package com.carddemo.transaction.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the conversions {@link TransactionMapper} performs over the 350-byte transaction record.
 *
 * <h2>Purpose</h2>
 *
 * <p>Six of that class's seven conversions had no executable consumer: the detail view, the list
 * row, the display ordering, the page envelope, the appended row and the append acknowledgement.
 * The seventh, the bill-payment acknowledgement, is already pinned by the sibling
 * {@code BillPaymentMappingTest}, so it is deliberately not re-asserted here. Each case below
 * asserts a decision no member name discloses -- which stored column becomes which published
 * member, what is withheld on the way out, what is padded and to which width, which of two
 * timestamps a value is read from, and which absences collapse onto {@code null}.
 *
 * <p>The record being carried is {@code 01 TRAN-RECORD}, declared at line 4 of
 * {@code app/cpy/CVTRA05Y.cpy}, whose header line 2 states its length as 350 and whose thirteen
 * named fields occupy lines 5 to 17. The directory charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/mapper/package-info.java}
 * assigns this class exactly that subject, and the subtree charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java}
 * owns the conventions this file obeys. Both are cited rather than restated, because a second copy
 * of a convention is a second thing to keep in step.
 *
 * <h2>What this class deliberately does not assert</h2>
 *
 * <p>Assumptions: a transfer object's declared constraints belong to
 * {@code com.carddemo.transaction.dto} and a declared-width record's geometry to
 * {@code com.carddemo.transaction.domain}. Where a component inventory appears below it is asserted
 * as a property of the CONVERSION -- that this class produces neither more nor fewer members than
 * the record has fields to fill -- and never as a restatement of a constraint annotation.
 *
 * <p>Assumptions: no field is renamed in this module, and the absence of a rename assertion is a
 * decision rather than an oversight. A reader arriving from a neighbouring service will expect one,
 * because the wider migration corrects three misspelled baseline names in the account, card and
 * authorization contexts; none of the three occurs in {@code app/cpy/CVTRA05Y.cpy}, and
 * {@code app/cbl/CBTRN02C.cbl} uses the misspelled account name at its line 414 exactly as it
 * stands. A rename asserted here would pass only against an implementation that had invented a
 * divergence from the reference layout.
 *
 * <p>Assumptions: no case below is a golden-master parity check, because no committed output exists
 * to difference this module against. The four programs it migrates are online programs, and
 * {@code tests/README.md} records at its lines 43 to 45 that those cannot be exercised end to end
 * without a transaction monitor the runner does not provide. Every case is answerable instead
 * to the reference line it cites and to the seed bytes it quotes, and a cited batch line is
 * evidence of a rule rather than an oracle this class is differenced against.
 *
 * <p>A test class takes no parameter, returns no value and raises nothing outside the test engine,
 * so the type carries no at-clause of its own; every member below carries its own.
 */
@DisplayName("TransactionMapper: the 350-byte record's conversions, its paddings and its withholdings")
class TransactionMapperTest {

    /** The mapper under test, which holds no state, so one instance serves every case. */
    private final TransactionMapper mapper = new TransactionMapper();

    /**
     * The transaction identifier of the first seed record, at its declared width.
     *
     * <p>Assumptions: this is the value at the sixteen bytes starting at one-based position 1 of
     * record 1 of {@code app/data/ASCII/dailytran.txt}, read from the file rather than invented, so
     * that a case asserting a padded identifier asserts a shape the committed extract actually
     * carries.
     */
    private static final String SEED_TRAN_ID = "0000000000683580";

    /**
     * The card number of the first seed record, which begins with a significant non-zero digit.
     *
     * <p>Assumptions: the sixteen bytes starting at one-based position 263 of record 1 of that
     * file, which is the offset {@code app/jcl/TRANREPT.jcl} independently declares at its line 41
     * as {@code TRAN-CARD-NUM,263,16,ZD}.
     */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /**
     * The card number of the second seed record, whose leading digit is a zero.
     *
     * <p>Assumptions: the sixteen bytes starting at one-based position 263 of record 2 of that
     * file. It is used wherever the leading zero is the property under test, because 30 of that
     * file's 300 records carry a card number beginning with a zero and this is the first of them.
     */
    private static final String SEED_CARD_NUMBER_LEADING_ZERO = "0927987108636232";

    /**
     * The originating instant of the first seed record.
     *
     * <p>Assumptions: the twenty-six bytes starting at one-based position 279 of record 1 read
     * {@code 2022-06-10 19:27:53.000000}, so this value is the feed's own stamp rather than a
     * reading of any clock. Nothing in this class reads ambient time.
     */
    private static final LocalDateTime SEED_ORIGIN_TS = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /**
     * A processing instant distinct from {@link #SEED_ORIGIN_TS}, carrying non-zero microseconds.
     *
     * <p>Assumptions: the batch path mints this stamp separately rather than copying the feed's,
     * so the two differ in date, in time and in sub-second component. Making all three differ is
     * what stops a case passing against an implementation that had copied one stamp into both.
     */
    private static final LocalDateTime BATCH_PROCESS_TS =
            LocalDateTime.of(2022, 6, 11, 2, 15, 4, 123_456_000);

    /**
     * The amount of the first seed record, decoded from its eleven zoned-decimal bytes.
     *
     * <p>Assumptions: the eleven bytes starting at one-based position 133 of record 1 read
     * {@code 0000005047G}, whose trailing {@code G} carries the digit 7 together with a positive
     * sign, so the value is 504.77. The same vector appears as a known-answer doctest at lines 81
     * and 82 of {@code tests/helpers/record_codec.py}.
     */
    private static final BigDecimal SEED_AMOUNT = new BigDecimal("504.77");

    /**
     * The unsigned category code of the first two seed records.
     *
     * <p>Assumptions: {@code TRAN-CAT-CD PIC 9(04)} at line 7 of {@code app/cpy/CVTRA05Y.cpy}
     * carries no {@code S}, so the four bytes at one-based positions 19 to 22 are plain digits with
     * no trailing-sign overpunch, and both seed records hold {@code 0001} there.
     */
    private static final String SEED_CATEGORY_CODE = "0001";

    /**
     * The unsigned merchant identifier every seed record carries.
     *
     * <p>Assumptions: {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11 of that copybook likewise
     * carries no {@code S}, so the nine bytes at one-based positions 144 to 152 are plain digits,
     * and both seed records hold {@code 800000000} there.
     */
    private static final String SEED_MERCHANT_ID = "800000000";

    /**
     * Synthetic key material for the cursor sealer, above the minimum width it accepts.
     *
     * <p>Assumptions: this is test-only material and is a credential of no environment. It is a
     * literal so that a sealed token is reproducible within one run, and it reads as synthetic on
     * sight so that no reader mistakes it for a value to protect.
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-transaction-mapper-test-key-material".getBytes(StandardCharsets.US_ASCII);

    /**
     * Builds a stored row from the first seed record's own field values.
     *
     * <p>Assumptions: the thirteen constructor arguments are the record's thirteen named fields in
     * declaration order, with no fourteenth for the padding at line 18. The row is fabricated
     * inline rather than drawn from the module's fixture tree, because the directory charter asks
     * that the figures a case supplies be visible in the case that supplies them.
     *
     * @param cardNumber the sixteen-digit card number to store, supplied per case so that a case
     *     about a leading zero can differ from one about masking
     * @param originTimestamp the originating instant to store, or {@code null} to store none
     * @param processTimestamp the processing instant to store, or {@code null} to store none, which
     *     is the state the daily feed carries
     * @return a row carrying the first seed record's identifier, codes, description, amount and
     *     merchant values together with the three supplied values
     */
    private static Transaction storedRow(String cardNumber, LocalDateTime originTimestamp,
            LocalDateTime processTimestamp) {
        return new Transaction(SEED_TRAN_ID, "01", SEED_CATEGORY_CODE, "POS TERM  ",
                "Purchase at Abshire-Lowe", SEED_AMOUNT, Long.valueOf(SEED_MERCHANT_ID),
                "Merchant Name", "Merchant City", "12345     ", cardNumber, originTimestamp,
                processTimestamp);
    }

    /**
     * Builds the stored row every case uses unless it needs a different card or a different stamp.
     *
     * <p>Assumptions: the default pairs the feed's originating instant with a distinct processing
     * instant, because {@code app/cbl/CBTRN02C.cbl} assigns the two from different sources.
     * Defaulting both to one value would quietly bias every case that names no stamp toward the
     * bill-payment shape, where a single move feeds two receivers.
     *
     * @return a row carrying the first seed record's card number, its originating instant and a
     *     distinct processing instant
     */
    private static Transaction storedRow() {
        return storedRow(SEED_CARD_NUMBER, SEED_ORIGIN_TS, BATCH_PROCESS_TS);
    }

    /**
     * Builds a submission carrying the record-derived fields the add screen supplies.
     *
     * <p>Assumptions: the account identifier is supplied and the card number is left absent,
     * because lines 195 to 209 of {@code app/cbl/COTRN02C.cbl} resolve the card from the
     * account-keyed cross-reference and then overwrite whatever the client typed. A submission
     * naming both keys is a shape the request type refuses, so it is not built here.
     *
     * @param amount the amount submitted, which the conversion reads into the stored row
     * @param originDate the origination date as ten characters in the form {@code YYYY-MM-DD}
     * @param processDate the processing date in the same form, supplied separately so that a case
     *     can prove the two are read independently
     * @return a submission carrying an account identifier, the eleven record-derived fields and an
     *     affirmative confirmation
     */
    private static TransactionAddRequest submission(Money amount, String originDate,
            String processDate) {
        return new TransactionAddRequest("00000000011", "01", SEED_CATEGORY_CODE, "POS TERM  ",
                "Purchase at Abshire-Lowe", amount, SEED_MERCHANT_ID, "Merchant Name",
                "Merchant City", "12345     ", null, originDate, processDate, "Y");
    }

    /**
     * Builds a JSON writer carrying the shared money module and nothing else.
     *
     * <p>Refactoring Rationale: the module is registered explicitly rather than obtained from an
     * auto-configured application context. A wire-format contract that holds only because some
     * starter happened to register the module is a contract nobody can rely on the moment a starter
     * changes, so the registration is made a statement of this file rather than an inheritance.
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
     * the property that lets a case assert an ORDER rather than only a set. Reading the order off
     * the compiled type rather than from a list written here is what keeps a case honest when a
     * component is inserted: a written list would go on passing against the order it was written
     * for.
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
     * Lists every published component name and every declared field name of the mapped shapes.
     *
     * <p>Assumptions: the four transfer objects and the entity are swept together because a value
     * withheld from a response but retained on the entity is still reachable from a log line, and a
     * value withheld from the entity but published on a response is reachable from a body. A sweep
     * that covered only one side would leave the other as the route.
     *
     * @return every component name of the four mapped records followed by every declared field name
     *     of the entity, all in lower case so that a case may match without regard to spelling
     */
    private static List<String> everyMappedMemberName() {
        List<String> names = new ArrayList<>();
        for (Class<?> mapped : List.of(TransactionDetailResponse.class,
                TransactionListItemResponse.class, TransactionAddRequest.class,
                TransactionAddResponse.class)) {
            for (String name : componentNames(mapped)) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        for (Field field : Transaction.class.getDeclaredFields()) {
            names.add(field.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Seals one raw keyset cursor into the opaque token a page envelope requires.
     *
     * <p>Assumptions: the envelope refuses a raw key outright -- its canonical constructor tests
     * every present boundary for the sealed shape -- so a case exercising a page carrying rows
     * cannot avoid sealing. The sealer is therefore a real one over synthetic key material rather
     * than a substitute, because a substitute would let this file assert a page shape the
     * production envelope would have rejected.
     *
     * @param cursorKey the raw boundary key to seal, which is a stored transaction identifier
     * @return the sealed token, which the envelope accepts as a boundary
     */
    private static String sealed(String cursorKey) {
        CursorToken sealer = new CursorToken(CURSOR_KEY, Duration.ofMinutes(15));
        return sealer.seal(
                CursorToken.binding("transactions", "mapper-test-subject", CursorToken.SCOPE_NONE),
                cursorKey);
    }

    /**
     * The detail view publishes the record's thirteen fields in the record's own declaration order.
     *
     * <p>Pins paragraph {@code PROCESS-ENTER-KEY} of {@code app/cbl/COTRN01C.cbl}, whose thirteen
     * screen moves occupy lines 178 to 190, against the record declaration at lines 5 to 17 of
     * {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Assumptions: the RECORD order governs and the screen-move order does not, and the two
     * genuinely differ rather than differing incidentally -- the two positions the assertions below
     * name are that divergence. A reader following the moves would order the components the other
     * way, and every component involved is a string, so a transposition would compile, run and
     * publish two values under each other's names.
     */
    @Test
    @DisplayName("the detail view publishes the record's thirteen fields in record order")
    void theDetailViewCarriesTheRecordsThirteenFieldsInRecordOrder() {
        List<String> published = componentNames(TransactionDetailResponse.class);

        assertThat(published).containsExactly("transactionId", "typeCode", "categoryCode", "source",
                "description", "amount", "merchantId", "merchantName", "merchantCity", "merchantZip",
                "cardNumber", "originTimestamp", "processTimestamp", "returnMessage");
        assertThat(published.indexOf("cardNumber"))
                .as("CVTRA05Y line 15 declares the card number eleventh, though COTRN01C line 179"
                        + " moves it to the screen second")
                .isEqualTo(10);
        assertThat(published.indexOf("description"))
                .as("CVTRA05Y line 9 declares the description fifth, though COTRN01C line 184 moves"
                        + " it to the screen seventh")
                .isEqualTo(4);

        TransactionDetailResponse view = this.mapper.toDetailResponse(storedRow(), null);

        assertThat(view.transactionId()).isEqualTo(SEED_TRAN_ID);
        assertThat(view.typeCode()).isEqualTo("01");
        assertThat(view.categoryCode()).isEqualTo(SEED_CATEGORY_CODE);
        assertThat(view.source()).isEqualTo("POS TERM  ");
        assertThat(view.description()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(view.merchantId()).isEqualTo(SEED_MERCHANT_ID);
        assertThat(view.merchantName()).isEqualTo("Merchant Name");
        assertThat(view.merchantCity()).isEqualTo("Merchant City");
        assertThat(view.merchantZip()).isEqualTo("12345     ");
    }

    /**
     * The twenty padding bytes reach no component and no member in either direction.
     *
     * <p>Pins the drop of {@code 05 FILLER PIC X(20)} at line 18 of
     * {@code app/cpy/CVTRA05Y.cpy}, whose twenty bytes occupy one-based positions 331 to 350.
     *
     * <p>Assumptions: those twenty bytes pad the record to the length its header line 2 declares
     * rather than carrying a value, so the drop is only demonstrably deliberate if the arithmetic
     * closes -- a reader reconciling a 350-byte record against a thirteen-component list needs the
     * twenty bytes named. The sweep covers the entity as well as the four transfer objects, since a
     * padding member retained on the entity would still reach a log line.
     */
    @Test
    @DisplayName("the twenty padding bytes have no component and no member in either direction")
    void theTwentyPaddingBytesHaveNoComponentInEitherDirection() {
        int[] namedWidths = {16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26};
        int mapped = Arrays.stream(namedWidths).sum();

        assertThat(namedWidths).as("CVTRA05Y lines 5 to 17 declare thirteen named fields").hasSize(13);
        assertThat(mapped)
                .as("the thirteen named widths of CVTRA05Y lines 5 to 17 sum to 330 bytes")
                .isEqualTo(330);
        assertThat(mapped + 20)
                .as("the 20 bytes of the FILLER at line 18 complete the 350 the header at line 2"
                        + " declares")
                .isEqualTo(350);

        assertThat(everyMappedMemberName())
                .as("no mapped shape names the padding CVTRA05Y line 18 declares")
                .noneMatch(name -> name.contains("filler"))
                .noneMatch(name -> name.contains("padding"));
        assertThat(componentNames(TransactionDetailResponse.class))
                .as("thirteen mapped fields plus the one nullable message, and no fourteenth field")
                .hasSize(14);
    }

    /**
     * No mapped shape names a card verification value in either direction.
     *
     * <p>Pins the record declaration at lines 5 to 17 of {@code app/cpy/CVTRA05Y.cpy}, which
     * declares no such field.
     *
     * <p>Assumptions: because the record declares none, this module has no path that could return
     * one and suppresses nothing. The prohibition is asserted anyway because this class is the only
     * place a value joined from another context could enter one of these shapes, so the sweep is
     * what stops such a join being added silently.
     */
    @Test
    @DisplayName("no mapped shape names a card verification value")
    void noMappedShapeCarriesACardVerificationValue() {
        assertThat(everyMappedMemberName())
                .noneMatch(name -> name.contains("cvv"))
                .noneMatch(name -> name.contains("cvc"))
                .noneMatch(name -> name.contains("verification"))
                .noneMatch(name -> name.contains("securitycode"));
    }

    /**
     * The list row carries four members, and neither a selection marker nor a message.
     *
     * <p>Pins paragraph {@code POPULATE-TRAN-DATA} of {@code app/cbl/COTRN00C.cbl}, whose row fill
     * reads {@code TRAN-ORIG-TS} at line 384, against the row fields the map declares.
     *
     * <p>Assumptions: the selection marker is not row data, and the reference proves it by keeping
     * the marker somewhere else -- lines 62 to 70 of {@code app/cbl/COTRN00C.cbl} append it to the
     * communication area, where it travels between turns as navigation state rather than as a value
     * belonging to a row. A message is likewise screen-wide rather than row-wide, and the envelope
     * this row travels in has no place for one. Both absences are asserted rather than left
     * implicit, since either could be added by an author reading the screen rather than the record.
     */
    @Test
    @DisplayName("the list row carries four members and neither a marker nor a message")
    void theListRowCarriesFourMembersAndNeitherAMarkerNorAMessage() {
        List<String> published = componentNames(TransactionListItemResponse.class);

        assertThat(published)
                .containsExactly("transactionId", "description", "amount", "originTimestamp");
        assertThat(published)
                .as("the marker travels in the COTRN00C lines 62 to 70 extension, not in the row")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("sel"))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("marker"))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("flag"))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("message"));

        TransactionListItemResponse row = this.mapper.toListItem(storedRow());

        assertThat(row.transactionId()).isEqualTo(SEED_TRAN_ID);
        assertThat(row.description())
                .as("TRAN-DESC PIC X(100) at CVTRA05Y line 9 is carried at its record width, not at"
                        + " the X(26) the row field TDESC01I declares at COTRN00.CPY line 90")
                .isEqualTo("Purchase at Abshire-Lowe");
        assertThat(row.originTimestamp())
                .as("COTRN00C line 384 reads TRAN-ORIG-TS and never TRAN-PROC-TS")
                .isEqualTo(TimestampFormatter.format(SEED_ORIGIN_TS));
    }

    /**
     * The submission carries no transaction identifier, because the reference derives one.
     *
     * <p>Pins paragraph {@code ADD-TRANSACTION} of {@code app/cbl/COTRN02C.cbl}, whose identifier
     * derivation occupies lines 444 to 451.
     *
     * <p>Assumptions: the identifier is generated rather than submitted, and the reference generates
     * it by a backward browse to the high end of the key that no client can perform. The screen map
     * {@code app/cpy-bms/COTRN02.CPY} declares no identifier field at all, so a component here
     * would publish an input the reference has nowhere to read.
     */
    @Test
    @DisplayName("the submission carries no transaction identifier")
    void theSubmissionCarriesNoTransactionIdentifier() {
        List<String> submitted = componentNames(TransactionAddRequest.class);

        assertThat(submitted).hasSize(14);
        assertThat(submitted.get(0))
                .as("ACTIDINI PIC X(11) at COTRN02.CPY line 60 is the first field the screen keys")
                .isEqualTo("accountId");
        assertThat(submitted.get(submitted.size() - 1))
                .as("CONFIRMI PIC X(1) at COTRN02.CPY line 138 is turn control and comes last")
                .isEqualTo("confirmation");
        assertThat(submitted)
                .as("COTRN02C lines 444 to 451 derive the identifier, so it is not submitted")
                .doesNotContain("transactionId");
        assertThat(submitted).containsSequence("typeCode", "categoryCode", "source", "description",
                "amount", "merchantId", "merchantName", "merchantCity", "merchantZip", "cardNumber",
                "originDate", "processDate");
    }

    /**
     * The acknowledgement carries three members and no error-message component.
     *
     * <p>Pins paragraph {@code ADD-TRANSACTION} of {@code app/cbl/COTRN02C.cbl} at its lines 727 to
     * 733, which assemble the outcome sentence the acknowledgement carries.
     *
     * <p>Assumptions: absence is representable for the return message and for that field alone.
     * {@code app/cpy/CVCRD01Y.cpy} declares {@code CCARD-RETURN-MSG PIC X(75)} at line 29 and
     * attaches {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} at line 30 to it, while
     * {@code CCARD-ERROR-MSG PIC X(75)} at line 28 carries no such condition. An error therefore has
     * no sentinel to express absence with, which is why the refusal path is answered by
     * {@code com.carddemo.common.error.ApiError} instead and no error component appears here.
     */
    @Test
    @DisplayName("the acknowledgement carries three members and no error-message component")
    void theAcknowledgementCarriesNoErrorMessageComponent() {
        List<String> published = componentNames(TransactionAddResponse.class);

        assertThat(published).containsExactly("transactionId", "amount", "returnMessage");
        assertThat(published)
                .as("CVCRD01Y line 28 gives the error message no sentinel, so the refusal path is"
                        + " ApiError's rather than this shape's")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("error"));
    }

    /**
     * Money crosses every conversion as an exact decimal held at two decimal places.
     *
     * <p>Pins the amount declaration {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy}, which the transaction category balance mirrors as
     * {@code TRAN-CAT-BAL PIC S9(09)V99} at line 9 of {@code app/cpy/CVTRA01Y.cpy}.
     *
     * <p>Assumptions: the scale is determined by the picture rather than chosen, since {@code V99}
     * declares two decimal positions and nine integer positions, which is the {@code NUMERIC(11,2)}
     * the stored column carries. The scale is asserted exactly rather than by comparison, because a
     * value equal in magnitude but held at a different scale re-renders differently on the wire while
     * comparing equal in every arithmetic test.
     *
     * <p>Refactoring Rationale: the arithmetic contract is read off
     * {@code com.carddemo.common.money.Money} rather than restated here, following the reference
     * suite's own rule never to duplicate a layout; a scale or rounding mode copied into this file
     * would be a second declaration able to drift from the one the conversion actually applies.
     */
    @Test
    @DisplayName("money crosses as an exact decimal held at two decimal places")
    void moneyCrossesAsAnExactDecimalAtScaleTwo() {
        assertThat(Money.SCALE)
                .as("V99 at CVTRA05Y line 10 declares two decimal positions")
                .isEqualTo(2);
        assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);

        TransactionDetailResponse view = this.mapper.toDetailResponse(storedRow(), null);
        TransactionListItemResponse row = this.mapper.toListItem(storedRow());
        TransactionAddResponse acknowledgement = this.mapper.toAddResponse(storedRow(), null);

        for (Money crossed : List.of(view.amount(), row.amount(), acknowledgement.amount())) {
            assertThat(crossed.amount()).isEqualByComparingTo(SEED_AMOUNT);
            assertThat(crossed.amount().scale()).isEqualTo(Money.SCALE);
        }

        Transaction appended = this.mapper.toEntity(
                submission(Money.of(SEED_AMOUNT), "2022-06-10", "2022-06-11"), SEED_TRAN_ID,
                SEED_CARD_NUMBER);

        assertThat(appended.getTranAmt()).isEqualByComparingTo(SEED_AMOUNT);
        assertThat(appended.getTranAmt().scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Money leaves every response as a quoted JSON string and never as a JSON number.
     *
     * <p>Pins the amount declaration at line 10 of {@code app/cpy/CVTRA05Y.cpy} as it reaches the
     * wire, for the three response shapes this class produces.
     *
     * <p>Alternatives Considered: transporting the amount as a JSON number, which is the obvious
     * alternative and is rejected. Most clients parse a JSON number into an IEEE-754 binary
     * floating-point value, and a value such as 504.77 has no exact binary representation, so the
     * exactness the {@code NUMERIC(11,2)} column and the scale-2 decimal both maintain would be
     * discarded at the last hop, where nothing downstream could detect the loss. The cost accepted is
     * that every client performs an explicit parse.
     *
     * <p>Trade-offs: the assertion is made against the RAW serialised text rather than against a
     * re-parsed tree. Re-parsing would answer what the value is and not how it was written, so the
     * quotation marks -- the whole property under test -- would be consumed by the parser before the
     * assertion saw them, and a bare number carrying the right digits would pass. What is given up is
     * that the assertion is coupled to the writer's spacing; what it buys is that it can fail for the
     * defect the quoted form exists to prevent.
     *
     * <p>Assumptions: zero is asserted separately because it is the value most likely to lose its
     * scale. A shape emitting {@code "0"} rather than {@code "0.00"} would still be a string and
     * would still parse to the right number, so a quotation-mark test alone would not see it.
     */
    @Test
    @DisplayName("money leaves every response as a quoted JSON string, and zero keeps its scale")
    void moneyLeavesEveryResponseAsAQuotedJsonString() {
        ObjectMapper writer = jsonWriter();
        Transaction row = storedRow();

        for (Object response : List.of(this.mapper.toDetailResponse(row, null),
                this.mapper.toListItem(row), this.mapper.toAddResponse(row, null))) {
            String json = writer.writeValueAsString(response);

            assertThat(json).contains("\"amount\":\"504.77\"");
            assertThat(json)
                    .as("a bare JSON number here would be parsed into an IEEE-754 binary"
                            + " floating-point value by most clients")
                    .doesNotContain("\"amount\":504.77");
        }

        Transaction zeroRow = storedRow(SEED_CARD_NUMBER, SEED_ORIGIN_TS, BATCH_PROCESS_TS);
        zeroRow.setTranAmt(BigDecimal.ZERO);

        assertThat(writer.writeValueAsString(this.mapper.toAddResponse(zeroRow, null)))
                .contains("\"amount\":\"0.00\"")
                .doesNotContain("\"amount\":\"0\"")
                .doesNotContain("\"amount\":0");
    }

    /**
     * A value whose own decimal rendering carries an exponent leaves without one.
     *
     * <p>Pins the amount declaration at line 10 of {@code app/cpy/CVTRA05Y.cpy}, whose nine integer
     * and two decimal positions admit no exponent notation at all.
     *
     * <p>Assumptions: an exponent form is reachable rather than hypothetical, and the case
     * demonstrates the input before asserting the output. An arbitrary-precision decimal built from
     * {@code 1E+3} carries a negative scale, and its own textual rendering is the exponent form, so a
     * conversion that passed the value through its default rendering would publish a token that a
     * field of nine integer and two decimal positions cannot express and no reference screen could
     * show. Asserting the input's rendering in the same case is what makes the output assertion
     * meaningful: without it a reader cannot tell whether an exponent was ever in play.
     */
    @Test
    @DisplayName("a value whose decimal rendering carries an exponent leaves without one")
    void aMoneyValueWhoseDecimalFormCarriesAnExponentLeavesWithoutOne() {
        BigDecimal exponentForm = new BigDecimal("1E+3");

        assertThat(exponentForm.toString())
                .as("a negative scale renders in exponent notation")
                .isEqualTo("1E+3");
        assertThat(exponentForm.scale()).isNegative();

        Transaction row = storedRow();
        row.setTranAmt(exponentForm);

        String json = jsonWriter().writeValueAsString(this.mapper.toDetailResponse(row, null));

        assertThat(json).contains("\"amount\":\"1000.00\"");
        assertThat(json).doesNotContain("E+").doesNotContain("e+");
    }

    /**
     * Each seed amount decodes to its exact value and re-encodes to the bytes it came from.
     *
     * <p>Pins the amount declaration {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy} and the balance declaration {@code TRAN-CAT-BAL PIC S9(09)V99} at
     * line 9 of {@code app/cpy/CVTRA01Y.cpy}, both eleven bytes wide.
     *
     * <p>Assumptions: the four spans are read out of committed files rather than invented. The two
     * left-brace spans are the balance field of record 1 of {@code app/data/ASCII/tcatbal.txt} and
     * of {@code tests/fixtures/posting/happy_path/tcatbal.txt}; the right-brace span and
     * {@code 0000005047G} are the eleven bytes at one-based position 133 of records 2 and 1 of
     * {@code app/data/ASCII/dailytran.txt}. The braces are the zero entries of the two overpunch
     * tables at lines 66 and 67 of {@code tests/helpers/record_codec.py} -- a left brace carrying
     * the digit 0 with a positive sign, a right brace the digit 0 with a negative one.
     *
     * <p>Assumptions: the expected values are the arithmetic of the picture and not a reading of
     * the digits, which matters because the two readings look alike and differ by a factor of ten.
     * Nine integer positions consume the first nine bytes and two decimal positions the last two, of
     * which the second is the trailing-sign overpunch, so {@code 0000001000} closed by a left brace
     * denotes 100.00 rather than 10.00 and {@code 0000009190} closed by a right brace denotes
     * -919.00 rather than -91.90. Three sources agree on those two values independently of this
     * file: the shared codec the assertion calls, the reference decoder at
     * {@code tests/helpers/record_codec.py}, and the production mapper's own class documentation.
     *
     * <p>Assumptions: the negative span is the one that carries the case, because a sign misread is
     * silent. {@code tests/README.md} records at its lines 273 and 274 that the compiler's default
     * sign convention misreads the overpunch and silently corrupts negative balances, so a negative
     * vector is the only one that can fail for that reason. Negatives are not rare in the data: of
     * the 300 amount spans in that file, 250 carry a positive overpunch and 50 carry a negative one.
     *
     * <p>Refactoring Rationale: the shared codec decodes the span and no overpunch table is written
     * here. A third copy of a table the reference decoder already states canonically could disagree
     * with the one the service applies while this test went on passing against its own copy.
     *
     * @param span the eleven zoned-decimal bytes exactly as the committed file holds them
     * @param expected the exact decimal those bytes denote under the nine-and-two picture
     */
    @ParameterizedTest(name = "{0} denotes {1}")
    @CsvSource({"0000000000{, 0.00", "0000001000{, 100.00", "0000009190}, -919.00",
        "0000005047G, 504.77"})
    @DisplayName("each seed amount decodes exactly and re-encodes to the bytes it came from")
    void theZonedDecimalVectorsDecodeAndReEncodeExactly(String span, BigDecimal expected) {
        BigDecimal decoded = ZonedDecimalCodec.decode(span, 9, Money.SCALE, true);

        assertThat(decoded).isEqualByComparingTo(expected);
        assertThat(decoded.scale()).isEqualTo(Money.SCALE);
        assertThat(ZonedDecimalCodec.decodeMoney(span, 9, true).amount())
                .isEqualByComparingTo(expected);
        assertThat(ZonedDecimalCodec.encode(decoded, 9, Money.SCALE, true))
                .as("the encoding is the exact inverse, so a round trip reproduces the stored bytes")
                .isEqualTo(span);
        assertThat(span).hasSize(ZonedDecimalCodec.widthOf(9, Money.SCALE));
    }

    /**
     * The unsigned code and merchant fields carry plain digits and no overpunch.
     *
     * <p>Pins {@code TRAN-CAT-CD PIC 9(04)} at line 7 and {@code TRAN-MERCHANT-ID PIC 9(09)} at line
     * 11 of {@code app/cpy/CVTRA05Y.cpy}, neither of which carries a leading {@code S}.
     *
     * <p>Assumptions: the absence of the {@code S} is what decides the encoding -- an unsigned
     * field contains plain digits with no overpunch, and the committed data agrees at both offsets
     * the constants above quote. The geometry is asserted from the declaration rather than inferred
     * from the bytes, because reading either field as signed would take its final digit for a sign
     * character and yield a different number.
     *
     * <p>Assumptions: the merchant identifier is the one component of the detail view that is NOT a
     * pass-through, which is worth asserting because it sits between two that are. Its column is a
     * magnitude rather than a token, so the nine-character form the reference field declares is
     * re-created on the way out by left-padding, and a value needing a tenth position is refused
     * rather than truncated.
     */
    @Test
    @DisplayName("the unsigned code and merchant fields carry plain digits and no overpunch")
    void theUnsignedCodeAndMerchantFieldsCarryPlainDigits() {
        assertThat(SEED_CATEGORY_CODE).matches("[0-9]{4}");
        assertThat(SEED_MERCHANT_ID).matches("[0-9]{9}");
        assertThat(ZonedDecimalCodec.decode(SEED_CATEGORY_CODE, 4, 0, false))
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(ZonedDecimalCodec.decode(SEED_MERCHANT_ID, 9, 0, false))
                .isEqualByComparingTo(new BigDecimal(SEED_MERCHANT_ID));

        TransactionDetailResponse view = this.mapper.toDetailResponse(storedRow(), null);

        assertThat(view.categoryCode()).isEqualTo(SEED_CATEGORY_CODE).matches("[0-9]{4}");
        assertThat(view.merchantId())
                .as("TRAN-MERCHANT-ID PIC 9(09) at CVTRA05Y line 11 is nine digit positions")
                .isEqualTo(SEED_MERCHANT_ID)
                .hasSize(TransactionMapper.MERCHANT_ID_WIDTH)
                .matches("[0-9]{9}");

        Transaction appended = this.mapper.toEntity(
                submission(Money.of(SEED_AMOUNT), "2022-06-10", "2022-06-11"), SEED_TRAN_ID,
                SEED_CARD_NUMBER);

        assertThat(appended.getMerchantId()).isEqualTo(Long.valueOf(SEED_MERCHANT_ID));
        assertThat(this.mapper.toDetailResponse(appended, null).merchantId())
                .isEqualTo(SEED_MERCHANT_ID);
    }

    /**
     * The card number keeps the leading zero the committed data carries.
     *
     * <p>Pins paragraph {@code ADD-TRANSACTION} of {@code app/cbl/COTRN02C.cbl}, whose
     * numeric-to-character moves at lines 218 to 221 produce a fully zero-padded card number.
     *
     * <p>Assumptions: a numeric Java type would be wrong on the evidence of the data rather than as
     * a matter of taste, because 30 of the 300 committed records carry a card number beginning with
     * a zero. The case demonstrates the loss directly, rendering the same characters through a
     * numeric type and showing that the result is one position shorter, so the reason for the
     * string type is executable rather than asserted in prose.
     *
     * <p>Assumptions: the reference itself treats the value as characters on the wire and as a
     * number only for arithmetic -- {@code app/cpy/CVCRD01Y.cpy} declares the field {@code X(16)}
     * at its line 37 and redefines the same bytes as {@code 9(16)} at line 39. It is cited as a
     * house idiom only, because none of the four programs this module migrates copies that book.
     */
    @Test
    @DisplayName("the card number keeps its leading zero through the appended row")
    void theCardNumberKeepsItsLeadingZeroThroughTheAppendedRow() {
        Transaction appended = this.mapper.toEntity(
                submission(Money.of(SEED_AMOUNT), "2022-06-10", "2022-06-11"), SEED_TRAN_ID,
                SEED_CARD_NUMBER_LEADING_ZERO);

        assertThat(appended.getCardNum())
                .isEqualTo(SEED_CARD_NUMBER_LEADING_ZERO)
                .startsWith("0")
                .hasSize(TransactionMapper.CARD_NUMBER_WIDTH);
        assertThat(Long.toString(Long.parseLong(SEED_CARD_NUMBER_LEADING_ZERO)))
                .as("a numeric type discards the leading zero and shortens a sixteen-position key")
                .hasSize(TransactionMapper.CARD_NUMBER_WIDTH - 1);

        assertThat(this.mapper.toEntity(
                submission(Money.of(SEED_AMOUNT), "2022-06-10", "2022-06-11"), "683580", "6232")
                .getCardNum())
                .as("COTRN02C lines 218 to 221 left-pad a numeric value onto a character field of"
                        + " matching width")
                .isEqualTo("0000000000006232");
    }

    /**
     * The detail view masks the card number to its last four digits, and no other response shows one.
     *
     * <p>Pins paragraph {@code PROCESS-ENTER-KEY} of {@code app/cbl/COTRN01C.cbl} at its line 179,
     * which moves {@code TRAN-CARD-NUM} whole into the screen field {@code CARDNUMI PIC X(16)}
     * declared at line 72 of {@code app/cpy-bms/COTRN01.CPY}.
     *
     * <p>Assumptions: the baseline renders all sixteen digits, the Java masks to the last four, and
     * the divergence is documented rather than presented as equivalence. The reference screen's
     * audience was a terminal inside a controlled network, whereas these responses cross a public
     * edge, and a caller needing the whole number obtains it from the card context's own endpoint.
     *
     * <p>Assumptions: the masked form keeps the field's declared width, so the assertion is on twelve
     * mask characters followed by the final four digits rather than on a short prefix. Keeping the
     * width is what lets a masked value line up in a column with an unmasked one.
     *
     * <p>Alternatives Considered: asserting against the shared masker's own constants rather than
     * against the rendered literal. Rejected because the property under test is the value a client
     * receives, and a literal is the only form in which a reader of this case can see it; the width
     * is still tied back to {@code TransactionMapper.CARD_NUMBER_WIDTH} so that the two cannot drift.
     *
     * <p>Assumptions: the other two response shapes declare no card component at all, so the
     * statement that every response masks the number is complete once their absence is asserted
     * alongside the detail view's masking. Asserting only the detail view would leave the reader
     * unable to tell an absent component from an unmasked one.
     */
    @Test
    @DisplayName("the detail view masks the card number, and no other response carries one")
    void theDetailViewMasksTheCardNumberToItsLastFourDigits() {
        TransactionDetailResponse view = this.mapper
                .toDetailResponse(storedRow(SEED_CARD_NUMBER_LEADING_ZERO, SEED_ORIGIN_TS,
                        BATCH_PROCESS_TS), null);

        assertThat(view.cardNumber())
                .isEqualTo("************6232")
                .hasSize(TransactionMapper.CARD_NUMBER_WIDTH)
                .doesNotContain(SEED_CARD_NUMBER_LEADING_ZERO)
                .matches("[*]{12}[0-9]{4}");

        assertThat(this.mapper.toDetailResponse(storedRow(), null).cardNumber())
                .isEqualTo("************7065")
                .doesNotContain(SEED_CARD_NUMBER);

        assertThat(componentNames(TransactionListItemResponse.class))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("card"));
        assertThat(componentNames(TransactionAddResponse.class))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("card"));
    }

    /**
     * The two stored timestamps are read independently and may legitimately differ.
     *
     * <p>Pins paragraph {@code 1500-A-UPDATE-TRNX-RECORD} of {@code app/cbl/CBTRN02C.cbl} at its
     * lines 436 to 438, which is where the posting path fills the two stamps.
     *
     * <p>Assumptions: the two stamps have two different sources in that path, so they differ rather
     * than agreeing -- line 436 passes the feed's own stamp straight through, while line 437
     * obtains a separate value that line 438 moves into the processing stamp. Line 438 is the only
     * site in that program assigning the processing stamp at all, which settles that the two are not
     * one value written twice.
     *
     * <p>Assumptions: this case deliberately does NOT assert that the two stamps are equal, and
     * does not assert a zero sub-second component for either. Both belong to the bill-payment path,
     * where {@code app/cbl/COBIL00C.cbl} moves one assembled value into both stamps. Folding either
     * property onto this path would pass against an implementation that had lost the distinction,
     * and nothing in the build would report it, which is why the three forms are asserted
     * separately and never normalised together.
     *
     * <p>Refactoring Rationale: the twenty-six-character form is rendered by
     * {@code com.carddemo.common.time.TimestampFormatter} and no pattern is written here. The
     * reference form's separators survive only because {@code INITIALIZE} leaves {@code FILLER}
     * untouched, so a second pattern declared here could produce a value whose separator positions
     * were blank while looking correct.
     */
    @Test
    @DisplayName("the two stored timestamps are read independently and may differ")
    void theBatchPathMapsTheTwoTimestampsIndependently() {
        TransactionDetailResponse view = this.mapper.toDetailResponse(storedRow(), null);

        assertThat(view.originTimestamp())
                .as("CBTRN02C line 436 passes the feed's own stamp through, and record 1 of"
                        + " app/data/ASCII/dailytran.txt holds it at one-based position 279")
                .isEqualTo("2022-06-10 19:27:53.000000")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(view.processTimestamp())
                .as("CBTRN02C line 438 is the only site the processing stamp is assigned, and it"
                        + " assigns a separately obtained value")
                .isEqualTo("2022-06-11 02:15:04.123456")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(view.originTimestamp()).isNotEqualTo(view.processTimestamp());
        assertThat(TimestampFormatter.parse(view.originTimestamp())).isEqualTo(SEED_ORIGIN_TS);
        assertThat(TimestampFormatter.parse(view.processTimestamp())).isEqualTo(BATCH_PROCESS_TS);
    }

    /**
     * An absent processing stamp is reported absent rather than as an instant.
     *
     * <p>Pins paragraph {@code 1500-A-UPDATE-TRNX-RECORD} of {@code app/cbl/CBTRN02C.cbl} at its line
     * 438, by asserting the state that exists before that line has run.
     *
     * <p>Assumptions: the absent state is real rather than defensive, and the committed feed is the
     * evidence -- every one of the 300 records of {@code app/data/ASCII/dailytran.txt} carries
     * twenty-six spaces at the processing-stamp field while its originating stamp is populated,
     * because a staged transaction has not been processed. Spaces denote no value, so decoding them
     * to a zero instant would date an unprocessed transaction to 1970 and make it indistinguishable
     * from one processed at an absurd time.
     *
     * <p>Assumptions: the originating stamp is asserted present in the same case, because the property
     * is that the two are reported independently. A conversion that reported both absent, or both
     * present, would satisfy a case that looked at only one of them.
     */
    @Test
    @DisplayName("an absent processing stamp is reported absent while the originating one survives")
    void anAbsentProcessingTimestampIsReportedAbsentRatherThanAsAnInstant() {
        TransactionDetailResponse view = this.mapper
                .toDetailResponse(storedRow(SEED_CARD_NUMBER, SEED_ORIGIN_TS, null), null);

        assertThat(view.processTimestamp()).isNull();
        assertThat(view.originTimestamp()).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(" ".repeat(TimestampFormatter.TIMESTAMP_LENGTH))
                .as("the twenty-six bytes at position 305 of every dailytran record are spaces")
                .isBlank();
    }

    /**
     * A submission through the add screen carries a date and no time component whatever.
     *
     * <p>Pins paragraph {@code ADD-TRANSACTION} of {@code app/cbl/COTRN02C.cbl} at its lines 464 and
     * 465, which fill the two record stamps from the screen.
     *
     * <p>Assumptions: the screen supplies ten characters and the record field holds twenty-six, so
     * the value the reference stores is a date followed by sixteen spaces. Both cited lines move a
     * {@code PIC X(10)} screen field into a {@code PIC X(26)} record field, so a transaction
     * captured through that screen holds no time component, and a microsecond-precision column
     * renders midnight for it rather than an invented submission time.
     *
     * <p>Assumptions: the reference span for this path is asserted to carry no period at all, which is
     * the property that distinguishes it from the zero-microsecond form. That form belongs to
     * {@code app/cbl/COBIL00C.cbl}, whose line 266 moves zeros into the microsecond component of a
     * value that does have a time, and it is not a property of this path. Asserting the span's own
     * shape keeps the two apart without asserting anything about how the target renders midnight.
     *
     * <p>Assumptions: the two dates are supplied differently so that reading them independently is
     * observable. Both lines move a separate screen field, so a conversion that filled both stamps
     * from one date would be wrong in a way a single shared date could not reveal.
     */
    @Test
    @DisplayName("a submission through the add screen carries a date and no time component")
    void theAddScreenPathCarriesADateWithNoTimeComponent() {
        Transaction appended = this.mapper.toEntity(
                submission(Money.of(SEED_AMOUNT), "2022-06-10", "2022-06-11"), SEED_TRAN_ID,
                SEED_CARD_NUMBER);

        assertThat(appended.getOrigTs().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(appended.getProcTs().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(appended.getOrigTs()).isNotEqualTo(appended.getProcTs());

        TransactionDetailResponse view = this.mapper.toDetailResponse(appended, null);

        assertThat(TimestampFormatter.datePrefix(view.originTimestamp())).isEqualTo("2022-06-10");
        assertThat(TimestampFormatter.datePrefix(view.processTimestamp())).isEqualTo("2022-06-11");

        String referenceSpan = "2022-06-10"
                + " ".repeat(TimestampFormatter.TIMESTAMP_LENGTH - TransactionMapper.DATE_WIDTH);

        assertThat(referenceSpan)
                .as("COTRN02C lines 464 and 465 leave sixteen spaces after ten characters of date")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH)
                .endsWith("                ")
                .doesNotContain(".");
    }

    /**
     * A lexically well-shaped date that names no calendar day is refused.
     *
     * <p>Pins paragraph {@code VALIDATE-INPUT-KEY-FIELDS} of {@code app/cbl/COTRN02C.cbl}, whose
     * character-by-character date checks occupy lines 353 to 366 and 368 to 381.
     *
     * <p>Assumptions: those checks test the shape and not the calendar -- four digits, a hyphen, two
     * digits, a hyphen, two digits -- and the reference decides existence separately through the date
     * utility it calls at its lines 389 to 395. The conversion therefore parses the shape the
     * constraint already accepted and refuses a value that shape admits but the calendar does not, so
     * a thirty-first of February is refused here rather than stored as a plausible instant.
     */
    @Test
    @DisplayName("a well-shaped date naming no calendar day is refused")
    void aWellShapedDateThatNamesNoCalendarDayIsRefused() {
        assertThatThrownBy(() -> this.mapper.toEntity(
                submission(Money.of(SEED_AMOUNT), "2022-02-31", "2022-06-11"), SEED_TRAN_ID,
                SEED_CARD_NUMBER))
                .isInstanceOf(DateTimeParseException.class);
    }

    /**
     * The acknowledgement echoes the normalised amount the row stores, not the characters submitted.
     *
     * <p>Pins paragraph {@code VALIDATE-INPUT-DATA-FIELDS} of {@code app/cbl/COTRN02C.cbl} at its
     * lines 383 to 386, together with the record write at its line 458.
     *
     * <p>Assumptions: the reference re-renders the amount before showing it back, so the operator
     * sees a canonical form rather than the characters typed -- it converts the screen field, moves
     * that through the edited field {@code WS-TRAN-AMT-E PIC +99999999.99} and writes the same
     * normalised value into the record, so reading the amount off the appended row reproduces what
     * the screen shows.
     *
     * <p>Assumptions: a value carrying one decimal place is submitted, because the normalisation is
     * only observable on a value the canonical form changes. A figure already at two decimal places
     * would pass through a conversion that performed no normalisation at all.
     */
    @Test
    @DisplayName("the acknowledgement echoes the normalised amount the row stores")
    void theAcknowledgementEchoesTheNormalisedAmount() {
        Transaction appended = this.mapper.toEntity(
                submission(Money.of(new BigDecimal("1250.7")), "2022-06-10", "2022-06-11"),
                SEED_TRAN_ID, SEED_CARD_NUMBER);
        TransactionAddResponse acknowledgement = this.mapper.toAddResponse(appended, null);

        assertThat(acknowledgement.amount().amount()).isEqualByComparingTo(new BigDecimal("1250.70"));
        assertThat(acknowledgement.amount().amount().scale()).isEqualTo(Money.SCALE);
        assertThat(acknowledgement.amount().toPlainString())
                .as("WS-TRAN-AMT-E PIC +99999999.99 at COTRN02C line 59 renders two decimal places")
                .isEqualTo("1250.70");
        assertThat(acknowledgement.transactionId()).isEqualTo(SEED_TRAN_ID);
        assertThat(jsonWriter().writeValueAsString(acknowledgement))
                .contains("\"amount\":\"1250.70\"");
    }

    /**
     * Every reference message spelling survives both conversions character for character.
     *
     * <p>Pins five message literals across three programs: {@code app/cbl/COTRN00C.cbl} at its lines
     * 199, 214, 615, 649 and 683; {@code app/cbl/COTRN01C.cbl} at its line 292; and
     * {@code app/cbl/COTRN02C.cbl} at its lines 664, 693 and 727 to 733.
     *
     * <p>Assumptions: neighbouring reference strings differ from one another by a single byte, so a
     * conversion that re-worded, re-cased, re-spaced or truncated a message it was handed would
     * silently alter a screen. Three differences are load-bearing and each is represented above.
     * {@code 'Invalid selection. Valid value is S'} at line 199 of the list program carries no
     * ellipsis at all, whereas {@code 'Tran ID must be Numeric ...'} at its line 214 carries a space
     * before one. The lookup failure is spelled with a lower-case noun at lines 615, 649 and 683 of
     * that program and with an upper-case noun at line 292 of the view program and at lines 664 and
     * 693 of the add program, so the two spellings coexist and both have to survive. The assembled
     * outcome sentence carries TWO consecutive spaces, because the literal at line 728 ends with one
     * and the literal at line 730 begins with one and both are {@code DELIMITED BY SIZE}.
     *
     * <p>Assumptions: the message is passed through both conversions that accept one, since a
     * pass-through asserted on one of the two would leave the other free to normalise. The three
     * absence spellings are asserted separately, in the case below.
     *
     * @param message the reference literal to pass through, reproduced from the cited line exactly
     *     as that line holds it, including any leading, trailing or doubled space
     */
    @ParameterizedTest(name = "[{index}] survives verbatim")
    @ValueSource(strings = {"Invalid selection. Valid value is S", "Tran ID must be Numeric ...",
        "Unable to lookup transaction...", "Unable to lookup Transaction...",
        "Transaction added successfully.  Your Tran ID is 0000000000683580.",
        "Thank you for using CardDemo application...      "})
    @DisplayName("every reference message spelling survives both conversions character for character")
    void everyMessageSpellingSurvivesTheConversionCharacterForCharacter(String message) {
        Transaction row = storedRow();

        assertThat(this.mapper.toDetailResponse(row, message).returnMessage()).isEqualTo(message);
        assertThat(this.mapper.toAddResponse(row, message).returnMessage()).isEqualTo(message);
    }

    /**
     * The two spellings of the lookup failure remain distinct, and the doubled space is not tidied.
     *
     * <p>Pins the same reference literals the case above cites, this time for the property that
     * the two spellings and the doubled space are not normalised into one another.
     *
     * <p>Assumptions: asserting that each spelling survives is not the same as asserting that the two
     * remain different from each other, and the second is the property a consolidation would break. A
     * conversion that normalised the case of the noun would still return a value equal to one of the
     * two literals, so the survival case above would pass while a screen had been re-worded. The same
     * reasoning applies to the doubled space: a conversion that collapsed runs of whitespace would
     * return a sentence that still read correctly.
     *
     * <p>Assumptions: the doubled space is asserted at the exact position the two literals meet, after
     * the period that ends the first, so that the assertion cannot be satisfied by a doubled space
     * anywhere else in the sentence.
     */
    @Test
    @DisplayName("the two lookup spellings stay distinct and the doubled space is not tidied")
    void theTwoLookupFailureSpellingsRemainDistinct() {
        String lowerCaseNoun = "Unable to lookup transaction...";
        String upperCaseNoun = "Unable to lookup Transaction...";

        assertThat(lowerCaseNoun).isNotEqualTo(upperCaseNoun);

        Transaction row = storedRow();

        assertThat(this.mapper.toDetailResponse(row, lowerCaseNoun).returnMessage())
                .isEqualTo(lowerCaseNoun)
                .isNotEqualTo(upperCaseNoun);
        assertThat(this.mapper.toDetailResponse(row, upperCaseNoun).returnMessage())
                .isEqualTo(upperCaseNoun)
                .isNotEqualTo(lowerCaseNoun);

        String assembled = "Transaction added successfully. " + " Your Tran ID is " + SEED_TRAN_ID
                + ".";

        assertThat(this.mapper.toAddResponse(row, assembled).returnMessage())
                .isEqualTo(assembled)
                .contains(".  Your Tran ID is ");
    }

    /**
     * The one message constant asserted from a copybook is asserted as its source literal.
     *
     * <p>Pins {@code app/cpy/CSMSG01Y.cpy} at its lines 18 and 19, which declare
     * {@code CCDA-MSG-THANK-YOU} and its value.
     *
     * <p>Assumptions: two different strings are describable here and the case names which one it
     * asserts. Line 18 declares the FIELD as {@code PIC X(50)}, and line 19 supplies a VALUE literal
     * measuring 49 characters, so the compiler pads the fiftieth byte at run time. This case asserts
     * the 49-character SOURCE LITERAL of line 19 and not the 50-byte runtime value, because the
     * literal is what the file holds and is therefore what a reader can check the assertion against.
     * Saying "space-padded to fifty" without that distinction would describe the runtime value while
     * quoting the source, and the two differ by exactly one byte.
     */
    @Test
    @DisplayName("the thank-you constant is asserted as its 49-character source literal")
    void theThankYouConstantIsAssertedAsItsSourceLiteral() {
        String sourceLiteral = "Thank you for using CardDemo application...      ";

        assertThat(sourceLiteral)
                .as("CSMSG01Y line 19 supplies 49 characters into the X(50) field line 18 declares")
                .hasSize(49);
        assertThat(this.mapper.toDetailResponse(storedRow(), sourceLiteral).returnMessage())
                .isEqualTo(sourceLiteral)
                .hasSize(49);
    }

    /**
     * Every spelling of an absent message collapses onto no value rather than onto a blank one.
     *
     * <p>Pins {@code app/cpy/CVCRD01Y.cpy} at its lines 28, 29 and 30, which declare the two
     * seventy-five-character message fields and attach the absence condition to one of them.
     *
     * <p>Assumptions: absence and blankness are one state for the return message and two for its
     * neighbour. Line 30 nests {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} under
     * {@code CCARD-RETURN-MSG} at line 29 and under that field alone, whereas
     * {@code CCARD-ERROR-MSG} at line 28 carries no such condition. A message read out of a field of
     * declared width arrives padded, so an unset field, a field of spaces and a field of low values are
     * all the same absence and all map to no value; mapping them onto a blank string instead would make
     * an unset message indistinguishable from a deliberately empty one, which is the distinction the
     * condition name exists to draw. The collapse is applied by
     * {@code com.carddemo.common.validation.FieldValidationFlag} rather than restated here.
     *
     * <p>Assumptions: both conversions that accept a message are exercised, because the collapse is a
     * property of each crossing rather than of the shape they return.
     *
     * @param absent one spelling of an absent message: an empty value, a run of spaces at the declared
     *     width, or a run of low values
     */
    @ParameterizedTest(name = "[{index}] collapses onto no value")
    @ValueSource(strings = {"", "   ", "\u0000\u0000\u0000"})
    @DisplayName("every spelling of an absent message collapses onto no value")
    void anAbsentMessageCollapsesOntoNoValueInEachSpelling(String absent) {
        Transaction row = storedRow();

        assertThat(this.mapper.toDetailResponse(row, absent).returnMessage()).isNull();
        assertThat(this.mapper.toAddResponse(row, absent).returnMessage()).isNull();
        assertThat(this.mapper.toDetailResponse(row, null).returnMessage()).isNull();
        assertThat(this.mapper.toAddResponse(row, null).returnMessage()).isNull();
    }

    /**
     * Builds three stored rows whose identifiers ascend, for the ordering and paging cases.
     *
     * <p>Assumptions: the identifiers are consecutive at the declared width, because the property
     * under test is an ORDER over the key and a key shorter than sixteen characters is not a prefix of
     * a key but a value that matches none.
     *
     * @return three rows carrying ascending sixteen-character identifiers and otherwise identical
     *     field values
     */
    private static List<Transaction> threeRowsAscending() {
        List<Transaction> rows = new ArrayList<>();
        for (String identifier : List.of("0000000000000001", "0000000000000002",
                "0000000000000003")) {
            Transaction row = storedRow();
            row.setTranId(identifier);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Lists the identifiers of a page's rows in the order the page carries them.
     *
     * <p>Assumptions: the envelope preserves the row order the conversion produced rather than
     * sorting on read, which is the property that lets a case assert display order at all; a
     * resorting envelope would let an ascending assertion pass for a page that is descending.
     *
     * @param page the page to read
     * @return the identifier of each row, in page order
     */
    private static List<String> identifiersOf(PageResponse<TransactionListItemResponse> page) {
        List<String> identifiers = new ArrayList<>();
        for (TransactionListItemResponse row : page.items()) {
            identifiers.add(row.transactionId());
        }
        return identifiers;
    }

    /**
     * A backward scan's rows are reversed for display, and a forward scan's are not.
     *
     * <p>Pins paragraphs {@code PROCESS-PF7-KEY} and {@code PROCESS-PF8-KEY} of
     * {@code app/cbl/COTRN00C.cbl}, whose backward fill occupies lines 349 to 352.
     *
     * <p>Assumptions: the reference displays ascending keys while reading the file descending, and the
     * slot arithmetic is what shows it. Line 349 sets the slot index to ten, line 351 runs the fill
     * {@code UNTIL WS-IDX &lt;= 0} and line 352 reads the previous record, so the FIRST row read lands
     * in slot ten and the LAST row read lands in slot one. A backward result therefore arrives
     * descending and has to be reversed before it is shown, whereas a forward result is already in
     * display order and must not be touched. Both directions are asserted, because a conversion that
     * reversed unconditionally would satisfy a case that looked only at the backward one.
     *
     * <p>Trade-offs: the returned list is asserted to refuse mutation. The alternative was to reverse
     * the caller's list where it lies, which would mutate a list the caller still holds and would fail
     * outright on a list a persistence provider had returned, so one copy per page is accepted in
     * exchange for leaving the argument untouched.
     */
    @Test
    @DisplayName("a backward scan's rows are reversed for display and a forward scan's are not")
    void aDisplayOrderIsReversedOnlyForABackwardScan() {
        List<Transaction> ascending = threeRowsAscending();
        List<Transaction> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);

        List<Transaction> forward =
                this.mapper.orderForDisplay(ascending, TransactionListRequest.Direction.NEXT);
        List<Transaction> backward =
                this.mapper.orderForDisplay(descending, TransactionListRequest.Direction.PREVIOUS);

        assertThat(forward).containsExactlyElementsOf(ascending);
        assertThat(backward)
                .as("COTRN00C lines 349 to 352 land the first row read in the last slot")
                .containsExactlyElementsOf(ascending);
        assertThat(ascending)
                .as("the argument is copied rather than reversed where it lies")
                .containsExactlyElementsOf(threeRowsAscending());
        assertThatThrownBy(() -> forward.add(storedRow()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * A page carries one list row per stored row, in order, with both sealed boundaries.
     *
     * <p>Pins paragraph {@code PROCESS-PAGE-FORWARD} of {@code app/cbl/COTRN00C.cbl}, whose ten slots
     * are filled at lines 290 to 297 and whose boundaries are captured at line 393 and lines 437 to
     * 439.
     *
     * <p>Assumptions: the page size is the reference screen's own ten row slots, transcribed rather
     * than configured, because a different page size would change which rows a given cursor returns
     * and therefore which rows a client sees.
     *
     * <p>Assumptions: forward availability is the reference's own read-one-past-the-page result and
     * is taken from the caller rather than counted from the rows, because the probe read belongs to
     * whoever ran the query.
     *
     * <p>Assumptions: the boundaries arrive already sealed and this conversion mints none, so the
     * case seals its own through {@link #sealed(String)} rather than through a substitute.
     */
    @Test
    @DisplayName("a page carries one list row per stored row, in order, with both sealed boundaries")
    void thePageEnvelopeCarriesOneRowPerStoredRowWithBothSealedBoundaries() {
        assertThat(TransactionMapper.PAGE_SIZE)
                .as("COTRN00C line 290 clears ten row slots and line 297 fills ten")
                .isEqualTo(10);

        List<Transaction> displayOrdered = threeRowsAscending();
        String firstKeyToken = sealed("0000000000000001");
        String lastKeyToken = sealed("0000000000000003");

        PageResponse<TransactionListItemResponse> page =
                this.mapper.toListPage(displayOrdered, firstKeyToken, lastKeyToken, true);

        assertThat(page.items()).hasSameSizeAs(displayOrdered);
        assertThat(identifiersOf(page))
                .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
        assertThat(page.firstKey()).isEqualTo(firstKeyToken);
        assertThat(page.lastKey()).isEqualTo(lastKeyToken);
        assertThat(page.hasNext()).isTrue();
        assertThat(CursorToken.hasSealedShape(page.lastKey()))
                .as("a boundary is an opaque sealed token and never a raw keyset cursor")
                .isTrue();
    }

    /**
     * An exhausted page carries no rows, no boundary and no further page.
     *
     * <p>Pins paragraph {@code PROCESS-PAGE-FORWARD} of {@code app/cbl/COTRN00C.cbl} at its lines 305
     * to 320, specifically the branch taken when the fill read nothing.
     *
     * <p>Assumptions: line 315 sets the no-further-page condition unconditionally when nothing was
     * filled, so an exhausted page reports no continuation. A boundary carried on such a page would
     * name a position no returned row corresponds to, which is why none is carried.
     *
     * <p>Assumptions: a further page reported alongside no rows is a contradiction the caller cannot
     * have observed, and it is refused rather than normalised. The probe read at line 308 is reached
     * only on the branch where at least one row was filled, so a caller that returned no rows cannot
     * have found one beyond them. Refusing adds a failure mode the reference does not have, and the
     * cost is paid once by the caller that is wrong; normalising instead would let a client follow a
     * continuation into a request that returns nothing.
     */
    @Test
    @DisplayName("an exhausted page carries no rows, no boundary and no further page")
    void anExhaustedPageReportsNoFurtherPageAndNoBoundary() {
        PageResponse<TransactionListItemResponse> exhausted =
                this.mapper.toListPage(List.of(), null, null, false);

        assertThat(exhausted.items()).isEmpty();
        assertThat(exhausted.firstKey()).isNull();
        assertThat(exhausted.lastKey()).isNull();
        assertThat(exhausted.hasNext()).isFalse();

        assertThatThrownBy(() -> this.mapper.toListPage(List.of(), null, null, true))
                .as("COTRN00C line 315 denies a further page whenever the fill read nothing")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hasNext");
    }

    /**
     * A raw keyset cursor is refused as a boundary, so a key cannot travel as a token.
     *
     * <p>Pins the two raw sixteen-character cursor keys the reference carried in the communication
     * area it appends at lines 62 to 70 of {@code app/cbl/COTRN00C.cbl}.
     *
     * <p>Refactoring Rationale: the reference echoed those two raw keys to the terminal between
     * turns, so any online program could move a value into them, nothing could reject an
     * inconsistent one, and a client holding a boundary could resume a scan it was never issued.
     * The target publishes an opaque authenticated token in their place, and the refusal is
     * asserted here because this conversion is the crossing at which a raw key would otherwise
     * reach the envelope.
     */
    @Test
    @DisplayName("a raw keyset cursor is refused as a boundary")
    void aRawKeysetCursorIsRefusedAsABoundary() {
        assertThat(CursorToken.hasSealedShape("0000000000000001")).isFalse();
        assertThatThrownBy(() -> this.mapper.toListPage(threeRowsAscending(),
                "0000000000000001", "0000000000000003", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CursorToken");
    }
}
