package com.carddemo.common;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.observability.MetricsConfig;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * response there to operate on. The cursor sealer is the one exception among the non-servlet beans:
 * it is conditional on a deployment naming its key material, because it is the only component here
 * that needs a secret and a shared kernel must not ship one.</p>
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
     * The property naming the base64-encoded key material every sealed keyset cursor is
     * authenticated with.
     *
     * <p>Assumptions: named here and defaulted nowhere. {@link CursorToken} is the only component in
     * the shared kernel that needs a secret, and a shared kernel must not invent one -- a value
     * shipped in this module would be a signing key committed to source, and every deployment that
     * failed to override it would accept cursors minted by anyone holding this repository. Making the
     * sealer conditional on a deployment naming a key is what gives the type one concrete configured
     * owner without shipping the secret that owning it requires.</p>
     *
     * <p>Trade-offs: the value is base64 rather than the raw characters of a passphrase, because the
     * constructor takes bytes and a properties source carries characters. The cost is that an
     * operator has to encode the secret once; the benefit is that the 32-byte floor
     * {@link CursorToken#MIN_KEY_LENGTH} imposes is a floor on real key material rather than on a
     * character count that a multi-byte encoding could satisfy with fewer bytes than it appears.</p>
     */
    public static final String CURSOR_SIGNING_KEY_PROPERTY =
            "carddemo.pagination.cursor.signing-key";

    /**
     * The property naming how long a sealed cursor stays redeemable, as an ISO-8601 duration.
     *
     * <p>Assumptions: separate from the key so that a deployment can shorten the window without
     * rotating key material, which are two independent operational decisions.</p>
     */
    public static final String CURSOR_LIFETIME_PROPERTY = "carddemo.pagination.cursor.lifetime";

    /**
     * The cursor lifetime applied when a deployment names key material but no lifetime.
     *
     * <p>Assumptions: fifteen minutes, chosen as an upper bound on how long a user leaves a list
     * screen open between paging keystrokes. A default is safe here in a way a default key is not,
     * because a lifetime is not a secret and a wrong one fails visibly -- a cursor stops being
     * redeemable -- rather than silently accepting tokens a stranger minted.</p>
     */
    public static final String DEFAULT_CURSOR_LIFETIME = "PT15M";

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
        // Assumptions: the declared return type is the codec library's module interface rather than
        //   the concrete class, because the framework's codec auto-configuration collects beans of
        //   that interface type into the mapper builder. Declaring the concrete type would still
        //   satisfy the collection -- a subtype is assignable -- but it would tie the registration to
        //   this implementation, so a future replacement would change a signature that a consumer
        //   may have injected by type.
        return new MoneyModule();
    }

    /**
     * Publishes the one cursor sealer every paged read of the application shares.
     *
     * <p>Refactoring Rationale: this bean is the configured owner of {@link CursorToken}. Before it
     * existed the type had none, so the two components that need a sealer -- the transaction list
     * service and the pending-authorization view mapper -- each documented a future integration
     * instead of a wiring, and neither could have started. One bean per application context is also
     * what makes the contract hold: a token sealed on the way out is only redeemable on the way back
     * in if the same key opens it, so a second instance with different key material would reject
     * cursors this application itself issued.</p>
     *
     * <p>Alternatives Considered: constructing a sealer inside each consumer from its own property.
     * Rejected because two consumers reading the same secret independently is two chances to read a
     * different one, and the failure is silent until a client pages: the cursor a mapper issues is
     * simply refused by the service that opens it, with nothing in the response saying why.</p>
     *
     * @param signingKeyBase64 the base64-encoded key material named by
     *     {@value #CURSOR_SIGNING_KEY_PROPERTY}, supplied by the deployment from the secret store
     *     provisioned in {@code infra/modules/secrets}; must decode to at least
     *     {@link CursorToken#MIN_KEY_LENGTH} bytes
     * @param lifetime how long a sealed cursor stays redeemable, named by
     *     {@value #CURSOR_LIFETIME_PROPERTY} as an ISO-8601 duration and defaulting to
     *     {@value #DEFAULT_CURSOR_LIFETIME}
     * @return the sealer and opener the application's paged reads share, never {@code null}
     * @throws IllegalArgumentException if the key material is not base64, decodes to fewer than
     *     {@link CursorToken#MIN_KEY_LENGTH} bytes, or the lifetime is not a positive ISO-8601
     *     duration; each of those fails the context at assembly rather than the first paged request
     * @throws java.time.format.DateTimeParseException if the lifetime is not an ISO-8601 duration
     */
    @Bean
    @ConditionalOnMissingBean(CursorToken.class)
    @ConditionalOnProperty(name = CURSOR_SIGNING_KEY_PROPERTY)
    public CursorToken carddemoCursorToken(
            @Value("${" + CURSOR_SIGNING_KEY_PROPERTY + "}") String signingKeyBase64,
            @Value("${" + CURSOR_LIFETIME_PROPERTY + ":" + DEFAULT_CURSOR_LIFETIME + "}")
                    String lifetime) {
        // Trade-offs: the lifetime is taken as text and parsed here rather than injected as a
        //   Duration. Binding a Duration through a value expression depends on a conversion service
        //   being installed on the bean factory, which is true in a Boot application and not in a
        //   plain context test, so parsing explicitly keeps this bean method behaving identically in
        //   both and puts the malformed-value failure on a line a reader can find.
        return new CursorToken(decodeSigningKey(signingKeyBase64), Duration.parse(lifetime.trim()));
    }

    /**
     * Decodes the configured signing key, naming the property in any failure.
     *
     * <p>Trade-offs: the decoder's own message says only that a character was illegal, which in a
     * startup stack trace gives an operator no idea which value to correct. Rethrowing with the
     * property name costs one catch block and turns an unexplained context failure into an
     * actionable one.</p>
     *
     * @param signingKeyBase64 the configured value, base64 with the standard alphabet; must not be
     *     {@code null}
     * @return the decoded key material, never {@code null}
     * @throws IllegalArgumentException if the value is not valid base64
     */
    private static byte[] decodeSigningKey(String signingKeyBase64) {
        try {
            return Base64.getDecoder().decode(signingKeyBase64.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(
                    CURSOR_SIGNING_KEY_PROPERTY + " must carry base64-encoded key material of at"
                            + " least " + CursorToken.MIN_KEY_LENGTH + " bytes", notBase64);
        }
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
         * <p>Assumptions: the context's shared clock is passed to the filter, because the filter now
         * renders its own refusal body and that body carries a timestamp. Letting the filter fall back
         * to its system-clock default would leave one request stamped from two different clocks
         * whenever a test substituted a fixed reading for the advice, which is precisely the case a
         * fixed reading exists to make assertable.</p>
         *
         * @param clock the clock the filter's refusal body reads its failure instant from, resolved
         *     from the context so a test can substitute a fixed reading
         * @return the registration placing one shared {@link CorrelationIdFilter} instance at
         *     {@link CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER} across all request
         *     paths, never {@code null}
         */
        @Bean
        @ConditionalOnMissingBean(name = "carddemoCorrelationIdFilterRegistration")
        public FilterRegistrationBean<CorrelationIdFilter> carddemoCorrelationIdFilterRegistration(
                Clock clock) {
            FilterRegistrationBean<CorrelationIdFilter> registration =
                    new FilterRegistrationBean<>(new CorrelationIdFilter(clock));
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
