package com.carddemo.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.carddemo.common.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Asserts that the shared kernel's default configuration turns every service's application log into
 * one structured JSON object per event, carrying the three common tags and the correlation identity.
 *
 * <p>Purpose: verify the structured-logging half of {@code carddemo-common-defaults.yml} the same way
 * {@link MetricsConfigTest} verifies the metric half -- by resolving the shipped file rather than by
 * restating its values, and then by driving the encoder that file selects and reading the bytes it
 * produces.
 *
 * <p>Refactoring Rationale: this class exists because the control it covers was previously specified
 * and unimplemented, and the gap was invisible from any single artifact. The target format in
 * {@code docs/architecture/observability.md} is one JSON object per event with the three common tags
 * and the correlation identifier, while no module shipped a {@code logback-spring.xml} and no
 * {@code application.yml} named a structured format -- so every service emitted the framework's
 * default prose layout, which still appeared in the log group and still looked like logging. A prose
 * assertion in the architecture document cannot fail; this class can.
 *
 * <p>Alternatives Considered: asserting the encoder selection by starting a real
 * {@code SpringApplication} and capturing standard output. Rejected because logging is initialised by
 * an application listener before the context exists, so such a test would be asserting the order of
 * two framework listeners rather than this module's configuration, and it would leave the assertion
 * dependent on whatever appender the surrounding build had already installed. The two halves are
 * separated instead: the property tests resolve the shipped file through the same config-data import
 * every service uses, and the encoder tests drive the encoder directly with a supplied environment.
 *
 * <p>Alternatives Considered: asserting the exact byte sequence of a rendered record. Rejected
 * because the framework owns the member order and the timestamp, so such an assertion would fail on a
 * patch upgrade that changed neither the field set nor the format identifier. What is asserted is the
 * two properties this module actually depends on: that the record is ONE JSON object rather than a
 * prose line, and that the fields the migration joins records on are present in it.
 *
 * <p>Assumptions: no container, network endpoint or external service is used, so this class belongs
 * under Surefire as a unit test rather than under Failsafe.
 */
class StructuredLoggingDefaultsTest {

    /** The shipped defaults file, imported exactly as each service's {@code application.yml} imports it. */
    private static final String SHARED_DEFAULTS = "classpath:/carddemo-common-defaults.yml";

    /** The property that selects a structured console format, or disables one when empty. */
    private static final String FORMAT_PROPERTY = "logging.structured.format.console";

    /** The format identifier the shared defaults select. */
    private static final String ECS_FORMAT = "ecs";

    /** The service name a deployment supplies, which also carries the metric service dimension. */
    private static final String SERVICE_NAME = "authorization-service";

    /** The environment label a deployment supplies through {@code CARDDEMO_ENVIRONMENT}. */
    private static final String ENVIRONMENT_LABEL = "prod";

    /** The version label a deployment supplies through {@code CARDDEMO_VERSION}. */
    private static final String VERSION_LABEL = "1.0.0";

    /** The literal the shipped file falls back to when a deployment supplies neither label. */
    private static final String UNSPECIFIED_LABEL = "unspecified";

    /** A correlation identity of the shape the shared filter mints, used as a mapped-context value. */
    private static final String CORRELATION_VALUE = "CD4F1C0E423A554D219B7E";

    /**
     * The two deployment values the shipped file reads from the process environment.
     *
     * <p>Assumptions: these are held as ENVIRONMENT VARIABLE names rather than as canonical property
     * names because that is the layer a task definition supplies them on, and because the relaxed
     * binding that turns {@code CARDDEMO_ENVIRONMENT} into {@code carddemo.environment} is itself part
     * of what this class asserts.
     */
    private static final Map<String, Object> DEPLOYMENT_VARIABLES = Map.of(
            "CARDDEMO_ENVIRONMENT", ENVIRONMENT_LABEL,
            "CARDDEMO_VERSION", VERSION_LABEL);

    /**
     * Replaces the process environment this context resolves against with the two values above.
     *
     * <p>Purpose: make the environment-variable layer this class depends on a value the test supplies,
     * so that the assertions describe the shipped file rather than the machine the build runs on.
     *
     * <p>⚠️ Refactoring Rationale: the previous form supplied the two values through
     * {@code withPropertyValues} on the claim that a property and an environment variable "resolve
     * through the same relaxed binding either way". That claim is false in one direction, and the
     * direction it fails in is the one the documented runtime uses. {@code withPropertyValues} adds a
     * plain map source keyed by the LITERAL name {@code CARDDEMO_ENVIRONMENT}, which a request for
     * {@code carddemo.environment} does not match, because only {@link SystemEnvironmentPropertySource}
     * relaxes names. The real system-environment source does relax, and it sits AHEAD of the imported
     * file that {@link ConfigDataApplicationContextInitializer} appends at the end of the list. So on a
     * machine where {@code CARDDEMO_ENVIRONMENT} is genuinely set -- which is every deployed task, and
     * which is what {@code /opt/carddemo-tools/svc-aws.env} and {@code svc-common.env} do for the
     * documented local runtime -- the ambient value won and the assertion compared the shipped file
     * against the operator's machine. Observed directly: the build was green with the variable unset
     * and reported {@code expected: "prod" but was: "local"} with it set. Both env-backed values were
     * exposed, not just one: {@code svc-common.env} supplies {@code CARDDEMO_VERSION=1.0.0-local}, so
     * the version assertion passed only because that particular file had not been sourced.
     *
     * <p>Alternatives Considered: asserting the canonical {@code carddemo.environment} property
     * directly instead. Rejected because the placeholder indirection and the relaxed-name mapping ARE
     * the mechanism under test -- injecting the resolved value would leave a file that named the wrong
     * variable, or named none, still passing.
     *
     * <p>Alternatives Considered: clearing the variables for the test process. Rejected because a Java
     * process cannot portably mutate its own environment, and a launcher-level exclusion would move the
     * requirement into build configuration where a developer running the class from an IDE would not
     * inherit it.
     *
     * @param variables the environment variables this context may see, which may be empty
     * @return an initializer substituting a controlled system-environment source
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> controlledEnvironment(
            Map<String, Object> variables) {
        return context -> {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            // WHY : Assumptions: a StandardEnvironment always carries a source under this name, so
            //       REPLACE is used rather than addFirst. Replacing is what makes the test hermetic --
            //       adding ahead of the real source would leave the machine's other variables visible,
            //       and a future value collision would resurface exactly this defect.
            sources.replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            variables));
        };
    }

    /**
     * A context runner that imports the shipped defaults and supplies the three deployment values.
     *
     * <p>Assumptions: the service name is supplied as a property because that is how a service's own
     * {@code application.yml} sets it, while the environment and version arrive as environment
     * variables through {@link #controlledEnvironment()} because that is how a task definition sets
     * them. The controlled-environment initializer is registered FIRST so that the substitution is in
     * place before the config-data import resolves any placeholder against it.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(controlledEnvironment(DEPLOYMENT_VARIABLES))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.config.import=" + SHARED_DEFAULTS,
                    "spring.application.name=" + SERVICE_NAME);

    /**
     * The same runner with a process environment that supplies NEITHER deployment value.
     *
     * <p>Purpose: reach the branch of the shipped file that only it can satisfy. Because
     * {@link SystemEnvironmentPropertySource} relaxes names, a set {@code CARDDEMO_ENVIRONMENT}
     * supplies {@code carddemo.environment} on its own, ahead of the imported file -- so an assertion
     * made with the variable present cannot distinguish the file's mapping from the variable's own
     * relaxed binding. With the variable absent, the ONLY thing that can produce a value is the
     * file's own declaration and its default.
     */
    private final ApplicationContextRunner runnerWithoutDeploymentVariables = new ApplicationContextRunner()
            .withInitializer(controlledEnvironment(Map.of()))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.config.import=" + SHARED_DEFAULTS,
                    "spring.application.name=" + SERVICE_NAME);

    /**
     * The shipped defaults select the structured format for every service that imports them.
     */
    @Test
    @DisplayName("the shared defaults select the ECS structured console format")
    void sharedDefaultsSelectTheStructuredConsoleFormat() {
        this.runner.run(context ->
                assertThat(context.getEnvironment().getProperty(FORMAT_PROPERTY)).isEqualTo(ECS_FORMAT));
    }

    /**
     * The three service dimensions resolve to the same values the shared meter filter reads.
     *
     * <p>Assumptions: this is the property that lets one filter expression select a service's log
     * records and its metric series. Asserting each of the three separately is deliberate: a single
     * assertion on all three would report only the first difference, and the failure mode this guards
     * against -- one of the three left unresolved while the other two work -- is exactly the case
     * where knowing WHICH one matters.
     */
    @Test
    @DisplayName("the three service dimensions resolve to the metric tag values")
    void serviceDimensionsResolveToTheMetricTagValues() {
        this.runner.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("logging.structured.ecs.service.name"))
                    .isEqualTo(SERVICE_NAME);
            assertThat(environment.getProperty("logging.structured.ecs.service.environment"))
                    .isEqualTo(ENVIRONMENT_LABEL);
            assertThat(environment.getProperty("logging.structured.ecs.service.version"))
                    .isEqualTo(VERSION_LABEL);

            // WHY : Assumptions: the two carddemo.* keys are asserted alongside the three above
            //       because they are the SAME values, read by the shared meter filter. If the
            //       structured members were ever changed to literals, these two would still hold
            //       while the three above silently described a different environment.
            assertThat(environment.getProperty("carddemo.environment")).isEqualTo(ENVIRONMENT_LABEL);
            assertThat(environment.getProperty("carddemo.version")).isEqualTo(VERSION_LABEL);
        });
    }

    /**
     * With neither variable supplied, the shipped file still resolves both tags to its own fallback.
     *
     * <p>Purpose: assert the half of the mapping that the case above cannot. A deployment that forgets
     * the two variables must still produce a log record and a metric series with a readable tag rather
     * than an unresolved placeholder, which is a startup failure, or an empty string, which silently
     * groups every such deployment together.
     *
     * <p>⚠️ Refactoring Rationale: this case was added because making the class hermetic revealed that
     * its existing assertions could not see the file's declaration at all. Replacing
     * {@code carddemo.environment} in the shipped file with the literal {@code hardcoded-wrong} left the
     * whole class green, because the controlled variable supplied that property through relaxed binding
     * before the file was consulted. With the variables absent there is no such shadow, so this case
     * fails on exactly that mutation. Verified both ways: green as shipped, and red for the literal.
     *
     * <p>Assumptions: the fallback is asserted as the file's literal rather than as "any non-empty
     * value" because the value reaches an operator's filter expression -- a silent change from
     * {@code unspecified} to something else would split one deployment's records across two tag values.
     */
    @Test
    @DisplayName("with neither deployment variable set the tags fall back to the shipped literal")
    void absentDeploymentVariablesFallBackToTheShippedLiteral() {
        this.runnerWithoutDeploymentVariables.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("carddemo.environment")).isEqualTo(UNSPECIFIED_LABEL);
            assertThat(environment.getProperty("carddemo.version")).isEqualTo(UNSPECIFIED_LABEL);

            // WHY : Trade-offs: the two structured members are asserted here as well as in the case
            //       above. There they prove the members exist; here they prove they follow the SAME
            //       fallback, so a deployment missing the variables cannot end up with a metric tag
            //       reading "unspecified" while its log records read something else.
            assertThat(environment.getProperty("logging.structured.ecs.service.environment"))
                    .isEqualTo(UNSPECIFIED_LABEL);
            assertThat(environment.getProperty("logging.structured.ecs.service.version"))
                    .isEqualTo(UNSPECIFIED_LABEL);
        });
    }

    /**
     * A deployment may switch the structured format off, and an empty value is what does it.
     *
     * <p>Assumptions: an importing document sits ABOVE the imported one in precedence, so a service
     * or a developer overriding the format is asserted here through a property value that outranks
     * the shipped file. An empty value is the disabling value because the framework treats a blank
     * format as "no structured encoder", which is why the fallback console pattern needs no second
     * switch of its own.
     */
    @Test
    @DisplayName("an empty format value switches structured logging off without a second key")
    void anEmptyFormatValueSwitchesStructuredLoggingOff() {
        this.runner.withPropertyValues(FORMAT_PROPERTY + "=").run(context -> {
            assertThat(context.getEnvironment().getProperty(FORMAT_PROPERTY)).isEmpty();

            // WHY : Assumptions: the fallback pattern is asserted to SURVIVE the override, because a
            //       console with neither a structured encoder nor a pattern would emit the
            //       framework's own default layout and the correlation keys would vanish from every
            //       line -- the same silent failure the structured format was added to end.
            assertThat(context.getEnvironment().getProperty("logging.pattern.console"))
                    .contains("%X{" + CorrelationIdFilter.CORRELATION_ID_MDC_KEY + ":-}");
        });
    }

    /**
     * The selected encoder renders one JSON object per event, not a prose line.
     *
     * @throws Exception if the encoded bytes cannot be produced
     */
    @Test
    @DisplayName("the selected encoder renders one JSON object per event")
    void theSelectedEncoderRendersOneJsonObjectPerEvent() throws Exception {
        String record = encodeOneEvent(Map.of());

        assertThat(record).startsWith("{").endsWith("\n");
        assertThat(record.strip()).endsWith("}");

        // WHY : Assumptions: the newline count is asserted rather than the absence of newlines,
        //       because a structured record is terminated by exactly one. A record carrying two
        //       would be two log events to a collector reading line by line, which is the failure
        //       that makes a multi-line prose stack trace unqueryable in the first place.
        assertThat(record.chars().filter(character -> character == '\n').count()).isEqualTo(1L);
        assertThat(record).contains("\"@timestamp\"").contains("\"message\"").contains("\"level\":\"INFO\"");
    }

    /**
     * The rendered record carries the three service dimensions as fields.
     *
     * @throws Exception if the encoded bytes cannot be produced
     */
    @Test
    @DisplayName("the rendered record carries the three service dimensions")
    void theRenderedRecordCarriesTheThreeServiceDimensions() throws Exception {
        String record = encodeOneEvent(Map.of());

        // WHY : Assumptions: the three dimensions are asserted as members of a NESTED service object
        //       rather than as three dotted top-level keys, because that is the shape the schema
        //       declares and the shape a log-insights expression has to address them by. Asserting a
        //       flattened spelling would pass against a formatter this build does not use and fail
        //       against the one it does.
        assertThat(record).contains("\"name\":\"" + SERVICE_NAME + "\"");
        assertThat(record).contains("\"environment\":\"" + ENVIRONMENT_LABEL + "\"");
        assertThat(record).contains("\"version\":\"" + VERSION_LABEL + "\"");
        assertThat(record).contains("\"service\":{");
    }

    /**
     * The rendered record carries the correlation identity the shared filter published.
     *
     * <p>Assumptions: the identity travels through the mapped diagnostic context and not as part of
     * the message text, so what is asserted is that the context key becomes a FIELD. A correlation
     * value interpolated into prose would satisfy a substring check on the value alone, which is why
     * the assertion names the key and the value together.
     *
     * @throws Exception if the encoded bytes cannot be produced
     */
    @Test
    @DisplayName("the rendered record carries the correlation identity as a queryable field")
    void theRenderedRecordCarriesTheCorrelationIdentityAsAField() throws Exception {
        String record = encodeOneEvent(
                Map.of(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_VALUE));

        assertThat(record).contains(
                "\"" + CorrelationIdFilter.CORRELATION_ID_MDC_KEY + "\":\"" + CORRELATION_VALUE + "\"");
    }

    /**
     * The shipped defaults keep the security filter chain off the container's ERROR dispatch and keep the
     * fallback error body free of the request path.
     *
     * <p>Assumptions: this case lives in this class rather than in one of its own, and the placement is
     * argued rather than incidental. This class is the ONLY place that resolves the shipped defaults file
     * through the same config-data import a service uses, so an assertion about any value that file ships
     * belongs beside the runner that can see it; a second class would duplicate the runner in order to
     * assert two keys. The class's name names the largest of the file's concerns rather than all of them
     * — the file also ships the two refusal-rendering defaults asserted here.</p>
     *
     * <p>⚠️ Assumptions: the dispatcher-type value is the substance. The framework's own default is
     * {@code REQUEST, ASYNC, ERROR}, and with ERROR included a request refused before reaching a
     * controller was authorised a SECOND time on the container's internal error dispatch — where the
     * bearer-token filter does not run, because it is a once-per-request filter and those skip error
     * dispatches — so the request arrived anonymous and a caller holding a valid token was answered 401
     * for a fault that was really a 400. Reverting this key reopens exactly that, silently, so it is
     * asserted rather than trusted.</p>
     *
     * <p>Assumptions: the two keys are asserted together because they are two halves of one guarantee.
     * Exempting the error dispatch is only safe because the body that dispatch renders discloses nothing;
     * if the path were ever restored to that body, the exemption would begin echoing a caller-supplied
     * address on every unmatched request.</p>
     */
    @Test
    @DisplayName("the shared defaults exempt the error dispatch and withhold the fallback path")
    void sharedDefaultsCarryTheRefusalRenderingValues() {
        this.runner.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.security.filter.dispatcher-types"))
                    .as("including ERROR re-authorises a refused request on the container's own error "
                            + "dispatch, where the bearer-token filter does not run")
                    .isEqualTo("REQUEST, ASYNC");
            assertThat(environment.getProperty("server.error.include-path"))
                    .as("the exemption above is only safe while the fallback body echoes no "
                            + "caller-supplied address")
                    .isEqualTo("never");
        });
    }

    /**
     * Encodes one logging event through the encoder the shared defaults select.
     *
     * <p>Assumptions: the environment is supplied to the logger context under the key the encoder
     * looks it up by, which is how the framework itself hands the resolved properties to an appender
     * declared in XML. Building it here rather than starting an application is what keeps this
     * assertion about the encoder's output instead of about listener ordering.
     *
     * @param mappedContext the mapped diagnostic context entries the event carries; may be empty
     * @return the encoded record as text, including its terminating newline
     * @throws Exception if the encoder cannot be started or the event cannot be encoded
     */
    private static String encodeOneEvent(Map<String, String> mappedContext) throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.structured.ecs.service.name", SERVICE_NAME)
                .withProperty("logging.structured.ecs.service.environment", ENVIRONMENT_LABEL)
                .withProperty("logging.structured.ecs.service.version", VERSION_LABEL);

        // WHY : Assumptions: the logger context is stopped in a finally block rather than opened as a
        //       try-with-resources subject, because Logback's context is not an AutoCloseable. Leaving
        //       it unstopped would leak its scheduled executor for the lifetime of the surefire fork.
        LoggerContext loggerContext = new LoggerContext();
        loggerContext.putObject(Environment.class.getName(), environment);

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggerContext);
        encoder.setFormat(ECS_FORMAT);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        try {
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName(StructuredLoggingDefaultsTest.class.getName());
            event.setLevel(Level.INFO);
            event.setMessage("event=api.request.accepted");
            event.setTimeStamp(0L);
            event.setMDCPropertyMap(mappedContext);
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
            loggerContext.stop();
        }
    }
}
