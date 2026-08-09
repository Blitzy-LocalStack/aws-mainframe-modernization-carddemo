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
 * Holds every measured claim a package charter publishes to the directory the charter lives in.
 *
 * <p><b>Purpose.</b> Package charters across the service tree publish closed inventories of their own
 * directory -- "exactly four classes", "three source files and no more", "five Java files, four of
 * them tests". Those figures are the ones the review found stale, and they go stale for a structural
 * reason rather than through carelessness: the figure lives in a different file from the thing it
 * counts, so the change that falsifies it never touches it. This test reads the figure and counts the
 * directory, so the next such change fails the build instead of publishing a false document.</p>
 *
 * <p>Two kinds of measured claim are checked, on the same argument. The first is the count of files in
 * the directory, published as the marker line described below. The second is the count of cases an
 * enumerated member covers, published as a per-member figure in the charter's inventory: it is a
 * measurement of a file in the same directory and drifts for the same structural reason. Both are
 * re-measured here, and each check separately refuses to be vacuous.</p>
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
@DisplayName("Every measured claim a package charter states matches the directory it describes")
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

    /** Matches one enumerated member and the body of its entry, over a flattened charter. */
    private static final Pattern ENUMERATED_MEMBER =
            Pattern.compile("<li>\\{@code ([A-Z][A-Za-z0-9]*)}(.*?)</li>", Pattern.DOTALL);

    /** Matches the case count an enumerated member's entry declares. */
    private static final Pattern DECLARED_CASE_COUNT = Pattern.compile("across (\\d+) cases");

    /** Matches one declared case, being a test annotation that begins its own line. */
    private static final Pattern TEST_ANNOTATION = Pattern.compile(
            "^\\s*@(?:Test|ParameterizedTest|RepeatedTest)\\b", Pattern.MULTILINE);

    /** Matches the leading asterisk of a Javadoc continuation line. */
    private static final Pattern JAVADOC_LINE_PREFIX =
            Pattern.compile("^\\s*\\*[ \\t]?", Pattern.MULTILINE);

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

    /**
     * Holds every declared case count a charter states against the class it names.
     *
     * <p><b>Purpose.</b> A charter that enumerates its members often states how many cases each one
     * covers, and that figure is a measurement of a file sitting in the same directory. This check
     * re-measures it. The metric is the one the charters name: cases DECLARED, being methods annotated
     * as a test, a parameterised test or a repeated test, rather than the larger number of cases a run
     * reports having executed once a parameterised method expands.</p>
     *
     * <p>Refactoring Rationale: this check was added because a stated count drifted from its directory
     * in a way that naming the metric had not prevented. The count was re-measured by someone who
     * counted the plain test annotations and did not add the parameterised ones, which produced a
     * figure lower than the truth and a note recording it as a correction -- so the charter then
     * asserted a wrong number and explained why it was right. Prose cannot defend against that; a
     * measurement can, and this is the same argument the marker line above rests on.</p>
     *
     * <p>Assumptions: a named member whose file is absent is skipped rather than failed here, because
     * the check above already owns member existence and reports it against the enumeration with a
     * message shaped for that failure. Duplicating it here would report one defect twice and would
     * report it second in a weaker form. A planned member, which by definition has no file yet, is
     * skipped by the same rule without needing the planned clause to be parsed again.</p>
     *
     * @throws UncheckedIOException if a charter or an enumerated class cannot be read, which fails the
     *     test rather than skipping it, for the same reason as the cases above
     */
    @Test
    @DisplayName("every case count a charter declares matches the class it names")
    void everyDeclaredCaseCountMatchesTheClassItNames() {
        int checked = 0;
        for (Path charter : charters()) {
            Path directory = charter.getParent();
            Matcher entries = ENUMERATED_MEMBER.matcher(flatten(read(charter)));
            while (entries.find()) {
                Matcher declared = DECLARED_CASE_COUNT.matcher(entries.group(2));
                if (!declared.find()) {
                    continue;
                }
                Path member = directory.resolve(entries.group(1) + ".java");
                if (!Files.isRegularFile(member)) {
                    continue;
                }
                checked++;
                assertThat(declaredCases(member))
                        .as("%s states %s covers %s cases; that is a count of methods annotated as a"
                                + " test, a parameterised test or a repeated test in %s",
                                charter, entries.group(1), declared.group(1), member.getFileName())
                        .isEqualTo(Integer.parseInt(declared.group(1)));
            }
        }
        assertThat(checked)
                .as("declared case counts found across the charters; none would make this check"
                        + " vacuous")
                .isPositive();
    }

    /**
     * Counts the cases one test class declares.
     *
     * @param member the test class to measure; must not be {@code null}
     * @return the number of methods annotated as a test, a parameterised test or a repeated test
     * @throws UncheckedIOException if the class cannot be read
     */
    private static int declaredCases(Path member) {
        // WHY : Assumptions: the annotations are counted on the raw source rather than on the
        //       flattened form used for the charter, because the pattern anchors each one to the start
        //       of its own line. That anchor is what keeps the count to real annotations: the same
        //       token appears inside prose and inside an import, and neither begins a line of its own.
        //       The word boundary keeps sibling annotations whose names merely start with Test out of
        //       the count.
        Matcher cases = TEST_ANNOTATION.matcher(read(member));
        int declared = 0;
        while (cases.find()) {
            declared++;
        }
        return declared;
    }

    /**
     * Reads one source file as text.
     *
     * @param file the file to read; must not be {@code null}
     * @return the file's full contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Reduces one Javadoc comment to a single line so a claim that wraps can be matched.
     *
     * @param source the file's full contents; must not be {@code null}
     * @return the same text with Javadoc line prefixes removed and runs of whitespace collapsed to one
     *     space; never {@code null}
     */
    private static String flatten(String source) {
        // WHY : Assumptions: the continuation prefix is stripped and whitespace collapsed before a
        //       claim is matched, because a claim in a charter wraps wherever the line ran out and the
        //       wrap point is not part of the claim. A pattern written against the wrapped form would
        //       match a count today and stop matching it the moment a word was inserted earlier in the
        //       same sentence -- which is a check that silently stops checking, the failure this whole
        //       class exists to make impossible.
        return JAVADOC_LINE_PREFIX.matcher(source).replaceAll("").replaceAll("\\s+", " ");
    }

    /** Matches the canonical subpackage-roster marker, whose figure must equal the child directories. */
    private static final Pattern SUBPACKAGE_MARKER =
            Pattern.compile("^ \\* this package: (\\d+) subpackages$", Pattern.MULTILINE);

    /** Matches one subpackage entry of a roster, which is written with the leading dot the tree uses. */
    private static final Pattern SUBPACKAGE_ENTRY =
            Pattern.compile("\\* {3}<li>\\{@code \\.([a-z][A-Za-z0-9]*)}");

    /**
     * Fewest subpackage-roster markers this test must find for its verdict to mean anything.
     *
     * <p>Assumptions: the floor is one because exactly one charter in the tree publishes a closed roster
     * of the packages beneath it today, and stating the real number rather than an aspirational one is
     * what keeps the floor meaningful. It is a floor for the same reason as the file-count floor above:
     * adopting the marker in a further charter needs no ceremony, while removing it from the charter that
     * has it becomes a visible act in review rather than an invisible omission.</p>
     */
    private static final int MINIMUM_MARKED_ROSTERS = 1;

    /**
     * Lists the names of the directories immediately inside one directory.
     *
     * @param directory the directory whose children are listed
     * @return the child directory names, sorted, so a comparison reads the same on every platform
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static List<String> childDirectoryNames(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory)
                    .map(child -> child.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Confirms every published subpackage roster matches the directories beneath its charter.
     *
     * <p>Assumptions: both directions are asserted, because the two failures a roster can carry are
     * different and neither implies the other. A roster can UNDERCOUNT, which is what makes a reader
     * believe a directory they can see is not part of the tree; and it can NAME a package that is not
     * there, which sends a reader looking for it. The count catches the first and the name check catches
     * the second, so a roster that named nine packages of which one was absent while a tenth existed
     * unnamed -- which would keep the total correct -- still fails.</p>
     *
     * <p>Assumptions: the entries are read with the leading dot the tree writes them with, so a mention
     * of a package elsewhere in the charter's prose is not swept in. Charters name sibling and production
     * packages constantly, and holding every mention to a child directory would fail on all of them.</p>
     *
     * @throws UncheckedIOException if a charter or its directory cannot be read, which fails the test
     *     rather than skipping it: an unreadable charter is exactly the case in which its claims would
     *     otherwise go unchecked
     */
    @Test
    @DisplayName("each subpackage-roster marker's count and named packages match the directories present")
    void everyMarkedSubpackageRosterMatchesItsDirectory() {
        int marked = 0;
        for (Path charter : charters()) {
            Path directory = charter.getParent();
            String text;
            try {
                text = Files.readString(charter, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + charter, unreadable);
            }
            Matcher marker = SUBPACKAGE_MARKER.matcher(text);
            if (!marker.find()) {
                continue;
            }
            marked++;
            List<String> present = childDirectoryNames(directory);
            assertThat(Integer.parseInt(marker.group(1)))
                    .as("subpackages claimed by %s, against the directories present: %s", charter,
                            present)
                    .isEqualTo(present.size());
            List<String> named = new ArrayList<>();
            Matcher entry = SUBPACKAGE_ENTRY.matcher(text);
            while (entry.find()) {
                named.add(entry.group(1));
            }
            assertThat(named)
                    .as("%s publishes a closed roster, so every directory present must be named in it"
                            + " and every name must be a directory", charter)
                    .containsExactlyInAnyOrderElementsOf(present);
        }
        assertThat(marked)
                .as("charters carrying the canonical '%s' marker line; the floor exists so that"
                        + " deleting a marker to silence a failure is a visible act",
                        "this package: N subpackages")
                .isGreaterThanOrEqualTo(MINIMUM_MARKED_ROSTERS);
    }

}
