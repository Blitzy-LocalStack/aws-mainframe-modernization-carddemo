package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body that adds a transaction type, satisfying the contract schema of the same name.
 *
 * <p>Purpose: the inbound body of the create operation. It carries the key because a create is the
 * one operation that chooses it; every other operation takes the key from the request path.</p>
 *
 * <p>Alternatives Considered: one shared write shape for both create and replace was evaluated and
 * rejected. A create supplies the key and carries no version, while a replace carries the version and
 * takes the key from the path, so a shared shape would have to make both members optional and then
 * decide at run time which combination was meant. Two shapes state that difference; one shape hides
 * it and turns a refusal the shape could have made into a branch the service has to write.</p>
 *
 * <p>Assumptions: no version member appears here. There is no revision to state when the row does not
 * yet exist, and the migration defaults the column to zero, so a value supplied by a caller could
 * only either agree with that default or contradict it.</p>
 *
 * @param typeCd the two-character transaction type to add; the published pattern admits
 *     {@code 01} through {@code 99} and refuses {@code 00}, which is not a type the seed contains,
 *     and the pattern is written out as character ranges rather than with the digit shorthand
 *     because that shorthand's reach depends on a matcher flag Bean Validation does not set
 * @param description the type description, from {@code TRAN-TYPE-DESC PIC X(50)} at L6 of
 *     {@code app/cpy/CVTRA03Y.cpy}; required, capped at the copybook's fifty characters, and required
 *     to carry at least one non-blank character so that an all-blank string is refused by the shape
 *     rather than stored as a description
 */
public record TransactionTypeCreateRequest(
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "(?:0[1-9]|[1-9][0-9])") String typeCd,
        @NotBlank @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*") String description) {
}
