package com.carddemo.transaction.service;

import java.util.Optional;

/**
 * The outbound seam through which this context reads the account-owned card cross-reference.
 *
 * <p>Purpose: two of the four migrated programs resolve a card number through a record this context does
 * not own. {@code READ-CXACAIX-FILE} at line 576 of {@code app/cbl/COTRN02C.cbl} and the paragraph of the
 * same name at line 408 of {@code app/cbl/COBIL00C.cbl} both read the card cross-reference, which under
 * schema-per-service belongs to the account context -- so this interface is the only way this context
 * reaches it. It declares TWO members and both are reads.</p>
 *
 * <p>Refactoring Rationale: this seam formerly also carried the account balance read and the balance
 * change, and it no longer carries either. Those two moved onto
 * {@link com.carddemo.transaction.repository.AccountBalanceRepository}, which issues them on the caller's
 * own connection under a named cross-schema grant, because lines 233 and 235 of
 * {@code app/cbl/COBIL00C.cbl} sit in ONE CICS task with no commit verb between them. A remote write
 * cannot enlist in a local transaction, so keeping it here left two states the baseline cannot produce: an
 * account debited with no payment recorded, and a payment recorded that was never settled. An earlier
 * revision of this paragraph rejected the grant on the grounds that the payment screen had no atomicity
 * requirement and that the reference committed between its two file operations; both claims were wrong,
 * and the commit it cited at line 470 belongs to a different program.</p>
 *
 * <p>Assumptions: what remains on this seam is a read whose answer this context decides on, and it has no
 * atomicity requirement at all -- a stale cross-reference cannot corrupt anything, because the row it
 * keys is written afterwards inside the transaction. That asymmetry is why one crossing moved and the
 * other did not.</p>
 *
 * <p>Alternatives Considered: publishing an event and letting the account context apply the balance
 * change asynchronously. Rejected before the local write was adopted and still rejected: the reference
 * answers the operator with the balance already changed, so an asynchronous application would either
 * report a balance that is not yet true or make the screen wait for a callback, and the second is a
 * synchronous call written the long way.</p>
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
     * The cross-reference entry joining a card number to the account it belongs to.
     *
     * @param accountId the account identifier the card belongs to, as digit characters so a leading zero
     *     survives, never {@code null}
     * @param cardNumber the card number the entry is keyed by, as digit characters, never {@code null}
     */
    record CardXref(String accountId, String cardNumber) {
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
