package com.carddemo.reporting.dto;

import java.util.List;
import java.util.Objects;

/**
 * The transactions one statement was rendered from.
 *
 * <p>Purpose: the published contract declares the statement-transactions operation's body as an
 * object carrying a single {@code items} array, and this record is that object.
 *
 * <p>Assumptions: this is deliberately NOT a page. The set is delimited by the statement's own period
 * and is read to be rendered in full, so there is no open-ended sequence for a cursor to walk, and
 * publishing a page envelope here would offer a caller a forward step that could never yield
 * anything. That is why the member is {@code items} alone and why no boundary key accompanies it.
 *
 * <p>Assumptions: delimited by a period is not the same as capped by a row count, and only the first
 * is true. An earlier revision of this paragraph also cited a row ceiling on
 * {@code com.carddemo.reporting.service.StatementService}; no such ceiling exists, because carrying
 * no ceiling is divergence D-2, which that service owns and documents.
 *
 * <p>Alternatives Considered: reusing the shared page envelope for uniformity with the report's line
 * operation. Rejected for the reason above: a page envelope whose {@code hasNext} is always false and
 * whose keys are always null describes a paging capability the operation does not have, and a client
 * written against it would carry a paging loop that never runs.
 *
 * @param items the statement's transactions, each with its card number already masked; never
 *     {@code null} and copied at construction
 */
public record StatementTransactionCollection(List<StatementTransactionResponse> items) {

    /**
     * Copies and freezes the transaction list at construction.
     *
     * @throws NullPointerException if {@code items} is {@code null} or holds a {@code null} element
     */
    public StatementTransactionCollection {
        Objects.requireNonNull(items, "items must not be null; a statement covering no transaction"
                + " carries an empty list rather than an absent one");
        items = List.copyOf(items);
    }
}
