package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Re-derives the service catalog's measurable current-state claims from the repository.
 *
 * <p><b>Purpose.</b> {@code docs/architecture/service-catalog.md} is the migration's naming
 * authority, and it reported the delivered tree as it stood several checkpoints earlier: two modules
 * with main-source Java rather than nine, no per-module READMEs, no Flyway migration in the
 * authorization context, no architecture test. Each of those was accurate when written and false when
 * read, which is the failure mode a prose current-state section has by construction -- the change that
 * falsifies it never touches it. This test measures the tree and holds the catalog's three countable
 * claims to it.</p>
 *
 * <p>Alternatives Considered: deleting the current-state figures from the catalog and pointing a
 * reader at the source tree. Rejected because the catalog's job is to be the one place a reader can
 * see the whole delivery at once, and a document that refuses to say what is delivered cannot do that
 * job. Mechanising the figures keeps the summary and removes the staleness.</p>
 *
 * <p>Trade-offs: only the claims that are countable are checked -- the per-module class and migration
 * table, the per-module READMEs, and the Terraform directory count. Prose claims about design
 * intent are not, and cannot be, checked here. Those are the claims a reviewer must still read, and
 * narrowing what a reader has to verify by hand to exactly that set is the point.</p>
 */
@DisplayName("The service catalog's countable current-state claims match the repository")
final class ServiceCatalogInventoryTest {

    /** The catalog, which is also the marker used to locate the repository root. */
    private static final String CATALOG = "docs/architecture/service-catalog.md";

    /** Root of the Maven reactor whose modules the catalog tabulates. */
    private static final String SERVICES_ROOT = "services";

    /** Path, relative to a module, of the Flyway migration directory. */
    private static final String MIGRATION_PATH = "src/main/resources/db/migration";

    /** Path, relative to a module, of the production source root. */
    private static final String MAIN_SOURCE_PATH = "src/main/java";

    /** File name every package charter carries, excluded from every class count. */
    private static final String CHARTER_FILE_NAME = "package-info.java";

    /** Rendering the table uses for a module that owns no migration. */
    private static final String NO_MIGRATIONS = "none";

    /** Matches one row of the implementation-status table. */
    private static final Pattern MODULE_ROW = Pattern.compile(
            "^\\| `([a-z-]+)` \\| (\\d+) \\| (.+?) \\|$", Pattern.MULTILINE);

    /** Matches a migration file name inside a table cell. */
    private static final Pattern MIGRATION_NAME = Pattern.compile("`(V\\d+__[a-z_]+\\.sql)`");

    /** Matches the sentence counting the Terraform directories that carry generated contracts. */
    private static final Pattern TERRAFORM_DIRECTORIES =
            Pattern.compile("\\*\\*passes\\*\\* for ([a-z]+) documented Terraform directories");

    /** Spelled numbers the Terraform sentence may use, indexed by value. */
    private static final List<String> SPELLED = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "twenty-one", "twenty-two");

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the repository root; never {@code null}
     * @throws AssertionError if no ancestor holds the catalog, which fails rather than skips
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(CATALOG))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " contains "
                + CATALOG + ", so the catalog's claims cannot be checked");
    }

    /**
     * Reads one repository file as text.
     *
     * @param relative the path relative to the repository root
     * @return the file's contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String read(String relative) {
        Path file = repositoryRoot().resolve(relative);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Counts production classes beneath one module's main source root.
     *
     * @param module the module directory
     * @return the number of {@code .java} files that are not package charters
     * @throws UncheckedIOException if the tree cannot be walked
     */
    private static int countMainClasses(Path module) {
        Path source = module.resolve(MAIN_SOURCE_PATH);
        try (Stream<Path> tree = Files.walk(source)) {
            return (int) tree.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !name.equals(CHARTER_FILE_NAME))
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + source, unreadable);
        }
    }

    /**
     * Lists the Flyway migration file names one module owns.
     *
     * @param module the module directory
     * @return the migration file names in applied order, empty when the module owns none
     * @throws UncheckedIOException if the directory exists and cannot be listed
     */
    private static List<String> migrations(Path module) {
        Path directory = module.resolve(MIGRATION_PATH);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .forEach(names::add);
            return names;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Parses the catalog's implementation-status table.
     *
     * @return module name to its stated class count and stated migration cell
     * @throws AssertionError if the table parses to nothing, which would make the checks vacuous
     */
    private static Map<String, String[]> statedRows() {
        Map<String, String[]> rows = new LinkedHashMap<>();
        Matcher row = MODULE_ROW.matcher(read(CATALOG));
        while (row.find()) {
            rows.put(row.group(1), new String[] {row.group(2), row.group(3)});
        }
        assertThat(rows)
                .as("%s must carry an implementation-status table shaped"
                        + " '| `module` | <n> | <migrations> |', or this test is vacuous", CATALOG)
                .isNotEmpty();
        return rows;
    }

    /**
     * Confirms the implementation-status table names every reactor module and no absent one.
     *
     * <p>Assumptions: both directions are checked in one method. The failure that occurred was a
     * table describing a subset of the reactor as though it were the whole of it, which a
     * one-directional check on the rows alone would not have seen.</p>
     *
     * @throws UncheckedIOException if the reactor root cannot be listed, which fails the test
     *     rather than skipping it: an unlistable reactor is the case in which the table would
     *     otherwise go unchecked
     */
    @Test
    @DisplayName("the implementation-status table covers exactly the reactor's modules")
    void theTableCoversExactlyTheReactorsModules() {
        Path services = repositoryRoot().resolve(SERVICES_ROOT);
        List<String> measured = new ArrayList<>();
        try (Stream<Path> children = Files.list(services)) {
            children.filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve("pom.xml")))
                    .map(child -> child.getFileName().toString())
                    .sorted()
                    .forEach(measured::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + services, unreadable);
        }
        assertThat(statedRows().keySet())
                .as("modules tabulated by %s, against the reactor directories that carry a pom",
                        CATALOG)
                .containsExactlyInAnyOrderElementsOf(measured);
    }

    /**
     * Confirms each row's class count and migration list against the module it names.
     *
     * <p>Assumptions: a module owning no migration must say so with the word "none" rather than with
     * an empty cell, so that an omission is distinguishable from a deliberate absence -- the
     * reporting context owns no table by design and that is a decision, not a gap.</p>
     */
    @Test
    @DisplayName("each row's class count and migration list match the module it names")
    void everyRowMatchesItsModule() {
        Path services = repositoryRoot().resolve(SERVICES_ROOT);
        statedRows().forEach((module, stated) -> {
            Path directory = services.resolve(module);
            assertThat(Integer.parseInt(stated[0]))
                    .as("main-source classes stated by %s for %s", CATALOG, module)
                    .isEqualTo(countMainClasses(directory));

            List<String> named = new ArrayList<>();
            Matcher migration = MIGRATION_NAME.matcher(stated[1]);
            while (migration.find()) {
                named.add(migration.group(1));
            }
            List<String> present = migrations(directory);
            assertThat(named)
                    .as("migrations named by %s for %s", CATALOG, module)
                    .containsExactlyInAnyOrderElementsOf(present);
            if (present.isEmpty()) {
                assertThat(stated[1])
                        .as("%s must record %s's absence of a migration explicitly", CATALOG, module)
                        .contains(NO_MIGRATIONS);
            }
        });
    }

    /**
     * Confirms every module the catalog names carries the README the catalog says it does.
     *
     * <p>Assumptions: the catalog's naming-authority preamble states that the per-module READMEs use
     * the canonical service names, which is a claim that those files exist. It said the opposite for
     * several checkpoints after they landed, so the existence is checked rather than described.</p>
     */
    @Test
    @DisplayName("every tabulated module carries a README, as the naming-authority preamble claims")
    void everyModuleCarriesItsReadme() {
        Path services = repositoryRoot().resolve(SERVICES_ROOT);
        statedRows().keySet().forEach(module ->
                assertThat(services.resolve(module).resolve("README.md"))
                        .as("%s names %s as a module whose README uses the canonical service name",
                                CATALOG, module)
                        .exists());
    }

    /**
     * Confirms the Terraform directory count against the directories that carry a generated contract.
     *
     * <p>Assumptions: a documented Terraform directory is one holding both a {@code .tf} file and a
     * {@code README.md}, because that is the pair the generator's check operates on. Counting
     * READMEs alone would include the infrastructure tree's own top-level guide, which the generator
     * does not process.</p>
     *
     * @throws UncheckedIOException if the infrastructure tree cannot be walked, which fails the test
     *     rather than skipping it
     */
    @Test
    @DisplayName("the documented-Terraform-directory count equals the directories that qualify")
    void theTerraformDirectoryCountMatchesTheTree() {
        Path infra = repositoryRoot().resolve("infra");
        int qualifying;
        try (Stream<Path> tree = Files.walk(infra)) {
            qualifying = (int) tree.filter(Files::isDirectory)
                    .filter(directory -> Files.isRegularFile(directory.resolve("README.md")))
                    .filter(ServiceCatalogInventoryTest::holdsTerraform)
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + infra, unreadable);
        }

        Matcher stated = TERRAFORM_DIRECTORIES.matcher(read(CATALOG));
        assertThat(stated.find())
                .as("%s must state how many Terraform directories carry a generated contract",
                        CATALOG)
                .isTrue();
        assertThat(SPELLED.indexOf(stated.group(1)))
                .as("Terraform directory count stated by %s as '%s'", CATALOG, stated.group(1))
                .isEqualTo(qualifying);
    }

    /**
     * Reports whether one directory holds at least one Terraform source file.
     *
     * @param directory the directory to inspect
     * @return {@code true} when a {@code .tf} file sits directly inside it
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static boolean holdsTerraform(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".tf"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }
}
