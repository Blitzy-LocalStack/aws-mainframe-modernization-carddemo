package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every package charter's stated file count to the directory the charter lives in.
 *
 * <p><b>Purpose.</b> Package charters across the service tree publish closed inventories of their own
 * directory -- "exactly four classes", "three source files and no more", "five Java files, four of
 * them tests". Those figures are the ones the review found stale, and they go stale for a structural
 * reason rather than through carelessness: the figure lives in a different file from the thing it
 * counts, so the change that falsifies it never touches it. This test reads the figure and counts the
 * directory, so the next such change fails the build instead of publishing a false document.</p>
 *
 * <p>A charter opts in by carrying one line in a canonical shape:</p>
 *
 * <pre>
 * this directory: 5 java files = 4 classes + 1 charter
 * this directory: 6 java files = 5 tests + 1 charter
 * this directory: 3 java files = 2 classes + 1 charter (planned: SqsConfig)
 * </pre>
 *
 * <p>The optional planned clause exists because one charter in the tree legitimately enumerates a
 * member the migration plan assigns to a later index, and its entry is a contract rather than a
 * description. Naming it in the marker keeps that honest in both directions: the named class is
 * excluded from the must-exist check, and it is asserted ABSENT, so a planned entry left standing
 * after the class lands fails the build. That is exactly the drift the review found -- a charter
 * reporting a class as outstanding while the file sat beside it.</p>
 *
 * <p>Alternatives Considered: parsing the counts out of each charter's own prose, so that no charter
 * needs a marker line. Rejected because the prose forms are genuinely different from one another --
 * one spells its numbers as words, one states a target and its delta, one gives a table -- and a
 * parser loose enough to read all of them would also match sentences that merely mention a number,
 * making its verdict untrustworthy in both directions. A single canonical line is a small imposition
 * on the charter and it is unambiguous.</p>
 *
 * <p>Alternatives Considered: one such test inside each service module. Rejected because the check is
 * identical in all of them, so nine copies would be nine files to keep in step, and the precedent for
 * a repository-wide document check already sits in this package -- {@code RuntimeConfigurationContractTest}
 * reads infrastructure sources from here for the same reason. Assumptions: reading another module's
 * FILES is not importing its types, so the shared kernel's inward-only dependency rule is untouched;
 * nothing in this class references a service package in any form.</p>
 *
 * <p>Trade-offs: a charter that carries no marker line is not checked at all, and this test cannot
 * tell such a charter from one that never made a count claim. It therefore asserts a floor on how many
 * markers it found rather than accepting whatever it happens to see: a change that deletes a marker to
 * silence a failure has to also lower that floor, which is a visible act in review rather than an
 * invisible omission.</p>
 */
@DisplayName("Every package charter's stated file count matches the directory it describes")
final class PackageCharterInventoryTest {

    /** Marker used to locate the repository root, being a file this test also reads. */
    private static final String ROOT_MARKER =
            "services/common-lib/src/main/java/com/carddemo/common/package-info.java";

    /** Root of the service tree the walk covers. */
    private static final String SERVICES_ROOT = "services";

    /** File name every package charter carries. */
    private static final String CHARTER_FILE_NAME = "package-info.java";

    /**
     * Fewest marker lines this test must find for its verdict to mean anything.
     *
     * <p>Assumptions: this is a floor and not an exact count, so adopting the marker in a further
     * charter does not fail the build, while removing one from a charter that has it does. Those are
     * the two directions that matter: adoption is the desired direction and needs no ceremony, and
     * removal is how a failing assertion would be silenced.</p>
     */
    private static final int MINIMUM_MARKED_CHARTERS = 4;

    /** Matches the canonical marker line, whose three figures must agree with the directory. */
    private static final Pattern DIRECTORY_MARKER = Pattern.compile(
            "^ \\* this directory: (\\d+) java files = (\\d+) (?:classes|tests) \\+ 1 charter"
                    + "(?: \\(planned: ([A-Za-z0-9]+(?:, [A-Za-z0-9]+)*)\\))?$",
            Pattern.MULTILINE);

    /** Separator between names inside a marker line's planned clause. */
    private static final String PLANNED_SEPARATOR = ", ";

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the repository root; never {@code null}
     * @throws AssertionError if no ancestor holds the marker file, which fails rather than skips
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " contains "
                + ROOT_MARKER + ", so package charters cannot be checked");
    }

    /**
     * Lists every package charter beneath the service tree.
     *
     * @return absolute paths of every {@code package-info.java} under {@code services/}
     * @throws UncheckedIOException if the tree cannot be walked
     */
    private static List<Path> charters() {
        Path root = repositoryRoot().resolve(SERVICES_ROOT);
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> found = new ArrayList<>();
            tree.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().equals(CHARTER_FILE_NAME))
                    .sorted()
                    .forEach(found::add);
            return found;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
    }

    /**
     * Counts the {@code .java} files directly inside one directory.
     *
     * @param directory the directory to count in
     * @return the number of {@code .java} files, the charter included
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static int countJavaFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Extracts the class names one marker line declares planned rather than present.
     *
     * @param marker a matched marker line
     * @return the planned class names, empty when the clause is absent
     */
    private static List<String> plannedNames(Matcher marker) {
        String clause = marker.group(3);
        if (clause == null) {
            return List.of();
        }
        return List.of(clause.split(PLANNED_SEPARATOR));
    }

    /**
     * Confirms every marked charter's three figures against its own directory.
     *
     * <p>Assumptions: all three figures are checked, not just the total. The two failures the review
     * found were of different kinds -- one directory held a class the charter's enumeration omitted
     * while its total happened to be quotable, and another named a class under a name no file carries
     * -- so a check on the total alone would have caught one of them and missed the other.</p>
     *
     * @throws UncheckedIOException if a charter or its directory cannot be read, which fails the
     *     test rather than skipping it: an unreadable charter is exactly the case in which its
     *     claims would otherwise go unchecked
     */
    @Test
    @DisplayName("each marker line's file count, member count and charter count match the directory")
    void everyMarkedCharterMatchesItsDirectory() {
        int marked = 0;
        for (Path charter : charters()) {
            Path directory = charter.getParent();
            String text;
            try {
                text = Files.readString(charter, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + charter, unreadable);
            }
            Matcher marker = DIRECTORY_MARKER.matcher(text);
            while (marker.find()) {
                marked++;
                int files = countJavaFiles(directory);
                assertThat(Integer.parseInt(marker.group(1)))
                        .as("java files claimed by %s for its own directory", charter)
                        .isEqualTo(files);
                assertThat(Integer.parseInt(marker.group(2)))
                        .as("members besides the charter claimed by %s for its own directory",
                                charter)
                        .isEqualTo(files - 1);
                for (String planned : plannedNames(marker)) {
                    assertThat(directory.resolve(planned + ".java"))
                            .as("%s declares %s planned, so no file of that name may exist beside"
                                    + " it; a planned entry left standing after the class lands is"
                                    + " the drift this check exists for", charter, planned)
                            .doesNotExist();
                }
            }
        }
        assertThat(marked)
                .as("charters carrying the canonical '%s' marker line; the floor exists so that"
                        + " deleting a marker to silence a failure is a visible act",
                        "this directory: N java files = M classes + 1 charter")
                .isGreaterThanOrEqualTo(MINIMUM_MARKED_CHARTERS);
    }

    /**
     * Confirms every class a marked charter names in a {@code {@code Xxx}} entry exists beside it.
     *
     * <p>Assumptions: only the entries of a list are read, and only from a charter that carries the
     * marker line, because those are the enumerations the marker declares to be complete. A charter
     * mentions many type names in passing -- collaborators, reference programs, types in other
     * packages -- and holding every mention to a sibling file would fail on all of them.</p>
     *
     * <p>Trade-offs: this checks that each named member EXISTS, not that each existing member is
     * named. The reverse direction is already covered by the count above: a member present and
     * unnamed makes the directory larger than the enumeration, which the figures catch.</p>
     *
     * @throws UncheckedIOException if a charter cannot be read, which fails the test rather than
     *     skipping it, for the same reason as the case above
     */
    @Test
    @DisplayName("every member a marked charter enumerates is a file in that same directory")
    void everyEnumeratedMemberExistsBesideItsCharter() {
        Pattern entry = Pattern.compile("\\* {3}<li>\\{@code ([A-Z][A-Za-z0-9]*)}");
        int checked = 0;
        for (Path charter : charters()) {
            Path directory = charter.getParent();
            String text;
            try {
                text = Files.readString(charter, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + charter, unreadable);
            }
            Matcher marker = DIRECTORY_MARKER.matcher(text);
            if (!marker.find()) {
                continue;
            }
            List<String> planned = plannedNames(marker);
            Matcher named = entry.matcher(text);
            while (named.find()) {
                if (planned.contains(named.group(1))) {
                    continue;
                }
                checked++;
                assertThat(directory.resolve(named.group(1) + ".java"))
                        .as("%s enumerates %s, which must be a file in that directory",
                                charter, named.group(1))
                        .exists();
            }
        }
        assertThat(checked)
                .as("enumerated members found across the marked charters; none would make this"
                        + " check vacuous")
                .isPositive();
    }
}
