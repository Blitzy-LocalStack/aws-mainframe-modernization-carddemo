package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every row-deleting operation the services ship is a row-deleting operation the database
 * actually permits, and that no table is granted a delete privilege nothing uses.
 *
 * <p><b>Purpose.</b> {@code data-migration/sql/V0__schemas_and_roles.sql} runs before a single table
 * exists, so the only privilege forms available to it are {@code GRANT ... ON ALL TABLES IN SCHEMA} and
 * {@code ALTER DEFAULT PRIVILEGES}, and neither can name one table. It therefore grants {@code SELECT},
 * {@code INSERT} and {@code UPDATE} schema-wide and withholds {@code DELETE} entirely, and
 * {@code data-migration/sql/V2__runtime_delete_grants.sql} grants {@code DELETE} one table at a time once
 * the owning migrations have created them. That split is correct and it is also silent: a service can
 * compile, pass every unit test and start cleanly while holding a delete path the database refuses,
 * because the refusal arrives as a runtime {@code SQLSTATE 42501} on a code path no in-process test
 * exercises. That has already happened twice in this repository -- the two reference-service delete
 * routes, and {@code OutboxPublisher.purgePublished()}'s scheduled retention sweep over a table whose
 * every row carries a primary account number, which failed hourly and let that table grow without
 * bound. This class is the check that catches either direction.</p>
 *
 * <p>Assumptions: this test reads the REPOSITORY rather than the class path, for the same reason
 * {@link RuntimeConfigurationContractTest} does. The artifacts that have to agree are a service's Java
 * source, its {@code application.yml} and a SQL file under {@code data-migration/}, and no two of those
 * are ever on one class path -- least of all here, where this shared kernel is a DEPENDENCY of every
 * module it reasons about and so can never see their compiled classes. Reflection is therefore not
 * available and text is not a shortcut. The repository root is located by walking up from the working
 * directory, and a failure to find it FAILS rather than skips.</p>
 *
 * <p>Trade-offs: the check is bounded to delete CALL SITES rather than to delete CAPABILITY. Every one of
 * the twenty-odd interfaces extending {@code JpaRepository} inherits {@code delete}, {@code deleteById}
 * and {@code deleteAll} as callable methods, so a capability-based check would demand a grant for every
 * table in the system and invert the very least-privilege boundary the split exists to draw. Asserting
 * call sites instead reports exactly the tables that are reached today, which is the set the privilege
 * graph should match.</p>
 *
 * <p>Alternatives Considered: asserting the privileges against a live PostgreSQL through Testcontainers,
 * which would test the grants rather than the agreement between the grants and the code. Rejected as the
 * wrong instrument for this defect, not as unnecessary: the engine's evaluation of an ACL is not in doubt
 * and is already covered by {@code data-migration/sql/verify/runtime_delete_grants.sql}, which runs
 * against the provisioned database where the roles genuinely exist. What no live check can see is a delete
 * site added to Java tomorrow whose table nobody granted -- that is a source-agreement question, and this
 * is where it belongs.</p>
 *
 * @see RuntimeConfigurationContractTest
 * @see LayeringRulesTest
 */
class RuntimeDeletePrivilegeContractTest {

    /**
     * The path, relative to the repository root, of the table-specific delete grants.
     */
    private static final String GRANT_FILE = "data-migration/sql/V2__runtime_delete_grants.sql";

    /**
     * One {@code GRANT DELETE ON <table> TO <role>} statement, with either part optionally quoted.
     */
    private static final Pattern GRANTED = Pattern.compile(
            "GRANT\\s+DELETE\\s+ON\\s+([\\w.\"]+)\\s+TO\\s+([\\w\"]+)\\s*;",
            Pattern.CASE_INSENSITIVE);

    /**
     * A delete issued through a field, which is how every repository is reached in this codebase.
     */
    private static final Pattern DELETE_CALL = Pattern.compile(
            "this\\.(\\w+)\\s*\\.\\s*delete(?:ById|All|AllInBatch)?\\s*\\(");

    /**
     * A bulk delete written as JPQL, whose subject is an entity name rather than a table name.
     */
    private static final Pattern DELETE_JPQL =
            Pattern.compile("delete\\s+from\\s+([A-Z]\\w+)", Pattern.CASE_INSENSITIVE);

    /**
     * The entity type argument of a Spring Data repository declaration.
     */
    private static final Pattern REPOSITORY_ENTITY =
            Pattern.compile("extends\\s+\\w*Repository\\s*<\\s*(\\w+)\\s*,", Pattern.DOTALL);

    /**
     * The mapped table of a JPA entity, whose {@code schema} element is optional.
     */
    private static final Pattern MAPPED_TABLE = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"(\\w+)\"(?:\\s*,\\s*schema\\s*=\\s*\"(\\w+)\")?");

    /**
     * The pinned session search path, from which a schema-less entity resolves its schema.
     */
    private static final Pattern SEARCH_PATH = Pattern.compile(
            "connection-init-sql:\\s*\"?SET\\s+search_path\\s+TO\\s+\"?([\\w\"]+)");

    /**
     * Fails when a service issues a delete against a table its runtime role holds no {@code DELETE} on.
     *
     * <p>This is the direction that has actually broken: the operation compiles, starts and is refused
     * only when the statement reaches the engine.</p>
     */
    @Test
    @DisplayName("every delete a service issues names a table V2 grants that service's role DELETE on")
    void everyDeleteCallSiteHasAMatchingGrant() {
        Path root = repositoryRoot();
        Map<String, String> granted = grantedTables(root);
        Map<String, String> reached = reachedTables(root);

        assertThat(reached)
                .as("no delete call site was found in any service, which would make this check vacuous;"
                        + " the scan or the codebase has changed shape")
                .isNotEmpty();

        Map<String, String> ungranted = new TreeMap<>();
        reached.forEach((table, module) -> {
            String role = granted.get(table);
            if (role == null) {
                ungranted.put(table, module + " deletes from it, and " + GRANT_FILE + " grants nothing");
            } else if (!role.equals(expectedRole(module))) {
                ungranted.put(table, module + " deletes from it, but the grant names " + role
                        + " rather than " + expectedRole(module));
            }
        });

        assertThat(ungranted)
                .as("each table below is deleted from by a shipped service and is not granted DELETE to"
                        + " that service's runtime role, so the operation fails at runtime with SQLSTATE"
                        + " 42501 on a path no in-process test reaches; add the grant to " + GRANT_FILE)
                .isEmpty();
    }

    /**
     * Fails when the grant file confers {@code DELETE} on a table no service deletes from.
     *
     * <p>This is the opposite direction, and the one an operator cannot see: an over-broad privilege
     * produces no error at all, so nothing but a check like this one reports it.</p>
     */
    @Test
    @DisplayName("every table V2 grants DELETE on is reached by a delete a service actually issues")
    void everyGrantIsReachedByADeleteCallSite() {
        Path root = repositoryRoot();
        Map<String, String> granted = grantedTables(root);
        Map<String, String> reached = reachedTables(root);

        assertThat(granted)
                .as("no grant was parsed from " + GRANT_FILE + ", which would make this check vacuous")
                .isNotEmpty();

        assertThat(new TreeSet<>(granted.keySet()))
                .as("each table below is granted DELETE and no service issues a delete against it, so the"
                        + " privilege is wider than the code it exists for; remove the grant from "
                        + GRANT_FILE + " or name the operation that needs it")
                .isSubsetOf(reached.keySet());
    }

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * <p>Assumptions: the marker is the grant file itself rather than a build file, because that file is
     * the subject of this check and a marker that is not the subject can be present while the subject is
     * absent.</p>
     *
     * @return the repository root; never {@code null}
     * @throws AssertionError if no ancestor of the working directory holds the grant file, which fails
     *     rather than skips, because a check that silently does nothing is not a check
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(GRANT_FILE))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " holds " + GRANT_FILE
                + ", so the delete-privilege contract cannot be checked; this test fails rather than"
                + " skips, because a contract nobody checks is a contract that stops holding");
    }

    /**
     * Parses the qualified table names the grant file confers {@code DELETE} on.
     *
     * <p>Assumptions: comment lines are skipped. The file explains each grant at length and names tables
     * inside those rationales -- including the three authorization tables it deliberately does NOT grant
     * -- so a scan that read comments would report grants that are not there.</p>
     *
     * @param root the repository root
     * @return a map of unquoted qualified table name to the role granted {@code DELETE}; never
     *     {@code null}
     * @throws UncheckedIOException if the grant file cannot be read
     */
    private static Map<String, String> grantedTables(Path root) {
        Map<String, String> byTable = new TreeMap<>();
        for (String line : read(root.resolve(GRANT_FILE)).lines().toList()) {
            String code = line.strip();
            if (code.startsWith("--")) {
                continue;
            }
            Matcher grant = GRANTED.matcher(code);
            while (grant.find()) {
                byTable.put(unquote(grant.group(1)), unquote(grant.group(2)));
            }
        }
        return byTable;
    }

    /**
     * Resolves every table a service deletes rows from, through the repository and entity that name it.
     *
     * @param root the repository root
     * @return a map of unquoted qualified table name to the service module that deletes from it; never
     *     {@code null}
     * @throws UncheckedIOException if the service tree cannot be walked
     */
    private static Map<String, String> reachedTables(Path root) {
        Map<String, Path> sources = mainSources(root);
        Map<String, String> byTable = new TreeMap<>();
        sources.values().forEach(file -> {
            String text = read(file);
            String module = moduleOf(root, file);
            deletedEntities(text).forEach(entity ->
                    tableOf(root, sources, module, entity)
                            .ifPresent(table -> byTable.put(table, module)));
        });
        return byTable;
    }

    /**
     * Collects the entity type names one source file issues a delete against.
     *
     * <p>Assumptions: a delete reached through a field is kept only when that field's declared type name
     * ends in {@code Repository}. The two reference controllers call {@code this.service.delete(...)},
     * which is a call to a service and not a statement against a table; counting it would resolve to no
     * entity and report a false gap.</p>
     *
     * @param text the source file's contents
     * @return the repository and entity type names, in encounter order; never {@code null}
     */
    private static Set<String> deletedEntities(String text) {
        Set<String> entities = new LinkedHashSet<>();
        Matcher call = DELETE_CALL.matcher(text);
        while (call.find()) {
            String field = call.group(1);
            Matcher declaration = Pattern.compile(
                    "(?:private|protected|public)\\s+(?:final\\s+)?(\\w+)\\s+" + field + "\\s*[;=)]")
                    .matcher(text);
            if (declaration.find() && declaration.group(1).endsWith("Repository")) {
                entities.add(declaration.group(1));
            }
        }
        Matcher jpql = DELETE_JPQL.matcher(text);
        while (jpql.find()) {
            entities.add(jpql.group(1));
        }
        return entities;
    }

    /**
     * Resolves a repository or entity type name to the qualified table it maps to.
     *
     * @param root the repository root
     * @param sources every main source file, indexed by simple type name
     * @param module the service module the delete was found in
     * @param type either a repository type name or an entity type name
     * @return the unquoted qualified table name, or empty when the type resolves to no mapped table
     * @throws UncheckedIOException if a resolved source file cannot be read
     */
    private static Optional<String> tableOf(
            Path root, Map<String, Path> sources, String module, String type) {
        Path declaration = sources.get(type);
        if (declaration == null) {
            return Optional.empty();
        }
        String text = read(declaration);
        if (type.endsWith("Repository")) {
            Matcher entity = REPOSITORY_ENTITY.matcher(text);
            return entity.find()
                    ? tableOf(root, sources, module, entity.group(1))
                    : Optional.empty();
        }
        Matcher table = MAPPED_TABLE.matcher(text);
        if (!table.find()) {
            return Optional.empty();
        }
        String schema = table.group(2) != null ? table.group(2) : pinnedSchema(root, module);
        return Optional.of(schema + "." + table.group(1));
    }

    /**
     * Reads the first schema on a module's pinned session search path.
     *
     * <p>Assumptions: an entity whose {@code @Table} declares no schema resolves against the session
     * search path, so the module's own {@code connection-init-sql} is the only place its schema is
     * stated. {@code AuthReplyOutbox} is exactly this case, and its schema name is a reserved word the
     * configuration quotes.</p>
     *
     * @param root the repository root
     * @param module the service module directory name
     * @return the first schema on the pinned search path; never {@code null}
     * @throws AssertionError if the module pins no search path, because a schema-less entity would then
     *     have no resolvable schema and the check would silently compare the wrong name
     */
    private static String pinnedSchema(Path root, String module) {
        Matcher path = SEARCH_PATH.matcher(
                read(root.resolve("services").resolve(module).resolve("src/main/resources/application.yml")));
        assertThat(path.find())
                .as(module + " maps an entity with no declared schema and pins no search path, so that"
                        + " entity's schema is unresolvable and this contract cannot be checked")
                .isTrue();
        return unquote(path.group(1)).split(",")[0].strip();
    }

    /**
     * Derives the runtime role a module connects as, from the module's directory name.
     *
     * <p>Assumptions: the naming rule is the one {@code V0__schemas_and_roles.sql} establishes -- one
     * {@code carddemo_<context>} login per bounded context, where the context is the module name without
     * its {@code -service} suffix. Asserting the role rather than only the table is what distinguishes a
     * correct grant from one conferred on the wrong identity, which no table-only check can see.</p>
     *
     * @param module the service module directory name
     * @return the expected runtime role name; never {@code null}
     */
    private static String expectedRole(String module) {
        return "carddemo_" + module.replaceFirst("-service$", "").replace('-', '_');
    }

    /**
     * Indexes every main source file under the services tree by its simple type name.
     *
     * @param root the repository root
     * @return a map of simple type name to source path; never empty
     * @throws UncheckedIOException if the services tree cannot be walked
     */
    private static Map<String, Path> mainSources(Path root) {
        Map<String, Path> byName = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root.resolve("services"))) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> file.toString().contains("/src/main/java/"))
                    .forEach(file -> {
                        String name = file.getFileName().toString();
                        byName.put(name.substring(0, name.length() - ".java".length()), file);
                    });
        } catch (IOException unwalkable) {
            throw new UncheckedIOException("cannot walk the services tree under " + root, unwalkable);
        }
        assertThat(byName)
                .as("the services tree holds no main source file, so this check would be vacuous")
                .isNotEmpty();
        return byName;
    }

    /**
     * Names the service module a source file belongs to.
     *
     * @param root the repository root
     * @param file a source file under the services tree
     * @return the module directory name; never {@code null}
     */
    private static String moduleOf(Path root, Path file) {
        return root.resolve("services").relativize(file).getName(0).toString();
    }

    /**
     * Removes SQL and YAML identifier quoting from one token.
     *
     * @param token the token to unquote
     * @return the token with every double quote removed; never {@code null}
     */
    private static String unquote(String token) {
        return token.replace("\"", "");
    }

    /**
     * Reads one repository file as text.
     *
     * @param file the absolute path to read
     * @return the file's contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which fails the test
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
