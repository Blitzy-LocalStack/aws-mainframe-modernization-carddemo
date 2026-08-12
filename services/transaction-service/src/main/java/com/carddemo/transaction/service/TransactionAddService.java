package com.carddemo.transaction.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionAddOutcome;
import com.carddemo.transaction.dto.TransactionAddPreview;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Captures a new transaction, migrated from {@code app/cbl/COTRN02C.cbl}.
 *
 * <p>Purpose: this is the target of the reference program's add screen, whose 783 physical lines were
 * counted in the file itself. It resolves the submitted account identifier or card number through the
 * card cross-reference, runs the program's own validation chain, derives the next transaction identifier
 * the way the program derives it, and appends one row to the owned ledger table. Every sentence it emits
 * is carried across character for character from the program that emits it, as transformation rule T8
 * requires.</p>
 *
 * <h2>Paragraph-to-method citations</h2>
 *
 * <p>Each significant paragraph of the reference becomes one named method here, so the traceability
 * matrix can cite paragraph-to-method pairs. All ten anchors were read in the source:</p>
 *
 * <ul>
 *   <li>{@code MAIN-PARA} at line 107 has no method. It is CICS task orchestration -- a first-entry
 *       test, a map send, a map receive and an attention-identifier dispatch -- and none of those
 *       survives a stateless request. The dispatch at lines 133 to 152 becomes client-side routing.</li>
 *   <li>{@code PROCESS-ENTER-KEY} at line 164 becomes
 *       {@link #addTransaction(TransactionAddRequest)}, the one public method.</li>
 *   <li>{@code VALIDATE-INPUT-KEY-FIELDS} at line 193 becomes
 *       {@link #validateInputKeyFields(TransactionAddRequest)}.</li>
 *   <li>{@code VALIDATE-INPUT-DATA-FIELDS} at line 235 becomes
 *       {@link #validateInputDataFields(TransactionAddRequest)}, which delegates to one method per
 *       block of that paragraph.</li>
 *   <li>{@code ADD-TRANSACTION} at line 442 becomes
 *       {@link #appendTransaction(TransactionAddRequest, String)}.</li>
 *   <li>{@code COPY-LAST-TRAN-DATA} at line 471 becomes
 *       {@link #copyLastTransactionData(TransactionAddRequest)}.</li>
 *   <li>{@code READ-CXACAIX-FILE} at line 576 becomes
 *       {@link #readCardXrefByAccountId(String)}.</li>
 *   <li>{@code READ-CCXREF-FILE} at line 609 becomes
 *       {@link #readCardXrefByCardNumber(String)}.</li>
 *   <li>{@code WRITE-TRANSACT-FILE} at line 711 becomes
 *       {@link #writeTransactRecord(Transaction)}.</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} at line 762 has no method, and neither does the data-field
 *       clearing at lines 237 to 249. Both are reproduced STRUCTURALLY by the answer shape rather
 *       than by a statement, and the detail is in the section below.</li>
 * </ul>
 *
 * <h2>Two paragraphs are reproduced by the answer shape and not by a method</h2>
 *
 * <p>Trade-offs: the clearing at lines 237 to 249 blanks all eleven data fields when the error flag is
 * already on, and {@code INITIALIZE-ALL-FIELDS} at lines 762 to 779 blanks all fifteen input fields --
 * the two keys, the eleven data fields and the confirmation -- plus the message work field, positioning
 * the cursor on the account identifier at line 764. Neither is expressed as a method here, because the
 * fields they clear do not exist on this side of the boundary: the session structure they lived in does
 * not travel. What reproduces them is the ANSWER: every rejection on this path raises with one field
 * entry and one sentence and echoes nothing back, and the success answer declares three components --
 * identifier, amount and sentence -- so a client re-rendering the form from either answer has no field
 * values to restore. That is the same empty form both paragraphs produce. The compromise accepted is
 * that the cursor position has no target here, and it is recorded in this paragraph rather than lost.</p>
 *
 * <p>Assumptions: the clearing block's own guard is UNREACHABLE in the reference, which is why nothing
 * tests it. Every path that sets the flag performs the shared exit in its next statement and the exit
 * terminates the task, so the flag is never observed still on when line 237 is reached. That is measured
 * from the paths themselves -- lines 198 and 202, 212 and 216, 225 and 229 -- and not assumed from the
 * flag's declaration at line 44.</p>
 *
 * <h2>Why exactly one message reaches the caller</h2>
 *
 * <p>Assumptions: every rejection in the reference ends the CICS task rather than falling through to the
 * next test, so a submission produces exactly ONE message however many of its fields are wrong. The
 * evidence is the shared exit itself: every rejection performs {@code SEND-TRNADD-SCREEN}, whose last
 * act is {@code EXEC CICS RETURN} at lines 530 to 534, and that verb terminates the task instead of
 * returning to the paragraph that performed it. The consequence reaches every construct in the program
 * -- the key-field evaluation at lines 195 to 230, the eleven-way mandatory chain at lines 251 to 320,
 * and each of the six constructs after it -- so the priority order in which they are written IS the
 * contract, and this class runs them in that order and raises on the first fault it finds.</p>
 *
 * <p>Alternatives Considered: collecting every fault and answering with a populated field array was
 * evaluated and rejected. It reads as the more helpful answer and it is the shape
 * {@link TransactionAddRequest}'s own bean constraints produce, because a constraint set aggregates by
 * design. It would report faults this screen never reports: a submission blank in three fields would
 * answer with three entries where the reference answers with the highest-priority one alone, and a
 * client rendering the array against the form would mark three controls the operator was never told
 * about. Aggregation is a parity failure rather than an improvement, so this class answers with a
 * ONE-ELEMENT array and the declarative constraint set is left to do the shape work it can do without
 * reading anything.</p>
 *
 * <h2>The order the three phases run in</h2>
 *
 * <p>Refactoring Rationale: the key fields are validated first, the data fields second and the
 * confirmation last, which is the order {@code PROCESS-ENTER-KEY} performs them in -- line 166, then
 * line 167, then the evaluation opening at line 169. Alternatives Considered: testing the confirmation
 * FIRST, on the ground that resolving the cross-reference before knowing whether the operator confirmed
 * spends a cross-context read on a turn that writes nothing. Rejected because the reordering is
 * OBSERVABLE: a submission carrying neither key and a refused confirmation answers with the key
 * complaint at line 226 in the reference, and would answer with the confirmation prompt at line 178
 * under the reordering. The read the saving avoids is one the reference performs on every turn, so the
 * saving is not parity-neutral.</p>
 *
 * <h2>The confirmation field has three branches here, not four</h2>
 *
 * <p>Assumptions: the evaluation at lines 169 to 188 has three arms and the middle one is shared.
 * {@code 'Y'} and {@code 'y'} at lines 170 and 171 perform the append. {@code 'N'}, {@code 'n'},
 * spaces and low values at lines 173 to 176 ALL fall into one arm which sets the error flag at line 177
 * and asks {@code 'Confirm to add this transaction...'} at line 178. Anything else reaches
 * {@code WHEN OTHER} at line 182 and reports the value-domain complaint at line 184.</p>
 *
 * <p>Assumptions: the refusal spelling therefore DOES emit a message on this screen, and the payment
 * screen arranges the same field differently -- {@code app/cbl/COBIL00C.cbl} gives {@code 'N'} and
 * {@code 'n'} their own arm at lines 178 and 181, which clears the screen and sets the flag and emits
 * NO message, while spaces and low values get a third arm of their own at lines 182 to 184. Three arms
 * here against four there, and a message here against none there. The contrast is recorded because the
 * two evaluations look alike enough to be unified by a later reader, and unifying them would change one
 * of the two screens.</p>
 *
 * <h2>What does not travel</h2>
 *
 * <p>Assumptions: the session structure at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 does not travel.
 * Identity arrives as a validated token claim rather than as a field the client echoed, the selection
 * context arrives in the request, and the re-entry discriminator at lines 29 to 31 of that copybook
 * disappears entirely -- a stateless handler that answers with a field array has no first-entry against
 * re-entry distinction to draw. That last removal is why the field highlight this class describes is
 * driven by the answer alone and not by a remembered turn count, which is what
 * {@code app/cpy/CSSETATY.cpy} lines 17 to 27 gate it on.</p>
 *
 * <p>Assumptions: attention-identifier dispatch is client-side and no method here answers a function
 * key. The mapping is recorded rather than implemented: line 146 dispatches PF5 to
 * {@code COPY-LAST-TRAN-DATA} and lines 156 to 159 end the turn. PF5 means something different on every
 * screen of this context -- {@code app/cbl/COTRN01C.cbl} lines 125 to 127 navigate to the browse screen
 * with it and {@code app/cbl/COBIL00C.cbl} lines 125 to 142 declare no PF5 at all -- so it never means
 * save, and {@link #copyLastTransactionData(TransactionAddRequest)} is reachable as an operation rather
 * than as a key.</p>
 */
@Service
public class TransactionAddService {

    /** The confirmation value the reference accepts in upper case at line 170. */
    public static final String CONFIRM_YES_UPPER = "Y";

    /** The confirmation value the reference accepts in lower case at line 171. */
    public static final String CONFIRM_YES_LOWER = "y";

    /** The confirmation refusal the reference accepts in upper case at line 173. */
    public static final String CONFIRM_NO_UPPER = "N";

    /** The confirmation refusal the reference accepts in lower case at line 174. */
    public static final String CONFIRM_NO_LOWER = "n";

    /** The request member the confirmation discriminator is keyed by. */
    public static final String FIELD_CONFIRMATION = "confirmation";

    /** The request member the account identifier is keyed by. */
    public static final String FIELD_ACCOUNT_ID = "accountId";

    /** The request member the card number is keyed by. */
    public static final String FIELD_CARD_NUMBER = "cardNumber";

    /** The request member the transaction type code is keyed by. */
    public static final String FIELD_TYPE_CODE = "typeCode";

    /** The request member the transaction category code is keyed by. */
    public static final String FIELD_CATEGORY_CODE = "categoryCode";

    /** The request member the source is keyed by. */
    public static final String FIELD_SOURCE = "source";

    /** The request member the description is keyed by. */
    public static final String FIELD_DESCRIPTION = "description";

    /** The request member the amount is keyed by. */
    public static final String FIELD_AMOUNT = "amount";

    /** The request member the merchant identifier is keyed by. */
    public static final String FIELD_MERCHANT_ID = "merchantId";

    /** The request member the merchant name is keyed by. */
    public static final String FIELD_MERCHANT_NAME = "merchantName";

    /** The request member the merchant city is keyed by. */
    public static final String FIELD_MERCHANT_CITY = "merchantCity";

    /** The request member the merchant postal code is keyed by. */
    public static final String FIELD_MERCHANT_ZIP = "merchantZip";

    /** The request member the origination date is keyed by. */
    public static final String FIELD_ORIGIN_DATE = "originDate";

    /** The request member the processing date is keyed by. */
    public static final String FIELD_PROCESS_DATE = "processDate";

    /** The prompt the reference emits at line 178 for a refused, blank or low-value confirmation. */
    public static final String MESSAGE_CONFIRM_ADD = "Confirm to add this transaction...";

    /**
     * The complaint the reference emits at line 184 for any other confirmation value.
     *
     * <p>Assumptions: this aliases the constant the request record already declares rather than
     * repeating the literal, because transformation rule T2 gives every contract one owner and a
     * user-visible string with two declarations can drift in one of them. The alias is kept because the
     * unit tests of this class name it here, where the branch that emits it lives.</p>
     */
    public static final String MESSAGE_INVALID_CONFIRMATION =
            TransactionAddRequest.CONFIRM_INVALID_VALUE;

    /** The complaint the reference emits at line 226 when neither key was supplied. */
    public static final String MESSAGE_KEY_REQUIRED = TransactionAddRequest.KEY_FIELD_REQUIRED;

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

    /**
     * The failed-read report the reference emits at lines 664 and 693 while deriving the key.
     *
     * <p>Assumptions: the two occurrences are one sentence and the reference writes the same characters
     * at both, once for a failed browse start and once for a failed backward read. It is spelled with a
     * capital in this program, and {@code app/cbl/COTRN00C.cbl} spells its own lower case at lines 615,
     * 649 and 683, so the two are separate constants in separate classes and neither is normalised onto
     * the other.</p>
     */
    public static final String MESSAGE_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /**
     * The failed-write report the reference emits at line 745.
     *
     * <p>Assumptions: this is NOT the payment screen's sentence.
     * {@code app/cbl/COBIL00C.cbl} line 543 writes {@code 'Unable to Add Bill pay Transaction...'},
     * which names the operation it failed at. Two programs, two sentences, and rule T8 carries each
     * across as written.</p>
     */
    public static final String MESSAGE_ADD_FAILED = "Unable to Add Transaction...";

    /**
     * The calendar rejection the reference emits for the origination date at line 401.
     *
     * <p>Assumptions: this is a THIRD distinct date sentence and not a spelling of the lexical one. The
     * lexical complaint at line 360 names the form a date must take; this one reports that a
     * well-formed date denotes no calendar day. Both survive as separate constants because the
     * reference emits them from separate constructs and a client can act on the difference.</p>
     */
    public static final String MESSAGE_ORIGIN_DATE_INVALID = "Orig Date - Not a valid date...";

    /** The calendar rejection the reference emits for the processing date at line 421. */
    public static final String MESSAGE_PROCESS_DATE_INVALID = "Proc Date - Not a valid date...";

    /** The first fragment of the acknowledgement the reference composes at line 728. */
    public static final String MESSAGE_ADDED_PREFIX = "Transaction added successfully. ";

    /** The second fragment of that acknowledgement, from line 730, including both of its spaces. */
    public static final String MESSAGE_ADDED_INFIX = " Your Tran ID is ";

    /** The final fragment of that acknowledgement, from line 732. */
    public static final String MESSAGE_ADDED_SUFFIX = ".";

    /** The declared width of the transaction key, from {@code app/cpy/CVTRA05Y.cpy} line 5. */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /** The declared width of the account identifier, from {@code app/cpy-bms/COTRN02.CPY} line 60. */
    public static final int ACCOUNT_ID_WIDTH = TransactionAddRequest.ACCOUNT_ID_WIDTH;

    /** The declared width of the card number, from {@code app/cpy-bms/COTRN02.CPY} line 66. */
    public static final int CARD_NUMBER_WIDTH = TransactionAddRequest.CARD_NUMBER_WIDTH;

    /**
     * The number of characters the reference's edited amount field holds.
     *
     * <p>Assumptions: sixteen digit characters hold at most {@code 9999999999999999}, and
     * {@code V2__ledger_transaction_id_allocator.sql} declares exactly that as the sequence's
     * {@code MAXVALUE} so an allocation that would not fit the column fails at the allocator. This
     * constant states the same bound on the rendering side, because a value that did not fit would be
     * truncated into an identifier that collides with a stored one rather than failing.</p>
     */
    public static final long MAX_TRANSACTION_ID = 9_999_999_999_999_999L;

    /**
     * The number of decimal digits every amount picture in this flow holds.
     *
     * <p>Assumptions: two is not in dispute between the two widths this class had to reconcile. The
     * reference's edited picture {@code WS-TRAN-AMT-E PIC +99999999.99} at line 59 of
     * {@code app/cbl/COTRN02C.cbl}, its parsed field {@code WS-TRAN-AMT-N PIC S9(9)V99} at line 58 and
     * the record's {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy} all declare
     * two, and the shared money type fixes scale two at the wire boundary, so this is the one part of the
     * shape that needs no adjudication.</p>
     */
    public static final int EDITED_AMOUNT_FRACTION_DIGITS = 2;

    /**
     * The number of integer digits the transaction RECORD holds, which is the domain this service admits.
     *
     * <p>Assumptions: {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy} declares
     * nine, where the screen's edited picture at line 59 of {@code app/cbl/COTRN02C.cbl} declares eight.
     * The two differ in the baseline itself -- the reference parses the submitted characters into
     * {@code WS-TRAN-AMT-N PIC S9(9)V99} at line 58, nine digits, and only its ECHO is narrower -- and
     * {@code app/cbl/COBIL00C.cbl} pairs the same eight-digit transaction edit at line 55 with a
     * ten-digit balance edit at line 56, so no screen width is a general rule.</p>
     *
     * <p>Refactoring Rationale: this class measured the eight-digit SCREEN shape while
     * {@code TransactionAddRequest} and the published contract both admit the record's nine, under
     * divergence D-AMOUNT-RECORD-WIDTH registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. Two authorities disagreed on one field
     * and the narrower one ran second, so a nine-digit amount cleared the boundary and was then refused
     * here with a format sentence: input the contract published as valid, rejected after deserialization,
     * and a divergence the register described as delivered that was not. The record's width is now the
     * single authority for what this service accepts; how many digits a client CHOOSES to render belongs
     * to that client, and a 3270-faithful one still renders the reference's twelve characters.</p>
     *
     * <p>Alternatives Considered: narrowing the boundary to eight digits instead would have made the two
     * authorities agree just as well, and was rejected on two grounds. It would withdraw a published
     * contract domain that {@code TransactionApiContractTest} asserts and the register publishes, which
     * is a breaking change to settle an internal inconsistency; and it would refuse amounts the RECORD
     * can hold, so a value loadable by the ETL from a baseline extract would be unaddable through the
     * API -- a narrower domain than the data it stores.</p>
     */
    public static final int RECORD_AMOUNT_INTEGER_DIGITS = 9;

    /**
     * The number of characters an amount rendered at the record's width occupies.
     *
     * <p>Assumptions: a sign, nine integer digits, a point and two decimals is thirteen, one more than
     * the reference screen's twelve. The positional test below measures this form, so the test stays
     * positional -- as the reference's is, rather than becoming an arithmetic range comparison -- while
     * measuring the record's domain rather than the screen's. The expression is written out from its
     * parts instead of spelled as a literal so the two cannot drift.</p>
     */
    public static final int RECORD_AMOUNT_LENGTH =
            1 + RECORD_AMOUNT_INTEGER_DIGITS + 1 + EDITED_AMOUNT_FRACTION_DIGITS;

    /** The sign the reference's edited amount picture renders a negative value with. */
    public static final char EDITED_AMOUNT_NEGATIVE_SIGN = '-';

    /** The sign the reference's edited amount picture renders a non-negative value with. */
    public static final char EDITED_AMOUNT_POSITIVE_SIGN = '+';

    /** The decimal point the positional test at line 342 requires at the tenth character. */
    public static final char EDITED_AMOUNT_DECIMAL_POINT = '.';

    /**
     * The number of characters the two date fields hold.
     *
     * <p>Assumptions: ten is the width of {@code TORIGDTI PIC X(10)} at
     * {@code app/cpy-bms/COTRN02.CPY} line 102 and of {@code TPROCDTI PIC X(10)} at line 108, and it is
     * the sum the five positional tests at lines 354 to 358 address.</p>
     */
    public static final int ISO_DATE_LENGTH = 10;

    /** The separator the positional tests at lines 355 and 357 require. */
    public static final char ISO_DATE_SEPARATOR = '-';

    /** The stored rows this service appends to and derives its next key from. */
    private final TransactionRepository transactions;

    /** The account-owned records this service resolves a card number through. */
    private final AccountContextClient accounts;

    /** The boundary that turns a submitted shape into a row and a row into an answer. */
    private final TransactionMapper transactionMapper;

    /** The unit of work the key derivation and the append share, opened after the outbound read. */
    private final TransactionTemplate writeTransaction;

    /**
     * Builds the service over its four collaborators.
     *
     * <p>Alternatives Considered: the cross-reference this screen reads at lines 576 and 609 belongs to
     * the ACCOUNT context, which owns {@code account.card_xref} and the by-account index that replaces
     * the {@code CXACAIX} alternate index, so reaching it needs a seam and two seams were rejected
     * before this one was chosen. A Maven dependency on {@code account-service} was rejected twice
     * over: it is prohibited outright, and it would replace a network boundary the deployment topology
     * actually has with a compile-time edge, so a failure mode that exists in production would have no
     * expression in the code. Copying {@code card_xref} into the {@code ledger} schema was rejected
     * because it splits ownership of one table across two deployables, which is the failure that
     * bounded contexts exist to prevent, and it would leave two rows to reconcile after every
     * maintenance write the account screens perform.</p>
     *
     * <p>Refactoring Rationale: the seam is injected as the interface rather than built here, and the
     * timeout-carrying client behind it is built in {@link RestAccountContextClient}. The sibling
     * configuration package is closed at three classes and records the absence of a client
     * configuration together with its reason, so the client cannot be built there; and this package's
     * charter closes its own inventory, so it cannot be built in a new helper either. Building it in
     * this constructor instead would put one HTTP concern in two classes of this package, because the
     * payment screen reads the same three records through the same seam.</p>
     *
     * <p>Trade-offs: there is no circuit breaker and no resilience library on that hop, and the
     * omission is deliberate. The call is in-VPC to an internal load balancer under an explicit connect
     * and read timeout, so a breaker would add a failure mode -- a tripped breaker refusing calls the
     * dependency would have answered -- without removing one, and the timeouts already bound the
     * latency a caller can be made to wait. A declarative retry annotation is likewise absent: its
     * enabling annotation is the framework's own {@code @EnableResilientMethods}, which must sit on a
     * configuration class this package may not add to, and the attribute a use site would carry is
     * {@code maxRetries}, whose total attempts are one plus its value.</p>
     *
     * <p>Refactoring Rationale: the write boundary is a programmatic template rather than an annotation
     * on the public method, and the difference is what keeps the outbound read outside it. The reference
     * declares no {@code SYNCPOINT} anywhere in the program -- the CICS task's own implicit syncpoint at
     * {@code EXEC CICS RETURN} commits it -- so the unit of work to reproduce is the key derivation and
     * the one file write of {@code ADD-TRANSACTION}, and nothing before them. An annotation on
     * {@link #addTransaction(TransactionAddRequest)} would enclose the cross-reference read as well,
     * which holds a pooled database connection across a network wait; and an annotation on the private
     * append could not open a boundary at all, because the framework's advice is applied by a proxy that
     * an in-class call does not pass through, so it would read as a boundary while being none.</p>
     *
     * @param transactions the repository over the owned transaction table; must not be {@code null}
     * @param accounts the seam onto the account context's cross-reference; must not be {@code null}
     * @param transactionMapper the record boundary; must not be {@code null}
     * @param transactionManager the manager the write template is built over; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionAddService(TransactionRepository transactions, AccountContextClient accounts,
            TransactionMapper transactionMapper, PlatformTransactionManager transactionManager) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.transactionMapper =
                Objects.requireNonNull(transactionMapper, "transactionMapper must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");

        // WHY : Assumptions: the propagation is REQUIRES_NEW so the derive-and-append span is a unit of
        //       work of its own even when a caller already holds one. The reference's span is one CICS
        //       task, which is never nested inside another, so an enclosing transaction must not be able
        //       to widen the window between reading the maximum key and inserting the row it derives.
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Captures the submitted transaction, or answers with the message the reference answers with.
     *
     * <p>This transcribes {@code PROCESS-ENTER-KEY} at line 164 of {@code app/cbl/COTRN02C.cbl}: it
     * validates the key fields at line 166, validates the data fields at line 167, and then evaluates
     * the confirmation across lines 169 to 188, performing the append only from the affirmative arm at
     * line 172.</p>
     *
     * <p>Refactoring Rationale: the cross-reference read is resolved BEFORE the transactional boundary
     * opens, and the boundary covers the append alone. A read over HTTP inside a database transaction
     * holds a pooled connection for the whole of a network wait, so a slow account context would consume
     * the write pool of every task in this service rather than only the request that is waiting. The
     * reference has no equivalent exposure because its file read and its file write are both local to
     * the region, so keeping the read outside is what preserves the reference's isolation properties
     * rather than what departs from them.</p>
     *
     * <p>Assumptions: the append is a single-row insert into the owned {@code ledger} schema and no
     * cross-schema write happens on this path. The balance-affecting write belongs to the payment screen
     * and its own service, and {@code ADD-TRANSACTION} at line 442 writes exactly one file.</p>
     *
     * @param request the submitted capture, whose shape the API layer has already constrained; must not
     *     be {@code null}
     * @return {@link TransactionAddResponse} carrying the generated identifier, the normalised amount
     *     and the composed sentence when the capture was confirmed and appended; otherwise
     *     {@link TransactionAddPreview} carrying the normalised amount, the discriminator fixed false
     *     and the prompt; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if a key field, a data field or the confirmation carries a value the
     *     reference refuses, each carrying that program's own sentence and naming one field
     * @throws NoSuchElementException if the supplied key resolves to no cross-reference entry, carrying
     *     the absence sentence for the key that was supplied
     * @throws RecordConflictException if the derived identifier is already stored, carrying the
     *     duplicate sentence the reference emits for both of its duplicate conditions
     * @throws IllegalStateException if a read or the append failed for a reason the caller cannot
     *     correct, carrying the sentence the reference emits for that operation
     */
    public TransactionAddOutcome addTransaction(TransactionAddRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String resolvedCardNumber = validateInputKeyFields(request);
        Money canonicalAmount = validateInputDataFields(request);

        String confirmation = request.confirmation();
        if (!isAffirmative(confirmation)) {
            // WHY : Assumptions: the two non-affirmative arms are separated here exactly as the
            //       reference separates them, and they are separated by STATUS as well as by sentence.
            //       The shared arm at lines 173 to 176 asks for a confirmation and re-sends the same
            //       screen, so nothing about the submission was refused and it is answered as a 200
            //       carrying the prompt. The WHEN OTHER arm at line 182 refuses a value the field's
            //       domain does not admit, so it is answered as a rejection naming that field.
            if (isRefusedOrNeverSupplied(confirmation)) {
                // WHY : Assumptions: the prompt answer is composed directly rather than through the
                //       mapper, because the mapper converts a STORED ROW and this arm has appended
                //       none -- its own contract refuses a row without an identifier. The identifier
                //       component is therefore absent, and the amount echoed is the CANONICAL one the
                //       sixth block derived, which is what line 386 moves back onto the screen field
                //       before the confirmation is ever evaluated at line 169.
                // WHY : ⚠️ Refactoring Rationale: the PREVIEW shape is returned here, where the capture
                //       shape used to be returned with its identifier left null. The published
                //       TransactionAddPreview schema declares no transactionId and closes its object, so
                //       the old body was invalid against its own contract and a strict client rejected
                //       it; that schema also marks `written` required, and the capture record carries no
                //       such member, so a client reading the body alone had to infer a prompt from a
                //       missing identifier. This shape carries both, and its discriminator is fixed at
                //       its own factory.
                return TransactionAddPreview.prompting(canonicalAmount, MESSAGE_CONFIRM_ADD);
            }
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_CONFIRMATION,
                    FieldValidationFlag.NOT_OK, MESSAGE_INVALID_CONFIRMATION);
        }

        return appendTransaction(request, resolvedCardNumber);
    }

    /**
     * Re-captures the most recently stored transaction into a fresh submission and appends it.
     *
     * <p>This transcribes {@code COPY-LAST-TRAN-DATA} at line 471 of {@code app/cbl/COTRN02C.cbl}: it
     * validates the key fields at line 473, positions past the end of the file and reads one record
     * backwards across lines 475 to 478, copies eleven fields out of that record across lines 480 to
     * 493, and then performs {@code PROCESS-ENTER-KEY} at line 495.</p>
     *
     * <p>Refactoring Rationale: the re-entry at line 495 means the copied values go through the FULL
     * validation chain rather than a shortened one, so this method funnels into
     * {@link #addTransaction(TransactionAddRequest)} rather than into the append directly. Line 493 is
     * the {@code END-IF} of the copy block and line 494 is blank, so the performing statement is at 495;
     * the distinction matters because a citation one line early would attribute the re-entry to the end
     * of a conditional and hide that the copy path is validated at all.</p>
     *
     * <p>Assumptions: the copy overwrites the eleven data fields of the submission and leaves the two
     * key fields and the confirmation as submitted, because lines 482 to 492 move only the record's own
     * data columns and line 481 renders its amount through the edited picture first. A submission whose
     * data fields were already populated therefore loses them, which is the reference behaviour: the
     * copy is a replacement rather than a merge.</p>
     *
     * @param request the submission whose key fields select the account or card and whose confirmation
     *     decides whether the copied capture is appended; must not be {@code null}
     * @return the same outcome {@link #addTransaction(TransactionAddRequest)} returns for the copied
     *     capture, being {@link TransactionAddResponse} when it was confirmed and appended and
     *     {@link TransactionAddPreview} when it was not; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if a key field, a copied data field or the confirmation carries a
     *     value the reference refuses
     * @throws NoSuchElementException if the supplied key resolves to no cross-reference entry, or if the
     *     table holds no row to copy
     * @throws RecordConflictException if the derived identifier is already stored
     * @throws IllegalStateException if a read or the append failed for a reason the caller cannot
     *     correct
     */
    public TransactionAddOutcome copyLastTransactionData(TransactionAddRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateInputKeyFields(request);

        Transaction latest = readLatestTransaction()
                .orElseThrow(() -> new NoSuchElementException(MESSAGE_TRANSACTION_LOOKUP_FAILED));

        return addTransaction(copiedSubmission(request, latest));
    }

    /**
     * Copies eleven data fields out of the stored row into a fresh submission.
     *
     * <p>This transcribes the copy block at lines 480 to 493 of {@code app/cbl/COTRN02C.cbl}. It moves
     * the type code, category code and source at lines 482 to 484, the amount rendered through the edited
     * picture at lines 481 and 485, the description at 486, the two dates at 487 and 488 and the four
     * merchant fields at 489 to 492. The two key fields and the confirmation are carried from the
     * submission because the block moves nothing into them.</p>
     *
     * <p>Assumptions: the two dates are taken as DATES ONLY, discarding the time the stored columns can
     * hold. Lines 487 and 488 move a {@code PIC X(26)} record field into a {@code PIC X(10)} screen
     * field, which truncates to the leftmost ten characters, and for a row this screen wrote those ten
     * characters are the whole of the stored value -- so the copy round-trips exactly, and for a row the
     * batch path wrote it takes the date and drops the time exactly as the reference does.</p>
     *
     * <p>Assumptions: four of the columns copied are NULLABLE in the target schema and have no absent
     * spelling in the reference, where a zoned or display field always decodes to a value. A row carrying
     * an absent one is therefore not a row this screen could have written, and the copy reports the
     * reference's own failed-read sentence rather than composing a submission around a value it does not
     * have. That is a documented divergence from a case the reference cannot represent, chosen over the
     * alternative of substituting a zero or a blank, which would append a row the operator never saw.</p>
     *
     * @param request the submission whose key fields and confirmation are carried forward; must not be
     *     {@code null}
     * @param latest the stored row whose eleven data fields are copied; must not be {@code null}
     * @return a submission carrying the copied data fields, never {@code null}
     * @throws IllegalStateException if the stored row leaves one of the copied fields absent
     */
    private static TransactionAddRequest copiedSubmission(TransactionAddRequest request,
            Transaction latest) {

        Long merchantId = latest.getMerchantId();
        if (merchantId == null || latest.getTranAmt() == null || latest.getOrigTs() == null
                || latest.getProcTs() == null) {
            throw new IllegalStateException(MESSAGE_TRANSACTION_LOOKUP_FAILED);
        }

        return new TransactionAddRequest(request.accountId(), latest.getTranTypeCd(),
                latest.getTranCatCd(), latest.getTranSource(), latest.getTranDesc(),
                Money.of(latest.getTranAmt()),
                zeroPadded(merchantId, TransactionAddRequest.MERCHANT_ID_WIDTH),
                latest.getMerchantName(), latest.getMerchantCity(), latest.getMerchantZip(),
                request.cardNumber(), latest.getOrigTs().toLocalDate().toString(),
                latest.getProcTs().toLocalDate().toString(), request.confirmation());
    }

    /**
     * Resolves the card number the appended row is keyed by from whichever key the submission carried.
     *
     * <p>This transcribes {@code VALIDATE-INPUT-KEY-FIELDS} at lines 193 to 230 of
     * {@code app/cbl/COTRN02C.cbl}. The construct at line 195 is an {@code EVALUATE TRUE}, so the FIRST
     * arm whose condition holds is the only arm that runs: the account identifier at line 196, then the
     * card number at line 210, then the complaint at line 224.</p>
     *
     * <p>Trade-offs: a submission carrying BOTH keys resolves through the account identifier alone, and
     * the card number it supplied is SILENTLY DISCARDED. The account arm reads the cross-reference at
     * line 208 and then moves the resolved card number straight over the submitted one at line 209, so
     * the row is written with the cross-reference's card and the operator is told nothing. The compromise
     * accepted is that a client can send a card number that disagrees with the account and receive a
     * success naming neither the disagreement nor the value that won. Reporting the conflict was rejected
     * because the reference emits no message for it and adding one would refuse submissions this screen
     * accepts today; the discard is behaviour rather than a defect, so it is preserved and recorded.</p>
     *
     * <p>Assumptions: the account identifier the card arm back-fills at line 223 has no target in the
     * answer, because the acknowledgement declares three components and none of them is an account. The
     * back-fill is a screen effect of a paragraph that also serves the account arm, and nothing
     * downstream of this method reads it, so this method returns the card number alone.</p>
     *
     * @param request the submitted capture, whose account identifier and card number are read; must not
     *     be {@code null}
     * @return the card number the cross-reference resolved, as sixteen digit characters, never
     *     {@code null}
     * @throws ClientInputException if the supplied key is not composed of digits, or if neither key was
     *     supplied
     * @throws NoSuchElementException if the supplied key resolves to no cross-reference entry
     * @throws IllegalStateException if the lookup could not be performed
     */
    private String validateInputKeyFields(TransactionAddRequest request) {
        if (!FieldValidationFlag.isNeverSupplied(request.accountId())) {
            // WHY : Assumptions: the numeric test at line 197 runs BEFORE the conversion at line 204,
            //       because FUNCTION NUMVAL has no defined result for characters that are not a number
            //       and the reference guards it rather than relying on one.
            String accountId = numericValueOf(request.accountId(), ACCOUNT_ID_WIDTH,
                    FIELD_ACCOUNT_ID, TransactionAddRequest.ACCOUNT_ID_NOT_NUMERIC);
            return readCardXrefByAccountId(accountId);
        }

        if (!FieldValidationFlag.isNeverSupplied(request.cardNumber())) {
            String cardNumber = numericValueOf(request.cardNumber(), CARD_NUMBER_WIDTH,
                    FIELD_CARD_NUMBER, TransactionAddRequest.CARD_NUMBER_NOT_NUMERIC);
            return readCardXrefByCardNumber(cardNumber);
        }

        // WHY : Assumptions: the state is the blank one rather than the rejected-value one, because the
        //       reference reaches this arm when neither control was filled in and the shared kernel
        //       draws the asterisk of app/cpy/CSSETATY.cpy lines 23 to 25 only for that state. The
        //       account identifier is the field named because line 228 positions the cursor there.
        throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_ACCOUNT_ID,
                FieldValidationFlag.BLANK, MESSAGE_KEY_REQUIRED);
    }

    /**
     * Converts one submitted key to the digits-only, zero-padded form the reference converts it to.
     *
     * <p>This transcribes the two identical three-statement sequences the key arms perform: the numeric
     * test at line 197 and the conversion at line 204 for the account identifier, and the test at line
     * 211 and the conversion at line 218 for the card number. Each converts with
     * {@code FUNCTION NUMVAL} and then moves the numeric work field back over the input field, at lines
     * 206 to 207 and 220 to 221, which left-pads the value with zeros to the field's declared width.</p>
     *
     * <p>Assumptions: {@code FUNCTION NUMVAL} and not {@code FUNCTION NUMVAL-C} is what the two key arms
     * use, and the difference is preserved rather than smoothed over. The currency-tolerant form appears
     * only on the money field, at lines 383 and 456, so an identifier carrying a currency or grouping
     * character is refused here where the same character would be tolerated there.</p>
     *
     * @param submitted the value as the client sent it, already known to be present; must not be
     *     {@code null}
     * @param width the declared width of the field, to which the converted value is left-padded
     * @param field the request member the value came from, used to key the refusal; must not be
     *     {@code null}
     * @param message the sentence the reference emits when the value is not a number; must not be
     *     {@code null}
     * @return the value as exactly {@code width} digit characters, never {@code null}
     * @throws ClientInputException if the value holds a character that is not a digit, or is wider than
     *     the declared width
     */
    private static String numericValueOf(String submitted, int width, String field, String message) {
        String trimmed = submitted.trim();
        if (trimmed.isEmpty() || trimmed.length() > width || !isAllDigits(trimmed)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, message);
        }
        return zeroPadded(Long.parseLong(trimmed), width);
    }

    /**
     * Reads the card cross-reference by account identifier and reports its three outcomes.
     *
     * <p>This transcribes {@code READ-CXACAIX-FILE} at lines 576 to 604 of
     * {@code app/cbl/COTRN02C.cbl}. That read is keyed by the account identifier at line 582, so it
     * travels the {@code CXACAIX} alternate-index path, which the target expresses as the by-account
     * index on {@code account.card_xref}. Its three outcomes are the normal read at line 589, the
     * absence at line 591 whose sentence is at line 593, and every other condition at line 597 whose
     * sentence is at line 600.</p>
     *
     * @param accountId the account identifier as eleven digit characters; must not be {@code null}
     * @return the card number the cross-reference entry carries, never {@code null}
     * @throws NoSuchElementException if the account identifier resolves to no entry
     * @throws IllegalStateException if the read could not be performed
     */
    private String readCardXrefByAccountId(String accountId) {
        return resolveCardNumber(() -> this.accounts.findCardXrefByAccountId(accountId),
                MESSAGE_ACCOUNT_NOT_FOUND, MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED);
    }

    /**
     * Reads the card cross-reference by card number and reports its three outcomes.
     *
     * <p>This transcribes {@code READ-CCXREF-FILE} at lines 609 to 637 of
     * {@code app/cbl/COTRN02C.cbl}. That read is keyed by the card number at line 615, so it travels the
     * base path of the cross-reference rather than the alternate index. Its three outcomes are the normal
     * read at line 622, the absence at line 624 whose sentence is at line 626, and every other condition
     * at line 630 whose sentence is at line 633.</p>
     *
     * @param cardNumber the card number as sixteen digit characters; must not be {@code null}
     * @return the card number the cross-reference entry carries, never {@code null}
     * @throws NoSuchElementException if the card number resolves to no entry
     * @throws IllegalStateException if the read could not be performed
     */
    private String readCardXrefByCardNumber(String cardNumber) {
        return resolveCardNumber(() -> this.accounts.findCardXrefByCardNumber(cardNumber),
                MESSAGE_CARD_NOT_FOUND, MESSAGE_CARD_XREF_LOOKUP_FAILED);
    }

    /**
     * Performs one cross-reference read and converts its three outcomes into this screen's answers.
     *
     * <p>Assumptions: an absence and a failure are DIFFERENT answers and the reference distinguishes
     * them, so the two sentences are supplied by the caller rather than merged here. An absence is a
     * condition the caller can act on by supplying another key; a failure is one it cannot act on at all.
     * A third sentence is never invented: a response the account context refuses for any reason other
     * than absence reports the caller's own failed-read sentence.</p>
     *
     * @param read the cross-reference read to perform, deferred so that both directions share this
     *     outcome handling; must not be {@code null}
     * @param absentMessage the sentence for a key that resolves to nothing; must not be {@code null}
     * @param failedMessage the sentence for a read that could not be performed; must not be {@code null}
     * @return the resolved card number, never {@code null}
     * @throws NoSuchElementException if the key resolves to nothing
     * @throws IllegalStateException if the read could not be performed
     */
    private static String resolveCardNumber(Supplier<Optional<AccountContextClient.CardXref>> read,
            String absentMessage, String failedMessage) {
        try {
            return read.get()
                    .map(AccountContextClient.CardXref::cardNumber)
                    .orElseThrow(() -> new NoSuchElementException(absentMessage));
        } catch (AccountContextClient.AccountContextUnavailableException unavailable) {
            throw new IllegalStateException(failedMessage, unavailable);
        }
    }

    /**
     * Runs the eight validation blocks of the data-field paragraph in the order they are written.
     *
     * <p>This transcribes {@code VALIDATE-INPUT-DATA-FIELDS} at lines 235 to 437 of
     * {@code app/cbl/COTRN02C.cbl}, whose body is eight consecutive blocks. Each block below is its own
     * named method carrying its own line span, so a block-to-method pair can be cited as precisely as a
     * paragraph-to-method one.</p>
     *
     * <p>Assumptions: the blocks run in written order and the first fault ends the turn, for the reason
     * recorded on this class: every rejection performs the shared exit whose {@code EXEC CICS RETURN} at
     * lines 530 to 534 terminates the task. Each method below therefore raises rather than returning a
     * verdict, which is what makes the order load-bearing instead of decorative.</p>
     *
     * @param request the submitted capture, whose eleven data fields are validated; must not be
     *     {@code null}
     * @return the canonical amount the sixth block derives, which is the value every answer on this path
     *     echoes back, never {@code null}
     * @throws ClientInputException if any data field carries a value the reference refuses, naming that
     *     one field and carrying that one sentence
     */
    private static Money validateInputDataFields(TransactionAddRequest request) {
        requireEveryMandatoryDataField(request);
        requireNumericCodeFields(request);
        requireEditedAmountShape(request);
        requireLexicalDateShapes(request);
        Money canonicalAmount = canonicaliseAmount(request);
        requireCalendarValidDates(request);
        requireNumericMerchantId(request);

        return canonicalAmount;
    }

    /**
     * Requires all eleven data fields to be present, in the reference's own priority order.
     *
     * <p>This transcribes the block at lines 251 to 320 of {@code app/cbl/COTRN02C.cbl}. It is a single
     * {@code EVALUATE TRUE} of eleven arms, so at most one arm runs and the arm that runs is the FIRST
     * whose field is absent. The order is the contract: the type code at line 254, the category code at
     * 260, the source at 266, the description at 272, the amount at 278, the origination date at 284,
     * the processing date at 290, the merchant identifier at 296, the merchant name at 302, the merchant
     * city at 308 and the merchant postal code at 314.</p>
     *
     * <p>Assumptions: absence covers both spellings the reference tests for, spaces and low values, which
     * the shared validation kernel treats as one state because the reference's own comparison does. The
     * amount is the one field tested for a null reference instead, because it arrives as the shared money
     * type rather than as characters and that type has no blank spelling.</p>
     *
     * @param request the submitted capture; must not be {@code null}
     * @throws ClientInputException naming the FIRST absent field in the order above and carrying that
     *     field's own sentence, with the blank state so a form draws the reference's marker
     */
    private static void requireEveryMandatoryDataField(TransactionAddRequest request) {
        requirePresent(request.typeCode(), FIELD_TYPE_CODE, TransactionAddRequest.TYPE_CODE_REQUIRED);
        requirePresent(request.categoryCode(), FIELD_CATEGORY_CODE,
                TransactionAddRequest.CATEGORY_CODE_REQUIRED);
        requirePresent(request.source(), FIELD_SOURCE, TransactionAddRequest.SOURCE_REQUIRED);
        requirePresent(request.description(), FIELD_DESCRIPTION,
                TransactionAddRequest.DESCRIPTION_REQUIRED);

        if (request.amount() == null) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_AMOUNT,
                    FieldValidationFlag.BLANK, TransactionAddRequest.AMOUNT_REQUIRED);
        }

        requirePresent(request.originDate(), FIELD_ORIGIN_DATE,
                TransactionAddRequest.ORIGIN_DATE_REQUIRED);
        requirePresent(request.processDate(), FIELD_PROCESS_DATE,
                TransactionAddRequest.PROCESS_DATE_REQUIRED);
        requirePresent(request.merchantId(), FIELD_MERCHANT_ID,
                TransactionAddRequest.MERCHANT_ID_REQUIRED);
        requirePresent(request.merchantName(), FIELD_MERCHANT_NAME,
                TransactionAddRequest.MERCHANT_NAME_REQUIRED);
        requirePresent(request.merchantCity(), FIELD_MERCHANT_CITY,
                TransactionAddRequest.MERCHANT_CITY_REQUIRED);
        requirePresent(request.merchantZip(), FIELD_MERCHANT_ZIP,
                TransactionAddRequest.MERCHANT_ZIP_REQUIRED);
    }

    /**
     * Refuses one absent field with the sentence the reference emits for it.
     *
     * @param value the submitted value, which is absent when it is {@code null}, blank or low values;
     *     may be {@code null}
     * @param field the request member the value came from; must not be {@code null}
     * @param message the sentence the reference emits for that member; must not be {@code null}
     * @throws ClientInputException if the value is absent
     */
    private static void requirePresent(String value, String field, String message) {
        if (FieldValidationFlag.isNeverSupplied(value)) {
            // WHY : Assumptions: the blank state is carried rather than the rejected-value one, because
            //       app/cpy/CSSETATY.cpy lines 23 to 25 move an asterisk into a field that is blank and
            //       only the blank state asks the shared kernel to publish that marker.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.BLANK, message);
        }
    }

    /**
     * Requires the two code fields to be composed of digits.
     *
     * <p>This transcribes the block at lines 322 to 337 of {@code app/cbl/COTRN02C.cbl}, a two-armed
     * {@code EVALUATE TRUE} whose sentences are at line 325 for the type code and line 331 for the
     * category code.</p>
     *
     * <p>Assumptions: a blank field never reaches this block, because the presence block above sends the
     * screen and ends the task first. The two blocks live in separate constructs at lines 251 to 320 and
     * 322 to 336 precisely so that a blank field reports the empty sentence alone and never the
     * composition sentence as well.</p>
     *
     * @param request the submitted capture, whose type and category codes are inspected; must not be
     *     {@code null}
     * @throws ClientInputException naming the type code, or failing that the category code, when its
     *     value holds a character that is not a digit
     */
    private static void requireNumericCodeFields(TransactionAddRequest request) {
        requireAllDigits(request.typeCode(), FIELD_TYPE_CODE,
                TransactionAddRequest.TYPE_CODE_NOT_NUMERIC);
        requireAllDigits(request.categoryCode(), FIELD_CATEGORY_CODE,
                TransactionAddRequest.CATEGORY_CODE_NOT_NUMERIC);
    }

    /**
     * Requires the merchant identifier to be composed of digits.
     *
     * <p>This transcribes the block at lines 430 to 436 of {@code app/cbl/COTRN02C.cbl}, the last of the
     * eight and the only one written as a plain conditional rather than as an evaluation. Its sentence is
     * at line 432.</p>
     *
     * @param request the submitted capture, whose merchant identifier is inspected; must not be
     *     {@code null}
     * @throws ClientInputException if the merchant identifier holds a character that is not a digit
     */
    private static void requireNumericMerchantId(TransactionAddRequest request) {
        requireAllDigits(request.merchantId(), FIELD_MERCHANT_ID,
                TransactionAddRequest.MERCHANT_ID_NOT_NUMERIC);
    }

    /**
     * Refuses one field whose value is not composed entirely of digits.
     *
     * @param value the submitted value, already known to be present; must not be {@code null}
     * @param field the request member the value came from; must not be {@code null}
     * @param message the sentence the reference emits for that member; must not be {@code null}
     * @throws ClientInputException if the value holds any character other than a digit
     */
    private static void requireAllDigits(String value, String field, String message) {
        if (!isAllDigits(value.trim())) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, message);
        }
    }

    /**
     * Requires the amount to occupy the record's edited shape, position by position.
     *
     * <p>This transcribes the block at lines 339 to 351 of {@code app/cbl/COTRN02C.cbl}, whose four
     * alternatives are POSITIONAL rather than arithmetic: the first character must be a minus or a plus
     * at line 340, the integer digits from the second must be numeric at line 341, the character after
     * them must be a decimal point at line 342 and two characters from the one after that must be numeric
     * at line 343. All four share one action block, so however many of them hold the reference emits the
     * single sentence at line 345.</p>
     *
     * <p>Assumptions: the test is applied to the edited RENDERING of the amount rather than to the
     * characters the client sent, because by the time a request reaches this class those characters have
     * already been parsed into the shared money type at the wire boundary. Rendering the value back
     * through an edited picture and testing the four positions on that string is what keeps the test
     * positional; the alternative of replacing it with a numeric range comparison was rejected because a
     * range test states a conclusion the reference reaches by inspecting bytes, and it would silently
     * accept a scale the fixed-width shape cannot hold.</p>
     *
     * <p>Refactoring Rationale: the width the positions are measured against is the RECORD's, from
     * {@link #RECORD_AMOUNT_INTEGER_DIGITS}, and not the eight-digit SCREEN picture at line 59 that the
     * four alternatives literally address. Registered divergence D-AMOUNT-RECORD-WIDTH in
     * {@code docs/architecture/cobol-to-service-traceability.md} admits the record's nine integer digits
     * at the boundary and {@link TransactionAddRequest#AMOUNT_MAGNITUDE_LIMIT_CENTS} enforces exactly
     * that; measuring the screen's eight here made the two authorities disagree, so a nine-digit amount
     * cleared the boundary and was then refused by this method with a format sentence. Two widths cannot
     * both be authoritative on one field, and the record's is the one the divergence register publishes,
     * so this test now measures it. The screen's own width is not declared here at all, because nothing
     * in this service needs it: it is a rendering width for whichever client draws the field, and stating
     * it as a constant beside the one that governs acceptance is what let the two diverge.</p>
     *
     * <p>Assumptions: the shape test is still what bounds the scale, exactly as it does in the reference.
     * A value carrying more than two decimals cannot reach this method -- the shared money type fixes
     * scale two at the wire boundary -- and a value needing more than nine integer digits renders wider,
     * which pushes the decimal point past its position and fails the third alternative. The magnitude
     * bound is therefore asserted twice, once declaratively at the boundary and once positionally here,
     * and the two now agree.</p>
     *
     * @param request the submitted capture, whose amount is rendered and inspected; must not be
     *     {@code null}
     * @throws ClientInputException if the rendering does not occupy the record's edited shape, carrying
     *     the format sentence the reference emits
     */
    private static void requireEditedAmountShape(TransactionAddRequest request) {
        String edited = editedAmount(request.amount());

        boolean malformed = edited.length() != RECORD_AMOUNT_LENGTH
                || (edited.charAt(0) != EDITED_AMOUNT_NEGATIVE_SIGN
                        && edited.charAt(0) != EDITED_AMOUNT_POSITIVE_SIGN)
                || !isAllDigits(edited.substring(1, 1 + RECORD_AMOUNT_INTEGER_DIGITS))
                || edited.charAt(1 + RECORD_AMOUNT_INTEGER_DIGITS) != EDITED_AMOUNT_DECIMAL_POINT
                || !isAllDigits(edited.substring(RECORD_AMOUNT_LENGTH - EDITED_AMOUNT_FRACTION_DIGITS));

        if (malformed) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_AMOUNT,
                    FieldValidationFlag.NOT_OK, TransactionAddRequest.AMOUNT_FORMAT);
        }
    }

    /**
     * Renders one amount through the reference's edited picture and confirms that is what is echoed.
     *
     * <p>This transcribes the block at lines 383 to 386 of {@code app/cbl/COTRN02C.cbl}: line 383
     * converts the submitted characters with {@code FUNCTION NUMVAL-C}, line 385 moves the result into
     * {@code WS-TRAN-AMT-E PIC +99999999.99} declared at line 59, and line 386 moves that edited value
     * BACK OVER the input field. The operator therefore sees a canonical rendering after a successful
     * turn rather than the characters typed, and the acknowledgement this class returns carries the
     * NORMALISED value for the same reason.</p>
     *
     * <p>Assumptions: the currency-tolerant conversion is what the money field uses, at lines 383 and
     * again at 456, while the two key fields use the plain conversion at lines 204 and 218. The two are
     * not interchangeable and the difference is preserved: the money form tolerates a currency sign and
     * grouping separators that the plain form does not. A bare {@code new BigDecimal(String)} was
     * rejected as the parse for this field on two counts -- its own grammar admits neither of those
     * characters, so it is stricter than the reference where the reference is deliberately lenient, and
     * it carries whatever scale the text happened to have, where the record's
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 fixes two. The parse
     * therefore goes through the shared money type, whose scale-two half-up invariant is that fixing,
     * and this method performs only the edited-picture half of the sequence.</p>
     *
     * @param request the submitted capture, whose amount is rendered; must not be {@code null}
     * @return the canonical amount the answer echoes, which is the submitted value after the edited
     *     picture has been applied to it, never {@code null}
     */
    private static Money canonicaliseAmount(TransactionAddRequest request) {
        // WHY : Assumptions: the round trip is written out rather than shortened away, because it is the
        //       statement of the contract -- the value the answer carries is the value that survives the
        //       reference's edited picture, and reading it back out of the rendering demonstrates that
        //       instead of asserting it. The shape test above has already refused every value the
        //       picture cannot hold, so the parse here cannot fail.
        String edited = editedAmount(request.amount());
        BigDecimal magnitude = new BigDecimal(edited.substring(1));

        return Money.of(edited.charAt(0) == EDITED_AMOUNT_NEGATIVE_SIGN
                ? magnitude.negate()
                : magnitude);
    }

    /**
     * Renders one amount into the edited form the record's width produces.
     *
     * <p>Assumptions: the shape follows the reference's picture at line 59 of
     * {@code app/cbl/COTRN02C.cbl}, {@code +99999999.99}, in every respect except the count of integer
     * digits: the sign is always written -- a plus for a non-negative value and a minus for a negative
     * one -- the integer part is left-padded with zeros, and two decimal digits always follow the point.
     * The padding target is {@link #RECORD_AMOUNT_INTEGER_DIGITS} rather than the picture's eight,
     * because the positional test that consumes this rendering measures the record's domain under
     * registered divergence D-AMOUNT-RECORD-WIDTH; padding to eight and then measuring nine would refuse
     * every value narrower than a hundred million on the first alternative instead of the third.</p>
     *
     * <p>Assumptions: a value too large for nine integer digits is rendered at its own width rather than
     * truncated, so the positional test sees the same failure the reference sees rather than a value
     * quietly cut to fit. The rendering is internal to this class -- {@link #canonicaliseAmount} reads
     * the value back out of it and the answer carries a money value, never this string -- so widening it
     * changes no published payload.</p>
     *
     * @param amount the amount to render, already known to be present; must not be {@code null}
     * @return the edited rendering, {@link #RECORD_AMOUNT_LENGTH} characters for every value the record
     *     can hold and wider for one it cannot, never {@code null}
     */
    private static String editedAmount(Money amount) {
        BigDecimal magnitude = amount.amount().abs();
        String digits = magnitude.movePointRight(EDITED_AMOUNT_FRACTION_DIGITS).toBigInteger()
                .toString();
        int minimumDigits = RECORD_AMOUNT_INTEGER_DIGITS + EDITED_AMOUNT_FRACTION_DIGITS;
        String padded = digits.length() >= minimumDigits
                ? digits
                : "0".repeat(minimumDigits - digits.length()) + digits;
        int pointIndex = padded.length() - EDITED_AMOUNT_FRACTION_DIGITS;

        return (amount.isNegative() ? EDITED_AMOUNT_NEGATIVE_SIGN : EDITED_AMOUNT_POSITIVE_SIGN)
                + padded.substring(0, pointIndex) + EDITED_AMOUNT_DECIMAL_POINT
                + padded.substring(pointIndex);
    }

    /**
     * Requires both dates to occupy the ten-character shape, position by position.
     *
     * <p>This transcribes the two blocks at lines 353 to 366 and 368 to 381 of
     * {@code app/cbl/COTRN02C.cbl}. Each is a five-alternative {@code EVALUATE TRUE} whose alternatives
     * share one action block: four numeric characters, a hyphen, two numeric characters, a hyphen and two
     * numeric characters, tested at lines 354 to 358 for the origination date and 369 to 373 for the
     * processing date. The two sentences are DIFFERENT -- line 360 names the origination date and line
     * 375 names the processing date -- so they are two constants and the origination date is tested
     * first.</p>
     *
     * <p>Assumptions: this layer tests SHAPE ONLY and says nothing about whether the date exists. The
     * reference decides existence separately, by calling the date utility at lines 393 to 395 and 413 to
     * 415, which is the block below. A date of the wrong shape therefore reports the shape sentence and
     * never reaches the calendar test, which is why the two layers cannot be collapsed into one.</p>
     *
     * @param request the submitted capture, whose two dates are inspected; must not be {@code null}
     * @throws ClientInputException naming the origination date, or failing that the processing date, when
     *     its value does not occupy the ten-character shape
     */
    private static void requireLexicalDateShapes(TransactionAddRequest request) {
        if (!isLexicalIsoDate(request.originDate())) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_ORIGIN_DATE,
                    FieldValidationFlag.NOT_OK, TransactionAddRequest.ORIGIN_DATE_FORMAT);
        }

        if (!isLexicalIsoDate(request.processDate())) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_PROCESS_DATE,
                    FieldValidationFlag.NOT_OK, TransactionAddRequest.PROCESS_DATE_FORMAT);
        }
    }

    /**
     * Reports whether one value occupies the ten-character date shape the reference tests for.
     *
     * @param value the submitted date, already known to be present; must not be {@code null}
     * @return {@code true} when the value is four digits, a hyphen, two digits, a hyphen and two digits;
     *     otherwise {@code false}
     */
    private static boolean isLexicalIsoDate(String value) {
        return value.length() == ISO_DATE_LENGTH
                && isAllDigits(value.substring(0, 4))
                && value.charAt(4) == ISO_DATE_SEPARATOR
                && isAllDigits(value.substring(5, 7))
                && value.charAt(7) == ISO_DATE_SEPARATOR
                && isAllDigits(value.substring(8, ISO_DATE_LENGTH));
    }

    /**
     * Requires both dates to denote a real calendar day, forgiving the one condition the reference
     * forgives.
     *
     * <p>This transcribes the two blocks at lines 389 to 407 and 409 to 427 of
     * {@code app/cbl/COTRN02C.cbl}. Each sets up the utility's parameter block, calls
     * {@code CSUTLDTC} at lines 393 to 395 and 413 to 415, tests the returned severity at lines 397 and
     * 417, and only then decides. The two sentences are again different, at line 401 for the origination
     * date and line 421 for the processing date, which is why four distinct date sentences exist on this
     * screen rather than two.</p>
     *
     * <p>Refactoring Rationale: the message-number tolerance is carried across as a TOLERANCE and not
     * merely as a validation, and it appears TWICE -- at line 400 for the origination date and at line
     * 420 for the processing date. Each of those inner conditionals raises the error only when the
     * returned message number is not {@code 2513}, and neither has an alternative branch, closing at
     * lines 406 and 426. A date the utility rejects with exactly that number therefore raises NOTHING
     * and the turn proceeds. Reproducing only the severity test would refuse submissions this screen
     * accepts today, and the divergence would be invisible because the field would look validated;
     * reproducing the tolerance is what makes the two agree.</p>
     *
     * <p>Assumptions: the calendar rules themselves are the shared kernel's and are not re-implemented
     * here. {@code com.carddemo.common.validation.DateEditValidator} publishes the severity and the
     * message number separately for exactly this reason, so a caller that forgives a number decides that
     * for itself, and the number this screen forgives is the one that type names as its
     * unsupported-range outcome. The mask supplied is the ten-character form the reference moves in at
     * lines 390 and 410 from the constant declared at line 61.</p>
     *
     * @param request the submitted capture, whose two dates are evaluated; must not be {@code null}
     * @throws ClientInputException naming the origination date, or failing that the processing date, when
     *     the utility rejects it with any condition other than the forgiven one
     */
    private static void requireCalendarValidDates(TransactionAddRequest request) {
        if (isCalendarRejected(request.originDate())) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_ORIGIN_DATE,
                    FieldValidationFlag.NOT_OK, MESSAGE_ORIGIN_DATE_INVALID);
        }

        if (isCalendarRejected(request.processDate())) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_PROCESS_DATE,
                    FieldValidationFlag.NOT_OK, MESSAGE_PROCESS_DATE_INVALID);
        }
    }

    /**
     * Reports whether the date utility rejects one date with a condition the reference does not forgive.
     *
     * <p>Assumptions: the two-part test is written once and read twice because the reference writes the
     * identical two-part test at lines 397 and 400 and again at lines 417 and 420. Only the sentence
     * differs between the two occurrences, and the sentence is the caller's, so sharing the predicate
     * cannot make one date's tolerance diverge from the other's.</p>
     *
     * @param date the submitted date, already known to occupy the ten-character shape; must not be
     *     {@code null}
     * @return {@code true} when the utility reports a non-zero severity AND a message number other than
     *     the forgiven unsupported-range one; otherwise {@code false}
     */
    private static boolean isCalendarRejected(String date) {
        DateEditValidator.LanguageEnvironmentResult verdict = DateEditValidator
                .evaluateWithLanguageEnvironment(date, DateEditValidator.DATE_FORMAT_MASK);

        return !verdict.acceptable() && !verdict.unsupportedRange();
    }

    /**
     * Derives the next identifier, builds the row and appends it.
     *
     * <p>This transcribes {@code ADD-TRANSACTION} at lines 442 to 466 of
     * {@code app/cbl/COTRN02C.cbl}: it derives the key across lines 444 to 449, initialises the record at
     * line 450, moves thirteen fields into it across lines 451 to 465 and writes it at line 466.</p>
     *
     * <p>Assumptions: the card number written into the row is the RESOLVED one and not the submitted one.
     * Line 459 moves the screen's card field into the record, and that field is the one line 209 has
     * already overwritten from the cross-reference whenever an account identifier was supplied -- so a
     * submission keyed by account writes the cross-reference's card, which is the discard recorded on
     * {@link #validateInputKeyFields(TransactionAddRequest)}.</p>
     *
     * <p>Assumptions: the second currency-tolerant conversion at line 456 recomputes the amount from the
     * same screen field the sixth block wrote at line 386, so the value written to the record is the
     * canonical one. Reading it out of the request here reproduces that, because the request's amount is
     * already that value.</p>
     *
     * <p>Trade-offs: both stored timestamps carry MIDNIGHT, and that follows from the reference rather
     * than being a default chosen here. Lines 464 and 465 move two {@code PIC X(10)} screen fields into
     * the two {@code PIC X(26)} record fields {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} declared at
     * {@code app/cpy/CVTRA05Y.cpy} lines 16 and 17, and COBOL left-justifies and space-pads, so a record
     * written through this screen holds ten characters of date followed by sixteen spaces and carries no
     * time at all. Rendering that into a microsecond-precision column can only produce
     * {@code 00:00:00.000000}. The compromise accepted is that a reader of the stored row cannot tell
     * what time of day the capture happened, and the alternative -- reading the wall clock to complete
     * the value -- was rejected because it would record an instant the reference never captured and
     * would make two runs of the same input differ. The seed extract itself shows the reference's
     * timestamps are only partly populated: record one of {@code app/data/ASCII/dailytran.txt} carries an
     * origination timestamp of {@code 2022-06-10 19:27:53.000000} while its processing-timestamp region
     * is spaces. The processing column is declared not-null, so the midnight value is what is stored and
     * no absent branch is coded for a state the schema forbids.</p>
     *
     * @param request the validated capture; must not be {@code null}
     * @param resolvedCardNumber the card number the cross-reference resolved; must not be {@code null}
     * @return the acknowledgement carrying the generated identifier, the normalised amount and the
     *     composed sentence, never {@code null}
     * @throws RecordConflictException if the derived identifier is already stored
     * @throws IllegalStateException if the identifier could not be derived, or if the append failed
     */
    private TransactionAddResponse appendTransaction(TransactionAddRequest request,
            String resolvedCardNumber) {

        // WHY : Assumptions: the derivation and the append share ONE unit of work because the reference
        //       performs them in one CICS task, whose implicit syncpoint commits both together. Splitting
        //       them into two transactions would widen the window in which another writer can claim the
        //       identifier this one derived, and the duplicate that results is the condition at lines 735
        //       and 736 -- so keeping the span narrow is what makes that condition rare rather than
        //       routine.
        Transaction stored = this.writeTransaction.execute(status -> {
            String derivedId = nextTransactionId();
            return writeTransactRecord(
                    this.transactionMapper.toEntity(request, derivedId, resolvedCardNumber));
        });

        return this.transactionMapper.toAddResponse(stored,
                acknowledgement(requireStoredIdentifier(stored)));
    }

    /**
     * Reads the identifier off the appended row for the sentence to name.
     *
     * @param stored the row the append returned; must not be {@code null}
     * @return the identifier the row carries, never {@code null}
     * @throws IllegalStateException if the append returned no row, or a row with no identifier, which the
     *     primary key makes impossible for a row the store accepted
     */
    private static String requireStoredIdentifier(Transaction stored) {
        if (stored == null || stored.getTranId() == null) {
            throw new IllegalStateException(MESSAGE_ADD_FAILED);
        }
        return stored.getTranId();
    }

    /**
     * Allocates the next transaction identifier from the database's own allocator.
     *
     * <p>This stands where the five-statement derivation at lines 444 to 449 of
     * {@code app/cbl/COTRN02C.cbl} stands: high values are moved into the key at line 444, a browse is
     * started at line 445, ONE record is read backwards at line 446, the browse is ended at line 447, the
     * key that was found is moved into a numeric work field at line 448 and one is added to it at line
     * 449. Reading backwards from past the end of a keyed file yields the highest key, so the reference's
     * derivation is a read-then-add over the whole table.</p>
     *
     * <p>⚠️ Refactoring Rationale: this method used to perform that read-then-add literally, through
     * {@link TransactionRepository#findMaxTranId()}, and its own note argued for doing so and against the
     * allocator that {@link TransactionRepository#allocateTransactionId()} already publishes. That
     * argument is withdrawn, and both of the reasons it gave are answered here rather than left standing.
     * The first was that the allocator "removes the maximum-read whose failure the published contract
     * promises to report with the reference's own read sentence" -- but the contract promises a sentence
     * for a failed IDENTIFIER DERIVATION, not for one particular SQL statement, and this method still
     * reports exactly that sentence when the allocation cannot be performed, so nothing a caller can
     * observe was lost. The second was that "a sequence is not rolled back with its transaction, so an
     * abandoned turn would consume an identifier the reference would have left available" -- true, and
     * immaterial: nothing in the reference tree reads an identifier gap as meaningful, the report job at
     * {@code app/jcl/TRANREPT.jcl} lines 41 and 42 ordering by processing timestamp and card number
     * rather than by identifier arithmetic.</p>
     *
     * <p>What the read-then-add cost is measurable rather than theoretical. Under CICS the region
     * serialised this program and the payment program, so read-then-add was indivisible in effect. Two
     * Fargate tasks behind a load balancer are not serialised: both read the same maximum, both add one,
     * and both attempt the same primary key. One commits and the other is refused on a constraint
     * violation. The old note treated that as acceptable because the reference has a duplicate branch at
     * lines 735 and 736 -- but that branch answers an operator who keyed an identifier that was already
     * taken, which cannot happen on a screen that derives the identifier itself, so relying on it turned
     * a server-side race into a refusal the caller did nothing to cause and can only answer by
     * resubmitting. Allocating in the database makes the increment indivisible, so two concurrent callers
     * receive different values without either waiting on the other.</p>
     *
     * <p>Assumptions: the padding stays HERE and is not moved into the repository. The allocator returns
     * a number and this method renders it as sixteen zero-padded digit characters, because
     * {@code app/cpy/CVTRA05Y.cpy} line 5 declares {@code TRAN-ID PIC X(16)} and the target column is
     * {@code CHAR(16)}, so leading zeros are part of the value -- the seeded extract carries identifiers
     * such as {@code 0000000000683580} that a shortest-form rendering would neither match nor order
     * beside. Rule T5 turns file verbs into repository members, and formatting is not one.</p>
     *
     * <p>Assumptions: the allocator's own migration positions the sequence past the loaded data, so the
     * first value it issues on a seeded database is above every identifier the extract carries and the
     * empty-table case the reference reaches at lines 688 and 689 needs no separate arm here. That is
     * why no zero-origin fallback survives in this method.</p>
     *
     * <p>Trade-offs: the width guard is kept even though the sequence is declared without a maximum this
     * side can rely on. Exhausting sixteen digits is unreachable at any realistic volume, and the guard
     * is one comparison that turns an identifier the column cannot hold into the reference's own read
     * sentence rather than into a truncated key or a database-level error naming no field.</p>
     *
     * @return the next identifier as exactly sixteen digit characters, never {@code null}
     * @throws IllegalStateException if the allocation could not be performed or reported no value, or if
     *     the allocated value needs more than the sixteen digits the column holds, each carrying the
     *     reference's own read sentence
     */
    private String nextTransactionId() {
        Long allocated;
        try {
            allocated = this.transactions.allocateTransactionId();
        } catch (RuntimeException allocationFailure) {
            // WHY : Assumptions: the sentence is the reference's failed-read one from lines 664 and 693,
            //       which are the two conditions its own derivation can fail under. The published
            //       contract for this operation names that sentence for a derivation that could not be
            //       completed, and an allocation that could not be performed is that same condition
            //       reached by a different mechanism.
            throw new IllegalStateException(MESSAGE_TRANSACTION_LOOKUP_FAILED, allocationFailure);
        }

        // WHY : Assumptions: a null return is treated as a failed allocation rather than as zero. The
        //       statement selects one row from a sequence and cannot legitimately answer with nothing, so
        //       a null here means the query did not do what it says; defaulting to zero would then write
        //       identifier one over a populated table and collide on the first attempt.
        if (allocated == null) {
            throw new IllegalStateException(MESSAGE_TRANSACTION_LOOKUP_FAILED);
        }

        if (Long.toString(allocated).length() > TRANSACTION_ID_WIDTH) {
            throw new IllegalStateException(MESSAGE_TRANSACTION_LOOKUP_FAILED);
        }

        return zeroPadded(allocated, TRANSACTION_ID_WIDTH);
    }

    /**
     * Reads the most recently stored transaction, the way the copy path reads it.
     *
     * <p>This transcribes the browse at lines 475 to 478 of {@code app/cbl/COTRN02C.cbl}, which is the
     * same four-statement sequence the append path uses at lines 444 to 447 but keeps the RECORD rather
     * than only its key. The relational form is the highest key followed by a keyed read, because the
     * repository publishes the maximum rather than a whole-row backward read.</p>
     *
     * @return the row carrying the highest identifier, or an empty optional when the table holds no rows
     *     at all, never {@code null}
     * @throws IllegalStateException if the read could not be performed
     */
    private Optional<Transaction> readLatestTransaction() {
        try {
            return this.transactions.findMaxTranId().flatMap(this.transactions::findById);
        } catch (RuntimeException readFailure) {
            throw new IllegalStateException(MESSAGE_TRANSACTION_LOOKUP_FAILED, readFailure);
        }
    }

    /**
     * Appends one row and converts the write's three outcomes into this screen's answers.
     *
     * <p>This transcribes {@code WRITE-TRANSACT-FILE} at lines 711 to 749 of
     * {@code app/cbl/COTRN02C.cbl}. Its normal arm at line 724 clears the form and composes the
     * acknowledgement; its failure arm at line 742 reports the sentence at line 745.</p>
     *
     * <p>Refactoring Rationale: TWO CICS conditions collapse onto ONE answer here, and the collapse is
     * the reference's own rather than a simplification made in translation. Line 735 names the
     * duplicate-key condition and line 736 names the duplicate-record condition, and both fall through to
     * a single shared arm whose first act at line 738 moves one sentence. Reporting them as two answers
     * would invent a distinction the reference does not draw and would give a client something to branch
     * on that no message tells it apart. The one answer is a conflict, because the caller can act on it by
     * retrying, and the sentence it carries is the reference's -- singular verb included, since rule T8
     * carries a user-visible string across character for character rather than correcting its
     * grammar.</p>
     *
     * <p>Assumptions: the conflict rides the shared kernel's existing conflict mapping rather than a
     * handler added here, so the status, the code, the subsystem and the correlation entry are composed
     * in the one place every other conflict in this migration is composed.</p>
     *
     * @param row the row to append, already carrying its derived identifier; must not be {@code null}
     * @return the appended row as the store holds it, never {@code null}
     * @throws RecordConflictException if the row's identifier is already stored
     * @throws IllegalStateException if the append failed for a reason the caller cannot correct
     */
    private Transaction writeTransactRecord(Transaction row) {
        try {
            return this.transactions.saveAndFlush(row);
        } catch (DataIntegrityViolationException duplicate) {
            // WHY : Assumptions: the integrity violation is caught SEPARATELY from every other store
            //       failure because it is the only one on this path the caller can act on, and the
            //       action is to submit again. The write is flushed rather than left to the commit so
            //       the violation arrives inside this try; a write deferred to commit is wrapped by the
            //       framework's transaction advice and reaches the shared handler unclassified, where it
            //       would be answered with the dependent-row sentence that names no transaction at all.
            throw new RecordConflictException(RecordConflictException.Kind.DUPLICATE_KEY);
        } catch (RuntimeException writeFailure) {
            throw new IllegalStateException(MESSAGE_ADD_FAILED, writeFailure);
        }
    }

    /**
     * Composes the acknowledgement the reference composes from four fragments.
     *
     * <p>This transcribes the normal arm at lines 724 to 734 of {@code app/cbl/COTRN02C.cbl}. Line 725
     * performs {@code INITIALIZE-ALL-FIELDS}, so the form is CLEARED on success; line 727 moves the
     * success colour into the message attribute, so the sentence is a success-severity message rather
     * than an error; and lines 728 to 733 assemble it with {@code STRING} from four fragments.</p>
     *
     * <p>Assumptions: the assembly emits TWO CONSECUTIVE SPACES and the doubling is deliberate to
     * reproduce. The first fragment at line 728 ends with a space INSIDE the literal and the second at
     * line 730 begins with one, and both are delimited by size, so nothing trims either. A hand-written
     * sentence with a single space between "successfully." and "Your" would be wrong by one byte, and the
     * error would be invisible in a rendered answer because a browser collapses whitespace.</p>
     *
     * <p>Assumptions: the identifier is emitted in FULL, all sixteen zero-padded characters. The third
     * fragment at line 731 is delimited by space rather than by size, which trims at the first space --
     * but line 451 fed the field from {@code WS-TRAN-ID-N PIC 9(16)}, a numeric picture that pads with
     * zeros rather than spaces, so there is no space to trim and the whole sixteen characters are
     * emitted.</p>
     *
     * <p>Assumptions: this is NOT the payment screen's acknowledgement.
     * {@code app/cbl/COBIL00C.cbl} assembles its own from different literals -- line 527 writes
     * {@code 'Payment successful. '} and line 528 writes {@code ' Your Transaction ID is '}, spelling out
     * the word this screen abbreviates. Both emit the two consecutive spaces and the two sentences are
     * separate constants in separate classes.</p>
     *
     * @param transactionId the generated identifier, as sixteen digit characters; must not be
     *     {@code null}
     * @return the acknowledgement sentence, never {@code null}
     */
    private static String acknowledgement(String transactionId) {
        return MESSAGE_ADDED_PREFIX + MESSAGE_ADDED_INFIX + transactionId + MESSAGE_ADDED_SUFFIX;
    }

    /**
     * Reports whether the submitted confirmation is one of the two affirmative spellings.
     *
     * @param confirmation the submitted discriminator, possibly {@code null}
     * @return {@code true} for the upper or lower case affirmative at lines 170 and 171, otherwise
     *     {@code false}
     */
    private static boolean isAffirmative(String confirmation) {
        return CONFIRM_YES_UPPER.equals(confirmation) || CONFIRM_YES_LOWER.equals(confirmation);
    }

    /**
     * Reports whether the submitted confirmation is one the reference answers with its prompt.
     *
     * <p>Assumptions: the never-supplied test covers both of the absence spellings the reference names
     * at lines 175 and 176, spaces and low values, which the shared validation kernel treats as one
     * state because the reference's own comparison does. They remain two distinct byte states in
     * general; only their handling coincides at this one site.</p>
     *
     * @param confirmation the submitted discriminator, possibly {@code null}
     * @return {@code true} for the refusal spellings at lines 173 and 174 and for either absence
     *     spelling, otherwise {@code false}
     */
    private static boolean isRefusedOrNeverSupplied(String confirmation) {
        return CONFIRM_NO_UPPER.equals(confirmation) || CONFIRM_NO_LOWER.equals(confirmation)
                || FieldValidationFlag.isNeverSupplied(confirmation);
    }

    /**
     * Reports whether every character of a value is a digit.
     *
     * <p>Assumptions: this is the target of the reference's {@code IS NUMERIC} class test, which accepts
     * a display field whose every character is a digit and rejects one holding a space, a sign or a
     * point. An empty value is reported as NOT all digits, so a caller that has not already established
     * presence cannot mistake emptiness for a number.</p>
     *
     * @param value the value to inspect; must not be {@code null}
     * @return {@code true} when the value is non-empty and every character is a digit, otherwise
     *     {@code false}
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders one number left-padded with zeros to a declared field width.
     *
     * <p>Assumptions: this is the target of the reference's move from a numeric work field back into an
     * alphanumeric display field -- lines 206, 220 and 451 each do it -- which left-pads with zeros
     * because the source picture is numeric. The padding is significant rather than cosmetic wherever
     * the target column has a declared width, which is the case for all three of those fields.</p>
     *
     * @param value the number to render; must not be negative, which no identifier or key on this path
     *     can be
     * @param width the declared width of the field to pad to
     * @return the value as exactly {@code width} digit characters when it fits, or at its own width when
     *     it does not, never {@code null}
     */
    private static String zeroPadded(long value, int width) {
        return String.format("%0" + width + "d", value);
    }
}
