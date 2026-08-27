package com.carddemo.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Holds the startup environment check to the four verdicts it exists to give.
 *
 * <p><b>Purpose.</b> The defect this check answers is that Spring Boot binds an unresolvable
 * placeholder as literal text, so a deployment missing {@code SPRING_DATASOURCE_URL} used to die
 * several layers downstream with {@code 'url' must start with "jdbc"} and no variable name anywhere in
 * the message. The cases here assert the two directions that matter -- every absent variable is named
 * with the property that reads it, and nothing else is reported -- plus the two framework properties
 * a direct call cannot see: that the registration resource is on the classpath under the key Spring
 * Boot reads, and that a real start fails with the message on the console.</p>
 *
 * <p>Assumptions: environments are built by hand from {@link MapPropertySource} instances rather than
 * loaded from YAML. What the detector reads is raw, unresolved property text in precedence order, and
 * a map states that directly; loading a file would add a parser and a profile resolution to every case
 * without changing the input under test.</p>
 *
 * <p>Trade-offs: each case that expects a refusal sets the enforcement property explicitly, because
 * the check is deliberately inert under a test harness. The repetition is accepted -- it is one entry
 * per case -- and the guard's own two verdicts are asserted by their own case rather than assumed.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The startup environment check names every absent variable and nothing else")
final class RequiredEnvironmentVariablePostProcessorTest {

    /** Name of the property source standing in for a service's own {@code application.yml}. */
    private static final String CONFIGURATION_SOURCE = "carddemo-test-configuration";

    /** The variable no environment supplies, used wherever a case needs a genuine absence. */
    private static final String ABSENT_VARIABLE = "CARDDEMO_TEST_ABSENT_DATASOURCE_URL";

    /**
     * Builds an environment whose lowest-precedence source is the given configuration.
     *
     * @param properties the raw, unresolved property text a service's configuration would declare
     * @return an environment carrying that configuration beneath the platform sources; never
     *     {@code null}
     */
    private static StandardEnvironment environmentDeclaring(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addLast(new MapPropertySource(CONFIGURATION_SOURCE, properties));
        return environment;
    }

    /**
     * Turns enforcement on for one environment, as a test harness must.
     *
     * @param environment the environment to opt in
     * @return the same environment, so a case can build and opt in on one line
     */
    private static StandardEnvironment enforcing(StandardEnvironment environment) {
        environment.getPropertySources().addFirst(new MapPropertySource("carddemo-test-enforcement",
                Map.of(RequiredEnvironmentVariablePostProcessor.ENFORCEMENT_PROPERTY, "true")));
        return environment;
    }

    /**
     * Confirms a configuration whose placeholders all resolve reports nothing.
     *
     * <p>Assumptions: this is the case that decides whether the check is deployable at all. A
     * detector that reported a well-configured service would be removed within a day of reaching an
     * environment, so the inert verdict is asserted first and from a configuration that carries
     * placeholders rather than from an empty one.</p>
     */
    @Test
    @DisplayName("a configuration whose placeholders all resolve is reported as complete")
    void nothingIsReportedWhenEveryPlaceholderResolves() {
        StandardEnvironment environment = environmentDeclaring(Map.of(
                "spring.datasource.url", "${CARDDEMO_TEST_SUPPLIED_URL}",
                "CARDDEMO_TEST_SUPPLIED_URL", "jdbc:postgresql://localhost:5432/carddemo",
                "carddemo.environment", "${CARDDEMO_ENVIRONMENT:unspecified}"));

        assertThat(RequiredEnvironmentVariablePostProcessor
                .missingEnvironmentVariables(environment))
                .as("a configuration whose every placeholder resolves must report nothing")
                .isEmpty();
        assertThatCode(() -> new RequiredEnvironmentVariablePostProcessor()
                .postProcessEnvironment(enforcing(environment), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    /**
     * Confirms one absent variable is named together with the property that reads it.
     */
    @Test
    @DisplayName("one absent variable is named with the property it feeds")
    void oneAbsentVariableIsNamedWithItsProperty() {
        StandardEnvironment environment = enforcing(environmentDeclaring(
                Map.of("spring.datasource.url", "${" + ABSENT_VARIABLE + "}")));

        assertThatThrownBy(() -> new RequiredEnvironmentVariablePostProcessor()
                .postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .hasMessageContaining(ABSENT_VARIABLE + " (spring.datasource.url)")
                .hasMessageContaining("CardDemo cannot start");
    }

    /**
     * Confirms several absent variables arrive in one message, each with its property.
     *
     * <p>Assumptions: the whole set is asserted rather than the first entry, because the failure this
     * replaces stopped at the first defect it met. An operator restarting once per missing variable is
     * the cost of a check that reports one at a time, and this is the case that prevents it.</p>
     */
    @Test
    @DisplayName("several absent variables are named in one message, in a stable order")
    void severalAbsentVariablesAreNamedInOneMessage() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("spring.datasource.url", "${CARDDEMO_TEST_ABSENT_URL}");
        configuration.put("spring.datasource.password", "${CARDDEMO_TEST_ABSENT_PASSWORD}");
        configuration.put("spring.flyway.user", "${CARDDEMO_TEST_ABSENT_MIGRATOR}");

        Map<String, String> missing = RequiredEnvironmentVariablePostProcessor
                .missingEnvironmentVariables(environmentDeclaring(configuration));

        assertThat(missing)
                .as("every absent variable must be reported, each against the property reading it")
                .containsExactly(
                        Map.entry("CARDDEMO_TEST_ABSENT_MIGRATOR", "spring.flyway.user"),
                        Map.entry("CARDDEMO_TEST_ABSENT_PASSWORD", "spring.datasource.password"),
                        Map.entry("CARDDEMO_TEST_ABSENT_URL", "spring.datasource.url"));
        assertThat(new MissingEnvironmentVariablesException(missing).getMessage())
                .as("the message must carry the count and every pair")
                .contains("3 environment variables",
                        "CARDDEMO_TEST_ABSENT_MIGRATOR (spring.flyway.user)",
                        "CARDDEMO_TEST_ABSENT_PASSWORD (spring.datasource.password)",
                        "CARDDEMO_TEST_ABSENT_URL (spring.datasource.url)");
    }

    /**
     * Confirms a lower-case dotted reference to another property is never reported.
     *
     * <p>Assumptions: this shape is not hypothetical. reference-service declares
     * {@code sslrootcert: ${carddemo.database.ssl.root-cert}} so that its connection pool and its
     * separate migration data source read one declaration instead of two literals, and that
     * declaration carries the environment default. Reporting it would name a property no deployment is
     * expected to set, which is precisely the false alarm that gets a check disabled.</p>
     */
    @Test
    @DisplayName("a lower-case dotted property reference is not an environment variable")
    void aDottedPropertyReferenceIsNotReported() {
        StandardEnvironment environment = environmentDeclaring(Map.of(
                "spring.datasource.hikari.data-source-properties.sslrootcert",
                "${carddemo.database.ssl.root-cert}",
                "carddemo.database.ssl.root-cert",
                "${CARDDEMO_DB_SSL_ROOT_CERT:/etc/ssl/certs/carddemo-rds-ca-bundle.pem}"));

        assertThat(RequiredEnvironmentVariablePostProcessor
                .missingEnvironmentVariables(environment))
                .as("neither the indirection nor the defaulted variable behind it is a finding")
                .isEmpty();
    }

    /**
     * Confirms a placeholder carrying a default is not reported.
     */
    @Test
    @DisplayName("a placeholder carrying a default is not reported")
    void aDefaultedPlaceholderIsNotReported() {
        StandardEnvironment environment = environmentDeclaring(Map.of(
                "carddemo.environment", "${CARDDEMO_TEST_UNSET_WITH_DEFAULT:unspecified}",
                "carddemo.version", "${CARDDEMO_TEST_ALSO_UNSET:0.0.0}"));

        assertThat(RequiredEnvironmentVariablePostProcessor
                .missingEnvironmentVariables(environment))
                .as("a variable the configuration itself defaults is supplied, not missing")
                .isEmpty();
    }

    /**
     * Confirms a placeholder in an overridden property is not reported.
     *
     * <p>Assumptions: precedence is the property under test, not deduplication. The same key is
     * routinely declared by a base document and again by a profile document or an environment
     * variable, and only the winning value is ever bound -- so a placeholder sitting in a value that
     * loses is a placeholder the process never resolves.</p>
     */
    @Test
    @DisplayName("a placeholder in an overridden property is not reported")
    void anOverriddenPlaceholderIsNotReported() {
        StandardEnvironment environment = environmentDeclaring(
                Map.of("spring.datasource.url", "${" + ABSENT_VARIABLE + "}"));
        environment.getPropertySources().addFirst(new MapPropertySource("carddemo-test-override",
                Map.of("spring.datasource.url", "jdbc:postgresql://localhost:5432/carddemo")));

        assertThat(RequiredEnvironmentVariablePostProcessor
                .missingEnvironmentVariables(environment))
                .as("the winning value resolves, so the losing declaration is not a finding")
                .isEmpty();
    }

    /**
     * Confirms placeholder-shaped text inside a supplied value is not read as a placeholder.
     *
     * <p>Assumptions: the system-environment and system-property sources carry values a deployment
     * chose, not templates this project wrote. A password that happens to contain the characters
     * {@code ${...}} is a password, and scanning those sources would report it as a missing
     * variable -- while printing part of it in the message.</p>
     */
    @Test
    @DisplayName("placeholder-shaped text inside a supplied value is not scanned")
    void placeholderShapedTextInASuppliedValueIsNotScanned() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("CARDDEMO_TEST_LITERAL", "${" + ABSENT_VARIABLE + "}")));

        assertThat(RequiredEnvironmentVariablePostProcessor
                .missingEnvironmentVariables(environment))
                .as("a value-bearing source holds values, so its text is not a template")
                .isEmpty();
    }

    /**
     * Confirms the guard's two verdicts: inert under a harness, enforced when opted in.
     *
     * <p>Assumptions: this test JVM has {@code spring-test} on its classpath, which is exactly the
     * condition the guard reads, so the inert verdict can be asserted here without simulation. No
     * packaged service jar carries that library, so the enforced verdict is what a deployment
     * gets.</p>
     */
    @Test
    @DisplayName("the check is inert under a test harness unless a case opts in")
    void theCheckIsInertUnderATestHarnessUnlessOptedIn() {
        StandardEnvironment environment = environmentDeclaring(
                Map.of("spring.datasource.url", "${" + ABSENT_VARIABLE + "}"));

        assertThat(RequiredEnvironmentVariablePostProcessor.isEnforced(environment))
                .as("a harness on the classpath and no opt-in property means no enforcement")
                .isFalse();
        assertThatCode(() -> new RequiredEnvironmentVariablePostProcessor()
                .postProcessEnvironment(environment, new SpringApplication()))
                .as("an absent variable under a harness must not fail the context")
                .doesNotThrowAnyException();

        assertThat(RequiredEnvironmentVariablePostProcessor.isEnforced(enforcing(environment)))
                .as("the opt-in property restores enforcement for a case that wants it")
                .isTrue();
    }

    /**
     * Confirms the check runs after configuration data has been loaded.
     *
     * <p>Assumptions: the order is asserted against the config-data post-processor's own constant
     * rather than against a copied number, because the property that matters is the relation between
     * the two and not either value. Running before config data would mean scanning an environment that
     * had not yet read {@code application.yml}, in which every placeholder is absent and none of them
     * is a finding.</p>
     */
    @Test
    @DisplayName("the check is ordered after the post-processor that loads configuration data")
    void theCheckRunsAfterConfigurationDataIsLoaded() {
        int order = new RequiredEnvironmentVariablePostProcessor().getOrder();

        assertThat(order).as("the check must run last among environment post-processors")
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(order).as("and therefore after configuration data is loaded")
                .isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    /**
     * Confirms the registration resource names this check under the key Spring Boot reads.
     *
     * <p>Assumptions: every {@code META-INF/spring.factories} on the classpath is read and the entries
     * are collected across all of them, because that is how {@code SpringFactoriesLoader} itself
     * resolves a key -- a registration is not required to be in any particular jar. The check is on the
     * CURRENT interface name; Spring Boot 4.1 also honours the deprecated {@code
     * org.springframework.boot.env} spelling, and a registration written against it would be tied to a
     * removal already announced.</p>
     *
     * @throws UncheckedIOException if a factories resource on the classpath cannot be read, which is a
     *     broken build rather than a missing registration and fails loudly rather than quietly
     *     reporting an absence
     */
    @Test
    @DisplayName("the registration resource names this check under the current post-processor key")
    void theRegistrationResourceNamesThisCheck() {
        List<String> registered = new ArrayList<>();
        try {
            Enumeration<URL> resources = getClass().getClassLoader()
                    .getResources("META-INF/spring.factories");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                Properties entries = new Properties();
                try (InputStream stream = resource.openStream()) {
                    entries.load(stream);
                }
                String declared = entries.getProperty(EnvironmentPostProcessor.class.getName());
                if (declared != null) {
                    for (String candidate : declared.split(",")) {
                        registered.add(candidate.trim());
                    }
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read META-INF/spring.factories", unreadable);
        }

        assertThat(registered)
                .as("classes registered under %s across every factories resource on the classpath",
                        EnvironmentPostProcessor.class.getName())
                .contains(RequiredEnvironmentVariablePostProcessor.class.getName());
    }

    /**
     * Confirms a real start fails with the variable named, and that the message reaches the console.
     *
     * <p>Assumptions: this case runs the framework rather than the method, because three of the
     * properties a deployment depends on are the framework's: that the factories resource is
     * discovered, that the ordering puts this check after configuration data, and that the failure is
     * reported rather than swallowed. It is also the only case that can answer what an operator
     * actually SEES, which is why the captured output is asserted and not only the thrown type.</p>
     *
     * @param output the console this start writes to, captured by the extension on this class
     */
    @Test
    @DisplayName("a real start is refused with the variable named on the console")
    void aRealStartIsRefusedWithTheVariableNamed(CapturedOutput output) {
        SpringApplication application = new SpringApplication(MinimalApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                RequiredEnvironmentVariablePostProcessor.ENFORCEMENT_PROPERTY, "true",
                "spring.datasource.url", "${" + ABSENT_VARIABLE + "}"));

        assertThatThrownBy(application::run)
                .as("the start must fail with the shared refusal rather than downstream")
                .isInstanceOf(MissingEnvironmentVariablesException.class)
                .hasMessageContaining(ABSENT_VARIABLE + " (spring.datasource.url)");
        assertThat(output.getAll())
                .as("what an operator reads on the console when a variable is absent")
                .contains(ABSENT_VARIABLE);
    }

    /**
     * The smallest application this module can start, used only by the real-start case.
     *
     * <p>Assumptions: it declares nothing. The refusal under test happens while the environment is
     * being prepared, which is before any bean definition is read, so a source carrying components
     * would add startup work to a case that never reaches it.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class MinimalApplication {
    }
}
