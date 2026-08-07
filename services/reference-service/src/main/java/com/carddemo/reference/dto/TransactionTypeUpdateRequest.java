package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The request body that replaces a transaction type, satisfying the contract schema
 * {@code TransactionTypeReplaceRequest}.
 *
 * <p>Purpose: the inbound body of the replace operation. The key travels in the request path, so this
 * shape carries the new description and the revision the caller believes it is replacing.</p>
 *
 * <p>Assumptions: the name differs from the schema it satisfies, and the difference is deliberate
 * rather than an oversight. This package's charter fixes the suffix pair {@code CreateRequest} and
 * {@code UpdateRequest} as the convention that lets a reader tell a request from a response without
 * opening either file, and records the correspondence to {@code TransactionTypeReplaceRequest} so
 * that neither name has to be guessed at from the other.</p>
 *
 * <p>Assumptions: the version is required rather than optional, and that is what makes the
 * optimistic-lock check unavoidable. An optional version would let a caller omit it and overwrite a
 * revision it never read, which is exactly the lost update the counter exists to prevent. The
 * mechanism is the baseline's own: its update program snapshots the record it read, carries a
 * data-changed flag, and commits only when the snapshot still agrees with the stored row.</p>
 *
 * @param description the replacement description; required, capped at the copybook's fifty characters
 *     and required to carry at least one non-blank character
 * @param version the revision the caller read and intends to replace; a mismatch against the stored
 *     value is answered with HTTP 409 carrying the version the row now holds, rather than silently
 *     overwriting. Zero is admitted because the migration defaults every seeded row to zero, so a
 *     first replace of a seeded row legitimately sends it
 */
public record TransactionTypeUpdateRequest(
        @NotBlank @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*") String description,
        @NotNull @PositiveOrZero Long version) {
}
