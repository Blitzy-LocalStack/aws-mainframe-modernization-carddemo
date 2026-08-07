package com.carddemo.reference.api;

import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.service.ReferenceBatchUpdateService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reference-data maintenance batch, the migrated form of the baseline batch driver.
 *
 * <p>Purpose: binds the ordered action array and delegates to {@code ReferenceBatchUpdateService}, which
 * applies each action in its own transaction and reports one outcome per action.</p>
 *
 * <p>Assumptions: this answers 200 even when some actions did not apply, which is what the contract
 * declares and what the baseline does -- its driver accumulates the worst condition code seen and
 * completes rather than abandoning the run at the first reject. Answering a failure status would report a
 * run as broken that the reference completes, and would discard the outcomes of the actions that applied.
 * The aggregate code is in the body, where a client that cares can read it.</p>
 */
@RestController
@RequestMapping(ReferenceMaintenanceController.BASE_PATH)
public class ReferenceMaintenanceController {

    /** The path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/maintenance-actions";

    /** The batch rules this controller delegates to. */
    private final ReferenceBatchUpdateService service;

    /**
     * Builds the controller over the batch service.
     *
     * @param service the batch rules; must not be {@code null}
     */
    public ReferenceMaintenanceController(ReferenceBatchUpdateService service) {
        this.service = service;
    }

    /**
     * Applies every submitted action in order and reports each outcome.
     *
     * @param request the validated batch body
     * @return one outcome per action in submission order, with the aggregate condition code
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public MaintenanceActionBatchResponse applyReferenceMaintenanceActions(
            @Valid @RequestBody MaintenanceActionBatchRequest request) {
        return this.service.apply(request);
    }
}
