package com.carddemo.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.carddemo.batch.service.BatchErrorPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies that this module's terminal-sink configuration is reachable, and reachable only when
 * configured.
 *
 * <p><b>Purpose.</b> Refactoring Rationale: this class had no test at all, and that absence is exactly
 * how it came to be a configuration nothing used. It validated an address, framed a media type, bounded
 * two source identifiers and built send requests, and its only callers were the record's own arithmetic
 * -- so the module documented a terminal error sink, the orchestration carried a failure-notification
 * state that watched it, and no message was ever put on it. Two properties therefore need proving rather
 * than describing: that a producer bean EXISTS when a deployment supplies the sink's address, and that
 * absolutely nothing is contributed when it does not.</p>
 *
 * <p>Assumptions: the queue client is supplied as a user bean in every case that expects a context to
 * refresh, which makes the module's own client method back off through its
 * {@code @ConditionalOnMissingBean}. That is deliberate: building the real client needs the AWS
 * client-builder configurer that only the cloud starter's own auto-configuration contributes, and
 * dragging that in would make these cases assert the starter's wiring rather than this module's gate.</p>
 */
class SqsConfigTest {

    /** A standard-queue address. An ordered one is refused by the binding, which is asserted below. */
    private static final String QUEUE_URL =
            "https://sqs.us-east-1.amazonaws.com/000000000000/carddemo-error-dev";

    /** The property that gates the whole configuration, spelled as the class publishes it. */
    private static final String GATE = SqsConfig.PROPERTY_ERROR_QUEUE_URL + "=" + QUEUE_URL;

    /**
     * Builds a runner carrying this configuration plus the two collaborators a producer needs.
     *
     * @return the runner, never {@code null}
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(SqsConfig.class)
                .withBean(SqsClient.class, () -> mock(SqsClient.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    /** The gate itself: what the configuration contributes, and when. */
    @Nested
    @DisplayName("the property gate")
    class Gate {

        /**
         * Confirms a configured deployment gets a producer, and that the binding's defaults reach it.
         */
        @Test
        @DisplayName("contributes a producer and a binding when the sink address is supplied")
        void configuredDeploymentGetsAProducer() {
            runner().withPropertyValues(GATE).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(BatchErrorPublisher.class);
                assertThat(context).hasSingleBean(SqsConfig.ErrorSinkBinding.class);
                // WHY : Assumptions: the binding's four members are asserted here rather than in a
                //       separate case, because this is the only place they are produced by the
                //       CONTAINER from properties. Constructing the record directly would assert the
                //       record's own arithmetic and say nothing about whether the defaults reach it.
                SqsConfig.ErrorSinkBinding binding = context.getBean(SqsConfig.ErrorSinkBinding.class);
                assertThat(binding.queueUrl()).isEqualTo(QUEUE_URL);
                assertThat(binding.contentType()).isEqualTo(SqsConfig.DEFAULT_CONTENT_TYPE);
                assertThat(binding.sourceApplication()).isEqualTo("CARDDEMO");
                assertThat(binding.sourceProgram()).isEqualTo("BATCHSVC");
            });
        }

        /**
         * Confirms an unconfigured deployment still refreshes and contributes no sink wiring.
         */
        @Test
        @DisplayName("contributes nothing at all when the sink address is absent")
        void unconfiguredDeploymentGetsNothing() {
            // WHY : Assumptions: the assertion is on ABSENCE of the producer and the binding together,
            //       not on the context failing. A deployment that publishes no diagnostics must still
            //       run its jobs, so the correct behaviour of an absent address is a context that
            //       refreshes with no sink wiring in it -- and asserting both beans is what stops a
            //       future @Bean here from escaping the gate by being declared outside the class.
            runner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(BatchErrorPublisher.class);
                assertThat(context).doesNotHaveBean(SqsConfig.ErrorSinkBinding.class);
            });
        }

        /**
         * Confirms the Java half of the property name agrees with the variable the roots publish.
         */
        @Test
        @DisplayName("the gate names the property the environment roots publish")
        void gateNamesThePublishedProperty() {
            ConditionalOnProperty gate = SqsConfig.class.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).isNotNull();
            // WHY : Assumptions: the annotation is read reflectively rather than trusted from the
            //       source, because the two halves of this contract live in different languages. The
            //       infrastructure publishes the container variable CARDDEMO_MESSAGING_ERROR_QUEUE_URL,
            //       and relaxed binding maps it onto this exact dotted spelling; a rename on either side
            //       leaves a gate that is never satisfied and a variable that binds nothing, with no
            //       error anywhere. This line is the Java half of that agreement.
            assertThat(gate.name()).containsExactly("carddemo.messaging.error-queue-url");
            assertThat(SqsConfig.PROPERTY_ERROR_QUEUE_URL)
                    .isEqualTo("carddemo.messaging.error-queue-url");
        }
    }

    /** The refusals the binding makes while the context is still starting. */
    @Nested
    @DisplayName("startup refusals")
    class Refusals {

        /**
         * Confirms an ordered destination is refused while starting, not on the failure path.
         */
        @Test
        @DisplayName("an ordered address fails the context rather than the first send")
        void orderedAddressFailsAtStartup() {
            runner()
                    .withPropertyValues(SqsConfig.PROPERTY_ERROR_QUEUE_URL + "="
                            + QUEUE_URL + SqsConfig.FIFO_QUEUE_SUFFIX)
                    .run(context -> assertThat(context).hasFailed());
        }

        /**
         * Confirms an over-wide source identifier fails startup rather than being truncated.
         */
        @Test
        @DisplayName("a source identifier wider than its copybook field fails the context")
        void overWideSourceIdentifierFailsAtStartup() {
            runner()
                    .withPropertyValues(GATE,
                            SqsConfig.PROPERTY_ERROR_SOURCE_APPLICATION + "=CARDDEMOAPPLICATION")
                    .run(context -> assertThat(context).hasFailed());
        }

        /**
         * Confirms a blank media type is refused, so no body is published undeclared.
         */
        @Test
        @DisplayName("a blank media type fails the context")
        void blankContentTypeFailsAtStartup() {
            runner()
                    .withPropertyValues(GATE, SqsConfig.PROPERTY_ERROR_CONTENT_TYPE + "=   ")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
