package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreAutoConfiguration;
import io.awspring.cloud.autoconfigure.config.secretsmanager.SecretsManagerAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Verifies that the three Spring Cloud AWS starters this module declares actually produce their beans
 * and register their configuration-import resolvers under the Spring Boot release this build pins.
 *
 * <p>Assumptions: this class exists because a successful COMPILE proves nothing about this particular
 * combination. Spring Cloud AWS 4.1.0 is built against Spring Boot 4.0.7 -- its build parent,
 * {@code org.springframework.cloud:spring-cloud-build:5.0.2}, declares
 * {@code spring-boot.version} as {@code 4.0.7} -- while this reactor forces Boot 4.1.0, which the
 * migration plan freezes. Every risk that mismatch carries is a RUNTIME risk: a moved or removed Boot
 * type surfaces as a {@code NoClassDefFoundError} while the context is built, a changed bean signature
 * as an unsatisfied dependency, and a relocated configuration-import service-provider interface as a
 * location that silently resolves to nothing. None of those is visible to the compiler, because this
 * module compiles against none of the affected types.</p>
 *
 * <p>Alternatives Considered: pinning Spring Cloud AWS to a release built against Boot 4.0.x instead.
 * There is none to pin -- 4.1.0 is the newest release of the line and the whole line is built against
 * Boot 4.0.x -- so the choice is not between a matched and a mismatched pair. Replacing the starters
 * with hand-wired AWS SDK clients was also considered and rejected: it would withdraw the
 * configuration-import mechanism and the listener container that the messaging design depends on, and
 * the migration plan adopts this line by name. What remains is to VERIFY the combination and to keep
 * verifying it, which is what this class does.</p>
 *
 * <p>Trade-offs: these assertions run with no network and no container, so they cover the startup
 * risk and not the request path -- a bean that is created but cannot talk to a real endpoint would
 * still pass. The real endpoint path is covered separately by {@code AwsIntegrationConfigDataIT},
 * which exercises a configuration import and a queue round trip against a real emulator. The split is
 * deliberate: the cheap assertions run on every build, and the expensive ones run where a container
 * runtime exists.</p>
 */
class AwsIntegrationStartupTest {

    /** A region that is syntactically real, so no probe of instance metadata is attempted. */
    private static final String STATIC_REGION = "spring.cloud.aws.region.static=us-east-1";

    /** A static access key, so the credential chain resolves without a profile or metadata call. */
    private static final String STATIC_ACCESS_KEY =
            "spring.cloud.aws.credentials.access-key=carddemo-startup-probe";

    /** The matching secret, supplied for the same reason and never used against a real endpoint. */
    private static final String STATIC_SECRET_KEY =
            "spring.cloud.aws.credentials.secret-key=carddemo-startup-probe-secret";

    /**
     * The runner that builds a context from exactly the auto-configurations the three starters
     * contribute.
     *
     * <p>Assumptions: the six are named explicitly rather than reached through the whole
     * auto-configuration set, so a failure here identifies which contribution broke instead of
     * reporting that a large context did not start. The two core ones are included because the client
     * builders depend on them: without a region and a credentials provider the SQS, Parameter Store and
     * Secrets Manager beans have nothing to configure a client from.</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RegionProviderAutoConfiguration.class,
                    CredentialsProviderAutoConfiguration.class,
                    AwsAutoConfiguration.class,
                    SqsAutoConfiguration.class,
                    ParameterStoreAutoConfiguration.class,
                    SecretsManagerAutoConfiguration.class))
            .withPropertyValues(STATIC_REGION, STATIC_ACCESS_KEY, STATIC_SECRET_KEY);

    /**
     * Confirms the queue integration this module's messaging design depends on is actually built.
     *
     * <p>Assumptions: three beans are asserted rather than one, because they fail independently. The
     * asynchronous client is what a Boot-relocated type would break; the template is what the
     * publishing path uses; and the listener container factory is what an {@code @SqsListener} method
     * is bound to, so a context holding a client and a template but no factory would start and then
     * consume nothing.</p>
     */
    @Test
    @DisplayName("the SQS starter produces its client, template and listener container factory")
    void sqsStarterProducesItsClientTemplateAndListenerFactory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SqsAsyncClient.class);
            assertThat(context).hasSingleBean(SqsTemplate.class);
            assertThat(context).hasSingleBean(SqsMessageListenerContainerFactory.class);
        });
    }

    /**
     * Confirms the two configuration-source starters produce the clients their imports read through.
     *
     * <p>Assumptions: the migration plan makes Parameter Store and Secrets Manager the ONLY sources of
     * the datasource URL, the queue identifiers, the issuer URI and the key identifiers, so a context
     * that started without these two clients would be a service with no configuration rather than a
     * service missing a feature.</p>
     */
    @Test
    @DisplayName("the configuration starters produce the Parameter Store and Secrets Manager clients")
    void configurationStartersProduceTheirClients() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SsmClient.class);
            assertThat(context).hasSingleBean(SecretsManagerClient.class);
        });
    }

    /**
     * Confirms the core contributions the client builders are configured from are present.
     */
    @Test
    @DisplayName("the core starter produces a region provider and a credentials provider")
    void coreStarterProducesRegionAndCredentialsProviders() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AwsRegionProvider.class);
            assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
        });
    }

    /**
     * Confirms the configuration-import service-provider entries still name types this Boot release
     * declares, and that the implementations behind them load and implement those types.
     *
     * <p>Assumptions: this is the assertion that covers the failure mode with no symptom. Spring Cloud
     * AWS registers its {@code aws-parameterstore:} and {@code aws-secretsmanager:} location support
     * through {@code META-INF/spring.factories}, keyed by the fully-qualified names of two Boot
     * interfaces. Boot 4 reorganised packages extensively; had either interface moved, the key would
     * name a type that no longer exists, nothing would register, and a
     * {@code spring.config.import=aws-parameterstore:...} location would resolve to nothing at all --
     * with no error at build time and, for an {@code optional:} import, none at run time either. The
     * two keys are therefore read from the packaged file and compared against the interfaces this
     * build compiles with, and each named implementation is loaded and checked to implement it.</p>
     *
     * @throws IOException if the packaged service-provider files cannot be read, which would mean the
     *     starters are not on the test class path and the assertion would be vacuous
     * @throws ClassNotFoundException if a named implementation cannot be loaded, which is itself the
     *     incompatibility this test exists to detect
     */
    @Test
    @DisplayName("the configuration-import resolvers register against the Boot interfaces of this build")
    void configurationImportResolversRegisterAgainstThisBootRelease()
            throws IOException, ClassNotFoundException {
        List<String> resolvers = factoryEntries(ConfigDataLocationResolver.class.getName());
        List<String> loaders = factoryEntries(ConfigDataLoader.class.getName());

        assertThat(resolvers)
                .as("the parameter-store and secrets-manager locations must be registered under the"
                        + " resolver interface name this build's Spring Boot actually declares")
                .contains(
                        "io.awspring.cloud.autoconfigure.config.parameterstore"
                                + ".ParameterStoreConfigDataLocationResolver",
                        "io.awspring.cloud.autoconfigure.config.secretsmanager"
                                + ".SecretsManagerConfigDataLocationResolver");
        assertThat(loaders)
                .as("a registered resolver with no loader would resolve a location and then fail to"
                        + " read it")
                .contains(
                        "io.awspring.cloud.autoconfigure.config.parameterstore"
                                + ".ParameterStoreConfigDataLoader",
                        "io.awspring.cloud.autoconfigure.config.secretsmanager"
                                + ".SecretsManagerConfigDataLoader");

        for (String resolver : resolvers) {
            if (resolver.startsWith("io.awspring.cloud.")) {
                assertThat(ConfigDataLocationResolver.class)
                        .as("%s must implement the resolver interface of the Boot release in use",
                                resolver)
                        .isAssignableFrom(Class.forName(resolver));
            }
        }
        for (String loader : loaders) {
            if (loader.startsWith("io.awspring.cloud.")) {
                assertThat(ConfigDataLoader.class)
                        .as("%s must implement the loader interface of the Boot release in use", loader)
                        .isAssignableFrom(Class.forName(loader));
            }
        }
    }

    /**
     * Reads one service-provider key from every {@code META-INF/spring.factories} on the class path.
     *
     * @param key the fully-qualified interface name whose registrations are wanted; must not be
     *     {@code null}
     * @return every registered implementation name, in class-path order; never {@code null}
     * @throws IOException if a service-provider file cannot be read
     */
    private List<String> factoryEntries(String key) throws IOException {
        List<String> names = new ArrayList<>();
        Enumeration<URL> files =
                getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (files.hasMoreElements()) {
            Properties registrations = new Properties();
            try (InputStream file = files.nextElement().openStream()) {
                registrations.load(file);
            }
            String value = registrations.getProperty(key);
            if (value == null) {
                continue;
            }
            for (String name : value.split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }
}
