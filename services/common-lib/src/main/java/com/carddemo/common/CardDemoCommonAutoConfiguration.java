package com.carddemo.common;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.observability.MetricsConfig;
import com.carddemo.common.web.CorrelationIdFilter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import tools.jackson.databind.JacksonModule;

/**
 * Registers the shared kernel's cross-cutting components in every service that puts this module on
 * its path.
 *
 * <p>Refactoring Rationale: this class did not exist, and its absence was the defect it corrects. The
 * correlation filter, the common-tag meter filter, the money codec module and the single error advice
 * all live in package roots that no service scans -- a service scans its own bounded context's root and
 * nothing else -- so each was written, tested and then never instantiated by anything. The observable
 * symptom was specific rather than theoretical: log lines carried no correlation identity because the
 * filter was in no chain, meters carried no service dimension because the meter filter was never
 * registered, and a failed request was rendered by the framework's own default because the advice was
 * not a bean.</p>
 *
 * <p>Alternatives Considered: requiring each service to import these components explicitly, from its
 * own configuration package or from a wider component scan. Rejected on the same ground the error
 * package's charter gives for reaching its advice by propagation rather than by an invocation at each
 * of fourteen sites: a registration a service has to remember is a registration a service can omit,
 * and the symptom of omitting it is invisible -- the service starts, serves requests and simply emits
 * no correlation identity. Eight explicit registrations would also be eight places for the
 * filter's ordering to be got wrong. Auto-configuration is reached by putting the module on the path,
 * which every service already does because it cannot compile without it.</p>
 *
 * <p>Trade-offs: this makes the shared kernel the first module in the reactor to own a
 * {@code src/main/resources} entry -- the single registration file this class is named in. That is a
 * real departure from the library-with-no-resources shape the module charters describe, and it is
 * accepted for one reason: there is no other mechanism by which a library can contribute a bean to a
 * consumer that has not been told to look for it. What it does NOT do is make the module configurable:
 * the file names this class and holds nothing else, no property is read from a resource, and no message
 * text is externalised. Every component below is additionally guarded by a missing-bean condition, so a
 * service that has a reason to supply its own replaces it rather than colliding with it.</p>
 *
 * <p>Refactoring Rationale: the servlet filter and request-error advice are isolated in conditional
 * nested configurations rather than guarded only on their bean methods. Spring must introspect every
 * method signature on an auto-configuration class before it can evaluate a method-level condition;
 * keeping {@link FilterRegistrationBean}, {@link CorrelationIdFilter} or
 * {@link GlobalExceptionHandler} on this outer class would therefore make a non-web consumer resolve
 * {@code jakarta.servlet.Filter} merely to discover that the bean should be skipped. The batch
 * context deliberately has no servlet API, so that shape fails before a job can start. A class-level
 * name condition is evaluated from bytecode metadata before either nested class is loaded, which
 * keeps this outer configuration introspectable on both web and non-web classpaths.</p>
 *
 * <p>Assumptions: the metrics contribution, system clock and money module remain unconditional
 * because none has a servlet dependency. A one-shot batch task needs the same meter dimensions and
 * exact-money mapper contract as an online service, while the filter and advice have no request or
 * response there to operate on.</p>
 */
@AutoConfiguration
@Import(MetricsConfig.class)
public class CardDemoCommonAutoConfiguration {

    /**
     * The order the correlation filter is placed at in the servlet filter chain.
     *
     * <p>Assumptions: as close to first as the framework's own ordering permits, because the identity
     * this filter establishes has to exist before anything else can log under it. The security filter
     * chain, the request-logging filter and every handler run after it, so an ordering further down
     * would leave the earliest lines of a request -- including an authentication failure, which is
     * precisely the kind a client reports -- with no identity attached. The value is the framework's
     * highest-precedence constant offset by one so that a deployment with a genuine reason to run
     * something ahead of it still can.</p>
     */
    public static final int CORRELATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * Supplies the clock the error advice timestamps problem shapes from.
     *
     * <p>Trade-offs: the system clock in the platform's default zone, matching what the migrated
     * timestamp formatter is written against. A fixed zone was considered and rejected: the baseline's
     * 26-character timestamp carries no zone designator at all, so pinning one here would assert an
     * offset the format cannot express, and the value that reaches a client would silently disagree
     * with the value the same service wrote to its own record.</p>
     *
     * @return the system default-zone clock, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock carddemoClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Registers the money codec module so every payload carries an amount as a JSON string.
     *
     * <p>Assumptions: contributed as a codec module bean, which the framework's own codec
     * auto-configuration collects and applies to the shared object mapper. Registering it on a mapper
     * built by hand instead would leave every mapper the framework builds -- the one the web layer
     * uses among them -- without it, which is exactly the state that lets an amount reach a client as a
     * bare JSON number and be parsed into binary floating point on arrival. Transformation rule T3
     * forbids that, and this bean is where the prohibition becomes effective rather than merely
     * documented.</p>
     *
     * @return the money codec module, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(MoneyModule.class)
    @ConditionalOnClass(JacksonModule.class)
    public JacksonModule carddemoMoneyModule() {
        // WHY : Assumptions: the declared return type is the codec library's module interface rather
        //       than the concrete class, because the framework's codec auto-configuration collects
        //       beans of that interface type into the mapper builder. Declaring the concrete type would
        //       still satisfy the collection -- a subtype is assignable -- but it would tie the
        //       registration to this implementation, so a future replacement would change a signature
        //       that a consumer may have injected by type.
        return new MoneyModule();
    }

    /**
     * Contains the correlation-filter registration only when a servlet runtime is actually present.
     *
     * <p>Refactoring Rationale: the condition names the servlet API as text rather than as a class
     * literal so Spring can reject this configuration from class-file metadata without asking the JVM
     * to resolve the very type whose absence the condition handles. A method-level condition was
     * considered and rejected because method discovery resolves the return type first, reproducing the
     * non-web startup failure this boundary exists to prevent.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static final class ServletCorrelationConfiguration {

        /**
         * Registers the correlation filter for every request path, ordered ahead of the security
         * chain.
         *
         * <p>Assumptions: registered through the framework's registration bean rather than as a bare
         * filter bean, and the difference is load-bearing. A bare filter bean is added to the chain
         * with the default order and every url pattern, so its position relative to the security chain
         * would be whatever bean ordering happened to produce; the registration bean states both the
         * order and the pattern explicitly, which is what makes the guarantee above checkable rather
         * than incidental.</p>
         *
         * @return the registration placing one shared {@link CorrelationIdFilter} instance at
         *     {@link CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER} across all request
         *     paths, never {@code null}
         */
        @Bean
        @ConditionalOnMissingBean(name = "carddemoCorrelationIdFilterRegistration")
        public FilterRegistrationBean<CorrelationIdFilter> carddemoCorrelationIdFilterRegistration() {
            FilterRegistrationBean<CorrelationIdFilter> registration =
                    new FilterRegistrationBean<>(new CorrelationIdFilter());
            registration.setOrder(CORRELATION_FILTER_ORDER);

            // WHY : Assumptions: every path, including the management endpoints. A health probe that
            //       fails is diagnosed from the same operational record as a failed business request,
            //       so excluding the actuator paths would remove the identity from exactly the lines
            //       an operator reads while a task is being taken out of rotation.
            registration.addUrlPatterns("/*");
            registration.setName("carddemoCorrelationIdFilter");
            return registration;
        }
    }

    /**
     * Contains request-error rendering only when the servlet, web and security contracts it handles
     * are present.
     *
     * <p>Assumptions: all three names are checked together because the advice's public handler
     * signatures use the servlet request, its type carries the web advice annotation and one handler
     * claims Spring Security's access-denial exception. Loading the advice with any one absent would
     * fail during handler introspection instead of cleanly omitting a bean. Online services declare
     * all three contracts; the batch task declares none.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "jakarta.servlet.http.HttpServletRequest",
        "org.springframework.web.bind.annotation.RestControllerAdvice",
        "org.springframework.security.access.AccessDeniedException"
    })
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static final class ServletErrorConfiguration {

        /**
         * Registers the single error advice that renders every failed request as the migrated problem
         * shape.
         *
         * <p>Assumptions: the class-level condition is deliberately stronger than a condition on this
         * bean method. Spring can decide whether the configuration is eligible from metadata before
         * resolving this method's return type, which is the ordering required for a library shared
         * with a non-web batch context.</p>
         *
         * @param clock the clock every emitted problem shape reads its failure instant from, resolved
         *     from the context so a test can substitute a fixed reading
         * @return the advice bean, never {@code null}
         */
        @Bean
        @ConditionalOnMissingBean(GlobalExceptionHandler.class)
        public GlobalExceptionHandler carddemoGlobalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }
    }
}
