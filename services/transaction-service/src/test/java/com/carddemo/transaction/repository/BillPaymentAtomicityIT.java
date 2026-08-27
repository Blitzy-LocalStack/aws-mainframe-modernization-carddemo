package com.carddemo.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.money.Money;
import com.carddemo.transaction.dto.BillPaymentOutcome;
import com.carddemo.transaction.dto.BillPaymentPreview;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.service.AccountContextClient;
import com.carddemo.transaction.service.BillPaymentService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the bill payment unit of work onto a real PostgreSQL engine: that the ledger row and the
 * account balance change commit together, and that neither of them survives a failure of the other.
 *
 * <h2>Why this class exists at all, stated as the gap it closes</h2>
 *
 * <p>Refactoring Rationale: the property asserted here cannot be asserted with mocks, and an earlier
 * revision of {@code BillPaymentServiceTest} claimed it with them. That class counts the transactional
 * annotations on {@link BillPaymentService} and finds exactly one on the entry point, which establishes
 * that a boundary is DECLARED; it then stubs both collaborators, so no statement is issued, nothing is
 * written, and nothing can be rolled back. Whether both effects are ENROLLED in that boundary depends
 * entirely on which connection each one is issued on, and a stub records a call without touching a
 * connection at all. Worse, the arrangement that revision documented could not have had the property
 * under any reading: the balance change was an HTTP call to another service, which no local transaction
 * can enlist. So the strongest claim available from a mocked test was being read as the strongest claim
 * there is. This class supplies the missing half against a real engine and is cited by name from the
 * two cases in that class whose Javadoc now records what they do not prove.
 *
 * <h2>What is under test, and the two directions of half-applied state</h2>
 *
 * <p><b>Purpose.</b> {@code app/cbl/COBIL00C.cbl} writes the payment row at L233, computes the reduced
 * balance at L234 and rewrites the account master at L235, all inside one CICS task, so the implicit
 * task-end syncpoint commits the three effects as one. Two half-applied states are therefore
 * unreachable in the baseline and must stay unreachable here:
 *
 * <ul>
 *   <li>a payment row with no balance reduction against it, which is a payment the operator was told
 *       succeeded and that the account never reflected; and</li>
 *   <li>a balance reduction with no payment row, which is money moved with nothing recording that it
 *       moved -- the worse of the two, because a ledger reconciliation cannot even see it.</li>
 * </ul>
 *
 * <p>Assumptions: the second direction is the one the HTTP arrangement could not close, and it is the
 * one this class's enclosing-failure case addresses. The first direction is closed by the balance change
 * being issued last and propagating, which the failed-change case addresses. Both cases read BOTH tables
 * back afterwards, because a case that read only the table it expected to change would pass while the
 * other one held the residue.
 *
 * <h2>How the two schemas are reached, and what this class does not claim</h2>
 *
 * <p>Assumptions: {@code ledger} is built by Flyway from this module's own migration and {@code account}
 * is supplied by a Testcontainers init script, and the split is not arbitrary. This module owns the
 * ledger migration; it does not own {@code account.accounts}, so it may not migrate that table -- doing
 * so would put a second definition of another context's table in the repository, under the control of a
 * context that does not own it. The init script is the analogue of a provisioned environment in which
 * the account context has already run its own migration, and it runs strictly before Flyway opens its
 * first connection. The script itself records the three alternatives it rejects.
 *
 * <p>Trade-offs: this class proves the STATEMENTS and the transaction boundary and makes no claim about
 * PRIVILEGES. Section 4b of {@code data-migration/sql/V0__schemas_and_roles.sql} grants role
 * {@code carddemo_ledger} {@code USAGE} on the {@code account} schema and {@code SELECT, UPDATE} on
 * {@code account.accounts} by name, and no container database runs that file -- the harness connects as
 * the container's own role, which needs no grant. A test that tried to reproduce the privilege graph
 * would have to reproduce the role hierarchy of a file this module does not own, and would then be
 * asserting that file rather than this code. The gap is real and is named here rather than left for a
 * reader to discover: a missing grant surfaces as SQLSTATE 42501 in a deployed environment and no
 * in-process test reaches it.
 *
 * <h2>The failure injection, and why it is the engine's own arithmetic</h2>
 *
 * <p>Alternatives Considered: four ways to make the balance change fail inside a real transaction were
 * available.
 *
 * <ul>
 *   <li>Stubbing the gateway to throw. Rejected: that is the mocked test again, and it cannot show that
 *       the ledger row was discarded because no ledger row was ever written.</li>
 *   <li>A trigger on {@code account.accounts} that raises. Rejected because it would make the harness
 *       table differ from {@code V1__account.sql}, and the harness's whole value is that it does
 *       not -- a table shaped unlike the deployed one lets a test pass against a shape nothing has.</li>
 *   <li>Dropping the table or killing the connection mid-transaction. Rejected as non-deterministic: the
 *       first needs a second session that blocks on the row lock the payment holds, and the second
 *       produces a failure whose exact type depends on timing.</li>
 *   <li>Seeding the row's {@code version} at the largest value {@code BIGINT} holds, so that the
 *       {@code version = version + 1} clause of the reduction overflows. ADOPTED. The value is inside the
 *       column's declared domain, so no schema deviation is needed; the failure is raised by the engine
 *       on the exact statement under test, inside the transaction, after the ledger row has been flushed;
 *       and it is reproducible on every run.</li>
 * </ul>
 *
 * <p>Assumptions: the overflow arrives at the service as a framework data-access exception rather than as
 * a provider exception, and that conversion is itself under test here. {@link AccountBalanceRepository} is
 * annotated as a repository, which makes it eligible for the persistence-exception translation
 * post-processor Boot auto-configures; {@link BillPaymentService} catches only the framework family, so
 * if translation were absent the raw provider exception would escape uncaught and the operator would see
 * a generic internal sentence instead of the reference's own. Asserting the sentence is therefore also
 * asserting that the translation is wired.
 *
 * <h2>Non-vacuity: every negative assertion is guarded by a row that must survive</h2>
 *
 * <p>Assumptions: each case starts with one pre-existing ledger row and reads the table back through a
 * plain JDBC handle outside any transaction. Both halves of that are load-bearing. The pre-existing row
 * means an assertion that the payment row is absent cannot pass because the read mechanism sees nothing
 * at all -- a broken read would fail on the guard first. And reading outside a transaction is what makes
 * "survived" mean committed: a read inside the same persistence context would be answered from the
 * first-level cache, and a read inside a transaction that had not ended could see its own uncommitted
 * work.
 *
 * <p>Trade-offs: the identifier the payment mints is derived rather than fixed, and the guard row is what
 * fixes it. {@link BillPaymentService} reads the highest stored identifier and adds one, so seeding
 * {@code 0000000000000041} makes the derived identifier {@code 0000000000000042} on every case and lets
 * the assertion name an exact value instead of matching a pattern. The identifier derivation itself is
 * pinned by the unit tests of the service; what is pinned here is that the row carrying it commits.
 *
 * <h2>The container, the profile and the context, decided as the siblings decided them</h2>
 *
 * <p>Assumptions: the engine image is named by manifest digest, the connection coordinates arrive as a
 * bean through {@code @ServiceConnection}, the {@code test} profile carries the only mechanism that
 * reaches the {@code ledger} schema inside a container database that starts empty, and the nested
 * configuration declares no component scan because the module's own application class registers an
 * explicit pool factory that builds from properties this class does not set. Every one of those four
 * decisions is argued in full at the head of {@link TransactionRepositoryIT} and in the package charter
 * beside this file; they are cited here rather than restated, because two statements of one decision
 * drift apart and a reader cannot then tell which is current.
 *
 * <p>Trade-offs: the container field, the profile annotation and the nested configuration are declared
 * again here rather than inherited from a shared base class. The package charter records that rejection
 * and its reason -- a base class holding a container is shared mutable state, so rows one test inserts
 * become rows another reads and a failure names the test that ran afterwards. The cost is the repeated
 * declaration; what is bought is that this class can be run alone and still mean something.
 *
 * <p>Assumptions: the clock is fixed. The payment stamps a processing timestamp, and a case that
 * compared a stored value against a wall-clock read would pass for a reason unrelated to the code under
 * test and fail whenever two reads straddled a boundary.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the members below carry their own.
 */
@Testcontainers
@SpringBootTest(
        classes = BillPaymentAtomicityIT.PaymentUnitOfWorkTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BillPaymentAtomicityIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: this is the same digest the four sibling integration tests of this package pin, and
     * it is repeated rather than shared for the reason the package charter gives for repeating the
     * container declaration. The version it denotes survives only in this sentence, because a digest
     * states nothing a reader can recognise.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * Classpath-relative path of the init script that supplies the foreign {@code account} schema.
     *
     * <p>Assumptions: this string is the only reference to that file and no compiler checks it, which is
     * why the first case below asserts the post-state the script establishes. A renamed or moved script
     * would otherwise surface as an undefined-table error inside an unrelated assertion.</p>
     */
    private static final String HARNESS_SCRIPT = "db/testharness/test-harness-account-schema.sql";

    /**
     * Class-path location of the harness that creates the schema's owning role in the container.
     *
     * <p>Assumptions: the role is a NOLOGIN role {@code data-migration/sql/V0__schemas_and_roles.sql} names and no container has, so it has
     * to exist before Flyway opens a connection and assumes it. A Testcontainers init script runs once
     * at container start, which is strictly earlier than Flyway's first connection; the alternative
     * this replaces -- creating the role from {@code spring.flyway.init-sqls} -- carried Flyway's
     * deprecated {@code initSql} setting and its per-connection removal notice.</p>
     */
    private static final String OWNER_ROLE_SCRIPT = "db/testharness/test-harness-owner-role.sql";

    /** The container the assertions run against, started once for this class. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withInitScripts(HARNESS_SCRIPT, OWNER_ROLE_SCRIPT);

    /** The account that pays, seeded with a payable balance and a version of zero. */
    private static final String PAYING_ACCOUNT_ID = "00000000011";

    /**
     * The account whose balance change cannot be applied, seeded with an unadvanceable version.
     *
     * <p>Assumptions: this account is otherwise identical to the paying one, including its balance, so
     * the only difference between the committing case and the failing case is the value the reduction's
     * version clause has to advance.</p>
     */
    private static final String REFUSING_ACCOUNT_ID = "00000000012";

    /** An account identifier no row carries, used to reach the not-found branch. */
    private static final String ABSENT_ACCOUNT_ID = "00000000099";

    /** The card number the stub cross-reference answers with for either seeded account. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The balance both seeded accounts carry, and therefore the amount a payment subtracts. */
    private static final Money PAYABLE_BALANCE = Money.of("1250.75");

    /** The balance a paid account is left with, being the whole balance subtracted from itself. */
    private static final BigDecimal SETTLED_BALANCE = new BigDecimal("0.00");

    /** The identifier of the guard row seeded before every case. */
    private static final String SEEDED_TRANSACTION_ID = "0000000000000041";

    /**
     * The identifier the payment is allocated, being the value the sequence is positioned to hand out.
     *
     * <p>⚠️ Refactoring Rationale: this was described as "one above the guard row's", which is the
     * read-then-increment derivation the migration withdrew. {@code allocateTransactionId} advances
     * {@code ledger.transaction_id_seq} instead -- registered as {@code D-TRANSACTION-ID-ALLOCATED} in
     * {@code docs/architecture/cobol-to-service-traceability.md} -- so the value owes nothing to the
     * rows present and the literal held here only by coincidence of being the first case to run. The
     * sequence is now POSITIONED by the seed below so this value is what the payment receives regardless
     * of how many cases allocated before it. Alternatives Considered: reading the sequence back after
     * the payment and comparing. Rejected because the message assertion in the same case embeds this
     * identifier, so a value discovered after the fact would make that assertion self-fulfilling.</p>
     */
    private static final String DERIVED_TRANSACTION_ID = "0000000000000042";

    /** The sequence the ledger identifier is allocated from, positioned by the seed before each case. */
    private static final String TRANSACTION_ID_SEQUENCE = "ledger.transaction_id_seq";

    /**
     * The version the refusing account is seeded with, being the largest value the column holds.
     *
     * <p>Assumptions: the column is {@code BIGINT} in both the owning migration and the harness, so
     * advancing this value by one is an overflow the engine refuses rather than a wrap. A wider numeric
     * type would silently accept it and this case would then assert nothing.</p>
     */
    private static final long UNADVANCEABLE_VERSION = Long.MAX_VALUE;

    /** The instant every case is pinned to, so a stored timestamp is a fixed value. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /** The processing timestamp the guard row carries, distinct from the pinned payment instant. */
    private static final LocalDateTime SEEDED_TIMESTAMP =
            LocalDateTime.parse("2022-07-17T09:30:00");

    /** The service under test, wired to the real repository and the real cross-schema gateway. */
    @Autowired
    private BillPaymentService service;

    /** Used to run a payment inside a caller's transaction that then fails. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used to seed and to read committed state back outside any transaction. */
    private JdbcTemplate jdbc;

    /**
     * Empties both tables, seeds the guard ledger row and seeds the two accounts.
     *
     * <p>Assumptions: the seed is committed before each case rather than shared across the class, so a
     * case that rolls a payment back still starts from a known balance and a known version. Each
     * statement below is issued outside a transaction, so each commits on its own; that is deliberate,
     * because a seed that shared a transaction with the case under test would be discarded by the very
     * rollback the case is asserting.</p>
     *
     * <p>Assumptions: every statement names its schema explicitly. The pool sets
     * {@code search_path} to {@code ledger}, so an unqualified reference to the account table would
     * resolve to nothing and an unqualified reference to the ledger table would resolve by accident
     * rather than by intent.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void resetAndSeed(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM ledger.transactions");
        this.jdbc.update("DELETE FROM account.accounts");

        this.jdbc.update("INSERT INTO ledger.transactions (transaction_id, type_cd, category_cd,"
                        + " source, description, amount, merchant_id, merchant_name, merchant_city,"
                        + " merchant_zip, card_num, orig_ts, proc_ts)"
                        + " VALUES (?, '02', '0001', 'POS TERM  ', 'GUARD ROW, PRE-EXISTING',"
                        + " 10.00, 123456789, 'GUARD MERCHANT', 'GUARD CITY', '12345     ', ?, ?, ?)",
                SEEDED_TRANSACTION_ID, CARD_NUMBER, Timestamp.valueOf(SEEDED_TIMESTAMP),
                Timestamp.valueOf(SEEDED_TIMESTAMP));

        // WHY : Assumptions: the identifier sequence is POSITIONED as part of the seed, because a
        //       sequence is not emptied by deleting rows and its allocations survive a rollback -- which
        //       is the property the allocation was chosen for. Without this statement the value a case
        //       receives would depend on how many cases ran before it, so the first case to run would
        //       pass and every rerun in the same container would fail on a different number.
        // WHY : Assumptions: setval is called with the guard row's own identifier and the default
        //       is-called flag, so the NEXT allocation is one past it. Passing the target value with a
        //       false flag would hand out the guard row's identifier itself and collide with it.
        // WHY : Assumptions: the call is issued as a QUERY and not as an update, because setval returns
        //       the value it positioned; an update refuses a statement that produced a result set, which
        //       is a driver-level failure rather than a seeding problem and reads as neither.
        this.jdbc.queryForObject("SELECT setval('" + TRANSACTION_ID_SEQUENCE + "', ?)",
                Long.class, Long.parseLong(SEEDED_TRANSACTION_ID));

        seedAccount(PAYING_ACCOUNT_ID, 0L);
        seedAccount(REFUSING_ACCOUNT_ID, UNADVANCEABLE_VERSION);
    }

    /**
     * Confirms the two schemas are reachable and carry the objects the cases depend on.
     *
     * <p>Assumptions: this case exists so that a broken init-script reference or an unapplied migration
     * is reported as itself rather than as an undefined-table error inside an unrelated assertion. The
     * script path is a string no compiler checks, and Flyway's success is a runtime outcome that a
     * classpath missing the PostgreSQL companion artifact would turn into a context failure, so both are
     * asserted rather than assumed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("Flyway supplied the owned ledger table and the init script the foreign account table")
    void bothSchemasAreReachable() {
        assertThat(this.jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'ledger' AND table_name = 'transactions'",
                        Integer.class))
                .as("ledger.transactions is built by this module's own Flyway migration")
                .isEqualTo(1);
        assertThat(this.jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'account' AND table_name = 'accounts'",
                        Integer.class))
                .as("account.accounts is supplied by %s, whose path nothing else checks", HARNESS_SCRIPT)
                .isEqualTo(1);
        assertThat(storedTransactionCount())
                .as("the guard row committed, so a later absence assertion cannot pass vacuously")
                .isEqualTo(1);
    }

    /**
     * The payment row and the balance reduction commit together.
     *
     * <p>Purpose: this is the whole of {@code PROCESS-ENTER-KEY} on a confirmed turn --
     * {@code app/cbl/COBIL00C.cbl} L233 writes the row, L234 subtracts the amount and L235 rewrites the
     * master -- observed as the state a subsequent reader sees.</p>
     *
     * <p>Assumptions: both tables are read back and the version is read alongside the balance. Reading
     * only the ledger row would leave the reduction unasserted, reading only the balance would leave the
     * row unasserted, and omitting the version would leave the one column the account context uses for
     * optimistic locking unchecked -- a cross-schema update that did not advance it would silently
     * disable that context's concurrency check on the very column both writers touch.</p>
     *
     * <p>Assumptions: the balance the response reports is the balance BEFORE the payment, which is what
     * the reference shows: L193 reads it and L194 fills the screen field before L234 reduces it. The
     * settled balance is asserted from the table instead.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a confirmed payment commits the ledger row and the reduced balance together")
    void bothEffectsOfAConfirmedPaymentCommit() {
        // WHY : Assumptions: the answer is NARROWED rather than declared as the posted type, because the
        //       operation answers the sealed outcome its two branches share -- a posted payment and a
        //       withheld one are different records, and which one arrives is part of what this case
        //       asserts. Declaring the posted type would move that assertion into a cast whose failure
        //       reads as a class-cast error rather than as the branch being wrong.
        BillPaymentOutcome outcome =
                this.service.payBalanceInFull(new BillPaymentRequest(PAYING_ACCOUNT_ID, "Y"));

        assertThat(outcome)
                .as("a confirmed payment answers the POSTED record, not the withheld one")
                .isInstanceOf(BillPaymentResponse.class);
        BillPaymentResponse response = (BillPaymentResponse) outcome;
        assertThat(response.paid()).as("the turn reports a payment made").isTrue();
        assertThat(response.transactionId()).isEqualTo(DERIVED_TRANSACTION_ID);
        assertThat(response.currentBalance().amount())
                .as("the balance as READ, which the reference shows before it reduces it")
                .isEqualByComparingTo(PAYABLE_BALANCE.amount());
        // WHY : Assumptions: the sentence is composed from the mapper's own fragments rather than written
        //       here as a literal, because the assembled form carries two consecutive spaces that a
        //       hand-written literal collapses to one -- and a literal would also be a second copy of a
        //       user-visible string that transformation rule T8 keeps single-sourced.
        assertThat(response.returnMessage())
                .isEqualTo(BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_PREFIX
                        + BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_INFIX
                        + DERIVED_TRANSACTION_ID
                        + BillPaymentMapper.MESSAGE_PAYMENT_SUCCESSFUL_SUFFIX);

        assertThat(storedTransactionCount())
                .as("the guard row and exactly one payment row")
                .isEqualTo(2);
        assertThat(storedAmount(DERIVED_TRANSACTION_ID))
                .as("the row carries the whole balance as its amount, exact to the cent")
                .isEqualByComparingTo(PAYABLE_BALANCE.amount());
        assertThat(storedCardNumber(DERIVED_TRANSACTION_ID))
                .as("the card comes from the cross-reference, not from the request")
                .isEqualTo(CARD_NUMBER);
        assertThat(storedBalance(PAYING_ACCOUNT_ID))
                .as("the balance was reduced by the amount, in the same commit")
                .isEqualByComparingTo(SETTLED_BALANCE);
        assertThat(storedVersion(PAYING_ACCOUNT_ID))
                .as("the version advanced by one, so the owning context's lock still bites")
                .isEqualTo(1L);
    }

    /**
     * A balance change the engine refuses discards the payment row written before it.
     *
     * <p>Purpose: this is the residual arm of {@code UPDATE-ACCTDAT-FILE},
     * {@code app/cbl/COBIL00C.cbl} L396 to L399, where a response other than normal or not-found raises
     * the error flag and moves {@code 'Unable to Update Account...'}. In the baseline the task ends
     * without a syncpoint of its own on that arm, so the row written at L233 never commits; here the
     * exception propagates out of the one transactional boundary and the row is rolled back.</p>
     *
     * <p>Assumptions: THIS is the case that proves enrolment, and it proves it by observing an absence
     * that only a shared transaction can produce. The payment row is flushed to the engine before the
     * reduction is issued -- the service uses the flushing form of the write for exactly that reason --
     * so if the two effects were on different connections, or if the write had committed separately, the
     * row would still be in the table after the reduction failed. It is not.</p>
     *
     * <p>Assumptions: the sentence asserted is the update-specific one at L399 and not the read's at
     * L368, because the reference selects between them and the two must not be merged.</p>
     *
     * <p>Assumptions: the account's own state is asserted unchanged as well, including its version. A
     * reduction that had partially applied would be the other direction of half-applied state, and a
     * case that only checked the ledger table would not see it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refused balance change leaves no payment row and no partial reduction")
    void aRefusedBalanceChangeDiscardsThePaymentRow() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> this.service
                        .payBalanceInFull(new BillPaymentRequest(REFUSING_ACCOUNT_ID, "Y")))
                .withMessage(BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED);

        assertThat(storedTransactionCount())
                .as("only the guard row survived, so the payment row was rolled back")
                .isEqualTo(1);
        assertThat(storedTransactionIds())
                .as("and the row that survived is the guard row, not a partially written payment")
                .containsExactly(SEEDED_TRANSACTION_ID);
        assertThat(storedBalance(REFUSING_ACCOUNT_ID))
                .as("the balance is untouched, so no reduction was left half applied")
                .isEqualByComparingTo(PAYABLE_BALANCE.amount());
        assertThat(storedVersion(REFUSING_ACCOUNT_ID))
                .as("and the version is untouched too")
                .isEqualTo(UNADVANCEABLE_VERSION);
    }

    /**
     * A failure in an enclosing transaction discards both effects of a payment that had succeeded.
     *
     * <p>Purpose: this closes the second direction of half-applied state, the one the previous
     * arrangement could not close. When the balance change was a call to another service it committed on
     * its own, so a later failure of this context's commit left the balance reduced with nothing
     * recording why -- money moved with no ledger entry against it. The boundary propagates outward with
     * the framework's default participation, so a caller's transaction becomes the payment's
     * transaction, and one commit decision now covers both effects.</p>
     *
     * <p>Assumptions: the failure is raised by the caller AFTER the payment returned, which is what makes
     * this a test of the commit decision rather than of the service's own error handling. The payment
     * itself succeeds: it writes its row, reduces the balance, and answers with its confirmation
     * sentence. Only then does the enclosing unit of work fail.</p>
     *
     * <p>Alternatives Considered: forcing the commit itself to fail with a constraint deferred to commit
     * time, which is the literal reading of "the commit fails". Rejected on two grounds. It would require
     * adding and dropping a constraint around one case, so a case that failed midway would leave the
     * constraint in place and change what every later case in the class proves. And it would assert the
     * same property less directly: what has to hold is that ONE commit decision covers both effects, and
     * a caller's rollback observes exactly that -- if the reduction were on any other connection or in
     * any other transaction, it would survive this rollback.</p>
     *
     * <p>Assumptions: the exception type raised by the caller is a type this class declares, so it cannot
     * be confused with the service's own failure sentence. Raising the framework's own state exception
     * would have made a genuine service failure indistinguishable from the deliberate one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an enclosing failure after a successful payment discards the row and the reduction")
    void anEnclosingFailureDiscardsBothEffects() {
        assertThatExceptionOfType(EnclosingUnitOfWorkFailed.class).isThrownBy(() ->
                this.transactionTemplate.executeWithoutResult(status -> {
                    BillPaymentOutcome response = this.service
                            .payBalanceInFull(new BillPaymentRequest(PAYING_ACCOUNT_ID, "Y"));
                    assertThat(response.paid())
                            .as("the payment must succeed, or this case would prove nothing")
                            .isTrue();
                    throw new EnclosingUnitOfWorkFailed();
                }));

        assertThat(storedTransactionIds())
                .as("the payment row did not survive the caller's rollback")
                .containsExactly(SEEDED_TRANSACTION_ID);
        assertThat(storedBalance(PAYING_ACCOUNT_ID))
                .as("and neither did the reduction, which is the direction the remote change leaked")
                .isEqualByComparingTo(PAYABLE_BALANCE.amount());
        assertThat(storedVersion(PAYING_ACCOUNT_ID))
                .as("the version is back at its seeded value, so the whole row was restored")
                .isEqualTo(0L);
    }

    /**
     * A declined confirmation reads the balance without locking it and writes nothing.
     *
     * <p>Purpose: this is the branch at {@code app/cbl/COBIL00C.cbl} L178 to L181, where {@code 'N'}
     * clears the screen and raises the error flag with no message and no write.</p>
     *
     * <p>Assumptions: this case exists because it is the only one that exercises the NON-locking read
     * against a real engine, and the two reads differ by the six characters that hold a row lock for the
     * rest of the transaction. A locking read on a turn that writes nothing would be further from the
     * reference than a plain one, and no assertion elsewhere would notice.</p>
     *
     * <p>Assumptions: the absent sentence is asserted as null rather than as spaces, because the
     * reference attaches a low-values condition to the return message field and absence is representable
     * for it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a declined confirmation reports the balance and writes nothing")
    void aDeclinedConfirmationWritesNothing() {
        BillPaymentOutcome outcome =
                this.service.payBalanceInFull(new BillPaymentRequest(PAYING_ACCOUNT_ID, "N"));

        assertThat(outcome)
                .as("a declined confirmation answers the WITHHELD record")
                .isInstanceOf(BillPaymentPreview.class);
        BillPaymentPreview declined = (BillPaymentPreview) outcome;
        assertThat(declined.paid()).isFalse();
        assertThat(declined.returnMessage())
                .as("the reference emits no sentence on this branch")
                .isNull();
        // WHY : ⚠️ Refactoring Rationale: the declined turn is asserted to carry NO balance, where an
        //       earlier form of this case expected it to report the stored one. The landed operation
        //       returns a CLEARED record on this branch and reaches no account read at all, which is
        //       the reference's own behaviour: CLEAR-CURRENT-SCREEN at line 180 of
        //       app/cbl/COBIL00C.cbl blanks the display fields, so a body carrying a figure would show
        //       an operator a value the baseline blanks. Asserting the absence is also what keeps the
        //       case a proof about the DATABASE: it is the strongest available statement that nothing
        //       was read and nothing was written.
        assertThat(declined.payableBalance())
                .as("the declined branch blanks the display fields rather than reporting a figure")
                .isNull();

        assertThat(storedTransactionIds()).containsExactly(SEEDED_TRANSACTION_ID);
        assertThat(storedBalance(PAYING_ACCOUNT_ID))
                .isEqualByComparingTo(PAYABLE_BALANCE.amount());
        assertThat(storedVersion(PAYING_ACCOUNT_ID)).isEqualTo(0L);
    }

    /**
     * An identifier no row carries is reported as not found rather than as a failure.
     *
     * <p>Purpose: this is the not-found status of {@code READ-ACCTDAT-FILE},
     * {@code app/cbl/COBIL00C.cbl} L358 to L361, answered with {@code 'Account ID NOT found...'} --
     * which is a different sentence from the one any other status gets at L368.</p>
     *
     * <p>Assumptions: this case is here because absence reaches the gateway as a raised condition from
     * the provider rather than as an empty result, and that conversion is only observable against a real
     * engine. A mocked gateway hands back an empty optional directly and so cannot show that the
     * conversion happens at all -- if it were removed, the provider's exception would escape and the
     * operator would see the failure sentence instead of the not-found one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent account is reported not found, with the read's own sentence")
    void anAbsentAccountIsReportedNotFound() {
        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> this.service
                        .payBalanceInFull(new BillPaymentRequest(ABSENT_ACCOUNT_ID, "Y")))
                .withMessage(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);

        assertThat(storedTransactionIds()).containsExactly(SEEDED_TRANSACTION_ID);
    }

    /**
     * Inserts one account row with the seeded balance and a caller-chosen version.
     *
     * <p>Assumptions: the eleven columns this context never touches are supplied anyway, because the
     * harness table declares them {@code NOT NULL} exactly as the owning migration does. Omitting them
     * would mean the harness accepted a row a deployed environment would reject.</p>
     *
     * @param accountId the identifier to seed, as the digit characters the service is given; must not be
     *     {@code null}
     * @param version the value to seed the optimistic-locking column with
     */
    private void seedAccount(String accountId, long version) {
        this.jdbc.update("INSERT INTO account.accounts (account_id, active_status, curr_bal,"
                        + " credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date,"
                        + " curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)"
                        + " VALUES (?, 'Y', ?, 5000.00, 1000.00, DATE '2020-01-01',"
                        + " DATE '2030-12-31', DATE '2025-01-01', 0.00, 0.00, '12345     ',"
                        + " 'DEFAULT   ', ?)",
                Long.parseLong(accountId), PAYABLE_BALANCE.amount(), version);
    }

    /**
     * Counts the rows committed to the ledger table.
     *
     * @return the number of rows, never {@code null}
     */
    private Integer storedTransactionCount() {
        return this.jdbc.queryForObject("SELECT count(*) FROM ledger.transactions", Integer.class);
    }

    /**
     * Lists the identifiers committed to the ledger table, in key order.
     *
     * <p>Assumptions: the stored key is a fixed-width character column, so each value is stripped before
     * comparison; the width itself is pinned by {@link TransactionRepositoryIT} and is not re-asserted
     * here.</p>
     *
     * @return the identifiers in ascending key order, never {@code null}
     */
    private List<String> storedTransactionIds() {
        return this.jdbc.queryForList(
                        "SELECT transaction_id FROM ledger.transactions ORDER BY transaction_id",
                        String.class)
                .stream()
                .map(String::strip)
                .toList();
    }

    /**
     * Reads the amount committed against one ledger row.
     *
     * @param transactionId the identifier to read; must not be {@code null}
     * @return the stored amount as an exact decimal, never {@code null}
     */
    private BigDecimal storedAmount(String transactionId) {
        return this.jdbc.queryForObject(
                "SELECT amount FROM ledger.transactions WHERE transaction_id = ?",
                BigDecimal.class, transactionId);
    }

    /**
     * Reads the card number committed against one ledger row.
     *
     * @param transactionId the identifier to read; must not be {@code null}
     * @return the stored card number with its column padding removed, never {@code null}
     */
    private String storedCardNumber(String transactionId) {
        return this.jdbc.queryForObject(
                        "SELECT card_num FROM ledger.transactions WHERE transaction_id = ?",
                        String.class, transactionId)
                .strip();
    }

    /**
     * Reads the balance committed against one account row.
     *
     * <p>Assumptions: the value is requested as an exact decimal and never as a floating-point number,
     * which is transformation rule T3 applied to the read side of this assertion. Reading it as a double
     * would compare cents through a binary approximation.</p>
     *
     * @param accountId the identifier to read, as digit characters; must not be {@code null}
     * @return the stored balance, never {@code null}
     */
    private BigDecimal storedBalance(String accountId) {
        return this.jdbc.queryForObject(
                "SELECT curr_bal FROM account.accounts WHERE account_id = ?",
                BigDecimal.class, Long.parseLong(accountId));
    }

    /**
     * Reads the optimistic-locking version committed against one account row.
     *
     * @param accountId the identifier to read, as digit characters; must not be {@code null}
     * @return the stored version, never {@code null}
     */
    private Long storedVersion(String accountId) {
        return this.jdbc.queryForObject(
                "SELECT version FROM account.accounts WHERE account_id = ?",
                Long.class, Long.parseLong(accountId));
    }

    /**
     * Raised by the enclosing-failure case so that a deliberate rollback cannot be mistaken for a
     * genuine service failure.
     *
     * <p>Assumptions: this is unchecked, because only an unchecked exception triggers the framework's
     * default rollback; a checked one would commit and the case would assert the opposite of what it
     * means to.</p>
     */
    private static final class EnclosingUnitOfWorkFailed extends RuntimeException {

        /** Serialisation identity, fixed because the type is never serialised but the gate asks for it. */
        private static final long serialVersionUID = 1L;

        /** Creates the marker failure with a fixed detail message. */
        EnclosingUnitOfWorkFailed() {
            super("the enclosing unit of work failed after the payment returned");
        }
    }

    /**
     * The cross-reference stand-in, answering the one lookup the payment makes across the service seam.
     *
     * <p>Assumptions: this collaborator is a stand-in and the balance gateway is NOT, and the difference
     * is the whole point of this class. The cross-reference lives in another bounded context and is
     * reached over HTTP, so it cannot be enrolled in this transaction and nothing here needs it to
     * be -- it is read, not written. The balance is written, so it is reached by a statement on this
     * transaction's own connection and is exercised for real.</p>
     *
     * <p>Alternatives Considered: standing up a stub HTTP server so the seam were exercised too.
     * Rejected: it would add a second moving part to a class whose subject is the transaction boundary,
     * and the seam's own wire behaviour is covered by the tests of its REST implementation.</p>
     */
    private static final class StubCardXrefSource implements AccountContextClient {

        /**
         * Answers the account-keyed lookup for either seeded account and reports absence otherwise.
         *
         * @param accountId the account identifier the payment resolved; must not be {@code null}
         * @return the entry for a seeded account, otherwise an empty optional
         */
        @Override
        public Optional<CardXref> findCardXrefByAccountId(String accountId) {
            if (PAYING_ACCOUNT_ID.equals(accountId) || REFUSING_ACCOUNT_ID.equals(accountId)) {
                return Optional.of(new CardXref(accountId, CARD_NUMBER));
            }
            return Optional.empty();
        }

        /**
         * Reports absence for the card-keyed lookup, which no path in this class reaches.
         *
         * <p>Assumptions: this returns an empty optional rather than raising, because a stand-in that
         * threw on an unreached method would turn a future call into an unexplained failure instead of
         * a documented absence.</p>
         *
         * @param cardNumber the card number to resolve; must not be {@code null}
         * @return always an empty optional
         */
        @Override
        public Optional<CardXref> findCardXrefByCardNumber(String cardNumber) {
            return Optional.empty();
        }
    }

    /**
     * The minimal Spring Boot configuration this suite runs against.
     *
     * <p>Assumptions: this configuration declares no component scan, and that omission is the whole
     * point of it. The module's own application class scans this bounded context and so registers the
     * explicit pool factory that builds from datasource PROPERTIES; those properties are absent here
     * because the container's coordinates arrive as a connection-details bean, and the context would
     * then fail on a URL that does not begin with the JDBC scheme. Naming the two persistence packages
     * explicitly instead leaves Boot's own auto-configuration to build the pool from that bean. The
     * sibling integration tests of this package take the same decision for the same reason.</p>
     *
     * <p>Assumptions: the four collaborators the service needs are declared as beans rather than
     * scanned, so this context holds the payment path and nothing else. Scanning the service package
     * would register every other service of this context and, with them, the REST client that reaches
     * the account context -- a dependency this suite has no endpoint for and needs none.</p>
     *
     * <p>Trade-offs: declaring it nested rather than as a file of its own keeps the package charter's
     * closed file set true, at the cost of it not being reusable by the four sibling tests. That cost is
     * accepted deliberately: the same charter rejects a shared holder for exactly the reason it would
     * apply here.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.transaction.domain")
    @EnableJpaRepositories("com.carddemo.transaction.repository")
    static class PaymentUnitOfWorkTestApplication {

        /**
         * Supplies the cross-schema balance access path under test.
         *
         * <p>Assumptions: the bean is declared explicitly rather than scanned, and it is still eligible
         * for persistence-exception translation because that post-processor selects on the bean TYPE's
         * repository annotation rather than on how the bean was registered. That eligibility is
         * load-bearing here: the service catches only the framework's data-access family, so an
         * untranslated provider exception would escape with the wrong sentence.</p>
         *
         * <p>Assumptions: the entity manager injected is the shared transaction-bound proxy Spring Data
         * registers, which is the same instance production constructor injection resolves. A manager
         * created per call would place the statements outside the caller's transaction, which is
         * precisely the defect this suite exists to detect.</p>
         *
         * @param entityManager the shared transaction-bound entity manager; must not be {@code null}
         * @return the gateway, never {@code null}
         */
        @Bean
        AccountBalanceRepository accountBalanceGateway(EntityManager entityManager) {
            return new AccountBalanceRepository(entityManager);
        }

        /**
         * Supplies the mapper that shapes the row and selects the screen's sentences.
         *
         * @return the mapper, never {@code null}
         */
        @Bean
        BillPaymentMapper billPaymentMapper() {
            return new BillPaymentMapper();
        }

        /**
         * Supplies the cross-reference stand-in.
         *
         * @return the stand-in, never {@code null}
         */
        @Bean
        AccountContextClient accountContextClient() {
            return new StubCardXrefSource();
        }

        /**
         * Supplies the fixed clock every case is pinned to.
         *
         * @return a clock frozen at the pinned instant, never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(PINNED_INSTANT, ZoneId.of("UTC"));
        }

        /**
         * Supplies the service under test, wired to the real repository and the real gateway.
         *
         * @param transactions the ledger repository Spring Data built from its interface; must not be
         *     {@code null}
         * @param accounts the cross-reference stand-in; must not be {@code null}
         * @param balances the cross-schema balance gateway; must not be {@code null}
         * @param billPaymentMapper the mapper; must not be {@code null}
         * @param clock the pinned clock; must not be {@code null}
         * @return the service, never {@code null}
         */
        @Bean
        BillPaymentService billPaymentService(TransactionRepository transactions,
                AccountContextClient accounts, AccountBalanceRepository balances,
                BillPaymentMapper billPaymentMapper, Clock clock) {
            return new BillPaymentService(transactions, accounts, balances, billPaymentMapper, clock);
        }
    }
}
