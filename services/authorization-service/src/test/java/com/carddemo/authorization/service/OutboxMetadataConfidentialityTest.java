package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Asserts that no primary account number reaches SQS message METADATA on the reply path.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: the group identity on every published reply was the card number itself, and
 * this class is the assertion the finding asks for -- metadata assertions proving the number is absent.
 * The distinction that makes it necessary is that the message BODY and the message METADATA have
 * different protections. The body is the reference wire contract and legitimately carries the number, so
 * a test that simply searched the whole send request for it would have to fail. Metadata is different: it
 * sits outside the body, it is reported in queue telemetry, and it is carried into logs and metrics, which
 * is where {@code docs/adr/ADR-008-security-and-identity.md} requires the number to be masked. These
 * assertions therefore examine every metadata field individually and deliberately leave the body alone.
 * </p>
 *
 * <p>Alternatives Considered: asserting only on the group identity, which is the field the finding names.
 * Rejected because it would leave the deduplication identifier, the queue address and the three message
 * attributes unexamined, and the defect was not that one field was chosen wrongly but that nothing
 * checked what went into metadata at all. Enumerating every field is what makes a NEW leak -- a card
 * number added to an attribute for debugging, say -- fail here.</p>
 *
 * <p>Assumptions: the queue client is a mock and the send request is captured rather than sent. What is
 * under test is the CONTENT of the request the publisher builds, which is fully determined before any
 * network call, so a real or emulated queue would add a dependency without adding an assertion.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class OutboxMetadataConfidentialityTest {

    /**
     * The card number that must appear in the body and nowhere in the metadata.
     */
    private static final String CARD_NUMBER = "4111111111112345";

    /**
     * The acquirer's transaction identifier, which is the deduplication identity.
     */
    private static final String TRANSACTION_ID = "000000000000001";

    /**
     * An allowlisted reply destination.
     */
    private static final String REPLY_QUEUE = "https://sqs.us-east-1.amazonaws.com/1/reply.fifo";

    /**
     * A fixed instant so the publication timestamp and the expiry comparison are reproducible.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /**
     * The tokeniser the group identity is derived through, keyed deterministically.
     */
    private static final OpaqueIdentifier TOKENISER = new OpaqueIdentifier(fixedKeyMaterial());

    /**
     * Builds deterministic tokeniser key material of the minimum admissible length.
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
     * Builds the reply carrier a published row was encoded from.
     *
     * @return an approved reply for the fixture card; never {@code null}
     */
    private static CsvAuthCodec.AuthReply reply() {
        return new CsvAuthCodec.AuthReply(CARD_NUMBER, TRANSACTION_ID, "143000", "00", "0000",
                Money.of("125.50"));
    }

    /**
     * Builds a pending outbox row exactly as the consumer writes one.
     *
     * <p>Assumptions: the row is constructed through the same accessor the consumer uses --
     * {@code AuthReply.orderGroup} -- rather than by writing a token literal. Using a literal would let
     * this test keep passing after the consumer stopped deriving the value, which is the regression it
     * exists to catch.</p>
     *
     * @param correlationId the correlation identity to echo, or {@code null} for none
     * @return a pending row; never {@code null}
     */
    private static AuthReplyOutbox pendingRow(String correlationId) {
        CsvAuthCodec.AuthReply reply = reply();
        LocalDateTime now = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
        AuthReplyOutbox row = new AuthReplyOutbox(REPLY_QUEUE, correlationId,
                reply.orderGroup(TOKENISER), reply.deduplicationKey(TOKENISER),
                CsvAuthCodec.encodeReply(reply), now.plusSeconds(300), now);
        // WHY : Assumptions: the database-generated identity is assigned reflectively because the
        //       publisher reads it to ask for the next row of the same ordering group, and the entity
        //       exposes no setter for it -- which is correct for production. The alternative, adding a
        //       setter, would widen the entity's surface for a test's convenience.
        assignIdentity(row, 1L);
        return row;
    }

    /**
     * Assigns the database-generated identity of the row under test.
     *
     * @param row the row to assign; must not be {@code null}
     * @param outboxId the identity to assign
     * @throws IllegalStateException if the field cannot be reached, which would mean the entity's
     *     identity member had been renamed and this helper had not been updated with it
     */
    private static void assignIdentity(AuthReplyOutbox row, long outboxId) {
        try {
            Field field = AuthReplyOutbox.class.getDeclaredField("outboxId");
            field.setAccessible(true);
            field.set(row, outboxId);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(
                    "AuthReplyOutbox.outboxId is no longer reachable, so this helper is stale",
                    unreachable);
        }
    }

    /**
     * Drains one row and returns the send request the publisher built for it.
     *
     * @param row the row to drain; must not be {@code null}
     * @return the captured send request; never {@code null}
     */
    private static SendMessageRequest sendRequestFor(AuthReplyOutbox row) {
        OutboxRepository outbox = mock(OutboxRepository.class);
        SqsClient sqs = mock(SqsClient.class);
        // WHY : Refactoring Rationale: the claim is stubbed per ORDERING GROUP -- a head row and then no
        //       follower -- and it was a single global claim of the oldest rows. The publisher claims one
        //       head per group and advances that group only once its head has been accepted, so a stub of
        //       the older shape would leave drain() with nothing to send and this class would assert
        //       nothing while still passing.
        when(outbox.claimGroupHeads(25)).thenReturn(List.of(row));
        when(outbox.claimGroupFollowers(anyString(), anyLong(), anyInt())).thenReturn(List.of());
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m-1").build());

        // WHY : Assumptions: the poll interval and the retention window are supplied as well as the batch
        //       size, because the publisher takes all three. Neither is relevant to what this asserts --
        //       drain() is invoked directly rather than by the scheduler, and nothing here is swept -- so
        //       each value is the one its property default carries, which keeps the construction here from
        //       implying a tuning decision.
        new OutboxPublisher(outbox, sqs, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), 25, 1000, 7)
                .drain();

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        return sent.getValue();
    }

    /**
     * Verifies the group identity on the send is the derived token and never the card number.
     */
    @Test
    @DisplayName("the published group identity is the derived token and never the card number")
    void theGroupIdentityIsTheDerivedToken() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        assertThat(sent.messageGroupId())
                .isEqualTo(TOKENISER.token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER))
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .isNotEqualTo(CARD_NUMBER);
    }

    /**
     * Verifies no metadata field on the send carries the card number or any run of its digits.
     *
     * <p>Assumptions: the six-digit leading range and the four-digit trailing range are searched as well
     * as the whole number. A partial disclosure is still a disclosure -- the leading digits identify the
     * issuer and the trailing four are the masked-form remainder -- so searching only for the complete
     * sixteen digits would pass a metadata field carrying half of it.</p>
     */
    @Test
    @DisplayName("no metadata field carries the card number or any run of its digits")
    void noMetadataFieldCarriesTheCardNumber() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        Map<String, MessageAttributeValue> attributes = sent.messageAttributes();
        StringBuilder allMetadata = new StringBuilder()
                .append(sent.queueUrl())
                .append('\u0000').append(sent.messageGroupId())
                .append('\u0000').append(sent.messageDeduplicationId());
        attributes.forEach((name, value) -> allMetadata
                .append('\u0000').append(name)
                .append('\u0000').append(value.stringValue()));

        assertThat(allMetadata.toString())
                .as("metadata leaves the encrypted body and reaches queue telemetry and logs")
                .doesNotContain(CARD_NUMBER)
                .doesNotContain(CARD_NUMBER.substring(0, 6))
                .doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
    }

    /**
     * Verifies the deduplication identity is the keyed token over the card and transaction pair.
     *
     * <p>Refactoring Rationale: this value was the acquirer's transaction identifier, published raw, and
     * an earlier revision of this class asserted that it stayed raw. It is tokenised for the same reason
     * as the group identity: a deduplication identifier is METADATA rather than body, server-side
     * encryption covers a body and not its metadata, and the transaction identifier is the value that
     * joins a queue observer's view to a cardholder's purchase everywhere else it is recorded. The
     * contract {@code docs/adr/ADR-004-messaging.md} fixes is that suppression is keyed on the request's
     * identity rather than on its bytes, and a token over the pair preserves that exactly -- it is equal
     * for equal pairs, so a re-sent reply whose rendering changed is still recognised as the same
     * reply.</p>
     *
     * <p>Assumptions: the token is asserted to be the derivation rather than merely to differ from the
     * identifier, and the identifier is asserted absent. A publisher that emitted any other stable value
     * would satisfy the second assertion alone while breaking duplicate suppression.</p>
     */
    @Test
    @DisplayName("the deduplication identity is the keyed token over the card and transaction pair")
    void theDeduplicationIdentityIsTheKeyedToken() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        assertThat(sent.messageDeduplicationId())
                .isEqualTo(reply().deduplicationKey(TOKENISER))
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .doesNotContain(TRANSACTION_ID);
    }

    /**
     * Verifies the message body still carries the full number, so the wire contract is intact.
     *
     * <p>Assumptions: this is asserted alongside the metadata rules rather than elsewhere, because the
     * two together are the actual requirement. A change that removed the number from the body would
     * satisfy every metadata assertion above and would break the requester at
     * {@code cpy/CCPAURLY.cpy} L19, which reads the reply positionally and expects sixteen characters
     * first.</p>
     */
    @Test
    @DisplayName("the message body still carries the full number, so the wire contract is intact")
    void theBodyStillCarriesTheNumber() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        assertThat(sent.messageBody()).startsWith(CARD_NUMBER + ",");
    }

    /**
     * Verifies the correlation attribute is echoed exactly and is not replaced by a derivation.
     *
     * <p>Assumptions: the requester's own correlation value must cross back UNCHANGED, because it is the
     * only thing pairing an answer with its question. It is not tokenised for that reason, and it is safe
     * not to be: it is a value the requester chose, so it discloses nothing this service holds.</p>
     */
    @Test
    @DisplayName("the requester's correlation attribute is echoed unchanged")
    void theCorrelationAttributeIsEchoedUnchanged() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        assertThat(sent.messageAttributes().get(OutboxPublisher.ATTRIBUTE_CORRELATION_ID).stringValue())
                .isEqualTo("req-0001");
    }

    /**
     * Verifies a row that carried no correlation identity publishes no correlation attribute.
     */
    @Test
    @DisplayName("an absent correlation identity publishes no correlation attribute")
    void anAbsentCorrelationPublishesNoAttribute() {
        SendMessageRequest sent = sendRequestFor(pendingRow(null));

        assertThat(sent.messageAttributes())
                .doesNotContainKey(OutboxPublisher.ATTRIBUTE_CORRELATION_ID)
                .containsKeys(OutboxPublisher.ATTRIBUTE_CONTENT_TYPE,
                        OutboxPublisher.ATTRIBUTE_EXPIRES_AT);
    }
}
