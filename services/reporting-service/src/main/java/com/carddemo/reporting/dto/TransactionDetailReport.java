package com.carddemo.reporting.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

/**
 * One rendering of the daily transaction detail report: the detail lines of a business-date range
 * together with the subtotal bands accumulated over them.
 *
 * <p>The reference emits both in a single 133-column stream, interleaving a band among the detail
 * lines wherever the depth test at {@code app/cbl/CBTRN03C.cbl} L282 or the control break fires.
 * This shape carries the same two populations as two lists, so a caller may render them interleaved,
 * render only the totals, or total-check the lines against the bands without re-parsing a column
 * layout.
 *
 * <p>Refactoring Rationale: the two populations are separate members rather than one list of a
 * union type. A union would force every consumer to discriminate on each element before it could use
 * it, and the interleaving order is derivable from the bands themselves: a page band closes each run
 * of twenty detail lines, an account band closes each run sharing a card number, and the grand band
 * closes the whole. Keeping them apart therefore loses no information and removes a discrimination
 * from every consumer.
 *
 * <p>Alternatives Considered: returning the assembled 133-column byte stream itself, which is what
 * the reference produces and what a byte-for-byte parity comparison needs. Rejected as this
 * operation's shape because a browser client would then have to parse fixed-width columns to display
 * a table, and because the assembled stream is already produced on the batch path that writes the
 * report artifact -- the mapper that emits it is
 * {@code com.carddemo.reporting.mapper.TransactionReportMapper}, and it is where parity is asserted.
 * This shape is the structured view of the same figures and is not a substitute for that stream.
 *
 * <p>Assumptions: the word page here is print pagination and never query pagination. It counts lines
 * inside a rendered document, following {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at
 * {@code app/cbl/CBTRN03C.cbl} L131 to L132, and it carries no cursor and no row ordinal. The
 * distinction is registered at {@code com.carddemo.reporting.api} and is cited rather than restated.
 *
 * @param startDate the first business date the report covers, in ISO order; never {@code null}
 * @param endDate the last business date the report covers, in ISO order, and never earlier than
 *     {@code startDate}; never {@code null}
 * @param lines the detail lines in report order -- card number then transaction identifier -- which
 *     is empty when the range selected nothing; never {@code null}
 * @param totals the subtotal bands accumulated over {@code lines}, in emission order; empty exactly
 *     when {@code lines} is empty, because a report with no detail line writes no band; never
 *     {@code null}
 */
public record TransactionDetailReport(
        @Size(max = DATE_WIDTH) @Pattern(regexp = ISO_DATE) String startDate,
        @Size(max = DATE_WIDTH) @Pattern(regexp = ISO_DATE) String endDate,
        List<TransactionReportLineResponse> lines,
        List<ReportTotalsResponse> totals) {

    /**
     * Declared width of each business date this report states.
     *
     * <p>Assumptions: ten characters, the width of the masked date form the shared date validator
     * publishes and the form the report-request map's own date subfields assemble to.
     */
    public static final int DATE_WIDTH = 10;

    /**
     * Accepted spelling of a business date: four digits, a hyphen, two digits, a hyphen, two digits.
     *
     * <p>Assumptions: the ISO ordering is asserted rather than assumed because it is what makes a
     * lexical comparison of two of these values equivalent to a date comparison, which is the
     * property the migrated schema relies on when it stores such a value as a date column.
     */
    private static final String ISO_DATE = "[0-9]{4}-[0-9]{2}-[0-9]{2}";

    /**
     * Copies and freezes both populations at construction.
     *
     * @throws NullPointerException if any component is {@code null}, or if either list holds a
     *     {@code null} element
     */
    public TransactionDetailReport {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");

        // WHY : Assumptions: both lists are copied into unmodifiable views at construction rather
        //       than being stored as handed in. A record component holds the reference it was given,
        //       so a caller retaining the original list could otherwise mutate a value this type has
        //       already validated -- and List.copyOf additionally refuses a null element, which is
        //       the one malformation a size or pattern constraint on the element type cannot reach.
        lines = List.copyOf(lines);
        totals = List.copyOf(totals);
    }

    /**
     * Renders the report as its date range and the SIZES of its two lists.
     *
     * <p>Purpose. Both lists carry values withheld by {@code docs/architecture/observability.md} L1093 to
     * L1112 -- every detail line holds an account identifier and an amount, and every total holds a sum --
     * so the compiler-generated rendering emitted an entire report, one account and one figure at a time,
     * into whatever stringified it.</p>
     *
     * <p>Assumptions: the two dates are kept and are the report's identity. They are the parameters the
     * reference job is driven by rather than values read from a row, so they name WHICH report this is
     * without naming anything in it, and a report produced for the wrong range is the most common fault
     * this rendering is read for.</p>
     *
     * <p>Trade-offs: the two sizes are rendered rather than the lists, and the pair of counts is worth
     * more than either alone -- a report with lines but no totals, or totals with no lines, is a
     * generation fault that both counts together make obvious.</p>
     *
     * @return a rendering naming the start and end dates and the sizes of the line and total lists, with
     *     the lines and totals themselves omitted; never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionDetailReport[startDate=" + this.startDate
                + ", endDate=" + this.endDate
                + ", lines=" + (this.lines == null ? "absent" : this.lines.size() + " entries")
                + ", totals=" + (this.totals == null ? "absent" : this.totals.size() + " entries") + ']';
    }
}
