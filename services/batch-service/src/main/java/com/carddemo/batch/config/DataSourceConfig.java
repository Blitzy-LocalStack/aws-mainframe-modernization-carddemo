package com.carddemo.batch.config;

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
 * Builds the HikariCP data source and verifies the effective schema used by batch-service.
 *
 * <p>This class owns configuration-bound pool sizing and the connection-level search path only. It
 * deliberately owns no API representation, identity or request-filtering bean, because this module
 * exposes no business REST surface and no administrative trigger: its only invocation path is a
 * synchronous run-task call from a state-machine state.
 * </p>
 *
 * <p>Assumptions: the verification below matters more in this module than in any sibling. Every
 * other service initialises its connections with a single-schema search path, so an ordering
 * mistake has nothing to resolve against and fails loudly at the first unqualified statement. This
 * module's path is {@code batch, ledger, account, card, reference} — five schemas, because the
 * posting unit of work commits the transaction, the category balance and the account together and
 * is kept a single ACID commit rather than fragmented into a saga. An unqualified write under a
 * reordered path would therefore still resolve, against a real table in the wrong schema, and the
 * failure would be a plausible row rather than an error.
 * </p>
 *
 * <p>Assumptions: {@code spring.jpa.hibernate.ddl-auto} is {@code validate} here where the
 * siblings use {@code none}, and that difference is deliberate rather than accidental. This module
 * maps four FOREIGN schemas through explicit {@code @Table(schema = ...)} declarations that its own
 * {@code V1__batch.sql} does not create, so validation is what proves at startup that the tables
 * those owning services migrated still match what this module reads. It never emits DDL: Flyway
 * alone applies table DDL, and the deployment bootstrap creates the schemas, roles and grants.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // WHY : Alternatives Considered: registering the batch job repository and a transaction manager
    //       here as well, which would make this the module's single configuration class. Rejected:
    //       Spring Boot already auto-configures both from this data source, so a local registration
    //       would restate a framework default and then have to be kept in step with it. Keeping this
    //       class at the persistence boundary stops it becoming the module's general bean registry.

    /**
     * The query that resolves the first valid schema on the active connection search path.
     */
    private static final String CURRENT_SCHEMA_QUERY = "SELECT current_schema()";

    /**
     * Builds the HikariCP data source from the core datasource and pool properties.
     *
     * @param properties the {@link DataSourceProperties} carrying the JDBC driver, URL, username and
     *     password read from external configuration; must not be {@code null}
     * @return the {@link HikariDataSource} whose sizing and connection initialization are bound from
     *     {@code spring.datasource.hikari}, never {@code null}
     * @throws org.springframework.beans.factory.BeanCreationException if the configured JDBC driver
     *     or URL cannot be resolved
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // WHY : Refactoring Rationale: the baseline runs this work as JCL steps against VSAM files
        //       opened one at a time -- app/jcl/POSTTRAN.jcl declares nine DD statements for a single
        //       EXEC PGM=CBTRN02C step -- so file-level access is serialised by the platform and the
        //       program never chooses a concurrency level. Binding the pool ceiling and idle floor
        //       records the deliberate move to concurrent database sessions instead of inheriting a
        //       library default for behaviour the baseline did not expose.
        // WHY : Trade-offs: this module's pool is deliberately SMALLER than an online service's,
        //       four against ten. A batch step is one chunk-oriented reader and writer on one thread
        //       per step, so additional connections would sit idle while still counting against the
        //       cluster's connection ceiling that every online task shares -- and the batch window is
        //       exactly when those tasks are least able to lose capacity to a neighbour.
        // WHY : Alternatives Considered: a currentSchema JDBC URL parameter, or Hibernate's
        //       default_schema property, instead of Hikari connection initialization. The URL is
        //       environment-owned, so an operator edit could silently remove the pin; the Hibernate
        //       property reaches neither native JDBC nor Flyway, and this module's steps use both.
        //       Hikari applies the configured statement to every physical pool connection, which is
        //       the only place that covers all three access paths.
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Supplies the startup callback that verifies the effective schema after singleton creation.
     *
     * @param dataSource the {@link HikariDataSource} whose initialized connection is inspected; must
     *     not be {@code null}
     * @param expectedSchema the {@link String} schema name read from
     *     {@code spring.flyway.default-schema}; must not be blank
     * @return a {@link SmartInitializingSingleton} callback that aborts context initialization when
     *     the effective schema does not match, never {@code null}
     */
    @Bean
    public SmartInitializingSingleton searchPathVerifier(
            HikariDataSource dataSource,
            @Value("${spring.flyway.default-schema}") String expectedSchema) {

        // WHY : Trade-offs: resolving one connection during context creation spends one pool
        //       acquisition before any step runs. That is the cheapest possible moment to spend it,
        //       and the alternative is worse than a late error: PostgreSQL accepts a search path
        //       whose leading schema is absent or misordered, so without this check the first
        //       unqualified statement of a nightly posting run decides where rows land.
        // WHY : Assumptions: SmartInitializingSingleton runs after regular singleton initialization,
        //       so Flyway has completed before this callback inspects a pooled connection. Comparing
        //       the result against Flyway's separately configured default schema proves the two
        //       configuration paths AGREE rather than deriving one from the other -- if the pool's
        //       initialization statement and Flyway's schema ever disagree, this module would migrate
        //       one schema and write into another, and each setting alone looks correct.
        return () -> verifySearchPath(dataSource, expectedSchema);
    }

    /**
     * Resolves the connection's effective schema and verifies it against configuration.
     *
     * @param dataSource the {@link DataSource} that supplies the connection to inspect; must not be
     *     {@code null}
     * @param expectedSchema the {@link String} schema name supplied by configuration; must not be
     *     blank
     * @return the verified effective schema name, never {@code null}
     * @throws IllegalStateException if the expected schema is blank, the query cannot complete, or
     *     the connection resolves a different schema
     */
    private static String verifySearchPath(DataSource dataSource, String expectedSchema) {
        if (expectedSchema == null || expectedSchema.isBlank()) {
            throw new IllegalStateException(
                    "spring.flyway.default-schema must name the schema to verify");
        }

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(CURRENT_SCHEMA_QUERY)) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "The database returned no row while resolving the effective schema");
            }

            String actualSchema = result.getString(1);
            if (!expectedSchema.equals(actualSchema)) {
                throw new IllegalStateException("Configured database schema '" + expectedSchema
                        + "' does not match effective schema '" + actualSchema + "'");
            }
            return actualSchema;
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to resolve the effective schema from a pooled connection", failure);
        }
    }
}
