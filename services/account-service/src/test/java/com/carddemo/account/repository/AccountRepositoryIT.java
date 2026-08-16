package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.config.DataSourceConfig;
import com.carddemo.account.domain.Account;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Holds {@link AccountRepository} and the {@code account.accounts} table it reads against a real
 * PostgreSQL engine, so that the account master's migrated storage contract is proven rather than
 * assumed.
 *
 * <h2>Purpose</h2>
 *
 * <p>Four things about this table can only be established by the engine that stores it: that the five
 * exact-decimal columns keep their declared scale, that the three date columns really are dates while
 * two same-width character columns really are not, that a competing update is detected by the version
 * column, and that the three keyed windows resume strictly after or strictly before the position they
 * are given. The sibling {@code com.carddemo.account.service} and {@code com.carddemo.account.mapper}
 * test packages substitute the store, which is correct for what they assert and structurally unable to
 * establish any of the four.</p>
 *
 * <p>The nine package-level rulings this class obeys are recorded once in {@code package-info.java}
 * beside this file and are cited rather than restated here. The commentary below is confined to what is
 * specific to {@code Account}.</p>
 *
 * <h2>What the reference does, and what this class holds the Java to</h2>
 *
 * <p>The account master is {@code 01 ACCOUNT-RECORD.} at L4 of {@code app/cpy/CVACT01Y.cpy}, whose L2
 * header declares a record length of 300 bytes. Its twelve named fields run L5 through L16 and its
 * {@code FILLER PIC X(178)} at L17 pads the record out to that declared length. The online keyed read
 * this repository replaces is paragraph {@code 9300-GETACCTDATA-BYACCT.} at L774 of
 * {@code app/cbl/COACTVWC.cbl}, whose {@code EXEC CICS READ} at L776 names the file at L777, the record
 * identification field at L778, its key length at L779 and the receiving record and its length at L780
 * and L781. The sequential walk the keyed windows replace is the open, get-next, close triad of
 * {@code app/cbl/CBACT01C.cbl} at 430 lines: {@code 0000-ACCTFILE-OPEN.} at L317 with its
 * {@code OPEN INPUT ACCTFILE-FILE} at L319, {@code 1000-ACCTFILE-GET-NEXT.} at L165 with its
 * {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD.} at L166, and {@code 9000-ACCTFILE-CLOSE.} at L388
 * with its {@code CLOSE ACCTFILE-FILE} at L390.</p>
 *
 * <p>L11 of that copybook spells the expiration field {@code ACCT-EXPIRAION-DATE}. The baseline carries
 * that spelling; the target column is {@code expiration_date} and the mapped member is
 * {@code expirationDate}; the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Nothing under {@code app/} is altered by
 * this class or by anything it exercises.</p>
 *
 * <h2>Assumptions: no executable parity oracle exists for this context</h2>
 *
 * <p>Every assertion below is authored directly from the copybook contract and the program paragraphs
 * named above, because there is nothing to compare against. L83 through L85 of {@code tests/README.md}
 * record that the online programs cannot be run end to end without a CICS runtime, which the runner does
 * not have, so only their extractable field-validation logic is unit-tested there. And the business
 * rules that suite asserts verbatim, from its L553 onward, name the posting, interest and
 * category-balance programs of other contexts and name none of this context's programs or files. No
 * golden-master comparison is therefore claimed for any case here, and none is available to claim. The
 * COBOL three-layer suite under {@code tests/} is separate reference material with its own runners; the
 * cases in this file are strictly additive to it and the gate over them is pass or fail.</p>
 *
 * <h2>Assumptions: the two 300-byte fixtures, and why one of them must be negative</h2>
 *
 * <p>This class reads {@code fixtures/account/account-valid.txt} and
 * {@code fixtures/account/account-negative-balance.txt} from the classpath by name, so those two names
 * are part of its contract: {@code application-test.yml} declares no fixture location, and nothing else
 * resolves them. Each is exactly 300 bytes with no terminating newline, which is the length L2 of
 * {@code app/cpy/CVACT01Y.cpy} declares and L17's {@code FILLER PIC X(178)} completes.</p>
 *
 * <p>Assumptions: at least one fixture must carry a negative amount, and an all-positive set would be
 * unable to detect the defect that matters most here. L273 and L274 of {@code tests/README.md} record
 * that {@code -fsign=EBCDIC} is required precisely because the default sign convention misreads the
 * zoned-decimal sign overpunch and silently corrupts negative balances. A corruption that is silent is
 * one no positive vector can expose, so the negative fixture carries both overpunch shapes rather than
 * one. Its {@code ACCT-CURR-BAL} decodes to -919.00, its eleven leading digits being closed by the
 * row-head character of the negative overpunch sequence, which encodes a negative zero digit; its
 * {@code ACCT-CURR-CYC-DEBIT} decodes to -193.45, closed instead by {@code N}, a mid-sequence letter
 * encoding a negative five. Both are deliberately non-zero, because a decimal value has no negative
 * zero: a negatively
 * overpunched zero decodes to zero and re-encodes with the positive character, which is the one zoned
 * round trip that is not byte-identical, and a fixture resting on it would prove nothing about sign
 * handling.</p>
 *
 * <h2>Alternatives Considered: a real engine rather than a substitute for one</h2>
 *
 * <p>An in-memory engine, and an embedded build of this engine, were both rejected, and not on grounds
 * of realism in general. Four specific capabilities are the subject of the cases below rather than
 * their backdrop: {@code NUMERIC(p,s)} scale semantics, which decide whether an amount keeps two
 * decimal places or acquires more; fixed-width {@code character} padding, which decides whether a
 * ten-byte code comes back at its declared width; session {@code search_path} pinning, which decides
 * whether an unqualified table name resolves at all; and the engine's own catalog, which is the only
 * place the declared column contract can be read back from. A substitute that emulated any of the four
 * approximately would let a case pass while describing storage the deployment does not have.</p>
 *
 * <h2>The conversational state that is gone</h2>
 *
 * <p>What the reference carried between terminal turns was one structure, {@code 01 CARDDEMO-COMMAREA.}
 * at L19 of {@code app/cpy/COCOM01Y.cpy} running to L44, whose declared field widths sum to 160 bytes.
 * It does not travel in the target: identity arrives as claims on a validated token, the values
 * {@code 'A'} and {@code 'U'} at L27 and L28 of that copybook becoming the {@code carddemo-admin} and
 * {@code carddemo-user} groups, and selection context arrives in the request BODY -- registered as
 * {@code D-ACCOUNT-SELECTION-IN-BODY} in
 * {@code docs/architecture/cobol-to-service-traceability.md} §7.4, and stated correctly here because a
 * reader of this class is being told what replaced the structure, not merely that something did. The
 * reference asserts
 * its own statelessness independently, {@code app/csd/CARDDEMO.CSD} giving {@code TWASIZE(0)} to
 * {@code COACTUPC} at L308 and to {@code COACTVWC} at L318, so this layer holds no conversational state
 * and a case may build a context and discard it freely.</p>
 *
 * <h2>Why every member below carries a block like this one</h2>
 *
 * <p>The one user-specified rule governing this migration, its Explainability rule, requires a docstring
 * on every class and function and requires each non-obvious choice to name why it was made under one of
 * four categories; its validation gate is conjunctive, so a member missing either half fails review. The
 * written convention that spells this out for each language in the new tree is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the mechanical half for Java is the Javadoc gate
 * {@code config/checkstyle/checkstyle.xml} declares, which the Checkstyle execution in
 * {@code services/pom.xml} runs at {@code validate} with its test-source-directory flag set -- so this
 * file is audited before it is compiled. Trade-offs: the four category labels here are written in the
 * plural and without parentheses, taking their spelling from the rule that defines them, which is the
 * spelling the rest of this Java tree uses even though the Checkstyle configuration and section 11 of
 * {@code tests/README.md} use singular forms. What that costs is reading differently from the
 * surrounding non-Java material; what it buys is one searchable spelling, and it specifically avoids
 * imitating L548 of {@code tests/README.md}, whose own label is written with a non-breaking hyphen that
 * no ordinary search will match.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Assumptions: BOTH remote configuration locations are disabled, because the context cannot
//       otherwise start. The non-profile document imports
//       `optional:aws-parameterstore:` and `optional:aws-secretsmanager:` locations, and LOADING either
//       one builds an AWS client while configuration is still being assembled; the region this module's
//       test profile pins arrives too late, because a profile-specific document is read only after the
//       non-profile document's imports have resolved. The `optional:` marker does not help either: it
//       tolerates a location that yields nothing, not one that cannot construct a client. The three
//       sibling integration classes in this package set the same two flags for the same measured reason.
@SpringBootTest(
        classes = AccountRepositoryIT.AccountMasterPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.cloud.aws.parameterstore.enabled=false",
            "spring.cloud.aws.secretsmanager.enabled=false"
        })
@ActiveProfiles("test")
class AccountRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration class in this repository names, so a
     * single cached layer set serves them all and no two of them can silently run against different
     * engine builds. A moving tag was rejected because a registry-side rebuild could change collation or
     * a default setting under an unchanged commit, and ordering is exactly what that would break.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container every case in this class runs against, started once for the class.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The owning role the migration's own session assumes before it creates anything.
     */
    private static final String OWNER_ROLE = "carddemo_account_owner";

    /**
     * The classpath location of the all-positive 300-byte account image.
     */
    private static final String VALID_FIXTURE = "/fixtures/account/account-valid.txt";

    /**
     * The classpath location of the 300-byte account image carrying both negative overpunch shapes.
     */
    private static final String NEGATIVE_FIXTURE = "/fixtures/account/account-negative-balance.txt";

    /**
     * The record length L2 of {@code app/cpy/CVACT01Y.cpy} declares.
     */
    private static final int RECORD_LENGTH = 300;

    /**
     * The declared width shared by the two character columns and the three date fields in the copybook.
     */
    private static final int TEN_BYTE_WIDTH = 10;

    /**
     * The status every seeded row carries, one of the two the migration's check constraint admits.
     */
    private static final String SEEDED_ACTIVE_STATUS = "Y";

    /**
     * The posted balance every seeded row carries, matching the all-positive fixture.
     *
     * <p>Assumptions: the seeded values below are the ones the all-positive fixture decodes to, so the
     * lowest seeded row and that fixture describe the same account. That lets the fixture case assert
     * agreement between the 300-byte image and the stored row instead of asserting each separately
     * against a literal written twice.</p>
     */
    private static final String SEEDED_CURRENT_BALANCE = "193.00";

    /**
     * The credit limit every seeded row carries, matching the all-positive fixture.
     */
    private static final String SEEDED_CREDIT_LIMIT = "2065.00";

    /**
     * The cash credit limit every seeded row carries, matching the all-positive fixture.
     */
    private static final String SEEDED_CASH_CREDIT_LIMIT = "264.00";

    /**
     * The cycle credit every seeded row carries, matching the all-positive fixture.
     */
    private static final String SEEDED_CYCLE_CREDIT = "0.00";

    /**
     * The cycle debit every seeded row carries, matching the all-positive fixture.
     */
    private static final String SEEDED_CYCLE_DEBIT = "0.00";

    /**
     * The open date of the lowest seeded row; each higher row advances it by one day.
     */
    private static final String SEEDED_OPEN_DATE = "2012-10-12";

    /**
     * The expiration date every seeded row carries, matching the all-positive fixture.
     */
    private static final String SEEDED_EXPIRATION_DATE = "2024-12-13";

    /**
     * The reissue date every seeded row carries, matching the all-positive fixture.
     */
    private static final String SEEDED_REISSUE_DATE = "2024-12-13";

    /**
     * The postal code every seeded row carries.
     *
     * <p>Assumptions: this value is deliberately not date-shaped and not wholly numeric. It is ten bytes
     * wide, exactly as the three date fields are, so it is the value that would break first under a rule
     * that decided storage from declared width alone.</p>
     */
    private static final String SEEDED_ADDR_ZIP = "A000000000";

    /**
     * The disclosure-group key every seeded row carries, at its stored fixed width.
     *
     * <p>Assumptions: the trailing spaces are part of the value rather than an accident of formatting.
     * The copybook declares {@code ACCT-GROUP-ID PIC X(10)} at L16 and the fixture stores a
     * seven-character group padded out to ten, which is what a fixed-width column returns.</p>
     */
    private static final String SEEDED_GROUP_ID = "DEFAULT   ";

    /**
     * The identifier the all-positive fixture carries, which is also the lowest seeded identifier.
     */
    private static final long FIRST_ACCOUNT_ID = 11L;

    /**
     * The identifier the negative fixture carries, chosen distinct so both images can coexist under the
     * assigned primary key.
     */
    private static final long SECOND_ACCOUNT_ID = 12L;

    /**
     * An identifier no case seeds, used to prove an absent key is answered rather than raised.
     */
    private static final long ABSENT_ACCOUNT_ID = 999L;

    /**
     * The five identifiers seeded before every case, ascending.
     */
    private static final List<Long> SEEDED_IDS = List.of(11L, 12L, 13L, 14L, 15L);

    /**
     * The number of rows a window shows a caller.
     *
     * <p>Assumptions: the seeded row count is deliberately not a multiple of this, so the final window
     * of a walk is partial and the surplus-row probe has to answer differently on it than on the
     * windows before it.</p>
     */
    private static final int WINDOW_ROWS = 2;

    /**
     * The bound every read below asks for: one row more than a window shows.
     *
     * <p>Assumptions: the extra row is never displayed. It exists only so that the presence of a further
     * window can be established from a row that was actually read, rather than by asking the engine how
     * many rows the table holds, which is a question whose answer changes under concurrent inserts and
     * costs a scan to answer.</p>
     */
    private static final Limit READ_BOUND = Limit.of(WINDOW_ROWS + 1);

    /**
     * The repository under test.
     */
    @Autowired
    private AccountRepository repository;

    /**
     * The template every seed statement and every catalog read goes through.
     *
     * <p>Trade-offs: rows are seeded with plain statements rather than through the repository under
     * test, which is the ruling recorded in {@code package-info.java} beside this file. The cost is that
     * the seed does not exercise the mapping; what it buys is that a window returning nothing because
     * the seed never persisted cannot be mistaken for a window whose query is wrong.</p>
     */
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Seals and opens the boundary positions of an assembled window.
     *
     * <p>Assumptions: {@code com.carddemo.common.web.PageResponse} refuses any boundary that is not a
     * sealed token, so a window cannot be assembled here without one. The key is drawn from the
     * platform's strong random source for the lifetime of the class rather than written down, because a
     * committed key would be a credential in source and this one needs to outlive nothing.</p>
     */
    private static final CursorToken CURSOR_TOKENS =
            new CursorToken(freshKey(), Duration.ofMinutes(5));

    /**
     * The binding every boundary in this class is sealed under.
     *
     * <p>Assumptions: the account walk narrows nothing, so it declares {@code CursorToken.SCOPE_NONE}
     * rather than an empty scope, which would be indistinguishable from a scope a caller forgot to
     * render.</p>
     */
    private static final String SCAN_BINDING = CursorToken.binding(
            "account-master-scan", "account-repository-integration", CursorToken.SCOPE_NONE);

    /**
     * Draws key material for {@link #CURSOR_TOKENS} from the platform's strong random source.
     *
     * <p>Assumptions: the length is taken from {@code CursorToken.MIN_KEY_LENGTH} rather than written as
     * a number here, so a change to that minimum cannot leave this class constructing an instance the
     * constructor would refuse.</p>
     *
     * @return freshly drawn key material of exactly the minimum accepted length, never {@code null}
     */
    private static byte[] freshKey() {
        byte[] material = new byte[CursorToken.MIN_KEY_LENGTH];
        new SecureRandom().nextBytes(material);
        return material;
    }

    /**
     * Publishes the container's generated coordinates into the environment the context binds.
     *
     * <p>Assumptions: {@code application-test.yml} carries no datasource URL, user name or credential of
     * any kind and states that omission deliberately, because the container's port and database name are
     * generated per run and no committed value could match them. The migration is given the same
     * credentials so that it and the entity mapping reach one database.</p>
     *
     * <p>Assumptions: this registers through {@code @DynamicPropertySource} rather than through a
     * service-connection annotation, and the distinction is worth recording because the latter is the
     * more usual modern idiom so its absence reads as an oversight. This module's POM declares the
     * Testcontainers JUnit and PostgreSQL modules and declares no Spring Boot Testcontainers module, so
     * no service-connection annotation and no connection-details bean exists on this test classpath to
     * be used.</p>
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
     * <p>Assumptions: nothing else in a container supplies this prerequisite, and its absence is the
     * single most likely wiring failure in this package. {@code V1__account.sql} declares no
     * {@code CREATE SCHEMA}, no {@code CREATE ROLE} and no {@code GRANT} of its own;
     * {@code data-migration/sql/V0__schemas_and_roles.sql} is the exclusive authority for schemas, owner
     * roles and grants across this system, creating this role without login at its L596 and the schema
     * under that ownership at its L711; and a throwaway container has never run it. The non-profile
     * document additionally forbids the migration tool from creating a schema and instructs its session
     * to assume this role first, so every object the migration creates belongs to the role and the role
     * has to exist before the first statement runs. A schema missing at that moment surfaces at RUN TIME
     * as a context that will not start, with nothing from the compiler beforehand.</p>
     *
     * <p>Assumptions: this runs before the Spring context exists, because JUnit invokes a
     * {@code @BeforeAll} method after the Testcontainers extension has started the static container and
     * before the Spring extension builds the context for the first test instance. Plain JDBC is used
     * rather than the injected template for that reason, there being no bean yet to inject.</p>
     *
     * <p>This setup step takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the container refuses the connection or any statement, which is a broken
     *     harness rather than a failed assertion and is reported as one
     */
    @BeforeAll
    static void createOwnerRoleAndSchemaBeforeFlywayRuns() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + OWNER_ROLE + " NOLOGIN");
            // WHY : Assumptions: the right to create in the database is not implied by creating the
            //       role. A fresh role holds only the grants held by everyone, which are connect and
            //       temporary, so the schema creation below would fail once the role is assumed. The
            //       database name is interpolated because the container generates it, and it is quoted
            //       as an identifier because no generated name is guaranteed to be a bare lower-case
            //       word.
            statement.execute("GRANT CREATE ON DATABASE \"" + POSTGRES.getDatabaseName()
                    + "\" TO " + OWNER_ROLE);
            statement.execute("CREATE SCHEMA " + DataSourceConfig.SCHEMA_NAME
                    + " AUTHORIZATION " + OWNER_ROLE);
        }
    }

    /**
     * Returns the account table to the same five ascending rows before every case.
     *
     * <p>Trade-offs: the table is emptied and refilled per case rather than filled once for the class.
     * The cost is five statements per case; what it buys is that a row written or version-bumped by one
     * case cannot change an order, a count or a version in another, which is the failure hardest to read
     * correctly.</p>
     *
     * <p>This setup step takes no parameter and returns no value.</p>
     */
    @BeforeEach
    void resetToSeededAccounts() {
        this.jdbc.execute("TRUNCATE " + DataSourceConfig.SCHEMA_NAME + ".accounts");
        for (Long identifier : SEEDED_IDS) {
            insertAccount(identifier);
        }
    }

    /**
     * Confirms the migration this context owns ran inside the container and left the schema owned by the
     * role the deployment gives it.
     *
     * <p>Assumptions: ownership is asserted rather than taken for granted because the non-profile
     * document's default-privilege grants are keyed on the owning role. A schema created by the
     * container's generated user instead would leave every one of those grants inert while the migration
     * itself still reported success, so this is the assertion that distinguishes the two.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account migration applied inside the container under the owning role")
    void theAccountMigrationAppliedUnderTheOwningRole() {
        assertThat(this.jdbc.queryForObject(
                        "SELECT count(*) FROM " + DataSourceConfig.SCHEMA_NAME
                                + ".flyway_schema_history WHERE version = '1' AND success = true",
                        Integer.class))
                .as("the account migration must be recorded as applied and successful")
                .isEqualTo(1);

        assertThat(this.jdbc.queryForObject(
                        "SELECT pg_catalog.pg_get_userbyid(nspowner) FROM pg_catalog.pg_namespace"
                                + " WHERE nspname = ?",
                        String.class, DataSourceConfig.SCHEMA_NAME))
                .as("the schema must belong to the owning role, or the default-privilege grants are"
                        + " inert")
                .isEqualTo(OWNER_ROLE);
    }

    /**
     * Confirms the account table's thirteen columns carry the declared types, widths, scales and domain
     * the copybook and the migration between them specify.
     *
     * <p>Assumptions: twelve of the thirteen come from the copybook's twelve named fields at L5 through
     * L16 of {@code app/cpy/CVACT01Y.cpy}, and the thirteenth is the version column, which the reference
     * has no equivalent of because it compared a snapshot instead. The {@code FILLER PIC X(178)} at L17
     * is dropped rather than stored: it pads a fixed-length record and carries no value, so a column for
     * it would store 178 spaces per row and invite a reader to look for meaning in them.</p>
     *
     * <p>Assumptions: the five amount columns are asserted at precision 12 and scale 2 rather than merely
     * as numeric. The copybook declares each as {@code PIC S9(10)V99}, which is ten integral digits and
     * two decimal ones, and a numeric column left unconstrained would accept a third decimal digit and
     * store it, which is the silent way an exact amount stops being exact.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account table carries its thirteen declared columns, widths, scales and domain")
    void theAccountTableCarriesItsDeclaredContract() {
        assertThat(this.jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema = ?"
                                + " AND table_name = 'accounts'",
                        Integer.class, DataSourceConfig.SCHEMA_NAME))
                .as("twelve copybook fields plus the version column, and nothing else")
                .isEqualTo(13);

        assertThat(typeOf("account_id")).isEqualTo("bigint");
        assertThat(typeOf("active_status")).isEqualTo("character");
        assertThat(lengthOf("active_status")).isEqualTo(1);

        for (String amount : List.of("curr_bal", "credit_limit", "cash_credit_limit",
                "curr_cyc_credit", "curr_cyc_debit")) {
            assertThat(typeOf(amount)).as("%s stores an exact amount", amount).isEqualTo("numeric");
            assertThat(precisionOf(amount)).as("%s declares ten integral digits", amount).isEqualTo(12);
            assertThat(scaleOf(amount)).as("%s declares two decimal digits", amount)
                    .isEqualTo(Money.SCALE);
        }

        assertThat(typeOf("version"))
                .as("optimistic concurrency stands in for the reference's snapshot comparison")
                .isEqualTo("bigint");

        for (String column : List.of("account_id", "active_status", "curr_bal", "credit_limit",
                "cash_credit_limit", "open_date", "expiration_date", "reissue_date",
                "curr_cyc_credit", "curr_cyc_debit", "addr_zip", "group_id", "version")) {
            assertThat(nullableOf(column)).as("%s is mandatory in a fixed-length record", column)
                    .isEqualTo("NO");
        }

        assertThat(this.jdbc.queryForObject(
                        "SELECT pg_catalog.pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint"
                                + " WHERE conrelid = ?::regclass"
                                + " AND conname = 'ck_accounts_active_status'",
                        String.class, DataSourceConfig.SCHEMA_NAME + ".accounts"))
                .as("the status domain is closed at the two values the reference admits")
                .contains("'Y'", "'N'");
    }

    /**
     * Confirms no card-verification-value column exists on the account table at all.
     *
     * <p>Assumptions: the absence is asserted rather than any masking of such a value, and the
     * distinction is the whole point of the case. {@code app/cpy/CVACT01Y.cpy} declares no verification
     * value anywhere among its twelve named fields at L5 through L16, so the account master never held
     * one; that value belongs to the card record, which another context owns. An assertion that such a
     * column came back masked would imply the column exists and is merely hidden, which would describe
     * storage this table does not have and would quietly license a later change to add it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account table declares no card-verification-value column of any spelling")
    void theAccountTableDeclaresNoCardVerificationValueColumn() {
        assertThat(this.jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                                + " AND table_name = 'accounts'"
                                + " AND (column_name LIKE '%cvv%' OR column_name LIKE '%verification%')",
                        String.class, DataSourceConfig.SCHEMA_NAME))
                .as("the account master never carried a verification value, so none is stored or masked")
                .isEmpty();
    }

    /**
     * Confirms a keyed read returns the stored row and that an unknown key is answered rather than
     * raised.
     *
     * <p>Assumptions: the repository declares no keyed read of its own and inherits
     * {@code findById(Long)}, which is the migrated form of paragraph
     * {@code 9300-GETACCTDATA-BYACCT.} at L774 of {@code app/cbl/COACTVWC.cbl}. The reference reads that
     * record with a response code it then examines, so a key that is not present is an outcome there and
     * not a failure; an empty result models that exactly, whereas raising would turn a routine
     * not-found into an error path the reference does not have.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a keyed read returns the stored row and answers an unknown key with an empty result")
    void aKeyedReadReturnsTheRowAndAnswersAnUnknownKeyEmpty() {
        Optional<Account> found = this.repository.findById(FIRST_ACCOUNT_ID);

        assertThat(found).isPresent();
        assertThat(found.get().getAccountId()).isEqualTo(FIRST_ACCOUNT_ID);
        assertThat(found.get().getActiveStatus()).isEqualTo(SEEDED_ACTIVE_STATUS);

        assertThat(this.repository.findById(ABSENT_ACCOUNT_ID))
                .as("an absent key is an outcome the reference examines, not an exception it raises")
                .isEmpty();
    }

    /**
     * Confirms the primary key is the one the caller assigns rather than one the engine invents.
     *
     * <p>Assumptions: {@code ACCT-ID} at L5 of {@code app/cpy/CVACT01Y.cpy} is {@code PIC 9(11)} and is
     * the record's own key in the reference, where every writer supplies it. The mapped identifier
     * therefore carries no generation strategy, and this case proves that by writing a value no sequence
     * would have produced next and reading the same value back. Were a strategy present the row would
     * come back under a different key and every cross-reference to it would dangle.</p>
     *
     * <p>Assumptions: this one case writes through the repository rather than seeding by statement,
     * because the assigned key travels on the write path and is precisely what is under test here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the primary key is assigned by the caller and never generated")
    void thePrimaryKeyIsAssignedByTheCallerAndNeverGenerated() {
        long chosen = 42424242424L;

        this.repository.saveAndFlush(accountOf(chosen, new BigDecimal(SEEDED_CURRENT_BALANCE),
                new BigDecimal(SEEDED_CYCLE_DEBIT), LocalDate.parse(SEEDED_OPEN_DATE)));

        assertThat(this.repository.findById(chosen))
                .as("an engine-generated key would have replaced the eleven-digit value supplied")
                .isPresent()
                .get()
                .extracting(Account::getAccountId)
                .isEqualTo(chosen);
    }

    /**
     * Confirms all five amount columns round-trip as exact decimals at two decimal places.
     *
     * <p>Assumptions: every amount in the account master is zoned decimal with a sign overpunch in the
     * reference, declared {@code PIC S9(10)V99} at L7, L8, L9, L13 and L14 of
     * {@code app/cpy/CVACT01Y.cpy}. The migrated storage is exact decimal at scale 2 end to end, so the
     * scale is asserted on the value that came back from the engine rather than on the one handed to it:
     * a scale set only in the caller would prove nothing about the column.</p>
     *
     * <p>Assumptions: a binary floating-point type is absent from this path by construction rather than
     * by care taken here, and no value below is built from one. That prohibition has one owner, the
     * architecture rules in {@code common-lib}, and is not restated as an assertion here; what is
     * asserted is the behaviour it exists to protect.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all five amount columns round-trip as exact decimals at two decimal places")
    void allFiveAmountColumnsRoundTripAtExactlyTwoDecimalPlaces() {
        Account stored = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();

        List<BigDecimal> amounts = List.of(stored.getCurrentBalance(), stored.getCreditLimit(),
                stored.getCashCreditLimit(), stored.getCurrentCycleCredit(),
                stored.getCurrentCycleDebit());
        for (BigDecimal amount : amounts) {
            assertThat(amount.scale())
                    .as("an amount read back at any other scale is no longer the stored amount")
                    .isEqualTo(Money.SCALE);
        }

        assertThat(stored.getCurrentBalance()).isEqualTo(new BigDecimal(SEEDED_CURRENT_BALANCE));
        assertThat(stored.getCreditLimit()).isEqualTo(new BigDecimal(SEEDED_CREDIT_LIMIT));
        assertThat(stored.getCashCreditLimit()).isEqualTo(new BigDecimal(SEEDED_CASH_CREDIT_LIMIT));
        assertThat(stored.getCurrentCycleCredit()).isEqualTo(new BigDecimal(SEEDED_CYCLE_CREDIT));
        assertThat(stored.getCurrentCycleDebit()).isEqualTo(new BigDecimal(SEEDED_CYCLE_DEBIT));
    }

    /**
     * Confirms an amount carrying a third decimal digit is reduced half-up before it is stored, and comes
     * back at the reduced value.
     *
     * <p>Assumptions: reduction is the shared money type's decision and not this table's, so the value is
     * put through {@code com.carddemo.common.money.Money} rather than rounded here. Its general rounding
     * mode is half-up, so a third decimal digit of five rounds the second one away from zero: the value
     * written below is 193.005 and the value stored is therefore 193.01, not 193.00. Handing the
     * unreduced value straight to the column was rejected as the vehicle for this case, because the
     * engine would also reduce it and the case would then pass without the money type being involved at
     * all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a third decimal digit is reduced half-up before storage and reads back reduced")
    void aThirdDecimalDigitIsReducedHalfUpBeforeStorage() {
        Money reduced = Money.of(new BigDecimal("193.005"));

        assertThat(reduced.amount())
                .as("half-up carries the second decimal digit away from zero")
                .isEqualTo(new BigDecimal("193.01"));

        Account updated = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();
        updated.setCurrentBalance(reduced.amount());
        this.repository.saveAndFlush(updated);

        assertThat(this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow().getCurrentBalance())
                .isEqualTo(new BigDecimal("193.01"));
    }

    /**
     * Confirms three of the five ten-byte copybook fields became dates and the other two did not.
     *
     * <p>Assumptions: five fields in {@code app/cpy/CVACT01Y.cpy} are declared {@code PIC X(10)} and
     * share one width exactly: {@code ACCT-OPEN-DATE} at L10, {@code ACCT-EXPIRAION-DATE} at L11 and
     * {@code ACCT-REISSUE-DATE} at L12, then {@code ACCT-ADDR-ZIP} at L15 and {@code ACCT-GROUP-ID} at
     * L16. Only the first three narrow to a date column. The last two remain character values despite
     * the identical declared width, and this case is the guard against a rule that decided storage from
     * width alone: the seeded postal code begins with a letter and the seeded group key is a padded word,
     * so a width-driven conversion would have failed on both, and one that somehow succeeded would have
     * silently changed what they mean.</p>
     *
     * <p>Assumptions: the narrowing is safe in the first place only because the stored form is already
     * ordered most-significant-first. Comparing the three fields as text and comparing them as dates
     * therefore select the same rows, which is what this case checks rather than assumes; had the
     * reference stored them in any other field order the two comparisons would diverge and the narrowing
     * would change results.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("only the three date fields narrow to dates and their ordering survives the narrowing")
    void onlyTheThreeDateFieldsNarrowAndOrderingSurvives() {
        for (String dateColumn : List.of("open_date", "expiration_date", "reissue_date")) {
            assertThat(typeOf(dateColumn)).as("%s narrows to a date", dateColumn).isEqualTo("date");
        }

        for (String characterColumn : List.of("addr_zip", "group_id")) {
            assertThat(typeOf(characterColumn))
                    .as("%s shares the ten-byte width of a date field and is not one", characterColumn)
                    .isEqualTo("character");
            assertThat(lengthOf(characterColumn)).isEqualTo(TEN_BYTE_WIDTH);
        }

        String bound = "2012-10-14";
        Integer byDate = this.jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE open_date < ?",
                Integer.class, LocalDate.parse(bound));
        Integer byText = this.jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE to_char(open_date, 'YYYY-MM-DD') < ?",
                Integer.class, bound);
        assertThat(byDate)
                .as("a most-significant-first stored form makes text order and date order the same order")
                .isEqualTo(byText)
                .isEqualTo(2);

        Account stored = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();
        assertThat(stored.getOpenDate()).isEqualTo(LocalDate.parse(SEEDED_OPEN_DATE));
        assertThat(stored.getExpirationDate()).isEqualTo(LocalDate.parse(SEEDED_EXPIRATION_DATE));
        assertThat(stored.getReissueDate()).isEqualTo(LocalDate.parse(SEEDED_REISSUE_DATE));
        assertThat(stored.getAddressZip())
                .as("a value a width rule would have converted stays the characters it is")
                .isEqualTo(SEEDED_ADDR_ZIP);
        assertThat(stored.getGroupId()).isEqualTo(SEEDED_GROUP_ID);
    }

    /**
     * Confirms the all-positive 300-byte fixture decodes through the registered layout and describes the
     * same account the stored row does.
     *
     * <p>Assumptions: the byte positions are taken from the layout registered as {@code ACCOUNT} in
     * {@code com.carddemo.common.codec.CopybookLayout} rather than written out here, and that is a
     * correctness requirement rather than tidiness. The record does not group its fields by kind: three
     * amounts are followed by the three dates, then two further amounts, then the two ten-byte character
     * fields. A reader that assumed the five amounts were adjacent would slice a date where it expected
     * an amount and would still consume exactly 300 bytes, so the error would surface as wrong values
     * rather than as a length failure. The house rule that a layout is single-sourced and never
     * duplicated is recorded at L540 through L542 of {@code tests/README.md}.</p>
     *
     * <p>Assumptions: the layout's own registered record length is asserted against the fixture's actual
     * byte count, so a fixture that drifted from the declared 300 fails here and not later as a decode
     * whose fields have quietly shifted.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath, which is a broken harness
     *     rather than a failed assertion and is reported as one
     */
    @Test
    @DisplayName("the all-positive fixture decodes through the registered layout and matches the row")
    void theAllPositiveFixtureDecodesAndMatchesTheStoredRow() throws IOException {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout("ACCOUNT");
        byte[] image = fixtureBytes(VALID_FIXTURE);

        assertThat(spec.reclen()).isEqualTo(RECORD_LENGTH);
        assertThat(image).hasSize(RECORD_LENGTH);

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image, spec);

        assertThat(decoded.get("ACCT-ID")).isEqualTo(FIRST_ACCOUNT_ID);
        assertThat(decoded.get("ACCT-ACTIVE-STATUS")).isEqualTo(SEEDED_ACTIVE_STATUS);
        assertThat(decoded.get("ACCT-CURR-BAL")).isEqualTo(new BigDecimal(SEEDED_CURRENT_BALANCE));
        assertThat(decoded.get("ACCT-CREDIT-LIMIT")).isEqualTo(new BigDecimal(SEEDED_CREDIT_LIMIT));
        assertThat(decoded.get("ACCT-CASH-CREDIT-LIMIT"))
                .isEqualTo(new BigDecimal(SEEDED_CASH_CREDIT_LIMIT));
        assertThat(decoded.get("ACCT-OPEN-DATE")).isEqualTo(SEEDED_OPEN_DATE);
        assertThat(decoded.get("ACCT-EXPIRAION-DATE"))
                .as("the baseline spelling names the field in the image; the column is expiration_date")
                .isEqualTo(SEEDED_EXPIRATION_DATE);
        assertThat(decoded.get("ACCT-REISSUE-DATE")).isEqualTo(SEEDED_REISSUE_DATE);
        assertThat(decoded.get("ACCT-CURR-CYC-CREDIT")).isEqualTo(new BigDecimal(SEEDED_CYCLE_CREDIT));
        assertThat(decoded.get("ACCT-CURR-CYC-DEBIT")).isEqualTo(new BigDecimal(SEEDED_CYCLE_DEBIT));
        assertThat(decoded.get("ACCT-ADDR-ZIP")).isEqualTo(SEEDED_ADDR_ZIP);
        assertThat(decoded.get("ACCT-GROUP-ID")).isEqualTo(SEEDED_GROUP_ID);

        Account stored = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();
        assertThat(stored.getCurrentBalance()).isEqualTo(decoded.get("ACCT-CURR-BAL"));
        assertThat(stored.getGroupId())
                .as("a fixed-width column returns the padded value the image carries")
                .isEqualTo(decoded.get("ACCT-GROUP-ID"));
    }

    /**
     * Confirms the negative fixture decodes both signed overpunch shapes and that the engine stores and
     * returns them unchanged.
     *
     * <p>Assumptions: this is the only negative money vector in this module, and it exercises the codec
     * and the column together rather than either alone. A decode asserted in isolation would leave open
     * whether the column keeps the sign, and a stored literal asserted in isolation would leave open
     * whether the overpunch was read correctly; writing the decoded values and reading them back closes
     * both. The two shapes are the row-head character of the negative overpunch sequence, standing for a
     * negative zero digit, and a mid-sequence letter standing for a negative five, so one record covers
     * both ends of that mapping.</p>
     *
     * <p>Assumptions: the write below lands on the row the fixture's own identifier names, which the seed
     * has already created, so it is an update of that row rather than an insert of a second one. That is
     * the identifier the fixture carries and the reason it differs from the all-positive fixture's.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the fixture cannot be read from the classpath, which is a broken harness
     *     rather than a failed assertion and is reported as one
     */
    @Test
    @DisplayName("the negative fixture decodes both overpunch shapes and they survive storage")
    void theNegativeFixtureDecodesBothOverpunchShapesAndTheySurviveStorage() throws IOException {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout("ACCOUNT");
        byte[] image = fixtureBytes(NEGATIVE_FIXTURE);
        assertThat(image).hasSize(RECORD_LENGTH);

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image, spec);

        assertThat(decoded.get("ACCT-ID")).isEqualTo(SECOND_ACCOUNT_ID);
        assertThat(decoded.get("ACCT-CURR-BAL"))
                .as("the row-head overpunch character stands for a negative zero digit")
                .isEqualTo(new BigDecimal("-919.00"));
        assertThat(decoded.get("ACCT-CURR-CYC-DEBIT"))
                .as("a mid-sequence overpunch letter stands for a negative five")
                .isEqualTo(new BigDecimal("-193.45"));

        // WHY : Assumptions: the same bytes are put through the money-typed entry point as well, because
        //       the record decoder yields a plain decimal while the shared money type is what the
        //       service layer carries. The interval and the digit counts are taken from the registered
        //       field rather than written here, for the reason given in this case's sibling above.
        CopybookLayout.FieldSpec balanceField = spec.field("ACCT-CURR-BAL");
        Money asMoney = ZonedDecimalCodec.decodeMoney(
                new String(image, balanceField.start(), balanceField.length(),
                        StandardCharsets.US_ASCII),
                balanceField.intDigits(), balanceField.signed());
        assertThat(asMoney.amount()).isEqualTo(new BigDecimal("-919.00"));

        this.repository.saveAndFlush(accountOf(SECOND_ACCOUNT_ID,
                (BigDecimal) decoded.get("ACCT-CURR-BAL"),
                (BigDecimal) decoded.get("ACCT-CURR-CYC-DEBIT"),
                LocalDate.parse((String) decoded.get("ACCT-OPEN-DATE"))));

        Account stored = this.repository.findById(SECOND_ACCOUNT_ID).orElseThrow();
        assertThat(stored.getCurrentBalance()).isEqualTo(new BigDecimal("-919.00"));
        assertThat(stored.getCurrentCycleDebit()).isEqualTo(new BigDecimal("-193.45"));
        assertThat(stored.getCurrentBalance().scale()).isEqualTo(Money.SCALE);
        assertThat(Money.of(stored.getCurrentBalance()).isNegative())
                .as("a sign lost anywhere on this path would read back as a positive balance")
                .isTrue();
    }

    /**
     * Confirms the version column advances when a row is updated through the repository.
     *
     * <p>Assumptions: the reference detects a competing update by keeping a snapshot of the record it is
     * about to write and comparing it back. That snapshot is {@code 05 ACUP-OLD-DETAILS.} at L669 of
     * {@code app/cbl/COACTUPC.cbl}, whose first member is
     * {@code 15  ACUP-OLD-ACCT-ID-X                 PIC X(11).} at L671 with a numeric
     * {@code REDEFINES} at L672 and L673, and which runs to L756 because
     * {@code 05 ACUP-NEW-DETAILS.} begins at L757. The comparison is driven from
     * {@code 05  WS-DATACHANGED-FLAG                   PIC X(1).} at L168 with its two conditions at
     * L169 and L170, and is performed by {@code 9700-CHECK-CHANGE-IN-REC.} at L4109 before
     * {@code 9600-WRITE-PROCESSING.} at L3888 commits at L953. The Java carries a version column instead
     * and the divergence is documented; nothing in the baseline is altered.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the version column advances on an update made through the repository")
    void theVersionColumnAdvancesOnAnUpdate() {
        Account first = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();
        assertThat(first.getVersion()).isZero();

        first.setCurrentBalance(new BigDecimal("250.00"));
        this.repository.saveAndFlush(first);

        assertThat(this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow().getVersion())
                .as("an unchanging version would leave a competing update undetectable")
                .isEqualTo(1L);
        assertThat(storedVersion(FIRST_ACCOUNT_ID))
                .as("the advance is the engine's, so it is confirmed in the row and not only in the map")
                .isEqualTo(1L);
    }

    /**
     * Confirms a write carrying a superseded version is refused and leaves the stored row untouched.
     *
     * <p>Assumptions: the competing update is applied with a plain statement so that the mapping never
     * sees it, which is what makes the instance held here genuinely stale. Writing it through a second
     * managed instance was rejected: the two would share one context, the version held in memory would
     * already have advanced, and the case would then prove only that the mapping is self-consistent.</p>
     *
     * <p>Assumptions: the refusal observed is the framework's translated form,
     * {@code org.springframework.dao.OptimisticLockingFailureException}, and not the persistence
     * provider's own exception. A caller of this repository sees the translated type because the
     * repository translates, so asserting the provider's type would assert something no caller can
     * observe. What the refusal is turned into at the boundary, an HTTP 409 carrying the message declared
     * at L521 and L522 of {@code app/cbl/COACTUPC.cbl} as
     * {@code 'Record changed by some one else. Please review'}, belongs to
     * {@code com.carddemo.common.error.GlobalExceptionHandler}; no status is asserted here.</p>
     *
     * <p>Assumptions: no lock of any kind is taken to produce this outcome, and none is available to
     * take. The reference never held a read lock across a terminal turn either, which is the reason it
     * needs the snapshot at all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a write carrying a superseded version is refused and the stored row is untouched")
    void aWriteCarryingASupersededVersionIsRefused() {
        Account stale = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();
        assertThat(stale.getVersion()).isZero();

        this.jdbc.update("UPDATE accounts SET curr_bal = ?, version = version + 1"
                + " WHERE account_id = ?", new BigDecimal("777.77"), FIRST_ACCOUNT_ID);

        stale.setCurrentBalance(new BigDecimal("1.23"));

        assertThatThrownBy(() -> this.repository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        Account survivor = this.repository.findById(FIRST_ACCOUNT_ID).orElseThrow();
        assertThat(survivor.getCurrentBalance())
                .as("the refused write must not have landed even partly")
                .isEqualTo(new BigDecimal("777.77"));
        assertThat(survivor.getVersion()).isEqualTo(1L);
    }

    /**
     * Confirms the opening window returns the lowest rows in ascending order and reports that more
     * follow.
     *
     * <p>Alternatives Considered: reaching a window of rows by counted distance, asking the engine to
     * pass over a tally of rows and return what comes after them. Rejected on observable behaviour rather
     * than on taste: under concurrent inserts a read of that shape omits rows and repeats rows, because
     * the tally is evaluated against whatever the table holds at the moment the second read runs, whereas
     * a read resuming from the last key it actually returned can do neither, the key naming a row rather
     * than a distance. The reference never counted rows either; it walked the file in key order, which is
     * the triad at L317, L165 and L388 of {@code app/cbl/CBACT01C.cbl} named in this class's opening
     * block.</p>
     *
     * <p>Assumptions: the read asks for one row more than the window shows, and that surplus row is what
     * settles whether more follow. The boundary published is the key of the LAST ROW SHOWN and never the
     * surplus row's, which this case checks by opening the sealed boundary and comparing it: a boundary
     * taken from the surplus row would silently omit that row from the next read.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the opening window is ascending, bounded, and reports the rows that follow it")
    void theOpeningWindowIsAscendingAndReportsThatMoreFollow() {
        PageResponse<Account> opening =
                windowOf(this.repository.findAllByOrderByAccountIdAsc(READ_BOUND));

        assertThat(identifiersOf(opening.items())).containsExactly(11L, 12L);
        assertThat(opening.hasNext())
                .as("the surplus row proves a further window exists without counting the table")
                .isTrue();
        assertThat(CURSOR_TOKENS.open(SCAN_BINDING, opening.lastKey()))
                .as("the trailing boundary is the last row SHOWN, not the surplus row read past it")
                .isEqualTo("12");
        assertThat(CURSOR_TOKENS.open(SCAN_BINDING, opening.firstKey())).isEqualTo("11");
    }

    /**
     * Confirms a resuming window begins strictly after the boundary it is given.
     *
     * <p>Assumptions: strictness is the property that makes a walk visit every row exactly once. A
     * resuming read that included its boundary would show the last row of the previous window again, and
     * one that omitted a key would lose a row entirely; both are indistinguishable from correct behaviour
     * on a table whose keys happen to be contiguous, so the assertion names the excluded key explicitly
     * rather than only checking the count.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a resuming window begins strictly after its boundary and repeats no row")
    void aResumingWindowBeginsStrictlyAfterItsBoundary() {
        PageResponse<Account> resuming = windowOf(
                this.repository.findByAccountIdGreaterThanOrderByAccountIdAsc(12L, READ_BOUND));

        assertThat(identifiersOf(resuming.items()))
                .containsExactly(13L, 14L)
                .doesNotContain(12L);
        assertThat(resuming.hasNext()).isTrue();
        assertThat(CURSOR_TOKENS.open(SCAN_BINDING, resuming.lastKey())).isEqualTo("14");
    }

    /**
     * Confirms the final window reports that nothing follows it, because no surplus row was there to
     * read.
     *
     * <p>Assumptions: five rows shown two at a time make the last window partial, and that is deliberate.
     * Had the seeded count been an exact multiple of the window the final read would have returned
     * exactly the window's worth with no surplus row, and the indicator would then read correctly by
     * coincidence rather than because the surplus row was absent. The partial window separates the two
     * cases.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the final window reports that nothing follows it")
    void theFinalWindowReportsThatNothingFollowsIt() {
        PageResponse<Account> last = windowOf(
                this.repository.findByAccountIdGreaterThanOrderByAccountIdAsc(14L, READ_BOUND));

        assertThat(identifiersOf(last.items())).containsExactly(15L);
        assertThat(last.hasNext())
                .as("no surplus row was read, so no further window may be advertised")
                .isFalse();
        assertThat(CURSOR_TOKENS.open(SCAN_BINDING, last.lastKey())).isEqualTo("15");

        assertThat(this.repository.findByAccountIdGreaterThanOrderByAccountIdAsc(15L, READ_BOUND))
                .as("a read resuming past the highest key returns nothing rather than wrapping")
                .isEmpty();
    }

    /**
     * Confirms the backward window reads strictly below its boundary in descending order and yields the
     * rows of the window that preceded it.
     *
     * <p>Assumptions: descending order is what makes the backward direction the exact counterpart of the
     * reference's read-previous rather than a re-read from the beginning of the file. The rows come back
     * highest-first and are therefore reversed for display, which is why this case asserts the descending
     * read AND the reversed sequence: asserting only the reversed sequence would pass equally well
     * against a read that had returned them ascending and could not resume.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the backward window is strictly below its boundary and yields the preceding window")
    void theBackwardWindowYieldsThePrecedingWindow() {
        List<Account> opening = this.repository.findAllByOrderByAccountIdAsc(READ_BOUND);
        PageResponse<Account> shownFirst = windowOf(opening);
        List<Account> resuming =
                this.repository.findByAccountIdGreaterThanOrderByAccountIdAsc(12L, READ_BOUND);
        PageResponse<Account> shownNext = windowOf(resuming);

        long leadingBoundary = shownNext.items().getFirst().getAccountId();
        List<Account> backward =
                this.repository.findByAccountIdLessThanOrderByAccountIdDesc(leadingBoundary,
                        READ_BOUND);

        assertThat(identifiersOf(backward))
                .as("the backward read descends from its boundary and excludes it")
                .containsExactly(12L, 11L)
                .doesNotContain(leadingBoundary);
        assertThat(identifiersOf(backward.reversed()))
                .as("reversed, the backward window is exactly the window that preceded the current one")
                .isEqualTo(identifiersOf(shownFirst.items()));

        assertThat(this.repository.findByAccountIdLessThanOrderByAccountIdDesc(11L, READ_BOUND))
                .as("stepping back from the lowest key returns nothing rather than wrapping")
                .isEmpty();
    }

    /**
     * Confirms an unqualified table name resolves, because the session is pinned to this context's
     * schema.
     *
     * <p>Assumptions: {@code com.carddemo.account.config.DataSourceConfig} holds the schema name as a
     * single named constant and pins the session search path to it, so it is the one owner of that
     * question and no query in this package names a schema. Every read in this class that goes through
     * the repository already relies on the pinning silently; this case makes the reliance explicit, so a
     * change that unpinned it fails here with a plain message rather than as a relation-not-found while
     * some later context starts.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unqualified table name resolves through the pinned session search path")
    void anUnqualifiedTableNameResolvesThroughThePinnedSearchPath() {
        assertThat(this.jdbc.queryForObject("SHOW search_path", String.class))
                .as("a search path naming anything else would resolve an unqualified name elsewhere")
                .isEqualTo(DataSourceConfig.SCHEMA_NAME);

        assertThat(this.jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class))
                .as("the table is reached without being qualified, exactly as the mapping reaches it")
                .isEqualTo(SEEDED_IDS.size());
    }

    /**
     * Confirms the sessions these cases run in read only committed data.
     *
     * <p>Trade-offs: the reference is the looser of the two, not the stricter, and the compromise is
     * accepted in the only direction that is safe. Every one of the eight file resources in
     * {@code app/csd/CARDDEMO.CSD} is declared to read without regard to uncommitted change: for the
     * account file defined by {@code DEFINE FILE(ACCTDAT) GROUP(CARDDEMO)} at L1, whose data set name
     * follows immediately at L2, the {@code READINTEG(UNCOMMITTED)} operand appears at L3 alongside
     * {@code LSRPOOLNUM(1)} and {@code DSNSHARING(ALLREQS)}. The isolation asserted below is strictly
     * stronger, so a read here may decline to show something the reference would have shown and never the
     * reverse. What is given up is that the reference's tolerance of an uncommitted read is not
     * reproduced; what is bought is that no case in this package can depend on it. No test here asserts
     * dirty-read behaviour, and one written the other way round would be asserting a weakness the target
     * does not have.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("these sessions read only committed data, which is stricter than the reference")
    void theseSessionsReadOnlyCommittedData() {
        assertThat(this.jdbc.queryForObject("SHOW transaction_isolation", String.class))
                .as("a weaker level than the reference's would be the one direction that is unsafe")
                .isEqualTo("read committed");
    }

    /**
     * Writes one account row carrying every column the migration declares mandatory.
     *
     * <p>Assumptions: the open date advances by one day per identifier so that the seeded rows differ in
     * a date column as well as in their key. A seed in which every date were identical could not
     * distinguish a date column that ordered correctly from one that did not order at all.</p>
     *
     * @param identifier the account identifier to write, which is also the assigned primary key
     */
    private void insertAccount(long identifier) {
        this.jdbc.update("""
                INSERT INTO accounts (
                    account_id, active_status, curr_bal, credit_limit, cash_credit_limit,
                    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit,
                    addr_zip, group_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                identifier,
                SEEDED_ACTIVE_STATUS,
                new BigDecimal(SEEDED_CURRENT_BALANCE),
                new BigDecimal(SEEDED_CREDIT_LIMIT),
                new BigDecimal(SEEDED_CASH_CREDIT_LIMIT),
                LocalDate.parse(SEEDED_OPEN_DATE).plusDays(identifier - FIRST_ACCOUNT_ID),
                LocalDate.parse(SEEDED_EXPIRATION_DATE),
                LocalDate.parse(SEEDED_REISSUE_DATE),
                new BigDecimal(SEEDED_CYCLE_CREDIT),
                new BigDecimal(SEEDED_CYCLE_DEBIT),
                SEEDED_ADDR_ZIP,
                SEEDED_GROUP_ID);
    }

    /**
     * Builds an unsaved account carrying the mandatory columns, varying only what a case needs to vary.
     *
     * @param identifier the assigned primary key to carry
     * @param balance the posted balance as an exact decimal at two decimal places
     * @param cycleDebit the cycle debit as an exact decimal at two decimal places
     * @param openDate the open date to carry
     * @return an unsaved account instance at version zero, never {@code null}
     */
    private Account accountOf(long identifier, BigDecimal balance, BigDecimal cycleDebit,
            LocalDate openDate) {
        return new Account(identifier, SEEDED_ACTIVE_STATUS, balance,
                new BigDecimal(SEEDED_CREDIT_LIMIT), new BigDecimal(SEEDED_CASH_CREDIT_LIMIT),
                openDate, LocalDate.parse(SEEDED_EXPIRATION_DATE),
                LocalDate.parse(SEEDED_REISSUE_DATE), new BigDecimal(SEEDED_CYCLE_CREDIT),
                cycleDebit, SEEDED_ADDR_ZIP, SEEDED_GROUP_ID);
    }

    /**
     * Assembles the window a caller would receive from the rows one read returned.
     *
     * <p>Assumptions: this stands in for the service layer, which is where the boundaries are sealed. The
     * repository yields entities in key order and nothing else, so the surplus row is discarded and the
     * two boundaries are taken from the first and last rows SHOWN. The envelope refuses a row-bearing
     * window that names no boundary and refuses a further-window claim with no trailing boundary, so an
     * exhausted read answers with the envelope's own empty form rather than with boundaries sealed over
     * nothing.</p>
     *
     * @param readRows the rows one read returned, at most one more than a window shows; must not be
     *     {@code null}
     * @return the assembled window, never {@code null}
     */
    private PageResponse<Account> windowOf(List<Account> readRows) {
        boolean hasSurplus = readRows.size() > WINDOW_ROWS;
        List<Account> shown = hasSurplus ? readRows.subList(0, WINDOW_ROWS) : readRows;
        if (shown.isEmpty()) {
            return PageResponse.empty();
        }
        return PageResponse.ofRows(shown,
                CURSOR_TOKENS.seal(SCAN_BINDING, String.valueOf(shown.getFirst().getAccountId())),
                CURSOR_TOKENS.seal(SCAN_BINDING, String.valueOf(shown.getLast().getAccountId())),
                hasSurplus);
    }

    /**
     * Extracts the identifiers of a sequence of rows, preserving the order the read returned them in.
     *
     * @param rows the rows to read identifiers from; must not be {@code null}
     * @return the identifiers in the same order, never {@code null}
     */
    private static List<Long> identifiersOf(List<Account> rows) {
        return rows.stream().map(Account::getAccountId).toList();
    }

    /**
     * Reads one row's stored version directly, bypassing the mapping.
     *
     * @param identifier the account identifier to read
     * @return the version the row holds, never {@code null} for an existing row
     */
    private Long storedVersion(long identifier) {
        return this.jdbc.queryForObject("SELECT version FROM accounts WHERE account_id = ?",
                Long.class, identifier);
    }

    /**
     * Reads one fixed-width fixture from the classpath as the exact bytes it holds.
     *
     * <p>Assumptions: the resource is read as bytes rather than as text, and that is the whole reason this
     * helper exists. A sign overpunch is a character whose meaning is positional, and a fixed-width record
     * is padded to a declared length; routing either through a reader that trimmed, normalised a line
     * ending or substituted an unmappable character would alter the record while leaving it plausible.</p>
     *
     * @param resource the absolute classpath location of the fixture; must not be {@code null}
     * @return the fixture's exact bytes, never {@code null}
     * @throws IOException if the resource cannot be read
     * @throws IllegalStateException if the resource is absent, which means the fixture was renamed or
     *     removed and is reported as a broken harness rather than as a failed assertion
     */
    private static byte[] fixtureBytes(String resource) throws IOException {
        try (InputStream stream = AccountRepositoryIT.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "fixture " + resource + " is absent from the test classpath");
            }
            return stream.readAllBytes();
        }
    }

    /**
     * Reads one column's declared type from the engine's catalog.
     *
     * @param column the column name; must not be {@code null}
     * @return the declared type as the catalog reports it, never {@code null} for a declared column
     */
    private String typeOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = ?"
                        + " AND table_name = 'accounts' AND column_name = ?",
                String.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * Reads one character column's declared width from the engine's catalog.
     *
     * @param column the column name; must not be {@code null}
     * @return the declared width, never {@code null} for a character column
     */
    private Integer lengthOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = 'accounts' AND column_name = ?",
                Integer.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * Reads one numeric column's declared total digit count from the engine's catalog.
     *
     * @param column the column name; must not be {@code null}
     * @return the declared total digit count, never {@code null} for a constrained numeric column
     */
    private Integer precisionOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT numeric_precision FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = 'accounts' AND column_name = ?",
                Integer.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * Reads one numeric column's declared decimal digit count from the engine's catalog.
     *
     * @param column the column name; must not be {@code null}
     * @return the declared decimal digit count, never {@code null} for a constrained numeric column
     */
    private Integer scaleOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT numeric_scale FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = 'accounts' AND column_name = ?",
                Integer.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * Reads whether one column admits a null, as the engine's catalog reports it.
     *
     * @param column the column name; must not be {@code null}
     * @return {@code "YES"} or {@code "NO"}, never {@code null} for a declared column
     */
    private String nullableOf(String column) {
        return this.jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = ?"
                        + " AND table_name = 'accounts' AND column_name = ?",
                String.class, DataSourceConfig.SCHEMA_NAME, column);
    }

    /**
     * The narrowest context that can start this class: this context's entities and repositories, and
     * nothing else.
     *
     * <p>Assumptions: {@code @SpringBootConfiguration} performs no component scan of its own, so the api,
     * service, mapper and config packages are absent from this context. That is deliberate: none of them
     * is under test here, and one of them would otherwise pull the resource-server filter chain, the
     * key-management client and the reference-context adapter into a class about one table and four
     * queries.</p>
     *
     * <p>Trade-offs: two auto-configurations are excluded by name rather than neutralised by property.
     * The resource-server one builds its decoder eagerly and would issue a discovery request against the
     * deliberately unroutable test issuer; the queue one would construct a client for a transport no
     * query here uses. Excluding them keeps this class's failure surface at the persistence layer, at the
     * cost that neither is covered here, which is correct because each is covered where it is
     * configured.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    static class AccountMasterPersistenceTestApplication {
    }
}
