package com.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
