package com.carddemo.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that this context refuses to start unless its connections are pinned to the one schema it holds
 * read privileges on, and that the statement the shipped profile declares is one it accepts.
 *
 * <p>Refactoring Rationale: this class exists because the guard it exercises used to match on the opening
 * words {@code set search_path} alone. Everything after those words was unchecked, so the guard admitted
 * every statement it existed to refuse -- a different context's schema, an extra schema alongside this one,
 * and a second statement appended after a semicolon, which the pool would then have run on every physical
 * connection it opened. The assertions below are one per admitted case, because a single "rejects a bad
 * value" test would have passed against the old guard too.</p>
 *
 * <p>Assumptions: the accepting case is asserted from the SHIPPED profile rather than from a literal, so a
 * guard tightened past what the configuration declares fails here rather than at container start. That
 * pairing is the point: the rejecting cases prove the guard bites, and the accepting case proves it does
 * not bite the deployment.</p>
 */
class DataSourceConfigTest {

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /** The test profile, packaged from {@code src/test/resources}. */
    private static final String TEST_PROFILE = "/application-test.yml";

    /**
     * Confirms the statement each shipped profile declares is accepted, so the guard admits the deployment.
     *
     * <p>Assumptions: both profiles that declare the statement are exercised. The two overlays do not
     * declare it at all -- verified by reading them -- so the base and test values are the complete set of
     * statements this service can be started with.</p>
     */
    @Test
    @DisplayName("the statement every shipped profile declares is accepted")
    void statementEveryShippedProfileDeclaresIsAccepted() {
        for (String profile : new String[] {BASE_PROFILE, TEST_PROFILE}) {
            String declared = String.valueOf(initSql(profile));

            assertThatCode(() -> new DataSourceConfig(declared, true))
                    .as("%s declares [%s], which the guard must accept", profile, declared)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Confirms neither environment overlay redeclares the statement, which is what makes one allowed schema
     * an architectural invariant rather than a per-environment value.
     *
     * <p>Assumptions: this is asserted rather than assumed because the whole justification for comparing
     * against a compiled schema name rests on it. If an overlay were to declare its own statement, the
     * compiled constant and the configuration would have two legitimate values and the comparison would be
     * wrong rather than strict.</p>
     */
    @Test
    @DisplayName("neither environment overlay redeclares the pinning statement")
    void neitherEnvironmentOverlayRedeclaresThePinningStatement() {
        for (String overlay : new String[] {"/application-dev.yml", "/application-prod.yml"}) {
            assertThat(initSql(overlay))
                    .as("%s must inherit the base statement, not restate it", overlay)
                    .isNull();
        }
    }

    /**
     * The rejecting cases, one per statement the previous prefix-only guard admitted.
     *
     * <p>Assumptions: each entry below is a statement a PostgreSQL server would have executed without
     * complaint, which is why none of them could be caught anywhere later than startup. The second-statement
     * case is the most consequential: the pool runs this text on every physical connection it opens, so a
     * statement appended here executes for the life of the service rather than once.</p>
     */
    @Test
    @DisplayName("a different schema, an extra schema, an appended statement and a bare word are refused")
    void statementsThePrefixOnlyGuardAdmittedAreRefused() {
        List<String> refused = List.of(
                "SET search_path TO card",
                "SET search_path TO reporting, ledger",
                "SET search_path TO reporting; DROP VIEW reporting.transactions",
                "SET search_path TO reporting_staging",
                "SET search_path TO \"reporting\", \"ledger\"",
                "SET search_path TO reporting; SET ROLE carddemo_reporting_owner");

        for (String statement : refused) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("[%s] must not be accepted", statement)
                    .isThrownBy(() -> new DataSourceConfig(statement, true))
                    .withMessageContaining("connection-init-sql");
        }
    }

    /**
     * Confirms the quoted spelling of the one allowed schema is still accepted, since it is the same name.
     *
     * <p>Assumptions: this boundary is asserted so the tightening does not become an accidental ban on a
     * legitimate spelling. To the server {@code reporting} and {@code "reporting"} name one schema, and a
     * guard that refused the quoted form would reject a correct configuration.</p>
     */
    @Test
    @DisplayName("the quoted spelling of the allowed schema is accepted, and a trailing semicolon too")
    void quotedSpellingAndTrailingSemicolonAreAccepted() {
        assertThatCode(() -> new DataSourceConfig("SET search_path TO \"reporting\"", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> new DataSourceConfig("SET  search_path  TO  reporting ;", true))
                .doesNotThrowAnyException();
    }

    /**
     * Confirms the default schema keeps its own diagnostic, and that absence and blankness are refused.
     */
    @Test
    @DisplayName("the default schema, an absent value and a blank value are each refused")
    void defaultSchemaAbsenceAndBlanknessAreRefused() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new DataSourceConfig("SET search_path TO public", true))
                .withMessageContaining("default schema");
        for (String unset : java.util.Arrays.asList(null, "", "   ")) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a statement of [%s] must be refused", unset)
                    .isThrownBy(() -> new DataSourceConfig(unset, true))
                    .withMessageContaining("connection-init-sql");
        }
    }

    /**
     * Confirms a pool that is not declared read-only stops the service, independently of the statement.
     *
     * <p>Assumptions: the read-only posture is a second, independent guard over privileges the database
     * already withholds, so it is asserted separately from the search path. A pool permitting writes would
     * be refused by the login role anyway -- but at the moment of the write, deep inside a report that had
     * already done its work, rather than at startup.</p>
     */
    @Test
    @DisplayName("a pool not declared read-only stops the service at startup")
    void poolNotDeclaredReadOnlyStopsTheServiceAtStartup() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new DataSourceConfig("SET search_path TO reporting", false))
                .withMessageContaining("read-only");
    }

    /**
     * Reads the pool's initialisation statement from a packaged profile.
     *
     * @param resource the class-path resource to read; must name a YAML document
     * @return the declared statement, or {@code null} when the profile does not declare one
     * @throws IllegalStateException if the resource is absent from the test class path, which would mean
     *     this assertion was silently reading nothing
     */
    @SuppressWarnings("unchecked")
    private Object initSql(String resource) {
        try (InputStream document = DataSourceConfigTest.class.getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(resource + " is not on the test class path");
            }
            Object current = new Yaml().load(document);
            for (String key : new String[] {"spring", "datasource", "hikari", "connection-init-sql"}) {
                if (!(current instanceof Map<?, ?> mapping)) {
                    return null;
                }
                current = ((Map<String, Object>) mapping).get(key);
                if (current == null) {
                    return null;
                }
            }
            return current;
        } catch (java.io.IOException problem) {
            throw new IllegalStateException(resource + " could not be read", problem);
        }
    }
}
