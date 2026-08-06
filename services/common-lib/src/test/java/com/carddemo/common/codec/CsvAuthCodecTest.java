package com.carddemo.common.codec;

import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.OpaqueIdentifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link CsvAuthCodec} to the exact bytes the reference authorization program consumes and
 * emits, using golden vectors rather than round trips alone.
 *
 * <p>Assumptions: the reference for every expectation here is
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} together with the two payload copybooks
 * {@code CCPAURQY.cpy} and {@code CCPAURLY.cpy} beside it. Four statements in that program decide
 * everything this class asserts, and each is cited again at the test that depends on it: the
 * {@code UNSTRING ... DELIMITED BY ','} at lines 354 to 374, whose ordinal-nine receiver is
 * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63; the
 * {@code COMPUTE ... = FUNCTION NUMVAL(WS-TRANSACTION-AMT-AN)} at lines 376 to 377; the
 * {@code MOVE WS-APPROVED-AMT TO WS-APPROVED-AMT-DIS} at line 720 into the mask
 * {@code PIC -zzzzzzzzz9.99} declared at line 66; and the {@code STRING ... DELIMITED BY SIZE} at
 * lines 722 to 731 that joins the reply, whose cursor {@code WS-RESP-LENGTH PIC S9(4) VALUE 1} is
 * declared at line 46 and reused as a message length at lines 756 and 762.</p>
 *
 * <p>Assumptions: this contract is delimiter-positional and not byte-positional, so nothing in this
 * class addresses a field by a record offset. A field is located by counting delimiters, exactly as
 * the reference {@code UNSTRING} locates it, and the comma offsets asserted below are consequences of
 * the width table rather than addresses into a record. That is also why this class touches none of
 * the byte-positional machinery in the same package: a copybook layout descriptor and a fixed-width
 * codec address a record by offset, and inventing an offset for a payload that has none is the
 * silent-shift failure this contract cannot absorb.</p>
 *
 * <p>Assumptions: both payload copybooks begin at level {@code 05} and declare no {@code 01} root --
 * {@code CCPAURQY.cpy} at lines 19 to 36 and {@code CCPAURLY.cpy} at lines 19 to 24. They are
 * therefore caller-supplied fragments: the reference program copies each one INTO a working-storage
 * group it declares itself, at lines 178 and 182. The Java carriers stand for those fragments and not
 * for a record, so the absence of an {@code 01} declaration does not weaken either contract. What the
 * copybooks fix is the ordinal order of the fields and the width of each, and both survive the
 * translation intact.</p>
 *
 * <p>Alternatives Considered: asserting only that {@code decode(encode(x))} returns {@code x}. That
 * shape cannot fail on the two defects that matter, because both of them are symmetric: a codec that
 * emits a fourteen-character request amount also accepts one, and a codec that emits
 * {@code +0000000100.00} where COBOL emits {@code        100.00} also parses its own output. The
 * bytes are therefore written out as literals below -- one per sign and cents case -- and each
 * literal's length is asserted alongside its content, because a length mismatch and a content
 * mismatch have different causes and a combined assertion would hide which occurred.</p>
 *
 * <p>Trade-offs: the golden strings are written with explicit space runs rather than assembled from
 * a helper. A helper would make the file shorter and would also reproduce, in the test, the very
 * padding logic under test -- so a mistake in the production loop and the same mistake in the helper
 * would agree and the test would pass. Written out, the expectation is independent of the code it
 * checks. The cost accepted is that a reader must count spaces, so every literal is accompanied by
 * an explicit statement of its width and of what occupies each region.</p>
 *
 * <p>Trade-offs: every no-argument test below states its own inapplicable parameter and return
 * behaviour in one closing sentence rather than relying on this paragraph to cover the class. The
 * repetition is accepted because the project's single user-specified rule, Explainability, forbids a
 * docstring that omits parameters or return values, and a reader auditing one method has to be able
 * to settle the question inside that method's own block.</p>
 *
 * <p>Assumptions: no test here reads a clock, a file, an environment variable or a network resource.
 * Every expectation is a literal transcribed from the reference source, which is what lets this
 * class run with no database, no emulator and no COBOL compiler present.</p>
 *
 * <p>Parameters, return values and exceptions at type level: declared inapplicable. A class
 * declaration accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
class CsvAuthCodecTest {

    /** The card number these tests submit, and which must never appear in an output or a message. */
    private static final String CARD_NUM = "4111111111111111";

    /** The transaction identifier these tests submit, carrying leading zeros that must survive. */
    private static final String TRANSACTION_ID = "000000000000123";

    /** The copybook field name used when a request amount failure is diagnosed. */
    private static final String REQUEST_AMOUNT_FIELD = "PA-RQ-TRANSACTION-AMT";

    /** The copybook field name used when a reply amount failure is diagnosed. */
    private static final String REPLY_AMOUNT_FIELD = "PA-RL-APPROVED-AMT";

    /** Key material of the required width, fixed so that a token is reproducible across instances. */
    private static final byte[] KEY =
            "carddemo-opaque-id-key-for-tests".getBytes(StandardCharsets.UTF_8);

    /**
     * The eighteen request component names in the order {@code CCPAURQY.cpy} lines 19 to 36 declare
     * them.
     *
     * <p>Assumptions: these are the Java component names and the list order is the wire order, so a
     * component reordered on the carrier fails here rather than shifting every later field on the
     * wire. The tenth entry is spelled {@code merchantCategoryCode} while its copybook line 28 is
     * spelled {@code PA-RQ-MERCHANT-CATAGORY-CODE}; the correction is a rename of the Java identifier
     * only and moves nothing.</p>
     */
    private static final List<String> REQUEST_COMPONENT_NAMES = List.of(
            "authDate",
            "authTime",
            "cardNum",
            "authType",
            "cardExpiryDate",
            "messageType",
            "messageSource",
            "processingCode",
            "transactionAmount",
            "merchantCategoryCode",
            "acquirerCountryCode",
            "posEntryMode",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantState",
            "merchantZip",
            "transactionId");

    /**
     * The six reply component names in the order {@code CCPAURLY.cpy} lines 19 to 24 declare them.
     */
    private static final List<String> REPLY_COMPONENT_NAMES = List.of(
            "cardNum",
            "transactionId",
            "authIdCode",
            "authRespCode",
            "authRespReason",
            "approvedAmount");

    /**
     * The eighteen widths the request copybook DECLARES, with its ordinal-nine field at fourteen.
     *
     * <p>Assumptions: this table is the copybook's own reading of {@code CCPAURQY.cpy} lines 19 to 36,
     * including {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at line 27 as fourteen characters. It is
     * NOT what the codec emits, and holding both tables side by side is the point: the declared table
     * sums to 153 and the emitted table to 152, and a reader who met only one of them would conclude
     * the other was a defect.</p>
     */
    private static final List<Integer> COPYBOOK_REQUEST_WIDTHS =
            List.of(6, 6, 16, 4, 4, 6, 6, 6, 14, 4, 3, 2, 15, 22, 13, 2, 9, 15);

    /**
     * The zero-based position of each of the seventeen delimiters in an emitted request payload.
     *
     * <p>Assumptions: these are derived from the emitted width table, so the ninth entry onward each
     * sit one position earlier than the copybook's declared table would put them. The list is written
     * out rather than computed so that a change to any width fails against a fixed expectation
     * instead of against a formula that moved with it.</p>
     */
    private static final List<Integer> REQUEST_DELIMITER_POSITIONS =
            List.of(6, 13, 30, 35, 40, 47, 54, 61, 75, 80, 84, 87, 103, 126, 140, 143, 153);

    /**
     * The zero-based position of each of the six delimiters in an emitted reply payload.
     *
     * <p>Assumptions: the sixth entry, 62, is the trailing delimiter the reference {@code STRING}
     * pairs with the sixth value at line 727 of {@code COPAUA0C.cbl}. Its presence is what makes the
     * payload 63 characters rather than 62.</p>
     */
    private static final List<Integer> REPLY_DELIMITER_POSITIONS = List.of(16, 32, 39, 42, 47, 62);

    /**
     * A request payload whose every character field carries a distinguishable filler, so that a
     * misordered component cannot pass as a correctly ordered one.
     *
     * <p>Assumptions: each field is filled to exactly its declared width with a letter unique to its
     * ordinal, except the two digit-picture fields and the money field, which carry digit text because
     * that is what their pictures declare. Ordinal nine carries its forced {@code +} because
     * {@code PIC +9(10).99} spends its first position on a sign in every case, so a token without one
     * would be thirteen characters and the payload would be 169. A payload of realistic values could not
     * prove order at all: two six-character fields holding a date and a time are interchangeable to an
     * assertion that only checks widths, whereas {@code AAAAAA} in ordinal one and {@code BBBBBB} in
     * ordinal two cannot be swapped without the slice assertions failing.</p>
     */
    private static final String ORDERED_REQUEST_PAYLOAD =
            "AAAAAA,BBBBBB,CCCCCCCCCCCCCCCC,DDDD,EEEE,FFFFFF,GGGGGG,000008,0000000100.99,JJJJ,KKK,"
                    + "07,MMMMMMMMMMMMMMM,NNNNNNNNNNNNNNNNNNNNNN,OOOOOOOOOOOOO,PP,QQQQQQQQQ,"
                    + "RRRRRRRRRRRRRRR";

    /**
     * The canonical 63-character reply payload, transcribed from the reference {@code STRING}.
     *
     * <p>Assumptions: the sixth field is {@code "        100.99"} -- eight spaces then
     * {@code 100.99} -- which is {@code PIC -zzzzzzzzz9.99} at line 66 of {@code COPAUA0C.cbl} applied
     * to 100.99: a blank sign-control position, then seven suppressed digit positions, then the digits
     * and the two cents. The payload ends with a comma because the {@code STRING} at lines 722 to 731
     * pairs one with every value including the sixth.</p>
     */
    private static final String CANONICAL_REPLY_PAYLOAD =
            "4111111111111111,TRN000000000001,A00001,00,0000,        100.99,";

    /**
     * A request whose every character field is exactly its declared width, so that only the money
     * field varies between the vectors below.
     *
     * <p>Assumptions: the values are shaped like the baseline's rather than chosen freely -- a
     * six-character date and time as {@code PA-RQ-AUTH-DATE} and {@code PA-RQ-AUTH-TIME} declare, a
     * sixteen-digit card number as {@code PA-RQ-CARD-NUM} declares, and a fifteen-character
     * transaction identifier as {@code PA-RQ-TRANSACTION-ID} declares. Filling every field to its
     * width keeps the assertions below about the money token alone: no other field can contribute a
     * pad byte that shifts the comparison.</p>
     *
     * @param amount the {@link Money} value to place in the ordinal-nine position
     * @return an {@link AuthRequest} carrying {@code amount} and fully-populated character fields
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    private static AuthRequest requestWith(Money amount) {
        return new AuthRequest(
                "240715",
                "101530",
                "4111111111111111",
                "PURC",
                "1229",
                "AUTH01",
                "POS001",
                "000000",
                amount,
                "5411",
                "840",
                "05",
                "MERCHANT0000001",
                "ACME SUPERMARKET NO 12",
                "SEATTLE   WA ",
                "WA",
                "981010000",
                "TRN000000000001");
    }

    /**
     * A reply whose every character field is exactly its declared width, so that only the money field
     * varies between the vectors below.
     *
     * @param amount the {@link Money} value to place in the sixth position
     * @return an {@link AuthReply} carrying {@code amount} and fully-populated character fields
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    private static AuthReply replyWith(Money amount) {
        return new AuthReply(
                "4111111111111111",
                "TRN000000000001",
                "A00001",
                "00",
                "0000",
                amount);
    }

    /**
     * Builds a well-formed request whose card number and transaction identifier are the caller's.
     *
     * <p>Assumptions: every other component is a fixed, valid value inside its declared width, because
     * these tests are about the two identified fields and about payload handling. Fixing the rest keeps
     * each expectation about one variable.</p>
     *
     * @param cardNum the {@link String} card number to carry
     * @param transactionId the {@link String} transaction identifier to carry
     * @return the assembled {@link AuthRequest}
     * @throws NullPointerException if either parameter is {@code null}
     * @throws AuthMessageFormatException if either parameter contains a delimiter or exceeds its
     *     authorization width
     */
    private static AuthRequest request(String cardNum, String transactionId) {
        return new AuthRequest(
                "220718",
                "120000",
                cardNum,
                "A",
                "1231",
                "0100",
                "POS",
                "000",
                Money.of("1000.00"),
                "5411",
                "USA",
                "90",
                "MERCH000000001",
                "MERCHANT NAME",
                "CITY",
                "TX",
                "75001",
                transactionId);
    }

    /**
     * Builds a well-formed reply whose card number and transaction identifier are the caller's.
     *
     * <p>Assumptions: every other component is fixed inside its declared width so correlation tests
     * vary only the two values that form the private source identity. The amount is non-negative to
     * keep these tests independent of the separately documented negative reply parse asymmetry.</p>
     *
     * @param cardNum the {@link String} card number to carry
     * @param transactionId the {@link String} transaction identifier to carry
     * @return the assembled {@link AuthReply}
     * @throws NullPointerException if either parameter is {@code null}
     * @throws AuthMessageFormatException if either parameter contains a delimiter or exceeds its
     *     authorization width
     */
    private static AuthReply reply(String cardNum, String transactionId) {
        return new AuthReply(
                cardNum,
                transactionId,
                "A12345",
                "00",
                "0000",
                Money.of("1000.00"));
    }

    /**
     * Builds a request whose merchant-location fields are supplied by the caller.
     *
     * <p>Assumptions: every non-merchant component is fixed inside its authorization width so the
     * divergence tests isolate one ledger-versus-authorization boundary at a time. The transaction
     * identifier remains the authorization-width value and is tested independently.</p>
     *
     * @param merchantId the {@link String} authorization merchant identifier
     * @param merchantName the {@link String} authorization merchant name
     * @param merchantCity the {@link String} authorization merchant city
     * @param merchantState the {@link String} authorization merchant state
     * @param merchantZip the {@link String} authorization merchant postal code
     * @return the assembled {@link AuthRequest}
     * @throws NullPointerException if any parameter is {@code null}
     * @throws AuthMessageFormatException if any parameter contains a delimiter or exceeds its
     *     authorization width
     */
    private static AuthRequest requestWithMerchantFields(
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantState,
            String merchantZip) {
        return new AuthRequest(
                "240715",
                "101530",
                CARD_NUM,
                "PURC",
                "1229",
                "AUTH01",
                "POS001",
                "000000",
                Money.of("100.99"),
                "5411",
                "840",
                "05",
                merchantId,
                merchantName,
                merchantCity,
                merchantState,
                merchantZip,
                TRANSACTION_ID);
    }

    /**
     * Builds the request whose payload is {@link #ORDERED_REQUEST_PAYLOAD}.
     *
     * <p>Assumptions: the two digit-picture fields carry {@code 000008} and {@code 07} rather than a
     * letter run, because {@code PA-RQ-PROCESSING-CODE PIC 9(06)} at line 26 of {@code CCPAURQY.cpy}
     * and {@code PA-RQ-POS-ENTRY-MODE PIC 9(02)} at line 30 declare digit positions. Their values
     * begin with zeros deliberately: a codec that carried either as a number rather than as text would
     * emit {@code 8} and {@code 7} and shift every later field.</p>
     *
     * @return an {@link AuthRequest} whose every field is distinguishable from every other by content
     */
    private static AuthRequest orderedRequest() {
        return new AuthRequest(
                "AAAAAA",
                "BBBBBB",
                "CCCCCCCCCCCCCCCC",
                "DDDD",
                "EEEE",
                "FFFFFF",
                "GGGGGG",
                "000008",
                Money.of("100.99"),
                "JJJJ",
                "KKK",
                "07",
                "MMMMMMMMMMMMMMM",
                "NNNNNNNNNNNNNNNNNNNNNN",
                "OOOOOOOOOOOOO",
                "PP",
                "QQQQQQQQQ",
                "RRRRRRRRRRRRRRR");
    }

    /**
     * Supplies requests spanning the production-supported value and padding regimes.
     *
     * <p>Assumptions: negative money is included here because the request renderer and parser
     * round-trip it across the whole ten-digit integer domain the copybook's sign position admits.
     * The final case supplies trailing spaces deliberately; carrier construction normalizes them as
     * COBOL pad before the case enters the stream, while its leading and internal spaces remain data.
     * Parameterized display names use only the case index so no card number reaches a test report.</p>
     *
     * <p>This provider takes no parameter.</p>
     *
     * @return a {@link Stream} of {@link AuthRequest} values covering positive, negative and zero
     *     money, exact widths, leading zeros, alphanumeric merchant data, significant spaces and
     *     normalized trailing pad
     */
    private static Stream<AuthRequest> requestRoundTripCorpus() {
        return Stream.of(
                requestWith(Money.of("100.99")),
                requestWith(Money.of("-100.99")),
                requestWith(Money.ofCents(0L)),
                orderedRequest(),
                new AuthRequest(
                        "000001",
                        "000002",
                        "0000000000000001",
                        "AB C",
                        "0001",
                        "MSG001",
                        "SRC001",
                        "000008",
                        Money.of("12.30"),
                        "0A1B",
                        "001",
                        "07",
                        "0ABC00000000001",
                        " ACME  MARKET ",
                        " A B ",
                        "WA",
                        "012345678",
                        "ABC000000000001"));
    }

    /**
     * Supplies replies whose emitted money text is accepted by the real production parser.
     *
     * <p>Assumptions: the corpus includes positive and zero amounts plus exact-width, under-width,
     * leading-zero, alphanumeric and space-bearing identifiers. A negative reply is intentionally
     * excluded from this round-trip provider because the production formatter emits suppression
     * spaces after its minus while the production parser strips pad only around the whole token; the
     * separate negative-asymmetry test below records that real API boundary instead of hiding it.</p>
     *
     * <p>This provider takes no parameter.</p>
     *
     * @return a {@link Stream} of {@link AuthReply} values covering canonical positive and zero money
     *     plus diverse character-field shapes
     */
    private static Stream<AuthReply> replyRoundTripCorpus() {
        return Stream.of(
                replyWith(Money.of("100.99")),
                replyWith(Money.ofCents(0L)),
                replyWith(Money.of("9999999999.99")),
                new AuthReply(
                        "0000000000000001",
                        "ABC000000000001",
                        "A1",
                        "0",
                        "1",
                        Money.of("12.30")),
                new AuthReply(
                        " 4111 ",
                        " TX 1 ",
                        "A1 ",
                        "0 ",
                        "1 ",
                        Money.of("0.01")));
    }

    /**
     * Reads the record component names of a carrier in declaration order.
     *
     * <p>Assumptions: {@code Class.getRecordComponents} returns the components in the order the record
     * header declares them, which for these two carriers is the wire order. Reflection is used rather
     * than a second hand-written table because a table in the test could be reordered to match a
     * reordered carrier and the assertion would still pass.</p>
     *
     * @param carrier the {@code Class<?>} record type whose components are read; must be a record
     *     class
     * @return a {@link List} of {@link String} component names in declaration order
     */
    private static List<String> componentNamesOf(Class<?> carrier) {
        return Arrays.stream(carrier.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * Collects the zero-based position of every delimiter in a payload.
     *
     * @param payload the {@link String} emitted payload to scan
     * @return a {@link List} of {@link Integer} positions for each delimiter, in ascending order
     */
    private static List<Integer> delimiterPositionsOf(String payload) {
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < payload.length(); position++) {
            if (payload.charAt(position) == CsvAuthCodec.DELIMITER) {
                positions.add(position);
            }
        }
        return positions;
    }

    /**
     * Extracts one ordinal field from a payload by counting delimiters.
     *
     * <p>Assumptions: the field is located by splitting on the delimiter rather than by a byte offset,
     * because the reference intake is an {@code UNSTRING DELIMITED BY ','} and a delimited payload has
     * no offsets to address. Splitting with a negative limit keeps empty tokens, so a blank field
     * cannot silently shift the ordinals after it.</p>
     *
     * @param payload the {@link String} emitted payload to read
     * @param ordinal the {@code int} zero-based ordinal of the field to return
     * @return the {@link String} field as it appears on the wire, including any pad it carries
     */
    private static String fieldAt(String payload, int ordinal) {
        return payload.split(",", -1)[ordinal];
    }

    /**
     * Replaces one delimited field while preserving every delimiter, including a trailing one.
     *
     * <p>Assumptions: splitting with a negative limit retains the reply's final empty framing token,
     * so joining the array reproduces the original delimiter count. The helper changes exactly one
     * business field and leaves malformed-input tests able to attribute a rejection to that field
     * rather than to accidental structural drift in the test data.</p>
     *
     * @param payload the {@link String} request or reply payload to copy
     * @param ordinal the {@code int} zero-based business-field ordinal to replace
     * @param replacement the {@link String} text to place in that field
     * @return a {@link String} payload with only the selected field changed
     */
    private static String withField(String payload, int ordinal, String replacement) {
        String[] fields = payload.split(",", -1);
        fields[ordinal] = replacement;
        return String.join(",", fields);
    }

    /**
     * Asserts that a rendered money token carries no {@code +} character.
     *
     * <p>This helper returns no value; JUnit reports an assertion failure when the token contains a
     * plus sign.</p>
     *
     * @param rendered the rendered {@link String} token to inspect
     */
    private static void assertNoPlusSign(String rendered) {
        assertTrue(rendered.indexOf('+') < 0, "rendering must not emit '+', was '" + rendered + "'");
    }

    /**
     * The request carrier declares exactly eighteen components in the copybook's own order.
     *
     * <p>Assumptions: ordinal position is the whole of this contract, so the ORDER of the components
     * is load bearing in a way a field name never is. The reference {@code UNSTRING} at lines 354 to
     * 374 of {@code COPAUA0C.cbl} names its eighteen receivers in the order
     * {@code CCPAURQY.cpy} declares them at lines 19 to 36, and a component moved on the carrier would
     * move a value on the wire while every field still read as plausible text.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the request carrier declares eighteen components in copybook order")
    void requestCarrierDeclaresEighteenComponentsInCopybookOrder() {
        assertTrue(AuthRequest.class.isRecord(), "AuthRequest must be a record carrier");
        assertEquals(18, AuthRequest.class.getRecordComponents().length);
        assertEquals(REQUEST_COMPONENT_NAMES, componentNamesOf(AuthRequest.class));
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT, AuthRequest.class.getRecordComponents().length);
    }

    /**
     * The reply carrier declares exactly six components in the copybook's own order.
     *
     * <p>Assumptions: the six are {@code CCPAURLY.cpy} lines 19 to 24, and the reference
     * {@code STRING} at lines 722 to 731 of {@code COPAUA0C.cbl} emits them in that order. The two
     * leading components are the card number and the transaction identifier for a stated reason -- a
     * consumer pairs a reply with its request from them without decoding the rest -- so a reordering
     * of the first two would break correlation as well as parsing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply carrier declares six components in copybook order")
    void replyCarrierDeclaresSixComponentsInCopybookOrder() {
        assertTrue(AuthReply.class.isRecord(), "AuthReply must be a record carrier");
        assertEquals(6, AuthReply.class.getRecordComponents().length);
        assertEquals(REPLY_COMPONENT_NAMES, componentNamesOf(AuthReply.class));
        assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT, AuthReply.class.getRecordComponents().length);
    }

    /**
     * The component count, the declared field count, the width table and the name table all agree.
     *
     * <p>Assumptions: four independent declarations describe the same eighteen and the same six, and
     * any one of them changing alone is a defect rather than a refinement. Asserting them against each
     * other rather than each against a literal is what makes a partial edit fail: adding a component
     * without a width leaves the tables unequal even though each remains internally consistent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("carrier components, field counts, widths and names all agree")
    void carrierComponentCountsMatchTheDeclaredFieldCounts() {
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT, CsvAuthCodec.REQUEST_FIELD_WIDTHS.size());
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT, CsvAuthCodec.REQUEST_FIELD_NAMES.size());
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT, COPYBOOK_REQUEST_WIDTHS.size());
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT, REQUEST_COMPONENT_NAMES.size());

        assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT, CsvAuthCodec.REPLY_FIELD_WIDTHS.size());
        assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT, CsvAuthCodec.REPLY_FIELD_NAMES.size());
        assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT, REPLY_COMPONENT_NAMES.size());
    }

    /**
     * The two carriers and the failure type are declared inside the codec that owns the wire format.
     *
     * <p>Assumptions: nesting is part of the contract this class checks rather than a style choice.
     * The carriers exist to hold exactly the fields the two copybooks declare, in exactly that order,
     * and the failure type reports a violation of that same format, so a top-level carrier or a
     * top-level exception would be reusable somewhere the format does not apply. The failure type
     * extends {@link IllegalArgumentException} so that a consumer can route a malformed payload to a
     * dead-letter queue while still catching it with one type.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("carriers and the failure type are nested in the codec")
    void nestedCarriersAndFailureTypeBelongToTheCodec() {
        assertSame(CsvAuthCodec.class, AuthRequest.class.getEnclosingClass());
        assertSame(CsvAuthCodec.class, AuthReply.class.getEnclosingClass());
        assertSame(CsvAuthCodec.class, AuthMessageFormatException.class.getEnclosingClass());
        assertTrue(IllegalArgumentException.class.isAssignableFrom(AuthMessageFormatException.class));
    }

    /**
     * The eighteen declared widths sum to 153 while the eighteen emitted widths sum to 152.
     *
     * <p>Assumptions: 153 is {@code 6+6+16+4+4+6+6+6+14+4+3+2+15+22+13+2+9+15}, the arithmetic over
     * the pictures {@code CCPAURQY.cpy} declares at lines 19 to 36 with
     * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at line 27 counted as fourteen. 152 is the same
     * arithmetic with that one field counted as thirteen, which is the width of the RECEIVER the
     * reference program reads it into: {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63 of
     * {@code COPAUA0C.cbl}. An alphanumeric receiver truncates on the right, so a fourteen-character
     * token loses its final cent digit before {@code FUNCTION NUMVAL} runs at line 377 and 100.99
     * arrives as 100.9. Both sums are asserted so that neither figure can be mistaken for the
     * other's defect.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the request width tables sum to 153 declared and 152 emitted")
    void requestWidthTablesSumToTheDeclaredAndEmittedTotals() {
        assertEquals(153, COPYBOOK_REQUEST_WIDTHS.stream().mapToInt(Integer::intValue).sum());
        assertEquals(152, CsvAuthCodec.REQUEST_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum());
        assertEquals(CsvAuthCodec.REQUEST_DECLARED_WIDTH_SUM,
                CsvAuthCodec.REQUEST_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum());

        // WHY : Assumptions: the two tables are compared POSITION BY POSITION and not only by their
        //       totals, because the failure this guards against is one width borrowing a character
        //       from its neighbour. Equal sums would report such a pair as correct, and every field
        //       after the first of them would sit at the wrong offset on the wire.
        for (int ordinal = 0; ordinal < COPYBOOK_REQUEST_WIDTHS.size(); ordinal++) {
            if (ordinal == 8) {
                continue;
            }
            assertEquals(COPYBOOK_REQUEST_WIDTHS.get(ordinal),
                    CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(ordinal),
                    "the emitted widths must agree with the copybook at ordinal " + ordinal);
        }
        assertEquals(CsvAuthCodec.MONEY_EDITED_WIDTH, COPYBOOK_REQUEST_WIDTHS.get(8));
        assertEquals(CsvAuthCodec.REQUEST_MONEY_WIDTH, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(8));
        assertEquals(1, CsvAuthCodec.MONEY_EDITED_WIDTH - CsvAuthCodec.REQUEST_MONEY_WIDTH,
                "ordinal nine is the only position the two tables may differ at, and by one");
    }

    /**
     * The request payload is its emitted width sum plus seventeen interior delimiters.
     *
     * <p>Assumptions: 169 is {@code 152 + 17}, and 170 would be {@code 153 + 17} -- the length the
     * copybook's declared widths predict. The request carries seventeen delimiters and no trailing one
     * because the {@code UNSTRING} at line 354 of {@code COPAUA0C.cbl} names eighteen receiving fields
     * and therefore consumes only interior separators, which is the respect in which the request
     * differs from the reply.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the request payload is 169 characters with seventeen interior delimiters")
    void requestPayloadLengthMatchesTheEmittedWidths() {
        String payload = CsvAuthCodec.encodeRequest(requestWith(Money.of("100.99")));

        assertEquals(169, CsvAuthCodec.REQUEST_WIRE_LENGTH);
        assertEquals(152, CsvAuthCodec.REQUEST_DECLARED_WIDTH_SUM);
        assertEquals(CsvAuthCodec.REQUEST_DECLARED_WIDTH_SUM + 17, CsvAuthCodec.REQUEST_WIRE_LENGTH);
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, payload.length());
        assertEquals(17, delimiterPositionsOf(payload).size());
        assertFalse(payload.charAt(payload.length() - 1) == CsvAuthCodec.DELIMITER,
                "the request carries no trailing delimiter");
        assertEquals(170, COPYBOOK_REQUEST_WIDTHS.stream().mapToInt(Integer::intValue).sum() + 17);
    }

    /**
     * The seventeen request delimiters sit at exactly the positions the emitted widths put them.
     *
     * <p>Assumptions: a delimiter position is a consequence of the widths before it, so this assertion
     * localises a width defect to the field that caused it. The first eight positions are the same
     * whichever money width is used, so the ninth is the earliest place a wrong money width becomes
     * visible -- and once it is wrong there, every position after it is wrong too.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the request delimiters sit at the declared zero-based positions")
    void requestDelimiterPositionsAreExactlyTheEmittedOffsets() {
        String payload = CsvAuthCodec.encodeRequest(orderedRequest());

        assertEquals(REQUEST_DELIMITER_POSITIONS, delimiterPositionsOf(payload));
        assertEquals(17, REQUEST_DELIMITER_POSITIONS.size());
        assertEquals(153, REQUEST_DELIMITER_POSITIONS.get(16));
    }

    /**
     * Every request field lands in its own ordinal slot, proven by a filler unique to each.
     *
     * <p>Assumptions: the payload's fields are read by counting delimiters, so this is an assertion
     * about ordinal order and not about byte offsets. Each expectation is the whole emitted field
     * including any pad, so a value that reached the right ordinal at the wrong width fails here
     * too.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("each request field lands in its own ordinal slot")
    void requestFieldOrderIsProvenBySliceExtraction() {
        String payload = CsvAuthCodec.encodeRequest(orderedRequest());
        String[] fields = payload.split(",", -1);

        assertEquals(18, fields.length);
        assertEquals("AAAAAA", fields[0]);
        assertEquals("BBBBBB", fields[1]);
        assertEquals("CCCCCCCCCCCCCCCC", fields[2]);
        assertEquals("DDDD", fields[3]);
        assertEquals("EEEE", fields[4]);
        assertEquals("FFFFFF", fields[5]);
        assertEquals("GGGGGG", fields[6]);
        assertEquals("000008", fields[7]);
        assertEquals("0000000100.99", fields[8]);
        assertEquals("JJJJ", fields[9]);
        assertEquals("KKK", fields[10]);
        assertEquals("07", fields[11]);
        assertEquals("MMMMMMMMMMMMMMM", fields[12]);
        assertEquals("NNNNNNNNNNNNNNNNNNNNNN", fields[13]);
        assertEquals("OOOOOOOOOOOOO", fields[14]);
        assertEquals("PP", fields[15]);
        assertEquals("QQQQQQQQQ", fields[16]);
        assertEquals("RRRRRRRRRRRRRRR", fields[17]);
    }

    /**
     * Every request field occupies exactly the emitted width its table declares.
     *
     * <p>Assumptions: this is asserted field by field rather than only as a total, because two
     * compensating width errors sum to the right total. A field one character short and its neighbour
     * one character long would pass a length check on the whole payload and would still shift the
     * boundary between them for any reader that trusts the widths.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every request field occupies its full emitted width")
    void requestFieldsOccupyTheirDeclaredWidths() {
        String[] fields = CsvAuthCodec.encodeRequest(orderedRequest()).split(",", -1);

        for (int ordinal = 0; ordinal < CsvAuthCodec.REQUEST_FIELD_COUNT; ordinal++) {
            assertEquals(CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(ordinal), fields[ordinal].length(),
                    "field " + CsvAuthCodec.REQUEST_FIELD_NAMES.get(ordinal)
                            + " must occupy its declared width");
        }
    }

    /**
     * The whole ordered request payload is byte-identical to the transcribed golden vector.
     *
     * <p>Assumptions: this compares a complete emitted buffer against one literal rather than
     * comparing the codec with itself, which is the only form of assertion that can fail when encode
     * and decode share one mistaken width. The literal is the eighteen values of
     * {@link #orderedRequest()} joined by seventeen commas, with the ordinal-nine value rendered by
     * the thirteen-character request renderer.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the ordered request payload matches its golden vector byte for byte")
    void requestCanonicalPayloadIsTheGoldenVector() {
        assertEquals(ORDERED_REQUEST_PAYLOAD, CsvAuthCodec.encodeRequest(orderedRequest()));
        assertEquals(169, ORDERED_REQUEST_PAYLOAD.length());
        assertEquals(17, delimiterPositionsOf(ORDERED_REQUEST_PAYLOAD).size());
    }


    /**
     * A field's leading and internal spaces survive, and its trailing pad is restored to full width.
     *
     * <p>Assumptions: {@code DELIMITED BY SIZE} at line 728 of {@code COPAUA0C.cbl} contributes each
     * source field's FULL declared size, blanks included, which is why every emitted field occupies
     * its whole width rather than being trimmed. The direction of the pad is what distinguishes the
     * three kinds of space: a COBOL alphanumeric move left-justifies and pads on the right, so a
     * trailing space on this wire is padding, while a leading or internal space is data. The carrier
     * therefore strips only the trailing run and the encoder restores it, so a value supplied as
     * {@code " A B "} is held as {@code " A B"} and emitted as {@code " A B         "}.</p>
     *
     * <p>Alternatives Considered: stripping both ends, which would look symmetrical and would silently
     * alter any value whose first character is genuinely a space -- a merchant city of {@code " YORK"}
     * would become {@code "YORK"} and no longer match the record it came from.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("DELIMITED BY SIZE keeps leading and internal spaces and pads to full width")
    void delimitedBySizeRetainsLeadingAndInternalSpacesAndPadsToWidth() {
        AuthRequest spaced = new AuthRequest(
                "240715",
                "101530",
                CARD_NUM,
                "PURC",
                "1229",
                "AUTH01",
                "POS001",
                "000000",
                Money.of("100.99"),
                "5411",
                "840",
                "05",
                "MERCHANT0000001",
                " ACME  SUPERMARKET ",
                " A B ",
                "WA",
                "981010000",
                TRANSACTION_ID);

        String payload = CsvAuthCodec.encodeRequest(spaced);

        assertEquals(" ACME  SUPERMARKET    ", fieldAt(payload, 13));
        assertEquals(22, fieldAt(payload, 13).length());
        assertEquals(" A B         ", fieldAt(payload, 14));
        assertEquals(13, fieldAt(payload, 14).length());
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, payload.length());

        // WHY : Assumptions: the decode direction has to agree with the strip rule or the round trip
        //       would report a value the producer never sent. The leading space and the internal
        //       two-space run come back and the restored trailing pad does not, which is exactly the
        //       asymmetry the wire declares.
        AuthRequest decoded = CsvAuthCodec.decodeRequest(payload);
        assertEquals(" ACME  SUPERMARKET", decoded.merchantName());
        assertEquals(" A B", decoded.merchantCity());
    }

    /**
     * The two digit-picture fields stay digit TEXT and keep their leading zeros.
     *
     * <p>Assumptions: {@code PA-RQ-PROCESSING-CODE PIC 9(06)} at line 26 of {@code CCPAURQY.cpy} and
     * {@code PA-RQ-POS-ENTRY-MODE PIC 9(02)} at line 30 declare digit positions, and a display digit
     * position holds a character. Carrying either as a Java number would emit {@code 8} for
     * {@code 000008} and {@code 7} for {@code 07}, which loses five characters and one respectively
     * and shifts every field after it -- and the reference {@code UNSTRING} would then read the
     * shifted payload without complaint, because a delimited reader has no width to check against.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("digit-picture fields keep their leading zeros as text")
    void digitPictureFieldsKeepTheirLeadingZeroesAsText() {
        String payload = CsvAuthCodec.encodeRequest(orderedRequest());

        assertEquals("000008", fieldAt(payload, 7));
        assertEquals(6, fieldAt(payload, 7).length());
        assertEquals("07", fieldAt(payload, 11));
        assertEquals(2, fieldAt(payload, 11).length());

        AuthRequest decoded = CsvAuthCodec.decodeRequest(payload);
        assertEquals("000008", decoded.processingCode());
        assertEquals("07", decoded.posEntryMode());
    }

    /**
     * Neither carrier's incidental rendering discloses a full card number or a monetary amount.
     *
     * <p>Assumptions: a carrier reaches a log line by accident rather than by intent -- inside a
     * collection, as an exception's context, or through a debug statement -- so the rendering has to be
     * safe without the author of that line having thought about it. Both carriers therefore override the
     * rendering a {@code record} would otherwise generate, which interpolates EVERY component including
     * the sixteen-digit primary account number and the amount.</p>
     *
     * <p>Assumptions: the assertion is that the rendering does not CONTAIN the sensitive values, not
     * merely that it differs from the default. A rendering that masked the card number but still carried
     * the amount, or that carried the card number in a different position, would pass a difference check
     * and still disclose the value.</p>
     *
     * <p>Trade-offs: the transaction identifier and the reply's three decision fields ARE rendered,
     * because they are the values that make a log line diagnostically useful and none of them is
     * cardholder data. Withholding everything would have produced a rendering nobody could correlate,
     * which is the pressure that leads an author to log the raw payload instead.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("carrier renderings mask the card number and withhold monetary detail")
    void carrierRenderingsDoNotDiscloseCardNumbersOrAmounts() {
        AuthRequest request = requestWith(Money.of("100.99"));
        AuthReply reply = replyWith(Money.of("100.99"));
        String requestText = request.toString();
        String replyText = reply.toString();

        for (String rendered : List.of(requestText, replyText)) {
            assertFalse(rendered.contains(CARD_NUM),
                    "a carrier rendering must not disclose the full card number");
            assertFalse(rendered.contains("100.99"),
                    "a carrier rendering must not disclose a monetary amount");
            assertTrue(rendered.contains(CardNumberMasker.mask(CARD_NUM)),
                    "a carrier rendering must carry the masked card number");
            assertTrue(rendered.contains("TRN000000000001"),
                    "a carrier rendering must carry the transaction identifier for correlation");
        }

        // WHY : Assumptions: the request withholds its merchant and acquirer detail as a group rather
        //       than field by field, because those fields identify where a cardholder transacted and
        //       are jointly re-identifying even when the card number is masked. The reply withholds
        //       only its amount, since its other five fields are the decision itself.
        assertTrue(requestText.contains("<withheld>"),
                "the request rendering must state that components are withheld");
        assertFalse(requestText.contains("ACME SUPERMARKET NO 12"),
                "the request rendering must not disclose the merchant name");
        assertFalse(requestText.contains("MERCHANT0000001"),
                "the request rendering must not disclose the merchant identifier");
        assertTrue(replyText.contains("approvedAmount=<withheld>"),
                "the reply rendering must withhold the approved amount");
        assertTrue(replyText.contains("authIdCode=A00001"),
                "the reply rendering must carry its authorization identifier");
        assertTrue(replyText.contains("authRespCode=00"),
                "the reply rendering must carry its response code");
    }

    /**
     * The merchant category code keeps the copybook's misspelling as lineage and its four-byte slot.
     *
     * <p>Assumptions: the copybook spells the field {@code PA-RQ-MERCHANT-CATAGORY-CODE} at line 28 of
     * {@code CCPAURQY.cpy}, and the codec keeps that spelling in the name table it uses for failure
     * messages while naming the Java component {@code merchantCategoryCode}. The correction is
     * therefore a rename of an identifier and nothing else: the field stays tenth, stays four
     * characters, and stays between the money field and the acquirer country code. Renaming it in the
     * name table instead would send a reader looking for a copybook line that does not exist.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the merchant category code misspelling is lineage only")
    void merchantCategoryCodeKeepsItsSourceSpellingAndItsSlot() {
        assertEquals("PA-RQ-MERCHANT-CATAGORY-CODE", CsvAuthCodec.REQUEST_FIELD_NAMES.get(9));
        assertEquals("merchantCategoryCode", REQUEST_COMPONENT_NAMES.get(9));
        assertEquals("merchantCategoryCode", componentNamesOf(AuthRequest.class).get(9));
        assertEquals(4, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(9));

        String payload = CsvAuthCodec.encodeRequest(orderedRequest());
        assertEquals("JJJJ", fieldAt(payload, 9));
        assertEquals(80, REQUEST_DELIMITER_POSITIONS.get(9));
        assertEquals("JJJJ", CsvAuthCodec.decodeRequest(payload).merchantCategoryCode());
    }

    /**
     * A request payload survives decode and re-encode byte for byte.
     *
     * <p>Assumptions: this is the direction that matters for a producer's own traffic --
     * {@code encode(decode(wire))} rather than {@code decode(encode(value))} -- because it establishes
     * that reading a payload and writing it back changes nothing. It holds here only because every
     * field of the golden vector is already at its declared width; a payload carrying a shortened
     * field re-encodes to the padded form instead, which the tolerant-decode discipline accepts and
     * this class asserts separately.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request payload decodes and re-encodes byte for byte")
    void requestPayloadReEncodesByteForByte() {
        AuthRequest decoded = CsvAuthCodec.decodeRequest(ORDERED_REQUEST_PAYLOAD);

        assertEquals(ORDERED_REQUEST_PAYLOAD, CsvAuthCodec.encodeRequest(decoded));
        assertEquals(orderedRequest(), decoded);
        assertArrayEqualsAsWireBytes(ORDERED_REQUEST_PAYLOAD, CsvAuthCodec.encodeRequestBytes(decoded));
    }

    /**
     * The six reply fields occupy the fifty-seven characters declared by {@code CCPAURLY.cpy}.
     *
     * <p>Assumptions: the width table is asserted both as six individual values and as a sum because
     * a total alone cannot detect compensating drift. The table comes from lines 19 to 24 of
     * {@code CCPAURLY.cpy}: sixteen for the card number, fifteen for the transaction identifier, six
     * for the authorization identifier, two for the response code, four for the reason and fourteen
     * for the approved amount.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the six reply fields occupy fifty-seven declared characters")
    void replyWidthTableSumsToFiftySevenDeclaredCharacters() {
        assertEquals(List.of(16, 15, 6, 2, 4, 14), CsvAuthCodec.REPLY_FIELD_WIDTHS);
        assertEquals(57, CsvAuthCodec.REPLY_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum());
        assertEquals(CsvAuthCodec.REPLY_DECLARED_WIDTH_SUM,
                CsvAuthCodec.REPLY_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum());

        String[] fields = CsvAuthCodec.encodeReply(replyWith(Money.of("100.99"))).split(",", -1);
        assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT + 1, fields.length);
        for (int ordinal = 0; ordinal < CsvAuthCodec.REPLY_FIELD_COUNT; ordinal++) {
            assertEquals(CsvAuthCodec.REPLY_FIELD_WIDTHS.get(ordinal), fields[ordinal].length(),
                    "field " + CsvAuthCodec.REPLY_FIELD_NAMES.get(ordinal)
                            + " must occupy its declared width");
        }
    }

    /**
     * The canonical reply length is sixty-three characters, not sixty-two.
     *
     * <p>Assumptions: the six-comma counting error is to count separators only BETWEEN six fields,
     * which gives five and produces the incorrect total {@code 57 + 5 = 62}. The {@code STRING} at
     * lines 722 to 727 of {@code COPAUA0C.cbl} instead pairs a comma literal with every field,
     * including the approved amount at line 727, so the correct arithmetic is
     * {@code 57 + 6 = 63}. This regression records the baseline statement as written; it makes no
     * claim that the baseline was repaired or changed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the canonical reply length is sixty-three, not sixty-two")
    void replyCanonicalLengthIsSixtyThreeNotSixtyTwo() {
        String payload = CsvAuthCodec.encodeReply(replyWith(Money.of("100.99")));
        int delimiterCount = delimiterPositionsOf(payload).size();

        assertEquals(6, delimiterCount);
        assertEquals(63, CsvAuthCodec.REPLY_WIRE_LENGTH);
        assertEquals(CsvAuthCodec.REPLY_DECLARED_WIDTH_SUM + delimiterCount,
                CsvAuthCodec.REPLY_WIRE_LENGTH);
        assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, payload.length());
        assertFalse(payload.length() == 62, "the sixth, trailing comma is part of the payload");
    }

    /**
     * The six reply delimiters occupy the exact zero-based positions implied by the width table.
     *
     * <p>Assumptions: the final position, 62, is as important as the five interior positions because
     * it proves the sixth comma is physically present at the end of the 63-character payload. The
     * positions are fixed literals rather than values recomputed from the production table, so a
     * width and its derived constant cannot drift together without this test noticing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply delimiters sit at zero-based positions 16, 32, 39, 42, 47 and 62")
    void replyDelimiterPositionsIncludeTheTrailingComma() {
        String payload = CsvAuthCodec.encodeReply(replyWith(Money.of("100.99")));

        assertEquals(REPLY_DELIMITER_POSITIONS, delimiterPositionsOf(payload));
        assertEquals(List.of(16, 32, 39, 42, 47, 62), REPLY_DELIMITER_POSITIONS);
        assertEquals(CsvAuthCodec.DELIMITER, payload.charAt(payload.length() - 1));
    }

    /**
     * The empty token after the trailing comma is framing structure, not a seventh business field.
     *
     * <p>Assumptions: splitting the canonical text while retaining empty tokens necessarily yields
     * seven tokens, but {@code CCPAURLY.cpy} declares only six values. The decoder consumes the first
     * six and permits the seventh only when it is pad-only, which keeps the framing comma visible
     * without enlarging {@link AuthReply} or shifting its component order.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the empty token after the trailing comma is not a seventh reply field")
    void replyTrailingEmptyTokenIsDelimiterStructure() {
        String[] tokens = CANONICAL_REPLY_PAYLOAD.split(",", -1);

        assertEquals(7, tokens.length);
        assertTrue(tokens[6].isEmpty(), "the token after the sixth comma must contain no business data");
        assertEquals(6, componentNamesOf(AuthReply.class).size());
        assertEquals(replyWith(Money.of("100.99")),
                CsvAuthCodec.decodeReply(CANONICAL_REPLY_PAYLOAD));
    }

    /**
     * The reply encoder emits exactly the canonical sixty-three single-byte characters.
     *
     * <p>Assumptions: the text and byte APIs are asserted together because ISO-8859-1 makes every
     * declared display character one byte. A text-only check could miss a future byte encoder using a
     * variable-width charset, while a length-only byte check could miss the right length carrying the
     * wrong zero-suppressed money or a misplaced comma.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply encoder emits exactly sixty-three canonical bytes")
    void replyEncoderEmitsExactlySixtyThreeCanonicalBytes() {
        AuthReply reply = replyWith(Money.of("100.99"));
        String payload = CsvAuthCodec.encodeReply(reply);
        byte[] bytes = CsvAuthCodec.encodeReplyBytes(reply);

        assertEquals(CANONICAL_REPLY_PAYLOAD, payload);
        assertEquals(63, payload.length());
        assertEquals(63, bytes.length);
        assertArrayEqualsAsWireBytes(CANONICAL_REPLY_PAYLOAD, bytes);
    }

    /**
     * The 64-byte frame transmitted by the baseline decodes to the canonical six-field reply.
     *
     * <p>Assumptions: {@code WS-RESP-LENGTH} starts at one at line 46 of
     * {@code COPAUA0C.cbl}; after the {@code STRING} writes 63 characters, it is the cursor position
     * 64, and lines 756 and 762 reuse that cursor as the MQ length. Byte 64 is therefore one space
     * from the remaining {@code PIC X(200)} buffer, not a seventh field. The Java encoder does not
     * reproduce that cursor defect: it emits the 63-byte canonical form, while the decoder accepts
     * the one pad-only framing token needed for baseline interoperability.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the baseline sixty-four-byte frame decodes as the canonical reply")
    void baselineSixtyFourByteReplyFrameDecodesToTheCanonicalReply() {
        byte[] canonical = CANONICAL_REPLY_PAYLOAD.getBytes(StandardCharsets.ISO_8859_1);
        byte[] baselineBuffer = new byte[200];
        Arrays.fill(baselineBuffer, (byte) ' ');
        System.arraycopy(canonical, 0, baselineBuffer, 0, canonical.length);

        // WHY : Assumptions: strict encode and tolerant decode are deliberately asymmetric. Emitting
        //       only the 63 contract bytes prevents Java from perpetuating a cursor-as-length defect,
        //       while accepting the pad-only seventh token keeps replies already produced by the
        //       baseline readable during migration.
        AuthReply expected = replyWith(Money.of("100.99"));
        assertEquals(63, canonical.length);
        assertEquals((byte) ' ', baselineBuffer[63]);
        assertEquals(expected, CsvAuthCodec.decodeReply(baselineBuffer, 64));
        assertEquals(expected, CsvAuthCodec.decodeReply(CANONICAL_REPLY_PAYLOAD + " "));
        assertEquals(CANONICAL_REPLY_PAYLOAD, CsvAuthCodec.encodeReply(expected));
    }

    /**
     * A seventh reply token carrying non-pad data raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: accepting a pad-only seventh token is a framing exception, not permission to
     * add a business field. The byte {@code X} is chosen because it cannot be mistaken for buffer
     * padding; rejecting it proves the tolerance cannot turn a seven-field reply into a six-field
     * carrier by silently discarding data.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a non-pad seventh reply token is rejected")
    void replyDecoderRejectsNonPadTrailingData() {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeReply(CANONICAL_REPLY_PAYLOAD + "X"));

        assertTrue(failure.getMessage().contains("reply payload"));
        assertTrue(failure.getMessage().contains("6"));
        assertFalse(failure.getMessage().contains(CARD_NUM),
                "a structural failure must not repeat the submitted card number");
    }

    /**
     * A second excess byte that creates another token raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the production decoder defines its exception by token shape rather than by a
     * blanket 64-byte ceiling. The first excess byte here is the documented space and the second is a
     * comma, so the latter creates an eighth token and necessarily falls outside the one pad-only
     * excess token the codec accepts. This test intentionally does not generalise that finding to a
     * second SPACE: two spaces remain one pad-only token under the real public API and production
     * must not be changed merely to make a broader test statement true.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a second excess byte that creates another reply token is rejected")
    void replyDecoderRejectsASecondExcessByteThatCreatesAnotherToken() {
        String frameWithSecondExcessByte = CANONICAL_REPLY_PAYLOAD + " ,";

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeReply(frameWithSecondExcessByte));

        assertEquals(65, frameWithSecondExcessByte.length());
        assertTrue(failure.getMessage().contains("8 delimited fields"));
        assertFalse(failure.getMessage().contains(CARD_NUM),
                "a field-count failure must not repeat the submitted card number");
    }

    /**
     * A canonical reply survives decode and re-encode with equal value and identical bytes.
     *
     * <p>Assumptions: value equality and byte stability are separate obligations. Record equality
     * proves every one of the six business values survived, while the literal and byte assertions
     * prove the blank sign position, zero suppression and trailing comma also survived even though
     * they are representation details not visible in the record's values.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a canonical reply round trip preserves all values and every byte")
    void replyPayloadRoundTripPreservesValueAndCanonicalBytes() {
        AuthReply expected = replyWith(Money.of("100.99"));
        AuthReply decoded = CsvAuthCodec.decodeReply(CANONICAL_REPLY_PAYLOAD);
        String reencoded = CsvAuthCodec.encodeReply(decoded);

        assertEquals(expected, decoded);
        assertEquals(CANONICAL_REPLY_PAYLOAD, reencoded);
        assertArrayEqualsAsWireBytes(
                CANONICAL_REPLY_PAYLOAD,
                CsvAuthCodec.encodeReplyBytes(decoded));
    }

    /**
     * The money renderings expose the exact display positions their two baseline receivers use.
     *
     * <p>Assumptions: edited numeric TEXT is a separate wire regime from a byte-encoded decimal
     * representation. The reply reaches the wire through {@code PIC -zzzzzzzzz9.99} at line 66 of
     * {@code COPAUA0C.cbl}, so its fourteen positions are one sign-control position, ten integer
     * positions, one literal point and two fraction positions. The request reaches
     * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63, so its emitted positive form has ten
     * integer digits, the point and two fraction digits but no sign position.</p>
     *
     * <p>Assumptions: the final period in the source declaration {@code PIC +9(10).99.} terminates
     * the COBOL clause; it is not a second punctuation character in the field. Only the point between
     * the integer and fractional pictures occupies a wire position, which is why the copybook picture
     * is fourteen characters rather than fifteen.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("money text occupies its sign, digit, point and fraction positions exactly")
    void moneyTextOccupiesItsDeclaredDisplayPositions() {
        String reply = CsvAuthCodec.formatReplyMoney(Money.of("100.99"), REPLY_AMOUNT_FIELD);
        String request = CsvAuthCodec.formatRequestMoney(Money.of("100.99"), REQUEST_AMOUNT_FIELD);

        assertEquals(14, CsvAuthCodec.MONEY_EDITED_WIDTH);
        assertEquals(13, CsvAuthCodec.REQUEST_MONEY_WIDTH);
        assertEquals(10, CsvAuthCodec.MONEY_INTEGER_DIGITS);
        assertEquals(2, CsvAuthCodec.MONEY_SCALE);

        assertEquals(14, reply.length());
        assertEquals(' ', reply.charAt(0));
        assertEquals("       ", reply.substring(1, 8));
        assertEquals("100", reply.substring(8, 11));
        assertEquals('.', reply.charAt(11));
        assertEquals("99", reply.substring(12));

        assertEquals(13, request.length());
        assertEquals("0000000100", request.substring(0, 10));
        assertEquals('.', request.charAt(10));
        assertEquals("99", request.substring(11));

        // WHY : Assumptions: the two renderings are compared to each other rather than only against
        //       their own literals, because they are easy to confuse -- both end in the same six
        //       characters. They differ in two ways and both are asserted: the reply is one position
        //       wider, spending it on the sign-control character the request has no room for, and the
        //       reply SUPPRESSES its leading zeros to blanks where the request forces them to digits.
        //       Asserting only the width would let a renderer emit the other's padding at the right
        //       length, and asserting only the padding would let it emit the wrong width.
        assertEquals(1, reply.length() - request.length());
        assertEquals(' ', reply.charAt(0));
        assertEquals("100.99", reply.substring(8));
        assertEquals("100.99", request.substring(7));
        assertEquals(' ', reply.charAt(7));
        assertEquals('0', request.charAt(6));
    }

    /**
     * Positive request amounts carry a forced {@code +}, ten zero-padded digits and two cent digits.
     *
     * <p>Assumptions: the {@code +} in {@code PIC +9(10).99} is a forced sign, which prints its sign
     * character for every value including a positive one, so it is not optional and does not become a
     * space. The ten integer positions are separate from it, which is why the full ten-digit domain
     * survives alongside the sign rather than competing with it for a position.</p>
     *
     * <p>Assumptions: the request's thirteen-character receiver has no room for a positive sign if
     * the full ten-digit integer domain is to survive. An unsigned token remains positive under
     * {@code FUNCTION NUMVAL}, so dropping the sign rather than an integer position preserves every
     * non-negative value the copybook picture can express.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("positive request money is plus-signed, zero-padded and two-place exact")
    void positiveRequestMoneyUsesTheThirteenCharacterGoldenVectors() {
        String withCents =
                CsvAuthCodec.formatRequestMoney(Money.of("100.99"), REQUEST_AMOUNT_FIELD);
        String whole =
                CsvAuthCodec.formatRequestMoney(Money.of("100.00"), REQUEST_AMOUNT_FIELD);
        String maximum =
                CsvAuthCodec.formatRequestMoney(Money.of("9999999999.99"), REQUEST_AMOUNT_FIELD);

        assertEquals("0000000100.99", withCents);
        assertEquals("0000000100.00", whole);
        assertEquals("9999999999.99", maximum);
        assertEquals(13, withCents.length());
        assertEquals(13, whole.length());
        assertEquals(13, maximum.length());
        assertNoPlusSign(withCents);
        assertNoPlusSign(whole);
        assertNoPlusSign(maximum);
    }

    /**
     * A negative request amount spends one receiver position on {@code -} and nine on integer digits.
     *
     * <p>Assumptions: negative amounts are not present in the repository's request producer
     * contract, but the copybook sign permits them. The production renderer therefore carries values
     * that fit without truncation and raises for a tenth integer digit; this vector proves the
     * accepted shape remains exactly thirteen characters.</p>
     *
     * <p>Assumptions: this case asserts what the WIRE admits and not what the system accepts, and the
     * distinction is worth stating because the two differ deliberately.
     * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} line 27 declares an explicit sign
     * position, so a negative amount is expressible on the wire and this codec must report it
     * faithfully in both directions -- an encoder that emitted one and a decoder that refused to read
     * it back would not be closed under its own contract, which is the defect the reply-amount case
     * elsewhere in this class exists to prevent. Whether such an amount may be ACTED on is a different
     * question and belongs to the consumer:
     * {@code com.carddemo.authorization.dto.AuthorizationRequestPayload.isAmountWithinRecordDomain}
     * states the record's own domain as zero through the greatest magnitude, and the queue consumer
     * applies it immediately after decoding and before any lookup, so a negative amount is refused
     * there rather than approved. Reading this case as an endorsement of a negative authorization would
     * be a misreading of what a codec test can assert.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("negative request money uses a minus and nine integer positions")
    void negativeRequestMoneyUsesOneSignAndTenIntegerPositions() {
        String rendered =
                CsvAuthCodec.formatRequestMoney(Money.of("-100.99"), REQUEST_AMOUNT_FIELD);

        assertEquals("-000000100.99", rendered);
        assertEquals(CsvAuthCodec.REQUEST_MONEY_WIDTH, rendered.length());
        assertEquals('-', rendered.charAt(0));
        assertEquals('.', rendered.charAt(10));
        assertEquals(Money.of("-100.99"),
                CsvAuthCodec.parseMoney(rendered, REQUEST_AMOUNT_FIELD));
    }

    /**
     * Zero request money renders as a plus, ten zero digits, a point and two zero cents.
     *
     * <p>Assumptions: zero is not negative in the value model, so the forced sign position takes the
     * non-negative character {@code +} rather than a preserved textual minus. This is the rendering half
     * of the signed-zero rule: a payload arriving as {@code -0000000000.00} parses successfully because
     * it is numerically well formed, and re-rendering it produces the plus form, which is the behaviour
     * registered centrally as D-SIGNED-ZERO-ZONED for the zoned regime and applied consistently
     * here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("zero request money renders with the non-negative forced sign")
    void zeroRequestMoneyCanonicalizesWithoutASign() {
        String rendered =
                CsvAuthCodec.formatRequestMoney(Money.ofCents(0L), REQUEST_AMOUNT_FIELD);

        assertEquals("0000000000.00", rendered);
        assertEquals(13, rendered.length());
        assertNoPlusSign(rendered);
        assertFalse(rendered.startsWith("-"), "canonical zero must not retain a negative sign");
        assertEquals(Money.ofCents(0L), CsvAuthCodec.parseMoney(rendered, REQUEST_AMOUNT_FIELD));
    }

    /**
     * Positive reply money uses a blank sign and blank-suppressed leading zero positions.
     *
     * <p>Assumptions: {@code PIC -zzzzzzzzz9.99} emits a space from its sign control for a
     * non-negative value and a space from every leading {@code z}. The final {@code 9} before the
     * point is forced, so the integer run never disappears altogether.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("positive reply money uses a blank sign and zero suppression")
    void positiveReplyMoneyUsesTheFourteenCharacterGoldenVectors() {
        String withCents =
                CsvAuthCodec.formatReplyMoney(Money.of("100.99"), REPLY_AMOUNT_FIELD);
        String whole =
                CsvAuthCodec.formatReplyMoney(Money.of("100.00"), REPLY_AMOUNT_FIELD);
        String maximum =
                CsvAuthCodec.formatReplyMoney(Money.of("9999999999.99"), REPLY_AMOUNT_FIELD);

        assertEquals("        100.99", withCents);
        assertEquals("        100.00", whole);
        assertEquals(" 9999999999.99", maximum);
        assertEquals(14, withCents.length());
        assertEquals(14, whole.length());
        assertEquals(14, maximum.length());
        assertEquals(' ', withCents.charAt(0));
        assertNoPlusSign(withCents);
    }

    /**
     * Negative reply money emits a minus in the sign-control position and suppresses leading zeros.
     *
     * <p>Assumptions: the minus occupies its own first position rather than sharing a digit
     * position, so the reply retains all ten integer positions for either sign. The request picture
     * agrees on that and on the width, and differs only in printing {@code +} rather than a blank for a
     * non-negative value -- which is why the two renderers cannot be collapsed into one padding rule
     * even though their geometry is identical.</p>
     *
     * <p>Trade-offs: the production parser accepts compact negative text such as {@code -100.99} but
     * strips pad only around the whole token, not the suppression spaces between a leading minus and
     * its first digit. This test therefore pins the formatter's baseline mask and the parser's compact
     * negative grammar separately; it does not claim a negative reply encode/decode round trip that
     * the real public API does not provide.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("negative reply money uses the sign-control position")
    void negativeReplyMoneyUsesTheLeadingSignControlPosition() {
        String rendered =
                CsvAuthCodec.formatReplyMoney(Money.of("-100.99"), REPLY_AMOUNT_FIELD);

        assertEquals("-       100.99", rendered);
        assertEquals(14, rendered.length());
        assertEquals('-', rendered.charAt(0));
        assertEquals('.', rendered.charAt(11));
        assertEquals(Money.of("-100.99"),
                CsvAuthCodec.parseMoney("-100.99", REPLY_AMOUNT_FIELD));
    }

    /**
     * Zero reply money keeps one forced integer digit and canonicalizes its sign to a blank.
     *
     * <p>Assumptions: the nine suppressed positions become spaces while the single forced
     * {@code 9} immediately before the point emits {@code 0}. A field of only blanks and
     * {@code .00} would have no integer digit for the parser, so the forced position is part of the
     * contract rather than cosmetic formatting.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("zero reply money keeps one forced digit and a blank sign")
    void zeroReplyMoneyCanonicalizesToTheBlankSignGoldenVector() {
        String rendered =
                CsvAuthCodec.formatReplyMoney(Money.ofCents(0L), REPLY_AMOUNT_FIELD);

        assertEquals("          0.00", rendered);
        assertEquals(14, rendered.length());
        assertEquals(' ', rendered.charAt(0));
        assertEquals('0', rendered.charAt(10));
        assertNoPlusSign(rendered);
    }

    /**
     * Neither production money rendering emits a plus sign for a non-negative value.
     *
     * <p>Assumptions: the request has no positive sign position and the reply's leading
     * {@code -} picture emits a space when no minus is needed. The intuitive
     * {@code +0000000000.00} form belongs to the value-holding copybook picture, not to either text
     * form that reaches or leaves the production codec.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("request and reply money never emit a plus sign")
    void neitherMoneyRenderingEmitsAPlusSign() {
        assertNoPlusSign(
                CsvAuthCodec.formatRequestMoney(Money.of("1.00"), REQUEST_AMOUNT_FIELD));
        assertNoPlusSign(
                CsvAuthCodec.formatReplyMoney(Money.of("1.00"), REPLY_AMOUNT_FIELD));
        assertNoPlusSign(
                CsvAuthCodec.formatRequestMoney(Money.ofCents(0L), REQUEST_AMOUNT_FIELD));
        assertNoPlusSign(
                CsvAuthCodec.formatReplyMoney(Money.ofCents(0L), REPLY_AMOUNT_FIELD));
    }

    /**
     * Both non-negative money text forms parse back to the same exact two-place amount.
     *
     * <p>Assumptions: parsing is checked through unscaled cents as well as value equality because
     * cents are the integer representation that proves no binary approximation or implicit rounding
     * occurred. Negative request parsing and negative reply rendering/parsing are asserted separately
     * because the production reply parser does not remove suppression spaces after a leading minus.</p>
     *
     * <p>This test returns no value.</p>
     *
     * @param amountText the {@link String} exact decimal value to render through both text forms
     */
    @ParameterizedTest(name = "non-negative money amount {0} retains its exact cents")
    @ValueSource(strings = {"0.00", "0.01", "100.99", "999999999.99"})
    @DisplayName("both non-negative money text forms preserve exact two-place cents")
    void nonNegativeMoneyRoundTripsPreserveExactScaleAndCents(String amountText) {
        Money expected = Money.of(amountText);
        Money fromRequest = CsvAuthCodec.parseMoney(
                CsvAuthCodec.formatRequestMoney(expected, REQUEST_AMOUNT_FIELD),
                REQUEST_AMOUNT_FIELD);
        Money fromReply = CsvAuthCodec.parseMoney(
                CsvAuthCodec.formatReplyMoney(expected, REPLY_AMOUNT_FIELD),
                REPLY_AMOUNT_FIELD);

        assertEquals(expected, fromRequest);
        assertEquals(expected, fromReply);
        assertEquals(2, fromRequest.amount().scale());
        assertEquals(2, fromReply.amount().scale());
        assertEquals(expected.unscaledCents(), fromRequest.unscaledCents());
        assertEquals(expected.unscaledCents(), fromReply.unscaledCents());
    }

    /**
     * The public money entry points expose only text, {@link Money} and {@link BigDecimal} values.
     *
     * <p>Assumptions: signature inspection is used instead of naming implementation helpers because
     * the public boundary is the stable contract. Four render overloads accept an exact decimal
     * value or the bounded money value and return text; the parser accepts text and returns
     * {@code Money}. A new overload outside those five shapes would fail this inventory before a
     * caller could choose an inexact path accidentally.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("money entry points expose only exact decimal, money and text types")
    void moneyEntryPointsUseOnlyExactDecimalMoneyAndTextTypes() {
        List<String> names = List.of("formatReplyMoney", "formatRequestMoney", "parseMoney");
        var entryPoints = Arrays.stream(CsvAuthCodec.class.getDeclaredMethods())
                .filter(method -> names.contains(method.getName()))
                .toList();

        assertEquals(5, entryPoints.size());
        assertEquals(4, entryPoints.stream()
                .filter(method -> method.getReturnType() == String.class)
                .count());
        assertEquals(1, entryPoints.stream()
                .filter(method -> method.getReturnType() == Money.class)
                .count());
        assertTrue(entryPoints.stream().allMatch(method -> {
            Class<?> firstParameter = method.getParameterTypes()[0];
            return firstParameter == BigDecimal.class
                    || firstParameter == Money.class
                    || firstParameter == String.class;
        }));
        assertTrue(entryPoints.stream().allMatch(
                method -> method.getParameterTypes()[1] == String.class));
    }

    /**
     * Exact decimal inputs with fewer than two places are padded without changing their value.
     *
     * <p>Assumptions: adding zero fraction positions is lossless and is distinct from reducing a
     * wider scale. The renderer may append {@code .00} or one trailing zero, but it must not choose a
     * rounding rule because no non-zero digit is discarded in either case.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("narrow exact decimal scales are padded without rounding")
    void moneyRenderersPadNarrowScalesWithoutChangingValue() {
        String wholeRequest = CsvAuthCodec.formatRequestMoney(
                new BigDecimal("12"), REQUEST_AMOUNT_FIELD);
        String tenthsRequest = CsvAuthCodec.formatRequestMoney(
                new BigDecimal("12.3"), REQUEST_AMOUNT_FIELD);
        String wholeReply = CsvAuthCodec.formatReplyMoney(
                new BigDecimal("12"), REPLY_AMOUNT_FIELD);
        String tenthsReply = CsvAuthCodec.formatReplyMoney(
                new BigDecimal("12.3"), REPLY_AMOUNT_FIELD);

        assertEquals("0000000012.00", wholeRequest);
        assertEquals("0000000012.30", tenthsRequest);
        assertTrue(wholeReply.endsWith("12.00"));
        assertTrue(tenthsReply.endsWith("12.30"));
        assertEquals(Money.of("12.00"),
                CsvAuthCodec.parseMoney(wholeReply, REPLY_AMOUNT_FIELD));
        assertEquals(Money.of("12.30"),
                CsvAuthCodec.parseMoney(tenthsReply, REPLY_AMOUNT_FIELD));
    }

    /**
     * A value carrying more than two decimal places raises
     * {@code CsvAuthCodec.AuthMessageFormatException} for both amount fields.
     *
     * <p>Assumptions: transport code has no authority to choose a rounding rule. Both
     * {@code PA-RQ-TRANSACTION-AMT} and {@code PA-RL-APPROVED-AMT} therefore reject a raw exact
     * decimal whose scale is three, leaving any reduction to the caller before it crosses the wire
     * boundary.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures both expected exceptions so
     * neither exception escapes the test.</p>
     */
    @Test
    @DisplayName("both money renderers reject a scale greater than two")
    void moneyRenderersRejectScaleGreaterThanTwo() {
        BigDecimal overScale = new BigDecimal("1.001");

        AuthMessageFormatException requestFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.formatRequestMoney(overScale, REQUEST_AMOUNT_FIELD));
        AuthMessageFormatException replyFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.formatReplyMoney(overScale, REPLY_AMOUNT_FIELD));

        assertTrue(requestFailure.getMessage().contains(REQUEST_AMOUNT_FIELD));
        assertTrue(replyFailure.getMessage().contains(REPLY_AMOUNT_FIELD));
        assertTrue(requestFailure.getMessage().contains("3 decimal places"));
        assertTrue(replyFailure.getMessage().contains("3 decimal places"));
    }

    /**
     * A negative request amount with all ten integer digits renders, round-trips and is not truncated.
     *
     * <p>Assumptions: the minus consumes one of the receiver's thirteen positions, leaving only nine
     * integer positions beside the point and cents. Raising is the lossless behavior; emitting a
     * fourteenth character would let the baseline receiver truncate the final cent digit silently.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a negative ten-digit request amount is rejected rather than truncated")
    void negativeTenDigitRequestMoneyIsRejectedRatherThanTruncated() {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.formatRequestMoney(
                        new BigDecimal("-1000000000.00"), REQUEST_AMOUNT_FIELD));

        assertTrue(failure.getMessage().contains(REQUEST_AMOUNT_FIELD));
        assertTrue(failure.getMessage().contains("10 integer digits"));
        assertTrue(failure.getMessage().contains("9 available"));
    }

    /**
     * An explicit plus parses, is preserved by the request rendering and dropped by the reply's.
     *
     * <p>Assumptions: a parsed value carries no textual sign at all, so each renderer re-derives the
     * sign position from its OWN picture rather than from the text it came from. The same parsed value is
     * therefore re-rendered with a {@code +} for {@code PIC +9(10).99} and with a blank for
     * {@code PIC -zzzzzzzzz9.99}, and asserting both from one parse is what proves the sign is a property
     * of the destination field and not of the source text.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an explicit parsed plus is re-rendered per destination picture")
    void explicitPositiveSignParsesAndCanonicalizesWithoutAPlus() {
        Money parsed = CsvAuthCodec.parseMoney("+0000000100.99", REPLY_AMOUNT_FIELD);
        String request = CsvAuthCodec.formatRequestMoney(parsed, REQUEST_AMOUNT_FIELD);
        String reply = CsvAuthCodec.formatReplyMoney(parsed, REPLY_AMOUNT_FIELD);

        assertEquals(Money.of("100.99"), parsed);
        assertEquals("0000000100.99", request);
        assertEquals("        100.99", reply);
        assertNoPlusSign(reply);
    }

    /**
     * A textual negative zero parses as zero and canonicalizes without a negative sign.
     *
     * <p>Assumptions: the production value type has one zero, not distinct signed zero values. The
     * parser therefore accepts {@code -0000000000.00} as numerically well formed and neither renderer
     * reproduces its minus: the request has no sign position at all for a non-negative value and the
     * reply emits a blank sign-control position. Treating that text as malformed would contradict the real public
     * parser, so this test records normalization rather than inventing a rejection.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("textual negative zero normalizes to the one canonical zero")
    void textualNegativeZeroParsesAndCanonicalizesToUnsignedZero() {
        Money parsed = CsvAuthCodec.parseMoney("-0000000000.00", REPLY_AMOUNT_FIELD);

        assertEquals(Money.ofCents(0L), parsed);
        assertEquals("0000000000.00",
                CsvAuthCodec.formatRequestMoney(parsed, REQUEST_AMOUNT_FIELD));
        assertEquals("          0.00",
                CsvAuthCodec.formatReplyMoney(parsed, REPLY_AMOUNT_FIELD));
    }

    /**
     * A blank {@code PA-RL-APPROVED-AMT} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: surrounding spaces are padding only when they surround an amount. Once they
     * are stripped, a token with no sign, digit or point cannot represent zero implicitly because the
     * reply picture has a forced zero position for that value.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a blank reply amount is rejected")
    void moneyParserRejectsABlankReplyAmount() {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure("              ");

        assertTrue(failure.getMessage().contains("no amount"));
    }

    /**
     * A {@code PA-RL-APPROVED-AMT} without its literal point raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the decimal point occupies a real display position; it is not an implied scale
     * that may be reconstructed from the final two digits. Inferring it would accept bytes no edited
     * display producer emitted and would make a punctuation loss invisible.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a reply amount without a decimal point is rejected")
    void moneyParserRejectsAReplyAmountWithoutItsDecimalPoint() {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure("000000010099");

        assertTrue(failure.getMessage().contains("literal decimal point"));
    }

    /**
     * A {@code PA-RL-APPROVED-AMT} with two points raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: selecting either point would reinterpret the remaining punctuation as a digit
     * or silently discard a segment. The grammar therefore rejects ambiguity before any integer
     * conversion is attempted.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a reply amount with two decimal points is rejected")
    void moneyParserRejectsAReplyAmountWithTwoDecimalPoints() {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure("00000001.0.99");

        assertTrue(failure.getMessage().contains("more than one"));
    }

    /**
     * A point before every integer digit in {@code PA-RL-APPROVED-AMT} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: a leading sign may be present, but at least one forced or unsuppressed integer
     * digit must follow it before the point. Accepting {@code +.99} would invent the missing zero and
     * admit a form neither baseline picture emits.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a reply amount with no integer digit before the point is rejected")
    void moneyParserRejectsAMisplacedPointBeforeAllIntegerDigits() {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure("+.99");

        assertTrue(failure.getMessage().contains("no digit before"));
    }

    /**
     * A non-digit in either digit run of {@code PA-RL-APPROVED-AMT} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: integer and fractional positions share one digit rule, so the two vectors are
     * one parameterized test rather than duplicate methods. Each case differs only in which run
     * contains the invalid character, and both must fail before a numeric value is constructed.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param malformedToken the {@link String} reply amount containing a non-digit in an integer or
     *     fraction position
     */
    @ParameterizedTest(name = "non-digit reply amount {0} is rejected")
    @ValueSource(strings = {"00000A0100.99", "0000000100.9A"})
    @DisplayName("a non-digit in either reply amount digit run is rejected")
    void moneyParserRejectsNonDigitsInIntegerAndFractionPositions(String malformedToken) {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure(malformedToken);

        assertTrue(failure.getMessage().contains("digit position"));
        assertTrue(failure.getMessage().contains("picture declares a digit"));
    }

    /**
     * An invalid sign character in {@code PA-RL-APPROVED-AMT} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: only {@code +}, {@code -}, a leading digit or surrounding pad can begin a
     * parsed amount. A currency symbol is not treated as decoration because discarding it would widen
     * the wire grammar beyond either source picture and could conceal a producer using a locale
     * format.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("an invalid reply amount sign character is rejected")
    void moneyParserRejectsAnInvalidSignCharacter() {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure("*000000100.99");

        assertTrue(failure.getMessage().contains("sign position"));
        assertTrue(failure.getMessage().contains("'*'"));
    }

    /**
     * A short or long fraction in {@code PA-RL-APPROVED-AMT} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the parser deliberately accepts a shorter TOTAL token because zero suppression
     * removes leading integer positions, but it never accepts a shorter or longer FRACTION. The two
     * positions after the point are fixed digits, so one or three fraction digits are both malformed
     * rather than values to pad or round.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param malformedToken the {@link String} reply amount carrying one or three fraction digits
     */
    @ParameterizedTest(name = "wrong-scale reply amount {0} is rejected")
    @ValueSource(strings = {"0000000100.9", "0000000100.999"})
    @DisplayName("a reply amount with a short or long fraction is rejected")
    void moneyParserRejectsWrongFractionWidths(String malformedToken) {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure(malformedToken);

        assertTrue(failure.getMessage().contains("digits after"));
        assertTrue(failure.getMessage().contains("declares 2"));
    }

    /**
     * Eleven integer digits in {@code PA-RL-APPROVED-AMT} raise
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: this is the meaningful long-token boundary. Surrounding pad may legitimately
     * make the received token longer than fourteen characters, but an eleventh integer digit cannot
     * fit {@code 9(10)} and must never be truncated to make the token fit.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a reply amount with eleven integer digits is rejected")
    void moneyParserRejectsMoreThanTenIntegerDigits() {
        AuthMessageFormatException failure =
                assertReplyMoneyParseFailure("10000000000.99");

        assertTrue(failure.getMessage().contains("11 integer digits"));
        assertTrue(failure.getMessage().contains("declares 10"));
    }

    /**
     * Eleven integer digits supplied to the reply renderer raise
     * {@code CsvAuthCodec.AuthMessageFormatException} for {@code PA-RL-APPROVED-AMT}.
     *
     * <p>Assumptions: parser and renderer enforce the same ten-digit magnitude even though one starts
     * from text and the other from an exact decimal value. Rejecting before rendering avoids a
     * plausible-looking fourteen-character suffix of a materially different amount.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("the reply renderer rejects an eleven-digit magnitude")
    void replyMoneyRendererRejectsMoreThanTenIntegerDigits() {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.formatReplyMoney(
                        new BigDecimal("10000000000.00"), REPLY_AMOUNT_FIELD));

        assertTrue(failure.getMessage().contains(REPLY_AMOUNT_FIELD));
        assertTrue(failure.getMessage().contains("11 integer digits"));
    }

    /**
     * Zero-suppressed and surrounding-pad reply amounts parse despite differing total text lengths.
     *
     * <p>Assumptions: total token width is not the parser's strict boundary because the baseline
     * reply mask suppresses leading zeros and the transport may include pad around the token. The
     * strict geometry lies inside the value -- at most ten integer digits, one literal point and
     * exactly two fraction digits -- so both a short value and a pad-extended value represent the
     * same exact amount.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("zero suppression and surrounding pad do not change a parsed reply amount")
    void moneyParserAcceptsShortAndPadExtendedCanonicalGrammar() {
        Money expected = Money.of("1.00");

        assertEquals(expected, CsvAuthCodec.parseMoney("1.00", REPLY_AMOUNT_FIELD));
        assertEquals(expected,
                CsvAuthCodec.parseMoney("          1.00          ", REPLY_AMOUNT_FIELD));
    }

    /**
     * The private correlation source identity is exactly sixteen plus fifteen characters.
     *
     * <p>Assumptions: {@code CORRELATION_COMPOSITE_LENGTH} describes the value protected by the
     * tokeniser, not the public token it returns. The source is {@code PA-*-CARD-NUM X(16)} followed
     * by {@code PA-*-TRANSACTION-ID X(15)}, and the single-byte wire character set makes those
     * thirty-one characters thirty-one bytes without any numeric interpretation.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the private correlation source is sixteen plus fifteen characters")
    void correlationSourceIdentityIsExactlyThirtyOneCharacters() {
        assertEquals(16, CsvAuthCodec.CARD_NUM_WIDTH);
        assertEquals(15, CsvAuthCodec.TRANSACTION_ID_WIDTH);
        assertEquals(31, CsvAuthCodec.CORRELATION_COMPOSITE_LENGTH);
        assertEquals(
                CsvAuthCodec.CARD_NUM_WIDTH + CsvAuthCodec.TRANSACTION_ID_WIDTH,
                CsvAuthCodec.CORRELATION_COMPOSITE_LENGTH);
    }

    /**
     * The two published correlation widths name two different quantities and neither describes the
     * other.
     *
     * <p>Refactoring Rationale: a single constant used to be named for the correlation key and carried
     * the width of the composite the key is derived from, so a consumer sizing a column or a buffer
     * from it was wrong by nine characters in the direction that truncates. This test pins both
     * quantities and pins the returned token to the one that actually describes it, so the two cannot
     * be silently merged again.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the composite width and the token width are published as two distinct constants")
    void correlationCompositeAndTokenWidthsArePublishedApart() {
        assertEquals(31, CsvAuthCodec.CORRELATION_COMPOSITE_LENGTH);
        assertEquals(22, CsvAuthCodec.CORRELATION_TOKEN_LENGTH);
        assertEquals(OpaqueIdentifier.TOKEN_LENGTH, CsvAuthCodec.CORRELATION_TOKEN_LENGTH);
        assertNotEquals(CsvAuthCodec.CORRELATION_COMPOSITE_LENGTH,
                CsvAuthCodec.CORRELATION_TOKEN_LENGTH);

        String requestToken =
                request(CARD_NUM, TRANSACTION_ID).correlationKey(new OpaqueIdentifier(KEY));
        String replyToken =
                reply(CARD_NUM, TRANSACTION_ID).correlationKey(new OpaqueIdentifier(KEY));

        assertEquals(CsvAuthCodec.CORRELATION_TOKEN_LENGTH, requestToken.length());
        assertEquals(CsvAuthCodec.CORRELATION_TOKEN_LENGTH, replyToken.length());
    }

    /**
     * Request and reply carriers derive the same opaque token for the same source pair.
     *
     * <p>Assumptions: both APIs protect the same padded thirty-one-character source with the same
     * purpose string and key. Equality is the only correlation property a caller needs, so the raw
     * source pair remains private while either leg can independently reproduce its token.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("request and reply derive the same opaque correlation token")
    void requestAndReplyDeriveTheSameOpaqueCorrelationToken() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String requestToken = request(CARD_NUM, TRANSACTION_ID).correlationKey(tokeniser);
        String replyToken = reply(CARD_NUM, TRANSACTION_ID).correlationKey(tokeniser);

        assertEquals(requestToken, replyToken);
        assertEquals(OpaqueIdentifier.TOKEN_LENGTH, requestToken.length());
    }

    /**
     * The public correlation value is a bounded opaque token carrying neither source component.
     *
     * <p>Assumptions: the public representation is twenty-two URL-safe characters rather than the
     * thirty-one-character source. Checking both complete source values avoids mistaking fixed output
     * length for opacity: a value could be bounded and still copy one component into its output.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the public correlation token is bounded, URL-safe and opaque")
    void correlationTokenCarriesNeitherRawSourceComponent() {
        String token =
                request(CARD_NUM, TRANSACTION_ID).correlationKey(new OpaqueIdentifier(KEY));

        assertThat(token)
                .hasSize(22)
                .matches("[A-Za-z0-9_-]{22}")
                .doesNotContain(CARD_NUM)
                .doesNotContain(TRANSACTION_ID);
        assertEquals(22, OpaqueIdentifier.TOKEN_LENGTH);
        assertFalse(token.length() == CsvAuthCodec.CORRELATION_COMPOSITE_LENGTH,
                "the opaque token must not be confused with the private source width");
    }

    /**
     * Repeated derivation with equal key material and source values is stable.
     *
     * <p>Assumptions: stability is checked across two tokeniser instances rather than by invoking one
     * instance twice, so the assertion covers deterministic key use and not merely an accidental
     * per-instance cache. No global state or clock may influence a correlation value.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the same pair and key material always derive the same token")
    void correlationTokenIsStableAcrossTokeniserInstances() {
        String first =
                request(CARD_NUM, TRANSACTION_ID).correlationKey(new OpaqueIdentifier(KEY));
        String second =
                request(CARD_NUM, TRANSACTION_ID).correlationKey(new OpaqueIdentifier(KEY));

        assertEquals(first, second);
    }

    /**
     * Changing either half of the source identity changes the correlation token.
     *
     * <p>Assumptions: card and transaction changes are asserted independently so a derivation that
     * accidentally omitted either half cannot pass. The replacement values retain the exact declared
     * widths, leaving content as the only changed input.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("changing either source component changes the correlation token")
    void correlationTokenDistinguishesCardsAndTransactionsIndependently() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String original = request(CARD_NUM, TRANSACTION_ID).correlationKey(tokeniser);
        String differentCard =
                request("4111111111111112", TRANSACTION_ID).correlationKey(tokeniser);
        String differentTransaction =
                request(CARD_NUM, "000000000000124").correlationKey(tokeniser);

        assertThat(differentCard).isNotEqualTo(original);
        assertThat(differentTransaction).isNotEqualTo(original);
        assertThat(differentCard).isNotEqualTo(differentTransaction);
    }

    /**
     * Leading zeros in either source component remain significant characters.
     *
     * <p>Assumptions: both copybook fields are alphanumeric pictures, even when a card number happens
     * to contain only digits. Comparing a full leading-zero value with its numerically equivalent
     * short text proves the derivation never parses either component as a number and never discards
     * leading zeros before padding.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("leading zeros remain significant in both correlation components")
    void correlationTokenDoesNotInterpretEitherComponentNumerically() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String full = request("0000000000000001", "000000000000123")
                .correlationKey(tokeniser);
        String shortCard = request("1", "000000000000123")
                .correlationKey(tokeniser);
        String shortTransaction = request("0000000000000001", "123")
                .correlationKey(tokeniser);

        assertThat(shortCard).isNotEqualTo(full);
        assertThat(shortTransaction).isNotEqualTo(full);
        assertThat(shortCard).isNotEqualTo(shortTransaction);
    }

    /**
     * Letter case remains significant in the alphanumeric transaction identifier.
     *
     * <p>Assumptions: the correlation path preserves the exact display characters and applies no
     * case normalization. Upper- and lower-case values are distinct source identities even when an
     * operator might read them as the same word.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("correlation derivation performs no case normalization")
    void correlationTokenPreservesTransactionIdentifierCase() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String upperTransaction = "ABC000000000001";
        String lowerTransaction = "abc000000000001";

        assertEquals(15, upperTransaction.length());
        assertEquals(15, lowerTransaction.length());
        assertThat(request(CARD_NUM, upperTransaction).correlationKey(tokeniser))
                .isNotEqualTo(request(CARD_NUM, lowerTransaction).correlationKey(tokeniser));
    }

    /**
     * Leading and internal spaces remain data in the private correlation source.
     *
     * <p>Assumptions: a COBOL alphanumeric move pads only on the right, so a space at the beginning
     * or inside a value is data. The three under-width transaction identifiers below all become
     * fifteen characters, but their non-pad characters occupy different positions and must derive
     * different tokens.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("leading and internal spaces remain significant in correlation values")
    void correlationTokenPreservesLeadingAndInternalSpaces() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String plain = request(CARD_NUM, "ABC").correlationKey(tokeniser);
        String leading = request(CARD_NUM, " ABC").correlationKey(tokeniser);
        String internal = request(CARD_NUM, "AB C").correlationKey(tokeniser);

        assertThat(leading).isNotEqualTo(plain);
        assertThat(internal).isNotEqualTo(plain);
        assertThat(leading).isNotEqualTo(internal);
    }

    /**
     * Under-width values are accepted, trailing pad is normalized, and full width is restored.
     *
     * <p>Assumptions: the production carriers strip trailing spaces because they are COBOL pad, then
     * the encoder and private correlation source restore enough right pad to reach sixteen and
     * fifteen characters. Consequently caller-supplied trailing spaces are not distinct data under
     * the real API; leading and internal spaces are the significant cases asserted separately.</p>
     *
     * <p>Alternatives Considered: rejecting every under-width value or silently truncating an
     * over-width value. Strict exact-width construction would reject the zero-suppressed and trimmed
     * values the decoder intentionally returns, while truncation could make two different identifiers
     * collide. The implemented boundary accepts and right-pads short values but rejects long ones.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("under-width correlation values are right-padded and trailing pad is normalized")
    void correlationSourceRestoresWidthAfterTrailingPadNormalization() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        AuthRequest trimmedRequest = request("4111", "TX1");
        AuthRequest paddedRequest = request("4111   ", "TX1   ");
        AuthReply trimmedReply = reply("4111", "TX1");

        assertEquals(trimmedRequest, paddedRequest);
        assertEquals("4111", paddedRequest.cardNum());
        assertEquals("TX1", paddedRequest.transactionId());
        assertEquals(trimmedRequest.correlationKey(tokeniser),
                paddedRequest.correlationKey(tokeniser));
        assertEquals(trimmedRequest.correlationKey(tokeniser),
                trimmedReply.correlationKey(tokeniser));

        String requestPayload = CsvAuthCodec.encodeRequest(trimmedRequest);
        String replyPayload = CsvAuthCodec.encodeReply(trimmedReply);
        assertEquals("4111            ", fieldAt(requestPayload, 2));
        assertEquals("TX1            ", fieldAt(requestPayload, 17));
        assertEquals("4111            ", fieldAt(replyPayload, 0));
        assertEquals("TX1            ", fieldAt(replyPayload, 1));
    }

    /**
     * A seventeen-character card number raises
     * {@code CsvAuthCodec.AuthMessageFormatException} for both card fields.
     *
     * <p>Alternatives Considered: truncating the seventeenth character before correlation. That would
     * make two distinct primary account numbers derive one token and could pair a reply with the wrong
     * request, so both {@code PA-RQ-CARD-NUM} and {@code PA-RL-CARD-NUM} reject the value at carrier
     * construction instead.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures both expected exceptions so
     * neither exception escapes the test.</p>
     */
    @Test
    @DisplayName("over-width request and reply card numbers are rejected")
    void overWidthCorrelationCardNumbersAreRejectedRatherThanTruncated() {
        String overWidthCard = "41111111111111111";

        AuthMessageFormatException requestFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> request(overWidthCard, TRANSACTION_ID));
        AuthMessageFormatException replyFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> reply(overWidthCard, TRANSACTION_ID));

        assertTrue(requestFailure.getMessage().contains("PA-RQ-CARD-NUM"));
        assertTrue(replyFailure.getMessage().contains("PA-RL-CARD-NUM"));
        assertFalse(requestFailure.getMessage().contains(overWidthCard));
        assertFalse(replyFailure.getMessage().contains(overWidthCard));
    }

    /**
     * A sixteen-character transaction identifier raises
     * {@code CsvAuthCodec.AuthMessageFormatException} for both transaction fields.
     *
     * <p>Alternatives Considered: removing or ignoring the final character. The authorization
     * contract is {@code X(15)} even though the ledger contract is wider, and truncation would make
     * two ledger identifiers collide at the authorization boundary. Both
     * {@code PA-RQ-TRANSACTION-ID} and {@code PA-RL-TRANSACTION-ID} therefore reject the value by
     * name.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures both expected exceptions so
     * neither exception escapes the test.</p>
     */
    @Test
    @DisplayName("over-width request and reply transaction identifiers are rejected")
    void overWidthCorrelationTransactionIdsAreRejectedRatherThanTruncated() {
        String overWidthTransaction = "0000000000000123";

        AuthMessageFormatException requestFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> request(CARD_NUM, overWidthTransaction));
        AuthMessageFormatException replyFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> reply(CARD_NUM, overWidthTransaction));

        assertTrue(requestFailure.getMessage().contains("PA-RQ-TRANSACTION-ID"));
        assertTrue(replyFailure.getMessage().contains("PA-RL-TRANSACTION-ID"));
        assertFalse(requestFailure.getMessage().contains(overWidthTransaction));
        assertFalse(replyFailure.getMessage().contains(overWidthTransaction));
    }

    /**
     * Request and reply correlation APIs reject absent key material with {@link NullPointerException}.
     *
     * <p>Assumptions: there is no safe default tokeniser. Returning the raw pair would expose the card
     * number, and an unkeyed derivation would let an observer confirm guesses, so both carriers require
     * the caller to supply an explicitly keyed {@link OpaqueIdentifier}.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures both expected exceptions so
     * neither exception escapes the test.</p>
     */
    @Test
    @DisplayName("correlation derivation requires explicit key material")
    void correlationDerivationRejectsANullTokeniser() {
        assertThrows(
                NullPointerException.class,
                () -> request(CARD_NUM, TRANSACTION_ID).correlationKey(null));
        assertThrows(
                NullPointerException.class,
                () -> reply(CARD_NUM, TRANSACTION_ID).correlationKey(null));
    }

    /**
     * Correlation and ordering tokens are distinct even when they describe the same card.
     *
     * <p>Assumptions: purpose separation prevents an observer from joining queue ordering metadata to
     * a correlation value from an application trace. Both values are stable opaque tokens, but one
     * protects card plus transaction and the other protects only the card under a different purpose.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("correlation and ordering purposes derive unrelated tokens")
    void correlationAndOrderingPurposesRemainScopedApart() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        AuthRequest authRequest = request(CARD_NUM, TRANSACTION_ID);
        String correlation = authRequest.correlationKey(tokeniser);
        String orderGroup = authRequest.orderGroup(tokeniser);

        assertThat(orderGroup)
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .doesNotContain(CARD_NUM)
                .isNotEqualTo(correlation);
    }

    /**
     * The authorization transaction identifier is {@code X(15)}, not the ledger's {@code X(16)}.
     *
     * <p>Assumptions: authorization field order, widths and comma placement are the MQ string
     * contract; {@code CVTRA05Y.cpy} is a separate 350-byte ledger record and is not a conversion
     * template for this payload. Cross-contract coercion is therefore forbidden: an under-width
     * authorization identifier is padded only to fifteen, and a sixteen-character ledger identifier
     * raises {@code CsvAuthCodec.AuthMessageFormatException} for
     * {@code PA-RQ-TRANSACTION-ID} rather than being truncated.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("authorization transaction identifiers remain fifteen characters, not sixteen")
    void authorizationTransactionIdDoesNotAdoptTheLedgerWidth() {
        int ledgerTransactionWidth = 16;
        String encodedShort = fieldAt(
                CsvAuthCodec.encodeRequest(request(CARD_NUM, "TX1")),
                17);

        assertEquals(ledgerTransactionWidth - 1, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(17));
        assertEquals(ledgerTransactionWidth - 1, CsvAuthCodec.REPLY_FIELD_WIDTHS.get(1));
        assertEquals(15, encodedShort.length());
        assertFalse(encodedShort.length() == 16,
                "authorization padding must stop at its own X(15) boundary");

        String ledgerWidthTransaction = "0000000000000123";
        assertEquals(ledgerTransactionWidth, ledgerWidthTransaction.length());
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> request(CARD_NUM, ledgerWidthTransaction));
        assertTrue(failure.getMessage().contains("PA-RQ-TRANSACTION-ID"));
        assertTrue(failure.getMessage().contains("declares 15"));
    }

    /**
     * The authorization merchant identifier is fifteen alphanumeric characters, not ledger 9(09).
     *
     * <p>Assumptions: letters and a leading zero are data in {@code PA-RQ-MERCHANT-ID X(15)}. Reading
     * the field through the ledger's nine-digit picture would either reject the letters or drop the
     * leading zero, so the payload keeps the original text and its authorization width.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("authorization merchant identifiers remain fifteen-character alphanumeric text")
    void authorizationMerchantIdDoesNotAdoptTheLedgerNumericPicture() {
        int ledgerMerchantIdWidth = 9;
        String merchantId = "0ABC00000000001";
        AuthRequest request = requestWithMerchantFields(
                merchantId,
                "MERCHANT NAME",
                "CITY",
                "WA",
                "981010000");
        String payload = CsvAuthCodec.encodeRequest(request);

        assertEquals(15, merchantId.length());
        assertEquals(ledgerMerchantIdWidth + 6, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(12));
        assertEquals(merchantId, fieldAt(payload, 12));
        assertEquals(merchantId, CsvAuthCodec.decodeRequest(payload).merchantId());
        assertTrue(fieldAt(payload, 12).startsWith("0"));
        assertTrue(fieldAt(payload, 12).contains("ABC"));
        assertFalse(fieldAt(payload, 12).chars().allMatch(Character::isDigit),
                "the authorization field must not be coerced to ledger 9(09)");
    }

    /**
     * The authorization merchant name is {@code X(22)}, not the ledger's {@code X(50)}.
     *
     * <p>Assumptions: a ledger-width name cannot be folded into the MQ request by truncation because
     * the discarded suffix may distinguish two merchants. A fifty-character value therefore raises
     * {@code CsvAuthCodec.AuthMessageFormatException} for {@code PA-RQ-MERCHANT-NAME}, while an
     * authorization-width value occupies exactly its twenty-two-character slot.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("authorization merchant names remain twenty-two characters, not fifty")
    void authorizationMerchantNameDoesNotAdoptTheLedgerWidth() {
        int ledgerMerchantNameWidth = 50;
        String authorizationName = "N".repeat(22);
        AuthRequest request = requestWithMerchantFields(
                "MERCHANT0000001",
                authorizationName,
                "CITY",
                "WA",
                "981010000");

        assertEquals(ledgerMerchantNameWidth - 28, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(13));
        assertEquals(authorizationName, fieldAt(CsvAuthCodec.encodeRequest(request), 13));

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> requestWithMerchantFields(
                        "MERCHANT0000001",
                        "N".repeat(ledgerMerchantNameWidth),
                        "CITY",
                        "WA",
                        "981010000"));
        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-NAME"));
        assertTrue(failure.getMessage().contains("declares 22"));
    }

    /**
     * The authorization merchant city is {@code X(13)}, not the ledger's {@code X(50)}.
     *
     * <p>Assumptions: the MQ string's city boundary ends after thirteen characters and the following
     * comma begins the state field. Importing the ledger width would move that delimiter by
     * thirty-seven characters, so a fifty-character ledger city raises
     * {@code CsvAuthCodec.AuthMessageFormatException} for {@code PA-RQ-MERCHANT-CITY} rather than
     * being copied or shortened.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("authorization merchant cities remain thirteen characters, not fifty")
    void authorizationMerchantCityDoesNotAdoptTheLedgerWidth() {
        int ledgerMerchantCityWidth = 50;
        String authorizationCity = "C".repeat(13);
        AuthRequest request = requestWithMerchantFields(
                "MERCHANT0000001",
                "MERCHANT NAME",
                authorizationCity,
                "WA",
                "981010000");

        assertEquals(ledgerMerchantCityWidth - 37, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(14));
        assertEquals(authorizationCity, fieldAt(CsvAuthCodec.encodeRequest(request), 14));

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> requestWithMerchantFields(
                        "MERCHANT0000001",
                        "MERCHANT NAME",
                        "C".repeat(ledgerMerchantCityWidth),
                        "WA",
                        "981010000"));
        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-CITY"));
        assertTrue(failure.getMessage().contains("declares 13"));
    }

    /**
     * The authorization merchant postal code is {@code X(09)}, not the ledger's {@code X(10)}.
     *
     * <p>Assumptions: postal codes are carried as text, so the one-character difference cannot be
     * resolved by numeric conversion or by dropping a leading zero. A ten-character ledger value
     * raises {@code CsvAuthCodec.AuthMessageFormatException} for {@code PA-RQ-MERCHANT-ZIP}; an
     * authorization value remains exactly nine characters on the wire.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("authorization merchant postal codes remain nine characters, not ten")
    void authorizationMerchantZipDoesNotAdoptTheLedgerWidth() {
        int ledgerMerchantZipWidth = 10;
        String authorizationZip = "012345678";
        AuthRequest request = requestWithMerchantFields(
                "MERCHANT0000001",
                "MERCHANT NAME",
                "CITY",
                "WA",
                authorizationZip);

        assertEquals(ledgerMerchantZipWidth - 1, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(16));
        assertEquals(authorizationZip, fieldAt(CsvAuthCodec.encodeRequest(request), 16));
        assertTrue(fieldAt(CsvAuthCodec.encodeRequest(request), 16).startsWith("0"));

        String ledgerWidthZip = "0123456789";
        assertEquals(ledgerMerchantZipWidth, ledgerWidthZip.length());
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> requestWithMerchantFields(
                        "MERCHANT0000001",
                        "MERCHANT NAME",
                        "CITY",
                        "WA",
                        ledgerWidthZip));
        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-ZIP"));
        assertTrue(failure.getMessage().contains("declares 9"));
    }

    /**
     * Authorization carries a two-character state even though the ledger record has no state field.
     *
     * <p>Assumptions: the verified merchant portion of {@code CVTRA05Y.cpy} contains identifier,
     * name, city and postal code fields only. The state at authorization ordinal sixteen therefore
     * cannot be dropped merely because no ledger column receives it; it remains a separate
     * {@code X(02)} MQ field between city and postal code.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("authorization state remains a field even though the ledger has no counterpart")
    void authorizationMerchantStateHasNoLedgerCounterpart() {
        List<String> ledgerMerchantFields = List.of(
                "TRAN-MERCHANT-ID",
                "TRAN-MERCHANT-NAME",
                "TRAN-MERCHANT-CITY",
                "TRAN-MERCHANT-ZIP");
        AuthRequest request = requestWithMerchantFields(
                "MERCHANT0000001",
                "MERCHANT NAME",
                "CITY",
                "WA",
                "981010000");
        String payload = CsvAuthCodec.encodeRequest(request);

        assertEquals("PA-RQ-MERCHANT-STATE", CsvAuthCodec.REQUEST_FIELD_NAMES.get(15));
        assertEquals(2, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(15));
        assertEquals("WA", fieldAt(payload, 15));
        assertEquals("WA", CsvAuthCodec.decodeRequest(payload).merchantState());
        assertFalse(ledgerMerchantFields.stream().anyMatch(name -> name.contains("STATE")),
                "the ledger field inventory has no state counterpart");
    }

    /**
     * A seventeen-field request raises {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: removing the eighteenth field leaves sixteen delimiters and therefore exactly
     * seventeen tokens. The decoder must report that structural count before attempting to assign
     * any token to a copybook field, and the report must not reproduce the card number or payload.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a seventeen-field request is rejected by structural count")
    void requestDecoderRejectsSeventeenFields() {
        int finalDelimiter = ORDERED_REQUEST_PAYLOAD.lastIndexOf(',');
        String seventeenFields = ORDERED_REQUEST_PAYLOAD.substring(0, finalDelimiter);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(seventeenFields));

        assertTrue(failure.getMessage().contains("request payload carries 17 delimited fields"));
        assertTrue(failure.getMessage().contains("copybook declares 18"));
        assertFalse(failure.getMessage().contains("CCCCCCCCCCCCCCCC"));
        assertFalse(failure.getMessage().contains(seventeenFields));
    }

    /**
     * A nineteenth non-pad request field raises {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: one excess token is tolerated only when it contains pad alone. Appending
     * {@code EXTRA} therefore proves the compatibility rule cannot be used to discard a genuine
     * nineteenth business value.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a nineteenth non-pad request field is rejected")
    void requestDecoderRejectsNineteenNonPadFields() {
        String nineteenFields = ORDERED_REQUEST_PAYLOAD + ",EXTRA";

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(nineteenFields));

        assertTrue(failure.getMessage().contains("request payload carries 19 delimited fields"));
        assertTrue(failure.getMessage().contains("copybook declares 18"));
        assertFalse(failure.getMessage().contains(nineteenFields));
    }

    /**
     * A five-field reply raises {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the amount field is removed together with the delimiter that precedes it, so
     * the payload has four delimiters and five business tokens. The decoder must not reinterpret the
     * response reason as an amount to make the count fit.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a five-field reply is rejected by structural count")
    void replyDecoderRejectsFiveFields() {
        String fiveFields =
                "4111111111111111,TRN000000000001,A00001,00,0000";

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeReply(fiveFields));

        assertTrue(failure.getMessage().contains("reply payload carries 5 delimited fields"));
        assertTrue(failure.getMessage().contains("copybook declares 6"));
        assertFalse(failure.getMessage().contains(CARD_NUM));
        assertFalse(failure.getMessage().contains(fiveFields));
    }

    /**
     * A seventh non-pad reply business field raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the canonical trailing comma begins the tolerated seventh framing token, but
     * placing {@code EXTRA} in that token turns it into business data. The decoder must reject it
     * rather than silently treating the reply as six fields.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a seventh non-pad reply business field is rejected")
    void replyDecoderRejectsSevenNonPadBusinessFields() {
        String sevenFields = CANONICAL_REPLY_PAYLOAD + "EXTRA";

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeReply(sevenFields));

        assertTrue(failure.getMessage().contains("reply payload carries 7 delimited fields"));
        assertTrue(failure.getMessage().contains("copybook declares 6"));
        assertFalse(failure.getMessage().contains(CARD_NUM));
        assertFalse(failure.getMessage().contains(sevenFields));
    }

    /**
     * Under-width character values are accepted BY A CARRIER and restored to declared width on
     * re-encode.
     *
     * <p>Assumptions: decoded carriers store character values without trailing COBOL pad, so strict
     * exact-width construction would reject the codec's own output. The carrier boundary therefore
     * accepts short values, preserves their meaningful characters and right-pads them when rebuilding
     * the wire; no value is silently truncated.</p>
     *
     * <p>Assumptions: this tolerance belongs to the CARRIER and not to the WIRE, and the distinction
     * is the whole of why both this test and the truncation tests below can hold at once. What is
     * decoded here is a full-width payload, because the encoder padded every field out to its declared
     * width first. A payload that arrives short is a different thing entirely -- bytes lost in transit
     * rather than a value stored trimmed -- and
     * {@code truncatedRequestPayloadIsRefusedRatherThanShorteningTheTransactionId} pins its
     * refusal.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("under-width carrier values are accepted and right-padded on re-encode")
    void underWidthCharacterFieldsAreAcceptedAndRightPadded() {
        AuthRequest shortRequest = request("4111", "TX1");
        AuthReply shortReply = reply("4111", "TX1");
        String requestPayload = CsvAuthCodec.encodeRequest(shortRequest);
        String replyPayload = CsvAuthCodec.encodeReply(shortReply);

        assertEquals(shortRequest, CsvAuthCodec.decodeRequest(requestPayload));
        assertEquals(shortReply, CsvAuthCodec.decodeReply(replyPayload));
        assertEquals(16, fieldAt(requestPayload, 2).length());
        assertEquals(15, fieldAt(requestPayload, 17).length());
        assertEquals(16, fieldAt(replyPayload, 0).length());
        assertEquals(15, fieldAt(replyPayload, 1).length());
        assertTrue(fieldAt(requestPayload, 2).startsWith("4111"));
        assertTrue(fieldAt(requestPayload, 17).startsWith("TX1"));
    }

    /**
     * A request payload short of its declared length is refused instead of shortening the transaction
     * identifier.
     *
     * <p>Refactoring Rationale: width used to be checked as an upper bound alone, so a payload one,
     * two or three bytes short still split into eighteen fields, every token still fitted, and the
     * final token -- {@code PA-RQ-TRANSACTION-ID} -- silently lost that many characters. The
     * consequences compounded rather than surfacing: the shortened value re-emitted at the full
     * declared width, so the corruption became indistinguishable from a well-formed payload; the
     * correlation token changed, so a reply carrying the original identifier no longer matched it; and
     * two identifiers differing only in their last character collapsed onto one, so a genuinely
     * distinct authorization could be discarded as a duplicate by a deduplicating transport. Each
     * dropped-byte count is asserted separately because the defect was uniform rather than positional
     * and a single case would not have shown that.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param droppedBytes the {@code int} number of trailing characters to remove from an otherwise
     *     well-formed payload
     */
    @ParameterizedTest(name = "a payload {0} character(s) short is refused")
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("a truncated request payload is refused rather than shortening the transaction id")
    void truncatedRequestPayloadIsRefusedRatherThanShorteningTheTransactionId(int droppedBytes) {
        String intact = CsvAuthCodec.encodeRequest(request(CARD_NUM, TRANSACTION_ID));
        String truncated = intact.substring(0, intact.length() - droppedBytes);

        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, intact.length());

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(truncated));

        assertTrue(failure.getMessage().contains("PA-RQ-TRANSACTION-ID"));
        assertTrue(failure.getMessage().contains("declares 15"));
        assertTrue(failure.getMessage()
                .contains("as " + (CsvAuthCodec.TRANSACTION_ID_WIDTH - droppedBytes) + " characters"));
        assertFalse(failure.getMessage().contains(TRANSACTION_ID),
                "the transaction identifier is sensitive and must stay out of the diagnostic");
    }

    /**
     * A request whose trailing transaction identifier is entirely absent is refused.
     *
     * <p>Assumptions: cutting the payload at its final delimiter leaves eighteen fields of which the
     * last is empty, so the field-count check cannot see the loss and only a width requirement can.
     * An empty identifier is the most damaging case of the same defect rather than a separate one: it
     * still re-emits as fifteen pad characters, and every request that lost its identifier would then
     * correlate and deduplicate against every other.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     */
    @Test
    @DisplayName("a request with an entirely absent transaction id is refused")
    void requestWithAnAbsentTrailingTransactionIdIsRefused() {
        String intact = CsvAuthCodec.encodeRequest(request(CARD_NUM, TRANSACTION_ID));
        String emptyTail = intact.substring(0, intact.lastIndexOf(',') + 1);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(emptyTail));

        assertTrue(failure.getMessage().contains("PA-RQ-TRANSACTION-ID"));
        assertTrue(failure.getMessage().contains("as 0 characters"));
    }

    /**
     * A short text field in the middle of a request payload is refused by its own name.
     *
     * <p>Assumptions: the requirement is a property of every text field rather than of the trailing
     * one, so it is asserted away from the end of the payload as well. This case is the one that shows
     * the defect was never positional: the trailing field merely made it easiest to reach, because
     * losing bytes in transit removes them from the end.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     */
    @Test
    @DisplayName("a short mid-payload text field is refused by name")
    void shortMidPayloadTextFieldIsRefused() {
        String shortMerchantCategory = "JJ";
        String malformed = withField(ORDERED_REQUEST_PAYLOAD, 9, shortMerchantCategory);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(malformed));

        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-CATAGORY-CODE"));
        assertTrue(failure.getMessage().contains("as 2 characters"));
        assertTrue(failure.getMessage().contains("declares 4"));
    }

    /**
     * A transport buffer one byte short of the declared request length is refused.
     *
     * <p>Assumptions: the byte-oriented entry point is asserted separately from the text one because it
     * is the route a queue consumer takes, and because it is the route the substitution defect
     * travelled: a supplementary code point encoded to one byte instead of two, so the frame arrived at
     * 168 bytes with every field after the substitution shifted one position earlier. A caller that
     * passes the length it received cannot detect that by itself, which is why the refusal has to come
     * from the codec.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     */
    @Test
    @DisplayName("a 168-byte request frame is refused")
    void truncatedRequestByteFrameIsRefused() {
        byte[] intact = CsvAuthCodec.encodeRequestBytes(request(CARD_NUM, TRANSACTION_ID));

        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, intact.length);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(intact, intact.length - 1));

        assertTrue(failure.getMessage().contains("PA-RQ-TRANSACTION-ID"));
        assertTrue(failure.getMessage().contains("declares 15"));
    }

    /**
     * A short reply text field is refused, and the tolerated trailing pad token is still tolerated.
     *
     * <p>Assumptions: both halves are asserted in one test because they are the same boundary read two
     * ways. The reply's trailing framing token is structure the reference {@code STRING} produces and
     * is dropped before any width is examined, so requiring the six business tokens to reach their
     * declared widths cannot reject the canonical form, the 62-character form without a trailing
     * delimiter, or the 64-byte frame the reference put actually sends.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     */
    @Test
    @DisplayName("a short reply text field is refused while the trailing pad token stays tolerated")
    void shortReplyTextFieldIsRefusedWithoutBreakingTheTrailingPadTolerance() {
        String malformed = withField(CANONICAL_REPLY_PAYLOAD, 2, "A0001");

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeReply(malformed));

        assertTrue(failure.getMessage().contains("PA-RL-AUTH-ID-CODE"));
        assertTrue(failure.getMessage().contains("as 5 characters"));
        assertTrue(failure.getMessage().contains("declares 6"));

        AuthReply canonical = CsvAuthCodec.decodeReply(CANONICAL_REPLY_PAYLOAD);
        assertEquals(canonical, CsvAuthCodec.decodeReply(CANONICAL_REPLY_PAYLOAD + " "));
        assertEquals(canonical, CsvAuthCodec.decodeReply(
                CANONICAL_REPLY_PAYLOAD.substring(0, CANONICAL_REPLY_PAYLOAD.length() - 1)));
    }

    /**
     * A zero-suppressed money token is still accepted, because the money field is exempt by design.
     *
     * <p>Trade-offs: the width requirement exempts the one money ordinal in each payload, and this test
     * is what keeps that exemption honest by pinning it. The reference reply mask
     * {@code PIC -zzzzzzzzz9.99} suppresses leading zeros and the transport may pad around the token,
     * so a legitimate amount need not occupy its declared fourteen positions; the geometry that matters
     * is inside the value -- at most ten integer digits, one literal point, exactly two fraction digits
     * -- and that is what refuses a truncated amount. A width requirement over the money token would
     * reject amounts the reference program emits.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a zero-suppressed reply amount is accepted despite being narrower than fourteen")
    void zeroSuppressedReplyAmountRemainsExemptFromTheWidthRequirement() {
        String suppressed = withField(CANONICAL_REPLY_PAYLOAD, 5, "100.99");

        AuthReply decoded = CsvAuthCodec.decodeReply(suppressed);

        assertEquals(Money.of("100.99"), decoded.approvedAmount());
        assertEquals(6, fieldAt(suppressed, 5).length());
        assertTrue(fieldAt(suppressed, 5).length() < CsvAuthCodec.REPLY_FIELD_WIDTHS.get(5));
    }

    /**
     * A character the single-byte wire cannot represent is refused where the field enters.
     *
     * <p>Refactoring Rationale: such a character used to be accepted by the carrier and then replaced
     * with a question mark by the encoder, which changed the value with nothing raised and nothing a
     * caller could inspect. The emoji case was worse than lossy: a supplementary code point is two Java
     * characters and encodes to ONE substitute byte, so the payload came out a byte short of its
     * declared length, every field after it shifted one position earlier, and the under-width token
     * that produced was absorbed silently as well. Refusing at the field is what makes the failure name
     * the field, and it closes the text and byte emission paths together.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param merchantName the {@link String} merchant name carrying one character above U+00FF
     */
    @ParameterizedTest(name = "an unrepresentable character in {0} is refused")
    @ValueSource(strings = {"CAF\u0100", "OMEGA \u03a9", "CJK \u4e2d", "CAFE\u0301",
        "OK \ud83d\ude00"})
    @DisplayName("a character outside the single-byte wire range is refused, never substituted")
    void unrepresentableCharacterIsRefusedRatherThanSubstituted(String merchantName) {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> new AuthRequest("220718", "120000", CARD_NUM, "A", "1231", "0100", "POS",
                        "000", Money.of("1000.00"), "5411", "USA", "90", "MERCH000000001",
                        merchantName, "CITY", "TX", "75001", TRANSACTION_ID));

        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-NAME"));
        assertTrue(failure.getMessage()
                .contains("the single-byte wire character set cannot represent"));
        assertTrue(failure.getMessage().contains("U+0000 to U+00FF range"));
        assertFalse(failure.getMessage().contains("?"),
                "a refusal must not show the substitute the encoder would have written");
    }

    /**
     * A character inside the single-byte wire range still round-trips as exactly one byte.
     *
     * <p>Assumptions: this is the counterpart the refusal above needs to be meaningful. The wire
     * encoding maps U+0000 through U+00FF onto the byte values 0x00 through 0xFF one for one, so an
     * accented Latin-1 letter is legitimate data on this wire and is preserved rather than refused;
     * refusing it would narrow the contract instead of protecting it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a Latin-1 character round-trips as one byte and is not refused")
    void latinOneCharacterRoundTripsAsExactlyOneByte() {
        AuthRequest accented = new AuthRequest("220718", "120000", CARD_NUM, "A", "1231", "0100",
                "POS", "000", Money.of("1000.00"), "5411", "USA", "90", "MERCH000000001",
                "CAF\u00c9", "CITY", "TX", "75001", TRANSACTION_ID);

        byte[] wire = CsvAuthCodec.encodeRequestBytes(accented);

        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, wire.length);
        assertEquals((byte) 0xc9, wire[REQUEST_DELIMITER_POSITIONS.get(12) + 4]);
        assertEquals("CAF\u00c9", CsvAuthCodec.decodeRequest(wire, wire.length).merchantName());
    }

    /**
     * Every emitted payload carries exactly one byte per character.
     *
     * <p>Assumptions: this is the post-condition whose absence let a 168-byte request leave the
     * encoder. The declared wire length is handed to the transport as the message length without
     * measuring the array, so a payload whose byte count and character count disagree would arrive at a
     * consumer with every field after the disagreement shifted, and the consumer would have no way to
     * tell. Asserting it over both payloads and over a negative amount covers the sign position as
     * well.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("emitted payloads are one byte per character in both directions")
    void emittedPayloadsCarryOneBytePerCharacter() {
        String requestPayload = CsvAuthCodec.encodeRequest(request(CARD_NUM, TRANSACTION_ID));
        byte[] requestWire = CsvAuthCodec.encodeRequestBytes(request(CARD_NUM, TRANSACTION_ID));
        String replyPayload = CsvAuthCodec.encodeReply(replyWith(Money.of("-100.99")));
        byte[] replyWire = CsvAuthCodec.encodeReplyBytes(replyWith(Money.of("-100.99")));

        assertEquals(requestPayload.length(), requestWire.length);
        assertEquals(replyPayload.length(), replyWire.length);
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, requestWire.length);
        assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, replyWire.length);
    }

    /**
     * An over-width {@code PA-RQ-AUTH-TYPE} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Alternatives Considered: truncating a five-character value into the four-character slot.
     * That would produce a syntactically valid request carrying a different authorization type, so
     * the decoder rejects the field by name and includes the non-sensitive value for diagnosis.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("an over-width non-sensitive request field is rejected by name")
    void overWidthRequestFieldIsRejectedWithoutTruncation() {
        String overWidthType = "FIVES";
        String malformed = withField(ORDERED_REQUEST_PAYLOAD, 3, overWidthType);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(malformed));

        assertTrue(failure.getMessage().contains("PA-RQ-AUTH-TYPE"));
        assertTrue(failure.getMessage().contains("declares 4"));
        assertTrue(failure.getMessage().contains(overWidthType));
    }

    /**
     * Over-width request and reply transaction fields raise
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: this exercises malformed wire text rather than only carrier construction. Both
     * {@code PA-RQ-TRANSACTION-ID} and {@code PA-RL-TRANSACTION-ID} are sensitive
     * {@code X(15)} fields, so a sixteen-character token must be rejected by name and withheld from
     * the diagnostic rather than truncated.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures both expected exceptions so
     * neither exception escapes the test.</p>
     */
    @Test
    @DisplayName("over-width request and reply transaction fields are rejected from wire text")
    void overWidthWireTransactionIdsAreRejectedWithoutDisclosure() {
        String overWidthTransaction = "0000000000000123";
        String requestPayload = withField(ORDERED_REQUEST_PAYLOAD, 17, overWidthTransaction);
        String replyPayload = withField(CANONICAL_REPLY_PAYLOAD, 1, overWidthTransaction);

        AuthMessageFormatException requestFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(requestPayload));
        AuthMessageFormatException replyFailure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeReply(replyPayload));

        assertTrue(requestFailure.getMessage().contains("PA-RQ-TRANSACTION-ID"));
        assertTrue(replyFailure.getMessage().contains("PA-RL-TRANSACTION-ID"));
        assertFalse(requestFailure.getMessage().contains(overWidthTransaction));
        assertFalse(replyFailure.getMessage().contains(overWidthTransaction));
    }

    /**
     * Removing one required request comma raises {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the first delimiter is removed without altering any field character, merging
     * the date and time into one token and reducing the structural count to seventeen. Because the
     * contract is delimiter-positional, no width inference may reconstruct the missing comma.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a request with one required comma absent is rejected")
    void requestDecoderRejectsAnAbsentRequiredComma() {
        String missingFirstComma = ORDERED_REQUEST_PAYLOAD.substring(0, 6)
                + ORDERED_REQUEST_PAYLOAD.substring(7);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(missingFirstComma));

        assertTrue(failure.getMessage().contains("17 delimited fields"));
        assertTrue(failure.getMessage().contains("copybook declares 18"));
        assertFalse(failure.getMessage().contains(missingFirstComma));
    }

    /**
     * A comma inside {@code PA-RQ-MERCHANT-NAME} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: the baseline has no quoting or escaping rule, so an embedded comma is
     * indistinguishable from a field boundary after serialization. Rejecting it at carrier
     * construction preserves the field identity needed for an actionable diagnostic and prevents
     * every later ordinal from shifting.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("an unquoted comma inside a fixed request field is rejected")
    void requestCarrierRejectsAnUnexpectedCommaInsideAField() {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> requestWithMerchantFields(
                        "MERCHANT0000001",
                        "ACME, STORE",
                        "CITY",
                        "WA",
                        "981010000"));

        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-NAME"));
        assertTrue(failure.getMessage().contains("delimiter"));
        assertFalse(failure.getMessage().contains("ACME, STORE"));
    }

    /**
     * A control character inside a character field is refused where the field enters.
     *
     * <p>Refactoring Rationale: this test was written to assert the OPPOSITE and it found a defect,
     * which is why the rationale is recorded rather than the outcome alone. The decode path refused any
     * control character in a whole payload while the encode path refused none, so this class would emit
     * a full-length payload that its own decoder then rejected -- the assertion below originally called
     * {@code encodeRequest} and then {@code decodeRequest} on the result, and the second call threw. The
     * field-level refusal now closes that hole, and the decode-side rule is the one that was kept: a
     * carriage return or line feed inside a value forges or splits a record in any line-oriented log the
     * value later reaches, and an escape sequence reaching a terminal is interpreted rather than
     * displayed.</p>
     *
     * <p>Assumptions: the vectors cover both ISO control ranges rather than only the familiar low one.
     * The delete character and the upper range from U+0080 to U+009F are control characters too, and a
     * check written as a comparison against the space character would have admitted every one of them --
     * which is exactly why the production check uses the platform predicate.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param merchantName the {@link String} merchant name carrying one control character
     */
    @ParameterizedTest(name = "a control character in {0} is refused at the field")
    @ValueSource(strings = {"ACME\nSTORE", "ACME\rSTORE", "ACME\tSTORE", "ACME\u0000STORE",
        "ACME\u001bSTORE", "ACME\u007fSTORE", "ACME\u0085STORE"})
    @DisplayName("a control character inside a field is refused, naming the field")
    void controlCharacterInsideAFieldIsRefused(String merchantName) {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> requestWithMerchantFields(
                        "MERCHANT0000001", merchantName, "CITY", "WA", "981010000"));

        assertTrue(failure.getMessage().contains("PA-RQ-MERCHANT-NAME"),
                "the refusal must name the field, which the payload-level check cannot");
        assertTrue(failure.getMessage().contains("control character"));
    }

    /**
     * Whatever this codec emits, it can read back -- asserted over the values the two rules sit beside.
     *
     * <p>Assumptions: this is the invariant the defect above violated, so it is asserted directly rather
     * than left as a property the individual tests imply. The vectors are the values closest to the two
     * refusals: a space, the printable extremes of the single-byte range, and the highest representable
     * character. Each must survive encode and decode unchanged, because a value this codec accepts on
     * the way out and refuses on the way in is a message that cannot be delivered to its own consumer.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every value the encoder accepts is one the decoder reads back unchanged")
    void everyValueTheEncoderAcceptsIsOneTheDecoderReadsBackUnchanged() {
        for (String merchantName : List.of("ACME STORE", "CAF\u00c9 BAR", "A\u00ffZ", "A!\"#$%&'()*+-./Z",
                "ACME  DOUBLE  SPACED", " LEADING SPACE KEPT")) {
            AuthRequest request = requestWithMerchantFields(
                    "MERCHANT0000001", merchantName, "CITY", "WA", "981010000");

            String payload = CsvAuthCodec.encodeRequest(request);

            assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, payload.length(),
                    "an accepted value must not change the emitted length");
            assertEquals(merchantName, CsvAuthCodec.decodeRequest(payload).merchantName(),
                    "the decoder must read back exactly what the encoder was given");
        }
    }

    /**
     * Invalid sign or point punctuation in {@code PA-RQ-TRANSACTION-AMT} raises
     * {@code CsvAuthCodec.AuthMessageFormatException}.
     *
     * <p>Assumptions: these vectors are inserted into a complete eighteen-field payload so the
     * failure is attributable to the amount grammar rather than to field count. The field is
     * sensitive, so the diagnostic must name it and the violated punctuation rule without quoting the
     * token or the full request.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param malformedAmount the {@link String} amount token containing an invalid sign or point
     */
    @ParameterizedTest(name = "malformed request amount {0} is rejected")
    @ValueSource(strings = {"$000000100.99", "0000000100;99"})
    @DisplayName("invalid request amount sign or point punctuation is rejected")
    void requestDecoderRejectsMalformedAmountPunctuation(String malformedAmount) {
        String payload = withField(ORDERED_REQUEST_PAYLOAD, 8, malformedAmount);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(payload));

        assertTrue(failure.getMessage().contains(REQUEST_AMOUNT_FIELD));
        assertFalse(failure.getMessage().contains(malformedAmount));
        assertFalse(failure.getMessage().contains(payload));
        assertFalse(failure.getMessage().contains("CCCCCCCCCCCCCCCC"));
    }

    /**
     * An ISO control character, including NUL, raises
     * {@code CsvAuthCodec.AuthMessageFormatException} for the request structure.
     *
     * <p>Assumptions: control characters are rejected before tokenization so none can reach a field,
     * a diagnostic or a line-oriented log. NUL and line feed exercise both an invisible terminator
     * and a record-splitting character while sharing the same structural rule.</p>
     *
     * <p>This test returns no value; it captures the expected exception so no exception escapes the
     * test.</p>
     *
     * @param controlCode the {@code int} ISO control code inserted at zero-based payload position 20
     */
    @ParameterizedTest(name = "control code {0} is rejected")
    @ValueSource(ints = {0, 10})
    @DisplayName("request payloads reject NUL and line-feed controls")
    void requestDecoderRejectsControlCharactersIncludingNul(int controlCode) {
        int position = 20;
        String payload = ORDERED_REQUEST_PAYLOAD.substring(0, position)
                + (char) controlCode
                + ORDERED_REQUEST_PAYLOAD.substring(position + 1);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(payload));

        assertTrue(failure.getMessage().contains(
                "control character at zero-based position " + position));
        assertFalse(failure.getMessage().contains("CCCCCCCCCCCCCCCC"));
        assertFalse(failure.getMessage().contains(payload));
    }

    /**
     * A request text longer than the maximum bound raises
     * {@code CsvAuthCodec.AuthMessageFormatException} before structural parsing.
     *
     * <p>Assumptions: the length guard runs before delimiter counting and field construction so work
     * remains bounded for an untrusted queue payload. The oversized suffix contains no delimiter,
     * making the length the first and only applicable rejection.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("request text beyond the maximum payload bound is rejected first")
    void requestDecoderRejectsTextBeyondTheMaximumPayloadLength() {
        String oversized = ORDERED_REQUEST_PAYLOAD
                + "X".repeat(CsvAuthCodec.MAX_PAYLOAD_LENGTH
                        - ORDERED_REQUEST_PAYLOAD.length() + 1);

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(oversized));

        assertEquals(CsvAuthCodec.MAX_PAYLOAD_LENGTH + 1, oversized.length());
        assertTrue(failure.getMessage().contains(
                "beyond the " + CsvAuthCodec.MAX_PAYLOAD_LENGTH));
        assertFalse(failure.getMessage().contains(oversized));
        assertFalse(failure.getMessage().contains("CCCCCCCCCCCCCCCC"));
    }

    /**
     * A byte payload length beyond the maximum raises
     * {@code CsvAuthCodec.AuthMessageFormatException} before decoding bytes.
     *
     * <p>Assumptions: the byte overload must apply the bound to the caller-supplied length before it
     * allocates a string. Testing a buffer that is itself large enough isolates the maximum-bound
     * rejection from the separate rule that the declared length must fit the buffer.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("byte payload lengths beyond the maximum are rejected before decoding")
    void requestDecoderRejectsByteLengthBeyondTheMaximumPayloadLength() {
        byte[] buffer = new byte[CsvAuthCodec.MAX_PAYLOAD_LENGTH + 8];
        int oversizedLength = CsvAuthCodec.MAX_PAYLOAD_LENGTH + 1;

        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(buffer, oversizedLength));

        assertTrue(failure.getMessage().contains(
                "beyond the " + CsvAuthCodec.MAX_PAYLOAD_LENGTH));
        assertTrue(failure.getMessage().contains(oversizedLength + " bytes"));
    }

    /**
     * Negative and buffer-exceeding payload lengths raise
     * {@code CsvAuthCodec.AuthMessageFormatException} for the request byte structure.
     *
     * <p>Assumptions: the caller-supplied payload length is independent of the transport buffer's
     * capacity and is validated in both directions. A negative length cannot identify a prefix, and
     * a length one byte beyond the buffer cannot be satisfied by reading unowned memory.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures both expected exceptions so
     * neither exception escapes the test.</p>
     */
    @Test
    @DisplayName("negative and buffer-exceeding request lengths are rejected")
    void requestDecoderRejectsInvalidBytePayloadLengths() {
        byte[] buffer = CsvAuthCodec.encodeRequestBytes(orderedRequest());

        AuthMessageFormatException negative = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(buffer, -1));
        AuthMessageFormatException beyondBuffer = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.decodeRequest(buffer, buffer.length + 1));

        assertTrue(negative.getMessage().contains("payload length is -1"));
        assertTrue(negative.getMessage().contains("outside the " + buffer.length + "-byte buffer"));
        assertTrue(beyondBuffer.getMessage().contains(
                "payload length is " + (buffer.length + 1)));
        assertTrue(beyondBuffer.getMessage().contains(
                "outside the " + buffer.length + "-byte buffer"));
    }

    /**
     * Every request corpus item survives value and byte round trips with fixed geometry.
     *
     * <p>Assumptions: the assertion is parameterized over independent content shapes so 169 bytes and
     * the seventeen delimiter positions are properties of the contract, not of one golden literal.
     * Value equality proves normalization is stable; re-encoding and byte comparison prove no
     * representation detail changes after decode.</p>
     *
     * <p>This test returns no value.</p>
     *
     * @param request the {@link AuthRequest} corpus item to encode, decode and reproduce
     */
    @ParameterizedTest(name = "request corpus case {index}")
    @MethodSource("requestRoundTripCorpus")
    @DisplayName("request corpus items preserve values, bytes and geometry")
    void requestRoundTripCorpusPreservesValuesBytesAndGeometry(AuthRequest request) {
        String encoded = CsvAuthCodec.encodeRequest(request);
        AuthRequest decoded = CsvAuthCodec.decodeRequest(encoded);
        String reproduced = CsvAuthCodec.encodeRequest(decoded);

        assertEquals(request, decoded);
        assertEquals(encoded, reproduced);
        assertArrayEqualsAsWireBytes(encoded, CsvAuthCodec.encodeRequestBytes(decoded));
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, encoded.length());
        assertEquals(REQUEST_DELIMITER_POSITIONS, delimiterPositionsOf(encoded));

        String[] fields = encoded.split(",", -1);
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT, fields.length);
        for (int ordinal = 0; ordinal < CsvAuthCodec.REQUEST_FIELD_COUNT; ordinal++) {
            assertEquals(CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(ordinal), fields[ordinal].length(),
                    "request field " + ordinal + " must retain its declared geometry");
        }
    }

    /**
     * Every supported reply corpus item survives value and byte round trips with fixed geometry.
     *
     * <p>Assumptions: these are the reply values the production formatter and parser both support,
     * including zero suppression, leading zeros, under-width identifiers and significant
     * leading/internal spaces. The sixth trailing comma is retained as a seventh empty framing token
     * but never becomes a seventh business component.</p>
     *
     * <p>This test returns no value.</p>
     *
     * @param reply the {@link AuthReply} corpus item to encode, decode and reproduce
     */
    @ParameterizedTest(name = "reply corpus case {index}")
    @MethodSource("replyRoundTripCorpus")
    @DisplayName("supported reply corpus items preserve values, bytes and geometry")
    void replyRoundTripCorpusPreservesValuesBytesAndGeometry(AuthReply reply) {
        String encoded = CsvAuthCodec.encodeReply(reply);
        AuthReply decoded = CsvAuthCodec.decodeReply(encoded);
        String reproduced = CsvAuthCodec.encodeReply(decoded);

        assertEquals(reply, decoded);
        assertEquals(encoded, reproduced);
        assertArrayEqualsAsWireBytes(encoded, CsvAuthCodec.encodeReplyBytes(decoded));
        assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, encoded.length());
        assertEquals(REPLY_DELIMITER_POSITIONS, delimiterPositionsOf(encoded));

        String[] fields = encoded.split(",", -1);
        assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT + 1, fields.length);
        assertTrue(fields[CsvAuthCodec.REPLY_FIELD_COUNT].isEmpty());
        for (int ordinal = 0; ordinal < CsvAuthCodec.REPLY_FIELD_COUNT; ordinal++) {
            assertEquals(CsvAuthCodec.REPLY_FIELD_WIDTHS.get(ordinal), fields[ordinal].length(),
                    "reply field " + ordinal + " must retain its declared geometry");
        }
    }

    /**
     * Zero uses the production canonical text in both round-trip corpora, per each field's own picture.
     *
     * <p>Assumptions: the request's forced sign position emits {@code +} for zero because zero is not
     * negative, while the reply's sign-CONTROL position emits a blank and its final integer position is
     * forced to {@code 0}. The two forms represent the same {@link Money} value and are intentionally not
     * textually identical, which is why zero is asserted in both corpora rather than in one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("round-trip corpus zero uses plus-signed request and blank-sign reply text")
    void roundTripCorpusZeroUsesTheProductionCanonicalText() {
        AuthRequest request = requestWith(Money.ofCents(0L));
        AuthReply reply = replyWith(Money.ofCents(0L));
        String requestPayload = CsvAuthCodec.encodeRequest(request);
        String replyPayload = CsvAuthCodec.encodeReply(reply);

        assertEquals("0000000000.00", fieldAt(requestPayload, 8));
        assertEquals("          0.00", fieldAt(replyPayload, 5));
        assertNoPlusSign(fieldAt(replyPayload, 5));
        assertEquals(request, CsvAuthCodec.decodeRequest(requestPayload));
        assertEquals(reply, CsvAuthCodec.decodeReply(replyPayload));
    }

    /**
     * A formatted negative reply round-trips through the production parser unchanged.
     *
     * <p>Assumptions: the emitted field is the baseline mask {@code -       100.99} -- a minus in the
     * fixed sign position of {@code PIC -zzzzzzzzz9.99}, declared at {@code COPAUA0C.cbl} line 66, then
     * the zero-suppression blanks that the magnitude does not reach, then the digits. The parser reads the
     * sign and then removes exactly that blank run, so the reply codec is closed under its own public
     * contract: whatever {@code encodeReply} emits, {@code decodeReply} accepts and returns equal.</p>
     *
     * <p>Assumptions: the whole reply is compared rather than the amount alone, so the assertion proves
     * the round trip of the message and not merely of one field. An earlier revision asserted the
     * opposite -- that this exact payload could not be decoded -- which recorded a real defect as though
     * it were a contract; the parser now removes the pad the mask produces and the geometry assertions
     * below are unchanged, so the closure is proved rather than excused.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the production negative reply mask decodes back to the same reply")
    void negativeReplyCorpusCaseRoundTripsThroughTheProductionParser() {
        AuthReply negative = replyWith(Money.of("-100.99"));
        String encoded = CsvAuthCodec.encodeReply(negative);

        assertEquals("-       100.99", fieldAt(encoded, 5));
        assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, encoded.length());
        assertEquals(REPLY_DELIMITER_POSITIONS, delimiterPositionsOf(encoded));

        AuthReply decoded = CsvAuthCodec.decodeReply(encoded);
        assertEquals(negative, decoded);
        assertEquals(Money.of("-100.99"), decoded.approvedAmount());
        assertEquals(encoded, CsvAuthCodec.encodeReply(decoded));
    }

    /**
     * A blank inside the integer digit run is still refused after the sign and its pad are read.
     *
     * <p>Assumptions: this is the counterpart of the round trip above and is what keeps the pad removal
     * narrow. The pad is removed only where the mask can produce it, between the sign position and the
     * first digit, so a token whose blank falls after a digit is not a zero-suppressed rendering of
     * anything and is rejected rather than being silently read as the digits with the gap closed up.</p>
     *
     * <p>This test takes no parameter and returns no value; it captures the expected exception so no
     * exception escapes the test.</p>
     */
    @Test
    @DisplayName("a blank inside the integer digits is refused, not closed up")
    void aBlankInsideTheIntegerDigitsIsRefused() {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.parseMoney("-      1 0.99", REPLY_AMOUNT_FIELD));

        assertTrue(failure.getMessage().contains(REPLY_AMOUNT_FIELD));
        assertTrue(failure.getMessage().contains("digit position"));
        assertFalse(failure.getMessage().contains(CARD_NUM));
    }

    /**
     * Captures a reply-money parse rejection and verifies that it names the copybook field.
     *
     * <p>Assumptions: every malformed token test uses the same sensitive field. Centralizing the
     * field-name assertion keeps those tests focused on the distinct grammar reason while still
     * proving every diagnostic points to {@code PA-RL-APPROVED-AMT}; the helper returns the captured
     * exception so each caller can assert its own actionable reason.</p>
     *
     * @param malformedToken the {@link String} text expected to violate the reply amount grammar
     * @return the captured {@link AuthMessageFormatException}
     */
    private static AuthMessageFormatException assertReplyMoneyParseFailure(String malformedToken) {
        AuthMessageFormatException failure = assertThrows(
                AuthMessageFormatException.class,
                () -> CsvAuthCodec.parseMoney(malformedToken, REPLY_AMOUNT_FIELD));
        assertTrue(failure.getMessage().contains(REPLY_AMOUNT_FIELD));
        return failure;
    }

    /**
     * Asserts that a byte payload is the single-byte encoding of the expected text.
     *
     * <p>Assumptions: the codec encodes and decodes with a single-byte charset, so a declared character
     * width is also a byte width and the array's length equals the text's length. Comparing through
     * that same charset is what makes the assertion a statement about the wire rather than about the
     * platform's default encoding, which would differ between a developer machine and a container.</p>
     *
     * <p>This helper returns no value; JUnit reports an assertion failure when the byte length or
     * decoded text differs from the expected wire text.</p>
     *
     * @param expectedText the {@link String} payload text the bytes must carry
     * @param actualBytes the {@code byte[]} bytes the codec emitted
     */
    private static void assertArrayEqualsAsWireBytes(String expectedText, byte[] actualBytes) {
        assertEquals(expectedText.length(), actualBytes.length,
                "a single-byte encoding must produce one byte per character");
        assertEquals(expectedText, new String(actualBytes, StandardCharsets.ISO_8859_1));
    }

}
