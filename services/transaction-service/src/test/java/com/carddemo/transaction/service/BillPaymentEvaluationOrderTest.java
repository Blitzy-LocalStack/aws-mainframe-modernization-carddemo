package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    /** The card number the cross-reference resolves for that account. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The stored rows, stubbed per test. */
    private TransactionRepository transactions;

    /** The seam onto the account context, stubbed per test. */
    private AccountContextClient accounts;

    /** The service under test. */
    private BillPaymentService service;

    /** Builds the service over stubs. */
    @BeforeEach
    void setUp() {
        this.transactions = mock(TransactionRepository.class);
        this.accounts = mock(AccountContextClient.class);
        this.service = new BillPaymentService(this.transactions, this.accounts,
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
        verifyNoInteractions(this.transactions);
    }

    /** A refused confirmation on a supplied account abandons the turn with no sentence at all. */
    @Test
    @DisplayName("abandon the turn with no sentence when the confirmation is refused")
    void refusedConfirmationAbandonsTheTurnWithoutASentence() {
        when(this.accounts.findAccountBalance(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.AccountBalance(ACCOUNT_ID, Money.of("100.00"))));

        BillPaymentResponse answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "N"));

        assertThat(answer.paid()).isFalse();
        assertThat(answer.returnMessage()).isNull();
        assertThat(answer.currentBalance()).isEqualTo(Money.of("100.00"));
        verify(this.transactions, never()).save(any());
    }

    /** A never-supplied confirmation on a supplied account is answered with the prompt. */
    @Test
    @DisplayName("prompt for confirmation when the confirmation was never supplied")
    void absentConfirmationIsAnsweredWithThePrompt() {
        when(this.accounts.findAccountBalance(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.AccountBalance(ACCOUNT_ID, Money.of("100.00"))));

        BillPaymentResponse answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, ""));

        assertThat(answer.returnMessage()).isEqualTo(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT);
        verify(this.transactions, never()).save(any());
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
        when(this.accounts.findAccountBalance(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.AccountBalance(ACCOUNT_ID, Money.ZERO)));

        BillPaymentResponse answer =
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"));

        assertThat(answer.returnMessage()).isEqualTo(BillPaymentMapper.MESSAGE_NOTHING_TO_PAY);
        verify(this.transactions, never()).save(any());
    }

    /** An unknown account is reported with the reference's own not-found sentence. */
    @Test
    @DisplayName("report an unknown account with the reference's not-found sentence")
    void unknownAccountIsReportedAsAbsent() {
        when(this.accounts.findAccountBalance(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /** A failed balance update raises, so the payment row cannot stand without the balance change. */
    @Test
    @DisplayName("raise when the balance change fails, so the row rolls back with it")
    void failedBalanceChangeRaisesRatherThanReturning() {
        when(this.accounts.findAccountBalance(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.AccountBalance(ACCOUNT_ID, Money.of("100.00"))));
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("0000000000000008"));
        when(this.transactions.save(any())).thenAnswer(call -> call.getArgument(0));
        org.mockito.Mockito.doThrow(new AccountContextClient.AccountContextUnavailableException(
                        "payment not applied", null))
                .when(this.accounts).applyPayment(anyString(), any());

        assertThatThrownBy(() -> this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED);
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
