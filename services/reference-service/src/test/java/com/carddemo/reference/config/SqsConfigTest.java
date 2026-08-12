package com.carddemo.reference.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.messaging.QueueClientBudget;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Holds the queue client {@link SqsConfig} publishes date-conversion replies with to its time bounds.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: the client was built with no whole-call bound and no per-attempt bound, which
 * is the software development kit's own default -- a stalled call retries and waits without end. The reply
 * is published from INSIDE the inquiry handler, before the listener returns, so an unbounded call is an
 * unbounded handler; and a handler that outlives its message's visibility period lets the queue make the
 * request visible again, a second consumer take it, and two handlers answer one inquiry. These cases pin
 * the startup refusal, because once the service is running the symptom is indistinguishable in a log from
 * ordinary redelivery.</p>
 *
 * <p>Assumptions: the refusals are asserted against {@link QueueClientBudget}'s published property names
 * rather than against a literal repeated here, so a rename of a property fails at compilation in this file
 * instead of leaving an assertion that quietly matches nothing.</p>
 *
 * <p>Assumptions: the client-builder configurer is a stub in every case. Nothing issues a call, so a client
 * that would fail on its first request is sufficient -- and preferable to the real configurer, which
 * resolves a region and a credentials chain from the environment and would make these cases pass or fail on
 * how the runner happens to be configured.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class SqsConfigTest {

    /** The whole-call bound this module's packaged configuration declares, in milliseconds. */
    private static final long API_CALL_TIMEOUT_MS = 10_000L;

    /** The per-attempt bound this module's packaged configuration declares, in milliseconds. */
    private static final long API_CALL_ATTEMPT_TIMEOUT_MS = 5_000L;

    /** The visibility period the infrastructure module provisions, in seconds. */
    private static final long VISIBILITY_TIMEOUT_SECONDS = 60L;

    /**
     * Confirms the declared bounds are accepted and reach the built client rather than only being checked.
     *
     * <p>Assumptions: the bounds are read back off the CLIENT, not off the value object that validated
     * them, because the class under test could satisfy a validation-only assertion while applying nothing
     * -- which is precisely the state the client was in before, unvalidated and unbounded.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the declared bounds are applied to the built client")
    void theDeclaredBoundsReachTheBuiltClient() {
        SqsClient client = new SqsConfig().sqsClient(buildableConfigurer(), API_CALL_TIMEOUT_MS,
                API_CALL_ATTEMPT_TIMEOUT_MS, VISIBILITY_TIMEOUT_SECONDS);

        assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                .contains(Duration.ofMillis(API_CALL_TIMEOUT_MS));
        assertThat(client.serviceClientConfiguration().overrideConfiguration()
                .apiCallAttemptTimeout()).contains(Duration.ofMillis(API_CALL_ATTEMPT_TIMEOUT_MS));
    }

    /**
     * Confirms a whole-call bound that reaches the visibility period stops startup.
     *
     * <p>Assumptions: equality is refused rather than accepted. A call permitted to run for exactly the
     * visibility period leaves the outcome to scheduling -- whether the queue redelivers first or the call
     * returns first is not something either side decides -- and a bound whose effect depends on that is not
     * a bound.</p>
     *
     * <p>Assumptions: the configurer is asserted UNTOUCHED, which places the refusal ahead of client
     * construction so the context fails to refresh rather than starting with a client that cannot meet its
     * deadline.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a whole-call bound reaching the visibility period is refused at startup")
    void aCallBoundReachingVisibilityIsRefused() {
        AwsClientBuilderConfigurer configurer = mock(AwsClientBuilderConfigurer.class);
        SqsConfig config = new SqsConfig();
        long visibilityAsMillis = VISIBILITY_TIMEOUT_SECONDS * 1_000L;

        assertThatThrownBy(() -> config.sqsClient(configurer, visibilityAsMillis,
                API_CALL_ATTEMPT_TIMEOUT_MS, VISIBILITY_TIMEOUT_SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QueueClientBudget.PROPERTY_API_CALL_TIMEOUT)
                .hasMessageContaining(QueueClientBudget.PROPERTY_VISIBILITY_TIMEOUT);
        verifyNoInteractions(configurer);
    }

    /**
     * Confirms a per-attempt bound wider than the whole-call bound stops startup.
     *
     * <p>Assumptions: this is refused because the per-attempt bound would then be unreachable -- the whole
     * call expires first, so the value silently describes a limit that never applies, and a configuration
     * that cannot take effect is one an operator will believe is in force.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a per-attempt bound wider than the whole-call bound is refused at startup")
    void anUnreachableAttemptBoundIsRefused() {
        AwsClientBuilderConfigurer configurer = mock(AwsClientBuilderConfigurer.class);
        SqsConfig config = new SqsConfig();

        assertThatThrownBy(() -> config.sqsClient(configurer, API_CALL_ATTEMPT_TIMEOUT_MS,
                API_CALL_TIMEOUT_MS, VISIBILITY_TIMEOUT_SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT);
        verifyNoInteractions(configurer);
    }

    /**
     * Confirms a bound of no duration stops startup on each of the three settings.
     *
     * <p>Assumptions: zero is what a property set to an empty or mistyped value converts to, so it is
     * exercised rather than only the negative a hand-edited document produces. A check written as
     * {@code &lt; 0} would admit the first of them and leave a client bounded to no time at all, which
     * fails every call immediately.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a bound of no duration is refused on each of the three settings")
    void aNonPositiveBoundIsRefused() {
        AwsClientBuilderConfigurer configurer = mock(AwsClientBuilderConfigurer.class);
        SqsConfig config = new SqsConfig();

        assertThatThrownBy(() -> config.sqsClient(configurer, 0L, API_CALL_ATTEMPT_TIMEOUT_MS,
                VISIBILITY_TIMEOUT_SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QueueClientBudget.PROPERTY_API_CALL_TIMEOUT);
        assertThatThrownBy(() -> config.sqsClient(configurer, API_CALL_TIMEOUT_MS, 0L,
                VISIBILITY_TIMEOUT_SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT);
        assertThatThrownBy(() -> config.sqsClient(configurer, API_CALL_TIMEOUT_MS,
                API_CALL_ATTEMPT_TIMEOUT_MS, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QueueClientBudget.PROPERTY_VISIBILITY_TIMEOUT);
        verifyNoInteractions(configurer);
    }

    /**
     * Builds a stub client-builder configurer that supplies just enough for a builder to build.
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
        java.lang.reflect.Method bean = SqsConfig.class.getDeclaredMethod("dateInquiryListenerErrorHandler");

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
        assertThat(new SqsConfig().dateInquiryListenerErrorHandler())
                .isInstanceOf(com.carddemo.common.messaging.RethrowingDigestErrorHandler.class);
        assertThat(SqsConfig.LISTENER_SOURCE)
                .as("the source is the only field distinguishing this listener's failures from another"
                        + " service's in one log stream")
                .isEqualTo("date.inquiry");
    }

}
