package com.carddemo.reference.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The query parameters shared by the three seeded address-lookup browses.
 *
 * <p>Purpose: the inbound shape of the area-code, state and state-and-postal-prefix list operations.
 * All three page by key over a single-column table, so all three take the same position and
 * direction; the classification filter is declared by the area-code browse alone.</p>
 *
 * <p>Assumptions: one shape serves three operations rather than three near-identical shapes, and the
 * classification member is simply unused by two of them. The alternative -- a separate shape per
 * lookup, two of them carrying exactly the position and the direction -- would restate the same pair
 * three times to express a difference of one optional filter. A filter absent from a request is
 * indistinguishable from a filter the operation does not declare, so nothing is lost.</p>
 *
 * @param cursor the paging position, an opaque token minted by a previous reply; absent on a first
 *     request
 * @param direction the paging direction; absent means forward, the default the contract publishes
 * @param codeClass the area-code classification filter, {@code 'G'} or {@code 'E'}; declared by the
 *     area-code browse alone and ignored by the other two, and absent means unfiltered
 */
public record LookupPageRequest(
        @Size(max = 256) @Pattern(regexp = "v1\\.[A-Za-z0-9_-]{1,200}\\.[A-Za-z0-9_-]{43}") String cursor,
        PageDirection direction,
        @Pattern(regexp = "[GE]") String codeClass) {
}
