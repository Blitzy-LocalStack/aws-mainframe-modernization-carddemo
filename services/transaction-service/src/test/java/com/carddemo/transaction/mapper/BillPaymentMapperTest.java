package com.carddemo.transaction.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the bill-payment conversion boundary: the fixed row it writes and the text it assembles.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link BillPaymentMapper} had no executable consumer. The sibling {@code BillPaymentMappingTest}
 * exercises the response record's own factory and never names this class, so the nine fixed field values
 * this mapper writes into every payment row, the two identifier paddings, the timestamp form and the
 * assembled confirmation sentence had no evidence behind them at all.</p>
 *
 * <p>Those fixed values are the whole of the migrated behaviour here. The reference screen writes a
 * payment row in which nine of the thirteen members are literals it holds itself -- the type, the
 * category, the source, the description, the merchant identifier, name, city and postal code -- and only
 * the identifier, the card number, the amount and the two stamps come from the request or its context. A
 * single wrong literal produces a row that persists, balances and reports successfully while being
 * classified as something other than a bill payment for the rest of its life, which is why each one is
 * asserted by value rather than by shape.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a caller
 * invokes, no value it yields and no exception it raises outside the test engine, so the type itself
 * accepts no parameter, returns nothing and throws nothing. The inapplicability is stated rather than
 * passed over, because user-specified Rule 1 forbids a docstring that omits parameters, return values
 * or purpose and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own at-clauses.</p>
 */
@DisplayName("BillPaymentMapper: the fixed payment row, the paddings and the assembled text")
class BillPaymentMapperTest {

    /** The mapper under test, which holds no state and needs no collaborator. */
    private final BillPaymentMapper mapper = new BillPaymentMapper();

    /** An eleven-digit account identifier, at its declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A sixteen-digit card number resolved from the cross-reference, at its declared width. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** The instant a payment is stamped with in these cases. */
    private static final LocalDateTime PAYMENT_INSTANT =
            LocalDateTime.parse("2022-07-18T10:15:30.123456");

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
     * Every one of the nine fixed members is written with the reference's own literal.
     *
     * <p>Assumptions: the nine are asserted individually rather than by comparing a whole expected row,
     * so a failure names the member that drifted. A whole-row comparison would report one failure for
     * any of the nine and leave a reader to find which.</p>
     */
    @Test
    @DisplayName("the nine fixed members carry the reference's own literals")
    void theNineFixedMembersAreTheReferenceLiterals() {
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(new BigDecimal("1250.75")), PAYMENT_INSTANT);

        assertThat(payment.getTranTypeCd()).isEqualTo("02");
        assertThat(payment.getTranCatCd()).isEqualTo("0002");
        assertThat(payment.getTranSource()).isEqualTo("POS TERM");
        assertThat(payment.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(payment.getMerchantId()).isEqualTo(999999999L);
        assertThat(payment.getMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(payment.getMerchantCity()).isEqualTo("N/A");
        assertThat(payment.getMerchantZip()).isEqualTo("N/A");
        assertThat(payment.getTranTypeCd()).isEqualTo(BillPaymentMapper.TRANSACTION_TYPE_CODE);
        assertThat(payment.getTranCatCd()).isEqualTo(BillPaymentMapper.TRANSACTION_CATEGORY_CODE);
    }

    /**
     * The amount written is the whole balance standing before the payment, exact at scale two.
     *
     * <p>Assumptions: a bill payment on this screen pays the whole balance rather than a requested
     * figure -- the request carries no amount member at all -- so the amount is the balance, and the
     * scale is asserted because every comparison downstream is an exact-decimal comparison.</p>
     */
    @Test
    @DisplayName("the amount is the whole pre-payment balance at scale two")
    void theAmountIsTheWholePrePaymentBalance() {
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(new BigDecimal("1250.7")), PAYMENT_INSTANT);

        assertThat(payment.getTranAmt()).isEqualByComparingTo(new BigDecimal("1250.70"));
        assertThat(payment.getTranAmt().scale()).isEqualTo(2);
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
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", "7065",
                Money.of(new BigDecimal("1.00")), PAYMENT_INSTANT);

        assertThat(payment.getTranId()).isEqualTo("0000000000000042")
                .hasSize(BillPaymentMapper.IDENTIFIER_WIDTH);
        assertThat(payment.getCardNum()).isEqualTo("0000000000007065")
                .hasSize(BillPaymentMapper.IDENTIFIER_WIDTH);
    }

    /**
     * One instant is reduced once and written to BOTH stamps, which are therefore equal.
     *
     * <p>Assumptions: equality of the two members is the property, and it distinguishes this screen
     * from the two other paths that write the same pair. The nightly posting program copies an incoming
     * originating stamp through and mints only the processing one, so its two members are not equal;
     * this screen takes one reading and writes it twice, so they are. Reducing once rather than twice is
     * what makes them equal in the last three digits as well as the first twenty-three.</p>
     */
    @Test
    @DisplayName("one reduced instant is written to both stamps")
    void oneInstantIsWrittenToBothStamps() {
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(new BigDecimal("1.00")),
                LocalDateTime.parse("2022-07-18T10:15:30.123456789"));

        assertThat(payment.getOrigTs()).isEqualTo(payment.getProcTs());
        assertThat(payment.getOrigTs())
                .as("reduced to the resolution the 26-character contract carries")
                .isEqualTo(LocalDateTime.parse("2022-07-18T10:15:30.123456"));
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
        assertThatThrownBy(() -> mapper.toEntity(null, "42", CARD_NUMBER,
                Money.of(BigDecimal.ONE), PAYMENT_INSTANT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER, null,
                PAYMENT_INSTANT))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * An identifier that is absent, empty, non-numeric or over-wide is refused rather than coerced.
     *
     * @param identifier the value to offer as the transaction identifier for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "4x2", "00000000000000042", " 42"})
    @DisplayName("an unusable identifier is refused rather than coerced")
    void anUnusableIdentifierIsRefused(String identifier) {
        assertThatThrownBy(() -> mapper.toEntity(confirmedRequest(), identifier, CARD_NUMBER,
                Money.of(BigDecimal.ONE), PAYMENT_INSTANT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The rendered timestamp is the contract's 26-character form and agrees with the stored value.
     *
     * <p>Assumptions: the rendering and the stored value are asserted TOGETHER, because the failure
     * worth guarding is not a wrong rendering but a rendering that disagrees with the row written from
     * the same instant. Both go through the shared formatter for exactly that reason, and a locally
     * declared pattern is what would break the agreement while rendering something plausible.</p>
     */
    @Test
    @DisplayName("the rendered timestamp is the 26-character form and matches the stored value")
    void theRenderedTimestampMatchesTheStoredValue() {
        String rendered = mapper.renderTimestamp(LocalDateTime.parse("2022-07-18T10:15:30.123456789"));
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(BigDecimal.ONE), LocalDateTime.parse("2022-07-18T10:15:30.123456789"));

        assertThat(rendered).isEqualTo("2022-07-18 10:15:30.123456")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(TimestampFormatter.parse(rendered)).isEqualTo(payment.getProcTs());
    }

    /**
     * An absent instant has no rendering under this contract and is refused.
     */
    @Test
    @DisplayName("an absent instant is refused by the renderer")
    void anAbsentInstantIsRefused() {
        assertThatThrownBy(() -> mapper.renderTimestamp(null))
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
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(new BigDecimal("1250.75")), PAYMENT_INSTANT);

        BillPaymentResponse acknowledgement = mapper.toResponse(new BillPaymentRequest("11", "Y"),
                payment, mapper.paymentSuccessfulMessage(payment.getTranId()));

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
     * An absent message on the acknowledgement is reported as null in each of its spellings.
     *
     * <p>Assumptions: the collapse onto null exists because the reference attaches a message-off
     * condition to that one field, making absence representable for it, and a client testing for a
     * message must not have to test for three different spellings of its absence.</p>
     *
     * @param absent the spelling of absence to offer for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "        "})
    @DisplayName("an absent acknowledgement message collapses onto null")
    void anAbsentMessageCollapsesOntoNull(String absent) {
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(BigDecimal.ONE), PAYMENT_INSTANT);

        assertThat(mapper.toResponse(confirmedRequest(), payment, absent).returnMessage()).isNull();
        assertThat(mapper.toResponse(confirmedRequest(), payment, null).returnMessage()).isNull();
    }

    /**
     * An absent request or an absent row is refused by the acknowledgement conversion.
     */
    @Test
    @DisplayName("the acknowledgement conversion requires both the request and the row")
    void theAcknowledgementConversionRequiresBoth() {
        Transaction payment = mapper.toEntity(confirmedRequest(), "42", CARD_NUMBER,
                Money.of(BigDecimal.ONE), PAYMENT_INSTANT);

        assertThatThrownBy(() -> mapper.toResponse(null, payment, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.toResponse(confirmedRequest(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The confirmation sentence is assembled from four fragments and keeps BOTH of its spaces.
     *
     * <p>Assumptions: the repeated space between the opening clause and the second is asserted
     * deliberately. It is real -- one space trails the first reference literal and one leads the second,
     * at lines 527 and 528 of {@code app/cbl/COBIL00C.cbl} -- so a reader who took it for a typing slip
     * and removed it would change a user-visible string that transformation rule T8 requires to be
     * carried across character for character. Asserting the whole sentence literally is what makes that
     * removal fail a test rather than pass a review.</p>
     */
    @Test
    @DisplayName("the confirmation sentence is verbatim, both of its spaces included")
    void theConfirmationSentenceIsVerbatim() {
        assertThat(mapper.paymentSuccessfulMessage("42"))
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
        assertThatThrownBy(() -> mapper.paymentSuccessfulMessage(identifier))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.paymentSuccessfulMessage(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Two refused fields produce two entries, in the screen's own field order.
     *
     * <p>Assumptions: the order is asserted because the array is what a client renders its field
     * markers from, and a reversed array marks the wrong field on the screen while carrying the right
     * two messages.</p>
     *
     * <p>Refactoring Rationale: the account identifier is offered as UNACCEPTABLE rather than as
     * never supplied, and an earlier revision of this case offered the never-supplied state. The
     * mapper settles the account identifier before it looks at the confirmation, because the
     * reference catches a never-supplied identifier at line 159 of {@code app/cbl/COBIL00C.cbl} and
     * answers it at line 161, and only a submission that survives that test reaches the four-way
     * confirmation evaluation at its lines 173 to 191 at all. So a never-supplied identifier yields
     * ONE entry by design, and pinning two against that input would have pinned the inverted order
     * this case exists to guard. The two-entry shape is still real and still reachable -- it is the
     * turn where the identifier was supplied but resolved to no account and the confirmation was
     * filled in with something the screen does not accept -- and that is the input offered here.</p>
     */
    @Test
    @DisplayName("two refused fields produce two entries in screen order")
    void twoRefusedFieldsProduceTwoEntriesInScreenOrder() {
        List<ApiError.FieldError> reported = mapper.toFieldErrors(
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
     * <p>Assumptions: this is asserted as its own case because the short-circuit is a behavioural
     * claim about the reference's ORDER of evaluation and not an omission. The commonest first-turn
     * submission leaves both fields empty, and the reference answers it with the one sentence at line
     * 161 of {@code app/cbl/COBIL00C.cbl}; reporting a confirmation marker as well would put a marker
     * on a control the reference never formed an opinion about on that turn.</p>
     */
    @Test
    @DisplayName("a never-supplied account identifier is reported alone")
    void aNeverSuppliedAccountIdentifierIsReportedAlone() {
        assertThat(mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.NOT_OK))
                .extracting(ApiError.FieldError::field)
                .containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD);
        assertThat(mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.VALID))
                .extracting(ApiError.FieldError::field)
                .containsExactly(BillPaymentMapper.ACCOUNT_ID_FIELD);
        assertThat(mapper.toFieldErrors(FieldValidationFlag.BLANK, FieldValidationFlag.NOT_OK)
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
        List<ApiError.FieldError> reported = mapper.toFieldErrors(
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
        assertThat(mapper.toFieldErrors(FieldValidationFlag.VALID, FieldValidationFlag.VALID)).isEmpty();
    }

    /**
     * A never-supplied confirmation is refused as an argument, because it drives the prompt instead.
     *
     * <p>Assumptions: an unfilled confirmation is not an error on this screen -- it is what makes the
     * screen ask for one -- so presenting it here means a caller has routed the prompt branch into the
     * refusal branch. Refusing the argument names that mistake where it was made rather than emitting a
     * complaint about a field the operator has not yet reached.</p>
     */
    @Test
    @DisplayName("a never-supplied confirmation is refused as an argument")
    void aNeverSuppliedConfirmationIsRefusedAsAnArgument() {
        assertThatThrownBy(() -> mapper.toFieldErrors(
                FieldValidationFlag.VALID, FieldValidationFlag.BLANK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Both validation states are required.
     */
    @Test
    @DisplayName("both validation states are required")
    void bothValidationStatesAreRequired() {
        assertThatThrownBy(() -> mapper.toFieldErrors(null, FieldValidationFlag.VALID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.toFieldErrors(FieldValidationFlag.VALID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
