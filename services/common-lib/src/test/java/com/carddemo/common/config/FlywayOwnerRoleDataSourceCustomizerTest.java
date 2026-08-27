package com.carddemo.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.CardDemoCommonAutoConfiguration;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Holds the migration owner-role DataSource customizer to the contract the deprecated {@code initSql}
 * setting used to carry, which is the part the companion callback cannot carry on its own.
 *
 * <p><b>Purpose.</b> {@code initSql} ran on the raw JDBC connection before Flyway wrapped it, so the
 * owner role was in force for everything the engine did afterwards -- including creating
 * {@code flyway_schema_history} -- and the engine's own role restores kept it rather than undoing it.
 * The cases here assert the parts of that a unit test can see: that the configured DataSource is
 * replaced, that each connection handed out has had the exact quoted statement issued on it, that a
 * rejected statement closes the connection instead of returning it un-elevated, that a configuration
 * in which Flyway would share the application's pool is refused, that a blank role is a documented
 * opt-out, and the two registration verdicts. The part a unit test cannot see -- that the objects a
 * real migration creates end up owned by the NOLOGIN role -- is asserted by the consuming services'
 * ownership integration tests, which read {@code pg_namespace} and {@code pg_class} after Flyway has
 * run.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A test class is instantiated by JUnit,
 * takes no parameter, returns nothing and raises nothing a caller handles. The inapplicability is
 * declared rather than passed over, because the Explainability rule forbids a docstring that omits
 * parameters or return values and a reader has to tell a declared inapplicability from an oversight.
 *
 * <p>Assumptions: the JDBC surface is supplied the same way {@code FlywayOwnerRoleCallbackTest}
 * supplies it -- {@link DataSource} implemented directly, because it declares eight methods and the
 * wrapper delegates all of them, and {@link Connection} and {@link Statement} through reflective
 * proxies, because they declare some hundred abstract methods between them and three are called. The
 * alternatives were rejected in that class for reasons that have not changed: a hand-written stub of
 * the two large interfaces would be almost entirely noise, and a mocking framework would make these
 * the only classes in the module a reader needs a second testing vocabulary for.
 */
@DisplayName("The migration owner-role customizer elevates every connection Flyway borrows")
class FlywayOwnerRoleDataSourceCustomizerTest {

    /** The role name the accepting cases configure, in the shape the bootstrap SQL generates. */
    private static final String OWNER_ROLE = "carddemo_account_owner";

    /** The statement the accepting cases expect to reach the driver. */
    private static final String EXPECTED_SQL = "SET ROLE \"carddemo_account_owner\"";

    /** The auto-configuration exercised by the registration cases. */
    private static final AutoConfigurations SHARED_KERNEL =
            AutoConfigurations.of(CardDemoCommonAutoConfiguration.class);

    /**
     * Confirms the configured DataSource is replaced and every connection it hands out has assumed the
     * role, with the name quoted as an identifier.
     *
     * <p>Assumptions: the connection itself must NOT be closed on the success path -- Flyway holds its
     * main connection open for the whole run -- so the case asserts the statement was closed and the
     * connection was not, which together describe the try-with-resources the wrapper uses.</p>
     *
     * @throws SQLException if the stub's connection hand-out or the elevation raises, which no
     *     accepting case expects and which JUnit reports as a failure
     */
    @Test
    @DisplayName("the role is assumed on each connection handed out, with its name quoted")
    void wrapsTheConfiguredDataSourceAndElevatesEachConnection() throws SQLException {
        RecordingDataSource configured = new RecordingDataSource(null);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(configured);
        FlywayOwnerRoleDataSourceCustomizer customizer = customizerFor(OWNER_ROLE);

        customizer.customize(configuration);

        assertThat(configuration.getDataSource())
                .as("the configured DataSource has to be replaced, because the role must be in force"
                        + " before Flyway wraps a connection and captures its role")
                .isNotSameAs(configured);
        assertThat(customizer.ownerRole()).isEqualTo(OWNER_ROLE);
        assertThat(customizer.setRoleStatement()).isEqualTo(EXPECTED_SQL);

        Connection first = configuration.getDataSource().getConnection();
        Connection second = configuration.getDataSource().getConnection();

        assertThat(configured.executed)
                .as("one statement per connection, because the engine opens a main connection and a"
                        + " separate migration connection under its transactional lock")
                .containsExactly(EXPECTED_SQL, EXPECTED_SQL);
        assertThat(configured.statementsClosed).isEqualTo(2);
        assertThat(configured.connectionsClosed)
                .as("a successful elevation must hand the connection back open")
                .isZero();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
    }

    /**
     * Confirms the credentialed overload elevates as well as the no-argument one.
     *
     * <p>Assumptions: both overloads are covered because Boot hands Flyway a
     * {@code SimpleDriverDataSource} whose own {@code getConnection()} may route through either, and a
     * wrapper that elevated only one of them would fail open on whichever it missed.</p>
     *
     * @throws SQLException if the stub's connection hand-out or the elevation raises, which this case
     *     does not expect and which JUnit reports as a failure
     */
    @Test
    @DisplayName("a connection asked for by credential is elevated too")
    void elevatesCredentialedConnectionsToo() throws SQLException {
        RecordingDataSource configured = new RecordingDataSource(null);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(configured);
        customizerFor(OWNER_ROLE).customize(configuration);

        assertThat(configuration.getDataSource().getConnection("migrator", "secret")).isNotNull();

        assertThat(configured.executed).containsExactly(EXPECTED_SQL);
        assertThat(configured.credentialedRequests).isEqualTo(1);
    }

    /**
     * Confirms a rejected statement closes the connection and reports the role and the property.
     *
     * <p>Assumptions: this is the fail-closed case, and closing is the assertion that matters.
     * Returning the connection un-elevated would let the migration run as the migrator and create every
     * object under the wrong owner, which is silent -- the migration succeeds and the privilege failure
     * surfaces later, in a service that never ran it.</p>
     */
    @Test
    @DisplayName("a server rejection closes the connection rather than handing it back un-elevated")
    void failsClosedWhenTheRoleCannotBeAssumed() {
        SQLException rejection = new SQLException("role does not exist", "42704");
        RecordingDataSource configured = new RecordingDataSource(rejection);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(configured);
        customizerFor(OWNER_ROLE).customize(configuration);

        assertThatThrownBy(() -> configuration.getDataSource().getConnection())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(OWNER_ROLE)
                .hasMessageContaining(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY)
                .hasCauseReference(rejection);

        assertThat(configured.connectionsClosed)
                .as("the un-elevated connection must not escape")
                .isEqualTo(1);
    }

    /**
     * Confirms applying the customizer twice leaves one wrapper rather than two.
     *
     * <p>Assumptions: a configuration can be customized more than once -- a context refreshed twice, or
     * a test that rebuilds the Flyway bean -- and a wrapper around a wrapper would issue the same
     * statement twice per connection for no gain.</p>
     *
     * @throws SQLException if the stub's connection hand-out or the elevation raises, which this case
     *     does not expect and which JUnit reports as a failure
     */
    @Test
    @DisplayName("a second application is a no-op rather than a second wrapper")
    void isIdempotent() throws SQLException {
        RecordingDataSource configured = new RecordingDataSource(null);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(configured);
        FlywayOwnerRoleDataSourceCustomizer customizer = customizerFor(OWNER_ROLE);

        customizer.customize(configuration);
        DataSource afterFirst = configuration.getDataSource();
        customizer.customize(configuration);

        assertThat(configuration.getDataSource()).isSameAs(afterFirst);
        assertThat(configuration.getDataSource().getConnection()).isNotNull();
        assertThat(configured.executed).containsExactly(EXPECTED_SQL);
    }

    /**
     * Confirms a configuration in which Flyway shares the application's pool is refused.
     *
     * <p>Assumptions: the refusal names the property to set rather than only the problem, because the
     * remedy -- giving Flyway its own DataSource -- is one configuration key and a reader has no way to
     * derive it from the symptom.</p>
     *
     * <p>Alternatives Considered: wrapping the shared pool and resetting the role when a connection is
     * closed. Rejected in the production class because the reset is itself a statement that can fail,
     * and a failed reset leaves a request-serving thread holding the migration owner's privileges --
     * the exact escalation the reset was added to prevent, silently. The case is asserted here so that
     * a later relaxation of the guard has to change a test rather than a comment.</p>
     */
    @Test
    @DisplayName("Flyway sharing the application's DataSource is refused, not wrapped")
    void refusesTheApplicationsOwnDataSource() {
        RecordingDataSource applicationPool = new RecordingDataSource(null);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(applicationPool);
        FlywayOwnerRoleDataSourceCustomizer customizer =
                new FlywayOwnerRoleDataSourceCustomizer(
                        OWNER_ROLE, providerOf(applicationPool));

        assertThatThrownBy(() -> customizer.customize(configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OWNER_ROLE)
                .hasMessageContaining(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY)
                .hasMessageContaining("spring.flyway.user");

        assertThat(configuration.getDataSource())
                .as("a refused configuration must be left exactly as it was")
                .isSameAs(applicationPool);
    }

    /**
     * Confirms an unrelated DataSource bean in the context does not trigger the shared-pool refusal.
     *
     * <p>Assumptions: the guard compares by INSTANCE, not by type. Boot's migration DataSource is
     * derived from the application's and is an ordinary DataSource of an ordinary type, so a guard that
     * tested the type would refuse every correctly configured service.</p>
     */
    @Test
    @DisplayName("a different DataSource bean in the context is not the application's own")
    void acceptsADataSourceDistinctFromTheApplicationBeans() {
        RecordingDataSource applicationPool = new RecordingDataSource(null);
        RecordingDataSource migrationDataSource = new RecordingDataSource(null);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(migrationDataSource);

        new FlywayOwnerRoleDataSourceCustomizer(OWNER_ROLE, providerOf(applicationPool))
                .customize(configuration);

        assertThat(configuration.getDataSource()).isNotSameAs(migrationDataSource);
    }

    /**
     * Confirms a configuration carrying no DataSource is refused with the role and property named.
     *
     * <p>Assumptions: Spring Boot asserts a migration DataSource exists before customizers run, so this
     * state is unreachable through Boot. It is asserted anyway because the customizer is a public type
     * a service could apply itself, and silently doing nothing there would be a control that fails
     * open.</p>
     */
    @Test
    @DisplayName("a configuration with no DataSource is refused")
    void refusesAConfigurationWithoutADataSource() {
        FluentConfiguration configuration = new FluentConfiguration();

        assertThatThrownBy(() -> customizerFor(OWNER_ROLE).customize(configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OWNER_ROLE)
                .hasMessageContaining(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY);
    }

    /**
     * Confirms an empty configured value leaves the configuration untouched.
     *
     * <p>Assumptions: this is the documented opt-out the authorization context's test profile uses, and
     * the assertion is that the DataSource is the SAME instance -- an inert customizer that wrapped
     * with a no-op wrapper would still cost a round trip and would still hide a misconfiguration.</p>
     */
    @Test
    @DisplayName("an empty configured value is a documented opt-out and replaces nothing")
    void isInertWhenNoRoleIsConfigured() {
        for (String blank : Arrays.asList(null, "", "   ")) {
            RecordingDataSource configured = new RecordingDataSource(null);
            FluentConfiguration configuration = new FluentConfiguration().dataSource(configured);
            FlywayOwnerRoleDataSourceCustomizer customizer = customizerFor(blank);

            customizer.customize(configuration);

            assertThat(configuration.getDataSource()).isSameAs(configured);
            assertThat(customizer.ownerRole()).isNull();
            assertThat(customizer.setRoleStatement()).isNull();
        }
    }

    /**
     * Confirms only a lower-case identifier is accepted, because the value reaches SQL unbound.
     *
     * <p>Assumptions: the rejected set is asserted through this class as well as through the callback
     * because both now read the same allow-list from {@code MigrationOwnerRole}, and a test that
     * covered only one consumer would not notice the other losing the check.</p>
     */
    @Test
    @DisplayName("only a lower-case identifier is accepted, because the value reaches SQL unbound")
    void refusesEveryNameOutsideTheAllowList() {
        List<String> rejected =
                Arrays.asList(
                        "carddemo_auth_owner; DROP SCHEMA auth CASCADE",
                        "carddemo\"auth\"owner",
                        "Carddemo_Auth_Owner",
                        "carddemo auth owner",
                        "1carddemo_auth_owner",
                        "carddemo-auth-owner",
                        "a".repeat(64));

        for (String name : rejected) {
            assertThatThrownBy(() -> customizerFor(name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY);
        }

        assertThat(customizerFor("a".repeat(63)).ownerRole()).isEqualTo("a".repeat(63));
        assertThat(customizerFor("  " + OWNER_ROLE + "  ").ownerRole()).isEqualTo(OWNER_ROLE);
    }

    /**
     * Confirms the wrapper answers the JDBC wrapper protocol without hiding its delegate.
     *
     * <p>Assumptions: Flyway and Boot both call {@code unwrap} on a DataSource to reach vendor
     * extensions, so a wrapper that refused would break a path neither of them reports clearly.</p>
     *
     * @throws SQLException if the wrapper protocol calls raise, which this case does not expect and
     *     which JUnit reports as a failure
     */
    @Test
    @DisplayName("the wrapper unwraps to itself and defers everything else to the delegate")
    void answersTheWrapperProtocol() throws SQLException {
        RecordingDataSource configured = new RecordingDataSource(null);
        FluentConfiguration configuration = new FluentConfiguration().dataSource(configured);
        customizerFor(OWNER_ROLE).customize(configuration);
        DataSource wrapper = configuration.getDataSource();

        assertThat(wrapper.isWrapperFor(DataSource.class)).isTrue();
        assertThat(wrapper.unwrap(DataSource.class)).isSameAs(wrapper);
        assertThat(wrapper.isWrapperFor(RecordingDataSource.class)).isTrue();
        assertThat(wrapper.unwrap(RecordingDataSource.class)).isSameAs(configured);
        assertThat(wrapper.getLoginTimeout()).isEqualTo(RecordingDataSource.LOGIN_TIMEOUT);
    }

    /**
     * Confirms the customizer is registered exactly when a service names an owner role.
     *
     * <p>Assumptions: presence of the key is the condition, not a particular value, so the empty-value
     * case must still yield a bean -- that is what lets a profile opt out by overriding a key its base
     * document declares, which a YAML overlay cannot remove.</p>
     */
    @Test
    @DisplayName("the customizer is registered exactly when a service names an owner role")
    void isRegisteredOnlyWhenThePropertyIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(FlywayOwnerRoleDataSourceCustomizer.class));

        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .withPropertyValues(
                        FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + "=" + OWNER_ROLE)
                .run(context -> {
                    assertThat(context).hasSingleBean(FlywayOwnerRoleDataSourceCustomizer.class);
                    assertThat(
                                    context.getBean(FlywayOwnerRoleDataSourceCustomizer.class)
                                            .setRoleStatement())
                            .isEqualTo(EXPECTED_SQL);
                });

        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .withPropertyValues(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + "=")
                .run(context -> {
                    assertThat(context).hasSingleBean(FlywayOwnerRoleDataSourceCustomizer.class);
                    assertThat(context.getBean(FlywayOwnerRoleDataSourceCustomizer.class).ownerRole())
                            .isNull();
                });
    }

    /**
     * Builds a customizer over an empty set of application DataSource beans.
     *
     * @param configuredOwnerRole the value the customizer is configured with
     * @return the customizer, never {@code null}
     */
    private static FlywayOwnerRoleDataSourceCustomizer customizerFor(String configuredOwnerRole) {
        return new FlywayOwnerRoleDataSourceCustomizer(configuredOwnerRole, providerOf());
    }

    /**
     * Wraps a fixed set of DataSources in the provider shape the customizer consumes.
     *
     * <p>Assumptions: every method of {@link ObjectProvider} carries a default implementation, and the
     * customizer iterates the provider, so overriding {@code iterator} is the whole of what a stub has
     * to supply. {@code stream} is overridden alongside it so the two cannot disagree if the
     * customizer's traversal changes.
     *
     * @param dataSources the beans the provider reports
     * @return a provider over exactly those beans, never {@code null}
     */
    private static ObjectProvider<DataSource> providerOf(DataSource... dataSources) {
        List<DataSource> beans = Arrays.asList(dataSources);
        return new ObjectProvider<>() {
            @Override
            public Iterator<DataSource> iterator() {
                return beans.iterator();
            }

            @Override
            public Stream<DataSource> stream() {
                return beans.stream();
            }
        };
    }

    /**
     * A DataSource stub that records the SQL issued on the connections it hands out, counts what is
     * closed, and optionally rejects every execution.
     *
     * <p>Assumptions: {@link DataSource} is implemented rather than proxied because the wrapper
     * delegates all eight of its methods and the case asserting that needs real answers, not type
     * defaults. {@link Connection} and {@link Statement} are proxied for the opposite reason.
     */
    private static final class RecordingDataSource implements DataSource {

        /** The login timeout this stub reports, so a delegating call can be told from a default. */
        private static final int LOGIN_TIMEOUT = 17;

        /** The SQL each execution received, in the order it was issued. */
        private final List<String> executed = new ArrayList<>();

        /** The failure every execution raises, or {@code null} when executions succeed. */
        private final SQLException rejection;

        /** How many statements were closed, which is how the try-with-resources is observed. */
        private int statementsClosed;

        /** How many connections were closed, which is how the fail-closed path is observed. */
        private int connectionsClosed;

        /** How many connections were asked for by credential rather than by the no-argument call. */
        private int credentialedRequests;

        /**
         * Creates a stub that either records executions or rejects them.
         *
         * @param rejection the failure every execution raises, or {@code null} to record instead
         */
        private RecordingDataSource(SQLException rejection) {
            this.rejection = rejection;
        }

        /**
         * Hands out a recording connection.
         *
         * @return the proxied connection, never {@code null}
         */
        @Override
        public Connection getConnection() {
            return connection();
        }

        /**
         * Hands out a recording connection and notes that a credential was supplied.
         *
         * @param username the credential asked for; recorded only as a count, because the stub opens
         *     nothing
         * @param password that credential's password; not read
         * @return the proxied connection, never {@code null}
         */
        @Override
        public Connection getConnection(String username, String password) {
            credentialedRequests++;
            return connection();
        }

        /**
         * Returns no log writer, which is what a driver reports when logging is disabled.
         *
         * @return always {@code null}
         */
        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        /**
         * Accepts and discards a log writer.
         *
         * @param out the writer offered; not retained, because no case reads it back
         */
        @Override
        public void setLogWriter(PrintWriter out) {
            // Assumptions: discarded deliberately. The delegating call is asserted through
            //   getLoginTimeout, which returns a distinctive value; a second observable setter would
            //   assert the same delegation twice.
        }

        /**
         * Accepts and discards a login timeout.
         *
         * @param seconds the timeout offered; not retained, so {@link #getLoginTimeout()} keeps
         *     reporting the distinctive constant a delegating call is recognised by
         */
        @Override
        public void setLoginTimeout(int seconds) {
            // Assumptions: discarded for the reason given on the setter's parameter -- the constant is
            //   what makes a delegated read distinguishable from a type default.
        }

        /**
         * Reports the distinctive login timeout.
         *
         * @return {@link #LOGIN_TIMEOUT}
         */
        @Override
        public int getLoginTimeout() {
            return LOGIN_TIMEOUT;
        }

        /**
         * Reports no parent logger, which is what a driver that does not use {@code java.util.logging}
         * reports.
         *
         * @return always {@code null}
         */
        @Override
        public Logger getParentLogger() {
            return null;
        }

        /**
         * Unwraps to this stub when asked for a type it satisfies.
         *
         * @param <T> the interface asked for
         * @param iface the interface asked for
         * @return this stub when it satisfies {@code iface}
         * @throws SQLException if it does not, which is what a driver does
         */
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("not a wrapper for " + iface.getName());
        }

        /**
         * Reports whether this stub satisfies the given type.
         *
         * @param iface the interface asked about
         * @return {@code true} when this stub is an instance of it
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        /**
         * Returns a connection whose {@code createStatement} yields a recording statement and whose
         * {@code close} is counted.
         *
         * @return the proxied connection, never {@code null}
         */
        private Connection connection() {
            InvocationHandler handler = (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "createStatement" -> {
                        return statement();
                    }
                    case "close" -> {
                        connectionsClosed++;
                        return null;
                    }
                    default -> {
                        return defaultValue(method);
                    }
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    handler);
        }

        /**
         * Returns a statement that records or rejects each execution and counts its own close.
         *
         * @return the proxied statement, never {@code null}
         */
        private Statement statement() {
            InvocationHandler handler = (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "execute" -> {
                        if (rejection != null) {
                            throw rejection;
                        }
                        executed.add((String) arguments[0]);
                        return Boolean.FALSE;
                    }
                    case "close" -> {
                        statementsClosed++;
                        return null;
                    }
                    default -> {
                        return defaultValue(method);
                    }
                }
            };
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, handler);
        }

        /**
         * Returns the type default for a method this stub does not answer.
         *
         * @param method the invoked method
         * @return {@code false} for a {@code boolean} return, {@code 0} for an {@code int} return and
         *     {@code null} otherwise
         */
        private static Object defaultValue(Method method) {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == int.class) {
                return Integer.valueOf(0);
            }
            return null;
        }
    }
}
