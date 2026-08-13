package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * Asserts that every runtime setting a service REQUIRES is one the infrastructure actually delivers, that
 * every setting the infrastructure delivers is one a service accepts, and that every such setting is named
 * by the Dockerfile header an operator reads.
 *
 * <p><b>Purpose.</b> A Spring configuration placeholder written {@code ${NAME}} with no fallback is not a
 * default: it is a bean that cannot be created. If nothing publishes {@code NAME}, the task starts, fails
 * context refresh and is replaced, and the deployment reports a service that will not stay up rather than
 * a setting nobody wired. That failure has occurred in this repository more than once -- the
 * pending-authorization context bound two such placeholders that neither environment root supplied, and
 * the card context was published a parameter its own task-definition contract did not admit, which made
 * that task definition unplannable. Both are the same defect in opposite directions, and this class is the
 * check that catches either.</p>
 *
 * <p>Assumptions: this test reads the REPOSITORY rather than the class path, which is unusual and
 * deliberate. The four artifacts that have to agree are a service's {@code application.yml}, the
 * name inventory in {@code infra/modules/ecs-service/main.tf}, the published parameters in the two
 * environment roots, and that service's own {@code Dockerfile} header -- and no two of those are ever on
 * one class path. A check that could see only one of
 * them could not detect a disagreement between them, which is the entire failure mode. The repository root
 * is located by walking up from the working directory, and a failure to find it FAILS rather than skips:
 * a check that silently does nothing is how a contract stops being enforced.</p>
 *
 * <p>Trade-offs: the agreement is tested by searching text rather than by parsing HCL. A parser would be
 * exact, and it would also mean either a dependency this shared kernel has no other use for or a
 * hand-written grammar with its own defects. The text search is deliberately coarse in ONE direction only:
 * it asks whether a required name appears in a root at all, not whether it appears in the right map. That
 * is enough to catch the defect that actually happens -- a name that appears nowhere in {@code infra/} --
 * and the exact placement is already checked by the module's own lifecycle precondition, which is where an
 * exact check belongs because only Terraform can evaluate it.</p>
 *
 * @see LayeringRulesTest
 */
class RuntimeConfigurationContractTest {

    /**
     * A placeholder with no fallback: a dollar-brace name with no colon before the closing brace.
     */
    private static final Pattern NO_FALLBACK_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /**
     * The {@code environment_name = "NAME"} assignment each published runtime parameter carries.
     */
    private static final Pattern PUBLISHED_PARAMETER =
            Pattern.compile("environment_name\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"");

    /**
     * One {@code <service> = toset([...])} entry of a required-name inventory.
     */
    private static final Pattern REQUIRED_ENTRY =
            Pattern.compile("([a-z-]+)\\s*=\\s*toset\\(\\[(.*?)]\\)", Pattern.DOTALL);

    /**
     * One of the three required-name inventories in the service module.
     */
    private static final Pattern REQUIRED_INVENTORY = Pattern.compile(
            "required_(?:plain|parameter|secret)_environment_names = \\{(.*?)\\n  }", Pattern.DOTALL);

    /**
     * A quoted upper-case name, which is how every admitted name is written in the module inventories.
     */
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([A-Z][A-Z0-9_]*)\"");

    /**
     * Names a service binds with no fallback that the DEPLOYMENT does not supply, because the container
     * supplies them to itself.
     *
     * <p>Assumptions: exactly one name, and its exemption is evidence-based rather than a convenience.
     * {@code config/docker/generate-listener-material.sh} mints each task's own listener key pair and
     * self-signed certificate at startup and exports this variable from that script before the
     * application is executed, so the value exists in the process environment without ever being a
     * deployment input. Admitting it to the module inventory would be wrong in the other direction: it
     * would invite a root to inject a shared keystore password, which is the state the task-minted design
     * exists to remove.</p>
     */
    private static final Set<String> SUPPLIED_BY_CONTAINER_ENTRYPOINT =
            Set.of("CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD");

    /**
     * The path, relative to the repository root, of the module holding the name inventories.
     */
    private static final String SERVICE_MODULE = "infra/modules/ecs-service/main.tf";

    /**
     * The environment roots that must publish what the module requires.
     */
    private static final List<String> ENVIRONMENT_ROOTS =
            List.of("infra/envs/dev/main.tf", "infra/envs/prod/main.tf");

    /**
     * The one module directory whose workload publishes no HTTP listener.
     *
     * <p>Assumptions: named as a constant rather than inlined because two separate assertions turn on
     * it, and because the exception is a property of the workload -- a one-shot task whose exit status
     * is its result -- rather than a property of any one setting.</p>
     */
    private static final String BATCH_MODULE = "batch-service";

    /**
     * The one required runtime name that is NOT an {@code application.yml} placeholder.
     *
     * <p>Assumptions: the shared kernel publishes the keyset-cursor signer under
     * {@code @ConditionalOnProperty(carddemo.pagination.cursor.signing-key)} and defaults that property
     * nowhere, so the variable never appears as a {@code ${...}} placeholder in any service document and
     * the placeholder sweep below cannot see it. It is still start-time REQUIRED wherever a bean asks for
     * a {@code CursorToken}: an absent property removes the bean rather than degrading paging, and the
     * context then fails to refresh. Because the sweep is blind to it, an image that documented only the
     * placeholders would present a contract an operator could follow and still get a task that will not
     * start -- which is exactly the shape this class exists to refuse.</p>
     */
    private static final String CURSOR_SIGNING_KEY = "CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY";

    /**
     * Recognises a DECLARATION of the keyset-cursor signer rather than a mention of it.
     *
     * <p>Trade-offs: matching the type name followed by an identifier finds constructor parameters, fields
     * and locals, which is every way a bean can be asked for, while rejecting the Javadoc forms
     * {@code &#123;@link CursorToken#MIN_KEY_LENGTH&#125;} and a fully-qualified name in prose. Prose can
     * still defeat it -- "the CursorToken bean" matches this shape -- so the scan additionally skips
     * comment lines, the same discipline {@link #noFallbackNames(String)} applies for the same reason. The
     * residual cost is a conservative over-approximation: a module that named the type in a non-comment
     * position without injecting it would be asked to document one variable it does not need, which is a
     * harmless direction to err in. Parsing the sources instead would need a Java parser this shared
     * kernel has no other use for.</p>
     */
    private static final Pattern CURSOR_SIGNER_DECLARATION =
            Pattern.compile("\\bCursorToken\\s+[a-z][A-Za-z0-9_]*");

    /**
     * The floor on how many service images this class inspects.
     *
     * <p>Assumptions: eight deployable contexts carry a Dockerfile today, so a run that inspected fewer
     * than eight is reading a tree that is not this one -- a moved image path, a filtered walk, or a
     * checkout missing modules -- and a closure check over a short list is worse than no check, because it
     * reports agreement it never tested. The floor is a lower bound rather than an equality so that adding
     * a ninth context is not a failure; removing one is, and should be.</p>
     */
    private static final int DEPLOYABLE_IMAGE_FLOOR = 8;

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * <p>Assumptions: the marker is the service module itself rather than a build file, because that file
     * is what this test needs and a marker that is not the subject can be present while the subject is
     * absent. Maven sets the working directory to the module being built, so the walk is two levels for a
     * reactor build and zero for a build invoked at the root.</p>
     *
     * @return the repository root; never {@code null}
     * @throws AssertionError if no ancestor of the working directory contains the service module, which
     *     fails rather than skips, because a check that silently does nothing is not a check
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(SERVICE_MODULE))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath()
                + " contains " + SERVICE_MODULE + ", so the service configuration contract cannot be"
                + " checked; this test must fail rather than skip, because a contract nobody checks is a"
                + " contract that stops holding");
    }

    /**
     * Reads one repository file as text.
     *
     * @param root the repository root
     * @param relative the path relative to that root
     * @return the file's contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which fails the test
     */
    private static String read(Path root, String relative) {
        try {
            return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "cannot read " + relative + " under " + root, unreadable);
        }
    }

    /**
     * Collects the configuration documents of every service module.
     *
     * @param root the repository root
     * @return a map of service directory name to the concatenated text of its base and production
     *     profiles; never empty, because a build with no service module would be a broken checkout
     * @throws UncheckedIOException if the service tree cannot be walked
     */
    private static Map<String, String> serviceConfigurations(Path root) {
        Map<String, String> byService = new TreeMap<>();
        try (Stream<Path> services = Files.list(root.resolve("services"))) {
            services.filter(Files::isDirectory).forEach(service -> {
                StringBuilder text = new StringBuilder();
                for (String profile : List.of("application.yml", "application-prod.yml")) {
                    Path file = service.resolve("src/main/resources").resolve(profile);
                    if (Files.isRegularFile(file)) {
                        try {
                            text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                        } catch (IOException unreadable) {
                            throw new UncheckedIOException("cannot read " + file, unreadable);
                        }
                    }
                }
                if (text.length() > 0) {
                    byService.put(service.getFileName().toString(), text.toString());
                }
            });
        } catch (IOException unwalkable) {
            throw new UncheckedIOException("cannot list the services tree under " + root, unwalkable);
        }
        assertThat(byService)
                .as("the services tree must hold at least one module with a configuration document,"
                        + " otherwise this check is vacuous")
                .isNotEmpty();
        return byService;
    }

    /**
     * Extracts the no-fallback placeholder names from one configuration document, ignoring comments.
     *
     * <p>Assumptions: comment lines are skipped, because these documents explain their own placeholders at
     * length and a name quoted inside a rationale is not a name the service binds.</p>
     *
     * @param configuration the document text
     * @return the names bound with no fallback, in encounter order; never {@code null}
     */
    private static Set<String> noFallbackNames(String configuration) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : configuration.split("\n", -1)) {
            if (line.trim().startsWith("#")) {
                continue;
            }
            Matcher matcher = NO_FALLBACK_PLACEHOLDER.matcher(line);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Confirms every setting a service cannot start without is a name the task-definition contract admits.
     *
     * <p>Assumptions: admission is tested by the name appearing as a quoted string anywhere in the service
     * module, which is how all three of its inventories are written. Which inventory it belongs to -- plain
     * variable, parameter or secret -- is a decision the module documents per name and enforces in its own
     * precondition; this check asks only the question that failed in practice, which is whether the name
     * was thought about at all.</p>
     */
    @Test
    @DisplayName("every no-fallback setting a service binds is admitted by the task-definition contract")
    void everyRequiredSettingIsAdmitted() {
        Path root = repositoryRoot();
        String module = read(root, SERVICE_MODULE);
        List<String> unadmitted = new ArrayList<>();

        serviceConfigurations(root).forEach((service, configuration) -> {
            for (String name : noFallbackNames(configuration)) {
                if (SUPPLIED_BY_CONTAINER_ENTRYPOINT.contains(name)) {
                    continue;
                }
                if (!module.contains('"' + name + '"')) {
                    unadmitted.add(service + " binds " + name);
                }
            }
        });

        assertThat(unadmitted)
                .as("each of these is a placeholder with no fallback that %s does not admit, so the"
                        + " service cannot refresh its context and no plan reports the omission",
                        SERVICE_MODULE)
                .isEmpty();
    }

    /**
     * Confirms every runtime parameter the environment roots publish is a name the task-definition contract
     * admits, which is the direction that made one task definition unplannable.
     *
     * <p>Assumptions: this is the stricter half of the pair. The module's precondition refuses any supplied
     * name it does not admit, and {@code terraform validate} does not evaluate a lifecycle precondition --
     * so an unadmitted published name is invisible until a plan is run against a real account. Here it is
     * a unit test.</p>
     */
    @Test
    @DisplayName("every runtime parameter an environment root publishes is admitted")
    void everyPublishedParameterIsAdmitted() {
        Path root = repositoryRoot();
        String module = read(root, SERVICE_MODULE);
        Map<String, Set<String>> unadmitted = new TreeMap<>();

        for (String rootFile : ENVIRONMENT_ROOTS) {
            String text = read(root, rootFile);
            Set<String> offending = new TreeSet<>();
            Matcher matcher = PUBLISHED_PARAMETER.matcher(text);
            while (matcher.find()) {
                if (!module.contains('"' + matcher.group(1) + '"')) {
                    offending.add(matcher.group(1));
                }
            }
            if (!offending.isEmpty()) {
                unadmitted.put(rootFile, offending);
            }
        }

        assertThat(unadmitted)
                .as("each of these is published to a task definition that %s refuses to admit, which"
                        + " fails the plan rather than the apply and only against a real account",
                        SERVICE_MODULE)
                .isEmpty();
    }

    /**
     * Confirms every name the task-definition contract REQUIRES of a workload is one both environment roots
     * mention, which is exactly the omission that stopped the pending-authorization context starting.
     *
     * <p>Trade-offs: the assertion is that the name appears in the root at all, not that it appears in the
     * right map for the right workload. Two things make the coarser test the right one here. A name is
     * written in these roots in three different syntactic positions -- a quoted map key, a bare HCL
     * identifier and an {@code environment_name} assignment -- so an exact test would have to model all
     * three and would fail on a fourth. And the exact check already exists: the module's own precondition
     * compares the required set against what a caller passed, per workload, which only Terraform can
     * evaluate. This test catches the case that precondition cannot report early, which is a name absent
     * from the infrastructure entirely.</p>
     */
    @Test
    @DisplayName("every name the contract requires is present in both environment roots")
    void everyRequiredNameIsPresentInBothRoots() {
        Path root = repositoryRoot();
        String module = read(root, SERVICE_MODULE);
        Map<String, Set<String>> required = requiredNamesByWorkload(module);
        assertThat(required)
                .as("the required-name inventories must be readable from %s, otherwise this check is"
                        + " vacuous", SERVICE_MODULE)
                .isNotEmpty();

        List<String> absent = new ArrayList<>();
        for (String rootFile : ENVIRONMENT_ROOTS) {
            String text = read(root, rootFile);
            required.forEach((workload, names) -> names.stream()
                    .filter(name -> !text.contains(name))
                    .forEach(name -> absent.add(
                            rootFile + " never mentions " + name + ", required of " + workload)));
        }

        assertThat(absent)
                .as("each of these is a setting the task-definition contract requires of a workload and"
                        + " that no environment root supplies, so the workload cannot start on a"
                        + " provisioned stack")
                .isEmpty();
    }

    /**
     * Confirms every workload declares the fleet's shutdown budget, and that only the web ones enable
     * graceful shutdown.
     *
     * <p><b>Purpose.</b> Two settings have to agree across the whole fleet for a rolling deployment to
     * drain rather than sever: {@code spring.lifecycle.timeout-per-shutdown-phase}, which bounds the
     * drain, and {@code server.shutdown}, which enables it. Neither is checkable from one module,
     * because what makes a value right is that every sibling carries the same one.</p>
     *
     * <p>Assumptions: the budget is twenty seconds for every workload, and it is a real number rather
     * than a preference. Spring Boot's own default for the key is thirty seconds and
     * {@code infra/modules/ecs-service} pins the container's {@code stopTimeout} to thirty as well, so
     * leaving the key unset makes the drain deadline and the SIGKILL deadline the same instant -- a task
     * can then be killed while it is still draining, which is the exact failure graceful shutdown
     * exists to prevent. Twenty is also below the load balancer's thirty-second deregistration delay,
     * so a draining task is already out of rotation before the timer starts. Two services carried
     * neither key while six carried both, which is what this test now makes impossible to repeat.</p>
     *
     * <p>Assumptions: {@code batch-service} is asserted to declare NO {@code server.shutdown}, rather
     * than being skipped. It publishes no listener -- it is a one-shot task whose exit status is its
     * result -- so the key would be inert there, and an inert setting invites a reader to conclude the
     * batch task drains HTTP traffic it never serves. It still carries the lifecycle budget, because
     * that key bounds every {@code SmartLifecycle} phase and not only the web server.</p>
     *
     * <p>Trade-offs: the assertion is a text search over the concatenated base and production profiles,
     * matching this class's established style, rather than a YAML parse. A parse would be exact and
     * would need a dependency this shared kernel has no other use for; the cost accepted is that a key
     * commented out in a way that still contains the literal would pass, which the surrounding
     * comment-stripping in {@link #noFallbackNames(String)} shows is a known and bounded limitation.</p>
     */
    @Test
    @DisplayName("every workload carries the fleet's twenty-second shutdown budget, and only web ones drain")
    void everyWorkloadCarriesTheFleetShutdownBudget() {
        Map<String, String> configurations = serviceConfigurations(repositoryRoot());
        List<String> wrong = new ArrayList<>();

        configurations.forEach((service, configuration) -> {
            if (!configuration.contains("timeout-per-shutdown-phase: 20s")) {
                wrong.add(service + " sets no twenty-second lifecycle shutdown budget");
            }
            boolean drains = configuration.contains("shutdown: graceful");
            if (BATCH_MODULE.equals(service) && drains) {
                wrong.add(service + " enables graceful shutdown but publishes no listener");
            } else if (!BATCH_MODULE.equals(service) && !drains) {
                wrong.add(service + " serves HTTP but does not enable graceful shutdown");
            }
        });

        assertThat(wrong)
                .as("each of these is a workload whose drain behaviour differs from the rest of the"
                        + " fleet, which a rolling deployment surfaces as severed in-flight requests"
                        + " rather than as a configuration difference")
                .isEmpty();
    }

    /**
     * Confirms every runtime name a context cannot start without is documented by that context's image.
     *
     * <p><b>Purpose.</b> A service Dockerfile's header is the only place an operator reads what a task
     * definition has to carry: the image declares no {@code ENV} and no {@code ARG}, deliberately, so
     * nothing else in it names a single variable. That makes the header a CONTRACT, and a contract that
     * presents itself as exhaustive while omitting a no-fallback name is worse than one that says nothing
     * -- an operator follows it, provisions a task that passes review, and the task then fails context
     * refresh on a variable no document mentioned. That had happened in five of the eight images at once:
     * the migration credential pair was missing from three headers, two cross-context addresses and two
     * signing keys from others, one header named its variables only by category ("the datasource triple"),
     * and one named none at all.</p>
     *
     * <p>Assumptions: the placeholder set is re-derived from {@code application.yml} and
     * {@code application-prod.yml} by the same {@link #noFallbackNames(String)} used by the inventory
     * checks above, so this case and those cannot disagree about what "required" means. Documentation is
     * tested by the NAME appearing literally in the Dockerfile, which is the weakest claim that is still
     * useful: it proves the variable was thought about and can be found by an operator grepping for it,
     * and it deliberately does not judge the prose beside it, because whether a rationale is truthful is a
     * Rule 1 review question and not a string comparison. {@link #SUPPLIED_BY_CONTAINER_ENTRYPOINT} is NOT
     * exempted here, unlike in the infrastructure-inventory cases: the entry point minting its own
     * keystore password is precisely the fact a reader of the image needs, and every header now states it
     * as an explicit exclusion rather than by leaving the name out.</p>
     *
     * <p>Trade-offs: a literal text search will accept a name that appears anywhere in the file, including
     * inside an unrelated sentence, so the check bounds a header's omissions rather than certifying its
     * structure. Parsing the header into sections was rejected: the eight headers legitimately organise
     * their variable lists differently -- one splits required from defaulted, one splits build from
     * command from runtime -- and a parser strict enough to be meaningful would force a single layout on
     * prose whose job is to be read.</p>
     */
    @Test
    @DisplayName("every runtime name a context cannot start without is named by that context's image")
    void everyNoFallbackSettingIsDocumentedByItsImage() {
        Path root = repositoryRoot();
        Map<String, String> configurations = serviceConfigurations(root);
        Map<String, String> images = serviceImages(root);
        List<String> undocumented = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, String> image : images.entrySet()) {
            String module = image.getKey();
            String dockerfile = image.getValue();
            String configuration = configurations.get(module);
            assertThat(configuration)
                    .as("%s ships an image but no configuration document, so there is nothing to close"
                            + " its runtime contract against", module)
                    .isNotNull();

            for (String name : noFallbackNames(configuration)) {
                checked++;
                if (!dockerfile.contains(name)) {
                    undocumented.add(module + " binds " + name + " with no fallback, and its Dockerfile"
                            + " never names it");
                }
            }
            if (declaresCursorSigner(root, module)) {
                checked++;
                if (!dockerfile.contains(CURSOR_SIGNING_KEY)) {
                    undocumented.add(module + " asks for a CursorToken bean, so it requires "
                            + CURSOR_SIGNING_KEY + " to start, and its Dockerfile never names it");
                }
            }
        }

        assertThat(images)
                .as("fewer than %d service images were found, so this closure check is reading a tree that"
                        + " is not this repository and its agreement means nothing",
                        DEPLOYABLE_IMAGE_FLOOR)
                .hasSizeGreaterThanOrEqualTo(DEPLOYABLE_IMAGE_FLOOR);
        assertThat(checked)
                .as("no runtime name was checked at all, which a passing assertion below would misreport"
                        + " as eight closed contracts")
                .isGreaterThan(DEPLOYABLE_IMAGE_FLOOR);
        assertThat(undocumented)
                .as("each of these is a variable a task definition must carry that the image an operator"
                        + " reads does not mention, which surfaces as a context that will not stay up"
                        + " rather than as a setting nobody wired")
                .isEmpty();
    }

    /**
     * Collects the Dockerfile text of every service module that ships one.
     *
     * <p>Assumptions: presence of a Dockerfile is what makes a module a deployable context, so the walk
     * keys on that file rather than on a hand-kept module list. {@code common-lib} therefore drops out on
     * its own -- it is a library and ships no image -- without needing to be named as an exception.</p>
     *
     * @param root the repository root
     * @return a map of service directory name to its Dockerfile contents; never {@code null}
     * @throws UncheckedIOException if the service tree cannot be walked
     */
    private static Map<String, String> serviceImages(Path root) {
        Map<String, String> byService = new TreeMap<>();
        try (Stream<Path> services = Files.list(root.resolve("services"))) {
            services.filter(Files::isDirectory).forEach(service -> {
                Path dockerfile = service.resolve("Dockerfile");
                if (Files.isRegularFile(dockerfile)) {
                    try {
                        byService.put(service.getFileName().toString(),
                                Files.readString(dockerfile, StandardCharsets.UTF_8));
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException("cannot read " + dockerfile, unreadable);
                    }
                }
            });
        } catch (IOException unwalkable) {
            throw new UncheckedIOException("cannot list the services tree under " + root, unwalkable);
        }
        return byService;
    }

    /**
     * Reports whether one module's production sources ask for a keyset-cursor signer.
     *
     * <p>Assumptions: the answer is DERIVED from the sources rather than listed here, because a hand-kept
     * list of paging contexts is exactly the kind of census that goes stale the first time a context gains
     * or loses a list endpoint. Comment lines are skipped so that a charter discussing the type -- the
     * batch context's repository charter does precisely that, to explain why it has none -- is not read as
     * a requirement.</p>
     *
     * @param root the repository root
     * @param module the service directory name
     * @return {@code true} if any non-comment line of the module's main sources declares a
     *     {@code CursorToken}
     * @throws UncheckedIOException if the module's source tree cannot be walked or read
     */
    private static boolean declaresCursorSigner(Path root, String module) {
        Path sources = root.resolve("services").resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .anyMatch(RuntimeConfigurationContractTest::declaresCursorSignerIn);
        } catch (IOException unwalkable) {
            throw new UncheckedIOException("cannot walk the sources under " + sources, unwalkable);
        }
    }

    /**
     * Reports whether one source file declares a keyset-cursor signer outside its comments.
     *
     * @param file the source file
     * @return {@code true} if a non-comment line declares a {@code CursorToken}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static boolean declaresCursorSignerIn(Path file) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            if (CURSOR_SIGNER_DECLARATION.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the three required-name inventories out of the service module.
     *
     * @param module the module's text
     * @return a map of workload name to the union of names required of it across the three inventories;
     *     never {@code null}
     */
    private static Map<String, Set<String>> requiredNamesByWorkload(String module) {
        Map<String, Set<String>> required = new TreeMap<>();
        Matcher inventory = REQUIRED_INVENTORY.matcher(module);
        while (inventory.find()) {
            Matcher entry = REQUIRED_ENTRY.matcher(inventory.group(1));
            while (entry.find()) {
                String workload = entry.group(1).toLowerCase(Locale.ROOT);
                Matcher name = QUOTED_NAME.matcher(entry.group(2));
                while (name.find()) {
                    required.computeIfAbsent(workload, key -> new TreeSet<>()).add(name.group(1));
                }
            }
        }
        return required;
    }
}
