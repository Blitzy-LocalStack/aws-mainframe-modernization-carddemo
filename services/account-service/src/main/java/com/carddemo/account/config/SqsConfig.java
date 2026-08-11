package com.carddemo.account.config;

import com.carddemo.common.messaging.QueueClientBudget;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.SqsContainerOptionsBuilder;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the queue integration for the account-inquiry request and reply flow.
 *
 * <p>This is the migrated form of the connection and polling setup the baseline performs in
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl}, a 620-line queue-triggered program that opens three
 * queues once and then drains its request queue until it is empty. That program is REFERENCE-only
 * and is never modified; the framing throughout this file is deliberately "the baseline does X, this
 * context does Y, and the divergence is recorded" rather than any suggestion of repair.</p>
 *
 * <h2>What a listener in this context may rely on</h2>
 *
 * <p>The inquiry consumer binds to the starter's own listener-container factory, registered under the
 * name {@link io.awspring.cloud.sqs.config.EndpointRegistrar#DEFAULT_LISTENER_CONTAINER_FACTORY_BEAN_NAME},
 * so an {@code @SqsListener} method needs no {@code factory} attribute at all. Three properties of
 * that factory hold in the assembled context and a listener may rely on all three: a five-second long
 * poll, delete-on-success acknowledgement under the queue's visibility period, and a bounded drain
 * when the container stops.</p>
 *
 * <p>Assumptions: this class contributes the second and third of those directly and leaves the first
 * to the property that owns it, which the declined-settings note on the options method records in
 * full. The distinction matters to anyone changing either one: a value this class does not set cannot
 * be corrected by editing this class. A fourth guarantee is given by omission rather than by setting,
 * and it is the most important sentence in the file: <b>no transactional outbox is involved in this
 * flow.</b></p>
 *
 * <h2>Assumptions: there is no transactional outbox here, and the baseline settles it</h2>
 *
 * <p>Assumptions: the inquiry flow has no lost-reply window, so nothing exists for an outbox to
 * close. In {@code COACCT01.cbl} the receive is set up under syncpoint at L347, where the get options
 * are computed as the syncpoint option, and the get itself is issued at L352; the reply is set up the
 * same way at L475 and the put is issued at L479. The account read sits inside that same window, at
 * L396 through L401. A search of the file for a no-syncpoint option returns nothing at all. Receive,
 * business work and reply are therefore one atomic unit of work, opened per drain iteration by the
 * {@code EXEC CICS SYNCPOINT} at L327.</p>
 *
 * <p>Assumptions: the contrast with the authorization context is what makes this worth stating twice
 * -- once in this package's charter and once here, beside the settings that depend on it. That
 * context's consumer, {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, computes its get
 * options with the <b>no</b>-syncpoint option combined with a wait at L389 and its put options with
 * the no-syncpoint option at L753, publishing through the single-shot put at L758. Its reply is
 * consequently published outside its commit, so a crash between the two loses a reply the data says
 * was produced, and <b>that</b> context does need an outbox. The two rulings are opposite and the
 * same word appears in both option names, which is precisely how a reader skimming for "syncpoint"
 * inverts the pair. Adding an outbox here would not be harmlessly redundant either: it would publish
 * the reply from a second, later transaction and so introduce an observable intermediate state the
 * baseline's single unit of work does not have.</p>
 *
 * <h2>Assumptions: the payload is fixed-width, and the media type does not describe it</h2>
 *
 * <p>Assumptions: this flow's wire format is fixed-width text, not delimited text. The request is
 * declared at L109 through L112 as a four-character function, an eleven-digit key and a
 * 985-character filler, which is exactly 1000 bytes, and the function discriminator is the literal
 * tested at L393. The success reply is assembled at L130 through L169 from eleven label-and-value
 * pairs -- 140 bytes of labels and 112 bytes of values, so 252 significant bytes -- moved into the
 * reply area at L426 and then into a buffer whose length is set to 1000 at L468. Because the message
 * format is asserted to be character data at L471, byte offsets survive the transport intact, which
 * is what makes field order and offsets the contract rather than a rendering detail.</p>
 *
 * <p>Assumptions: the {@code contentType} attribute this flow carries is inherited from the shared
 * messaging contract that governs the whole inquiry queue family, and it is <b>not</b> a description
 * of the payload's internal shape. Conflating the two would send a future implementer to a
 * delimited-text codec for a fixed-width record. Conversion belongs to the shared kernel and to the
 * consumer that performs it -- {@code com.carddemo.common.codec.InquiryRequestCodec} carries this
 * flow's 1000-byte layout, with {@code com.carddemo.common.codec.FixedWidthCodec} and
 * {@code com.carddemo.common.codec.CopybookLayout} underneath it for record-level work. This class
 * declares no codec and registers no message converter, so there is exactly one owner of the layout.
 * {@code com.carddemo.common.codec.CsvAuthCodec} belongs to the authorization request and reply pair
 * and has no part in this flow.</p>
 *
 * <h2>Refactoring Rationale: connection bookkeeping moves to the container</h2>
 *
 * <p>Refactoring Rationale: the baseline hand-rolls its own connection lifecycle and the bookkeeping
 * does not line up with the queues it tracks. It opens three queues -- the error queue first at L187,
 * before the trigger is even retrieved, then the input queue at L212 and the reply queue at L213,
 * with the open paragraphs themselves at L222, L255 and L289 -- and closes them in three paragraphs
 * at L552, L574 and L597. Four status flags are declared for those three queues, at L13 to L14, L16
 * to L17, L19 to L20 and L22 to L23, and the wiring crosses: the input-queue open sets the
 * reply-queue flag at L245 while the reply-queue open sets the response flag at L279. What was wrong
 * with the old arrangement is not the crossing itself but that the crossing is invisible, because
 * both flags are set on success paths that always run together. Here the container owns connection
 * lifecycle and this class contributes only settings, so there is no per-queue flag to keep in step
 * with anything.</p>
 *
 * <h2>Refactoring Rationale: every destination arrives from configuration</h2>
 *
 * <p>Refactoring Rationale: all three queue references this flow uses arrive from configuration and
 * none is written in Java, and that is an improvement on the baseline rather than a port of it. The
 * baseline declares its four queue-name fields at L92 through L96, every one initialised to spaces,
 * which establishes that it already treats a queue name as configuration; but only ONE of them is
 * actually injected -- the triggering queue name retrieved at L191 to L192 and moved at L197 --
 * while the reply destination is written as a literal at L198 and the error destination as a literal
 * at L294. Externalising all three is therefore a deliberate divergence, and it is what lets one
 * image serve every environment. No queue name, queue address, region or account identifier appears
 * anywhere in this class.</p>
 *
 * <h2>Assumptions: the handler is stateless, one message at a time</h2>
 *
 * <p>Assumptions: nothing in this flow retains state between messages, and the baseline says so in
 * its own header -- the program is declared at L2 with the clause that resets its working storage on
 * every invocation. A per-message handler on a container-managed thread is the natural analogue of
 * that, and it is why this class configures no session, no cursor and no accumulator: only polling,
 * acknowledgement and drain. It is also why the acknowledgement settings below are per-message
 * rather than batched, since a batch would be the one piece of cross-message state this flow does
 * not otherwise have.</p>
 *
 * <h2>What this class deliberately does not own</h2>
 *
 * <p>Assumptions: this class holds no security, datasource or contract-metadata concern -- each has
 * its own owner in this package, enumerated in the charter at {@code package-info.java} -- and it
 * holds no batch concern at all. Chunk-oriented jobs belong to {@code batch-service}: the batch
 * starter is version-managed centrally but is deliberately not declared by this module, which
 * {@code services/account-service/pom.xml} records in prose at L682, so the framework types such a
 * class would reference are absent from the compile classpath and the omission is enforced by the
 * compiler rather than by agreement. The shared kernel's correlation filter is likewise not
 * registered here; it is an HTTP concern and its identifier is the same round-trip token this flow
 * carries on a message, which is the only reason the two are worth mentioning together.</p>
 *
 * @see QueueClientBudget
 */
@Configuration(proxyBeanMethods = false)
public class SqsConfig {

    /**
     * The attribute selector that asks for every message attribute a producer sent.
     */
    private static final String ALL_MESSAGE_ATTRIBUTES = "All";

    /**
     * Property carrying how long a stopping container waits for in-flight messages to finish.
     */
    public static final String PROPERTY_LISTENER_SHUTDOWN_TIMEOUT =
            "carddemo.messaging.listener-shutdown-timeout-seconds";

    /**
     * Property carrying how long a stopping container waits for outstanding acknowledgements.
     */
    public static final String PROPERTY_ACKNOWLEDGEMENT_SHUTDOWN_TIMEOUT =
            "carddemo.messaging.acknowledgement-shutdown-timeout-seconds";

    /**
     * Property carrying the shutdown phase both drain budgets are spent inside.
     */
    public static final String PROPERTY_SHUTDOWN_PHASE_TIMEOUT =
            "spring.lifecycle.timeout-per-shutdown-phase";

    /**
     * Logger for the one startup line that records which settings were applied.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SqsConfig.class);

    /**
     * Supplies the synchronous queue client the inquiry consumer publishes replies and diagnostics with.
     *
     * <p>Refactoring Rationale: the client is given a whole-call bound and a per-attempt bound, and it
     * had neither. The software development kit's default for both is no bound at all, so a stalled
     * publish retried indefinitely -- and this publish happens INSIDE the message handler, before the
     * listener returns, so an unbounded call is an unbounded handler. A handler that outlives its
     * message's visibility period does not merely run late: the queue makes the request visible again,
     * a second consumer takes it, and two handlers act on one request at once. {@link QueueClientBudget}
     * is what refuses that arrangement at startup, by requiring the whole-call bound to be strictly
     * shorter than the visibility period the queue is provisioned with.</p>
     *
     * <p>Assumptions: the visibility period is a PROPERTY here rather than a value read from the queue.
     * It is set by {@code infra/modules/sqs}, whose {@code visibility_timeout_seconds} defaults to 60,
     * and the default below is that same 60 so an unconfigured context validates against what the
     * infrastructure actually provisions. Reading it from the queue at startup was the alternative and
     * is rejected: it would make context refresh depend on a reachable queue, and it would silently
     * pass in every test and local run where no queue exists.</p>
     *
     * <p>Trade-offs: the bounds are applied through the CONSUMER form of {@code overrideConfiguration},
     * which mutates the configuration the starter's configurer already built. The value form would
     * replace it, discarding the retry policy, the user agent and any execution interceptor the starter
     * had installed -- a loss that shows up only as absent telemetry and absent retries, neither of
     * which fails a test.</p>
     *
     * <p>Assumptions: what remains of one message's handling after this call -- the database work -- is
     * bounded by the connection pool and driver settings this service configures, not here, so it is
     * not added to the sum below. The sum this class can verify is the queue call against visibility,
     * and overstating what it verifies would be worse than stating the part it owns.</p>
     *
     * <p>Assumptions: the builder is handed to the starter's own configurer rather than being
     * configured here, so region, credentials and any endpoint override resolve exactly as they do for
     * the auto-configured client. Setting them here would create a second place the two clients could
     * disagree about which account and region they address, and a publisher pointed at a different
     * endpoint from the consumer fails only at run time, only on the reply path.</p>
     *
     * <p>Assumptions: one client serves two destinations that mean different things, which is why the
     * consumer needs a client at all rather than a reply-only helper. A request the baseline cannot
     * satisfy but can still answer DOES get a reply: a missing account takes the not-found branch at
     * L428, builds its sentence at L429 and publishes it through the reply path at L435, and a
     * malformed function or a zero key takes the other branch and publishes at L456. A request it
     * cannot answer at all does NOT get a reply: a hard read failure takes the branch at L437, sets
     * its message at L442, and goes to the error paragraph at L444 and then to termination at L445.
     * The migrated flow keeps that split exactly, so a diagnostic goes to the dedicated error
     * destination and never to the reply destination, where a requester would parse it as an answer.</p>
     *
     * <p>Alternatives Considered: annotating the publish with the framework's core retry support --
     * which this platform activates with {@code @EnableResilientMethods} and bounds with the
     * {@code maxRetries} attribute, not with the older enabling annotation or the older attribute name
     * -- or putting a circuit breaker in front of it. Neither is adopted and no resilience library is
     * added. The durable retry tier for this flow is the transport itself: an unacknowledged request
     * reappears after its visibility period and the queue's redrive policy parks it at five receives,
     * so an in-handler retry would spend the very visibility budget the bounds above are sized against,
     * and a further attempt inside a handler that is already the retry is not a second chance but a
     * longer first one. A breaker is omitted because the only call this client makes is to a regional
     * queue endpoint reached through an interface endpoint inside the private network, so it would add
     * a failure mode -- an open breaker refusing a publish the transport would have accepted -- without
     * removing one.</p>
     *
     * @param configurer the starter's client-builder configurer; must not be {@code null}
     * @param apiCallTimeoutMillis the whole-call bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_TIMEOUT}; must be positive and shorter than the
     *     visibility period
     * @param apiCallAttemptTimeoutMillis the per-attempt bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_ATTEMPT_TIMEOUT}; must be positive and must not
     *     exceed the whole-call bound
     * @param visibilityTimeoutSeconds how long a received message stays invisible to other consumers,
     *     from {@link QueueClientBudget#PROPERTY_VISIBILITY_TIMEOUT}; must be positive
     * @return the queue client, never {@code null}
     * @throws IllegalStateException if the three bounds do not satisfy {@link QueueClientBudget}, so the
     *     failure arrives at startup naming the relationship that does not hold
     */
    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(AwsClientBuilderConfigurer configurer,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_TIMEOUT + ":10000}")
            long apiCallTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT + ":5000}")
            long apiCallAttemptTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_VISIBILITY_TIMEOUT + ":60}")
            long visibilityTimeoutSeconds) {

        // WHY : Assumptions: the budget is built BEFORE the configurer is touched, so a context whose
        //   bounds do not hold fails without having constructed a client at all. The ordering is
        //   asserted by a case in this module's test scope that verifies no interaction with the
        //   configurer on refusal, which is the only way to tell a validating method apart from one
        //   that validates and then builds anyway.
        QueueClientBudget budget = new QueueClientBudget(
                Duration.ofMillis(apiCallTimeoutMillis),
                Duration.ofMillis(apiCallAttemptTimeoutMillis),
                Duration.ofSeconds(visibilityTimeoutSeconds));

        // WHY : Trade-offs: a SYNCHRONOUS client is supplied here while the starter auto-configures an
        //   asynchronous one for the listener container. The reply is sent from inside the handler and
        //   the handler must not return until the send has succeeded -- returning earlier would let the
        //   container delete a request that had not been answered -- so an asynchronous call would have
        //   to be blocked on immediately in any case. Blocking explicitly is the same wait without the
        //   ambiguity about which thread the continuation runs on, and that ambiguity matters because
        //   the surrounding transaction's persistence context is not safe to touch from another thread.
        return configurer.configure(SqsClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(budget.apiCallTimeout())
                        .apiCallAttemptTimeout(budget.apiCallAttemptTimeout()))
                .build();
    }

    /**
     * Applies this context's acknowledgement and drain posture to the listener-container factory.
     *
     * <p>Alternatives Considered: declaring a replacement {@code SqsMessageListenerContainerFactory}
     * bean under the starter's default name. The starter's own factory method is conditional on no such
     * bean existing, so a replacement would be honoured -- and it would also silently discard every
     * collaborator that method wires from the context: the error handlers, the message interceptors, the
     * observation registry and convention, the acknowledgement-result callbacks and the message
     * converter. Post-processing the factory the starter built keeps all of them and still leaves this
     * class the last writer for the options it names, because the factory composes its configuration
     * consumers in order and a later one wins for the same option. It also keeps the context holding
     * exactly ONE factory bean, which a case in this module's test scope asserts.</p>
     *
     * <p>Alternatives Considered: a custom factory bean name that each listener then references by
     * string. Rejected because a string that has to agree in two files is a silent-drift hazard -- a
     * listener naming a factory that does not exist fails at startup, but a listener that omits the
     * attribute after a rename binds to the starter's untuned default and consumes with the wrong
     * posture. Tuning the default-named factory cannot be forgotten by a listener, because there is no
     * attribute to forget.</p>
     *
     * <p>Assumptions: the method is static so the returned post-processor is created before the ordinary
     * singletons it must observe, without forcing early instantiation of the enclosing configuration
     * class.</p>
     *
     * <p>Refactoring Rationale: the two drain budgets are VALIDATED rather than merely converted,
     * because the framework's own defaults do not fit the phase this service declares. Each defaults to
     * twenty seconds and a stopping container spends them in sequence, so the pair defaults to forty
     * seconds inside a shutdown phase this module's {@code application.yml} sets to twenty. Left alone,
     * a container draining a message is terminated mid-transaction by the very timeout that exists to
     * let it finish, and the rollback is then attributed to redelivery rather than to shutdown. Neither
     * budget is reachable through the starter's property namespace or through the listener annotation,
     * so a factory-level setting is the only place either can be expressed.</p>
     *
     * @param listenerShutdownSeconds how long a stopping container waits for in-flight messages to
     *     finish, from {@link #PROPERTY_LISTENER_SHUTDOWN_TIMEOUT}; must be positive
     * @param acknowledgementShutdownSeconds how long a stopping container waits for outstanding
     *     acknowledgements to be sent, from {@link #PROPERTY_ACKNOWLEDGEMENT_SHUTDOWN_TIMEOUT}; must be
     *     positive
     * @param shutdownPhaseTimeout the phase both budgets are spent inside, from
     *     {@link #PROPERTY_SHUTDOWN_PHASE_TIMEOUT}; must be positive
     * @return the post-processor that applies these settings to the listener-container factory, never
     *     {@code null}
     * @throws IllegalStateException if either budget is not positive, or if their sum exceeds the
     *     shutdown phase they are spent inside, so the failure names the property rather than presenting
     *     as work abandoned during a deployment
     */
    @Bean
    public static BeanPostProcessor accountInquiryListenerContainerOptions(
            @Value("${" + PROPERTY_LISTENER_SHUTDOWN_TIMEOUT + ":10}") long listenerShutdownSeconds,
            @Value("${" + PROPERTY_ACKNOWLEDGEMENT_SHUTDOWN_TIMEOUT + ":5}")
            long acknowledgementShutdownSeconds,
            @Value("${" + PROPERTY_SHUTDOWN_PHASE_TIMEOUT + ":30s}") Duration shutdownPhaseTimeout) {

        // WHY : Assumptions: the phase budget is READ from configuration rather than restated here,
        //   with the framework's own thirty-second default as its default, so lowering it in
        //   application.yml fails this check instead of silently leaving a drain that cannot finish.
        return new InquiryListenerContainerOptionsCustomizer(
                Duration.ofSeconds(listenerShutdownSeconds),
                Duration.ofSeconds(acknowledgementShutdownSeconds),
                shutdownPhaseTimeout);
    }

    /**
     * Applies the container settings that carry a baseline semantic nothing else in this module can.
     *
     * <p>Assumptions: this exists as a named type rather than a lambda because it holds validated state
     * and because a post-processor is easier to reason about when the thing it does to a bean has a
     * name. It is private and static: nothing outside this class configures the container, and it must
     * not capture the enclosing configuration instance.</p>
     */
    private static final class InquiryListenerContainerOptionsCustomizer implements BeanPostProcessor {

        /**
         * How long a stopping container waits for in-flight messages to finish.
         */
        private final Duration listenerShutdownTimeout;

        /**
         * How long a stopping container waits for outstanding acknowledgements to be sent.
         */
        private final Duration acknowledgementShutdownTimeout;

        /**
         * Retains the two drain budgets after checking they can actually be spent.
         *
         * <p>Assumptions: both budgets are checked here rather than where they are applied, because the
         * application happens inside a bean-post-processing callback where a failure is reported against
         * whichever bean was being initialised. Checking at construction makes the failure name the
         * property instead.</p>
         *
         * @param listenerShutdownTimeout how long a stopping container waits for in-flight messages to
         *     finish; must be positive
         * @param acknowledgementShutdownTimeout how long a stopping container waits for outstanding
         *     acknowledgements to be sent; must be positive
         * @param shutdownPhaseTimeout the phase both budgets are spent inside; must be positive and must
         *     be at least the sum of the two
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalStateException if either budget is not positive, or if their sum exceeds the
         *     shutdown phase they are spent inside
         */
        InquiryListenerContainerOptionsCustomizer(Duration listenerShutdownTimeout,
                Duration acknowledgementShutdownTimeout, Duration shutdownPhaseTimeout) {

            this.listenerShutdownTimeout =
                    Objects.requireNonNull(listenerShutdownTimeout, "listenerShutdownTimeout");
            this.acknowledgementShutdownTimeout = Objects.requireNonNull(
                    acknowledgementShutdownTimeout, "acknowledgementShutdownTimeout");
            Objects.requireNonNull(shutdownPhaseTimeout, "shutdownPhaseTimeout");

            // WHY : Refactoring Rationale: a budget of zero or less is refused rather than accepted as
            //   "no wait". A container that waits no time for its in-flight messages abandons every one
            //   of them, and the acknowledgement it never sent means the queue redelivers an inquiry
            //   whose database work had already committed -- the requester then receives a second reply
            //   for one request, and nothing in a log distinguishes that from ordinary redelivery.
            requirePositiveBudget(listenerShutdownTimeout, PROPERTY_LISTENER_SHUTDOWN_TIMEOUT);
            requirePositiveBudget(acknowledgementShutdownTimeout,
                    PROPERTY_ACKNOWLEDGEMENT_SHUTDOWN_TIMEOUT);
            requirePositiveBudget(shutdownPhaseTimeout, PROPERTY_SHUTDOWN_PHASE_TIMEOUT);

            // WHY : Assumptions: the two budgets are spent in SEQUENCE by a stopping container, so it is
            //   their sum and not either one that has to fit inside the phase. Comparing them
            //   individually would pass a pair of budgets that each fit and together do not, which is
            //   exactly the shape the framework's own twenty-and-twenty defaults take against the
            //   twenty-second phase this module declares.
            Duration drain = listenerShutdownTimeout.plus(acknowledgementShutdownTimeout);
            if (drain.compareTo(shutdownPhaseTimeout) > 0) {
                throw new IllegalStateException("the listener drain budget " + drain
                        + " exceeds the shutdown phase " + shutdownPhaseTimeout
                        + " it is spent inside, so a draining container would be terminated mid-message;"
                        + " lower " + PROPERTY_LISTENER_SHUTDOWN_TIMEOUT + " and "
                        + PROPERTY_ACKNOWLEDGEMENT_SHUTDOWN_TIMEOUT + " or raise "
                        + PROPERTY_SHUTDOWN_PHASE_TIMEOUT);
            }
        }

        /**
         * Configures the listener-container factory as it is initialised, leaving other beans untouched.
         *
         * <p>Assumptions: the test is on the bean's TYPE rather than on its name, because the name the
         * starter registers its factory under is an implementation detail of the starter while the type
         * is the contract a listener binds through. Any other bean is returned exactly as received.</p>
         *
         * @param bean the bean the container has just initialised; must not be {@code null}
         * @param beanName the name the bean is registered under, used only in the startup log line
         *     because the selection above is by type; must not be {@code null}
         * @return the same bean instance, configured when it is a listener-container factory, never
         *     {@code null}
         */
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof SqsMessageListenerContainerFactory<?> factory) {
                factory.configure(this::apply);
                LOG.info("event=account.inquiry.listener.options.applied factory={}"
                                + " listenerShutdown={} acknowledgementShutdown={}",
                        beanName, this.listenerShutdownTimeout, this.acknowledgementShutdownTimeout);
            }
            return bean;
        }

        /**
         * Sets the options this context owns and leaves the ones another owner already declares.
         *
         * @param options the shared options builder of the factory being configured; must not be
         *     {@code null}
         */
        private void apply(SqsContainerOptionsBuilder options) {
            // WHY : Assumptions: a message is acknowledged only where its handler returned normally, so
            //   a handler that threw leaves the request to reappear after its visibility period and the
            //   queue's own redrive policy is the single authority on how many attempts it gets. This is
            //   the target form of the baseline's atomic receive-work-reply window -- COACCT01.cbl L347
            //   for the receive and L475 for the reply -- where a failure before the commit left the
            //   request on the queue. Alternatives Considered: acknowledging unconditionally, which
            //   would delete a request whose transaction had rolled back; the requester would then wait
            //   for a reply that no committed row exists to produce, and the request would be gone. The
            //   framework's current default is this same mode, so this is a pin rather than a change,
            //   and it is pinned because the mode is reachable neither through the starter's property
            //   namespace nor through any setting this module declares -- an upstream default change
            //   would otherwise alter the delivery contract with nothing in this repository recording it.
            options.acknowledgementMode(AcknowledgementMode.ON_SUCCESS);

            // WHY : Assumptions: each request is acknowledged on its own and immediately, never as part
            //   of a batch spanning several messages. The baseline commits once per drain iteration --
            //   the EXEC CICS SYNCPOINT at COACCT01.cbl L327, with the next receive performed at L330 --
            //   and the per-message handler is the migrated form of that commit, so an acknowledgement
            //   covering several messages would span transactions that committed independently and
            //   could delete a request whose own transaction had rolled back. Alternatives Considered:
            //   leaving both unset. Rejected because the starter then selects a batching policy from
            //   the queue's kind, and these are standard queues, so the immediacy this contract needs
            //   would rest on that selection rather than on anything written down.
            options.acknowledgementInterval(Duration.ZERO);
            options.acknowledgementThreshold(0);

            // WHY : Assumptions: every message attribute is requested, so the five this flow depends
            //   on -- the content type, the correlation identifier, the originating message
            //   identifier, the requested reply destination and the expiry instant -- all arrive
            //   whatever else a producer sends. Pinning it matters most for the expiry instant,
            //   because that deadline is enforced by the consumer alone: an attribute that failed to
            //   arrive would not surface as an error, it would surface as an expired inquiry being
            //   answered.
            // WHY : Alternatives Considered: naming exactly those five. Rejected because a sixth
            //   attribute a producer added would then be dropped with no diagnostic, and the five
            //   names are already owned by the consumer that reads them, so repeating them here would
            //   put a second owner on the attribute contract.
            // WHY : Assumptions: the correlation identifier is a round-trip TOKEN and not a selector.
            //   The baseline clears both identifier fields before its receive, at COACCT01.cbl L343
            //   and L344, so the receive matches whatever is waiting rather than filtering; it then
            //   saves what arrived into its own areas, declared at L55 and L56 as twenty-four
            //   characters each, and echoes BOTH of them back onto the reply at L469 and L470. The
            //   migrated flow therefore carries the correlation identifier end to end and echoes it
            //   verbatim rather than minting one. The authorization consumer echoes the correlation
            //   identifier but mints a fresh message identifier, which is one more place the two
            //   flows differ and one more reason not to generalise from that one to this.
            // WHY : Trade-offs: the requested reply destination is asked for as an attribute but is
            //   treated as INFORMATIONAL, because the reply destination is static. The baseline
            //   captures the inbound reply-to field exactly once, at COACCT01.cbl L366, and stores it
            //   at L371 into an area declared at L57 that is then never read anywhere in the program;
            //   its reply put at L479 targets the statically opened reply-queue handle passed at L480
            //   instead. Dynamic reply-to routing is the authorization flow's single-shot put, not
            //   this one, so the reply destination here is a configured value and an inbound request
            //   cannot redirect a reply by asserting one. The compromise accepted is that a genuinely
            //   new consumer wanting its own reply destination needs a deliberate, separately
            //   documented change rather than just an attribute.
            options.messageAttributeNames(List.of(ALL_MESSAGE_ATTRIBUTES));

            // WHY : Assumptions: a stopping container drains rather than being cut off, which is the
            //   target form of the baseline declining new work once its region has begun to quiesce --
            //   the fail-if-quiescing option appears on every option word it computes, at COACCT01.cbl
            //   L348 for the receive, L477 for the reply and L514 for the error put, and on all three
            //   opens at L231, L265 and L300. Trade-offs: the two budgets are the only settings in this
            //   method that a deployment can tune, and they are bounded by the constructor above so
            //   their sum fits the shutdown phase; the acknowledgement budget is the smaller of the two
            //   because acknowledgement here is immediate, so what it waits for is one request in flight
            //   rather than a buffer.
            options.listenerShutdownTimeout(this.listenerShutdownTimeout);
            options.acknowledgementShutdownTimeout(this.acknowledgementShutdownTimeout);

            // WHY : Assumptions: four settings a reader might expect here are deliberately absent
            //   because another owner already declares each, and declaring one again would make which
            //   value wins depend on bean ordering rather than on anything written down. The poll wait
            //   is spring.cloud.aws.sqs.listener.poll-timeout, set to five seconds and transcribed from
            //   COACCT01.cbl L337, which moves 5000 into the wait-interval field -- a value in
            //   MILLISECONDS, as that program's own preceding comment states, so the target quantity is
            //   five seconds and never five thousand. The batch size and the concurrency are
            //   max-messages-per-poll and max-concurrent-messages in the same namespace, and both are
            //   target-side choices with no baseline counterpart: the baseline's drain loop is bounded
            //   only by an empty receive -- primed at L214, repeated at L215 to L216 and closed at L218,
            //   exiting where the no-message-available reason sets its flag at L377 to L378 -- and it
            //   carries no message limit whatsoever. The behaviour for a reference that resolves to
            //   nothing is spring.cloud.aws.sqs.queue-not-found-strategy, declared as failure because
            //   the starter would otherwise CREATE the missing queue, and what it creates has neither
            //   the dead-letter redrive at five receives nor the encryption that infra/modules/sqs
            //   provisions.
            //
            // WHY : Alternatives Considered: setting acknowledgement ordering by message group, as the
            //   authorization context does. Rejected, and the rejection is mechanical rather than
            //   stylistic: group-ordered acknowledgement makes the starter refuse to build an unordered
            //   message source, so setting it here would fail container start outright. These three
            //   destinations are STANDARD queues -- the baseline opens three of them, at L222, L255 and
            //   L289, the third being the dedicated error destination -- and inquiry carries no ordering
            //   requirement, because each inquiry is independent and its reply is paired by correlation
            //   rather than by position. Ordering belongs to the per-card authorization flow, which
            //   groups by card number and deduplicates by transaction identifier; imposing it here would
            //   serialise independent inquiries to no purpose.
        }

        /**
         * Refuses a drain budget that cannot be spent.
         *
         * @param budget the duration to check; must not be {@code null}
         * @param propertyName the property the value came from, named in the failure so the operator
         *     knows what to change; must not be {@code null}
         * @throws IllegalStateException if the budget is zero or negative
         */
        private static void requirePositiveBudget(Duration budget, String propertyName) {
            if (budget.isZero() || budget.isNegative()) {
                throw new IllegalStateException("the duration configured by " + propertyName + " is "
                        + budget + ", but it must be positive; a non-positive budget abandons work"
                        + " instead of waiting for it");
            }
        }
    }
}
