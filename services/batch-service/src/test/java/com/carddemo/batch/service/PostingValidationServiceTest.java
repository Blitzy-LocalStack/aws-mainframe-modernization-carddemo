package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.dto.RejectReason;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the posting validation reaches the reference's four conditions in the reference's order.
 *
 * <p>Purpose: these cases were previously carried by a repository integration test in the ledger-owning
 * module, which transcribed the decision sequence into the test itself and then asserted the row it had
 * written. That test could only agree with itself. The decision now lives in
 * {@link PostingValidationService} and {@link PostingValidationResult}, and these cases drive it, so a
 * failure names the production dispatch.</p>
 */
@DisplayName("the posting validation chain")
class PostingValidationServiceTest {

    /** The by-card access path, stubbed per case. */
    private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

    /** The by-account access path, held so a case can assert it was never read. */
    private final AccountRepository accounts = mock(AccountRepository.class);

    /** The service under test; it holds no mutable state, so one instance serves every case. */
    private final PostingValidationService service =
            new PostingValidationService(this.crossReferences, this.accounts);

    /** An account identifier used wherever the identity itself is not what is under test. */
    private static final long ACCOUNT_ID = 11L;

    /** A card number used wherever the number itself is not what is under test. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * Builds an account carrying the cycle credit, limit and expiration date a case needs.
     *
     * <p>Assumptions: the first argument is placed in the CYCLE CREDIT accumulator and the current
     * balance is left at zero, because the projection the credit-limit guard compares is formed at
     * {@code app/cbl/CBTRN02C.cbl:403-405} from {@code ACCT-CURR-CYC-CREDIT} minus
     * {@code ACCT-CURR-CYC-DEBIT} plus the transaction amount, and not from
     * {@code ACCT-CURR-BAL}. Placing it in the current balance instead would leave the projection at
     * the transaction amount alone, which would quietly stop the boundary cases below exercising any
     * boundary at all -- they would still pass, while asserting nothing about the limit.</p>
     *
     * @param cycleCredit the cycle credit total the account has already accumulated toward its limit,
     *     which the projection adds the transaction amount to
     * @param creditLimit the credit limit the projected balance is compared against
     * @param expirationDate the expiration date the originating date is compared against
     * @return the account, never {@code null}
     */
    private static Account account(String cycleCredit, String creditLimit,
            LocalDate expirationDate) {
        return new Account(ACCOUNT_ID, "Y", BigDecimal.ZERO,
                new BigDecimal(creditLimit), new BigDecimal("500.00"),
                LocalDate.of(2020, 1, 1), expirationDate, LocalDate.of(2024, 1, 1),
                new BigDecimal(cycleCredit), BigDecimal.ZERO, "98101", "DEFAULT");
    }

    /**
     * Builds a daily transaction with the amount and originating date a case needs.
     *
     * @param amount the transaction amount, which may be signed
     * @param originatingDate the originating date, whose ten characters the date guard compares
     * @return the transaction, never {@code null}
     */
    private static DailyTransaction transaction(String amount, LocalDate originatingDate) {
        LocalDateTime stamp = originatingDate.atTime(12, 0);
        return new DailyTransaction("0000000000000001", "01", "0001", "POS", "GROCERY",
                new BigDecimal(amount), 999999999L, "STORE", "SEATTLE", "98101", CARD_NUMBER,
                stamp, stamp);
    }

    /**
     * Returns the cross-reference entry a resolved card yields.
     *
     * @return the entry, never {@code null}
     */
    private static Optional<CardXref> resolved() {
        return Optional.of(new CardXref(CARD_NUMBER, 1L, ACCOUNT_ID));
    }

    /** A transaction failing nothing may post. */
    @Test
    @DisplayName("post a transaction that fails no condition")
    void passingTransactionMayPost() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100.00", LocalDate.of(2022, 7, 18)), resolved(),
                Optional.of(account("0.00", "1000.00", LocalDate.of(2030, 1, 1))));

        assertThat(outcome.isAccepted()).isTrue();
        assertThat(outcome.rejectReason()).isEmpty();
    }

    /** An unresolved card number reports reason 100 and suppresses every later condition. */
    @Test
    @DisplayName("report reason 100 and suppress the later conditions when the card does not resolve")
    void unresolvedCardReportsOneHundredAndSuppressesTheRest() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100000.00", LocalDate.of(2030, 1, 1)), Optional.empty(),
                Optional.empty());

        assertThat(outcome.rejectReason())
                .contains(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
    }

    /** An absent account reports reason 101 even when both boundary conditions would also fail. */
    @Test
    @DisplayName("report reason 101 rather than a boundary reason when the account is absent")
    void absentAccountReportsOneHundredAndOneRatherThanABoundaryReason() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100000.00", LocalDate.of(2030, 1, 1)), resolved(), Optional.empty());

        assertThat(outcome.rejectReason()).contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
    }

    /** A projected balance landing exactly on the credit limit posts, because the guard is inclusive. */
    @Test
    @DisplayName("post a projected balance landing exactly on the credit limit")
    void exactLimitPosts() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100.00", LocalDate.of(2022, 7, 18)), resolved(),
                Optional.of(account("900.00", "1000.00", LocalDate.of(2030, 1, 1))));

        assertThat(outcome.isAccepted()).isTrue();
    }

    /** One cent beyond the credit limit reports reason 102. */
    @Test
    @DisplayName("report reason 102 one cent beyond the credit limit")
    void oneCentBeyondTheLimitReportsOneHundredAndTwo() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100.01", LocalDate.of(2022, 7, 18)), resolved(),
                Optional.of(account("900.00", "1000.00", LocalDate.of(2030, 1, 1))));

        assertThat(outcome.rejectReason()).contains(RejectReason.OVER_CREDIT_LIMIT);
    }

    /** A transaction dated equal to the expiration date posts, because the guard is inclusive. */
    @Test
    @DisplayName("post a transaction dated equal to the account expiration date")
    void expirationDateEqualPosts() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100.00", LocalDate.of(2022, 7, 18)), resolved(),
                Optional.of(account("0.00", "1000.00", LocalDate.of(2022, 7, 18))));

        assertThat(outcome.isAccepted()).isTrue();
    }

    /** One day past the expiration date reports reason 103. */
    @Test
    @DisplayName("report reason 103 one day past the account expiration date")
    void oneDayPastExpirationReportsOneHundredAndThree() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100.00", LocalDate.of(2022, 7, 19)), resolved(),
                Optional.of(account("0.00", "1000.00", LocalDate.of(2022, 7, 18))));

        assertThat(outcome.rejectReason())
                .contains(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
    }

    /**
     * A transaction failing BOTH boundary guards reports 103, because the second assignment overwrites
     * the first.
     *
     * <p>Assumptions: this is the one case a naive transcription gets wrong. The reference's two boundary
     * blocks at {@code :407} and {@code :414} are sequential and unguarded, so the later assignment wins;
     * an either-or would report 102 here.</p>
     */
    @Test
    @DisplayName("report reason 103 when both boundary guards fail")
    void bothBoundaryFailuresReportOneHundredAndThree() {
        PostingValidationResult outcome = this.service.validate(
                transaction("100.01", LocalDate.of(2022, 7, 19)), resolved(),
                Optional.of(account("900.00", "1000.00", LocalDate.of(2022, 7, 18))));

        assertThat(outcome.rejectReason())
                .contains(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
        assertThat(outcome.rejectReason()).get()
                .isNotEqualTo(RejectReason.OVER_CREDIT_LIMIT);
    }

    /** The precedence factory refuses to let a later condition displace an earlier one. */
    @Test
    @DisplayName("keep the precedence in the result type, not in its caller")
    void precedenceIsOwnedByTheResultType() {
        // WHY : Assumptions: the arguments state which conditions FAILED, all four in the same
        //       polarity, so a reader does not have to track that some mean "found" and others mean
        //       "failed". The projection is null on the first two because a lookup failure means none
        //       was computed, and required on the last two because both reached the boundary tests.
        assertThat(PostingValidationResult.resolve(true, true, true, true, null).rejectReason())
                .contains(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
        assertThat(PostingValidationResult.resolve(false, true, true, true, null).rejectReason())
                .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
        assertThat(PostingValidationResult
                .resolve(false, false, true, true, new BigDecimal("1000.01")).rejectReason())
                .contains(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
        assertThat(PostingValidationResult
                .resolve(false, false, false, false, new BigDecimal("100.00")).isAccepted())
                .isTrue();
    }

    /**
     * An unresolved card ends validation without the account path being read at all.
     *
     * <p>Assumptions: this drives the resolving entry point rather than the pre-resolved overload,
     * because the guard at {@code app/cbl/CBTRN02C.cbl:372} is about a read NOT HAPPENING and only
     * the entry point that owns both reads can be observed to skip one. Reason 100 therefore
     * excludes reason 101 by control flow rather than by precedence.</p>
     */
    @Test
    @DisplayName("leave the account path unread when the card does not resolve")
    void unresolvedCardLeavesTheAccountPathUnread() {
        when(this.crossReferences.findByCardNum(CARD_NUMBER)).thenReturn(Optional.empty());

        PostingValidationResult outcome =
                this.service.validate(transaction("100.00", LocalDate.of(2022, 7, 18)));

        assertThat(outcome.rejectReason())
                .contains(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
        verifyNoInteractions(this.accounts);
    }

    /**
     * No combination of findings reports the reason the reference assigns on a failed rewrite.
     *
     * <p>Assumptions: reason 109 is assigned at {@code app/cbl/CBTRN02C.cbl:556}, inside the posting
     * path that {@code :212} enters only when validation assigned no reason, so it is not a
     * validation outcome and no input to this service may produce it.</p>
     */
    @Test
    @DisplayName("never report the rewrite reason for any input")
    void noInputEverReportsTheRewriteReason() {
        assertThat(this.service.validate(transaction("100.01", LocalDate.of(2022, 7, 19)),
                        Optional.empty(), Optional.empty()).rejectReason())
                .isNotEqualTo(Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE));
        assertThat(this.service.validate(transaction("100.01", LocalDate.of(2022, 7, 19)),
                        resolved(), Optional.empty()).rejectReason())
                .isNotEqualTo(Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE));
        assertThat(this.service.validate(transaction("100.01", LocalDate.of(2022, 7, 19)),
                        resolved(),
                        Optional.of(account("900.00", "1000.00", LocalDate.of(2022, 7, 18))))
                        .rejectReason())
                .isNotEqualTo(Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE));
    }

    /**
     * The projection is formed from the cycle accumulators and carries two decimal places.
     *
     * <p>Assumptions: the account below carries a current balance deliberately unequal to its cycle
     * totals, so a projection taken from the wrong field would be arithmetically distinguishable.
     * {@code app/cbl/CBTRN02C.cbl:403-405} forms the quantity as the cycle credit less the cycle
     * debit plus the amount, giving {@code 400.00 - 25.00 + 100.00}, and never touches
     * {@code ACCT-CURR-BAL}.</p>
     */
    @Test
    @DisplayName("form the projection from the cycle accumulators, not the current balance")
    void projectionComesFromTheCycleAccumulators() {
        Account distinguishing = new Account(ACCOUNT_ID, "Y", new BigDecimal("7777.77"),
                new BigDecimal("1000.00"), new BigDecimal("500.00"), LocalDate.of(2020, 1, 1),
                LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1), new BigDecimal("400.00"),
                new BigDecimal("25.00"), "98101", "DEFAULT");

        PostingValidationResult outcome = this.service.validate(
                transaction("100.00", LocalDate.of(2022, 7, 18)), resolved(),
                Optional.of(distinguishing));

        assertThat(outcome.isAccepted()).isTrue();
        assertThat(outcome.projectedCycleBalance()).isEqualByComparingTo(new BigDecimal("475.00"));
        assertThat(outcome.projectedCycleBalance().scale()).isEqualTo(2);
    }
}
