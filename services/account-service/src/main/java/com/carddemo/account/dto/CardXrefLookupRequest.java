package com.carddemo.account.dto;

import com.carddemo.common.security.CardNumberMasker;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body of the card cross-reference lookup.
 *
 * <h2>Why the card number travels in a body rather than in the path</h2>
 *
 * <p>Assumptions: this endpoint is a {@code POST} carrying the card number in its body, and every other
 * read in this contract is a {@code GET} carrying its key in the path. The asymmetry is deliberate and the
 * reason is disclosure: a request path is recorded by the load balancer's access log, by the gateway's
 * execution log and by every intermediary in between, none of which the migration's security mapping allows
 * to hold a primary account number. A request body is not written to any of those. That makes {@code POST}
 * the only shape available for a lookup keyed on the number itself, even though the operation is a read and
 * has no side effect.</p>
 *
 * <p>Trade-offs: because the operation is a {@code POST}, it is not cacheable and not idempotent by method
 * semantics. Neither costs anything here -- the caller is a queue consumer deciding one authorization
 * inside a transaction, so it would not reuse a cached answer, and it makes the call exactly once per
 * message. Alternatives Considered: a {@code GET} with the number as a query parameter, which is worse than
 * a path segment rather than better, because a query string is logged in the same places and additionally
 * survives in browser history and referrer headers.</p>
 *
 * <p>Assumptions: the field is validated for EXACT width and digits-only, not merely for presence. Sixteen
 * digits is the declared contract at {@code app/cpy/CVACT03Y.cpy} and at {@code cpy/CCPAURQY.cpy} L21, so a
 * value of any other shape cannot match a stored row -- and refusing it here answers with a 400 that names
 * the field, rather than with a 404 that a caller must read as "this card is not cross-referenced".</p>
 *
 * <p>Alternatives Considered: Lombok for the accessor. Rejected across this migration because generated
 * accessors cannot carry the documentation the Explainability rule requires; a record gives the same
 * brevity with every member visible in the declaration.</p>
 *
 * @param cardNumber the sixteen-digit primary account number to resolve; must be exactly sixteen digits
 */
public record CardXrefLookupRequest(
        @NotNull
        @Size(min = CARD_NUMBER_LENGTH, max = CARD_NUMBER_LENGTH)
        @Pattern(regexp = DIGITS_ONLY)
        String cardNumber) {

    /**
     * The declared width of a primary account number, sixteen digits.
     *
     * <p>Assumptions: the same constant bounds the minimum and the maximum, because sixteen is a fixed
     * width rather than a ceiling. Bounding only the maximum would admit a shorter value that could never
     * match a stored row and would then be answered as an absent card.</p>
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * The expression admitting digits and nothing else.
     *
     * <p>Assumptions: this excludes the space that the reference's fixed-width field pads with. A padded
     * value cannot arrive here, because this boundary is JSON rather than a fixed-length record -- the
     * padding is a property of the record format the queue carries, and the consumer's own codec has already
     * removed it by the time it calls.</p>
     */
    private static final String DIGITS_ONLY = "[0-9]+";

    /**
     * Renders this request for a log line, disclosing no more of the card number than a response may.
     *
     * <p>Purpose. This record is rendered precisely when it is most sensitive: a request record reaches a
     * diagnostic BECAUSE the request failed, and its single component is a whole primary account number
     * that the caller supplied. The compiler-generated rendering would print it in full, on the validation
     * failure path, before any handler had run.</p>
     *
     * <p>Trade-offs: the value is masked through the shared masker rather than omitted, and the choice is
     * deliberate on both counts. Masking keeps the one fragment the migration's disclosure rule allows
     * everywhere -- the last four digits -- which is what makes a refusal traceable to the card that
     * provoked it; delegating keeps this rendering identical to the one the mapping layer publishes and the
     * ones the sibling contexts emit, rather than making this the second place a masking rule is
     * written.</p>
     *
     * <p>Assumptions: no branch is written for an absent or short value even though this record binds
     * caller-supplied input, where both are reachable before validation runs. The shared masker answers
     * {@code null} for an absent value and masks a short one entirely, so neither can leak digits and
     * neither can raise inside a log call -- which a rendering reached from a diagnostic path must never
     * do.</p>
     *
     * @return a rendering naming the type and the masked card number, never {@code null}
     */
    @Override
    public String toString() {
        return "CardXrefLookupRequest[cardNumber=" + CardNumberMasker.mask(this.cardNumber) + ']';
    }
}
