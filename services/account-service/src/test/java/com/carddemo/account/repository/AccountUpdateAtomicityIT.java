package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.service.AccountRevision;
import com.carddemo.account.service.AccountUpdateService;
import com.carddemo.account.service.AddressValidationService;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the account update writes its customer row and its account row as one indivisible unit of work.
 *
 * <p>Purpose: {@code AccountUpdateService.update} flushes two rows in sequence -- the customer first, the
 * account second -- inside one {@code TransactionTemplate} callback. The baseline writes the same pair
 * inside one CICS task and reaches its single {@code EXEC CICS SYNCPOINT} only after both writes have
 * succeeded, so a state in which the customer's details moved and the account's did not is a state the
 * reference cannot produce. This class holds that guarantee against a real PostgreSQL engine: one case
 * proves both rows commit together, and one case forces the SECOND flush to fail and proves neither row
 * moved.</p>
 *
 * <p>Refactoring Rationale: the only prior coverage of this pair substituted the transaction manager --
 * {@code com.carddemo.account.service.AccountUpdatePreservationTest} passes
 * {@code mock(PlatformTransactionManager.class)}, whose templates run their callbacks and commit nothing --
 * and the controller test substitutes the whole service. Between them, every assertion about the write was
 * made against a component that cannot commit and cannot roll back, so the indivisibility claim rested on
 * reading the code. That substitution is correct for those classes and its own comment defers the commit
 * "to the container-backed integration test"; this is that test.</p>
 *
 * <p>Alternatives Considered for forcing the second flush to fail, since the choice decides what the
 * negative case actually proves:</p>
 *
 * <ul>
 *   <li>Stubbing {@code AccountRepository.saveAndFlush} to throw. Rejected: the repository is then not the
 *       real one, so the case would re-prove that a mock throws where it was told to and would say nothing
 *       about whether the engine rolled the earlier statement back.</li>
 *   <li>A {@code BEFORE UPDATE} trigger on {@code account.accounts} that raises. Rejected: it works, but it
 *       adds an object the migration does not define, so a reader has to hold the table under test and the
 *       table the migration creates apart in their head, and a forgotten drop would silently poison every
 *       later case in the class.</li>
 *   <li>Seeding the account's version column at {@code Long.MAX_VALUE} so the increment overflows. Rejected
 *       because it does NOT fail: the provider computes the next version in Java and writes it as a
 *       parameter, so the increment wraps to a negative number that {@code bigint} accepts without
 *       complaint. Verified rather than assumed -- the same trick DOES fail for a relative
 *       {@code version = version + 1} statement, which is how a sibling test uses it, and this write is not
 *       one.</li>
 *   <li>An over-wide group identifier. Rejected because {@code AccountMapper.applyUpdate} refuses one
 *       before either flush, so the customer row would never have been written and the case would prove
 *       nothing about rollback.</li>
 * </ul>
 *
 * <p>What is used instead is an over-wide MONETARY value. {@code AccountMapper} bounds an amount's scale
 * and admits only digits, but bounds no number of integer digits, so an eleven-digit balance passes every
 * Java edit and reaches {@code curr_bal NUMERIC(12,2)}, which admits ten. PostgreSQL then raises a numeric
 * field overflow on the account statement itself. The failure is therefore raised by the engine, on the
 * exact statement under test, inside the transaction, after the customer statement has already been
 * issued -- and it is reproducible on every run with no object added to the schema.</p>
 *
 * <p>Assumptions: that unbounded integer width is a real property of the code rather than a contrivance for
 * this test, and it is what makes the guarantee load-bearing: for the five monetary members the column is
 * the only thing standing between an over-wide submission and the database, so the rollback that follows is
 * a path a caller can actually reach.</p>
 *
 * <p>Assumptions: only {@code AddressValidationService}'s reference lookup is substituted, by a permissive
 * double rather than a mock. The address edits call another bounded context over HTTP, which no container
 * here provides; every other collaborator -- both mappers, both repositories, the cross-reference
 * repository and the real {@code JpaTransactionManager} the context builds -- is the deployed one. A refusal
 * from the lookup would abort the update before either flush and both cases below would pass for a reason
 * unrelated to atomicity.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: BOTH remote config-data locations are DISABLED for this context, for the
//       reason measured on the sibling CustomerMasterRepositoryIT: application.yml imports an AWS parameter
//       store and an AWS secrets manager location, and LOADING either builds a client from an unresolved
//       region placeholder while configuration is still in progress, aborting the context before the first
//       assertion. The `optional:` marker tolerates a location that yields nothing, not one whose client
//       cannot be constructed, so the flags are required rather than belt-and-braces.
@SpringBootTest(
        classes = AccountUpdateAtomicityIT.AccountWritePersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.aws.parameterstore.enabled=false",
                "spring.cloud.aws.secretsmanager.enabled=false"})
@ActiveProfiles("test")
class AccountUpdateAtomicityIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one engine
     * serves the whole suite and two tests cannot disagree about one schema. The version is recorded in
     * prose because a digest states nothing a reader recognises, and the two must be changed together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The account both cases edit, inside the eleven-digit range the master's key declares.
     */
    private static final long ACCOUNT_ID = 12_345_678_901L;

    /**
     * The customer the edited account names, inside the nine-digit range the customer key declares.
     */
    private static final long CUSTOMER_ID = 987_654_321L;

    /**
     * The card number of the one cross-reference row that ties the account to its customer.
     *
     * <p>Assumptions: the row is required rather than decorative. The service resolves the customer for an
     * account through the bounded single-row cross-reference query, so an account with no cross-reference
     * row is one it reports as absent, and both cases would fail before reaching a flush.</p>
     */
    private static final String CARD_NUMBER = "4000123456789010";

    /**
     * The family name the seeded customer row carries before either case runs.
     */
    private static final String SEEDED_LAST_NAME = "LOVELACE";

    /**
     * The family name both cases submit, so the customer statement genuinely changes a column.
     *
     * <p>Assumptions: the customer half of the submission MUST change something. A flush that finds nothing
     * dirty issues no statement at all, so a rollback case built on an unchanged customer row would prove
     * only that nothing was written, which is true whether or not the transaction rolled back.</p>
     */
    private static final String SUBMITTED_LAST_NAME = "HOPPER";

    /**
     * The balance the seeded account row carries before either case runs.
     */
    private static final String SEEDED_BALANCE = "1234.56";

    /**
     * The balance the committing case submits, chosen to fit the column it lands in.
     */
    private static final String ACCEPTED_BALANCE = "2000.00";

    /**
     * The balance the rollback case submits: eleven integer digits against a column admitting ten.
     *
     * <p>Assumptions: the value passes every Java edit -- the scale is two, every character is a digit and
     * no width rule applies to an amount's integer part -- and is refused by {@code NUMERIC(12,2)}. That
     * asymmetry is the injection point, and it is the reason this case exercises the engine rather than a
     * substitute.</p>
     */
    private static final String OVERFLOWING_BALANCE = "99999999999.99";

    /**
     * The engine's own wording for the refusal the over-wide balance provokes.
     *
     * <p>Assumptions: the refusal text is asserted and not merely the exception type, because the type alone
     * would be satisfied by a constraint failure anywhere in the unit of work -- including one on the
     * CUSTOMER statement, which would invert what this case proves. Pinning the engine's wording keeps the
     * case honest about what failed.</p>
     */
    private static final String REFUSED_STATEMENT_MARKER = "numeric field overflow";

    /**
     * The table the refused statement must name, proving the failure landed on the SECOND flush.
     *
     * <p>Assumptions: the provider includes the failing statement text in the translated exception, and the
     * statement under test updates this table. Asserting the table name is what distinguishes the situation
     * this case exists for -- the account statement failing after the customer statement was already issued
     * -- from "something failed somewhere", which would be equally true had a Java edit refused the
     * submission before either row was written. Without this assertion the case would still pass if a later
     * change moved the refusal earlier, and it would then be reporting a rollback it no longer exercised.
     * The marker is lower case and the message is folded before it is searched, because the statement text
     * is echoed in whatever case the provider emitted it and that is not a contract worth depending on.</p>
     */
    private static final String ACCOUNT_TABLE_MARKER = "update account.accounts";

    /**
     * The container the whole class runs against.
     *
     * <p>Assumptions: one static container is started for the class and reused by both cases, because
     * starting an engine per case would multiply the slowest step in the run by two for no isolation gain --
     * each case re-seeds both rows itself.</p>
     *
     * <p>Assumptions: the raw type is deliberate and matches every sibling here. The container class is
     * imported from {@code org.testcontainers.postgresql} rather than the deprecated
     * {@code org.testcontainers.containers}, and the replacement is not generic.</p>
     */
    @SuppressWarnings("rawtypes")
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The account master repository, injected as the deployed proxy rather than constructed.
     */
    @Autowired
    private AccountRepository accounts;

    /**
     * The customer master repository, injected as the deployed proxy rather than constructed.
     */
    @Autowired
    private CustomerRepository customers;

    /**
     * The cross-reference repository the service resolves an account's customer through.
     */
    @Autowired
    private CardXrefRepository crossReferences;

    /**
     * The real transaction manager the context builds over the container's pool.
     *
     * <p>Assumptions: this is the participant the prior coverage substituted, so injecting the real one is
     * the whole point of this class rather than an implementation detail of it.</p>
     */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * A direct template used to seed rows and to read committed state outside any transaction.
     *
     * <p>Assumptions: committed state is read through plain SQL rather than through the repositories,
     * because a repository read can be served from a persistence context that still holds the entity the
     * failed write left behind. Reading the columns directly answers what the DATABASE holds, which is the
     * question both cases ask.</p>
     */
    private JdbcTemplate jdbc;

    /**
     * The service under test, assembled over the injected persistence beans.
     */
    private AccountUpdateService service;

    /**
     * Reads the revision the two seeded rows currently stand at, as the deployed read path would publish it.
     *
     * <p>Assumptions: the token is composed from the two {@code @Version} values through the same class the
     * write path uses, {@link AccountRevision}, rather than being written as a literal. A literal would fix
     * the token's SHAPE inside these cases, and the shape is the write path's business -- the one property
     * they need is that the value they present is the one the stored rows are at.</p>
     *
     * <p>Refactoring Rationale: the revision is derived here rather than read from an operation on the
     * service, because the landed service publishes a revision only as part of an answer -- the read path
     * returns it beside a view and the write path beside the committed body -- and neither of those is what
     * these cases are exercising. Calling the read path first would put a second unit of work in front of
     * the one under test, and it would also load the very rows the failure case then expects to find
     * unchanged.</p>
     *
     * @return the concurrency token covering the seeded account and customer rows, never {@code null}
     */
    private String currentRevision() {
        Account account = this.accounts.findById(ACCOUNT_ID).orElseThrow();
        Customer customer = this.customers.findById(CUSTOMER_ID).orElseThrow();
        return AccountRevision.of(account, customer);
    }

    /**
     * Publishes the container's generated coordinates to the context before it refreshes.
     *
     * <p>Assumptions: the migration credentials are registered beside the pool's, because the test profile
     * deliberately commits no connection literal of any kind and Flyway opens its own connection from
     * {@code spring.flyway.user} and {@code spring.flyway.password}. Omitting the pair leaves Flyway with no
     * credential and ends the context load on its first connection.</p>
     *
     * @param registry the Spring test property registry the container's coordinates are added to as
     *     deferred suppliers; must not be {@code null}
     */
    @DynamicPropertySource
    static void publishContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Creates the schema and its owning role before Flyway opens its first connection.
     *
     * <p>Assumptions: {@code data-migration/sql/V0__schemas_and_roles.sql} is the exclusive authority for
     * schemas, owner roles and grants in this system, so {@code V1__account.sql} carries no
     * {@code CREATE SCHEMA} and the base profile forbids Flyway from creating one. A throwaway container has
     * never run that bootstrap, so the schema and its {@code NOLOGIN} owner have to exist first. This is the
     * same documented prerequisite the two sibling integration tests in this package each carry.</p>
     *
     * <p>Assumptions: the connection is opened through the driver directly rather than from an injected
     * pool, because this runs before the context refreshes -- no pool bean exists yet.</p>
     *
     * @throws SQLException if the container refuses the connection or any statement, which is a broken
     *     harness rather than a failed assertion
     */
    @BeforeAll
    static void createSchemaAndOwnerBeforeFlywayRuns() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE carddemo_account_owner NOLOGIN");

            // WHY : Assumptions: CREATE on the database is required and is not implied by role creation --
            //       a fresh role holds only the PUBLIC grants, so the schema creation below would fail once
            //       the role is assumed. The database name is interpolated because Testcontainers generates
            //       it, and it is quoted because no generated name is guaranteed to be a bare lower-case
            //       word.
            statement.execute("GRANT CREATE ON DATABASE \"" + POSTGRES.getDatabaseName()
                    + "\" TO carddemo_account_owner");
            statement.execute("CREATE SCHEMA account AUTHORIZATION carddemo_account_owner");
        }
    }

    /**
     * Empties all three tables, seeds one consistent trio and assembles the service over them.
     *
     * <p>Assumptions: the rows are deleted and re-seeded rather than each case being wrapped in a
     * rolled-back transaction. One case below asserts that the ENGINE rolled a transaction back, and a
     * rollback inside a rolled-back wrapper cannot be told apart from the wrapper's own.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void resetSeedAndAssemble(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM account.card_xref");
        this.jdbc.update("DELETE FROM account.accounts");
        this.jdbc.update("DELETE FROM account.customers");

        this.customers.saveAndFlush(seededCustomer());
        this.accounts.saveAndFlush(seededAccount());
        this.crossReferences.saveAndFlush(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID));

        // WHY : Assumptions: the service is constructed here rather than injected, because the context
        //       deliberately scans no service package -- see the nested configuration below -- and because
        //       the one collaborator that reaches another bounded context has to be substituted. Every
        //       participant that touches the database is the injected, deployed bean.
        this.service = new AccountUpdateService(this.accounts, this.customers, this.crossReferences,
                new AccountMapper(), new CustomerMapper(AccountUpdateAtomicityIT::derivedCiphertext),
                new AddressValidationService(new PermissiveLookup()),
                Clock.fixed(LocalDate.of(2022, 7, 18).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        ZoneOffset.UTC),
                this.transactionManager);
    }

    /**
     * An accepted edit commits the customer row and the account row together.
     *
     * <p>Purpose: this is the positive half of the guarantee, and it is asserted first because the negative
     * half is only meaningful once both statements are known to reach the database at all. A rollback case
     * standing alone would pass identically against an implementation that never wrote anything.</p>
     *
     * <p>Assumptions: the committed values are read with plain SQL after the call returns, so what is
     * asserted is what the database holds rather than what a persistence context remembers.</p>
     */
    @Test
    @DisplayName("an accepted edit commits the customer row and the account row together")
    void anAcceptedEditCommitsBothRows() {
        this.service.update(ACCOUNT_ID, submission(SUBMITTED_LAST_NAME, ACCEPTED_BALANCE),
                currentRevision());

        assertThat(storedLastName())
                .as("the customer statement must have committed")
                .isEqualTo(SUBMITTED_LAST_NAME);
        assertThat(storedBalance())
                .as("the account statement must have committed in the same unit of work")
                .isEqualByComparingTo(ACCEPTED_BALANCE);
    }

    /**
     * When the account statement fails, the customer statement issued before it does not survive.
     *
     * <p>Purpose: this is the guarantee itself. The customer row is flushed first, so between that flush and
     * the account flush there is a moment at which the database holds the new family name and the old
     * balance. If the account statement then fails and the transaction does not roll back, that moment
     * becomes the committed state -- a customer whose details moved against an account whose did not, which
     * the reference's single syncpoint cannot produce. This case forces exactly that failure and asserts
     * both rows are byte-for-byte what they were.</p>
     *
     * <p>Assumptions: the refusal is asserted as a {@code DataAccessException} rather than as a validation
     * refusal, because the whole point is that no Java edit caught this value. Were the mapper to gain an
     * integer-width rule later, this assertion would fail and say so, rather than quietly stopping short of
     * the database and continuing to report a rollback it no longer exercised.</p>
     *
     * <p>Assumptions: the version columns are asserted alongside the values. A row can be restored to its
     * original values while still carrying an incremented version, which would mean the write committed and
     * something else reverted it; asserting the versions rules that reading out.</p>
     */
    @Test
    @DisplayName("a failing account statement leaves neither the customer row nor the account row changed")
    void aFailingAccountStatementRollsBackTheCustomerStatementToo() {
        long customerVersionBefore = storedVersion("customers", "customer_id", CUSTOMER_ID);
        long accountVersionBefore = storedVersion("accounts", "account_id", ACCOUNT_ID);
        String revision = currentRevision();

        assertThatThrownBy(() -> this.service.update(ACCOUNT_ID,
                submission(SUBMITTED_LAST_NAME, OVERFLOWING_BALANCE), revision))
                .as("the engine must refuse the account statement, not a Java edit ahead of it")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(REFUSED_STATEMENT_MARKER)
                .satisfies(raised -> assertThat(String.valueOf(raised.getMessage())
                        .toLowerCase(Locale.ROOT))
                        .as("the refused statement must be the one updating the account master")
                        .contains(ACCOUNT_TABLE_MARKER));

        assertThat(storedLastName())
                .as("the customer statement was issued before the failure and must not survive it")
                .isEqualTo(SEEDED_LAST_NAME);
        assertThat(storedBalance())
                .as("the account statement failed, so the balance must be the seeded one")
                .isEqualByComparingTo(SEEDED_BALANCE);
        assertThat(storedVersion("customers", "customer_id", CUSTOMER_ID))
                .as("a restored value with a moved version would mean the write committed and was undone")
                .isEqualTo(customerVersionBefore);
        assertThat(storedVersion("accounts", "account_id", ACCOUNT_ID))
                .as("the account row must carry the version it was seeded with")
                .isEqualTo(accountVersionBefore);
    }

    /**
     * Reads the committed family name straight out of the customer row.
     *
     * @return the stored family name with trailing pad removed, never {@code null}
     */
    private String storedLastName() {
        return this.jdbc.queryForObject(
                "SELECT last_name FROM account.customers WHERE customer_id = ?", String.class, CUSTOMER_ID)
                .strip();
    }

    /**
     * Reads the committed balance straight out of the account row.
     *
     * @return the stored balance, never {@code null}
     */
    private BigDecimal storedBalance() {
        return this.jdbc.queryForObject(
                "SELECT curr_bal FROM account.accounts WHERE account_id = ?",
                BigDecimal.class, ACCOUNT_ID);
    }

    /**
     * Reads a row's optimistic-concurrency version straight out of its table.
     *
     * <p>Assumptions: the table and key column are parameters of the method rather than of the SQL, because
     * an identifier cannot be bound as a value. Both are supplied only from the constants in this class, so
     * no external input reaches the statement text.</p>
     *
     * @param table the unqualified table name inside the {@code account} schema; must not be {@code null}
     * @param keyColumn the primary-key column of that table; must not be {@code null}
     * @param key the primary-key value to read
     * @return the version the row currently carries
     */
    private long storedVersion(String table, String keyColumn, long key) {
        Long version = this.jdbc.queryForObject("SELECT version FROM account." + table
                + " WHERE " + keyColumn + " = ?", Long.class, key);
        return version == null ? -1L : version;
    }

    /**
     * Builds the customer row both cases start from.
     *
     * @return a seeded customer carrying the pre-edit family name, never {@code null}
     */
    private static Customer seededCustomer() {
        return new Customer(CUSTOMER_ID, "ADA", "M", SEEDED_LAST_NAME, "1 SYNTHETIC WAY", "SUITE 100",
                "TESTVILLE", "NY", "USA", "10001     ", "2125550100", "          ",
                derivedCiphertext("111226789", "ssn"),
                derivedCiphertext("SYNTHETICID000004321", "governmentIssuedId"),
                LocalDate.of(1980, 4, 2), "0000000001", "Y", (short) 742);
    }

    /**
     * Builds the account row both cases start from.
     *
     * <p>Assumptions: the group identifier is ten blanks rather than a named group, matching what the
     * mapper stores for a submission that names none and what the shipped account seeds carry.</p>
     *
     * @return a seeded account carrying the pre-edit balance, never {@code null}
     */
    private static Account seededAccount() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal(SEEDED_BALANCE), new BigDecimal("5000.00"),
                new BigDecimal("500.00"), LocalDate.of(2020, 1, 15), LocalDate.of(2027, 1, 31),
                LocalDate.of(2024, 1, 31), new BigDecimal("250.75"), new BigDecimal("1000.00"),
                "10001     ", "          ");
    }

    /**
     * Builds a submission that changes one customer column and one account column.
     *
     * <p>Assumptions: exactly two of the forty-three values vary between the two cases, so a difference in
     * outcome can only be attributed to the balance. Every other value repeats what the seeded rows already
     * hold, which also keeps the edit chain from refusing the submission for an unrelated reason.</p>
     *
     * @param lastName the family name to submit; must not be {@code null}
     * @param balance the balance to submit, as the screen's rendered digits; must not be {@code null}
     * @return a complete submission, never {@code null}
     */
    private static AccountUpdateRequest submission(String lastName, String balance) {
        return new AccountUpdateRequest(
                String.valueOf(ACCOUNT_ID),
                "Y",
                "5000.00",
                "500.00",
                balance,
                "250.75",
                "1000.00",
                "2020", "01", "15",
                "2027", "01", "31",
                "2024", "01", "31",
                "",
                String.valueOf(CUSTOMER_ID),
                "111", "22", "6789",
                "1980", "04", "02",
                "742",
                "ADA",
                "M",
                lastName,
                "1 SYNTHETIC WAY",
                "SUITE 100",
                "TESTVILLE",
                "NY",
                "USA",
                "10001",
                "212", "555", "0100",
                "", "", "",
                "SYNTHETICID000004321",
                "0000000001",
                "Y");
    }

    /**
     * Stands in for the protection boundary, returning bytes derived from what they protect.
     *
     * <p>Assumptions: the result is DERIVED from the input rather than random, which is why a substitute is
     * used at all. A real cipher returns different bytes every time by design, so a case could not
     * distinguish a column holding the new value from one re-enciphered with the old.</p>
     *
     * @param clearText the value to protect; must not be {@code null}
     * @param field the column it belongs to; must not be {@code null}
     * @return recognisable bytes naming the column and the value, never {@code null}
     */
    private static byte[] derivedCiphertext(String clearText, String field) {
        return ("enc:" + field + ":" + clearText).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A reference lookup that accepts every code, so the address edits never refuse these fixtures.
     *
     * <p>Assumptions: this double answers "present" rather than being a mock, because these cases are about
     * the transaction boundary and not about the address allow-lists. A refusal here would abort the update
     * before either flush, and both cases would then pass for a reason unrelated to atomicity. The refusal
     * paths are covered by {@code com.carddemo.account.service.AccountAddressValidationTest}.</p>
     */
    private static final class PermissiveLookup
            implements AddressValidationService.ReferenceAddressLookup {

        /**
         * Reports every area code as belonging to the general-purpose list.
         *
         * @param areaCode the candidate area code, ignored
         * @return always the general-purpose classification, never empty
         */
        @Override
        public Optional<AddressValidationService.AreaCodeClass> findAreaCodeClass(String areaCode) {
            return Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE);
        }

        /**
         * Reports every state code as present.
         *
         * @param stateCode the candidate state code, ignored
         * @return always {@code true}
         */
        @Override
        public boolean stateCodeExists(String stateCode) {
            return true;
        }

        /**
         * Reports every state and postal-prefix pairing as present.
         *
         * @param stateZipPrefix the candidate pairing, ignored
         * @return always {@code true}
         */
        @Override
        public boolean stateZipPrefixExists(String stateZipPrefix) {
            return true;
        }
    }

    /**
     * The narrowest context that can carry the three repositories and a real transaction manager.
     *
     * <p>Assumptions: no component scan is declared. The module's own application class registers the
     * datasource configuration, whose pool factory builds from {@code spring.datasource.*} properties, and
     * the api, service and config packages pull in an issuer, an internal signing key and a reference-context
     * address that this context supplies none of. Naming the entities and the repositories directly gives
     * exactly the beans these cases need and nothing that would fail to start.</p>
     *
     * <p>Assumptions: the resource-server and queue auto-configurations are excluded by name, matching both
     * siblings in this package. Neither has anything to configure here, and each would otherwise fail
     * resolving a coordinate no container provides.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            OAuth2ResourceServerAutoConfiguration.class,
            SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    static class AccountWritePersistenceTestApplication {
    }
}
