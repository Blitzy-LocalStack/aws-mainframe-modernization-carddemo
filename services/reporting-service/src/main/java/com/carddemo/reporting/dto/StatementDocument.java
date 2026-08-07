package com.carddemo.reporting.dto;

import java.util.List;
import java.util.Objects;

/**
 * One card's statement as a whole document: the heading figures together with every transaction the
 * statement lists.
 *
 * <p>A statement is a document rather than a collection, and this shape says so. The reference
 * assembles one per card and writes it complete -- {@code app/cbl/CBSTM03A.CBL} drives one card at a
 * time and emits its heading and then all of that card's transactions into the same artifact -- so a
 * caller that received the heading without the transactions would hold something the reference never
 * produces.
 *
 * <p>Refactoring Rationale: the transactions are a member of this document rather than a separately
 * paged collection, which is the one place this context deliberately does not use keyset paging. A
 * cursor exists to let a caller walk a set larger than it wants to hold at once; a statement is
 * bounded by one card's activity in one period and is read to be rendered in full, so paging it would
 * add a cursor round trip per screen for a document whose consumer needs all of it before it can
 * render any of it. The read behind this shape is nonetheless bounded, at
 * {@code com.carddemo.reporting.service.StatementService#MAX_STATEMENT_TRANSACTIONS}, so an
 * unexpectedly large card cannot make the response unbounded.
 *
 * <p>Alternatives Considered: exposing only the heading and asking a caller to fetch the transactions
 * from the transaction context. Rejected because the statement's transaction list is not the same
 * population as that context's ledger: it is the card-ordered read-only projection this module is
 * granted, filtered to one card, and it carries the narrowed primary account number this module
 * publishes rather than the stored one. Sending a caller elsewhere would give it a different
 * projection under a different disclosure rule.
 *
 * <p>Assumptions: an empty transaction list is a legitimate document and not an error. The reference
 * walks the cross-reference and produces a statement for every card it finds, whether or not that
 * card had activity, so a card with none yields a heading and no lines rather than no statement.
 *
 * @param statement the heading figures, the two artifact locations and the assembled total; never
 *     {@code null}
 * @param transactions every transaction the statement lists, in ascending transaction-identifier
 *     order, which is empty for a card with no activity in the period; never {@code null}
 */
public record StatementDocument(
        StatementResponse statement,
        List<StatementTransactionResponse> transactions) {

    /**
     * Copies and freezes the transaction list at construction.
     *
     * @throws NullPointerException if either component is {@code null}, or if the list holds a
     *     {@code null} element
     */
    public StatementDocument {
        Objects.requireNonNull(statement, "statement");

        // WHY : Assumptions: the list is copied into an unmodifiable view rather than stored as
        //       handed in, for the same reason the report shape copies its own two lists: a record
        //       component holds the reference it was given, so a caller retaining the original could
        //       mutate a document this type has already validated.
        transactions = List.copyOf(transactions);
    }
}
