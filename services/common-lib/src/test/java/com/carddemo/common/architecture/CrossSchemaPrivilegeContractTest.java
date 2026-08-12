package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the one cross-schema database role in this migration reaches exactly the schemas its code
 * reaches, and no others.
 *
 * <p><b>Purpose.</b> Every bounded context in this migration owns one schema and its runtime role holds
 * privileges on that schema alone -- with one sanctioned exception. The posting unit of work commits the
 * transaction, the category balance and the account together, and the migration keeps that a single ACID
 * commit rather than fragmenting it into a saga, so {@code carddemo_batch} is granted narrowly scoped
 * cross-schema privileges. An exception granted once is an exception that grows: a schema added to that
 * grant list costs nothing to add, produces no error, and is invisible in every test the module runs,
 * because an unused read privilege behaves exactly like an absent one right up until the role is misused.
 * That has already happened here -- {@code card} stood on the list and on the service's session search
 * path, justified on a reading of {@code app/cbl/CBTRN01C.cbl} that the program itself contradicts: it
 * OPENs {@code CARD-FILE} and never READs it. That grant was withdrawn, and it has since been reinstated
 * on a justification the source does support: {@code app/cbl/CBEXPORT.cbl} opens the card master at
 * {@code :513} and writes one export record per card at {@code :527}-{@code :545}, and
 * {@code com.carddemo.batch.domain.Card} maps {@code card.cards} for that phase. Both movements are the
 * point rather than a contradiction -- what this class asserts is not that the list is short but that
 * every entry on it answers to a call site, and it is what turned the first reading into a build failure
 * and lets the second stand. This class is the check that makes the grant list and the code agree, in
 * both directions.</p>
 *
 * <p>Assumptions: three artifacts have to agree and no two of them are on one class path -- the bootstrap
 * SQL under {@code data-migration/}, the batch service's {@code application.yml}, and that module's Java
 * sources. This test therefore reads the REPOSITORY, exactly as {@link RuntimeDeletePrivilegeContractTest}
 * and {@link RuntimeConfigurationContractTest} do, and for the same reason: this shared kernel is a
 * DEPENDENCY of every module it reasons about, so it can never see their compiled classes and reflection
 * is not available. Reading another module's FILES is not importing its types, so the kernel's inward-only
 * dependency rule is untouched. The repository root is located by walking up from the working directory,
 * and a failure to find it FAILS rather than skips, because a check that silently does nothing is not a
 * check.</p>
 *
 * <p>Alternatives Considered: asserting the privilege graph against a live PostgreSQL through
 * Testcontainers. Rejected as the wrong instrument, not as unnecessary. What a live check can see is how
 * the engine evaluates an access-control list, which is not in doubt and is already covered by the
 * operator-run scripts under {@code data-migration/sql/verify/}. What no live check can see is a grant
 * that no code path uses, because the database cannot know which privileges the application intends to
 * exercise -- that is an agreement between two files, and this is where it belongs.</p>
 *
 * <p>Trade-offs: the code side of the comparison is the service's declared session search path rather
 * than a scan of its query text. The search path is the module's own statement of which schemas it
 * resolves objects in, it is validated at start-up against the connection's effective posture by that
 * module's {@code DataSourceConfig}, and it is one line rather than an inference over every repository
 * and entity. The cost is that a query which named a schema explicitly and left it off the path would not
 * be seen here; that is acceptable because the same {@code DataSourceConfig} rejects a path whose owned
 * schema does not lead it, and because a cross-schema write in this module is deliberately confined to
 * the mapped entities the posting unit of work touches.</p>
 *
 * @see RuntimeDeletePrivilegeContractTest
 * @see RuntimeConfigurationContractTest
 */
@DisplayName("The batch role's cross-schema grants are exactly the schemas its code reaches")
final class CrossSchemaPrivilegeContractTest {

    /** The bootstrap script that establishes every schema, role and privilege. */
    private static final String BOOTSTRAP_FILE = "data-migration/sql/V0__schemas_and_roles.sql";

    /** The batch service configuration that declares the session search path. */
    private static final String BATCH_CONFIGURATION =
            "services/batch-service/src/main/resources/application.yml";

    /** The batch service's Java source tree, scanned for a declared card-schema mapping. */
    private static final String BATCH_SOURCES = "services/batch-service/src/main/java";

    /** The cross-schema runtime role this contract governs. */
    private static final String ROLE = "carddemo_batch";

    /** The schema the batch context owns, which is never one of its cross-schema grants. */
    private static final String OWNED_SCHEMA = "batch";

    /**
     * One {@code GRANT USAGE ON SCHEMA <list> TO <role>} statement, capturing the comma-separated list.
     *
     * <p>Assumptions: USAGE is the privilege matched rather than SELECT or UPDATE, because USAGE on the
     * schema is what makes any object inside it nameable at all. A table privilege without it is
     * unreachable, so the USAGE list is the authoritative statement of which schemas a role can touch.</p>
     */
    private static final Pattern USAGE_GRANT = Pattern.compile(
            "GRANT\\s+USAGE\\s+ON\\s+SCHEMA\\s+([\\w\\s,\"]+?)\\s+TO\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE);

    /**
     * One {@code GRANT ... ON ALL TABLES IN SCHEMA <schema> TO <role>} statement.
     */
    private static final Pattern TABLE_GRANT = Pattern.compile(
            "GRANT\\s+[\\w\\s,]+?\\s+ON\\s+ALL\\s+TABLES\\s+IN\\s+SCHEMA\\s+(\\w+)\\s+TO\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE);

    /**
     * One {@code ALTER DEFAULT PRIVILEGES ... IN SCHEMA <schema> GRANT ... TO <role>} statement.
     *
     * <p>Assumptions: a default privilege is matched as well as a direct grant, because it is the form
     * that confers a privilege on tables that do not exist yet. A check that read only the direct grants
     * would report a schema as ungranted while every future table in it was in fact reachable.</p>
     */
    private static final Pattern DEFAULT_PRIVILEGE = Pattern.compile(
            "ALTER\\s+DEFAULT\\s+PRIVILEGES\\s+FOR\\s+ROLE\\s+\\w+\\s+IN\\s+SCHEMA\\s+(\\w+)\\s+"
                    + "GRANT\\s+[\\w\\s,]+?\\s+ON\\s+\\w+\\s+TO\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE);

    /** The pinned session search path the batch service sets on every connection it opens. */
    private static final Pattern SEARCH_PATH = Pattern.compile(
            "connection-init-sql:\\s*\"SET\\s+search_path\\s+TO\\s+([\\w\\s,]+)\"");

    /** A JPA table mapping that names its schema explicitly. */
    private static final Pattern MAPPED_SCHEMA =
            Pattern.compile("@Table\\s*\\([^)]*schema\\s*=\\s*\"(\\w+)\"");

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the repository root; never {@code null}
     * @throws AssertionError if no ancestor holds the bootstrap file, which fails rather than skips
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(BOOTSTRAP_FILE))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " contains "
                + BOOTSTRAP_FILE + ", so the cross-schema privilege graph cannot be checked");
    }

    /**
     * Reads one repository file as text.
     *
     * @param relativePath the path to read, relative to the repository root; must not be {@code null}
     * @return the file's contents, never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which fails the test rather than skipping
     *     it: an unreadable subject is exactly the case in which its claims would otherwise go unchecked
     */
    private static String read(String relativePath) {
        Path file = repositoryRoot().resolve(relativePath);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Collects every schema the bootstrap script confers any privilege on to the cross-schema role.
     *
     * <p>Assumptions: the role's own schema is excluded, because a context's privileges on the schema it
     * owns are not the cross-schema exception this contract governs and would be granted whatever its
     * code reached.</p>
     *
     * @return the schema names, excluding the owned schema, never {@code null}
     */
    private static Set<String> grantedSchemas() {
        String sql = read(BOOTSTRAP_FILE);
        Set<String> schemas = new TreeSet<>();
        Matcher usage = USAGE_GRANT.matcher(sql);
        while (usage.find()) {
            if (!ROLE.equals(usage.group(2))) {
                continue;
            }
            for (String schema : usage.group(1).split(",")) {
                schemas.add(schema.trim().replace("\"", ""));
            }
        }
        for (Pattern statement : List.of(TABLE_GRANT, DEFAULT_PRIVILEGE)) {
            Matcher granted = statement.matcher(sql);
            while (granted.find()) {
                if (ROLE.equals(granted.group(2))) {
                    schemas.add(granted.group(1).trim());
                }
            }
        }
        schemas.remove(OWNED_SCHEMA);
        return schemas;
    }

    /**
     * Reads the schemas the batch service's own session search path names, in order.
     *
     * @return the schema names in the order the path declares them, never {@code null} or empty
     */
    private static List<String> searchPathSchemas() {
        Matcher path = SEARCH_PATH.matcher(read(BATCH_CONFIGURATION));
        assertThat(path.find())
                .as("no connection-init-sql search path was found in %s, so the code side of this"
                        + " comparison is unknown and the check would be vacuous", BATCH_CONFIGURATION)
                .isTrue();
        Set<String> ordered = new LinkedHashSet<>();
        for (String schema : path.group(1).split(",")) {
            ordered.add(schema.trim());
        }
        return List.copyOf(ordered);
    }

    /**
     * Collects every schema a batch entity names explicitly on its table mapping.
     *
     * @return the schema names found across the module's main sources, never {@code null}
     * @throws UncheckedIOException if the source tree cannot be walked
     */
    private static Set<String> mappedSchemas() {
        Path sources = repositoryRoot().resolve(BATCH_SOURCES);
        Set<String> schemas = new TreeSet<>();
        try (var tree = Files.walk(sources)) {
            tree.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .forEach(file -> {
                        Matcher mapped = MAPPED_SCHEMA.matcher(readFile(file));
                        while (mapped.find()) {
                            schemas.add(mapped.group(1));
                        }
                    });
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + sources, unreadable);
        }
        return schemas;
    }

    /**
     * Reads one absolute file as text.
     *
     * @param file the file to read; must not be {@code null}
     * @return its contents, never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Fails when the bootstrap grants the batch role a schema its own session path does not name.
     *
     * <p>This is the direction an operator cannot see. An unused read privilege produces no error, passes
     * every test the module runs, and is indistinguishable from a necessary one until the role is misused
     * -- which is how a {@code card} grant once survived on a role whose code never opened that schema,
     * with every row of that master carrying a primary account number. The schema is granted again today,
     * and the difference is exactly what this case measures: it is now named on the module's search path
     * and mapped by one of its entities, so the grant is reachable rather than dormant.</p>
     */
    @Test
    @DisplayName("no schema is granted to the batch role that its session search path does not name")
    void everyGrantedSchemaIsOnTheSearchPath() {
        Set<String> granted = grantedSchemas();
        List<String> path = searchPathSchemas();

        assertThat(granted)
                .as("no cross-schema grant was parsed from %s for %s, which would make this check"
                        + " vacuous; the statements or the role name have changed shape",
                        BOOTSTRAP_FILE, ROLE)
                .isNotEmpty();

        assertThat(granted)
                .as("each schema below is granted to %s in %s and is absent from that service's own"
                        + " search path in %s, so no code path can use it; remove the grant or name the"
                        + " read that needs it", ROLE, BOOTSTRAP_FILE, BATCH_CONFIGURATION)
                .isSubsetOf(path);
    }

    /**
     * Fails when the batch service's search path names a schema the bootstrap grants it nothing on.
     *
     * <p>This is the direction that fails at run time rather than silently: the module starts, resolves an
     * unqualified name into a schema it holds no privilege on, and is refused inside the nightly window
     * with a permission error.</p>
     */
    @Test
    @DisplayName("every schema on the batch search path is one the bootstrap grants that role")
    void everySearchPathSchemaIsGranted() {
        Set<String> granted = new TreeSet<>(grantedSchemas());
        granted.add(OWNED_SCHEMA);

        assertThat(new TreeSet<>(searchPathSchemas()))
                .as("each schema below is on %s's session search path and is granted nothing in %s, so an"
                        + " unqualified statement resolving into it is refused at run time inside the"
                        + " nightly window", BATCH_CONFIGURATION, BOOTSTRAP_FILE)
                .isSubsetOf(granted);
    }

    /**
     * Fails when the batch module maps an entity into a schema it is granted no privilege on.
     *
     * <p>Assumptions: an explicit {@code schema} element on a table mapping is a stronger statement of
     * intent than the search path, because it resolves regardless of the path. Checking it separately is
     * what stops a cross-schema mapping being added without the grant that makes it work -- which is the
     * failure this case actually caught: {@code com.carddemo.batch.domain.Card} was mapped onto
     * {@code card.cards} for the export's card phase while the bootstrap granted that role nothing on the
     * schema, so every statement against the entity would have been refused inside an operator-invoked
     * export. Read the other way, an entity mapping is also the evidence that justifies a cross-schema
     * grant, and the absence of one is what a withdrawal must rest on.</p>
     */
    @Test
    @DisplayName("every schema a batch entity maps explicitly is one the bootstrap grants that role")
    void everyMappedSchemaIsGranted() {
        Set<String> granted = new TreeSet<>(grantedSchemas());
        granted.add(OWNED_SCHEMA);
        Set<String> mapped = mappedSchemas();

        assertThat(mapped)
                .as("no explicitly mapped schema was found across %s, which would make this check"
                        + " vacuous", BATCH_SOURCES)
                .isNotEmpty();

        assertThat(mapped)
                .as("each schema below is named on a table mapping in %s and is granted nothing to %s in"
                        + " %s, so every statement against that entity fails with a permission error",
                        BATCH_SOURCES, ROLE, BOOTSTRAP_FILE)
                .isSubsetOf(granted);
    }
}
