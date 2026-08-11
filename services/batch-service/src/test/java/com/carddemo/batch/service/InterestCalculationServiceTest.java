package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Proves the interest accrual reproduces {@code app/cbl/CBACT04C.cbl} paragraph by paragraph.
 *
 * <p>The subject is {@link InterestCalculationService}. The behaviour under assertion is the set of
 * rulings a careful transcription gets wrong by doing the obvious thing, and each case below is
 * anchored to committed evidence rather than to a restatement of the production code: the accrual
 * TRUNCATES because the reference statement at {@code app/cbl/CBACT04C.cbl:464-465} carries no
 * {@code ROUNDED} phrase and no statement in that program's 652 lines carries one; the truncation
 * happens PER CATEGORY ROW because {@code :467} accumulates a value the preceding statement has
 * already stored into {@code PIC S9(09)V99}; and one transaction is emitted PER CATEGORY ROW because
 * {@code :468} sits inside {@code 1300-COMPUTE-INTEREST} rather than at the account level.</p>
 *
 * <p>Assumptions: the two cases that separate {@code RoundingMode.DOWN} from its neighbours are the
 * load-bearing ones and are the reason this class exists rather than one more grouping in the
 * aggregate. The shipped happy-path fixture accrues 12.50 on a balance of 1000.00 at 15.00 per cent,
 * and that datum yields an EXACT two-place quotient -- so it passes under half-up rounding just as it
 * does under truncation and cannot detect a wrong mode on its own. A balance of 1000.80 at 2.50 per
 * cent yields 2.085 exactly, which is 2.08 truncated and 2.09 rounded half up, and its negative
 * counterpart is 2.08 truncated toward zero and 2.09 floored. Without both, the mode is asserted only
 * by the constant that names it.</p>
 */
@DisplayName("the interest calculation service")
class InterestCalculationServiceTest {

    /** The account the cases accrue on, an eleven-digit identifier as the reference declares. */
    private static final long ACCOUNT_ID = 1L;

    /** A second account, so a control-break walk can be driven over two of them. */
    private static final long SECOND_ACCOUNT_ID = 2L;

    /** The card the cross-reference resolves for the account under test. */
    private static final String CARD_NUMBER = "9680294154603697";

    /** The business date whose raw token opens every generated identifier. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2024-01-15");

    /** The instant both generated stamps take, pinned so the assertion is deterministic. */
    private static final LocalDateTime STAMP = LocalDateTime.of(2024, 1, 15, 2, 0, 0);

    /** The account's own disclosure group, matching the shipped fixture's value. */
    private static final String OWN_GROUP = "A000000000";

    /** The rate table, stubbed per case. */
    private DisclosureGroupRepository groups;

    /** The account master, whose save echoes its argument back. */
    private AccountRepository accounts;

    /** The cross-reference the card number comes from. */
    private CardXrefRepository crossReferences;

    /** The ledger the generated transactions are captured from. */
    private TransactionRepository ledger;

    /** The service under test. */
    private InterestCalculationService service;

    /**
     * Builds fresh mocks and the service over them before each case.
     *
     * <p>Assumptions: both saving repositories echo their argument back, because the production
     * methods return what the repository returned and a default mock would answer {@code null}. A
     * fresh set is built per case since several cases assert call counts, and a shared set would
     * carry one case's calls into the next.</p>
     */
    @BeforeEach
    void buildService() {
        this.groups = mock(DisclosureGroupRepository.class);
        this.accounts = mock(AccountRepository.class);
        this.crossReferences = mock(CardXrefRepository.class);
        this.ledger = mock(TransactionRepository.class);

        when(this.accounts.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        when(this.ledger.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));

        this.service = new InterestCalculationService(
                this.groups, this.accounts, this.crossReferences, this.ledger);
    }

    /** The formula at {@code :464-465}, and the rounding mode it does not name. */
    @Nested
    @DisplayName("the accrual arithmetic")
    class AccrualArithmetic {

        /**
         * The shipped fixture's datum accrues to the cent the golden records.
         *
         * <p>Assumptions: {@code tests/golden/interest/happy_path/transact.expected} carries
         * {@code TRAN-AMT} as the eleven-byte zoned field {@code 0000000125} followed by a positive
         * overpunch of a final zero, which is 12.50, on a balance of 1000.00 at a rate of 15.00.</p>
         */
        @Test
        @DisplayName("accrue 12.50 on 1000.00 at 15.00 per cent, as the golden records")
        void theGoldenVectorAccruesToTheCent() {
            assertThat(accrue("1000.00", "15.00").amount()).isEqualByComparingTo("12.50");
        }

        /**
         * A quotient of exactly half a cent truncates down rather than rounding up.
         *
         * <p>Assumptions: this is the case that separates the two modes, and it is the reason the
         * class documentation calls the happy-path datum insufficient. A balance of 1000.80 at 2.50
         * per cent gives a raw product of 2502.0000 and a quotient of exactly 2.085; truncation
         * toward zero yields 2.08 and rounding half up would yield 2.09.</p>
         */
        @Test
        @DisplayName("truncate a half-cent quotient down, where half up would round it up")
        void aHalfCentQuotientTruncatesDown() {
            Money accrued = accrue("1000.80", "2.50");

            assertThat(accrued.amount())
                    .as("the reference statement at app/cbl/CBACT04C.cbl:464-465 carries no ROUNDED"
                            + " phrase, so it discards the surplus digits of its result")
                    .isEqualByComparingTo("2.08");
            assertThat(accrued.amount())
                    .as("2.09 is the half-up answer and would mean the mode had been inherited from"
                            + " the shared general contract instead of the reference statement")
                    .isNotEqualByComparingTo("2.09");
        }

        /**
         * A negative balance truncates toward zero, which is where flooring diverges.
         *
         * <p>Assumptions: negatives are reachable because the receiving field is declared SIGNED --
         * {@code WS-MONTHLY-INT PIC S9(09)V99} at {@code app/cbl/CBACT04C.cbl:168} -- and the
         * accrual base {@code TRAN-CAT-BAL} is signed too at {@code app/cpy/CVTRA01Y.cpy:9}. On a
         * quotient of -2.085, truncation toward zero gives -2.08 while flooring gives -2.09, so this
         * is the case that rules out substituting the floor mode for the truncating one.</p>
         */
        @Test
        @DisplayName("truncate a negative quotient toward zero, where flooring would go past it")
        void aNegativeQuotientTruncatesTowardZero() {
            Money accrued = accrue("-1000.80", "2.50");

            assertThat(accrued.amount()).isEqualByComparingTo("-2.08");
            assertThat(accrued.amount())
                    .as("-2.09 is the floored answer, and flooring differs from truncation on"
                            + " exactly the negative side")
                    .isNotEqualByComparingTo("-2.09");
        }

        /**
         * The product is formed before the division, at full precision.
         *
         * <p>Assumptions: the multiplication is parenthesised in the reference source itself at
         * {@code :465}, so the order is stated rather than inferred. A two-place balance times a
         * two-place rate is a FOUR-place product, and reducing it to cents before the division
         * discards two digits the division would have consumed -- which on this datum is the
         * difference between 2.08 and 2.07.</p>
         */
        @Test
        @DisplayName("multiply at full precision before dividing, not after")
        void theProductIsFormedBeforeTheDivision() {
            BigDecimal balance = new BigDecimal("1000.80");
            BigDecimal rate = new BigDecimal("2.50");

            BigDecimal multiplyFirst = balance.multiply(rate)
                    .divide(new BigDecimal("1200"), 2, RoundingMode.DOWN);
            BigDecimal divideFirst = balance.divide(new BigDecimal("1200"), 2, RoundingMode.DOWN)
                    .multiply(rate).setScale(2, RoundingMode.DOWN);

            assertThat(multiplyFirst)
                    .as("the two orders must be shown to disagree, or this case proves nothing")
                    .isNotEqualByComparingTo(divideFirst);
            assertThat(accrue("1000.80", "2.50").amount()).isEqualByComparingTo(multiplyFirst);
        }

        /**
         * The mode the service names is the mode the shared arithmetic actually applies.
         *
         * <p>Assumptions: {@code ACCRUAL_ROUNDING} is documentation rather than a parameter -- the
         * accrual reduces through {@code Money.monthlyInterest}, which binds the mode -- so the
         * constant could drift from the behaviour it describes without any other case failing. The
         * shared constant compared against is the ACCRUAL mode and deliberately not the general
         * one.</p>
         */
        @Test
        @DisplayName("name the same rounding mode the shared arithmetic applies")
        void theNamedModeIsTheAppliedMode() {
            assertThat(InterestCalculationService.ACCRUAL_ROUNDING)
                    .isEqualTo(Money.BASELINE_INTEREST_ROUNDING)
                    .isEqualTo(RoundingMode.DOWN);
        }

        /**
         * Each row truncates on its own, so the account increment is a sum of truncated terms.
         *
         * <p>Assumptions: {@code :467} performs {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT} with an
         * addend the preceding statement has already stored into {@code PIC S9(09)V99}, so the
         * account increment is the SUM OF THE TRUNCATED terms and never the truncation of their
         * unreduced sum. Two rows of 1000.80 at 2.50 per cent each truncate 2.085 to 2.08, giving
         * 4.16, whereas the unreduced sum is 4.170 and would truncate to 4.17 -- so the two
         * strategies are one cent apart on this datum and the case would fail if a sum-then-reduce
         * helper were reached for. The shared money type deliberately offers no such helper.</p>
         */
        @Test
        @DisplayName("truncate each row on its own, so the total is a sum of truncated terms")
        void truncationHappensPerRowAndNotOnTheSum() {
            Money first = accrue("1000.80", "2.50");
            Money second = accrue("1000.80", "2.50");

            Money sumOfTruncated = first.plus(second);
            BigDecimal truncationOfSum = new BigDecimal("1000.80").multiply(new BigDecimal("2.50"))
                    .add(new BigDecimal("1000.80").multiply(new BigDecimal("2.50")))
                    .divide(new BigDecimal("1200"), 2, RoundingMode.DOWN);

            assertThat(sumOfTruncated.amount()).isEqualByComparingTo("4.16");
            assertThat(truncationOfSum).isEqualByComparingTo("4.17");
            assertThat(sumOfTruncated.amount())
                    .as("the two strategies must be shown to disagree, or this case proves nothing")
                    .isNotEqualByComparingTo(truncationOfSum);
        }

        /**
         * Accrues one balance at one rate through the service.
         *
         * @param balance the category balance as exact decimal text
         * @param rate the annual percentage rate as exact decimal text
         * @return the accrued interest, never {@code null}
         */
        private Money accrue(String balance, String rate) {
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    key(OWN_GROUP), new BigDecimal(rate));
            return InterestCalculationServiceTest.this.service
                    .monthlyInterest(Money.of(balance), lookup);
        }
    }

    /** The two-step lookup at {@code :415-460}. */
    @Nested
    @DisplayName("the rate lookup and the DEFAULT fallback")
    class RateLookup {

        /** A group that resolves on its own records no substitution. */
        @Test
        @DisplayName("resolve the account's own group without substituting")
        void aDirectHitRecordsNoFallback() {
            stubGroup(OWN_GROUP, "12.00");

            InterestRateLookup lookup =
                    InterestCalculationServiceTest.this.service.rateFor(key(OWN_GROUP));

            assertThat(lookup.defaultGroupFallbackApplied()).isFalse();
            assertThat(lookup.resolvedRate()).isEqualByComparingTo("12.00");
            verify(InterestCalculationServiceTest.this.groups, times(1)).findByIdIs(any());
        }

        /**
         * An absent group retries under {@code DEFAULT}, carrying type and category through.
         *
         * <p>Assumptions: {@code :437} moves the literal into {@code FD-DIS-ACCT-GROUP-ID} alone,
         * one field of the three-part key at {@code app/cpy/CVTRA02Y.cpy:5-8}. A retry that rebuilt
         * the whole key from defaults would resolve the rate of a different transaction type and
         * category, so it would answer a plausible number rather than fail.</p>
         */
        @Test
        @DisplayName("retry under DEFAULT, replacing the account group alone")
        void theFallbackReplacesTheAccountGroupAlone() {
            stubMissing(OWN_GROUP);
            stubGroup("DEFAULT   ", "18.00");

            InterestRateLookup lookup =
                    InterestCalculationServiceTest.this.service.rateFor(key(OWN_GROUP));

            assertThat(lookup.defaultGroupFallbackApplied()).isTrue();
            assertThat(lookup.resolvedRate()).isEqualByComparingTo("18.00");
            assertThat(lookup.effectiveKey().accountGroupId()).isEqualTo("DEFAULT   ");
            assertThat(lookup.effectiveKey().transactionTypeCode())
                    .isEqualTo(key(OWN_GROUP).transactionTypeCode());
            assertThat(lookup.effectiveKey().transactionCategoryCode())
                    .isEqualTo(key(OWN_GROUP).transactionCategoryCode());
        }

        /**
         * A missing {@code DEFAULT} row is fatal and is never a silent zero.
         *
         * <p>Assumptions: {@code 1200-A-GET-DEFAULT-INT-RATE} at {@code :443-460} reads with NO
         * {@code INVALID KEY} clause at all and then accepts only file status {@code '00'},
         * displaying {@code ERROR READING DEFAULT DISCLOSURE GROUP} and abending otherwise.
         * Answering zero instead would suppress the accrual for every account whose group is unknown
         * while reporting a clean run.</p>
         */
        @Test
        @DisplayName("fail the step when the DEFAULT row is absent, rather than accruing zero")
        void anAbsentDefaultRowIsFatal() {
            stubMissing(OWN_GROUP);
            stubMissing("DEFAULT   ");

            assertThatThrownBy(
                    () -> InterestCalculationServiceTest.this.service.rateFor(key(OWN_GROUP)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DEFAULT");
        }

        /**
         * The key reaching the repository carries the type and the category in their own positions.
         *
         * <p>Assumptions: {@code :210-212} move the account group, then the CATEGORY, then the TYPE,
         * while the physical layout at {@code app/cpy/CVTRA02Y.cpy:6-8} is the account group, then
         * the TYPE, then the category. Both are same-width character components as far as a
         * positional transcription is concerned, so a transposition would compile and would resolve
         * a real row for many inputs. This case pins the assembled identity.</p>
         */
        @Test
        @DisplayName("assemble the repository key by name, not by the reference's move order")
        void theKeyIsAssembledByName() {
            stubGroup(OWN_GROUP, "12.00");

            InterestCalculationServiceTest.this.service.rateFor(key(OWN_GROUP));

            ArgumentCaptor<DisclosureGroup.DisclosureGroupId> captured =
                    ArgumentCaptor.forClass(DisclosureGroup.DisclosureGroupId.class);
            verify(InterestCalculationServiceTest.this.groups).findByIdIs(captured.capture());
            assertThat(captured.getValue().getAcctGroupId()).isEqualTo(OWN_GROUP);
            assertThat(captured.getValue().getTranTypeCd()).isEqualTo("01");
            assertThat(captured.getValue().getTranCatCd()).isEqualTo("0005");
        }

    }

    /** The generated transaction at {@code :473-515}. */
    @Nested
    @DisplayName("the generated interest transaction")
    class GeneratedTransaction {

        /**
         * Every rendered field matches the shipped golden record.
         *
         * <p>Assumptions: the expected values are read from
         * {@code tests/golden/interest/happy_path/transact.expected} rather than from the production
         * constants, so the case would fail if a constant changed. The identifier is the raw
         * ten-character token followed by a six-digit suffix; the category is the hardcoded value at
         * {@code :483} and not the balance's own category; and the description is thirteen literal
         * characters plus an eleven-digit account identifier.</p>
         */
        @Test
        @DisplayName("render the identifier, codes, description and card the golden records")
        void everyFieldMatchesTheGolden() {
            Transaction generated = write(1L);

            assertThat(generated.getTransactionId()).isEqualTo("2024-01-15000001");
            assertThat(generated.getTransactionId()).hasSize(16);
            assertThat(generated.getTypeCd()).isEqualTo("01");
            assertThat(generated.getCategoryCd()).isEqualTo("0005");
            assertThat(generated.getSource()).isEqualTo("System");
            assertThat(generated.getDescription()).isEqualTo("Int. for a/c 00000000001");
            assertThat(generated.getDescription()).hasSize(24);
            assertThat(generated.getAmount()).isEqualByComparingTo("12.50");
            assertThat(generated.getMerchantId()).isZero();
            assertThat(generated.getCardNum()).isEqualTo(CARD_NUMBER);
        }

        /**
         * The category is the hardcoded literal and never the driving balance's own category.
         *
         * <p>Assumptions: the shipped fixture's category is {@code 0001} and the golden's generated
         * row carries {@code 0005}, so propagating the source category is the plausible reading and
         * the wrong one. This case asserts the constant rather than a pass-through.</p>
         */
        @Test
        @DisplayName("carry the hardcoded category, not the category being accrued")
        void theCategoryIsHardcoded() {
            assertThat(write(1L).getCategoryCd())
                    .as("the golden's generated row carries 0005 where the driving fixture's own"
                            + " category is 0001, so this field is the literal at :483")
                    .isEqualTo("0005")
                    .isNotEqualTo("0001");
        }

        /**
         * Both stamps take the same instant.
         *
         * <p>Assumptions: {@code :496} derives one value and {@code :497-498} move that one value
         * into both stamp fields, because an accrual originates at the moment it is processed. A
         * posted transaction copies its originating stamp from the feed instead, so the two
         * producers differ here and the parity harness masks both fields for this one.</p>
         */
        @Test
        @DisplayName("stamp the originating and processing times from one instant")
        void bothStampsTakeTheSameInstant() {
            Transaction generated = write(1L);

            assertThat(generated.getOrigTs()).isEqualTo(STAMP);
            assertThat(generated.getProcTs()).isEqualTo(STAMP);
            assertThat(generated.getOrigTs()).isEqualTo(generated.getProcTs());
        }

        /**
         * The suffix is rendered at its declared width and is not reset between accounts.
         *
         * <p>Assumptions: {@code :474} increments {@code WS-TRANID-SUFFIX PIC 9(06)} and no
         * statement resets it -- the control break at {@code :200} resets the interest total and
         * nothing else. The golden's two rows are numbered {@code 000001} and {@code 000002} for two
         * DIFFERENT accounts, which is what makes the run-global scope observable.</p>
         */
        @Test
        @DisplayName("zero-pad the run-global suffix to six digits across accounts")
        void theSuffixIsRunGlobalAndPadded() {
            assertThat(write(1L).getTransactionId()).isEqualTo("2024-01-15000001");
            assertThat(write(2L, SECOND_ACCOUNT_ID).getTransactionId())
                    .isEqualTo("2024-01-15000002");
            assertThat(write(123456L).getTransactionId()).isEqualTo("2024-01-15123456");
        }

        /**
         * A suffix wider than its declared field is refused rather than widening the identifier.
         *
         * <p>Assumptions: the identifier column holds sixteen characters exactly -- ten of token
         * plus six of suffix -- so a seven-digit suffix has no correct rendering. Widening would
         * overflow the column and truncating would reuse an identifier already issued in the run.</p>
         */
        @Test
        @DisplayName("refuse a suffix that has outgrown its six digits")
        void anOversizedSuffixIsRefused() {
            assertThatThrownBy(() -> write(1_000_000L))
                    .isInstanceOf(IllegalStateException.class);
        }

        /**
         * A non-positive suffix is refused, because the reference increments before rendering.
         *
         * <p>Assumptions: {@code :474} performs {@code ADD 1 TO WS-TRANID-SUFFIX} before the
         * identifier is built, so the first generated identifier of a run carries {@code 000001} and
         * a run can never present {@code 000000}.</p>
         */
        @Test
        @DisplayName("refuse a suffix of zero, which the reference never presents")
        void aNonPositiveSuffixIsRefused() {
            assertThatThrownBy(() -> write(0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * The business-date token is concatenated verbatim, in either committed layout.
         *
         * <p>Assumptions: two different ten-character forms are both committed --
         * {@code app/jcl/INTCALC.jcl:22} passes {@code '2022071800'} while the golden harness pins
         * {@code 2024-01-15} -- and the golden's identifier is {@code 2024-01-15000001}, hyphens
         * included. Any normalisation would render the same day as {@code 2024011500} and change
         * every generated identifier in the run.</p>
         */
        @Test
        @DisplayName("concatenate either committed date layout without reformatting it")
        void theTokenIsConcatenatedVerbatim() {
            Transaction iso = InterestCalculationServiceTest.this.service.writeInterestTransaction(
                    ACCOUNT_ID, CARD_NUMBER, Money.of("12.50"),
                    new BusinessDate("2024-01-15"), 1L, STAMP);
            Transaction compact =
                    InterestCalculationServiceTest.this.service.writeInterestTransaction(
                            ACCOUNT_ID, CARD_NUMBER, Money.of("12.50"),
                            new BusinessDate("2022071800"), 2L, STAMP);

            assertThat(iso.getTransactionId()).isEqualTo("2024-01-15000001");
            assertThat(compact.getTransactionId()).isEqualTo("2022071800000002");
        }

        /**
         * Three qualifying rows of one account yield three rows, not one.
         *
         * <p>Assumptions: the emit sits inside {@code 1300-COMPUTE-INTEREST} at {@code :468},
         * alongside the accumulate at {@code :467}, rather than at the account level -- so the row
         * count follows the CATEGORY count and not the account count. Each row carries its own
         * identifier because the suffix advances per emit.</p>
         */
        @Test
        @DisplayName("emit one row per category balance, each with its own identifier")
        void oneRowIsEmittedPerCategoryBalance() {
            write(1L);
            write(2L);
            write(3L);

            ArgumentCaptor<Transaction> captured = ArgumentCaptor.forClass(Transaction.class);
            verify(InterestCalculationServiceTest.this.ledger, times(3))
                    .save(captured.capture());
            assertThat(captured.getAllValues())
                    .extracting(Transaction::getTransactionId)
                    .containsExactly("2024-01-15000001", "2024-01-15000002", "2024-01-15000003");
        }

        /**
         * Writes one generated transaction for the primary account.
         *
         * @param suffix the run-scoped identifier suffix
         * @return the stored transaction, never {@code null}
         */
        private Transaction write(long suffix) {
            return write(suffix, ACCOUNT_ID);
        }

        /**
         * Writes one generated transaction for a nominated account.
         *
         * @param suffix the run-scoped identifier suffix
         * @param accountId the account the accrual belongs to
         * @return the stored transaction, never {@code null}
         */
        private Transaction write(long suffix, long accountId) {
            return InterestCalculationServiceTest.this.service.writeInterestTransaction(
                    accountId, CARD_NUMBER, Money.of("12.50"), BUSINESS_DATE, suffix, STAMP);
        }
    }

    /** The account flush and billing-cycle reset at {@code :350-370}. */
    @Nested
    @DisplayName("the account flush")
    class AccountFlush {

        /**
         * The accumulated total reaches the balance, matching the golden's flushed account.
         *
         * <p>Assumptions: {@code tests/golden/interest/happy_path/acctdat.expected} carries account
         * {@code 00000000001} at 206.50, which is its input balance of 194.00 plus its 12.50
         * accrual, so this is the arithmetic {@code :352} performs.</p>
         */
        @Test
        @DisplayName("add the accumulated interest to the balance, as the golden records")
        void theAccumulatedTotalReachesTheBalance() {
            Account account = account("194.00", "0.00", "0.00");

            InterestCalculationServiceTest.this.service.flushAccount(account, Money.of("12.50"));

            assertThat(account.getCurrBal()).isEqualByComparingTo("206.50");
            verify(InterestCalculationServiceTest.this.accounts).save(account);
        }

        /**
         * Both cycle amounts are cleared, tested with non-zero inputs so the reset is observable.
         *
         * <p>Assumptions: {@code :353-354} zero the cycle credit and the cycle debit
         * unconditionally, and the shipped golden shows both at zero because its inputs were ALREADY
         * zero -- so a transcription that dropped the reset would pass against that fixture alone.
         * Driving non-zero inputs is what makes the assignment observable.</p>
         */
        @Test
        @DisplayName("clear both cycle amounts, with non-zero inputs so the reset is observable")
        void bothCycleAmountsAreCleared() {
            Account account = account("194.00", "25.00", "75.00");

            InterestCalculationServiceTest.this.service.flushAccount(account, Money.of("12.50"));

            assertThat(account.getCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(account.getCurrCycDebit()).isEqualByComparingTo("0.00");
        }

        /**
         * An account that accrued nothing is still flushed, so its cycle amounts still clear.
         *
         * <p>Assumptions: {@code :352} adds whatever the accumulator holds, and {@code :200} resets
         * that accumulator to zero on every control break, so a zero total is a reachable and
         * ordinary input. The two cycle assignments at {@code :353-354} are not conditional on it.</p>
         */
        @Test
        @DisplayName("clear the cycle amounts even when nothing accrued")
        void aZeroTotalStillClearsTheCycle() {
            Account account = account("194.00", "25.00", "75.00");

            InterestCalculationServiceTest.this.service.flushAccount(account, Money.ZERO);

            assertThat(account.getCurrBal()).isEqualByComparingTo("194.00");
            assertThat(account.getCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(account.getCurrCycDebit()).isEqualByComparingTo("0.00");
        }

        /**
         * Two accounts walked in one run are both flushed, including the last.
         *
         * <p>Refactoring Rationale: the baseline does not apply the final account group's
         * accumulated interest, because the final-flush path at {@code :219-220} is the {@code ELSE}
         * of the test at {@code :189} and the loop at {@code :188} terminates before its body can
         * observe end of file. The golden shows account {@code 00000000002} unchanged at 158.00 while
         * account {@code 00000000001} is flushed to 206.50. The migrated rule flushes every account,
         * and the divergence is documented as D-3 in
         * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
         */
        @Test
        @DisplayName("flush every account including the final one, the documented D-3 divergence")
        void everyAccountIsFlushedIncludingTheLast() {
            Account first = account("194.00", "0.00", "0.00");
            Account last = account("158.00", "0.00", "0.00");

            InterestCalculationServiceTest.this.service.flushAccount(first, Money.of("12.50"));
            InterestCalculationServiceTest.this.service.flushAccount(last, Money.of("12.50"));

            assertThat(first.getCurrBal()).isEqualByComparingTo("206.50");
            assertThat(last.getCurrBal())
                    .as("the baseline leaves the final account at 158.00; the migrated rule applies"
                            + " its accrual and the divergence is documented as D-3")
                    .isEqualByComparingTo("170.50");
            verify(InterestCalculationServiceTest.this.accounts, times(2)).save(any(Account.class));
        }

        /**
         * Builds an account with a nominated balance and cycle amounts.
         *
         * @param balance the current balance as exact decimal text
         * @param cycleCredit the cycle credit as exact decimal text
         * @param cycleDebit the cycle debit as exact decimal text
         * @return the account, never {@code null}
         */
        private Account account(String balance, String cycleCredit, String cycleDebit) {
            return new Account(ACCOUNT_ID, "Y", new BigDecimal(balance),
                    new BigDecimal("20200.00"), new BigDecimal("2000.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1),
                    new BigDecimal(cycleCredit), new BigDecimal(cycleDebit), OWN_GROUP, OWN_GROUP);
        }
    }

    /** The two control-break reads at {@code :372-413}, and the fee seam at {@code :518}. */
    @Nested
    @DisplayName("the control-break reads and the fee seam")
    class ControlBreakReads {

        /** An account that exists is answered as present. */
        @Test
        @DisplayName("answer the account row when it exists")
        void aReadableAccountIsAnswered() {
            Account account = new Account(ACCOUNT_ID, "Y", new BigDecimal("194.00"),
                    new BigDecimal("20200.00"), new BigDecimal("2000.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1),
                    BigDecimal.ZERO, BigDecimal.ZERO, OWN_GROUP, OWN_GROUP);
            when(InterestCalculationServiceTest.this.accounts.findByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));

            assertThat(InterestCalculationServiceTest.this.service.loadAccount(ACCOUNT_ID))
                    .contains(account);
        }

        /**
         * An absent account is reported rather than raised.
         *
         * <p>Assumptions: the reference abends at {@code :389}, and continuing past an orphaned
         * balance row is registered as divergence D-INTEREST-ORPHAN-ROW against the walk that owns
         * what a skipped row means for the rows after it. This read therefore reports the absence and
         * leaves the decision there.</p>
         */
        @Test
        @DisplayName("report an absent account rather than raising, leaving the skip to the walk")
        void anAbsentAccountIsReported() {
            when(InterestCalculationServiceTest.this.accounts.findByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThat(InterestCalculationServiceTest.this.service.loadAccount(ACCOUNT_ID))
                    .isEmpty();
        }

        /**
         * The card number comes from the bounded, ordered by-account finder.
         *
         * <p>Assumptions: the reference reads the cross-reference through the by-account alternate
         * index mounted at {@code app/jcl/INTCALC.jcl:31-32}, and that index is non-unique, so one
         * account may map to several cards. The finder is bounded to one row in ascending card-number
         * order, which resolves to the same card on every run.</p>
         */
        @Test
        @DisplayName("take the card number from the bounded ordered by-account finder")
        void theCardNumberComesFromTheBoundedFinder() {
            when(InterestCalculationServiceTest.this.crossReferences
                    .findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardXref(CARD_NUMBER, 1L, ACCOUNT_ID)));

            assertThat(InterestCalculationServiceTest.this.service.loadCrossReference(ACCOUNT_ID))
                    .isEqualTo(CARD_NUMBER);
        }

        /**
         * An account with no card row fails the step.
         *
         * <p>Assumptions: {@code :400-412} accepts only file status {@code '00'} and otherwise
         * displays {@code ERROR READING XREF FILE} and abends. Defaulting the card number to blanks
         * would emit interest attributed to no card while reporting success.</p>
         */
        @Test
        @DisplayName("fail the step when an account has no card row")
        void anAccountWithNoCardRowFails() {
            when(InterestCalculationServiceTest.this.crossReferences
                    .findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> InterestCalculationServiceTest.this.service
                    .loadCrossReference(ACCOUNT_ID))
                    .isInstanceOf(IllegalStateException.class);
        }

        /**
         * The fee seam does nothing and touches nothing.
         *
         * <p>Assumptions: {@code app/cbl/CBACT04C.cbl:518-520} declares the paragraph as a comment
         * reading {@code To be implemented} followed by an immediate exit, with no statement between
         * them. Asserting that no collaborator is touched is the only observable form the emptiness
         * has, and it is what would fail if behaviour were added without a documented divergence.</p>
         */
        @Test
        @DisplayName("do nothing at all in the preserved fee seam")
        void theFeeSeamDoesNothing() {
            InterestCalculationServiceTest.this.service.computeFees();

            verify(InterestCalculationServiceTest.this.accounts, never())
                    .save(any(Account.class));
            verify(InterestCalculationServiceTest.this.ledger, never())
                    .save(any(Transaction.class));
            verify(InterestCalculationServiceTest.this.groups, never()).findByIdIs(any());
        }
    }

    /**
     * Builds the three-part lookup key the accrual resolves rates by.
     *
     * @param accountGroupId the account group component, at its declared ten-character width
     * @return the key, never {@code null}
     */
    private static DisclosureGroupKey key(String accountGroupId) {
        return new DisclosureGroupKey(accountGroupId, "01", 5);
    }

    /**
     * Stubs the rate table to answer one group with one rate.
     *
     * @param accountGroupId the group component the stub answers for
     * @param rate the rate to answer, as exact decimal text
     */
    private void stubGroup(String accountGroupId, String rate) {
        DisclosureGroup.DisclosureGroupId id =
                new DisclosureGroup.DisclosureGroupId(accountGroupId, "01", "0005");
        when(this.groups.findByIdIs(id))
                .thenReturn(Optional.of(new DisclosureGroup(id, new BigDecimal(rate))));
    }

    /**
     * Stubs the rate table to answer nothing for one group.
     *
     * @param accountGroupId the group component the stub reports absent
     */
    private void stubMissing(String accountGroupId) {
        when(this.groups.findByIdIs(
                new DisclosureGroup.DisclosureGroupId(accountGroupId, "01", "0005")))
                .thenReturn(Optional.empty());
    }
}
