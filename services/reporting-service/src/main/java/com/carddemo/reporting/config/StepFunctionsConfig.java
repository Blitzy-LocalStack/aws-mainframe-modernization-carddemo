package com.carddemo.reporting.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Supplies the orchestration client through which an on-demand report execution is started.
 *
 * <h2>Purpose</h2>
 *
 * <p>The baseline submits an on-demand report by writing job control text to a transient data queue:
 * {@code app/csd/CARDDEMO.CSD} defines that queue across L499 to L505 with {@code DDNAME(INREADER)}
 * at L501, and {@code app/cbl/CORPT00C.cbl} reaches it from the paragraph at L462. There is no cloud
 * analogue of an internal reader, so the submission becomes a {@code StartExecution} call on a second,
 * smaller state machine, and this class is where the client that makes that call is built.
 *
 * <h2>Assumptions: the client is built here and nowhere else</h2>
 *
 * <p>The package charter beside this file names this class as the one place an orchestration client
 * bean may be declared. Alternatives Considered: constructing a client inside the service that uses
 * it, which is shorter and needs no configuration class. Rejected because a client constructed at a
 * call site takes its region and its credentials from whatever the default provider chain resolves at
 * that moment, so two call sites can silently reach two different accounts, and neither one is
 * substitutable in a test. A bean is resolved once, is visible in the context, and can be replaced.
 *
 * <h2>Assumptions: the call timeout is configured rather than left to the default</h2>
 *
 * <p>An on-demand submission is issued while a caller waits on an HTTP response, so an orchestration
 * call that hangs holds a request thread for as long as the software development kit's own default
 * allows. The module's {@code application.yml} therefore declares
 * {@code carddemo.reporting.step-functions.api-call-timeout} and this class binds it, which is what
 * makes the caller learn of a failure rather than waiting on one. Trade-offs: a bounded timeout can
 * abandon a submission the orchestrator went on to accept, so a caller that retries could start the
 * same report twice; that is accepted because the state machine's execution name is derived from the
 * request rather than generated, which makes a duplicate start a name conflict the orchestrator
 * refuses rather than a second run.
 *
 * <h2>Assumptions: no region, endpoint or credential appears in this file</h2>
 *
 * <p>The client is built from the software development kit's own builder with no region and no
 * credentials provider set, so both are resolved from the task's environment -- which is how a
 * container running under an execution role obtains a credential without one being written anywhere.
 * A region literal here would bind every deployment of this module to one region, and a credential
 * here would be a secret in source, which the repository's structural prohibition does not admit.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility.
 * The four rationale labels are written in the plural, unparenthesised forms that rule gives.
 */
@Configuration(proxyBeanMethods = false)
public class StepFunctionsConfig {

    /**
     * Property key of the bounded per-call timeout.
     *
     * <p>Assumptions: the key is declared as a constant and referenced from the annotation below so
     * that the property name has one spelling in this file. A key written twice is a key that can be
     * renamed in one place.</p>
     */
    public static final String API_CALL_TIMEOUT_PROPERTY =
            "carddemo.reporting.step-functions.api-call-timeout";

    /**
     * The bounded duration a single orchestration call may take.
     */
    private final Duration apiCallTimeout;

    /**
     * Records the configured call timeout.
     *
     * <p>Assumptions: the timeout is validated here rather than trusted, because a non-positive value
     * is accepted by the software development kit's builder and then makes every call fail
     * immediately -- a failure that reads as an orchestration outage rather than as a configuration
     * error. Refusing it at start-up names the property instead.</p>
     *
     * @param apiCallTimeout the bounded duration a single orchestration call may take, bound from
     *     {@value #API_CALL_TIMEOUT_PROPERTY}
     * @throws IllegalArgumentException if the configured timeout is zero or negative
     */
    public StepFunctionsConfig(
            @Value("${" + API_CALL_TIMEOUT_PROPERTY + "}") Duration apiCallTimeout) {
        if (apiCallTimeout.isZero() || apiCallTimeout.isNegative()) {
            throw new IllegalArgumentException(API_CALL_TIMEOUT_PROPERTY
                    + " must be a positive duration but was " + apiCallTimeout);
        }
        this.apiCallTimeout = apiCallTimeout;
    }

    /**
     * Builds the orchestration client the on-demand report submission uses.
     *
     * <p>Assumptions: the bean is a singleton and that is safe, because the client is documented as
     * thread-safe and is built to be shared. Building one per request would open a connection pool
     * per request, which is the failure mode a shared client exists to prevent.</p>
     *
     * @return the orchestration client, with the configured per-call timeout applied and its region
     *     and credentials resolved from the task environment; never {@code null}
     */
    @Bean
    public SfnClient sfnClient() {
        return SfnClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .build())
                .build();
    }
}
