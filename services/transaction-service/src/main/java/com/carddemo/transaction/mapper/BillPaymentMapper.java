package com.carddemo.transaction.mapper;

import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Converts between the bill-payment request and response shapes and the ledger row the payment
 * appends, for the screen migrated from {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>Purpose: bill payment is the one write path in this module that composes a transaction record
 * entirely from constants and from one figure read elsewhere, rather than from operator input. The
 * reference program accepts an account identifier and a single confirmation character and then
 * fills eleven of the thirteen record fields with literals at
 * {@code app/cbl/COBIL00C.cbl:219-230}. This class is where those eleven literals live, so that a
 * reader looking for the payment's shape finds one place holding all of it instead of eleven
 * assignments distributed through a service method.
 *
 * <p>Assumptions: the amount paid is the account balance as it stood BEFORE the payment, and
 * statement order in the reference is what fixes that. Line 194 moves {@code ACCT-CURR-BAL} onto
 * the screen field, line 224 moves the same untouched value into {@code TRAN-AMT}, line 233 writes
 * the record, and only line 234 computes
 * {@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}. Nothing refills the screen field between line
 * 194 and the send at line 242. Both the appended row's amount and the response's reported balance
 * are therefore the pre-payment figure, and this class takes it as one argument used twice rather
 * than deriving it twice, so the two can not diverge.
 *
 * <p>Alternatives Considered: folding these conversions into {@link TransactionMapper}, which
 * already owns {@code Transaction} construction for the three transaction screens. Rejected
 * because that class's {@code toEntity} derives eleven of its thirteen fields from operator input
 * and both timestamps from submitted dates, whereas this one derives eleven from literals and both
 * timestamps from one supplied instant. A single method serving both would need a branch on which
 * screen called it, and the literals recorded here would then sit inside a method whose contract is
 * about a different screen. Splitting them keeps each method's Javadoc able to state one screen's
 * provenance without qualification.
 *
 * <p>Alternatives Considered: generating this mapper. Rejected for the reason recorded on this
 * package's charter and repeated at the point of use because it is load-bearing here: eleven of the
 * thirteen field values are literals whose only justification is a cited reference line, and a
 * generated mapper has nowhere to carry that citation. The value {@code 999999999} in particular is
 * indistinguishable from an accident unless the line that assigns it is named.
 *
 * <p>Assumptions: this class reads no clock. The reference calls
 * {@code GET-CURRENT-TIMESTAMP} at line 231 and moves one value into both {@code TRAN-ORIG-TS} and
 * {@code TRAN-PROC-TS}, so the two are equal by construction rather than by coincidence. Taking the
 * instant as an argument preserves that equality while leaving the reading of it to the caller,
 * which is what lets a test assert the 26-character rendering against a fixed value. A clock read
 * here would make the appended row unassertable without also making this class mockable.
 *
 * <p>Trade-offs: the response reports only the pre-payment balance, matching the screen, and no
 * component carries the balance line 234 leaves behind. That asymmetry is inherited from
 * {@link BillPaymentResponse} rather than decided here; publishing a post-payment figure would show
 * a value this screen never displayed, which is a behavioural change rather than a structural one.
 *
 * <p>Assumptions: nothing is masked in this direction. {@link BillPaymentResponse} declares four
 * components and none of them is a card number, so the primary-account-number masking this package
 * applies on the transaction detail view has no subject here. The card number reached through the
 * cross-reference is written into the ledger row, where it is storage rather than a response, and
 * is therefore carried in full.
 */
@Component
public class BillPaymentMapper {

    /**
     * The transaction type code every bill payment carries.
     *
     * <p>Assumptions: the value is the two-character literal {@code '02'} moved at
     * {@code app/cbl/COBIL00C.cbl:220}, and it is declared as text rather than as a number because
     * {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:6} is a character field. A
     * numeric two would render as {@code "2"} and match no reference-data row.
     */
    public static final String TRANSACTION_TYPE_CODE = "02";

    /**
     * The transaction category code every bill payment carries.
     *
     * <p>Assumptions: the reference moves the numeric literal {@code 2} at
     * {@code app/cbl/COBIL00C.cbl:221} into {@code TRAN-CAT-CD PIC 9(04)} declared at
     * {@code app/cpy/CVTRA05Y.cpy:7}, and a four-digit numeric field receives it right-aligned and
     * zero-filled, so the stored characters are {@code 0002}. The zeros are written out here
     * because the column is a fixed-width character type and a two-character value would not
     * satisfy the foreign key into the reference categories.
     */
    public static final String TRANSACTION_CATEGORY_CODE = "0002";

    /**
     * The transaction source every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at {@code app/cbl/COBIL00C.cbl:222} and is eight
     * characters against a {@code TRAN-SOURCE PIC X(10)} field, so the reference stores it with two
     * trailing spaces. Those spaces are padding contributed by the fixed-width column rather than
     * data, so the logical value is carried here and the column supplies the width, exactly as
     * {@link TransactionMapper} does for the operator-supplied source.
     */
    public static final String TRANSACTION_SOURCE = "POS TERM";

    /**
     * The transaction description every bill payment carries.
     *
     * <p>Assumptions: reproduced character for character from the literal at
     * {@code app/cbl/COBIL00C.cbl:223}, including the spaced hyphen. This string is visible on a
     * statement and in a transaction list, so re-wording, re-casing or re-spacing it would change
     * what an operator reads.
     */
    public static final String TRANSACTION_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /**
     * The merchant identifier every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at {@code app/cbl/COBIL00C.cbl:226} into
     * {@code TRAN-MERCHANT-ID PIC 9(09)} declared at {@code app/cpy/CVTRA05Y.cpy:11}, and nine
     * nines is the largest value that field can hold. It is a reserved sentinel standing for "no
     * merchant", not a real merchant, which is why a bill payment can not be attributed to one.
     */
    public static final long MERCHANT_ID = 999999999L;

    /**
     * The merchant name every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at {@code app/cbl/COBIL00C.cbl:227}. It is the display
     * partner of {@link #MERCHANT_ID}: because the identifier is a sentinel rather than a key, no
     * lookup can supply a name, so the reference supplies one directly.
     */
    public static final String MERCHANT_NAME = "BILL PAYMENT";

    /**
     * The merchant city every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at {@code app/cbl/COBIL00C.cbl:228}. The reference writes
     * the three characters {@code N/A} rather than leaving the field blank, and the two states are
     * distinguishable in a fixed-width record, so blanking it here would alter the stored bytes.
     */
    public static final String MERCHANT_CITY = "N/A";

    /**
     * The merchant postal code every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at {@code app/cbl/COBIL00C.cbl:229}, on the same footing
     * as {@link #MERCHANT_CITY}: an explicit not-applicable marker rather than an absent value.
     */
    public static final String MERCHANT_ZIP = "N/A";

    /**
     * The declared width of the transaction identifier and of the card number.
     *
     * <p>Assumptions: {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:5} and
     * {@code TRAN-CARD-NUM PIC X(16)} at line 15 are both sixteen characters, and both are stored
     * as fixed-width character columns. A value shorter than the width is not a partial identifier
     * but one that matches no row, which is why both are left-padded here rather than trimmed.
     */
    public static final int IDENTIFIER_WIDTH = 16;

    /**
     * Builds the ledger row a confirmed bill payment appends.
     *
     * <p>Assumptions: the amount is the balance as it stood before the payment, supplied by the
     * caller. The reference reaches it through the account record it has already read, and this
     * class can not re-read it without becoming a repository client, so the figure arrives as an
     * argument. The caller is also the party that subtracts it afterwards, which keeps the write and
     * the balance adjustment inside one transaction as
     * {@code app/cbl/COBIL00C.cbl:233-235} does.
     *
     * <p>Assumptions: the identifier is minted by the caller and never accepted from a client. The
     * reference derives it at lines 212 to 218 by browsing the transaction file backwards from high
     * values, reading the last identifier and adding one, and those verbs hold no lock, so two
     * concurrent payments can derive the same next value. Keeping the mint in the service is what
     * lets the collision be handled where the transaction boundary is.
     *
     * <p>Assumptions: the account identifier on the request does not appear on the row. The
     * reference resolves the card number from the cross-reference at line 210 and stores that,
     * because {@code TRAN-RECORD} has no account field at all; the account is reachable only
     * through the card. The request is therefore read for nothing but its presence, and the caller
     * supplies the resolved card number.
     *
     * @param request the confirmed submission, read for its presence and for the account it
     *     identifies; must not be {@code null}
     * @param transactionId the identifier the caller minted for this payment, as digits, which is
     *     left-padded with zeros to the declared width; must not be {@code null}
     * @param resolvedCardNumber the card number the caller obtained from the cross-reference, as
     *     digits, which is left-padded with zeros to the declared width; must not be {@code null}
     * @param currentBalance the account balance before the payment, which becomes the transaction
     *     amount; must not be {@code null}
     * @param timestamp the instant the caller captured, written to both the originating and the
     *     processing timestamp so the two are equal as they are in the reference; must not be
     *     {@code null}
     * @return a row carrying the eleven bill-payment literals, the supplied amount, the supplied
     *     card number and both timestamps set to the supplied instant
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the identifier or the card number is empty, contains a
     *     character other than a digit, or is longer than the declared width
     */
    public Transaction toEntity(BillPaymentRequest request, String transactionId,
            String resolvedCardNumber, Money currentBalance, LocalDateTime timestamp) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(currentBalance, "currentBalance must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        // WHY : Assumptions: one instant is written to both timestamp fields rather than two reads
        //       taken. app/cbl/COBIL00C.cbl:231-232 performs GET-CURRENT-TIMESTAMP once and moves
        //       WS-TIMESTAMP to TRAN-ORIG-TS and TRAN-PROC-TS in a single statement, so equality is
        //       a property of the record and not an artefact of two reads landing in the same
        //       microsecond. Two separate reads here would be equal almost always and unequal
        //       occasionally, which is the worst of the available behaviours.
        return new Transaction(
                zeroPaddedDigits(transactionId, IDENTIFIER_WIDTH, "transactionId"),
                TRANSACTION_TYPE_CODE,
                TRANSACTION_CATEGORY_CODE,
                TRANSACTION_SOURCE,
                TRANSACTION_DESCRIPTION,
                currentBalance.amount(),
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                zeroPaddedDigits(resolvedCardNumber, IDENTIFIER_WIDTH, "resolvedCardNumber"),
                timestamp,
                timestamp);
    }

    /**
     * Converts an appended bill payment into the acknowledgement the screen shows.
     *
     * <p>Assumptions: the balance reported is read from the row's amount rather than taken again
     * from the account, and the two are the same figure by construction because
     * {@link #toEntity} wrote the pre-payment balance into the amount. Reading it back from the row
     * is what makes the acknowledgement describe the record that was actually persisted rather than
     * a value held alongside it.
     *
     * <p>Assumptions: the account identifier is echoed from the request. It is not on the row, as
     * recorded on {@link #toEntity}, and the screen field it populates is the one the operator
     * keyed, so echoing the submitted value reproduces what the reference redisplays at
     * {@code app/cbl/COBIL00C.cbl:242}.
     *
     * @param request the submission whose account identifier is echoed back; must not be
     *     {@code null}
     * @param transaction the appended row, whose identifier and amount must both be present; must
     *     not be {@code null}
     * @param returnMessage the confirmation text accompanying the payment, or {@code null} when
     *     there is none; any spelling of absence is collapsed onto {@code null}
     * @return the acknowledgement carrying the account identifier, the pre-payment balance, the
     *     minted transaction identifier, the fixed paid discriminator and the message
     * @throws NullPointerException if either the request or the row is {@code null}, or if the row
     *     carries no transaction identifier or no amount
     * @throws ArithmeticException if the stored amount cannot be reduced to the two decimal places
     *     the money contract carries
     */
    public BillPaymentResponse toResponse(BillPaymentRequest request, Transaction transaction,
            String returnMessage) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");
        // WHY : Assumptions: the acknowledgement is built through the record's own posted(...)
        //       factory rather than through its canonical constructor, because the shape carries a
        //       paid discriminator whose only correct value on this path is true. Constructing it
        //       directly would put that value at the call site, where a future caller could pass
        //       false and publish a body that says no payment was made about a transaction row that
        //       exists. The factory fixes it, so this mapper cannot express that state at all.
        return BillPaymentResponse.posted(
                request.accountId(),
                requiredAmount(transaction.getTranAmt()),
                Objects.requireNonNull(transaction.getTranId(),
                        "tranId must not be null: it is this row's primary key"),
                absentWhenNeverSupplied(returnMessage));
    }

    /**
     * Wraps a stored decimal amount in the exact money type the response component declares.
     *
     * <p>Assumptions: an absent amount is refused rather than reported as absent. The column is
     * declared without a not-null constraint, yet the reference record has no absent state for it:
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10} occupies eleven bytes of
     * every 350-byte record and a zoned-decimal field always decodes to a number. An absent amount
     * is therefore a defect in the row rather than a state to report, and refusing here names it.
     *
     * @param storedAmount the value read from the amount column; must not be {@code null}
     * @return the same value as an exact amount at two decimal places
     * @throws NullPointerException if {@code storedAmount} is {@code null}
     * @throws ArithmeticException if the value declares a scale or a magnitude the money contract
     *     cannot reduce to two decimal places
     */
    private static Money requiredAmount(BigDecimal storedAmount) {
        return Money.of(Objects.requireNonNull(storedAmount,
                "tranAmt must not be null: the reference record has no absent amount, so an absent"
                        + " value is a defect in the row rather than a state to report"));
    }

    /**
     * Collapses every spelling of an absent message onto {@code null}, leaving a present one
     * untouched.
     *
     * <p>Assumptions: the low-values condition at {@code app/cpy/CVCRD01Y.cpy:30} is nested under
     * {@code CCARD-RETURN-MSG} at line 29 and under that field alone, so an unset return message
     * has a sentinel and maps to no value. A message read out of a field of declared width arrives
     * padded, so the shared absence test folds a null, an empty value, a run of spaces and a run of
     * low values alike.
     *
     * <p>Trade-offs: the same collapse is written here and in {@link TransactionMapper} rather than
     * promoted to a shared helper. Both call one predicate that already lives in
     * {@code common-lib}, so what is duplicated is a single expression, and hoisting it would put a
     * message-absence decision in a class whose subject is transaction conversion, where the
     * copybook line that justifies it would no longer sit beside the field it governs.
     *
     * @param message the message as supplied, which may be {@code null}, empty, blank or genuine
     *     text
     * @return {@code null} when the value is any spelling of absence, and otherwise the value
     *     unchanged
     */
    private static String absentWhenNeverSupplied(String message) {
        return FieldValidationFlag.isNeverSupplied(message) ? null : message;
    }

    /**
     * Left-pads a run of digits with zeros to a fixed width, refusing anything that is not digits.
     *
     * <p>Assumptions: both identifiers this method guards are stored as fixed-width character
     * columns, so a shorter value is not a partial key but one that matches no row. The committed
     * extracts are zero-padded, which is why zero is the pad character and why padding happens on
     * the left.
     *
     * <p>Trade-offs: a value longer than the width is refused rather than truncated. Truncating a
     * key silently addresses a different row, and there is no rendering in which that is preferable
     * to a refusal that names the value.
     *
     * @param value the digits to pad; must not be {@code null}
     * @param width the declared width to pad to
     * @param component the argument name reproduced in a refusal so that it names the value that
     *     failed; must not be {@code null}
     * @return the value left-padded with zeros to the declared width
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is empty, contains a character other than a
     *     digit, or is longer than the declared width
     */
    private static String zeroPaddedDigits(String value, int width, String component) {
        Objects.requireNonNull(value, component + " must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(component + " must not be empty");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(component + " must be at most " + width
                    + " characters, because the stored column is that wide");
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                throw new IllegalArgumentException(component
                        + " must contain digits only before it is used as a key");
            }
        }
        return "0".repeat(width - value.length()) + value;
    }
}
