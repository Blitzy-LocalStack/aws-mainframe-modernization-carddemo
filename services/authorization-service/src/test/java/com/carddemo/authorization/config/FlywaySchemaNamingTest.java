package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that this context spells its reserved-word schema the way each consumer of the value requires:
 * bare where the value is an IDENTIFIER, quoted where the value is SQL TEXT.
 *
 * <p>Refactoring Rationale: this class exists because both Flyway keys carried {@code '"authorization"'} --
 * the identifier with two literal double-quote characters inside it -- on the stated ground that the
 * quoting was required for the same reason the connection-init statement needs it. Measured against a real
 * PostgreSQL 17 instance, that value makes Flyway resolve
 * {@code """authorization"""."flyway_schema_history"} and the server answers SQLSTATE 3F000,
 * {@code schema ""authorization"" does not exist}, so the service could not start at all. The bare value
 * resolves {@code "authorization"."flyway_schema_history"} and validates. Nothing in the build compared the
 * two spellings, and the failure only appeared against a database, which is why the distinction is pinned
 * here.</p>
 *
 * <p>Assumptions: both profiles are read from the class path rather than restated as literals, so the
 * assertions are about the shipped files. A literal here would let this class agree with itself while a
 * profile drifted, which is the entire failure mode it is written against.</p>
 */
class FlywaySchemaNamingTest {

    /** The schema this context owns, spelled as the identifier Flyway and JPA must receive. */
    private static final String SCHEMA = "authorization";

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /** The test profile, packaged from {@code src/test/resources}. */
    private static final String TEST_PROFILE = "/application-test.yml";

    /**
     * Confirms both Flyway keys name the schema with no quote characters, in both profiles.
     *
     * <p>Assumptions: {@code schemas} and {@code default-schema} are asserted separately even though both
     * currently hold the same value. They answer different questions -- which schemas Flyway manages, and
     * where its history table resolves -- so a value corrected in one and missed in the other would place
     * migration state in {@code public} while the tables landed here, and a repeated run would report the
     * wrong history rather than failing.</p>
     */
    @Test
    @DisplayName("both Flyway schema keys are bare identifiers in both profiles")
    void bothFlywaySchemaKeysAreBareIdentifiersInBothProfiles() {
        for (String profile : new String[] {BASE_PROFILE, TEST_PROFILE}) {
            Map<String, Object> flyway = section(profile, "spring", "flyway");

            for (String key : new String[] {"schemas", "default-schema"}) {
                Object value = flyway.get(key);

                assertThat(value)
                        .as("%s must declare spring.flyway.%s", profile, key)
                        .isNotNull();
                assertThat(String.valueOf(value))
                        .as("%s spring.flyway.%s must be the bare identifier: a value carrying quote "
                                + "characters makes Flyway name a schema whose name contains them",
                                profile, key)
                        .isEqualTo(SCHEMA)
                        .doesNotContain("\"")
                        .doesNotContain("'");
            }
        }
    }

    /**
     * Confirms the connection-init statement keeps its quotes, which is the opposite requirement.
     *
     * <p>Assumptions: this is asserted alongside the bare-identifier rule rather than separately, because
     * the two are one decision seen from two ends and a reader who corrects only half of it breaks the
     * other half. {@code authorization} is a reserved word, so in SQL text an unquoted occurrence is a
     * syntax error; in an identifier passed to a library that quotes for itself, the quotes become part of
     * the name.</p>
     */
    @Test
    @DisplayName("the connection-init statement quotes the reserved word, because that value is SQL text")
    void connectionInitStatementQuotesTheReservedWord() {
        Object initSql = section(BASE_PROFILE, "spring", "datasource", "hikari").get("connection-init-sql");

        assertThat(String.valueOf(initSql))
                .as("the init statement is SQL sent verbatim, where the reserved word must be quoted")
                .isEqualTo("SET search_path TO \"" + SCHEMA + "\"");
    }

    /**
     * Reads one nested mapping out of a packaged YAML profile.
     *
     * @param resource the class-path resource to read; must name a YAML document
     * @param path the mapping keys to descend, in order; must not be empty
     * @return the mapping at that path, never {@code null}
     * @throws IllegalStateException if the resource is absent from the test class path, or if any key on
     *     the path is missing or does not hold a mapping, either of which would mean this assertion was
     *     silently testing nothing
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String resource, String... path) {
        try (InputStream document = FlywaySchemaNamingTest.class.getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(resource + " is not on the test class path");
            }
            Object current = new Yaml().load(document);
            StringBuilder walked = new StringBuilder();
            for (String key : path) {
                walked.append('/').append(key);
                if (!(current instanceof Map<?, ?> mapping) || !mapping.containsKey(key)) {
                    throw new IllegalStateException(resource + " has no mapping at " + walked);
                }
                current = ((Map<String, Object>) mapping).get(key);
            }
            if (!(current instanceof Map<?, ?>)) {
                throw new IllegalStateException(resource + walked + " is not a mapping");
            }
            return new LinkedHashMap<>((Map<String, Object>) current);
        } catch (java.io.IOException problem) {
            throw new IllegalStateException(resource + " could not be read", problem);
        }
    }
}
