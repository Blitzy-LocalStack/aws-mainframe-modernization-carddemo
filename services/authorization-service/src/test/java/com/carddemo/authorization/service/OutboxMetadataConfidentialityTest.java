package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.money.Money;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins exactly which SQS message METADATA fields the reply path is allowed to put a card number into.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: this class was authored to assert that NO metadata field carried a primary
 * account number, and that absolute rule has been narrowed to a containment rule, because the
 * specification states the two first-in-first-out identities literally. Section 0.4.1.8 fixes
 * {@code MessageGroupId = card_num} and {@code MessageDeduplicationId = transaction_id}, and section
 * 0.7.6 repeats the grouping rule as the mechanism that preserves per-card ordering. Those identities are
 * therefore no longer this service's choice to make, and the interesting question changes from "is the
 * number absent from metadata" to "is it confined to the one field the specification puts it in". This
 * class answers the second question, which is the one that can still fail: a card number appearing in a
 * message ATTRIBUTE, or in the deduplication identity, or in the queue address, would be a new leak that
 * no contract asks for.</p>
 *
 * <p>Alternatives Considered: keeping the absolute rule and deriving both identities through the keyed
 * tokeniser, which is what the previous revision did. Rejected because the derivation broke the guarantee
 * it was protecting. A group identity has to be EQUAL for equal cards across every producer on the queue
 * -- that equality is the whole of the ordering guarantee -- and a value keyed from this service's secret
 * is one only this service can compute, so a second producer's replies for the same card land in a
 * different group. Duplicate suppression has the same shape: the requester may resend, and suppression
 * compares an identity the requester can predict. Confidentiality that removes an ordering guarantee is
 * not a trade the specification leaves open.</p>
 *
 * <p>Trade-offs: the card number consequently appears in queue metadata, where the encrypted body does
 * not protect it, and that consequence is registered as divergence
 * {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA} in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than left implicit. Three controls
 * bound it, and all three are provisioned rather than assumed: the queue is encrypted server-side under a
 * customer-managed key, it is reachable only through a private-network interface endpoint, and read
 * access is scoped to the task roles of this service and of the requesting producer. What remains
 * unbounded by those controls is queue telemetry and any log line that records a group identity, which is
 * why this class still enumerates every OTHER metadata field: the exposure the specification requires is
 * one field wide, and nothing may widen it quietly.</p>
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
     * The card number that appears in the body and, as the group identity, in exactly one metadata field.
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
     * <p>Assumptions: the two identities are taken from the reply's OWN accessors, {@code cardNum} and
     * {@code transactionId}, rather than restated as string literals. The row this builds has to be the
     * row the consumer writes, and reading both values off the same carrier the consumer encodes is what
     * keeps this fixture from drifting into asserting a card the body does not carry.</p>
     *
     * @param correlationId the correlation identity to echo, or {@code null} for none
     * @return a pending row; never {@code null}
     */
    private static AuthReplyOutbox pendingRow(String correlationId) {
        CsvAuthCodec.AuthReply reply = reply();
        LocalDateTime now = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
        AuthReplyOutbox row = new AuthReplyOutbox(REPLY_QUEUE, correlationId,
                reply.cardNum(), reply.transactionId(),
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
        when(outbox.claimGroupHeads(eq(25), any(), any())).thenReturn(List.of(row));
        when(outbox.claimGroupFollowers(anyString(), anyLong(), anyInt(), any(), any()))
                .thenReturn(List.of());
        // WHY : Assumptions: the outcome re-read is answered with the same row, because the publisher no
        //       longer mutates the instance it claimed -- it re-reads by identity inside a second short
        //       transaction and writes the outcome there. Without this stub the transition is refused as
        //       superseded, which would not stop the send this class captures but would leave the row
        //       unpublished, so a later reader could not tell a routing assertion from a lifecycle one.
        when(outbox.findById(any())).thenReturn(Optional.of(row));
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m-1").build());

        // WHY : Assumptions: every configured number is supplied because the publisher takes them all,
        //       and each is the value its own property default carries -- the batch size, poll interval,
        //       retention window, per-pass row budget and attempt ceiling. None is relevant to what this
        //       class asserts, since drain() is invoked directly rather than by the scheduler, nothing is
        //       swept and one row cannot reach a budget; using the defaults keeps the construction here
        //       from implying a tuning decision this class is not making.
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        new OutboxPublisher(outbox, sqs, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), transactions, 25,
                1000, 7, 500, 10).drain();

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        return sent.getValue();
    }

    /**
     * Verifies the group identity on the send is the card number itself, exactly as specified.
     *
     * <p>Assumptions: the assertion is equality with the whole sixteen characters rather than a
     * containment check, because ordering holds only if two producers computing the identity from the same
     * card agree character for character. A value that merely CONTAINED the card number -- a prefixed or
     * suffixed rendering -- would group this producer's replies apart from every other producer's while
     * looking correct in a log.</p>
     */
    @Test
    @DisplayName("the published group identity is the literal card number the specification fixes")
    void theGroupIdentityIsTheLiteralCardNumber() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        assertThat(sent.messageGroupId()).isEqualTo(CARD_NUMBER);
    }

    /**
     * Verifies the card number reaches metadata ONLY as the group identity and nowhere else.
     *
     * <p>Assumptions: the group identity is excluded from the search and every other metadata field is
     * included, so this case fails the moment a card number appears somewhere the specification does not
     * put it. That is the containment rule the class exists for now that the group identity itself is
     * fixed by section 0.4.1.8: the exposure is one field wide, and this is what stops it widening.</p>
     *
     * <p>Assumptions: the six-digit leading range and the four-digit trailing range are searched as well
     * as the whole number. A partial disclosure is still a disclosure -- the leading digits identify the
     * issuer and the trailing four are the masked-form remainder -- so searching only for the complete
     * sixteen digits would pass a metadata field carrying half of it.</p>
     *
     * <p>Alternatives Considered: dropping this case once the group identity became the card number,
     * on the grounds that the number is now in metadata anyway. Rejected because it confuses a specified
     * exposure with an unbounded one. The attributes carry a content type, an expiry instant and the
     * requester's own correlation value, none of which has any reason to hold cardholder data, and a
     * debugging attribute added later is exactly the regression this catches.</p>
     */
    @Test
    @DisplayName("the card number reaches metadata only as the group identity")
    void theCardNumberReachesMetadataOnlyAsTheGroupIdentity() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        Map<String, MessageAttributeValue> attributes = sent.messageAttributes();
        StringBuilder otherMetadata = new StringBuilder()
                .append(sent.queueUrl())
                .append('\u0000').append(sent.messageDeduplicationId());
        attributes.forEach((name, value) -> otherMetadata
                .append('\u0000').append(name)
                .append('\u0000').append(value.stringValue()));

        assertThat(otherMetadata.toString())
                .as("only MessageGroupId is specified to carry the number; nothing else may")
                .doesNotContain(CARD_NUMBER)
                .doesNotContain(CARD_NUMBER.substring(0, 6))
                .doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
    }

    /**
     * Verifies the deduplication identity is the acquirer's transaction identifier, exactly as specified.
     *
     * <p>Refactoring Rationale: this assertion has been in three states -- the raw identifier, a keyed
     * token over the card and transaction pair, and now the raw identifier again. The specification settles
     * it at section 0.4.1.8 with {@code MessageDeduplicationId = transaction_id}, and the reason the
     * token could not stand is the same one that retired it for the group identity: suppression compares an
     * identity the REQUESTER may resend, so it has to be a value the requester can predict. A token keyed
     * from this service's secret is not, which means a duplicate arriving through any other path is
     * accepted as new.</p>
     *
     * <p>Assumptions: equality is asserted against the identifier the fixture reply carries rather than
     * against a restated literal, so a fixture edit cannot leave this case asserting a stale value.</p>
     */
    @Test
    @DisplayName("the deduplication identity is the literal transaction identifier")
    void theDeduplicationIdentityIsTheLiteralTransactionIdentifier() {
        SendMessageRequest sent = sendRequestFor(pendingRow("req-0001"));

        assertThat(sent.messageDeduplicationId()).isEqualTo(TRANSACTION_ID);
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
