package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.mapper.AuthorizationMessageMapper;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that one authorization decision and every row it produces are ONE unit of work, on a real engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: divergence D-5 is the whole reason this context has an outbox. The reference consumer
 * commits its database work and publishes its reply as two separate acts -- {@code cbl/COPAUA0C.cbl}
 * commits at L335 and puts the reply with a no-syncpoint option at L753 -- so a failure between the two
 * loses a reply the data says was produced. The migration closes that window by committing the reply AS A
 * ROW alongside the decision. That claim is about one transaction spanning three tables, so only a real
 * transaction manager over a real engine can answer it, and this class is where it is answered.
 *
 * <p>Refactoring Rationale: this class is an addition. The decision path was covered only by a unit test
 * over mocked repositories, which can establish which collaborator was called and in what order and can
 * establish nothing about what the database retains. Four properties were therefore documented and
 * unproven: that the summary, the authorization and the outbox row commit together; that a failure
 * discards all three rather than some; that a redelivered request adds no second authorization; and that
 * a card the cross-reference cannot resolve records no authorization while still producing a reply. Each
 * of those is a different arrangement of rows across three tables, and a mock holds none.
 *
 * <h2>What is real here, and what is not</h2>
 *
 * <p>Assumptions: the repositories, the transaction manager, the schema and the engine are real; the
 * ACCOUNT CONTEXT is a stub. That split is not a convenience. The account, customer and card
 * cross-reference records belong to other bounded contexts and are read over HTTP, so there is no table
 * here to seed, and a test that seeded one would be asserting another service's schema.
 *
 * <p>Assumptions: there is no queue in this class at all, and that is the point of the outbox. The
 * listener's publication IS a row; what later reaches the queue is the publisher's concern and is
 * asserted where the publisher is.
 *
 * <p>Assumptions: every assertion is made through plain JDBC on a connection of its own. A repository
 * read would share the persistence context the writes went through, so an instance still held there
 * could answer from the identity map and report a row the engine never stored -- which is the exact
 * failure this class exists to detect.
 *
 * <h2>Trade-offs</h2>
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's Failsafe configuration
 * includes exactly that suffix, so any other name would leave the class unrun. The suffix names the TIER
 * it belongs to, not the subject it exercises.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
@SpringBootTest(
        classes = AuthorizationDecisionUnitOfWorkRepositoryIT.DecisionTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AuthorizationDecisionUnitOfWorkRepositoryIT {

    /** The engine image, named by manifest digest so the version cannot drift: PostgreSQL 17.10-alpine. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The engine every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The instant the listener's clock is fixed at, so every key this class produces is predictable. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /** The reply destination the allowlist admits, which is the only one a reply may be routed to. */
    static final String ALLOWED_REPLY_QUEUE =
            "https://sqs.eu-west-1.amazonaws.com/000000000000/carddemo-pauth-reply-test.fifo";

    /**
     * The window size the listener under test is configured with.
     *
     * <p>Assumptions: generous rather than narrow, so no case here ever fills a window. Refactoring
     * Rationale: this was one, because the window boundary used to be the only collaborator the listener
     * invoked AFTER all three writes and still inside its transaction, and a window of one fired it on the
     * message under test. The reservation now happens on ADMISSION -- as the handler's first statement,
     * which is what bounds the messages the container is allowed to hand out rather than the ones it has
     * finished -- so the boundary can no longer fail a request after its writes, and a narrow window here
     * would only fire the boundary between cases sharing this context. The bound's own arithmetic is
     * asserted against its real value in {@code AuthorizationRequestListenerTest}, so nothing is lost by
     * keeping the window out of the way here.
     */
    private static final int GENEROUS_WINDOW = 1_000;

    /** The card the stubbed cross-reference resolves. */
    static final String RESOLVED_CARD = "4111111111111111";

    /** A card the stubbed cross-reference does not resolve, which drives the not-found decline. */
    private static final String UNRESOLVED_CARD = "4000000000000000";

    /** The transaction identifier every request in this class carries. */
    private static final String TRANSACTION_ID = "TXN000000000001";

    /** The amount every request in this class carries, comfortably inside the stubbed credit room. */
    private static final String REQUEST_AMOUNT = "100.99";

    /** The account the resolved card belongs to. */
    static final long ACCOUNT_ID = 10_000_000_301L;

    /** The customer that account belongs to. */
    static final long CUSTOMER_ID = 451L;

    /** The credit limit the stubbed account carries. */
    static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** The cash credit limit the stubbed account carries. */
    static final BigDecimal CASH_LIMIT = new BigDecimal("1000.00");

    /** The balance the stubbed account carries, leaving room for the amount used here. */
    static final BigDecimal CURRENT_BALANCE = new BigDecimal("100.00");

    /** The listener under test, injected so the transactional proxy is the object exercised. */
    @Autowired
    private AuthorizationRequestListener listener;

    /** The summary repository, used only to empty the tables between cases. */
    @Autowired
    private PendingAuthSummaryRepository summaries;

    /** The authorization repository, used only to empty the tables between cases. */
    @Autowired
    private PendingAuthDetailRepository details;

    /** The outbox repository, used only to empty the table between cases. */
    @Autowired
    private OutboxRepository outbox;

    /** The pool the JDBC assertions take their own connections from. */
    @Autowired
    private DataSource dataSource;

    /** The template the emptying writes run in, which must commit before a case begins. */
    @Autowired
    private TransactionTemplate transactions;

    /** The latch the window boundary reads, which a case arms to make the boundary refuse. */
    @Autowired
    private AtomicBoolean replyMappingRefuses;

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
     * Empties all three tables and disarms the window boundary, so no case inherits another's state.
     *
     * <p>Assumptions: the emptying runs in its OWN transaction, which has committed before any case
     * begins. Rows left inside a case's own transaction would be invisible to the JDBC assertions, and a
     * case that then found nothing could not tell an absent write from an unseen one.
     *
     * <p>Assumptions: the order is outbox, then authorization, then summary. The authorization carries a
     * foreign key to the summary, so removing the parent first would be refused.
     */
    @BeforeEach
    void emptyEveryTable() {
        this.transactions.executeWithoutResult(status -> {
            this.outbox.deleteAllInBatch();
            this.details.deleteAllInBatch();
            this.summaries.deleteAllInBatch();
        });
        this.replyMappingRefuses.set(false);
    }

    /**
     * One decision leaves the summary, the authorization and the reply row all committed.
     *
     * <p>Assumptions: the account holds no summary before the request, so this also covers the CREATE arm
     * of the summary write -- the arm that runs the first time an account is authorized -- and the
     * counters are read back to show the decision actually reached them rather than merely that a row
     * appeared.
     *
     * <p>Assumptions: all three tables are asserted in ONE case rather than in three. The property is
     * that they arrive TOGETHER; three cases each asserting one table would pass against an
     * implementation that committed them in three separate transactions, which is precisely the state
     * divergence D-5 exists to prevent.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("one decision commits the summary, the authorization and the reply row together")
    void theThreeWritesCommitTogether() {
        this.listener.onRequest(messageFor(RESOLVED_CARD));

        assertThat(rowCount("pending_auth_summary"))
                .as("the first authorization of an account creates its summary")
                .isEqualTo(1);
        assertThat(intColumn("SELECT approved_auth_cnt FROM pending_auth_summary"))
                .as("the approval is counted on the summary")
                .isEqualTo(1);
        assertThat(decimalColumn("SELECT approved_auth_amt FROM pending_auth_summary"))
                .as("and its amount is accumulated exactly, at the scale the column declares")
                .isEqualByComparingTo(REQUEST_AMOUNT);
        assertThat(decimalColumn("SELECT credit_balance FROM pending_auth_summary"))
                .as("an approval also holds the amount against the account's credit room,"
                        + " transcribing cbl/COPAUA0C.cbl L814-L817")
                .isEqualByComparingTo(REQUEST_AMOUNT);
        assertThat(rowCount("pending_auth_detail"))
                .as("the authorization itself is recorded")
                .isEqualTo(1);
        assertThat(textColumn("SELECT match_status FROM pending_auth_detail"))
                .as("an approved authorization is recorded pending a match")
                .isEqualTo(PendingAuthDetail.MATCH_STATUS_PENDING);
        assertThat(rowCount("auth_reply_outbox"))
                .as("the reply is committed as a row, which is the whole of divergence D-5")
                .isEqualTo(1);
        assertThat(textColumn("SELECT payload FROM auth_reply_outbox"))
                .as("the committed reply carries the card and the transaction it answers")
                .contains(RESOLVED_CARD)
                .contains(TRANSACTION_ID);
    }

    /**
     * A card the cross-reference cannot resolve records no authorization and still produces a reply.
     *
     * <p>Purpose: this asymmetry is the baseline's, and it is easy to migrate wrongly in either
     * direction. {@code cbl/COPAUA0C.cbl} gates the database write on the cross-reference outcome and
     * gates the reply on nothing, so an unknown card is answered and nothing is recorded against it. An
     * implementation that recorded the decline would attribute an authorization to an account it could
     * not identify -- and could not satisfy the authorization's own foreign key while doing so. One that
     * suppressed the reply would leave the requester waiting for an answer the reference gives it.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unresolved card records no authorization and still commits a reply")
    void anUnresolvedCardRecordsNothingAndStillReplies() {
        this.listener.onRequest(messageFor(UNRESOLVED_CARD));

        assertThat(rowCount("pending_auth_detail"))
                .as("the record is gated on the cross-reference, which did not resolve")
                .isZero();
        assertThat(rowCount("pending_auth_summary"))
                .as("no summary is created for an account that was never identified")
                .isZero();
        assertThat(rowCount("auth_reply_outbox"))
                .as("the reply is gated on nothing, so the requester is still answered")
                .isEqualTo(1);
        assertThat(textColumn("SELECT payload FROM auth_reply_outbox"))
                .as("and the answer names the card that was asked about")
                .contains(UNRESOLVED_CARD);
    }

    /**
     * A redelivered request records no second authorization and republishes the recorded answer.
     *
     * <p>Purpose: delivery is at-least-once, so a redelivery is not an edge case but the ordinary
     * consequence of the transport. What must not happen is a second authorization row, a second
     * contribution to the account's counters, or a different answer.
     *
     * <p>Assumptions: a SECOND outbox row is expected here and is correct. The reply has to be published
     * again, because the first publication may be the very thing that was lost; the duplicate is
     * suppressed at the queue on the deduplication token, which is why the two rows are asserted to carry
     * the SAME token. A case expecting one row would be asserting that a redelivery is answered with
     * silence, which is the failure mode the reference has and the migration removes.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a redelivered request adds no authorization and republishes the same answer")
    void aRedeliveredRequestAddsNoAuthorization() {
        Message<String> message = messageFor(RESOLVED_CARD);

        this.listener.onRequest(message);
        this.listener.onRequest(message);

        assertThat(rowCount("pending_auth_detail"))
                .as("the second delivery finds the decision already recorded")
                .isEqualTo(1);
        assertThat(intColumn("SELECT approved_auth_cnt FROM pending_auth_summary"))
                .as("the account's counters must not move twice for one authorization")
                .isEqualTo(1);
        assertThat(decimalColumn("SELECT credit_balance FROM pending_auth_summary"))
                .as("nor may the credit room be held against twice")
                .isEqualByComparingTo(REQUEST_AMOUNT);
        assertThat(rowCount("auth_reply_outbox"))
                .as("the answer is republished, because the first publication may be what was lost")
                .isEqualTo(2);
        assertThat(textColumnsOf("SELECT DISTINCT deduplication_id FROM auth_reply_outbox"))
                .as("both rows carry one token, so the queue suppresses the duplicate")
                .hasSize(1);
    }

    /**
     * A failure after the three writes leaves none of them behind.
     *
     * <p>Purpose: this is the rollback half of divergence D-5, and the half that fails silently. Three
     * rows in three tables are written through two different mechanisms -- two dirty-checked entities and
     * one accumulation statement issued directly -- and if any one of them escaped the transaction the
     * result would be a reply queued for a decision the database does not hold, which is worse than the
     * lost reply the outbox was introduced to prevent.
     *
     * <p>Assumptions: the lever is the REPLY MAPPING. It is injected, it is reached after the summary
     * accumulation and the authorization insert and before the outbox row that would carry the reply, and
     * a failure there is a real possibility rather than a contrivance -- it validates the reply it builds
     * and refuses one it cannot vouch for. Failing at that point is the strongest arrangement this class
     * can make: two writes are already pending when it raises, so a boundary that did not enclose them
     * would leave a decision behind, and the third write is the one whose absence-together-with-the-others
     * the property is about.
     *
     * <p>Refactoring Rationale: the lever was the window boundary, which used to be invoked after all
     * three writes because the window place was reserved on COMPLETION. The reservation moved to
     * ADMISSION -- the handler's first statement -- so that the bound governs what the container may hand
     * out rather than what it has finished, and the boundary is consequently no longer reachable after any
     * write at all. Left as it was, this case armed a lever the handler now passes before it writes
     * anything and, at the production window arithmetic, never reached on a single message: nothing raised
     * and the case failed while the atomicity it guards was intact.
     *
     * <p>Alternatives Considered: rolling back an ambient transaction, as the sibling fraud-marking test
     * does. That cannot work here, because this listener declares {@code Propagation.REQUIRES_NEW} and so
     * suspends any transaction its caller holds and commits its own regardless of what the caller later
     * decides -- which is itself worth knowing, and is why the two classes reach for different levers.
     * Alternatives Considered: provoking a constraint violation on the outbox insert instead. Rejected
     * because that table declares no constraint a well-formed reply can violate, so the fixture would have
     * to be malformed for a reason unrelated to the property under test.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failure after the three writes rolls back all of them")
    void aFailureAfterTheWritesRollsBackAllThree() {
        this.replyMappingRefuses.set(true);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> this.listener.onRequest(messageFor(RESOLVED_CARD)))
                .withMessageContaining("reply mapping refused");

        assertThat(rowCount("pending_auth_summary"))
                .as("the summary write must be enlisted in the listener's own transaction")
                .isZero();
        assertThat(rowCount("pending_auth_detail"))
                .as("and so must the authorization")
                .isZero();
        assertThat(rowCount("auth_reply_outbox"))
                .as("a reply left behind by a rolled-back decision is the exact fault D-5 prevents")
                .isZero();
    }

    /**
     * Builds one well-formed request message for a card, routed to the allowlisted reply queue.
     *
     * <p>Assumptions: the declared content type is part of what makes the message well-formed, not a
     * decoration on the fixture. The payload is a positional record, so the consumer refuses a request
     * that declares no format rather than reading fields it cannot know are the fields the producer sent;
     * a helper that omitted the attribute would exercise that refusal in every case here and none of them
     * would reach the unit of work they are about.</p>
     *
     * @param cardNum the card the request names
     * @return a message the listener will accept
     */
    private static Message<String> messageFor(String cardNum) {
        AuthRequest request = new AuthRequest("250801", "104530", cardNum, "0100", "1230", "0100",
                "POS001", "000000", Money.of(REQUEST_AMOUNT), "5411", "840", "05",
                "MERCHANT0000001", "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000",
                TRANSACTION_ID);
        return MessageBuilder.withPayload(CsvAuthCodec.encodeRequest(request))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_CONTENT_TYPE,
                        AuthorizationRequestListener.CONTENT_TYPE_CSV)
                .build();
    }

    /**
     * Counts the rows of one table on a connection of its own.
     *
     * @param table the unqualified table name
     * @return how many rows the table holds
     */
    private int rowCount(String table) {
        return intColumn("SELECT count(*) FROM " + table);
    }

    /**
     * Runs one single-value integer query on a connection of its own.
     *
     * @param query the query to run
     * @return the first column of the first row it answered
     * @throws IllegalStateException if the query answered no row, or could not be run
     */
    private int intColumn(String query) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("no row answered " + query);
            }
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * Runs one single-value numeric query on a connection of its own.
     *
     * <p>Assumptions: the value is read as a {@code BigDecimal} rather than through any binary floating
     * type, because these columns hold money and the scale the engine stored is part of what is being
     * asserted.
     *
     * @param query the query to run
     * @return the first column of the first row it answered
     * @throws IllegalStateException if the query answered no row, or could not be run
     */
    private BigDecimal decimalColumn(String query) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("no row answered " + query);
            }
            return rows.getBigDecimal(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * Runs one single-value character query on a connection of its own.
     *
     * @param query the query to run
     * @return the first column of the first row, trimmed of any fixed-width padding
     * @throws IllegalStateException if the query answered no row, or could not be run
     */
    private String textColumn(String query) {
        List<String> values = textColumnsOf(query);
        if (values.isEmpty()) {
            throw new IllegalStateException("no row answered " + query);
        }
        return values.get(0);
    }

    /**
     * Runs one character query on a connection of its own and collects every value it answered.
     *
     * @param query the query to run
     * @return the first column of every row, each trimmed of any fixed-width padding
     * @throws IllegalStateException if the query could not be run
     */
    private List<String> textColumnsOf(String query) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                String value = rows.getString(1);
                values.add(value == null ? null : value.trim());
            }
            return values;
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * A stubbed account context, standing in for the two services this one reads over HTTP.
     *
     * <p>Assumptions: exactly one card resolves, and it is fixed rather than configurable. That is what
     * lets one class exercise both the recorded path and the unresolved path without a switch a case has
     * to remember to set, and it makes the two cases readable side by side.
     */
    static final class StubAccountContext implements AccountContextClient {

        /**
         * Resolves the one card this stub knows and nothing else.
         *
         * @param cardNum the card the listener asked about
         * @return the cross-reference for the known card, or empty for any other
         */
        @Override
        public Optional<CardXref> findCardXref(String cardNum) {
            return RESOLVED_CARD.equals(cardNum)
                    ? Optional.of(new CardXref(ACCOUNT_ID, CUSTOMER_ID))
                    : Optional.empty();
        }

        /**
         * Answers the one account this stub knows and nothing else.
         *
         * @param accountId the account the listener asked about
         * @return the known account's limits and balance, or empty for any other
         */
        @Override
        public Optional<Account> findAccount(long accountId) {
            return accountId == ACCOUNT_ID
                    ? Optional.of(new Account(CREDIT_LIMIT, CASH_LIMIT, CURRENT_BALANCE))
                    : Optional.empty();
        }

        /**
         * Confirms the one customer this stub knows and nothing else.
         *
         * @param customerId the customer the listener asked about
         * @return whether that customer is the known one
         */
        @Override
        public boolean customerExists(long customerId) {
            return customerId == CUSTOMER_ID;
        }

        /**
         * Answers nothing, because the decision path never asks for a display projection.
         *
         * <p>Assumptions: empty rather than a fabricated record. This member serves the pending
         * authorization SCREEN, which no case here exercises, and returning invented display text would
         * make a future case that did exercise it pass against data no service produced.
         *
         * @param customerId the customer a screen would be rendering
         * @return always empty
         */
        @Override
        public Optional<CustomerDisplay> customerDisplay(long customerId) {
            return Optional.empty();
        }
    }

    /**
     * The narrowest context that can host the listener under test against a real engine.
     *
     * <p>Assumptions: the listener is declared through a bean method rather than component-scanned, for
     * two independent reasons. Scanning its own package would also start the outbox publisher, which
     * wants a queue client, and the remote account client, which wants a network address. Scanning the
     * repository package with the default filters would additionally pick up the nested configuration
     * classes of the OTHER integration tests in that package -- the test tree shares the production
     * package names -- each declaring its own repository enablement, which fails the context on a
     * duplicate bean definition. Declaring exactly what this class needs avoids both.
     *
     * <p>Assumptions: a bean declared here is still wrapped in the framework's transactional proxy, so
     * the boundary these cases exercise is the production one rather than a hand-rolled substitute. That
     * is what makes the rollback case a statement about the listener instead of about the test.
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.authorization.domain")
    @EnableJpaRepositories("com.carddemo.authorization.repository")
    static class DecisionTestApplication {

        /**
         * The latch the reply mapping reads, exposed so a case and the mapping share one instance.
         *
         * @return a latch that starts disarmed
         */
        @Bean
        AtomicBoolean replyMappingRefuses() {
            return new AtomicBoolean(false);
        }

        /**
         * The window boundary, which does nothing.
         *
         * <p>Assumptions: it is supplied as a lambda rather than as the production container-cycling
         * implementation, which needs a listener container registry and a live queue. Separating the two is
         * the reason the boundary is an interface at all. It does nothing because the window is sized above
         * every case here, so it is never invoked; its own behaviour is asserted where the window
         * arithmetic is.
         *
         * @return a boundary that records nothing and raises nothing
         */
        @Bean
        RequestWindowBoundary inertWindowBoundary() {
            return (generation, admittedInWindow) -> {
                // WHY : Assumptions: deliberately empty. A window is never filled in this class, so a
                //       body here could only run if the window arithmetic changed underneath these cases,
                //       and a boundary that raised in that event would report the change as a failure of
                //       the unit of work these cases are about.
            };
        }

        /**
         * The reply mapping, which refuses whenever the shared latch is armed.
         *
         * <p>Assumptions: it decorates the real mapper rather than replacing it, so every case but the
         * refusal one maps exactly as production does and the refusal case differs in one respect only.
         *
         * @param validator the validator the real mapper is built with
         * @param refuses the shared latch a case arms
         * @return a mapper that raises when armed and maps normally otherwise
         */
        @Bean
        AuthorizationMessageMapper failableReplyMapper(Validator validator, AtomicBoolean refuses) {
            return new AuthorizationMessageMapper(validator) {
                @Override
                public OutboxMessage toOutboxMessage(AuthReply reply, ReplyRouting routing) {
                    if (refuses.get()) {
                        throw new IllegalStateException(
                                "the reply mapping refused, so the decision must not survive");
                    }
                    return super.toOutboxMessage(reply, routing);
                }
            };
        }

        /**
         * The validator the reply mapper is built with.
         *
         * @return a validator from the default factory
         */
        @Bean
        Validator replyValidator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }

        /**
         * The stubbed account context.
         *
         * @return the stub, which resolves exactly one card
         */
        @Bean
        AccountContextClient accountContextClient() {
            return new StubAccountContext();
        }

        /**
         * The listener under test, wired from the real repositories and the two stubs above.
         *
         * @param summaries the real summary repository
         * @param details the real authorization repository
         * @param outbox the real outbox repository
         * @param accounts the stubbed account context
         * @param payloads the reply mapping, which refuses when a case arms it
         * @param windowBoundary the inert window boundary
         * @return the listener, which the framework wraps in its transactional proxy
         */
        @Bean
        AuthorizationRequestListener authorizationRequestListener(
                PendingAuthSummaryRepository summaries, PendingAuthDetailRepository details,
                OutboxRepository outbox, AccountContextClient accounts,
                AuthorizationMessageMapper payloads, RequestWindowBoundary windowBoundary) {
            // WHY : Assumptions: no key material is supplied, because the deduplication identity is the
            //   acquirer's OWN transaction identifier rather than a value this service derives. A
            //   redelivery therefore carries the same identity by construction, which is the property
            //   the redelivery case below asserts.
            return new AuthorizationRequestListener(summaries, details, outbox,
                    new AuthorizationDecisionService(), payloads,
                    accounts, List.of(ALLOWED_REPLY_QUEUE),
                    Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), GENEROUS_WINDOW, windowBoundary);
        }

        /**
         * A template for the emptying writes, which must commit outside the listener's own boundary.
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
