package com.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.control.OnlineWriteGate;
import com.carddemo.common.control.OnlineWriteGateInterceptor;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.RequestBodySizeFilter;
import com.carddemo.common.web.CursorToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import tools.jackson.databind.JacksonModule;

/**
 * Asserts that the shared kernel's auto-configuration contributes exactly the right beans to a web
 * context and exactly the right subset to a non-web one.
 *
 * <p>Purpose: verify the registration contract end to end -- the entry in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, the
 * class-level conditions on the two nested servlet configurations, and the
 * {@code @ConditionalOnMissingBean} guards -- by starting real application contexts rather than by
 * calling the factory methods directly.
 *
 * <p>Refactoring Rationale: this is the module's first integration test, and it exists for two
 * reasons that are separate but point the same way. The first is the contract itself: the four
 * cross-cutting components this module publishes are instantiated by nothing unless the imports file,
 * the conditions and the consumer's classpath all agree, and every failure of that agreement is
 * silent -- log lines with no correlation identity, meters with no service dimension, amounts on the
 * wire as bare JSON numbers, and failed requests rendered in the framework's own shape. A unit test on
 * any one class cannot see any of that, because the defect is in whether the class is reached at all.
 * The second reason is the build: the reactor binds Failsafe to run {@code *IT} classes at the
 * integration-test phase, and until this class existed the pattern matched nothing, so the gate was
 * bound and demonstrably unexercised. A gate whose first execution happens after someone relies on it
 * is not a gate.
 *
 * <p>Alternatives Considered: naming this class {@code ...Test} so Surefire ran it in the test phase.
 * Rejected on both counts above -- it is an integration test by content, since it starts a context and
 * asserts on the assembled graph rather than on one unit, and putting it under Surefire would leave the
 * Failsafe binding with nothing to run.
 *
 * <p>Alternatives Considered: {@code @SpringBootTest} on a purpose-built application class. Rejected
 * because it would require an entry point this module deliberately does not have -- it is a library and
 * not a deployable -- and because the context runner asserts the conditions directly: it can start the
 * same auto-configuration as a web context and as a non-web one within a single test class, which is
 * the distinction the two nested configurations exist to draw.
 *
 * <p>Assumptions: no external service, container, database or network endpoint is used. The
 * integration being tested is between this module's classes, its registration resource and the
 * framework's condition evaluation, all of which are in-process. That keeps the phase this class runs
 * in fast and keeps its result independent of anything outside the reactor.
 */
class CardDemoCommonAutoConfigurationIT {

    /** The auto-configuration under test, applied exactly as a consumer's classpath would apply it. */
    private static final AutoConfigurations UNDER_TEST =
            AutoConfigurations.of(CardDemoCommonAutoConfiguration.class);

    /**
     * Thirty-two bytes of base64-encoded key material, the minimum the sealer accepts.
     *
     * <p>Assumptions: a readable phrase rather than random bytes, so a reader can see at a glance that
     * this is test material and not a key copied from a deployment. Exactly at the floor, because a
     * longer value would leave the floor itself unexercised by the sibling that asserts refusal.
     */
    private static final String TEST_SIGNING_KEY_BASE64 =
            "Y2FyZGRlbW8tY3Vyc29yLXRlc3Qta2V5LTMyYnl0ZXM=";

    /**
     * Parameter path standing in for the online-write flag.
     *
     * <p>Assumptions: shaped like the real one, which an environment root composes from its own
     * prefix and environment name, so a reader recognises what the property carries. Nothing here
     * reads the parameter's value; the stub answers whatever is asked for.
     */
    private static final String TEST_FLAG_PARAMETER =
            "/carddemo/dev/batch/online-writes-enabled";

    /**
     * A servlet web context receives every contribution, including both filter registrations.
     *
     * <p>Assumptions: each filter is asserted through its registration bean rather than as a bare
     * filter bean, because the registration is what carries the order and the url pattern. Asserting
     * only that a filter exists would pass against a bare bean whose position in the chain is
     * whatever bean ordering produced.
     *
     * <p>Refactoring Rationale: this case asserted a SINGLE registration bean, which was true while
     * the kernel contributed one filter and became wrong when it began bounding request bodies as
     * well. The replacement asserts both by bean NAME and asserts the relationship between their two
     * orders, which is the property that actually matters: the body bound renders a refusal carrying
     * the correlation identity, and that identity does not exist until the filter ahead of it has
     * published one. A pair of independent order assertions would pass for two filters ordered the
     * wrong way round.</p>
     */
    @Test
    @DisplayName("a servlet web context receives the clock, money module, both filters and the advice")
    void contributesEveryComponentToAWebContext() {
        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).hasSingleBean(JacksonModule.class);
                    assertThat(context.getBean(JacksonModule.class)).isInstanceOf(MoneyModule.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context.getBeansOfType(FilterRegistrationBean.class))
                            .containsOnlyKeys("carddemoCorrelationIdFilterRegistration",
                                    "carddemoRequestBodySizeFilterRegistration");

                    FilterRegistrationBean<?> correlation = (FilterRegistrationBean<?>)
                            context.getBean("carddemoCorrelationIdFilterRegistration");
                    assertThat(correlation.getFilter()).isInstanceOf(CorrelationIdFilter.class);
                    assertThat(correlation.getOrder())
                            .isEqualTo(CardDemoCommonAutoConfiguration.CORRELATION_FILTER_ORDER);
                    assertThat(correlation.getUrlPatterns()).containsExactly("/*");

                    FilterRegistrationBean<?> bodyBound = (FilterRegistrationBean<?>)
                            context.getBean("carddemoRequestBodySizeFilterRegistration");
                    assertThat(bodyBound.getFilter()).isInstanceOf(RequestBodySizeFilter.class);
                    assertThat(bodyBound.getOrder())
                            .isEqualTo(CardDemoCommonAutoConfiguration.BODY_SIZE_FILTER_ORDER)
                            .isGreaterThan(correlation.getOrder());
                    assertThat(bodyBound.getUrlPatterns()).containsExactly("/*");
                    assertThat(((RequestBodySizeFilter) bodyBound.getFilter()).maxBodyBytes())
                            .as("an unnamed bound must fall back to the documented default rather"
                                    + " than leaving the body unbounded")
                            .isEqualTo(RequestBodySizeFilter.DEFAULT_MAX_BODY_BYTES);
                });
    }

    /**
     * A deployment-named bound replaces the default, and a non-positive one fails the context.
     *
     * <p>Assumptions: both halves are asserted in one case because they are one decision. The bound is
     * configurable so that a deployment serving a larger legitimate body can raise it; the value being
     * configurable is exactly what makes a nonsensical value reachable, and a bound of zero would
     * refuse every write this system publishes while the context started successfully. Failing at
     * assembly turns that into a startup failure naming the value.</p>
     */
    @Test
    @DisplayName("a named request-body bound is applied, and a non-positive one fails the context")
    void theRequestBodyBoundIsConfigurableAndValidated() {
        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withPropertyValues(CardDemoCommonAutoConfiguration.MAX_REQUEST_BODY_BYTES_PROPERTY
                        + "=4096")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequestBodySizeFilter filter = (RequestBodySizeFilter)
                            ((FilterRegistrationBean<?>) context
                                    .getBean("carddemoRequestBodySizeFilterRegistration"))
                                    .getFilter();
                    assertThat(filter.maxBodyBytes()).isEqualTo(4096L);
                });

        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withPropertyValues(CardDemoCommonAutoConfiguration.MAX_REQUEST_BODY_BYTES_PROPERTY
                        + "=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("maxBodyBytes"));
    }

    /**
     * A non-web context receives the two servlet-free contributions and neither servlet one.
     *
     * <p>Assumptions: this is the batch context's shape. It needs the same meter dimensions and the
     * same exact-money mapper contract as an online service, and it has no request or response for
     * either filter or the error advice to operate on. The absence assertion covers both filters,
     * because both registrations live in the one nested configuration whose conditions are under
     * test here. Asserting the ABSENCE is what proves the nested
     * conditions are evaluated from class-file metadata before the servlet return types are resolved:
     * if they were not, this context would fail to start rather than start without the two beans.
     */
    @Test
    @DisplayName("a non-web context receives the clock and money module and neither servlet bean")
    void omitsServletComponentsOutsideAWebContext() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).hasSingleBean(JacksonModule.class);
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    /**
     * A consumer's own clock wins, and the advice is timestamped from it.
     *
     * <p>Assumptions: this is the guard that makes the contributed clock a DEFAULT rather than an
     * imposition. It matters concretely rather than academically: a test that needs to assert an
     * emitted timestamp exactly substitutes a fixed clock, and if the contribution were unconditional
     * the substitution would either be ignored or make the context ambiguous.
     */
    @Test
    @DisplayName("a consumer-declared clock replaces the contributed one")
    void yieldsToAConsumerDeclaredClock() {
        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withUserConfiguration(FixedClockConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context.getBean(Clock.class))
                            .isSameAs(FixedClockConfiguration.FIXED_CLOCK);
                });
    }

    /**
     * No signing key named means no cursor sealer, and a context that still starts.
     *
     * <p>Assumptions: this is the shape a service with no paged read has, and asserting the ABSENCE
     * is what proves the sealer cannot arrive with key material this module invented. A bean here
     * would mean a signing key shipped in source, which is the one thing a sealed cursor exists to
     * prevent.
     *
     * <p>Trade-offs: "no bean" and "no failure" are asserted together on purpose. Withholding the
     * bean is only useful if a context that never pages still starts, and that guarantee holds only
     * while no configuration file declares the property -- a placeholder with no default makes the
     * property EXIST, so condition evaluation resolves it and every importing context fails. The
     * four transaction-service repository integration tests are exactly that shape, which is why
     * {@link #bindsTheCursorSigningKeyFromItsDocumentedEnvironmentVariableName()} carries the other
     * half of the decision: the key reaches a deployment through its environment, not through a
     * declared property.
     */
    @Test
    @DisplayName("no cursor sealer is published until a deployment names key material")
    void withholdsTheCursorSealerUntilAKeyIsNamed() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CursorToken.class);
                });
    }

    /**
     * A named signing key yields exactly one sealer, and that sealer round-trips a cursor.
     *
     * <p>Assumptions: the round trip is asserted rather than the bean's mere presence, because a
     * sealer built from a mis-decoded key would still be a single bean of the right type and would
     * still seal -- it would simply refuse every token the application itself issued, and the symptom
     * would surface only when a client paged.
     */
    @Test
    @DisplayName("a named signing key publishes one sealer that round-trips a cursor")
    void publishesTheCursorSealerWhenAKeyIsNamed() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withPropertyValues(
                        CardDemoCommonAutoConfiguration.CURSOR_SIGNING_KEY_PROPERTY + "="
                                + TEST_SIGNING_KEY_BASE64,
                        CardDemoCommonAutoConfiguration.CURSOR_LIFETIME_PROPERTY + "=PT5M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CursorToken.class);

                    CursorToken sealer = context.getBean(CursorToken.class);
                    String sealed = sealer.seal("transaction-list:0000000001", "0000000000000042");
                    assertThat(CursorToken.hasSealedShape(sealed)).isTrue();
                    assertThat(sealer.open("transaction-list:0000000001", sealed))
                            .isEqualTo("0000000000000042");
                });
    }

    /**
     * The documented environment-variable spelling activates the sealer through relaxed binding.
     *
     * <p>Assumptions: no {@code application.yml} in this repository declares the signing key, not
     * even as an environment placeholder, because a declared property resolves during condition
     * evaluation and an unresolvable placeholder then fails every context that merely imports this
     * auto-configuration. Supplying the environment variable IS therefore the whole wiring, and it
     * only works if Spring Boot maps {@code CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY} onto the
     * hyphenated property name.
     *
     * <p>Refactoring Rationale: that mapping is asserted here rather than trusted. It is the single
     * step between a correctly-provisioned secret and a service that cannot assemble its list
     * screen, it is invisible in every other test because they all set the property directly, and
     * the underscore-for-hyphen substitution is exactly the kind of convention a reader assumes
     * without checking. A {@code SystemEnvironmentPropertySource} is used rather than a plain map
     * because only that source type carries the relaxed-binding mapper under test.
     */
    @Test
    @DisplayName("the documented environment variable name activates the cursor sealer")
    void bindsTheCursorSigningKeyFromItsDocumentedEnvironmentVariableName() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withInitializer(context -> {
                    MutablePropertySources sources =
                            context.getEnvironment().getPropertySources();
                    sources.replace(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    Map.of(
                                            "CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY",
                                            TEST_SIGNING_KEY_BASE64)));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CursorToken.class);
                    assertThat(
                                    context.getEnvironment()
                                            .getProperty(
                                                    CardDemoCommonAutoConfiguration
                                                            .CURSOR_SIGNING_KEY_PROPERTY))
                            .isEqualTo(TEST_SIGNING_KEY_BASE64);
                });
    }

    /**
     * Key material too short to key the authentication code fails the context, naming the property.
     *
     * <p>Assumptions: a short key produces a perfectly well-formed and verifiable -- but weaker --
     * code, so nothing downstream can detect it. Failing at assembly is the only point at which an
     * operator learns of it, and the property name in the message is what makes the failure
     * actionable rather than merely fatal.
     */
    @Test
    @DisplayName("key material shorter than the authentication code requires fails the context")
    void refusesKeyMaterialShorterThanTheAuthenticationCodeRequires() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withPropertyValues(
                        CardDemoCommonAutoConfiguration.CURSOR_SIGNING_KEY_PROPERTY
                                + "=dG9vLXNob3J0LWZvci1obWFjLXNoYTI1Ng==")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * No online-write gate is published until a deployment names the flag's parameter.
     *
     * <p>Assumptions: the withheld case is asserted first because it is the shape almost every context
     * in this repository has. The batch task and the extract-transform-load package are deliberately
     * not write-gated, and so is every test that does not set the property, so a gate published
     * unconditionally would try to resolve an AWS region and credentials in all of them merely to hold
     * a client nothing calls.
     */
    @Test
    @DisplayName("no online-write gate is published until a deployment names the flag")
    void withholdsTheOnlineWriteGateUntilTheFlagIsNamed() {
        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OnlineWriteGate.class);
                    assertThat(context).doesNotHaveBean(SsmClient.class);
                    assertThat(context).doesNotHaveBean("carddemoOnlineWriteGateMvcConfigurer");
                });
    }

    /**
     * Naming the flag publishes one gate and registers exactly one interceptor for it.
     *
     * <p>Assumptions: the registration is asserted by invoking the configurer and capturing what it
     * adds, not merely by the configurer bean's presence. A configurer that registered nothing would
     * be a bean of the right type in the right context and would leave every mutating request
     * ungated -- which is precisely the "created but read by nothing" failure this whole control was
     * added to correct, reproduced one layer higher.
     *
     * <p>Assumptions: a stub client is supplied so the context needs no region or credential. The gate
     * is not exercised here; that is the unit tests' subject. What is under test is the wiring.
     */
    @Test
    @DisplayName("naming the flag publishes one gate and registers one interceptor")
    void publishesTheOnlineWriteGateAndItsInterceptor() {
        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withUserConfiguration(StubSsmConfiguration.class)
                .withPropertyValues(
                        CardDemoCommonAutoConfiguration.ONLINE_WRITES_PARAMETER_PROPERTY
                                + "=" + TEST_FLAG_PARAMETER,
                        CardDemoCommonAutoConfiguration.ONLINE_WRITES_CACHE_PROPERTY + "=PT2S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OnlineWriteGate.class);
                    assertThat(context).hasBean("carddemoOnlineWriteGateMvcConfigurer");

                    CapturingInterceptorRegistry registry = new CapturingInterceptorRegistry();
                    context.getBean("carddemoOnlineWriteGateMvcConfigurer", WebMvcConfigurer.class)
                            .addInterceptors(registry);
                    assertThat(registry.added)
                            .hasSize(1)
                            .allMatch(OnlineWriteGateInterceptor.class::isInstance);
                });
    }

    /**
     * A non-web context receives the gate but no interceptor.
     *
     * <p>Assumptions: the gate is declared on the outer configuration rather than inside the
     * servlet-only nested class, so a context with no servlet API still assembles it. Asserting the
     * absence of the configurer alongside the presence of the gate is what shows the nested condition
     * is evaluated from class-file metadata rather than by resolving the MVC return type -- if it were
     * the latter, this context would fail to start rather than start without one bean.
     */
    @Test
    @DisplayName("a non-web context receives the gate and no interceptor registration")
    void contributesTheGateWithoutAnInterceptorOutsideAWebContext() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withUserConfiguration(StubSsmConfiguration.class)
                .withPropertyValues(
                        CardDemoCommonAutoConfiguration.ONLINE_WRITES_PARAMETER_PROPERTY
                                + "=" + TEST_FLAG_PARAMETER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OnlineWriteGate.class);
                    assertThat(context).doesNotHaveBean("carddemoOnlineWriteGateMvcConfigurer");
                });
    }

    /**
     * The documented environment-variable spelling activates the gate through relaxed binding.
     *
     * <p>Assumptions: no {@code application.yml} declares this property, exactly as none declares the
     * cursor signing key, so supplying the environment variable IS the whole wiring. It works only if
     * the framework maps {@code CARDDEMO_ONLINE_WRITES_PARAMETER} onto the hyphenated property name,
     * and that mapping is the single step between a correctly-provisioned environment and a service
     * whose write gate silently does not exist.
     *
     * <p>Refactoring Rationale: the property name was chosen to satisfy this mapping rather than to
     * mirror the Java package it lives in. A name carrying a {@code control} segment would have needed
     * the variable to be {@code CARDDEMO_CONTROL_ONLINE_WRITES_PARAMETER}, so this case is what pins
     * the two together -- renaming either without the other would leave the gate unpublished, and
     * nothing else in the build would notice.
     */
    @Test
    @DisplayName("the documented environment variable name activates the online-write gate")
    void bindsTheOnlineWriteFlagFromItsDocumentedEnvironmentVariableName() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withUserConfiguration(StubSsmConfiguration.class)
                .withInitializer(context -> {
                    MutablePropertySources sources =
                            context.getEnvironment().getPropertySources();
                    sources.replace(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    Map.of("CARDDEMO_ONLINE_WRITES_PARAMETER",
                                            TEST_FLAG_PARAMETER)));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OnlineWriteGate.class);
                    assertThat(context.getEnvironment().getProperty(
                                    CardDemoCommonAutoConfiguration
                                            .ONLINE_WRITES_PARAMETER_PROPERTY))
                            .isEqualTo(TEST_FLAG_PARAMETER);
                });
    }

    /**
     * A blank flag parameter fails the context rather than refusing every write at run time.
     *
     * <p>Assumptions: this is the misconfiguration worth failing loudly on. Because the gate fails
     * closed, a blank name would refuse every mutating request in the service while presenting as a
     * working control, so the only point at which an operator can learn of it is assembly.
     */
    @Test
    @DisplayName("a blank flag parameter fails the context instead of refusing every write")
    void aBlankFlagParameterFailsTheContext() {
        new ApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .withUserConfiguration(StubSsmConfiguration.class)
                .withPropertyValues(
                        CardDemoCommonAutoConfiguration.ONLINE_WRITES_PARAMETER_PROPERTY + "=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * An interceptor registry that records what a configurer adds to it.
     *
     * <p>Assumptions: a subclass rather than reflection over the registry's protected accessor. The
     * registration method is public, so overriding it observes exactly what a configurer does without
     * this test depending on a framework internal that a version bump could rename.
     */
    static final class CapturingInterceptorRegistry extends InterceptorRegistry {

        /** Interceptors added, in the order they were registered. */
        private final List<Object> added = new ArrayList<>();

        /**
         * Records the interceptor and registers it as the framework would.
         *
         * @param interceptor the interceptor a configurer added
         * @return the registration the superclass produced
         */
        @Override
        public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
            this.added.add(interceptor);
            return super.addInterceptor(interceptor);
        }
    }

    /**
     * Supplies a Systems Manager client stub so a gated context needs no region or credential.
     *
     * <p>Assumptions: the stub answers rather than throws, because these cases test WIRING. A stub
     * that threw would still satisfy every assertion here -- the gate is never asked for a decision --
     * but it would mislead the next reader into thinking the failure path was covered, and it is
     * covered in the gate's own unit tests instead.
     */
    @Configuration(proxyBeanMethods = false)
    static class StubSsmConfiguration {

        /**
         * Supplies the stub client.
         *
         * @return a client reporting the window open, never {@code null}
         */
        @Bean
        SsmClient ssmClient() {
            return new SsmClient() {
                @Override
                public GetParameterResponse getParameter(GetParameterRequest request) {
                    return GetParameterResponse.builder()
                            .parameter(Parameter.builder().value("true").build())
                            .build();
                }

                @Override
                public String serviceName() {
                    return SsmClient.SERVICE_NAME;
                }

                @Override
                public void close() {
                    // Assumptions: the stub holds nothing to release.
                }
            };
        }
    }

    /**
     * A consumer configuration declaring its own clock, used to exercise the missing-bean guard.
     */
    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        /** The reading a substituted clock returns, chosen so it cannot be a system reading. */
        static final Clock FIXED_CLOCK =
                Clock.fixed(Instant.parse("2026-08-05T09:16:44.902355Z"), ZoneOffset.UTC);

        /**
         * Supplies the substituted clock.
         *
         * @return the fixed clock, never {@code null}
         */
        @Bean
        Clock clock() {
            return FIXED_CLOCK;
        }
    }
}
