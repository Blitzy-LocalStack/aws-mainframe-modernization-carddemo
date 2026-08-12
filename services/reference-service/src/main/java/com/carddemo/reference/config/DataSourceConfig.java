package com.carddemo.reference.config;

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

    /**
     * The query that reports the first existing schema on the connection's effective search path.
     */
    private static final String EFFECTIVE_SCHEMA_QUERY = "SELECT current_schema()";

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
        //       shared a single thread. Binding a pool here records the deliberate move to
        //       concurrent database sessions instead of inheriting a library default for behaviour
        //       the baseline never exposed.
        // WHY : Trade-offs: the ceiling and the idle floor are declared per profile rather than
        //       here, and are deliberately different in each. The dev profile lets the pool empty
        //       because Aurora Serverless auto-pause requires every connection to be closed, and it
        //       accepts a longer first acquisition because a resume takes on the order of fifteen
        //       seconds; the prod profile holds an idle floor against steady provisioned capacity.
        //       Keeping both in configuration lets one image serve every environment.
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
            throw new IllegalStateException(
                    "Unable to resolve the effective schema from a pooled connection", failure);
        }
    }
}
