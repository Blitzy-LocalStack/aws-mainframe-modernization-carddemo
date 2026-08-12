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
 * Holds the test census a service README publishes to the test tree that README describes.
 *
 * <p>Every service README states how many test classes the module carries, split into the Surefire tier
 * matching {@code *Test} and the Failsafe tier matching {@code *IT}. Those figures are prose: nothing in
 * the build reads them, so adding a test class falsifies a sentence in a file the change never touches.
 * That is not a hypothetical failure mode. The code review this class answers found a README claiming 26
 * classes where the tree held 28, with the two omitted classes being the web-layer tests for two
 * published routes -- an omission that reads as a coverage gap and invites a duplicate class to fill it.
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
 * <p>Trade-offs: this class checks the two totals and not the per-package table beside them. Checking the
 * table would mean parsing Markdown cells and mapping backtick-quoted names onto files, which is the
 * brittleness this design set out to avoid; the totals are what a reader plans work against, and a class
 * added without its table row still fails here because the total moves.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter or return documentation.
 */
@DisplayName("Every service README's published test census matches that module's test tree")
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
}
