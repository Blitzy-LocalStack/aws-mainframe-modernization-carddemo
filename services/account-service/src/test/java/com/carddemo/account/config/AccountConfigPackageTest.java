package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the configuration package's closed-set claim against the directory it describes.
 *
 * <p>Purpose: {@code package-info.java} states that this package is "closed at seven configuration
 * classes" and enumerates them. That claim has been wrong twice — first headed "four" while five classes
 * existed, then "five" while seven existed — and neither error failed a build, because a charter is prose
 * and no compiler reads it. This class is the comparison, so the census fails here instead of being
 * re-read by eye at review time.
 *
 * <p>Refactoring Rationale: the charter is checked in BOTH directions rather than only for
 * under-counting. A charter that named a class the directory does not hold would send a reader looking
 * for a file that is not there, and a charter that omitted one instructs a reader — through the closure
 * sentence beside the list — to treat the extra file as not belonging and remove it. Neither direction
 * fails a build on its own: removing the internal filter chain makes the two consuming contexts' reads
 * refuse authentication, and removing the protected-identifier bean makes any refresh that scans the
 * mapper package fail to start.
 *
 * <p>Assumptions: the stated NUMBER is asserted separately from the stated NAMES, because the two can
 * disagree — a charter can enumerate seven members under a heading that says five, which is precisely
 * how the second of the two errors arose.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.
 */
class AccountConfigPackageTest {

    /**
     * The charter under test, relative to the repository root.
     */
    private static final String CHARTER =
            "services/account-service/src/main/java/com/carddemo/account/config/package-info.java";

    /**
     * The directory the charter describes, relative to the repository root.
     */
    private static final String CONFIG_DIRECTORY =
            "services/account-service/src/main/java/com/carddemo/account/config";

    /**
     * The count the charter states, spelled as the charter spells it.
     *
     * <p>Assumptions: the word rather than the digit, because that is the form the charter uses in every
     * one of the three places it states the number, and matching the digit would pass against a charter
     * whose prose still said "five".</p>
     */
    private static final String STATED_COUNT = "seven";

    /**
     * The count the charter states, as a number, for comparison against the directory.
     */
    private static final int EXPECTED_MEMBERS = 7;

    /**
     * The name of the charter file itself, which is not one of the configuration classes it counts.
     */
    private static final String CHARTER_FILE_NAME = "package-info.java";

    /**
     * Every configuration class the directory holds appears in the charter, and no other name does.
     *
     * @throws IOException if the charter or the directory it describes cannot be read, which fails the
     *     case rather than being handled, because a census that cannot see the tree has proved nothing
     */
    @Test
    @DisplayName("the configuration charter enumerates exactly the classes the directory holds")
    void theCharterEnumeratesExactlyTheClassesTheDirectoryHolds() throws IOException {
        String charter = Files.readString(repositoryFile(CHARTER), StandardCharsets.UTF_8);
        Set<String> present = configurationClassNames();

        assertThat(present)
                .as("the charter's stated count must match the directory")
                .hasSize(EXPECTED_MEMBERS);

        for (String className : present) {
            assertThat(charter)
                    .as("%s exists in the configuration package and must be named by its charter",
                            className)
                    .contains("{@code " + className + "}");
        }
    }

    /**
     * The charter states its count in words, and the number it states is the number of members.
     *
     * @throws IOException if the charter cannot be read, which fails the case rather than being handled
     */
    @Test
    @DisplayName("the configuration charter states the count the directory actually holds")
    void theCharterStatesTheCountTheDirectoryActuallyHolds() throws IOException {
        String charter = Files.readString(repositoryFile(CHARTER), StandardCharsets.UTF_8);

        assertThat(charter)
                .as("the charter's closure sentence must state the member count in words")
                .contains("closed at " + STATED_COUNT + " configuration")
                .contains("closed set of " + STATED_COUNT + " configuration");

        // WHY : Assumptions: the superseded spellings are refused explicitly rather than left to the
        //       positive assertions above. A charter can carry both a corrected heading and a stale
        //       sentence elsewhere in the same file -- which is how the "five" heading survived beside a
        //       list of five while two further classes sat in the directory -- and a positive contains
        //       check passes in exactly that state.
        assertThat(charter)
                .as("no superseded member count may survive anywhere in the charter")
                .doesNotContain("closed at four")
                .doesNotContain("closed set of four configuration")
                .doesNotContain("closed at five configuration")
                .doesNotContain("closed set of five configuration");
    }

    /**
     * Lists the simple class names of every configuration class in the package.
     *
     * @return the names without their {@code .java} suffix, in a stable order, never {@code null}
     * @throws IOException if the directory cannot be listed
     */
    private static Set<String> configurationClassNames() throws IOException {
        try (Stream<Path> entries = Files.list(repositoryFile(CONFIG_DIRECTORY))) {
            return entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !CHARTER_FILE_NAME.equals(name))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    /**
     * Resolves a repository-relative path from whichever directory the build runs this class in.
     *
     * <p>Assumptions: the walk is upward from the working directory rather than a fixed relative path,
     * because this class runs with the module directory as the working directory under Maven and with
     * the repository root under some development environments. A fixed prefix would pass in one and fail
     * in the other for a reason unrelated to the charter.</p>
     *
     * @param relative the repository-root-relative path; must not be {@code null}
     * @return the resolved absolute path, never {@code null}
     * @throws AssertionError if no ancestor of the working directory holds the path, which is a failure
     *     of the environment rather than of the charter and is reported as such
     */
    private static Path repositoryFile(String relative) {
        Path candidate = Path.of("").toAbsolutePath();
        for (Path directory = candidate; directory != null; directory = directory.getParent()) {
            Path resolved = directory.resolve(relative);
            if (Files.exists(resolved)) {
                return resolved;
            }
        }
        throw new AssertionError("no ancestor of " + candidate + " contains " + relative
                + "; the charter census cannot run without the repository tree");
    }

    /**
     * Reports the names this class would compare, for a maintainer reading a failure.
     *
     * @return the sorted member names, never {@code null}
     * @throws IOException if the directory cannot be listed
     */
    static List<String> members() throws IOException {
        return List.copyOf(configurationClassNames());
    }
}
