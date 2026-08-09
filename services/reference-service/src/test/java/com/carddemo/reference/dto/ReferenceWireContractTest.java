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
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.reference.config.SecurityConfig;
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
 * table carried; it declared a requirement on reads WEAKER than the chain enforces, naming only
 * authentication after the read rule had been hardened to demand a recognised group; it constrained
 * the date mask to an enumeration
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
     * The value every read operation declares, taken from the converter rather than restated.
     *
     * <p>Assumptions: on an operation this names a requirement satisfied by EITHER recognised group,
     * which is what {@link SecurityConfig#businessAccess()} enforces on the read route. Reading it from
     * {@link JwtRoleConverter} rather than writing the literal is what stops the document and the
     * converter drifting apart while this test keeps passing.</p>
     */
    private static final String USER_AUTHORITY = JwtRoleConverter.USER_AUTHORITY;

    /** The group every write operation declares and the filter chain requires. */
    private static final String ADMIN_AUTHORITY = JwtRoleConverter.ADMIN_AUTHORITY;

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
        //       four hasAuthority matchers for the four write methods followed by a read rule of
        //       access(businessAccess()) and a denyAll() catch-all, so a write declares the
        //       administrative group and every read declares the value either group satisfies.
        // WHY : Refactoring Rationale: the reads previously declared authenticated, and this comment
        //       previously justified it -- that carddemo-user could not be enforced because the shared
        //       converter builds no hierarchy, so an administrator-only token would be refused every
        //       read. That premise was removed when the read rule became businessAccess(), which is
        //       hasAnyAuthority over BOTH groups and therefore admits an administrator-only token. The
        //       document was left behind, publishing a requirement no rule enforces and one WEAKER than
        //       the chain applies: a consumer honouring authenticated would admit a token carrying
        //       neither group, which is the missing-authorization defect the hardening removed.
        operations().forEach((label, operation) -> {
            String method = label.substring(0, label.indexOf(' '));
            String expected = WRITE_METHODS.contains(method) ? ADMIN_AUTHORITY : USER_AUTHORITY;
            assertThat(operation.get(AUTHORITY_FIELD))
                    .as("%s must declare %s", label, expected)
                    .isEqualTo(expected);
        });
    }

    /**
     * Verifies that the authority model publishes exactly the vocabulary the chain enforces.
     *
     * <p>Assumptions: {@code groupValues} is compared against
     * {@link SecurityConfig#BUSINESS_AUTHORITIES} -- the very list the read rule is built from -- rather
     * than against two literals, so the document cannot claim a vocabulary the chain does not admit.
     * That constant is published for exactly this purpose.</p>
     */
    @Test
    @DisplayName("the authority model publishes the vocabulary the filter chain enforces")
    void theAuthorityModelPublishesTheVocabularyTheFilterChainEnforces() {
        Map<String, Object> model = mapping(contract(), "x-authority-model");

        assertThat(model.get("values"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .as("the admitted values are the two the sibling contracts publish, and the withdrawn"
                        + " third value must not return: nothing in this chain requires only"
                        + " authentication")
                .containsExactlyInAnyOrder(USER_AUTHORITY, ADMIN_AUTHORITY);
        assertThat(model.get("groupValues"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .as("the group vocabulary is the migrated form of the two user types, is closed, and is"
                        + " the list SecurityConfig.businessAccess() is built from")
                .containsExactlyInAnyOrderElementsOf(SecurityConfig.BUSINESS_AUTHORITIES);
        assertThat(model)
                .as("the read value states a requirement rather than the absence of one, so the model"
                        + " must say what it means")
                .containsKey("userMeans");
        assertThat(model)
                .as("the prose for the withdrawn value must go with it")
                .doesNotContainKey("authenticatedMeans");
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
        // WHY : Refactoring Rationale: 503 joined this set when the online-write gate was wired, and
        //       the claim above survives unchanged. A batch-level 409 would assert atomic behaviour
        //       the baseline does not have; a 503 asserts nothing about the batch, only that the
        //       environment is not accepting mutating work -- so the operation is refused before any
        //       action is read and there is no per-action outcome to report. The set stays exact so
        //       that a 409 added here later still fails.
        Map<String, Object> batch =
                mapping(mapping(contract(), "paths"), "/api/v1/reference/maintenance-actions");
        Map<String, Object> responses = mapping(mapping(batch, "post"), "responses");

        assertThat(responses.keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "500", "503");

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
