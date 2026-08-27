package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.common.money.Money;
import com.carddemo.transaction.dto.BillPaymentOutcome;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.repository.AccountBalanceRepository;
import com.carddemo.transaction.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the bill payment is ONE database transaction, against a real PostgreSQL engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/COBIL00C.cbl} writes the ledger row at line 233 and rewrites the account balance at
 * line 235, and both statements sit inside the SAME implicit CICS syncpoint -- the task-end commit. Two
 * states are therefore unreachable in the baseline: a payment recorded against an unchanged balance, and
 * a reduced balance with no payment to show for it. This class establishes that both remain unreachable
 * in the migrated path, which no unit test can do: a unit test observes the two writes being ISSUED, and
 * the property under test is whether they COMMIT together.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a test class: no caller constructs it,
 * it yields no value and it raises nothing outside the test engine, so the type itself accepts no
 * parameter, returns nothing and throws nothing. The inapplicability is stated rather than passed over,
 * because the user-specified Explainability rule forbids a docstring that omits parameters, return values
 * or purpose, and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own at-clauses.</p>
 *
 * <h2>⚠️ Refactoring Rationale: what this class exists to have caught</h2>
 *
 * <p>⚠️ Refactoring Rationale: the balance change used to be issued over HTTP to account-service, through
 * a seam operation named {@code applyPayment}, and that arrangement was broken in two independent ways
 * that BOTH escaped a fully-mocked unit suite. The visible one: the endpoint it addressed,
 * {@code POST /api/v1/accounts/payments}, was never published -- account-service exposes a lookup, a
 * view, an update and a cross-reference list, and no payment route -- so every confirmed payment failed
 * at run time while every unit test passed, because a mocked seam answers whatever it is told to. The
 * invisible one, which publishing that endpoint would not have fixed: a separate connection is a separate
 * transaction, so the balance change committed on its own and the two states the baseline cannot produce
 * became reachable. A unit test cannot distinguish one commit from two at all, which is precisely why
 * this class runs against an engine.</p>
 *
 * <p>Assumptions: the fix this class holds is the one AAP section 0.4.1.3 sanctions -- a narrow
 * cross-schema grant so that a genuinely multi-record unit of work stays a single ACID commit. The grant
 * is {@code data-migration/sql/V0__schemas_and_roles.sql} section 4b:
 * {@code carddemo_ledger} receives {@code USAGE} on {@code account} plus {@code SELECT} and
 * {@code UPDATE} on {@code account.accounts}, and nothing else. Saga and
 * outbox-plus-compensating-reversal were considered and rejected by that same section, because both
 * introduce observable intermediate states -- a posted transaction beside an unreduced balance -- that
 * the baseline has no state for.</p>
 *
 * <h2>Assumptions: the foreign table is a harness, not a migration</h2>
 *
 * <p>Assumptions: {@code account.accounts} belongs to account-service, so this module's migration set
 * does not and must not create it. A container database starts empty, so the table is supplied by a
 * Testcontainers init script that runs strictly BEFORE Flyway opens its first connection, exactly as the
 * batch context's own cross-schema integration tests supply theirs. That script lives outside
 * {@code db/migration} on purpose: the set of migrations Flyway applies under test is then identical to
 * the set it applies in production, and the script supplies only what a provisioned environment supplies
 * ahead of Flyway.</p>
 *
 * <p>Assumptions: the harness is asserted PRESENT before anything else is asserted. The classpath path to
 * the script is a string with no compiler behind it, so a renamed or moved file would otherwise surface
 * as an undefined-table error naming nothing about the move.</p>
 *
 * <h2>Alternatives Considered: the collaborator that stays mocked, and why</h2>
 *
 * <p>Alternatives Considered: resolving {@link AccountContextClient} for real as well, so the whole path
 * runs unmocked. Rejected because that seam reaches a different bounded context over HTTP -- it serves
 * the card cross-reference read at line 408 and nothing else now -- so resolving it would need
 * account-service running, and a failure would belong to that service rather than to the unit of work
 * under test. It is the one collaborator here that does not participate in the transaction, which is
 * exactly why it can be stood in for without weakening the claim.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = BillPaymentUnitOfWorkIT.BillPaymentUnitOfWorkTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BillPaymentUnitOfWorkIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the same one the four sibling repository integration tests of this
     * module pin, so every container-backed test here runs the same engine and a behavioural difference
     * between two of them cannot be an engine difference. The version is recorded in prose because a
     * digest states nothing a reader can recognise, and a tag such as {@code postgres:17-alpine} pins
     * the major line only -- the publisher moves it on each minor release.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative init script that supplies the account-owned schema and table.
     *
     * <p>Assumptions: the path is fixed by the script's own location under {@code src/test/resources}, so
     * moving the file breaks this reference with no compiler to catch it. The first case below asserts
     * the post-state the script establishes for that reason.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-account-schema.sql";

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

    /** The account every case pays, at the eleven digit declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** That identifier as the {@code BIGINT} column binds it. */
    private static final long ACCOUNT_KEY = 11L;

    /** The card number the stubbed cross-reference resolves for that account. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The balance every case starts the account at, chosen to be neither zero nor round. */
    private static final Money OPENING_BALANCE = Money.of("1250.75");

    /**
     * The instant every payment is stamped with, supplied so the stored timestamps are reproducible.
     *
     * <p>Assumptions: the date is the business date the baseline batch chain is driven with as a
     * parameter rather than from the wall clock, so the value is drawn from the specification rather than
     * invented.</p>
     */
    private static final Clock PINNED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T12:00:00Z"), ZoneOffset.UTC);

    /** The ledger table's repository, resolved for real against the container. */
    @Autowired
    private TransactionRepository transactions;

    /** The two statements over the account-owned balance, resolved for real against the container. */
    @Autowired
    private AccountBalanceRepository accountBalances;

    /** The entity manager, used to read committed state back and to seed the foreign row. */
    @Autowired
    private EntityManager entityManager;

    /**
     * A template for the explicit transactions this class opens around its own reads and writes.
     *
     * <p>Alternatives Considered: annotating this class {@code @Transactional}, which is the shorter
     * arrangement. Rejected outright and for the reason this whole class exists: a test-managed
     * transaction that rolls back at the end would make every observation below a read of UNCOMMITTED
     * state, so the one property under test -- what survives a commit and what does not -- would be
     * unobservable, and every case would pass against an implementation that committed nothing.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** The card cross-reference seam, stubbed per case for the reason the class note records. */
    private AccountContextClient accounts;

    /** The unit under test, rebuilt per case over the real repositories and the stubbed seam. */
    private BillPaymentService service;

    /**
     * Empties both tables and seeds one account row before every case.
     *
     * <p>Assumptions: the tables are emptied rather than the container restarted, because a container
     * start costs seconds and the two tables here are small and wholly owned by this class. Each case
     * therefore begins from a state it fully determines, which is what lets a balance assertion be
     * absolute rather than relative.</p>
     *
     * <p>Assumptions: the account row is inserted through a native statement rather than through an
     * entity, because this module declares no entity for {@code account.accounts} at all -- it reaches
     * that table through two named statements and nothing else, which is the narrowness the grant
     * permits. Declaring an entity for it here would create a mapping the production code deliberately
     * does not have.</p>
     */
    @BeforeEach
    void setUp() {
        this.accounts = mock(AccountContextClient.class);
        this.service = new BillPaymentService(this.transactions, this.accounts, this.accountBalances,
                new BillPaymentMapper(), PINNED_CLOCK);

        this.transactionTemplate.executeWithoutResult(status -> {
            this.entityManager.createNativeQuery("delete from ledger.transactions").executeUpdate();
            this.entityManager.createNativeQuery("delete from account.accounts").executeUpdate();
            this.entityManager.createNativeQuery("""
                    insert into account.accounts (account_id, active_status, curr_bal, credit_limit,
                        cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit,
                        curr_cyc_debit, addr_zip, group_id)
                    values (:id, 'Y', :balance, 5000.00, 1000.00, date '2020-01-01',
                        date '2030-01-01', date '2026-01-01', 0.00, 0.00, '98101     ',
                        'DEFAULT   ')""")
                    .setParameter("id", ACCOUNT_KEY)
                    .setParameter("balance", OPENING_BALANCE.amount())
                    .executeUpdate();
        });
    }

    /** Arranges the cross-reference read the confirmed branch performs. */
    private void theCrossReferenceResolves() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(
                Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CARD_NUMBER)));
    }

    /**
     * Reads the committed balance back in its own transaction.
     *
     * <p>Assumptions: a separate transaction is opened for the read rather than reusing a session the
     * payment ran in, because a first-level cache would answer from the entity the payment touched and
     * the case would then pass without anything having reached the engine. The statement is native and
     * unqualified by any entity, matching how the production path reaches this column.</p>
     *
     * @return the balance the row carries as committed, of type {@link BigDecimal}; never {@code null}
     */
    private BigDecimal committedBalance() {
        return this.transactionTemplate.execute(status -> (BigDecimal) this.entityManager
                .createNativeQuery(
                        "select curr_bal from account.accounts where account_id = :id")
                .setParameter("id", ACCOUNT_KEY)
                .getSingleResult());
    }

    /**
     * Reads the committed revision counter back in its own transaction.
     *
     * @return the value of the optimistic-lock column as committed, of type {@link Long}; never
     *     {@code null}
     */
    private Long committedRevision() {
        return this.transactionTemplate.execute(status -> ((Number) this.entityManager
                .createNativeQuery("select version from account.accounts where account_id = :id")
                .setParameter("id", ACCOUNT_KEY)
                .getSingleResult()).longValue());
    }

    /**
     * Counts the committed ledger rows in their own transaction.
     *
     * @return the number of rows {@code ledger.transactions} holds, of type {@code long}
     */
    private long committedLedgerRows() {
        return this.transactionTemplate.execute(status -> this.transactions.count());
    }

    /**
     * The harness supplied the foreign schema and table before Flyway ran.
     *
     * <p>Assumptions: this is asserted first and separately, because the classpath path to the init
     * script is a string with no compiler behind it. A renamed or moved script would otherwise surface as
     * an undefined-table error inside a payment assertion, which names the symptom and not the cause.</p>
     */
    @Test
    @DisplayName("the init script supplied account.accounts before Flyway opened a connection")
    void theHarnessSuppliedTheForeignTable() {
        Object present = this.transactionTemplate.execute(status -> this.entityManager
                .createNativeQuery("select to_regclass('account.accounts')")
                .getSingleResult());

        assertThat(present)
                .as("the init script at %s must have created the account-owned table", HARNESS_SCRIPT)
                .isNotNull();
        assertThat(committedBalance())
                .as("and the fixture row this class seeds must be readable through it")
                .isEqualByComparingTo(OPENING_BALANCE.amount());
    }

    /**
     * A confirmed payment commits the ledger row and the reduced balance TOGETHER.
     *
     * <p>Purpose: this is lines 233 and 235 of {@code app/cbl/COBIL00C.cbl} inside one syncpoint. Both
     * effects are read back from separate transactions afterwards, which is what makes the claim about
     * committed state rather than about issued statements.</p>
     *
     * <p>Assumptions: the balance is asserted to have reached exactly zero, and the ledger row to carry
     * exactly the amount subtracted, because the reference always pays the WHOLE balance -- the screen
     * carries no amount field, which is why the request shape has no amount component. A payment that
     * committed one effect and not the other would leave a non-zero balance beside a row, or a zero
     * balance beside none, and both are asserted against.</p>
     *
     * <p>Assumptions: the revision counter is asserted to have ADVANCED, and the assertion is
     * load-bearing rather than incidental. account-service maps that column as a JPA {@code @Version}, so
     * a payment that reduced the balance without advancing it would leave a concurrent account update
     * matching on a revision that no longer describes the row -- and that update would then overwrite
     * the payment with a balance read before it.</p>
     */
    @Test
    @DisplayName("a confirmed payment commits the ledger row and the reduced balance together")
    void aConfirmedPaymentCommitsBothEffects() {
        theCrossReferenceResolves();

        BillPaymentOutcome outcome = this.transactionTemplate.execute(status ->
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y")));

        assertThat(outcome).isInstanceOf(BillPaymentResponse.class);
        BillPaymentResponse acknowledgement = (BillPaymentResponse) outcome;

        assertThat(committedLedgerRows())
                .as("line 233 wrote one row, and it survived the commit")
                .isEqualTo(1L);
        assertThat(committedBalance())
                .as("line 235's reduction survived the same commit, leaving nothing outstanding")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(committedRevision())
                .as("the revision advanced, so a concurrent account update cannot overwrite this")
                .isEqualTo(1L);

        assertThat(this.transactions.findById(acknowledgement.transactionId()))
                .as("the row committed is the one the acknowledgement names")
                .isPresent()
                .get()
                .satisfies(stored -> {
                    assertThat(stored.getTranAmt()).isEqualByComparingTo(OPENING_BALANCE.amount());
                    assertThat(stored.getCardNum()).isEqualTo(CARD_NUMBER);
                });
    }

    /**
     * A failure after the ledger write rolls BOTH effects back, leaving neither behind.
     *
     * <p>Purpose: this is the residual arm of {@code UPDATE-ACCTDAT-FILE} at lines 396 to 399 of
     * {@code app/cbl/COBIL00C.cbl}, which raises the error flag and moves
     * {@code 'Unable to Update Account...'}. In the baseline the task ends abnormally and its implicit
     * syncpoint discards the row written at line 233, so the state "a payment recorded against an
     * unchanged balance" is unreachable. This case establishes that it is unreachable here too.</p>
     *
     * <p>Assumptions: the failure is provoked by DELETING the account row between the seed and the
     * payment, which is the one way to make the real statements fail without stubbing either of them. The
     * locking read then finds nothing, so the payment is refused before it writes -- and the case
     * therefore asserts the complementary half explicitly: that no ledger row was committed. Provoking
     * it after the write instead would need one of the two repositories mocked, and a mocked repository
     * does not participate in the transaction whose behaviour is the whole subject.</p>
     *
     * <p>Assumptions: the balance is asserted absent rather than unchanged, because the row itself is
     * gone. What matters is that the ledger is empty afterwards: a payment that committed its row before
     * discovering the account was missing would leave one behind, and the count is what detects it.</p>
     */
    @Test
    @DisplayName("a payment that cannot complete leaves no ledger row behind")
    void aFailedPaymentLeavesNothingBehind() {
        this.transactionTemplate.executeWithoutResult(status ->
                this.entityManager.createNativeQuery("delete from account.accounts").executeUpdate());

        assertThatThrownBy(() -> this.transactionTemplate.executeWithoutResult(status ->
                this.service.payBalanceInFull(new BillPaymentRequest(ACCOUNT_ID, "Y"))))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND);

        assertThat(committedLedgerRows())
                .as("nothing was appended, so no payment stands against a balance that never moved")
                .isZero();
    }

    /**
     * The locking read and the reduction operate on the SAME row view, so a concurrent write cannot slip
     * between them.
     *
     * <p>Purpose: the reference's read at line 351 carries the {@code UPDATE} option with
     * {@code RIDFLD(ACCT-ID)} at line 349, and the rewrite at line 379 consumes that lock. This case
     * establishes that the migrated read really does take a row lock rather than merely reading, which is
     * what makes the inclusive verdict at line 198 and the reduction share one view of the row.</p>
     *
     * <p>Assumptions: the lock is observed through PostgreSQL's own catalogue rather than by racing two
     * threads. A race would be the more direct evidence and is rejected on determinism: whether the
     * second writer blocks depends on scheduling, so the case would pass or fail intermittently and a
     * failure would say nothing about the statement. Reading the locks the current transaction HOLDS is
     * exact and reproducible.</p>
     *
     * <p>⚠️ Assumptions: the lock read is a RELATION-level {@code RowShareLock} and NOT a tuple-level
     * entry, and the distinction cost a measurement to establish. PostgreSQL records an uncontended row
     * lock in the tuple header rather than in {@code pg_locks}, so a {@code locktype = 'tuple'} filter
     * returns zero for a successful {@code SELECT ... FOR UPDATE} and would report the correct
     * implementation as unlocked. What the catalogue does carry is the table-level mode the statement
     * escalates to: {@code FOR UPDATE} and its three siblings take {@code ROW SHARE} on the relation,
     * where a plain {@code SELECT} takes only {@code ACCESS SHARE}. That difference is present with no
     * contention at all, which is what makes it observable from one session.</p>
     *
     * <p>Assumptions: the reporting read is asserted to take NO such lock in the same case, because the
     * asymmetry is deliberate and easy to lose. A turn that writes nothing must not hold a row for the
     * whole of an HTTP request -- the CICS task could afford to, because it ended at the screen -- so one
     * operator's unconfirmed preview must not block another operator's payment.</p>
     */
    @Test
    @DisplayName("the paying read escalates to ROW SHARE and the reporting read does not")
    void thePayingReadLocksTheRowAndTheReportingReadDoesNot() {
        Long heldByPayingRead = this.transactionTemplate.execute(status -> {
            this.accountBalances.lockCurrentBalance(ACCOUNT_KEY);
            return lockModesHeldOnAccounts("RowShareLock");
        });

        Long heldByReportingRead = this.transactionTemplate.execute(status -> {
            this.accountBalances.findCurrentBalance(ACCOUNT_KEY);
            return lockModesHeldOnAccounts("RowShareLock");
        });

        Long readByReportingRead = this.transactionTemplate.execute(status -> {
            this.accountBalances.findCurrentBalance(ACCOUNT_KEY);
            return lockModesHeldOnAccounts("AccessShareLock");
        });

        assertThat(heldByPayingRead)
                .as("SELECT ... FOR UPDATE takes ROW SHARE, which is what line 351's UPDATE option does")
                .isEqualTo(1L);
        assertThat(heldByReportingRead)
                .as("a plain SELECT takes none, so a preview cannot block another operator's payment")
                .isZero();

        // WHY : Assumptions: the reporting read is asserted to have taken the WEAKER mode as well as not
        //       the stronger one. Without it a query that never ran at all would satisfy the assertion
        //       above -- zero ROW SHARE locks is exactly what an unexecuted statement leaves behind, so
        //       the negative alone cannot tell a plain read from no read.
        assertThat(readByReportingRead)
                .as("but it did read: a plain SELECT takes ACCESS SHARE on the same relation")
                .isEqualTo(1L);
    }

    /**
     * Counts the locks of one mode the current transaction holds on the account table.
     *
     * <p>Assumptions: the catalogue view is filtered to this backend's own process, so a lock another
     * session holds cannot be miscounted as this one's. The relation is resolved through
     * {@code to_regclass} rather than by name comparison, because the catalogue stores an object
     * identifier and a name comparison would depend on the search path.</p>
     *
     * @param mode the lock mode to count, of type {@link String}, spelled as the catalogue spells it --
     *     {@code RowShareLock} or {@code AccessShareLock}; must not be {@code null}
     * @return the number of locks of that mode this transaction holds on {@code account.accounts}, of
     *     type {@link Long}; never {@code null}
     */
    private Long lockModesHeldOnAccounts(String mode) {
        return ((Number) this.entityManager.createNativeQuery("""
                select count(*) from pg_locks
                 where pid = pg_backend_pid()
                   and locktype = 'relation'
                   and relation = to_regclass('account.accounts')
                   and mode = :mode""")
                .setParameter("mode", mode)
                .getSingleResult()).longValue();
    }

    /**
     * The minimal application this class starts, scanning only what the unit of work needs.
     *
     * <p>Alternatives Considered: naming this module's own application class instead. Rejected for the
     * measured reason the sibling repository integration tests record: that class component-scans the
     * whole bounded context and pulls in a resource-server security chain that needs a resolvable token
     * issuer, so the context fails to start for a reason that has nothing to do with the property under
     * test.</p>
     *
     * <p>Assumptions: a {@link TransactionTemplate} bean is contributed here because this class opens its
     * own transactions and Boot's auto-configuration provides only the manager. Declaring it here rather
     * than autowiring the manager and constructing a template per use keeps one template with one
     * propagation setting, so no two cases can differ in it by accident.</p>
     */
    // WHY : Assumptions: AccountBalanceRepository is registered by an explicit import rather than by a
    //       scan, and it needs registering at all because it is the one member of the repository package
    //       that is a concrete @Repository CLASS rather than a Spring Data interface --
    //       @EnableJpaRepositories proxies interfaces and would leave it out, so the context would start
    //       and then fail to autowire it. Its @PersistenceContext field is still injected, because the
    //       annotation post-processor that handles it is registered by the framework for every bean
    //       definition rather than only for scanned ones.
    // WHY : ⚠️ Alternatives Considered: @ComponentScan over that same package, which was tried first and
    //       FAILS -- measured, not suspected. Test classes share the classpath with main classes, so the
    //       scan reaches the nested @SpringBootConfiguration classes of the four sibling repository
    //       integration tests, each of which declares its own @EnableJpaRepositories over the same
    //       package; two of them then register the same repository bean name and the context aborts with
    //       a bean-definition override. An explicit import names one class and cannot reach a sibling
    //       test's configuration at all.
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AccountBalanceRepository.class)
    @EntityScan("com.carddemo.transaction.domain")
    @EnableJpaRepositories("com.carddemo.transaction.repository")
    static class BillPaymentUnitOfWorkTestApplication {

        /**
         * Contributes the template this class opens its own transactions through.
         *
         * @param manager the platform transaction manager the auto-configuration built for the container
         *     connection; must not be {@code null}
         * @return the template, never {@code null}
         */
        @Bean
        TransactionTemplate billPaymentUnitOfWorkTransactionTemplate(
                org.springframework.transaction.PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
