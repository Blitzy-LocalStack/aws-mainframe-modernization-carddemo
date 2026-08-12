package com.carddemo.authorization.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.repository.OutboxRepository;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins divergence D-5, the transactional outbox, and the transport fidelity of the replies it drains.
 *
 * <p><b>Purpose.</b> This class exercises {@link OutboxPublisher} over mocked collaborators. It asserts
 * three things: that a committed decision yields exactly one publishable reply while a rolled-back one
 * yields none; that each reply reaches the queue addressed, correlated, grouped and labelled from its own
 * row rather than from configuration or from a re-derivation; and that the drain is bounded, repeatable
 * and refuses a configuration it cannot honour. It starts no Spring context, opens no database and
 * reaches no queue, which is what lets it assert the whole branch table of one pass.</p>
 *
 * <p><b>What the publisher is, and the three things it is not.</b> It is a bounded, repeatedly-invocable
 * drain that claims undispatched rows, publishes each and marks it. It is <b>not</b> a codec client: the
 * payload is strictly opaque, so this class deliberately does not import
 * {@code com.carddemo.common.codec.CsvAuthCodec} and builds every payload as a plain character constant.
 * It does <b>not</b> take a pessimistic row lock: the claim is an atomic status transition, so no case
 * below asserts or stubs a lock mode. And it does <b>not</b> publish to an error queue: a failure is
 * recorded on the row and emitted to the structured log, which is why the only queue interaction any case
 * asserts is a send to the requester's own reply destination.</p>
 *
 * <p>Refactoring Rationale: D-5 replaces an unguarded inline put, and what was wrong with it is an
 * ORDER rather than a distance. In {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} the
 * paragraph {@code 5000-PROCESS-AUTH} computes the decision at L459, performs
 * {@code 7100-SEND-RESPONSE} at L461 -- whose put is the program's single {@code CALL 'MQPUT1'} at L758
 * -- and only afterwards performs {@code 8000-WRITE-AUTH-TO-DB} at L464, guarded by
 * {@code IF CARD-FOUND-XREF} at L463 and closed at L465. The one {@code EXEC CICS SYNCPOINT} is reached
 * later still, at L334 to L336, once the paragraph performed at L330 returns and L332 has counted the
 * message. <b>The verified order is decide, put the reply, write to the database, commit.</b> Reading the
 * seam by line distance instead inverts it: L335 precedes L758 in source text, yet L335 executes AFTER
 * L758, so an observation that some number of lines separates them mis-orders the execution.</p>
 *
 * <p>Refactoring Rationale: two distinct failure windows follow from that order and both are what the
 * outbox closes. The first is a PHANTOM REPLY: the put at L461 succeeds, then the write at L464 or the
 * commit at L335 does not, and the requester holds an answer no committed row accounts for. The second is
 * easy to miss and is a LOST REPLY: when the put fails, the branch at L767 to L779 moves {@code 'M004'}
 * into the error location at L768, sets {@code ERR-CRITICAL} at L769 and {@code ERR-MQ} at L770, moves
 * {@code 'FAILED TO PUT ON REPLY MQ'} at L775 and performs {@code 9500-LOG-ERROR} at L778 -- and it sets
 * no exit flag and issues no rollback, so execution falls through, the write at L464 and the commit at
 * L335 both still happen, and the data says a reply was produced when none was. The target writes the
 * reply as a row inside the deciding transaction and publishes it afterwards, so a reply exists for every
 * committed decision and for no uncommitted one.</p>
 *
 * <p>Alternatives Considered: reusing {@code account-service}'s ruling, which correctly has no outbox at
 * all. It must not be transposed here, because the two contexts differ in the baseline itself by three MQ
 * option constants. {@code COPAUA0C.cbl} computes {@code MQGMO-NO-SYNCPOINT} at L389 and
 * {@code MQPMO-NO-SYNCPOINT} at L753, so both halves of its round trip sit OUTSIDE the unit of work and
 * an outbox is required. The sibling inquiry program {@code app/app-vsam-mq/cbl/COACCT01.cbl} composes
 * the same option sets and selects the opposite flag -- {@code MQGMO-SYNCPOINT} at L347 and
 * {@code MQPMO-SYNCPOINT} at L475 and L512 -- so its receive and its puts already stand or fall together
 * and a durable row there would guarantee nothing that is not already guaranteed. Reading either
 * arrangement as an oversight breaks one service or the other.</p>
 *
 * <p>Assumptions: no golden master exists for this path, and that is stated plainly rather than left to
 * be inferred from a green run. Three structural reasons, each independent of the others. The online
 * programs cannot run end to end because there is no CICS runtime on the runner, which
 * {@code tests/README.md} records at lines 83 to 85. The harness compiles only the batch tree, the build
 * step at its section 5.2 line 267 naming {@code app/cbl/} and its section 1.1 accounting for ten of the
 * twelve programs there. And {@code COPAUA0C} issues eight {@code COPY CMQ*} statements, at L149, L152,
 * L155, L158, L161, L164, L167 and L170, while no {@code CMQ}-prefixed copybook exists anywhere in the
 * repository, so that program cannot be compiled by the harness at all. Every citation in this class is
 * therefore read from the reference source directly, and the assertions below are the only oracle this
 * path has.</p>
 *
 * <p>Assumptions: no reference source is edited and none deleted. D-5 is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is the register of record, and the
 * house precedent for recording rather than repairing a baseline defect is already written down:
 * {@code tests/README.md} section 1.1 at lines 50 to 60 documents an unfixable record-key defect in two
 * immutable programs, states that no compiler flag can fix it and that the minimal-change principle
 * forbids editing it, and gives its reason as keeping a runnable claim from hiding a blocked feature,
 * which it calls a financial-enterprise auditability requirement. This class follows that precedent
 * rather than inventing one.</p>
 *
 * <p>Assumptions: AAP Rule T3 (money never leaves fixed point) is honoured here by absence rather than by
 * conversion, and the absence is deliberate. No {@code float}, no {@code double} and no numeric money
 * type appears anywhere in this class, because the publisher never handles an amount as a number: the
 * approved amount lives inside the opaque payload as the 14-character edited item of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} L24, and byte-identical transmission is
 * precisely the proof that no numeric round trip occurred. Asserting the amount survives as characters,
 * with its two decimal places and its positional sign intact, is the strongest form Rule T3 takes at this
 * boundary; the exact-decimal arithmetic and the string rendering on the wire are asserted where they
 * live, in the shared kernel's own money tests.</p>
 *
 * <p>Assumptions: three neighbouring classes own questions this one deliberately does not repeat.
 * {@link OutboxMetadataConfidentialityTest} owns which values may appear OUTSIDE the encrypted body, so
 * the cases here ask the different question of whether each value was taken FROM THE ROW rather than from
 * configuration. {@code OutboxPublisherLifecycleRepositoryIT} owns the claim-send seam against a real
 * engine, including that the claim has committed before the send begins and that one pass advances the
 * attempt counter once. {@code AuthorizationDecisionUnitOfWorkRepositoryIT} owns durable commit and
 * rollback atomicity, and the reference's L461-against-L463 asymmetry, against a real engine. What is
 * left to this class is everything observable from the order and content of the send calls one drain
 * issues.</p>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) governs this file and its validation gate is
 * conjunctive, so a missing docstring and a missing decision rationale each fail on their own. Its ruling
 * here is that every type, every case, the fixture hook and every private helper carries a Javadoc block
 * stating purpose, parameters with their types, return values and exceptions; and that in test code the
 * assertion is the WHAT while the derivation from the reference is the WHY, so no comment below restates
 * an assertion and each non-obvious choice carries one of the rule's four labels. The rule text itself is
 * not reproduced anywhere in this file.</p>
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

    /** The per-pass row budget the cases configure, matching the deployment default. */
    private static final int VALID_MAX_ROWS_PER_DRAIN = 500;

    /** The attempt ceiling the cases configure, matching the deployment default. */
    private static final int VALID_MAX_ATTEMPTS = 10;

    /** The instant the publisher's clock is fixed at. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /** The reply destination most rows in the behavioural cases name. */
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

    /** A SECOND primary account number, so that a pass spanning two ordering groups can be built. */
    private static final String OTHER_CARD_NUM = "4222222222222222";

    /**
     * A THIRD primary account number, so that more groups can be pending than one pass may claim.
     *
     * <p>Assumptions: three groups against a batch of two is the smallest arrangement in which a bound
     * is distinguishable from an absent bound. With two groups a pass that claimed everything and a pass
     * that claimed exactly its batch would both publish two, so the surplus group is what makes the
     * refusal visible.</p>
     */
    private static final String THIRD_CARD_NUM = "4333333333333333";

    /** The acquirer's transaction identifier of the reply belonging to the third card. */
    private static final String THIRD_TRANSACTION_ID = "TX0000000000003";

    /** The acquirer's transaction identifier of the first reply. */
    private static final String FIRST_TRANSACTION_ID = "TX0000000000001";

    /** The acquirer's transaction identifier of the second reply for the same card. */
    private static final String SECOND_TRANSACTION_ID = "TX0000000000002";

    /**
     * The requester's correlation identity, which the reply echoes unaltered.
     *
     * <p>Assumptions: the value is deliberately not derivable from anything else on the row -- it is
     * neither the card number nor the transaction identifier nor the destination -- so an assertion that
     * the published attribute equals it cannot be satisfied by a publisher that regenerated or
     * transformed the identity instead of echoing it.</p>
     */
    private static final String CORRELATION_ID = "corr-7f3a91b0-inbound";

    /**
     * The wire-format label the reference descriptor declares for every reply.
     *
     * <p>Assumptions: the reference moves {@code MQFMT-STRING} into the reply descriptor's format field
     * at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L751, and this is that declaration's
     * migrated spelling. It is stated as a literal here rather than read from the entity, so that a case
     * asserting the published label would fail if the entity's own default were ever changed -- which is
     * the direction of drift worth catching, since a consumer parses the bytes according to this label.
     * </p>
     */
    private static final String EXPECTED_CONTENT_TYPE = "text/csv";

    /**
     * An APPROVED reply body, held and asserted purely as opaque characters.
     *
     * <p>Assumptions: this is shaped like a real reply so that a reader can see which decision it
     * reports, and it is treated as an opaque byte source regardless. It carries the six items of
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} L19 to L24 separated by the delimiter the
     * {@code STRING} at {@code cbl/COPAUA0C.cbl} L722 to L731 emits, including the trailing one after the
     * sixth item. Its approved amount appears here only as CHARACTERS, which is what AAP Rule T3 (money
     * never leaves fixed point) requires of a transport that must not reinterpret it.</p>
     *
     * <p>Trade-offs: no case asserts this constant's LENGTH, and that is a deliberate refusal rather than
     * an omission. Three defensible figures exist -- the copybook item widths sum to 57, the emitter's six
     * delimiters make the payload 63, and L46 declares the length field with an initial value of 1 as a
     * one-based pointer that L756 then reuses as a length, so 64 bytes are transmitted for a 63-byte
     * payload -- and the publisher holds no opinion between them. A length assertion here would adopt one
     * figure and reject a correctly-encoded reply whenever the other applied, which is exactly the
     * coupling the opaque-payload contract exists to prevent.</p>
     */
    private static final String APPROVED_PAYLOAD =
            "4111111111111111,TX0000000000001,A00001,00,0000,        100.99,";

    /**
     * A DECLINED reply body carrying the response code and reason an unresolved card produces.
     *
     * <p>Assumptions: the two codes are the reference's own. {@code cbl/COPAUA0C.cbl} L688 moves
     * {@code '05'} into the response code when the decision declines, and L704 moves {@code '3100'} into
     * the response reason. The identification-code item is blank because a declined authorization was
     * never identified, and the amount is zero for the same reason.</p>
     */
    private static final String DECLINED_PAYLOAD =
            "4111111111111111,TX0000000000001,      ,05,3100,          0.00,";

    /**
     * A body that is deliberately NOT a well-formed reply, used to demonstrate payload opacity.
     *
     * <p>Assumptions: this is the positive form of the opacity claim and it is the whole reason the
     * constant exists. It carries no delimiters, no six items and no edited amount, so a publisher that
     * validated, re-derived, padded, trimmed or length-checked its payload could not transmit it
     * unchanged. Asserting that it arrives byte for byte therefore proves opacity in a way that a
     * well-formed payload never can, because a well-formed payload survives a re-encode.</p>
     */
    private static final String MALFORMED_PAYLOAD = "not-a-reply";

    /**
     * The message identity a broker returns for an accepted send.
     *
     * <p>Assumptions: this is shaped as the transport's own identity -- a version-4 identifier -- rather
     * than as anything this service could have derived, which is the point of asserting it. A value this
     * service could compute would not distinguish a line that read the broker's answer from one that
     * re-rendered its own row.</p>
     */
    private static final String BROKER_MESSAGE_ID = "3f9c1d84-1c2b-4e77-9a05-6de2b31c7f10";

    /**
     * The sequence number a broker returns for an accepted send on an ordered queue.
     *
     * <p>Assumptions: the value is a long decimal string rather than a number, because the transport
     * models it as text and it exceeds the range a signed 64-bit integer would hold. Treating it as a
     * number is the mistake this constant's shape is here to discourage.</p>
     */
    private static final String BROKER_SEQUENCE_NUMBER = "18860000000000000101";

    /** The outbox repository mock, backed by the simulated table below. */
    private OutboxRepository outbox;

    /** The queue client mock. */
    private SqsClient sqs;

    /** The clock the publisher reads publication and retention instants from. */
    private Clock clock;

    /** The publisher the behavioural cases drive, built over the configured values. */
    private OutboxPublisher publisher;

    /**
     * Every row a case has written, standing in for the outbox table.
     *
     * <p>Assumptions: the list IS the table. The publisher claims from it, re-reads from it before
     * writing an outcome, and counts its unpublished rows, so a case creates a row to mean "the deciding
     * transaction committed" and creates none to mean "it rolled back". That is what makes both
     * transaction directions observable without a database.</p>
     */
    private List<AuthReplyOutbox> stored;

    /**
     * The row limit the publisher asked each head claim for, in call order.
     *
     * <p>Assumptions: the bound one pass claims is enforced by the repository statement, so what is
     * observable at this boundary is the limit the publisher REQUESTS. Recording it is therefore the only
     * way a case can assert that a pass is bounded by configuration rather than by the backlog it met.
     * </p>
     */
    private List<Integer> requestedHeadLimits;

    /**
     * Builds fresh collaborators and wires the simulated outbox table for each case.
     *
     * <p>Assumptions: the repository mock is backed by a real in-memory list rather than by per-case
     * canned answers, and that choice is what makes both transaction directions and repeat invocation
     * observable at all. A canned answer returns the same rows however often it is asked, so a second
     * drain would republish a reply the first drain had already sent and idempotence could not be
     * asserted. Answering from the list means publication state accumulates exactly as it does in the
     * schema: a row that has been marked published is no longer a candidate, because the claim filters on
     * the same condition the migration's partial index does.</p>
     *
     * <p>Trade-offs: the simulation models publication state, abandonment and backoff eligibility, and
     * nothing else. It does not model the claim's own attempt increment, its lease or its
     * comparison-and-swap, because those are properties of a statement rather than of the publisher, and
     * they are asserted against a real engine by {@code OutboxRepositoryIT} and
     * {@code OutboxPublisherLifecycleRepositoryIT}. Modelling them here would restate a statement this
     * class does not own and would drift from it silently.</p>
     *
     * <p>Assumptions: no collaborator is stubbed for the successful send path. The publisher discards the
     * transport's own response, so the mock's default null answer is indistinguishable from a real
     * acceptance, and leaving the queue client unstubbed keeps the construction cases able to assert that
     * it was never reached.</p>
     */
    @BeforeEach
    void setUp() {
        this.outbox = mock(OutboxRepository.class);
        this.sqs = mock(SqsClient.class);
        this.clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        this.stored = new ArrayList<>();
        this.requestedHeadLimits = new ArrayList<>();
        // WHY : Assumptions: the re-read the publisher performs before it writes an outcome is answered
        //       from the rows this case itself wrote, keyed by identity. That is what makes the split
        //       between the claim, the send and the OUTCOME observable: the publisher does not mutate the
        //       instance it was handed, it re-reads the row inside a second short transaction and writes
        //       there, so a case asserting on publication has to see the same object come back.
        //       Alternatives Considered: answering every lookup with the argument wrapped in an Optional
        //       regardless of identity; rejected because it would satisfy a lookup for a row no case ever
        //       wrote, hiding a publisher that transitioned the wrong identity.
        when(this.outbox.findById(anyLong())).thenAnswer(invocation -> {
            Long wanted = invocation.getArgument(0);
            return this.stored.stream().filter(row -> wanted.equals(row.getOutboxId())).findFirst();
        });
        when(this.outbox.claimGroupHeads(anyInt(), any(), any(), anyInt())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(0);
            this.requestedHeadLimits.add(limit);
            return claimHeads(limit);
        });
        when(this.outbox.claimGroupFollowers(anyString(), anyLong(), anyInt(), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    String orderGroupId = invocation.getArgument(0);
                    long afterOutboxId = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    return claimFollowers(orderGroupId, afterOutboxId, limit);
                });
        when(this.outbox.countByPublishedAtIsNull()).thenAnswer(invocation -> this.stored.stream()
                .filter(row -> row.getPublishedAt() == null).count());
        // WHY : Assumptions: the shared publisher is CONSTRUCTED here and no collaborator is stubbed by
        //       the construction itself. Construction only validates its numbers, so the cases that
        //       assert no interaction remain able to do so.
        this.publisher = publisherWith(VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS);
    }

    /**
     * A committed decision leaves exactly one publishable reply, and one drain publishes exactly it.
     *
     * <p>Refactoring Rationale: this is the target half of divergence D-5. The reference publishes the
     * reply from {@code 7100-SEND-RESPONSE}, performed at {@code COPAUA0C.cbl} L461, whose put is the
     * {@code CALL 'MQPUT1'} at L758 -- and it does so BEFORE the conditional write at L463 to L465 and
     * before the single {@code EXEC CICS SYNCPOINT} at L334 to L336. Because L753 computes
     * {@code MQPMO-NO-SYNCPOINT}, that put is outside the unit of work and cannot be withdrawn, so a
     * write or a commit that then fails leaves a PHANTOM REPLY the requester already holds. The target
     * writes the reply as a row inside the deciding transaction instead, which is why a committed
     * decision is exactly one publishable reply here.</p>
     *
     * <p>Assumptions: the count is asserted as EXACTLY one rather than at least one, and the distinction
     * is the reference's rather than a stylistic preference. The reference commits once per message -- one
     * {@code SYNCPOINT} at L334 to L336, reached after L332 has counted that message -- so one committed
     * decision is one unit of work and can account for no more and no fewer than one answer. An
     * at-least-one assertion would pass for a publisher that duplicated the reply, and a duplicate is the
     * one outcome the deduplication identity exists to suppress rather than to license.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a committed decision yields exactly one publishable reply")
    void aCommittedDecisionYieldsExactlyOnePublishableReply() {
        AuthReplyOutbox committed = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.outbox.countByPublishedAtIsNull())
                .as("the deciding transaction committed one reply row alongside its decision")
                .isOne();
        assertThat(this.publisher.drain()).isOne();

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(1)).sendMessage(sent.capture());
        assertThat(sent.getValue().messageDeduplicationId())
                .as("one authorization answers under one deduplication identity")
                .isEqualTo(FIRST_TRANSACTION_ID);
        assertThat(committed.getPublishedAt()).isNotNull();
        assertThat(this.outbox.countByPublishedAtIsNull()).isZero();
    }

    /**
     * A rolled-back decision leaves no publishable reply, so nothing whatever reaches the queue.
     *
     * <p>Refactoring Rationale: this is the direction the reference cannot express, and it is the second
     * of D-5's two failure windows read from the other side. The reference's own rollback-shaped outcome
     * is a LOST REPLY: when the put fails, the branch at {@code COPAUA0C.cbl} L767 to L779 moves
     * {@code 'M004'} into the error location at L768, sets {@code ERR-CRITICAL} at L769 and
     * {@code ERR-MQ} at L770, moves {@code 'FAILED TO PUT ON REPLY MQ'} at L775 and performs
     * {@code 9500-LOG-ERROR} at L778 -- setting no exit flag and issuing no rollback. Execution therefore
     * FALLS THROUGH: the write at L464 and the commit at L335 both still happen, and the persisted
     * decision claims an answer that was never sent. Here the reply row is written inside the deciding
     * transaction, so a rollback removes the answer with the decision and the two cannot disagree in
     * either direction.</p>
     *
     * <p>Assumptions: the absence of a row IS the rolled-back decision, which is what makes this case
     * expressible without a database. A reply row can only exist because the transaction that wrote it
     * committed, so a case that writes none has said everything a rollback says to a publisher. That the
     * row and the decision genuinely share one durable fate is asserted against a real engine by
     * {@code AuthorizationDecisionUnitOfWorkRepositoryIT}, which is the only place it can be.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a rolled-back decision yields no publishable reply at all")
    void aRolledBackDecisionYieldsNoPublishableReply() {
        assertThat(this.outbox.countByPublishedAtIsNull())
                .as("a rollback took the reply row with the decision that wrote it")
                .isZero();

        assertThat(this.publisher.drain()).isZero();

        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * A second drain over an already-published reply publishes nothing and completes normally.
     *
     * <p>Assumptions: repeat invocation is a first-class property rather than an edge case, because the
     * drain is reached from a fixed-delay schedule and therefore runs again over the same table every
     * interval for the life of the process. A publisher that republished a completed row would emit one
     * duplicate per interval indefinitely, and the queue's deduplication interval is five minutes, so
     * only the first few would be suppressed.</p>
     *
     * <p>Assumptions: the second pass is asserted not to THROW as well as not to publish. Its claim
     * legitimately comes back empty, which the publisher must read as an idle table rather than as a
     * fault, since an empty claim is the ordinary state of a drain that is keeping up.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a second drain over an already-published reply publishes nothing and does not fail")
    void aSecondDrainOverAPublishedReplyPublishesNothing() {
        approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();
        assertThat(assertDoesNotThrow(() -> this.publisher.drain())).isZero();

        verify(this.sqs, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * One drain claims at most the configured batch size, however many groups are pending.
     *
     * <p>Assumptions: the bound is asserted through the limit the publisher REQUESTS as well as through
     * the sends it makes, because the limit is enforced by the repository statement and the request is the
     * only part of that contract observable here. Asserting only the send count would pass for a publisher
     * that claimed the whole backlog and happened to meet a small one.</p>
     *
     * <p>Assumptions: the surplus group is deferred rather than dropped, which the follow-up pass asserts.
     * A bound that lost work would be a worse fault than an unbounded pass, so both halves are stated: the
     * pass stops at its batch, and the next pass takes what it left.</p>
     *
     * <p>Trade-offs: the figure the reference bounds one invocation at is five hundred, declared
     * {@code 05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500.} at {@code COPAUA0C.cbl} L40 and
     * enforced at L339. This case configures two instead, because the property under test is that A bound
     * is honoured rather than that a particular number is, and building five hundred rows to demonstrate
     * it would trade a legible case for an arithmetically identical one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("one drain claims at most the configured batch size and defers the rest")
    void oneDrainClaimsAtMostTheConfiguredBatchSize() {
        OutboxPublisher bounded = publisherWith(2, VALID_POLL_INTERVAL_MILLIS);
        approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        approvedRow(2L, OTHER_CARD_NUM, SECOND_TRANSACTION_ID);
        approvedRow(3L, THIRD_CARD_NUM, THIRD_TRANSACTION_ID);

        assertThat(bounded.drain()).isEqualTo(2);

        verify(this.sqs, times(2)).sendMessage(any(SendMessageRequest.class));
        assertThat(this.requestedHeadLimits)
                .as("the pass asked the claim for its configured batch and not for the backlog")
                .containsExactly(2);
        assertThat(bounded.drain())
                .as("the group the bound deferred is taken by the following pass")
                .isOne();
    }


    /**
     * A card-not-found decline is published like any other reply, though no decision record exists.
     *
     * <p>Assumptions: the condition this fixture exercises is {@code CARD-NFOUND-XREF}, and naming it
     * matters because the reason code does not identify it. {@code COPAUA0C.cbl} L701 to L704 collapses
     * THREE distinct conditions onto the single reason {@code '3100'} -- {@code CARD-NFOUND-XREF} at L701,
     * {@code NFOUND-ACCT-IN-MSTR} at L702 and {@code NFOUND-CUST-IN-MSTR} at L703 -- so reading
     * {@code '3100'} as "card not found" alone would mistake one of three antecedents for the whole set.
     * The accompanying response code is {@code '05'}, moved at L688 whenever the decision declines.</p>
     *
     * <p>Refactoring Rationale: this path is the one place the reference's unconditional reply is NOT a
     * phantom, and the reason is that nothing is written for it to disagree with. L463's
     * {@code IF CARD-FOUND-XREF} gates the database write at L464 only; the reply performed at L461 is
     * unconditional. So an unresolved card is answered and recorded nowhere, and since there is no write
     * to fail there is no committed decision for the answer to contradict. The target preserves exactly
     * that: {@code AuthorizationRequestListener} persists the summary and detail rows only inside
     * {@code if (xref.isPresent())} and then calls {@code enqueueReply} unconditionally at its line 721,
     * outside that branch. The reply therefore becomes an outbox row on this path as on any other.</p>
     *
     * <p>Trade-offs: this case asserts that the decline IS published, rather than asserting an absent row.
     * An earlier reading of the specification expected the outbox to be empty here, inferring from L463
     * that nothing at all is written. That inference is wrong in the target and would be a functional
     * regression if adopted: it would leave a requester with no answer whatever to a request the reference
     * always answered. The publisher is transport, so it holds no notion of a decision record and cannot
     * suppress a reply for want of one -- which is what this case pins, by driving a decline through the
     * identical assertions an approval passes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a card-not-found decline is published like any other reply")
    void aCardNotFoundDeclineIsStillPublished() {
        AuthReplyOutbox decline = declinedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();

        SendMessageRequest sent = sentRequests(1).get(0);
        assertThat(sent.messageBody())
                .as("the decline reaches the requester carrying the codes the reference sets")
                .isEqualTo(DECLINED_PAYLOAD)
                .contains(",05,")
                .contains(",3100,");
        assertThat(sent.queueUrl()).isEqualTo(decline.getReplyQueueUrl());
        assertThat(sent.messageGroupId()).isEqualTo(CARD_NUM);
        assertThat(decline.getPublishedAt()).isNotNull();
    }

    /**
     * Each reply is addressed to the destination its OWN row recorded, not to one shared queue.
     *
     * <p>Assumptions: per-message routing is the reference's mechanism, not an embellishment of it.
     * {@code COPAUA0C.cbl} saves the inbound reply-to queue at L413 to L414, sets the reply object type at
     * L741 and moves that saved name into the object name at L742, and its put is {@code MQPUT1} at L758
     * -- an open, put and close in one operation -- which is precisely why a per-request destination needs
     * no pre-opened handle there. A send request naming its own queue reproduces that per-message
     * selection here, which is why the destination travels on the row rather than in configuration.</p>
     *
     * <p>Assumptions: two destinations are used because one cannot distinguish the two implementations
     * that matter. A publisher reading each row's destination and a publisher sending everything to a
     * single configured queue both pass a single-destination case, and the second would answer the wrong
     * caller whenever two requesters were in flight.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("each reply is routed to the destination recorded on its own row")
    void eachReplyGoesToTheDestinationItsOwnRowRecords() {
        AuthReplyOutbox first = rowWith(1L, CARD_NUM, FIRST_TRANSACTION_ID, REPLY_QUEUE,
                CORRELATION_ID, APPROVED_PAYLOAD, liveDeadline());
        AuthReplyOutbox second = rowWith(2L, CARD_NUM, SECOND_TRANSACTION_ID, OTHER_REPLY_QUEUE,
                CORRELATION_ID, APPROVED_PAYLOAD, liveDeadline());

        assertThat(this.publisher.drain()).isEqualTo(2);

        List<SendMessageRequest> sent = sentRequests(2);
        assertThat(sent.get(0).queueUrl()).isEqualTo(first.getReplyQueueUrl());
        assertThat(sent.get(1).queueUrl()).isEqualTo(second.getReplyQueueUrl());
        // WHY : Assumptions: the two destinations are asserted DIFFERENT as well as individually correct.
        //       Were the fixture ever to route both rows to one queue, the pair of equality assertions
        //       above would still pass while proving nothing at all about per-message routing.
        assertThat(first.getReplyQueueUrl()).isNotEqualTo(second.getReplyQueueUrl());
    }

    /**
     * The requester's correlation identity is echoed onto the reply exactly as it arrived.
     *
     * <p>Assumptions: the reference captures the inbound identity at {@code COPAUA0C.cbl} L411 to L412 and
     * moves that saved value onto the reply descriptor at L745, having pre-set {@code MQMI-NONE} and
     * {@code MQCI-NONE} on the get at L395 to L396 so that it filters on neither. The identity is the only
     * thing pairing an answer with its question, so it is echoed rather than regenerated or derived.</p>
     *
     * <p>Assumptions: the fixture's identity is deliberately unrelated to every other value on the row, so
     * this assertion cannot be satisfied by accident. A publisher that substituted the transaction
     * identifier, the card number or a freshly generated identity would fail it, whereas an identity
     * derived from any of those would pass a weaker assertion that merely required the attribute to be
     * present.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the requester's correlation identity is echoed onto the reply unchanged")
    void theCorrelationIdentityIsEchoedUnchanged() {
        AuthReplyOutbox row = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();

        Map<String, MessageAttributeValue> attributes = sentRequests(1).get(0).messageAttributes();
        assertThat(attributes.get(OutboxPublisher.ATTRIBUTE_CORRELATION_ID).stringValue())
                .as("an answer a requester cannot pair with its question is an unanswered request")
                .isEqualTo(row.getCorrelationId())
                .isEqualTo(CORRELATION_ID);
    }

    /**
     * The published reply is terminal: it targets the inbound destination and advertises none of its own.
     *
     * <p>Assumptions: this asymmetry is explicit in the reference and is easy to invert. The reply
     * descriptor asks for a FRESH message identifier with {@code MQMI-NONE} at {@code COPAUA0C.cbl} L746,
     * and then BLANKS its own reply-to fields at L747 and L748, moving {@code SPACES} into
     * {@code MQMD-REPLYTOQ} and {@code MQMD-REPLYTOQMGR}. A reply therefore carries the inbound
     * correlation identity and goes to the inbound destination, while inviting no answer of its own -- the
     * conversation ends with it.</p>
     *
     * <p>Assumptions: terminality is asserted in the only two forms available on a send request. The
     * attribute set is asserted to hold EXACTLY the three attributes the publisher owns, so a fourth
     * naming a reply destination could not be added unnoticed; and no attribute value is allowed to equal
     * a reply destination, so the inbound address cannot be re-advertised under some other attribute name.
     * The reference's remaining descriptor fields have no counterpart to assert: L744's message type is
     * not modelled by the target transport, the fresh identifier of L746 is assigned by the transport
     * itself, and L749's non-persistent delivery is provisioned as queue retention rather than set per
     * send.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the published reply is terminal and advertises no reply-to of its own")
    void thePublishedReplyIsTerminal() {
        AuthReplyOutbox row = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();

        SendMessageRequest sent = sentRequests(1).get(0);
        assertThat(sent.queueUrl()).isEqualTo(row.getReplyQueueUrl());
        assertThat(sent.messageAttributes())
                .as("a fourth attribute naming a reply destination would make the reply conversational")
                .containsOnlyKeys(OutboxPublisher.ATTRIBUTE_CONTENT_TYPE,
                        OutboxPublisher.ATTRIBUTE_CORRELATION_ID,
                        OutboxPublisher.ATTRIBUTE_EXPIRES_AT);
        assertThat(sent.messageAttributes().values())
                .extracting(MessageAttributeValue::stringValue)
                .as("the inbound destination is the target of the reply, never a value carried on it")
                .doesNotContain(REPLY_QUEUE, OTHER_REPLY_QUEUE);
    }

    /**
     * The reply is labelled with the delimited-text wire format the reference descriptor declares.
     *
     * <p>Assumptions: the reference moves {@code MQFMT-STRING} into the reply descriptor's format field at
     * {@code COPAUA0C.cbl} L751, and because the payload is declared as a string format the item order and
     * the delimiter ARE the interface. The migrated label is what lets a consumer tell the delimited reply
     * from the structured envelope offered additively beside it without inspecting the bytes, so a
     * consumer that read this label would parse the payload the wrong way if it ever disagreed with the
     * body.</p>
     *
     * <p>Assumptions: the label is asserted against a literal declared in this class rather than read back
     * from the entity's own default. Reading it from the entity would make the assertion true by
     * construction and could not fail, whereas a literal fails if the default is ever changed -- which is
     * the direction of drift worth catching.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply is labelled with the delimited-text wire format the descriptor declares")
    void theReplyCarriesTheDeclaredWireFormat() {
        approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();

        Map<String, MessageAttributeValue> attributes = sentRequests(1).get(0).messageAttributes();
        assertThat(attributes.get(OutboxPublisher.ATTRIBUTE_CONTENT_TYPE).stringValue())
                .isEqualTo(EXPECTED_CONTENT_TYPE);
    }

    /**
     * The ordering and deduplication identities are carried from the row, per reply.
     *
     * <p>Alternatives Considered: the two identities are the card number and the acquirer's transaction
     * identifier, and each alternative was weighed. Grouping by CARD preserves the per-card ordering the
     * reference obtains from a single-threaded consumer while leaving unrelated cards free to proceed in
     * parallel; a single group for every message was rejected because it would serialise every card behind
     * one ordering chain. Deduplicating by TRANSACTION gives exactly-once acceptance that is independent
     * of the payload bytes, so a reply re-sent after a failed outcome write is suppressed inside the
     * queue's five-minute deduplication interval; content-based deduplication was rejected because two
     * genuinely distinct replies could hash alike and one would be silently dropped. A standard queue was
     * rejected outright, because it cannot preserve per-card ordering at all.</p>
     *
     * <p>Assumptions: both identities are asserted against the ROW's own accessors rather than only
     * against the literals, because the question here is transport fidelity -- whether the value published
     * is the value committed. Which values may legitimately appear outside the encrypted body is the
     * separate question {@link OutboxMetadataConfidentialityTest} owns, and it is not restated here.</p>
     *
     * <p>Assumptions: two rows of DIFFERENT groups are drained together, so a publisher that read one
     * configured group identity for the whole pass fails. With a single row, any constant would pass.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the ordering and deduplication identities are carried from each row")
    void theQueueIdentitiesAreCarriedFromEachRow() {
        AuthReplyOutbox first = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        AuthReplyOutbox second = approvedRow(2L, OTHER_CARD_NUM, SECOND_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isEqualTo(2);

        List<SendMessageRequest> sent = sentRequests(2);
        assertThat(sent).extracting(SendMessageRequest::messageGroupId)
                .containsExactly(first.getOrderGroupId(), second.getOrderGroupId());
        assertThat(sent).extracting(SendMessageRequest::messageDeduplicationId)
                .containsExactly(first.getDeduplicationId(), second.getDeduplicationId());
        assertThat(first.getOrderGroupId()).isNotEqualTo(second.getOrderGroupId());
    }

    /**
     * A payload that is not a well-formed reply is transmitted byte for byte, unexamined.
     *
     * <p>Assumptions: this is the positive form of the opaque-payload contract, and only a MALFORMED
     * payload can establish it. A well-formed reply survives a re-encode, a re-derivation and a pad to a
     * declared width, so transmitting one unchanged is consistent with a publisher that inspected it. A
     * body carrying no delimiters, no six items and no edited amount survives none of those, so its
     * arriving unchanged proves the publisher neither validated, re-derived, padded, trimmed nor
     * length-checked it. That is why the class under test takes no dependency on the reply codec and why
     * this class imports none either: a publisher able to produce a payload of its own could disagree with
     * the bytes committed inside the deciding transaction, which would reintroduce the very divergence the
     * outbox exists to remove.</p>
     *
     * <p>Trade-offs: the comparison is made on BYTES rather than on characters, and the length asserted is
     * the payload's own rather than any declared width. Comparing strings would pass for a re-encode that
     * preserved the characters and changed the encoding, and asserting a declared width would import the
     * wire-length question the publisher deliberately holds no opinion on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a payload that is not a well-formed reply is transmitted byte for byte")
    void anOpaquePayloadIsTransmittedUnchanged() {
        AuthReplyOutbox row = rowWith(1L, CARD_NUM, FIRST_TRANSACTION_ID, REPLY_QUEUE,
                CORRELATION_ID, MALFORMED_PAYLOAD, liveDeadline());

        assertThat(this.publisher.drain()).isOne();

        String body = sentRequests(1).get(0).messageBody();
        assertThat(body.getBytes(StandardCharsets.UTF_8))
                .as("the publisher is transport and not semantics, so the bytes it was given are the "
                        + "bytes it sends")
                .isEqualTo(row.getPayload().getBytes(StandardCharsets.UTF_8));
        assertThat(body).isEqualTo(MALFORMED_PAYLOAD).hasSameSizeAs(MALFORMED_PAYLOAD);
    }

    /**
     * An approved amount reaches the queue as the characters it was stored as, never renormalised.
     *
     * <p>Assumptions: this is AAP Rule T3 (money never leaves fixed point) asserted in the only form it
     * takes at a transport boundary. The amount is the 14-character edited item of
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} L24, declared {@code PIC +9(10).99}, whose
     * sign position is positional rather than floating -- so a non-negative amount carries a SPACE there
     * and never a plus. Byte-identical transmission of that rendering is what proves no numeric round trip
     * occurred: any parse into a binary floating type and back would renormalise the padding, the sign
     * position or the trailing zero, and any of those three would show here.</p>
     *
     * <p>Assumptions: no {@code float}, {@code double} or numeric money type appears anywhere in this
     * class, because the publisher handles no amount as a number. The shared kernel's architecture rules
     * forbid binary floating point only within its own money package, so this module's money path is NOT
     * covered by inheritance and the discipline has to be kept deliberately here rather than assumed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approved amount reaches the queue as stored characters, never renormalised")
    void theApprovedAmountSurvivesAsCharacters() {
        approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();

        assertThat(sentRequests(1).get(0).messageBody())
                .as("a renormalised amount is a different answer, however close its value")
                .contains("        100.99")
                .isEqualTo(APPROVED_PAYLOAD);
    }


    /**
     * A group whose head fails to send does not advance, so a newer reply cannot overtake an older one.
     *
     * <p>Assumptions: this is the ordering guarantee stated as an executable case. A first-in-first-out
     * queue orders messages within a group only after it has ACCEPTED them, so a newer reply sent while an
     * older one is still unsent is delivered first and the two answers for one card arrive reversed. The
     * case asserts the negative -- that the follower is never claimed and nothing is sent for it -- because
     * that is the only observable form the guarantee has at this boundary.</p>
     *
     * <p>Assumptions: the failure is read from the DIAGNOSTIC and the BACKOFF rather than from the attempt
     * counter. The counter is advanced by the claiming statement and by nothing else, so over a simulated
     * table there is no increment here to observe -- and that is the point, because counting a failure in
     * the publisher as well would double-count every failed publication. The claim's own increment is
     * asserted against a real engine by {@code OutboxRepositoryIT}.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed head blocks its own group and never sends the reply behind it")
    void aFailedHeadBlocksItsGroup() {
        AuthReplyOutbox head = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        approvedRow(2L, CARD_NUM, SECOND_TRANSACTION_ID);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new IllegalStateException("queue unreachable"));

        assertThat(this.publisher.drain()).isZero();

        verify(this.outbox, never())
                .claimGroupFollowers(anyString(), anyLong(), anyInt(), any(), any(), anyInt());
        verify(this.sqs, times(1)).sendMessage(any(SendMessageRequest.class));
        assertThat(head.getPublishedAt()).isNull();
        // WHY : Refactoring Rationale: this asserted EQUALITY with the exception's class name, which is
        //       what the publisher used to persist. The recorded value is now the cause chain's types and
        //       frames, so the assertion is on the type BEING NAMED rather than on it being the whole
        //       string -- and it additionally asserts that no message text was persisted, which is the
        //       property the digest exists to guarantee and the one an equality check on a class name
        //       happened to imply without stating.
        assertThat(head.getLastError())
                .as("the persisted diagnostic names the fault's type and the frames it was raised at")
                .startsWith(IllegalStateException.class.getName() + "@")
                .contains(".java:");
        assertThat(head.getLastError())
                .as("a transport-authored message must not be persisted into a column that outlives the "
                        + "incident, because it can quote the reply it was building")
                .doesNotContain("queue unreachable");
        assertThat(head.getNextAttemptAt())
                .as("a failed reply is deferred to a backoff rather than left immediately eligible")
                .isAfter(fixedNow());
    }

    /**
     * A group with a backlog drains within one pass, in ascending identity order.
     *
     * <p>Assumptions: the follow-on claim is what keeps per-group head-of-line claiming from reducing
     * throughput to one reply per group per poll interval. The ORDER of the two sends is asserted rather
     * than their count, because a pass that sent both in the wrong order would satisfy a count while
     * breaking the single guarantee the ordering group exists to provide.</p>
     *
     * <p>Assumptions: order is read from the deduplication identity rather than from the payload, because
     * that identity is the acquirer's transaction identifier and is therefore the one value that
     * distinguishes two replies of one card without the case having to vary an opaque body.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a group with a backlog is published in ascending identity order in one pass")
    void aGroupWithABacklogDrainsInOrder() {
        AuthReplyOutbox head = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        AuthReplyOutbox follower = approvedRow(2L, CARD_NUM, SECOND_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isEqualTo(2);

        assertThat(sentRequests(2)).extracting(SendMessageRequest::messageDeduplicationId)
                .containsExactly(FIRST_TRANSACTION_ID, SECOND_TRANSACTION_ID);
        assertThat(head.getPublishedAt()).isNotNull();
        assertThat(follower.getPublishedAt()).isNotNull();
    }

    /**
     * A reply whose deadline has passed is STILL SENT, and the lateness is reported rather than acted on.
     *
     * <p>⚠️ Refactoring Rationale: this case asserted the exact opposite. It required that a stale row was
     * withheld from the transport, marked published without being sent, and stamped
     * {@code "expired before publication"} -- and it passed, because that is what the publisher did. The
     * arithmetic is what makes that behaviour a defect rather than a policy: the deadline this service
     * stamps is five seconds ({@code COPAUA0C.cbl} L750, {@code MOVE 50 TO MQMD-EXPIRY}, denominated in
     * TENTHS of a second, a distinct unit from the receive wait at L242 which is in milliseconds) and the
     * first retry backoff is also five seconds, so EVERY reply that failed one attempt was past its
     * deadline before its next attempt fell due and was then discarded unsent, with a row asserting a
     * publication that never happened and a retention sweep keyed on that same column deleting the
     * evidence. §0.4.3 of the technical specification states the guarantee this outbox exists for as "a
     * reply is published for every committed authorization", and the withdrawn behaviour inverted it.</p>
     *
     * <p>Assumptions: the deadline is a TRANSPORT attribute the RECEIVER honours, which is how
     * §0.7.6 and {@code docs/adr/ADR-004-messaging.md} both record the resolution of the reference's
     * per-message expiry against a transport that has none. So the publisher's half of the contract is to
     * SEND and to say that it is late; the receiving half belongs to
     * {@code AuthorizationRequestListenerTest}. This case asserts three things together because any one
     * without the others would be a defect: the reply reaches the transport, the row ends genuinely
     * published with NO diagnostic, and the lateness is on the log where an operator can alert on it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reply past its deadline is still sent, and the lateness is logged not enacted")
    void aReplyPastItsDeadlineIsStillSentAndTheLatenessIsLogged() {
        AuthReplyOutbox stale = rowWith(1L, CARD_NUM, FIRST_TRANSACTION_ID, REPLY_QUEUE,
                CORRELATION_ID, APPROVED_PAYLOAD, fixedNow().minusSeconds(1L));
        Logger publisherLogger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        publisherLogger.addAppender(captured);
        // WHY : Assumptions: the prior level may legitimately be null, which is not "no level" but
        //       "inherit from the parent", and restoring null is what puts the logger back into that
        //       state. Substituting a concrete default here would leave the logger pinned where it had
        //       previously been inheriting, which is a different configuration that happens to look the
        //       same in this class.
        Level previousLevel = publisherLogger.getLevel();
        publisherLogger.setLevel(Level.WARN);
        try {
            assertThat(this.publisher.drain())
                    .as("a reply the committed decision says is owed is delivered late rather than lost")
                    .isOne();

            verify(this.sqs, times(1)).sendMessage(any(SendMessageRequest.class));
            assertThat(stale.isPublished())
                    .as("the publication column must mean an accepted send and nothing else")
                    .isTrue();
            assertThat(stale.getLastError())
                    .as("a successful send leaves no diagnostic, however late it was")
                    .isNull();
            List<String> lines =
                    captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines)
                    .as("lateness an operator cannot see is lateness nobody can act on")
                    .anyMatch(line -> line.contains("event=auth.reply.late")
                            && line.contains("expiresAt=" + stale.getExpiresAt()));
            assertThat(lines)
                    .as("the withdrawn retirement event must not come back under its old name")
                    .noneMatch(line -> line.contains("event=auth.reply.expired"));
        } finally {
            publisherLogger.setLevel(previousLevel);
            publisherLogger.detachAppender(captured);
        }
    }

    /**
     * The deadline stamped on a send is the row's WINDOW re-applied from the send instant.
     *
     * <p>⚠️ Refactoring Rationale: the stored absolute instant was sent verbatim, and that mistranslated
     * the reference semantic in a way that guaranteed loss under any delay. The reference sets a DURATION
     * on the descriptor immediately before its put ({@code COPAUA0C.cbl} L750) and the transport counts it
     * from the put; it is not a wall-clock instant fixed when the decision was committed. So a reply
     * committed with a five-second window and first sent seven seconds later went out carrying a deadline
     * one second in the PAST, and the receiving end -- which honours the attribute, as the messaging
     * decision record directs -- discarded it on arrival. Late delivery became guaranteed non-delivery.</p>
     *
     * <p>Assumptions: the window is asserted rather than the instant, because the window is what the
     * reference chose and the instant is derived from it. This row's window is five seconds and the fixed
     * clock does not move, so the sent value equals the row's own stored deadline here -- which is why the
     * case ALSO drives a row whose window is wider, so that a publisher which simply echoed the stored
     * value could not satisfy both halves at once.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the sent deadline is the row's window re-applied from the send instant")
    void theSentDeadlineIsTheRowsWindowRebasedOnTheSendInstant() {
        AuthReplyOutbox punctual = rowWith(1L, CARD_NUM, FIRST_TRANSACTION_ID, REPLY_QUEUE,
                CORRELATION_ID, APPROVED_PAYLOAD, liveDeadline());
        AuthReplyOutbox wideWindow = rowWith(2L, OTHER_CARD_NUM, SECOND_TRANSACTION_ID, REPLY_QUEUE,
                CORRELATION_ID, APPROVED_PAYLOAD, fixedNow().plusSeconds(30L));

        assertThat(this.publisher.drain()).isEqualTo(2);

        List<SendMessageRequest> sent = sentRequests(2);
        assertThat(sent).extracting(SendMessageRequest::messageGroupId)
                .as("the heads are claimed in ascending identity order, so the rows are positional here")
                .containsExactly(CARD_NUM, OTHER_CARD_NUM);
        assertThat(sentDeadlineOf(sent.get(0)))
                .as("a five-second window re-applied from an unmoved clock is the stored instant")
                .isEqualTo(fixedNow().plusSeconds(5L).toString())
                .isEqualTo(punctual.getExpiresAt().toString());
        assertThat(sentDeadlineOf(sent.get(1)))
                .as("a thirty-second window must stay thirty seconds and not collapse onto five")
                .isEqualTo(fixedNow().plusSeconds(30L).toString())
                .isEqualTo(wideWindow.getExpiresAt().toString());
    }

    /**
     * A successful publish logs the BROKER'S own identities beside this service's row identity.
     *
     * <p>⚠️ Refactoring Rationale: the send's response was discarded, and the comment justifying that said
     * it "carries only the transport's own message identifier, which nothing here records". That
     * identifier is the one piece of evidence the publication line was missing. A first-in-first-out send
     * whose deduplication identifier matches one the broker already accepted is SUPPRESSED and answered
     * with the identity of the message the broker already holds -- both outcomes return success and both
     * reach this line -- so a replay of a prompt reply was indistinguishable from a fresh delivery in the
     * log, while the row claimed a publication either way. Logging the returned identity is what makes
     * the two tellable apart at all.</p>
     *
     * <p>Assumptions: the identities are logged and NOT persisted. The row's column set is frozen by an
     * applied migration and adding one would change a Flyway checksum in every environment that has
     * already run it, so the pairing lives in the log and is joined to the row by the outbox identity,
     * which is on both lines.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a successful publish logs the broker's message identity and sequence number")
    void aSuccessfulPublishLogsTheBrokerIdentity() {
        approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder()
                        .messageId(BROKER_MESSAGE_ID)
                        .sequenceNumber(BROKER_SEQUENCE_NUMBER)
                        .build());
        Logger publisherLogger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        publisherLogger.addAppender(captured);
        Level previousLevel = publisherLogger.getLevel();
        publisherLogger.setLevel(Level.INFO);
        try {
            assertThat(this.publisher.drain()).isOne();

            List<String> lines =
                    captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines)
                    .as("a publication line that omits the broker's answer cannot distinguish a fresh "
                            + "enqueue from a deduplication-suppressed replay")
                    .anyMatch(line -> line.contains("event=auth.reply.published")
                            && line.contains("brokerMessageId=" + BROKER_MESSAGE_ID)
                            && line.contains("brokerSequenceNumber=" + BROKER_SEQUENCE_NUMBER));
        } finally {
            publisherLogger.setLevel(previousLevel);
            publisherLogger.detachAppender(captured);
        }
    }

    /**
     * A publication that succeeds while the transport answers nothing is still recorded as published.
     *
     * <p>Assumptions: the broker identities are read from the response AFTER the row has been committed as
     * published, so a null-tolerant read is a CORRECTNESS requirement rather than defensive habit. A null
     * dereference at that point would be caught by the failure handler and would record a failure -- or,
     * at the attempt ceiling, an abandonment -- against a reply that was successfully sent. This case
     * leaves the client stub unstubbed, which is exactly the shape that produces a null response.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a send whose response carries no identity is still recorded as published")
    void aSendWithNoBrokerResponseIsStillPublished() {
        AuthReplyOutbox row = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);

        assertThat(this.publisher.drain()).isOne();

        assertThat(row.isPublished())
                .as("an unreadable broker answer must not turn an accepted send into a failure")
                .isTrue();
        assertThat(row.getLastError()).isNull();
        assertThat(row.isAbandoned()).isFalse();
    }

    /**
     * A transient transport fault is reattempted exactly once and then succeeds.
     *
     * <p>Assumptions: the retry budget is asserted through the COUNT of send calls, because that is its
     * only observable form at this boundary. One further attempt is the whole budget: the framework
     * attribute the policy mirrors is named {@code maxRetries} and not {@code maxAttempts}, so a budget of
     * one means two attempts in total, and reading it as an attempt count would be an off-by-one that
     * surfaces only under the failure it was meant to handle.</p>
     *
     * <p>Assumptions: the recovered row is asserted to end published with NO diagnostic, so a successful
     * second attempt is not left looking like a failure an operator would investigate.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a transient transport fault is reattempted once and then succeeds")
    void aTransientTransportFaultIsReattemptedOnce() {
        AuthReplyOutbox recovering = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("connection reset"))
                .thenReturn(SendMessageResponse.builder().build());

        assertThat(this.publisher.drain()).isOne();

        verify(this.sqs, times(2)).sendMessage(any(SendMessageRequest.class));
        assertThat(recovering.isPublished()).isTrue();
        assertThat(recovering.getLastError()).isNull();
    }

    /**
     * A client-side refusal is not reattempted, and its group stops rather than advancing.
     *
     * <p>Assumptions: the classification is deliberately narrow because the reference's own is. Its
     * transient set is {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L87 -- three of the seven non-ok statuses
     * declared in that block -- and the four data conditions beside it are excluded there because they
     * would return the same answer however often they were attempted. A status in the 4xx band is the
     * migrated analogue: a statement about the request, which a second identical attempt cannot change, so
     * reattempting it would only delay recording it.</p>
     *
     * <p>Assumptions: the refused row is asserted to end PENDING rather than abandoned, because its
     * attempt budget is far from spent. Its group therefore stops, which is what keeps a later reply for
     * the same card from overtaking an unanswered earlier one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a client-side refusal is not reattempted and leaves its row pending")
    void aClientSideRefusalIsNotReattempted() {
        AuthReplyOutbox refused = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        when(this.sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(
                AwsServiceException.builder().message("not authorized").statusCode(403).build());

        assertThat(this.publisher.drain()).isZero();

        verify(this.sqs, times(1)).sendMessage(any(SendMessageRequest.class));
        assertThat(refused.isPublished()).isFalse();
        assertThat(refused.isAbandoned()).isFalse();
    }

    /**
     * The retention sweep deletes by a cut-off that is the configured window behind the clock.
     *
     * <p>Assumptions: the cut-off is asserted rather than the delete count, because the safety property of
     * this sweep is which rows it can select. The repository statement itself requires a publication
     * instant to be PRESENT, so a pending reply is unselectable by construction; what remains to be proved
     * here is that the publisher asks for the configured age and not for everything.</p>
     *
     * <p>Assumptions: a short retention window is defensible precisely because the reference reply was
     * never persistent. {@code COPAUA0C.cbl} L749 moves {@code MQPER-NOT-PERSISTENT} into the reply
     * descriptor's persistence field, so the reference kept no durable copy of an answer at all and an
     * operator there could not ask after the fact whether one had been sent. The window retained here is
     * therefore strictly more than the reference offered, and it is kept short because a row still carries
     * an encoded reply containing a primary account number -- so holding one longer than the question needs
     * is a disclosure surface rather than a safety margin.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("retention sweeps published replies older than the configured window")
    void retentionSweepsPublishedRepliesOnly() {
        when(this.outbox.deletePublishedBefore(any(LocalDateTime.class), anyInt())).thenReturn(3);

        assertThat(this.publisher.purgePublished()).isEqualTo(3);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(this.outbox).deletePublishedBefore(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isEqualTo(fixedNow().minusDays(VALID_RETENTION_DAYS));
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
     * repository as a row limit the database rejects, so without this refusal the fault recurs once per
     * poll interval on a scheduled thread and never stops the instance. An enormous size is accepted by
     * the database and is worse for it: one pass claims and LEASES every head before it publishes any of
     * them, so a larger batch withholds more rows from any other publisher for longer.</p>
     *
     * <p>Assumptions: the message is asserted to name the PROPERTY rather than merely to exist, because a
     * start-up failure is read by an operator who has to find the value they mis-set, and the property
     * name is what they search for.</p>
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
        verifyNoInteractions(this.sqs);
    }

    /**
     * A polling interval that would busy-loop the database or strand a committed reply is refused.
     *
     * <p>Assumptions: the floor is not merely a positive test, because this value is the delay BETWEEN
     * passes and every pass opens a transaction and issues a claiming statement. An interval of one
     * millisecond turns the drain into a busy loop whose only effect is to consume the connection budget
     * the deciding path needs.</p>
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
        verifyNoInteractions(this.sqs);
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
                () -> new OutboxPublisher(this.outbox, this.sqs, this.clock, txManager(),
                        VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS, 0,
                        VALID_MAX_ROWS_PER_DRAIN, VALID_MAX_ATTEMPTS));

        assertTrue(zero.getMessage().contains("carddemo.messaging.outbox-retention-days"),
                "the refusal names the property an operator has to correct");
        verifyNoInteractions(this.sqs);
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
                () -> new OutboxPublisher(null, this.sqs, this.clock, txManager(),
                        VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS,
                        VALID_MAX_ROWS_PER_DRAIN, VALID_MAX_ATTEMPTS));
        NullPointerException noSqs = assertThrows(NullPointerException.class,
                () -> new OutboxPublisher(this.outbox, null, this.clock, txManager(),
                        VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS,
                        VALID_MAX_ROWS_PER_DRAIN, VALID_MAX_ATTEMPTS));
        NullPointerException noClock = assertThrows(NullPointerException.class,
                () -> new OutboxPublisher(this.outbox, this.sqs, null, txManager(),
                        VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS,
                        VALID_MAX_ROWS_PER_DRAIN, VALID_MAX_ATTEMPTS));

        assertTrue(noOutbox.getMessage().contains("outbox"), "the refusal names the outbox repository");
        assertTrue(noSqs.getMessage().contains("sqs"), "the refusal names the queue client");
        assertTrue(noClock.getMessage().contains("clock"), "the refusal names the clock");
    }


    /**
     * Captures the send requests one drain issued, asserting how many there were.
     *
     * <p>Assumptions: the expected count is asserted INSIDE this helper rather than left to each caller,
     * because a captor returns whatever it captured and a case that read the first request without
     * bounding the total would pass for a publisher that also sent a second one nobody looked at.</p>
     *
     * @param expected the exact number of sends, as an {@code int}, the pass is required to have made
     * @return the captured requests as a {@code List<SendMessageRequest>} in call order, never
     *     {@code null}
     */
    private List<SendMessageRequest> sentRequests(int expected) {
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(expected)).sendMessage(sent.capture());
        return sent.getAllValues();
    }

    /**
     * Builds a transaction manager whose transactions begin and commit without a database.
     *
     * <p>Assumptions: a plain mock is sufficient and is deliberately not a callback-running stub. The
     * publisher opens its units of work through a template built over this manager, and a template asks
     * the manager for a transaction, runs the callback, then commits -- so a mock returning a mock status
     * executes every callback exactly once and records that a transaction was demanded. Alternatives
     * Considered: a stub that runs callbacks without asking for a transaction at all; rejected because it
     * would let a publisher that stopped opening a transaction around its writes still pass, and where
     * those boundaries fall is the substance of this class's subject.</p>
     *
     * @return a {@code PlatformTransactionManager} that begins and commits without a database, never
     *     {@code null}
     */
    private static PlatformTransactionManager txManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return manager;
    }

    /**
     * Builds a publisher over the shared collaborators with the two bounded numbers supplied.
     *
     * @param batchSize the batch size to configure, as an {@code int}, which may be a value the
     *     constructor refuses
     * @param pollIntervalMillis the polling interval in milliseconds, as a {@code long}, which may be a
     *     value the constructor refuses
     * @return the constructed {@code OutboxPublisher}, when every number was acceptable
     */
    private OutboxPublisher publisherWith(int batchSize, long pollIntervalMillis) {
        return new OutboxPublisher(this.outbox, this.sqs, this.clock, txManager(), batchSize,
                pollIntervalMillis, VALID_RETENTION_DAYS, VALID_MAX_ROWS_PER_DRAIN,
                VALID_MAX_ATTEMPTS);
    }

    /**
     * Every claimed head is published before any group advances a second time.
     *
     * <p>Purpose: this is the fairness property the drain's own documentation described and its code did not
     * have. The observable is the ORDER of the sends, because that order is what a requester's deadline is
     * measured against: a group whose first reply is sent after four hundred and eighty other row-turns has
     * missed a five-second deadline whatever the pass's totals say.</p>
     *
     * <p>Refactoring Rationale: the loop was DEPTH-first while its documentation claimed breadth. It reached
     * the first claimed head and drained that group up to a per-group share -- twenty rows with the shipped
     * defaults, each a claim, a send and a short transaction -- before touching the second head at all. A
     * dead {@code followerBudget} local sat beside the loop as the only trace of the intended shape. This
     * case fails against that loop and passes against the rotation that replaced it.</p>
     *
     * <p>Assumptions: the FIRST TWO sends must be one from each group, and that is asserted rather than the
     * whole sequence being pinned. What the property guarantees is when each group's first reply goes out;
     * the order the remaining rows of a busy group take among themselves is per-group ordering, which the
     * cases beside this one already cover.</p>
     *
     * <p>Assumptions: the busy group is given FIVE rows and the quiet group ONE, both comfortably inside the
     * per-group ceiling of twenty that the shipped defaults derive. The ceiling is therefore not what makes
     * this case pass -- if it were, the case would be asserting the bound rather than the fairness.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a busy group does not delay the first reply of a quiet group claimed beside it")
    void aBusyGroupDoesNotDelayAQuietGroupsFirstReply() {
        approvedRow(1L, CARD_NUM, "TX0000000000101");
        approvedRow(2L, CARD_NUM, "TX0000000000102");
        approvedRow(3L, CARD_NUM, "TX0000000000103");
        approvedRow(4L, CARD_NUM, "TX0000000000104");
        approvedRow(5L, CARD_NUM, "TX0000000000105");
        approvedRow(6L, OTHER_CARD_NUM, "TX0000000000201");

        assertThat(this.publisher.drain())
                .as("every row of both groups is inside the pass budget, so all six are published")
                .isEqualTo(6);

        ArgumentCaptor<SendMessageRequest> sent =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(6)).sendMessage(sent.capture());
        List<String> groupOrder = sent.getAllValues().stream()
                .map(SendMessageRequest::messageGroupId)
                .toList();

        assertThat(groupOrder.subList(0, 2))
                .as("the quiet group's only reply must go out in the head round, not behind five others")
                .containsExactly(CARD_NUM, OTHER_CARD_NUM);
        assertThat(groupOrder)
                .as("the busy group's four remaining rows follow, in the rotation's remainder")
                .containsExactly(CARD_NUM, OTHER_CARD_NUM, CARD_NUM, CARD_NUM, CARD_NUM, CARD_NUM);
    }

    /**
     * Three groups of differing depth are advanced one row each per round, in claim order.
     *
     * <p>Purpose: two groups can be satisfied by an implementation that merely handles the FIRST row of each
     * head before looping again over the same list. Three groups of three different depths pin the rotation
     * itself: it must keep going round the survivors, dropping each group as it runs dry, rather than
     * finishing one and then the next.</p>
     *
     * <p>Assumptions: the expected sequence is stated in full, because with the depths chosen there is
     * exactly one order a round-robin can produce. Round one takes one row from each of the three; round two
     * takes one row from the two that still have rows; round three takes the last row of the deepest. A
     * depth-first loop produces a completely different sequence and a per-group-share loop produces another,
     * so the assertion distinguishes all three.</p>
     *
     * <p>Assumptions: the deduplication identifiers are distinct across every row, because the reply queue
     * suppresses a duplicate identifier within its interval and a case reusing one would be asserting on a
     * send the queue would have discarded.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the follower rotation advances every surviving group once per round, in claim order")
    void theFollowerRotationAdvancesEverySurvivingGroupOncePerRound() {
        approvedRow(1L, CARD_NUM, "TX0000000000301");
        approvedRow(2L, CARD_NUM, "TX0000000000302");
        approvedRow(3L, CARD_NUM, "TX0000000000303");
        approvedRow(4L, OTHER_CARD_NUM, "TX0000000000401");
        approvedRow(5L, OTHER_CARD_NUM, "TX0000000000402");
        approvedRow(6L, THIRD_CARD_NUM, "TX0000000000501");

        assertThat(this.publisher.drain()).isEqualTo(6);

        ArgumentCaptor<SendMessageRequest> sent =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(6)).sendMessage(sent.capture());
        assertThat(sent.getAllValues().stream().map(SendMessageRequest::messageGroupId).toList())
                .as("round one takes one of each, round two the two that remain, round three the deepest")
                .containsExactly(CARD_NUM, OTHER_CARD_NUM, THIRD_CARD_NUM,
                        CARD_NUM, OTHER_CARD_NUM, CARD_NUM);
    }

    /**
     * A group whose send fails leaves the rotation and never delays or reorders the groups beside it.
     *
     * <p>Purpose: the rotation must drop a failed group rather than carry it, and it must not let that failure
     * cost the other groups their turns. Both halves matter and they pull in opposite directions: stopping the
     * failed group is what preserves per-card order, and continuing the others is what stops one unreachable
     * reply erasing every other group's progress.</p>
     *
     * <p>Assumptions: the failing group is the FIRST claimed, so a rotation that abandoned the pass on a
     * failure would publish nothing at all and a rotation that carried the failed group would send its second
     * row. Placing it last would let both faults pass unnoticed.</p>
     *
     * <p>Assumptions: the surviving group's BOTH rows are expected, because the failed group's departure
     * returns its share of the remainder rather than stranding it. A rotation that reserved a private share
     * per group would leave the second row of the survivor unsent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed group leaves the rotation while the groups beside it keep their turns")
    void aFailedGroupLeavesTheRotationWithoutCostingTheOthers() {
        approvedRow(1L, CARD_NUM, "TX0000000000601");
        approvedRow(2L, CARD_NUM, "TX0000000000602");
        approvedRow(3L, OTHER_CARD_NUM, "TX0000000000701");
        approvedRow(4L, OTHER_CARD_NUM, "TX0000000000702");

        // WHY : Assumptions: the failure is bound to the FAILING GROUP's identity rather than to a call
        //       ordinal, so the case states which group cannot be reached instead of which turn fails. A
        //       call-ordinal stub would silently move to a different group the moment the rotation's order
        //       changed, which is exactly the property under test.
        when(this.sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(invocation -> {
            SendMessageRequest request = invocation.getArgument(0);
            if (CARD_NUM.equals(request.messageGroupId())) {
                throw new IllegalStateException("the reply queue for this group is unreachable");
            }
            return null;
        });

        assertThat(this.publisher.drain())
                .as("the reachable group's two rows are published and the unreachable group's none")
                .isEqualTo(2);

        ArgumentCaptor<SendMessageRequest> sent =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(3)).sendMessage(sent.capture());
        List<String> groupOrder = sent.getAllValues().stream()
                .map(SendMessageRequest::messageGroupId)
                .toList();
        assertThat(groupOrder)
                .as("the failed group is attempted once and then dropped, so its second row is never sent")
                .containsExactly(CARD_NUM, OTHER_CARD_NUM, OTHER_CARD_NUM);
        assertThat(groupOrder.stream().filter(CARD_NUM::equals).count())
                .as("advancing a group whose send failed would place a newer reply ahead of an older one")
                .isEqualTo(1L);
    }

    /**
     * An abandoned reply is announced with the ACQUIRER'S TRANSACTION IDENTIFIER and the cause.
     *
     * <p>Assumptions: abandonment is the terminal statement that a reply the committed decision owed will
     * never be delivered, so it is the one line an operator reaches for when a requester reports an
     * unanswered transaction -- and the operator holding that report has the transaction identifier, not
     * this service's surrogate row key. The identifier is message metadata rather than a protected value:
     * the technical specification freezes it as the deduplication identity, so it already travels in queue
     * telemetry on every send, which is why naming it here discloses nothing the transport does not
     * already carry.</p>
     *
     * <p>Assumptions: the terminal state itself is asserted alongside the line, because a diagnosis with
     * the wrong row state behind it is worse than none. A publication instant must NOT be set -- that is
     * the property the withdrawn retirement transition violated and the reason the retention sweep could
     * delete the evidence -- and the abandonment instant must be.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an abandoned reply names its transaction identifier and its cause")
    void anAbandonedReplyNamesItsTransactionAndCause() {
        AuthReplyOutbox doomed = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        // WHY : Assumptions: the row is placed AT the ceiling rather than driven to it. The attempt
        //       counter is advanced by the claiming statement alone, which this simulated table does not
        //       reproduce, so a case that only lowered the ceiling would never reach the terminal branch
        //       at all. Setting the counter to the ceiling states the precondition the branch tests --
        //       "this row has used its whole budget" -- and leaves the ladder that produces that state to
        //       OutboxPublisherLifecycleRepositoryIT, which drives it against a real engine.
        assignAttempts(doomed, 1);
        OutboxPublisher atCeiling = new OutboxPublisher(this.outbox, this.sqs, this.clock, txManager(),
                VALID_BATCH_SIZE, VALID_POLL_INTERVAL_MILLIS, VALID_RETENTION_DAYS,
                VALID_MAX_ROWS_PER_DRAIN, 1);
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.builder()
                        .message("Unable to execute HTTP request")
                        .cause(new ConnectException("Connection refused"))
                        .build());
        Logger publisherLogger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        publisherLogger.addAppender(captured);
        Level previousLevel = publisherLogger.getLevel();
        publisherLogger.setLevel(Level.ERROR);
        try {
            assertThat(atCeiling.drain()).isZero();

            List<String> lines = captured.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("event=auth.reply.abandoned"))
                    .toList();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0))
                    .as("an operator holding an unanswered-transaction report has the transaction "
                            + "identifier, not this service's row key")
                    .contains("transactionId=" + FIRST_TRANSACTION_ID)
                    .contains("reason=Connection refused")
                    .contains("sqlState=(absent)");
            assertThat(doomed.isAbandoned()).isTrue();
            assertThat(doomed.isPublished())
                    .as("an abandoned reply must never read as delivered, or the retention sweep "
                            + "deletes the only evidence that it was owed")
                    .isFalse();
        } finally {
            publisherLogger.setLevel(previousLevel);
            publisherLogger.detachAppender(captured);
        }
    }

    /**
     * A failed publication names the deepest cause's MESSAGE on the log, masked and bounded.
     *
     * <p>⚠️ Refactoring Rationale: the failure line carried {@code fault=} and nothing else, and its value
     * was the outermost exception's class name. Against a live queue that produced
     * {@code fault=software.amazon.awssdk.core.exception.SdkClientException} twice for the same reply, with
     * no statement anywhere of WHY the client could not reach the queue -- and a client exception is a
     * connect refusal, a timeout or an unresolved host, so the cause beneath it IS the diagnosis. The line
     * now carries three values: the cause chain's types and frames, the deepest message, and the SQLSTATE
     * where the chain carries one.</p>
     *
     * <p>Assumptions: the message is asserted to arrive MASKED and control-free, not merely present. It is
     * written by a transport, a driver or a codec and can quote the value it was handling, so it passes
     * {@code FailureSummary} first -- control characters neutralised, any embedded card number reduced to
     * its last four digits, length bounded. This case seeds a cause whose message embeds this row's own
     * card number and a carriage return, and asserts that neither reaches the line, which is the only form
     * in which "safe to log" is provable rather than claimed.</p>
     *
     * <p>Assumptions: the LOG carries the message and the ROW does not, and both halves are asserted here
     * because the asymmetry is deliberate. An abandoned row deliberately outlives the retention sweep, so
     * persisting a transport-authored string would give it an unbounded lifetime in the database, whereas
     * the log is retained by policy.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed publication logs the deepest cause message, masked, and persists none of it")
    void aFailedPublicationLogsTheMaskedCauseMessage() {
        AuthReplyOutbox head = approvedRow(1L, CARD_NUM, FIRST_TRANSACTION_ID);
        Throwable rootCause = new ConnectException(
                "Connection refused to " + CARD_NUM + "\rhost sqs.local:9324");
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.builder()
                        .message("Unable to execute HTTP request")
                        .cause(rootCause)
                        .build());
        Logger publisherLogger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        publisherLogger.addAppender(captured);
        Level previousLevel = publisherLogger.getLevel();
        publisherLogger.setLevel(Level.ERROR);
        try {
            assertThat(this.publisher.drain()).isZero();

            List<String> failures = captured.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("event=auth.reply.publish-failed"))
                    .toList();
            assertThat(failures)
                    .as("a publication failure with no stated cause is a failure nobody can act on")
                    .hasSize(1);
            String line = failures.get(0);
            assertThat(line)
                    .as("the deepest cause is the diagnosis; the outer client exception is only its wrapper")
                    .contains("reason=")
                    .contains("Connection refused to ")
                    .contains("host sqs.local:9324");
            assertThat(line)
                    .as("an unmasked primary account number must never reach a log line")
                    .doesNotContain(CARD_NUM)
                    .contains(CARD_NUM.substring(CARD_NUM.length() - 4));
            assertThat(line)
                    .as("a carriage return would let a transport message forge a second log record")
                    .doesNotContain("\r");
            assertThat(line)
                    .as("no SQLSTATE is carried by a transport chain, and its absence is named")
                    .contains("sqlState=(absent)");
            assertThat(head.getLastError())
                    .as("the message is logged and never persisted")
                    .doesNotContain("Connection refused");
        } finally {
            publisherLogger.setLevel(previousLevel);
            publisherLogger.detachAppender(captured);
        }
    }

    /**
     * Reads the staleness deadline a send request carries.
     *
     * @param sent the captured {@code SendMessageRequest}; must not be {@code null}
     * @return the deadline attribute's value as a {@code String}, never {@code null}
     */
    private static String sentDeadlineOf(SendMessageRequest sent) {
        return sent.messageAttributes().get(OutboxPublisher.ATTRIBUTE_EXPIRES_AT).stringValue();
    }

    /**
     * Writes one pending reply row carrying an approved body into the simulated table.
     *
     * @param outboxId the identity to assign, as a {@code long}, standing in for the generated key
     * @param cardNum the card number as a {@code String}, which is also the ordering identity
     * @param transactionId the acquirer's transaction identifier as a {@code String}, which is also the
     *     deduplication identity
     * @return the written {@code AuthReplyOutbox} row, never {@code null}
     */
    private AuthReplyOutbox approvedRow(long outboxId, String cardNum, String transactionId) {
        return rowWith(outboxId, cardNum, transactionId, REPLY_QUEUE, CORRELATION_ID,
                APPROVED_PAYLOAD, liveDeadline());
    }

    /**
     * Writes one pending reply row carrying a declined body into the simulated table.
     *
     * @param outboxId the identity to assign, as a {@code long}, standing in for the generated key
     * @param cardNum the card number as a {@code String}, which is also the ordering identity
     * @param transactionId the acquirer's transaction identifier as a {@code String}, which is also the
     *     deduplication identity
     * @return the written {@code AuthReplyOutbox} row, never {@code null}
     */
    private AuthReplyOutbox declinedRow(long outboxId, String cardNum, String transactionId) {
        return rowWith(outboxId, cardNum, transactionId, REPLY_QUEUE, CORRELATION_ID,
                DECLINED_PAYLOAD, liveDeadline());
    }

    /**
     * Writes one pending reply row into the simulated table, with every column stated.
     *
     * <p>Assumptions: the ordering identity IS the card number and the deduplication identity IS the
     * acquirer's transaction identifier, verbatim, which is what the technical specification freezes for
     * the reply queue. Two rows built for one card therefore share an ordering group and differ in
     * deduplication identity, which is exactly the shape the publisher's group-then-follower claim walks.
     * </p>
     *
     * <p>Assumptions: the payload is passed in as characters and is never assembled from a codec here, so
     * a case can hand the publisher a body that is not a well-formed reply at all. That is the whole
     * mechanism by which payload opacity is provable rather than merely asserted.</p>
     *
     * <p>Assumptions: the row is added to the simulated table as a side effect, because writing a row is
     * what a committed decision does. A case that wants a rolled-back decision therefore calls nothing.</p>
     *
     * @param outboxId the identity to assign, as a {@code long}, standing in for the generated key
     * @param cardNum the card number as a {@code String}, which becomes the ordering identity
     * @param transactionId the acquirer's transaction identifier as a {@code String}, which becomes the
     *     deduplication identity
     * @param replyQueueUrl the destination this row records, as a {@code String}
     * @param correlationId the identity to echo, as a {@code String}
     * @param payload the opaque encoded body, as a {@code String}, held exactly as given
     * @param expiresAt the staleness deadline as a {@code LocalDateTime} in coordinated universal time
     * @return the written {@code AuthReplyOutbox} row, never {@code null}
     */
    private AuthReplyOutbox rowWith(long outboxId, String cardNum, String transactionId,
            String replyQueueUrl, String correlationId, String payload, LocalDateTime expiresAt) {
        AuthReplyOutbox row = new AuthReplyOutbox(replyQueueUrl, correlationId, cardNum,
                transactionId, payload, expiresAt, fixedNow());
        assignIdentity(row, outboxId);
        this.stored.add(row);
        return row;
    }

    /**
     * Assigns the database-generated identity of a row under test.
     *
     * <p>Assumptions: the identity is assigned reflectively because it is generated by the database and
     * the entity exposes no setter for it, which is correct for production and leaves a case needing two
     * ordered rows of one group with no other way to order them. Alternatives Considered: adding a setter
     * to the entity; rejected because it would widen a production type's surface for a test's
     * convenience.</p>
     *
     * @param row the {@code AuthReplyOutbox} row to assign; must not be {@code null}
     * @param outboxId the identity to assign, as a {@code long}
     * @throws IllegalStateException if the field cannot be reached, which would mean the entity's identity
     *     member had been renamed and this helper had not been updated with it
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
     * Places a row's attempt counter at a stated value.
     *
     * <p>Assumptions: the counter is set reflectively for the same reason the identity is -- it is advanced
     * by the CLAIMING STATEMENT and the entity deliberately exposes no way to advance it, which is the
     * invariant that stopped one failed publication being counted twice. A case that needs a row already at
     * its attempt ceiling therefore has no other way to say so. Alternatives Considered: adding a setter to
     * the entity; rejected because it would reopen exactly the double-counting the absent setter closes.</p>
     *
     * @param row the {@code AuthReplyOutbox} row to adjust; must not be {@code null}
     * @param attempts the attempt count to place on it, as an {@code int}
     * @throws IllegalStateException if the field cannot be reached, which would mean the entity's counter
     *     had been renamed and this helper had not been updated with it
     */
    private static void assignAttempts(AuthReplyOutbox row, int attempts) {
        try {
            Field field = AuthReplyOutbox.class.getDeclaredField("attempts");
            field.setAccessible(true);
            field.set(row, attempts);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(
                    "AuthReplyOutbox.attempts is no longer reachable, so this helper is stale",
                    unreachable);
        }
    }

    /**
     * Answers a head claim from the simulated table, one row per ordering group.
     *
     * <p>Assumptions: this reproduces the three properties of the real claim that the publisher's own
     * behaviour depends on, and no more. At most one row per group is returned, which is what preserves
     * per-card order; rows are visited in ascending identity so the oldest reply of a group is its head;
     * and the limit is honoured, so a pass is bounded by configuration rather than by the backlog. What it
     * deliberately does not reproduce is the statement's attempt increment, lease and
     * comparison-and-swap, which belong to {@code OutboxRepositoryIT}.</p>
     *
     * @param limit the greatest number of group heads to return, as an {@code int}
     * @return the claimed heads as a {@code List<AuthReplyOutbox>} in ascending identity order, never
     *     {@code null}
     */
    private List<AuthReplyOutbox> claimHeads(int limit) {
        List<AuthReplyOutbox> heads = new ArrayList<>();
        Set<String> groupsTaken = new LinkedHashSet<>();
        for (AuthReplyOutbox row : byIdentity()) {
            // WHY : Assumptions: an unclaimable row does NOT reserve its group, so the next eligible row
            //       of that group can still become its head. Reserving the group on a skipped row would
            //       hide a whole card behind one published or deferred reply, which is neither what the
            //       claiming statement does nor what the ordering guarantee requires.
            if (!isClaimable(row) || !groupsTaken.add(row.getOrderGroupId())) {
                continue;
            }
            heads.add(row);
            if (heads.size() >= limit) {
                break;
            }
        }
        return heads;
    }

    /**
     * Answers a follower claim from the simulated table, for one group above one identity.
     *
     * <p>Assumptions: the bound is the identity just handled rather than a re-read of the group's head,
     * matching the publisher's own call, so a row already handled cannot be handed back twice within one
     * pass whatever its stored state currently says.</p>
     *
     * @param orderGroupId the ordering group to advance, as a {@code String}
     * @param afterOutboxId the identity just handled, as a {@code long}, which bounds the search below
     * @param limit the greatest number of followers to return, as an {@code int}
     * @return the claimed followers as a {@code List<AuthReplyOutbox>} in ascending identity order, never
     *     {@code null}
     */
    private List<AuthReplyOutbox> claimFollowers(String orderGroupId, long afterOutboxId, int limit) {
        List<AuthReplyOutbox> followers = new ArrayList<>();
        for (AuthReplyOutbox row : byIdentity()) {
            if (!orderGroupId.equals(row.getOrderGroupId()) || row.getOutboxId() <= afterOutboxId
                    || !isClaimable(row)) {
                continue;
            }
            followers.add(row);
            if (followers.size() >= limit) {
                break;
            }
        }
        return followers;
    }

    /**
     * Orders the simulated table by identity, which is its publication order.
     *
     * @return a copy of the simulated table as a {@code List<AuthReplyOutbox>} sorted ascending by
     *     identity, never {@code null}
     */
    private List<AuthReplyOutbox> byIdentity() {
        List<AuthReplyOutbox> ordered = new ArrayList<>(this.stored);
        ordered.sort(Comparator.comparing(AuthReplyOutbox::getOutboxId));
        return ordered;
    }

    /**
     * Reports whether a row is still a candidate for a claim.
     *
     * <p>Assumptions: the predicate mirrors the three exclusions the claiming statement makes that the
     * publisher can observe -- a published row is finished, an abandoned row is terminal, and a row
     * deferred to a backoff is not yet eligible. Including the backoff matters rather than being
     * incidental: without it a failed reply would be re-claimed by the very next pass, and a case
     * asserting that a failure defers rather than spins would pass against a simulation that did not
     * defer.</p>
     *
     * @param row the {@code AuthReplyOutbox} row to test; must not be {@code null}
     * @return {@code true} as a {@code boolean} when the row is unpublished, not abandoned and due
     */
    private static boolean isClaimable(AuthReplyOutbox row) {
        return !row.isPublished() && !row.isAbandoned()
                && (row.getNextAttemptAt() == null || !row.getNextAttemptAt().isAfter(fixedNow()));
    }

    /**
     * Reads the instant the publisher's fixed clock reports.
     *
     * @return the fixed instant as a {@code LocalDateTime} in coordinated universal time, never
     *     {@code null}
     */
    private static LocalDateTime fixedNow() {
        return LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
    }

    /**
     * Computes a reply deadline that has not yet passed.
     *
     * <p>Assumptions: five seconds is the reference's own reply deadline, which
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L750 expresses as
     * {@code MOVE 50 TO MQMD-EXPIRY} in a field denominated in TENTHS of a second. The unit is worth
     * stating because the receive wait at L242 is denominated in milliseconds, so two different numbers in
     * that program both mean five seconds and reading either unit onto the other is an easy error.</p>
     *
     * @return a deadline as a {@code LocalDateTime} five seconds ahead of the fixed clock, never
     *     {@code null}
     */
    private static LocalDateTime liveDeadline() {
        return fixedNow().plusSeconds(5L);
    }
}
