package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.MaskedCardNumber;
import com.carddemo.common.web.CursorToken;
import java.util.List;
import java.util.Objects;

/**
 * The body of the pending-authorization read operation: one authorization in full.
 *
 * <h2>What this record is authoritative for</h2>
 *
 * <p>This record is the Java realisation of the {@code PendingAuthDetail} schema in
 * {@code src/main/resources/openapi/authorization-api.yaml}, and that document is the contract of record
 * for the HTTP boundary of this context. Every component is named exactly as the schema names its
 * property.
 *
 * <p>Refactoring Rationale: this record and {@code PendingAuthDetailResponse} are not two candidates for
 * one payload. This one publishes the STORED value of every column, so a client can compute any
 * presentation it needs; that one publishes the twenty-seven positions of the BMS detail map, with the
 * card expiry composed to five characters around a solidus, the reason composed to twenty characters
 * around a separator and the fraud mark composed with its report date -- compositions the reference
 * program performs on the way to the screen and which the browser client performs instead. Keeping the
 * compositions out of this body is what lets the divergence registered as D-AUTH-REASON-WIDTH stay a
 * statement about the screen rather than a truncation applied to persisted data on its way out.
 *
 * <h2>Both amounts are published, and they differ on a decline</h2>
 *
 * <p>Assumptions: {@code transactionAmt} is what the acquirer asked for and {@code approvedAmt} is what
 * was granted; on a declined authorization the second is zero while the first is not. The list resource
 * carries only the approved amount, so this is the resource on which the pair is reconcilable, and
 * publishing only one of them here would make the difference unobservable anywhere.
 *
 * <p>Assumptions: both are declared as {@link Money}, bounded by the contract's {@code DetailAmount}
 * schema at ten integer digits and two decimals, and serialised as JSON strings. The columns are
 * {@code PIC S9(10)V99 COMP-3}, so ten and two is the domain rather than a chosen bound.
 *
 * <h2>The three key parts are published beside the sealed selector</h2>
 *
 * <p>Assumptions: {@code key} is the sealed token a client sends back, while {@code accountId},
 * {@code authDate} and {@code authTime} are the three parts of the row's own identity. Publishing both is
 * deliberate: the token is opaque by design, so a client that needs to display or sort by the
 * authorization's date and time has no way to read them out of it, and a client that needs to address the
 * row again must not have to reassemble a key from parts.
 *
 * @param key the sealed selector addressing this authorization, never {@code null}
 * @param accountId the eleven-digit account the authorization is against, never {@code null}
 * @param authDate the authorization date as the stored ordinal, never {@code null}
 * @param authTime the authorization time as the stored millisecond-of-day, never {@code null}
 * @param authOrigDate the six stored characters of the originating date, or {@code null} when none
 * @param authOrigTime the six stored characters of the originating time, or {@code null} when none
 * @param cardNum the card the authorization is against, masked to its last four digits, never
 *     {@code null}
 * @param authType the four-character authorization type, or {@code null} when none
 * @param cardExpiryDate the four stored characters of the card expiry, or {@code null} when none
 * @param messageType the message type the acquirer supplied, or {@code null} when none
 * @param messageSource the message source the acquirer supplied, or {@code null} when none
 * @param authIdCode the identification code returned to the acquirer, or {@code null} when none
 * @param authRespCode the response code returned to the acquirer, or {@code null} when none
 * @param authRespReason the response reason returned to the acquirer, or {@code null} when none
 * @param processingCode the processing code the acquirer supplied, or {@code null} when none
 * @param transactionAmt the amount the acquirer requested, never {@code null}
 * @param approvedAmt the amount actually approved, never {@code null}
 * @param merchantCategoryCode the merchant category code, or {@code null} when none
 * @param acqrCountryCode the acquirer country code, or {@code null} when none
 * @param posEntryMode the point-of-sale entry mode as its stored digits, or {@code null} when none
 * @param merchantId the merchant identifier, or {@code null} when none
 * @param merchantName the merchant name, or {@code null} when none
 * @param merchantCity the merchant city, or {@code null} when none
 * @param merchantState the merchant state, or {@code null} when none
 * @param merchantZip the merchant postal code, or {@code null} when none
 * @param transactionId the acquirer's transaction identifier, never {@code null}
 * @param matchStatus one of the four one-character match statuses, never {@code null}
 * @param authFraud the one-character fraud indicator, or {@code null} when the authorization is
 *     unmarked
 * @param fraudRptDate the eight stored characters of the fraud report date, or {@code null} when the
 *     authorization is unmarked
 */
public record PendingAuthDetailView(
        String key,
        String accountId,
        Integer authDate,
        Integer authTime,
        String authOrigDate,
        String authOrigTime,
        String cardNum,
        String authType,
        String cardExpiryDate,
        String messageType,
        String messageSource,
        String authIdCode,
        String authRespCode,
        String authRespReason,
        String processingCode,
        Money transactionAmt,
        Money approvedAmt,
        String merchantCategoryCode,
        String acqrCountryCode,
        String posEntryMode,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantState,
        String merchantZip,
        String transactionId,
        String matchStatus,
        String authFraud,
        String fraudRptDate) {

    /**
     * Validates every component the contract marks required and confines the two closed domains.
     *
     * <p>Refactoring Rationale: the guards live here rather than in the mapper so that no instance can
     * exist in a state the contract does not describe, whichever path constructed it. The match status is
     * re-checked through {@link PendingAuthRowView}'s own declared domain rather than restated, so the
     * list and the detail resources cannot come to disagree about it.</p>
     *
     * <p>Refactoring Rationale: the card number is checked by
     * {@link MaskedCardNumber#require(String, String)} rather than by the length-and-first-character test
     * that stood here. That test was an approximation of the list view's rule and admitted values the rule
     * refuses: {@code *234567890123456} is sixteen characters beginning with the mask character, so it
     * passed while disclosing fifteen digits of a card number. The masking obligation is stated once in
     * the shared module now, and this contract reads it instead of restating a weaker form of it.</p>
     *
     * <p>Assumptions: the two response members are checked against the CLOSED domains this context's
     * contract publishes for them -- two values for the code and eight for the reason, each taken from
     * the producer's own {@code MOVE} statements at {@code cbl/COPAUA0C.cbl} L688 and L693 and at L698
     * and L700 to L717. Both may be absent, because a stored row may carry no reply at all, and a
     * stored blank is treated as absent for the same reason the migration's check constraints admit one:
     * a COBOL character field nothing was moved into holds spaces rather than a null. Checking them here
     * as well as in the database is not redundant: the database binds what may be STORED while this
     * binds what may be PUBLISHED, and a row that predates the constraints would otherwise be
     * serialised into a body the contract says cannot exist.</p>
     *
     * @throws NullPointerException if any required component is {@code null}
     * @throws IllegalArgumentException if the selector is not a sealed token, if the match status is
     *     outside its four-value domain, if either response member is outside the closed domain the
     *     contract publishes for it, or if the card number is not in the masked form the contract
     *     publishes
     */
    public PendingAuthDetailView {
        Objects.requireNonNull(key, "key is required");
        if (!CursorToken.hasSealedShape(key)) {
            throw new IllegalArgumentException(
                    "key must be a sealed cursor token, so that a row selector cannot be forged");
        }
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(authDate, "authDate is required");
        Objects.requireNonNull(authTime, "authTime is required");
        Objects.requireNonNull(transactionAmt, "transactionAmt is required");
        Objects.requireNonNull(approvedAmt, "approvedAmt is required");
        Objects.requireNonNull(transactionId, "transactionId is required");
        if (!PendingAuthRowView.MATCH_STATUSES.contains(matchStatus)) {
            throw new IllegalArgumentException("matchStatus must be one of "
                    + PendingAuthRowView.MATCH_STATUSES + " but was " + matchStatus);
        }
        requireWithinDomain(authRespCode, RESPONSE_CODES, "authRespCode");
        requireWithinDomain(authRespReason, RESPONSE_REASONS, "authRespReason");

        // WHY : Refactoring Rationale: the mask check delegates to the shared module's validator rather
        //       than restating a weaker one. The restated form here tested only the length and the first
        //       character, so "*123456789012345" satisfied it and disclosed fifteen digits of a primary
        //       account number on a detail body. One validator means the list and the detail resources
        //       -- and the two card contracts that read the same rule -- cannot disagree about what
        //       masked means, which is exactly the divergence that let the weaker test survive beside
        //       the stricter one.
        MaskedCardNumber.require("cardNum", cardNum);
    }

    /**
     * The response codes this context can produce, and the only ones a detail body may carry.
     *
     * <p>Assumptions: two values, from the two {@code MOVE} statements that write the field --
     * {@code cbl/COPAUA0C.cbl} L688 moves the declined code and L693 the approved one -- with no third
     * branch between them. The published contract enumerates the same two plus null.</p>
     */
    public static final List<String> RESPONSE_CODES = List.of("00", "05");

    /**
     * The response reasons this context can produce, and the only ones a detail body may carry.
     *
     * <p>Assumptions: eight values. {@code cbl/COPAUA0C.cbl} L698 writes the approved reason and its
     * L700 to L717 select one of seven decline reasons: not found across the cross-reference, the
     * account master or the customer master collapse into one at L704, then insufficient funds at L706,
     * card not active at L708, account closed at L710, card fraud at L712, merchant fraud at L714 and a
     * catch-all at L716.</p>
     */
    public static final List<String> RESPONSE_REASONS =
            List.of("0000", "3100", "4100", "4200", "4300", "5100", "5200", "9000");

    /**
     * Refuses a response member that is present, non-blank and outside its published domain.
     *
     * <p>Assumptions: absence and blankness both pass. The column is nullable because a stored row may
     * carry no reply, and a stored blank is the state a COBOL character field holds when nothing was
     * moved into it, so neither is an out-of-domain value. Only a value that is actually present and
     * actually different is refused.</p>
     *
     * @param candidate the value to check; may be {@code null}
     * @param domain the closed set of values the contract publishes for the component
     * @param component the component name, so a rejection names the member rather than only its value
     * @throws IllegalArgumentException if {@code candidate} is present, non-blank and not in
     *     {@code domain}
     */
    private static void requireWithinDomain(String candidate, List<String> domain, String component) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        if (!domain.contains(candidate)) {
            throw new IllegalArgumentException(component + " must be one of " + domain
                    + ", blank, or absent, because the published contract closes that domain, but was "
                    + candidate);
        }
    }
}
