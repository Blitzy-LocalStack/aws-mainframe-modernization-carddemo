package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One requested reference-data maintenance action, satisfying the contract schema
 * {@code MaintenanceAction}.
 *
 * <p>Purpose: an element of the maintenance batch body. It is the migrated form of one record of the
 * baseline's reference-update file, which the batch driver reads and applies one row at a time.</p>
 *
 * <p>Assumptions: the description is optional on this shape even though an insert and an update both
 * need one, because a delete needs none and the batch carries all three action kinds in one stream.
 * The baseline expresses exactly this: {@code TTYP-UPDATE-RECORD} reuses one record layout for all
 * three actions, so the description position is simply unused on a delete. Requiring it on this shape
 * would refuse a well-formed delete; requiring it per action kind is the service's obligation, and the
 * per-action outcome the reply carries is where that refusal is reported.</p>
 *
 * <p>Alternatives Considered: three separate element shapes, one per action kind, were evaluated and
 * rejected. The contract declares a single element schema with an action discriminator, so three
 * shapes would satisfy no schema the document publishes, and a heterogeneous array would have to be
 * bound by a discriminator this document does not declare.</p>
 *
 * @param action the action kind, one of {@code INSERT}, {@code UPDATE} or {@code DELETE}
 * @param typeCd the two-character transaction type the action applies to
 * @param description the description an insert or an update supplies; absent on a delete, where the
 *     baseline record leaves the position unused
 */
public record MaintenanceActionRequest(
        @NotBlank @Pattern(regexp = "INSERT|UPDATE|DELETE") String action,
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "(?:0[1-9]|[1-9][0-9])") String typeCd,
        @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*") String description) {
}
