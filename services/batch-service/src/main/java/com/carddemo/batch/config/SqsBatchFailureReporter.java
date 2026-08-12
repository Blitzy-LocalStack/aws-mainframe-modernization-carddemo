package com.carddemo.batch.config;

import com.carddemo.batch.config.SqsConfig.ErrorSinkBinding;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.service.BatchFailureReporter;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.observability.ThrowableDigest;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends a step failure to the terminal error sink, and is the only class in this module that does.
 *
 * <p>Purpose: this is the adapter half of {@link BatchFailureReporter}. It renders a
 * {@link BatchErrorEvent} as the media type {@link SqsConfig#DEFAULT_CONTENT_TYPE} names, shapes the
 * send through {@link ErrorSinkBinding#publicationOf} so the closed three-attribute contract is built
 * in exactly one place, and puts it on the queue the migration plan's section 0.4.1.8 provisions as
 * the replacement for the reference system's {@code CARD.DEMO.ERROR} -- the single sink both reference
 * inquiry programs write to, at {@code MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME} in
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl:294} and {@code app/app-vsam-mq/cbl/CODATE01.cbl:243}.</p>
 *
 * <p>Refactoring Rationale: this class did not exist. {@link SqsConfig} declared, validated and logged
 * a sink binding, and nothing anywhere called it -- so the configuration announced publishing as
 * enabled and published nothing, on every environment, forever. The correction is a class rather than
 * a method on the binding because sending is a side effect on a network client while the binding is a
 * validated value: keeping the value free of the client is what lets one assertion cover the whole
 * attribute contract without a transport.</p>
 *
 * <p>Assumptions: this class lives in the configuration package and not beside the port, and the
 * placement is deliberate. Its three collaborators are all infrastructure -- a queue client, a
 * serialiser and a validated address -- and the package that owns the queue client is this one. The
 * inverse placement, putting the adapter beside the rule that calls it, would put the two packages in
 * a cycle, because this package already depends on that one for the ledger the reporter serves.</p>
 *
 * <p>Alternatives Considered: injecting the context's own JSON mapper bean, which the web starter this
 * module declares does register. Rejected because that bean is configured for the module's HTTP
 * surface -- which is one actuator health endpoint -- so the wire form of a published failure would
 * then change whenever anything about that surface's serialisation was tuned, and a queue consumer
 * would have no way to know. A mapper owned by this class makes the wire form a property of the class
 * a test can assert without a context, which is the same reasoning the shared kernel records for its
 * two problem writers at {@code CorrelationIdFilter} and {@code ApiErrorSecurityHandlers}: both build
 * their own rather than injecting one.</p>

 * <p>Assumptions: a default-configured mapper suffices, and that is a property of what
 * {@link BatchErrorEvent} contains rather than an omission. Its six components are three strings, two
 * enumerations and a record of four strings -- there is no date, no money and no polymorphic type in
 * it, so none of the settings a project normally has to fix applies. The one project-wide
 * serialisation rule that does exist, that money travels as a JSON string, cannot be reached from here
 * because no component of this event is monetary.</p>
 *
 * <h2>Data minimisation</h2>
 *
 * <p>Assumptions: nothing this class sends or logs can carry a primary account number, a card
 * verification value, a national identifier or a government-issued identifier. The body is the
 * serialised form of {@link BatchErrorEvent}, whose canonical constructor refuses identifier-shaped
 * and credential-shaped text outright; the three attributes are a correlation identity, a generated
 * message identity and a fixed media type; and the log lines below carry the run, the step and a
 * message-free {@link ThrowableDigest}. The sink ADDRESS is never logged either: it is deployment
 * configuration an operator can read from the parameter it came from, whereas a log stream is copied
 * into stores that inherit none of that parameter's controls.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}. The four rationale labels
 * are written in the plural unparenthesised form with the colon retained and no emphasis markup, and
 * this file is restricted to ASCII, as the charter at {@code package-info.java} requires of every
 * class in this package.</p>
 *
 * <p>Baseline lineage: every citation above is provenance. Nothing under {@code app/} is read at run
 * time and nothing under it is altered by this migration. Columns 73 to 80 of a reference line carry a
 * sequence field that is not part of the statement.</p>
 */
public final class SqsBatchFailureReporter implements BatchFailureReporter {

    /** The operational log this class reports its own publication outcomes to. */
    private static final Logger LOG = LoggerFactory.getLogger(SqsBatchFailureReporter.class);

    /** The queue client the send is issued on, whose call budget {@link SqsConfig} bounds. */
    private final SqsClient sqsClient;

    /** The validated binding every send is shaped by, including the closed attribute set. */
    private final ErrorSinkBinding binding;

    /**
     * The mapper that renders the event as the declared media type, owned by this class.
     *
     * <p>Assumptions: it is static and shared because a mapper is thread-safe once built and this one
     * is never reconfigured, so one instance per class rather than per bean is the cheaper of two
     * identical behaviours.</p>
     */
    private static final ObjectMapper EVENT_WRITER = JsonMapper.builder().build();

    /**
     * Builds the reporter over its two collaborators.
     *
     * @param sqsClient the queue client the send is issued on; must not be {@code null}
     * @param binding the validated sink binding every send is shaped by; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    SqsBatchFailureReporter(SqsClient sqsClient, ErrorSinkBinding binding) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient must not be null");
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
    }

    /**
     * Serialises the event, shapes the send and issues it, absorbing every failure of its own.
     *
     * <p>Assumptions: EVERY throwable raised between here and the transport is caught, including an
     * {@link Error}, and none is rethrown. This method is called from a catch block that is about to
     * rethrow the failure the step actually suffered, so anything escaping here would replace a
     * diagnosed step failure with an undiagnosed failure about the reporting of it. A queue permission
     * the task role was not granted, a serialiser configuration that cannot render the event and an
     * unreachable endpoint all present the same way to an operator otherwise: the night fails with a
     * stack trace naming the messaging library, and the real cause is three frames further down and in
     * a different subsystem.</p>
     *
     * <p>Trade-offs: catching {@link Throwable} rather than {@link Exception} is normally the wrong
     * default and is right here. The alternative leaves an {@link Error} -- most plausibly a linkage
     * error from a client whose transitive dependency is absent at run time -- free to propagate out
     * of a reporting call and terminate the run, which is a strictly worse outcome than an unreported
     * failure. The cost is that a genuinely fatal condition first surfaced by this call is logged
     * rather than raised; it is paid because the caller re-raises a failure immediately afterwards, so
     * the run still fails and the digest below still names the type.</p>
     *
     * @param event the failure to report; must not be {@code null}
     * @return {@code true} when the transport accepted the send, {@code false} when the event was not
     *     published for any reason
     * @throws NullPointerException if {@code event} is {@code null}, which is a programming error in
     *     the caller rather than a reporting failure and is therefore the one condition this method
     *     does not absorb
     */
    @Override
    public boolean report(BatchErrorEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        // WHY : Assumptions: the message identity is GENERATED here rather than taken from the event,
        //       because the event carries no identity of its own and the transport's own identifier is
        //       assigned after the send. The attribute exists so a consumer can recognise a redelivery
        //       of one report as the same report; a standard queue may deliver a message more than
        //       once, so without it two deliveries of one failure are indistinguishable from two
        //       failures.
        String messageId = UUID.randomUUID().toString();
        String correlationId = publishableCorrelation(event, messageId);

        try {
            // WHY : Assumptions: serialisation happens BEFORE the send is shaped, so a serialiser
            //       failure is reported as a serialisation failure rather than as a rejected send. The
            //       two have different repairs -- one is a mapper configuration and the other a queue
            //       permission -- and a single catch around both would name neither.
            String body = EVENT_WRITER.writeValueAsString(event);
            SendMessageRequest request =
                    this.binding.publicationOf(body, correlationId, messageId);
            SendMessageResponse response = this.sqsClient.sendMessage(request);

            // WHY : Assumptions: the transport's own identifier is logged and the generated one is
            //       not repeated, because the pair is what lets an operator find this exact message in
            //       the queue while the correlation identity already joins the line to the run. Neither
            //       is a value a person owns, so neither is minimised.
            LOG.info("event=batch.error.published runId={} step={} job={} returnCode={}"
                            + " transportMessageId={}",
                    event.runId(), event.stepName(), event.jobName().token(),
                    event.returnCode().numericValue(), response.messageId());
            return true;
        } catch (Throwable failure) {
            // WHY : Refactoring Rationale: the throwable is NOT handed to the facade as a trailing
            //       argument. A trailing throwable renders its message and every cause's message, and
            //       those messages are composed by the failing library -- a transport quotes the
            //       endpoint and the request it rejected, a serialiser quotes the value it could not
            //       write -- so their content is unbounded by construction. ThrowableDigest keeps the
            //       type chain and the frames, which are facts about code and can hold no request
            //       value, and drops the messages, which are the whole disclosure channel. This is the
            //       same rule the ledger and the entry point apply at their own failure boundaries.
            LOG.error("event=batch.error.publish-failed runId={} step={} job={} failureDigest={}",
                    event.runId(), event.stepName(), event.jobName().token(),
                    ThrowableDigest.of(failure));
            return false;
        }
    }

    /**
     * Resolves a correlation identity the transport will carry, substituting the message identity.
     *
     * <p>Assumptions: the event's correlation identity is the orchestrator's execution name, and that
     * value is admitted by {@link BatchErrorEvent} on a weaker rule than the transport's. The event
     * requires it to be non-blank; a message attribute additionally has to be at most
     * {@link MessagingCorrelationId#MAX_LENGTH} characters and composed only of the printable range
     * the transport accepts. An execution name may legitimately exceed that width, and the entry point
     * replaces a control character inside one with a SPACE, which the messaging rule excludes -- so the
     * two rules genuinely disagree on values that occur, and the disagreement has to be resolved
     * somewhere.</p>
     *
     * <p>Alternatives Considered: rewriting the identity into the admitted alphabet and truncating it
     * to the admitted width, which preserves a recognisable prefix. Rejected because a rewritten
     * identity no longer equals the value the run's log lines were written under, so it would join to
     * nothing -- and joining is the single thing this attribute is for. A value that looks joinable and
     * is not is worse than one that does not pretend to be.</p>
     *
     * <p>Alternatives Considered: refusing to publish at all when the identity is unusable, which is
     * the strictest reading. Rejected because the report's content -- which run, which step, which job,
     * which tier -- is inside the body and is unaffected, so withholding the whole report to protect
     * one attribute discards the diagnostics to preserve a join that was already unavailable.</p>
     *
     * <p>Trade-offs: the substitution is therefore the generated message identity, which is canonical
     * by construction and correlates with the log line this method writes and with nothing else. The
     * warn line is what makes the substitution auditable: it names the run and the step, so an
     * operator who cannot find the report by execution name can find it by those.</p>
     *
     * @param event the failure being reported, read for its correlation identity and for the run and
     *     step named in the substitution warning; must not be {@code null}
     * @param messageId the generated message identity, used as the substitute; must not be
     *     {@code null} and is canonical by construction
     * @return the event's own correlation identity when the shared messaging rule admits it, and
     *     {@code messageId} otherwise; never {@code null}
     */
    private static String publishableCorrelation(BatchErrorEvent event, String messageId) {
        String supplied = event.correlationId();
        if (MessagingCorrelationId.isCanonical(supplied)) {
            return supplied;
        }

        LOG.warn("event=batch.error.correlation-substituted runId={} step={} suppliedLength={}"
                        + " maxLength={}",
                event.runId(), event.stepName(), supplied.length(),
                MessagingCorrelationId.MAX_LENGTH);
        return messageId;
    }
}
