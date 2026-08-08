package com.carddemo.transaction.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures a new transaction, migrated from {@code app/cbl/COTRN02C.cbl}.
 *
 * <p>Purpose: this is the target of the reference program's add screen. It resolves the submitted account
 * identifier or card number through the cross-reference, derives the next transaction identifier the way
 * the reference derives it, and writes one row. Every sentence it emits is carried across character for
 * character from the program that emits it, as transformation rule T8 requires.</p>
 *
 * <p>Assumptions: the confirmation discriminator is evaluated FIRST, before any file is read, because the
 * reference evaluates it first at lines 168 to 187 and reaches its add paragraph only from the
 * affirmative branch. Reading the cross-reference before knowing whether the operator confirmed would
 * charge the account context for a read on every unconfirmed turn and would report a lookup failure for a
 * submission the reference never looked anything up for.</p>
 *
 * <p>Assumptions: the three branches the reference distinguishes are all distinguishable here. The
 * affirmative branch writes; the negative, blank and low-value branches share one prompt at line 178,
 * which is why they are answered together rather than separately; and anything else is the complaint at
 * line 184. The payment screen arranges the same field differently -- its negative branch abandons the
 * turn rather than prompting -- so the two evaluations are deliberately not shared.</p>
 */
@Service
public class TransactionAddService {

    /** The confirmation value the reference accepts in upper case at line 169. */
    public static final String CONFIRM_YES_UPPER = "Y";

    /** The confirmation value the reference accepts in lower case at line 170. */
    public static final String CONFIRM_YES_LOWER = "y";

    /** The confirmation refusal the reference accepts in upper case at line 172. */
    public static final String CONFIRM_NO_UPPER = "N";

    /** The confirmation refusal the reference accepts in lower case at line 173. */
    public static final String CONFIRM_NO_LOWER = "n";

    /** The request member the confirmation discriminator is keyed by. */
    public static final String FIELD_CONFIRMATION = "confirmation";

    /** The request member the account identifier is keyed by. */
    public static final String FIELD_ACCOUNT_ID = "accountId";

    /** The request member the card number is keyed by. */
    public static final String FIELD_CARD_NUMBER = "cardNumber";

    /** The prompt the reference emits at line 178 for a refused, blank or low-value confirmation. */
    public static final String MESSAGE_CONFIRM_ADD = "Confirm to add this transaction...";

    /** The complaint the reference emits at line 184 for any other confirmation value. */
    public static final String MESSAGE_INVALID_CONFIRMATION =
            "Invalid value. Valid values are (Y/N)...";

    /** The complaint the reference emits at line 226 when neither key was supplied. */
    public static final String MESSAGE_KEY_REQUIRED = "Account or Card Number must be entered...";

    /** The absence report the reference emits at line 593 for an unresolved account identifier. */
    public static final String MESSAGE_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** The absence report the reference emits at line 626 for an unresolved card number. */
    public static final String MESSAGE_CARD_NOT_FOUND = "Card Number NOT found...";

    /** The failed-read report the reference emits at line 600 for the account-keyed lookup. */
    public static final String MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED =
            "Unable to lookup Acct in XREF AIX file...";

    /** The failed-read report the reference emits at line 633 for the card-keyed lookup. */
    public static final String MESSAGE_CARD_XREF_LOOKUP_FAILED =
            "Unable to lookup Card # in XREF file...";

    /** The failed-read report the reference emits at lines 664 and 693 while deriving the key. */
    public static final String MESSAGE_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** The failed-write report the reference emits at line 745. */
    public static final String MESSAGE_ADD_FAILED = "Unable to Add Transaction...";

    /** The first fragment of the acknowledgement the reference composes at line 728. */
    public static final String MESSAGE_ADDED_PREFIX = "Transaction added successfully. ";

    /** The second fragment of that acknowledgement, from line 730, including both spaces. */
    public static final String MESSAGE_ADDED_INFIX = " Your Tran ID is ";

    /** The final fragment of that acknowledgement, from line 732. */
    public static final String MESSAGE_ADDED_SUFFIX = ".";

    /** The declared width of the transaction key, from {@code app/cpy/CVTRA05Y.cpy} line 5. */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /** The stored rows this service writes to and derives its next key from. */
    private final TransactionRepository transactions;

    /** The account-owned records this service resolves a card number through. */
    private final AccountContextClient accounts;

    /** The boundary that turns a submitted shape into a row and a row into a response. */
    private final TransactionMapper transactionMapper;

    /**
     * Builds the service over its three collaborators.
     *
     * @param transactions the repository over the owned transaction table; must not be {@code null}
     * @param accounts the seam onto the account context's cross-reference; must not be {@code null}
     * @param transactionMapper the record boundary; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionAddService(TransactionRepository transactions, AccountContextClient accounts,
            TransactionMapper transactionMapper) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.transactionMapper =
                Objects.requireNonNull(transactionMapper, "transactionMapper must not be null");
    }

    /**
     * Captures the submitted transaction, or answers with the prompt the reference answers with.
     *
     * @param request the submitted capture, already bean-validated by the API layer; must not be
     *     {@code null}
     * @return the acknowledgement carrying the generated key when the capture was confirmed and written,
     *     otherwise the prompt shape carrying no key; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if neither key was supplied, or the confirmation carries a value the
     *     reference refuses, each carrying that program's own sentence
     * @throws NoSuchElementException if the supplied key resolves to no cross-reference entry, carrying
     *     the absence sentence for the key that was supplied
     * @throws IllegalStateException if a read or the write failed for a reason the caller cannot correct,
     *     carrying the sentence the reference emits for that operation
     */
    @Transactional
    public TransactionAddResponse addTransaction(TransactionAddRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String confirmation = request.confirmation();
        if (!isAffirmative(confirmation)) {
            // WHY : Assumptions: the two prompt branches are separated here exactly as the reference
            //       separates them. A refused, blank or low-value confirmation is answered with the
            //       prompt and is NOT a refusal -- the reference re-sends the same screen and waits --
            //       so it is reported as a 200 carrying the prompt rather than as a 400. Any other value
            //       is the complaint at line 184, which is a refusal of what was submitted.
            if (isNegativeOrAbsent(confirmation)) {
                return new TransactionAddResponse(null, request.amount(), MESSAGE_CONFIRM_ADD);
            }
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_CONFIRMATION,
                    FieldValidationFlag.NOT_OK, MESSAGE_INVALID_CONFIRMATION);
        }

        String resolvedCardNumber = resolveCardNumber(request);
        String transactionId = nextTransactionId();
        Transaction row = this.transactionMapper.toEntity(request, transactionId, resolvedCardNumber);

        Transaction stored;
        try {
            stored = this.transactions.save(row);
        } catch (RuntimeException writeFailure) {
            // WHY : Refactoring Rationale: the write failure is re-raised as the standard illegal-state
            //       type carrying the reference sentence, because the shared advice renders a carried
            //       sentence only for that exact type and only when the sentence is provably from the
            //       message catalogue. Letting the persistence failure propagate would answer with the
            //       generic internal sentence, and the published contract for this operation promises
            //       this program's own wording at line 745.
            throw new IllegalStateException(MESSAGE_ADD_FAILED, writeFailure);
        }

        return this.transactionMapper.toAddResponse(stored, acknowledgement(transactionId));
    }

    /**
     * Reports whether the submitted confirmation is one of the two affirmative spellings.
     *
     * @param confirmation the submitted discriminator, possibly {@code null}
     * @return {@code true} for the upper or lower case affirmative, otherwise {@code false}
     */
    private static boolean isAffirmative(String confirmation) {
        return CONFIRM_YES_UPPER.equals(confirmation) || CONFIRM_YES_LOWER.equals(confirmation);
    }

    /**
     * Reports whether the submitted confirmation is one the reference answers with its prompt.
     *
     * <p>Assumptions: the never-supplied test covers both of the reference's two absence spellings at
     * lines 174 and 175, spaces and low values, which the shared validation kernel treats as one state
     * because the reference's own comparison does.</p>
     *
     * @param confirmation the submitted discriminator, possibly {@code null}
     * @return {@code true} for the negative spellings and for either absence spelling, otherwise
     *     {@code false}
     */
    private static boolean isNegativeOrAbsent(String confirmation) {
        return CONFIRM_NO_UPPER.equals(confirmation) || CONFIRM_NO_LOWER.equals(confirmation)
                || FieldValidationFlag.isNeverSupplied(confirmation);
    }

    /**
     * Resolves the card number the row is keyed by from whichever key the submission carried.
     *
     * <p>Assumptions: the account identifier is tried first and the card number second, which is the
     * order the reference tries them in at lines 570 and 605. The order is observable: a submission
     * carrying both keys resolves through the account identifier, so a card number that disagrees with it
     * is not reported.</p>
     *
     * @param request the submitted capture, never {@code null}
     * @return the card number the cross-reference resolved, never {@code null}
     * @throws ClientInputException if neither key was supplied
     * @throws NoSuchElementException if the supplied key resolves to nothing
     * @throws IllegalStateException if the lookup could not be performed
     */
    private String resolveCardNumber(TransactionAddRequest request) {
        if (!FieldValidationFlag.isNeverSupplied(request.accountId())) {
            return lookup(() -> this.accounts.findCardXrefByAccountId(request.accountId()),
                    FIELD_ACCOUNT_ID, MESSAGE_ACCOUNT_NOT_FOUND, MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED);
        }
        if (!FieldValidationFlag.isNeverSupplied(request.cardNumber())) {
            return lookup(() -> this.accounts.findCardXrefByCardNumber(request.cardNumber()),
                    FIELD_CARD_NUMBER, MESSAGE_CARD_NOT_FOUND, MESSAGE_CARD_XREF_LOOKUP_FAILED);
        }
        throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_ACCOUNT_ID,
                FieldValidationFlag.BLANK, MESSAGE_KEY_REQUIRED);
    }

    /**
     * Performs one cross-reference read and converts its three outcomes into this screen's answers.
     *
     * @param read the read to perform, never {@code null}
     * @param field the request member the read was keyed by, never {@code null}
     * @param absentMessage the sentence for a key that resolves to nothing, never {@code null}
     * @param failedMessage the sentence for a read that could not be performed, never {@code null}
     * @return the resolved card number, never {@code null}
     * @throws NoSuchElementException if the key resolves to nothing
     * @throws IllegalStateException if the read could not be performed
     */
    private String lookup(java.util.function.Supplier<Optional<AccountContextClient.CardXref>> read,
            String field, String absentMessage, String failedMessage) {
        try {
            return read.get()
                    .map(AccountContextClient.CardXref::cardNumber)
                    .orElseThrow(() -> new NoSuchElementException(absentMessage));
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            throw new IllegalStateException(failedMessage, unavailable);
        }
    }

    /**
     * Obtains the next transaction identifier from the database's own allocator.
     *
     * <p>Assumptions: the reference positions a browse at high values, reads backwards once and adds one
     * to the key it found, at lines 442 to 449. The value this method returns is the same value that
     * derivation would produce, obtained by a different route, and the route is the point.</p>
     *
     * <p>Refactoring Rationale: this previously read the maximum stored identifier and added one in
     * Java, which was a faithful transcription of the reference and an unsafe one here. It had two
     * independent defects, and each would have reached production as an unexplained failure.</p>
     *
     * <p>The first was concurrency. Under CICS the add and payment transactions were serialised by the
     * region, so read-then-add was atomic in effect. Two Fargate tasks behind a load balancer are not
     * serialised: both read the same maximum, both add one, and both attempt the same primary key, so
     * one add fails on a constraint violation reported as an internal error. Allocating in the database
     * makes the increment indivisible.</p>
     *
     * <p>The second was a parse failure that only appears after a normal night's batch. This context
     * stores identifiers in two formats -- the sequence format this method serves, and the format the
     * interest job composes from a business-date prefix at {@code app/cbl/CBACT04C.cbl} lines 474 to
     * 480. Reading the MAXIMUM spans both formats, because the column is {@code CHAR(16)} and orders
     * lexicographically, so a date-prefixed identifier sorts above every sequence-format one. The old
     * code then called {@code Long.parseLong} on it. That is harmless while the prefix is numeric, as
     * the baseline's own parameter is -- {@code app/jcl/INTCALC.jcl} line 22 passes {@code '2022071800'}
     * -- but the migrated entry point admitted a separated token of the same width, which yields an
     * identifier like {@code 2024-01-15000001} and a {@code NumberFormatException} on the next add and
     * the next payment. The allocator removes the read entirely, so no stored value is parsed at all;
     * the entry point's own predicate was tightened in the same change so the non-numeric prefix cannot
     * be produced either.</p>
     *
     * @return the next identifier as sixteen digit characters, never {@code null}
     * @throws IllegalStateException if the allocator could not be reached
     */
    private String nextTransactionId() {
        long next;
        try {
            next = this.transactions.allocateTransactionId();
        } catch (RuntimeException allocationFailure) {
            // WHY : Assumptions: the failure is reported with the SAME message the previous read
            //       failure used, and deliberately so. From the caller's side the condition is
            //       identical -- an identifier could not be obtained -- and the reference has one
            //       sentence for it. Introducing a second message would be a user-visible divergence
            //       for an internal change of mechanism.
            throw new IllegalStateException(MESSAGE_TRANSACTION_LOOKUP_FAILED, allocationFailure);
        }

        // WHY : Assumptions: the allocated number is rendered zero-padded to the declared width rather
        //       than written as its shortest form. app/cpy/CVTRA05Y.cpy L5 declares TRAN-ID PIC X(16),
        //       an alphanumeric picture, so the full sixteen bytes are the contract and leading zeros
        //       are significant -- the seeded extract carries values such as 0000000000683580 that a
        //       shortest-form rendering would not match.
        return String.format("%0" + TRANSACTION_ID_WIDTH + "d", next);
    }

    /**
     * Composes the acknowledgement the reference composes from three fragments.
     *
     * <p>Assumptions: the fragments are concatenated in the reference's own order and with its own spacing
     * -- the first ends with a space and the second begins and ends with one -- so the rendered sentence
     * matches character for character including the double space the reference produces between them.</p>
     *
     * @param transactionId the generated key, never {@code null}
     * @return the acknowledgement sentence, never {@code null}
     */
    private static String acknowledgement(String transactionId) {
        return MESSAGE_ADDED_PREFIX + MESSAGE_ADDED_INFIX + transactionId + MESSAGE_ADDED_SUFFIX;
    }
}
