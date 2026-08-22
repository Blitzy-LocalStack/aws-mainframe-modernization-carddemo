package com.carddemo.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.config.SqsConfig.ErrorSinkBinding;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.service.BatchErrorPublisher;
import com.carddemo.common.messaging.MessagingCorrelationId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the terminal error sink's binding and the one class that sends on it.
 *
 * <p>Purpose: the sink is the migrated form of the reference system's {@code CARD.DEMO.ERROR}, and the
 * cases here cover the two things about it that no other tier exercises -- what the binding refuses
 * while the context is starting, and that a send is actually issued.</p>
 *
 * <p>Refactoring Rationale: this class exists because the publisher did not. The binding was
 * declared, validated and logged from the checkpoint that authored it, nothing in the module ever
 * built a request from it, and the startup line it emits reports publishing as ENABLED -- so a batch
 * step could fail on every night of the year with the sink empty and the logs saying otherwise. A
 * case asserting only that a well-formed request COULD be built would have passed throughout.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Rationale labels are
 * written in the plural unparenthesised form with the colon retained, and this file is restricted to
 * ASCII.</p>
 */
@DisplayName("The terminal error sink refuses a misconfiguration and issues exactly one send")
class ErrorSinkPublicationTest {

    /** A standard-queue address of the shape the queue module's own output produces. */
    private static final String QUEUE_URL =
            "https://sqs.us-east-1.amazonaws.com/000000000000/carddemo-error-dev";

    /** The eight-character source application the copybook field admits. */
    private static final String APPLICATION = "CARDDEMO";

    /** The eight-character source program the copybook field admits. */
    private static final String PROGRAM = "BATCHSVC";

    /**
     * Builds a binding over the admitted values, so a case can vary one component at a time.
     *
     * @param contentType the media type to configure; must not be {@code null}
     * @return the binding, never {@code null}
     */
    private static ErrorSinkBinding bindingWithContentType(String contentType) {
        return new ErrorSinkBinding(QUEUE_URL, contentType, APPLICATION, PROGRAM);
    }

    /**
     * Builds the event the reporter publishes, at the only tier the record admits.
     *
     * @param correlationId the correlation identity to carry; must not be {@code null} or blank
     * @return the event, never {@code null}
     */
    private static BatchErrorEvent eventCorrelatedBy(String correlationId) {
        return BatchErrorEvent.withoutAbendDetail("exec-0001", "post-transactions-step",
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, correlationId);
    }

    /** What the binding refuses while the context is still starting. */
    @Nested
    @DisplayName("the binding's startup validation")
    class BindingValidation {

        /**
         * The media type is admitted only at the exact value the publisher produces.
         *
         * <p>Assumptions: this is an EQUALITY check and not a structural one, because the attribute is
         * a promise about the body and only one body shape is ever built. A deployment that labelled a
         * JSON document as fixed-width text would be believed by a consumer, which would then fail to
         * read the one message the sink exists to deliver.</p>
         */
        @Test
        @DisplayName("admit only the exact JSON media type the publisher produces")
        void onlyTheExactMediaTypeIsAdmitted() {
            assertThat(bindingWithContentType(SqsConfig.DEFAULT_CONTENT_TYPE).contentType())
                    .isEqualTo(SqsConfig.DEFAULT_CONTENT_TYPE);

            assertThatThrownBy(() -> bindingWithContentType("text/csv"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SqsConfig.PROPERTY_ERROR_CONTENT_TYPE)
                    .hasMessageContaining(SqsConfig.DEFAULT_CONTENT_TYPE);

            assertThatThrownBy(() -> bindingWithContentType("application/json; charset=utf-8"))
                    .as("a charset parameter could only restate what the mapper already does or"
                            + " contradict it, so the admitted value has one spelling")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SqsConfig.PROPERTY_ERROR_CONTENT_TYPE);
        }

        /** An ordered destination is refused, because every send omits the identifiers it requires. */
        @Test
        @DisplayName("refuse an ordered destination")
        void anOrderedDestinationIsRefused() {
            assertThatThrownBy(() -> new ErrorSinkBinding(QUEUE_URL + SqsConfig.FIFO_QUEUE_SUFFIX,
                    SqsConfig.DEFAULT_CONTENT_TYPE, APPLICATION, PROGRAM))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SqsConfig.FIFO_QUEUE_SUFFIX);
        }

        /** A blank value is refused, naming the property that carries it. */
        @Test
        @DisplayName("refuse a blank address, naming the property it was set on")
        void aBlankAddressIsRefused() {
            assertThatThrownBy(() -> new ErrorSinkBinding("   ", SqsConfig.DEFAULT_CONTENT_TYPE,
                    APPLICATION, PROGRAM))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SqsConfig.PROPERTY_ERROR_QUEUE_URL);
        }

        /**
         * A source identifier wider than its copybook field is refused rather than truncated.
         *
         * <p>Assumptions: the widths are the contract the field vocabulary carries, so a value that
         * does not fit is a value two readers would report differently.</p>
         */
        @Test
        @DisplayName("refuse a source identifier wider than the copybook field it populates")
        void anOverWideSourceIdentifierIsRefused() {
            assertThatThrownBy(() -> new ErrorSinkBinding(QUEUE_URL,
                    SqsConfig.DEFAULT_CONTENT_TYPE, "CARDDEMO-TOO-WIDE", PROGRAM))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ERR-APPLICATION")
                    .hasMessageContaining(String.valueOf(SqsConfig.ERR_APPLICATION_LENGTH));

            assertThatThrownBy(() -> new ErrorSinkBinding(QUEUE_URL,
                    SqsConfig.DEFAULT_CONTENT_TYPE, APPLICATION, "BATCHSERVICE"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ERR-PROGRAM");
        }

        /**
         * The send carries exactly three attributes and neither ordered-queue identifier.
         *
         * <p>Assumptions: the set is asserted as a whole rather than one member at a time, because the
         * contract is that it is CLOSED -- a fourth attribute added later is the regression this case
         * exists to catch, and a per-member assertion would not see it.</p>
         */
        @Test
        @DisplayName("build exactly three attributes and neither ordered-queue identifier")
        void theAttributeSetIsClosed() {
            SendMessageRequest request = bindingWithContentType(SqsConfig.DEFAULT_CONTENT_TYPE)
                    .publicationOf("{}", "exec-0001", "msg-0001");

            assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
            assertThat(request.messageBody()).isEqualTo("{}");
            assertThat(request.messageAttributes()).containsOnlyKeys(
                    SqsConfig.ATTRIBUTE_CORRELATION_ID,
                    SqsConfig.ATTRIBUTE_MESSAGE_ID,
                    SqsConfig.ATTRIBUTE_CONTENT_TYPE);
            assertThat(request.messageAttributes()
                    .get(SqsConfig.ATTRIBUTE_CONTENT_TYPE).stringValue())
                    .isEqualTo(SqsConfig.DEFAULT_CONTENT_TYPE);
            assertThat(request.messageGroupId())
                    .as("a standard queue rejects a group identifier, so none is set")
                    .isNull();
            assertThat(request.messageDeduplicationId()).isNull();
        }

        /** An identity the transport would not carry as an attribute is refused here, not there. */
        @Test
        @DisplayName("refuse an identity the transport would not carry as an attribute")
        void anUnusableIdentityIsRefused() {
            ErrorSinkBinding binding = bindingWithContentType(SqsConfig.DEFAULT_CONTENT_TYPE);
            String tooLong = "e".repeat(MessagingCorrelationId.MAX_LENGTH + 1);

            assertThatThrownBy(() -> binding.publicationOf("{}", tooLong, "msg-0001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("correlationId");

            assertThatThrownBy(() -> binding.publicationOf("  ", "exec-0001", "msg-0001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("body");
        }
    }

    /** That a send is issued through the module's one sender, and that nothing escapes the adapter. */
    @Nested
    @DisplayName("the publisher")
    class Publisher {

        /** The binding every case in this group sends on. */
        private final ErrorSinkBinding binding =
                bindingWithContentType(SqsConfig.DEFAULT_CONTENT_TYPE);

        /**
         * Builds the step-level adapter over a sender bound to the supplied client.
         *
         * <p>Refactoring Rationale: the adapter used to take the client and the binding and send for
         * itself, which made it the module's SECOND sender and made one hard failure publish twice. It
         * now takes the one sender, so every case in this group exercises the same send path the
         * run-level occasion uses.</p>
         *
         * @param client the transport the sender issues on; must not be {@code null}
         * @return the adapter, never {@code null}
         */
        private SqsBatchFailureReporter reporterSendingOn(SqsClient client) {
            return new SqsBatchFailureReporter(
                    new BatchErrorPublisher(client, this.binding, new ObjectMapper()));
        }

        /**
         * A reported event reaches the transport as one send carrying the serialised event.
         *
         * <p>Assumptions: the body is asserted to CONTAIN the event's components rather than to equal
         * a literal document, because the exact key order of a serialised record is the mapper's
         * choice and pinning it would make this case fail on a library upgrade that changed nothing
         * about the contract. What the contract requires is that the components are present.</p>
         */
        @Test
        @DisplayName("issue one send carrying the serialised event")
        void aReportedEventIsSent() {
            SqsClient client = mock(SqsClient.class);
            when(client.sendMessage(any(SendMessageRequest.class)))
                    .thenReturn(SendMessageResponse.builder().messageId("transport-1").build());

            boolean published = reporterSendingOn(client).report(eventCorrelatedBy("exec-0001"));

            assertThat(published).isTrue();
            ArgumentCaptor<SendMessageRequest> sent =
                    ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(client).sendMessage(sent.capture());
            assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE_URL);
            assertThat(sent.getValue().messageBody())
                    .contains("exec-0001", "post-transactions-step",
                            BatchJobName.POST_TRANSACTIONS.name(),
                            BatchReturnCode.HARD_FAILURE.name());
            assertThat(sent.getValue().messageAttributes()
                    .get(SqsConfig.ATTRIBUTE_CORRELATION_ID).stringValue())
                    .isEqualTo("exec-0001");
        }

        /**
         * A rejected send is reported by return value and never by an exception.
         *
         * <p>Assumptions: the only caller is a catch block about to re-raise the failure the step
         * actually suffered, so an exception escaping here would replace a diagnosed step failure with
         * an undiagnosed failure about the reporting of it. A missing queue permission is the most
         * likely cause and the least recognisable symptom.</p>
         */
        @Test
        @DisplayName("absorb a rejected send and report it by return value")
        void aRejectedSendIsAbsorbed() {
            SqsClient client = mock(SqsClient.class);
            when(client.sendMessage(any(SendMessageRequest.class)))
                    .thenThrow(SqsException.builder().message("AccessDenied").build());

            boolean published = reporterSendingOn(client).report(eventCorrelatedBy("exec-0001"));

            assertThat(published).isFalse();
        }

        /**
         * An unusable correlation identity is substituted rather than refused.
         *
         * <p>Assumptions: the event admits a correlation identity on a weaker rule than a message
         * attribute does -- non-blank against at most {@link MessagingCorrelationId#MAX_LENGTH}
         * printable characters -- and an orchestrator execution name may legitimately exceed the
         * attribute's width. The report's content is inside the body and is unaffected, so withholding
         * the whole report to protect one attribute would discard the diagnostics to preserve a join
         * that was already unavailable.</p>
         */
        @Test
        @DisplayName("substitute the generated identity when the supplied one is unusable")
        void anUnusableCorrelationIdentityIsSubstituted() {
            SqsClient client = mock(SqsClient.class);
            when(client.sendMessage(any(SendMessageRequest.class)))
                    .thenReturn(SendMessageResponse.builder().messageId("transport-1").build());
            String overWide = "x".repeat(MessagingCorrelationId.MAX_LENGTH + 5);

            boolean published = reporterSendingOn(client).report(eventCorrelatedBy(overWide));

            assertThat(published)
                    .as("the report is still published, because its content is in the body")
                    .isTrue();
            ArgumentCaptor<SendMessageRequest> sent =
                    ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(client).sendMessage(sent.capture());
            MessageAttributeValue correlation =
                    sent.getValue().messageAttributes().get(SqsConfig.ATTRIBUTE_CORRELATION_ID);
            assertThat(correlation.stringValue())
                    .as("a rewritten identity would join to nothing, so the generated message"
                            + " identity is used instead and is the same value on both attributes")
                    .isNotEqualTo(overWide)
                    .isEqualTo(sent.getValue().messageAttributes()
                            .get(SqsConfig.ATTRIBUTE_MESSAGE_ID).stringValue());
        }

        /**
         * Both of the module's occasions for one run put exactly one message on the sink.
         *
         * <p>Refactoring Rationale: this is the case the duplicate defect would have failed and no
         * previous case could. The adapter and the run-level producer were two senders on one queue, so
         * a single hard failure published a step report carrying diagnostics AND a run notification
         * carrying none -- two messages for one failure, against the producer's documented contract of
         * one per failed run. Driving BOTH occasions through one sender in one case is what makes the
         * count assertable at all; asserting each occasion separately passes in either design.</p>
         *
         * <p>Assumptions: the occasions are driven in the order they occur at run time -- the ledger
         * reports from inside the failing step and the entry point publishes after the job returns --
         * so the surviving message is the step report, which is the richer of the two.</p>
         */
        @Test
        @DisplayName("put one message on the sink for one run, whichever occasion reaches it first")
        void twoOccasionsForOneRunPutOneMessageOnTheSink() {
            SqsClient client = mock(SqsClient.class);
            when(client.sendMessage(any(SendMessageRequest.class)))
                    .thenReturn(SendMessageResponse.builder().messageId("transport-1").build());
            BatchErrorPublisher sender =
                    new BatchErrorPublisher(client, this.binding, new ObjectMapper());
            BatchErrorEvent event = eventCorrelatedBy("exec-0001");

            boolean stepReport = new SqsBatchFailureReporter(sender).report(event);
            boolean runNotification = sender.publish(event);

            assertThat(stepReport).isTrue();
            assertThat(runNotification)
                    .as("the run's notification is on the sink, so the second occasion is not a failure")
                    .isTrue();
            verify(client, times(1)).sendMessage(any(SendMessageRequest.class));
        }

        /** A null event is a programming error in the caller and is the one condition not absorbed. */
        @Test
        @DisplayName("refuse a null event without issuing a send")
        void aNullEventIsRefused() {
            SqsClient client = mock(SqsClient.class);

            assertThatThrownBy(() -> reporterSendingOn(client).report(null))
                    .isInstanceOf(NullPointerException.class);

            verify(client, never()).sendMessage(any(SendMessageRequest.class));
        }
    }
}
