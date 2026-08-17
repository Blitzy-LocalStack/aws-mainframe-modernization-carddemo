package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.reporting.domain.CardXrefView;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
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
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the cross-reference cursor drives a statement run over every published card, unwritably.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code StatementCardXrefRepository.streamAllInCardNumberOrder} is the driving traversal of a
 * whole statement run: one row reached is one statement produced, and exhaustion of the cursor is
 * what ends the run. Of the three read paragraphs the mainline loop of
 * {@code app/cbl/CBSTM03A.CBL} reaches, only the cross-reference one carries an end-of-file arm --
 * {@code WHEN '10' MOVE 'Y' TO END-OF-FILE} at its L356 and L357 inside the evaluation at L353 to
 * L362 -- and the loop at L316 to L329 continues until that arm fires. The customer read at L379 to
 * L386 and the account read at L403 to L410 carry no such arm at all: anything but a clean status
 * reaches the abend paragraph at L921, whose body calls the language-environment abend routine at
 * L923. Four properties of that traversal are invisible to every cheaper gate in this module and are
 * asserted here against a real engine carrying the shipped definitions: it yields every published
 * card once in a total order, it holds one row at a time rather than a run, it cannot be opened
 * outside a transaction that outlives the call, and neither the mapping nor the login role can write
 * anything at all.</p>
 *
 * <h2>What this class asserts that no sibling does</h2>
 *
 * <p>Assumptions: the three classes already in this directory divide the module's engine-backed
 * properties between them, and this one takes the fourth rather than restating any of theirs.
 * {@code ReportingQueryBootstrapIT} establishes no relation and proves the declared queries parse;
 * {@code StatementHeadingChunkIT} seeds stand-in tables and proves the heading walk's keyset
 * predicate reproduces its own ordering; {@code ReportingDeployedRelationIT} applies the shipped
 * definitions and proves the projections mask, that a card joins to its customer and its account,
 * and that the login role cannot write a relation it can read. None of the three touches
 * {@code fixtures/xreffile.txt} and none calls the cursor method. This class is the only consumer of
 * both, and its write-refusal target is deliberately different: a base table in another context's
 * schema rather than a projection in this one.</p>
 *
 * <h2>Why the shipped definitions are applied rather than reproduced</h2>
 *
 * <p>Assumptions: every relation read below is created by executing the shipped files themselves,
 * read from the working tree and handed to the engine unchanged. Nothing here authors a schema
 * object of any kind -- no relation, no privilege, no index -- and no statement below defines one.
 * The authority for the schemas, the roles and the privileges is
 * {@code data-migration/sql/V0__schemas_and_roles.sql}; the authority for the projections is
 * {@code data-migration/sql/V1__reporting_views.sql}; the base tables beneath them belong to four
 * other services' migrations. Register entry <b>R11</b> in the main-tree charter records that a
 * relation missing at run time is a defect to report against whichever of those files owns it, never
 * something for a class here to remedy, and that this login role could not create it in any case.</p>
 *
 * <p>Alternatives Considered: a hand-written stand-in for the projection, which is what the sibling
 * ordering class uses. Rejected here because two of the four properties above live entirely outside
 * Java: the total order depends on the narrowing expression the projection applies to a card number,
 * and the write refusal depends on a privilege. A stand-in would assert both of the stand-in and
 * nothing about what ships.</p>
 *
 * <h2>The two-level write refusal</h2>
 *
 * <p>Trade-offs: writing is refused twice over, at the mapping and at the database, and the
 * duplication of intent is accepted because either guard alone answers only half the question. The
 * mapping guard cannot show that the database would refuse a statement that reached it; the
 * privilege guard cannot show that the application fails before one is generated. The cost is a
 * slower class -- a container, eleven script executions and a fixture load stand between it and its
 * first assertion -- and that cost buys the only two facts that together make the posture provable.</p>
 *
 * <h2>Parameters, return values, exceptions or errors</h2>
 *
 * <p>This is a test class with no constructor a caller invokes, no value it yields and no exception
 * it raises outside the test engine, so the type accepts no parameter, returns nothing and raises
 * nothing. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids a docstring that omits parameters, return values or exceptions.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = StatementCardXrefRepositoryIT.CardXrefCursorTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: this module carries cloud starters as compile dependencies, so a context
    //       that enables auto-configuration at all builds their beans, and the region provider
    //       resolves eagerly and raises when it finds none. Nothing below contacts a cloud service,
    //       so the value identifies nothing this class reaches. It is supplied here rather than in
    //       application-test.yml because that document's own register of deliberate omissions rules
    //       that a cloud-service value belongs to the test that needs it.
    "spring.cloud.aws.region.static=us-east-1",
    // WHY : Assumptions: this was MEASURED rather than assumed. With them left enabled this class
    //       still passes, but each importer walks the whole credential-provider chain looking for a
    //       store to read -- system properties, environment, web identity, profile file, container
    //       and finally the instance metadata service -- which cost ten additional seconds on a host
    //       where that last probe fails fast and would cost a timeout on a host where it hangs
    //       instead, so switching them off is about determinism rather than tidiness. Assumptions:
    //       the second key's NAME mentions a secret store while its VALUE is what stops one from
    //       being read, so neither key is a credential; this class supplies no access key and no
    //       signing material at all, because it makes no cloud call for one to sign.
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class StatementCardXrefRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and both properties asserted here
     * that depend on the engine -- collation-dependent ordering and privilege enforcement -- are ones
     * an engine version can genuinely change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The two session settings the shipped bootstrap requires before it will run.
     *
     * <p>Assumptions: both are opt-outs the bootstrap reads through {@code current_setting} with its
     * missing-value flag set, and it raises rather than proceeding when neither is on: one
     * acknowledges an unencrypted local connection, the other accepts roles arriving without a
     * credential because their source is a secret store no test has. They are session settings, and
     * they are applied as such below -- the engine's own client accepts several statement options
     * intermixed with a file option and runs them in ONE session, so a setting made before the file
     * is still in force while the file executes. Alternatives Considered: recording them on the
     * database instead, which is what the sibling deployed-relation class does. Rejected here because
     * that form is a schema-alteration statement, and this directory's charter draws its boundary on
     * definitions: executing an authoritative definition unchanged is permitted, authoring one is
     * not, and a database-level setting written here would be a definition this class authored.</p>
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
     * missing at creation surfaces on the first READ rather than at creation -- which is exactly the
     * failure the second pass removes. Applying the files in the order an operator applies them is
     * therefore part of what this class establishes.</p>
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

    /**
     * The classpath directory holding the fixture corpus this class loads.
     */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The statement-path cross-reference fixture, bound by exact name.
     *
     * <p>Assumptions: this file and {@code cardxref.txt} are DIFFERENT fixtures for the same 50-byte
     * record under two different data-definition names, and conflating them would misplace an
     * assertion. This one is named for the {@code XREFFILE} definition at
     * {@code app/jcl/CREASTMT.JCL} L84, which the statement generator reads; the other is named for
     * the {@code CARDXREF} definition at {@code app/jcl/TRANREPT.jcl} L67 and L68, which the report
     * writer reads. Both decode against the one registered descriptor {@code XREF}, because the
     * record is one record.</p>
     */
    private static final String STATEMENT_PATH_XREF_FIXTURE = "xreffile.txt";

    /**
     * The report-path cross-reference fixture, bound by exact name for the agreement assertion.
     */
    private static final String REPORT_PATH_XREF_FIXTURE = "cardxref.txt";

    /**
     * The registered copybook descriptor both cross-reference fixtures decode against.
     */
    private static final String XREF_DESCRIPTOR = "XREF";

    /**
     * The login role the deployed service authenticates as, holding read privileges only.
     */
    private static final String REPORTING_ROLE = "carddemo_reporting";

    /**
     * The standard condition code the engine reports when a privilege is insufficient.
     *
     * <p>Assumptions: the condition code is asserted rather than the diagnostic text, because the text
     * is subject to the engine's message locale while the code is part of the standard. Asserting the
     * code is also what makes the refusal specific: a write that failed for a uniqueness violation, a
     * missing relation or a read-only transaction would report a different code and would otherwise
     * have satisfied an assertion that only required a failure.</p>
     */
    private static final String INSUFFICIENT_PRIVILEGE_STATE = "42501";

    /**
     * The number of distinct cards the statement-path fixture publishes.
     *
     * <p>Assumptions: this figure is asserted against the fixture as well as against the cursor, so a
     * fixture edit cannot silently weaken the walk assertion into one over fewer rows.</p>
     */
    private static final int PUBLISHED_CARD_COUNT = 88;

    /**
     * The declared outer arity of the reference's distinct-card table, fifty-one.
     *
     * <p>Assumptions: this is one of THREE numbers the reference declares or exhibits and they must
     * be kept apart. {@code app/cbl/CBSTM03A.CBL} L226 declares
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} with the companion counter table
     * {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES} at L232, which is this one; L228 declares
     * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES}, an inner arity of ten; and the same-card overrun
     * threshold measured against the reference is a third and much larger number. There is no one
     * combined ceiling, and reading the distinct-card figure as a transaction count would mislabel
     * two separate limits as one. The baseline holds a run in declared-width tables, the Java holds
     * one row at a time behind a retrieval batch, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} as divergence D-2.</p>
     */
    private static final int REFERENCE_DECLARED_CARD_TABLE_ARITY = 51;

    /**
     * The declared length of the cross-reference record, fifty bytes.
     */
    private static final int XREF_RECORD_LENGTH = 50;

    /**
     * The copybook field name of the whole card number.
     */
    private static final String CARD_NUMBER_FIELD = "XREF-CARD-NUM";

    /**
     * The copybook field name of the customer identifier.
     */
    private static final String CUSTOMER_ID_FIELD = "XREF-CUST-ID";

    /**
     * The copybook field name of the account identifier.
     */
    private static final String ACCOUNT_ID_FIELD = "XREF-ACCT-ID";

    /**
     * The copybook name of the trailing pad that carries the record to its declared length.
     */
    private static final String PADDING_FIELD = "FILLER";

    /**
     * The declared digit count of the customer identifier, nine.
     */
    private static final int CUSTOMER_KEY_DIGITS = 9;

    /**
     * The declared digit count of the account identifier, eleven.
     */
    private static final int ACCOUNT_KEY_DIGITS = 11;

    /**
     * The one-based first byte the account identifier occupies in the record, twenty-six.
     */
    private static final int ACCOUNT_ID_FIRST_BYTE = 26;

    /**
     * The one-based last byte the account identifier occupies in the record, thirty-six.
     */
    private static final int ACCOUNT_ID_LAST_BYTE = 36;

    /**
     * The number of trailing digits the projection publishes of a card number, four.
     */
    private static final int PUBLISHED_TAIL_LENGTH = 4;

    /**
     * The narrowing prefix the projection substitutes for the leading digits of a card number.
     *
     * <p>Assumptions: twelve characters, so the narrowed rendering occupies the declared width of
     * {@value CardXrefView#CARD_NUMBER_WIDTH} exactly and needs no pad. Writing the prefix out is
     * what lets an assertion below check the rendering without reading a card number.</p>
     */
    private static final String NARROWING_PREFIX = "************";

    /**
     * The chunk of the cursor a bounded consumption asks for, five rows.
     *
     * <p>Assumptions: five is far below both the retrieval batch of
     * {@value StatementCardXrefRepository#XREF_FETCH_SIZE} and the fixture's row count, which is what
     * makes a bounded consumption distinguishable from a whole walk at all.</p>
     */
    private static final int BOUNDED_PREFIX_LENGTH = 5;

    /**
     * The placeholder written into the two enciphered columns the fixture load must populate.
     *
     * <p>Assumptions: both target columns are byte arrays and one of them is not nullable, so the
     * load has to supply something; no reporting projection selects either column, so what it
     * supplies cannot affect any assertion here. A fabricated constant is used rather than a real
     * enciphering, because reaching for the owning service's cipher would couple this class to a
     * component it does not exercise and would make a cipher change read as a cursor defect.</p>
     */
    private static final byte[] PLACEHOLDER_CIPHERTEXT =
            "not-real-ciphertext".getBytes(StandardCharsets.US_ASCII);

    /**
     * The method names a mutating repository surface would carry.
     *
     * <p>Assumptions: the vocabulary is the one the persistence framework's own write interfaces
     * publish, so the assertion catches a widened base interface as well as a hand-declared write
     * method. It is matched against the interface's WHOLE method surface, inherited members
     * included, because the failure mode being guarded against is inheriting the surface rather than
     * declaring it.</p>
     */
    private static final Set<String> WRITE_METHOD_NAMES = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "insert", "update",
            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch",
            "deleteAllInBatch", "deleteAllByIdInBatch", "flush", "persist", "merge", "remove");

    /**
     * The exact set of read methods the interface under test declares.
     *
     * <p>Alternatives Considered: asserting the ABSENCE of a keyed read, which the dispatcher alone
     * appears to support. The {@code 'XREFFILE'} branch of {@code app/cbl/CBSTM03B.CBL} at L157 to
     * L179 implements only an open arm at L159, a plain read at L164 and a close at L170, with no
     * keyed-read arm and sequential access declared at L39 -- so an absence assertion reads as the
     * obvious transcription of that branch. It is rejected because it is FALSE of the interface under
     * test, which declares a keyed read on the per-card fingerprint alongside the traversal, and an
     * assertion contradicted by the type it is written against fails without saying anything about
     * the reference. The absence the reference DOES support is the absence of a write arm, and that
     * one is asserted separately. Naming the whole set is the stronger form in any case: it fails on
     * a method removed as well as on one added.</p>
     */
    private static final Set<String> DECLARED_READ_METHOD_NAMES = Set.of(
            "streamAllInCardNumberOrder", "resolveByWholeCardNumber", "findById",
            "findCardsOfAccount", "findHeadingChunk");

    /**
     * The migration directory whose ABSENCE this module's ownership requires.
     */
    private static final String MIGRATION_DIRECTORY =
            "services/reporting-service/src/main/resources/db/migration";

    /**
     * The schema-migration entry point whose absence from the classpath is asserted.
     *
     * <p>Assumptions: naming the absent tooling is this module's own convention rather than a leak of
     * one. The DELIBERATELY ABSENT block of {@code services/reporting-service/pom.xml} names the same
     * two coordinates at its L444, and both package charters in this module name them too, because an
     * absence nobody has written down is indistinguishable from an omission. A negative assertion
     * cannot be written without the name it denies.</p>
     */
    private static final String MIGRATION_TOOL_TYPE = "org.flywaydb.core.Flyway";

    /**
     * The token a schema-migration configuration key would carry, matched case-insensitively.
     */
    private static final String MIGRATION_TOOL_TOKEN = "flyway";

    /**
     * The classpath location of the profile document this class activates.
     */
    private static final String TEST_PROFILE_RESOURCE = "application-test.yml";

    /**
     * The container the context connects to, started once for this class.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text, because the container's port is assigned as it starts -- so committed text could
     * not be correct, and the failure mode of the mistake is not an error but a class that passes
     * against whichever engine happened to be listening. {@code application-test.yml} declares no
     * location, no login name and no credential precisely so that these arrive this way.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The cursor under test, injected so every read goes through the declared query.
     */
    @Autowired
    private StatementCardXrefRepository cardXrefs;

    /**
     * The entity manager, used only to read the published dimension identities the cursor must
     * resolve against.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Applies the shipped definitions and loads the fixture corpus before the context connects.
     *
     * <p>Assumptions: the container is already running when this runs, and that is a framework
     * guarantee rather than an assumption about extension ordering -- every {@code BeforeAllCallback}
     * extension is invoked before a {@code @BeforeAll} method, so the container annotation's
     * extension has started it whichever order the two class-level extensions were registered in. The
     * Spring context, by contrast, is built when the first test instance is prepared, which is after
     * this method, so the relations and the rows exist before the pool opens its first connection.</p>
     *
     * <p>Assumptions: nothing here needs to precede the pool for correctness in any case, and the
     * reason is worth recording so a future edit does not treat the ordering as fragile.
     * {@code application-test.yml} pins schema resolution to the single {@code reporting} schema, and
     * a search-path entry naming a schema that does not yet exist is accepted and simply resolves
     * nothing until it does. Its {@code ddl-auto} is {@code none}, so the provider emits no
     * schema-generation statement that could fabricate the very relations this class must not create.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @BeforeAll
    static void arrangeTheDeployedSchemaAndFixtureCorpus() {
        applyDeployedSchema();
        loadFixtureCorpus();
    }

    /**
     * Confirms the cursor yields every published card once, in the order the query declares.
     *
     * <p>Assumptions: the expected sequence is derived from the FIXTURE rather than read back from the
     * engine with the same ordering. Reading it back would compare the engine against itself and
     * would hold however the projection narrowed a card number; deriving it applies the narrowing rule
     * independently and therefore fails if the projection ever stopped applying it.</p>
     *
     * <p>Assumptions: a natural string comparison in Java is a faithful stand-in for the engine's
     * ordering over these particular values, and the reason is specific rather than general. Every
     * narrowed rendering is the same twelve-character prefix followed by four ASCII digits, so the
     * comparison reduces to four digits in every case and no collation the container may carry can
     * reorder them. That would not hold for values differing in letters or in punctuation.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the cursor yields every published card once, in the declared narrowed order")
    void theCursorYieldsEveryPublishedCardOnceInTheDeclaredOrder() {
        // WHY : Assumptions: the traversal stands in for 1000-XREFFILE-GET-NEXT at
        //       app/cbl/CBSTM03A.CBL L345, which the mainline loop at L316 to L329 calls once per
        //       iteration and which ends the run through the end-of-file arm at L356 and L357. One row
        //       reached is one statement produced, so a row lost here is a cardholder with no
        //       document and no record anywhere of the omission.
        List<CardXrefView> walked = walkTheWholeCursor();
        List<String> expected = expectedNarrowedOrder();

        assertThat(expected)
                .as("the committed fixture publishes the expected number of distinct cards")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(walked)
                .as("the cursor yields one row per card the cross-reference publishes")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(walked.stream().map(CardXrefView::getCardFingerprint).toList())
                .as("the per-card fingerprint identifies each row exactly")
                .doesNotHaveDuplicates();
        assertThat(walked.stream().map(CardXrefView::getCardNum).toList())
                .withFailMessage("the cursor must yield every published card exactly once in the"
                        + " narrowed-then-fingerprint order the query declares; the walk and the"
                        + " sequence derived from the committed fixture disagree, so either a card was"
                        + " skipped or repeated, or the ordering is no longer the declared one")
                .containsExactlyElementsOf(expected);
    }

    /**
     * Confirms two walks over unchanged rows produce one identical sequence.
     *
     * <p>Assumptions: stability is asserted within one database rather than across two, and the
     * distinction is load-bearing. The secondary ordering key is a keyed digest whose key is generated
     * per database, so two containers would compute different digests for the same card; what must be
     * reproducible is a run over one database, which is what a golden-master comparison of two runs
     * actually compares. The primary key of the ordering is reproducible everywhere, and the case
     * beside this one asserts that against the fixture.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("two walks over unchanged rows yield one identical sequence")
    void twoWalksOverUnchangedRowsYieldOneIdenticalSequence() {
        List<String> first = walkTheWholeCursor().stream().map(CardXrefView::getCardNum).toList();
        List<String> second = walkTheWholeCursor().stream().map(CardXrefView::getCardNum).toList();

        // WHY : Assumptions: an unstable ordering yields the same SET on every walk and a different
        //       SEQUENCE, so a set comparison would hold while the property failed. The ordering
        //       app/jcl/CREASTMT.JCL L53 declares is two ascending keys and is therefore total; a
        //       migrated traversal that reproduced only its leading key would pass a set comparison
        //       and produce two cardholders' statements in either sequence between runs.
        assertThat(second)
                .withFailMessage("two walks over unchanged rows must yield one identical sequence;"
                        + " they did not, so the ordering is not total and a comparison of two runs"
                        + " would differ on data that had not changed")
                .containsExactlyElementsOf(first);
    }

    /**
     * Confirms the traversal is a forward-only cursor and that a bounded consumption reads a bound.
     *
     * <p>Trade-offs: this asserts what it can and the limit is stated rather than glossed. That the
     * declared return is a stream and that a bounded consumption yields exactly the bound establishes
     * the traversal is consumed lazily and forward-only by a caller that wants a prefix. It does NOT
     * establish how many rows crossed the wire: the retrieval batch at
     * {@link StatementCardXrefRepository#XREF_FETCH_SIZE} governs that, and no assertion available
     * here can observe a batch boundary. The property that matters to the statement path is the
     * one asserted -- a caller need not materialise the relation to read part of it -- and the
     * alternative of collecting the whole stream first would prove nothing about laziness at all.</p>
     *
     * <p>Assumptions: the cursor is consumed inside try-with-resources throughout this class because
     * the interface's own contract says the caller owns it and must close it. An unclosed cursor holds
     * its statement and its connection until the pool reclaims them, and this profile admits a
     * maximum of two pooled connections, so a leak would exhaust the pool rather than merely waste
     * one -- and the failure would surface in a later test as an acquisition timeout.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws NoSuchMethodException if the declared traversal method cannot be reflected, which means
     *     the interface no longer declares it under that name
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the traversal is declared as a stream and a bounded consumption reads its bound")
    void theTraversalIsAStreamAndABoundedConsumptionReadsItsBound() throws NoSuchMethodException {
        Method traversal =
                StatementCardXrefRepository.class.getMethod("streamAllInCardNumberOrder");

        assertThat(traversal.getReturnType())
                .as("the traversal is declared as a lazily consumed sequence")
                .isEqualTo(Stream.class);

        // WHY : Assumptions: the reference reads ONE row per call and never a run.
        //       app/cbl/CBSTM03A.CBL L348 sets the plain-read opcode inside the paragraph the mainline
        //       loop at L316 to L329 calls once per iteration, so the row it holds is one row. A
        //       migrated traversal that returned a collection would hold the whole relation where the
        //       reference held a record, and the declared-width tables at L226 and L232 are the only
        //       place the reference accumulates anything at all -- which is the very arity this
        //       traversal is registered as not reproducing.
        List<String> prefix;
        try (Stream<CardXrefView> cursor = cardXrefs.streamAllInCardNumberOrder()) {
            prefix = cursor.limit(BOUNDED_PREFIX_LENGTH).map(CardXrefView::getCardNum).toList();
        }

        assertThat(prefix)
                .as("a bounded consumption yields its bound and the leading rows of the order")
                .containsExactlyElementsOf(
                        expectedNarrowedOrder().subList(0, BOUNDED_PREFIX_LENGTH));
    }

    /**
     * Confirms the cursor refuses to open outside a transaction that outlives the call.
     *
     * <p>Assumptions: this case carries no transaction annotation and every other reading case here
     * carries one, which is what makes the refusal observable at all. The declared propagation is
     * mandatory, so the framework raises before a statement is prepared.</p>
     *
     * <p>Assumptions: the exception the assertion captures is
     * {@link org.springframework.transaction.IllegalTransactionStateException}, which the framework
     * raises for a mandatory propagation with no transaction in progress. It is that type rather than
     * a persistence or data-access exception because the refusal happens in the transaction
     * interceptor, before the query is handed to the provider -- naming a data-access type here would
     * pass on any failure that reached the database and would stop asserting the propagation.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cursor refuses to open with no enclosing transaction")
    void theCursorRefusesToOpenWithNoEnclosingTransaction() {
        // WHY : Assumptions: the reference brackets the whole run between ONE open and ONE close, so
        //       the scope a cursor needs here is the scope the reference already used.
        //       app/cbl/CBSTM03A.CBL opens the cross-reference at 8200-XREFFILE-OPEN L765, setting the
        //       open opcode at L767, and closes it at 9200-XREFFILE-CLOSE L873, performed at L333 --
        //       AFTER the mainline loop at L316 to L329 has read the file repeatedly through L348. A
        //       traversal that started its own transaction would therefore be exhausted before the
        //       caller read a row, and the retrieval batch declared on the interface applies only
        //       outside autocommit, which is the same condition. Failing at the boundary is what keeps
        //       that from surfacing later as an empty statement run.
        assertThatThrownBy(() -> cardXrefs.streamAllInCardNumberOrder())
                .as("a mandatory propagation refuses to open a cursor into a scope that would close"
                        + " underneath it")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Confirms every card the cursor yields resolves to a published customer and a published account.
     *
     * <p>Assumptions: an unresolvable dimension is an abort and never a row to pass over, which is
     * the reference's own behaviour rather than a policy invented for the migration. The customer read
     * at {@code app/cbl/CBSTM03A.CBL} L379 to L386 and the account read at L403 to L410 have no
     * not-found arm: their {@code WHEN OTHER} arms perform the abend paragraph at L921. Register entry
     * <b>R10</b> records the decision. This case therefore asserts the fixture corpus satisfies that
     * precondition for every row the cursor yields, which is what makes the run's abort path
     * unreachable on this data rather than merely untested.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("every card the cursor yields resolves to a published customer and account")
    void everyCardTheCursorYieldsResolvesToAPublishedCustomerAndAccount() {
        Set<Long> publishedCustomers =
                publishedIdentities("select customer_id from reporting.v_customers");
        Set<Long> publishedAccounts =
                publishedIdentities("select account_id from reporting.v_accounts");
        List<CardXrefView> walked = walkTheWholeCursor();

        assertThat(walked).as("the cursor yielded rows to resolve").isNotEmpty();
        for (CardXrefView row : walked) {
            assertThat(publishedCustomers)
                    .withFailMessage("the cross-reference names customer %s, which no published"
                            + " customer relation carries; the reference treats that as an abort at"
                            + " app/cbl/CBSTM03A.CBL L921 rather than as a card to skip",
                            row.getCustomerId())
                    .contains(row.getCustomerId());
            assertThat(publishedAccounts)
                    .withFailMessage("the cross-reference names account %s, which no published"
                            + " account relation carries; the reference treats that as an abort at"
                            + " app/cbl/CBSTM03A.CBL L921 rather than as a card to skip",
                            row.getAccountId())
                    .contains(row.getAccountId());
        }

        // WHY : Assumptions: the keyed reads those two paragraphs perform are EXACT full-key reads
        //       and not partial-key browses, so the widths are part of the contract this cursor
        //       supplies. app/cbl/CBSTM03A.CBL L373 and L397 move a transient zero into the
        //       key-length field and L374 and L398 immediately recompute it to the declared length of
        //       the key about to be used -- nine and eleven, per app/cpy/CVACT03Y.cpy L6 and L7 -- and
        //       a search of the reference for a zero-length reference modification returns nothing.
        //       Keyset positioning must therefore never be justified from that opcode; register entry
        //       R1 records its provenance as app/cbl/COCRDLIC.cbl L230 to L244 instead.
        assertThat(walked)
                .allSatisfy(row -> {
                    assertThat(String.valueOf(row.getCustomerId()))
                            .as("the customer key fits its nine declared digits")
                            .hasSizeLessThanOrEqualTo(CUSTOMER_KEY_DIGITS);
                    assertThat(String.valueOf(row.getAccountId()))
                            .as("the account key fits its eleven declared digits")
                            .hasSizeLessThanOrEqualTo(ACCOUNT_KEY_DIGITS);
                });
    }

    /**
     * Confirms the two cross-reference fixtures agree on every card they share.
     *
     * <p>Assumptions: the two fixtures are the same record under two data-definition names and the
     * distinction is between JOBS rather than between record types. The statement path reads
     * {@code XREFFILE} at {@code app/jcl/CREASTMT.JCL} L84 alongside {@code TRNXFILE} at L83,
     * {@code ACCTFILE} at L85 and {@code CUSTFILE} at L86. The report path reads {@code CARDXREF} at
     * {@code app/jcl/TRANREPT.jcl} L67 and L68 alongside {@code TRANFILE} at L65, {@code TRANTYPE} at
     * L69, {@code TRANCATG} at L71 and {@code DATEPARM} at L73 -- and it declares no account
     * definition and no customer definition at all, so the report path never reads either master. The
     * two four-way joins share only the transaction relation and the cross-reference, and conflating
     * them would put an account read on a path that has none.</p>
     *
     * <p>Assumptions: agreement is asserted on the WHOLE triple and not on the card number alone,
     * because a divergence in the customer or the account a shared card resolves to is exactly the
     * kind of drift two fixtures for one record can develop, and it would make the statement path and
     * the report path attribute one card to two cardholders.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the statement-path and report-path cross-reference fixtures agree on shared cards")
    void theTwoCrossReferenceFixturesAgreeOnEveryCardTheyShare() {
        List<Map<String, Object>> statementPath =
                decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR);
        List<Map<String, Object>> reportPath = decodeAll(REPORT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR);
        List<String> statementTriples = identityTriples(statementPath);
        List<String> reportTriples = identityTriples(reportPath);

        // WHY : Assumptions: the two exist because the reference reads the same record under two
        //       data-definition names on two different jobs -- XREFFILE at app/jcl/CREASTMT.JCL L84 on
        //       the statement path, CARDXREF at app/jcl/TRANREPT.jcl L67 on the report path -- and the
        //       report job declares no account definition and no customer definition at all, so it
        //       never reads either master. Two fixtures for one record can drift, and a drift in the
        //       customer or the account a shared card resolves to would make the two paths attribute
        //       one card to two cardholders. Assumptions: the comparison key is the NARROWED card
        //       rendering rather than a whole card number, which costs it no discriminating power on
        //       this corpus because every card here carries a distinct last four digits, and keeps a
        //       card number out of the comparison and out of any failure message.
        assertThat(reportTriples).as("the report-path fixture carries rows to compare").isNotEmpty();
        assertThat(statementTriples)
                .withFailMessage("every card the report-path fixture carries must appear in the"
                        + " statement-path fixture with the same customer and the same account; they"
                        + " diverge, so one card would resolve to two cardholders depending on which"
                        + " job read it")
                .containsAll(reportTriples);

        List<String> published = walkTheWholeCursor().stream()
                .map(CardXrefView::getCardNum)
                .toList();
        List<String> sharedRenderings = reportPath.stream()
                .map(row -> narrowedRenderingOf(text(row, CARD_NUMBER_FIELD)))
                .toList();

        assertThat(published)
                .as("the cursor publishes every card the two fixtures share")
                .containsAll(sharedRenderings);
    }

    /**
     * Confirms the record is fifty bytes with the account key where the copybook places it.
     *
     * <p>Assumptions: the length is derived by SUMMING the declared field widths of
     * {@code app/cpy/CVACT03Y.cpy} L5 through L8 -- sixteen plus nine plus eleven plus fourteen -- and
     * never by trusting the descriptive banner at its L2, because a banner is a comment and a comment
     * cannot be wrong in a way a compiler notices. It is corroborated independently by the file
     * section of {@code app/cbl/CBSTM03B.CBL}, whose {@code FD XREF-FILE} at L65 declares a
     * sixteen-byte key at L67 and a thirty-four-byte remainder at L68.</p>
     *
     * <p>Assumptions: the trailing pad is dropped rather than carried, and naming the dropped field is
     * required of every record this migration carries across rather than being a courtesy. The pad
     * exists only to reach the declared length, so a projection member for it would publish padding as
     * data.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cross-reference record is fifty bytes with its trailing pad dropped")
    void theCrossReferenceRecordIsFiftyBytesWithItsTrailingPadDropped() {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(XREF_DESCRIPTOR);
        CopybookLayout.FieldSpec accountKey = spec.field(ACCOUNT_ID_FIELD);

        assertThat(spec.reclen()).as("the declared record length").isEqualTo(XREF_RECORD_LENGTH);
        assertThat(spec.field(CARD_NUMBER_FIELD).length())
                .as("the card number occupies its declared width")
                .isEqualTo(CardXrefView.CARD_NUMBER_WIDTH);
        assertThat(spec.field(CUSTOMER_ID_FIELD).length())
                .as("the customer key occupies its nine declared digits")
                .isEqualTo(CUSTOMER_KEY_DIGITS);

        // WHY : Assumptions: the descriptor records a zero-based start while the copybook and every
        //       citation in this repository count bytes from one, so the two differ by exactly one and
        //       an assertion written in the descriptor's frame would read as though it disagreed with
        //       the copybook. Converting here keeps the assertion legible against app/cpy/CVACT03Y.cpy
        //       L7 without changing what is checked.
        assertThat(accountKey.start() + 1)
                .as("the account key begins at its one-based first byte")
                .isEqualTo(ACCOUNT_ID_FIRST_BYTE);
        assertThat(accountKey.start() + accountKey.length())
                .as("the account key ends at its one-based last byte")
                .isEqualTo(ACCOUNT_ID_LAST_BYTE);
        assertThat(accountKey.length())
                .as("the account key occupies its eleven declared digits")
                .isEqualTo(ACCOUNT_KEY_DIGITS);

        assertThat(spec.field(PADDING_FIELD).start() + 1)
                .as("the trailing pad begins immediately after the account key")
                .isEqualTo(ACCOUNT_ID_LAST_BYTE + 1);
        assertThat(Arrays.stream(CardXrefView.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .withFailMessage("the projection must carry no member for the trailing pad; the pad"
                        + " exists only to reach the fifty-byte declared length, so a member for it"
                        + " would publish padding as data")
                .doesNotContain(PADDING_FIELD.toLowerCase(Locale.ROOT));
    }

    /**
     * Confirms the mapping refuses a write before any statement can be generated for it.
     *
     * <p>Assumptions: this is the first of two guards and it is entirely structural, which is what
     * makes it a guard at the MAPPING layer rather than at the database. Four facts together mean no
     * insert, update or delete statement for this relation can be generated at all: the projection
     * carries Hibernate's immutability marker, so it is excluded from dirty checking; it declares no
     * version member, so no optimistic write path exists; every mapped column is declared
     * non-updatable; and the interface's whole method surface, inherited members included, carries no
     * write operation. A behavioural probe was considered instead and rejected: an immutable entity
     * has its updates silently discarded rather than refused, so a probe would have to assert an
     * unchanged row -- which the read-only pool this profile inherits would produce on its own, and
     * the assertion would then hold for the wrong reason.</p>
     *
     * <p>Assumptions: the base interface is asserted by identity and not merely by the absence of
     * names. Register entry <b>R4</b> and the main-tree charter's own reasoning turn on the base being
     * the marker interface: a wider base would inherit five write methods onto the public surface of a
     * type whose entire contract is that it has no write path, and they would compile, appear in every
     * completion list, and fail at the database.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the mapping declares no write surface and no mutable column")
    void theMappingDeclaresNoWriteSurfaceAndNoMutableColumn() {
        assertThat(CardXrefView.class.getAnnotation(Immutable.class))
                .as("the projection carries the immutability marker, so it is not dirty checked")
                .isNotNull();
        assertThat(Arrays.stream(CardXrefView.class.getDeclaredFields())
                        .anyMatch(field -> field.isAnnotationPresent(Version.class)))
                .as("the projection declares no version member, so no optimistic write path exists")
                .isFalse();

        for (Field field : CardXrefView.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertThat(column.updatable())
                        .withFailMessage("every mapped column of the projection must be declared"
                                + " non-updatable; %s is not, so the provider could generate an update"
                                + " for it", field.getName())
                        .isFalse();
            }
        }

        // WHY : Assumptions: the failure mode being guarded against is INHERITING a write surface
        //       rather than declaring one, and a scan of declared methods alone cannot see that. The
        //       reference supports the absence: app/cbl/CBSTM03B.CBL declares write and rewrite
        //       opcodes at L107 and L108, mirrored at app/cbl/CBSTM03A.CBL L78 and L79, and a census
        //       of those two condition names across the whole reference returns exactly those four
        //       hits with no reference to either -- while a search of the caller for the statement
        //       that selects an opcode yields only open, close, read and keyed read. An unexercised
        //       opcode is indistinguishable from an unsupported one from the caller's side.
        assertThat(Arrays.stream(StatementCardXrefRepository.class.getMethods())
                        .map(Method::getName)
                        .filter(WRITE_METHOD_NAMES::contains)
                        .toList())
                .as("no write operation appears anywhere on the interface's method surface")
                .isEmpty();
        assertThat(StatementCardXrefRepository.class.getInterfaces())
                .as("the base is the marker interface, which declares nothing")
                .containsExactly(Repository.class);
        Set<String> declaredNames = new LinkedHashSet<>();
        for (Method declared : StatementCardXrefRepository.class.getDeclaredMethods()) {
            declaredNames.add(declared.getName());
        }
        assertThat(declaredNames)
                .as("the declared surface is exactly the five reads the register accounts for")
                .isEqualTo(DECLARED_READ_METHOD_NAMES);
    }

    /**
     * Confirms the database refuses a write from the read-only login role.
     *
     * <p>Assumptions: this is the second of the two guards and it answers the question the structural
     * one cannot -- whether a statement that DID reach the engine would be refused. The target is
     * {@code card.cards}, a base table in another context's schema, loaded from
     * {@code fixtures/carddata.txt} so the refusal is of a write to a populated relation rather than
     * to an empty or absent one.</p>
     *
     * <p>Assumptions: neither reporting program reads a card master, and the discrepancy is stated
     * rather than smoothed over. {@code app/cbl/CBSTM03A.CBL} reads the transaction, cross-reference,
     * account and customer definitions, and {@code app/jcl/TRANREPT.jcl} L65 to L73 resolves to the
     * transaction store, the cross-reference and two reference stores, so the union of the two paths
     * is {@code ledger}, {@code account} and {@code reference} alone -- the {@code card} schema feeds
     * no report and no statement. It is nonetheless inside the privileged set of the projection OWNER,
     * because a card attribute does surface from this context narrowed; register entry <b>R15</b>
     * records that gap. That is precisely what makes it the right target here: the login role has no
     * business reaching it at all, so a refusal is unambiguous.</p>
     *
     * <p>Assumptions: the role is assumed on a direct connection rather than authenticated as, and no
     * credential of any kind appears in this class as a consequence. Assuming a role drops the
     * session's own privileges for the duration, so permission checks are made as the login role
     * would meet them. The connection is deliberately NOT the pooled one, because this profile's pool
     * is declared read-only and would refuse the statement before the engine consulted a privilege --
     * which would prove the pool flag and not the grant. The pool posture is asserted separately by
     * the sibling deployed-relation class.</p>
     *
     * <p>Assumptions: the exception the assertion captures is {@link java.sql.SQLException}, raised by
     * the driver when the engine rejects the statement. It is that type rather than a framework
     * data-access exception because the statement is issued through a direct connection with no
     * framework translation in the path.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the connection to the container cannot be opened at all, which is an
     *     arrangement failure rather than the refusal under test
     */
    @Test
    @DisplayName("the read-only login role cannot write the card master it cannot even reach")
    void theReadOnlyLoginRoleCannotWriteTheCardMaster() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            requireLoadedRelation(connection, "card.cards");
            try (Statement assumeRole = connection.createStatement()) {
                assumeRole.execute("set role " + REPORTING_ROLE);
            }

            // WHY : Assumptions: read-only is the reference's own posture and not a tightening the
            //       migration invented. app/cbl/CBSTM03B.CBL L160 opens the cross-reference with
            //       OPEN INPUT -- input, never input-output and never output -- and the write and
            //       rewrite opcodes it declares at L107 and L108 are referenced by no paragraph in
            //       either program. Assumptions: the statement names a card number the fixture corpus
            //       does not carry, so a database that ACCEPTED it would leave a row behind and the
            //       assertion would have to distinguish a privilege refusal from a uniqueness one.
            //       Choosing a value no fixture uses removes that ambiguity, and the condition code
            //       asserted below removes the rest of it.
            assertThatThrownBy(() -> {
                try (Statement write = connection.createStatement()) {
                    write.executeUpdate("insert into card.cards"
                            + " (card_num, account_id, cvv_encrypted, embossed_name,"
                            + " expiration_date, active_status, version)"
                            + " values ('0000000000000000', 7, '\\x00', 'NOBODY',"
                            + " date '2030-01-01', 'Y', 0)");
                }
            })
                    .withFailMessage("the read-only login role must be unable to write the card"
                            + " master; the engine accepted the statement, so a privilege is wider"
                            + " than data-migration/sql/V0__schemas_and_roles.sql conveys")
                    .isInstanceOf(SQLException.class)
                    .extracting(thrown -> ((SQLException) thrown).getSQLState())
                    .as("the refusal is a privilege refusal and not some other rejection")
                    .isEqualTo(INSUFFICIENT_PRIVILEGE_STATE);
        }
    }

    /**
     * Confirms this module ships no migration directory and no migration tooling.
     *
     * <p>Alternatives Considered: owning a schema as the other seven contexts do. Rejected because
     * this context creates no record: the target design records its owned tables as "(none)" and it
     * reads every other context's data through read-only projections, so a schema of its own would
     * convey write authority it must never hold. Also considered: a read replica for reporting.
     * Rejected because a replica adds cost and replica-lag semantics for no parity benefit -- register
     * entry <b>R12</b> carries that ground, and the specific behavioural cost is that the reference
     * statement job reads the live transaction cluster directly at {@code app/jcl/CREASTMT.JCL} L45,
     * so it cannot omit a transaction the online path has already accepted while a statement generated
     * from a lagging replica can.</p>
     *
     * <p>Assumptions: the tooling's absence is asserted by attempting to load its entry point rather
     * than by inspecting a dependency list, because what matters is the CLASSPATH the module runs
     * with. A dependency declared and excluded, or supplied transitively, differs from what a manifest
     * reads.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("this module ships no migration directory and no migration tooling")
    void thisModuleShipsNoMigrationDirectoryAndNoMigrationTooling() {
        assertThat(repositoryRoot().resolve(MIGRATION_DIRECTORY))
                .withFailMessage("%s must not exist; its presence would mean this context had begun"
                        + " to own a schema, and the relations it reads are authored by"
                        + " data-migration/sql and by four other services' migrations",
                        MIGRATION_DIRECTORY)
                .doesNotExist();

        assertThatThrownBy(() -> Class.forName(MIGRATION_TOOL_TYPE))
                .as("the migration tooling's entry point is not loadable from this module")
                .isInstanceOf(ClassNotFoundException.class);

        // WHY : Assumptions: defining nothing it reads is the reference's own arrangement.
        //       app/cbl/CBSTM03A.CBL declares only TWO file definitions of its own, both outputs --
        //       the selects at L39 and L40 and the descriptions at L44 and L46 -- and declares none of
        //       the four inputs it reads; those arrive as the data definitions at
        //       app/jcl/CREASTMT.JCL L83 to L86, supplied by the job rather than by the program. This
        //       module's counterpart of that arrangement is owning no migration directory at all.
        //       Assumptions: an absent dependency and an absent configuration key are separate
        //       failures with separate remedies, so both are asserted. A key naming migration tooling
        //       could arrive in this document while the dependency stayed absent -- it would bind
        //       silently and do nothing, and a later reader would take its presence as evidence that
        //       this module runs migrations. The match is case-insensitive because the framework binds
        //       a relaxed key, so one spelling would not settle it.
        assertThat(profileDocumentLines())
                .withFailMessage("%s must declare no schema-migration key under any spelling; this"
                        + " context owns no schema, and a key here would read as evidence that it"
                        + " does", TEST_PROFILE_RESOURCE)
                .noneMatch(line -> line.toLowerCase(Locale.ROOT).contains(MIGRATION_TOOL_TOKEN));
    }

    /**
     * Confirms the traversal carries no arity ceiling where the reference declares two.
     *
     * <p>Assumptions: the fixture deliberately EXCEEDS the reference's declared distinct-card arity,
     * and that is the point of its size rather than an accident of it.
     * {@code app/cbl/CBSTM03A.CBL} L226 declares a card table of
     * {@value #REFERENCE_DECLARED_CARD_TABLE_ARITY} entries with its companion counter table at L232,
     * so the reference holds a run in declared-width working storage. The Java holds one row at a time
     * behind a retrieval batch and declares no ceiling of any kind. The baseline does one thing, the
     * Java does another, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} as divergence D-2 -- cited here, not
     * redefined.</p>
     *
     * <p>Assumptions: three numbers must be kept apart and this case asserts against exactly one of
     * them. The declared inner arity is ten, at L228; the declared distinct-card arity is
     * {@value #REFERENCE_DECLARED_CARD_TABLE_ARITY}, at L226 and L232; and the same-card overrun
     * threshold measured against the reference is a third and much larger number. There is no one
     * combined ceiling, and reading the distinct-card figure as a transaction count would mislabel two
     * separate limits as one.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the traversal yields more distinct cards than the reference table declares")
    void theTraversalYieldsMoreDistinctCardsThanTheReferenceTableDeclares() {
        // WHY : Assumptions: the fingerprint is unique per card while the narrowed rendering is only
        //       incidentally so on this corpus, so counting the fingerprint counts cards even if a
        //       future fixture added two cards sharing a tail. The figure it is compared against is the
        //       arity app/cbl/CBSTM03A.CBL L226 declares for its card table, with the companion counter
        //       table at L232 declaring the same arity again -- so the reference cannot hold more than
        //       that many distinct cards in one run, and this corpus deliberately holds more.
        Set<String> distinctCards = new LinkedHashSet<>(
                walkTheWholeCursor().stream().map(CardXrefView::getCardFingerprint).toList());

        assertThat(distinctCards)
                .as("every card the cursor yields is a distinct card")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(distinctCards.size())
                .withFailMessage("the fixture must publish more distinct cards than the reference's"
                        + " declared card-table arity of %d, or this case asserts nothing about the"
                        + " absence of an arity ceiling", REFERENCE_DECLARED_CARD_TABLE_ARITY)
                .isGreaterThan(REFERENCE_DECLARED_CARD_TABLE_ARITY);
    }

    /**
     * Walks the whole cursor and collects its rows in the order it yielded them.
     *
     * <p>Assumptions: the cursor is consumed inside try-with-resources because the interface's
     * contract states the caller owns it and must close it, and this profile admits a maximum of two
     * pooled connections -- so a leaked cursor would exhaust the pool and surface in a later case as
     * an acquisition timeout rather than as a leak.</p>
     *
     * @return every row the cursor yielded, in the order it yielded them, never {@code null}
     */
    private List<CardXrefView> walkTheWholeCursor() {
        try (Stream<CardXrefView> cursor = cardXrefs.streamAllInCardNumberOrder()) {
            return cursor.toList();
        }
    }

    /**
     * Reads one published dimension's identifiers as a set.
     *
     * <p>Assumptions: the query reads a projection in the one schema this login role may reach, never
     * the base table beneath it, because {@code application-test.yml} pins schema resolution to that
     * schema alone and the base tables are unreachable from here by privilege as well as by name.</p>
     *
     * @param sql a query returning one integral identifier column
     * @return the identifiers that query produced, never {@code null}
     */
    private Set<Long> publishedIdentities(String sql) {
        List<?> rows = entityManager.createNativeQuery(sql).getResultList();
        Set<Long> identities = new LinkedHashSet<>();
        for (Object row : rows) {
            identities.add(((Number) row).longValue());
        }
        return identities;
    }

    /**
     * Derives the sequence the cursor must yield, from the committed fixture alone.
     *
     * <p>Assumptions: the narrowing rule is applied here rather than read back from the engine, so this
     * sequence is an independent statement of the expected order. Sorting is by natural string
     * comparison, which is faithful for these values because every rendering is one constant prefix
     * followed by four ASCII digits.</p>
     *
     * @return the narrowed renderings of every card the statement-path fixture publishes, ascending,
     *     never {@code null}
     */
    private static List<String> expectedNarrowedOrder() {
        return decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR).stream()
                .map(row -> narrowedRenderingOf(text(row, CARD_NUMBER_FIELD)))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * Applies the narrowing rule the projection applies, to one whole card number.
     *
     * <p>Assumptions: the result occupies the declared width of
     * {@value CardXrefView#CARD_NUMBER_WIDTH} exactly, because the prefix is twelve characters and the
     * published tail is four, so the projection's cast to that width neither pads nor truncates. A
     * whole card number never leaves this method: the value it returns is the only card rendering any
     * assertion or message in this class handles.</p>
     *
     * @param wholeCardNumber a whole card number as the fixture carries it
     * @return that card narrowed to a constant prefix and its last four digits, never {@code null}
     */
    private static String narrowedRenderingOf(String wholeCardNumber) {
        return NARROWING_PREFIX
                + wholeCardNumber.substring(wholeCardNumber.length() - PUBLISHED_TAIL_LENGTH);
    }

    /**
     * Renders each decoded cross-reference row as one comparable identity triple.
     *
     * <p>Assumptions: the card component is the NARROWED rendering and never the whole number, so two
     * fixtures can be compared without either the comparison or a failure message carrying a card
     * number. The narrowed rendering is unique across this corpus -- every card in the statement-path
     * fixture has a distinct last four digits, which the ordering case asserts -- so narrowing costs
     * the comparison no discriminating power here.</p>
     *
     * @param rows decoded cross-reference rows
     * @return one triple per row joining the narrowed card, its customer and its account, never
     *     {@code null}
     */
    private static List<String> identityTriples(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> narrowedRenderingOf(text(row, CARD_NUMBER_FIELD))
                        + '/' + integral(row, CUSTOMER_ID_FIELD)
                        + '/' + integral(row, ACCOUNT_ID_FIELD))
                .toList();
    }

    /**
     * Fails unless the named relation exists and carries at least one row.
     *
     * <p>Assumptions: a refusal to write an ABSENT relation is indistinguishable from a refusal to
     * write a forbidden one, so the write-refusal case establishes the target is really there first.
     * The diagnostic names the file that OWNS the relation, because a relation missing here is a defect
     * to report against that file and never something for this class to create.</p>
     *
     * @param connection an open connection able to read the relation
     * @param relation the schema-qualified relation name
     * @throws SQLException if the relation cannot be read at all
     * @throws AssertionError if the relation carries no row
     */
    private static void requireLoadedRelation(Connection connection, String relation)
            throws SQLException {
        long rows;
        try (Statement count = connection.createStatement();
                ResultSet answer = count.executeQuery("select count(*) from " + relation)) {
            answer.next();
            rows = answer.getLong(1);
        }
        assertThat(rows)
                .withFailMessage("%s must exist and carry rows before a refusal to write it means"
                        + " anything; the relation is owned by"
                        + " services/card-service/src/main/resources/db/migration/V1__card.sql and the"
                        + " privileges over it by data-migration/sql/V0__schemas_and_roles.sql, so an"
                        + " absence here is a defect to report against one of those files", relation)
                .isPositive();
    }

    /**
     * Reads the activated profile document from the classpath, line by line.
     *
     * <p>Assumptions: the document is read from the CLASSPATH and not from a working-tree path,
     * because what governs this context is the copy the test resources contribute. A path-based read
     * would resolve differently depending on the directory the build was invoked from.</p>
     *
     * @return every line of the profile document, in file order, never {@code null}
     * @throws UncheckedIOException if the document cannot be read
     * @throws IllegalStateException if the document is absent from the classpath
     */
    private static List<String> profileDocumentLines() {
        try (InputStream stream = StatementCardXrefRepositoryIT.class.getClassLoader()
                .getResourceAsStream(TEST_PROFILE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the profile document " + TEST_PROFILE_RESOURCE
                        + " is absent from the classpath, so the profile this class activates cannot"
                        + " be the one it asserts about");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException(
                    "cannot read the profile document " + TEST_PROFILE_RESOURCE, cause);
        }
    }

    /**
     * Applies every shipped definition file, unchanged, in the order an operator applies them.
     *
     * <p>Assumptions: each file is copied into the container and executed by the engine's OWN client
     * rather than sent through the driver, and the reason is syntactic. Two of the files wrap
     * themselves in an explicit transaction and several carry dollar-quoted procedural blocks whose
     * bodies hold semicolons, so any client-side splitting on a statement terminator would cut them in
     * half. Handing the whole file to the client the shipping documentation tells an operator to use
     * removes the question entirely.</p>
     *
     * <p>Assumptions: the host paths are resolved from the repository root located by probe rather
     * than by a fixed number of parent steps, so this class runs identically from the module directory
     * and from the reactor root.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a shipped file is absent, naming the path so the defect is
     *     reported against the file that owns it
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
     * Loads the four fixtures this class reads into the relations their owning services declare.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against its registered descriptor, so the load carries the record layout the migration is
     * defined against rather than a second transcription of it. Alternatives Considered: parsing the
     * fixed-width rows here by offset. Rejected because the descriptor registry is the one place
     * offsets are declared, and a second parser in this class would drift from it silently -- and the
     * fixture is the artifact whose geometry the assertions depend on.</p>
     *
     * <p>Assumptions: the loads are inserts and nothing else. This class defines no relation, conveys
     * no privilege and discards nothing; the boundary this directory draws is on DEFINITIONS, and
     * inserting rows sits inside it because a fixture has to be loaded before anything can be read.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded
     */
    private static void loadFixtureCorpus() {
        try (Connection connection = POSTGRES.createConnection("")) {
            loadAccounts(connection);
            loadCustomers(connection);
            loadCards(connection);
            loadCardCrossReferences(connection);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the statement-path fixture corpus", cause);
        }
    }

    /**
     * Loads {@code acctfile.txt} into the account master.
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadAccounts(Connection connection) throws SQLException {
        String sql = "insert into account.accounts(account_id, active_status, curr_bal,"
                + " credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date,"
                + " curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("acctfile.txt", "ACCOUNT")) {
                insert.setLong(1, integral(row, "ACCT-ID"));
                insert.setString(2, text(row, "ACCT-ACTIVE-STATUS"));
                insert.setBigDecimal(3, money(row, "ACCT-CURR-BAL"));
                insert.setBigDecimal(4, money(row, "ACCT-CREDIT-LIMIT"));
                insert.setBigDecimal(5, money(row, "ACCT-CASH-CREDIT-LIMIT"));
                insert.setObject(6, date(row, "ACCT-OPEN-DATE"));
                insert.setObject(7, date(row, "ACCT-EXPIRAION-DATE"));
                insert.setObject(8, date(row, "ACCT-REISSUE-DATE"));
                insert.setBigDecimal(9, money(row, "ACCT-CURR-CYC-CREDIT"));
                insert.setBigDecimal(10, money(row, "ACCT-CURR-CYC-DEBIT"));
                insert.setString(11, text(row, "ACCT-ADDR-ZIP"));
                insert.setString(12, text(row, "ACCT-GROUP-ID"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code custfile.txt} into the customer master.
     *
     * <p>Assumptions: the two enciphered columns receive {@link #PLACEHOLDER_CIPHERTEXT} because no
     * reporting projection selects either, so what they hold cannot affect an assertion here, and
     * reaching for the owning service's cipher would couple this class to a component it does not
     * exercise.</p>
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCustomers(Connection connection) throws SQLException {
        String sql = "insert into account.customers(customer_id, first_name, middle_name, last_name,"
                + " addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip,"
                + " phone_num_1, phone_num_2, ssn_encrypted, govt_issued_id_encrypted, dob,"
                + " eft_account_id, pri_card_holder_ind, fico_credit_score, version)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("custfile.txt", "CUSTOMER")) {
                insert.setLong(1, integral(row, "CUST-ID"));
                insert.setString(2, text(row, "CUST-FIRST-NAME"));
                insert.setString(3, text(row, "CUST-MIDDLE-NAME"));
                insert.setString(4, text(row, "CUST-LAST-NAME"));
                insert.setString(5, text(row, "CUST-ADDR-LINE-1"));
                insert.setString(6, text(row, "CUST-ADDR-LINE-2"));
                insert.setString(7, text(row, "CUST-ADDR-LINE-3"));
                insert.setString(8, text(row, "CUST-ADDR-STATE-CD"));
                insert.setString(9, text(row, "CUST-ADDR-COUNTRY-CD"));
                insert.setString(10, text(row, "CUST-ADDR-ZIP"));
                insert.setString(11, text(row, "CUST-PHONE-NUM-1"));
                insert.setString(12, text(row, "CUST-PHONE-NUM-2"));
                insert.setBytes(13, PLACEHOLDER_CIPHERTEXT);
                insert.setBytes(14, PLACEHOLDER_CIPHERTEXT);
                insert.setObject(15, date(row, "CUST-DOB-YYYY-MM-DD"));
                insert.setString(16, text(row, "CUST-EFT-ACCOUNT-ID"));
                insert.setString(17, text(row, "CUST-PRI-CARD-HOLDER-IND"));
                insert.setShort(18, (short) integral(row, "CUST-FICO-CREDIT-SCORE"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code carddata.txt} into the card master.
     *
     * <p>Assumptions: this fixture is infrastructural to this class and is read for one purpose only --
     * it populates the relation the write-refusal case targets. Its verification value at offset 27 is
     * sensitive and is neither selected nor asserted on anywhere here; the column receives
     * {@link #PLACEHOLDER_CIPHERTEXT} instead, because the target column holds ciphertext and no
     * reporting projection publishes it at all.</p>
     *
     * @param connection an open connection able to write the card schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCards(Connection connection) throws SQLException {
        String sql = "insert into card.cards(card_num, account_id, cvv_encrypted, embossed_name,"
                + " expiration_date, active_status, version) values (?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("carddata.txt", "CARD")) {
                insert.setString(1, text(row, "CARD-NUM"));
                insert.setLong(2, integral(row, "CARD-ACCT-ID"));
                insert.setBytes(3, PLACEHOLDER_CIPHERTEXT);
                insert.setString(4, text(row, "CARD-EMBOSSED-NAME"));
                insert.setObject(5, date(row, "CARD-EXPIRAION-DATE"));
                insert.setString(6, text(row, "CARD-ACTIVE-STATUS"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code xreffile.txt} into the card cross-reference.
     *
     * <p>Assumptions: this is the statement-path fixture and the only one of the two cross-reference
     * fixtures loaded, because loading both would insert one card twice and the relation's key is the
     * card number. The four rows the report-path fixture carries are present in this one verbatim,
     * which is the property the agreement case asserts.</p>
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCardCrossReferences(Connection connection) throws SQLException {
        String sql = "insert into account.card_xref(card_num, customer_id, account_id)"
                + " values (?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row
                    : decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR)) {
                insert.setString(1, text(row, CARD_NUMBER_FIELD));
                insert.setLong(2, integral(row, CUSTOMER_ID_FIELD));
                insert.setLong(3, integral(row, ACCOUNT_ID_FIELD));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Decodes every row of one fixture through the shared codec against its registered descriptor.
     *
     * <p>Assumptions: each fixture is line-oriented with one record per line, and the newline is a
     * file convention that is not part of any record -- the declared length excludes it, so reading a
     * fixture as one continuous byte stream would mis-align every record after the first. The bytes are
     * taken in a single-byte encoding so that one character is one byte and every declared offset
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
        try (InputStream stream = StatementCardXrefRepositoryIT.class.getClassLoader()
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
     * <p>Assumptions: the trailing blanks are removed because the copybook pads a character field to a
     * declared width while the target column is variable-width for exactly the fields holding text a
     * person reads. Loading the pad would make every comparison depend on it.</p>
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
     * through an approximate numeric type, which transformation rule T3 of the migration plan requires
     * of every value in the money path. Nothing this class ASSERTS is monetary -- the cross-reference
     * projection carries no monetary member at all -- so this exists only so the account load can
     * populate the columns the cursor's dimensions declare.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's exact value, never {@code null}
     */
    private static BigDecimal money(Map<String, Object> row, String field) {
        return (BigDecimal) row.get(field);
    }

    /**
     * Reads one decoded character field holding an already-ordered calendar date.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return that date, never {@code null}
     */
    private static LocalDate date(Map<String, Object> row, String field) {
        return LocalDate.parse(text(row, field));
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a fixed
     * number of parent steps, so this class runs identically from the reactor root and from the module
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
     * The narrowest context that can create the cursor's repository proxy.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason all three sibling
     * integration tests record: scanning the root would instantiate the orchestration client and the
     * filter chain, so a context started to walk a cursor would additionally need a state-machine
     * identifier and an object-store bucket, and a failure to supply either would read as a cursor
     * defect. Alternatives Considered: a data-access test slice, which would restrict
     * auto-configuration to persistence and remove the cloud values supplied above entirely. It is not
     * available -- the artifact carrying that annotation is absent from
     * {@code services/reporting-service/pom.xml} and from the reactor's managed set -- and adding a
     * dependency to reshape a test is not a trade this module makes.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class CardXrefCursorTestApplication {
    }
}
