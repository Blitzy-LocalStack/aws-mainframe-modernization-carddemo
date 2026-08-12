package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.config.DataSourceConfig;
import com.carddemo.account.domain.CardXref;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.web.PageResponse;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds the cross-reference access paths of this context against a real PostgreSQL engine, and holds
 * the by-account path in particular against the engine's own query planner.
 *
 * <h2>Purpose</h2>
 *
 * <p>The reference system reaches {@code CARD-XREF-RECORD} two ways, and only one of them is a key of
 * the base cluster. {@code app/cbl/CBACT03C.cbl} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} at
 * L32, so an account identifier is not a key of that file at all; the account-entered read exists only
 * because a second access path was defined over the account field. This class establishes that the
 * migrated form of that second path is a real access path rather than a filtered scan, which no test
 * that substitutes the store can establish, because the subject of the assertion is the engine's plan
 * and not the query's result.</p>
 *
 * <p>The properties established here, in the order the members below assert them, are: that the
 * migration produced the non-unique secondary index the by-account path depends on; that the table
 * carries exactly the three columns the copybook declares and nothing further; that the fifty-byte
 * seed record decodes through the shared layout registry; that the keyed read entered on a card number
 * round-trips and answers an unknown card with an absent result; that the account-entered read returns
 * the row the reference read would have returned, <em>and that the engine resolves it through
 * {@code idx_card_xref_account_id} rather than by reading the table end to end</em>; that the ordered
 * windowed reads resume by key in both directions; and that an unqualified table name resolves inside
 * this context's schema.</p>
 *
 * <h2>What this class deliberately does not restate</h2>
 *
 * <p>The nine package-level rulings this class runs under are recorded once in
 * {@code com.carddemo.account.repository} package descriptor and are cited from the members below
 * rather than repeated: the naming rule that makes the {@code IT} suffix the only selector, the profile
 * annotation, this package's ownership of the connection, the schema prerequisite, position-by-key,
 * selective versioning, unqualified table names, the engine's stronger isolation, and per-case seeding.
 * The sibling members {@code AccountScreenProjectionIT} and {@code CustomerMasterRepositoryIT} already
 * assert the joined projection's outer-join arms, the migration history and the declared column widths
 * of the two master tables; nothing here duplicates them.</p>
 *
 * <h2>Why the engine is real: the plan is the subject</h2>
 *
 * <p>Alternatives Considered: an in-memory engine, or an embedded distribution of this engine, in place
 * of a container. Rejected on a ground specific to this class rather than on the general one. Three of
 * the reasons the package gives apply here as they do everywhere -- fixed-width {@code CHAR} padding,
 * binary column semantics and schema search-path pinning are all engine behaviour -- but the decisive
 * one is the fourth: this class asserts which access path a cost-based planner chooses, and a planner
 * is the one component no substitute reproduces. An index-choice assertion evaluated against a
 * different planner would pass or fail for reasons that say nothing about the deployed engine, which is
 * worse than not asserting it, because it would read as coverage.</p>
 *
 * <h2>The fifty-byte record, and the triple pinned across three files</h2>
 *
 * <p>Assumptions: the record contract is {@code app/cpy/CVACT03Y.cpy}, an eleven-line copybook whose L2
 * header declares fifty bytes. {@code 01 CARD-XREF-RECORD.} stands at L4 and carries three named
 * fields -- {@code XREF-CARD-NUM PIC X(16)} at L5, {@code XREF-CUST-ID PIC 9(09)} at L6 and
 * {@code XREF-ACCT-ID PIC 9(11)} at L7 -- followed by {@code FILLER PIC X(14)} at L8. Sixteen plus nine
 * plus eleven is thirty-six, and the fourteen padding bytes at L8 are what carry that to the declared
 * fifty; the seed record this class reads is exactly fifty bytes for that reason and no other. The
 * record holds no money field and no date field, so neither zoned-decimal sign handling nor any date
 * narrowing is in play here -- which is why no sign-overpunch byte may appear in any numeric position
 * of that seed record, in contrast to the negative-balance account vector this module also carries,
 * whose reason for existing is the requirement recorded verbatim at L273 and L274 of
 * {@code tests/README.md}.</p>
 *
 * <p>Assumptions: the seed record's three values form a triple that is pinned across three separate
 * files under this module's test resources, and the by-account and by-customer paths are exercisable
 * only because it is. The cross-reference record names card number {@code 4111111111111111}, customer
 * identifier {@code 000000011} and account identifier {@code 00000000011}; the account vector's leading
 * {@code ACCT-ID} field is that same account identifier and the customer vector's leading
 * {@code CUST-ID} field is that same customer identifier. Because the three values live in three files,
 * a change to any one of them breaks the join deliberately and visibly here rather than quietly
 * elsewhere. The card number is fabricated: it identifies no card and no holder, and the assertions
 * below treat it strictly as the sixteen-character key L5 declares.</p>
 *
 * <h2>The engine's isolation is the stronger of the two</h2>
 *
 * <p>Trade-offs: no member of this class asserts anything about reading uncommitted change, and the
 * reason is that the reference is the looser of the two rather than the stricter. Both cross-reference
 * resources are defined to read without regard to uncommitted change -- {@code READINTEG(UNCOMMITTED)}
 * stands at L40 of {@code app/csd/CARDDEMO.CSD} for the base cluster {@code CCXREF} and at L66 for the
 * second access path {@code CXACAIX} -- and each is defined alongside
 * {@code JNLSYNCWRITE(YES) RECOVERY(NONE) FWDRECOVLOG(NO)}, at L46 and L72 respectively, so the
 * reference had no rollback for this file at all. The compromise accepted is the direction nobody
 * minds: the engine's default isolation is strictly stronger, so a read here may decline to see
 * something the reference would have shown and never the reverse, and the target's transactional
 * guarantee is unconditional where the reference had none. An assertion written the other way round
 * would be asserting a weakness the target does not have.</p>
 *
 * <h2>No executable parity oracle exists for these paths</h2>
 *
 * <p>Assumptions: every assertion below is authored directly from the copybook contract and the program
 * paragraphs cited on it, because no runnable comparison exists to author them from, and that is stated
 * plainly because a reader may reasonably assume one does. Two independent statements establish it.
 * L83 through L85 of {@code tests/README.md} record that the online programs cannot be run end to end
 * without a CICS runtime, which the runner does not have, so only their extractable field-validation
 * logic is unit-tested there. And the business rules that suite asserts verbatim, from its L553 onward,
 * name the posting, interest and category-balance programs of other contexts -- with no mention
 * anywhere in that section of {@code COACTVWC}, {@code CBACT03C}, {@code CCXREF} or the two master
 * files this context owns. No golden-master comparison is therefore claimed for anything below.</p>
 *
 * <p>Assumptions: the COBOL three-layer suite under {@code tests/} is a separate tree from this Java
 * module's own test tree, is reference material with its own runners, and is neither modified nor
 * re-pinned by anything here. This class is strictly additive to it. That suite's graded condition-code
 * convention, under which a soft code still reads as success, belongs to it alone; the gate over this
 * file is pass or fail.</p>
 *
 * <p>Every reference program and copybook named in this class is read and cited only. None is modified.
 * Where the migrated behaviour differs from the reference the divergence is recorded at the member that
 * differs, rather than introduced silently.</p>
 *
 * <p>The written convention these documentation blocks follow is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Where it and the project Explainability rule appear to
 * differ, the rule governs first, {@code config/checkstyle/checkstyle.xml} second and the prose
 * standard third.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Assumptions: both remote configuration locations are switched off for this context because it
//       cannot start otherwise. The base profile imports a parameter-store location and a
//       secrets-manager location, and LOADING either builds a cloud client while configuration is still
//       being assembled -- before the profile-specific document that would supply a region has been
//       read -- so the client is constructed from an unresolved placeholder and the context aborts. Both
//       sibling integration tests in this package set the same pair for the same measured reason, and
//       the package descriptor records the full reasoning under its third ruling.
// WHY : Alternatives Considered: pointing the run at a configuration document that does not exist, which
//       is what the module's own AWS starter test does to get this module's document out of its way.
//       Rejected here for the opposite reason it was chosen there: this class asserts the schema, the
//       access paths and the plan, so it NEEDS the deployed document -- its pool sizing, its schema
//       binding, its connection pin and its migration settings are part of what is under test.
@SpringBootTest(
        classes = CardXrefRepositoryIT.CrossReferencePersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.cloud.aws.parameterstore.enabled=false",
            "spring.cloud.aws.secretsmanager.enabled=false"
        })
@ActiveProfiles("test")
class CardXrefRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository names, so
     * one cached layer set serves them all and no two classes can silently plan against different
     * engine builds. A floating tag was rejected because a registry-side rebuild could change a
     * default that alters a plan under an unchanged commit, and a plan assertion is exactly what that
     * would break.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: no initialisation script is supplied, because this context owns every table it
     * maps and the production migration is what creates them -- including the index this class asserts
     * a plan over. The type comes from {@code org.testcontainers.postgresql} rather than the deprecated
     * container package, and carries no type argument because the replacement is not generic.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The card number the fifty-byte seed record carries, and the primary key every read below resolves.
     *
     * <p>Assumptions: sixteen characters exactly, the width {@code XREF-CARD-NUM PIC X(16)} declares at
     * L5 of {@code app/cpy/CVACT03Y.cpy}. Fabricated, and never presented as a real card.</p>
     */
    private static final String FIXTURE_CARD_NUMBER = "4111111111111111";

    /** The customer identifier the seed record carries, {@code XREF-CUST-ID} at L6 of that copybook. */
    private static final long FIXTURE_CUSTOMER_ID = 11L;

    /** The account identifier the seed record carries, {@code XREF-ACCT-ID} at L7 of that copybook. */
    private static final long FIXTURE_ACCOUNT_ID = 11L;

    /**
     * The account carrying several cross-reference rows, which the non-unique index admits.
     *
     * <p>Assumptions: distinct from {@link #FIXTURE_ACCOUNT_ID} so that the single-row read and the
     * ordered windowed reads can be asserted against different accounts without one disturbing the
     * other. Its value is inside the eleven-digit range L7 of the copybook declares.</p>
     */
    private static final long MULTI_CARD_ACCOUNT_ID = 22L;

    /** The number of cross-reference rows {@link #MULTI_CARD_ACCOUNT_ID} owns. */
    private static final int MULTI_CARD_ROWS = 5;

    /**
     * An account identifier no seeded row names, used for the exhausted and absent-row assertions.
     *
     * <p>Assumptions: it must not collide with the fixture account, the several-card account or any
     * bulk row, and the seeding member below is the single place all four ranges are chosen.</p>
     */
    private static final long ACCOUNT_WITHOUT_ANY_CARD = 33L;

    /**
     * A card number no seeded row names, used to assert that an unknown key yields an absent result.
     *
     * <p>Assumptions: sixteen characters, so the assertion tests absence rather than a width refusal.</p>
     */
    private static final String CARD_NUMBER_NOT_CROSS_REFERENCED = "4111999988887777";

    /**
     * The number of unrelated rows seeded so that reading the table end to end is not the cheaper plan.
     *
     * <p>Assumptions: a plan assertion over a table small enough to sit in a page or two proves
     * nothing, because a cost-based planner would rightly read such a table end to end whether an index
     * existed or not. This many narrow rows put the table well past that point, so choosing the index
     * becomes the planner's own decision rather than an artefact of there being nothing to choose
     * between.</p>
     */
    private static final int UNRELATED_ROWS = 3000;

    /** The rows one window carries, deliberately smaller than the several-card account holds. */
    private static final int WINDOW_ROWS = 2;

    /**
     * The role that owns this context's schema and everything the migration creates inside it.
     *
     * <p>Assumptions: the same name {@code data-migration/sql/V0__schemas_and_roles.sql} uses, so the
     * two must agree. It is spelled out here rather than imported because that bootstrap document is the
     * authority for the deployed role graph and no Java constant may become a second definition of
     * it.</p>
     */
    private static final String SCHEMA_OWNER_ROLE = "carddemo_account_owner";

    /**
     * The secondary index the by-account access path resolves through.
     *
     * <p>Assumptions: created by {@code db/migration/V1__account.sql} and by nothing else, so every
     * assertion naming it depends on that migration having run inside the container first.</p>
     */
    private static final String BY_ACCOUNT_INDEX = "idx_card_xref_account_id";

    /** The logical name under which the cross-reference layout is registered in the shared registry. */
    private static final String XREF_LAYOUT_NAME = "XREF";

    /** The classpath location of the fifty-byte cross-reference seed record. */
    private static final String XREF_FIXTURE = "fixtures/xref/card-xref-valid.txt";

    /** The classpath location of the three-hundred-byte account record the triple is pinned against. */
    private static final String ACCOUNT_FIXTURE = "fixtures/account/account-valid.txt";

    /** The classpath location of the five-hundred-byte customer record the triple is pinned against. */
    private static final String CUSTOMER_FIXTURE = "fixtures/customer/customer-valid.txt";

    /** The cross-reference repository, injected as the production interface rather than a stand-in. */
    @Autowired
    private CardXrefRepository crossReferences;

    /** The context's pool, borrowed from for the catalog, plan and seeding statements. */
    @Autowired
    private DataSource dataSource;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry this method adds the container's generated JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the migration credential pair is registered beside the datasource triple
    //       because the base profile binds spring.flyway.user and spring.flyway.password to
    //       placeholders carrying no fallback, and the framework consults those keys precisely when no
    //       connection-details bean supplies them -- which is this module's case, the artifact that
    //       would contribute one being deliberately absent from its POM. The package descriptor records
    //       the full reasoning under its third ruling, including why the registration mechanism here is
    //       a dynamic property source rather than the more usual service-connection annotation.
    // WHY : Assumptions: every value is registered as a supplier rather than as a string. The container
    //       assigns its host port as it starts, so a value read at class-initialisation time would name
    //       a port that does not exist yet; a supplier is consulted after the container is running.
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
     * <p>Assumptions: {@code data-migration/sql/V0__schemas_and_roles.sql} is the exclusive authority
     * for schemas, owner roles and grants across this system -- it creates this schema under this owner
     * at its L711 -- and a throwaway container has never run it. That bootstrap is a precondition of
     * the service starting rather than part of its migration, so
     * {@code db/migration/V1__account.sql} declares no schema creation, no role and no grant, and the
     * base profile forbids the migration runner from creating a schema. Supplying the precondition here
     * is what lets the migration run under the ownership a deployment gives it, because the base
     * profile's own initialisation statement assumes this role before the first migration runs.</p>
     *
     * <p>Assumptions: this is the single most likely wiring failure in this package and it earns the
     * emphasis, because nothing reports it ahead of time. A missing schema is not a compilation error
     * and produces no warning; it arrives at RUN TIME as a context that will not start, and the
     * assertion that would have caught it never executes.</p>
     *
     * <p>Assumptions: this member runs before the Spring context exists, because the test framework
     * invokes a class-level setup method after the container extension has started the static container
     * and before the Spring extension builds the context for the first test instance. Plain JDBC is used
     * rather than the injected pool for that reason -- no pool bean exists yet -- and the schema is
     * therefore the one place in this class where a name has to be spelled out, the connection used here
     * being outside the pin that {@code DataSourceConfig} installs.</p>
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
            statement.execute("CREATE ROLE " + SCHEMA_OWNER_ROLE + " NOLOGIN");
            // WHY : Assumptions: the right to create is required on the database and is not implied by
            //       role creation -- a fresh role holds only the grants held by every role, which are
            //       the right to connect and the right to create temporary objects, so the schema
            //       creation below would be refused once the role is assumed. The database name is
            //       interpolated because the container generates it, and it is quoted as an identifier
            //       because no generated name is guaranteed to be a bare lower-case word.
            statement.execute("GRANT CREATE ON DATABASE \"" + POSTGRES.getDatabaseName()
                    + "\" TO " + SCHEMA_OWNER_ROLE);
            statement.execute("CREATE SCHEMA " + DataSourceConfig.SCHEMA_NAME
                    + " AUTHORIZATION " + SCHEMA_OWNER_ROLE);
        }
    }

    /**
     * Returns the three tables to a known state and reseeds them before every case.
     *
     * <p>Assumptions: the cross-reference row this method writes is taken from the fifty-byte seed
     * record on the classpath rather than from a literal, so the file is the input to these cases and
     * not merely a document beside them. A change to any of its three values therefore changes what is
     * seeded, and the cases below -- which assert against the constants declared at the head of this
     * class -- fail loudly rather than silently drifting.</p>
     *
     * <p>Assumptions: the two master rows are written as well as the cross-reference rows, even though
     * neither the by-account nor the by-customer path reads them, because the pinned triple is only
     * demonstrable end to end if both ends exist. Their non-key columns are fixed literals: nothing here
     * reads a wall clock, because a value compared against the current instant passes for a reason
     * unrelated to the code under test and fails only when two reads straddle a boundary. The COBOL
     * suite reaches the same conclusion for its own reason, recording at L273 and L274 of
     * {@code tests/README.md} the compiler setting its determinism depends on.</p>
     *
     * <p>Alternatives Considered: seeding through the repository under test. Rejected because it lets a
     * defect in that repository conceal itself -- a read returning nothing because the seed never
     * persisted is indistinguishable from a read whose predicate is wrong. Plain statements against the
     * pool make the seed independent of the subject, which is the ruling the package descriptor records
     * ninth.</p>
     *
     * <p>Assumptions: statistics are gathered at the end, and the plan assertion in this class cannot be
     * trusted without it. A table populated in the same session carries no statistics until they are
     * gathered, so a cost-based planner would work from built-in guesses about row count and
     * distribution rather than from this table -- and a plan chosen from a guess is not the plan the
     * deployed engine would choose. Gathering them is what makes the choice the planner's own.</p>
     *
     * <p>This setup step takes no parameter and returns no value.</p>
     *
     * @throws IOException if the seed record cannot be read from the classpath
     * @throws SQLException if any seeding statement is refused, which is a broken harness rather than a
     *     failed assertion and is reported as such
     */
    @BeforeEach
    void resetAndSeed() throws IOException, SQLException {
        Map<String, Object> seedRecord = decodeCrossReferenceFixture();
        try (Connection connection = this.dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE card_xref, accounts, customers");
            }
            seedCustomer(connection, FIXTURE_CUSTOMER_ID);
            seedAccount(connection, FIXTURE_ACCOUNT_ID);
            seedCrossReference(connection,
                    (String) seedRecord.get("XREF-CARD-NUM"),
                    (Long) seedRecord.get("XREF-CUST-ID"),
                    (Long) seedRecord.get("XREF-ACCT-ID"));
            for (int ordinal = 1; ordinal <= MULTI_CARD_ROWS; ordinal++) {
                seedCrossReference(connection, multiCardNumber(ordinal),
                        MULTI_CARD_ACCOUNT_ID, MULTI_CARD_ACCOUNT_ID);
            }
            seedUnrelatedRows(connection, UNRELATED_ROWS);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE card_xref");
            }
        }
    }

    /**
     * Confirms the migration produced the by-account index, over the account column, and non-unique.
     *
     * <p>Assumptions: an alternate index is an access path and not decoration, and four independent
     * statements in the reference establish that this one is. {@code app/csd/CARDDEMO.CSD} defines a file
     * resource named {@code CXACAIX} at L63 and describes it in words at L64 as the alternate index to
     * {@code CCXREF} by the account key. The data set names prove the relationship: that resource points
     * at a name ending {@code .AIX.PATH} at L65 where the base cluster, defined at L37 and described at
     * L38 as the card-to-account cross-reference, points at a name ending {@code .KSDS} at L39 -- the
     * same {@code CARDXREF} stem reached two ways. A program declares the path as a file literal,
     * {@code app/cbl/COACTVWC.cbl} holding {@code 'CXACAIX '} across L192 and L193 as eight characters
     * including the trailing blank. And the decisive one: the base cluster is keyed by CARD NUMBER, which
     * {@code app/cbl/CBACT03C.cbl} states at L32 as {@code RECORD KEY IS FD-XREF-CARD-NUM} beside
     * {@code ACCESS MODE IS SEQUENTIAL} at L31, so an account identifier is not a key of it and a
     * separate index has to exist for the by-account read to be a path rather than a search.</p>
     *
     * <p>Assumptions: the index is asserted NON-unique, and the non-uniqueness is load-bearing rather
     * than a cautious default. One account holds many cards and therefore many cross-reference rows, so a
     * unique index would refuse rows the reference admits; the migration records that reasoning at the
     * index it declares. A test asserting uniqueness here would lock the schema into refusing an account's
     * second card.</p>
     *
     * <p>Assumptions: the two resource names most easily confused are kept apart deliberately. The base
     * cluster's CSD resource name is {@code CCXREF} at L37 and not {@code CARDXREF} -- the longer spelling
     * occurs in that file only inside the data set names at L39 and L65. And {@code CARDAIX} at L13 is the
     * alternate index over the CARD master, pointing at a {@code CARDDATA} stem at L14 and belonging to
     * the card context, where {@code CXACAIX} at L63 points at a {@code CARDXREF} stem. The two are worth
     * separating explicitly because the reference declares their file literals as ADJACENT lines --
     * {@code 'CARDAIX '} across L190 and L191 of {@code app/cbl/COACTVWC.cbl} immediately above
     * {@code 'CXACAIX '} across L192 and L193 -- so a reader scanning that area can take one for the
     * other and file this assertion in the wrong context. Neither stanza length is a guide either: the
     * stanzas at L1 and L13 carry no description line at all where the other six do, so no fixed line
     * stride into that file is safe.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalog cannot be read, which is a broken harness rather than a failed
     *     assertion
     */
    @Test
    @DisplayName("V1__account.sql created idx_card_xref_account_id as a non-unique index on account_id")
    void theMigrationCreatedTheNonUniqueByAccountIndex() throws SQLException {
        // WHY : Assumptions: the catalog is read rather than the migration's own history, because what
        //       this case needs is the index's SHAPE and not the fact that a script ran. A history row
        //       says a script succeeded; it does not say the index is over the account column, and it
        //       does not say the index is non-unique. The sibling CustomerMasterRepositoryIT asserts the
        //       history and the schema ownership, so neither is restated here.
        String indexShape = """
                SELECT i.indisunique AS is_unique,
                       pg_catalog.pg_get_indexdef(i.indexrelid) AS definition
                  FROM pg_catalog.pg_index i
                  JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relname = ?
                """;
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement(indexShape)) {
            query.setString(1, DataSourceConfig.SCHEMA_NAME);
            query.setString(2, BY_ACCOUNT_INDEX);
            try (ResultSet found = query.executeQuery()) {
                assertThat(found.next())
                        .as("the by-account index must exist, because it is the migrated form of the"
                                + " CXACAIX access path defined at app/csd/CARDDEMO.CSD L63")
                        .isTrue();
                assertThat(found.getBoolean("is_unique"))
                        .as("the index must be NON-unique, because one account holds many cards")
                        .isFalse();
                assertThat(found.getString("definition"))
                        .as("the index must be over the account column, which is the key the alternate"
                                + " index carried per app/csd/CARDDEMO.CSD L64")
                        .contains("(account_id)");
            }
        }
    }

    /**
     * Confirms the table carries exactly the three copybook fields as columns, and no version column.
     *
     * <p>Assumptions: three named copybook fields become three columns with nothing added, which is what
     * makes an exact-set assertion possible here where it would not be on the two master tables -- each of
     * those gains a version column the copybook does not declare. The fourteen padding bytes of
     * {@code FILLER PIC X(14)} at L8 of {@code app/cpy/CVACT03Y.cpy} become no column, so a fourth column
     * appearing here would mean either that padding had been materialised or that a concern had been added
     * to a record whose contract is settled by the copybook.</p>
     *
     * <p>Assumptions: the absence of a version column is asserted rather than assumed, and the grounding
     * is concrete rather than a general observation about cross-references. The platform PERMITS updating
     * this file -- {@code app/csd/CARDDEMO.CSD} grants
     * {@code BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES) JOURNAL(NO)} at L44 for the base cluster and at
     * L70 for the second access path -- so permission is not the reason. The reason is that nothing takes
     * it up: a search across the reference programs finds no rewrite of {@code CARD-XREF-RECORD} anywhere
     * at all, and the single mutation of that record in the whole baseline is one insert, at L361 of
     * {@code app/cbl/CBIMPORT.cbl}, in a program that belongs to the batch context and not to this one.
     * Optimistic concurrency detects a competing UPDATE; this context performs none on this table, so a
     * version column would advertise an update path that does not exist.</p>
     *
     * <p>Assumptions: for contrast, the two master tables carry a version column because
     * {@code app/cbl/COACTUPC.cbl} keeps a manual before-image of the record it is about to write --
     * {@code 05 ACUP-OLD-DETAILS.} at L669, ending immediately before {@code 05 ACUP-NEW-DETAILS.} at
     * L757 and so spanning L669 through L756 -- and no such before-image exists for the cross-reference
     * record. Nothing here asserts a version increment, an optimistic-lock failure or a lock of any kind
     * against this entity: the first two are unrepresentable without a version column and the third would
     * hold a row across a request boundary the reference never held one across.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalog cannot be read, which is a broken harness rather than a failed
     *     assertion
     */
    @Test
    @DisplayName("card_xref carries exactly card_num, customer_id and account_id, and no version column")
    void theCrossReferenceCarriesThreeColumnsAndNoVersionColumn() throws SQLException {
        List<String> columns = new ArrayList<>();
        String columnQuery = """
                SELECT column_name, data_type, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = 'card_xref'
                 ORDER BY ordinal_position
                """;
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement(columnQuery)) {
            query.setString(1, DataSourceConfig.SCHEMA_NAME);
            try (ResultSet declared = query.executeQuery()) {
                while (declared.next()) {
                    columns.add(declared.getString("column_name"));
                    // WHY : Assumptions: the card number is asserted as a FIXED-width character column
                    //       and not merely as some character type, because the difference decides whether
                    //       the engine pads. XREF-CARD-NUM is PIC X(16) at L5 of app/cpy/CVACT03Y.cpy, so
                    //       a value shorter than sixteen is a defect to be refused rather than a shorter
                    //       name to be stored, and a variable-width column would store it silently.
                    if ("card_num".equals(declared.getString("column_name"))) {
                        assertThat(declared.getString("data_type"))
                                .as("card_num must be fixed-width character, the PIC X(16) of"
                                        + " app/cpy/CVACT03Y.cpy L5")
                                .isEqualTo("character");
                        assertThat(declared.getInt("character_maximum_length"))
                                .as("card_num must be exactly the declared width")
                                .isEqualTo(16);
                    } else {
                        assertThat(declared.getString("data_type"))
                                .as("both identifier columns must be whole numbers, the PIC 9(09) at L6"
                                        + " and PIC 9(11) at L7 of app/cpy/CVACT03Y.cpy")
                                .isEqualTo("bigint");
                    }
                }
            }
        }
        assertThat(columns)
                .as("exactly the three named copybook fields become columns; FILLER at L8 of"
                        + " app/cpy/CVACT03Y.cpy becomes none, and no version column is added")
                .containsExactly("card_num", "customer_id", "account_id");
    }

    /**
     * Confirms the table declares no card-verification-value column, rather than a masked one.
     *
     * <p>Assumptions: the ABSENCE is asserted and not a masking rule, and the distinction matters. A
     * masking assertion would imply such a column exists and is merely hidden on the way out;
     * {@code app/cpy/CVACT03Y.cpy} declares no verification value at all -- its three named fields at L5,
     * L6 and L7 are a card number and two identifiers, and L8 is padding. So there is nothing to mask
     * here, and the correct statement about this table is that the column does not exist. The same holds
     * for the two master records this context owns, whose copybooks declare no such field either; the
     * verification value belongs to the card record of another context, and no assertion in this class
     * names a value for one.</p>
     *
     * <p>Assumptions: the check is expressed as a pattern over the catalog rather than as a comparison
     * against one spelling, because the defect it guards against is a column ARRIVING under a name nobody
     * predicted. A test naming a single expected spelling would pass against a column called something
     * adjacent, which is the outcome least likely to be noticed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the catalog cannot be read, which is a broken harness rather than a failed
     *     assertion
     */
    @Test
    @DisplayName("card_xref declares no card-verification-value column under any spelling")
    void theCrossReferenceDeclaresNoCardVerificationValueColumn() throws SQLException {
        String suspectColumns = """
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = 'card_xref'
                   AND (column_name LIKE '%cvv%' OR column_name LIKE '%verification%'
                        OR column_name LIKE '%cvc%' OR column_name LIKE '%security_code%')
                """;
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement(suspectColumns)) {
            query.setString(1, DataSourceConfig.SCHEMA_NAME);
            try (ResultSet counted = query.executeQuery()) {
                assertThat(counted.next())
                        .as("the catalog count must return a row")
                        .isTrue();
                assertThat(counted.getInt(1))
                        .as("no verification-value column may exist on a record whose copybook declares"
                                + " none: app/cpy/CVACT03Y.cpy names three fields and one FILLER")
                        .isZero();
            }
        }
    }

    /**
     * Confirms the fifty-byte seed record decodes through the registered cross-reference layout.
     *
     * <p>Assumptions: the layout is taken from the shared registry rather than described again here, and
     * the registry entry is the one authority for this record's geometry. Its declared record length,
     * retrieval-key length and key offset are asserted because those three numbers are the ones a
     * hand-written offset would silently disagree with: the key length and offset express
     * {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 of {@code app/cbl/CBACT03C.cbl} as sixteen bytes
     * starting at zero, which is precisely {@code XREF-CARD-NUM PIC X(16)} at L5 of
     * {@code app/cpy/CVACT03Y.cpy}. The house rule that a layout stays single-sourced and is never
     * duplicated is recorded at L540 through L542 of {@code tests/README.md}, and driving this decode
     * from the registry is how that rule is honoured on this side of the migration.</p>
     *
     * <p>Assumptions: the decoded map is asserted to hold exactly three entries. The padding field
     * declared at L8 of the copybook is deliberately not among them -- the codec omits padding -- so a
     * fourth entry would mean the padding had become data, and two entries would mean a named field had
     * been lost. Both identifiers come back as whole numbers and the card number as characters, which is
     * the same split the column contract asserts, so the two ends of the load agree by construction
     * rather than by coincidence.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the seed record cannot be read from the classpath
     */
    @Test
    @DisplayName("The 50-byte seed record decodes through the registered XREF layout into three fields")
    void theSeedRecordDecodesThroughTheRegisteredCrossReferenceLayout() throws IOException {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(XREF_LAYOUT_NAME);
        assertThat(spec.reclen())
                .as("the declared record length is the 50 bytes the L2 header of app/cpy/CVACT03Y.cpy"
                        + " announces, being 16 + 9 + 11 named bytes plus 14 of FILLER at L8")
                .isEqualTo(50);
        assertThat(spec.keyLength())
                .as("the retrieval key is the 16-byte card number of app/cpy/CVACT03Y.cpy L5")
                .isEqualTo(16);
        assertThat(spec.keyOffset())
                .as("that key starts at the front of the record, which is why RECORD KEY IS"
                        + " FD-XREF-CARD-NUM at app/cbl/CBACT03C.cbl L32 needs no offset of its own")
                .isZero();

        byte[] record = readFixtureBytes(XREF_FIXTURE);
        assertThat(record)
                .as("the seed record must be exactly as long as the layout declares, or every offset"
                        + " below it is displaced")
                .hasSize(spec.reclen());

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, spec);
        assertThat(decoded)
                .as("three named fields decode and the FILLER at app/cpy/CVACT03Y.cpy L8 decodes to no"
                        + " entry at all")
                .containsOnlyKeys("XREF-CARD-NUM", "XREF-CUST-ID", "XREF-ACCT-ID");
        assertThat(decoded.get("XREF-CARD-NUM"))
                .as("the card number decodes as characters, never as a number")
                .isEqualTo(FIXTURE_CARD_NUMBER);
        assertThat(decoded.get("XREF-CUST-ID"))
                .as("the customer identifier decodes as a whole number")
                .isEqualTo(FIXTURE_CUSTOMER_ID);
        assertThat(decoded.get("XREF-ACCT-ID"))
                .as("the account identifier decodes as a whole number")
                .isEqualTo(FIXTURE_ACCOUNT_ID);
    }

    /**
     * Confirms the keyed read entered on a card number round-trips, and that an unknown card is absent.
     *
     * <p>Assumptions: the key of this table is textual where the two sibling repositories in this package
     * key on whole numbers, and that asymmetry is the detail a reader is most likely to expect wrongly. It
     * follows from the reference: {@code app/cbl/CBACT03C.cbl} names the record key at L32 as
     * {@code RECORD KEY IS FD-XREF-CARD-NUM}, and {@code XREF-CARD-NUM} is declared
     * {@code PIC X(16)} -- characters -- at L5 of {@code app/cpy/CVACT03Y.cpy}. A leading zero is
     * significant in that value and sixteen significant digits exceed what binary floating point holds
     * exactly, so the key is compared as characters at every hop and never rendered as a number.</p>
     *
     * <p>Assumptions: the inherited keyed read and the named one are asserted to agree, because the
     * interface offers both and a reader needs to know they resolve to the same row rather than to two
     * subtly different statements. The named one exists because a call site reading the inherited form
     * gives a reviewer no indication that the value being passed is a primary account number.</p>
     *
     * <p>Assumptions: an unknown card yields an absent result rather than a raised condition, which is the
     * shape the reference's own not-found arm has -- at L741 of {@code app/cbl/COACTVWC.cbl} it sets an
     * input-error flag and composes a screen message rather than abending. Which sentence a user
     * eventually reads is settled in the service layer, so no message text is reproduced here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("The keyed read on the CHAR(16) card number round-trips, and an unknown card is absent")
    void theKeyedReadByCardNumberRoundTripsAndAnUnknownCardIsAbsent() {
        Optional<CardXref> byIdentity = this.crossReferences.findById(FIXTURE_CARD_NUMBER);
        assertThat(byIdentity)
                .as("the seeded card must resolve through the primary key declared as pk_card_xref")
                .isPresent();
        assertThat(byIdentity.get().getCardNum()).isEqualTo(FIXTURE_CARD_NUMBER);
        assertThat(byIdentity.get().getCustomerId()).isEqualTo(FIXTURE_CUSTOMER_ID);
        assertThat(byIdentity.get().getAccountId()).isEqualTo(FIXTURE_ACCOUNT_ID);

        assertThat(this.crossReferences.findByCardNum(FIXTURE_CARD_NUMBER))
                .as("the named keyed read must resolve the same row as the inherited one, both being the"
                        + " card-number key of app/cbl/CBACT03C.cbl L32")
                .isPresent()
                .get()
                .extracting(CardXref::getCardNum)
                .isEqualTo(FIXTURE_CARD_NUMBER);

        assertThat(this.crossReferences.findByCardNum(CARD_NUMBER_NOT_CROSS_REFERENCED))
                .as("a card that is not cross-referenced is an empty result, never a raised condition")
                .isEmpty();
        assertThat(this.crossReferences.findById(CARD_NUMBER_NOT_CROSS_REFERENCED))
                .as("the inherited read answers an unknown key the same way")
                .isEmpty();
    }

    /**
     * Confirms the account-entered read returns the one row the reference read would have returned.
     *
     * <p>Assumptions: this is the migrated counterpart of a single keyed read and not of a browse. The
     * paragraph is {@code 9200-GETCARDXREF-BYACCT.} at L723 of {@code app/cbl/COACTVWC.cbl}, and its verbs
     * run L727 through L735: the read is issued at L727, its data set named at L728 as the
     * account-path literal, its record identification field at L729 holding an account identifier with
     * that field's length at L730, the record received at L731 into {@code CARD-XREF-RECORD} with its
     * length at L732, and the two response fields at L733 and L734 before the terminator at L735. It
     * carries no generic-key option and is not a browse start, so it reads at most one record, and the
     * result arm at L738 consumes exactly one row -- moving the customer identifier at L739 and the card
     * number at L740. The return shape asserted here is therefore a single optional row and not a list.</p>
     *
     * <p>Assumptions: the comment at L725 of that program is misleading and is cited only so a reader is
     * warned off it. It describes the access as a read of the card file, while the data set named at L728
     * is the cross-reference account path and the record received at L731 is
     * {@code CARD-XREF-RECORD} -- so the verbs read the cross-reference file. The baseline's comment says
     * one thing and its verbs do another; the Java follows the verbs, and the divergence is recorded here.
     * An implementation trusting the comment would file this access path in the card context, which owns a
     * different alternate index entirely.</p>
     *
     * <p>Assumptions: the tie-break is the ordering and it is required rather than cosmetic, because the
     * index behind this read is non-unique. Without a stated ordering the row returned would be whichever
     * the plan reached first and could differ between two executions over identical rows, which would be
     * an unstated behaviour change rather than a carried-over one. The account asserted here holds exactly
     * one row, so this case establishes the reference's shape; the several-card case below establishes
     * that the ordering decides which row that shape yields.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("The by-account read returns the single row the COACTVWC L727-L732 read would return")
    void theByAccountReadReturnsTheSingleRowTheReferenceReadWouldHaveReturned() {
        assertThat(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(FIXTURE_ACCOUNT_ID))
                .as("the account of the pinned triple owns exactly one cross-reference row, and the"
                        + " by-account read must return it")
                .isPresent()
                .get()
                .extracting(CardXref::getCardNum)
                .isEqualTo(FIXTURE_CARD_NUMBER);

        assertThat(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(MULTI_CARD_ACCOUNT_ID))
                .as("where an account holds several rows the stated ascending ordering decides which one"
                        + " a single-row read yields, so the answer is reproducible")
                .isPresent()
                .get()
                .extracting(CardXref::getCardNum)
                .isEqualTo(multiCardNumber(1));

        assertThat(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_WITHOUT_ANY_CARD))
                .as("an account with no cards is an empty result, matching the not-found arm at"
                        + " app/cbl/COACTVWC.cbl L741")
                .isEmpty();
    }

    /**
     * Confirms the engine resolves the by-account read through the secondary index, not by reading the
     * table end to end.
     *
     * <p>Assumptions: this is the assertion the whole class exists for, because an alternate index is an
     * access path and not decoration. The reference reached this record by account only through a second
     * path -- {@code CXACAIX}, defined at L63 of {@code app/csd/CARDDEMO.CSD} and described at L64 as the
     * alternate index to {@code CCXREF} by the account key -- precisely because the base cluster is keyed
     * by card number, which {@code app/cbl/CBACT03C.cbl} states at L32. A migration that produced the
     * right ANSWERS by filtering a full read of the table would have carried the query across and left the
     * access path behind, and no assertion about a returned row can tell the two apart. That the path is
     * load-bearing across the system rather than incidental to one screen is visible from its readers:
     * {@code COACTVWC}, {@code COACTUPC}, {@code COBIL00C}, {@code COTRN02C} and {@code COPAUS0C} all read
     * it, as do three of the jobs under {@code app/jcl}.</p>
     *
     * <p>Assumptions: the plan is taken over the statement SHAPE the by-account finder produces -- the
     * account predicate with the ascending card-number ordering -- rather than over a captured translation
     * of the finder's own query. Capturing the emitted text would tie this assertion to a translation
     * detail that a framework upgrade may reword, where the shape is what the access path actually is; the
     * case above asserts that the finder returns the right row, and the two together establish that the
     * right row arrives by the right path.</p>
     *
     * <p>Assumptions: the account identifier is placed in the statement as a literal rather than as a bind
     * parameter, and the reason is specific to explaining a plan. A planner may answer a parameterised
     * statement with a plan chosen to suit any future value rather than the value at hand, so a plan
     * explained through a parameter is not necessarily the plan a keyed read receives. Interpolation is
     * safe here because the value is a whole number held in a numeric variable and reaches this statement
     * from a constant in this class, never from input.</p>
     *
     * <p>Assumptions: nothing in this class disables reading the table end to end. Turning that off would
     * leave the planner no alternative and the assertion would then hold whatever the index did, which is
     * the one way to make a plan assertion prove nothing. What makes the choice meaningful instead is the
     * seeding: enough unrelated rows that reading the table end to end is a real option, with statistics
     * gathered so the planner costs both options from this table rather than from a built-in guess.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the plan cannot be obtained, which is a broken harness rather than a failed
     *     assertion
     */
    @Test
    @DisplayName("The by-account read resolves through idx_card_xref_account_id, not a sequential scan")
    void theByAccountReadResolvesThroughTheAccountIndexRatherThanBySequentialScan() throws SQLException {
        String byAccountRead = "SELECT card_num, customer_id, account_id FROM card_xref"
                + " WHERE account_id = " + MULTI_CARD_ACCOUNT_ID
                + " ORDER BY card_num ASC";
        String plan = String.join("\n", queryPlan(byAccountRead));

        assertThat(plan)
                .as("the plan must name the index the migration creates for this access path, or the"
                        + " CXACAIX path of app/csd/CARDDEMO.CSD L63 has not been carried across: %s", plan)
                .contains(BY_ACCOUNT_INDEX);
        assertThat(plan)
                .as("the plan must not read card_xref end to end, which is what filtering a full read"
                        + " rather than using the access path would look like: %s", plan)
                .doesNotContain("Seq Scan on card_xref");
    }

    /**
     * Confirms the by-account listing returns every row the non-unique index admits.
     *
     * <p>Trade-offs: this listing is a documented SUPERSET of the reference's by-account behaviour, and the
     * divergence is stated rather than absorbed. The reference does one thing on this path: the keyed read
     * at L727 through L732 of {@code app/cbl/COACTVWC.cbl} carries no generic-key option and is not a
     * browse start, so it can only ever surface ONE row for an account even where the underlying data holds
     * several. The migrated index is non-unique -- {@code db/migration/V1__account.sql} declares
     * {@code idx_card_xref_account_id} without uniqueness, because the alternate index it replaces carried
     * the account key per L64 of {@code app/csd/CARDDEMO.CSD} and one account holds many cards -- so a
     * multi-row answer becomes expressible where it previously was not. What is accepted is that a reader
     * comparing the two must consult this paragraph to see which member corresponds to the reference read;
     * what is bought is that an account's whole card set is reachable in one statement. The listing is
     * offered additively and the single-row read asserted above preserves the reference's observable shape
     * unchanged. This is a superset, not a correction: the reference is not deficient, it simply answers a
     * narrower question, and its own resource definition grants {@code BROWSE(YES)} on this path at L70 of
     * that same file whether or not that particular program takes it up.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("The by-account listing returns every row the non-unique index admits, in key order")
    void theByAccountListingReturnsEveryRowTheNonUniqueIndexAdmits() {
        List<CardXref> everyRow = this.crossReferences.findForwardFromCursor(
                null, MULTI_CARD_ACCOUNT_ID, Limit.of(MULTI_CARD_ROWS + 1));

        assertThat(cardNumbersOf(everyRow))
                .as("all of the account's rows come back, in ascending card-number order, and none"
                        + " belonging to another account does")
                .containsExactly(multiCardNumber(1), multiCardNumber(2), multiCardNumber(3),
                        multiCardNumber(4), multiCardNumber(5));
        assertThat(everyRow)
                .as("every returned row names the account the read was narrowed to")
                .allMatch(row -> row.getAccountId().equals(MULTI_CARD_ACCOUNT_ID));
    }

    /**
     * Confirms the cross-reference resolves a customer identifier as well as an account identifier.
     *
     * <p>Assumptions: {@code XREF-CUST-ID PIC 9(09)} at L6 of {@code app/cpy/CVACT03Y.cpy} makes a
     * by-customer lookup answerable from this record, and the reference itself consumes that field on the
     * account path -- it is the value moved at L739 of {@code app/cbl/COACTVWC.cbl} once the read at L727
     * has returned. This case asserts it against the customer identifier of the pinned triple.</p>
     *
     * <p>Assumptions: the statement is issued by this case rather than through a finder, because
     * {@code CardXrefRepository} declares none for this column and this class does not extend the
     * interface under test in order to be able to assert something about it. What is being established is
     * that the column carries the value the copybook says it carries and that it is selectable, which a
     * statement demonstrates as well as a finder would.</p>
     *
     * <p>Assumptions: no plan is asserted for this path, deliberately, and the asymmetry against the
     * by-account case is the point. {@code db/migration/V1__account.sql} declares exactly one secondary
     * index on this table and it is over the account column; there is no index named for the customer
     * column, so an index-choice assertion here would either fail or -- worse -- pass by naming the primary
     * key and read as coverage of an access path that does not exist.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the statement is refused, which is a broken harness rather than a failed
     *     assertion
     */
    @Test
    @DisplayName("The by-customer column resolves the pinned triple's customer identifier")
    void theByCustomerColumnResolvesTheJoinTriplesCustomerIdentifier() throws SQLException {
        List<String> cards = new ArrayList<>();
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT card_num FROM card_xref WHERE customer_id = ? ORDER BY card_num ASC")) {
            query.setLong(1, FIXTURE_CUSTOMER_ID);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    cards.add(rows.getString("card_num"));
                }
            }
        }
        assertThat(cards)
                .as("the customer identifier of the pinned triple resolves to the one card the seed"
                        + " record cross-references to it")
                .containsExactly(FIXTURE_CARD_NUMBER);
    }

    /**
     * Confirms the one row read beyond a window is what settles whether further rows follow.
     *
     * <p>Alternatives Considered: positioning a window by counting rows from the start of the ordered set,
     * asking the engine to pass over a tally and return what follows. Rejected on observable behaviour
     * rather than on taste. When rows are inserted or removed between two requests the number of rows
     * preceding a resume point changes underneath the reader, so a window positioned that way omits rows it
     * never returned and returns rows it already returned; a window resuming from the key of the last row
     * it actually returned can do neither, because that key names a row rather than a distance. The
     * reference never counted either: {@code app/cbl/COCRDLIC.cbl} carries a trailing key pair and a
     * leading key pair across the terminal turn and sets its further-rows indicator by discovering one
     * record beyond those a screen holds -- so reading one row past the window is the reference's own
     * technique and not an addition here.</p>
     *
     * <p>Assumptions: the surplus row is the caller's to drop and its key must never be published as a
     * boundary. The trailing boundary is the key of the LAST RETURNED row, and publishing the surplus row's
     * key instead would advance the position one row too far and drop a row from the following read. That
     * is asserted here explicitly, because it is a defect that leaves every individual window looking
     * correct.</p>
     *
     * <p>Assumptions: the second half of this case is the boundary itself -- a window that comes back
     * exactly full with no surplus row. It reports that no further rows follow, which distinguishes a full
     * final window from a full intermediate one, and getting that comparison inclusive rather than strict
     * would report a further window that does not exist.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("The row read beyond the window settles whether further rows follow, and is not published")
    void theRowBeyondTheWindowSettlesWhetherFurtherRowsFollow() {
        List<CardXref> withSurplus = this.crossReferences.findForwardFromCursor(
                null, MULTI_CARD_ACCOUNT_ID, Limit.of(WINDOW_ROWS + 1));
        assertThat(withSurplus)
                .as("asking for one more than the window keeps is what makes the answer knowable")
                .hasSize(WINDOW_ROWS + 1);
        assertThat(withSurplus.size() > WINDOW_ROWS)
                .as("the surplus row is the evidence that further rows follow")
                .isTrue();
        assertThat(withSurplus.get(WINDOW_ROWS - 1).getCardNum())
                .as("the trailing boundary is the last row the caller KEEPS, never the surplus row read"
                        + " beyond it")
                .isEqualTo(multiCardNumber(WINDOW_ROWS));
        assertThat(withSurplus.get(WINDOW_ROWS).getCardNum())
                .as("the surplus row is the row after the window, and is dropped rather than published")
                .isEqualTo(multiCardNumber(WINDOW_ROWS + 1));

        List<CardXref> exactlyFull = this.crossReferences.findForwardFromCursor(
                multiCardNumber(3), MULTI_CARD_ACCOUNT_ID, Limit.of(WINDOW_ROWS + 1));
        assertThat(cardNumbersOf(exactlyFull))
                .as("resuming STRICTLY after a key excludes that key, so the last two rows come back")
                .containsExactly(multiCardNumber(4), multiCardNumber(5));
        assertThat(exactlyFull.size() > WINDOW_ROWS)
                .as("a window that comes back exactly full with no surplus row reports no further rows")
                .isFalse();
    }

    /**
     * Confirms a backward window returns the same rows as the forward window that preceded it.
     *
     * <p>Assumptions: the backward read is the counterpart of the reference's read-previous verb, and the
     * descending ordering is what makes it that rather than a fresh read from the start of the set. The
     * position it seeks from is the LEADING boundary of the window the caller already holds, which is why
     * {@code app/cbl/COCRDLIC.cbl} keeps a leading key pair beside its trailing one. Together the two
     * directions are the whole of the reference's four browse verbs -- the browse-start, read-next,
     * read-previous and browse-end sequence, and the open, get-next, close triad the batch program drives
     * at L118, L92 and L136 of {@code app/cbl/CBACT03C.cbl} -- with nothing left to open or release,
     * because the position lives in the request rather than in a file handle. The sequential access mode
     * that triad declares at L31 of that program is the same ordered walk this pair of reads performs.</p>
     *
     * <p>Assumptions: the rows arrive descending and the caller reverses them, and the order of those two
     * steps matters. The bound has to apply to the rows NEAREST the position, and only the descending
     * ordering selects those, so the surplus row on a backward read is the LAST row returned and therefore
     * the smallest key. Reversing before dropping it would discard the row nearest the position and leave a
     * hole at the boundary -- a window that looks right and is quietly missing a row.</p>
     *
     * <p>Assumptions: the property asserted is a property of the SEQUENCE of windows rather than of any one
     * of them. Walking forward twice and then stepping back must land on exactly the rows the first window
     * held; a comparison that was inclusive rather than strict would repeat one row per boundary while every
     * individual window still looked correct.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("A backward window reproduces the forward window that preceded it, row for row")
    void theBackwardWindowReproducesTheForwardWindowThatPrecededIt() {
        List<String> firstWindow = cardNumbersOf(this.crossReferences.findForwardFromCursor(
                        null, MULTI_CARD_ACCOUNT_ID, Limit.of(WINDOW_ROWS + 1)))
                .subList(0, WINDOW_ROWS);
        String trailingBoundary = firstWindow.get(WINDOW_ROWS - 1);

        List<String> secondWindow = cardNumbersOf(this.crossReferences.findForwardFromCursor(
                        trailingBoundary, MULTI_CARD_ACCOUNT_ID, Limit.of(WINDOW_ROWS + 1)))
                .subList(0, WINDOW_ROWS);
        String leadingBoundary = secondWindow.get(0);
        assertThat(secondWindow)
                .as("the second window continues strictly after the first, sharing no row with it")
                .doesNotContainAnyElementsOf(firstWindow);

        List<CardXref> steppedBack = this.crossReferences.findBackwardFromCursor(
                leadingBoundary, MULTI_CARD_ACCOUNT_ID, Limit.of(WINDOW_ROWS + 1));
        assertThat(cardNumbersOf(steppedBack))
                .as("the backward read returns the rows adjacent to the position, in DESCENDING key"
                        + " order, which is the ordering the bound requires")
                .containsExactly(firstWindow.get(1), firstWindow.get(0));

        List<String> reversed = new ArrayList<>(cardNumbersOf(steppedBack));
        Collections.reverse(reversed);
        assertThat(reversed)
                .as("reversed for presentation, the backward window is exactly the forward window that"
                        + " preceded it -- no row skipped and none repeated at the boundary")
                .isEqualTo(firstWindow);
    }

    /**
     * Confirms an exhausted by-account scan is the shared envelope that carries no position at all.
     *
     * <p>Assumptions: the shared envelope {@code com.carddemo.common.web.PageResponse} is a record with one
     * type parameter and four components in a fixed order -- the rows, the leading boundary, the trailing
     * boundary and whether further rows follow -- and both boundaries may be absent when nothing was
     * returned. An exhausted scan is exactly that state, and it is the one envelope state this class can
     * construct.</p>
     *
     * <p>Assumptions: no POPULATED envelope is built here, and the reason is a hard constraint rather than a
     * division of labour. That record's constructor requires each present boundary to be a sealed cursor
     * token and refuses a raw key outright, sealing needs key material, and this package admits no key
     * material of any kind -- the ruling the package descriptor records ninth. The sealer therefore lives
     * one layer up, in {@code com.carddemo.account.service}, which holds it and the caller's identity;
     * every member of the repository interface accordingly yields entities and ordered lists of entities,
     * and the boundaries are sealed above. What this class asserts is the window semantics the envelope is
     * assembled FROM, which the two cases above establish, plus the one state that needs no token.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("An account with no cross-reference row yields the envelope that carries no position")
    void anExhaustedByAccountScanIsTheEnvelopeThatCarriesNoPosition() {
        List<CardXref> nothing = this.crossReferences.findForwardFromCursor(
                null, ACCOUNT_WITHOUT_ANY_CARD, Limit.of(WINDOW_ROWS + 1));
        assertThat(nothing)
                .as("an account no seeded row names has no cross-reference row to return")
                .isEmpty();

        PageResponse<CardXref> exhausted = PageResponse.empty();
        assertThat(exhausted.items())
                .as("the exhausted envelope carries no rows")
                .isEmpty();
        assertThat(exhausted.firstKey())
                .as("with no row returned there is no leading boundary to name")
                .isNull();
        assertThat(exhausted.lastKey())
                .as("nor a trailing one")
                .isNull();
        assertThat(exhausted.hasNext())
                .as("and nothing follows a scan that is exhausted")
                .isFalse();
    }

    /**
     * Confirms an unqualified table name resolves inside this context's schema.
     *
     * <p>Assumptions: {@code com.carddemo.account.config.DataSourceConfig} is the single owner of this
     * question. It holds the schema name as one public constant, pins the session search path to it on
     * every pooled connection, and refuses at startup a pool whose search path pins anything else -- which
     * is why that class is imported into the configuration this case runs against rather than left out of
     * it. Importing it means the pin under assertion is the deployed mechanism and not a value this test
     * happened to supply to itself.</p>
     *
     * <p>Assumptions: this is a precondition of the plan assertion in this class and not merely a
     * convenience. Every statement this class explains names {@code card_xref} unqualified, so an
     * unqualified name resolving to some other schema would explain a plan over a different table, and the
     * index the plan is asserted to name would not be visible on it. The failure would arrive as a missing
     * relation while the context starts rather than as a compilation error.</p>
     *
     * <p>Assumptions: the count is asserted as well as the resolution, so the case fails if the statement
     * resolves to a same-named table somewhere else that happens to exist. A statement that merely
     * succeeded would pass against such a table; the number of rows this case seeded is what distinguishes
     * this context's table from any other.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the statement is refused, which is a broken harness rather than a failed
     *     assertion
     */
    @Test
    @DisplayName("An unqualified card_xref resolves through the search path DataSourceConfig pins")
    void anUnqualifiedCrossReferenceNameResolvesThroughThePinnedSearchPath() throws SQLException {
        try (Connection connection = this.dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            try (ResultSet inForce = statement.executeQuery("SHOW search_path")) {
                assertThat(inForce.next())
                        .as("the session setting must be readable")
                        .isTrue();
                assertThat(inForce.getString(1).trim())
                        .as("every pooled connection is pinned to this context's schema alone, which is"
                                + " what lets an unqualified name below resolve to the right table")
                        .isEqualTo(DataSourceConfig.SCHEMA_NAME);
            }
            try (ResultSet counted = statement.executeQuery("SELECT count(*) FROM card_xref")) {
                assertThat(counted.next())
                        .as("the count must return a row")
                        .isTrue();
                assertThat(counted.getLong(1))
                        .as("the unqualified name reaches the table this case seeded, and not a"
                                + " same-named table in another schema")
                        .isEqualTo(1L + MULTI_CARD_ROWS + UNRELATED_ROWS);
            }
        }
    }

    /**
     * Confirms the pinned triple holds across all three seed records and resolves end to end.
     *
     * <p>Assumptions: the triple is pinned across three separate files, so the first half of this case
     * compares the three files directly. The cross-reference record's account field must equal the leading
     * {@code ACCT-ID} of the account record and its customer field must equal the leading {@code CUST-ID}
     * of the customer record; a change to any one of the three files breaks this comparison deliberately
     * and visibly, which is the reason to make it here rather than to let a downstream case fail obscurely
     * on a row that no longer joins.</p>
     *
     * <p>Assumptions: the two master records are decoded FIELD by field where the cross-reference record is
     * decoded whole, and the asymmetry is deliberate. The cross-reference record is this class's subject, so
     * decoding all of it is the point; the master records contribute one key field each, and decoding the
     * rest of them would make this case fail whenever an unrelated field of another context's record
     * changed -- including the zoned-decimal money fields the account record carries, which have nothing to
     * do with a cross-reference.</p>
     *
     * <p>Assumptions: the second half reads the composition through the repository so the triple is shown to
     * resolve at the store and not only on the classpath. Both master components must be present, because
     * this case seeds both -- the sibling {@code AccountScreenProjectionIT} owns the arms where one is
     * absent, and nothing here restates them.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if any of the three seed records cannot be read from the classpath
     */
    @Test
    @DisplayName("The pinned triple holds across the three seed records and resolves through the store")
    void thePinnedTripleHoldsAcrossTheThreeSeedRecordsAndResolvesEndToEnd() throws IOException {
        Map<String, Object> crossReference = decodeCrossReferenceFixture();
        Object accountKey = decodeKeyField(ACCOUNT_FIXTURE, "ACCOUNT", "ACCT-ID");
        Object customerKey = decodeKeyField(CUSTOMER_FIXTURE, "CUSTOMER", "CUST-ID");

        assertThat(crossReference.get("XREF-ACCT-ID"))
                .as("the cross-reference record's account field is the account record's own key, or the"
                        + " by-account path has nothing to resolve")
                .isEqualTo(accountKey);
        assertThat(crossReference.get("XREF-CUST-ID"))
                .as("and its customer field is the customer record's own key")
                .isEqualTo(customerKey);

        List<AccountScreenRow> composition =
                this.crossReferences.findAccountScreenRows(FIXTURE_ACCOUNT_ID, Limit.of(1));
        assertThat(composition)
                .as("the account of the pinned triple composes exactly one row")
                .hasSize(1);
        AccountScreenRow row = composition.get(0);
        assertThat(row.crossReference().getCardNum())
                .as("the cross-reference side carries the card number the seed record declares")
                .isEqualTo(FIXTURE_CARD_NUMBER);
        assertThat(row.account())
                .as("the account master seeded for this triple must be reached")
                .isNotNull();
        assertThat(row.account().getAccountId())
                .as("and must be the account the triple names")
                .isEqualTo(FIXTURE_ACCOUNT_ID);
        assertThat(row.customer())
                .as("the customer master seeded for this triple must be reached")
                .isNotNull();
        assertThat(row.customer().getCustomerId())
                .as("and must be the customer the triple names")
                .isEqualTo(FIXTURE_CUSTOMER_ID);
    }

    /**
     * Returns the textual plan the engine would use for a statement, one line per plan node.
     *
     * <p>Assumptions: the plan is obtained by asking the engine to explain the statement rather than by
     * executing it and inferring anything from timing. A timing-based inference would be a measurement
     * subject to the machine it ran on, where the plan text names the access path outright; and explaining
     * a statement does not run it, so nothing this helper does can disturb the seeded rows.</p>
     *
     * <p>Assumptions: the plan is returned unparsed. Parsing it into a structure would require this class
     * to model a format the engine owns and may extend, and every assertion made against it here is a
     * containment test over node names that the caller can express directly.</p>
     *
     * @param statement the complete statement to explain, which must carry no bind parameter for the reason
     *     recorded at the case that calls this helper
     * @return the plan lines in the order the engine reported them, outermost node first; never
     *     {@code null} and never empty for a statement the engine accepted
     * @throws SQLException if the connection or the explain request is refused, which is a broken harness
     *     rather than a failed assertion
     */
    private List<String> queryPlan(String statement) throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Connection connection = this.dataSource.getConnection();
                Statement explain = connection.createStatement();
                ResultSet lines = explain.executeQuery("EXPLAIN " + statement)) {
            while (lines.next()) {
                plan.add(lines.getString(1));
            }
        }
        return plan;
    }

    /**
     * Reads one seed record from the classpath as raw bytes.
     *
     * <p>Assumptions: the bytes are read without a character decoding step, because a fixed-width record is
     * a byte layout and every offset in the registered layout is a byte offset. Decoding the whole record to
     * text first and slicing the text would give the same answer only for as long as every field stayed
     * single-byte, and the codec is the component that owns that decision per field.</p>
     *
     * <p>Assumptions: the record is resolved by classpath name and the name is therefore part of this
     * class's contract. The test profile declares no seed-record location, so nothing else can supply
     * it.</p>
     *
     * @param resourceName the classpath-relative name of the seed record, resolved against this class's
     *     own loader
     * @return the complete record image, exactly as many bytes as the file holds; never {@code null}
     * @throws IOException if the resource is absent from the classpath or cannot be read, either of which
     *     is a broken harness rather than a failed assertion
     */
    private static byte[] readFixtureBytes(String resourceName) throws IOException {
        try (InputStream source =
                CardXrefRepositoryIT.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (source == null) {
                throw new IOException("seed record absent from the classpath: " + resourceName);
            }
            return source.readAllBytes();
        }
    }

    /**
     * Decodes the cross-reference seed record through the layout registered for this record.
     *
     * <p>Assumptions: the decode is driven from the registry entry rather than from offsets written here,
     * so this class cannot disagree with the layout the rest of the migration uses. The house rule that a
     * record layout stays single-sourced and is never duplicated is recorded at L540 through L542 of
     * {@code tests/README.md}, and the registry is where that single source lives on this side.</p>
     *
     * @return the three named fields in copybook declaration order, the padding field of
     *     {@code app/cpy/CVACT03Y.cpy} L8 being absent by design; never {@code null}
     * @throws IOException if the seed record cannot be read from the classpath
     */
    private static Map<String, Object> decodeCrossReferenceFixture() throws IOException {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(XREF_LAYOUT_NAME);
        return FixedWidthCodec.decodeRecord(readFixtureBytes(XREF_FIXTURE), spec);
    }

    /**
     * Decodes exactly one field of a seed record, leaving every other field of it untouched.
     *
     * <p>Assumptions: a single field is decoded rather than the whole record because the two master records
     * contribute one key field each to the pinned triple. Decoding all of either would couple this
     * cross-reference class to fields belonging to another concern -- the account record's zoned-decimal
     * money fields among them -- so an unrelated change there would fail a case about a cross-reference.</p>
     *
     * @param resourceName the classpath-relative name of the seed record to read
     * @param layoutName the logical name of the layout that record is registered under
     * @param fieldName the copybook field name to decode, matched exactly as the layout declares it
     * @return the decoded value, whose Java type follows the field's declared kind: a whole number for a
     *     digits field and characters for a text field; never {@code null}
     * @throws IOException if the seed record cannot be read from the classpath
     */
    private static Object decodeKeyField(String resourceName, String layoutName, String fieldName)
            throws IOException {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(layoutName);
        CopybookLayout.FieldSpec field = spec.fields().stream()
                .filter(candidate -> candidate.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "layout " + layoutName + " declares no field named " + fieldName));
        return FixedWidthCodec.decodeField(readFixtureBytes(resourceName), field);
    }

    /**
     * Writes one cross-reference row with plain statements against the container.
     *
     * <p>Assumptions: the table name is unqualified, which every statement in this class other than the
     * schema prerequisite can rely on, because the pool this connection came from is pinned to this
     * context's schema. Naming the schema here would give one setting two definitions free to drift.</p>
     *
     * @param connection the borrowed connection to write through; must not be {@code null} and is neither
     *     committed nor closed here, the caller owning both
     * @param cardNum the sixteen-character key of the row to write
     * @param customerId the customer identifier the row resolves to
     * @param accountId the account identifier the row resolves to
     * @throws SQLException if the statement is refused, which is a broken harness rather than a failed
     *     assertion
     */
    private static void seedCrossReference(
            Connection connection, String cardNum, long customerId, long accountId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO card_xref (card_num, customer_id, account_id) VALUES (?, ?, ?)")) {
            insert.setString(1, cardNum);
            insert.setLong(2, customerId);
            insert.setLong(3, accountId);
            insert.executeUpdate();
        }
    }

    /**
     * Writes the account master row the pinned triple resolves to.
     *
     * <p>Assumptions: every column the migration declares as mandatory is supplied as a fixed literal, and
     * none of the values is read by any assertion in this class other than the account's own key. The row
     * exists so that the outer join in the composition read has a row to reach; giving its other columns
     * meaning here would invite a later reader to assert against values this class never chose with care.</p>
     *
     * @param connection the borrowed connection to write through; must not be {@code null}
     * @param accountId the account identifier to write, which is the key the triple names
     * @throws SQLException if the statement is refused, which is a broken harness rather than a failed
     *     assertion
     */
    private static void seedAccount(Connection connection, long accountId) throws SQLException {
        String insertAccount = """
                INSERT INTO accounts (
                    account_id, active_status, curr_bal, credit_limit, cash_credit_limit,
                    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit,
                    addr_zip, group_id, version)
                VALUES (?, 'Y', 1234.56, 5000.00, 1000.00,
                    DATE '2020-01-01', DATE '2030-01-01', DATE '2025-01-01', 0.00, 0.00,
                    '75001', 'DEFAULT', 0)
                """;
        try (PreparedStatement insert = connection.prepareStatement(insertAccount)) {
            insert.setLong(1, accountId);
            insert.executeUpdate();
        }
    }

    /**
     * Writes the customer master row the pinned triple resolves to.
     *
     * <p>Assumptions: the two protected columns are given bytes that are not valid text in any single-byte
     * encoding, deliberately. A printable value would survive a character column too, so a row seeded with
     * one would pass against exactly the column-type defect a container test exists to rule out. The bytes
     * are fabricated and are not key material of any kind.</p>
     *
     * @param connection the borrowed connection to write through; must not be {@code null}
     * @param customerId the customer identifier to write, which is the key the triple names
     * @throws SQLException if the statement is refused, which is a broken harness rather than a failed
     *     assertion
     */
    private static void seedCustomer(Connection connection, long customerId) throws SQLException {
        String insertCustomer = """
                INSERT INTO customers (
                    customer_id, first_name, last_name, addr_line_1, addr_line_3,
                    addr_state_cd, addr_country_cd, addr_zip, ssn_encrypted, dob,
                    eft_account_id, pri_card_holder_ind, fico_credit_score, version)
                VALUES (?, 'FIXTURE', 'CUSTOMER', 'ADDRESS LINE ONE', 'ADDRESS LINE THREE',
                    'TX', 'USA', '75001', ?, DATE '1980-01-01',
                    '0000000001', 'Y', 750, 0)
                """;
        try (PreparedStatement insert = connection.prepareStatement(insertCustomer)) {
            insert.setLong(1, customerId);
            insert.setBytes(2, new byte[] {0x00, (byte) 0xFF, 0x10, (byte) 0x80, 0x7F});
            insert.executeUpdate();
        }
    }

    /**
     * Writes the unrelated rows that make reading the table end to end a real alternative for the planner.
     *
     * <p>Assumptions: the rows are generated inside one set-based statement rather than inserted one at a
     * time, because this runs before every case and a per-row round trip would multiply the number of rows
     * by the number of cases in network latency alone. The generated values are chosen not to collide with
     * any account or card the cases name: the card numbers begin with a digit no named card begins with, so
     * every one of them sorts after every named card, and the account and customer identifiers sit in a
     * range far above the named ones.</p>
     *
     * @param connection the borrowed connection to write through; must not be {@code null}
     * @param rows the number of unrelated rows to generate, which must be positive
     * @throws SQLException if the statement is refused, which is a broken harness rather than a failed
     *     assertion
     */
    private static void seedUnrelatedRows(Connection connection, int rows) throws SQLException {
        String bulkInsert = """
                INSERT INTO card_xref (card_num, customer_id, account_id)
                SELECT '5' || lpad(ordinal::text, 15, '0'),
                       500000 + ordinal,
                       90000000000 + ordinal
                  FROM generate_series(1, ?) AS ordinal
                """;
        try (PreparedStatement insert = connection.prepareStatement(bulkInsert)) {
            insert.setInt(1, rows);
            insert.executeUpdate();
        }
    }

    /**
     * Builds the card number of one of the several-card account's rows.
     *
     * <p>Assumptions: the value is exactly sixteen characters, the width
     * {@code XREF-CARD-NUM PIC X(16)} declares at L5 of {@code app/cpy/CVACT03Y.cpy}, because the column is
     * fixed-width and a shorter value is a defect the schema refuses rather than a shorter name it stores.
     * The ordinal occupies the final four positions so that ascending ordinal order and ascending
     * card-number order are the same order, which is what lets the window cases name an expected row by
     * its ordinal.</p>
     *
     * @param ordinal the one-based position of the row within the account's card set
     * @return the sixteen-character card number for that position; never {@code null}
     */
    private static String multiCardNumber(int ordinal) {
        return String.format("411122223333%04d", ordinal);
    }

    /**
     * Extracts the card numbers of a read, in the order the read returned them.
     *
     * <p>Assumptions: the assertions in this class compare keys rather than entities, because the key is
     * what an ordering and a resume position are expressed in and because comparing entities would make
     * every window case depend on how the entity decides two rows are the same.</p>
     *
     * @param rows the rows a read returned, whose order is preserved; must not be {@code null}
     * @return the card number of each row in the same order; never {@code null}
     */
    private static List<String> cardNumbersOf(List<CardXref> rows) {
        return rows.stream().map(CardXref::getCardNum).toList();
    }

    /**
     * The minimal Spring Boot configuration every case in this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the queue listener, the identity client and the
     * cipher this module's own application class registers stay out of the context. Naming the two
     * persistence packages leaves the framework's own auto-configuration to build the pool from the
     * properties the container registered and to run the migration ahead of it -- and it is that migration
     * which creates {@code idx_card_xref_account_id}, so the index this class explains a plan over exists
     * only because it ran. The migration engine works at all only because two artifacts are on the
     * classpath: the migration starter and its PostgreSQL companion, which the engine has required as a
     * separate artifact since it stopped shipping database support in its core. Both are managed by the
     * parent and neither omission would be a compilation error -- each surfaces at run time as a missing
     * relation.</p>
     *
     * <p>Assumptions: {@code DataSourceConfig} is imported rather than left to a component scan, and it is
     * the one production configuration class this context needs. It owns the schema name and pins the
     * session search path on every pooled connection, which is what makes the unqualified table names in
     * this class -- including in every statement it explains -- resolve inside this context's schema.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    // WHY : Assumptions: the two auto-configurations excluded here read deployment values the test profile
    //       deliberately does not carry -- the resource-server one evaluates its decoder condition against
    //       a token-issuer address, and the queue one builds a client that needs a region. Both are bound
    //       in the base profile to placeholders with no fallback, so leaving either in this context ends
    //       context load while the condition is being evaluated, reporting a configuration failure in place
    //       of any assertion in this class. Both sibling integration tests in this package exclude the same
    //       pair, so all three load the same shape.
    // WHY : Alternatives Considered: committing a stand-in issuer address and a region into the test profile
    //       so that a bare auto-configuration would start. Rejected because it puts a network location in a
    //       committed file for a context that never calls it, and because a stand-in that resolves is an
    //       invitation for a later context to reach it. Excluding what is not under test leaves neither the
    //       filter chain nor the queue transport covered here, which is correct rather than a gap -- each is
    //       covered where it is configured, and a class about an index plan is not where a transport should
    //       first be exercised.
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    @Import(DataSourceConfig.class)
    static class CrossReferencePersistenceTestApplication {
    }
}
