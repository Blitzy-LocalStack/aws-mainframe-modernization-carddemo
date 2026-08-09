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

    /** The attempt ceiling used by every case except the one that asserts abandonment. */
    private static final int GENEROUS_MAX_ATTEMPTS = 10;

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

        int published = publisherWith(GENEROUS_MAX_ATTEMPTS).drain();

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
     * lives inside one pass, and a pass that counted per transport call rather than per claim would burn
     * a permanently unreachable queue's whole attempt ceiling in a handful of passes and abandon replies
     * that were never given the tries the configuration promised.
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

        int published = publisherWith(GENEROUS_MAX_ATTEMPTS).drain();

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
                .as("nor is it abandoned, the ceiling being far above one attempt")
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
        OutboxPublisher publisher = publisherWith(GENEROUS_MAX_ATTEMPTS);
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
        OutboxPublisher publisher = publisherWith(GENEROUS_MAX_ATTEMPTS);
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
        assertThat(stored.abandonedAt()).as("and the row is not abandoned").isNull();
    }

    /**
     * A row that reaches the attempt ceiling is abandoned rather than retried once more.
     *
     * <p>Purpose: the ceiling is what stops a permanently unreachable queue holding a group's head
     * forever, and this case covers the first half of that -- that reaching the ceiling RECORDS the
     * abandonment instead of retrying once more. The second half, that a recorded abandonment is
     * respected by later passes, needs an arrangement in which the attempt comparison is not already
     * doing the work, and is asserted in the case below.
     *
     * <p>Assumptions: the ceiling is narrowed to two for this case. Reaching a ceiling of ten would take
     * ten passes and eight clock advances to assert a property that two passes establish exactly, and
     * the ceiling is a configured value whose own validation is asserted where it is validated.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a row at the attempt ceiling is abandoned rather than retried again")
    void aRowAtTheCeilingIsAbandoned() {
        long outboxId = seedOnePendingReply();
        this.sqs.failEverySend();
        OutboxPublisher publisher = publisherWith(2);
        publisher.drain();
        this.clock.advance(FIRST_BACKOFF.plusSeconds(1));

        publisher.drain();

        RowSnapshot abandoned = snapshotOf(outboxId);
        assertThat(abandoned.attempts()).as("the second pass took the row to the ceiling").isEqualTo(2);
        assertThat(abandoned.abandonedAt())
                .as("reaching the ceiling retires the row rather than retrying it again")
                .isNotNull();
        assertThat(abandoned.publishedAt())
                .as("and abandonment is recorded apart from publication, so neither reads as the other")
                .isNull();

    }

    /**
     * An abandoned row stays abandoned even after an operator raises the attempt ceiling.
     *
     * <p>Purpose: this is the arrangement that makes the abandonment predicate load-bearing rather than
     * decorative. While the ceiling is unchanged, a row at the ceiling is already excluded by the
     * attempt comparison alone, so a pass that ignored abandonment would still behave correctly and no
     * assertion could tell. Raising the ceiling -- which is a configured value and therefore a real
     * operational act -- removes that cover: the row's attempt count is now BELOW the ceiling, and only
     * the abandonment keeps it out.
     *
     * <p>Refactoring Rationale: this case exists because a mutation exposed the gap. Deleting the
     * claim's abandonment predicate left the previous case green, since the ceiling was doing the work.
     * The property under test is "an abandoned reply is never sent", not "one particular predicate is
     * present", so the case was arranged until it could fail for the right reason.
     *
     * <p>Assumptions: the second publisher is a NEW instance with the wider ceiling, which is what an
     * operator raising the configured value and restarting the service would produce. The row itself is
     * untouched between the two passes.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an abandoned row is not claimed even after the attempt ceiling is raised")
    void anAbandonedRowSurvivesTheCeilingBeingRaised() {
        long outboxId = seedOnePendingReply();
        this.sqs.failEverySend();
        OutboxPublisher narrow = publisherWith(2);
        narrow.drain();
        this.clock.advance(FIRST_BACKOFF.plusSeconds(1));
        narrow.drain();
        assertThat(snapshotOf(outboxId).abandonedAt())
                .as("the arrangement is a row that has already been abandoned")
                .isNotNull();
        int sendsWhileNarrow = this.sqs.sendCount();

        this.sqs.succeedEverySend();
        this.clock.advance(Duration.ofDays(1));
        int published = publisherWith(GENEROUS_MAX_ATTEMPTS).drain();

        assertThat(published)
                .as("the wider ceiling publishes nothing, the row's fate having been settled")
                .isZero();
        assertThat(this.sqs.sendCount())
                .as("and the queue is not reached, so the abandoned row was not claimed again")
                .isEqualTo(sendsWhileNarrow);
        RowSnapshot stored = snapshotOf(outboxId);
        assertThat(stored.attempts())
                .as("nor did its counter move, even though it now sits below the ceiling")
                .isEqualTo(2);
        assertThat(stored.publishedAt())
                .as("and it was certainly not published under the wider ceiling")
                .isNull();
    }

    /**
     * Builds one publisher over the real repository, the scripted queue and the advanceable clock.
     *
     * @param maxAttempts the attempt ceiling this publisher is to enforce
     * @return a publisher whose only doubles are the queue client and the clock
     */
    private OutboxPublisher publisherWith(int maxAttempts) {
        return new OutboxPublisher(this.outbox, this.sqs, this.clock, this.transactionManager,
                BATCH_SIZE, POLL_INTERVAL_MILLIS, RETENTION_DAYS, MAX_ROWS_PER_DRAIN, maxAttempts);
    }

    /**
     * Writes one publishable reply row and returns its key, in a transaction that has committed.
     *
     * @return the surrogate key of the seeded row
     */
    private long seedOnePendingReply() {
        LocalDateTime createdAt = LocalDateTime.ofInstant(START_INSTANT, ZoneOffset.UTC);
        AuthReplyOutbox row = new AuthReplyOutbox(REPLY_QUEUE_URL, CORRELATION_ID, ORDER_GROUP_TOKEN,
                DEDUPLICATION_TOKEN, OPAQUE_PAYLOAD,
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
