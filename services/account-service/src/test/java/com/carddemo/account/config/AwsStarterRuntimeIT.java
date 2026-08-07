package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

/**
 * Exercises the Spring Cloud AWS configuration-import and queue paths against a real AWS endpoint,
 * under the Spring Boot release this build pins.
 *
 * <p>Assumptions: this class exists because bean creation is not the whole of the compatibility
 * question. Spring Cloud AWS 4.1.0 is built against Spring Boot 4.0.7 -- its build parent,
 * {@code org.springframework.cloud:spring-cloud-build:5.0.2}, declares {@code spring-boot.version} as
 * {@code 4.0.7} -- while this reactor forces Boot 4.1.0. {@code AwsIntegrationStartupTest} covers the
 * startup half without a network; what remains, and what only a real endpoint can answer, is whether a
 * {@code spring.config.import=aws-parameterstore:...} location actually delivers properties into the
 * environment and whether a message published through the template actually reaches a method annotated
 * with the listener annotation. Both run through Boot machinery that changed between 4.0 and 4.1 -- the
 * configuration-data phase and the application lifecycle the listener container is started by -- so
 * both are asserted here rather than assumed.</p>
 *
 * <p>Alternatives Considered: mocking the two clients. Rejected outright, because a mock would assert
 * that this code called a method and the question is whether the FRAMEWORK wires and drives it. A
 * mocked configuration import cannot fail the way a relocated resolver interface fails, which is
 * exactly the failure this class is here to catch.</p>
 *
 * <p>Trade-offs: this test needs a container runtime and pulls a large emulator image, so it is slower
 * and heavier than every other test in this module. It is a failsafe integration test rather than a
 * unit test for that reason: the cheap startup assertions run on every build, and this one runs where
 * a container runtime exists. The emulator tag is pinned to the same release the repository's existing
 * COBOL harness uses, so one emulator version serves the whole repository.</p>
 *
 * <h2>Why this class is opt-in rather than unconditional</h2>
 *
 * <p>Assumptions: the pinned emulator release refuses to start without a licence token -- it exits with
 * status 55 and reports "License activation failed" when {@code LOCALSTACK_AUTH_TOKEN} is absent, which
 * was observed rather than read from documentation. A test that demanded that token unconditionally
 * would therefore fail every build on a machine that has no emulator account, which is a worse outcome
 * than not running: it would report a licensing gap as a compatibility failure.</p>
 *
 * <p>Refactoring Rationale: the gate below is a deliberate port of the convention this repository
 * already applies to exactly this problem. Its COBOL harness treats the emulator layer as OPTIONAL and
 * degrades a missing emulator to a warning, while refusing to let a run that TARGETED that layer pass
 * having executed nothing -- {@code CARDDEMO_REQUIRE_LOCALSTACK=1} is the switch that turns an absent
 * emulator from a skip into a failure there. The same two environment variables carry the same two
 * meanings here, so one convention governs both harnesses and neither can be silently satisfied by a
 * run that verified nothing.</p>
 *
 * <p>Trade-offs: a skipped assertion proves nothing, so the compatibility evidence this class produces
 * is recorded where a reader will find it without running anything -- the property rationale in
 * {@code services/pom.xml} and {@code docs/adr/ADR-002-compute-platform.md} both state that these paths
 * were exercised, and this class is what they were exercised WITH. The alternative -- asserting the
 * combination in prose alone -- is what the review found and rejected.</p>
 */
class AwsStarterRuntimeIT {

    /**
     * The emulator image, pinned to the release the repository already standardises on.
     *
     * <p>Assumptions: the tag is explicit rather than left to the Testcontainers module default,
     * because the default moves with the module version and this repository pins the emulator release
     * in its Python test requirements. One version across the repository means a behavioural
     * difference between the two harnesses cannot be an emulator difference.</p>
     */
    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:2026.6.1");

    /**
     * The region both sides of this test use.
     *
     * <p>Assumptions: the value is stated once here and given to the container AND to every client,
     * rather than each side deciding for itself. The emulator accepts any region, but the SQS API
     * validates the one the client sends, so the two must agree; naming it once is what makes that
     * agreement visible instead of accidental.</p>
     */
    private static final String EMULATOR_REGION = "us-east-1";

    /** The path the configuration import reads, mirroring the deployed parameter hierarchy. */
    private static final String PARAMETER_PATH = "/carddemo/it/";

    /** One parameter under that path, named as the deployed roots name their entries. */
    private static final String PARAMETER_NAME = PARAMETER_PATH + "verified-datasource-url";

    /** A sentinel value, distinctive enough that finding it proves it came from the emulator. */
    private static final String PARAMETER_VALUE =
            "jdbc:postgresql://parameter-store-was-really-read:5432/carddemo";

    /** The queue the round trip publishes to and consumes from. */
    private static final String QUEUE_NAME = "carddemo-it-inquiry-request";

    /** The payload the round trip carries, in the fixed-width shape this context's inquiry uses. */
    private static final String QUEUE_PAYLOAD = "00000000011,ACCTINQ,00000000000000000000";

    /** How long a consumer is given to receive one message before the assertion fails. */
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);

    /** The variable the pinned emulator release reads its licence token from. */
    private static final String AUTH_TOKEN_VARIABLE = "LOCALSTACK_AUTH_TOKEN";

    /** The variable that turns an absent emulator from a skip into a failure. */
    private static final String REQUIRE_VARIABLE = "CARDDEMO_REQUIRE_LOCALSTACK";

    /**
     * The emulator, started once for the class and shared by both assertions.
     *
     * <p>Assumptions: the container is started by hand rather than by the Testcontainers extension,
     * because the extension starts an annotated field before any assumption can be evaluated and this
     * class must decide whether to run at all first. Only the two services these starters use are
     * enabled; starting the whole emulator would work and would cost startup time for endpoints
     * nothing here reads.</p>
     */
    private static LocalStackContainer localstack;

    /**
     * Starts and seeds the emulator, or explains precisely why the class is not running.
     *
     * <p>Assumptions: the seeding uses the AWS SDK directly rather than the Spring beans under test,
     * so a failure to seed cannot be mistaken for a failure of the integration being verified.</p>
     *
     * @throws IllegalStateException if the emulator was REQUIRED by {@code CARDDEMO_REQUIRE_LOCALSTACK}
     *     and no licence token is available, because a run that asked for this layer must not pass
     *     having executed none of it
     */
    @BeforeAll
    static void startAndSeedTheEmulator() {
        String token = System.getenv(AUTH_TOKEN_VARIABLE);
        boolean required = "1".equals(System.getenv(REQUIRE_VARIABLE));
        if (token == null || token.isBlank()) {
            String explanation = AUTH_TOKEN_VARIABLE + " is not set, and the pinned emulator release"
                    + " exits with status 55 without it";
            if (required) {
                throw new IllegalStateException(REQUIRE_VARIABLE + "=1 asked for the emulator-backed"
                        + " AWS assertions, but " + explanation);
            }
            Assumptions.abort(explanation
                    + "; set " + REQUIRE_VARIABLE + "=1 to make this a failure instead of a skip");
        }

        localstack = new LocalStackContainer(LOCALSTACK_IMAGE)
                .withServices("sqs", "ssm")
                .withEnv(AUTH_TOKEN_VARIABLE, token)
                .withEnv("DEFAULT_REGION", EMULATOR_REGION);
        localstack.start();

        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
        Region region = Region.of(EMULATOR_REGION);

        try (SsmClient ssm = SsmClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .build()) {
            ssm.putParameter(PutParameterRequest.builder()
                    .name(PARAMETER_NAME)
                    .value(PARAMETER_VALUE)
                    .type(ParameterType.STRING)
                    .overwrite(true)
                    .build());
        }
        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .build()) {
            sqs.createQueue(CreateQueueRequest.builder().queueName(QUEUE_NAME).build());
        }
    }

    /**
     * Stops the emulator, if this class started one.
     *
     * <p>Assumptions: the null check is what makes the skip path clean -- when the assumption aborted,
     * no container was ever created and there is nothing to stop.</p>
     */
    @AfterAll
    static void stopTheEmulator() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    /**
     * Confirms a Parameter Store location named in {@code spring.config.import} delivers its
     * properties into the environment of a context started under this Boot release.
     *
     * <p>Assumptions: the assertion searches the environment for the sentinel VALUE rather than for a
     * property name this test predicts. The name a location contributes is a convention of the
     * resolver, and pinning the assertion to a predicted name would make this test fail on a
     * convention change while the mechanism it exists to verify still worked. What matters is that the
     * value arrived and is reachable through {@link ConfigurableEnvironment#getProperty(String)}, and
     * that it arrived from a property source the import contributed.</p>
     */
    @Test
    @DisplayName("a parameter-store configuration import delivers properties into the environment")
    void aParameterStoreImportDeliversPropertiesIntoTheEnvironment() {
        try (ConfigurableApplicationContext context = start(
                ParameterStoreOnlyConfiguration.class,
                "spring.config.import=aws-parameterstore:" + PARAMETER_PATH)) {
            ConfigurableEnvironment environment = context.getEnvironment();
            Map<String, String> carrying = propertiesHolding(environment, PARAMETER_VALUE);

            assertThat(carrying)
                    .as("the import must contribute the seeded parameter; an unresolved location"
                            + " contributes nothing and reports nothing")
                    .isNotEmpty();
            carrying.forEach((name, source) -> assertThat(environment.getProperty(name))
                    .as("%s came from property source %s and must resolve through the environment",
                            name, source)
                    .isEqualTo(PARAMETER_VALUE));
            assertThat(carrying.values())
                    .as("the contributing property source must be the AWS import rather than one of"
                            + " this test's own property sources")
                    .anySatisfy(source -> assertThat(source).contains("aws-parameterstore"));
        }
    }

    /**
     * Confirms a message published through the template reaches a method annotated as a listener.
     *
     * <p>Assumptions: the round trip is asserted through the ANNOTATION rather than through a direct
     * receive call, because the annotation is what the migrated consumers use and it depends on three
     * separate pieces of framework wiring -- the annotation post-processor the auto-configuration
     * imports, the listener container factory, and the container being started with the application
     * lifecycle. A direct receive would pass with all three broken.</p>
     *
     * @throws InterruptedException if the waiting thread is interrupted, which the queue's timed poll
     *     declares
     */
    @Test
    @DisplayName("a message sent through the template reaches an annotated listener method")
    void aMessageSentThroughTheTemplateReachesAnAnnotatedListener() throws InterruptedException {
        try (ConfigurableApplicationContext context = start(
                SqsRoundTripConfiguration.class, "carddemo.it.queue-name=" + QUEUE_NAME)) {
            context.getBean(SqsTemplate.class).send(QUEUE_NAME, QUEUE_PAYLOAD);

            String received = context.getBean(RecordingListener.class).awaitOne();

            assertThat(received)
                    .as("the listener must receive the published payload unchanged within %s",
                            RECEIVE_TIMEOUT)
                    .isEqualTo(QUEUE_PAYLOAD);
        }
    }

    /**
     * Starts a context against the emulator with the supplied additional properties.
     *
     * @param source the primary configuration class, which decides which starters are active; must not
     *     be {@code null}
     * @param extraProperties properties beyond the emulator coordinates, in {@code key=value} form;
     *     must not be {@code null}
     * @return the started context, which the caller closes; never {@code null}
     */
    private ConfigurableApplicationContext start(Class<?> source, String... extraProperties) {
        List<String> properties = new ArrayList<>(List.of(
                // WHY : Assumptions: the module's own application.yml is NOT loaded, because naming a
                //       document that does not exist is what keeps this context to the starters under
                //       test. That document is written for a deployed service: it reads the region
                //       from AWS_REGION and the datasource, issuer and queue names from placeholders
                //       this JVM does not set, and an unresolved placeholder reaches the SQS client as
                //       a literal region name -- which is how this was found, as a GetQueueUrl refusal
                //       naming the placeholder text itself.
                "spring.config.name=aws-runtime-it",
                "spring.cloud.aws.region.static=" + EMULATOR_REGION,
                "spring.cloud.aws.credentials.access-key=" + localstack.getAccessKey(),
                "spring.cloud.aws.credentials.secret-key=" + localstack.getSecretKey(),
                // WHY : Assumptions: the endpoint is set globally AND per integration. The global
                //       value is what the client builders read, while the configuration-import
                //       resolver runs before any bean exists and reads its own prefix, so a single
                //       global override would leave the import pointed at the real service.
                "spring.cloud.aws.endpoint=" + localstack.getEndpoint(),
                "spring.cloud.aws.parameterstore.endpoint=" + localstack.getEndpoint(),
                "spring.cloud.aws.parameterstore.region=" + EMULATOR_REGION,
                "spring.cloud.aws.sqs.endpoint=" + localstack.getEndpoint()));
        properties.addAll(List.of(extraProperties));

        // WHY : Alternatives Considered: SpringApplicationBuilder.properties(...), which is what this
        //       method used first. Rejected because it contributes DEFAULT properties, the lowest
        //       precedence source there is, so every value here was overridden by the module's own
        //       document -- the failure that produced the placeholder region above. Command-line
        //       arguments sit above every file-based source, which is the precedence this test needs.
        String[] arguments = properties.stream().map(property -> "--" + property).toArray(String[]::new);

        return new SpringApplicationBuilder(source)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(arguments);
    }

    /**
     * Finds every property whose resolved value equals the sentinel, with the source that carries it.
     *
     * @param environment the environment to search; must not be {@code null}
     * @param value the sentinel value to look for; must not be {@code null}
     * @return property name to property-source name, in property-source order; never {@code null}
     */
    private Map<String, String> propertiesHolding(ConfigurableEnvironment environment, String value) {
        Map<String, String> found = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (value.equals(String.valueOf(enumerable.getProperty(name)))) {
                    found.put(name, source.getName());
                }
            }
        }
        return found;
    }

    /**
     * The context for the configuration-import assertion: the configuration source starter only.
     *
     * <p>Assumptions: the queue starter is deliberately absent here, so a failure of the import
     * cannot be masked or caused by queue wiring.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            RegionProviderAutoConfiguration.class,
            CredentialsProviderAutoConfiguration.class,
            AwsAutoConfiguration.class,
            ParameterStoreAutoConfiguration.class})
    static class ParameterStoreOnlyConfiguration {
    }

    /**
     * The context for the round trip: the queue starter plus the recording listener.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            RegionProviderAutoConfiguration.class,
            CredentialsProviderAutoConfiguration.class,
            AwsAutoConfiguration.class,
            SqsAutoConfiguration.class})
    static class SqsRoundTripConfiguration {

        /**
         * Contributes the listener whose annotated method the framework must discover and drive.
         *
         * @return the recording listener; never {@code null}
         */
        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    /**
     * A listener that records what it receives so a test thread can wait for it.
     *
     * <p>Assumptions: the hand-off is a blocking queue rather than a field plus a sleep, because the
     * container delivers on its own thread and a sleep long enough to be reliable is longer than a
     * timed poll needs to be.</p>
     */
    static class RecordingListener {

        /** Payloads received, in arrival order. */
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        /**
         * Records one delivered payload.
         *
         * @param payload the message body the container converted; must not be {@code null}
         */
        @SqsListener("${carddemo.it.queue-name}")
        void onMessage(String payload) {
            received.add(payload);
        }

        /**
         * Waits for the first delivery.
         *
         * @return the payload received, or {@code null} if none arrived within the timeout
         * @throws InterruptedException if the waiting thread is interrupted
         */
        String awaitOne() throws InterruptedException {
            return received.poll(RECEIVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }
}
