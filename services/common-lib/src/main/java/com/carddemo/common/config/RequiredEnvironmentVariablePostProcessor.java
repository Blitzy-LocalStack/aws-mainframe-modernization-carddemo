package com.carddemo.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Stops a service from starting when the active configuration reads environment variables the
 * platform did not supply, naming every one of them and the property it feeds.
 *
 * <p><b>Purpose.</b> Every service in this reactor declares its deployment inputs as bare
 * environment placeholders -- {@code url: ${SPRING_DATASOURCE_URL}} and twenty-seven others like it
 * across the eight modules. Spring Boot's relaxed binder resolves such a value through
 * {@code PropertySourcesPlaceholdersResolver}, which IGNORES an unresolvable placeholder and binds
 * the literal text instead of failing. A deployment missing one variable therefore starts, gets as
 * far as the driver-class lookup, and dies with {@code 'url' must start with "jdbc"} -- a message
 * that names neither the variable nor the property. This post-processor moves that failure to the
 * earliest point at which it can be diagnosed and gives it the one fact the deployment needs.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> Declared on each member below. The type
 * itself is instantiated by Spring Boot through {@code META-INF/spring.factories} and takes no
 * construction argument.</p>
 *
 * <p>Assumptions: a placeholder is treated as a deployment input only when its name is UPPER SNAKE
 * CASE and it carries no {@code :} default -- {@code ${SPRING_DATASOURCE_URL}} qualifies,
 * {@code ${CARDDEMO_ENVIRONMENT:unspecified}} does not, and neither does a lower-case dotted
 * reference such as {@code ${carddemo.database.ssl.root-cert}}. The last exclusion is load-bearing
 * rather than cosmetic: reference-service single-sources its trust anchor through exactly that shape
 * so its Hikari map and its separate Flyway data source cannot drift onto two literals, and a
 * detector that read it as a variable would report a property the deployment is not expected to
 * set.</p>
 *
 * <p>Alternatives Considered: declaring the same keys per service, either as
 * {@code @ConfigurationProperties} with {@code @NotBlank} or through
 * {@code ConfigurableEnvironment.setRequiredProperties}. Rejected on two counts. The first is that
 * both check a PROPERTY rather than a variable, and the property is present -- it holds the
 * unresolved placeholder text -- so neither sees the defect at all. The second is arithmetic: eight
 * services declare between six and sixteen such inputs each, and a per-service list is one more
 * document to keep in step with the YAML beside it, which is the drift this project has already paid
 * for elsewhere. Reading the placeholders out of the configuration that declares them needs no list.
 * </p>
 *
 * <p>Trade-offs: the check runs over the environment's own property sources and therefore sees only
 * what the active profiles loaded. A variable read exclusively by a document guarded by
 * {@code spring.config.activate.on-profile: prod} is not reported when the process starts under
 * {@code dev}, which is correct for that process and means this is not a static audit of the whole
 * file. That is accepted: the contract being enforced is "this process can resolve what this process
 * reads", and a check that failed a development start over a production-only variable would be
 * routinely disabled.</p>
 */
public final class RequiredEnvironmentVariablePostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /**
     * Property by which a test harness opts into enforcement.
     *
     * <p>Assumptions: consulted ONLY when a Spring test harness is on the classpath, so a deployed
     * process cannot switch the contract off by setting it. That asymmetry is the point -- the
     * property exists to let a test exercise the failure, not to let a deployment excuse it.</p>
     */
    static final String ENFORCEMENT_PROPERTY = "carddemo.startup.required-environment.enforce";

    /**
     * Matches one environment-variable-shaped placeholder carrying no default.
     *
     * <p>Assumptions: the name class admits only upper-case letters, digits and the underscore, and
     * the closing brace must follow immediately. A placeholder with a {@code :} default therefore
     * fails to match at all rather than being matched and then filtered, and so does a lower-case
     * dotted property reference -- which is what keeps a single-sourced property out of the
     * report.</p>
     */
    private static final Pattern ENVIRONMENT_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /**
     * The Spring test-context entry point, whose presence identifies a test harness.
     *
     * <p>Assumptions: this class is absent from every packaged service jar -- verified by listing
     * the built artefacts, which carry neither {@code spring-test} nor JUnit -- so its presence is a
     * sound signal that the process is a test rather than a deployment.</p>
     */
    private static final String TEST_CONTEXT_MANAGER =
            "org.springframework.test.context.TestContextManager";

    /**
     * The two property sources that carry supplied values rather than configuration templates.
     *
     * <p>Assumptions: these are skipped as SUBJECTS of the scan and still consulted for precedence.
     * An environment variable's own value is the thing a placeholder resolves TO, so a
     * {@code ${...}} sequence inside one is a value a deployment chose to contain those characters
     * and not a placeholder this project wrote.</p>
     */
    private static final Set<String> VALUE_BEARING_SOURCES = Set.of(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

    /**
     * Fails the start when the active configuration reads a variable the environment cannot supply.
     *
     * @param environment the environment Spring Boot has finished loading configuration data into;
     *     read but never modified, because this contributes no property and only inspects the ones
     *     already present
     * @param application the application being started, which this implementation does not consult:
     *     the contract is a property of the configuration rather than of the application's own
     *     settings, and reading its sources would make the verdict depend on construction order
     * @throws MissingEnvironmentVariablesException if one or more environment-variable-shaped
     *     placeholders with no default cannot be resolved, naming every one of them
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        if (!isEnforced(environment)) {
            return;
        }
        Map<String, String> missing = missingEnvironmentVariables(environment);
        if (!missing.isEmpty()) {
            throw new MissingEnvironmentVariablesException(missing);
        }
    }

    /**
     * Places this post-processor after every other one, so it reads the finished environment.
     *
     * <p>Assumptions: Spring Boot sorts environment post-processors with
     * {@code AnnotationAwareOrderComparator} before invoking them, and
     * {@code ConfigDataEnvironmentPostProcessor} -- the one that loads {@code application.yml}, its
     * profile documents and every {@code spring.config.import} -- declares
     * {@code HIGHEST_PRECEDENCE + 10}. Running last is what makes the configuration this check reads
     * the configuration the application will actually bind; running earlier would report every
     * placeholder in a file that had not been loaded yet.</p>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}, so this runs after config data and after any
     *     library post-processor that contributes a property source
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Decides whether the contract is enforced in this process.
     *
     * <p>Refactoring Rationale: an unconditional check was written first and withdrawn, because it
     * failed roughly sixty integration tests that are correctly configured. A Testcontainers test
     * supplies its database through a {@code JdbcConnectionDetails} BEAN contributed by
     * {@code @ServiceConnection}, never as a property, so the unresolved
     * {@code ${SPRING_DATASOURCE_URL}} text is still present in those contexts and is still the
     * right thing for them to carry. Recognising the harness rather than the profile name is what
     * makes the exemption exact: several of those contexts activate no profile at all, so a
     * profile-based exemption would have covered some of them and not others.</p>
     *
     * <p>Trade-offs: the signal is a class on the classpath, which is coarser than asking whether
     * THIS process is running a test. The cost is that a deployment that shipped
     * {@code spring-test} would lose the check; that is accepted because no service jar packages it
     * and because the alternative -- inspecting the stack for a test runner -- is both slower and
     * defeated by any harness that does not appear where it is looked for.</p>
     *
     * @param environment the environment to read the opt-in property from
     * @return {@code true} when the check should run, which is always the case outside a test
     *     harness
     */
    static boolean isEnforced(ConfigurableEnvironment environment) {
        boolean harnessPresent = ClassUtils.isPresent(TEST_CONTEXT_MANAGER,
                RequiredEnvironmentVariablePostProcessor.class.getClassLoader());
        if (!harnessPresent) {
            return true;
        }
        return Boolean.TRUE.equals(
                environment.getProperty(ENFORCEMENT_PROPERTY, Boolean.class, Boolean.FALSE));
    }

    /**
     * Collects every environment variable the active configuration reads and cannot resolve.
     *
     * <p>Assumptions: a property is inspected only where it WINS. The same name is routinely
     * declared by several sources -- a profile document over a base document, an environment
     * variable over both -- and only the winning value is ever bound, so reporting a placeholder
     * from a source that has been overridden would name a variable the process never resolves.</p>
     *
     * <p>Assumptions: a source that cannot enumerate its names is consulted for neither purpose.
     * The only such source in a Spring Boot environment is the aggregate
     * {@code configurationProperties} source that sits first and delegates to all the others, so
     * consulting it for precedence would report every property as overridden and leave this method
     * inspecting nothing at all.</p>
     *
     * @param environment the environment whose sources are read
     * @return each unresolved variable name mapped to the comma-separated properties that read it,
     *     ordered by variable name so the message is stable across runs; never {@code null}, and
     *     empty when the contract holds
     */
    static Map<String, String> missingEnvironmentVariables(ConfigurableEnvironment environment) {
        List<PropertySource<?>> sources = new ArrayList<>();
        environment.getPropertySources().forEach(sources::add);

        Map<String, Set<String>> readers = new TreeMap<>();
        for (int index = 0; index < sources.size(); index++) {
            PropertySource<?> source = sources.get(index);
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || VALUE_BEARING_SOURCES.contains(source.getName())) {
                continue;
            }
            for (String property : enumerable.getPropertyNames()) {
                if (isOverridden(sources, index, property)) {
                    continue;
                }
                recordUnresolved(environment, readers, property, source.getProperty(property));
            }
        }

        Map<String, String> missing = new LinkedHashMap<>();
        readers.forEach((variable, properties) -> missing.put(variable,
                String.join(", ", properties)));
        return missing;
    }

    /**
     * Records each unresolved variable one property's raw value reads.
     *
     * @param environment the environment used to ask whether a variable can be resolved at all
     * @param readers the collector, keyed by variable name, that the finding is added to
     * @param property the property whose raw value is being inspected, named in the report so an
     *     operator learns what the variable feeds as well as that it is absent
     * @param value the property's raw, unresolved value; a non-{@code String} carries no placeholder
     *     text and is ignored rather than rendered
     */
    private static void recordUnresolved(ConfigurableEnvironment environment,
            Map<String, Set<String>> readers, String property, Object value) {
        if (!(value instanceof String text)) {
            return;
        }
        Matcher placeholder = ENVIRONMENT_PLACEHOLDER.matcher(text);
        while (placeholder.find()) {
            String variable = placeholder.group(1);
            // WHY : Assumptions: resolvability is asked of the ENVIRONMENT rather than of the
            //   system-environment source alone, so a variable supplied as a JVM system property or
            //   declared literally by any configuration source counts as supplied. The relaxed
            //   lookup that maps an upper-snake name onto a dotted property lives in that source's
            //   own getProperty, which is why the question is asked through the environment and not
            //   by reading a map of variables.
            if (environment.getProperty(variable) != null) {
                continue;
            }
            readers.computeIfAbsent(variable, name -> new TreeSet<>()).add(property);
        }
    }

    /**
     * Reports whether a higher-precedence source already declares one property name.
     *
     * @param sources every property source, in precedence order, highest first
     * @param index the position of the source being inspected
     * @param property the property name to look for above that position
     * @return {@code true} when a source of higher precedence declares the name, in which case the
     *     value at {@code index} is never the one the application binds
     */
    private static boolean isOverridden(List<PropertySource<?>> sources, int index,
            String property) {
        for (int higher = 0; higher < index; higher++) {
            PropertySource<?> candidate = sources.get(higher);
            if (candidate instanceof EnumerablePropertySource<?>
                    && candidate.containsProperty(property)) {
                return true;
            }
        }
        return false;
    }
}
