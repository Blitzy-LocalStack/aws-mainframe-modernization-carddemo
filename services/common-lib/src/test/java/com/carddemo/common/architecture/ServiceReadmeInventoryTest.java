package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every inventory a service README publishes -- test census, production inventory and closed file
 * listing -- to the source tree that README describes.
 *
 * <p>Every service README states how many test classes the module carries, split into the Surefire tier
 * matching {@code *Test} and the Failsafe tier matching {@code *IT}. Those figures are prose: nothing in
 * the build reads them, so adding a test class falsifies a sentence in a file the change never touches.
 * That is not a hypothetical failure mode. The code review this class answers found a README claiming 26
 * classes where the tree held 28, with the two omitted classes being the web-layer tests for two
 * published routes -- an omission that reads as a coverage gap and invites a duplicate class to fill it.
 *
 * <p>Refactoring Rationale: two further inventories are governed here, and they were added because the
 * same drift recurred in the production direction. A later review found this module's own README
 * publishing forty-one production classes over fifty-two compilation units where the tree held
 * forty-four over fifty-five, and found the file listing beside those figures -- which the README's own
 * prose calls CLOSED, and argues at length that a closed listing must be -- omitting eight files that
 * exist, two of them production security controls. Correcting the numbers would have left the same trap
 * armed, so the numbers and the listing are both read from disk now: a production inventory publishes
 * itself through {@link #SOURCE_MARKER} and a closed listing declares its own boundaries with
 * {@link #LISTING_BEGIN} and {@link #LISTING_END}. Alternatives Considered: generating the listing.
 * Rejected for the reason recorded below for the census -- a generated block must be regenerated and
 * committed to stay honest, which relocates the drift rather than removing it, whereas a CHECKED
 * hand-written listing keeps the prose judgement and fails the build the moment it stops being true.
 *
 * <p>Assumptions: a README opts in by carrying one canonical marker comment, and a README without the
 * marker is ignored rather than failed. That is deliberate and it mirrors
 * {@link PackageCharterInventoryTest}, which governs the equivalent claim inside package charters the
 * same way. Alternatives Considered: parsing the census sentence itself, which reads "**28** test
 * classes: **25** ... and **3** ...". Rejected because the sentence is written for a human and its
 * wording differs between modules, so a regex over it would either be so loose that it matched the wrong
 * bolded number or so tight that an editorial rewording broke the build for no reason. An HTML comment is
 * invisible in rendered Markdown, is stable under rewording, and states plainly that it is machine-read.
 *
 * <p>Alternatives Considered: generating the census into each README from the tree. Rejected because the
 * generated block would have to be regenerated and committed to stay honest, which relocates the drift
 * rather than removing it, and because the surrounding prose -- which package holds which class, and what
 * each one pins -- is judgement a generator cannot supply.
 *
 * <p>Trade-offs: this class checks the two census totals and not the per-package table beside them.
 * Checking the table would mean parsing Markdown cells and mapping backtick-quoted names onto files, which
 * is the brittleness this design set out to avoid; the totals are what a reader plans work against, and a
 * class added without its table row still fails here because the total moves. The closed-listing case
 * below is not an exception to that reasoning: it reads a region a README explicitly delimits, in which
 * every entry is a bare file name, so nothing about Markdown structure is being parsed.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter or return documentation.
 */
@DisplayName("Every inventory a service README publishes matches that module's source tree")
final class ServiceReadmeInventoryTest {

    /**
     * The directory holding one subdirectory per Maven module, relative to the repository root.
     */
    private static final String SERVICES_DIRECTORY = "services";

    /**
     * A file that exists only at the repository root, used to recognise it while walking upwards.
     *
     * <p>Assumptions: the aggregator POM is the anchor rather than {@code .git}, because a worktree or an
     * exported archive may carry no {@code .git} entry while still being a complete checkout.
     */
    private static final String ROOT_MARKER = "services/pom.xml";

    /**
     * The canonical marker a README carries to opt in, capturing both published figures.
     *
     * <p>Assumptions: the two numbers are captured in the order the census states them -- the Surefire
     * tier first, then the Failsafe tier -- and the wording between them is fixed so that a README cannot
     * appear to opt in while stating something this class does not actually check.
     */
    private static final Pattern MARKER =
            Pattern.compile("<!--\\s*test-inventory:\\s*(\\d+)\\s+tests\\s*\\+\\s*(\\d+)\\s+integration tests\\s*-->");

    /**
     * The marker a README carries to publish its PRODUCTION tree's inventory, capturing three figures.
     *
     * <p>Assumptions: this marker governs {@code src/main/java} while {@link #MARKER} above governs
     * {@code src/test/java}, and the two are separate rather than one marker carrying five numbers. A
     * README may describe one tree and not the other, and a module that adopted a combined marker would
     * have to publish a figure it had not measured in order to opt in at all.
     *
     * <p>Assumptions: three figures are captured rather than one, because they fail independently. A
     * production type is a compilation unit that is not a package charter, a charter is one
     * {@code package-info.java}, and a package is a directory holding at least one source file, so a class
     * landing in a new package moves all three while a class landing in an existing package moves only the
     * first. A marker publishing their sum would be satisfied by two errors that cancelled.
     */
    private static final Pattern SOURCE_MARKER = Pattern.compile(
            "<!--\\s*source-inventory:\\s*(\\d+)\\s+production types\\s*\\+\\s*(\\d+)"
                    + "\\s+charters in\\s+(\\d+)\\s+packages\\s*-->");

    /**
     * Opens the region of a README whose file listing is claimed to be closed over the module's tree.
     *
     * <p>Assumptions: the region is delimited rather than inferred, and that is the whole reason this
     * check is safe to run. A README legitimately names Java files that belong to other modules or to no
     * module at all -- this repository's own service READMEs name a per-service application class
     * generically, for instance -- so a check reading every file name in a README would report those as
     * files absent from the tree. Delimiters make the CLAIM explicit: inside them the listing asserts it
     * is complete, outside them prose is free.
     */
    private static final String LISTING_BEGIN = "<!-- source-listing:begin -->";

    /**
     * Closes the region opened by {@link #LISTING_BEGIN}.
     */
    private static final String LISTING_END = "<!-- source-listing:end -->";

    /**
     * Matches one Java file name, including the hyphen a package charter's name carries.
     *
     * <p>Assumptions: the hyphen is in the character class deliberately. Omitting it -- which is the
     * obvious first form of this pattern -- does not fail to match {@code package-info.java}; it matches
     * the {@code info.java} tail instead, which then reports twenty-three charters as twenty-three files
     * that do not exist while reporting the charters themselves as unlisted. Measured on this module's own
     * README while this check was being written.
     */
    private static final Pattern JAVA_FILE_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.java");

    /**
     * The file name every package charter carries, which is counted apart from production types.
     */
    private static final String CHARTER_FILE_NAME = "package-info.java";

    /**
     * The smallest number of READMEs that must carry the marker for this class to be doing any work.
     *
     * <p>Assumptions: this is a floor rather than an exact count, so adopting the marker in a further
     * README never has to be accompanied by an edit here. It is raised as modules adopt the marker --
     * seven carry it today: {@code account-service}, {@code card-service}, {@code common-lib},
     * {@code transaction-service}, {@code reference-service}, {@code authorization-service} and
     * {@code reporting-service} -- because a floor left at one would let six of the seven lose their
     * marker unnoticed. Trade-offs: a
     * floor still cannot notice the LAST marked README losing its marker, which is the residual gap
     * accepted here; raising the floor to an exact count instead would fail the build on the legitimate
     * removal of a module, which is a worse trade for a check whose purpose is to catch drift.
     */
    private static final int MINIMUM_MARKED_READMES = 7;

    /**
     * The smallest number of READMEs that must publish a production inventory or a closed listing.
     *
     * <p>Assumptions: this floor is one rather than seven because one module adopts these two markers
     * today -- {@code common-lib}, whose README enumerates the shared kernel's whole tree class by class
     * and states in prose that the enumeration is CLOSED. The other eight service READMEs describe their
     * trees without claiming completeness, so requiring the markers there would demand a claim they do not
     * make. Trade-offs: a floor of one cannot notice six of seven markers disappearing, which is why the
     * test-census floor above is seven; here there is only one to lose, and losing it fails this floor.
     */
    private static final int MINIMUM_SOURCE_MARKED_READMES = 1;

    /**
     * Locates the repository root by walking upwards until the aggregator POM is visible.
     *
     * @return the repository root directory; never {@code null}
     * @throws IllegalStateException when no ancestor of the working directory contains the anchor, which
     *     means the test is running from outside a checkout and no assertion here could be meaningful
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
                "no ancestor of " + Path.of("").toAbsolutePath() + " contains " + ROOT_MARKER
                        + ", so the repository root could not be located");
    }

    /**
     * Reads a file as UTF-8.
     *
     * @param file the file to read; must not be {@code null}
     * @return the file's entire content; never {@code null}
     * @throws UncheckedIOException when the file cannot be read, which is a broken checkout rather than a
     *     failed assertion and is therefore raised rather than reported as a test failure
     */
    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * Counts the Java source files under a directory whose name ends with a given suffix.
     *
     * @param directory the directory to walk; may be absent, in which case the count is zero
     * @param suffix the file-name suffix to match, excluding the {@code .java} extension; must not be
     *     {@code null}
     * @return the number of matching files, zero or more
     * @throws UncheckedIOException when the tree cannot be walked
     */
    private static long countClasses(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(suffix + ".java"))
                    .count();
        } catch (IOException cause) {
            throw new UncheckedIOException("could not walk " + directory, cause);
        }
    }

    /**
     * Collects every service README that carries the canonical marker, keyed by module directory name.
     *
     * @return the marked READMEs in module order; never {@code null}, possibly empty
     * @throws UncheckedIOException when the services directory cannot be listed
     */
    private static Map<String, Path> markedReadmes() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        Map<String, Path> marked = new TreeMap<>();
        try (Stream<Path> modules = Files.list(services)) {
            modules.filter(Files::isDirectory).forEach(module -> {
                Path readme = module.resolve("README.md");
                if (Files.isRegularFile(readme) && MARKER.matcher(readFile(readme)).find()) {
                    marked.put(module.getFileName().toString(), readme);
                }
            });
        } catch (IOException cause) {
            throw new UncheckedIOException("could not list " + services, cause);
        }
        return marked;
    }

    /**
     * Collects every service README whose text contains a given marker or delimiter.
     *
     * @param signature the literal text or pattern source a README must contain to opt in; must not be
     *     {@code null}
     * @param asPattern whether the signature is a regular expression rather than a literal
     * @return the opted-in READMEs keyed by module directory name; never {@code null}, possibly empty
     * @throws UncheckedIOException when the services directory cannot be listed
     */
    private static Map<String, Path> readmesContaining(String signature, boolean asPattern) {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        Map<String, Path> marked = new TreeMap<>();
        try (Stream<Path> modules = Files.list(services)) {
            modules.filter(Files::isDirectory).forEach(module -> {
                Path readme = module.resolve("README.md");
                if (!Files.isRegularFile(readme)) {
                    return;
                }
                String text = readFile(readme);
                boolean present = asPattern
                        ? Pattern.compile(signature).matcher(text).find()
                        : text.contains(signature);
                if (present) {
                    marked.put(module.getFileName().toString(), readme);
                }
            });
        } catch (IOException cause) {
            throw new UncheckedIOException("could not list " + services, cause);
        }
        return marked;
    }

    /**
     * Collects the Java source file names held beneath a directory, one entry per file.
     *
     * @param directory the directory to walk; may be absent, in which case the result is empty
     * @return every Java file name beneath it, in no particular order; never {@code null}
     * @throws UncheckedIOException when the tree cannot be walked
     */
    private static List<String> javaFileNames(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("could not walk " + directory, cause);
        }
    }

    /**
     * Holds each published production inventory to the module's own {@code src/main/java} tree.
     *
     * <p>Assumptions: this case exists because a production inventory drifts in the direction a test census
     * cannot. A test census understates coverage, which reads as a gap; a production inventory understates
     * the SURFACE, which reads as an absent capability and sends a contributor to write a class that is
     * already there. The code review this case answers found this module's README publishing forty-one
     * production classes over fifty-two compilation units where the tree held forty-four over fifty-five,
     * with the two unlisted classes being a request-size filter and an origin policy -- both of them
     * security controls, so the understatement read as two controls that did not exist.
     *
     * <p>Assumptions: the vacuity floor is asserted inside this case rather than as a case of its own,
     * because the two published-figure cases in this class each iterate the same opted-in set and a
     * separate floor case would report the same fact twice.
     */
    @Test
    @DisplayName("each published production inventory equals the module's own src/main/java tree")
    void eachPublishedSourceInventoryMatchesItsModule() {
        Map<String, Path> marked = readmesContaining(SOURCE_MARKER.pattern(), true);
        assertThat(marked)
                .as("service READMEs carrying the source-inventory marker; if this is empty this case"
                        + " asserts nothing at all")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SOURCE_MARKED_READMES);

        List<String> mismatches = new ArrayList<>();
        marked.forEach((module, readme) -> {
            Matcher marker = SOURCE_MARKER.matcher(readFile(readme));
            if (!marker.find()) {
                throw new IllegalStateException("marker vanished between passes in " + readme);
            }
            Path source = repositoryRoot()
                    .resolve(SERVICES_DIRECTORY)
                    .resolve(module)
                    .resolve("src/main/java");
            List<String> held = javaFileNames(source);
            long charters = held.stream().filter(CHARTER_FILE_NAME::equals).count();
            long productionTypes = held.size() - charters;

            // WHY : Refactoring Rationale: the three comparisons are carried in a record rather than in
            //       two parallel arrays indexed together. The arrays were correct, and they were replaced
            //       because a figure and the word naming it were kept in step only by their subscripts:
            //       inserting a fourth comparison and forgetting its subject, or the reverse, compiles
            //       and then reports the wrong count under the right name -- a failure message that
            //       actively misleads whoever reads it.
            List<PublishedFigure> figures = List.of(
                    new PublishedFigure("production types",
                            Long.parseLong(marker.group(1)), productionTypes),
                    new PublishedFigure("package charters",
                            Long.parseLong(marker.group(2)), charters),
                    new PublishedFigure("packages",
                            Long.parseLong(marker.group(3)), countPackages(source)));
            for (PublishedFigure figure : figures) {
                if (figure.published() != figure.actual()) {
                    mismatches.add(String.format(
                            Locale.ROOT,
                            "%s/README.md publishes %d %s, its tree holds %d",
                            module,
                            figure.published(),
                            figure.name(),
                            figure.actual()));
                }
            }
        });

        if (!mismatches.isEmpty()) {
            fail("a README's published production inventory no longer matches its module; update the"
                    + " inventory and the source-inventory marker beside it: "
                    + String.join("; ", mismatches));
        }
    }

    /**
     * No service README carries the withdrawn second census marker.
     *
     * <p>⚠️ Purpose: the withdrawal recorded on {@link #WITHDRAWN_MAIN_INVENTORY_MARKER} is only a
     * withdrawal while nothing publishes that marker again. Its pattern and its case are gone, so a README
     * reintroducing it would be checked by NOTHING -- it would state a census that no case measures, which
     * is strictly worse than the redundancy the withdrawal removed. This case is what makes the removal
     * hold rather than merely having happened once.</p>
     *
     * <p>Assumptions: the marker's PUBLISHING FORM is matched -- its comment opener and name, up to the
     * colon -- rather than either its bare name or its whole shape. Its bare name would match the prose
     * that RECORDS the withdrawal, so this module's own README would fail the case documenting the very
     * decision it enforces; its whole shape would pass a marker whose figures had been reordered, which
     * is the same unchecked claim in a different arrangement. The opener is present in every form that
     * actually publishes a census and in no form that merely discusses one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no README republishes the withdrawn second census marker")
    void noReadmeRepublishesTheWithdrawnMainInventoryMarker() {
        assertThat(readmesContaining(WITHDRAWN_MAIN_INVENTORY_MARKER, false))
                .as("the marker opening %s was withdrawn because it restated the source-inventory census;"
                        + " a README publishing it again would state a figure no case measures",
                        WITHDRAWN_MAIN_INVENTORY_MARKER)
                .isEmpty();
    }

    /**
     * Holds each delimited file listing to the module's own tree in BOTH directions.
     *
     * <p>Assumptions: both directions are checked because they mislead a reader differently. A listing
     * naming a file that does not exist sends a reader hunting for it; a listing omitting a file that does
     * exist invites a duplicate of work already done. This module's README states exactly that pair of
     * consequences in prose and calls its own listing CLOSED, and the code review this case answers found
     * eight files absent from it -- so the prose was the only thing asserting the property it claimed.
     *
     * <p>Assumptions: the comparison is by file NAME rather than by path, which is what lets a listing lay
     * a tree out for a reader -- indented under its package, several names to a line -- instead of
     * repeating a path per entry. It is sound here because the two names that recur across packages, the
     * package charter and nothing else, are present once per package in both the listing and the tree.
     *
     * <p>Trade-offs: the comparison is by SET rather than by multiset, so a name listed twice while nothing
     * is missing passes. Accepted: a duplicate entry is visible to any reader of the listing and misleads
     * nobody about what exists, whereas the multiset form would fail a listing that annotated one file in
     * two places, which is a legitimate thing for a map of a tree to do.
     */
    @Test
    @DisplayName("each delimited source listing names exactly the Java files its module holds")
    void eachDelimitedSourceListingNamesExactlyTheTree() {
        Map<String, Path> marked = readmesContaining(LISTING_BEGIN, false);
        assertThat(marked)
                .as("service READMEs opening a delimited source listing; if this is empty this case"
                        + " asserts nothing at all")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SOURCE_MARKED_READMES);

        List<String> mismatches = new ArrayList<>();
        marked.forEach((module, readme) -> {
            String text = readFile(readme);
            int begin = text.indexOf(LISTING_BEGIN);
            int end = text.indexOf(LISTING_END);
            if (end < begin) {
                mismatches.add(module + "/README.md opens a source listing it never closes with "
                        + LISTING_END);
                return;
            }
            Matcher names = JAVA_FILE_NAME.matcher(text.substring(begin, end));
            List<String> listed = new ArrayList<>();
            while (names.find()) {
                listed.add(names.group());
            }
            List<String> held = javaFileNames(
                    repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(module).resolve("src"));

            List<String> unlisted = held.stream().distinct().filter(name -> !listed.contains(name))
                    .sorted().toList();
            List<String> absent = listed.stream().distinct().filter(name -> !held.contains(name))
                    .sorted().toList();
            if (!unlisted.isEmpty()) {
                mismatches.add(module + "/README.md holds a closed listing that omits " + unlisted);
            }
            if (!absent.isEmpty()) {
                mismatches.add(module + "/README.md lists files its tree does not hold " + absent);
            }
        });

        if (!mismatches.isEmpty()) {
            fail("a README's delimited source listing no longer matches its module's tree: "
                    + String.join("; ", mismatches));
        }
    }

    /**
     * Confirms that the mechanism is measuring something, so that a vacuous pass is impossible.
     *
     * <p>Assumptions: this case exists because every other case in this class iterates the marked set. If
     * the marker were renamed, or the comment deleted from every README, that iteration would be empty
     * and the remaining cases would pass while checking nothing -- the exact failure this suite is meant
     * to catch in documentation, reproduced in the test that catches it.
     */
    @Test
    @DisplayName("at least one service README opts into the census check")
    void theCensusCheckIsNotVacuous() {
        Map<String, Path> marked = markedReadmes();
        assertThat(marked)
                .as("service READMEs carrying the test-inventory marker; if this is empty the remaining"
                        + " cases in this class assert nothing at all")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_MARKED_READMES);
    }

    /**
     * Holds each marked README's two published figures to the module's own test tree.
     *
     * <p>Assumptions: both tiers are counted from file names rather than from compiled classes, because
     * the census describes source files a reader can list, and because a nested or abstract helper that
     * never becomes its own test class would otherwise be counted. Trade-offs: a file named for the
     * suffix but holding no test case would be counted here and by a reader running {@code ls} alike,
     * which keeps this check and the sentence it guards measuring the same thing.
     */
    @Test
    @DisplayName("each published census equals the count of *Test and *IT sources in that module")
    void eachPublishedCensusMatchesItsModule() {
        List<String> mismatches = new ArrayList<>();
        markedReadmes().forEach((module, readme) -> {
            Matcher marker = MARKER.matcher(readFile(readme));
            if (!marker.find()) {
                throw new IllegalStateException("marker vanished between passes in " + readme);
            }
            long publishedTests = Long.parseLong(marker.group(1));
            long publishedIntegrationTests = Long.parseLong(marker.group(2));

            Path tests = repositoryRoot()
                    .resolve(SERVICES_DIRECTORY)
                    .resolve(module)
                    .resolve("src/test/java");
            long actualTests = countClasses(tests, "Test");
            long actualIntegrationTests = countClasses(tests, "IT");

            if (publishedTests != actualTests) {
                mismatches.add(String.format(
                        Locale.ROOT,
                        "%s/README.md publishes %d *Test classes, its tree holds %d",
                        module,
                        publishedTests,
                        actualTests));
            }
            if (publishedIntegrationTests != actualIntegrationTests) {
                mismatches.add(String.format(
                        Locale.ROOT,
                        "%s/README.md publishes %d *IT classes, its tree holds %d",
                        module,
                        publishedIntegrationTests,
                        actualIntegrationTests));
            }
        });

        if (!mismatches.isEmpty()) {
            fail("a README's published test census no longer matches its module; update the census and the"
                    + " marker comment beside it: " + String.join("; ", mismatches));
        }
    }

    /**
     * Confirms the census total stated in prose agrees with the marker's two parts.
     *
     * <p>Assumptions: the README states a total as well as the two tiers, and a reader takes the total at
     * face value. Checking that the bolded total equals the sum catches the specific editing slip of
     * correcting one number and leaving the other, which is how a census drifts even when someone did
     * look at it. Trade-offs: the total is located by a narrow pattern anchored on the words that follow
     * it, so a README that rewords the sentence entirely stops being checked here rather than failing --
     * the per-tier case above remains the binding one.
     */
    @Test
    @DisplayName("where a README states a census total, it equals the sum of the two tiers")
    void eachStatedTotalEqualsTheSumOfItsTiers() {
        Pattern total = Pattern.compile("\\*\\*(\\d+)\\*\\* test classes");
        List<String> mismatches = new ArrayList<>();
        markedReadmes().forEach((module, readme) -> {
            String text = readFile(readme);
            Matcher marker = MARKER.matcher(text);
            if (!marker.find()) {
                throw new IllegalStateException("marker vanished between passes in " + readme);
            }
            long sum = Long.parseLong(marker.group(1)) + Long.parseLong(marker.group(2));
            Matcher stated = total.matcher(text);
            if (stated.find()) {
                long statedTotal = Long.parseLong(stated.group(1));
                if (statedTotal != sum) {
                    mismatches.add(String.format(
                            Locale.ROOT,
                            "%s/README.md states a total of %d test classes but its marker sums to %d",
                            module,
                            statedTotal,
                            sum));
                }
            }
        });

        if (!mismatches.isEmpty()) {
            fail("a README's stated total disagrees with its own per-tier figures: "
                    + String.join("; ", mismatches));
        }
    }

    /**
     * The production-tree census this class holds a README to, and the one it no longer holds it to.
     *
     * <p>⚠️ Refactoring Rationale: a second marker stood here, {@code <!-- main-inventory: N packages + N
     * classes + N units -->}, with its own pattern, its own case and its own README-collecting helper. It
     * is WITHDRAWN, because it published the SAME census as {@link #SOURCE_MARKER} in a different
     * spelling: its "classes" figure is that marker's production types, its "packages" figure is that
     * marker's packages, and its "compilation units" figure is the sum of that marker's two, since a
     * compilation unit under {@code src/main/java} is either a production type or a package charter. Two
     * markers over one tree therefore obliged every README to state one census twice while nothing
     * checked the two statements against EACH OTHER -- so the redundancy could not even detect its own
     * drift, which is the one thing a second copy might have bought.</p>
     *
     * <p>Assumptions: the surviving marker is the one that splits charters out rather than folding them
     * into a total, and that choice is not arbitrary. The three figures fail independently -- a type
     * landing in a new package moves all three, a type landing in an existing package moves only the
     * first -- so a marker whose third figure is the sum of its first two can be satisfied by two errors
     * that cancel. It is also the marker the closed source-listing region is anchored beside, so keeping
     * it leaves the census and the listing describing one tree through one opt-in.</p>
     */
    private static final String WITHDRAWN_MAIN_INVENTORY_MARKER = "<!-- main-inventory:";

    /**
     * Counts the directories under a source root that hold at least one Java source file.
     *
     * <p>Assumptions: a directory holding only further directories is NOT a package, because it declares
     * no type and carries no charter -- the {@code com} and {@code com/carddemo} levels of every module
     * are exactly that. Counting them would inflate every module's figure by the depth of its root
     * package, which is a property of the naming convention rather than of the module.
     *
     * <p>⚠️ Refactoring Rationale: a second counter stood beside this one, reached from the source-inventory
     * case, which walked to each Java FILE and counted its distinct parent directory. The two agree on
     * every tree -- a directory holds at least one source file exactly when at least one source file has
     * it as a parent -- so it is retired rather than kept, because two spellings of one measurement are
     * two places for the definition of "package" to drift and only one of them would have been updated.
     *
     * @param sourceRoot the source root to walk; may be absent, in which case the count is zero
     * @return the number of directories holding at least one {@code .java} file, zero or more
     * @throws UncheckedIOException when the tree cannot be walked
     */
    private static long countPackages(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            return 0L;
        }
        try (Stream<Path> tree = Files.walk(sourceRoot)) {
            return tree.filter(Files::isDirectory)
                    .filter(ServiceReadmeInventoryTest::holdsJavaSource)
                    .count();
        } catch (IOException cause) {
            throw new UncheckedIOException("could not walk " + sourceRoot, cause);
        }
    }

    /**
     * One published figure paired with the tree measurement it claims to describe.
     *
     * <p>Assumptions: the three figures of a production inventory are checked in one pass and reported
     * together, so a README stating two correct numbers and one wrong one names the wrong one rather than
     * failing on the first difference and hiding the rest.
     *
     * @param name what the figure counts, reproduced verbatim in the failure message
     * @param published the value the README states
     * @param actual the value measured from the module's tree
     */
    private record PublishedFigure(String name, long published, long actual) {
    }

    /**
     * Reports whether a directory holds at least one Java source file directly.
     *
     * @param directory the directory to inspect; must not be {@code null}
     * @return {@code true} when the directory holds a {@code .java} file of its own
     * @throws UncheckedIOException when the directory cannot be listed
     */
    private static boolean holdsJavaSource(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.anyMatch(entry ->
                    Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".java"));
        } catch (IOException cause) {
            throw new UncheckedIOException("could not list " + directory, cause);
        }
    }
}
