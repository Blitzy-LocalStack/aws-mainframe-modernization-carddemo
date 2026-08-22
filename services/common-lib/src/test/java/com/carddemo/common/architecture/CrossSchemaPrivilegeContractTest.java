package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * <p>Refactoring Rationale: this class used to reject a live-engine check outright, and that rejection
 * was the hole a CRITICAL defect fell through. Its wording was that the engine's evaluation of an
 * access-control list "is not in doubt", which is true of the ENGINE and says nothing about whether the
 * shipped provisioning ever issues the statement -- and it did not: the batch role's {@code UPDATE} on
 * {@code account.accounts} sat inside a {@code to_regclass} guard in a script that runs before any table
 * exists, so on every freshly provisioned database the guard was false, the grant was skipped and the
 * nightly posting job failed its third write with a permission error. Every case in this class was green
 * throughout, because all three matched schema-level {@code USAGE} and none of them looked at a table
 * privilege at all. Two things follow, and both are implemented rather than noted. The two cases at the
 * foot of this class assert the TABLE-level write privileges the batch role must hold and must not hold,
 * against the files that issue them; and the live half is no longer declined -- {@code
 * com.carddemo.account.repository.BatchAccountWriteGrantIT} applies the bootstrap and the account
 * context's own migrations to a real engine and reads {@code has_table_privilege} back for the granted
 * privilege and for five that must stay refused.</p>
 *
 * <p>Alternatives Considered: moving the whole of this contract to that live test and deleting this
 * class. Rejected, and the division between the two is deliberate: what no live check can see is a grant
 * that no code path uses, because the database cannot know which privileges the application intends to
 * exercise -- an unused read privilege behaves exactly like an absent one. That is an agreement between
 * files and belongs here; whether the engine ends up holding the entry belongs there.</p>
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
@DisplayName("The batch role's cross-schema grants are exactly the schemas and tables its code reaches")
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

    /** The migration directory of the context that owns the account master, relative to the root. */
    private static final String ACCOUNT_MIGRATIONS =
            "services/account-service/src/main/resources/db/migration";

    /** The directory holding every module, so a grant in any service migration is in view. */
    private static final String SERVICES_DIRECTORY = "services";

    /** The path, within one module, at which Flyway resolves that module's migrations. */
    private static final String MIGRATION_PATH = "src/main/resources/db/migration";

    /** The schema whose write surface for the batch role is exactly one table. */
    private static final String ACCOUNT_SCHEMA = "account";

    /** The one table in that schema the posting unit of work rewrites. */
    private static final String ACCOUNT_MASTER = "accounts";

    /** The privilege the posting unit of work's third write needs on that table. */
    private static final String UPDATE_PRIVILEGE = "UPDATE";

    /**
     * Every privilege that lets a role change stored rows, in the spelling a {@code GRANT} uses.
     *
     * <p>Assumptions: both spellings of the blanket form -- {@code ALL} and {@code ALL PRIVILEGES} --
     * are listed, because either confers the whole set and {@link #privileges(String)} splits only on
     * commas, so the two-word form arrives as one token and would miss a one-word entry. A check that
     * matched only the four named verbs would pass the widest statement a single {@code GRANT} can
     * write.</p>
     */
    private static final Set<String> WRITE_PRIVILEGES =
            Set.of("INSERT", UPDATE_PRIVILEGE, "DELETE", "TRUNCATE", "ALL", "ALL PRIVILEGES");

    /**
     * One {@code GRANT <privileges> ON <schema>.<table> TO <role>} statement, all four parts captured.
     *
     * <p>Assumptions: the object is required to carry a dot, which is what separates this form from the
     * schema-wide {@code ON ALL TABLES IN SCHEMA <schema>} form matched by {@link #TABLE_GRANT}. A
     * pattern loose enough to match both would report a schema-wide grant as a named-table grant, which
     * is the exact distinction these two cases turn on.</p>
     *
     * <p>Assumptions: the optional {@code TABLE} keyword is admitted because PostgreSQL accepts
     * {@code GRANT ... ON TABLE x.y} as a synonym for {@code GRANT ... ON x.y}, so a statement written
     * in the longer form would otherwise be invisible to this check while being fully effective in the
     * engine.</p>
     */
    private static final Pattern NAMED_TABLE_GRANT = Pattern.compile(
            "GRANT\\s+([\\w\\s,]+?)\\s+ON\\s+(?:TABLE\\s+)?(\\w+)\\.(\\w+)\\s+TO\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE);

    /**
     * One {@code GRANT <privileges> ON ALL TABLES IN SCHEMA <schema> TO <role>} statement.
     *
     * <p>Assumptions: this repeats {@link #TABLE_GRANT}'s subject with the privilege list captured
     * rather than skipped. The existing pattern answers "which schemas does this role touch", for which
     * the privileges are irrelevant; the case below answers "does any statement confer a WRITE on this
     * schema wholesale", for which they are the whole question.</p>
     */
    private static final Pattern SCHEMA_WIDE_TABLE_PRIVILEGE = Pattern.compile(
            "GRANT\\s+([\\w\\s,]+?)\\s+ON\\s+ALL\\s+TABLES\\s+IN\\s+SCHEMA\\s+(\\w+)\\s+TO\\s+(\\w+)"
                    + "\\s*;",
            Pattern.CASE_INSENSITIVE);

    /**
     * One {@code ALTER DEFAULT PRIVILEGES ... IN SCHEMA <schema> GRANT <privileges> ON TABLES TO
     * <role>} statement, with the privilege list captured.
     *
     * <p>Assumptions: this is the form that reaches tables which do not exist yet, so it is the form in
     * which an over-broad write grant is easiest to introduce and hardest to see: it names no table, it
     * produces no error, and it applies to every table the schema's owner creates from then on.</p>
     */
    private static final Pattern DEFAULT_TABLE_PRIVILEGE = Pattern.compile(
            "ALTER\\s+DEFAULT\\s+PRIVILEGES\\s+FOR\\s+ROLE\\s+\\w+\\s+IN\\s+SCHEMA\\s+(\\w+)\\s+"
                    + "GRANT\\s+([\\w\\s,]+?)\\s+ON\\s+TABLES\\s+TO\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE);

    /**
     * One SQL line comment, from its leading double hyphen to the end of that line.
     *
     * <p>Assumptions: comments are removed before the two table-privilege cases match anything, and the
     * reason is specific rather than hygienic. Both files these cases read argue their grants at length,
     * and those arguments quote statement fragments -- {@code "GRANT UPDATE ON TABLES"} appears inside a
     * paragraph explaining why that form is NOT used. A check a comment can satisfy is not a check, and
     * one a comment can FAIL is worse: it would report a defect in prose.</p>
     *
     * <p>Trade-offs: a line comment is stripped wherever it appears, including inside a string literal
     * that happened to contain a double hyphen. No statement in either file carries such a literal, and
     * accepting that risk buys a stripper of one line instead of a tokenizer; the alternative considered
     * was parsing the SQL properly, which is a dependency and a grammar for a check whose subject is
     * four statement shapes.</p>
     */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

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

    /**
     * Fails when no migration owned by the account context grants the batch role {@code UPDATE} on the
     * account master.
     *
     * <p><b>Purpose.</b> The posting unit of work performs three writes and commits them together: the
     * category balance and the posted transaction in {@code ledger}, and the account master here. The
     * first two ride a default privilege, which reaches a table created later; the third cannot, because
     * a default privilege is unable to name a table and naming one table is the whole point -- {@code
     * account.customers} carries the encrypted national identifier and no batch step has any business
     * rewriting it. A privilege that must name a table can only be granted after the table exists and
     * only by a session that owns it, and exactly one artifact satisfies both: a migration in the
     * account context's own chain, which Flyway applies after {@code V1__account.sql} under {@code
     * SET ROLE carddemo_account_owner}.</p>
     *
     * <p>Assumptions: the whole of the account context's migration directory is searched rather than one
     * file name, so the grant may be renumbered or moved between migrations without this case having to
     * be edited. What it holds is the property -- the account context issues it -- and not the file.</p>
     *
     * <p>Assumptions: the bootstrap's own guarded block does NOT satisfy this case, and that is the
     * point of reading only this directory. That block is real and is retained as a safety net for a
     * re-run against an already-migrated database, but it is skipped on a fresh one, which is the state
     * every new environment starts in.</p>
     */
    @Test
    @DisplayName("a migration owned by the account context grants the batch role UPDATE on the master")
    void theAccountContextGrantsTheBatchRoleUpdateOnTheAccountMaster() {
        List<String> granting = new ArrayList<>();
        for (Path migration : sqlFilesIn(repositoryRoot().resolve(ACCOUNT_MIGRATIONS))) {
            Matcher grant = NAMED_TABLE_GRANT.matcher(withoutComments(readFile(migration)));
            while (grant.find()) {
                if (ROLE.equals(grant.group(4))
                        && ACCOUNT_SCHEMA.equalsIgnoreCase(grant.group(2))
                        && ACCOUNT_MASTER.equalsIgnoreCase(grant.group(3))
                        && privileges(grant.group(1)).contains(UPDATE_PRIVILEGE)) {
                    granting.add(migration.getFileName().toString());
                }
            }
        }

        assertThat(granting)
                .as("no migration under %s grants %s UPDATE on %s.%s. The posting job's third write"
                        + " (app/cbl/CBTRN02C.cbl L554) and the interest job's account rewrite"
                        + " (app/cbl/CBACT04C.cbl L356) are then refused at run time inside the nightly"
                        + " window with a permission error naming the table, on every freshly"
                        + " provisioned database. %s cannot carry this grant on a first run: it names a"
                        + " table, and that script executes before any table exists",
                        ACCOUNT_MIGRATIONS, ROLE, ACCOUNT_SCHEMA, ACCOUNT_MASTER, BOOTSTRAP_FILE)
                .isNotEmpty();
    }

    /**
     * Fails when any statement in the shipped provisioning confers a write on an account table other
     * than the master, or confers one on the whole account schema, to the batch role.
     *
     * <p><b>Purpose.</b> This is the other half of the case above, and without it that case would be
     * satisfied by the widest possible grant. The narrow surface is the reason the cross-schema
     * exception is acceptable at all: the nightly chain reads all three account records and rewrites
     * exactly one of them, so {@code INSERT}, {@code DELETE} and {@code TRUNCATE} are withheld
     * everywhere and {@code UPDATE} is withheld on {@code customers} and {@code card_xref}. A schema-wide
     * form -- either {@code ON ALL TABLES IN SCHEMA} or a default privilege -- would also hand over
     * every table the schema gains in future, with nothing in the file changing to say so.</p>
     *
     * <p>Assumptions: every service's migration directory is searched, not only the account context's.
     * A grant is legal wherever its issuing session owns the object, so a widening statement could be
     * introduced in any chain, and a check that read one directory would be satisfied by the file it
     * happened to read.</p>
     */
    @Test
    @DisplayName("no account table besides the master, and no account default, is writable by batch")
    void noAccountTableBesidesTheMasterIsWritableByTheBatchRole() {
        List<String> widened = new ArrayList<>();
        for (Path file : provisioningSql()) {
            String sql = withoutComments(readFile(file));
            String source = repositoryRoot().relativize(file).toString();

            Matcher named = NAMED_TABLE_GRANT.matcher(sql);
            while (named.find()) {
                if (!ROLE.equals(named.group(4))
                        || !ACCOUNT_SCHEMA.equalsIgnoreCase(named.group(2))) {
                    continue;
                }
                Set<String> conferred = privileges(named.group(1));
                conferred.retainAll(WRITE_PRIVILEGES);
                if (conferred.isEmpty()) {
                    continue;
                }
                if (!ACCOUNT_MASTER.equalsIgnoreCase(named.group(3))) {
                    widened.add(source + " grants " + conferred + " on " + ACCOUNT_SCHEMA + "."
                            + named.group(3));
                } else if (!Set.of(UPDATE_PRIVILEGE).equals(conferred)) {
                    widened.add(source + " grants " + conferred + " on " + ACCOUNT_SCHEMA + "."
                            + ACCOUNT_MASTER + ", where UPDATE alone is the contracted privilege");
                }
            }

            widened.addAll(schemaWideWrites(sql, source, SCHEMA_WIDE_TABLE_PRIVILEGE, 2, 1, 3,
                    "GRANT ... ON ALL TABLES IN SCHEMA " + ACCOUNT_SCHEMA));
            widened.addAll(schemaWideWrites(sql, source, DEFAULT_TABLE_PRIVILEGE, 1, 2, 3,
                    "ALTER DEFAULT PRIVILEGES ... IN SCHEMA " + ACCOUNT_SCHEMA + " GRANT ... ON TABLES"));
        }

        assertThat(widened)
                .as("each statement below hands %s a write privilege on the account schema wider than"
                        + " the one table the posting and interest jobs rewrite. The narrowness is the"
                        + " reason the cross-schema exception is acceptable: account.customers carries"
                        + " the encrypted national and government-issued identifiers and no batch step"
                        + " modifies it, and a schema-wide form additionally covers every table the"
                        + " schema gains later", ROLE)
                .isEmpty();
    }

    /**
     * Collects the provisioning SQL both table-privilege cases read: the bootstrap and every service
     * migration.
     *
     * @return the files in a stable order, never {@code null} or empty
     * @throws UncheckedIOException if the services tree cannot be listed
     */
    private static List<Path> provisioningSql() {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        files.add(root.resolve(BOOTSTRAP_FILE));
        Path services = root.resolve(SERVICES_DIRECTORY);
        try (var modules = Files.list(services)) {
            modules.filter(Files::isDirectory)
                    .sorted()
                    .forEach(module -> files.addAll(sqlFilesIn(module.resolve(MIGRATION_PATH))));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + services, unreadable);
        }
        return List.copyOf(files);
    }

    /**
     * Lists the SQL files one directory holds, in name order.
     *
     * @param directory the directory to list; a module owning no migration has none, which is not an
     *     error and yields an empty list
     * @return the SQL files it holds, never {@code null}
     * @throws UncheckedIOException if the directory exists and cannot be listed
     */
    private static List<Path> sqlFilesIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Reports every schema-wide statement in one file that confers a write on the account schema to the
     * cross-schema role.
     *
     * @param sql the file's contents with comments already removed; must not be {@code null}
     * @param source the repository-relative path of that file, named in each report; must not be
     *     {@code null}
     * @param statement the pattern matching the statement shape to examine; must not be {@code null}
     * @param schemaGroup the capture group holding the schema name
     * @param privilegeGroup the capture group holding the comma-separated privilege list
     * @param roleGroup the capture group holding the grantee role name
     * @param shape the statement shape as a reader would write it, for the report
     * @return one entry per offending statement, empty when the file carries none; never {@code null}
     */
    private static List<String> schemaWideWrites(String sql, String source, Pattern statement,
            int schemaGroup, int privilegeGroup, int roleGroup, String shape) {
        List<String> offenders = new ArrayList<>();
        Matcher matched = statement.matcher(sql);
        while (matched.find()) {
            if (!ROLE.equals(matched.group(roleGroup))
                    || !ACCOUNT_SCHEMA.equalsIgnoreCase(matched.group(schemaGroup))) {
                continue;
            }
            Set<String> conferred = privileges(matched.group(privilegeGroup));
            conferred.retainAll(WRITE_PRIVILEGES);
            if (!conferred.isEmpty()) {
                offenders.add(source + " confers " + conferred + " through " + shape);
            }
        }
        return offenders;
    }

    /**
     * Splits a {@code GRANT} statement's privilege list into upper-case privilege names.
     *
     * <p>Assumptions: the list is upper-cased because SQL keywords are case-insensitive and both files
     * read here write them upper-case by convention; a statement written {@code grant update} confers
     * the same privilege and must be counted the same way.</p>
     *
     * @param clause the comma-separated privilege list as written, such as {@code "SELECT, UPDATE"};
     *     must not be {@code null}
     * @return the privilege names it lists, as a mutable set the caller may intersect; never
     *     {@code null}
     */
    private static Set<String> privileges(String clause) {
        Set<String> named = new TreeSet<>();
        for (String privilege : clause.split(",")) {
            String trimmed = privilege.trim().toUpperCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                named.add(trimmed);
            }
        }
        return named;
    }

    /**
     * Removes every SQL line comment from a script, so only executable text is matched.
     *
     * <p>Assumptions: each comment is replaced by nothing rather than by a space, and the newline that
     * terminated it is left in place, so statement boundaries and the line structure a reader would see
     * both survive. Replacing the comment with a space would join a statement to the one below it only
     * if the newline were consumed as well, which the pattern deliberately does not do.</p>
     *
     * @param sql the script as read from disk; must not be {@code null}
     * @return the same script with its line comments removed, never {@code null}
     */
    private static String withoutComments(String sql) {
        return LINE_COMMENT.matcher(sql).replaceAll("");
    }
}
