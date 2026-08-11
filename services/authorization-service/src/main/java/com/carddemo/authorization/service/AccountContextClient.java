package com.carddemo.authorization.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The port through which this context reads the account data it does not own.
 *
 * <p><b>Purpose.</b> An authorization decision needs three records this bounded context has no table
 * for: the card cross-reference that resolves a card number to an account and a customer, the account
 * master that carries the limits and the posted balance, and the customer master whose mere existence
 * the baseline tests. The reference program reads all three directly, at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} paragraph
 * {@code 5100-READ-XREF-RECORD} on lines 472 to 516, {@code 5200-READ-ACCT-RECORD} on lines 520 to
 * 564 and {@code 5300-READ-CUST-RECORD} on lines 568 to 612. In the target those records belong to
 * the account context, so this interface is the seam: it states exactly what this context needs,
 * names none of the other context's types, and is implemented by an adapter that speaks that
 * context's published contract.</p>
 *
 * <p>Refactoring Rationale: this interface exists because the alternative in place before it was
 * worse than a missing lookup. The listener resolved a card to an account by reading the account
 * recorded on that card's OWN PREVIOUS authorizations -- a substitute that cannot work for the case
 * that matters most, the first authorization a card ever presents, and that resolves to a stale
 * account after a card is reissued. Both failures decline a request the baseline approves, which is a
 * parity break rather than a conservative default. The substitute query and its rationale are removed
 * together with this interface's arrival, so no path is left that could quietly prefer it.</p>
 *
 * <p>Alternatives Considered: giving this context read grants on the account context's tables and
 * mapping its records as entities here. Rejected because it would put two owners on one schema and
 * would oblige this service's ArchUnit boundary test to admit a cross-context domain import that the
 * migration's layering rules forbid outright. The one place the migration accepts a scoped
 * cross-schema grant is the posting unit of work, which is atomic and cannot be split; an
 * authorization read is neither.</p>
 *
 * <p>Alternatives Considered: publishing the three reads as asynchronous messages, matching the
 * transport this context's own request already arrives on. Rejected because a decision cannot be made
 * without them: the consumer would have to suspend a message mid-transaction and resume it on a
 * reply, which turns one unit of work into a saga and introduces the observable intermediate states
 * the migration's decision on transaction posting rejected for the same reason. A bounded synchronous
 * read inside the private network is the lower-risk shape.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> An interface
 * declaration takes no parameters, returns no value and raises nothing; the four operations, the three
 * views and the one failure type below carry their own at-clauses. The inapplicability is declared
 * rather than left silent so a reader can tell it from an omission.</p>
 *
 * <p>Assumptions: three records is the WHOLE of what this context borrows, and that is a counted
 * result rather than an expectation. Every {@code COPY} statement in
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} was enumerated: sixteen statements, of
 * which eight name the six distinct absent vendor message-interface books -- two of them are copied
 * twice, at lines 149 and 155 and at lines 152 and 158 -- five name this context's own staging and
 * segment layouts, and exactly three name a layout another context owns: {@code CVACT03Y} at line 203,
 * {@code CVACT01Y} at line 206 and {@code CVCUS01Y} at line 209. Eight plus five plus three closes on
 * sixteen with nothing left over, so the enumeration is exhaustive and this interface is COMPLETE:
 * there is no fourth borrowed record that a decision silently needs and this seam does not name. That
 * matters because an incomplete seam does not fail loudly -- it fails as a decline the reference
 * program would have approved, which is the failure mode the substitute lookup this interface replaced
 * already exhibited.</p>
 *
 * <p>Assumptions: every operation answers with an empty optional for a record that does not exist,
 * and raises only for a failure to ask. That split is what lets the decision logic treat
 * not-found as the baseline treats it -- a decline with reason {@code '3100'} at
 * {@code COPAUA0C.cbl} line 704 -- while a transport failure propagates and lets the queue redeliver
 * the request rather than answering it with a decline the account might not deserve.</p>
 */
public interface AccountContextClient {

    /**
     * Resolves a card number to the account and customer it belongs to.
     *
     * <p>Assumptions: this is the migrated form of {@code 5100-READ-XREF-RECORD}, which reads the
     * cross-reference file keyed by the card number and sets {@code CARD-NFOUND-XREF} when the read
     * misses, at {@code COPAUA0C.cbl} lines 489 to 492. A miss here is therefore the same observable
     * state as that flag, and the caller maps it to the same decline reason.</p>
     *
     * @param cardNum the sixteen-character primary account number the request carried; must not be
     *     {@code null}
     * @return the account and customer the card maps to, or an empty optional when the card is not
     *     cross-referenced
     * @throws AccountContextUnavailableException if the account context could not be asked
     */
    Optional<CardXref> findCardXref(String cardNum);

    /**
     * Reads the account master record an authorization is decided against.
     *
     * <p>Assumptions: this is the migrated form of {@code 5200-READ-ACCT-RECORD}, whose result the
     * decision paragraph uses as its FALLBACK limit source when no summary segment exists yet, at
     * {@code COPAUA0C.cbl} lines 673 to 679. The limits and the posted balance are therefore both
     * required by the caller, and the view below carries both.</p>
     *
     * @param accountId the eleven-digit account identifier resolved from the cross-reference
     * @return the account's limits, balance and status, or an empty optional when the account master
     *     holds no such row
     * @throws AccountContextUnavailableException if the account context could not be asked
     */
    Optional<Account> findAccount(long accountId);

    /**
     * Reports whether the customer master holds the customer a card is cross-referenced to.
     *
     * <p>Assumptions: the baseline reads the whole customer record at {@code 5300-READ-CUST-RECORD}
     * and then uses NONE of its fields in the decision -- it sets {@code NFOUND-CUST-IN-MSTR} on a
     * miss at {@code COPAUA0C.cbl} line 587 and that flag is read only by the reason table at line
     * 703. Existence is therefore the whole of what this context needs, and returning a record would
     * carry customer data across a boundary for no consumer.</p>
     *
     * @param customerId the nine-digit customer identifier resolved from the cross-reference
     * @return {@code true} when the customer master holds the row, {@code false} when it does not
     * @throws AccountContextUnavailableException if the account context could not be asked
     */
    boolean customerExists(long customerId);

    /**
     * Reads the customer display fields the pending-authorization summary screen shows.
     *
     * <p>Refactoring Rationale: this operation exists because the summary screen was publishing the
     * segment's own identifiers and totals and NOTHING ELSE, while the record it publishes into declares
     * a customer name, two address lines and a telephone number. The reference program composes all four
     * from {@code GETCUSTDATA-BYCUST} at {@code cbl/COPAUS0C.cbl} L920, so omitting them left the screen
     * short of four fields the baseline showed -- a functional-parity gap rather than a design choice,
     * even though this context correctly declines to own the customer record itself.</p>
     *
     * <p>Assumptions: this is ONE call per screen, not one per row. Every authorization beneath a summary
     * belongs to the same account and therefore the same customer, so the fields are read once for the
     * whole page. Alternatives Considered: reading them per list row, which would make the screen's cost
     * grow with the page size for data that is identical on every row; and denormalising them into this
     * context's own summary table, which was rejected because the customer record belongs to the account
     * context and a copy here would go stale with no owner responsible for it.</p>
     *
     * <p>Assumptions: an ABSENT customer returns an empty optional rather than raising, and the caller
     * publishes blanks. That mirrors the reference, whose own read has a not-found arm that leaves the
     * screen fields unfilled and continues -- the authorization totals are what the screen is for, and
     * refusing the whole screen because a display name could not be resolved would withdraw information
     * the operator can act on over information they cannot.</p>
     *
     * <p>Assumptions: a transport FAILURE still raises, and the distinction from absence is deliberate. A
     * customer that does not exist is an answer; a customer that could not be reached is not, and
     * rendering blanks for it would present an unreachable dependency as an empty record.</p>
     *
     * @param customerId the customer the summary's segment names
     * @return the display fields, or an empty optional when no such customer exists
     * @throws AccountContextUnavailableException if the account context cannot be reached
     */
    Optional<CustomerDisplay> customerDisplay(long customerId);

    /**
     * The four customer fields the summary screen renders, and nothing else.
     *
     * <p>Assumptions: this record carries exactly what the screen shows and no more, which is the point of
     * it being declared here rather than being the account context's whole customer record. The customer
     * master holds a national identifier, a government-issued identifier and a credit score; none of the
     * three is on this screen, so none of the three crosses this seam. Narrowing the contract is what
     * keeps the exposure narrow -- an interface that returned the whole record would make widening the
     * screen a matter of nobody's decision.</p>
     *
     * @param customerName the customer's name as the screen displays it, or {@code null} when unset
     * @param addressLine1 the first address line, or {@code null} when unset
     * @param addressLine2 the second address line, or {@code null} when unset
     * @param phoneNumber1 the customer's first telephone number, or {@code null} when unset
     */
    record CustomerDisplay(String customerName, String addressLine1, String addressLine2,
            String phoneNumber1) {
    }

    /**
     * The cross-reference row that resolves a card to an account and a customer.
     *
     * <p>Assumptions: only the two identifiers are carried, because the cross-reference record holds
     * only three fields -- the card number is what was asked with. The layout is
     * {@code app/cpy/CVACT03Y.cpy} lines 4 to 8, fifty bytes.</p>
     *
     * @param accountId the account the card belongs to, from {@code XREF-ACCT-ID}
     * @param customerId the customer the card belongs to, from {@code XREF-CUST-ID}
     */
    record CardXref(long accountId, long customerId) {
    }

    /**
     * The account fields an authorization decision reads.
     *
     * <p>Assumptions: three fields and no more, and each one has a named reader. The credit limit and
     * the posted balance are the fallback available-amount computation at {@code COPAUA0C.cbl} lines
     * 674 and 675; the cash credit limit is what the summary row this context maintains stores at that
     * program's line 811. Nothing else is carried, and in particular the account's active status is
     * NOT: the baseline declares a decline reason for a closed account and never sets it, so a status
     * carried here could only be either ignored or used to decline a request the reference program
     * approves. Rule T9 of the migration plan settles which of those two is wrong, so the field is
     * absent rather than present and unread. No cardholder field is carried either -- a name or an
     * address would cross the boundary with no consumer on this side.</p>
     *
     * @param creditLimit the account's credit limit, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}; never
     *     {@code null}
     * @param cashCreditLimit the account's cash credit limit, {@code ACCT-CASH-CREDIT-LIMIT}; never
     *     {@code null}
     * @param currentBalance the account's posted balance, {@code ACCT-CURR-BAL}; never {@code null}
     */
    record Account(BigDecimal creditLimit, BigDecimal cashCreditLimit, BigDecimal currentBalance) {
    }

    /**
     * Reports that the account context could not be asked, as distinct from answering not-found.
     *
     * <p>Assumptions: this is deliberately UNCHECKED and deliberately distinct from an empty optional.
     * A caller must not be able to confuse "this card is not cross-referenced", which is a decision
     * input, with "the cross-reference could not be read", which is not -- the first produces a
     * decline the requester can act on and the second must let the queue redeliver the request so it
     * is answered once the dependency recovers. Declining on a transport failure would tell a
     * cardholder their card is unknown because a network call timed out.</p>
     */
    class AccountContextUnavailableException extends RuntimeException {

        /**
         * The serialisation identifier, pinned so a serialised instance stays readable across builds.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception describing a failed attempt to read the account context.
         *
         * @param message what was being read and how the attempt failed; never carries a card number,
         *     because this message reaches a log
         * @param cause the transport or decoding failure underneath, which may be {@code null}
         */
        public AccountContextUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Creates an exception describing a successful response this contract cannot be built from.
         *
         * <p>Refactoring Rationale: this second form exists because the class previously admitted only a
         * caused failure, on the reading that every unavailability had a transport exception underneath.
         * That reading missed the case this constructor is for: a dependency that answers 2xx with an
         * absent or incomplete body has thrown nothing at all, so there is no cause to pass -- and the
         * only alternative available to a caller was to report the answer as not-found, which turns a
         * broken contract into a committed wrong decision on the cardholder's account.</p>
         *
         * <p>Alternatives Considered: passing {@code null} as the cause to the two-argument form.
         * Rejected because it reads as an omission at every call site, so a reader cannot tell a
         * deliberate absence of cause from a forgotten one; a distinct constructor states which it is.
         * Alternatives Considered: synthesising a cause. Rejected because a fabricated stack trace points
         * at this file rather than at anything that failed.</p>
         *
         * @param message what was read and what the answer was missing; names components rather than
         *     values, because this message reaches a log
         */
        public AccountContextUnavailableException(String message) {
            super(message);
        }
    }
}
