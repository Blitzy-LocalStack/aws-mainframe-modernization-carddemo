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
 * Re-derives the shared kernel's class inventory from the directory and holds the charters to it.
 *
 * <p><b>Purpose.</b> The root charter at {@code com/carddemo/common/package-info.java} publishes a
 * closed inventory table -- one row per package, giving its production-class count, its charter count
 * and their sum -- plus two cross-check totals and an enumeration of every class this module holds
 * beyond the seventeen the migration plan names at its section 0.4.1.2. The sibling charter at
 * {@code com/carddemo/common/money/package-info.java} restates the same two totals. All of it was
 * maintained by hand and all of it went stale twice: first by understating {@code common.error} by
 * four classes, then by understating {@code codec}, {@code observability} and {@code security} and
 * omitting the whole {@code messaging} subpackage. This test measures the tree and fails on any
 * disagreement, so a third occurrence is a build failure rather than a false document.</p>
 *
 * <p>Alternatives Considered: deleting the inventory from both charters and letting a directory
 * listing speak for itself. Rejected because a listing answers a different question -- it says what
 * is present, where the table says what BELONGS in the shared kernel, which is the judgement a
 * reviewer needs when deciding whether a proposed type is in the right module. Mechanising the count
 * keeps the judgement and removes the staleness; deleting the table would do the reverse.</p>
 *
 * <p>Alternatives Considered: asserting the counts against literals declared in this file, which is
 * how a conventional test would be written. Rejected because it moves the hand-maintained number
 * rather than removing it: the literal here and the table there would then be two places to edit,
 * and the failure mode would be identical the first time only one of them was. Every figure this
 * test compares is measured from the directory on the left and parsed from the charter on the
 * right, so neither side is authored twice.</p>
 *
 * <p>Trade-offs: the charter is parsed with regular expressions over its text rather than through a
 * Javadoc model. A parse failure is therefore possible if the table's layout is reformatted, and
 * every parse here fails loudly with the expected shape quoted rather than returning an empty result
 * -- a regex that silently matched nothing would turn this test into a check that passes for any
 * charter at all, which is the one outcome worse than the staleness it replaces.</p>
 */
@DisplayName("The shared kernel's charters state the inventory the directory actually holds")
final class SharedKernelInventoryTest {

    /**
     * Marker used to locate the repository root, chosen because it is the subject of this test.
     *
     * <p>Assumptions: the marker is the root charter itself rather than a build file. A marker that
     * is not the subject can be present while the subject is absent, which would let the walk
     * succeed and the read fail with a less useful message.</p>
     */
    private static final String ROOT_CHARTER =
            "services/common-lib/src/main/java/com/carddemo/common/package-info.java";

    /** Directory holding the shared kernel's production source, relative to the repository root. */
    private static final String KERNEL_SOURCE_ROOT =
            "services/common-lib/src/main/java/com/carddemo/common";

    /** The money charter, which restates the root charter's two totals. */
    private static final String MONEY_CHARTER = KERNEL_SOURCE_ROOT + "/money/package-info.java";

    /** File name every package charter carries, excluded from every production-class count. */
    private static final String CHARTER_FILE_NAME = "package-info.java";

    /**
     * Count of shared-kernel classes the migration plan names by path at its section 0.4.1.2.
     *
     * <p>Assumptions: this is the one figure in this test that is a literal, because it is a
     * property of a frozen document rather than of the tree. The plan is not edited by this project,
     * so the number cannot drift; the charter quotes the same 17 and this test holds it to that.</p>
     */
    private static final int PLAN_NAMED_CLASSES = 17;

    /** Matches one inventory row: a package name followed by three integers. */
    private static final Pattern INVENTORY_ROW = Pattern.compile(
            "^ \\* (common(?:\\.[a-z]+)?)(?: \\(this root\\))? +(\\d+) +(\\d+) +(\\d+)$",
            Pattern.MULTILINE);

    /** Matches the production-class cross-check sum, whose addends must be the rows in order. */
    private static final Pattern PRODUCTION_SUM =
            Pattern.compile("([\\d]+(?: \\+ [\\d]+)+) = (\\d+), the root contributing one");

    /** Matches the compilation-unit cross-check sum. */
    private static final Pattern COMPILATION_UNIT_SUM =
            Pattern.compile("compilation unit: ([\\d]+(?: \\+ [\\d]+)+) = (\\d+)");

    /** Matches the money charter's restatement of both totals. */
    private static final Pattern MONEY_TOTALS = Pattern.compile(
            "production classes: +([\\d]+(?: \\+ [\\d]+)+) = (\\d+)\\s+\\*"
                    + " compilation units: +(\\d+) production \\+ (\\d+) package descriptors = (\\d+)");

    /** Matches the sentence declaring how many additions beyond the plan are enumerated. */
    private static final Pattern DECLARED_ADDITIONS =
            Pattern.compile("The\\s+\\*? ?difference is (\\d+) deliberate additions");

    /**
     * Matches one entry of the additions list, keyed by the class it names.
     *
     * <p>Assumptions: nothing is required after the class name. An earlier form of this pattern
     * demanded the em-dash immediately, which silently dropped the one entry that qualifies its
     * subject first ("{@code CardDemoCommonAutoConfiguration} in this root -- the registration
     * entry") and so under-counted the list by one. The entry is identified by the class it names,
     * which is the only part of it this test reasons about.
     */
    private static final Pattern ADDITION_ENTRY =
            Pattern.compile("\\* {3}<li>\\{@code ([A-Za-z.]+)}");

    /** Closes the list the additions are enumerated in. */
    private static final String LIST_END = "</ul>";

    /**
     * Matches one labelled kernel-wide cross-check sum, wherever a charter restates it.
     *
     * <p>Refactoring Rationale: the sums were unlabelled -- a bare "1 + 2 + 6 + ... = 35" -- in every
     * charter that carried them, which made a wrong addend detectable only by re-deriving the whole
     * line by hand and impossible to attribute to a package. Labelling each addend with the package
     * it counts is what lets this test report WHICH figure is wrong, and it is why the charters were
     * reworded rather than merely recomputed.</p>
     */
    private static final Pattern LABELLED_SUM = Pattern.compile(
            "^ \\* ((?:[a-z]+ \\d+ \\+ )+[a-z]+ \\d+) = (\\d+)$", Pattern.MULTILINE);

    /** Matches a charter's statement of its own directory's share of the kernel. */
    private static final Pattern OWN_SHARE = Pattern.compile(
            "^ \\* this package: ([a-z]+) (\\d+) production \\+ (\\d+) charter"
                    + " = (\\d+) compilation units$",
            Pattern.MULTILINE);

    /** Label the sums use for the kernel root, which has no simple package name of its own. */
    private static final String ROOT_LABEL = "root";

    /** The shared kernel's own README, whose published censuses this class also re-derives. */
    private static final String KERNEL_README = "services/common-lib/README.md";

    /** Root of the kernel's test tree, measured for the README's test-side listing. */
    private static final String KERNEL_TEST_ROOT =
            "services/common-lib/src/test/java/com/carddemo/common";

    /**
     * Matches the README's canonical production census marker.
     *
     * <p>Assumptions: the marker is an HTML comment, invisible in rendered Markdown, and it is the
     * same device {@link ServiceReadmeInventoryTest} uses for the test-side census in the same file.
     * A second mechanism for the same job would be two things to learn; reusing this one means a
     * reader who understands either census understands both.</p>
     */
    private static final Pattern README_SOURCE_MARKER = Pattern.compile(
            "<!--\\s*source-inventory:\\s*(\\d+) production classes \\+ (\\d+) charters"
                    + " = (\\d+) compilation units\\s*-->");

    /** Matches the header line of the README's main-source tree listing. */
    private static final Pattern README_MAIN_HEADER = Pattern.compile(
            "src/main/java/com/carddemo/common/\\s+(\\d+) packages · (\\d+) production types");

    /** Matches the header line of the README's test tree listing. */
    private static final Pattern README_TEST_HEADER = Pattern.compile(
            "src/test/java/com/carddemo/common/\\s+(\\d+) packages · (\\d+) \\*Test \\+ (\\d+) \\*IT");

    /** Opens the roster the root charter states one entry per package in. */
    private static final String ROSTER_START = "<h2>What each subpackage owns</h2>";

    /** Matches the package a roster entry describes, which its bold lead names. */
    private static final Pattern ROSTER_PACKAGE =
            Pattern.compile("<b>\\{@code ([a-z]+)}</b>");

    /** Matches a roster entry's production-class count, stated as a word before the colon. */
    private static final Pattern ROSTER_COUNT =
            Pattern.compile("([A-Za-z]+(?:-[a-z]+)?) production class(?:es)?:");

    /** Matches one class a roster entry or a charter census names. */
    private static final Pattern NAMED_CLASS =
            Pattern.compile("\\{@code ([A-Z][A-Za-z0-9]*)}");

    /** Matches the anchor of a prose census that attributes a count to a named package. */
    private static final Pattern PROSE_PACKAGE_ANCHOR =
            Pattern.compile("([a-z]+(?:-[a-z]+)?) production classes in \\{@code ([a-z]+)}");

    /** Matches one further term of that prose census, after its anchor. */
    private static final Pattern PROSE_PACKAGE_TERM =
            Pattern.compile("([a-z]+(?:-[a-z]+)?) in \\{@code ([a-z]+)}");

    /**
     * The census nouns whose figures this class re-derives wherever they appear in prose.
     *
     * <p>Assumptions: the set is closed and small on purpose. Each noun names a quantity this class
     * can measure from the directory, so a figure written against one of them is checkable; a noun
     * outside the set -- "services", "endpoints", "bytes" -- names something this module cannot
     * measure, and admitting it would produce a check that guessed.</p>
     */
    private static final List<String> CENSUS_NOUNS = List.of(
            "production classes", "production class", "production types", "production type",
            "compilation units", "compilation unit", "package descriptors", "charter files",
            "charters", "subpackages");

    /**
     * Words that mark a census figure as a statement about an EARLIER state of the tree.
     *
     * <p>Assumptions: every charter in this module records what it previously said, because the
     * Explainability rule asks for the rationale of a correction and not merely its result. Those
     * sentences necessarily carry figures the directory no longer supports, and they are correct as
     * written. They are recognised by the past-tense verb that makes them retrospective rather than
     * by an allow-list of line numbers, which would need editing on every reflow of a paragraph.</p>
     *
     * <p>Trade-offs: a paragraph that carries one of these words AND states a current figure wrongly
     * is exempted, so this scan is not a total guarantee. It is accepted because the authoritative
     * statements -- the root charter's table, the labelled sums, each charter's own-share line, the
     * roster, and the README's marker and listing -- are all re-derived unconditionally by the other
     * cases here, so the exemption only ever covers prose that restates one of them.</p>
     */
    private static final List<String> RETROSPECTIVE_MARKERS = List.of(
            "named", "said", "stated", "recorded", "stood here", "exceeded", "drifted",
            "previously", "target", "earlier", "corrected", "understated", "omitted");

    /** Lines whose figures another case in this class re-derives, removed before the prose scan. */
    private static final Pattern CANONICAL_LINE = Pattern.compile(
            "^(?: \\*)? *(?:this package: .*|this directory: .*|production classes: .*"
                    + "|compilation units: .*|package +production classes.*|common(?:\\.[a-z]+)?"
                    + "(?: \\(this root\\)| \\(root\\))? +\\d+ +\\d+ +\\d+|[a-z]+ \\d+(?: \\+ [a-z]+"
                    + " \\d+)+ = \\d+)$",
            Pattern.MULTILINE);

    /** Number words the charters spell out, used to read a spelled census figure. */
    private static final Map<String, Integer> NUMBER_WORDS = numberWords();

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the repository root; never {@code null}
     * @throws AssertionError if no ancestor holds the root charter, which fails rather than skips,
     *     because a contract nobody checks is a contract that stops holding
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_CHARTER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " contains "
                + ROOT_CHARTER + ", so the shared kernel inventory cannot be checked");
    }

    /**
     * Reads one repository file as text.
     *
     * @param relative the path relative to the repository root
     * @return the file's contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which fails the test
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
     * Measures the production-class count of the kernel root and of each of its subpackages.
     *
     * <p>Assumptions: a production class is a {@code .java} file that is not a package charter, and
     * the walk is one level deep because the kernel declares no nested subpackage. A deeper walk
     * would silently fold a future nested package into its parent's row, which is the drift this
     * test exists to expose rather than absorb.</p>
     *
     * @return package name to production-class count, root first then subpackages in directory
     *     order; never {@code null} and never empty
     * @throws UncheckedIOException if the source tree cannot be listed
     */
    private static Map<String, Integer> measuredProductionClasses() {
        Path source = repositoryRoot().resolve(KERNEL_SOURCE_ROOT);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("common", countClasses(source));
        try (Stream<Path> children = Files.list(source)) {
            children.filter(Files::isDirectory)
                    .sorted()
                    .forEach(child ->
                            counts.put("common." + child.getFileName(), countClasses(child)));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + source, unreadable);
        }
        return counts;
    }

    /**
     * Counts the production classes directly inside one directory.
     *
     * @param directory the directory to count in
     * @return the number of {@code .java} files that are not the package charter
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static int countClasses(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !name.equals(CHARTER_FILE_NAME))
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Parses the root charter's inventory table.
     *
     * @param charter the root charter's text
     * @return package name to the row's three figures, in the order the table declares them
     * @throws AssertionError if the table has no rows, which would make every comparison vacuous
     */
    private static Map<String, int[]> declaredInventory(String charter) {
        Map<String, int[]> rows = new LinkedHashMap<>();
        Matcher row = INVENTORY_ROW.matcher(charter);
        while (row.find()) {
            rows.put(row.group(1), new int[] {Integer.parseInt(row.group(2)),
                    Integer.parseInt(row.group(3)), Integer.parseInt(row.group(4))});
        }
        assertThat(rows)
                .as("the root charter's inventory table must parse into rows, or this test is"
                        + " vacuous; expected lines shaped '<package> <n> <n> <n>' inside the <pre>"
                        + " block")
                .isNotEmpty();
        return rows;
    }

    /**
     * Confirms the inventory table's package set is exactly the package set the directory holds.
     *
     * <p>Assumptions: both directions are asserted in one method rather than as two, because either
     * direction alone is satisfiable by a table that is wrong in the other. The omission that
     * actually occurred was one-directional -- {@code messaging} existed and no row named it -- so a
     * check written only the other way round would have passed.</p>
     */
    @Test
    @DisplayName("every package in the tree has an inventory row, and no row names an absent package")
    void theInventoryTableCoversExactlyThePackagesThatExist() {
        Map<String, Integer> measured = measuredProductionClasses();
        Map<String, int[]> declared = declaredInventory(read(ROOT_CHARTER));
        // WHY : both directions are asserted in one test so neither can be satisfied alone. A table
        //   that omitted a package would read as "the shared kernel has no such concern", which is
        //   precisely how a second copy of `messaging` would have come to be written inside a
        //   service; a table naming a package that no longer exists sends a reader to an empty path.
        assertThat(declared.keySet())
                .as("inventory rows in %s versus packages under %s", ROOT_CHARTER,
                        KERNEL_SOURCE_ROOT)
                .containsExactlyInAnyOrderElementsOf(measured.keySet());
    }

    /**
     * Confirms each row's three figures against the directory it describes.
     *
     * <p>Assumptions: the charter column is held to exactly one rather than measured, because one
     * charter per package is the rule the Explainability gate enforces, so a row claiming two would
     * describe a package that had been split without the table saying so.</p>
     */
    @Test
    @DisplayName("every inventory row states the class count, charter count and sum the tree holds")
    void everyInventoryRowMatchesTheDirectory() {
        Map<String, Integer> measured = measuredProductionClasses();
        Map<String, int[]> declared = declaredInventory(read(ROOT_CHARTER));
        for (Map.Entry<String, Integer> entry : measured.entrySet()) {
            int[] figures = declared.get(entry.getKey());
            assertThat(figures).as("no inventory row for %s", entry.getKey()).isNotNull();
            assertThat(figures[0])
                    .as("%s production classes declared in %s", entry.getKey(), ROOT_CHARTER)
                    .isEqualTo(entry.getValue());
            // WHY : the charter column is asserted to be exactly one rather than measured, because
            //   one charter per package is the rule the Explainability gate enforces and a row
            //   claiming two would describe a package that had been split without the table saying so.
            assertThat(figures[1]).as("%s charter count", entry.getKey()).isEqualTo(1);
            assertThat(figures[2])
                    .as("%s compilation units must be its classes plus its one charter",
                            entry.getKey())
                    .isEqualTo(entry.getValue() + 1);
        }
    }

    /**
     * Confirms both cross-check sums add the table's own row values and reach the measured totals.
     *
     * <p>Trade-offs: the addends are compared as well as the totals. Two totals agreeing with each
     * other while disagreeing with the tree is the failure this charter has already suffered twice,
     * and comparing only the totals cannot see it -- a sum whose addends had been quietly rewritten
     * to reach a stale total would still balance.</p>
     */
    @Test
    @DisplayName("both cross-check sums add the table's own rows and reach the measured totals")
    void bothCrossCheckSumsAreDerivedFromTheRows() {
        String charter = read(ROOT_CHARTER);
        Map<String, int[]> declared = declaredInventory(charter);
        List<Integer> classesByRow = new ArrayList<>();
        List<Integer> unitsByRow = new ArrayList<>();
        declared.values().forEach(figures -> {
            classesByRow.add(figures[0]);
            unitsByRow.add(figures[2]);
        });
        int measuredClasses = classesByRow.stream().mapToInt(Integer::intValue).sum();
        int measuredUnits = unitsByRow.stream().mapToInt(Integer::intValue).sum();

        // WHY : the ADDENDS are compared, not only the totals. Two totals that agree with each other
        //   while disagreeing with the tree is the exact failure this charter has already suffered
        //   twice, and comparing only the totals cannot see it -- a sum whose addends were quietly
        //   rewritten to reach a stale total would still balance.
        assertThat(addends(charter, PRODUCTION_SUM, "production-class cross-check"))
                .as("the production-class cross-check in %s must add the table's own rows",
                        ROOT_CHARTER)
                .isEqualTo(classesByRow);
        assertThat(total(charter, PRODUCTION_SUM, "production-class cross-check"))
                .as("production-class total").isEqualTo(measuredClasses);
        assertThat(addends(charter, COMPILATION_UNIT_SUM, "compilation-unit cross-check"))
                .as("the compilation-unit cross-check in %s must add the table's own rows",
                        ROOT_CHARTER)
                .isEqualTo(unitsByRow);
        assertThat(total(charter, COMPILATION_UNIT_SUM, "compilation-unit cross-check"))
                .as("compilation-unit total").isEqualTo(measuredUnits);
    }

    /**
     * Confirms the money charter's restated totals against the directory, not against the root charter.
     *
     * <p>Assumptions: the second charter is checked against the measured tree rather than against the
     * root charter's text, because comparing the two documents to each other passes whenever both are
     * wrong in the same way -- and both were, twice: both quoted 21 and 30, then both quoted 27 and 36.</p>
     */
    @Test
    @DisplayName("the money charter restates the same two totals as the root charter")
    void theMoneyCharterAgreesWithTheRootCharter() {
        Map<String, Integer> measured = measuredProductionClasses();
        int classes = measured.values().stream().mapToInt(Integer::intValue).sum();
        int charters = measured.size();
        Matcher totals = MONEY_TOTALS.matcher(read(MONEY_CHARTER));
        assertThat(totals.find())
                .as("%s must restate both totals in the shape 'production classes: a + b = n' and"
                        + " 'compilation units: n production + m package descriptors = t'",
                        MONEY_CHARTER)
                .isTrue();
        // WHY : this second charter is checked against the DIRECTORY rather than against the root
        //   charter's text. Comparing the two documents to each other would pass whenever both were
        //   wrong in the same way, and they were: both quoted 21 and 30, then both quoted 27 and 36.
        assertThat(Integer.parseInt(totals.group(2))).as("money charter production total")
                .isEqualTo(classes);
        assertThat(Integer.parseInt(totals.group(3))).as("money charter production addend")
                .isEqualTo(classes);
        assertThat(Integer.parseInt(totals.group(4))).as("money charter descriptor count")
                .isEqualTo(charters);
        assertThat(Integer.parseInt(totals.group(5))).as("money charter compilation-unit total")
                .isEqualTo(classes + charters);
    }

    /**
     * Confirms the additions list has one entry per class held beyond the migration plan's own roster.
     *
     * <p>Trade-offs: the list's LENGTH is asserted rather than its membership. Which classes count as
     * additions depends on reading the migration plan, which this test cannot do; how many there are
     * is arithmetic it can. That is enough to catch the failure that occurred -- eight classes present
     * with no argument recorded for their presence -- without duplicating the plan's roster here and
     * going stale in turn.</p>
     */
    @Test
    @DisplayName("the additions list enumerates every class held beyond the migration plan's own")
    void everyAdditionBeyondThePlanIsEnumerated() {
        String charter = read(ROOT_CHARTER);
        Map<String, Integer> measured = measuredProductionClasses();
        int classes = measured.values().stream().mapToInt(Integer::intValue).sum();
        int expectedAdditions = classes - PLAN_NAMED_CLASSES;

        Matcher declared = DECLARED_ADDITIONS.matcher(charter);
        assertThat(declared.find())
                .as("%s must declare how many additions beyond the plan it enumerates", ROOT_CHARTER)
                .isTrue();
        assertThat(Integer.parseInt(declared.group(1)))
                .as("declared addition count in %s, against %d measured classes minus the plan's %d",
                        ROOT_CHARTER, classes, PLAN_NAMED_CLASSES)
                .isEqualTo(expectedAdditions);

        // WHY : Assumptions: the entries are read from the list that FOLLOWS the declaring
        //   sentence, not from the whole charter. This charter carries several other <li> lists --
        //   the subpackage roster and the package-root roster among them -- and matching across the
        //   whole file would count their entries too, which would make the size assertion below
        //   pass or fail for reasons that have nothing to do with the additions being argued in.
        int listEnd = charter.indexOf(LIST_END, declared.end());
        assertThat(listEnd)
                .as("%s must close the additions list it declares", ROOT_CHARTER)
                .isGreaterThan(0);
        String additionsList = charter.substring(declared.end(), listEnd);

        List<String> enumerated = new ArrayList<>();
        Matcher entry = ADDITION_ENTRY.matcher(additionsList);
        while (entry.find()) {
            enumerated.add(entry.group(1));
        }
        // WHY : the list LENGTH is asserted, not its membership. Which classes count as additions
        //   depends on reading the plan, which this test cannot do; how many there are is arithmetic
        //   it can. That is enough to catch the failure that occurred -- eight classes present in the
        //   module with no argument recorded for their presence -- without this test having to
        //   duplicate the plan's own roster and then go stale itself.
        assertThat(enumerated)
                .as("<li> entries in the additions list of %s, each naming one added class",
                        ROOT_CHARTER)
                .hasSize(expectedAdditions);
        assertThat(enumerated).as("no class may be argued in twice").doesNotHaveDuplicates();
    }

    /**
     * Extracts the addends of one cross-check sum.
     *
     * @param charter the charter's text
     * @param pattern the pattern whose first group is the {@code a + b + c} run
     * @param description what the sum is, used in the failure message
     * @return the addends in the order written; never {@code null} or empty
     * @throws AssertionError if the pattern matches nothing, which would make the caller vacuous
     */
    private static List<Integer> addends(String charter, Pattern pattern, String description) {
        Matcher matcher = pattern.matcher(charter);
        assertThat(matcher.find()).as("%s must be present and on one line in %s", description,
                ROOT_CHARTER).isTrue();
        List<Integer> values = new ArrayList<>();
        for (String addend : matcher.group(1).split(" \\+ ")) {
            values.add(Integer.parseInt(addend.trim()));
        }
        return values;
    }

    /**
     * Extracts the stated total of one cross-check sum.
     *
     * @param charter the charter's text
     * @param pattern the pattern whose second group is the total
     * @param description what the sum is, used in the failure message
     * @return the total as written
     * @throws AssertionError if the pattern matches nothing
     */
    private static int total(String charter, Pattern pattern, String description) {
        Matcher matcher = pattern.matcher(charter);
        assertThat(matcher.find()).as("%s must be present in %s", description, ROOT_CHARTER)
                .isTrue();
        return Integer.parseInt(matcher.group(2));
    }

    /**
     * Lists every package charter in the shared kernel, root first.
     *
     * @return repository-relative paths of the charters, keyed by the package each describes
     * @throws UncheckedIOException if the source tree cannot be listed
     */
    private static Map<String, String> charters() {
        Map<String, String> files = new LinkedHashMap<>();
        for (String packageName : measuredProductionClasses().keySet()) {
            String directory = packageName.equals("common")
                    ? KERNEL_SOURCE_ROOT
                    : KERNEL_SOURCE_ROOT + "/" + packageName.substring("common.".length());
            files.put(packageName, directory + "/" + CHARTER_FILE_NAME);
        }
        return files;
    }

    /**
     * Renders the label a labelled sum uses for one package.
     *
     * @param packageName the fully qualified package name, for example {@code common.codec}
     * @return {@code root} for the kernel root, otherwise the package's simple name
     */
    private static String label(String packageName) {
        return packageName.equals("common") ? ROOT_LABEL : packageName.substring("common.".length());
    }

    /**
     * Parses one labelled sum's addends.
     *
     * @param addends the sum's text, for example {@code root 1 + money 2 + codec 6}
     * @return label to value, in the order written
     */
    private static Map<String, Integer> addends(String addends) {
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String term : addends.split(" \\+ ")) {
            String[] parts = term.split(" ");
            parsed.put(parts[0], Integer.parseInt(parts[1]));
        }
        return parsed;
    }

    /**
     * Confirms every labelled kernel-wide sum, in every charter that restates one, matches the tree.
     *
     * <p>Assumptions: a sum is accepted if its addends are either the measured production-class
     * counts or those counts each plus one charter, because those are the only two kernel-wide sums
     * the charters state and they are distinguishable by their values alone. Nothing in the
     * surrounding prose is read to decide which is which, so rewording a charter cannot make this
     * check pass a wrong sum.</p>
     *
     * <p>Trade-offs: a charter that deletes its labelled sums altogether and keeps a stale figure in
     * ordinary prose is not caught here. That residual gap is accepted because the authoritative
     * statement of the inventory -- the root charter's table, its two totals and its additions list
     * -- is checked unconditionally by the other cases in this class, and because a check that tried
     * to recognise a count in free prose would either miss the ones it was written for or fail on
     * sentences that merely mention a number.</p>
     */
    @Test
    @DisplayName("every labelled cross-check sum in every charter is re-derived from the directory")
    void everyLabelledKernelSumAgreesWithTheDirectory() {
        Map<String, Integer> measured = measuredProductionClasses();
        Map<String, Integer> production = new LinkedHashMap<>();
        Map<String, Integer> units = new LinkedHashMap<>();
        measured.forEach((packageName, classes) -> {
            production.put(label(packageName), classes);
            units.put(label(packageName), classes + 1);
        });

        int found = 0;
        for (Map.Entry<String, String> charter : charters().entrySet()) {
            Matcher sum = LABELLED_SUM.matcher(read(charter.getValue()));
            while (sum.find()) {
                found++;
                Map<String, Integer> stated = addends(sum.group(1));
                assertThat(stated.keySet())
                        .as("packages named by the labelled sum '%s' in %s",
                                sum.group(1), charter.getValue())
                        .containsExactlyInAnyOrderElementsOf(production.keySet());
                assertThat(stated)
                        .as("addends of the labelled sum in %s, against the measured directory",
                                charter.getValue())
                        .isIn(production, units);
                assertThat(stated.values().stream().mapToInt(Integer::intValue).sum())
                        .as("stated total of the labelled sum in %s", charter.getValue())
                        .isEqualTo(Integer.parseInt(sum.group(2)));
            }
        }
        assertThat(found)
                .as("labelled cross-check sums found across the shared kernel's charters; none"
                        + " would make this check vacuous")
                .isPositive();
    }

    /**
     * Confirms each charter's statement of its own directory's share against that directory.
     *
     * <p>Assumptions: the package a charter names in its own-share line must be the package the
     * charter describes. A charter is the one document that can see its own directory, so an
     * own-share figure naming a different package is a copy-paste rather than a measurement, and
     * that is checked as well as the counts themselves.</p>
     */
    @Test
    @DisplayName("every charter's own-share figure equals the directory the charter lives in")
    void everyCharterOwnShareAgreesWithItsDirectory() {
        Map<String, Integer> measured = measuredProductionClasses();

        int found = 0;
        for (Map.Entry<String, String> charter : charters().entrySet()) {
            String packageName = charter.getKey();
            Matcher share = OWN_SHARE.matcher(read(charter.getValue()));
            while (share.find()) {
                found++;
                int classes = measured.get(packageName);
                assertThat(share.group(1))
                        .as("package named by the own-share line in %s", charter.getValue())
                        .isEqualTo(label(packageName));
                assertThat(Integer.parseInt(share.group(2)))
                        .as("production classes claimed by %s for its own directory",
                                charter.getValue())
                        .isEqualTo(classes);
                assertThat(Integer.parseInt(share.group(3)))
                        .as("charters claimed by %s for its own directory", charter.getValue())
                        .isEqualTo(1);
                assertThat(Integer.parseInt(share.group(4)))
                        .as("compilation units claimed by %s for its own directory",
                                charter.getValue())
                        .isEqualTo(classes + 1);
            }
        }
        assertThat(found)
                .as("own-share lines found across the shared kernel's charters; none would make"
                        + " this check vacuous")
                .isPositive();
    }

    /**
     * Builds the spelled-number vocabulary the charters draw on.
     *
     * <p>Assumptions: only the units, the teens and the tens are declared, and a hyphenated word is
     * read by adding its parts. That covers every figure this module can state -- its largest census
     * is in the fifties -- without a table of a hundred entries, and a word outside the vocabulary is
     * reported rather than silently skipped by the case that reads it.</p>
     *
     * @return word to value, for the units, the teens and the tens; never {@code null}
     */
    private static Map<String, Integer> numberWords() {
        Map<String, Integer> words = new LinkedHashMap<>();
        List<String> units = List.of("zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                "sixteen", "seventeen", "eighteen", "nineteen");
        for (int value = 0; value < units.size(); value++) {
            words.put(units.get(value), value);
        }
        List<String> tens = List.of("twenty", "thirty", "forty", "fifty", "sixty", "seventy",
                "eighty", "ninety");
        for (int index = 0; index < tens.size(); index++) {
            words.put(tens.get(index), 20 + index * 10);
        }
        return words;
    }

    /**
     * Reads a census figure written either in digits or in words.
     *
     * @param token the figure as the document writes it, for example {@code 44} or
     *     {@code forty-four}
     * @return the value, or {@code null} when the token is not a number this vocabulary knows
     */
    private static Integer figure(String token) {
        String candidate = token.toLowerCase(java.util.Locale.ROOT);
        if (candidate.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(candidate);
        }
        int total = 0;
        for (String part : candidate.split("-")) {
            Integer value = NUMBER_WORDS.get(part);
            if (value == null) {
                return null;
            }
            total += value;
        }
        return total;
    }

    /**
     * Lists the class file names one kernel package holds, without their extension.
     *
     * @param packageName the package to list, for example {@code common.codec} or {@code common}
     * @return the simple class names in the directory, excluding the charter; never {@code null}
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static List<String> measuredClassNames(String packageName) {
        Path directory = repositoryRoot().resolve(packageName.equals("common")
                ? KERNEL_SOURCE_ROOT
                : KERNEL_SOURCE_ROOT + "/" + packageName.substring("common.".length()));
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !name.equals(CHARTER_FILE_NAME))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Confirms the root charter's roster names, for each package, the classes that package holds.
     *
     * <p>Assumptions: the roster is checked by NAME and not only by count, because the omission this
     * case exists for was invisible to a count alone. The {@code messaging} entry said three classes
     * and named three while the directory held four, so the entry was internally consistent and
     * externally wrong -- a reader deciding where a fourth messaging concern belonged would have
     * concluded from the closed roster that the module had no such class.</p>
     *
     * <p>Assumptions: the count word and the named set are both compared, rather than only the set.
     * The two can disagree with each other as easily as either can disagree with the tree, and an
     * entry whose word and list contradict each other is a document a reader cannot use.</p>
     */
    @Test
    @DisplayName("the root charter's roster names every class in every package it describes")
    void theRootRosterNamesEveryClassInEveryPackage() {
        String charter = read(ROOT_CHARTER);
        int rosterStart = charter.indexOf(ROSTER_START);
        assertThat(rosterStart)
                .as("the root charter must carry the roster heading '%s', or this case is vacuous",
                        ROSTER_START)
                .isNotNegative();
        String roster = charter.substring(rosterStart, charter.indexOf(LIST_END, rosterStart));

        Map<String, Integer> measured = measuredProductionClasses();
        int checked = 0;
        for (String rawEntry : roster.split("<li>")) {
            // Assumptions: the entry's leading asterisks and line breaks are collapsed to single
            //   spaces before it is parsed, because Javadoc wraps a sentence wherever the column
            //   runs out -- the codec entry writes its count word at the end of one line and the
            //   noun at the start of the next. A pattern demanding a single space would fail on the
            //   longest entries only, which is the least useful place for a document check to stop
            //   working.
            String entry = rawEntry.replaceAll("\\s*\\*\\s*", " ");
            Matcher declaredPackage = ROSTER_PACKAGE.matcher(entry);
            if (!declaredPackage.find()) {
                continue;
            }
            String packageName = "common." + declaredPackage.group(1);
            Matcher declaredCount = ROSTER_COUNT.matcher(entry);
            assertThat(declaredCount.find())
                    .as("the roster entry for %s must state its production-class count in the shape"
                            + " '<count> production classes:'", packageName)
                    .isTrue();

            assertThat(figure(declaredCount.group(1)))
                    .as("production classes the roster claims for %s", packageName)
                    .isEqualTo(measured.get(packageName));

            List<String> named = new ArrayList<>();
            Matcher classes = NAMED_CLASS.matcher(entry.substring(declaredCount.end()));
            while (classes.find()) {
                named.add(classes.group(1));
            }
            assertThat(named)
                    .as("classes the roster names for %s, against that directory", packageName)
                    .containsExactlyInAnyOrderElementsOf(measuredClassNames(packageName));
            checked++;
        }
        assertThat(checked)
                .as("roster entries parsed; the kernel has ten subpackages and each must be named")
                .isEqualTo(measuredProductionClasses().size() - 1);
    }

    /**
     * Confirms every prose census that attributes a count to a named package matches that directory.
     *
     * <p>Assumptions: this reads the one sentence shape that spreads the whole inventory across prose
     * -- "six production classes in {@code codec}, four in {@code control}, ..." -- rather than any
     * sentence mentioning a package. A looser pattern would match ordinary prose such as "the two
     * filters in {@code web}" and would then fail on a sentence that was never a census, which is the
     * failure mode that makes a document check untrustworthy.</p>
     */
    @Test
    @DisplayName("every prose census attributing a count to a package matches that directory")
    void everyProsePackageCountMatchesItsDirectory() {
        Map<String, Integer> measured = measuredProductionClasses();
        int checked = 0;
        for (Map.Entry<String, String> charter : charters().entrySet()) {
            String text = read(charter.getValue()).replaceAll("\\s*\\*\\s*", " ");
            Matcher anchor = PROSE_PACKAGE_ANCHOR.matcher(text);
            while (anchor.find()) {
                assertThat(figure(anchor.group(1)))
                        .as("production classes %s attributes to common.%s in prose",
                                charter.getValue(), anchor.group(2))
                        .isEqualTo(measured.get("common." + anchor.group(2)));
                checked++;
                int sentenceEnd = text.indexOf(". ", anchor.end());
                String sentence = sentenceEnd < 0 ? text.substring(anchor.start())
                        : text.substring(anchor.start(), sentenceEnd);
                Matcher term = PROSE_PACKAGE_TERM.matcher(sentence);
                while (term.find()) {
                    String packageName = "common." + term.group(2);
                    Integer stated = figure(term.group(1));
                    // Assumptions: a term whose leading word is not a number is skipped rather than
                    //   failed, because the pattern deliberately matches "<word> in {@code pkg}" and
                    //   the anchor's own noun sits in that position too -- "six production classes
                    //   in {@code codec}" contains "classes in {@code codec}". The anchor's figure is
                    //   asserted from its own capture above, so skipping here loses no coverage.
                    if (stated == null || !measured.containsKey(packageName)) {
                        continue;
                    }
                    assertThat(stated)
                            .as("production classes %s attributes to %s in prose",
                                    charter.getValue(), packageName)
                            .isEqualTo(measured.get(packageName));
                    checked++;
                }
            }
        }
        // Assumptions: the floor is the number of SUBPACKAGES rather than of packages, because the
        //   one sentence of this shape in the tree attributes a count to each of the ten subpackages
        //   and then states the root's single class in a different clause -- "the package root
        //   contributing one" -- which this pattern deliberately does not read, there being no
        //   {@code common} to name it against.
        assertThat(checked)
                .as("prose per-package census terms found; the money charter states one such"
                        + " sentence covering every subpackage, so a lower figure means the shape"
                        + " moved and this case stopped checking what it was written for")
                .isGreaterThanOrEqualTo(measured.size() - 1);
    }

    /**
     * Confirms the README's production census and both tree-listing headers match the module.
     *
     * <p>Assumptions: the README is checked here rather than in {@link ServiceReadmeInventoryTest}
     * because these three figures are kernel-specific -- a production census and a package count --
     * where that class governs the test census every service README publishes in one shared shape.
     * Splitting them this way keeps each check next to the tree it measures.</p>
     */
    @Test
    @DisplayName("the README's production census and tree-listing headers match the module")
    void theReadmeCensusAgreesWithTheModule() {
        String readme = read(KERNEL_README);
        Map<String, Integer> measured = measuredProductionClasses();
        int production = measured.values().stream().mapToInt(Integer::intValue).sum();
        int packages = measured.size();

        Matcher marker = README_SOURCE_MARKER.matcher(readme);
        assertThat(marker.find())
                .as("the README must carry the source-inventory marker, or its census is prose"
                        + " nothing measures")
                .isTrue();
        assertThat(Integer.parseInt(marker.group(1)))
                .as("production classes the README marker publishes").isEqualTo(production);
        assertThat(Integer.parseInt(marker.group(2)))
                .as("charters the README marker publishes").isEqualTo(packages);
        assertThat(Integer.parseInt(marker.group(3)))
                .as("compilation units the README marker publishes")
                .isEqualTo(production + packages);

        Matcher mainHeader = README_MAIN_HEADER.matcher(readme);
        assertThat(mainHeader.find())
                .as("the README's main-source listing must carry its packages-and-types header")
                .isTrue();
        assertThat(Integer.parseInt(mainHeader.group(1)))
                .as("packages the README's main listing header claims").isEqualTo(packages);
        assertThat(Integer.parseInt(mainHeader.group(2)))
                .as("production types the README's main listing header claims")
                .isEqualTo(production);

        Matcher testHeader = README_TEST_HEADER.matcher(readme);
        assertThat(testHeader.find())
                .as("the README's test listing must carry its packages-and-classes header").isTrue();
        assertThat(Integer.parseInt(testHeader.group(1)))
                .as("packages the README's test listing header claims")
                .isEqualTo(testTreeCensus().get("packages"));
        assertThat(Integer.parseInt(testHeader.group(2)))
                .as("*Test classes the README's test listing header claims")
                .isEqualTo(testTreeCensus().get("tests"));
        assertThat(Integer.parseInt(testHeader.group(3)))
                .as("*IT classes the README's test listing header claims")
                .isEqualTo(testTreeCensus().get("integrationTests"));
    }

    /**
     * Measures the kernel's test tree the way the README's listing header states it.
     *
     * @return the package count, the {@code *Test} count and the {@code *IT} count, keyed
     *     {@code packages}, {@code tests} and {@code integrationTests}; never {@code null}
     * @throws UncheckedIOException if the test tree cannot be walked
     */
    private static Map<String, Integer> testTreeCensus() {
        Path root = repositoryRoot().resolve(KERNEL_TEST_ROOT);
        Map<String, Integer> census = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .toList();
            census.put("packages", (int) files.stream()
                    .map(Path::getParent).distinct().count());
            census.put("tests", (int) files.stream()
                    .filter(file -> file.getFileName().toString().endsWith("Test.java")).count());
            census.put("integrationTests", (int) files.stream()
                    .filter(file -> file.getFileName().toString().endsWith("IT.java")).count());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
        return census;
    }

    /**
     * Confirms the README's file listing names every file in the module and no file it does not hold.
     *
     * <p>Assumptions: both directions are asserted, because the two failures read differently to a
     * reader and both occurred. Two production classes were missing from the listing, which reads as
     * a module that does not hold them -- the omission that made the README's totals wrong in the
     * first place -- while a name left behind by a deletion reads as a class to look for and not
     * find.</p>
     *
     * <p>Assumptions: charters are excluded from the comparison because the listing states one per
     * package as its first entry rather than by name, and their count is checked by the header case
     * above.</p>
     *
     * @throws UncheckedIOException if either source tree cannot be walked, which is a broken checkout
     *     rather than a documentation failure and fails loudly rather than comparing a short list
     */
    @Test
    @DisplayName("the README's file listing names exactly the files this module holds")
    void theReadmeListingNamesExactlyTheFilesTheModuleHolds() {
        String readme = read(KERNEL_README);
        int start = readme.indexOf("src/main/java/com/carddemo/common/");
        assertThat(start)
                .as("the README must carry its tree listing, or this case is vacuous")
                .isNotNegative();
        String listing = readme.substring(start, readme.indexOf("```", start));

        List<String> named = new ArrayList<>();
        // Assumptions: the name class admits a HYPHEN, so that "package-info.java" is captured
        //   whole and filtered by name. Without it the capture began after the hyphen and every
        //   charter in the listing was read as a class called "info", which no directory holds.
        Matcher file = Pattern.compile("([A-Za-z0-9_-]+)\\.java").matcher(listing);
        while (file.find()) {
            if (!file.group(1).equals("package-info")) {
                named.add(file.group(1));
            }
        }

        List<String> measured = new ArrayList<>();
        for (String root : List.of(KERNEL_SOURCE_ROOT, KERNEL_TEST_ROOT)) {
            try (Stream<Path> walk = Files.walk(repositoryRoot().resolve(root))) {
                walk.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".java"))
                        .filter(name -> !name.equals(CHARTER_FILE_NAME))
                        .map(name -> name.substring(0, name.length() - ".java".length()))
                        .forEach(measured::add);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot walk " + root, unreadable);
            }
        }

        assertThat(named)
                .as("classes the README's listing names, against the module's own two source trees")
                .containsExactlyInAnyOrderElementsOf(measured);
    }

    /**
     * Confirms no census figure written in prose contradicts the directory it describes.
     *
     * <p>Purpose: this is the residual gap the labelled-sum case documents -- a charter that keeps a
     * stale figure in ordinary prose beside a correct canonical line -- closed by measurement rather
     * than left accepted. Every figure the review found stale lived in exactly that position: the
     * root charter said 43 classes and 54 units above a table that said 44 and 55, and the
     * {@code security} charter promised that a ninth class would fail the build in the same paragraph
     * that named eight.</p>
     *
     * <p>Assumptions: the check is by ADMISSIBLE VALUE rather than by parsing each sentence's
     * meaning. A figure written against one of the census nouns must be a number the directory
     * supports for that noun -- a per-package count or the kernel total for classes, a per-package
     * unit count or the kernel total for units, one or the charter count for charters, and the
     * subpackage count for subpackages. That admits a coincidence, in that a figure equal to a
     * different package's count passes; it refuses every value the tree does not produce at all,
     * which is the whole of the drift observed, and it needs no sentence-level grammar to do it.</p>
     *
     * <p>Assumptions: canonical lines are removed before the scan and retrospective paragraphs are
     * exempted, so this case never duplicates another's verdict and never fails a correction's own
     * account of what it corrected.</p>
     */
    @Test
    @DisplayName("no census figure in prose contradicts the directory it describes")
    void noProseCensusFigureContradictsTheDirectory() {
        Map<String, Integer> measured = measuredProductionClasses();
        int production = measured.values().stream().mapToInt(Integer::intValue).sum();
        int packages = measured.size();
        Map<String, List<Integer>> admissible = new LinkedHashMap<>();
        List<Integer> classCounts = new ArrayList<>(measured.values());
        classCounts.add(production);
        List<Integer> unitCounts = measured.values().stream().map(count -> count + 1)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        unitCounts.add(production + packages);
        for (String noun : CENSUS_NOUNS) {
            if (noun.startsWith("production")) {
                admissible.put(noun, classCounts);
            } else if (noun.startsWith("compilation")) {
                admissible.put(noun, unitCounts);
            } else if (noun.equals("subpackages")) {
                admissible.put(noun, List.of(packages - 1));
            } else {
                admissible.put(noun, List.of(1, packages, packages + 1));
            }
        }

        List<String> documents = new ArrayList<>(charters().values());
        documents.add(KERNEL_README);
        int examined = 0;
        for (String document : documents) {
            String text = CANONICAL_LINE.matcher(read(document)).replaceAll("");
            for (String paragraph : text.split("(?m)^(?: \\*)?\\s*$")) {
                String prose = paragraph.replaceAll("\\s*\\*\\s*", " ");
                boolean retrospective = RETROSPECTIVE_MARKERS.stream()
                        .anyMatch(marker -> prose.toLowerCase(java.util.Locale.ROOT)
                                .contains(marker));
                for (String noun : CENSUS_NOUNS) {
                    // Assumptions: the separator admits a HYPHEN as well as a space, both before the
                    //   noun and inside it, because a figure can be written attributively -- "the
                    //   55-compilation-unit total" -- and that form carried one of the stale numbers
                    //   this case was written for.
                    // Refactoring Rationale: the noun is quoted WORD BY WORD and the separators are
                    //   inserted between the quoted parts. An earlier form quoted the whole noun and
                    //   then substituted inside the result, which put the separator class inside the
                    //   quotation and so matched the literal text "production[- ]classes" -- nothing
                    //   at all. The scan still passed its own floor on the single-word nouns, which
                    //   is exactly the silent weakening a negative check exists to catch.
                    String nounPattern = java.util.Arrays.stream(noun.split(" "))
                            .map(Pattern::quote)
                            .collect(java.util.stream.Collectors.joining("[- ]"));
                    Matcher census = Pattern.compile("([A-Za-z]+(?:-[a-z]+)?|\\d+)"
                            + "(?:\\*\\*)?[- ]" + nounPattern + "\\b").matcher(prose);
                    while (census.find()) {
                        Integer value = figure(census.group(1));
                        if (value == null) {
                            continue;
                        }
                        examined++;
                        if (retrospective) {
                            continue;
                        }
                        assertThat(value)
                                .as("'%s %s' in %s must be a figure this module's directory"
                                        + " supports; a paragraph describing an EARLIER state is"
                                        + " exempt and this one carries no retrospective marker",
                                        census.group(1), noun, document)
                                .isIn(admissible.get(noun));
                    }
                }
            }
        }
        assertThat(examined)
                .as("census figures examined across the kernel's charters and README; none would"
                        + " mean the nouns moved and this case stopped checking anything")
                .isGreaterThan(20);
    }
}
