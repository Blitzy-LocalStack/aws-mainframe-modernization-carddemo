package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Pins what the outbox publisher refuses to be CONSTRUCTED with, and the two ordering and retention
 * properties it exists to guarantee.
 *
 * <p>The first subject is the three configured numbers the publisher reads: the batch size one drain
 * claims, the delay between drains, and the retention window the sweep deletes behind. None was enforced,
 * while the documentation on the batch size already said it must be positive -- so those failure modes
 * reached a scheduled background thread rather than the start-up path, which is where a misconfiguration
 * has to surface if an orchestrator is to report it.</p>
 *
 * <p>The second subject is behavioural: that a card's replies reach the queue in the order they were
 * decided, and that the retention sweep asks for the configured age rather than for everything.</p>
 *
 * <p>Assumptions: the construction cases do not drive {@code drain()} and assert that no collaborator was
 * reached, because a constructor that reached a collaborator before validating its numbers would be doing
 * work on behalf of an instance that is about to be refused. The behavioural cases drive the publisher
 * over the same mocks, which is sufficient because every property they assert is a property of the ORDER
 * and CONTENT of the send calls it issues and of the predicate it deletes by -- none of which needs a real
 * queue or a real database to observe.</p>
 *
 * <p>Assumptions: queue-metadata confidentiality is asserted in
 * {@link OutboxMetadataConfidentialityTest} rather than duplicated here, so that one class owns the
 * question of which values may appear outside the encrypted body.</p>
 */
class OutboxPublisherTest {

    /**
     * A batch size the publisher accepts, matching the value the deployment configures.
     */
    private static final int VALID_BATCH_SIZE = 25;

    /**
     * A polling interval the publisher accepts, matching the value the deployment configures.
     */
    private static final long VALID_POLL_INTERVAL_MILLIS = 1_000L;

    /**
     * The one-hour interval the test profile configures, asserted acceptable because the ceiling is
     * inclusive.
     *
     * <p>Assumptions: this exact number appears in {@code application-test.yml}, where it exists to keep
     * the scheduled drain from firing during a test that drives the publisher directly. A ceiling that
     * excluded it would break that profile rather than catch a mistake, so the boundary is asserted here
     * and not merely documented.</p>
     */
    private static final long TEST_PROFILE_POLL_INTERVAL_MILLIS = 3_600_000L;

    /**
     * A retention window the publisher accepts, matching the value the deployment configures.
     */
    private static final int VALID_RETENTION_DAYS = 7;

    /** The instant the publisher's clock is fixed at. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /** The reply destination every row in the behavioural cases names. */
    private static final String REPLY_QUEUE = "https://sqs.eu-west-1.amazonaws.com/1/reply.fifo";

    /**
     * A SECOND reply destination, so that per-message routing can be told apart from a static one.
     *
     * <p>Assumptions: one destination cannot distinguish a publisher that reads each row's recorded
     * destination from one that sends everything to a single configured queue, because both would pass.
     * Two destinations in one group are also realistic rather than contrived: the same card can be
     * authorised through two acquirers, each naming its own reply queue.</p>
     */
    private static final String OTHER_REPLY_QUEUE =
            "https://sqs.eu-west-1.amazonaws.com/1/other-reply.fifo";

    /** The primary account number the rows in the behavioural cases answer for. */
    private static final String CARD_NUM = "4111111111111111";

    /** The acquirer's transaction identifier of the first reply. */
    private static final String FIRST_TRANSACTION_ID = "TX0000000000001";

    /** The acquirer's transaction identifier of the second reply for the same card. */
    private static final String SECOND_TRANSACTION_ID = "TX0000000000002";

    /**
     * The tokeniser the queue identities on the rows under test are derived with.
     *
     * <p>Assumptions: test-only key material at the tokeniser's minimum length, and a fixed pattern
     * rather than a random one so that a token asserted here is reproducible from the source alone. The
     * production key arrives from the secret store through {@code config/MessagingIdentityConfig.java}.
     * </p>
     */
    private static final OpaqueIdentifier TOKENISER = new OpaqueIdentifier(
            "carddemo-authorization-test-key!".repeat(2).getBytes(StandardCharsets.UTF_8));

    /** The outbox repository mock. */
    private OutboxRepository outbox;

    /** The queue client mock. */
    private SqsClient sqs;

    /** The clock the publisher would read publication instants from. */
    private Clock clock;

    /** The publisher the behavioural cases drive, built over the configured values. */
    private OutboxPublisher publisher;

    /**
     * Builds fresh collaborators for each case.
     */
    @BeforeEach
    void setUp() {
        this.outbox = mock(OutboxRepository.class);
        this.sqs = mock(SqsClient.class);
        this.clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        // WHY : Assumptions: the shared publisher is CONSTRUCTED here but no collaborator is stubbed here.
        //       Construction touches neither mock -- it only validates its numbers -- so the cases that
        //       assert no interaction remain able to do so, and each behavioural case states its own
        //       stubbing where the behaviour it asserts can be read beside it.
        this.publisher = publisherWith(VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS);
    }

    /**
     * The configured values the deployment supplies are accepted, and the boundaries with them.
     *
     * <p>Assumptions: the accepted cases are asserted in the same class as the refused ones, because a
     * bound is only correct if it admits the values in use. The two production values and the three
     * boundary values -- the smallest batch, the largest batch and the test profile's interval -- are what
     * the refusals below must not have swept up.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the configured and boundary values are accepted")
    void theConfiguredAndBoundaryValuesAreAccepted() {
        assertDoesNotThrow(() -> publisherWith(VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS));
        assertDoesNotThrow(() -> publisherWith(1, VALID_POLL_INTERVAL_MILLIS));
        assertDoesNotThrow(() -> publisherWith(500, VALID_POLL_INTERVAL_MILLIS));
        assertDoesNotThrow(() -> publisherWith(VALID_BATCH_SIZE, 50L));
        assertDoesNotThrow(
                () -> publisherWith(VALID_BATCH_SIZE, TEST_PROFILE_POLL_INTERVAL_MILLIS));
    }

    /**
     * A batch size one pass must not claim is refused, at either end.
     *
     * <p>Assumptions: the two ends fail differently and both are asserted. A non-positive size reaches the
     * repository as a {@code LIMIT} the database rejects, so without this refusal the fault recurs once
     * per poll interval on a scheduled thread and never stops the instance. An enormous size is accepted
     * by the database and is worse for it: one drain runs in a single transaction that row-locks every row
     * it claimed, so the pass would lock the whole unpublished backlog and keep a second publisher
     * instance off all of it.</p>
     *
     * <p>Assumptions: the message is asserted to name the PROPERTY rather than merely to exist, because a
     * start-up failure is read by an operator who has to find the value they mis-set, and the property name
     * is what they search for.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-positive or oversized batch size is refused at construction")
    void anUnusableBatchSizeIsRefused() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> publisherWith(0, VALID_POLL_INTERVAL_MILLIS));
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> publisherWith(-1, VALID_POLL_INTERVAL_MILLIS));
        IllegalArgumentException oversized = assertThrows(IllegalArgumentException.class,
                () -> publisherWith(501, VALID_POLL_INTERVAL_MILLIS));

        assertTrue(zero.getMessage().contains("carddemo.messaging.outbox-batch-size"),
                "the refusal names the property an operator has to correct");
        assertTrue(negative.getMessage().contains("must be positive"),
                "the refusal states the bound rather than only that the value was wrong");
        assertTrue(oversized.getMessage().contains("500"),
                "the refusal states the ceiling the value exceeded");
        verifyNoInteractions(this.outbox, this.sqs);
    }

    /**
     * A polling interval that would busy-loop the database or strand a committed reply is refused.
     *
     * <p>Assumptions: the floor is not merely a positive test, because this value is the delay BETWEEN
     * passes and every pass opens a transaction and issues a locking query. An interval of one millisecond
     * turns the drain into a busy loop whose only effect is to consume the connection budget the deciding
     * path needs.</p>
     *
     * <p>Assumptions: the ceiling matters for the opposite reason. This interval is the upper bound on how
     * long a committed reply waits before it is published, so a value beyond an hour leaves a decided
     * authorization unanswered for longer than any requester waits -- a silent functional outage rather
     * than a visible misconfiguration.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a polling interval below the floor or above the ceiling is refused at construction")
    void anUnusablePollIntervalIsRefused() {
        IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class,
                () -> publisherWith(VALID_BATCH_SIZE, 1L));
        IllegalArgumentException nonPositive = assertThrows(IllegalArgumentException.class,
                () -> publisherWith(VALID_BATCH_SIZE, 0L));
        IllegalArgumentException tooSlow = assertThrows(IllegalArgumentException.class,
                () -> publisherWith(VALID_BATCH_SIZE, TEST_PROFILE_POLL_INTERVAL_MILLIS + 1L));

        assertTrue(tooFast.getMessage().contains("carddemo.messaging.outbox-poll-interval-ms"),
                "the refusal names the property an operator has to correct");
        assertTrue(nonPositive.getMessage().contains("50"),
                "the refusal states the floor the value fell below");
        assertTrue(tooSlow.getMessage().contains("3600000"),
                "the refusal states the ceiling the value exceeded");
        verifyNoInteractions(this.outbox, this.sqs);
    }

    /**
     * Each collaborator is required, and the refusal names which one was absent.
     *
     * <p>Assumptions: naming the parameter is the point. These three arrive from the container, so an
     * absent one means a bean was not defined, and a bare failure with no name leaves an operator to
     * discover which of the three by elimination.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent repository, queue client or clock is refused by name")
    void anAbsentCollaboratorIsRefusedByName() {
        NullPointerException noOutbox = assertThrows(NullPointerException.class,
                () -> new OutboxPublisher(null, this.sqs, this.clock, VALID_BATCH_SIZE,
                        VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS));
        NullPointerException noSqs = assertThrows(NullPointerException.class,
                () -> new OutboxPublisher(this.outbox, null, this.clock, VALID_BATCH_SIZE,
                        VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS));
        NullPointerException noClock = assertThrows(NullPointerException.class,
                () -> new OutboxPublisher(this.outbox, this.sqs, null, VALID_BATCH_SIZE,
                        VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS));

        assertTrue(noOutbox.getMessage().contains("outbox"), "the refusal names the outbox repository");
        assertTrue(noSqs.getMessage().contains("sqs"), "the refusal names the queue client");
        assertTrue(noClock.getMessage().contains("clock"), "the refusal names the clock");
    }

    /**
     * A group whose head fails to send does not advance, so a newer reply cannot overtake an older one.
     *
     * <p>Assumptions: this is the ordering guarantee stated as an executable case. The queue orders
     * messages within a group only after it has ACCEPTED them, so a newer reply sent while an older one
     * is still unsent is delivered first and the two answers for one card arrive reversed. The case
     * asserts the negative -- that the follower is never claimed and nothing is sent for it -- because
     * that is the only observable form the guarantee has at this boundary.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed head blocks its own group and never sends the reply behind it")
    void aFailedHeadBlocksItsGroup() {
        AuthReplyOutbox head = rowFor(1L, FIRST_TRANSACTION_ID, null);
        when(this.outbox.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(head));
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new IllegalStateException("queue unreachable"));

        assertThat(this.publisher.drain()).isZero();

        verify(this.outbox, never()).claimGroupFollowers(anyString(), anyLong(), anyInt());
        verify(this.sqs).sendMessage(any(SendMessageRequest.class));
        assertThat(head.getPublishedAt()).isNull();
        assertThat(head.getAttempts()).isEqualTo((short) 1);
    }

    /**
     * A group with a backlog drains in one pass, in ascending identity order.
     *
     * <p>Assumptions: the follow-on claim is what keeps per-group head-of-line claiming from reducing
     * throughput to one reply per group per poll interval, and the ORDER of the two send calls is
     * asserted rather than their count, because a pass that sent both in the wrong order would satisfy a
     * count.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a group with a backlog is published in ascending identity order in one pass")
    void aGroupWithABacklogDrainsInOrder() {
        AuthReplyOutbox head = rowFor(1L, FIRST_TRANSACTION_ID, null);
        AuthReplyOutbox follower = rowFor(2L, SECOND_TRANSACTION_ID, null);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m1").build());
        when(this.outbox.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(head));
        when(this.outbox.claimGroupFollowers(eq(head.getOrderGroupToken()), eq(1L), anyInt()))
                .thenReturn(List.of(follower));
        when(this.outbox.claimGroupFollowers(eq(head.getOrderGroupToken()), eq(2L), anyInt()))
                .thenReturn(List.of());

        assertThat(this.publisher.drain()).isEqualTo(2);

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(sent.capture());
        List<String> bodies = new ArrayList<>();
        sent.getAllValues().forEach(request -> bodies.add(request.messageBody()));
        assertThat(bodies.get(0)).contains(FIRST_TRANSACTION_ID);
        assertThat(bodies.get(1)).contains(SECOND_TRANSACTION_ID);
        assertThat(head.getPublishedAt()).isNotNull();
        assertThat(follower.getPublishedAt()).isNotNull();
    }

    /**
     * Each reply is addressed to the destination its OWN row recorded, not to one shared queue.
     *
     * <p>Assumptions: the reference program takes the destination from the inbound request's reply-to
     * field, saved at {@code COPAUA0C.cbl} L413 to L414 and moved into the put's object name at L742, and
     * it uses {@code MQPUT1} at L758 -- open, put and close per message -- precisely so that each reply
     * can go somewhere different without a pre-opened handle. A publisher that read one configured queue
     * instead would answer the wrong caller whenever two requesters were in flight, and that is a fault
     * no single-destination test can see, which is why two are used here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("each reply is routed to the destination recorded on its own row")
    void eachReplyGoesToTheDestinationItsOwnRowRecords() {
        AuthReplyOutbox head = rowFor(1L, FIRST_TRANSACTION_ID, null);
        AuthReplyOutbox follower = rowRoutedTo(2L, SECOND_TRANSACTION_ID, OTHER_REPLY_QUEUE);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m1").build());
        when(this.outbox.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(head));
        when(this.outbox.claimGroupFollowers(eq(head.getOrderGroupToken()), eq(1L), anyInt()))
                .thenReturn(List.of(follower));
        when(this.outbox.claimGroupFollowers(eq(head.getOrderGroupToken()), eq(2L), anyInt()))
                .thenReturn(List.of());

        assertThat(this.publisher.drain()).isEqualTo(2);

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(sent.capture());
        assertThat(sent.getAllValues().get(0).queueUrl()).isEqualTo(head.getReplyQueueUrl());
        assertThat(sent.getAllValues().get(1).queueUrl()).isEqualTo(follower.getReplyQueueUrl());
        // WHY : Assumptions: the two destinations are asserted DIFFERENT as well as individually
        // correct, because if the helper ever routed both rows to one queue the pair of equality
        // assertions above would still pass while proving nothing about per-message routing.
        assertThat(head.getReplyQueueUrl()).isNotEqualTo(follower.getReplyQueueUrl());
    }

    /**
     * The stored payload reaches the queue byte-identically, neither re-encoded nor padded nor trimmed.
     *
     * <p>Assumptions: the transmitted body is compared to the row's OWN stored payload rather than to any
     * expected width, because the publisher treats the payload as opaque and a test that asserted a
     * number here would import the very wire-length question the publisher deliberately does not hold an
     * opinion on. Comparing the raw bytes rather than the strings is what makes the claim byte-identity:
     * a re-encode that preserved the characters but changed the encoding would pass a string comparison.
     * </p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the stored payload is transmitted byte-identically")
    void thePayloadReachesTheQueueByteIdentically() {
        AuthReplyOutbox row = rowFor(1L, FIRST_TRANSACTION_ID, null);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m1").build());
        when(this.outbox.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(row));
        when(this.outbox.claimGroupFollowers(anyString(), anyLong(), anyInt()))
                .thenReturn(List.of());

        assertThat(this.publisher.drain()).isEqualTo(1);

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs).sendMessage(sent.capture());
        assertThat(sent.getValue().messageBody().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(row.getPayload().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The retention sweep deletes by a cut-off that is the configured window behind the clock.
     *
     * <p>Assumptions: the cut-off is asserted rather than the delete count, because the safety property
     * of this sweep is which rows it can select. The repository statement itself requires a publication
     * instant to be present, so a pending row is unselectable by construction; what remains to be proved
     * here is that the publisher asks for the configured age and not for "everything".</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("retention sweeps published replies older than the configured window")
    void retentionSweepsPublishedRepliesOnly() {
        when(this.outbox.deletePublishedBefore(any(LocalDateTime.class))).thenReturn(3);

        assertThat(this.publisher.purgePublished()).isEqualTo(3);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(this.outbox).deletePublishedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime
                .ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusDays(VALID_RETENTION_DAYS));
    }

    /**
     * An expired reply is retired unsent and the reason it was retired survives on its row.
     *
     * <p>Assumptions: the ordering of the two calls the publisher makes here is load-bearing and is the
     * whole subject of this case. Marking a row published RESETS its diagnostic column, which is right for
     * a reply that finally succeeded after failing, so a retirement that recorded its reason before
     * marking would leave the column empty and an operator with no way to tell a retired reply from an
     * ordinary one. Asserting the reason rather than only the publication instant is therefore what pins
     * the order.</p>
     *
     * <p>Assumptions: the row is asserted to be marked published even though nothing was sent, because
     * that is what removes it from the pending index the claim reads -- and the return value is asserted
     * to be zero in the same case, because a retired reply must not be counted as one that reached the
     * queue.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an expired reply is retired unsent and keeps the reason on its row")
    void anExpiredReplyIsRetiredWithItsReason() {
        LocalDateTime alreadyPast =
                LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusSeconds(1L);
        AuthReplyOutbox stale = rowFor(1L, FIRST_TRANSACTION_ID, alreadyPast);
        when(this.outbox.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(stale));

        assertThat(this.publisher.drain()).isZero();

        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
        assertThat(stale.isPublished()).isTrue();
        assertThat(stale.getLastError()).isEqualTo("expired before publication");
    }

    /**
     * A transient transport fault is reattempted once and a client-side refusal is not reattempted.
     *
     * <p>Assumptions: the retry budget is asserted through the COUNT of send calls, because that is its
     * only observable form at this boundary. The narrow classification is the subject: a fault the service
     * attributes to itself is worth one further attempt, while a status in the 4xx band is a statement
     * about the request that would be repeated identically, so reattempting it would only delay recording
     * it. This mirrors the reference's own three-status transient set at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L87, which likewise excludes the four
     * data conditions declared beside it.</p>
     *
     * <p>Assumptions: the recovering case asserts the row ends PUBLISHED with no diagnostic, so a
     * successful second attempt is not left looking like a failure; the refused case asserts the row ends
     * pending, so its group stops rather than advancing past an unanswered reply.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a transient transport fault is reattempted once and a 4xx refusal is not")
    void onlyATransientTransportFaultIsReattempted() {
        AuthReplyOutbox recovering = rowFor(1L, FIRST_TRANSACTION_ID, null);
        when(this.outbox.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(recovering));
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("connection reset"))
                .thenReturn(SendMessageResponse.builder().build());

        assertThat(this.publisher.drain()).isEqualTo(1);

        verify(this.sqs, times(2)).sendMessage(any(SendMessageRequest.class));
        assertThat(recovering.isPublished()).isTrue();
        assertThat(recovering.getLastError()).isNull();

        SqsClient refusing = mock(SqsClient.class);
        when(refusing.sendMessage(any(SendMessageRequest.class))).thenThrow(
                AwsServiceException.builder().message("not authorized").statusCode(403).build());
        OutboxRepository singleRow = mock(OutboxRepository.class);
        AuthReplyOutbox refused = rowFor(2L, SECOND_TRANSACTION_ID, null);
        when(singleRow.claimGroupHeads(VALID_BATCH_SIZE)).thenReturn(List.of(refused));
        OutboxPublisher strict = new OutboxPublisher(singleRow, refusing, this.clock,
                VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS);

        assertThat(strict.drain()).isZero();

        verify(refusing, times(1)).sendMessage(any(SendMessageRequest.class));
        assertThat(refused.isPublished()).isFalse();
    }

    /**
     * A non-positive retention window is refused at construction rather than deleting a fresh reply.
     *
     * <p>Assumptions: only the FLOOR is asserted. A long window is a disclosure surface rather than a
     * fault, and an operator lengthening it deliberately is making a judgement the publisher is not in a
     * position to overrule -- whereas a non-positive one is never a judgement at all, because it would
     * delete a reply in the same pass that published it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-positive retention window is refused at construction")
    void aNonPositiveRetentionWindowIsRefused() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new OutboxPublisher(this.outbox, this.sqs, this.clock, VALID_BATCH_SIZE,
                        VALID_POLL_INTERVAL_MILLIS, 0));

        assertTrue(zero.getMessage().contains("carddemo.messaging.outbox-retention-days"),
                "the refusal names the property an operator has to correct");
        verifyNoInteractions(this.outbox, this.sqs);
    }

    /**
     * Builds a publisher over the shared mocks with the configured numbers supplied.
     *
     * @param batchSize the batch size to configure, which may be a value the constructor refuses
     * @param pollIntervalMillis the polling interval to configure, which may be a value the constructor
     *     refuses
     * @return the publisher, when every number was acceptable
     */
    private OutboxPublisher publisherWith(int batchSize, long pollIntervalMillis) {
        return new OutboxPublisher(this.outbox, this.sqs, this.clock, batchSize, pollIntervalMillis,
                VALID_RETENTION_DAYS);
    }

    /**
     * Builds one persistent-shaped outbox row carrying tokenised queue identities.
     *
     * <p>Assumptions: the identity column is assigned reflectively because it is generated by the
     * database and the entity exposes no setter for it -- which is correct for production and leaves a
     * test needing two rows of one group with no other way to give them an order. The alternative,
     * adding a setter, would widen the entity's surface for a test's convenience.</p>
     *
     * @param outboxId the identity to assign
     * @param transactionId the acquirer's transaction identifier this reply answers
     * @param expiresAt when the reply stops being worth sending, or {@code null} for never
     * @return the row, never {@code null}
     */
    private AuthReplyOutbox rowFor(long outboxId, String transactionId, LocalDateTime expiresAt) {
        CsvAuthCodec.AuthReply reply = new CsvAuthCodec.AuthReply(CARD_NUM, transactionId, "104530",
                "00", "0000", Money.of("100.99"));
        AuthReplyOutbox row = new AuthReplyOutbox(REPLY_QUEUE, "corr-1",
                reply.orderGroup(TOKENISER), reply.deduplicationKey(TOKENISER),
                CsvAuthCodec.encodeReply(reply), expiresAt,
                LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        assignIdentity(row, outboxId);
        return row;
    }

    /**
     * Builds a row of the SAME order group as {@link #rowFor} but naming a different destination.
     *
     * <p>Assumptions: the order-group token is derived from the card number, which this helper keeps
     * identical to {@link #rowFor}'s, so the row it returns is claimed as a follower of that group. Only
     * the destination differs, which is what isolates routing as the single variable under test.</p>
     *
     * @param outboxId the identity to assign
     * @param transactionId the acquirer's transaction identifier this reply answers
     * @param replyQueueUrl the destination this row records
     * @return the row, never {@code null}
     */
    private AuthReplyOutbox rowRoutedTo(long outboxId, String transactionId, String replyQueueUrl) {
        CsvAuthCodec.AuthReply reply = new CsvAuthCodec.AuthReply(CARD_NUM, transactionId, "104530",
                "00", "0000", Money.of("100.99"));
        AuthReplyOutbox row = new AuthReplyOutbox(replyQueueUrl, "corr-1",
                reply.orderGroup(TOKENISER), reply.deduplicationKey(TOKENISER),
                CsvAuthCodec.encodeReply(reply), null,
                LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        assignIdentity(row, outboxId);
        return row;
    }

    /**
     * Assigns the database-generated identity of a row under test.
     *
     * @param row the row to assign; must not be {@code null}
     * @param outboxId the identity to assign
     * @throws IllegalStateException if the field cannot be reached, which would mean the entity's
     *     identity member had been renamed and this helper had not been updated with it
     */
    private void assignIdentity(AuthReplyOutbox row, long outboxId) {
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
}
