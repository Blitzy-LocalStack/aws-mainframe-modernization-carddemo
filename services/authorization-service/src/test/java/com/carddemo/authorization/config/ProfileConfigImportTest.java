package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * Asserts that every {@code spring.config.import} this module declares resolves on both deployment
 * profiles without an AWS region, an AWS credential or a reachable AWS endpoint.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: this module once declared a Parameter Store overlay on one profile and not
 * the other, and the pair could not both resolve. The queue starter brings
 * {@code spring-cloud-aws-autoconfigure}, whose {@code spring.factories} registers a resolver for the
 * {@code aws-parameterstore} prefix, so such a location IS claimed here and the resolver then builds a
 * Systems Manager client. Without the client artifact that construction raised
 * {@code NoClassDefFoundError}; with it, the client resolves a region through the default provider chain
 * and raised {@code SdkClientException} wherever the chain could not answer -- and this module
 * deliberately declines to pin {@code spring.cloud.aws.region.static} because a task supplies both
 * region and credentials. Neither failure is suppressed by the {@code optional:} marker, which covers a
 * claimed location holding no resource and not a resolver that throws while building its own client. The
 * overlay and the starter were therefore withdrawn together, and this test is what stops either coming
 * back unnoticed.</p>
 *
 * <p>Assumptions: the assertion is made against config-data resolution alone, through
 * {@link ConfigDataEnvironmentPostProcessor#applyTo}, rather than by starting a context. That is the
 * exact phase the earlier failures occurred in, and it is the only phase in which the question can be
 * asked cleanly: a context start additionally requires a datasource, an issuer and a queue client, so a
 * failure would no longer be attributable to an import. The environment carries no AWS setting at all,
 * which is what makes an accidental reintroduction of a remote config location fail here rather than in
 * a deployment.</p>
 *
 * <p>Assumptions: each profile also asserts one value it alone sets, so the test proves the profile
 * document was actually applied rather than merely that nothing threw. An import list that silently
 * resolved to nothing would otherwise pass.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class ProfileConfigImportTest {

    /**
     * The property the development overlay sets to a value no other document uses.
     */
    private static final String POOL_SIZE_PROPERTY = "spring.datasource.hikari.maximum-pool-size";

    /**
     * The connection count the development overlay sizes its pool to.
     */
    private static final String DEV_POOL_SIZE = "4";

    /**
     * The connection count the production overlay sizes its pool to.
     */
    private static final String PROD_POOL_SIZE = "20";

    /**
     * Verifies the development profile resolves every declared import with no AWS setting present.
     */
    @Test
    @DisplayName("the dev profile resolves every config import without an AWS region")
    void devProfileResolvesEveryDeclaredImport() {
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> applyProfile(environment, "dev")).doesNotThrowAnyException();
        assertThat(environment.getProperty(POOL_SIZE_PROPERTY)).isEqualTo(DEV_POOL_SIZE);
    }

    /**
     * Verifies the production profile resolves every declared import with no AWS setting present.
     */
    @Test
    @DisplayName("the prod profile resolves every config import without an AWS region")
    void prodProfileResolvesEveryDeclaredImport() {
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> applyProfile(environment, "prod")).doesNotThrowAnyException();
        assertThat(environment.getProperty(POOL_SIZE_PROPERTY)).isEqualTo(PROD_POOL_SIZE);
    }

    /**
     * Runs config-data resolution over this module's own documents for one profile.
     *
     * <p>Assumptions: the profile name is supplied both as a property and as an argument. The argument is
     * what the processor activates; the property is what a running application would carry, and setting
     * both keeps this exercise identical to the one a deployment performs.</p>
     *
     * @param environment the environment to resolve into; must not be {@code null}
     * @param profile the profile to activate; must not be {@code null}
     */
    private static void applyProfile(MockEnvironment environment, String profile) {
        environment.setProperty("spring.profiles.active", profile);
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(),
                new DefaultBootstrapContext(), profile);
    }
}
