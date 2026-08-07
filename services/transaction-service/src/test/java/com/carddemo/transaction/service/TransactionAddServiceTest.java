package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the capture screen takes the reference's branches and emits the reference's sentences.
 *
 * <p>Assumptions: the confirmation is asserted to be settled before any read, because the reference
 * evaluates it at lines 168 to 187 of {@code app/cbl/COTRN02C.cbl} and reaches its add paragraph only from
 * the affirmative branch. A target that resolved the cross-reference first would report a lookup failure
 * for a turn the reference looks nothing up on.</p>
 */
@DisplayName("the capture screen's branches")
class TransactionAddServiceTest {

    /** A well-formed eleven digit account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A well-formed sixteen digit card number. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The stored rows, stubbed per test. */
    private TransactionRepository transactions;

    /** The seam onto the account context, stubbed per test. */
    private AccountContextClient accounts;

    /** The service under test. */
    private TransactionAddService service;

    /** Builds the service over stubs and a real mapper. */
    @BeforeEach
    void setUp() {
        this.transactions = mock(TransactionRepository.class);
        this.accounts = mock(AccountContextClient.class);
        this.service = new TransactionAddService(this.transactions, this.accounts,
                new TransactionMapper());
    }

    /**
     * Builds a submission that differs from the accepted one only in the components named.
     *
     * @param accountId the account identifier to submit, possibly empty
     * @param cardNumber the card number to submit, possibly empty
     * @param confirmation the confirmation discriminator to submit, possibly empty
     * @return the submission, never {@code null}
     */
    private static TransactionAddRequest submission(String accountId, String cardNumber,
            String confirmation) {
        return new TransactionAddRequest(accountId, "01", "0001", "POS", "GROCERY PURCHASE",
                Money.of("125.50"), "000000000", "CORNER STORE", "SEATTLE", "98101", cardNumber,
                "2026-01-15", "2026-01-16", confirmation);
    }

    /** A never-supplied confirmation is answered with the prompt and writes nothing. */
    @Test
    @DisplayName("prompt to confirm when the confirmation was never supplied")
    void absentConfirmationIsAnsweredWithThePrompt() {
        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", ""));

        assertThat(answer.returnMessage()).isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
        assertThat(answer.transactionId()).isNull();
        verifyNoInteractions(this.accounts);
        verify(this.transactions, never()).save(any());
    }

    /** A refused confirmation shares the prompt with the never-supplied case, as the reference does. */
    @Test
    @DisplayName("share the prompt between the refused and never-supplied confirmations")
    void refusedConfirmationSharesThePrompt() {
        assertThat(this.service.addTransaction(submission(ACCOUNT_ID, "", "N")).returnMessage())
                .isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
    }

    /** Any other confirmation value is refused with the reference's own complaint. */
    @Test
    @DisplayName("refuse any other confirmation value")
    void otherConfirmationValueIsRefused() {
        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Q")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_INVALID_CONFIRMATION);
    }

    /** A confirmed submission carrying neither key is refused with the reference's own complaint. */
    @Test
    @DisplayName("require one of the two keys")
    void neitherKeySuppliedIsRefused() {
        assertThatThrownBy(() -> this.service.addTransaction(submission("", "", "Y")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_KEY_REQUIRED);
    }

    /** An unresolved account identifier reports the account-keyed absence sentence. */
    @Test
    @DisplayName("report the account-keyed absence with the reference's account sentence")
    void unresolvedAccountReportsAccountAbsence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /** An unresolved card number reports the card-keyed absence sentence, which is a different one. */
    @Test
    @DisplayName("report the card-keyed absence with the reference's card sentence")
    void unresolvedCardReportsCardAbsence() {
        when(this.accounts.findCardXrefByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.addTransaction(submission("", CARD_NUMBER, "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_CARD_NOT_FOUND);
        assertThat(TransactionAddService.MESSAGE_CARD_NOT_FOUND)
                .isNotEqualTo(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /** A failing cross-reference read is reported with the sentence for the key that was used. */
    @Test
    @DisplayName("report a failing lookup with the sentence for the key it was keyed by")
    void failingLookupReportsTheKeyedSentence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenThrow(
                new AccountContextClient.AccountContextUnavailableException("seam", null));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED);
    }

    /** A confirmed submission writes one row keyed one past the highest existing identifier. */
    @Test
    @DisplayName("derive the next identifier as one past the highest stored one")
    void confirmedSubmissionDerivesTheNextIdentifier() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("0000000000000008"));
        when(this.transactions.save(any())).thenAnswer(call -> call.getArgument(0));

        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.transactionId()).isEqualTo("0000000000000009");
        assertThat(answer.returnMessage())
                .startsWith(TransactionAddService.MESSAGE_ADDED_PREFIX)
                .contains(TransactionAddService.MESSAGE_ADDED_INFIX)
                .endsWith("0000000000000009" + TransactionAddService.MESSAGE_ADDED_SUFFIX);
    }

    /** An empty table yields the first identifier, which the reference also produces. */
    @Test
    @DisplayName("derive the first identifier from an empty table")
    void emptyTableYieldsTheFirstIdentifier() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        when(this.transactions.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")).transactionId())
                .isEqualTo("0000000000000001");
    }

    /** A failing write is reported with the reference's own failed-write sentence. */
    @Test
    @DisplayName("report a failing write with the reference's failed-write sentence")
    void failingWriteReportsTheReferenceSentence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("0000000000000008"));
        when(this.transactions.save(any())).thenThrow(new RuntimeException("constraint"));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_ADD_FAILED);
    }
}
