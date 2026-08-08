package com.carddemo.transaction.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the bill-payment conversion boundary: the fixed row it writes and the text it assembles.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link BillPaymentMapper} is the translation point for the bill-payment screen
 * {@code app/cbl/COBIL00C.cbl} migrates, and this class is its executable consumer. The sibling
 * {@code BillPaymentMappingTest} exercises the response record's own factory and never names this
 * mapper, so the values this mapper writes into every payment row, the two identifier paddings, the
 * timestamp form, the assembled acknowledgement sentence and the field-error vocabulary need evidence
 * of their own.</p>
 *
 * <p>The row this mapper appends is built almost entirely from values the reference program holds
 * itself. Lines 220 to 229 of {@code app/cbl/COBIL00C.cbl} are <b>ten consecutive {@code MOVE}
 * statements, of which eight carry hardcoded literals and two carry data read elsewhere</b> -- the
 * balance at line 224 and the cross-referenced card number at line 225. Both framings are stated
 * because either alone misleads: "ten constants" invites a reader to look for ten literals and find
 * eight, while "eight literals" leaves the block's two data moves unaccounted for. A single wrong
 * literal produces a row that persists, balances and reports successfully while being classified as
 * something other than a bill payment for the rest of its life, which is why each is asserted by
 * value rather than by shape.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises outside the test engine, so the type
 * itself accepts no parameter, returns nothing and throws nothing. The inapplicability is stated
 * rather than passed over, because user-specified Rule 1 forbids at its line 39 a docstring that omits
 * parameters, return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Every member below carries its own at-clauses.</p>
 *
 * <h2>Conventions this class inherits rather than restates</h2>
 *
 * <p>Refactoring Rationale: the directory charter {@code package-info.java} beside this file and the
 * subtree charter one package above it already fix the conventions this class follows -- the ruling
 * that the three twenty-six character timestamp forms are never normalised together, the ruling that
 * no field is renamed in this module, the prohibition on exempting anything here from the
 * documentation gate, and the single-sourcing discipline under which a test consumes a layout and
 * never re-declares one. They are cited at the members that depend on them and are not reproduced,
 * because a convention restated in two places is a convention that can come to disagree with itself.
 * The production charter beside {@link BillPaymentMapper} owns the conversion rulings, and this class
 * asserts against them rather than paraphrasing them.</p>
 *
 * <p>Refactoring Rationale: the shared kernel supplies {@link Money}, {@link MoneyModule} and
 * {@link TimestampFormatter}, and no arithmetic, wire form or timestamp pattern is written here. The
 * discipline is the reference suite's own, stated at lines 540 to 542 of {@code tests/README.md} and
 * recorded at its line 549 as a hard review gate: a layout copied into a test drifts from the layout
 * the code uses, and the test then passes against its own copy while the code is wrong.</p>
 */
@DisplayName("BillPaymentMapper: the fixed payment row, the paddings and the assembled text")
class BillPaymentMapperTest {

    /** The mapper under test, which holds no state and needs no collaborator. */
    private final BillPaymentMapper mapper = new BillPaymentMapper();

    /** An eleven-digit account identifier, at the width {@code ACTIDINI PIC X(11)} declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A sixteen-digit card number resolved from the cross-reference, at its declared width. */
    private static final String CARD_NUMBER = "4859452612877065";

    // WHY : Assumptions: this is the card number record 2 of app/data/ASCII/dailytran.txt holds at
    //       one-based bytes 263 to 278, and it is used rather than an invented value because its
    //       LEADING ZERO is the property under test. The record's thirteen named widths place the
    //       field there: 16+2+4+10+100+11+9+50+50+10 is 262, so the card number begins at 263.
    /** The card number from the committed feed, whose leading zero a numeric type would discard. */
    private static final String SEED_CARD_NUMBER = "0927987108636232";

    /** The instant a payment is stamped with in these cases. */
    private static final LocalDateTime PAYMENT_INSTANT =
            LocalDateTime.parse("2022-07-18T10:15:30.123456");

    // WHY : Assumptions: nine integer digits is the whole of what TRAN-AMT PIC S9(09)V99 at line 10 of
    //       app/cpy/CVTRA05Y.cpy declares, so this is the widest amount that record admits and the
    //       upper edge of the boundary the D7 case below walks.
    /** The widest amount the transaction record's own picture admits, at nine integer digits. */
    private static final BigDecimal WIDEST_ROW_AMOUNT = new BigDecimal("999999999.99");

    // WHY : Assumptions: ten integer digits is legal for ACCT-CURR-BAL PIC S9(10)V99 at line 7 of
    //       app/cpy/CVACT01Y.cpy and is one digit wider than the row above admits, which is exactly
    //       the mismatch line 224 of app/cbl/COBIL00C.cbl moves across.
    /** A balance one integer digit wider than the transaction record's amount can hold. */
    private static final BigDecimal OVER_WIDE_BALANCE = new BigDecimal("1000000000.00");

    /**
     * Builds a confirmed payment request.
     *
     * @return a {@link BillPaymentRequest} carrying the account identifier and an affirmative
     *     confirmation
     */
    private static BillPaymentRequest confirmedRequest() {
        return new BillPaymentRequest(ACCOUNT_ID, "Y");
    }

    /**
     * Converts a confirmed payment of the given balance into the ledger row the mapper appends.
     *
     * <p>Assumptions: the transaction identifier and the card number are held constant so that a case
     * varying the balance varies one thing. The balance is taken as an exact amount rather than as a
     * decimal because the mapper's own parameter is the exact type.</p>
     *
     * @param balance the pre-payment balance to pay in full, which becomes the row's amount
     * @return the converted row, never {@code null}
     */
    private Transaction paymentOf(Money balance) {
        return this.mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER, balance, PAYMENT_INSTANT);
    }

    /**
     * Builds a JSON writer carrying the shared money module and nothing else.
     *
     * <p>Refactoring Rationale: the module is registered explicitly rather than obtained from an
     * auto-configured application context. A wire-format contract that holds only because some starter
     * happened to register the module is a contract nobody can rely on the moment a starter changes,
     * so the registration is a statement of this file rather than an inheritance.</p>
     *
     * <p>Alternatives Considered: reaching the identical helper the sibling
     * {@code TransactionMapperTest} declares, so that one writer served both. It is rejected because
     * that helper is private to a class this one does not extend, and sharing it would mean either
     * widening another test's surface or authoring a third holder type -- both of which change files
     * outside the single file this one is. The convention is therefore inherited and the two lines
     * that express it are repeated deliberately.</p>
     *
     * @return a writer with {@link MoneyModule} registered and no other configuration applied
     */
    private static ObjectMapper jsonWriter() {
        return JsonMapper.builder().addModule(new MoneyModule()).build();
    }

    /**
     * Lists the component names of a record type in its own declaration order.
     *
     * <p>Assumptions: {@code getRecordComponents} returns components in declaration order, which is
     * the property that lets a case assert an ORDER and a COUNT rather than only a set. Reading them
     * off the compiled type rather than from a list written here is what keeps a case honest when a
     * component is inserted: a written list would go on passing against the shape it was written
     * for.</p>
     *
     * @param recordType the record type to enumerate; must be a record type
     * @return the component names in declaration order
     */
    private static List<String> componentNames(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Every one of the eight literal moves is written with the reference's own literal.
     *
     * <p>Pins the eight hardcoded moves within the ten-statement block at lines 220 to 229 of
     * {@code app/cbl/COBIL00C.cbl}: the type code at 220, the category at 221, the source at 222, the
     * description at 223, the merchant identifier at 226, its name at 227, its city at 228 and its
     * postal code at 229.</p>
     *
     * <p>Assumptions: the eight are asserted individually rather than by comparing a whole expected
     * row, so a failure names the member that drifted. A whole-row comparison would report one failure
     * for any of the eight and leave a reader to find which.</p>
     *
     * <p>Assumptions: the type code is asserted as the two-character string {@code "02"} and the
     * category as the four-character string {@code "0002"}, and neither is an integer. Line 220 moves
     * a quoted two-character literal into {@code TRAN-TYPE-CD PIC X(02)} at line 6 of
     * {@code app/cpy/CVTRA05Y.cpy}, so its leading zero is data. Line 221 moves the unquoted numeral 2
     * into {@code TRAN-CAT-CD PIC 9(04)} at line 7 of the same copybook, and a four-digit numeric
     * field stores that as {@code 0002}; an integer 2 on the wire would name a category the reference
     * never writes.</p>
     */
    @Test
    @DisplayName("the eight literal moves carry the reference's own literals")
    void theEightLiteralMovesAreTheReferenceLiterals() {
        Transaction payment = paymentOf(Money.of(new BigDecimal("1250.75")));

        assertThat(payment.getTranTypeCd()).isEqualTo("02").hasSize(2);
        assertThat(payment.getTranCatCd()).isEqualTo("0002").hasSize(4);
        assertThat(payment.getTranSource()).isEqualTo("POS TERM");
        assertThat(payment.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(payment.getMerchantId()).isEqualTo(999999999L);
        assertThat(payment.getMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(payment.getMerchantCity()).isEqualTo("N/A");
        assertThat(payment.getMerchantZip()).isEqualTo("N/A");

        assertThat(payment.getTranTypeCd()).isEqualTo(BillPaymentMapper.TRANSACTION_TYPE_CODE);
        assertThat(payment.getTranCatCd()).isEqualTo(BillPaymentMapper.TRANSACTION_CATEGORY_CODE);
        assertThat(payment.getTranSource()).isEqualTo(BillPaymentMapper.TRANSACTION_SOURCE);
        assertThat(payment.getTranDesc()).isEqualTo(BillPaymentMapper.TRANSACTION_DESCRIPTION);
    }

    /**
     * The description and the source keep the interior spacing the reference wrote them with.
     *
     * <p>Pins the description at line 223 and the source at line 222 of
     * {@code app/cbl/COBIL00C.cbl}.</p>
     *
     * <p>Assumptions: the spacing is asserted as its own case because it is the part of a literal a
     * reader is most likely to normalise while believing the value unchanged. The description
     * separates its two words with a space, an ASCII hyphen-minus and a second space, and the source
     * carries exactly one interior space; transformation rule T8 requires a user-visible string to be
     * carried across character for character, so a tidied hyphen or a collapsed space is a change of
     * data rather than of layout.</p>
     */
    @Test
    @DisplayName("the description and source keep their exact interior spacing")
    void theDescriptionAndSourceKeepTheirInteriorSpacing() {
        Transaction payment = paymentOf(Money.of(new BigDecimal("1.00")));

        assertThat(payment.getTranDesc())
                .as("line 223 spaces an ASCII hyphen-minus between the two words")
                .isEqualTo("BILL PAYMENT" + " " + "-" + " " + "ONLINE")
                .doesNotContain("  ");
        assertThat(payment.getTranSource())
                .as("line 222 carries one interior space and no trailing padding")
                .isEqualTo("POS TERM")
                .hasSize(8);
    }

    /**
     * The block's two data moves carry their source values rather than literals.
     *
     * <p>Pins the two non-literal statements inside the ten-statement block: line 224 of
     * {@code app/cbl/COBIL00C.cbl} moves the account balance into the amount, and line 225 moves the
     * card number reached through the cross-reference into the row's card number.</p>
     *
     * <p>Assumptions: these two are asserted as a case of their own so that the block's composition is
     * recorded as eight literals PLUS two data moves rather than as ten of one kind. A reader who
     * inherited only the literal case above would have no evidence that the remaining two statements
     * carry anything at all, and a mapper that wrote a constant into either would satisfy every other
     * case in this class.</p>
     */
    @Test
    @DisplayName("the two data moves carry the balance and the cross-referenced card number")
    void theTwoDataMovesCarryTheirSourceValues() {
        Money balance = Money.of(new BigDecimal("1250.75"));

        Transaction payment = this.mapper.toEntity(confirmedRequest(), "42", SEED_CARD_NUMBER, balance,
                PAYMENT_INSTANT);

        assertThat(payment.getTranAmt())
                .as("line 224 moves ACCT-CURR-BAL into TRAN-AMT")
                .isEqualByComparingTo(balance.amount());
        assertThat(payment.getCardNum())
                .as("line 225 moves XREF-CARD-NUM into TRAN-CARD-NUM")
                .isEqualTo(SEED_CARD_NUMBER);
    }

    /**
     * The amount written is the whole balance standing before the payment, exact at scale two.
     *
     * <p>Assumptions: a bill payment on this screen pays the whole balance rather than a requested
     * figure -- the request carries no amount component at all -- so the amount is the balance, and the
     * scale is asserted because every comparison downstream is an exact-decimal comparison.</p>
     */
    @Test
    @DisplayName("the amount is the whole pre-payment balance at scale two")
    void theAmountIsTheWholePrePaymentBalance() {
        Transaction payment = paymentOf(Money.of(new BigDecimal("1250.7")));

        assertThat(payment.getTranAmt()).isEqualByComparingTo(new BigDecimal("1250.70"));
        assertThat(payment.getTranAmt().scale()).isEqualTo(Money.SCALE);
    }

    /**
     * The balance arrives as a scalar amount, so no other context's record crosses this boundary.
     *
     * <p>Pins the parameter list of the conversion that builds the row for lines 220 to 229 of
     * {@code app/cbl/COBIL00C.cbl}, whose line 224 reads {@code ACCT-CURR-BAL} -- a field declared at
     * line 7 of {@code app/cpy/CVACT01Y.cpy} and owned by the account context rather than by this
     * one.</p>
     *
     * <p>Alternatives Considered: accepting the account record itself, so that the balance could be
     * read off it rather than handed in. It is rejected because that record is the account context's
     * own persistence model, and the layering rules this module runs against its own classes forbid
     * importing a domain package belonging to another service; those rules are themselves a test, so
     * such an import fails the build rather than a review. A second shape -- having this conversion
     * fetch the account for itself -- is rejected as well, because it would put a remote call inside a
     * translation boundary and leave every conversion able to fail for a reason unrelated to
     * converting anything. A scalar carries exactly the one figure line 224 moves and nothing else.</p>
     *
     * <p>Assumptions: the constraint is asserted over the DECLARED parameter types rather than by
     * searching this file for an import, because an absent import shows only that this test reaches no
     * other context, whereas the signature is what decides whether any caller could be made to. The
     * permitted set is stated positively -- the shared kernel, this service, or the platform library --
     * since naming the prohibited package here would place the very reference the rule forbids inside
     * the case that asserts the prohibition.</p>
     *
     * @throws NoSuchMethodException if the conversion no longer declares this signature, which is
     *     itself the drift this case exists to report
     */
    @Test
    @DisplayName("the balance arrives as a scalar amount, not as another context's record")
    void theBalanceArrivesAsAScalarAmount() throws NoSuchMethodException {
        Method conversion = BillPaymentMapper.class.getMethod("toEntity", BillPaymentRequest.class,
                String.class, String.class, Money.class, LocalDateTime.class);

        assertThat(conversion.getParameterTypes()[3])
                .as("line 224 needs one figure, so one figure is what crosses")
                .isEqualTo(Money.class);

        for (Class<?> parameter : conversion.getParameterTypes()) {
            assertThat(parameter.getName())
                    .as("a parameter type outside these three roots would be a foreign domain model")
                    .matches("^(com\\.carddemo\\.common\\.|com\\.carddemo\\.transaction\\.|java\\.).*");
        }
    }

    /**
     * The balance the acknowledgement reports is the pre-payment figure and the amount paid at once.
     *
     * <p>Pins the statement sequence that makes those three the same number. Line 193 of
     * {@code app/cbl/COBIL00C.cbl} captures {@code ACCT-CURR-BAL} into working storage and line 194
     * moves it into the screen's balance field; line 224 moves the SAME field into the row's amount;
     * line 233 writes the row; and only at line 234 is the balance reduced by that amount, with line
     * 235 rewriting the account. Line 242 then sends the screen, and nothing between 194 and 242
     * repopulates the balance field.</p>
     *
     * <p>Assumptions: the figure the operator reads back is therefore the balance as it stood BEFORE
     * the payment, and because a payment on this screen is always paid in full it is simultaneously the
     * amount paid. That is why the response shape publishes no post-payment balance: the reference
     * never renders one. The identity is asserted across both conversions in one case rather than as
     * two independent equalities, because the property worth guarding is that the two agree -- a
     * mapper that reported a correct figure computed independently of the row would satisfy two
     * separate assertions and still be able to drift.</p>
     *
     * <p>Alternatives Considered: asserting this against the response record's own factory, which is
     * where the sibling {@code BillPaymentMappingTest} pins the pay-in-full semantic. It is rejected as
     * a duplicate: that case fixes what the record publishes, whereas this one fixes that the mapper
     * reads the reported balance back out of the row it just wrote, which is the only place the two can
     * be made to disagree.</p>
     */
    @Test
    @DisplayName("the reported balance is the pre-payment figure and equals the amount paid")
    void theReportedBalanceIsThePrePaymentFigureAndTheAmountPaid() {
        Money prePaymentBalance = Money.of(new BigDecimal("1250.75"));
        Transaction payment = paymentOf(prePaymentBalance);

        BillPaymentResponse acknowledgement = this.mapper.toResponse(confirmedRequest(), payment, null);

        assertThat(acknowledgement.currentBalance().amount())
                .as("the figure captured at lines 193 and 194, before the reduction at line 234")
                .isEqualByComparingTo(prePaymentBalance.amount());
        assertThat(acknowledgement.currentBalance().amount())
                .as("and the same figure line 224 moved into the amount, so the balance IS the"
                        + " amount paid")
                .isEqualByComparingTo(payment.getTranAmt());
        assertThat(acknowledgement.paid())
                .as("the record's posted factory fixes the discriminator on this path")
                .isTrue();
    }

    /**
     * A balance one integer digit wider than the row admits is refused rather than narrowed.
     *
     * <p>Pins the width mismatch line 224 of {@code app/cbl/COBIL00C.cbl} moves across:
     * {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} declares ten
     * integer digits, while {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy} declares nine.</p>
     *
     * <p>Trade-offs: the baseline moves a ten-integer-digit balance into a nine-integer-digit amount
     * and the tenth digit is dropped with no diagnostic, so a balance of one thousand million posts as
     * a payment of zero; the Java refuses the assignment instead, and the divergence is documented
     * here and in the traceability record rather than being introduced silently. The compromise
     * accepted is that the two systems answer differently for an input the seed data does not contain
     * -- every amount in {@code app/data/ASCII/dailytran.txt} is far below the boundary -- and that a
     * caller reaching it receives an error where the reference would have written a wrong number. A
     * refusal that names the assignment was preferred to reproducing a silent narrowing, because a
     * wrong money figure that no downstream check can detect is the one outcome a payment path must
     * not have.</p>
     *
     * <p>Assumptions: both edges are exercised, because a bound is only demonstrated by the pair. The
     * widest amount the row admits is accepted unchanged, and one cent past its integer width is
     * refused; the refusing type is the shared kernel's, which reports an arithmetic condition rather
     * than an argument fault because the value is well-formed money that the narrower picture cannot
     * express.</p>
     */
    @Test
    @DisplayName("a balance wider than the row's amount is refused rather than narrowed")
    void anOverWideBalanceIsRefusedRatherThanNarrowed() {
        Transaction widest = paymentOf(Money.of(WIDEST_ROW_AMOUNT));

        assertThat(widest.getTranAmt())
                .as("nine integer digits is the whole of what CVTRA05Y line 10 declares")
                .isEqualByComparingTo(WIDEST_ROW_AMOUNT);

        // WHY : Assumptions: the over-wide value is built as money FIRST, so the case proves the
        //       refusal happens where the reference's line 224 narrows and not earlier. The shared
        //       kernel admits ten integer digits, which is exactly the account balance's declared
        //       width, so this value is legal money and illegal only as a transaction amount.
        Money legalBalanceIllegalAmount = Money.of(OVER_WIDE_BALANCE);

        assertThat(legalBalanceIllegalAmount.amount()).isEqualByComparingTo(OVER_WIDE_BALANCE);
        assertThatThrownBy(() -> paymentOf(legalBalanceIllegalAmount))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * Both identifiers are zero-padded on the left to their declared record widths.
     *
     * <p>Assumptions: the padding side is the whole of the difference between identifier 42 and
     * identifier 4,200,000,000,000,000, and both members are fixed-width record keys, so a
     * right-padded value would still be sixteen characters and would name a different row.</p>
     */
    @Test
    @DisplayName("both identifiers are zero-padded on the left to their declared widths")
    void bothIdentifiersAreLeftPadded() {
        Transaction payment = this.mapper.toEntity(confirmedRequest(), "42", "7065",
                Money.of(new BigDecimal("1.00")), PAYMENT_INSTANT);

        assertThat(payment.getTranId()).isEqualTo("0000000000000042")
                .hasSize(BillPaymentMapper.IDENTIFIER_WIDTH);
        assertThat(payment.getCardNum()).isEqualTo("0000000000007065")
                .hasSize(BillPaymentMapper.IDENTIFIER_WIDTH);
    }

    /**
     * The card number survives as digits with its leading zero intact.
     *
     * <p>Pins the move at line 225 of {@code app/cbl/COBIL00C.cbl} into
     * {@code TRAN-CARD-NUM PIC X(16)} at line 15 of {@code app/cpy/CVTRA05Y.cpy}.</p>
     *
     * <p>Assumptions: the card number is carried as text and never as a number. Record 2 of
     * {@code app/data/ASCII/dailytran.txt} holds {@code 0927987108636232} at one-based bytes 263 to
     * 278, so a numeric member would render it fifteen digits long and address a different card, and
     * the reference itself declares the field as character with a numeric redefinition rather than the
     * other way round -- {@code CC-CARD-NUM PIC X(16)} at line 37 of {@code app/cpy/CVCRD01Y.cpy} with
     * {@code CC-CARD-NUM-N REDEFINES} it as {@code PIC 9(16)} at line 39. The value is asserted
     * unchanged rather than merely non-empty, because a padding routine that stripped and re-padded it
     * would produce the same length and a different number.</p>
     */
    @Test
    @DisplayName("the card number keeps its leading zero and stays sixteen digits")
    void theCardNumberKeepsItsLeadingZero() {
        Transaction payment = this.mapper.toEntity(confirmedRequest(), "42", SEED_CARD_NUMBER,
                Money.of(new BigDecimal("1.00")), PAYMENT_INSTANT);

        assertThat(payment.getCardNum())
                .isEqualTo(SEED_CARD_NUMBER)
                .startsWith("0")
                .hasSize(BillPaymentMapper.IDENTIFIER_WIDTH)
                .containsOnlyDigits();
    }

    /**
     * The conversion does not branch on the confirmation character, whichever arm it belongs to.
     *
     * <p>Pins the four-way {@code EVALUATE CONFIRMI} at lines 173 to 191 of
     * {@code app/cbl/COBIL00C.cbl} as a decision that belongs to the caller and not to this
     * conversion.</p>
     *
     * <p>Assumptions: the reference decides at those lines whether a payment happens at all --
     * affirmative at 174 and 175, declining at 178 and 179, unfilled at 182 and 183, anything else at
     * 185 -- and only the affirmative arm reaches the block at 220 to 229. This mapper is therefore
     * asked to convert only after that decision has been taken, which is why {@code toEntity} never
     * reads the confirmation component at all and {@code toResponse} reads only the account
     * identifier. The case asserts the invariance directly: every spelling produces the same row, so a
     * future edit that made the conversion inspect the character would fail here rather than move a
     * screen decision into a translation boundary where no screen test would see it.</p>
     *
     * @param confirmation the confirmation character to submit, one spelling from one arm of the
     *     four-way evaluation
     */
    @ParameterizedTest
    @ValueSource(strings = {"Y", "y", "N", "n", "X", "1"})
    @DisplayName("the conversion does not branch on the confirmation character")
    void theConversionDoesNotBranchOnTheConfirmationCharacter(String confirmation) {
        Transaction payment = this.mapper.toEntity(new BillPaymentRequest(ACCOUNT_ID, confirmation),
                "42", CARD_NUMBER, Money.of(new BigDecimal("1250.75")), PAYMENT_INSTANT);

        assertThat(payment.getTranTypeCd()).isEqualTo(BillPaymentMapper.TRANSACTION_TYPE_CODE);
        assertThat(payment.getTranDesc()).isEqualTo(BillPaymentMapper.TRANSACTION_DESCRIPTION);
        assertThat(payment.getTranAmt()).isEqualByComparingTo(new BigDecimal("1250.75"));
        assertThat(this.mapper.toResponse(new BillPaymentRequest(ACCOUNT_ID, confirmation), payment,
                null).accountId()).isEqualTo(ACCOUNT_ID);
    }

    /**
     * The three sentences the four-way evaluation emits are carried across character for character.
     *
     * <p>Pins the never-supplied identifier answer at line 161, the catch-all answer at line 187 and
     * the prompt the unfilled arm reaches at line 237, all of
     * {@code app/cbl/COBIL00C.cbl}.</p>
     *
     * <p>Assumptions: each is asserted as a whole literal rather than by a fragment, because the parts
     * a reader normalises are exactly the parts a fragment match would not see. The first capitalises
     * {@code NOT} and closes with three dots and no space before them; the second parenthesises the two
     * accepted values as {@code (Y/N)}; the third is the sentence the screen shows when the
     * confirmation has not been filled in, which is a prompt rather than a complaint. Transformation
     * rule T8 requires a user-visible string to be reproduced exactly, and the reference suite states
     * the same discipline at lines 555 to 557 of {@code tests/README.md}: a test encodes the
     * specification as documented and does not redefine it.</p>
     *
     * <p>Assumptions: the unfilled arm is also shown to be constructible and convertible, because on
     * this screen an unfilled confirmation is not an error -- it is what makes the screen ask for one --
     * so its two spellings, blank and a low value, must reach the conversion rather than be refused by
     * it.</p>
     */
    @Test
    @DisplayName("the three sentences of the four-way evaluation are verbatim")
    void theThreeSentencesOfTheFourWayEvaluationAreVerbatim() {
        assertThat(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY)
                .as("line 161, with NOT capitalised and no space before the ellipsis")
                .isEqualTo("Acct ID can NOT be empty...");
        assertThat(BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION)
                .as("line 187, with the accepted values parenthesised")
                .isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT)
                .as("line 237, the prompt the unfilled arm at lines 182 and 183 reaches")
                .isEqualTo("Confirm to make a bill payment...");

        for (String unfilled : List.of(" ", "\u0000")) {
            assertThat(this.mapper.toEntity(new BillPaymentRequest(ACCOUNT_ID, unfilled), "42",
                    CARD_NUMBER, Money.of(new BigDecimal("1.00")), PAYMENT_INSTANT).getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("1.00"));
        }
    }

    /**
     * The request publishes the screen's two enterable fields and nothing else.
     *
     * <p>Pins the two unprotected fields of the bill-payment map: {@code ACTIDINI PIC X(11)} at line 60
     * of {@code app/cpy-bms/COBIL00.CPY} and {@code CONFIRMI PIC X(1)} at its line 72.</p>
     *
     * <p>Assumptions: four absences are asserted alongside the two present components, because an
     * absence has no declaration to read and would otherwise be indistinguishable from an omission.
     * There is no amount component, since line 224 of {@code app/cbl/COBIL00C.cbl} always pays the
     * whole balance and the screen offers no field to type one into; no money component at all follows
     * from the same line; and no timestamp component, because the instant is taken inside the program at
     * line 230 rather than submitted, so a client-supplied stamp would let a caller choose when its own
     * payment was recorded.</p>
     *
     * <p>Assumptions: there is likewise no paging component. The screen does browse the transaction
     * file at lines 213 to 215, but that browse is a single highest-key probe used to mint the next
     * identifier -- line 212 moves high values into the key before it, and lines 216 and 217 read the
     * key back and add one after it -- rather than a cursor a client drives. The two page members the
     * program declares at lines 67 and 68, {@code CDEMO-CB00-PAGE-NUM} and
     * {@code CDEMO-CB00-NEXT-PAGE-FLG}, are referenced at no other line of its 572, so no page state
     * crosses this boundary and none is published. The distinction is recorded because the presence of
     * browse verbs invites the opposite conclusion.</p>
     */
    @Test
    @DisplayName("the request publishes the two enterable fields and no amount, money, stamp or page")
    void theRequestPublishesExactlyTheTwoEnterableFields() {
        List<String> components = componentNames(BillPaymentRequest.class);

        assertThat(components)
                .as("ACTIDINI at COBIL00.CPY line 60 and CONFIRMI at its line 72, in that order")
                .containsExactly("accountId", "confirmation");

        assertThat(components).noneMatch(name -> name.toLowerCase().contains("amount"))
                .noneMatch(name -> name.toLowerCase().contains("balance"))
                .noneMatch(name -> name.toLowerCase().contains("money"))
                .noneMatch(name -> name.toLowerCase().contains("timestamp"))
                .noneMatch(name -> name.toLowerCase().contains("page"))
                .noneMatch(name -> name.toLowerCase().contains("cursor"));

        // WHY : Assumptions: the component TYPES are swept as well as the names, because a money or a
        //       temporal component admitted under some other name would pass a name sweep while
        //       putting the amount or the instant back under a client's control.
        for (RecordComponent component : BillPaymentRequest.class.getRecordComponents()) {
            assertThat(component.getType()).isEqualTo(String.class);
        }
    }

    /**
     * The acknowledgement publishes five components, and no card number or verification value.
     *
     * <p>Assumptions: the shape is asserted by count and order rather than field by field, so that a
     * sixth component cannot be introduced without a failure here. The five are the row's identifier,
     * the echoed account identifier, the balance, the paid discriminator and the nullable message; the
     * message is the seventy-five character {@code CCARD-RETURN-MSG} form at line 29 of
     * {@code app/cpy/CVCRD01Y.cpy}, whose low-values condition at line 30 is what makes its absence
     * representable.</p>
     *
     * <p>Assumptions: there is no post-payment balance component, for the reason the pre-payment case
     * above records from lines 193, 194, 224, 234 and 242 -- the reference renders the figure it
     * captured before the reduction and never renders the reduced one.</p>
     *
     * <p>Assumptions: no card number reaches this shape, so the masking this package applies where a
     * card does cross a response has no site here at all, and the absence is asserted rather than a
     * masked form. The card number line 225 supplies is written into the ledger row, where it is
     * storage rather than a response, and is carried there in full. No card verification value is read,
     * held or published by any member of this conversion, which is why the sweep names both.</p>
     */
    @Test
    @DisplayName("the acknowledgement publishes five components and no card number or CVV")
    void theAcknowledgementPublishesFiveComponentsAndNoCard() {
        List<String> components = componentNames(BillPaymentResponse.class);

        assertThat(components).containsExactly("transactionId", "accountId", "currentBalance", "paid",
                "returnMessage");

        assertThat(components).noneMatch(name -> name.toLowerCase().contains("card"))
                .noneMatch(name -> name.toLowerCase().contains("cvv"))
                .noneMatch(name -> name.toLowerCase().contains("pan"))
                .noneMatch(name -> name.toLowerCase().contains("newbalance"));

        Transaction payment = paymentOf(Money.of(new BigDecimal("1250.75")));
        String serialised = jsonWriter()
                .writeValueAsString(this.mapper.toResponse(confirmedRequest(), payment, null));

        assertThat(serialised)
                .as("the card number written into the row at line 225 does not reach the body")
                .doesNotContain(CARD_NUMBER)
                .doesNotContain("cardNumber")
                .doesNotContain("cvv");
    }

    /**
     * The twenty padding bytes of the record reach no component of either shape.
     *
     * <p>Pins the drop of {@code 05 FILLER PIC X(20)} at line 18 of {@code app/cpy/CVTRA05Y.cpy},
     * whose twenty bytes occupy one-based positions 331 to 350 and are blank in record 2 of
     * {@code app/data/ASCII/dailytran.txt}.</p>
     *
     * <p>Assumptions: those twenty bytes pad the record to the length its line 2 header declares rather
     * than carrying a value, so the target is narrower than the source by exactly that much and the
     * shortfall is accounted for here rather than left for a reader to notice. The arithmetic is closed
     * in the same case, because the drop is only demonstrably deliberate if it does: a reader
     * reconciling a 350-byte record against a thirteen-member entity needs the twenty bytes named. The
     * sweep covers the request and the acknowledgement together, since a padding component on either
     * would reach a client or a log line.</p>
     */
    @Test
    @DisplayName("the twenty padding bytes reach no component of either shape")
    void theTwentyPaddingBytesReachNoComponent() {
        int[] namedWidths = {16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26};
        int mapped = Arrays.stream(namedWidths).sum();

        assertThat(namedWidths).as("CVTRA05Y lines 5 to 17 declare thirteen named fields").hasSize(13);
        assertThat(mapped).as("those thirteen widths sum to 330 bytes").isEqualTo(330);
        assertThat(mapped + 20)
                .as("the 20 bytes of the FILLER at line 18 complete the 350 its line 2 declares")
                .isEqualTo(350);

        List<String> published = new ArrayList<>(componentNames(BillPaymentRequest.class));
        published.addAll(componentNames(BillPaymentResponse.class));

        assertThat(published).noneMatch(name -> name.toLowerCase().contains("filler"))
                .noneMatch(name -> name.toLowerCase().contains("padding"));
    }

    /**
     * No name this conversion carries is renamed from the layout it came out of.
     *
     * <p>Assumptions: the absence of a spelling change is a decision rather than an oversight, and it
     * is asserted because a reader arriving from a neighbouring service will expect one. The wider
     * migration does carry three misspelled reference names across under different target spellings,
     * and none of the three is reachable from here: the account and card expiration dates belong to the
     * account and card contexts, and the merchant category code to the authorization context. The two
     * layouts this conversion reads are {@code app/cpy/CVTRA05Y.cpy} and
     * {@code app/cpy-bms/COBIL00.CPY}, and neither declares any of them, so there is nothing here to
     * rename. The batch program shows the reference spelling standing exactly as it always has where it
     * does occur: line 414 of {@code app/cbl/CBTRN02C.cbl} reads {@code ACCT-EXPIRAION-DATE} as
     * written, and this migration reads it the same way.</p>
     *
     * <p>Assumptions: a rename introduced here for symmetry with those services would invent a
     * divergence from the reference layout in the one package whose task is to absorb such differences
     * rather than create them, which is why the sweep asserts those other target spellings are ABSENT
     * rather than present.</p>
     */
    @Test
    @DisplayName("no component name is renamed from its source layout")
    void noComponentNameIsRenamedFromItsSourceLayout() {
        List<String> published = new ArrayList<>(componentNames(BillPaymentRequest.class));
        published.addAll(componentNames(BillPaymentResponse.class));

        assertThat(published).noneMatch(name -> name.toLowerCase().contains("expiration"))
                .noneMatch(name -> name.toLowerCase().contains("expiraion"))
                .noneMatch(name -> name.toLowerCase().contains("category"))
                .noneMatch(name -> name.toLowerCase().contains("catagory"));

        Transaction payment = paymentOf(Money.of(new BigDecimal("1.00")));

        assertThat(payment.getTranCatCd())
                .as("the category the reference writes at line 221, under the copybook's own name")
                .isEqualTo(BillPaymentMapper.TRANSACTION_CATEGORY_CODE);
    }

    /**
     * One reduced instant is written to BOTH stamps, which are therefore equal.
     *
     * <p>Pins the single statement with two receiving fields at lines 231 and 232 of
     * {@code app/cbl/COBIL00C.cbl}, fed by the paragraph at its lines 249 to 267.</p>
     *
     * <p>Assumptions: equality of the two members is the property, and it is what distinguishes this
     * screen from the two other paths that write the same pair. This class asserts THAT form and no
     * other: the batch posting path passes an incoming originating stamp through and mints only the
     * processing one, so its two members differ, and the transaction-add screen moves two ten-byte
     * screen dates into two twenty-six byte fields, so it carries a date and no time; both belong to
     * the sibling {@code TransactionMapperTest} and neither is claimed here. Folding any two of the
     * three together would pass against an implementation that had lost the distinction, and nothing in
     * the build would report it, which is the ruling the directory charter records.</p>
     *
     * <p>Assumptions: reducing the instant ONCE rather than per member is what makes the two equal in
     * their sub-second digits as well as their first twenty, so a value carrying nanosecond precision
     * is offered here rather than one already at microsecond resolution.</p>
     */
    @Test
    @DisplayName("one reduced instant is written to both stamps")
    void oneInstantIsWrittenToBothStamps() {
        Transaction payment = this.mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(new BigDecimal("1.00")),
                LocalDateTime.parse("2022-07-18T10:15:30.123456789"));

        assertThat(payment.getOrigTs()).isEqualTo(payment.getProcTs());
        assertThat(payment.getOrigTs())
                .as("reduced to the resolution the 26-character contract carries")
                .isEqualTo(LocalDateTime.parse("2022-07-18T10:15:30.123456"));
    }

    /**
     * The rendered stamp carries the separators the record's untouched padding holds.
     *
     * <p>Pins the twenty-six character group at lines 42 to 55 of {@code app/cpy/CSDAT01Y.cpy} as the
     * paragraph at lines 249 to 267 of {@code app/cbl/COBIL00C.cbl} assembles it.</p>
     *
     * <p>Assumptions: two of the form's characters are never written by any statement, and that is why
     * they are asserted by position. Line 264 writes positions 1 to 10 and line 265 writes 12 to 19, so
     * position 11 and position 20 are only ever the {@code FILLER} values their declarations carry --
     * a space at line 48 and a period at line 54 -- because {@code INITIALIZE} at line 263 leaves
     * {@code FILLER} untouched. A renderer that emitted the digits and assumed the punctuation would
     * produce a value blank at both positions while looking plausible in a log.</p>
     *
     * <p>Assumptions: the tail is six zeros because line 266 moves zeros into the microsecond member
     * rather than a reading, so a bill payment's sub-second component is invariably zero however
     * precise the instant handed in. The width is asserted against the shared formatter's own published
     * length rather than a number written here, and the thirteen declared widths sum to it exactly:
     * 4+1+2+1+2+1+2+1+2+1+2+1+6.</p>
     */
    @Test
    @DisplayName("the rendered stamp holds a space at position 11, a period at 20 and a zero tail")
    void theRenderedStampHoldsTheFillerSeparatorsAndAZeroTail() {
        // WHY : Assumptions: the instant is offered at whole-second resolution because the reference
        //       never reads a sub-second value at all -- line 266 supplies zeros -- so this is the
        //       shape the reference actually produces rather than one reduced to it.
        String rendered = this.mapper.renderTimestamp(LocalDateTime.parse("2022-06-10T19:27:53"));

        assertThat(rendered).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(rendered.charAt(10))
                .as("position 11 is the FILLER space CSDAT01Y line 48 declares and nothing writes")
                .isEqualTo(' ');
        assertThat(rendered.charAt(19))
                .as("position 20 is the FILLER period CSDAT01Y line 54 declares and nothing writes")
                .isEqualTo('.');
        assertThat(rendered)
                .as("line 266 moves zeros into the microsecond member")
                .endsWith(".000000");

        // WHY : Assumptions: the whole form is compared against the committed feed rather than only its
        //       separators, because record 2 of app/data/ASCII/dailytran.txt holds this exact
        //       twenty-six byte value at one-based bytes 279 to 304, which makes the expectation an
        //       observation of the reference data rather than a restatement of the pattern above.
        assertThat(rendered).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(Arrays.stream(new int[] {4, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 6}).sum())
                .as("the thirteen widths CSDAT01Y lines 43 to 55 declare sum to the contract length")
                .isEqualTo(TimestampFormatter.TIMESTAMP_LENGTH);
    }

    /**
     * The rendered timestamp is the contract's twenty-six character form and agrees with the row.
     *
     * <p>Assumptions: the rendering and the stored value are asserted TOGETHER, because the failure
     * worth guarding is not a wrong rendering but a rendering that disagrees with the row written from
     * the same instant. Both go through the shared formatter for exactly that reason, and a locally
     * declared pattern is what would break the agreement while rendering something plausible.</p>
     */
    @Test
    @DisplayName("the rendered timestamp is the 26-character form and matches the stored value")
    void theRenderedTimestampMatchesTheStoredValue() {
        String rendered = this.mapper.renderTimestamp(
                LocalDateTime.parse("2022-07-18T10:15:30.123456789"));
        Transaction payment = this.mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(BigDecimal.ONE), LocalDateTime.parse("2022-07-18T10:15:30.123456789"));

        assertThat(rendered).isEqualTo("2022-07-18 10:15:30.123456")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(TimestampFormatter.parse(rendered)).isEqualTo(payment.getProcTs());
    }

    /**
     * The balance leaves the acknowledgement as a quoted JSON string.
     *
     * <p>Pins {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} as it
     * reaches the wire, in the shape line 194 of {@code app/cbl/COBIL00C.cbl} renders into
     * {@code CURBALI PIC X(14)} at line 66 of {@code app/cpy-bms/COBIL00.CPY} -- a sign, ten integer
     * digits, a point and two decimals.</p>
     *
     * <p>Alternatives Considered: transporting the balance as a JSON number, which is the obvious
     * alternative and is rejected. Most clients parse a JSON number into an IEEE-754 binary
     * floating-point value, and a figure such as 1250.75 acquires a representation error the moment it
     * is parsed that way, so the exactness the {@code NUMERIC} column and the scale-2 decimal both
     * maintain would be discarded at the last hop, where nothing downstream could detect the loss. The
     * cost accepted is that every client performs an explicit parse.</p>
     *
     * <p>Trade-offs: the assertion is made against the RAW serialised text and never against a
     * re-parsed tree. Re-parsing would answer what the value is rather than how it was written, so the
     * quotation marks -- the whole property under test -- would be consumed by the parser before the
     * assertion saw them, and a bare number carrying the right digits would pass. What is given up is
     * that the assertion is coupled to the writer's spacing; what it buys is that it can fail for the
     * one defect the quoted form exists to prevent.</p>
     *
     * <p>Assumptions: zero and a negative figure are asserted alongside an ordinary one because each
     * fails differently. Zero is the value most likely to lose its scale, and a shape emitting
     * {@code "0"} rather than {@code "0.00"} would still be a string and would still parse to the right
     * number, so a quotation-mark test alone would not see it. A negative figure is admissible because
     * the picture is SIGNED, and the exponent form is excluded because a decimal carrying one would
     * round-trip through a parser and still be unreadable to a fixed-width consumer.</p>
     *
     * <p>Assumptions: converting a zero or negative balance here is not a claim that the screen pays
     * one. Line 198 of {@code app/cbl/COBIL00C.cbl} turns a balance at or below zero away before the
     * payment path is reached, with line 199 requiring a filled-in identifier alongside it, and that
     * guard reads the account record -- so it belongs to the service that holds the record and not to
     * this conversion, which is asked only to render whatever figure it is handed. The two values are
     * offered because they are the two that break a serialiser, and the guard is named so that a reader
     * does not read the case as permission.</p>
     */
    @Test
    @DisplayName("the balance leaves the acknowledgement as a quoted JSON string")
    void theBalanceLeavesTheAcknowledgementAsAQuotedJsonString() {
        ObjectMapper writer = jsonWriter();

        String ordinary = writer.writeValueAsString(this.mapper.toResponse(confirmedRequest(),
                paymentOf(Money.of(new BigDecimal("1250.75"))), null));

        assertThat(ordinary).contains("\"currentBalance\":\"1250.75\"");
        assertThat(ordinary)
                .as("a bare JSON number here would be parsed into an IEEE-754 binary"
                        + " floating-point value by most clients")
                .doesNotContain("\"currentBalance\":1250.75");

        String zero = writer.writeValueAsString(this.mapper.toResponse(confirmedRequest(),
                paymentOf(Money.ZERO), null));

        assertThat(zero).contains("\"currentBalance\":\"0.00\"")
                .doesNotContain("\"currentBalance\":\"0\"")
                .doesNotContain("\"currentBalance\":0");

        String negative = writer.writeValueAsString(this.mapper.toResponse(confirmedRequest(),
                paymentOf(Money.of(new BigDecimal("-1250.75"))), null));

        assertThat(negative)
                .as("CVACT01Y line 7 declares the balance SIGNED, so the sign has to survive")
                .contains("\"currentBalance\":\"-1250.75\"");
        assertThat(ordinary + zero + negative).doesNotContain("E+").doesNotContain("e+");
    }

    /**
     * An absent request or an absent balance is refused, naming the argument.
     *
     * <p>Assumptions: the balance is refused rather than defaulted to zero, because a confirmed payment
     * always has a balance to pay and a zero written in place of an unknown one would post a payment
     * row for nothing while reporting success.</p>
     */
    @Test
    @DisplayName("an absent request or balance is refused")
    void anAbsentRequestOrBalanceIsRefused() {
        assertThatThrownBy(() -> this.mapper.toEntity(null, "42", CARD_NUMBER,
                Money.of(BigDecimal.ONE), PAYMENT_INSTANT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER, null,
                PAYMENT_INSTANT))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * An identifier that is empty, non-numeric or over-wide is refused rather than coerced.
     *
     * <p>Assumptions: a value too wide for the field is refused rather than truncated, because the
     * identifier is a record key and a silently shortened key addresses a different row.</p>
     *
     * @param identifier the value to offer as the transaction identifier for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "4x2", "00000000000000042", " 42"})
    @DisplayName("an unusable identifier is refused rather than coerced")
    void anUnusableIdentifierIsRefused(String identifier) {
        assertThatThrownBy(() -> this.mapper.toEntity(confirmedRequest(), identifier, CARD_NUMBER,
                Money.of(BigDecimal.ONE), PAYMENT_INSTANT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An absent instant has no rendering under this contract and is refused.
     *
     * <p>Assumptions: the renderer refuses rather than substituting the current reading, because the
     * reference obtains its instant once at line 230 of {@code app/cbl/COBIL00C.cbl} and a substituted
     * reading would stamp a row with a time unrelated to the payment it records.</p>
     */
    @Test
    @DisplayName("an absent instant is refused by the renderer")
    void anAbsentInstantIsRefused() {
        assertThatThrownBy(() -> this.mapper.renderTimestamp(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The acknowledgement carries the padded account identifier, the balance paid and the row's key.
     *
     * <p>Assumptions: the identifier on the acknowledgement is padded to the record width rather than
     * echoed as the client sent it, so that the value a client reads back is the value stored. Echoing
     * the request's own spelling would let a client that sent a short identifier receive one shape and
     * find another in the row.</p>
     */
    @Test
    @DisplayName("the acknowledgement carries the padded identifier, the balance and the row key")
    void theAcknowledgementCarriesThePaidFigures() {
        Transaction payment = paymentOf(Money.of(new BigDecimal("1250.75")));

        BillPaymentResponse acknowledgement = this.mapper.toResponse(
                new BillPaymentRequest("11", "Y"), payment,
                this.mapper.paymentSuccessfulMessage(payment.getTranId()));

        assertThat(acknowledgement.accountId()).isEqualTo("00000000011")
                .hasSize(BillPaymentMapper.ACCOUNT_ID_WIDTH);
        assertThat(acknowledgement.transactionId()).isEqualTo("0000000000000042");
        assertThat(acknowledgement.currentBalance().amount())
                .isEqualByComparingTo(new BigDecimal("1250.75"));
        assertThat(acknowledgement.returnMessage())
                .contains("0000000000000042")
                .startsWith(BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_PREFIX);
    }

    /**
     * An absent message on the acknowledgement is reported as no value in each of its spellings.
     *
     * <p>Assumptions: the collapse onto no value exists because the reference attaches a message-off
     * condition to that one field at line 30 of {@code app/cpy/CVCRD01Y.cpy}, making absence
     * representable for it, and a client testing for a message must not have to test for three
     * different spellings of its absence.</p>
     *
     * @param absent the spelling of absence to offer for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "        "})
    @DisplayName("an absent acknowledgement message collapses onto no value")
    void anAbsentMessageCollapsesOntoNoValue(String absent) {
        Transaction payment = paymentOf(Money.of(BigDecimal.ONE));

        assertThat(this.mapper.toResponse(confirmedRequest(), payment, absent).returnMessage())
                .isNull();
        assertThat(this.mapper.toResponse(confirmedRequest(), payment, null).returnMessage()).isNull();
    }

    /**
     * An absent request or an absent row is refused by the acknowledgement conversion.
     */
    @Test
    @DisplayName("the acknowledgement conversion requires both the request and the row")
    void theAcknowledgementConversionRequiresBoth() {
        Transaction payment = paymentOf(Money.of(BigDecimal.ONE));

        assertThatThrownBy(() -> this.mapper.toResponse(null, payment, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.mapper.toResponse(confirmedRequest(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The confirmation sentence is assembled from its fragments and keeps BOTH of its spaces.
     *
     * <p>Assumptions: the repeated space between the opening clause and the second is asserted
     * deliberately. It is real -- one space trails the first fragment and one leads the second -- so a
     * reader who took it for a typing slip and removed it would change a user-visible string that
     * transformation rule T8 requires to be carried across character for character. Asserting the whole
     * sentence literally is what makes that removal fail a test rather than pass a review.</p>
     */
    @Test
    @DisplayName("the confirmation sentence is verbatim, both of its spaces included")
    void theConfirmationSentenceIsVerbatim() {
        assertThat(this.mapper.paymentSuccessfulMessage("42"))
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000042.");
        assertThat(BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_PREFIX).endsWith(" ");
        assertThat(BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_INFIX).startsWith(" ");
    }

    /**
     * The confirmation sentence refuses an identifier it cannot pad to the declared width.
     *
     * @param identifier the value to offer as the identifier for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "4-2", "00000000000000042"})
    @DisplayName("the confirmation sentence refuses an unusable identifier")
    void theConfirmationSentenceRefusesAnUnusableIdentifier(String identifier) {
        assertThatThrownBy(() -> this.mapper.paymentSuccessfulMessage(identifier))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.mapper.paymentSuccessfulMessage(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Two refused fields produce two entries, in the screen's own field order.
     *
     * <p>Assumptions: the order is asserted because the array is what a client renders its field
     * markers from, and a reversed array marks the wrong field on the screen while carrying the right
     * two messages.</p>
     *
     * <p>Refactoring Rationale: the account identifier is offered as unacceptable rather than as never
     * supplied, and an earlier revision of this case offered the never-supplied state. The mapper
     * settles the account identifier before it looks at the confirmation, because the reference catches
     * a never-supplied identifier at line 159 of {@code app/cbl/COBIL00C.cbl} and answers it at line
     * 161, and only a submission that survives that test reaches the four-way confirmation evaluation
     * at its lines 173 to 191 at all. So a never-supplied identifier yields ONE entry by design, and
     * pinning two against that input would have pinned the inverted order this case exists to guard.
     * The two-entry shape is still real and still reachable -- it is the turn where the identifier was
     * supplied but resolved to no account and the confirmation was filled in with something the screen
     * does not accept -- and that is the input offered here.</p>
     */
    @Test
    @DisplayName("two refused fields produce two entries in screen order")
    void twoRefusedFieldsProduceTwoEntriesInScreenOrder() {
        List<ApiError.FieldError> reported = this.mapper.toFieldErrors(
                FieldValidationFlag.NOT_OK, FieldValidationFlag.NOT_OK);

        assertThat(reported).extracting(ApiError.FieldError::field)
                .containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD,
                        BillPaymentMapper.CONFIRMATION_FIELD);
        assertThat(reported.get(0).message()).isEqualTo(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);
        assertThat(reported.get(1).message())
                .isEqualTo(BillPaymentMapper.MESSAGE_INVALID_CONFIRMATION);
    }

    /**
     * A never-supplied account identifier is answered alone, whatever the confirmation holds.
     *
     * <p>Assumptions: this is asserted as its own case because the short-circuit is a behavioural claim
     * about the reference's ORDER of evaluation and not an omission. The commonest first-turn submission
     * leaves both fields empty, and the reference answers it with the one sentence at line 161 of
     * {@code app/cbl/COBIL00C.cbl}; reporting a confirmation marker as well would put a marker on a
     * control the reference never formed an opinion about on that turn.</p>
     */
    @Test
    @DisplayName("a never-supplied account identifier is reported alone")
    void aNeverSuppliedAccountIdentifierIsReportedAlone() {
        assertThat(this.mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.NOT_OK))
                .extracting(ApiError.FieldError::field)
                .containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD);
        assertThat(this.mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.VALID))
                .extracting(ApiError.FieldError::field)
                .containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD);
        assertThat(this.mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.NOT_OK)
                .get(0).message())
                .isEqualTo(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY);
    }

    /**
     * A present but unusable identifier gets the reference's not-found report, not an invented one.
     *
     * <p>Assumptions: this screen applies no numeric and no width edit to the identifier before its
     * read, so the reference has no format complaint to make and reports whatever the read returned.
     * Selecting the not-found text is what avoids putting a sentence in front of an operator that no
     * line of the reference emits.</p>
     */
    @Test
    @DisplayName("a present but unusable identifier reports the not-found text")
    void aPresentButUnusableIdentifierReportsNotFound() {
        List<ApiError.FieldError> reported = this.mapper.toFieldErrors(
                FieldValidationFlag.NOT_OK, FieldValidationFlag.VALID);

        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).field()).isEqualTo(BillPaymentMapper.ACCOUNT_ID_FIELD);
        assertThat(reported.get(0).message())
                .isEqualTo(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);
    }

    /**
     * Two acceptable fields produce no entries at all.
     */
    @Test
    @DisplayName("two acceptable fields produce no entries")
    void twoAcceptableFieldsProduceNoEntries() {
        assertThat(this.mapper.toFieldErrors(FieldValidationFlag.VALID, FieldValidationFlag.VALID))
                .isEmpty();
    }

    /**
     * A never-supplied confirmation is refused as an argument, because it drives the prompt instead.
     *
     * <p>Assumptions: an unfilled confirmation is not an error on this screen -- it is what makes the
     * screen ask for one at line 237 of {@code app/cbl/COBIL00C.cbl} -- so presenting it here means a
     * caller has routed the prompt branch into the refusal branch. Refusing the argument names that
     * mistake where it was made rather than emitting a complaint about a field the operator has not yet
     * reached.</p>
     */
    @Test
    @DisplayName("a never-supplied confirmation is refused as an argument")
    void aNeverSuppliedConfirmationIsRefusedAsAnArgument() {
        assertThatThrownBy(() -> this.mapper.toFieldErrors(
                FieldValidationFlag.VALID, FieldValidationFlag.BLANK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Both validation states are required.
     */
    @Test
    @DisplayName("both validation states are required")
    void bothValidationStatesAreRequired() {
        assertThatThrownBy(() -> this.mapper.toFieldErrors(null, FieldValidationFlag.VALID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.mapper.toFieldErrors(FieldValidationFlag.VALID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
