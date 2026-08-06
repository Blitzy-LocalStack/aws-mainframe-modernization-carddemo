package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import java.util.List;
import java.util.Objects;

/**
 * One pending-authorization row on a page returned by the list operation.
 *
 * <h2>What this record is authoritative for</h2>
 *
 * <p>This record is the Java realisation of the {@code PendingAuthListItem} schema in
 * {@code src/main/resources/openapi/authorization-api.yaml}. Its components are named exactly as that
 * schema names its properties, and a page of these is what the {@code PendingAuthPage} schema carries
 * -- which is to say a {@code PageResponse} of this type, the four-component keyset envelope shared by
 * every list in this migration.
 *
 * <p>Refactoring Rationale: the reference screen holds five rows in five fixed field groups, and the
 * module's {@code PendingAuthSummaryResponse} reproduces that shape with components named
 * {@code row1TransactionId} through {@code row5ApprovedAmount}. A list whose page size is written into
 * its own component names cannot return four rows or six, and the keyset envelope this migration
 * requires has nowhere to live in it. One row type plus a page envelope expresses the reference
 * screen's five rows and every other page size with the same contract.
 *
 * <h2>Three of the nine components are derived rather than stored</h2>
 *
 * <p>Assumptions: {@code key} is not a stored column. It is the sealed selector a client sends back as
 * the path segment of the read and fraud operations, produced by {@link CursorToken} over the row's
 * three-part identity, and it is deliberately not the reference system's packed eight-byte key.
 *
 * <p>Assumptions: {@code approvalStatus} is derived and not stored.
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} tests the response code at L536 and moves
 * {@code 'A'} at L537 or {@code 'D'} at L539 into the screen field, so the character the row carries is
 * a function of the response code rather than a column of its own. The code it derives from is
 * published in full on the detail resource, so nothing is hidden by deriving it here.
 *
 * <p>Assumptions: {@code cardNum} is masked to its last four digits. The reference list does not show
 * a card number at all, so publishing the full sixteen would be a disclosure the baseline never made;
 * publishing the last four lets a client group rows by card without a second request.
 *
 * <h2>The amount is the approved amount, not the requested one</h2>
 *
 * <p>Assumptions: {@code amount} carries {@code PA-APPROVED-AMT} and not
 * {@code PA-TRANSACTION-AMT}. {@code cbl/COPAUS0C.cbl} L525 moves the approved amount into the edited
 * work field that L553 and its four siblings then move into the row, so the approved amount is what the
 * list displayed. The two differ on a declined authorization, where the approved amount is zero, and a
 * client comparing a list row against a detail resource would otherwise read the difference as an
 * inconsistency. Both amounts are published on the detail resource, so the requested amount remains
 * reachable in one further request.
 *
 * @param key the sealed selector addressing this row, never {@code null}
 * @param transactionId the acquirer's transaction identifier, never {@code null}
 * @param authOrigDate the six stored characters of the originating date, or {@code null} when the row
 *     carried none
 * @param authOrigTime the six stored characters of the originating time, or {@code null} when the row
 *     carried none
 * @param authType the four-character authorization type, or {@code null} when the row carried none
 * @param approvalStatus {@code A} when the response code was the approved value and {@code D}
 *     otherwise, never {@code null}
 * @param matchStatus one of the four one-character match statuses, never {@code null}
 * @param amount the amount approved for this authorization, never {@code null}
 * @param cardNum the card this authorization is against, masked to its last four digits, never
 *     {@code null}
 */
public record PendingAuthRowView(
        String key,
        String transactionId,
        String authOrigDate,
        String authOrigTime,
        String authType,
        String approvalStatus,
        String matchStatus,
        Money amount,
        String cardNum) {

    /**
     * The character published when the authorization was approved.
     *
     * <p>Assumptions: read from the literal moved at {@code cbl/COPAUS0C.cbl} L537, and it is the same
     * character the detail screen derives at {@code cbl/COPAUS1C.cbl} L312.</p>
     */
    public static final String APPROVAL_STATUS_APPROVED = "A";

    /**
     * The character published when the authorization was not approved.
     *
     * <p>Assumptions: read from the literal moved at {@code cbl/COPAUS0C.cbl} L539. It is reached on
     * every response code other than the approved one, including an absent code, because the reference
     * test is an equality against one value with an unconditional {@code ELSE}.</p>
     */
    public static final String APPROVAL_STATUS_DECLINED = "D";

    /**
     * The closed set of one-character match statuses this row may carry.
     *
     * <p>Assumptions: the four values are the check constraint on the persistent column, and the
     * contract publishes the same four as an enumeration. The set is declared here so the refusal below
     * and the contract cannot drift apart silently.</p>
     */
    public static final List<String> MATCH_STATUSES = List.of("P", "D", "E", "M");

    /**
     * The number of trailing digits a masked card number discloses.
     *
     * <p>Assumptions: matched by the contract's {@code MaskedCardNumber} schema, whose pattern is twelve
     * mask characters followed by exactly four digits.</p>
     */
    public static final int VISIBLE_CARD_DIGITS = 4;

    /**
     * The total length of a masked card number, sixteen characters.
     *
     * <p>Assumptions: masking preserves length, so a masked primary account number is as long as the
     * unmasked one. Sixteen is the declared width of the stored card number.</p>
     */
    public static final int MASKED_CARD_LENGTH = 16;

    /**
     * Validates every component the contract marks required and refuses a value outside its domain.
     *
     * <p>Refactoring Rationale: the domains are enforced here rather than only in the OpenAPI document,
     * because a response body is not validated against its own contract at runtime. A row assembled with
     * an unmasked card number or an out-of-domain match status would otherwise be published, and the
     * contract would describe a body the service does not send.</p>
     *
     * @throws NullPointerException if any required component is {@code null}
     * @throws IllegalArgumentException if the selector is not a sealed token, if the approval status is
     *     neither of its two characters, if the match status is outside its four-value domain, or if the
     *     card number is not in the masked form the contract publishes
     */
    public PendingAuthRowView {
        Objects.requireNonNull(key, "key is required");
        if (!CursorToken.hasSealedShape(key)) {
            throw new IllegalArgumentException(
                    "key must be a sealed cursor token, so that a row selector cannot be forged");
        }
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(amount, "amount is required");
        if (!APPROVAL_STATUS_APPROVED.equals(approvalStatus)
                && !APPROVAL_STATUS_DECLINED.equals(approvalStatus)) {
            throw new IllegalArgumentException("approvalStatus must be "
                    + APPROVAL_STATUS_APPROVED + " or " + APPROVAL_STATUS_DECLINED
                    + " but was " + approvalStatus);
        }
        if (!MATCH_STATUSES.contains(matchStatus)) {
            throw new IllegalArgumentException(
                    "matchStatus must be one of " + MATCH_STATUSES + " but was " + matchStatus);
        }
        requireMasked(cardNum);
    }

    /**
     * Refuses a card number that is absent or not in the masked form the contract publishes.
     *
     * <p>Assumptions: the check is on the SHAPE and not on a masking call having been made, because that
     * is what makes it a guard. A value that arrived here unmasked -- from a mapper that forgot the call
     * or from deserialisation of a hand-written body -- is refused on its digits rather than trusted.</p>
     *
     * @param candidate the value to check, which may be {@code null}
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not twelve mask characters followed by
     *     exactly four digits
     */
    private static void requireMasked(String candidate) {
        Objects.requireNonNull(candidate, "cardNum is required");
        if (candidate.length() != MASKED_CARD_LENGTH) {
            throw new IllegalArgumentException("cardNum must be exactly " + MASKED_CARD_LENGTH
                    + " characters but was " + candidate.length());
        }
        int maskedPrefix = MASKED_CARD_LENGTH - VISIBLE_CARD_DIGITS;
        for (int index = 0; index < maskedPrefix; index++) {
            if (candidate.charAt(index) != '*') {
                throw new IllegalArgumentException("cardNum must be masked: position " + (index + 1)
                        + " discloses a character the list contract withholds");
            }
        }
        for (int index = maskedPrefix; index < MASKED_CARD_LENGTH; index++) {
            char character = candidate.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        "cardNum must end in " + VISIBLE_CARD_DIGITS + " digits");
            }
        }
    }
}
