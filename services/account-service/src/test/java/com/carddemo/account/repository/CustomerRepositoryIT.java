package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.config.DataSourceConfig;
import com.carddemo.account.domain.Customer;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.OptimisticLockingFailureException;
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
 * Holds {@link CustomerRepository} and the {@code account.customers} table against the copybook record
 * they both derive from, using a real PostgreSQL engine rather than a substitute for one.
 *
 * <p>The nine package-level rulings recorded in {@code package-info.java} of this package govern every
 * member below and are cited rather than restated here. This class occupies the copybook-contract seat in
 * that inventory: where the sibling {@code CustomerMasterRepositoryIT} holds the migration history, the
 * catalog geometry and the window queries, this class holds the record layout, the fixture bytes, the
 * keyed read, the version column and the composition of the three window queries into one envelope.</p>
 *
 * <p>Assumptions: the normative record is {@code app/cpy/CVCUS01Y.cpy}, whose {@code 01 CUSTOMER-RECORD.}
 * at L4 carries eighteen named fields at L5 through L22 and {@code FILLER PIC X(168)} at L23. The
 * ownership proof is {@code app/cbl/CBCUS01C.cbl} L45, whose {@code COPY CVCUS01Y.} is that program's
 * only copy statement. A near-clone named {@code app/cpy/CUSTREC.cpy} also exists and also declares 500
 * bytes in its header, differing substantively only in that its L19 field is spelled without the two
 * inner hyphens; it is named here once, and only so that a later reader is warned away from it, because
 * every width and every field order is otherwise identical and so a record decoded against the wrong one
 * still sums to 500 and fails silently. It is copied by one program repository-wide and that program
 * belongs to the reporting context, not to this one.
 *
 * <p>Assumptions: no functional-parity oracle covers this context, so every assertion below is authored
 * from the copybook contracts and program paragraphs directly rather than from a recorded run. Two
 * independent statements in {@code tests/README.md} establish that: L83 through L85 record that the
 * online CICS programs cannot be run end to end without a CICS runtime, and the business rules from L553
 * onward name none of this context's programs or files. The COBOL three-layer suite rooted at
 * {@code tests/} is a separate tree that this class neither displaces nor depends on, and its graded
 * condition-code convention belongs to it alone: the gate over this class is pass or fail.</p>
 *
 * <p>Trade-offs: every one of the eight file resources in {@code app/csd/CARDDEMO.CSD} is defined to read
 * without regard to uncommitted change, the customer master among them at L53, whose operand list carries
 * {@code READINTEG(UNCOMMITTED)} beside its pool number. The engine these cases run against reads
 * committed rows only, which is strictly the stronger of the two guarantees, so no case below asserts
 * that an uncommitted row was visible. What is given up is that one reference behaviour is not reproduced;
 * what is bought is that no case can pass by observing a row that a concurrent writer may still abandon.
 * The compromise runs in the direction that costs nothing, which is why it is accepted rather than
 * worked around.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Assumptions: both remote configuration sources are switched off for this context because it
//       cannot start with either one loaded. The base document imports a parameter-store location and a
//       secrets-manager location, and LOADING either builds an AWS client while configuration is still in
//       progress, from a region placeholder that a profile-specific document resolves too late to help.
//       The test profile already disables both; they are repeated here so that this class states its own
//       precondition rather than inheriting an invisible one, which is the same shape the two sibling
//       persistence classes in this package use.
// WHY : Alternatives Considered: pointing the context at a configuration document that does not exist, so
//       that no import is reached at all. Rejected for this class specifically: the deployed document is
//       what supplies the datasource pool sizing, the schema binding and the migration configuration, and
//       all three are part of what these cases hold. Removing the real document would leave the class
//       asserting a configuration no deployment uses.
@SpringBootTest(
        classes = CustomerRepositoryIT.CustomerRecordPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.aws.parameterstore.enabled=false",
                "spring.cloud.aws.secretsmanager.enabled=false"})
@ActiveProfiles("test")
class CustomerRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two classes cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises, and the two are changed
     * together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The classpath name of the fixture record, which is part of this class's contract. */
    private static final String FIXTURE_RESOURCE = "/fixtures/customer/customer-valid.txt";

    /** The name under which the customer record layout is registered in the shared layout table. */
    private static final String CUSTOMER_LAYOUT_NAME = "CUSTOMER";

    /** The record length the copybook header declares, and the fixture's exact size in bytes. */
    private static final int CUSTOMER_RECORD_LENGTH = 500;

    /** Eighteen named fields plus the trailing filler the copybook declares at L23. */
    private static final int CUSTOMER_FIELD_COUNT = 19;

    /**
     * The columns the table carries: the eighteen copybook fields with the filler dropped, plus one.
     *
     * <p>Assumptions: this equals the field count above by arithmetic coincidence rather than by
     * correspondence, and the two are kept as separate constants so that a reader does not take one for
     * the other. The filler is padding to a fixed record length and is not stored, which removes one; the
     * version column has no copybook counterpart at all, which adds one back.</p>
     */
    private static final int CUSTOMER_COLUMN_COUNT = 19;

    /** The retrieval key width, which is the nine digits the identifier occupies at offset zero. */
    private static final int CUSTOMER_KEY_LENGTH = 9;

    /** The offset at which the date of birth begins, following the twenty-byte government identifier. */
    private static final int DATE_OF_BIRTH_OFFSET = 308;

    /** The width of the trailing filler, which pads the 332-byte data region out to the record length. */
    private static final int FILLER_WIDTH = 168;

    /** The declared width of each of the three address lines. */
    private static final int ADDRESS_LINE_WIDTH = 50;

    /** The declared width of the postal code, which shares the width of the three date-bearing fields. */
    private static final int POSTAL_CODE_WIDTH = 10;

    /** The identifier the fixture record carries, read from its first nine bytes. */
    private static final long FIXTURE_CUSTOMER_ID = 11L;

    /** The city the fixture carries in its third address line. */
    private static final String FIXTURE_CITY = "New Aricchester";

    /** The postal code the fixture carries, which is a character value and not a date. */
    private static final String FIXTURE_POSTAL_CODE = "04257";

    /** The date of birth the fixture carries, in the ISO order the copybook stores it in. */
    private static final LocalDate FIXTURE_DATE_OF_BIRTH = LocalDate.of(1960, 12, 1);

    /** The credit score the fixture carries, which drives the narrowing assertion. */
    private static final short FIXTURE_CREDIT_SCORE = (short) 623;

    /**
     * The identifier of the first row the seeded ascending run holds.
     *
     * <p>Assumptions: inside the nine-digit range {@code CUST-ID PIC 9(09)} declares at L5 of
     * {@code app/cpy/CVCUS01Y.cpy}, and deliberately far above the fixture's own identifier so that a
     * case which loads the fixture and a case which walks the run cannot be confused with one another.
     * The value is fabricated and identifies nobody.</p>
     */
    private static final long FIRST_SEEDED_ID = 910000001L;

    /** The number of rows every case seeds before it runs. */
    private static final int SEEDED_ROWS = 5;

    /** The rows one window publishes, deliberately smaller than the seeded run. */
    private static final int WINDOW_ROWS = 2;

    /** An identifier no case seeds, used to hold the absent-key answer. */
    private static final long ABSENT_CUSTOMER_ID = 999999999L;

    /**
     * The ciphertext both protected columns hold.
     *
     * <p>Assumptions: bytes that are not valid text in any single-byte encoding, deliberately. A value
     * that happened to be printable would survive a character column too, so the round trip would pass
     * against exactly the column-type defect the protected-identifier case exists to rule out.</p>
     */
    private static final byte[] CIPHERTEXT = {0x00, (byte) 0xFF, 0x10, (byte) 0x80, 0x7F};

    /**
     * Key material for the cursor sealer this class builds, which is fabricated and opens nothing.
     *
     * <p>Assumptions: a sealed cursor is required because {@link PageResponse} refuses a boundary token
     * that does not carry the sealed shape, so a case that asserts the envelope has to mint real tokens.
     * The phrase below is a fabricated constant chosen to be self-evidently not a credential; it is
     * longer than the minimum key length the sealer enforces, it is never written anywhere, and a token
     * minted from it is accepted only by the instance this class constructs.</p>
     */
    private static final byte[] FABRICATED_CURSOR_KEY =
            "carddemo-account-repository-integration-test-cursor-key-not-a-secret"
                    .getBytes(StandardCharsets.US_ASCII);

    /** The sealer that mints and opens the boundary tokens the envelope assertions below carry. */
    private static final CursorToken CURSOR_SEALER =
            new CursorToken(FABRICATED_CURSOR_KEY, Duration.ofMinutes(5));

    /** The binding every token in this class is issued and opened under, held identical on both sides. */
    private static final String CURSOR_BINDING = CursorToken.binding(
            "customers.byCustomerId", "account-repository-integration-test", CursorToken.SCOPE_NONE);

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: no initialisation script is supplied, because this context owns every table it maps
     * and the production migration is what creates them. The type comes from
     * {@code org.testcontainers.postgresql} rather than the superseded {@code org.testcontainers.containers},
     * and carries no type argument because the replacement is not generic.</p>
     */
    // WHY : Alternatives Considered: an in-memory engine, or an embedded PostgreSQL substitute, in place
    //       of a container. Both were rejected, and for this class the decisive item is the pair of
    //       protected columns: they are declared BYTEA, and an in-memory engine's binary type does not
    //       reproduce PostgreSQL's, so the round trip that proves those bytes survive unaltered would be
    //       proving a different engine's behaviour. Three further properties every case here leans on are
    //       equally unavailable from a substitute -- fixed-width CHAR padding to a declared width, a
    //       session search path that resolves an unqualified table name, and the planner behaviour the
    //       keyed windows are ordered for. A substitute would let this class pass while the deployed
    //       engine failed, which is the one outcome an integration test must not permit.
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The customer master, injected as the production repository interface. */
    @Autowired
    private CustomerRepository customers;

    /** A plain JDBC handle, for the catalog and stored-byte assertions no entity can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC URL,
     *     user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the registration mechanism is a dynamic property source and not a service
    //       connection, because the artifact that would contribute a connection-details bean is
    //       deliberately absent from this module's POM, which declares the three Testcontainers modules
    //       and no Spring Boot Testcontainers integration. The migration credential pair is registered
    //       beside the datasource triple for the same reason: the base document binds those two keys to
    //       placeholders with no fallback, and the framework consults them precisely when no
    //       connection-details bean supplies them. Ruling three of the package descriptor records the
    //       full reasoning, and this comment cites it rather than repeating it.
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
     * schemas, owner roles, runtime roles and grants across the migration, and a throwaway container has
     * never run it. That bootstrap is a precondition of the service starting rather than a part of its
     * migration, which is why {@code V1__account.sql} issues no schema creation, no role creation and no
     * grant, and why the base profile forbids the migration runner from creating a schema. Supplying the
     * precondition here is what lets the migration run against the ownership a deployment gives it. If
     * the schema is absent when the runner opens its connection the context does not start, and that
     * arrives at run time with nothing from the compiler, which makes it the single most likely wiring
     * failure in this package.</p>
     *
     * <p>Assumptions: this runs before the Spring context is created, because the test framework invokes
     * a class-level setup method after the container extension has started the static container and
     * before the Spring extension builds the context. Plain JDBC is used rather than an injected pool for
     * that reason, since no bean exists yet.</p>
     *
     * <p>This setup step takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the container refuses the connection or any of the three statements, which
     *     is a broken harness rather than a failed assertion and is reported as such
     */
    @BeforeAll
    static void createSchemaAndOwnerBeforeFlywayRuns() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE carddemo_account_owner NOLOGIN");
            // WHY : Assumptions: the create privilege on the database is required and is not implied by
            //       role creation -- a fresh role holds only the public grants, so the schema creation
            //       below would fail once the role is assumed. The database name is interpolated because
            //       the container generates it, and it is quoted as an identifier because no generated
            //       name is guaranteed to be a bare lower-case word.
            statement.execute("GRANT CREATE ON DATABASE \"" + POSTGRES.getDatabaseName()
                    + "\" TO carddemo_account_owner");
            statement.execute("CREATE SCHEMA " + DataSourceConfig.SCHEMA_NAME
                    + " AUTHORIZATION carddemo_account_owner");
        }
    }

    /**
     * Empties the master and seeds a known ascending run before each case.
     *
     * <p>Assumptions: the table is emptied and re-seeded rather than each case being wrapped in a
     * rolled-back transaction, because two cases below assert that the engine refused a write, and a
     * refusal inside a rolled-back wrapper cannot be told apart from the rollback itself. The fixture row
     * is deliberately not seeded here; the three cases that need it load it themselves, so that a case
     * walking the seeded run never sees a row with a far lower identifier at the head of its first
     * window.</p>
     *
     * @param dataSource the pool the context built from the coordinates the container registered; must
     *     not be {@code null}
     */
    @BeforeEach
    void resetAndSeed(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM account.customers");
        this.customers.saveAll(seededRows());
    }

    /**
     * Confirms a keyed read returns exactly the row its key names.
     *
     * <p>Assumptions: the inherited finder replaces the keyed read at paragraph
     * {@code 9400-GETCUSTDATA-BYCUST.} on L825 of {@code app/cbl/COACTVWC.cbl}, whose verbs run from L826
     * through L831 -- a read against the customer file, the identifier as the record identification
     * field, that field's own length as the key length, and the record and its length as the destination.
     * The whole of that contract is carried by the inherited finder, which is why the repository declares
     * nothing for it and why this case exercises the inherited member rather than a derived one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a keyed read returns the row that key names")
    void theKeyedReadReturnsTheRowItsKeyNames() {
        Optional<Customer> found = this.customers.findById(FIRST_SEEDED_ID);

        assertThat(found).isPresent();
        assertThat(found.get().getCustomerId()).isEqualTo(FIRST_SEEDED_ID);
        assertThat(found.get().getLastName()).isEqualTo("SEEDED");
    }

    /**
     * Confirms an absent key yields an empty result rather than raising.
     *
     * <p>Assumptions: absence is an ordinary outcome of a keyed read and not a failure, so it is carried
     * as an empty result. The reference distinguishes the two the same way, by a response code the calling
     * paragraph inspects rather than by an abend, so a finder that threw would introduce a control flow
     * the reference does not have.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent key yields an empty result rather than raising")
    void anAbsentKeyYieldsAnEmptyResult() {
        Optional<Customer> found = this.customers.findById(ABSENT_CUSTOMER_ID);

        assertThat(found).isEmpty();
    }

    /**
     * Confirms the registered layout transcribes the normative copybook field for field.
     *
     * <p>Assumptions: the layout registered under its record name is the transcription of
     * {@code app/cpy/CVCUS01Y.cpy} and of no other document, and the field spelled with two inner hyphens
     * at L19 of that copybook is what proves it. The near-clone warned about on this class declares the
     * same widths in the same order and the same 500-byte total, so a geometry assertion alone cannot tell
     * the two apart; the field name can, and it is the only thing that can. The final assertion therefore
     * holds that the unhyphenated spelling is absent from the layout, which fails loudly if the
     * transcription is ever re-sourced from the clone.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the registered layout transcribes CVCUS01Y.cpy and not its near-clone")
    void theRegisteredLayoutTranscribesTheNormativeCopybook() {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(CUSTOMER_LAYOUT_NAME);

        assertThat(spec.reclen()).isEqualTo(CUSTOMER_RECORD_LENGTH);
        assertThat(spec.keyOffset()).isZero();
        assertThat(spec.keyLength()).isEqualTo(CUSTOMER_KEY_LENGTH);
        assertThat(spec.fields().stream().map(CopybookLayout.FieldSpec::name).toList())
                .as("the eighteen named fields of CVCUS01Y.cpy L5-L22 followed by its L23 filler")
                .containsExactly("CUST-ID", "CUST-FIRST-NAME", "CUST-MIDDLE-NAME", "CUST-LAST-NAME",
                        "CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-ADDR-LINE-3", "CUST-ADDR-STATE-CD",
                        "CUST-ADDR-COUNTRY-CD", "CUST-ADDR-ZIP", "CUST-PHONE-NUM-1", "CUST-PHONE-NUM-2",
                        "CUST-SSN", "CUST-GOVT-ISSUED-ID", "CUST-DOB-YYYY-MM-DD", "CUST-EFT-ACCOUNT-ID",
                        "CUST-PRI-CARD-HOLDER-IND", "CUST-FICO-CREDIT-SCORE", "FILLER");
        assertThat(spec.field("CUST-DOB-YYYY-MM-DD").start()).isEqualTo(DATE_OF_BIRTH_OFFSET);
        assertThat(spec.field("FILLER").length()).isEqualTo(FILLER_WIDTH);
        assertThatThrownBy(() -> spec.field("CUST-DOB-YYYYMMDD"))
                .as("the unhyphenated spelling belongs to the clone and must not resolve here")
                .isInstanceOf(CopybookLayout.LayoutException.class);
    }

    /**
     * Confirms the fixture decodes through the registered layout and carries no monetary field at all.
     *
     * <p>Assumptions: the record is exactly 500 bytes because {@code app/cpy/CVCUS01Y.cpy} L23 pads its
     * 332-byte data region out to that length, and the fixture is resolved by the classpath name declared
     * on this class because the test profile names no fixture location, which makes the name part of the
     * contract. This case also discharges the documentation obligation the fixture's own bytes carry: a
     * flat file cannot hold a docstring, so the reasoning belongs in the Javadoc of whatever reads it, and
     * this is that reader.</p>
     *
     * <p>Assumptions: the absence of a monetary field is itself the contract here, and is asserted rather
     * than assumed. The customer record declares no signed field with an implied decimal anywhere in its
     * eighteen fields, so no sign overpunch can occur in it and the zoned-decimal codec has no work to do
     * -- which is why the layout is held to carry neither a zoned nor a packed field and why every byte of
     * the fixture is held to be an ordinary printable character. The contrast is with
     * {@code fixtures/account/account-negative-balance.txt}, the one negative-money vector in this module,
     * which exists because L273 and L274 of {@code tests/README.md} record that the EBCDIC sign setting is
     * required since the default one misreads the zoned-decimal sign overpunch and silently corrupts
     * negative balances. That hazard cannot arise in this record, and stating why is what makes its
     * absence auditable rather than merely untested.</p>
     *
     * <p>Assumptions: the layout declares nineteen fields while the decoded record carries eighteen, and
     * the difference is the filler rather than an omission. Blank trailing padding is dropped on the way
     * out because it pads a record to a fixed length and is not content, which is also why the table
     * carries no column for it; a filler that were non-blank would be retained, so the drop is a decision
     * about this record's actual bytes and not a rule about the field's name.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath, which is a broken harness
     *     rather than a failed assertion and is reported as such
     */
    @Test
    @DisplayName("the fixture decodes through the registered layout and holds no monetary field")
    void theFixtureDecodesThroughTheRegisteredLayoutAndHoldsNoMonetaryField() throws IOException {
        byte[] record = fixtureRecord();
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(CUSTOMER_LAYOUT_NAME);

        assertThat(record).hasSize(CUSTOMER_RECORD_LENGTH);
        assertThat(spec.fields())
                .as("no signed display or packed field exists in this record, so no money path does")
                .allSatisfy(field -> assertThat(field.kind())
                        .isNotIn(CopybookLayout.Kind.ZONED, CopybookLayout.Kind.PACKED));
        for (int offset = 0; offset < record.length; offset++) {
            assertThat(record[offset])
                    .as("byte at offset %d must be printable, so no sign overpunch can hide there", offset)
                    .isBetween((byte) 0x20, (byte) 0x7E);
        }

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, spec);

        assertThat(decoded.get("CUST-ID")).isEqualTo(FIXTURE_CUSTOMER_ID);
        assertThat(decoded.get("CUST-SSN")).isEqualTo(931248469L);
        assertThat(decoded.get("CUST-GOVT-ISSUED-ID")).isEqualTo("00000000000030387824");
        assertThat(decoded.get("CUST-FICO-CREDIT-SCORE")).isEqualTo((long) FIXTURE_CREDIT_SCORE);
        assertThat(decoded)
                .as("the blank trailing filler is padding to a fixed length and is not carried as data")
                .doesNotContainKey("FILLER")
                .hasSize(CUSTOMER_FIELD_COUNT - 1);
    }

    /**
     * Confirms the fixture row loads into the master carrying its city in the third address line.
     *
     * <p>Assumptions: the record declares no city field. {@code app/cpy/CVCUS01Y.cpy} has exactly three
     * address lines, at L9, L10 and L11, and the third is where the city is carried -- the fixture's own
     * third line holds one, and the sibling persistence class in this package seeds one there too. The
     * assertion below therefore holds the third line at its full declared width in the record and holds
     * its stored form to be the city itself, so that a later reader does not normalise the value into a
     * dedicated column the copybook never had.</p>
     *
     * <p>Assumptions: the stored value carries no trailing padding while the record field carries 50
     * bytes of it, and the two are asserted separately because the difference is deliberate. A
     * descriptive character field's trailing blanks are padding to a fixed record length rather than data,
     * so the column is declared as a varying-length type and the blanks are dropped on the way in; a fixed
     * code keeps its width because the width is part of the code.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("the fixture row loads with its city in the third address line")
    void theFixtureRowLoadsWithItsCityInTheThirdAddressLine() throws IOException {
        this.customers.saveAndFlush(customerFromFixture());

        Customer stored = this.customers.findById(FIXTURE_CUSTOMER_ID).orElseThrow();

        assertThat(stored.getAddressLine3()).isEqualTo(FIXTURE_CITY);
        assertThat((String) decodedFixture().get("CUST-ADDR-LINE-3"))
                .as("the record field is 50 bytes wide whatever the stored value's length")
                .hasSize(ADDRESS_LINE_WIDTH)
                .startsWith(FIXTURE_CITY);
        assertThat(typeOf("addr_line_3")).isEqualTo("character varying");
        assertThat(lengthOf("addr_line_3")).isEqualTo(ADDRESS_LINE_WIDTH);
    }

    /**
     * Confirms both protected identifiers are stored as bytes and are never rendered in clear text.
     *
     * <p>Assumptions: the entity holds already-encrypted bytes and performs no cryptography of its own,
     * so this case performs none either. The two columns are declared as a binary type and what may be
     * asserted about them is that the bytes they hold survive a round trip unaltered, which a binary
     * column does and a character column does not -- the ciphertext constant on this class is deliberately
     * not valid text in any single-byte encoding for exactly that reason. Nothing here reaches a key
     * provider, and neither identifier is compared against a readable value, because the layer boundary
     * that keeps encryption out of the domain keeps it out of a test of the domain as well.</p>
     *
     * <p>Assumptions: the two values the fixture carries in those positions are synthetic baseline
     * content that identifies nobody, and they are treated here as opaque bytes. They appear in this case
     * only as strings that must be absent from the rendered form, which is the one use that does not
     * depend on their meaning.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("both protected identifiers round-trip as bytes and never render in clear text")
    void theProtectedIdentifiersRoundTripAsBytesAndNeverRenderInClearText() throws IOException {
        this.customers.saveAndFlush(customerFromFixture());

        assertThat(typeOf("ssn_encrypted")).isEqualTo("bytea");
        assertThat(typeOf("govt_issued_id_encrypted")).isEqualTo("bytea");
        assertThat(storedBytes("ssn_encrypted")).isEqualTo(CIPHERTEXT);
        assertThat(storedBytes("govt_issued_id_encrypted")).isEqualTo(CIPHERTEXT);

        String rendered = this.customers.findById(FIXTURE_CUSTOMER_ID).orElseThrow().toString();
        Map<String, Object> decoded = decodedFixture();

        assertThat(rendered).contains("[REDACTED]");
        assertThat(rendered)
                .as("neither protected identifier may reach a log line or a diagnostic string")
                .doesNotContain(String.valueOf(decoded.get("CUST-SSN")))
                .doesNotContain((String) decoded.get("CUST-GOVT-ISSUED-ID"));
    }

    /**
     * Confirms the table declares no card-verification-value column at all.
     *
     * <p>Assumptions: the absence is asserted rather than any masking of such a value, because
     * {@code app/cpy/CVCUS01Y.cpy} declares no such field in any of its eighteen positions. A masking
     * assertion would imply a column exists and is merely hidden, which would mislead a later reader into
     * looking for one; holding the column list to its exact membership states the truth instead.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the table declares no card-verification-value column")
    void theTableDeclaresNoCardVerificationValueColumn() {
        List<String> columns = this.jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = 'customers'",
                String.class, DataSourceConfig.SCHEMA_NAME);

        assertThat(columns).hasSize(CUSTOMER_COLUMN_COUNT);
        assertThat(columns)
                .as("no verification value is carried by this record, so none may be carried by its table")
                .noneMatch(name -> name.contains("cvv") || name.contains("card_verification"));
    }

    /**
     * Confirms the date of birth narrows to a date and keeps lexical order equivalent to date order.
     *
     * <p>Assumptions: the stored character form is already ordered most-significant-component first, so
     * narrowing the ten-byte field to a date type changes the type without reordering anything. That
     * equivalence is asserted in both directions against a value on each side of the fixture's own date,
     * because it is the property that lets the reference's character comparisons and this schema's date
     * comparisons agree on every input rather than on the ones a test happened to choose.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("the date of birth narrows to a date and preserves ordering")
    void theDateOfBirthNarrowsToADateAndPreservesOrdering() throws IOException {
        this.customers.saveAndFlush(customerFromFixture());

        assertThat(typeOf("dob")).isEqualTo("date");
        assertThat(this.customers.findById(FIXTURE_CUSTOMER_ID).orElseThrow().getDateOfBirth())
                .isEqualTo(FIXTURE_DATE_OF_BIRTH);

        String storedForm = (String) decodedFixture().get("CUST-DOB-YYYY-MM-DD");

        assertThat(storedForm).isEqualTo(FIXTURE_DATE_OF_BIRTH.toString());
        assertThat("1959-12-31".compareTo(storedForm) < 0)
                .as("a lexically earlier form must also be an earlier date")
                .isEqualTo(LocalDate.of(1959, 12, 31).isBefore(FIXTURE_DATE_OF_BIRTH));
        assertThat("1961-01-01".compareTo(storedForm) > 0)
                .as("a lexically later form must also be a later date")
                .isEqualTo(LocalDate.of(1961, 1, 1).isAfter(FIXTURE_DATE_OF_BIRTH));
    }

    /**
     * Confirms the postal code stays a character value even though it shares the width of the dates.
     *
     * <p>Assumptions: width alone does not identify a date, and this field is the reason that matters
     * here. The postal code is declared ten bytes wide at L14 of {@code app/cpy/CVCUS01Y.cpy}, exactly as
     * the date of birth is at L19, so a conversion rule driven by declared width would narrow both and
     * would corrupt this one. The case holds the column to a character type and the decoded value to a
     * form that carries no date separator, so that the two same-width fields cannot be conflated later.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("the postal code stays a character value despite sharing the date width")
    void thePostalCodeStaysACharacterValueDespiteSharingTheDateWidth() throws IOException {
        assertThat(typeOf("addr_zip")).isEqualTo("character");
        assertThat(lengthOf("addr_zip")).isEqualTo(POSTAL_CODE_WIDTH);
        assertThat(typeOf("dob")).as("the same-width neighbour did narrow, which is the contrast")
                .isEqualTo("date");

        String postalCode = (String) decodedFixture().get("CUST-ADDR-ZIP");

        assertThat(postalCode).hasSize(POSTAL_CODE_WIDTH);
        assertThat(postalCode.strip()).isEqualTo(FIXTURE_POSTAL_CODE).doesNotContain("-");
    }

    /**
     * Confirms the credit score narrows to a small integer and the fixture's value round-trips.
     *
     * <p>Assumptions: three decimal digits are bounded well inside a two-byte signed integer, so the
     * narrowing is total rather than lossy and the entity may hold the narrower primitive directly. The
     * decoded record carries the value as the wider integral type every unsigned display field decodes
     * to, and the assertion crosses that boundary explicitly so the narrowing is visible at the one place
     * it happens.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath
     */
    @Test
    @DisplayName("the credit score narrows to a small integer and round-trips")
    void theCreditScoreNarrowsToASmallIntegerAndRoundTrips() throws IOException {
        this.customers.saveAndFlush(customerFromFixture());

        assertThat(typeOf("fico_credit_score")).isEqualTo("smallint");
        assertThat(this.customers.findById(FIXTURE_CUSTOMER_ID).orElseThrow().getFicoCreditScore())
                .isEqualTo(FIXTURE_CREDIT_SCORE);
        assertThat(decodedFixture().get("CUST-FICO-CREDIT-SCORE"))
                .as("the decoded record carries the wider integral type the narrowing starts from")
                .isEqualTo((long) FIXTURE_CREDIT_SCORE);
    }

    /**
     * Confirms the version column advances by one when the row is rewritten.
     *
     * <p>Assumptions: the version column replaces the manual snapshot the reference keeps across the
     * terminal turn. {@code app/cbl/COACTUPC.cbl} declares {@code 05 ACUP-OLD-DETAILS.} at L669 and it
     * runs to L756, its final member being the customer credit score held as characters at L754 with a
     * numeric redefinition at L755 and L756, because {@code 05 ACUP-NEW-DETAILS.} begins at L757. That the
     * snapshot's last member is a customer field is direct evidence that this record takes part in the
     * same concurrency window the account record does. The reference compares the two groups and carries a
     * changed-data flag declared at L168 with its two condition names at L169 and L170; this schema carries
     * a version column instead, and the divergence is documented rather than silent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the version column advances when the row is rewritten")
    void theVersionAdvancesWhenTheRowIsRewritten() {
        Customer loaded = this.customers.findById(FIRST_SEEDED_ID).orElseThrow();
        long versionBefore = loaded.getVersion();

        renameFirstNameOf(loaded, "REWRITTEN");
        this.customers.saveAndFlush(loaded);

        assertThat(this.customers.findById(FIRST_SEEDED_ID).orElseThrow().getVersion())
                .isEqualTo(versionBefore + 1);
    }

    /**
     * Confirms a rewrite carrying a stale version is refused once another writer has moved the row.
     *
     * <p>Assumptions: the second writer is applied with a plain statement rather than through the
     * repository, because the conflict has to arrive from outside the loaded row's own knowledge for the
     * version check to be the thing under test. The reference reaches the same state by comparing its
     * snapshot and, on a mismatch, setting a locked-but-failed state and issuing the rollback at L4100 of
     * {@code app/cbl/COACTUPC.cbl}, where its success path instead commits at L953.</p>
     *
     * <p>Assumptions: the assertion names the framework's optimistic-locking failure rather than the
     * persistence provider's own stale-state exception, because the repository boundary translates the
     * second into the first, and the first is the type the shared handler in {@code common-lib} keys its
     * answer on. No status code is asserted anywhere here: the outward form of the conflict is settled in
     * that handler, and restating it in a persistence test would put one contract in two places.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stale version is refused once another writer has moved the row")
    void aStaleVersionIsRefusedOnceAnotherWriterHasMovedTheRow() {
        Customer stale = this.customers.findById(FIRST_SEEDED_ID).orElseThrow();
        this.jdbc.update("UPDATE account.customers SET first_name = ?, version = version + 1"
                + " WHERE customer_id = ?", "ELSEWHERE", FIRST_SEEDED_ID);

        renameFirstNameOf(stale, "MINE");

        assertThatThrownBy(() -> this.customers.saveAndFlush(stale))
                .as("the rewrite carries the version it read, which the row no longer holds")
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    /**
     * Confirms the opening window publishes its rows in ascending key order and reports a continuation.
     *
     * <p>Assumptions: the boundary tokens name the first and last rows this window actually published,
     * never the further row read to settle whether another window follows. That is asserted by opening
     * both tokens and comparing them to the published rows' own keys, because a trailing token taken from
     * the further row would step past that row on the next window and every individual window would still
     * look correctly ordered, correctly sized and correctly bounded.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the opening window publishes ascending rows and reports a continuation")
    void theOpeningWindowPublishesAscendingRowsAndReportsAContinuation() {
        PageResponse<Customer> opening = forwardWindow(null, WINDOW_ROWS);

        assertThat(identifiersOf(opening.items()))
                .containsExactly(FIRST_SEEDED_ID, FIRST_SEEDED_ID + 1);
        assertThat(opening.hasNext()).isTrue();
        assertThat(openedKey(opening.firstKey())).isEqualTo(String.valueOf(FIRST_SEEDED_ID));
        assertThat(openedKey(opening.lastKey()))
                .as("the trailing boundary is the last PUBLISHED row, not the further row read past it")
                .isEqualTo(String.valueOf(FIRST_SEEDED_ID + 1));
    }

    /**
     * Confirms a completely full window at the end of the run still reports no continuation.
     *
     * <p>Assumptions: this is the sharpest case the further-row technique has to answer, and it is the
     * reason the technique is used at all. The window below publishes its full complement of rows and is
     * nevertheless the last one, so anything that inferred exhaustion from a short window would report a
     * continuation that does not exist and a caller would ask for a window that returns nothing. The
     * reference settles the same question the same way, by discovering one more record than a screen
     * holds rather than by consulting any total.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a full window at the end of the run reports no continuation")
    void aFullWindowAtTheEndOfTheRunReportsNoContinuation() {
        PageResponse<Customer> trailing = forwardWindow(FIRST_SEEDED_ID + 2, WINDOW_ROWS);

        assertThat(trailing.items()).hasSize(WINDOW_ROWS);
        assertThat(identifiersOf(trailing.items()))
                .containsExactly(FIRST_SEEDED_ID + 3, FIRST_SEEDED_ID + 4);
        assertThat(trailing.hasNext())
                .as("a window that is full and last must still report that nothing follows it")
                .isFalse();
    }

    /**
     * Confirms a backward window returns exactly the rows of the window that preceded its bound.
     *
     * <p>Assumptions: this is the property that makes the two directions one scan rather than two, and it
     * cannot be observed from either direction alone. A backward bound that included its own value, or a
     * backward read left in the order the engine returned it, would each produce a window that is
     * individually well formed and disagrees with the forward window it is supposed to retrace.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a backward window returns the rows of the window before its bound")
    void aBackwardWindowReturnsTheRowsOfTheWindowBeforeItsBound() {
        PageResponse<Customer> opening = forwardWindow(null, WINDOW_ROWS);
        PageResponse<Customer> second = forwardWindow(FIRST_SEEDED_ID + 1, WINDOW_ROWS);

        PageResponse<Customer> retraced = backwardWindow(FIRST_SEEDED_ID + 2, WINDOW_ROWS);

        assertThat(identifiersOf(second.items()))
                .containsExactly(FIRST_SEEDED_ID + 2, FIRST_SEEDED_ID + 3);
        assertThat(identifiersOf(retraced.items()))
                .as("retracing from the second window's leading bound must land on the opening window")
                .isEqualTo(identifiersOf(opening.items()));
    }

    /**
     * Confirms an unqualified table name resolves through the session search path this context pins.
     *
     * <p>Assumptions: every statement this context issues names its tables unqualified, and what makes
     * that resolve is the search path pinned on each pooled connection by the datasource configuration,
     * which holds the schema name as its single named constant. The query below is deliberately the only
     * unqualified one in this class; every other statement here names the schema explicitly so that this
     * case is the single place the pinning is under test and cannot pass by accident somewhere else.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unqualified table name resolves through the pinned session search path")
    void anUnqualifiedTableNameResolvesThroughThePinnedSearchPath() {
        Long counted = this.jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);

        assertThat(counted).isEqualTo(SEEDED_ROWS);
        assertThat(DataSourceConfig.SCHEMA_NAME).isEqualTo("account");
    }

    /**
     * Reads the fixture record from the classpath as raw bytes.
     *
     * <p>Assumptions: the stream is read as bytes and never as text, because a fixed-width record is a
     * byte layout and reading it through a character reader would let an encoding decision change field
     * offsets while leaving every width plausible.</p>
     *
     * @return the fixture's bytes, which the caller asserts to be exactly the declared record length
     * @throws IOException if the resource is absent from the classpath or cannot be read, either of which
     *     is a broken harness rather than a failed assertion
     */
    private byte[] fixtureRecord() throws IOException {
        try (InputStream stream = CustomerRepositoryIT.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("fixture is absent from the classpath at " + FIXTURE_RESOURCE);
            }
            return stream.readAllBytes();
        }
    }

    /**
     * Decodes the fixture record through the registered layout.
     *
     * <p>Assumptions: decoding is driven by the registered layout rather than by offsets written out here,
     * because a layout is single-sourced and a second copy of one is a second thing to get wrong. Text
     * fields come back at their full declared width, which is why callers that want the stored form strip
     * the padding explicitly.</p>
     *
     * @return the decoded field values, keyed by the copybook field names the layout declares
     * @throws IOException if the fixture cannot be read from the classpath
     */
    private Map<String, Object> decodedFixture() throws IOException {
        return FixedWidthCodec.decodeRecord(
                fixtureRecord(), CopybookLayout.layout(CUSTOMER_LAYOUT_NAME));
    }

    /**
     * Builds the entity the fixture record describes.
     *
     * <p>Assumptions: the two protected positions are replaced with this class's ciphertext constant
     * rather than carried across from the record, because the record holds those two values in clear
     * characters and the columns hold ciphertext. Converting between the two is the work of a component
     * that reaches a key provider, which this layer is not permitted to do, so the fixture's own bytes in
     * those two positions are used only where their meaning does not matter.</p>
     *
     * @return the entity, with descriptive text stripped of the padding the record pads it with
     * @throws IOException if the fixture cannot be read from the classpath
     */
    private Customer customerFromFixture() throws IOException {
        Map<String, Object> decoded = decodedFixture();
        return new Customer(
                (Long) decoded.get("CUST-ID"),
                strippedText(decoded, "CUST-FIRST-NAME"),
                strippedText(decoded, "CUST-MIDDLE-NAME"),
                strippedText(decoded, "CUST-LAST-NAME"),
                strippedText(decoded, "CUST-ADDR-LINE-1"),
                strippedText(decoded, "CUST-ADDR-LINE-2"),
                strippedText(decoded, "CUST-ADDR-LINE-3"),
                strippedText(decoded, "CUST-ADDR-STATE-CD"),
                strippedText(decoded, "CUST-ADDR-COUNTRY-CD"),
                strippedText(decoded, "CUST-ADDR-ZIP"),
                strippedText(decoded, "CUST-PHONE-NUM-1"),
                strippedText(decoded, "CUST-PHONE-NUM-2"),
                CIPHERTEXT,
                CIPHERTEXT,
                LocalDate.parse(strippedText(decoded, "CUST-DOB-YYYY-MM-DD")),
                strippedText(decoded, "CUST-EFT-ACCOUNT-ID"),
                strippedText(decoded, "CUST-PRI-CARD-HOLDER-IND"),
                ((Long) decoded.get("CUST-FICO-CREDIT-SCORE")).shortValue());
    }

    /**
     * Assembles a forward window and seals it into an envelope.
     *
     * <p>Alternatives Considered: reaching a window of rows by counted distance from the start of the
     * run. Rejected because under concurrent inserts a request of that shape omits rows and repeats rows,
     * which changes behaviour a keyed read does not; a row inserted ahead of the current position shifts
     * every later row by one, so the next window both re-serves a row already served and steps over one
     * never served. The reference never counted either: {@code app/cbl/COCRDLIC.cbl} declares
     * {@code 01 WS-THIS-PROGCOMMAREA.} at L229 and carries across the terminal turn a trailing key pair at
     * L230 through L232 and a leading key pair at L233 through L235, each holding a card number and an
     * account identifier, and no ordinal into a result set anywhere. One further row beyond the window is
     * read so that the continuation flag is settled by discovery rather than by a running total, and that
     * further row is then discarded rather than published.</p>
     *
     * @param afterKey the key the window resumes strictly after, or {@code null} to open the run at its
     *     lowest key
     * @param rows how many rows the window publishes, which is one fewer than it reads
     * @return the sealed envelope, whose boundary tokens name the first and last published rows
     */
    private PageResponse<Customer> forwardWindow(Long afterKey, int rows) {
        List<Customer> read = afterKey == null
                ? this.customers.findAllByOrderByCustomerIdAsc(Limit.of(rows + 1))
                : this.customers.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                        afterKey, Limit.of(rows + 1));
        boolean furtherRowExists = read.size() > rows;
        return sealPublished(furtherRowExists ? read.subList(0, rows) : read, furtherRowExists);
    }

    /**
     * Assembles a backward window and seals it into an envelope carrying ascending rows.
     *
     * <p>Assumptions: the engine returns this window in descending order because that is the only order
     * in which a limit can take the rows adjacent to the bound, and the retained rows are reversed before
     * they are published so that both directions hand back one ascending sequence. Publishing the
     * descending order would make the two directions disagree about the order of the same rows.</p>
     *
     * @param beforeKey the key the window retreats strictly before
     * @param rows how many rows the window publishes, which is one fewer than it reads
     * @return the sealed envelope, whose rows ascend as the forward direction's do
     */
    private PageResponse<Customer> backwardWindow(Long beforeKey, int rows) {
        List<Customer> descending = this.customers
                .findByCustomerIdLessThanOrderByCustomerIdDesc(beforeKey, Limit.of(rows + 1));
        boolean furtherRowExists = descending.size() > rows;
        List<Customer> retained =
                furtherRowExists ? descending.subList(0, rows) : descending;
        List<Customer> ascending = new ArrayList<>(retained);
        Collections.reverse(ascending);
        return sealPublished(ascending, furtherRowExists);
    }

    /**
     * Seals a published run of rows into an envelope.
     *
     * <p>Assumptions: the envelope is assembled here rather than inside the repository because the
     * package charter places that assembly above the persistence layer -- a member of that layer yields
     * entities and ordered collections of them, and the boundary keys are sealed by the layer above it.
     * This method stands in for that layer so the composition of the three window queries can be held to
     * its contract without moving the sealing into the queries themselves.</p>
     *
     * @param published the rows this window publishes, already in ascending key order
     * @param furtherRowExists whether a further row was read beyond the published run
     * @return the envelope, empty and unbounded when nothing was published
     */
    private static PageResponse<Customer> sealPublished(
            List<Customer> published, boolean furtherRowExists) {
        if (published.isEmpty()) {
            return PageResponse.empty();
        }
        String leading = String.valueOf(published.get(0).getCustomerId());
        String trailing = String.valueOf(published.get(published.size() - 1).getCustomerId());
        return PageResponse.ofRows(List.copyOf(published),
                CURSOR_SEALER.seal(CURSOR_BINDING, leading),
                CURSOR_SEALER.seal(CURSOR_BINDING, trailing),
                furtherRowExists);
    }

    /**
     * Opens a sealed boundary token back into the raw key it names.
     *
     * @param token the sealed token taken from an envelope this class assembled
     * @return the raw key the token carries, for comparison against a published row's own key
     */
    private static String openedKey(String token) {
        return CURSOR_SEALER.open(CURSOR_BINDING, token);
    }

    /**
     * Strips the padding a fixed-width record pads a descriptive text field with.
     *
     * @param decoded the decoded record, keyed by copybook field name
     * @param fieldName the copybook name of the field to read
     * @return the field's stored form, with the record's trailing blanks removed
     */
    private static String strippedText(Map<String, Object> decoded, String fieldName) {
        return ((String) decoded.get(fieldName)).strip();
    }

    /**
     * Builds the ascending run of rows every case starts from.
     *
     * @return the seeded rows, whose identifiers are consecutive from this class's first seeded identifier
     */
    private static List<Customer> seededRows() {
        List<Customer> rows = new ArrayList<>(SEEDED_ROWS);
        for (int step = 0; step < SEEDED_ROWS; step++) {
            rows.add(seededCustomer(FIRST_SEEDED_ID + step));
        }
        return rows;
    }

    /**
     * Builds one seeded row.
     *
     * <p>Assumptions: every value is a literal and no value is read from a clock, so a case that runs
     * twice reads the same row twice. The identifiers are fabricated and identify nobody.</p>
     *
     * @param customerId the identifier this row carries
     * @return the row, with both protected columns holding this class's ciphertext constant
     */
    private static Customer seededCustomer(long customerId) {
        return new Customer(customerId, "SEED", "S", "SEEDED", "1 SEED WAY", null, "SEED CITY",
                "NY", "USA", "10001", "(212)555-0100", null, CIPHERTEXT, CIPHERTEXT,
                LocalDate.of(1980, 1, 15), "0000000001", "Y", (short) 700);
    }

    /**
     * Rewrites one row's first name, leaving every other field as it stands.
     *
     * <p>Assumptions: the update is applied to the loaded row rather than to a freshly built one, because
     * that is what puts the version the row was read at into the rewrite. Both protected positions are
     * told to preserve what they hold: the national identifier cannot be cleared at all, since its column
     * is declared not null, and neither may be replaced by a test that holds no key.</p>
     *
     * @param customer the loaded row to rewrite
     * @param newFirstName the first name to place on it
     */
    private static void renameFirstNameOf(Customer customer, String newFirstName) {
        customer.applyUpdate(newFirstName, customer.getMiddleName(), customer.getLastName(),
                customer.getAddressLine1(), customer.getAddressLine2(), customer.getAddressLine3(),
                customer.getAddressStateCode(), customer.getAddressCountryCode(),
                customer.getAddressZip(), customer.getPhoneNumber1(), customer.getPhoneNumber2(),
                Customer.ProtectedValueUpdate.preserve(), Customer.ProtectedValueUpdate.preserve(),
                customer.getDateOfBirth(), customer.getEftAccountId(),
                customer.getPrimaryCardHolderIndicator(), customer.getFicoCreditScore());
    }

    /**
     * Lists the identifiers of a window's rows, in the order the window published them.
     *
     * @param window the rows to read identifiers from
     * @return the identifiers, order preserved, for comparison against an expected sequence
     */
    private static List<Long> identifiersOf(List<Customer> window) {
        return window.stream().map(Customer::getCustomerId).toList();
    }

    /**
     * Reads one column's declared type from the catalog.
     *
     * @param column the column name to look up in the customer table
     * @return the catalog's name for that column's type
     */
    private String typeOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = ?"
                        + " AND table_name = 'customers' AND column_name = ?",
                String.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * Reads one column's declared character width from the catalog.
     *
     * @param column the column name to look up in the customer table
     * @return the declared width, or {@code null} for a column that has no character width
     */
    private Integer lengthOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = ?"
                        + " AND table_name = 'customers' AND column_name = ?",
                Integer.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * Reads the raw bytes one binary column holds for the fixture's row.
     *
     * @param column the binary column to read, which is one of the two protected columns
     * @return the stored bytes exactly as the engine returns them
     */
    // WHY : Assumptions: the column name is placed into the statement text because a column name cannot
    //       be a bound parameter in SQL. It is safe here and only here because both call sites pass a
    //       literal constant declared on this class, so no value from outside this file can reach it.
    private byte[] storedBytes(String column) {
        return this.jdbc.queryForObject(
                "SELECT " + column + " FROM account.customers WHERE customer_id = ?",
                byte[].class, FIXTURE_CUSTOMER_ID);
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the queue listener, the identity client and the
     * cipher this module's own application class would register stay out of the context. Naming the two
     * persistence packages leaves the framework's own auto-configuration to build the pool from the
     * properties the container registered and to run the migration ahead of it. The schema validation the
     * base profile sets is inherited unchanged rather than restated, so a disagreement between an entity
     * mapping and the migration ends the context, which is itself one of the things this class holds.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    // WHY : Assumptions: the two auto-configurations excluded here read deployment values the test profile
    //       deliberately does not carry -- the resource-server one evaluates its decoder condition against
    //       an issuer location and the queue one builds a client that needs a region, and both are bound
    //       in the base document to placeholders with no fallback. Leaving either in place ends context
    //       load while the condition is being evaluated, reporting a configuration failure in a class
    //       whose subject is the record contract. The two sibling persistence classes in this package
    //       exclude the same pair, so all three load the same shape.
    //       Trade-offs: neither the filter chain nor the queue transport is covered here, which is correct
    //       rather than a gap -- each is covered where it is configured, and a class about one record
    //       layout is not where either should first be exercised.
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    static class CustomerRecordPersistenceTestApplication {
    }
}
