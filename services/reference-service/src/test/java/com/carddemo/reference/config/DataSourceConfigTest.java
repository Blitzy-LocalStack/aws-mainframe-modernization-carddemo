package com.carddemo.reference.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the schema pin of this context to the two artifacts that have to agree about it.
 *
 * <p>Assumptions: the class under test asserts an EFFECT rather than a setting -- that the schema a
 * pooled connection resolves is the schema the migrations declare -- so these assertions come in two
 * halves. The profile half proves the two configuration keys agree, which is what makes the runtime
 * comparison able to succeed at all; the verifier half proves the comparison bites when they do not.
 * Either half alone would pass against a broken pin: agreeing keys prove nothing if nothing compares
 * them, and a comparison proves nothing if the shipped configuration cannot satisfy it.</p>
 *
 * <p>Trade-offs: the verifier half drives a mocked pool rather than a real engine, so it binds the
 * comparison and not the SQL. The statement it issues is asserted as text against the engine's own
 * documented function name, and this module's repository integration tests already run against a real
 * PostgreSQL image, so the query's validity is covered where an engine is available while the refusal
 * paths -- an absent schema, a blank configuration value, a driver failure -- are covered here, where
 * they can be provoked deliberately.</p>
 */
class DataSourceConfigTest {

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /** The test profile, packaged from {@code src/test/resources}. */
    private static final String TEST_PROFILE = "/application-test.yml";

    /** The schema this context owns, and the only one its connections may resolve. */
    private static final String OWNED_SCHEMA = "reference";

    /** The statement PostgreSQL answers with the first existing schema on the search path. */
    private static final String EFFECTIVE_SCHEMA_QUERY = "SELECT current_schema()";

    /** The instance under test; it holds no state, so one instance serves every case. */
    private final DataSourceConfig config = new DataSourceConfig();

    /**
     * Confirms every shipped profile pins the pool at exactly the schema its migrations declare.
     *
     * <p>Assumptions: this is asserted from the SHIPPED profiles rather than from a literal pair,
     * because the runtime check compares those two keys and nothing else does. A profile that pinned
     * one schema while migrating another would start, migrate, and then abort on the comparison, and
     * this is the only place that outcome is visible before a container is started.</p>
     */
    @Test
    @DisplayName("every shipped profile pins the pool at the schema its migrations declare")
    void everyShippedProfilePinsTheSchemaItsMigrationsDeclare() {
        for (String profile : new String[] {BASE_PROFILE, TEST_PROFILE}) {
            Object pin = value(profile, "spring", "datasource", "hikari", "connection-init-sql");
            Object migrationSchema = value(profile, "spring", "flyway", "default-schema");

            assertThat(migrationSchema)
                    .as("%s must declare the migration schema the pin is compared against", profile)
                    .isEqualTo(OWNED_SCHEMA);
            assertThat(String.valueOf(pin).trim())
                    .as("%s pins [%s], which must name the migration schema and nothing else",
                            profile, pin)
                    .isEqualTo("SET search_path TO " + migrationSchema);
        }
    }

    /**
     * Confirms neither environment overlay redeclares the pin or the migration schema.
     *
     * <p>Assumptions: one owned schema is an architectural invariant of this context rather than a
     * per-environment value, and that is what lets the class under test compare against a single
     * name. An overlay declaring either key would give the invariant two legitimate values, so the
     * absence is asserted instead of assumed.</p>
     */
    @Test
    @DisplayName("neither environment overlay redeclares the pin or the migration schema")
    void neitherEnvironmentOverlayRedeclaresThePinOrTheMigrationSchema() {
        for (String overlay : new String[] {"/application-dev.yml", "/application-prod.yml"}) {
            assertThat(value(overlay, "spring", "datasource", "hikari", "connection-init-sql"))
                    .as("%s must inherit the pin, not restate it", overlay)
                    .isNull();
            assertThat(value(overlay, "spring", "flyway", "default-schema"))
                    .as("%s must inherit the migration schema, not restate it", overlay)
                    .isNull();
        }
    }

    /**
     * Confirms the factory produces a HikariCP pool carrying the configured URL.
     *
     * <p>Assumptions: the pool TYPE is asserted because the binding prefix
     * {@code spring.datasource.hikari} only reaches the object the factory returns; a pool of another
     * implementation would silently ignore every sizing key the profiles declare. Building the pool
     * opens no connection, so this costs no database.</p>
     */
    @Test
    @DisplayName("the factory produces a HikariCP pool carrying the configured URL")
    void theFactoryProducesAHikariPoolCarryingTheConfiguredUrl() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:postgresql://db.invalid:5432/carddemo");
        properties.setUsername("carddemo_reference_app");
        properties.setPassword("not-a-real-password");

        try (HikariDataSource pool = config.dataSource(properties)) {
            assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.invalid:5432/carddemo");
            assertThat(pool.getUsername()).isEqualTo("carddemo_reference_app");
        }
    }

    /**
     * Confirms a connection resolving the owned schema is accepted, and that the query used is the
     * one that reports the effective schema rather than the requested one.
     *
     * @throws SQLException never in practice; the mocked JDBC members declare it
     */
    @Test
    @DisplayName("a connection resolving the owned schema is accepted")
    void aConnectionResolvingTheOwnedSchemaIsAccepted() throws SQLException {
        Statement statement = mock(Statement.class);
        HikariDataSource pool = poolResolving(OWNED_SCHEMA, statement);

        assertThatCode(() -> verifier(pool, OWNED_SCHEMA).afterSingletonsInstantiated())
                .doesNotThrowAnyException();

        // WHY : Assumptions: the STATEMENT is asserted, not merely the outcome, because the whole
        //       value of this check is that it reads back from the server. A verifier that compared
        //       two configuration values, or echoed the statement the pool was told to run, would
        //       satisfy every other assertion in this class while proving nothing about effect.
        verify(statement).executeQuery(EFFECTIVE_SCHEMA_QUERY);
    }

    /**
     * Confirms a connection resolving some other schema stops the context, naming both schemas.
     *
     * <p>Assumptions: the message is asserted to carry both names because the case a reader most
     * often meets is an EMPTY effective schema, which the engine reports when the pinned schema is
     * absent from the database. A message naming only the expectation would read as though no
     * comparison had happened.</p>
     *
     * @throws SQLException never in practice; the mocked JDBC members declare it
     */
    @Test
    @DisplayName("a connection resolving another schema stops the context and names both schemas")
    void aConnectionResolvingAnotherSchemaStopsTheContext() throws SQLException {
        HikariDataSource pool = poolResolving("public", mock(Statement.class));

        assertThatThrownBy(() -> verifier(pool, OWNED_SCHEMA).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OWNED_SCHEMA)
                .hasMessageContaining("public");
    }

    /**
     * Confirms an empty result set is refused rather than read as agreement.
     *
     * <p>Assumptions: this path is asserted separately because it is the shape a driver returns when
     * the query is answered with no row, and a verifier reading an absent row as a match would report
     * success for a database it had learned nothing about.</p>
     *
     * @throws SQLException never in practice; the mocked JDBC members declare it
     */
    @Test
    @DisplayName("a result set carrying no row is refused")
    void aResultSetCarryingNoRowIsRefused() throws SQLException {
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(EFFECTIVE_SCHEMA_QUERY)).thenReturn(result);
        when(result.next()).thenReturn(false);

        assertThatThrownBy(() -> verifier(pool, OWNED_SCHEMA).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no row");
    }

    /**
     * Confirms an absent or blank migration schema is refused before any connection is taken.
     *
     * <p>Assumptions: the pool is a strict mock with no stubbing, so a verifier that acquired a
     * connection before checking its expectation would fail here on a null connection rather than
     * pass. That is the point of the case: an unset expectation must not be able to match anything.</p>
     */
    @Test
    @DisplayName("an absent or blank migration schema is refused before a connection is taken")
    void anAbsentOrBlankMigrationSchemaIsRefused() {
        HikariDataSource unusedPool = mock(HikariDataSource.class);

        for (String unset : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> verifier(unusedPool, unset).afterSingletonsInstantiated())
                    .as("a migration schema of [%s] must be refused", unset)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.flyway.default-schema");
        }
    }

    /**
     * Confirms a driver failure stops the context and keeps the original failure as the cause.
     *
     * <p>Assumptions: the cause is asserted rather than the message alone, because a connection
     * failure at this point is almost always a network, credential or TLS fault whose only usable
     * detail is the driver's own exception. Discarding it would leave an operator with a sentence
     * about schemas for a problem that has nothing to do with schemas.</p>
     *
     * @throws SQLException never in practice; the mocked JDBC member declares it
     */
    @Test
    @DisplayName("a driver failure stops the context and keeps the driver exception as the cause")
    void aDriverFailureStopsTheContextAndKeepsTheCause() throws SQLException {
        HikariDataSource pool = mock(HikariDataSource.class);
        SQLException refused = new SQLException("connection refused");
        when(pool.getConnection()).thenThrow(refused);

        assertThatThrownBy(() -> verifier(pool, OWNED_SCHEMA).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pooled connection")
                .hasCause(refused);
    }

    /**
     * Builds the startup callback the container would register for the given pool and schema.
     *
     * @param pool the pool the callback inspects; must not be {@code null}
     * @param expectedSchema the migration schema the callback compares against; may be blank, which
     *     is one of the cases under test
     * @return the callback under test, never {@code null}
     */
    private SmartInitializingSingleton verifier(HikariDataSource pool, String expectedSchema) {
        return config.referenceSchemaPinVerifier(pool, expectedSchema);
    }

    /**
     * Builds a mocked pool whose single connection reports the given effective schema.
     *
     * @param effectiveSchema the value {@code current_schema()} is to report; must not be
     *     {@code null}
     * @param statement the statement mock to drive, supplied by the caller so it can be verified
     * @return a pool mock wired through connection, statement and result set, never {@code null}
     * @throws SQLException never in practice; the mocked JDBC members declare it
     */
    private HikariDataSource poolResolving(String effectiveSchema, Statement statement)
            throws SQLException {
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        ResultSet result = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(EFFECTIVE_SCHEMA_QUERY)).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(effectiveSchema);
        return pool;
    }

    /**
     * Reads one nested value from a packaged YAML profile.
     *
     * @param resource the class-path resource to read; must name a YAML document
     * @param path the key sequence to walk, outermost first; must not be empty
     * @return the value found, or {@code null} when the profile does not declare that key
     * @throws IllegalStateException if the resource is absent from the test class path, which would
     *     mean this assertion was silently reading nothing, or if it cannot be read
     */
    @SuppressWarnings("unchecked")
    private Object value(String resource, String... path) {
        try (InputStream document = DataSourceConfigTest.class.getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(resource + " is not on the test class path");
            }
            Object current = new Yaml().load(document);
            for (String key : path) {
                if (!(current instanceof Map<?, ?> mapping)) {
                    return null;
                }
                current = ((Map<String, Object>) mapping).get(key);
                if (current == null) {
                    return null;
                }
            }
            return current;
        } catch (IOException problem) {
            throw new IllegalStateException(resource + " could not be read", problem);
        }
    }
}
