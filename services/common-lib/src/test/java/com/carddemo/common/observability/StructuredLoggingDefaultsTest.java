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
import org.springframework.core.env.Environment;
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

    /** A correlation identity of the shape the shared filter mints, used as a mapped-context value. */
    private static final String CORRELATION_VALUE = "CD4F1C0E423A554D219B7E";

    /**
     * A context runner that imports the shipped defaults and supplies the three deployment values.
     *
     * <p>Assumptions: the three values are supplied as PROPERTIES rather than as environment
     * variables, because the placeholders in the shipped file resolve through the same relaxed
     * binding either way and a test cannot set an environment variable for its own process. What is
     * being asserted is that the file's placeholders resolve at all and resolve to the values the
     * meter filter reads, not the operating system's variable mechanism.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.config.import=" + SHARED_DEFAULTS,
                    "spring.application.name=" + SERVICE_NAME,
                    "CARDDEMO_ENVIRONMENT=" + ENVIRONMENT_LABEL,
                    "CARDDEMO_VERSION=" + VERSION_LABEL);

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
