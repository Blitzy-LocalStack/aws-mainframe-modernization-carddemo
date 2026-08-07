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
 * Builds the connection pool and proves that every pooled connection resolves unqualified table names
 * inside the {@code auth} schema and nowhere else.
 *
 * <h2>Purpose</h2>
 *
 * <p>Two responsibilities and no others: configuration-bound pool sizing, and verification of the
 * connection-level search path at start-up. This class registers no representation bean, no error
 * advice, no metrics binding, no identity bean and no request filter. Those belong to
 * {@code OpenApiConfig} and {@code SecurityConfig} in this same package, and keeping the persistence
 * boundary separate is what stops this class becoming the module's general bean registry.</p>
 *
 * <p>Refactoring Rationale: this class is authored because the package charter beside it declared a
 * CLOSED SET OF THREE configuration types while the directory held one, and carried no
 * target-versus-census disclaimer to mark the difference. The charter's description of this type -- pool
 * behaviour plus a search-path pin applied at the connection boundary rather than per statement -- is
 * what is implemented here, so the roster is closed by delivering the named type rather than by
 * weakening the roster.</p>
 *
 * <h2>Why the pin is verified rather than merely configured</h2>
 *
 * <p>Assumptions: the pin itself is a configuration value, {@code spring.datasource.hikari.
 * connection-init-sql}, which this module's {@code application.yml} sets to
 * {@code SET search_path TO auth}. Hikari applies it to every PHYSICAL connection it opens, so it
 * covers JPA, native JDBC and Flyway alike. What configuration cannot do is prove it took effect, and
 * that is the gap this class closes: PostgreSQL accepts a search path naming a schema that does not
 * exist, {@code current_schema()} then resolves to nothing, and an unqualified statement fails -- or
 * worse, resolves in another context's schema -- only when a request first reaches it. Verifying once at
 * start-up turns that into a refusal to start.</p>
 *
 * <p>Assumptions: the verification compares the effective schema against
 * {@code spring.flyway.default-schema} rather than against a literal. Those two keys are set
 * independently -- one governs where migrations are applied, the other where runtime statements resolve
 * -- and comparing them proves the two configuration paths AGREE. Deriving one from the other, or
 * comparing both against a constant in this file, would make a disagreement between them
 * unobservable, and a disagreement is exactly the condition in which migrations land in one schema
 * while the service reads another.</p>
 *
 * <p>Alternatives Considered: a {@code currentSchema} parameter on the JDBC URL. Rejected because the
 * URL is supplied by the environment through {@code SPRING_DATASOURCE_URL}, so an operator editing it
 * for an unrelated reason could drop the pin without touching anything under review, and nothing would
 * report the loss.</p>
 *
 * <p>Alternatives Considered: Hibernate's {@code default_schema} property. Rejected because it governs
 * only statements Hibernate generates: native JDBC and Flyway would still resolve against whatever the
 * session's search path happened to be, so the pin would hold for most of the module's traffic and
 * silently not for the rest.</p>
 *
 * <p>Alternatives Considered: qualifying the schema on every entity and every query. Rejected because
 * it makes schema selection part of compiled code, so one image could no longer serve every
 * environment, and because a single omitted qualifier is invisible in review while a connection-level
 * pin cannot be omitted per statement.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /**
     * The query that resolves the first existing schema on the connection's search path.
     *
     * <p>Assumptions: {@code current_schema()} is used rather than reading the raw
     * {@code search_path} setting, because the raw setting reports what was ASKED FOR and this function
     * reports what RESOLVED. A path naming an absent schema differs between the two, and that
     * difference is the condition worth catching.</p>
     */
    private static final String EFFECTIVE_SCHEMA_QUERY = "SELECT current_schema()";

    /**
     * Builds the connection pool from the core datasource properties and the pool properties.
     *
     * <p>Assumptions: pool sizing, the initialisation statement and the TLS parameters are all BOUND
     * from configuration rather than set here, so one image serves every environment and each profile
     * pays its own connection cost. The development profile is free to let the pool empty, which Aurora
     * Serverless auto-pause requires because a paused cluster needs every connection closed; the
     * production profile keeps an idle floor against provisioned capacity. Writing either choice into
     * this factory would make the image environment-specific.</p>
     *
     * <p>Assumptions: {@code spring.jpa.hibernate.ddl-auto} stays {@code none} while this factory binds
     * connection behaviour. Flyway alone applies table definitions and the deployment bootstrap creates
     * the schema, the role and the grants, so there is exactly one schema manager for the {@code auth}
     * schema. A second one would let two independently deployed artifacts reshape one table after the
     * other had bound to it.</p>
     *
     * @param properties the datasource properties carrying the JDBC URL, username and password read
     *     from external configuration; must not be {@code null}
     * @return the pool whose sizing and connection initialisation are bound from
     *     {@code spring.datasource.hikari}, never {@code null}
     * @throws org.springframework.beans.factory.BeanCreationException if the configured JDBC driver or
     *     URL cannot be resolved
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Supplies the start-up callback that verifies the effective schema once the singletons exist.
     *
     * <p>Assumptions: this runs as a {@link SmartInitializingSingleton}, which the container invokes
     * AFTER ordinary singleton initialisation, so Flyway has already migrated by the time a connection
     * is inspected. Running earlier would inspect a schema that migrations had not yet created and
     * report a failure that is only a matter of ordering.</p>
     *
     * <p>Trade-offs: one pool acquisition is spent during context creation, before traffic is accepted.
     * Accepted because the alternative is discovering a mis-resolved search path on a user's first
     * request, where it presents as a missing table rather than as a configuration fault.</p>
     *
     * @param dataSource the pool whose initialised connection is inspected; must not be {@code null}
     * @param expectedSchema the schema name read from {@code spring.flyway.default-schema}; must not be
     *     blank
     * @return a callback that aborts context initialisation when the effective schema disagrees, never
     *     {@code null}
     */
    @Bean
    public SmartInitializingSingleton authSchemaPinVerifier(
            HikariDataSource dataSource,
            @Value("${spring.flyway.default-schema}") String expectedSchema) {
        return () -> verifyEffectiveSchema(dataSource, expectedSchema);
    }

    /**
     * Resolves the connection's effective schema and verifies it against configuration.
     *
     * @param dataSource the pool that supplies the connection to inspect; must not be {@code null}
     * @param expectedSchema the schema name supplied by configuration; must not be blank
     * @return the verified effective schema name, never {@code null}
     * @throws IllegalStateException if the expected schema is blank, if the query cannot complete, or
     *     if the connection resolves a different schema, each of which leaves unqualified statements
     *     able to resolve outside the schema this context owns
     */
    static String verifyEffectiveSchema(DataSource dataSource, String expectedSchema) {
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

            // WHY : Assumptions: a null first column is treated as a disagreement rather than
            //       dereferenced. current_schema() returns NULL when the search path names only
            //       schemas that do not exist, which is precisely the condition this method exists to
            //       catch, so it has to reach the comparison rather than raise a null pointer.
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
