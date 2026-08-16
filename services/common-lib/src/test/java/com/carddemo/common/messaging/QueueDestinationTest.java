package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts what a configured queue destination must look like, and what a queue's name is within one.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: the two inquiry consumers in this repository each accepted any non-blank string
 * as a reply or error destination and then handed it to the queue service as a queue NAME. The deployment
 * supplies a queue URL -- every one of the six task-definition variables is set from a
 * {@code module.sqs.*_queue_url} output -- so each consumer asked the service to look up a queue whose name
 * was an address. It could only fail, and it failed on the reply and diagnostic paths alone, which a request
 * reaches only after it has already been taken off the request queue: requests were consumed and never
 * answered while start-up, health and the request side all looked correct. These cases pin the rule that now
 * refuses that configuration before a single request is taken.</p>
 *
 * <p>Assumptions: the addresses below cover the three host forms this system legitimately receives, because
 * the rule has to admit all three and refuse a bare name. The public endpoint is what a deployed environment
 * publishes; the two emulator forms are what the local runner and the emulator-backed tests publish; and a
 * bare name is the value the defect treated as valid.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class QueueDestinationTest {

    /**
     * The property name every refusal below is expected to quote back.
     *
     * <p>Assumptions: a single constant, because the point being asserted is that the refusal names the
     * setting an operator has to correct -- not which setting it happens to be in any one case.</p>
     */
    private static final String PROPERTY = "carddemo.reference.inquiry.reply-queue-url";

    /**
     * Confirms the address a deployed environment publishes is accepted and returned unchanged.
     */
    @Test
    @DisplayName("a public queue endpoint address is accepted verbatim")
    void aPublicEndpointAddressIsAccepted() {
        String address = "https://sqs.eu-west-1.amazonaws.com/111122223333/carddemo-inquiry-reply-prod";

        assertThat(QueueDestination.requireQueueUrl(address, PROPERTY)).isEqualTo(address);
    }

    /**
     * Confirms both address forms the local emulator publishes are accepted.
     *
     * <p>Assumptions: plain HTTP and a port are both admitted deliberately. A rule that demanded HTTPS would
     * pass only in a deployed environment, which is the one place a misconfiguration is most expensive to
     * discover, and it would refuse every address the test suite and the local runner produce.</p>
     */
    @Test
    @DisplayName("both emulator address forms, plain HTTP and a port, are accepted")
    void emulatorAddressesAreAccepted() {
        String subdomainForm =
                "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/carddemo-inquiry-reply";
        String loopbackForm = "http://localhost:4566/000000000000/carddemo-inquiry-reply";

        assertThat(QueueDestination.requireQueueUrl(subdomainForm, PROPERTY)).isEqualTo(subdomainForm);
        assertThat(QueueDestination.requireQueueUrl(loopbackForm, PROPERTY)).isEqualTo(loopbackForm);
    }

    /**
     * Confirms surrounding whitespace is removed and the trimmed address is what a caller receives.
     *
     * <p>Assumptions: a destination arriving from a task-definition variable or a YAML scalar can carry
     * surrounding whitespace, and an address differing from the published one only by a trailing space would
     * fail every publication for a reason no log line would explain.</p>
     */
    @Test
    @DisplayName("an address is trimmed before it is judged and the trimmed value is returned")
    void surroundingWhitespaceIsRemoved() {
        String address = "https://sqs.eu-west-1.amazonaws.com/111122223333/carddemo-error-prod";

        assertThat(QueueDestination.requireQueueUrl("  " + address + "\n", PROPERTY)).isEqualTo(address);
    }

    /**
     * Confirms a bare queue name is refused, which is the exact defect this rule exists to stop.
     *
     * <p>Assumptions: the refusal must name both the property and the offending value. The value is safe to
     * quote here in a way wire content would not be, because it came from the deployment's own configuration
     * and an operator cannot correct an address they are not shown.</p>
     */
    @Test
    @DisplayName("a bare queue name is refused, naming the property and the value")
    void aBareQueueNameIsRefused() {
        assertThatThrownBy(() -> QueueDestination.requireQueueUrl("carddemo-inquiry-reply-dev", PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("carddemo-inquiry-reply-dev");
    }

    /**
     * Confirms an address naming a host but no account and queue is refused.
     *
     * <p>Assumptions: this is the case a length or prefix check would let through. Requiring two path
     * segments is what separates a queue address from a host, and asserting it here is what keeps the rule
     * from being weakened to a scheme test.</p>
     */
    @Test
    @DisplayName("a host-only address, and one naming only an account, are both refused")
    void anAddressNamingNoQueueIsRefused() {
        assertThatThrownBy(() -> QueueDestination.requireQueueUrl("https://sqs.eu-west-1.amazonaws.com",
                PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY);

        assertThatThrownBy(() -> QueueDestination.requireQueueUrl(
                "https://sqs.eu-west-1.amazonaws.com/111122223333", PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY);
    }

    /**
     * Confirms a scheme this system never receives a queue address under is refused.
     */
    @Test
    @DisplayName("a non-HTTP scheme and a scheme-less path are both refused")
    void aNonHttpSchemeIsRefused() {
        assertThatThrownBy(() -> QueueDestination.requireQueueUrl(
                "ftp://sqs.eu-west-1.amazonaws.com/111122223333/carddemo-error-prod", PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY);

        assertThatThrownBy(() -> QueueDestination.requireQueueUrl("/111122223333/carddemo-error-prod",
                PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY);
    }

    /**
     * Confirms a blank destination is refused with the argument that names the consequence.
     *
     * <p>Assumptions: blank is separated from malformed because the two are different operator mistakes -- a
     * variable that was never set against one that was set wrongly -- and a single message covering both
     * would name neither cause.</p>
     */
    @Test
    @DisplayName("an empty or whitespace-only destination is refused")
    void aBlankDestinationIsRefused() {
        assertThatThrownBy(() -> QueueDestination.requireQueueUrl("   ", PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY);
    }

    /**
     * Confirms a null destination and a null property name are each refused by name.
     */
    @Test
    @DisplayName("a null destination names the property, and a null property is refused on its own")
    void nullArgumentsAreRefused() {
        assertThatThrownBy(() -> QueueDestination.requireQueueUrl(null, PROPERTY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(PROPERTY);

        assertThatThrownBy(() -> QueueDestination.requireQueueUrl("https://host/1/q", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("property");
    }

    /**
     * Confirms a malformed address is refused rather than accepted and left to fail on first publication.
     */
    @Test
    @DisplayName("an address the platform cannot parse is refused at the point of configuration")
    void anUnparseableAddressIsRefused() {
        assertThatThrownBy(() -> QueueDestination.requireQueueUrl("http://host:port/1/queue", PROPERTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY);
    }

    /**
     * Confirms the queue name recovered from an address is its last path segment.
     *
     * <p>Assumptions: this is what makes a diagnostic's declared forty-eight-character queue-name field
     * truthful. The baseline puts a queue NAME there, having no addresses to put anywhere, so an address in
     * that field would be both the wrong kind of value and long enough to be truncated mid-host.</p>
     */
    @Test
    @DisplayName("the queue name is the address's last path segment, for every host form")
    void theQueueNameIsTheLastPathSegment() {
        assertThat(QueueDestination.queueNameOf(
                "https://sqs.eu-west-1.amazonaws.com/111122223333/carddemo-error-prod"))
                .isEqualTo("carddemo-error-prod");
        assertThat(QueueDestination.queueNameOf(
                "http://localhost:4566/000000000000/carddemo-pauth-reply-local.fifo"))
                .isEqualTo("carddemo-pauth-reply-local.fifo");
    }

    /**
     * Confirms recovering a name from a value that names no queue is refused rather than answered emptily.
     *
     * <p>Assumptions: an empty answer would be worse than a refusal, because it would put a blank field in a
     * diagnostic and read as a queue whose name happens to be blank. A value in this state can only mean it
     * never went through the shape check, which is a defect in the caller rather than a configuration fault.</p>
     */
    @Test
    @DisplayName("recovering a name from a value that names no queue is refused")
    void recoveringANameFromAnUnvalidatedValueIsRefused() {
        assertThatThrownBy(() -> QueueDestination.queueNameOf("https://sqs.eu-west-1.amazonaws.com/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueDestination.queueNameOf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("queueUrl");
    }
}
