package com.carddemo.transaction.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays an account balance in full, migrated from {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>Purpose: this is the target of the reference program's payment screen. It evaluates the submission in
 * the reference's own order -- account identifier, then confirmation, then balance -- writes one payment
 * transaction for the whole outstanding balance, and asks the account context to reduce the balance by
 * that amount.</p>
 *
 * <p>Assumptions: the evaluation order is load-bearing and is the order the reference states. A blank
 * account identifier is caught at line 159 and answered at line 161 before the confirmation is examined
 * at line 173 at all; the balance test at lines 197 and 198 runs after the account has been read; and the
 * write happens only in the affirmative branch at line 210. Reordering any pair changes which sentence an
 * operator sees for a submission that is deficient in more than one way.</p>
 *
 * <p>Assumptions: the four confirmation branches are all distinguishable and one of them is unlike the add
 * screen's. The affirmative branch reads and pays; the negative branch clears the screen at line 180 and
 * sets the error flag at line 181, which abandons the turn without a message; a never-supplied
 * confirmation still reads the account, so the balance is reported, and is then answered by the prompt at
 * line 237; and anything else is the complaint at line 186. The add screen shares its negative, blank and
 * low-value branches under one prompt, which is why the two evaluations are not shared.</p>
 */
@Service
public class BillPaymentService {

    /** The confirmation value the reference accepts in upper case at line 174. */
    public static final String CONFIRM_YES_UPPER = "Y";

    /** The confirmation value the reference accepts in lower case at line 175. */
    public static final String CONFIRM_YES_LOWER = "y";

    /** The confirmation refusal the reference accepts in upper case at line 178. */
    public static final String CONFIRM_NO_UPPER = "N";

    /** The confirmation refusal the reference accepts in lower case at line 179. */
    public static final String CONFIRM_NO_LOWER = "n";

    /** The message-free answer the reference's negative branch produces by clearing the screen. */
    public static final String MESSAGE_ABANDONED = null;

    /** The stored rows this service writes the payment transaction into. */
    private final TransactionRepository transactions;

    /** The seam onto the account-owned balance and cross-reference. */
    private final AccountContextClient accounts;

    /** The boundary that composes the payment row, its response and its sentences. */
    private final BillPaymentMapper billPaymentMapper;

    /** The clock the payment timestamp is stamped from, injected so a test can fix it. */
    private final Clock clock;

    /**
     * Builds the service over its four collaborators.
     *
     * @param transactions the repository over the owned transaction table; must not be {@code null}
     * @param accounts the seam onto the account context; must not be {@code null}
     * @param billPaymentMapper the record boundary; must not be {@code null}
     * @param clock the clock the payment timestamp is taken from; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public BillPaymentService(TransactionRepository transactions, AccountContextClient accounts,
            BillPaymentMapper billPaymentMapper, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.billPaymentMapper =
                Objects.requireNonNull(billPaymentMapper, "billPaymentMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Pays the account's whole outstanding balance, or answers with the sentence the reference answers
     * with.
     *
     * @param request the submitted payment, already bean-validated by the API layer; must not be
     *     {@code null}
     * @return the posted acknowledgement when the payment was made, and otherwise the shape carrying the
     *     balance and the reference's prompt or advisory sentence; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the account identifier was never supplied or the confirmation
     *     carries a value the reference refuses
     * @throws NoSuchElementException if the account identifier names no account
     * @throws IllegalStateException if a read, the write or the balance update failed for a reason the
     *     caller cannot correct
     */
    @Transactional
    public BillPaymentResponse payBalanceInFull(BillPaymentRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String accountId = request.accountId();
        if (FieldValidationFlag.isNeverSupplied(accountId)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION,
                    BillPaymentMapper.ACCOUNT_ID_FIELD, FieldValidationFlag.BLANK,
                    BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY);
        }

        String confirmation = request.confirmation();
        boolean affirmative = CONFIRM_YES_UPPER.equals(confirmation)
                || CONFIRM_YES_LOWER.equals(confirmation);
        boolean negative = CONFIRM_NO_UPPER.equals(confirmation)
                || CONFIRM_NO_LOWER.equals(confirmation);
        boolean absent = FieldValidationFlag.isNeverSupplied(confirmation);

        if (!affirmative && !negative && !absent) {
            throw new ClientInputException(ApiError.CODE_VALIDATION,
                    BillPaymentMapper.CONFIRMATION_FIELD, FieldValidationFlag.NOT_OK,
                    BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION);
        }

        if (negative) {
            // WHY : Assumptions: the negative branch answers with the balance and no message at all,
            //       because the reference clears the screen at line 180 and sets its error flag at line
            //       181 without moving anything into the message field. Substituting a sentence here --
            //       "payment cancelled" would be the obvious one -- would put text in front of an
            //       operator that no line of the reference emits, which transformation rule T8 forbids.
            //       The balance is still reported because the reference has already moved it to the
            //       screen at line 194 by the time this branch is taken.
            Money balance = readBalance(accountId).currentBalance();
            return new BillPaymentResponse(null, accountId, balance, false, MESSAGE_ABANDONED);
        }

        Money balance = readBalance(accountId).currentBalance();

        // WHY : Assumptions: the comparison is against zero and is inclusive, matching the reference's
        //       "less than or equal to zeros" at line 197, so an account already at zero is answered with
        //       the nothing-to-pay advisory rather than having a zero-amount transaction written for it.
        //       A credit balance -- negative in this record's sign convention -- takes the same branch.
        if (!balance.exceeds(Money.ZERO)) {
            return new BillPaymentResponse(null, accountId, balance, false,
                    BillPaymentMapper.MESSAGE_NOTHING_TO_PAY);
        }

        if (!affirmative) {
            return new BillPaymentResponse(null, accountId, balance, false,
                    BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT);
        }

        String resolvedCardNumber = resolveCardNumber(accountId);
        String transactionId = nextTransactionId();
        LocalDateTime paymentTimestamp = LocalDateTime.now(this.clock);
        Transaction row = this.billPaymentMapper.toEntity(request, transactionId, resolvedCardNumber,
                balance, paymentTimestamp);

        Transaction stored;
        try {
            stored = this.transactions.save(row);
        } catch (RuntimeException writeFailure) {
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_TRANSACTION_LOOKUP_FAILED,
                    writeFailure);
        }

        try {
            this.accounts.applyPayment(accountId, balance);
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            // WHY : Refactoring Rationale: the balance change failing raises rather than returns, so the
            //       enclosing transaction rolls the payment row back. The reference cannot do this: its
            //       write at line 233 and its account update at line 235 are separate file operations and
            //       a failure of the second leaves the first standing, which is the one shape this screen
            //       can produce that the ledger cannot explain. Rolling back is a divergence and is
            //       registered as one rather than introduced silently.
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED,
                    unavailable);
        }

        return this.billPaymentMapper.toResponse(request, stored,
                this.billPaymentMapper.paymentSuccessfulMessage(transactionId));
    }

    /**
     * Reads the account balance, converting absence and failure into this screen's own answers.
     *
     * @param accountId the submitted account identifier, never {@code null}
     * @return the balance the account context reported, never {@code null}
     * @throws NoSuchElementException if the account does not exist
     * @throws IllegalStateException if the read could not be performed
     */
    private AccountContextClient.AccountBalance readBalance(String accountId) {
        try {
            return this.accounts.findAccountBalance(accountId).orElseThrow(
                    () -> new NoSuchElementException(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_ACCOUNT_LOOKUP_FAILED,
                    unavailable);
        }
    }

    /**
     * Resolves the card number the payment row is keyed by.
     *
     * @param accountId the submitted account identifier, never {@code null}
     * @return the card number the cross-reference resolved, never {@code null}
     * @throws NoSuchElementException if the account has no cross-reference entry
     * @throws IllegalStateException if the read could not be performed
     */
    private String resolveCardNumber(String accountId) {
        try {
            return this.accounts.findCardXrefByAccountId(accountId)
                    .map(AccountContextClient.CardXref::cardNumber)
                    .orElseThrow(() -> new NoSuchElementException(
                            BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_XREF_LOOKUP_FAILED, unavailable);
        }
    }

    /**
     * Obtains the next transaction identifier from the database's own allocator.
     *
     * <p>Assumptions: the reference derives the same value by positioning a browse at high values,
     * reading backwards once and adding one, at lines 212 to 217. The value is the same; the route
     * differs, and the route is what was wrong.</p>
     *
     * <p>Refactoring Rationale: this previously read the maximum stored identifier and added one in
     * Java. That was unsafe for two independent reasons -- concurrent callers derived the same key, and
     * the maximum spans a second identifier format that a numeric parse can reject -- both of which are
     * set out in full on {@code TransactionAddService.nextTransactionId()} and are not repeated here so
     * that the argument has one home. The concurrency hazard is if anything sharper on this path: bill
     * payment's unit of work also writes the account balance through another context, so the window
     * between reading a maximum and inserting under it was the widest of the two.</p>
     *
     * @return the next identifier as sixteen digit characters, never {@code null}
     * @throws IllegalStateException if the allocator could not be reached
     */
    private String nextTransactionId() {
        long next;
        try {
            next = this.transactions.allocateTransactionId();
        } catch (RuntimeException allocationFailure) {
            // WHY : Assumptions: the same sentence the previous read failure used, because from the
            //       caller's side the condition is identical -- an identifier could not be obtained --
            //       and the reference has one message for it.
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_TRANSACTION_LOOKUP_FAILED,
                    allocationFailure);
        }
        return String.format("%0" + TransactionAddService.TRANSACTION_ID_WIDTH + "d", next);
    }
}
