package com.carddemo.batch.service;

import com.carddemo.batch.config.SqsConfig;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.observability.ThrowableDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * <h2>One notification per failed run, published on either of two occasions</h2>
 *
 * <p>Refactoring Rationale: two call sites reach this sink and they used to reach it through two
 * separate senders. {@code BatchStepLedger} reports a failed STEP through the
 * {@code BatchFailureReporter} port, and {@code com.carddemo.batch.BatchApplication} announces the
 * failed RUN once the job has returned. Each issued its own client call with its own mapper and its own
 * log record against the one address {@link SqsConfig#PROPERTY_ERROR_QUEUE_URL} carries, so a single
 * hard failure put TWO messages on the sink -- one carrying a redacted {@code AbendDetail} and one
 * carrying none -- and left a reader deduplicating them against the documented contract of one message
 * per failed run. The port's adapter now delegates here, making this class the module's one sender, and
 * the claim below makes the publication idempotent per run: the first attempt that reaches the sink
 * wins and every later attempt for that run is suppressed rather than sent.</p>
 *
 * <p>Assumptions: the surviving message is the RICHER of the two, and that follows from the order the
 * two occasions occur in rather than from any comparison made here. The ledger reports from inside the
 * step, before the failure it recorded is re-raised; the entry point publishes after the job has
 * returned. The diagnostic-carrying report is therefore always first, and a queue cannot be asked to
 * withdraw a message it has already accepted. Alternatives Considered: comparing the two payloads and
 * preferring the fuller one. Rejected because it would mean holding the first message back until the
 * second either arrived or did not, which is a buffer with a timeout on the failure path -- and the
 * failure path is exactly where a buffer is least likely to be drained.</p>
 *
 * <p>Assumptions: a failure that names no step still produces its one message. A context that never
 * refreshed and a job the framework refuses both fail before any step runs, so no step is ever
 * recorded, the ledger never reports, and the run-level occasion is the only one there is -- it takes
 * the claim itself and publishes the step token its caller substitutes for a step that never ran.</p>
 *
 * <p>Trade-offs: the claim is released when a send does not reach the sink, so a failed first attempt
 * costs no coverage -- the second occasion publishes the leaner payload rather than nothing at all. The
 * cost is that a sink which accepts a message and then loses it is indistinguishable here from one that
 * kept it, which is the assumption every at-least-once producer makes and is why the delivered record
 * below is written at informational level rather than being inferred from the absence of an error.</p>
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
     * The run whose notification is on the sink, or {@code null} while no run's is.
     *
     * <p>Assumptions: ONE slot rather than a table of every run seen, because this process publishes
     * for exactly one run and exits. The entry point selects a single unit of work from its
     * {@code --job=} argument and returns an exit status, so the two occasions that can publish -- the
     * step report and the run notification -- always name that same run and always occur one after the
     * other. A slot therefore holds the whole property the sink needs, at constant memory, and retains
     * one identifier for the life of a process rather than accumulating them.</p>
     *
     * <p>Alternatives Considered: a concurrent set of notified run identifiers, which would also make
     * a process that alternated between two runs idempotent for both. Rejected because no such process
     * exists in this module and the set would grow for the life of whatever process did exist, so it
     * would trade an unbounded structure for a case the entry point cannot produce.</p>
     *
     * <p>Trade-offs: consequently a caller that published for run A, then for run B, then for A again
     * would send twice for A. That sequence is unreachable from the entry point for the reason above,
     * and it is stated here so a future caller that could produce it changes this field deliberately
     * rather than discovering the behaviour.</p>
     */
    private final AtomicReference<String> notifiedRun = new AtomicReference<>();

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
     * <p>Assumptions: the claim is taken BEFORE the send and released only when the send did not reach
     * the sink, rather than being taken after a delivered send. Taken afterwards, two callers publishing
     * for one run concurrently would both find the run unclaimed and both send, which is the outcome
     * this method exists to prevent; taken beforehand and never released, a first attempt that failed
     * would silence the second and the failed run would reach the sink not at all.</p>
     *
     * <p>Trade-offs: a run identifier carrying a character the transport will not accept as an attribute
     * -- which the entry point's neutralisation of an operator-supplied override can produce, because it
     * substitutes a space for a control character and a space is outside the admitted set -- has the
     * generated message identity substituted for it rather than being refused. Refactoring Rationale:
     * that substitution used to live in the port's adapter, so the two senders disagreed about the same
     * value: a step failure published with a substituted attribute and a run-level failure was suppressed
     * outright. Collapsing the senders forces one policy, and the publishing one is correct -- the
     * report's content is inside the body and is unaffected, so withholding the whole report to protect
     * one attribute would discard the diagnostics of a failure that has no other message coming.</p>
     *
     * @param event the failure to notify, already minimised and redacted by its own canonical
     *     constructor; must not be {@code null}
     * @return {@code true} when this run's one notification is on the sink -- either because this call
     *     delivered it or because an earlier call for the same run already did -- and {@code false} when
     *     the attempt failed and the fault was logged in place of being propagated
     * @throws NullPointerException if {@code event} is {@code null}, which is a programming error in
     *     the caller rather than a transport fault and is therefore NOT swallowed
     */
    public boolean publish(BatchErrorEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        if (!claimRun(event.runId())) {
            // WHY : Assumptions: the suppression is recorded at informational level and not silently,
            //       because the property an operator has to be able to check is that ONE message on the
            //       sink means one failed run rather than one lost sender. A silent return leaves a
            //       reader unable to tell a deduplicated second occasion from a sender that never ran.
            LOG.info("event=batch.error.publish-suppressed reason=run-already-notified runId={}"
                            + " step={} job={} returnCode={}",
                    event.runId(), event.stepName(), event.jobName().token(),
                    event.returnCode().numericValue());
            return true;
        }

        String messageId = UUID.randomUUID().toString();
        try {
            String body = this.json.writeValueAsString(event);
            SendMessageRequest request = this.binding.publicationOf(body,
                    publishableCorrelation(event, messageId), messageId);
            this.sqs.sendMessage(request);
            LOG.info("event=batch.error.published application={} program={} runId={} step={} job={}"
                            + " returnCode={} messageId={}",
                    this.binding.sourceApplication(), this.binding.sourceProgram(), event.runId(),
                    event.stepName(), event.jobName().token(), event.returnCode().numericValue(),
                    messageId);
            return true;
        } catch (RuntimeException failure) {
            // WHY : Assumptions: the claim is released here and nowhere else. The run has NOT been
            //       notified, so leaving the claim taken would turn one undelivered send into a run
            //       that reached the sink not at all -- and the second occasion carries the same run,
            //       the same job and the same graded tier, so it is a usable substitute.
            releaseRun(event.runId());
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

    /**
     * Claims the right to put this run's one notification on the sink.
     *
     * <p>Assumptions: the claim is one atomic exchange rather than a read followed by a write, so two
     * callers publishing for one run cannot both observe it unclaimed. The exchange returns the value
     * the slot held: equal to this run means the run is already claimed and this caller must not send,
     * anything else -- including the empty slot -- means this caller now holds the claim.</p>
     *
     * <p>Trade-offs: an unconditional exchange is used in preference to a compare-and-set loop against
     * the empty slot, because the slot legitimately holds a DIFFERENT run's identifier once that run has
     * been notified, and a loop that only ever claimed an empty slot would suppress the first
     * notification of every run after the first one.</p>
     *
     * @param runId the run whose notification is being claimed, non-blank by the payload record's own
     *     construction; must not be {@code null}
     * @return {@code true} when this call now holds the claim and must send, {@code false} when the run
     *     was already claimed and this call must suppress
     */
    private boolean claimRun(String runId) {
        return !runId.equals(this.notifiedRun.getAndSet(runId));
    }

    /**
     * Releases a claim whose send did not reach the sink, so a later occasion can publish.
     *
     * <p>Assumptions: the release is conditional on the slot still naming this run. A different run
     * claimed in the meantime is a claim this call did not take and must not clear, and clearing it
     * would let that run publish twice -- which is the defect the claim exists to remove.</p>
     *
     * @param runId the run whose failed attempt is being released; must not be {@code null}
     */
    private void releaseRun(String runId) {
        this.notifiedRun.compareAndSet(runId, null);
    }

    /**
     * Resolves a correlation identity the transport will carry, substituting the message identity.
     *
     * <p>Assumptions: the event's correlation identity is the orchestrator's execution name, and that
     * value is admitted by {@link BatchErrorEvent} on a weaker rule than the transport's. The event
     * requires it to be non-blank; a message attribute additionally has to be at most
     * {@link MessagingCorrelationId#MAX_LENGTH} characters and composed only of the printable range the
     * transport accepts. An execution name may legitimately exceed that width, and the entry point
     * replaces a control character inside one with a SPACE, which the messaging rule excludes -- so the
     * two rules genuinely disagree on values that occur, and the disagreement has to be resolved
     * somewhere.</p>
     *
     * <p>Alternatives Considered: rewriting the identity into the admitted alphabet and truncating it to
     * the admitted width, which preserves a recognisable prefix. Rejected because a rewritten identity
     * no longer equals the value the run's log lines were written under, so it would join to nothing --
     * and joining is the single thing this attribute is for. A value that looks joinable and is not is
     * worse than one that does not pretend to be.</p>
     *
     * <p>Alternatives Considered: refusing to publish at all when the identity is unusable, which is
     * the strictest reading and was this class's own behaviour while the substitution lived only in the
     * port's adapter. Rejected because the report's content -- which run, which step, which job, which
     * tier -- is inside the body and is unaffected, so withholding the whole report to protect one
     * attribute discards the diagnostics to preserve a join that was already unavailable.</p>
     *
     * <p>Trade-offs: the substitution is therefore the generated message identity, which is canonical by
     * construction and correlates with the publication record this class writes and with nothing else.
     * The warn line is what makes the substitution auditable: it names the run and the step, so an
     * operator who cannot find the notification by execution name can find it by those.</p>
     *
     * @param event the failure being published, read for its correlation identity and for the run and
     *     step named in the substitution warning; must not be {@code null}
     * @param messageId the generated message identity, used as the substitute; must not be {@code null}
     *     and is canonical by construction
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
