package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
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
     * exist in a state the contract does not describe, whichever path constructed it. The match status
     * and the masked card number are re-checked through {@link PendingAuthRowView}'s own declared
     * domains rather than restated, so the list and the detail resources cannot come to disagree about
     * either.</p>
     *
     * @throws NullPointerException if any required component is {@code null}
     * @throws IllegalArgumentException if the selector is not a sealed token, if the match status is
     *     outside its four-value domain, or if the card number is not in the masked form the contract
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
        Objects.requireNonNull(cardNum, "cardNum is required");
        Objects.requireNonNull(transactionAmt, "transactionAmt is required");
        Objects.requireNonNull(approvedAmt, "approvedAmt is required");
        Objects.requireNonNull(transactionId, "transactionId is required");
        if (!PendingAuthRowView.MATCH_STATUSES.contains(matchStatus)) {
            throw new IllegalArgumentException("matchStatus must be one of "
                    + PendingAuthRowView.MATCH_STATUSES + " but was " + matchStatus);
        }
        if (cardNum.length() != PendingAuthRowView.MASKED_CARD_LENGTH
                || cardNum.charAt(0) != '*') {
            throw new IllegalArgumentException(
                    "cardNum must be published in the masked form the contract declares");
        }
    }
}
