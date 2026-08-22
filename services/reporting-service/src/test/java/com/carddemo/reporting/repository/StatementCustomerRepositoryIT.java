package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.reporting.domain.CustomerView;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.annotations.Immutable;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the customer lookup is an exact whole-key read whose empty answer is fatal, not benign.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code StatementCustomerRepository.findById} replaces one data definition and one operation
 * code. The definition is {@code //CUSTFILE DD} at {@code app/jcl/CREASTMT.JCL} L86, the last of the
 * four inputs the statement step reads at L83 to L86; the operation code is the keyed read of the
 * {@code 'CUSTFILE'} branch of {@code app/cbl/CBSTM03B.CBL}, which spans L181 to L204 and implements
 * an open at L184, that keyed read at L189 and L190, and a close at L196 -- and no plain sequential
 * read at all. The caller reaches it once per cross-reference row, from
 * {@code 2000-CUSTFILE-GET.} at L368 of {@code app/cbl/CBSTM03A.CBL}. Two properties of that read
 * are invisible to every cheaper gate in this module and are asserted here against a real engine
 * carrying the shipped definitions: the key is matched WHOLE, so no shorter form of a published
 * identifier resolves anything; and an unresolved identifier is a referential-integrity violation
 * that stops the run rather than an end-of-data condition that ends it.
 *
 * <h2>What this class asserts that no sibling does</h2>
 *
 * <p>Assumptions: the four classes already in this directory divide the module's engine-backed
 * properties between them, and this one takes the fifth rather than restating any of theirs.
 * {@code ReportingQueryBootstrapIT} establishes no relation and proves the declared queries parse;
 * {@code StatementHeadingChunkIT} seeds stand-in tables and proves the heading walk's keyset
 * predicate reproduces its own ordering; {@code ReportingDeployedRelationIT} applies the shipped
 * definitions and proves the projections narrow a card, that a card joins to its customer and its
 * account, and that the login role cannot write a relation it can read;
 * {@code StatementCardXrefRepositoryIT} walks the driving cursor and owns the two-level write
 * refusal. Two of them read a customer identifier, and neither asserts what this class does: the
 * deployed-relation class resolves one customer per joined row to show the join holds, and the
 * cursor class asserts each identifier is a member of a set it reads back with its own query and
 * fits its nine declared digits. Both ask whether a PRESENT identifier resolves. This class asks the
 * opposite question -- what an identifier that is nearly right, or absent altogether, does -- and
 * that is where a browse and a whole-key read differ.
 *
 * <h2>Assumptions: the operation code is an exact whole-key read and never a partial-key browse</h2>
 *
 * <p>This class is the primary evidence site for that finding, which register entry <b>R3</b> in the
 * main-tree charter states for the package. Three independent pieces of evidence settle it, and each
 * is verified against the file below rather than merely quoted, so a citation cannot drift into
 * unrelated text without a case here failing. First, the file is declared
 * {@code ACCESS MODE IS RANDOM} at {@code app/cbl/CBSTM03B.CBL} L45, and a COBOL indexed file so
 * declared cannot be browsed at all, while L33 and L39 declare {@code SEQUENTIAL} for the two
 * ordered-scan definitions. Second, the callee body at L188 to L193 is a reference-modified move
 * followed by a plain keyed read -- {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID} at
 * L189 then {@code READ CUST-FILE INTO LK-M03B-FLDT} at L190 -- with no positioning verb and no
 * read-next of any kind. Third, the caller computes the WHOLE key length every time:
 * {@code app/cbl/CBSTM03A.CBL} L372 moves the cross-reference identifier into the key, L373 moves a
 * transient zero into the key-length field, and L374 immediately recomputes it as
 * {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID}, which is nine. The transient zero is
 * never the value the callee reads.
 *
 * <p>Assumptions: keyset positioning must therefore never be justified from this operation code, and
 * the misreading is worth naming because the length field's declaration invites it. Register entry
 * <b>R1</b> records that positioning's provenance as {@code app/cbl/COCRDLIC.cbl} L230 to L244
 * instead, and the class that asserts it is the report-path one, not this one. A repository-wide
 * search for a zero-length reference modification returns nothing, so the baseline contains no
 * partial-key read anywhere for a reader to generalise from.
 *
 * <h2>Assumptions: an unresolved identifier aborts the run rather than omitting a statement</h2>
 *
 * <p>The asymmetry between two reads in one paragraph pair is the whole ground for the contract, and
 * register entry <b>R10</b> is its package-wide statement. The cross-reference read at
 * {@code app/cbl/CBSTM03A.CBL} L345 to L366 tolerates end-of-file through the
 * {@code WHEN '10' MOVE 'Y' TO END-OF-FILE} arm at L356 and L357, and the mainline loop ends on that
 * flag -- exhaustion of the cross-reference is how a run finishes normally. The customer read at
 * L368 to L390 carries no such arm: its evaluation at L379 to L386 has only {@code WHEN '00'} and
 * {@code WHEN OTHER}, and the second reaches {@code PERFORM 9999-ABEND-PROGRAM} at L385, whose
 * paragraph at L921 displays a line at L922 and issues {@code CALL 'CEE3ABD'} at L923. Treating the
 * two alike would let a broken referential chain truncate a statement run silently instead of
 * failing it, which is the one outcome neither the baseline nor the target permits.
 *
 * <h2>Why the shipped definitions are applied rather than reproduced</h2>
 *
 * <p>Assumptions: every relation read below is created by executing the shipped files themselves,
 * read from the working tree and handed to the engine unchanged. Nothing here authors a schema
 * object of any kind -- no relation, no privilege, no index -- and no statement below defines one.
 * The authority for the schemas, the roles and the privileges is
 * {@code data-migration/sql/V0__schemas_and_roles.sql}; the authority for the projection this class
 * reads is {@code data-migration/sql/V1__reporting_views.sql}; the base table beneath it belongs to
 * the account service's migration. Register entry <b>R11</b> records that a relation missing at run
 * time is a defect to report against whichever of those files owns it, never something for a class
 * here to remedy.
 *
 * <p>Alternatives Considered: a hand-written stand-in for the projection, which is what the sibling
 * ordering class uses. Rejected here because the property this class exists for lives partly inside
 * the view definition: the two protected identifier columns are absent from the projection rather
 * than masked within it, and a stand-in would assert that of the stand-in and nothing about what
 * ships.
 *
 * <h2>Alternatives Considered: the arrangement is repeated here rather than shared</h2>
 *
 * <p>Alternatives Considered: extracting the container declaration, the definition-application loop
 * and the fixture load into a base class or a support type that the classes here would inherit. That
 * is the obvious response to seeing the same shape a third time, and it is declined for two reasons
 * beyond taste. This directory's charter fixes its own contents -- a class here earns its place by
 * owning one engine-backed property, and a support type owns none -- so an inherited arrangement
 * would add a file that no property accounts for and that the naming contract would not select.
 * More concretely, the arrangements are not actually the same: the deployed-relation class grants a
 * login and switches the pool onto it, the cursor class loads four fixtures because its
 * write-refusal target is a card master, and this one loads two and needs no privilege at all. A
 * shared type would have to carry the union of those needs, so each class would inherit setup it does
 * not use and a change made for one would run for all three.
 *
 * <p>Trade-offs: what the repetition costs is real and is accepted rather than waved away. The same
 * eleven definition files are applied a third time, so this class does not reach its first assertion
 * until a container has started and those files plus a fixture load have run, and a change to the
 * shipped definition set has to be mirrored in three places. What it buys is that each class states
 * its own preconditions in its own source, so a reader can tell what a case depends on without
 * opening another file, and a failure here cannot be caused by setup another class needed.
 *
 * <h2>Why no failure message below names a customer identifier</h2>
 *
 * <p>Assumptions: a failure message from this class is written to the build log, and a build log is
 * retained, aggregated and read far more widely than the fixture corpus it describes -- which is what
 * makes an identifier in one a sensitive-diagnostic exposure rather than a convenience. Every case
 * below therefore identifies the argument it failed on by its ORDINAL within the ordered set the case
 * walks -- a published identity, a leading fragment, a nearby absent key, or a row of the committed
 * cross-reference -- and never by the value itself. The ordinal is exactly as diagnostic here,
 * because each of those sets is closed and is declared in fixture order in this file, and the case
 * that walks the cross-reference additionally asserts the published set against the committed
 * fixture -- so an ordinal locates one argument unambiguously for anyone holding the fixture.
 *
 * <p>Alternatives Considered: naming a leading fragment of an identifier, an abbreviation of one, or
 * a digest of one. All three are rejected on one ground -- over the closed domains this class probes,
 * each is trivially invertible, so each is still key material. A fragment is additionally the WORST
 * of the three here, because this class's whole subject is that a fragment is a distinct key with its
 * own resolution behaviour, so a message naming one would be both a leak and ambiguous about which
 * argument had failed.
 *
 * <h2>Parameters, return values, exceptions or errors</h2>
 *
 * <p>This is a test class with no constructor a caller invokes, no value it yields and no exception
 * it raises outside the test engine, so the type accepts no parameter, returns nothing and raises
 * nothing. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids a docstring that omits parameters, return values or exceptions.
 */
@Testcontainers
@SpringBootTest(
        classes = StatementCustomerRepositoryIT.CustomerLookupTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: a context that enables auto-configuration at all builds those starters'
    //       beans, and the region provider resolves eagerly and raises when it finds none. Nothing
    //       below contacts a cloud service, so the value identifies nothing this class reaches. It
    //       is supplied here rather than in application-test.yml because that document's own
    //       register of deliberate omissions rules that a cloud-service value belongs to the test
    //       that needs it.
    "spring.cloud.aws.region.static=us-east-1",
    // WHY : Assumptions: left enabled, each importer walks the whole credential-provider chain
    //       looking for a store to read, which costs a fixed delay on a host where the final probe
    //       fails fast and a timeout on a host where it hangs instead, so switching them off is
    //       about determinism rather than tidiness. The second key's NAME mentions a secret store
    //       while its VALUE is what stops one from being read, so neither key is a credential; this
    //       class supplies no access key and no signing material at all, because it makes no cloud
    //       call for one to sign.
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class StatementCustomerRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and the properties asserted here
     * that depend on the engine -- how a view resolves and which columns it exposes through the
     * catalogue -- are ones an engine version can genuinely change.</p>
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
     * settings in the SAME client session as the file they precede, which is what makes them still
     * in force while the file executes. Alternatives Considered: recording them on the database
     * instead. Rejected because that form is a schema-alteration statement, and this directory's
     * charter draws its boundary on definitions -- executing an authoritative definition unchanged
     * is permitted, authoring one is not, and a database-level setting written here would be a
     * definition this class had authored.</p>
     */
    private static final List<String> BOOTSTRAP_ACKNOWLEDGEMENTS = List.of(
            "carddemo.bootstrap_allow_insecure",
            "carddemo.bootstrap_allow_missing_credentials");

    /**
     * The shipped definition files, repository-relative, in the order an operator applies them.
     *
     * <p>Assumptions: this list is the one both sibling engine-backed classes in this directory
     * apply, entry for entry, and it is reproduced rather than narrowed so that all three assert
     * against one arrangement. The bootstrap appears TWICE and the repetition is required rather
     * than defensive: its first pass creates the schemas and the roles, while the cross-schema read
     * privileges it conveys to the projection owner are conditional on base tables that do not
     * exist on that pass. A projection executes with its owner's rights, so a privilege missing at
     * creation surfaces on the first READ rather than at creation, which is exactly the failure the
     * second pass removes.</p>
     *
     * <p>Assumptions: the last entry is the one that creates the projection under test, so it
     * cannot be omitted -- a shorter list ending at the per-service migrations would leave this
     * class asserting against a relation that does not exist. The reference service's seed script is
     * deliberately NOT applied: the projection read here is derived from the account schema alone,
     * so seeded reference rows would be loaded for no assertion.</p>
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
     * The customer fixture, bound by exact name.
     */
    private static final String CUSTOMER_FIXTURE = "custfile.txt";

    /**
     * The statement-path cross-reference fixture, bound by exact name.
     *
     * <p>Assumptions: this file and {@code cardxref.txt} are DIFFERENT fixtures for the same 50-byte
     * record under two different data-definition names, and conflating them would misplace an
     * assertion. This one is named for the {@code XREFFILE} definition at
     * {@code app/jcl/CREASTMT.JCL} L84, which the statement generator reads alongside the customer
     * definition at L86; the other is named for the {@code CARDXREF} definition at
     * {@code app/jcl/TRANREPT.jcl} L67, on a path that reads no customer master at all.</p>
     */
    private static final String STATEMENT_PATH_XREF_FIXTURE = "xreffile.txt";

    /**
     * The registered copybook descriptor the customer fixture decodes against.
     */
    private static final String CUSTOMER_DESCRIPTOR = "CUSTOMER";

    /**
     * The registered copybook descriptor the cross-reference fixture decodes against.
     */
    private static final String XREF_DESCRIPTOR = "XREF";

    /**
     * The declared length of the customer record, five hundred bytes.
     *
     * <p>Assumptions: this figure is corroborated twice over rather than taken from a banner
     * comment. The named fields of {@code app/cpy/CVCUS01Y.cpy} sum to 332 bytes and its
     * {@code PIC X(168)} pad at L23 completes the 500; independently, the file definition at
     * {@code app/cbl/CBSTM03B.CBL} L70 to L73 declares a nine-byte key and a 491-byte remainder,
     * which sum to the same total. The banner at {@code app/cpy/CVCUS01Y.cpy} L2 agrees, and is the
     * weakest of the three because a comment cannot be wrong in a way a compiler notices.</p>
     */
    private static final int CUSTOMER_RECORD_LENGTH = 500;

    /**
     * The key width the file definition declares, nine bytes.
     */
    private static final int FILE_DEFINITION_KEY_WIDTH = 9;

    /**
     * The remainder width the file definition declares, four hundred and ninety-one bytes.
     */
    private static final int FILE_DEFINITION_DATA_WIDTH = 491;

    /**
     * The declared digit count of the customer identifier, nine.
     *
     * <p>Assumptions: nine is the width the CALLER computes for every keyed read, at
     * {@code app/cbl/CBSTM03A.CBL} L374, and it is the width of the cross-reference member that
     * supplies every value, {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy} L6,
     * occupying bytes 17 to 25 of the 50-byte record. The record member the row is read into is
     * declared the same width at {@code app/cpy/CVCUS01Y.cpy} L5. Under the target's mapping for a
     * numeric identity those nine digits become a {@code BIGINT} column and a {@code Long}, which is
     * the identifier type the interface under test takes.</p>
     */
    private static final int CUSTOMER_KEY_DIGITS = 9;

    /**
     * The copybook field name of the customer identifier.
     */
    private static final String CUSTOMER_ID_FIELD = "CUST-ID";

    /**
     * The copybook field name of the cross-reference's card number.
     */
    private static final String XREF_CARD_NUMBER_FIELD = "XREF-CARD-NUM";

    /**
     * The copybook field name of the cross-reference's customer identifier.
     */
    private static final String XREF_CUSTOMER_ID_FIELD = "XREF-CUST-ID";

    /**
     * The copybook field name of the cross-reference's account identifier.
     */
    private static final String XREF_ACCOUNT_ID_FIELD = "XREF-ACCT-ID";

    /**
     * The copybook name of the trailing pad that carries the record to its declared length.
     */
    private static final String PADDING_FIELD = "FILLER";

    /**
     * The one-based first byte of the trailing pad, three hundred and thirty-three.
     */
    private static final int PAD_FIRST_BYTE = 333;

    /**
     * The zero-based start of the first protected identifier field, two hundred and seventy-nine.
     *
     * <p>Assumptions: the two protected fields are addressed by OFFSET here, and no assertion in this
     * class names either of them, which is the same indirection the projection's own definition
     * practises for the same reason: {@code data-migration/tests/test_reporting_views.py} asserts
     * that neither identifier appears anywhere in {@code data-migration/sql/V1__reporting_views.sql},
     * comments included, so that no protected column can be uncommented into a projection later.
     * Writing either name into an assertion to explain its own absence would weaken that discipline
     * one file away, so every assertion takes the name from the registered descriptor at run time
     * instead. The one place a name appears at all is the fixture load's column list, because both
     * target columns are declared not null in the base table and no row can be inserted without
     * them. This offset is the descriptor's zero-based start, which is one-based bytes 280 to
     * 288.</p>
     */
    private static final int NATIONAL_IDENTIFIER_START = 279;

    /**
     * The zero-based start of the second protected identifier field, two hundred and eighty-eight.
     *
     * <p>Assumptions: one-based bytes 289 to 308, addressed by offset for the reason recorded on the
     * constant above.</p>
     */
    private static final int GOVERNMENT_IDENTIFIER_START = 288;

    /**
     * The prefix every field name of this record carries, stripped when deriving a column token.
     */
    private static final String CUSTOMER_FIELD_PREFIX = "CUST-";

    /**
     * The identities the customer fixture publishes, in fixture order.
     *
     * <p>Assumptions: these four are asserted against the fixture as well as against the lookup, so
     * a fixture edit cannot silently weaken a resolution assertion into one over fewer rows. Two of
     * them exist in neither reference extract, which the fixture's own README records; that is a
     * property of a reduced corpus and not of this class.</p>
     */
    private static final List<Long> PUBLISHED_IDENTITIES = List.of(7L, 50L, 101L, 102L);

    /**
     * The number of distinct cards the statement-path cross-reference fixture publishes.
     */
    private static final int PUBLISHED_CARD_COUNT = 88;

    /**
     * An identifier no fixture row carries, used to provoke the missing-dimension refusal.
     *
     * <p>Assumptions: nine digits, so it is a WELL-FORMED key rather than a malformed one -- the
     * refusal under test is a referential-integrity failure and not a validation failure, and a key
     * of the wrong width would provoke the wrong one. It appears in neither
     * {@code fixtures/custfile.txt} nor {@code fixtures/xreffile.txt}, and a case below asserts that
     * absence rather than assuming it, because the whole refusal case is vacuous if the identifier
     * ever became present.</p>
     */
    private static final long ABSENT_IDENTIFIER = 999_999_999L;

    /**
     * Leading fragments of published identifiers, none of which is itself a published identifier.
     *
     * <p>Assumptions: each is a strict leading fragment of one of the four published identities --
     * 1 and 10 of both 101 and 102, and 5 of 50 -- so under a partial-key browse each would position
     * the read on the row it prefixes and return it. Under the whole-key read the reference actually
     * performs, each resolves nothing. That difference is the observable consequence of
     * {@code ACCESS MODE IS RANDOM} at {@code app/cbl/CBSTM03B.CBL} L45, and it is the reason a case
     * below probes with these values rather than merely restating the declaration.</p>
     */
    private static final List<Long> LEADING_FRAGMENTS_OF_PUBLISHED_IDENTITIES = List.of(1L, 10L, 5L);

    /**
     * Absent identifiers that a greater-or-equal browse would answer with a different row.
     *
     * <p>Assumptions: 8 sits between 7 and 50, 51 between 50 and 101, and 100 between 51 and 101, so
     * a browse positioned at the first key not less than the argument would return 50, 101 and 101
     * respectively -- three wrong rows, each belonging to a different cardholder. The keyed read
     * returns nothing for all three. Alternatives Considered: probing with one such value only.
     * Rejected because a single probe cannot distinguish a whole-key read from a browse that
     * happened to find nothing greater, whereas a value with a known successor can.</p>
     */
    private static final List<Long> KEYS_A_BROWSE_WOULD_ANSWER_WRONGLY = List.of(8L, 51L, 100L);

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
     * The simple type names a traversal, a window or an ordering argument would appear under.
     *
     * <p>Assumptions: the absence is asserted by SIMPLE NAME rather than by importing each type,
     * because importing a paging type into a class whose point is that no paging type belongs here
     * would put the very coupling under test into the imports. The set covers both directions of a
     * method signature -- a return type carrying many rows and a parameter type carrying a window or
     * an ordering -- since either alone would turn a single-row lookup into something else.</p>
     */
    private static final Set<String> TRAVERSAL_TYPE_NAMES = Set.of(
            "Stream", "Iterable", "Collection", "List", "Set", "Streamable",
            "Page", "Slice", "Window", "Pageable", "Sort", "Limit", "ScrollPosition");

    /**
     * The exact set of methods the interface under test declares.
     *
     * <p>Assumptions: naming the whole set is stronger than asserting an absence, because it fails
     * on a method removed as well as on one added. One entry is the whole set here, and that is the
     * finding rather than an accident of drafting: the {@code 'CUSTFILE'} branch at
     * {@code app/cbl/CBSTM03B.CBL} L181 to L204 supports an open, a keyed read and a close, and of
     * those three only the read has a counterpart in a repository role -- a connection pool owns the
     * other two.</p>
     */
    private static final Set<String> DECLARED_READ_METHOD_NAMES = Set.of("findById");

    /**
     * The statement job whose four input definitions the statement path reads.
     */
    private static final String STATEMENT_JOB = "app/jcl/CREASTMT.JCL";

    /**
     * The report job whose input definitions include no customer master.
     */
    private static final String REPORT_JOB = "app/jcl/TRANREPT.jcl";

    /**
     * The subprogram owning all four statement-path file definitions and their operation codes.
     */
    private static final String FILE_ACCESS_PROGRAM = "app/cbl/CBSTM03B.CBL";

    /**
     * The statement generator that calls that subprogram once per cross-reference row.
     */
    private static final String STATEMENT_PROGRAM = "app/cbl/CBSTM03A.CBL";

    /**
     * The one-based line of the statement job declaring the transaction input, eighty-three.
     */
    private static final int TRANSACTION_DEFINITION_LINE = 83;

    /**
     * The one-based line of the statement job declaring the cross-reference input, eighty-four.
     */
    private static final int CROSS_REFERENCE_DEFINITION_LINE = 84;

    /**
     * The one-based line of the statement job declaring the account input, eighty-five.
     *
     * <p>Assumptions: the account definition precedes the customer definition, and the order is
     * asserted rather than assumed because the two are adjacent and a citation that swapped them
     * would attribute this class's whole subject to the wrong input. A case below reads the job and
     * checks both lines.</p>
     */
    private static final int ACCOUNT_DEFINITION_LINE = 85;

    /**
     * The one-based line of the statement job declaring the customer input, eighty-six.
     */
    private static final int CUSTOMER_DEFINITION_LINE = 86;

    /**
     * The one-based line declaring the customer file's access mode, forty-five.
     */
    private static final int RANDOM_ACCESS_MODE_LINE = 45;

    /**
     * The one-based line of the reference-modified move that supplies the key, one hundred and
     * eighty-nine.
     */
    private static final int KEYED_MOVE_LINE = 189;

    /**
     * The one-based line of the plain keyed read, one hundred and ninety.
     */
    private static final int KEYED_READ_LINE = 190;

    /**
     * The one-based line that zeroes the key-length field transiently, three hundred and
     * seventy-three.
     */
    private static final int TRANSIENT_ZERO_LINE = 373;

    /**
     * The one-based line that recomputes the key length as the whole key's length, three hundred and
     * seventy-four.
     */
    private static final int WHOLE_KEY_LENGTH_LINE = 374;

    /**
     * The one-based first line of the customer read's status evaluation, three hundred and
     * seventy-nine.
     */
    private static final int CUSTOMER_EVALUATION_FIRST_LINE = 379;

    /**
     * The one-based last line of the customer read's status evaluation, three hundred and
     * eighty-six.
     */
    private static final int CUSTOMER_EVALUATION_LAST_LINE = 386;

    /**
     * The one-based line carrying the cross-reference read's end-of-file arm, three hundred and
     * fifty-six.
     */
    private static final int CROSS_REFERENCE_END_OF_FILE_LINE = 356;

    /**
     * The end-of-file status the cross-reference read tolerates and the customer read does not.
     */
    private static final String END_OF_FILE_STATUS = "WHEN '10'";

    /**
     * The data-definition name the reference displays when the customer read fails.
     *
     * <p>Assumptions: the caller's refusal names this definition rather than a relation name, which
     * is what makes the failure readable against the reference's own display at
     * {@code app/cbl/CBSTM03A.CBL} L383. The production caller does the same, at L1062 of
     * {@code StatementService} in this module's {@code service} package.</p>
     */
    private static final String CUSTOMER_DEFINITION_NAME = "CUSTFILE";

    /**
     * The display text the reference's abend paragraph emits, carried across unchanged.
     */
    private static final String ABEND_MESSAGE = "ABENDING PROGRAM";

    /**
     * The placeholder written into the two enciphered columns the fixture load must populate.
     *
     * <p>Assumptions: both target columns are byte arrays and the first of them is declared not null
     * in the base table, so the load has to supply something; the projection under test selects
     * neither column -- which is the property a case below asserts -- so what the load supplies
     * cannot affect any assertion here. The second is nullable and receives the same constant anyway,
     * so the two rows of the load read alike and neither invites a reader to wonder which column the
     * asymmetry belonged to. A fabricated constant is used rather than a real enciphering, because
     * reaching for the owning service's cipher would couple this class to a component it does not
     * exercise and would make a cipher change read as a lookup defect. No value of either protected
     * field is decoded from the fixture, inserted, asserted on or written anywhere in this class.</p>
     */
    private static final byte[] PLACEHOLDER_CIPHERTEXT =
            "not-real-ciphertext".getBytes(StandardCharsets.US_ASCII);

    /**
     * Every statement the persistence provider generated, in the order it generated them.
     *
     * <p>Assumptions: the list is static because the inspector the provider instantiates is not a
     * bean this class holds a reference to, and it is copy-on-write because the provider may issue a
     * statement from any thread the pool hands it. Alternatives Considered: raising the provider's
     * statement log level and reading the captured output. Rejected because a log format is not a
     * contract, and a level raised for one case would additionally publish every bound parameter of
     * every other case -- which is the one thing the profile's own logging register pins those
     * categories down to prevent.</p>
     */
    private static final List<String> GENERATED_STATEMENTS = new CopyOnWriteArrayList<>();

    /**
     * The container the context connects to, started once for this class.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text, because the container's port is assigned as it starts -- so committed text could
     * not be correct, and the failure mode of the mistake is not an error but a class that passes
     * against whichever engine happened to be listening. {@code application-test.yml} declares no
     * location, no login name and no credential precisely so that these arrive this way.</p>
     *
     * <p>Assumptions: the declaration carries no type argument, and the omission is deliberate rather
     * than an unparameterised generic. The container type in the 2.x package this module depends on
     * is NOT generic -- it declares its own self-referential bound internally -- so a wildcard
     * argument would not compile at all. The two sibling classes here declare it the same way.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The lookup under test, injected so every read goes through the declared repository role.
     */
    @Autowired
    private StatementCustomerRepository customers;

    /**
     * The entity manager, used only to read the catalogue and the identifiers a case compares
     * against.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Applies the shipped definitions and loads the fixture corpus before the context connects.
     *
     * <p>Assumptions: the container is already running when this runs, and that is a framework
     * guarantee rather than an assumption about extension ordering -- every {@code BeforeAllCallback}
     * extension is invoked before a {@code @BeforeAll} method, so the container annotation's
     * extension has started it whichever order the two class-level extensions were registered in.
     * The Spring context, by contrast, is built when the first test instance is prepared, which is
     * after this method, so the relations and the rows exist before the pool opens its first
     * connection.</p>
     *
     * <p>Assumptions: nothing here needs to precede the pool for correctness in any case, and the
     * reason is worth recording so a future edit does not treat the ordering as fragile.
     * {@code application-test.yml} pins schema resolution to the single {@code reporting} schema, and
     * a search-path entry naming a schema that does not yet exist is accepted and simply resolves
     * nothing until it does. Its {@code ddl-auto} is {@code none}, so the provider emits no
     * schema-generation statement that could fabricate the very relation this class must not
     * create.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @BeforeAll
    static void arrangeTheDeployedSchemaAndFixtureCorpus() {
        applyDeployedSchema();
        loadFixtureCorpus();
    }

    /**
     * Confirms the role exposes one keyed read and neither a traversal nor a write.
     *
     * <p>Assumptions: the surface is asserted as a WHOLE -- every method reachable on the type,
     * inherited members included -- because the failure mode being guarded against is inheriting a
     * wider surface rather than declaring one. Register entry <b>R4</b> and the main-tree charter
     * both turn on the base being the bare marker interface: a wider base would inherit write methods
     * onto a type whose entire contract is that it has none, and they would compile, appear in every
     * completion list, and fail only at the database.</p>
     *
     * <p>Assumptions: the reference supports the two absences separately, and they are asserted
     * separately for that reason. The absence of a traversal comes from the dispatcher's
     * per-definition asymmetry: the {@code 'CUSTFILE'} branch at {@code app/cbl/CBSTM03B.CBL} L181 to
     * L204 implements a keyed read and no plain read, while the {@code 'TRNXFILE'} branch at L133 to
     * L155 and the {@code 'XREFFILE'} branch at L157 to L179 implement a plain read and no keyed
     * read, over files declared {@code SEQUENTIAL} at L33 and L39. Two ordered-scan definitions and
     * two keyed-lookup definitions therefore become two kinds of repository role, and they must not
     * share one interface. The absence of a write comes from the unreferenced operation codes.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the role exposes one keyed read, with no traversal, no window and no write")
    void theRoleExposesOneKeyedReadWithNoTraversalAndNoWrite() {
        // WHY : Assumptions: app/cbl/CBSTM03B.CBL dispatches on data-definition name FIRST, at its
        //       L118, and only then on operation code, so support is per definition rather than
        //       uniform. The 'CUSTFILE' branch at L181 to L204 offers an open at L184, a keyed read
        //       at L189 and L190 and a close at L196; of those three only the read is a repository
        //       concern, which is why one method is the whole set rather than a starting point.
        List<Method> reachable = List.of(StatementCustomerRepository.class.getMethods());
        Set<String> reachableNames = new LinkedHashSet<>();
        for (Method method : reachable) {
            reachableNames.add(method.getName());
        }

        assertThat(reachableNames)
                .withFailMessage("the reachable surface must be exactly the keyed read the"
                        + " 'CUSTFILE' branch of %s supports; anything else is a member the reference"
                        + " has no operation code for", FILE_ACCESS_PROGRAM)
                .isEqualTo(DECLARED_READ_METHOD_NAMES);
        assertThat(reachable)
                .as("one name is reached by exactly one method, so no overload widens the surface")
                .hasSize(1);
        assertThat(StatementCustomerRepository.class.getInterfaces())
                .as("the base is the marker interface, which declares nothing")
                .containsExactly(Repository.class);
        assertThat(reachable.getFirst().getReturnType())
                .as("the keyed read yields at most one row, expressed as an optional")
                .isEqualTo(Optional.class);

        // WHY : Assumptions: the callee body at app/cbl/CBSTM03B.CBL L188 to L193 is a move followed
        //       by a plain READ, with no positioning verb and no read-next, so there is no cursor
        //       here for a stream, a page or a sort argument to express. Register entry R1 records
        //       that keyset positioning's provenance is app/cbl/COCRDLIC.cbl L230 to L244 and belongs
        //       to the report-path role, so a paging member appearing on THIS role would be
        //       positioning justified from the wrong opcode.
        for (Method method : reachable) {
            assertThat(method.getReturnType().getSimpleName())
                    .withFailMessage("%s must not yield a traversal or a window; this role replaces a"
                            + " single-row keyed read, not a browse", method.getName())
                    .isNotIn(TRAVERSAL_TYPE_NAMES);
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat(parameter.getSimpleName())
                        .withFailMessage("%s must accept no window and no ordering argument; the"
                                + " reference supplies a whole key and nothing else, at %s L372 to"
                                + " L374", method.getName(), STATEMENT_PROGRAM)
                        .isNotIn(TRAVERSAL_TYPE_NAMES);
            }
        }

        // WHY : Assumptions: an unexercised operation code is indistinguishable from an unsupported
        //       one from the caller's side. app/cbl/CBSTM03B.CBL declares write and rewrite codes at
        //       L107 and L108, mirrored at app/cbl/CBSTM03A.CBL L78 and L79, and a census of those
        //       two condition names across the whole reference returns exactly those four
        //       declarations with no reference to either, while a search of the caller for the
        //       statement that selects a code yields only open, close, read and keyed read.
        assertThat(reachable.stream()
                        .map(Method::getName)
                        .filter(WRITE_METHOD_NAMES::contains)
                        .toList())
                .as("no write operation appears anywhere on the interface's method surface")
                .isEmpty();
    }

    /**
     * Confirms the projection is read-only at the mapping layer.
     *
     * <p>Assumptions: this is the mapping half of the posture only, and the database half is
     * deliberately not repeated here. {@code StatementCardXrefRepositoryIT} owns the two-level
     * refusal -- a structural guard at the mapping and a privilege guard at the engine -- and
     * duplicating its privilege case would give one decision two homes. What this case adds is that
     * the customer projection carries the same three structural properties: the immutability marker,
     * so it is excluded from dirty checking; no version member, so no optimistic write path exists;
     * and every mapped column declared non-updatable, so no update statement can be generated for
     * it.</p>
     *
     * <p>Alternatives Considered: probing the behaviour instead, by mutating a loaded instance and
     * asserting the row is unchanged. Rejected because an immutable entity has its updates silently
     * DISCARDED rather than refused, and the read-only pool this profile inherits would produce an
     * unchanged row on its own -- so the assertion would hold for a reason unrelated to the mapping
     * it claims to check.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the customer projection is immutable, unversioned and wholly non-updatable")
    void theCustomerProjectionIsImmutableUnversionedAndWhollyNonUpdatable() {
        // WHY : Assumptions: the reference reaches this file through one operation code only. The
        //       three call sites in app/cbl/CBSTM03A.CBL for this definition are the open at its
        //       L787, the keyed read at L377 and the close at L893, and no site anywhere selects the
        //       write or rewrite code declared at app/cbl/CBSTM03B.CBL L107 and L108. A mapping that
        //       could generate an update would therefore express an operation the reference has no
        //       code path for.
        assertThat(CustomerView.class.getAnnotation(Immutable.class))
                .as("the projection carries the immutability marker, so it is not dirty checked")
                .isNotNull();

        List<Field> mapped = new ArrayList<>();
        for (Field field : CustomerView.class.getDeclaredFields()) {
            assertThat(field.isAnnotationPresent(Version.class))
                    .withFailMessage("the projection must declare no version member; %s is one, and"
                            + " an optimistic write path is still a write path", field.getName())
                    .isFalse();
            if (field.isAnnotationPresent(Column.class)) {
                mapped.add(field);
            }
        }

        assertThat(mapped)
                .as("the projection maps at least the columns a statement heading is built from")
                .isNotEmpty();
        for (Field field : mapped) {
            assertThat(field.getAnnotation(Column.class).updatable())
                    .withFailMessage("every mapped column of the projection must be declared"
                            + " non-updatable; %s is not, so the provider could generate an update"
                            + " for it", field.getName())
                    .isFalse();
        }
    }

    /**
     * Confirms the statement path declares the customer input where this class cites it, and the
     * report path declares none.
     *
     * <p>Assumptions: a citation that is only quoted can drift into unrelated text as the file it
     * names is edited, and a reader who follows one and finds something else concludes the claim is
     * wrong rather than the pointer. Every line-numbered claim this class makes about the statement
     * job is therefore checked against the job itself, which converts the citations from prose into
     * assertions. The four inputs are asserted IN ORDER because the account and the customer
     * definitions are adjacent, and a citation that transposed them would attribute this class's whole
     * subject to the wrong input.</p>
     *
     * <p>Assumptions: the report path is asserted to declare NEITHER master, because the two
     * four-way joins are routinely conflated and they share only the transaction store and the
     * cross-reference. The report writer's inputs are the transaction store at
     * {@code app/jcl/TRANREPT.jcl} L65, the cross-reference at L67, two reference stores at L69 and
     * L71 and a date parameter at L73 -- so no report band reads a customer row at all, and a keyed
     * customer lookup placed on the report path would be a read that path never performs.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IOException if either job cannot be read from the working tree, which is an arrangement
     *     failure rather than a property of the migration
     */
    @Test
    @DisplayName("the statement job declares the customer input at the cited line and the report job"
            + " declares none")
    void theStatementJobDeclaresTheCustomerInputAndTheReportJobDeclaresNone() throws IOException {
        // WHY : Assumptions: the job is read as single-byte text rather than as a decoded character
        //       stream, because these are fixed-column mainframe artifacts and a decoder that
        //       replaced an unexpected byte would silently alter a line this assertion compares. The
        //       four files are read-only reference in this migration and are never modified.
        List<String> statementJob = jobLines(STATEMENT_JOB);

        assertThat(statementJob.get(TRANSACTION_DEFINITION_LINE - 1))
                .as("the transaction input stands where %s L%d is cited",
                        STATEMENT_JOB, TRANSACTION_DEFINITION_LINE)
                .startsWith("//TRNXFILE DD");
        assertThat(statementJob.get(CROSS_REFERENCE_DEFINITION_LINE - 1))
                .as("the cross-reference input stands where %s L%d is cited",
                        STATEMENT_JOB, CROSS_REFERENCE_DEFINITION_LINE)
                .startsWith("//XREFFILE DD");
        assertThat(statementJob.get(ACCOUNT_DEFINITION_LINE - 1))
                .withFailMessage("%s L%d must declare the ACCOUNT input; the customer input is the"
                        + " line after it, and a citation that swapped the two would attribute this"
                        + " class's subject to the wrong definition",
                        STATEMENT_JOB, ACCOUNT_DEFINITION_LINE)
                .startsWith("//ACCTFILE DD");
        assertThat(statementJob.get(CUSTOMER_DEFINITION_LINE - 1))
                .withFailMessage("%s L%d must declare the customer input, which is the definition"
                        + " this whole class stands in for", STATEMENT_JOB, CUSTOMER_DEFINITION_LINE)
                .startsWith("//" + CUSTOMER_DEFINITION_NAME + " DD");

        // WHY : Assumptions: the absence is what keeps the statement four-way join and the report
        //       four-way join distinct. app/cbl/CBSTM03A.CBL reads the transaction store, the
        //       cross-reference, the account master and the customer master, while the report job's
        //       inputs at app/jcl/TRANREPT.jcl L65 to L73 name the transaction store, the
        //       cross-reference and two reference stores; the intersection is two inputs, not four.
        assertThat(jobLines(REPORT_JOB))
                .withFailMessage("%s must declare no customer and no account input; the report path"
                        + " reads neither master, and conflating the two joins would put a keyed"
                        + " customer read on a path that performs none", REPORT_JOB)
                .noneMatch(line -> line.startsWith("//" + CUSTOMER_DEFINITION_NAME)
                        || line.startsWith("//ACCTFILE"));
    }

    /**
     * Confirms the customer record is five hundred bytes with its trailing pad dropped and no money
     * field.
     *
     * <p>Assumptions: the total is derived by summing declared field widths and is corroborated
     * against the file definition, never taken from a comment. The registered descriptor is the one
     * place those offsets are declared, and it is transcribed from
     * {@code app/cpy/CVCUS01Y.cpy}, whose {@code 01 CUSTOMER-RECORD.} stands at L4. That copybook has
     * a near-duplicate sibling in the same directory which differs only in one field's spelling and a
     * version stamp; the descriptor's field names follow this one, so this one is the canonical
     * layout and the sibling is never the source.</p>
     *
     * <p>Assumptions: the pad is asserted absent from the projection because it exists only to reach
     * the declared length, so a mapped member for it would publish padding as data. Transformation
     * rule T1 of the migration plan requires the drop to be RECORDED per record, and the descriptor is
     * where that record lives -- this case asserts the projection honours it.</p>
     *
     * <p>Assumptions: the record carries NO monetary field, which is asserted rather than assumed
     * because the assertion is what keeps a future edit from introducing one silently. Every field of
     * this record is character or unsigned-integral, none is signed, and consequently no
     * zoned-decimal sign overpunch appears anywhere in it -- unlike the account master, whose
     * balances are signed display fields. Nothing in this class therefore handles money, and no
     * binary floating-point type appears in it at all.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the customer record is five hundred bytes with its trailing pad dropped")
    void theCustomerRecordIsFiveHundredBytesWithItsTrailingPadDropped() {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(CUSTOMER_DESCRIPTOR);
        CopybookLayout.FieldSpec key = spec.field(CUSTOMER_ID_FIELD);
        CopybookLayout.FieldSpec pad = spec.field(PADDING_FIELD);

        // WHY : Assumptions: app/cbl/CBSTM03B.CBL L70 to L73 declares FD-CUST-ID as nine bytes and
        //       FD-CUST-DATA as 491, and the two sum to the same 500 the copybook's fields sum to.
        //       Two independent statements of one number are what make the total a fact rather than a
        //       banner comment, which is why the arithmetic is written out here instead of a literal.
        assertThat(spec.reclen())
                .as("the declared record length")
                .isEqualTo(CUSTOMER_RECORD_LENGTH);
        assertThat(FILE_DEFINITION_KEY_WIDTH + FILE_DEFINITION_DATA_WIDTH)
                .as("the file definition's key and remainder sum to the same declared length")
                .isEqualTo(CUSTOMER_RECORD_LENGTH);

        // WHY : Assumptions: the descriptor records a zero-based start while the copybook and every
        //       citation in this repository count bytes from one, so the two differ by exactly one and
        //       an assertion written in the descriptor's frame would read as though it disagreed with
        //       app/cpy/CVCUS01Y.cpy L5. Converting here keeps the assertion legible against the
        //       copybook without changing what is checked.
        assertThat(key.start() + 1)
                .as("the key begins at the first byte of the record")
                .isEqualTo(1);
        assertThat(key.length())
                .as("the key occupies its nine declared digits")
                .isEqualTo(CUSTOMER_KEY_DIGITS);
        assertThat(spec.keyLength())
                .as("the descriptor's declared key length is the same nine digits")
                .isEqualTo(CUSTOMER_KEY_DIGITS);

        assertThat(pad.start() + 1)
                .as("the trailing pad begins at its one-based first byte")
                .isEqualTo(PAD_FIRST_BYTE);
        assertThat(pad.start() + pad.length())
                .as("the trailing pad ends at the last byte of the record")
                .isEqualTo(CUSTOMER_RECORD_LENGTH);
        assertThat(mappedColumnNames())
                .withFailMessage("the projection must carry no member for the trailing pad; the pad"
                        + " exists only to reach the five-hundred-byte declared length, so a member"
                        + " for it would publish padding as data")
                .noneMatch(column -> column.contains(PADDING_FIELD.toLowerCase(Locale.ROOT)));

        // WHY : Assumptions: transformation rule T3 of the migration plan requires every value in the
        //       money path to stay exact fixed point, and the cheapest way to keep this class outside
        //       that path is to establish that the record has no money in it at all. The account
        //       master's balances are signed display fields; this record's fields are character and
        //       unsigned-integral only, so a decoder reading it needs no sign-overpunch handling and
        //       an assertion here needs no decimal type.
        for (CopybookLayout.FieldSpec field : spec.fields()) {
            assertThat(field.kind())
                    .withFailMessage("%s must be neither zoned nor packed; a monetary field in this"
                            + " record would put this class in the money path, where an exact"
                            + " fixed-point type is required end to end", field.name())
                    .isNotIn(CopybookLayout.Kind.ZONED, CopybookLayout.Kind.PACKED);
            assertThat(field.signed())
                    .withFailMessage("%s must carry no sign, because a sign overpunch is the one"
                            + " thing this record's decoding would otherwise have to handle",
                            field.name())
                    .isFalse();
        }
    }

    /**
     * Confirms the three pieces of evidence for a whole-key read stand where they are cited.
     *
     * <p>Assumptions: this case asserts nothing about the migrated code and everything about the
     * citations the migrated code rests on, which is why it is separate from the probe case beside it.
     * The finding that opcode {@code 'K'} is an exact whole-key read is register entry <b>R3</b>, and
     * this directory is where it is evidenced; an evidence claim whose line numbers have drifted is
     * worse than none, because a reader who follows one and finds unrelated text concludes the finding
     * is wrong rather than the pointer.</p>
     *
     * <p>Assumptions: the fourth check is an absence, and it is the one that generalises. A
     * repository-wide search for a zero-length reference modification finds nothing, so the baseline
     * contains no partial-key read anywhere -- not on this definition and not on any other -- which is
     * what makes the whole-key reading of the length field the only reading the reference supports.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IOException if either program cannot be read from the working tree, which is an
     *     arrangement failure rather than a property of the migration
     */
    @Test
    @DisplayName("the reference declares random access, reads plainly by whole key and computes that"
            + " key's whole length")
    void theReferenceDeclaresRandomAccessAndComputesTheWholeKeyLength() throws IOException {
        List<String> accessProgram = jobLines(FILE_ACCESS_PROGRAM);
        List<String> statementProgram = jobLines(STATEMENT_PROGRAM);

        // WHY : Assumptions: a COBOL indexed file declared ACCESS MODE IS RANDOM cannot be browsed at
        //       all, so this one line rules out a positioning read before any body is examined. The
        //       two ordered-scan definitions in the same program are declared SEQUENTIAL at L33 and
        //       L39, so the declaration is per file and not a property of the program.
        assertThat(accessProgram.get(RANDOM_ACCESS_MODE_LINE - 1))
                .as("%s L%d declares the customer file's access mode",
                        FILE_ACCESS_PROGRAM, RANDOM_ACCESS_MODE_LINE)
                .contains("ACCESS MODE")
                .contains("IS RANDOM");

        // WHY : Assumptions: the absence of a positioning verb is the evidence, so the two lines are
        //       asserted for what they DO contain and the arm is asserted for what it does not. A
        //       greater-or-equal qualifier, a start verb or a read-next anywhere in this arm would
        //       make the same two statements a browse rather than a keyed read.
        assertThat(accessProgram.get(KEYED_MOVE_LINE - 1))
                .as("%s L%d moves the supplied key into the record key",
                        FILE_ACCESS_PROGRAM, KEYED_MOVE_LINE)
                .contains("LK-M03B-KEY (1:LK-M03B-KEY-LN)")
                .contains("FD-CUST-ID");
        assertThat(accessProgram.get(KEYED_READ_LINE - 1))
                .as("%s L%d reads the file plainly, with no qualifier",
                        FILE_ACCESS_PROGRAM, KEYED_READ_LINE)
                .contains("READ CUST-FILE INTO")
                .doesNotContain("KEY IS GREATER")
                .doesNotContain("NEXT");

        // WHY : Assumptions: the transient zero at L373 is the single most misleading line on this
        //       path, because read alone it suggests a zero-length -- that is, empty -- key. L374
        //       overwrites it immediately with LENGTH OF XREF-CUST-ID, which app/cpy/CVACT03Y.cpy L6
        //       declares as nine digits, so the value the callee reads is always the whole key and
        //       never the zero. Both lines are asserted together for that reason.
        assertThat(statementProgram.get(TRANSIENT_ZERO_LINE - 1))
                .as("%s L%d zeroes the key-length field transiently",
                        STATEMENT_PROGRAM, TRANSIENT_ZERO_LINE)
                .contains("MOVE ZERO TO WS-M03B-KEY-LN");
        assertThat(statementProgram.get(WHOLE_KEY_LENGTH_LINE - 1))
                .withFailMessage("%s L%d must recompute the key length as the WHOLE key's length;"
                        + " without it the transient zero at L%d would be the value the callee reads,"
                        + " and the opcode would be a partial-key read",
                        STATEMENT_PROGRAM, WHOLE_KEY_LENGTH_LINE, TRANSIENT_ZERO_LINE)
                .contains("COMPUTE WS-M03B-KEY-LN")
                .contains("LENGTH OF " + XREF_CUSTOMER_ID_FIELD);

        // WHY : Assumptions: corroboration by absence is what stops the whole-key reading from resting
        //       on one path. The two programs on this path are the ones a reader
        //       would generalise from, so their absence of the construct is the assertion; a partial
        //       key of length zero appears in neither, and a repository-wide search finds it in no
        //       other file either.
        assertThat(accessProgram)
                .as("%s contains no zero-length reference modification", FILE_ACCESS_PROGRAM)
                .noneMatch(line -> line.contains("(1:0)"));
        assertThat(statementProgram)
                .as("%s contains no zero-length reference modification", STATEMENT_PROGRAM)
                .noneMatch(line -> line.contains("(1:0)"));
    }

    /**
     * Confirms a whole identifier resolves while nothing shorter and nothing merely nearby does.
     *
     * <p>Assumptions: this is the observable consequence of the whole-key read, and it is the property
     * no cheaper gate in this module can see. A unit test against a stubbed repository answers
     * whatever it was arranged to answer, and a parsing gate never executes a query at all, so only a
     * real engine holding real rows can distinguish an exact match from a positioning read. Both
     * sibling classes that touch a customer identifier ask whether a PRESENT identifier resolves; this
     * case asks what a nearly-right one does, which is where the two shapes differ.</p>
     *
     * <p>Assumptions: three families of argument are probed and each rules out a different shape. A
     * whole identifier must resolve, or the case proves nothing about the others. A strict leading
     * fragment must resolve nothing, which rules out prefix positioning. An absent identifier with a
     * known successor must resolve nothing, which rules out a greater-or-equal browse -- and that one
     * matters most, because a browse would return a row belonging to a DIFFERENT cardholder rather
     * than no row, so the statement would be built from the wrong customer and nothing would
     * fail.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the whole identifier resolves and neither a leading fragment nor a nearby key does")
    void theWholeIdentifierResolvesAndNeitherAFragmentNorANearbyKeyDoes() {
        // WHY : Assumptions: the reference supplies exactly this value and no other, at
        //       app/cbl/CBSTM03A.CBL L372, and computes its whole length at L374, so the whole
        //       identifier is the only argument the keyed read at app/cbl/CBSTM03B.CBL L189 and L190
        //       is ever given. A published identity that failed to resolve would mean the projection
        //       or the load, not the key semantics, so this is the control the two negative families
        //       below are read against. Trade-offs: each of the three loops is indexed so its failure
        //       text can name the case by ordinal and carry no key material into the build log, for the
        //       reason this class's own documentation records.
        for (int ordinal = 1; ordinal <= PUBLISHED_IDENTITIES.size(); ordinal++) {
            assertThat(customers.findById(PUBLISHED_IDENTITIES.get(ordinal - 1)))
                    .withFailMessage("published identity %d of %d must resolve; every value reaching"
                            + " this lookup comes from a cross-reference row that exists",
                            ordinal, PUBLISHED_IDENTITIES.size())
                    .isPresent();
        }

        // WHY : Assumptions: under a partial-key browse each of these would position the read on the
        //       row it prefixes and return it, which is precisely the reading of LK-M03B-KEY-LN --
        //       declared PIC S9(4) at app/cbl/CBSTM03B.CBL L111 -- that this case exists to refute.
        //       Register entry R3 records the finding; this is where the engine confirms it.
        for (int ordinal = 1; ordinal <= LEADING_FRAGMENTS_OF_PUBLISHED_IDENTITIES.size(); ordinal++) {
            assertThat(customers.findById(
                            LEADING_FRAGMENTS_OF_PUBLISHED_IDENTITIES.get(ordinal - 1)))
                    .withFailMessage("leading fragment %d of %d must resolve NOTHING; if it resolves,"
                            + " the read is positioning by prefix rather than matching a whole key,"
                            + " and register entry R3 is wrong about %s L%d",
                            ordinal, LEADING_FRAGMENTS_OF_PUBLISHED_IDENTITIES.size(),
                            FILE_ACCESS_PROGRAM, RANDOM_ACCESS_MODE_LINE)
                    .isEmpty();
        }

        // WHY : Assumptions: a greater-or-equal browse would answer each of these with the next
        //       published identity -- a row belonging to a different cardholder -- and the run would
        //       continue and produce a statement addressed to the wrong person, with nothing raised
        //       anywhere. That silent-wrong-row outcome, rather than a missing row, is what makes this
        //       family the sharper probe of the two.
        for (int ordinal = 1; ordinal <= KEYS_A_BROWSE_WOULD_ANSWER_WRONGLY.size(); ordinal++) {
            assertThat(customers.findById(KEYS_A_BROWSE_WOULD_ANSWER_WRONGLY.get(ordinal - 1)))
                    .withFailMessage("nearby absent key %d of %d must resolve NOTHING; a"
                            + " greater-or-equal browse would answer it with the next published"
                            + " identity, and a statement would then be built from another"
                            + " cardholder's row", ordinal, KEYS_A_BROWSE_WOULD_ANSWER_WRONGLY.size())
                    .isEmpty();
        }
    }

    /**
     * Confirms every identifier the driving cursor supplies resolves through this role.
     *
     * <p>Assumptions: the identifiers are taken from the committed cross-reference fixture and
     * resolved THROUGH the role under test, which is what distinguishes this case from the sibling
     * cursor class's own resolution check. That one reads a set of published identifiers with its own
     * query and asserts membership; this one exercises the keyed read for every row the run would
     * perform it for, so the mapping, the projection and the lookup are all in the path.</p>
     *
     * <p>Assumptions: the reference performs this read once per cross-reference row rather than once
     * per distinct customer -- {@code 2000-CUSTFILE-GET.} at {@code app/cbl/CBSTM03A.CBL} L368 is
     * called from the mainline loop with no memory of the previous row -- so the probe follows the
     * fixture's rows rather than its distinct identifiers, and the count is the card count.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("every cross-reference row's customer identifier resolves through the keyed read")
    void everyCrossReferenceRowsCustomerIdentifierResolvesThroughTheKeyedRead() {
        List<Map<String, Object>> crossReferenceRows =
                decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR);

        assertThat(crossReferenceRows)
                .as("the committed cross-reference fixture publishes the expected number of cards")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(publishedIdentitiesOfFixture())
                .as("the committed customer fixture publishes exactly the expected identities")
                .containsExactlyElementsOf(PUBLISHED_IDENTITIES);

        // WHY : Assumptions: an identifier that the cross-reference names and the customer relation
        //       cannot answer is the abort condition, not a row to skip: the customer read's
        //       evaluation at app/cbl/CBSTM03A.CBL L379 to L386 has no end-of-file arm and reaches the
        //       abend paragraph at L921. Asserting resolution per ROW rather than per distinct
        //       identifier is what makes this case fail on the same input the run would fail on.
        //       Trade-offs: the loop is indexed so the failure text can name the offending case by its
        //       row position in the committed fixture and carry no key material into the build log, for
        //       the reason this class's own documentation records. The width check reads the key's
        //       LENGTH rather than the key rendered as text for the same reason -- the property under
        //       test is the digit count, and asserting on the text would put the key itself into the
        //       framework's own failure rendering, which is the one place a diagnostic can still carry
        //       one after the message text has been cleaned.
        for (int ordinal = 1; ordinal <= crossReferenceRows.size(); ordinal++) {
            long identity = integral(crossReferenceRows.get(ordinal - 1), XREF_CUSTOMER_ID_FIELD);
            assertThat(customers.findById(identity))
                    .withFailMessage("the cross-reference names the customer of row %d of %d, which"
                            + " the customer projection cannot answer; %s treats that as an abort at"
                            + " L921 rather than as a card to skip",
                            ordinal, crossReferenceRows.size(), STATEMENT_PROGRAM)
                    .isPresent();
            assertThat(String.valueOf(identity).length())
                    .as("the supplied key fits its nine declared digits")
                    .isLessThanOrEqualTo(CUSTOMER_KEY_DIGITS);
        }
    }

    /**
     * Confirms an absent identifier yields an empty answer that no terminal read can turn into a
     * value.
     *
     * <p>Assumptions: the empty answer is a referential-integrity violation and never an end-of-data
     * condition, and register entry <b>R10</b> is the package-wide statement of it. The evidence is an
     * asymmetry inside one program: the cross-reference read at {@code app/cbl/CBSTM03A.CBL} L345 to
     * L366 carries the {@code WHEN '10'} end-of-file arm at L356 and L357, and exhausting it is how a
     * run ends normally; the customer read at L368 to L390 has no such arm at all, so its evaluation at
     * L379 to L386 sends anything but a clean status to {@code PERFORM 9999-ABEND-PROGRAM} at L385,
     * reaching the paragraph at L921 that displays a line at L922 and issues {@code CALL 'CEE3ABD'} at
     * L923. Both halves of that asymmetry are asserted against the program below, because treating the
     * two reads alike is the specific mistake this case exists to prevent: a broken referential chain
     * would then truncate a statement run silently instead of failing it.</p>
     *
     * <p>Assumptions: the captured exception is {@link java.lang.IllegalStateException}, which is the
     * type the production caller's own refusal builder returns at L1700 of
     * {@code StatementService} in this module's {@code service} package, and which it raises from the
     * lookup's empty answer at that file's L1061 to L1063. It is that type
     * rather than a data-access exception because nothing failed at the database -- the read succeeded
     * and found no row -- and rather than a checked exception because the reference has no recovery
     * path to offer a caller: its response is to end the job. The caller is expected to let it
     * propagate, unwinding the read-only transaction the run opened, and to publish no statement for
     * the run at all.</p>
     *
     * <p>Assumptions: the second capture is {@link java.util.NoSuchElementException}, raised by a bare
     * terminal read of the same empty answer. It is asserted so that the refusal cannot be read as a
     * property of one refusal builder: no terminal read of this result yields a value, whichever one a
     * future caller reaches for. Alternatives Considered: letting the lookup return a defaulted
     * customer row, or letting the caller skip the card. Both are rejected because the reference
     * abends rather than continuing, so either would be an undocumented behavioural change -- a
     * statement built from a fabricated cardholder, or a cardholder silently left without a document
     * and no record anywhere of the omission.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IOException if the statement program cannot be read from the working tree, which is an
     *     arrangement failure rather than a property of the migration
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("an absent identifier yields an empty answer that no terminal read turns into a"
            + " value")
    void anAbsentIdentifierYieldsAnEmptyAnswerNoTerminalReadTurnsIntoAValue() throws IOException {
        // WHY : Assumptions: the whole case is vacuous if the identifier ever became present, and a
        //       fixture is the artifact most likely to gain a row. The absence is constructed from
        //       DATA and never by removing a row or altering a relation, because a case that deleted
        //       its own precondition would leave the corpus different for whichever case ran next.
        assertThat(publishedIdentitiesOfFixture())
                .as("the customer fixture must not publish the probe identifier")
                .doesNotContain(ABSENT_IDENTIFIER);
        assertThat(decodeAll(STATEMENT_PATH_XREF_FIXTURE, XREF_DESCRIPTOR))
                .as("no cross-reference row may name the probe identifier")
                .noneMatch(row -> integral(row, XREF_CUSTOMER_ID_FIELD) == ABSENT_IDENTIFIER);

        Optional<CustomerView> unresolved = customers.findById(ABSENT_IDENTIFIER);

        assertThat(unresolved)
                .as("a well-formed identifier the relation does not carry resolves to nothing")
                .isEmpty();

        // WHY : Assumptions: this asymmetry is the entire ground for treating the empty answer as
        //       fatal, so it is asserted rather than cited. The arm the cursor has at L356 is what
        //       ends a run normally; its absence from the customer read's evaluation is what makes an
        //       unresolved customer an abort. If a future edit gave the customer read such an arm, the
        //       target's refusal would be the divergence rather than the parity.
        List<String> statementProgram = jobLines(STATEMENT_PROGRAM);

        assertThat(statementProgram.subList(
                        CUSTOMER_EVALUATION_FIRST_LINE - 1, CUSTOMER_EVALUATION_LAST_LINE))
                .withFailMessage("%s L%d to L%d must carry NO end-of-file arm; an unresolved customer"
                        + " is a referential-integrity violation and not an end of data",
                        STATEMENT_PROGRAM, CUSTOMER_EVALUATION_FIRST_LINE,
                        CUSTOMER_EVALUATION_LAST_LINE)
                .noneMatch(line -> line.contains(END_OF_FILE_STATUS));
        assertThat(statementProgram.get(CROSS_REFERENCE_END_OF_FILE_LINE - 1))
                .as("%s L%d carries the cross-reference read's end-of-file arm",
                        STATEMENT_PROGRAM, CROSS_REFERENCE_END_OF_FILE_LINE)
                .contains(END_OF_FILE_STATUS);

        // WHY : Assumptions: the reference displays the definition name and the status at
        //       app/cbl/CBSTM03A.CBL L383 and L384 before abending, and the target's refusal
        //       additionally names the identifier it could not resolve -- which is the stated reason
        //       the interface leaves the refusal to its caller rather than raising it from the lookup.
        //       An operator reading the failure needs the same two facts the display gave them plus
        //       the key, because the key is what identifies the broken cross-reference row.
        assertThatThrownBy(() -> unresolved.orElseThrow(() -> abendFor(ABSENT_IDENTIFIER)))
                .as("the refusal the caller raises from an empty answer")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CUSTOMER_DEFINITION_NAME)
                .hasMessageContaining(String.valueOf(ABSENT_IDENTIFIER))
                .hasMessageContaining(ABEND_MESSAGE);

        // WHY : Alternatives Considered: asserting the modelled refusal alone, which is what the
        //       production caller actually raises. Rejected because it would make the contract a
        //       property of one refusal builder; this second capture establishes that the empty answer
        //       yields nothing to ANY terminal read, which is what rules out a default row reaching a
        //       statement heading through a caller that reached for a different accessor.
        assertThatThrownBy(unresolved::orElseThrow)
                .as("a bare terminal read of the same empty answer")
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Confirms the projection withholds both protected identifier columns the base table carries.
     *
     * <p>Assumptions: the two columns are ABSENT from the projection rather than masked within it, and
     * the difference is the whole point. A masked form would still give this login role a column to
     * read, and the ciphertext would hand it material to attack offline; omission is the only form of
     * masking that a later change to a mapper cannot undo. A statement heading needs the cardholder's
     * name and address -- which is what {@code app/cpy/COSTM01.CPY} lays out -- and never an
     * identifier, so the correct reach for this role is none at all.</p>
     *
     * <p>Assumptions: no assertion here names either column. Each name is taken at run time from the
     * registered descriptor by the byte offset the copybook declares it at, so the assertion is exact
     * without an identifier's name appearing in it; the projection's own definition practises the same
     * indirection, because a test over that file asserts neither identifier appears in it at all,
     * comments included. Alternatives Considered: writing the two column names out as literals, which
     * would make the assertion shorter to read. Rejected because an assertion that a literal is absent
     * is satisfied by that literal being stale, whereas a name derived from the descriptor cannot
     * drift away from the field it stands for. The fixture load below does name both target columns,
     * once, because each is declared not null in the base table -- but a load is not an assertion, and
     * nothing derives from it.</p>
     *
     * <p>Assumptions: the base table is asserted to CARRY both columns, and that positive control is
     * what makes the negative assertion mean anything -- an absence from the projection proves nothing
     * if the source has no such column either. The base table belongs to the account service's
     * migration and the projection to the data-migration package; a missing relation here is a defect
     * to report against whichever of those owns it, which register entry <b>R11</b> records.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the projection withholds both protected identifier columns the base table carries")
    void theProjectionWithholdsBothProtectedIdentifierColumns() {
        List<String> tokens = protectedColumnTokens();

        // WHY : Assumptions: the tokens below are derived from the descriptor rather than written out,
        //       so the sensitivity flag is the thing that identifies them and it is asserted first. If
        //       a future edit cleared either flag, this case would stop testing what it claims to,
        //       and the descriptor is the single place in the migration where that flag lives.
        assertThat(tokens)
                .as("both protected fields are derivable from the registered descriptor")
                .hasSize(2);
        for (String token : tokens) {
            assertThat(token)
                    .as("a derived column token is non-blank and lower-cased")
                    .isNotBlank()
                    .isEqualTo(token.toLowerCase(Locale.ROOT));
        }

        // WHY : Assumptions: in the target both identifiers are held enciphered as byte columns and
        //       returned masked, so a byte-array member on a reporting projection would be a column of
        //       exactly that shape. Asserting the absence of the shape as well as of the two names
        //       catches a third enciphered column added later under a name neither token matches.
        List<String> mappedColumns = mappedColumnNames();

        for (String token : tokens) {
            assertThat(mappedColumns)
                    .withFailMessage("the projection must map no column carrying the protected field"
                            + " at the offset this class pins; a statement heading prints a name and"
                            + " an address, never an identifier")
                    .noneMatch(column -> column.contains(token));
        }
        for (Field field : CustomerView.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .withFailMessage("the projection must declare no byte-array member; %s is one,"
                            + " and an enciphered column is the only reason such a member would"
                            + " exist here", field.getName())
                    .isNotEqualTo(byte[].class);
        }

        // WHY : Assumptions: the positive control is load-bearing -- an absence from the projection
        //       would prove nothing if the source table had no such column either, and that failure
        //       mode is invisible without checking both. The two are read from the catalogue rather
        //       than from either definition file, so what is asserted is the relation the service
        //       actually reads and not a statement about a file.
        Set<String> baseColumns = columnNamesOf("account", "customers");
        Set<String> projectionColumns = columnNamesOf("reporting", "v_customers");

        assertThat(projectionColumns)
                .as("the projection exposes the columns a statement heading is built from")
                .isNotEmpty();
        for (String token : tokens) {
            assertThat(baseColumns)
                    .withFailMessage("the base table must carry the protected column this case"
                            + " asserts the projection withholds, or the absence below proves"
                            + " nothing")
                    .anyMatch(column -> column.contains(token));
            assertThat(projectionColumns)
                    .withFailMessage("the projection must expose no protected column; it is absent"
                            + " from the projection rather than masked within it, because omission is"
                            + " the only masking a later mapper change cannot undo")
                    .noneMatch(column -> column.contains(token));
        }
    }

    /**
     * Confirms the statement the keyed read generates names neither protected column.
     *
     * <p>Assumptions: what a relation exposes and what a query asks it for are two different facts,
     * and this case establishes the second. The case beside it shows the projection withholds both
     * columns; this one observes the statement the provider actually issued for the lookup, so a
     * mapping that reached past the projection -- to the base table, or through a member added with
     * its own column name -- would be caught here even though the projection itself were unchanged.</p>
     *
     * <p>Assumptions: the statement is captured through the provider's own statement-inspection
     * extension point, registered on the nested context below, so what is asserted is the text sent to
     * the driver rather than a rendering of it. The lookup is called with NO enclosing transaction on
     * purpose: the framework then gives the call its own short transaction and its own persistence
     * context, so the row cannot be answered from a context an earlier case populated and a statement
     * is guaranteed to reach the engine.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the generated lookup statement names the projection and neither protected column")
    void theGeneratedLookupStatementNamesTheProjectionAndNeitherProtectedColumn() {
        List<String> tokens = protectedColumnTokens();
        GENERATED_STATEMENTS.clear();

        assertThat(customers.findById(PUBLISHED_IDENTITIES.getFirst()))
                .as("the lookup resolves, so a statement was generated for it")
                .isPresent();

        List<String> captured = GENERATED_STATEMENTS.stream()
                .map(statement -> statement.toLowerCase(Locale.ROOT))
                .toList();

        // WHY : Assumptions: the projection is the relation this role is granted select on, and the
        //       base table beneath it is unreachable to the deployed login role by privilege as well
        //       as by name. A statement naming the base table would therefore run here, where the
        //       context connects as the container's own user, and fail in production -- which is the
        //       one defect this capture can see and no local reasoning can.
        assertThat(captured)
                .withFailMessage("the lookup must issue a statement against the projection; nothing"
                        + " was captured naming it, so either no statement reached the engine or the"
                        + " mapping addressed something else")
                .anyMatch(statement -> statement.contains("v_customers"));

        // WHY : Alternatives Considered: checking only the statement that names the projection, which
        //       reads as the narrower and therefore safer form. Rejected because a
        //       mapping defect could issue a second statement -- a
        //       secondary load, or a read of a base table -- and a per-statement assertion scoped to
        //       the projection would not see it. In the target both identifiers are stored enciphered
        //       and returned masked, so a statement that named either would be a reach this role has
        //       no business making at all.
        for (String token : tokens) {
            assertThat(captured)
                    .withFailMessage("no statement generated for the keyed read may name a protected"
                            + " column; a statement heading needs the cardholder's name and address"
                            + " and never an identifier")
                    .noneMatch(statement -> statement.contains(token));
        }
    }

    /**
     * Derives the two protected column tokens from the registered descriptor, by byte offset.
     *
     * <p>Assumptions: the derivation is mechanical and deliberately name-free in this source. Each
     * field is located by the zero-based start the copybook's declared widths put it at, its
     * sensitivity flag is required, and its name is folded to the column-name convention the target
     * schema uses -- the record prefix dropped, the separator changed and the result lower-cased --
     * which yields the leading token of the enciphered column that carries it. Matching by leading
     * token rather than by whole name is what lets the assertion hold whatever suffix the schema gives
     * an enciphered column.</p>
     *
     * @return the two derived tokens, in declaration order, never {@code null}
     * @throws AssertionError if either field is absent from the descriptor at the declared offset, or
     *     is not marked sensitive there
     */
    private static List<String> protectedColumnTokens() {
        List<String> tokens = new ArrayList<>();
        for (int start : List.of(NATIONAL_IDENTIFIER_START, GOVERNMENT_IDENTIFIER_START)) {
            CopybookLayout.FieldSpec field = CopybookLayout.layout(CUSTOMER_DESCRIPTOR).fields()
                    .stream()
                    .filter(candidate -> candidate.start() == start)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the registered CUSTOMER descriptor carries"
                            + " no field beginning at zero-based offset " + start + ", so the"
                            + " protected columns this class asserts about cannot be located"));
            assertThat(field.sensitive())
                    .withFailMessage("the field at zero-based offset %d must be marked sensitive in"
                            + " the descriptor, because that flag is what identifies it here", start)
                    .isTrue();
            tokens.add(field.name()
                    .replace(CUSTOMER_FIELD_PREFIX, "")
                    .replace('-', '_')
                    .toLowerCase(Locale.ROOT));
        }
        return List.copyOf(tokens);
    }

    /**
     * Reads the column names the projection maps, lower-cased for comparison.
     *
     * <p>Assumptions: the mapped name is read from the mapping annotation rather than inferred from
     * the member name, because the two differ for every member here -- the annotation carries the
     * schema's spelling and the member carries the Java one -- and only the annotation's spelling can
     * appear in a generated statement.</p>
     *
     * @return every column the projection maps, lower-cased, never {@code null}
     */
    private static List<String> mappedColumnNames() {
        List<String> columns = new ArrayList<>();
        for (Field field : CustomerView.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(column.name().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(columns);
    }

    /**
     * Reads the identifiers the committed customer fixture publishes, in fixture order.
     *
     * @return one identifier per fixture row, in file order, never {@code null}
     */
    private static List<Long> publishedIdentitiesOfFixture() {
        return decodeAll(CUSTOMER_FIXTURE, CUSTOMER_DESCRIPTOR).stream()
                .map(row -> integral(row, CUSTOMER_ID_FIELD))
                .toList();
    }

    /**
     * Reads the column names of one relation from the engine's own catalogue.
     *
     * <p>Assumptions: the catalogue is read rather than either definition file, so what is asserted is
     * the relation the service reads and not a claim about a script. The relation is addressed by
     * schema and name as bound parameters rather than by string assembly, which keeps the query one
     * prepared statement whatever it is asked about.</p>
     *
     * @param schema the schema holding the relation
     * @param relation the relation's name
     * @return that relation's column names, lower-cased, never {@code null} and empty when the
     *     relation does not exist
     */
    private Set<String> columnNamesOf(String schema, String relation) {
        List<?> rows = entityManager.createNativeQuery(
                        "select column_name from information_schema.columns"
                                + " where table_schema = ?1 and table_name = ?2")
                .setParameter(1, schema)
                .setParameter(2, relation)
                .getResultList();
        Set<String> columns = new LinkedHashSet<>();
        for (Object row : rows) {
            columns.add(String.valueOf(row).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    /**
     * Builds the refusal a caller raises when the cross-reference names a customer that is absent.
     *
     * <p>Assumptions: the shape mirrors the production caller's own refusal builder at L1700 of
     * {@code StatementService} in this module's {@code service} package,
     * which renders the reference's four abend fields into the message and returns an
     * unchecked failure. It is modelled here rather than invoked because the unit under test in this
     * class is the repository role, and reaching into the service would make a service change read as
     * a lookup defect.</p>
     *
     * <p>Alternatives Considered: asserting on the production builder directly. Rejected for the
     * reason above and because the service's own unit tests already cover it; what this class needs is
     * the CONTRACT the empty answer imposes on any caller, which is expressible without the
     * service.</p>
     *
     * @param identifier the identifier the cross-reference named and the projection could not answer
     * @return the refusal to raise, never {@code null} and never itself raised from here
     */
    private static IllegalStateException abendFor(long identifier) {
        return new IllegalStateException("ERROR READING " + CUSTOMER_DEFINITION_NAME
                + ": the cross-reference names customer " + identifier
                + " which does not resolve; abendMsg=" + ABEND_MESSAGE);
    }

    /**
     * Reads one reference artifact from the working tree, line by line.
     *
     * <p>Assumptions: the bytes are taken in a single-byte encoding rather than decoded as multi-byte
     * text, because these are fixed-column mainframe artifacts and a decoder that substituted a
     * replacement character for an unexpected byte would silently alter a line an assertion compares.
     * The path is resolved from the repository root located by probe, so this class reads the same
     * file whether the build was invoked from the module directory or from the reactor root.</p>
     *
     * @param repositoryRelativePath the artifact's path, relative to the repository root
     * @return every line of that artifact, in file order, never {@code null}
     * @throws IOException if the artifact cannot be read
     * @throws AssertionError if the artifact is absent from the working tree
     */
    private static List<String> jobLines(String repositoryRelativePath) throws IOException {
        Path source = repositoryRoot().resolve(repositoryRelativePath);
        assertThat(Files.isRegularFile(source))
                .withFailMessage("the reference artifact %s must be present; it is read-only"
                        + " reference in this migration and every line-numbered citation in this"
                        + " class is checked against it", repositoryRelativePath)
                .isTrue();
        return Files.readAllLines(source, StandardCharsets.ISO_8859_1);
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
     * and from the reactor root. Nothing here authors a definition: every statement executed is one
     * the named file already contains.</p>
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
     * Loads the two fixtures this class reads into the relations the account service declares.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against its registered descriptor, so the load carries the record layout the migration is
     * defined against rather than a second transcription of it. Alternatives Considered: parsing the
     * fixed-width rows here by offset. Rejected because the descriptor registry is the one place
     * offsets are declared, and a second parser in this class would drift from it silently -- and the
     * fixture's geometry is exactly what several assertions above depend on.</p>
     *
     * <p>Assumptions: the loads are inserts and nothing else. This class defines no relation, conveys
     * no privilege and discards nothing; the boundary this directory draws is on DEFINITIONS, and
     * inserting rows sits inside it because a fixture has to be loaded before anything can be read.
     * Neither relation this class writes declares a foreign key, so the two loads are independent and
     * no third fixture is needed to satisfy one.</p>
     *
     * <p>Assumptions: the card master is deliberately NOT loaded, and the omission is recorded because
     * a sibling does load it. Neither reporting program reads a card master --
     * {@code app/cbl/CBSTM03A.CBL} reads the transaction, cross-reference, account and customer
     * definitions, and {@code app/jcl/TRANREPT.jcl} L65 to L73 names no card store -- and the one
     * class here that loads it does so to have a populated relation its write-refusal case can be
     * refused against. This class asserts no privilege, so it needs no such target.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded
     */
    private static void loadFixtureCorpus() {
        try (Connection connection = POSTGRES.createConnection("")) {
            loadCustomers(connection);
            loadCardCrossReferences(connection);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the statement-path fixture corpus", cause);
        }
    }

    /**
     * Loads {@code custfile.txt} into the customer master.
     *
     * <p>Assumptions: the two enciphered columns receive {@link #PLACEHOLDER_CIPHERTEXT} because the
     * projection under test selects neither -- which a case above asserts -- so what they hold cannot
     * affect an assertion here, and reaching for the owning service's cipher would couple this class
     * to a component it does not exercise. This statement's column list is the one place either
     * target column is named at all, and it names them because a row cannot be inserted otherwise; no
     * value of either protected field is read out of the fixture.</p>
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
            for (Map<String, Object> row : decodeAll(CUSTOMER_FIXTURE, CUSTOMER_DESCRIPTOR)) {
                insert.setLong(1, integral(row, CUSTOMER_ID_FIELD));
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
     * Loads {@code xreffile.txt} into the card cross-reference.
     *
     * <p>Assumptions: this is the statement-path fixture and the only one of the two cross-reference
     * fixtures loaded, because loading both would insert one card twice and the relation's key is the
     * card number. It is loaded at all because the identifiers this class resolves are the ones the
     * driving cursor supplies, and the fixture is where they come from.</p>
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
                insert.setString(1, text(row, XREF_CARD_NUMBER_FIELD));
                insert.setLong(2, integral(row, XREF_CUSTOMER_ID_FIELD));
                insert.setLong(3, integral(row, XREF_ACCOUNT_ID_FIELD));
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
        try (InputStream stream = StatementCustomerRepositoryIT.class.getClassLoader()
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
     * <p>Assumptions: every integral field this class reads is an identifier or a bounded small
     * integer, never money, so a whole-number type is exact for all of them and no decimal type
     * appears in this class at all. The customer record carries no monetary field, which a case above
     * asserts against the descriptor rather than leaving to this observation.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value
     */
    private static long integral(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    /**
     * Reads one decoded character field holding an already-ordered calendar date.
     *
     * <p>Assumptions: the field is ten characters in year-month-day order, so its lexical order is its
     * chronological order and parsing needs no format of its own.</p>
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
     * Records every statement the persistence provider generates, without altering any of them.
     *
     * <p>Assumptions: the provider's inspection extension point is the only place the text sent to the
     * driver is observable without changing what is sent, and returning the argument unchanged is what
     * keeps this a pure observer -- the contract permits a substitution, and one made here would mean
     * a case asserted about a statement the provider never issued.</p>
     *
     * <p>Assumptions: the recorded statements are held on the enclosing class rather than on an
     * instance of this one, because the provider constructs this type itself and the test never holds
     * the instance it constructed.</p>
     */
    static final class CapturingStatementInspector implements StatementInspector {

        /**
         * The serialized form's version, fixed at one.
         */
        // WHY : Assumptions: the provider's inspection extension point extends the serialization
        //       marker, so this type is serializable whether or not anything ever serializes it, and a
        //       compiler reports the missing version under its full warning set. The value is declared
        //       explicitly rather than left to the compiler to derive, because a derived value changes
        //       whenever a member is added and would silently invalidate an already-serialized form.
        //       Alternatives Considered: suppressing the warning instead, on the ground that this
        //       instance is constructed by the provider and never written to a stream. Rejected because
        //       the suppression would have to be revisited the moment the provider's own serialization
        //       behaviour changed, whereas a fixed version is correct either way -- the same trade the
        //       shared statement projection's embedded identifier records.
        private static final long serialVersionUID = 1L;

        /**
         * Records one generated statement and returns it unchanged.
         *
         * @param sql the statement the provider is about to send to the driver
         * @return that same statement, never altered and never {@code null} when the argument is not
         */
        @Override
        public String inspect(String sql) {
            GENERATED_STATEMENTS.add(sql);
            return sql;
        }
    }

    /**
     * The narrowest context that can create the lookup's repository proxy.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason all four sibling integration
     * tests record: scanning the root would instantiate the orchestration client and the filter chain,
     * so a context started to perform one keyed read would additionally need a state-machine
     * identifier and an object-store bucket, and a failure to supply either would read as a lookup
     * defect. {@code application-test.yml} declares none of those values, and its own register of
     * deliberate omissions is the authority for that: a test needing one supplies it in its own
     * source.</p>
     *
     * <p>Alternatives Considered: a data-access test slice, which would restrict auto-configuration to
     * persistence and remove the cloud values supplied above entirely. It is not available -- the
     * artifact carrying that annotation is absent from {@code services/reporting-service/pom.xml} and
     * from the reactor's managed set -- and adding a dependency to reshape a test is not a trade this
     * module makes.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class CustomerLookupTestApplication {

        /**
         * Registers the statement recorder on the persistence provider this context builds.
         *
         * <p>Assumptions: the recorder is supplied as an INSTANCE through the provider's own settings
         * map rather than as a class name in a property, so the wiring is checked by the compiler and
         * a rename cannot leave a property naming a class that no longer exists. Alternatives
         * Considered: naming the class in a property on the annotation above, which the provider also
         * accepts. Rejected because it resolves the name reflectively at context refresh, so the same
         * mistake would surface as a context failure rather than as a compilation error.</p>
         *
         * @return the customiser that installs the recorder, never {@code null}
         */
        @Bean
        HibernatePropertiesCustomizer statementRecorder() {
            return properties -> properties.put(
                    AvailableSettings.STATEMENT_INSPECTOR, new CapturingStatementInspector());
        }
    }
}
