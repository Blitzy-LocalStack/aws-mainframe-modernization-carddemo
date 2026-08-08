package com.carddemo.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The request body of the customer existence check.
 *
 * <h2>Why the customer identifier travels in a body rather than in the path</h2>
 *
 * <p>Refactoring Rationale: this check was reachable as {@code HEAD} and {@code GET} on
 * {@code /api/v1/customers/{customerId}} and the identifier travelled in the path. It moved into a body for
 * the same reason as {@link AccountLookupRequest}: the migration's sensitive-data logging contract names
 * customer identifiers explicitly, and the load balancer composes its access record from the request line
 * before any application code runs, so nothing inside a service can withdraw a value placed there.</p>
 *
 * <p>Refactoring Rationale: the two methods collapsed into ONE operation, and that is a simplification the
 * move made available rather than a capability removed. Both were served by a single handler -- the
 * framework answers {@code HEAD} from a {@code GET} mapping by discarding the body -- and both declared
 * identical status codes, so the contract described one behaviour twice and invited a reader to wonder how
 * they differed. Nothing is lost because the answer was never in a body: this check reports presence
 * entirely through its status code, so the response to the {@code POST} is still empty and a caller that
 * wanted only presence still transfers no body back.</p>
 *
 * <p>Trade-offs: {@code POST} is not idempotent by method semantics, which reads oddly for an existence
 * check that changes nothing. It is accepted for the same reason the sibling lookups accept it, and the
 * alternative that would have preserved method semantics -- keeping {@code HEAD} and putting the identifier
 * in a header -- was rejected because a resource identifier in a header is not addressable, leaves the
 * request no longer self-describing, and would still have to be excluded from the access log's header set by
 * inspection rather than by construction.</p>
 *
 * <p>Assumptions: the identifier is bounded to the nine-digit unsigned display width the reference layout
 * declares at {@code app/cpy/CVCUS01Y.cpy}. A wider value cannot match a stored row, so bounding it here
 * answers with a 400 naming the field instead of a 404 the caller would read as "no such customer".</p>
 *
 * @param customerId the customer to check for; must be within the declared nine-digit width
 */
public record CustomerLookupRequest(
        @NotNull
        @Min(CUSTOMER_ID_MIN)
        @Max(CUSTOMER_ID_MAX)
        Long customerId) {

    /**
     * The lowest value the reference layout admits for a customer identifier.
     */
    public static final long CUSTOMER_ID_MIN = 0L;

    /**
     * The highest value the reference layout admits for a customer identifier, nine nines.
     */
    public static final long CUSTOMER_ID_MAX = 999_999_999L;

    /**
     * Renders the request without disclosing the identifier it carries.
     *
     * <p>Assumptions: withheld rather than abbreviated, for the reason stated on
     * {@link AccountLookupRequest#toString()}. The entire content of this request is one prohibited value,
     * so the rendering carries only the type name.</p>
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
