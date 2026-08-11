package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/**
 * Pins the one balance-affecting write of the ledger context against the paragraphs of
 * {@code COBIL00C}.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link BillPaymentService} transcribes {@code app/cbl/COBIL00C.cbl}, 572 lines counted in the
 * file, transaction {@code CB00}, named "Bill Payment" in the inventory table of the repository root
 * {@code README.md} at line 302. Every other screen this context migrates reads; this one writes a
 * ledger row and reduces an account balance, so the properties held here are the ones a reader cannot
 * check by inspection: which values on the written row are constants and which are live data, the
 * order the three write statements run in, which balance the operator is shown afterwards, and the
 * single instant both timestamp members carry.</p>
 *
 * <p>Assumptions: the inventory row is cited at the line the tree carries as it stands, 302. The
 * pristine baseline held the same row twenty lines earlier, at line 282, because the migration section
 * this plan adds to {@code README.md} moved the whole table down; the sibling charter in this
 * directory records the same shift for the four rows above it. The verifiable claim is the row content
 * -- transaction {@code CB00}, mapset {@code COBIL00}, program {@code COBIL00C}, screen name "Bill
 * Payment" -- and the line number is the convenience.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a test class: no caller constructs
 * it, it yields no value and it raises nothing outside the test engine, so the type itself accepts no
 * parameter, returns nothing and throws nothing. The inapplicability is stated rather than passed
 * over, because the user-specified Explainability rule forbids a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared inapplicability from an
 * oversight. Every member below carries its own at-clauses.</p>
 *
 * <h2>Assumptions: the unit of work is an IMPLICIT syncpoint in the baseline</h2>
 *
 * <p>Assumptions: the baseline relies on the implicit CICS task-end syncpoint -- no explicit
 * {@code EXEC CICS SYNCPOINT} verb appears in any of the four programs this context migrates -- and
 * the Java expresses the same unit of work as an explicit {@code @Transactional} boundary. That
 * framing is stated at the top because this is the only one of the four programs that writes, so it
 * is the file where a citation to a commit verb would read most plausibly and be most wrong. The
 * program's own atomicity comes from the task ending, and its rollback likewise: there is no
 * {@code SYNCPOINT ROLLBACK} to transcribe, so a failure is signalled by propagating rather than by
 * returning a status, which is what lets the enclosing boundary undo the row.</p>
 *
 * <p>Assumptions: the three write statements of the confirmed branch, lines 233, 234 and 235, are
 * therefore inside one commit in the baseline and inside one boundary here. What that buys the cases
 * below is that their ORDER is not externally observable at run time, which is precisely why the order
 * is asserted from the collaborators rather than from any database state.</p>
 *
 * <h2>Alternatives Considered: what is held real, against the charter's default</h2>
 *
 * <p>Alternatives Considered: mocking {@link BillPaymentMapper}, which is what the charter beside this
 * class lists as the default for the payment screen and what the sibling detail-screen case does with
 * its own converter. It was rejected here for one reason: the property this class exists to hold is
 * that EIGHT of the ten values written onto the row are constants carried byte for byte from the
 * reference, and the converter is what writes them. A mocked converter would answer with a row this
 * test itself built, so the literal cases would assert their own fixture and pass against a converter
 * that had every literal wrong. The converter is therefore constructed real, and the row is captured
 * as it reaches the repository. The sibling payment case in this directory takes the opposite choice
 * for the opposite reason -- it holds an ORDER of evaluation, which a real converter cannot help
 * establish -- so the two are complementary rather than redundant.</p>
 *
 * <p>Assumptions: the two collaborators that ARE mocked are the ones that leave this module.
 * {@link TransactionRepository} is mocked because its reads and its write belong to the persistence
 * layer and are proved against a real engine by the integration class in the sibling
 * {@code repository} package. {@link AccountContextClient} is mocked because what it reaches is a
 * different bounded context over HTTP; resolving it for real would fail for a reason belonging to that
 * context rather than to the paragraph under test.</p>
 *
 * <p>Trade-offs: the extension form used here applies strict stubbing, so a stubbing no case exercises
 * fails the build. That cost is accepted because several cases below assert a NEGATIVE -- that no
 * paging member is called, that no balance change is issued -- and a drifting stub is exactly how such
 * a case rots into one that passes while proving nothing.</p>
 *
 * <h2>Assumptions: two crossings of the account boundary, and they stay distinguishable</h2>
 *
 * <p>Assumptions: this screen reaches records the account context owns TWICE and the two crossings are
 * not the same kind of operation, so they are asserted separately and are recorded here as distinct
 * lest a later reader unify them. The cross-reference read at line 408 and the account master read at
 * line 343 are LOOKUPS whose answers this class then decides on. The balance change standing for the
 * rewrite at line 379 is a WRITE that must stand or fall with the ledger row, which is why it is
 * issued last and why its refusal propagates. Both travel through the one outbound port
 * {@link AccountContextClient}, and the reason they share a port rather than a mechanism is recorded
 * on the production class: this service connects as the ledger role, and
 * {@code data-migration/sql/V0__schemas_and_roles.sql} grants that role usage on the {@code ledger}
 * schema alone, so schema-qualified statements against {@code account.accounts} on this module's own
 * entity manager would not resolve at run time. The cross-schema grants that script does carry are
 * issued to the batch role and justified there by the nightly programs.</p>
 *
 * <p>Alternatives Considered: a circuit breaker in front of that port. Rejected because the hop is
 * in-network to a service behind an internal load balancer and both of its timeouts are bounded by
 * configuration, so a stalled dependency already surfaces as a refused request within seconds. A
 * breaker would add a state machine that can refuse a call the dependency would have served, which is
 * a new failure mode in exchange for none removed. No resilience library is introduced, and the case
 * below asserts the absence by name so that adding one silently is not possible.</p>
 *
 * <h2>Assumptions: NO golden master covers this program</h2>
 *
 * <p>Assumptions: no golden master exists for this path and none is claimed.
 * {@code tests/README.md} lines 83 to 85 record that the online {@code CO*} programs cannot be run end
 * to end without a CICS runtime, which the runner does not have, so only their extractable validation
 * logic is unit-tested. {@code COBIL00C} is one of those programs. Parity therefore rests on two
 * things and is asserted as such: logic transcribed statement by statement with each statement cited
 * to its line, and the copybook record contracts. Nothing below is justified by pointing at a
 * committed output file, and nothing here creates, regenerates or reads anything under the oracle's
 * fixture or golden trees.</p>
 *
 * <p>Assumptions: the graded condition-code rubric the oracle aggregates belongs to that suite alone.
 * This class runs under a gate that is binary -- a case either passes or fails -- so nothing below
 * tolerates a degree of failure, no case computes a return code, and no case here is wired into the
 * oracle's pipeline.</p>
 *
 * <h2>Assumptions: determinism is supplied, never read</h2>
 *
 * <p>Assumptions: no case below reads an ambient clock. The clock is supplied as a pinned instant
 * through {@link Clock}, which is what makes the two timestamp assertions reproducible: the
 * claim that both members carry ONE instant and the claim that the rendered form ends in six zero
 * digits are both claims about a value the class under test generates, so a wall-clock reading would
 * make them agree almost always and disagree occasionally. That is the arrangement
 * {@code services/transaction-service/src/test/resources/application-test.yml} records for this
 * module, where the clock is supplied as a bean rather than as a property; that file is read rather
 * than duplicated. Normalising an ambient reading before comparison is the oracle's technique for a
 * batch program compared against a committed file, and it is not available here because the value
 * under test is the one being generated. Each case builds its own stand-ins and shares no mutable
 * state, which is what keeps the class safe to run beside any other.</p>
 *
 * <p>Assumptions: money never leaves exact decimal form in any case below. Every amount is built
 * through {@link Money}, compared as {@link Money}, and never converted to an inexact binary
 * representation, because this is the balance-affecting screen and an inexact intermediate here would
 * be an error in currency rather than in presentation.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the payment screen: one write, eight literals, one order and one instant")
class BillPaymentServiceTest {

    /**
     * The instant every case stamps its payment with, supplied so the rendered form is reproducible.
     *
     * <p>Assumptions: the seconds and the sub-second component are both zero in the source instant, so
     * the reduction the class under test applies is observable as a no-change rather than masking a
     * rounding step that a reader could not see.</p>
     */
    private static final Clock PINNED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T12:00:00Z"), ZoneId.of("UTC"));

    /** The instant the pinned clock yields, as the class under test stores it on both members. */
    private static final LocalDateTime PAYMENT_INSTANT = LocalDateTime.parse("2022-07-18T12:00:00");

    /**
     * The twenty-six character rendering of that instant, as the reference's own group item forms it.
     *
     * <p>Assumptions: the space at character 11 and the period at character 20 are structural rather
     * than incidental, and the case that asserts this constant proves why from the copybook.</p>
     */
    private static final String RENDERED_TIMESTAMP = "2022-07-18 12:00:00.000000";

    /** An eleven digit account identifier, at the width the account key declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The card number the cross-reference resolves for that account, at its declared width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The outstanding balance every paying case settles, chosen to be neither zero nor round. */
    private static final Money PAYABLE_BALANCE = Money.of("1250.75");

    /** The highest identifier the ledger already holds, at the sixteen character declared width. */
    private static final String HIGHEST_STORED_ID = "0000000000000041";

    /** The identifier one above it, which the derivation must produce. */
    private static final String DERIVED_ID = "0000000000000042";

    /** The identifier an empty ledger must produce, being the zero sentinel plus one. */
    private static final String FIRST_ID = "0000000000000001";

    /** The reads and the write of the owned ledger table, stood in for by a mock. */
    @Mock
    private TransactionRepository transactions;

    /** The outbound port onto the account-owned cross-reference, balance and balance change. */
    @Mock
    private AccountContextClient accounts;

    /**
     * The record and response boundary, held REAL because the eight literals are its contract.
     *
     * <p>Alternatives Considered: mocking it, which the class note above records the rejection of.</p>
     */
    private BillPaymentMapper billPaymentMapper;

    /** The unit under test, rebuilt over fresh stand-ins before each case. */
    private BillPaymentService service;

    /**
     * Builds the service over freshly created stand-ins before every case.
     *
     * <p>Assumptions: the service is rebuilt rather than shared because it is constructed around its
     * four collaborators, and one retained across cases would hold the previous case's stubbings. The
     * class under test keeps no mutable instance state of its own, so rebuilding it costs nothing.</p>
     */
    @BeforeEach
    void setUp() {
        this.billPaymentMapper = new BillPaymentMapper();
        this.service = new BillPaymentService(this.transactions, this.accounts, this.billPaymentMapper,
                PINNED_CLOCK);
    }

    /**
     * Stubs the account master read that stands for the reference's read for update.
     *
     * <p>Assumptions: the port answers with a present balance, which is the normal arm of
     * {@code READ-ACCTDAT-FILE} at line 343 of {@code app/cbl/COBIL00C.cbl}. Absence and failure are
     * different answers on this port and are stubbed by the cases that assert them.</p>
     *
     * @param balance the outstanding balance the account context is to report, of type {@link Money};
     *     must not be {@code null}
     */
    private void stubBalanceRead(Money balance) {
        when(this.accounts.findAccountBalance(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountContextClient.AccountBalance(ACCOUNT_ID, balance)));
    }

    /**
     * Stubs the cross-reference read that resolves the card the payment is recorded against.
     *
     * <p>Assumptions: this stands for {@code READ-CXACAIX-FILE} at line 408, performed from line 211
     * on the confirmed branch only, so it is stubbed only by cases that reach the write.</p>
     */
    private void stubCardResolution() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
    }

    /**
     * Stubs the highest-key read the identifier derivation issues, and reports the derived key free.
     *
     * @param highestStored the highest identifier the ledger is to report, of type {@link String}, or
     *     {@code null} to report an empty ledger as the end-of-file arm of line 487 does
     * @param derived the identifier the derivation is expected to produce, of type {@link String},
     *     reported as not yet taken so the write proceeds; must not be {@code null}
     */
    private void stubIdentifierDerivation(String highestStored, String derived) {
        when(this.transactions.findMaxTranId()).thenReturn(Optional.ofNullable(highestStored));
        when(this.transactions.existsById(derived)).thenReturn(false);
    }

    /**
     * Stubs the ledger write to echo the row it was handed, standing for a normal write response.
     *
     * <p>Assumptions: echoing rather than returning a separately built row is what lets the literal
     * cases below read the row the converter actually composed. The normal arm of
     * {@code WRITE-TRANSACT-FILE} at line 510 stores the record as presented, so echoing is the
     * faithful stand-in.</p>
     */
    private void stubWriteEchoesTheRow() {
        when(this.transactions.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * Stubs every collaborator the confirmed payment path consults, in one call.
     *
     * @param balance the outstanding balance the account context is to report, of type {@link Money};
     *     must not be {@code null}
     */
    private void stubConfirmedPaymentPath(Money balance) {
        stubBalanceRead(balance);
        stubCardResolution();
        stubIdentifierDerivation(HIGHEST_STORED_ID, DERIVED_ID);
        stubWriteEchoesTheRow();
    }

    /**
     * Captures the row as it reached the ledger write.
     *
     * <p>Assumptions: the row is read off the write rather than off the returned acknowledgement,
     * because what the literal cases claim is about the record that was STORED. The acknowledgement
     * carries a deliberately narrower shape and would not expose the merchant members at all.</p>
     *
     * @return the {@link Transaction} the class under test handed to the repository; never
     *     {@code null}
     */
    private Transaction storedRow() {
        ArgumentCaptor<Transaction> written = ArgumentCaptor.forClass(Transaction.class);
        verify(this.transactions).save(written.capture());
        return written.getValue();
    }

    /**
     * Captures the amount the balance change was asked to subtract.
     *
     * <p>Assumptions: the port is given an AMOUNT and not a resulting balance, which is the whole of
     * what preserves the reference's arithmetic at line 234. Capturing it is therefore how the
     * subtraction form becomes observable from outside the class.</p>
     *
     * @return the {@link Money} amount the account context was asked to reduce the balance by; never
     *     {@code null}
     */
    private Money subtractedAmount() {
        ArgumentCaptor<Money> reduced = ArgumentCaptor.forClass(Money.class);
        verify(this.accounts).applyPayment(anyString(), reduced.capture());
        return reduced.getValue();
    }

    /**
     * A confirmed payment writes one row and has the balance reduced by the amount it paid.
     *
     * <p>This pins the confirmed branch of {@code PROCESS-ENTER-KEY}, lines 208 to 235 of
     * {@code app/cbl/COBIL00C.cbl}: line 208 re-tests the error flag, line 210 selects the confirmed
     * arm, line 211 resolves the cross-reference, lines 212 to 219 derive the identifier, lines 220 to
     * 229 populate the record, line 230 stamps it, line 233 writes it, line 234 subtracts and line 235
     * rewrites the account.</p>
     *
     * <p>Assumptions: the baseline relies on the implicit CICS task-end syncpoint -- no explicit
     * {@code EXEC CICS SYNCPOINT} verb appears in any of the four programs this context migrates -- and
     * the Java expresses the same unit of work as an explicit {@code @Transactional} boundary. So this
     * case asserts that BOTH effects are produced on one call, which is what the single boundary makes
     * indivisible; the boundary's own declaration is asserted separately below.</p>
     *
     * <p>Assumptions: the acknowledgement reports the identifier that was written rather than one the
     * client supplied, because the reference derives it at lines 216 and 217 from the ledger's own
     * highest key and the screen carries no identifier field to submit.</p>
     */
    @Test
    @DisplayName("a confirmed payment writes the row and reduces the balance on one call")
    void aConfirmedPaymentWritesTheRowAndReducesTheBalance() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        BillPaymentResponse acknowledgement =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(acknowledgement.paid())
                .as("a confirmed payment reports itself as paid")
                .isTrue();
        assertThat(acknowledgement.transactionId()).isEqualTo(DERIVED_ID);
        assertThat(storedRow().getTranId()).isEqualTo(DERIVED_ID);
        verify(this.accounts).applyPayment(ACCOUNT_ID, PAYABLE_BALANCE);
    }

    /**
     * The balance reported after a payment is the pre-payment figure, and equals the amount paid.
     *
     * <p>This pins lines 193 and 194 of {@code app/cbl/COBIL00C.cbl} together with what the program
     * does NOT do afterwards. Line 193 moves the account balance into a working field and line 194
     * moves that field into the screen's balance, both of them before the payment block at line 208.
     * Line 224 then reuses the same untouched balance as the transaction amount, line 234 subtracts it
     * and line 235 rewrites the account -- and nothing repopulates the screen's balance before the send
     * at line 242, which merely sends. The figure the operator is shown after a successful payment is
     * consequently the balance from BEFORE it.</p>
     *
     * <p>Assumptions: the two claims are asserted in one case because they are one number serving two
     * meanings, and separating them would let a reader take the equality for a coincidence of the
     * chosen amount. They coincide because the reference always pays the whole balance: the screen
     * carries no amount field, which is why the request shape has no amount component either.</p>
     *
     * <p>Assumptions: the reported figure is compared as exact decimal rather than as text, so a value
     * that rendered identically at a different scale would still fail. The amount is deliberately not
     * a round number, so a case that reported zero -- the post-payment balance -- could not pass by
     * accident.</p>
     */
    @Test
    @DisplayName("the reported balance is the pre-payment figure and is also the amount paid")
    void theReportedBalanceIsThePrePaymentFigureAndTheAmountPaid() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        BillPaymentResponse acknowledgement =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(acknowledgement.currentBalance())
                .as("the pre-payment balance is reported, not the balance left after the payment")
                .isEqualTo(PAYABLE_BALANCE)
                .isNotEqualTo(Money.ZERO);
        assertThat(subtractedAmount())
                .as("the figure reported is the same figure that was paid")
                .isEqualTo(acknowledgement.currentBalance());
        assertThat(storedRow().getTranAmt()).isEqualByComparingTo(PAYABLE_BALANCE.amount());
    }

    /**
     * The balance change is expressed as a subtraction of the amount, never as an assignment of zero.
     *
     * <p>This pins line 234 of {@code app/cbl/COBIL00C.cbl},
     * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}, in the FORM the reference computes it
     * and not merely in its result.</p>
     *
     * <p>Assumptions: asserting that the balance becomes zero would NOT pin this statement, and that
     * is the reason this case exists separately from the one above. Because the amount paid is always
     * the whole balance, subtracting the amount and assigning zero produce the same number today, so a
     * case that asserted the resulting balance would pass just as readily against an implementation
     * that overwrote the field. What distinguishes them is observable only at the boundary: the port is
     * handed the amount to REDUCE BY, so the owner performs the arithmetic against the value as it then
     * stands, whereas an assignment would discard whatever the balance had become. Transformation rule
     * T4 requires the computed form to survive for exactly this reason.</p>
     *
     * <p>Trade-offs: this case therefore asserts a parameter rather than a state, which is a weaker
     * kind of evidence in general. It is the strongest available from a unit test of a class that does
     * not own the row, and the compensating assertion -- that the subtracted amount is the balance and
     * is not zero -- rules out the one substitution that would otherwise go undetected.</p>
     */
    @Test
    @DisplayName("the balance change subtracts the amount rather than assigning zero")
    void theBalanceChangeSubtractsTheAmountRatherThanAssigningZero() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        // WHY : Assumptions: the second, negative half of this assertion is the load-bearing one and is
        //       not redundant with the first. An implementation that assigned zero instead of subtracting
        //       would hand the port a zero amount, and zero is the one value that would satisfy a test
        //       written only around "the balance ends up settled". Naming zero as forbidden is what
        //       distinguishes the two implementations at the only place they differ.
        assertThat(subtractedAmount())
                .as("the amount to subtract is transmitted, so the owner subtracts rather than assigns")
                .isEqualTo(PAYABLE_BALANCE)
                .isNotEqualTo(Money.ZERO);
        assertThat(PAYABLE_BALANCE.minus(subtractedAmount()))
                .as("subtracting the whole balance leaves nothing outstanding, as the reference does")
                .isEqualTo(Money.ZERO);
    }

    /**
     * The three write statements run in the reference's order: write, then compute, then update.
     *
     * <p>This pins lines 233, 234 and 235 of {@code app/cbl/COBIL00C.cbl} as a sequence. Line 233
     * performs {@code WRITE-TRANSACT-FILE}, line 234 computes the reduced balance and line 235
     * performs {@code UPDATE-ACCTDAT-FILE}, so the ledger row is appended BEFORE the account is
     * touched. The two reads that feed them are asserted in place too: the balance read standing for
     * line 343, the cross-reference read standing for line 408 performed at line 211, and the
     * highest-key read standing for lines 212 to 217.</p>
     *
     * <p>Assumptions: the order here is the OPPOSITE of the nightly posting program's and the
     * difference is deliberately not normalised. {@code app/cbl/CBTRN02C.cbl} orders the same three
     * concerns the other way round -- line 440 performs {@code 2700-UPDATE-TCATBAL}, line 441 performs
     * {@code 2800-UPDATE-ACCOUNT-REC} and line 442 performs {@code 2900-WRITE-TRANSACTION-FILE}, so
     * there the write comes LAST -- while lines 233, 234 and 235 here put it first. Each order is its
     * own program's behaviour: one is an online payment and the other is the posting chain, and
     * harmonising them would mean mistranscribing one of the two. Both line sets are named so a reader
     * meeting the two files does not read the difference as an error in either.</p>
     *
     * <p>Assumptions: within one commit the order is not externally observable, so preserving it buys
     * nothing at run time and is preserved for auditability -- a reader comparing the Java against the
     * source statement by statement needs the sequence to match. That is also why the order is asserted
     * from the collaborators rather than from any stored state: stored state after one commit cannot
     * distinguish the two orders at all.</p>
     */
    @Test
    @DisplayName("write, then compute, then update: the reference's order, not the posting job's")
    void theWriteThenComputeThenUpdateOrderIsPreserved() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        InOrder ordered = inOrder(this.transactions, this.accounts);
        ordered.verify(this.accounts).findAccountBalance(ACCOUNT_ID);
        ordered.verify(this.accounts).findCardXrefByAccountId(ACCOUNT_ID);
        ordered.verify(this.transactions).findMaxTranId();
        ordered.verify(this.transactions).save(any(Transaction.class));
        ordered.verify(this.accounts).applyPayment(ACCOUNT_ID, PAYABLE_BALANCE);
    }

    /**
     * Eight of the ten values written onto the row are constants, carried across character for
     * character.
     *
     * <p>This pins the ten consecutive move statements at lines 220 to 229 of
     * {@code app/cbl/COBIL00C.cbl}. Eight of them carry a hardcoded literal: the type code
     * {@code '02'} at line 220, the category code at line 221, the source {@code 'POS TERM'} at line
     * 222, the description {@code 'BILL PAYMENT - ONLINE'} at line 223, the merchant identifier
     * {@code 999999999} at line 226, the merchant name {@code 'BILL PAYMENT'} at line 227 and the
     * merchant city and merchant zip, both {@code 'N/A'}, at lines 228 and 229.</p>
     *
     * <p>Assumptions: each literal is compared against the characters themselves rather than against
     * the boundary's own constant, which is the only comparison that can catch a mistyped constant. A
     * case asserting equality with the constant it is meant to police would agree with any value that
     * constant happened to hold. Transformation rule T8 governs these as strictly as it governs a
     * screen sentence, because a report renders them.</p>
     *
     * <p>Assumptions: the category code is asserted as four characters, {@code 0002}, where line 221
     * moves the bare numeral {@code 2}. The receiving field is {@code TRAN-CAT-CD PIC 9(04)} at line 7
     * of {@code app/cpy/CVTRA05Y.cpy}, so the numeral lands right-aligned in four digit positions and
     * the stored value carries the leading zeros. Asserting a single character would describe a field
     * the record contract does not declare.</p>
     *
     * <p>Assumptions: the description is asserted with its interior hyphen surrounded by single spaces.
     * The plausible mistranscription is a hyphen without spaces or an em-length dash, both of which
     * read identically in review and neither of which the reference writes.</p>
     */
    @Test
    @DisplayName("the eight literals of lines 220 to 229 are written character for character")
    void theEightLiteralsAreWrittenCharacterForCharacter() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        Transaction written = storedRow();

        // WHY : Refactoring Rationale: each expectation below is a literal rather than a reference to the
        //       boundary's own constant of the same value. Referring to the constant would make this case
        //       agree with whatever that constant holds, which is precisely the thing it exists to
        //       police; spelling the characters out here means the two must independently agree with the
        //       reference.
        assertThat(written.getTranTypeCd()).as("line 220").isEqualTo("02");
        assertThat(written.getTranCatCd()).as("line 221, right-aligned in four digits").isEqualTo("0002");
        assertThat(written.getTranSource()).as("line 222").isEqualTo("POS TERM");
        assertThat(written.getTranDesc()).as("line 223").isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(written.getMerchantId()).as("line 226").isEqualTo(999999999L);
        assertThat(written.getMerchantName()).as("line 227").isEqualTo("BILL PAYMENT");
        assertThat(written.getMerchantCity()).as("line 228").isEqualTo("N/A");
        assertThat(written.getMerchantZip()).as("line 229").isEqualTo("N/A");
    }

    /**
     * The two moves that are NOT literals carry live data, and neither is client input.
     *
     * <p>This pins lines 224 and 225 of {@code app/cbl/COBIL00C.cbl}, which sit among the eight
     * literals and are the only two of the ten that move data. Line 224 moves the stored account
     * balance into the transaction amount and line 225 moves the cross-referenced card number into the
     * card member.</p>
     *
     * <p>Assumptions: the card number is asserted to be the resolved one and NOT anything the request
     * carried, which is why the submitted request in this case names only an account. Line 225 moves
     * the field the read at line 211 populated, so the card the payment is recorded against is the one
     * the account owns rather than one a client could nominate; a target that echoed a submitted card
     * would record a payment against a card the account may not hold.</p>
     *
     * <p>Assumptions: the amount is asserted to be the balance that was read, not a value from the
     * request, because the screen has no amount field at all -- which is the reason the request shape
     * carries no amount component. The two are compared as exact decimal.</p>
     */
    @Test
    @DisplayName("the two data moves of lines 224 and 225 carry the balance and the resolved card")
    void theTwoDataMovesCarryTheBalanceAndTheResolvedCard() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        Transaction written = storedRow();
        assertThat(written.getTranAmt())
                .as("line 224 moves the stored balance, so the amount is the balance")
                .isEqualByComparingTo(PAYABLE_BALANCE.amount());
        assertThat(written.getCardNum())
                .as("line 225 moves the cross-referenced card, never a submitted one")
                .isEqualTo(CARD_NUMBER);
    }

    /**
     * Both timestamp members carry ONE instant, because the reference moves one value to two receivers.
     *
     * <p>This pins lines 230 to 232 of {@code app/cbl/COBIL00C.cbl}. Line 230 performs
     * {@code GET-CURRENT-TIMESTAMP} at line 249 once, and lines 231 and 232 are a single move statement
     * with two receiving fields, the originating timestamp and the processing timestamp. The two are
     * therefore equal by construction rather than by two readings happening to land in the same
     * instant.</p>
     *
     * <p>Assumptions: reading the clock twice would produce members that agree almost always and
     * disagree occasionally, which is the worst available behaviour -- a parity comparison would pass
     * repeatedly and then fail once for a reason no fixture reproduces. Asserting equality under a
     * pinned clock cannot by itself distinguish one reading from two, so the assertion is paired with
     * the rendered-form case below, which is what makes the single reduction observable.</p>
     *
     * <p>Assumptions: the batch program does the OPPOSITE and the contrast is asserted rather than
     * left implicit. {@code app/cbl/CBTRN02C.cbl} takes the originating timestamp from the incoming
     * feed at line 436 and the processing timestamp from the clock at lines 437 and 438, which is the
     * only place it sets the processing member, so there the two members differ. Here one move fills
     * both, so they agree. A shared expectation across the two programs would have to be wrong for one
     * of them.</p>
     */
    @Test
    @DisplayName("one instant reaches both timestamp members, unlike the batch program's two sources")
    void oneInstantReachesBothTimestampMembers() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        Transaction written = storedRow();
        assertThat(written.getOrigTs())
                .as("lines 231 and 232 are one move with two receivers, so the members agree")
                .isEqualTo(written.getProcTs())
                .isEqualTo(PAYMENT_INSTANT);
    }

    /**
     * The stamp renders as twenty-six characters ending in six zero digits.
     *
     * <p>This pins {@code GET-CURRENT-TIMESTAMP} at line 249 of {@code app/cbl/COBIL00C.cbl} through
     * to its terminating period at line 267. Line 263 issues {@code INITIALIZE} on the group, line 264
     * moves the ten-character date into character positions 1 to 10, line 265 moves the eight-character
     * time into positions 12 to 19, and line 266 moves zeros into the six-digit fractional field.</p>
     *
     * <p>Assumptions: characters 11 and 20 are never written by any statement of that paragraph, and
     * that is precisely why the rendered form carries a space and a period there. The group is
     * {@code WS-TIMESTAMP} declared at lines 42 to 55 of {@code app/cpy/CSDAT01Y.cpy}, whose members
     * occupy 4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 6 character positions and so sum to
     * exactly 26. Position 11 is the {@code FILLER PIC X(01) VALUE ' '} of line 48 and position 20 is
     * the {@code FILLER PIC X(01) VALUE '.'} of line 54. Because {@code INITIALIZE} does not touch
     * {@code FILLER} items, both keep the value their declarations give them across line 263, while the
     * date and time separators come from the {@code DATESEP} and {@code TIMESEP} options at lines 258
     * and 260. Line 266 is why the tail is six zeros rather than a live microsecond reading.</p>
     *
     * <p>Assumptions: live seed data corroborates the form independently of that reasoning. Record 2 of
     * {@code app/data/ASCII/dailytran.txt} carries {@code 2022-06-10 19:27:53.000000} in character
     * positions 279 to 304, which is the same twenty-six character shape with the same two separators
     * in the same two places.</p>
     *
     * <p>Assumptions: three twenty-six character forms coexist across the reference programs and none
     * of them is this one generalised, so no shared expectation is written for them. This program
     * produces the zero-microsecond form; {@code app/cbl/CBTRN02C.cbl} carries a fed originating value
     * beside a clock-read processing value; and lines 464 and 465 of {@code app/cbl/COTRN02C.cbl} move
     * ten-character screen dates into twenty-six character members, giving a date-only value padded to
     * the declared width.</p>
     */
    @Test
    @DisplayName("the stamp is twenty-six characters with a space at 11, a period at 20, zeros at 21")
    void theStampRendersAsTwentySixCharactersEndingInZeros() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        String rendered = this.billPaymentMapper.renderTimestamp(storedRow().getOrigTs());
        assertThat(rendered)
                .as("the zero-microsecond form line 266 produces")
                .isEqualTo(RENDERED_TIMESTAMP)
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH)
                .endsWith(".000000");
        assertThat(rendered.charAt(10))
                .as("character position 11 is the FILLER of CSDAT01Y.cpy line 48, never written")
                .isEqualTo(' ');
        assertThat(rendered.charAt(19))
                .as("character position 20 is the FILLER of CSDAT01Y.cpy line 54, never written")
                .isEqualTo('.');
    }

    /**
     * The identifier is derived as one above the highest key already stored, by a maximum-key read.
     *
     * <p>This pins lines 212 to 217 of {@code app/cbl/COBIL00C.cbl}. Line 212 moves high values into
     * the key, line 213 starts a browse at line 441, line 214 reads BACKWARD once at line 472, line 215
     * ends the browse at line 501, line 216 moves the key it landed on into the numeric work field and
     * line 217 adds one.</p>
     *
     * <p>Assumptions: that sequence is a maximum-key probe for identifier generation and is not a page
     * of a list, and the distinction is measurable rather than interpretive: the program contains no
     * {@code READNEXT} paragraph at all, so nothing can step forward from where the backward read
     * landed. Starting from high values and reading backward once therefore reaches exactly the highest
     * key and stops. The faithful target expression is a single highest-key query -- ordering by the
     * key descending and taking one row -- which is the degenerate case of the same key ordering the
     * paged browse uses, so no positional skipping enters anywhere. Calling it a page would also
     * mislead a reader into expecting boundary keys and an availability flag, none of which this screen
     * has: the reference declares no PF7 and no PF8.</p>
     *
     * <p>Assumptions: the three paging members are named individually as well as covered by the
     * catch-all, because a failure against a named member says WHICH browse analogue appeared, whereas
     * a catch-all alone reports only that something extra was called.</p>
     */
    @Test
    @DisplayName("the identifier comes from a highest-key read, and no paging member is touched")
    void theIdentifierComesFromAHighestKeyRead() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);

        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        verify(this.transactions).findMaxTranId();
        assertThat(storedRow().getTranId())
                .as("line 216 takes the highest key and line 217 adds one")
                .isEqualTo(DERIVED_ID);
        verify(this.transactions, never()).findAllByOrderByTranIdAsc(any(Limit.class));
        verify(this.transactions, never())
                .findByTranIdGreaterThanOrderByTranIdAsc(anyString(), any(Limit.class));
        verify(this.transactions, never())
                .findByTranIdLessThanOrderByTranIdDesc(anyString(), any(Limit.class));
    }

    /**
     * An empty ledger yields identifier one, by way of the end-of-file sentinel.
     *
     * <p>This pins the end-of-file arm of {@code READPREV-TRANSACT-FILE}, lines 487 and 488 of
     * {@code app/cbl/COBIL00C.cbl}, together with line 217. When the backward read finds no record the
     * program moves ZEROS into the key at line 488 rather than leaving the high values it set at line
     * 212, and line 217 then adds one -- so the first payment written to an empty ledger takes
     * identifier one and not zero.</p>
     *
     * <p>Assumptions: the value is asserted at the sixteen character declared width with its leading
     * zeros intact. {@code TRAN-ID} is {@code PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}, a
     * character field, while the work field the value is derived through is numeric, so the derived
     * number is re-padded to that width. A numeric identifier would drop the leading zeros the stored
     * form carries and would key on a value the reference never forms.</p>
     */
    @Test
    @DisplayName("an empty ledger yields identifier one, zero-padded to sixteen characters")
    void anEmptyLedgerYieldsTheFirstIdentifier() {
        stubBalanceRead(PAYABLE_BALANCE);
        stubCardResolution();
        stubIdentifierDerivation(null, FIRST_ID);
        stubWriteEchoesTheRow();

        BillPaymentResponse acknowledgement =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(acknowledgement.transactionId())
                .as("the zero sentinel of line 488 plus the increment of line 217 gives one")
                .isEqualTo(FIRST_ID);
        assertThat(Long.parseLong(FIRST_ID)).isEqualTo(BillPaymentService.FIRST_TRANSACTION_ID);
    }

    /**
     * A failed highest-key read is reported with this program's own browse-failure sentence.
     *
     * <p>This pins the residual arm of {@code READPREV-TRANSACT-FILE}, lines 489 to 494 of
     * {@code app/cbl/COBIL00C.cbl}: line 490 traces the response codes, line 491 raises the error flag,
     * line 492 moves the sentence and line 494 repositions the cursor. The same sentence is moved at
     * line 463 for a failed browse start.</p>
     *
     * <p>Assumptions: the sentence is asserted with an UPPERCASE letter in "Transaction", because that
     * is what this program writes and a sibling program writes the same words differently. Line 292 of
     * {@code app/cbl/COTRN01C.cbl} carries the identical wording, so the two agree; the list screen does
     * not, and merging any of them would silently reword a screen.</p>
     *
     * <p>Assumptions: nothing is written and no balance is changed on this path. The reference reaches
     * the sentence with its error flag raised, and the write at line 233 is inside the block line 208
     * gates on that flag being clear, so a failure here abandons the turn before the ledger is touched.
     * </p>
     */
    @Test
    @DisplayName("a failed highest-key read reports the uppercase browse-failure sentence")
    void aFailedHighestKeyReadReportsTheBrowseFailureSentence() {
        stubBalanceRead(PAYABLE_BALANCE);
        stubCardResolution();
        when(this.transactions.findMaxTranId())
                .thenThrow(new IllegalStateException("the ledger could not be read"));

        assertThatThrownBy(
                () -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup Transaction...");

        verify(this.transactions, never()).save(any(Transaction.class));
        verify(this.accounts, never()).applyPayment(anyString(), any(Money.class));
    }

    /**
     * A refused confirmation abandons the turn and emits NO sentence at all.
     *
     * <p>This pins the refusal branch of {@code EVALUATE CONFIRMI}, lines 178 to 181 of
     * {@code app/cbl/COBIL00C.cbl}: lines 178 and 179 match the upper and lower case refusal, line 180
     * performs {@code CLEAR-CURRENT-SCREEN} at line 552 and line 181 raises the error flag. It moves
     * nothing into the message field, so the flag is being used as a control-flow short-circuit rather
     * than to report an operator error.</p>
     *
     * <p>Refactoring Rationale: this branch must NOT be shared with the capture screen's, and the two
     * look similar enough to invite it. Line 173 of {@code app/cbl/COBIL00C.cbl} opens a selection with
     * FOUR branch groups, in which the refusal has its own arm and emits nothing. Line 169 of
     * {@code app/cbl/COTRN02C.cbl} opens a selection with THREE, in which lines 173 to 176 group the
     * refusal together with the never-supplied cases and line 178 then emits
     * {@code 'Confirm to add this transaction...'} for all of them. Four arms against three, and
     * silence against a prompt. A shared evaluator would have to break one of the two programs, so the
     * assertion below states the negative explicitly and also states which sentence must not appear.</p>
     *
     * <p>Assumptions: inventing a sentence here is the specific error this case exists to catch, with
     * "payment cancelled" being the obvious candidate. Transformation rule T8 admits only strings the
     * reference emits, and the reference emits none on this branch.</p>
     *
     * <p>Assumptions: the account IS read on this branch even though nothing is paid, because line 181
     * raises the flag that line 197 re-tests, so the nothing-to-pay advisory is skipped as well. That is
     * why a refused payment on an account with nothing outstanding is answered with silence rather than
     * with the advisory.</p>
     *
     * @param refusal the spelling of refusal this case offers, of type {@code String}, being the upper
     *     case form of line 178 or the lower case form of line 179
     */
    @ParameterizedTest
    @ValueSource(strings = {"N", "n"})
    @DisplayName("a refused confirmation emits no sentence, unlike the capture screen's shared arm")
    void aRefusedConfirmationEmitsNoSentence(String refusal) {
        stubBalanceRead(PAYABLE_BALANCE);

        BillPaymentResponse answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, refusal));

        assertThat(answer.returnMessage())
                .as("lines 178 to 181 move nothing into the message field")
                .isNull();
        assertThat(answer.returnMessage())
                .as("the capture screen's prompt must not leak onto this screen's refusal")
                .isNotEqualTo("Confirm to add this transaction...");
        assertThat(answer.paid()).isFalse();
        assertThat(answer.transactionId()).isNull();
        verify(this.transactions, never()).save(any(Transaction.class));
        verify(this.accounts, never()).applyPayment(anyString(), any(Money.class));
    }

    /**
     * A confirmation outside the accepted values is refused with the sentence both screens share.
     *
     * <p>This pins the residual arm of {@code EVALUATE CONFIRMI}, lines 185 to 189 of
     * {@code app/cbl/COBIL00C.cbl}: line 186 raises the error flag, line 187 moves the complaint and
     * line 189 repositions the cursor onto the confirmation field.</p>
     *
     * <p>Assumptions: this sentence is one of the few carried by TWO programs character for character --
     * line 187 here and line 184 of {@code app/cbl/COTRN02C.cbl} -- so neither screen owns it
     * exclusively and both may assert it. It is asserted here against the characters themselves so that
     * the shared wording is pinned from this side independently of the other.</p>
     *
     * <p>Assumptions: nothing is read on this branch. The reference's residual arm performs no file
     * operation before it moves the complaint and sends the screen, so a target that resolved the
     * account first would charge the account context for a read the reference never performs.</p>
     */
    @Test
    @DisplayName("an unaccepted confirmation is refused with the sentence shared with the capture screen")
    void anUnacceptedConfirmationIsRefusedWithTheSharedSentence() {
        assertThatThrownBy(
                () -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Q")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Invalid value. Valid values are (Y/N)...");

        verify(this.accounts, never()).findAccountBalance(anyString());
        verify(this.transactions, never()).save(any(Transaction.class));
    }

    /**
     * A balance of exactly zero takes the nothing-to-pay branch, because the comparison is inclusive.
     *
     * <p>This pins lines 197 to 203 of {@code app/cbl/COBIL00C.cbl}. Line 198 tests
     * {@code IF ACCT-CURR-BAL <= ZEROS}, line 199 adds the requirement that the submitted account field
     * be neither spaces nor low values, line 200 raises the error flag, line 201 moves the advisory and
     * line 203 repositions the cursor onto the account field.</p>
     *
     * <p>Assumptions: the comparison is INCLUSIVE and a case covering only a negative balance would not
     * pin it, which is why exactly zero is the first value offered. A strict comparison reads perfectly
     * well in review and would let a zero-balance account pay zero, writing a row with a zero amount
     * that the reference never writes. A credit balance -- negative in this record's sign convention --
     * takes the same branch, so it is offered as the second value.</p>
     *
     * <p>Assumptions: the advisory is RETURNED as an ordinary turn rather than raised, because the
     * reference reaches it by exactly the mechanism it reaches the confirmation prompt by: lines 200 to
     * 204 move a sentence and send the screen, as lines 236 to 242 do. Neither is an abend.</p>
     *
     * @param balanceText the balance this case offers, of type {@code String}, being exactly zero at
     *     the inclusive edge of line 198 or a credit balance below it
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-25.00"})
    @DisplayName("a balance at exactly zero, and one below it, both take the nothing-to-pay branch")
    void aBalanceAtExactlyZeroTakesTheNothingToPayBranch(String balanceText) {
        stubBalanceRead(Money.of(balanceText));

        BillPaymentResponse answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(answer.returnMessage())
                .as("line 201's advisory, reached inclusively at zero")
                .isEqualTo("You have nothing to pay...");
        assertThat(answer.paid()).isFalse();
        verify(this.transactions, never()).save(any(Transaction.class));
        verify(this.accounts, never()).applyPayment(anyString(), any(Money.class));
    }

    /**
     * A ten-integer-digit balance is refused rather than silently losing its leading digit.
     *
     * <p>This registers a documented divergence at line 224 of {@code app/cbl/COBIL00C.cbl}. The
     * balance is {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy}, giving
     * ten integer digit positions, while the amount it is moved into is
     * {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy}, giving nine. The
     * reference moves the wider field into the narrower one, so a balance needing ten integer digits
     * loses its high-order digit there and the row records an amount smaller than the payment.</p>
     *
     * <p>Assumptions: the baseline does that silently; the migrated path refuses the assignment instead,
     * and the divergence is documented rather than presented as equivalent. Refusing is chosen because
     * the alternative on this screen is a ledger row that disagrees with the balance change made beside
     * it, and the value is bounded at the narrower picture at the assignment that introduces it rather
     * than at the column, where the complaint would name no field. The condition is latent at balances
     * the seed data does not reach.</p>
     *
     * <p>Assumptions: the refusal arrives BEFORE anything is written, and that is asserted as well as
     * the refusal itself. The row is composed before it is stored, so the bound is reached first, which
     * is what keeps a rejected payment from leaving a balance change behind it.</p>
     */
    @Test
    @DisplayName("a ten-integer-digit balance is refused, not truncated into the nine-digit amount")
    void aTenIntegerDigitBalanceIsRefusedRatherThanTruncated() {
        stubBalanceRead(Money.of("1234567890.99"));
        stubCardResolution();
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of(HIGHEST_STORED_ID));

        assertThatThrownBy(
                () -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .as("the narrower picture of CVTRA05Y.cpy line 10 bounds the assignment")
                .isInstanceOf(ArithmeticException.class);

        verify(this.transactions, never()).save(any(Transaction.class));
        verify(this.accounts, never()).applyPayment(anyString(), any(Money.class));
    }

    /**
     * The account read-for-update lock is load-bearing here, so its protection is carried across.
     *
     * <p>This registers the OPPOSITE ruling to the sibling detail-screen case about the same CICS
     * option. The account read supplies {@code UPDATE} at line 351 of {@code app/cbl/COBIL00C.cbl},
     * with {@code RIDFLD(ACCT-ID)} at line 349, and that lock is then consumed by a genuine
     * {@code EXEC CICS REWRITE} at line 379 inside {@code UPDATE-ACCTDAT-FILE} at line 377, which names
     * its dataset at line 380 and its source record at line 381. Lock acquired, lock used: it protects
     * a real read-then-write window and cannot be dropped.</p>
     *
     * <p>Refactoring Rationale: the identical option in the detail screen IS dropped, and conflating the
     * two would be an error in both directions. Line 275 of {@code app/cbl/COTRN01C.cbl} supplies
     * {@code UPDATE} as well, but that program contains no {@code REWRITE} and no {@code EXEC CICS
     * WRITE} anywhere in its 330 lines, so there the lock is acquired and never used and the target
     * reads without one. Two identical reference options therefore map to two different targets, each
     * decided by whether a write follows it, and the sibling case that pins the detail screen asserts
     * the converse of this one.</p>
     *
     * <p>Assumptions: what carries the protection here is the SHAPE of the change operation rather than
     * a lock this module can take, because the row belongs to the account context and this service holds
     * no privilege on its schema. The operation is handed the amount to reduce BY and returns nothing,
     * so the owner performs the arithmetic against the value as it then stands. An operation that
     * accepted a computed balance instead would reintroduce exactly the window the reference's lock
     * closes: a balance that moved between the read and the write would be overwritten rather than
     * adjusted. The parameter shape is therefore asserted, and it is asserted by name and type so that
     * widening it later fails this case.</p>
     */
    @Test
    @DisplayName("the load-bearing update lock: the change carries an amount, not a computed balance")
    void theLoadBearingUpdateLockIsCarriedAcrossAsAnAmountChange() {
        Method change = null;
        for (Method declared : AccountContextClient.class.getMethods()) {
            if ("applyPayment".equals(declared.getName())) {
                change = declared;
            }
        }

        assertThat(change)
                .as("the port must declare the balance change the rewrite at line 379 stands for")
                .isNotNull();
        assertThat(change.getParameterTypes())
                .as("an amount to subtract, never a balance to assign")
                .containsExactly(String.class, Money.class);
        assertThat(change.getReturnType())
                .as("the change answers with nothing, so no caller can mistake it for a lookup")
                .isEqualTo(void.class);

        stubConfirmedPaymentPath(PAYABLE_BALANCE);
        this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));
        verify(this.accounts).applyPayment(ACCOUNT_ID, PAYABLE_BALANCE);
    }

    /**
     * Exactly one transactional boundary spans the payment, and it is the entry point.
     *
     * <p>Assumptions: the baseline relies on the implicit CICS task-end syncpoint -- no explicit
     * {@code EXEC CICS SYNCPOINT} verb appears in any of the four programs this context migrates -- and
     * the Java expresses the same unit of work as an explicit {@code @Transactional} boundary. This case
     * asserts that there is exactly ONE such boundary and that it is the public entry point, which is
     * what makes the write at line 233 and the balance change at line 235 of
     * {@code app/cbl/COBIL00C.cbl} indivisible in the way the ending task made them indivisible.</p>
     *
     * <p>Assumptions: a boundary declared on a narrower inner member would be silently ineffective, and
     * that is the failure this case is shaped to catch. The boundary is applied by a proxy, so a call
     * this class makes to its own private member does not pass through that proxy; a narrower annotated
     * member would look correct and would commit each write separately -- the worst available outcome,
     * because it is invisible in review and only shows up as a half-applied payment. Counting the
     * boundaries and naming the one that carries it is therefore the assertion, not merely checking that
     * one exists somewhere.</p>
     *
     * <p>Assumptions: the annotation is matched by simple name rather than by type, so this case holds
     * whichever transaction annotation the class carries and does not require that annotation on the
     * test classpath. Naming it as text is what lets a claim about an annotation live in a place that
     * need not depend on it.</p>
     *
     * <p>Assumptions: rollback needs no counterpart to transcribe. There is no
     * {@code SYNCPOINT ROLLBACK} in this program, so a failure is signalled by propagating, which the
     * case below asserts.</p>
     */
    @Test
    @DisplayName("exactly one transactional boundary, and it is the entry point")
    void exactlyOneTransactionalBoundarySpansThePayment() {
        int boundaries = 0;
        for (Method declared : BillPaymentService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(declared.getModifiers())) {
                continue;
            }
            for (Annotation present : declared.getAnnotations()) {
                // WHY : Alternatives Considered: comparing against the annotation TYPE, which would be
                //       stricter and was rejected. The transaction annotation exists under more than one
                //       package across the framework generations this build spans, so a type comparison
                //       would pass or fail on which one the production class happened to import rather
                //       than on whether a boundary is declared. The simple name is the invariant.
                if ("Transactional".equals(present.annotationType().getSimpleName())) {
                    boundaries++;
                    assertThat(declared.getName())
                            .as("the boundary belongs on the entry point, not on an inner member")
                            .isEqualTo("payBalanceInFull");
                }
            }
        }

        assertThat(boundaries)
                .as("one unit of work, matching the one implicit syncpoint the ending task gave")
                .isEqualTo(1);
    }

    /**
     * A refused balance change propagates, so the row written before it cannot stand alone.
     *
     * <p>This pins the residual arm of {@code UPDATE-ACCTDAT-FILE}, lines 396 to 399 of
     * {@code app/cbl/COBIL00C.cbl}, where a response other than normal or not-found raises the error
     * flag and moves {@code 'Unable to Update Account...'}.</p>
     *
     * <p>Assumptions: propagating rather than returning a status is what makes the ordering protective
     * instead of merely faithful. The change is issued last, inside the one boundary, so a refusal here
     * discards the ledger row written at line 233 and the state the reference cannot produce -- a
     * recorded payment beside an unchanged balance -- is unreachable. This case therefore asserts BOTH
     * that the failure escapes and that the write had already happened, since a failure raised before
     * the write would satisfy the first claim while proving nothing about the second.</p>
     *
     * <p>Assumptions: the sentence carried is this operation's own and not the read's. Line 399 differs
     * from the read's sentence at line 368, and the two are not merged.</p>
     */
    @Test
    @DisplayName("a refused balance change propagates, undoing the row written before it")
    void aRefusedBalanceChangePropagatesAndUndoesTheRow() {
        stubConfirmedPaymentPath(PAYABLE_BALANCE);
        org.mockito.Mockito.doThrow(new AccountContextClient.AccountContextUnavailableException(
                        "the balance change was refused", null))
                .when(this.accounts).applyPayment(anyString(), any(Money.class));

        assertThatThrownBy(
                () -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Update Account...");

        // WHY : Assumptions: confirming the write ALREADY happened is what gives the escape above its
        //       meaning. A failure raised before the write would satisfy the assertion above while
        //       proving nothing about rollback, because there would have been nothing to roll back. The
        //       pair together says the boundary had a written row in hand when the refusal reached it.
        verify(this.transactions)
                .save(any(Transaction.class));
    }

    /**
     * The two crossings of the account boundary stay distinguishable, and no breaker guards either.
     *
     * <p>Assumptions: this screen reaches account-owned records TWICE by operations of two different
     * kinds, and the distinction is recorded here so a later reader does not unify them. The
     * cross-reference read standing for line 408 of {@code app/cbl/COBIL00C.cbl} and the account master
     * read standing for line 343 are LOOKUPS: each answers with a value or with a documented absence,
     * which is why both report through an optional and why this class then decides what to do. The
     * balance change standing for the rewrite at line 379 is a WRITE: it answers with nothing, is issued
     * last and its refusal propagates. Asserting the return shapes is how that difference is held --
     * a lookup that answered with nothing could not report absence, and a write that answered with a
     * value would invite a caller to branch on it instead of relying on the boundary.</p>
     *
     * <p>Alternatives Considered: a circuit breaker in front of either crossing. Rejected because the
     * hop is in-network to a service behind an internal load balancer with both timeouts bounded by
     * configuration, so a stalled dependency already surfaces as a refused request within seconds. A
     * breaker would add a state machine that can refuse a call the dependency would have served, which
     * is a new failure mode in exchange for none removed. A retry is rejected on a separate ground: a
     * retry of a payment is a retry of money movement, and the port offers no idempotency key that would
     * make one safe. Neither is declared, and the absence is asserted by name so that introducing one
     * silently is not possible.</p>
     *
     * <p>Assumptions: the resilience annotations are matched by simple name rather than by type,
     * precisely because no resilience library is on this module's classpath -- which is the condition
     * being asserted. A check written against the types would not compile in the state it is meant to
     * confirm.</p>
     */
    @Test
    @DisplayName("two crossings, two operation shapes, and no breaker or retry on either")
    void theTwoAccountCrossingsStayDistinguishableAndUnguarded() {
        // WHY : Trade-offs: the size assertion accompanies the filter because a filter that matched
        //       nothing would otherwise satisfy the per-element check vacuously. Renaming either lookup
        //       on the port would empty the selection, and without the count this case would then go
        //       green having examined no member at all -- the specific way a filtered assertion rots.
        assertThat(AccountContextClient.class.getMethods())
                .filteredOn(declared -> "findAccountBalance".equals(declared.getName())
                        || "findCardXrefByAccountId".equals(declared.getName()))
                .as("both lookups report absence, so both answer with an optional")
                .hasSize(2)
                .allSatisfy(lookup -> assertThat(lookup.getReturnType()).isEqualTo(Optional.class));

        for (Method declared : AccountContextClient.class.getMethods()) {
            assertUnguarded(declared);
        }
        for (Method declared : BillPaymentService.class.getDeclaredMethods()) {
            assertUnguarded(declared);
        }
    }

    /**
     * Confirms one member carries no resilience annotation of any kind.
     *
     * <p>Assumptions: the four names checked are the ones a resilience library would introduce on a
     * synchronous outbound call, and they are compared as text for the reason the calling case records.
     * </p>
     *
     * @param declared the member to check, of type {@link Method}; must not be {@code null}
     */
    private static void assertUnguarded(Method declared) {
        for (Annotation present : declared.getAnnotations()) {
            assertThat(present.annotationType().getSimpleName())
                    .as("member %s must carry no breaker, retry, bulkhead or limiter",
                            declared.getName())
                    .isNotIn("CircuitBreaker", "Retryable", "Retry", "Bulkhead", "RateLimiter",
                            "TimeLimiter");
        }
    }
}
