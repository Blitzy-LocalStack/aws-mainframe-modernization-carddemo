package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.repository.OutboxRepository;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Drives one reply through its whole publication lifecycle against a real engine and a scripted queue.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the publisher's correctness rests on two claims that only this tier can settle. The first
 * is that the CLAIM is a short transaction which COMMITS BEFORE the reply is sent, so no database
 * resource is held across a call to an external queue service. The second is that one pass over one row
 * advances the attempt counter EXACTLY ONCE, however many times the transport itself is retried inside
 * that pass. Both are properties of what the engine holds at a particular moment, and neither can be
 * observed from a mocked repository.
 *
 * <p>Refactoring Rationale: this class is an addition, and it joins two things that were previously
 * asserted apart. The claim was covered by a repository test that issued the claim and then read the
 * row; the failure and success transitions were covered by a publisher test over mocks. Between the two
 * sat the seam that matters -- the send -- and nothing crossed it. A double increment, or a claim that
 * had not committed when the send began, would have satisfied both halves separately while being wrong.
 *
 * <h2>How the seam is observed</h2>
 *
 * <p>Assumptions: the queue client is a scripted stub that can run a hook AT THE MOMENT OF SENDING, and
 * the hook reads the row through its OWN JDBC connection. That is the whole mechanism: a second
 * connection can only see the claim's effect if the claim's transaction has already committed, so
 * reading {@code attempts = 1} from outside while the send is in flight is a direct observation of the
 * seam rather than an inference about it. Alternatives Considered: asserting the absence of a lock by
 * probing for one from a second connection. Rejected, because readers are never blocked by a row lock
 * under this engine's concurrency control, so such a probe would pass whether the lock existed or not
 * -- it would be the worthless assertion this class exists to avoid.
 *
 * <p>Assumptions: the publisher is CONSTRUCTED by each case rather than injected as a bean, because the
 * cases vary its attempt ceiling and share a clock they advance. Nothing is lost: {@code drain()} is
 * deliberately not annotated as transactional -- it opens its own short transactions through a template
 * -- so there is no proxy for a bean declaration to add, and that absence is itself part of what is
 * under test here.
 *
 * <h2>Trade-offs</h2>
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's Failsafe configuration
 * includes exactly that suffix, so any other name would leave the class unrun. The suffix names the tier
 * rather than the subject.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
@SpringBootTest(
        classes = OutboxPublisherLifecycleRepositoryIT.OutboxLifecycleTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OutboxPublisherLifecycleRepositoryIT {

    /** The engine image, named by manifest digest so the version cannot drift: PostgreSQL 17.10-alpine. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The engine every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The instant every case starts from, so each observation is compared against a known clock. */
    private static final Instant START_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /** The reply destination the seeded rows carry. */
    private static final String REPLY_QUEUE_URL =
            "https://sqs.eu-west-1.amazonaws.com/000000000000/carddemo-pauth-reply-test.fifo";

    /** The correlation attribute the seeded rows carry. */
    private static final String CORRELATION_ID = "CORRELID0000000000000001";

    /** The ordering group the seeded rows share. */
    private static final String ORDER_GROUP_TOKEN = "GROUPTOKEN000000000000000000001";

    /** The deduplication attribute the seeded row carries. */
    private static final String DEDUPLICATION_TOKEN = "DEDUPTOKEN000000000000000000001";

    /**
     * The deduplication attribute of the OLDER of two replies for one card.
     *
     * <p>Assumptions: the two identities below are distinguishable at a glance in a failure message,
     * because the property they serve is an ORDER and a diagnosis that has to count trailing digits is a
     * diagnosis nobody makes correctly under pressure.</p>
     */
    private static final String HEAD_DEDUPLICATION_TOKEN = "DEDUPTOKEN00000000000000000HEAD";

    /** The deduplication attribute of the NEWER of two replies for one card. */
    private static final String FOLLOWER_DEDUPLICATION_TOKEN = "DEDUPTOKEN0000000000000FOLLOWER";

    /**
     * The reply body the seeded rows carry.
     *
     * <p>Assumptions: it is deliberately not a real encoded reply. Nothing in this class inspects the
     * body, and a realistic one would carry a card number into a file whose subject is scheduling.
     */
    private static final String OPAQUE_PAYLOAD = "OPAQUE-REPLY-BODY";

    /** How long a seeded reply stays publishable, well beyond every clock advance made here. */
    private static final long REPLY_DEADLINE_SECONDS = 86_400L;

    /** The batch size the publishers under test are built with. */
    private static final int BATCH_SIZE = 5;

    /** The per-pass row budget the publishers under test are built with. */
    private static final int MAX_ROWS_PER_DRAIN = 500;

    /** The poll interval the publishers under test are built with, which no case relies upon. */
    private static final long POLL_INTERVAL_MILLIS = 1_000L;

    /** The retention window the publishers under test are built with, which no case relies upon. */
    private static final int RETENTION_DAYS = 7;

    /**
     * The stall-alert threshold used by every case that is not about the escalation itself.
     *
     * <p>⚠️ Assumptions: this bounds nothing -- it decides only when a still-failing reply is reported
     * under {@code event=auth.reply.stalled}. Refactoring Rationale: it was an attempt CEILING at which
     * the row was abandoned, which lost a reply the committed decision says is owed and, because a
     * group's head is its lowest unpublished and unquarantined row, released the same card's later
     * replies past it.</p>
     */
    private static final int GENEROUS_STALL_ALERT_ATTEMPTS = 10;

    /**
     * The lease a claim writes into the next-attempt column, taken from the production constant's value.
     *
     * <p>Assumptions: two minutes, restated here because the production field is private. A case asserts
     * the leased instant is at least this far ahead, not that it equals it, so a later widening of the
     * production lease would not turn this class red for no reason.
     */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    /** The first retry delay, restated from the production constant so the backoff can be asserted. */
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);

    /** The real outbox repository, injected so the claim statements under test are the production ones. */
    @Autowired
    private OutboxRepository outbox;

    /** The manager the publisher opens its short transactions through. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The pool the JDBC observations take their own connections from. */
    @Autowired
    private DataSource dataSource;

    /** The template the seeding writes run in, which must commit before a case begins. */
    @Autowired
    private TransactionTemplate transactions;

    /** The clock the publisher reads, which a case advances to step past a backoff. */
    private AdvanceableClock clock;

    /** The scripted queue client, whose behaviour each case sets. */
    private RecordingSqsClient sqs;

    /**
     * Points the context's datasource and migration at the container this class started.
     *
     * @param registry the registry the framework supplies for late-bound properties
     */
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the table and rebuilds the clock and the queue stub, so no case inherits another's state.
     *
     * <p>Assumptions: the emptying runs in its OWN transaction, which has committed before a case
     * begins. A row left inside a case's transaction would be invisible to the JDBC observations, and a
     * case that then found nothing could not tell an absent write from an unseen one.
     */
    @BeforeEach
    void emptyTheOutbox() {
        this.transactions.executeWithoutResult(status -> this.outbox.deleteAllInBatch());
        this.clock = new AdvanceableClock(START_INSTANT);
        this.sqs = new RecordingSqsClient();
    }

    /**
     * The claim has already committed by the time the reply is handed to the queue.
     *
     * <p>Purpose: this is the property the design rests on. If the claim were part of the same
     * transaction as the send, the row's advanced attempt count would be invisible outside that
     * transaction until the send returned, and every other worker's progress would then wait on the
     * queue service's latency.
     *
     * <p>Assumptions: the observation is taken from a connection of its own, INSIDE the send. Seeing the
     * incremented counter there is only possible if the claiming transaction has committed; seeing the
     * publication instant still unset at the same moment shows the row was not marked before the work
     * was done. The two readings together are the seam.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the claim is committed and visible from another connection while the send is in flight")
    void theClaimIsCommittedBeforeTheReplyIsSent() {
        long outboxId = seedOnePendingReply();
        AtomicReference<RowSnapshot> duringSend = new AtomicReference<>();
        this.sqs.onSend(() -> duringSend.set(snapshotOf(outboxId)));

        int published = publisherWith(GENEROUS_STALL_ALERT_ATTEMPTS).drain();

        assertThat(published).as("the pass published the one reply it claimed").isEqualTo(1);
        RowSnapshot observed = duringSend.get();
        assertThat(observed)
                .as("the queue client was reached, so an observation was taken")
                .isNotNull();
        assertThat(observed.attempts())
                .as("a second connection sees the advanced counter, so the claim had committed")
                .isEqualTo(1);
        assertThat(observed.publishedAt())
                .as("and it sees no publication instant, so the row was not marked before the work")
                .isNull();
        assertThat(observed.nextAttemptAt())
                .as("the claim leased the row forward, so a concurrent pass would not take it")
                .isAfterOrEqualTo(LocalDateTime.ofInstant(START_INSTANT, ZoneOffset.UTC)
                        .plus(CLAIM_LEASE));
        assertThat(snapshotOf(outboxId).publishedAt())
                .as("and once the send returned, the publication was recorded")
                .isNotNull();
    }

    /**
     * A pass whose transport is retried still advances the attempt counter exactly once.
     *
     * <p>Purpose: this is the double increment the lifecycle has to rule out. The transport's own retry
     * lives inside one pass, and a pass that counted per transport call rather than per claim would cross
     * the configured stall-alert threshold in a fraction of the passes an operator reading that figure
     * expects, and would reach the capped backoff sooner than the configuration says -- the backoff being
     * computed from the same counter.
     *
     * <p>Assumptions: the scripted fault is a client-side transport fault, which the publisher's policy
     * classifies as transient and therefore DOES retry. That choice is what makes the case meaningful: a
     * fault the policy declined to retry would produce a single send and could not distinguish an
     * implementation that counted per claim from one that counted per call.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a retried transport advances the attempt counter once, not once per send")
    void aFailedSendAdvancesTheAttemptOnceAndSchedulesABackoff() {
        long outboxId = seedOnePendingReply();
        this.sqs.failEverySend();

        int published = publisherWith(GENEROUS_STALL_ALERT_ATTEMPTS).drain();

        assertThat(published).as("nothing was published").isZero();
        assertThat(this.sqs.sendCount())
                .as("the transport itself was retried, which is what makes the count below meaningful")
                .isEqualTo(2);
        RowSnapshot stored = snapshotOf(outboxId);
        assertThat(stored.attempts())
                .as("one pass over one row is ONE attempt, however many times the transport was tried")
                .isEqualTo(1);
        assertThat(stored.publishedAt()).as("the reply is not published").isNull();
        assertThat(stored.abandonedAt())
                .as("nor is it quarantined: no failure count ends a reply, and nothing in this service "
                        + "writes that column at all")
                .isNull();
        assertThat(stored.lastError())
                .as("the failure is kept as a diagnostic so an operator can see why")
                .isNotBlank();
        assertThat(stored.nextAttemptAt())
                .as("and the row is scheduled forward by the first backoff step")
                .isEqualTo(LocalDateTime.ofInstant(START_INSTANT, ZoneOffset.UTC)
                        .plus(FIRST_BACKOFF));
    }

    /**
     * A row scheduled forward by a backoff is not claimed again before that instant arrives.
     *
     * <p>Purpose: the backoff and the claim lease are written into the same column, so a pass that
     * ignored it would spin on a failing row at the poll interval and would also let two workers hold
     * one row at once. Asserting that the immediately following pass takes nothing is what shows the
     * column is honoured on the way back IN, not merely written on the way out.
     *
     * <p>Assumptions: the clock is NOT advanced between the two passes, so the second pass runs at the
     * same instant the first one did. That is the whole arrangement -- the row's scheduled instant is in
     * the future relative to that instant, and nothing else distinguishes the two passes.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a backed-off row is not reclaimed before its scheduled instant")
    void aBackedOffRowIsNotClaimedBeforeItsInstantArrives() {
        long outboxId = seedOnePendingReply();
        this.sqs.failEverySend();
        OutboxPublisher publisher = publisherWith(GENEROUS_STALL_ALERT_ATTEMPTS);
        publisher.drain();
        int sendsAfterFirstPass = this.sqs.sendCount();

        int published = publisher.drain();

        assertThat(published).as("the second pass published nothing").isZero();
        assertThat(this.sqs.sendCount())
                .as("the second pass did not reach the queue at all, so it claimed no row")
                .isEqualTo(sendsAfterFirstPass);
        assertThat(snapshotOf(outboxId).attempts())
                .as("and the counter did not move, so the row was left where the backoff put it")
                .isEqualTo(1);
    }

    /**
     * Once the backoff has elapsed the next pass publishes the reply and clears the diagnostic.
     *
     * <p>Purpose: this closes the lifecycle. A failure that could not be recovered from would make the
     * outbox a one-shot mechanism, and a recovery that left the earlier diagnostic in place would leave
     * an operator reading a published row as a failing one.
     *
     * <p>Assumptions: the attempt counter is asserted to be TWO rather than reset to zero. The count is
     * a record of how many passes the row has cost, and resetting it on success would remove the only
     * evidence that the queue had been unreachable.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the pass after the backoff publishes the reply and clears the diagnostic")
    void theNextPassPublishesAndClearsTheError() {
        long outboxId = seedOnePendingReply();
        this.sqs.failEverySend();
        OutboxPublisher publisher = publisherWith(GENEROUS_STALL_ALERT_ATTEMPTS);
        publisher.drain();
        assertThat(snapshotOf(outboxId).lastError())
                .as("the arrangement is a row that has already failed once")
                .isNotBlank();
        this.sqs.succeedEverySend();
        this.clock.advance(FIRST_BACKOFF.plusSeconds(1));

        int published = publisher.drain();

        assertThat(published).as("the recovered reply was published").isEqualTo(1);
        RowSnapshot stored = snapshotOf(outboxId);
        assertThat(stored.publishedAt())
                .as("the publication instant is the clock the pass ran at")
                .isEqualTo(LocalDateTime.ofInstant(
                        START_INSTANT.plus(FIRST_BACKOFF).plusSeconds(1), ZoneOffset.UTC));
        assertThat(stored.attempts())
                .as("two passes cost two attempts, and success does not erase the first")
                .isEqualTo(2);
        assertThat(stored.lastError())
                .as("the stale diagnostic is cleared, so a published row does not read as failing")
                .isNull();
        assertThat(stored.abandonedAt())
                .as("and no quarantine marker was written, that column being an operator's alone")
                .isNull();
    }

    /**
     * A row past the stall-alert threshold keeps being claimed, and is delivered when the queue returns.
     *
     * <p>⚠️ Purpose: this case replaces one that asserted the OPPOSITE -- that a row reaching a
     * configured attempt ceiling was abandoned rather than retried again. Refactoring Rationale: that
     * ceiling broke the guarantee §0.4.3 of the technical specification states as "a reply is published
     * for every committed authorization", so the property is inverted rather than tuned. Eligibility must
     * not expire: a reply is retried at its capped backoff until it is delivered, and crossing the
     * threshold changes only which event name the failure is reported under.
     *
     * <p>Assumptions: the threshold is narrowed to two for this case, so that the third pass is
     * unambiguously a pass BEYOND it. Reaching ten would take ten passes and nine clock advances to
     * establish what three establish exactly, and the threshold's own validation is asserted where it is
     * validated.
     *
     * <p>Assumptions: the recovery is asserted in the same case rather than in one of its own, because
     * "still claimable" and "eventually delivered" are one property in two halves and a case proving only
     * the first would pass for a publisher that claimed the row forever and never sent it.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a row past the stall-alert threshold is retried and finally published")
    void aRowPastTheStallAlertThresholdIsRetriedAndFinallyPublished() {
        long outboxId = seedOnePendingReply();
        this.sqs.failEverySend();
        OutboxPublisher publisher = publisherWith(2);
        publisher.drain();
        this.clock.advance(FIRST_BACKOFF.plusSeconds(1));
        publisher.drain();

        RowSnapshot atThreshold = snapshotOf(outboxId);
        assertThat(atThreshold.attempts())
                .as("the second pass took the row to the escalation threshold")
                .isEqualTo(2);
        assertThat(atThreshold.abandonedAt())
                .as("reaching the threshold escalates the row and must not retire it, since retiring it "
                        + "would lose an answer the committed decision says is owed")
                .isNull();
        assertThat(atThreshold.publishedAt())
                .as("and an undelivered reply must never read as delivered")
                .isNull();

        // WHY : Assumptions: the advance is generous rather than exactly the second backoff step, because
        //       the delay doubles with the attempt count and this case's subject is eligibility rather
        //       than the arithmetic of the ladder -- which aFailedSendAdvancesTheAttemptOnce... asserts
        //       precisely, at the one step where the value is unambiguous.
        this.clock.advance(Duration.ofHours(1));
        int publishedWhileStillFailing = publisher.drain();

        assertThat(publishedWhileStillFailing).as("the queue is still refusing, so nothing is sent").isZero();
        RowSnapshot pastThreshold = snapshotOf(outboxId);
        assertThat(pastThreshold.attempts())
                .as("a THIRD attempt was made past a threshold of two: eligibility does not expire")
                .isEqualTo(3);
        assertThat(pastThreshold.abandonedAt())
                .as("and the row is still not retired, however far past the threshold it now is")
                .isNull();

        this.sqs.succeedEverySend();
        this.clock.advance(Duration.ofHours(1));
        int publishedAfterRecovery = publisher.drain();

        assertThat(publishedAfterRecovery)
                .as("the recovered queue receives the reply that every earlier pass could not deliver")
                .isEqualTo(1);
        RowSnapshot delivered = snapshotOf(outboxId);
        assertThat(delivered.publishedAt())
                .as("the guarantee is discharged by delivery, not by a bound on how long it took")
                .isNotNull();
        assertThat(delivered.lastError())
                .as("and the stale diagnostic is cleared, so a delivered reply does not read as failing")
                .isNull();
    }

    /**
     * A head that cannot be sent holds its own card's later reply until the head itself is delivered.
     *
     * <p>⚠️ Purpose: this is the ordering half of the same guarantee, against the real claiming
     * statements rather than a double. Refactoring Rationale: it replaces a case that asserted a
     * quarantined row stays quarantined once the ceiling is raised -- true, but the arrangement it
     * described is one no code path can now produce, because the publisher no longer writes that column.
     * What needs asserting instead is the consequence the withdrawn ceiling had: the head claim derives a
     * group's head as its lowest unpublished, unquarantined identity, so retiring a failing head PROMOTED
     * the same card's next reply and delivered that card's sequence with a hole in it.
     *
     * <p>Assumptions: the observation is the deduplication identity of each SEND, not merely the
     * follower's stored state. A follower left unpublished by a pass that attempted it and failed would
     * satisfy a state-only assertion, and attempting it is already the reordering -- a first-in-first-out
     * queue orders by the sequence in which it accepts messages.
     *
     * <p>Assumptions: the follower's attempt counter is asserted to be zero, which is the strongest
     * available statement that the claim never offered it to a pass at all: the counter is advanced by
     * the claiming statement itself, so a zero cannot be explained by a send that was declined later.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a head that keeps failing holds its group, and is delivered before its follower")
    void aFailingHeadHoldsItsGroupUntilItIsDelivered() {
        long headId = seedPendingReply(HEAD_DEDUPLICATION_TOKEN);
        long followerId = seedPendingReply(FOLLOWER_DEDUPLICATION_TOKEN);
        this.sqs.failEverySend();
        OutboxPublisher publisher = publisherWith(2);

        for (int pass = 1; pass <= 3; pass++) {
            assertThat(publisher.drain())
                    .as("pass %d publishes nothing, the group's oldest reply being unsendable", pass)
                    .isZero();
            this.clock.advance(Duration.ofHours(1));
        }

        assertThat(this.sqs.sentDeduplicationIds())
                .as("every attempt of every pass was the HEAD, twice per pass for the transport's own "
                        + "retry, and the follower was never handed to the queue")
                .containsOnly(HEAD_DEDUPLICATION_TOKEN);
        assertThat(snapshotOf(followerId).attempts())
                .as("the claim never offered the follower to a pass, which is what keeps a later "
                        + "authorization from being answered before an earlier one for the same card")
                .isZero();
        assertThat(snapshotOf(followerId).publishedAt()).isNull();

        this.sqs.succeedEverySend();
        int published = publisher.drain();

        assertThat(published)
                .as("the recovered pass publishes the head and then advances the group to its follower")
                .isEqualTo(2);
        assertThat(this.sqs.sentDeduplicationIds().subList(6, 8))
                .as("and it does so IN ORDER: the held reply first, the one behind it second")
                .containsExactly(HEAD_DEDUPLICATION_TOKEN, FOLLOWER_DEDUPLICATION_TOKEN);
        assertThat(snapshotOf(headId).publishedAt()).isNotNull();
        assertThat(snapshotOf(followerId).publishedAt()).isNotNull();
    }

    /**
     * Builds one publisher over the real repository, the scripted queue and the advanceable clock.
     *
     * @param stallAlertAttempts the attempt count at which this publisher escalates a still-unpublished
     *     reply, which changes the diagnostic it writes and never the row's eligibility
     * @return a publisher whose only doubles are the queue client and the clock
     */
    private OutboxPublisher publisherWith(int stallAlertAttempts) {
        return new OutboxPublisher(this.outbox, this.sqs, this.clock, this.transactionManager,
                BATCH_SIZE, POLL_INTERVAL_MILLIS, RETENTION_DAYS, MAX_ROWS_PER_DRAIN,
                stallAlertAttempts);
    }

    /**
     * Writes one publishable reply row and returns its key, in a transaction that has committed.
     *
     * @return the surrogate key of the seeded row
     */
    private long seedOnePendingReply() {
        return seedPendingReply(DEDUPLICATION_TOKEN);
    }

    /**
     * Writes one publishable reply row under a stated deduplication identity, and returns its key.
     *
     * <p>Assumptions: the ordering group is always the shared one, so two calls produce two replies for
     * ONE card in the order they were written. That is the arrangement a case needs to state anything
     * about per-card order at all -- the group is what the claim derives a head over, and two rows in two
     * groups would be independent of each other by construction.</p>
     *
     * @param deduplicationId the deduplication identity to store on the row, which is what a case reads
     *     back off the send to tell one reply of the group from the other
     * @return the surrogate key of the seeded row
     * @throws IllegalStateException if the save returned no row, so no key could be reported
     */
    private long seedPendingReply(String deduplicationId) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(START_INSTANT, ZoneOffset.UTC);
        AuthReplyOutbox row = new AuthReplyOutbox(REPLY_QUEUE_URL, CORRELATION_ID, ORDER_GROUP_TOKEN,
                deduplicationId, OPAQUE_PAYLOAD,
                createdAt.plusSeconds(REPLY_DEADLINE_SECONDS), createdAt);
        AuthReplyOutbox stored = this.transactions.execute(status -> this.outbox.save(row));
        return Optional.ofNullable(stored)
                .map(AuthReplyOutbox::getOutboxId)
                .orElseThrow(() -> new IllegalStateException("the seeded reply row has no key"));
    }

    /**
     * Reads the four lifecycle columns of one row on a connection of its own.
     *
     * <p>Assumptions: a connection of its own, and never the repository. A repository read would share
     * the persistence context the publisher wrote through, so an instance still held there could answer
     * from the identity map and report a state the engine never stored -- which is exactly the reading
     * this class must not make.
     *
     * @param outboxId the row to read
     * @return the row's attempt count, publication instant, abandonment instant, diagnostic and
     *     scheduled instant
     * @throws IllegalStateException if the row is absent, or the read could not be run
     */
    private RowSnapshot snapshotOf(long outboxId) {
        String query = "SELECT attempts, published_at, abandoned_at, last_error, next_attempt_at"
                + " FROM auth_reply_outbox WHERE outbox_id = ?";
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, outboxId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("no reply row stands at key " + outboxId);
                }
                return new RowSnapshot(rows.getInt(1), timestamp(rows, 2), timestamp(rows, 3),
                        rows.getString(4), timestamp(rows, 5));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the reply row at key " + outboxId
                    + " could not be read", failure);
        }
    }

    /**
     * Reads one nullable timestamp column without letting an absent value become an epoch instant.
     *
     * @param rows the result set positioned on the row
     * @param column the one-based column ordinal
     * @return the stored local instant, or {@code null} where the column holds no value
     * @throws SQLException if the column cannot be read
     */
    private static LocalDateTime timestamp(ResultSet rows, int column) throws SQLException {
        return rows.getObject(column, LocalDateTime.class);
    }

    /**
     * The five lifecycle columns of one reply row, as one immutable reading.
     *
     * <p>Assumptions: they are read and carried TOGETHER, in one statement, because a case that read
     * them one at a time could observe two different moments of a row that another pass was moving.
     *
     * @param attempts how many passes have claimed the row
     * @param publishedAt when the reply was published, or {@code null} while it is pending
     * @param abandonedAt when the row was retired unpublished, or {@code null} while it is live
     * @param lastError the diagnostic from the most recent failure, or {@code null} where there is none
     * @param nextAttemptAt the instant from which the row may next be claimed
     */
    private record RowSnapshot(int attempts, LocalDateTime publishedAt, LocalDateTime abandonedAt,
            String lastError, LocalDateTime nextAttemptAt) {
    }

    /**
     * A clock a case can move forward, so a backoff can be stepped over without waiting for it.
     *
     * <p>Assumptions: it moves only when a case moves it. A clock that advanced on its own would make
     * every instant assertion in this class approximate, and the publication instant is one of the
     * values under test.
     */
    static final class AdvanceableClock extends Clock {

        /** The instant this clock currently reads. */
        private Instant now;

        /**
         * Starts the clock at one instant.
         *
         * @param start the instant to read until a case moves it
         */
        AdvanceableClock(Instant start) {
            this.now = start;
        }

        /**
         * Moves the clock forward.
         *
         * @param by how far forward to move
         */
        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        /**
         * Reports the instant this clock currently reads.
         *
         * @return the current instant
         */
        @Override
        public Instant instant() {
            return this.now;
        }

        /**
         * Reports the zone this clock reads in.
         *
         * @return always the offset the publisher itself converts through
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Answers a clock in another zone.
         *
         * <p>Assumptions: it answers itself rather than a re-zoned copy, because the publisher converts
         * to a fixed offset itself and never asks for another zone. A copy would need its own advance
         * state, and two clocks a case could move independently is a fault waiting to be written.
         *
         * @param zone the zone requested, which is ignored
         * @return this clock
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /**
     * A scripted queue client that records its sends and can run a hook at the moment of sending.
     *
     * <p>Assumptions: it is hand-written rather than a mocking framework's double, for one reason that
     * matters here -- the hook runs INSIDE the send, which is the only moment at which the seam between
     * the claim's transaction and the network call is observable.
     */
    static final class RecordingSqsClient implements SqsClient {

        /** How many sends have been asked for. */
        private final AtomicInteger sends = new AtomicInteger();

        /**
         * The deduplication identity of every send asked for, in call order.
         *
         * <p>Assumptions: the identities are recorded rather than only counted, because per-card ORDER is
         * a statement about which reply reached the queue first and a count cannot express it. The list is
         * synchronized because the publisher's pass and a hook running inside a send are not guaranteed to
         * be the same thread.</p>
         */
        private final List<String> deduplicationIds = Collections.synchronizedList(new ArrayList<>());

        /** Whether every send is to be refused. */
        private volatile boolean failing;

        /** What to run at the moment of sending, or {@code null} to run nothing. */
        private volatile Runnable duringSend;

        /** Refuses every later send with a fault the publisher classifies as transient. */
        void failEverySend() {
            this.failing = true;
        }

        /** Accepts every later send. */
        void succeedEverySend() {
            this.failing = false;
        }

        /**
         * Sets what to run at the moment of sending.
         *
         * @param hook the action to run inside each send
         */
        void onSend(Runnable hook) {
            this.duringSend = hook;
        }

        /**
         * Reports how many sends have been asked for.
         *
         * @return the running count
         */
        int sendCount() {
            return this.sends.get();
        }

        /**
         * Reports the deduplication identity of every send asked for, in call order.
         *
         * @return a copy of the recorded identities, never {@code null}
         */
        List<String> sentDeduplicationIds() {
            return List.copyOf(this.deduplicationIds);
        }

        /**
         * Records the send, runs the hook, and then either accepts or refuses it.
         *
         * <p>Assumptions: the refusal is a CLIENT-side transport fault, which the publisher's retry
         * policy classifies as transient. That is deliberate: it exercises the transport retry inside
         * one pass, which is the arrangement the attempt-counter assertion needs.
         *
         * @param request the send the publisher composed
         * @return an accepted send's response
         * @throws SdkClientException if this stub has been told to refuse
         */
        @Override
        public SendMessageResponse sendMessage(SendMessageRequest request) {
            int ordinal = this.sends.incrementAndGet();
            this.deduplicationIds.add(request.messageDeduplicationId());
            Runnable hook = this.duringSend;
            if (hook != null) {
                hook.run();
            }
            if (this.failing) {
                throw SdkClientException.create("the scripted queue refused send " + ordinal);
            }
            return SendMessageResponse.builder().messageId("scripted-" + ordinal).build();
        }

        /**
         * Names the service this stub stands for.
         *
         * @return the queue service's own name constant
         */
        @Override
        public String serviceName() {
            return SqsClient.SERVICE_NAME;
        }

        /**
         * Releases nothing, this stub holding no resource.
         */
        @Override
        public void close() {
            // WHY : Assumptions: empty by construction rather than by omission. This stub holds no
            //   connection, no thread and no buffer, so there is nothing a close could release; the
            //   method exists because the client contract declares it.
        }
    }

    /**
     * The narrowest context that can host the real outbox repository against a real engine.
     *
     * <p>Assumptions: the publisher is NOT declared here. Each case builds its own, because the cases
     * vary its attempt ceiling and hand it a clock they control; and because {@code drain()} opens its
     * own short transactions rather than relying on an annotation, a bean declaration would add no
     * proxy the cases need.
     *
     * <p>Assumptions: the two auto-configurations excluded are the resource server, which would demand
     * an issuer this class has no use for, and the queue integration, which would build a real client
     * beside the scripted one.
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.authorization.domain")
    @EnableJpaRepositories("com.carddemo.authorization.repository")
    static class OutboxLifecycleTestApplication {

        /**
         * A template for the seeding and emptying writes, which must commit before a case begins.
         *
         * @param manager the context's transaction manager
         * @return a template that begins and commits one transaction per call
         */
        @Bean
        TransactionTemplate seedingTransactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
