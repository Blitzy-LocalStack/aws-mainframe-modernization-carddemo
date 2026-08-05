package com.carddemo.card.config;

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
 * Supplies this module's pooled JDBC data source and proves at startup that its connections resolve
 * inside the single schema the card bounded context owns.
 *
 * <h2>Purpose</h2>
 *
 * <p>Two responsibilities, and deliberately no others. The first is HikariCP sizing: the pool is
 * built here so that its type is a decision of this module rather than a consequence of whichever
 * pool happens to reach the classpath, while every number that sizes it is bound from external
 * configuration. The second is the schema pin: the statement that sets a connection's search path
 * is applied once per physical connection, and the schema it selects is then verified against the
 * two other places the same schema name is declared.</p>
 *
 * <p>No business rule lives here. The validation chains, the keyset paging behaviour and the field
 * masking encoded from the baseline card programs belong to the service, mapper and repository
 * packages of this context, so a reader looking for why a card update is refused will not find the
 * answer in this class.</p>
 *
 * <h2>Configuration consumed</h2>
 *
 * <p>Every value arrives from configuration and none is written into this class. The keys are
 * declared in {@code src/main/resources/application.yml}, and the two profile overlays beside it
 * vary only the three sizing values.</p>
 *
 * <ul>
 *   <li>{@code spring.datasource.url}, {@code spring.datasource.username} and
 *       {@code spring.datasource.password} reach this class only through the injected
 *       {@link DataSourceProperties}. All three are unresolved placeholders with no fallback, so an
 *       unset variable aborts context refresh and names itself rather than producing a service
 *       running against the wrong database.</li>
 *   <li>{@code spring.datasource.hikari.connection-init-sql} carries the search path statement, so
 *       the pin takes effect at the connection boundary rather than at any call site.</li>
 *   <li>{@code spring.datasource.hikari.maximum-pool-size}, {@code minimum-idle},
 *       {@code connection-timeout}, {@code max-lifetime}, {@code pool-name},
 *       {@code transaction-isolation} and the {@code data-source-properties} map bind onto the pool
 *       returned below, which is why this class holds no numeric literal at all.</li>
 *   <li>{@code spring.jpa.properties.hibernate.default_schema} and
 *       {@code spring.flyway.default-schema} are read only so that they can be compared. Nothing
 *       here configures either of them.</li>
 * </ul>
 *
 * <h2>Beans contributed</h2>
 *
 * <p>Exactly two, and neither is a general registration point. One {@link HikariDataSource}, which
 * the repositories, the migration engine and the JPA entity manager factory all draw their
 * connections from. One {@link SmartInitializingSingleton}, which runs once after the context's
 * ordinary singletons exist and yields the verified schema name.</p>
 *
 * <h2>Startup failure modes</h2>
 *
 * <ol>
 *   <li>An unset datasource variable fails placeholder resolution before a socket is opened.</li>
 *   <li>An unreachable database exhausts the configured connection timeout and fails context
 *       refresh, so a task never reports healthy while unable to serve a read.</li>
 *   <li>A schema disagreement between the pin, the mapping layer and the migration engine raises
 *       {@link IllegalStateException} from the verifier below, naming both schemas.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // WHY : Alternatives Considered: declaring a migration bean here, or adding a separate
    //       migration configuration class beside this one. Rejected because Spring Boot's own
    //       migration autoconfiguration already builds that bean from the spring.flyway.* keys in
    //       application.yml, and a bean declared here would take its place. The cost of taking its
    //       place is concrete rather than stylistic: enabled, locations, schemas, default-schema,
    //       create-schemas and baseline-on-migrate would all stop being read, so the base file and
    //       both profile overlays would keep declaring migration settings that nothing consumed,
    //       with no error anywhere to say so.
    //
    // WHY : Assumptions: schema ownership is already settled outside this class and needs no code
    //       here to assert it. spring.jpa.hibernate.ddl-auto is none (application.yml:510), so the
    //       mapping layer emits no DDL and validates nothing, and the whole persistent shape of
    //       this context exists only because db/migration/V1__card.sql created it. That is the sole
    //       migration this module carries; there is no V2__ script here, because the one seeded
    //       reference dataset belongs to the context that owns the lookup data rather than to this
    //       one.
    //
    // WHY : Alternatives Considered: issuing a schema, role or privilege statement from Java, so
    //       that a bare database could be brought up by starting the service. Rejected outright.
    //       data-migration/sql/V0__schemas_and_roles.sql is the exclusive authority for schemas,
    //       roles and grants, and its completion is a deployment precondition of this service
    //       rather than something this class can stand in for. It is also why
    //       spring.flyway.create-schemas is false (application.yml:605): a database missing that
    //       bootstrap aborts startup naming the absent schema, instead of gaining a second,
    //       differently-owned schema that carries none of the privileges the bootstrap grants.

    // Assumptions: this resolves the first EXISTING schema on the connection's search path, which
    //   is what makes it a probe of the pin rather than a second reading of the configuration that
    //   set it. PostgreSQL accepts a search path naming a schema that does not exist and ignores
    //   the entry, so a comparison drawn from configuration alone would agree with itself while the
    //   connection resolved something else entirely, or nothing at all.
    private static final String EFFECTIVE_SCHEMA_QUERY = "SELECT current_schema()";

    /**
     * Builds the pooled data source this module draws every database connection from.
     *
     * @param properties the {@link DataSourceProperties} bound from {@code spring.datasource},
     *     carrying the JDBC URL, the username, the password and the driver this class never names
     *     itself; must not be {@code null}
     * @return the {@link HikariDataSource} whose sizing, isolation level, transport properties and
     *     connection initialization statement are all bound from {@code spring.datasource.hikari},
     *     never {@code null}
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // WHY : Alternatives Considered: three other ways of making an unqualified table name
        //       resolve inside this context's own schema were weighed against pinning the search
        //       path on the connection. Writing the schema into every statement and every migration
        //       keeps the guarantee at each call site, so it holds only for as long as every future
        //       query author remembers it, and an omission stays invisible until a statement
        //       resolves somewhere else. Declaring the schema on each mapped entity's table
        //       annotation covers the mapped entities and nothing besides: not a native query, not
        //       the migration engine's own history table, and not any statement issued through this
        //       pool from outside the mapping layer. Appending a currentSchema parameter to the JDBC
        //       URL is unavailable here because that URL arrives as an opaque placeholder, so the
        //       parameter would have to be remembered by whoever sets the variable, and that fails
        //       silently when it is not.
        //       Binding spring.datasource.hikari.connection-init-sql onto the pool below puts the
        //       pin at the connection boundary instead, where it is one enforcement point applied to
        //       every physical connection the pool opens and cannot be forgotten by a later query
        //       author.
        //
        // WHY : Assumptions: the pin and the mapping layer must name the SAME schema, and they are
        //       told it independently. The bound statement is SET search_path TO card
        //       (application.yml:431), while the mapping layer is told card at
        //       spring.jpa.properties.hibernate.default_schema (application.yml:535), because it
        //       qualifies the SQL it generates from its own setting rather than from the
        //       connection's search path. Nothing in the compiler, the documentation gate or a unit
        //       test can observe a disagreement between those two: the module compiles, the gate
        //       passes, and the divergence appears only when a statement executes against a schema
        //       nobody intended. That silent gap is the entire reason the verifier bean below exists
        //       rather than this comment being the only safeguard.
        //
        // WHY : Assumptions: every number that sizes this pool is bound from
        //       spring.datasource.hikari, so this method holds none of them. The quantity that has
        //       to fit inside the cluster's connection budget is not the ceiling in any one file but
        //       the product of that ceiling with the running task count, because each task behind
        //       the internal load balancer holds its own pool, summed again across every context
        //       sharing the cluster. Binding the values keeps that arithmetic in the two profile
        //       overlays, where the development profile can let the pool drain to nothing and the
        //       production profile can hold a warm floor, without either decision being compiled
        //       into the image.
        //
        // WHY : Assumptions: there is no baseline number to carry across for pool size, which is
        //       worth stating because most values in this module are derived from one. The baseline
        //       defines its two card file resources with STRINGS(1), at app/csd/CARDDEMO.CSD:28 for
        //       CARDDAT and :16 for CARDAIX, so card access was served through exactly one string
        //       per file and the concurrency ceiling was one. That ceiling carries no business
        //       meaning: it is a property of the access method rather than a rule about cards.
        //       Concurrent pooled sessions are a capability the relational engine provides and the
        //       indexed-file platform did not, so pool sizing is a new degree of freedom the
        //       migration introduces rather than a tunable ported from the baseline. The baseline
        //       behaves one way and this service behaves the other, and the divergence is recorded
        //       in docs/architecture/cobol-to-service-traceability.md.
        //
        // WHY : Trade-offs: building the pool here rather than leaving the framework to build it
        //       makes the pool type this module's own decision, and the cost is specific. A data
        //       source declared in application code takes the place of the framework's own, so
        //       connection details contributed as a bean by a test harness are no longer applied to
        //       it, and a container-backed integration test has to publish its URL, username and
        //       password as spring.datasource properties, which this method does read. What is
        //       bought is that the pool cannot change type because an unrelated dependency
        //       reordered its own transitives, and that the sizing keys named above bind onto a pool
        //       this one file can be read against.
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * Supplies the startup callback that proves a pooled connection resolves inside the owned
     * schema.
     *
     * @param dataSource the {@link HikariDataSource} built above, from which one connection is
     *     borrowed to read the effective schema; must not be {@code null}
     * @param mappingSchema the {@link String} schema name bound from
     *     {@code spring.jpa.properties.hibernate.default_schema}, the name the mapping layer
     *     qualifies its generated SQL with; rejected when blank
     * @param migrationSchema the {@link String} schema name bound from
     *     {@code spring.flyway.default-schema}, the name the migration engine places its history
     *     table in; rejected when blank
     * @return a {@link SmartInitializingSingleton} whose callback yields the verified schema name
     *     and otherwise aborts context initialization, never {@code null}
     */
    @Bean
    public SmartInitializingSingleton cardSchemaPinVerifier(
            HikariDataSource dataSource,
            @Value("${spring.jpa.properties.hibernate.default_schema}") String mappingSchema,
            @Value("${spring.flyway.default-schema}") String migrationSchema) {

        // WHY : Assumptions: the migration schema compared here is only meaningful if the migration
        //       engine can match this connection at all, and that takes two Maven coordinates rather
        //       than one. Flyway 10 and later moved database-specific support out of the engine into
        //       one module per database, so services/card-service/pom.xml declares both the starter
        //       carrying the engine and its autoconfiguration (:320-323) and the PostgreSQL dialect
        //       module (:324-327). With the dialect absent the build compiles cleanly and then fails
        //       at application startup, when the engine finds no implementation matching a
        //       postgresql JDBC URL; there is no compile-time signal whatsoever, which is exactly
        //       why the second coordinate must never be dropped as a duplicate of the first. Neither
        //       carries a version, because the aggregator manages both, and the house reason for
        //       pinning exactly rather than floating is recorded at tests/README.md:178-184: an
        //       exact pin makes a run reproducible, while a floating dependency breaks determinism
        //       silently.
        //
        // WHY : Assumptions: a SmartInitializingSingleton callback runs after the context's ordinary
        //       singletons have been created, so the migration engine has already applied
        //       db/migration/V1__card.sql by the time the schema is read. Comparing the effective
        //       schema against BOTH the mapping name and the migration name proves that the three
        //       independent declarations agree, rather than deriving any one of them from another.
        //
        // WHY : Trade-offs: one pooled connection is borrowed while the context is still starting,
        //       which spends a connection acquisition before any request is accepted and, on a
        //       profile whose database capacity may be paused, pays the resume as well. Accepted
        //       because the alternative differs in kind rather than in degree: without this check a
        //       schema disagreement is discovered by whichever request first issues an unqualified
        //       statement, and the failure is then attributed to that request instead of to
        //       configuration. Aborting startup names both schemas and stops the task before the
        //       load balancer can register it.
        return () -> verifyEffectiveSchema(dataSource, mappingSchema, migrationSchema);
    }

    /**
     * Verifies that a pooled connection resolves the schema the mapping layer and the migration
     * engine were separately configured with.
     *
     * @param dataSource the {@link DataSource} that supplies the connection to inspect; must not be
     *     {@code null}
     * @param mappingSchema the {@link String} schema name the mapping layer qualifies its generated
     *     SQL with; rejected when blank
     * @param migrationSchema the {@link String} schema name the migration engine places its history
     *     table in; rejected when blank
     * @return the verified effective schema name, equal to both configured names, never
     *     {@code null}
     * @throws IllegalStateException if either configured name is blank, if the two disagree with
     *     each other, or if the connection resolves a different schema
     */
    private static String verifyEffectiveSchema(
            DataSource dataSource, String mappingSchema, String migrationSchema) {

        String mapping =
                requireSchemaName(mappingSchema, "spring.jpa.properties.hibernate.default_schema");
        String migration = requireSchemaName(migrationSchema, "spring.flyway.default-schema");

        // WHY : Assumptions: the two configured names are compared with each other BEFORE the
        //       database is opened. A disagreement between them is a misconfiguration of this
        //       module that needs no connection to detect, and reporting it on its own terms
        //       produces a message an operator can act on, whereas discovering it as a mismatch
        //       against whichever of the two the connection happened to resolve would name only one
        //       of the two keys at fault.
        if (!mapping.equals(migration)) {
            throw new IllegalStateException("Mapping schema '" + mapping
                    + "' from spring.jpa.properties.hibernate.default_schema disagrees with"
                    + " migration schema '" + migration + "' from spring.flyway.default-schema");
        }

        String effective = readEffectiveSchema(dataSource);
        if (!mapping.equals(effective)) {
            throw new IllegalStateException("Configured schema '" + mapping
                    + "' does not match the schema '" + effective
                    + "' a pooled connection resolves; check"
                    + " spring.datasource.hikari.connection-init-sql");
        }
        return effective;
    }

    /**
     * Accepts a configured schema name and rejects an absent or blank one.
     *
     * @param schemaName the {@link String} value bound from configuration, which may arrive
     *     {@code null} or blank when the key is absent or overridden with an empty value
     * @param propertyKey the {@link String} configuration key the value came from, carried so that
     *     the failure names the key an operator has to change rather than the field it landed in
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
        //       comparisons above against a name the database reports without it. That failure is
        //       the worst kind to debug, because its message shows two names that look identical.
        return schemaName.trim();
    }

    /**
     * Reads the schema that a pooled connection actually resolves.
     *
     * @param dataSource the {@link DataSource} to borrow exactly one connection from; must not be
     *     {@code null}
     * @return the effective schema name the connection reports, never {@code null}
     * @throws IllegalStateException if no row is returned, if the reported name is absent, or if the
     *     query cannot be executed
     */
    private static String readEffectiveSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(EFFECTIVE_SCHEMA_QUERY)) {

            if (!resultSet.next()) {
                throw new IllegalStateException(
                        "The database returned no row while resolving the effective schema");
            }
            String effectiveSchema = resultSet.getString(1);

            // WHY : Assumptions: an absent value here is the specific symptom of a search path on
            //       which every entry names a schema that does not exist, which is what a database
            //       missing its bootstrap looks like from the connection's side. It is separated
            //       from the mismatch case above because the remedy differs: the schema has to be
            //       created by the bootstrap, not renamed in configuration.
            if (effectiveSchema == null) {
                throw new IllegalStateException("A pooled connection resolved no schema at all;"
                        + " the search path names no schema that exists in this database");
            }
            return effectiveSchema;
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to resolve the effective schema from a pooled connection", failure);
        }
    }
}
