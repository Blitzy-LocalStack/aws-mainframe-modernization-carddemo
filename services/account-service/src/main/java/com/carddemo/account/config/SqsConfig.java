package com.carddemo.account.config;

import com.carddemo.common.messaging.QueueClientBudget;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the queue client this context publishes inquiry replies with.
 *
 * <p>This is the migrated form of the connection setup the baseline performs in
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl}, whose {@code 2400-OPEN-OUTPUT-QUEUE} at physical lines 205 to 249
 * and {@code 2100-OPEN-ERROR-QUEUE} at physical lines 250 to 288 each open a named queue once and hold the
 * handle for the life of the task. Here the equivalent handle is a client bean with the same lifetime, and the
 * queue NAMES move out of the program into configuration -- which the baseline itself already treats as
 * configuration, since it initialises all four name fields to spaces and fills them at run time.</p>
 *
 * <p>Assumptions: the CONSUMING side needs no bean here. The starter on the class path auto-configures the
 * listener container that {@code @SqsListener} binds to, and the polling discipline the baseline expresses in
 * code -- its five-second bounded wait at physical line 286 and its loop-until-empty at physical lines 216 to
 * 217 -- is expressed as container properties on that annotation instead. Declaring a container factory here
 * would duplicate the auto-configured one and the two could disagree.</p>
 *
 * <p>Trade-offs: this supplies the SYNCHRONOUS client while the starter auto-configures an asynchronous one
 * for the listener container. The reply is sent from inside the listener's own transaction and the listener
 * must not return until the send has succeeded -- returning earlier would let the framework delete a request
 * that had not been answered -- so it would have to block on a future immediately in any case. Blocking
 * explicitly on a synchronous call is the same wait without the ambiguity about which thread the continuation
 * runs on, and that ambiguity matters here because the surrounding transaction's persistence context is not
 * safe to touch from another thread.</p>
 *
 * <p>Assumptions: the builder is handed to the starter's own configurer rather than being configured here, so
 * region, credentials and any endpoint override resolve exactly as they do for the auto-configured client.
 * Setting them here would create a second place the two clients could disagree about which account and region
 * they address, and a publisher pointed at a different endpoint from the consumer fails only at run time, only
 * on the reply path.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SqsConfig {

    /**
     * Supplies the synchronous queue client the inquiry consumer publishes replies and diagnostics with.
     *
     * <p>Refactoring Rationale: the client is given a whole-call bound and a per-attempt bound, and it had
     * neither. The software development kit's default for both is no bound at all, so a stalled publish
     * retried indefinitely -- and this publish happens INSIDE the message handler, before the listener
     * returns, so an unbounded call is an unbounded handler. A handler that outlives its message's
     * visibility period does not merely run late: the queue makes the request visible again, a second
     * consumer takes it, and two handlers act on one request at once. {@link QueueClientBudget} is what
     * refuses that arrangement at startup, by requiring the whole-call bound to be strictly shorter than
     * the visibility period the queue is provisioned with.</p>
     *
     * <p>Assumptions: the visibility period is a PROPERTY here rather than a value read from the queue.
     * It is set by {@code infra/modules/sqs}, whose {@code visibility_timeout_seconds} defaults to 60, and
     * the default below is that same 60 so an unconfigured context validates against what the
     * infrastructure actually provisions. Reading it from the queue at startup was the alternative and is
     * rejected: it would make context refresh depend on a reachable queue, and it would silently pass in
     * every test and local run where no queue exists.</p>
     *
     * <p>Trade-offs: the bounds are applied through the CONSUMER form of
     * {@code overrideConfiguration}, which mutates the configuration the starter's configurer already
     * built. The value form would replace it, discarding the retry policy, the user agent and any
     * execution interceptor the starter had installed -- a loss that shows up only as absent telemetry
     * and absent retries, neither of which fails a test.</p>
     *
     * <p>Assumptions: what remains of one message's handling after this call -- the database work -- is
     * bounded by the connection pool and driver settings this service configures, not here, so it is not
     * added to the sum below. The sum this class can verify is the queue call against visibility, and
     * overstating what it verifies would be worse than stating the part it owns.</p>
     *
     * @param configurer the starter's client-builder configurer; must not be {@code null}
     * @param apiCallTimeoutMillis the whole-call bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_TIMEOUT}; must be positive and shorter than the
     *     visibility period
     * @param apiCallAttemptTimeoutMillis the per-attempt bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_ATTEMPT_TIMEOUT}; must be positive and must not
     *     exceed the whole-call bound
     * @param visibilityTimeoutSeconds how long a received message stays invisible to other consumers,
     *     from {@link QueueClientBudget#PROPERTY_VISIBILITY_TIMEOUT}; must be positive
     * @return the queue client, never {@code null}
     * @throws IllegalStateException if the three bounds do not satisfy {@link QueueClientBudget}, so the
     *     failure arrives at startup naming the relationship that does not hold
     */
    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(AwsClientBuilderConfigurer configurer,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_TIMEOUT + ":10000}")
            long apiCallTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT + ":5000}")
            long apiCallAttemptTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_VISIBILITY_TIMEOUT + ":60}")
            long visibilityTimeoutSeconds) {

        QueueClientBudget budget = new QueueClientBudget(
                Duration.ofMillis(apiCallTimeoutMillis),
                Duration.ofMillis(apiCallAttemptTimeoutMillis),
                Duration.ofSeconds(visibilityTimeoutSeconds));

        return configurer.configure(SqsClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(budget.apiCallTimeout())
                        .apiCallAttemptTimeout(budget.apiCallAttemptTimeout()))
                .build();
    }
}
