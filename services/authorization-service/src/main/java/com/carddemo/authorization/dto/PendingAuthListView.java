package com.carddemo.authorization.dto;

import com.carddemo.common.web.PageResponse;
import java.util.List;
import java.util.Objects;

/**
 * The body of the pending-authorization list operation: the account summary and one page of rows.
 *
 * <h2>What this record is authoritative for</h2>
 *
 * <p>This record is the Java realisation of the {@code PendingAuthListResponse} schema in
 * {@code src/main/resources/openapi/authorization-api.yaml}, and that document is the contract of record
 * for the HTTP boundary of this context.
 *
 * <p>Assumptions: the summary and the page are returned together in one body rather than from two
 * operations. The reference screen shows the account header and its rows in ONE screen turn, so
 * splitting them would turn one turn into two round trips and would let a client render a header from
 * one account beside rows from another if the two calls interleaved.
 *
 * <h2>The page is the shared envelope, not a type of this context's own</h2>
 *
 * <p>Refactoring Rationale: {@code page} is a {@link PageResponse} of {@link PendingAuthRowView} rather
 * than a bespoke record, because the four-component keyset envelope is shared by every list in this
 * migration and its cursor invariants are enforced in one place. The summary sits BESIDE the envelope
 * rather than inside it for the same reason in reverse: adding a fifth component to
 * {@code PageResponse} would change a type seven other contexts also serialise, and per-account summary
 * data has no meaning on any of their pages.
 *
 * @param summary the account-level summary, identical on every page of the same account, never
 *     {@code null}
 * @param page one page of that account's pending-authorization rows, never {@code null}
 * @param screenMessage the navigation-boundary sentence for this request, or {@code null} when the
 *     request was not a paging move that had already reached a boundary
 */
public record PendingAuthListView(
        PendingAuthSummaryView summary,
        PageResponse<PendingAuthRowView> page,
        String screenMessage) {

    /**
     * The sentence published when a backward move was already on the opening page.
     *
     * <p>Assumptions: carried character for character from {@code cbl/COPAUS0C.cbl} L381, including its
     * trailing ellipsis, because a user-visible string is part of the observable contract.</p>
     */
    public static final String MESSAGE_TOP_OF_PAGE = "You are already at the top of the page...";

    /**
     * The sentence published when a forward move was already on the closing page.
     *
     * <p>Assumptions: carried character for character from {@code cbl/COPAUS0C.cbl} L409-L410.</p>
     */
    public static final String MESSAGE_BOTTOM_OF_PAGE =
            "You are already at the bottom of the page...";

    /**
     * The sentence published when no further authorization follows the one in hand.
     *
     * <p>Assumptions: carried character for character from {@code cbl/COPAUS1C.cbl} L283-L284. It is a
     * distinct sentence from the bottom-of-page wording and is deliberately not merged with it: the
     * reference programs emit them from two different paragraphs on two different screens.</p>
     */
    public static final String MESSAGE_LAST_AUTHORIZATION = "Already at the last Authorization...";

    /**
     * The closed set of sentences the boundary message may carry.
     *
     * <p>Refactoring Rationale: the set is declared once and enforced in the compact constructor rather
     * than left to the contract's enumeration alone. A response body is not validated against its own
     * contract at runtime, so an authored sentence that drifted by one character -- a lost ellipsis, a
     * capitalised word -- would be published as though it were the baseline's.</p>
     */
    public static final List<String> BOUNDARY_MESSAGES =
            List.of(MESSAGE_TOP_OF_PAGE, MESSAGE_BOTTOM_OF_PAGE, MESSAGE_LAST_AUTHORIZATION);

    /**
     * Validates the two required components and confines the boundary message to its closed set.
     *
     * @throws NullPointerException if {@code summary} or {@code page} is {@code null}
     * @throws IllegalArgumentException if {@code screenMessage} is present but is not one of the three
     *     reference sentences
     */
    public PendingAuthListView {
        Objects.requireNonNull(summary, "summary is required");
        Objects.requireNonNull(page, "page is required");
        if (screenMessage != null && !BOUNDARY_MESSAGES.contains(screenMessage)) {
            throw new IllegalArgumentException(
                    "screenMessage must be one of the three reference navigation sentences");
        }
    }

    /**
     * Renders this body as the shape of the page it carries, delegating to neither nested component.
     *
     * <p>Purpose. Both nested components carry protected values -- the summary holds the account's
     * financial position and the cardholder's postal identity, and each row holds an approved amount --
     * so the compiler-generated rendering disclosed a whole page of them through one call.</p>
     *
     * <p>Alternatives Considered: relying on the two nested renderings instead, now that each nested
     * type declares a safe one. Rejected because that makes this type's disclosure a property of two
     * other files: a component added to the summary without its renderer updated, or a page envelope
     * whose rendering changed, would leak through here with nothing in this file to show it. Reading the
     * page's shape directly makes this rendering independent of both.</p>
     *
     * <p>Assumptions: the row count is read from the envelope's item list rather than from its
     * {@code hasNext} indicator, because the two answer different questions -- how many rows this body
     * actually carries, and whether another page follows -- and a paging fault is usually a disagreement
     * between them.</p>
     *
     * <p>Trade-offs: the boundary sentence is rendered in full because it is one of exactly three
     * reference strings this record's own compact constructor confines it to, so it can carry no value
     * from a row and no text from a client.</p>
     *
     * @return a rendering carrying the row count, the forward-availability indicator and the boundary
     *     sentence, with the summary and the rows themselves omitted; never {@code null}
     */
    @Override
    public String toString() {
        return "PendingAuthListView[rows=" + this.page.items().size()
                + ", hasNext=" + this.page.hasNext()
                + ", screenMessage=" + this.screenMessage + ']';
    }
}
