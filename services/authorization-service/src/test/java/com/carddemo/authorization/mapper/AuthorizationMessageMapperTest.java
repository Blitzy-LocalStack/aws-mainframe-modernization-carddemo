package com.carddemo.authorization.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.AuthorizationReplyPayload;
import com.carddemo.authorization.dto.AuthorizationRequestPayload;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the structured payloads and the delimited wire records remain one contract.
 *
 * <p>The two declarations under test are {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} lines
 * 19 to 36 for the eighteen request fields and {@code cpy/CCPAURLY.cpy} lines 19 to 24 for the six reply
 * fields. The codec owns the wire form of both; the payload records restate the same fields as a
 * structured type. These assertions are what keep the restatement honest.</p>
 *
 * <p>Assumptions: every case here sits at the CROSSING and not on either side of it. The question asked
 * is what this mapper does with a value the codec already decoded, and what it hands the codec to encode;
 * whether the decoding itself was right is asserted once, in {@code services/common-lib}. The practical
 * test is the one this package's charter states: an assertion that would still pass with this mapper
 * removed belongs elsewhere and is not written here.</p>
 *
 * <p>Assumptions: the verified wire arithmetic is CITED throughout rather than re-proved. The request's
 * eighteen declared widths sum to 153 and its comma-delimited payload is
 * {@link CsvAuthCodec#REQUEST_WIRE_LENGTH} characters; the reply's six sum to 57 and its payload is
 * {@link CsvAuthCodec#REPLY_WIRE_LENGTH}, which is 63 rather than 62 because
 * {@code cbl/COPAUA0C.cbl} L722 to L731 emits six comma literals with the sixth TRAILING the money field
 * at L727. Every case below refers to those published constants instead of a literal, so a width that
 * moves is restated in one place.</p>
 *
 * <p>Refactoring Rationale: four readings this class was briefed with are superseded by
 * {@code com/carddemo/authorization/package-info.java} and by
 * {@code docs/architecture/cobol-to-service-traceability.md}, and each is corrected here rather than
 * acted on, because acting on any one of them would have produced a wrong assertion or a duplicate that
 * hides its own authority. FIRST, divergence D-H is the fourteen-character money layout parsed through a
 * thirteen-character receiver, registered as {@code D-AUTH-AMOUNT-TOLERANT-READ}; it is NOT the reply's
 * transmitted length, which is the separate {@code D-REPLY-PUT-LENGTH}. SECOND, and consequently, the
 * candidate divergence this class was asked to leave unlettered IS D-H, so no new letter is coined for
 * it. THIRD, the architecture rule forbidding a binary floating-point member IS inherited by this
 * package, so no declaration-level prohibition is asserted here; what remains this class's own is
 * behaviour an import graph cannot see, namely a scale of two, half-up rounding, and an amount crossing
 * as a string. FOURTH, the fixtures directory carries its own README and a contract test enrols every
 * resource, so this class is not the sole Explainability carrier for those images -- but the per-case
 * statement of the invariant relied upon is still mandatory, because a README cannot say which invariant
 * a particular case rests on.</p>
 *
 * <p>Assumptions: four neighbouring contracts are owned elsewhere, and each is named so that a reader
 * does not read its absence here as a gap.</p>
 * <ul>
 *   <li>The exhaustive wire contract -- both width tables, the seventeen request and six reply comma
 *       offsets, the sixty-three-not-sixty-two regression, the tolerance for a sixty-fourth byte, the
 *       full-width blank padding that {@code DELIMITED BY SIZE} at L728 produces, and every malformed
 *       input -- belongs to {@code CsvAuthCodec}'s own tests in {@code services/common-lib}.</li>
 *   <li>The committed byte images belong to the classes that enrol them: the request wire and the
 *       amount vectors to the two wire-fixture classes in this package, and the frame images to
 *       {@code fixtures.AuthorizationWireFrameFixtureTest}, which owns the three-way length collision at
 *       64 and asserts on byte 63's VALUE rather than on a length.</li>
 *   <li>The published property names, the two closed decision domains and the JSON string form of both
 *       amounts belong to the transfer-type tests in {@code .dto}.</li>
 *   <li>The single-line renderings of the routing and diagnostic carriers, and the projection's field
 *       set and tokenised event key, belong to {@code MapperRenderingExposureTest}.</li>
 * </ul>
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
     * A reply destination of the shape the deployment nominates, used by the routing assertions.
     *
     * <p>Assumptions: the value is a plain character string and no transport client parses it here, which
     * is the property the routing carrier is documented to hold, so any well-formed address serves.</p>
     */
    private static final String REPLY_QUEUE_URL =
            "https://sqs.eu-west-2.amazonaws.com/000000000000/carddemo-pauth-reply-dev.fifo";

    /**
     * The declared width of the reference program's saved correlation identity.
     *
     * <p>Assumptions: {@code WS-SAVE-CORRELID PIC X(24)} at {@code cbl/COPAUA0C.cbl} L45 is where the
     * reference program holds the identity it captured from the request at L411 to L412 and re-applies to
     * the reply at L745, so twenty-four is the widest identity a baseline-produced exchange can present.
     * It is named here as a lower bound on what the projection must carry, not as an upper one: the column
     * that stores it admits {@link OutboxMessage#CORRELATION_ID_MAX_LENGTH}, which is wider, because a
     * transport-supplied identity is not bounded by the reference field's width.</p>
     */
    private static final int BASELINE_CORRELATION_ID_WIDTH = 24;

    /**
     * The keyed tokeniser the ordering and deduplication assertions derive their expectations from.
     *
     * <p>Assumptions: the key is fixed and built in the source rather than read from configuration, so
     * the two tokens are reproducible from this file alone; the assertions compare the projection's tokens
     * against the reply's own derivation under the same key rather than against literals, because a
     * literal would restate the derivation under test.</p>
     */
    private static final OpaqueIdentifier TOKENISER = new OpaqueIdentifier(fixedKeyMaterial());

    /**
     * Builds key material of the minimum admissible length, deterministically.
     *
     * @return the key material, never {@code null}
     */
    private static byte[] fixedKeyMaterial() {
        byte[] material = new byte[OpaqueIdentifier.MIN_KEY_LENGTH];
        for (int index = 0; index < material.length; index++) {
            material[index] = (byte) (index + 1);
        }
        return material;
    }

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
     * <p>Assumptions: the refusal surfaces as a {@code ConstraintViolationException}, and that type is
     * named here in prose because it is asserted inside a lambda where the documentation gate cannot see
     * it. It is the specification's own validation failure, so a caller already handling refusals from the
     * web layer handles this one identically.</p>
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
     * <p>Assumptions: the refusal surfaces as a {@code ConstraintViolationException} carrying all three
     * violations, and the type is named here in prose because it is asserted inside a lambda the
     * documentation gate cannot read into.</p>
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
     * <p>Assumptions: each of the four crossings raises a {@code NullPointerException}, and the type is
     * named here in prose because it is asserted inside a lambda the documentation gate cannot read into.
     * All four are exercised in one case because a guard added to three of them would leave the fourth
     * returning a record whose components were all absent, which reads as a valid empty message rather
     * than as a fault.</p>
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
     * A short numeric-display value is filled with leading zeros to its declared width on encode.
     *
     * <p>Assumptions: the assertion is made against the ENCODED bytes and not only against the wire
     * record's components, because the defect this closes was invisible in the record and visible only
     * once the encoder had padded the short value on the right with blanks. The committed oracle is the
     * one-hundred-and-seventy byte image the encoder must produce, so the assertion is byte-exact rather
     * than a restatement of the same logic under test.</p>
     *
     * <p>Assumptions: both numerically-pictured components are supplied short in the same payload -- one
     * digit for a six-digit picture and one for a two-digit picture -- so a fix applied to only one of
     * them fails here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws java.io.IOException if the committed oracle cannot be read, which a rename would cause and
     *     which must surface as a failure rather than as a silently skipped assertion
     */
    @Test
    @DisplayName("a short numeric-display value is left-zero filled to its declared width on encode")
    void aShortNumericDisplayValueIsFilledOnEncode() throws java.io.IOException {
        AuthorizationRequestPayload shortValues = new AuthorizationRequestPayload("240404", "091500",
                "4000123456789010", "PURC", "1227", "0100  ", "POS   ", "1",
                Money.of("250.00"), "5411", "840", "5", "MERCH0000000001",
                "ACME HARDWARE         ", "SEATTLE      ", "WA", "98101    ",
                "TXN000000000100");

        AuthRequest wire = mapper.toWireRecord(shortValues);

        assertEquals("000001", wire.processingCode());
        assertEquals("05", wire.posEntryMode());
        assertEquals(oracle("auth-request-short-numeric-display-oracle-170.bin"),
                CsvAuthCodec.encodeRequest(wire));
    }

    /**
     * A value already at its declared width crosses the fill unaltered.
     *
     * <p>Assumptions: this is asserted separately from the round-trip above because the fill is a
     * conditional and a fill applied unconditionally would double the width of a full value while every
     * short-value assertion still passed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a numeric-display value already at its declared width is not filled")
    void aFullWidthNumericDisplayValueIsNotFilled() {
        AuthRequest source = distinctRequest();

        AuthRequest wire = mapper.toWireRecord(mapper.toPayload(source));

        assertEquals("000001", wire.processingCode());
        assertEquals("05", wire.posEntryMode());
        assertEquals(source, wire);
    }

    /**
     * The projection refuses a reply that answers a different card or a different transaction.
     *
     * <p>Assumptions: the two identifiers are asserted separately, because a check written against only
     * one of them would leave the other pairing silently persistable, and the outcomes differ -- a wrong
     * card writes another cardholder's decision into this account's history, a wrong transaction answers
     * the wrong request for the same card.</p>
     *
     * <p>Assumptions: both refusals surface as an {@code IllegalArgumentException}, and the type is named
     * here in prose because it is asserted inside a lambda the documentation gate cannot read into. It is
     * an argument fault rather than a validation failure because the mismatch is between two arguments the
     * caller supplied together, which no constraint on either one alone can express.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the detail projection refuses a reply that does not answer the request")
    void theDetailProjectionRefusesAMismatchedReply() {
        AuthRequest request = distinctRequest();
        PendingAuthDetailKey key = new PendingAuthDetailKey(11111111111L, 25213, 104530000);
        AuthReply answersAnotherCard = new AuthReply("4111111111111112", request.transactionId(),
                "104530", "00", "0000", Money.of("100.99"));
        AuthReply answersAnotherTransaction = new AuthReply(request.cardNum(), "TXN000000000002",
                "104530", "00", "0000", Money.of("100.99"));

        assertThrows(IllegalArgumentException.class, () -> mapper.toPendingAuthDetail(key, request,
                answersAnotherCard, PendingAuthDetail.MATCH_STATUS_PENDING));
        assertThrows(IllegalArgumentException.class, () -> mapper.toPendingAuthDetail(key, request,
                answersAnotherTransaction, PendingAuthDetail.MATCH_STATUS_PENDING));
    }

    /**
     * The projection accepts the reply built from the request it is paired with.
     *
     * <p>Assumptions: this is asserted alongside the refusals so the identity check cannot pass by
     * refusing everything, and the four decided values are asserted to have come from the REPLY rather
     * than from the request, which is the property the check exists to make safe.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the detail projection takes the four decided values from the reply")
    void theDetailProjectionTakesTheDecidedValuesFromTheReply() {
        AuthRequest request = distinctRequest();
        PendingAuthDetailKey key = new PendingAuthDetailKey(11111111111L, 25213, 104530000);
        AuthReply decision = new AuthReply(request.cardNum(), request.transactionId(), "104531",
                "05", "4100", Money.ZERO);

        PendingAuthDetail row = mapper.toPendingAuthDetail(key, request, decision,
                PendingAuthDetail.MATCH_STATUS_DECLINED);

        assertEquals("104531", row.getAuthIdCode());
        assertEquals("05", row.getAuthRespCode());
        assertEquals("4100", row.getAuthRespReason());
        assertEquals(Money.ZERO.amount(), row.getApprovedAmount());
        assertEquals(request.transactionAmount().amount(), row.getTransactionAmount());
    }

    /**
     * The routing carrier refuses each of the three faults its contract names.
     *
     * <p>Assumptions: the deadline is asserted required HERE even though the publication type admits an
     * absent one, because the two rules are deliberately different and a test asserting only the
     * publication's rule would let this one be relaxed to match it.</p>
     *
     * <p>Assumptions: the four refusals raise TWO different types, and both are named here in prose
     * because each is asserted inside a lambda the documentation gate cannot read into. An absent
     * destination and an absent deadline raise a {@code NullPointerException}, because the argument was
     * never supplied; a blank destination and an over-wide identity raise an
     * {@code IllegalArgumentException}, because a value was supplied and is unusable. Collapsing the two
     * onto one type would tell a caller that a missing argument and a malformed one are the same
     * mistake.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply routing refuses an absent destination, an oversized identity and no deadline")
    void theReplyRoutingRefusesItsThreeFaults() {
        LocalDateTime deadline = LocalDateTime.of(2026, 8, 8, 9, 14, 27);
        String tooWide = "c".repeat(OutboxMessage.CORRELATION_ID_MAX_LENGTH + 1);

        assertThrows(NullPointerException.class,
                () -> new AuthorizationMessageMapper.ReplyRouting(null, "corr", deadline));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationMessageMapper.ReplyRouting("   ", "corr", deadline));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL, tooWide,
                        deadline));
        assertThrows(NullPointerException.class,
                () -> new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL, "corr", null));

        AuthorizationMessageMapper.ReplyRouting accepted =
                new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL, null, deadline);

        assertEquals(deadline, accepted.expiresAt());
    }

    /**
     * The publication carries the routing's three values and the deadline the descriptor sets.
     *
     * <p>Assumptions: the deadline is asserted as five seconds past the instant supplied, which is the
     * fifty tenths of a second the reference descriptor sets at {@code cbl/COPAUA0C.cbl} L750 read
     * through the mapper's own conversion rather than restated here as a literal five.</p>
     *
     * <p>Assumptions: the two first-in-first-out identities are asserted as the LITERAL card number and
     * the LITERAL transaction identifier, which is what the specification freezes at &sect;0.4.1.8 --
     * {@code MessageGroupId = card_num} and {@code MessageDeduplicationId = transaction_id}. They are
     * compared against the reply's own accessors rather than against restated string literals, so a
     * fixture edit cannot make the assertion pass against a different card.</p>
     *
     * <p>Refactoring Rationale: this case previously asserted the identities equalled a keyed token of
     * the card and of the card-plus-transaction pair. That made the identity a value only this producer
     * could compute, and the identity is not a local concern: grouping orders one card's messages ACROSS
     * every producer on the queue, and duplicate suppression compares a value the requester may resend.
     * A keyed derivation silently stopped both working for any other producer, so the assertion has been
     * moved onto the frozen values.</p>
     *
     * <p>Assumptions: the format label is asserted as the delimited-string content type, because that is
     * what the reference descriptor declares on both directions -- {@code MQFMT-STRING} is set on the
     * reply at {@code cbl/COPAUA0C.cbl} L751 and on the get at L397. It is asserted from
     * {@code AuthReplyOutbox}'s own constant rather than from a literal, so the message and the row that
     * stores it are held to one value; a projection that labelled a delimited payload as a document would
     * tell a consumer to parse it with the wrong reader.</p>
     *
     * <p>Assumptions: a correlation identity at the reference field's full declared width is asserted to
     * cross UNCHANGED, neither truncated nor regenerated. The reference program does not mint one -- it
     * captures the requester's and puts it back -- so a projection that generated its own would answer a
     * requester with an identity it never sent and could not match. The width is
     * {@link #BASELINE_CORRELATION_ID_WIDTH}, and the refusal of an identity wider than the storing
     * column is a separate rule asserted by the routing case below.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the outbox projection carries the routing, the encoded reply and the deadline")
    void theOutboxProjectionCarriesTheRoutingAndTheDeadline() {
        LocalDateTime sentAt = LocalDateTime.of(2026, 8, 8, 9, 14, 27);
        String baselineWidthCorrelationId = "c".repeat(BASELINE_CORRELATION_ID_WIDTH);
        AuthReply reply = new AuthReply("4111111111111111", "TXN000000000001", "104530", "00",
                "0000", Money.of("100.99"));
        AuthorizationMessageMapper.ReplyRouting routing =
                new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL, "corr-1",
                        AuthorizationMessageMapper.replyExpiresAt(sentAt));

        OutboxMessage publication = mapper.toOutboxMessage(reply, routing);

        assertEquals(REPLY_QUEUE_URL, publication.replyQueueUrl());
        assertEquals("corr-1", publication.correlationId());
        assertEquals(CsvAuthCodec.encodeReply(reply), publication.payload());
        assertEquals(sentAt.plusSeconds(5), publication.expiresAt());
        assertEquals(reply.cardNum(), publication.orderGroupId());
        assertEquals(reply.transactionId(), publication.deduplicationId());
        assertEquals(AuthReplyOutbox.CONTENT_TYPE_CSV, publication.contentType());
        assertEquals(baselineWidthCorrelationId, mapper
                .toOutboxMessage(reply, new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL,
                        baselineWidthCorrelationId, routing.expiresAt()))
                .correlationId());
    }

    /**
     * A reply publication cannot be made durable without the deadline that stops a stale send.
     *
     * <p>Assumptions: the canonical constructor is asserted to STILL accept an absent deadline in the
     * same test, because the two rules are one decision and asserting only the refusal would let a
     * later edit tighten the constructor and break the projection of a row whose column is null.</p>
     *
     * <p>Assumptions: the refusal surfaces as a {@code NullPointerException}, and the type is named here
     * in prose because it is asserted inside a lambda the documentation gate cannot read into.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reply publication requires a deadline while a projected row may carry none")
    void aReplyPublicationRequiresADeadline() {
        assertThrows(NullPointerException.class, () -> OutboxMessage.csvReply(REPLY_QUEUE_URL,
                "corr-1", "group", "dedup", "payload", null));

        OutboxMessage loaded = new OutboxMessage(REPLY_QUEUE_URL, "corr-1", "group", "dedup",
                "payload", AuthReplyOutbox.CONTENT_TYPE_CSV, null);

        assertEquals(null, loaded.expiresAt());
    }

    /**
     * The mapper refuses to be built without the validation engine it documents as required.
     *
     * <p>Assumptions: the refusal surfaces as a {@code NullPointerException} at CONSTRUCTION, and the type
     * is named here in prose because it is asserted inside a lambda the documentation gate cannot read
     * into. Failing at construction rather than at the first crossing is the property under test: a mapper
     * built without an engine would otherwise accept every payload silently, so each of its declared
     * constraints would read as enforced while none was applied.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the mapper refuses to be built without a validation engine")
    void theMapperRefusesToBeBuiltWithoutAnEngine() {
        assertThrows(NullPointerException.class, () -> new AuthorizationMessageMapper(null));
    }

    /**
     * The diagnostic projection renders the ten disclosable copybook fields, in order and safely, omits
     * the eleventh, and reports the one severity that is terminal.
     *
     * <p>Assumptions: the eleven declared widths are restated here from
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} L20 to L40 and their sum is asserted
     * against the published record length, which is what holds that constant to the layout it claims to
     * describe. Asserting the constant against itself would assert nothing. The copybook declares
     * eleven fields and the RECORD still carries all eleven; it is the log PROJECTION that carries
     * ten, so the width sum and the projected key list are deliberately different counts.</p>
     *
     * <p>Assumptions: {@code eventKey} is asserted ABSENT from the no-argument projection rather than
     * present-and-sanitized. {@code docs/architecture/observability.md} L1075 to L1081 states the rule
     * this obeys -- a prohibited value is omitted and not abbreviated, and no account identifier or
     * customer identifier may appear in any rendering read by an operator -- and the reference program
     * puts the key of the item being processed into that twenty-character field, which for this consumer
     * is a primary account number, an account identifier or a customer identifier depending on which
     * step failed. Sanitizing prevents log FORGING and not DISCLOSURE, so a sanitized {@code eventKey}
     * in this projection would still have sent a protected identifier to centralized logging on the one
     * path guaranteed to run when something is already going wrong.</p>
     *
     * <p>Assumptions: the line-break defence is therefore asserted on the overload that DOES record the
     * key, which is the only path that carries it. That keeps both properties covered -- the key never
     * reaches a log undisguised, and the value it is derived from cannot forge a second log entry --
     * rather than trading one for the other.</p>
     *
     * <p>Assumptions: the same line break is also fed through {@code message}, so the sanitization of an
     * ordinary attacker-influenced component is still asserted on the no-argument projection itself and
     * does not rest on the tokenised overload alone.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the diagnostic projection renders ten ordered safe fields, omits the event key and flags the terminal level")
    void theDiagnosticProjectionRendersTenOrderedSafeFieldsAndOmitsTheEventKey() {
        List<Integer> declaredWidths = List.of(6, 6, 8, 8, 4, 1, 1, 9, 9, 50, 20);
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 8, 9, 14, 27);

        AuthorizationMessageMapper.ErrorLogEntry warning = AuthorizationMessageMapper.errorLogEntry(
                observedAt, "CP00", "COPAUA0C", "A001",
                AuthorizationMessageMapper.ERROR_LEVEL_WARNING,
                AuthorizationMessageMapper.ERROR_SUBSYSTEM_APPLICATION, null, null,
                "CARD NOT FOUND\nIN XREF", "forged\nentry");

        assertEquals(AuthorizationMessageMapper.ERROR_LOG_RECORD_LENGTH,
                declaredWidths.stream().mapToInt(Integer::intValue).sum());
        assertEquals("260808", warning.errDate());
        assertEquals("091427", warning.errTime());
        assertEquals(List.of("errDate", "errTime", "application", "program", "location", "level",
                        "subsystem", "codeOne", "codeTwo", "message"),
                List.copyOf(warning.structuredFields().keySet()));
        assertEquals("", warning.structuredFields().get("codeOne"));
        assertEquals(false, warning.structuredFields().containsKey("eventKey"));
        assertEquals(false, warning.structuredFields().get("message").contains("\n"));
        assertEquals(false, warning.isTerminal());

        Map<String, String> tokenised = warning.structuredFields(TOKENISER);
        assertEquals(false, tokenised.containsKey("eventKey"));
        assertEquals(true, tokenised.containsKey("eventKeyToken"));
        assertEquals(false, tokenised.get("eventKeyToken").contains("\n"));
        assertEquals(false, tokenised.get("eventKeyToken").contains("forged"));
        assertEquals(false, tokenised.get("eventKeyToken").contains("entry"));
        assertEquals(warning.structuredFields(TOKENISER).get("eventKeyToken"),
                tokenised.get("eventKeyToken"));
        assertEquals(false, warning.isTerminal());

        AuthorizationMessageMapper.ErrorLogEntry critical = AuthorizationMessageMapper.errorLogEntry(
                observedAt, "CP00", "COPAUA0C", "M001",
                AuthorizationMessageMapper.ERROR_LEVEL_CRITICAL, "M", "00002059", "00002085",
                "REQ MQ OPEN ERROR", "");

        assertEquals(true, critical.isTerminal());
    }

    /**
     * The crossing routes every wire form through the shared codec and holds no parser of its own.
     *
     * <p>Assumptions: this group is the one that makes the rest of the class meaningful. The codec's own
     * suite proves the wire contract in isolation; what it cannot see is whether this mapper reached that
     * contract by calling the codec or by building an equivalent string of its own that will drift the
     * first time a width moves. Every case here is therefore about the ROUTE and not about the bytes.</p>
     *
     * <p>Assumptions: the structural case below is written reflectively over the two crossing methods
     * rather than as a behavioural one, because a mapper that parsed wire text itself would still produce
     * the right answer today. What makes it wrong is that the entry point EXISTS, so the assertion has to
     * be about the signature. The projection and detail methods are deliberately outside its subject
     * set -- one legitimately accepts a match-status character and the other a queue address, and
     * widening the sweep to every method would refuse both for carrying a string that is not wire
     * text.</p>
     */
    @Nested
    @DisplayName("delegation to the shared authorization codec")
    class CodecDelegation {

        /**
         * The published reply payload is the codec's own encoding and reads back through the codec.
         *
         * <p>Assumptions: the assertion compares against
         * {@code CsvAuthCodec.encodeReply} and against
         * {@link CsvAuthCodec#REPLY_WIRE_LENGTH} rather than against a sixty-three character literal, so
         * this case states that the projection publishes at the codec's DECLARED length and takes no
         * position of its own on what that length is. The proof that the length is sixty-three rather
         * than sixty-two, and that a sixty-fourth byte is tolerated inbound, belongs to the codec's own
         * suite and to {@code fixtures.AuthorizationWireFrameFixtureTest}; re-proving either here would
         * leave two suites asserting one contract.</p>
         *
         * <p>Assumptions: the decode step is included because equality against the encoder alone would
         * pass for a projection that published a value the decoder could not read -- the two directions
         * are separate entry points and a mapper can reach one without the other.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the published reply body is the codec's encoding at the codec's declared length")
        void thePublishedReplyBodyIsTheCodecsOwnEncoding() {
            AuthReply reply = new AuthReply("4000123456789010", "TXN000000000100", "A00001", "00",
                    "0000", Money.of("250.00"));
            AuthorizationMessageMapper.ReplyRouting routing =
                    new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL, "corr-1",
                            LocalDateTime.of(2026, 8, 8, 9, 14, 27));

            OutboxMessage publication = mapper.toOutboxMessage(reply, routing);

            assertEquals(CsvAuthCodec.encodeReply(reply), publication.payload());
            assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, publication.payload().length());
            assertEquals(reply, CsvAuthCodec.decodeReply(publication.payload()));
        }

        /**
         * Neither crossing method accepts or answers wire text, so no parser can live behind one.
         *
         * <p>Assumptions: the four admissible types are the two codec carriers and the two payload
         * records. A crossing that accepted a character sequence or a byte array would be a second
         * decoder, and a crossing that ANSWERED one would be a second encoder; both are refused by the
         * same sweep because both are the same mistake seen from opposite ends.</p>
         *
         * <p>Assumptions: the sweep asserts a non-empty subject set before asserting anything about it.
         * A filter that matched nothing would pass every remaining assertion vacuously, which is the one
         * failure mode a reflective test cannot detect from its own result.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("no crossing method accepts or returns wire text")
        void noCrossingMethodAcceptsOrReturnsWireText() {
            List<Class<?>> admissible = List.of(AuthRequest.class, AuthReply.class,
                    AuthorizationRequestPayload.class, AuthorizationReplyPayload.class);
            List<String> offending = new ArrayList<>();
            int crossings = 0;

            for (Method candidate : AuthorizationMessageMapper.class.getDeclaredMethods()) {
                if (!"toPayload".equals(candidate.getName())
                        && !"toWireRecord".equals(candidate.getName())) {
                    continue;
                }
                crossings++;
                if (candidate.getParameterCount() != 1
                        || !admissible.contains(candidate.getParameterTypes()[0])
                        || !admissible.contains(candidate.getReturnType())) {
                    offending.add(candidate.getName() + " "
                            + List.of(candidate.getParameterTypes()) + " -> "
                            + candidate.getReturnType());
                }
            }

            assertEquals(4, crossings);
            assertEquals(List.of(), offending);
        }

        /**
         * A reply payload outside either decided domain is refused before any wire record exists.
         *
         * <p>Assumptions: the failure surfaces as a {@code ConstraintViolationException} carrying one
         * violation per fault, and the two faults chosen are the response CODE and the response REASON
         * because those are the two decided fields and they are separately constrained. The membership
         * of each domain is asserted once, by the transfer-type tests in {@code .dto}; what this case
         * asserts is that the crossing APPLIES them, so an out-of-domain decision cannot reach the queue
         * by way of this mapper.</p>
         *
         * <p>Assumptions: the catch-all reason is asserted to CROSS in the same case, because the
         * reference program's evaluation ends in a {@code WHEN OTHER} at
         * {@code cbl/COPAUA0C.cbl} L715 to L716 that emits it for any condition outside the six it
         * enumerates. A crossing that narrowed the domain to the enumerated reasons would refuse a
         * decision the reference program legitimately produces, so accepting it is a parity requirement
         * rather than laxity.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an out-of-domain reply decision is refused while the catch-all reason crosses")
        void anOutOfDomainReplyDecisionIsRefusedWhileTheCatchAllCrosses() {
            AuthorizationReplyPayload outOfDomain = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "XX", "9999", Money.ZERO);
            AuthorizationReplyPayload catchAll = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "05", "9000", Money.ZERO);

            ConstraintViolationException refusal = assertThrows(ConstraintViolationException.class,
                    () -> mapper.toWireRecord(outOfDomain));

            assertEquals(2, refusal.getConstraintViolations().size());
            assertEquals("9000", mapper.toWireRecord(catchAll).authRespReason());
        }
    }

    /**
     * One logical amount, two textual forms, and the crossing that must not confuse them.
     *
     * <p>Refactoring Rationale: this group asserts divergence D-I, which
     * {@code com/carddemo/authorization/package-info.java} registers as one logical amount having two
     * textual forms. The baseline holds them in two different items and never reconciles them: the reply
     * amount reaching the wire is moved at {@code cbl/COPAUA0C.cbl} L720 into
     * {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} declared at L66, whose {@code z} positions SUPPRESS
     * leading zeros to blanks and whose sign position is blank for a non-negative value; the persisted
     * amount comes from a different move at L900 and is zero-FILLED. Nothing in the baseline states that
     * these are one quantity, so a reader has no reason to expect the two renderings to differ and every
     * reason to reuse whichever formatter is nearest. The target keeps both forms and keeps them
     * distinct: the wire carries the suppressed rendering the reference emits, so a reference consumer
     * still reads it, while the exact decimal behind both is one value at one scale. What was wrong with
     * the old approach is precisely that it had no single value -- an amount existed only as whichever
     * edited picture last touched it -- which is why a crossing test rather than a formatter test is the
     * one that can catch a regression here.</p>
     *
     * <p>Assumptions: the hazard is specific to a MAPPER and to no other layer, which is why it is
     * asserted here. This class holds a request payload and a reply payload whose amounts are the same
     * Java type, and the codec exposes two renderers of the same width for them; the codec's own source
     * flags the confusion at its request encoder, noting that the request mask fixes a sign character and
     * zero-pads while the reply mask blanks the sign and suppresses. A crossing that called the wrong one
     * would still produce fourteen characters and would still round-trip through its own inverse, so
     * neither a width assertion nor a round trip detects it. Only comparing the two renderings of ONE
     * amount does.</p>
     *
     * <p>Assumptions: the two renderers themselves, their sign and suppression rules and their refusals
     * are asserted once in the codec's own suite. What is asserted here is which renderer each side of
     * the crossing reaches, and that the value survives both.</p>
     */
    @Nested
    @DisplayName("divergence D-I: the two textual forms of one amount")
    class AmountTextualForms {

        /**
         * The same amount crosses to a blank-signed suppressed reply and a plus-signed padded request.
         *
         * <p>Assumptions: two hundred and fifty is the amount used because it has leading positions to
         * resolve. The greatest representable amount fills every position with a nine, so on that value
         * a suppressed and a padded rendering are indistinguishable and the case would prove nothing;
         * zero would leave only the forced units digit, which is the one position suppression cannot
         * reach. The eight-blank prefix asserted below is the committed reply fixture's own content, so
         * the expectation is the recorded wire rather than a restatement of the formatter.</p>
         *
         * <p>Assumptions: both renderings are {@link CsvAuthCodec#MONEY_EDITED_WIDTH} characters and the
         * width is asserted on both, because the format is positional -- a thirteen or fifteen character
         * money field would move the delimiter that follows it and change the payload length. The width
         * is taken from the published constant so this case states the equality of the two widths and not
         * the number.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("one amount renders blank-signed and suppressed on the reply, plus-signed and padded on the request")
        void oneAmountRendersSuppressedOnTheReplyAndPaddedOnTheRequest() {
            Money amount = Money.of("250.00");
            AuthorizationReplyPayload replyPayload = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "00", "0000", amount);
            AuthorizationRequestPayload requestPayload = requestPayloadWithAmount(amount);

            String replyText = CsvAuthCodec.encodeReply(mapper.toWireRecord(replyPayload));
            String requestText = CsvAuthCodec.encodeRequest(mapper.toWireRecord(requestPayload));

            assertEquals("        250.00", amountTokenOf(replyText, CsvAuthCodec.REPLY_AMOUNT_ORDINAL));
            assertEquals("+0000000250.00",
                    amountTokenOf(requestText, CsvAuthCodec.REQUEST_AMOUNT_ORDINAL));
            assertEquals(CsvAuthCodec.MONEY_EDITED_WIDTH,
                    amountTokenOf(replyText, CsvAuthCodec.REPLY_AMOUNT_ORDINAL).length());
            assertEquals(CsvAuthCodec.MONEY_EDITED_WIDTH,
                    amountTokenOf(requestText, CsvAuthCodec.REQUEST_AMOUNT_ORDINAL).length());
            assertNotEquals(amountTokenOf(replyText, CsvAuthCodec.REPLY_AMOUNT_ORDINAL),
                    amountTokenOf(requestText, CsvAuthCodec.REQUEST_AMOUNT_ORDINAL));
        }

        /**
         * Both textual forms decode back through the crossing to one exact decimal.
         *
         * <p>Assumptions: the sign is read from the LEADING position in both forms, so a negative amount
         * is carried through the request's explicit minus and a non-negative one through a blank the
         * reply leaves where its sign would be. Asserting the two decoded values are equal to each other
         * and to the amount they were built from is what establishes that the crossing normalises to one
         * quantity rather than to one rendering.</p>
         *
         * <p>Assumptions: the comparison is on the exact decimal at its own scale rather than on the
         * rendered text, because two renderings that differ by design cannot be compared as strings and
         * comparing them would assert the opposite of D-I.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both textual forms normalise to one exact decimal across the crossing")
        void bothTextualFormsNormaliseToOneExactDecimal() {
            Money amount = Money.of("250.00");
            AuthorizationReplyPayload replyPayload = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "00", "0000", amount);

            AuthReply reEncoded = CsvAuthCodec.decodeReply(
                    CsvAuthCodec.encodeReply(mapper.toWireRecord(replyPayload)));
            AuthRequest requestRoundTrip = CsvAuthCodec.decodeRequest(
                    CsvAuthCodec.encodeRequest(mapper.toWireRecord(requestPayloadWithAmount(amount))));

            assertEquals(amount, mapper.toPayload(reEncoded).approvedAmount());
            assertEquals(amount, mapper.toPayload(requestRoundTrip).transactionAmount());
            assertEquals(mapper.toPayload(reEncoded).approvedAmount().amount(),
                    mapper.toPayload(requestRoundTrip).transactionAmount().amount());
        }

        /**
         * A negative amount carries its sign in the leading position of the reply's own mask.
         *
         * <p>Assumptions: the sign occupies index zero of the token in BOTH textual forms, because a
         * leading sign character in either picture is a fixed position rather than a floating one -- the
         * reply mask leaves it blank for a non-negative value where the request mask forces a plus, and
         * neither ever moves it next to the first significant digit. That fixity is why a positional
         * assertion at index zero is meaningful; a floating sign would make it meaningless.</p>
         *
         * <p>Assumptions: the reply record admits a negative approved amount because the reference mask
         * declares a sign-control position for one, while the request PAYLOAD refuses a negative
         * transaction amount on domain grounds. Those are two different rules and the second is asserted
         * elsewhere, so this case exercises the reply side only.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a negative reply amount uses the mask's leading sign position")
        void aNegativeReplyAmountUsesTheLeadingSignPosition() {
            AuthorizationReplyPayload negative = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "05", "4100", Money.of("-250.00"));
            AuthorizationReplyPayload positive = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "00", "0000", Money.of("250.00"));

            String negativeText = CsvAuthCodec.encodeReply(mapper.toWireRecord(negative));
            String token = amountTokenOf(negativeText, CsvAuthCodec.REPLY_AMOUNT_ORDINAL);

            assertEquals('-', token.charAt(0));
            assertEquals(' ', amountTokenOf(CsvAuthCodec.encodeReply(mapper.toWireRecord(positive)),
                    CsvAuthCodec.REPLY_AMOUNT_ORDINAL).charAt(0));
            assertEquals(CsvAuthCodec.MONEY_EDITED_WIDTH, token.length());
            assertEquals(Money.of("-250.00"),
                    mapper.toPayload(CsvAuthCodec.decodeReply(negativeText)).approvedAmount());
        }
    }

    /**
     * The declared-width amount token survives the crossing whole, hundredths digit included.
     *
     * <p>Refactoring Rationale: this group asserts divergence D-H, which
     * {@code com/carddemo/authorization/package-info.java} registers as a fourteen-character money layout
     * parsed through a thirteen-character receiver, and which
     * {@code docs/architecture/cobol-to-service-traceability.md} registers as
     * {@code D-AUTH-AMOUNT-TOLERANT-READ}. What was wrong with the baseline approach is not a missing
     * check but a silent one: {@code cpy/CCPAURQY.cpy} L27 declares
     * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99}, fourteen characters, while its only consumer receives
     * ordinal nine of the {@code UNSTRING} at {@code cbl/COPAUA0C.cbl} L364 into
     * {@code WS-TRANSACTION-AMT-AN PIC X(13)} declared at L63. An alphanumeric move into a shorter item
     * drops the LAST character, and the conversion at L376 to L377 accepts one fraction digit as readily
     * as two, so a wire value of {@code +0000000100.99} is acted upon as one hundred point nine zero with
     * nothing raised anywhere. The target reads and emits the copybook's fourteen, so no digit is lost,
     * and a token narrower than the declared width is refused by name rather than reinterpreted.</p>
     *
     * <p>Assumptions: the codec-level proof of D-H belongs to {@code AuthRequestWireFixtureTest} in this
     * package, which reads both committed request images and asserts the receiver-width payload, the
     * refusal of a token that lost its final cents digit, and the re-emission at the declared width. What
     * is asserted HERE is the crossing that class cannot see -- that the value reaches the payload record
     * and returns from it with the hundredths digit intact -- so the two are complementary rather than
     * duplicates.</p>
     *
     * <p>Assumptions: correction C5 in the parent charter decides which vector this group must use, and
     * it is not the obvious one. A conforming token is always fourteen characters, so a move into a
     * thirteen-character receiver ALWAYS discards the fourteenth; the value is corrupted only when that
     * discarded character is non-zero. Every amount in this module's fixtures other than the all-nines
     * variant has a zero in the hundredths position, so written against any of them the case passes while
     * proving nothing -- which is worse than no case at all, because it reads as coverage.</p>
     *
     * <p>Assumptions: the fixture is {@code auth-request-amount-variants.csv}, 513 bytes as three records
     * of 171 -- {@link CsvAuthCodec#REQUEST_WIRE_LENGTH} payload characters plus one line-feed terminator
     * each. The three amount vectors, in file order, are {@code -0000000250.00},
     * {@code +9999999999.99} and {@code +0000000000.00}, and their transaction identifiers end 200, 201
     * and 202 so a record read out of order is visible rather than plausible. The stride is uniform, so
     * record N begins at 171 times N; the terminator is stripped explicitly here rather than left to a
     * reader to remove, because the assertion is on the payload and a terminator inside it would move
     * every subsequent position by one.</p>
     */
    @Nested
    @DisplayName("divergence D-H: the declared-width amount token crosses whole")
    class AmountWidthAtTheCrossing {

        /**
         * The all-nines vector keeps its hundredths digit across the payload crossing.
         *
         * <p>Assumptions: the expectation is the exact decimal nine thousand nine hundred and ninety-nine
         * million and so on to two places, which is also {@link Money#MAX_MAGNITUDE}, and the value that
         * the baseline receiver would have reported as ending in a single nine. Comparing against
         * {@code MAX_MAGNITUDE} rather than a literal ties the case to the declared
         * {@code PIC S9(10)V99} domain instead of restating it.</p>
         *
         * <p>Assumptions: the re-encode step is included because a crossing can preserve a value in the
         * record and still narrow it on the way back out. Asserting the re-emitted token is the fourteen
         * characters the fixture carries closes that half.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         *
         * @throws java.io.IOException if the committed fixture cannot be read, which a rename would cause
         *     and which must surface as a failure rather than as a silently skipped assertion
         */
        @Test
        @DisplayName("the all-nines amount keeps its hundredths digit through the payload crossing")
        void theAllNinesAmountKeepsItsHundredthsDigit() throws java.io.IOException {
            String payload = amountVariantRecord(1);

            AuthorizationRequestPayload crossed = mapper.toPayload(CsvAuthCodec.decodeRequest(payload));
            String reEmitted = CsvAuthCodec.encodeRequest(mapper.toWireRecord(crossed));

            assertEquals(Money.of(Money.MAX_MAGNITUDE), crossed.transactionAmount());
            assertEquals("+9999999999.99",
                    amountTokenOf(payload, CsvAuthCodec.REQUEST_AMOUNT_ORDINAL));
            assertEquals("+9999999999.99",
                    amountTokenOf(reEmitted, CsvAuthCodec.REQUEST_AMOUNT_ORDINAL));
            assertEquals(payload, reEmitted);
        }

        /**
         * The canonical-zero vector crosses unchanged, so the case above is not passing on magnitude.
         *
         * <p>Assumptions: the zero vector is asserted alongside the all-nines one because a crossing that
         * treated a fully-zero amount as absent, or that reached for a default, would leave the all-nines
         * case green. Zero is the value with the fewest significant digits the record admits, so it is the
         * one where an absent-versus-zero confusion is possible at all.</p>
         *
         * <p>Assumptions: the fixture's NEGATIVE vector is deliberately not crossed here, and the omission
         * is stated so it is not read as a gap. Its wire picture carries an explicit sign, so the codec
         * decodes it faithfully and must -- but the request payload's amount domain admits only zero
         * through the greatest representable magnitude, so crossing it raises rather than returning a
         * value. That refusal is a different registered divergence and is already asserted in this class
         * by the negative-amount case above; repeating it from the fixture would assert the same rule
         * twice, and the fixture's negative vector exists for the codec's own decode table, which is the
         * shared kernel's subject. What IS asserted here is the narrow half that case cannot cover -- that
         * the committed fixture's own bytes decode to the negative value and are then refused -- and the
         * refusal surfaces as a {@code ConstraintViolationException}, named in prose because it is
         * asserted inside a lambda the documentation gate cannot read into.</p>
         *
         * <p>Assumptions: the record is located by its own stride rather than by scanning for a value, and
         * the transaction identifier is asserted alongside the amount so that a record read at the wrong
         * offset fails on identity instead of silently comparing the wrong amount.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         *
         * @throws java.io.IOException if the committed fixture cannot be read, which a rename would cause
         *     and which must surface as a failure rather than as a silently skipped assertion
         */
        @Test
        @DisplayName("the canonical-zero vector also crosses unchanged, and the negative one is refused")
        void theCanonicalZeroVectorAlsoCrossesUnchanged() throws java.io.IOException {
            String zero = amountVariantRecord(2);
            AuthRequest negative = CsvAuthCodec.decodeRequest(amountVariantRecord(0));

            AuthorizationRequestPayload crossedZero = mapper.toPayload(CsvAuthCodec.decodeRequest(zero));

            assertEquals(Money.ZERO, crossedZero.transactionAmount());
            assertEquals("TXN000000000202", crossedZero.transactionId());
            assertEquals(zero, CsvAuthCodec.encodeRequest(mapper.toWireRecord(crossedZero)));
            assertEquals(Money.of("-250.00"), negative.transactionAmount());
            assertThrows(ConstraintViolationException.class, () -> mapper.toPayload(negative));
        }
    }

    /**
     * The keyed identity that pairs one reply with the request it answers.
     *
     * <p>Assumptions: these two entry points had no caller anywhere in this module's test tree before
     * this group, which is the reason it exists. The package charter permits a class to cover the members
     * of its subject that the contract-shaped classes leave unreached, and requires that it name the
     * contract no sibling holds; the contract here is that the two overloads AGREE, because that equality
     * is the entire mechanism by which a drained reply is matched to the request that produced it.</p>
     *
     * <p>Assumptions: the identity being tokenised is the thirty-one character business correlation key,
     * the sixteen-character card number followed by the fifteen-character transaction identifier. Its
     * composition, its width and the opacity, stability and purpose separation of the token derived from
     * it are asserted once in the codec's own suite. What is asserted here is that this mapper ROUTES to
     * that derivation rather than deriving an identity of its own, which is the only failure a caller of
     * these two methods could suffer.</p>
     *
     * <p>Assumptions: the key material is built in this source at
     * {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes, deterministically, so every expectation is
     * reproducible from this file alone and no secret store is consulted. The tokeniser is shared with
     * the diagnostic cases below for the same reason.</p>
     */
    @Nested
    @DisplayName("the keyed business correlation identity")
    class CorrelationIdentity {

        /**
         * A request and the reply that answers it derive one identical token.
         *
         * <p>Assumptions: the two tokens are compared to EACH OTHER rather than to a recorded literal,
         * because a literal would freeze a keyed derivation whose output is deliberately opaque and would
         * have to be rewritten the moment the key material or the purpose changed. Equality between the
         * two overloads is the property the exchange depends on and it survives both of those
         * changes.</p>
         *
         * <p>Assumptions: a reply naming a DIFFERENT transaction is asserted to derive a different token
         * in the same case, so the equality above cannot be passing because the derivation ignores its
         * input.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a request and its reply derive one token, and a different exchange derives another")
        void aRequestAndItsReplyDeriveOneToken() {
            AuthRequest request = distinctRequest();
            AuthReply answer = new AuthReply(request.cardNum(), request.transactionId(), "A00001",
                    "00", "0000", request.transactionAmount());
            AuthReply otherExchange = new AuthReply(request.cardNum(), "TXN000000000999", "A00001",
                    "00", "0000", request.transactionAmount());

            String fromRequest =
                    AuthorizationMessageMapper.businessCorrelationToken(request, TOKENISER);
            String fromReply = AuthorizationMessageMapper.businessCorrelationToken(answer, TOKENISER);

            assertEquals(fromRequest, fromReply);
            assertNotEquals(fromRequest,
                    AuthorizationMessageMapper.businessCorrelationToken(otherExchange, TOKENISER));
        }

        /**
         * Each overload answers exactly what the carrier it was handed derives for itself.
         *
         * <p>Assumptions: this is the delegation half. Comparing each overload's answer against the
         * carrier record's own derivation under the same tokeniser is what establishes that the mapper
         * added no second derivation of its own; a mapper that hashed the pair itself would satisfy the
         * equality case above while producing a token no other participant in the exchange could
         * reproduce.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("each overload answers the carrier's own derivation")
        void eachOverloadAnswersTheCarriersOwnDerivation() {
            AuthRequest request = distinctRequest();
            AuthReply answer = new AuthReply(request.cardNum(), request.transactionId(), "A00001",
                    "00", "0000", request.transactionAmount());

            assertEquals(request.correlationKey(TOKENISER),
                    AuthorizationMessageMapper.businessCorrelationToken(request, TOKENISER));
            assertEquals(answer.correlationKey(TOKENISER),
                    AuthorizationMessageMapper.businessCorrelationToken(answer, TOKENISER));
        }

        /**
         * The token discloses neither of the two values it is derived from.
         *
         * <p>Assumptions: a correlation identity is written to message metadata, to queue telemetry and
         * to log lines, none of which sits behind the boundary that masks a primary account number, so a
         * token that contained either component would publish a card number to all three. The assertion
         * is negative and is stated on both components, because the transaction identifier is not
         * sensitive on its own but its presence would prove the token was a concatenation rather than a
         * derivation.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the token contains neither the card number nor the transaction identifier")
        void theTokenContainsNeitherSourceComponent() {
            AuthRequest request = distinctRequest();

            String token = AuthorizationMessageMapper.businessCorrelationToken(request, TOKENISER);

            assertEquals(false, token.contains(request.cardNum()));
            assertEquals(false, token.contains(request.transactionId()));
            assertEquals(OpaqueIdentifier.TOKEN_LENGTH, token.length());
        }

        /**
         * Both overloads refuse a null carrier and a null tokeniser rather than answering.
         *
         * <p>Assumptions: the failure is a {@code NullPointerException} in all four positions. A
         * correlation value that silently became empty would pair every unkeyed exchange with every
         * other, which is a defect that presents as mismatched replies rather than as a failure, so
         * refusing is the only safe answer.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both overloads refuse a null carrier and a null tokeniser")
        void bothOverloadsRefuseNullArguments() {
            AuthRequest request = distinctRequest();
            AuthReply answer = new AuthReply(request.cardNum(), request.transactionId(), "A00001",
                    "00", "0000", request.transactionAmount());

            assertThrows(NullPointerException.class, () -> AuthorizationMessageMapper
                    .businessCorrelationToken((AuthRequest) null, TOKENISER));
            assertThrows(NullPointerException.class, () -> AuthorizationMessageMapper
                    .businessCorrelationToken((AuthReply) null, TOKENISER));
            assertThrows(NullPointerException.class,
                    () -> AuthorizationMessageMapper.businessCorrelationToken(request, null));
            assertThrows(NullPointerException.class,
                    () -> AuthorizationMessageMapper.businessCorrelationToken(answer, null));
        }
    }

    /**
     * The reply deadline, and the unit its published interval is denominated in.
     *
     * <p>Assumptions: the descriptor field this derives from is denominated in TENTHS of a second.
     * {@code cbl/COPAUA0C.cbl} L750 sets an expiry of 50, which is five seconds and not fifty; reading
     * the unit as seconds yields a window ten times too long, during which a requester would act on a
     * decision it had already stopped waiting for. That is the specific misreading this group exists to
     * catch, and nothing else in this module's test tree reached either published constant before it.</p>
     *
     * <p>Assumptions: the target transport offers no per-message time to live, so the interval cannot be
     * expressed as a queue setting and travels as a message attribute instead -- a sender declines a
     * message whose instant has passed and a consumer drops and logs a stale one. Short retention on the
     * reply queue is a backstop rather than the mechanism, which is why the deadline is asserted on the
     * message and not on any queue property.</p>
     */
    @Nested
    @DisplayName("the reply deadline and its tenths-of-a-second unit")
    class ReplyDeadline {

        /**
         * The published interval is fifty tenths of a second, which is five seconds and not fifty.
         *
         * <p>Assumptions: the horizon is asserted to EQUAL five seconds and to DIFFER from fifty in the
         * same case. Asserting the equality alone would pass for a constant that happened to be right
         * while its name claimed the wrong unit; asserting the inequality alongside it is what pins the
         * unit rather than the number, and fifty seconds is the exact value a reader misreading the
         * descriptor field would arrive at.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("fifty tenths of a second is five seconds, not fifty")
        void fiftyTenthsOfASecondIsFiveSeconds() {
            assertEquals(50, AuthorizationMessageMapper.REPLY_EXPIRY_TENTHS_OF_A_SECOND);
            assertEquals(Duration.ofSeconds(5), AuthorizationMessageMapper.REPLY_EXPIRY_HORIZON);
            assertNotEquals(Duration.ofSeconds(AuthorizationMessageMapper
                    .REPLY_EXPIRY_TENTHS_OF_A_SECOND), AuthorizationMessageMapper.REPLY_EXPIRY_HORIZON);
        }

        /**
         * The deadline is the send instant advanced by the published horizon, and it refuses no instant.
         *
         * <p>Assumptions: the expectation is composed from the published horizon rather than from a
         * five-second literal, so a change to the descriptor reading is restated in one place. The
         * instant chosen crosses no boundary of its own, because the arithmetic under test is an addition
         * on an instant and a calendar edge would test the platform rather than this mapper.</p>
         *
         * <p>Assumptions: the refusal of a null instant is asserted in the same case, as a
         * {@code NullPointerException}. A deadline computed from an absent instant would be an absent
         * deadline, and an absent deadline is exactly what makes a stale reply undetectable.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the deadline advances the send instant by the published horizon")
        void theDeadlineAdvancesTheSendInstantByTheHorizon() {
            LocalDateTime sentAt = LocalDateTime.of(2026, 8, 8, 9, 14, 27);

            assertEquals(sentAt.plus(AuthorizationMessageMapper.REPLY_EXPIRY_HORIZON),
                    AuthorizationMessageMapper.replyExpiresAt(sentAt));
            assertThrows(NullPointerException.class,
                    () -> AuthorizationMessageMapper.replyExpiresAt(null));
        }
    }

    /**
     * Where the diagnostic record goes, and that its two coded domains are carried whole.
     *
     * <p>Assumptions: the record is {@code ERROR-LOG-RECORD} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} L19 to L40, eleven fields whose declared
     * widths are 6, 6, 8, 8, 4, 1, 1, 9, 9, 50 and 20 and therefore
     * {@link AuthorizationMessageMapper#ERROR_LOG_RECORD_LENGTH} characters in total. Its severity
     * condition names at L26 to L29 admit four values and its subsystem condition names at L31 to L36
     * admit six, and those two counts are what this group sweeps. The record's field ordering, its
     * projection's key set and the tokenised event key are asserted elsewhere in this package.</p>
     *
     * <p>Alternatives Considered: projecting this record onto a dedicated queue message, so that the
     * reference application's terminal error queue kept a direct counterpart. That was evaluated and
     * rejected on two grounds. The reference queue's only consumer is an operator reading it, and the
     * target replaces that reader with centralized structured logging, so a queue message would duplicate
     * a log entry without adding a consumer -- and it would then need its own retention, its own
     * encryption key grant and its own alarm to be worth having. More decisively, a diagnostic is emitted
     * on paths that are ALREADY failing, so making its delivery depend on a transport call adds a second
     * thing that can fail at the exact moment the first has: a queue outage would take the record of the
     * outage with it. Structured logging reaches the collector by a route the request path does not share.
     * This is not the dead-letter queue decision and must not be conflated with it: the target does retain
     * a dead-letter queue for every functional queue, which exists to preserve an undeliverable BUSINESS
     * message for redrive, whereas this record is an observation about a failure and has no redrive
     * meaning at all.</p>
     */
    @Nested
    @DisplayName("the diagnostic record routes to structured logging")
    class DiagnosticRouting {

        /**
         * All four declared severities are carried verbatim and only the critical one is terminal.
         *
         * <p>Assumptions: the four are swept from a table declared in the case rather than from the
         * mapper's own constants, because the mapper publishes only the two severities its own call sites
         * use and a sweep over those two could not detect a third being mishandled. The copybook is the
         * authority for the domain, so the table is the copybook's.</p>
         *
         * <p>Assumptions: the mapping is asserted TOTAL. Each severity appears in the projection exactly
         * as supplied, so none is silently folded onto another, and only the critical value reports as
         * terminal. A projection that defaulted an unrecognised severity would be the dangerous failure
         * here, because it would report a critical fault at a level nobody alerts on.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("all four declared severities are carried verbatim and only critical is terminal")
        void allFourSeveritiesAreCarriedVerbatim() {
            List<String> declaredSeverities = List.of("L", "I", "W", "C");
            List<String> carried = new ArrayList<>();
            List<String> terminal = new ArrayList<>();

            for (String severity : declaredSeverities) {
                AuthorizationMessageMapper.ErrorLogEntry entry = diagnosticAt(severity,
                        AuthorizationMessageMapper.ERROR_SUBSYSTEM_APPLICATION);
                carried.add(entry.structuredFields().get("level"));
                if (entry.isTerminal()) {
                    terminal.add(severity);
                }
            }

            assertEquals(declaredSeverities, carried);
            assertEquals(List.of(AuthorizationMessageMapper.ERROR_LEVEL_CRITICAL), terminal);
            assertEquals(AuthorizationMessageMapper.ERROR_LEVEL_WARNING, declaredSeverities.get(2));
        }

        /**
         * All six declared subsystems are carried verbatim and remain distinguishable from one another.
         *
         * <p>Assumptions: the six are asserted as a distinct ordered list rather than one at a time,
         * because the failure worth catching is two of them collapsing onto one value -- a fault in the
         * datastore reported as a fault in the transport sends an operator to the wrong system. Comparing
         * the projected list against the declared list catches a collapse and a reordering together.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("all six declared subsystems are carried verbatim and stay distinct")
        void allSixSubsystemsAreCarriedVerbatim() {
            List<String> declaredSubsystems = List.of("A", "C", "I", "D", "M", "F");
            List<String> carried = new ArrayList<>();

            for (String subsystem : declaredSubsystems) {
                carried.add(diagnosticAt(AuthorizationMessageMapper.ERROR_LEVEL_WARNING, subsystem)
                        .structuredFields().get("subsystem"));
            }

            assertEquals(declaredSubsystems, carried);
            assertEquals(declaredSubsystems.size(), carried.stream().distinct().count());
            assertEquals(AuthorizationMessageMapper.ERROR_SUBSYSTEM_APPLICATION,
                    declaredSubsystems.get(0));
        }

        /**
         * A code outside either declared domain is carried as given and never promoted or defaulted.
         *
         * <p>Assumptions: an unrecognised severity is reported as NOT terminal and is neither replaced by
         * a default nor rejected. Rejecting it would discard the only account of a failure this service
         * has, on the one path guaranteed to run when something is already wrong; defaulting it would
         * relabel it, which is worse than carrying an unknown code because a reader would then trust the
         * label. Carrying it lets a human see an unexpected value and lets an alert rule keep matching on
         * the critical code alone.</p>
         *
         * <p>Assumptions: an absent code projects as an empty value rather than as a missing key, which is
         * the projection's documented treatment of every absent component, so a consumer's field set does
         * not change shape between a complete and an incomplete diagnostic.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an unrecognised severity is carried as given, neither defaulted nor promoted")
        void anUnrecognisedSeverityIsCarriedAsGiven() {
            AuthorizationMessageMapper.ErrorLogEntry unknown = diagnosticAt("Z", "Z");
            AuthorizationMessageMapper.ErrorLogEntry absent = diagnosticAt(null, null);

            assertEquals("Z", unknown.structuredFields().get("level"));
            assertEquals("Z", unknown.structuredFields().get("subsystem"));
            assertEquals(false, unknown.isTerminal());
            assertEquals("", absent.structuredFields().get("level"));
            assertEquals("", absent.structuredFields().get("subsystem"));
            assertEquals(false, absent.isTerminal());
            assertEquals(true, absent.structuredFields().containsKey("level"));
        }
    }

    /**
     * The behaviour of money at the crossing, which no import graph can observe.
     *
     * <p>Assumptions: the prohibition on declaring a binary floating-point field, parameter or return type
     * is an architecture rule this package INHERITS -- it selects classes residing under the analysed
     * {@code com.carddemo} root, so this context is inside its subject set. No declaration-level
     * assertion is therefore made here, because it would re-prove an inherited rule and leave two suites
     * claiming one contract. What remains this class's own is behaviour: that an amount carries a scale of
     * exactly two after the crossing, and that the reduction which establishes that scale is half-up.</p>
     *
     * <p>Assumptions: the third member of that trio -- that an amount crosses an interface as a string
     * rather than as a bare number -- is asserted by the transfer-type serialisation test in
     * {@code .dto}, because the document form is that class's subject. It is named here so its absence is
     * not read as a gap. It matters for the same reason the scale does: a bare number in a document is
     * parsed into a binary floating-point value by most clients, which discards exactness at the one
     * boundary a user actually sees.</p>
     *
     * <p>Assumptions: the wire form on this path is the delimited record and not a document, so the string
     * rule governs the additive document envelope offered to new consumers. The delimited form remains the
     * normative contract and the envelope never replaces it.</p>
     */
    @Nested
    @DisplayName("money behaviour at the crossing")
    class MoneyAtTheCrossing {

        /**
         * Both amounts carry a scale of exactly two on each side of the crossing.
         *
         * <p>Assumptions: the scale is asserted on the exact decimal behind the amount and on BOTH
         * directions, because the crossing has two entry points and a scale established on the way in
         * tells a reader nothing about the way out. Two is taken from {@link Money#SCALE} rather than
         * written as a literal, so the assertion follows the shared kernel's declaration of the
         * {@code V99} the copybook pictures carry.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both amounts carry a scale of exactly two in both directions")
        void bothAmountsCarryScaleTwoInBothDirections() {
            AuthRequest request = distinctRequest();
            AuthReply reply = new AuthReply("4000123456789010", "TXN000000000100", "A00001", "00",
                    "0000", Money.of("250.00"));

            AuthorizationRequestPayload requestPayload = mapper.toPayload(request);
            AuthorizationReplyPayload replyPayload = mapper.toPayload(reply);

            assertEquals(Money.SCALE, requestPayload.transactionAmount().amount().scale());
            assertEquals(Money.SCALE, replyPayload.approvedAmount().amount().scale());
            assertEquals(Money.SCALE,
                    mapper.toWireRecord(requestPayload).transactionAmount().amount().scale());
            assertEquals(Money.SCALE,
                    mapper.toWireRecord(replyPayload).approvedAmount().amount().scale());
        }

        /**
         * A narrower scale is padded and a wider one is reduced half-up, and the crossing keeps both.
         *
         * <p>Assumptions: a two-hundred-and-fifty with no fractional part and one with a single
         * fractional digit both reach two places by PADDING rather than by rounding, so establishing the
         * scale must not alter a value that was already exact. That is what distinguishes setting a scale
         * from rounding to it, and it is asserted through the crossing rather than on the amount alone
         * because the crossing is what a payload actually travels through.</p>
         *
         * <p>Assumptions: half-up is asserted as BEHAVIOUR and not as a constant identity. That
         * {@link Money#GENERAL_ROUNDING} equals half-up is already asserted by
         * {@code PendingAuthDetailMapperTest} at the storage boundary, and restating it here would leave
         * two suites asserting one constant. What this case asserts instead is discriminating: three
         * decimal places at five in the thousandths reduce UP to a hundredth, and the value that reaches
         * the wire is asserted to differ from the one a downward reduction would have produced. A crossing
         * that re-quantized with any other mode fails here, which no constant comparison can detect.</p>
         *
         * <p>Assumptions: the reduction itself is the shared kernel's, so what belongs to this class is
         * that the crossing carries the reduced value FAITHFULLY and does not quantize it a second time.
         * Both directions are asserted for that reason.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a narrower scale is padded and a wider one reduces upward across the crossing")
        void aNarrowerScaleIsPaddedAndAWiderOneReducesUpward() {
            Money fromWholeUnits = Money.of(new BigDecimal("250"));
            Money fromOnePlace = Money.of(new BigDecimal("250.0"));
            Money fromThreePlaces = Money.of(new BigDecimal("250.005"));
            BigDecimal downwardWouldGive =
                    new BigDecimal("250.005").setScale(Money.SCALE, RoundingMode.DOWN);

            AuthorizationReplyPayload padded = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "00", "0000", fromWholeUnits);
            AuthorizationReplyPayload reduced = new AuthorizationReplyPayload("4000123456789010",
                    "TXN000000000100", "A00001", "00", "0000", fromThreePlaces);

            assertEquals(Money.SCALE, fromWholeUnits.amount().scale());
            assertEquals(fromWholeUnits, fromOnePlace);
            assertEquals(new BigDecimal("250.00"),
                    mapper.toWireRecord(padded).approvedAmount().amount());
            assertEquals(new BigDecimal("250.01"),
                    mapper.toWireRecord(reduced).approvedAmount().amount());
            assertNotEquals(downwardWouldGive,
                    mapper.toWireRecord(reduced).approvedAmount().amount());
            assertEquals(fromThreePlaces,
                    mapper.toPayload(mapper.toWireRecord(reduced)).approvedAmount());
        }
    }

    /**
     * Reads one 171-byte record of the amount-variants fixture as its 170-character payload.
     *
     * <p>Assumptions: the fixture is a uniform-stride text file, so a record is located by arithmetic
     * rather than by scanning: record N occupies bytes 171 times N through 171 times N plus 170, of which
     * the first {@link CsvAuthCodec#REQUEST_WIRE_LENGTH} are the payload and the last is the line-feed
     * terminator. The terminator is dropped here explicitly rather than by a text reader, because a
     * reader that also stripped a carriage return would silently shorten the payload by one and move
     * every delimiter after it.</p>
     *
     * <p>Assumptions: the returned length is checked against the published wire length before the value
     * is handed back, so a fixture edited to a different width fails inside this helper naming the record
     * rather than inside a caller naming a field.</p>
     *
     * @param recordIndex the zero-based record position, 0 for the negative vector, 1 for the all-nines
     *     vector and 2 for the canonical zero
     * @return the record's payload characters without the line-feed terminator, never {@code null}
     * @throws java.io.IOException if the committed fixture cannot be read
     * @throws IllegalStateException if the fixture is absent, or if the record at that index is not the
     *     published wire length, either of which means the assertion built on it would be meaningless
     */
    private String amountVariantRecord(int recordIndex) throws java.io.IOException {
        String all = oracle("auth-request-amount-variants.csv");
        int stride = CsvAuthCodec.REQUEST_WIRE_LENGTH + 1;
        int start = recordIndex * stride;
        String record = all.substring(start, start + CsvAuthCodec.REQUEST_WIRE_LENGTH);
        if (record.length() != CsvAuthCodec.REQUEST_WIRE_LENGTH || record.indexOf('\n') >= 0) {
            throw new IllegalStateException("amount-variant record " + recordIndex
                    + " is not one payload of the published wire length");
        }
        return record;
    }

    /**
     * Extracts one delimited token from a payload by its zero-based ordinal.
     *
     * <p>Assumptions: the payload is split on {@link CsvAuthCodec#DELIMITER} with a negative limit, so a
     * trailing empty token is retained rather than discarded. The reply payload ends with a delimiter, and
     * a positive limit would drop the element after it and renumber nothing -- but it would make the
     * element count disagree with the delimiter count, which is the sort of quiet disagreement a
     * positional format cannot survive.</p>
     *
     * @param payload the delimited payload to read, without any terminator
     * @param ordinal the zero-based position of the wanted token
     * @return the token at that ordinal, with no trimming applied so that declared-width padding survives
     * @throws IllegalStateException if the payload holds no token at that ordinal, which means the caller
     *     is reading a payload of a different shape than it believes
     */
    private String amountTokenOf(String payload, int ordinal) {
        String[] tokens = payload.split(String.valueOf(CsvAuthCodec.DELIMITER), -1);
        if (ordinal >= tokens.length) {
            throw new IllegalStateException("the payload holds no token at ordinal " + ordinal);
        }
        return tokens[ordinal];
    }

    /**
     * Builds a request payload that differs from the canonical one only in its amount.
     *
     * <p>Assumptions: every other component is held at a value the payload's own domain accepts, so a
     * refusal raised by a case using this helper is attributable to the amount and to nothing else. The
     * values match the canonical committed request record, which keeps the two comparable when a case
     * asserts against that fixture.</p>
     *
     * @param amount the amount to place in the ninth wire position
     * @return the payload, never {@code null}
     */
    private AuthorizationRequestPayload requestPayloadWithAmount(Money amount) {
        return new AuthorizationRequestPayload("240404", "091500", "4000123456789010", "PURC", "1227",
                "0100  ", "POS   ", "000000", amount, "5411", "840", "05", "MERCH0000000001",
                "ACME HARDWARE         ", "SEATTLE      ", "WA", "98101    ", "TXN000000000100");
    }

    /**
     * Builds a diagnostic record differing only in its severity and subsystem codes.
     *
     * <p>Assumptions: every other component is fixed, so a swept assertion attributes a difference to the
     * code under test. The event key is left absent because this group's subject is the two coded domains
     * and the key's disclosure treatment is asserted elsewhere in this package; supplying one here would
     * draw a second subject into a sweep that has no assertion for it.</p>
     *
     * @param level the single-character severity to place in the record, which may be {@code null}
     * @param subsystem the single-character subsystem to place in the record, which may be {@code null}
     * @return the diagnostic record, never {@code null}
     */
    private AuthorizationMessageMapper.ErrorLogEntry diagnosticAt(String level, String subsystem) {
        return AuthorizationMessageMapper.errorLogEntry(LocalDateTime.of(2026, 8, 8, 9, 14, 27),
                "CP00", "COPAUA0C", "A001", level, subsystem, "00000000", "00000000",
                "CARD NOT FOUND IN XREF", null);
    }



    /**
     * Reads one committed oracle image as the characters the wire carries.
     *
     * @param resourceName the fixture file name within the module's fixture directory
     * @return the image as a string, never {@code null}
     * @throws java.io.IOException if the resource cannot be read
     * @throws IllegalStateException if the resource is absent, which a rename would cause
     */
    private String oracle(String resourceName) throws java.io.IOException {
        try (java.io.InputStream image = AuthorizationMessageMapperTest.class
                .getResourceAsStream("/fixtures/" + resourceName)) {
            if (image == null) {
                throw new IllegalStateException("the committed fixture " + resourceName
                        + " is absent, so the encode oracle cannot be asserted");
            }
            return new String(image.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
        }
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
