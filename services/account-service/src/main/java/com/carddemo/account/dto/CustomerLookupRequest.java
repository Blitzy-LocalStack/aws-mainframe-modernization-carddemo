package com.carddemo.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The request body of the customer presence probe.
 *
 * <h2>Why the customer identifier travels in a body rather than in the path</h2>
 *
 * <p>Refactoring Rationale: this operation was a {@code GET} — issued by its consumer as a {@code HEAD} —
 * on {@code /api/v1/customers/{customerId}}, and the identifier travelled in the path. It moved into a
 * body for the reason {@link AccountLookupRequest} already records for an account identifier, and the
 * reason applies here without weakening: the migration's sensitive-data logging contract names ACCOUNT AND
 * CUSTOMER IDENTIFIERS alongside the primary account number as values a durable diagnostic may not carry.
 * A path segment is recorded verbatim in the load balancer's access log, and that record is composed by
 * the load balancer itself, from the request line, before any application code runs — so no masker, no
 * filter and no exception handler inside a service can reach it. A body is written to none of those
 * places.</p>
 *
 * <p>Assumptions: moving the identifier off the target did NOT cost the property that made the previous
 * shape attractive. The answer is still carried entirely by the status code and the response still has no
 * body on either outcome, so a consumer that needed only presence still receives only presence; what
 * changed is where the identifier travels, not what comes back.</p>
 *
 * <p>Trade-offs: as a {@code POST} the operation is neither cacheable nor idempotent by method semantics.
 * Neither costs anything for the one consumer this contract has: the pending-authorization context calls
 * it once inside a transaction it is about to commit or roll back, so it would not reuse a cached answer.
 * Alternatives Considered: keeping the {@code HEAD} and sealing the identifier into an opaque selector, as
 * the card contract does for a card number. Rejected for the reason {@link AccountLookupRequest} records:
 * a sealed selector has to be minted by whoever holds the key and handed to the caller, and this caller
 * arrives holding a raw identifier it read out of a cross-reference row, so there is no prior response
 * for a token to come from.</p>
 *
 * <p>Assumptions: the identifier is a {@code Long} bounded by its declared width rather than a string,
 * matching the nine-digit unsigned display field {@code CUST-ID PIC 9(09)} at L5 of
 * {@code app/cpy/CVCUS01Y.cpy}. It is a numeric key in the baseline as well as in the target and no
 * leading zero is significant, so a numeric binding refuses a non-numeric value at the binding layer and
 * reports it as a 400 naming the field.</p>
 *
 * <p>Alternatives Considered: Lombok for the accessor. Rejected across this migration because generated
 * accessors cannot carry the documentation the Explainability rule requires; a record gives the same
 * brevity with every member visible in the declaration.</p>
 *
 * @param customerId the customer to probe for; must be within the declared nine-digit width
 */
public record CustomerLookupRequest(
        @NotNull
        @Min(CUSTOMER_ID_MIN)
        @Max(CUSTOMER_ID_MAX)
        Long customerId) {

    /**
     * The lowest value the reference layout admits for a customer identifier.
     *
     * <p>Assumptions: zero rather than one, matching the {@code minimum} the published contract declares
     * for this identifier. The reference field is unsigned display and places no floor above zero, and no
     * reference program applies an all-zeroes edit to a customer key, so imposing a floor here would
     * refuse a value the baseline can store and the baseline would have read.</p>
     */
    public static final long CUSTOMER_ID_MIN = 0L;

    /**
     * The highest value the reference layout admits for a customer identifier, nine nines.
     *
     * <p>Assumptions: bounded because the width is a contract and not merely a hint. A wider value cannot
     * match a stored row, so refusing it here answers with a 400 that names the field rather than with a
     * 404 a caller must read as "this customer does not exist".</p>
     */
    public static final long CUSTOMER_ID_MAX = 999_999_999L;

    /**
     * Renders the request without disclosing the identifier it carries.
     *
     * <p>Assumptions: the customer identifier is withheld rather than abbreviated, which is the rule the
     * migration's logging contract states for every prohibited value. Withholding it leaves this rendering
     * with nothing but the type name, and that is accepted: the whole content of this request is one
     * prohibited value, so a rendering that disclosed anything useful would disclose the value itself. The
     * override exists because the record-generated one would have printed it, and this type travels through
     * the binding and validation layers where a rejected body is a natural thing to log.</p>
     *
     * @return a rendering carrying no customer identifier, never {@code null}
     */
    @Override
    public String toString() {
        return "CustomerLookupRequest[customerId=" + REDACTED + "]";
    }

    /**
     * The text substituted for a withheld value.
     */
    private static final String REDACTED = "REDACTED";
}
