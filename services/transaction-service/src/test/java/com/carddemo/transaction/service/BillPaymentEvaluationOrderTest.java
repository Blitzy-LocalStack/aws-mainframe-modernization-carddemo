package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.dto.BillPaymentPreview;
import com.carddemo.transaction.dto.BillPaymentOutcome;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.repository.AccountBalanceRepository;
import com.carddemo.transaction.repository.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Proves the payment screen evaluates its submission in the reference's own order.
 *
 * <p>Purpose: the order is what decides which sentence an operator sees for a submission that is deficient
 * in more than one way, and the commonest first-turn submission is deficient in two -- both the account
 * identifier and the confirmation are empty. The reference settles the account identifier at line 159 of
 * {@code app/cbl/COBIL00C.cbl} and reaches its confirmation evaluation at line 173 only for a submission
 * that survived it, so a target that evaluated the confirmation first would answer that submission with the
 * wrong field entirely.</p>
 */
@DisplayName("the payment screen's evaluation order")
class BillPaymentEvaluationOrderTest {

    /** A fixed clock so the payment timestamp is deterministic. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2022-07-18T12:00:00Z"), ZoneOffset.UTC);

    /** A well-formed eleven digit account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * The same identifier as the database binds it.
     *
     * <p>Assumptions: {@code account.accounts.account_id} is {@code BIGINT} at line 215 of
     * account-service's {@code V1__account.sql} while the request component carries eleven digit
     * characters, so the service parses before it binds. The stubs below are keyed by the parsed value
     * for that reason -- keying them by the character form would stub a call the service never makes and
     * every balance would read as absent.</p>
     */
    private static final long ACCOUNT_KEY = 11L;

    /** The card number the cross-reference resolves for that account. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The stored rows, stubbed per test. */
    private TransactionRepository transactions;

    /** The seam onto the account context's cross-reference, stubbed per test. */
    private AccountContextClient accounts;

    /** The two statements over the account-owned balance column, stubbed per test. */
    private AccountBalanceRepository accountBalances;

    /** The service under test. */
    private BillPaymentService service;

    /** Builds the service over stubs. */
    @BeforeEach
    void setUp() {
        this.transactions = mock(TransactionRepository.class);
        this.accounts = mock(AccountContextClient.class);
        this.accountBalances = mock(AccountBalanceRepository.class);
        this.service = new BillPaymentService(this.transactions, this.accounts, this.accountBalances,
                new BillPaymentMapper(), FIXED);
    }


    /** A submission with both fields empty is answered about the account identifier alone. */
    @Test
    @DisplayName("settle a blank account identifier before looking at the confirmation")
    void blankAccountIsSettledBeforeTheConfirmation() {
        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest("", "")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY)
                .extracting(failure -> ((ClientInputException) failure).field())
                .isEqualTo(BillPaymentMapper.ACCOUNT_ID_FIELD);

        // WHY : Assumptions: the seam is asserted untouched as well as the sentence asserted correct,
        //       because the reference reads no file at all on this turn. A target that answered with the
        //       right sentence after reading the account would be charging the account context for a read
        //       the reference never performs, which a message assertion alone cannot detect.
        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.accountBalances);
        verifyNoInteractions(this.transactions);
    }

    /**
     * A refused confirmation abandons the turn with no sentence, no balance and no account access.
     *
     * @param refusal the spelling of refusal this case offers, of type {@link String}, being the upper
     *     case form of line 178 of {@code app/cbl/COBIL00C.cbl} or the lower case form of line 179
     */
    @ParameterizedTest(name = "confirmation \"{0}\"")
    @ValueSource(strings = {"N", "n"})
    @DisplayName("abandon the turn with no sentence, no balance and no account read when refused")
    void refusedConfirmationAbandonsTheTurnWithoutASentence(String refusal) {
        BillPaymentOutcome answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, refusal));

        assertThat(answer).isInstanceOf(BillPaymentPreview.class);
        BillPaymentPreview preview = (BillPaymentPreview) answer;
        assertThat(preview.paid()).isFalse();
        assertThat(preview.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(preview.returnMessage()).isNull();

        // WHY : Assumptions: the absent balance is asserted, not merely left unexamined. The reference's
        //       CLEAR-CURRENT-SCREEN at line 180 of app/cbl/COBIL00C.cbl blanks the display fields on
        //       this branch, so a body carrying a balance would show an operator a figure the baseline
        //       has just removed from view.
        assertThat(preview.payableBalance()).isNull();

        // WHY : Assumptions: the account collaborators are asserted COMPLETELY untouched, which is the
        //       whole point of this branch and cannot be seen from the body. Only lines 177 and 184
        //       reach READ-ACCTDAT-FILE at line 343; line 178 is this branch. A read here would also
        //       make the branch FAIL on an unknown identifier -- 404 where the baseline clears the
        //       screen and says nothing -- so absence of interaction is the assertion that catches it.
        verifyNoInteractions(this.accountBalances);
        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.transactions);
    }

    /** A never-supplied confirmation on a supplied account is answered with the prompt. */
    @Test
    @DisplayName("prompt for confirmation when the confirmation was never supplied")
    void absentConfirmationIsAnsweredWithThePrompt() {
        when(this.accountBalances.findCurrentBalance(ACCOUNT_KEY))
                .thenReturn(Optional.of(Money.of("100.00")));

        BillPaymentOutcome answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, ""));

        assertThat(answer).isInstanceOf(BillPaymentPreview.class);
        assertThat(((BillPaymentPreview) answer).returnMessage())
                .isEqualTo(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT);
        assertThat(((BillPaymentPreview) answer).payableBalance()).isEqualTo(Money.of("100.00"));

        // WHY : Assumptions: the reporting turn is asserted to read WITHOUT the lock, because a turn
        //       that writes nothing must not hold a row for the whole request -- one operator's
        //       unconfirmed preview would otherwise block another operator's payment.
        verify(this.accountBalances, never()).lockCurrentBalance(anyLong());
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /** Any other confirmation value is refused with the reference's own complaint. */
    @Test
    @DisplayName("refuse any other confirmation value")
    void otherConfirmationValueIsRefused() {
        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Q")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION);
        verifyNoInteractions(this.transactions);
    }

    /** A balance already at zero takes the nothing-to-pay branch inclusively. */
    @Test
    @DisplayName("answer a zero balance with the nothing-to-pay advisory")
    void zeroBalanceTakesTheNothingToPayBranch() {
        when(this.accountBalances.lockCurrentBalance(ACCOUNT_KEY)).thenReturn(Optional.of(Money.ZERO));

        BillPaymentOutcome answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(answer).isInstanceOf(BillPaymentPreview.class);
        assertThat(((BillPaymentPreview) answer).returnMessage())
                .isEqualTo(BillPaymentMapper.MESSAGE_NOTHING_TO_PAY);
        verify(this.transactions, never()).saveAndFlush(any());
        verify(this.accountBalances, never()).reduceCurrentBalance(anyLong(), any());
    }

    /**
     * A balance too wide for the ledger amount is refused before the seam and before the allocator.
     *
     * <p>Purpose: this is the position of the width refusal registered as
     * {@code D-BILLPAY-AMOUNT-WIDTH-REFUSED}, and position is the whole of what this case asserts. The
     * refusal cannot precede the balance read, because the balance IS the amount being judged -- line 224
     * of {@code app/cbl/COBIL00C.cbl} moves it verbatim and this screen carries no amount field. What it
     * must precede is everything that SPENDS something: the cross-reference read crosses the seam to the
     * account context, and the identifier allocation advances a database sequence whose consumed value no
     * rollback returns, so a refused payment that reached either would leave a cost behind it.</p>
     *
     * <p>Assumptions: the two collaborators are asserted untouched rather than merely left unstubbed,
     * because this class builds plain mocks with no strict-stubbing check -- an unstubbed call here answers
     * a default and passes silently, so absence of interaction has to be asserted to be proved.</p>
     */
    @Test
    @DisplayName("refuse a too-wide balance before the cross-reference read and the allocator")
    void aTooWideBalanceIsRefusedBeforeTheSeamAndTheAllocator() {
        when(this.accountBalances.lockCurrentBalance(ACCOUNT_KEY))
                .thenReturn(Optional.of(Money.of("9999999999.99")));

        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(BillPaymentService.MESSAGE_PAYMENT_ADD_FAILED);

        verifyNoInteractions(this.accounts);
        verify(this.transactions, never()).allocateTransactionId();
        verify(this.transactions, never()).saveAndFlush(any());
        verify(this.accountBalances, never()).reduceCurrentBalance(anyLong(), any());
    }

    /**
     * A ten-integer-digit CREDIT balance takes the nothing-to-pay branch, not the width refusal.
     *
     * <p>Purpose: this pins the width refusal as sitting AFTER line 198's balance test rather than before
     * it, which is the one ordering decision the case above cannot show. A credit balance is negative in
     * this record's sign convention, so line 198's inclusive test claims it and no payment is attempted on
     * it at all -- which makes line 201's advisory the reference's own answer, and a width complaint about
     * an amount that was never going to be written a sentence the baseline never emits.</p>
     */
    @Test
    @DisplayName("answer a ten-digit credit balance with the advisory, not the width refusal")
    void aTooWideCreditBalanceTakesTheNothingToPayBranch() {
        when(this.accountBalances.lockCurrentBalance(ACCOUNT_KEY))
                .thenReturn(Optional.of(Money.of("-9999999999.99")));

        BillPaymentOutcome answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(answer).isInstanceOf(BillPaymentPreview.class);
        assertThat(((BillPaymentPreview) answer).returnMessage())
                .isEqualTo(BillPaymentMapper.MESSAGE_NOTHING_TO_PAY);
        verifyNoInteractions(this.accounts);
        verify(this.transactions, never()).allocateTransactionId();
    }

    /** An unknown account is reported with the reference's own not-found sentence. */
    @Test
    @DisplayName("report an unknown account with the reference's not-found sentence")
    void unknownAccountIsReportedAsAbsent() {
        when(this.accountBalances.lockCurrentBalance(ACCOUNT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /** A failed balance update raises, so the payment row cannot stand without the balance change. */
    @Test
    @DisplayName("raise when the balance change fails, so the row rolls back with it")
    void failedBalanceChangeRaisesRatherThanReturning() {
        when(this.accountBalances.lockCurrentBalance(ACCOUNT_KEY))
                .thenReturn(Optional.of(Money.of("100.00")));
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
        when(this.transactions.allocateTransactionId()).thenReturn(9L);
        when(this.transactions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        // WHY : Assumptions: the refusal is expressed as an affected-row count of ZERO rather than as a
        //       thrown failure, because that is the form the reference's line 391 not-found status takes
        //       through a SQL UPDATE. Both forms must raise, and the count form is the one a reader is
        //       likelier to assume succeeds, so it is the one asserted here.
        when(this.accountBalances.reduceCurrentBalance(ACCOUNT_KEY, Money.of("100.00"))).thenReturn(0);

        // WHY : ⚠️ Assumptions: the sentence expected is the NOT-FOUND one, where this case previously
        //       expected the update-failure one. A count of zero is how a SQL update reports the condition
        //       the reference reads as a not-found status, and line 392 of app/cbl/COBIL00C.cbl answers
        //       that condition with 'Account ID NOT found...' -- the same string lines 361 and 425 emit --
        //       while line 399 is reserved for any other status. The two branches carry different
        //       sentences, so expecting one for the other would have accepted a reworded branch.
        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /**
     * A balance change that RAISES rather than reporting zero rows also aborts the turn.
     *
     * <p>Assumptions: the failure injected is the framework's data-access family, which is what the
     * balance statements raise once the repository stereotype has translated them, and NOT a transport
     * failure -- the balance change is issued on this module's own connection rather than over HTTP, so a
     * transport failure is not a condition this path can reach at all.</p>
     *
     * <p>Refactoring Rationale: this case sits beside the zero-row-count one above rather than replacing
     * it, because the two are different failures of the same statement and only one of them was asserted.
     * A count of zero is the form the reference's not-found status takes through a SQL update, and a
     * translated exception is the form every other database failure takes; an implementation that handled
     * the count and let the exception through would have passed the case above.</p>
     */
    @Test
    @DisplayName("raise when the balance statement itself fails, not only when it affects no row")
    void aTranslatedBalanceFailureAlsoAbortsTheTurn() {
        when(this.accountBalances.lockCurrentBalance(ACCOUNT_KEY))
                .thenReturn(Optional.of(Money.of("100.00")));
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
        when(this.transactions.allocateTransactionId()).thenReturn(9L);
        when(this.transactions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(this.accountBalances.reduceCurrentBalance(anyLong(), any(Money.class)))
                .thenThrow(new DataAccessResourceFailureException("the statement did not complete"));

        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .as("the payment row may not stand on a balance change that never completed")
                .isInstanceOf(RuntimeException.class);
    }

    /** The field-error boundary reports the account alone when the account is blank. */
    @Test
    @DisplayName("report the account entry alone when the account is blank, whatever the confirmation")
    void fieldErrorsShortCircuitOnABlankAccount() {
        BillPaymentMapper mapper = new BillPaymentMapper();

        assertThat(mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.BLANK))
                .singleElement()
                .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                .containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD,
                        BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY);
    }
}
