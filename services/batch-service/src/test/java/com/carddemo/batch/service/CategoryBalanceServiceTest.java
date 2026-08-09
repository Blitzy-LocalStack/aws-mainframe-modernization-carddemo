package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Proves the category-balance create arm and update arm stay two separately reachable outcomes rather
 * than one merged effect.
 *
 * <p>The subject is {@link CategoryBalanceService}, the second of the three writes the posting unit of
 * work commits together at {@code app/cbl/CBTRN02C.cbl:440-442}. The behaviour under assertion is one
 * dispatcher and two arms: {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:467} composes
 * the three-part key and reads, {@code 2700-A-CREATE-TCATBAL-REC} at line 503 is the create arm, and
 * {@code 2700-B-UPDATE-TCATBAL-REC} at line 526 is the update arm, selected between at lines 495 to
 * 499.</p>
 *
 * <p>Alternatives Considered: covering the two arms with one parameterised case seeded differently per
 * invocation, which is shorter and would report a single green line. Rejected because the arm taken is
 * the property being asserted, not a variation of one property: section 13 of {@code tests/README.md}
 * names {@code 2700-A-CREATE} and {@code 2700-B-UPDATE} and requires the branch exercised both ways,
 * so a case that cannot report which way it went does not discharge that requirement. Two nested
 * groups make the partition visible in the test report itself, and a change that collapsed one arm
 * would fail the group named for it rather than one parameter of several.</p>
 *
 * <p>Alternatives Considered: asserting the resulting balance alone, which is the obvious reading of
 * "the balance was maintained" and is what the aggregate cases noted below do. Rejected because the
 * balance provably cannot discriminate the arms. A row seeded at {@code +0.00} and an absent row both
 * leave the balance equal to the transaction amount, so an assertion on the number passes with either
 * arm running -- and the seeded-zero shape is not hypothetical, it is the committed fixture:
 * {@code tests/fixtures/posting/boundary_exact_limit/README.md} section 3 seeds
 * {@code 00000000007/01/0001} at {@code +0.00} and records that it "forces the {@code 2700-B-UPDATE}
 * (REWRITE) branch". Every case here therefore asserts which persistence operation ran, and the two
 * available discriminators are the reported {@link CategoryBalanceService.Arm} and the IDENTITY of the
 * instance handed to the repository: the create arm constructs a new row, while the update arm can
 * only ever save the very instance the read returned.</p>
 *
 * <p>Assumptions: the reference's two verbs do not survive as two repository calls, so identity is the
 * discriminator rather than the method name. {@code WRITE} at {@code app/cbl/CBTRN02C.cbl:510} and
 * {@code REWRITE} at line 528 both become the inherited {@code save}, which
 * {@link TransactionCategoryBalanceRepository} declares no override of and which the subject's own
 * documentation records as a deliberate collapse. Asserting a distinct method per arm would assert a
 * shape the production code does not have.</p>
 *
 * <h2>What these cases deliberately do not assert</h2>
 *
 * <p>Assumptions: no transaction boundary is expected, arranged or asserted anywhere here, because the
 * production service package holds no transaction annotation on any method -- the boundary belongs to
 * the posting job's chunk, which is where {@code app/cbl/CBTRN02C.cbl:440-442} places the unit of work
 * that keeps the three writes one commit. With the repository supplied as a mock there is no unit of
 * work to break, so such an assertion would pass whatever the production propagation was, and would
 * pass equally if the boundary were removed. An assertion that cannot fail for the reason it was
 * written occupies the place a real check would go.</p>
 *
 * <p>Assumptions: no rendered record image is asserted either. The fifty-byte image that
 * {@code app/cpy/CVTRA01Y.cpy} line 2 declares as {@code RECLN = 50} belongs to
 * {@code com.carddemo.batch.mapper.TransactionCategoryBalanceRecordMapper}, which has its own test;
 * the geometry read below is used to derive the component widths and the declared scale from
 * {@code app/cpy/CVTRA01Y.cpy:7-9} rather than to compose bytes.</p>
 *
 * <p>Trade-offs: these are plain JUnit cases over a directly constructed subject and a mocked
 * repository, so they cannot observe wiring -- whether the bean is declared, whether the datasource
 * resolves its schema search path, or whether a real insert differs from a real update. That is
 * accepted because wiring is observable where it actually exists, in the {@code job} and
 * {@code repository} tiers, and what is bought is a case that runs without a database, a container or
 * a network and fails for exactly one reason: the rule disagreed. The rulings themselves are what this
 * tier owns, and they come from {@code app/cbl/CBTRN02C.cbl:467-542} rather than from any wiring.</p>
 *
 * <p>Refactoring Rationale: three of these rulings were already carried by the
 * {@code CategoryBalanceArms} grouping inside {@code BatchServicesTest}, which asserts the reported arm
 * and the resulting balance for a create, an update and a signed update. Those cases are left exactly
 * where they are rather than moved, because the package charter declines a change whose only motive is
 * filing and which would move landed, passing assertions; it records the same accepted overlap for the
 * generation subject. What this type adds is what the aggregate cannot express in three cases: the
 * saved-instance identity that discriminates the arms, the guard on the initialisation at
 * {@code app/cbl/CBTRN02C.cbl:504}, the seeded-zero shape from the committed fixture, the key
 * provenance at line 469, the entry point that composes the key, the declared scale, and the
 * equivalence of the two arms' arithmetic.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the transaction category balance arms")
class CategoryBalanceServiceTest {

    /**
     * The transcribed geometry of the category-balance record, resolved from the shared registry.
     *
     * <p>Assumptions: this is the migrated equivalent of compiling against the copybook path. It
     * resolves {@code app/cpy/CVTRA01Y.cpy}, whose line 2 declares {@code RECLN = 50} and whose line 5
     * brackets the first three items as the seventeen-byte key that {@code app/jcl/TCATBALF.jcl:40}
     * defines with {@code KEYS(17 0)}.</p>
     */
    // Alternatives Considered: declaring the widths and the scale as literals in this file, which is
    //     one line shorter per constant and needs no registry lookup. Rejected because the layout
    //     would then exist twice and the copy inside a test is the one free to drift; item 4 of the
    //     extension section of tests/README.md requires a layout never be duplicated but kept
    //     single-sourced from app/cpy/, and CopybookLayout is the migrated form of that path. Taking
    //     the values from the registry also means an amendment to the record reaches these cases
    //     rather than leaving them asserting a geometry the production code no longer has.
    private static final CopybookLayout.RecordSpec TCATBAL_LAYOUT = CopybookLayout.layout("TCATBAL");

    /**
     * The declared width of the transaction type code component of the key.
     *
     * <p>Assumptions: {@code TRANCAT-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA01Y.cpy:7}, which the
     * key type refuses a narrower value for, because a short value is stored blank-padded and compares
     * as a different key.</p>
     */
    private static final int TYPE_CD_WIDTH = TCATBAL_LAYOUT.field("TRANCAT-TYPE-CD").length();

    /**
     * The declared width of the transaction category code component of the key.
     *
     * <p>Assumptions: {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy:8}, a numeric
     * picture, so every value the reference can write is four digits with its leading zeros intact.</p>
     */
    private static final int CATEGORY_CD_WIDTH = TCATBAL_LAYOUT.field("TRANCAT-CD").length();

    /**
     * The declared decimal scale of the running balance, taken from the layout rather than assumed.
     *
     * <p>Assumptions: {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:9} carries two
     * decimal places, which is the scale every persisted balance below is asserted to have.</p>
     */
    private static final int BALANCE_SCALE = TCATBAL_LAYOUT.field("TRAN-CAT-BAL").decDigits();

    /**
     * The transaction type code every case keys on, at the width the layout declares.
     *
     * <p>Assumptions: {@code 01} is the value the committed posting fixtures use --
     * {@code tests/golden/posting/boundary_exact_limit/tcatbal.expected} carries it in the two bytes
     * following the eleven-digit account -- so a case here keys the same category the golden masters
     * do.</p>
     */
    private static final String TYPE_CD = "01";

    /**
     * The transaction category code every case keys on, at the width the layout declares.
     *
     * <p>Assumptions: {@code 0001} is the value
     * {@code tests/fixtures/posting/boundary_exact_limit/README.md} section 3 names for the seeded row,
     * carrying the leading zeros that {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy:8}
     * zero-fills.</p>
     */
    private static final String CATEGORY_CD = "0001";

    /**
     * The account identifier the cross-reference resolves to, and the only source of the key's account.
     *
     * <p>Assumptions: account {@code 7} is the account
     * {@code tests/fixtures/posting/boundary_exact_limit/README.md} section 3 maps its card to, and
     * {@code tests/golden/posting/boundary_exact_limit/tcatbal.expected} renders as
     * {@code 00000000007} in the leading eleven bytes of the row.</p>
     */
    private static final long XREF_ACCOUNT_ID = 7L;

    /**
     * A second account identifier, used to prove the key's account follows the cross-reference.
     *
     * <p>Assumptions: it differs from {@link #XREF_ACCOUNT_ID} for one purpose. Because
     * {@code app/cbl/CBTRN02C.cbl:469} moves {@code XREF-ACCT-ID} into the key, posting the very same
     * transaction record through two different cross-reference entries must produce two different
     * keys, and only a second value can show that; with one value the assertion would hold whichever
     * source the account had been taken from.</p>
     */
    private static final long OTHER_XREF_ACCOUNT_ID = 11L;

    /**
     * A numeric the feed record carries that is NOT the account, used to make provenance falsifiable.
     *
     * <p>Assumptions: the daily transaction record declares no account field at all --
     * {@code app/cpy/CVTRA06Y.cpy} carries {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at line 11 and
     * {@code DALYTRAN-CARD-NUM PIC X(16)} at line 15, and nothing else numeric that could be mistaken
     * for one. This value therefore stands in for the wrong source: it differs from
     * {@link #XREF_ACCOUNT_ID}, so a key composed from the record instead of from the cross-reference
     * produces an observably different account.</p>
     */
    private static final long FEED_MERCHANT_ID = 999999999L;

    /**
     * The card number the feed record is presented on and the cross-reference resolves.
     *
     * <p>Assumptions: this is the synthetic card the committed posting fixture uses, retaining every
     * digit because {@code DALYTRAN-CARD-NUM PIC X(16)} is the cross-reference lookup key and is not
     * masked on the way in.</p>
     */
    private static final String CARD_NUMBER = "4859452612877065";

    /** The repository the subject reads the row through and writes it back to, supplied as a mock. */
    @Mock
    private TransactionCategoryBalanceRepository balances;

    /** The subject, rebuilt for every case so no case can observe another's accumulation. */
    private CategoryBalanceService service;

    /**
     * Builds the subject over the mocked repository before each case.
     *
     * <p>Assumptions: the subject is constructed directly rather than injected by a context, because
     * the charter at
     * {@code services/batch-service/src/test/java/com/carddemo/batch/service/package-info.java}
     * settles that no case here starts one. A fresh instance per case is what makes the sequential
     * guard on the initialisation at {@code app/cbl/CBTRN02C.cbl:504} meaningful: when two calls DO
     * share an instance, that sharing is the arrangement of that one case rather than a leak from the
     * harness.</p>
     */
    @BeforeEach
    void buildSubjectOverTheMockedRepository() {
        this.service = new CategoryBalanceService(this.balances);
    }

    /**
     * Builds the whole three-part key for the account, type and category a case is accumulating onto.
     *
     * <p>Assumptions: the components arrive in the order {@code app/cpy/CVTRA01Y.cpy:6-8} declares
     * them, being the eleven-digit account, the two-character type and the four-digit category, which
     * is also the order the key type accepts them in.</p>
     *
     * @param accountId the account identifier the cross-reference resolved, standing in for
     *     {@code XREF-ACCT-ID} as moved at {@code app/cbl/CBTRN02C.cbl:469}
     * @return the composite key, never {@code null}
     */
    private static TransactionCategoryBalanceId key(long accountId) {
        return new TransactionCategoryBalanceId(accountId, TYPE_CD, CATEGORY_CD);
    }

    /**
     * Builds a category-balance row already carrying a balance, standing in for a row the read found.
     *
     * <p>Assumptions: a row built here is what the {@code ADD} at {@code app/cbl/CBTRN02C.cbl:527}
     * accumulates ONTO, so the value passed is the base and not the expected result. The instance
     * matters as much as the number: the {@code REWRITE} at line 528 writes back this very object,
     * which is how a case tells the arms apart.</p>
     *
     * @param balance the balance the row already carries, as an exact decimal string, which may be
     *     {@code 0.00} to reproduce the committed fixture's shape or negative to reproduce a refunded
     *     category
     * @return the seeded row, never {@code null}
     */
    private static TransactionCategoryBalance seededRow(String balance) {
        return new TransactionCategoryBalance(key(XREF_ACCOUNT_ID), new BigDecimal(balance));
    }

    /**
     * Builds the cross-reference entry the posted card resolved to, which supplies the key's account.
     *
     * @param accountId the account identifier the entry maps the card to, being the value
     *     {@code app/cbl/CBTRN02C.cbl:469} moves into the key
     * @return the cross-reference entry, never {@code null}
     */
    private static CardXref resolvedTo(long accountId) {
        return new CardXref(CARD_NUMBER, 1L, accountId);
    }

    /**
     * Builds the daily transaction being posted, carrying the amount and the two code components.
     *
     * <p>Assumptions: the type and category codes on this record are the ones
     * {@code app/cbl/CBTRN02C.cbl:470-471} move into the key, while the account is deliberately absent
     * from it because {@code app/cpy/CVTRA06Y.cpy} declares no account field. The merchant identifier
     * is set to a value that differs from the account so that a key composed from the wrong source is
     * detectable rather than coincidentally correct.</p>
     *
     * @param amount the signed transaction amount, as an exact decimal string, standing in for
     *     {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:10}
     * @return the daily transaction, never {@code null}
     */
    private static DailyTransaction postedTransaction(String amount) {
        LocalDateTime stamp = LocalDateTime.of(2022, 6, 10, 12, 0);
        return new DailyTransaction("0000000000683580", TYPE_CD, CATEGORY_CD, "POS", "GROCERY",
                new BigDecimal(amount), FEED_MERCHANT_ID, "STORE", "SEATTLE", "98101", CARD_NUMBER,
                stamp, stamp);
    }

    /**
     * Captures every row the subject handed to the repository, in the order it handed them over.
     *
     * <p>Assumptions: the capture is on {@code save} because both reference verbs -- {@code WRITE} at
     * {@code app/cbl/CBTRN02C.cbl:510} and {@code REWRITE} at line 528 -- become that one call, so the
     * arm cannot be read from the method invoked and is read from the instance passed instead.
     * Verifying an exact invocation count at the same time is what rules out a second write that a
     * captor alone would silently absorb.</p>
     *
     * @param expectedWrites the number of saves the case arranged, asserted exactly so an extra or a
     *     missing write fails rather than being averaged away
     * @return the saved rows in invocation order, never {@code null}
     */
    private List<TransactionCategoryBalance> capturedWrites(int expectedWrites) {
        ArgumentCaptor<TransactionCategoryBalance> written =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(this.balances, times(expectedWrites)).save(written.capture());
        return written.getAllValues();
    }

    /**
     * The create arm, reached when the keyed read finds no row for the category.
     *
     * <p>Every case here arranges an EMPTY result from the read, which is what
     * {@code app/cbl/CBTRN02C.cbl:474-479} produces through its {@code INVALID KEY} clause and what
     * lines 495 to 499 branch on to reach {@code 2700-A-CREATE-TCATBAL-REC} at line 503.</p>
     */
    @Nested
    @DisplayName("the create arm, when no row exists for the category")
    class CreateArm {

        /**
         * An absent row reaches the create arm and writes exactly once.
         *
         * <p>Pins the branch at {@code app/cbl/CBTRN02C.cbl:495-499} taking the create leg, and the
         * single {@code WRITE} at line 510 rather than a write per arm.</p>
         */
        @Test
        @DisplayName("take the create arm and write once when the read finds nothing")
        void anAbsentRowTakesTheCreateArm() {
            TransactionCategoryBalanceId absent = key(XREF_ACCOUNT_ID);
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(absent, Money.of("125.00"));

            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.CREATED);
            assertThat(outcome.arm()).isNotEqualTo(CategoryBalanceService.Arm.UPDATED);
            // WHY : Assumptions: the lookup is verified on the WHOLE key rather than on any key,
            //       because app/cbl/CBTRN02C.cbl:469-471 composes all three components before the read
            //       at :474 and the reference's record key is the single group item TRAN-CAT-KEY. A
            //       lookup on a partial key would still return nothing here and would still reach this
            //       arm, so without naming the key this case would pass on a subject that keyed the
            //       read wrongly.
            verify(balances).findByIdIs(absent);
            assertThat(capturedWrites(1).get(0).getId()).isEqualTo(absent);
        }

        /**
         * The created row's balance is the transaction amount alone, so it accumulated onto zero.
         *
         * <p>Pins {@code INITIALIZE TRAN-CAT-BAL-RECORD} at {@code app/cbl/CBTRN02C.cbl:504} followed
         * by {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at line 508.</p>
         */
        @Test
        @DisplayName("start the created row at zero so the balance is the amount alone")
        void aCreatedRowStartsFromZero() {
            TransactionCategoryBalanceId absent = key(XREF_ACCOUNT_ID);
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(absent, Money.of("125.00"));

            // WHY : Trade-offs: this asserts the RESULT of an addition onto zero and not an
            //       assignment, and the two are observationally identical on this input. The weaker
            //       reading is accepted deliberately: :508 is an ADD, character for character the same
            //       statement as :527, so the create arm accumulates onto a cleared field rather than
            //       replacing it. Asserting an assignment instead would encode an arithmetic difference
            //       between the arms that the reference does not have, and the sequential case below is
            //       what makes the additive base observable rather than merely stated.
            assertThat(outcome.balance()).isEqualByComparingTo("125.00");
            assertThat(capturedWrites(1).get(0).getBalance()).isEqualByComparingTo("125.00");
        }

        /**
         * A create following an update starts from zero rather than inheriting the earlier balance.
         *
         * <p>This is the direct guard on why {@code INITIALIZE} at {@code app/cbl/CBTRN02C.cbl:504} is
         * load-bearing, and it is the one ruling here that a single-call case cannot reach.</p>
         */
        @Test
        @DisplayName("create from zero even when the preceding call left a balance behind")
        void aCreateFollowingAnUpdateDoesNotInheritThatBalance() {
            // WHY : Assumptions: the initialisation at :504 guards against CROSS-TRANSACTION
            //       contamination and not against an unset field, which is why this case makes two
            //       sequential calls through one subject instead of one call. The reference reads INTO
            //       a working-storage record at :474, and a COBOL read that takes its INVALID KEY path
            //       leaves that record holding the PREVIOUS iteration's bytes -- so without the
            //       initialisation the ADD at :508 would accumulate onto some earlier category's
            //       balance. The first call below leaves 625.00 behind; if the second inherited it the
            //       created row would carry 750.00 rather than 125.00.
            TransactionCategoryBalanceId present = key(XREF_ACCOUNT_ID);
            TransactionCategoryBalanceId absent =
                    new TransactionCategoryBalanceId(XREF_ACCOUNT_ID, "02", CATEGORY_CD);
            TransactionCategoryBalance carried = seededRow("500.00");
            when(balances.findByIdIs(present)).thenReturn(Optional.of(carried));
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome first = service.accumulate(present, Money.of("125.00"));
            CategoryBalanceService.Outcome second = service.accumulate(absent, Money.of("125.00"));

            assertThat(first.arm()).isEqualTo(CategoryBalanceService.Arm.UPDATED);
            assertThat(first.balance()).isEqualByComparingTo("625.00");
            assertThat(second.arm()).isEqualTo(CategoryBalanceService.Arm.CREATED);
            assertThat(second.balance()).isEqualByComparingTo("125.00");

            List<TransactionCategoryBalance> written = capturedWrites(2);
            // WHY : Assumptions: identity is what tells the arms apart here, because both reference
            //       verbs became one save -- WRITE at app/cbl/CBTRN02C.cbl:510 and REWRITE at :528.
            //       The update arm can only save the instance the read at :474 handed back, and the
            //       create arm can only save the one it constructed at :504, so a same-instance check
            //       on the first write and a different-instance check on the second name the arms
            //       without depending on either balance.
            assertThat(written.get(0)).isSameAs(carried);
            assertThat(written.get(1)).isNotSameAs(carried);
            assertThat(written.get(1).getBalance()).isEqualByComparingTo("125.00");
        }

        /**
         * The created row carries the account from the cross-reference and both codes from the record.
         *
         * <p>Pins the three moves at {@code app/cbl/CBTRN02C.cbl:505-507}, and the declared widths at
         * {@code app/cpy/CVTRA01Y.cpy:7-8}.</p>
         */
        @Test
        @DisplayName("carry the account from the cross-reference and the codes from the transaction")
        void aCreatedRowCarriesTheThreeKeyComponents() {
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.empty());

            service.accumulatePostedTransaction(
                    postedTransaction("125.00"), resolvedTo(XREF_ACCOUNT_ID));

            TransactionCategoryBalance created = capturedWrites(1).get(0);
            assertThat(created.getAccountId()).isEqualTo(XREF_ACCOUNT_ID);
            // WHY : Assumptions: the widths are asserted alongside the values because the key type
            //       refuses a narrower component and a padded spelling of one logical code compares as
            //       a DIFFERENT key over the declared-width columns at app/cpy/CVTRA01Y.cpy:7-8. The
            //       expected widths come from the layout registry rather than from literals here, so
            //       an amendment to the record reaches this assertion.
            assertThat(created.getTypeCd()).isEqualTo(TYPE_CD).hasSize(TYPE_CD_WIDTH);
            assertThat(created.getCategoryCd()).isEqualTo(CATEGORY_CD).hasSize(CATEGORY_CD_WIDTH);
        }

        /**
         * A missing row raises nothing, because the reference treats not-found as a clean outcome.
         *
         * <p>Pins {@code IF TCATBALF-STATUS = '00' OR '23'} at {@code app/cbl/CBTRN02C.cbl:481}, where
         * {@code '23'} is record-not-found and only a third status reaches the abend path at lines 489
         * to 492.</p>
         */
        @Test
        @DisplayName("raise nothing when the category has no row yet")
        void anAbsentRowIsANormalOutcome() {
            TransactionCategoryBalanceId absent = key(XREF_ACCOUNT_ID);
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            // WHY : Assumptions: an absent row is what the FIRST transaction of every category
            //       produces, so it is ordinary rather than exceptional -- :481 accepts its status
            //       alongside the success status and :478 turns it into the create flag. Were it raised
            //       here, a first posting run over a freshly loaded table would fail once per category
            //       instead of creating one row per category.
            assertThatCode(() -> service.accumulate(absent, Money.of("125.00")))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The update arm, reached when the keyed read finds the category's row.
     *
     * <p>Every case here arranges a PRESENT result from the read, which lines 495 to 499 of
     * {@code app/cbl/CBTRN02C.cbl} branch on to reach {@code 2700-B-UPDATE-TCATBAL-REC} at line
     * 526.</p>
     */
    @Nested
    @DisplayName("the update arm, when the category already has a row")
    class UpdateArm {

        /**
         * A present row reaches the update arm and writes back the very instance the read returned.
         *
         * <p>Pins the {@code REWRITE} at {@code app/cbl/CBTRN02C.cbl:528}, which rewrites the record
         * the read at line 474 populated rather than a record built afresh.</p>
         */
        @Test
        @DisplayName("take the update arm and write back the row the read returned")
        void aPresentRowTakesTheUpdateArm() {
            TransactionCategoryBalance carried = seededRow("500.00");
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(carried));

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(key(XREF_ACCOUNT_ID), Money.of("125.00"));

            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.UPDATED);
            assertThat(outcome.arm()).isNotEqualTo(CategoryBalanceService.Arm.CREATED);
            // WHY : Assumptions: a same-instance check is the positive proof of this arm, because
            //       REWRITE at app/cbl/CBTRN02C.cbl:528 writes back the record the read at :474
            //       populated. The create arm builds its own row after the INITIALIZE at :504, so it
            //       could never hand this object to the repository, which makes the identity check
            //       something only the update leg can satisfy -- unlike the balance, which both legs
            //       can produce.
            assertThat(capturedWrites(1).get(0)).isSameAs(carried);
        }

        /**
         * The amount is added to the balance the row already carries rather than replacing it.
         *
         * <p>Pins {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at {@code app/cbl/CBTRN02C.cbl:527}.</p>
         */
        @Test
        @DisplayName("add the amount to the balance already carried")
        void theUpdateArmAccumulatesOntoTheExistingBalance() {
            TransactionCategoryBalance carried = seededRow("500.00");
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(carried));

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(key(XREF_ACCOUNT_ID), Money.of("125.00"));

            assertThat(outcome.balance()).isEqualByComparingTo("625.00");
            assertThat(capturedWrites(1).get(0).getBalance()).isEqualByComparingTo("625.00");
        }

        /**
         * A row seeded at zero still takes the update arm, which the balance alone could not show.
         *
         * <p>This is the committed fixture's own shape:
         * {@code tests/fixtures/posting/boundary_exact_limit/README.md} section 3 seeds
         * {@code 00000000007/01/0001} at {@code +0.00} and records that it forces the
         * {@code 2700-B-UPDATE} branch, and
         * {@code tests/golden/posting/boundary_exact_limit/tcatbal.expected} carries the resulting
         * {@code +2065.00} in the eleven bytes following the seventeen-byte key.</p>
         */
        @Test
        @DisplayName("take the update arm for a row seeded at zero, not the create arm")
        void aRowSeededAtZeroStillTakesTheUpdateArm() {
            TransactionCategoryBalance seededAtZero = seededRow("0.00");
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(seededAtZero));

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(key(XREF_ACCOUNT_ID), Money.of("2065.00"));

            // WHY : Assumptions: this is the case the whole identity discipline exists for. A row
            //       seeded at 0.00 and an absent row both leave the balance at the transaction amount,
            //       so the 2065.00 below -- the very value
            //       tests/golden/posting/boundary_exact_limit/tcatbal.expected carries -- is common to
            //       both arms and proves nothing on its own. The arm and the instance are what
            //       establish that the 2700-B-UPDATE branch at app/cbl/CBTRN02C.cbl:526, which
            //       tests/fixtures/posting/boundary_exact_limit/README.md section 3 says this seed
            //       forces, was the branch actually taken.
            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.UPDATED);
            assertThat(outcome.balance()).isEqualByComparingTo("2065.00");
            assertThat(capturedWrites(1).get(0)).isSameAs(seededAtZero);
        }

        /**
         * The update arm leaves the row's key untouched instead of rebuilding it from the record.
         *
         * <p>Pins the absence of the three key moves on this leg: {@code app/cbl/CBTRN02C.cbl:505-507}
         * belong to the create arm alone, because the read at line 474 already supplied the key.</p>
         */
        @Test
        @DisplayName("leave the key the read supplied rather than composing a new one")
        void theUpdateArmDoesNotReassignTheKey() {
            TransactionCategoryBalance carried = seededRow("500.00");
            TransactionCategoryBalanceId keyTheReadSupplied = carried.getId();
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(carried));

            service.accumulatePostedTransaction(
                    postedTransaction("125.00"), resolvedTo(XREF_ACCOUNT_ID));

            // WHY : Assumptions: the entry point necessarily composes a key to look the row UP at
            //       app/cbl/CBTRN02C.cbl:469-471, so an equality check could not tell a supplied key
            //       from a rebuilt one -- the two are equal by construction in this arrangement. A
            //       same-instance check separates them: it holds only while the saved row still carries
            //       the key object the read at :474 supplied, which is what shows the three key moves
            //       at :505-507 stayed on the create arm alone.
            assertThat(capturedWrites(1).get(0).getId()).isSameAs(keyTheReadSupplied);
        }
    }

    /**
     * The rulings both arms share, and the key provenance the dispatcher settles before either runs.
     *
     * <p>These are the properties that are NOT a difference between the arms: one accumulation rule,
     * one signed picture, one declared scale, and one source per key component.</p>
     */
    @Nested
    @DisplayName("the rulings both arms share")
    class SharedRulings {

        /**
         * Both arms leave the identical balance for the identical amount, differing only in the arm.
         *
         * <p>This is the ruling of record for {@code app/cbl/CBTRN02C.cbl:508} against line 527: the
         * two are the same {@code ADD}, so the branch is a persistence concern and not an arithmetic
         * one.</p>
         */
        @Test
        @DisplayName("leave the same balance on either arm for the same amount")
        void bothArmsShareOneAccumulationRule() {
            // WHY : Assumptions: the two arms differ in exactly three respects and the arithmetic is
            //       not among them. The create arm initialises at :504, moves the three key components
            //       at :505-507 and writes at :510; the update arm does none of those and rewrites at
            //       :528. The accumulation is line 508 on one leg and line 527 on the other, and the
            //       two statements are identical, which is what this case pins so that a later reader
            //       does not invent an arithmetic difference between the legs.
            TransactionCategoryBalance seededAtZero = seededRow("0.00");
            TransactionCategoryBalanceId absent =
                    new TransactionCategoryBalanceId(XREF_ACCOUNT_ID, "02", CATEGORY_CD);
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(seededAtZero));
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome updated =
                    service.accumulate(key(XREF_ACCOUNT_ID), Money.of("2065.00"));
            CategoryBalanceService.Outcome created =
                    service.accumulate(absent, Money.of("2065.00"));

            assertThat(updated.balance()).isEqualByComparingTo(created.balance());
            assertThat(updated.arm()).isNotEqualTo(created.arm());

            List<TransactionCategoryBalance> written = capturedWrites(2);
            assertThat(written.get(0)).isSameAs(seededAtZero);
            assertThat(written.get(1)).isNotSameAs(seededAtZero);
        }

        /**
         * A negative amount reduces a balance the row already carries.
         *
         * <p>Pins the unconditional {@code ADD} at {@code app/cbl/CBTRN02C.cbl:527} against the signed
         * picture {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:9}.</p>
         */
        @Test
        @DisplayName("reduce an existing balance when the amount is negative")
        void aNegativeAmountReducesAnExistingBalance() {
            // WHY : Assumptions: a negative balance movement is reachable rather than defensive, and
            //       the layout is asked rather than assumed: the balance field is declared SIGNED at
            //       app/cpy/CVTRA01Y.cpy:9, so a refund is an ordinary value here. Only the ACCOUNT
            //       side of posting inspects the sign, at app/cbl/CBTRN02C.cbl:548, and it does so to
            //       choose between two cycle accumulators rather than to change this arithmetic -- so
            //       neither arm branches on it.
            assertThat(TCATBAL_LAYOUT.field("TRAN-CAT-BAL").signed()).isTrue();

            TransactionCategoryBalance carried = seededRow("100.00");
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(carried));

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(key(XREF_ACCOUNT_ID), Money.of("-40.00"));

            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.UPDATED);
            assertThat(outcome.balance()).isEqualByComparingTo("60.00");
            assertThat(capturedWrites(1).get(0).getBalance()).isEqualByComparingTo("60.00");
        }

        /**
         * A negative amount on an absent row creates a row carrying a negative balance.
         *
         * <p>Pins the create arm's {@code ADD} at {@code app/cbl/CBTRN02C.cbl:508} accumulating a
         * signed amount onto the zero that line 504 established.</p>
         */
        @Test
        @DisplayName("create a negative balance when the first amount in a category is negative")
        void aNegativeAmountOnAnAbsentRowCreatesANegativeBalance() {
            TransactionCategoryBalanceId absent = key(XREF_ACCOUNT_ID);
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome outcome =
                    service.accumulate(absent, Money.of("-40.00"));

            // WHY : Assumptions: the created balance is the amount itself and carries its sign, which
            //       follows from the zero base at :504 rather than from any special handling -- a
            //       refund arriving before any charge in a category is the case that reaches it.
            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.CREATED);
            assertThat(outcome.balance()).isEqualByComparingTo("-40.00");
            assertThat(capturedWrites(1).get(0).getBalance()).isEqualByComparingTo("-40.00");
        }

        /**
         * Every persisted balance carries exactly the scale the copybook declares, on both arms.
         *
         * <p>Pins the two decimal places of {@code TRAN-CAT-BAL PIC S9(09)V99} at
         * {@code app/cpy/CVTRA01Y.cpy:9}, asserted as a scale rather than as a value comparison.</p>
         */
        @Test
        @DisplayName("carry the declared two-place scale on every persisted balance")
        void everyPersistedBalanceCarriesTheDeclaredScale() {
            // WHY : Alternatives Considered: comparing values with a value comparison throughout, which
            //       is what every other case here does deliberately because a comparison of two amounts
            //       is a comparison of value and not of scale. That is exactly why the scale needs one
            //       case of its own: a balance stored at scale 0 or 4 compares EQUAL to the same amount
            //       at scale 2, so no value assertion anywhere else would notice the loss of the two
            //       places that TRAN-CAT-BAL PIC S9(09)V99 declares at app/cpy/CVTRA01Y.cpy:9. This
            //       case therefore reads scale directly, and takes the expected number from that
            //       field's declared decimal digits in the layout registry rather than from a literal.
            TransactionCategoryBalance seededAtZero = seededRow("0.00");
            TransactionCategoryBalanceId absent =
                    new TransactionCategoryBalanceId(XREF_ACCOUNT_ID, "02", CATEGORY_CD);
            when(balances.findByIdIs(key(XREF_ACCOUNT_ID))).thenReturn(Optional.of(seededAtZero));
            when(balances.findByIdIs(absent)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome updated =
                    service.accumulate(key(XREF_ACCOUNT_ID), Money.of("2065.00"));
            CategoryBalanceService.Outcome created =
                    service.accumulate(absent, Money.of("125.00"));

            assertThat(BALANCE_SCALE).isEqualTo(2);
            assertThat(updated.balance().scale()).isEqualTo(BALANCE_SCALE);
            assertThat(created.balance().scale()).isEqualTo(BALANCE_SCALE);

            List<TransactionCategoryBalance> written = capturedWrites(2);
            assertThat(written.get(0).getBalance().scale()).isEqualTo(BALANCE_SCALE);
            assertThat(written.get(1).getBalance().scale()).isEqualTo(BALANCE_SCALE);
        }

        /**
         * The composed key takes its account from the cross-reference and its codes from the record.
         *
         * <p>Pins {@code MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID} at {@code app/cbl/CBTRN02C.cbl:469}
         * against the two moves from the transaction at lines 470 and 471.</p>
         */
        @Test
        @DisplayName("compose the key from the cross-reference account and the transaction codes")
        void theKeyTakesItsAccountFromTheCrossReference() {
            // WHY : Assumptions: the two sources are not interchangeable and the record is not even a
            //       candidate for the account -- app/cpy/CVTRA06Y.cpy declares no account field, only a
            //       card number at line 15 and a merchant identifier at line 11. The merchant
            //       identifier this record carries is set to a value that differs from the account, so
            //       a key composed from the record instead of from the cross-reference is detectable
            //       here rather than coincidentally correct.
            when(balances.findByIdIs(any())).thenReturn(Optional.empty());

            service.accumulatePostedTransaction(
                    postedTransaction("125.00"), resolvedTo(XREF_ACCOUNT_ID));

            ArgumentCaptor<TransactionCategoryBalanceId> lookedUp =
                    ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
            verify(balances).findByIdIs(lookedUp.capture());
            TransactionCategoryBalanceId composed = lookedUp.getValue();

            assertThat(composed.getAccountId()).isEqualTo(XREF_ACCOUNT_ID);
            assertThat(composed.getAccountId()).isNotEqualTo(FEED_MERCHANT_ID);
            assertThat(composed.getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(composed.getCategoryCd()).isEqualTo(CATEGORY_CD);
            assertThat(capturedWrites(1).get(0).getAccountId()).isEqualTo(XREF_ACCOUNT_ID);
        }

        /**
         * Changing only the cross-reference changes the key's account, the record staying identical.
         *
         * <p>This is the falsifying half of {@code app/cbl/CBTRN02C.cbl:469}: with one account value an
         * assertion holds whichever source the account came from, so two are used.</p>
         */
        @Test
        @DisplayName("follow the cross-reference account when only the cross-reference differs")
        void theSameRecordThroughTwoCrossReferencesKeysTwoAccounts() {
            when(balances.findByIdIs(any())).thenReturn(Optional.empty());
            // WHY : Assumptions: ONE transaction instance is posted twice on purpose. Holding the
            //       record constant while varying only the cross-reference means the two keys can
            //       differ for exactly one reason: the XREF-ACCT-ID that app/cbl/CBTRN02C.cbl:469
            //       moves. A subject drawing the account from the record instead would produce two rows
            //       on the same account and fail here -- which a single-account arrangement could never
            //       reveal, since one value agrees with both sources.
            DailyTransaction sameRecordBothTimes = postedTransaction("125.00");

            service.accumulatePostedTransaction(sameRecordBothTimes, resolvedTo(XREF_ACCOUNT_ID));
            service.accumulatePostedTransaction(
                    sameRecordBothTimes, resolvedTo(OTHER_XREF_ACCOUNT_ID));

            List<TransactionCategoryBalance> written = capturedWrites(2);
            assertThat(written.get(0).getAccountId()).isEqualTo(XREF_ACCOUNT_ID);
            assertThat(written.get(1).getAccountId()).isEqualTo(OTHER_XREF_ACCOUNT_ID);
            assertThat(written.get(0).getTypeCd()).isEqualTo(written.get(1).getTypeCd());
            assertThat(written.get(0).getCategoryCd()).isEqualTo(written.get(1).getCategoryCd());
        }
    }
}
