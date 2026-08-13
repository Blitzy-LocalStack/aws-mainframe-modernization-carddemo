package com.carddemo.reference.dto;

import java.util.List;

/**
 * The reply of the reference-data maintenance batch, satisfying the contract schema of the same name.
 *
 * <p>Purpose: the outbound shape of the batch operation, reporting one outcome per requested action
 * together with the aggregate return code the baseline's condition-code convention defines.</p>
 *
 * <p>Assumptions: the batch reports per action rather than all or nothing, and it answers HTTP 200 even
 * when some actions did not apply. That is the baseline's discipline: its driver processes each record,
 * accumulates the worst return code seen and completes, rather than abandoning the run at the first
 * reject. An all-or-nothing reply would report a run as failed that the baseline completes, and would
 * discard the outcomes of the actions that did apply.</p>
 *
 * <p>Assumptions: the return code is the worst seen across the outcomes and follows the mainframe
 * convention the suite documents -- zero clean, four a soft reject that the run tolerates, eight a
 * failure. It is carried as a number rather than inferred by a client from the outcome list, because
 * the aggregation rule is the server's and two clients reducing the same list could disagree.</p>
 *
 * @param outcomes one outcome per requested action, in the order the actions were submitted
 * @param returnCode the aggregate condition code, the worst seen across the outcomes
 */
public record MaintenanceActionBatchResponse(
        List<MaintenanceActionOutcomeResponse> outcomes, int returnCode) {

    /**
     * Renders the batch outcome as its SIZE and its return code rather than as its outcomes.
     *
     * <p>Purpose. The outcome list is as long as the request that produced it, so the compiler-generated
     * rendering scaled with a caller-supplied count -- the collection concern the diagnostic rule at
     * {@code docs/architecture/observability.md} L1093 to L1112 addresses. The request shape's own renderer
     * makes the same choice for the same reason, and the two agree deliberately: a batch and its answer
     * should be counted the same way so that a mismatch between the counts is visible in a log.</p>
     *
     * <p>Assumptions: the return code prints in full and is the component this rendering exists to carry.
     * It is the reference condition code -- zero, four, eight or sixteen -- so it is a bounded status that
     * the rule's third clause permits, and it is the one value that says whether the batch as a whole
     * succeeded, warned or failed.</p>
     *
     * <p>Trade-offs: which individual action failed is not visible from a log line and must be read from
     * the response body. That is accepted for the reason above; the aggregate code tells an operator
     * whether reading the body is worth doing.</p>
     *
     * @return a rendering naming the outcome count and the aggregate return code, with the outcomes
     *     themselves omitted; never {@code null}
     */
    @Override
    public String toString() {
        return "MaintenanceActionBatchResponse[outcomes=" + (this.outcomes == null ? "absent"
                : this.outcomes.size() + " entries")
                + ", returnCode=" + this.returnCode + ']';
    }
}
