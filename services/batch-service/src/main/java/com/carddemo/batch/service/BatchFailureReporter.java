package com.carddemo.batch.service;

import com.carddemo.batch.dto.BatchErrorEvent;

/**
 * The port a step failure is reported through, so that the ledger names a destination and not a
 * transport.
 *
 * <p>Purpose: the durable step ledger is the one place in this module that observes every step
 * failure, and the migration plan's section 0.4.1.8 provisions a terminal error sink -- the standard
 * queue replacing the reference system's {@code CARD.DEMO.ERROR} -- for exactly such an outcome to be
 * reported to. This interface is the seam between those two facts. It is declared HERE, beside its
 * only caller, rather than beside its only implementation, which is what keeps the dependency arrow
 * pointing from the transport at the rule and never the other way.</p>
 *
 * <p>Alternatives Considered: having {@link BatchStepLedger} depend on the queue adapter directly.
 * Rejected on two independent grounds. Structurally it inverts the arrow: the adapter is declared in
 * {@code com.carddemo.batch.config}, which already depends on this package for the ledger itself, so
 * a direct dependency would put the two packages in a cycle -- and a cycle between a configuration
 * package and the package it configures is the kind that only ever grows. Practically it would make
 * the ledger untestable without a queue client, a serialiser and a validated binding, when the
 * behaviour under test is which outcomes are reported and which are not. The same shape is used
 * elsewhere in this repository for the same reason: the account context declares a
 * protected-identifier port beside its mapper and satisfies it from its own configuration
 * package.</p>
 *
 * <p>Alternatives Considered: expressing the port as {@link java.util.function.Consumer} of the event
 * rather than as a named interface. Rejected because a named type carries this contract's two
 * non-obvious clauses -- that an implementation must not throw, and that it reports rather than
 * decides -- where a generic functional type carries only an argument and a return. A reader holding
 * a consumer has nowhere to learn either clause.</p>
 *
 * <p>Assumptions: an implementation is OPTIONAL at run time. The whole queue configuration is gated
 * on the sink's address being published, so a deployment that provisions no sink registers no
 * implementation and the ledger holds an empty optional. That is a supported state and not a
 * degraded one: the authoritative record of a batch outcome is the ledger row and the run's log
 * stream, and the orchestrator reports a failed state from its own catch route, so an unpublished
 * sink costs a duplicate of something two other channels already carry.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Rationale labels are
 * written in the plural unparenthesised form with the colon retained, and this file is restricted to
 * ASCII, as the charter at {@code package-info.java} requires of every type in this package.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
public interface BatchFailureReporter {

    /**
     * Reports one terminal step failure to the sink, without ever raising.
     *
     * <p>Assumptions: an implementation MUST NOT throw, and the obligation is on the implementation
     * rather than on the caller wrapping the call. The only call site is a catch block that is about
     * to rethrow the failure the step actually suffered, so an exception escaping here would replace
     * a diagnosed failure with an undiagnosed one about the reporting of it -- the failure an
     * operator least wants and the one hardest to trace back to a queue permission. Putting the
     * obligation in the contract means every future implementation inherits it, where a caller-side
     * catch would protect only the one call site that wrote it.</p>
     *
     * <p>Trade-offs: the method therefore reports whether the sink accepted the event by its return
     * value rather than by an exception, which a caller may ignore. That is accepted because the
     * caller has no recovery available -- the step has already failed and its ledger row is already
     * committed -- so the value exists for a test to assert on and for a log line to state, not for
     * control flow.</p>
     *
     * @param event the failure to report, already reduced to the closed set of components
     *     {@link BatchErrorEvent} admits and already carrying only the failure tier; must not be
     *     {@code null}
     * @return {@code true} when the sink accepted the event, {@code false} when it was not published
     *     for any reason, including a rejected send, a serialisation failure or an unusable
     *     correlation identity
     */
    boolean report(BatchErrorEvent event);
}
