package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.carddemo.common.messaging.RethrowingDigestErrorHandler;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.authorization.config.SqsConfig.FifoQueueNamingContract;
import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.service.AuthorizationRequestListener;
import com.carddemo.authorization.service.OutboxPublisher;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.QueueClientBudget;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.BackPressureMode;
import io.awspring.cloud.sqs.listener.FifoSqsComponentFactory;
import io.awspring.cloud.sqs.listener.ListenerMode;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.listener.SqsContainerOptions;
import io.awspring.cloud.sqs.listener.ListenerExecutionFailedException;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.StandardSqsComponentFactory;
import io.awspring.cloud.sqs.listener.acknowledgement.AcknowledgementOrdering;
import io.awspring.cloud.sqs.listener.acknowledgement.ImmediateAcknowledgementProcessor;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

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

    /** The transport identifier the failure-record cases expect to see named. */
    private static final String TRANSPORT_MESSAGE_ID = "d3f4a1b2-0000-4000-8000-000000000001";

    /**
     * A failure message composed of the kind of material a record must withhold.
     *
     * <p>Assumptions: this stands in for both shapes the real thing takes -- a transport fault composes
     * an endpoint and credential material into its message, and a validation fault composes field
     * values -- so a case asserting this string is absent is asserting that neither shape leaks.</p>
     */
    private static final String FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED =
            "connect failed to https://sqs.example.invalid using key AKIAEXAMPLEKEY";

    /**
     * The card number the request queue groups by, which is therefore the group identifier.
     *
     * <p>Assumptions: this value being a primary account number is the whole reason a record must not
     * name the group identifier, so the cases set it as the group header and then assert its absence.</p>
     */
    private static final String GROUPING_CARD_NUMBER = "4111111111111111";

    /** A payload standing in for the request record, which carries protected values by contract. */
    private static final String PAYLOAD_THAT_MUST_NOT_BE_RECORDED =
            GROUPING_CARD_NUMBER + ",TXN000000000001,000000012345";

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
                LISTENER_SHUTDOWN_SECONDS, ACKNOWLEDGEMENT_SHUTDOWN_SECONDS,
                declaredShutdownPhaseTimeout());
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
     * Confirms the two accepted shapes of a listener reference are accepted and retained unchanged.
     *
     * <p>Refactoring Rationale: this case asserted that an UPPER-CASE suffix was accepted, on the stated
     * ground that a deployment legitimately supplies any of three shapes. That was the defect written down
     * as a requirement. The transport's suffix is lower case and the listener starter tests for it
     * case-sensitively when it chooses between its ordered and unordered message sources, so a reference
     * ending {@code .FIFO} passed the check and then selected the UNORDERED components -- losing per-card
     * ordering with nothing to observe. The upper-case form is now asserted to be REFUSED, in the sibling
     * case below.</p>
     *
     * <p>Assumptions: a bare name and an address are both exercised here because the listener starter
     * genuinely resolves either, and a check that accepted only one would refuse correct configuration.
     * The reply allowlist is deliberately not given the same latitude: its entries are sent to, and a send
     * accepts an address only.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a listener reference is accepted as a bare name or as an address, and retained")
    void orderedReferencesAreAcceptedAndRetained() {
        FifoQueueNamingContract contract = new SqsConfig().authorizationQueueNamingContract(
                ORDERED_REQUEST_QUEUE, List.of(ORDERED_REPLY_ADDRESS));

        assertThat(contract.requestQueue()).isEqualTo(ORDERED_REQUEST_QUEUE);
        assertThat(contract.replyQueueAllowlist()).containsExactly(ORDERED_REPLY_ADDRESS);

        FifoQueueNamingContract addressed = new SqsConfig().authorizationQueueNamingContract(
                ORDERED_REPLY_ADDRESS, List.of(ORDERED_REPLY_ADDRESS));

        assertThat(addressed.requestQueue())
                .as("an address is a shape the listener starter resolves too")
                .isEqualTo(ORDERED_REPLY_ADDRESS);
        assertThatThrownBy(() -> contract.replyQueueAllowlist().add("another"))
                .as("the retained allowlist is not modifiable through the accessor")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms an upper-case ordered-queue suffix is refused in either position.
     *
     * <p>Assumptions: both references are exercised, because the two are validated by different rules and
     * a suffix relaxed in one of them is as harmful as in both. The listener reference selects the
     * starter's ordered components by this suffix; the reply address is what a send is issued against, and
     * the transport's own name for the queue is lower case.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an upper-case .FIFO suffix is refused in both the listener and the reply position")
    void anUpperCaseOrderedSuffixIsRefused() {
        assertThatThrownBy(() -> new SqsConfig().authorizationQueueNamingContract(
                "carddemo-pauth-request-dev.FIFO", List.of(ORDERED_REPLY_ADDRESS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.pauth-request-queue")
                .hasMessageContaining("lower-case");

        assertThatThrownBy(() -> new SqsConfig().authorizationQueueNamingContract(
                ORDERED_REQUEST_QUEUE,
                List.of("https://sqs.us-east-1.amazonaws.com/000000000000/reply-dev.FIFO")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.reply-queue-allowlist");
    }

    /**
     * Confirms a reply destination that is not an address is refused even when it is ordered.
     *
     * <p>Assumptions: a bare name and a resource name are both exercised, and both are ordered-looking, so
     * the case isolates the SHAPE from the suffix. Every allowlist entry is passed unchanged as the
     * destination of a send by the outbox drain, and a send resolves an address only -- so either shape
     * would pass startup and then fail every reply to the requester that nominated it, on the reply path,
     * for one requester, which is the least observable place to fail.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a bare name and a resource name are refused as reply destinations")
    void aReplyDestinationThatIsNotAnAddressIsRefused() {
        assertThatThrownBy(() -> new SqsConfig().authorizationQueueNamingContract(
                ORDERED_REQUEST_QUEUE, List.of("carddemo-pauth-reply-dev.fifo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.reply-queue-allowlist")
                .hasMessageContaining(SqsConfig.QUEUE_URL_SCHEME);

        assertThatThrownBy(() -> new SqsConfig().authorizationQueueNamingContract(
                ORDERED_REQUEST_QUEUE,
                List.of("arn:aws:sqs:us-east-1:000000000000:carddemo-pauth-reply-dev.fifo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.reply-queue-allowlist");
    }

    /**
     * Confirms a resource name is refused as the listener reference.
     *
     * <p>Assumptions: the reference is ordered-looking, so this case isolates the shape. The listener
     * starter resolves a bare name or an address and neither resolves a resource name, so a deployment
     * configuring one would start and then receive nothing at all -- which is a silent halt of the whole
     * flow rather than an error, and exactly what a startup check exists to convert into a message naming
     * the property.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a resource name is refused as the listener queue reference")
    void aResourceNameIsRefusedAsTheListenerReference() {
        assertThatThrownBy(() -> new SqsConfig().authorizationQueueNamingContract(
                "arn:aws:sqs:us-east-1:000000000000:carddemo-pauth-request-dev.fifo",
                List.of(ORDERED_REPLY_ADDRESS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.messaging.pauth-request-queue")
                .hasMessageContaining("resource name");
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
     * Confirms a non-positive listener drain budget stops startup, naming the property.
     *
     * <p>Purpose: a container that waits no time for its in-flight messages abandons every one of them,
     * and the acknowledgements it never sends make the queue redeliver work that had already completed.
     * That is indistinguishable in a log from ordinary redelivery, which is why the value is refused at
     * startup rather than left to be inferred from duplicated processing.</p>
     *
     * <p>Assumptions: zero and a negative are both exercised, because zero is what a property set to an
     * empty or mistyped value converts to while a negative is what a hand-edited document produces, and a
     * check written as {@code &lt; 0} would admit the first of them.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-positive listener drain budget is refused, naming the property")
    void aNonPositiveListenerDrainBudgetIsRefused() {
        for (long invalid : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> SqsConfig.authorizationListenerContainerOptions(
                    invalid, ACKNOWLEDGEMENT_SHUTDOWN_SECONDS, declaredShutdownPhaseTimeout()))
                    .as("listener drain budget of %d seconds", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.messaging.listener-shutdown-timeout-seconds");
        }
    }

    /**
     * Confirms a non-positive acknowledgement drain budget stops startup, naming the property.
     *
     * <p>Assumptions: this is asserted separately from the listener budget rather than parameterised with
     * it, because the two are distinct properties with distinct consequences -- this one discards the
     * acknowledgements of messages that already SUCCEEDED -- and a single case covering both would pass
     * against an implementation that validated only one of them.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-positive acknowledgement drain budget is refused, naming the property")
    void aNonPositiveAcknowledgementDrainBudgetIsRefused() {
        for (long invalid : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> SqsConfig.authorizationListenerContainerOptions(
                    LISTENER_SHUTDOWN_SECONDS, invalid, declaredShutdownPhaseTimeout()))
                    .as("acknowledgement drain budget of %d seconds", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "carddemo.messaging.acknowledgement-shutdown-timeout-seconds");
        }
    }

    /**
     * Confirms two individually valid drain budgets whose SUM exceeds the shutdown phase are refused.
     *
     * <p>Purpose: a stopping container spends the two budgets in sequence -- in-flight messages first,
     * outstanding acknowledgements afterwards -- so neither alone is what has to fit inside the phase the
     * platform allows. This is the case that distinguishes a summed comparison from two individual ones:
     * both budgets here are shorter than the phase and their sum is not.</p>
     *
     * <p>Assumptions: the phase timeout is taken from the packaged configuration and the two budgets are
     * derived from it, so the case states the RELATIONSHIP rather than three numbers -- changing the
     * declared phase in that document cannot make this case vacuous.</p>
     *
     * <p>Assumptions: the equal case is asserted to be accepted alongside the excess, because a drain
     * that exactly fills the phase completes within it. A check written with the wrong comparison would
     * fail exactly one of the two halves below.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("two drain budgets that individually fit but together do not are refused")
    void anOverBudgetDrainSumIsRefused() {
        long phaseSeconds = declaredShutdownPhaseTimeout().toSeconds();
        long each = phaseSeconds - 1L;

        assertThatThrownBy(() -> SqsConfig.authorizationListenerContainerOptions(
                each, each, declaredShutdownPhaseTimeout()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.lifecycle.timeout-per-shutdown-phase");

        assertThat(SqsConfig.authorizationListenerContainerOptions(
                phaseSeconds - 1L, 1L, declaredShutdownPhaseTimeout()))
                .as("a drain that exactly fills the phase completes inside it")
                .isNotNull();
    }

    /**
     * Confirms the whole per-message budget is compared against the visibility period at startup.
     *
     * <p>Purpose: this consumer's handler makes three account-context round trips and then does database
     * work, and every one of those has its own bound. Each bound can be individually reasonable while
     * their SUM outlives the period the queue keeps the message invisible -- at which point the queue
     * redelivers, a second consumer takes the request, and two handlers decide one authorization at once.
     * Nothing else in this context can see every bound together, so the comparison lives here and this is
     * the case that proves it happens.</p>
     *
     * <p>Assumptions: the client-builder configurer is asserted to be UNTOUCHED. That is what places the
     * refusal ahead of client construction, so the context fails to refresh naming the relationship that
     * does not hold rather than starting with a client that cannot meet its deadline.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a summed handler budget reaching the visibility period is refused at startup")
    void aSummedHandlerBudgetReachingVisibilityIsRefused() {
        AwsClientBuilderConfigurer configurer = mock(AwsClientBuilderConfigurer.class);
        SqsConfig config = new SqsConfig();

        assertThatThrownBy(() -> config.sqsClient(configurer, 10_000L, 5_000L, 60L,
                2_000L, 3_000L, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QueueClientBudget.PROPERTY_VISIBILITY_TIMEOUT)
                .hasMessageContaining("carddemo.datasource.read-timeout-ms");
        verifyNoInteractions(configurer);
    }

    /**
     * Confirms the configured bounds reach the built client rather than only being validated.
     *
     * <p>Assumptions: the bounds are read back off the CLIENT, not off the budget, because the class
     * under test could satisfy every other case here by validating the values and then never applying
     * them -- which is exactly the state the client was in before, validated nowhere and bounded
     * nowhere.</p>
     *
     * <p>Assumptions: the stubbed configurer stands in for the starter's own, supplying only the region
     * and credentials a builder needs in order to build. It returns the SAME builder it was handed, so
     * the assertion below observes the override the class under test applied on top of it -- which is
     * what makes the consumer form of the override, rather than the replacing value form, observable.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the whole-call and per-attempt bounds are applied to the built client")
    void theConfiguredBoundsReachTheBuiltClient() {
        SqsClient client = new SqsConfig().sqsClient(buildableConfigurer(), 10_000L, 5_000L, 60L,
                2_000L, 3_000L, 30_000L);

        assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                .contains(Duration.ofSeconds(10));
        assertThat(client.serviceClientConfiguration().overrideConfiguration()
                .apiCallAttemptTimeout()).contains(Duration.ofSeconds(5));
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
                LISTENER_SHUTDOWN_SECONDS, ACKNOWLEDGEMENT_SHUTDOWN_SECONDS,
                declaredShutdownPhaseTimeout());
        Object unrelated = new Object();

        assertThat(customizer.postProcessAfterInitialization(unrelated,
                "defaultSqsListenerContainerFactory")).isSameAs(unrelated);
        assertThat(customizer.postProcessBeforeInitialization(unrelated, "any"))
                .isSameAs(unrelated);
    }

    /**
     * Confirms a recorded listener failure is still re-raised, and re-raised as the same failure.
     *
     * <p>Assumptions: identity is asserted rather than type, because the container's error stage turns a
     * handler's normal completion into a SUCCESS carrying the message and would then acknowledge and
     * delete it. A handler that logged and returned would pass a type assertion on nothing at all, so
     * the case asserts that the very instance handed in comes back out.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a recorded listener failure is re-raised as the same instance, never swallowed")
    void aRecordedListenerFailureIsReRaised() {
        ErrorHandler<Object> handler = new SqsConfig().authorizationListenerErrorHandler();
        RuntimeException fault = new IllegalArgumentException(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED);

        assertThatThrownBy(() -> handler.handle(deliveryWith(TRANSPORT_MESSAGE_ID, "2"), fault))
                .as("returning normally here would acknowledge a message whose transaction rolled back")
                .isSameAs(fault);
    }

    /**
     * Confirms the failure record names the transport and withholds the payload, the group and the text.
     *
     * <p>Assumptions: the group identifier is asserted absent because it IS the card number -- the
     * request queue is grouped by card to preserve per-card ordering -- so a record naming it would
     * write cardholder data into the log stream. The failure's own message is asserted absent for the
     * same class of reason: a transport fault composes endpoint and credential material into it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failure record names the transport identity and never the group, payload or text")
    void aFailureRecordWithholdsEveryProtectedValue() {
        ErrorHandler<Object> handler = new SqsConfig().authorizationListenerErrorHandler();
        RuntimeException fault = new IllegalStateException(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED);

        List<String> recorded = recordsFrom(Level.ERROR, () ->
                assertThatThrownBy(() -> handler.handle(deliveryWith(TRANSPORT_MESSAGE_ID, "4"), fault))
                        .isSameAs(fault));

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0))
                .contains(RethrowingDigestErrorHandler.EVENT)
                .contains("source=" + SqsConfig.LISTENER_SOURCE)
                .contains("messageId=" + TRANSPORT_MESSAGE_ID)
                .contains("receiveCount=4")
                .contains(IllegalStateException.class.getName())
                .doesNotContain(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED)
                .doesNotContain(GROUPING_CARD_NUMBER)
                .doesNotContain(PAYLOAD_THAT_MUST_NOT_BE_RECORDED);
    }

    /**
     * Confirms a deliberate window deferral is not recorded as a fault, yet is still re-raised.
     *
     * <p>Assumptions: the deferral is constructed reflectively. Its constructor is deliberately
     * package-private so that nothing outside the listener can fabricate one, and widening it to let a
     * test call it would remove that protection for the benefit of this case alone.</p>
     *
     * <p>Assumptions: the capture runs at DEBUG so both a deferral record and a fault record would be
     * visible, which is what lets the case assert that the fault record is absent rather than merely
     * filtered out by the level.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws ReflectiveOperationException if the deferral cannot be constructed, which fails the case
     *     rather than skipping it, because a case that could not raise the condition proved nothing
     */
    @Test
    @DisplayName("a window deferral is recorded as a deferral and never as a fault")
    void aWindowDeferralIsNotRecordedAsAFault() throws ReflectiveOperationException {
        ErrorHandler<Object> handler = new SqsConfig().authorizationListenerErrorHandler();
        RuntimeException deferral = newWindowDeferral();

        List<String> recorded = recordsFrom(Level.DEBUG, () ->
                assertThatThrownBy(() -> handler.handle(deliveryWith(TRANSPORT_MESSAGE_ID, "1"),
                        deferral)).isSameAs(deferral));

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0))
                .as("an expected control outcome recorded as a fault reports a healthy service as failing")
                .contains(SqsConfig.EVENT_WINDOW_DEFERRED)
                .contains("messageId=" + TRANSPORT_MESSAGE_ID)
                .doesNotContain(RethrowingDigestErrorHandler.EVENT);
    }

    /**
     * Confirms a checked failure is wrapped rather than swallowed, and an error is re-raised untouched.
     *
     * <p>Assumptions: both arms are asserted in one case because they are one decision -- the handler
     * signature declares no checked exception, so the only two ways out are to wrap or to swallow, and
     * swallowing is what acknowledges a rolled-back message. The error arm proves the wrap is reached by
     * elimination and not by catching everything.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a checked failure is wrapped and an error is re-raised, so neither is swallowed")
    void neitherACheckedFailureNorAnErrorIsSwallowed() {
        ErrorHandler<Object> handler = new SqsConfig().authorizationListenerErrorHandler();
        Throwable checked = new IOException(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED);
        Error error = new StackOverflowError();

        // WHY : ⚠️ Assumptions: the wrapper is the STARTER's own ListenerExecutionFailedException, not a
        //       plain IllegalStateException. The shared handler wraps a checked failure in the type the
        //       container's own pipeline raises, which keeps the failure attributable to the message it
        //       arrived with -- the wrapper carries that message -- and it carries a fixed literal of its
        //       own so nothing from the failure's text reaches a log through it.
        assertThatThrownBy(() -> handler.handle(deliveryWith(TRANSPORT_MESSAGE_ID, "1"), checked))
                .isInstanceOf(ListenerExecutionFailedException.class)
                .hasMessage(RethrowingDigestErrorHandler.CHECKED_FAILURE_WRAPPER_MESSAGE)
                .hasCauseReference(checked);
        assertThatThrownBy(() -> handler.handle(deliveryWith(TRANSPORT_MESSAGE_ID, "1"), error))
                .isSameAs(error);
    }

    /**
     * Confirms the collection form records every delivery and re-raises, rather than refusing the call.
     *
     * <p>Assumptions: this form is unreachable while the listener takes a single message, and the case
     * exists precisely because that could change. The interface declares both methods {@code default}
     * with bodies that throw {@link UnsupportedOperationException}, so an implementation that inherited
     * this one would substitute a complaint about itself for every real failure the moment the listener
     * switched to batch delivery.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the collection form records every delivery and re-raises, and never refuses the call")
    void theCollectionFormRecordsEveryDeliveryAndReRaises() {
        ErrorHandler<Object> handler = new SqsConfig().authorizationListenerErrorHandler();
        RuntimeException fault = new IllegalStateException(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED);
        List<Message<Object>> batch = List.of(deliveryWith("first-delivery", "2"),
                deliveryWith("second-delivery", "3"));

        List<String> recorded = recordsFrom(Level.ERROR, () ->
                assertThatThrownBy(() -> handler.handle(batch, fault))
                        .as("a refusal here would replace every real failure with a complaint about the "
                                + "handler")
                        .isSameAs(fault));

        // WHY : ⚠️ Refactoring Rationale: ONE record is expected for the batch, naming EVERY delivery in
        //       its identifier and redelivery-count fields, where this case previously expected one record
        //       per delivery. One failure with one throwable is one event -- that is the shared handler's
        //       rule and it is what keeps a failure count queryable without knowing which overload the
        //       container called -- and the property this case exists for is unchanged: every message in
        //       the failed batch is named, so none of them is unfindable when it reaches the dead-letter
        //       queue.
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0))
                .contains(RethrowingDigestErrorHandler.EVENT)
                .contains("source=" + SqsConfig.LISTENER_SOURCE)
                .contains("messageId=first-delivery,second-delivery")
                .contains("receiveCount=2,3")
                .contains("messageCount=2")
                .doesNotContain(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED);
    }

    /**
     * Confirms an absent transport header degrades the record and never the handling.
     *
     * <p>Assumptions: the receive count is a system attribute the container requests rather than one
     * this context sets, so its absence has to be survivable. A handler that dereferenced it would
     * replace the failure it was called about with a null reference, and the container would then be
     * told about the wrong fault entirely.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent transport header degrades the record and leaves the failure re-raised")
    void anAbsentTransportHeaderDegradesOnlyTheRecord() {
        ErrorHandler<Object> handler = new SqsConfig().authorizationListenerErrorHandler();
        RuntimeException fault = new IllegalStateException(FAILURE_TEXT_THAT_MUST_NOT_BE_RECORDED);
        Message<Object> bare = MessageBuilder.withPayload((Object) "unused").build();

        List<String> recorded = recordsFrom(Level.ERROR,
                () -> assertThatThrownBy(() -> handler.handle(bare, fault)).isSameAs(fault));

        assertThat(recorded).hasSize(1);
        // WHY : ⚠️ Assumptions: what degrades is the REDELIVERY COUNT alone. The identifier field still
        //       carries a value, because a message assembled without transport headers still carries the
        //       framework's own identifier and the shared handler falls back to it -- which is the right
        //       behaviour for a record whose purpose is to be findable. The count has no such fallback and
        //       renders as the stable token instead of as the string "null".
        assertThat(recorded.get(0))
                .contains("receiveCount=(unknown)")
                .contains("messageId=" + bare.getHeaders().getId())
                .doesNotContain("messageId=null");
    }

    /**
     * Builds a delivery carrying the transport headers a failure record reads, and nothing a record may
     * disclose.
     *
     * <p>Assumptions: the group header is populated even though no assertion wants it recorded, because
     * a case that omitted it could not prove the handler withholds it. The payload is populated for the
     * same reason.</p>
     *
     * @param transportId the transport's identifier for the delivery; must not be {@code null}
     * @param receiveCount how many times the transport reports having delivered it; must not be
     *     {@code null}
     * @return a delivery with the message identifier, receive count and group identifier set, never
     *     {@code null}
     */
    private static Message<Object> deliveryWith(String transportId, String receiveCount) {
        return MessageBuilder.withPayload((Object) PAYLOAD_THAT_MUST_NOT_BE_RECORDED)
                .setHeader(SqsHeaders.MessageSystemAttributes.MESSAGE_ID, transportId)
                .setHeader(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT,
                        receiveCount)
                .setHeader(SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_GROUP_ID_HEADER,
                        GROUPING_CARD_NUMBER)
                .build();
    }

    /**
     * Constructs the listener's window deferral without widening its constructor.
     *
     * @return a deferral naming an arbitrary window generation, never {@code null}
     * @throws ReflectiveOperationException if the deferral's constructor cannot be reached, which fails
     *     the calling case rather than letting it pass having raised nothing
     */
    private static RuntimeException newWindowDeferral() throws ReflectiveOperationException {
        Constructor<AuthorizationRequestListener.WindowClosedException> constructor =
                AuthorizationRequestListener.WindowClosedException.class
                        .getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(7L);
    }

    /**
     * Captures what the class under test records at or above a level while an action runs.
     *
     * <p>Assumptions: the level is raised for the duration and restored afterwards, and the previous
     * level is restored even when it was {@code null} -- which is not "no level" but "inherit from the
     * parent", so substituting a concrete default would leave the logger pinned where it had been
     * inheriting.</p>
     *
     * @param level the level to capture at; must not be {@code null}
     * @param action the action whose records are wanted, run inside the capture; must not be
     *     {@code null}
     * @return the formatted records in the order they were emitted, never {@code null}
     */
    private static List<String> recordsFrom(Level level, Runnable action) {
        // WHY : ⚠️ Refactoring Rationale: the appender attaches to the SHARED handler's logger, where it
        //       previously attached to this configuration class's. The record is written by
        //       com.carddemo.common.messaging.RethrowingDigestErrorHandler, which three services now share
        //       -- the redaction, the digest and the rethrow rules exist once rather than once per service
        //       -- and its logger is named for itself so one name covers every listener in the fleet. The
        //       listener is identified by the source field on the line instead, which every case below
        //       asserts. Capturing this class's logger silently captured NOTHING once the handler moved.
        Logger configLogger =
                (Logger) LoggerFactory.getLogger(RethrowingDigestErrorHandler.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        configLogger.addAppender(captured);
        Level previousLevel = configLogger.getLevel();
        configLogger.setLevel(level);
        try {
            action.run();
            return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            configLogger.detachAppender(captured);
            captured.stop();
            configLogger.setLevel(previousLevel);
        }
    }

    /**
     * Builds a stub client-builder configurer that supplies just enough for a builder to build.
     *
     * <p>Assumptions: the region and credentials are literals with no reachable endpoint behind them.
     * Nothing in these cases issues a call, so a client that would fail on its first request is
     * sufficient -- and preferable to a real configurer, which would resolve a region and a credentials
     * chain from the environment and make the case pass or fail on how the runner is configured.</p>
     *
     * @return a configurer that returns the builder it was handed, after making it buildable
     */
    private static AwsClientBuilderConfigurer buildableConfigurer() {
        AwsClientBuilderConfigurer configurer = mock(AwsClientBuilderConfigurer.class);
        when(configurer.configure(any(SqsClientBuilder.class))).thenAnswer(invocation -> {
            SqsClientBuilder builder = invocation.getArgument(0);
            return builder.region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test-access-key", "test-secret-key")));
        });
        return configurer;
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

    /**
     * The context publishes the shared listener error handler, so a failed delivery is still recorded.
     *
     * <p>Purpose: the queue starter's own failure record is switched off by NAME in
     * {@code carddemo-common-defaults.yml}, because it renders the throwable as a trailing argument and the
     * logging facade then prints every exception message in the cause chain -- text written by a driver, a
     * codec or a validation library, which on this queue can quote a request value verbatim. The
     * suppression is unconditional, so deleting this bean would not leave a quieter log: it would leave the
     * framework's record with nothing in front of it and no replacement behind it.</p>
     *
     * <p>Refactoring Rationale: the handler's own behaviour -- the message-free rendering and the rethrow
     * that keeps the queue's redrive contract intact -- is asserted once, in the shared kernel's
     * {@code RethrowingDigestErrorHandlerTest}. What this case asserts is the part that can only be wrong
     * HERE: that this context publishes it at all, and publishes it under the type the starter's factory
     * method looks the context up by. A handler declared as its concrete type would compile and would never
     * be installed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws ReflectiveOperationException if the bean method cannot be found under the name the
     *     starter's context lookup depends on, which is the deletion this case exists to report
     */
    @Test
    @DisplayName("the context publishes the shared listener error handler under the starter's own type")
    void theContextPublishesTheSharedListenerErrorHandler() throws ReflectiveOperationException {
        java.lang.reflect.Method bean = SqsConfig.class.getDeclaredMethod("authorizationListenerErrorHandler");

        assertThat(bean.getReturnType())
                .as("the starter looks the context up by %s; a bean declared as its concrete type is"
                        + " created and then never installed",
                        io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler.class.getName())
                .isEqualTo(io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler.class);
        assertThat(bean.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                .as("without @Bean the method is ordinary code and the handler is never registered")
                .isTrue();
        assertThat(bean.getAnnotations())
                .as("a condition would let the one record of a failed delivery be absent whenever"
                        + " something else happened to publish an error handler first")
                .noneMatch(annotation -> annotation.annotationType().getName()
                        .startsWith("org.springframework.boot.autoconfigure.condition."));
        assertThat(new SqsConfig().authorizationListenerErrorHandler())
                .isInstanceOf(com.carddemo.common.messaging.RethrowingDigestErrorHandler.class);
        assertThat(SqsConfig.LISTENER_SOURCE)
                .as("the source is the only field distinguishing this listener's failures from another"
                        + " service's in one log stream")
                .isEqualTo("auth.request");
    }

}
