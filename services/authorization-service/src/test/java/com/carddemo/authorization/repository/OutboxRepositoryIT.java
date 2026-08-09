package com.carddemo.authorization.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies {@link OutboxRepository} against a real PostgreSQL engine, over the
 * {@code auth_reply_outbox} table that
 * {@code services/authorization-service/src/main/resources/db/migration/V1__authorization.sql}
 * builds.
 *
 * <p><b>Purpose.</b> This is the persistence half of the transactional outbox, and it is the only
 * table in this bounded context with no record layout behind it: the reference system has no such
 * table, because there a reply is a message and never a record. What this class establishes is
 * narrow and is stated positively so that the boundary is legible. A reply row round-trips every
 * column it declares; the payload survives untouched; only unpublished rows are publishable; the
 * publishable order is total and reproducible; a claim is a transition exactly one competing worker
 * can complete; marking published cannot be undone by marking it again; and retention can never
 * remove a reply that has not been sent. The charter in {@code package-info.java} beside this file
 * owns the rulings this class obeys and is cited rather than restated.
 *
 * <p>Assumptions: the type this class persists is {@link AuthReplyOutbox} and not the similarly
 * named record in the same domain package, and the choice is stated because a reader arriving from a
 * plan or a schema listing will expect the other one. That record declares itself not to be a mapped
 * type and names this one as the type that maps this table; the boundary under test is in turn
 * declared over this one, so its identifier type and every column assertion below follow the mapping
 * rather than the record. The record is the reply's transport-facing half and has no place in a
 * persistence assertion. For the same reason the table is {@code auth_reply_outbox}: that is the one
 * outbox table this context owns, and the migration declares no other.
 *
 * <p>Assumptions: this table carries no {@code status} column, and its absence changes what every
 * assertion below can be written against. Publication state is whether {@code published_at} is set,
 * tries are counted in {@code attempts}, and the diagnostic is {@code last_error}; there is no status
 * domain to advance through and no affected-row count returned by a marking call, because marking is
 * a transition on the loaded row rather than a statement on the boundary. Assertions here are
 * therefore written against those three columns and against the rows a claim returns, which is the
 * only success signal the claim produces.
 *
 * <h2>Refactoring Rationale: divergence D-5, which has two failure modes and not one</h2>
 *
 * <p>Refactoring Rationale: this table replaces a publication that the reference consumer performs
 * outside any unit of work, and the seam it closes is unguarded in BOTH directions. Naming only one
 * direction is the common error, because it leaves a reader believing the other is already safe. The
 * ordering was read off {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} directly:
 * {@code 2000-MAIN-PROCESS} at L323 extracts a request at L328 and performs {@code 5000-PROCESS-AUTH}
 * at L330; that paragraph, at L438, reads the cross-reference at L448, reads the account, customer,
 * authorization summary and profile under {@code IF CARD-FOUND-XREF} at L450 to L457, decides at
 * L459, performs the response paragraph at L461, and only afterwards performs the database write at
 * L463 to L465. The single {@code EXEC CICS SYNCPOINT} is reached later still, at L334 to L336, after
 * the paragraph performed at L330 returns. The verified order is therefore decide, put the reply,
 * write the data, commit.
 *
 * <p>Refactoring Rationale: the first failure mode is a PHANTOM REPLY, and it is the dominant one
 * precisely because the put comes first. The reply leaves at L461, whose put is the {@code MQPUT1}
 * at L758 with {@code MQPMO-NO-SYNCPOINT} computed into its options at L753; the write at L463 to
 * L465 then fails, or the task ends before the commit at L334 to L336, and a requester is holding an
 * answer that no committed row accounts for. The second is a LOST REPLY, and it is the converse of
 * the same absence of a unit of work: because the put is outside the syncpoint it cannot be replayed
 * by anything either, so a put that does not succeed while processing continues to a successful
 * commit leaves a persisted decision that no reply ever reported. One unguarded seam, two
 * directions.
 *
 * <p>Refactoring Rationale: the target inverts the order. The decision and the row carrying its
 * reply are committed together in one local transaction, and publication happens strictly
 * afterwards, from this table. That eliminates the phantom outright, because no reply can exist for
 * a decision that never committed. It does not come free, and the cost is stated rather than implied:
 * a publisher that sends and then fails before recording the send will send that reply again, so
 * publication is at-least-once and the duplicate is absorbed by the reply queue on the deduplication
 * token stored on the row. Trading a duplicate for a phantom is the only direction that preserves
 * the invariant that a reply implies a committed decision, because a duplicate is suppressible
 * downstream and a phantom is not recoverable by anything. This is divergence D-5 in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The reference sources are read as the
 * specification for that comparison and are never modified by this migration; the baseline behaves
 * as described, the target adds a durable reply, and the difference is registered rather than made
 * silently.
 *
 * <p>Assumptions: one asymmetry in the baseline explains why the outbox row belongs on the paths
 * that persist rather than on every reply, and a reader who misses it will look for a row that was
 * never meant to exist. L463 gates the database write on the cross-reference outcome; L461 gates the
 * reply on nothing. An unknown card therefore receives a reply and writes no row at all, which is
 * legitimate baseline behaviour. That path is asserted where the decision is taken, in the service
 * package, and not here: this class is handed rows and asserts what the table does with them.
 *
 * <p>Assumptions: the target's per-message transaction stands in for the baseline's per-message
 * commit at L334 to L336, but the transaction BOUNDARY is service territory. Nothing below asserts
 * that a decision and its outbox row commit together; what is asserted is the row's persistence, its
 * publishable state and its state transitions. Reading a boundary assertion into this class would
 * put the service's contract in the wrong package.
 *
 * <h2>Alternatives Considered: the claim is an atomic transition, not a held lock</h2>
 *
 * <p>Alternatives Considered: selecting the candidate rows under a pessimistic row lock, with or
 * without stepping over rows another transaction already holds, and equally requesting that lock
 * through the persistence annotation that expresses a lock mode on a query method. Rejected, and the
 * reason is mechanical rather than stylistic: a lock taken as part of the selection is held until the
 * transaction ends, so the drain would hold it across the call that sends the reply to an external
 * queue service, and the progress of every other worker would then depend on that call's latency.
 * The transition below completes in one statement and holds nothing while the reply is sent. The
 * declaration under test carries the same reasoning, and no repository in this module declares a lock
 * mode at all.
 *
 * <p>Alternatives Considered: this class must therefore assert no pessimistic lock, no lock mode and
 * no statement that steps over locked rows. The prohibition is recorded here rather than merely
 * observed, because a later reader looking for the missing concurrency assertion is exactly the
 * person who would add one: such an assertion would fail against the declaration as written, and if
 * it were made to pass it would enshrine the design this module weighed and rejected. What stands in
 * its place is the pair of assertions the transition actually supports, a claim that succeeds and a
 * competing claim that takes nothing, and the concurrency proof that runs them at once.
 *
 * <p>Assumptions: the transition is over the ATTEMPT COUNTER and the publication instant, and not
 * over a status column, because this table declares no status column. The migration records
 * publication by whether {@code published_at} is set, counts tries in {@code attempts} and keeps a
 * diagnostic in {@code last_error}; the entity's own contract states the same absence. A claim
 * therefore advances {@code attempts} from the value it observed, under a predicate naming that same
 * value and requiring {@code published_at} to be null, and reports success by RETURNING the rows it
 * changed rather than by an affected-row count. Every assertion below is written against that
 * signal, which is why a claim's result is a row list and never a number.
 *
 * <h2>Assumptions: the payload is opaque, and its length is not this package's business</h2>
 *
 * <p>Assumptions: the repository stores and returns the payload exactly as it was handed it and
 * interprets nothing, so what is asserted below is fidelity and never shape. No assertion here
 * states a payload length, a delimiter count, a field count or a field order. The reason is concrete
 * and is recorded so the omission is not read as a gap. More than one length is correct for the
 * reference reply depending on what is being measured: its six items at
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} L19 to L24 declare 57 characters of
 * field data; the emitter at {@code cbl/COPAUA0C.cbl} L722 to L731 appends a separator after the
 * sixth item as well as between the pairs; and the pointer it writes through at L730 is declared
 * {@code PIC S9(4) VALUE 1} at L46, so it is one-based and the value moved to the buffer length at
 * L756 counts one past the content. Settling which figure is meant is the wire contract's business,
 * and the wire contract is owned and asserted in {@code services/common-lib} by the shared codec's
 * own tests and in this module's service and mapper packages. A repository test that adopted one of
 * those figures would decide the question in the wrong place and would go on asserting it after the
 * decision moved.
 *
 * <p>Assumptions: this class holds no reference to that codec and no reference to any codec, which
 * is the structural expression of the paragraph above rather than an oversight. It also uses no
 * recorded byte image, so the charter's obligation to state a record length, the offsets read and
 * the encoding of any packed field does not arise: the payload written below is deliberately not a
 * well-formed reply, so nothing here can be mistaken for a statement about the wire.
 *
 * <h2>Trade-offs: the drain is bounded, and the bound is configuration</h2>
 *
 * <p>Trade-offs: a claim takes at most a stated number of rows rather than emptying the backlog, and
 * the accepted cost is that a backlog needs several passes to clear. What is bought is a unit of work
 * whose size and duration are known in advance, and the bound is not invented here: the reference
 * consumer declares {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at
 * {@code cbl/COPAUA0C.cbl} L40 and leaves its loop at L339 once the processed count exceeds it. The
 * figure reaches the declaration under test as an argument rather than as a constant inside the
 * statement, so it stays the caller's configuration; the module's test profile carries it as
 * {@code carddemo.messaging.request-process-limit}, and the assertion below reads it from there
 * rather than repeating the number, so the provenance is asserted and not just described.
 *
 * <h2>Assumptions: marking published is idempotent, and ordering is by identity</h2>
 *
 * <p>Assumptions: because publication is at-least-once, the same row can be marked published more
 * than once, so marking has to be safe to repeat. What repetition must not do is make the row
 * publishable again or make it look freshly attempted, and both halves are asserted below: the second
 * mark leaves the publication instant and the attempt counter as the first left them, and a
 * subsequent claim does not return the row. Asserting only that the second mark did not raise would
 * pass even if it had quietly reverted the state, which is why the claim is re-run afterwards.
 *
 * <p>Assumptions: publishable rows are ordered by the identity column and never by the creation
 * instant, and this is a contract rather than a preference. The creation instant defaults to the
 * statement timestamp, so two rows committed in one transaction carry the same value and their
 * relative order is undefined, and a bounded drain ordered on it can repeat or skip a row at a batch
 * boundary under exactly the timing that produces the tie. The identity is generated strictly
 * increasing and unique, so it totally orders the rows. The ordering assertion below therefore gives
 * its rows creation instants that run OPPOSITE to their insertion order: a test that merely made the
 * instants distinct would pass against either rule, whereas an inverted instant fails immediately if
 * the ordering ever moves onto the timestamp. That also removes the clock from this class entirely,
 * so no assertion here can flake for a reason unrelated to its subject.
 *
 * <h2>Assumptions: nothing here is asserted about a queue</h2>
 *
 * <p>Assumptions: this class asserts nothing about a message reaching a queue, and that includes the
 * error sink and the dead-letter path. Publication and its failure handling are the service's, they
 * need a broker or a stand-in that this package deliberately has none of, and the whole point of the
 * outbox is that the database fact and the publication are separable. The repository's entire
 * relationship to publication is two columns: the publication instant and the attempt counter. A
 * recorded failure advancing the counter is therefore in scope and is asserted; a message arriving
 * anywhere is not. The expiry instant sits in the same category. The reference reply carries a
 * deadline at {@code cbl/COPAUA0C.cbl} L750, where {@code MOVE 50 TO MQMD-EXPIRY} is denominated in
 * tenths of a second and so means five seconds, and the target transport offers no per-message time
 * to live at all, so the deadline became a column. Whether a consumer honours it is service
 * behaviour; that the column persists and returns what it was given is asserted below and is all
 * that is asserted.
 *
 * <h2>Trade-offs: a real engine, and no test-managed transaction</h2>
 *
 * <p>Trade-offs: a real PostgreSQL engine runs in a container rather than an in-memory database, at
 * the cost of container start-up on every run and of a container runtime the host must provide,
 * which also means this class cannot run where that runtime is absent. The cost is accepted because
 * every property asserted here is specific to the target engine. The two partial indexes restricted
 * by a null test, the data-modifying common table expression the claim is written as, and above all
 * the behaviour of a conditional update when two transactions reach one row at the same moment are
 * either rejected outright by an in-memory engine or accepted with different semantics. A green
 * in-memory run would establish nothing about the schema that is deployed, so the faster option is
 * not a cheaper version of this one but a weaker and differently-scoped assertion.
 *
 * <p>Trade-offs: no test-managed transaction wraps these methods, and each unit of work is committed
 * explicitly through an injected transaction template. The accepted cost is that rows survive a
 * method, so the table is emptied before each one. Two things make the alternative unusable rather
 * than merely inconvenient. The concurrency assertion needs one transaction's uncommitted row lock to
 * be visible to another connection, which a single rolled-back test transaction cannot express at
 * all. And the claim performs a data-modifying statement, whereas a declared query method's default
 * transaction posture is read-only and a read-only transaction refuses to modify a row; running the
 * claim inside an explicitly read-write transaction is what keeps that from failing for a reason
 * unconnected to the assertion.
 *
 * <p>Assumptions: connection coordinates are published as PROPERTIES by the method below and not
 * contributed as a bean. The annotation that would contribute one comes from
 * {@code org.springframework.boot:spring-boot-testcontainers}, which this module's POM does not
 * declare, so a reader arriving from the sibling integration tests in {@code auth-service},
 * {@code transaction-service}, {@code reference-service} or {@code reporting-service} would
 * otherwise expect a mechanism that cannot resolve here. The two migration credentials are
 * registered as well and are not redundant beside the datasource pair: this module's
 * {@code application.yml} binds them to environment placeholders carrying no fallback, and those
 * keys are consulted precisely because no connection-details bean supplies them instead, so leaving
 * either unregistered aborts the context before a migration runs.
 *
 * <p>Assumptions: the schema comes from the migration and automatic schema generation stays off,
 * which the test profile already pins. Generated definitions are disqualified rather than
 * disfavoured here: they would emit the table from entity metadata and emit neither the primary-key
 * constraint under its declared name nor either partial index, and the partial indexes are what make
 * the claim's predicate an index scan over the pending rows rather than a scan of the whole table. A
 * suite passing against generated definitions would be asserting a schema nobody deploys. The
 * migration engine is two artifacts and not one: support for this engine moved out of the core
 * artifact from its tenth major version, so the core resolves and compiles on its own and then fails
 * at run time when a migration is applied, which is a failure that appears only after a container is
 * already up.
 *
 * <p>Assumptions: any statement written here names its table unqualified and resolves it through the
 * connection search path this module's {@code application.yml} pins with an initialisation
 * statement, which is the discipline the boundary under test relies on. Qualifying the name in a test
 * is the one form of the assertion that cannot detect the failure it appears to guard against, since
 * it passes while the pin is correct and keeps passing once the pin is gone. The one statement below
 * that does name a catalogue relation is the deliberate exception the charter allows, because asking
 * which sessions are waiting means asking the catalogue.
 *
 * <p>Assumptions: nothing here creates a schema, a role or a grant. Those belong to
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, and a test that granted itself a privilege
 * would prove only that it had done so. The single narrow exception is not an exception to that rule:
 * the test profile lets the migration engine create this context's schema, because a container
 * started for a test has never had the bootstrap applied and the tables would otherwise have nowhere
 * to land.
 *
 * <p>Assumptions: each method uses its own ordering-group and deduplication tokens and asserts only
 * over rows it created. Because the publication instant is the selection criterion, a pending row
 * left behind by another method would change a count without changing an order, which is the failure
 * hardest to read correctly.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
@SpringBootTest(
        classes = OutboxRepositoryIT.OutboxPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OutboxRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the version is recorded in prose because a digest states nothing a reader
     * recognises, and it is the major line the deployed cluster targets. This is deliberately the
     * same digest the sibling integration test in this module and the repository integration tests of
     * the peer contexts already name, so that two suites cannot disagree about one engine
     * behaviour.</p>
     */
    // WHY : Alternatives Considered: the tag postgres:17-alpine, or the apparently exact
    //       postgres:17.10-alpine. Both are mutable, because a publisher moves the first to each new
    //       patch release and may rebuild the second on a new base layer, and the properties asserted
    //       here are engine behaviours: what a conditional update does when two transactions reach
    //       one row together, and whether a data-modifying common table expression returns the rows
    //       it changed. Either could change between two runs of an unchanged declaration, so a
    //       failure could not be attributed and an altered outcome could keep every assertion green
    //       while proving something else.
    // WHY : Trade-offs: a digest is unreadable, so the engine version survives only in the prose
    //       above and has to be updated together with the pin. That is the same trade the
    //       repository's own workflows accept in pinning each action to a commit identifier with the
    //       readable tag beside it, and it buys an attributable failure for one comment that moves
    //       with the value.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The engine this class asserts against, started once for the whole class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} and not from
     * {@code org.testcontainers.containers}, whose counterpart is deprecated in the version this
     * reactor pins. The replacement is not generic, so the declaration carries no type argument and
     * nothing here relies on the self-type that argument refined.</p>
     */
    // WHY : Trade-offs: this class declares its OWN container rather than inheriting one from a
    //       shared base, which costs a container per class instead of per module. The cost is paid
    //       deliberately: the house determinism convention rests its isolation claim on tests sharing
    //       nothing, so that a green parallel run demonstrates isolation rather than merely being
    //       consistent with it, and a shared base would couple every class to one container and one
    //       migration and make that demonstration unavailable.
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The reply destination every row below is given, which is deliberately not routable.
     *
     * <p>Assumptions: the destination is data rather than configuration, because the reference system
     * routes each reply to the destination its request nominated, moving a per-request queue name onto
     * the reply object descriptor at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L741 to
     * L742 rather than addressing one fixed place. The column is therefore populated on every row
     * here, and what it holds is a name reserved by standard for exactly this purpose so that it
     * cannot resolve anywhere. A value shaped like a real endpoint would be copied out of a test
     * eventually, and no endpoint of a deployed queue belongs in source.</p>
     */
    private static final String REPLY_QUEUE_URL =
            "https://sqs.test.invalid/000000000000/carddemo-pauth-reply-test.fifo";

    /**
     * The correlation identity every row below carries, at the width the reference field declares.
     *
     * <p>Assumptions: the reference identity is declared {@code WS-SAVE-CORRELID PIC X(24)} at
     * {@code cbl/COPAUA0C.cbl} L45 and is captured from the inbound descriptor at L411 to L412, then
     * echoed onto the reply unaltered at L745, so it originates in the request and is never defaulted
     * at publication. Twenty-four characters are used here to mirror that declared width even though
     * the column is wider, because a value at the reference width is the one a round-trip assertion
     * should be made with.</p>
     */
    private static final String CORRELATION_ID = "CORRELID0000000000000001";

    /**
     * A payload chosen to break any path that treats the column as anything but opaque text.
     *
     * <p>Assumptions: this is deliberately NOT a well-formed reply, so no assertion made with it can
     * be mistaken for a statement about the wire format, whose length is settled elsewhere. What it
     * does carry is every character class a storing path might quietly rewrite: a carriage return
     * immediately followed by a line feed, which a line-ending normaliser collapses; a tab; repeated
     * commas, which a value that was being parsed rather than stored would disturb; and two
     * characters outside the ASCII range, which a transcoding path would replace. The literal holds
     * no code point that character data cannot store, which is why no zero byte appears in it.</p>
     */
    private static final String OPAQUE_PAYLOAD =
            "OPAQUE-NOT-A-REPLY\r\n\tsecond line,,,\u00e9\u20ac,";

    /**
     * The bound this class passes to a claim, which is far below the configured one.
     *
     * <p>Trade-offs: a small bound is used so that the boundary can be crossed with three rows
     * instead of five hundred and one, and the accepted cost is that the number exercised is not the
     * number configured. What is asserted is the boundary behaviour, which is identical at any bound:
     * a claim returns the bound exactly while more groups remain, and the remainder afterwards. The
     * configured figure is asserted separately, against the property that carries it, so the
     * provenance is proved rather than assumed from a literal.</p>
     */
    private static final int SMALL_BATCH = 2;

    /**
     * A bound large enough that no assertion below is limited by it.
     *
     * <p>Assumptions: this is used wherever the subject is which rows are eligible rather than how
     * many are taken, so that a wrong result reads as an eligibility failure instead of as the bound
     * biting. It is kept well above the largest row count any method here creates.</p>
     */
    private static final int UNRESTRICTIVE_BATCH = 50;

    /**
     * The reference consumer's own per-invocation message bound.
     *
     * <p>Assumptions: this mirrors {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at
     * {@code cbl/COPAUA0C.cbl} L40, whose loop ends at L339 once the processed count exceeds it. It
     * exists here only to be compared against the configured value, so that the configuration is
     * shown to carry the reference figure rather than some number chosen independently.</p>
     */
    private static final short REFERENCE_PROCESS_LIMIT = 500;

    /**
     * The creation instant the earliest-inserted row of an ordering assertion is given.
     *
     * <p>Assumptions: a fixed instant is used rather than the current time so that nothing in this
     * class reads the clock, which is what keeps the ordering assertions insensitive to how fast the
     * host runs. Microsecond precision matches the declared resolution of the audit columns, so a
     * value written here returns unchanged rather than being rounded on the way.</p>
     */
    private static final LocalDateTime BASE_INSTANT =
            LocalDateTime.of(2022, 7, 18, 22, 15, 30, 123_456_000);

    /**
     * How far past its creation instant a row's reply deadline is set, in seconds.
     *
     * <p>Assumptions: five seconds is the reference reply's own deadline. The descriptor field carrying
     * it at {@code cbl/COPAUA0C.cbl} L750 is denominated in tenths of a second, so the fifty moved
     * into it means five seconds and not fifty; the receive wait set elsewhere in the same program is
     * denominated in milliseconds, and conflating the two units is the easy mistake. The value is used
     * here only so the column is exercised with a realistic deadline, since whether a deadline is
     * honoured is the consumer's behaviour and is asserted where the consumer is.</p>
     */
    private static final long REPLY_DEADLINE_SECONDS = 5L;

    /**
     * The diagnostic a failed publication attempt stores against a row.
     *
     * <p>Assumptions: this is a plain sentence and names no endpoint, no identifier and no credential,
     * because the column it lands in is read by operators and is carried in diagnostics. It is also
     * comfortably inside the column's declared width, so this assertion exercises the storing path and
     * not the entity's own truncation of an over-long reason, which belongs with the entity.</p>
     */
    private static final String PUBLICATION_FAILURE_REASON =
            "the reply could not be handed to the transport on this pass";

    /**
     * How long an assertion waits for a competing claim to be observed blocking, in milliseconds.
     *
     * <p>Trade-offs: a bound is needed because the alternative is a test that hangs when the
     * behaviour it waits for never happens, and a hang is worse than a failure because it reports
     * nothing. The value is generous rather than tight, since it is only ever reached when the
     * expected blocking does not occur at all, in which case the assertion should fail loudly and
     * say so.</p>
     */
    private static final long BLOCK_OBSERVATION_TIMEOUT_MS = 30_000L;

    /**
     * How long an assertion waits for a claim running on another thread to return, in seconds.
     *
     * <p>Assumptions: this bounds a wait on a result that is already unblocked by the time it is
     * read, so it is a guard against a lost signal rather than a tuning value.</p>
     */
    private static final long CLAIM_COMPLETION_TIMEOUT_S = 60L;

    /**
     * How long the loop that watches for a blocked claim pauses between catalogue reads, in
     * milliseconds.
     *
     * <p>Trade-offs: polling is used because the engine offers no way to be notified that a session
     * has started waiting for a lock. The interval is short enough that the wait adds no perceptible
     * time and long enough that the watch does not spin on the catalogue.</p>
     */
    private static final long BLOCK_POLL_INTERVAL_MS = 25L;

    /**
     * The catalogue question that establishes a session is waiting for a lock.
     *
     * <p>Assumptions: this is the deliberate exception to the unqualified-naming discipline, because a
     * catalogue relation cannot be asked about without naming it. It is scoped to the current database
     * so that nothing outside this container can satisfy it, and it reads the wait class rather than a
     * lock's identity because what has to be established is only that a session is waiting, not which
     * row it wants.</p>
     */
    private static final String BLOCKED_SESSION_COUNT_QUERY =
            "SELECT count(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'";

    /**
     * The boundary under test.
     */
    @Autowired
    private OutboxRepository repository;

    /**
     * Runs a unit of work that commits, which every method here needs and none gets for free.
     *
     * <p>Assumptions: this is named for what it guarantees rather than for its type, because the
     * guarantee is the reason it is used. Two things depend on it. A claim modifies a row, and a
     * declared query method's default transaction posture is read-only, so the claim is invoked inside
     * a transaction this template opens read-write. And the concurrency assertion needs one
     * transaction's uncommitted work to be visible as a lock to another connection, which is only
     * expressible with transactions that really begin and really end.</p>
     */
    @Autowired
    private TransactionTemplate commit;

    /**
     * Supplies the extra connection the concurrency assertion watches the engine through.
     *
     * <p>Assumptions: the watching connection has to be outside both racing transactions, because a
     * session cannot observe itself waiting. Borrowing it from the same pool keeps the coordinates in
     * one place; the pool is sized in the test profile well above the three connections the widest
     * assertion here needs at once.</p>
     */
    @Autowired
    private DataSource dataSource;

    /**
     * The per-invocation message bound the module's own configuration carries.
     *
     * <p>Assumptions: this is injected rather than read from a literal so that the assertion on it
     * fails if the configured value ever drifts from the reference figure. The key is the one the test
     * profile declares, and it is the same key the drain reads its argument from in service, which is
     * what makes the bound configuration rather than something compiled into a statement.</p>
     */
    @Value("${carddemo.messaging.request-process-limit}")
    private short configuredProcessLimit;

    /**
     * Publishes the container's coordinates and the migration's credentials as properties.
     *
     * @param registry the registry the test context reads its highest-precedence properties from,
     *     which is written to and never read here
     */
    // WHY : Assumptions: not one connection literal appears in this class or in the module's test
    //       profile, and the omission is the mechanism by which no endpoint or credential reaches the
    //       repository rather than merely being kept out of sight. The container assigns its host port
    //       when it starts, so no literal authored beforehand could be correct: it would point either
    //       at nothing or, on a developer machine, at whatever database happened to be listening on
    //       the guessed port.
    // WHY : Alternatives Considered: contributing the same three values as a connection-details bean
    //       from an annotation on the container field, which is how the sibling integration tests in
    //       four peer modules supply them. It is unavailable here on a classpath fact rather than a
    //       preference, because that annotation's artifact is not among this module's test
    //       dependencies. The boundary this module declares over its own pool records the same
    //       consequence from the other side: a pool built in application code takes the place of the
    //       framework's, so a bean contributed by a harness would not reach it and a container-backed
    //       test has to publish these keys instead.
    // WHY : Assumptions: the two migration credentials are not redundant beside the datasource pair.
    //       The module's application.yml binds them to environment placeholders that carry no
    //       fallback, and they are consulted exactly because no connection-details bean supplies them
    //       instead, so leaving either unregistered aborts the context before a migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the outbox table before each method so that none observes another's rows.
     */
    // WHY : Assumptions: this is a hard requirement here rather than tidiness, because the publication
    //       instant is the selection criterion for every claim below. A pending row left behind by
    //       another method would be eligible for a claim it was never meant to be part of, changing a
    //       row count without changing an order, which is the failure hardest to read correctly.
    // WHY : Alternatives Considered: letting each method roll back instead of clearing. Rejected
    //       because a rolled-back method never commits, and the concurrency assertion below depends on
    //       one transaction's row lock being visible from another connection, which uncommitted work
    //       inside a single test transaction cannot express at all.
    // WHY : Trade-offs: the rows go in one statement, so the persistence context is unaware of the
    //       removal and no method may hold an entity across this boundary. That is accepted because
    //       every method creates the rows it needs after this runs, and the alternative would read
    //       every row into memory purely to delete it one at a time.
    @BeforeEach
    void emptyOutboxTable() {
        this.repository.deleteAllInBatch();
    }

    /**
     * Confirms a committed reply row returns every column it was given.
     */
    // WHY : Assumptions: each column is asserted individually rather than by comparing whole rows,
    //       because a row comparison would pass on an entity whose equality ignores the very columns
    //       under test and would say nothing about which one had moved. The two tokens and the
    //       destination are asserted because each carries a meaning one field of the reference message
    //       descriptor carried: the destination is per request rather than fixed, taken from the
    //       nominated queue name at app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl L741 to L742;
    //       the correlation identity is echoed unaltered at L745; and the wire format is the string
    //       format selected at L751, which is what the content type records here.
    // WHY : Assumptions: the counter, the publication instant and the diagnostic are asserted at their
    //       initial values because those three ARE the row's publishable state, this table declaring
    //       no status column at all. A row is claimable exactly while its publication instant is
    //       absent, so establishing that a freshly committed row has none is what makes every
    //       eligibility assertion below meaningful.
    @Test
    @DisplayName("a committed reply row returns every column it was given")
    void committedRowReturnsEveryColumnItWasGiven() {
        String group = orderGroup("roundtrip", 1);
        String token = deduplication("roundtrip", 1);
        AuthReplyOutbox persisted = persistOne(pendingRow(group, token, BASE_INSTANT));
        assertThat(persisted.getOutboxId()).isNotNull();

        AuthReplyOutbox stored = reload(persisted.getOutboxId());

        assertThat(stored.getReplyQueueUrl()).isEqualTo(REPLY_QUEUE_URL);
        assertThat(stored.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(stored.getOrderGroupToken()).isEqualTo(group);
        assertThat(stored.getDeduplicationToken()).isEqualTo(token);
        assertThat(stored.getContentType()).isEqualTo(AuthReplyOutbox.CONTENT_TYPE_CSV);
        assertThat(stored.getCreatedAt()).isEqualTo(BASE_INSTANT);
        assertThat(stored.getExpiresAt())
                .isEqualTo(BASE_INSTANT.plusSeconds(REPLY_DEADLINE_SECONDS));
        assertThat(stored.getAttempts()).isEqualTo((short) 0);
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getLastError()).isNull();
        assertThat(stored.isPublished()).isFalse();
    }

    /**
     * Confirms the payload returns exactly the characters it was given.
     */
    // WHY : Assumptions: this asserts FIDELITY and deliberately asserts no length, no delimiter count
    //       and no field count. More than one length is correct for the reference reply depending on
    //       what is measured, as the class block above sets out, and settling that question inside a
    //       repository test would fix one reading of it in the wrong place. The payload used here is
    //       not a well-formed reply for the same reason, so nothing in this method can be read as a
    //       statement about the wire.
    // WHY : Trade-offs: both a character comparison and an octet comparison are made, and the second
    //       is kept even though the first already covers today's declared column type. The reason is
    //       specific rather than defensive: the octet form is what states byte fidelity, so if the
    //       column ever moves from character data to binary this assertion keeps the meaning it was
    //       written with instead of quietly weakening to a character-only claim. The accepted cost is
    //       one assertion that is presently implied by its neighbour.
    @Test
    @DisplayName("the payload returns exactly the characters it was given")
    void payloadReturnsExactlyTheCharactersItWasGiven() {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("payload", 1), deduplication("payload", 1), BASE_INSTANT));

        AuthReplyOutbox stored = reload(persisted.getOutboxId());

        assertThat(stored.getPayload()).isEqualTo(OPAQUE_PAYLOAD);
        assertThat(stored.getPayload().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(OPAQUE_PAYLOAD.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Confirms only rows carrying no publication instant are claimable.
     */
    // WHY : Assumptions: the contract is "not yet published" and NOT "not yet claimed", and the
    //       difference is asserted explicitly because a reader expecting the second reading would take
    //       the first for a fault. Both partial indexes serving these statements are restricted by a
    //       null test on the publication instant, so that column alone decides eligibility. A row
    //       already taken by a pass that then died is still unpublished and is therefore still
    //       eligible, which is the property that stops such a reply being stranded; it carries its own
    //       assertion below.
    @Test
    @DisplayName("only rows carrying no publication instant are claimable")
    void onlyRowsCarryingNoPublicationInstantAreClaimable() {
        AuthReplyOutbox pending = persistOne(pendingRow(
                orderGroup("eligible", 1), deduplication("eligible", 1), BASE_INSTANT));
        AuthReplyOutbox published = persistOne(pendingRow(
                orderGroup("eligible", 2), deduplication("eligible", 2), BASE_INSTANT));
        markPublished(published.getOutboxId(), BASE_INSTANT.plusSeconds(1));

        List<AuthReplyOutbox> claimed = claimHeads(UNRESTRICTIVE_BATCH);

        assertThat(identities(claimed)).containsExactly(pending.getOutboxId());
        assertThat(this.repository.countByPublishedAtIsNull()).isEqualTo(1L);
    }

    /**
     * Confirms claiming orders by identity and never by the creation instant.
     */
    // WHY : Assumptions: the three rows are given creation instants running OPPOSITE to their
    //       insertion order, which is what makes this assertion discriminating rather than merely
    //       true. A fixture whose instants only differed would satisfy either ordering rule and so
    //       would prove nothing; inverted instants fail the moment the ordering moves onto the
    //       timestamp. The instants are also fixed literals, so nothing here reads the clock and no
    //       run of this method can differ from another.
    // WHY : Assumptions: identity is the correct ordering key rather than a convenient one. The
    //       creation instant defaults to the statement timestamp, so rows committed together share it
    //       and their relative order is undefined, which lets a bounded drain repeat or skip a row at
    //       a batch boundary under exactly the timing that produces the tie. Order matters at all
    //       because the reply queue preserves order only within an ordering group and only in the
    //       sequence it accepted messages, so an unordered drain would reverse two answers for one
    //       card before the queue ever saw them.
    @Test
    @DisplayName("claiming orders by identity and never by the creation instant")
    void claimOrdersByIdentityAndNotByCreationInstant() {
        List<AuthReplyOutbox> inserted = persistCommitted(
                pendingRow(orderGroup("order", 1), deduplication("order", 1),
                        BASE_INSTANT.plusSeconds(30)),
                pendingRow(orderGroup("order", 2), deduplication("order", 2),
                        BASE_INSTANT.plusSeconds(20)),
                pendingRow(orderGroup("order", 3), deduplication("order", 3),
                        BASE_INSTANT.plusSeconds(10)));

        List<AuthReplyOutbox> claimed = claimHeads(UNRESTRICTIVE_BATCH);

        assertThat(identities(claimed)).containsExactlyElementsOf(identities(inserted));
        assertThat(identities(claimed)).isSorted();
        assertThat(claimed.stream().map(AuthReplyOutbox::getCreatedAt).toList())
                .isSortedAccordingTo(Comparator.<LocalDateTime>reverseOrder());
    }

    /**
     * Confirms a claim takes at most its bound and the remainder on a later pass.
     */
    // WHY : Trade-offs: the bound exercised is two rather than the configured five hundred, so three
    //       rows cross the boundary instead of five hundred and one. The boundary behaviour is
    //       identical at any bound, and the configured figure is asserted separately against the
    //       property that carries it, so nothing is lost by not building the larger fixture and a
    //       great deal of fixture is saved.
    // WHY : Assumptions: the first pass's rows are marked published before the second pass, and that
    //       step is load-bearing rather than tidying. A claimed but unpublished row stays eligible on
    //       purpose, so an immediate second claim would legitimately return the same two rows again
    //       and the remainder would never be reached. Publishing them is what makes the second pass a
    //       statement about the bound rather than about redelivery, which is asserted on its own
    //       below.
    @Test
    @DisplayName("a claim takes at most its bound and the remainder on a later pass")
    void claimTakesAtMostItsBoundAndTheRemainderOnALaterPass() {
        List<AuthReplyOutbox> inserted = persistCommitted(
                pendingRow(orderGroup("bound", 1), deduplication("bound", 1), BASE_INSTANT),
                pendingRow(orderGroup("bound", 2), deduplication("bound", 2), BASE_INSTANT),
                pendingRow(orderGroup("bound", 3), deduplication("bound", 3), BASE_INSTANT));

        List<AuthReplyOutbox> firstPass = claimHeads(SMALL_BATCH);

        assertThat(identities(firstPass)).containsExactly(
                inserted.get(0).getOutboxId(), inserted.get(1).getOutboxId());

        firstPass.forEach(row -> markPublished(row.getOutboxId(), BASE_INSTANT.plusSeconds(1)));
        List<AuthReplyOutbox> secondPass = claimHeads(SMALL_BATCH);

        assertThat(identities(secondPass)).containsExactly(inserted.get(2).getOutboxId());
        assertThat(this.repository.countByPublishedAtIsNull()).isEqualTo(1L);
    }

    /**
     * Confirms the configured per-invocation bound carries the reference consumer's own figure.
     */
    // WHY : Assumptions: this asserts the bound's PROVENANCE, which is the half a boundary assertion
    //       cannot cover. The declaration under test takes the bound as an argument, so a statement
    //       about how many rows one pass returns says nothing about where that number came from;
    //       reading it from the property instead proves the drain is bounded by configuration rather
    //       than by a figure compiled into a statement. The reference figure is
    //       WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500, declared at
    //       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl L40, whose loop ends at L339 once the
    //       processed count exceeds it, so keeping the two equal is what stops the migration silently
    //       changing the throughput characteristic it inherited.
    @Test
    @DisplayName("the configured per-invocation bound carries the reference figure")
    void configuredBoundCarriesTheReferenceFigure() {
        assertThat(this.configuredProcessLimit).isEqualTo(REFERENCE_PROCESS_LIMIT);
    }

    /**
     * Confirms a claim advances the attempt counter of the row it takes.
     */
    // WHY : Assumptions: the counter is the transition's observable half, so this is the positive case
    //       of the mechanism that replaces a held lock. The row is read back as well as inspected as
    //       returned, because the returned instance is what the claiming statement produced and only a
    //       fresh read establishes that the change was committed rather than merely constructed.
    // WHY : Assumptions: the publication instant is asserted still absent, because a claim is not a
    //       publication. Conflating the two would make a claimed row look sent, and the row would then
    //       be unrecoverable if the pass that claimed it never reached the queue.
    @Test
    @DisplayName("a claim advances the attempt counter of the row it takes")
    void claimAdvancesTheAttemptCounterOfTheRowItTakes() {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("advance", 1), deduplication("advance", 1), BASE_INSTANT));

        List<AuthReplyOutbox> claimed = claimHeads(UNRESTRICTIVE_BATCH);

        assertThat(identities(claimed)).containsExactly(persisted.getOutboxId());
        assertThat(claimed.get(0).getAttempts()).isEqualTo((short) 1);

        AuthReplyOutbox stored = reload(persisted.getOutboxId());

        assertThat(stored.getAttempts()).isEqualTo((short) 1);
        assertThat(stored.getPublishedAt()).isNull();
    }

    /**
     * Confirms a claim takes nothing from an already published row and does not raise.
     */
    // WHY : Assumptions: the absence of a raise is asserted explicitly rather than inferred from the
    //       call having returned, because finding nothing to claim is the ORDINARY outcome of a drain
    //       against an empty backlog and not an exceptional one. If it raised, a scheduled drain would
    //       report a failure on every quiet interval, and an operator would learn to ignore the alarm
    //       that matters.
    // WHY : Assumptions: the claim is issued twice so that the second call is made against a table in
    //       the same state as the first left it, which is what establishes that an empty claim changes
    //       nothing and is therefore repeatable.
    @Test
    @DisplayName("a claim takes nothing from an already published row and does not raise")
    void claimTakesNothingFromAnAlreadyPublishedRowAndDoesNotRaise() {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("published", 1), deduplication("published", 1), BASE_INSTANT));
        markPublished(persisted.getOutboxId(), BASE_INSTANT.plusSeconds(1));

        assertThat(claimHeads(UNRESTRICTIVE_BATCH)).isEmpty();
        assertThatCode(() -> claimHeads(UNRESTRICTIVE_BATCH)).doesNotThrowAnyException();
        assertThat(reload(persisted.getOutboxId()).getAttempts()).isEqualTo((short) 0);
    }

    /**
     * Confirms a row already claimed but not yet published stays claimable.
     */
    // WHY : Refactoring Rationale: this is the property that makes the outbox worth having, so it is
    //       asserted rather than left implicit. The reference consumer puts its reply outside any unit
    //       of work, at the MQPUT1 of
    //       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl L758 flagged MQPMO-NO-SYNCPOINT at L753,
    //       so a put that does not happen cannot be retried by anything: the request was consumed with
    //       the same no-syncpoint option at L389 and cannot be presented again to re-derive the answer.
    //       Here the row outlives the pass that claimed it, so a later pass takes it and the reply is
    //       still sent. Excluding a claimed row would reintroduce the lost reply from the other side,
    //       by stranding the row that was added to prevent it.
    // WHY : Trade-offs: the accepted cost is a duplicate publication when a pass sends and then fails
    //       before recording the send, since the row it left behind is claimed again. That is
    //       tolerable only because the reply queue suppresses the duplicate on the deduplication token
    //       stored on the row, and it is the right way round: a duplicate is suppressible downstream
    //       whereas a reply that was never sent is not recoverable by anything.
    @Test
    @DisplayName("a row already claimed but not yet published stays claimable")
    void rowAlreadyClaimedButNotPublishedStaysClaimable() {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("reclaim", 1), deduplication("reclaim", 1), BASE_INSTANT));

        List<AuthReplyOutbox> firstClaim = claimHeads(UNRESTRICTIVE_BATCH);
        List<AuthReplyOutbox> secondClaim = claimHeads(UNRESTRICTIVE_BATCH);

        assertThat(identities(firstClaim)).containsExactly(persisted.getOutboxId());
        assertThat(identities(secondClaim)).containsExactly(persisted.getOutboxId());
        assertThat(secondClaim.get(0).getAttempts()).isEqualTo((short) 2);
        assertThat(reload(persisted.getOutboxId()).getPublishedAt()).isNull();
    }

    /**
     * Confirms two claims reaching one row together leave exactly one winner.
     *
     * @throws Exception if a claim running on another thread cannot be collected, which is a failure
     *     of the coordination in this method rather than of the boundary under test
     */
    // WHY : Assumptions: the two claims run on two threads over two connections and genuinely
    //       overlap, which is the only arrangement that tests anything. Two sequential calls on one
    //       connection would both succeed and would do so correctly, because the second would read the
    //       counter the first had already advanced and its predicate would match that new value; the
    //       assertion would then pass while the contention it claims to exercise had never occurred.
    //       The overlap is established by asking the engine whether a session is waiting for a lock,
    //       not by pausing and hoping.
    // WHY : Assumptions: the outcome is deterministic once the overlap holds. The second claim reads
    //       the row at the counter value the first has not yet committed, so its update waits on the
    //       first transaction's row lock; when that transaction commits, the predicate naming the
    //       previously observed value no longer holds and the statement changes no row, so the claim
    //       returns nothing. That is the whole mechanism: the returned rows are the rows transitioned,
    //       and the row can be transitioned from a given counter value only once.
    // WHY : Alternatives Considered: asserting that one of the two calls raised, which is what a
    //       pessimistic lock with a no-wait or a skip behaviour would have produced. Rejected because
    //       the design under test deliberately takes no lock: the losing pass is meant to observe an
    //       ordinary empty result and move on, and an exception on a contended claim would turn
    //       routine concurrency into an error an operator has to triage.
    @Test
    @DisplayName("two claims reaching one row together leave exactly one winner")
    void twoClaimsReachingOneRowTogetherLeaveExactlyOneWinner() throws Exception {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("race", 1), deduplication("race", 1), BASE_INSTANT));
        CountDownLatch claimTaken = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        ExecutorService claimants = Executors.newFixedThreadPool(2);
        try {
            Future<List<AuthReplyOutbox>> holder = claimants.submit(
                    () -> this.commit.execute(status -> {
                        List<AuthReplyOutbox> taken =
                                this.repository.claimGroupHeads(UNRESTRICTIVE_BATCH);
                        claimTaken.countDown();
                        awaitSignal(mayCommit);
                        return taken;
                    }));
            Future<List<AuthReplyOutbox>> contender = claimants.submit(() -> {
                awaitSignal(claimTaken);
                return this.commit.execute(
                        status -> this.repository.claimGroupHeads(UNRESTRICTIVE_BATCH));
            });

            awaitBlockedSession();
            mayCommit.countDown();
            List<AuthReplyOutbox> winner = holder.get(CLAIM_COMPLETION_TIMEOUT_S, TimeUnit.SECONDS);
            List<AuthReplyOutbox> loser =
                    contender.get(CLAIM_COMPLETION_TIMEOUT_S, TimeUnit.SECONDS);

            assertThat(identities(winner)).containsExactly(persisted.getOutboxId());
            assertThat(loser).isEmpty();
            assertThat(reload(persisted.getOutboxId()).getAttempts()).isEqualTo((short) 1);
        } finally {
            claimants.shutdownNow();
        }
    }

    /**
     * Confirms a claim takes one row per ordering group and that followers advance within a group.
     */
    // WHY : Assumptions: at most one row per ordering group is taken by a pass, and that is what
    //       preserves per-card order rather than being an incidental effect of grouping. The reply
    //       queue orders messages within a group only in the sequence it accepted them, so a pass
    //       holding two rows of one group whose earlier send failed while the drain continued would
    //       place the later reply ahead of the earlier one, reversing two answers for one card, which
    //       is the single guarantee the group token exists to give.
    // WHY : Assumptions: the follow-on claim is bounded BELOW by the identity just handled rather than
    //       by re-reading the group's head, so a row this pass has already handled cannot come back
    //       whatever its stored state now says. The bound is exclusive, which is asserted directly:
    //       passing the last handled identity a second time has to yield nothing, since an inclusive
    //       bound would hand that row back and a pass would publish it twice within one cycle.
    @Test
    @DisplayName("a claim takes one row per ordering group and followers advance within a group")
    void claimTakesOneRowPerGroupAndFollowersAdvanceWithinIt() {
        String busyGroup = orderGroup("group", 1);
        String quietGroup = orderGroup("group", 2);
        List<AuthReplyOutbox> inserted = persistCommitted(
                pendingRow(busyGroup, deduplication("group", 1), BASE_INSTANT),
                pendingRow(busyGroup, deduplication("group", 2), BASE_INSTANT),
                pendingRow(busyGroup, deduplication("group", 3), BASE_INSTANT),
                pendingRow(quietGroup, deduplication("group", 4), BASE_INSTANT));
        long busyHead = inserted.get(0).getOutboxId();
        long busyTail = inserted.get(2).getOutboxId();

        List<AuthReplyOutbox> heads = claimHeads(UNRESTRICTIVE_BATCH);

        assertThat(identities(heads))
                .containsExactly(busyHead, inserted.get(3).getOutboxId());

        List<AuthReplyOutbox> followers =
                claimFollowers(busyGroup, busyHead, UNRESTRICTIVE_BATCH);

        assertThat(identities(followers))
                .containsExactly(inserted.get(1).getOutboxId(), busyTail);
        assertThat(claimFollowers(busyGroup, busyTail, UNRESTRICTIVE_BATCH)).isEmpty();
    }

    /**
     * Confirms marking published twice changes nothing and does not make the row claimable again.
     */
    // WHY : Assumptions: publication is at-least-once, so the same row can be marked more than once
    //       and marking has to be safe to repeat. Both halves are asserted because either alone would
    //       pass over a real fault: checking only that the second mark did not raise would pass even
    //       if it had reverted the row to publishable, and checking only the stored instant would pass
    //       even if the row had somehow become eligible again. Re-running the claim afterwards is what
    //       closes that.
    // WHY : Assumptions: the attempt counter is asserted unchanged by marking, because marking is not
    //       an attempt. A mark that advanced the counter would make a published row look freshly tried,
    //       and the counter is the only field a reader has to tell how often publication was attempted.
    // WHY : Assumptions: no affected-row count is asserted, and none exists to assert. The boundary
    //       declares no marking statement: the transition belongs to the entity and reaches the table
    //       when the surrounding transaction commits, so the equivalent signal that the second mark
    //       was inert is that the stored row is byte-for-byte in the state the first mark left it and
    //       is still not selectable.
    @Test
    @DisplayName("marking published twice changes nothing and does not make the row claimable")
    void markingPublishedTwiceChangesNothingAndDoesNotMakeTheRowClaimable() {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("idempotent", 1), deduplication("idempotent", 1), BASE_INSTANT));
        LocalDateTime publishedAt = BASE_INSTANT.plusSeconds(2);

        markPublished(persisted.getOutboxId(), publishedAt);
        AuthReplyOutbox afterFirstMark = reload(persisted.getOutboxId());
        markPublished(persisted.getOutboxId(), publishedAt);
        AuthReplyOutbox afterSecondMark = reload(persisted.getOutboxId());

        assertThat(afterFirstMark.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(afterSecondMark.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(afterSecondMark.getAttempts()).isEqualTo(afterFirstMark.getAttempts());
        assertThat(afterSecondMark.isPublished()).isTrue();
        assertThat(claimHeads(UNRESTRICTIVE_BATCH)).isEmpty();
        assertThat(this.repository.countByPublishedAtIsNull()).isZero();
    }

    /**
     * Confirms a recorded failure advances the counter and leaves the row claimable.
     */
    // WHY : Assumptions: this is the whole of the repository's relationship to a failed publication,
    //       and the boundary is worth naming because the tempting assertion lies just past it. That a
    //       message reached a queue, an error sink or a dead-letter path is the service's to assert and
    //       needs a broker or a stand-in this package has none of; what belongs here is only that the
    //       attempt was counted, the diagnostic was stored, and the reply was not silently written off.
    // WHY : Assumptions: the row must remain claimable after a failure, since a reply whose first send
    //       failed is precisely the reply this table exists to guarantee. A failure that made the row
    //       ineligible would discard an answer the committed data says was produced.
    @Test
    @DisplayName("a recorded failure advances the counter and leaves the row claimable")
    void recordedFailureAdvancesTheCounterAndLeavesTheRowClaimable() {
        AuthReplyOutbox persisted = persistOne(pendingRow(
                orderGroup("failure", 1), deduplication("failure", 1), BASE_INSTANT));

        recordFailedAttempt(persisted.getOutboxId(), PUBLICATION_FAILURE_REASON);
        AuthReplyOutbox afterFailure = reload(persisted.getOutboxId());

        assertThat(afterFailure.getAttempts()).isEqualTo((short) 1);
        assertThat(afterFailure.getLastError()).isEqualTo(PUBLICATION_FAILURE_REASON);
        assertThat(afterFailure.getPublishedAt()).isNull();
        assertThat(identities(claimHeads(UNRESTRICTIVE_BATCH)))
                .containsExactly(persisted.getOutboxId());
    }

    /**
     * Confirms retention removes only rows published before the cut-off.
     */
    // WHY : Assumptions: that an unpublished row can never be removed however old it is, is the one
    //       property this statement must have, so it is asserted with a row deliberately created long
    //       before the cut-off. An unpublished row is the reply the table exists to guarantee, and
    //       deleting one would discard an answer the committed data says was produced, reintroducing
    //       from the retention side exactly the outcome the row was written to prevent.
    // WHY : Alternatives Considered: removing each row as it is published, which would need no sweep
    //       at all. Rejected because a short published history is what lets an operator answer whether
    //       a reply was ever sent for a given transaction, and that is the question the reference
    //       system's separate queue and database units of work leave unanswerable. The retention window
    //       is the caller's parameter for the same reason, so the answer stays available for as long as
    //       it is needed and no longer.
    @Test
    @DisplayName("retention removes only rows published before the cut-off")
    void retentionRemovesOnlyRowsPublishedBeforeTheCutOff() {
        LocalDateTime cutoff = BASE_INSTANT.plusSeconds(60);
        List<AuthReplyOutbox> inserted = persistCommitted(
                pendingRow(orderGroup("retention", 1), deduplication("retention", 1),
                        BASE_INSTANT.minusDays(400)),
                pendingRow(orderGroup("retention", 2), deduplication("retention", 2), BASE_INSTANT),
                pendingRow(orderGroup("retention", 3), deduplication("retention", 3), BASE_INSTANT));
        long neverPublished = inserted.get(0).getOutboxId();
        long publishedBefore = inserted.get(1).getOutboxId();
        long publishedAfter = inserted.get(2).getOutboxId();
        markPublished(publishedBefore, cutoff.minusSeconds(1));
        markPublished(publishedAfter, cutoff.plusSeconds(1));

        int removed = deletePublishedBefore(cutoff);

        assertThat(removed).isEqualTo(1);
        assertThat(this.repository.findById(publishedBefore)).isEmpty();
        assertThat(this.repository.findById(publishedAfter)).isPresent();
        assertThat(this.repository.findById(neverPublished)).isPresent();
        assertThat(identities(claimHeads(UNRESTRICTIVE_BATCH))).containsExactly(neverPublished);
    }

    /**
     * Builds one unpublished reply row, ready to be persisted.
     *
     * @param orderGroupToken the ordering group the row belongs to, which decides whether a claim
     *     treats it as a head or as a follower
     * @param deduplicationToken the duplicate-suppression token the row carries, distinct per row so
     *     that two rows are never indistinguishable to a reader of the table
     * @param createdAt the creation instant to store, supplied explicitly rather than defaulted
     * @return a new row carrying no publication instant, a zero attempt counter and the opaque
     *     payload, so that it is publishable the moment it is committed
     */
    // WHY : Assumptions: the creation instant is passed in rather than left to the column's default,
    //       and that is what lets the ordering assertion invert it deliberately. Taking the default
    //       would make every row of one transaction share the statement timestamp, which is precisely
    //       the tie that makes a timestamp-ordered drain able to repeat or skip a row.
    // WHY : Assumptions: the expiry is set five seconds past the creation instant to mirror the
    //       reference reply's own deadline, which app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl
    //       expresses at L750 as MOVE 50 TO MQMD-EXPIRY in a field denominated in tenths of a second.
    //       Whether that deadline is honoured is service behaviour and is not asserted here; the value
    //       is set so the column is exercised with a realistic one rather than left null.
    private AuthReplyOutbox pendingRow(
            String orderGroupToken, String deduplicationToken, LocalDateTime createdAt) {
        return new AuthReplyOutbox(REPLY_QUEUE_URL, CORRELATION_ID, orderGroupToken,
                deduplicationToken, OPAQUE_PAYLOAD, createdAt.plusSeconds(REPLY_DEADLINE_SECONDS),
                createdAt);
    }

    /**
     * Commits the given rows and hands them back carrying the identities the engine assigned.
     *
     * @param rows the rows to insert, in the order their identities are to ascend
     * @return the same rows after insertion, in the order supplied, each holding its generated
     *     identity
     */
    // WHY : Assumptions: insertion order and identity order are the same thing here, because the
    //       identity is generated by the engine as each row is inserted. Every assertion below that
    //       speaks of an earliest or a lowest row relies on that, so the rows are handed over in the
    //       order the assertion means rather than sorted afterwards.
    private List<AuthReplyOutbox> persistCommitted(AuthReplyOutbox... rows) {
        return this.commit.execute(status -> this.repository.saveAll(List.of(rows)));
    }

    /**
     * Commits one row and hands it back carrying the identity the engine assigned.
     *
     * @param row the row to insert
     * @return the same row after insertion, holding its generated identity
     */
    private AuthReplyOutbox persistOne(AuthReplyOutbox row) {
        return persistCommitted(row).get(0);
    }

    /**
     * Claims the head row of each pending ordering group, up to the given bound, and commits.
     *
     * @param batchSize the greatest number of ordering groups to take a head row from
     * @return the rows the claim actually transitioned, in ascending identity order, which is empty
     *     when nothing was eligible
     */
    // WHY : Assumptions: the claim runs inside an explicitly read-write transaction because it
    //       modifies the rows it returns. A declared query method's default transaction posture is
    //       read-only, and this engine refuses to modify a row inside a read-only transaction, so
    //       calling the claim bare would fail for a reason unrelated to whatever was being asserted.
    //       Committing also matters in its own right: a claim whose transition were rolled back would
    //       leave the counter where it started, and the next assertion in the same method would then
    //       observe a row that had never been claimed.
    private List<AuthReplyOutbox> claimHeads(int batchSize) {
        return this.commit.execute(status -> this.repository.claimGroupHeads(batchSize));
    }

    /**
     * Claims the pending rows of one ordering group above a given identity, and commits.
     *
     * @param orderGroupToken the ordering group to advance within
     * @param afterOutboxId the identity already handled, treated as an exclusive lower bound
     * @param batchSize the greatest number of follow-on rows to take
     * @return the rows the claim actually transitioned, in ascending identity order, which is empty
     *     when the group holds no further pending row above the bound
     */
    private List<AuthReplyOutbox> claimFollowers(
            String orderGroupToken, long afterOutboxId, int batchSize) {
        return this.commit.execute(status ->
                this.repository.claimGroupFollowers(orderGroupToken, afterOutboxId, batchSize));
    }

    /**
     * Records one row as published at the given instant, and commits.
     *
     * @param outboxId the identity of the row to mark
     * @param publishedAt the instant to record as the publication instant
     */
    // WHY : Assumptions: marking is a state transition on the loaded row rather than a statement on
    //       the boundary, because the boundary declares no marking method: the entity owns the
    //       transition and the persistence context writes it out when this transaction commits. That
    //       is why the idempotency assertion below reads the row back instead of inspecting a count of
    //       affected rows, which no marking call here produces.
    private void markPublished(long outboxId, LocalDateTime publishedAt) {
        this.commit.executeWithoutResult(status ->
                loadWithinTransaction(outboxId).markPublished(publishedAt));
    }

    /**
     * Records one failed publication attempt against a row, and commits.
     *
     * @param outboxId the identity of the row to charge the attempt to
     * @param reason the diagnostic to store against the row
     */
    private void recordFailedAttempt(long outboxId, String reason) {
        this.commit.executeWithoutResult(status ->
                loadWithinTransaction(outboxId).recordFailedAttempt(reason));
    }

    /**
     * Loads one row inside the caller's transaction so that changes to it are written out.
     *
     * @param outboxId the identity of the row to load
     * @return the managed row
     * @throws IllegalStateException if no row carries that identity, which means a preceding step of
     *     the same method did not persist what it was expected to
     */
    private AuthReplyOutbox loadWithinTransaction(long outboxId) {
        return this.repository.findById(outboxId).orElseThrow(() -> new IllegalStateException(
                "No outbox row carries identity " + outboxId));
    }

    /**
     * Reads one row back from the table as it now stands.
     *
     * @param outboxId the identity of the row to read
     * @return the row as stored, detached from any persistence context
     * @throws IllegalStateException if no row carries that identity
     */
    // WHY : Assumptions: the row is read back rather than reused from an earlier result, because a
    //       claim returns the row as its own statement produced it and a mark leaves an instance whose
    //       state was set in memory. Reading again is what makes each assertion a statement about the
    //       table rather than about an object this class is still holding.
    private AuthReplyOutbox reload(long outboxId) {
        return this.repository.findById(outboxId).orElseThrow(() -> new IllegalStateException(
                "No outbox row carries identity " + outboxId));
    }

    /**
     * Deletes rows published before a cut-off, and commits.
     *
     * @param cutoff the instant a published row must precede to be removed
     * @return how many rows were removed
     */
    // WHY : Assumptions: this is wrapped for the same reason the claim is. A bulk delete needs a
    //       read-write transaction, and the boundary declares none of its own, so invoking it bare
    //       would raise before the retention rule under test had a chance to be exercised.
    private int deletePublishedBefore(LocalDateTime cutoff) {
        return this.commit.execute(status -> this.repository.deletePublishedBefore(cutoff));
    }

    /**
     * Builds an ordering-group token scoped to one method.
     *
     * @param discriminator a fragment unique to the calling method
     * @param ordinal which group within that method, so one method can hold several
     * @return a token no other method uses
     */
    // WHY : Assumptions: tokens are derived per method because the publication instant is the
    //       selection criterion, so a group token shared between two methods would let one method's
    //       pending row be claimed as another's group head and change a count without changing an
    //       order.
    private static String orderGroup(String discriminator, int ordinal) {
        return "OGT-" + discriminator + "-" + ordinal;
    }

    /**
     * Builds a duplicate-suppression token scoped to one row.
     *
     * @param discriminator a fragment unique to the calling method
     * @param ordinal which row within that method
     * @return a token no other row uses
     */
    private static String deduplication(String discriminator, int ordinal) {
        return "DDT-" + discriminator + "-" + ordinal;
    }

    /**
     * Extracts the identities of a claim's result, in the order the claim returned them.
     *
     * @param rows the rows a claim returned
     * @return their identities in the same order, so that an ordering assertion compares numbers
     *     rather than entities
     */
    private static List<Long> identities(List<AuthReplyOutbox> rows) {
        return rows.stream().map(AuthReplyOutbox::getOutboxId).toList();
    }

    /**
     * Waits until the engine reports a session waiting for a lock.
     *
     * @throws AssertionError if no session is seen waiting before the observation bound elapses,
     *     which means the two claims never overlapped and the assertion that depends on the overlap
     *     was not exercised
     */
    // WHY : Assumptions: this is what makes the concurrency assertion deterministic instead of
    //       dependent on how fast the host runs. The two claims only contend if the second one's
    //       statement begins while the first one's transaction is still open; if the second ran
    //       entirely after the first committed it would read the already-advanced counter, its
    //       predicate would match that value, and it would legitimately claim the row a second time.
    //       Waiting for the engine to report the second session blocked establishes the overlap
    //       rather than assuming it.
    // WHY : Alternatives Considered: pausing the first transaction for a fixed interval before it
    //       commits, on the assumption that the interval is long enough for the second to reach the
    //       lock. Rejected because the assumption is exactly what fails on a loaded machine, and it
    //       fails by turning a real assertion into a passing one: the second claim would then succeed
    //       and the test would report the design working when it had not been tested. Asking the
    //       engine cannot pass for the wrong reason, and when the overlap genuinely does not happen it
    //       says so.
    private void awaitBlockedSession() {
        long deadline = System.currentTimeMillis() + BLOCK_OBSERVATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (blockedSessionCount() > 0L) {
                return;
            }
            pause(BLOCK_POLL_INTERVAL_MS);
        }
        throw new AssertionError("No session was observed waiting for a lock within "
                + BLOCK_OBSERVATION_TIMEOUT_MS + " ms, so the two claims never overlapped and the"
                + " conditional predicate was never put under contention");
    }

    /**
     * Counts the sessions of this database currently waiting for a lock.
     *
     * @return how many sessions the engine reports in a lock wait, which is zero when none is
     *     contended
     * @throws IllegalStateException if the catalogue cannot be read or returns no row
     */
    // WHY : Assumptions: this borrows a third connection deliberately, because a session cannot
    //       observe itself waiting: both racing transactions are occupied, one holding a row and one
    //       blocked on it, so the question has to be asked from outside both.
    private long blockedSessionCount() {
        try (Connection connection = this.dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(BLOCKED_SESSION_COUNT_QUERY)) {
            if (!resultSet.next()) {
                throw new IllegalStateException(
                        "The engine returned no row while counting sessions in a lock wait");
            }
            return resultSet.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read the count of sessions waiting for a lock", failure);
        }
    }

    /**
     * Waits for a coordinating signal, converting an interruption into a failure that names it.
     *
     * @param signal the latch a cooperating thread releases
     * @throws IllegalStateException if the signal does not arrive within the completion bound, or if
     *     the waiting thread is interrupted
     */
    // WHY : Assumptions: the wait is bounded and the interrupt flag is restored rather than swallowed,
    //       because an unbounded wait here would hang the whole run and report nothing at all, which
    //       is strictly less useful than a failure naming the signal that never came.
    private static void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(CLAIM_COMPLETION_TIMEOUT_S, TimeUnit.SECONDS)) {
                throw new IllegalStateException("A coordinating signal did not arrive within "
                        + CLAIM_COMPLETION_TIMEOUT_S + " seconds");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a coordinating signal", interruption);
        }
    }

    /**
     * Pauses the calling thread, converting an interruption into a failure that names it.
     *
     * @param millis how long to pause for
     * @throws IllegalStateException if the pause is interrupted
     */
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while watching for a contended claim", interruption);
        }
    }

    /**
     * The smallest application context that can answer a question about this table.
     *
     * <p>Alternatives Considered: starting this module's real entry point, which would assemble the
     * whole service. Rejected because none of what that adds is needed to ask a question of one table
     * and each part of it is a further way for this class to fail for an unrelated reason: the
     * transport listener, the resource-server filter chain that resolves an identity provider at start
     * up, the published contract, the metric exporter and the pool this module builds in application
     * code with its own start-up probe against a schema a container has never had bootstrapped. Naming
     * the two persistence packages leaves the framework's own configuration to build a pool from the
     * properties the container registered, and nothing else is brought up.</p>
     *
     * <p>Trade-offs: it is declared nested rather than as a file of its own, which keeps this package's
     * file set as its charter describes it at the cost of not being reusable by a sibling. The cost is
     * accepted for the same reason the charter rejects a shared container holder: two configurations
     * enabling different subsets of the framework's own set-up would let two classes disagree about
     * the context they reach one schema through, and a disagreement of that kind shows up as one class
     * failing for a reason the other cannot reproduce.</p>
     *
     * <p>Alternatives Considered: two pieces of the framework's own set-up are excluded by name
     * instead of being satisfied, and the choice matters because satisfying either would have been
     * the worse option. The resource-server set-up reads this module's identity-provider address
     * while it is still deciding which beans to define, and the module's base profile binds that
     * address to an environment placeholder with no fallback, so the context fails before a single
     * bean exists. Supplying an address instead was rejected on two independent grounds: the decoder
     * that address configures is a singleton, so it would be built during start up and would try to
     * fetch the provider's metadata over the network from a test that must not need one; and the
     * module's own test profile records, in its register of deliberate omissions, that no
     * endpoint-shaped literal may be committed, naming this very key as one a context-loading test
     * must supply for itself. Excluding the set-up satisfies both, because a repository assertion
     * authorises nobody. The messaging client set-up is excluded on the plainer ground that this
     * package asserts nothing about a queue at all, so a client for one is a bean that can only
     * fail.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.authorization.domain")
    @EnableJpaRepositories("com.carddemo.authorization.repository")
    static class OutboxPersistenceTestApplication {
    }
}
