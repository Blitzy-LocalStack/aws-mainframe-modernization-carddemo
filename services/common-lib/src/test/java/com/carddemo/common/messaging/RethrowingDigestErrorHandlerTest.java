package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.common.observability.ThrowableDigest;
import io.awspring.cloud.sqs.listener.ListenerExecutionFailedException;
import io.awspring.cloud.sqs.listener.MessageProcessingException;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Asserts the two obligations the shared listener error handler carries at once.
 *
 * <p>Purpose: the handler must make a failure visible without publishing exception message text, and it
 * must leave the queue's redrive behaviour exactly as it would have been with no handler registered. Those
 * are independent properties with independent failure modes, so they are asserted separately and both
 * failure modes are demonstrated rather than described.</p>
 *
 * <p>Refactoring Rationale: the rethrow cases exist because of a non-obvious property of the pinned
 * starter. Its error-handler stage is installed as a recovery step, so a handler that returns normally
 * makes the pipeline result SUCCESSFUL and the acknowledgement stage that runs after it deletes the
 * message. A handler edited into swallowing would therefore not lose a log line, it would lose the
 * message — no visibility-timeout redelivery, no dead-letter at the fifth receive — and it would do so
 * with nothing failing. The cases below are what make that edit fail here instead.</p>
 *
 * <p>Assumptions: the log assertions read the CAPTURED EVENT rather than a rendered line, and check both
 * that the sensitive text is absent from the formatted message and that it is still present on the
 * throwable the handler rethrows. Checking only the first would pass for a handler that had lost the
 * throwable altogether, which would break the redrive contract while looking like a privacy improvement.</p>
 *
 * <p>Assumptions: no container, network endpoint or external service is used, so this class belongs under
 * Surefire as a unit test rather than under Failsafe.</p>
 */
class RethrowingDigestErrorHandlerTest {

    /** The listener name the handler under test reports failures against. */
    private static final String SOURCE = "account.inquiry";

    /** The queue the fabricated messages claim to have arrived on. */
    private static final String QUEUE = "carddemo-inquiry-request-dev";

    /**
     * Stands in for the sensitive text a driver, codec or validator puts in an exception message.
     *
     * <p>Assumptions: a card-number-shaped literal is used because that is the value actually at risk on
     * these queues, so a captured record containing it is unambiguously a disclosure rather than a
     * near miss.</p>
     */
    private static final String SENSITIVE_TEXT = "could not parse card 4111111111111111 in request";

    /** The handler under test. */
    private final RethrowingDigestErrorHandler<String> handler =
            new RethrowingDigestErrorHandler<>(SOURCE);

    /** The handler's own logger, captured so the written record can be read. */
    private final ch.qos.logback.classic.Logger handlerLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(RethrowingDigestErrorHandler.class);

    /** Collects the records the handler writes. */
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    /**
     * The handler logger's level before this class changed it, restored afterwards.
     *
     * <p>Assumptions: this may legitimately be {@code null}, which means "inherit from the parent" rather
     * than "no level", and restoring {@code null} is what puts the logger back into that state.</p>
     */
    private Level previousLevel;

    /**
     * Attaches the capturing appender and lowers the handler logger to a level that admits its records.
     */
    @BeforeEach
    void attachAppender() {
        this.captured.start();
        this.handlerLogger.addAppender(this.captured);
        this.previousLevel = this.handlerLogger.getLevel();
        this.handlerLogger.setLevel(Level.ERROR);
    }

    /**
     * Detaches the capturing appender and restores the handler logger's previous level.
     */
    @AfterEach
    void detachAppender() {
        this.handlerLogger.detachAppender(this.captured);
        this.captured.stop();
        this.handlerLogger.setLevel(this.previousLevel);
    }

    /**
     * An unchecked failure is rethrown as the very same instance, so nothing downstream sees a change.
     *
     * <p>Refactoring Rationale: identity is asserted, not merely type. The starter wraps only what is not
     * already a processing exception, so returning the original instance is what makes the throwable that
     * reaches the acknowledgement stage byte for byte what it would have been with no handler at all. A
     * handler that wrapped every failure would still preserve redelivery, but it would change what the
     * starter unwraps and what a future error-handling decision could match on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unchecked failure is rethrown as the same instance")
    void anUncheckedFailureIsRethrownAsTheSameInstance() {
        Message<String> message = messageOn(QUEUE);
        RuntimeException failure = new ListenerExecutionFailedException(
                SENSITIVE_TEXT, new IllegalStateException(SENSITIVE_TEXT), message);

        assertThatExceptionOfType(RuntimeException.class)
                .as("returning normally would make the pipeline result successful, and the"
                        + " acknowledgement stage that runs next would DELETE the message")
                .isThrownBy(() -> this.handler.handle(message, failure))
                .isSameAs(failure);
    }

    /**
     * A checked failure is wrapped in the starter's own unchecked type rather than swallowed.
     *
     * <p>Assumptions: the wrapper is the starter's {@link ListenerExecutionFailedException}, which
     * implements {@link MessageProcessingException}, so the starter's own wrapping step leaves it alone
     * and the failed message stays attached and unwrappable.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a checked failure is wrapped in the starter's processing exception, not swallowed")
    void aCheckedFailureIsWrappedRatherThanSwallowed() {
        Message<String> message = messageOn(QUEUE);
        IOException failure = new IOException(SENSITIVE_TEXT);

        assertThatExceptionOfType(ListenerExecutionFailedException.class)
                .isThrownBy(() -> this.handler.handle(message, failure))
                .satisfies(thrown -> {
                    assertThat(thrown).hasCause(failure);
                    assertThat(thrown.getMessage())
                            .as("the wrapper's own text is a fixed literal, so it cannot carry anything"
                                    + " from the failure it wraps")
                            .isEqualTo(RethrowingDigestErrorHandler.CHECKED_FAILURE_WRAPPER_MESSAGE)
                            .doesNotContain(SENSITIVE_TEXT);
                    assertThat(MessageProcessingException.hasProcessingException(thrown))
                            .as("the starter wraps only what is not already a processing exception, so"
                                    + " this stays a single wrap rather than becoming a double one")
                            .isTrue();
                    assertThat(thrown.getFailedMessage())
                            .as("the failed message must stay attached, or the starter cannot report"
                                    + " which message the failure belonged to")
                            .isSameAs(message);
                });
    }

    /**
     * An error is rethrown as itself and is never converted into an application exception.
     *
     * <p>Assumptions: an {@link Error} reports a condition of the virtual machine rather than of the
     * message, so wrapping it would present a machine-level failure as a message-processing one and could
     * see it retried per-message forever.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an error is rethrown as itself rather than wrapped")
    void anErrorIsRethrownAsItself() {
        Message<String> message = messageOn(QUEUE);
        OutOfMemoryError failure = new OutOfMemoryError(SENSITIVE_TEXT);

        assertThatExceptionOfType(OutOfMemoryError.class)
                .isThrownBy(() -> this.handler.handle(message, failure))
                .isSameAs(failure);
    }

    /**
     * The written record names the failure's types and frames and carries none of its message text.
     *
     * <p>Refactoring Rationale: the absence assertion is paired with a presence assertion on the SAME
     * failure. An empty or truncated record would satisfy "does not contain the card number" while telling
     * an operator nothing, so the record is also required to name the digest of the failure — which is what
     * makes it actionable — and the rethrown throwable is required to still carry the text, which is what
     * proves the handler withheld it rather than lost it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the record carries the type chain and no exception message text")
    void theRecordCarriesTheTypeChainAndNoMessageText() {
        Message<String> message = messageOn(QUEUE);
        RuntimeException failure =
                new IllegalStateException(SENSITIVE_TEXT, new IllegalArgumentException(SENSITIVE_TEXT));

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> this.handler.handle(message, failure))
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .as("the text is withheld from the LOG, not removed from the failure; a handler"
                                + " that dropped the throwable would break redelivery")
                        .contains(SENSITIVE_TEXT));

        assertThat(this.captured.list).hasSize(1);
        ILoggingEvent record = this.captured.list.get(0);

        assertThat(record.getFormattedMessage())
                .as("this is the whole point of the class: the rendered record must not be able to"
                        + " publish text written by a driver, a codec or a validation library")
                .doesNotContain(SENSITIVE_TEXT)
                .doesNotContain("4111111111111111")
                .contains(RethrowingDigestErrorHandler.EVENT)
                .contains("source=" + SOURCE)
                .contains("queue=" + QUEUE)
                .contains("messageCount=1")
                .contains(ThrowableDigest.of(failure));
        assertThat(record.getFormattedMessage())
                .as("both links of the chain must be named, or a reader cannot tell what actually threw")
                .contains(IllegalStateException.class.getName())
                .contains(IllegalArgumentException.class.getName());
        assertThat(record.getThrowableProxy())
                .as("the throwable is deliberately NOT passed as a trailing argument, because that is"
                        + " exactly what makes the facade render every message in the chain")
                .isNull();
    }

    /**
     * The record names the broker's own message identifier, which is safe by construction.
     *
     * <p>Assumptions: the identifier asserted here is the value the starter's header mapper puts in the
     * standard identifier header, which is the queue's own message id. It is broker-generated rather than
     * requester-supplied, which is why it is logged verbatim while the correlation identity — which IS
     * requester-supplied — is redacted before it reaches a record.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the record names the broker's message identifier and the originating queue")
    void theRecordNamesTheBrokerIdentifierAndQueue() {
        Message<String> message = messageOn(QUEUE);
        UUID identifier = message.getHeaders().getId();

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> this.handler.handle(message, new IllegalStateException("x")));

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .as("without the broker's identifier an operator cannot find the message in the"
                        + " dead-letter queue, which is the one place its payload may be read")
                .contains("messageId=" + identifier)
                .contains("queue=" + QUEUE);
    }

    /**
     * A batch failure writes one record carrying the batch size, and still rethrows.
     *
     * <p>Refactoring Rationale: this case exists because both methods on the starter's handler interface
     * are {@code default} and both defaults do nothing. A handler overriding only the single-message form
     * would compile, would pass every test above, and would swallow every batch failure — deleting whole
     * batches of messages that had never been processed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a batch failure writes one record with the batch size and still rethrows")
    void aBatchFailureWritesOneRecordAndStillRethrows() {
        List<Message<String>> batch = List.of(messageOn(QUEUE), messageOn(QUEUE), messageOn(QUEUE));
        RuntimeException failure = new IllegalStateException(SENSITIVE_TEXT);

        assertThatExceptionOfType(RuntimeException.class)
                .as("the batch overload must not be left to the interface default, which does nothing"
                        + " and would therefore delete the whole batch")
                .isThrownBy(() -> this.handler.handle(batch, failure))
                .isSameAs(failure);

        assertThat(this.captured.list)
                .as("one failure with one throwable is one event, whatever the batch size")
                .hasSize(1);
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .contains("messageCount=3")
                .contains("messageId=" + batch.get(0).getHeaders().getId())
                .doesNotContain(SENSITIVE_TEXT);
    }

    /**
     * A checked batch failure is wrapped with the whole batch attached rather than a single message.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a checked batch failure is wrapped with the whole batch attached")
    void aCheckedBatchFailureIsWrappedWithTheBatchAttached() {
        List<Message<String>> batch = List.of(messageOn(QUEUE), messageOn(QUEUE));
        IOException failure = new IOException(SENSITIVE_TEXT);

        assertThatExceptionOfType(ListenerExecutionFailedException.class)
                .isThrownBy(() -> this.handler.handle(batch, failure))
                .satisfies(thrown -> {
                    assertThat(thrown).hasCause(failure);
                    assertThat(thrown.getFailedMessages())
                            .as("attaching one message from a batch would misreport which messages the"
                                    + " failure covered")
                            .hasSize(batch.size());
                });
    }

    /**
     * An empty batch renders stable tokens instead of the word null, and still rethrows.
     *
     * <p>Assumptions: an empty collection is possible rather than hypothetical — the overload's parameter
     * carries no non-empty constraint — and a failure path is the worst place to raise a second failure,
     * so the absent values render as tokens a query can match rather than throwing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an empty batch renders stable tokens and still rethrows")
    void anEmptyBatchRendersStableTokensAndStillRethrows() {
        RuntimeException failure = new IllegalStateException("x");

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> this.handler.handle(List.<Message<String>>of(), failure))
                .isSameAs(failure);

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .contains("messageId=" + RethrowingDigestErrorHandler.NO_IDENTIFIER)
                .contains("messageCount=0")
                .doesNotContain("null");
    }

    /**
     * A message with no queue header renders a stable token rather than failing.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a message with no queue header renders a stable token")
    void aMessageWithNoQueueHeaderRendersAStableToken() {
        Message<String> message = MessageBuilder.withPayload("payload").build();

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> this.handler.handle(message, new IllegalStateException("x")));

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .as("a message converted by something other than the queue starter carries no queue"
                        + " header, and a record is still better than a second failure")
                .contains("queue=(unknown)");
    }

    /**
     * A blank or absent source is refused at construction, not discovered in a log line.
     *
     * <p>Assumptions: the source is the only field distinguishing one listener's failures from another's
     * in a shared log stream, so an empty one is refused where it is supplied rather than accepted and
     * rendered empty.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a blank or absent source is refused at construction")
    void aBlankOrAbsentSourceIsRefusedAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RethrowingDigestErrorHandler<String>(null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RethrowingDigestErrorHandler<String>("   "));
    }

    /**
     * Both overloads refuse null arguments rather than writing a record about nothing.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both overloads refuse null arguments")
    void bothOverloadsRefuseNullArguments() {
        Message<String> message = messageOn(QUEUE);
        RuntimeException failure = new IllegalStateException("x");

        assertThatNullPointerException()
                .isThrownBy(() -> this.handler.handle((Message<String>) null, failure));
        assertThatNullPointerException().isThrownBy(() -> this.handler.handle(message, null));
        assertThatNullPointerException()
                .isThrownBy(() -> this.handler.handle((List<Message<String>>) null, failure));
        assertThatNullPointerException()
                .isThrownBy(() -> this.handler.handle(List.of(message), null));
    }

    /**
     * The starter still installs a context-supplied error handler, which is how this one is registered.
     *
     * <p>Purpose: three services publish this handler as an ordinary bean and none of them touches the
     * listener-container factory, because the starter's own factory method takes an {@code ErrorHandler}
     * from the context and installs it. That parameter is the entire registration mechanism. If it were
     * withdrawn upstream the bean would keep being created and would simply never be consulted, the
     * framework's own full-throwable record would return in its place — it is switched off by NAME, not by
     * the presence of a replacement — and nothing anywhere in this repository would fail.</p>
     *
     * <p>Refactoring Rationale: the assertion is on the auto-configuration method's declared parameter
     * types rather than on a running context. Standing up the real auto-configuration would need a region,
     * a credentials chain and a client, making the guard pass or fail on how the runner is configured; the
     * parameter list is the contract itself and is readable without any of that.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws ClassNotFoundException if the starter's SQS auto-configuration cannot be loaded under the
     *     name the three services rely on, which is itself a failure of the same contract
     */
    @Test
    @DisplayName("the starter's factory still takes an ErrorHandler from the context")
    void theStarterStillTakesAnErrorHandlerFromTheContext() throws ClassNotFoundException {
        Class<?> autoConfiguration =
                Class.forName("io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration");

        Method factoryMethod = Arrays.stream(autoConfiguration.getDeclaredMethods())
                .filter(method -> "defaultSqsListenerContainerFactory".equals(method.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the starter no longer declares defaultSqsListenerContainerFactory, so the"
                                + " registration route these three services use no longer exists"));

        assertThat(Arrays.stream(factoryMethod.getGenericParameterTypes())
                        .map(Type::getTypeName)
                        .toList())
                .as("the factory method must still accept an ObjectProvider of %s, or a published"
                        + " error-handler bean is created and never installed",
                        ErrorHandler.class.getName())
                .anySatisfy(parameter -> assertThat(parameter)
                        .contains("org.springframework.beans.factory.ObjectProvider")
                        .contains(ErrorHandler.class.getName()));
    }

    /**
     * Builds a message carrying the queue header the starter's converter sets.
     *
     * @param queue the queue name to place in the starter's queue-name header; must not be {@code null}
     * @return a message with a generated identifier and the given queue header, never {@code null}
     */
    private static Message<String> messageOn(String queue) {
        return MessageBuilder.withPayload("payload")
                .setHeader(SqsHeaders.SQS_QUEUE_NAME_HEADER, queue)
                .build();
    }
}
