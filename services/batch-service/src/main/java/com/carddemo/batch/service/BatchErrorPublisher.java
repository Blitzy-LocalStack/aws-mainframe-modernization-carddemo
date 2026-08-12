package com.carddemo.batch.service;

import com.carddemo.batch.config.SqsConfig;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.common.observability.ThrowableDigest;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends one failure notification for a batch run to the deployment's terminal error sink.
 *
 * <p><b>Purpose.</b> This is the producer the migrated error sink was missing. The configuration that
 * addresses the sink, the validated binding that shapes one send and the payload record were all
 * authored, and nothing called any of them: the queue existed, the binding refused a misconfigured
 * address at startup, and no message was ever put on the wire. This class closes that gap. It takes an
 * already-minimised {@link BatchErrorEvent}, renders it through the shared kernel's mapper, hands the
 * rendered body to {@link SqsConfig.ErrorSinkBinding#publicationOf} and sends the request the binding
 * builds.</p>
 *
 * <p>Assumptions: this class shapes no send request of its own and names no message attribute. The
 * binding is documented as the only place in this module where a send is shaped, precisely so that the
 * two ordered-queue identifiers it must NOT set are checkable in one assertion instead of at every call
 * site -- so building a request here would defeat the property that decision exists to buy. The only
 * thing this class decides is WHEN to publish and what to do when publishing fails.</p>
 *
 * <h2>One notification per failed run, not per failed step</h2>
 *
 * <p>Refactoring Rationale: the notification is published by the process that RAN the job rather than
 * from inside a step. A step failure already propagates and is already recorded twice -- once in the
 * durable step ledger's row for that run and step, and once in the step's own structured log record --
 * so a per-step publish would put several messages on the sink for one failed run and leave a reader
 * deduplicating them. The run is the unit an operator acts on and the unit the orchestrator redrives,
 * so one message per failed run is the shape that matches both.</p>
 *
 * <p>Alternatives Considered: publishing from the durable step ledger's own failure path, which is the
 * single funnel every step passes through and the one place a real step name is always in hand.
 * Rejected because the ledger does not know which JOB it is recording -- it is keyed by the run and
 * step pair alone -- and {@link BatchErrorEvent} requires the job. Supplying it would mean threading a
 * job name through the ledger's public signature and through every one of its call sites, so a
 * diagnostic concern would have widened the signature of the module's restart mechanism. The entry
 * point already holds the job name, the run identifier, the correlation identity and the graded return
 * code together, so publishing there needs no new parameter anywhere.</p>
 *
 * <h2>What this class cannot put on the sink</h2>
 *
 * <p>Assumptions: nothing here can place a record image, a primary account number, an account
 * identifier or a customer identifier on the queue, and that property is held by the PAYLOAD rather
 * than by this class. {@link BatchErrorEvent} accepts only a run identifier, a step name, a job name, a
 * return code, a correlation identifier and the four abend components, and its own canonical
 * constructor masks identifier-shaped digit runs and replaces any component naming a credential. There
 * is no field on this class through which a caller could route a value past that check, which is why
 * this class re-checks none of it -- a second check would be a second place the rule could be relaxed.
 * </p>
 *
 * <p>Assumptions: the two configured source identifiers the binding carries,
 * {@link SqsConfig.ErrorSinkBinding#sourceApplication} and
 * {@link SqsConfig.ErrorSinkBinding#sourceProgram}, are written onto this class's own publication log
 * record rather than onto the message. That is where the migration's observability contract maps them:
 * {@code docs/architecture/observability.md} maps {@code ERR-APPLICATION} at
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy:22} to the {@code service} common tag and
 * the {@code service} log field, and {@code ERR-PROGRAM} at that copybook's line 23 to the logger name.
 * Alternatives Considered: carrying them in the body instead. Rejected because the body's wire form is
 * decided once, by the shared kernel's mapper binding a record through its canonical constructor, and a
 * body composed here from a map plus a record would be a second place that form is decided -- which is
 * the outcome the binding's own documentation refuses. Adding them as message attributes was rejected
 * for the same class of reason: the attribute set is closed at three by the binding so that one
 * assertion can cover it, and a fourth attribute added here would reopen it.</p>
 *
 * <h2>A failure to notify must never replace the failure being notified</h2>
 *
 * <p>Assumptions: every fault this class can raise while publishing is swallowed and logged. The caller
 * is already on a failure path and its exit status is the only channel the orchestrator reads, so a
 * publish that threw would replace a graded, reported failure with an unreported one -- and it would do
 * so exactly when the platform is least healthy, which is when an unreachable queue is most likely. The
 * swallow is narrow: it covers one send, it logs at error level, and it returns a boolean so a caller
 * or a test can tell a delivered notification from a suppressed one.</p>
 *
 * <p>Trade-offs: because the fault is swallowed, a permanently unreachable sink is visible only in this
 * service's own log stream and never on the sink. That is accepted for the reason above, and it is why
 * the suppression record names the property the address came from rather than the address itself: an
 * operator alarming on the log stream still sees it, and the address stays out of a stream that is
 * copied more widely than the parameter store it was read from.</p>
 *
 * <p>Baseline lineage: this producer has no reference counterpart -- a reference batch program signals
 * failure by terminating abnormally and writing one line to the job log, at
 * {@code app/cbl/CBTRN02C.cbl:707-711} and the matching paragraph of each sibling program -- so this
 * class is an ADDITION and the divergence is registered as
 * <b>{@code D-BATCH-FAILURE-NOTIFICATION}</b> in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Baseline lineage: every
 * citation above is provenance. Nothing under {@code app/**} is read at run time and nothing under it
 * is altered by this migration; the reference implementation is the behavioural oracle and stays
 * byte-identical. Columns 73 to 80 of a reference line carry a sequence field that is not part of the
 * statement.</p>
 */
public class BatchErrorPublisher {

    /** The logger for delivered and suppressed notifications, named for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchErrorPublisher.class);

    /** The queue client one notification is sent with. */
    private final SqsClient sqs;

    /** The validated destination, media type and the two configured source identifiers. */
    private final SqsConfig.ErrorSinkBinding binding;

    /** Renders the payload record as the media type the binding declares. */
    private final ObjectMapper json;

    /**
     * Retains the three collaborators one send needs.
     *
     * <p>Assumptions: the binding arrives as a validated record rather than as four strings, and
     * nothing here re-checks any of its components. It has already refused a blank address, an ordered
     * destination and an over-wide source identifier at startup, and a second check would be a second
     * place those rules could diverge from the first.</p>
     *
     * @param sqs the queue client; must not be {@code null}
     * @param binding the validated binding to the terminal error sink; must not be {@code null}
     * @param json the mapper the payload record is rendered with, which is the context's own so that
     *     this body is framed exactly as every other body in this migration; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public BatchErrorPublisher(SqsClient sqs, SqsConfig.ErrorSinkBinding binding, ObjectMapper json) {
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    /**
     * Publishes one notification, reporting whether it reached the sink.
     *
     * <p>Assumptions: the return value is a boolean rather than void, and it exists so that a caller
     * can act on a suppressed notification and a test can assert one. A void method whose faults are
     * all swallowed is indistinguishable from one that did nothing at all, which is precisely the shape
     * this class was written to stop being.</p>
     *
     * <p>Assumptions: the publisher's own message identity is minted here, per send, and is not the
     * identifier the transport assigns. The binding's attribute contract names it as the publisher's
     * identity for the event; a value derived from the run and step instead would collide with itself
     * if one run and step ever published twice, and a value the transport assigns is not knowable to a
     * producer before the send it would have to accompany.</p>
     *
     * <p>Trade-offs: a run identifier carrying a character the transport will not accept as an
     * attribute -- which the entry point's neutralisation of an operator-supplied override can produce,
     * because it substitutes a space for a control character and a space is outside the admitted set --
     * is refused by the binding and therefore surfaces here as a suppressed notification rather than as
     * a rejected send. That is the correct outcome on this path and it is the reason the refusal is
     * inside the swallow rather than ahead of it.</p>
     *
     * @param event the failure to notify, already minimised and redacted by its own canonical
     *     constructor; must not be {@code null}
     * @return {@code true} when the sink accepted the message, {@code false} when the attempt failed
     *     and the fault was logged in place of being propagated
     * @throws NullPointerException if {@code event} is {@code null}, which is a programming error in
     *     the caller rather than a transport fault and is therefore NOT swallowed
     */
    public boolean publish(BatchErrorEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        try {
            String body = this.json.writeValueAsString(event);
            SendMessageRequest request = this.binding.publicationOf(body, event.correlationId(),
                    UUID.randomUUID().toString());
            this.sqs.sendMessage(request);
            LOG.info("event=batch.error.published application={} program={} runId={} step={} job={}"
                            + " returnCode={}",
                    this.binding.sourceApplication(), this.binding.sourceProgram(), event.runId(),
                    event.stepName(), event.jobName().token(), event.returnCode().numericValue());
            return true;
        } catch (RuntimeException failure) {
            // WHY : Assumptions: this is the swallow the class comment justifies, and it is written as
            //       ONE catch of RuntimeException rather than as several narrower ones deliberately. The
            //       faults reachable here are a rejected send, an unreachable endpoint, a refused
            //       credential, a refused correlation identity and a serialisation fault -- and the
            //       correct response to every one of them is identical: report it here and let the
            //       caller's own graded failure stand. Enumerating them would imply a distinction this
            //       method draws and does not.
            // WHY : Assumptions: the fault is rendered as its chain of TYPES rather than its message. A
            //       transport fault composes its message from endpoint and credential material and a
            //       serialisation fault quotes the value it could not write, and this record goes to the
            //       same log stream as every other line this run emits.
            LOG.error("event=batch.error.publish-failed gate={} runId={} step={} failure={}",
                    SqsConfig.PROPERTY_ERROR_QUEUE_URL, event.runId(), event.stepName(),
                    ThrowableDigest.of(failure));
            return false;
        }
    }
}
