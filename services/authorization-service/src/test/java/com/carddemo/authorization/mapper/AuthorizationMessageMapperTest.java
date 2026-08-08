package com.carddemo.authorization.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
     * A reply destination of the shape the deployment nominates, used by the routing assertions.
     *
     * <p>Assumptions: the value is a plain character string and no transport client parses it here, which
     * is the property the routing carrier is documented to hold, so any well-formed address serves.</p>
     */
    private static final String REPLY_QUEUE_URL =
            "https://sqs.eu-west-2.amazonaws.com/000000000000/carddemo-pauth-reply-dev.fifo";

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
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the outbox projection carries the routing, the encoded reply and the deadline")
    void theOutboxProjectionCarriesTheRoutingAndTheDeadline() {
        LocalDateTime sentAt = LocalDateTime.of(2026, 8, 8, 9, 14, 27);
        AuthReply reply = new AuthReply("4111111111111111", "TXN000000000001", "104530", "00",
                "0000", Money.of("100.99"));
        AuthorizationMessageMapper.ReplyRouting routing =
                new AuthorizationMessageMapper.ReplyRouting(REPLY_QUEUE_URL, "corr-1",
                        AuthorizationMessageMapper.replyExpiresAt(sentAt));

        OutboxMessage publication = mapper.toOutboxMessage(reply, routing, TOKENISER);

        assertEquals(REPLY_QUEUE_URL, publication.replyQueueUrl());
        assertEquals("corr-1", publication.correlationId());
        assertEquals(CsvAuthCodec.encodeReply(reply), publication.payload());
        assertEquals(sentAt.plusSeconds(5), publication.expiresAt());
        assertEquals(reply.orderGroup(TOKENISER), publication.orderGroupToken());
        assertEquals(reply.deduplicationKey(TOKENISER), publication.deduplicationToken());
    }

    /**
     * A reply publication cannot be made durable without the deadline that stops a stale send.
     *
     * <p>Assumptions: the canonical constructor is asserted to STILL accept an absent deadline in the
     * same test, because the two rules are one decision and asserting only the refusal would let a
     * later edit tighten the constructor and break the projection of a row whose column is null.</p>
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
