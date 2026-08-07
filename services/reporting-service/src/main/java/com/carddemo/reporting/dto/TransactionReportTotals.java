package com.carddemo.reporting.dto;

import java.util.List;
import java.util.Objects;

/**
 * The subtotal bands of one report run, in the order the report emits them.
 *
 * <p>Purpose: the published contract declares the totals operation's body as an OBJECT carrying a
 * single {@code bands} array rather than as a bare array, and this record is that object. It adds no
 * information to the list it wraps; what it adds is a place for a member to be added later without
 * changing the shape of the body, which a bare array cannot offer.
 *
 * <p>Alternatives Considered: answering the totals operation with a bare JSON array of bands, which
 * is one fewer type and one fewer level of nesting. Rejected because a top-level array cannot be
 * extended -- a later run identifier or a range echo would have to become a breaking change of the
 * body's outermost type -- and because every other operation of this contract answers with an object,
 * so a lone array would also be an inconsistency a client has to remember.
 *
 * <p>Assumptions: the band list is the one {@code TransactionReportService.composeTotals} produces
 * and is not re-ordered here. The order IS the report's order -- page subtotals interleaved with the
 * account and grand bands as {@code app/cbl/CBTRN03C.cbl} emits them -- so a sort applied here would
 * destroy information the caller renders from.
 *
 * @param bands the subtotal bands in emission order; never {@code null} and copied at construction
 */
public record TransactionReportTotals(List<ReportTotalsResponse> bands) {

    /**
     * Copies and freezes the band list at construction.
     *
     * <p>Assumptions: the list is copied rather than retained, so a caller that keeps its own
     * reference and mutates it afterwards cannot change a body already handed to the serializer. The
     * copy also refuses a null element, which a caller assembling bands in a loop could otherwise
     * introduce without noticing.
     *
     * @throws NullPointerException if {@code bands} is {@code null} or holds a {@code null} element
     */
    public TransactionReportTotals {
        Objects.requireNonNull(bands, "bands must not be null; a report with no subtotal band"
                + " carries an empty list rather than an absent one");
        bands = List.copyOf(bands);
    }
}
