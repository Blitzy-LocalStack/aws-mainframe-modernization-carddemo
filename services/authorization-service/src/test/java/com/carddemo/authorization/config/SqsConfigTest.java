package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.carddemo.authorization.config.SqsConfig.FifoQueueNamingContract;
import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.service.AuthorizationRequestListener;
import com.carddemo.authorization.service.OutboxPublisher;
import com.carddemo.common.messaging.MessageExpiry;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.BackPressureMode;
import io.awspring.cloud.sqs.listener.FifoSqsComponentFactory;
import io.awspring.cloud.sqs.listener.ListenerMode;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.listener.SqsContainerOptions;
import io.awspring.cloud.sqs.listener.StandardSqsComponentFactory;
import io.awspring.cloud.sqs.listener.acknowledgement.AcknowledgementOrdering;
import io.awspring.cloud.sqs.listener.acknowledgement.ImmediateAcknowledgementProcessor;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Verifies the queue-transport decisions {@link SqsConfig} makes on behalf of this context.
 *
 * <p>Assumptions: the options are read back from a container the real factory built, rather than from
 * a builder this test configured itself. The factory accumulates configuration callbacks and applies
 * them in order, so reading a builder directly would assert what the customizer was asked to do while
 * saying nothing about whether it actually got the last word over the starter's own callback -- which
 * is the whole mechanism the class depends on.</p>
 *
 * <p>Assumptions: the queue client the factory needs is a stub, because no assertion here reaches the
 * transport. Building a container requires a client to be present and never calls it, so a stub is
 * sufficient and keeps these cases independent of an emulator being up.</p>
 *
 * <p>Alternatives Considered: asserting the settings by starting a context. Rejected because this
 * module's test profile deliberately supplies no queue reference and holds the listener container
 * stopped, so a context-based assertion would either prove nothing or would require the very
 * configuration the profile exists to withhold.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class SqsConfigTest {

    /** A conforming ordered request-queue reference, in the bare-name form. */
    private static final String ORDERED_REQUEST_QUEUE = "carddemo-pauth-request-dev.fifo";

    /** A conforming ordered reply destination, in the address form a deployment supplies. */
    private static final String ORDERED_REPLY_ADDRESS =
            "https://sqs.example.invalid/000000000000/carddemo-pauth-reply-dev.fifo";

    /** The listener shutdown budget these cases configure, in seconds. */
    private static final long LISTENER_SHUTDOWN_SECONDS = 10L;

    /** The acknowledgement shutdown budget these cases configure, in seconds. */
    private static final long ACKNOWLEDGEMENT_SHUTDOWN_SECONDS = 5L;

    /** The packaged configuration document the per-phase shutdown budget is declared in. */
    private static final String BASE_CONFIGURATION = "/application.yml";

    /**
     * Builds the container options that result from applying the customizer to a real factory.
     *
     * <p>Assumptions: a first callback stands in for the starter's own, setting the three values the
     * listener annotation and the starter properties own. It is applied before the customizer so that
     * the cases below can tell a value the customizer set from one it left alone.</p>
     *
     * @param queueName the queue reference the container is built for, which selects the ordered or
     *     the unordered component set; must not be {@code null}
     * @return the options the built container carries, never {@code null}
     */
    private static SqsContainerOptions optionsFor(String queueName) {
        SqsMessageListenerContainerFactory<Object> factory =
                new SqsMessageListenerContainerFactory<>();
        factory.setSqsAsyncClient(mock(SqsAsyncClient.class));
        factory.configure(options -> options
                .pollTimeout(Duration.ofSeconds(5))
                .maxMessagesPerPoll(10)
                .maxConcurrentMessages(10));

        BeanPostProcessor customizer = SqsConfig.authorizationListenerContainerOptions(
                LISTENER_SHUTDOWN_SECONDS, ACKNOWLEDGEMENT_SHUTDOWN_SECONDS);
        Object returned = customizer.postProcessAfterInitialization(factory, "factory");
        assertThat(returned).as("the customizer returns the same factory instance")
                .isSameAs(factory);

        return factory.createContainer(queueName).getContainerOptions();
    }

    /**
     * Confirms a request-queue reference that does not name an ordered queue stops startup.
     *
     * <p>Assumptions: the failure has to NAME the property, because the operator repair is to change a
     * value and a message that described only the requirement would not say which value.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unordered request-queue reference is refused, naming the property")
    void anUnorderedRequestQueueIsRefused() {
        assertThatThrownBy(() -> new SqsConfig()
                .authorizationQueueNamingContract("carddemo-pauth-request-dev", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.pauth-request-queue")
                .hasMessageContaining(SqsConfig.FIFO_QUEUE_SUFFIX);
    }

    /**
     * Confirms an allowlisted reply destination that is not ordered stops startup.
     *
     * <p>Assumptions: this is asserted separately from the request queue because the container never
     * opens the reply queue -- a reply goes to the address its request nominated -- so the ordering
     * option the customizer applies cannot reach it. Without this case an unordered reply destination
     * would pass startup and fail later, on the reply path, for one requester only.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unordered reply destination is refused, naming the allowlist property")
    void anUnorderedReplyDestinationIsRefused() {
        assertThatThrownBy(() -> new SqsConfig().authorizationQueueNamingContract(
                ORDERED_REQUEST_QUEUE, List.of(ORDERED_REPLY_ADDRESS, "queue-without-the-suffix")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.reply-queue-allowlist");
    }

    /**
     * Confirms both authorization queue references are accepted when both name ordered queues.
     *
     * <p>Assumptions: an address form and a bare-name form are both exercised, and the suffix is also
     * exercised in upper case, because a deployment legitimately supplies any of the three and a check
     * that accepted only one shape would refuse correct configuration.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("ordered references in name, address and mixed-case form are all accepted")
    void orderedReferencesAreAcceptedAndRetained() {
        FifoQueueNamingContract contract = new SqsConfig().authorizationQueueNamingContract(
                "carddemo-pauth-request-dev.FIFO", List.of(ORDERED_REPLY_ADDRESS));

        assertThat(contract.requestQueue()).isEqualTo("carddemo-pauth-request-dev.FIFO");
        assertThat(contract.replyQueueAllowlist()).containsExactly(ORDERED_REPLY_ADDRESS);
        assertThatThrownBy(() -> contract.replyQueueAllowlist().add("another"))
                .as("the retained allowlist is not modifiable through the accessor")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms a context that configures no queue at all has nothing to verify and starts.
     *
     * <p>Assumptions: an unset comma-separated property arrives as one blank element, which is the
     * shape this case pins. Presence is required by the consumer, which injects both properties
     * without a default, so tolerating absence here does not let a deployment start without them.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unconfigured context has nothing to verify and is accepted")
    void anUnconfiguredContextIsAccepted() {
        FifoQueueNamingContract contract =
                new SqsConfig().authorizationQueueNamingContract("", List.of(""));

        assertThat(contract.requestQueue()).isEmpty();
        assertThat(contract.replyQueueAllowlist()).containsExactly("");
    }

    /**
     * Confirms the listener refuses to create a queue this context does not own.
     *
     * <p>Assumptions: the starter's own default is to create a missing queue, so this asserts a value
     * that had to be set rather than one that was inherited. What creation would produce is the wrong
     * queue in three ways at once -- unordered, unencrypted and with no redrive policy -- so a service
     * that created one would come up healthy and answer nothing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a missing queue is a startup failure rather than something to create")
    void aMissingQueueIsNeverCreated() {
        assertThat(optionsFor(ORDERED_REQUEST_QUEUE).getQueueNotFoundStrategy())
                .isEqualTo(QueueNotFoundStrategy.FAIL);
    }

    /**
     * Confirms each message is acknowledged on its own and never as part of a batch.
     *
     * <p>Assumptions: the acknowledgement PROCESSOR is asserted in addition to the two settings that
     * select it, and under both component sets. The settings alone would leave the guarantee resting on
     * the queue name, because the starter picks its component set by testing that name for the ordered
     * suffix; asserting the processor under both shows the choice no longer depends on it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("acknowledgement is immediate and per message under either component set")
    void acknowledgementIsPerMessageAndNeverBatched() {
        SqsContainerOptions options = optionsFor(ORDERED_REQUEST_QUEUE);

        assertThat(options.getAcknowledgementMode()).isEqualTo(AcknowledgementMode.ON_SUCCESS);
        assertThat(options.getAcknowledgementInterval()).isEqualTo(Duration.ZERO);
        assertThat(options.getAcknowledgementThreshold()).isZero();
        assertThat(new FifoSqsComponentFactory<>().createAcknowledgementProcessor(options))
                .isInstanceOf(ImmediateAcknowledgementProcessor.class);

        SqsContainerOptions unordered = options.toBuilder()
                .acknowledgementOrdering(AcknowledgementOrdering.ORDERED)
                .build();
        assertThat(new StandardSqsComponentFactory<>().createAcknowledgementProcessor(unordered))
                .isInstanceOf(ImmediateAcknowledgementProcessor.class);
    }

    /**
     * Confirms acknowledgement is ordered per group and that an unordered queue is refused.
     *
     * <p>Assumptions: the refusal is the mechanical half of never using an unordered queue for this
     * pair, and it is asserted through the starter rather than through this project's own check so that
     * the two are shown to agree. A request queue lacking the ordered suffix selects the unordered
     * component set, which will not build with group-ordered acknowledgement, so the misconfiguration
     * surfaces at container start instead of as quietly lost per-card ordering.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("acknowledgement is ordered per group, which an unordered queue cannot satisfy")
    void anUnorderedQueueCannotSatisfyGroupOrdering() {
        SqsContainerOptions options = optionsFor(ORDERED_REQUEST_QUEUE);

        assertThat(options.getAcknowledgementOrdering())
                .isEqualTo(AcknowledgementOrdering.ORDERED_BY_GROUP);
        assertThatThrownBy(() ->
                new StandardSqsComponentFactory<>().createAcknowledgementProcessor(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORDERED_BY_GROUP");
    }

    /**
     * Confirms every message attribute is requested, so the four this contract needs all arrive.
     *
     * <p>Assumptions: the four names are read from the components that own them rather than repeated
     * here, so this case fails if any owner renames one. It matters most for the expiry instant,
     * because that deadline is enforced by the consumer alone: an attribute that failed to arrive would
     * surface not as an error but as an expired message being acted on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all message attributes are requested and the four named ones are the contract")
    void everyMessageAttributeIsRequested() {
        assertThat(optionsFor(ORDERED_REQUEST_QUEUE).getMessageAttributeNames())
                .containsExactly("All");

        assertThat(OutboxPublisher.ATTRIBUTE_CONTENT_TYPE).isEqualTo("contentType");
        assertThat(AuthReplyOutbox.CONTENT_TYPE_CSV).isEqualTo("text/csv");
        assertThat(AuthorizationRequestListener.HEADER_CORRELATION_ID).isEqualTo("correlationId");
        assertThat(AuthorizationRequestListener.HEADER_REPLY_TO).isEqualTo("replyToQueueUrl");
        assertThat(MessageExpiry.HEADER_EXPIRES_AT).isEqualTo("expiresAt");
    }

    /**
     * Confirms the two shutdown budgets are the configured values and fit the per-phase timeout.
     *
     * <p>Assumptions: the per-phase timeout is READ from the packaged configuration rather than
     * repeated here, so lowering it in that document fails this case instead of silently leaving a
     * drain that cannot finish. The two budgets are consumed in sequence by a stopping container, so
     * their sum is what has to fit.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the two shutdown budgets are configured and their sum fits the phase timeout")
    void theShutdownBudgetsFitThePhaseTimeout() {
        SqsContainerOptions options = optionsFor(ORDERED_REQUEST_QUEUE);
        Duration listener = Duration.ofSeconds(LISTENER_SHUTDOWN_SECONDS);
        Duration acknowledgement = Duration.ofSeconds(ACKNOWLEDGEMENT_SHUTDOWN_SECONDS);

        assertThat(options.getListenerShutdownTimeout()).isEqualTo(listener);
        assertThat(options.getAcknowledgementShutdownTimeout()).isEqualTo(acknowledgement);
        assertThat(listener.plus(acknowledgement))
                .as("a stopping container consumes both budgets inside one shutdown phase")
                .isLessThanOrEqualTo(declaredShutdownPhaseTimeout());
    }

    /**
     * Confirms the customizer leaves the poll and concurrency settings it does not own alone.
     *
     * <p>Assumptions: this is the assertion that the request window and the receive wait are decided by
     * configuration and by the listener annotation rather than here. It is written against values a
     * preceding callback set, because a customizer that silently reset them would still satisfy every
     * other case in this class.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the poll wait and the concurrency settings are not decided by this configuration")
    void thePollAndConcurrencySettingsAreLeftAlone() {
        SqsContainerOptions options = optionsFor(ORDERED_REQUEST_QUEUE);

        assertThat(options.getPollTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(options.getMaxMessagesPerPoll()).isEqualTo(10);
        assertThat(options.getMaxConcurrentMessages()).isEqualTo(10);
    }

    /**
     * Confirms an empty receive cannot reach application code, so an idle queue raises nothing.
     *
     * <p>Assumptions: the reference consumer treats its no-message-available condition as the normal
     * end of a poll cycle rather than as a failure, so the migrated consumer must not report an idle
     * queue either. The invariant that guarantees it is the delivery MODE: in single-message mode the
     * handler is invoked once per message and therefore cannot be invoked at all when a receive
     * returns nothing, whereas a batch handler takes a collection and can be invoked with an empty
     * one. The handler this context registers takes a single message, so the mode is the assertion.</p>
     *
     * <p>Alternatives Considered: driving a stub client that returns an empty receive and asserting no
     * error was logged. Rejected because a container polls on its own threads, so the case would have
     * to wait for a poll that may or may not have happened, and a timing-dependent case that passes
     * when the wait was simply too short is worse than no case at all.</p>
     *
     * <p>Assumptions: the poll back-off and the maximum delay between polls are asserted to be
     * untouched alongside it, because those are the settings a configuration would reach for if it
     * treated an idle queue as a condition to react to.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an idle queue reaches no application code and changes no polling behaviour")
    void anEmptyReceiveIsNormal() {
        SqsContainerOptions options = optionsFor(ORDERED_REQUEST_QUEUE);

        assertThat(options.getListenerMode())
                .as("a single-message handler cannot be invoked by a receive that returned nothing")
                .isEqualTo(ListenerMode.SINGLE_MESSAGE);
        assertThat(options.getBackPressureMode()).isEqualTo(BackPressureMode.AUTO);
        assertThat(options.getMaxDelayBetweenPolls()).isEqualTo(Duration.ofSeconds(10));
    }

    /**
     * Confirms a bean that is not a listener-container factory is returned untouched.
     *
     * <p>Assumptions: the selection is by TYPE rather than by bean name, so this case passes an
     * unrelated bean under a name that looks like the factory's. A customizer selecting by name would
     * configure it and fail here, which is the failure this case is written to catch.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a bean that is not a listener-container factory passes through untouched")
    void anUnrelatedBeanPassesThroughUntouched() {
        BeanPostProcessor customizer = SqsConfig.authorizationListenerContainerOptions(
                LISTENER_SHUTDOWN_SECONDS, ACKNOWLEDGEMENT_SHUTDOWN_SECONDS);
        Object unrelated = new Object();

        assertThat(customizer.postProcessAfterInitialization(unrelated,
                "defaultSqsListenerContainerFactory")).isSameAs(unrelated);
        assertThat(customizer.postProcessBeforeInitialization(unrelated, "any"))
                .isSameAs(unrelated);
    }

    /**
     * Reads the per-phase shutdown budget this module's packaged configuration declares.
     *
     * <p>Assumptions: the value is parsed with the framework's own duration format rather than with a
     * hand-written parser, because the document writes it in the suffixed form the framework accepts
     * and a parser that only handled digits would read it as the wrong unit.</p>
     *
     * @return the declared per-phase shutdown timeout, never {@code null}
     * @throws UncheckedIOException if the packaged configuration cannot be read, which fails the case
     *     rather than skipping it, because an unreadable document means the assertion proved nothing
     */
    private static Duration declaredShutdownPhaseTimeout() {
        try (InputStream configuration =
                SqsConfigTest.class.getResourceAsStream(BASE_CONFIGURATION)) {
            assertThat(configuration).as("the packaged %s must be readable", BASE_CONFIGURATION)
                    .isNotNull();
            Map<String, Object> document = new Yaml().load(configuration);
            Object spring = document.get("spring");
            assertThat(spring).as("spring section of %s", BASE_CONFIGURATION)
                    .isInstanceOf(Map.class);
            Object lifecycle = asMap(spring).get("lifecycle");
            assertThat(lifecycle).as("spring.lifecycle section of %s", BASE_CONFIGURATION)
                    .isInstanceOf(Map.class);
            Object declared = asMap(lifecycle).get("timeout-per-shutdown-phase");
            assertThat(declared).as("spring.lifecycle.timeout-per-shutdown-phase").isNotNull();
            return DurationStyle.detectAndParse(declared.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + BASE_CONFIGURATION, unreadable);
        }
    }

    /**
     * Narrows a parsed configuration node to a map of its child keys.
     *
     * @param node a configuration node already asserted to be a mapping; must not be {@code null}
     * @return the node's child keys, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }
}
