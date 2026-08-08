package com.carddemo.authorization.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies this module's single pooled JDBC data source, bounds every database interaction it makes
 * with explicit timeouts, and proves at startup that a pooled connection resolves inside the one
 * schema the pending-authorization bounded context owns.
 *
 * <h2>Purpose</h2>
 *
 * <p>Three responsibilities and deliberately no others. The pool is built here so that its type is
 * a decision of this module rather than a consequence of whichever pool happens to reach the
 * classpath, while every number that sizes it is bound from external configuration. The four
 * timeouts that bound a database interaction are contributed here because no other file in this
 * module declares any of them. And the schema pin, which is configured entirely in
 * {@code src/main/resources/application.yml}, is verified here against the schema the migration
 * engine was separately told to use.</p>
 *
 * <p>No business rule lives here. The pessimistic locking that serialises a decision on one pending
 * authorization, the fraud-marking guard and the outbox drain belong to the sibling
 * {@code service} and {@code repository} packages, so a reader looking for why an authorization was
 * declined will not find the answer in this class.</p>
 *
 * <h2>There is no second resource manager, and that absence is the point</h2>
 *
 * <p>Refactoring Rationale: a reader who knows the baseline arrives at this class looking for the
 * second resource manager and has to find the explanation rather than a gap. In the baseline,
 * marking a pending authorization as fraudulent writes to two different resource managers inside
 * one unit of work, and the structure that joins them is worth setting out precisely because it is
 * what decides the shape of this class.</p>
 *
 * <ol>
 *   <li>The screen program reaches the fraud program by
 *       {@code EXEC CICS LINK PROGRAM(WS-PGM-AUTH-FRAUD) COMMAREA(WS-FRAUD-DATA) NOHANDLE} at
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L248-L252, naming the target at
 *       L35. A linked program runs inside the caller's unit of work rather than starting one of its
 *       own.</li>
 *   <li>The linked program writes the relational table:
 *       {@code INSERT INTO CARDDEMO.AUTHFRDS} at
 *       {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L142, and
 *       {@code UPDATE CARDDEMO.AUTHFRDS} at L223 under the {@code FRAUD-UPDATE.} label at L221.
 *       That file is 244 lines long and contains no {@code SYNCPOINT} at any of them: its only
 *       three {@code EXEC CICS} statements are {@code ASKTIME} at L91, {@code FORMATTIME} at L95
 *       and {@code RETURN} at L218. Its write is therefore still uncommitted when control
 *       returns.</li>
 *   <li>Back in the caller, {@code COPAUS1C.cbl} L253-L255 proceeds only on a normal response and
 *       writes the hierarchical segment under {@code UPDATE-AUTH-DETAILS.} at L520 -- the record
 *       move at L522 and {@code EXEC DLI REPL USING PCB(PAUT-PCB-NUM) SEGMENT (PAUTDTL1) FROM
 *       (PENDING-AUTH-DETAILS)} at L525-L528.</li>
 *   <li>One commit then covers both writes: L533 performs the paragraph at L557, whose
 *       {@code EXEC CICS SYNCPOINT} is at L558. The failure edge is symmetrical, L540 performing
 *       the paragraph at L565 whose {@code EXEC CICS SYNCPOINT ROLLBACK} is at L566-L568. The
 *       transaction-manager half of the arrangement is declared in
 *       {@code app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd}, which carries
 *       {@code ACTION(BACKOUT)} at L45, L55 and L65 and {@code DROLLBACK(YES)} at L71 on
 *       {@code DEFINE DB2ENTRY(AWS01PLN)} at L69.</li>
 * </ol>
 *
 * <p>Two resource managers under one commit require something to coordinate them, and in the
 * baseline the transaction monitor is that something. In this context all four tables the same unit
 * of work touches live in one schema of one database -- {@code pending_auth_summary},
 * {@code pending_auth_detail}, {@code auth_fraud} and the reply outbox, created together by
 * {@code src/main/resources/db/migration/V1__authorization.sql}. The unit of work is therefore a
 * single local transaction, and there is no second resource manager for a coordinator to coordinate
 * with. Two-phase commit is eliminated rather than emulated, which is why this class declares one
 * {@link DataSource} and no transaction manager at all, leaving the framework's local JPA manager
 * in place. The baseline path is untouched and keeps running exactly as it does; this is the shape
 * the added path has.</p>
 *
 * <p>Alternatives Considered: an XA data source paired with a JTA transaction manager, retaining
 * the distributed-commit shape the baseline has. Rejected on a concrete consequence rather than on
 * taste: it would reintroduce the two-phase-commit failure modes -- heuristic outcomes, in-doubt
 * transactions and a recovery log to administer -- in order to protect an invariant that a
 * single-schema design already guarantees locally. Paying for a coordinator buys nothing once there
 * is only one participant left to coordinate, and it would leave an operator diagnosing in-doubt
 * transactions that cannot arise.</p>
 *
 * <h2>Every database interaction is bounded, on both of the tiers that were not</h2>
 *
 * <p>Refactoring Rationale: the baseline places no effective time limit on either of its two tiers,
 * so the bounds below are additions rather than transcriptions, and both tiers are cited because a
 * claim about one would describe half the system.</p>
 *
 * <ul>
 *   <li>Online: {@code app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd} L43 declares
 *       {@code DTIMOUT(NO)} on the fraud-detail transaction, so there is no deadlock timeout at all
 *       -- the same value recurs at L53 and L63 for the other two transactions -- and L72 declares
 *       {@code THREADLIMIT(1) THREADWAIT(YES)}, so a task with nowhere to run waits instead of
 *       being refused.</li>
 *   <li>Batch: {@code app/app-authorization-ims-db2-mq/jcl/LOADPADB.JCL} L2 carries
 *       {@code REGION=0M,NOTIFY=&amp;SYSUID,TIME=1440}, which is a job step limit of 1440 minutes
 *       and no storage limit; {@code UNLDPADB.JCL} L2 and {@code UNLDGSAM.JCL} L2 are identical.
 *       Two jobs in the same tree differ and neither is generalised away:
 *       {@code jcl/CBPAUP0J.jcl} L2 carries {@code REGION=0M,NOTIFY=&amp;SYSUID} with no
 *       {@code TIME=} at all, and {@code jcl/DBPAUTP0.jcl} L2 carries {@code REGION=0K,TIME=30},
 *       the one bounded job in the tree.</li>
 * </ul>
 *
 * <p>Alternatives Considered: leaving all four bounds to driver and pool defaults. Rejected because
 * the PostgreSQL defaults for two of them are literally unbounded -- an unset
 * {@code statement_timeout} and {@code lock_timeout} both report {@code 0}, which is the server's
 * own spelling of "no limit" -- so accepting the defaults would carry the baseline's unbounded
 * posture forward into a place where it costs more. A stalled statement would hold a pooled
 * connection for as long as it stalled, and because each task's pool is finite, one stall would
 * spread into request failures across the whole task instead of staying contained to the caller
 * that provoked it.</p>
 *
 * <h2>Configuration consumed</h2>
 *
 * <p>Every value arrives from configuration and no schema name, endpoint or credential is written
 * into this class.</p>
 *
 * <ul>
 *   <li>{@code spring.datasource.url}, {@code spring.datasource.username} and
 *       {@code spring.datasource.password} reach this class only through the injected
 *       {@link DataSourceProperties}. All three are unresolved placeholders with no fallback, so an
 *       unset variable aborts context refresh and names itself rather than leaving this context
 *       writing authorization decisions into some other database.</li>
 *   <li>{@code spring.datasource.hikari.connection-init-sql} carries the search-path statement, so
 *       the schema pin takes effect at the connection boundary. This class reads that property and
 *       declares no schema of its own, which is the division of labour the property's own note in
 *       {@code application.yml} reserves.</li>
 *   <li>{@code spring.datasource.hikari.maximum-pool-size}, {@code minimum-idle},
 *       {@code connection-timeout}, {@code idle-timeout}, {@code max-lifetime}, {@code pool-name}
 *       and the {@code data-source-properties} map bind onto the pool returned below, which is why
 *       this class holds no pool-sizing number at all.</li>
 *   <li>{@code carddemo.datasource.connect-timeout-ms}, {@code read-timeout-ms},
 *       {@code statement-timeout-ms} and {@code lock-timeout-ms} are the four bounds this class
 *       contributes. Each carries a default, so a profile narrows a bound by overriding it rather
 *       than by having to declare all four.</li>
 *   <li>{@code spring.flyway.default-schema} is read only so that it can be compared against what
 *       a connection actually resolves. Nothing here configures the migration engine.</li>
 * </ul>
 *
 * <h2>Beans contributed</h2>
 *
 * <p>Exactly two. One {@link HikariDataSource}, which the repositories, the migration engine and
 * the JPA entity manager factory all draw their connections from. One
 * {@link SmartInitializingSingleton}, which runs once after the context's ordinary singletons exist
 * and yields the verified schema name.</p>
 *
 * <h2>Startup failure modes</h2>
 *
 * <ol>
 *   <li>An unset datasource variable fails placeholder resolution before a socket is opened.</li>
 *   <li>A timeout set to zero or below, or a set of four that is not ordered, raises
 *       {@link IllegalStateException} while the pool is being built and names the property at
 *       fault.</li>
 *   <li>An unreachable database exhausts the configured acquisition timeout and fails context
 *       refresh, so a task never reports healthy while unable to serve a read.</li>
 *   <li>A schema the connection does not resolve, or a session timeout that arrives unbounded,
 *       raises {@link IllegalStateException} from the verifier below.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /** Configuration key holding the socket-establishment bound, in milliseconds. */
    private static final String CONNECT_TIMEOUT_MS_KEY = "carddemo.datasource.connect-timeout-ms";

    /** Configuration key holding the response-wait bound on an established socket, in ms. */
    private static final String READ_TIMEOUT_MS_KEY = "carddemo.datasource.read-timeout-ms";

    /** Configuration key holding the server-side single-statement bound, in milliseconds. */
    private static final String STATEMENT_TIMEOUT_MS_KEY =
            "carddemo.datasource.statement-timeout-ms";

    /** Configuration key holding the server-side row-lock wait bound, in milliseconds. */
    private static final String LOCK_TIMEOUT_MS_KEY = "carddemo.datasource.lock-timeout-ms";

    /** Configuration key naming the schema the migration engine places its history table in. */
    private static final String MIGRATION_SCHEMA_KEY = "spring.flyway.default-schema";

    /** Driver connection property bounding socket establishment, expressed in whole seconds. */
    private static final String CONNECT_TIMEOUT_PROPERTY = "connectTimeout";

    /** Driver connection property bounding a wait for a response, expressed in whole seconds. */
    private static final String READ_TIMEOUT_PROPERTY = "socketTimeout";

    /** Driver connection property carrying server parameters applied to each new session. */
    private static final String SESSION_OPTIONS_PROPERTY = "options";

    /** Milliseconds in one second, the single conversion factor this class applies. */
    private static final int MILLIS_PER_SECOND = 1000;

    // Assumptions: this reads the schema a connection RESOLVES rather than re-reading the
    //   configuration that set it, and the two are not the same claim. PostgreSQL accepts a search
    //   path naming a schema that does not exist and silently ignores the entry, so a comparison
    //   drawn from configuration alone would agree with itself while the connection resolved
    //   something else or nothing at all. The two settings are read in the same round trip because
    //   they are read for the same reason -- to establish that what was configured actually took
    //   effect on a real session -- and one statement spends one connection acquisition instead of
    //   three.
    private static final String SESSION_POSTURE_QUERY =
            "SELECT current_schema(), current_setting('statement_timeout'),"
                    + " current_setting('lock_timeout')";

    // Assumptions: PostgreSQL spells "no limit" as exactly this for both session timeouts, which is
    //   what makes an equality test against it a meaningful check rather than a style preference.
    //   The reported value is NOT parsed into a number, because the server normalises it to the
    //   largest exact unit -- 15000 milliseconds is reported as 15s -- so a numeric parse would
    //   have to know the unit table and would break on a value expressed in min or h. Comparing
    //   against the disabled sentinel needs no unit knowledge at all.
    private static final String TIMEOUT_DISABLED = "0";

    /**
     * Builds the one pooled data source this module draws every database connection from, and
     * contributes the four bounds that stop any single interaction with it running without a limit.
     *
     * @param properties the {@link DataSourceProperties} bound from {@code spring.datasource},
     *     carrying the JDBC URL, the username, the password and the driver this class never names
     *     itself; must not be {@code null}
     * @param connectTimeoutMillis the {@code int} bound in milliseconds on establishing a socket to
     *     the database, from {@code carddemo.datasource.connect-timeout-ms}; must be positive
     * @param readTimeoutMillis the {@code int} bound in milliseconds on waiting for a response on an
     *     established socket, from {@code carddemo.datasource.read-timeout-ms}; must be positive and
     *     must exceed the statement bound
     * @param statementTimeoutMillis the {@code int} bound in milliseconds the server applies to a
     *     single statement, from {@code carddemo.datasource.statement-timeout-ms}; must be positive
     *     and must exceed the lock bound
     * @param lockTimeoutMillis the {@code int} bound in milliseconds the server applies to waiting
     *     for a row lock, from {@code carddemo.datasource.lock-timeout-ms}; must be positive and
     *     must be the smallest of the three server-side and socket bounds
     * @return the {@link HikariDataSource} whose sizing, isolation, transport properties and
     *     connection-initialization statement are bound from {@code spring.datasource.hikari} and
     *     whose four timeout properties are set here, never {@code null}
     * @throws IllegalStateException if any bound is zero or negative, or if the three ordered bounds
     *     do not ascend from lock through statement to read
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            DataSourceProperties properties,
            @Value("${" + CONNECT_TIMEOUT_MS_KEY + ":30000}") int connectTimeoutMillis,
            @Value("${" + READ_TIMEOUT_MS_KEY + ":30000}") int readTimeoutMillis,
            @Value("${" + STATEMENT_TIMEOUT_MS_KEY + ":15000}") int statementTimeoutMillis,
            @Value("${" + LOCK_TIMEOUT_MS_KEY + ":5000}") int lockTimeoutMillis) {

        // WHY : Assumptions: the ordering is checked before the pool exists, because a set of bounds
        //       that does not ascend is a misconfiguration of this module that needs no database to
        //       detect. Detecting it here names the property at fault; detecting it later would
        //       present as a query behaving oddly under contention.
        requireAscendingTimeoutBounds(lockTimeoutMillis, statementTimeoutMillis, readTimeoutMillis);

        // WHY : Trade-offs: building the pool here rather than leaving the framework to build it
        //       makes the pool type this module's own decision, and the cost is specific. A data
        //       source declared in application code takes the place of the framework's own, so
        //       connection details contributed as a bean by a test harness no longer reach it and a
        //       container-backed integration test has to publish its URL, username and password as
        //       spring.datasource properties, which this method does read. What is bought is that
        //       the pool cannot change type because an unrelated dependency reordered its own
        //       transitives, and that the four properties added below bind onto a pool one file can
        //       be read against.
        HikariDataSource pool =
                properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();

        // WHY : Assumptions: every number that sizes this pool is bound from
        //       spring.datasource.hikari, so this method holds none of them -- and the quantity that
        //       has to fit inside the cluster's connection budget is not the ceiling written in any
        //       one file but THE PRODUCT OF THAT CEILING WITH THE RUNNING TASK COUNT, summed again
        //       across the seven peer contexts and the batch tasks that share the cluster. Each
        //       Fargate task holds its own pool, so autoscaling moves the second term; a scale-out
        //       driven by a fault rather than by demand is the case where that product bites, and it
        //       is named as a risk in docs/adr/ADR-002-compute-platform.md. Alternatives Considered:
        //       leaving the ceiling at the framework default. Rejected because the default is a
        //       per-task number chosen without knowledge of the task count, so the tier-wide total
        //       would be whatever autoscaling happened to make it, and the first symptom would be a
        //       peer context failing to connect at all.
        //
        // WHY : Assumptions: the pool is sized for concurrency because this service can use it, which
        //       is a structural difference from the baseline rather than a tuning choice. The
        //       baseline serialised: app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd L72 declares
        //       THREADLIMIT(1) with THREADWAIT(YES), so tasks queued for a single database thread,
        //       and CONCURRENCY(QUASIRENT) on all four programs -- L14, L21, L28 and L35 -- had the
        //       transaction monitor serialise them on one task control block. There is no
        //       single-thread ceiling to carry forward here, so the numbers bound above are not a
        //       transcription of a baseline limit and should not be read as one.
        //
        // WHY : Assumptions: the durability and encryption posture of what this pool connects TO is
        //       provisioned by Terraform and is deliberately absent from this class. The baseline
        //       ran its region jobs with logging suppressed, pairing IMSLOGR DD DUMMY with IEFRDER DD
        //       DUMMY in four of them -- jcl/LOADPADB.JCL L46-L47, jcl/UNLDGSAM.JCL L48-L49,
        //       jcl/CBPAUP0J.jcl L43-L44 and jcl/UNLDPADB.JCL L64-L65, the fifth DFSRRC00 job at
        //       jcl/DBPAUTP0.jcl L15 carrying neither. The target cluster is encrypted at rest with a
        //       customer-managed key and takes automated backups, and none of that is expressible
        //       through a connection property, so attempting it here would produce a class that
        //       appeared to guarantee something it cannot reach. The one transport control this class
        //       does inherit rather than set is sslmode, which application.yml pins to verify-full.
        //
        // WHY : Alternatives Considered: three other ways of making an unqualified table name
        //       resolve inside this context's own schema were weighed, and all three lose to the
        //       search-path pin that application.yml already carries -- which is why no schema name
        //       appears in this file. Writing the schema into every statement keeps the guarantee at
        //       each call site, so it holds only while every future query author remembers it, and
        //       an omission still resolves against whatever the search path happened to be rather
        //       than failing. Declaring the schema on each mapped entity covers the mapped entities
        //       and nothing else: not a native query, not the migration engine's own history table.
        //       Appending currentSchema to the JDBC URL is unavailable here because that URL arrives
        //       as an opaque placeholder, so the parameter would have to be remembered by whoever
        //       sets the variable and fails silently when it is not.
        //
        // WHY : Assumptions: this context is the ONE place in the reactor where the mapping layer is
        //       deliberately NOT told a schema name, and the reason is a property of the name
        //       itself. `authorization` is a PostgreSQL reserved keyword, and the persistence
        //       provider does not quote a qualifier it was handed unquoted, so setting
        //       spring.jpa.properties.hibernate.default_schema here would turn every generated
        //       statement into a syntax error rather than a wrong lookup. That is why the verifier
        //       below compares ONE configured name and not two, where the peer contexts compare
        //       both: there is no second name to compare, and a reader should not read its absence
        //       as a dropped comparison.
        //
        // WHY : Assumptions: these four entries survive the @ConfigurationProperties binding that
        //       runs after this method returns, because the binder binds INTO the pool's existing
        //       properties map rather than replacing it. That was measured on this tree rather than
        //       assumed, and it is the whole reason the timeouts can be set here while application.yml
        //       keeps contributing its transport keys to the same map. It also means a key set in
        //       application.yml under data-source-properties would override the same key set here,
        //       which is the intended precedence: the resources channel owns the last word.
        // WHY : Trade-offs: the connect bound defaults to the LARGER of the two waits that govern
        //       getting a connection, not the smaller, and the direction is the non-obvious part. The
        //       caller-facing wait is the pool's own acquisition timeout, which each profile already
        //       sets for its own cluster -- and the development profile sets it high precisely because
        //       that cluster is provisioned with a minimum capacity of zero, so it pauses and its
        //       first connection afterwards absorbs a resume of roughly fifteen seconds. A short
        //       driver bound would abort the socket in the middle of that resume and defeat the wait
        //       the development profile deliberately widened, so the driver bound has to be at least
        //       as long as the longest resume any profile faces. Alternatives Considered: defaulting
        //       it to the shortest acquisition timeout in use, on the reasoning that a tight bound
        //       fails faster. Rejected because it fails faster in the wrong environment: production
        //       already fails fast through its own short acquisition timeout, which fires first and
        //       bounds the caller regardless of this value, so tightening this one would change
        //       nothing in production while breaking every first connection in development. What this
        //       value is for is narrower than the acquisition timeout -- it is the backstop that stops
        //       a socket attempt waiting forever when nothing answers at all.
        pool.addDataSourceProperty(
                CONNECT_TIMEOUT_PROPERTY,
                wholeSecondsCeiling(connectTimeoutMillis, CONNECT_TIMEOUT_MS_KEY));
        pool.addDataSourceProperty(
                READ_TIMEOUT_PROPERTY, wholeSecondsCeiling(readTimeoutMillis, READ_TIMEOUT_MS_KEY));
        pool.addDataSourceProperty(
                SESSION_OPTIONS_PROPERTY, sessionOptions(statementTimeoutMillis, lockTimeoutMillis));
        return pool;
    }

    /**
     * Supplies the startup callback that proves a pooled connection resolves inside the owned schema
     * and opens its session with both server-side bounds in force.
     *
     * @param dataSource the {@link HikariDataSource} built above, from which exactly one connection
     *     is borrowed to read the session posture; must not be {@code null}
     * @param migrationSchema the {@link String} schema name bound from
     *     {@code spring.flyway.default-schema}, the name the migration engine places its history
     *     table in and the only configured schema name this context declares; rejected when blank
     * @return a {@link SmartInitializingSingleton} whose callback yields the verified schema name and
     *     otherwise aborts context initialization, never {@code null}
     */
    @Bean
    public SmartInitializingSingleton authorizationSessionPostureVerifier(
            HikariDataSource dataSource,
            @Value("${" + MIGRATION_SCHEMA_KEY + "}") String migrationSchema) {

        // WHY : Assumptions: a SmartInitializingSingleton callback runs after the context's ordinary
        //       singletons have been created, so the migration engine has already applied
        //       db/migration/V1__authorization.sql and the four tables exist by the time the session
        //       is inspected. That ordering is what lets one check cover both the pin and the
        //       migration target rather than needing two.
        //
        // WHY : Assumptions: the schema compared here exists because a bootstrap outside this module
        //       created it, and this class deliberately cannot stand in for that bootstrap.
        //       data-migration/sql/V0__schemas_and_roles.sql is the exclusive authority for the eight
        //       schemas, the per-service roles and the narrowly-scoped grants -- it creates this one
        //       at its L738, quoting the name because the keyword form of CREATE SCHEMA would
        //       otherwise consume it and report a syntax error pointing nowhere near the cause. That
        //       is also why spring.flyway.create-schemas is false: a database missing the bootstrap
        //       must abort here naming the absent schema, rather than gaining a second,
        //       differently-owned schema carrying none of the privileges the bootstrap grants.
        //
        // WHY : Trade-offs: one pooled connection is borrowed while the context is still starting,
        //       which spends a connection acquisition before any request is accepted and, on a
        //       profile whose database capacity may be paused, pays the resume as well. Accepted
        //       because the alternative differs in kind rather than in degree. Without this check a
        //       schema disagreement or an unbounded session is discovered by whichever request first
        //       issues an unqualified statement or first stalls, and the failure is then attributed
        //       to that request instead of to configuration. Aborting startup stops the task before
        //       the load balancer can register it.
        //
        // WHY : Alternatives Considered: retrying the probe, so that a paused cluster did not abort a
        //       start. Rejected, and no retry appears anywhere in this class: the pool's own
        //       acquisition wait already absorbs a resume, and each profile sizes that wait for its
        //       own cluster, so a retry here would multiply a bound that already exists rather than
        //       add one. No resilience library is declared by this module or used by it either; that
        //       decision is recorded in docs/adr/ADR-002-compute-platform.md, and the durable retry
        //       tier this context does rely on is queue redelivery with a dead-letter queue, argued
        //       at its own site in SqsConfig. The baseline is the precedent for keeping such a
        //       surface narrow rather than the counter-example: its own retry predicate admitted only
        //       three transient infrastructure statuses -- 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'
        //       at app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl L87 and at cbl/COPAUS1C.cbl L88,
        //       one line apart because the whole status block is offset by one between the two files
        //       -- while the logical outcomes declared in the same block, segment-not-found at L80,
        //       duplicate at L81, wrong-parentage at L82 and end-of-database at L83 in the first of
        //       those files, were left to be handled rather than repeated.
        return () -> verifySessionPosture(dataSource, migrationSchema);
    }

    /**
     * Accepts three bounds that ascend from the innermost wait to the outermost, and rejects any
     * ordering in which an inner bound could be reached only after an outer one had already fired.
     *
     * @param lockTimeoutMillis the {@code int} bound in milliseconds on waiting for a row lock, the
     *     innermost of the three; must be positive and strictly the smallest
     * @param statementTimeoutMillis the {@code int} bound in milliseconds on one server-side
     *     statement; must be positive and lie strictly between the other two
     * @param readTimeoutMillis the {@code int} bound in milliseconds on the client waiting for a
     *     response, the outermost of the three; must be positive and strictly the largest
     * @throws IllegalStateException if any of the three is zero or negative, or if the three do not
     *     ascend strictly
     */
    private static void requireAscendingTimeoutBounds(
            int lockTimeoutMillis, int statementTimeoutMillis, int readTimeoutMillis) {

        requirePositiveMillis(lockTimeoutMillis, LOCK_TIMEOUT_MS_KEY);
        requirePositiveMillis(statementTimeoutMillis, STATEMENT_TIMEOUT_MS_KEY);
        requirePositiveMillis(readTimeoutMillis, READ_TIMEOUT_MS_KEY);

        // WHY : Assumptions: the lock bound has to be the smaller of the two server-side bounds, and
        //       the consequence of inverting them is a loss of information rather than a loss of
        //       safety. This context takes a pessimistic row lock on a summary while it decides and
        //       the outbox drain holds locks on the rows it has claimed, so contention here is an
        //       ordinary operating condition rather than an exceptional one. With the lock bound the
        //       larger, a row that is simply contended is cancelled by the statement bound instead,
        //       and the error an operator reads says a statement took too long rather than that two
        //       workers wanted the same authorization -- which are different problems with different
        //       remedies.
        if (lockTimeoutMillis >= statementTimeoutMillis) {
            throw new IllegalStateException(LOCK_TIMEOUT_MS_KEY + " (" + lockTimeoutMillis
                    + ") must be smaller than " + STATEMENT_TIMEOUT_MS_KEY + " ("
                    + statementTimeoutMillis + ") so a contended row reports as a lock wait rather"
                    + " than as a slow statement");
        }

        // WHY : Assumptions: the client's response bound has to outlast the server's statement bound,
        //       and inverting these two is the more damaging of the two orderings. If the socket is
        //       torn down first, the client gives up while the server is still executing: the query
        //       runs on, holding whatever locks it has taken, with nobody left waiting for its
        //       result. Letting the server cancel first means the connection survives to carry the
        //       cancellation back, so the pool reclaims a usable connection and the caller receives
        //       an error that says which statement was cancelled.
        if (statementTimeoutMillis >= readTimeoutMillis) {
            throw new IllegalStateException(STATEMENT_TIMEOUT_MS_KEY + " (" + statementTimeoutMillis
                    + ") must be smaller than " + READ_TIMEOUT_MS_KEY + " (" + readTimeoutMillis
                    + ") so the server cancels a long statement before the client abandons the"
                    + " socket and leaves it running");
        }
    }

    /**
     * Accepts a positive bound in milliseconds and rejects one that would disable the bound entirely.
     *
     * @param millis the {@code int} value bound from configuration, which a profile may set to zero
     *     or to a negative number
     * @param propertyKey the {@link String} configuration key the value came from, carried so that a
     *     failure names the key an operator has to change rather than the parameter it landed in
     * @return the accepted value, always positive
     * @throws IllegalStateException if the value is zero or negative
     */
    private static int requirePositiveMillis(int millis, String propertyKey) {

        // WHY : Assumptions: zero is rejected rather than treated as "unset", because zero is not a
        //       neutral value in either of the two systems that consume these numbers -- the driver
        //       reads a zero connect or socket timeout as "wait forever", and the server reads a zero
        //       statement or lock timeout as "no limit" and reports it back as such. Accepting zero
        //       would therefore silently reproduce the unbounded posture this class exists to
        //       replace, and it would do so while every property still appeared to be configured. A
        //       negative value is rejected in the same breath because it reaches the driver as text
        //       and would be refused there instead, at connection time, far from the profile that
        //       set it.
        if (millis <= 0) {
            throw new IllegalStateException(propertyKey + " must be a positive number of"
                    + " milliseconds but was " + millis + "; zero and negative values leave the"
                    + " interaction unbounded");
        }
        return millis;
    }

    /**
     * Converts a bound expressed in milliseconds into the whole seconds the driver expects, rounding
     * up so that a configured bound is never quietly tightened.
     *
     * @param millis the {@code int} bound in milliseconds as configured; must be positive
     * @param propertyKey the {@link String} configuration key the value came from, carried so that a
     *     rejection names the key rather than the parameter
     * @return the equivalent whole seconds as a {@link String}, never {@code null} and never
     *     {@code "0"}
     * @throws IllegalStateException if the value is zero or negative
     */
    private static String wholeSecondsCeiling(int millis, String propertyKey) {
        int accepted = requirePositiveMillis(millis, propertyKey);

        // WHY : Assumptions: the driver expresses these two bounds in WHOLE SECONDS while every other
        //       timing value in this module -- the pool's own acquisition timeout, both server-side
        //       bounds, the messaging intervals -- is in milliseconds. Rather than exposing one pair
        //       of keys in a different unit from all the others, the conversion is done here, at the
        //       single boundary where it belongs.
        //
        // WHY : Trade-offs: rounding is UP rather than to nearest or down, and the direction matters
        //       because the alternative fails in the dangerous direction. Truncating 1500 ms to one
        //       second would enforce a bound TIGHTER than the one configured, so a profile that
        //       widened a timeout could find the widening partly discarded; and truncating anything
        //       under 1000 ms would produce zero, which the driver reads as "wait forever" and which
        //       would turn a very short bound into no bound at all. Rounding up costs at most a
        //       fraction of a second of extra tolerance and cannot produce either surprise.
        int seconds = (accepted + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND;
        return Integer.toString(seconds);
    }

    /**
     * Composes the server parameters applied to every new session, carrying both server-side bounds.
     *
     * @param statementTimeoutMillis the {@code int} bound in milliseconds the server applies to a
     *     single statement; must be positive
     * @param lockTimeoutMillis the {@code int} bound in milliseconds the server applies to waiting
     *     for a row lock; must be positive
     * @return the driver {@code options} value carrying both settings, never {@code null}
     * @throws IllegalStateException if either value is zero or negative
     */
    private static String sessionOptions(int statementTimeoutMillis, int lockTimeoutMillis) {

        // WHY : Alternatives Considered: issuing SET statement_timeout and SET lock_timeout from the
        //       connection-initialization statement instead, which is the other place a per-session
        //       setting can be applied. Rejected because that property already carries the search-path
        //       pin and is owned by application.yml, so appending to it would put two unrelated
        //       decisions in one string that two different channels both needed to edit. Passing them
        //       as server parameters keeps each concern where its owner can change it alone.
        //
        // WHY : Assumptions: both settings are expressed in milliseconds WITHOUT a unit suffix, which
        //       the server accepts because milliseconds is the declared unit of both parameters. No
        //       conversion is applied here, unlike the two driver bounds above, and that asymmetry is
        //       deliberate rather than an oversight: converting would introduce a rounding step where
        //       none is needed.
        return "-c statement_timeout=" + requirePositiveMillis(
                        statementTimeoutMillis, STATEMENT_TIMEOUT_MS_KEY)
                + " -c lock_timeout=" + requirePositiveMillis(lockTimeoutMillis, LOCK_TIMEOUT_MS_KEY);
    }

    /**
     * Verifies on one borrowed connection that the session resolves the owned schema and carries both
     * server-side bounds.
     *
     * @param dataSource the {@link DataSource} to borrow exactly one connection from; must not be
     *     {@code null}
     * @param migrationSchema the {@link String} schema name the migration engine was configured with,
     *     which is the name a connection is expected to resolve; rejected when blank
     * @return the verified effective schema name, equal to the configured name, never {@code null}
     * @throws IllegalStateException if the configured name is blank, if no row is returned, if the
     *     connection resolves no schema or a different one, if either session bound is disabled, or
     *     if the query cannot be executed
     */
    private static String verifySessionPosture(DataSource dataSource, String migrationSchema) {
        String expectedSchema = requireSchemaName(migrationSchema, MIGRATION_SCHEMA_KEY);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(SESSION_POSTURE_QUERY)) {

            if (!resultSet.next()) {
                throw new IllegalStateException(
                        "The database returned no row while reading the session posture");
            }
            String effectiveSchema = resultSet.getString(1);

            // WHY : Assumptions: an absent value here is the specific symptom of a search path on
            //       which every entry names a schema that does not exist, which is what a database
            //       missing its bootstrap looks like from the connection's side. It is separated from
            //       the mismatch case below because the remedy differs: the schema has to be created
            //       by the bootstrap, not renamed in configuration.
            if (effectiveSchema == null) {
                throw new IllegalStateException("A pooled connection resolved no schema at all; the"
                        + " search path set by spring.datasource.hikari.connection-init-sql names no"
                        + " schema that exists in this database");
            }
            if (!expectedSchema.equals(effectiveSchema)) {
                throw new IllegalStateException("Configured schema '" + expectedSchema + "' from "
                        + MIGRATION_SCHEMA_KEY + " does not match the schema '" + effectiveSchema
                        + "' a pooled connection resolves; check"
                        + " spring.datasource.hikari.connection-init-sql");
            }
            requireBoundedSessionTimeout(
                    "statement_timeout", resultSet.getString(2), STATEMENT_TIMEOUT_MS_KEY);
            requireBoundedSessionTimeout("lock_timeout", resultSet.getString(3), LOCK_TIMEOUT_MS_KEY);
            return effectiveSchema;
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read the session posture from a pooled connection", failure);
        }
    }

    /**
     * Accepts a session timeout the server reports as bounded, and rejects one it reports as
     * disabled.
     *
     * @param settingName the {@link String} server parameter name, carried so that a failure says
     *     which of the two bounds was not in force
     * @param reportedValue the {@link String} value the server reports for that parameter, which may
     *     arrive {@code null} if the parameter is unknown to it
     * @param propertyKey the {@link String} configuration key that was supposed to set the bound,
     *     carried so that a failure names the key rather than the server parameter alone
     * @throws IllegalStateException if the reported value is absent or is the server's disabled
     *     sentinel
     */
    private static void requireBoundedSessionTimeout(
            String settingName, String reportedValue, String propertyKey) {

        // WHY : Assumptions: this is the check that distinguishes "the property was set" from "the
        //       bound is in force", and only the second is worth anything. The two bounds travel to
        //       the server as driver connection parameters, so any link in that chain breaking -- a
        //       key overridden under data-source-properties, a pool built by something other than
        //       this class, a driver that ignored the parameter -- leaves the configuration looking
        //       correct while the session runs unbounded. Reading the value back off a live session
        //       is the only way that gap becomes visible, and it becomes visible at startup rather
        //       than during the first statement that hangs.
        if (reportedValue == null || TIMEOUT_DISABLED.equals(reportedValue.trim())) {
            throw new IllegalStateException("The session reports " + settingName + " as '"
                    + reportedValue + "', which leaves it unbounded; " + propertyKey
                    + " was expected to bound it through the driver's options parameter");
        }
    }

    /**
     * Accepts a configured schema name and rejects an absent or blank one.
     *
     * @param schemaName the {@link String} value bound from configuration, which may arrive
     *     {@code null} or blank when the key is absent or overridden with an empty value
     * @param propertyKey the {@link String} configuration key the value came from, carried so that a
     *     failure names the key an operator has to change rather than the field it landed in
     * @return the trimmed schema name, never blank
     * @throws IllegalStateException if the value is {@code null} or holds only whitespace
     */
    private static String requireSchemaName(String schemaName, String propertyKey) {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalStateException(
                    propertyKey + " must name the schema this service owns");
        }

        // WHY : Assumptions: the value is trimmed because a YAML scalar can carry surrounding
        //       whitespace that survives binding, and an untrimmed name would then fail the
        //       comparison above against a name the database reports without it. That failure is the
        //       worst kind to debug, because its message shows two names that look identical.
        return schemaName.trim();
    }
}
