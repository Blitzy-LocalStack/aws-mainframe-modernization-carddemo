package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.domain.ReportTransactionView;
import jakarta.persistence.Version;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the report query surface reads one date range two ways that are never interchangeable.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionReportRepository} stands in for the four input reads of
 * {@code app/cbl/CBTRN03C.cbl}, the program {@code app/jcl/TRANREPT.jcl} L59 drives as
 * {@code //STEP10R EXEC PGM=CBTRN03C}. Those four reads are declared at that program's L29 to L49
 * and they are not one shape: the driving transaction input is declared
 * {@code ORGANIZATION IS SEQUENTIAL} at L30 and is scanned once end to end, while the card
 * cross-reference at L33, the transaction type at L39 and the transaction category at L45 are each
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS RANDOM} and are looked up by key. This
 * class asserts that both halves of the migrated surface behave as the reference behaves, over a
 * real engine carrying the shipped relation definitions.</p>
 *
 * <p>Two consumers reach that surface and each gets its own nested class here, so the distinction
 * is structural rather than a matter of reading the method names carefully. Report generation
 * consumes an open cursor over a whole range; the interactive list consumes one keyset window at a
 * time. What is asserted of each is stated on the nested class that asserts it.</p>
 *
 * <h2>What this class asserts that no sibling asserts</h2>
 *
 * <p>Assumptions: the other classes in this directory divide the module's engine-backed properties
 * between them, and this one takes the report surface rather than restating any of theirs.
 * {@code ReportingQueryBootstrapIT} establishes no relation and proves the declared queries parse;
 * {@code StatementHeadingChunkIT} seeds stand-in tables for the heading walk;
 * {@code ReportingDeployedRelationIT} applies the shipped definitions and proves the projections
 * mask and that the login role cannot write; {@code StatementCardXrefRepositoryIT} drives the
 * statement run's cross-reference cursor and owns the two-level write-refusal proof. None of the
 * four calls a method on {@link TransactionReportRepository} and none loads
 * {@code fixtures/tranfile.txt}. This class is the only consumer of both.</p>
 *
 * <h2>Why the shipped definitions are applied rather than reproduced</h2>
 *
 * <p>Assumptions: every relation read below is brought into being by executing the shipped files
 * themselves, read from the working tree and handed to the engine unchanged. Nothing here authors a
 * schema object of any kind, and no statement below defines one. The authority for the schemas, the
 * roles and the privileges is {@code data-migration/sql/V0__schemas_and_roles.sql}; the authority
 * for the projections is {@code data-migration/sql/V1__reporting_views.sql}; the base tables beneath
 * them belong to four other services. Register row <b>R11</b> of the main-tree charter rules that a
 * relation absent at run time is a defect to report against whichever file owns it, never something
 * for a class here to supply.</p>
 *
 * <h2>The two joins, never conflated</h2>
 *
 * <p>Assumptions: the report path joins the driving transaction relation, the card cross-reference,
 * the transaction type and the transaction category, evidenced by the data definitions at
 * {@code app/jcl/TRANREPT.jcl} L65, L67, L69 and L71, with L73 supplying the date range as a control
 * card rather than as a join participant. The statement path joins its own four, at
 * {@code app/jcl/CREASTMT.JCL} L83, L84, L85 and L86, and the two paths share only the transaction
 * relation and the cross-reference. {@code TRANREPT.jcl} declares no account data definition and no
 * customer data definition at all, so the report path reads neither master, and every assertion
 * below stays inside the four participants its own job declares.</p>
 *
 * <h2>Parameters, return values, exceptions or errors</h2>
 *
 * <p>This is a test class with no constructor a caller invokes, no value it yields and no exception
 * it raises outside the test engine, so the type accepts no parameter, returns nothing and raises
 * nothing. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids a docstring that omits parameters, return values or exceptions.</p>
 */
@Testcontainers
// WHAT: names one narrow nested configuration as the whole context rather than the module root.
// WHY : Assumptions: services/reporting-service/src/test/resources/application-test.yml is the
//       constraint. It declares the token issuer as the EMPTY STRING rather than leaving the key
//       absent, and carries no orchestrator execution identifier and no object-store bucket, so a
//       context that scanned the module root would build the filter chain and the orchestration
//       client and abort the refresh while bean definitions were still loading. Alternatives
//       Considered: a data-access test slice, which would restrict auto-configuration to
//       persistence outright. It is unavailable -- the artifact carrying that annotation is absent
//       from services/reporting-service/pom.xml and from the reactor's managed set -- and adding a
//       dependency to reshape a test is not a trade this module makes.
@SpringBootTest(
        classes = TransactionReportRepositoryIT.ReportQueryTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHAT: pins a region and switches off the two configuration importers.
    // WHY : Assumptions: this module carries cloud starters as compile dependencies, so a context
    //       that enables auto-configuration at all builds their beans and the region provider
    //       resolves eagerly. Nothing below contacts a cloud service, so the value identifies
    //       nothing this class reaches, and the two disabled importers would otherwise walk the
    //       whole credential-provider chain looking for a store to read. The second key's NAME
    //       mentions a secret store while its VALUE is what stops one from being read, so neither
    //       key is a credential.
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class TransactionReportRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and the properties asserted here
     * that depend on the engine -- collation-dependent ordering, exact decimal arithmetic and
     * server-side cursor behaviour -- are ones an engine version can genuinely change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The two session settings the shipped bootstrap requires before it will run.
     *
     * <p>Assumptions: both are opt-outs the bootstrap reads through {@code current_setting} with its
     * missing-value flag set, and it raises rather than proceeding when neither is on: one
     * acknowledges an unencrypted local connection, the other accepts roles arriving without a
     * credential because their source is a secret store no test has. They are applied as session
     * settings intermixed with the file option, so a setting made before the file is still in force
     * while the file executes.</p>
     */
    private static final List<String> BOOTSTRAP_ACKNOWLEDGEMENTS = List.of(
            "carddemo.bootstrap_allow_insecure",
            "carddemo.bootstrap_allow_missing_credentials");

    /**
     * The shipped definition files, repository-relative, in the order an operator applies them.
     *
     * <p>Assumptions: the bootstrap appears TWICE and the repetition is required rather than
     * defensive. Its first pass creates the schemas and the roles; the cross-schema read privileges
     * it conveys to the projection owner are conditional on the base tables existing, and on that
     * pass none of them does. The projections execute with their owner's rights, so a privilege
     * absent at creation surfaces on the first READ rather than at creation, which is exactly the
     * failure the second pass removes.</p>
     *
     * <p>Assumptions: the reference context's seed migration is deliberately not among these. The
     * two reference relations this class reads are loaded from the committed fixtures below instead,
     * so that the type and category domains under test are the ones
     * {@code fixtures/trantype.txt} and {@code fixtures/trancatg.txt} declare and nothing else.
     * Applying the seed as well would put two populations in one keyed relation and the loads would
     * collide on the primary key.</p>
     *
     * <p>Assumptions: the card context's two definitions are applied although this class reads no
     * card relation and neither baseline member reads a card store -- the data definitions of
     * {@code app/jcl/TRANREPT.jcl} L65 to L72 resolve to the transaction store, the card
     * cross-reference and two reference stores, and those of {@code app/jcl/CREASTMT.JCL} L83 to L86
     * resolve to the transaction store and three account-side stores, so their union is the ledger,
     * account and reference schemas alone. The shipped bootstrap nonetheless conveys the projection
     * owner usage over four schemas at {@code data-migration/sql/V0__schemas_and_roles.sql} L1578 and
     * read over all card tables at its L1593, and its L1590 conveys read by default privilege, which
     * reaches only relations the card owner creates AFTERWARDS. A run that created the card schema
     * and never its tables would therefore leave this harness holding a narrower privilege shape than
     * the deployed system holds, and a privilege-scoped assertion would then pass here for a reason
     * that does not hold in the deployed system. Applying the list whole keeps the two shapes
     * identical. Register row <b>R15</b> of the main-tree charter records the wider privileged set as
     * a stated divergence rather than one to be narrowed.</p>
     */
    private static final List<String> DEPLOYED_SCHEMA_SCRIPTS = List.of(
            "data-migration/sql/V0__schemas_and_roles.sql",
            "services/account-service/src/main/resources/db/migration/V1__account.sql",
            "services/account-service/src/main/resources/db/migration/"
                    + "V2__account_inquiry_reply_ledger.sql",
            "services/card-service/src/main/resources/db/migration/V1__card.sql",
            "services/card-service/src/main/resources/db/migration/V2__card_num_digit_domain.sql",
            "services/reference-service/src/main/resources/db/migration/V1__reference.sql",
            "services/transaction-service/src/main/resources/db/migration/V1__ledger.sql",
            "services/transaction-service/src/main/resources/db/migration/"
                    + "V2__ledger_transaction_id_allocator.sql",
            "services/transaction-service/src/main/resources/db/migration/"
                    + "V3__ledger_bytewise_collation.sql",
            "data-migration/sql/V0__schemas_and_roles.sql",
            "data-migration/sql/V1__reporting_views.sql");

    /** The classpath directory holding the fixture corpus this class loads. */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The driving transaction fixture, carrying the 350-byte record of {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Assumptions: this fixture stands in for the {@code TRANFILE} data definition at
     * {@code app/jcl/TRANREPT.jcl} L65 to L66, whose data set is the sort output declared at that
     * job's L55, so its layout is {@code CVTRA05Y} and not the daily-transaction layout.</p>
     */
    private static final String TRANSACTION_FIXTURE = "tranfile.txt";

    /**
     * The REPORT-path cross-reference fixture, four rows of the 50-byte record.
     *
     * <p>Assumptions: this is the {@code CARDXREF} data definition at
     * {@code app/jcl/TRANREPT.jcl} L67 to L68 and is a different artifact from
     * {@code fixtures/xreffile.txt}, which stands in for the statement path's {@code XREFFILE} at
     * {@code app/jcl/CREASTMT.JCL} L84 and carries far more cards. The two are never conflated:
     * only one of them may be loaded, because the relation's key is the card number and the two
     * populations overlap.</p>
     */
    private static final String REPORT_PATH_XREF_FIXTURE = "cardxref.txt";

    /** The transaction type fixture, standing in for {@code app/jcl/TRANREPT.jcl} L69's input. */
    private static final String TYPE_FIXTURE = "trantype.txt";

    /** The transaction category fixture, standing in for {@code TRANREPT.jcl} L71's input. */
    private static final String CATEGORY_FIXTURE = "trancatg.txt";

    /**
     * The registered descriptor the driving fixture decodes against.
     *
     * <p>Assumptions: the name is {@code TRAN} and never {@code TRNX}. Both descriptors declare a
     * 350-byte record, so a record length cannot tell them apart, but {@code TRNX} is the rearranged
     * geometry {@code app/cpy/COSTM01.CPY} declares for the statement path, whose amount sits
     * sixteen bytes further along. Decoding this fixture against that descriptor would therefore
     * read every amount from the wrong offset and still succeed.</p>
     */
    private static final String TRANSACTION_DESCRIPTOR = "TRAN";

    /** The registered descriptor the cross-reference fixture decodes against. */
    private static final String XREF_DESCRIPTOR = "XREF";

    /** The registered descriptor the transaction type fixture decodes against. */
    private static final String TYPE_DESCRIPTOR = "TRANTYPE";

    /**
     * The registered descriptor the transaction category fixture decodes against.
     *
     * <p>Assumptions: this descriptor's key is SIX bytes -- a two-byte type and a four-byte category
     * -- and its group name collides with the unrelated SEVENTEEN-byte group of the same name in
     * {@code app/cpy/CVTRA01Y.cpy}, which the category-balance descriptor carries. Field names are
     * therefore scoped per copybook and never resolved globally; the two records share a group name
     * at two different widths.</p>
     */
    private static final String CATEGORY_DESCRIPTOR = "TRANCAT";

    /**
     * The category-balance fixture, which corroborates the money regime from outside this surface.
     *
     * <p>Assumptions: this fixture backs the SECOND report in the reference set,
     * {@code app/jcl/PRTCATBL.jcl} {@code STEP10R}, which is a sort step with no COBOL program at
     * all, and it is loaded here as independent third-party evidence that the base-master money
     * regime is ZONED decimal rather than packed. That job's own sort symbols at its L47 to L50
     * declare the same four offsets this record's descriptor declares, and its L50
     * {@code TRAN-CAT-BAL,18,11,ZD} states both that the balance is zoned and that eleven bytes is
     * the width of a {@code PIC S9(09)V99}. Two unrelated members agreeing on a width is what makes
     * the amount assertions below rest on evidence rather than on one reading.</p>
     */
    private static final String CATEGORY_BALANCE_FIXTURE = "tcatbal.txt";

    /**
     * The registered descriptor the category-balance fixture decodes against.
     *
     * <p>Assumptions: its key is SEVENTEEN bytes, against the six of {@link #CATEGORY_DESCRIPTOR},
     * and the two records nonetheless declare a group of the same name. That collision is why a
     * field name is looked up inside one record's descriptor and never across the registry.</p>
     */
    private static final String CATEGORY_BALANCE_DESCRIPTOR = "TCATBAL";

    /**
     * The first business date the report admits, inclusive.
     *
     * <p>Assumptions: the value is the reference's own injected parameter, declared at
     * {@code app/jcl/TRANREPT.jcl} L43 as {@code PARM-START-DATE,C'2022-01-01'}. It is a literal
     * here for the same reason it is a literal there: the range is INJECTED into a run rather than
     * read from a clock, which is what lets two runs over one corpus produce one report.</p>
     */
    private static final LocalDate REPORT_START_DATE = LocalDate.parse("2022-01-01");

    /**
     * The last business date the report admits, inclusive.
     *
     * <p>Assumptions: declared at {@code app/jcl/TRANREPT.jcl} L44 as
     * {@code PARM-END-DATE,C'2022-07-06'}, and inclusive because the program's own comparison at
     * {@code app/cbl/CBTRN03C.cbl} L174 is less-than-or-equal against it.</p>
     */
    private static final LocalDate REPORT_END_DATE = LocalDate.parse("2022-07-06");

    /**
     * The number of driving rows the committed corpus places inside the report range.
     *
     * <p>Assumptions: this is a property of {@code fixtures/tranfile.txt} rather than of the query.
     * Of its 31 rows, 26 carry a processing timestamp inside the inclusive range and 5 do not: one
     * dated the day before the opening bound, one dated the day after the closing bound, and three
     * carrying an unresolvable dimension that are additionally date-shifted well outside the range
     * so that they cannot perturb any in-range assertion.</p>
     */
    private static final int IN_RANGE_ROW_COUNT = 26;

    /**
     * The number of values the reference detail line assembles, and therefore the number printed.
     *
     * <p>Assumptions: {@code 1120-WRITE-DETAIL} at {@code app/cbl/CBTRN03C.cbl} L361 to L374 carries
     * exactly eight {@code MOVE} statements into the detail record, at its L363 through L370, and
     * the projection carries exactly those eight printed values plus two ordering members that reach
     * no output band.</p>
     */
    private static final int PRINTED_DETAIL_COLUMN_COUNT = 8;

    /**
     * The number of members the row projection declares in total.
     *
     * <p>Assumptions: eight printed values plus the two ordering members. The two are declared on
     * the projection because the cursor key and the join are built from them, and the 133-column
     * layout {@code app/cpy/CVTRA07Y.cpy} declares carries neither of them.</p>
     */
    private static final int PROJECTION_MEMBER_COUNT = 10;

    /**
     * The window size the keyset cases request.
     *
     * <p>Assumptions: five is chosen because it divides the 26 in-range rows unevenly -- five full
     * windows and a final window of one -- so a short final window is exercised rather than assumed.
     * Trade-offs: it is deliberately none of the arities that appear elsewhere in this system, so it
     * can be mistaken neither for the 20-line output band nor for either query hint.</p>
     */
    private static final int WINDOW_SIZE = 5;

    /**
     * The number of windows {@link #WINDOW_SIZE} divides the in-range corpus into.
     *
     * <p>Assumptions: 26 rows in windows of five is six windows, the last carrying one row.</p>
     */
    private static final int WINDOW_COUNT = 6;

    /**
     * The row bound the whole-range reads pass, chosen to exceed the corpus rather than to match it.
     *
     * <p>Assumptions: {@code fixtures/tranfile.txt} carries 31 rows in total, so a bound of 64 cannot
     * be the thing that limits any result below. A bound equal to the expected count would make a
     * count assertion pass whether the query admitted that many rows or more, which is the one thing
     * those assertions exist to distinguish.</p>
     */
    private static final int RANGE_READ_BOUND = 64;

    /** The transaction identifier of the corpus row whose card resolves to no cross-reference. */
    private static final String ORPHAN_CARD_TRANSACTION_ID = "0000000000000031";

    /** The transaction identifier of the corpus row whose type code resolves to no type. */
    private static final String ORPHAN_TYPE_TRANSACTION_ID = "0000000000000014";

    /** The transaction identifier of the corpus row whose category key resolves to no category. */
    private static final String ORPHAN_CATEGORY_TRANSACTION_ID = "0000000000000015";

    /**
     * The business date the three unresolvable rows carry.
     *
     * <p>Assumptions: they are date-shifted outside the report range on purpose, so the report's own
     * inclusive range never joins them and the reconciliation surface has to be asked about their
     * date explicitly to see them at all.</p>
     */
    private static final LocalDate ORPHAN_BUSINESS_DATE = LocalDate.parse("2021-01-15");

    /**
     * The account two of the four cross-reference rows share.
     *
     * <p>Assumptions: the corpus supplies this case deliberately, because the reference breaks on a
     * CARD and not on an account. {@code WS-CURR-CARD-NUM PIC X(16)} at
     * {@code app/cbl/CBTRN03C.cbl} L137 is a sixteen-byte card number, and the break test at its
     * L181 compares it against {@code TRAN-CARD-NUM}, so an implementation grouping by account
     * identifier would merge two runs the reference keeps separate whenever one account holds more
     * than one card.</p>
     */
    private static final long SHARED_ACCOUNT_ID = 50L;

    /** The number of cards {@link #SHARED_ACCOUNT_ID} holds in the committed corpus. */
    private static final int SHARED_ACCOUNT_CARD_COUNT = 2;

    /** The declared byte length of the driving record, per {@code app/cpy/CVTRA05Y.cpy} L4 to L18. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /**
     * The ONE-based position {@code app/jcl/TRANREPT.jcl} L41 declares for the card number.
     *
     * <p>Assumptions: the sort symbol there is {@code TRAN-CARD-NUM,263,16,ZD}, and DFSORT positions
     * are one-based. Summing the fourteen declared widths of {@code app/cpy/CVTRA05Y.cpy} places the
     * same field at zero-based 262, and the two readings agree once the single conversion rule is
     * applied: a zero-based position is the one-based position minus one. The rule is stated because
     * the two sources genuinely use two conventions, and applying it backwards shifts every field by
     * one byte and yields values that look plausible.</p>
     */
    private static final int CARD_NUMBER_ONE_BASED_POSITION = 263;

    /** The zero-based offset the descriptor declares for the card number. */
    private static final int CARD_NUMBER_ZERO_BASED_OFFSET = 262;

    /** The ONE-based position {@code app/jcl/TRANREPT.jcl} L42 declares for the timestamp. */
    private static final int PROC_TIMESTAMP_ONE_BASED_POSITION = 305;

    /** The zero-based offset the descriptor declares for the processing timestamp. */
    private static final int PROC_TIMESTAMP_ZERO_BASED_OFFSET = 304;

    /**
     * The decimal scale every monetary value on this surface carries.
     *
     * <p>Assumptions: {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} L10 is eleven
     * bytes of zoned decimal with two decimal places, so the scale is two exactly and is never
     * stripped. A stripped scale would render a whole-currency amount with no minor units and would
     * differ from every reference edit mask.</p>
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * The decimal precision the driving amount column carries.
     *
     * <p>Assumptions: nine integer digits plus two decimal places is eleven, which is both the byte
     * width of the zoned field and the precision of the target column. It is NEVER unified with the
     * twelve-digit precision of the account projection's balance, which comes from a
     * {@code PIC S9(10)V99} of twelve bytes and belongs to a different record entirely.</p>
     */
    private static final int AMOUNT_PRECISION = 11;

    /** The precision belonging to the account projection, named here only to keep the two apart. */
    private static final int ACCOUNT_PROJECTION_PRECISION = 12;

    /**
     * The migration directory this module must not have.
     *
     * <p>Assumptions: this context owns no relational object, so a versioned migration here would
     * order against four other services' histories that it does not share. Register row <b>R11</b>
     * of the main-tree charter records that the relations belong to those services and to the
     * data-migration package.</p>
     */
    private static final String MIGRATION_DIRECTORY =
            "services/reporting-service/src/main/resources/db/migration";

    /** The migration tool type whose absence from the module's runtime classpath is asserted. */
    private static final String MIGRATION_TOOL_TYPE = "org.flywaydb.core.Flyway";

    /**
     * The version prefix a sealed cursor token carries.
     *
     * <p>Assumptions: taken from {@code CursorToken.VERSION} rather than written out, so the
     * stand-in sealer below cannot drift from the shape the envelope accepts.</p>
     */
    private static final String TOKEN_VERSION = CursorToken.VERSION;

    /** The 16-character middle segment a stand-in token carries for a leading boundary. */
    private static final String LEADING_BOUNDARY_TAG = "leadingBoundary_";

    /** The 16-character middle segment a stand-in token carries for a trailing boundary. */
    private static final String TRAILING_BOUNDARY_TAG = "trailingBoundary";

    /**
     * The engine running the shipped definitions, reached through the framework's own connection
     * detail bean.
     *
     * <p>Assumptions: the coordinates arrive from the annotation rather than from
     * {@code application-test.yml}, which declares no datasource location, no login name and no
     * credential precisely so that they do. The container's port is assigned as it starts, so
     * committed text could not have been correct in any case.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The query surface under test, proxied over the relations the shipped definitions create. */
    @Autowired
    private TransactionReportRepository reportQueries;

    /**
     * Applies the shipped definitions and loads the committed corpus once for the whole class.
     *
     * <p>Assumptions: this runs once rather than per case because nothing below writes a relation the
     * next case reads, with one deliberate exception that inserts and then removes its own row and
     * says so where it does it. A per-case load would multiply eleven script executions by the case
     * count for a corpus that does not change.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a shipped definition file is absent from the working tree, or
     *     if the engine client refuses a script, both of which are defects in the file that owns the
     *     definition rather than anything this class may work around
     */
    @BeforeAll
    static void arrangeTheDeployedSchemaAndFixtureCorpus() {
        applyDeployedSchema();
        loadFixtureCorpus();
    }

    /**
     * Surface A, serving REPORT GENERATION: one open cursor over a whole date range.
     *
     * <p>Assumptions: the consumer of this surface is the writing side that produces the 133-column
     * output declared at {@code app/jcl/TRANREPT.jcl} L78 as {@code DCB=(LRECL=133,...)}. It reaches
     * every row of the range in one pass, which is the shape the reference itself has: the mainline
     * loop at {@code app/cbl/CBTRN03C.cbl} L170 to L196 reads one record, writes one detail line and
     * holds no table of lines at all, and its input is declared {@code ORGANIZATION IS SEQUENTIAL} at
     * that program's L30.</p>
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("Surface A -- the report-generation cursor")
    class ReportGenerationCursor {

        /**
         * Confirms the cursor reaches every in-range row once, grouped by card and keyed within it.
         *
         * <p>WHY: the grouping is load-bearing rather than cosmetic. The reference resolves the card
         * cross-reference ONCE PER CARD inside a control break -- {@code app/cbl/CBTRN03C.cbl} L181
         * tests {@code WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM} and only then performs
         * {@code 1500-A-LOOKUP-XREF} at its L186 to L187 -- while the type and category lookups at
         * its L189 to L195 fire for every row. A cursor that interleaved two cards would resolve the
         * cross-reference repeatedly and would break the account subtotal at its L182 to L183 in the
         * wrong place.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("the cursor reaches every in-range row once, grouped by card")
        void theCursorReachesEveryInRangeRowOnceGroupedByCard() {
            List<TransactionReportRepository.ReportLine> lines = drainTheCursor();

            assertThat(lines).hasSize(IN_RANGE_ROW_COUNT);
            assertThat(lines).extracting(TransactionReportRepository.ReportLine::getTransactionId)
                    .doesNotHaveDuplicates();

            // WHAT: requires each card's rows to occupy one unbroken run, and to ascend within it.
            // WHY : Assumptions: the leading ordering component is the keyed per-card digest and NOT
            //       the card number, so the relative order BETWEEN two cards is the digest's and not
            //       the number's -- data-migration/sql/V1__reporting_views.sql L711 to L712 states
            //       exactly that. Asserting card numbers ascend would therefore assert a property the
            //       relation does not have; what the control break at app/cbl/CBTRN03C.cbl L181
            //       actually needs is contiguity, and that is what is asserted.
            assertThat(distinctRunsOf(lines)).isEqualTo(distinctFingerprintsOf(lines));
            assertAscendingWithinEachCard(lines);
        }

        /**
         * Confirms the traversal is a closeable stream and that a bounded read consumes only its
         * bound.
         *
         * <p>WHY: the reference holds no table of report lines, so the migrated surface must not
         * assemble one either. Its input is opened at {@code app/cbl/CBTRN03C.cbl} L376 and closed in
         * {@code 9000-TRANFILE-CLOSE.} at its L514 to L516, and between those two points it holds one
         * record at a time.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws NoSuchMethodException if the declared query method is absent under the name and
         *     parameter types asserted here, which would mean the surface had been renamed and this
         *     assertion had stopped describing it
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("the traversal is a closeable stream and a bounded read consumes only its bound")
        void theTraversalIsACloseableStreamAndABoundedReadConsumesOnlyItsBound()
                throws NoSuchMethodException {

            Method declared = TransactionReportRepository.class.getMethod(
                    "streamReportLinesWithin", LocalDateTime.class, LocalDateTime.class);
            assertThat(declared.getReturnType()).isEqualTo(Stream.class);
            assertThat(AutoCloseable.class.isAssignableFrom(declared.getReturnType())).isTrue();

            int[] reached = new int[1];
            try (Stream<TransactionReportRepository.ReportLine> cursor =
                    reportQueries.streamReportLines(REPORT_START_DATE, REPORT_END_DATE)) {

                // WHAT: counts rows as they pass a bound placed below the counter.
                // WHY : Trade-offs: this establishes that the pipeline is PULL-based and stops at the
                //       bound -- the counter fires WINDOW_SIZE times over a range holding
                //       IN_RANGE_ROW_COUNT rows -- and it deliberately claims nothing more. It does
                //       not prove how many rows the driver buffered per round trip, because the
                //       transport batch is a driver-side matter no assertion in this process can
                //       observe; that value is pinned by a query hint on the method itself and the
                //       honest division is stated rather than blurred. What IS proved is the property
                //       the reference has at app/cbl/CBTRN03C.cbl L170 to L196: no row past the one
                //       being read has to be materialised for the read to proceed.
                List<TransactionReportRepository.ReportLine> bounded = cursor
                        .peek(line -> reached[0]++)
                        .limit(WINDOW_SIZE)
                        .toList();

                assertThat(bounded).hasSize(WINDOW_SIZE);
            }
            assertThat(reached[0]).isEqualTo(WINDOW_SIZE);
            assertThat(IN_RANGE_ROW_COUNT).isGreaterThan(WINDOW_SIZE);
        }

        /**
         * Confirms the cursor refuses to open when no transaction encloses the call.
         *
         * <p>The captured exception is
         * {@link org.springframework.transaction.IllegalTransactionStateException}, raised by the
         * mandatory propagation the query method declares.</p>
         *
         * <p>WHY: a server-side cursor stays usable only while the transaction that opened it lives,
         * so opening one outside a transaction would hand back a traversal that closed underneath its
         * reader partway through a report. The reference brackets its own read the same way, opening
         * at {@code app/cbl/CBTRN03C.cbl} L376 and closing at its L514 to L516; a refusal at the call
         * is the migrated equivalent of never having opened.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the cursor refuses to open with no enclosing transaction")
        void theCursorRefusesToOpenWithNoEnclosingTransaction() {
            assertThatThrownBy(() ->
                    reportQueries.streamReportLines(REPORT_START_DATE, REPORT_END_DATE))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        /**
         * Confirms two traversals over an unchanged corpus yield one identical sequence.
         *
         * <p>WHY: {@code app/jcl/TRANREPT.jcl} L46 is {@code SORT FIELDS=(TRAN-CARD-NUM,A)} -- a
         * SINGLE key with no equal-records qualifier -- so the relative order of two reference rows
         * sharing a card number is whatever that sort happens to produce. Reproducing that literally
         * would leave the migrated output non-deterministic, and a golden-master comparison over a
         * non-deterministic ordering compares nothing.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("two traversals over an unchanged corpus yield one identical sequence")
        void twoTraversalsOverAnUnchangedCorpusYieldOneIdenticalSequence() {
            // WHAT: adds a secondary ordering key the reference sort does not declare.
            // WHY : Trade-offs: the target's order is a SUPERSET constraint -- it fixes an order
            //       between two rows sharing a card, where app/jcl/TRANREPT.jcl L46 leaves it
            //       arbitrary -- and the compromise is accepted so that a rerun reproduces the same
            //       bytes. It can only ever differ from the reference where the reference itself was
            //       arbitrary. That two of the reference's own sorts are already total is what shows
            //       the single-key declaration to be particular to this one job rather than a house
            //       convention: app/jcl/CREASTMT.JCL L53 declares SORT FIELDS=(263,16,CH,A,1,16,CH,A)
            //       with two keys, and app/jcl/PRTCATBL.jcl L52 declares three.
            List<String> first = identifiersOf(drainTheCursor());
            List<String> second = identifiersOf(drainTheCursor());

            assertThat(second).containsExactlyElementsOf(first);
        }

        /**
         * Drains the whole report cursor into a list, closing it as the contract requires.
         *
         * @return every resolved line the report range admits, in the order the cursor yields them,
         *     never {@code null}
         */
        private List<TransactionReportRepository.ReportLine> drainTheCursor() {
            try (Stream<TransactionReportRepository.ReportLine> cursor =
                    reportQueries.streamReportLines(REPORT_START_DATE, REPORT_END_DATE)) {
                return cursor.toList();
            }
        }
    }

    /**
     * Surface B, serving the INTERACTIVE LIST: one keyset window at a time, in either direction.
     *
     * <p>Assumptions: the consumer of this surface is a client rendering one screen of rows and
     * stepping between screens, which is the shape the reference's browse screens have. The
     * provenance is {@code app/cbl/COCRDLIC.cbl} L229 to L244 and nothing else: that block declares a
     * last-key pair at its L230 to L232, a first-key pair at its L233 to L235, a screen number at its
     * L237, a last-page-displayed flag at its L239 and a next-page indicator at its L242 to L244, and
     * it carries no row ordinal anywhere. The migrated envelope's leading key, trailing key and
     * further-window indicator are that block, one member at a time.</p>
     *
     * <p>Assumptions: one plausible justification for this shape is wrong and is named so it is not
     * reintroduced. The statement generator's keyed read is NOT a partial-key browse:
     * {@code app/cbl/CBSTM03A.CBL} L373 moves a transient zero into the key-length field and its
     * L374 immediately recomputes the full length from {@code LENGTH OF XREF-CUST-ID}, with L397 and
     * L398 doing the same for the account identifier, so it addresses one row by a whole key. A
     * repository-wide search for a zero-length reference modification returns nothing at all.</p>
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("Surface B -- the interactive keyset window")
    class InteractiveKeysetWindow {

        /**
         * Confirms the leading window names its own boundaries and reports a further window.
         *
         * <p>WHY: the reference sets its own next-page indicator by discovering one record more than
         * fits, the lookahead read at {@code app/cbl/COCRDLIC.cbl} L1197, and stores the boundary keys
         * in the communication area at its L230 to L235. The envelope carries the same three answers.
         * </p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the leading window names its own boundaries and reports a further window")
        void theLeadingWindowNamesItsOwnBoundariesAndReportsAFurtherWindow() {
            PageResponse<TransactionReportRepository.ReportLine> window = readFirstWindow();

            assertThat(window.items()).hasSize(WINDOW_SIZE);
            assertThat(window.hasNext()).isTrue();
            assertThat(window.firstKey()).isNotNull();
            assertThat(window.lastKey()).isNotNull();

            // WHAT: requires the trailing boundary to name the last row the window actually carried.
            // WHY : Assumptions: register row R2 of the main-tree charter settles this and is cited
            //       rather than restated -- the reference is genuinely inconsistent between its two
            //       browse screens, app/cbl/COCRDLIC.cbl L1207 to L1214 overwriting the stored key
            //       with the probe row's key while app/cbl/COTRN00C.cbl L305 to L313 discards it, and
            //       the target follows the second. The probe row is by construction a row the client
            //       never received.
            assertThat(unsealForTest(window.lastKey()))
                    .isEqualTo(lastOf(window.items()).getTransactionId());
            assertThat(unsealForTest(window.firstKey()))
                    .isEqualTo(window.items().get(0).getTransactionId());
        }

        /**
         * Confirms a forward walk visits every in-range row once and ends with no further window.
         *
         * <p>WHY: the reference's forward browse is a quartet of positioned reads --
         * {@code STARTBR} at {@code app/cbl/COCRDLIC.cbl} L1129, {@code READNEXT} at its L1146, the
         * lookahead at its L1197 and {@code ENDBR} at its L1258 -- driven until the store reports
         * nothing further. A walk that skipped or repeated a row would produce a report of a
         * different length from the one the sequential pass at
         * {@code app/cbl/CBTRN03C.cbl} L170 to L196 produces over the same range.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a forward walk visits every in-range row once and ends without a further window")
        void aForwardWalkVisitsEveryInRangeRowOnceAndEndsWithoutAFurtherWindow() {
            List<PageResponse<TransactionReportRepository.ReportLine>> windows = walkForward();

            assertThat(windows).hasSize(WINDOW_COUNT);
            assertThat(lastOf(windows).hasNext()).isFalse();
            assertThat(lastOf(windows).items()).hasSize(IN_RANGE_ROW_COUNT % WINDOW_SIZE);

            List<String> walked = new ArrayList<>();
            for (PageResponse<TransactionReportRepository.ReportLine> window : windows) {
                walked.addAll(identifiersOf(window.items()));
            }
            assertThat(walked).hasSize(IN_RANGE_ROW_COUNT).doesNotHaveDuplicates();
        }

        /**
         * Confirms a forward step returns only rows ordered strictly past the opened position.
         *
         * <p>WHY: the reference's read-next resumes from the stored trailing key rather than from the
         * start of the file, which is what {@code app/cbl/COCRDLIC.cbl} L230 to L232 exists to carry
         * between two turns of a pseudo-conversational task.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a forward step returns only rows ordered strictly past the opened position")
        void aForwardStepReturnsOnlyRowsOrderedStrictlyPastTheOpenedPosition() {
            List<PageResponse<TransactionReportRepository.ReportLine>> windows = walkForward();
            List<String> walked = new ArrayList<>();
            for (PageResponse<TransactionReportRepository.ReportLine> window : windows) {
                walked.addAll(identifiersOf(window.items()));
            }

            PageResponse<TransactionReportRepository.ReportLine> second = windows.get(1);
            String anchor = unsealForTest(windows.get(0).lastKey());

            // WHAT: requires every row of the following window to sit past the anchor in walk order.
            // WHY : Assumptions: the comparison is on WALK POSITION and not on the identifier's own
            //       collation, because the ordering is led by the per-card digest and an identifier
            //       is only ordered WITHIN a card. Comparing identifiers directly would assert a
            //       total order on the secondary key that the relation does not carry, and would pass
            //       or fail according to which cards the digest happened to place first.
            int anchorPosition = walked.indexOf(anchor);
            assertThat(anchorPosition).isEqualTo(WINDOW_SIZE - 1);
            for (TransactionReportRepository.ReportLine line : second.items()) {
                assertThat(walked.indexOf(line.getTransactionId())).isGreaterThan(anchorPosition);
            }
        }

        /**
         * Confirms a backward step returns the preceding window reversed into ascending order.
         *
         * <p>WHY: the reference reads backward from the store rather than re-reading forward from the
         * start, priming with {@code READPREV} at {@code app/cbl/COCRDLIC.cbl} L1294 and looping at
         * its L1322, and it then displays the screen ascending. A surface that could only step
         * forward would not be a migration of a browse that both {@code app/cbl/COCRDLIC.cbl} and
         * {@code app/cbl/COTRN00C.cbl} drive in both directions.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a backward step returns the preceding window reversed into ascending order")
        void aBackwardStepReturnsThePrecedingWindowReversedIntoAscendingOrder() {
            List<PageResponse<TransactionReportRepository.ReportLine>> windows = walkForward();
            PageResponse<TransactionReportRepository.ReportLine> third = windows.get(2);

            PageResponse<TransactionReportRepository.ReportLine> steppedBack =
                    reportQueries.readPreviousReportLines(
                            REPORT_START_DATE,
                            REPORT_END_DATE,
                            unsealForTest(third.firstKey()),
                            WINDOW_SIZE,
                            TransactionReportRepositoryIT::sealForTest);

            assertThat(identifiersOf(steppedBack.items()))
                    .containsExactlyElementsOf(identifiersOf(windows.get(1).items()));

            // WHAT: requires a further window to be reported unconditionally on the backward path.
            // WHY : Assumptions: the reference does the same -- app/cbl/COCRDLIC.cbl L1287 sets its
            //       next-page-exists condition on the backward path without testing anything -- and
            //       it is a fact about the request rather than an assumption: a caller can only step
            //       backward from a window it already holds, so that window lies ahead of these rows.
            assertThat(steppedBack.hasNext()).isTrue();
        }

        /**
         * Confirms a backward step from the leading window returns nothing but keeps its position.
         *
         * <p>WHY: the reference's own first entry has no cursor to step back from -- the condition
         * {@code CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} at {@code app/cbl/COCRDLIC.cbl} L243
         * describes an unpositioned arrival -- so an exhausted backward step is an ordinary answer
         * rather than a failure, and it still has to name where the caller is standing.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a backward step from the leading window returns nothing but keeps its position")
        void aBackwardStepFromTheLeadingWindowReturnsNothingButKeepsItsPosition() {
            PageResponse<TransactionReportRepository.ReportLine> first = readFirstWindow();

            PageResponse<TransactionReportRepository.ReportLine> steppedBack =
                    reportQueries.readPreviousReportLines(
                            REPORT_START_DATE,
                            REPORT_END_DATE,
                            unsealForTest(first.firstKey()),
                            WINDOW_SIZE,
                            TransactionReportRepositoryIT::sealForTest);

            assertThat(steppedBack.items()).isEmpty();

            // WHAT: expects the surviving position in lastKey while firstKey is absent.
            // WHY : Assumptions: the two are easy to transpose and the envelope's own contract settles
            //       which is which. Its filtered-empty factory names its parameters after the
            //       DIRECTION each continues rather than after the component each lands in, and it
            //       stores the forward position in lastKey and reports a further window exactly when
            //       that position is present. The surface under test supplies the position it was
            //       stepped from as that argument, so an exhausted backward step carries lastKey and
            //       no firstKey -- which is what its own return documentation states. The token is
            //       nonetheless sealed under the LEADING binding, because backward is the only step a
            //       caller standing here can take, and the two facts are independent: one is which
            //       component holds the position, the other is which direction the token may be
            //       redeemed in.
            assertThat(steppedBack.firstKey()).isNull();
            assertThat(steppedBack.lastKey()).isNotNull();
            assertThat(unsealForTest(steppedBack.lastKey()))
                    .isEqualTo(first.items().get(0).getTransactionId());
            assertThat(boundaryTagOf(steppedBack.lastKey())).isEqualTo(LEADING_BOUNDARY_TAG);
            assertThat(steppedBack.hasNext()).isTrue();
        }

        /**
         * Confirms every window carries the size and the further-window answer the corpus implies.
         *
         * <p>WHY: the reference publishes the same two answers per screen and derives the second by
         * reading one record past the screen -- the lookahead at {@code app/cbl/COCRDLIC.cbl} L1197 to
         * L1214 and the one at {@code app/cbl/COTRN00C.cbl} L305 to L313 -- so checking every window
         * of a walk rather than only the first is what shows the derivation holds at each position and
         * not merely at the start.</p>
         *
         * @param windowOrdinal the one-based position of the window being checked, used only to
         *     select it out of the forward walk
         * @param expectedRows the number of rows that window is expected to carry
         * @param expectedFurther whether a further window is expected to be reported from it
         */
        @ParameterizedTest(name = "window {0} carries {1} row(s) and reports further={2}")
        @MethodSource(
                "com.carddemo.reporting.repository.TransactionReportRepositoryIT#windowExpectations")
        @DisplayName("every window carries the size and further-window answer the corpus implies")
        void everyWindowCarriesTheSizeAndFurtherAnswerTheCorpusImplies(
                int windowOrdinal, int expectedRows, boolean expectedFurther) {

            PageResponse<TransactionReportRepository.ReportLine> window =
                    walkForward().get(windowOrdinal - 1);

            // WHAT: reads the further-window answer as a probe result rather than as a tally.
            // WHY : Assumptions: the reference establishes it by reading one record past the screen
            //       and setting a flag from whether that record was found -- the lookahead at
            //       app/cbl/COCRDLIC.cbl L1197 to L1214 and the one at app/cbl/COTRN00C.cbl L305 to
            //       L313 -- so the answer is a fact about the relation at that position. A count of
            //       how many rows exist altogether would answer a different question at a cost that
            //       grows with the range.
            assertThat(window.items()).hasSize(expectedRows);
            assertThat(window.hasNext()).isEqualTo(expectedFurther);
        }

        /**
         * Confirms a window boundary neither skips nor repeats a row when a row is inserted across
         * it.
         *
         * <p>WHY: this is the concrete reason addressing a window by row ordinal is refused. Insert a
         * row ahead of a position and every ordinal behind the insertion moves by one, so two
         * consecutive requests omit rows and return others twice; a key does not move when a
         * neighbour is inserted. Register row <b>R1</b> of the main-tree charter reaches the same
         * conclusion from the reference's own cursor at {@code app/cbl/COCRDLIC.cbl} L229 to L244,
         * which carries key pairs and no ordinal at all.</p>
         *
         * <p>Alternatives Considered: addressing a window by ordinal position -- a row count to skip
         * plus a row count to take -- which is the shape most query interfaces reach for first and
         * which this surface refuses outright. It was evaluated and rejected on the behaviour this
         * case measures: the interleaved row below makes an ordinal-addressed second request re-read
         * one row and omit another, while the key-addressed request admits the new row without
         * disturbing the boundary the caller holds. The reference never had the defect to begin with,
         * because its browse state at {@code app/cbl/COCRDLIC.cbl} L230 to L235 is a pair of keys, so
         * an ordinal interface would be a migration of something that is not there.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws SQLException if the interleaved row cannot be written or removed, which would leave
         *     the corpus altered for every later case and is therefore raised rather than swallowed
         */
        @Test
        @DisplayName("a window boundary neither skips nor repeats a row inserted across it")
        void aWindowBoundaryNeitherSkipsNorRepeatsARowInsertedAcrossIt() throws SQLException {
            PageResponse<TransactionReportRepository.ReportLine> first = readFirstWindow();
            List<String> firstIdentifiers = identifiersOf(first.items());
            String interleavedCard = first.items().get(0).getCardNum();

            // WHAT: writes one extra row onto the card the open window starts on, then reads on.
            // WHY : Assumptions: the row is placed on THAT card and given an identifier below the
            //       window's own leading identifier, so under the declared order it lands ahead of
            //       the boundary the caller is holding -- which is precisely the position at which an
            //       ordinal-addressed second request would re-read a row it had already shown. The
            //       card is taken from the returned row rather than written out, because the relation
            //       publishes the number narrowed to its last four digits and a literal here would
            //       either be a whole card number or would not match.
            String interleavedId = interleavedIdentifierBelow(firstIdentifiers.get(0));
            try {
                insertInterleavedTransaction(interleavedId, wholeCardNumberBehind(interleavedCard));

                PageResponse<TransactionReportRepository.ReportLine> second =
                        reportQueries.readNextReportLines(
                                REPORT_START_DATE,
                                REPORT_END_DATE,
                                unsealForTest(first.lastKey()),
                                WINDOW_SIZE,
                                TransactionReportRepositoryIT::sealForTest);

                List<String> secondIdentifiers = identifiersOf(second.items());
                assertThat(secondIdentifiers).doesNotContainAnyElementsOf(firstIdentifiers);
                assertThat(secondIdentifiers).doesNotContain(interleavedId);
                assertThat(secondIdentifiers).hasSize(WINDOW_SIZE).doesNotHaveDuplicates();

                PageResponse<TransactionReportRepository.ReportLine> reread =
                        readFirstWindow();
                assertThat(identifiersOf(reread.items())).contains(interleavedId);
            } finally {
                // WHAT: removes the interleaved row again before leaving the case.
                // WHY : Assumptions: every other case in this class reads the committed corpus
                //       exactly as fixtures/tranfile.txt declares it, and the arrangement runs once
                //       for the whole class rather than once per case. Leaving the row behind would
                //       make the in-range row count wrong for every case that runs after this one,
                //       and the removal is a data statement rather than a definition, so it stays
                //       inside the boundary this directory draws.
                deleteInterleavedTransaction(interleavedId);
            }
        }

        /**
         * Confirms a window size that cannot describe a window is refused.
         *
         * <p>The captured exception is
         * {@link org.springframework.dao.InvalidDataAccessApiUsageException}, carrying a nested
         * {@link java.lang.IllegalArgumentException} which is what the surface's own window-size guard
         * raises before any query is issued.</p>
         *
         * <p>WHY: the reference's two browse screens each declare a positive screen size -- seven at
         * {@code app/cbl/COCRDLIC.cbl} L177 to L178 and ten at {@code app/cbl/COTRN00C.cbl} L290 --
         * and neither has a representation for a screen of nothing. A request for none would read
         * only the probe row and report a further window while carrying nothing.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a window size that cannot describe a window is refused")
        void aWindowSizeThatCannotDescribeAWindowIsRefused() {
            // WHAT: expects the framework's data-access wrapper around the guard's own exception.
            // WHY : Assumptions: this was MEASURED rather than predicted. The surface is reached
            //       through a repository proxy carrying the framework's persistence exception
            //       translator, and that translator converts an IllegalArgumentException escaping any
            //       repository method into InvalidDataAccessApiUsageException with the original as its
            //       cause. Asserting the raw type would therefore fail even though the guard fired
            //       exactly as intended, and asserting only the wrapper would not show WHICH guard
            //       fired -- so both the wrapper and the nested type are named, and the message the
            //       guard composed is asserted with them.
            assertThatThrownBy(() -> reportQueries.readNextReportLines(
                    REPORT_START_DATE,
                    REPORT_END_DATE,
                    null,
                    0,
                    TransactionReportRepositoryIT::sealForTest))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a window carries at least one row");
        }

        /**
         * Confirms a cursor key this surface did not produce is refused rather than positioned on.
         *
         * <p>The captured exception is
         * {@link org.springframework.dao.InvalidDataAccessApiUsageException}, carrying a nested
         * {@link java.lang.IllegalArgumentException} raised by the surface's cursor-key width
         * check.</p>
         *
         * <p>WHY: an unchecked key silently yields a position instead of an error. A well-formed key
         * is the sixteen characters {@code TRAN-ID PIC X(16)} declares at
         * {@code app/cpy/CVTRA05Y.cpy} L5; a key of any other width names no row, so the answer would
         * reach a client as a range exhausted rather than as a request refused.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a cursor key this surface did not produce is refused")
        void aCursorKeyThisSurfaceDidNotProduceIsRefused() {
            // WHAT: offers a two-component key joined by a separator, of the wrong overall width.
            // WHY : Assumptions: this is the exact shape a key had before the surface reduced it to
            //       the transaction identifier alone, so it is the malformed value most likely to be
            //       replayed by a stale client rather than an arbitrary string. Its width is 33 rather
            //       than 16, so it exercises the width check on a value that would otherwise select
            //       nothing and read as a range exhausted.
            assertThatThrownBy(() -> reportQueries.readNextReportLines(
                    REPORT_START_DATE,
                    REPORT_END_DATE,
                    "0000000000000001|0000000000000001",
                    WINDOW_SIZE,
                    TransactionReportRepositoryIT::sealForTest))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("characters wide this query surface produces");
        }

        /**
         * Confirms a backward step taken from no position at all is refused.
         *
         * <p>The captured exception is {@link java.lang.NullPointerException}, raised by the
         * surface's own requirement that a backward step name the window it is stepping from.</p>
         *
         * <p>WHY: the reference moves its stored FIRST key into the field its backward browse is
         * positioned from before starting it, at {@code app/cbl/COCRDLIC.cbl} L1268, so a backward
         * step requires a position and names nothing without one.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a backward step taken from no position at all is refused")
        void aBackwardStepTakenFromNoPositionAtAllIsRefused() {
            assertThatThrownBy(() -> reportQueries.readPreviousReportLines(
                    REPORT_START_DATE,
                    REPORT_END_DATE,
                    null,
                    WINDOW_SIZE,
                    TransactionReportRepositoryIT::sealForTest))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Reads the leading window of the report range.
         *
         * @return the first window, carrying {@link #WINDOW_SIZE} rows over the committed corpus,
         *     never {@code null}
         */
        private PageResponse<TransactionReportRepository.ReportLine> readFirstWindow() {
            return reportQueries.readNextReportLines(
                    REPORT_START_DATE,
                    REPORT_END_DATE,
                    null,
                    WINDOW_SIZE,
                    TransactionReportRepositoryIT::sealForTest);
        }

        /**
         * Walks the whole report range forward, one keyset window at a time.
         *
         * <p>Assumptions: each step is issued as its own call with no transaction spanning the walk,
         * which is how a client issues them -- one request per window. That is also what makes the
         * interleaved-insert case above a genuine test rather than a simulation.</p>
         *
         * @return every window in walk order, the last of which reports no further window, never
         *     {@code null}
         */
        private List<PageResponse<TransactionReportRepository.ReportLine>> walkForward() {
            List<PageResponse<TransactionReportRepository.ReportLine>> windows = new ArrayList<>();
            String opened = null;
            while (true) {
                PageResponse<TransactionReportRepository.ReportLine> window =
                        reportQueries.readNextReportLines(
                                REPORT_START_DATE,
                                REPORT_END_DATE,
                                opened,
                                WINDOW_SIZE,
                                TransactionReportRepositoryIT::sealForTest);
                if (window.items().isEmpty()) {
                    return windows;
                }
                windows.add(window);
                if (!window.hasNext()) {
                    return windows;
                }
                opened = unsealForTest(window.lastKey());
            }
        }
    }

    /**
     * Proves the two surfaces above are distinct and that neither can stand in for the other.
     *
     * <p>Assumptions: they are separated because merging them would break both consumers. A paged
     * surface cannot generate a report without issuing one request per window, so a range of any size
     * would cost as many round trips as it has windows; and a streaming cursor cannot answer a
     * step-forward request without re-scanning the range from its start, because a cursor carries no
     * position a later call can resume from. Keeping them apart is what lets the report keep the
     * single sequential pass the reference has at {@code app/cbl/CBTRN03C.cbl} L170 to L196 while the
     * list keeps the resumable position {@code app/cbl/COCRDLIC.cbl} L229 to L244 carries.</p>
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("the two surfaces are distinct")
    class SurfaceSeparation {

        /**
         * Confirms the two surfaces differ in return type, so neither substitutes for the other.
         *
         * <p>WHY: the difference is structural rather than a naming convention. One yields an open
         * traversal with no position; the other yields a closed envelope carrying two positions and a
         * further-window answer, which is the migrated form of the communication-area block at
         * {@code app/cbl/COCRDLIC.cbl} L229 to L244.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws NoSuchMethodException if either method is absent under the name and parameter types
         *     asserted here, which would mean a surface had been renamed
         */
        @Test
        @DisplayName("the two surfaces differ in return type")
        void theTwoSurfacesDifferInReturnType() throws NoSuchMethodException {
            Method streaming = TransactionReportRepository.class.getMethod(
                    "streamReportLines", LocalDate.class, LocalDate.class);
            Method paged = TransactionReportRepository.class.getMethod(
                    "readNextReportLines", LocalDate.class, LocalDate.class, String.class,
                    int.class, TransactionReportRepository.CursorSealer.class);

            assertThat(streaming.getReturnType()).isEqualTo(Stream.class);
            assertThat(paged.getReturnType()).isEqualTo(PageResponse.class);
            assertThat(streaming.getReturnType()).isNotEqualTo(paged.getReturnType());
        }

        /**
         * Confirms the report band the reference paginates by is not what the envelope reports.
         *
         * <p>WHY: two different notions of a page exist in this material and conflating them would
         * put a printer's concern into an interface a browser reads.
         * {@code app/cbl/CBTRN03C.cbl} L282 tests
         * {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} against
         * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at its L131 to L132, and that band exists so
         * a printed page carries a heading and a subtotal. It is not a client request size.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the report output band is not the interactive window size")
        void theReportOutputBandIsNotTheInteractiveWindowSize() {
            PageResponse<TransactionReportRepository.ReportLine> window =
                    reportQueries.readNextReportLines(
                            REPORT_START_DATE,
                            REPORT_END_DATE,
                            null,
                            WINDOW_SIZE,
                            TransactionReportRepositoryIT::sealForTest);

            // WHAT: requires the envelope's size to be the requested one and nothing else.
            // WHY : Assumptions: report pagination and keyset positioning are DIFFERENT concepts and
            //       this case asserts nothing whatever about the 20-line output band -- no heading, no
            //       page subtotal and no band boundary appears in any assertion here, because those
            //       belong to the writing side. The band is cited as a contract fact so that a reader
            //       does not later look for it in this envelope and conclude it went missing.
            assertThat(window.items()).hasSize(WINDOW_SIZE);
            assertThat(IN_RANGE_ROW_COUNT).isGreaterThan(WINDOW_SIZE);
        }
    }

    /**
     * Proves the record-selection condition became an inclusive SQL predicate, applied once.
     *
     * <p>Assumptions: {@code app/jcl/TRANREPT.jcl} L47 to L48 is
     * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)}, and
     * it selects RECORDS. It is the only {@code COND=} that job carries, so that job has no step gate
     * at all. By contrast {@code app/jcl/CREASTMT.JCL} carries three step gates, {@code COND=(0,NE)}
     * at its L56, L66 and L79, whose stated sense is the condition under which a step is BYPASSED and
     * which therefore inverts when expressed as a condition for running. The two forms share a
     * keyword and must never be modelled as one another in either direction: a record filter belongs
     * in a query and a step gate belongs in orchestration. {@code app/jcl/PRTCATBL.jcl} carries
     * neither form.</p>
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("the date range predicate")
    class DateRangePredicate {

        /**
         * Confirms a row on either bound is admitted and a row one day outside is not.
         *
         * <p>WHY: both bounds are inclusive in the reference and both are injected. The literals are
         * {@code PARM-START-DATE,C'2022-01-01'} at {@code app/jcl/TRANREPT.jcl} L43 and
         * {@code PARM-END-DATE,C'2022-07-06'} at its L44, and the comparisons that consume them are
         * greater-or-equal and less-or-equal at {@code app/cbl/CBTRN03C.cbl} L173 to L174. A boundary
         * is the one place an inclusive test and an exclusive one differ, so a row placed exactly on
         * each bound is the only evidence that distinguishes them.</p>
         *
         * @param transactionId the identifier of the corpus row under test
         * @param expectedAdmitted whether the inclusive report range is expected to admit that row
         */
        @ParameterizedTest(name = "{0} admitted={1}")
        @MethodSource(
                "com.carddemo.reporting.repository.TransactionReportRepositoryIT#dateBoundaryRows")
        @DisplayName("a row on either bound is admitted and a row one day outside is not")
        void aRowOnEitherBoundIsAdmittedAndARowOneDayOutsideIsNot(
                String transactionId, boolean expectedAdmitted) {

            List<String> admitted = identifiersOf(readWholeRange());

            assertThat(admitted.contains(transactionId)).isEqualTo(expectedAdmitted);
        }

        /**
         * Confirms the range is inclusive on a single-day window carrying two rows.
         *
         * <p>WHY: the opening bound is {@code PARM-START-DATE,C'2022-01-01'} at
         * {@code app/jcl/TRANREPT.jcl} L43 and the reference compares greater-or-equal against it at
         * {@code app/cbl/CBTRN03C.cbl} L173. A single-day range is the sharpest available test of
         * that, because it makes both bounds the same date and admits a row only if BOTH comparisons
         * are inclusive.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a single-day range admits both rows timed on that day")
        void aSingleDayRangeAdmitsBothRowsTimedOnThatDay() {
            List<String> admitted = identifiersOf(readRange(REPORT_START_DATE, REPORT_START_DATE));

            assertThat(admitted).containsExactlyInAnyOrder(
                    "0000000000000022", "0000000000000023");
        }

        /**
         * Confirms the admitted set is exactly the set the shared date utility computes.
         *
         * <p>WHY: the reference compares the first ten characters of a 26-character processing
         * timestamp, at {@code app/cbl/CBTRN03C.cbl} L173 to L174, and that ten-character prefix is
         * what the shared utility returns.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the admitted set is exactly what the shared date utility computes")
        void theAdmittedSetIsExactlyWhatTheSharedDateUtilityComputes() {
            List<String> expected = new ArrayList<>();
            for (Map<String, Object> row : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
                // WHAT: derives the row's business date through the shared timestamp utility.
                // WHY : Assumptions: the ten-character date part is obtained ONLY from
                //       com.carddemo.common.time.TimestampFormatter, whose datePrefix and toLocalDate
                //       accessors own the 26-character 'YYYY-MM-DD HH:MM:SS.mmmmmm' contract, and this
                //       package is that accessor's sole named production consumer -- register row R7
                //       of the main-tree charter records why it lives in the shared kernel rather than
                //       here. Writing a ten-character slice in this file would be a second
                //       implementation of one contract, and the two would drift without either
                //       failing.
                String raw = text(row, "TRAN-PROC-TS");
                LocalDate businessDate = TimestampFormatter.toLocalDate(raw);
                assertThat(TimestampFormatter.datePrefix(raw)).isEqualTo(businessDate.toString());
                if (!businessDate.isBefore(REPORT_START_DATE)
                        && !businessDate.isAfter(REPORT_END_DATE)) {
                    expected.add(text(row, "TRAN-ID"));
                }
            }

            assertThat(expected).hasSize(IN_RANGE_ROW_COUNT);
            assertThat(identifiersOf(readWholeRange()))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        /**
         * Confirms the query applies the range predicate once, not once per reference application.
         *
         * <p>WHY: the reference applies the same inclusive both-bounds test TWICE -- once as the sort
         * record filter at {@code app/jcl/TRANREPT.jcl} L47 to L48 and again in the program itself at
         * {@code app/cbl/CBTRN03C.cbl} L173 to L174.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws NoSuchMethodException if the declared query method is absent under the name and
         *     parameter types asserted here
         */
        @Test
        @DisplayName("the query applies the range predicate exactly once")
        void theQueryAppliesTheRangePredicateExactlyOnce() throws NoSuchMethodException {
            String jpql = TransactionReportRepository.class
                    .getMethod("streamReportLinesWithin", LocalDateTime.class, LocalDateTime.class)
                    .getAnnotation(Query.class)
                    .value();

            // WHAT: requires one lower-bound comparison and one upper-bound comparison, no more.
            // WHY : Trade-offs: one predicate is simpler than two and is provably equivalent, because
            //       both reference applications are the same inclusive both-bounds test over the same
            //       ten-character date part -- so a second application could only re-evaluate a
            //       decision already taken and could not change the admitted set. What is given up is
            //       the reference's belt-and-braces arrangement, in which the program re-checks what
            //       the sort step already filtered; that arrangement exists because the two ran as
            //       separate job steps over an intermediate data set, and the target has no
            //       intermediate data set between them.
            assertThat(occurrencesOf(jpql, "t.procTs >= :rangeStart")).isEqualTo(1);
            assertThat(occurrencesOf(jpql, "t.procTs < :rangeEnd")).isEqualTo(1);
            assertThat(occurrencesOf(jpql, "t.procTs")).isEqualTo(2);
        }

        /**
         * Confirms the range arrives as an argument and never from the host clock.
         *
         * <p>WHY: the reference injects both endpoints, as the control card at
         * {@code app/jcl/TRANREPT.jcl} L73 read into the ten, one, ten parameter record declared at
         * {@code app/cbl/CBTRN03C.cbl} L123 to L125, with the literals at that job's L43 and L44. An
         * injected range is what makes a rerun reproduce a run; a clock read would make the same
         * corpus yield a different report tomorrow.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the range arrives as an argument and never from the host clock")
        void theRangeArrivesAsAnArgumentAndNeverFromTheHostClock() {
            List<String> asOfTheReportRange = identifiersOf(readWholeRange());
            List<String> asOfTheOrphanDate =
                    identifiersOf(readRange(ORPHAN_BUSINESS_DATE, ORPHAN_BUSINESS_DATE));

            // WHAT: requires two different supplied ranges to yield two different admitted sets.
            // WHY : Assumptions: a surface reading a clock instead of its arguments would answer both
            //       calls identically, because nothing but the arguments differs between them. The
            //       second range is the date the three unresolvable rows carry, which is years away
            //       from the report range, so the two sets cannot coincide by accident.
            assertThat(asOfTheReportRange).hasSize(IN_RANGE_ROW_COUNT);
            assertThat(asOfTheOrphanDate).isNotEqualTo(asOfTheReportRange);
            assertThat(asOfTheOrphanDate).doesNotContainAnyElementsOf(asOfTheReportRange);
        }

        /**
         * Confirms the stored date form is already ordered, so a character compare is a date compare.
         *
         * <p>WHY: {@code app/jcl/TRANREPT.jcl} L42 declares the sort symbol
         * {@code TRAN-PROC-DT,305,10,CH} -- ten bytes compared as CHARACTER, not as a date -- and that
         * is only sound because the {@code YYYY-MM-DD} form places the most significant component
         * first. Recording this keeps a reader from assuming the reference must have parsed a date to
         * sort on one.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the stored date form is already ordered, so a character compare orders dates")
        void theStoredDateFormIsAlreadyOrderedSoACharacterCompareOrdersDates() {
            List<Map<String, Object>> rows = decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR);
            for (Map<String, Object> row : rows) {
                String raw = text(row, "TRAN-PROC-TS");
                String prefix = TimestampFormatter.datePrefix(raw);
                LocalDate parsed = TimestampFormatter.toLocalDate(raw);

                assertThat(prefix).hasSize("YYYY-MM-DD".length());
                assertThat(prefix.compareTo(REPORT_START_DATE.toString()) >= 0)
                        .isEqualTo(!parsed.isBefore(REPORT_START_DATE));
                assertThat(prefix.compareTo(REPORT_END_DATE.toString()) <= 0)
                        .isEqualTo(!parsed.isAfter(REPORT_END_DATE));
            }
        }
    }

    /**
     * Proves the ordering the report rests on, and the byte positions that ordering is derived from.
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("the report ordering and the positions behind it")
    class ReportOrdering {

        /**
         * Confirms the two independent readings of the record geometry agree.
         *
         * <p>WHY: two unrelated members declare these positions and they use different conventions.
         * Summing the fourteen declared widths of {@code app/cpy/CVTRA05Y.cpy} L5 to L18 gives exactly
         * 350 bytes and places the card number at zero-based 262 and the processing timestamp at
         * zero-based 304; {@code app/jcl/TRANREPT.jcl} L41 and L42 declare the same two fields at
         * one-based 263 and 305. Asserting the conversion rather than assuming it is what keeps an
         * off-by-one from moving a key onto the neighbouring field, which would fail silently by
         * producing values that look plausible.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the one-based sort positions and the zero-based offsets agree")
        void theOneBasedSortPositionsAndTheZeroBasedOffsetsAgree() {
            CopybookLayout.RecordSpec spec = CopybookLayout.layout(TRANSACTION_DESCRIPTOR);

            assertThat(spec.reclen()).isEqualTo(TRANSACTION_RECORD_LENGTH);
            assertThat(offsetOf(spec, "TRAN-CARD-NUM")).isEqualTo(CARD_NUMBER_ZERO_BASED_OFFSET);
            assertThat(offsetOf(spec, "TRAN-PROC-TS")).isEqualTo(PROC_TIMESTAMP_ZERO_BASED_OFFSET);

            // WHAT: states the conversion rule between the two conventions as an assertion.
            // WHY : Assumptions: a zero-based offset is the one-based position MINUS ONE, and the rule
            //       runs in that direction only. Applying it the other way round shifts every field
            //       by one byte, and the corroboration app/jcl/TRANIDX.jcl L27 supplies as
            //       KEYS(26 304) would then appear to disagree with a copybook it in fact confirms.
            assertThat(CARD_NUMBER_ONE_BASED_POSITION - 1)
                    .isEqualTo(CARD_NUMBER_ZERO_BASED_OFFSET);
            assertThat(PROC_TIMESTAMP_ONE_BASED_POSITION - 1)
                    .isEqualTo(PROC_TIMESTAMP_ZERO_BASED_OFFSET);
        }

        /**
         * Confirms a card's rows are contiguous even when one account holds two cards.
         *
         * <p>WHY: the reference breaks on a CARD.
         * {@code WS-CURR-CARD-NUM PIC X(16)} at {@code app/cbl/CBTRN03C.cbl} L137 is a sixteen-byte
         * card number and the break at its L181 compares it against {@code TRAN-CARD-NUM}, so an
         * implementation grouping by account identifier would merge two runs the reference keeps
         * apart. The corpus supplies exactly that case: two of the four cross-reference rows carry the
         * same account.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("a card's rows stay contiguous even when one account holds two cards")
        void aCardsRowsStayContiguousEvenWhenOneAccountHoldsTwoCards() {
            List<TransactionReportRepository.ReportLine> lines = readWholeRange();

            List<String> cardsOnSharedAccount = new ArrayList<>();
            for (TransactionReportRepository.ReportLine line : lines) {
                if (line.getAccountId() == SHARED_ACCOUNT_ID
                        && !cardsOnSharedAccount.contains(line.getCardFingerprint())) {
                    cardsOnSharedAccount.add(line.getCardFingerprint());
                }
            }

            // WHAT: requires the shared account to appear as two separate card runs, not one.
            // WHY : Assumptions: this is the observable difference between breaking on a card and
            //       breaking on an account. Were the grouping keyed on the account identifier, these
            //       two cards would form a single run and the cross-reference would be resolved once
            //       where the reference resolves it twice -- app/cbl/CBTRN03C.cbl L186 to L187 fires
            //       per card, inside the break at its L181.
            assertThat(cardsOnSharedAccount).hasSize(SHARED_ACCOUNT_CARD_COUNT);
            assertThat(distinctRunsOf(lines)).isEqualTo(distinctFingerprintsOf(lines));
        }

        /**
         * Confirms the ordering is genuinely applied and not inherited from the corpus's own order.
         *
         * <p>WHY: the order under test is the one {@code app/jcl/TRANREPT.jcl} L46 establishes with
         * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} before that job's L59 runs the program at all, so it
         * is a property of the input the program consumes rather than something the program computes.
         * {@code fixtures/tranfile.txt} is committed in a deliberately unordered physical sequence --
         * identifiers descend within each card's block and the blocks themselves are not in key order
         * -- so a query that omitted its ordering clause could not pass this case by accident.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("the ordering is applied rather than inherited from the corpus")
        void theOrderingIsAppliedRatherThanInheritedFromTheCorpus() {
            List<String> physical = new ArrayList<>();
            for (Map<String, Object> row : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
                physical.add(text(row, "TRAN-ID"));
            }

            List<String> queried = identifiersOf(readWholeRange());

            assertThat(physical).hasSizeGreaterThan(queried.size());
            assertThat(queried).isNotEqualTo(
                    physical.stream().filter(queried::contains).toList());
            assertAscendingWithinEachCard(readWholeRange());
        }
    }

    /**
     * Proves the projection carries the eight values the reference detail line assembles.
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("the eight printed detail values")
    class DetailColumns {

        /**
         * Confirms the projection declares eight printed members and two ordering members.
         *
         * <p>WHY: {@code 1120-WRITE-DETAIL} at {@code app/cbl/CBTRN03C.cbl} L361 to L374 carries
         * exactly eight moves into the detail record, at its L363 through L370. The two additional
         * members are the card rendering and the per-card digest, and neither reaches the 133-column
         * layout {@code app/cpy/CVTRA07Y.cpy} declares.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the projection declares eight printed members and two ordering members")
        void theProjectionDeclaresEightPrintedMembersAndTwoOrderingMembers() {
            List<String> declared = new ArrayList<>();
            for (Method accessor
                    : TransactionReportRepository.ReportLine.class.getDeclaredMethods()) {
                declared.add(accessor.getName());
            }

            List<String> printed = List.of(
                    "getTransactionId", "getAccountId", "getTypeCd", "getTypeDescription",
                    "getCategoryCd", "getCategoryDescription", "getSource", "getAmount");
            List<String> ordering = List.of("getCardNum", "getCardFingerprint");

            assertThat(printed).hasSize(PRINTED_DETAIL_COLUMN_COUNT);
            assertThat(declared).hasSize(PROJECTION_MEMBER_COUNT)
                    .containsAll(printed)
                    .containsAll(ordering);
        }

        /**
         * Confirms every printed value on one row matches the corpus it was decoded from.
         *
         * <p>WHY: {@code app/cbl/CBTRN03C.cbl} L361 to L374 assembles the detail line from eight
         * moves, and each of the eight is moved from a different place -- one of them, the account
         * identifier at that program's L364, from a different FILE entirely. Establishing all eight
         * against the corpus is what shows the four-way join assembled the line the reference
         * assembles.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("every printed value on one row matches the corpus it came from")
        void everyPrintedValueOnOneRowMatchesTheCorpusItCameFrom() {
            Map<String, Object> source = corpusRow("0000000000000004");
            TransactionReportRepository.ReportLine line = reportLine("0000000000000004");

            assertThat(line.getTransactionId()).isEqualTo(text(source, "TRAN-ID"));
            assertThat(line.getTypeCd()).isEqualTo(text(source, "TRAN-TYPE-CD"));
            assertThat(line.getCategoryCd())
                    .isEqualTo(categoryCode(source, "TRAN-CAT-CD"));

            // WHAT: compares the two character values after removing the pad to their declared width.
            // WHY : Assumptions: the reference stores these in blank-padded character fields and the
            //       target columns are declared CHAR, so the engine pads a shorter value back out to
            //       the declared width on the way in. Comparing the padded forms would make every
            //       assertion depend on the pad rather than on the value, and the reference itself
            //       moves these into report fields that are wider still.
            assertThat(line.getSource().strip()).isEqualTo(text(source, "TRAN-SOURCE"));
            assertThat(line.getTypeDescription().strip())
                    .isEqualTo(descriptionOfType(text(source, "TRAN-TYPE-CD")));
            assertThat(line.getCategoryDescription().strip()).isEqualTo(
                    descriptionOfCategory(
                            text(source, "TRAN-TYPE-CD"), categoryCode(source, "TRAN-CAT-CD")));
            assertThat(line.getAmount().amount())
                    .isEqualByComparingTo(money(source, "TRAN-AMT"));
            assertThat(line.getAccountId()).isNotNull();
        }

        /**
         * Confirms the printed account identifier comes from the cross-reference, not a master.
         *
         * <p>WHY: {@code app/cbl/CBTRN03C.cbl} L364 is {@code MOVE XREF-ACCT-ID TO
         * TRAN-REPORT-ACCOUNT-ID}, and the reference resolves that value in
         * {@code 1500-A-LOOKUP-XREF} at its L484 to L492. The report path reads no account master at
         * all -- {@code app/jcl/TRANREPT.jcl} declares no account data definition -- so the
         * cross-reference is the only possible source and is why it is a join participant.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("the printed account identifier comes from the cross-reference")
        void thePrintedAccountIdentifierComesFromTheCrossReference() {
            for (TransactionReportRepository.ReportLine line : readWholeRange()) {
                assertThat(line.getAccountId())
                        .isEqualTo(crossReferencedAccountOf(line.getCardFingerprint()));
            }

            // WHAT: reads the declared width of the cross-reference's own account field.
            // WHY : Assumptions: app/cpy/CVACT03Y.cpy L7 declares XREF-ACCT-ID as PIC 9(11) and
            //       app/cbl/CBTRN03C.cbl L364 moves it into an X(11) report field, so the reference
            //       prints its leading zeros verbatim rather than suppressing them. The target carries
            //       the value as an integral identifier and the leading zeros become a RENDERING
            //       concern belonging to the writing side; recording that here keeps a reader from
            //       expecting a zero-padded string from this query.
            assertThat(CopybookLayout.layout(XREF_DESCRIPTOR)).isNotNull();
            assertThat(widthOf(CopybookLayout.layout(XREF_DESCRIPTOR), "XREF-ACCT-ID"))
                    .isEqualTo("00000000000".length());
        }

        /**
         * Confirms the three dimensions are reached by query rather than by a mapped association.
         *
         * <p>WHY: the reference reaches each of the three with its own keyed read against its own
         * file, at {@code app/cbl/CBTRN03C.cbl} L484, L494 and L504, and the three files are declared
         * separately at its L33, L39 and L45. A mapped association would make the dimension a property
         * of the transaction row and would fetch it whether a caller wanted it or not.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws NoSuchMethodException if the declared query method is absent under the name and
         *     parameter types asserted here
         */
        @Test
        @DisplayName("the three dimensions are reached by query, not by a mapped association")
        void theThreeDimensionsAreReachedByQueryNotByAMappedAssociation()
                throws NoSuchMethodException {

            String jpql = TransactionReportRepository.class
                    .getMethod("streamReportLinesWithin", LocalDateTime.class, LocalDateTime.class)
                    .getAnnotation(Query.class)
                    .value();

            // WHAT: requires each dimension to be joined by an explicit predicate in the query text.
            // WHY : Alternatives Considered: mapping the three as associations on the driving entity
            //       was rejected, because the driving relation and the three dimensions live in
            //       separate projections whose only agreement is a column value -- a join predicate
            //       expresses that and an association would additionally claim an ownership the
            //       relations do not have. It would also make the dimension load a property of the
            //       entity rather than of the query, so the report and the reconciliation surface
            //       could no longer join the same rows two different ways, which is exactly what
            //       distinguishing a resolved row from an unresolved one requires.
            assertThat(jpql).contains("join CardXrefView x on x.cardFingerprint = t.cardFingerprint");
            assertThat(jpql).contains("join TransactionTypeView ty on ty.typeCd = t.typeCd");
            assertThat(jpql).contains("join TransactionCategoryView c");
            assertThat(jpql).doesNotContain("t.cardXref").doesNotContain("t.transactionType");
        }

        /**
         * Confirms the amount stays an exact decimal at scale two, unstripped.
         *
         * <p>WHY: {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} L10 is eleven bytes
         * of zoned decimal, and the three accumulators the reference declares at
         * {@code app/cbl/CBTRN03C.cbl} L134 to L136 carry the same picture, so the accumulation
         * precision matches the value precision throughout.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("the amount stays an exact decimal at scale two, unstripped")
        void theAmountStaysAnExactDecimalAtScaleTwoUnstripped() {
            for (TransactionReportRepository.ReportLine line : readWholeRange()) {
                BigDecimal amount = line.getAmount().amount();

                assertThat(amount.scale()).isEqualTo(AMOUNT_SCALE);
                assertThat(amount.precision()).isLessThanOrEqualTo(AMOUNT_PRECISION);
            }

            // WHAT: keeps this surface's precision separate from the account projection's.
            // WHY : Assumptions: nine integer digits plus two decimals is eleven and belongs to the
            //       transaction record; the account balance is PIC S9(10)V99, twelve bytes, and
            //       belongs to a different record read by a different job. Unifying the two would
            //       widen this column for no reason and would let a value the source record cannot
            //       express pass into it, at which point the column stops being evidence of what the
            //       source record held.
            assertThat(AMOUNT_PRECISION).isNotEqualTo(ACCOUNT_PROJECTION_PRECISION);
        }

        /**
         * Confirms the four amount cases the corpus supplies all survive the round trip exactly.
         *
         * <p>WHY: a zoned-decimal field carries its sign as an overpunch on its final byte, so a
         * positive value, a negative value, a zero and a value at the full declared magnitude
         * exercise four different byte patterns. The category-balance fixture corroborates the regime
         * from a second, unrelated member: {@code app/jcl/PRTCATBL.jcl} L50 declares
         * {@code TRAN-CAT-BAL,18,11,ZD}, stating both that the field is zoned and that eleven bytes
         * is the width of that picture.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @Transactional(readOnly = true)
        @DisplayName("the four amount cases the corpus supplies survive the round trip exactly")
        void theFourAmountCasesTheCorpusSuppliesSurviveTheRoundTripExactly() {
            // WHAT: decodes each expected amount through the shared codec rather than parsing it.
            // WHY : Alternatives Considered: writing the expected amounts out as literals, or slicing
            //       the eleven bytes and parsing them here. Both were rejected because the registered
            //       descriptor is the ONE place the offsets and the sign convention are declared, so a
            //       second reading in this file would drift from it without either failing -- and the
            //       sign overpunch is exactly the part a hand-written parser gets wrong, since the
            //       final byte of a negative value is a letter rather than a digit.
            for (Map<String, Object> row : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
                String identifier = text(row, "TRAN-ID");
                LocalDate businessDate =
                        TimestampFormatter.toLocalDate(text(row, "TRAN-PROC-TS"));
                if (businessDate.isBefore(REPORT_START_DATE)
                        || businessDate.isAfter(REPORT_END_DATE)) {
                    continue;
                }
                assertThat(reportLine(identifier).getAmount().amount())
                        .isEqualByComparingTo(money(row, "TRAN-AMT"));
            }

            List<BigDecimal> amounts = new ArrayList<>();
            for (TransactionReportRepository.ReportLine line : readWholeRange()) {
                amounts.add(line.getAmount().amount());
            }
            assertThat(amounts).anyMatch(value -> value.signum() > 0);
            assertThat(amounts).anyMatch(value -> value.signum() < 0);
            assertThat(amounts).anyMatch(value -> value.signum() == 0);
            assertThat(amounts).anyMatch(
                    value -> value.abs().compareTo(new BigDecimal("999999999.99")) == 0);
        }
    }

    /**
     * Proves an unresolvable dimension is named rather than quietly discarded.
     *
     * <p>Assumptions: the reference treats all three lookups as fatal.
     * {@code 1500-A-LOOKUP-XREF} at {@code app/cbl/CBTRN03C.cbl} L484 to L492 reads with an
     * {@code INVALID KEY} arm at its L486 that displays the verbatim diagnostic
     * {@code 'INVALID CARD NUMBER : '} with the offending key at its L487 before abending, and the
     * type and category lookups do the same at its L497 and L507. The corpus reaches that condition
     * from data alone: {@code fixtures/cardxref.txt} deliberately omits one card that
     * {@code fixtures/tranfile.txt} carries, and two further rows carry a type code and a category key
     * that the two reference fixtures do not declare.</p>
     *
     * <p>Assumptions: what this class asserts is that the offending identifiers are NAMED. Raising on
     * them belongs to the service layer above this surface, which is the migrated home of the abend,
     * and it is deliberately not asserted here -- a data-access type that raised would leave a caller
     * unable to report WHICH row was unresolvable, which is the one thing the reference's diagnostic
     * does.</p>
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("an unresolvable dimension is surfaced, never dropped")
    class UnresolvedDimensions {

        /**
         * Confirms all three unresolvable rows are named when their own date range is asked about.
         *
         * <p>WHY: the three failure modes are independent -- an absent cross-reference row, an absent
         * type and an absent category -- and the reference has a separate abend path for each, at
         * {@code app/cbl/CBTRN03C.cbl} L487, L497 and L507. A check that named only one of them would
         * pass while two remained invisible.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("all three unresolvable rows are named")
        void allThreeUnresolvableRowsAreNamed() {
            List<String> offending = reportQueries.findTransactionsWithUnresolvedDimensions(
                    startOf(ORPHAN_BUSINESS_DATE),
                    startOfDayAfter(ORPHAN_BUSINESS_DATE),
                    Limit.of(IN_RANGE_ROW_COUNT));

            assertThat(offending).containsExactlyInAnyOrder(
                    ORPHAN_CARD_TRANSACTION_ID,
                    ORPHAN_TYPE_TRANSACTION_ID,
                    ORPHAN_CATEGORY_TRANSACTION_ID);
        }

        /**
         * Confirms an unresolvable row is absent from the report while remaining countable.
         *
         * <p>WHY: this is the whole reason the reconciliation surface exists. The reference does not
         * carry on past an unresolvable lookup -- {@code app/cbl/CBTRN03C.cbl} L484 to L492 takes the
         * {@code INVALID KEY} arm at its L486, displays a diagnostic at its L487 and abends, with the
         * type and category lookups doing the same at its L497 and L507 -- so the condition is loud
         * there and cannot be missed. The report joins its three dimensions with inner joins, so the
         * same row simply does not appear, which is invisible from the report alone. Counting the
         * driving relation on its own is what makes the discrepancy observable, and it is unaffected by
         * whether any dimension resolved.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an unresolvable row is absent from the report yet remains countable")
        void anUnresolvableRowIsAbsentFromTheReportYetRemainsCountable() {
            List<String> reported =
                    identifiersOf(readRange(ORPHAN_BUSINESS_DATE, ORPHAN_BUSINESS_DATE));
            long driving = reportQueries.countDrivingRows(
                    startOf(ORPHAN_BUSINESS_DATE), startOfDayAfter(ORPHAN_BUSINESS_DATE));

            assertThat(reported).isEmpty();
            assertThat(driving).isEqualTo(3L);

            // WHAT: names the offending rows from the driving relation rather than from the report.
            // WHY : Assumptions: a caller that could only see the report would know a row was missing
            //       but not which one, and so could not produce the reference's own diagnostic, which
            //       displays the offending key alongside its message at app/cbl/CBTRN03C.cbl L487. The
            //       driving read is what supplies the key, which is why it exists as a separate
            //       surface rather than being folded into the report query.
            List<ReportTransactionView> drivingRows = reportQueries.findDrivingRows(
                    startOf(ORPHAN_BUSINESS_DATE),
                    startOfDayAfter(ORPHAN_BUSINESS_DATE),
                    Limit.of(IN_RANGE_ROW_COUNT));
            List<String> named = new ArrayList<>();
            for (ReportTransactionView row : drivingRows) {
                named.add(row.transactionId());
            }
            assertThat(named).containsExactlyInAnyOrder(
                    ORPHAN_CARD_TRANSACTION_ID,
                    ORPHAN_TYPE_TRANSACTION_ID,
                    ORPHAN_CATEGORY_TRANSACTION_ID);
        }

        /**
         * Confirms the report range itself carries no unresolvable row.
         *
         * <p>WHY: the three unresolvable rows are date-shifted years away from the report range on
         * purpose, so that every in-range assertion in this class rests on a range in which every
         * driving row resolves to exactly one of each dimension. Were that not so, a count assertion
         * elsewhere would be measuring an unresolved join rather than the corpus. It also matches the
         * only range over which the reference produces a report at all: {@code app/cbl/CBTRN03C.cbl}
         * L484 to L492 abends on an unresolvable cross-reference, so a range carrying one yields a
         * diagnostic rather than output, and the range declared at {@code app/jcl/TRANREPT.jcl} L43 to
         * L44 is one the reference can run to completion.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the report range itself carries no unresolvable row")
        void theReportRangeItselfCarriesNoUnresolvableRow() {
            List<String> offending = reportQueries.findTransactionsWithUnresolvedDimensions(
                    startOf(REPORT_START_DATE),
                    startOfDayAfter(REPORT_END_DATE),
                    Limit.of(IN_RANGE_ROW_COUNT));

            assertThat(offending).isEmpty();
            assertThat(reportQueries.countDrivingRows(
                    startOf(REPORT_START_DATE), startOfDayAfter(REPORT_END_DATE)))
                    .isEqualTo(IN_RANGE_ROW_COUNT);
        }
    }

    /**
     * Proves this module materialises nothing and owns no relation it reads.
     *
     * <p>This class takes no parameter, returns no value and raises nothing outside the test
     * engine.</p>
     */
    @Nested
    @DisplayName("nothing is materialised and nothing is owned here")
    class ReadOnlyPosture {

        /**
         * Confirms every relation this surface reads is a plain view and not a stored copy.
         *
         * <p>WHY: a physical copy in card order is a real alternative rather than a straw man, because
         * the reference builds exactly that. {@code app/jcl/CREASTMT.JCL} L44 runs a sort over the
         * transaction cluster named at its L45, writes the sequential data set declared at its L48 to
         * L51, and then at its L56 runs the data-set utility whose control card at its L61 is
         * {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)} to load a keyed copy. The target rejects that:
         * the card-first ordering is an index plus a read-only projection, and no byte is copied.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws SQLException if the catalog cannot be read, which would leave the posture unproven
         *     rather than merely unasserted
         */
        @Test
        @DisplayName("every relation this surface reads is a plain view, not a stored copy")
        void everyRelationThisSurfaceReadsIsAPlainViewNotAStoredCopy() throws SQLException {
            // WHAT: reads the catalog's own relation kind for each of the four participants.
            // WHY : Alternatives Considered: materialising the card-ordered projection, which the
            //       reference does. Rejected because a second physical copy is a second truth for
            //       figures whose only purpose is to restate the first exactly, and keeping it in step
            //       would add a synchronisation the reference's nightly wholesale rebuild only hides.
            //       A view leaves the transaction context the single owner of the rows. The catalog is
            //       asked rather than the mapping, because a mapping cannot show what the engine
            //       holds: a materialised view maps identically and would pass any Java-only check.
            for (String relation : List.of("v_report_transactions", "v_card_xref",
                    "v_transaction_types", "v_transaction_categories")) {
                assertThat(relationKindOf(relation))
                        .as("relation kind of reporting.%s", relation)
                        .isEqualTo("v");
            }
        }

        /**
         * Confirms this module ships no migration directory and no migration tooling.
         *
         * <p>WHY: this context owns no relational object, so a versioned history here would order
         * against four other services' histories it does not share. The relations this class reads are
         * declared by {@code data-migration/sql/V1__reporting_views.sql} and the base tables beneath
         * them by the four owning contexts' own migrations, none of which is this module's to version.
         * Register row <b>R11</b> of the main-tree charter rules that a relation absent at run time is
         * a defect to report against the file that owns it, and this login could not create one in any
         * case. The reference has no counterpart to cite because a data definition there is a job step
         * rather than an artifact a program carries; {@code app/jcl/TRANREPT.jcl} L59 runs the program
         * with the data sets already defined.</p>
         *
         * <p>This method takes no parameter and returns no value. The load attempt below is expected
         * to raise {@link ClassNotFoundException}, which is captured and asserted on rather than
         * propagated, so this method declares no exception of its own.</p>
         */
        @Test
        @DisplayName("this module ships no migration directory and no migration tooling")
        void thisModuleShipsNoMigrationDirectoryAndNoMigrationTooling() {
            assertThat(Files.isDirectory(repositoryRoot().resolve(MIGRATION_DIRECTORY))).isFalse();

            // WHAT: tries to load the migration engine's own entry type from this module's classpath.
            // WHY : Assumptions: absence is established by attempting the load rather than by
            //       searching this module's text for the tool's name, because the name legitimately
            //       APPEARS in this module -- in the module descriptor's own note recording that the
            //       coordinate is not declared, and in a constant on this very class. A textual search
            //       therefore cannot distinguish a declaration from a note about the absence of one,
            //       whereas the classpath can.
            assertThatThrownBy(() -> Class.forName(MIGRATION_TOOL_TYPE))
                    .isInstanceOf(ClassNotFoundException.class);
        }

        /**
         * Confirms the index the report reads through is neither created nor removed from here.
         *
         * <p>WHY: what retires from the reference is a build STEP and not an access path. The
         * separate rebuild has no counterpart because the engine maintains an index transactionally as
         * rows change, while the secondary path it produced survives intact -- and that path is owned
         * by the transaction context, whose own migration declares it. {@code app/jcl/TRANIDX.jcl} L27
         * is cited only as position evidence, declaring {@code KEYS(26 304)} whose second operand is
         * the same processing-timestamp offset this class asserts above.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         *
         * @throws SQLException if the catalog cannot be read
         */
        @Test
        @DisplayName("the index the report reads through is owned by another context")
        void theIndexTheReportReadsThroughIsOwnedByAnotherContext() throws SQLException {
            assertThat(indexExists("idx_transactions_proc_ts")).isTrue();

            // WHAT: locates the declaration of that index in the owning service's migration.
            // WHY : Assumptions: the index is proven to belong to the transaction context by finding
            //       its declaration in that context's own migration file, not by this class having
            //       created it -- nothing here issues a definition of any kind. Naming the owning file
            //       is what makes a future absence reportable against the right package instead of
            //       being worked around from here.
            Path owner = repositoryRoot().resolve(
                    "services/transaction-service/src/main/resources/db/migration/V1__ledger.sql");
            assertThat(Files.isRegularFile(owner)).isTrue();
            assertThat(readAll(owner)).contains("idx_transactions_proc_ts");
        }

        /**
         * Confirms the row projection is read-only at the mapping layer.
         *
         * <p>WHY: reads reach the writer through read-only cross-schema views rather than a replica.
         * A replica adds cost and replica-lag semantics for no parity benefit, and the behavioural
         * cost is concrete rather than general: the reference statement job reads the live transaction
         * cluster directly at {@code app/jcl/CREASTMT.JCL} L45, so it cannot omit a transaction the
         * online path has already accepted, whereas a report generated from a lagging replica can.</p>
         *
         * <p>Alternatives Considered: serving this context from a read replica, which is the standard
         * answer to keeping report scans off a writer. Rejected on two specific counts rather than on
         * preference. The first is monetary: a replica is a second always-on instance charged for
         * whether or not a report runs, against a nightly scan the writer already absorbs. The second
         * is that replica lag is observable in the output -- a row committed by the online path and
         * not yet replayed is simply absent from the report, so two runs of the same range over the
         * same committed data can differ, which is the property register row <b>R12</b> of the
         * main-tree charter refuses. Read-only cross-schema views on the writer cost nothing to run
         * and cannot lag.</p>
         *
         * <p>Assumptions: the full two-level write-refusal proof -- the mapping refusing to generate a
         * statement and the login role refusing one that reached the engine -- lives in
         * {@code services/reporting-service/src/test/java/com/carddemo/reporting/repository/StatementCardXrefRepositoryIT.java}
         * and is cited rather than duplicated here.</p>
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the row projection is read-only at the mapping layer")
        void theRowProjectionIsReadOnlyAtTheMappingLayer() {
            assertThat(ReportTransactionView.class
                    .isAnnotationPresent(Immutable.class)).isTrue();

            for (Field member : ReportTransactionView.class.getDeclaredFields()) {
                assertThat(member.isAnnotationPresent(Version.class))
                        .as("member %s carries no optimistic-locking version", member.getName())
                        .isFalse();
            }
        }
    }

    /**
     * One expected window of a forward walk: its position, its size and its further-window answer.
     *
     * <p>Assumptions: this is declared as a record rather than assembled inline so the expectation
     * table below reads as a table. The three components are exactly the three answers the envelope
     * publishes for a window, and the reference publishes the same three from
     * {@code app/cbl/COCRDLIC.cbl} L237, its row counter at L145 and its next-page indicator at
     * L242.</p>
     *
     * @param ordinal the one-based position of the window in a forward walk
     * @param rows the number of rows that window is expected to carry
     * @param furtherWindow whether a further window is expected to be reported from it
     */
    private record WindowExpectation(int ordinal, int rows, boolean furtherWindow) {
    }

    /**
     * Supplies the expected shape of every window of a forward walk over the committed corpus.
     *
     * <p>Assumptions: 26 in-range rows in windows of five is five full windows and a final window of
     * one, and only the final window reports no further window. The table is derived from the two
     * declared constants rather than written out, so changing either cannot leave the expectation
     * silently stale.</p>
     *
     * @return one argument triple per window, in walk order, never {@code null}
     */
    static Stream<Arguments> windowExpectations() {
        List<WindowExpectation> expectations = new ArrayList<>();
        for (int ordinal = 1; ordinal <= WINDOW_COUNT; ordinal++) {
            boolean lastWindow = ordinal == WINDOW_COUNT;
            int rows = lastWindow ? IN_RANGE_ROW_COUNT % WINDOW_SIZE : WINDOW_SIZE;
            expectations.add(new WindowExpectation(ordinal, rows, !lastWindow));
        }
        return expectations.stream().map(expectation -> Arguments.of(
                expectation.ordinal(), expectation.rows(), expectation.furtherWindow()));
    }

    /**
     * Supplies the five corpus rows that sit on or just outside the inclusive report bounds.
     *
     * <p>Assumptions: the identifiers and the expectations are stated rather than computed, because
     * the point of this table is to name the rows the corpus places on the boundaries. Two are timed
     * on the opening bound of {@code app/jcl/TRANREPT.jcl} L43 and one on the closing bound of its
     * L44; the remaining two are one day outside each end.</p>
     *
     * @return one identifier and admission expectation per boundary row, never {@code null}
     */
    static Stream<Arguments> dateBoundaryRows() {
        return Stream.of(
                Arguments.of("0000000000000023", true),
                Arguments.of("0000000000000022", true),
                Arguments.of("0000000000000004", true),
                Arguments.of("0000000000000006", false),
                Arguments.of("0000000000000005", false));
    }

    /**
     * Seals one cursor key into a token of the shape the shared envelope accepts.
     *
     * <p>Assumptions: this is a shape-conforming stand-in and deliberately performs no cryptography.
     * The surface under test takes its sealer as a parameter precisely because sealing needs two
     * things a data-access type must not hold -- the keyed authentication material and the identity of
     * the authenticated caller a token is bound to -- so the sealing lives with the caller and this
     * class is the caller here. Alternatives Considered: reaching for the real sealing utility.
     * Rejected because it would make every case in this class depend on key material that belongs to a
     * deployed configuration, and because the authenticated envelope's own behaviour is asserted by
     * that utility's unit tests rather than by an integration test of a query. What this stand-in must
     * do, and does, is satisfy the shape the envelope validates, so the algebra of positioning is
     * exercised through the same code path a deployed caller uses.</p>
     *
     * @param cursorKey the cursor key to seal, being the transaction identifier this surface produces
     * @param leading {@code true} when the key names the first row of the window, {@code false} when
     *     it names the last
     * @return a token carrying that key in the version-tagged three-segment shape the envelope
     *     accepts, never {@code null}
     */
    static String sealForTest(String cursorKey, boolean leading) {
        String boundary = leading ? LEADING_BOUNDARY_TAG : TRAILING_BOUNDARY_TAG;
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursorKey.getBytes(StandardCharsets.US_ASCII));
        return TOKEN_VERSION + '.' + boundary + '.' + payload;
    }

    /**
     * Recovers the cursor key a stand-in token carries.
     *
     * @param token a token produced by {@link #sealForTest(String, boolean)}
     * @return the cursor key it carries, never {@code null}
     * @throws IllegalArgumentException if the token does not carry the three segments this class
     *     seals, which would mean the envelope had accepted something this class did not produce
     */
    static String unsealForTest(String token) {
        String[] segments = token.split("\\.");
        if (segments.length != 3) {
            throw new IllegalArgumentException(
                    "token " + token + " does not carry the three segments this class seals");
        }
        return new String(Base64.getUrlDecoder().decode(segments[2]), StandardCharsets.US_ASCII);
    }

    /**
     * Reads the boundary tag a stand-in token carries in its middle segment.
     *
     * <p>Assumptions: the surface under test tells its sealer WHICH boundary it is sealing, because
     * the published contract binds a token to the direction it was issued for so that replaying a
     * trailing position backward is refused rather than answered with the wrong window. The stand-in
     * records that flag in the segment a real token uses for its binding, which is what lets a case
     * here assert the direction a position was issued for.</p>
     *
     * @param token a token produced by {@link #sealForTest(String, boolean)}
     * @return the boundary tag it carries, never {@code null}
     * @throws IllegalArgumentException if the token does not carry the three segments this class seals
     */
    static String boundaryTagOf(String token) {
        String[] segments = token.split("\\.");
        if (segments.length != 3) {
            throw new IllegalArgumentException(
                    "token " + token + " does not carry the three segments this class seals");
        }
        return segments[1];
    }

    /**
     * Reads every resolved line the inclusive report range admits.
     *
     * @return the report's own rows in its own order, never {@code null}
     */
    private List<TransactionReportRepository.ReportLine> readWholeRange() {
        return readRange(REPORT_START_DATE, REPORT_END_DATE);
    }

    /**
     * Reads every resolved line one inclusive business-date range admits.
     *
     * <p>Assumptions: the bound handed to the query is generous rather than exact, so that a case
     * asserting a row count measures the range and not the bound.</p>
     *
     * @param startDate the first business date admitted, inclusive
     * @param endDate the last business date admitted, inclusive
     * @return the resolved lines in the report's order, never {@code null}
     */
    private List<TransactionReportRepository.ReportLine> readRange(
            LocalDate startDate, LocalDate endDate) {
        return reportQueries.findReportLines(
                startOf(startDate), startOfDayAfter(endDate), Limit.of(RANGE_READ_BOUND));
    }

    /**
     * Reads the one resolved line carrying the given transaction identifier.
     *
     * @param transactionId the identifier of the wanted line
     * @return that line, never {@code null}
     * @throws IllegalStateException if the report range does not admit exactly one such line, which
     *     would mean the corpus or the range had changed under this assertion
     */
    private TransactionReportRepository.ReportLine reportLine(String transactionId) {
        List<TransactionReportRepository.ReportLine> matches = readWholeRange().stream()
                .filter(line -> line.getTransactionId().equals(transactionId))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("the report range admits " + matches.size()
                    + " line(s) for " + transactionId + " rather than exactly one");
        }
        return matches.get(0);
    }

    /**
     * Reduces one inclusive business date to the first instant of it.
     *
     * <p>Assumptions: the bound is normalised through the shared timestamp utility, the same one the
     * surface under test uses, so a bound and a stored value are compared at the one resolution the
     * 26-character contract carries.</p>
     *
     * @param date the business date to reduce
     * @return the first instant of that date at the contract's resolution, never {@code null}
     */
    private static LocalDateTime startOf(LocalDate date) {
        return TimestampFormatter.normalize(date.atStartOfDay());
    }

    /**
     * Reduces one inclusive business date to the first instant excluded after it.
     *
     * <p>Assumptions: the upper bound is the start of the FOLLOWING day and the comparison against it
     * is strict, which is what expresses an inclusive date range without applying a function to the
     * column. It selects the same rows the reference's inclusive comparison at
     * {@code app/cbl/CBTRN03C.cbl} L174 selects, because every instant of the last day is before the
     * start of the next and no instant of the next day is.</p>
     *
     * @param date the last inclusive business date of a range
     * @return the first instant of the following day, never {@code null}
     */
    private static LocalDateTime startOfDayAfter(LocalDate date) {
        return TimestampFormatter.normalize(date.plusDays(1).atStartOfDay());
    }

    /**
     * Extracts the transaction identifiers of a list of resolved lines, in order.
     *
     * @param lines the lines to read
     * @return their identifiers in the same order, never {@code null}
     */
    private static List<String> identifiersOf(List<TransactionReportRepository.ReportLine> lines) {
        List<String> identifiers = new ArrayList<>();
        for (TransactionReportRepository.ReportLine line : lines) {
            identifiers.add(line.getTransactionId());
        }
        return identifiers;
    }

    /**
     * Returns the last element of a non-empty list.
     *
     * @param <T> the element type
     * @param values the list to read
     * @return its last element, never {@code null} unless the list holds nulls
     */
    private static <T> T lastOf(List<T> values) {
        return values.get(values.size() - 1);
    }

    /**
     * Counts the unbroken runs of one card in a list of resolved lines.
     *
     * @param lines the lines to inspect, in the order the query yielded them
     * @return the number of times the card changes, plus one for a non-empty list
     */
    private static int distinctRunsOf(List<TransactionReportRepository.ReportLine> lines) {
        int runs = 0;
        String current = null;
        for (TransactionReportRepository.ReportLine line : lines) {
            if (!line.getCardFingerprint().equals(current)) {
                runs++;
                current = line.getCardFingerprint();
            }
        }
        return runs;
    }

    /**
     * Counts the distinct cards a list of resolved lines mentions.
     *
     * @param lines the lines to inspect
     * @return the number of distinct per-card grouping values present
     */
    private static int distinctFingerprintsOf(List<TransactionReportRepository.ReportLine> lines) {
        List<String> seen = new ArrayList<>();
        for (TransactionReportRepository.ReportLine line : lines) {
            if (!seen.contains(line.getCardFingerprint())) {
                seen.add(line.getCardFingerprint());
            }
        }
        return seen.size();
    }

    /**
     * Requires the transaction identifiers to ascend inside each card's run.
     *
     * <p>Assumptions: the check is scoped to a run rather than applied across the whole list, because
     * the identifier is the SECONDARY ordering key and is not ordered between cards. Asserting it
     * across the list would assert a total order the relation does not carry.</p>
     *
     * @param lines the lines to inspect, in the order the query yielded them
     */
    private static void assertAscendingWithinEachCard(
            List<TransactionReportRepository.ReportLine> lines) {
        String card = null;
        String previous = null;
        for (TransactionReportRepository.ReportLine line : lines) {
            if (!line.getCardFingerprint().equals(card)) {
                card = line.getCardFingerprint();
                previous = null;
            }
            if (previous != null) {
                assertThat(line.getTransactionId()).isGreaterThan(previous);
            }
            previous = line.getTransactionId();
        }
    }

    /**
     * Counts the non-overlapping occurrences of one substring inside another string.
     *
     * @param text the string to search
     * @param fragment the substring to count
     * @return the number of non-overlapping occurrences, zero when there are none
     */
    private static int occurrencesOf(String text, String fragment) {
        int found = 0;
        int from = text.indexOf(fragment);
        while (from >= 0) {
            found++;
            from = text.indexOf(fragment, from + fragment.length());
        }
        return found;
    }

    /**
     * Reads the zero-based offset a record descriptor declares for one field.
     *
     * @param spec the record descriptor to read
     * @param field the copybook field name
     * @return that field's zero-based byte offset
     * @throws IllegalStateException if the descriptor declares no field of that name, which would
     *     mean the descriptor and this assertion had stopped describing one record
     */
    private static int offsetOf(CopybookLayout.RecordSpec spec, String field) {
        for (CopybookLayout.FieldSpec declared : spec.fields()) {
            if (declared.name().equals(field)) {
                return declared.start();
            }
        }
        throw new IllegalStateException(
                "descriptor " + spec.name() + " declares no field named " + field);
    }

    /**
     * Reads the declared byte width a record descriptor gives one field.
     *
     * @param spec the record descriptor to read
     * @param field the copybook field name
     * @return that field's declared byte width
     * @throws IllegalStateException if the descriptor declares no field of that name
     */
    private static int widthOf(CopybookLayout.RecordSpec spec, String field) {
        for (CopybookLayout.FieldSpec declared : spec.fields()) {
            if (declared.name().equals(field)) {
                return declared.length();
            }
        }
        throw new IllegalStateException(
                "descriptor " + spec.name() + " declares no field named " + field);
    }

    /**
     * Reads the one committed corpus row carrying the given transaction identifier.
     *
     * @param transactionId the identifier of the wanted row
     * @return that row's decoded field map, never {@code null}
     * @throws IllegalStateException if the corpus holds no such row
     */
    private static Map<String, Object> corpusRow(String transactionId) {
        for (Map<String, Object> row : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
            if (text(row, "TRAN-ID").equals(transactionId)) {
                return row;
            }
        }
        throw new IllegalStateException(
                "the committed corpus holds no row for " + transactionId);
    }

    /**
     * Reads the description the type fixture gives one transaction type code.
     *
     * @param typeCode the two-character type code
     * @return its description with the pad removed, never {@code null}
     * @throws IllegalStateException if the type fixture declares no such code
     */
    private static String descriptionOfType(String typeCode) {
        for (Map<String, Object> row : decodeAll(TYPE_FIXTURE, TYPE_DESCRIPTOR)) {
            if (text(row, "TRAN-TYPE").equals(typeCode)) {
                return text(row, "TRAN-TYPE-DESC");
            }
        }
        throw new IllegalStateException("the type fixture declares no code " + typeCode);
    }

    /**
     * Reads the description the category fixture gives one type and category pair.
     *
     * @param typeCode the two-character type code
     * @param categoryCode the four-character category code
     * @return its description with the pad removed, never {@code null}
     * @throws IllegalStateException if the category fixture declares no such pair
     */
    private static String descriptionOfCategory(String typeCode, String categoryCode) {
        for (Map<String, Object> row : decodeAll(CATEGORY_FIXTURE, CATEGORY_DESCRIPTOR)) {
            if (text(row, "TRAN-TYPE-CD").equals(typeCode)
                    && categoryCode(row, "TRAN-CAT-CD").equals(categoryCode)) {
                return text(row, "TRAN-CAT-TYPE-DESC");
            }
        }
        throw new IllegalStateException(
                "the category fixture declares no pair " + typeCode + categoryCode);
    }

    /**
     * Reads the account the cross-reference relation resolves one card grouping value to.
     *
     * @param cardFingerprint the per-card grouping value the projection publishes
     * @return the account identifier the cross-reference carries for that card
     * @throws IllegalStateException if the relation resolves it to no account or to more than one
     */
    private static long crossReferencedAccountOf(String cardFingerprint) {
        String sql = "select account_id from reporting.v_card_xref where card_fingerprint = ?";
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, cardFingerprint);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException(
                            "the cross-reference resolves no account for the given card");
                }
                long account = rows.getLong(1);
                if (rows.next()) {
                    throw new IllegalStateException(
                            "the cross-reference resolves more than one account for one card");
                }
                return account;
            }
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot read the cross-reference projection", cause);
        }
    }

    /**
     * Builds a transaction identifier that sorts below every identifier the corpus declares.
     *
     * <p>Assumptions: every committed identifier is sixteen digits with a value of one or more, so an
     * identifier of sixteen zeros sorts below all of them under the bytewise collation the transaction
     * relation declares on its key. The relation is asked for nothing else, and the guard below makes
     * the assumption fail loudly rather than silently producing a row that lands somewhere else.</p>
     *
     * @param identifier the identifier the produced one must sort below
     * @return a sixteen-character identifier ordering strictly before {@code identifier}
     * @throws IllegalStateException if it does not sort below the given identifier, which would mean
     *     the corpus had gained a row whose key is lower than this construction assumes
     */
    private static String interleavedIdentifierBelow(String identifier) {
        String candidate = "0".repeat(TransactionReportRepository.CURSOR_KEY_LENGTH);
        if (candidate.compareTo(identifier) >= 0) {
            throw new IllegalStateException("the constructed identifier " + candidate
                    + " does not sort below " + identifier);
        }
        return candidate;
    }

    /**
     * Recovers the whole card number standing behind a narrowed rendering.
     *
     * <p>Assumptions: the projection publishes a card number narrowed to twelve constant characters
     * and its last four digits, so the rendering alone cannot be written to the base relation. The
     * whole number is taken from the committed cross-reference fixture by matching those four digits,
     * which is sound here because the four rows of that fixture carry four distinct endings; the guard
     * below refuses the ambiguous case rather than choosing one.</p>
     *
     * @param narrowedRendering the sixteen-character narrowed rendering a report line carries
     * @return the whole card number the corpus declares behind it, never {@code null}
     * @throws IllegalStateException if the fixture matches no card or more than one
     */
    private static String wholeCardNumberBehind(String narrowedRendering) {
        String tail = narrowedRendering.substring(narrowedRendering.length() - 4);
        List<String> matches = new ArrayList<>();
        for (Map<String, Object> row : decodeAll(REPORT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR)) {
            String whole = text(row, "XREF-CARD-NUM");
            if (whole.endsWith(tail)) {
                matches.add(whole);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("the cross-reference fixture matches "
                    + matches.size() + " cards on the published ending rather than exactly one");
        }
        return matches.get(0);
    }

    /**
     * Writes one extra transaction onto an existing card, inside the report range.
     *
     * <p>Assumptions: the type and category are ones both reference fixtures declare, so the row
     * resolves through all three dimensions and reaches the joined report rather than being filtered
     * out by an unresolved join and appearing to prove something it did not.</p>
     *
     * @param transactionId the identifier to write, which must not already exist
     * @param cardNumber the whole card number the row belongs to
     * @throws SQLException if the row cannot be written
     */
    private static void insertInterleavedTransaction(String transactionId, String cardNumber)
            throws SQLException {
        String sql = "insert into ledger.transactions(transaction_id, type_cd, category_cd,"
                + " source, description, amount, merchant_id, merchant_name, merchant_city,"
                + " merchant_zip, card_num, orig_ts, proc_ts) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement insert = connection.prepareStatement(sql)) {
            LocalDateTime stamp = REPORT_START_DATE.atStartOfDay().plusHours(1);
            insert.setString(1, transactionId);
            insert.setString(2, "01");
            insert.setString(3, "0001");
            insert.setString(4, "BATCH");
            insert.setString(5, "Interleaved row written by one case and removed by it");
            insert.setBigDecimal(6, new BigDecimal("1.00"));
            insert.setLong(7, 800000103L);
            insert.setString(8, "Ironwood Bindery");
            insert.setString(9, "Lower Schroeder");
            insert.setString(10, "66104-2275");
            insert.setString(11, cardNumber);
            insert.setObject(12, stamp);
            insert.setObject(13, stamp);
            insert.executeUpdate();
        }
    }

    /**
     * Removes the extra transaction one case wrote, restoring the committed corpus.
     *
     * @param transactionId the identifier written by {@link #insertInterleavedTransaction}
     * @throws SQLException if the row cannot be removed, which would leave the corpus altered
     */
    private static void deleteInterleavedTransaction(String transactionId) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement remove = connection.prepareStatement(
                        "delete from ledger.transactions where transaction_id = ?")) {
            remove.setString(1, transactionId);
            remove.executeUpdate();
        }
    }

    /**
     * Reads the engine's own relation kind for one relation in the reporting schema.
     *
     * @param relation the unqualified relation name
     * @return the single-character relation kind the catalog records, never {@code null}
     * @throws SQLException if the catalog cannot be read
     * @throws IllegalStateException if the reporting schema holds no relation of that name, which is
     *     a defect in {@code data-migration/sql/V1__reporting_views.sql} rather than something this
     *     class may create
     */
    private static String relationKindOf(String relation) throws SQLException {
        String sql = "select c.relkind from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = 'reporting' and c.relname = ?";
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, relation);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("reporting." + relation + " is absent; that"
                            + " relation belongs to data-migration/sql/V1__reporting_views.sql and"
                            + " its absence is a defect to report against that file");
                }
                return rows.getString(1);
            }
        }
    }

    /**
     * Reports whether an index of the given name exists anywhere in the database.
     *
     * @param indexName the unqualified index name
     * @return {@code true} when the catalog records an index of that name
     * @throws SQLException if the catalog cannot be read
     */
    private static boolean indexExists(String indexName) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement query = connection.prepareStatement(
                        "select count(*) from pg_class where relkind = 'i' and relname = ?")) {
            query.setString(1, indexName);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() && rows.getLong(1) > 0L;
            }
        }
    }

    /**
     * Reads a working-tree file whole, as text.
     *
     * @param file the file to read
     * @return its entire content, never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String readAll(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read " + file, cause);
        }
    }

    /**
     * Applies the shipped definition files to the container, in the order an operator applies them.
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a definition file is absent from the working tree, naming the
     *     file that owns the relations so the defect is reported against it rather than worked around
     */
    private static void applyDeployedSchema() {
        Path root = repositoryRoot();
        int sequence = 0;
        for (String script : DEPLOYED_SCHEMA_SCRIPTS) {
            Path source = root.resolve(script);
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("the shipped definition file " + source
                        + " is absent, so this class cannot assert anything about the relations the"
                        + " reporting context reads; that file owns them and this class must not"
                        + " create them");
            }
            // WHAT: gives each copied file a distinct numeric name inside the container.
            // WHY : Assumptions: the bootstrap appears twice in the list on purpose, so two entries
            //       resolve to one working-tree path. A name derived from the file would collide on
            //       that pair and the second application would silently re-run whichever copy landed
            //       last, which is the one thing the repetition exists to avoid.
            String target = "/tmp/deployed-" + sequence + ".sql";
            sequence++;
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(source), target);
            List<String> arguments = new ArrayList<>(List.of("-v", "ON_ERROR_STOP=1"));
            for (String acknowledgement : BOOTSTRAP_ACKNOWLEDGEMENTS) {
                arguments.add("-c");
                arguments.add("set " + acknowledgement + " = 'on'");
            }
            arguments.add("-f");
            arguments.add(target);
            runEngineClient(arguments.toArray(String[]::new));
        }
    }

    /**
     * Runs the engine's own client inside the container and fails loudly on a non-zero result.
     *
     * <p>Assumptions: the client is invoked without a credential, because it runs inside the container
     * as the operating-system user the engine trusts locally. That is what lets this class apply the
     * shipped files and load the corpus with no credential written anywhere in it.</p>
     *
     * @param arguments the client arguments following the connection flags
     * @throws IllegalStateException if the client reports a failure, or if the calling thread is
     *     interrupted while the client is running
     * @throws UncheckedIOException if the client cannot be run at all
     */
    private static void runEngineClient(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-q"));
        command.addAll(List.of(arguments));
        try {
            org.testcontainers.containers.Container.ExecResult result =
                    POSTGRES.execInContainer(command.toArray(String[]::new));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("the engine client refused " + command + ": "
                        + result.getStderr() + result.getStdout());
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot run " + command, cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, cause);
        }
    }

    /**
     * Loads the five fixtures this class reads into the relations their owning services declare.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against its registered descriptor, so the load carries the record layout the migration is
     * defined against rather than a second transcription of it. Alternatives Considered: parsing the
     * rows here by offset. Rejected because the descriptor registry is the ONE place the offsets and
     * the sign convention are declared, and a second parser in this class would drift from it in
     * silence -- and it is the fixture's geometry that every assertion below depends on.</p>
     *
     * <p>Assumptions: the loads are inserts and nothing else. This class defines no relation, conveys
     * no privilege and drops nothing; the boundary this directory draws is on DEFINITIONS, and
     * inserting rows sits inside it because a corpus has to be loaded before anything can be read.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded
     */
    private static void loadFixtureCorpus() {
        try (Connection connection = POSTGRES.createConnection("")) {
            // WHAT: loads the types before the categories.
            // WHY : Assumptions: the category relation carries a restricting foreign key onto the type
            //       relation, which preserves the reference extension's own delete-restrict semantic,
            //       so a category inserted before its type is refused. The order is therefore a
            //       property of the schema rather than a preference.
            loadTransactionTypes(connection);
            loadTransactionCategories(connection);
            loadCardCrossReferences(connection);
            loadTransactions(connection);
            loadCategoryBalances(connection);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the report-path fixture corpus", cause);
        }
    }

    /**
     * Loads {@code trantype.txt} into the transaction type reference.
     *
     * @param connection an open connection able to write the reference schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadTransactionTypes(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into reference.transaction_types(type_cd, description) values (?,?)")) {
            for (Map<String, Object> row : decodeAll(TYPE_FIXTURE, TYPE_DESCRIPTOR)) {
                insert.setString(1, text(row, "TRAN-TYPE"));
                insert.setString(2, text(row, "TRAN-TYPE-DESC"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code trancatg.txt} into the transaction category reference.
     *
     * @param connection an open connection able to write the reference schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadTransactionCategories(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into reference.transaction_categories(type_cd, cat_cd, description)"
                        + " values (?,?,?)")) {
            for (Map<String, Object> row : decodeAll(CATEGORY_FIXTURE, CATEGORY_DESCRIPTOR)) {
                insert.setString(1, text(row, "TRAN-TYPE-CD"));
                insert.setString(2, categoryCode(row, "TRAN-CAT-CD"));
                insert.setString(3, text(row, "TRAN-CAT-TYPE-DESC"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code cardxref.txt} into the card cross-reference.
     *
     * <p>Assumptions: this is the REPORT-path fixture, the data definition at
     * {@code app/jcl/TRANREPT.jcl} L67, and it is the only one of the two cross-reference fixtures
     * loaded. Loading the statement-path one as well would insert one card twice and the relation's
     * key is the card number.</p>
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCardCrossReferences(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into account.card_xref(card_num, customer_id, account_id)"
                        + " values (?,?,?)")) {
            for (Map<String, Object> row
                    : decodeAll(REPORT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR)) {
                insert.setString(1, text(row, "XREF-CARD-NUM"));
                insert.setLong(2, integral(row, "XREF-CUST-ID"));
                insert.setLong(3, integral(row, "XREF-ACCT-ID"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code tranfile.txt} into the transaction relation.
     *
     * @param connection an open connection able to write the ledger schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadTransactions(Connection connection) throws SQLException {
        String sql = "insert into ledger.transactions(transaction_id, type_cd, category_cd,"
                + " source, description, amount, merchant_id, merchant_name, merchant_city,"
                + " merchant_zip, card_num, orig_ts, proc_ts) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
                insert.setString(1, text(row, "TRAN-ID"));
                insert.setString(2, text(row, "TRAN-TYPE-CD"));
                insert.setString(3, categoryCode(row, "TRAN-CAT-CD"));
                insert.setString(4, text(row, "TRAN-SOURCE"));
                insert.setString(5, text(row, "TRAN-DESC"));
                insert.setBigDecimal(6, money(row, "TRAN-AMT"));
                insert.setLong(7, integral(row, "TRAN-MERCHANT-ID"));
                insert.setString(8, text(row, "TRAN-MERCHANT-NAME"));
                insert.setString(9, text(row, "TRAN-MERCHANT-CITY"));
                insert.setString(10, text(row, "TRAN-MERCHANT-ZIP"));
                insert.setString(11, text(row, "TRAN-CARD-NUM"));
                // WHAT: parses both timestamps through the shared timestamp utility.
                // WHY : Assumptions: the two fields are 26-character character data in the record and
                //       microsecond timestamps in the column, and the shared utility owns that
                //       contract -- parsing them here with a locally written pattern would put a
                //       second reading of one format in this file. The ORIGINATING stamp is business
                //       data copied from the source transaction while the PROCESSING stamp is written
                //       by a run, which is why only the second is declared for normalisation on the
                //       descriptor; both are nonetheless stored, because the relation declares both.
                insert.setObject(12, TimestampFormatter.parse(text(row, "TRAN-ORIG-TS")));
                insert.setObject(13, TimestampFormatter.parse(text(row, "TRAN-PROC-TS")));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code tcatbal.txt} into the transaction category balance relation.
     *
     * <p>Assumptions: this relation is not a participant in either report join, and it is loaded so
     * that the money-regime corroboration rests on a populated relation rather than on a reading of
     * one file. {@code app/jcl/PRTCATBL.jcl} L47 to L50 declares the same four offsets this record's
     * descriptor declares, with {@code ZD} on the eleven-byte balance at its L50.</p>
     *
     * @param connection an open connection able to write the ledger schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCategoryBalances(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into ledger.transaction_category_balances(account_id, type_cd,"
                        + " category_cd, balance) values (?,?,?,?)")) {
            for (Map<String, Object> row
                    : decodeAll(CATEGORY_BALANCE_FIXTURE, CATEGORY_BALANCE_DESCRIPTOR)) {
                insert.setLong(1, integral(row, "TRANCAT-ACCT-ID"));
                insert.setString(2, text(row, "TRANCAT-TYPE-CD"));
                insert.setString(3, categoryCode(row, "TRANCAT-CD"));
                insert.setBigDecimal(4, money(row, "TRAN-CAT-BAL"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Decodes every row of one fixture through the shared codec against its registered descriptor.
     *
     * <p>Assumptions: each fixture is line-oriented with one record per line, and the newline is a
     * FILE convention that is not part of any record -- the declared length excludes it, so reading a
     * fixture as one continuous byte stream would mis-align every record after the first. The bytes
     * are taken in a single-byte encoding so that one character is one byte and every declared offset
     * holds.</p>
     *
     * @param fileName the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @param descriptorName the registered copybook descriptor name
     * @return one decoded field map per row, in file order, never {@code null}
     * @throws UncheckedIOException if the fixture cannot be read
     * @throws IllegalStateException if the fixture is absent from the classpath
     */
    private static List<Map<String, Object>> decodeAll(String fileName, String descriptorName) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        String resource = FIXTURE_DIRECTORY + '/' + fileName;
        try (InputStream stream = TransactionReportRepositoryIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the fixture " + resource + " is absent");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.ISO_8859_1))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(line -> FixedWidthCodec.decodeRecord(
                                line.getBytes(StandardCharsets.ISO_8859_1), spec))
                        .toList();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read the fixture " + resource, cause);
        }
    }

    /**
     * Reads one decoded character field, trimmed of the pad that carries it to its declared width.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value with trailing blanks removed, never {@code null}
     */
    private static String text(Map<String, Object> row, String field) {
        return String.valueOf(row.get(field)).stripTrailing();
    }

    /**
     * Reads one decoded unsigned integral field.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value
     */
    private static long integral(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    /**
     * Reads one decoded monetary field as an exact decimal.
     *
     * <p>Assumptions: the value stays an exact decimal from the codec to the column and never passes
     * through an approximate numeric type. A binary floating-point hop would round a value the record
     * holds exactly, and the rounding would be invisible until a total disagreed.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's exact value, never {@code null}
     */
    private static BigDecimal money(Map<String, Object> row, String field) {
        return (BigDecimal) row.get(field);
    }

    /**
     * Reads one decoded category code back into the zero-padded character form the column carries.
     *
     * <p>Assumptions: the descriptor declares the category code as an unsigned DISPLAY integer, so it
     * decodes to a number and loses the leading zeros the record holds, while the target column is a
     * four-character code in which those zeros are part of the value. Re-padding here is what keeps
     * the code that is written equal to the code the record declares -- {@code app/cbl/CBTRN03C.cbl}
     * L367 moves it into a report field verbatim.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the four-character zero-padded code, never {@code null}
     */
    private static String categoryCode(Map<String, Object> row, String field) {
        return String.format("%04d", integral(row, field));
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a count of
     * parent steps, so this class runs identically from the reactor root and from the module
     * directory. A relative path would resolve differently between those two invocations.</p>
     *
     * @return the repository root, never {@code null}
     * @throws IllegalStateException if no ancestor carries the reactor descriptor
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("services/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory carries services/pom.xml");
    }

    /**
     * The narrowest context that can create this surface's repository proxy.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason every sibling integration
     * test in this directory records and which
     * {@code services/reporting-service/src/test/resources/application-test.yml} is the authority for:
     * scanning the root would instantiate the orchestration client and the filter chain, so a context
     * started to run a query would additionally need a state-machine identifier, an object-store
     * bucket and a token issuer, and a failure to supply any of them would read as a query defect.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class ReportQueryTestApplication {
    }
}
