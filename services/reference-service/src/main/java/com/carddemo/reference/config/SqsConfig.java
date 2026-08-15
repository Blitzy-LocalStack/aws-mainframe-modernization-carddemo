package com.carddemo.reference.config;

import com.carddemo.common.messaging.QueueClientBudget;
import com.carddemo.common.messaging.RethrowingDigestErrorHandler;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the queue client and the failure record for this context's date-inquiry request and reply flow.
 *
 * <h2>Purpose</h2>
 *
 * <p>This is the migrated form of the queue plumbing in {@code app/app-vsam-mq/cbl/CODATE01.cbl} (524 lines),
 * a queue-triggered CICS transaction that answers a one-thousand-character request with the system date and
 * time. That program is REFERENCE ONLY: it is read here as the specification and is never modified. Its
 * {@code 1000-CONTROL} opens three queues and holds each handle for the life of the task; the equivalent
 * long-lived handle here is a client bean with the same lifetime, and the queue NAMES leave the program
 * entirely and become configuration.</p>
 *
 * <p>The business body of the flow is not here. The one iteration the reference performs in
 * {@code 4000-PROCESS-REQUEST-REPLY}, and both of the publications described below, live in
 * {@code com.carddemo.reference.service.DateInquiryMessageListener}, which consumes the beans declared here
 * at run time. That consumer is named for legibility only: it imports nothing from this class and this class
 * imports nothing from it, so neither can drag the other into its own build graph.</p>
 *
 * <h2>What this class does not declare, and why</h2>
 *
 * <p>Assumptions: the CONSUMING side needs no bean here. The messaging starter on the class path
 * auto-configures the listener container that the annotated consumer binds to, and the polling discipline the
 * reference expresses in code is expressed as configuration instead: its bounded get interval of five
 * seconds, set by {@code MOVE 5000 TO MQGMO-WAITINTERVAL} at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L286}
 * and labelled by the in-source comment immediately above it at {@code L285}, is declared once as
 * {@code spring.cloud.aws.sqs.listener.poll-timeout} in this module's {@code application.yml}, and its
 * loop-until-empty becomes the container's own polling. This class sets no equivalent in Java. Declaring one
 * would put the same number in two places that are edited by different people, and the two would diverge
 * silently because nothing compares them.</p>
 *
 * <p>Alternatives Considered: declaring a listener-container factory here, which is the usual way to reach
 * container settings from Java. Rejected because the starter already publishes one, so a second would stand
 * beside it rather than replace it, and the two could resolve a different region or a different credential
 * chain -- a disagreement that surfaces only at run time, as a consumer polling one account while the
 * publisher below addresses another. The supported way to influence the auto-configured container is to
 * publish a collaborator it looks up, which is what the error handler below is.</p>
 *
 * <h2>Queue destinations are resolved, never written down</h2>
 *
 * <p>Assumptions: no queue name, address or account identifier appears in this class, and none is defaulted
 * here either. The destinations resolve from the environment through
 * {@code carddemo.reference.inquiry.request-queue}, {@code carddemo.reference.inquiry.reply-queue} and
 * {@code carddemo.reference.inquiry.error-queue}, each of which is bound to an environment variable in
 * {@code application.yml} with no fallback, so an unset destination stops start-up instead of producing a
 * consumer that takes requests and can answer none of them. This follows the reference rather than departing
 * from it: the baseline does not carry its input destination as a literal either, taking it from the CICS
 * trigger message at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L146}, and it initialises all four name fields
 * in its {@code QUEUE-INFO} group ({@code L92} to {@code L96}) to spaces before filling them at run time.</p>
 *
 * <p>Refactoring Rationale: the two destinations the baseline DOES hold as literals -- the reply queue at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl:L147} and the error sink at {@code L243} -- could not be carried
 * across under their original spelling, so the rename is forced rather than a matter of taste. Both are
 * dotted upper-case names in the shape of a multi-part qualified dataset name, and the target queue service
 * admits only alphanumerics, hyphens and underscores in a queue name, reserving the dot for one trailing
 * suffix. Preserving the original spelling was the alternative and it does not fail subtly: queue creation is
 * refused outright by the service, so the environment cannot be provisioned at all. The target names are
 * therefore lower-case, hyphen-separated and suffixed with the environment they belong to, and they are
 * supplied to this service as configuration rather than being written anywhere in it.</p>
 *
 * <p>Alternatives Considered: provisioning these destinations as the ordering-preserving queue class, the one
 * that admits a group discriminator and suppresses duplicates within a de-duplication window. Rejected
 * because this flow has no ordering requirement to preserve -- each request is answered from the clock and no
 * answer depends on another -- so that class would impose its per-group throughput ceiling and its stricter
 * naming rule while buying nothing back. Ordering that is genuinely load-bearing belongs to the per-card
 * authorization path in a different bounded context, where the card number is the group discriminator and two
 * decisions on one card must not cross. These are ordinary queues, and the distinction is deliberate.</p>
 *
 * <p>Assumptions: the redrive and encryption posture of these queues is provisioned by {@code infra/modules/sqs}
 * and is not reproduced here. Each has a companion dead-letter destination taking delivery after the fifth
 * receive, and each is encrypted at rest under a customer-managed key. Nothing in this class creates,
 * configures or asserts any of that; a queue that is absent or wrongly configured is reported at start-up by
 * the {@code queue-not-found-strategy} of {@code fail} declared in {@code application.yml}, rather than being
 * created unmanaged by the application.</p>
 *
 * <h2>Both publications exist, and the error path terminates</h2>
 *
 * <p>Assumptions: the reference publishes on two distinct paths and the target owes both. The reply path is
 * {@code 4100-PUT-REPLY}, whose header stands at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L366} and whose
 * {@code MQPUT} is at {@code L383} against the output handle, reporting {@code 'MQPUT ERR'} at {@code L400}
 * when it fails. The error path is {@code 9000-ERROR}, whose header stands at {@code L405} and whose
 * {@code MQPUT} is at {@code L420} against the error handle, reporting the same literal at {@code L437}. The
 * client below serves both, which is why it is one bean rather than two.</p>
 *
 * <p>Assumptions: the error path TERMINATES rather than re-entering itself. When its own publication fails
 * the reference proceeds to termination at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L439} instead of reporting
 * the failure of a report, so the target must not answer a failed error publication with another error
 * publication. A recursive report is not merely redundant here: the condition that stopped the first
 * publication is the condition that would stop the second, so the recursion would not terminate on its own.</p>
 *
 * <p>Assumptions: both publications carry the same declared transport width. The reference moves its payload
 * through {@code MQ-BUFFER}, declared {@code PIC X(1000)} at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L50},
 * and sets the length to that same 1000 on the get at {@code L291}, on the reply publication at {@code L372}
 * and on the error publication at {@code L411}. The reply body is framed to that width by this context's
 * reply mapper rather than by anything here, so no width is declared in this class.</p>
 *
 * <h2>Correlation carries two identifiers, not one</h2>
 *
 * <p>Assumptions: a reply owes BOTH identifiers, because the reference restores both before publishing. It
 * captures the requester's values on the way in across {@code app/app-vsam-mq/cbl/CODATE01.cbl:L313} to
 * {@code L321} -- the message identifier at {@code L313}, the correlation identifier at {@code L314}, the
 * reply destination at {@code L315}, and the three saved copies at {@code L319} to {@code L321} -- and
 * restores the saved message identifier at {@code L373} and the saved correlation identifier at {@code L374}
 * immediately before the publication at {@code L383}. A reply echoing only the correlation identifier would
 * therefore carry less than the baseline carried. The outbound attribute names are consequently
 * {@code correlationId}, {@code messageId} and {@code replyToQueueUrl}, alongside a {@code contentType}
 * declaring how the body is laid out.</p>
 *
 * <p>Assumptions: the correlation width is 24 bytes and that number is not arbitrary here. Both
 * {@code MQ-CORRELID} and {@code MQ-MSG-ID} are declared {@code PIC X(24)} at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl:L52} and {@code L53}, their saved copies at {@code L55} and
 * {@code L56} match, and the synchronous edge of this system already honours exactly that width through
 * {@code com.carddemo.common.web.CorrelationIdFilter}, whose {@code CORRELATION_ID_MAX_LENGTH} is 24. That
 * filter echoes an inbound identifier unchanged, generates one when none was supplied, publishes it to the
 * logging context and clears it in a {@code finally} block; the queue edge follows the same rule rather than
 * a rule of its own, so one request crossing both edges keeps one identity.</p>
 *
 * <p>Refactoring Rationale: the media type this flow declares is {@code text/plain} and NOT the delimited
 * type used by the authorization messages. The section headed "The inquiry replies declare {@code text/plain},
 * not {@code text/csv}" in {@code docs/architecture/messaging-contracts.md} settles this: the delimited type
 * "belongs to the authorization flow alone", which really is comma-separated, while both positional inquiry
 * replies carry {@code text/plain}. The reference sets {@code MQFMT-STRING} on every publication and the reply
 * this context renders is a positional block located by byte position, so a consumer that split it on commas
 * would recover one field holding the whole record. The value is declared by the consumer that stamps it, not
 * here; it is recorded here because a reader arriving from the descriptor mapping table will otherwise expect
 * the delimited value and read its absence as an omission.</p>
 *
 * <h2>The reply destination, where the reference disagrees with itself</h2>
 *
 * <p>Assumptions: the reference appears to name its reply destination twice and the two do not agree. It
 * hard-codes the destination at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L147} and opens the output handle
 * from that very field at {@code L210}, with the open itself at {@code L216}; separately it captures the
 * requester's own reply destination at {@code L315} and saves it at {@code L320}. The saved copy is then
 * never read anywhere in the program, and the publication at {@code L383} targets the handle opened from the
 * literal. The baseline therefore collects the requester's dynamic reply destination and discards it. The
 * target resolves the ambiguity by PREFERRING an inbound {@code replyToQueueUrl} and falling back to the
 * configured reply destination when none is supplied.</p>
 *
 * <p>Trade-offs: neither half of that rule is free. Preferring the inbound value honours what the baseline
 * troubled itself to capture at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L320} and lets a requester be
 * answered where it is actually listening, at the cost that a requester now influences where a reply is sent.
 * Falling back to the configured destination reproduces the baseline's observable behaviour exactly, at the
 * cost that a requester listening elsewhere is not answered. Taking the inbound value first and the
 * configured destination second is what allows both to be true at once. This is a documented divergence
 * rather than a repair of the reference, and it is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is authored elsewhere and only referenced
 * from here.</p>
 *
 * <h2>The receive is not selective, and the handler is not stateful</h2>
 *
 * <p>Assumptions: the consumer must not filter on the way in. The reference clears both selectors before its
 * get, moving {@code MQMI-NONE} to the descriptor's message identifier at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl:L292} and {@code MQCI-NONE} to its correlation identifier at
 * {@code L293}, so it accepts whatever message is next and correlates only on the way out. Nothing here
 * requests a selective receive, and a consumer that filtered by identifier would leave every message it did
 * not select on the queue until redrive removed it.</p>
 *
 * <p>Assumptions: statelessness here is a property the baseline already had rather than something this
 * migration introduces. The reference declares {@code PROGRAM-ID. CODATE01 IS INITIAL.} at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl:L2}, which guarantees fresh working storage on every invocation,
 * and its {@code LINKAGE SECTION} at {@code L123} is empty, so no caller passes it a communication area.
 * Elsewhere in this migration the removal of conversational state is a change that has to be argued; on this
 * flow it is a preservation, and saying so keeps a reader from looking for the state that was removed.</p>
 *
 * <h2>Two patterns deliberately absent</h2>
 *
 * <p>Alternatives Considered: a relay table written inside the same database transaction as the work and
 * published after that transaction commits -- the arrangement the authorization context needs. Rejected for
 * this flow because the condition that motivates it is absent here. The reference brackets everything in one
 * unit of work: it takes a syncpoint at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L275} through {@code L277},
 * its get options include the syncpoint option at {@code L296} through {@code L299}, its reply publication
 * options include it at {@code L379} through {@code L381}, and its error publication options include it at
 * {@code L416} through {@code L418}. All three operations sit inside the unit of work, so there is no window
 * in which the data is committed and the reply is lost. That maps directly onto a visibility period plus
 * delete-on-success, which is what the starter already does. The authorization consumer is the one whose
 * baseline publishes outside its commit and therefore genuinely needs the relay; adding one here would add a
 * table, a publication step and a delivery delay to close a window that does not exist.</p>
 *
 * <p>Trade-offs: no client-side retry facility is declared either. The durable retry tier for this flow is
 * the queue's own redelivery after the visibility period expires, escalating to the dead-letter destination
 * at the fifth receive, and that tier survives a task being replaced while an in-process retry loop does not.
 * An in-process loop would also hold the message beyond its visibility period, which is the failure the
 * client bounds below exist to prevent -- so the two would work against each other. The cost accepted is that
 * a transient failure is answered a visibility period later rather than within the same invocation.</p>
 *
 * <h2>One external contract is not vendored here</h2>
 *
 * <p>Assumptions: the queue-manager constant and structure definitions the reference compiles against are an
 * external contract that this repository does not carry. The program copies six system definitions --
 * {@code CMQGMOV} at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L71}, {@code CMQPMOV} at {@code L75},
 * {@code CMQMDV} at {@code L79}, {@code CMQODV} at {@code L83}, {@code CMQV} at {@code L87} and
 * {@code CMQTML} at {@code L90} -- and none of the six is present in this repository. Every option and
 * constant name cited above is therefore read from the calling program's own usage rather than from a
 * definition that can be opened here, and a reader who cannot find one of those names has not missed a file.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SqsConfig {

    /**
     * Names this service's consumer in every failure record the shared handler writes.
     *
     * <p>Assumptions: the value matches the prefix this consumer's own event names already use, so a query
     * selecting {@code date.inquiry} records finds its failures alongside its outcomes rather than under a
     * second vocabulary. It is also the only field distinguishing this consumer's failures from another
     * service's once both are in one log stream.</p>
     */
    public static final String LISTENER_SOURCE = "date.inquiry";

    /**
     * Publishes the shared consumer error handler, so a failed delivery is recorded without its message text.
     *
     * <p>Purpose: the starter's own failure record is switched off by name in
     * {@code carddemo-common-defaults.yml}, because it renders the throwable as a trailing argument and the
     * logging facade then prints every message in the cause chain. Those messages are written by a database
     * driver, a codec or a validation library, and on this queue they can quote a request value verbatim.
     * This bean is the replacement record: the chain of exception TYPES and the frames, carrying no message
     * text, with the failure rethrown unchanged.</p>
     *
     * <p>Refactoring Rationale: the handler is published as a BEAN rather than applied by reaching into the
     * container after it is built. The starter's own factory method already looks an {@code ErrorHandler} up
     * from the context and installs it, so a bean is the supported extension point and it needs no reference
     * to the container at all -- which is what lets this class tune the auto-configured container without
     * declaring the competing factory the class documentation above rejects.</p>
     *
     * <p>Assumptions: the declared return type has to be the starter's interface rather than the concrete
     * handler. The context lookup is by that interface, so a bean declared as its implementation type would
     * be created successfully, would never be installed, and would leave no symptom other than the framework
     * record it was meant to replace reappearing.</p>
     *
     * <p>Assumptions: the handler RETHROWS, and that is load-bearing rather than tidy. The starter installs
     * its error-handler stage as a recovery step, so a handler returning normally would leave the pipeline
     * result successful and the acknowledgement stage that follows would DELETE the message -- no redelivery
     * when the visibility period expires and no escalation to the dead-letter destination at the fifth
     * receive. The shared handler's own tests cover that behaviour; nothing here restates it.</p>
     *
     * <p>Alternatives Considered: catching the failure inside the consumer and returning normally, so that no
     * handler were needed at all. Rejected because it is the same defect relocated: a request whose answer
     * could not be produced would be acknowledged as though it had been answered, and the requester would
     * wait for a reply that no longer exists anywhere in the system.</p>
     *
     * <p>Trade-offs: this bean deliberately carries no condition, unlike the client below it. A condition
     * would let the handler be absent whenever something else happened to publish an error handler first, and
     * an absent handler here is not a quieter log -- the framework's full-throwable record is suppressed
     * unconditionally and by name, not by the presence of a replacement, so the suppression would stand with
     * nothing behind it. A control that must not be conditionally absent is declared unconditionally; a test
     * needing a different handler overrides the definition by name instead.</p>
     *
     * @return the shared handler, declared as the starter's context-supplied error-handler interface so its
     *     factory method finds it, never {@code null}
     */
    @Bean
    public ErrorHandler<Object> dateInquiryListenerErrorHandler() {
        return new RethrowingDigestErrorHandler<>(LISTENER_SOURCE);
    }

    /**
     * Supplies the one queue client this context publishes its reply and its error report with.
     *
     * <p>Purpose: this is the long-lived handle the reference holds open for the life of its task, opened for
     * output at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L216} and for the error sink at {@code L251}. One
     * client serves both publications rather than two, because the two differ only in the destination they
     * address and the destination is resolved per call from configuration.</p>
     *
     * <p>Assumptions: there is no connection handle to manage. The reference declares {@code MQ-HCONN} with a
     * value of zero at {@code app/app-vsam-mq/cbl/CODATE01.cbl:L44} and never assigns it, passing that zero to
     * every queue call it makes, and it issues no connect or disconnect at all -- because CICS supplies the
     * queue-manager connection to the task. In the target the client owns its own connection pooling, so the
     * parallel holds and nothing here opens or closes a connection either.</p>
     *
     * <p>Refactoring Rationale: the client is given a whole-call bound and a per-attempt bound, and it had
     * neither. The software development kit defaults both to no bound at all, so a stalled publication would
     * be retried indefinitely -- and this publication happens INSIDE the message handler, before the consumer
     * returns, so an unbounded call is an unbounded handler. A handler outliving its message's visibility
     * period does not merely run late: the queue makes the request visible again, a second consumer takes it,
     * and two handlers act on one request at once. {@link QueueClientBudget} is what refuses that arrangement
     * at start-up, by requiring the whole-call bound to be strictly shorter than the visibility period the
     * queue is provisioned with.</p>
     *
     * <p>Assumptions: the visibility period arrives as a PROPERTY rather than being read from the queue. It is
     * set by {@code infra/modules/sqs}, whose {@code visibility_timeout_seconds} defaults to 60, and the
     * default below is that same 60 so an unconfigured context validates against what the infrastructure
     * actually provisions. Reading it from the queue at start-up was the alternative and is rejected twice
     * over: it would make context refresh depend on a reachable queue, and it would pass vacuously in every
     * test and local run where no queue exists -- which is precisely where this check has to hold.</p>
     *
     * <p>Assumptions: the budget is constructed BEFORE the builder is touched, so a context that cannot
     * satisfy the relationship fails without having built a client or consulted the configurer. Validating
     * afterwards would leave a client constructed and then abandoned on the failing path.</p>
     *
     * <p>Trade-offs: the bounds are applied through the CONSUMER form of {@code overrideConfiguration}, which
     * mutates the configuration the starter's configurer already built. The value form would replace it
     * wholesale, discarding the retry policy, the user agent and any execution interceptor the starter had
     * installed -- a loss showing up only as absent telemetry and absent retries, neither of which fails a
     * test.</p>
     *
     * <p>Assumptions: the builder is handed to the starter's own configurer rather than being configured here,
     * so region, credentials and any endpoint override resolve exactly as they do for the client the starter
     * builds for the consumer side. Setting them here would create a second place the two could disagree about
     * which account they address, and a publisher pointed at a different endpoint from its consumer fails only
     * at run time and only under load.</p>
     *
     * <p>Trade-offs: what remains of one message's handling after this call -- the reply rendering and any
     * database work -- is bounded by settings this class does not own, so it is not added to the sum checked
     * below. The relationship this class can actually verify is the queue call against the visibility period,
     * and overstating the coverage would be worse than stating the part it owns.</p>
     *
     * @param configurer the starter's client-builder configurer, which applies the region, credential and
     *     endpoint resolution shared with the auto-configured client; must not be {@code null}
     * @param apiCallTimeoutMillis the whole-call bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_TIMEOUT}; must be positive and strictly shorter than the
     *     visibility period
     * @param apiCallAttemptTimeoutMillis the per-attempt bound in milliseconds, from
     *     {@link QueueClientBudget#PROPERTY_API_CALL_ATTEMPT_TIMEOUT}; must be positive and must not exceed
     *     the whole-call bound
     * @param visibilityTimeoutSeconds how long a received message stays invisible to other consumers, from
     *     {@link QueueClientBudget#PROPERTY_VISIBILITY_TIMEOUT}; must be positive
     * @return the queue client serving both the reply publication and the error publication, never
     *     {@code null}
     * @throws IllegalStateException if the three bounds do not satisfy {@link QueueClientBudget}, so the
     *     refusal arrives at start-up naming the property whose relationship does not hold
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
