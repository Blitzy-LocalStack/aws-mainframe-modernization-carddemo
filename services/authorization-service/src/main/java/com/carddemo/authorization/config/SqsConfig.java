package com.carddemo.authorization.config;

import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.QueueClientBudget;
import com.carddemo.common.observability.MetricsConfig;
import com.carddemo.common.web.CorrelationIdFilter;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.listener.SqsContainerOptionsBuilder;
import io.awspring.cloud.sqs.listener.acknowledgement.AcknowledgementOrdering;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the queue transport this context receives authorization requests on and publishes replies to.
 *
 * <p>This is the migrated form of the queue setup the baseline performs in
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}. Two queues belong here and no others:
 * the request queue that replaces {@code AWS.M2.CARDDEMO.PAUTH.REQUEST} and the reply queue that
 * replaces {@code AWS.M2.CARDDEMO.PAUTH.REPLY}, each named for its environment and each paired with
 * its own dead-letter queue. The inquiry pair and the terminal error sink the two sibling extensions
 * use are configured in the contexts that own their data, not here.</p>
 *
 * <p>Assumptions: this class declares no infrastructure. The queues, their dead-letter queues and
 * their encryption keys are provisioned by {@code infra/modules/sqs}, and no queue address, resource
 * name, account identifier, endpoint or region appears in this file. Every queue reference reaches
 * the running service through the {@code carddemo.messaging} properties, whose values originate as
 * Terraform outputs surfaced through the deployment's parameter store.</p>
 *
 * <h2>Ordering and duplicate suppression</h2>
 *
 * <p>Alternatives Considered: a standard queue for the request and reply pair, which costs less per
 * message and imposes no per-group throughput ceiling. It is rejected on two concrete consequences.
 * A standard queue offers no ordering guarantee, so two authorizations for one card could be decided
 * against the same summary row in the reverse of the order they were sent, and the available credit
 * the second one sees would not be the credit the baseline's strictly serial consumer would have
 * shown it -- the baseline reads one message, decides it, commits it and only then reads the next,
 * at {@code COPAUA0C.cbl} L326 through L342, so per-card ordering is a behavioural property of the
 * reference system rather than an implementation detail of its transport. A standard queue also
 * offers no duplicate suppression, so a redelivered request would be decided twice and two replies
 * would be sent for one transaction identifier. The first-in-first-out queue supplies both: the
 * message group is one group per card, which keeps requests for one card ordered while requests for
 * different cards stay parallel, and the deduplication identifier is the transaction identifier.</p>
 *
 * <p>Assumptions: both identities are the LITERAL values the specification freezes -- the group
 * identity is {@code card_num} and the deduplication identity is {@code transaction_id}, at
 * &sect;0.4.1.8 and again at &sect;0.7.6. Neither is derived. Refactoring Rationale: an earlier
 * revision derived both through a keyed tokeniser so that no primary account number reached message
 * metadata, and the derivation is withdrawn because it broke the guarantees it sat on top of. A group
 * identity is only an ordering guarantee if it is EQUAL for equal cards across every producer on the
 * queue, and a deduplication identity is only a suppression guarantee if the REQUESTER that may resend
 * can predict it; a value keyed from this service's own secret is neither, so a second producer built
 * to the specification would split one card across two groups and a redelivery arriving by any other
 * path would be accepted as new.</p>
 *
 * <p>Trade-offs: the card number therefore appears in message metadata, which server-side encryption of
 * the body does not cover, and that consequence is registered as divergence
 * {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA} in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than treated as unremarkable. Three
 * provisioned controls bound it: the queues are encrypted under a customer-managed key, they are
 * reachable only through an interface endpoint inside the private network, and read access is scoped to
 * the task roles of this service and of the requesting producer. Revisiting the trade means revisiting
 * the specification, not this file.</p>
 *
 * <p>Trade-offs: deduplication is exactly-once acceptance within a FIVE-MINUTE window, not unbounded
 * exactly-once. Outside that window the same transaction identifier is accepted again, and the
 * durable backstop is the database: the consumer looks the decision up and replies from the row it
 * already holds instead of deciding a second time. Stating the window rather than calling the queue
 * exactly-once matters because a reader who believed the stronger claim would see the database check
 * as redundant and remove the only protection that survives past five minutes.</p>
 *
 * <h2>Redelivery, and where retry authority lives</h2>
 *
 * <p>Assumptions: each queue has a dead-letter queue with a redrive policy that moves a message
 * aside after FIVE receives. That threshold is an external contract this configuration depends on
 * and does not express -- it is provisioned in {@code infra/modules/sqs} -- and it is the reason
 * nothing here counts attempts. Durable retry comes from queue redelivery bounded by that policy,
 * together with per-state retry in the batch orchestrator for the scheduled work this context also
 * runs. Two independent retry budgets would multiply rather than add, so a message would reach its
 * dead-letter queue long after an operator expected it to.</p>
 *
 * <p>Alternatives Considered: adopting a resilience library. Retry lives in the Spring Framework
 * core that arrives inside the Spring Boot 4.1.0 parent -- {@code @Retryable},
 * {@code @ConcurrencyLimit} and a programmatic {@code RetryTemplate} over a {@code RetryPolicy}
 * built from {@code includes}, {@code excludes}, {@code maxRetries}, {@code delay}, {@code jitter},
 * {@code multiplier} and {@code maxDelay} -- so a library would be a second retry authority
 * competing with queue redelivery and orchestrator retry for a capability the platform already has.
 * Two API details are recorded here because both are easy to get wrong at a use site: the annotation
 * attribute is {@code maxRetries} and not {@code maxAttempts}, so the total number of attempts is
 * one plus that value and defaults to three, and the enabling annotation is
 * {@code @EnableResilientMethods} and not {@code @EnableRetry}. Neither
 * {@code io.github.resilience4j:resilience4j-spring-boot3}, whose published artifact targets the
 * previous major of the framework, nor the superseded {@code spring-retry} is adopted. No circuit
 * breaker is configured either: the only synchronous hop this context makes is in-network behind an
 * internal load balancer with an explicit connect and read timeout, so a breaker would add a failure
 * mode without removing one. Recorded in {@code docs/adr/ADR-002-compute-platform.md}.</p>
 *
 * <p>Assumptions: were a retry policy ever added at a use site, its {@code includes} list has to be
 * narrow, and the baseline itself supplies the boundary. Its transient-condition set is exactly
 * three infrastructure statuses -- {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L87 and at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L88, which is the same declaration one
 * line apart in the two programs. The four data conditions declared in that same block are not in
 * the set: segment-not-found, duplicate, wrong-parentage and end-of-database describe the data and
 * would return the same answer however many times they were attempted.</p>
 *
 * <h2>The expiry deadline, and the hundredfold unit difference behind it</h2>
 *
 * <p>Trade-offs: the reference reply carries a deadline and the target transport has no field for
 * one, so ENFORCEMENT MOVES FROM THE BROKER TO THE CONSUMER. Two adjacent values in the baseline
 * both mean five seconds and are expressed in units a hundred apart, with no comment on either, so
 * the arithmetic is spelled out here rather than left for a reader to reconcile:
 * {@code COPAUA0C.cbl} L750 issues {@code MOVE 50 TO MQMD-EXPIRY OF MQM-MD-REPLY}, and that
 * descriptor field counts TENTHS OF A SECOND, so 50 is 5.0 seconds; {@code COPAUA0C.cbl} L242
 * issues {@code MOVE 5000 TO WS-WAIT-INTERVAL}, which is carried into the get options at L393 and
 * counts MILLISECONDS, so 5000 is also 5 seconds. A reader who harmonised the two literals would
 * change one of them by a factor of one hundred in whichever direction they chose.</p>
 *
 * <p>Trade-offs: the semantic is preserved in three parts and is not quietly dropped. A message
 * states its own deadline in the {@link MessageExpiry#HEADER_EXPIRES_AT} attribute, so the value
 * survives queue time; the consumer honours it by DROPPING the message AND LOGGING the drop, since
 * dropping in silence is the very failure this attribute exists to prevent; and reply-queue
 * retention is kept short by {@code infra/modules/sqs}, which is the analogue of the
 * non-persistent delivery the baseline asks for at L749 beside the deadline at L750. What is given
 * up is that under the baseline no program could act on an expired message because the queue
 * manager had already discarded it, whereas here every consumer must check, and one that did not
 * would act on a message the baseline would never have delivered. The reasoning and the two
 * rejected broker alternatives are recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
 *
 * <h2>The receive: bounded wait, bounded window, and an empty poll</h2>
 *
 * <p>Assumptions: the receive wait is five seconds, converted from L242 and L393 rather than chosen,
 * and it is read from {@code carddemo.messaging.poll-timeout-seconds} at the listener rather than
 * written here. The number of requests one processing window handles before intake closes and
 * reopens is likewise a property, {@code carddemo.messaging.request-process-limit}, and no bound
 * appears as a literal anywhere in this class.</p>
 *
 * <p>Assumptions: the baseline's own control flow admits ONE MORE than the limit it declares, and
 * the off-by-one is invisible without the arithmetic. {@code COPAUA0C.cbl} L40 declares
 * {@code 05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500.}; the counter is POST-incremented at
 * L332, after the message has been handled; and it is then tested with a STRICT greater-than at
 * L339, {@code IF WS-MSG-PROCESSED > WS-REQSTS-PROCESS-LIMIT}. Counts one through five hundred all
 * fail that test and read another request, so the loop ends only once the five-hundred-and-first has
 * been handled. Reproducing a bounded window at all is what keeps the target's throughput profile
 * the same shape as the baseline's instead of shifting it silently; which of the two numbers is
 * enforced is a registered divergence and the property, not this class, decides it.</p>
 *
 * <p>Assumptions: an empty receive after the wait completes the poll cycle NORMALLY and is not a
 * failure. The baseline treats its no-message-available reason at {@code COPAUA0C.cbl} L416 by
 * setting the no-more-messages condition at L417, which ends the loop opened at L326 cleanly. An
 * idle queue therefore produces no error log, no error metric and no failed health check here.</p>
 *
 * <h2>Reply routing is per message, and the correlation identifier is echoed</h2>
 *
 * <p>Assumptions: the reply destination is taken from the request and never from a statically
 * configured queue. The baseline's receive deliberately does not filter -- it pre-sets the
 * no-identifier constants into the message identifier at {@code COPAUA0C.cbl} L395 and into the
 * correlation identifier at L396 -- and on a successful receive it SAVES the inbound correlation
 * identifier at L411 to L412 into a field declared {@code PIC X(24)} at L45, and the inbound
 * reply-to queue at L413 to L414 into a field declared {@code PIC X(48)} at L44, the same width as
 * the request queue-name field at L43. The reply then takes its object name from that saved queue at
 * L742 and its correlation identifier from that saved value at L745, mints a FRESH message
 * identifier at L746, and blanks its own reply-to queue at L747 and reply-to queue manager at L748
 * so the reply is terminal. The put is a single open-put-close at L758, which is exactly why dynamic
 * routing needs no pre-opened handle. Alternatives Considered: one statically configured reply
 * destination, which is simpler to configure and is wrong: a requester that named its own reply
 * queue would never receive its reply, and the failure would be silent on the requester's side.</p>
 *
 * <p>Assumptions: which addresses a reply may be sent to is still constrained, by the exact-match
 * allowlist in {@code carddemo.messaging.reply-queue-allowlist} that the consumer applies. Dynamic
 * routing decides the destination from the message; the allowlist decides which destinations are
 * legitimate. Both are needed, because the reply-to attribute is chosen by whoever can put a message
 * on the request queue.</p>
 *
 * <h2>Descriptor fields, and the payload contract</h2>
 *
 * <p>Assumptions: each message-descriptor field the baseline sets maps onto one target attribute or
 * queue setting. The correlation identifier becomes {@code correlationId}; the message identifier
 * becomes {@code messageId}; the reply-to queue becomes {@code replyToQueueUrl}; the string format
 * indicator set outbound at L751 and inbound at L397 becomes a {@code contentType} of
 * {@code text/csv}; the non-persistent delivery at L749 becomes short reply-queue retention; and the
 * deadline at L750 becomes the {@code expiresAt} attribute described above.</p>
 *
 * <p>Assumptions: because the payload is declared in string format, FIELD ORDER, FIELD COUNT AND
 * DELIMITER ARE THE INTERFACE, and none of the three may change. The request declares EIGHTEEN
 * fields at {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} L19 to L36, whose declared
 * widths sum to 153 bytes with seventeen delimiters between them; the reply declares SIX fields at
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} L19 to L24, whose declared widths sum to
 * 57 bytes with five delimiters between them. Those two width sums are the source-verified figures.
 * Any TOTAL length is a derived figure, and {@link CsvAuthCodec} is its single owner: that class
 * publishes {@link CsvAuthCodec#REQUEST_WIRE_LENGTH} and {@link CsvAuthCodec#REPLY_WIRE_LENGTH} and
 * carries the delimiter accounting behind each, so no total is restated here. A JSON envelope may be
 * offered additively to a new consumer; it never replaces the delimited form.</p>
 *
 * <p>Assumptions: {@link CsvAuthCodec} performs all of the encoding and decoding and no codec is
 * declared in this context, because a shared wire contract with two implementations has two
 * definitions. Money in both payloads is declared {@code PIC +9(10).99} -- a fourteen-character
 * numeric-EDITED display field, at {@code CCPAURQY.cpy} L27 and {@code CCPAURLY.cpy} L24 -- which is
 * why the baseline converts it with {@code FUNCTION NUMVAL} at {@code COPAUA0C.cbl} L376 to L377
 * rather than reading it as a number. The spelling {@code PA-RQ-MERCHANT-CATAGORY-CODE} at
 * {@code CCPAURQY.cpy} L28 is carried on the wire exactly as declared; only the Java member name
 * differs.</p>
 *
 * <h2>Code page, and orderly shutdown</h2>
 *
 * <p>Assumptions: the inbound payload arrives in the consumer's own character encoding. The baseline
 * asks its transport to convert on receipt -- the get option set spans {@code COPAUA0C.cbl} L389 to
 * L391 and it is L390 that adds the convert option -- so code-page conversion was a transport
 * responsibility there. No target transport converts payloads, so the responsibility moves to the
 * PRODUCER, and this consumer reads what it is handed.</p>
 *
 * <p>Assumptions: the consumer cooperates with an orderly stop rather than being cut off mid-message.
 * L391 of that same option set adds the fail-if-quiescing option, which is how the baseline declines
 * to start a new get once its region has begun shutting down. The target equivalent is graceful
 * termination of the listener container on signal, configured by
 * {@link #authorizationListenerContainerOptions(long, long, java.time.Duration)} together with the
 * graceful web shutdown and per-phase timeout this module's {@code application.yml} declares.</p>
 *
 * <h2>Publication sits outside the transaction in both directions</h2>
 *
 * <p>Trade-offs: in the baseline the reply is published BEFORE the commit and outside it, so the
 * window is bidirectional and both halves of it lose something. The main loop performs the
 * authorization work at {@code COPAUA0C.cbl} L330, which reaches the send-response paragraph at
 * L738 and issues the put at L758 under the no-syncpoint put options set at L753 to L754; only then
 * does it commit, at L334 to L336 with the verb itself on L335, and release its database schedule at
 * L337. So if the put succeeds and the commit is rolled back, a PHANTOM REPLY describes a decision
 * that was never persisted; and if the put fails while the commit succeeds, a LOST REPLY is one the
 * persisted data says was produced. A transactional outbox closes both directions at once: nothing
 * is published unless it committed, and every committed row is eventually published.</p>
 *
 * <p>Assumptions: the outbox itself is implemented by {@code service/OutboxPublisher.java} and no
 * part of it is duplicated or approximated here. What this class carries is the compatibility
 * requirement that the send side supports publication AFTER a commit, from a drain, rather than
 * fire-and-forget publication inline in the listener. The per-message commit at L334 to L336 becomes
 * a per-message transaction boundary owned by the listener, and this configuration must not
 * undermine it -- which is why acknowledgement here is immediate and per message rather than
 * batched across a poll, since a batched acknowledgement would span messages whose transactions
 * committed independently.</p>
 *
 * <h2>Observability</h2>
 *
 * <p>Assumptions: the common metric tags come from {@link MetricsConfig} and the request correlation
 * identifier from {@link CorrelationIdFilter}, both imported from the shared kernel and neither
 * re-declared here, so one context cannot drift from another in how a metric is tagged or how a
 * correlation identifier is put into the logging context. The correlation concept is not invented by
 * the migration either: the baseline declares its own at
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} L40,
 * {@code 05 ERR-EVENT-KEY PIC X(20).}, the final line of that forty-line copybook. The target
 * formalises an existing concept rather than adding one.</p>
 *
 * <p>Assumptions: three field widths are quotable from files this repository holds -- 20 for that
 * event key, 24 for the saved correlation identifier at {@code COPAUA0C.cbl} L45, and 48 for the two
 * queue-name fields at L43 and L44. No width of any transport constant is quotable, because the six
 * vendor copybooks the baseline copies those constants from are absent from the repository.</p>
 *
 * <h2>Why this class configures the container at all</h2>
 *
 * <p>Refactoring Rationale: an earlier revision of this class supplied the reply client and nothing
 * else, leaving every listener-container setting at the starter's default. Three of those defaults
 * carry a consequence this context cannot accept, and none of the three is visible from the listener
 * annotation, which is why the settings moved here. The starter's default for a queue reference that
 * resolves to nothing is to CREATE the queue, so a deployment whose reference was wrong would come
 * up healthy against a queue no producer writes to. Its default acknowledgement batching is selected
 * by testing the queue name for its ordering suffix, so the per-message acknowledgement the
 * per-message transaction depends on rested on a configured string. And its two shutdown budgets are
 * each as long as this module's whole per-phase shutdown timeout, so a container draining a message
 * could be terminated by the very budget that exists to let it finish. Each is now set explicitly and
 * each carries its reasoning at the point it is applied.</p>
 *
 * <h2>What parity rests on here</h2>
 *
 * <p>Assumptions: NO GOLDEN MASTER EXISTS for this path, and the reason is direct rather than
 * incidental. {@code tests/README.md} records at L83 to L85 that the online programs cannot run end
 * to end without their transaction runtime, and {@code COPAUA0C} cannot even be compiled here
 * because the vendor copybooks it copies are not in the repository. Parity therefore rests on the
 * copybook and definition contracts and on logic transcribed line by line, which is why every claim
 * above cites the line it came from. The baseline behaves as described and remains reference
 * material, read and never modified; the migration adds a path, it does not remove one.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SqsConfig {

    /**
     * The suffix a first-in-first-out queue name is required to end with.
     *
     * <p>Assumptions: this is a transport rule and not a naming convention of this project. A
     * first-in-first-out queue cannot be created without it, and the listener starter selects its
     * ordered message source and its ordered acknowledgement path by testing for exactly this
     * suffix on the queue reference -- so a reference that lacks it silently selects the unordered
     * components. That is why the suffix is verified rather than assumed, by
     * {@link FifoQueueNamingContract}.</p>
     */
    public static final String FIFO_QUEUE_SUFFIX = ".fifo";

    /**
     * The scheme every queue ADDRESS this context sends to begins with.
     *
     * <p>Assumptions: an address rather than a bare name is required of a reply destination because the
     * drain passes each one unchanged as the destination of a send, and a send accepts an address only.
     * The scheme is checked rather than the whole address parsed: what distinguishes an address from the
     * two shapes that fail at send time -- a bare name and a resource name -- is exactly this prefix,
     * and parsing further would give this check an opinion about a host and a path it was not given.</p>
     */
    public static final String QUEUE_URL_SCHEME = "https://";

    /**
     * The prefix a resource name carries, which neither the listener nor a send can resolve.
     */
    public static final String RESOURCE_NAME_PREFIX = "arn:";

    /**
     * The receive sentinel that requests every message attribute a message carries.
     *
     * <p>Assumptions: the transport spells this sentinel exactly this way in a receive request, and
     * it is not an enumerated attribute name. It is declared here rather than written inline so the
     * one place it is used can be read against the reasoning on
     * {@link #authorizationListenerContainerOptions(long, long, java.time.Duration)}.</p>
     */
    private static final String ALL_MESSAGE_ATTRIBUTES = "All";

    /**
     * How many account-context round trips one authorization request can cost.
     *
     * <p>Assumptions: three, and they are the reads {@code COPAUA0C.cbl} performs at its paragraphs 5100,
     * 5200 and 5300 -- the card cross-reference, the account master and the customer existence check.
     * Three is the WORST case rather than the usual one: a card that does not resolve costs one, because
     * the reference program guards the remaining reads on the cross-reference having been found. The
     * worst case is the figure that matters when the sum is being compared against a deadline.</p>
     */
    private static final int ACCOUNT_CONTEXT_CALLS_PER_REQUEST = 3;

    /**
     * The logger for queue-transport configuration, named for this class.
     *
     * <p>Assumptions: naming the logger for this class lets a deployment raise the level on
     * transport configuration alone. What it records is what was verified and applied at startup,
     * because the alternative is that a misconfiguration of ordering or of shutdown draining is
     * observable only as behaviour much later, on the reply path, under load.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(SqsConfig.class);

    /**
     * Supplies the synchronous queue client the outbox drain publishes replies with.
     *
     * <p>Trade-offs: this is the SYNCHRONOUS client, while the starter auto-configures an
     * asynchronous one for the listener container. The drain sends inside a database transaction
     * that is holding row locks, and it must know each send's outcome before it decides what to
     * write on the row, so it would have to block on a future immediately in any case. Blocking
     * explicitly on a synchronous call is the same wait with none of the ambiguity about which
     * thread the continuation runs on, and that ambiguity matters because the continuation touches a
     * transaction-bound persistence context that is not safe to use from another thread.</p>
     *
     * <p>Assumptions: the builder is handed to the starter's own configurer rather than being
     * configured here, so region, credentials and any endpoint override resolve exactly as they do
     * for the auto-configured client. Setting them here would create a second place the two clients
     * could disagree about which account and region they address, and a publisher pointed at a
     * different endpoint from the consumer fails only at run time, only on the reply path.</p>
     *
     * <p>Refactoring Rationale: the client is given a whole-call bound and a per-attempt bound, and it
     * had neither -- the software development kit's default for both is no bound at all, so a stalled
     * send retried indefinitely. For the drain that is a stuck scheduled pass holding row locks. For the
     * request consumer the same absence is worse, because a handler that outlives its message's
     * visibility period does not merely run late: the queue makes the request visible again, a second
     * consumer takes it, and two handlers decide one authorization at once. The durable
     * transaction-identifier check makes the second decision idempotent rather than harmless, and
     * relying on it to absorb a race the configuration could have prevented is not a bound.</p>
     *
     * <p>Assumptions: this method is where the WHOLE per-message budget is compared against visibility,
     * because it is the only place in this context that can see every bound at once. The three
     * account-context bounds and the datasource socket bound are read here for comparison only -- they
     * are applied by {@code RestAccountContextClient} and {@code DataSourceConfig} respectively, each
     * from the same property, so no value is set twice and none can drift between the two readers.</p>
     *
     * <p>Assumptions: the visibility period is a PROPERTY rather than a value read from the queue. It is
     * set by {@code infra/modules/sqs}, whose {@code visibility_timeout_seconds} defaults to 60, and the
     * default here is that same 60 so an unconfigured context validates against what the infrastructure
     * provisions. Reading it from the queue was the alternative and is rejected: context refresh would
     * then depend on a reachable queue, and the check would silently pass in every test and local run.</p>
     *
     * <p>Trade-offs: the bounds are applied through the CONSUMER form of
     * {@code overrideConfiguration}, which mutates the configuration the starter's configurer already
     * built. The value form would replace it, discarding the retry policy, the user agent and any
     * execution interceptor the starter installed -- a loss visible only as absent telemetry and absent
     * retries, neither of which fails a test.</p>
     *
     * @param configurer the starter's client-builder configurer, which applies the resolved region,
     *     credentials provider and any endpoint override; must not be {@code null}
     * @param apiCallTimeoutMillis the whole-call bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_TIMEOUT}; must be positive and shorter than the
     *     visibility period
     * @param apiCallAttemptTimeoutMillis the per-attempt bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_ATTEMPT_TIMEOUT}; must be positive and must not
     *     exceed the whole-call bound
     * @param visibilityTimeoutSeconds how long a received message stays invisible to other consumers,
     *     from {@link QueueClientBudget#PROPERTY_VISIBILITY_TIMEOUT}; must be positive
     * @param accountConnectMillis the account-context connect bound in milliseconds, from
     *     {@code carddemo.account-context.connect-timeout-ms}, read here only to be summed
     * @param accountReadMillis the account-context read bound in milliseconds, from
     *     {@code carddemo.account-context.read-timeout-ms}, read here only to be summed
     * @param datasourceReadMillis the datasource socket bound in milliseconds, from
     *     {@code carddemo.datasource.read-timeout-ms}, read here only to be summed
     * @return the synchronous queue client, never {@code null}
     * @throws IllegalStateException if the three queue bounds do not satisfy {@link QueueClientBudget},
     *     or if the summed per-message budget reaches the visibility period, so the failure arrives at
     *     startup naming the relationship that does not hold rather than as duplicated processing later
     */
    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(AwsClientBuilderConfigurer configurer,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_TIMEOUT + ":10000}")
            long apiCallTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT + ":5000}")
            long apiCallAttemptTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_VISIBILITY_TIMEOUT + ":60}")
            long visibilityTimeoutSeconds,
            @Value("${carddemo.account-context.connect-timeout-ms:2000}") long accountConnectMillis,
            @Value("${carddemo.account-context.read-timeout-ms:3000}") long accountReadMillis,
            @Value("${carddemo.datasource.read-timeout-ms:30000}") long datasourceReadMillis) {

        QueueClientBudget budget = new QueueClientBudget(
                Duration.ofMillis(apiCallTimeoutMillis),
                Duration.ofMillis(apiCallAttemptTimeoutMillis),
                Duration.ofSeconds(visibilityTimeoutSeconds));

        // WHY : Assumptions: the handler budget is the THREE account-context round trips the consumer
        //       makes -- the cross-reference, the account master and the customer existence check, at
        //       COPAUA0C.cbl paragraphs 5100, 5200 and 5300 -- plus the datasource socket bound that
        //       backstops the database work. Each round trip can spend a connect and a read, so the
        //       worst case is three times their sum. Adding them here rather than trusting each bound
        //       separately is the point: the individual bounds are all reasonable and their SUM is what
        //       has to fit inside the visibility period, and nothing else in this context compares them.
        Duration handlerWork = Duration.ofMillis(accountConnectMillis + accountReadMillis)
                .multipliedBy(ACCOUNT_CONTEXT_CALLS_PER_REQUEST)
                .plus(Duration.ofMillis(datasourceReadMillis));
        budget.requireFitsVisibility(handlerWork,
                ACCOUNT_CONTEXT_CALLS_PER_REQUEST + " account-context round trips of"
                        + " carddemo.account-context.connect-timeout-ms plus"
                        + " carddemo.account-context.read-timeout-ms, plus"
                        + " carddemo.datasource.read-timeout-ms");
        LOG.info("event=auth.queue.client.bounded apiCallTimeout={} apiCallAttemptTimeout={}"
                        + " handlerWork={} visibilityTimeout={}",
                budget.apiCallTimeout(), budget.apiCallAttemptTimeout(), handlerWork,
                budget.visibilityTimeout());

        return configurer.configure(SqsClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(budget.apiCallTimeout())
                        .apiCallAttemptTimeout(budget.apiCallAttemptTimeout()))
                .build();
    }

    /**
     * Verifies at startup that every authorization queue reference this context uses is ordered.
     *
     * <p>Assumptions: both references are read with an empty default, so a context that configures
     * no queue at all has nothing to verify and starts. That is not a weakening: presence is already
     * required elsewhere, because the consumer injects both of these properties without a default
     * and a context missing either cannot refresh. Splitting the two concerns this way is what lets
     * the unit tests in this module build a context with no queue configured while a deployment
     * still cannot start without one.</p>
     *
     * <p>Alternatives Considered: leaving the queue TYPE unverified and relying on the ordering
     * option applied by {@link #authorizationListenerContainerOptions(long, long, java.time.Duration)} to refuse an
     * unordered queue. That refusal covers the request side only, because the container never opens
     * the reply queue -- a reply goes to the address its request nominated. An unordered reply
     * destination would therefore pass startup and fail later, when the transport rejected the group
     * and deduplication identifiers the drain sets on every send, on the reply path only, for the
     * one requester that had named it. Verifying the allowlist here converts that into a startup
     * failure that names the offending entry.</p>
     *
     * @param requestQueue the reference to the ordered request queue, from
     *     {@code carddemo.messaging.pauth-request-queue}, empty when this context configures none;
     *     must not be {@code null}
     * @param replyQueueAllowlist the addresses a reply may be sent to, from
     *     {@code carddemo.messaging.reply-queue-allowlist}, empty when this context configures none;
     *     must not be {@code null}
     * @return the verified naming contract, carrying the references it checked, never {@code null}
     * @throws IllegalStateException if any configured reference does not name an ordered queue, so
     *     the failure arrives at startup naming the property rather than on the reply path
     */
    @Bean
    public FifoQueueNamingContract authorizationQueueNamingContract(
            @Value("${carddemo.messaging.pauth-request-queue:}") String requestQueue,
            @Value("${carddemo.messaging.reply-queue-allowlist:}") List<String> replyQueueAllowlist) {
        return new FifoQueueNamingContract(requestQueue, replyQueueAllowlist);
    }

    /**
     * Applies the container settings that carry a transport semantic the listener cannot express.
     *
     * <p>Alternatives Considered: declaring a listener-container factory bean here instead. The
     * starter's factory is conditional on one being absent, so a factory declared here would replace
     * it rather than sit beside it -- and with it would go every collaborator the starter wires into
     * that factory: the message converter, the observation registry, any error handler or message
     * interceptor bean, and the mapping of the starter's own listener properties. Each of those
     * would have to be re-injected and re-applied here to stand still, and the copy would then drift
     * from the starter across an upgrade. Post-processing the factory the starter already built
     * avoids that entirely: the settings below are appended after its own, and appended settings
     * take precedence over earlier ones for the same option while leaving every option and every
     * collaborator this class does not name exactly as the starter left it.</p>
     *
     * <p>Assumptions: the method is static so the returned post-processor is created before the
     * ordinary singletons it must observe, without forcing early instantiation of the enclosing
     * configuration class.</p>
     *
     * <p>Refactoring Rationale: the two budgets are now VALIDATED rather than merely converted. They
     * were accepted as raw second counts with no check of any kind, so a deployment could set either to
     * zero or to a negative number -- a container that waits zero seconds for its in-flight messages
     * abandons every one of them, and the acknowledgement it never sent means the queue redelivers work
     * that had already completed. They were also unchecked against the shutdown phase they are spent
     * inside: a stopping container consumes the two in sequence, and if their sum exceeds
     * {@code spring.lifecycle.timeout-per-shutdown-phase} the platform terminates the process
     * mid-drain, which is the same loss reached by a different route. Both conditions now fail startup.</p>
     *
     * <p>Assumptions: the phase timeout is READ from configuration rather than restated here, with the
     * framework's own thirty-second default as its default, so lowering it in {@code application.yml}
     * fails this check instead of silently leaving a drain that cannot finish.</p>
     *
     * @param listenerShutdownSeconds how long a stopping container waits for in-flight messages to
     *     finish, from {@code carddemo.messaging.listener-shutdown-timeout-seconds}; must be positive
     * @param acknowledgementShutdownSeconds how long a stopping container waits for outstanding
     *     acknowledgements to be sent, from
     *     {@code carddemo.messaging.acknowledgement-shutdown-timeout-seconds}; must be positive
     * @param shutdownPhaseTimeout the phase both budgets are spent inside, from
     *     {@code spring.lifecycle.timeout-per-shutdown-phase}; must be positive
     * @return the post-processor that applies these settings to the listener-container factory,
     *     never {@code null}
     * @throws IllegalStateException if either budget is not positive, or if their sum exceeds the
     *     shutdown phase they are spent inside, so the failure names the property rather than presenting
     *     as work abandoned during a deployment
     */
    @Bean
    public static BeanPostProcessor authorizationListenerContainerOptions(
            @Value("${carddemo.messaging.listener-shutdown-timeout-seconds:10}")
            long listenerShutdownSeconds,
            @Value("${carddemo.messaging.acknowledgement-shutdown-timeout-seconds:5}")
            long acknowledgementShutdownSeconds,
            @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") Duration shutdownPhaseTimeout) {
        return new ListenerContainerOptionsCustomizer(Duration.ofSeconds(listenerShutdownSeconds),
                Duration.ofSeconds(acknowledgementShutdownSeconds), shutdownPhaseTimeout);
    }

    /**
     * Carries the authorization queue references whose names were verified to be ordered.
     *
     * <p>Assumptions: this is a verified value rather than a checker, and it is published as a bean
     * so that the verification is part of building the context rather than something a caller has to
     * remember to invoke. Holding what it checked also lets a test assert the contract without
     * reaching into the environment.</p>
     */
    public static final class FifoQueueNamingContract {

        /**
         * The verified reference to the ordered request queue, empty when none is configured.
         */
        private final String requestQueue;

        /**
         * The verified addresses a reply may be sent to, empty when none is configured.
         */
        private final List<String> replyQueueAllowlist;

        /**
         * Verifies the supplied references and retains them.
         *
         * <p>Assumptions: a blank entry is skipped rather than refused. An empty allowlist arrives
         * as one blank element when the property is absent, which is the ordinary shape of an unset
         * comma-separated value, and refusing it would fail every context that configures no queue.
         * A NON-blank entry is always checked, so nothing that is actually configured escapes.</p>
         *
         * <p>Refactoring Rationale: the two references are checked by DIFFERENT rules, and one rule
         * checked both. They are consumed by different code in different shapes: the request queue is
         * handed to the listener starter, which resolves either a bare queue name or a queue address,
         * while every allowlisted reply destination is passed straight to {@code queueUrl(...)} on a send
         * by {@code OutboxPublisher}, which accepts an address and nothing else. One rule that accepted
         * both shapes for both references blessed a bare name in the allowlist that made every reply to
         * that requester fail, and a resource name in either position that neither consumer can
         * resolve.</p>
         *
         * @param requestQueue the reference to the ordered request queue, possibly blank; must not
         *     be {@code null}
         * @param replyQueueAllowlist the addresses a reply may be sent to, possibly empty or
         *     containing blank entries; must not be {@code null}
         * @throws IllegalStateException if a non-blank request-queue reference is not a bare name or an
         *     address ending in {@link SqsConfig#FIFO_QUEUE_SUFFIX}, or if a non-blank allowlist entry is
         *     not an address ending in that suffix, because an unordered queue cannot carry the per-card
         *     ordering the reference consumer's serial loop provides and a non-address cannot be sent to
         */
        FifoQueueNamingContract(String requestQueue, List<String> replyQueueAllowlist) {
            Objects.requireNonNull(requestQueue, "requestQueue");
            Objects.requireNonNull(replyQueueAllowlist, "replyQueueAllowlist");
            requireOrderedListenerReference(requestQueue, "carddemo.messaging.pauth-request-queue");
            for (String address : replyQueueAllowlist) {
                requireOrderedReplyQueueUrl(address, "carddemo.messaging.reply-queue-allowlist");
            }
            this.requestQueue = requestQueue;
            this.replyQueueAllowlist = List.copyOf(replyQueueAllowlist);
            LOG.info("event=auth.queue.naming.verified requestQueueConfigured={} allowlistSize={}",
                    !requestQueue.isBlank(), this.replyQueueAllowlist.size());
        }

        /**
         * Returns the verified request-queue reference.
         *
         * @return the reference, blank when this context configures none, never {@code null}
         */
        public String requestQueue() {
            return this.requestQueue;
        }

        /**
         * Returns the verified reply-destination allowlist.
         *
         * @return an unmodifiable copy of the configured addresses, never {@code null}
         */
        public List<String> replyQueueAllowlist() {
            return this.replyQueueAllowlist;
        }

        /**
         * Refuses a non-blank listener reference that the starter cannot resolve to an ordered queue.
         *
         * <p>Assumptions: a bare queue name and a queue address are both accepted, because the listener
         * starter resolves either. A RESOURCE NAME is refused: it resolves to neither, so a deployment
         * configuring one would start and then receive nothing at all, which is the failure a startup
         * check exists to convert into a message naming the property.</p>
         *
         * <p>Refactoring Rationale: the suffix is compared CASE-SENSITIVELY, and it was compared against
         * a lower-cased copy of the reference. The lower-casing was defended as locale hygiene, and it
         * inverted the check's meaning: the transport's own suffix is lower case, the listener starter
         * selects its ordered message source and ordered acknowledgement path by testing for exactly
         * that lower-case suffix, so a reference ending {@code .FIFO} passed this check and then selected
         * the UNORDERED components -- losing the per-card ordering silently, which is the one outcome
         * this class exists to prevent. Case-sensitivity also disposes of the locale question entirely:
         * nothing is transformed, so no locale can transform it differently.</p>
         *
         * @param reference the configured reference to check, possibly blank; must not be
         *     {@code null}
         * @param propertyName the property the reference came from, named in the failure so an
         *     operator repairs the right value; must not be {@code null}
         * @throws IllegalStateException if the reference is non-blank and is either a resource name or
         *     does not end with {@link SqsConfig#FIFO_QUEUE_SUFFIX} exactly
         */
        private static void requireOrderedListenerReference(String reference, String propertyName) {
            if (reference.isBlank()) {
                return;
            }
            String trimmed = reference.trim();
            if (trimmed.startsWith(RESOURCE_NAME_PREFIX)) {
                throw new IllegalStateException(propertyName
                        + " must be a queue name or a queue address, not a resource name: the listener"
                        + " starter resolves a name or an address and would receive nothing at all");
            }
            requireFifoSuffix(trimmed, propertyName);
        }

        /**
         * Refuses a non-blank reply destination that is not an address of an ordered queue.
         *
         * <p>Assumptions: only an ADDRESS is accepted here, where the listener reference also accepts a
         * bare name, and the asymmetry is the consuming code rather than a preference. Every entry of
         * this list is compared against a requester's reply-to attribute and then passed unchanged to
         * {@code queueUrl(...)} on a send, so a bare name or a resource name in this list is a value that
         * passes startup and then fails every reply to the requester that nominated it -- on the reply
         * path only, for one requester, which is the least observable place for it to fail.</p>
         *
         * @param address the configured destination to check, possibly blank; must not be {@code null}
         * @param propertyName the property the destination came from, named in the failure; must not be
         *     {@code null}
         * @throws IllegalStateException if the destination is non-blank and is not an
         *     {@code https} address, or does not end with {@link SqsConfig#FIFO_QUEUE_SUFFIX} exactly
         */
        private static void requireOrderedReplyQueueUrl(String address, String propertyName) {
            if (address.isBlank()) {
                return;
            }
            String trimmed = address.trim();
            if (!trimmed.startsWith(QUEUE_URL_SCHEME)) {
                throw new IllegalStateException(propertyName
                        + " must be a queue address beginning " + QUEUE_URL_SCHEME
                        + ": every entry is passed unchanged as the destination of a send, which accepts"
                        + " an address and neither a bare name nor a resource name");
            }
            requireFifoSuffix(trimmed, propertyName);
        }

        /**
         * Refuses a reference whose final segment does not end with the ordered-queue suffix.
         *
         * @param trimmed the non-blank, trimmed reference to check; must not be {@code null}
         * @param propertyName the property the reference came from, named in the failure; must not be
         *     {@code null}
         * @throws IllegalStateException if the reference does not end with
         *     {@link SqsConfig#FIFO_QUEUE_SUFFIX} exactly
         */
        private static void requireFifoSuffix(String trimmed, String propertyName) {
            if (!trimmed.endsWith(FIFO_QUEUE_SUFFIX)) {
                throw new IllegalStateException(propertyName
                        + " must name a first-in-first-out queue, whose name ends with the exact"
                        + " lower-case suffix " + FIFO_QUEUE_SUFFIX + ": an unordered queue gives no"
                        + " per-card ordering and rejects the group and deduplication identifiers every"
                        + " send sets, and the starter tests for this suffix case-sensitively");
            }
        }
    }

    /**
     * Appends this context's container settings to the listener-container factory the starter built.
     *
     * <p>Assumptions: the factory accumulates configuration callbacks against one shared options
     * builder and applies them in the order they were added, so a callback added after the context
     * is built has the last word on the options it sets and leaves the rest untouched. That is what
     * makes this a supplement to the starter's configuration rather than a replacement for it.</p>
     */
    static final class ListenerContainerOptionsCustomizer implements BeanPostProcessor {

        /**
         * How long a stopping container waits for in-flight messages to finish.
         */
        private final Duration listenerShutdownTimeout;

        /**
         * How long a stopping container waits for outstanding acknowledgements to be sent.
         */
        private final Duration acknowledgementShutdownTimeout;

        /**
         * Validates the two shutdown budgets against the phase they are spent in, and retains them.
         *
         * @param listenerShutdownTimeout how long a stopping container waits for in-flight messages
         *     to finish; must not be {@code null} and must be positive
         * @param acknowledgementShutdownTimeout how long a stopping container waits for outstanding
         *     acknowledgements to be sent; must not be {@code null} and must be positive
         * @param shutdownPhaseTimeout the phase both budgets are spent inside, which their sum must fit;
         *     must not be {@code null} and must be positive
         * @throws NullPointerException if any budget is {@code null}
         * @throws IllegalStateException if any budget is not positive, or if the two container budgets
         *     together exceed the shutdown phase
         */
        ListenerContainerOptionsCustomizer(Duration listenerShutdownTimeout,
                Duration acknowledgementShutdownTimeout, Duration shutdownPhaseTimeout) {
            this.listenerShutdownTimeout =
                    Objects.requireNonNull(listenerShutdownTimeout, "listenerShutdownTimeout");
            this.acknowledgementShutdownTimeout = Objects.requireNonNull(
                    acknowledgementShutdownTimeout, "acknowledgementShutdownTimeout");
            Objects.requireNonNull(shutdownPhaseTimeout, "shutdownPhaseTimeout");
            requirePositiveBudget(listenerShutdownTimeout,
                    "carddemo.messaging.listener-shutdown-timeout-seconds",
                    "a container that waits no time for its in-flight messages abandons every one of"
                            + " them, and the acknowledgement it never sends makes the queue redeliver"
                            + " work that had already completed");
            requirePositiveBudget(acknowledgementShutdownTimeout,
                    "carddemo.messaging.acknowledgement-shutdown-timeout-seconds",
                    "an acknowledgement budget of no time discards the acknowledgements of messages"
                            + " that succeeded, so the queue redelivers completed work");
            requirePositiveBudget(shutdownPhaseTimeout, "spring.lifecycle.timeout-per-shutdown-phase",
                    "a phase of no duration terminates the process before any drain begins");
            // WHY : Assumptions: the two budgets are compared as a SUM because a stopping container
            //       spends them in sequence -- it waits for in-flight messages first and for outstanding
            //       acknowledgements afterwards -- so neither alone is what has to fit. Comparing them
            //       individually would bless a pair that each fit and together do not.
            Duration drain = listenerShutdownTimeout.plus(acknowledgementShutdownTimeout);
            if (drain.compareTo(shutdownPhaseTimeout) > 0) {
                throw new IllegalStateException("the container drain budget " + drain
                        + " (carddemo.messaging.listener-shutdown-timeout-seconds plus"
                        + " carddemo.messaging.acknowledgement-shutdown-timeout-seconds) exceeds"
                        + " spring.lifecycle.timeout-per-shutdown-phase " + shutdownPhaseTimeout
                        + ": the platform would terminate the process mid-drain, abandoning in-flight"
                        + " messages and the acknowledgements of completed ones");
            }
        }

        /**
         * Refuses a shutdown budget that is not positive, naming the property and the consequence.
         *
         * @param budget the configured budget; must not be {@code null}
         * @param propertyName the property the budget came from, named in the failure; must not be
         *     {@code null}
         * @param consequence what a non-positive value costs, quoted so the failure explains itself;
         *     must not be {@code null}
         * @throws IllegalStateException if the budget is zero or negative
         */
        private static void requirePositiveBudget(Duration budget, String propertyName,
                String consequence) {
            if (budget.isZero() || budget.isNegative()) {
                throw new IllegalStateException(propertyName + " must be positive but was " + budget
                        + ": " + consequence);
            }
        }

        /**
         * Applies this context's settings to a listener-container factory as it is created.
         *
         * <p>Assumptions: the type test is on the factory rather than on the bean name, because the
         * name of the starter's factory is an implementation detail of the starter while its type is
         * the contract a listener binds through. Any other bean is returned untouched.</p>
         *
         * @param bean the bean the container has just initialised; must not be {@code null}
         * @param beanName the name the bean is registered under, unused because the selection is by
         *     type; must not be {@code null}
         * @return the same bean instance, configured when it is a listener-container factory, never
         *     {@code null}
         */
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof SqsMessageListenerContainerFactory<?> factory) {
                factory.configure(this::apply);
                LOG.info("event=auth.listener.options.applied factory={}"
                                + " listenerShutdown={} acknowledgementShutdown={}",
                        beanName, this.listenerShutdownTimeout,
                        this.acknowledgementShutdownTimeout);
            }
            return bean;
        }

        /**
         * Sets the options that carry a baseline semantic the listener annotation cannot express.
         *
         * @param options the shared options builder of the factory being configured; must not be
         *     {@code null}
         */
        private void apply(SqsContainerOptionsBuilder options) {
            // WHY : Assumptions: the queues are provisioned by infra/modules/sqs, so a reference
            //   that resolves to nothing is a configuration fault and not an instruction. The
            //   starter's default is to CREATE the missing queue, and what it would create is the
            //   wrong queue in three separate ways -- unordered, unencrypted, and with no redrive
            //   policy to the dead-letter queue this design bounds redelivery with -- so the
            //   service would come up healthy, consume from a queue no producer writes to, and
            //   answer nothing. Failing is the only outcome that names the fault.
            options.queueNotFoundStrategy(QueueNotFoundStrategy.FAIL);

            // WHY : Assumptions: a message is acknowledged only where its handler returned, so a
            //   handler that threw leaves the message to reappear after its visibility timeout and
            //   the queue's own redrive policy is the single authority on how many attempts it
            //   gets. Alternatives Considered: acknowledging unconditionally, which would delete a
            //   request whose transaction had rolled back -- the requester would wait for a reply
            //   that no row exists to produce, and the message would be gone.
            options.acknowledgementMode(AcknowledgementMode.ON_SUCCESS);

            // WHY : Trade-offs: acknowledgements are ordered WITHIN a message group, which costs
            //   the parallelism a fully unordered acknowledgement path would have and buys the
            //   ordering guarantee the group exists for: the transport releases the next message of
            //   a group only once the previous one is acknowledged, so acknowledging out of order
            //   would let a later request for one card be delivered while an earlier one was still
            //   outstanding. It has a second effect worth naming because it is the mechanical half
            //   of "never an unordered queue for this pair": the starter refuses to build an
            //   unordered message source with group-ordered acknowledgement, so a request queue
            //   that is not first-in-first-out fails at container start instead of quietly losing
            //   per-card ordering.
            options.acknowledgementOrdering(AcknowledgementOrdering.ORDERED_BY_GROUP);

            // WHY : Assumptions: each message is acknowledged on its own, immediately, and never as
            //   part of a batch that spans messages. The reference consumer commits once per
            //   message inside its loop -- app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl L334
            //   to L336 -- and the listener's per-message transaction is the migrated form of that
            //   commit, so an acknowledgement covering several messages would span transactions
            //   that committed independently and could delete a message whose own transaction had
            //   rolled back. Alternatives Considered: leaving both unset and relying on the
            //   starter to pick immediate acknowledgement because the queue is first-in-first-out.
            //   Rejected because that selection is made by testing the queue NAME for its suffix,
            //   so the guarantee would rest on a configured string; set explicitly, it holds
            //   whichever components the starter selects.
            options.acknowledgementInterval(Duration.ZERO);
            options.acknowledgementThreshold(0);

            // WHY : Assumptions: every message attribute is requested, so the four this contract
            //   depends on -- the content type, the correlation identifier, the reply destination
            //   and the expiry instant -- all arrive whatever else a producer sends. Pinning it
            //   here rather than inheriting it matters most for the expiry instant, because that
            //   deadline is enforced by the consumer alone: an attribute that failed to arrive
            //   would not surface as an error, it would surface as an expired message being acted
            //   on. Alternatives Considered: naming exactly those four. Rejected on two counts --
            //   a fifth attribute a producer added would be dropped with no diagnostic, and the
            //   four names are already owned by the consumer and the drain that read them, so
            //   repeating them here would put a second owner on the attribute contract.
            options.messageAttributeNames(List.of(ALL_MESSAGE_ATTRIBUTES));

            // WHY : Assumptions: a stopping container drains rather than being cut off, which is
            //   the target form of the baseline declining a new get once its region has begun to
            //   quiesce at COPAUA0C.cbl L391. Trade-offs: the two budgets are set so their SUM sits
            //   inside the per-phase shutdown timeout this module's application.yml declares, which
            //   is itself held below the platform's signal-to-kill window. Left at their defaults
            //   the two are each as long as the whole phase budget, so a container draining a
            //   message would be terminated mid-transaction by the very timeout that exists to let
            //   it finish -- and the rollback would be attributed to redelivery rather than to
            //   shutdown. The acknowledgement budget is the smaller of the two because
            //   acknowledgement here is immediate, so what it waits for is one request in flight
            //   and not a buffer.
            options.listenerShutdownTimeout(this.listenerShutdownTimeout);
            options.acknowledgementShutdownTimeout(this.acknowledgementShutdownTimeout);
        }
    }
}
