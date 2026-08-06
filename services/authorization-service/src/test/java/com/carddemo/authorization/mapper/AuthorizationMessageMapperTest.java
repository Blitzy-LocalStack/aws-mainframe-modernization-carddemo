package com.carddemo.authorization.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.authorization.dto.AuthorizationReplyPayload;
import com.carddemo.authorization.dto.AuthorizationRequestPayload;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the structured payloads and the delimited wire records remain one contract.
 *
 * <p>The two declarations under test are {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} lines
 * 19 to 36 for the eighteen request fields and {@code cpy/CCPAURLY.cpy} lines 19 to 24 for the six reply
 * fields. The codec owns the wire form of both; the payload records restate the same fields as a
 * structured type. These assertions are what keep the restatement honest.</p>
 */
class AuthorizationMessageMapperTest {

    /**
     * The factory backing the engine, held so it can be closed after the class has run.
     */
    private static ValidatorFactory factory;

    /**
     * The mapper under test, built over an engine from the default provider.
     */
    private static AuthorizationMessageMapper mapper;

    /**
     * The ordinal of the request's money component, which is not a character field.
     *
     * <p>Assumptions: the amount is excluded from the width sweep because its declared entry in the
     * codec's width table is the thirteen-character RECEIVER the reference program reads into, not a
     * character count this record carries -- the record holds an exact decimal instead. Sweeping it as
     * text would assert a width against a type that has none.</p>
     */
    private static final int REQUEST_AMOUNT_ORDINAL = 8;

    /**
     * The ordinals of the two request components whose copybook picture is numeric.
     *
     * <p>Assumptions: {@code PA-RQ-PROCESSING-CODE PIC 9(06)} at line 26 and
     * {@code PA-RQ-POS-ENTRY-MODE PIC 9(02)} at line 30 are the only two numeric pictures among the
     * eighteen, so they are the only two the payload constrains to digits. Filling them with letters in
     * the sweep below would fail for the right reason and would obscure the width property under test.</p>
     */
    private static final List<Integer> NUMERIC_REQUEST_ORDINALS = List.of(7, 11);

    /**
     * Builds the mapper once for the class.
     */
    @BeforeAll
    static void buildMapper() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        mapper = new AuthorizationMessageMapper(validator);
    }

    /**
     * Releases the validation engine's resources.
     */
    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * Every request component crosses to the component of the same meaning, in the same order.
     *
     * <p>Assumptions: each of the eighteen is asserted individually rather than through a round trip
     * alone, because a round trip is identity even when two components of the same type have been swapped
     * -- swapping twice restores the original. Only a per-component assertion catches a mis-wiring, and
     * the fixture gives every component a distinct value so a swap cannot pass.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all eighteen request components cross to the same meaning")
    void everyRequestComponentCrossesToTheSameMeaning() {
        AuthRequest source = distinctRequest();

        AuthorizationRequestPayload payload = mapper.toPayload(source);

        assertEquals(source.authDate(), payload.authDate());
        assertEquals(source.authTime(), payload.authTime());
        assertEquals(source.cardNum(), payload.cardNumber());
        assertEquals(source.authType(), payload.authType());
        assertEquals(source.cardExpiryDate(), payload.cardExpiryDate());
        assertEquals(source.messageType(), payload.messageType());
        assertEquals(source.messageSource(), payload.messageSource());
        assertEquals(source.processingCode(), payload.processingCode());
        assertEquals(source.transactionAmount(), payload.transactionAmount());
        assertEquals(source.merchantCategoryCode(), payload.merchantCategoryCode());
        assertEquals(source.acquirerCountryCode(), payload.acquirerCountryCode());
        assertEquals(source.posEntryMode(), payload.posEntryMode());
        assertEquals(source.merchantId(), payload.merchantId());
        assertEquals(source.merchantName(), payload.merchantName());
        assertEquals(source.merchantCity(), payload.merchantCity());
        assertEquals(source.merchantState(), payload.merchantState());
        assertEquals(source.merchantZip(), payload.merchantZip());
        assertEquals(source.transactionId(), payload.transactionId());
    }

    /**
     * Every reply component crosses to the component of the same meaning, in the same order.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all six reply components cross to the same meaning")
    void everyReplyComponentCrossesToTheSameMeaning() {
        AuthReply source = new AuthReply("4111111111111111", "TXN000000000001", "104530", "05",
                "4100", Money.ZERO);

        AuthorizationReplyPayload payload = mapper.toPayload(source);

        assertEquals(source.cardNum(), payload.cardNumber());
        assertEquals(source.transactionId(), payload.transactionId());
        assertEquals(source.authIdCode(), payload.authIdCode());
        assertEquals(source.authRespCode(), payload.authResponseCode());
        assertEquals(source.authRespReason(), payload.authResponseReason());
        assertEquals(source.approvedAmount(), payload.approvedAmount());
    }

    /**
     * A request survives a crossing in both directions unchanged.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request round-trips through the payload unchanged")
    void aRequestRoundTripsUnchanged() {
        AuthRequest source = distinctRequest();

        assertEquals(source, mapper.toWireRecord(mapper.toPayload(source)));
    }

    /**
     * A reply survives a crossing in both directions unchanged.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reply round-trips through the payload unchanged")
    void aReplyRoundTripsUnchanged() {
        AuthReply source = new AuthReply("4111111111111111", "TXN000000000001", "104530", "00",
                "0000", Money.of("100.99"));

        assertEquals(source, mapper.toWireRecord(mapper.toPayload(source)));
    }

    /**
     * No request payload width is narrower than the width its copybook declares.
     *
     * <p>Assumptions: the fixture is built FROM the codec's published width table rather than from
     * literals, so every character component carries exactly its declared width and the assertion is that
     * the crossing accepts all of them. A payload constraint narrower than a copybook field would refuse a
     * value the reference program accepts, which is the class of drift this test exists to catch, and
     * driving it from the table means a width that moves is restated in one place rather than two.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every request component accepts its full declared copybook width")
    void everyRequestComponentAcceptsItsDeclaredWidth() {
        AuthRequest atFullWidth = requestAtDeclaredWidths();

        AuthorizationRequestPayload payload = mapper.toPayload(atFullWidth);

        assertEquals(atFullWidth, mapper.toWireRecord(payload));
        assertEquals(CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(2).intValue(),
                payload.cardNumber().length());
        assertEquals(CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(13).intValue(),
                payload.merchantName().length());
    }

    /**
     * No reply payload width is narrower than the width its copybook declares.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every reply component accepts its full declared copybook width")
    void everyReplyComponentAcceptsItsDeclaredWidth() {
        AuthReply atFullWidth = new AuthReply(filled(CsvAuthCodec.REPLY_FIELD_WIDTHS.get(0)),
                filled(CsvAuthCodec.REPLY_FIELD_WIDTHS.get(1)),
                filled(CsvAuthCodec.REPLY_FIELD_WIDTHS.get(2)), "00", "0000",
                Money.of(Money.MAX_MAGNITUDE));

        AuthorizationReplyPayload payload = mapper.toPayload(atFullWidth);

        assertEquals(atFullWidth, mapper.toWireRecord(payload));
        assertEquals(CsvAuthCodec.REPLY_FIELD_WIDTHS.get(0).intValue(),
                payload.cardNumber().length());
    }

    /**
     * A wire record the codec accepts can still be refused by the payload's value domain.
     *
     * <p>Assumptions: this is the property that makes the payload constraints load-bearing rather than
     * decorative. The request's wire picture carries an explicit sign at {@code CCPAURQY.cpy} line 27, so
     * the codec decodes a negative amount faithfully and must; the payload's domain refuses one, because a
     * negative authorization amount releases credit rather than reserving it. The crossing is where the two
     * meet, and it fails here rather than approving the request.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a negative amount passes the codec and is refused on the crossing")
    void aNegativeAmountIsRefusedOnTheCrossing() {
        AuthRequest negative = new AuthRequest("250801", "104530", "4111111111111111", "0100",
                "1230", "0100", "POS001", "000000", Money.of("-100.99"), "5411", "840", "05",
                "MERCHANT0000001", "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000",
                "TXN000000000001");

        ConstraintViolationException refusal =
                assertThrows(ConstraintViolationException.class, () -> mapper.toPayload(negative));

        assertEquals(1, refusal.getConstraintViolations().size());
    }

    /**
     * A payload violating its contract is refused before a wire record is built from it.
     *
     * <p>Assumptions: three components are wrong at once and three violations are reported, because a
     * producer correcting a payload should learn about all of its faults from one rejection rather than
     * one per attempt.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a payload with three faults is refused with three violations")
    void aPayloadWithSeveralFaultsReportsThemAll() {
        AuthorizationRequestPayload invalid = new AuthorizationRequestPayload("250801", "104530",
                "4111111111111111", "0100", "1230", "0100", "POS001", "ABCDEF",
                Money.of("-1.00"), "5411", "840", "XY", "MERCHANT0000001",
                "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000", "TXN000000000001");

        ConstraintViolationException refusal = assertThrows(ConstraintViolationException.class,
                () -> mapper.toWireRecord(invalid));

        assertEquals(3, refusal.getConstraintViolations().size());
    }

    /**
     * The mapper refuses a null on every crossing rather than returning a half-built value.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every crossing refuses a null argument")
    void everyCrossingRefusesNull() {
        assertThrows(NullPointerException.class, () -> mapper.toPayload((AuthRequest) null));
        assertThrows(NullPointerException.class, () -> mapper.toPayload((AuthReply) null));
        assertThrows(NullPointerException.class,
                () -> mapper.toWireRecord((AuthorizationRequestPayload) null));
        assertThrows(NullPointerException.class,
                () -> mapper.toWireRecord((AuthorizationReplyPayload) null));
    }

    /**
     * Builds a request whose eighteen components all carry distinct values.
     *
     * <p>Assumptions: distinctness is the requirement. Two components sharing a value would let a
     * mis-wiring between them pass every assertion in this class.</p>
     *
     * @return the request, never {@code null}
     */
    private AuthRequest distinctRequest() {
        return new AuthRequest("250801", "104530", "4111111111111111", "0100", "1230", "0200",
                "POS001", "000001", Money.of("100.99"), "5411", "840", "05", "MERCHANT0000001",
                "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000", "TXN000000000001");
    }

    /**
     * Builds a request whose every character component is exactly its declared copybook width.
     *
     * <p>Assumptions: the two numerically-pictured components are filled with digits and the rest with a
     * repeated letter, because the payload constrains those two to digits and only those two. The amount
     * is set to the greatest representable magnitude, which is the widest value its own type admits.</p>
     *
     * @return the request, never {@code null}
     */
    private AuthRequest requestAtDeclaredWidths() {
        List<Integer> widths = CsvAuthCodec.REQUEST_FIELD_WIDTHS;
        return new AuthRequest(componentAt(0, widths), componentAt(1, widths),
                componentAt(2, widths), componentAt(3, widths), componentAt(4, widths),
                componentAt(5, widths), componentAt(6, widths), componentAt(7, widths),
                Money.of(Money.MAX_MAGNITUDE), componentAt(9, widths), componentAt(10, widths),
                componentAt(11, widths), componentAt(12, widths), componentAt(13, widths),
                componentAt(14, widths), componentAt(15, widths), componentAt(16, widths),
                componentAt(17, widths));
    }

    /**
     * Builds one component value of exactly the declared width for its ordinal.
     *
     * @param ordinal the zero-based wire position of the component
     * @param widths the codec's published width table
     * @return a value of exactly the declared width, digits where the picture is numeric
     * @throws IllegalArgumentException if the amount's ordinal is requested, which carries a decimal
     *     rather than text and so has no width this helper can fill
     */
    private String componentAt(int ordinal, List<Integer> widths) {
        if (ordinal == REQUEST_AMOUNT_ORDINAL) {
            throw new IllegalArgumentException("the amount ordinal carries a decimal, not text");
        }
        int width = widths.get(ordinal);
        return NUMERIC_REQUEST_ORDINALS.contains(ordinal) ? "9".repeat(width) : filled(width);
    }

    /**
     * Builds a value of exactly the requested width from one repeated letter.
     *
     * @param width the number of characters required
     * @return the value, exactly {@code width} characters long
     */
    private String filled(int width) {
        return "X".repeat(width);
    }
}
