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
}
