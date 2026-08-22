package com.carddemo.batch.config;

import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.service.BatchErrorPublisher;
import com.carddemo.batch.service.BatchFailureReporter;
import com.carddemo.common.observability.ThrowableDigest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a step failure to the terminal error sink through the module's one sender.
 *
 * <p>Purpose: this is the adapter half of {@link BatchFailureReporter}. It hands the
 * {@link BatchErrorEvent} the durable step ledger built to {@link BatchErrorPublisher}, which renders
 * it, shapes the send through {@link SqsConfig.ErrorSinkBinding#publicationOf} and puts it on the queue
 * the migration plan's section 0.4.1.8 provisions as the replacement for the reference system's
 * {@code CARD.DEMO.ERROR} -- the single sink both reference inquiry programs write to, at
 * {@code MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME} in
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl:294} and {@code app/app-vsam-mq/cbl/CODATE01.cbl:243}.</p>
 *
 * <h2>Why this class issues no send of its own</h2>
 *
 * <p>Refactoring Rationale: it used to. This class held a queue client, a mapper and the binding, and
 * so did {@link BatchErrorPublisher} -- two senders addressing the one queue on two occasions, the
 * ledger's step report and the entry point's run notification. A single hard failure therefore put TWO
 * messages on the sink, one carrying a redacted {@code AbendDetail} and one carrying none, against a
 * documented contract of one notification per failed run. The correction keeps the port, because the
 * ledger must be able to report without knowing a transport exists, and removes the second sender: this
 * class now delegates, and the publisher holds the per-run claim that makes one failure produce one
 * message.</p>
 *
 * <p>Alternatives Considered: keeping both senders and putting the per-run claim in a third collaborator
 * that both consulted. Rejected because two senders would still be two places a send is shaped, two
 * mappers deciding one wire form and two log contracts describing one queue -- so the claim would have
 * made the message count right while leaving every other property of a published failure dependent on
 * which occasion published it. Alternatives Considered: deleting this class and pointing the ledger
 * straight at the publisher. Rejected because the ledger sits in the service package and holds the port
 * as an {@link java.util.Optional}, so that change would put a queue client's collaborator in its
 * constructor and cost its unit test the recording stub it exercises the report path with.</p>
 *
 * <p>Assumptions: the mapper the body is rendered with is now the context's own rather than one owned
 * here, and for this payload the two are indistinguishable. {@link BatchErrorEvent} carries three
 * strings, two enumerations and a record of four strings -- no date, no money, no polymorphic type --
 * so none of the settings the shared kernel's modules contribute can reach it. Refactoring Rationale:
 * the reason to prefer the context's mapper anyway is that one wire form for one queue should be decided
 * in one place; a mapper owned here was a second place, and it was invisible precisely because this
 * payload cannot yet tell them apart.</p>
 *
 * <p>Assumptions: this class stays in the configuration package rather than moving beside the port it
 * satisfies. Its one collaborator is a bean this package declares, and the inverse placement would put
 * the two packages in a cycle, because this package already depends on the service package for the
 * ledger the reporter serves.</p>
 *
 * <h2>Data minimisation</h2>
 *
 * <p>Assumptions: nothing this class logs can carry a primary account number, a card verification value,
 * a national identifier or a government-issued identifier. It writes one line, on the path where a
 * throwable escaped the sender, and that line carries the run, the step, the job token and a
 * message-free {@link ThrowableDigest}. The sink ADDRESS is never logged either: it is deployment
 * configuration an operator can read from the parameter it came from, whereas a log stream is copied
 * into stores that inherit none of that parameter's controls.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}. The four rationale labels
 * are written in the plural unparenthesised form with the colon retained and no emphasis markup, and
 * this file is restricted to ASCII, as the charter at {@code package-info.java} requires of every class
 * in this package.</p>
 *
 * <p>Baseline lineage: every citation above is provenance. Nothing under {@code app/} is read at run
 * time and nothing under it is altered by this migration. Columns 73 to 80 of a reference line carry a
 * sequence field that is not part of the statement.</p>
 */
public final class SqsBatchFailureReporter implements BatchFailureReporter {

    /** The operational log this class reports an escaped throwable to. */
    private static final Logger LOG = LoggerFactory.getLogger(SqsBatchFailureReporter.class);

    /** The module's one sender, which renders the event, shapes the send and issues it. */
    private final BatchErrorPublisher publisher;

    /**
     * Builds the adapter over the module's one sender.
     *
     * @param publisher the sender every publication in this module is issued through; must not be
     *     {@code null}
     * @throws NullPointerException if {@code publisher} is {@code null}
     */
    SqsBatchFailureReporter(BatchErrorPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    /**
     * Hands the event to the sender, absorbing anything that escapes it.
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
     * <p>Assumptions: the sender already swallows every {@link RuntimeException} it can raise and
     * reports it by return value, so what remains for this catch is narrow -- most plausibly a linkage
     * error from a client whose transitive dependency is absent at run time. The catch is kept anyway
     * because its purpose is the guarantee, not the enumeration: this method's contract to the ledger is
     * that reporting cannot fail the step, and a contract that holds only for the exceptions someone
     * remembered is not the same contract.</p>
     *
     * <p>Trade-offs: catching {@link Throwable} rather than {@link Exception} is normally the wrong
     * default and is right here. The alternative leaves an {@link Error} free to propagate out of a
     * reporting call and terminate the run, which is a strictly worse outcome than an unreported
     * failure. The cost is that a genuinely fatal condition first surfaced by this call is logged rather
     * than raised; it is paid because the caller re-raises a failure immediately afterwards, so the run
     * still fails and the digest below still names the type.</p>
     *
     * @param event the failure to report; must not be {@code null}
     * @return {@code true} when this run's one notification is on the sink, {@code false} when the event
     *     was not published for any reason
     * @throws NullPointerException if {@code event} is {@code null}, which is a programming error in
     *     the caller rather than a reporting failure and is therefore the one condition this method
     *     does not absorb
     */
    @Override
    public boolean report(BatchErrorEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        try {
            return this.publisher.publish(event);
        } catch (Throwable failure) {
            // WHY : Refactoring Rationale: the throwable is NOT handed to the facade as a trailing
            //       argument. A trailing throwable renders its message and every cause's message, and
            //       those messages are composed by the failing library -- a transport quotes the
            //       endpoint and the request it rejected, a serialiser quotes the value it could not
            //       write -- so their content is unbounded by construction. ThrowableDigest keeps the
            //       type chain and the frames, which are facts about code and can hold no request
            //       value, and drops the messages, which are the whole disclosure channel. This is the
            //       same rule the ledger and the entry point apply at their own failure boundaries.
            // WHY : Assumptions: the event name is distinct from BOTH neighbouring records, and
            //       deliberately: the sender's publish-failed line means a send was attempted and
            //       rejected, the ledger's report-refused line means this method raised at its own call
            //       site, and this line means something escaped the sender. Three different repairs --
            //       a queue permission, a caller fault, an absent dependency -- so three names.
            LOG.error("event=batch.error.report-failed runId={} step={} job={} failureDigest={}",
                    event.runId(), event.stepName(), event.jobName().token(),
                    ThrowableDigest.of(failure));
            return false;
        }
    }
}
