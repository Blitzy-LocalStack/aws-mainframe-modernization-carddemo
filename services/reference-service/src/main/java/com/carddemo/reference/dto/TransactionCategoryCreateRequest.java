package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body that adds a transaction category, satisfying the contract schema of the same name.
 *
 * <p>Purpose: the inbound body of the category create operation, carrying both halves of the
 * composite key it chooses and the description.</p>
 *
 * <p>Assumptions: the type half is validated here as a well-formed code and not as an existing one.
 * Whether the referenced type exists is decided against stored data, by the foreign key the migration
 * declares {@code ON DELETE RESTRICT}, and it surfaces to a caller as HTTP 409 rather than as a
 * driver error. A shape cannot check it, and pretending to would leave two places able to disagree
 * about the same question.</p>
 *
 * @param typeCd the two-character transaction type this category belongs to; must reference a type
 *     that exists, which is checked against the stored row rather than by this constraint
 * @param catCd the four-digit category code with its leading zeros intact; exactly four digits,
 *     because the seed keys the row by positional concatenation -- its first key is literally
 *     {@code 010001}, the type {@code 01} followed by the category {@code 0001} -- and
 *     {@code app/jcl/TRANCATG.jcl} L40 declares a six-byte key that balances only if this field
 *     occupies four bytes beside the two of the type code
 * @param description the category description; required, capped at fifty characters and required to
 *     carry at least one non-blank character
 */
public record TransactionCategoryCreateRequest(
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "(?:0[1-9]|[1-9][0-9])") String typeCd,
        @NotBlank @Size(min = 4, max = 4) @Pattern(regexp = "[0-9]{4}") String catCd,
        @NotBlank @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*") String description) {
}
