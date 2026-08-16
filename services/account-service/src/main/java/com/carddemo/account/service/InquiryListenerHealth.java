package com.carddemo.account.service;

import com.carddemo.common.observability.ThrowableDigest;
import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Reports whether the account-inquiry consumer is actually consuming.
 *
 * <h2>Why this exists</h2>
 *
 * <p>⚠️ Refactoring Rationale: this service reported itself healthy while its inquiry consumer was dead.
 * The health endpoint aggregated the datasource, the disk and the application's own readiness state, and
 * nothing at all about the listener container -- so a request queue that did not exist, a queue attribute
 * lookup that was refused, or a container that stopped after start-up all produced the same answer as a
 * working service: {@code UP}. Every inquiry request then accumulated on the queue unanswered, each
 * requester waited for a reply that would never come, and the orchestrator kept the task in service
 * because the task said it was fine. Contributing the container's state is what converts that into a
 * failing health check, which is the signal the platform already acts on by replacing the task.</p>
 *
 * <p>Assumptions: the baseline has the same discipline and reaches it differently.
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} opens all three queues before it takes a single message -- the
 * input queue at physical line 227, the reply queue at 261 and the error queue at 318 -- and performs
 * {@code 8000-TERMINATION} if any open fails, at physical lines 250, 284 and 319. A consumer that cannot
 * reach its queues therefore does not linger there; it ends, and the transaction that triggers it is not
 * left half-running. A container-hosted consumer has no equivalent of ending itself, so the target
 * expresses the same intent through the signal its platform reads.</p>
 *
 * <h2>What it reports and what it deliberately does not</h2>
 *
 * <p>⚠️ Assumptions: TWO facts are reported, not one, and the second is here because the first is not
 * sufficient. The container's own running flag does NOT go false when its queue does not exist: the
 * container starts, registers, and then fails every receive -- which is precisely the condition observed,
 * a service reporting itself healthy while consuming nothing. So this indicator also requires the request
 * queue to be REACHABLE, verified once by resolving its attributes.</p>
 *
 * <p>Assumptions: the reachability verdict is CACHED once it succeeds, and after that no queue call is ever
 * made again. That is what keeps this signal from coupling the synchronous account API's health to the
 * queue service's availability -- a probe that called the queue service on every poll would take every
 * task out of service during a messaging outage, converting a messaging outage into an API outage, and the
 * account view and update paths need no queue at all. While the verdict is failing it is re-attempted on
 * each probe, so a queue created after start-up recovers the signal without a restart. The cost is bounded
 * by {@code SqsConfig}'s own client budget, which sets a ten-second whole-call and five-second per-attempt
 * timeout, so a probe cannot hang on an unreachable endpoint.</p>
 *
 * <p>Assumptions: only the REQUEST queue is verified this way, and the omission of the reply and error
 * queues is an IAM constraint rather than an oversight. {@code infra/modules/ecs-service} grants
 * {@code sqs:GetQueueAttributes} on the RECEIVE list alone -- its send statement carries
 * {@code sqs:SendMessage} and nothing else -- so resolving a send-only queue's attributes would be denied
 * in a deployed task and would report a healthy service as down. The request queue's permission is
 * guaranteed because the listener framework itself resolves that queue's attributes at container start. A
 * reply queue that does not exist is reported by the error sink's own {@code MQPUT ERR} arm and reaches the
 * dead-letter queue at the fifth receive, which is where that condition is visible.</p>
 *
 * <p>Trade-offs: a container that is running, whose request queue resolved once, and which is then making
 * no progress -- polling successfully while every handler invocation fails, or polling a queue deleted
 * after start-up -- still reports {@code UP} here. Those are left to the queue's own dead-letter queue, to
 * the error sink this consumer publishes to, and to the stale-work alarm on message age, because a handler
 * failure is a per-message outcome and a task that is answering some requests and failing others must not
 * be taken out of service for it. What this indicator catches is the whole-consumer failure, which nothing
 * else caught.</p>
 *
 * <p>Assumptions: the details this indicator attaches are visible only where the management configuration
 * admits them. {@code src/main/resources/application.yml} sets both {@code show-details} and
 * {@code show-components} to {@code never}, so an unauthenticated prober sees the aggregated status alone
 * -- which is what it needs, and it is why a failing consumer changes the endpoint's status code rather
 * than only its body. The details are still attached because they are what an operator reading the
 * endpoint under a configuration that admits them, or reading a test's assertion, needs in order to act.</p>
 *
 * <p>Parameters, return values, exceptions or errors: this class's constructor and single operation each
 * carry their own at-clauses. The inapplicability at type level is stated rather than passed over, because
 * the Explainability rule forbids a docstring that omits parameters or return values and a reader must be
 * able to tell a declared inapplicability from an oversight.</p>
 */
@Component
public class InquiryListenerHealth implements HealthIndicator {

    /**
     * The identifier the inquiry listener container is registered under.
     *
     * <p>Assumptions: this constant IS the {@code id} of the {@code @SqsListener} on
     * {@link InquiryMessageListener} -- that annotation references this field rather than repeating its
     * text, so the two cannot drift apart while the reference stands. The value lives here rather than
     * there because a mismatch between the two would not fail at start-up: the registry would simply answer
     * nothing and this indicator would report the consumer missing on a perfectly healthy task, which is a
     * false alarm that costs a task replacement. That agreement is asserted from the annotation by
     * {@code InquiryMessageListenerTest}, so a later edit replacing the reference with a literal fails a
     * test rather than passing quietly.</p>
     *
     * <p>Assumptions: the spelling follows the sibling consumer's own container identifier in
     * {@code authorization-service}, {@code carddemo-pauth-request-listener}, so an operator reading two
     * services' health output meets one naming convention rather than two.</p>
     */
    public static final String REQUEST_CONTAINER_ID = "carddemo-account-inquiry-listener";

    /**
     * The detail key the container identifier is reported under.
     */
    static final String DETAIL_CONTAINER_ID = "containerId";

    /**
     * The detail key the reason for a down report is given under.
     */
    static final String DETAIL_REASON = "reason";

    /**
     * The reason reported when no listener registry is present in this context at all.
     */
    static final String REASON_REGISTRY_UNAVAILABLE = "listener-registry-unavailable";

    /**
     * The reason reported when the registry holds no container under the expected identifier.
     */
    static final String REASON_CONTAINER_ABSENT = "listener-container-absent";

    /**
     * The reason reported when the container exists and is not running.
     */
    static final String REASON_NOT_RUNNING = "listener-not-running";

    /**
     * The reason reported when the request queue cannot be resolved at all.
     *
     * <p>⚠️ Assumptions: this is the condition the container's own running flag cannot express. A container
     * bound to a queue that does not exist starts, registers and reports itself running, then fails every
     * receive -- so without this reason the observed failure, a service healthy while consuming nothing,
     * would still be reported as healthy.</p>
     */
    static final String REASON_QUEUE_UNREACHABLE = "request-queue-unreachable";

    /**
     * The registry the inquiry container is resolved from, supplied lazily so its absence is reportable.
     *
     * <p>Assumptions: an {@link ObjectProvider} rather than the registry itself, and the difference is what
     * a context missing that bean gets. Injected directly, this bean could not be created at all in such a
     * context and the whole application would fail to refresh -- turning a reportable condition into a
     * start-up failure whose message names a framework type rather than the consumer. Through a provider,
     * the bean is created either way and the absence becomes the {@code DOWN} answer it should be, which is
     * accurate: the registry is contributed by the same queue auto-configuration that builds the container,
     * so no registry means no consumer.</p>
     */
    private final ObjectProvider<MessageListenerContainerRegistry> registry;

    /**
     * The queue client the one reachability verification is issued through.
     *
     * <p>Assumptions: the SYNCHRONOUS client {@code SqsConfig} publishes, which is the same client the
     * consumer sends its replies with. It carries that class's validated call budget, so this
     * verification inherits a bounded timeout rather than declaring one of its own.</p>
     */
    private final SqsClient sqs;

    /**
     * The configured request destination, as the listener annotation reads it.
     */
    private final String requestQueueUrl;

    /**
     * Whether the request queue has been resolved successfully at least once.
     *
     * <p>Assumptions: it latches, so exactly one queue call is made in the life of a healthy task. The
     * consequence -- a queue deleted after that call still reads reachable -- is recorded on this class as
     * a deliberate trade-off, and the alternative it avoids is far worse: a probe that resolved the queue
     * on every call would deregister every task of this service during a queue-service outage, taking the
     * account view and update paths down with a messaging fault they do not depend on.</p>
     */
    private final AtomicBoolean requestQueueReachable = new AtomicBoolean();

    /**
     * The logger the one failing verification is recorded through.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InquiryListenerHealth.class);

    /**
     * Creates the indicator over the application's listener registry and queue client.
     *
     * @param registry the provider of the registry the inquiry container is registered in; must not be
     *     {@code null}, though it may legitimately supply nothing
     * @param sqs the queue client the reachability verification is issued through; must not be
     *     {@code null}
     * @param requestQueueUrl the configured request destination, in either form the listener annotation
     *     accepts; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public InquiryListenerHealth(ObjectProvider<MessageListenerContainerRegistry> registry,
            SqsClient sqs,
            @Value("${carddemo.account.inquiry.request-queue-url}") String requestQueueUrl) {

        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.requestQueueUrl = Objects.requireNonNull(requestQueueUrl, "requestQueueUrl must not be null");
    }

    /**
     * Reports the inquiry consumer's state as a health contribution.
     *
     * <p>Assumptions: the four down conditions are reported with DIFFERENT reasons rather than one, because
     * they call for different actions: no registry is a wiring fault in this deployment's configuration, an
     * absent container means the endpoint was never registered under the expected identifier, a container
     * that is present and stopped means intake was closed after start-up, and an unreachable request queue
     * means the destination this consumer is bound to does not exist or cannot be reached. Collapsing them
     * would leave an operator with the same next step for four different causes.</p>
     *
     * <p>Assumptions: the container checks run FIRST and the queue verification last, because the container
     * checks read local state and cost nothing while the queue verification may issue a call. A stopped
     * container is reported as stopped rather than as an unreachable queue, which is the more specific of
     * the two facts.</p>
     *
     * @return the contribution, {@code UP} only when a container is registered under
     *     {@link #REQUEST_CONTAINER_ID}, is running, and its request queue has resolved; never {@code null}
     */
    @Override
    public Health health() {
        MessageListenerContainerRegistry containers = this.registry.getIfAvailable();
        if (containers == null) {
            return down(REASON_REGISTRY_UNAVAILABLE);
        }

        // WHY : Assumptions: the registry is asked for the container by IDENTIFIER rather than searched for
        //   one bound to the request queue. The identifier is this service's own stable name for the
        //   consumer, while the queue address differs per environment -- so a search on the address would
        //   have to know the configuration, and it would answer differently in two environments running the
        //   same code.
        MessageListenerContainer<?> container = containers.getContainerById(REQUEST_CONTAINER_ID);
        if (container == null) {
            return down(REASON_CONTAINER_ABSENT);
        }
        if (!container.isRunning()) {
            return down(REASON_NOT_RUNNING);
        }
        if (!requestQueueReachable()) {
            return down(REASON_QUEUE_UNREACHABLE);
        }
        return Health.up().withDetail(DETAIL_CONTAINER_ID, REQUEST_CONTAINER_ID).build();
    }

    /**
     * Reports whether the request queue has been resolved, resolving it once if it has not.
     *
     * <p>Purpose: this is the check the container's running flag cannot make. It resolves the queue's own
     * ARN, which is the cheapest attribute the queue service will answer and the same call the listener
     * framework itself makes at container start -- so the permission it needs is one a consuming task
     * demonstrably already holds.</p>
     *
     * <p>Assumptions: only an {@link SdkException} is caught, so a failure that is not the queue service
     * answering -- a programming fault in this method, for instance -- propagates and is reported as a
     * failed health check rather than as an unreachable queue.</p>
     *
     * <p>Assumptions: the failure is logged as a type chain through the shared digest and its message text
     * is withheld, exactly as the consumer's own failure path does, because the queue service's message can
     * carry the configured address and this line goes to shared log storage.</p>
     *
     * @return {@code true} when the queue resolved now or at any earlier probe
     */
    private boolean requestQueueReachable() {
        if (this.requestQueueReachable.get()) {
            return true;
        }

        try {
            this.sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(this.requestQueueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build());
            this.requestQueueReachable.set(true);
            return true;
        } catch (SdkException unreachable) {
            LOG.error("event=account.inquiry.request-queue-unreachable containerId={} failure={}",
                    REQUEST_CONTAINER_ID, ThrowableDigest.of(unreachable));
            return false;
        }
    }

    /**
     * Renders one down contribution carrying the container identifier and a reason.
     *
     * @param reason which of the four down conditions was observed; must not be {@code null}
     * @return the contribution, never {@code null}
     */
    private static Health down(String reason) {
        return Health.down()
                .withDetail(DETAIL_CONTAINER_ID, REQUEST_CONTAINER_ID)
                .withDetail(DETAIL_REASON, reason)
                .build();
    }
}
