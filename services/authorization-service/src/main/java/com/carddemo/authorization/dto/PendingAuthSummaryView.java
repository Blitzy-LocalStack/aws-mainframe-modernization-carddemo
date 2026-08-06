package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;
import java.util.Objects;

/**
 * The account-level summary block published by the list operation.
 *
 * <h2>What this record is authoritative for</h2>
 *
 * <p>This record is the Java realisation of the {@code PendingAuthSummary} schema in
 * {@code src/main/resources/openapi/authorization-api.yaml}, and that document is the contract of
 * record for the HTTP boundary of this context. Every component below is named exactly as the schema
 * names its property, because a record component is serialised under its own name and a rename here
 * silently publishes a body the contract does not describe.
 *
 * <p>Refactoring Rationale: this record exists because the module previously had no type in the shape
 * the contract publishes. {@code PendingAuthSummaryResponse} is a projection of the BMS summary map --
 * sixty-seven components including the transaction name, both title bands, the current date and time
 * and five fixed row groups -- so it can express neither the keyset envelope this migration requires
 * of every list nor a page size other than five, and it carries screen chrome a browser client owns.
 * The two records are therefore not two candidates for one payload: this one is the HTTP body, and
 * that one is the record of what the terminal displayed, which the UI screen implements and the
 * module's own tests assert.
 *
 * <h2>Every amount is fixed point and travels as a string</h2>
 *
 * <p>Assumptions: the four limit and balance components and the two aggregate amounts are declared as
 * {@link Money} rather than as {@code BigDecimal} or a numeric primitive. The persistent columns are
 * {@code PA-CREDIT-LIMIT PIC S9(09)V99 COMP-3} and its siblings, so nine integer digits and two
 * decimals is the domain, and the contract bounds each of them with the {@code SummaryAmount} schema
 * at {@code ^-?[0-9]{1,9}\.[0-9]{2}$}. {@code Money} is the only type in this migration that both
 * holds that domain exactly and serialises as a JSON string, and a JSON number here would be parsed
 * into IEEE-754 binary floating point by most clients.
 *
 * <p>Trade-offs: the two counts are {@link Short} while the amounts are {@link Money}, which reads as
 * an inconsistency and is not one. {@code PA-APPROVED-AUTH-CNT PIC S9(04) COMP-3} is a count with no
 * decimal places and no currency, so wrapping it in a money type would assert a scale it does not
 * have; the contract bounds it as an integer between -9999 and 9999 accordingly.
 *
 * @param accountId the eleven-digit account this summary belongs to, never {@code null}
 * @param customerId the nine-digit customer that owns the account, never {@code null}
 * @param authStatus the one-character authorization status of the account, or {@code null} when the
 *     row carries none
 * @param accountStatus1 the first of the five two-character status slots, or {@code null} when unset
 * @param accountStatus2 the second of the five two-character status slots, or {@code null} when unset
 * @param accountStatus3 the third of the five two-character status slots, or {@code null} when unset
 * @param accountStatus4 the fourth of the five two-character status slots, or {@code null} when unset
 * @param accountStatus5 the fifth of the five two-character status slots, or {@code null} when unset
 * @param creditLimit the account's credit limit, never {@code null}
 * @param cashLimit the account's cash credit limit, never {@code null}
 * @param creditBalance the account's current credit balance, never {@code null}
 * @param cashBalance the account's current cash balance, never {@code null}
 * @param approvedAuthCnt how many authorizations have been approved against the account, never
 *     {@code null}
 * @param declinedAuthCnt how many authorizations have been declined against the account, never
 *     {@code null}
 * @param approvedAuthAmt the total amount approved against the account, never {@code null}
 * @param declinedAuthAmt the total amount declined against the account, never {@code null}
 */
public record PendingAuthSummaryView(
        String accountId,
        String customerId,
        String authStatus,
        String accountStatus1,
        String accountStatus2,
        String accountStatus3,
        String accountStatus4,
        String accountStatus5,
        Money creditLimit,
        Money cashLimit,
        Money creditBalance,
        Money cashBalance,
        Short approvedAuthCnt,
        Short declinedAuthCnt,
        Money approvedAuthAmt,
        Money declinedAuthAmt) {

    /**
     * The declared width of the account identifier, eleven digits.
     *
     * <p>Assumptions: read from {@code PA-ACCT-ID PIC S9(11) COMP-3} and matched by the contract's
     * {@code AccountId} schema, which fixes both {@code minLength} and {@code maxLength} at eleven. It
     * is a named constant rather than a literal at the two places that check it, because the number is
     * inherited from a file this migration does not alter.</p>
     */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The declared width of the customer identifier, nine digits.
     *
     * <p>Assumptions: read from {@code PA-CUST-ID PIC S9(09) COMP-3} and matched by the contract's
     * {@code CustomerId} schema.</p>
     */
    public static final int CUSTOMER_ID_WIDTH = 9;

    /**
     * Validates the two identifiers and refuses a summary whose money or counts are absent.
     *
     * <p>Refactoring Rationale: the checks live in the compact constructor rather than in the mapper
     * that builds this record, so that no instance can exist in a state the contract does not describe
     * -- including one built by a test, by a future controller or by deserialisation. A mapper-side
     * check would leave every other construction path unguarded.</p>
     *
     * <p>Assumptions: the identifiers are checked for exact width and digits only, and nothing else is
     * pattern-checked. The remaining string components are single characters or two-character status
     * codes whose persistent columns are {@code CHAR} and therefore admit any character, so a pattern
     * here would refuse values the database holds.</p>
     *
     * @throws NullPointerException if any component the contract marks required is {@code null}
     * @throws IllegalArgumentException if either identifier is not exactly its declared width of digits
     */
    public PendingAuthSummaryView {
        requireDigits(accountId, ACCOUNT_ID_WIDTH, "accountId");
        requireDigits(customerId, CUSTOMER_ID_WIDTH, "customerId");
        Objects.requireNonNull(creditLimit, "creditLimit is required");
        Objects.requireNonNull(cashLimit, "cashLimit is required");
        Objects.requireNonNull(creditBalance, "creditBalance is required");
        Objects.requireNonNull(cashBalance, "cashBalance is required");
        Objects.requireNonNull(approvedAuthCnt, "approvedAuthCnt is required");
        Objects.requireNonNull(declinedAuthCnt, "declinedAuthCnt is required");
        Objects.requireNonNull(approvedAuthAmt, "approvedAuthAmt is required");
        Objects.requireNonNull(declinedAuthAmt, "declinedAuthAmt is required");
    }

    /**
     * Refuses an identifier that is absent, of the wrong width or not wholly digits.
     *
     * <p>Assumptions: the identifiers are carried as strings and checked as strings, because the
     * contract publishes them as strings for a stated reason -- a leading zero is a value these
     * columns admit and a JSON number would lose it.</p>
     *
     * @param candidate the value to check, which may be {@code null}
     * @param width the exact number of digits the contract declares
     * @param component the component name to name in a refusal, so the caller learns which one failed
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not exactly {@code width} digits
     */
    private static void requireDigits(String candidate, int width, String component) {
        Objects.requireNonNull(candidate, component + " is required");
        if (candidate.length() != width) {
            throw new IllegalArgumentException(component + " must be exactly " + width
                    + " digits but was " + candidate.length() + " characters");
        }
        for (int index = 0; index < width; index++) {
            char character = candidate.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        component + " must be digits only but carried a non-digit at position "
                                + (index + 1));
            }
        }
    }
}
