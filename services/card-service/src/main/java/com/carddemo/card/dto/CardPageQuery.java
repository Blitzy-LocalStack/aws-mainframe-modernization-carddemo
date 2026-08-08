package com.carddemo.card.dto;

import jakarta.validation.constraints.Pattern;

/**
 * The request body of the card page search.
 *
 * <h2>Why the account narrowing travels in a body rather than in a query string</h2>
 *
 * <p>Refactoring Rationale: the listing was {@code GET /api/v1/cards} and took its account narrowing,
 * cursor and direction as QUERY PARAMETERS. The account narrowing moved into this body for the reason the
 * sibling card-number lookup already records, and the reason applies to an account identifier without
 * weakening. That operation's rationale states it exactly: the load balancer's access log is written by the
 * load balancer itself, from the request line, before any application code runs, and access logging is
 * mandatory in this deployment, so no downstream masking can redact a record that is already written. A
 * query string is part of the request line. The card number was removed from it on that basis; the
 * migration's sensitive-data logging contract names ACCOUNT AND CUSTOMER IDENTIFIERS in the same sentence
 * as the primary account number, so leaving the account number there applied the finding to one of the two
 * values it covers.</p>
 *
 * <p>Assumptions: the cursor and the direction travel in this body too, although neither is a prohibited
 * value and neither had to move. Splitting them -- narrowing in a body, paging in the query string -- was
 * rejected because it would give one operation two places to look for its criteria and would leave a reader
 * to work out which half went where; and because a {@code GET} cannot carry a body reliably in any case, so
 * once the narrowing moved the operation had to become a {@code POST} regardless. The cursor is also already
 * opaque, so it gains nothing from either position.</p>
 *
 * <p>Assumptions: every member is optional, and a body with no member at all is the whole collection one
 * page at a time -- which is the baseline list screen's initial state. That is why no member carries
 * {@code @NotNull}: a required narrowing would make the screen's opening request unexpressible.</p>
 *
 * <p>Trade-offs: as a {@code POST} the search is not cacheable and not idempotent by method semantics. The
 * sibling lookup accepts the same cost and records why the alternatives do not work -- a {@code GET} body
 * has no defined semantics for caches and intermediaries and several drop it, which would push the value
 * back into the request line, and the registered {@code SEARCH} method is unroutable here because neither
 * the gateway route keys nor the load-balancer rules admit it.</p>
 *
 * @param accountId the eleven-digit account to narrow the listing to, or {@code null} for the whole
 *     collection
 * @param cursor the opaque page cursor returned by a previous page, or {@code null} to open at the first
 *     page
 * @param direction the paging direction to apply to {@code cursor}, or {@code null} for forward
 */
public record CardPageQuery(
        @Pattern(regexp = ACCOUNT_ID_DOMAIN) String accountId,
        String cursor,
        String direction) {

    /**
     * The exact shape an account narrowing may take.
     *
     * <p>Assumptions: eleven digits exactly, matching the width the reference layout declares at
     * {@code app/cpy/CVACT01Y.cpy}. The value is validated here rather than in the handler so that a
     * malformed narrowing is refused as a 400 naming the field, instead of reaching a repository predicate
     * that would answer with an empty page a caller could not distinguish from a real one.</p>
     */
    public static final String ACCOUNT_ID_DOMAIN = "^[0-9]{11}$";

    /**
     * Renders the query without disclosing the account it narrows to.
     *
     * <p>Assumptions: the account identifier is withheld rather than abbreviated, which is the rule the
     * migration's logging contract states for every prohibited value -- and withholding it here matters
     * more than it would on a response type, because the entire purpose of moving the value off the request
     * line was to keep it out of durable records. A rendering of this body in a service log would have
     * reinstated the disclosure one hop later, in a record the same contract governs.</p>
     *
     * <p>Assumptions: the cursor is rendered only as present-or-absent rather than in full. It is not a
     * prohibited value, but it is a signed token whose whole content is an opaque key, so reproducing it
     * would fill a log line with material no operator can read while telling them nothing the presence flag
     * does not. The direction is rendered in full: it is drawn from a closed set of two.</p>
     *
     * @return a rendering carrying no account identifier and no cursor material, never {@code null}
     */
    @Override
    public String toString() {
        return "CardPageQuery[accountId=" + REDACTED
                + ", cursor=" + (this.cursor == null ? "absent" : "present")
                + ", direction=" + this.direction
                + "]";
    }

    /**
     * The text substituted for a withheld value.
     */
    private static final String REDACTED = "REDACTED";
}
