package com.carddemo.reference.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The request body of the reference-data maintenance batch, satisfying the contract schema of the same
 * name.
 *
 * <p>Purpose: the inbound body of the batch operation, carrying the ordered actions to apply. It is
 * the migrated form of the baseline's reference-update input file, and the order of the array is the
 * order of that file.</p>
 *
 * <p>Assumptions: the array is ordered and the order is honoured, because two actions in one batch can
 * address the same row -- an insert followed by an update of the same type is a sequence the baseline
 * file can contain and applies in the order read. Treating the array as a set would make the outcome
 * depend on iteration order, which is a behavioural change rather than a structural one.</p>
 *
 * <p>Assumptions: the array is bounded and required to be non-empty. An empty batch is a request that
 * asks for nothing and would report an all-clean return code, which is indistinguishable from a batch
 * that succeeded; the bound exists because the body is read wholly into memory before anything is
 * applied.</p>
 *
 * @param actions the ordered actions to apply; required, non-empty and bounded, each element validated
 *     in turn so that a malformed element is reported against its own position
 */
public record MaintenanceActionBatchRequest(
        @NotEmpty @Size(max = 1000) @Valid List<MaintenanceActionRequest> actions) {

    /**
     * Renders the batch as its SIZE rather than as its actions.
     *
     * <p>Purpose. The single component is a list bounded at a thousand elements, so the
     * compiler-generated rendering could emit a thousand nested action renderings in one log line. That is
     * the collection half of the diagnostic rule at {@code docs/architecture/observability.md} L1093 to
     * L1112: the concern is not that any one action discloses a protected value -- a reference maintenance
     * action carries a type code, a category code and a description -- but that a rendering whose length is
     * a function of a caller-supplied count is a denial-of-service surface in a log pipeline as much as an
     * unreadable line.</p>
     *
     * <p>Alternatives Considered: rendering the first few actions and eliding the rest. Rejected because a
     * truncated list reads as a complete one to anyone who does not count, and the elision marker is the
     * only thing distinguishing them; the count states the same fact without a form that can be
     * misread.</p>
     *
     * <p>Trade-offs: an operator can no longer see WHICH actions a refused batch contained and must read
     * the request body to learn it. That is accepted: the per-action outcomes are published in the
     * response, which names each action's own index, so the pairing is recoverable from the exchange.</p>
     *
     * @return a rendering naming the action count, with the actions themselves omitted; never {@code null}
     */
    @Override
    public String toString() {
        return "MaintenanceActionBatchRequest[actions=" + (this.actions == null ? "absent"
                : this.actions.size() + " entries") + ']';
    }
}
