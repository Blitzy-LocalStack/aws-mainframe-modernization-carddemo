package com.carddemo.authorization.dto;

import com.carddemo.common.error.FieldOrdering;
import com.carddemo.common.web.CursorToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The request body of the pending-authorization page search.
 *
 * <h2>Why the account scope travels in a body rather than in a query string</h2>
 *
 * <p>Refactoring Rationale: the listing was {@code GET /api/v1/authorizations} and took its account
 * scope, cursor and direction as QUERY PARAMETERS. The scope moved into this body because it is an account
 * identifier, and the migration's sensitive-data logging contract names account and customer identifiers
 * alongside the primary account number as values a durable diagnostic may not hold. A query string is part
 * of the request line, and the load balancer composes its access record from the request line itself,
 * before any application code runs -- so no masker, filter or exception handler inside this service can
 * withdraw a value placed there. Access logging is mandatory in this deployment, so the value had to leave
 * the request line rather than be redacted after it.</p>
 *
 * <p>Assumptions: the exposure this closes was larger here than on the sibling card listing, because this
 * scope is REQUIRED rather than optional. Every single request to the pending-authorization list carried an
 * account identifier into the access log, so the disclosure was not an edge case a particular filter choice
 * produced -- it was every use of the screen.</p>
 *
 * <p>Assumptions: the cursor and the direction travel here too, although neither is a prohibited value.
 * Once the scope had to move the operation had to become a {@code POST}, because a {@code GET} body has no
 * defined semantics for caches and intermediaries and several drop it; and splitting the criteria across a
 * body and a query string would leave one operation with two places to carry them.</p>
 *
 * <p>Trade-offs: the operation is no longer idempotent by method semantics. That costs nothing observable
 * -- it changes nothing this context owns, and the browser client issues it once per page -- and the
 * alternative that would have preserved the verb is to leave the identifier in the request line, which is
 * the disclosure being closed.</p>
 *
 * @param accountId the account whose pending authorizations are wanted, as exactly eleven digits; required
 * @param cursor the sealed paging position to continue from, or {@code null} for the opening page
 * @param direction {@code next} or {@code previous}, or {@code null} to default to next; meaningful only
 *     alongside a cursor
 */
public record PendingAuthPageQuery(
        @NotBlank(message = MESSAGE_ACCOUNT_ID_REQUIRED)
        @Pattern(regexp = ACCOUNT_ID_DOMAIN, message = MESSAGE_ACCOUNT_ID_NUMERIC)
        String accountId,

        @Size(max = CursorToken.MAX_TOKEN_LENGTH)
        String cursor,

        @Pattern(regexp = DIRECTION_DOMAIN)
        String direction) implements FieldOrdering {

    /**
     * Returns the order the reference program checks these members in.
     *
     * <p>Refactoring Rationale: this record declares a check order because the reference screen LATCHES
     * its first failing sentence rather than accumulating, and because moving these criteria out of the
     * query string changed which advice path renders their rejection. As query parameters they were
     * rendered by the parameter handler, which orders a single parameter's constraints presence-first and
     * takes the aggregate sentence from the first entry -- that handler's own rationale cites this very
     * screen as the case that forced it. As body members they are rendered by the bound-body handler,
     * which promotes the first entry's sentence to the aggregate only for a body that declares its order.
     * Without this method the relocation would have silently replaced the reference sentence at the
     * aggregate position with the generic one, which transformation rule T8 forbids and which the
     * published contract does not describe.</p>
     *
     * <p>Assumptions: {@code accountId} is first because the reference tests blank BEFORE numeric and
     * stops at the first failure -- it rejects a blank value at {@code cbl/COPAUS0C.cbl} L264 and reaches
     * its numeric test at L272 only when the blank test passed. The paging members follow it because
     * neither is reachable as a first failure on a request the screen can produce: the screen sends no
     * direction without a cursor, and a cursor it did not receive from this service cannot be constructed.</p>
     *
     * @return the member names in the order the reference checks them, never {@code null}
     */
    @Override
    public List<String> fieldOrder() {
        return List.of("accountId", "cursor", "direction");
    }

    /**
     * The exact shape an account scope may take: eleven digits, or empty.
     *
     * <p>Refactoring Rationale: the handler's constraint was {@code ^[0-9]{11}$} while this admits the
     * empty string as well, and the widening is deliberate rather than a relaxation. Emptiness is owned by
     * the presence constraint above, which carries the reference's own blank sentence; a pattern that also
     * refused the empty string made a blank value fail BOTH constraints, so the rejection carried two
     * entries for one member and the sentence promoted to the aggregate was whichever the validation
     * provider happened to produce first -- a provider is explicitly free to evaluate a member's
     * constraints in any order. Letting the pattern pass an empty value leaves exactly one violation per
     * case, which makes the promoted sentence deterministic without depending on ordering the provider
     * does not guarantee.</p>
     *
     * <p>Assumptions: nothing is admitted by this widening that the presence constraint does not refuse,
     * so the pair still rejects the empty string -- and rejects it with the reference's blank sentence
     * rather than its numeric one, which is the distinction the baseline draws by testing blank at
     * {@code cbl/COPAUS0C.cbl} L264 before reaching its numeric test at L272.</p>
     *
     * <p>Alternatives Considered: keeping the strict pattern and ordering the two entries
     * presence-first, as the parameter-validation path does within one parameter. Rejected because the
     * bound-body path sorts by MEMBER and its sort is stable, so two entries for one member keep the
     * provider's order and there is no hook to order within a member -- the fix had to make the second
     * violation not arise rather than sort it.</p>
     *
     * <p>⚠️ Refactoring Rationale: the widening was {@code ^$} and admitted only the EMPTY string, which
     * left the reasoning above true of exactly one of the values the presence constraint refuses. The
     * presence constraint is {@code @NotBlank}, so it refuses every all-whitespace value, and eleven
     * spaces -- which is precisely what a fixed-width screen field sends when the operator leaves it
     * untouched -- therefore failed BOTH constraints and produced the two entries this widening exists to
     * prevent. The observed rejection carried two entries for {@code accountId}, both stating BLANK, the
     * second carrying the numeric sentence, so a client rendering per-member errors showed the field
     * complaining twice. Admitting any all-whitespace value restores the invariant the paragraphs above
     * assert: at most one violation per member per request, whatever blank shape arrives.</p>
     *
     * <p>Assumptions: this widened form is deliberately NOT published in the OpenAPI document, whose
     * {@code AccountId} schema keeps the strict {@code ^[0-9]{11}$} with an eleven-character length.
     * The two are describing different things and reconciling them would be wrong: the published pattern
     * states what a caller must SEND, and blanks are not acceptable input, whereas this constant exists
     * only to keep one refusal from being counted twice by a validation provider whose per-member
     * evaluation order is unspecified. Publishing the whitespace alternative would tell a client that a
     * blank scope is admissible when the request is refused either way.</p>
     */
    public static final String ACCOUNT_ID_DOMAIN = "^\\s*$|^[0-9]{11}$";

    /**
     * The two directions the paging vocabulary admits.
     */
    public static final String DIRECTION_DOMAIN = "^(next|previous)$";

    /**
     * The message a missing account scope is refused with.
     *
     * <p>Assumptions: the text is the baseline's own, carried across verbatim under transformation rule
     * T8, and it is declared here rather than in the handler so that the message travels with the
     * constraint it belongs to.</p>
     */
    public static final String MESSAGE_ACCOUNT_ID_REQUIRED = "Please enter Acct Id...";

    /**
     * The message a non-numeric account scope is refused with.
     */
    public static final String MESSAGE_ACCOUNT_ID_NUMERIC = "Acct Id must be Numeric ...";

    /**
     * Renders the query without disclosing the account it is scoped to.
     *
     * <p>Assumptions: the account identifier is withheld rather than abbreviated, which is the rule the
     * migration's logging contract states for every prohibited value. It matters more on this type than on
     * a response shape: the entire purpose of moving the value out of the request line was to keep it out
     * of durable records, and rendering this body into a service log would reinstate the disclosure one hop
     * later, in a record the same contract governs.</p>
     *
     * <p>Assumptions: the cursor is rendered as present-or-absent rather than in full. It is not a
     * prohibited value, but its content is opaque sealed material, so reproducing it fills a log line with
     * bytes no operator can read while saying nothing the presence flag does not.</p>
     *
     * @return a rendering carrying no account identifier and no cursor material, never {@code null}
     */
    @Override
    public String toString() {
        return "PendingAuthPageQuery[accountId=" + REDACTED
                + ", cursor=" + (this.cursor == null ? "absent" : "present")
                + ", direction=" + this.direction
                + "]";
    }

    /**
     * The text substituted for a withheld value.
     */
    private static final String REDACTED = "REDACTED";
}
