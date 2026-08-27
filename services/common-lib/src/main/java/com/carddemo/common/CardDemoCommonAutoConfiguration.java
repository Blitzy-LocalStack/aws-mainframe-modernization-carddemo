package com.carddemo.common;

import com.carddemo.common.config.FlywayOwnerRoleCallback;
import com.carddemo.common.config.FlywayOwnerRoleDataSourceCustomizer;
import com.carddemo.common.control.OnlineWriteGate;
import com.carddemo.common.control.OnlineWriteGateInterceptor;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.observability.MetricsConfig;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.RejectedRequestErrorReportValveCustomizer;
import com.carddemo.common.web.RequestBodySizeFilter;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.services.ssm.SsmClient;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

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
     * The order the request-body bound is placed at in the servlet filter chain.
     *
     * <p>Assumptions: immediately after the correlation filter and therefore still ahead of the security
     * chain, and both halves of that placement are load-bearing. After the correlation filter, because
     * the bound's refusal carries the correlation identity in its body and its log record, and that
     * identity does not exist until the filter ahead of it has published one. Ahead of the security
     * chain, because the whole purpose of the bound is to refuse an over-large body before work is done
     * on it, and authenticating a request is work -- a token signature is verified and a claim set is
     * parsed -- that a caller should not be able to cause by sending bytes nobody will accept.</p>
     *
     * <p>Alternatives Considered: placing it after the security chain so that only an authenticated
     * caller could reach it, on the reasoning that an unauthenticated flood is the edge's problem.
     * Rejected because it inverts the ordering the bound exists for: the refusal would then happen after
     * the most expensive per-request work in the chain rather than before it.</p>
     */
    public static final int BODY_SIZE_FILTER_ORDER = CORRELATION_FILTER_ORDER + 1;

    /**
     * The property naming the greatest number of bytes one request body may carry.
     *
     * <p>Assumptions: named in bytes rather than as a data-size string such as {@code 64KB}. A data-size
     * binding depends on a conversion service being installed on the bean factory, which is true in a
     * Boot application and not in a plain context test, so an explicit byte count keeps the bean method
     * behaving identically in both -- the same reasoning {@link #carddemoCursorToken} records for taking
     * its lifetime as text.</p>
     *
     * <p>Assumptions: one property for every service rather than one per bounded context. The bound is a
     * platform property, and a per-service bound would let two services that publish the same shape of
     * request disagree about how large it may be, with nothing failing on either side.</p>
     */
    public static final String MAX_REQUEST_BODY_BYTES_PROPERTY =
            "carddemo.web.max-request-body-bytes";

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
     * The property naming the Parameter Store entry that says whether the environment is currently
     * accepting mutating work.
     *
     * <p>Assumptions: this property is what makes a service write-gated, and it is defaulted nowhere.
     * A default would either name a parameter that does not exist -- which, because
     * {@link OnlineWriteGate} fails closed, would refuse every write in every deployment and every
     * test -- or name one that does, which would put an environment's topology into a shared library.
     * Leaving it unset is what lets the batch task and the extract-transform-load package run with no
     * gate at all, which is correct: the quiesce exists to protect the batch chain, so gating the
     * chain itself against its own window would deadlock it.</p>
     *
     * <p>Assumptions: the name is chosen so that the framework's own environment-variable resolution
     * maps {@code CARDDEMO_ONLINE_WRITES_PARAMETER} onto it with NO declaration in any
     * {@code application.yml}. The resolver uppercases the requested name and replaces both dots and
     * hyphens with underscores, so {@code carddemo.online-writes.parameter} is looked up as
     * {@code CARDDEMO_ONLINE_WRITES_PARAMETER} -- which is exactly the variable
     * {@code infra/envs/dev} and {@code infra/envs/prod} inject into the seven online workloads, and
     * exactly the one whose parameter ARN the same module grants the task role
     * {@code ssm:GetParameter} on. A {@code control} segment matching this class's package was the
     * first choice and was rejected on that arithmetic alone: it would have required the variable to
     * be {@code CARDDEMO_CONTROL_ONLINE_WRITES_PARAMETER}, so either the infrastructure would have had
     * to be renamed to suit a Java package name or the property would silently never resolve and the
     * gate would silently never exist -- which is the failure this whole control was added to fix.</p>
     *
     * <p>Assumptions: no service declares this property in its {@code application.yml}, and that
     * absence is deliberate rather than an omission. It follows the same discipline as
     * {@value #CURSOR_SIGNING_KEY_PROPERTY}, which likewise appears in no configuration file: a
     * placeholder with an empty default would make the property PRESENT with an empty value, which
     * satisfies the condition below and then fails the gate's own blank check at startup, and a
     * placeholder with no default would make every service that is not write-gated fail to start.
     * Binding straight from the environment means the gate exists exactly where the variable is
     * injected and nowhere else, with no second list to keep in step.</p>
     */
    public static final String ONLINE_WRITES_PARAMETER_PROPERTY =
            "carddemo.online-writes.parameter";

    /**
     * The property naming how long an online-write decision may be reused before the flag is read
     * again, as an ISO-8601 duration.
     *
     * <p>Assumptions: separate from the parameter name so that an environment can trade promptness
     * against request volume without restating its topology, which are two independent decisions.
     * Named in the same family as the parameter above so that it too resolves from
     * {@code CARDDEMO_ONLINE_WRITES_CACHE_PERIOD} if an environment ever needs to set it; nothing
     * injects it today, which is why it has the default below.</p>
     */
    public static final String ONLINE_WRITES_CACHE_PROPERTY =
            "carddemo.online-writes.cache-period";

    /**
     * The cache period applied when a deployment names the flag but no period.
     *
     * <p>Assumptions: five seconds. A default is safe here in the way a default parameter name is not,
     * because the value is not a secret and neither direction of error is silent: too short shows up
     * as request volume against Parameter Store, and too long shows up as a write accepted shortly
     * after a quiesce. Five seconds is deliberately the same order as the reference's own five-second
     * message wait, and it is short enough that a quiesce takes effect well inside the state
     * transition that follows it in the batch state machine.</p>
     */
    public static final String DEFAULT_ONLINE_WRITES_CACHE_PERIOD = "PT5S";

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
     * Refuses a non-textual scalar wherever a published contract declares a character field.
     *
     * <p>⚠️ Refactoring Rationale: this customiser existed in ONE service and the leniency it closes is
     * the reader's default in every service. Every scalar member of every request body this migration
     * publishes is declared {@code type: string}, because each stands for a fixed-width CHARACTER field
     * on a reference map -- an eleven-character account scope, a sealed selector, a paging direction, a
     * one-character action code, an optimistic-concurrency version. The reader's default is to accept a
     * JSON NUMBER for such a member and convert it, and the conversion is what makes the acceptance
     * undetectable: the number that arrives renders as exactly the digits the pattern constraint expects,
     * so validation passes and nothing reports that the caller sent a shape the contract refuses. The
     * measured case on the service that found it: {@code {"accountId":10000000101}} was answered 200 for
     * account 20000000001. A float reaching an integer version member truncates the same way, which on an
     * optimistic update means a version the contract rejects can authorise a write.</p>
     *
     * <p>⚠️ Assumptions: the refusal names the TEXTUAL target family and the three scalar input shapes
     * that are not text -- integer, floating point and boolean. It is deliberately NOT a blanket
     * withdrawal of scalar coercion, because coercion in the OPPOSITE direction is load-bearing here:
     * money crosses every boundary as a JSON string and is read into a {@code BigDecimal}, which is a
     * string-shaped input to a numeric target. The asymmetry is the point -- text may be read as a
     * number, a number may not be read as text.</p>
     *
     * <p>⚠️ Assumptions: an array, an object and a null reaching a textual member are left to the
     * reader's own handling. The first two already fail as shape mismatches, and a null is a legitimate
     * absent value for optional members such as a paging cursor, so refusing it here would turn "no
     * cursor supplied" into a malformed body.</p>
     *
     * <p>Alternatives Considered: {@code spring.jackson.mapper.allow-coercion-of-scalars: false}, which
     * is one property and needs no bean. Rejected because it is symmetric, so it would take the money
     * path down with it. Also considered: leaving the leniency and relying on the pattern constraint.
     * Rejected because the constraint sees the CONVERTED value, so it cannot tell a conforming string
     * from a number that converted into one, which is the whole defect. Trade-offs: a client that has
     * been sending a bare number for a declared string member now receives 400 where it previously
     * received an answer -- the intended effect of enforcing a published domain, and the same trade the
     * shared {@code fail-on-unknown-properties} default makes for an undeclared member.</p>
     *
     * @return the customiser the framework applies to the mapper it builds for the web layer, never
     *     {@code null}
     */
    @Bean
    @ConditionalOnClass(JsonMapperBuilderCustomizer.class)
    public JsonMapperBuilderCustomizer carddemoRefuseNonTextualScalarsForTextTargets() {
        // Assumptions: the bean is the FRAMEWORK's builder-customiser type rather than a mapper this
        //   method constructs, so it is applied to the same mapper the web layer builds. A separate
        //   mapper would be configured correctly and read nothing, because the request converter would
        //   keep using the framework's.
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> {
            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
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
     * Supplies the Systems Manager client the online-write gate reads its flag with.
     *
     * <p>Assumptions: the client is built from the SDK's default chains, so the region and the
     * credentials come from the task's own environment rather than from configuration this module
     * holds. On Fargate that is the task role and the injected region; nothing else has to be stated.
     * </p>
     *
     * <p>Assumptions: conditional on the same property as the gate itself, so that a context which is
     * not write-gated never constructs a client and therefore never needs a region, a credential or a
     * network path to reach one. Without that condition every service and every test would resolve
     * AWS credentials at startup merely to hold a client nothing calls.</p>
     *
     * @return the client, never {@code null}; closed by the context on shutdown, because the SDK's
     *     client implements {@link AutoCloseable} and the framework infers the destroy method from it
     */
    @Bean
    @ConditionalOnMissingBean(SsmClient.class)
    @ConditionalOnProperty(name = ONLINE_WRITES_PARAMETER_PROPERTY)
    public SsmClient carddemoSsmClient() {
        return SsmClient.create();
    }

    /**
     * Publishes the one online-write gate every mutating path of a service shares.
     *
     * <p>Purpose. This bean is what turns the quiesce flag from a value the infrastructure sets into a
     * control a running service obeys. Before it existed the flag was created, toggled and injected
     * into every online task definition, and read by nothing.</p>
     *
     * <p>Assumptions: declared on the outer configuration rather than inside the servlet-only nested
     * class below, so that the decision is a property of the application context and not of its web
     * layer. The interceptor is the only component that applies it today; keeping the gate outside the
     * servlet boundary means a future enforcement point that is not a request -- a scheduled task, say
     * -- injects the same instance and inherits the same fail-closed behaviour rather than
     * constructing a second gate with its own cache.</p>
     *
     * <p>Assumptions: one instance per application context, so the short-lived cache in front of the
     * parameter read is shared by every caller. A gate constructed per consumer would multiply the
     * reads by the number of consumers and let two of them hold different answers at the same instant.
     * </p>
     *
     * @param ssm the client the flag is read with, resolved from the context so a test can substitute
     *     a stub that returns a chosen value or throws
     * @param clock the context's shared clock, which the gate measures its cache period against;
     *     resolved from the context so that a test substituting a fixed reading for the error advice
     *     gets the same reading here rather than leaving one request measured by two clocks
     * @param parameterName the Parameter Store entry named by
     *     {@value #ONLINE_WRITES_PARAMETER_PROPERTY}
     * @param cachePeriod how long a decision may be reused, named by
     *     {@value #ONLINE_WRITES_CACHE_PROPERTY} as an ISO-8601 duration and defaulting to
     *     {@value #DEFAULT_ONLINE_WRITES_CACHE_PERIOD}
     * @return the gate, never {@code null}
     * @throws IllegalArgumentException if the parameter name is blank or the cache period is not
     *     positive, each of which fails the context at assembly rather than at the first write
     * @throws java.time.format.DateTimeParseException if the cache period is not an ISO-8601 duration
     */
    @Bean
    @ConditionalOnMissingBean(OnlineWriteGate.class)
    @ConditionalOnProperty(name = ONLINE_WRITES_PARAMETER_PROPERTY)
    public OnlineWriteGate carddemoOnlineWriteGate(
            SsmClient ssm,
            Clock clock,
            @Value("${" + ONLINE_WRITES_PARAMETER_PROPERTY + "}") String parameterName,
            @Value("${" + ONLINE_WRITES_CACHE_PROPERTY + ":" + DEFAULT_ONLINE_WRITES_CACHE_PERIOD
                    + "}") String cachePeriod) {
        // Trade-offs: the period is taken as text and parsed here for the same reason the cursor
        //   lifetime above is -- binding a Duration through a value expression depends on a conversion
        //   service that a plain context test does not install, so parsing explicitly keeps this bean
        //   method behaving identically in a Boot application and in a test.
        return new OnlineWriteGate(
                ssm, parameterName.trim(), Duration.parse(cachePeriod.trim()), clock);
    }

    /**
     * Contains the online-write gate's request-side registration only when a servlet runtime and an
     * MVC dispatcher are actually present.
     *
     * <p>Assumptions: isolated in a nested configuration for the same reason the correlation and error
     * configurations below are. The registration's own types -- the MVC configurer and the interceptor
     * registry -- come from the web starter, so keeping them on the outer class would make the batch
     * context resolve MVC types merely to discover that the beans should be skipped, and that context
     * deliberately has no servlet API at all.</p>
     *
     * <p>Assumptions: conditional on the gate's property as well as on the runtime, so that the
     * interceptor is registered exactly where the gate exists. Registering it without a gate is not
     * possible -- the bean below could not be constructed -- and this condition makes that a skipped
     * configuration rather than an unsatisfied dependency.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "jakarta.servlet.Filter",
        "org.springframework.web.servlet.config.annotation.WebMvcConfigurer"
    })
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = ONLINE_WRITES_PARAMETER_PROPERTY)
    static final class ServletOnlineWriteGateConfiguration {

        /**
         * Registers the gate ahead of every mapped handler.
         *
         * <p>Assumptions: added for all paths with no explicit exclusions, because the exemptions this
         * control needs are declared at the handlers that own them through
         * {@link com.carddemo.common.control.OnlineWriteGateExempt}. Excluding path patterns here as
         * well would put the same decision in two places and let them disagree.</p>
         *
         * @param gate the decision the interceptor applies, resolved from the context
         * @return the configurer that adds one shared interceptor instance to the MVC chain, never
         *     {@code null}
         */
        @Bean
        @ConditionalOnMissingBean(name = "carddemoOnlineWriteGateMvcConfigurer")
        public WebMvcConfigurer carddemoOnlineWriteGateMvcConfigurer(OnlineWriteGate gate) {
            OnlineWriteGateInterceptor interceptor = new OnlineWriteGateInterceptor(gate);
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor);
                }
            };
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

        /**
         * Registers the request-body bound for every request path, just behind the correlation filter.
         *
         * <p>Assumptions: the bound is read as a property with a default rather than being made
         * conditional on a deployment naming one, which is the opposite of the decision
         * {@link CardDemoCommonAutoConfiguration#carddemoCursorToken} records for its signing key. The
         * two are opposite for a reason: an absent signing key must leave the sealer unbuilt, because a
         * default key would be a secret committed to source, whereas an absent bound must NOT leave the
         * filter unregistered, because the unbounded state is precisely the exposure this filter exists
         * to close. A default is safe here in the way a default key is not -- a bound is not a secret,
         * and a wrong one fails visibly with a message naming it.</p>
         *
         * <p>Assumptions: every path, including the management endpoints, for the same reason the
         * correlation filter covers them. An actuator request body is smaller than any business one, so
         * the bound refuses none of them, and excluding those paths would leave one route family
         * unbounded for no gain.</p>
         *
         * @param maxBodyBytes the ceiling on one request's body in bytes, named by
         *     {@value #MAX_REQUEST_BODY_BYTES_PROPERTY} and defaulting to
         *     {@link RequestBodySizeFilter#DEFAULT_MAX_BODY_BYTES}; must be positive
         * @param clock the clock the filter's refusal body reads its failure instant from, resolved from
         *     the context for the same reason the correlation filter's is
         * @return the registration placing one shared {@link RequestBodySizeFilter} instance at
         *     {@link CardDemoCommonAutoConfiguration#BODY_SIZE_FILTER_ORDER} across all request paths,
         *     never {@code null}
         * @throws IllegalArgumentException if the configured bound is not positive, failing the context
         *     at assembly rather than the first request carrying a body
         */
        @Bean
        @ConditionalOnMissingBean(name = "carddemoRequestBodySizeFilterRegistration")
        public FilterRegistrationBean<RequestBodySizeFilter>
                carddemoRequestBodySizeFilterRegistration(
                @Value("${" + MAX_REQUEST_BODY_BYTES_PROPERTY + ":"
                        + RequestBodySizeFilter.DEFAULT_MAX_BODY_BYTES + "}") long maxBodyBytes,
                Clock clock) {
            FilterRegistrationBean<RequestBodySizeFilter> registration =
                    new FilterRegistrationBean<>(new RequestBodySizeFilter(maxBodyBytes, clock));
            registration.setOrder(BODY_SIZE_FILTER_ORDER);
            registration.addUrlPatterns("/*");
            registration.setName("carddemoRequestBodySizeFilter");
            return registration;
        }
    }

    /**
     * Contains request-error rendering only when the servlet, web and security contracts it handles
     * are present.
     *
     * <p>Assumptions: all four names are checked together because the advice's public handler
     * signatures use the servlet request, its type carries the web advice annotation, one handler
     * claims Spring Security's access-denial exception and one claims the dispatcher's own
     * absent-resource exception. Loading the advice with any one absent would
     * fail during handler introspection instead of cleanly omitting a bean. Online services declare
     * all four contracts; the batch task declares none.</p>
     *
     * <p>Refactoring Rationale: the fourth name was added with the arm that answers an unpublished path
     * as a 404 rather than as a server fault. It is the only name in this list that comes from the
     * dispatcher module rather than from the web module, and naming it is what keeps the omission clean:
     * a consumer holding the web contracts but no dispatcher -- which is how a service exposing no
     * request mapping would be assembled -- would otherwise fail while the framework introspected that
     * handler's declared exception types, rather than simply not receiving the bean. Its sibling
     * no-handler exception, claimed by the same arm, ships in that same module, so one name settles
     * both.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "jakarta.servlet.http.HttpServletRequest",
        "org.springframework.web.bind.annotation.RestControllerAdvice",
        "org.springframework.security.access.AccessDeniedException",
        "org.springframework.web.servlet.resource.NoResourceFoundException"
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

    /**
     * Registers the handler that answers a request the HTTP firewall refuses to route.
     *
     * <p>Assumptions: this is a SEPARATE configuration rather than a bean on
     * {@link ServletErrorConfiguration}, because the type it publishes lives in the security web module
     * while that configuration's conditions name the security core module. Conditioning the bean method
     * itself was rejected for the reason that configuration's own note gives: Spring decides eligibility
     * from class metadata before resolving a method's return type, so a condition on the method could not
     * protect a context in which the return type is absent, and the whole error configuration would fail
     * during introspection instead of cleanly omitting one bean.</p>
     *
     * <p>⚠️ Assumptions: a bean is the right mechanism here even though the sibling entry point and
     * access-denied handler must be set on the builder instead. The reason they are set explicitly is that
     * Spring Security does not resolve either from the context for a resource-server chain; this type is
     * different — the framework's own web-security configuration autowires a single
     * {@code RequestRejectedHandler} bean onto the filter-chain proxy — so publishing it once here gives
     * every service the same answer with nothing to remember per service. It is the only one of the three
     * for which a bean is effective, and the asymmetry is stated so a reader does not try to move the
     * other two here.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "jakarta.servlet.http.HttpServletRequest",
        "org.springframework.security.web.firewall.RequestRejectedHandler"
    })
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static final class ServletFirewallConfiguration {

        /**
         * Registers the shared firewall-rejection handler.
         *
         * <p>Assumptions: guarded on absence so a service needing a different refusal — a different status,
         * or a body shape its own contract publishes — replaces it by declaring its own bean rather than by
         * editing this one.</p>
         *
         * @param clock the clock the rendered problem shape reads its failure instant from, resolved from
         *     the context so a test can substitute a fixed reading
         * @return the handler bean, never {@code null}
         */
        @Bean
        @ConditionalOnMissingBean(RequestRejectedHandler.class)
        public RequestRejectedHandler carddemoRequestRejectedHandler(Clock clock) {
            return ApiErrorSecurityHandlers.requestRejectedHandler(clock);
        }
    }

    /**
     * Registers the customizer that makes the embedded container answer its own refusals in the
     * migrated problem shape.
     *
     * <p>Assumptions: this is a THIRD refusal-rendering configuration rather than a bean on either of
     * the two above, and the reason is the layer it reaches rather than a preference. The advice
     * answers a request the dispatcher accepted, the firewall handler answers one Spring Security
     * refused, and both of those are inside a web application; this one answers a request the
     * CONTAINER refused before any web application was selected, which no filter, error page or advice
     * is invoked for. Its conditions therefore name the container's own classes, which neither of the
     * other two configurations requires, and folding it into one of them would make that
     * configuration -- and the advice or the handler with it -- conditional on an embedded container
     * being present. A service deployed behind a different container would then silently lose its
     * request-error rendering.</p>
     *
     * <p>Assumptions: three names are checked together. The servlet request establishes that a servlet
     * runtime is present at all, the container's error-report valve is the type the published valve
     * extends, and the container's servlet factory is the type the published customizer is bound to.
     * Loading the customizer with either of the last two absent would fail while the framework
     * resolved its supertypes instead of cleanly omitting a bean. Online services declare all three
     * through the web starter; the batch task declares none.</p>
     *
     * <p>Trade-offs: naming the container's classes ties this one configuration to one container
     * implementation, where the rest of this file is implementation-neutral. That is unavoidable
     * rather than chosen -- the slot being occupied is that container's, and there is no portable
     * interface for it -- and it is confined to this configuration precisely so the neutrality of the
     * others is unaffected.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "jakarta.servlet.http.HttpServletRequest",
        "org.apache.catalina.valves.ErrorReportValve",
        "org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory"
    })
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static final class TomcatContainerRefusalConfiguration {

        /**
         * Registers the shared container-refusal renderer's installer.
         *
         * <p>Assumptions: guarded on absence so a service needing different container-level behaviour
         * replaces it by declaring its own bean rather than by editing this one, which is the same
         * escape hatch the advice and the firewall handler offer. No property gates it: a deployment
         * that had to name one to receive a parseable refusal would receive an unparseable one by
         * default, which is the exposure this bean closes.</p>
         *
         * @param clock the clock every emitted problem shape reads its failure instant from, resolved
         *     from the context so one request is never stamped from two clocks and a test can
         *     substitute a fixed reading
         * @return the customizer bean, never {@code null}
         */
        @Bean
        @ConditionalOnMissingBean(RejectedRequestErrorReportValveCustomizer.class)
        public RejectedRequestErrorReportValveCustomizer
                carddemoRejectedRequestErrorReportValveCustomizer(Clock clock) {
            return new RejectedRequestErrorReportValveCustomizer(clock);
        }
    }

    /**
     * Registers the migration owner-role callback only where a migration engine is present and a
     * service has named the role its schema is owned by.
     *
     * <p>Refactoring Rationale: the seven migrating services each carried
     * {@code spring.flyway.init-sqls: ["SET ROLE carddemo_<context>_owner;"]}, which maps to a Flyway
     * setting that engine deprecated in favour of a connect-time callback -- and which printed a
     * deprecation notice on every connection it opened, seventy of them in one reactor build. The
     * replacement is registered here rather than seven times because the statement is the same
     * decision in all seven, and the only thing that differs is the role name, which stays in each
     * service's own configuration where the rest of its database identity is.</p>
     *
     * <p>Assumptions: {@link ConditionalOnClass} names Flyway's callback interface as text so the
     * condition is evaluated from class-file metadata, which is what allows the reporting context --
     * the one bounded context that runs no migration and declares no Flyway at all -- to skip this
     * configuration rather than fail resolving the bean method's return type. It is the same reason
     * the servlet configurations above name their types as text.</p>
     *
     * <p>Assumptions: {@link ConditionalOnProperty} on the owner-role key is what keeps the
     * registration out of a context that names no role. Presence is the condition, not a particular
     * value, so a profile can deliberately opt out by overriding the key to an empty value -- see
     * {@link com.carddemo.common.config.FlywayOwnerRoleCallback} for why the empty case is a
     * documented opt-out rather than an error.</p>
     *
     * <p>Trade-offs: Spring Boot's Flyway auto-configuration collects {@code Callback} beans from the
     * context, so a bean is all this has to contribute and no service needs to reference it. The cost
     * is that the registration is invisible at each service's configuration -- what a reader sees
     * there is one property -- which is why that property's own comment in each
     * {@code application.yml} names this class.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.flywaydb.core.api.callback.Callback")
    @ConditionalOnProperty(name = FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY)
    static final class FlywayOwnerRoleConfiguration {

        /**
         * Registers the callback that makes Flyway migrate as the schema's owner.
         *
         * <p>Assumptions: guarded on absence of the concrete type so a service needing different
         * behaviour replaces it by declaring its own bean, which is the same escape hatch every other
         * shared bean in this file offers. The guard names the concrete class rather than
         * {@code Callback}, because a service may well declare an unrelated Flyway callback of its own
         * and that must not silently displace this one.</p>
         *
         * @param ownerRole the role Flyway assumes before creating objects, named by
         *     {@value com.carddemo.common.config.FlywayOwnerRoleCallback#OWNER_ROLE_PROPERTY}; an
         *     empty value registers an inert callback
         * @return the callback bean, never {@code null}
         * @throws IllegalArgumentException if a non-empty value is not a lower-case identifier, which
         *     fails the context at assembly rather than letting an unchecked name reach a privileged
         *     statement
         */
        @Bean
        @ConditionalOnMissingBean(FlywayOwnerRoleCallback.class)
        public FlywayOwnerRoleCallback carddemoFlywayOwnerRoleCallback(
                @Value("${" + FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + ":}") String ownerRole) {
            return new FlywayOwnerRoleCallback(ownerRole);
        }
    }

    /**
     * Registers the customizer that puts the owner role in force before Flyway takes hold of a
     * connection, wherever Spring Boot's own Flyway support is what builds the engine.
     *
     * <p>Refactoring Rationale: the callback registered above was the whole of this control until it
     * was measured. Flyway's PostgreSQL connection wrapper captures the session's role when it is
     * constructed and its schema-history writes restore that captured role, so a role the callback
     * assumes is reverted before {@code flyway_schema_history} is created -- the account context failed
     * with {@code SQLSTATE 42501} on exactly that. The deprecated {@code spring.flyway.init-sqls} this
     * work replaces did not have the problem because of WHERE it ran, on the raw JDBC connection
     * before the wrapper existed, and Boot's configuration customizer is the one supported hook that
     * reaches the same position. See {@link FlywayOwnerRoleDataSourceCustomizer} for the reading this
     * rests on.
     *
     * <p>Assumptions: this is a second nested configuration rather than a second bean method beside
     * the callback, because the two conditions genuinely differ. The callback's contract belongs to the
     * migration engine and is satisfied by {@code flyway-core} alone; this customizer's contract
     * belongs to Spring Boot's Flyway module, and a bean method whose return type implements a missing
     * interface is resolved while the enclosing configuration is parsed, so the condition has to guard
     * the class rather than the method.
     *
     * <p>Trade-offs: {@link ConditionalOnProperty} is restated here, duplicating one line of the
     * configuration above. Accepted because the alternative -- one configuration guarded by the union
     * of both class conditions -- would silently stop registering the CALLBACK in a context that has
     * the engine but not Boot's Flyway module, widening the blast radius of a condition that exists
     * only to protect the customizer.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.flywaydb.core.api.configuration.FluentConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer"
            })
    @ConditionalOnProperty(name = FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY)
    static final class FlywayOwnerRoleDataSourceConfiguration {

        /**
         * Registers the customizer that makes Flyway borrow connections which have already assumed the
         * schema's owning role.
         *
         * <p>Assumptions: guarded on absence of the concrete type, the same escape hatch every other
         * shared bean in this file offers, and named concretely rather than by the Boot interface so a
         * service declaring an unrelated Flyway customizer of its own does not displace this one.
         *
         * @param ownerRole the role Flyway assumes before creating objects, named by
         *     {@value com.carddemo.common.config.FlywayOwnerRoleCallback#OWNER_ROLE_PROPERTY}; an empty
         *     value registers an inert customizer that leaves the configured DataSource alone
         * @param applicationDataSources the context's DataSource beans, which the customizer compares
         *     by instance so that it can refuse a configuration in which Flyway would elevate
         *     connections belonging to the pool the application serves requests from
         * @return the customizer bean, never {@code null}
         * @throws IllegalArgumentException if a non-empty value is not a lower-case identifier, which
         *     fails the context at assembly rather than letting an unchecked name reach a privileged
         *     statement
         */
        @Bean
        @ConditionalOnMissingBean(FlywayOwnerRoleDataSourceCustomizer.class)
        public FlywayOwnerRoleDataSourceCustomizer carddemoFlywayOwnerRoleDataSourceCustomizer(
                @Value("${" + FlywayOwnerRoleCallback.OWNER_ROLE_PROPERTY + ":}") String ownerRole,
                ObjectProvider<DataSource> applicationDataSources) {
            return new FlywayOwnerRoleDataSourceCustomizer(ownerRole, applicationDataSources);
        }
    }
}
