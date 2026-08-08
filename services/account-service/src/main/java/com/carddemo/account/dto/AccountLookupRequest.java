package com.carddemo.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The request body of the account context lookup.
 *
 * <h2>Why the account identifier travels in a body rather than in the path</h2>
 *
 * <p>Refactoring Rationale: this operation was a {@code GET} on {@code /api/v1/accounts/{accountId}} and the
 * identifier travelled in the path. It moved into a body for the reason
 * {@link CardXrefLookupRequest} already records for a card number, and the reason applies here without
 * weakening: the migration's sensitive-data logging contract names ACCOUNT AND CUSTOMER IDENTIFIERS
 * alongside the primary account number as values a durable diagnostic may not carry. A path segment is
 * recorded verbatim in the load balancer's access log, and that record is composed by the load balancer
 * itself, from the request line, before any application code runs -- so no masker, no filter and no
 * exception handler inside a service can reach it. A body is written to none of those places.</p>
 *
 * <p>Assumptions: the earlier shape was not made safe by the identifier being "only" an account number
 * rather than a card number. That reading was explicitly refuted by the migration's own logging contract,
 * which covers account identifiers by name; and the value is the join key to every other row about the
 * cardholder, so a log holding it plus a timestamp locates the customer, the cards and the transactions
 * without holding any of them.</p>
 *
 * <p>Trade-offs: as a {@code POST} the operation is neither cacheable nor idempotent by method semantics.
 * Neither costs anything for the two consumers this contract has: both are internal service clients that
 * call it once inside a transaction they are about to commit or roll back, so neither would reuse a cached
 * answer. Alternatives Considered: keeping the {@code GET} and sealing the identifier into an opaque
 * selector, as the card contract does for a card number. Rejected here because a sealed selector has to be
 * MINTED by whoever holds the key and handed to the caller, and these callers arrive holding a raw
 * identifier they read out of a transaction record -- there is no prior response for a token to come from,
 * so sealing would have required distributing the selector key to two further services to buy the same
 * property a body already gives for nothing.</p>
 *
 * <p>Assumptions: the identifier is an {@code long} bounded by its declared width rather than a string,
 * matching the eleven-digit unsigned display field at {@code app/cpy/CVACT01Y.cpy}. It is a numeric key in
 * the baseline as well as in the target and no leading zero is significant, so a numeric binding refuses a
 * non-numeric value at the binding layer and reports it as a 400 naming the field.</p>
 *
 * <p>Alternatives Considered: Lombok for the accessor. Rejected across this migration because generated
 * accessors cannot carry the documentation the Explainability rule requires; a record gives the same
 * brevity with every member visible in the declaration.</p>
 *
 * @param accountId the account to read; must be within the declared eleven-digit width
 */
public record AccountLookupRequest(
        @NotNull
        @Min(ACCOUNT_ID_MIN)
        @Max(ACCOUNT_ID_MAX)
        Long accountId) {

    /**
     * The lowest value the reference layout admits for an account identifier.
     *
     * <p>Assumptions: zero rather than one, matching the {@code minimum} the published contract declares.
     * The reference field is unsigned display and places no floor above zero, so imposing one here would
     * refuse a value the baseline can store.</p>
     */
    public static final long ACCOUNT_ID_MIN = 0L;

    /**
     * The highest value the reference layout admits for an account identifier, eleven nines.
     *
     * <p>Assumptions: bounded because the width is a contract and not merely a hint. A wider value cannot
     * match a stored row, so refusing it here answers with a 400 that names the field rather than with a
     * 404 a caller must read as "this account does not exist".</p>
     */
    public static final long ACCOUNT_ID_MAX = 99_999_999_999L;

    /**
     * Renders the request without disclosing the identifier it carries.
     *
     * <p>Assumptions: the account identifier is withheld rather than abbreviated, which is the rule the
     * migration's logging contract states for every prohibited value. Withholding it leaves this rendering
     * with nothing but the type name, and that is accepted: the whole content of this request is one
     * prohibited value, so a rendering that disclosed anything useful would disclose the value itself. The
     * override exists because the record-generated one would have printed it, and this type travels through
     * the binding and validation layers where a rejected body is a natural thing to log.</p>
     *
     * @return a rendering carrying no account identifier, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountLookupRequest[accountId=" + REDACTED + "]";
    }

    /**
     * The text substituted for a withheld value.
     */
    private static final String REDACTED = "REDACTED";
}
