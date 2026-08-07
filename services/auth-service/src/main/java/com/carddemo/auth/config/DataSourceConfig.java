package com.carddemo.auth.config;

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
 * Builds the connection pool for the auth bounded context and proves that every pooled connection
 * resolves an unqualified table name inside the {@code auth} schema and nowhere else.
 *
 * <h2>Purpose</h2>
 *
 * <p>Two responsibilities and no others: pool behaviour bound entirely from external configuration, and
 * verification at start-up that the connection-level schema resolution took effect. This class registers
 * no representation bean, no error advice, no metrics binding, no identity client, no request filter, no
 * migration bean and no transaction manager. Those belong to {@code OpenApiConfig},
 * {@code SecurityConfig} and {@code CognitoIdentityConfig} in this same package, to the shared kernel's
 * auto-configuration, and to the framework's own migration auto-configuration reading the
 * {@code spring.flyway} keys. Keeping the persistence boundary separate is what stops this class
 * becoming the module's general bean registry.</p>
 *
 * <p>Every value this class acts on arrives from external configuration. This module's
 * {@code application.yml} is the property-key contract, and it says so at its own head; no endpoint, no
 * credential, no pool number and no profile name is restated here as a Java literal, so one image serves
 * every environment and each profile pays its own connection cost.</p>
 *
 * <h2>Why the pin is verified rather than merely configured</h2>
 *
 * <p>Assumptions: the pin itself is a configuration value,
 * {@code spring.datasource.hikari.connection-init-sql}, which this module's {@code application.yml}
 * sets to {@code SET search_path TO auth}. The pool applies it to every physical connection it opens, so
 * it covers generated statements, hand-written JDBC and the migration tool alike. What configuration
 * cannot do is prove it took effect, and that is the gap this class closes: the engine accepts a search
 * path naming a schema that does not exist, {@code current_schema()} then resolves to nothing, and an
 * unqualified statement fails -- or worse, resolves in another context's schema -- only when a request
 * first reaches it. Verifying once at start-up turns that into a refusal to start.</p>
 *
 * <p>Assumptions: the verification compares the effective schema against
 * {@code spring.flyway.default-schema} rather than against a literal. Those two keys are set
 * independently -- one governs where migrations are applied, the other where runtime statements resolve
 * -- and comparing them proves the two configuration paths agree. Deriving one from the other, or
 * comparing both against a constant in this file, would make a disagreement between them unobservable,
 * and a disagreement is exactly the condition in which migrations land in one schema while the service
 * reads another.</p>
 *
 * <h2>Why the connection boundary and not each statement</h2>
 *
 * <p>Assumptions: schema isolation is enforced where the pool hands out a connection rather than by
 * qualifying every statement, and the difference is mechanical rather than stylistic. A pin applied when
 * a physical connection is opened is a single enforcement point: it is in force for every statement that
 * connection ever carries, and no author of a later query can omit it, because there is nothing per
 * query to omit. Per-statement qualification is a convention instead of a mechanism, so it has to be
 * re-applied correctly in every repository method anyone adds afterwards, and the first omission does
 * not fail loudly: an unqualified name resolves against whatever the session's path happens to be, which
 * on a cluster holding eight contexts' schemas means a query written for this context can read a
 * different context's table and return rows from it. Choosing the boundary makes that omission
 * impossible to express rather than something review has to catch.</p>
 *
 * <p>Assumptions: this class creates no schema, no role and no privilege, and executes no
 * data-definition statement of any kind. {@code data-migration/sql/V0__schemas_and_roles.sql} is the
 * exclusive owner of all three: it establishes the eight schemas, the three tiers of role behind them
 * and the whole cross-schema privilege graph, and it is where the {@code auth} schema is established
 * under its own owner role. The {@code auth} schema is therefore assumed to PRE-EXIST when this service
 * starts. That is a deployment precondition rather than something this class can arrange, which is also
 * why {@code spring.flyway.create-schemas} is false in this module's base profile; the verification
 * below is what turns a missed precondition into a refusal to start instead of a permission error on a
 * user's first request.</p>
 *
 * <h2>Alternatives measured and rejected</h2>
 *
 * <p>Alternatives Considered: a {@code currentSchema} parameter on the connection URL. Rejected because
 * the URL is supplied by the environment through {@code SPRING_DATASOURCE_URL}, so an operator editing
 * it for an unrelated reason could drop the pin without touching anything under review, and nothing
 * would report the loss.</p>
 *
 * <p>Alternatives Considered: the persistence provider's own default-schema property. Rejected as the
 * sole mechanism because it governs only statements that provider generates: hand-written JDBC and the
 * migration tool would still resolve against whatever the session's search path happened to be, so the
 * pin would hold for most of the module's traffic and silently not for the rest. It is set as well as
 * the connection pin, not instead of it, and this module's {@code application.yml} records why the two
 * are independent.</p>
 *
 * <p>Alternatives Considered: qualifying the schema on every entity and every query. Rejected because it
 * makes schema selection part of compiled code, so one image could no longer serve every environment,
 * and because a single omitted qualifier is invisible in review while a connection-level pin cannot be
 * omitted per statement.</p>
 *
 * <h2>What this class replaces</h2>
 *
 * <p>The sole persistent store of the five migrated sign-on and user-administration programs is the
 * indexed file defined at {@code app/csd/CARDDEMO.CSD} L88-L97, whose 80-byte record layout is
 * {@code app/cpy/CSUSR01Y.cpy} L17-L23. That definition is reference-only and is not edited here. Three
 * of its declared access characteristics differ from what this class configures, and each difference is
 * recorded beside the code it bears on rather than left for a reader to reconstruct: read isolation and
 * data-at-rest durability on {@link #dataSource(DataSourceProperties)}, and the concurrency ceiling
 * inside that same factory.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // WHY : Assumptions: current_schema() is queried rather than the raw search_path setting, because
    //       the raw setting reports what was ASKED FOR and this function reports what RESOLVED. A path
    //       naming an absent schema differs between the two, and that difference is the whole condition
    //       worth catching -- reading back the setting would agree with itself and prove nothing.
    // WHY : Assumptions: the statement is a plain read with no parameter and no vendor extension, so it
    //       carries no driver-specific type into this context. That matters because this module declares
    //       its database driver at runtime scope precisely so a direct reference to a vendor type cannot
    //       compile, and a pin expressed through a vendor data-source class would have defeated it.
    private static final String EFFECTIVE_SCHEMA_QUERY = "SELECT current_schema()";

    /**
     * Builds the connection pool from the core datasource properties and the pool properties.
     *
     * <p>Assumptions: pool sizing, the connection-initialisation statement, the borrow timeout and the
     * transport-security parameters are all BOUND from configuration rather than set here, so one image
     * serves every environment. The development profile is free to let the pool empty, which serverless
     * auto-pause requires because a paused cluster needs every connection closed; the production profile
     * keeps an idle floor against provisioned capacity. Writing either choice into this factory would
     * make the image environment-specific and would put a number that belongs to a deployment into
     * compiled code.</p>
     *
     * <p>Assumptions: {@code spring.jpa.hibernate.ddl-auto} stays {@code none} while this factory binds
     * connection behaviour. The migration tool alone applies table definitions and the deployment
     * bootstrap establishes the schema, its owner role and its privileges, so there is exactly one schema
     * manager for the {@code auth} schema. A second one would let two independently deployed artifacts
     * reshape one table after the other had bound to it.</p>
     *
     * <p>Trade-offs: every statement issued through this pool runs at the engine's default isolation
     * level, read-committed, and that is STRICTER than the {@code READINTEG(UNCOMMITTED)} the baseline
     * file declares at {@code app/csd/CARDDEMO.CSD} L90. The observable difference is precise. The
     * baseline permitted a reading task to return a record image another task had written and not yet
     * committed, so a user-administration list could render a name that a rolled-back update had
     * produced; a statement here only ever sees a row version whose writing transaction committed, so
     * that output is not reachable. The cost is accepted rather than absent, and it falls in two places.
     * A statement that takes a row lock -- an update, or a read issued for update -- waits for a
     * concurrent writer on the same row to commit or roll back instead of proceeding on uncommitted
     * bytes, so contention presents as added latency rather than as inconsistent output. And the engine
     * retains superseded row versions until no snapshot needs them, which is storage the
     * baseline's single in-place record image never spent. The baseline reads uncommitted images; the
     * Java reads only committed ones; the divergence is registered against this exact stanza line as
     * {@code D-REPORTING-ISOLATION} in {@code docs/architecture/cobol-to-service-traceability.md}, which
     * cites L90 among the eight stanzas that all carry the attribute.</p>
     *
     * <p>Trade-offs: the store this pool connects to is encrypted at rest, backed up on a schedule and
     * write-ahead logged, where the file it replaces is declared with none of the three.
     * {@code app/csd/CARDDEMO.CSD} states {@code JOURNAL(NO)} at L94, then {@code JNLREAD(NONE)},
     * {@code JNLSYNCREAD(NO)}, {@code JNLUPDATE(NO)} and {@code JNLADD(NONE)} at L95, then
     * {@code JNLSYNCWRITE(YES)}, {@code RECOVERY(NONE)} and {@code FWDRECOVLOG(NO)} at L96, and
     * {@code BACKUPTYPE(STATIC)} at L97. The accurate description of the baseline is therefore that it
     * kept no forward recovery log and no data-change journalling, and that a damaged file was
     * recoverable only as far back as its last static backup. Stating it with that precision is
     * load-bearing, because {@code JNLSYNCWRITE(YES)} IS set on L96: describing the file as journal-free
     * without that qualification would misstate the definition this migration is measured against. What
     * the target accepts in exchange is cost and commit latency rather than any change of result -- each
     * commit is durable to a log before it is acknowledged and each stored byte is encrypted under a
     * managed key, neither of which the baseline paid for. The baseline configures
     * no forward recovery log and no data-change journalling; the Java runs on a store that logs and
     * backs up by default; the divergence is registered as {@code D-REPORTING-DATA-AT-REST} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, which cites L94 and L96 for this
     * stanza by name.</p>
     *
     * @param properties the {@link DataSourceProperties} carrying the connection URL, the username and
     *     the password this service authenticates requests with, every one of them resolved from
     *     external configuration; must not be {@code null}
     * @return the {@link HikariDataSource} whose sizing, borrow timeout and connection initialisation are
     *     bound from {@code spring.datasource.hikari}, never {@code null}
     * @throws org.springframework.beans.factory.BeanCreationException if the driver cannot be determined
     *     from the configured URL, or the URL itself is absent, either of which leaves the pool with no
     *     endpoint to open a connection against
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // WHY : Refactoring Rationale: the numbers bound onto this pool are a genuinely NEW degree of
        //       freedom rather than a ported tunable, which is why they are configuration values and are
        //       derived from nothing in the baseline. app/csd/CARDDEMO.CSD L91 declares STRINGS(1) on the
        //       USRSEC file, so every request against the user store -- sign-on, list, add, update and
        //       delete alike -- was serialised through exactly ONE access string: concurrency against
        //       that store was capped at one in-flight operation, and there was no larger figure
        //       available to carry across. Removing the ceiling removes no business rule, because the
        //       number one there expressed a buffer allocation and never a requirement about how many
        //       users may be served at once, and no migrated behaviour depends on requests against this
        //       store being serialised.
        // WHY : Assumptions: the ceiling that replaces it is a budget this file cannot see on its own.
        //       What the cluster must absorb is the per-task pool size MULTIPLIED BY the number of
        //       running tasks, summed over every service sharing the cluster, because horizontal scaling
        //       multiplies the pool rather than dividing it -- a service scaled from two tasks to ten
        //       does not share ten connections, it borrows ten times its configured maximum. That is why
        //       the sizing lives in the profiles, alongside where the task count is decided, and why
        //       raising one without the other is what turns a traffic peak into refused connections for
        //       every context on the same cluster.
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Supplies the start-up callback that verifies the effective schema once the singletons exist.
     *
     * <p>Assumptions: this runs as a {@link SmartInitializingSingleton}, which the container invokes
     * AFTER ordinary singleton initialisation, so the migration has already been applied by the time a
     * connection is inspected. Running earlier would inspect a schema state the migration had not yet
     * reached and report a failure that is only a matter of ordering.</p>
     *
     * <p>Trade-offs: one pool acquisition is spent during context creation, before any traffic is
     * accepted. Accepted because the alternative is discovering a mis-resolved search path on a user's
     * first request, where it presents as a missing table rather than as the configuration fault it is.</p>
     *
     * @param dataSource the {@link HikariDataSource} whose initialised connection is inspected, taken as
     *     the pool type rather than the interface so the callback cannot be wired to some other
     *     connection source than the one this class configures; must not be {@code null}
     * @param expectedSchema the {@link String} schema name read from
     *     {@code spring.flyway.default-schema}, the key that governs where this module's migration is
     *     applied; must not be blank
     * @return a {@link SmartInitializingSingleton} that aborts context initialisation when the effective
     *     schema disagrees with the configured one, never {@code null}
     */
    @Bean
    public SmartInitializingSingleton authSchemaPinVerifier(
            HikariDataSource dataSource,
            @Value("${spring.flyway.default-schema}") String expectedSchema) {
        // WHY : Assumptions: what is checked is the connection, not the setting, and that is the whole
        //       point of enforcing the pin at the connection boundary. Because the path is established
        //       once per physical connection and then holds, inspecting ONE borrowed connection is
        //       representative of every statement the pool will ever carry; had the schema been selected
        //       per statement instead, no single observation would have said anything about the
        //       statements written later, and there would be nothing here a start-up check could assert.
        return () -> verifyEffectiveSchema(dataSource, expectedSchema);
    }

    /**
     * Resolves the connection's effective schema and verifies it against configuration.
     *
     * @param dataSource the {@link DataSource} that supplies the connection to inspect, widened to the
     *     interface so the verification can be exercised against a stub with no pool behind it; must not
     *     be {@code null}
     * @param expectedSchema the {@link String} schema name supplied by configuration, against which the
     *     resolved value is compared; must not be blank
     * @return the {@link String} effective schema name the connection reported, equal to
     *     {@code expectedSchema} whenever this method returns at all, never {@code null}
     * @throws IllegalStateException if the expected schema is blank, if the query returns no row, if it
     *     cannot complete, or if the connection resolves a different schema, each of which leaves
     *     unqualified statements able to resolve outside the schema this context owns
     */
    static String verifyEffectiveSchema(DataSource dataSource, String expectedSchema) {
        // WHY : Assumptions: the blank case is refused BEFORE a connection is taken, because it is the
        //       one input that would make the check pass vacuously. An empty expected value compared
        //       against an empty resolved value agrees, so the pin would be unverified while start-up
        //       reported success -- and the message names the property rather than the symptom, because
        //       the fault is always a missing configuration key and never a database state.
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

            // WHY : Assumptions: a null first column is carried into the comparison rather than
            //       dereferenced. current_schema() returns NULL when the search path names only schemas
            //       that do not exist, which is precisely the state a missed deployment precondition
            //       produces -- data-migration/sql/V0__schemas_and_roles.sql not yet applied, so the auth
            //       schema this context assumes is not there -- so it has to reach the comparison and be
            //       reported as a disagreement rather than raise a null pointer that hides the cause.
            String actualSchema = result.getString(1);
            if (!expectedSchema.equals(actualSchema)) {
                throw new IllegalStateException("Configured database schema '" + expectedSchema
                        + "' does not match effective schema '" + actualSchema + "'");
            }
            return actualSchema;
        } catch (SQLException failure) {
            // WHY : Trade-offs: the driver failure is wrapped rather than propagated, accepting the loss
            //       of the checked type at this boundary in exchange for one uniform failure for every
            //       way the pin can be unverifiable. The cause is attached, so nothing diagnostic is
            //       lost, and the caller is a container callback that cannot handle a checked exception
            //       usefully in any case.
            throw new IllegalStateException(
                    "Unable to resolve the effective schema from a pooled connection", failure);
        }
    }
}
