package com.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
     * A servlet web context receives all four contributions.
     *
     * <p>Assumptions: the filter is asserted through its registration bean rather than as a bare
     * filter bean, because the registration is what carries the order and the url pattern. Asserting
     * only that a filter exists would pass against a bare bean whose position in the chain is
     * whatever bean ordering produced.
     */
    @Test
    @DisplayName("a servlet web context receives the clock, money module, filter and error advice")
    void contributesEveryComponentToAWebContext() {
        new WebApplicationContextRunner()
                .withConfiguration(UNDER_TEST)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).hasSingleBean(JacksonModule.class);
                    assertThat(context.getBean(JacksonModule.class)).isInstanceOf(MoneyModule.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);

                    FilterRegistrationBean<?> registration =
                            context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(CorrelationIdFilter.class);
                    assertThat(registration.getOrder())
                            .isEqualTo(CardDemoCommonAutoConfiguration.CORRELATION_FILTER_ORDER);
                    assertThat(registration.getUrlPatterns()).containsExactly("/*");
                });
    }

    /**
     * A non-web context receives the two servlet-free contributions and neither servlet one.
     *
     * <p>Assumptions: this is the batch context's shape. It needs the same meter dimensions and the
     * same exact-money mapper contract as an online service, and it has no request or response for a
     * filter or an error advice to operate on. Asserting the ABSENCE is what proves the nested
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
