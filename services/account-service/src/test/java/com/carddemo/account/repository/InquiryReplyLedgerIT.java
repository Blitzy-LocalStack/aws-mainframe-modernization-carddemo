package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds {@code account.inquiry_reply_ledger} and its three native statements against a real engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>The asynchronous inquiry exchange answers a request by sending a reply and then returning, and the
 * queue acknowledges the request only on that clean return -- so a task killed between the send and the
 * acknowledgement leaves the request visible again and the next delivery would send a SECOND reply bearing
 * the same correlation identifier as the first. {@link InquiryReplyLedger} removes that by recording the
 * answer under the broker's own identifier for the delivery, committing it, and only then sending. This
 * class asserts the three statements that make it work.</p>
 *
 * <p>Assumptions: this runs against a real PostgreSQL engine rather than a substitute, because every
 * property under test here is the ENGINE's. {@code INSERT ... ON CONFLICT DO NOTHING} reporting zero
 * affected rows on a second claim, the two {@code CHECK} constraints refusing an unrecognised state and a
 * mismatched instant, and a guarded {@code UPDATE} reporting whether it retired the row are all decisions
 * the database makes; a mocked repository would return whatever a stub was told to and would pass against a
 * statement with a typo in it. The consumer's own behaviour on each outcome is asserted separately, over a
 * substituted ledger, in {@code InquiryMessageListenerTest}.</p>
 *
 * <p>Assumptions: the migration is what creates the table -- the same {@code V2__account_inquiry_reply_ledger.sql}
 * a deployment applies -- so a column, a constraint or an index this class asserts is one the deployed schema
 * carries. No initialisation script declares the table, deliberately: a harness-declared copy could drift
 * from the migration and this class would then assert the copy.</p>
 *
 * <p>Assumptions: the engine image is pinned by the same manifest digest every sibling integration test in
 * this build pins, so one engine serves the whole suite and two tests cannot disagree about one schema.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
@Testcontainers
// WHY : Assumptions: the two configuration-import sources are disabled and the web layer is switched off
//       by PROPERTY rather than by excluding an auto-configuration, because both act during environment
//       preparation -- before any auto-configuration condition is evaluated. Left enabled, the Secrets
//       Manager config-data loader builds a client from the unresolved region placeholder in
//       application.yml and ends context load there, reporting a failure that belongs to configuration
//       rather than to any statement in this class. The sibling AccountScreenProjectionIT sets the same
//       three for the same reason.
@SpringBootTest(properties = {
        "spring.cloud.aws.parameterstore.enabled=false",
        "spring.cloud.aws.secretsmanager.enabled=false",
        "spring.main.web-application-type=none"})
@ActiveProfiles("test")
@DisplayName("Inquiry reply ledger: the claim, the seek and the retirement, against a real engine")
class InquiryReplyLedgerIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the version is recorded in prose beside the digest because a digest states nothing a
     * reader recognises, and the two must be changed together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The requester identity every case claims on, obviously synthetic. */
    private static final String REQUEST_KEY = "inbound-message-0001";

    /** The framed reply body a claim records. */
    private static final String REPLY_BODY = "ACCOUNT FOUND FIXTURE REPLY";

    /** The destination a claim records, so a re-send goes where the first send went. */
    private static final String DESTINATION = "https://sqs.test.invalid/queue/account-test-reply";

    /** The correlation identity a claim records for echoing on a re-send. */
    private static final String CORRELATION_ID = "corr-0001";

    /** A fixed instant, so nothing here depends on the wall clock. */
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 8, 7, 12, 0, 0);

    /**
     * The container every assertion in this class runs against, started once for the class.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The ledger under test, injected as the production bean. */
    @Autowired
    private InquiryReplyLedger ledger;

    /** A plain JDBC handle, for the catalog and constraint assertions the ledger does not expose. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry this method adds the container's JDBC URL, user
     *     name and credential to as deferred suppliers; must not be {@code null}
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
     * Creates the owning role and schema the migration expects to find already present.
     *
     * <p>Assumptions: {@code data-migration/sql/V0__schemas_and_roles.sql} is the exclusive authority for
     * schemas, roles and grants and this container has never run it, so the precondition is supplied here
     * exactly as the sibling integration tests in this package supply it. The migrations contain no
     * {@code CREATE SCHEMA} for that reason.</p>
     *
     * <p>This setup step takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the container refuses the connection or any statement, which is a broken
     *     harness rather than a failed assertion and is reported as such
     */
    @BeforeAll
    static void createSchemaAndOwnerBeforeFlywayRuns() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE carddemo_account_owner NOLOGIN");
            statement.execute("GRANT CREATE ON DATABASE \"" + POSTGRES.getDatabaseName()
                    + "\" TO carddemo_account_owner");
            statement.execute("CREATE SCHEMA account AUTHORIZATION carddemo_account_owner");
        }
    }

    /**
     * Empties the ledger before each case.
     *
     * <p>Assumptions: the table is emptied rather than each case being wrapped in a rolled-back
     * transaction, because two cases below assert that the DATABASE refused a write and a refusal inside a
     * rolled-back wrapper cannot be told apart from the rollback.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void reset(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM account.inquiry_reply_ledger");
    }

    /**
     * Confirms the production migration applied, through Flyway's own history.
     *
     * <p>Assumptions: the history table is read rather than the ledger merely being queried successfully. A
     * query succeeding proves a table exists; it does not distinguish a table the migration created from one
     * a harness script created, and the whole value of this class is that it asserts the deployed shape.</p>
     */
    @Test
    @DisplayName("Flyway applied V2__account_inquiry_reply_ledger.sql")
    void flywayAppliedTheLedgerMigration() {
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM account.flyway_schema_history WHERE script = ?",
                Integer.class, "V2__account_inquiry_reply_ledger.sql"))
                .as("the ledger table must come from the migration a deployment applies")
                .isEqualTo(1);
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'account'"
                        + " AND indexname = 'idx_inquiry_reply_ledger_claimed_at'", Integer.class))
                .as("the pruning index must exist, or the first prune scans the whole ledger")
                .isEqualTo(1);
    }

    /**
     * A first claim inserts the row and reports itself the winner; a second on the same key does not.
     *
     * <p>Purpose: this is the property the whole guarantee rests on. The affected-row count of
     * {@code INSERT ... ON CONFLICT DO NOTHING} is what tells a delivery whether it is the first to answer
     * a request, and it is an engine behaviour -- expressing the same intent as a read followed by a
     * conditional insert would leave the decision to the gap between two statements, which is exactly where
     * two concurrent deliveries of one request would both decide they were first.</p>
     *
     * <p>Assumptions: the second claim presents DIFFERENT bytes and a different destination, and the case
     * asserts the stored row still holds the first claim's values. A conflicting insert that silently
     * updated the row would let a redelivery overwrite the answer already sent.</p>
     */
    @Test
    @DisplayName("a second claim on one key is refused and does not overwrite the first")
    @Transactional
    void aSecondClaimIsRefusedAndOverwritesNothing() {
        assertThat(claim(REQUEST_KEY, REPLY_BODY, DESTINATION))
                .as("the first delivery must take the claim")
                .isTrue();

        assertThat(claim(REQUEST_KEY, "A DIFFERENT ANSWER", "https://sqs.test.invalid/queue/elsewhere"))
                .as("a redelivery must be told the claim is already held")
                .isFalse();

        Optional<InquiryReplyLedger.RecordedReply> recorded = this.ledger.find(REQUEST_KEY);
        assertThat(recorded).isPresent();
        assertThat(recorded.get().payload()).isEqualTo(REPLY_BODY);
        assertThat(recorded.get().destination()).isEqualTo(DESTINATION);
        assertThat(recorded.get().status()).isEqualTo(InquiryReplyLedger.STATUS_PENDING);
        assertThat(recorded.get().sent()).isFalse();
    }

    /**
     * A claim starts outstanding, is retired once, and cannot be retired twice.
     *
     * <p>Purpose: the guard on the update is what tells a delivery whether ITS send was the duplicate. A
     * second retirement reporting success would leave a concurrent delivery unable to tell that another one
     * had already answered.</p>
     *
     * <p>⚠️ Assumptions: the delivery count is asserted to be ONE after a claim and two retirements, and
     * both halves of that are the fix rather than the old behaviour. The claim now records the delivery it
     * admits, and the retirement records none -- where the retirement was previously the only statement
     * that counted anything. Counting at the retirement counted SUCCESSES, so the column never moved for
     * the one condition an operator reads it for, a reply whose send keeps failing, and it stayed at zero
     * across every redelivery of such a request. It still must not accumulate unrelated events, which the
     * sibling outbox's second migration records the consequences of; what it now accumulates is deliveries
     * that reached the send step, and nothing else.</p>
     */
    @Test
    @DisplayName("a claim is retired once, and a second retirement reports that it was already retired")
    @Transactional
    void aClaimIsRetiredExactlyOnce() {
        claim(REQUEST_KEY, REPLY_BODY, DESTINATION);

        assertThat(this.ledger.markSent(REQUEST_KEY, CLAIMED_AT.plusSeconds(1)))
                .as("the delivery that sent the reply must retire the claim")
                .isTrue();
        assertThat(this.ledger.markSent(REQUEST_KEY, CLAIMED_AT.plusSeconds(2)))
                .as("a second retirement must report that another delivery got there first")
                .isFalse();

        InquiryReplyLedger.RecordedReply recorded = this.ledger.find(REQUEST_KEY).orElseThrow();
        assertThat(recorded.sent()).isTrue();
        assertThat(this.jdbc.queryForObject(
                "SELECT attempts FROM account.inquiry_reply_ledger WHERE request_key = ?",
                Integer.class, REQUEST_KEY))
                .as("the claim counted this one delivery and neither retirement counted again")
                .isEqualTo(1);
    }

    /**
     * The delivery count follows deliveries: the claim counts one, each re-send counts another, and the
     * retirement counts none.
     *
     * <p>⚠️ Purpose: this column carried nothing an operator could use. It was zero for every outstanding
     * claim and one for every retired one -- which is exactly what {@code status} already says -- so a
     * reply whose send failed on every one of its deliveries read zero, and the redelivery pressure that
     * eventually dead-letters the request was invisible. The three assertions below are the three halves of
     * the rule that replaces it, asserted against the real engine because the counting lives entirely in
     * SQL and a substituted repository would assert the stub rather than the statements.</p>
     *
     * <p>Assumptions: the second claim is asserted to be REFUSED as well as counted-around, so this case
     * cannot pass by a redelivery having quietly overwritten the row instead of counting on it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the delivery count follows deliveries, not successes")
    @Transactional
    void theDeliveryCountFollowsDeliveries() {
        claim(REQUEST_KEY, REPLY_BODY, DESTINATION);
        assertThat(attempts())
                .as("the claim records the delivery it admits, so a failed first send still reads one")
                .isEqualTo(1);

        assertThat(claim(REQUEST_KEY, REPLY_BODY, DESTINATION))
                .as("a redelivery must still be refused the claim")
                .isFalse();
        assertThat(this.ledger.countSendAttempt(REQUEST_KEY))
                .as("a redelivery that re-sends counts itself on the row it found")
                .isTrue();
        assertThat(attempts())
                .as("two deliveries reached the send step")
                .isEqualTo(2);

        assertThat(this.ledger.markSent(REQUEST_KEY, CLAIMED_AT.plusSeconds(1))).isTrue();
        assertThat(attempts())
                .as("the retirement records WHEN the answer went out, not how many deliveries it took")
                .isEqualTo(2);

        assertThat(this.ledger.countSendAttempt("no-delivery-claimed-this"))
                .as("counting a key no row holds reports that it counted nothing")
                .isFalse();
    }

    /**
     * Reads the delivery count recorded for this class's fixed request key.
     *
     * @return the count, never {@code null} because the column is declared {@code NOT NULL}
     */
    private Integer attempts() {
        return this.jdbc.queryForObject(
                "SELECT attempts FROM account.inquiry_reply_ledger WHERE request_key = ?",
                Integer.class, REQUEST_KEY);
    }

    /**
     * Seeking a key no delivery has claimed answers empty rather than raising.
     *
     * <p>Assumptions: an absent row is the ordinary first-delivery outcome rather than a failure, so the
     * seek must express it as an empty optional. A statement issued through a single-result call would
     * raise instead, and the exchange would then drive its normal path through an exception.</p>
     */
    @Test
    @DisplayName("an unclaimed key is answered empty, not raised")
    @Transactional
    void anUnclaimedKeyIsAnsweredEmpty() {
        assertThat(this.ledger.find("no-delivery-claimed-this")).isEmpty();
    }

    /**
     * The engine refuses an unrecognised state and a state that disagrees with its instant.
     *
     * <p>Purpose: the two {@code CHECK} constraints are enforced by the DATABASE because the claim is a
     * native statement -- there is no mapped entity whose type could constrain it -- and an unrecognised
     * state would make a redelivery neither suppressible nor re-sendable. The statements are issued
     * directly, since the ledger's own three statements cannot produce either condition; that is the point,
     * and this case asserts the floor beneath them rather than their behaviour.</p>
     */
    @Test
    @DisplayName("the engine refuses an unrecognised state and a state that disagrees with its instant")
    void theEngineRefusesAnInvalidState() {
        assertThatThrownBy(() -> this.jdbc.update(
                "INSERT INTO account.inquiry_reply_ledger (request_key, status, reply_payload,"
                        + " reply_to_queue_url, claimed_at) VALUES (?, 'DONE', ?, ?, ?)",
                "state-domain", REPLY_BODY, DESTINATION, CLAIMED_AT))
                .as("only PENDING and SENT may reach this column")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbc.update(
                "INSERT INTO account.inquiry_reply_ledger (request_key, status, reply_payload,"
                        + " reply_to_queue_url, claimed_at, sent_at) VALUES (?, 'PENDING', ?, ?, ?, ?)",
                "instant-agreement", REPLY_BODY, DESTINATION, CLAIMED_AT, CLAIMED_AT))
                .as("an outstanding claim carrying a send instant would invite a re-send of a sent reply")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbc.update(
                "INSERT INTO account.inquiry_reply_ledger (request_key, status, reply_payload,"
                        + " reply_to_queue_url, claimed_at) VALUES (?, 'SENT', ?, ?, ?)",
                "instant-missing", REPLY_BODY, DESTINATION, CLAIMED_AT))
                .as("a sent reply with no instant would be unprunable by age")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Both echoed identities survive the round trip, including their absence.
     *
     * <p>Assumptions: a re-send must carry the same attributes as the original, so the two identities are
     * recorded and read back. They are nullable because a request may supply either, both or neither, and
     * this case asserts a null returns as a null rather than as an empty string -- an empty attribute is a
     * value a requester would try to pair on.</p>
     */
    @Test
    @DisplayName("the echoed identities round-trip, including their absence")
    @Transactional
    void theEchoedIdentitiesRoundTrip() {
        assertThat(this.ledger.claim("with-both", REPLY_BODY, DESTINATION, CORRELATION_ID,
                REQUEST_KEY, CLAIMED_AT)).isTrue();
        assertThat(this.ledger.claim("with-neither", REPLY_BODY, DESTINATION, null, null, CLAIMED_AT))
                .isTrue();

        InquiryReplyLedger.RecordedReply both = this.ledger.find("with-both").orElseThrow();
        assertThat(both.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(both.messageId()).isEqualTo(REQUEST_KEY);

        InquiryReplyLedger.RecordedReply neither = this.ledger.find("with-neither").orElseThrow();
        assertThat(neither.correlationId()).isNull();
        assertThat(neither.messageId()).isNull();
    }

    /**
     * Claims a reply with this class's fixed instant.
     *
     * @param requestKey the requester identity to claim on; must not be {@code null}
     * @param payload the reply bytes to record; must not be {@code null}
     * @param destination the destination to record; must not be {@code null}
     * @return {@code true} when the claim was taken
     */
    private boolean claim(String requestKey, String payload, String destination) {
        return this.ledger.claim(requestKey, payload, destination, CORRELATION_ID, REQUEST_KEY,
                CLAIMED_AT);
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: the two auto-configurations excluded here read deployment values the test profile
     * deliberately does not carry -- the resource-server one evaluates its decoder condition against an
     * issuer address and the queue one builds a client needing a region -- and both are bound in
     * {@code application.yml} to placeholders with no fallback, so leaving either in would end context load
     * while the condition is being evaluated and report a failure belonging to configuration rather than to
     * any statement in this class. The sibling integration tests in this package exclude the same pair.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    static class LedgerPersistenceTestApplication {

        /**
         * Registers the ledger, which is a class rather than a Spring Data interface.
         *
         * <p>Assumptions: the bean is declared rather than reached by a component scan over the repository
         * package, because such a scan also reaches the nested configuration classes of the sibling
         * integration tests in that package -- each declaring its own repository enablement -- and the
         * duplicate definitions end context load.</p>
         *
         * @return the ledger, never {@code null}
         */
        @org.springframework.context.annotation.Bean
        InquiryReplyLedger inquiryReplyLedger() {
            return new InquiryReplyLedger();
        }
    }
}
