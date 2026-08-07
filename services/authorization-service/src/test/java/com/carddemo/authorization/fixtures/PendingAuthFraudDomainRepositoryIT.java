package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the accepted domain of {@code authorization.pending_auth_detail.auth_fraud} onto a real engine.
 *
 * <p>Purpose: this class is the engine-tier half of the pair of fixtures
 * {@code PendingAuthDetailFraudDomainFixtureTest} documents byte by byte. That class proves the two
 * images are well-formed and states why one of them is deliberately out of domain; this class proves
 * what the database does with each -- that the three imaged states and SQL null are ACCEPTED, and that
 * {@code pautdtl1-auth-fraud-invalid.bin} is REFUSED by the check constraint named
 * {@code ck_pending_auth_detail_auth_fraud} and by no other cause.
 *
 * <p><b>Assumptions: {@code pautdtl1-auth-fraud-invalid.bin} is intentionally invalid.</b> If a change
 * to {@code V1__authorization.sql} widened the check to admit its {@code 'Y'}, the fixture would load
 * and this class would fail -- which is the point of it. The correct response to that failure is to
 * restore the four-state domain, never to amend this class or the fixture, because the widening the
 * domain already carries reaches exactly as far as its evidence: {@code 'F'} and {@code 'R'} from the
 * condition names at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 51 and 52, a
 * single blank from the copybook declaring no condition name for one while
 * {@code cbl/COPAUS1C.cbl} lines 344 to 349 handle that state explicitly, and null from the column
 * being nullable for an extract row that carries no flag.
 *
 * <p>Assumptions: the refusal is asserted BY CONSTRAINT NAME rather than as "some exception", and the
 * adjacency of the two constrained bytes is why. {@code PA-MATCH-STATUS} occupies offset 173 and
 * {@code PA-AUTH-FRAUD} offset 174, each governed by its own check, so a fixture that had broken the
 * neighbour would produce an identically green test under a weaker assertion.
 * {@link #aBrokenNeighbourAndADuplicateKeyNameTheirOwnConstraints()} exists to prove the
 * identification actually discriminates: it drives the two other refusals this row could plausibly
 * suffer and asserts each names its own constraint.
 *
 * <p>Assumptions: rows are written through PLAIN JDBC and not through the JPA entity, and that is a
 * requirement rather than a shortcut. {@code PendingAuthDetail}'s fraud mutator admits only
 * {@code 'F'} and {@code 'R'}, so an entity-mediated insert could never present {@code 'Y'} to the
 * database and the check would go unexercised. JDBC is also the shape of the writer this constraint
 * exists for: the migration's own rationale names the extract load as the second writer of this
 * column, and an invariant asserted in the marking flow cannot bind a bulk load.
 *
 * <p>Assumptions: the fourth accepted state, SQL null, is reached by an UPDATE rather than by an
 * insert from a fixture, because a fixed-width record has no way to image an absent field -- a COBOL
 * {@code X(01)} that nothing was moved into holds a blank, which is a different state and is already
 * imaged by the sibling's third record. Clearing the column to null is what the relational target adds,
 * so it is exercised on the path that produces it.
 *
 * <p>Trade-offs: the schema is built by running the module's own Flyway migration against the
 * container rather than by a hand-written table definition. A local definition would let this class
 * pass while the deployed constraint said something else, which would invert what it is for. The cost
 * is that a migration failure surfaces here as a setup error rather than as an assertion, and that is
 * acceptable: a migration that does not apply is a larger problem than the one this class tests.
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's failsafe
 * configuration includes exactly that suffix, so any other name would leave it unrun. The suffix
 * describes the tier -- a case needing a real database -- rather than the subject, which is a fixture
 * and a constraint.
 */
@Testcontainers
class PendingAuthFraudDomainRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     */
    // WHY : Assumptions: this is deliberately the SAME digest the repository integration tests of the
    //       auth and transaction contexts already name. Two integration tests pinning two engines
    //       could disagree about one constraint, and the disagreement would surface as whichever ran
    //       second; pinning by digest rather than by tag is what makes the reference immutable, since
    //       a publisher may rebuild and republish a minor tag on a new base layer.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The container every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The fixture whose fraud byte the check constraint must refuse. */
    private static final String REFUSED_FIXTURE = "fixtures/pautdtl1-auth-fraud-invalid.bin";

    /** The sibling fixture holding one record per admitted non-null fraud state. */
    private static final String ADMITTED_FIXTURE = "fixtures/pautdtl1-auth-fraud-domain.bin";

    /** The registry name of the 200-byte authorization-detail segment both fixtures are written to. */
    private static final String LAYOUT = "PAUTDTL";

    /** The declared segment length, so a multi-record image can be sliced. */
    private static final int SEGMENT_LENGTH = 200;

    /** The number of records the admitted-state sibling holds. */
    private static final int ADMITTED_RECORD_COUNT = 3;

    /**
     * The schema the migration owns, quoted at every SQL occurrence.
     */
    // WHY : Assumptions: the name must be QUOTED. It is a reserved word the parser otherwise reads as
    //       the AUTHORIZATION keyword, which yields a syntax error rather than a missing-schema error
    //       and so points at the wrong thing; data-migration/sql/V0__schemas_and_roles.sql records the
    //       same hazard for the same reason.
    private static final String SCHEMA = "\"authorization\"";

    /** The parent account of every record in both fixtures, inherited from the IMS root segment. */
    private static final long PARENT_ACCOUNT_ID = 10_000_000_001L;

    /** The customer the parent summary carries, matching the summary fixture's own value. */
    private static final long PARENT_CUSTOMER_ID = 451L;

    /** Complement base for the five-digit date, {@code 99999 - YYDDD} at COPAUA0C line 874. */
    private static final int DATE_COMPLEMENT_BASE = 99999;

    /** Complement base for the nine-digit time, {@code 999999999 - HHMMSSmmm} at line 875. */
    private static final int TIME_COMPLEMENT_BASE = 999999999;

    /** The name of the check this fixture exists to trip. */
    private static final String FRAUD_CONSTRAINT = "ck_pending_auth_detail_auth_fraud";

    /** The name of the check governing the byte immediately before the fraud flag. */
    private static final String MATCH_STATUS_CONSTRAINT = "ck_pending_auth_detail_match_status";

    /** The name of the composite key a colliding load would report instead. */
    private static final String PRIMARY_KEY_CONSTRAINT = "pk_pending_auth_detail";

    /** The SQL state PostgreSQL reports for a check violation. */
    private static final String CHECK_VIOLATION = "23514";

    /** The SQL state PostgreSQL reports for a unique or primary-key violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** The insert every case drives, naming every column the row needs. */
    private static final String INSERT_DETAIL = """
            INSERT INTO %s.pending_auth_detail (
                account_id, auth_date, auth_time, auth_orig_date, auth_orig_time, card_num,
                auth_type, card_expiry_date, message_type, message_source, auth_id_code,
                auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id,
                merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id,
                match_status, auth_fraud, fraud_rpt_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(SCHEMA);

    /**
     * Applies the module's own migration to the container once, before any case runs.
     *
     * <p>Assumptions: {@code createSchemas} is enabled here although the deployed configuration
     * disables it, because deployment relies on {@code data-migration/sql/V0__schemas_and_roles.sql}
     * having created the eight schemas and their roles first. A bare container has neither, and
     * creating the one schema this migration owns is the narrowest way to close that gap without
     * importing the whole bootstrap.</p>
     *
     * <p>Trade-offs: a Flyway failure is deliberately not caught. It aborts the class before any case
     * runs, which reports the migration as the cause instead of letting every case fail on a missing
     * table and leave a reader to infer why.</p>
     */
    @BeforeAll
    static void applyMigration() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("authorization")
                .defaultSchema("authorization")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Empties both tables and re-creates the parent summary before each case.
     *
     * <p>Assumptions: the parent row is required rather than optional.
     * {@code fk_pending_auth_detail_summary} makes a detail row unreachable without its summary, which
     * reproduces the hierarchical rule that an IMS child exists only beneath its root -- so without
     * this insert every case below would fail on the foreign key and never reach the check under
     * test.</p>
     */
    @BeforeEach
    void resetToOneParentSummary() {
        execute("DELETE FROM " + SCHEMA + ".pending_auth_detail");
        execute("DELETE FROM " + SCHEMA + ".pending_auth_summary");
        execute("INSERT INTO " + SCHEMA + ".pending_auth_summary (account_id, customer_id) VALUES ("
                + PARENT_ACCOUNT_ID + ", " + PARENT_CUSTOMER_ID + ")");
    }

    /**
     * Opens a connection whose search path is the migrated schema.
     *
     * @return a live connection, which the caller closes
     * @throws SQLException if the container refuses the connection or the search path cannot be set
     */
    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    /**
     * Runs one statement that is expected to succeed.
     *
     * @param sql the statement to run
     * @throws IllegalStateException if the statement fails, because every caller here is setup and a
     *     setup failure must abort rather than be mistaken for the refusal a case is asserting
     */
    private static void execute(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("setup statement failed: " + sql, failure);
        }
    }

    /**
     * Reads one fixture resource as raw bytes.
     *
     * @param name the resource name below the class-path root
     * @return the resource's bytes, exactly as stored
     * @throws AssertionError if the resource is absent from the test class path
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] bytes(String name) {
        try (InputStream stream = PendingAuthFraudDomainRepositoryIT.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Slices one record out of a multi-record image.
     *
     * @param image the whole fixture image
     * @param ordinal the zero-based record ordinal
     * @return exactly {@link #SEGMENT_LENGTH} bytes beginning at that ordinal's offset
     */
    private static byte[] record(byte[] image, int ordinal) {
        int start = ordinal * SEGMENT_LENGTH;
        return Arrays.copyOfRange(image, start, start + SEGMENT_LENGTH);
    }

    /**
     * Decodes one record through the registered layout.
     *
     * @param segment exactly one record of the registered length
     * @return the decoded fields in copybook declaration order
     */
    private static Map<String, Object> decode(byte[] segment) {
        return FixedWidthCodec.decodeRecord(segment, CopybookLayout.layout(LAYOUT));
    }

    /**
     * Returns one decoded field as text, trailing blanks removed, or null when it is entirely blank.
     *
     * <p>Assumptions: a wholly blank optional character field becomes SQL null rather than a string of
     * blanks, which is what the load path does for a field the reference never wrote into. The fraud
     * flag is deliberately NOT read through this helper, because for that column a blank and a null are
     * two DIFFERENT accepted states and collapsing them would delete one of the four.</p>
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the trimmed text, or {@code null} when the field holds only blanks
     */
    private static String optionalText(Map<String, Object> fields, String name) {
        String value = String.valueOf(fields.getOrDefault(name, "")).stripTrailing();
        return value.isEmpty() ? null : value;
    }

    /**
     * Returns one decoded field as an exact decimal.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's value as an exact decimal
     */
    private static BigDecimal numberField(Map<String, Object> fields, String name) {
        return new BigDecimal(String.valueOf(fields.get(name)));
    }

    /**
     * Inserts one fixture record, optionally overriding the flag and the match status it carries.
     *
     * <p>Assumptions: the date and time columns receive the value with the nines complement UNDONE.
     * The fixture's bytes hold {@code 99999 - YYDDD} and {@code 999999999 - HHMMSSmmm}; the columns are
     * integers holding the business values, and inserting a complement would store a row no reader
     * could render while satisfying every constraint.</p>
     *
     * @param segment exactly one 200-byte record
     * @param fraudFlag the flag to store, or {@code null} to store SQL null; pass the record's own
     *     value to insert it unaltered
     * @param matchStatus the match status to store, which every case but one leaves at the record's own
     *     valid value
     * @throws SQLException if the insert is refused, which the refusal cases assert on
     */
    private static void insertDetail(byte[] segment, String fraudFlag, String matchStatus)
            throws SQLException {
        Map<String, Object> fields = decode(segment);
        try (Connection connection = connection();
                PreparedStatement insert = connection.prepareStatement(INSERT_DETAIL)) {
            insert.setLong(1, PARENT_ACCOUNT_ID);
            insert.setInt(2, DATE_COMPLEMENT_BASE
                    - numberField(fields, "PA-AUTH-DATE-9C").intValueExact());
            insert.setInt(3, TIME_COMPLEMENT_BASE
                    - numberField(fields, "PA-AUTH-TIME-9C").intValueExact());
            insert.setString(4, optionalText(fields, "PA-AUTH-ORIG-DATE"));
            insert.setString(5, optionalText(fields, "PA-AUTH-ORIG-TIME"));
            insert.setString(6, optionalText(fields, "PA-CARD-NUM"));
            insert.setString(7, optionalText(fields, "PA-AUTH-TYPE"));
            insert.setString(8, optionalText(fields, "PA-CARD-EXPIRY-DATE"));
            insert.setString(9, optionalText(fields, "PA-MESSAGE-TYPE"));
            insert.setString(10, optionalText(fields, "PA-MESSAGE-SOURCE"));
            insert.setString(11, optionalText(fields, "PA-AUTH-ID-CODE"));
            insert.setString(12, optionalText(fields, "PA-AUTH-RESP-CODE"));
            insert.setString(13, optionalText(fields, "PA-AUTH-RESP-REASON"));
            insert.setString(14, optionalText(fields, "PA-PROCESSING-CODE"));
            insert.setBigDecimal(15, numberField(fields, "PA-TRANSACTION-AMT"));
            insert.setBigDecimal(16, numberField(fields, "PA-APPROVED-AMT"));
            insert.setString(17, optionalText(fields, "PA-MERCHANT-CATAGORY-CODE"));
            insert.setString(18, optionalText(fields, "PA-ACQR-COUNTRY-CODE"));
            insert.setShort(19, numberField(fields, "PA-POS-ENTRY-MODE").shortValueExact());
            insert.setString(20, optionalText(fields, "PA-MERCHANT-ID"));
            insert.setString(21, optionalText(fields, "PA-MERCHANT-NAME"));
            insert.setString(22, optionalText(fields, "PA-MERCHANT-CITY"));
            insert.setString(23, optionalText(fields, "PA-MERCHANT-STATE"));
            insert.setString(24, optionalText(fields, "PA-MERCHANT-ZIP"));
            insert.setString(25, optionalText(fields, "PA-TRANSACTION-ID"));
            insert.setString(26, matchStatus);
            insert.setString(27, fraudFlag);
            insert.setString(28, optionalText(fields, "PA-FRAUD-RPT-DATE"));
            insert.executeUpdate();
        }
    }

    /**
     * Inserts one fixture record exactly as its bytes declare it.
     *
     * @param segment exactly one 200-byte record
     * @throws SQLException if the insert is refused, which the refusal cases assert on
     */
    private static void insertAsImaged(byte[] segment) throws SQLException {
        Map<String, Object> fields = decode(segment);
        insertDetail(segment, String.valueOf(fields.get("PA-AUTH-FRAUD")),
                String.valueOf(fields.get("PA-MATCH-STATUS")));
    }

    /**
     * Reads every stored fraud flag, ordered by the key, with null preserved as null.
     *
     * @return one entry per stored row, holding the flag or {@code null}
     * @throws SQLException if the query fails
     */
    private static List<String> storedFraudFlags() throws SQLException {
        List<String> flags = new ArrayList<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT auth_fraud FROM " + SCHEMA + ".pending_auth_detail"
                                + " ORDER BY auth_date, auth_time")) {
            while (rows.next()) {
                flags.add(rows.getString(1));
            }
        }
        return flags;
    }

    /**
     * Counts the stored detail rows.
     *
     * @return the number of rows in {@code pending_auth_detail}
     * @throws SQLException if the query fails
     */
    private static int storedRowCount() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM " + SCHEMA + ".pending_auth_detail")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Moves the fraud flag of every row currently carrying one value to another.
     *
     * @param newFlag the flag to store
     * @param currentFlag the flag identifying the rows to move
     * @throws SQLException if the update is refused, which the update case asserts on
     */
    private static void updateFraudFlag(String newFlag, String currentFlag) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement update = connection.prepareStatement("UPDATE " + SCHEMA
                        + ".pending_auth_detail SET auth_fraud = ? WHERE auth_fraud = ?")) {
            update.setString(1, newFlag);
            update.setString(2, currentFlag);
            update.executeUpdate();
        }
    }

    /**
     * Confirms the live catalog carries the four-state check under the name this class asserts.
     *
     * <p>Assumptions: the predicate is read back from {@code pg_get_constraintdef} rather than from the
     * migration file, so this case proves what the ENGINE enforces rather than what the source says.
     * The two can differ -- a migration edited after a deployment, or a constraint dropped by hand --
     * and the engine's copy is the one that decides whether the fixture below is refused.</p>
     *
     * @throws SQLException if the catalog cannot be queried
     */
    @Test
    @DisplayName("the engine's own catalog admits exactly 'F', 'R', NULL and a single blank")
    void theEngineCarriesTheFourStateCheckUnderItsDeclaredName() throws SQLException {
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            query.setString(1, FRAUD_CONSTRAINT);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("%s must exist in the catalog", FRAUD_CONSTRAINT).isTrue();
                String definition = rows.getString(1);
                assertThat(definition).contains("auth_fraud");
                assertThat(definition).contains("'F'").contains("'R'").contains("IS NULL");
                assertThat(definition).contains("' '");
                assertThat(definition).doesNotContain("'Y'");
            }
        }
    }

    /**
     * Confirms all four accepted states are stored without complaint.
     *
     * <p>Assumptions: the three imaged states are inserted from the sibling fixture's own bytes, so
     * this case would fail if that file changed, and the fourth is reached by clearing one row to null
     * for the reason given on the class. Stating all four in one case is what documents the domain as
     * a closed set of four rather than as a column that happens to accept the values tried.</p>
     *
     * @throws SQLException if any accepted state is refused, which would itself be the finding
     */
    @Test
    @DisplayName("'F', 'R', a blank and SQL null are all accepted")
    void theThreeImagedStatesAndSqlNullAreAllAccepted() throws SQLException {
        byte[] admitted = bytes(ADMITTED_FIXTURE);
        for (int ordinal = 0; ordinal < ADMITTED_RECORD_COUNT; ordinal++) {
            insertAsImaged(record(admitted, ordinal));
        }

        assertThat(storedFraudFlags()).containsExactly("F", "R", " ");

        execute("UPDATE " + SCHEMA + ".pending_auth_detail SET auth_fraud = NULL"
                + " WHERE auth_fraud = ' '");

        assertThat(storedFraudFlags()).containsExactly("F", "R", null);
        assertThat(storedRowCount()).isEqualTo(ADMITTED_RECORD_COUNT);
    }

    /**
     * Confirms the refused fixture is rejected, and that the rejection names the fraud check.
     *
     * <p>Assumptions: this is the fixture's reason to exist, so the assertion is deliberately narrow.
     * It requires the check-violation SQL state, requires the reported constraint to be
     * {@code ck_pending_auth_detail_auth_fraud}, and requires it NOT to be the neighbouring
     * match-status check -- because the two constrained bytes are adjacent and an accidental break of
     * the wrong one would otherwise read as a pass. The row count is asserted afterwards so a partial
     * write cannot hide behind the exception.</p>
     *
     * @throws SQLException if the row count cannot be read after the refusal
     */
    @Test
    @DisplayName("the out-of-domain 'Y' is refused by the fraud check specifically")
    void theRefusedFixtureIsRejectedAndTheFailureNamesTheFraudCheck() throws SQLException {
        byte[] refused = bytes(REFUSED_FIXTURE);

        assertThatThrownBy(() -> insertAsImaged(refused))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(FRAUD_CONSTRAINT)
                .hasMessageNotContainingAny(MATCH_STATUS_CONSTRAINT, PRIMARY_KEY_CONSTRAINT)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        assertThat(storedRowCount())
                .as("the refused row reached no table at all, so no partial write hid behind it")
                .isZero();
    }

    /**
     * Confirms the two other refusals this row could suffer name their own constraints.
     *
     * <p>Assumptions: without this case the assertion above would be weaker than it reads. It proves
     * that a refusal identifies its cause rather than reporting a generic failure, by driving the two
     * plausible alternatives -- a match status outside its own domain, and a duplicate of the composite
     * key -- and requiring each to name a DIFFERENT constraint. That is what licenses reading the
     * fraud-check assertion as being about the fraud column.</p>
     *
     * @throws SQLException if the accepted row this case first inserts is refused
     */
    @Test
    @DisplayName("a broken match status and a duplicate key name their own constraints, not the fraud check")
    void aBrokenNeighbourAndADuplicateKeyNameTheirOwnConstraints() throws SQLException {
        byte[] refused = bytes(REFUSED_FIXTURE);
        byte[] firstAdmitted = record(bytes(ADMITTED_FIXTURE), 0);
        insertAsImaged(firstAdmitted);

        assertThatThrownBy(() -> insertDetail(refused, "F", "X"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(MATCH_STATUS_CONSTRAINT)
                .hasMessageNotContainingAny(FRAUD_CONSTRAINT)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        assertThatThrownBy(() -> insertAsImaged(firstAdmitted))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(PRIMARY_KEY_CONSTRAINT)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(UNIQUE_VIOLATION);
    }

    /**
     * Confirms loading the refused record beside its siblings fails on the check, not on the key.
     *
     * <p>Assumptions: this is what the fixture's distinct Julian 24121 buys. The refused record shares
     * its parent account and its time of day with the sibling that carries {@code 'F'}, so an identical
     * Julian would make this combined load fail on {@code pk_pending_auth_detail} before the fraud
     * check was evaluated -- and the case would pass while proving nothing. The three siblings are
     * loaded first and asserted present, so the key is demonstrably available when the fourth insert
     * is refused.</p>
     *
     * @throws SQLException if any of the three accepted records is refused
     */
    @Test
    @DisplayName("loaded beside its three siblings, the refused record still fails on the fraud check")
    void loadingTheRefusedRecordBesideItsSiblingsFailsOnTheCheckAndNotTheKey() throws SQLException {
        byte[] admitted = bytes(ADMITTED_FIXTURE);
        for (int ordinal = 0; ordinal < ADMITTED_RECORD_COUNT; ordinal++) {
            insertAsImaged(record(admitted, ordinal));
        }
        assertThat(storedRowCount()).isEqualTo(ADMITTED_RECORD_COUNT);

        assertThatThrownBy(() -> insertAsImaged(bytes(REFUSED_FIXTURE)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(FRAUD_CONSTRAINT)
                .hasMessageNotContainingAny(PRIMARY_KEY_CONSTRAINT,
                        "uq_pending_auth_detail_card_transaction");
        assertThat(storedRowCount())
                .as("the refused row reached no table; only its three siblings remain")
                .isEqualTo(ADMITTED_RECORD_COUNT);
    }

    /**
     * Confirms the check binds an update as well as an insert.
     *
     * <p>Assumptions: the column is written by more than one path -- the marking flow toggles it in
     * both directions and the extract loads it -- so a domain enforced only on insert would leave the
     * update path unbound. Asserting the update refusal is how the constraint's coverage is shown to
     * match the number of writers rather than the number of tests.</p>
     *
     * @throws SQLException if the accepted row this case first inserts is refused
     */
    @Test
    @DisplayName("updating an accepted row to the out-of-domain 'Y' is refused too")
    void theRefusedFlagIsAlsoRejectedOnUpdate() throws SQLException {
        insertAsImaged(record(bytes(ADMITTED_FIXTURE), 0));

        assertThatThrownBy(() -> updateFraudFlag("Y", "F"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(FRAUD_CONSTRAINT)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        assertThat(storedFraudFlags()).containsExactly("F");
    }
}
