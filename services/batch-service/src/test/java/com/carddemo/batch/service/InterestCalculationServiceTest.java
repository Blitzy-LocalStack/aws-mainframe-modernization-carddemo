package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
 *
 * <h2>Four constructed vectors, and why no shipped fixture could replace them</h2>
 *
 * <p>Alternatives Considered: driving every case from the committed interest fixtures and goldens,
 * which is what the rest of the parity work does and what a reader would expect here. It is declined
 * for four specific rulings, each of which the committed data provably cannot discriminate, and the
 * measurement is recorded so the vectors are not later "simplified" back onto the fixtures:</p>
 *
 * <ul>
 *   <li>The ROUNDING MODE. Every interest fixture drives 1000.00 at 15.00 per cent, whose quotient is
 *       exactly 12.5000, so truncation and half-up rounding agree and the goldens are satisfied by
 *       either. Closed by {@code aHalfCentQuotientTruncatesDown} and its second vector.</li>
 *   <li>The ZERO-RATE branch. The fixture named {@code zero_balance} is a zero-BALANCE scenario: its
 *       rate is 15.00 and its golden {@code transact.expected} is 702 bytes, being two 350-byte rows
 *       plus newlines, so two transactions ARE written there. Genuine zero-rate rows exist in
 *       {@code tests/fixtures/interest/default_fallback/discgrp.txt} at types {@code 02}, {@code 03}
 *       and {@code 07}, but no category-balance row addresses any of them. Closed by
 *       {@code ZeroRateAndZeroBalance}.</li>
 *   <li>The BILLING-CYCLE RESET. Both cycle amounts are already zero on input in every fixture, and
 *       {@code tests/golden/interest/default_fallback/acctdat.expected} shows them zero on output, so
 *       dropping the two assignments would change nothing observable. Closed by
 *       {@code allThreeStateChangesHappen}.</li>
 *   <li>PER-ROW truncation and PER-ROW emission. Every fixture gives each account exactly one
 *       category-balance row, so one account contributes one term and one row and the per-row and
 *       per-account readings coincide. Closed by
 *       {@code threeCategoryRowsEmitThreeRowsAndIncrementBySixtyCents}.</li>
 * </ul>
 *
 * <p>Trade-offs: a constructed vector is not traceable to a committed byte image, which is the
 * property the golden-master comparison exists to provide, and that is the cost accepted here. What
 * is bought is a case that can FAIL for the reason it was written; a fixture-backed case for any of
 * the four above passes under both the correct and the incorrect implementation, which is worse than
 * an absent case because it occupies the place a real check would go. Each constructed vector states
 * its own arithmetic in full so a reader can verify it without running anything.</p>
 *
 * <p>Trade-offs: the correction identified C-ROUNDING is acknowledged here rather than left for a
 * reader to rediscover, because the surrounding prose disagrees with this class on its face.
 * {@code services/common-lib/README.md} and
 * {@code docs/architecture/data-model-and-schema-mapping.md} both state scale 2 with half-up rounding
 * UNCONDITIONALLY, and for the accrual path that is not the mode: the shared kernel therefore carries
 * two named modes, a general half-up one and a distinct truncating one, and the accrual reaches the
 * truncating one through a dedicated helper that exposes no way to select the other. The
 * distinction is deliberate and asserted by {@code theNamedModeIsTheAppliedMode}, so a future reader
 * must not "reconcile" the kernel back to a single mode. Note also that no test vector had to change
 * when the disposition was settled, precisely because 12.50 is exact under both -- which is the same
 * fact that makes the constructed rounding vector necessary.</p>
 *
 * <p>Refactoring Rationale: C-ROUNDING was formerly a registered behavioural divergence, on the
 * reading that the accrual rounded half up and differed from the baseline by a cent. That
 * disposition is withdrawn and the identifier survives only as a withdrawal record in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The reason it could not stand is that
 * the cent did not stay local: {@code :467} adds each reduced term into the account total and
 * {@code :352} adds that total to the balance, which subsequent inclusive over-limit comparisons are
 * made against.</p>
 *
 * <p>Trade-offs: every case here builds its subject with a constructor call and supplies each
 * repository as a mock, so no application context starts, no database container is requested and no
 * clock is read. What is given up is any ability to observe wiring -- whether a bean is declared,
 * whether a property binds, whether the datasource resolves its schema search path -- and that is
 * proven instead by the job and repository tiers, which can observe it. What is bought is a ruling
 * that runs in milliseconds and fails for exactly one reason: the rule disagreed.</p>
 *
 * <p>Assumptions: no method in the production service package is transactional, so no case here
 * expects, asserts or arranges a transaction boundary. With the repositories supplied as mocks there
 * would be no unit of work to break, so such an assertion would pass whatever the production
 * propagation actually was.</p>
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

    /**
     * The transaction type component the default lookup key carries.
     *
     * <p>Assumptions: the value is the one the shipped fixtures drive.
     * {@code tests/fixtures/interest/happy_path/discgrp.txt} holds the single row
     * {@code A000000000} {@code 01} {@code 0001}, and every {@code tcatbal.txt} row in the interest
     * domain is keyed on type {@code 01}, so a lookup assembled from the fixture data asks for this
     * code.</p>
     */
    private static final String TYPE_CODE = "01";

    /**
     * The transaction category component the default lookup key carries, as a number.
     *
     * <p>Assumptions: the value is the CATEGORY OF THE BALANCE BEING ACCRUED, taken from the shipped
     * fixtures' own {@code tcatbal.txt} rows, and it is deliberately not the hardcoded {@code 05}
     * that {@code app/cbl/CBACT04C.cbl:483} stamps on the GENERATED row. The two are different
     * numbers doing different jobs -- {@code :211} copies the balance's category into the rate key
     * while {@code :483} writes a constant into the emitted transaction -- so using one value for
     * both would hide the distinction the golden makes plain by carrying {@code 0005} for a fixture
     * whose input category is {@code 0001}.</p>
     */
    private static final int CATEGORY_CODE = 1;

    /**
     * The transaction type the key-order vector carries, chosen to differ from the category.
     *
     * <p>Assumptions: the pair {@code 04} and {@code 0002} is a real seeded combination --
     * {@code tests/fixtures/interest/default_fallback/discgrp.txt} carries the row
     * {@code DEFAULT   } {@code 04} {@code 0002} -- so the vector exercises a key the reference data
     * genuinely holds rather than an invented one.</p>
     */
    private static final String OTHER_TYPE_CODE = "04";

    /** The transaction category the key-order vector carries, differing from its type code. */
    private static final int OTHER_CATEGORY_CODE = 2;

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
     * <p>Assumptions: both saving repositories echo their argument back, because
     * {@code InterestCalculationService.writeInterestTransaction} returns what
     * {@code TransactionRepository.save} returned and {@code flushAccount} returns what
     * {@code AccountRepository.save} returned, so an unstubbed mock would answer {@code null} and
     * every case reading a returned entity would fail on a null reference rather than on the ruling it
     * was written for.</p>
     *
     * <p>Assumptions: a fresh set is built per case because several cases assert CALL COUNTS -- the
     * one-read direct hit, the two-read fallback, the three-row emission and the two-write walk all
     * do -- and a shared set would carry one case's invocations into the next, making those counts
     * depend on execution order.</p>
     *
     * <p>Alternatives Considered: the Mockito JUnit extension with its strict-stub checking, which
     * would report an unused stub as a failure. It is declined because the two echo stubs above are
     * declared for EVERY case while the majority of cases save nothing, so strict checking would
     * report unnecessary-stubbing failures on correct tests. The alternative of moving the stubs into
     * only the cases that save was weighed and rejected as well: it would repeat them across roughly
     * a third of the file and invite a later case to omit one and meet a null instead of a ruling.</p>
     *
     * <p>Trade-offs: what is given up by declining strict stubs is the automatic detection of a stub
     * that has become dead as the file changes. That is accepted because the four repositories are
     * supplied to a constructor whose signature the compiler checks, so a collaborator that stopped
     * being used would surface as an unused field rather than as silent drift.</p>
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
         *
         * <p>Assumptions: this datum alone CANNOT detect a wrong rounding mode, and saying so here is
         * what stops it being mistaken for the mode's proof. The quotient of 15000.0000 by 1200 is
         * exactly 12.5000, so truncation, half-up rounding and flooring all yield 12.50. The mode is
         * settled by {@link #aHalfCentQuotientTruncatesDown} and
         * {@link #aNegativeQuotientTruncatesTowardZero} instead.</p>
         */
        @Test
        @DisplayName("accrue 12.50 on 1000.00 at 15.00 per cent, as the golden records")
        void theGoldenVectorAccruesToTheCent() {
            BigDecimal accrued = accrue("1000.00", "15.00").amount();

            assertThat(accrued).isEqualByComparingTo("12.50");

            // WHY : Assumptions: the SCALE is asserted alongside the value because the two are separate
            //       contracts and only the value is checked by a comparison. AssertJ's comparison
            //       assertion delegates to BigDecimal.compareTo, which ignores scale entirely, so a
            //       result carried as 12.5 or 12.500 would satisfy the line above while occupying the
            //       eleven-byte zoned TRAN-AMT field of app/cpy/CVTRA05Y.cpy as different bytes. Two
            //       decimal places is the declared scale of WS-MONTHLY-INT PIC S9(09)V99 at
            //       app/cbl/CBACT04C.cbl:168, so it is part of the ruling rather than a detail of it.
            assertThat(accrued.scale()).isEqualTo(Money.SCALE);
        }

        /**
         * A quotient of exactly half a cent truncates down rather than rounding up.
         *
         * <p>Assumptions: this is the case that separates the two modes, and it is the reason the
         * class documentation calls the happy-path datum insufficient. A balance of 1000.80 at 2.50
         * per cent gives a raw product of 2502.0000 and a quotient of exactly 2.085; truncation
         * toward zero yields 2.08 and rounding half up would yield 2.09. The mode is truncation
         * because the statement at {@code app/cbl/CBACT04C.cbl:464-465} carries no {@code ROUNDED}
         * phrase, and neither does any other statement in that program's 652 lines -- a count, not an
         * impression -- so it discards the surplus digits of its result into
         * {@code WS-MONTHLY-INT PIC S9(09)V99} at {@code :168}.</p>
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

            // WHY : Assumptions: the scale is asserted here in particular because truncation is the
            //       SUBJECT of this case, and a truncating reduction that returned three places would
            //       have discarded nothing at all -- 2.085 compares unequal to both 2.08 and 2.09, so
            //       the two assertions above would pass while no reduction had happened.
            assertThat(accrued.amount().scale()).isEqualTo(Money.SCALE);
        }

        /**
         * A second, independent half-cent vector truncates down, so the first is not a coincidence.
         *
         * <p>Alternatives Considered: relying on the single 1000.80-at-2.50 vector to settle the
         * mode. A lone datum leaves open the reading that the reduction happens to land correctly for
         * one pair of operands, so a second pair with different digits is used. A balance of 100.40
         * at 15.00 per cent forms the scale-4 product 1506.0000 and the quotient 1.2550, which
         * truncates to 1.25 and would round half up to 1.26.</p>
         *
         * <p>Assumptions: this vector is CONSTRUCTED and closes coverage gap G-2, which no shipped
         * fixture can close. Every interest fixture drives 1000.00 at 15.00 per cent -- the single
         * row of {@code tests/fixtures/interest/happy_path/discgrp.txt} carries that rate -- and that
         * quotient is exact, so {@code tests/golden/interest/happy_path/transact.expected} is
         * satisfied by either mode and the truncation is unobservable through it.</p>
         */
        @Test
        @DisplayName("truncate a second half-cent quotient down, on different digits")
        void aSecondHalfCentQuotientAlsoTruncatesDown() {
            Money accrued = accrue("100.40", "15.00");

            assertThat(accrued.amount()).isEqualByComparingTo("1.25");
            assertThat(accrued.amount())
                    .as("1.26 is the half-up answer on this vector")
                    .isNotEqualByComparingTo("1.26");
            assertThat(accrued.amount().scale()).isEqualTo(Money.SCALE);
        }

        /**
         * Every accrued amount carries exactly two decimal places, whatever the operands.
         *
         * <p>Assumptions: two places is the declared scale of {@code WS-MONTHLY-INT PIC S9(09)V99} at
         * {@code app/cbl/CBACT04C.cbl:168}, and a COBOL arithmetic statement stores into its receiving
         * field's scale whatever the intermediate carried. The cases collected here span an exact
         * quotient, a truncating one, a negative one, a zero and a non-terminating expansion, because
         * the last is where a reduction that forgot its scale would instead raise rather than answer
         * the wrong number.</p>
         *
         * <p>Assumptions: the scale is compared against the shared kernel's own {@code Money.SCALE}
         * rather than the literal 2, so the monetary scale has one definition and this case cannot
         * disagree with the type that enforces it.</p>
         */
        @Test
        @DisplayName("carry exactly two decimal places on every accrued amount")
        void everyAccruedAmountCarriesTwoDecimalPlaces() {
            // Assumptions: 1000.00 at 2.50 per cent is the non-terminating case -- the quotient is
            //     2.08333... recurring -- so it is included deliberately. An unscaled division would
            //     raise ArithmeticException on it rather than return a wrong value, which is a
            //     different failure mode from the others and would otherwise go untested.
            List<Money> accrued = List.of(
                    accrue("1000.00", "15.00"),
                    accrue("1000.80", "2.50"),
                    accrue("-1000.80", "2.50"),
                    accrue("0.00", "15.00"),
                    accrue("1000.00", "2.50"));

            assertThat(accrued)
                    .allSatisfy(amount -> assertThat(amount.amount().scale())
                            .as("a comparison assertion ignores scale, so the scale is checked"
                                    + " directly for every vector this class drives")
                            .isEqualTo(Money.SCALE));
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
            assertThat(accrued.amount().scale()).isEqualTo(Money.SCALE);
            assertThat(accrued.amount())
                    .as("the sign survives the reduction, which is what makes the signed picture at"
                            + " app/cpy/CVTRA01Y.cpy:9 reachable rather than decorative")
                    .isNegative();
        }

        /**
         * The product is formed before the division, at full precision.
         *
         * <p>Assumptions: the multiplication is parenthesised in the reference source itself at
         * {@code :465}, so the order is stated rather than inferred. A two-place balance times a
         * two-place rate is a FOUR-place product -- the rate is itself scale 2, being
         * {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:9} -- and reducing that
         * product to cents before the division discards two digits the division would have consumed,
         * which on this datum is the difference between 2.08 and 2.07.</p>
         *
         * <p>Trade-offs: the two orders are computed in the test body and compared, rather than the
         * expected 2.08 simply being asserted. The cost is arithmetic in a test, which normally risks
         * re-implementing the subject; it is accepted here because the value of this case is entirely
         * in showing that the orders DISAGREE on the vector chosen. Asserting 2.08 alone would leave a
         * reader unable to tell whether the order mattered on this datum at all, and a later
         * maintainer could substitute a vector on which it did not while the case stayed green. The
         * subject is still driven for the answer -- only the two candidate answers are computed
         * locally.</p>
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
         *
         * <p>Assumptions: the mode it must equal is truncation toward zero, because the accrual
         * statement at {@code app/cbl/CBACT04C.cbl:464-465} carries no {@code ROUNDED} phrase. This
         * case is the reason the constant cannot silently disagree with
         * {@code Money.monthlyInterest}, and it is also where correction C-ROUNDING is held in place:
         * an edit reconciling the kernel back to one half-up mode fails here.</p>
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
         * addend the preceding statement has already stored into {@code PIC S9(09)V99} at
         * {@code :168}, so the account increment is the SUM OF THE TRUNCATED terms and never the
         * truncation of their unreduced sum. The shared money type deliberately offers no
         * sum-then-reduce helper, so there is nothing to reach for by mistake.</p>
         *
         * <p>Assumptions: this vector is CONSTRUCTED and closes coverage gap G-5, which no shipped
         * fixture can close. Every interest fixture gives each account exactly ONE category-balance
         * row -- {@code tests/fixtures/interest/happy_path/tcatbal.txt} and its two siblings each hold
         * two rows for two different accounts, keyed account, type {@code 01}, category
         * {@code 0001} -- so with one term per account the two strategies cannot differ and the
         * distinction is unobservable through the goldens.</p>
         *
         * <p>Alternatives Considered: a two-row vector. Rejected because the smallest pair whose
         * strategies disagree still leaves the difference at one cent, where a reader may take it for
         * an artefact of one addition; three rows of 99.60 at 2.50 per cent make it two cents and
         * make the accumulation visibly repeated. Each row's quotient is exactly 0.2075, truncating
         * to 0.20 for a sum of 0.60, whereas the unreduced products total 747.0000 and truncate to
         * 0.62.</p>
         */
        @Test
        @DisplayName("truncate each of three rows on its own, giving 0.60 and not 0.62")
        void truncationHappensPerRowAndNotOnTheSum() {
            List<Money> perRow = List.of(
                    accrue("99.60", "2.50"), accrue("99.60", "2.50"), accrue("99.60", "2.50"));

            assertThat(perRow)
                    .as("each row's quotient is exactly 0.2075 and truncates to 0.20")
                    .allSatisfy(row -> assertThat(row.amount()).isEqualByComparingTo("0.20"));

            Money sumOfTruncated = Money.total(perRow.toArray(new Money[0]));
            BigDecimal truncationOfSum = new BigDecimal("99.60").multiply(new BigDecimal("2.50"))
                    .multiply(new BigDecimal("3"))
                    .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.DOWN);

            assertThat(sumOfTruncated.amount()).isEqualByComparingTo("0.60");
            assertThat(truncationOfSum).isEqualByComparingTo("0.62");

            // WHY : Assumptions: the two strategies are asserted to DISAGREE rather than merely
            //       asserting the expected total, because a vector on which they happened to agree
            //       would make this case pass without discriminating anything -- which is exactly the
            //       defect that leaves the shipped one-row-per-account fixtures unable to close G-5.
            assertThat(sumOfTruncated.amount())
                    .as("the sum of truncated terms must differ from the truncation of the raw sum,"
                            + " or this case discriminates nothing")
                    .isNotEqualByComparingTo(truncationOfSum);
            assertThat(sumOfTruncated.amount().scale()).isEqualTo(Money.SCALE);
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

        /**
         * A group that resolves on its own records no substitution.
         *
         * <p>Assumptions: this is the {@code '00'} arm of {@code app/cbl/CBACT04C.cbl:422}, where the
         * first read at {@code :416} finds the row and the substitution test at {@code :436} does not
         * fire, so exactly ONE read happens. The read count is asserted because it is what
         * distinguishes this arm from the fallback: a lookup that always retried under
         * {@code DEFAULT} would resolve the same rate here and report the same result.</p>
         */
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
            // Alternatives Considered: a vector whose type and category are interchangeable, which
            //     is what the default codes 01 and 0001 come close to being. A distinct pair is used
            //     instead -- type 04 with category 0002 -- so that a transcription following the
            //     reference's move order at :210-212 would place 0002 where 04 belongs and this case
            //     would fail. With near-identical codes a transposition can still resolve a row, and
            //     that is precisely the defect the reference's assignment order invites.
            DisclosureGroupKey wanted = key(OWN_GROUP, OTHER_TYPE_CODE, OTHER_CATEGORY_CODE);
            stubGroup(wanted, "12.00");

            InterestCalculationServiceTest.this.service.rateFor(wanted);

            ArgumentCaptor<DisclosureGroup.DisclosureGroupId> captured =
                    ArgumentCaptor.forClass(DisclosureGroup.DisclosureGroupId.class);
            verify(InterestCalculationServiceTest.this.groups).findByIdIs(captured.capture());
            assertThat(captured.getValue().getAcctGroupId()).isEqualTo(OWN_GROUP);
            assertThat(captured.getValue().getTranTypeCd()).isEqualTo(OTHER_TYPE_CODE);
            assertThat(captured.getValue().getTranCatCd()).isEqualTo("0002");
        }

        /**
         * The composite key is the account group, then the type, then the category, in that order.
         *
         * <p>Assumptions: the sixteen-character composite is
         * {@code DIS-ACCT-GROUP-ID PIC X(10)} then {@code DIS-TRAN-TYPE-CD PIC X(02)} then
         * {@code DIS-TRAN-CAT-CD PIC 9(04)}, declared in that sequence at
         * {@code app/cpy/CVTRA02Y.cpy:6-8} under the group item at {@code :5}. Two independent
         * artifacts confirm it: the file description at {@code app/cbl/CBACT04C.cbl:79-81} repeats the
         * three at the same widths, and {@code app/jcl/DISCGRP.jcl:40} defines the cluster with
         * {@code KEYS(16 0)}, which balances only if the components are ten, two and four bytes
         * wide.</p>
         *
         * <p>Assumptions: the transposed composite is asserted to be a DIFFERENT string rather than
         * merely asserting the correct one, because the two codes are same-shaped enough that a
         * reader cannot tell by inspection that the order matters. Showing the wrong order produces
         * different bytes is what makes the ruling checkable.</p>
         */
        @Test
        @DisplayName("compose the sixteen-character key as group, then type, then category")
        void theCompositeKeyIsGroupThenTypeThenCategory() {
            DisclosureGroupKey wanted = key(OWN_GROUP, OTHER_TYPE_CODE, OTHER_CATEGORY_CODE);

            assertThat(wanted.fixedWidthKey()).isEqualTo("A000000000" + "04" + "0002");
            assertThat(wanted.fixedWidthKey())
                    .as("ten plus two plus four is the sixteen app/jcl/DISCGRP.jcl:40 declares")
                    .hasSize(DisclosureGroupKey.KEY_LENGTH);
            assertThat(wanted.fixedWidthKey())
                    .as("the transposed composite is a different sixteen characters, which is what a"
                            + " transcription following the move order at :210-212 would produce")
                    .isNotEqualTo("A000000000" + "0002" + "04");
        }

        /**
         * The substituted key keeps the type and the category in their own positions too.
         *
         * <p>Assumptions: the retry key is {@code DEFAULT   } followed by the ORIGINAL type and
         * category, because {@code :437} moves the literal into {@code FD-DIS-ACCT-GROUP-ID} alone.
         * The reference's own key trace shows the tail surviving the substitution: the first read asks
         * for ten blanks followed by {@code 010001} and the retry asks for {@code DEFAULT   }
         * followed by the same {@code 010001}.</p>
         *
         * <p>Assumptions: the seed requirement that follows is ONE ROW PER TYPE AND CATEGORY PAIR
         * rather than one row, and {@code tests/fixtures/interest/default_fallback/discgrp.txt} is
         * seeded that way -- seventeen rows spanning types {@code 01} through {@code 07}. Those rows
         * are reference data owned by {@code reference-service} through its
         * {@code V2__seed_reference.sql}, so an absent one is a cross-service precondition and not a
         * defect in this module.</p>
         */
        @Test
        @DisplayName("keep the type and category positions in the substituted key as well")
        void theSubstitutedKeyPreservesTheOtherComponents() {
            DisclosureGroupKey wanted = key(OWN_GROUP, OTHER_TYPE_CODE, OTHER_CATEGORY_CODE);

            DisclosureGroupKey substituted = wanted.withDefaultAccountGroupId();

            assertThat(substituted.fixedWidthKey())
                    .isEqualTo(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID + "04" + "0002");
            assertThat(substituted.accountGroupId())
                    .as("the literal at :437 reaches a PIC X(10) field, so it is space-padded to ten")
                    .isEqualTo("DEFAULT   ")
                    .hasSize(DisclosureGroupKey.ACCOUNT_GROUP_ID_LENGTH);
            assertThat(substituted.transactionTypeCode()).isEqualTo(wanted.transactionTypeCode());
            assertThat(substituted.transactionCategoryCode())
                    .isEqualTo(wanted.transactionCategoryCode());
        }

        /**
         * An absent row at the first read is an ordinary outcome and raises nothing.
         *
         * <p>Assumptions: {@code :422} reads {@code IF DISCGRP-STATUS = '00' OR '23'} and treats
         * found and not-found as equally non-fatal, so only some other status drives the failure arm.
         * The not-found case is what SELECTS the fallback at {@code :436-438} rather than what fails
         * the step, and the reference makes that explicit by giving the read at {@code :416} an
         * {@code INVALID KEY} branch that merely displays
         * {@code DISCLOSURE GROUP RECORD MISSING} and {@code TRY WITH DEFAULT GROUP CODE}.</p>
         *
         * <p>Alternatives Considered: leaving this ruling implicit in the fallback case, which does
         * exercise the same path. Rejected because that case would also pass if the first read raised
         * and the fallback were reached through a caught exception, and the two designs differ in
         * what they do to a status the reference does regard as fatal. Asserting the absence of a
         * throw states the ruling directly.</p>
         */
        @Test
        @DisplayName("treat an absent row at the first read as ordinary, not as an error")
        void aMissAtTheFirstReadIsNotAnError() {
            stubMissing(OWN_GROUP);
            stubGroup("DEFAULT   ", "18.00");

            assertThat(InterestCalculationServiceTest.this.service.rateFor(key(OWN_GROUP)))
                    .as("the two reads at :416 and :444 both happen, and neither absence raises")
                    .isNotNull();
            verify(InterestCalculationServiceTest.this.groups, times(2)).findByIdIs(any());
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
         * The description fills its declared field, leaving exactly seventy-six padding positions.
         *
         * <p>Assumptions: the geometry is READ FROM the shared layout registry rather than written
         * here. {@code CopybookLayout.layout("INTTRAN")} is the interest producer's variant of the
         * transaction record, derived from the {@code TRAN} layout, and its {@code TRAN-DESC} field
         * declares a hundred positions. The rendered description occupies twenty-four of them --
         * thirteen literal characters from {@code app/cbl/CBACT04C.cbl:485} plus the eleven digits of
         * {@code ACCT-ID PIC 9(11)} -- so seventy-six positions remain.</p>
         *
         * <p>Assumptions: those seventy-six remaining positions carry NUL bytes and not blanks in the
         * stored record, and {@code tests/golden/interest/happy_path/transact.expected} shows it: the
         * hundred bytes at that field's offset are the twenty-four description characters followed by
         * seventy-six zero bytes. The cause is the verb -- {@code :485-489} builds the field with
         * {@code STRING}, which leaves the receiving field's untouched positions as they were, whereas
         * the posting producer uses {@code MOVE} and so blank-fills. This case pins the WIDTH and the
         * remainder arithmetic; rendering those positions is the record mapper's job, which is why no
         * padded value is asserted of this service.</p>
         *
         * <p>Alternatives Considered: writing 100 and 76 as literals in this case. Rejected because
         * the layout would then exist in two places and the copy here is the one free to drift; the
         * house convention the reference suite states for extending itself, at item 4 of
         * {@code tests/README.md} section 12, requires a record layout be single-sourced rather than
         * duplicated.</p>
         */
        @Test
        @DisplayName("fill the declared description field, leaving seventy-six padding positions")
        void theDescriptionLeavesSeventySixPaddingPositions() {
            int declaredWidth = CopybookLayout.layout("INTTRAN").field("TRAN-DESC").length();

            String rendered = write(1L).getDescription();

            assertThat(rendered).isEqualTo("Int. for a/c 00000000001");
            assertThat(rendered).hasSize(
                    InterestCalculationService.INTEREST_DESCRIPTION_PREFIX.length()
                            + InterestCalculationService.ACCOUNT_IDENTIFIER_DIGITS);
            assertThat(declaredWidth - rendered.length())
                    .as("the golden's hundred description bytes are twenty-four characters followed"
                            + " by seventy-six NUL bytes, so the remainder is exactly seventy-six")
                    .isEqualTo(76);
            assertThat(rendered.length())
                    .as("the rendered value must fit the field it is stored into")
                    .isLessThanOrEqualTo(declaredWidth);
        }

        /**
         * The source and the two codes fit their declared fields, and the source is held unpadded.
         *
         * <p>Assumptions: {@code :484} moves the six-character literal {@code System} into a field
         * declared ten wide, and {@code tests/golden/interest/happy_path/transact.expected} carries
         * {@code System} followed by four blanks at that field's offset. The service holds the
         * reference's own literal at its own width and the blank-filling belongs to the record mapper,
         * so what is asserted here is that the value FITS the declared field rather than that it
         * already fills it -- padding it in two places would let the two disagree.</p>
         *
         * <p>Assumptions: the generated category code occupies all four of its declared positions,
         * because {@code TRAN-CAT-CD} is {@code PIC 9(04)} and the {@code MOVE} at {@code :483}
         * zero-fills the two-character literal to that width. That is why the stored form is
         * {@code 0005} and not {@code 05}, and the golden confirms it.</p>
         */
        @Test
        @DisplayName("fit the source and codes to their declared fields, source held unpadded")
        void theSourceAndCodesFitTheirDeclaredFields() {
            CopybookLayout.RecordSpec layout = CopybookLayout.layout("INTTRAN");
            Transaction generated = write(1L);

            assertThat(generated.getSource())
                    .isEqualTo("System")
                    .as("the reference literal is six characters and the field is ten, so it fits with"
                            + " four positions of blank fill the mapper supplies")
                    .hasSizeLessThan(layout.field("TRAN-SOURCE").length());
            assertThat(layout.field("TRAN-SOURCE").length() - generated.getSource().length())
                    .as("the golden shows exactly four blanks after the literal")
                    .isEqualTo(4);
            assertThat(generated.getCategoryCd())
                    .as("the zero-filled category fills all four declared positions")
                    .hasSize(layout.field("TRAN-CAT-CD").length())
                    .hasSize(InterestCalculationService.TRANSACTION_CATEGORY_CODE_DIGITS);
            assertThat(generated.getTypeCd()).hasSize(layout.field("TRAN-TYPE-CD").length());
        }

        /**
         * The generated identifier fills the whole of its declared field, with nothing left over.
         *
         * <p>Assumptions: the ten-character business-date token and the six-digit suffix fill
         * {@code TRAN-ID} exactly, which is why neither width is free to change on its own. The field
         * width is read from the shared layout registry, where {@code TRAN-ID} declares sixteen
         * positions, and the same sixteen is independently the retrieval-key length of that record.</p>
         *
         * <p>Assumptions: the two widths are those of {@code PARM-DATE} and
         * {@code WS-TRANID-SUFFIX PIC 9(06)} at {@code app/cbl/CBACT04C.cbl:173}, concatenated
         * {@code DELIMITED BY SIZE} at {@code :476-480}, and
         * {@code tests/golden/interest/happy_path/transact.expected} carries the resulting sixteen
         * characters {@code 2024-01-15000001} in its first field.</p>
         */
        @Test
        @DisplayName("fill the declared identifier field exactly, token plus suffix")
        void theIdentifierFillsItsDeclaredField() {
            int declaredWidth = CopybookLayout.layout("INTTRAN").field("TRAN-ID").length();

            assertThat(write(1L).getTransactionId()).hasSize(declaredWidth);

            // WHY : Assumptions: the token's width is taken from the token itself rather than from a
            //       published constant, because the type keeps its ten-character invariant private and
            //       enforces it in its own constructor. Reading the length of a value that type has
            //       already admitted asks the same question without duplicating the number.
            assertThat(BUSINESS_DATE.token().length()
                    + InterestCalculationService.IDENTIFIER_SUFFIX_DIGITS)
                    .as("ten of token plus six of suffix leaves no padding in the sixteen-character"
                            + " field, which is what makes an oversized suffix unrepresentable")
                    .isEqualTo(declaredWidth);
        }

        /**
         * The category is the hardcoded literal and never the driving balance's own category.
         *
         * <p>Assumptions: the value is the literal moved at {@code app/cbl/CBACT04C.cbl:483}, and it
         * is emphatically not the category of the balance being accrued -- which {@code :211} copies
         * into the RATE key and nowhere else. Every row of
         * {@code tests/fixtures/interest/happy_path/tcatbal.txt} is keyed on category {@code 0001}
         * while {@code tests/golden/interest/happy_path/transact.expected} carries {@code 0005} in the
         * generated row, so propagating the source category is the plausible reading and the wrong
         * one. This case asserts the constant rather than a pass-through.</p>
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
         * overflow the column and truncating would reuse an identifier already issued in the run. The
         * six digits are those of {@code WS-TRANID-SUFFIX PIC 9(06)} at
         * {@code app/cbl/CBACT04C.cbl:173} and the sixteen are those of {@code TRAN-ID PIC X(16)} at
         * {@code app/cpy/CVTRA05Y.cpy:5}.</p>
         *
         * <p>Trade-offs: refusing is chosen over widening or truncating, and it is a DIVERGENCE from
         * the baseline in form though not in effect: a COBOL numeric-display field simply cannot hold
         * a seventh digit, so the condition is unrepresentable there rather than checked. Raising is
         * what makes it unrepresentable here too, instead of silently producing a seventeen-character
         * identifier the column cannot store.</p>
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
         * Three category rows of one account yield three rows and a sum-of-truncated increment.
         *
         * <p>This is the whole of coverage gap G-5 driven end to end at this tier: the emission at
         * {@code :468} runs once per category row, the accumulate at {@code :467} adds each
         * already-truncated term, and {@code :352} then adds the accumulated total to the balance. The
         * two rulings are asserted together because they share one cause -- the emit and the
         * accumulate are consecutive statements inside {@code 1300-COMPUTE-INTEREST} -- so a
         * transcription that hoisted either to the account level would break both.</p>
         *
         * <p>Assumptions: this vector is CONSTRUCTED because no shipped fixture can supply it. Every
         * interest fixture gives each account exactly ONE category-balance row, so one account
         * contributes one term and one emitted row, which makes per-row and per-account behaviour
         * indistinguishable in both dimensions at once.</p>
         *
         * <p>Assumptions: the three rows are built as real {@code TransactionCategoryBalance} entities
         * rather than as bare decimals, so the vector carries the key the reference walks --
         * {@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD} and {@code TRANCAT-CD} at
         * {@code app/cpy/CVTRA01Y.cpy:6-8} -- and three DISTINCT categories of one account is the
         * shape the ruling is about rather than three repetitions of one row.</p>
         *
         * <p>Assumptions: the balance reaches the account through the accumulated total and never
         * through any per-row write, because {@code 1300-B-WRITE-TX} writes only to the transaction
         * file. The increment is therefore 0.60 and not 0.62, and the difference is the two cents the
         * three truncations discard.</p>
         */
        @Test
        @DisplayName("emit three rows for three category balances and increment the balance by 0.60")
        void threeCategoryRowsEmitThreeRowsAndIncrementBySixtyCents() {
            List<TransactionCategoryBalance> categoryRows = threeCategoryRowsOf(ACCOUNT_ID);
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    key(OWN_GROUP), new BigDecimal("2.50"));
            Account account = new Account(ACCOUNT_ID, "Y", new BigDecimal("194.00"),
                    new BigDecimal("20200.00"), new BigDecimal("2000.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1),
                    new BigDecimal("25.00"), new BigDecimal("75.00"), OWN_GROUP, OWN_GROUP);

            Money accumulated = Money.ZERO;
            long suffix = 0L;
            for (TransactionCategoryBalance categoryRow : categoryRows) {
                Money accrued = InterestCalculationServiceTest.this.service
                        .monthlyInterest(Money.of(categoryRow.getBalance()), lookup);

                // WHY : Assumptions: the accumulate happens per row with the ALREADY-truncated term,
                //       which is what :467 adds. Accumulating the raw quotients and reducing once at the
                //       end would reach 0.62 here, and that is the defect this vector exists to catch.
                accumulated = accumulated.plus(accrued);
                suffix++;
                InterestCalculationServiceTest.this.service.writeInterestTransaction(
                        ACCOUNT_ID, CARD_NUMBER, accrued, BUSINESS_DATE, suffix, STAMP);
            }
            InterestCalculationServiceTest.this.service.flushAccount(account, accumulated);

            ArgumentCaptor<Transaction> emitted = ArgumentCaptor.forClass(Transaction.class);
            verify(InterestCalculationServiceTest.this.ledger, times(3)).save(emitted.capture());
            assertThat(emitted.getAllValues())
                    .as("one row per category balance, because :468 sits inside 1300-COMPUTE-INTEREST")
                    .hasSameSizeAs(categoryRows)
                    .extracting(Transaction::getTransactionId)
                    .containsExactly("2024-01-15000001", "2024-01-15000002", "2024-01-15000003");
            assertThat(emitted.getAllValues())
                    .as("each row carries its own truncated term, all 0.20 on this vector")
                    .allSatisfy(row -> assertThat(row.getAmount()).isEqualByComparingTo("0.20"));

            assertThat(accumulated.amount())
                    .as("the sum of three truncated terms is 0.60; truncating their raw sum gives 0.62")
                    .isEqualByComparingTo("0.60");
            assertThat(account.getCurrBal())
                    .as("194.00 plus the sum-of-truncated 0.60; a reduce-once total would give 194.62")
                    .isEqualByComparingTo("194.60");
            assertThat(account.getCurrBal())
                    .as("194.62 is the reduce-once answer and must not be reachable")
                    .isNotEqualByComparingTo("194.62");
            assertThat(account.getCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(account.getCurrCycDebit()).isEqualByComparingTo("0.00");
        }

        /**
         * Builds three distinct category-balance rows of one account, each accruing to 0.20.
         *
         * <p>Assumptions: the balance 99.60 at a rate of 2.50 per cent forms the scale-4 product
         * 249.0000 and the quotient 0.2075 exactly, which truncates to 0.20. Three such rows sum to
         * 0.60 once each is truncated, whereas their unreduced products total 747.0000 and truncate to
         * 0.62 -- so the vector separates the two accumulation strategies by two cents, which is what
         * makes it worth constructing.</p>
         *
         * <p>Assumptions: the three rows share the account and the transaction type and differ in
         * CATEGORY, because that is how one account comes to hold several rows in the reference feed:
         * the key at {@code app/cpy/CVTRA01Y.cpy:5-8} is account, type and category together, so rows
         * differing only in category are distinct rows of the same account.</p>
         *
         * @param accountId the account all three rows belong to
         * @return the three rows in category order, never {@code null}
         */
        private List<TransactionCategoryBalance> threeCategoryRowsOf(long accountId) {
            List<TransactionCategoryBalance> rows = new ArrayList<>();
            for (int category = 1; category <= 3; category++) {
                rows.add(new TransactionCategoryBalance(
                        new TransactionCategoryBalance.TransactionCategoryBalanceId(
                                accountId, TYPE_CODE, "000" + category),
                        new BigDecimal("99.60")));
            }
            return List.copyOf(rows);
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

    /**
     * The gate at {@code :214}, whose two zero cases pull in opposite directions.
     *
     * <p>Assumptions: {@code app/cbl/CBACT04C.cbl:214} reads {@code IF DIS-INT-RATE NOT = 0} and
     * encloses BOTH {@code :215} and {@code :216}, so the gate stands on the RATE and never on the
     * balance. The two zeros therefore behave oppositely: a zero rate suppresses the accrual, the
     * emitted row and the fee step together, while a zero balance passes the gate and accrues to
     * {@code 0.00} with a row emitted for it. The two cases are kept adjacent here because they are
     * trivially conflated and a gate written on the wrong operand would satisfy one of them.</p>
     *
     * <p>Assumptions: the gate is the caller's to apply, and the predicate that expresses it is
     * {@code InterestRateLookup.interestApplicable()}. This service accrues whatever it is handed, so
     * these cases drive the predicate and then assert what the service was and was not asked to do --
     * which is exactly the shape of the reference, where {@code :214} sits in the driving loop and the
     * accrual paragraphs it guards perform no test of their own.</p>
     */
    @Nested
    @DisplayName("the zero-rate gate and the zero-balance accrual")
    class ZeroRateAndZeroBalance {

        /**
         * A zero rate suppresses everything: no accrual, no emitted row, and no fee step.
         *
         * <p>Assumptions: this case is CONSTRUCTED and closes coverage gap G-3, which no shipped
         * fixture can close. The fixture named {@code zero_balance} is a zero-BALANCE scenario and
         * not a zero-rate one -- its {@code discgrp.txt} carries a rate of 15.00 and its golden
         * {@code transact.expected} is 702 bytes, which is two 350-byte rows plus their newlines, so
         * two transactions ARE written there. Genuine zero-rate rows do exist in
         * {@code tests/fixtures/interest/default_fallback/discgrp.txt}, where types {@code 02},
         * {@code 03} and {@code 07} all carry 0.00, but no category-balance row in the interest domain
         * addresses any of them, so the zero-rate branch is never driven by the committed data.</p>
         *
         * <p>Assumptions: the fee step is asserted suppressed alongside the accrual because
         * {@code :214} encloses both {@code :215} and {@code :216}. A caller that gated only the
         * accrual would perform the fee step more often than the reference does, which nothing would
         * report today and which would become a behavioural difference the moment the fee seam did
         * anything.</p>
         */
        @Test
        @DisplayName("emit nothing at all on a zero rate, neither an accrual nor a fee")
        void aZeroRateEmitsNothing() {
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    key(OWN_GROUP), new BigDecimal("0.00"));

            // WHY : Assumptions: the gate is DRIVEN here before it is asserted, so the verification
            //       below is load-bearing rather than guarded. A predicate that wrongly reported a zero
            //       rate as applicable would enter this branch, emit a row, and fail both the count and
            //       the assertion after it; asserting the predicate first would have made the branch
            //       unreachable and the verification vacuous.
            // WHY : Assumptions: the guarded work is SKIPPED rather than performed and discarded,
            //       because :214 encloses both :215 and :216. Accruing and then declining to write
            //       would leave the same ledger but would also compute a fee the reference never
            //       computes, and that difference is invisible only while the fee seam stays empty.
            if (lookup.interestApplicable()) {
                InterestCalculationServiceTest.this.service.writeInterestTransaction(
                        ACCOUNT_ID, CARD_NUMBER,
                        InterestCalculationServiceTest.this.service.monthlyInterest(
                                Money.of("1000.00"), lookup),
                        BUSINESS_DATE, 1L, STAMP);
                InterestCalculationServiceTest.this.service.computeFees();
            }

            assertThat(lookup.interestApplicable())
                    .as("the gate at :214 closes on a zero rate, so neither :215 nor :216 runs")
                    .isFalse();
            verify(InterestCalculationServiceTest.this.ledger, never())
                    .save(any(Transaction.class));
        }

        /**
         * A zero balance at a non-zero rate accrues zero and DOES emit a row for it.
         *
         * <p>Assumptions: the gate at {@code :214} never inspects the balance, so a zero balance
         * reaches {@code 1300-COMPUTE-INTEREST}, produces a quotient of {@code 0.00} and reaches
         * {@code 1300-B-WRITE-TX} at {@code :468} like any other row.
         * {@code tests/golden/interest/zero_balance/transact.expected} records exactly that: two rows
         * whose {@code TRAN-AMT} fields decode to zero, written from category balances of 0.00 against
         * a rate of 15.00.</p>
         *
         * <p>Assumptions: this case sits beside the zero-rate one deliberately. The two are the whole
         * content of the ruling, they point in opposite directions, and a gate implemented on the
         * balance instead of the rate would emit nothing here and emit a row there -- passing neither,
         * but only if both are present to be compared.</p>
         */
        @Test
        @DisplayName("emit one row at 0.00 on a zero balance, because the gate is on the rate")
        void aZeroBalanceStillEmitsOneRow() {
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    key(OWN_GROUP), new BigDecimal("15.00"));

            assertThat(lookup.interestApplicable())
                    .as("the rate is non-zero, so the gate at :214 opens whatever the balance is")
                    .isTrue();

            Money accrued = InterestCalculationServiceTest.this.service
                    .monthlyInterest(Money.of("0.00"), lookup);
            Transaction emitted = InterestCalculationServiceTest.this.service
                    .writeInterestTransaction(ACCOUNT_ID, CARD_NUMBER, accrued, BUSINESS_DATE, 1L,
                            STAMP);

            assertThat(accrued.amount()).isEqualByComparingTo("0.00");
            assertThat(accrued.amount().scale()).isEqualTo(Money.SCALE);
            assertThat(emitted.getAmount())
                    .as("the golden's two zero_balance rows carry a zero amount, so the row exists"
                            + " and is not suppressed")
                    .isEqualByComparingTo("0.00");
            verify(InterestCalculationServiceTest.this.ledger, times(1))
                    .save(any(Transaction.class));
        }

        /**
         * A zero rate carried at a different scale still closes the gate.
         *
         * <p>Assumptions: the comparison behind the gate is {@code compareTo} against zero and never
         * {@code equals}, because {@code BigDecimal.equals} compares scale as well as unscaled value
         * -- so {@code 0}, {@code 0.0} and {@code 0.000} are three unequal objects that are all
         * numerically zero. The rate arrives from a {@code NUMERIC(6,2)} column through a driver free
         * to hand back any of them, and {@code :214} asks the numeric question.</p>
         *
         * <p>Assumptions: the scale-3 zero is offered to the RECORD, whose compact constructor
         * canonicalises it to two places through the shared kernel, and the gate is then asserted
         * closed. Both halves matter: the canonicalisation is what makes an equals-based gate
         * accidentally correct today, and the raw comparison asserted alongside it is what shows the
         * gate would still be right if that canonicalisation were ever removed.</p>
         *
         * <p>Alternatives Considered: asserting only against a two-place zero. Rejected because a gate
         * written with {@code equals} against an unscaled {@code BigDecimal.ZERO} passes that
         * assertion -- {@code 0.00} is unequal to {@code ZERO} under {@code equals}, so such a gate
         * would report a zero rate as applicable and the case would still be green.</p>
         */
        @Test
        @DisplayName("close the gate on a zero rate at any scale, comparing value and not scale")
        void aZeroRateAtAnotherScaleStillClosesTheGate() {
            BigDecimal zeroAtScaleThree = new BigDecimal("0.000");

            assertThat(zeroAtScaleThree.scale())
                    .as("the vector is only meaningful if its scale really differs from the column's")
                    .isNotEqualTo(Money.SCALE);
            assertThat(zeroAtScaleThree)
                    .as("an equals-based gate would call this non-zero, which is the defect the"
                            + " compareTo ruling exists to prevent")
                    .isNotEqualTo(BigDecimal.ZERO);
            assertThat(zeroAtScaleThree.compareTo(BigDecimal.ZERO)).isZero();

            InterestRateLookup lookup =
                    InterestRateLookup.ofDirectHit(key(OWN_GROUP), zeroAtScaleThree);

            assertThat(lookup.interestApplicable())
                    .as("a numerically zero rate closes the gate whatever scale it arrived at")
                    .isFalse();
            assertThat(lookup.resolvedRate().scale())
                    .as("the record canonicalises the rate to the money scale on the way in")
                    .isEqualTo(Money.SCALE);
        }

        /**
         * A non-zero rate at a different scale still opens the gate.
         *
         * <p>Assumptions: the mirror of the case above, and it is present because a gate that
         * compared scale rather than value could fail in either direction. A rate of {@code 15.000}
         * is numerically the {@code 15.00} the fixtures carry, so it must accrue identically -- and
         * the accrual is asserted to reach the golden's 12.50 rather than merely to be attempted --
         * the amount that {@code tests/golden/interest/happy_path/transact.expected} carries as the
         * zoned field {@code 0000000125} with a positive overpunch. The gate itself is
         * {@code app/cbl/CBACT04C.cbl:214}.</p>
         */
        @Test
        @DisplayName("open the gate on a non-zero rate at any scale, and accrue identically")
        void aNonZeroRateAtAnotherScaleAccruesIdentically() {
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    key(OWN_GROUP), new BigDecimal("15.000"));

            assertThat(lookup.interestApplicable()).isTrue();
            assertThat(InterestCalculationServiceTest.this.service
                    .monthlyInterest(Money.of("1000.00"), lookup).amount())
                    .as("15.000 is numerically the fixtures' 15.00, so it accrues the golden's 12.50")
                    .isEqualByComparingTo("12.50");
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
         * All three state changes happen, on an account whose cycle amounts start non-zero.
         *
         * <p>Assumptions: {@code 1050-UPDATE-ACCOUNT} makes THREE changes before it writes --
         * {@code :352} adds the accumulated total to the current balance, {@code :353} zeroes the
         * cycle credit and {@code :354} zeroes the cycle debit -- and only then does {@code :356}
         * rewrite the row. All three are asserted together here because they are one indivisible
         * transition, which is why the entity exposes them as a single operation.</p>
         *
         * <p>Assumptions: this vector is CONSTRUCTED and closes coverage gap G-4, which no shipped
         * fixture can close. Every interest fixture arrives with both cycle amounts ALREADY at zero --
         * {@code tests/golden/interest/default_fallback/acctdat.expected} shows both fields zero for
         * both accounts, and the inputs were zero too -- so a transcription that dropped {@code :353}
         * and {@code :354} entirely would pass against the committed data. Non-zero inputs are what
         * make the assignments observable. The reset is also absent from the migration plan's own
         * description of this paragraph, so nothing but the source states it.</p>
         */
        @Test
        @DisplayName("apply the balance and clear both cycle amounts, all three in one transition")
        void allThreeStateChangesHappen() {
            Account account = account("194.00", "25.00", "75.00");

            InterestCalculationServiceTest.this.service.flushAccount(account, Money.of("12.50"));

            assertThat(account.getCurrBal())
                    .as(":352 adds the accumulated total to the current balance")
                    .isEqualByComparingTo("206.50");
            assertThat(account.getCurrCycCredit())
                    .as(":353 zeroes the cycle credit, which started at 25.00 here")
                    .isEqualByComparingTo("0.00");
            assertThat(account.getCurrCycDebit())
                    .as(":354 zeroes the cycle debit, which started at 75.00 here")
                    .isEqualByComparingTo("0.00");

            // WHY : Assumptions: the cleared amounts are checked for SCALE as well as value because the
            //       entity stores a zero carried at the money scale rather than the unscaled constant.
            //       A zero at another scale is numerically identical and compares equal, so only the
            //       scale assertion distinguishes it -- and a round-trip of the row would differ.
            assertThat(account.getCurrCycCredit().scale()).isEqualTo(Money.SCALE);
            assertThat(account.getCurrCycDebit().scale()).isEqualTo(Money.SCALE);
        }

        /**
         * The three state changes all precede the write, rather than following it.
         *
         * <p>Assumptions: {@code :356} rewrites the record AFTER {@code :352} through {@code :354}
         * have changed it, so the row handed to the write already carries the applied balance and both
         * cleared amounts. The state is snapshotted INSIDE the save stub, at the moment the write is
         * invoked, because the account object is mutated in place -- reading it afterwards would show
         * the final state whatever order the service had used, so an assertion made after the call
         * cannot distinguish a write that preceded the changes from one that followed them.</p>
         */
        @Test
        @DisplayName("write the row only after all three changes have been applied to it")
        void theWriteFollowsTheStateChanges() {
            List<BigDecimal> atWriteTime = new ArrayList<>();
            when(InterestCalculationServiceTest.this.accounts.save(any(Account.class)))
                    .thenAnswer(call -> {
                        Account written = call.getArgument(0);
                        atWriteTime.add(written.getCurrBal());
                        atWriteTime.add(written.getCurrCycCredit());
                        atWriteTime.add(written.getCurrCycDebit());
                        return written;
                    });

            InterestCalculationServiceTest.this.service
                    .flushAccount(account("194.00", "25.00", "75.00"), Money.of("12.50"));

            assertThat(atWriteTime)
                    .as("the row reaching :356 already carries the applied balance and both cleared"
                            + " amounts, so the write is last and not first")
                    .hasSize(3);
            assertThat(atWriteTime.get(0)).isEqualByComparingTo("206.50");
            assertThat(atWriteTime.get(1)).isEqualByComparingTo("0.00");
            assertThat(atWriteTime.get(2)).isEqualByComparingTo("0.00");
        }

        /**
         * On a control break the accumulated total reaches the account before the accumulator clears.
         *
         * <p>Assumptions: the reference performs the flush at {@code :196} and only then clears the
         * running total at {@code :200}, so the total belonging to the account just finished is
         * committed rather than discarded. The two statements are four lines apart in one
         * {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} block at {@code :194}, and inverting them
         * is the classic control-break defect: it silently applies zero to every account.</p>
         *
         * <p>Assumptions: the accumulator itself belongs to the walk rather than to this service --
         * this type holds no mutable state, which is what lets one instance serve concurrent runs --
         * so the sequence is driven here as the walk drives it. The ordering is asserted through the
         * VALUE that reached the account, which is what an inversion would change, together with the
         * order the two writes arrived in.</p>
         */
        @Test
        @DisplayName("flush the accumulated total before clearing it, not after")
        void theFlushPrecedesTheReset() {
            // WHY : Assumptions: the two accounts carry DISTINCT identifiers because this entity's
            //       equality is the identifier alone, as a row identity should be. Two accounts sharing
            //       one identifier would be indistinguishable to the ordered verification below, so
            //       either write would satisfy either expectation and the ordering would go unchecked.
            Account first = account(ACCOUNT_ID, "194.00", "0.00", "0.00");
            Account second = account(SECOND_ACCOUNT_ID, "158.00", "0.00", "0.00");

            // WHY : Assumptions: the accumulator is re-bound to ZERO between the two accounts rather
            //       than mutated, because the shared money type is immutable. That is the migrated form
            //       of :200, and re-binding after the flush at :196 is the ordering under assertion.
            Money accumulated = Money.of("12.50");
            InterestCalculationServiceTest.this.service.flushAccount(first, accumulated);
            accumulated = Money.ZERO;
            accumulated = accumulated.plus(Money.of("4.25"));
            InterestCalculationServiceTest.this.service.flushAccount(second, accumulated);

            assertThat(first.getCurrBal())
                    .as("the first account received its OWN total; a reset performed before the flush"
                            + " would have applied zero and left this at 194.00")
                    .isEqualByComparingTo("206.50");
            assertThat(second.getCurrBal())
                    .as("the second account received only the total accumulated after the reset, so"
                            + " the first account's 12.50 did not carry into it")
                    .isEqualByComparingTo("162.25");

            InOrder writes = inOrder(InterestCalculationServiceTest.this.accounts);
            writes.verify(InterestCalculationServiceTest.this.accounts).save(first);
            writes.verify(InterestCalculationServiceTest.this.accounts).save(second);
        }

        /**
         * The first account of a walk is not flushed before its own rows have been seen.
         *
         * <p>Assumptions: the break at {@code :194} fires on the FIRST row as well, because
         * {@code WS-LAST-ACCT-NUM} is initialised to spaces at {@code :167} and no account identifier
         * equals spaces. The guard at {@code :195}, {@code IF WS-FIRST-TIME NOT = 'Y'}, is what stops
         * that first break performing a flush, and {@code :198} then turns the flag off so every later
         * break does flush. Dropping the guard would write one extra row per run for an account that
         * had accrued nothing.</p>
         *
         * <p>Assumptions: the migrated walk uses a nullable identifier and an explicit
         * first-iteration flag rather than reproducing the spaces sentinel, because
         * {@code WS-LAST-ACCT-NUM PIC X(11)} is alphanumeric while {@code TRANCAT-ACCT-ID PIC 9(11)}
         * at {@code app/cpy/CVTRA01Y.cpy:6} is numeric -- so the comparison the reference makes has no
         * faithful equivalent between a Java number and a sentinel string, and an identifier that
         * genuinely has no prior value is what {@code null} expresses.</p>
         */
        @Test
        @DisplayName("perform one flush per account and none before the first account's rows")
        void theFirstBreakDoesNotFlush() {
            List<Account> walked = List.of(
                    account(ACCOUNT_ID, "194.00", "0.00", "0.00"),
                    account(SECOND_ACCOUNT_ID, "158.00", "0.00", "0.00"));

            // WHY : Assumptions: the walk is expressed with a nullable "previous" reference and no
            //       sentinel, which is the migrated form of the :167 spaces initialisation. The break
            //       fires on the first row too, and it is the null that suppresses the flush there --
            //       exactly as the :195 first-time guard suppresses it in the reference.
            Account pendingFlush = null;
            for (Account current : walked) {
                if (pendingFlush != null) {
                    InterestCalculationServiceTest.this.service
                            .flushAccount(pendingFlush, Money.of("12.50"));
                }
                pendingFlush = current;
            }
            InterestCalculationServiceTest.this.service.flushAccount(pendingFlush, Money.of("4.25"));

            // WHY : Assumptions: two accounts yield exactly TWO writes. A walk that dropped the
            //       first-iteration guard would flush before the first account's rows had been seen and
            //       produce three, which is the one count that distinguishes the defect -- asserting
            //       merely that a flush happened would not.
            verify(InterestCalculationServiceTest.this.accounts, times(2))
                    .save(any(Account.class));
            assertThat(walked.get(0).getCurrBal())
                    .as("the first account was flushed once, on the break its successor caused")
                    .isEqualByComparingTo("206.50");
            assertThat(walked.get(1).getCurrBal())
                    .as("the last account is flushed too, which is the documented D-3 divergence")
                    .isEqualByComparingTo("162.25");
        }

        /**
         * Builds an account with a nominated balance and cycle amounts, on the primary identifier.
         *
         * @param balance the current balance as exact decimal text
         * @param cycleCredit the cycle credit as exact decimal text
         * @param cycleDebit the cycle debit as exact decimal text
         * @return the account, never {@code null}
         */
        private Account account(String balance, String cycleCredit, String cycleDebit) {
            return account(ACCOUNT_ID, balance, cycleCredit, cycleDebit);
        }

        /**
         * Builds an account with a nominated identifier, balance and cycle amounts.
         *
         * <p>Assumptions: the limits, dates and group are constant values this family never asserts on,
         * and they are supplied only because the entity's constructor takes the whole row. The three
         * members that vary are the ones {@code 1050-UPDATE-ACCOUNT} changes at {@code :352-354}, and
         * the identifier varies so that a walk over two accounts has two distinguishable rows.</p>
         *
         * @param accountId the eleven-digit identifier this row is keyed by
         * @param balance the current balance as exact decimal text
         * @param cycleCredit the cycle credit as exact decimal text
         * @param cycleDebit the cycle debit as exact decimal text
         * @return the account, never {@code null}
         */
        private Account account(long accountId, String balance, String cycleCredit,
                String cycleDebit) {

            return new Account(accountId, "Y", new BigDecimal(balance),
                    new BigDecimal("20200.00"), new BigDecimal("2000.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1),
                    new BigDecimal(cycleCredit), new BigDecimal(cycleDebit), OWN_GROUP, OWN_GROUP);
        }
    }

    /** The two control-break reads at {@code :372-413}, and the fee seam at {@code :518}. */
    @Nested
    @DisplayName("the control-break reads and the fee seam")
    class ControlBreakReads {

        /**
         * An account that exists is answered as present.
         *
         * <p>Assumptions: this is {@code 1100-GET-ACCT-DATA} at
         * {@code app/cbl/CBACT04C.cbl:372-391}, performed from the control break at {@code :203} and
         * therefore once per ACCOUNT GROUP rather than once per category row. The row it answers is
         * both the source of the disclosure-group component that {@code :210} copies into the rate
         * key and the row the accrued total is later applied to, and those remain two separate steps
         * here as they are there.</p>
         */
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
         *
         * <p>Trade-offs: a case that asserts nothing happens is kept rather than omitted, and the seam
         * itself is kept rather than the call site removed. The cost is a case a reader may take for
         * padding and a method a reader may take for an oversight. What is bought is that fee accrual
         * is visibly INTENDED AND UNWRITTEN in the baseline -- the job header claims the step computes
         * interest and fees while the paragraph body is one comment -- which a silent omission on
         * either side would hide, and that supplying behaviour here becomes a change this case reports
         * rather than one that lands unnoticed. Inventing fee logic would be a behavioural change
         * against the baseline and would need its own registered divergence.</p>
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
     * Builds the three-part lookup key the accrual resolves rates by, at the default codes.
     *
     * @param accountGroupId the account group component, at its declared ten-character width
     * @return the key, never {@code null}
     */
    private static DisclosureGroupKey key(String accountGroupId) {
        return key(accountGroupId, TYPE_CODE, CATEGORY_CODE);
    }

    /**
     * Builds the three-part lookup key at nominated transaction type and category codes.
     *
     * <p>Assumptions: the components reach the record's positional constructor in the PHYSICAL order
     * of {@code DIS-GROUP-KEY} -- account group, then type, then category, per
     * {@code app/cpy/CVTRA02Y.cpy:6-8} -- and NOT in the order {@code app/cbl/CBACT04C.cbl:210-212}
     * assigns them, which is account group, then CATEGORY, then TYPE. The reference's order is
     * presentational because each of its statements names its destination field; a record's
     * components are positional, so following the reference's sequence here would transpose two of
     * them silently.</p>
     *
     * @param accountGroupId the account group component, at its declared ten-character width
     * @param typeCode the transaction type component, at its declared two-character width
     * @param categoryCode the transaction category component as a number, rendered to four digits by
     *     the key itself
     * @return the key, never {@code null}
     */
    private static DisclosureGroupKey key(String accountGroupId, String typeCode, int categoryCode) {
        return new DisclosureGroupKey(accountGroupId, typeCode, categoryCode);
    }

    /**
     * Stubs the rate table to answer one group with one rate, at the default codes.
     *
     * @param accountGroupId the group component the stub answers for
     * @param rate the rate to answer, as exact decimal text
     */
    private void stubGroup(String accountGroupId, String rate) {
        stubGroup(key(accountGroupId), rate);
    }

    /**
     * Stubs the rate table to answer one whole key with one rate.
     *
     * <p>Assumptions: the stub is keyed on the repository identity assembled from the key's own named
     * components rather than on a permissive matcher, so a production call that transposed the type
     * and the category would miss this stub and the case would fail. A lenient matcher would answer
     * the transposed call too and the key-order ruling would become unobservable.</p>
     *
     * @param wanted the whole three-part key the stub answers for
     * @param rate the rate to answer, as exact decimal text
     */
    private void stubGroup(DisclosureGroupKey wanted, String rate) {
        DisclosureGroup.DisclosureGroupId id = identityOf(wanted);
        when(this.groups.findByIdIs(id))
                .thenReturn(Optional.of(new DisclosureGroup(id, new BigDecimal(rate))));
    }

    /**
     * Stubs the rate table to answer nothing for one group, at the default codes.
     *
     * @param accountGroupId the group component the stub reports absent
     */
    private void stubMissing(String accountGroupId) {
        stubMissing(key(accountGroupId));
    }

    /**
     * Stubs the rate table to answer nothing for one whole key.
     *
     * @param wanted the whole three-part key the stub reports absent
     */
    private void stubMissing(DisclosureGroupKey wanted) {
        when(this.groups.findByIdIs(identityOf(wanted))).thenReturn(Optional.empty());
    }

    /**
     * Renders a lookup key as the repository identity the rate table is addressed by.
     *
     * <p>Assumptions: the category component is taken in its zero-padded four-character form, which
     * is what {@code DIS-TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA02Y.cpy:8} holds and what the
     * stored key column compares against. The numeric accessor would carry the right number in a
     * form that matches no row, so the conversion goes through the key's own rendering rather than
     * through this test's arithmetic.</p>
     *
     * @param wanted the key whose three components identify the wanted row
     * @return the repository identity for that key, never {@code null}
     */
    private static DisclosureGroup.DisclosureGroupId identityOf(DisclosureGroupKey wanted) {
        return new DisclosureGroup.DisclosureGroupId(wanted.accountGroupId(),
                wanted.transactionTypeCode(), wanted.transactionCategoryCodeField());
    }
}
