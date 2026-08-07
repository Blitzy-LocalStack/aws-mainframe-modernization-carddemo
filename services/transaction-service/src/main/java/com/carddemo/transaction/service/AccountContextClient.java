package com.carddemo.transaction.service;

import com.carddemo.common.money.Money;
import java.util.Optional;

/**
 * The outbound seam through which this context reads and updates account-owned records.
 *
 * <p>Purpose: two of the four migrated programs read records this context does not own. Both
 * {@code READ-CXACAIX-FILE} at line 576 of {@code app/cbl/COTRN02C.cbl} and the paragraph of the same
 * name at line 408 of {@code app/cbl/COBIL00C.cbl} resolve a card number through the cross-reference,
 * and the payment program additionally reads the account master at line 355 and rewrites its balance at
 * line 452. Under schema-per-service those three records belong to the account context, so this
 * interface is the only way this context reaches them.</p>
 *
 * <p>Alternatives Considered: granting this context read and write access to the account schema, the way
 * the posting job is granted it for its three-write unit of work. It was rejected because that grant is
 * justified by atomicity -- the posting commit genuinely spans two schemas and cannot be split without
 * making a partial post observable -- and neither of these two online screens has that property. The
 * capture screen writes one row in one schema; the payment screen writes one row here and asks the
 * account context to apply one balance change, and the reference itself performs those as two separate
 * file operations with a commit between them at line 470.</p>
 *
 * <p>Alternatives Considered: publishing an event and letting the account context apply the balance
 * change asynchronously. Rejected because the reference answers the operator with the balance already
 * changed, so an asynchronous application would either report a balance that is not yet true or make the
 * screen wait for a callback, and the second is a synchronous call written the long way.</p>
 *
 * <p>Assumptions: absence and failure are different answers here and are reported differently. A record
 * that does not exist is an empty optional, because the reference distinguishes a not-found status from
 * any other and answers each with its own sentence; anything else is
 * {@link AccountContextUnavailableException}, which the calling service converts into the sentence its
 * own screen emits. No implementation selects an operator sentence, so transformation rule T8 keeps
 * every user-visible string inside the service layer.</p>
 */
public interface AccountContextClient {

    /**
     * Resolves the cross-reference entry a card number is keyed by.
     *
     * @param cardNumber the sixteen digit card number as submitted, never {@code null}
     * @return the entry when one exists, otherwise an empty optional
     * @throws AccountContextUnavailableException if the account context could not be reached or answered
     *     with anything other than a record or a documented absence
     */
    Optional<CardXref> findCardXrefByCardNumber(String cardNumber);

    /**
     * Resolves the cross-reference entry an account identifier is keyed by.
     *
     * @param accountId the eleven digit account identifier as submitted, never {@code null}
     * @return the entry when one exists, otherwise an empty optional
     * @throws AccountContextUnavailableException if the account context could not be reached or answered
     *     with anything other than a record or a documented absence
     */
    Optional<CardXref> findCardXrefByAccountId(String accountId);

    /**
     * Reads the current balance of an account master record.
     *
     * @param accountId the eleven digit account identifier as submitted, never {@code null}
     * @return the balance when the account exists, otherwise an empty optional
     * @throws AccountContextUnavailableException if the account context could not be reached or answered
     *     with anything other than a record or a documented absence
     */
    Optional<AccountBalance> findAccountBalance(String accountId);

    /**
     * Applies a payment to an account master record, reducing its balance by the amount paid.
     *
     * <p>Assumptions: the amount is the balance as it stood when it was read, so this is the reference's
     * pay-in-full operation at lines 449 to 452 of {@code app/cbl/COBIL00C.cbl} and not a partial
     * payment. Sending the amount rather than the resulting balance is what lets the account context
     * refuse the change if the balance moved underneath, which is the contention the reference cannot
     * detect at all because it holds no version.</p>
     *
     * @param accountId the eleven digit account identifier as submitted, never {@code null}
     * @param paymentAmount the amount to reduce the balance by, never {@code null}
     * @throws AccountContextUnavailableException if the account context could not be reached or refused
     *     the change
     */
    void applyPayment(String accountId, Money paymentAmount);

    /**
     * The cross-reference entry joining a card number to the account it belongs to.
     *
     * @param accountId the account identifier the card belongs to, as digit characters so a leading zero
     *     survives, never {@code null}
     * @param cardNumber the card number the entry is keyed by, as digit characters, never {@code null}
     */
    record CardXref(String accountId, String cardNumber) {
    }

    /**
     * The balance an account master record currently carries.
     *
     * @param accountId the account identifier, as digit characters, never {@code null}
     * @param currentBalance the exact fixed-point balance at scale two, never {@code null}
     */
    record AccountBalance(String accountId, Money currentBalance) {
    }

    /**
     * Signals that the account context could not answer, as distinct from answering that a record is
     * absent.
     *
     * <p>Assumptions: this type is nested inside the interface that raises it rather than declared
     * separately, so the seam is one compilation unit and a caller cannot import the failure without
     * importing the contract that defines when it is raised. It carries no message of its own that any
     * response renders: the calling service selects the reference sentence for the operation it was
     * performing, because the reference emits a different one for each read.</p>
     */
    class AccountContextUnavailableException extends RuntimeException {

        /** Serialisation identity, fixed because this type is never serialised across versions. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the signal, recording what was attempted and the transport failure beneath it.
         *
         * @param detail a redacted description of the attempted read, naming the operation and never
         *     reproducing an account identifier or a card number; must not be {@code null}
         * @param cause the transport or protocol failure that prevented an answer, or {@code null} when
         *     the context answered but unusably
         */
        public AccountContextUnavailableException(String detail, Throwable cause) {
            super(detail, cause);
        }
    }
}
