package com.carddemo.account.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds this bounded context to the one PostgreSQL schema it owns, and to no other.
 *
 * <p>Exactly three concerns live here, and the closed four-class charter recorded in this package's
 * {@code package-info.java} assigns no fourth. The first is the schema-isolation contract: the session
 * search path pinned to a single schema on every pooled connection, so an unqualified table name can
 * only ever resolve inside this context. The second is the connection-pool sizing contract, whose
 * numbers come from configuration and are checked here rather than trusted. The third is the wiring that
 * holds the migration runner -- both the migration and its own history table -- inside that same
 * schema.</p>
 *
 * <h2>What this class replaces</h2>
 *
 * <p>The rows behind this pool were four separately defined CICS file resources. The stanzas open at
 * {@code app/csd/CARDDEMO.CSD} L1 for {@code ACCTDAT}, L37 for {@code CCXREF}, L50 for {@code CUSTDAT}
 * and L63 for {@code CXACAIX}; the first three become the tables {@code accounts}, {@code customers} and
 * {@code card_xref}, while the fourth was an alternate index over the cross-reference and becomes a
 * secondary index on that third table rather than a table of its own. Their record layouts are
 * {@code app/cpy/CVACT01Y.cpy} at 300 bytes, {@code app/cpy/CVCUS01Y.cpy} at 500 and
 * {@code app/cpy/CVACT03Y.cpy} at 50, and the batch programs that walked them sequentially are
 * {@code app/cbl/CBACT01C.cbl}, {@code app/cbl/CBCUS01C.cbl} and {@code app/cbl/CBACT03C.cbl}. Note that
 * the resource name at L37 is {@code CCXREF} even though the data set it names carries {@code CARDXREF};
 * the resource name is what the baseline programs open, so it is the one cited.</p>
 *
 * <p>Assumptions: those four are the whole of this context's scope. The stanzas opening at L13 and L25
 * belong to the card context, the one at L76 to the transaction context and the one at L88 to the
 * authentication context. Nothing configured here may reach any of them, and the pin this class installs
 * is the mechanism that makes reaching them impossible rather than merely discouraged.</p>
 *
 * <h2>Where the values come from, and where the contract comes from</h2>
 *
 * <p>Trade-offs: the numeric and environment-varying values are declared in
 * {@code services/account-service/src/main/resources/application.yml} and varied by its {@code -dev}
 * and {@code -prod} profiles, which that file's own closing notes fix as the only permitted axes of
 * variation; this class declares
 * none of them and hard-codes none of them. What this class contributes instead is the part a
 * configuration file cannot: a single compiled source for the schema name, and startup checks that fail
 * loudly when the declared configuration and the contract disagree. The compromise accepted is that the
 * schema binding is now expressed in two places rather than one, which is a real cost in reading. It is
 * accepted because the two places do different jobs -- the file supplies a value, this class refuses a
 * wrong one -- and because a pin that exists only as a string in a profile-overridable block can be lost
 * by an edit that looks unrelated to schema isolation.</p>
 *
 * <p>Alternatives Considered: declaring the {@link DataSource} bean here, which would have made this
 * class the literal constructor of the pool and is the more obvious reading of "owns pool sizing". It is
 * rejected on a concrete loss rather than on taste. A {@code DataSource} bean declared here makes the
 * framework's own pooled-datasource configuration back off entirely, and that configuration contributes
 * more than the pool: it also registers the post-processor that lets an externally supplied connection
 * detail override the pool's target, which is how the {@code testcontainers-postgresql} dependency
 * declared at {@code services/account-service/pom.xml} L517 to L519 points a repository test at its own
 * container. Re-declaring the bean would withdraw that and would additionally require the verified-TLS
 * driver properties at {@code application.yml} L701 and L702 to be re-applied by hand, where omitting
 * one would downgrade an encrypted connection silently. Extending the framework's pool through
 * documented extension points keeps both, and leaves exactly one {@code DataSource} bean in the
 * context.</p>
 *
 * <h2>Durability and isolation against the baseline</h2>
 *
 * <p>Trade-offs: the four baseline stanzas declare four separate attributes that are easy to read as
 * one. Each carries {@code JNLSYNCWRITE(YES)} beside {@code RECOVERY(NONE)} and {@code FWDRECOVLOG(NO)}
 * at {@code app/csd/CARDDEMO.CSD} L9, L46, L59 and L72, and each also carries {@code JOURNAL(NO)} at L7,
 * L44, L57 and L70 -- so the synchronous-write discipline applies to journal records the file never
 * produces, and nothing is written for recovery. All four additionally declare uncommitted read
 * integrity at L3, L40, L53 and L66, so a read there may observe a write that has not committed. The
 * baseline behaves that way; this service reads at committed-read isolation and holds its rows encrypted
 * at rest with automated backups, and the divergence is documented rather than reconciled. The
 * compromise is genuine: a read the baseline satisfied without regard to concurrent writers can now wait
 * on one. It is accepted because the alternative is to preserve a weaker guarantee over financial record
 * data for no reason beyond symmetry, and because no baseline behaviour depends on observing a partial
 * write.</p>
 *
 * <p>Assumptions: that committed-read level is pinned in configuration rather than by this class.
 * {@code application.yml} names it at L644 and records its reasoning at L622 to L643, so the level is
 * stated once, in the file that already owns every other pool setting this service varies by
 * environment. This class therefore sets no isolation level and installs no locking strategy -- naming a
 * level here would put one setting in two places that can disagree, and the concurrency contract is
 * owned elsewhere in any case. The baseline implements a before-image optimistic check across the
 * pseudo-conversational gap -- {@code app/cbl/COACTUPC.cbl} snapshots the pre-edit record at L669 and
 * carries the changed-record flag at L168 -- and the migrated form expresses that with a version column
 * on the two mutable entities plus the shared advice that renders a lost update as HTTP 409. A lock hint
 * set here would add a second, competing concurrency mechanism whose interaction with the version column
 * nothing documents.</p>
 */
// Trade-offs: the annotation set below is the whole of this class's framework surface, and three things
//   a datasource configuration is often expected to declare are deliberately absent from it.
//   (1) No transaction isolation level, because configuration already owns it: application.yml pins
//   TRANSACTION_READ_COMMITTED at L644 and records the reasoning at L622 to L643. Naming a level here
//   would put one setting in two places that can disagree, and whichever of the two lost would do so
//   silently. The baseline is the reason the level is stated explicitly somewhere rather than left to
//   whatever an engine ships: all four stanzas declare READINTEG(UNCOMMITTED) at app/csd/CARDDEMO.CSD
//   L3, L40, L53 and L66, so a read there may observe an uncommitted write, and each declares
//   RECOVERY(NONE) with FWDRECOVLOG(NO) at L9, L46, L59 and L72 and JOURNAL(NO) at L7, L44, L57 and L70,
//   so nothing is logged for recovery at all. The baseline behaves that way, this service reads at
//   committed-read isolation, and the divergence is documented. The compromise accepted is that a read
//   which the baseline satisfied while a writer was mid-transaction can now wait for that writer.
//   (2) No locking strategy. The baseline already performs a before-image optimistic check across the
//   pseudo-conversational gap -- app/cbl/COACTUPC.cbl snapshots the pre-edit record at L669 and carries
//   the changed-record flag at L168 -- and the migrated form expresses that as a version column on the
//   two mutable entities, which the domain package owns. A pessimistic default set here would compete
//   with that version column, and nothing would document which of the two decided a given conflict.
//   (3) No exception handling. Turning a failed version check into HTTP 409 belongs to
//   com.carddemo.common.error.GlobalExceptionHandler, which already renders it and reaches this context
//   by autoconfiguration rather than by scan. A handler declared here would give one exception two
//   mappings and make the answer depend on bean ordering.
// Assumptions: there is no batch configuration in this package and there must never be one. The batch
//   starter is version-managed centrally at services/pom.xml L750 but is deliberately NOT declared by
//   services/account-service/pom.xml, so the types such a class would reference are absent from this
//   module's compile classpath and the omission is enforced by the compiler rather than by agreement.
//   Chunk-oriented jobs, the durable job repository and the one cross-schema unit of work this migration
//   permits all belong to the batch context. The sequential readers this context absorbs --
//   app/cbl/CBACT01C.cbl, app/cbl/CBCUS01C.cbl and app/cbl/CBACT03C.cbl -- became read paths behind
//   endpoints here, not scheduled jobs, so nothing in this context needs a job repository to point at
//   this pool.
// Trade-offs: bean-method proxying is switched off. Every bean below is independent, so none calls
//   another's factory method, and the inter-bean interception that setting exists to support buys nothing
//   here while still costing a generated subclass of this class on every context refresh. The static
//   factory method below is unaffected by the choice -- a static bean method is admissible under either
//   setting -- so what is declined here is the redundant proxy and not any restriction on static
//   factories.
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /**
     * The one schema this bounded context owns.
     *
     * <p>This is the only place in this service's Java where the schema name is spelled. Anything that
     * needs it -- a test asserting the pin, a diagnostic message -- reads it from here rather than
     * repeating the literal, so the name cannot drift between two spellings.</p>
     */
    // Assumptions: the schema is created OUTSIDE this service and is assumed to exist before the
    //   context starts. data-migration/sql/V0__schemas_and_roles.sql is the exclusive authority for
    //   schemas, roles and grants across all eight bounded contexts, and it creates this one owned by a
    //   NOLOGIN role. Two consequences follow and getting either backwards produces a migration that
    //   fails on a clean database: this service's own migration at
    //   services/account-service/src/main/resources/db/migration/V1__account.sql issues no schema
    //   creation and currently contains none, and the migration runner is configured below with schema
    //   creation disabled. A service that created its own schema would own it, and ownership is what
    //   the bootstrap script assigns deliberately and per role.
    // Trade-offs: the name is a compiled constant while every other value this class touches arrives
    //   from configuration, and the asymmetry is deliberate. The schema name is an architectural
    //   invariant of the bounded context -- one schema per service, eight schemas across the migration
    //   -- so an environment that changed it would not be this service configured differently, it would
    //   be a different service. The connection target, the credentials and the pool numbers genuinely
    //   do vary by environment, so they stay in application.yml and its two profiles, which are owned by
    //   another author and are neither created nor edited from here. The cost accepted is that changing
    //   the schema name now requires a code change and a rebuild; that is the intended cost, because it
    //   forces the change through review rather than through a variable.
    public static final String SCHEMA_NAME = "account";

    /**
     * The statement that pins a session to {@link #SCHEMA_NAME} alone.
     *
     * <p>It names one schema and no fallback, so an object absent from that schema fails to resolve
     * instead of being found in another context's schema or in the database's default one.</p>
     */
    // Alternatives Considered: three other mechanisms were available, and the reason this one is used
    //   is coverage rather than preference, because the three layers that reach this database do not all
    //   read the same setting.
    //   (1) Appending a currentSchema parameter to the connection URL, which is the most common answer.
    //   Unavailable here: application.yml L540 takes the URL from the environment as an opaque string,
    //   so this code cannot inspect or extend it, and requiring whoever sets the variable to remember a
    //   parameter fails silently when forgotten.
    //   (2) The Hibernate default-schema setting alone, declared at application.yml L773. It governs
    //   mapped entities and is necessary, but it does not reach a hand-written native statement, and it
    //   does not reach the migration runner at all.
    //   (3) The migration runner's own initialisation statements, declared at application.yml L916.
    //   Those run only on the runner's connections, which are not this pool's connections at all -- the
    //   runner is given its own credential at application.yml L892 and L893, so the framework builds it
    //   a separate data source, and the pool statement below never executes there.
    //   Pool initialisation SQL is used because it is the one mechanism that covers every consumer of
    //   the pool uniformly: mapped entities, native statements and anything else that borrows a
    //   connection. The migration runner, which the pool cannot reach, is pinned separately below, and
    //   the two together are what make the coverage complete.
    // Assumptions: this statement is safe before any table exists. PostgreSQL resolves search-path
    //   entries lazily and does not validate the name when the path is set, so it succeeds on an empty
    //   schema and the migration then populates it. It is not a substitute for the schema existing:
    //   creation is disabled below, so a database on which the bootstrap DDL never ran fails at the
    //   migration and names the absent schema.
    static final String SEARCH_PATH_STATEMENT = "SET search_path TO " + SCHEMA_NAME;

    /**
     * The query that reports the search path actually in force on a borrowed connection.
     *
     * <p>The startup check reads the effective value rather than re-reading the declared one, because a
     * declaration that never took effect is exactly the failure worth catching.</p>
     */
    private static final String EFFECTIVE_SEARCH_PATH_QUERY = "SHOW search_path";

    private static final String MAXIMUM_POOL_SIZE_PROPERTY = "spring.datasource.hikari.maximum-pool-size";

    private static final String MINIMUM_IDLE_PROPERTY = "spring.datasource.hikari.minimum-idle";

    private static final Logger LOGGER = LoggerFactory.getLogger(DataSourceConfig.class);

    /**
     * Makes this class, rather than a configuration file, the authority on the pooled search path.
     *
     * <p>The returned post-processor sees each pool as it finishes initialising and takes one of three
     * actions. A pool that declares no initialisation statement is given {@link #SEARCH_PATH_STATEMENT}.
     * A pool that already declares that same statement is accepted unchanged, which is the ordinary case
     * because {@code application.yml} L669 declares it. A pool that declares some other statement is
     * refused, and startup stops.</p>
     *
     * @return the post-processor that installs or vets the pin on every pool in this context, never
     *     {@code null}
     */
    @Bean
    static BeanPostProcessor accountSchemaSearchPathEnforcer() {
        // Alternatives Considered: overwriting whatever the configuration declared, which is simpler
        //   and is rejected. The declared statement is the one place an operator can add a legitimate
        //   session setting, so silently discarding it would remove a setting nothing then reports as
        //   missing. Refusing a statement that pins anything other than this schema, and supplying the
        //   pin only when none was declared, keeps this class authoritative about schema isolation
        //   without making it authoritative about every session setting.
        // Assumptions: the post-processing hook used below runs after property binding and before the
        //   pool is first borrowed from, which is what makes the write legal. The framework binds
        //   configuration to a pool in the BEFORE-initialisation hook, and a bean's before-hooks all
        //   complete before any after-hook runs, so the declared value is already present when this is
        //   consulted. A pool built by the framework's own builder is also unsealed until its first
        //   connection request, so a late write would fail loudly rather than be ignored -- and the
        //   guard below turns that into a named diagnostic instead of a framework-level message.
        // Trade-offs: the method is static so that registering this post-processor does not force the
        //   enclosing configuration class to be instantiated ahead of the post-processor chain. The
        //   accepted cost is that no instance state is reachable from here, which is why every helper
        //   this hook calls is static too.
        return new BeanPostProcessor() {

            /**
             * Installs or vets the schema pin on a pool that has just finished initialising.
             *
             * @param bean the freshly initialised bean; only a connection pool is acted on and every
             *     other bean is returned untouched
             * @param beanName the bean's name, used solely to name the offending pool in a diagnostic
             * @return the same instance that was supplied, never a replacement and never {@code null}
             */
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource pool) {
                    applyOrVerifySearchPathPin(pool, beanName);
                }
                return bean;
            }
        };
    }

    /**
     * Holds the migration runner, and its own history table, inside this context's schema.
     *
     * @return the customiser applied to the migration runner's configuration, never {@code null}
     */
    @Bean
    FlywayConfigurationCustomizer accountSchemaFlywayCustomizer() {
        // Assumptions: the three settings below are applied in code even though application.yml already
        //   declares them at L809, L810 and L834, because a customiser is the only form that cannot be
        //   overridden by a profile. The framework applies the declared properties first and the
        //   customisers afterwards, so this is the last word on all three. The two schema settings do
        //   different jobs and both are stated rather than one being left to follow the other: the first
        //   names the set the runner may manage, and the second decides where its history table lives
        //   and how an unqualified name inside a migration resolves. Naming the second is what keeps
        //   each service's migration history in its own schema, so one service's history cannot be
        //   truncated by another's.
        // Assumptions: schema creation is disabled because
        //   data-migration/sql/V0__schemas_and_roles.sql already created this schema and assigned its
        //   owner. The runner may migrate this schema and may not create it, which is why
        //   services/account-service/src/main/resources/db/migration/V1__account.sql contains no schema
        //   creation and must not acquire one. The cost is that a bare database needs its schema created
        //   before this service can migrate; that is a visible precondition rather than a default that
        //   conceals a missing precondition.
        // Assumptions: the runner works at all only because two artifacts are on the classpath, and the
        //   second is easy to omit. services/account-service/pom.xml L398 declares the starter that
        //   activates migration -- the framework moved that autoconfiguration into its own module, so
        //   the migration engine alone resolves, compiles and starts while applying nothing. L402
        //   declares the PostgreSQL-specific companion, which the engine has required as a separate
        //   artifact since it stopped shipping database support in its core. Neither omission is a
        //   compile error; both surface at run time as a missing relation, which is precisely why the
        //   pair is recorded here rather than left silent.
        return configuration -> configuration
                .schemas(SCHEMA_NAME)
                .defaultSchema(SCHEMA_NAME)
                .createSchemas(false);
    }

    /**
     * Proves at startup that the pool honours both contracts this class owns.
     *
     * <p>Sizing is checked against the values configuration declared, and the search path is read back
     * from a real borrowed connection so that a declaration which never took effect is caught.</p>
     *
     * @param dataSource the context's single pool, borrowed from once to observe the search path in
     *     force; must not be {@code null}
     * @param maximumPoolSize the declared pool ceiling, read from configuration rather than chosen here
     * @param minimumIdle the declared idle floor, read from configuration rather than chosen here
     * @return the startup hook that performs the verification, never {@code null}
     */
    @Bean
    SmartInitializingSingleton accountSchemaIsolationVerifier(
            DataSource dataSource,
            @Value("${" + MAXIMUM_POOL_SIZE_PROPERTY + "}") int maximumPoolSize,
            @Value("${" + MINIMUM_IDLE_PROPERTY + "}") int minimumIdle) {
        // Alternatives Considered: two other hooks were available and both are wrong here. A
        //   construction-time callback on this class runs before the pool has been bound, so it would
        //   read the framework's defaults rather than the configured values and would report a pool
        //   that does not exist yet. A command-line runner runs after the context has finished
        //   refreshing, by which point the web server is already accepting requests, so a service with
        //   an unpinned search path could answer with another context's data before the check spoke. The
        //   hook used here fires once all singletons exist and before the server starts, which is the
        //   earliest point at which the whole contract is observable and still the last point at which
        //   refusing to start is free.
        // Assumptions: borrowing one connection here adds no failure mode of its own. The migration
        //   runner already opens connections during this same phase, so a database that cannot be
        //   reached has already stopped startup before this runs.
        // Assumptions: the sizing values are read as declared rather than from the pool, because the
        //   pool does not keep them. When an idle floor exceeds the ceiling, the pool replaces the floor
        //   with the ceiling inside its own numeric validation and writes no log line at all -- read
        //   back from a started pool the two values would then agree and the misconfiguration would be
        //   invisible. Reading the declared values is what makes it visible.
        // Trade-offs: neither property carries a default, so removing one from configuration stops
        //   startup with a message naming it. The alternative of defaulting to the pool's own numbers
        //   was rejected because it would put a sizing number in this file, and the sizing must vary by
        //   environment: application.yml declares the base pair at L585 and L594, application-dev.yml
        //   narrows the ceiling at L135 for a cluster that scales toward zero, and
        //   application-prod.yml widens it at L139. The invariant those numbers must satisfy cannot be
        //   checked here because half of it lives outside this deployable: running task count
        //   multiplied by this ceiling must stay within the database cluster's connection budget. What
        //   is checked here is the half that is local and self-contained, and the ceiling is logged so
        //   the other half can be audited against a task count from outside.
        // Alternatives Considered: a second pool for reads, or a reader-endpoint datasource alongside
        //   this one. Rejected: reporting reads reach this data through read-only cross-schema views
        //   owned by the reporting context rather than through a replica, so a second pool would add
        //   cost and replica-lag semantics to a context that gains no parity benefit from either. The
        //   one documented cross-schema exception in this migration belongs to the batch context, whose
        //   posting unit of work commits ledger and account rows in a single transaction; it is not
        //   this context's, and no grant or pool here may anticipate it.
        return () -> verifyPoolContract(dataSource, maximumPoolSize, minimumIdle);
    }

    /**
     * Installs the schema pin on a pool that declares none, and refuses one that pins anything else.
     *
     * @param pool the pool being vetted, whose initialisation statement is read and, when absent,
     *     written; must not be {@code null}
     * @param beanName the pool's bean name, included in any diagnostic so the offending pool is
     *     identifiable in a context that holds more than one
     * @throws IllegalStateException if the pool already declares an initialisation statement that pins
     *     something other than {@link #SCHEMA_NAME}, or if the pool has already been started and can no
     *     longer accept the pin
     */
    private static void applyOrVerifySearchPathPin(HikariDataSource pool, String beanName) {
        String declared = pool.getConnectionInitSql();
        if (declared == null || declared.isBlank()) {
            if (pool.isRunning()) {
                throw new IllegalStateException("Connection pool '" + beanName
                        + "' was started before its search path could be pinned to schema '"
                        + SCHEMA_NAME + "', so a connection may already have resolved an unqualified"
                        + " table name outside this bounded context");
            }
            pool.setConnectionInitSql(SEARCH_PATH_STATEMENT);
            LOGGER.info("Pinned the search path of connection pool '{}' to schema '{}'", beanName,
                    SCHEMA_NAME);
            return;
        }
        // Assumptions: the comparison is deliberately exact after normalisation rather than a
        //   containment test. A statement such as one naming this schema followed by a fallback schema
        //   would contain the schema name and would still break isolation, because an object absent here
        //   would then be found in the fallback instead of failing. Normalisation forgives only what
        //   cannot change meaning: surrounding space, internal run-length of whitespace, a trailing
        //   statement terminator and letter case.
        if (!normaliseStatement(declared).equals(normaliseStatement(SEARCH_PATH_STATEMENT))) {
            throw new IllegalStateException("Connection pool '" + beanName
                    + "' declares the connection initialisation statement [" + declared
                    + "] which does not pin the search path to schema '" + SCHEMA_NAME
                    + "' alone; this bounded context owns one schema and an unqualified table name must"
                    + " not be able to resolve outside it");
        }
    }

    /**
     * Reduces a session statement to a form in which only a difference of meaning still differs.
     *
     * @param statement the statement as configuration declared it, or as this class composed it; must
     *     not be {@code null}
     * @return the statement trimmed, with internal whitespace collapsed to single spaces, any trailing
     *     terminator removed and letters upper-cased, never {@code null}
     */
    private static String normaliseStatement(String statement) {
        // Trade-offs: the expression is compiled on each call rather than held in a precompiled constant.
        //   This runs twice per pool during context refresh and never again, so a cached pattern would
        //   add a member and a name to the class in exchange for saving two compilations across the
        //   lifetime of a task. The simpler form is preferred where the call count is bounded by the
        //   number of pools in the context, which is one.
        String collapsed = statement.trim().replaceAll("\\s+", " ");
        String unterminated = collapsed.endsWith(";")
                ? collapsed.substring(0, collapsed.length() - 1).trim()
                : collapsed;
        // Assumptions: the fold is locale-independent because one of the two statements it reduces is
        //   operator-supplied. The declared statement arrives from configuration -- application.yml
        //   declares it at L669 -- and may be written in any mixture of cases, so the comparison has to
        //   reduce it identically wherever the task runs. Locale.ROOT fixes the mapping of every letter
        //   regardless of the platform default locale, which is what makes acceptance a property of the
        //   declared statement rather than of the host that happened to read it.
        return unterminated.toUpperCase(Locale.ROOT);
    }

    /**
     * Verifies both pool contracts and converts a driver-level failure into a startup failure.
     *
     * @param dataSource the pool to borrow one connection from; must not be {@code null}
     * @param maximumPoolSize the declared pool ceiling
     * @param minimumIdle the declared idle floor
     * @throws IllegalStateException if the declared sizing is inconsistent, if the search path in force
     *     is not {@link #SCHEMA_NAME} alone, or if the connection needed to observe it could not be
     *     obtained or queried
     */
    private static void verifyPoolContract(DataSource dataSource, int maximumPoolSize, int minimumIdle) {
        verifyPoolSizing(maximumPoolSize, minimumIdle);
        try {
            verifyEffectiveSearchPath(dataSource);
        } catch (SQLException failure) {
            // Assumptions: the driver failure is wrapped rather than propagated, and the cause is kept.
            //   The hook this runs from declares no checked exception, so wrapping is required; keeping
            //   the cause is what preserves the driver's own message, which is the part that says
            //   whether the database was unreachable, the credential was refused or the certificate did
            //   not verify.
            throw new IllegalStateException("Could not confirm that the connection pool resolves"
                    + " unqualified table names inside schema '" + SCHEMA_NAME + "' only", failure);
        }
    }

    /**
     * Checks that the declared pool sizing is internally consistent.
     *
     * @param maximumPoolSize the declared pool ceiling, which must admit at least one connection
     * @param minimumIdle the declared idle floor, which must be neither negative nor above the ceiling
     * @throws IllegalStateException if either bound is out of range, or if the floor exceeds the ceiling
     */
    private static void verifyPoolSizing(int maximumPoolSize, int minimumIdle) {
        if (maximumPoolSize < 1) {
            throw new IllegalStateException("Property " + MAXIMUM_POOL_SIZE_PROPERTY + " is "
                    + maximumPoolSize + ", so the pool would admit no connection and every read of"
                    + " schema '" + SCHEMA_NAME + "' would fail");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalStateException("Property " + MINIMUM_IDLE_PROPERTY + " is " + minimumIdle
                    + " against " + MAXIMUM_POOL_SIZE_PROPERTY + " of " + maximumPoolSize
                    + "; the idle floor may not be negative or exceed the ceiling, and left unchecked"
                    + " the pool would substitute the ceiling for the floor and hold every connection"
                    + " open for the life of the task");
        }
        // Assumptions: the ceiling is logged at startup because the invariant it belongs to cannot be
        //   evaluated inside one task. The number of concurrently running tasks multiplied by this
        //   ceiling has to stay within the database cluster's connection budget, and this deployable
        //   knows only its own factor; emitting it gives whoever holds the other factor something to
        //   audit against instead of a number they have to infer from a configuration file.
        LOGGER.info("Connection pool sizing accepted for schema '{}': ceiling {}, idle floor {}",
                SCHEMA_NAME, maximumPoolSize, minimumIdle);
    }

    /**
     * Reads back the search path in force on a pooled connection and refuses anything wider.
     *
     * @param dataSource the pool to borrow one connection from; must not be {@code null}
     * @throws SQLException if the connection cannot be obtained, or if the search path cannot be read
     * @throws IllegalStateException if the search path in force is empty, or names anything other than
     *     {@link #SCHEMA_NAME} alone
     */
    private static void verifyEffectiveSearchPath(DataSource dataSource) throws SQLException {
        // Assumptions: exactly one connection is borrowed and it is returned by the try-with-resources
        //   block, so this check does not consume a pool slot beyond its own duration. It reads the
        //   EFFECTIVE path rather than the configured statement because the two can differ: a statement
        //   declared on a pool this class never saw, or a role-level default on the connecting user,
        //   both produce a session whose path was never what configuration said it was.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet inForce = statement.executeQuery(EFFECTIVE_SEARCH_PATH_QUERY)) {
            String observed = inForce.next() ? inForce.getString(1) : null;
            if (observed == null || !SCHEMA_NAME.equals(observed.trim())) {
                throw new IllegalStateException("The search path in force on a pooled connection is ["
                        + observed + "] rather than schema '" + SCHEMA_NAME
                        + "' alone, so an unqualified table name could resolve into another bounded"
                        + " context's schema or into the database's default schema");
            }
            LOGGER.info("Verified that a pooled connection resolves unqualified names in schema '{}'"
                    + " only", SCHEMA_NAME);
        }
    }
}
