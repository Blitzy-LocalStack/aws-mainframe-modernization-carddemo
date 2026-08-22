package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.domain.StatementTransactionView;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.QueryHint;
import jakarta.persistence.Version;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.hibernate.annotations.Immutable;
import org.hibernate.jpa.AvailableHints;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the statement transaction cursor streams a card's whole run in the baseline's own order.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code StatementTransactionRepository} stands in for one data definition:
 * {@code //TRNXFILE DD} at L83 of {@code app/jcl/CREASTMT.JCL}, read by
 * {@code //STEP040 EXEC PGM=CBSTM03A,COND=(0,NE)} at L79 of the same job. Its rows are the 350-byte
 * record {@code app/cpy/COSTM01.CPY} declares at L20 through L36, reached through the
 * per-definition paragraph of {@code app/cbl/CBSTM03B.CBL} at L133 through L155 -- open at L135,
 * a plain read at L140, close at L146, and nothing else. Six properties of the migrated cursor are
 * invisible to every cheaper gate in this module and are asserted here against a real engine
 * carrying the shipped definitions: the record geometry it maps is the statement geometry and not
 * the posting one; its amounts are exact fixed point at a scale of two; it carries no arity ceiling
 * where the reference declares two tables and exhibits a third threshold; its order is the two-key
 * order the job's sort declares; it is a forward-only cursor that cannot be opened outside a
 * transaction that outlives the call; and neither the interface nor the relation beneath it offers
 * a way to write or to materialise a second copy.</p>
 *
 * <h2>What this class asserts that no sibling does</h2>
 *
 * <p>Assumptions: the five classes already in this directory divide the module's engine-backed
 * properties between them, and this one takes what is left rather than restating any of theirs.
 * {@code ReportingQueryBootstrapIT} establishes no relation and proves the declared queries parse;
 * {@code StatementHeadingChunkIT} seeds stand-in tables and proves the heading walk's keyset
 * predicate reproduces its own ordering; {@code ReportingDeployedRelationIT} applies the shipped
 * definitions and proves the projections mask, that a card joins to its customer and its account,
 * and that the login role cannot write a relation it can read; {@code StatementCardXrefRepositoryIT}
 * walks the driving cross-reference cursor and proves the two-level write refusal. None of the five
 * reads {@code fixtures/trnxfile.txt} and none calls a method on the interface under test here.
 * This class is the only consumer of both, and it is therefore also the only artifact in this
 * module that measures the 350-byte transaction record against a fixture rather than summing it
 * from a file section.</p>
 *
 * <h2>The order is asserted as a composition, because no whole-table cursor is declared</h2>
 *
 * <p>Assumptions: the interface declares three reads and none of them walks every card. Its own
 * Javadoc records that an ordered pass over the whole projection was declared, was reached by
 * nothing and was withdrawn, because the migrated statement flow composes one statement per
 * request. Asserting a method that does not exist would fail without saying anything about the
 * reference, so the two-key order is asserted the way a run would reproduce it: the cards are
 * walked in ascending published order and each card's cursor is concatenated, and the resulting
 * sequence is compared with one derived independently from the committed fixture. That the
 * withdrawn method is still absent is asserted separately, by naming the interface's whole declared
 * surface.</p>
 *
 * <h2>Why the shipped definitions are applied rather than reproduced</h2>
 *
 * <p>Assumptions: every relation read below is created by executing the shipped files themselves,
 * read from the working tree and handed to the engine unchanged. Nothing here authors a schema
 * object of any kind -- no relation, no view, no index and no privilege -- and no statement below
 * defines one. The authority for the schemas, the roles and the privileges is
 * {@code data-migration/sql/V0__schemas_and_roles.sql}; the authority for the projections is
 * {@code data-migration/sql/V1__reporting_views.sql}; the base table beneath the one this class
 * reads belongs to {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}.
 * Register entry <b>R11</b> in the main-tree charter records that a relation missing at run time is
 * a defect to report against whichever of those files owns it, never something for a class here to
 * remedy, and every arrangement failure below names that owner.</p>
 *
 * <p>Alternatives Considered: a hand-written stand-in for the projection, which is what the sibling
 * ordering class uses. Rejected here because three of the six properties above live outside Java
 * altogether: the amount's precision is a column declaration, the order depends on the collation
 * pinned on two columns by
 * {@code services/transaction-service/src/main/resources/db/migration/V3__ledger_bytewise_collation.sql},
 * and whether anything is materialised is a fact about the catalog. A stand-in would assert all
 * three of the stand-in and nothing about what ships.</p>
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
        classes = StatementTransactionRepositoryIT.StatementCursorTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: this module carries cloud starters as compile dependencies, so a context
    //       that enables auto-configuration at all builds their beans, and the region provider
    //       resolves eagerly and raises when it finds none. Nothing below contacts a cloud service,
    //       so the value identifies nothing this class reaches. It is supplied here rather than in
    //       application-test.yml because that document's own register of deliberate omissions rules
    //       that a cloud-service value belongs to the test needing it.
    "spring.cloud.aws.region.static=us-east-1",
    // WHY : Assumptions: with them enabled each importer looks for a store to read -- system
    //       properties, environment, web identity, profile file, container and finally the instance
    //       metadata service -- which costs seconds where that last probe fails fast and a timeout
    //       where it hangs, so switching them off is about determinism rather than tidiness. The
    //       second key NAMES a credential store while its VALUE is what stops one from being
    //       read, so neither key is a credential and this class supplies no signing material at all.
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false",
    // WHY : Assumptions: the plan cases below have to examine the plan of the statement the PROVIDER
    //       emits, and the only way to be sure they examine that one is to record it as it is issued.
    //       Alternatives Considered: writing the expected SQL into the case and explaining that.
    //       Rejected because it is a second transcription of the query -- the case would then pass
    //       while the repository executed something else entirely, which is the exact failure the
    //       finding these cases answer was about.
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
            + "com.carddemo.reporting.repository.StatementTransactionRepositoryIT$EmittedStatements"
})
class StatementTransactionRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and two properties asserted here
     * that depend on the engine -- the ordering of a bytewise-collated key and the scale a numeric
     * aggregate returns -- are ones an engine version can genuinely change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The two session settings the shipped bootstrap requires before it will run.
     *
     * <p>Assumptions: both are opt-outs the bootstrap reads through {@code current_setting} with its
     * missing-value flag set, and it raises rather than proceeding when neither is on: one
     * acknowledges an unencrypted local connection, the other accepts roles arriving without a
     * credential because their source is a credential store no test has. They are applied as session
     * settings below, because the engine's own client accepts several statement options intermixed
     * with a file option and runs them in ONE session, so a setting made before the file is still in
     * force while the file executes. Alternatives Considered: recording them on the database
     * instead. Rejected because that form is a schema-alteration statement, and this directory's
     * charter draws its boundary on definitions -- executing an authoritative definition unchanged
     * is permitted, authoring one is not, and a database-level setting would be one this class
     * authored.</p>
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
     * missing at creation surfaces on the first READ rather than at creation, which is exactly the
     * failure the second pass removes.</p>
     *
     * <p>Assumptions: the two later ledger migrations are load-bearing for this class specifically
     * rather than carried for symmetry. {@code V3__ledger_bytewise_collation.sql} pins both
     * {@code transaction_id} and {@code card_num} to the bytewise collation at its L131 through
     * L142, and it REFUSES to run while a view depends on the column it retypes -- so it has to
     * precede {@code V1__reporting_views.sql}, and omitting it would leave the ordering key this
     * class asserts on at the mercy of whichever collation the container's initialisation chose.</p>
     *
     * <p>Trade-offs: the reference seed is deliberately not among these, and the omission costs
     * this class nothing. The relation it reads is
     * {@code ledger.transactions CROSS JOIN reporting.card_grouping_key} and the lookup it calls
     * reads {@code account.card_xref}, so no reference row participates in either; the reference
     * migration that CREATES those tables is still applied, because the projections over them
     * cannot be created without them.</p>
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
     * The statement-path transaction fixture, bound by exact name.
     *
     * <p>Assumptions: this file carries the STATEMENT geometry and decodes against the descriptor
     * {@code TRNX}, never {@code TRAN}. Both records are 350 bytes and both are transaction rows, so
     * decoding this one against the posting descriptor would read every field from the wrong offset
     * while leaving every row exactly 350 bytes -- a failure with no length symptom at all. It is
     * named for the {@code TRNXFILE} definition at {@code app/jcl/CREASTMT.JCL} L83.</p>
     */
    private static final String TRANSACTION_FIXTURE = "trnxfile.txt";

    /**
     * The statement-path cross-reference fixture, bound by exact name.
     *
     * <p>Assumptions: this file and {@code cardxref.txt} are DIFFERENT fixtures for the same 50-byte
     * record under two different data-definition names. This one is named for the {@code XREFFILE}
     * definition at {@code app/jcl/CREASTMT.JCL} L84, which the statement generator reads; the other
     * is named for the {@code CARDXREF} definition at {@code app/jcl/TRANREPT.jcl} L67, which the
     * report writer reads. Only this one is loaded here, because the per-card lookup this class
     * calls resolves against the relation it populates.</p>
     */
    private static final String CROSS_REFERENCE_FIXTURE = "xreffile.txt";

    /**
     * The statement-path account fixture, bound by exact name.
     *
     * <p>Assumptions: this is a STATEMENT-path definition only. It is named for the
     * {@code ACCTFILE} definition at {@code app/jcl/CREASTMT.JCL} L85, and
     * {@code app/jcl/TRANREPT.jcl} declares no account definition at all, so the report path never
     * reads an account master. It is decoded here for the record-length quartet and deliberately not
     * inserted.</p>
     */
    private static final String ACCOUNT_FIXTURE = "acctfile.txt";

    /**
     * The statement-path customer fixture, bound by exact name.
     *
     * <p>Assumptions: named for the {@code CUSTFILE} definition at {@code app/jcl/CREASTMT.JCL}
     * L86. The definition order at L83 through L86 is transaction, cross-reference, ACCOUNT and then
     * CUSTOMER, verified against the job rather than recalled, because an inverted reading of the
     * last two circulates and would misattribute both lengths.</p>
     */
    private static final String CUSTOMER_FIXTURE = "custfile.txt";

    /**
     * The registered copybook descriptor the statement transaction fixture decodes against.
     */
    private static final String TRANSACTION_DESCRIPTOR = "TRNX";

    /**
     * The registered copybook descriptor of the posting transaction master, read for contrast only.
     */
    private static final String POSTING_DESCRIPTOR = "TRAN";

    /**
     * The registered copybook descriptor the cross-reference fixture decodes against.
     */
    private static final String CROSS_REFERENCE_DESCRIPTOR = "XREF";

    /**
     * The registered copybook descriptor the account fixture decodes against.
     */
    private static final String ACCOUNT_DESCRIPTOR = "ACCOUNT";

    /**
     * The registered copybook descriptor the customer fixture decodes against.
     */
    private static final String CUSTOMER_DESCRIPTOR = "CUSTOMER";

    /**
     * The declared length of the statement transaction record, three hundred and fifty bytes.
     *
     * <p>Assumptions: derived by SUMMING the declared field widths of {@code app/cpy/COSTM01.CPY}
     * L22 through L36 -- 16 and 16 for the key group, then 2, 4, 10, 100, 11, 9, 50, 50, 10, 26, 26
     * and 20 -- and never by trusting the banner above them, because a banner is a comment and a
     * comment cannot be wrong in a way a compiler notices. It is corroborated independently by the
     * file section of {@code app/cbl/CBSTM03B.CBL}, whose {@code FD TRNX-FILE} at L58 declares a
     * 32-byte key group at L60 through L62 and a 318-byte remainder at L63.</p>
     */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /**
     * The declared length of the cross-reference record, fifty bytes.
     */
    private static final int CROSS_REFERENCE_RECORD_LENGTH = 50;

    /**
     * The declared length of the account record, three hundred bytes.
     */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /**
     * The declared length of the customer record, five hundred bytes.
     */
    private static final int CUSTOMER_RECORD_LENGTH = 500;

    /**
     * The width of the composite identity, thirty-two bytes.
     *
     * <p>Assumptions: {@code 05 TRNX-KEY} at {@code app/cpy/COSTM01.CPY} L21 is the card number at
     * L22 plus the transaction identifier at L23, both {@code PIC X(16)}, so the identity is 32
     * bytes and neither component identifies a row on its own -- the fixture carries 600 rows under
     * one card number, so the card alone is not a key.</p>
     */
    private static final int COMPOSITE_KEY_WIDTH = 32;

    /**
     * The declared width of each component of the composite identity, sixteen bytes.
     */
    private static final int KEY_COMPONENT_WIDTH = 16;

    /**
     * The one-based first byte the statement amount occupies, one hundred and forty-nine.
     *
     * <p>Assumptions: 32 for the key group plus 2, 4, 10 and 100 is 148 bytes ahead of it, so the
     * amount begins at one-based 149.</p>
     */
    private static final int STATEMENT_AMOUNT_FIRST_BYTE = 149;

    /**
     * The one-based last byte the statement amount occupies, one hundred and fifty-nine.
     */
    private static final int STATEMENT_AMOUNT_LAST_BYTE = 159;

    /**
     * The one-based first byte the POSTING amount occupies, one hundred and thirty-three.
     *
     * <p>Assumptions: {@code app/cpy/CVTRA05Y.cpy} L10 declares {@code TRAN-AMT} after 16, 2, 4, 10
     * and 100 bytes, which is 132, so it begins at one-based 133 -- sixteen bytes earlier than the
     * statement amount, because the card number moved from one-based 263 to position 1.</p>
     */
    private static final int POSTING_AMOUNT_FIRST_BYTE = 133;

    /**
     * The one-based first byte the POSTING card number occupies, two hundred and sixty-three.
     */
    private static final int POSTING_CARD_NUMBER_FIRST_BYTE = 263;

    /**
     * The one-based first byte the processing timestamp occupies in BOTH layouts, three hundred
     * and five.
     */
    private static final int PROCESSING_TIMESTAMP_FIRST_BYTE = 305;

    /**
     * The one-based last byte the processing timestamp occupies in both layouts, three hundred
     * and thirty.
     */
    private static final int PROCESSING_TIMESTAMP_LAST_BYTE = 330;

    /**
     * The one-based first byte of the trailing pad, three hundred and thirty-one.
     */
    private static final int TRAILING_PAD_FIRST_BYTE = 331;

    /**
     * The copybook name of the trailing pad that carries the record to its declared length.
     */
    private static final String PADDING_FIELD = "FILLER";

    /**
     * The copybook field name of the statement card number.
     */
    private static final String CARD_NUMBER_FIELD = "TRNX-CARD-NUM";

    /**
     * The copybook field name of the transaction identifier.
     */
    private static final String TRANSACTION_ID_FIELD = "TRNX-ID";

    /**
     * The copybook field name of the statement amount.
     */
    private static final String AMOUNT_FIELD = "TRNX-AMT";

    /**
     * The copybook field name of the origination timestamp.
     */
    private static final String ORIGINATION_TIMESTAMP_FIELD = "TRNX-ORIG-TS";

    /**
     * The copybook field name of the processing timestamp.
     */
    private static final String PROCESSING_TIMESTAMP_FIELD = "TRNX-PROC-TS";

    /**
     * The copybook field name of the transaction type code.
     */
    private static final String TYPE_CODE_FIELD = "TRNX-TYPE-CD";

    /**
     * The copybook field name of the transaction category code.
     */
    private static final String CATEGORY_CODE_FIELD = "TRNX-CAT-CD";

    /**
     * The copybook field name of the originating source.
     */
    private static final String SOURCE_FIELD = "TRNX-SOURCE";

    /**
     * The copybook field name of the transaction description.
     */
    private static final String DESCRIPTION_FIELD = "TRNX-DESC";

    /**
     * The copybook field name of the merchant identifier.
     */
    private static final String MERCHANT_ID_FIELD = "TRNX-MERCHANT-ID";

    /**
     * The copybook field name of the merchant name.
     */
    private static final String MERCHANT_NAME_FIELD = "TRNX-MERCHANT-NAME";

    /**
     * The copybook field name of the merchant city.
     */
    private static final String MERCHANT_CITY_FIELD = "TRNX-MERCHANT-CITY";

    /**
     * The copybook field name of the merchant postal code.
     */
    private static final String MERCHANT_POSTAL_CODE_FIELD = "TRNX-MERCHANT-ZIP";

    /**
     * The copybook field name of the POSTING amount, read for the contrast assertion only.
     */
    private static final String POSTING_AMOUNT_FIELD = "TRAN-AMT";

    /**
     * The copybook field name of the POSTING card number, read for the contrast assertion only.
     */
    private static final String POSTING_CARD_NUMBER_FIELD = "TRAN-CARD-NUM";

    /**
     * The copybook field name of the POSTING processing timestamp, read for contrast only.
     */
    private static final String POSTING_TIMESTAMP_FIELD = "TRAN-PROC-TS";

    /**
     * The copybook field name of the cross-reference card number.
     */
    private static final String CROSS_REFERENCE_CARD_FIELD = "XREF-CARD-NUM";

    /**
     * The copybook field name of the cross-reference customer identifier.
     */
    private static final String CROSS_REFERENCE_CUSTOMER_FIELD = "XREF-CUST-ID";

    /**
     * The copybook field name of the cross-reference account identifier.
     */
    private static final String CROSS_REFERENCE_ACCOUNT_FIELD = "XREF-ACCT-ID";

    /**
     * The number of rows the statement transaction fixture carries, seven hundred.
     *
     * <p>Assumptions: the figure is asserted against the fixture as well as against the cursor, so a
     * fixture edit cannot silently weaken a walk assertion into one over fewer rows.
     * {@code ReportingFixtureContractTest} binds the same figure against the file itself; this class
     * binds it against the behaviour.</p>
     */
    private static final int TRANSACTION_ROW_COUNT = 700;

    /**
     * The number of distinct cards the fixture corpus publishes, eighty-eight.
     */
    private static final int PUBLISHED_CARD_COUNT = 88;

    /**
     * The number of rows the busiest card carries, six hundred.
     *
     * <p>Assumptions: this figure exceeds BOTH the reference's measured same-card threshold and the
     * retrieval batch the interface declares, and it has to exceed both for the two assertions that
     * use it to mean anything. It is deliberately not stated as a maximum: the migrated path carries
     * no arity at all, so this is a fixture measurement rather than a limit.</p>
     */
    private static final int BUSIEST_CARD_ROW_COUNT = 600;

    /**
     * The declared OUTER arity of the reference's distinct-card table, fifty-one.
     *
     * <p>Assumptions: this is one of THREE figures the reference declares or exhibits and they must
     * be kept apart. {@code app/cbl/CBSTM03A.CBL} L226 declares
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} with an independent companion counter table
     * {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES} at L232, which is this one, and it bounds DISTINCT
     * CARDS; L228 declares {@code 10 WS-TRAN-TBL OCCURS 10 TIMES}, a declared inner arity of ten;
     * and {@link #REFERENCE_MEASURED_SAME_CARD_THRESHOLD} is a third and much larger figure on the
     * other axis. Those three are the only {@code OCCURS} clauses in the program. There is no one
     * combined ceiling, and reading the distinct-card figure as a transaction count would mislabel
     * two separate limits as one.</p>
     */
    private static final int REFERENCE_DECLARED_CARD_TABLE_ARITY = 51;

    /**
     * The declared INNER arity of the reference's same-card table, ten.
     */
    private static final int REFERENCE_DECLARED_TRANSACTION_TABLE_ARITY = 10;

    /**
     * The MEASURED same-card overrun threshold of the reference, five hundred and twelve.
     *
     * <p>Assumptions: measured rather than declared, and recorded at {@code tests/README.md} L70
     * through L82: one card renders up to 512 transactions and the 513th overruns the inner
     * same-card table. That document states in the same paragraph that there is no single combined
     * limit, because a lone figure taken from the outer table's card arity understates this axis by
     * an order of magnitude. The baseline holds a run in declared-width tables, the Java holds one
     * row at a time behind a retrieval batch, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} as divergence D-2 -- cited here,
     * not redefined.</p>
     */
    private static final int REFERENCE_MEASURED_SAME_CARD_THRESHOLD = 512;

    /**
     * The retrieval batch the interface declares, as an integer for comparison.
     *
     * <p>Assumptions: the interface declares it as text because a query hint's value is text, and
     * {@link StatementTransactionRepository#STATEMENT_FETCH_SIZE} is the single source of the figure
     * -- it is parsed here rather than restated, so the two cannot drift. Its value being equal to
     * the measured threshold above is deliberate on the interface's part and is why a walk over the
     * busiest card is the assertion that shows the batch is not a cap.</p>
     */
    private static final int DECLARED_FETCH_SIZE =
            Integer.parseInt(StatementTransactionRepository.STATEMENT_FETCH_SIZE);

    /**
     * The chunk of the cursor a bounded consumption asks for, five rows.
     *
     * <p>Assumptions: five is far below both the retrieval batch and the busiest card's row count,
     * which is what makes a bounded consumption distinguishable from a whole walk at all.</p>
     */
    private static final int BOUNDED_PREFIX_LENGTH = 5;

    /**
     * The page size the keyset window assertion asks for, one hundred rows.
     *
     * <p>Assumptions: it divides the busiest card's row count exactly six times, so a continuation
     * walk consumes whole pages and the final page is full rather than partial -- which is what lets
     * the assertion distinguish a skipped row from a short last page.</p>
     */
    private static final int WINDOW_PAGE_SIZE = 100;

    /**
     * The first-page sentinel the keyset window takes, the empty string.
     *
     * <p>Assumptions: the interface documents that the first page is requested by passing the empty
     * string, which sorts below every sixteen-character identifier the baseline produces because
     * those are digits. A nullable parameter was rejected there, and this constant simply names the
     * documented sentinel rather than reintroducing one.</p>
     */
    private static final String WINDOW_FIRST_PAGE = "";

    /**
     * The separator joining a narrowed card rendering to a transaction identifier in one comparable.
     *
     * <p>Assumptions: both joined components are fixed width, 16 and 16, so the separator carries no
     * ordering weight at all and is present only to make a failure message readable.</p>
     */
    private static final char IDENTITY_SEPARATOR = '/';

    /**
     * The declared precision of the statement amount column, eleven.
     *
     * <p>Assumptions: {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY} L29 is nine
     * integer digits and two decimal digits, so eleven digit positions in total and eleven bytes of
     * zoned decimal with the sign carried as an overpunch on the final byte. The column is
     * {@code NUMERIC(11,2)}.</p>
     */
    private static final int STATEMENT_AMOUNT_PRECISION = 11;

    /**
     * The declared precision of the ACCOUNT balance column, twelve, which is a different regime.
     *
     * <p>Assumptions: {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7 is ten
     * integer digits and two decimal digits, so twelve bytes and {@code NUMERIC(12,2)}. The two
     * precisions are never unified in either direction, and the projection carrying that one states
     * the same thing on its own column. It is named here so the distinction can be asserted from the
     * shared codec rather than asserted as a literal.</p>
     */
    private static final int ACCOUNT_BALANCE_PRECISION = 12;

    /**
     * The number of integer digit positions the statement amount declares, nine.
     */
    private static final int STATEMENT_AMOUNT_INTEGER_DIGITS = 9;

    /**
     * The number of integer digit positions the account balance declares, ten.
     */
    private static final int ACCOUNT_BALANCE_INTEGER_DIGITS = 10;

    /**
     * The number of decimal digit positions every money field in this migration declares, two.
     */
    private static final int MONEY_DECIMAL_DIGITS = 2;

    /**
     * The width of the keyed per-card fingerprint the relation publishes, sixty-four characters.
     *
     * <p>Assumptions: the projection derives it as a hexadecimal rendering of a 256-bit digest, so it
     * is 64 characters, and the interface documents that width on the parameter it takes.</p>
     */
    private static final int FINGERPRINT_WIDTH = 64;

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
     * <p>Assumptions: naming the whole set is stronger than asserting one absence, because it fails
     * on a method removed as well as on one added, and two absences the reference supports are
     * pinned by it at once. There is no keyed single-row read: the {@code 'TRNXFILE'} branch of
     * {@code app/cbl/CBSTM03B.CBL} at L133 through L155 implements an open arm at L135, a plain read
     * at L140 and a close at L146, with no keyed-read arm and sequential access declared at L33.
     * And there is no whole-table cursor: the interface's own Javadoc records that one was declared,
     * was reached by nothing and was withdrawn.</p>
     *
     * <p>Refactoring Rationale: the set gained {@code findWindowForCardGroup}, the bulk window that
     * reads a CHUNK of cards in one statement. It is the fourth read and not a replacement for the
     * third: the per-card window remains the one a single-card request uses, where a group predicate
     * over one fingerprint would be the same read with a wider predicate. What the fourth removes is
     * the whole-run pass issuing one query per cardholder -- so the set grew because the module gained
     * a read, which is exactly the kind of change this assertion is meant to make visible.</p>
     */
    private static final Set<String> DECLARED_READ_METHOD_NAMES = Set.of(
            "streamByCardFingerprint", "aggregateByCardFingerprint", "findWindowByCardFingerprint",
            "findWindowForCardGroup");

    /**
     * The mapping annotations whose absence proves the four-participant join is composed by query.
     *
     * <p>Assumptions: the statement generator reads four data definitions -- {@code TRNXFILE} at
     * {@code app/jcl/CREASTMT.JCL} L83, {@code XREFFILE} at L84, {@code ACCTFILE} at L85 and
     * {@code CUSTFILE} at L86 -- and it joins them in working storage rather than through a declared
     * relationship. The migrated projection carries no mapped association for the same reason: a
     * relationship would make one projection reach into another's relation, which is what routing
     * this context through separate views exists to prevent.</p>
     */
    private static final List<Class<? extends Annotation>> ASSOCIATION_ANNOTATIONS = List.of(
            OneToOne.class, OneToMany.class, ManyToOne.class, ManyToMany.class,
            JoinColumn.class, JoinTable.class, ElementCollection.class);

    /**
     * The projections a mapped association would have to name, and which this one must not.
     */
    private static final List<Class<?>> SIBLING_PROJECTIONS = List.of(
            CardXrefView.class, AccountView.class, CustomerView.class);

    /**
     * The projection this class reads, schema-qualified.
     */
    private static final String STATEMENT_VIEW = "reporting.v_statement_transactions";

    /**
     * The file that OWNS the projection, named in every arrangement failure about it.
     */
    private static final String STATEMENT_VIEW_OWNER = "data-migration/sql/V1__reporting_views.sql";

    /**
     * The base relation beneath the projection, schema-qualified.
     */
    private static final String TRANSACTION_TABLE = "ledger.transactions";

    /**
     * The file that OWNS the base relation and both of its indexes.
     */
    private static final String TRANSACTION_TABLE_OWNER =
            "services/transaction-service/src/main/resources/db/migration/V1__ledger.sql";

    /**
     * The cross-reference relation the per-card lookup resolves against, schema-qualified.
     */
    private static final String CROSS_REFERENCE_TABLE = "account.card_xref";

    /**
     * The file that OWNS the cross-reference relation.
     */
    private static final String CROSS_REFERENCE_TABLE_OWNER =
            "services/account-service/src/main/resources/db/migration/V1__account.sql";

    /**
     * The two ordinary tables the projection schema is expected to hold, in catalog name order.
     *
     * <p>Assumptions: {@code data-migration/sql/V1__reporting_views.sql} creates exactly these two
     * tables in that schema and every other relation it creates is a view. Naming them is what lets the
     * assertion below distinguish "the schema holds no second copy of the transaction rows" from "the
     * schema holds no table at all", which would be false.</p>
     *
     * <p>Refactoring Rationale: this was one name and is now two, because the identity relation joined
     * the grouping key there. The point of the assertion is unchanged and the second name does not
     * weaken it: {@code card_grouping_key} holds one row of key material and {@code card_identity}
     * holds one row per CARD, so neither is a copy of the transaction rows, and the case below proves
     * that of the new one by cardinality and by column list rather than by asserting it here.</p>
     */
    private static final List<String> PROJECTION_SCHEMA_TABLES =
            List.of("card_grouping_key", "card_identity");

    /**
     * The identity relation the statement walk is served from, schema-qualified.
     */
    private static final String CARD_IDENTITY_TABLE = "reporting.card_identity";

    /**
     * The cross-reference relation the identity relation is derived from, schema-qualified.
     */
    private static final String CARD_XREF_TABLE = "account.card_xref";

    /**
     * The three columns the identity relation is allowed to carry.
     *
     * <p>Assumptions: naming the whole column list, rather than asserting the absence of a column that
     * would be wrong, is what makes the check total. A relation that acquired an amount, a timestamp or
     * a customer attribute would become a partial copy of data another context owns, and the failure
     * mode of a negative assertion is that it passes for every column nobody thought of.</p>
     */
    private static final List<String> CARD_IDENTITY_COLUMNS =
            List.of("card_fingerprint", "card_num", "card_num_masked");

    /**
     * The schema the projections live in.
     */
    private static final String PROJECTION_SCHEMA = "reporting";

    /**
     * The catalog code the engine reports for an ordinary view.
     */
    private static final String VIEW_RELATION_KIND = "v";

    /**
     * The index on the base relation every statement read must reach the transactions through.
     *
     * <p>Assumptions: it is created by {@link #TRANSACTION_TABLE_OWNER} at its L288 and this class
     * neither creates nor drops it. Register entry <b>R8</b> records that the reference's separate
     * index-rebuild step retires because the engine maintains an index transactionally as rows change,
     * while the access path the step produced survives.</p>
     *
     * <p>Refactoring Rationale: this replaces a list of index NAMES that the class asserted merely
     * EXISTED. Existence was the wrong property and the assertion was worse than absent: a statement
     * read selects by fingerprint, so it reached this index only if the engine could resolve the
     * fingerprint to a card number first, and while the fingerprint was a computed expression it could
     * not -- every read was a sequential scan of the ledger with both indexes present and unused. A
     * name census cannot see that. The cases below assert instead that the chosen PLAN uses this
     * index, at a cardinality where a sequential scan is what the engine would otherwise
     * prefer.</p>
     */
    private static final String LEDGER_CARD_INDEX = "idx_transactions_card_num";

    /**
     * The identity relation's primary key, which every fingerprint lookup must be resolved through.
     */
    private static final String CARD_IDENTITY_PRIMARY_KEY = "pk_card_identity";

    /**
     * The scan descriptions a statement read's plan must not contain at production cardinality.
     *
     * <p>Assumptions: the engine prints an unqualified relation name in a scan node, and prefixes the
     * node with {@code Parallel} when it splits the scan across workers -- so matching on
     * {@code "Seq Scan on <relation>"} would pass on a parallel sequential scan, which is the same
     * defect with more processes. Matching on the relation and the words that precede it in both forms
     * is what closes that. The identity relation is named as well as the two base relations: a walk
     * that scanned it would be reading the whole portfolio to answer for one card.</p>
     */
    private static final List<String> FORBIDDEN_PLAN_SCANS = List.of(
            "Seq Scan on transactions", "Seq Scan on card_xref", "Seq Scan on card_identity");

    /**
     * The unqualified name of the projection every statement read names, used to select its statement.
     */
    private static final String STATEMENT_VIEW_RELATION = "v_statement_transactions";

    /**
     * The leading digit group the plan cases seed cards and transaction identifiers with.
     *
     * <p>Assumptions: the committed fixture leads with {@code 0500}, {@code 4859} and {@code 9900} in
     * both columns, so this group cannot collide with it. The value is a marker rather than a plausible
     * card number precisely so that a residue left behind by a failure is recognisable at a glance.</p>
     */
    private static final String PLAN_CARD_PREFIX = "1700";

    /**
     * How many cards the plan cases seed, five thousand.
     *
     * <p>Assumptions: the size is chosen so that a sequential scan is what the engine would pick for a
     * predicate it cannot index, which is the condition under which "no sequential scan" says
     * something. It is also small enough that the seeding is two statements and a few hundred
     * milliseconds, so the case does not dominate the module's build.</p>
     */
    private static final int PLAN_CARD_COUNT = 5_000;

    /**
     * How many transactions the plan cases seed, fifty thousand -- ten per seeded card.
     *
     * <p>Assumptions: ten per card is what makes the per-card access path matter. With one transaction
     * per card the engine could reach the same rows by scanning either relation, so a plan that
     * happened to be indexed would not be evidence that the index was necessary.</p>
     */
    private static final int PLAN_TRANSACTION_COUNT = 50_000;

    /**
     * The window size the plan cases request, one hundred.
     */
    private static final int PLAN_WINDOW_LIMIT = 100;

    /**
     * The continuation anchor the plan cases pass, a transaction identifier inside the seeded range.
     *
     * <p>Assumptions: the value only has to be a plausible identifier, because the plan is taken with
     * the parameters unknown -- what the continuation call establishes is that it issues the same
     * statement text as the opening call, not what that value selects.</p>
     */
    private static final String PLAN_CONTINUATION_ANCHOR = "1700000000000500";


    /**
     * The container the context connects to, started once for this class.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text, because the container's port is assigned as it starts -- so committed text could
     * not be correct, and the failure mode of the mistake is not an error but a class that passes
     * against whichever engine happened to be listening. {@code application-test.yml} declares no
     * location, no login name and no credential precisely so that these arrive this way.</p>
     *
     * <p>Assumptions: the declaration carries no type argument because the 2.x container type takes
     * none -- it declares itself as extending the JDBC container parameterised by ITSELF, so a
     * wildcard here would not compile. The dependency carries no version either: the aggregator
     * imports the container bill of materials and this module declares coordinates only.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The cursor under test, injected so every read goes through the declared query.
     */
    @Autowired
    private StatementTransactionRepository transactions;

    /**
     * The entity manager, used only for the two counts the projection identity assertion needs.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Every published card, resolved to its narrowed rendering and its fingerprint, ascending.
     */
    private static List<ResolvedCard> publishedCards;

    /**
     * The card carrying the most rows in the fixture, which is the one that exceeds two thresholds.
     */
    private static ResolvedCard busiestCard;

    /**
     * The card whose identifier order and processing-date order disagree, derived from the fixture.
     */
    private static ResolvedCard splitOrderCard;

    /**
     * The fixture's rows grouped by narrowed card rendering, each group in identifier order.
     */
    private static Map<String, List<FixtureRow>> expectedRows;

    /**
     * One published card, as the projection renders it and as the per-card query selects it.
     *
     * <p>Assumptions: the two components are different things and both are needed. The rendering is
     * what the projection publishes and what every message here prints; the fingerprint is what the
     * query selects on, and the interface documents that a request-time caller obtains it from the
     * definer-rights lookup rather than deriving it, because the key it is derived from is
     * unreadable from this context.</p>
     *
     * @param narrowedRendering the card as the projection publishes it, twelve asterisks followed by
     *     the last four digits
     * @param fingerprint the keyed per-card fingerprint, sixty-four hexadecimal characters
     */
    private record ResolvedCard(String narrowedRendering, String fingerprint) {
    }

    /**
     * One decoded fixture row, reduced to the three values the assertions here compare.
     *
     * @param transactionId the sixteen-character transaction identifier, which is the ordering key
     * @param amount the exact decimal amount at a scale of two, decoded through the shared codec
     * @param processingTimestamp the twenty-six-character processing timestamp as the record carries
     *     it
     */
    private record FixtureRow(String transactionId, BigDecimal amount, String processingTimestamp) {
    }

    /**
     * Applies the shipped definitions, loads the fixture corpus and resolves every published card.
     *
     * <p>Assumptions: the container is already running when this runs, and that is a framework
     * guarantee rather than an assumption about extension ordering -- every {@code BeforeAllCallback}
     * extension is invoked before a {@code @BeforeAll} method, so the container annotation's
     * extension has started it whichever order the two class-level extensions were registered in. The
     * Spring context is built when the first test instance is prepared, which is after this method,
     * so the relations and the rows exist before the pool opens its first connection.</p>
     *
     * <p>Assumptions: nothing here needs to precede the pool for correctness in any case, and the
     * reason is worth recording so a future edit does not treat the ordering as fragile.
     * {@code application-test.yml} pins schema resolution to the single {@code reporting} schema, and
     * a search-path entry naming a schema that does not yet exist is accepted and simply resolves
     * nothing until it does. Its {@code ddl-auto} is {@code none}, so the provider emits no
     * schema-generation statement that could fabricate the very relations this class must not
     * create.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @BeforeAll
    static void arrangeTheDeployedSchemaAndFixtureCorpus() {
        applyDeployedSchema();
        expectedRows = expectedRowsByCard();
        loadFixtureCorpus();
        busiestCard = cardCarryingTheMostRows();
        splitOrderCard = cardWhoseIdentifierOrderDiffersFromItsDateOrder();
    }

    /**
     * Confirms the record is three hundred and fifty bytes with a thirty-two byte composite identity.
     *
     * <p>Assumptions: the length and every offset are derived by SUMMING the declared field widths of
     * {@code app/cpy/COSTM01.CPY} L22 through L36 and never by trusting the banner above them,
     * because a banner is a comment and a comment cannot be wrong in a way a compiler notices. The
     * sum is corroborated independently by the file section of {@code app/cbl/CBSTM03B.CBL}, whose
     * {@code FD TRNX-FILE} at L58 declares a 32-byte key group at L60 through L62 followed by a
     * 318-byte remainder at L63, and a third time by the measured length of every row in the
     * committed fixture.</p>
     *
     * <p>Assumptions: the descriptor records a ZERO-based start while the copybook and every citation
     * in this repository count bytes from one, so the two differ by exactly one and the conversion is
     * applied here rather than left implicit. The rule is that a zero-based start plus one is the
     * one-based first byte, and that a start plus a length is the one-based last byte. Applying it
     * backwards shifts every field by a byte and yields plausible-looking values rather than a
     * failure.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the statement record is 350 bytes with a 32-byte composite identity")
    void theStatementRecordIsThreeHundredAndFiftyBytesWithAThirtyTwoByteIdentity() {
        // WHY : Assumptions: the descriptor is the single place these offsets are declared for the
        //       whole migration, so reading them from it rather than from a second table in this
        //       class is what keeps the fixture load below and these assertions from drifting apart.
        //       Provenance: app/cpy/COSTM01.CPY L20 declares 01 TRNX-RECORD and app/cbl/CBSTM03B.CBL
        //       L58 declares the file it is read through.
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(TRANSACTION_DESCRIPTOR);

        assertThat(spec.reclen())
                .as("the declared record length")
                .isEqualTo(TRANSACTION_RECORD_LENGTH);
        assertThat(spec.keyLength())
                .as("the composite identity spans the card number and the transaction identifier")
                .isEqualTo(COMPOSITE_KEY_WIDTH);
        assertThat(spec.keyOffset())
                .as("the composite identity begins at the first byte of the record")
                .isZero();
        assertThat(spec.field(CARD_NUMBER_FIELD).length())
                .as("the card number occupies its declared width")
                .isEqualTo(KEY_COMPONENT_WIDTH);
        assertThat(spec.field(TRANSACTION_ID_FIELD).length())
                .as("the transaction identifier occupies its declared width")
                .isEqualTo(KEY_COMPONENT_WIDTH);
        assertThat(spec.field(TRANSACTION_ID_FIELD).start())
                .as("the identifier begins where the card number ends, with no gap between them")
                .isEqualTo(spec.field(CARD_NUMBER_FIELD).length());
        assertThat(spec.field(TRANSACTION_ID_FIELD).end())
                .as("so the two components together fill the identity and nothing else does")
                .isEqualTo(COMPOSITE_KEY_WIDTH);

        CopybookLayout.FieldSpec processingTimestamp = spec.field(PROCESSING_TIMESTAMP_FIELD);
        assertThat(processingTimestamp.start() + 1)
                .as("the processing timestamp begins at its one-based first byte")
                .isEqualTo(PROCESSING_TIMESTAMP_FIRST_BYTE);
        assertThat(processingTimestamp.end())
                .as("the processing timestamp ends at its one-based last byte")
                .isEqualTo(PROCESSING_TIMESTAMP_LAST_BYTE);

        // WHY : Assumptions: the pad exists only to carry the record to its declared length, so a
        //       projection member for it would publish padding as data. app/cpy/COSTM01.CPY L36
        //       declares FILLER PIC X(20) as the last field, and the shared codec omits registered
        //       padding whose content is inert, so the decoded row carries no entry for it while the
        //       descriptor still describes the span. Both halves are asserted because the descriptor
        //       and the decoded row are different artifacts.
        assertThat(spec.field(PADDING_FIELD).start() + 1)
                .as("the trailing pad begins immediately after the processing timestamp")
                .isEqualTo(TRAILING_PAD_FIRST_BYTE);
        assertThat(spec.field(PADDING_FIELD).end())
                .as("the trailing pad ends at the declared record length")
                .isEqualTo(TRANSACTION_RECORD_LENGTH);
        assertThat(decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR).getFirst())
                .withFailMessage("the decoded row must carry no member for the trailing pad; the pad"
                        + " exists only to reach the 350-byte declared length, so a member for it"
                        + " would publish padding as data")
                .doesNotContainKey(PADDING_FIELD);
        assertThat(Arrays.stream(StatementTransactionView.class.getDeclaredFields())
                        .map(Field::getName)
                        .toList())
                .as("the projection declares no member named for the pad either")
                .doesNotContain(PADDING_FIELD.toLowerCase(Locale.ROOT));
    }

    /**
     * Confirms the statement and posting layouts are separate geometries and not two names for one.
     *
     * <p>Assumptions: this is the assertion that would catch the single most damaging silent mistake
     * available in this migration. Both records are 350 bytes, both carry a card number, a
     * transaction identifier and an amount, and both are transaction rows -- so decoding one against
     * the other's descriptor produces a wrong value in every field while every row remains exactly
     * 350 bytes, which no length check can see. The cause of the difference is physical:
     * {@code app/jcl/CREASTMT.JCL} L54 is
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, which lifts the 16-byte card number from
     * one-based 263 to position 1 and block-shifts the original first 262 bytes to position 17. The
     * consequence is that the amount sits sixteen bytes earlier in the posting layout than in this
     * one, so a unified reading would decode every amount from the sixteen bytes preceding it.</p>
     *
     * <p>Assumptions: the processing timestamp occupies one-based 305 through 330 in BOTH layouts,
     * and that coincidence is not evidence of an alias. The card number is at one-based 1 through 16
     * here and at 263 through 278 there, per {@code app/cpy/CVTRA05Y.cpy} L15, so the two layouts
     * disagree about the field the coincidence sits between.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the statement and posting layouts are separate geometries, never aliases")
    void theStatementAndPostingLayoutsAreSeparateGeometriesAndNotAliases() {
        CopybookLayout.RecordSpec statement = CopybookLayout.layout(TRANSACTION_DESCRIPTOR);
        CopybookLayout.RecordSpec posting = CopybookLayout.layout(POSTING_DESCRIPTOR);
        CopybookLayout.FieldSpec statementAmount = statement.field(AMOUNT_FIELD);
        CopybookLayout.FieldSpec postingAmount = posting.field(POSTING_AMOUNT_FIELD);

        assertThat(statementAmount.start() + 1)
                .as("the statement amount begins at its one-based first byte")
                .isEqualTo(STATEMENT_AMOUNT_FIRST_BYTE);
        assertThat(statementAmount.end())
                .as("the statement amount ends at its one-based last byte")
                .isEqualTo(STATEMENT_AMOUNT_LAST_BYTE);
        assertThat(postingAmount.start() + 1)
                .withFailMessage("the posting amount must begin at one-based %d while the statement"
                        + " amount begins at one-based %d; the two layouts are separate geometries"
                        + " and unifying them would read every statement amount sixteen bytes early",
                        POSTING_AMOUNT_FIRST_BYTE, STATEMENT_AMOUNT_FIRST_BYTE)
                .isEqualTo(POSTING_AMOUNT_FIRST_BYTE);
        assertThat(STATEMENT_AMOUNT_FIRST_BYTE - POSTING_AMOUNT_FIRST_BYTE)
                .as("the displacement is exactly the width of the card number that moved")
                .isEqualTo(KEY_COMPONENT_WIDTH);

        assertThat(statement.field(CARD_NUMBER_FIELD).start() + 1)
                .as("the statement card number leads the record")
                .isEqualTo(1);
        assertThat(posting.field(POSTING_CARD_NUMBER_FIELD).start() + 1)
                .as("the posting card number sits far into the record instead")
                .isEqualTo(POSTING_CARD_NUMBER_FIRST_BYTE);

        // WHY : Assumptions: both layouts place the processing timestamp at one-based 305 through
        //       330 -- app/cpy/COSTM01.CPY L35 and app/cpy/CVTRA05Y.cpy L17 -- and a reader who
        //       checked only that field would conclude the two records were one. Asserting the
        //       coincidence alongside the disagreement above is what keeps it from being read as
        //       evidence of an alias.
        assertThat(posting.field(POSTING_TIMESTAMP_FIELD).start() + 1)
                .as("the posting processing timestamp coincidentally shares its one-based span")
                .isEqualTo(PROCESSING_TIMESTAMP_FIRST_BYTE);
        assertThat(posting.field(POSTING_TIMESTAMP_FIELD).end())
                .as("and it ends at the same one-based byte as well")
                .isEqualTo(PROCESSING_TIMESTAMP_LAST_BYTE);
        assertThat(posting.reclen())
                .as("both records are the same declared length, which is why length cannot separate"
                        + " them")
                .isEqualTo(statement.reclen());
    }


    /**
     * Confirms the four statement-path definitions measure their declared record lengths.
     *
     * <p>Assumptions: the statement generator reads FOUR data definitions and this class is the only
     * artifact in the module that can measure all four, because it is the only consumer of the
     * transaction fixture. The definitions are {@code TRNXFILE} at {@code app/jcl/CREASTMT.JCL} L83,
     * {@code XREFFILE} at L84, {@code ACCTFILE} at L85 and {@code CUSTFILE} at L86 -- that order
     * verified against the job rather than recalled, because an inverted reading of the last two
     * circulates and would attribute 300 bytes to the customer record and 500 to the account
     * record.</p>
     *
     * <p>Assumptions: each length is summed from the file section of {@code app/cbl/CBSTM03B.CBL} --
     * 16 plus 16 plus 318 at L58, 16 plus 34 at L65, 9 plus 491 at L70 and 11 plus 289 at L75 -- and
     * measured independently against the committed fixture, so the two sources have to agree. One
     * baseline artifact is recorded here because a reader summing those widths will meet it: the name
     * {@code FD-ACCT-DATA} is declared TWICE with two different widths, 318 bytes at L63 inside the
     * transaction record and 289 bytes at L78 inside the account record. They are separate group
     * items in separate record descriptions, so the baseline behaves exactly as written and both sums
     * hold. The baseline declares the name twice, the target carries two distinct projections, and
     * the divergence is documented rather than resolved here.</p>
     *
     * <p>Assumptions: the account and customer fixtures are DECODED here and deliberately not
     * inserted anywhere. Nothing this class reads joins them -- the projection reads the transaction
     * relation cross joined with a one-row key table, and the per-card lookup reads the
     * cross-reference relation -- so loading four account rows and four customer rows would be
     * arrangement no assertion here reaches. The engine-level customer and account legs of the
     * statement join belong to {@code StatementCardXrefRepositoryIT}.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the four statement-path definitions measure 350, 50, 300 and 500 bytes")
    void theFourStatementPathDefinitionsMeasureTheirDeclaredRecordLengths() {
        // WHY : Assumptions: the four are bound by EXACT name because two of them have near
        //       neighbours that would silently substitute. xreffile.txt is the statement path's
        //       XREFFILE at app/jcl/CREASTMT.JCL L84 while cardxref.txt is the report path's CARDXREF
        //       at app/jcl/TRANREPT.jcl L67, and trnxfile.txt is the statement geometry while
        //       tranfile.txt is the posting one -- and within each pair the record length is
        //       identical, so a swap changes the values and not the shape.
        Map<String, String> boundFixtures = new LinkedHashMap<>();
        boundFixtures.put(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR);
        boundFixtures.put(CROSS_REFERENCE_FIXTURE, CROSS_REFERENCE_DESCRIPTOR);
        boundFixtures.put(ACCOUNT_FIXTURE, ACCOUNT_DESCRIPTOR);
        boundFixtures.put(CUSTOMER_FIXTURE, CUSTOMER_DESCRIPTOR);

        Map<String, Integer> declaredLengths = new LinkedHashMap<>();
        declaredLengths.put(TRANSACTION_DESCRIPTOR, TRANSACTION_RECORD_LENGTH);
        declaredLengths.put(CROSS_REFERENCE_DESCRIPTOR, CROSS_REFERENCE_RECORD_LENGTH);
        declaredLengths.put(ACCOUNT_DESCRIPTOR, ACCOUNT_RECORD_LENGTH);
        declaredLengths.put(CUSTOMER_DESCRIPTOR, CUSTOMER_RECORD_LENGTH);

        for (Map.Entry<String, String> bound : boundFixtures.entrySet()) {
            String descriptorName = bound.getValue();
            int declared = declaredLengths.get(descriptorName);
            assertThat(CopybookLayout.layout(descriptorName).reclen())
                    .withFailMessage("the descriptor %s must declare %d bytes, which is the sum of"
                            + " the widths its record description in app/cbl/CBSTM03B.CBL declares",
                            descriptorName, declared)
                    .isEqualTo(declared);
            for (String row : rawLinesOf(bound.getKey())) {
                assertThat(row.length())
                        .withFailMessage("every row of %s must measure the %d bytes its definition"
                                + " declares; the newline is a file convention that is not part of"
                                + " any record", bound.getKey(), declared)
                        .isEqualTo(declared);
            }
        }

        assertThat(CopybookLayout.layout(CROSS_REFERENCE_DESCRIPTOR).keyLength())
                .as("the cross-reference identity is the card number alone")
                .isEqualTo(KEY_COMPONENT_WIDTH);
        assertThat(CopybookLayout.layout(TRANSACTION_DESCRIPTOR).keyLength())
                .as("the transaction identity is the card number and the identifier together")
                .isEqualTo(COMPOSITE_KEY_WIDTH);
    }

    /**
     * Confirms every card the transaction fixture carries resolves in the cross-reference fixture.
     *
     * <p>Assumptions: an unresolvable dimension is an abort and never a row to pass over, which is
     * the reference's own behaviour rather than a policy invented for the migration. The customer
     * read at {@code app/cbl/CBSTM03A.CBL} L379 through L386 and the account read at L403 through
     * L410 have no not-found arm at all: their {@code WHEN OTHER} arms perform the abend paragraph at
     * L921. Register entry <b>R10</b> records the decision. This case therefore asserts the fixture
     * corpus satisfies that precondition for the transaction-to-cross-reference leg, which makes the
     * abort path unreachable on this data rather than merely untested. Only this class reads the
     * transaction fixture, so no sibling can assert this leg; the customer and account legs are
     * asserted at engine level by {@code StatementCardXrefRepositoryIT}.</p>
     *
     * <p>Assumptions: the resolution is additionally exercised through the shipped lookup rather than
     * only in Java, because the lookup is what a request-time caller uses and it answers nothing for
     * a card that does not exist. Every one of the published cards resolved during arrangement or the
     * arrangement itself would have failed, so this case asserts the SHAPE of what it returned: a
     * sixty-four character fingerprint per card, all distinct, and a rendering that publishes only
     * the last four digits.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every card the transaction fixture carries resolves in the cross-reference")
    void everyCardTheTransactionFixtureCarriesResolvesInTheCrossReference() {
        // WHY : Assumptions: the driving loop at app/cbl/CBSTM03A.CBL L316 through L329 reads the
        //       cross-reference and then reads the dimensions for the card it yielded, so a
        //       transaction whose card is absent from the cross-reference could never be reached at
        //       all -- the run would end at L921 rather than skip it. Comparing the two fixtures is
        //       what makes that unreachability a measured property of this corpus.
        Set<String> crossReferenced = new LinkedHashSet<>(
                decodeAll(CROSS_REFERENCE_FIXTURE, CROSS_REFERENCE_DESCRIPTOR).stream()
                        .map(row -> narrowedRenderingOf(text(row, CROSS_REFERENCE_CARD_FIELD)))
                        .toList());

        assertThat(crossReferenced)
                .as("the cross-reference fixture publishes the expected number of distinct cards")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(crossReferenced)
                .withFailMessage("every card the transaction fixture carries must appear in the"
                        + " cross-reference fixture; %s does not, and the reference treats an"
                        + " unresolvable dimension as an abort at app/cbl/CBSTM03A.CBL L921 rather"
                        + " than as a row to skip", expectedRows.keySet())
                .containsAll(expectedRows.keySet());

        assertThat(publishedCards)
                .as("every published card resolved through the shipped lookup")
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(publishedCards.stream().map(ResolvedCard::fingerprint).toList())
                .as("the per-card fingerprint identifies each card exactly")
                .doesNotHaveDuplicates();
        assertThat(publishedCards)
                .allSatisfy(card -> {
                    assertThat(card.fingerprint())
                            .as("the fingerprint is a hexadecimal rendering of a 256-bit digest")
                            .hasSize(FINGERPRINT_WIDTH);
                    // WHY : Assumptions: the shape is checked against the authority's published
                    //       expression rather than against a leading-asterisk test. A prefix test
                    //       passes on a rendering whose masked region is the right length but whose
                    //       tail is not four digits, and it passes on a value longer than the
                    //       declared width -- so it admits renderings the deployment could not have
                    //       produced. The expression pins both the masked run and the digit tail.
                    assertThat(card.narrowedRendering())
                            .as("the rendering publishes only the last four digits")
                            .matches(CardNumberMasker.MASKED_FORM_PATTERN)
                            .hasSize(KEY_COMPONENT_WIDTH);
                });
    }

    /**
     * Confirms one card streams six hundred rows, past the reference threshold and past the batch.
     *
     * <p>Assumptions: the fixture deliberately EXCEEDS the reference's measured same-card threshold,
     * and that is the point of its size rather than an accident of it. The baseline holds a run in
     * declared-width working storage -- {@code app/cbl/CBSTM03A.CBL} L228 declares
     * {@value #REFERENCE_DECLARED_TRANSACTION_TABLE_ARITY} entries per card and
     * {@code tests/README.md} L70 through L82 records that a card renders up to
     * {@value #REFERENCE_MEASURED_SAME_CARD_THRESHOLD} transactions before the 513th overruns that
     * table -- while the Java holds one row at a time behind a retrieval batch and declares no
     * ceiling of any kind. The baseline does one thing, the Java does another, and the divergence is
     * registered in {@code docs/architecture/cobol-to-service-traceability.md} as divergence D-2,
     * cited here and not redefined.</p>
     *
     * <p>Assumptions: the assertion is on CORRECT OUTPUT and never on an exception. A row count, an
     * order and an exact total are what a statement is made of, so those are what is checked; there
     * is no threshold in the migrated path for a probe to trip.</p>
     *
     * <p>Trade-offs: this case also carries the sharpest available proof that the declared retrieval
     * batch is not a cap, and it can only do so because the two figures happen to be comparable. The
     * batch is {@value StatementTransactionRepository#STATEMENT_FETCH_SIZE} and this card carries
     * {@value #BUSIEST_CARD_ROW_COUNT} rows, so a batch mistaken for a ceiling would return the
     * batch. What that costs is a coupling to the interface's chosen batch value; what it buys is
     * that the distinction between rows in flight and rows available is asserted rather than
     * asserted about.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("one card streams all 600 of its rows, past the reference threshold and the batch")
    void oneCardStreamsAllOfItsRowsPastTheReferenceThresholdAndTheBatch() {
        // WHY : Assumptions: this stands in for the same-card accumulation the reference performs in
        //       working storage, whose control break is at app/cbl/CBSTM03A.CBL L819 through L825.
        //       Selecting the run in the query instead means a caller composing one card's statement
        //       reads that card's rows and no others, so the row count here is the statement's line
        //       count and a lost row is a line missing from a document.
        List<StatementTransactionView> walked = walkCard(busiestCard);
        List<FixtureRow> expected = expectedRows.get(busiestCard.narrowedRendering());

        assertThat(expected)
                .withFailMessage("the committed fixture must carry %d rows on its busiest card, or"
                        + " neither the threshold comparison nor the batch comparison below asserts"
                        + " anything", BUSIEST_CARD_ROW_COUNT)
                .hasSize(BUSIEST_CARD_ROW_COUNT);
        assertThat(BUSIEST_CARD_ROW_COUNT)
                .as("the fixture carries more same-card rows than the reference could render")
                .isGreaterThan(REFERENCE_MEASURED_SAME_CARD_THRESHOLD);
        assertThat(walked)
                .withFailMessage("the cursor must yield every one of the card's %d rows; the"
                        + " reference could hold only %d of them in its inner table, and the target"
                        + " declares no arity at all", BUSIEST_CARD_ROW_COUNT,
                        REFERENCE_MEASURED_SAME_CARD_THRESHOLD)
                .hasSize(BUSIEST_CARD_ROW_COUNT);

        assertThat(walked.size())
                .withFailMessage("the cursor must yield more rows than the declared retrieval batch"
                        + " of %d; a walk that stopped there would mean the batch had been read as a"
                        + " ceiling on a statement rather than as rows in flight per round trip",
                        DECLARED_FETCH_SIZE)
                .isGreaterThan(DECLARED_FETCH_SIZE);
        assertThat(walked)
                .allSatisfy(row -> {
                    assertThat(row.cardFingerprint())
                            .as("every row belongs to the card the query selected")
                            .isEqualTo(busiestCard.fingerprint());
                    assertThat(row.key().cardNumber().stripTrailing())
                            .as("and carries that card's published rendering")
                            .isEqualTo(busiestCard.narrowedRendering());
                });
        assertThat(walked.stream().map(row -> row.key().transactionId().stripTrailing()).toList())
                .as("in ascending transaction-identifier order, which is the second sort key")
                .containsExactlyElementsOf(expected.stream().map(FixtureRow::transactionId).toList());
    }

    /**
     * Confirms the traversal covers more distinct cards than the reference's outer table declares.
     *
     * <p>Assumptions: three figures must be kept apart and this case asserts against exactly one of
     * them. {@code app/cbl/CBSTM03A.CBL} L226 declares
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} with an independent companion counter table at L232 of
     * the same arity, and that arity bounds DISTINCT CARDS -- the 52nd overruns it. L228 declares an
     * inner arity of {@value #REFERENCE_DECLARED_TRANSACTION_TABLE_ARITY} entries per card. The
     * measured same-card overrun of {@value #REFERENCE_MEASURED_SAME_CARD_THRESHOLD} is a third
     * figure on the other axis. There is no one combined ceiling, and reading the distinct-card
     * figure as a transaction count would mislabel two separate limits as one.</p>
     *
     * <p>Assumptions: the union of the per-card walks is asserted to be the whole relation, so this
     * case establishes coverage rather than merely a count. A run that reached
     * {@value #PUBLISHED_CARD_COUNT} cards while losing rows from one of them would satisfy a count
     * assertion and would still produce a short statement.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the traversal covers 88 distinct cards, past the reference's outer table arity")
    void theTraversalCoversMoreDistinctCardsThanTheReferenceOuterTableDeclares() {
        // WHY : Assumptions: the reference accumulates a run in two 51-entry tables -- the card table
        //       at app/cbl/CBSTM03A.CBL L226 and the counter table at L232 -- so it cannot hold more
        //       than that many distinct cards in one run, and this corpus deliberately holds more.
        //       Walking every card rather than counting them is what shows the absence of the outer
        //       ceiling in the same way the case above shows the absence of the inner one.
        List<String> reached = new ArrayList<>();
        int rowsWalked = 0;
        for (ResolvedCard card : publishedCards) {
            List<StatementTransactionView> run = walkCard(card);
            reached.add(card.narrowedRendering());
            rowsWalked += run.size();
            assertThat(run)
                    .withFailMessage("card %s must yield exactly the rows the fixture carries for"
                            + " it", card.narrowedRendering())
                    .hasSize(expectedRows.getOrDefault(card.narrowedRendering(), List.of()).size());
        }

        assertThat(reached)
                .as("every published card was reached exactly once")
                .doesNotHaveDuplicates()
                .hasSize(PUBLISHED_CARD_COUNT);
        assertThat(PUBLISHED_CARD_COUNT)
                .withFailMessage("the corpus must publish more distinct cards than the reference's"
                        + " declared card-table arity of %d, or this case asserts nothing about the"
                        + " absence of an outer ceiling", REFERENCE_DECLARED_CARD_TABLE_ARITY)
                .isGreaterThan(REFERENCE_DECLARED_CARD_TABLE_ARITY);
        assertThat(rowsWalked)
                .withFailMessage("the per-card walks together must cover the whole relation of %d"
                        + " rows; they covered %d, so a card's run was truncated even though every"
                        + " card was reached", TRANSACTION_ROW_COUNT, rowsWalked)
                .isEqualTo(TRANSACTION_ROW_COUNT);
    }


    /**
     * Confirms a walk composed card by card reproduces the job's two-key order exactly.
     *
     * <p>Assumptions: the order is the reference's own and not a preference.
     * {@code app/jcl/CREASTMT.JCL} L53 is {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} -- two keys,
     * both character, both ascending, the card number at one-based 263 for sixteen bytes and then the
     * transaction identifier at one-based 1 for sixteen -- so two keys make the order TOTAL and
     * reproducing it is reproduction rather than invention. Register entry <b>R6</b> holds the wider
     * decision and contrasts it with the report path, whose single-key sort at
     * {@code app/jcl/TRANREPT.jcl} L46 carries no equal-records qualifier and is therefore not total.
     * The two must not be conflated: they are different declarations in different jobs and only one
     * of them is already deterministic.</p>
     *
     * <p>Assumptions: the expected sequence is derived from the FIXTURE rather than read back from
     * the engine with the same ordering. Reading it back would compare the engine against itself;
     * deriving it applies the ordering rule independently, so the case fails if either component of
     * the order stopped being applied.</p>
     *
     * <p>Assumptions: a natural string comparison in Java is a faithful stand-in for the engine's
     * ordering over these particular values, and the reason is specific rather than general. Each
     * compared element is a fixed-width pair -- a sixteen-character rendering made of twelve
     * asterisks and four ASCII digits, then a separator, then a sixteen-digit identifier -- so the
     * comparison reduces to digits in every position that can differ, and the identifier column is
     * additionally pinned to the bytewise collation by the third ledger migration named in
     * {@link #DEPLOYED_SCHEMA_SCRIPTS}, at its L131 through L142. That would not hold for values
     * differing in letters or in punctuation.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("a walk composed card by card reproduces the job's card-then-identifier order")
    void aWalkComposedCardByCardReproducesTheCardThenIdentifierOrder() {
        // WHY : Assumptions: the interface declares no whole-table cursor, because its own Javadoc
        //       records that one was declared, was reached by nothing and was withdrawn -- the
        //       migrated flow composes one statement per request. Asserting a method that does not
        //       exist would fail without saying anything about the reference, so the two-key order of
        //       app/jcl/CREASTMT.JCL L53 is asserted the way a run would actually reproduce it.
        List<String> walked = new ArrayList<>();
        for (ResolvedCard card : publishedCards) {
            for (StatementTransactionView row : walkCard(card)) {
                walked.add(identityOf(row));
            }
        }
        List<String> expected = expectedIdentityOrder();

        assertThat(expected)
                .as("the committed fixture carries the whole relation")
                .hasSize(TRANSACTION_ROW_COUNT);
        assertThat(walked)
                .as("the composed walk is ascending in the composite order it declares")
                .isSorted();
        assertThat(walked)
                .withFailMessage("the composed walk must reproduce the card-then-identifier order the"
                        + " job's sort declares; the walk and the sequence derived from the committed"
                        + " fixture disagree, so either a row was skipped or repeated, or the"
                        + " ordering is no longer the declared one")
                .containsExactlyElementsOf(expected);
    }

    /**
     * Confirms two walks over unchanged rows produce one identical sequence.
     *
     * <p>Assumptions: an unstable ordering yields the same SET on every walk and a different
     * SEQUENCE, so a set comparison would hold while the property failed. The order
     * {@code app/jcl/CREASTMT.JCL} L53 declares is two ascending keys and is therefore total; a
     * migrated traversal that reproduced only its leading key would pass a set comparison and would
     * produce one card's lines in either sequence between runs, which is exactly what a
     * golden-master comparison of two runs would report as a difference on data that had not
     * changed.</p>
     *
     * <p>Assumptions: stability is asserted within one database rather than across two, and the
     * distinction is load-bearing. The card component of the order is a rendering of a keyed digest
     * whose key is generated per database, so two containers would order the CARDS differently for
     * the same data; what must be reproducible is a run over one database, and the identifier
     * component -- which is the whole of what this case walks, one card at a time -- is reproducible
     * everywhere.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("two walks over unchanged rows yield one identical sequence")
    void twoWalksOverUnchangedRowsYieldOneIdenticalSequence() {
        // WHY : Assumptions: the longest run is the one where an unstable secondary key would have
        //       the most opportunity to differ between two walks, since it holds 600 of the 700 rows
        //       and every one of them shares a card number. A short run could reorder and still
        //       compare equal by chance. The order it must repeat is the one
        //       app/jcl/CREASTMT.JCL L53 declares as SORT FIELDS=(263,16,CH,A,1,16,CH,A), whose two
        //       keys leave no tie for a second pass to break differently.
        List<String> first = walkCard(busiestCard).stream()
                .map(row -> row.key().transactionId().stripTrailing())
                .toList();
        List<String> second = walkCard(busiestCard).stream()
                .map(row -> row.key().transactionId().stripTrailing())
                .toList();

        assertThat(first)
                .as("the walk covered the whole run")
                .hasSize(BUSIEST_CARD_ROW_COUNT);
        assertThat(second)
                .withFailMessage("two walks over unchanged rows must yield one identical sequence;"
                        + " they did not, so the ordering is not total and a comparison of two runs"
                        + " would differ on data that had not changed")
                .containsExactlyElementsOf(first);
    }

    /**
     * Confirms the secondary key is the transaction identifier and not the processing date.
     *
     * <p>Assumptions: a corpus in which identifier order and date order agree cannot tell the two
     * apart, so the fixture deliberately carries a card where they DISAGREE and this case is why that
     * card exists. It is derived from the fixture rather than named here: it is the card, other than
     * the busiest, whose rows in identifier order are not its rows in processing-timestamp order. On
     * the committed corpus its three identifiers ascend while its three processing timestamps
     * descend, so the two orders are exact reverses and no partial agreement can mask a
     * substitution.</p>
     *
     * <p>Assumptions: the identifier is the key {@code app/jcl/CREASTMT.JCL} L53 names second -- its
     * second sort field is one-based position 1 for sixteen bytes, which is the identifier in the
     * rearranged record -- and the processing timestamp appears in that job's sort fields not at all.
     * A migrated query that ordered by the timestamp would look right on most data and would place
     * this card's lines in reverse.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the secondary key is the transaction identifier and not the processing date")
    void theSecondaryKeyIsTheTransactionIdentifierAndNotTheProcessingDate() {
        // WHY : Assumptions: the processing timestamp is a real ordering candidate rather than a
        //       straw one, because the OTHER job in this context sorts on a date --
        //       app/jcl/TRANREPT.jcl L41 and L42 declare TRAN-CARD-NUM and TRAN-PROC-DT as sort
        //       symbols and its INCLUDE condition at L47 filters on the date. This job instead
        //       declares app/jcl/CREASTMT.JCL L53, whose second key is the identifier at 1,16, so
        //       carrying the other job's field onto this one is what this case forecloses.
        List<FixtureRow> expected = expectedRows.get(splitOrderCard.narrowedRendering());
        List<String> byIdentifier = expected.stream().map(FixtureRow::transactionId).toList();
        List<String> byProcessingDate = expected.stream()
                .sorted(Comparator.comparing(FixtureRow::processingTimestamp))
                .map(FixtureRow::transactionId)
                .toList();

        assertThat(byProcessingDate)
                .withFailMessage("the derived card must be one whose identifier order and"
                        + " processing-date order genuinely differ, or this case cannot tell the two"
                        + " keys apart")
                .isNotEqualTo(byIdentifier);

        List<String> walked = walkCard(splitOrderCard).stream()
                .map(row -> row.key().transactionId().stripTrailing())
                .toList();

        assertThat(walked)
                .withFailMessage("the cursor must yield this card's rows in ascending"
                        + " transaction-identifier order, which is the second key app/jcl/"
                        + "CREASTMT.JCL L53 declares; it yielded them in another order, and on this"
                        + " card the processing-date order is the exact reverse")
                .containsExactlyElementsOf(byIdentifier);
        assertThat(walked)
                .as("and therefore not in processing-date order")
                .isNotEqualTo(byProcessingDate);
    }

    /**
     * Confirms the traversal is a forward-only cursor and that a bounded consumption reads a bound.
     *
     * <p>Trade-offs: this asserts what it can and the limit is stated rather than glossed. That the
     * declared return is a stream and that a bounded consumption yields exactly the bound establishes
     * the traversal is consumed lazily and forward-only by a caller that wants a prefix. It does NOT
     * establish how many rows crossed the wire: the retrieval batch the interface declares governs
     * that, and no assertion available here can observe a batch boundary. The property that matters
     * to the statement path is the one asserted -- a caller need not materialise a card's whole run
     * to read part of it -- and the alternative of collecting the whole stream first would prove
     * nothing about laziness at all.</p>
     *
     * <p>Assumptions: the cursor is consumed inside try-with-resources throughout this class because
     * the interface's own contract says the caller owns it and must close it. An unclosed cursor holds
     * its statement and its connection until the pool reclaims them, and this profile admits a
     * maximum of two pooled connections, so a leak would exhaust the pool rather than merely waste
     * one -- and the failure would surface in a later case as an acquisition timeout rather than as a
     * leak.</p>
     *
     * <p>Assumptions: the two declared query hints and the two declared transaction attributes are
     * asserted structurally alongside the behaviour, because they are what put a server-side cursor
     * in force at all. {@code application-test.yml} contributes the other half and says so itself:
     * its transport default is not what makes this read stream, the hint on this method is, and its
     * inherited pool settings supply the read-only posture and the disabled automatic commit that a
     * server-side cursor requires -- with automatic commit on, the driver buffers an entire result
     * set before handing over the first row, and a streaming assertion would pass against a fully
     * materialised list.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws NoSuchMethodException if the declared traversal method cannot be reflected, which means
     *     the interface no longer declares it under that name
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the traversal is a stream and a bounded consumption reads its bound")
    void theTraversalIsAStreamAndABoundedConsumptionReadsItsBound() throws NoSuchMethodException {
        Method traversal = StatementTransactionRepository.class
                .getMethod("streamByCardFingerprint", String.class);

        assertThat(traversal.getReturnType())
                .as("the traversal is declared as a lazily consumed sequence")
                .isEqualTo(Stream.class);
        assertThat(AutoCloseable.class)
                .as("and a sequence is closeable, which is what makes the caller its owner")
                .isAssignableFrom(traversal.getReturnType());

        // WHY : Assumptions: the reference reads ONE row per call and never a run.
        //       app/cbl/CBSTM03A.CBL L833 sets the plain-read opcode inside the paragraph its
        //       transaction pre-load calls repeatedly, and app/cbl/CBSTM03B.CBL L141 is a bare READ
        //       INTO of one record area, so the row it holds is one row. A migrated traversal that
        //       returned a collection would hold a whole run where the reference held a record, and
        //       the declared-width tables at L226 and L232 are the only place the reference
        //       accumulates anything at all.
        Map<String, String> declaredHints = new LinkedHashMap<>();
        for (QueryHint hint : traversal.getAnnotation(QueryHints.class).value()) {
            declaredHints.put(hint.name(), hint.value());
        }
        assertThat(declaredHints)
                .as("the traversal declares its own retrieval batch and a read-only result")
                .containsEntry(AvailableHints.HINT_FETCH_SIZE,
                        StatementTransactionRepository.STATEMENT_FETCH_SIZE)
                .containsEntry(AvailableHints.HINT_READ_ONLY, "true");

        Transactional attributes = traversal.getAnnotation(Transactional.class);
        assertThat(attributes.readOnly())
                .as("the traversal declares a read-only transaction")
                .isTrue();
        assertThat(attributes.propagation())
                .as("and requires one to be in progress already, so the cursor outlives the call")
                .isEqualTo(Propagation.MANDATORY);

        List<String> prefix;
        try (Stream<StatementTransactionView> cursor =
                     transactions.streamByCardFingerprint(busiestCard.fingerprint())) {
            prefix = cursor.limit(BOUNDED_PREFIX_LENGTH)
                    .map(row -> row.key().transactionId().stripTrailing())
                    .toList();
        }

        assertThat(prefix)
                .as("a bounded consumption yields its bound and the leading rows of the order")
                .containsExactlyElementsOf(expectedRows.get(busiestCard.narrowedRendering()).stream()
                        .map(FixtureRow::transactionId)
                        .toList()
                        .subList(0, BOUNDED_PREFIX_LENGTH));
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
        // WHY : Assumptions: the reference brackets a whole run between ONE open and ONE close, so
        //       the scope a cursor needs here is the scope the reference already used.
        //       app/cbl/CBSTM03A.CBL opens the transaction definition at 8100-TRNXFILE-OPEN L730,
        //       setting the open opcode at L732, and closes it at 9100-TRNXFILE-CLOSE L856 with the
        //       close opcode at L858 -- after the reads at L833 have run repeatedly in between. A
        //       traversal that started its own transaction would be exhausted before the caller read
        //       a row, and the declared retrieval batch applies only outside automatic commit, which
        //       is the same condition. Failing at the boundary is what keeps that from surfacing
        //       later as an empty statement.
        assertThatThrownBy(() -> transactions.streamByCardFingerprint(busiestCard.fingerprint()))
                .as("a mandatory propagation refuses to open a cursor into a scope that would close"
                        + " underneath it")
                .isInstanceOf(IllegalTransactionStateException.class);
    }


    /**
     * Confirms every amount is exact fixed point at a scale of two, from fixture bytes to projection.
     *
     * <p>Assumptions: {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY} L29 is eleven
     * bytes of zoned decimal carrying its sign as an overpunch on the final byte, so the column is
     * {@code NUMERIC(11,2)} and the mapped member is the shared money type at a scale of exactly two.
     * The regime is zoned rather than packed, which the baseline states independently in its own sort
     * symbols: {@code app/jcl/PRTCATBL.jcl} L47 through L50 declares an eleven-byte money field as
     * {@code TRAN-CAT-BAL,18,11,ZD}. Nothing in this class touches a binary floating-point type; the
     * whole path is exact from the fixture bytes to the projection member.</p>
     *
     * <p>Assumptions: the scale is asserted to be exactly two and NOT a normalised or stripped scale.
     * The distinction is not stylistic: a whole-currency amount at scale two and the same value with
     * its trailing zeroes stripped compare unequal under the decimal type's own equality while
     * comparing equal numerically, and only one of the two encodes back to the eleven bytes the
     * record declares. The fixture supplies a real whole-currency amount for this, so the case
     * asserts a measured hazard rather than a preference.</p>
     *
     * <p>Assumptions: this precision is never unified with the account projection's. The account
     * balance is {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7, so twelve bytes and
     * {@code NUMERIC(12,2)}, and the difference is asserted through the shared codec's own width
     * derivation rather than as two literals -- eleven digit positions against twelve. Unifying them
     * in either direction would silently change one of the two columns.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("every amount is exact fixed point at a scale of two, and never a stripped scale")
    void everyAmountIsExactFixedPointAtScaleTwo() {
        // WHY : Assumptions: the shared codec derives a zoned field's byte width from its declared
        //       digit positions, so deriving both widths from it makes the distinction between the two
        //       regimes checkable rather than asserted. app/cpy/COSTM01.CPY L29 declares nine integer
        //       digits and app/cpy/CVACT01Y.cpy L7 declares ten, which is the whole of the
        //       difference.
        assertThat(ZonedDecimalCodec.widthOf(STATEMENT_AMOUNT_INTEGER_DIGITS, MONEY_DECIMAL_DIGITS))
                .as("the statement amount occupies eleven bytes and maps to eleven digit positions")
                .isEqualTo(STATEMENT_AMOUNT_PRECISION);
        assertThat(ZonedDecimalCodec.widthOf(ACCOUNT_BALANCE_INTEGER_DIGITS, MONEY_DECIMAL_DIGITS))
                .withFailMessage("the account balance occupies %d bytes rather than the statement"
                        + " amount's %d, and the two precisions are never unified in either"
                        + " direction", ACCOUNT_BALANCE_PRECISION, STATEMENT_AMOUNT_PRECISION)
                .isEqualTo(ACCOUNT_BALANCE_PRECISION);
        assertThat(CopybookLayout.layout(TRANSACTION_DESCRIPTOR).field(AMOUNT_FIELD).length())
                .as("the descriptor agrees with that derivation")
                .isEqualTo(STATEMENT_AMOUNT_PRECISION);

        List<StatementTransactionView> walked = walkCard(splitOrderCard);
        List<FixtureRow> expected = expectedRows.get(splitOrderCard.narrowedRendering());

        assertThat(walked)
                .as("the derived card's whole run was read")
                .hasSameSizeAs(expected);
        for (int index = 0; index < walked.size(); index++) {
            Money published = walked.get(index).amount();
            BigDecimal fromFixture = expected.get(index).amount();
            assertThat(published.amount())
                    .withFailMessage("the amount the projection publishes for transaction %s must"
                            + " equal the value decoded from the fixture bytes through the shared"
                            + " codec, at the same scale; a scale difference alone would re-encode to"
                            + " different bytes", expected.get(index).transactionId())
                    .isEqualTo(fromFixture);
            assertThat(published.amount().scale())
                    .as("and it carries exactly two decimal places")
                    .isEqualTo(MONEY_DECIMAL_DIGITS);
        }

        // WHY : Assumptions: the derived card carries a negative amount as well as two positive ones,
        //       and a negative zoned value differs from a positive one only in the final byte, so a
        //       sign convention read wrongly corrupts the value silently rather than failing.
        //       tests/README.md records the same hazard for the reference compiler at its section on
        //       the sign convention, which is why the fixture keeps a negative row on this card.
        assertThat(walked.stream().map(row -> row.amount().isNegative()).toList())
                .as("the run carries both signs, so the overpunch is genuinely exercised")
                .contains(Boolean.TRUE, Boolean.FALSE);

        BigDecimal wholeCurrencyAmount = expected.stream()
                .map(FixtureRow::amount)
                .filter(amount -> amount.remainder(BigDecimal.ONE).signum() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the derived card must carry one whole-currency amount, or the scale-stripping"
                                + " hazard below cannot be demonstrated"));

        assertThat(wholeCurrencyAmount.scale())
                .as("the whole-currency amount is carried at scale two rather than normalised")
                .isEqualTo(MONEY_DECIMAL_DIGITS);
        assertThat(wholeCurrencyAmount)
                .withFailMessage("a stripped scale must not be accepted as equal: %s and its stripped"
                        + " form compare equal numerically and unequal by value, and only the"
                        + " unstripped form re-encodes to the eleven bytes the record declares",
                        wholeCurrencyAmount)
                .isNotEqualTo(wholeCurrencyAmount.stripTrailingZeros());

        Money runningTotal = Money.ZERO;
        for (FixtureRow row : expected) {
            runningTotal = runningTotal.plus(Money.of(row.amount()));
        }
        StatementTransactionRepository.StatementAggregate aggregate =
                transactions.aggregateByCardFingerprint(splitOrderCard.fingerprint());

        assertThat(aggregate.getLineCount())
                .as("the aggregate counts the same rows the cursor yields")
                .isEqualTo(expected.size());
        assertThat(aggregate.getTotal())
                .withFailMessage("the aggregate the database computes must equal the total summed"
                        + " from the fixture through the shared money type; a difference here is a"
                        + " rounding or a conversion that has entered the money path")
                .isEqualByComparingTo(runningTotal.amount());
    }

    /**
     * Confirms the interface offers no keyed single-row read and no way to write at all.
     *
     * <p>Assumptions: the absences are asserted rather than silently relied on, and each rests on the
     * dispatcher in the baseline. {@code app/cbl/CBSTM03B.CBL} L118 evaluates the DD NAME first and
     * the operation code only inside the branch it selects, and the branch for this definition at
     * L133 through L155 implements exactly three operations: open at L135, a PLAIN read at L140 and
     * close at L146. There is no keyed-read arm, and the definition is declared with sequential
     * access at L33. Register entry <b>R3</b> is the authority for replacing the single dispatcher
     * with separate roles of different shapes.</p>
     *
     * <p>Assumptions: there is no write surface either, and an unexercised operation code is
     * indistinguishable from an unsupported one from the caller's side. The write and rewrite codes
     * are DECLARED and referenced nowhere: a census across the whole baseline returns exactly four
     * occurrences, all of them condition-name declarations, at {@code app/cbl/CBSTM03B.CBL} L107 and
     * L108 and at {@code app/cbl/CBSTM03A.CBL} L78 and L79, while a search of the caller for the
     * statement that selects a code yields only open, close, read and keyed read. Register entry
     * <b>R4</b> records that an unsupported request raises here rather than returning a stale status,
     * which is the divergence from a dispatcher whose {@code WHEN OTHER} arm at L127 through L128
     * leaves the caller's status field holding whatever it held before.</p>
     *
     * <p>Assumptions: the whole declared surface is named rather than one absence, because that form
     * fails on a method removed as well as on one added. It pins two absences at once: no keyed
     * single-row read, and no ordered pass over every card -- the latter having been declared,
     * reached by nothing and withdrawn, as the interface's own Javadoc records. The bulk window this
     * module gained is a read over a BOUNDED group of cards and is not that pass: it takes the group
     * as an argument, so it cannot be issued without naming the cards it covers.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the interface declares no keyed read, no write surface and no mapped association")
    void theInterfaceDeclaresNoKeyedReadAndNoWriteSurface() {
        Set<String> declaredNames = new LinkedHashSet<>();
        for (Method declared : StatementTransactionRepository.class.getDeclaredMethods()) {
            declaredNames.add(declared.getName());
        }

        assertThat(declaredNames)
                .withFailMessage("the declared surface must be exactly the four reads this module"
                        + " accounts for; a keyed single-row read has no branch in app/cbl/"
                        + "CBSTM03B.CBL L133 to L155, and the withdrawn whole-table pass has no"
                        + " driver in this module")
                .isEqualTo(DECLARED_READ_METHOD_NAMES);
        assertThat(Arrays.stream(StatementTransactionRepository.class.getMethods())
                        .map(Method::getName)
                        .toList())
                .as("no lookup by identifier appears anywhere on the surface, inherited members"
                        + " included")
                .doesNotContain("findById");

        // WHY : Assumptions: the failure mode being guarded against is INHERITING a write surface
        //       rather than declaring one, and a scan of declared methods alone cannot see that. A
        //       wider base would put five write methods on the public surface of a type whose entire
        //       contract is that it has no write path, and they would compile, appear in every
        //       completion list and fail at the database. app/cbl/CBSTM03B.CBL L136 opens this
        //       definition OPEN INPUT -- input, never input-output and never output.
        assertThat(Arrays.stream(StatementTransactionRepository.class.getMethods())
                        .map(Method::getName)
                        .filter(WRITE_METHOD_NAMES::contains)
                        .toList())
                .as("no write operation appears anywhere on the interface's method surface")
                .isEmpty();
        assertThat(StatementTransactionRepository.class.getInterfaces())
                .as("the base is the marker interface, which declares nothing")
                .containsExactly(Repository.class);
        assertThat(StatementTransactionView.class.getAnnotation(Immutable.class))
                .as("the projection carries the immutability marker, so it is not dirty checked")
                .isNotNull();
        assertThat(Arrays.stream(StatementTransactionView.class.getDeclaredFields())
                        .anyMatch(field -> field.isAnnotationPresent(Version.class)))
                .as("the projection declares no version member, so no optimistic write path exists")
                .isFalse();

        // WHY : Assumptions: the reference joins its four definitions in working storage rather than
        //       through any declared relationship -- app/jcl/CREASTMT.JCL L83 to L86 supplies them as
        //       four separate data definitions and app/cbl/CBSTM03A.CBL reads each through its own
        //       paragraph. A mapped association here would make one projection reach into another's
        //       relation, which is what routing this context through separate views exists to
        //       prevent, and it would also load rows no statement asked for.
        for (Field field : StatementTransactionView.class.getDeclaredFields()) {
            for (Class<? extends Annotation> association : ASSOCIATION_ANNOTATIONS) {
                assertThat(field.isAnnotationPresent(association))
                        .withFailMessage("member %s must carry no %s; the statement join is composed"
                                + " by query and never by a mapped relationship", field.getName(),
                                association.getSimpleName())
                        .isFalse();
            }
            assertThat(SIBLING_PROJECTIONS)
                    .withFailMessage("member %s must not be typed as another projection; agreement"
                            + " with another context is through the physical relation, never through"
                            + " a compile-time dependency", field.getName())
                    .doesNotContain(field.getType());
        }
    }


    /**
     * Confirms the card-ordered view is a projection over the base rows and not a second copy.
     *
     * <p>Alternatives Considered: materialising a second physical copy of the transaction rows in card
     * order, which deserves naming as a real alternative because <b>the baseline does exactly
     * that</b>. {@code app/jcl/CREASTMT.JCL} L44 runs a sort over the cluster named at L45, writes the
     * sequential dataset declared at L48 through L51, then at L56 runs the dataset utility whose
     * control card at L61 is {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)}, loading the keyed dataset
     * at L59 that L83 then reads. Rejected because a second copy is a second truth for figures whose
     * only purpose is to restate the first exactly, and keeping it in step would add a
     * synchronisation the nightly rebuild hides only by rebuilding wholesale. Register entries
     * <b>R9</b> and <b>R12</b> hold this decision and the related refusal of a read replica, whose
     * specific behavioural cost is that the reference reads the live cluster at L45 so it cannot omit
     * a transaction the online path has already accepted, while a statement generated from a lagging
     * replica can.</p>
     *
     * <p>Assumptions: this case inspects the catalog on a direct connection rather than reading
     * through the repository, because what it asserts is a property of the deployed relations rather
     * than of a query. Nothing here defines or discards anything: the statements are catalog reads and
     * two row counts.</p>
     *
     * <p>Refactoring Rationale: this case no longer asserts that the base relation's indexes exist by
     * name. That assertion read as evidence about the statement path and was evidence about nothing:
     * the reads select by fingerprint, and while the fingerprint was computed per row from a secret in
     * another table they could not reach an index at all -- so the census passed on a database where
     * every statement read was a sequential scan. What replaced it is a plan assertion at
     * representative cardinality, in the case below, which fails if the index is present and unused.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the connection to the container cannot be opened or a catalog read
     *     fails, which is an arrangement failure rather than the property under test
     */
    @Test
    @DisplayName("the card-ordered view is a projection over the base rows and not a second copy")
    void theCardOrderedViewIsAProjectionAndNotASecondCopy() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            requireDeployedRelation(connection, STATEMENT_VIEW, STATEMENT_VIEW_OWNER);
            requireDeployedRelation(connection, TRANSACTION_TABLE, TRANSACTION_TABLE_OWNER);

            assertThat(singleValue(connection,
                    "select c.relkind::text from pg_class c join pg_namespace n"
                            + " on n.oid = c.relnamespace where n.nspname = '" + PROJECTION_SCHEMA
                            + "' and c.relname = 'v_statement_transactions'"))
                    .withFailMessage("%s must be an ordinary view; anything else means a second"
                            + " physical copy of the transaction rows exists, which is a second truth"
                            + " for figures whose only purpose is to restate the first",
                            STATEMENT_VIEW)
                    .isEqualTo(VIEW_RELATION_KIND);
            assertThat(singleValue(connection,
                    "select count(*)::text from pg_matviews where schemaname = '"
                            + PROJECTION_SCHEMA + "'"))
                    .as("no materialised view exists in the projection schema either")
                    .isEqualTo("0");

            // WHY : Assumptions: the schema is not empty of tables and asserting that it were would be
            //       false. data-migration/sql/V1__reporting_views.sql creates two tables there -- one
            //       keyed single-row table holding the grouping key, withdrawn from the service role
            //       outright, and one identity relation carrying a row per card, of which the service
            //       role may read two columns -- and every other relation it creates is a view. Naming
            //       both is what lets this assertion mean "no second copy of the transaction rows"
            //       rather than "no table at all". The baseline does materialise a second copy --
            //       app/jcl/CREASTMT.JCL L44 to L61 runs PGM=SORT into TRXFL.SEQ and then PGM=IDCAMS
            //       with REPRO INFILE(INFILE) OUTFILE(OUTFILE) at L61 -- and this schema declines it.
            assertThat(namesOf(connection,
                    "select c.relname::text from pg_class c join pg_namespace n"
                            + " on n.oid = c.relnamespace where n.nspname = '" + PROJECTION_SCHEMA
                            + "' and c.relkind = 'r' order by c.relname"))
                    .withFailMessage("the projection schema must hold no ordinary table beyond the two"
                            + " %s creates; a further table there would be a copy of rows another"
                            + " context owns", STATEMENT_VIEW_OWNER)
                    .containsExactlyElementsOf(PROJECTION_SCHEMA_TABLES);

            // WHY : Assumptions: the identity relation earns its place in the schema by being an
            //       ACCESS PATH rather than a copy, and the two checks below are what establish that
            //       rather than assert it. Its cardinality is one row per card in the cross-reference,
            //       so it cannot hold a transaction; and its column list is closed at three, so it
            //       cannot hold an amount, a timestamp or a customer attribute. Either property alone
            //       would be insufficient: a relation of the right size can still carry the wrong
            //       column, and a relation with the right columns can still be duplicated per
            //       transaction.
            assertThat(singleValue(connection, "select count(*)::text from " + CARD_IDENTITY_TABLE))
                    .withFailMessage("%s must hold exactly one row per card in %s; a different count"
                            + " means it is not a per-card identity relation, and a count above it"
                            + " means it has become a copy of something", CARD_IDENTITY_TABLE,
                            CARD_XREF_TABLE)
                    .isEqualTo(singleValue(connection,
                            "select count(*)::text from " + CARD_XREF_TABLE));
            assertThat(namesOf(connection,
                    "select column_name::text from information_schema.columns where table_schema = '"
                            + PROJECTION_SCHEMA + "' and table_name = 'card_identity'"
                            + " order by column_name"))
                    .withFailMessage("%s must carry exactly its three identity columns; a monetary,"
                            + " temporal or customer column there would make it a partial copy of a"
                            + " relation another context owns", CARD_IDENTITY_TABLE)
                    .containsExactlyElementsOf(CARD_IDENTITY_COLUMNS);

            assertThat(singleValue(connection, "select count(*)::text from " + STATEMENT_VIEW))
                    .withFailMessage("the projection must present exactly the rows the base relation"
                            + " holds; the identity relation it joins holds at most one row per card"
                            + " and the join is an outer one, so it can neither multiply nor lose a"
                            + " row")
                    .isEqualTo(singleValue(connection,
                            "select count(*)::text from " + TRANSACTION_TABLE));
            assertThat(singleValue(connection, "select count(*)::text from " + STATEMENT_VIEW))
                    .as("and that is the whole committed fixture")
                    .isEqualTo(String.valueOf(TRANSACTION_ROW_COUNT));
        }
    }

    /**
     * Confirms a bounded window continues by key without skipping or repeating a row.
     *
     * <p>Assumptions: the window is expressed as a strict keyset continuation on the transaction
     * identifier rather than as an ordinal offset, and the identifier is unique across the corpus, so
     * a continuation from the last identifier returned cannot skip a row or return one twice when a
     * concurrent posting inserts into a window already read -- which an offset does both of. This is
     * the discipline the whole migration uses rather than a local preference.</p>
     *
     * <p>Assumptions: the provenance of keyset positioning is NOT the keyed-read operation code of
     * this statement path, and the distinction matters because the opposite reading is an easy mistake
     * with a consequence. The caller moves a transient zero into the key-length field at
     * {@code app/cbl/CBSTM03A.CBL} L373 and L397 and then IMMEDIATELY recomputes it at L374 and L398
     * to the declared length of the key it is about to use, and a search of the baseline for a
     * zero-length reference modification returns nothing at all -- so that code addresses one row by a
     * whole key and says nothing about paging. Register entry <b>R1</b> records the provenance as
     * {@code app/cbl/COCRDLIC.cbl} L230 through L244, which carries a last-key pair, a first-key pair,
     * a screen ordinal, a last-page flag and a next-page indicator and is already a keyset cursor.
     * That program belongs to another path entirely.</p>
     *
     * <p>Assumptions: the first page is requested with the empty string, which is the sentinel the
     * interface documents, and the page size is the caller's because the boundary that publishes a
     * page decides how large one is.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("a bounded window continues by key without skipping or repeating a row")
    void aBoundedWindowContinuesByKeyWithoutSkippingOrRepeatingARow() {
        // WHY : Assumptions: the run is longer than the page size by an exact multiple, so every page
        //       including the last is full and a short page can only mean a lost row. Choosing the
        //       longest run also means the walk crosses the declared retrieval batch, so a page
        //       boundary and a batch boundary do not coincide and neither can mask the other. The
        //       scan this continues is app/cbl/CBSTM03B.CBL L140, a plain sequential READ under the
        //       ACCESS MODE IS SEQUENTIAL of L33, which a resumed run continues rather than restarts.
        List<String> expected = expectedRows.get(busiestCard.narrowedRendering()).stream()
                .map(FixtureRow::transactionId)
                .toList();
        List<String> paged = new ArrayList<>();
        String continuation = WINDOW_FIRST_PAGE;
        while (true) {
            List<StatementTransactionView> page = transactions.findWindowByCardFingerprint(
                    busiestCard.fingerprint(), continuation, WINDOW_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            assertThat(page)
                    .withFailMessage("every page of a run that divides the page size exactly must be"
                            + " full; a short page before exhaustion means a row was skipped")
                    .hasSize(WINDOW_PAGE_SIZE);
            for (StatementTransactionView row : page) {
                paged.add(row.key().transactionId().stripTrailing());
            }
            continuation = paged.getLast();
        }

        assertThat(paged)
                .withFailMessage("the reassembled pages must reproduce the card's whole run in"
                        + " ascending identifier order exactly once each; a strict continuation on a"
                        + " unique key cannot skip or repeat, which an ordinal offset can do both of")
                .containsExactlyElementsOf(expected);
        assertThat(paged)
                .as("and the run is longer than the declared retrieval batch it crossed")
                .hasSizeGreaterThan(DECLARED_FETCH_SIZE);
    }


    /**
     * Confirms the projection resolves through the pinned search path, unqualified.
     *
     * <p>Assumptions: schema resolution in this module is pinned to the SINGLE {@code reporting}
     * schema by {@code application-test.yml}, and the narrowness is compiled in rather than merely
     * intended -- {@code DataSourceConfig} in the sibling {@code config} package refuses any pinning
     * statement naming more than that one schema. Reading the projection by an unqualified name is
     * therefore an assertion about the resolution the deployed service uses: the entity mapping names
     * the schema explicitly, so a query written this way is the only form that fails if the pin were
     * ever widened or removed.</p>
     *
     * <p>Assumptions: the schemas this class WRITES during arrangement are {@code ledger} and
     * {@code account}, and it writes them only on a direct connection, because the pool is declared
     * read-only. The reach the projections themselves need is wider and belongs to their OWNER role
     * rather than to this module's login: {@code data-migration/sql/V0__schemas_and_roles.sql} conveys
     * usage on {@code ledger}, {@code account}, {@code card} and {@code reference} to that owner and
     * withdraws all four from the service role. The {@code card} schema is read by NEITHER reporting
     * program -- {@code app/jcl/CREASTMT.JCL} L83 through L86 resolves to the transaction store plus
     * three account-side stores -- and by none of the shipped projections either; it is inside the
     * owner's privileged set because a card attribute does surface from this context, masked, and the
     * reason that leg has a concrete target at all is the card master fixture {@code carddata.txt}
     * that a sibling loads for its write-refusal proof. Register entry <b>R15</b> records the gap and
     * deliberately does not resolve it.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the projection resolves unqualified through the pinned single-schema search path")
    void theProjectionResolvesThroughThePinnedSearchPath() {
        // WHY : Assumptions: were the pin ever widened to name the source schemas, an unqualified name
        //       would begin resolving to a BASE TABLE instead of to the view, so the masking the view
        //       carries would be bypassed in silence by queries nobody had changed. That is the
        //       failure this form of the query is able to see and a schema-qualified one is not. The
        //       baseline resolves a name just as indirectly: app/cbl/CBSTM03B.CBL L118 dispatches on
        //       the DD name it is handed, so which dataset a name reaches is settled by configuration
        //       outside the program rather than by the caller.
        Object count = entityManager
                .createNativeQuery("select count(*) from v_statement_transactions")
                .getSingleResult();

        assertThat(((Number) count).intValue())
                .withFailMessage("the projection must resolve unqualified and present the whole"
                        + " committed corpus of %d rows", TRANSACTION_ROW_COUNT)
                .isEqualTo(TRANSACTION_ROW_COUNT);
    }

    /**
     * Walks one card's whole run and collects its rows in the order the cursor yielded them.
     *
     * <p>Assumptions: the cursor is consumed inside try-with-resources because the interface's
     * contract states the caller owns it and must close it, and this profile admits a maximum of two
     * pooled connections -- so a leaked cursor would exhaust the pool and surface in a later case as
     * an acquisition timeout rather than as a leak.</p>
     *
     * @param card the published card to walk, resolved to its fingerprint
     * @return every row the cursor yielded for that card, in the order it yielded them, never
     *     {@code null}
     */
    private List<StatementTransactionView> walkCard(ResolvedCard card) {
        try (Stream<StatementTransactionView> cursor =
                     transactions.streamByCardFingerprint(card.fingerprint())) {
            return cursor.toList();
        }
    }

    /**
     * Renders one projected row as the fixed-width identity pair the composite order compares.
     *
     * <p>Assumptions: both components are fixed width, sixteen and sixteen, so the rendered pair
     * orders exactly as the two-key order does and the separator carries no ordering weight. The card
     * component is the NARROWED rendering the projection publishes, so no card number reaches a
     * comparison or a failure message.</p>
     *
     * @param row one row the cursor yielded
     * @return the narrowed card rendering joined to the transaction identifier, never {@code null}
     */
    private static String identityOf(StatementTransactionView row) {
        return row.key().cardNumber().stripTrailing()
                + IDENTITY_SEPARATOR
                + row.key().transactionId().stripTrailing();
    }

    /**
     * Derives the whole sequence a statement run must yield, from the committed fixture alone.
     *
     * <p>Assumptions: the ordering rule is applied here rather than read back from the engine, so this
     * sequence is an independent statement of the expected order. Sorting is by natural string
     * comparison, which is faithful for these values because every element is a constant
     * twelve-character prefix, four ASCII digits, a separator and sixteen ASCII digits.</p>
     *
     * @return the narrowed identity pair of every fixture row, ascending, never {@code null}
     */
    private static List<String> expectedIdentityOrder() {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, List<FixtureRow>> card : expectedRows.entrySet()) {
            for (FixtureRow row : card.getValue()) {
                pairs.add(card.getKey() + IDENTITY_SEPARATOR + row.transactionId());
            }
        }
        pairs.sort(Comparator.naturalOrder());
        return pairs;
    }

    /**
     * Groups every fixture row by its narrowed card rendering, each group in identifier order.
     *
     * <p>Assumptions: the grouping key is the NARROWED rendering rather than the whole card number,
     * which costs no discriminating power on this corpus because every published card carries a
     * distinct last four digits, and keeps a card number out of every map, message and comparison in
     * this class.</p>
     *
     * <p>Assumptions: each group is sorted by transaction identifier here, so the expected order is
     * established once and every case that needs it reads the same sequence.</p>
     *
     * @return the fixture's rows grouped by narrowed rendering, never {@code null}
     */
    private static Map<String, List<FixtureRow>> expectedRowsByCard() {
        Map<String, List<FixtureRow>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> decoded : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
            String rendering = narrowedRenderingOf(text(decoded, CARD_NUMBER_FIELD));
            grouped.computeIfAbsent(rendering, key -> new ArrayList<>())
                    .add(new FixtureRow(
                            text(decoded, TRANSACTION_ID_FIELD),
                            money(decoded, AMOUNT_FIELD),
                            text(decoded, PROCESSING_TIMESTAMP_FIELD)));
        }
        for (List<FixtureRow> run : grouped.values()) {
            run.sort(Comparator.comparing(FixtureRow::transactionId));
        }
        return grouped;
    }

    /**
     * Finds the published card carrying the most rows in the fixture.
     *
     * <p>Assumptions: the card is DERIVED from the fixture rather than named in this source, and that
     * is a data-handling decision rather than a stylistic one. Naming it would put a primary account
     * number in a committed test class, whereas deriving it keeps only the narrowed rendering in
     * memory and additionally makes the case fail if a fixture edit ever moved the longest run.</p>
     *
     * @return the published card with the largest run, never {@code null}
     * @throws IllegalStateException if no published card carries the expected longest run, which
     *     means the fixture no longer exceeds the reference's same-card threshold
     */
    private static ResolvedCard cardCarryingTheMostRows() {
        String rendering = expectedRows.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .orElseThrow(() -> new IllegalStateException(
                        "the transaction fixture carries no row at all"))
                .getKey();
        if (expectedRows.get(rendering).size() != BUSIEST_CARD_ROW_COUNT) {
            throw new IllegalStateException("the busiest card must carry " + BUSIEST_CARD_ROW_COUNT
                    + " rows so that it exceeds both the reference's measured same-card threshold of "
                    + REFERENCE_MEASURED_SAME_CARD_THRESHOLD + " and the declared retrieval batch of "
                    + DECLARED_FETCH_SIZE + "; it carries " + expectedRows.get(rendering).size());
        }
        return publishedCardRendered(rendering);
    }

    /**
     * Finds the card whose identifier order and processing-date order provably disagree.
     *
     * <p>Assumptions: the busiest card is excluded so that this case walks a short run, which keeps
     * the disagreement legible in a failure message; on the committed corpus exactly one other card
     * qualifies, and its two orders are exact reverses of each other. Deriving it rather than naming
     * it keeps a primary account number out of this source and makes the arrangement fail loudly if a
     * fixture edit ever removed the disagreement, which would leave the ordering case unable to tell
     * the identifier from the date.</p>
     *
     * @return a published card, other than the busiest, whose two candidate orders differ, never
     *     {@code null}
     * @throws IllegalStateException if no such card exists in the fixture
     */
    private static ResolvedCard cardWhoseIdentifierOrderDiffersFromItsDateOrder() {
        for (Map.Entry<String, List<FixtureRow>> card : expectedRows.entrySet()) {
            if (card.getKey().equals(busiestCard.narrowedRendering())) {
                continue;
            }
            List<String> byIdentifier = card.getValue().stream()
                    .map(FixtureRow::transactionId)
                    .toList();
            List<String> byProcessingDate = card.getValue().stream()
                    .sorted(Comparator.comparing(FixtureRow::processingTimestamp))
                    .map(FixtureRow::transactionId)
                    .toList();
            if (!byIdentifier.equals(byProcessingDate)) {
                return publishedCardRendered(card.getKey());
            }
        }
        throw new IllegalStateException("the transaction fixture must carry a card, other than its"
                + " busiest, whose transaction-identifier order differs from its processing-date"
                + " order; without one, nothing can distinguish the identifier from the date as the"
                + " secondary sort key");
    }

    /**
     * Finds the resolved card carrying one narrowed rendering.
     *
     * @param rendering the narrowed card rendering to look up
     * @return the resolved card publishing that rendering, never {@code null}
     * @throws IllegalStateException if the shipped lookup resolved no card to that rendering, which
     *     means the transaction fixture names a card the cross-reference does not carry
     */
    private static ResolvedCard publishedCardRendered(String rendering) {
        return publishedCards.stream()
                .filter(card -> card.narrowedRendering().equals(rendering))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no published card resolves to the"
                        + " rendering " + rendering + "; the transaction fixture names a card the"
                        + " cross-reference fixture does not carry, which the reference treats as an"
                        + " abort rather than as a row to skip"));
    }

    /**
     * Applies the narrowing rule the projection applies, to one whole card number.
     *
     * <p>Assumptions: the rendering is produced by {@link CardNumberMasker#mask(String)} -- the one
     * authority this repository has for a masked card -- rather than by a prefix and a tail composed
     * here. A whole card number never leaves this method: the value it returns is the only card
     * rendering any assertion, map key or message in this class handles.</p>
     *
     * <p>Refactoring Rationale: this concatenated a local twelve-asterisk constant with the last four
     * characters of the argument, which agreed with the authority by coincidence rather than by
     * construction. A local rule is free to be MORE PERMISSIVE than the authority and that is how a
     * masking control drifts: the authority masks a value at or below the visible-tail length
     * entirely, whereas the local form's tail arithmetic would have returned a short value as itself
     * -- so a rendering this class accepted could have been a card number the deployment would have
     * refused to publish. Delegating removes the second rule rather than aligning it, so no future
     * change to the authority can leave this class asserting the old one.</p>
     *
     * @param wholeCardNumber a whole card number as the fixture carries it
     * @return that card as the shared authority masks it, never {@code null} for a non-null argument
     */
    private static String narrowedRenderingOf(String wholeCardNumber) {
        return CardNumberMasker.mask(wholeCardNumber);
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
     *     reported against the file that owns it rather than remedied here
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
     * shipped files with no credential written anywhere in it.</p>
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
     * Loads the two fixtures this class reads and resolves every card the corpus publishes.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against its registered descriptor, so the load carries the record layout the migration is
     * defined against rather than a second transcription of it. Alternatives Considered: parsing the
     * fixed-width rows here by offset. Rejected because the descriptor registry is the one place
     * offsets are declared, and a second parser in this class would drift from it silently -- and the
     * fixture's geometry is exactly what the assertions above depend on.</p>
     *
     * <p>Assumptions: the loads are inserts and nothing else. This class defines no relation, conveys
     * no privilege and discards nothing; the boundary this directory draws is on DEFINITIONS, and
     * inserting rows sits inside it because a fixture has to be loaded before anything can be read.</p>
     *
     * <p>Assumptions: a direct connection is used rather than the pooled one, because the pool this
     * profile inherits is declared read-only and would refuse an insert before the engine saw it. The
     * same connection then resolves the published cards, so the whole arrangement costs one
     * connection.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded or any card cannot be resolved
     */
    private static void loadFixtureCorpus() {
        try (Connection connection = POSTGRES.createConnection("")) {
            requireDeployedRelation(connection, CROSS_REFERENCE_TABLE, CROSS_REFERENCE_TABLE_OWNER);
            requireDeployedRelation(connection, TRANSACTION_TABLE, TRANSACTION_TABLE_OWNER);
            loadCardCrossReferences(connection);
            refreshCardIdentity(connection);
            loadTransactions(connection);
            publishedCards = resolvePublishedCards(connection);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the statement-path fixture corpus", cause);
        }
    }

    /**
     * Brings the identity relation level with the cross-reference this class has just loaded.
     *
     * <p>Assumptions: this call is not an arrangement convenience but the SAME step the load path
     * performs in the deployment. {@code reporting.card_identity} carries one row per card and is
     * populated by a backfill when {@code data-migration/sql/V1__reporting_views.sql} creates it, so
     * any card inserted AFTER that script ran -- which is every card this class inserts, since the
     * migrations are applied before the fixture -- has no identity row until the maintenance procedure
     * runs. {@code data-migration/src/carddemo_migration/loaders/aurora.py} publishes the same step
     * as {@code refresh_card_identity(connection)}, to be run after its cross-reference load, and a
     * whole-run statement pass is required to call it before it walks the heading cursor.</p>
     *
     * <p>Assumptions: the omission this closes is SILENT rather than loud, which is why the call
     * belongs in the load and not in a case. A card with no identity row is absent from
     * {@code reporting.v_card_xref} and unresolvable by {@code reporting.resolve_card}, so a statement
     * run over a stale relation emits fewer statements and reports nothing -- the failure surfaces as
     * a missing cardholder rather than an error.</p>
     *
     * <p>Alternatives Considered: a trigger on {@code account.card_xref} that maintained the identity
     * row as part of the write, which would make this call unnecessary everywhere. Rejected because
     * {@code data-migration/sql/V1__reporting_views.sql} runs under
     * {@code SET LOCAL ROLE carddemo_reporting_owner} and that role holds no {@code TRIGGER} privilege
     * on a relation the account context owns; granting it would widen a cross-context boundary and
     * would make an account-context write fail whenever reporting maintenance failed.</p>
     *
     * @param connection an open connection able to execute the maintenance procedure
     * @throws SQLException if the procedure cannot be executed, which includes it being absent
     */
    private static void refreshCardIdentity(Connection connection) throws SQLException {
        try (Statement refresh = connection.createStatement()) {
            refresh.execute("call reporting.refresh_card_identity()");
        }
    }

    /**
     * Loads {@code xreffile.txt} into the card cross-reference.
     *
     * <p>Assumptions: this is the statement-path fixture and the only one of the two cross-reference
     * fixtures loaded, because loading both would insert one card twice and the relation's key is the
     * card number. It is loaded because the shipped per-card lookup resolves against this relation, so
     * without it no fingerprint could be obtained for any card.</p>
     *
     * @param connection an open connection able to write the account schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCardCrossReferences(Connection connection) throws SQLException {
        String sql = "insert into account.card_xref(card_num, customer_id, account_id)"
                + " values (?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row
                    : decodeAll(CROSS_REFERENCE_FIXTURE, CROSS_REFERENCE_DESCRIPTOR)) {
                insert.setString(1, text(row, CROSS_REFERENCE_CARD_FIELD));
                insert.setLong(2, integral(row, CROSS_REFERENCE_CUSTOMER_FIELD));
                insert.setLong(3, integral(row, CROSS_REFERENCE_ACCOUNT_FIELD));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code trnxfile.txt} into the transaction relation the projection reads.
     *
     * <p>Assumptions: the rows decode against the descriptor {@code TRNX} and never against
     * {@code TRAN}. Both records are 350 bytes and both carry the same field names in a different
     * order, so using the posting descriptor here would insert a wrong value in every column while
     * every row remained exactly 350 bytes -- the mistake has no length symptom and would surface as
     * implausible money rather than as a failure.</p>
     *
     * <p>Assumptions: two bindings are worth stating because the decoded type and the column type
     * differ. The category code decodes as an integral value while its column is a four-character
     * code, so it is rendered with an explicit four-digit format under a fixed locale rather than
     * relying on a default. And the twenty-six-character timestamp the record carries is exactly the
     * escape form the driver's own timestamp literal accepts, so it is converted with that literal
     * rather than through a hand-written format string, which keeps microsecond precision intact.</p>
     *
     * @param connection an open connection able to write the ledger schema
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadTransactions(Connection connection) throws SQLException {
        String sql = "insert into ledger.transactions(transaction_id, type_cd, category_cd, source,"
                + " description, amount, merchant_id, merchant_name, merchant_city, merchant_zip,"
                + " card_num, orig_ts, proc_ts) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll(TRANSACTION_FIXTURE, TRANSACTION_DESCRIPTOR)) {
                insert.setString(1, text(row, TRANSACTION_ID_FIELD));
                insert.setString(2, text(row, TYPE_CODE_FIELD));
                insert.setString(3, String.format(
                        Locale.ROOT, "%04d", integral(row, CATEGORY_CODE_FIELD)));
                insert.setString(4, text(row, SOURCE_FIELD));
                insert.setString(5, text(row, DESCRIPTION_FIELD));
                insert.setBigDecimal(6, money(row, AMOUNT_FIELD));
                insert.setLong(7, integral(row, MERCHANT_ID_FIELD));
                insert.setString(8, text(row, MERCHANT_NAME_FIELD));
                insert.setString(9, text(row, MERCHANT_CITY_FIELD));
                insert.setString(10, text(row, MERCHANT_POSTAL_CODE_FIELD));
                insert.setString(11, text(row, CARD_NUMBER_FIELD));
                insert.setTimestamp(12, timestamp(row, ORIGINATION_TIMESTAMP_FIELD));
                insert.setTimestamp(13, timestamp(row, PROCESSING_TIMESTAMP_FIELD));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Resolves every card the cross-reference fixture publishes through the shipped lookup.
     *
     * <p>Assumptions: the fingerprint is OBTAINED rather than derived, and that is the contract the
     * interface documents for a request-time caller: the key the projection mixes into it is
     * unreadable from this context by privilege, so a caller resolves the card it was given once and
     * then names it exactly for every read that follows. Deriving the digest here would require the
     * key and would make this class assert agreement with its own arithmetic.</p>
     *
     * <p>Assumptions: the returned sequence is sorted by narrowed rendering, so the composed walk can
     * be taken in published-card order without sorting at every call site. The renderings are unique
     * across this corpus, which the resolution case asserts, so that order is total.</p>
     *
     * @param connection an open connection able to execute the shipped lookup
     * @return every published card with its rendering and its fingerprint, ascending, never
     *     {@code null}
     * @throws SQLException if the lookup cannot be executed
     * @throws IllegalStateException if the lookup answers nothing for a card the fixture carries,
     *     which means the cross-reference load did not take effect
     */
    private static List<ResolvedCard> resolvePublishedCards(Connection connection)
            throws SQLException {
        List<ResolvedCard> resolved = new ArrayList<>();
        String sql = "select card_num, card_fingerprint from reporting.resolve_card(?)";
        try (PreparedStatement lookup = connection.prepareStatement(sql)) {
            for (Map<String, Object> row
                    : decodeAll(CROSS_REFERENCE_FIXTURE, CROSS_REFERENCE_DESCRIPTOR)) {
                String wholeCardNumber = text(row, CROSS_REFERENCE_CARD_FIELD);
                lookup.setString(1, wholeCardNumber);
                try (ResultSet answer = lookup.executeQuery()) {
                    if (!answer.next()) {
                        throw new IllegalStateException("the shipped lookup resolved no fingerprint"
                                + " for the card published as "
                                + narrowedRenderingOf(wholeCardNumber)
                                + "; it answers only for a card the cross-reference carries, so the"
                                + " fixture load did not take effect");
                    }
                    resolved.add(new ResolvedCard(
                            answer.getString(1).stripTrailing(), answer.getString(2)));
                }
            }
        }
        resolved.sort(Comparator.comparing(ResolvedCard::narrowedRendering));
        return resolved;
    }

    /**
     * Fails unless the named relation exists, naming the file that owns it.
     *
     * <p>Assumptions: a relation missing at run time is a defect to REPORT against the file that owns
     * it and never something for a class here to create -- this login could not create it in any case,
     * and a local remedy would place a second, drifting definition inside the one module deliberately
     * unable to replace one. Register entry <b>R11</b> is the authority, and every arrangement failure
     * in this class names an owner for that reason.</p>
     *
     * @param connection an open connection able to read the relation
     * @param relation the schema-qualified relation name
     * @param owningFile the repository-relative path of the file that creates the relation
     * @throws SQLException if the relation cannot be read at all
     */
    private static void requireDeployedRelation(Connection connection, String relation,
            String owningFile) throws SQLException {
        try (Statement probe = connection.createStatement();
                ResultSet answer = probe.executeQuery("select count(*) from " + relation)) {
            answer.next();
        } catch (SQLException cause) {
            throw new SQLException(relation + " is absent or unreadable; it is owned by " + owningFile
                    + ", so this is a defect to report against that file rather than something for"
                    + " this class to create", cause);
        }
    }

    /**
     * Reads one scalar as text from a direct connection.
     *
     * <p>Assumptions: the value is cast to text in the query rather than converted in Java, so a
     * catalog code, a count and a name all arrive through one accessor and no assertion here depends
     * on which numeric type the engine chose for a count.</p>
     *
     * @param connection an open connection able to run the query
     * @param sql a query returning exactly one row of one column, already cast to text
     * @return that value, never {@code null}
     * @throws SQLException if the query fails or returns no row
     */
    private static String singleValue(Connection connection, String sql) throws SQLException {
        try (Statement probe = connection.createStatement();
                ResultSet answer = probe.executeQuery(sql)) {
            if (!answer.next()) {
                throw new SQLException("the query returned no row: " + sql);
            }
            return answer.getString(1);
        }
    }

    /**
     * Reads one column of text values as a list from a direct connection.
     *
     * @param connection an open connection able to run the query
     * @param sql a query returning one column of text values in a defined order
     * @return those values in the order the query returned them, never {@code null}
     * @throws SQLException if the query fails
     */
    private static List<String> namesOf(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement probe = connection.createStatement();
                ResultSet answer = probe.executeQuery(sql)) {
            while (answer.next()) {
                values.add(answer.getString(1));
            }
        }
        return values;
    }


    /**
     * Reads one fixture's rows as raw lines, without decoding them.
     *
     * <p>Assumptions: the length of a line is the length of a record, because each fixture is
     * line-oriented with one record per line and the newline is a FILE convention that is not part of
     * any record -- the declared length excludes it. The bytes are read in a single-byte encoding so
     * that one character is one byte and a measured length is a byte count.</p>
     *
     * @param fileName the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @return every non-blank line of that fixture, in file order, never {@code null}
     * @throws UncheckedIOException if the fixture cannot be read
     * @throws IllegalStateException if the fixture is absent from the classpath
     */
    private static List<String> rawLinesOf(String fileName) {
        String resource = FIXTURE_DIRECTORY + '/' + fileName;
        try (InputStream stream = StatementTransactionRepositoryIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the fixture " + resource + " is absent");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.ISO_8859_1))) {
                return reader.lines().filter(line -> !line.isBlank()).toList();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read the fixture " + resource, cause);
        }
    }

    /**
     * Decodes every row of one fixture through the shared codec against its registered descriptor.
     *
     * <p>Assumptions: the bytes are taken in a single-byte encoding so that one character is one byte
     * and every declared offset holds. Reading a fixture as one continuous byte stream would mis-align
     * every record after the first, because the newline the file carries is not part of the declared
     * length.</p>
     *
     * @param fileName the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @param descriptorName the registered copybook descriptor name
     * @return one decoded field map per row, in file order, never {@code null}
     */
    private static List<Map<String, Object>> decodeAll(String fileName, String descriptorName) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        return rawLinesOf(fileName).stream()
                .map(line -> FixedWidthCodec.decodeRecord(
                        line.getBytes(StandardCharsets.ISO_8859_1), spec))
                .toList();
    }

    /**
     * Reads one decoded character field, trimmed of the pad that carries it to its declared width.
     *
     * <p>Assumptions: trailing blanks are removed because the copybook pads a character field to a
     * declared width while several target columns are variable-width for exactly the fields holding
     * text a person reads. Loading the pad would make every comparison depend on it.</p>
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
     * <p>Assumptions: the value arrives from the shared codec already at a scale of two, because the
     * descriptor declares two decimal digit positions, and it stays an exact decimal from the codec to
     * the column and to every comparison in this class. No stage of the money path here passes through
     * a binary floating-point type, which is what transformation rule T3 of the migration plan
     * requires of every value in it.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's exact value at a scale of two, never {@code null}
     */
    private static BigDecimal money(Map<String, Object> row, String field) {
        return (BigDecimal) row.get(field);
    }

    /**
     * Reads one decoded twenty-six character timestamp as a driver timestamp value.
     *
     * <p>Assumptions: the twenty-six character form the baseline emits,
     * {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}, is exactly the escape form the driver's own timestamp
     * literal accepts, so the conversion needs no format string of its own and keeps microsecond
     * precision. Alternatives Considered: a hand-written date-time formatter. Rejected because a
     * second spelling of the same 26-character contract is a second thing to keep correct, and a
     * pattern with the wrong number of fractional-digit symbols truncates silently rather than
     * failing.</p>
     *
     * <p>Assumptions: the target column is a microsecond timestamp while the reference's own physical
     * dataset carries only the first 24 characters of this field -- {@code app/jcl/CREASTMT.JCL} L54
     * copies 16 plus 262 plus 50, which is 328 of the record's 350 bytes, leaving one-based 329
     * through 350 never written, while {@code app/cpy/COSTM01.CPY} L35 declares the full 26. The
     * baseline truncates there, the Java carries the declared precision, and the divergence is
     * registered in {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed
     * here. The committed fixture carries the declared 26 characters, so this conversion is exact.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return that instant as a driver timestamp value, never {@code null}
     */
    private static Timestamp timestamp(Map<String, Object> row, String field) {
        return Timestamp.valueOf(LocalDateTime.parse(text(row, field).replace(' ', 'T')));
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
     * Records every statement the persistence provider emits, so a generated read can be planned.
     *
     * <p>Assumptions: the provider instantiates this type by name from the property declared on the
     * class above, so it is public with an implicit no-argument constructor and its store is static --
     * the instance the provider builds is not one a case could otherwise reach.</p>
     *
     * <p>Assumptions: the store is cleared by the case before each read it plans, unlike the sibling
     * recorder in {@code StatementAccountRepositoryIT} which never clears. The difference is required
     * by the question asked: that one asks whether ANY emitted statement names a forbidden column,
     * which earlier statements cannot affect, while these cases ask what THIS read emitted, which
     * earlier statements would answer wrongly.</p>
     */
    public static final class EmittedStatements implements StatementInspector {

        /**
         * The serialized form's version, fixed at one.
         */
        // WHY : Assumptions: the provider's inspector interface extends the serialization marker, so
        //       this type is serializable whether or not anything serializes it, and the compiler
        //       reports a missing version under the build's warning set. A fixed value is declared
        //       rather than derived because a derived one changes whenever a member is added.
        private static final long serialVersionUID = 1L;

        /**
         * Every statement seen since the last clear, in emission order, safe for concurrent append.
         */
        private static final List<String> SEEN = new CopyOnWriteArrayList<>();

        /**
         * Records one statement and returns it unchanged.
         *
         * <p>Assumptions: the statement is returned exactly as received, because this inspector
         * observes and never rewrites -- a returned change would alter what the provider executes, and
         * the case would then plan a statement the deployment never issues.</p>
         *
         * @param sql the statement the provider is about to issue
         * @return that same statement, unchanged
         */
        @Override
        public String inspect(String sql) {
            SEEN.add(sql);
            return sql;
        }

        /**
         * Discards every recorded statement.
         *
         * <p>This method takes no parameter and returns no value.</p>
         */
        static void clear() {
            SEEN.clear();
        }

        /**
         * Returns the one recorded statement that reads the named relation.
         *
         * @param relation the unqualified relation name the wanted statement must name
         * @return that statement, never {@code null}
         * @throws IllegalStateException if no recorded statement names the relation, which means the
         *     read under examination did not reach the database at all
         */
        static String theOneNaming(String relation) {
            List<String> matching = SEEN.stream()
                    .filter(sql -> sql.toLowerCase(Locale.ROOT).contains(relation))
                    .toList();
            if (matching.isEmpty()) {
                throw new IllegalStateException("no statement naming " + relation + " was emitted, so"
                        + " there is nothing to plan; the read under examination did not reach the"
                        + " database. Recorded statements: " + SEEN);
            }
            return matching.getLast();
        }
    }

    /**
     * Confirms every statement read reaches the transactions by index at production-like cardinality.
     *
     * <p>Purpose: this is the evidence the review finding asked for and the class previously lacked.
     * What stood here was a census of index NAMES on the base relation, which passed on a database
     * where every statement read was a sequential scan -- the reads select by fingerprint, and while
     * the fingerprint was an expression computed per row from a secret held in another table it was
     * not {@code IMMUTABLE}, so no index could serve it and both named indexes sat unused. An
     * assertion that an index exists is not an assertion that a query uses it.</p>
     *
     * <p>Assumptions: the plan examined is the plan of the statement the PROVIDER emitted, recorded as
     * it was issued rather than transcribed here. A transcription would let this case pass while the
     * repository executed something else, which is the shape of the defect it exists to catch.</p>
     *
     * <p>Assumptions: the plan is taken with {@code GENERIC_PLAN}, so the engine plans the statement
     * with its parameters UNKNOWN. That is deliberately harder than planning with the values bound: a
     * plan that survives without knowing the fingerprint cannot have been chosen because one
     * particular constant happened to look selective. It also removes any need to map a bind value
     * onto a placeholder position, which for the three-armed continuation would mean depending on the
     * provider's placeholder ordering.</p>
     *
     * <p>Assumptions: the four shapes share ONE seeded population, in one case rather than four,
     * because seeding is the expensive part and none of the four writes anything. Splitting them would
     * quadruple the load for no additional property.</p>
     *
     * <p>Assumptions: the population is seeded to {@value #PLAN_CARD_COUNT} cards and
     * {@value #PLAN_TRANSACTION_COUNT} transactions and then analysed, because the committed fixture
     * is far too small for a plan to mean anything about production -- at a few hundred rows a
     * sequential scan is genuinely the cheapest way to read the table, so "no sequential scan" would
     * be asserting that the engine had made the wrong choice. At the seeded size a sequential scan of
     * the ledger is what the engine picks for any predicate it cannot index, which is what gives the
     * assertion teeth.</p>
     *
     * <p>Assumptions: the seeded rows are removed in a {@code finally} arm and the committed counts are
     * asserted afterwards, because sibling cases in this class assert the fixture's exact size. The
     * removal doubles as the only exercise of the maintenance procedure's DELETE arm: the identity rows
     * for the departed cards must disappear, and the count assertion after the refresh is what
     * establishes it.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the container connection cannot be opened, the population cannot be
     *     seeded or removed, or a plan cannot be taken -- all arrangement failures rather than the
     *     property under test
     */
    @Test
    @DisplayName("every statement read reaches the transactions by index at production cardinality")
    void everyStatementReadReachesTheTransactionsByIndexAtProductionCardinality() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            // WHY : Trade-offs: the seeding is INSIDE the try whose finally discards it, not before
            //       it, so a failure part-way through leaves nothing behind. Seeding before the try
            //       reads more naturally but leaves a half-written population committed on the
            //       container the rest of this class then asserts exact counts against, turning one
            //       failure into every subsequent case failing for an unrelated reason.
            try {
                seedRepresentativePopulation(connection);
                String fingerprint = aSeededFingerprint(connection);

                EmittedStatements.clear();
                transactions.aggregateByCardFingerprint(fingerprint);
                assertReachesTransactionsByIndex("the per-card aggregate",
                        EmittedStatements.theOneNaming(STATEMENT_VIEW_RELATION));

                EmittedStatements.clear();
                transactions.findWindowByCardFingerprint(fingerprint, "", PLAN_WINDOW_LIMIT);
                String firstWindow = EmittedStatements.theOneNaming(STATEMENT_VIEW_RELATION);
                assertReachesTransactionsByIndex("the first window of one card", firstWindow);

                // WHY : Assumptions: the continuation is asserted to emit the SAME statement text as
                //       the opening window rather than being planned a second time. The two differ
                //       only in a bind value, so a second plan of the same text would restate the
                //       assertion above; what is worth establishing is that the continuation is not a
                //       DIFFERENT statement -- which is the only way it could reach a different plan
                //       -- and that is a property of the text.
                EmittedStatements.clear();
                transactions.findWindowByCardFingerprint(
                        fingerprint, PLAN_CONTINUATION_ANCHOR, PLAN_WINDOW_LIMIT);
                assertThat(EmittedStatements.theOneNaming(STATEMENT_VIEW_RELATION))
                        .withFailMessage("the continuation must issue the same statement as the"
                                + " opening window, since only then is it served by the plan asserted"
                                + " for that statement; a different text means the continuation has"
                                + " its own plan and its own cost")
                        .isEqualTo(firstWindow);

                EmittedStatements.clear();
                transactions.findWindowForCardGroup(
                        List.of(fingerprint), "", "", "", PLAN_WINDOW_LIMIT);
                assertReachesTransactionsByIndex("the bulk window over a card group",
                        EmittedStatements.theOneNaming(STATEMENT_VIEW_RELATION));
            } finally {
                discardRepresentativePopulation(connection);
            }

            assertThat(singleValue(connection, "select count(*)::text from " + TRANSACTION_TABLE))
                    .as("the committed fixture must be all that remains of the transaction relation")
                    .isEqualTo(String.valueOf(TRANSACTION_ROW_COUNT));
            assertThat(singleValue(connection, "select count(*)::text from " + CARD_IDENTITY_TABLE))
                    .withFailMessage("the maintenance procedure must have removed the identity row of"
                            + " every departed card; a residue here means its delete arm does not"
                            + " work, and a stale identity row keeps a card resolvable after the"
                            + " cross-reference has stopped publishing it")
                    .isEqualTo(singleValue(connection,
                            "select count(*)::text from " + CARD_XREF_TABLE));
        }
    }

    /**
     * Takes the plan of one emitted statement and fails unless it reaches the ledger by index.
     *
     * <p>Assumptions: the placeholders the provider emits are rewritten from {@code ?} to {@code $n}
     * in textual order, because {@code EXPLAIN (GENERIC_PLAN)} accepts only the numbered form. The
     * rewrite is textual and assumes no question mark appears inside a string literal, which holds for
     * every statement generated from the queries this class exercises -- their literals are the view
     * bodies' and not the queries'.</p>
     *
     * <p>Assumptions: the assertion names the indexes that MUST appear and the scans that must NOT,
     * rather than comparing the whole plan against a committed copy. A whole-plan comparison would
     * fail on every cost model change and would say nothing about which part mattered.</p>
     *
     * @param label how the failure message should describe the read, for a reader who has to find it
     * @param emitted the statement the provider issued, with {@code ?} placeholders
     * @throws SQLException if the statement cannot be planned, which is an arrangement failure
     */
    private static void assertReachesTransactionsByIndex(String label, String emitted)
            throws SQLException {
        List<String> plan = genericPlanOf(emitted);

        assertThat(plan)
                .withFailMessage("%s must resolve the fingerprint through %s; without it the engine"
                        + " has no way from a fingerprint to a card number and must read the whole"
                        + " ledger. Plan:%n%s", label, CARD_IDENTITY_PRIMARY_KEY,
                        String.join(System.lineSeparator(), plan))
                .anySatisfy(line -> assertThat(line).contains(CARD_IDENTITY_PRIMARY_KEY));
        assertThat(plan)
                .withFailMessage("%s must reach the transactions through %s; that index is the whole"
                        + " of the access path the reference's rebuilt alternate index became."
                        + " Plan:%n%s", label, LEDGER_CARD_INDEX,
                        String.join(System.lineSeparator(), plan))
                .anySatisfy(line -> assertThat(line).contains(LEDGER_CARD_INDEX));
        for (String forbidden : FORBIDDEN_PLAN_SCANS) {
            assertThat(plan)
                    .withFailMessage("%s must not scan %s sequentially at this cardinality; a"
                            + " sequential scan here is the defect the finding named, and it is"
                            + " invisible from the result. Plan:%n%s", label, forbidden,
                            String.join(System.lineSeparator(), plan))
                    .noneSatisfy(line -> assertThat(line).contains(forbidden));
        }
    }

    /**
     * Plans one statement with its parameters unknown and returns the plan, line by line.
     *
     * <p>Assumptions: the plan is taken over a connection of its own, opened in the driver's SIMPLE
     * query mode, and that mode is required rather than preferred. In the driver's default extended
     * mode every statement is parsed and then bound, and the server registers a {@code $n} it sees
     * during parsing as a parameter of the statement being prepared -- so the bind that follows
     * supplies none and the server refuses with "bind message supplies 0 parameters, but prepared
     * statement requires 1". Simple mode has no bind step, which leaves the placeholder for
     * {@code EXPLAIN (GENERIC_PLAN)} itself to interpret, exactly as it is interpreted when the same
     * statement is typed into a terminal client.</p>
     *
     * <p>Alternatives Considered: binding a plausible value for each placeholder and planning with
     * {@code EXPLAIN} alone. Rejected on two grounds: it would make the case depend on the provider's
     * placeholder ORDER, which for the three-armed continuation means depending on how the query was
     * written rather than on what it does; and a plan chosen with the values known can be indexed
     * because one particular constant looked selective, which is a weaker property than the one under
     * assertion.</p>
     *
     * @param emitted the statement to plan, with {@code ?} placeholders
     * @return every line of the plan, in the engine's order, never empty
     * @throws SQLException if the statement cannot be planned
     */
    private static List<String> genericPlanOf(String emitted) throws SQLException {
        StringBuilder numbered = new StringBuilder(emitted.length() + 16);
        int placeholder = 0;
        for (int index = 0; index < emitted.length(); index++) {
            char character = emitted.charAt(index);
            if (character == '?') {
                placeholder++;
                numbered.append('$').append(placeholder);
            } else {
                numbered.append(character);
            }
        }
        List<String> plan = new ArrayList<>();
        try (Connection planner = POSTGRES.createConnection("?preferQueryMode=simple");
                Statement explain = planner.createStatement();
                ResultSet answer = explain.executeQuery(
                        "explain (generic_plan, costs off) " + numbered)) {
            while (answer.next()) {
                plan.add(answer.getString(1));
            }
        }
        if (plan.isEmpty()) {
            throw new SQLException("the engine returned no plan for " + numbered);
        }
        return plan;
    }

    /**
     * Reads one fingerprint belonging to a seeded card, so a plan is taken for a card that exists.
     *
     * <p>Assumptions: the fingerprint is READ from the identity relation rather than computed here.
     * Computing it would need the grouping key and would restate the digest expression a second time,
     * and the two could then disagree -- at which point the plan would be taken for a card that does
     * not exist and would look indexed while returning nothing.</p>
     *
     * @param connection an open connection able to read the identity relation
     * @return the fingerprint of one seeded card, never {@code null}
     * @throws SQLException if the read fails
     * @throws IllegalStateException if the seeding did not take effect
     */
    private static String aSeededFingerprint(Connection connection) throws SQLException {
        String fingerprint = singleValue(connection,
                "select card_fingerprint from " + CARD_IDENTITY_TABLE
                        + " where card_num like '" + PLAN_CARD_PREFIX + "%' limit 1");
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("no identity row exists for a seeded card, so the"
                    + " maintenance procedure did not run or the seeding did not commit");
        }
        return fingerprint;
    }

    /**
     * Seeds a production-like population of cards and transactions and analyses the relations.
     *
     * <p>Assumptions: the transactions are inserted by copying every non-key column from a committed
     * fixture row, so the seeded rows carry values the deployed schema already accepts and this method
     * need not know which columns are constrained. Writing literal values instead would have to be
     * revisited whenever the ledger's own migration adds a constraint.</p>
     *
     * <p>Assumptions: the identity rows for the seeded cards are created by CALLING the deployed
     * maintenance procedure rather than by inserting them here. Inserting them would be a second
     * implementation of the digest, and a plan taken against rows this class computed would prove
     * nothing about the rows the deployment computes.</p>
     *
     * <p>Assumptions: the relations are analysed before anything is planned. Without fresh statistics
     * the engine plans against the fixture's size and would reasonably choose a sequential scan, so an
     * un-analysed seeding would produce a failure that is a property of the arrangement rather than of
     * the query.</p>
     *
     * @param connection an open connection able to write the base relations
     * @throws SQLException if the population cannot be seeded
     */
    private static void seedRepresentativePopulation(Connection connection) throws SQLException {
        try (Statement seed = connection.createStatement()) {
            seed.executeUpdate("insert into " + CARD_XREF_TABLE
                    + " (card_num, customer_id, account_id) select '" + PLAN_CARD_PREFIX
                    + "' || lpad(g.i::text, 12, '0'), 800000000 + g.i, 80000000000 + g.i"
                    + " from generate_series(1, " + PLAN_CARD_COUNT + ") as g(i)");
            seed.executeUpdate("insert into " + TRANSACTION_TABLE
                    + " (transaction_id, card_num, type_cd, category_cd, source, description,"
                    + " amount, merchant_id, merchant_name, merchant_city, merchant_zip,"
                    + " orig_ts, proc_ts)"
                    + " select '" + PLAN_CARD_PREFIX + "' || lpad(g.i::text, 12, '0'),"
                    + " '" + PLAN_CARD_PREFIX + "' || lpad(((g.i % " + PLAN_CARD_COUNT
                    + ") + 1)::text, 12, '0'),"
                    + " template.type_cd, template.category_cd, template.source,"
                    + " template.description, template.amount, template.merchant_id,"
                    + " template.merchant_name, template.merchant_city, template.merchant_zip,"
                    + " template.orig_ts, template.proc_ts"
                    + " from generate_series(1, " + PLAN_TRANSACTION_COUNT + ") as g(i)"
                    + " cross join (select * from " + TRANSACTION_TABLE + " limit 1) as template");
            seed.execute("call reporting.refresh_card_identity()");
            analyseSeededRelations(seed);
        }
    }

    /**
     * Removes every seeded row and returns the relations to the committed fixture.
     *
     * <p>Assumptions: the rows are matched by the leading digit group this class seeds with, which the
     * committed fixture does not use -- its cards and identifiers lead with {@code 0500}, {@code 4859}
     * and {@code 9900}. A collision would not go unnoticed: the caller asserts the committed counts
     * afterwards, so a delete that took a fixture row with it fails there.</p>
     *
     * @param connection an open connection able to write the base relations
     * @throws SQLException if the rows cannot be removed
     */
    private static void discardRepresentativePopulation(Connection connection) throws SQLException {
        try (Statement discard = connection.createStatement()) {
            discard.executeUpdate("delete from " + TRANSACTION_TABLE
                    + " where transaction_id like '" + PLAN_CARD_PREFIX + "%'");
            discard.executeUpdate("delete from " + CARD_XREF_TABLE
                    + " where card_num like '" + PLAN_CARD_PREFIX + "%'");
            discard.execute("call reporting.refresh_card_identity()");
            analyseSeededRelations(discard);
        }
    }

    /**
     * Refreshes the statistics of the three relations the seeded plans depend on.
     *
     * @param statement an open statement on a connection able to analyse the relations
     * @throws SQLException if a relation cannot be analysed
     */
    private static void analyseSeededRelations(Statement statement) throws SQLException {
        statement.execute("analyze " + TRANSACTION_TABLE);
        statement.execute("analyze " + CARD_XREF_TABLE);
        statement.execute("analyze " + CARD_IDENTITY_TABLE);
    }

    /**
     * The narrowest context that can create the cursor's repository proxy.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason every sibling integration
     * test in this directory records: scanning the root would instantiate the orchestration client and
     * the filter chain, so a context started to walk a cursor would additionally need a state-machine
     * identifier and an object-store bucket, and a failure to supply either would read as a cursor
     * defect. Alternatives Considered: a data-access test slice, which would restrict
     * auto-configuration to persistence and remove the cloud values supplied above entirely. It is not
     * available -- the artifact carrying that annotation is absent from this module's dependency set
     * and from the reactor's managed set -- and adding a dependency to reshape a test is not a trade
     * this module makes.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class StatementCursorTestApplication {
    }

}
