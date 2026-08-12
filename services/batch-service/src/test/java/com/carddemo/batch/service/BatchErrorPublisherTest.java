package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.config.SqsConfig;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the four properties of the terminal-sink producer that reading it cannot settle.
 *
 * <p>Assumptions: the four are that a notification actually reaches the transport through the module's
 * own binding rather than through a request this class shapes; that a transport fault is reported and
 * NOT propagated, because the caller is already reporting a failure and its exit status is the only
 * channel the orchestrator reads; that no log record this class writes carries the failure's message or
 * the sink's address; and that a caller error is still raised, so a swallow of transport faults does not
 * become a swallow of programming faults.</p>
 *
 * <p>Assumptions: the transport is a mock and the mapper is real. A mock mapper would let the test pass
 * while the payload was unserialisable, which is one of the faults the swallow covers and therefore one
 * the test must be able to observe; a real transport would need an endpoint.</p>
 */
class BatchErrorPublisherTest {

    /** A standard-queue address, which the binding requires -- an ordered one is refused. */
    private static final String QUEUE_URL =
            "https://sqs.us-east-1.amazonaws.com/000000000000/carddemo-error-dev";

    /** The producer under test, rebuilt per test so no captured state crosses cases. */
    private BatchErrorPublisher publisher;

    /** The mocked transport, held so each case can assert on or arrange its behaviour. */
    private SqsClient sqs;

    /** The appender capturing what the producer wrote during one test. */
    private ListAppender<ILoggingEvent> captured;

    /** The producer's own logger, held so the appender can be detached again. */
    private Logger publisherLogger;

    /** The producer logger's configured level, restored afterwards; null legitimately means inherit. */
    private Level originalLevel;

    /**
     * Builds the producer over a mocked transport and captures its log records from informational up.
     *
     * <p>Assumptions: the level is lowered to informational explicitly, because the delivered case is
     * reported there. A configuration admitting only errors would make an empty capture
     * indistinguishable from a passing assertion in the case that matters most.</p>
     */
    @BeforeEach
    void arrange() {
        this.sqs = mock(SqsClient.class);
        this.publisher = new BatchErrorPublisher(this.sqs,
                new SqsConfig.ErrorSinkBinding(QUEUE_URL, "application/json", "CARDDEMO", "BATCHSVC"),
                new ObjectMapper());
        this.publisherLogger = (Logger) LoggerFactory.getLogger(BatchErrorPublisher.class);
        this.originalLevel = this.publisherLogger.getLevel();
        this.captured = new ListAppender<>();
        this.captured.start();
        this.publisherLogger.addAppender(this.captured);
        this.publisherLogger.setLevel(Level.INFO);
    }

    /**
     * Detaches the appender and restores the logger's configured level.
     *
     * <p>Assumptions: the saved level may legitimately be null, which means the logger inherits rather
     * than declares one. Restoring null re-establishes inheritance, so it is passed through unchanged
     * instead of being replaced by a default that would pin a level this test never found set.</p>
     */
    @AfterEach
    void restore() {
        this.publisherLogger.detachAppender(this.captured);
        this.captured.stop();
        this.publisherLogger.setLevel(this.originalLevel);
    }

    /**
     * Builds a hard-failure event, which is the only tier the payload's constructor accepts.
     *
     * @param stepName the step to name on the event; must not be {@code null} or blank
     * @return the event, never {@code null}
     */
    private static BatchErrorEvent hardFailure(String stepName) {
        return BatchErrorEvent.withoutAbendDetail("CD0123456789ABCDEF012345", stepName,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE,
                "CD0123456789ABCDEF012345");
    }

    /**
     * Confirms the send is the one the module's binding builds, not one this producer shapes.
     */
    @Test
    @DisplayName("a notification reaches the transport as the binding's own request, and reports true")
    void deliveredNotificationUsesTheBinding() {
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("transport-assigned").build());

        boolean delivered = this.publisher.publish(hardFailure("post-transactions-step"));

        assertThat(delivered).isTrue();
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs).sendMessage(sent.capture());
        SendMessageRequest request = sent.getValue();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        // WHY : Assumptions: the attribute set is asserted as EXACTLY three names, not as three
        //       containments, because the property being proved is that this producer shapes no request
        //       of its own. The binding closes the set at three so one assertion can cover it, and a
        //       containment check would pass just as happily against a fourth attribute added here.
        assertThat(request.messageAttributes()).containsOnlyKeys(
                SqsConfig.ATTRIBUTE_CORRELATION_ID, SqsConfig.ATTRIBUTE_MESSAGE_ID,
                SqsConfig.ATTRIBUTE_CONTENT_TYPE);
        // WHY : Assumptions: neither ordered-queue identifier may be present. The sink is a standard
        //       queue, which rejects both, and the binding omits them -- so this is the assertion that
        //       fails if a send is ever shaped somewhere that does not know that.
        assertThat(request.messageGroupId()).isNull();
        assertThat(request.messageDeduplicationId()).isNull();
        assertThat(request.messageBody()).contains("post-transactions-step").contains("post-transactions");
    }

    /**
     * Confirms two sends carry two different publisher identities, neither of them the transport's.
     */
    @Test
    @DisplayName("the publisher's message identity is minted per send and is not the transport's")
    void messageIdentityIsMintedPerSend() {
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("transport-assigned").build());

        this.publisher.publish(hardFailure("post-transactions-step"));
        this.publisher.publish(hardFailure("post-transactions-step"));

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, org.mockito.Mockito.times(2)).sendMessage(sent.capture());
        List<SendMessageRequest> requests = sent.getAllValues();
        String first = requests.get(0).messageAttributes()
                .get(SqsConfig.ATTRIBUTE_MESSAGE_ID).stringValue();
        String second = requests.get(1).messageAttributes()
                .get(SqsConfig.ATTRIBUTE_MESSAGE_ID).stringValue();
        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(first).isNotEqualTo("transport-assigned");
    }

    /**
     * Confirms a transport fault is reported without its message and without ending the run.
     */
    @Test
    @DisplayName("a transport fault is reported and not propagated, and reports false")
    void transportFaultIsSwallowedAndReported() {
        doThrow(SdkClientException.create("unable to reach https://sqs.internal/secret-queue"))
                .when(this.sqs).sendMessage(any(SendMessageRequest.class));

        boolean delivered = this.publisher.publish(hardFailure("calculate-interest-step"));

        assertThat(delivered).isFalse();
        List<ILoggingEvent> errors = this.captured.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        String rendered = errors.get(0).getFormattedMessage();
        assertThat(rendered).contains("event=batch.error.publish-failed")
                .contains(SqsConfig.PROPERTY_ERROR_QUEUE_URL)
                .contains("calculate-interest-step")
                .contains(SdkClientException.class.getName());
        // WHY : Assumptions: the two things the record must NOT carry are asserted by ABSENCE, because
        //       both are values the obvious implementation would have included -- a fault's own message
        //       is the first thing a logger reaches for, and the queue address is right there on the
        //       binding. The message quoted above embeds an address on purpose, so a producer that
        //       logged getMessage() would fail this line rather than pass it silently.
        assertThat(rendered).doesNotContain("unable to reach").doesNotContain(QUEUE_URL);
        // WHY : Assumptions: no throwable is attached either. A trailing throwable renders the whole
        //       cause chain including every message in it, so suppressing the message text while
        //       passing the throwable would disclose exactly what the previous line refuses.
        assertThat(errors.get(0).getThrowableProxy()).isNull();
    }

    /**
     * Confirms a refused correlation identity ends as a suppressed notification, not as a raised fault.
     */
    @Test
    @DisplayName("a correlation identity the transport will not carry is suppressed, not raised")
    void unusableCorrelationIdentityIsSuppressed() {
        // WHY : Assumptions: a space is the character that makes this reachable in production rather
        //       than a contrived one. The entry point neutralises an operator-supplied run identifier
        //       by substituting a space for each control character, and the shared messaging rule
        //       admits printable US-ASCII OTHER than the space -- so a run identifier that arrived with
        //       a line feed in it becomes a value the binding refuses.
        BatchErrorEvent event = BatchErrorEvent.withoutAbendDetail("CD0123 456789", "export-step",
                BatchJobName.EXPORT, BatchReturnCode.HARD_FAILURE, "CD0123 456789");

        boolean delivered = this.publisher.publish(event);

        assertThat(delivered).isFalse();
        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
        assertThat(this.captured.list).anySatisfy(event2 ->
                assertThat(event2.getFormattedMessage()).contains("event=batch.error.publish-failed"));
    }

    /**
     * Confirms the swallow of transport faults has not become a swallow of programming faults.
     */
    @Test
    @DisplayName("a null event is a caller error and is still raised")
    void nullEventIsRaised() {
        assertThatThrownBy(() -> this.publisher.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event must not be null");
        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * Confirms the binding's two configured identifiers reach the publication record.
     */
    @Test
    @DisplayName("the delivered record names the two configured source identifiers")
    void deliveredRecordNamesTheConfiguredSource() {
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("transport-assigned").build());

        this.publisher.publish(hardFailure("import-step"));

        // WHY : Assumptions: this is the assertion that keeps the two configured identifiers from being
        //       dead configuration. They populate ERR-APPLICATION and ERR-PROGRAM, which the migration's
        //       observability contract maps to the service field and the logger name, so the log record
        //       is where they land -- and without this line a binding could validate two values that
        //       nothing ever read.
        assertThat(this.captured.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("event=batch.error.published")
                    .contains("application=CARDDEMO").contains("program=BATCHSVC");
        });
    }

    /**
     * Confirms none of the three collaborators may be absent, so no field can be null at send time.
     */
    @Test
    @DisplayName("every collaborator is required at construction")
    void collaboratorsAreRequired() {
        SqsConfig.ErrorSinkBinding binding =
                new SqsConfig.ErrorSinkBinding(QUEUE_URL, "application/json", "CARDDEMO", "BATCHSVC");
        assertThatThrownBy(() -> new BatchErrorPublisher(null, binding, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BatchErrorPublisher(this.sqs, null, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BatchErrorPublisher(this.sqs, binding, null))
                .isInstanceOf(NullPointerException.class);
    }
}
