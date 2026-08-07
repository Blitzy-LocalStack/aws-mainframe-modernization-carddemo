package com.carddemo.reference.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The query parameters of the transaction-type browse.
 *
 * <p>Purpose: the inbound shape of the list operation, carrying the paging position, the direction and
 * the two filters the browse declares. It is bound from the query string rather than from a body,
 * because the operation is a read.</p>
 *
 * <p>Assumptions: no page size, page number, offset or total count appears here, and none may be
 * added. {@code com.carddemo.common.web.PageResponse} is exactly items, first key, last key and a
 * has-next flag, so the window is the service's to decide, and a size member here would publish a
 * parameter the contract does not declare.</p>
 *
 * <p>Alternatives Considered: paging by ordinal was evaluated and rejected on a behavioural ground
 * rather than a preference. A window taken by ordinal skips rows and repeats rows when another caller
 * inserts or deletes between two requests, because what precedes the ordinal is re-counted against a
 * changed set, whereas a position naming a key cannot do either. This reference data is maintained
 * through the very operations this shape serves, so that is a real case and not a hypothetical one.</p>
 *
 * <p>Assumptions: the type-code filter admits a value the create shape refuses. The filter pattern is
 * any two digits, so {@code 00} passes it, while the create pattern excludes {@code 00} as a code the
 * seed does not contain. That asymmetry is intentional: a filter that matches no row is an empty page,
 * whereas a create carrying an unusable code is a request that should never be accepted.</p>
 *
 * @param cursor the paging position, an opaque token minted by a previous reply; absent on a first
 *     request, and meaningful only alongside a direction
 * @param direction the paging direction; absent means forward, which is the default the contract
 *     publishes, and the default is applied by the service rather than by this shape
 * @param typeCode an exact two-digit type-code filter; absent means unfiltered
 * @param description a description filter; absent means unfiltered
 */
public record TransactionTypeListRequest(
        @Size(max = 256) @Pattern(regexp = "v1\\.[A-Za-z0-9_-]{1,200}\\.[A-Za-z0-9_-]{43}") String cursor,
        PageDirection direction,
        @Size(min = 2, max = 2) @Pattern(regexp = "[0-9]{2}") String typeCode,
        @Size(min = 1, max = 50) String description) {
}
