// WHAT: this class re-measures the two closed censuses the charter beside it publishes -- the
//       container-backed classes in this directory, and the fixtures the module binds -- and fails
//       when either one drifts from the directory it describes.
// WHY : Refactoring Rationale: the review found BOTH censuses stale at once. The charter said "three
//       of them because there are exactly three such properties" while eight classes sat beside it,
//       and it said SEVEN fixtures while ten were bound. Neither is a careless reading: a count
//       published in one file and measured in another is falsified by changes that never open the
//       file holding it, so the only durable fix is to measure it from a test.
// WHY : Alternatives Considered: relying on the repository-wide charter check in
//       com.carddemo.common.architecture.PackageCharterInventoryTest alone. It does cover the marker
//       line and the enumerated class names, and the marker was added to this charter for exactly
//       that reason -- but it runs in the common-lib module, so a reporting-service change that
//       falsifies this charter would be reported by a build of another module. This class puts the
//       same verdict inside the module that owns the drift. Assumptions: the two checks are
//       deliberately not identical: the fixtures census has no counterpart there at all.

package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the charter of this directory to the classes beside it and to the fixtures it binds.
 *
 * <h2>Purpose</h2>
 *
 * <p>The charter in {@code package-info.java} beside this file publishes two CLOSED inventories: the
 * container-backed classes of this directory, and the fixture files
 * {@code src/test/resources/fixtures} carries. Both were found stale by review, in the same release
 * and for the same structural reason -- each figure sits in a file that a change adding a class or a
 * fixture never opens. This class re-measures both from the tree, so that change fails the build
 * rather than publishing a false document.</p>
 *
 * <p>Assumptions: this class deliberately ends in {@code Test} and not in {@code IT}, so it is run by
 * the unit-test plugin during the {@code test} phase rather than by the integration-test plugin. It
 * starts no container and reads no database: it reads FILES. Naming it {@code ...IT} would have made
 * it the ninth entry in the very census it measures, and would have delayed a verdict that needs no
 * engine until after packaging. The charter's own naming-contract section records the selection rule
 * this relies on.</p>
 *
 * <p>Trade-offs: the checks are textual, because a Javadoc block cannot be parsed for meaning -- each
 * measured name must APPEAR in the charter, and EVERY place the charter spells a census must spell it
 * as the measured figure. The second half is what makes the count check worth having: a containment
 * check was written first and then measured against an injected wrong figure, and it passed, because
 * the charter states its class count in two sections and the other one still read correctly. What
 * this gives up is the ability to notice a name mentioned in the charter for some unrelated reason;
 * what it buys is that a class or fixture added without an entry, or a figure corrected in one
 * sentence and left in another, fails here -- and those are the two drifts that actually
 * occurred.</p>
 */
@DisplayName("The repository charter's two censuses match the directories they describe")
final class RepositoryCharterCensusTest {

    /** Reactor descriptor used to locate the repository root, so the run directory does not matter. */
    private static final String ROOT_MARKER = "services/pom.xml";

    /** Directory this charter describes, relative to the repository root. */
    private static final String CHARTER_DIRECTORY =
            "services/reporting-service/src/test/java/com/carddemo/reporting/repository";

    /** Fixture directory the charter's roster describes, relative to the repository root. */
    private static final String FIXTURE_DIRECTORY =
            "services/reporting-service/src/test/resources/fixtures";

    /** The charter itself, being the only compilation unit able to carry a package docstring. */
    private static final String CHARTER_FILE = "package-info.java";

    /** Suffix the integration-test plugin selects, and therefore what makes a class part of the census. */
    private static final String IT_SUFFIX = "IT.java";

    /** Extension every bound fixture carries; the directory's README is admitted but is not a fixture. */
    private static final String FIXTURE_EXTENSION = ".txt";

    /** The one non-fixture resource the fixture directory admits, named so the count stays honest. */
    private static final String FIXTURE_README = "README.md";

    /**
     * Number words this charter may spell a census with, indexed by the number they spell.
     *
     * <p>Assumptions: the charter spells its counts as words rather than digits, which is the house
     * prose convention throughout this tree, so a check that looked for a digit would never match. The
     * table stops at twenty because a directory of this kind that grew past twenty classes would want
     * splitting rather than a longer table, and an index beyond the table fails with a message naming
     * the count instead of failing obscurely.</p>
     */
    private static final List<String> NUMBER_WORDS = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty");

    /**
     * Matches every place the charter spells its class census, capturing the number word.
     *
     * <p>Assumptions: the phrase is matched EVERYWHERE it occurs rather than looked for once. A
     * containment check would have been satisfied by any single correct occurrence, and this charter
     * states its class count in two places -- the opening census and the naming-contract section --
     * so one of them could carry a stale figure while the check stayed green. That is not
     * hypothetical: it was measured on this very file before this pattern replaced the containment
     * check, by editing the opening census to a wrong word and watching the case still pass.</p>
     */
    private static final Pattern CLASS_CENSUS =
            Pattern.compile("(\\w+) container-backed classes", Pattern.CASE_INSENSITIVE);

    /**
     * Matches every place the charter spells its fixture census, capturing the number word.
     *
     * <p>Assumptions: matched everywhere for the same reason as the class census. This roster has
     * already been short twice, and a charter that corrected one sentence and left another is the
     * next form the same drift would take.</p>
     */
    private static final Pattern FIXTURE_CENSUS =
            Pattern.compile("(\\w+) fixtures by exact name", Pattern.CASE_INSENSITIVE);

    /**
     * Matches the canonical directory marker the repository-wide charter check also reads.
     *
     * <p>Assumptions: the pattern is the one
     * {@code com.carddemo.common.architecture.PackageCharterInventoryTest} applies, restated here
     * rather than imported, because importing a type from another module's TEST tree is not possible
     * -- test classes are not published between modules. Restating a regular expression is acceptable
     * where restating a count would not be: if the two ever diverge, the marker simply fails to match
     * here and this class reports it, which is the safe direction.</p>
     */
    private static final Pattern DIRECTORY_MARKER = Pattern.compile(
            "^ \\* this directory: (\\d+) java files = (\\d+) tests \\+ 1 charter$",
            Pattern.MULTILINE);

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the repository root, never {@code null}
     * @throws IllegalStateException if no ancestor carries the reactor descriptor, which fails rather
     *     than skips: an unlocatable root is exactly the case in which these censuses would otherwise
     *     go unchecked
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory carries " + ROOT_MARKER);
    }

    /**
     * Reads one file as text.
     *
     * @param file the file to read; must not be {@code null}
     * @return the file's whole content decoded as UTF-8, never {@code null}
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
     * Lists the file names directly inside one directory that end in one suffix.
     *
     * @param directory the directory to list; must not be {@code null}
     * @param suffix the file-name suffix to admit; must not be {@code null}
     * @return the matching names in sorted order, never {@code null}
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static List<String> namesEndingWith(Path directory, String suffix) {
        try (Stream<Path> files = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .sorted()
                    .forEach(names::add);
            return names;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Spells one count as the word the charter states it with.
     *
     * @param count the count to spell; must be within the table
     * @return the lower-case number word, never {@code null}
     * @throws IllegalStateException if the count is outside the table, naming the count
     */
    private static String word(int count) {
        if (count < 0 || count >= NUMBER_WORDS.size()) {
            throw new IllegalStateException("no number word is tabulated for " + count
                    + "; extend NUMBER_WORDS and restate the census phrase in the charter");
        }
        return NUMBER_WORDS.get(count);
    }

    /**
     * Collects the number word from every place the charter spells one census.
     *
     * @param charter the charter's whole text; must not be {@code null}
     * @param census the pattern matching that census's phrase, capturing the number word in group
     *     one; must not be {@code null}
     * @return the captured words in lower case and in document order, never {@code null}
     */
    private static List<String> spelledCounts(String charter, Pattern census) {
        List<String> spelled = new ArrayList<>();
        Matcher stated = census.matcher(charter);
        while (stated.find()) {
            spelled.add(stated.group(1).toLowerCase(Locale.ROOT));
        }
        return spelled;
    }

    // WHY : Assumptions: the class census is asserted in THREE directions rather than one, because the
    //       stale charter would have satisfied a weaker check. Its spelled count was wrong, so the
    //       count is compared; it named three of the eight classes correctly, so a check that only
    //       looked for the names it did carry would have passed; and it enumerated nothing that was
    //       absent, so the reverse direction has to be asserted as well.
    /**
     * Confirms the charter's class census names every container-backed class in this directory.
     *
     * <p>Assumptions: the census counts classes selected by the integration-test plugin -- those whose
     * names end in {@code IT} -- and deliberately excludes this class and the charter itself, which is
     * why the count is taken from that suffix rather than from every Java file. The marker line covers
     * the Java-file total separately, so both figures are measured and neither stands in for the
     * other.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the class census spells the right count and names every container-backed class")
    void theClassCensusMatchesTheDirectory() {
        Path directory = repositoryRoot().resolve(CHARTER_DIRECTORY);
        List<String> classes = namesEndingWith(directory, IT_SUFFIX);
        String charter = read(directory.resolve(CHARTER_FILE));

        assertThat(classes)
                .as("a census over an empty list would assert nothing at all")
                .isNotEmpty();

        List<String> spelled = spelledCounts(charter, CLASS_CENSUS);
        assertThat(spelled)
                .as("the charter must spell its class count at least once, in the phrase this case"
                        + " reads; a census nothing measures is what went stale")
                .isNotEmpty();
        assertThat(spelled)
                .as("every place the charter spells its class count must say '%s', because %d classes"
                        + " end in %s in this directory", word(classes.size()), classes.size(),
                        IT_SUFFIX)
                .containsOnly(word(classes.size()));

        for (String name : classes) {
            String simpleName = name.substring(0, name.length() - ".java".length());
            assertThat(charter)
                    .as("%s sits in this directory and the charter does not name it, which is the"
                            + " omission this case exists for", simpleName)
                    .contains(simpleName);
        }

        Matcher marker = DIRECTORY_MARKER.matcher(charter);
        assertThat(marker.find())
                .as("the charter must carry the canonical marker line, which is what the"
                        + " repository-wide charter check reads")
                .isTrue();
        int javaFiles = namesEndingWith(directory, ".java").size();
        assertThat(Integer.parseInt(marker.group(1)))
                .as("java files the marker line claims for this directory")
                .isEqualTo(javaFiles);
        assertThat(Integer.parseInt(marker.group(2)))
                .as("members besides the charter the marker line claims for this directory")
                .isEqualTo(javaFiles - 1);
    }

    // WHY : Assumptions: the README is asserted PRESENT and excluded from the fixture count, rather
    //       than being ignored. The contract test admits eleven resources and ten of them are
    //       fixtures, so a reader reconciling the two lists meets the difference; asserting it here is
    //       what makes the charter's sentence about that eleventh resource a measured claim too.
    /**
     * Confirms the charter's fixture roster names every fixture the module binds.
     *
     * <p>Assumptions: the roster is measured against the fixture DIRECTORY rather than against
     * {@code ReportingFixtureContractTest}'s accepted-resource list. That list is itself a statement
     * about the directory, so comparing the charter with it would compare two documents and leave the
     * directory unmeasured -- and the contract test already holds its own list to the directory.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the fixture roster spells the right count and names every bound fixture")
    void theFixtureRosterMatchesTheDirectory() {
        Path root = repositoryRoot();
        Path fixtures = root.resolve(FIXTURE_DIRECTORY);
        List<String> bound = namesEndingWith(fixtures, FIXTURE_EXTENSION);
        String charter = read(root.resolve(CHARTER_DIRECTORY).resolve(CHARTER_FILE));

        assertThat(bound)
                .as("a roster over an empty directory would assert nothing at all")
                .isNotEmpty();

        List<String> spelled = spelledCounts(charter, FIXTURE_CENSUS);
        assertThat(spelled)
                .as("the charter must spell its fixture count at least once, in the phrase this case"
                        + " reads")
                .isNotEmpty();
        assertThat(spelled)
                .as("every place the charter spells its fixture count must say '%s', because the"
                        + " fixture directory carries %d files ending in %s",
                        word(bound.size()), bound.size(), FIXTURE_EXTENSION)
                .containsOnly(word(bound.size()));

        for (String fixture : bound) {
            assertThat(charter)
                    .as("%s is bound in the fixture directory and the charter's roster does not name"
                            + " it; a short census is what lets a statement about the whole directory"
                            + " be written from a subset of it", fixture)
                    .contains(fixture);
        }

        assertThat(fixtures.resolve(FIXTURE_README))
                .as("the fixture directory's own README is the eleventh admitted resource and is not"
                        + " a fixture, which is why the count above excludes it")
                .exists();
        assertThat(charter)
                .as("the charter must name that non-fixture resource, so the two counts reconcile")
                .contains(FIXTURE_README);
    }
}
