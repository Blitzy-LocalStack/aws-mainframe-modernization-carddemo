package com.carddemo.reference.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Builds the HikariCP data source of the reference-data context and proves its schema pin took effect.
 *
 * <p>This type owns exactly two things: the configuration-bound pool, and a startup check that the
 * connections that pool hands out resolve an unqualified table name in the {@code reference} schema.
 * It owns no API representation, no shared error handling, no identity decision and no queue client;
 * those belong to {@code OpenApiConfig}, {@code com.carddemo.common.error.GlobalExceptionHandler},
 * {@code SecurityConfig} and {@code SqsConfig} respectively, and the package charter beside this file
 * records that division.</p>
 *
 * <p>Assumptions: {@code spring.jpa.hibernate.ddl-auto} stays {@code none} while this type binds
 * connection behaviour. Flyway alone applies table DDL for this schema, from
 * {@code classpath:db/migration}, and the deployment bootstrap
 * {@code data-migration/sql/V0__schemas_and_roles.sql} creates the schema, the login role and the
 * grants beforehand. Two independent schema managers could otherwise reshape one table contract after
 * the other deployable had bound to it.</p>
 *
 * <p>Assumptions: this type reports failure by throwing rather than by setting a status character,
 * and that is a deliberate refusal of the baseline's convention because two character flag families
 * with OPPOSITE polarity both describe this bounded context. In
 * {@code app/app-transaction-type-db2/cpy/CSDB2RWY.cpy} lines 25 to 27,
 * {@code WS-DB2-PROCESSING-FLAG} is declared with {@code 88 WS-DB2-OK VALUE '0'} and
 * {@code 88 WS-DB2-ERROR VALUE '1'}, so there the character {@code '0'} means healthy. The tri-state
 * {@code FLG-} validation flags this same module carries read {@code '0'} as the not-OK state
 * instead. One character means the opposite thing in the two families, so any code that carried a
 * status character between them would invert every branch that tests it while still compiling and
 * still returning a value. An exception has no polarity available to invert, which is the property
 * being bought here.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // WHY : Refactoring Rationale: this type was withdrawn from the package once, on the argument
    //       that the schema pin and the pool sizing are both declared in application.yml and that a
    //       configuration type could only restate them. It is restored because the check below does
    //       not restate a setting: it asks the SERVER what the session actually resolved, through
    //       SELECT current_schema(), and compares that answer with a schema name declared on a
    //       DIFFERENT configuration path -- spring.flyway.default-schema rather than
    //       spring.datasource.hikari.connection-init-sql. A statement asked for and a statement
    //       having taken effect are different facts, and only the second one is asserted here.
    // WHY : Alternatives Considered: leaving that effect-check to an integration test against a real
    //       engine, which is what the withdrawal proposed. Rejected because a test proves the pin on
    //       the engine the test starts, while this callback proves it on the engine the deployment is
    //       actually pointed at -- including a production cluster whose search_path could have been
    //       altered at the role or database level after the image was built and tested.
    // WHY : Refactoring Rationale: three baseline attachment mechanisms are RETIRED here rather than
    //       modelled, and naming them is what stops a reader looking for their equivalents. The
    //       baseline bound its programs to Db2 through PLAN(CARDDEMO), declared at
    //       app/app-transaction-type-db2/csd/CRDDEMOD.csd L47 and serving both the online
    //       transactions and the sequential reference update, with the bound package searched
    //       through a DBRMLIB library and the load modules resolved through STEPLIB. A plan is a
    //       pre-bound, pre-authorised access path compiled ahead of execution; this context instead
    //       sends SQL a driver prepares against a login role whose privileges the bootstrap DDL
    //       grants. There is consequently no plan to name, no package to bind and no library to
    //       search, so no property below corresponds to any of the three.

    /**
     * The query that reports the first existing schema on the connection's effective search path.
     */
    private static final String EFFECTIVE_SCHEMA_QUERY = "SELECT current_schema()";

    /**
     * The driver property naming the transport-security mode a connection must negotiate.
     *
     * <p>Assumptions: this is the PostgreSQL driver's own property name, not a Spring one, which is why it
     * is written without separators. It is declared here so that the pooled data source and the migration
     * data source below cannot come to spell it differently.</p>
     */
    private static final String SSL_MODE_PROPERTY = "sslmode";

    /**
     * The driver property naming the certificate bundle the server's chain is validated against.
     */
    private static final String SSL_ROOT_CERT_PROPERTY = "sslrootcert";

    /**
     * Builds the HikariCP data source from the core datasource properties and the pool properties.
     *
     * @param properties the {@link DataSourceProperties} carrying the JDBC driver class, URL,
     *     username and password read from external configuration; must not be {@code null}
     * @return the {@link HikariDataSource} whose sizing, timeouts and connection initialization are
     *     bound from {@code spring.datasource.hikari}, never {@code null}
     * @throws org.springframework.beans.factory.BeanCreationException if the configured JDBC driver
     *     class or URL cannot be resolved while the bean is created
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // WHY : Refactoring Rationale: the baseline serialised this context's database work rather
        //       than pooling it. app/app-transaction-type-db2/csd/CRDDEMOD.csd defines one
        //       DB2ENTRY(CARDDEMO) at L45 with THREADLIMIT(1) at L48, and attaches BOTH maintenance
        //       transactions to it through the DB2TRAN definitions at L51-L60, so CTLI and CTTU
        //       shared a single thread. THREADWAIT(YES) on that same L48 completes the posture: a
        //       task arriving while the one thread was busy QUEUED for it rather than being
        //       refused, so the effective Db2 concurrency of this entire context was one, and the
        //       queue reached the caller as elapsed time and nothing else. Binding a pool here is a
        //       MAPPING of that posture onto a driver that has no single-thread equivalent. The
        //       baseline behaved exactly as its own resource definition specified.
        // WHY : Alternatives Considered: reproducing that posture literally, with a ceiling and an
        //       idle floor of one, which is the only sizing that preserves the serialisation
        //       THREADLIMIT(1) imposed. Rejected on a concrete consequence rather than on taste.
        //       Every read this context serves is a lookup of seeded rows no caller mutates --
        //       transaction types, their categories, disclosure rates, and the address tables the
        //       other services validate against -- so a pool of one would make concurrent readers
        //       of immutable rows wait behind one another for no gain of any kind, and the address
        //       validation performed in other services would serialise across the whole deployment
        //       on this single connection.
        // WHY : Alternatives Considered: sizing the pool generously instead, on the argument that
        //       connections are cheap. Rejected because the target cluster is serverless and scales
        //       its own capacity, which makes an idle connection two separate costs rather than
        //       none: it holds server state that keeps capacity awake and billed in Aurora Capacity
        //       Units, and it counts against the server's maximum connection limit, which the sum
        //       of every service's ceiling has to stay beneath. An oversized ceiling here surfaces
        //       as a refused connection in some unrelated service, which is a failure diagnosed
        //       nowhere near the setting that caused it.
        // WHY : Trade-offs: the numbers settling those two rejections are declared in
        //       configuration, not here, and they differ per environment, so this factory states
        //       none of them itself. The base profile carries a ceiling of 10 with an idle floor of
        //       2; the dev overlay lowers those to 4 and 0, and the prod overlay raises the floor to
        //       5 while inheriting the ceiling. Each overlay argues its own values at the key it
        //       sets -- dev that an idle floor would DEFEAT the auto-pause its scale-to-zero cluster
        //       requires, because a held connection stops the cluster reaching its threshold, and
        //       prod that its minimum capacity is held above zero precisely so that no request ever
        //       waits on a resume. Those arguments belong to the files that own the values and are
        //       referenced here rather than restated. The price of the split is that no single file
        //       shows the effective sizing; the return is that one image serves every environment.
        // WHY : Assumptions: pooled concurrent sessions make lock conflict reachable, and its CAUSE
        //       is recorded in the baseline rather than inferred here. DROLLBACK(YES) at
        //       app/app-transaction-type-db2/csd/CRDDEMOD.csd L47, in a stanza titled
        //       DESCRIPTION(DB2 RETRY FOR CARDDEMO PLAN) at L46, directed CICS to back the unit of
        //       work out when Db2 reported a deadlock or a lock timeout. That setting is why both
        //       programs carry an SQLCODE -911 branch at all: COTRTLIC.cbl L1870 tests for it and
        //       L1874 answers 'Deadlock. Someone else updating ?', while COTRTUPC.cbl L1561 tests
        //       for it and L1564 sets the condition its L181 declares as 'Could not lock record for
        //       update'. This context reaches the same conclusion from the driver's SQLSTATE, and
        //       the status that becomes is decided in exactly one place --
        //       com.carddemo.common.error.GlobalExceptionHandler, which answers HTTP 409 both for a
        //       lock that could not be taken and for the foreign-key restriction this schema relies
        //       on. This type therefore declares no handler of its own: a second one would give one
        //       failure two competing answers, selected by whichever matched.
        // WHY : Alternatives Considered: hard-coding the pool figures or the schema in this factory.
        //       Rejected because either makes an environment-specific value part of compiled code,
        //       so a capacity change would need a rebuild and the two environments could only differ
        //       by shipping two images.
        // WHY : Alternatives Considered: pinning the schema with a currentSchema JDBC URL parameter,
        //       or with Hibernate's default_schema property, instead of the connection-init-sql this
        //       pool binds. The URL is environment-owned, so an operator edit could remove the pin
        //       with nothing in the repository changing; the Hibernate property reaches neither
        //       Flyway nor native JDBC, and this context has both -- Flyway resolves its history
        //       table against the session path, and TransactionTypeRepository L666 issues a native
        //       query. Hikari applies its initialization statement to every physical connection it
        //       opens, which is the only one of the three that covers all of them.
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Builds the data source Flyway migrates through, carrying the same verified TLS terms as the pool.
     *
     * <p>Purpose: schema-owner migrations must reach the cluster over a connection whose certificate chain
     * and hostname are verified, exactly as runtime queries do. This bean is what makes that true, and it
     * exists because nothing else does it.</p>
     *
     * <p>Refactoring Rationale: this bean did not exist, and its absence silently weakened the transport of
     * every DDL statement this module applies. {@code spring.flyway.user} is set, which makes Spring Boot
     * build Flyway a separate migration data source of its own -- verified against
     * {@code FlywayAutoConfiguration.getMigrationDataSource} in {@code spring-boot-flyway} 4.1.0, whose
     * {@code applyConnectionDetails} copies the URL, the driver class name, the user and the password and
     * NOTHING else. The {@code sslmode: verify-full} and {@code sslrootcert} terms are bound under
     * {@code spring.datasource.hikari.data-source-properties}, which is a HikariCP-specific map, so they were
     * not among the values copied. The configuration comment beside those keys asserted that "the URL, driver
     * and TLS terms are inherited"; the first two were and the third was not, so migrations negotiated
     * whatever the driver's default mode is -- {@code prefer} for PostgreSQL, which will silently accept an
     * unencrypted session and validates no certificate at all. The runtime path was never affected, which is
     * precisely why the gap was invisible: a deployment could verify every query and verify none of its DDL.</p>
     *
     * <p>Alternatives Considered: appending the TLS parameters to the shared JDBC URL instead, which would fix
     * every consumer of that URL at once. Rejected for the same reason recorded above against pinning the
     * schema through the URL: the URL is environment-owned, arriving as {@code SPRING_DATASOURCE_URL} from the
     * deployment, so an operator edit could drop the terms with nothing in this repository changing -- and a
     * dropped {@code verify-full} is not a visible failure, it is a quietly downgraded connection.</p>
     *
     * <p>Alternatives Considered: setting {@code spring.flyway.url} together with
     * {@code spring.flyway.jdbc-properties}, which reads like the purpose-built mechanism. Rejected because it
     * is not: Boot hands Flyway a {@code DataSource} in every branch of {@code getMigrationDataSource}, and
     * Flyway applies {@code jdbcProperties} only when it constructs its own connection from a URL, so the
     * properties would have been accepted, carried and ignored -- the same class of silent no-op this bean is
     * correcting.</p>
     *
     * <p>Assumptions: this is deliberately NOT a pooled data source. Migrations run once during context
     * refresh and then never again, so a pool would hold connections open for the life of the task under the
     * migration credential -- a credential that, unlike the runtime one, holds DDL authority over the tables
     * it created. A driver-backed source opens a connection when Flyway asks and closes it when Flyway is
     * done, which is the whole of the lifetime the work needs.</p>
     *
     * <p>Assumptions: the bean is declared with {@code defaultCandidate = false} and is found through the
     * {@link FlywayDataSource} qualifier alone. Without that, this context would hold two beans of type
     * {@code DataSource}: the JPA autoconfiguration resolves one by type and would fail to choose, and the
     * actuator's datasource health contributor -- which collects every {@code DataSource} bean -- would open a
     * validation connection under the migration credential on every health poll. Excluding it from default
     * candidacy states that this data source has exactly one consumer, which is the fact.</p>
     *
     * <p>Assumptions: the bean is conditional on {@code spring.flyway.user}, which is the exact condition that
     * makes Boot build a migration data source of its own. Where no separate migration credential is
     * configured -- the integration-test profile, which drives an ephemeral container under one credential --
     * Boot hands Flyway the application data source itself, so the pooled TLS terms already apply and a second
     * source would only be a second thing to keep in agreement. Declaring the condition rather than always
     * publishing the bean is also what keeps this type usable in a profile that sets no migration credential
     * at all, where the mandatory placeholders below could not resolve.</p>
     *
     * @param properties the {@link DataSourceProperties} carrying the JDBC URL and driver class the runtime
     *     pool also uses, so migrations and queries cannot address different clusters; must not be
     *     {@code null}
     * @param migrationUser the migration login read from {@code spring.flyway.user}, distinct from the runtime
     *     login so that DDL authority is not held by the credential every request runs under; must not be
     *     blank
     * @param migrationPassword the credential for that login, read from {@code spring.flyway.password}; must
     *     not be blank
     * @param sslMode the transport-security mode read from {@code carddemo.database.ssl.mode}, the same key
     *     the pool's driver properties are bound from; must not be blank
     * @param sslRootCert the certificate-bundle path read from {@code carddemo.database.ssl.root-cert}, the
     *     same key the pool's driver properties are bound from; must not be blank
     * @return a {@link SimpleDriverDataSource} addressing the configured cluster as the migration login with
     *     verified transport security, never {@code null}
     * @throws IllegalStateException if the configured driver class cannot be loaded, raised by the builder,
     *     which is a packaging fault rather than a configuration one
     */
    @Bean(defaultCandidate = false)
    @FlywayDataSource
    @ConditionalOnProperty(name = "spring.flyway.user")
    public SimpleDriverDataSource flywayDataSource(DataSourceProperties properties,
            @Value("${spring.flyway.user}") String migrationUser,
            @Value("${spring.flyway.password}") String migrationPassword,
            @Value("${carddemo.database.ssl.mode}") String sslMode,
            @Value("${carddemo.database.ssl.root-cert}") String sslRootCert) {

        SimpleDriverDataSource migrationDataSource = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .url(properties.determineUrl())
                .driverClassName(properties.determineDriverClassName())
                .username(migrationUser)
                .password(migrationPassword)
                .build();

        // WHY : Assumptions: the properties are set as DRIVER connection properties rather than appended to
        //   the URL, so a URL that already carries a query string cannot be corrupted by concatenation and a
        //   URL that carries a conflicting ssl term is overridden rather than silently duplicated. This is
        //   the same mechanism the pool uses through spring.datasource.hikari.data-source-properties, applied
        //   to the one connection Flyway opens.
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty(SSL_MODE_PROPERTY, sslMode);
        connectionProperties.setProperty(SSL_ROOT_CERT_PROPERTY, sslRootCert);
        migrationDataSource.setConnectionProperties(connectionProperties);

        return migrationDataSource;
    }

    /**
     * Supplies the startup callback that verifies the effective schema once singletons are created.
     *
     * @param dataSource the {@link HikariDataSource} whose pooled connection is inspected; must not
     *     be {@code null}
     * @param expectedSchema the {@link String} schema name read from
     *     {@code spring.flyway.default-schema}; must not be blank
     * @return a {@link SmartInitializingSingleton} callback that aborts context initialization when
     *     the effective schema is not the expected one, never {@code null}
     */
    @Bean
    public SmartInitializingSingleton referenceSchemaPinVerifier(
            HikariDataSource dataSource,
            @Value("${spring.flyway.default-schema}") String expectedSchema) {

        // WHY : Trade-offs: this spends one pool acquisition during context creation, before any
        //       traffic is accepted. The alternative is to learn about a wrong search path from a
        //       request: PostgreSQL accepts a search_path whose first entry names a schema that does
        //       not exist, current_schema() then reports none, and an unqualified statement either
        //       fails or resolves in some other schema at the moment a caller reaches it.
        // WHY : Assumptions: the consequence is worse for THIS context than for most, which is why
        //       the acquisition is worth paying for here. Every other context reads these seeded
        //       lookup rows to validate an address, so a wrong path surfaces as a validation refusal
        //       inside a different service, several hops from the misconfiguration that caused it.
        // WHY : Assumptions: SmartInitializingSingleton runs after regular singleton initialization,
        //       so Flyway has already migrated by the time this inspects a pooled connection.
        //       Comparing the server's answer against Flyway's SEPARATELY declared default schema is
        //       what makes the check falsifiable -- deriving one of the two values from the other
        //       would leave the two configuration paths unable to disagree.
        return () -> verifyEffectiveSchema(dataSource, expectedSchema);
    }

    /**
     * Resolves the effective schema of one pooled connection and verifies it against configuration.
     *
     * @param dataSource the {@link DataSource} supplying the connection to inspect; must not be
     *     {@code null}
     * @param expectedSchema the {@link String} schema name supplied by configuration; must not be
     *     blank
     * @return the verified effective schema name, never {@code null}
     * @throws IllegalStateException if the expected schema is blank, the query cannot complete or
     *     returns no row, or the connection resolves a different schema
     */
    private static String verifyEffectiveSchema(DataSource dataSource, String expectedSchema) {
        if (expectedSchema == null || expectedSchema.isBlank()) {
            throw new IllegalStateException(
                    "spring.flyway.default-schema must name the schema to verify");
        }

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(EFFECTIVE_SCHEMA_QUERY)) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "The database returned no row while resolving the effective schema");
            }

            String actualSchema = result.getString(1);
            if (!expectedSchema.equals(actualSchema)) {
                // WHY : Assumptions: both names are quoted into the message because the failure a
                //       reader most often meets here is an EMPTY effective schema, which
                //       current_schema() reports when the pinned schema is absent from the
                //       database. Naming only the expected value would leave that case reading as
                //       though no comparison had been made.
                throw new IllegalStateException("Configured database schema '" + expectedSchema
                        + "' does not match effective schema '" + actualSchema + "'");
            }
            return actualSchema;
        } catch (SQLException failure) {
            // WHY : Refactoring Rationale: this failure travels on TWO channels -- a stable sentence
            //       naming what could not be done, and the driver's own exception retained as the
            //       cause -- replacing a baseline design that had room for only one.
            //       app/app-transaction-type-db2/cpy/CSDB2RPY.cpy composes the current action, the
            //       displayable SQLCODE and the vendor-formatted DSNTIAC text into an 800-byte
            //       WS-LONG-MSG across L74-L83, and its L84 then moves that into the 75-byte
            //       WS-RETURN-MSG declared at COTRTLIC.cbl L249, so every byte past 75 was
            //       discarded at the moment it was most needed. The vendor text was already
            //       partial before that: SQLERRM OF SQLCA sits commented out of the composition at
            //       L79. Carrying the cause as a separate object keeps the sentence short enough to
            //       read while discarding nothing.
            // WHY : Assumptions: the two-channel shape also NORMALISES an asymmetry, which is why
            //       it is a context-wide contract and not a local preference. COTRTLIC includes
            //       both Db2 copybooks -- CSDB2RWY at its L304 and CSDB2RPY at its L2055, by
            //       EXEC SQL INCLUDE rather than COPY, the precompiler dialect of the same
            //       mechanism -- and so carries both channels; COTRTUPC includes neither, declares
            //       its own WS-DISP-SQLCODE at L68 and its own 75-byte WS-RETURN-MSG at L167, and
            //       has no WS-LONG-MSG whatever. Two programs in one bounded context reported the
            //       same class of failure with different amounts of detail available. The
            //       truncation and the asymmetry are both registered as documented divergences in
            //       docs/architecture/cobol-to-service-traceability.md, which is owned elsewhere;
            //       neither is introduced silently here.
            // WHY : Assumptions: the stable channel carries exactly ONE sentence per failure, and
            //       that rule is inherited rather than invented. WS-RETURN-MSG-OFF, declared at
            //       COTRTLIC.cbl L250 and tested at each compose site -- the deadlock branch's
            //       guard at L1873 among them -- admitted only the earliest error and suppressed
            //       every later one. Throwing on the earliest refusal reproduces that
            //       first-error-wins behaviour with no flag needed to carry it.
            throw new IllegalStateException(
                    "Unable to resolve the effective schema from a pooled connection", failure);
        }
    }
}
