package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds {@code reference.inquiry_reply_ledger} and its three native statements against a real engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>The asynchronous date-conversion exchange answers a request by sending a reply and then returning,
 * and the queue acknowledges the request only on that clean return -- so a task killed between the send
 * and the acknowledgement leaves the request visible again. Because that reply body is the system date and
 * time read at the moment of composition, the next delivery would not repeat the answer, it would compose
 * a LATER one, and a requester pairing on one correlation identifier would hold two replies that disagree.
 * {@link InquiryReplyLedger} removes that by recording the composed reply under the identity the queue
 * service assigned the delivery, committing it, and only then sending. This class asserts the three
 * statements that make it work.</p>
 *
 * <p>Assumptions: this runs against a real engine rather than a substitute, because every property under
 * test here is the ENGINE's. {@code INSERT ... ON CONFLICT DO NOTHING} reporting zero affected rows on a
 * second claim, the two {@code CHECK} constraints refusing an unrecognised state and a mismatched instant,
 * and a guarded {@code UPDATE} reporting whether it retired the row are all decisions the database makes;
 * a substituted ledger would return whatever a stub was told to and would pass against a statement with a
 * typo in it. The CONSUMER's behaviour on each of those outcomes is asserted separately, over a
 * substituted ledger, by {@code DateConversionMessageListenerTest} -- so the two halves of the guarantee
 * are each held once rather than approximated twice.</p>
 *
 * <p>Assumptions: the migration is what creates the table -- the same
 * {@code V3__reference_inquiry_reply_ledger.sql} a deployment applies -- so a column, a constraint or an
 * index this class asserts is one the deployed schema carries. No initialisation script declares the
 * table, deliberately: a harness-declared copy could drift from the migration and this class would then
 * assert the copy.</p>
 *
 * <p>Assumptions: this class extends {@link ReferencePersistenceBase} rather than starting an engine of
 * its own, for the reason that base records at length -- one engine and one cached context serve every
 * test class in this package, and a second configuration class here would start a second context against
 * the same engine for no gain.</p>
 *
 * <p>Trade-offs: the transaction boundary is declared PER CASE here rather than on the class, unlike its
 * siblings in this package, and the split is load-bearing in both directions. The cases that write have to
 * carry {@code @Transactional} because the ledger's three statements are native updates and the provider
 * refuses one with no active transaction; the case asserting that the DATABASE refused a write must NOT,
 * because a refusal inside a rolled-back wrapper cannot be told apart from the rollback. The cost is that
 * each case empties the table itself, which the setup step below does, and that a reader cannot infer the
 * boundary from the class declaration -- which is why it is stated here. This is the same split the
 * identically shaped ledger test in {@code account-service} arrives at.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
@DisplayName("Reference inquiry reply ledger: the claim, the seek and the retirement, against a real engine")
class InquiryReplyLedgerIT extends ReferencePersistenceBase {

    /** The delivery identity every case claims on, obviously synthetic. */
    private static final String REQUEST_KEY = "11111111-2222-3333-4444-555555555555";

    /** The framed reply body a claim records, standing in for the forty-six-character answer. */
    private static final String REPLY_BODY = "SYSTEM DATE : 07-18-2022SYSTEM TIME : 09:04:05";

    /** The destination a claim records, so a re-send goes where the first send went. */
    private static final String DESTINATION = "https://sqs.test.invalid/queue/reference-test-reply";

    /** The correlation identity a claim records for echoing on a re-send. */
    private static final String CORRELATION_ID = "corr-0001";

    /** The message identity a claim records for echoing on a re-send. */
    private static final String MESSAGE_ID = "mid-0001";

    /** A fixed instant, so nothing here depends on the wall clock. */
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2022, 7, 18, 9, 4, 5);

    /** The ledger under test, injected as the production bean. */
    @Autowired
    private InquiryReplyLedger ledger;

    /** A plain JDBC handle, for the catalog and constraint assertions the ledger does not expose. */
    private JdbcTemplate jdbc;

    /**
     * Empties the ledger before each case.
     *
     * <p>Assumptions: the table is emptied rather than each case being wrapped in a rolled-back
     * transaction, for the reason recorded on the class: a refusal inside a rolled-back wrapper cannot be
     * told apart from the rollback.</p>
     *
     * @param dataSource the pool the shared context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void reset(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM reference.inquiry_reply_ledger");
    }

    /**
     * Confirms the production migration applied, through Flyway's own history.
     *
     * <p>Assumptions: the history table is read rather than the ledger merely being queried successfully. A
     * query succeeding proves a table exists; it does not distinguish a table the migration created from
     * one a harness script created, and the whole value of this class is that it asserts the deployed
     * shape.</p>
     */
    @Test
    @DisplayName("Flyway applied V3__reference_inquiry_reply_ledger.sql")
    void flywayAppliedTheLedgerMigration() {
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM reference.flyway_schema_history WHERE script = ?",
                Integer.class, "V3__reference_inquiry_reply_ledger.sql"))
                .as("the ledger table must come from the migration a deployment applies, not from a"
                        + " harness script that could drift from it")
                .isEqualTo(1);
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'reference'"
                        + " AND indexname = 'idx_reference_inquiry_reply_ledger_claimed_at'",
                Integer.class))
                .as("the pruning index must exist, or the first prune scans a ledger that grows without"
                        + " bound until it runs")
                .isEqualTo(1);
    }

    /**
     * A first claim inserts the row and reports itself the winner; a second on the same key does not.
     *
     * <p>Purpose: this is the property the whole guarantee rests on. The affected-row count of
     * {@code INSERT ... ON CONFLICT DO NOTHING} is what tells a delivery whether it is the first to answer
     * a request, and it is an engine behaviour -- expressing the same intent as a read followed by a
     * conditional insert would leave the decision to the gap between two statements, which is exactly
     * where two concurrent deliveries of one request would both decide they were first.</p>
     *
     * <p>Assumptions: the second claim presents DIFFERENT bytes and a different destination, and the case
     * asserts the stored row still holds the first claim's values. That is not a hypothetical for this
     * exchange: the second delivery's bytes carry a later timestamp, so a conflicting insert that silently
     * updated the row would replace the answer already sent with one that disagrees with it.</p>
     */
    @Test
    @DisplayName("a second claim on one key is refused and does not overwrite the first")
    @Transactional
    void aSecondClaimIsRefusedAndOverwritesNothing() {
        assertThat(claim(REQUEST_KEY, REPLY_BODY, DESTINATION))
                .as("the first delivery must take the claim")
                .isTrue();

        assertThat(claim(REQUEST_KEY, "SYSTEM DATE : 07-18-2022SYSTEM TIME : 10:04:12",
                        "https://sqs.test.invalid/queue/elsewhere"))
                .as("a redelivery must be told the claim is already held")
                .isFalse();

        Optional<InquiryReplyLedger.RecordedReply> recorded = this.ledger.find(REQUEST_KEY);
        assertThat(recorded).isPresent();
        assertThat(recorded.get().payload())
                .as("the FIRST answer's bytes, at the first answer's time, so a re-send re-sends them"
                        + " rather than the later delivery's own")
                .isEqualTo(REPLY_BODY);
        assertThat(recorded.get().destination()).isEqualTo(DESTINATION);
        assertThat(recorded.get().status()).isEqualTo(InquiryReplyLedger.STATUS_PENDING);
        assertThat(recorded.get().sent()).isFalse();
    }

    /**
     * A claim starts outstanding, is retired once, and cannot be retired twice.
     *
     * <p>Purpose: the guard on the update is what tells a delivery whether ITS send was the duplicate. A
     * second retirement reporting success would leave a concurrent delivery unable to tell that another
     * one had already answered.</p>
     *
     * <p>Assumptions: the send count is asserted to advance exactly once per retirement, so the column
     * answers one question -- how many times this reply reached the queue. That count is the operational
     * signal that the one residual window this design accepts, a task dying between the send and the mark,
     * was actually entered.</p>
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
                "SELECT attempts FROM reference.inquiry_reply_ledger WHERE request_key = ?",
                Integer.class, REQUEST_KEY))
                .as("the send count advances once per retirement and never twice")
                .isEqualTo(1);
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
     * directly, since the ledger's own three cannot produce either condition; that is the point, and this
     * case asserts the floor beneath them rather than their behaviour.</p>
     */
    @Test
    @DisplayName("the engine refuses an unrecognised state and a state that disagrees with its instant")
    void theEngineRefusesAnInvalidState() {
        assertThatThrownBy(() -> this.jdbc.update(
                "INSERT INTO reference.inquiry_reply_ledger (request_key, status, reply_payload,"
                        + " reply_to_queue_url, claimed_at) VALUES (?, 'DONE', ?, ?, ?)",
                "state-domain", REPLY_BODY, DESTINATION, CLAIMED_AT))
                .as("only PENDING and SENT may reach this column")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbc.update(
                "INSERT INTO reference.inquiry_reply_ledger (request_key, status, reply_payload,"
                        + " reply_to_queue_url, claimed_at, sent_at) VALUES (?, 'PENDING', ?, ?, ?, ?)",
                "instant-agreement", REPLY_BODY, DESTINATION, CLAIMED_AT, CLAIMED_AT))
                .as("an outstanding claim carrying a send instant would claim an answer had been sent"
                        + " while inviting a re-send of it")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> this.jdbc.update(
                "INSERT INTO reference.inquiry_reply_ledger (request_key, status, reply_payload,"
                        + " reply_to_queue_url, claimed_at) VALUES (?, 'SENT', ?, ?, ?)",
                "instant-missing", REPLY_BODY, DESTINATION, CLAIMED_AT))
                .as("a sent reply with no instant would be unprunable by age")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Both echoed identities survive the round trip, including their absence.
     *
     * <p>Assumptions: a re-send must carry the same attributes as the original -- the reference program
     * restores its saved message identifier at physical line 373 and its saved correlation identifier at
     * 374 immediately before the put at 383 -- so the two identities are recorded and read back. They are
     * nullable because a request may supply either, both or neither, and this case asserts a null returns
     * as a null rather than as an empty string: an empty attribute is a value a requester would try to
     * pair on.</p>
     */
    @Test
    @DisplayName("the echoed identities round-trip, including their absence")
    @Transactional
    void theEchoedIdentitiesRoundTrip() {
        assertThat(this.ledger.claim("with-both", REPLY_BODY, DESTINATION, CORRELATION_ID,
                MESSAGE_ID, CLAIMED_AT)).isTrue();
        assertThat(this.ledger.claim("with-neither", REPLY_BODY, DESTINATION, null, null, CLAIMED_AT))
                .isTrue();

        InquiryReplyLedger.RecordedReply both = this.ledger.find("with-both").orElseThrow();
        assertThat(both.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(both.messageId()).isEqualTo(MESSAGE_ID);

        InquiryReplyLedger.RecordedReply neither = this.ledger.find("with-neither").orElseThrow();
        assertThat(neither.correlationId()).isNull();
        assertThat(neither.messageId()).isNull();
    }

    /**
     * Claims a reply with this class's fixed instant and both echoed identities.
     *
     * @param requestKey the delivery identity to claim on; must not be {@code null}
     * @param payload the reply bytes to record; must not be {@code null}
     * @param destination the destination to record; must not be {@code null}
     * @return {@code true} when the claim was taken
     */
    private boolean claim(String requestKey, String payload, String destination) {
        return this.ledger.claim(requestKey, payload, destination, CORRELATION_ID, MESSAGE_ID,
                CLAIMED_AT);
    }
}
