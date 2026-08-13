package com.carddemo.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
 * Neither costs anything for the two consumers the account context read has: both are internal service
 * clients that call it once inside a transaction they are about to commit or roll back, so neither would
 * reuse a cached answer. Assumptions: this record now serves THREE operations rather than the one it was
 * written for -- the internal context read, the human account view and the by-account cross-reference
 * walk, each of which takes an account identifier and nothing else -- and the trade-off above holds for
 * the two screen reads as well, whose answer a browser must not cache in any case because it carries a
 * revision the caller is about to submit back. Alternatives Considered: keeping the {@code GET} and sealing the identifier into an opaque
 * selector, as the card contract does for a card number. Rejected here because a sealed selector has to be
 * MINTED by whoever holds the key and handed to the caller, and these callers arrive holding a raw
 * identifier they read out of a transaction record -- there is no prior response for a token to come from,
 * so sealing would have required distributing the selector key to two further services to buy the same
 * property a body already gives for nothing.</p>
 *
 * <h2>Why the identifier is characters on the wire and a number only when a row is addressed</h2>
 *
 * <p>⚠️ Refactoring Rationale: this component was a {@code Long} bounded by {@code @Min} and
 * {@code @Max}, and it is now digits-only text bounded by a pattern. It was the only account identifier
 * in any request body of any of this migration's seven contracts declared as a number -- the sibling
 * {@link AccountUpdateRequest#accountId()} on the very next route is a {@code String}, and so is the
 * {@code accountId} of every response this document publishes. Section 0.7.2 of the plan states the rule
 * the outlier broke: these identifiers transport as strings validated digits-only, because the reference
 * holds each of them as CHARACTERS and reinterprets them as numbers only for arithmetic --
 * {@code 10 CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy} L34 redefined as
 * {@code 10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11).} at L36 is that pattern stated in the layout
 * itself.</p>
 *
 * <p>⚠️ Refactoring Rationale: the consequence of the numeric form was not confined to this record. With
 * a number on the wire every consumer had to convert, and the browser client did: it grew a
 * digits-to-integer fold whose result discarded the leading zeroes that belong to the declared
 * eleven-character width, while its own documentation stated the identifier stayed text. A representation
 * that obliges each consumer to convert obliges each of them to convert correctly, and the character form
 * removes the obligation instead of restating it.</p>
 *
 * <p>Assumptions: the change is one of REPRESENTATION and not of domain. One to eleven digits spell
 * exactly the zero-through-eleven-nines range {@link #ACCOUNT_ID_MIN} and {@link #ACCOUNT_ID_MAX} state,
 * so nothing acceptable became unacceptable and nothing refused became admissible. What it adds is that
 * the width is expressible at all: a screen field is eleven characters wide and a caller may legitimately
 * send {@code 00000000011}, which no numeric binding can carry.</p>
 *
 * <p>Assumptions: the row is still addressed by a number, and {@link #accountIdNumber()} is the single
 * place the digits become one. The stored key is {@code BIGINT} because section 0.4.1.3 maps a
 * {@code PIC 9(n)} key to exactly that, so a conversion is unavoidable somewhere; performing it once
 * here, after validation, is what keeps every caller free of it -- and it is the same direction the
 * neighbouring update route already takes, which edits the submitted characters and parses them once.</p>
 *
 * <p>Trade-offs: a pattern replaces the numeric bounds, so a refusal now reports a value that is not one
 * to eleven digits rather than a value out of range. The wording differs and the outcome does not: both
 * are answered HTTP 400 naming {@code accountId} by the framework's own binding layer, before any handler
 * runs and before any file is read.</p>
 *
 * <p>Alternatives Considered: Lombok for the accessor. Rejected across this migration because generated
 * accessors cannot carry the documentation the Explainability rule requires; a record gives the same
 * brevity with every member visible in the declaration.</p>
 *
 * @param accountId the account to read, as one to eleven decimal digits; must satisfy
 *     {@link #ACCOUNT_ID_PATTERN}
 */
public record AccountLookupRequest(
        @NotNull
        @Pattern(regexp = ACCOUNT_ID_PATTERN)
        String accountId) {

    /**
     * The written form an account identifier is accepted in: one to eleven decimal digits.
     *
     * <p>Assumptions: the width is the contract and the pattern states it exactly -- at least one digit,
     * because an empty body member names no account, and at most eleven, because
     * {@code XREF-ACCT-ID PIC 9(11)} at L7 of {@code app/cpy/CVACT03Y.cpy} is the declared width and a
     * wider value cannot match a stored row. A short value is admitted rather than refused, since it
     * resolves to the same account as its zero-padded form; refusing it would refuse what an operator
     * typed into a left-blank screen field.</p>
     *
     * <p>Assumptions: no sign, no space, no decimal point and no separator, because the reference field is
     * unsigned display. Each of those would parse into some number under a lenient reading, so excluding
     * them at the binding layer is what stops a value that names no account from reaching a lookup.</p>
     */
    public static final String ACCOUNT_ID_PATTERN = "^[0-9]{1,11}$";

    /**
     * The lowest value the reference layout admits for an account identifier.
     *
     * <p>Assumptions: zero rather than one. The reference field is unsigned display and places no floor
     * above zero, so imposing one here would refuse a value the baseline can store -- and the all-zeroes
     * value in particular is refused further in, by the reference's own key edit, with that program's own
     * sentence rather than with a binding-layer message.</p>
     *
     * <p>Assumptions: retained alongside {@link #ACCOUNT_ID_PATTERN} rather than removed with the numeric
     * bounds it used to enforce, because the two state different things about one field: the pattern is
     * the accepted WRITTEN form and this is the numeric domain {@link #accountIdNumber()} yields. A reader
     * checking that the digits and the number agree needs both.</p>
     */
    public static final long ACCOUNT_ID_MIN = 0L;

    /**
     * The highest value the reference layout admits for an account identifier, eleven nines.
     *
     * <p>Assumptions: bounded because the width is a contract and not merely a hint. A wider value cannot
     * match a stored row, so refusing it here answers with a 400 that names the field rather than with a
     * 404 a caller must read as "this account does not exist". Eleven nines is what
     * {@link #ACCOUNT_ID_PATTERN}'s eleven-digit ceiling spells, so the two cannot disagree.</p>
     */
    public static final long ACCOUNT_ID_MAX = 99_999_999_999L;

    /**
     * Yields the identifier as the number the stored row is keyed by.
     *
     * <p>Assumptions: this is the ONE place in this context where the submitted characters become a
     * number, and it is placed on the record rather than repeated in each controller so that four call
     * sites cannot convert four ways. The conversion is safe without a further guard because the pattern
     * has already established one to eleven decimal digits, which is at most eleven nines and therefore
     * inside {@code long} by nine orders of magnitude; leading zeroes are consumed by the parse, which is
     * the intended reading -- {@code 00000000011} and {@code 11} name the same account.</p>
     *
     * <p>Assumptions: the conversion is a projection and not a normalisation of the request. The submitted
     * characters remain available unchanged through {@link #accountId()}, which is what the reference's own
     * key edits consume, so a refusal can still quote the field as it was written.</p>
     *
     * @return the account identifier as a number between {@link #ACCOUNT_ID_MIN} and
     *     {@link #ACCOUNT_ID_MAX} inclusive
     * @throws NumberFormatException if this record was constructed directly with a value that is not one
     *     to eleven decimal digits, which a request cannot produce because validation refuses such a body
     *     before a handler is entered
     */
    public long accountIdNumber() {
        return Long.parseLong(this.accountId);
    }

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
