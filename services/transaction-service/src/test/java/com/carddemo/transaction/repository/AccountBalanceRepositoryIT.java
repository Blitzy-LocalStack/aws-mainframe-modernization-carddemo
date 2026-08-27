package com.carddemo.transaction.repository;

import com.carddemo.common.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;


/**
 * Runs the two cross-schema balance statements against a real PostgreSQL engine.
 *
 * <h2>What this class asserts, and why an integration test is the only place it can be asserted</h2>
 *
 * <p>{@link AccountBalanceRepository} issues native SQL rather than deriving queries from an entity, so
 * nothing at compile time establishes that either statement parses. A mocked repository answers whatever a
 * case arranges, and the service tests that use one are asserting the SERVICE. These cases assert the
 * STATEMENTS: that the locked read returns the stored balance at its exact scale, that the reduction
 * subtracts rather than assigns, that it advances the owning context's version column, and that it reports
 * how many rows it changed so a vanished account is distinguishable from a settled one.</p>
 *
 * <p>Trade-offs: the {@code account.accounts} table is created HERE, by this test, with only the columns
 * the two statements name. That is a partial copy of a contract another module owns, which is exactly the
 * duplication the repository's own file refuses to make in production code -- and it is accepted in a test
 * because the alternative is not testing the statements at all: this module's Flyway configuration migrates
 * the {@code ledger} schema only, and running another module's migration from here would couple the two
 * builds far more tightly than a four-column fixture does. The drift this admits is closed by
 * {@link AccountBalanceSchemaAgreementTest}, which fails the build if any column named below is absent from
 * the owning migration; the two tests are complementary and neither is sufficient alone.</p>
 *
 * <p>Assumptions: the columns are created from the repository's own published constants rather than written
 * as literals, so a fixture that named a column the statements do not use could not be built at all.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = AccountBalanceRepositoryIT.BalanceAccessTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AccountBalanceRepositoryIT {

    /**
     * The engine image, named by manifest digest so the version cannot drift.
     *
     * <p>Assumptions: the same digest the sibling ledger integration test names, because two engines in one
     * module would be two behaviours to reason about and the point of pinning is that there is one.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";
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

    /** The engine every case in this class runs against. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(OWNER_ROLE_SCRIPT);

    /** The account the fixture row is keyed on, as the digit characters the screen carries. */
    private static final long ACCOUNT_ID = 11L;

    /** An account identifier no fixture row carries, so an absent read is distinguishable. */
    private static final long ABSENT_ACCOUNT_ID = 99L;

    /** The balance the fixture row starts with, chosen with non-zero cents. */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("1250.75");

    /** The repository under test. */
    @Autowired
    private AccountBalanceRepository balances;

    /** Used to build the fixture table, seed it and read the version column back. */
    @Autowired
    private EntityManager entityManager;

    /** Supplies the transaction the locked read needs, since a lock outside one is meaningless. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Creates the fixture table if it is absent and reseeds its single row before each case.
     *
     * <p>Assumptions: the table is created rather than assumed, because this module's migration does not
     * create it and will not. The statement is idempotent so the container is set up once and each case
     * starts from a known row rather than from a rebuilt schema.</p>
     */
    @BeforeEach
    void seedFixtureRow() {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.entityManager.createNativeQuery("create schema if not exists account").executeUpdate();
            this.entityManager.createNativeQuery(
                            "create table if not exists " + AccountBalanceRepository.TABLE + " ("
                                    + AccountBalanceRepository.COLUMN_ACCOUNT_ID + " bigint primary key,"
                                    + AccountBalanceRepository.COLUMN_CURRENT_BALANCE
                                    + " numeric(12,2) not null,"
                                    + AccountBalanceRepository.COLUMN_VERSION
                                    + " bigint not null default 0)")
                    .executeUpdate();
            this.entityManager.createNativeQuery(
                            "delete from " + AccountBalanceRepository.TABLE).executeUpdate();
            this.entityManager.createNativeQuery(
                            "insert into " + AccountBalanceRepository.TABLE + " values (?1, ?2, 0)")
                    .setParameter(1, ACCOUNT_ID)
                    .setParameter(2, OPENING_BALANCE)
                    .executeUpdate();
        });
    }

    /**
     * Confirms the locked read answers with the stored balance at the column's own scale.
     *
     * <p>Assumptions: the value is compared by VALUE rather than by equality, because a decimal carrying a
     * different scale is a different object and the same amount. The scale is asserted separately, because
     * it is part of the contract -- the money path is exact fixed point at scale two under transformation
     * rule T3, and a driver answering at another scale would be a finding rather than a detail.</p>
     */
    @Test
    @DisplayName("the locked read answers the stored balance, at scale two")
    void theLockedReadAnswersTheStoredBalanceAtScaleTwo() {
        Optional<BigDecimal> read = this.transactionTemplate.execute(status ->
                this.balances.lockCurrentBalance(ACCOUNT_ID).map(Money::amount));

        assertThat(read).isPresent().get()
                .satisfies(balance -> {
                    assertThat(balance).isEqualByComparingTo(OPENING_BALANCE);
                    assertThat(balance.scale())
                            .as("the column is NUMERIC(12,2), so the answer carries two decimals")
                            .isEqualTo(2);
                });
    }

    /**
     * Confirms an absent account reads as an empty answer rather than as a failure.
     *
     * <p>Assumptions: a row is present in the table when the miss is attempted, so an empty answer cannot
     * come from an empty table. Asserting a miss against no rows at all would pass against a statement that
     * never matched anything.</p>
     */
    @Test
    @DisplayName("an account no row carries reads as empty, not as a failure")
    void anAbsentAccountReadsAsEmpty() {
        Optional<BigDecimal> read = this.transactionTemplate.execute(status ->
                this.balances.lockCurrentBalance(ABSENT_ACCOUNT_ID).map(Money::amount));

        assertThat(read).isEmpty();
    }

    /**
     * Confirms the reduction subtracts, advances the version and reports one changed row.
     *
     * <p>Assumptions: all three effects are asserted together because they are one statement, and each
     * defends something different. The subtraction is the reference's arithmetic at line 234 of
     * {@code app/cbl/COBIL00C.cbl}; the version advance is what makes the OWNING context's optimistic
     * rewrite fail rather than silently overwrite a settled balance; and the row count is what lets the
     * caller tell a vanished account from a settled one, which is the reference's line 392 against its line
     * 399.</p>
     *
     * <p>Assumptions: the amount subtracted is a PART of the balance rather than the whole of it, even though
     * bill payment always pays the whole. Subtracting the whole would leave zero, and zero is also what an
     * assignment of the amount's complement would leave, so the case could not distinguish a subtraction
     * from an assignment -- which is the property it exists to pin.</p>
     */
    @Test
    @DisplayName("the reduction subtracts, advances the version and reports one row")
    void theReductionSubtractsAdvancesTheVersionAndReportsOneRow() {
        BigDecimal part = new BigDecimal("250.25");

        Integer changed = this.transactionTemplate.execute(status ->
                this.balances.reduceCurrentBalance(ACCOUNT_ID, Money.of(part)));

        assertThat(changed).isEqualTo(1);
        Optional<BigDecimal> afterReduction = this.transactionTemplate.execute(status ->
                this.balances.lockCurrentBalance(ACCOUNT_ID).map(Money::amount));
        assertThat(afterReduction).isPresent().get()
                .satisfies(balance -> assertThat(balance)
                        .as("line 234 subtracts from the balance as it stands")
                        .isEqualByComparingTo(OPENING_BALANCE.subtract(part)));
        assertThat(storedVersion())
                .as("the owning context checks its own rewrite against this column")
                .isEqualTo(1L);
    }

    /**
     * Confirms a reduction addressing no row changes nothing and says so.
     *
     * <p>Assumptions: the fixture row's balance is read back afterwards as well as the count, because a
     * statement with a wrong predicate could report zero while having altered another row. The count alone
     * would not distinguish the two.</p>
     */
    @Test
    @DisplayName("a reduction addressing no row reports zero and alters nothing")
    void aReductionAddressingNoRowReportsZero() {
        Integer changed = this.transactionTemplate.execute(status ->
                this.balances.reduceCurrentBalance(ABSENT_ACCOUNT_ID, Money.of("1.00")));

        assertThat(changed).isZero();
        Optional<BigDecimal> untouched = this.transactionTemplate.execute(status ->
                this.balances.lockCurrentBalance(ACCOUNT_ID).map(Money::amount));
        assertThat(untouched).isPresent().get()
                .satisfies(balance -> assertThat(balance).isEqualByComparingTo(OPENING_BALANCE));
        assertThat(storedVersion()).isZero();
    }

    /**
     * Reads the fixture row's version column.
     *
     * @return the version the row currently carries, never {@code null}
     */
    private Long storedVersion() {
        return this.transactionTemplate.execute(status -> ((Number) this.entityManager
                .createNativeQuery("select " + AccountBalanceRepository.COLUMN_VERSION + " from "
                        + AccountBalanceRepository.TABLE + " where "
                        + AccountBalanceRepository.COLUMN_ACCOUNT_ID + " = ?1")
                .setParameter(1, ACCOUNT_ID)
                .getSingleResult()).longValue());
    }

    /**
     * The minimal application this class boots, enabling persistence and nothing else.
     *
     * <p>Assumptions: it is declared nested rather than as a file of its own so the package's closed test
     * file set stays true, which is the same ruling the sibling ledger integration test records for its own
     * configuration. Two nested configurations enabling the same auto-configuration subset is repetition;
     * one shared holder that two tests could disagree about is worse.</p>
     *
     * <p>Assumptions: the repository under test is IMPORTED explicitly rather than found by a component
     * scan, and the distinction matters here. It is a plain component and not a Spring Data interface, so
     * {@code @EnableJpaRepositories} does not register it -- that annotation creates proxies for interfaces
     * and passes over a class. A component scan of the whole bounded context would register it and also drag
     * in every service, controller and messaging listener in the module, each with its own configuration
     * requirements, which is how a persistence test comes to fail for an unrelated reason. In production the
     * class is found by the application's own scan.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.transaction.domain")
    @EnableJpaRepositories("com.carddemo.transaction.repository")
    @Import(AccountBalanceRepository.class)
    static class BalanceAccessTestApplication {
    }
}
