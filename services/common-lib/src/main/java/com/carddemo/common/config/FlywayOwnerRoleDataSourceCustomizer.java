package com.carddemo.common.config;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

/**
 * Makes every JDBC connection Flyway borrows assume the schema's NOLOGIN owner role before Flyway
 * takes hold of it, so that every object a migration creates -- including Flyway's own schema-history
 * table -- is owned by that role rather than by the credential that connected.
 *
 * <h2>Purpose</h2>
 *
 * <p>Each bounded context migrates as its own {@code carddemo_<context>_migrator} credential, which
 * {@code data-migration/sql/V0__schemas_and_roles.sql} grants the matching
 * {@code carddemo_<context>_owner} role {@code WITH INHERIT FALSE}. A session authenticated as the
 * migrator therefore holds no privilege on the schema at all until it assumes the owner explicitly,
 * and every {@code ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_<context>_owner} clause in that
 * bootstrap is keyed on the role that CREATES an object. Without the assumption a migration either
 * fails for want of privilege or -- worse -- succeeds with the migrator as owner, leaving all those
 * default-privilege clauses inert and the runtime role holding no grant on the tables it must read.
 *
 * <h2>Why the role has to be assumed here rather than in a Flyway callback</h2>
 *
 * <p>Refactoring Rationale: the seven migrating services each carried
 * {@code spring.flyway.init-sqls: ["SET ROLE carddemo_<context>_owner;"]}, which Spring Boot maps to
 * Flyway's {@code initSql}. Flyway 13 deprecated that setting -- the engine printed a removal notice
 * on every connection it opened, seventy of them in one reactor build -- and names a connect-time
 * {@code Callback} as its replacement. {@link FlywayOwnerRoleCallback} was written first and is still
 * registered, but measured against Flyway 13.0.0 a callback alone cannot preserve ownership, for a
 * reason visible only in the engine's internals: {@code PostgreSQLConnection}'s constructor records
 * {@code SELECT CURRENT_USER} in a final {@code originalRole} field, {@code AFTER_CONNECT} is
 * dispatched only AFTER that wrapper exists, and {@code JdbcTableSchemaHistory}'s {@code exists},
 * {@code lock}, {@code update}, {@code delete} and {@code doAddAppliedMigration} each call
 * {@code Connection.restoreOriginalState()}, whose PostgreSQL implementation issues
 * {@code SET ROLE <originalRole>}. A callback-assumed role is therefore reverted to the migrator
 * before {@code CREATE TABLE flyway_schema_history} runs, which failed the account context with
 * {@code SQLSTATE 42501, permission denied for schema account}.
 *
 * <p>Refactoring Rationale: the deprecated setting worked because of WHERE it ran, not what it said.
 * {@code initSql} is executed by {@code JdbcConnectionFactory}'s connection initialiser, on the raw
 * JDBC connection BEFORE Flyway wraps it, so {@code originalRole} was captured as the OWNER and every
 * later restore was a no-op. This customizer reproduces exactly that position by replacing the
 * DataSource Flyway was configured with, so the assumption happens on connection hand-out and the
 * engine's own restore logic keeps the owner rather than undoing it.
 *
 * <p>Alternatives Considered: {@code ALTER ROLE carddemo_<context>_migrator SET role}, a server-side
 * per-role default that would need nothing in application configuration at all, and which the engine
 * could not revert. Rejected for the reason each service's configuration recorded before either
 * mechanism existed -- it makes a privilege decision invisible at the point it takes effect, so a
 * reader of the configuration sees a migration running as the migrator with no way to learn that the
 * server substitutes another identity. It would also move a runtime control into
 * {@code V0__schemas_and_roles.sql}, whose authority is role definition rather than session
 * behaviour.
 *
 * <p>Alternatives Considered: {@code spring.flyway.jdbc-properties} carrying the PostgreSQL
 * {@code options=-c role=<owner>} connection parameter, which the driver applies at connection
 * establishment and would therefore also precede the wrapper. Rejected because Flyway honours
 * {@code jdbcProperties} only when it builds the connection from a URL itself, and Boot hands it a
 * DataSource; adopting it would mean giving every service a second, separate Flyway URL and password,
 * duplicating the datasource contract this project deliberately keeps in one place.
 *
 * <h2>Why a shared application pool is refused rather than wrapped</h2>
 *
 * <p>Assumptions: Boot's {@code FlywayAutoConfiguration} hands Flyway a DEDICATED, unpooled
 * {@code SimpleDriverDataSource} whenever {@code spring.flyway.url} or {@code spring.flyway.user} is
 * set -- read from {@code getMigrationDataSource}, which builds one from the URL, otherwise derives
 * one from the application DataSource when a Flyway username is present, and only otherwise returns
 * the application's own pooled DataSource. All seven migrating services set
 * {@code spring.flyway.user}, so the dedicated branch is the one this project takes, and a connection
 * this customizer elevates is closed rather than returned to a pool the application borrows from.
 *
 * <p>Trade-offs: in the remaining branch the elevated connection WOULD go back into the application's
 * pool, where a request-serving thread could borrow it still holding the owner role -- a privilege
 * escalation reachable by deleting one property. That configuration is refused with a message naming
 * the property to set, rather than wrapped. Alternatives Considered: proxying the connection to issue
 * {@code RESET ROLE} on close, which would make the shared-pool case safe and require no
 * configuration. Rejected because the reset is itself a statement that can fail -- on a broken
 * connection, or inside an aborted transaction -- and a failed reset leaves exactly the escalation the
 * proxy was added to prevent, silently. Refusing a configuration that no service uses is the smaller
 * risk than a control that fails open.
 *
 * @see FlywayOwnerRoleCallback
 * @see com.carddemo.common.CardDemoCommonAutoConfiguration
 */
public final class FlywayOwnerRoleDataSourceCustomizer implements FlywayConfigurationCustomizer {

    /** The validated owner role, inert when the configured value was blank. */
    private final MigrationOwnerRole ownerRole;

    /**
     * Every DataSource bean the application context holds, resolved when this customizer runs.
     *
     * <p>Assumptions: held as a provider rather than a resolved value because the guard needs the
     * INSTANCE the application uses, and comparing instances is the only test that distinguishes
     * Boot's derived migration DataSource from the application's own pool -- both are DataSources of
     * ordinary types, and neither carries a marker saying which it is.
     */
    private final ObjectProvider<DataSource> applicationDataSources;

    /**
     * Creates a customizer that assumes the given role on every connection Flyway borrows.
     *
     * @param configuredOwnerRole the value of
     *     {@value FlywayOwnerRoleCallback#OWNER_ROLE_PROPERTY}; may be {@code null} or blank, either
     *     of which produces an inert customizer that leaves the configured DataSource alone
     * @param applicationDataSources the context's DataSource beans, used only to refuse a
     *     configuration in which Flyway would share the application's connection pool; must not be
     *     {@code null}
     * @throws IllegalArgumentException if a non-blank role name is not a lower-case identifier, which
     *     fails the application context at assembly rather than letting an unchecked identifier reach
     *     a privileged statement
     */
    public FlywayOwnerRoleDataSourceCustomizer(
            String configuredOwnerRole, ObjectProvider<DataSource> applicationDataSources) {
        this.ownerRole =
                MigrationOwnerRole.of(
                        configuredOwnerRole, FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY);
        this.applicationDataSources = applicationDataSources;
    }

    /**
     * Replaces the DataSource Flyway was configured with by one that assumes the owner role on every
     * connection it hands out.
     *
     * <p>Assumptions: Spring Boot calls customizers after it has configured the DataSource and before
     * {@code FluentConfiguration.load()} -- read from the ordering inside
     * {@code FlywayAutoConfiguration$FlywayConfiguration.flyway(...)}, which is
     * {@code configureDataSource}, {@code configureProperties}, {@code configureCallbacks},
     * {@code configureJavaMigrations}, the ordered customizers, then {@code load()}. So the DataSource
     * read here is the one the engine will use, and replacing it is effective.
     *
     * @param configuration the configuration Flyway is being built from; its DataSource is read and,
     *     unless this customizer is inert, replaced
     * @throws IllegalStateException if no DataSource has been configured, or if the configured
     *     DataSource is the application's own -- see this class's documentation for why that
     *     configuration is refused instead of wrapped
     */
    @Override
    public void customize(FluentConfiguration configuration) {
        if (ownerRole.isInert()) {
            return;
        }
        DataSource configured = configuration.getDataSource();
        if (configured == null) {
            throw new IllegalStateException(
                    "Flyway has no DataSource to migrate with, so the owner role '"
                            + ownerRole.name() + "' named by "
                            + FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY
                            + " cannot be assumed; migrated objects would be owned by whichever"
                            + " credential Flyway connects with, leaving every ALTER DEFAULT"
                            + " PRIVILEGES clause keyed on the owner inert");
        }
        if (configured instanceof OwnerRoleDataSource) {
            // Assumptions: a customizer may be applied to one configuration more than once -- a
            //   context refreshed twice, or a test that rebuilds the Flyway bean -- and wrapping a
            //   wrapper would issue the same statement twice per connection for no gain. The check is
            //   on the wrapper type rather than a flag on this object, because it is the
            //   configuration that carries the state being guarded, not the customizer.
            return;
        }
        for (DataSource applicationDataSource : applicationDataSources) {
            if (applicationDataSource == configured) {
                throw new IllegalStateException(
                        "Flyway is configured with the application's own DataSource, so assuming the"
                                + " owner role '" + ownerRole.name() + "' named by "
                                + FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY
                                + " would return a connection holding that role to the pool the"
                                + " application serves requests from. Set spring.flyway.user (or"
                                + " spring.flyway.url) so that Spring Boot gives Flyway a dedicated"
                                + " DataSource, or clear "
                                + FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY
                                + " to migrate as the connecting credential");
            }
        }
        configuration.dataSource(new OwnerRoleDataSource(configured, ownerRole));
    }

    /**
     * Returns the validated role name this customizer assumes, for diagnostics and tests.
     *
     * @return the role name, or {@code null} when the configured value was blank and this customizer
     *     is inert
     */
    public String ownerRole() {
        return ownerRole.name();
    }

    /**
     * Returns the statement this customizer issues on each borrowed connection, for diagnostics and
     * tests.
     *
     * <p>Assumptions: exposed so a test can assert the identifier quoting rather than infer it from a
     * database's behaviour, which is the only other way to see it.
     *
     * @return the {@code SET ROLE} statement with the role quoted as an identifier, or {@code null}
     *     when this customizer is inert
     */
    public String setRoleStatement() {
        return ownerRole.setRoleStatement();
    }

    /**
     * A DataSource that assumes the migration owner role on each connection before returning it.
     *
     * <p>Assumptions: this is a hand-off wrapper rather than a pool. It adds one round trip per
     * connection and holds no state, so it is safe to share and needs no shutdown hook; the delegate
     * remains responsible for everything else a DataSource does.
     *
     * <p>Trade-offs: implemented against {@code javax.sql.DataSource} directly rather than extending
     * Spring's {@code AbstractDataSource} or {@code DelegatingDataSource}. Those would remove five
     * trivial delegating methods, at the cost of a compile dependency on {@code spring-jdbc} in a
     * module that deliberately declares no JDBC dependency at all -- the shared kernel owns no schema
     * and no entity, and adding a persistence library so that a wrapper can inherit five methods
     * would widen that boundary for the smallest possible benefit.
     */
    private static final class OwnerRoleDataSource implements DataSource {

        /** The DataSource that actually opens connections. */
        private final DataSource delegate;

        /** The role assumed on each connection handed out. */
        private final MigrationOwnerRole ownerRole;

        /**
         * Wraps a DataSource so that every connection it hands out has assumed the owner role.
         *
         * @param delegate the DataSource that opens the connections; must not be {@code null}
         * @param ownerRole the validated role to assume; must not be inert
         */
        private OwnerRoleDataSource(DataSource delegate, MigrationOwnerRole ownerRole) {
            this.delegate = delegate;
            this.ownerRole = ownerRole;
        }

        /**
         * Opens a connection and assumes the owner role on it.
         *
         * @return a connection that has already assumed the owner role, never {@code null}
         * @throws SQLException if the connection cannot be opened, or if the role cannot be assumed --
         *     in which case the connection is closed first, so no un-elevated connection escapes
         */
        @Override
        public Connection getConnection() throws SQLException {
            return assumeOwnerRole(delegate.getConnection());
        }

        /**
         * Opens a connection for the given credentials and assumes the owner role on it.
         *
         * @param username the credential to connect as
         * @param password that credential's password
         * @return a connection that has already assumed the owner role, never {@code null}
         * @throws SQLException if the connection cannot be opened, or if the role cannot be assumed --
         *     in which case the connection is closed first, so no un-elevated connection escapes
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return assumeOwnerRole(delegate.getConnection(username, password));
        }

        /**
         * Issues the {@code SET ROLE} statement on a freshly opened connection.
         *
         * <p>Assumptions: a failure here closes the connection before propagating. Returning it
         * un-elevated would let the migration proceed as the migrator and create objects under the
         * wrong owner, which is the silent outcome this whole mechanism exists to prevent, so the
         * control fails closed. The close is attempted on a best-effort basis and its own failure is
         * attached as a suppressed exception, because the original refusal is the diagnosis a reader
         * needs and a secondary close failure must not replace it.
         *
         * @param connection the connection just obtained from the delegate
         * @return the same connection, with the owner role assumed
         * @throws SQLException if the statement fails -- most often because the role does not exist or
         *     the connecting credential has not been granted it
         */
        private Connection assumeOwnerRole(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ownerRole.setRoleStatement());
                return connection;
            } catch (SQLException failure) {
                SQLException refusal =
                        new SQLException(
                                "Flyway could not assume the schema owner role '" + ownerRole.name()
                                        + "' configured by "
                                        + FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY
                                        + " on a newly opened connection; migrated objects would be"
                                        + " owned by the connecting credential and every ALTER"
                                        + " DEFAULT PRIVILEGES clause keyed on the owner would be"
                                        + " inert, so the connection is refused instead",
                                failure.getSQLState(),
                                failure);
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    refusal.addSuppressed(closeFailure);
                }
                throw refusal;
            }
        }

        /**
         * Returns the delegate's log writer.
         *
         * @return the log writer, or {@code null} when logging is disabled
         * @throws SQLException if the delegate rejects the call
         */
        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        /**
         * Sets the delegate's log writer.
         *
         * @param out the writer to log to, or {@code null} to disable logging
         * @throws SQLException if the delegate rejects the call
         */
        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        /**
         * Sets the delegate's login timeout.
         *
         * @param seconds the timeout in seconds, or zero for the driver's default
         * @throws SQLException if the delegate rejects the call
         */
        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        /**
         * Returns the delegate's login timeout.
         *
         * @return the timeout in seconds, zero when the driver's default applies
         * @throws SQLException if the delegate rejects the call
         */
        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        /**
         * Returns the delegate's parent logger.
         *
         * @return the logger the delegate reports its messages through
         * @throws SQLFeatureNotSupportedException if the delegate does not use {@code java.util.logging}
         */
        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        /**
         * Unwraps to this wrapper when asked for it, and otherwise defers to the delegate.
         *
         * @param <T> the interface asked for
         * @param iface the interface asked for
         * @return this instance when it is an instance of {@code iface}, otherwise the delegate's
         *     answer
         * @throws SQLException if the delegate cannot satisfy the request
         */
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        /**
         * Reports whether this wrapper or its delegate can be unwrapped to the given interface.
         *
         * @param iface the interface asked about
         * @return {@code true} when this instance or the delegate satisfies {@code iface}
         * @throws SQLException if the delegate cannot answer
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }
}
