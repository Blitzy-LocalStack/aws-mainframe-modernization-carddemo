package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.Money;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Proves the capture screen takes the reference's branches and emits the reference's sentences.
 *
 * <p>Assumptions: the phases are asserted in the reference's own order -- key fields at line 166 of
 * {@code app/cbl/COTRN02C.cbl}, data fields at line 167 and only then the confirmation from line 169 --
 * because that order is observable. A submission carrying neither key and a refused confirmation is
 * answered with the key complaint at line 226, not with the confirmation prompt at line 178.</p>
 *
 * <p>Assumptions: the transaction manager is stubbed to run the callback inline, so the write span the
 * service opens is exercised without a database. That keeps the branch assertions here about the
 * reference's logic and leaves the real span to the repository integration tests.</p>
 */
@DisplayName("the capture screen's branches")
class TransactionAddServiceTest {

    /** A well-formed eleven digit account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A well-formed sixteen digit card number. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A second card number, used to prove which of two submitted keys wins. */
    private static final String OTHER_CARD_NUMBER = "5500000000000004";

    /** The stored rows, stubbed per test. */
    private TransactionRepository transactions;

    /** The seam onto the account context, stubbed per test. */
    private AccountContextClient accounts;

    /** The service under test. */
    private TransactionAddService service;

    /** Builds the service over stubs, a real mapper and an inline transaction manager. */
    @BeforeEach
    void setUp() {
        this.transactions = mock(TransactionRepository.class);
        this.accounts = mock(AccountContextClient.class);
        this.service = new TransactionAddService(this.transactions, this.accounts,
                new TransactionMapper(), inlineTransactionManager());
    }

    /**
     * Builds a transaction manager whose spans begin and end without a database.
     *
     * @return a manager that answers every request with a fresh status and commits it silently, never
     *     {@code null}
     */
    private static PlatformTransactionManager inlineTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        return manager;
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

    /**
     * Builds an accepted submission with one component replaced, so one rule can be exercised alone.
     *
     * @param original the submission to derive from; must not be {@code null}
     * @param typeCode the type code to submit, possibly empty
     * @param categoryCode the category code to submit, possibly empty
     * @param source the source to submit, possibly empty
     * @param description the description to submit, possibly empty
     * @param amount the amount to submit, possibly {@code null}
     * @param merchantId the merchant identifier to submit, possibly empty
     * @param originDate the origination date to submit, possibly empty
     * @param processDate the processing date to submit, possibly empty
     * @return the derived submission, never {@code null}
     */
    private static TransactionAddRequest with(TransactionAddRequest original, String typeCode,
            String categoryCode, String source, String description, Money amount, String merchantId,
            String originDate, String processDate) {
        return new TransactionAddRequest(original.accountId(), typeCode, categoryCode, source,
                description, amount, merchantId, original.merchantName(), original.merchantCity(),
                original.merchantZip(), original.cardNumber(), originDate, processDate,
                original.confirmation());
    }

    /**
     * Stubs the account-keyed lookup to resolve to the given card number.
     *
     * @param cardNumber the card number the cross-reference entry carries; must not be {@code null}
     */
    private void accountResolvesTo(String cardNumber) {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, cardNumber)));
    }

    /** Stubs the append to return whatever row it was handed. */
    private void appendEchoesTheRow() {
        when(this.transactions.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** A never-supplied confirmation is answered with the prompt and writes nothing. */
    @Test
    @DisplayName("prompt to confirm when the confirmation was never supplied")
    void absentConfirmationIsAnsweredWithThePrompt() {
        accountResolvesTo(CARD_NUMBER);

        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", ""));

        assertThat(answer.returnMessage()).isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
        assertThat(answer.transactionId()).isNull();
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * A refused confirmation shares the prompt with the never-supplied case, as the reference does.
     *
     * <p>Assumptions: the payment screen behaves differently here on purpose --
     * {@code app/cbl/COBIL00C.cbl} gives its refusal its own arm at lines 178 to 181 which emits no
     * message at all -- so this assertion is what stops the two being unified.</p>
     */
    @Test
    @DisplayName("share the prompt between the refused and never-supplied confirmations")
    void refusedConfirmationSharesThePrompt() {
        accountResolvesTo(CARD_NUMBER);

        assertThat(this.service.addTransaction(submission(ACCOUNT_ID, "", "N")).returnMessage())
                .isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
    }

    /** Any other confirmation value is refused with the reference's own complaint. */
    @Test
    @DisplayName("refuse any other confirmation value")
    void otherConfirmationValueIsRefused() {
        accountResolvesTo(CARD_NUMBER);

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Q")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_INVALID_CONFIRMATION);
    }

    /** Neither key supplied is refused before the confirmation is ever consulted. */
    @Test
    @DisplayName("refuse a submission carrying neither key, whatever the confirmation says")
    void neitherKeySuppliedIsRefused() {
        assertThatThrownBy(() -> this.service.addTransaction(submission("", "", "N")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_KEY_REQUIRED);
    }

    /**
     * Both keys supplied resolves through the account alone and persists the resolved card.
     *
     * <p>Assumptions: this is the silent discard at line 209. The submitted card number never reaches the
     * row, and no message reports that it was replaced.</p>
     */
    @Test
    @DisplayName("resolve through the account identifier alone and persist the resolved card number")
    void bothKeysResolveThroughTheAccountAndPersistTheResolvedCard() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        appendEchoesTheRow();

        this.service.addTransaction(submission(ACCOUNT_ID, OTHER_CARD_NUMBER, "Y"));

        ArgumentCaptor<Transaction> appended = ArgumentCaptor.forClass(Transaction.class);
        verify(this.transactions).saveAndFlush(appended.capture());
        assertThat(appended.getValue().getCardNum()).isEqualTo(CARD_NUMBER);
        verify(this.accounts, never()).findCardXrefByCardNumber(any());
    }

    /** An unresolved account identifier reports the reference's account-absence sentence. */
    @Test
    @DisplayName("report an unresolved account identifier with the account-absence sentence")
    void unresolvedAccountReportsAccountAbsence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /** An unresolved card number reports the reference's card-absence sentence. */
    @Test
    @DisplayName("report an unresolved card number with the card-absence sentence")
    void unresolvedCardReportsCardAbsence() {
        when(this.accounts.findCardXrefByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.addTransaction(submission("", CARD_NUMBER, "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_CARD_NOT_FOUND);
        assertThat(TransactionAddService.MESSAGE_CARD_NOT_FOUND)
                .isNotEqualTo(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /** A failing account-keyed lookup reports that direction's own failed-read sentence. */
    @Test
    @DisplayName("report a failing account-keyed lookup with that direction's sentence")
    void failingAccountLookupReportsTheKeyedSentence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenThrow(
                new AccountContextClient.AccountContextUnavailableException("unreachable",
                        new IllegalStateException("transport")));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED);
    }

    /** A failing card-keyed lookup reports that direction's own failed-read sentence. */
    @Test
    @DisplayName("report a failing card-keyed lookup with that direction's sentence")
    void failingCardLookupReportsTheKeyedSentence() {
        when(this.accounts.findCardXrefByCardNumber(CARD_NUMBER)).thenThrow(
                new AccountContextClient.AccountContextUnavailableException("unreachable",
                        new IllegalStateException("transport")));

        assertThatThrownBy(() -> this.service.addTransaction(submission("", CARD_NUMBER, "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_CARD_XREF_LOOKUP_FAILED);
    }

    /** A key that is not composed of digits reports that key's own composition sentence. */
    @Test
    @DisplayName("refuse a key that is not composed of digits")
    void nonNumericKeyIsRefused() {
        assertThatThrownBy(() -> this.service.addTransaction(submission("0000000001A", "", "Y")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.ACCOUNT_ID_NOT_NUMERIC);

        assertThatThrownBy(() -> this.service.addTransaction(submission("", "411111111111111X", "Y")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.CARD_NUMBER_NOT_NUMERIC);
    }

    /**
     * Several absent data fields produce exactly ONE message, the highest priority one.
     *
     * <p>Assumptions: every arm of the eleven-way construct at lines 251 to 320 ends the CICS task
     * through the shared exit, whose {@code EXEC CICS RETURN} at lines 530 to 534 terminates it, so the
     * written order of the arms is the priority order and only the first can be reported.</p>
     */
    @Test
    @DisplayName("report exactly one message for several absent data fields, the highest priority one")
    void severalAbsentDataFieldsReportOnlyTheHighestPriorityOne() {
        accountResolvesTo(CARD_NUMBER);
        TransactionAddRequest allBlank = with(submission(ACCOUNT_ID, "", "Y"), "", "", "", "",
                Money.of("125.50"), "", "2026-01-15", "2026-01-16");

        assertThatThrownBy(() -> this.service.addTransaction(allBlank))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.TYPE_CODE_REQUIRED);
    }

    /** The mandatory chain reports each field's own sentence, in the reference's written order. */
    @Test
    @DisplayName("walk the mandatory chain in the reference's written order")
    void mandatoryChainFollowsTheReferenceOrder() {
        accountResolvesTo(CARD_NUMBER);
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");

        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "", "", "",
                Money.of("125.50"), "", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.CATEGORY_CODE_REQUIRED);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "", "",
                Money.of("125.50"), "", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.SOURCE_REQUIRED);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "",
                Money.of("125.50"), "", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.DESCRIPTION_REQUIRED);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                null, "", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.AMOUNT_REQUIRED);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "", "", "2026-01-16")))
                .hasMessage(TransactionAddRequest.ORIGIN_DATE_REQUIRED);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "", "2026-01-15", "")))
                .hasMessage(TransactionAddRequest.PROCESS_DATE_REQUIRED);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.MERCHANT_ID_REQUIRED);
    }

    /** A code field that is not composed of digits reports its own composition sentence. */
    @Test
    @DisplayName("refuse a code field that is not composed of digits")
    void nonNumericCodeFieldIsRefused() {
        accountResolvesTo(CARD_NUMBER);
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");

        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "0A", "0001", "POS", "X",
                Money.of("125.50"), "000000000", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.TYPE_CODE_NOT_NUMERIC);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "000A", "POS", "X",
                Money.of("125.50"), "000000000", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.CATEGORY_CODE_NOT_NUMERIC);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "00000000A", "2026-01-15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.MERCHANT_ID_NOT_NUMERIC);
    }

    /**
     * An amount too wide for the edited shape reports the format sentence even though it parses.
     *
     * <p>Assumptions: the four alternatives at lines 340 to 343 test positions rather than
     * parseability, so a perfectly valid decimal needing more than eight integer digits is refused --
     * its rendering pushes the decimal point past the tenth character.</p>
     */
    @Test
    @DisplayName("refuse an amount the twelve-character edited shape cannot hold")
    void amountOutsideTheEditedShapeIsRefused() {
        accountResolvesTo(CARD_NUMBER);
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");

        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("100000000.00"), "000000000", "2026-01-15", "2026-01-16")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.AMOUNT_FORMAT);
    }

    /** A date of the wrong shape reports the LEXICAL sentence and never the calendar one. */
    @Test
    @DisplayName("report a misshapen date with the lexical sentence, not the calendar one")
    void misshapenDateReportsTheLexicalSentence() {
        accountResolvesTo(CARD_NUMBER);
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");

        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "000000000", "2026/01/15", "2026-01-16")))
                .hasMessage(TransactionAddRequest.ORIGIN_DATE_FORMAT);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "000000000", "2026-01-15", "2026/01/16")))
                .hasMessage(TransactionAddRequest.PROCESS_DATE_FORMAT);
    }

    /**
     * A well-shaped date denoting no calendar day reports the CALENDAR sentence.
     *
     * <p>Assumptions: the four date sentences on this screen are all distinct, and this test pairs with
     * the lexical one above to hold them apart -- lines 360 and 375 name the form, lines 401 and 421
     * report that a well-formed value is not a date.</p>
     */
    @Test
    @DisplayName("report a well-shaped but impossible date with the calendar sentence")
    void impossibleDateReportsTheCalendarSentence() {
        accountResolvesTo(CARD_NUMBER);
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");

        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "000000000", "2026-02-31", "2026-01-16")))
                .hasMessage(TransactionAddService.MESSAGE_ORIGIN_DATE_INVALID);
        assertThatThrownBy(() -> this.service.addTransaction(with(accepted, "01", "0001", "POS", "X",
                Money.of("125.50"), "000000000", "2026-01-15", "2026-02-31")))
                .hasMessage(TransactionAddService.MESSAGE_PROCESS_DATE_INVALID);

        assertThat(TransactionAddService.MESSAGE_ORIGIN_DATE_INVALID)
                .isNotEqualTo(TransactionAddRequest.ORIGIN_DATE_FORMAT);
        assertThat(TransactionAddService.MESSAGE_PROCESS_DATE_INVALID)
                .isNotEqualTo(TransactionAddRequest.PROCESS_DATE_FORMAT);
    }

    /**
     * A date the utility rejects with the forgiven message number raises nothing at all.
     *
     * <p>Assumptions: this is the tolerance at lines 400 and 420, which raise only when the returned
     * message number is not {@code 2513}. The unsupported-range outcome carries exactly that number, and
     * the date-edit kernel names it as its unsupported-range constant, so a date outside the supported
     * span is accepted here where a malformed one is not.</p>
     */
    @Test
    @DisplayName("forgive the date condition the reference forgives")
    void forgivenDateConditionRaisesNothing() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        appendEchoesTheRow();
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");

        TransactionAddResponse answer = this.service.addTransaction(with(accepted, "01", "0001",
                "POS", "X", Money.of("125.50"), "000000000", "1200-01-15", "1200-01-16"));

        assertThat(answer.transactionId()).isEqualTo("0000000000000001");
        verify(this.transactions).saveAndFlush(any());
    }

    /** An empty table yields the first identifier, zero-padded to the declared width. */
    @Test
    @DisplayName("derive the first identifier from an empty table")
    void emptyTableYieldsTheFirstIdentifier() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        appendEchoesTheRow();

        assertThat(this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")).transactionId())
                .isEqualTo("0000000000000001");
    }

    /** A populated table yields the stored maximum plus one, zero-padded to the declared width. */
    @Test
    @DisplayName("derive the next identifier as the stored maximum plus one")
    void populatedTableYieldsTheMaximumPlusOne() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("0000000000000008"));
        appendEchoesTheRow();

        assertThat(this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")).transactionId())
                .isEqualTo("0000000000000009");
    }

    /** A failing maximum read is reported with the reference's own failed-read sentence. */
    @Test
    @DisplayName("report a failing maximum read with the reference's failed-read sentence")
    void failingMaximumReadReportsTheReferenceSentence() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenThrow(new RuntimeException("unreadable"));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED);
    }

    /**
     * A duplicate identifier is reported as a conflict carrying the reference's duplicate sentence.
     *
     * <p>Assumptions: the two CICS conditions at lines 735 and 736 fall through to one arm whose
     * sentence is at line 738, so one conflict kind covers both and the shared kernel renders it as the
     * published 409. The singular verb is the reference's and is carried across as written.</p>
     */
    @Test
    @DisplayName("report a duplicate identifier as a conflict")
    void duplicateIdentifierIsReportedAsAConflict() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        when(this.transactions.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(RecordConflictException.class)
                .satisfies(failure -> assertThat(((RecordConflictException) failure).kind())
                        .isEqualTo(RecordConflictException.Kind.DUPLICATE_KEY));

        // WHY : Assumptions: the sentence is asserted HERE as well as the kind, because the kind alone
        //       would let the shared mapping be changed to render other wording without this screen's
        //       test noticing. The published contract for this operation promises this exact text.
        assertThat(GlobalExceptionHandler.MESSAGE_DUPLICATE_KEY).isEqualTo("Tran ID already exist...");
    }

    /** A failing write is reported with the reference's own failed-write sentence. */
    @Test
    @DisplayName("report a failing write with the reference's failed-write sentence")
    void failingWriteReportsTheReferenceSentence() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        when(this.transactions.saveAndFlush(any())).thenThrow(new RuntimeException("io"));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_ADD_FAILED);
        assertThat(TransactionAddService.MESSAGE_ADD_FAILED)
                .isNotEqualTo("Unable to Add Bill pay Transaction...");
    }

    /**
     * The acknowledgement carries two consecutive spaces and the full sixteen-digit identifier.
     *
     * <p>Assumptions: the assembly at lines 728 to 733 concatenates a literal ending in a space with one
     * beginning in a space, both delimited by size, so nothing trims either and the emitted text carries
     * both. The identifier fragment is delimited by space but the value holds none, because line 451 fed
     * it from a numeric picture that pads with zeros.</p>
     */
    @Test
    @DisplayName("emit the acknowledgement with two consecutive spaces and the whole identifier")
    void acknowledgementCarriesTwoSpacesAndTheWholeIdentifier() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("0000000000000008"));
        appendEchoesTheRow();

        TransactionAddResponse answer =
                this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.returnMessage())
                .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000009.")
                .contains("successfully.  Your")
                .doesNotContain("successfully. Your Tran");
        assertThat(answer.amount()).isEqualTo(Money.of("125.50"));
    }

    /** The cross-reference read happens before the write span is opened. */
    @Test
    @DisplayName("resolve the cross-reference before opening the write span")
    void crossReferenceIsResolvedBeforeTheWriteSpanOpens() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        appendEchoesTheRow();

        this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        InOrder order = inOrder(this.accounts, this.transactions);
        order.verify(this.accounts).findCardXrefByAccountId(ACCOUNT_ID);
        order.verify(this.transactions).findMaxTranId();
        order.verify(this.transactions).saveAndFlush(any());
    }

    /** The copy path re-enters the full chain, so a refused confirmation still only prompts. */
    @Test
    @DisplayName("re-enter the full chain from the copy path")
    void copyPathReEntersTheFullChain() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("0000000000000008"));
        when(this.transactions.findById("0000000000000008"))
                .thenReturn(Optional.of(storedRow()));
        appendEchoesTheRow();

        TransactionAddResponse prompted =
                this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "N"));
        assertThat(prompted.returnMessage()).isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
        verify(this.transactions, never()).saveAndFlush(any());

        TransactionAddResponse appended =
                this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "Y"));
        assertThat(appended.transactionId()).isEqualTo("0000000000000009");
        assertThat(appended.amount()).isEqualTo(Money.of("42.75"));
    }

    /** An empty table leaves the copy path nothing to copy. */
    @Test
    @DisplayName("report that the copy path found no row to copy")
    void copyPathWithNoStoredRowIsReported() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED);
    }

    /**
     * Builds a stored row for the copy path to read.
     *
     * @return a row carrying every column the copy reads, never {@code null}
     */
    private static Transaction storedRow() {
        return new Transaction("0000000000000008", "02", "0002", "POS", "FUEL",
                Money.of("42.75").amount(), 123456789L, "FUEL STOP", "TACOMA", "98402",
                CARD_NUMBER, java.time.LocalDateTime.of(2026, 1, 10, 0, 0),
                java.time.LocalDateTime.of(2026, 1, 11, 0, 0));
    }

    /** A stored maximum that is not the digit form this screen writes is reported as a failed read. */
    @Test
    @DisplayName("report a stored maximum that is not the digit form this screen writes")
    void nonNumericStoredMaximumIsReported() {
        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of("2026-01-15000001"));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED);
    }

    /** The write span is opened for the derivation and the append together. */
    @Test
    @DisplayName("open one write span for the derivation and the append")
    void oneWriteSpanCoversTheDerivationAndTheAppend() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        TransactionAddService scoped = new TransactionAddService(this.transactions, this.accounts,
                new TransactionMapper(), manager);

        accountResolvesTo(CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
        appendEchoesTheRow();

        scoped.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        verify(manager).getTransaction(any());
        verify(manager).commit(status);
    }
}
