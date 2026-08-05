package com.carddemo.transaction.config;

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
 * Builds the HikariCP data source and verifies the effective schema used by transaction-service.
 *
 * <p>This class owns configuration-bound pool sizing and the connection-level search path only. It
 * deliberately owns no API representation, shared error handling, observability, identity, or
 * request-filtering bean. Those registrations remain in {@code OpenApiConfig} and
 * {@code SecurityConfig}.
 * </p>
 *
 * <p>Assumptions: the shared error advice identifies persistence conflicts by fully qualified
 * exception names because common-lib carries no persistence dependency. This module carries Data
 * JPA and the PostgreSQL driver, so the named exception hierarchy can be raised without adding a
 * local handler here.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // WHY : Alternatives Considered: shared representation, error, metrics, identity and request
    //       filtering beans could have followed the data source into the first configuration class.
    //       OpenApiConfig owns the representation, error and metrics registrations, while
    //       SecurityConfig owns identity and request filtering. Keeping this class at the persistence
    //       boundary prevents it from becoming the module's general bean registry.

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
        // WHY : Refactoring Rationale: app/csd/CARDDEMO.CSD L79 assigns TRANSACT one STRINGS access
        //       path, so the baseline serialises file-level access. Binding the target pool's ceiling
        //       and idle floor records the deliberate move to concurrent database sessions instead of
        //       inheriting a library default for behaviour the baseline did not expose.
        // WHY : Trade-offs: the dev profile lets the pool empty because Aurora Serverless auto-pause
        //       requires every connection to close, and it extends acquisition time because a resume
        //       takes on the order of fifteen seconds. The prod profile keeps an idle floor against
        //       steady provisioned capacity. Keeping both choices in profile configuration lets each
        //       environment pay its own connection cost without rebuilding the image.
        // WHY : Alternatives Considered: hard-coding the schema in this factory or qualifying every
        //       entity. Both make schema selection part of compiled code. Binding the Hikari settings
        //       keeps one image usable in every profile while preserving one configuration authority.
        // WHY : Alternatives Considered: a currentSchema JDBC URL parameter and Hibernate's
        //       default_schema property. The URL is environment-owned and an operator edit could
        //       silently remove its pin; the Hibernate property does not reach native JDBC or Flyway.
        //       Hikari connection initialization applies the configured statement to every physical
        //       pool connection.
        // WHY : Assumptions: spring.jpa.hibernate.ddl-auto remains none while this factory binds
        //       connection behaviour. Transaction-service owns the ledger schema and batch-service
        //       writes it through a scoped grant; two independently deployed schema managers could
        //       reshape one table contract after the other deployable had bound to it. Flyway alone
        //       applies table DDL, while the deployment bootstrap creates schemas, roles and grants.
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
        //       acquisition before traffic is accepted. Without this check PostgreSQL accepts a
        //       search path whose first schema is absent, current_schema() reports no schema, and an
        //       unqualified write can fail or resolve outside the owned schema only when a later
        //       request reaches it.
        // WHY : Assumptions: SmartInitializingSingleton runs after regular singleton initialization,
        //       so Flyway initialization has completed before this callback inspects a pooled
        //       connection. Comparing the result with Flyway's separately configured default schema
        //       proves the two configuration paths agree instead of deriving one from the other.
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
