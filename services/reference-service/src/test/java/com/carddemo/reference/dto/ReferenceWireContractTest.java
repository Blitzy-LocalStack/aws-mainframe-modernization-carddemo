package com.carddemo.reference.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published reference contract to the Flyway migration and the filter chain beside it.
 *
 * <p>Assumptions: three authored artifacts have to agree and nothing compared them. The contract is
 * {@code openapi/reference-api.yaml}; the persistence is {@code db/migration/V1__reference.sql}; the
 * authorisation is {@code config/SecurityConfig.java}. All three are on this module's classpath at
 * test time, the first two as resources and the third as a compiled class, so the comparison needs
 * no running service -- which matters, because this module has no controller to run.
 *
 * <p>Refactoring Rationale: each assertion below corresponds to a contradiction a review found
 * between two of those three. The contract required a monotonically incremented version that no
 * table carried; it declared a group requirement on reads that the chain does not enforce and could
 * not enforce without refusing every administrator; it constrained the date mask to an enumeration
 * while promising to ANSWER an unrecognised mask rather than refuse it; it described the reference
 * maintenance batch as all-or-nothing although the baseline program soft-rejects a record and
 * continues; and its ten paging examples all failed the pattern it publishes for them. None of those
 * needed a service to detect and none of them was detectable by reading one file.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class ReferenceWireContractTest {

    /** The classpath location of the published contract. */
    private static final String CONTRACT_RESOURCE = "/openapi/reference-api.yaml";

    /** The classpath location of this context's schema migration. */
    private static final String MIGRATION_RESOURCE = "/db/migration/V1__reference.sql";

    /**
     * The requirement value every read operation declares.
     *
     * <p>Assumptions: this is a REQUIREMENT and not a group name. The two group names remain
     * {@code carddemo-admin} and {@code carddemo-user}; this value says that any accepted token
     * suffices, which is what {@code anyRequest().authenticated()} enforces.</p>
     */
    private static final String AUTHENTICATED = "authenticated";

    /** The group every write operation declares and the filter chain requires. */
    private static final String ADMIN_AUTHORITY = "carddemo-admin";

    /** The operation-level field carrying each operation's authority requirement. */
    private static final String AUTHORITY_FIELD = "x-required-authority";

    /** The HTTP methods the filter chain restricts to the administrative group. */
    private static final List<String> WRITE_METHODS = List.of("post", "put", "patch", "delete");

    /** The two reference tables this contract offers a replace for, and so versions. */
    private static final List<String> VERSIONED_TABLES =
            List.of("reference.transaction_types", "reference.transaction_categories");

    /** The four reference tables this contract only reads, and so does not version. */
    private static final List<String> UNVERSIONED_TABLES = List.of(
            "reference.disclosure_groups", "reference.us_phone_area_codes",
            "reference.us_states", "reference.us_state_zip_prefixes");

    /**
     * Reads a classpath resource as text.
     *
     * @param resource the absolute classpath location to read
     * @return the resource content decoded as UTF-8
     * @throws IllegalStateException if the resource is absent, which would mean the module does not
     *     package it, or if it cannot be read
     */
    private static String readResource(String resource) {
        try (InputStream stream = ReferenceWireContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("absent from the classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("could not be read: " + resource, failure);
        }
    }

    /**
     * Reads and parses the published contract.
     *
     * @return the whole document as nested maps and lists; never {@code null}
     * @throws IllegalStateException if the document is not a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() {
        Object parsed = new Yaml().load(readResource(CONTRACT_RESOURCE));
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException("the published contract is not a mapping");
        }
        return (Map<String, Object>) parsed;
    }

    /**
     * Returns one nested mapping by key.
     *
     * @param parent the enclosing mapping; must not be {@code null}
     * @param key the key to read
     * @return the nested mapping
     * @throws IllegalStateException if the key is absent or does not hold a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalStateException("expected a mapping at \"" + key + "\"");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Returns every operation of the document, keyed by a readable method-and-path label.
     *
     * @return the operations in document order; never empty
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> operations() {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        Map<String, Object> paths = mapping(contract(), "paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            for (Map.Entry<String, Object> verb : mapping(paths, path.getKey()).entrySet()) {
                if (verb.getValue() instanceof Map) {
                    found.put(verb.getKey() + " " + path.getKey(),
                            (Map<String, Object>) verb.getValue());
                }
            }
        }
        return found;
    }

    /**
     * Returns the body of one {@code CREATE TABLE} statement of the migration.
     *
     * @param qualifiedTableName the schema-qualified table name to locate
     * @return the text between the opening parenthesis and the closing {@code );}
     * @throws IllegalStateException if the migration declares no such table, so a renamed table
     *     fails here rather than making an assertion below silently vacuous
     */
    private static String createTableBody(String qualifiedTableName) {
        String migration = readResource(MIGRATION_RESOURCE);
        int start = migration.indexOf("CREATE TABLE " + qualifiedTableName + " (");
        if (start < 0) {
            throw new IllegalStateException("the migration declares no " + qualifiedTableName);
        }
        int end = migration.indexOf("\n);", start);
        if (end < 0) {
            throw new IllegalStateException("unterminated CREATE TABLE for " + qualifiedTableName);
        }
        return migration.substring(start, end);
    }

    /**
     * Returns the column names one {@code CREATE TABLE} body declares, ignoring comments.
     *
     * @param qualifiedTableName the schema-qualified table name to read
     * @return the declared column names in declaration order
     */
    private static List<String> columnNames(String qualifiedTableName) {
        List<String> names = new ArrayList<>();
        Pattern column = Pattern.compile("^ {4}([a-z_]+)\\s{2,}[A-Z]");
        for (String line : createTableBody(qualifiedTableName).split("\n")) {
            var matcher = column.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Verifies that every table offering a replace carries the version column the contract needs.
     */
    @Test
    @DisplayName("the version the contract requires is a column the migration creates")
    void theVersionTheContractRequiresIsAColumnTheMigrationCreates() {
        // WHY : Assumptions: the assertion runs in BOTH directions, and the second direction is the
        //       one that keeps it honest. Requiring the column where a replace exists catches a
        //       contract promising what no table supplies; requiring its ABSENCE elsewhere catches
        //       the opposite mistake, a column added to every table on the assumption that more
        //       concurrency control is always better, which would leave four columns written once
        //       and never read.
        assertThat(mapping(contract(), "components")).containsKey("schemas");
        assertThat(mapping(mapping(contract(), "components"), "schemas"))
                .as("the contract must still publish the version schema this asserts against")
                .containsKey("RecordVersion");

        for (String table : VERSIONED_TABLES) {
            assertThat(columnNames(table))
                    .as("%s is replaced through this contract and must carry a version", table)
                    .contains("version");
        }
        for (String table : UNVERSIONED_TABLES) {
            assertThat(columnNames(table))
                    .as("%s is read-only through this contract and must not carry a version", table)
                    .doesNotContain("version");
        }
    }

    /**
     * Verifies that the declared authority of every operation is the one the filter chain enforces.
     */
    @Test
    @DisplayName("each operation declares the requirement the filter chain enforces")
    void eachOperationDeclaresTheRequirementTheFilterChainEnforces() {
        // WHY : Assumptions: the chain restricts by HTTP METHOD and not by path, so the expected
        //       value of this field is computable from the method alone -- which is why the
        //       assertion can be exhaustive rather than a table somebody has to extend. The chain is
        //       four hasAuthority matchers for the four write methods followed by
        //       anyRequest().authenticated(), so a write declares the administrative group and
        //       everything else declares only that a token was accepted.
        // WHY : Refactoring Rationale: the reads previously declared carddemo-user, which the chain
        //       does not require. Enforcing it would have been worse than declaring it: the shared
        //       converter builds no hierarchy, so a token carrying only the administrative group
        //       would have been refused every read, and the baseline refuses an administrator
        //       nothing.
        operations().forEach((label, operation) -> {
            String method = label.substring(0, label.indexOf(' '));
            String expected = WRITE_METHODS.contains(method) ? ADMIN_AUTHORITY : AUTHENTICATED;
            assertThat(operation.get(AUTHORITY_FIELD))
                    .as("%s must declare %s", label, expected)
                    .isEqualTo(expected);
        });
    }

    /**
     * Verifies that the authority model separates a requirement value from a group name.
     */
    @Test
    @DisplayName("the authority model separates requirements from group names")
    void theAuthorityModelSeparatesRequirementsFromGroupNames() {
        Map<String, Object> model = mapping(contract(), "x-authority-model");

        assertThat(model.get("values"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrder(AUTHENTICATED, ADMIN_AUTHORITY);
        assertThat(model.get("groupValues"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .as("the group vocabulary is the migrated form of the two user types and is closed")
                .containsExactlyInAnyOrder(ADMIN_AUTHORITY, "carddemo-user");
        assertThat(model).containsKey("authenticatedMeans");
    }

    /**
     * Verifies that the enumerated mask schema is the type of nothing in this document.
     *
     * <p>Refactoring Rationale: this test used to assert that the enumerated mask schema carried NO
     * enum, publishing its recognised pair as an {@code x-recognised-masks} extension instead, on the
     * ground that an enum would make a schema-validating layer refuse an unrecognised mask with 400
     * before the handler could answer it with the unusable-pattern verdict the baseline gives. That
     * hazard is real and it is not where the guard belongs. The document carries TWO mask schemas:
     * {@code DateMaskInput}, bounded at ten characters and deliberately unenumerated, which is the
     * type of the submitted parameter AND of the echoed response member; and {@code DateMask}, whose
     * enum publishes the recognised pair to a generated client and which is the type of nothing.
     * Removing the enum hid the one fact that schema exists to state, in order to guard against a
     * change nobody had made. What this test asserts instead is the invariant that keeps the enum
     * safe -- that no reference path in the whole document resolves to it -- so an edit that
     * {@code $ref}s it into a request or a response position fails here rather than silently
     * blocking the verdict.</p>
     *
     * <p>Assumptions: the enum's own membership and the wider input's bound are asserted by
     * {@code ReferenceApiContractTest} in the {@code config} package, which holds the contract to
     * {@code SecurityConfig} and the migration. This class holds it to the Java shapes and to itself,
     * and the reachability invariant is a property of the document alone, which is why it is here.</p>
     */
    @Test
    @DisplayName("the enumerated mask schema is referenced by nothing so the verdict stays reachable")
    void theEnumeratedMaskSchemaIsReferencedByNothing() {
        Map<String, Object> schemas = mapping(mapping(contract(), "components"), "schemas");
        Map<String, Object> mask = mapping(schemas, "DateMask");

        assertThat(mask.get("enum"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .as("the recognised pair is published as a plain enum, not as a vendor extension")
                .containsExactly("YYYY-MM-DD", "YYYYMMDD");
        assertThat(mask)
                .as("a schema that types nothing needs no bound and no default")
                .doesNotContainKey("maxLength")
                .doesNotContainKey("default");

        assertThat(readResource(CONTRACT_RESOURCE))
                .as("nothing may reference the enumerated mask schema; the submitted and echoed "
                        + "mask are both DateMaskInput")
                .doesNotContain("'#/components/schemas/DateMask'")
                .doesNotContain("\"#/components/schemas/DateMask\"")
                .contains("'#/components/schemas/DateMaskInput'");
    }

    /**
     * Verifies that the maintenance batch reports per action and refuses no batch on a conflict.
     */
    @Test
    @DisplayName("the maintenance batch reports per action rather than all or nothing")
    void theMaintenanceBatchReportsPerActionRatherThanAllOrNothing() {
        // WHY : Assumptions: the absence of a batch-level conflict status is the load-bearing half of
        //       this assertion. The baseline's failure paths all reach 9999-ABEND, which reports and
        //       sets the warn-tier return code without stopping the run, so a conflicting record
        //       leaves every other record applied. A 409 on this operation would describe the atomic
        //       behaviour it deliberately does not have -- while the per-row operations keep theirs,
        //       because there a conflict IS the whole outcome.
        Map<String, Object> batch =
                mapping(mapping(contract(), "paths"), "/api/v1/reference/maintenance-actions");
        Map<String, Object> responses = mapping(mapping(batch, "post"), "responses");

        assertThat(responses.keySet()).containsExactlyInAnyOrder("200", "400", "401", "403", "500");

        Map<String, Object> response = mapping(mapping(mapping(contract(), "components"), "schemas"),
                "MaintenanceActionBatchResponse");
        assertThat(response.get("required"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrder("outcomes", "returnCode");
        // WHY : Assumptions: the aggregate is published as the baseline's own return code rather than
        //       as a named tier, and the two admitted values are the two that program reaches. The
        //       migration already carries this quantity under that name elsewhere -- batch.batch_run
        //       declares return_code SMALLINT with a check constraint -- so one vocabulary describes
        //       it in the schema, in the durable ledger and in this contract.
        assertThat(mapping(mapping(response, "properties"), "returnCode").get("enum"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Integer.class))
                .as("the two tiers the baseline reaches, its return code zero and four")
                .containsExactly(0, 4);

        Map<String, Object> perRow = mapping(mapping(contract(), "paths"),
                "/api/v1/reference/transaction-types/{typeCd}");
        assertThat(mapping(mapping(perRow, "put"), "responses").keySet())
                .as("a per-row replace still answers 409, where the conflict is the whole outcome")
                .contains("409");
    }

    /**
     * Verifies that every paging example satisfies the pattern the contract publishes for cursors.
     */
    @Test
    @DisplayName("every paging example satisfies the cursor pattern the contract publishes")
    void everyPagingExampleSatisfiesTheCursorPatternTheContractPublishes() {
        // WHY : Assumptions: the pattern is read FROM the document rather than restated here, so the
        //       examples are held to whatever the contract currently promises rather than to a copy
        //       of it that could drift. A copy would have gone on passing after the pattern changed,
        //       which is the failure this assertion exists to make impossible.
        Map<String, Object> cursor =
                mapping(mapping(mapping(contract(), "components"), "schemas"), "CursorToken");
        Pattern published = Pattern.compile(String.valueOf(cursor.get("pattern")));
        int ceiling = (int) cursor.get("maxLength");

        Pattern example = Pattern.compile("(?:firstKey|lastKey): (\\S+)");
        var matcher = example.matcher(readResource(CONTRACT_RESOURCE));
        int examined = 0;
        while (matcher.find()) {
            String token = matcher.group(1);
            examined++;
            assertThat(published.matcher(token).matches())
                    .as("the example cursor %s must satisfy the published pattern", token)
                    .isTrue();
            assertThat(token.length()).isLessThanOrEqualTo(ceiling);
        }
        assertThat(examined)
                .as("the sweep must find the cursor examples rather than silently finding none")
                .isEqualTo(10);
    }
}
