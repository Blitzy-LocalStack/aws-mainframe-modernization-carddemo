package com.carddemo.reference.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The query parameters of the transaction-category browse.
 *
 * <p>Purpose: the inbound shape of the category list operation. It carries the same four members as
 * the transaction-type browse, because the contract declares the same four parameters on both.</p>
 *
 * <p>Trade-offs: the paging members are written out here rather than inherited from a shared type. A
 * Java record cannot extend a class, so inheritance is unavailable without abandoning the record form
 * that keeps these shapes immutable and their members documented; and a nested paging sub-record was
 * rejected on a wire-visible ground, since a nested object binds as a dotted or bracketed query
 * parameter, which is a different query string from the one the contract declares. The accepted cost
 * is that a change to paging is a change in each list shape.</p>
 *
 * @param cursor the paging position, an opaque token minted by a previous reply; absent on a first
 *     request
 * @param direction the paging direction; absent means forward, the default the contract publishes
 * @param typeCode an exact two-digit type-code filter, which on this browse narrows the categories to
 *     one type; absent means unfiltered
 * @param description a description filter; absent means unfiltered
 */
public record TransactionCategoryListRequest(
        @Size(max = 256) @Pattern(regexp = "v1\\.[A-Za-z0-9_-]{1,200}\\.[A-Za-z0-9_-]{43}") String cursor,
        PageDirection direction,
        @Size(min = 2, max = 2) @Pattern(regexp = "[0-9]{2}") String typeCode,
        @Size(min = 1, max = 50) String description) {
}
