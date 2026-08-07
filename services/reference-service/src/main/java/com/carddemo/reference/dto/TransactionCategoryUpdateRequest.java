package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The request body that replaces a transaction category, satisfying the contract schema
 * {@code TransactionCategoryReplaceRequest}.
 *
 * <p>Purpose: the inbound body of the category replace operation. Both halves of the key travel in
 * the request path, so this shape carries only the new description and the revision being replaced.
 * Its name follows this package's suffix convention; the charter records the correspondence.</p>
 *
 * <p>Assumptions: neither key half appears here, and admitting one "for clarity" was considered and
 * rejected. It would give a caller a second place to state a key with no rule to follow when the two
 * disagreed, and the contract declares neither of them in the body.</p>
 *
 * @param description the replacement description; required, capped at fifty characters and required
 *     to carry at least one non-blank character
 * @param version the revision the caller read and intends to replace; a mismatch is answered with
 *     HTTP 409 carrying the version the row now holds
 */
public record TransactionCategoryUpdateRequest(
        @NotBlank @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*") String description,
        @NotNull @PositiveOrZero Long version) {
}
