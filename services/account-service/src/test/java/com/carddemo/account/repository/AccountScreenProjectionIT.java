package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs four of the five read methods of {@link CardXrefRepository} against a real PostgreSQL engine holding
 * the module's own migrated schema: the joined account-screen projection, both positioned keyset browse
 * queries, and the derived first-card read. The fifth, {@code findByCardNum}, is a single-property lookup
 * with no ordering, bound or projection to get wrong, and it is left to the callers that already cover it.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Three of those four carry their query as an annotation string, and an annotation string is invisible to
 * the Java compiler. It is parsed once, when Hibernate builds the persistence unit and the repository
 * factory turns each declaration into a query object; the fourth is derived from its method name, which is
 * resolved at the same moment and is just as capable of naming a property that does not exist. Before this
 * class was written, this module started no Spring context in any test, so neither parse ever happened
 * during a build: a mistyped path expression, an unsupported join form, or a constructor expression naming a
 * type with no matching constructor would have compiled, satisfied every unit test in the module, and failed
 * for the first time when a deployed container started. Merely loading the context here is therefore already
 * an assertion, and it is the assertion that would have caught the joined projection this class was added
 * alongside.</p>
 *
 * <p>Refactoring Rationale: loading the context also caught a defect nothing else in this repository could
 * see. The three entities of this context mapped nine {@code CHAR(n)} columns without stating the
 * fixed-character JDBC type, so the provider inferred {@code VARCHAR} for each and schema validation refused
 * to build the persistence unit. The deployed profile validates nothing -- it runs with schema management
 * off -- so the mismatch would not have stopped a container from starting. It would have surfaced instead as
 * a comparison, because PostgreSQL resolves {@code bpchar} against {@code varchar} under text rules, where
 * the blank padding a {@code CHAR} column adds is significant: a padded stored value would then fail to
 * equal the unpadded parameter a caller supplied, silently and without an error. Every other persistence
 * module in this repository already stated the type; this one did not, and no test could tell.</p>
 *
 * <p>Assumptions: the unit tests of this module mock this repository, and that is correct for what they
 * check -- how a caller behaves given rows -- but a mocked interface never parses its own annotations, so no
 * number of them can cover the parse. The two kinds of test are complementary rather than redundant.</p>
 *
 * <p>Trade-offs: a real engine rather than an in-memory substitute costs a container start per class. What
 * it buys is that {@code card_num} really is {@code CHAR(16)}, so blank padding, character ordering and the
 * three-valued logic of the nullable join columns behave as the deployed engine implements them. All three
 * are load-bearing for a keyset browse, and an approximation of any of them would let a browse defect pass.
 * The container is declared here rather than inherited from a shared base for the reason the sibling
 * contexts give: the house determinism convention rests its isolation claim on tests sharing nothing.</p>
 *
 * <h2>Why two properties are set on the annotation rather than in the test profile</h2>
 *
 * <p>Assumptions: this is the first test in this module to load the deployed configuration document, and
 * that document declares two {@code spring.config.import} locations, {@code aws-parameterstore:} and
 * {@code aws-secretsmanager:}. Both are prefixed {@code optional:}, which tolerates a location that holds
 * nothing -- but tolerating an empty location is not the same as tolerating an unconfigured client, and the
 * resolver builds its client before it discovers the location is empty. The document sets the region from
 * {@code AWS_REGION} with no fallback, deliberately, so that no deployment can come up pointed at the wrong
 * one; on a build host that variable is unset, the placeholder survives into the endpoint, and the software
 * development kit rejects it. The two properties below take the resolvers out of the run entirely, which is
 * the only lever that acts early enough: a config-data location declared in one document is resolved while
 * the environment is still being assembled, BEFORE the profile document that would have supplied a region
 * has been read, so the same two keys placed in {@code application-test.yml} would be evaluated too late to
 * matter. Properties named on the annotation are inlined into the environment before that assembly runs.</p>
 *
 * <p>Trade-offs: what is given up is that this class proves nothing about the two config-data imports. That
 * is deliberate -- they are covered by the module's own start-up tests, which exercise them against supplied
 * coordinates -- and the alternative was worse: supplying a syntactically valid region would have let the
 * resolvers construct clients and then attempt a real network call to a service this test has no business
 * reaching, turning a deterministic persistence test into one that fails differently depending on the host's
 * network.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = AccountScreenProjectionIT.AccountPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.cloud.aws.parameterstore.enabled=false",
            "spring.cloud.aws.secretsmanager.enabled=false"
        })
@ActiveProfiles("test")
class AccountScreenProjectionIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the same one every other integration test in this repository names, so
     * one cached layer set serves them all and no two modules can silently run against different engine
     * builds. A floating tag was rejected: it would let a registry-side rebuild change the collation or the
     * default settings under an unchanged commit, and ordering assertions are exactly what that would
     * break.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container every case in this class runs against, started once for the class.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The account that owns three cross-references and whose account and customer masters both exist.
     */
    private static final long ACCOUNT_WITH_BOTH_MASTERS = 10000000001L;

    /**
     * The account that owns one cross-reference and has NO row in {@code account.accounts}.
     *
     * <p>Assumptions: this is a state the reference can hold, because the three VSAM files it migrates are
     * independent and carry no integrity constraint between them.</p>
     */
    private static final long ACCOUNT_WITHOUT_ACCOUNT_MASTER = 10000000002L;

    /**
     * The account whose single cross-reference names a customer that has NO row in
     * {@code account.customers}.
     */
    private static final long ACCOUNT_WITH_ABSENT_CUSTOMER = 10000000003L;

    /**
     * An account identifier no cross-reference names, used to assert the empty answers.
     */
    private static final long ACCOUNT_WITH_NO_CROSS_REFERENCE = 10000000004L;

    /**
     * The customer the three cross-references of {@link #ACCOUNT_WITH_BOTH_MASTERS} name.
     */
    private static final long PRESENT_CUSTOMER = 900000001L;

    /**
     * The customer the cross-reference of {@link #ACCOUNT_WITHOUT_ACCOUNT_MASTER} names.
     */
    private static final long SECOND_PRESENT_CUSTOMER = 900000002L;

    /**
     * A customer identifier deliberately never inserted, so the row that names it is an orphan.
     */
    private static final long ABSENT_CUSTOMER = 900000999L;

    /**
     * The lowest card number of {@link #ACCOUNT_WITH_BOTH_MASTERS}, sixteen characters exactly.
     */
    private static final String FIRST_CARD = "4000000000000010";

    /**
     * The middle card number of {@link #ACCOUNT_WITH_BOTH_MASTERS}.
     */
    private static final String SECOND_CARD = "4000000000000020";

    /**
     * The highest card number of {@link #ACCOUNT_WITH_BOTH_MASTERS}.
     */
    private static final String THIRD_CARD = "4000000000000030";

    /**
     * The single card number of {@link #ACCOUNT_WITHOUT_ACCOUNT_MASTER}.
     */
    private static final String ORPHAN_ACCOUNT_CARD = "4000000000000040";

    /**
     * The single card number of {@link #ACCOUNT_WITH_ABSENT_CUSTOMER}.
     */
    private static final String ORPHAN_CUSTOMER_CARD = "4000000000000050";

    /**
     * A bound wide enough to return every seeded row, so a test asserting on order is not also asserting on
     * truncation.
     */
    private static final Limit UNTRUNCATED = Limit.of(10);

    /**
     * The repository under test.
     */
    @Autowired
    private CardXrefRepository repository;

    /**
     * The pool the fixture rows are written through.
     *
     * <p>Trade-offs: the fixture is seeded with plain statements rather than through the repository or an
     * entity manager, deliberately. The orphan rows this class depends on cannot be written through a
     * mapped association at all -- there is none to write -- and writing the valid rows the same way keeps
     * one seeding path instead of two. It also keeps the write side out of the thing under test: a
     * projection that returned nothing because the fixture never persisted would otherwise be
     * indistinguishable from one that returned nothing because the query is wrong.</p>
     */
    @Autowired
    private DataSource dataSource;

    /**
     * Publishes the container's generated coordinates into the environment the context binds.
     *
     * <p>Assumptions: the test profile deliberately leaves the datasource triple as placeholders, because
     * the container's port and database name are generated per run and no committed value could match them.
     * Flyway is given the same credentials so the migration and the entities reach one database.</p>
     *
     * @param registry the registry Spring Test supplies for this class, never {@code null}
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
     * Creates the owning role and the schema that the migration expects to find already present.
     *
     * <p>Assumptions: {@code data-migration/sql/V0__schemas_and_roles.sql} is the exclusive authority for
     * schemas, roles and grants, and this container has never run it. That bootstrap is a precondition of
     * the service starting rather than part of its migration, so {@code V1__account.sql} contains no
     * {@code CREATE SCHEMA} and the base profile forbids Flyway from creating one. Supplying the
     * precondition here lets the migration run under the ownership a deployment gives it, because the base
     * profile's own {@code carddemo.database.flyway.owner-role} names this role, common-lib's
     * {@code FlywayOwnerRoleDataSourceCustomizer} assumes it before Flyway wraps a connection and its
     * {@code FlywayOwnerRoleCallback} re-asserts it, and every object
     * {@code V1__account.sql} creates therefore belongs to it.</p>
     *
     * <p>Trade-offs: the three statements below name the same role the bootstrap document names, so the two
     * do have to agree. Letting the test profile create the role and grant it rights was rejected: a profile
     * issuing those statements becomes a second definition of the cluster's role graph that no migration
     * history records. Keeping the prerequisite in test setup leaves one authority for the deployed graph
     * and one visible harness step for the container.</p>
     *
     * <p>Assumptions: this runs before the Spring context is created, because JUnit invokes an
     * {@code @BeforeAll} method after the Testcontainers extension has started the static container and
     * before the Spring extension creates the context for the first test instance. Plain JDBC is used
     * rather than the injected {@code DataSource} for that reason -- no bean exists yet.</p>
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
            // WHY : Assumptions: CREATE on the database is required and is not implied by role creation --
            //       a fresh role holds only the PUBLIC grants, which are CONNECT and TEMPORARY, so the
            //       schema creation below would fail once the role is assumed. The database name is
            //       interpolated because Testcontainers generates it, and it is quoted as an identifier
            //       because no generated name is guaranteed to be a bare lower-case word.
            statement.execute("GRANT CREATE ON DATABASE \"" + POSTGRES.getDatabaseName()
                    + "\" TO carddemo_account_owner");
            statement.execute("CREATE SCHEMA account AUTHORIZATION carddemo_account_owner");
        }
    }

    /**
     * Returns the schema to the same five-row fixture before every case.
     *
     * <p>Trade-offs: the tables are emptied and refilled per case rather than filled once for the class.
     * The cost is five inserts per case; what it buys is that a row left behind by one case cannot change a
     * count or an order in another, which is the failure hardest to read correctly.</p>
     *
     * @throws IllegalStateException if the fixture cannot be written, which is a broken harness rather than
     *         a failed assertion and is reported as such
     */
    @BeforeEach
    void resetToFixture() {
        try (Connection connection = this.dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE account.card_xref, account.accounts, account.customers");
            }
            insertCustomer(connection, PRESENT_CUSTOMER);
            insertCustomer(connection, SECOND_PRESENT_CUSTOMER);
            insertAccount(connection, ACCOUNT_WITH_BOTH_MASTERS);
            insertAccount(connection, ACCOUNT_WITH_ABSENT_CUSTOMER);
            insertCrossReference(connection, THIRD_CARD, PRESENT_CUSTOMER, ACCOUNT_WITH_BOTH_MASTERS);
            insertCrossReference(connection, FIRST_CARD, PRESENT_CUSTOMER, ACCOUNT_WITH_BOTH_MASTERS);
            insertCrossReference(connection, SECOND_CARD, PRESENT_CUSTOMER, ACCOUNT_WITH_BOTH_MASTERS);
            insertCrossReference(connection, ORPHAN_ACCOUNT_CARD, SECOND_PRESENT_CUSTOMER,
                    ACCOUNT_WITHOUT_ACCOUNT_MASTER);
            insertCrossReference(connection, ORPHAN_CUSTOMER_CARD, ABSENT_CUSTOMER,
                    ACCOUNT_WITH_ABSENT_CUSTOMER);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not seed the card cross-reference fixture", failure);
        }
    }

    /**
     * Writes one customer master row carrying every column the migration declares mandatory.
     *
     * <p>Assumptions: the national identifier column is written as arbitrary non-empty bytes. It is
     * declared {@code BYTEA NOT NULL} and holds ciphertext, and nothing on the read path converts it, so a
     * projection that hydrates a customer neither decrypts it nor needs a key. Writing recognisable
     * plaintext there would imply the column holds plaintext, which is the opposite of its contract.</p>
     *
     * @param connection the open connection to write through, never {@code null}
     * @param customerId the identifier to write
     * @throws SQLException if the insert fails
     */
    private void insertCustomer(Connection connection, long customerId) throws SQLException {
        String sql = """
                INSERT INTO account.customers (
                    customer_id, first_name, last_name, addr_line_1, addr_line_3,
                    addr_state_cd, addr_country_cd, addr_zip, ssn_encrypted, dob,
                    eft_account_id, pri_card_holder_ind, fico_credit_score, version)
                VALUES (?, 'FIXTURE', 'CUSTOMER', 'ADDRESS LINE ONE', 'ADDRESS LINE THREE',
                    'TX', 'USA', '75001', ?, DATE '1980-01-01',
                    '0000000001', 'Y', 750, 0)
                """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setLong(1, customerId);
            insert.setBytes(2, "ciphertext".getBytes(StandardCharsets.UTF_8));
            insert.executeUpdate();
        }
    }

    /**
     * Writes one account master row carrying every column the migration declares mandatory.
     *
     * @param connection the open connection to write through, never {@code null}
     * @param accountId the identifier to write
     * @throws SQLException if the insert fails
     */
    private void insertAccount(Connection connection, long accountId) throws SQLException {
        String sql = """
                INSERT INTO account.accounts (
                    account_id, active_status, curr_bal, credit_limit, cash_credit_limit,
                    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit,
                    addr_zip, group_id, version)
                VALUES (?, 'Y', 1234.56, 5000.00, 1000.00,
                    DATE '2020-01-01', DATE '2030-01-01', DATE '2025-01-01', 0.00, 0.00,
                    '75001', 'DEFAULT', 0)
                """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setLong(1, accountId);
            insert.executeUpdate();
        }
    }

    /**
     * Writes one cross-reference row, which may name a master that does not exist.
     *
     * @param connection the open connection to write through, never {@code null}
     * @param cardNum the sixteen-character card number, which is the primary key
     * @param customerId the customer this card belongs to, present or absent
     * @param accountId the account this card belongs to, present or absent
     * @throws SQLException if the insert fails
     */
    private void insertCrossReference(Connection connection, String cardNum, long customerId, long accountId)
            throws SQLException {
        String sql = "INSERT INTO account.card_xref (card_num, customer_id, account_id) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, cardNum);
            insert.setLong(2, customerId);
            insert.setLong(3, accountId);
            insert.executeUpdate();
        }
    }

    /**
     * Asserts the joined projection parses, returns one row per cross-reference, and orders by card number
     * ascending rather than by insertion order.
     *
     * <p>Assumptions: the fixture inserts the three cards highest-first on purpose. A projection that
     * happened to return insertion order would pass an assertion written against ascending order if the
     * fixture had been inserted ascending, so the fixture is deliberately hostile to that coincidence.</p>
     */
    @Test
    void theJoinedProjectionReturnsOneRowPerCrossReferenceInAscendingCardNumberOrder() {
        List<AccountScreenRow> rows =
                this.repository.findAccountScreenRows(ACCOUNT_WITH_BOTH_MASTERS, UNTRUNCATED);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.crossReference().getCardNum())
                .containsExactly(FIRST_CARD, SECOND_CARD, THIRD_CARD);
    }

    /**
     * Asserts that when both masters exist the projection hydrates both components from the same statement,
     * which is the whole point of replacing three separate reads with one.
     */
    @Test
    void theJoinedProjectionHydratesBothMastersWhenBothArePresent() {
        List<AccountScreenRow> rows =
                this.repository.findAccountScreenRows(ACCOUNT_WITH_BOTH_MASTERS, UNTRUNCATED);

        AccountScreenRow first = rows.getFirst();
        assertThat(first.crossReference()).isNotNull();
        assertThat(first.crossReference().getAccountId()).isEqualTo(ACCOUNT_WITH_BOTH_MASTERS);
        assertThat(first.crossReference().getCustomerId()).isEqualTo(PRESENT_CUSTOMER);

        Account account = first.account();
        assertThat(account).isNotNull();
        assertThat(account.getAccountId()).isEqualTo(ACCOUNT_WITH_BOTH_MASTERS);
        assertThat(account.getActiveStatus()).isEqualTo("Y");

        Customer customer = first.customer();
        assertThat(customer).isNotNull();
        assertThat(customer.getCustomerId()).isEqualTo(PRESENT_CUSTOMER);
        assertThat(customer.getLastName()).isEqualTo("CUSTOMER");
    }

    /**
     * Asserts an absent account master leaves that component null and does NOT drop the row.
     *
     * <p>Assumptions: this is the case an inner join would silently delete. The reference answers a missing
     * account master with its own message and a missing customer master with a different one, so a query
     * that collapsed both into one empty result would make the two indistinguishable and would cost the
     * caller the ability to choose between the messages.</p>
     */
    @Test
    void anAbsentAccountMasterLeavesTheAccountComponentNullWithoutDroppingTheRow() {
        List<AccountScreenRow> rows =
                this.repository.findAccountScreenRows(ACCOUNT_WITHOUT_ACCOUNT_MASTER, UNTRUNCATED);

        assertThat(rows).hasSize(1);
        AccountScreenRow only = rows.getFirst();
        assertThat(only.crossReference().getCardNum()).isEqualTo(ORPHAN_ACCOUNT_CARD);
        assertThat(only.account()).isNull();
        assertThat(only.customer()).isNotNull();
        assertThat(only.customer().getCustomerId()).isEqualTo(SECOND_PRESENT_CUSTOMER);
    }

    /**
     * Asserts an absent customer master leaves that component null while the account component is still
     * hydrated, so the two misses remain distinguishable from one another.
     */
    @Test
    void anAbsentCustomerMasterLeavesTheCustomerComponentNullWithoutDroppingTheRow() {
        List<AccountScreenRow> rows =
                this.repository.findAccountScreenRows(ACCOUNT_WITH_ABSENT_CUSTOMER, UNTRUNCATED);

        assertThat(rows).hasSize(1);
        AccountScreenRow only = rows.getFirst();
        assertThat(only.crossReference().getCardNum()).isEqualTo(ORPHAN_CUSTOMER_CARD);
        assertThat(only.customer()).isNull();
        assertThat(only.account()).isNotNull();
        assertThat(only.account().getAccountId()).isEqualTo(ACCOUNT_WITH_ABSENT_CUSTOMER);
    }

    /**
     * Asserts the bound is applied by the engine and keeps the lowest card number rather than an arbitrary
     * one, which is what makes a single-row read of this projection deterministic.
     */
    @Test
    void theLimitBoundsTheProjectionAndKeepsTheLowestCardNumber() {
        List<AccountScreenRow> rows =
                this.repository.findAccountScreenRows(ACCOUNT_WITH_BOTH_MASTERS, Limit.of(1));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().crossReference().getCardNum()).isEqualTo(FIRST_CARD);
    }

    /**
     * Asserts the projection answers an account with no cross-reference with an empty list rather than with
     * a row whose components are all null.
     */
    @Test
    void theProjectionIsEmptyForAnAccountThatHasNoCrossReference() {
        assertThat(this.repository.findAccountScreenRows(ACCOUNT_WITH_NO_CROSS_REFERENCE, UNTRUNCATED))
                .isEmpty();
    }

    /**
     * Asserts the first-card read returns the LOWEST card number of the account.
     *
     * <p>Assumptions: this method replaced a read that loaded every cross-reference of the account and then
     * took the first element in memory. Both forms answer identically only if the engine applies the same
     * order, so the ordering is asserted here rather than assumed: the fixture inserts the highest card
     * first, and a read that returned insertion order would return the wrong card.</p>
     */
    @Test
    void theFirstCardReadReturnsTheLowestCardNumberForTheAccount() {
        Optional<CardXref> first =
                this.repository.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_WITH_BOTH_MASTERS);

        assertThat(first).isPresent();
        assertThat(first.get().getCardNum()).isEqualTo(FIRST_CARD);
        assertThat(first.get().getCustomerId()).isEqualTo(PRESENT_CUSTOMER);
    }

    /**
     * Asserts the first-card read is empty rather than failing when the account owns no card.
     */
    @Test
    void theFirstCardReadIsEmptyForAnAccountThatHasNoCrossReference() {
        assertThat(this.repository.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_WITH_NO_CROSS_REFERENCE))
                .isEmpty();
    }

    /**
     * Asserts the forward browse excludes the card number it is positioned on and narrows to the account.
     *
     * <p>Assumptions: the bound must be STRICT. A non-strict bound would return the row the caller has
     * already seen as the first row of the next page, which the reference's browse does not do, and the
     * repeated row would be indistinguishable from a genuine duplicate.</p>
     */
    @Test
    void theForwardBrowseIsStrictlyAfterTheSuppliedCardNumberAndNarrowsToTheAccount() {
        List<CardXref> rows =
                this.repository.findForwardFromCursor(FIRST_CARD, ACCOUNT_WITH_BOTH_MASTERS, UNTRUNCATED);

        assertThat(rows).extracting(CardXref::getCardNum).containsExactly(SECOND_CARD, THIRD_CARD);
    }

    /**
     * Asserts an unpositioned forward browse opens at the lowest card number of the account.
     *
     * <p>Assumptions: the absent position is expressed as a null parameter rather than as a low sentinel
     * card number, and the query guards it with a null test. That guard is exercised here because a
     * sentinel would be a value some card could legitimately equal, and the engine must be shown to accept
     * the null form against a character column rather than merely be assumed to.</p>
     */
    @Test
    void theUnpositionedForwardBrowseOpensAtTheLowestCardNumberOfTheAccount() {
        List<CardXref> rows =
                this.repository.findForwardFromCursor(null, ACCOUNT_WITH_BOTH_MASTERS, UNTRUNCATED);

        assertThat(rows).extracting(CardXref::getCardNum)
                .containsExactly(FIRST_CARD, SECOND_CARD, THIRD_CARD);
    }

    /**
     * Asserts an unpositioned, unnarrowed forward browse spans every account in ascending card order, which
     * is the only case that exercises both null guards of the query at once.
     */
    @Test
    void theUnnarrowedForwardBrowseSpansEveryAccountInAscendingCardNumberOrder() {
        List<CardXref> rows = this.repository.findForwardFromCursor(null, null, UNTRUNCATED);

        assertThat(rows).extracting(CardXref::getCardNum)
                .containsExactly(FIRST_CARD, SECOND_CARD, THIRD_CARD, ORPHAN_ACCOUNT_CARD,
                        ORPHAN_CUSTOMER_CARD);
    }

    /**
     * Asserts the backward browse excludes the card number it is positioned on and yields descending order.
     *
     * <p>Assumptions: descending is what the query must return, not what the caller wants to display. The
     * caller reverses the retained window after discarding the surplus row, and asserting the raw
     * descending order here is what keeps that responsibility visible in one place instead of being
     * absorbed silently by a query that pre-reversed.</p>
     */
    @Test
    void theBackwardBrowseIsStrictlyBeforeTheSuppliedCardNumberAndYieldsDescendingOrder() {
        List<CardXref> rows =
                this.repository.findBackwardFromCursor(THIRD_CARD, ACCOUNT_WITH_BOTH_MASTERS, UNTRUNCATED);

        assertThat(rows).extracting(CardXref::getCardNum).containsExactly(SECOND_CARD, FIRST_CARD);
    }

    /**
     * Asserts the backward browse from the lowest card number is empty, which is how the caller learns that
     * the page it is on is the opening page.
     */
    @Test
    void theBackwardBrowseFromTheLowestCardNumberIsEmpty() {
        assertThat(this.repository.findBackwardFromCursor(FIRST_CARD, ACCOUNT_WITH_BOTH_MASTERS, UNTRUNCATED))
                .isEmpty();
    }

    /**
     * The narrowest context that can start this class: the entities and the repositories of this context,
     * and nothing else.
     *
     * <p>Assumptions: {@code @SpringBootConfiguration} performs no component scan of its own, so the api,
     * service, mapper and config packages are absent from this context. That is deliberate: none of them is
     * under test here, and one of them would otherwise pull the resource-server filter chain, the
     * key-management client and the reference-context adapter into a test about four SQL statements.</p>
     *
     * <p>Trade-offs: two auto-configurations are excluded by name rather than neutralised by property. The
     * resource-server one is excluded because it builds its decoder eagerly and would issue a discovery
     * request against the deliberately unroutable test issuer; the queue one because it would construct a
     * client for a transport no query here uses. Excluding them keeps the failure surface of this class to
     * the persistence layer, at the cost that neither is covered here -- which is correct, because each is
     * covered where it is configured.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    static class AccountPersistenceTestApplication {
    }
}
