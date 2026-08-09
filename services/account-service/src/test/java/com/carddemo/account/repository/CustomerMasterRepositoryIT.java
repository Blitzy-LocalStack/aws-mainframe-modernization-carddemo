package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.domain.Customer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds {@code account.customers} and its three keyset window queries against a real PostgreSQL engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the customer master is the migrated form of the file {@code app/cbl/CBCUS01C.cbl} declares
 * -- {@code ACCESS MODE IS SEQUENTIAL} at L31 over {@code RECORD KEY IS FD-CUST-ID} at L32, carving each
 * record into a nine-digit key at L39 and four hundred and ninety-one opaque bytes at L40, with the
 * meaningful layout copied in at L45 by {@code COPY CVCUS01Y.} -- and the bounded scan built on top of it
 * is what a caller now uses in place of that program's unbounded sweep at L74 through L81. This class
 * asserts the half of that migration only an engine can answer for: that the production migration creates
 * what the entity maps, that the widths and the domain constraint are real, and that the three window
 * queries order and bound exactly as the scan assumes.
 *
 * <p>Refactoring Rationale: all three window queries are DERIVED from their method names, so nothing in
 * the source states what they do -- the framework composes the statement at startup from
 * {@code GreaterThan}, {@code LessThan} and the two {@code OrderBy} clauses. A reader cannot check them
 * and a substituted repository cannot either: a mock returns whatever a case hands it, so a test over a
 * mock asserts the caller's arithmetic and not the query's. Executing them is the only way the exclusivity
 * of the bound becomes a fact rather than a reading of a method name.
 *
 * <h2>What each group covers</h2>
 *
 * <p>Assumptions: the widths asserted are the ones a defect would be silent in. {@code CUST-ADDR-ZIP} is
 * {@code PIC X(10)} at L14 of {@code app/cpy/CVCUS01Y.cpy} and the migration declares {@code CHAR(10)},
 * so the engine pads a shorter value out to ten -- and the projection REFUSES a stored value whose width
 * is not exactly ten, so a column authored as {@code VARCHAR} would leave every read of a short postal
 * code failing at the mapper with no hint that the schema was at fault. The two protected columns are
 * asserted as {@code BYTEA} for the mirror-image reason: ciphertext in a character column would be
 * transcoded on the way through, and the corruption would only surface at decryption.
 *
 * <p>Assumptions: the whole-master walk is asserted as well as the individual windows, because the
 * property the scan promises a caller is a property of the SEQUENCE of pages rather than of any one of
 * them. Concatenated, the pages must reproduce the sequence L78 of the reference would have written -- no
 * row skipped and none repeated -- and a bound that was inclusive rather than exclusive would repeat one
 * row per page boundary while every single page still looked correct on its own.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
// WHY : Refactoring Rationale: BOTH remote config-data locations are DISABLED for this context, because
//       otherwise it cannot start. application.yml L390 and L391 declare
//       `optional:aws-parameterstore:/carddemo/${CARDDEMO_ENVIRONMENT:local}/account/` and
//       `optional:aws-secretsmanager:/carddemo/${CARDDEMO_ENVIRONMENT:local}/account/`, and LOADING either
//       one builds an AWS client while configuration is still in progress. The static region this module's
//       test profile sets arrives too late to help: a profile-specific document is read after the
//       non-profile document's imports have been resolved, so each client is built from the unresolved
//       placeholder at L431 and the context aborts. Measured, not inferred: every case in this class first
//       failed on `https://ssm.%24%7BAWS_REGION%7D.amazonaws.com` and then, with only the first location
//       disabled, on `https://secretsmanager.%24%7BAWS_REGION%7D.amazonaws.com` -- which is why both flags
//       are set rather than one.
// WHY : Assumptions: each flag stops the LOAD rather than the resolution, and that is why it is
//       sufficient. The resolver decides only on the location PREFIX, so a disabled starter's location is
//       still resolved; the loader consults the flag and returns before its client is built, and the
//       client is what fails. The `optional:` marker does not help either: it tolerates a location that
//       yields nothing, not one that cannot construct a client.
// WHY : Alternatives Considered: naming a configuration document that does not exist, which is what the
//       sibling AwsStarterRuntimeIT in this module does through `spring.config.name=aws-runtime-it`.
//       Rejected here for the opposite reason it was chosen there: that test asserts the AWS starters and
//       therefore wants this module's own document out of the way, while this class asserts the schema and
//       the queries and NEEDS the real document -- its datasource pool sizing, its schema binding and its
//       Flyway configuration are the deployed ones and are part of what is under test.
@SpringBootTest(
        classes = CustomerMasterRepositoryIT.CustomerPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.aws.parameterstore.enabled=false",
                "spring.cloud.aws.secretsmanager.enabled=false"})
@ActiveProfiles("test")
class CustomerMasterRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two tests cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises, and the two must be changed
     * together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The identifier of the first seeded row, with the rest following consecutively.
     *
     * <p>Assumptions: inside the nine-digit range {@code CUST-ID PIC 9(09)} declares at L5 of
     * {@code app/cpy/CVCUS01Y.cpy}. The value is fabricated and identifies nobody.</p>
     */
    private static final long FIRST_CUSTOMER_ID = 900000001L;

    /**
     * The number of rows every case seeds.
     *
     * <p>Assumptions: seven against a window of three yields two full windows and a third holding one row,
     * so the walk below reaches its end by exhaustion rather than by a count it already knew.</p>
     */
    private static final int SEEDED_ROWS = 7;

    /** The rows one window carries, deliberately smaller than the seeded master. */
    private static final int WINDOW_ROWS = 3;

    /** The declared width of the postal code, which the engine pads a shorter value out to. */
    private static final int POSTAL_CODE_WIDTH = 10;

    /** The declared width of the electronic-funds account identifier, likewise fixed. */
    private static final int EFT_ACCOUNT_ID_WIDTH = 10;

    /**
     * The ciphertext both protected columns hold.
     *
     * <p>Assumptions: bytes that are NOT valid text in any single-byte encoding, deliberately. A value
     * that happened to be printable would survive a character column too, so the round trip would pass
     * against exactly the schema defect this class exists to rule out.</p>
     */
    private static final byte[] CIPHERTEXT = {0x00, (byte) 0xFF, 0x10, (byte) 0x80, 0x7F};

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: no initialisation script is supplied, because this context owns every table it maps
     * and the production migration is what creates them. The type comes from
     * {@code org.testcontainers.postgresql} rather than the deprecated
     * {@code org.testcontainers.containers}, and carries no type argument because the replacement is not
     * generic.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The customer master, injected as the production repository interface. */
    @Autowired
    private CustomerRepository customers;

    /** A plain JDBC handle, for the catalog and constraint assertions no entity can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC URL,
     *     user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the Flyway pair is registered beside the datasource triple because
    //       application.yml L892 and L893 bind spring.flyway.user and spring.flyway.password to
    //       placeholders with no fallback, and Boot consults those keys precisely when no
    //       connection-details bean supplies them -- which is this module's case, since the artifact that
    //       would contribute one is deliberately absent from its POM. The package charter records the
    //       full reasoning.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the master and seeds a known ascending run before each case.
     *
     * <p>Assumptions: the table is emptied and re-seeded rather than each case being wrapped in a
     * rolled-back transaction, because two cases below assert that the DATABASE refused a write and a
     * refusal inside a rolled-back wrapper cannot be told apart from the rollback.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void resetAndSeed(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM account.customers");
        this.customers.saveAll(seededRows());
    }

    /**
     * Confirms the production migration applied, through Flyway's own history rather than by inference.
     *
     * <p>Assumptions: the history table is read instead of merely querying a table successfully. A query
     * succeeding proves a table exists; it does not distinguish a table Flyway created from one some other
     * mechanism created, and the distinction is the point -- this context's schema is supposed to arrive
     * from {@code db/migration} and from nowhere else.</p>
     *
     * <p>Assumptions: the ownership of the schema is asserted too. Every
     * {@code ALTER DEFAULT PRIVILEGES FOR ROLE} clause in
     * {@code data-migration/sql/V0__schemas_and_roles.sql} is keyed on the role that CREATES an object, so
     * a deployment in which Flyway created objects as the connecting user would grant the runtime role
     * nothing and fail at the first query with 42501. That is invisible to a passing query and the
     * container is the only place it can be observed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("Flyway applied V1__account.sql into a schema owned by the migration role")
    void flywayAppliedTheProductionAccountMigration() {
        Integer applied = this.jdbc.queryForObject(
                "SELECT count(*) FROM account.flyway_schema_history"
                        + " WHERE version = '1' AND success = true",
                Integer.class);
        assertThat(applied)
                .as("the one migration this context owns must be recorded as applied and successful")
                .isEqualTo(1);

        assertThat(this.jdbc.queryForObject(
                "SELECT pg_catalog.pg_get_userbyid(nspowner) FROM pg_catalog.pg_namespace"
                        + " WHERE nspname = 'account'",
                String.class))
                .as("the schema must belong to the NOLOGIN owner, or the default-privilege grants are"
                        + " inert")
                .isEqualTo("carddemo_account_owner");
    }

    /**
     * Confirms the customer table's declared widths, nullability and domain constraint are the real ones.
     *
     * <p>Assumptions: the fixed-width columns are asserted as {@code character} with their exact length
     * and the descriptive ones as {@code character varying}, because the difference is what decides
     * whether the engine pads. The projection refuses a stored postal code that is not exactly ten
     * characters, so this assertion is what keeps that refusal from being reachable through a schema
     * defect rather than through bad data.</p>
     *
     * <p>Assumptions: the optional government-issued identifier is asserted NULLABLE and the national
     * identifier NOT NULL, which is the asymmetry the copybook carries -- {@code CUST-SSN} is
     * {@code PIC 9(09)} at L17 of {@code app/cpy/CVCUS01Y.cpy} and is always present, while the
     * government-issued identifier at L18 is what the update screen can blank with the {@code '*'} marker
     * it tests at L1401 through L1403 of {@code app/cbl/COACTUPC.cbl}.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the customer table carries its declared widths, nullability and check constraint")
    void theCustomerTableCarriesItsDeclaredContract() {
        assertThat(typeOf("addr_zip")).isEqualTo("character");
        assertThat(lengthOf("addr_zip")).isEqualTo(POSTAL_CODE_WIDTH);
        assertThat(typeOf("eft_account_id")).isEqualTo("character");
        assertThat(lengthOf("eft_account_id")).isEqualTo(EFT_ACCOUNT_ID_WIDTH);
        assertThat(typeOf("first_name"))
                .as("a descriptive field's declared width is a maximum, so it is not padded")
                .isEqualTo("character varying");
        assertThat(lengthOf("first_name")).isEqualTo(25);

        assertThat(typeOf("ssn_encrypted"))
                .as("ciphertext in a character column would be transcoded on the way through")
                .isEqualTo("bytea");
        assertThat(typeOf("govt_issued_id_encrypted")).isEqualTo("bytea");
        assertThat(nullableOf("ssn_encrypted")).isEqualTo("NO");
        assertThat(nullableOf("govt_issued_id_encrypted"))
                .as("the optional identifier is the one the update screen can blank")
                .isEqualTo("YES");
        assertThat(typeOf("fico_credit_score")).isEqualTo("smallint");
        assertThat(typeOf("version"))
                .as("optimistic concurrency replaces the reference's manual before-image comparison")
                .isEqualTo("bigint");

        assertThat(this.jdbc.queryForObject(
                "SELECT pg_catalog.pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint"
                        + " WHERE conrelid = 'account.customers'::regclass"
                        + " AND conname = 'ck_customers_pri_card_holder_ind'",
                String.class))
                .as("the indicator domain is closed at the two values the reference admits")
                .contains("'Y'", "'N'");
    }

    /**
     * Confirms a stored postal code shorter than its declared width comes back padded to that width.
     *
     * <p>Assumptions: this is asserted through a value written by the REPOSITORY and read back after the
     * persistence context has been discarded, because a read served from the context would return the
     * unpadded string the case supplied and would show nothing about the column at all. The padding is
     * the engine's, and it is what makes the record-width projection possible.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a short postal code is stored padded out to its declared fixed width")
    void aShortPostalCodeIsPaddedToItsDeclaredWidth() {
        long shortCodeId = FIRST_CUSTOMER_ID + SEEDED_ROWS;
        this.customers.saveAll(List.of(customer(shortCodeId, "10001")));

        assertThat(this.jdbc.queryForObject(
                "SELECT addr_zip FROM account.customers WHERE customer_id = ?",
                String.class, shortCodeId))
                .as("the engine pads a CHAR column, which is why the projection can demand ten")
                .isEqualTo("10001     ")
                .hasSize(POSTAL_CODE_WIDTH);
    }

    /**
     * Confirms both protected identifiers survive the write byte for byte.
     *
     * <p>Assumptions: the bytes chosen are not valid text in any single-byte encoding, so the assertion
     * fails against a character column rather than passing by luck. Nothing here decrypts anything: what is
     * asserted is that the storage layer alters nothing, which is the only property this layer owes the
     * cipher.</p>
     *
     * <p>Assumptions: the values are read back through SQL rather than off the entity, and that is forced
     * rather than chosen -- {@code Customer} publishes NO accessor for either protected array. The absence
     * is the entity's own disclosure control and is worth naming here, because it is also the reason no
     * domain object in this context can leak either identifier: the constructor copies both arrays in and
     * nothing hands one back. Reading the column directly asserts what was stored, which is the stronger
     * statement in any case.</p>
     *
     * <p>Assumptions: the row is written by the REPOSITORY and read by a separate statement, so the
     * assertion cannot be served from the persistence context that wrote it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both protected identifiers round trip through BYTEA byte for byte")
    void theProtectedIdentifiersRoundTripUnaltered() {
        assertThat(this.customers.findById(FIRST_CUSTOMER_ID))
                .as("the seeded row is present, so the byte assertions below are about its columns")
                .isPresent();

        assertThat(this.jdbc.queryForObject(
                "SELECT ssn_encrypted FROM account.customers WHERE customer_id = ?",
                byte[].class, FIRST_CUSTOMER_ID))
                .isEqualTo(CIPHERTEXT);
        assertThat(this.jdbc.queryForObject(
                "SELECT govt_issued_id_encrypted FROM account.customers WHERE customer_id = ?",
                byte[].class, FIRST_CUSTOMER_ID))
                .isEqualTo(CIPHERTEXT);
    }

    /**
     * Confirms the opening window is ascending and stops at the width it was given.
     *
     * <p>Assumptions: the ORDER is asserted rather than assumed from the method name, because the order is
     * the one thing the reference fixes and the migration must not choose for itself: it follows from
     * {@code ACCESS MODE IS SEQUENTIAL} at L31 over {@code RECORD KEY IS FD-CUST-ID} at L32 of
     * {@code app/cbl/CBCUS01C.cbl}, so ascending identifier is the file's order and not a preference.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the opening window returns the lowest identifiers in ascending order")
    void theOpeningWindowIsAscendingAndBounded() {
        List<Customer> window = this.customers.findAllByOrderByCustomerIdAsc(Limit.of(WINDOW_ROWS));

        assertThat(identifiersOf(window)).containsExactly(FIRST_CUSTOMER_ID, FIRST_CUSTOMER_ID + 1,
                FIRST_CUSTOMER_ID + 2);
    }

    /**
     * Confirms the resuming window begins STRICTLY after its bound.
     *
     * <p>Assumptions: this is the single most consequential assertion in the class. The scan hands the
     * identifier of the last row it published as the bound of the next read, so a bound that included its
     * own value would return that row a second time -- once on each of two consecutive pages -- and every
     * individual page would still be correctly ordered, correctly sized and correctly bounded. The defect
     * is invisible to any assertion that does not compare across the boundary.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the resuming window excludes its own bound and continues ascending")
    void theResumingWindowIsStrictlyAfterItsBound() {
        long bound = FIRST_CUSTOMER_ID + 2;

        List<Customer> window = this.customers
                .findByCustomerIdGreaterThanOrderByCustomerIdAsc(bound, Limit.of(WINDOW_ROWS));

        assertThat(identifiersOf(window))
                .as("the bound itself must NOT reappear, or every page boundary repeats one row")
                .doesNotContain(bound);
        assertThat(identifiersOf(window)).containsExactly(bound + 1, bound + 2, bound + 3);
    }

    /**
     * Confirms the descending window walks back from its bound and excludes it.
     *
     * <p>Assumptions: this query is asserted although the customer scan never calls it, and the reason is
     * recorded rather than left implicit. It is declared on the repository because the envelope publishes
     * both boundaries and a future backward step would use it; a derived query that was never executed
     * would be a statement composed at startup and proven by nothing, and the first caller to reach it
     * would be the one to discover its bound was inclusive.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the descending window excludes its bound and returns the rows before it")
    void theDescendingWindowWalksBackFromItsBound() {
        long bound = FIRST_CUSTOMER_ID + 4;

        List<Customer> window = this.customers
                .findByCustomerIdLessThanOrderByCustomerIdDesc(bound, Limit.of(2));

        assertThat(identifiersOf(window))
                .as("descending, adjacent to the bound first, and the bound itself excluded")
                .containsExactly(bound - 1, bound - 2);
    }

    /**
     * Confirms consecutive windows concatenate into a walk that visits every row exactly once.
     *
     * <p>Assumptions: the walk is driven exactly as the scan drives it -- the opening query once, then the
     * resuming query bound to the last identifier received -- so what is asserted is the property the
     * migration actually promises: the concatenated pages reproduce the sequence the reference's own sweep
     * would have written at L78 of {@code app/cbl/CBCUS01C.cbl}, with no row skipped and none
     * repeated.</p>
     *
     * <p>Assumptions: the number of reads is asserted as well as the rows. Seven rows at a window of three
     * need three reads and a fourth that returns nothing, and asserting that count is what shows the walk
     * ended by EXHAUSTION rather than by a count the case supplied.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("consecutive windows concatenate into a walk over every row, once each")
    void aWalkOfConsecutiveWindowsVisitsEveryRowOnce() {
        List<Long> visited = new ArrayList<>();
        int reads = 0;
        Long bound = null;

        while (true) {
            List<Customer> window = bound == null
                    ? this.customers.findAllByOrderByCustomerIdAsc(Limit.of(WINDOW_ROWS))
                    : this.customers
                            .findByCustomerIdGreaterThanOrderByCustomerIdAsc(bound,
                                    Limit.of(WINDOW_ROWS));
            reads++;
            if (window.isEmpty()) {
                break;
            }
            visited.addAll(identifiersOf(window));
            bound = window.getLast().getCustomerId();
        }

        assertThat(visited).hasSize(SEEDED_ROWS);
        assertThat(visited).doesNotHaveDuplicates();
        assertThat(visited).isSorted();
        assertThat(reads)
                .as("three windows of three over seven rows, then one empty read that ends the walk")
                .isEqualTo(4);
    }

    /**
     * Confirms a second row for one customer identifier is refused by the declared primary key.
     *
     * <p>Assumptions: the duplicate is written through plain SQL and not through the repository, and the
     * difference is required rather than stylistic. Saving an entity whose assigned identifier already
     * exists issues a MERGE rather than an insert, so it would silently UPDATE the first row and the case
     * would assert nothing -- the key is enforced by the engine, so the engine is what has to be asked.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a duplicate customer identifier is refused by the primary key")
    void aDuplicateCustomerIdentifierIsRefused() {
        assertThatThrownBy(() -> insertRow(FIRST_CUSTOMER_ID, "Y"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_customers");

        assertThat(this.jdbc.queryForObject("SELECT count(*) FROM account.customers", Integer.class))
                .as("the refused write left the seeded master exactly as it was")
                .isEqualTo(SEEDED_ROWS);
    }

    /**
     * Confirms an indicator outside the reference's two values is refused by the check constraint.
     *
     * <p>Assumptions: the value is written through plain SQL because the entity's own setter refuses it
     * before the database sees it, and both guards are wanted: the entity refuses what an application path
     * could supply and the constraint refuses what the ETL or an operator could. A check constraint never
     * exercised is indistinguishable from one that was mistyped, because PostgreSQL accepts a predicate
     * that can never be false as readily as one that can.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a primary-card-holder indicator outside Y and N is refused")
    void anUndeclaredCardHolderIndicatorIsRefused() {
        assertThatThrownBy(() -> insertRow(FIRST_CUSTOMER_ID + SEEDED_ROWS, "X"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_customers_pri_card_holder_ind");
    }

    /**
     * Inserts one customer row through plain SQL, bypassing the entity's own guards.
     *
     * @param customerId the stored key of the row
     * @param indicator the primary-card-holder indicator, which one caller sets outside its domain
     *     deliberately
     * @throws DataIntegrityViolationException if the engine refuses the row, which both callers provoke
     */
    private void insertRow(long customerId, String indicator) {
        this.jdbc.update("INSERT INTO account.customers (customer_id, first_name, last_name,"
                        + " addr_line_1, addr_line_3, addr_state_cd, addr_country_cd, addr_zip,"
                        + " ssn_encrypted, dob, eft_account_id, pri_card_holder_ind, fico_credit_score)"
                        + " VALUES (?, 'FIRST', 'LAST', '1 FIXTURE WAY', 'FIXTURE CITY', 'NY', 'USA',"
                        + " '10001-0000', ?, DATE '1980-01-15', '0000000001', ?, 789)",
                customerId, CIPHERTEXT, indicator);
    }

    /**
     * Reads one column's declared data type from the catalog.
     *
     * @param column the column name; must not be {@code null}
     * @return the type the catalog reports, never {@code null}
     */
    private String typeOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = 'account'"
                        + " AND table_name = 'customers' AND column_name = ?",
                String.class, column);
    }

    /**
     * Reads one character column's declared width from the catalog.
     *
     * @param column the column name; must not be {@code null}
     * @return the declared width, never {@code null} for a character column
     */
    private Integer lengthOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns"
                        + " WHERE table_schema = 'account' AND table_name = 'customers'"
                        + " AND column_name = ?",
                Integer.class, column);
    }

    /**
     * Reads whether one column admits a null, as the catalog reports it.
     *
     * @param column the column name; must not be {@code null}
     * @return {@code "YES"} or {@code "NO"}, never {@code null}
     */
    private String nullableOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'account'"
                        + " AND table_name = 'customers' AND column_name = ?",
                String.class, column);
    }

    /**
     * Extracts the stored identifiers of a window, in the order the window returned them.
     *
     * @param window the rows a query returned; must not be {@code null}
     * @return the identifiers in the same order, never {@code null}
     */
    private static List<Long> identifiersOf(List<Customer> window) {
        List<Long> identifiers = new ArrayList<>(window.size());
        for (Customer row : window) {
            identifiers.add(row.getCustomerId());
        }
        return identifiers;
    }

    /**
     * Builds the ascending run of rows every case starts from.
     *
     * @return the seeded rows in ascending identifier order, never {@code null}
     */
    private static List<Customer> seededRows() {
        List<Customer> rows = new ArrayList<>(SEEDED_ROWS);
        for (int offset = 0; offset < SEEDED_ROWS; offset++) {
            rows.add(customer(FIRST_CUSTOMER_ID + offset, "10001-0000"));
        }
        return rows;
    }

    /**
     * Builds one stored customer row whose every value is a fixture constant.
     *
     * <p>Assumptions: no value here identifies a real person, and the two protected columns hold the same
     * fixed bytes rather than the ciphertext of anything -- what a case asserts about them is that the
     * bytes survive, not what they mean.</p>
     *
     * @param customerId the stored key of the row
     * @param postalCode the postal code the row carries, supplied so one case can store a value narrower
     *     than the declared width; must not be {@code null}
     * @return a transient customer row, never {@code null}
     */
    private static Customer customer(long customerId, String postalCode) {
        return new Customer(customerId, "FIRST", "M", "LAST", "1 FIXTURE WAY", null, "FIXTURE CITY",
                "NY", "USA", postalCode, "(212)555-0100  ", null, CIPHERTEXT, CIPHERTEXT,
                LocalDate.of(1980, 1, 15), "0000000001", "Y", (short) 789);
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the queue listener, the identity client and the
     * cipher this module's own application class would register stay out of the context. Naming the two
     * persistence packages leaves the framework's own auto-configuration to build the pool from the
     * properties the container registered and to run Flyway ahead of it.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    static class CustomerPersistenceTestApplication {
    }
}
