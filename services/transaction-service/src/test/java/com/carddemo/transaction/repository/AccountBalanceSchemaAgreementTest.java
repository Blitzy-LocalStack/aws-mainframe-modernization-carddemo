package com.carddemo.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Confirms the columns this module reaches in another context's table exist in that context's migration.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@link AccountBalanceRepository} issues two native statements against {@code account.accounts}, a
 * table {@code account-service} owns and this module holds only a named grant on. It maps no entity, for
 * the reasons that class records, so the persistence provider's start-up schema check does not cover the
 * three columns those statements name. Without a compensating control a rename in the owning migration
 * would surface as a failing statement at the first bill payment, inside a request, rather than as a
 * failing build.</p>
 *
 * <p>Alternatives Considered: mapping a JPA entity in this module so {@code ddl-auto: validate} would cover
 * the columns. Rejected on the grounds the repository records -- an entity here would either restate a
 * twenty-column contract this module does not own, free to drift from the migration that does, or map a
 * subset and be a projection wearing an entity's annotations. Alternatives Considered: asserting the
 * columns against a running database in an integration test. Rejected as the ONLY control, because that
 * database is provisioned from the same migration and so agrees by construction; it proves the statements
 * execute, which the sibling integration test does assert, and not that the two artifacts describe one
 * table. This test reads the owning migration as TEXT, which is the only way the disagreement is
 * detectable at build time.</p>
 *
 * <p>Trade-offs: reading a sibling module's resource from a test couples this module's build to that file's
 * PATH, so moving the migration breaks this test. That is accepted deliberately and is the lesser cost: the
 * coupling is already real -- two statements depend on that file's content -- and a broken test names it,
 * where an absent test leaves it to be discovered by a failing payment.</p>
 */
class AccountBalanceSchemaAgreementTest {

    /** The owning context's migration, which is normative for the table this module reaches. */
    private static final String OWNING_MIGRATION =
            "services/account-service/src/main/resources/db/migration/V1__account.sql";

    /**
     * Confirms every column the two statements name is declared by the owning migration.
     *
     * <p>Assumptions: the migration is searched for a column DECLARATION rather than for the column name
     * anywhere in the file, because that file is heavily commented and a name occurring in prose would
     * satisfy a bare containment check. A declaration is matched as the name at the start of a line
     * followed by whitespace and a type keyword, which is the shape the migration writes.</p>
     *
     * <p>Assumptions: the column names are read from the repository's own published constants rather than
     * written again here. A copy in this file could agree with the migration while disagreeing with the
     * statements, which is precisely the drift the test exists to catch.</p>
     */
    @Test
    @DisplayName("every column the balance statements name is declared by the owning migration")
    void everyColumnTheStatementsNameIsDeclaredByTheOwningMigration() {
        String migration = read(OWNING_MIGRATION);

        assertThat(List.of(AccountBalanceRepository.COLUMN_ACCOUNT_ID,
                        AccountBalanceRepository.COLUMN_CURRENT_BALANCE,
                        AccountBalanceRepository.COLUMN_VERSION))
                .allSatisfy(column -> assertThat(declaresColumn(migration, column))
                        .as("%s must declare a column named %s, because"
                                + " AccountBalanceRepository names it in a statement", OWNING_MIGRATION,
                                column)
                        .isTrue());
    }

    /**
     * Confirms the table the statements address is the table the owning migration creates.
     *
     * <p>Assumptions: the qualified name is asserted rather than the bare table name, because the grant
     * this module holds is on one schema-qualified relation and a statement resolving through a search path
     * could reach a different one. The migration is searched for the same qualified spelling the statements
     * use, so the two are established to name one relation.</p>
     */
    @Test
    @DisplayName("the addressed table is the one the owning migration creates, schema and all")
    void theAddressedTableIsTheOneTheOwningMigrationCreates() {
        String migration = read(OWNING_MIGRATION).toLowerCase(Locale.ROOT);

        assertThat(AccountBalanceRepository.TABLE)
                .as("the statements must name a schema-qualified relation, never a bare one")
                .contains(".");
        assertThat(migration)
                .as("%s must create %s", OWNING_MIGRATION, AccountBalanceRepository.TABLE)
                .contains("create table " + AccountBalanceRepository.TABLE);
    }

    /**
     * Reports whether a migration declares a column of a given name.
     *
     * <p>Assumptions: the match is anchored at the start of a line and requires a following type token, so
     * a mention of the name in a comment does not satisfy it. The comparison folds case because SQL
     * keywords in that file are upper case while column names are lower case.</p>
     *
     * @param migration the migration text; must not be {@code null}
     * @param column the column name to look for; must not be {@code null}
     * @return {@code true} when the migration declares that column, {@code false} otherwise
     */
    private static boolean declaresColumn(String migration, String column) {
        return migration.lines()
                .map(line -> line.strip().toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.startsWith(column + " ")
                        && line.substring(column.length()).strip().matches("^(bigint|numeric|char|integer|smallint|date|timestamp|bytea|varchar).*"));
    }

    /**
     * Reads a repository-relative file.
     *
     * <p>Assumptions: the repository root is located by walking up from the working directory looking for
     * the two top-level directories every checkout has, rather than by a relative path from this module.
     * A relative path would break when the reactor is invoked from the repository root instead of from the
     * module, and both invocations are used.</p>
     *
     * @param relativePath the path from the repository root; must not be {@code null}
     * @return the file's contents, never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which is itself the finding
     */
    private static String read(String relativePath) {
        try {
            return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "could not read " + relativePath + ", which this module's statements depend on",
                    unreadable);
        }
    }

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the first ancestor holding both {@code infra} and {@code services}, never {@code null}
     * @throws IllegalStateException if no ancestor qualifies
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("infra"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory holds both infra/ and services/");
    }
}
