package com.carddemo.authorization.config;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the queue client this context publishes replies with.
 *
 * <p>This is the migrated form of the connection setup the baseline performs in
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} paragraph {@code 1100-OPEN-REQUEST-QUEUE} at
 * lines 255 to 286. That paragraph opens a named queue for input once and holds the handle for the life
 * of the task; here the equivalent handle is a client bean with the same lifetime, and the queue NAMES
 * move out of the program into configuration so the same image runs against a development and a
 * production queue without being rebuilt.</p>
 *
 * <p>Assumptions: the CONSUMING side needs no bean here at all. The starter on the class path
 * auto-configures the listener container that {@code @SqsListener} binds to, and the polling discipline
 * the baseline expresses in code -- its bounded wait and its five-hundred-message batch limit -- is
 * expressed as container properties on that annotation instead. Declaring a container factory here would
 * duplicate the auto-configured one and the two could disagree.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SqsConfig {

    /**
     * Supplies the synchronous queue client the outbox publisher sends replies with.
     *
     * <p>Trade-offs: this is the SYNCHRONOUS client, while the starter auto-configures an asynchronous
     * one for the listener container. The publisher sends inside a database transaction that is holding
     * row locks, and it must know each send's outcome before it decides what to write on the row, so it
     * would have to block on a future immediately anyway. Blocking explicitly on a synchronous call is
     * the same wait with none of the ambiguity about which thread the continuation runs on -- and the
     * continuation matters here, because it touches a transaction-bound persistence context that is not
     * safe to use from another thread.</p>
     *
     * <p>Assumptions: the builder is handed to the starter's own configurer rather than being configured
     * here, so region, credentials and any endpoint override resolve exactly as they do for the
     * auto-configured client. Setting them here instead would create a second place the two clients could
     * disagree about which account and region they are talking to -- and a publisher pointed at a
     * different endpoint from the consumer fails only at run time, on the reply path, under load.</p>
     *
     * @param configurer the starter's client-builder configurer; must not be {@code null}
     * @return the queue client, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SqsClient.builder()).build();
    }
}
