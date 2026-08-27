package com.carddemo.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.CardDemoCommonAutoConfiguration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Holds the migration owner-role callback to the contract the deprecated {@code initSql} setting used
 * to carry.
 *
 * <p><b>Purpose.</b> What the removed setting guaranteed was that every connection Flyway opened had
 * assumed the schema's NOLOGIN owner before any object was created, so that objects a migration
 * creates belong to that role and the bootstrap SQL's {@code ALTER DEFAULT PRIVILEGES FOR ROLE}
 * clauses -- which are keyed on the CREATING role -- actually fire. The cases here assert the parts of
 * that guarantee a unit test can see: which events are acted on, that the statement may run inside the
 * migration's own transaction, the exact SQL issued, the refusal when the server rejects it, the
 * allow-list that stands between a configuration value and a privileged statement, and the two
 * registration verdicts. The part a unit test cannot see -- that the resulting owner is the NOLOGIN
 * role in a real cluster -- is asserted by the consuming services' ownership integration tests, which
 * read {@code pg_namespace} and {@code pg_class} after Flyway has run.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A test class is instantiated by JUnit,
 * takes no parameter, returns nothing and raises nothing a caller handles. The inapplicability is
 * declared rather than passed over, because the Explainability rule forbids a docstring that omits
 * parameters or return values and a reader has to tell a declared inapplicability from an oversight.
 *
 * <p>Alternatives Considered: two other ways to supply the JDBC surface. Implementing
 * {@link Connection} and {@link Statement} directly, as this module's other tests implement the AWS
 * client interfaces they stub -- rejected because those two interfaces declare some hundred abstract
 * methods between them and exactly two of them are called here, so the stub would be almost entirely
 * noise. Mocking them with a framework -- rejected because no test in this module uses one, and
 * introducing one for a two-call stub would make this the only class a reader has to know a second
 * testing vocabulary for. A reflective proxy answers both objections: the two calls that matter are
 * named in one handler and every other method answers a type-appropriate default.
 */
@DisplayName("The migration owner-role callback assumes the owning role and nothing else")
class FlywayOwnerRoleCallbackTest {

    /** The role name the accepting cases configure, in the shape the bootstrap SQL generates. */
    private static final String OWNER_ROLE = "carddemo_auth_owner";

    /** The statement the accepting cases expect to reach the driver. */
    private static final String EXPECTED_SQL = "SET ROLE \"carddemo_auth_owner\"";

    /** The auto-configuration exercised by the registration cases. */
    private static final AutoConfigurations SHARED_KERNEL =
            AutoConfigurations.of(CardDemoCommonAutoConfiguration.class);

    /**
     * Confirms the callback acts on exactly the two events whose connections create objects.
     *
     * <p>Assumptions: the closed set is asserted by walking every event rather than by naming the two
     * that are supported, because the defect this guards against is a third event being added and
     * quietly widening where a privileged statement is issued.</p>
     */
    @Test
    @DisplayName("the two events that carry an object-creating connection are acted on, and no others")
    void supportsTheConnectAndPerMigrationEventsOnly() {
        FlywayOwnerRoleCallback callback = new FlywayOwnerRoleCallback(OWNER_ROLE);

        assertThat(callback.supports(Event.AFTER_CONNECT, null))
                .as("AFTER_CONNECT is dispatched on Flyway's main connection, which creates the"
                        + " schema and the schema-history table")
                .isTrue();
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, null))
                .as("BEFORE_EACH_MIGRATE is dispatched on the migration connection, which runs the"
                        + " DDL of each migration")
                .isTrue();

        // WHY : Assumptions: BEFORE_MIGRATE is asserted by name rather than left to the loop below,
        //       because it is the event a reader would reach for first and it is dispatched on a
        //       throwaway event connection, so a role assumed there is discarded before any DDL runs.
        assertThat(callback.supports(Event.BEFORE_MIGRATE, null)).isFalse();

        for (Event event : Event.values()) {
            if (event == Event.AFTER_CONNECT || event == Event.BEFORE_EACH_MIGRATE) {
                continue;
            }
            assertThat(callback.supports(event, null))
                    .as("%s is not an event this callback acts on", event.getId())
                    .isFalse();
        }
    }

    /**
     * Confirms the statement is allowed to run inside a transaction Flyway has already opened.
     */
    @Test
    @DisplayName("the statement may run inside a transaction Flyway has already opened")
    void handlesInTransaction() {
        FlywayOwnerRoleCallback callback = new FlywayOwnerRoleCallback(OWNER_ROLE);

        // WHY : Assumptions: answering false here would make Flyway commit the migration's
        //       transaction before running the callback, which would place the role assumption
        //       outside the unit of work whose DDL it exists to affect.
        assertThat(callback.canHandleInTransaction(Event.AFTER_CONNECT, null)).isTrue();
        assertThat(callback.canHandleInTransaction(Event.BEFORE_EACH_MIGRATE, null)).isTrue();
    }

    /**
     * Confirms the ordering name Flyway sorts callbacks by is the published constant.
     */
    @Test
    @DisplayName("the callback's ordering name is the published constant")
    void publishesItsOrderingName() {
        // WHY : Assumptions: Flyway sorts the callbacks of one run by this value, so the name is part
        //       of the contract rather than a label, and renaming it would silently reorder this
        //       callback against any other callback a service declares.
        assertThat(new FlywayOwnerRoleCallback(OWNER_ROLE).getCallbackName())
                .isEqualTo("carddemo-flyway-owner-role")
                .isEqualTo(FlywayOwnerRoleCallback.CALLBACK_NAME);
    }

    /**
     * Confirms the exact SQL reaching the driver, on both supported events, with the role quoted.
     *
     * <p>Assumptions: the statement text is asserted rather than the effect, because the effect --
     * which role owns a created table -- is only observable against a real cluster and is asserted
     * there by the consuming services' ownership integration tests.</p>
     */
    @Test
    @DisplayName("the role is assumed with its name quoted as an identifier, on each supported event")
    void issuesTheQuotedSetRoleStatement() {
        FlywayOwnerRoleCallback callback = new FlywayOwnerRoleCallback(OWNER_ROLE);
        RecordingJdbc jdbc = new RecordingJdbc(null);
        Context context = contextOver(jdbc);

        callback.handle(Event.AFTER_CONNECT, context);
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);

        assertThat(jdbc.executed)
                .as("both connections receive the same statement, which is what the removed"
                        + " per-connection initSql produced")
                .containsExactly(EXPECTED_SQL, EXPECTED_SQL);
        assertThat(callback.setRoleStatement()).isEqualTo(EXPECTED_SQL);
        assertThat(callback.ownerRole()).isEqualTo(OWNER_ROLE);
        assertThat(jdbc.closed)
                .as("each statement is closed, so a long migration run leaks no server-side handle")
                .isEqualTo(2);
    }

    /**
     * Confirms a rejected {@code SET ROLE} stops the run with the role, the property and the event
     * named, and the driver's failure kept as the cause.
     */
    @Test
    @DisplayName("a server rejection stops the migration and names the role and the property")
    void wrapsAFailureAsAFlywayException() {
        FlywayOwnerRoleCallback callback = new FlywayOwnerRoleCallback(OWNER_ROLE);
        SQLException rejection = new SQLException("role \"carddemo_auth_owner\" does not exist");
        Context context = contextOver(new RecordingJdbc(rejection));

        assertThatThrownBy(() -> callback.handle(Event.AFTER_CONNECT, context))
                .as("a migration that cannot assume the owner must stop, because continuing would"
                        + " create every object under the connecting credential instead")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(OWNER_ROLE)
                .hasMessageContaining(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY)
                .hasMessageContaining(Event.AFTER_CONNECT.getId())
                .hasCause(rejection);
    }

    /**
     * Confirms the allow-list refuses every shape that would turn a configured value into injected
     * SQL, and accepts the two shapes a deployment legitimately produces.
     */
    @Test
    @DisplayName("only a lower-case identifier is accepted, because the value reaches SQL unbound")
    void refusesEveryNameOutsideTheAllowList() {
        // WHY : Assumptions: each rejected form is a different way the same seam would open --
        //       terminating the statement, commenting out what follows, breaking out of the quoted
        //       identifier, or naming a role the server would silently truncate -- so they are listed
        //       rather than represented by one of them.
        List<String> refused = List.of(
                "carddemo_auth_owner; DROP TABLE users",
                "carddemo_auth_owner--",
                "carddemo\"_auth_owner",
                "carddemo auth owner",
                "carddemo-auth-owner",
                "Carddemo_Auth_Owner",
                "0carddemo_auth_owner",
                "_carddemo_auth_owner",
                "pg_catalog.carddemo_auth_owner",
                "a".repeat(64));
        for (String name : refused) {
            assertThatThrownBy(() -> new FlywayOwnerRoleCallback(name))
                    .as("'%s' must be refused at assembly rather than concatenated into SET ROLE",
                            name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY);
        }

        assertThat(new FlywayOwnerRoleCallback("a".repeat(63)).ownerRole())
                .as("63 characters is PostgreSQL's identifier limit at the default NAMEDATALEN and is"
                        + " accepted")
                .isEqualTo("a".repeat(63));
        assertThat(new FlywayOwnerRoleCallback("  " + OWNER_ROLE + "  ").ownerRole())
                .as("surrounding whitespace from a YAML value is trimmed rather than refused")
                .isEqualTo(OWNER_ROLE);
    }

    /**
     * Confirms a blank configured value yields a callback that acts on nothing.
     */
    @Test
    @DisplayName("an empty configured value is a documented opt-out and acts on nothing")
    void isInertWhenNoRoleIsConfigured() {
        // WHY : Assumptions: the authorization context's test profile is the one caller of this
        //       branch. It deliberately runs no ownership split, because the role belongs to a
        //       bootstrap no throwaway container has run, and a YAML overlay can override a value but
        //       cannot unset a key its base document declares.
        for (String blank : new String[] {null, "", "   "}) {
            FlywayOwnerRoleCallback callback = new FlywayOwnerRoleCallback(blank);
            assertThat(callback.ownerRole()).isNull();
            assertThat(callback.setRoleStatement()).isNull();
            for (Event event : Event.values()) {
                assertThat(callback.supports(event, null))
                        .as("an inert callback acts on no event, including %s", event.getId())
                        .isFalse();
            }
        }
    }

    /**
     * Confirms the four registration verdicts: absent key registers nothing, a valid name registers a
     * callback that issues the expected statement, an emptied key registers an inert callback, and an
     * unacceptable name fails the context.
     *
     * <p>Assumptions: exercised through the auto-configuration rather than by direct construction,
     * because the conditions are the half of this mechanism a service depends on and a direct
     * construction cannot see them.</p>
     */
    @Test
    @DisplayName("the callback is registered exactly when a service names an owner role")
    void isRegisteredOnlyWhenThePropertyIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .run(context -> assertThat(context)
                        .as("a service naming no owner role receives no callback, which is what lets"
                                + " the reporting context -- which runs no migration -- stay unaffected")
                        .doesNotHaveBean(FlywayOwnerRoleCallback.class));

        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .withPropertyValues(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + "=" + OWNER_ROLE)
                .run(context -> {
                    assertThat(context).hasSingleBean(FlywayOwnerRoleCallback.class);
                    assertThat(context.getBean(FlywayOwnerRoleCallback.class).setRoleStatement())
                            .isEqualTo(EXPECTED_SQL);
                });

        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .withPropertyValues(FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + "=")
                .run(context -> {
                    assertThat(context).hasSingleBean(FlywayOwnerRoleCallback.class);
                    assertThat(context.getBean(FlywayOwnerRoleCallback.class).ownerRole())
                            .as("an emptied key registers an inert callback rather than none, so the"
                                    + " opt-out is visible in the context rather than absent from it")
                            .isNull();
                });

        new ApplicationContextRunner()
                .withConfiguration(SHARED_KERNEL)
                .withPropertyValues(
                        FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + "=Carddemo_Auth_Owner")
                .run(context -> assertThat(context)
                        .as("an unacceptable name fails the context at assembly, before any"
                                + " migration runs")
                        .hasFailed());
    }

    /**
     * Wraps a JDBC stub in the callback {@link Context} Flyway would pass.
     *
     * <p>Assumptions: only {@link Context#getConnection()} is answered, because that is the only
     * method the callback calls; the other four answer their type's default, so a future call to one
     * of them shows up as a null rather than being silently absorbed.
     *
     * @param jdbc the stub whose connection the context hands the callback
     * @return a context over that stub, never {@code null}
     */
    private static Context contextOver(RecordingJdbc jdbc) {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return jdbc.connection();
            }

            @Override
            public MigrationInfo getMigrationInfo() {
                return null;
            }

            @Override
            public org.flywaydb.core.api.callback.Statement getStatement() {
                return null;
            }

            @Override
            public OperationResult getOperationResult() {
                return null;
            }
        };
    }

    /**
     * A reflective JDBC stub that records the SQL it is asked to execute, counts the statements that
     * are closed, and optionally rejects every execution.
     *
     * <p>Assumptions: {@code execute} and {@code close} are the two methods named; everything else
     * returns the invoked method's type default, which for the reference-returning methods is
     * {@code null} and for {@code boolean} is {@code false}. That is sufficient because the callback
     * calls {@code createStatement}, {@code execute} and {@code close} and nothing else, and a stub
     * that answered more would be asserting a surface no code uses.
     */
    private static final class RecordingJdbc {

        /** The SQL each execution received, in the order it was issued. */
        private final List<String> executed = new ArrayList<>();

        /** The failure every execution raises, or {@code null} when executions succeed. */
        private final SQLException rejection;

        /** How many statements were closed, which is how the try-with-resources is observed. */
        private int closed;

        /**
         * Creates a stub that either records executions or rejects them.
         *
         * @param rejection the failure every execution raises, or {@code null} to record instead
         */
        private RecordingJdbc(SQLException rejection) {
            this.rejection = rejection;
        }

        /**
         * Returns a connection whose {@code createStatement} yields a recording statement.
         *
         * @return the proxied connection, never {@code null}
         */
        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    handler("createStatement", arguments -> statement()));
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
                        closed++;
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
         * Builds a handler that answers one named method with a supplied value.
         *
         * @param answeredMethod the method name to answer
         * @param answer the value that method returns, given the invocation's arguments
         * @return the handler, never {@code null}
         */
        private InvocationHandler handler(
                String answeredMethod, java.util.function.Function<Object[], Object> answer) {
            return (proxy, method, arguments) -> method.getName().equals(answeredMethod)
                    ? answer.apply(arguments)
                    : defaultValue(method);
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
                return 0;
            }
            return null;
        }
    }
}
