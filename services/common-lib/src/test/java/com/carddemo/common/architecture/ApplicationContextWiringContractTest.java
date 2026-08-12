package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every deployable in this reactor could actually start, in the three ways one of them
 * could not.
 *
 * <p><b>Purpose.</b> A Spring application fails to refresh its context for reasons a compiler cannot
 * see, and this repository has now hit three of them for real. All three were found by running a
 * bootable jar rather than by any test, because no test in any module loads its own application: every
 * context-loading test defines a narrow {@code @SpringBootConfiguration} of its own, which is correct
 * for what those tests assert and is exactly why they cannot see production wiring. The three:</p>
 * <ol>
 *   <li>{@code authorization-service}'s {@code PendingAuthViewMapper} carried NO stereotype annotation
 *       while three {@code @Service} classes declared it as a constructor parameter, so the context
 *       failed with {@code NoSuchBeanDefinitionException} naming that type.</li>
 *   <li>{@code account-service}'s {@code RestReferenceAddressLookup} and, separately,
 *       {@code authorization-service}'s {@code RestAccountContextClient} each declared TWO
 *       constructors — a public one and a package-private test seam — with neither marked, so the
 *       container stopped looking for an injectable constructor and failed with "No default constructor
 *       found".</li>
 *   <li>Both of those modules injected {@code RestClient.Builder} while declaring only
 *       {@code spring-boot-starter-web}. Spring Boot 4 split the HTTP clients out of that starter:
 *       {@code RestClient} is an API in {@code spring-web} so the code COMPILES, but
 *       {@code RestClientAutoConfiguration} — the only publisher of the builder bean — ships in
 *       {@code spring-boot-restclient}.</li>
 * </ol>
 *
 * <p>Assumptions: this test reads the REPOSITORY as text rather than reflecting over a class path, for
 * the same reason {@link RuntimeConfigurationContractTest} and {@link RuntimeDeletePrivilegeContractTest}
 * do. The artifacts that have to agree are nine modules' Java sources and nine POMs, and this shared
 * kernel is a DEPENDENCY of every module it reasons about, so it can never see their compiled classes.
 * Reflection is not available and text is not a shortcut. The repository root is located by walking up
 * from the working directory, and a failure to find it FAILS rather than skips.</p>
 *
 * <p>Trade-offs: a text scan cannot resolve an import, so a type is treated as this module's own when a
 * file of that simple name exists in the same module's main tree. That is the boundary the defect lives
 * on anyway — a collaborator a module declares and forgets to publish — and it keeps the check free of
 * every framework and library type, which are published by machinery this test has no business
 * modelling. Alternatives Considered: a real {@code @SpringBootTest} per module against Testcontainers
 * plus a stub issuer and a stub queue. Rejected as the wrong instrument for these three defects rather
 * than as valueless: it would be nine slow, environment-dependent tests to catch a class of mistake that
 * is visible in the source, and the two it could not catch at all are the POM one and any module whose
 * dependencies are not reachable from the test runner.</p>
 *
 * @see RuntimeConfigurationContractTest
 * @see LayeringRulesTest
 */
class ApplicationContextWiringContractTest {

    /**
     * The path, relative to the repository root, that identifies the root itself.
     */
    private static final String ROOT_MARKER = "services/pom.xml";

    /**
     * The directory holding one subdirectory per Maven module.
     */
    private static final String SERVICES_DIRECTORY = "services";

    /**
     * The stereotype annotations that make a class a component the container instantiates.
     *
     * <p>Assumptions: {@code @Configuration} is included because such a class is instantiated and its
     * constructor injected exactly like any other component — {@code StepFunctionsConfig} takes a
     * {@code @Value} through one — so a configuration class is subject to the same constructor rule.</p>
     */
    private static final Pattern STEREOTYPE = Pattern.compile(
            "^@(Component|Service|RestController|Controller|Repository|Configuration"
                    + "|RestControllerAdvice|ControllerAdvice)\\b",
            Pattern.MULTILINE);

    /**
     * A Spring Data interface, which the framework publishes without any annotation.
     *
     * <p>Assumptions: the bare {@code Repository} base is matched as well as the three common
     * sub-interfaces, because seventeen interfaces in this reactor extend it directly in order to expose
     * only the methods they declare. Omitting it would report every one of them as unpublished.</p>
     */
    private static final Pattern DATA_REPOSITORY = Pattern.compile(
            "interface\\s+\\w+\\s*(?:<[^>]*>)?\\s*extends\\s+(?:[\\w.]*\\.)?"
                    + "(JpaRepository|CrudRepository|PagingAndSortingRepository|Repository)\\b",
            Pattern.DOTALL);

    /**
     * A {@code @Bean} factory method, captured for the simple name of its declared return type.
     */
    private static final Pattern BEAN_METHOD = Pattern.compile(
            "@Bean[^\\n]*\\n(?:\\s*@\\w+[^\\n]*\\n)*\\s*(?:public|protected|private)?\\s*"
                    + "([\\w.<>\\[\\]]+)\\s+\\w+\\s*\\(");

    /**
     * The supertypes a class declares, so that an interface implemented by a component counts as
     * published.
     */
    private static final Pattern SUPERTYPES =
            Pattern.compile("\\b(?:implements|extends)\\s+([\\w.,<>\\s]+?)\\s*\\{");

    /**
     * One declared parameter, matched for its type's simple name.
     */
    private static final Pattern PARAMETER =
            Pattern.compile("(?:^|[,\\s(])([A-Z]\\w+)(?:<[^>]*>)?\\s+\\w+\\s*(?:,|$)");

    /**
     * The starter that carries {@code RestClientAutoConfiguration} under Spring Boot 4.
     */
    private static final String RESTCLIENT_STARTER = "spring-boot-starter-restclient";

    /**
     * The type whose bean only that starter publishes.
     */
    private static final String RESTCLIENT_BUILDER = "RestClient.Builder";

    /**
     * The smallest number of modules this class must have found for it to be doing any work.
     *
     * <p>Assumptions: a floor rather than an exact count, so adding a module never has to be accompanied
     * by an edit here. Nine exist today — the shared kernel and the eight bounded contexts — and a floor
     * left at one would let eight of them stop being scanned unnoticed.</p>
     */
    private static final int MINIMUM_MODULES = 9;

    /**
     * Confirms every collaborator a component injects from its own module is a bean that module
     * publishes.
     *
     * <p>Assumptions: "publishes" means one of four things, and all four are needed. A class carrying a
     * stereotype; a type returned by a {@code @Bean} method; a Spring Data interface, which the framework
     * publishes from the interface alone; or a supertype of a stereotyped class, because an interface is
     * injected by its own name while the annotation sits on the implementation. Dropping any one of the
     * four turns legitimate wiring into a reported defect — the fourth alone accounts for
     * {@code AccountContextClient} and {@code AuthFraudUpserter}.</p>
     *
     * <p>Trade-offs: only constructor parameters are examined, and only of stereotyped classes. Field and
     * setter injection are not used anywhere in this reactor, and a plain class nobody injects cannot fail
     * a refresh, so widening the scan would add noise rather than coverage.</p>
     */
    @Test
    @DisplayName("every collaborator a component injects from its own module is published by that module")
    void everyInjectedFirstPartyTypeIsPublished() {
        List<String> unpublished = new ArrayList<>();
        Map<String, Path> modules = modulesWithMainSources();
        modules.forEach((module, mainTree) -> {
            Map<String, String> sources = mainSources(mainTree);
            Set<String> stereotyped = new TreeSet<>();
            Set<String> published = new TreeSet<>();
            sources.forEach((name, text) -> {
                if (STEREOTYPE.matcher(text).find()) {
                    stereotyped.add(name);
                }
                if (DATA_REPOSITORY.matcher(text).find()) {
                    published.add(name);
                }
                Matcher bean = BEAN_METHOD.matcher(text);
                while (bean.find()) {
                    published.add(simpleName(bean.group(1)));
                }
            });
            published.addAll(stereotyped);
            stereotyped.forEach(name -> {
                Matcher supertypes = SUPERTYPES.matcher(sources.get(name));
                while (supertypes.find()) {
                    for (String declared : supertypes.group(1).split(",")) {
                        published.add(simpleName(declared.trim()));
                    }
                }
            });

            for (String name : stereotyped) {
                Matcher constructor = Pattern.compile(
                                "^\\s*(?:public|protected|private)\\s+" + Pattern.quote(name)
                                        + "\\s*\\(([^)]*)\\)",
                                Pattern.MULTILINE | Pattern.DOTALL)
                        .matcher(sources.get(name));
                while (constructor.find()) {
                    Matcher parameter = PARAMETER.matcher(constructor.group(1));
                    while (parameter.find()) {
                        String type = parameter.group(1);
                        if (sources.containsKey(type) && !published.contains(type)) {
                            unpublished.add(String.format(
                                    Locale.ROOT,
                                    "%s: %s injects %s, which that module declares but never publishes",
                                    module,
                                    name,
                                    type));
                        }
                    }
                }
            }
        });

        assertThat(modules)
                .as("modules with a main source tree; an empty scan would pass this test vacuously")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_MODULES);
        if (!unpublished.isEmpty()) {
            fail("a component injects a collaborator its own module never publishes, so that"
                    + " application context cannot refresh; annotate the collaborator or publish it from a"
                    + " @Bean method: " + String.join("; ", unpublished));
        }
    }

    /**
     * Confirms every component declaring more than one constructor marks the one to inject.
     *
     * <p>Assumptions: implicit constructor injection applies only to a class with exactly ONE
     * constructor. With two and neither marked, the container does not choose — it falls back to a
     * no-argument constructor, and every such class here declares none, so bean creation fails with "No
     * default constructor found" and the whole context fails rather than degrading. The second
     * constructor in both real cases is a package-private test seam that exists for a measured reason
     * recorded on it, so the remedy is to mark the injection point rather than to withdraw the seam.</p>
     *
     * <p>Trade-offs: the check is satisfied by {@code @Autowired} appearing anywhere in the file rather
     * than by parsing which member carries it. A file with two constructors and the annotation on the
     * wrong one would pass here and fail at run time; pinning the annotation to a specific declaration
     * would need a parser, and the failure mode this catches is a MISSING annotation rather than a
     * misplaced one.</p>
     */
    @Test
    @DisplayName("every component with two constructors marks one for injection")
    void everyComponentWithSeveralConstructorsMarksOne() {
        List<String> unmarked = new ArrayList<>();
        Map<String, Path> modules = modulesWithMainSources();
        modules.forEach((module, mainTree) -> mainSources(mainTree).forEach((name, text) -> {
            if (!STEREOTYPE.matcher(text).find()) {
                return;
            }
            Matcher constructor = Pattern.compile(
                            "^\\s*(?:public|protected|private)?\\s*" + Pattern.quote(name) + "\\s*\\(",
                            Pattern.MULTILINE)
                    .matcher(text);
            int declared = 0;
            while (constructor.find()) {
                declared++;
            }
            if (declared > 1 && !text.contains("@Autowired")) {
                unmarked.add(String.format(
                        Locale.ROOT,
                        "%s: %s declares %d constructors and marks none with @Autowired",
                        module,
                        name,
                        declared));
            }
        }));

        assertThat(modules)
                .as("modules with a main source tree; an empty scan would pass this test vacuously")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_MODULES);
        if (!unmarked.isEmpty()) {
            fail("a component declares several constructors without marking the injection point, so the"
                    + " container will look for a no-argument constructor that does not exist: "
                    + String.join("; ", unmarked));
        }
    }

    /**
     * Confirms every module injecting the HTTP client builder declares the starter that publishes it.
     *
     * <p>Assumptions: this is a POM-versus-source agreement and nothing else can express it. The
     * omission is invisible to the compiler, because the type is an API in {@code spring-web} which the
     * web starter does bring; what the web starter stopped bringing in Spring Boot 4 is the
     * auto-configuration that publishes the builder BEAN. Both affected modules had passing suites while
     * their contexts could not refresh, because each adapter's own tests hand it a builder through a
     * package-private seam.</p>
     *
     * <p>Trade-offs: the source side is matched as the literal text {@code RestClient.Builder} rather
     * than as a resolved type. A module that imported the nested type and referred to it as
     * {@code Builder} alone would not be seen — accepted, because the qualified form is the idiom
     * everywhere in this reactor and a bare {@code Builder} would match a dozen unrelated builders.</p>
     */
    @Test
    @DisplayName("every module injecting RestClient.Builder declares the restclient starter")
    void everyModuleUsingTheHttpClientBuilderDeclaresItsStarter() {
        List<String> missing = new ArrayList<>();
        Map<String, Path> modules = modulesWithMainSources();
        modules.forEach((module, mainTree) -> {
            boolean injectsBuilder = mainSources(mainTree).values().stream()
                    .anyMatch(text -> text.contains(RESTCLIENT_BUILDER));
            if (!injectsBuilder) {
                return;
            }
            Path pom = repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(module).resolve("pom.xml");
            if (!readFile(pom).contains(RESTCLIENT_STARTER)) {
                missing.add(String.format(
                        Locale.ROOT,
                        "%s injects %s but its pom.xml does not declare %s",
                        module,
                        RESTCLIENT_BUILDER,
                        RESTCLIENT_STARTER));
            }
        });

        assertThat(modules)
                .as("modules with a main source tree; an empty scan would pass this test vacuously")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_MODULES);
        if (!missing.isEmpty()) {
            fail("a module injects the HTTP client builder without the starter that publishes it, so its"
                    + " context fails with no qualifying bean of that type: " + String.join("; ", missing));
        }
    }

    /**
     * Collects every module directory that has a main Java source tree, keyed by module name.
     *
     * @return the modules in name order; never {@code null}
     * @throws UncheckedIOException when the services directory cannot be listed
     */
    private static Map<String, Path> modulesWithMainSources() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        Map<String, Path> modules = new TreeMap<>();
        try (Stream<Path> candidates = Files.list(services)) {
            candidates.filter(Files::isDirectory).forEach(module -> {
                Path mainTree = module.resolve("src/main/java");
                if (Files.isDirectory(mainTree)) {
                    modules.put(module.getFileName().toString(), mainTree);
                }
            });
        } catch (IOException cause) {
            throw new UncheckedIOException("could not list " + services, cause);
        }
        return modules;
    }

    /**
     * Reads every Java source under a main tree, keyed by its simple name.
     *
     * <p>Assumptions: {@code package-info.java} is excluded because it declares no type, so its name
     * would be a key nothing can be injected by.</p>
     *
     * @param mainTree the module's main source directory; must not be {@code null}
     * @return each source's text keyed by file stem; never {@code null}
     * @throws UncheckedIOException when the tree cannot be walked
     */
    private static Map<String, String> mainSources(Path mainTree) {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> tree = Files.walk(mainTree)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !"package-info.java".equals(path.getFileName().toString()))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        sources.put(name.substring(0, name.length() - ".java".length()), readFile(path));
                    });
        } catch (IOException cause) {
            throw new UncheckedIOException("could not walk " + mainTree, cause);
        }
        return sources;
    }

    /**
     * Reduces a possibly qualified, possibly generic type reference to its simple name.
     *
     * @param reference the type as written in source; must not be {@code null}
     * @return the simple name, never {@code null}
     */
    private static String simpleName(String reference) {
        String withoutGenerics = reference.split("<")[0];
        int lastDot = withoutGenerics.lastIndexOf('.');
        return lastDot < 0 ? withoutGenerics : withoutGenerics.substring(lastDot + 1);
    }

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
}
