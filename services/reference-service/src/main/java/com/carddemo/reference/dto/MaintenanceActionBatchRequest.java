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
}
