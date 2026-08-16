package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Holds the inquiry consumer's health contribution to reporting the consumer's ACTUAL state.
 *
 * <p><b>Purpose.</b> This service reported {@code UP} while its inquiry consumer was dead: nothing about
 * the listener container reached the health endpoint, so a request queue that did not exist, a refused
 * queue-attribute lookup and a container that stopped after start-up all produced the same answer as a
 * working service. Requests then accumulated unanswered, every requester waited for a reply that would
 * never arrive, and the orchestrator kept the task in service because the task said it was fine. These
 * cases pin each of the four states apart, because the whole value of the signal is that a dead consumer
 * is distinguishable from a live one.</p>
 *
 * <p>Assumptions: the registry is substituted rather than built, and no queue is contacted. The
 * indicator's whole contract is that it reads a LOCAL running flag -- a probe that called the queue
 * service would bill a request per poll per task and would report the queue service's availability as this
 * service's health -- so a case that needed a queue would be asserting a behaviour the class deliberately
 * does not have.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
@DisplayName("Inquiry listener health: a dead consumer must not report UP")
class InquiryListenerHealthTest {

    /** The configured request destination every case builds the indicator over. */
    private static final String REQUEST_URL =
            "https://sqs.test.invalid/000000000000/account-test-request";

    /**
     * Reports UP with the container identifier when the container is registered and running.
     *
     * <p>Assumptions: the identifier is asserted on the UP answer too, not only on the down ones, because
     * an operator reading two environments' health output has to be able to tell that both are reporting
     * the same component.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a running container over a reachable queue reports UP, naming the container")
    void aRunningContainerReportsUp() {
        Health health = indicator(container(true), reachableQueue()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry(InquiryListenerHealth.DETAIL_CONTAINER_ID,
                        InquiryListenerHealth.REQUEST_CONTAINER_ID)
                .doesNotContainKey(InquiryListenerHealth.DETAIL_REASON);
    }

    /**
     * Reports DOWN when the container is registered and has stopped.
     *
     * <p>Purpose: this is the state the QA observation was taken in -- a consumer that is registered and
     * consuming nothing -- and the state that used to be indistinguishable from a healthy one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stopped container reports DOWN with the not-running reason")
    void aStoppedContainerReportsDown() {
        Health health = indicator(container(false), reachableQueue()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry(InquiryListenerHealth.DETAIL_REASON,
                        InquiryListenerHealth.REASON_NOT_RUNNING);
    }

    /**
     * Reports DOWN when the registry holds no container under the expected identifier.
     *
     * <p>Purpose: this is what an identifier mismatch between the annotation and the indicator's constant
     * produces, and it is reported with its own reason so an operator is not sent to look for a stopped
     * container that does not exist. The identifier agreement itself is asserted from the annotation by
     * {@code InquiryMessageListenerTest}; this case asserts that the mismatch is at least visible.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unregistered container reports DOWN with the absent reason")
    void anUnregisteredContainerReportsDown() {
        MessageListenerContainerRegistry registry = mock(MessageListenerContainerRegistry.class);
        when(registry.getContainerById(InquiryListenerHealth.REQUEST_CONTAINER_ID)).thenReturn(null);

        Health health = new InquiryListenerHealth(provider(registry), reachableQueue(), REQUEST_URL)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry(InquiryListenerHealth.DETAIL_REASON,
                        InquiryListenerHealth.REASON_CONTAINER_ABSENT);
    }

    /**
     * Reports DOWN, rather than raising, when the context holds no listener registry at all.
     *
     * <p>Purpose: the registry is contributed by the same queue auto-configuration that builds the
     * container, so its absence means there is no consumer -- which is a health answer and not a start-up
     * failure. Injecting the registry directly would have made this bean uncreatable in such a context and
     * would have failed the whole application refresh with a message naming a framework type.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no listener registry reports DOWN rather than failing")
    void noRegistryReportsDown() {
        Health health = new InquiryListenerHealth(provider(null), reachableQueue(), REQUEST_URL)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry(InquiryListenerHealth.DETAIL_REASON,
                        InquiryListenerHealth.REASON_REGISTRY_UNAVAILABLE);
    }

    /**
     * Refuses construction without a provider, because a silent null would report UP forever.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("construction without a registry provider is refused")
    void constructionWithoutAProviderIsRefused() {
        SqsClient queue = reachableQueue();
        assertThatThrownBy(() -> new InquiryListenerHealth(null, queue, REQUEST_URL))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }

    /**
     * A running container whose request queue does not exist reports DOWN.
     *
     * <p>⚠️ Purpose: this is the reproduction the container's own running flag cannot answer, and the one
     * the observation was taken from. A container bound to a queue that does not exist STARTS, registers
     * and reports itself running; it then fails every receive. So a case asserting only the running flag
     * would pass while the service reported a dead consumer as healthy — which is exactly what happened.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a running container over a queue that does not exist reports DOWN")
    void anUnreachableRequestQueueReportsDown() {
        Health health = indicator(container(true), absentQueue()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry(InquiryListenerHealth.DETAIL_REASON,
                        InquiryListenerHealth.REASON_QUEUE_UNREACHABLE);
    }

    /**
     * The reachability verification happens ONCE and then never again.
     *
     * <p>Purpose: the latch is what keeps this signal from coupling the synchronous account API's health to
     * the queue service's availability. A probe that resolved the queue on every call would deregister
     * every task of this service during a messaging outage and take the account view and update paths down
     * with a fault they do not depend on. Three probes must therefore produce exactly one queue call.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the queue is resolved once, however many times health is polled")
    void theQueueIsResolvedOnce() {
        SqsClient queue = reachableQueue();
        InquiryListenerHealth indicator = indicator(container(true), queue);

        for (int probe = 0; probe < 3; probe++) {
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }

        org.mockito.Mockito.verify(queue, org.mockito.Mockito.times(1))
                .getQueueAttributes(org.mockito.ArgumentMatchers.any(GetQueueAttributesRequest.class));
    }

    /**
     * A queue created after start-up recovers the signal without a restart.
     *
     * <p>Purpose: the verdict is cached only once it SUCCEEDS. A latch set on the first attempt whatever its
     * outcome would leave a task reporting a dead consumer for the rest of its life over a transient
     * start-up failure, which would cost a task replacement for a condition that had already cleared.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a queue that appears later recovers the signal on the next probe")
    void aQueueThatAppearsLaterRecovers() {
        SqsClient queue = mock(SqsClient.class);
        when(queue.getQueueAttributes(org.mockito.ArgumentMatchers.any(GetQueueAttributesRequest.class)))
                .thenThrow(QueueDoesNotExistException.builder().message("absent").build())
                .thenReturn(GetQueueAttributesResponse.builder().build());
        InquiryListenerHealth indicator = indicator(container(true), queue);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    /**
     * A stopped container is reported as stopped rather than as an unreachable queue, and costs no call.
     *
     * <p>Purpose: the two facts are checked in a fixed order, and the order is what makes the reported
     * reason the more specific of the two. It also means the free local check short-circuits the one that
     * can issue a call.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stopped container short-circuits the queue verification")
    void aStoppedContainerShortCircuitsTheVerification() {
        SqsClient queue = reachableQueue();

        Health health = indicator(container(false), queue).health();

        assertThat(health.getDetails())
                .containsEntry(InquiryListenerHealth.DETAIL_REASON,
                        InquiryListenerHealth.REASON_NOT_RUNNING);
        org.mockito.Mockito.verify(queue, org.mockito.Mockito.never())
                .getQueueAttributes(org.mockito.ArgumentMatchers.any(GetQueueAttributesRequest.class));
    }

    /**
     * Builds a queue client that resolves the request queue.
     *
     * @return the client, never {@code null}
     */
    private static SqsClient reachableQueue() {
        SqsClient queue = mock(SqsClient.class);
        when(queue.getQueueAttributes(org.mockito.ArgumentMatchers.any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder().build());
        return queue;
    }

    /**
     * Builds a queue client that refuses the request queue as non-existent.
     *
     * <p>Assumptions: the refusal is the queue service's own exception type rather than a generic one, so
     * the case exercises the same class of failure a missing queue actually produces.</p>
     *
     * @return the client, never {@code null}
     */
    private static SqsClient absentQueue() {
        SqsClient queue = mock(SqsClient.class);
        when(queue.getQueueAttributes(org.mockito.ArgumentMatchers.any(GetQueueAttributesRequest.class)))
                .thenThrow(QueueDoesNotExistException.builder()
                        .message("the specified queue does not exist")
                        .statusCode(400)
                        .build());
        return queue;
    }

    /**
     * Builds the indicator over a registry answering one container under the expected identifier.
     *
     * @param container the container the registry answers with; must not be {@code null}
     * @param queue the queue client the reachability verification is issued through; must not be
     *     {@code null}
     * @return the indicator, never {@code null}
     */
    private static InquiryListenerHealth indicator(MessageListenerContainer<Object> container,
            SqsClient queue) {
        MessageListenerContainerRegistry registry = mock(MessageListenerContainerRegistry.class);

        // WHY : Assumptions: the stub is registered through doReturn rather than through when, because the
        //   registry's own signature answers a wildcard-parameterised container and a when-stub cannot be
        //   given one -- the capture of that wildcard is not assignable to itself. doReturn takes the value
        //   untyped, which is the standard remedy and costs nothing here: the value is a container this
        //   method built, so there is no type to get wrong.
        org.mockito.Mockito.doReturn(container).when(registry)
                .getContainerById(InquiryListenerHealth.REQUEST_CONTAINER_ID);
        return new InquiryListenerHealth(provider(registry), queue, REQUEST_URL);
    }

    /**
     * Builds a substituted container reporting the running state named.
     *
     * <p>Assumptions: the unchecked cast the mock forces is suppressed at the narrowest possible scope --
     * one factory method producing one substituted container -- because a mock of a generic interface is
     * raw and there is no expressible type argument that would make it checked. Nothing about the class
     * under test depends on the parameter, which reads only the container's running state.</p>
     *
     * @param running whether the container reports itself running
     * @return the container, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static MessageListenerContainer<Object> container(boolean running) {
        MessageListenerContainer<Object> container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(running);
        return container;
    }

    /**
     * Wraps one registry, or its absence, as the provider the indicator takes.
     *
     * <p>Assumptions: a hand-written provider rather than a mock, because only one of its methods is
     * exercised and a mock would answer the others with nulls that a later change could read as meaningful.
     * Every other method is refused outright, so a reader can see that the class under test uses exactly
     * one of them.</p>
     *
     * @param registry the registry to supply, or {@code null} to supply none
     * @return the provider, never {@code null}
     */
    private static ObjectProvider<MessageListenerContainerRegistry> provider(
            MessageListenerContainerRegistry registry) {

        return new ObjectProvider<>() {

            @Override
            public MessageListenerContainerRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MessageListenerContainerRegistry getObject() {
                throw new UnsupportedOperationException("the indicator must ask if one is AVAILABLE");
            }

            @Override
            public MessageListenerContainerRegistry getObject(Object... args) {
                throw new UnsupportedOperationException("the indicator must ask if one is AVAILABLE");
            }

            @Override
            public MessageListenerContainerRegistry getIfUnique() {
                throw new UnsupportedOperationException("the indicator must ask if one is AVAILABLE");
            }
        };
    }
}
