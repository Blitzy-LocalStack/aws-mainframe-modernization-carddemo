package com.carddemo.account.config;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
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
     * @param configurer the starter's client-builder configurer; must not be {@code null}
     * @return the queue client, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SqsClient.builder()).build();
    }
}
