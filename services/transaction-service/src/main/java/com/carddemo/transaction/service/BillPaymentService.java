package com.carddemo.transaction.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays an account's outstanding balance in full, transcribed from {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>Purpose: this is the migrated bill-payment screen and the only balance-affecting write in the
 * ledger context. It evaluates a submission in the reference program's own order, and on a confirmed
 * submission it writes one ledger transaction for the whole outstanding balance and has that balance
 * reduced by the same amount. The reference is 572 lines, counted in the file rather than carried over
 * from a summary, and it is read as specification and never modified.</p>
 *
 * <h2>Paragraph anchors</h2>
 *
 * <p>Each paragraph of the reference maps to one member here, so the traceability matrix can cite the
 * pair. Three paragraphs have no member and the reason is given rather than left as a gap.</p>
 *
 * <ul>
 *   <li>{@code MAIN-PARA} at line 99 — no member. It is CICS task orchestration: it re-establishes the
 *       communication area, dispatches on the attention identifier and returns with a transaction
 *       identifier. None of that survives a stateless handler.</li>
 *   <li>{@code PROCESS-ENTER-KEY} at line 154 — {@link #payBalanceInFull(BillPaymentRequest)}.</li>
 *   <li>{@code GET-CURRENT-TIMESTAMP} at line 249 — {@link #paymentTimestamp()}.</li>
 *   <li>{@code READ-ACCTDAT-FILE} at line 343 — {@link #readBalance(String)}.</li>
 *   <li>{@code UPDATE-ACCTDAT-FILE} at line 377 — {@link #applyBalanceChange(String, Money)}.</li>
 *   <li>{@code READ-CXACAIX-FILE} at line 408 — {@link #resolveCardNumber(String)}.</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} at line 441 — subsumed into {@link #nextTransactionId()}.</li>
 *   <li>{@code READPREV-TRANSACT-FILE} at line 472 — subsumed into {@link #nextTransactionId()}.</li>
 *   <li>{@code ENDBR-TRANSACT-FILE} at line 501 — no member. It closes a browse cursor, and the
 *       maximum-key query that replaces the browse holds none to close.</li>
 *   <li>{@code WRITE-TRANSACT-FILE} at line 510 — {@link #persist(Transaction)}.</li>
 * </ul>
 *
 * <h2>Function-key dispatch is client-side</h2>
 *
 * <p>Assumptions: the reference dispatches on the attention identifier at lines 125 to 142 — Enter at
 * lines 126 and 127, PF3 at lines 128 to 135, PF4 at lines 136 and 137, and anything else refused at
 * lines 138 to 141. This screen declares no PF5, no PF7 and no PF8 at all. The mapping is recorded here
 * and implemented nowhere in this class: Enter is this method being called, PF3 and PF4 are a route
 * change and a form reset in the browser, and an unrecognised key never reaches a server that is
 * addressed by path rather than by key. Worth stating because PF5 means something different on every
 * screen and never means save — {@code app/cbl/COTRN01C.cbl} lines 125 to 127 navigate, and
 * {@code app/cbl/COTRN02C.cbl} lines 146 and 147 copy the last captured transaction — so a reader
 * generalising from a sibling would expect a key this screen does not have.</p>
 *
 * <h2>The posted transaction is almost entirely constant</h2>
 *
 * <p>Assumptions: ten consecutive move statements at lines 220 to 229 populate the transaction record,
 * and exactly EIGHT of the ten carry a hardcoded literal. The eight are NOT contiguous: lines 224 and
 * 225 sit among them and carry live data instead, the balance into the amount and the cross-referenced
 * card number into the card number. The eight literals are the type code {@code '02'} at line 220, the
 * category code — the numeric literal {@code 2}, not the string, into a {@code PIC 9(04)} field
 * declared at line 7 of {@code app/cpy/CVTRA05Y.cpy} — at line 221, the source {@code 'POS TERM'} at
 * line 222, the description {@code 'BILL PAYMENT - ONLINE'} at line 223, the merchant identifier
 * {@code 999999999} at line 226, the merchant name {@code 'BILL PAYMENT'} at line 227 and the merchant
 * city and merchant zip, both {@code 'N/A'}, at lines 228 and 229.</p>
 *
 * <p>Alternatives Considered: declaring those eight as constants on this class. Rejected because
 * {@link BillPaymentMapper} already declares all eight, byte for byte, and its
 * {@link BillPaymentMapper#toEntity} is what writes them onto the row. A second declaration here would
 * be a second source of truth for one contract, which transformation rule T2 exists to prevent, and it
 * would be unreachable besides, since nothing on this path would read it. They are persisted values a
 * report renders, so transformation rule T8 governs them as strictly as it governs a screen sentence,
 * and one owner is what keeps them byte-exact. The inventory is recorded above so a reader can audit
 * the mapper against the reference without opening this class again.</p>
 *
 * <h2>The account context boundary is crossed twice, by one seam, for two different reasons</h2>
 *
 * <p>Assumptions: three of the records this screen touches belong to the account context under
 * schema-per-service — the card cross-reference read at line 408, the account master read at line 343
 * and the balance rewrite at line 377 — and this package's charter states that a seam is the only way
 * to reach them. {@link AccountContextClient} is that seam. The two crossings remain distinguishable
 * here even though they share one seam: the cross-reference and balance READS are lookups whose answers
 * this class then decides on, while the balance CHANGE is a write that must stand or fall with the
 * ledger row, which is why it is issued last and inside the same transaction.</p>
 *
 * <p>Alternatives Considered: issuing the balance read-for-update and rewrite as schema-qualified native
 * SQL — {@code SELECT ... FROM account.accounts ... FOR UPDATE} followed by an
 * {@code UPDATE account.accounts} — on this module's own entity manager, so that the lock, the
 * ledger insert and the balance change would share one local transaction. It was evaluated first
 * because it is the only arrangement that makes the balance change genuinely atomic with the row, and
 * it was rejected on evidence: this service connects as {@code carddemo_ledger}, and
 * {@code data-migration/sql/V0__schemas_and_roles.sql} grants that role {@code USAGE} on the
 * {@code ledger} schema alone. The cross-schema grants it does contain — {@code USAGE} on four schemas
 * and {@code UPDATE ON account.accounts} — are issued to {@code carddemo_batch} and are justified in
 * that file by the nightly posting and interest programs, not by an online screen. Probing a database
 * provisioned from that script confirms it: for {@code carddemo_ledger},
 * {@code has_schema_privilege('account','USAGE')} is false and both {@code SELECT} and {@code UPDATE}
 * on {@code account.accounts} are denied. That script's own note records what follows — without schema
 * {@code USAGE} a table-level privilege is unreachable and the error names the schema — so the
 * statements would not resolve at run time. Widening the grant is not an available remedy: this class
 * issues no grant statement and does not edit that script, which is the single owner of the privilege
 * graph.</p>
 *
 * <p>Alternatives Considered: three further routes to the balance, each rejected for its own reason. A
 * fifth entity type for the account in this module — rejected because the sibling domain charter
 * fixes exactly four entity types and an account entity here would claim ownership of a table another
 * context owns. A saga, or a transactional outbox with a compensating reversal — rejected because both
 * replace one commit with a sequence of committed steps, which makes a partly-applied payment
 * observable when the baseline has no such state. A second {@code DataSource} — rejected because a
 * separate connection is a separate transaction, which forfeits the atomicity that was the whole point
 * of reaching for SQL.</p>
 *
 * <p>Trade-offs: the accepted cost is that the balance change is applied by its owner rather than in
 * this transaction, so it is not atomic with the ledger insert in the way lines 233 and 235 are atomic
 * under one CICS syncpoint. The exposure is bounded in the one direction that matters and the bound is
 * deliberate: the change is issued LAST, and a failure of it propagates, so the enclosing transaction
 * rolls the ledger row back and a written payment never stands beside an unchanged balance. What
 * remains is the converse — a balance reduced and the local commit then failing — and it is narrowed by
 * ordering the change immediately before the commit. This is a divergence from the reference's single
 * syncpoint and is recorded as one rather than presented as equivalent.</p>
 *
 * <p>Alternatives Considered: a circuit breaker in front of the seam. Rejected because the hop is
 * in-network to a service behind an internal load balancer and both of its timeouts are bounded by
 * configuration, so a stalled dependency already surfaces as a refused request within seconds. A
 * breaker would add a state machine that can refuse a call the dependency would have served, which is
 * a new failure mode in exchange for none removed. No resilience library is introduced and no retry is
 * declared on this path: a retry of a payment is a retry of money movement, and the seam offers no
 * idempotency key to make one safe.</p>
 *
 * <h2>State that does not travel</h2>
 *
 * <p>Refactoring Rationale: the reference is pseudo-conversational, so everything connecting one screen
 * turn to the next lives in the communication area declared at lines 19 to 44 of
 * {@code app/cpy/COCOM01Y.cpy}. None of it travels here, and the re-entry discriminator at lines 29 to
 * 31 disappears outright: a handler that answers a deficient submission with a per-field error array
 * has no first-entry-versus-re-entry distinction left to make. What was wrong with the old arrangement
 * is not that it was stateful but that the state was storage the client echoed back, so a client could
 * in principle assert it. This class is stateless, holds only its injected collaborators, and derives
 * every decision from the submitted request and stored data.</p>
 */
@Service
public class BillPaymentService {

    /** The confirmation the reference accepts in upper case at line 174 of the reference. */
    public static final String CONFIRM_YES_UPPER = "Y";

    /** The confirmation the reference accepts in lower case at line 175 of the reference. */
    public static final String CONFIRM_YES_LOWER = "y";

    /** The refusal the reference accepts in upper case at line 178 of the reference. */
    public static final String CONFIRM_NO_UPPER = "N";

    /** The refusal the reference accepts in lower case at line 179 of the reference. */
    public static final String CONFIRM_NO_LOWER = "n";

    /**
     * The sentence the reference emits when the derived identifier is already taken.
     *
     * <p>Assumptions: carried verbatim from line 536 of {@code app/cbl/COBIL00C.cbl}, which both the
     * duplicate-key and the duplicate-record branches at lines 533 and 534 fall through to. It is
     * declared here rather than on the mapper because the mapper owns record representation and this is
     * an operator sentence, which the seam's own contract keeps in the service layer under
     * transformation rule T8.</p>
     */
    public static final String MESSAGE_TRANSACTION_ID_EXISTS = "Tran ID already exist...";

    /**
     * The sentence the reference emits when the payment row could not be written.
     *
     * <p>Assumptions: carried verbatim from line 543 of {@code app/cbl/COBIL00C.cbl}. It is a SECOND
     * constant and not a reuse of the capture screen's wording: {@code app/cbl/COTRN02C.cbl} line 745
     * emits {@code 'Unable to Add Transaction...'} for the same class of failure, and the two strings
     * differ. Merging them would silently reword one of the two screens.</p>
     */
    public static final String MESSAGE_PAYMENT_ADD_FAILED = "Unable to Add Bill pay Transaction...";

    /**
     * The identifier the reference derives when the transaction file holds no record.
     *
     * <p>Assumptions: the reference reaches this by moving zeros into the identifier on an end-of-file
     * condition at line 488 and then adding one at line 217, so an empty file yields one rather than
     * zero.</p>
     */
    public static final long FIRST_TRANSACTION_ID = 1L;

    /** The rows this service writes the payment transaction into and reads the highest key from. */
    private final TransactionRepository transactions;

    /** The seam onto the account-owned cross-reference, balance and balance change. */
    private final AccountContextClient accounts;

    /** The boundary that composes the payment row, its response and its confirmation sentence. */
    private final BillPaymentMapper billPaymentMapper;

    /** The clock the payment timestamp is read from, injected so a test can pin it. */
    private final Clock clock;

    /**
     * Builds the service over its four collaborators.
     *
     * <p>Assumptions: all four arrive through the constructor and are final, so an instance is fully
     * formed before it can serve a request and holds no mutable state. Field injection was not used:
     * it would leave a partially constructed instance observable and would let a test build one without
     * the seam, which is the collaborator every branch below depends on.</p>
     *
     * @param transactions the repository over the owned {@code ledger.transactions} table, used to read
     *     the highest existing key and to write the payment row; must not be {@code null}
     * @param accounts the seam onto the account context, used for the cross-reference read, the balance
     *     read and the balance change; must not be {@code null}
     * @param billPaymentMapper the record and response boundary that applies the eight literals, the
     *     money contract and the identifier widths; must not be {@code null}
     * @param clock the clock the single payment timestamp is read from; must not be {@code null}
     * @throws NullPointerException if any of the four collaborators is {@code null}
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
     * <p>Purpose: this is {@code PROCESS-ENTER-KEY} at line 154 of {@code app/cbl/COBIL00C.cbl}. It
     * settles the account identifier, evaluates the confirmation, refuses a balance there is nothing to
     * pay on, and on a confirmed submission resolves the card number, derives an identifier, writes the
     * payment row and has the balance reduced by the amount paid.</p>
     *
     * <p>Assumptions: the evaluation order is load-bearing rather than incidental, because it decides
     * which single sentence a submission deficient in more than one way is answered with. The reference
     * re-tests its error flag three times, at lines 169, 197 and 208, and each re-test gates the block
     * after it. So a never-supplied account identifier is caught at line 159 and answered at line 161
     * before the confirmation is examined at line 173 at all; the balance test at lines 198 and 199 runs
     * only for a submission that cleared the confirmation; and the write at lines 210 to 235 runs only
     * for one that cleared the balance test too. The commonest first-turn submission leaves both fields
     * empty, and it must be answered about the account identifier alone.</p>
     *
     * <p>Alternatives Considered: gathering every failure and answering with all of them at once, which
     * is the natural shape for a validated request body. Rejected because the reference moves one
     * sentence into a single 80-character message field, declared at line 39 of this program, and sends
     * the screen, so it can report exactly one condition per submission. An answer carrying line 187's
     * complaint alongside line 161's would be a combination this screen cannot produce. Each refusal
     * therefore raises with one field and one sentence, and the shared advice renders the one-element
     * per-field array from it.</p>
     *
     * <p>Assumptions: exactly ONE transactional boundary spans this method, because lines 233 and 235
     * sit inside one CICS syncpoint. The annotation is on this method rather than on a narrower inner
     * one for a mechanical reason, not a stylistic one: the transaction is applied by a proxy, and a
     * private method invoked from within this class is invoked on {@code this} rather than through that
     * proxy, so a narrower annotated method would be silently untransacted — the worst available
     * outcome, since it would look correct and commit each write separately. A rollback is signalled the
     * way the reference signals it at lines 4095 to 4104 of the account-update program, by letting the
     * failure propagate rather than by returning a status.</p>
     *
     * <p>Trade-offs: because the boundary is this whole method, the two seam reads execute inside it and
     * hold a pooled connection across a network wait. That cost is accepted and bounded rather than
     * ignored: both the connect and the read timeout are set from configuration, so a silent dependency
     * fails the request in seconds instead of holding a connection indefinitely. The reads are also
     * ordered before the write so that a submission refused on its balance never opens the write path at
     * all.</p>
     *
     * @param request the submitted payment, already bean-validated by the API layer, carrying the
     *     account identifier and the one-character confirmation and no amount; must not be {@code null}
     * @return the posted acknowledgement when the payment was made, and otherwise the preview shape
     *     carrying the balance together with the reference's prompt, its nothing-to-pay advisory or no
     *     sentence at all; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the account identifier was never supplied, answered with line 161,
     *     or the confirmation carries a value outside the four the reference accepts, answered with
     *     lines 187 and 188
     * @throws NoSuchElementException if the account identifier names no account, answered with line 361,
     *     or the account has no cross-reference entry, answered with line 425
     * @throws DataIntegrityViolationException if the derived identifier is already taken, which is the
     *     duplicate-key and duplicate-record branches at lines 533 and 534
     * @throws IllegalStateException if a read failed, answered with line 368 or line 432, if the payment
     *     row could not be written, answered with line 543, or if the balance change was refused,
     *     answered with line 399
     */
    @Transactional
    public BillPaymentResponse payBalanceInFull(BillPaymentRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String accountId = request.accountId();

        // WHY : Assumptions: this guard reads nothing and is first for that reason. The reference's
        //       line 159 tests the submitted field for spaces or low values and answers at line 161
        //       before any file is opened, so a target that resolved the account before answering would
        //       charge the account context for a read the reference never performs. The shared helper is
        //       used rather than a local emptiness test because it is the one place the baseline's two
        //       never-supplied representations, spaces and low values, are treated as one condition.
        if (FieldValidationFlag.isNeverSupplied(accountId)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION,
                    BillPaymentMapper.ACCOUNT_ID_FIELD, FieldValidationFlag.BLANK,
                    BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY);
        }

        String confirmation = request.confirmation();
        boolean affirmative = CONFIRM_YES_UPPER.equals(confirmation)
                || CONFIRM_YES_LOWER.equals(confirmation);
        boolean refused = CONFIRM_NO_UPPER.equals(confirmation)
                || CONFIRM_NO_LOWER.equals(confirmation);
        boolean withheld = FieldValidationFlag.isNeverSupplied(confirmation);

        // WHY : Assumptions: the confirmation domain is settled before the account is read, because the
        //       reference's out-of-domain branch at line 185 performs no read either -- it moves the
        //       complaint at lines 187 and 188 and sends the screen. Only two of the four branches,
        //       lines 177 and 184, reach READ-ACCTDAT-FILE.
        if (!affirmative && !refused && !withheld) {
            throw new ClientInputException(ApiError.CODE_VALIDATION,
                    BillPaymentMapper.CONFIRMATION_FIELD, FieldValidationFlag.NOT_OK,
                    BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION);
        }

        if (refused) {
            // WHY : Refactoring Rationale: the refusal branch answers with NO sentence, and this is the
            //       one place a reader is most likely to substitute one. The reference's branch at lines
            //       178 and 179 performs CLEAR-CURRENT-SCREEN at line 180 and sets its error flag at
            //       line 181, and it moves nothing into the message field -- so the flag is being used
            //       as a control-flow short-circuit, not to report an operator error. Inventing a
            //       sentence here, "payment cancelled" being the obvious candidate, would put text in
            //       front of an operator that no line of the reference emits, which transformation rule
            //       T8 forbids.
            // WHY : Refactoring Rationale: this branch must NOT be shared with the capture screen's,
            //       and the two look similar enough to invite it. The capture screen's evaluation at
            //       line 169 of app/cbl/COTRN02C.cbl has THREE branches, and its refusal shares one
            //       handler with the blank and low-value cases that DOES emit line 178's prompt. Here
            //       the refusal has its own branch and emits nothing: four branches against three, and
            //       one behaviour against the other. A shared evaluator would have to break one of the
            //       two.
            // WHY : Assumptions: returning here also skips the balance test below, matching the
            //       reference's gating. Line 197 re-tests the error flag that line 181 has just set, so
            //       a refused payment on an account with nothing to pay is answered with no sentence
            //       rather than with the nothing-to-pay advisory.
            return previewOf(accountId, readBalance(accountId), null);
        }

        Money payableBalance = readBalance(accountId);

        // WHY : Assumptions: the comparison is INCLUSIVE and the inclusivity is the single easiest thing
        //       on this path to get wrong, because a strict test reads perfectly well in review. Line
        //       198 is "IF ACCT-CURR-BAL <= ZEROS", so a balance of exactly zero takes this branch; a
        //       strict form would let a zero-balance account pay zero and write a zero-amount row the
        //       reference never writes. The shared predicate reports whether the amount is above zero,
        //       so negating it is exactly the reference's test, and a credit balance -- negative in this
        //       record's sign convention -- takes the same branch.
        // WHY : Assumptions: the reference's condition has a SECOND half at line 199, requiring the
        //       submitted account field to be neither spaces nor low values. It is already established
        //       here: the guard above returns for a never-supplied identifier, so this point is only
        //       reachable with one supplied, and restating it would be a test that cannot fail.
        if (!payableBalance.isPositive()) {
            // WHY : Assumptions: the advisory is RETURNED as a turn rather than raised, because the
            //       reference reaches it by the same mechanism it reaches the prompt below -- lines 200
            //       to 204 move the sentence and send the screen, exactly as lines 236 to 242 do -- so
            //       both are ordinary turns of the same transaction and neither is an abend.
            return previewOf(accountId, payableBalance, BillPaymentMapper.MESSAGE_NOTHING_TO_PAY);
        }

        if (!affirmative) {
            // WHY : Assumptions: reaching here means the confirmation was withheld, since the refusal
            //       returned above and an out-of-domain value raised. The reference reads the account on
            //       this branch at line 184 precisely so the balance can be displayed at lines 193 and
            //       194 beside the prompt it moves at lines 237 and 238, which is why the balance is
            //       reported here and no payment is attempted.
            return previewOf(accountId, payableBalance, BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT);
        }

        return pay(request, accountId, payableBalance);
    }

    /**
     * Writes the payment row and has the balance reduced by the amount paid.
     *
     * <p>Purpose: this is the confirmed branch at lines 210 to 235 of {@code app/cbl/COBIL00C.cbl}. It
     * resolves the card number, derives the identifier, composes the row, writes it and then applies the
     * balance change, in that order.</p>
     *
     * <p>Assumptions: the amount paid is the whole balance and is not client input. Line 224 moves the
     * stored balance into the transaction amount, and the screen carries no amount field to migrate,
     * which is why {@link BillPaymentRequest} has no amount component and why the balance read before
     * this call is both the figure reported and the figure paid.</p>
     *
     * <p>Trade-offs: the balance is a ten-integer-digit field, {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * line 7 of {@code app/cpy/CVACT01Y.cpy}, and the transaction amount is a nine-digit one,
     * {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy}. Line 224 therefore
     * moves the wider field into the narrower one, so a balance above the narrower picture loses its
     * high-order digit in the reference. The screen field {@code CURBALI PIC X(14)} at line 66 of
     * {@code app/cpy-bms/COBIL00.CPY} is sized for the wider picture, which corroborates the asymmetry
     * rather than resolving it. This is latent behaviour of the baseline at balances the seed data does
     * not reach; the migrated path carries the full value through the exact-decimal money type and the
     * difference is recorded here rather than reproduced.</p>
     *
     * <p>Trade-offs: the order is write, then compute, then update -- lines 233, 234 and 235 -- and it
     * is deliberately NOT normalised against the nightly posting job, which orders the same three
     * concerns the other way round: category balance at line 440 of {@code app/cbl/CBTRN02C.cbl}, the
     * account at line 441 and the transaction write at line 442. Each order is its own program's
     * behaviour, and this one is an online payment rather than the posting chain. Within a single commit
     * the order is not externally observable, so preserving it buys nothing at run time; it is preserved
     * anyway so that the transcription stays auditable statement by statement against the source, which
     * is what a reader comparing the two files needs.</p>
     *
     * @param request the submitted payment, needed by the response boundary to echo the account
     *     identifier at its declared width; must not be {@code null}
     * @param accountId the validated account identifier the payment settles; must not be {@code null}
     * @param payableBalance the balance as it stood when it was read, which is both the amount paid and
     *     the figure reported; must not be {@code null}
     * @return the posted acknowledgement carrying the assigned identifier, the pre-payment balance and
     *     the confirmation sentence; never {@code null}
     * @throws NoSuchElementException if the account has no cross-reference entry, answered with line 425
     * @throws DataIntegrityViolationException if the derived identifier is already taken, lines 533 to
     *     536
     * @throws IllegalStateException if the identifier could not be derived, if the row could not be
     *     written, answered with line 543, or if the balance change was refused, answered with line 399
     */
    private BillPaymentResponse pay(BillPaymentRequest request, String accountId,
            Money payableBalance) {

        // WHY : Assumptions: the card number comes from the cross-reference and never from the request.
        //       Line 225 moves XREF-CARD-NUM, populated by the read at line 211, into the row, so the
        //       card the payment is recorded against is the one the account owns rather than one a
        //       client could nominate.
        String resolvedCardNumber = resolveCardNumber(accountId);
        String transactionId = nextTransactionId();

        Transaction row = this.billPaymentMapper.toEntity(request, transactionId, resolvedCardNumber,
                payableBalance, paymentTimestamp());

        Transaction stored = persist(row);

        // WHY : Refactoring Rationale: the amount to subtract is transmitted, not a computed new
        //       balance, and that is what preserves the reference's arithmetic. Line 234 computes
        //       ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT, so the subtraction is performed against the
        //       balance as it then stands. Sending zero instead -- the algebraically identical answer
        //       today, since the amount IS the whole balance -- would replace that subtraction with an
        //       assignment, which transformation rule T4 forbids because it discards the form the
        //       baseline computes in; it would also overwrite rather than adjust, so a balance that
        //       moved between the read and the write would be silently zeroed instead of reduced.
        // WHY : Assumptions: this is issued LAST and its failure propagates, which is what makes the
        //       ordering protective rather than incidental. Because it is inside this method's
        //       transaction, a refusal here rolls the row written above back, so the state the reference
        //       cannot produce -- a payment recorded against an unchanged balance -- cannot be reached
        //       from this path.
        applyBalanceChange(accountId, payableBalance);

        // WHY : Assumptions: the confirmation sentence is ASSEMBLED from its fragments rather than written
        //       here as one literal, and the difference is a byte. Lines 527 to 530 build it with a string
        //       statement from four pieces -- a prefix ending in a space, an infix beginning with one, the
        //       identifier, and a period -- so the rendered text carries TWO consecutive spaces after
        //       "successful." that a hand-written literal would collapse to one. The fragments and the
        //       assembly live on the boundary that owns them, which is also what keeps this screen's
        //       wording separate from the capture screen's: line 528 spells "Transaction" where line 730
        //       of app/cbl/COTRN02C.cbl abbreviates it to "Tran".
        return this.billPaymentMapper.toResponse(request, stored,
                this.billPaymentMapper.paymentSuccessfulMessage(transactionId));
    }

    /**
     * Builds the answer for a turn on which no payment was made.
     *
     * <p>Purpose: three branches end without paying -- the refusal at lines 178 to 181, the
     * nothing-to-pay advisory at lines 200 to 204 and the confirmation prompt at lines 236 to 239 -- and
     * all three answer with the balance and at most one sentence. Composing that shape once keeps the
     * three from drifting apart in what they report.</p>
     *
     * <p>Assumptions: no identifier is reported and the paid discriminator is false, because no row was
     * written. The canonical constructor is used rather than the record's posted factory precisely
     * because that factory fixes the discriminator to the affirmative value, which none of these three
     * branches may claim.</p>
     *
     * <p>Assumptions: an absent sentence is {@code null} and never spaces. Line 30 of
     * {@code app/cpy/CVCRD01Y.cpy} attaches a low-values condition to the 75-character return message
     * at line 29 alone, so absence is representable for that field and null is what represents it. The
     * message field this program moves its sentences into is {@code WS-MESSAGE PIC X(80)} at line 39,
     * which is working storage rather than a contract width and is truncated into {@code ERRMSGI PIC
     * X(78)} at line 78 of {@code app/cpy-bms/COBIL00.CPY} on its way to the screen; the carried
     * contract is therefore the 75 characters of line 29, and every sentence passed here is well inside
     * it.</p>
     *
     * @param accountId the validated account identifier being reported back; must not be {@code null}
     * @param payableBalance the balance as read, reported so the operator sees what would be paid; must
     *     not be {@code null}
     * @param returnMessage the reference's sentence for this turn, or {@code null} on the refusal branch,
     *     which emits none
     * @return the preview shape with its discriminator false and no identifier; never {@code null}
     */
    private BillPaymentResponse previewOf(String accountId, Money payableBalance,
            String returnMessage) {
        return new BillPaymentResponse(null, accountId, payableBalance, false, returnMessage);
    }

    /**
     * Reads the account's current balance through the seam.
     *
     * <p>Purpose: this is {@code READ-ACCTDAT-FILE} at line 343 of {@code app/cbl/COBIL00C.cbl}, reached
     * from the affirmative branch at line 177 and from the withheld branch at line 184.</p>
     *
     * <p>Assumptions: the reference's read carries the {@code UPDATE} option at line 351 with
     * {@code RIDFLD(ACCT-ID)} at line 349, so it takes a lock the rewrite at line 379 then consumes.
     * That lock is not reproduced here, and it cannot be from this side: the row belongs to the account
     * context and this service holds no privilege on its schema, as the class note records. The
     * protection that replaces it is on the seam's change operation, which is given the amount to
     * subtract rather than a computed balance, so the owner applies the arithmetic against the current
     * value instead of overwriting a value this side read earlier.</p>
     *
     * <p>Assumptions: absence and failure are different answers and are reported differently, because
     * the reference answers them differently -- a not-found status is met with line 361 and any other
     * status with line 368. The seam reports absence as an empty optional and failure as its own
     * exception, and it selects no sentence itself, so the two sentences are chosen here.</p>
     *
     * @param accountId the validated account identifier to read; must not be {@code null}
     * @return the balance the account context reported; never {@code null}
     * @throws NoSuchElementException if no account carries that identifier, answered with line 361
     * @throws IllegalStateException if the account context could not answer, answered with line 368
     */
    private Money readBalance(String accountId) {
        try {
            return this.accounts.findAccountBalance(accountId)
                    .map(AccountContextClient.AccountBalance::currentBalance)
                    .orElseThrow(() -> new NoSuchElementException(
                            BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_ACCOUNT_LOOKUP_FAILED,
                    unavailable);
        }
    }

    /**
     * Resolves the card number the payment row is recorded against.
     *
     * <p>Purpose: this is {@code READ-CXACAIX-FILE} at line 408 of {@code app/cbl/COBIL00C.cbl},
     * performed from line 211 on the confirmed branch only. The reference reads the cross-reference
     * keyed by {@code XREF-ACCT-ID} at line 414, which line 171 has already loaded from the submitted
     * field.</p>
     *
     * <p>Assumptions: the sentence for a missing entry is the SAME string the account read uses. Line
     * 425 emits {@code 'Account ID NOT found...'}, character for character what lines 361 and 392 emit,
     * so one constant serves all three. The failure sentences are not the same and are not merged: line
     * 432 emits its own {@code 'Unable to lookup XREF AIX file...'} where line 368 emits the account
     * wording.</p>
     *
     * @param accountId the validated account identifier to resolve a card for; must not be {@code null}
     * @return the card number the cross-reference holds for that account; never {@code null}
     * @throws NoSuchElementException if the account has no cross-reference entry, answered with line 425
     * @throws IllegalStateException if the account context could not answer, answered with line 432
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
     * Asks the account context to reduce the balance by the amount paid.
     *
     * <p>Purpose: this is {@code UPDATE-ACCTDAT-FILE} at line 377 of {@code app/cbl/COBIL00C.cbl}, whose
     * {@code EXEC CICS REWRITE} at lines 379 to 382 consumes the lock the read for update took.</p>
     *
     * <p>Assumptions: the reference distinguishes two failures here and this method carries only one of
     * them onward. A not-found status is answered at line 392 with the same sentence the read uses, and
     * it is unreachable on this path because the read at line 343 has already established that the
     * account exists and this call follows it within the same request. Any other status is answered at
     * line 399 with the update-specific sentence, which is what a refusal from the seam becomes.</p>
     *
     * <p>Assumptions: the privilege graph this depends on is owned elsewhere and is not created here.
     * {@code data-migration/sql/V0__schemas_and_roles.sql} is the single owner of every schema, role and
     * grant in this deployment, and it grants this service's role privileges inside the {@code ledger}
     * schema only. This method therefore reaches the account master through its owner rather than
     * directly, and this class issues no grant statement of any kind.</p>
     *
     * @param accountId the validated account identifier whose balance is being reduced; must not be
     *     {@code null}
     * @param paidAmount the amount to subtract, being the whole balance as read; must not be
     *     {@code null}
     * @throws IllegalStateException if the account context refused or could not apply the change,
     *     answered with line 399, raised so that the enclosing transaction rolls the payment row back
     */
    private void applyBalanceChange(String accountId, Money paidAmount) {
        try {
            this.accounts.applyPayment(accountId, paidAmount);
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED,
                    unavailable);
        }
    }

    /**
     * Derives the identifier for the payment row as one above the highest already stored.
     *
     * <p>Purpose: this replaces the three browse paragraphs the reference uses as a maximum-key
     * generator. Lines 212 to 217 move high values into the key, start a browse at line 441, read
     * backwards once at line 472, end the browse at line 501, move the key into the numeric work field
     * {@code WS-TRAN-ID-NUM} declared at line 57, and add one. The three verbs are not a cursor -- there
     * is no {@code READNEXT} anywhere in the program -- so a single maximum-key query expresses the same
     * intent with one round trip.</p>
     *
     * <p>Refactoring Rationale: the value is derived here rather than by the repository, and it is
     * carried as digit characters rather than as a number. The repository reports the highest stored key
     * and nothing else, which is the division its own query note records; adding one is this layer's
     * work because it is where the reference performs it. The type matters because the entity's
     * identifier is a {@code String} over a fixed-width character column, and the baseline settles the
     * same question the same way -- {@code TRAN-ID} is {@code PIC X(16)} at line 5 of
     * {@code app/cpy/CVTRA05Y.cpy} while the work field it is derived through is numeric -- so the value
     * is parsed to increment and re-padded to its declared width immediately afterwards. A numeric
     * identifier would drop the leading zeros that the stored form carries.</p>
     *
     * <p>Assumptions: an empty table yields one. The reference reaches that by moving zeros into the key
     * on an end-of-file condition at line 488 and then adding one at line 217, so the first payment on an
     * empty ledger is identifier one, zero-padded to sixteen characters.</p>
     *
     * <p>Trade-offs: reading a maximum and adding one is not collision-proof, and the exposure is
     * accepted rather than removed. Two payments deriving the same value concurrently is exactly the
     * condition the reference's own duplicate-key and duplicate-record branches at lines 533 and 534
     * exist to answer, so this path relies on the same mechanism -- reported by {@link #persist} -- in
     * place of introducing an allocator the baseline has no analogue for. The compromise is that a
     * colliding caller is refused and must resubmit, which is what the reference does.</p>
     *
     * <p>Trade-offs: an exhausted key space is refused rather than wrapped. The reference's work field is
     * {@code PIC 9(16)} with no size-error clause, so adding one to the highest expressible value
     * truncates silently and would resume at zero, overwriting the oldest rows. Refusing is a documented
     * divergence chosen because the alternative destroys ledger history, and it is unreachable at any
     * realistic volume.</p>
     *
     * @return the next identifier as exactly sixteen digit characters, zero-padded; never {@code null}
     * @throws IllegalStateException if the highest stored identifier could not be read or does not hold
     *     digits, answered with the browse-failure sentence at lines 463 and 492, or if the sixteen-digit
     *     key space is exhausted
     */
    private String nextTransactionId() {
        Optional<String> highestStored;
        try {
            highestStored = this.transactions.findMaxTranId();
        } catch (RuntimeException lookupFailure) {
            // WHY : Assumptions: the browse-failure sentence is the reference's answer for this
            //       condition, and it is UPPERCASE here. Lines 463 and 492 emit 'Unable to lookup
            //       Transaction...' where the list screen's own lookup failures at lines 615, 649 and
            //       683 of app/cbl/COTRN00C.cbl emit the same words with a lower-case t. Two programs,
            //       two strings; the constant carried here is this program's.
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_TRANSACTION_LOOKUP_FAILED,
                    lookupFailure);
        }

        // WHY : Assumptions: the stored value is trimmed before it is parsed because the column is a
        //       fixed-width character type, so a driver may return it padded to its declared width. The
        //       digit test is separate from the parse so that a value which is not a number is reported
        //       with the reference's own browse-failure sentence rather than surfacing as an arithmetic
        //       complaint naming no field.
        long highest = FIRST_TRANSACTION_ID - 1L;
        if (highestStored.isPresent()) {
            String stored = highestStored.get().strip();
            if (stored.isEmpty() || !stored.chars().allMatch(Character::isDigit)) {
                throw new IllegalStateException(BillPaymentMapper.MESSAGE_TRANSACTION_LOOKUP_FAILED);
            }
            highest = Long.parseLong(stored);
        }

        long next = highest + 1L;
        if (numberOfDigits(next) > BillPaymentMapper.IDENTIFIER_WIDTH) {
            throw new IllegalStateException(BillPaymentMapper.MESSAGE_TRANSACTION_LOOKUP_FAILED);
        }

        // WHY : Assumptions: sixteen digits is the widest value this can produce and it fits a signed
        //       64-bit integer with four orders of magnitude to spare, so the increment above cannot
        //       overflow before the width guard above rejects it. That is why no arbitrary-precision
        //       type is used for what is arithmetic on a bounded key.
        return String.format("%0" + BillPaymentMapper.IDENTIFIER_WIDTH + "d", next);
    }

    /**
     * Counts the decimal digits a non-negative identifier value occupies.
     *
     * <p>Assumptions: this exists so the width guard above reads as the contract it enforces rather than
     * as a comparison against a magnitude literal. It is called only with a value derived from a stored
     * key plus one, which is why it is not written to handle a negative input.</p>
     *
     * @param value the identifier value to measure, never negative
     * @return the number of decimal digits the value occupies, at least one
     */
    private int numberOfDigits(long value) {
        return Long.toString(value).length();
    }

    /**
     * Reads the one instant both timestamp members of the payment row carry.
     *
     * <p>Purpose: this is {@code GET-CURRENT-TIMESTAMP} at line 249 of {@code app/cbl/COBIL00C.cbl},
     * performed once at line 230.</p>
     *
     * <p>Assumptions: the clock is read ONCE and the same value serves both members. Lines 231 and 232
     * are a single move statement with two receiving fields, the originating and the processing
     * timestamp, so in the reference the two are equal by construction rather than by two readings
     * landing in the same instant. Reading twice here would produce values that agree almost always and
     * disagree occasionally, which is the worst available behaviour: a parity comparison would pass
     * repeatedly and then fail once for a reason no fixture reproduces. The record boundary writes the
     * one value returned here to both members.</p>
     *
     * <p>Assumptions: the microsecond component is ZERO, and the mechanism behind that is worth stating
     * because it is not visible from the paragraph alone. The reference builds the value by formatting
     * the date and time into a group item, and the group is {@code WS-TIMESTAMP} declared at lines 42 to
     * 55 of {@code app/cpy/CSDAT01Y.cpy}. Line 263 issues {@code INITIALIZE} on that group, which does
     * not touch {@code FILLER} items, so the two separator characters those items carry as values
     * survive it -- the space at line 48 and the period at line 54 -- while the date and time separators
     * come from the {@code DATESEP} and {@code TIMESEP} options at lines 258 and 260. Line 266 then moves
     * zeros into the six-digit fractional field at line 55. The result is a twenty-six character value
     * of the form {@code 'YYYY-MM-DD HH:MM:SS.000000'}. Live seed data corroborates it: the first record
     * of {@code app/data/ASCII/dailytran.txt} carries {@code 2022-06-10 19:27:53.000000}.</p>
     *
     * <p>Assumptions: the shared formatter is read through rather than around, and then truncated once
     * more. Its own reduction is to whole microseconds, which is the resolution the twenty-six character
     * contract carries, and truncating that result to whole seconds is what reproduces line 266. Letting
     * a full-precision reading reach the row instead would store a fractional component the reference
     * never produces -- a divergence with no citation behind it, and one small enough to survive review.
     * The second truncation is idempotent with respect to the first, so the value stored and any text
     * rendered from it derive from a single reduction.</p>
     *
     * @return the instant to stamp both timestamp members with, carrying a zero fractional second; never
     *     {@code null}
     */
    private LocalDateTime paymentTimestamp() {
        return TimestampFormatter.normalizeNow(this.clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Writes the payment row, distinguishing a taken identifier from any other write failure.
     *
     * <p>Purpose: this is {@code WRITE-TRANSACT-FILE} at line 510 of {@code app/cbl/COBIL00C.cbl},
     * performed at line 233. The reference's three outcomes are reproduced: the normal path clears the
     * form at line 524, marks the message as a success at line 526 and assembles the confirmation
     * sentence at lines 527 to 530; the duplicate-key and duplicate-record statuses at lines 533 and 534
     * both answer with line 536; and any other status answers with line 543.</p>
     *
     * <p>Alternatives Considered: calling the repository's save alone and treating whatever it raises as
     * a write failure. Rejected because it would lose the duplicate answer entirely, and lose it
     * silently. The entity's identifier is assigned rather than generated, so a save with a non-null
     * identifier is a merge, and a merge against a key that already exists UPDATES that row instead of
     * refusing it -- overwriting a stranger's payment where the reference rejects. The entity carries
     * neither a version nor a new-state marker, both deliberately per its own notes, so nothing about it
     * turns the merge back into an insert. Establishing that the key is free is therefore what preserves
     * the reference's rejection.</p>
     *
     * <p>Alternatives Considered: raising this module's declared conflict type for the duplicate.
     * Rejected because that type carries a fixed set of contention conditions, each rendered with the
     * shared kernel's own wording, and none of them is this program's line 536. The integrity-violation
     * type is raised instead: the shared advice already maps it to a refusal status, so the duplicate
     * rides the existing handler exactly as it should and no handler is added here for it. The verbatim
     * sentence travels as the failure's own message so the reference's wording is carried and logged
     * rather than lost.</p>
     *
     * <p>Trade-offs: establishing that the key is free and then writing is two steps rather than one, so
     * two payments interleaving between them can still collide. The residual case is answered by the
     * table's own primary key and is reported here as the same refusal, so both routes reach one answer.
     * A narrower window is not available from this side without an allocator the baseline has no analogue
     * for, which the identifier note above records the reasoning for.</p>
     *
     * @param row the composed payment row, carrying the derived identifier as its key; must not be
     *     {@code null}
     * @return the stored row, read back so the response reports the key as persisted; never {@code null}
     * @throws DataIntegrityViolationException if the identifier is already taken, carrying the sentence
     *     the reference emits at line 536
     * @throws IllegalStateException if the row could not be written for any other reason, carrying the
     *     sentence the reference emits at line 543
     */
    private Transaction persist(Transaction row) {
        if (this.transactions.existsById(row.getTranId())) {
            throw new DataIntegrityViolationException(MESSAGE_TRANSACTION_ID_EXISTS);
        }

        try {
            return this.transactions.save(row);
        } catch (DataIntegrityViolationException duplicate) {
            // WHY : Assumptions: this is re-raised unchanged rather than wrapped, which is the whole
            //       point of catching it separately. Wrapping it would present a key collision as an
            //       unclassified failure, and the shared advice would then answer with the generic
            //       internal sentence instead of the refusal the reference reports at line 536.
            throw duplicate;
        } catch (RuntimeException writeFailure) {
            // WHY : Assumptions: the write failure is re-raised as the standard illegal-state type
            //       carrying this program's own sentence, because the shared advice renders a carried
            //       sentence only for that type. Letting the persistence failure propagate as itself
            //       would answer with the generic internal wording, and this operation's published
            //       contract promises line 543's text.
            throw new IllegalStateException(MESSAGE_PAYMENT_ADD_FAILED, writeFailure);
        }
    }
}
