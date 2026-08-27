package com.carddemo.common.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * Makes every connection Flyway uses to create an object assume the schema's NOLOGIN owner role, so
 * that the objects a migration creates belong to that role rather than to the credential that
 * connected.
 *
 * <h2>Purpose</h2>
 *
 * <p>Each bounded context migrates as its own {@code carddemo_<context>_migrator} credential, which
 * {@code data-migration/sql/V0__schemas_and_roles.sql} grants the matching
 * {@code carddemo_<context>_owner} role {@code WITH INHERIT FALSE}. A session authenticated as the
 * migrator therefore holds no privilege at all until it assumes the owner explicitly, and every
 * {@code ALTER DEFAULT PRIVILEGES FOR ROLE carddemo_<context>_owner} clause in that bootstrap is
 * keyed on the role that CREATES an object. Without the assumption, a migration either fails for want
 * of privilege or -- worse -- succeeds with the migrator as owner, leaving all those default-privilege
 * clauses inert and the runtime role holding no grant on the tables it must read.
 *
 * <h2>Which connections this covers, and why two events rather than one</h2>
 *
 * <p>Assumptions: Flyway opens more than one session per migration run and dispatches each callback
 * event on a specific one. Measured against Flyway 13.0.0 by reading
 * {@code DefaultCallbackExecutor}: {@code onEvent} -- which carries {@link Event#AFTER_CONNECT} --
 * executes on {@code Database.getMainConnection()}, while {@code onEachMigrateOrUndoEvent} -- which
 * carries {@link Event#BEFORE_EACH_MIGRATE} -- executes on {@code Database.getMigrationConnection()}.
 * Those two are the same session only when {@code useSingleConnection()} holds, and
 * {@code PostgreSQLDatabase} returns that as the negation of {@code isTransactionalLock()}, which
 * defaults on. So under PostgreSQL the schema and the schema-history table are created on the main
 * connection while a migration's own DDL runs on a second, and a callback that handled
 * {@code AFTER_CONNECT} alone would leave every table owned by the migrator.
 *
 * <p>Trade-offs: handling two events means the statement is issued more than once per run -- once for
 * the main connection and once per applied migration. That is accepted because {@code SET ROLE} is
 * idempotent within a session and costs one round trip, whereas the alternative of picking a single
 * event requires knowing which connection each object is created on, which is a Flyway internal that
 * has already changed once and is not part of its published contract.
 *
 * <p>Alternatives Considered: {@link Event#BEFORE_MIGRATE}, which reads as the natural "before any
 * migration" hook. Rejected on the same reading of {@code DefaultCallbackExecutor}: that event is
 * dispatched through {@code onMigrateOrUndoEvent}, which opens a throwaway event connection, so a
 * role assumed there is discarded before the first DDL statement runs.
 *
 * <h2>Why this callback is not sufficient on its own</h2>
 *
 * <p>Assumptions: a callback cannot be the whole mechanism, and this is the least obvious fact about
 * the arrangement. Measured against Flyway 13.0.0: {@code PostgreSQLConnection}'s constructor records
 * {@code SELECT CURRENT_USER} in a final {@code originalRole} field, this callback's earliest event is
 * dispatched only after that wrapper exists, and {@code JdbcTableSchemaHistory}'s {@code exists},
 * {@code lock}, {@code update}, {@code delete} and {@code doAddAppliedMigration} each call
 * {@code Connection.restoreOriginalState()}, which under PostgreSQL issues
 * {@code SET ROLE <originalRole>}. A role assumed by this callback is therefore reverted before
 * {@code CREATE TABLE flyway_schema_history} runs, which failed the account context with
 * {@code SQLSTATE 42501, permission denied for schema account}.
 *
 * <p>{@link FlywayOwnerRoleDataSourceCustomizer} is what makes the ownership hold: it assumes the role
 * on the JDBC connection BEFORE Flyway wraps it, exactly where the deprecated {@code initSql} ran, so
 * {@code originalRole} is captured as the owner and the engine's own restores keep it.
 *
 * <p>Trade-offs: with that customizer in place this callback's statement is idempotent and
 * effect-free, costing one round trip on connect and one per applied migration. It is kept rather than
 * deleted because it re-asserts the role on the exact connection each migration is about to run on, so
 * ownership does not rest on a single internal behaviour of the engine's connection wrapper -- the
 * behaviour that has already been observed to defeat one design of this control.
 *
 * <h2>Why this replaces {@code spring.flyway.init-sqls}</h2>
 *
 * <p>Refactoring Rationale: the seven migrating services each carried
 * {@code spring.flyway.init-sqls: ["SET ROLE carddemo_<context>_owner;"]}, which Spring Boot maps to
 * Flyway's {@code initSql}. Flyway 13 deprecated that setting and names this callback event as its
 * replacement, and because the connection initialiser that runs {@code initSql} also prints the
 * deprecation notice on EVERY connection it opens, one reactor build emitted seventy such notices.
 * The behaviour being preserved is exactly the one described above: the role is assumed on the
 * connections that create objects, so ownership and the default-privilege clauses keyed on it are
 * unaffected by the move.
 *
 * <p>Alternatives Considered: {@code ALTER ROLE carddemo_<context>_migrator SET role}, a server-side
 * per-role default that would need nothing in application configuration at all. Rejected for the
 * reason each service's configuration already recorded before this class existed -- it makes a
 * privilege decision invisible at the point it takes effect, so a reader of the configuration sees a
 * migration running as the migrator with no way to learn that the server substitutes another
 * identity.
 *
 * <h2>Why the configured value is validated rather than interpolated</h2>
 *
 * <p>Refactoring Rationale: the allow-list, the identifier quoting and their rationale were fields and
 * comments of this class while it was the only consumer of the configured value. They now live in
 * {@link MigrationOwnerRole}, because the customizer above needs the same value in the same two forms
 * and a security allow-list restated in two classes is one that drifts. The reasoning is unchanged and
 * is recorded there in full.
 *
 * @see MigrationOwnerRole
 * @see FlywayOwnerRoleDataSourceCustomizer
 * @see com.carddemo.common.CardDemoCommonAutoConfiguration
 */
public final class FlywayOwnerRoleCallback implements Callback {

    /**
     * The property whose value this callback assumes, read by the auto-configuration that registers
     * it and stated here so a diagnostic can name the key a reader has to edit.
     */
    public static final String OWNER_ROLE_PROPERTY = "carddemo.database.flyway.owner-role";

    /**
     * The name Flyway orders this callback by.
     *
     * <p>Assumptions: Flyway sorts the callbacks of one run by this value --
     * {@code DefaultCallbackExecutor}'s constructor sorts on {@code getCallbackName()} with natural
     * ordering -- so the name is part of the contract rather than a label. It is stated as a constant
     * so that a test can assert it and a reader adding a second callback can see what it will be
     * ordered against.
     */
    public static final String CALLBACK_NAME = "carddemo-flyway-owner-role";

    /**
     * The validated owner role, inert when the configured value was blank.
     *
     * <p>Assumptions: held as the validated value type rather than as two strings, so no code path can
     * reach {@link #handle} with an identifier that has not passed the allow-list.
     */
    private final MigrationOwnerRole ownerRole;

    /**
     * Creates a callback that assumes the given role, or an inert callback when the value is blank.
     *
     * <p>Assumptions: a blank value is an explicit opt-out rather than a mistake, and it is what a
     * profile uses when it deliberately runs no ownership split -- the authorization context's test
     * profile is the one such case, where the role belongs to a bootstrap no throwaway container has
     * run. An absent PROPERTY is a different thing and never reaches this constructor: the
     * auto-configuration that registers this bean is conditional on the key being present, so a
     * service that names no owner role gets no callback at all.
     *
     * <p>Alternatives Considered: rejecting blank as invalid and expressing the opt-out by removing
     * the property in the overriding profile. Rejected because a YAML overlay can override a value
     * but cannot unset a key its base document declares, so with that rule the one profile that needs
     * to opt out could not.
     *
     * @param configuredOwnerRole the value of {@value #OWNER_ROLE_PROPERTY}; may be {@code null} or
     *     blank, either of which produces an inert callback
     * @throws IllegalArgumentException if a non-blank value is not a lower-case identifier accepted by
     *     {@link MigrationOwnerRole}, which fails the application context at assembly rather than
     *     letting an unchecked identifier reach a privileged statement
     */
    public FlywayOwnerRoleCallback(String configuredOwnerRole) {
        this.ownerRole = MigrationOwnerRole.of(configuredOwnerRole, OWNER_ROLE_PROPERTY);
    }

    /**
     * Reports whether this callback acts on the given event.
     *
     * @param event the event Flyway is dispatching
     * @param context the connection and configuration the event is dispatched against; not read,
     *     because the decision depends on the event and on the configured role alone
     * @return {@code true} for {@link Event#AFTER_CONNECT} and {@link Event#BEFORE_EACH_MIGRATE} when
     *     a role is configured; {@code false} otherwise, including for every event when the
     *     configured value was blank
     */
    @Override
    public boolean supports(Event event, Context context) {
        if (ownerRole.isInert()) {
            return false;
        }
        return event == Event.AFTER_CONNECT || event == Event.BEFORE_EACH_MIGRATE;
    }

    /**
     * Reports that the statement may be issued inside a transaction Flyway has already opened.
     *
     * <p>Assumptions: this has to be {@code true} for the migration-connection half to work at all.
     * {@link Event#BEFORE_EACH_MIGRATE} fires inside the transaction that wraps the migration whose
     * DDL follows it, and answering {@code false} would make Flyway commit that transaction before
     * running the callback, which would place the role assumption outside the unit of work it exists
     * to affect.
     *
     * <p>Assumptions: {@code SET ROLE} is transaction-aware in PostgreSQL -- a rollback restores the
     * previous role -- so running inside the migration's transaction also means a failed migration
     * leaves the session as it found it.
     *
     * @param event the event Flyway is dispatching; not read, because the answer is the same for both
     *     supported events
     * @param context the connection and configuration the event is dispatched against; not read
     * @return always {@code true}
     */
    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    /**
     * Issues the {@code SET ROLE} statement on the connection Flyway dispatched the event against.
     *
     * @param event the event Flyway is dispatching; not read, because both supported events call for
     *     the same statement on whichever connection carries them
     * @param context the connection the statement is issued on, together with the run's configuration
     * @throws FlywayException if the statement fails -- most often because the role does not exist or
     *     the connected credential has not been granted it. It is wrapped in Flyway's own exception
     *     type so the migration stops with the failed statement named, rather than the run continuing
     *     and creating objects under the wrong owner.
     */
    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute(ownerRole.setRoleStatement());
        } catch (SQLException failure) {
            throw new FlywayException(
                    "Flyway could not assume the schema owner role '" + ownerRole.name()
                            + "' configured by "
                            + OWNER_ROLE_PROPERTY + " on event " + event.getId()
                            + "; migrated objects would be owned by the connecting credential and"
                            + " every ALTER DEFAULT PRIVILEGES clause keyed on the owner would be"
                            + " inert, so the migration is stopped instead",
                    failure);
        }
    }

    /**
     * Returns the name Flyway logs and orders this callback by.
     *
     * @return {@value #CALLBACK_NAME}, never {@code null}
     */
    @Override
    public String getCallbackName() {
        return CALLBACK_NAME;
    }

    /**
     * Returns the validated role name this callback assumes, for diagnostics and tests.
     *
     * @return the role name, or {@code null} when the configured value was blank and this callback is
     *     inert
     */
    public String ownerRole() {
        return ownerRole.name();
    }

    /**
     * Returns the statement this callback issues, for diagnostics and tests.
     *
     * <p>Assumptions: exposed so a test can assert the quoting rather than infer it from a database's
     * behaviour, which is the only other way to see it.
     *
     * @return the {@code SET ROLE} statement with the role quoted as an identifier, or {@code null}
     *     when this callback is inert
     */
    public String setRoleStatement() {
        return ownerRole.setRoleStatement();
    }
}
