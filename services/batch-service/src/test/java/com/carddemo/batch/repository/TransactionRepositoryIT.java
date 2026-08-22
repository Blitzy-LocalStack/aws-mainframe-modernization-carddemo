package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds the posted-transaction master's two properties that no sibling in this package reaches: that
 * two independent producers converge on the one table, and that the combine walk orders bytewise.
 *
 * <h2>Purpose, and the two members this class exists for</h2>
 *
 * <p>{@link TransactionRepository} declares three reads and inherits a write. One of the three, the
 * card-ordered daily-subset finder, is already owned by {@code PostingUnitOfWorkIT}, which asserts its
 * ordering and its strict window against a real engine. The other two had no executable consumer
 * anywhere in the module before this class: the unbounded ascending walk that rebuilds the master, and
 * the bounded continuation a restarted step resumes from. Those two, together with the convergence of
 * the two producers onto one primary key, are what this class owns.</p>
 *
 * <p>Refactoring Rationale: the two producers wrote SEPARATE physical datasets in the baseline and
 * write one table here, and the unification is transcribed rather than invented. Posting writes the
 * VSAM master from {@code 2900-WRITE-TRANSACTION-FILE}, whose paragraph opens at
 * {@code app/cbl/CBTRN02C.cbl:562} and whose write stands at {@code app/cbl/CBTRN02C.cbl:564}, reached
 * as the third write of the posting unit of work at {@code app/cbl/CBTRN02C.cbl:442}. Interest accrual
 * writes a different dataset entirely, a NEW generation of {@code AWS.M2.CARDDEMO.SYSTRAN} declared
 * across {@code app/jcl/INTCALC.jcl:37-41} at {@code LRECL=350} on line 39, from the paragraph
 * {@code 1300-B-WRITE-TX} at {@code app/cbl/CBACT04C.cbl:473} whose write stands at
 * {@code app/cbl/CBACT04C.cbl:500}. What licenses one table for both is that the baseline itself
 * already treated their union as the working set: the combine step's input is the backup generation
 * CONCATENATED with the system-transaction generation across {@code app/jcl/COMBTRAN.jcl:23-26}, so
 * the union was the real logical master and the two datasets were an artefact of how a sequential
 * utility had to be fed. Both emit the identical 350-byte layout at {@code app/cpy/CVTRA05Y.cpy},
 * which is why one entity and one interface serve both.</p>
 *
 * <h2>Proofs this class deliberately does not carry</h2>
 *
 * <p>Trade-offs: this class is the THIRD write of the posting unit of work and nonetheless contains no
 * atomicity assertion, which is a boundary rather than an omission. {@code PostingUnitOfWorkIT} is the
 * sole owner of that proof -- that the three writes performed in sequence at
 * {@code app/cbl/CBTRN02C.cbl:440-442} both commit and roll back as one transaction spanning two
 * schemas, observed from outside the failed transaction, with no saga, no two-phase commit and no
 * compensating reversal. It is cited here and not reproduced. The cost accepted is that a reader
 * following the third write arrives in a file that does not prove the property the write participates
 * in; the alternative cost is two files asserting one property, which is how a proof drifts -- one side
 * is updated, the other still passes, and nothing can report that they now describe different
 * behaviour.</p>
 *
 * <p>Assumptions: three further boundaries hold, and the package charter beside this file is the
 * authority for all of them. Tier one's interest test owns ALL interest arithmetic, including the
 * rounding boundary: the BASELINE divide truncates -- no {@code ROUNDED} phrase appears in
 * {@code app/cbl/CBACT04C.cbl} or anywhere else under {@code app/cbl} -- while the delivered target
 * reduces with {@code Money.GENERAL_ROUNDING}, which is {@code HALF_UP}, and that difference is
 * registered as divergence {@code C-ROUNDING}. No case here asserts any rounding behaviour on that
 * path. Tier two's job tests own the graded return-code tier, the inversion
 * of a baseline step gate into an orchestrator predicate, the business-date job parameter, the combine
 * step's job-level semantics, and parity against the reference goldens; this class owns only the
 * repository's ordering. And no case here asserts a byte image or the fixed-width encoding of a record:
 * this tier asserts ROWS, and where a field value matters the field is asserted.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 */
@Testcontainers
// Refactoring Rationale: the Parameter Store config-data location is disabled for this context. The
//     base profile's `optional:aws-parameterstore:` location builds a parameter-store client while
//     configuration is still loading, so a build host with no region configured aborts the context on an
//     unresolved placeholder before a single row is read. The sibling BatchRunRepositoryIT records the
//     full measurement, why disabling the LOAD is sufficient, and the two rejected alternatives; it is
//     cited from here rather than repeated.
@SpringBootTest(
        classes = TransactionRepositoryIT.PostedMasterPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class TransactionRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest and not a tag, so the engine under test cannot change beneath this
     * class when a tag is republished. Every sibling in this package names the same digest, which is what
     * lets a failure here be compared against a failure there without asking which engine each ran
     * on.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The harness script that supplies the four schemas this module does not own.
     *
     * <p>Assumptions: {@code ledger.transactions} arrives from THIS script and not from Flyway. Only
     * {@code batch.batch_run} and the job-repository tables come from the production migration on this
     * classpath, because the {@code ledger} schema belongs to transaction-service and its migration is
     * unreachable from here. The script is deliberately not a Flyway migration -- it carries no version
     * prefix and sits outside {@code db/migration} -- so its path is load-bearing rather than
     * descriptive.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * Rows arranged where a single fetch window must not be able to satisfy the walk.
     *
     * <p>Assumptions: this is {@link BatchConfig#CHUNK_SIZE} plus one rather than a locally written
     * hundred and one, and the reference to the constant is the point. The unbounded walk carries a
     * fetch-size hint of the same magnitude, so a fixture sized from the constant stays one row beyond
     * the window if that constant ever moves, whereas a copied literal would silently stop crossing the
     * boundary and the case would keep passing while proving less.</p>
     */
    private static final int ROWS_ACROSS_FETCH_BOUNDARY = BatchConfig.CHUNK_SIZE + 1;

    /**
     * Rows arranged for the cursor cases, small enough to reason about position by position.
     *
     * <p>Assumptions: five rows, which is the fewest that let a cursor be probed at two DIFFERENT
     * positions and still leave rows beyond both. The cursor cases assert which row a boundary excludes,
     * so they need to name specific positions rather than aggregate over a large set; crossing a fetch
     * window is the other cases' concern and a large fixture here would only obscure which row was
     * meant.</p>
     */
    private static final int ROWS_FOR_CURSOR_PROBES = 5;

    /**
     * The identifier of the row the posting golden records, sixteen digits with no punctuation.
     *
     * <p>Assumptions: measured from {@code tests/golden/posting/happy_path/tranfile.expected} rather
     * than composed here, so the shape this class calls "posting-shaped" is the shape the oracle
     * actually emits. Its freedom from punctuation is not incidental -- it is one half of the mixed
     * fixture the ordering case below depends on.</p>
     */
    private static final String POSTED_IDENTIFIER = "0000000000683580";

    /**
     * The first identifier the interest golden records, carrying the ISO business-date token.
     *
     * <p>Assumptions: measured from {@code tests/golden/interest/happy_path/transact.expected}. The
     * baseline composes it at {@code app/cbl/CBACT04C.cbl:476-480} by concatenating the business date
     * with a six-digit suffix incremented BEFORE use at {@code app/cbl/CBACT04C.cbl:474}, which is why
     * the first generated row of a run carries suffix {@code 000001} and not {@code 000000}.</p>
     */
    private static final String GENERATED_IDENTIFIER = "2024-01-15000001";

    /**
     * The second identifier the interest golden records, one suffix beyond the first.
     *
     * <p>Assumptions: measured from the same golden's second record. Two generated identifiers are
     * carried rather than one because the ordering case needs more than a single hyphenated value to
     * show that hyphenated identifiers order among themselves as well as against digit-only ones.</p>
     */
    private static final String GENERATED_IDENTIFIER_NEXT = "2024-01-15000002";

    /**
     * A digit-only identifier chosen so that byte ordering and punctuation-blind ordering disagree.
     *
     * <p>Assumptions: this value is DERIVED from the byte codes and not chosen for appearance. A hyphen
     * is {@code 0x2D} and a zero is {@code 0x30}, so a byte comparison of
     * {@code 2024-01-15000001} against this value decides at position five and puts the hyphenated value
     * FIRST. An ordering that gives punctuation no primary weight instead compares
     * {@code 20240115000001} against {@code 2024011400000001} and decides at the eighth digit, four
     * against five, which puts this value first. The two orders therefore disagree on this pair, which
     * is the whole reason it is in the fixture.</p>
     */
    private static final String PUNCTUATION_BLIND_DIVERGENT_IDENTIFIER = "2024011400000001";

    /**
     * The mixed-shape fixture the ordering case walks: two hyphenated identifiers and two digit-only.
     *
     * <p>Alternatives Considered: a fixture of one identifier shape, which is the obvious way to seed an
     * ordering case and cannot detect the failure that matters. Every identifier this table holds is
     * sixteen characters, so a set of digit-only identifiers orders identically under a byte comparison
     * and under any linguistic collation, and a case built from one would pass whatever the column's
     * collation was. Mixing the two shapes the two producers actually emit is what makes the assertion
     * capable of failing: the pair above is chosen so the two orders diverge, and the case asserts that
     * divergence explicitly so nobody can weaken the fixture back to one shape without a failure saying
     * why.</p>
     */
    private static final List<String> MIXED_SHAPE_IDENTIFIERS = List.of(
            POSTED_IDENTIFIER,
            GENERATED_IDENTIFIER,
            GENERATED_IDENTIFIER_NEXT,
            PUNCTUATION_BLIND_DIVERGENT_IDENTIFIER);

    /**
     * The two-character transaction type code both producers write.
     *
     * <p>Assumptions: two characters, matching {@code TRAN-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA05Y.cpy:6}. Interest accrual moves this literal at
     * {@code app/cbl/CBACT04C.cbl:482}, and the posting golden carries the same value, so one constant
     * serves both shapes.</p>
     */
    private static final String TYPE_CD = "01";

    /**
     * The category code a generated interest transaction carries, rendered at four characters.
     *
     * <p>Assumptions: the baseline moves a TWO-character literal {@code '05'} at
     * {@code app/cbl/CBACT04C.cbl:483}, yet the stored value is {@code 0005}, and the reason is the
     * receiving field rather than the literal. {@code TRAN-CAT-CD} is declared {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:7} -- numeric, not alphanumeric -- so the move right-justifies and
     * zero-fills instead of left-justifying and space-filling. The interest golden confirms
     * {@code 0005} at that field's span, which is why this constant is four characters and not two. A
     * constant written {@code 05} here would disagree with the oracle.</p>
     */
    private static final String GENERATED_CATEGORY_CD = "0005";

    /**
     * The category code the posting golden carries on its settled row.
     *
     * <p>Assumptions: measured at the same four-character span of
     * {@code tests/golden/posting/happy_path/tranfile.expected}. It differs from the generated
     * category above, which is what makes the two arranged shapes distinguishable by field rather than
     * only by identifier.</p>
     */
    private static final String POSTED_CATEGORY_CD = "0001";

    /**
     * The source a generated interest transaction carries, blank-padded to its declared width.
     *
     * <p>Assumptions: the baseline moves the six-character literal {@code 'System'} at
     * {@code app/cbl/CBACT04C.cbl:484} into {@code TRAN-SOURCE PIC X(10)} at
     * {@code app/cpy/CVTRA05Y.cpy:8}, and an alphanumeric move space-fills to the right. The padded form
     * is written here rather than the bare literal so that the arranged row matches what the loader
     * stores and what the golden records, which removes any question about whether the engine or this
     * class supplied the padding.</p>
     */
    private static final String GENERATED_SOURCE = "System    ";

    /**
     * The source the posting golden carries on its settled row, blank-padded to its declared width.
     *
     * <p>Assumptions: measured from the golden at the same ten-character span, padded for the same
     * reason as the generated source above.</p>
     */
    private static final String POSTED_SOURCE = "POS TERM  ";

    /**
     * The fixed opening of the description a generated interest transaction carries.
     *
     * <p>Assumptions: thirteen characters including the trailing space, exactly as the literal stands in
     * the concatenation at {@code app/cbl/CBACT04C.cbl:485-489}, which appends the account identifier to
     * it. The prefix is held separately from any account identifier so the case can assert the fixed
     * part without asserting the padding that follows the variable part -- the interest path leaves the
     * remainder of the field as low values where the posting path space-fills it, and that difference
     * belongs to the record mapper rather than to this tier.</p>
     */
    private static final String GENERATED_DESCRIPTION_PREFIX = "Int. for a/c ";

    /**
     * The account identifier the interest golden's first record names in its description.
     *
     * <p>Assumptions: eleven digits, measured from the golden immediately after the description prefix.
     * It is a fixture value here and carries no assertion about account data, which
     * {@code PostingUnitOfWorkIT} owns.</p>
     */
    private static final String GENERATED_ACCOUNT_ID = "00000000001";

    /**
     * The merchant identifier a generated interest transaction carries: none.
     *
     * <p>Assumptions: zero is a legitimate stored value and not an absent one. The baseline moves it
     * explicitly at {@code app/cbl/CBACT04C.cbl:491}, and the interest golden shows nine zero digits at
     * the merchant-identifier span rather than blanks, so the column holds zero rather than null.</p>
     */
    private static final long GENERATED_MERCHANT_ID = 0L;

    /**
     * The merchant identifier the posting golden carries on its settled row.
     *
     * <p>Assumptions: measured from the golden's nine-digit merchant span. A non-zero value is carried
     * so the two arranged shapes differ on this field too, which is what lets the convergence case tell
     * them apart without reading their identifiers.</p>
     */
    private static final long POSTED_MERCHANT_ID = 800000000L;

    /**
     * The amount the interest golden records, in exact fixed point at scale two.
     *
     * <p>Assumptions: decoded from the golden's zoned-decimal span of ten digits {@code 0000000125}
     * followed by an opening-brace overpunch, that brace being the sign overpunch for a positive zero
     * digit, which gives twelve dollars and fifty cents. It is constructed from a STRING rather than
     * from a literal number so no binary
     * floating-point value exists at any point: the migration plan's transformation rule T3 forbids
     * {@code float} and {@code double} in the money path, and
     * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
     * fails the build on a violation.</p>
     */
    private static final BigDecimal GENERATED_AMOUNT = new BigDecimal("12.50");

    /**
     * The amount the posting golden records, in exact fixed point at scale two.
     *
     * <p>Assumptions: decoded from the golden's zoned-decimal span {@code 0000005047G}, where the
     * trailing {@code G} is the sign overpunch for a positive seven, giving five hundred and four
     * dollars seventy-seven. Constructed from a string for the same reason as the amount above.</p>
     */
    private static final BigDecimal POSTED_AMOUNT = new BigDecimal("504.77");

    /**
     * A filler amount for rows arranged only to occupy positions in an ordered walk.
     *
     * <p>Assumptions: the walk cases assert ordering and never amounts, so a single benign value serves
     * every row they arrange. It is nonetheless a real scale-two value rather than zero, because a
     * column declared {@code NUMERIC(11,2)} and never given a fractional digit would leave the round
     * trip that the money case does assert untested by accident.</p>
     */
    private static final BigDecimal FILLER_AMOUNT = new BigDecimal("1.01");

    /**
     * The card number the interest golden's first record carries.
     *
     * <p>Assumptions: sixteen digits, measured from the golden. The baseline takes this field from the
     * cross-reference lookup at {@code app/cbl/CBACT04C.cbl:495}, reached through the
     * account-identifier alternate index rather than the card-number path; {@code CardXrefRepositoryIT}
     * owns the proof that the by-account path is determined when an account holds several cards, and
     * this class carries the value only as a stored field.</p>
     */
    private static final String GENERATED_CARD_NUM = "9680294154603697";

    /**
     * The card number the posting golden carries on its settled row.
     *
     * <p>Assumptions: sixteen digits, measured from the golden's card span.</p>
     */
    private static final String POSTED_CARD_NUM = "4859452612877065";

    /**
     * A synthetic card number for rows arranged only to occupy positions in an ordered walk.
     *
     * <p>Assumptions: deliberately synthetic rather than drawn from the reference data, so a value
     * appearing in a diagnostic cannot be mistaken for a number the shipped fixtures carry.</p>
     */
    private static final String FILLER_CARD_NUM = "0000000000009902";

    /**
     * The blank merchant name and city a generated interest transaction carries.
     *
     * <p>Assumptions: fifty characters of blank, matching the width
     * {@code app/cpy/CVTRA05Y.cpy:12-13} declares and the {@code MOVE SPACES} the baseline performs at
     * {@code app/cbl/CBACT04C.cbl:492-493}. The blanks are written explicitly rather than left null
     * because null and blank are different stored states and the baseline produces the second.</p>
     */
    private static final String BLANK_MERCHANT_TEXT = " ".repeat(50);

    /**
     * The blank merchant postal code a generated interest transaction carries.
     *
     * <p>Assumptions: ten characters of blank, matching {@code app/cpy/CVTRA05Y.cpy:14} and the
     * {@code MOVE SPACES} at {@code app/cbl/CBACT04C.cbl:494}.</p>
     */
    private static final String BLANK_MERCHANT_ZIP = " ".repeat(10);

    /**
     * The origination stamp the arranged rows carry, at microsecond resolution.
     *
     * <p>Assumptions: a literal, never a clock read. A value compared against the current time passes
     * for a reason unrelated to the code under test and fails only when two reads straddle a boundary,
     * which reproduces at the hour it was introduced and at no other. The reference suite injects the
     * business date as a parameter for the same reason, and {@code app/jcl/INTCALC.jcl:22} supplies it
     * as {@code PARM='2022071800'} rather than letting the program read one. The fractional part is
     * non-zero on purpose, so the round trip exercises the resolution the column is declared at instead
     * of a whole second that would survive a coarser column.</p>
     */
    private static final LocalDateTime ORIGINATED_AT =
            LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_000);

    /**
     * The processing stamp the arranged rows carry.
     *
     * <p>Assumptions: a literal for the same reason as the origination stamp, and a distinct value so a
     * mapping that crossed the two columns would be visible. The goldens cannot supply either stamp:
     * the reference suite normalises processing timestamps before comparison, as
     * {@code tests/README.md} section 11 records, so both stamp spans stand blank in the committed
     * golden and no timestamp assertion in this class is derived from one.</p>
     */
    private static final LocalDateTime PROCESSED_AT =
            LocalDateTime.of(2024, 1, 15, 2, 30, 15, 654_321_000);

    /**
     * The number of leading characters a masked card number replaces.
     *
     * <p>Assumptions: twelve of sixteen, leaving the last four. That is the masking contract the
     * migration plan fixes at its section 0.7.8 for a primary account number outside the one
     * administrative endpoint, and this class holds to it in every diagnostic even though its card
     * numbers are fixture values, because a failure message is the one place an unmasked number would be
     * read and copied.</p>
     */
    private static final int MASKED_PREFIX_LENGTH = 12;

    /**
     * Simple type names that would mean this interface had adopted offset positioning.
     *
     * <p>Assumptions: the check below is a DENY LIST of simple names compared as strings, and neither
     * type is imported by this class. Importing them to compare class literals would put the very
     * vocabulary the charter prohibits into this file's import block, where a later reader could not
     * tell a proof of absence from an adoption. Comparing simple names also catches either type
     * arriving from a different package than the one expected today.</p>
     */
    private static final Set<String> OFFSET_POSITIONING_TYPE_NAMES = Set.of("Pageable", "Page");

    /**
     * The engine this class asserts against, started once and destroyed with the class.
     *
     * <p>Alternatives Considered: one shared abstract base class holding the container for every member
     * of this package. Rejected, and the package charter carries the ruling: a container held in a base
     * class is shared mutable state, so rows one class inserts are rows another reads, and the failure
     * then names whichever class happened to run second. Trade-offs: the accepted cost is another
     * container start and another copy of this declaration, paid whenever the declaration changes. What
     * it buys is that this class can be run alone and still mean something.</p>
     *
     * <p>Assumptions: the harness script is handed to the container rather than executed from a case,
     * so it runs as the container becomes ready, which is strictly before the context opens a connection
     * and therefore before Flyway and before schema validation.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The interface under test, injected as the context builds it over the container's pool. */
    @Autowired
    private TransactionRepository ledger;

    /**
     * The boundary the unbounded walk requires, since that member declares mandatory propagation.
     *
     * <p>Assumptions: a template rather than an annotation on a case. The walk returns a cursor whose
     * validity ends with the transaction that opened it, so the transaction has to be visibly open
     * across the whole consumption rather than implied by an annotation on the method the framework
     * calls.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** The handle the arrange steps issue their row statements through. */
    private JdbcTemplate jdbc;

    /**
     * Registers the container's generated coordinates as this context's datasource and Flyway settings.
     *
     * @param registry the Spring test property registry, of type {@link DynamicPropertyRegistry}, that
     *     this method adds the container's JDBC URL, user name and credential to as deferred suppliers;
     *     must not be {@code null}
     */
    // Assumptions: no connection literal appears anywhere in this class or in the test profile, and none
    //     may be introduced. A container assigns its host port as it starts, so a literal authored
    //     beforehand would either address nothing or -- the worse outcome, because it PASSES -- address
    //     whatever database happened to be listening.
    // Assumptions: the Flyway pair is registered in addition to the datasource pair because the base
    //     profile binds those two keys to placeholders with no fallback, and the framework reads them
    //     only when no connection-details bean supplies them instead. That is this module's case: the
    //     annotation contributing such a bean ships in an artifact this POM deliberately does not
    //     declare, so leaving them unregistered aborts the context on an unresolved placeholder before
    //     any migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the master and opens a JDBC handle before each case.
     *
     * <p>Assumptions: rows are deleted rather than each case being wrapped in a rolled-back transaction.
     * An enclosing test transaction would make every walk read that transaction's own uncommitted
     * buffer, which is the reading the arrange steps are designed to avoid. Deleting between cases keeps
     * each one independent in the spirit {@code tests/README.md} section 11 states for the reference
     * suite, which is what lets any single case here be run alone and still mean something.</p>
     *
     * <p>Assumptions: only ROWS are removed. The statement is data manipulation and never a definition,
     * because this table's structure belongs to
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} and its
     * test-time shape to the harness script. This class authors no definition of any kind, and in
     * particular declares no index: {@code idx_transactions_card_num} and
     * {@code idx_transactions_proc_ts} are that migration's, the second being the replacement for the
     * alternate index {@code app/jcl/TRANIDX.jcl} builds -- and index BUILDING is retired rather than
     * migrated, because this engine maintains an index transactionally.</p>
     *
     * @param dataSource the connection pool, of type {@link DataSource}, that the context built from
     *     the container's coordinates, wrapped here for the arrange statements; must not be
     *     {@code null}
     */
    @BeforeEach
    void emptyPostedMaster(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(
                status -> this.jdbc.update("DELETE FROM ledger.transactions"));
    }

    /**
     * Confirms a row shaped as posting emits one survives a round trip through the engine with the
     * field values the posting golden records.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:442}, the third write of the posting unit of work, whose
     * paragraph {@code 2900-WRITE-TRANSACTION-FILE} opens at {@code app/cbl/CBTRN02C.cbl:562} and
     * writes at {@code app/cbl/CBTRN02C.cbl:564}, against the record
     * {@code tests/golden/posting/happy_path/tranfile.expected}. Every asserted value was measured from
     * that golden at the field spans {@code app/cpy/CVTRA05Y.cpy} declares.</p>
     */
    @Test
    @DisplayName("a posting-shaped row round trips with the field values the posting golden records")
    void postingShapedRowRoundTripsWithItsGoldenFieldValues() {
        this.persist(this.postingShapedRow(POSTED_IDENTIFIER));

        Transaction reloaded = this.reload(POSTED_IDENTIFIER);

        assertThat(reloaded.getTypeCd())
                .as("the two-character type code survives the round trip")
                .isEqualTo(TYPE_CD);
        assertThat(reloaded.getCategoryCd())
                .as("the four-character category code survives the round trip")
                .isEqualTo(POSTED_CATEGORY_CD);
        assertThat(reloaded.getSource())
                .as("the blank-padded source survives the round trip at its declared width")
                .isEqualTo(POSTED_SOURCE);
        assertThat(reloaded.getMerchantId())
                .as("a non-zero merchant identifier survives the round trip")
                .isEqualTo(POSTED_MERCHANT_ID);

        // Assumptions: compareTo and not equals, expressed here through AssertJ's comparison
        //     assertion. BigDecimal.equals is scale-sensitive, so a stored value that came back as
        //     504.770 would be unequal to 504.77 while representing the same money -- and the reverse
        //     mistake is worse, because a case written with equals passes today only because this
        //     column happens to declare scale two and would start failing on a widened scale that had
        //     changed nothing observable.
        assertThat(reloaded.getAmount())
                .as("the amount survives the round trip as the same money")
                .isEqualByComparingTo(POSTED_AMOUNT);
        // Assumptions: the scale is asserted SEPARATELY and against the shared money contract's own
        //     constant rather than against a literal two. The comparison above deliberately ignores
        //     scale, so on its own it would accept a column that had stopped storing cents; this second
        //     assertion is what holds the exact fixed-point contract the migration plan's rule T3 states
        //     for NUMERIC(11,2).
        assertThat(reloaded.getAmount().scale())
                .as("the amount is stored in exact fixed point at the money contract's scale")
                .isEqualTo(Money.SCALE);

        // Assumptions: the stamp is asserted through the shared formatter rather than a formatter
        //     written here, because the property being checked is that the column's resolution matches
        //     the twenty-six-character reference form -- and that form's definition lives in
        //     TimestampFormatter, which states its own length constant. Hand-rolling a pattern here
        //     would create a second definition of the same contract, free to disagree with the first.
        //     This asserts the COLUMN's fidelity only; the strict upper bound of the daily window over
        //     this same column belongs to PostingUnitOfWorkIT.
        assertThat(TimestampFormatter.format(reloaded.getOrigTs()))
                .as("the origination stamp survives at the resolution the reference form carries")
                .isEqualTo(TimestampFormatter.format(ORIGINATED_AT))
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);

        // Assumptions: the card number is compared MASKED on both sides, so neither the actual nor the
        //     expected value can reach a failure message in full. Masking the expectation as well as the
        //     actual is what makes that hold -- masking only one side would print the other verbatim in
        //     the diff the assertion emits on failure.
        assertThat(maskedCard(reloaded.getCardNum()))
                .as("the card number survives the round trip, asserted masked to its last four")
                .isEqualTo(maskedCard(POSTED_CARD_NUM));
    }

    /**
     * Confirms a row shaped as interest accrual emits carries every constant the interest golden
     * records, including the four-character rendering of its category code.
     *
     * <p>Pins the paragraph {@code 1300-B-WRITE-TX} at {@code app/cbl/CBACT04C.cbl:473-500} against the
     * first record of {@code tests/golden/interest/happy_path/transact.expected}: the type code moved at
     * {@code app/cbl/CBACT04C.cbl:482}, the category code at {@code app/cbl/CBACT04C.cbl:483}, the
     * source at {@code app/cbl/CBACT04C.cbl:484}, the description concatenated at
     * {@code app/cbl/CBACT04C.cbl:485-489}, the amount at {@code app/cbl/CBACT04C.cbl:490}, and the
     * absent merchant at {@code app/cbl/CBACT04C.cbl:491-494}.</p>
     */
    @Test
    @DisplayName("an interest-shaped row carries every constant the interest golden records")
    void interestShapedRowCarriesEveryConstantTheInterestGoldenRecords() {
        this.persist(this.interestShapedRow(GENERATED_IDENTIFIER));

        Transaction reloaded = this.reload(GENERATED_IDENTIFIER);

        assertThat(reloaded.getTypeCd())
                .as("the generated row carries the type code the accrual program moves")
                .isEqualTo(TYPE_CD);
        // Assumptions: FOUR characters are asserted and not two, and the distinction is the trap this
        //     assertion exists for. The program moves the two-character literal '05', but the receiving
        //     field is declared PIC 9(04) at app/cpy/CVTRA05Y.cpy:7, so the move right-justifies and
        //     zero-fills rather than left-justifying and space-filling. The golden confirms 0005 at that
        //     span. An assertion written against '05' would contradict the oracle while appearing to
        //     transcribe the program.
        assertThat(reloaded.getCategoryCd())
                .as("the generated category code is stored zero-filled to four characters")
                .isEqualTo(GENERATED_CATEGORY_CD);
        assertThat(reloaded.getSource())
                .as("the generated source is stored blank-padded within its declared width")
                .isEqualTo(GENERATED_SOURCE);
        assertThat(reloaded.getDescription())
                .as("the generated description opens with the fixed literal and names the account")
                .startsWith(GENERATED_DESCRIPTION_PREFIX)
                .isEqualTo(GENERATED_DESCRIPTION_PREFIX + GENERATED_ACCOUNT_ID);
        assertThat(reloaded.getMerchantId())
                .as("a generated row records zero rather than no merchant identifier")
                .isEqualTo(GENERATED_MERCHANT_ID);
        assertThat(reloaded.getMerchantName())
                .as("the generated merchant name is blank rather than absent")
                .isNotNull()
                .isBlank();
        assertThat(reloaded.getMerchantCity())
                .as("the generated merchant city is blank rather than absent")
                .isNotNull()
                .isBlank();
        assertThat(reloaded.getMerchantZip())
                .as("the generated merchant postal code is blank rather than absent")
                .isNotNull()
                .isBlank();
        assertThat(reloaded.getAmount())
                .as("the generated amount survives the round trip as the same money")
                .isEqualByComparingTo(GENERATED_AMOUNT);
        assertThat(reloaded.getAmount().scale())
                .as("the generated amount is stored at the money contract's scale")
                .isEqualTo(Money.SCALE);
        assertThat(maskedCard(reloaded.getCardNum()))
                .as("the cross-referenced card number survives, asserted masked to its last four")
                .isEqualTo(maskedCard(GENERATED_CARD_NUM));
    }

    /**
     * Confirms rows from both producers occupy the one master under one primary key without collision,
     * and stay individually retrievable and distinguishable by field.
     *
     * <p>Pins the convergence of {@code app/cbl/CBTRN02C.cbl:442} and
     * {@code app/cbl/CBACT04C.cbl:500} onto a single table. In the baseline these two writes reach
     * different physical datasets -- the VSAM master, and a new {@code SYSTRAN} generation declared
     * across {@code app/jcl/INTCALC.jcl:37-41} -- so no reference artifact exercises their coexistence
     * directly. What licenses asserting it is {@code app/jcl/COMBTRAN.jcl:23-26}, where the combine step
     * feeds the sort with the backup generation CONCATENATED with the system-transaction generation:
     * their union was already the working set, and this case is that union made durable in one table.</p>
     */
    @Test
    @DisplayName("rows from both producers coexist on the one master without key collision")
    void rowsFromBothProducersCoexistOnTheOneMasterWithoutKeyCollision() {
        this.persist(this.postingShapedRow(POSTED_IDENTIFIER));
        this.persist(this.interestShapedRow(GENERATED_IDENTIFIER));

        // Assumptions: the count is asserted before the two reads, because the failure this case is
        //     really guarding against is a SILENT one. Had the two rows collided on the primary key the
        //     second write would have raised, so a surviving pair of rows is the observable evidence that
        //     one key space accommodates both identifier shapes.
        assertThat(this.ledger.count())
                .as("both producers' rows are durable side by side in the one master")
                .isEqualTo(2L);

        Transaction posted = this.reload(POSTED_IDENTIFIER);
        Transaction generated = this.reload(GENERATED_IDENTIFIER);

        // Assumptions: the two rows are distinguished by FIELDS rather than by their identifiers, which
        //     is what makes this a convergence assertion rather than a restatement of the two reads
        //     above. Reading back the row a key was written under proves only that the key round-trips;
        //     showing that the posting row still carries the posting source and merchant while the
        //     generated row carries the generated ones proves that neither write overwrote any part of
        //     the other.
        assertThat(posted.getSource())
                .as("the posting row keeps its own source alongside the generated row")
                .isEqualTo(POSTED_SOURCE);
        assertThat(generated.getSource())
                .as("the generated row keeps its own source alongside the posting row")
                .isEqualTo(GENERATED_SOURCE);
        assertThat(posted.getMerchantId())
                .as("the posting row keeps its own merchant identifier")
                .isEqualTo(POSTED_MERCHANT_ID);
        assertThat(generated.getMerchantId())
                .as("the generated row keeps its own absent-merchant zero")
                .isEqualTo(GENERATED_MERCHANT_ID);
        assertThat(posted.getCategoryCd())
                .as("the two rows remain distinguishable by category code")
                .isNotEqualTo(generated.getCategoryCd());
    }

    /**
     * Confirms the keyed existence check answers correctly for an identifier that is present and for one
     * that is absent.
     *
     * <p>Pins the convergence again, from the side a caller would actually probe it from, and there is
     * no baseline citation for the probe itself: neither producer probes before writing. Posting writes
     * and then tests the resulting file status at {@code app/cbl/CBTRN02C.cbl:564-566}, and accrual does
     * the same at {@code app/cbl/CBACT04C.cbl:500-501}. What IS transcribed is the key space the check
     * answers over -- {@code app/jcl/TRANFILE.jcl} defines the master cluster on a sixteen-byte key at
     * offset zero, which {@code app/cpy/CVTRA05Y.cpy:5} declares as the whole of {@code TRAN-ID}.</p>
     */
    @Test
    @DisplayName("the keyed existence check answers for both a present and an absent identifier")
    void keyedExistenceCheckAnswersForBothAPresentAndAnAbsentIdentifier() {
        this.persist(this.interestShapedRow(GENERATED_IDENTIFIER));

        // Trade-offs: an existence check rather than a fetch, accepting that it returns no field values.
        //     A fetch would answer the same question and carry the row with it, which is exactly why it
        //     is the weaker instrument here: the question is whether ONE KEY is occupied, and a fetch
        //     answers it by materialising a row whose fields then invite assertions that belong to the
        //     round-trip cases above. Both directions are asserted because a check that answered true
        //     unconditionally would satisfy a present-key case on its own, and that is the defect most
        //     likely to reach a probe of this shape.
        assertThat(this.ledger.existsById(GENERATED_IDENTIFIER))
                .as("an identifier that was written is reported present")
                .isTrue();
        assertThat(this.ledger.existsById(PUNCTUATION_BLIND_DIVERGENT_IDENTIFIER))
                .as("an identifier that was never written is reported absent")
                .isFalse();

        // Assumptions: the absent probe uses a SIXTEEN-character identifier of the same declared width
        //     rather than an obviously invalid short value. The column is CHAR(16), and a shorter probe
        //     would be blank-padded before comparison, so a false answer could be produced by the width
        //     mismatch instead of by the key genuinely being unoccupied.
        assertThat(PUNCTUATION_BLIND_DIVERGENT_IDENTIFIER)
                .as("the absent probe is a well-formed identifier of the declared width")
                .hasSameSizeAs(GENERATED_IDENTIFIER);
    }

    /**
     * Confirms both ten-character business-date forms yield a storable, retrievable sixteen-character
     * identifier, without either being treated as the canonical one.
     *
     * <p>Pins the identifier composition at {@code app/cbl/CBACT04C.cbl:474-480}, which concatenates
     * {@code PARM-DATE} with a six-digit suffix. Two ten-character forms are in play and neither is
     * privileged: the committed golden
     * {@code tests/golden/interest/happy_path/transact.expected} carries the ISO form
     * {@code 2024-01-15}, while {@code app/jcl/INTCALC.jcl:22} supplies the compact form as
     * {@code PARM='2022071800'}, being eight date digits followed by two zeros. The business-date
     * PARAMETER contract belongs to tier two's accrual job test; all this class needs is that the column
     * accommodates either.</p>
     */
    @Test
    @DisplayName("either ten-character business-date form yields a storable sixteen-character key")
    void eitherBusinessDateFormYieldsAStorableSixteenCharacterKey() {
        String compactFormIdentifier = "2022071800" + "000001";

        this.persist(this.interestShapedRow(GENERATED_IDENTIFIER));
        this.persist(this.interestShapedRow(compactFormIdentifier));

        // Assumptions: the identifier is treated as OPAQUE and sixteen characters wide, and this case
        //     deliberately parses neither form. Ten characters of business date plus six of suffix is
        //     sixteen either way, so the column's contract is satisfied by both without the store
        //     needing to know which convention produced a given value. Asserting that one form were
        //     canonical would invent a rule the baseline does not state -- the accrual program moves
        //     whatever PARM-DATE it was given.
        assertThat(GENERATED_IDENTIFIER)
                .as("the ISO business-date form composes a sixteen-character identifier")
                .hasSize(compactFormIdentifier.length());
        assertThat(this.ledger.existsById(GENERATED_IDENTIFIER))
                .as("an identifier built from the ISO form is retrievable by key")
                .isTrue();
        assertThat(this.ledger.existsById(compactFormIdentifier))
                .as("an identifier built from the compact form is retrievable by key")
                .isTrue();
        assertThat(this.ledger.count())
                .as("the two forms occupy two distinct keys rather than colliding")
                .isEqualTo(2L);
    }

    /**
     * Confirms the unbounded walk delivers the whole master in strictly ascending identifier order and
     * keeps that order past the point where one fetch window cannot satisfy it.
     *
     * <p>Pins the ordering contract the combine step declares, which is transcribed rather than chosen.
     * That step is a sort utility invocation at {@code app/jcl/COMBTRAN.jcl:22}; its symbol definition at
     * {@code app/jcl/COMBTRAN.jcl:28} reads {@code TRAN-ID,1,16,CH}, a sixteen-byte CHARACTER field at
     * one-based position one, and its direction at {@code app/jcl/COMBTRAN.jcl:30} is
     * {@code SORT FIELDS=(TRAN-ID,A)}, ascending. The migration plan's transformation rule T6 maps a sort
     * clause of that form onto an {@code ORDER BY}, which is the member under test.</p>
     */
    @Test
    @DisplayName("the combine walk delivers the whole master in strictly ascending identifier order")
    void combineWalkDeliversTheWholeMasterInStrictlyAscendingIdentifierOrder() {
        // Alternatives Considered: arranging the rows in ascending order, which is the obvious fixture
        //     and proves nothing. Rows stored in the order they are asserted in come back in that order
        //     from an unordered scan just as readily as from an ordered one, so the case would pass with
        //     the ORDER BY removed entirely. Seeding in DESCENDING order means the asserted sequence is
        //     the exact reverse of the insertion sequence, and only the query can account for the
        //     difference.
        List<String> seeded = this.seedDescendingIdentifiers(ROWS_ACROSS_FETCH_BOUNDARY);

        List<String> walked = this.walkedIdentifiers();

        assertThat(walked)
                .as("the walk covers the whole master rather than stopping at the first fetch boundary")
                .hasSize(ROWS_ACROSS_FETCH_BOUNDARY);
        // Assumptions: sorted-and-no-duplicates together mean STRICTLY ascending, and the pair is
        //     asserted rather than only the endpoints. A comparison of the first and last identifiers
        //     alone is satisfied by a result that is out of order in the middle, which is precisely the
        //     shape a second fetch could introduce and the shape this case exists to exclude.
        assertThat(walked)
                .as("every identifier is greater than the one before it, across the whole result")
                .isSorted()
                .doesNotHaveDuplicates();
        // Assumptions: the insertion sequence is asserted to DIFFER from the walked sequence, so the
        //     fixture cannot quietly stop being out of order. Without this the seeding helper could be
        //     changed to ascending and every other assertion here would still pass while proving nothing
        //     about the query's ordering.
        assertThat(seeded)
                .as("the arranged insertion sequence differs from the sequence the walk returns")
                .isNotEqualTo(walked);
        assertThat(walked)
                .as("the walk returns exactly the arranged rows, reordered rather than filtered")
                .containsExactlyInAnyOrderElementsOf(seeded);
    }

    /**
     * Confirms the walk orders a fixture of MIXED identifier shapes bytewise rather than linguistically,
     * which is the property that keeps the rebuilt master byte-identical to the reference sort's output.
     *
     * <p>Pins {@code app/jcl/COMBTRAN.jcl:28}, where the sort field is declared {@code CH} -- character
     * format, which is a byte comparison of the sixteen bytes as they stand. The fixture is drawn from
     * both producers, so it mixes the hyphenated identifiers
     * {@code tests/golden/interest/happy_path/transact.expected} records with the digit-only identifier
     * {@code tests/golden/posting/happy_path/tranfile.expected} records.</p>
     */
    @Test
    @DisplayName("the combine walk orders mixed identifier shapes bytewise, not linguistically")
    void combineWalkOrdersMixedIdentifierShapesBytewiseNotLinguistically() {
        // Assumptions: the expected sequence is COMPUTED as a byte comparison rather than transcribed
        //     from a previous run's output. Java's natural String order compares UTF-16 code units,
        //     which for these ASCII identifiers is a byte comparison and therefore the same rule the
        //     reference's CH format applies. Copying an observed sequence instead would make the case
        //     agree with whatever the engine did last, including agreeing with a regression.
        List<String> expectedByByteOrder = MIXED_SHAPE_IDENTIFIERS.stream().sorted().toList();

        // Alternatives Considered: asserting the byte order and leaving it there, which is what this
        //     case looked like before the fixture was measured. It is not enough, because a fixture of
        //     one identifier shape orders identically under every collation and would pass whatever the
        //     column's collation was. The two orders below are therefore compared to EACH OTHER first:
        //     if they ever agree, this fixture has stopped being able to detect a collation problem and
        //     this assertion says so instead of the case silently weakening.
        List<String> punctuationBlindOrder = MIXED_SHAPE_IDENTIFIERS.stream()
                .sorted(Comparator.comparing(identifier -> identifier.replace("-", "")))
                .toList();
        assertThat(expectedByByteOrder)
                .as("the fixture discriminates: byte order and a punctuation-blind order disagree on it")
                .isNotEqualTo(punctuationBlindOrder);

        this.seedMixedShapeIdentifiers();

        List<String> walked = this.walkedIdentifiers();

        // ⚠️ Assumptions: this depends on an EXTERNAL contract -- that the identifier column is collated
        //     bytewise -- and not on anything this class can arrange. The contract is declared on the
        //     data in two places that must agree: the harness script this container is initialised from
        //     declares `transaction_id CHAR(16) COLLATE "C"`, and
        //     transaction-service/src/main/resources/db/migration/V3__ledger_bytewise_collation.sql
        //     pins the same collation on the deployed column. The pin has to live on the COLUMN because
        //     a query-level COLLATE reaches only the statement carrying it and a derived finder cannot
        //     express one at all. Should that pin ever be absent, the column falls back to the
        //     cluster's default -- a property of how the cluster was created rather than anything this
        //     schema states -- and the same master could be rebuilt in two different sequences from
        //     identical input with nothing reporting it. That is a blocking coordination finding against
        //     V1__ledger.sql and its collation migration, and this assertion is deliberately left able
        //     to fail rather than relaxed to accommodate it.
        assertThat(walked)
                .as("mixed-shape identifiers come back in byte order, hyphen sorting before a digit")
                .containsExactlyElementsOf(expectedByByteOrder);
    }

    /**
     * Confirms the walk over an empty master delivers no row rather than raising.
     *
     * <p>Pins the combine step's behaviour on an empty input, for which the reference has no dedicated
     * artifact: {@code app/jcl/COMBTRAN.jcl:23-26} hands the sort whole generations without asserting
     * that either holds a record, so an empty generation is an ordinary input there and an empty result
     * is the ordinary outcome here. An ordered scan discovers the end by reaching it.</p>
     */
    @Test
    @DisplayName("the combine walk over an empty master delivers no row")
    void combineWalkOverAnEmptyMasterDeliversNoRow() {
        // Assumptions: no arrange step runs, and the emptiness comes from the per-case row deletion
        //     rather than from a fresh container. That is what makes this case meaningful when it runs
        //     after a case that seeded a hundred and one rows: it asserts that the walk reports the
        //     master's CURRENT state and not a cached or accumulated one.
        assertThat(this.ledger.count())
                .as("the master is empty before the walk opens")
                .isZero();

        assertThat(this.walkedIdentifiers())
                .as("a walk of an empty master yields no row rather than raising")
                .isEmpty();
    }

    /**
     * Confirms the bounded continuation excludes the row carrying the cursor identifier, resumes at the
     * very next one, and stops at the cap it was given.
     *
     * <p>There is NO baseline citation for the continuation itself, and saying so is more useful than
     * reaching for a loose one: the reference has no checkpoint contract to be faithful to. The only
     * {@code RESTART=} anywhere in it is commented out, at {@code app/jcl/DEFGDGD.jcl:2}, and no
     * checkpoint clause appears in any of its jobs, so resumption is a documented target-side
     * IMPROVEMENT rather than a port. It exists so a redriven state-machine execution can restart a step
     * that already processed part of its input. What IS transcribed is the key space the cursor moves
     * through: {@code app/jcl/TRANFILE.jcl} keys the master cluster on a sixteen-byte key at offset zero,
     * which {@code app/cpy/CVTRA05Y.cpy:5} declares as the whole of {@code TRAN-ID}, so the ordering is
     * total and exactly one row carries any given boundary value. Whether a step of a run already reached
     * a terminal state is a different question answered by a different table, and
     * {@code BatchRunRepositoryIT} owns the uniqueness proof over it.</p>
     */
    @Test
    @DisplayName("the continuation excludes the cursor row, resumes at the next one and honours its cap")
    void continuationExcludesTheCursorRowResumesAtTheNextAndHonoursItsCap() {
        List<String> ascending = this.walkedIdentifiersAfterSeeding(ROWS_FOR_CURSOR_PROBES);

        String cursor = ascending.get(1);
        List<String> continued =
                identifiersOf(this.ledger.findByTransactionIdGreaterThanOrderByTransactionIdAsc(
                        cursor, Limit.of(2)));

        // Assumptions: the predicate is STRICTLY greater and not greater-or-equal, and the direction is a
        //     correctness decision rather than a stylistic one. A caller resumes from the identifier of a
        //     row it has ALREADY processed, so an inclusive bound would hand that row back and the step
        //     would emit it a second time into the combined output -- a duplicated record in a stream the
        //     golden master compares byte for byte. The exclusion is asserted as a NAMED boundary rather
        //     than inferred from a loop that happens to terminate, because an inclusive implementation
        //     makes such a loop run forever and reports as a hung build rather than as a failed assertion
        //     naming the predicate.
        assertThat(continued)
                .as("the cursor identifier is absent from its own continuation")
                .doesNotContain(cursor);
        assertThat(continued.getFirst())
                .as("the first row returned lies strictly beyond the cursor identifier")
                .isGreaterThan(cursor);
        assertThat(continued)
                .as("the continuation resumes at the very next identifier and stops at the cap")
                .containsExactly(ascending.get(2), ascending.get(3));

        // Assumptions: a second probe one position earlier pins WHICH row the predicate excludes, not
        //     merely that it excludes one. Moving the cursor back by one identifier must move the
        //     boundary by exactly one row; a case that probed a single position could be satisfied by an
        //     off-by-one predicate that excluded the wrong row consistently.
        assertThat(identifiersOf(this.ledger.findByTransactionIdGreaterThanOrderByTransactionIdAsc(
                        ascending.getFirst(), Limit.of(1))))
                .as("moving the cursor back one position moves the excluded row back one position")
                .containsExactly(ascending.get(1));
    }

    /**
     * Confirms a continuation from the master's final identifier delivers no row.
     *
     * <p>Target-side like the case above, with no baseline citation for the continuation itself. This is
     * the ordinary way a resumed walk ends rather than an error condition: an ordered scan discovers the
     * end by reaching it, so an empty result carries the same meaning here that an end-of-file status
     * carries in the reference's own sequential reads.</p>
     */
    @Test
    @DisplayName("a continuation from the final identifier delivers no row")
    void continuationFromTheFinalIdentifierDeliversNoRow() {
        List<String> ascending = this.walkedIdentifiersAfterSeeding(ROWS_FOR_CURSOR_PROBES);

        // Assumptions: the cap is deliberately larger than one so an empty result cannot be produced by
        //     the cap instead of by the predicate. A continuation asked for a single row would return
        //     nothing whether the boundary excluded everything beyond it or merely happened to be
        //     satisfied, and the two are different outcomes.
        // Assumptions: the result is reduced to identifiers before being asserted on, even though the
        //     assertion is only that it is empty. An assertion holding the rows themselves renders them
        //     in the diff it emits WHEN IT FAILS, which is the one moment a full record -- including the
        //     sixteen-character card number this table carries -- must not be printed. Reducing first
        //     means the failure output cannot contain one whatever the master happens to hold.
        assertThat(identifiersOf(this.ledger.findByTransactionIdGreaterThanOrderByTransactionIdAsc(
                        ascending.getLast(), Limit.of(ROWS_FOR_CURSOR_PROBES))))
                .as("no row remains beyond the greatest identifier the master holds")
                .isEmpty();
    }

    /**
     * Confirms no member this interface DECLARES names an offset-positioning type, in a parameter or in a
     * return.
     *
     * <p>Target-side, with no baseline citation: the reference positions its browses by key because a
     * keyed file has no other way to be positioned, so there is no counting-based read to have migrated.
     * The prohibition is the package charter's, applied package-wide, and the row cap the continuation
     * above accepts is a {@code Limit} for exactly this reason.</p>
     */
    @Test
    @DisplayName("no member this interface declares names an offset-positioning type")
    void noMemberThisInterfaceDeclaresNamesAnOffsetPositioningType() {
        // Assumptions: the assertion is scoped to DECLARED members, and the scope is stated rather than
        //     glossed because the honest claim is narrower than "this repository cannot be paged". The
        //     framework's own CRUD base contributes a counting-based read by inheritance, which no
        //     application file can remove; what this package controls is the vocabulary its OWN
        //     interfaces declare and the members its callers reach for. Asserting over inherited members
        //     would fail on the framework rather than report anything about this code.
        List<Method> declared = List.of(TransactionRepository.class.getDeclaredMethods());

        assertThat(declared)
                .as("the interface declares members, so this check is not vacuously satisfied")
                .isNotEmpty();

        // Alternatives Considered: offset positioning, in either shape the framework offers. Rejected
        //     because it does not preserve the behaviour it would replace: positioning by counting rows
        //     from the start of an ordered set means a concurrent insert ahead of the cursor pushes an
        //     unread row past the boundary, so the scan SKIPS it, and a concurrent delete behind the
        //     cursor pulls an already-read row back inside it, so the scan REPEATS it. A key already
        //     returned keeps its place in the ordering whatever is inserted or deleted around it. This
        //     module's correctness is judged by comparing committed output against a golden master, so a
        //     read discipline that can skip or repeat a row is not a near-equivalent of the reference.
        for (Method member : declared) {
            for (Class<?> parameterType : member.getParameterTypes()) {
                assertThat(parameterType.getSimpleName())
                        .as("the parameter types of %s carry no offset-positioning vocabulary",
                                member.getName())
                        .isNotIn(OFFSET_POSITIONING_TYPE_NAMES);
            }
            assertThat(member.getReturnType().getSimpleName())
                    .as("the return type of %s carries no offset-positioning vocabulary",
                            member.getName())
                    .isNotIn(OFFSET_POSITIONING_TYPE_NAMES);
        }
    }

    /**
     * Builds an unsaved row shaped as the posting program's write emits one.
     *
     * <p>Assumptions: every field value comes from
     * {@code tests/golden/posting/happy_path/tranfile.expected} rather than being composed here, so a
     * row this method returns is the shape the oracle actually produced. The baseline builds the record
     * at {@code app/cbl/CBTRN02C.cbl:425-439} by moving each field across from the daily-transaction
     * record before stamping the processing timestamp.</p>
     *
     * @param transactionId the sixteen-character identifier to key the row on, of type {@code String};
     *     must not be {@code null} and is the only value a caller varies, since every other field is
     *     fixed by the golden
     * @return an unsaved {@link Transaction} carrying the posting golden's field values under the given
     *     identifier, never {@code null}
     */
    private Transaction postingShapedRow(String transactionId) {
        Transaction row = new Transaction(transactionId);
        row.setTypeCd(TYPE_CD);
        row.setCategoryCd(POSTED_CATEGORY_CD);
        row.setSource(POSTED_SOURCE);
        row.setDescription("Purchase at Abshire-Lowe");
        row.setAmount(POSTED_AMOUNT);
        row.setMerchantId(POSTED_MERCHANT_ID);
        row.setMerchantName(BLANK_MERCHANT_TEXT);
        row.setMerchantCity(BLANK_MERCHANT_TEXT);
        row.setMerchantZip(BLANK_MERCHANT_ZIP);
        row.setCardNum(POSTED_CARD_NUM);
        row.setOrigTs(ORIGINATED_AT);
        row.setProcTs(PROCESSED_AT);
        return row;
    }

    /**
     * Builds an unsaved row shaped as the interest accrual program's write emits one.
     *
     * <p>Assumptions: the constants are those the accrual program moves at
     * {@code app/cbl/CBACT04C.cbl:482-495}, cross-checked against the first record of
     * {@code tests/golden/interest/happy_path/transact.expected}. The description is assembled from the
     * fixed prefix and the account identifier exactly as the concatenation at
     * {@code app/cbl/CBACT04C.cbl:485-489} assembles it, and no padding is appended: the interest path
     * leaves the remainder of that field as low values where the posting path space-fills it, and that
     * difference is the record mapper's concern rather than this tier's.</p>
     *
     * @param transactionId the sixteen-character identifier to key the row on, of type {@code String};
     *     must not be {@code null}
     * @return an unsaved {@link Transaction} carrying the generated-interest field constants under the
     *     given identifier, never {@code null}
     */
    private Transaction interestShapedRow(String transactionId) {
        Transaction row = new Transaction(transactionId);
        row.setTypeCd(TYPE_CD);
        row.setCategoryCd(GENERATED_CATEGORY_CD);
        row.setSource(GENERATED_SOURCE);
        row.setDescription(GENERATED_DESCRIPTION_PREFIX + GENERATED_ACCOUNT_ID);
        row.setAmount(GENERATED_AMOUNT);
        row.setMerchantId(GENERATED_MERCHANT_ID);
        row.setMerchantName(BLANK_MERCHANT_TEXT);
        row.setMerchantCity(BLANK_MERCHANT_TEXT);
        row.setMerchantZip(BLANK_MERCHANT_ZIP);
        row.setCardNum(GENERATED_CARD_NUM);
        row.setOrigTs(ORIGINATED_AT);
        row.setProcTs(PROCESSED_AT);
        return row;
    }

    /**
     * Builds an unsaved row carrying filler field values, for positions in an ordered walk.
     *
     * <p>Assumptions: the walk cases assert ordering and never field values, so one benign shape serves
     * every row they arrange. It is nonetheless a complete row rather than a sparsely populated one,
     * because {@code amount} and {@code proc_ts} are declared non-nullable on both the entity and the
     * harness table, so a partially populated row would fail on a constraint rather than on the ordering
     * the case is about.</p>
     *
     * @param transactionId the sixteen-character identifier to key the row on, of type {@code String};
     *     must not be {@code null}
     * @return an unsaved {@link Transaction} carrying filler values under the given identifier, never
     *     {@code null}
     */
    private Transaction fillerRow(String transactionId) {
        Transaction row = new Transaction(transactionId);
        row.setTypeCd(TYPE_CD);
        row.setCategoryCd(POSTED_CATEGORY_CD);
        row.setSource(POSTED_SOURCE);
        row.setDescription("ordered walk fixture");
        row.setAmount(FILLER_AMOUNT);
        row.setMerchantId(POSTED_MERCHANT_ID);
        row.setMerchantName(BLANK_MERCHANT_TEXT);
        row.setMerchantCity(BLANK_MERCHANT_TEXT);
        row.setMerchantZip(BLANK_MERCHANT_ZIP);
        row.setCardNum(FILLER_CARD_NUM);
        row.setOrigTs(ORIGINATED_AT);
        row.setProcTs(PROCESSED_AT);
        return row;
    }

    /**
     * Commits the requested number of rows under digit-only identifiers in DESCENDING order.
     *
     * <p>Assumptions: the identifiers are zero-padded to the column's full sixteen characters, so every
     * arranged identifier has the same width and a byte comparison of them is decided by digits alone.
     * Descending insertion is the point of the helper: it guarantees the sequence the walk returns is
     * the reverse of the sequence the engine received, so the ordering under test is the only thing that
     * could account for the difference.</p>
     *
     * @param rowCount how many rows to commit, of type {@code int}; must be positive, and callers pass a
     *     value above the walk's fetch window so more than one round trip is required
     * @return the identifiers in the DESCENDING order they were inserted in, so a case can assert that
     *     the insertion sequence and the walked sequence differ, never {@code null}
     */
    private List<String> seedDescendingIdentifiers(int rowCount) {
        List<String> inserted = new ArrayList<>(rowCount);
        this.transactionTemplate.executeWithoutResult(status -> {
            for (int ordinal = rowCount; ordinal >= 1; ordinal--) {
                String transactionId = String.format("%016d", ordinal);
                this.ledger.save(this.fillerRow(transactionId));
                inserted.add(transactionId);
            }
        });
        return inserted;
    }

    /**
     * Commits one row for each identifier of the mixed-shape fixture, using each producer's own shape.
     *
     * <p>Assumptions: the hyphenated identifiers are written as interest-shaped rows and the digit-only
     * ones as posting-shaped rows, matching the producer each identifier form actually comes from. The
     * ordering assertion would hold whatever shape carried them, but arranging them faithfully means the
     * fixture is a plausible master state rather than a set of keys with arbitrary bodies.</p>
     *
     * <p>Assumptions: insertion follows the fixture's declaration order, which is deliberately NOT the
     * byte order the walk must return -- the derived divergent identifier is declared last and sorts
     * last bytewise, while the two hyphenated identifiers are declared before it and sort before it, so
     * the digit-only posting identifier declared FIRST is the only one whose declared and sorted
     * positions coincide.</p>
     */
    private void seedMixedShapeIdentifiers() {
        this.transactionTemplate.executeWithoutResult(status -> {
            for (String transactionId : MIXED_SHAPE_IDENTIFIERS) {
                Transaction row = transactionId.contains("-")
                        ? this.interestShapedRow(transactionId)
                        : this.postingShapedRow(transactionId);
                this.ledger.save(row);
            }
        });
    }

    /**
     * Walks the whole master through the ordered finder and collects the identifiers it delivers.
     *
     * <p>Assumptions: the walk is consumed INSIDE a transaction and inside a try-with-resources block,
     * and both obligations belong to the caller by the member's own contract. The member declares
     * mandatory propagation, so a call holding no transaction is refused outright rather than handing
     * back a cursor that was already closed; and the returned stream holds a server-side cursor, so
     * leaving it unclosed leaks a database resource for as long as the pool keeps the connection.</p>
     *
     * @return the transaction identifiers in the order the finder delivered them, never {@code null} and
     *     empty when the master holds no row
     */
    private List<String> walkedIdentifiers() {
        return this.transactionTemplate.execute(status -> {
            try (Stream<Transaction> rows = this.ledger.findAllByOrderByTransactionIdAsc()) {
                return rows.map(Transaction::getTransactionId).toList();
            }
        });
    }

    /**
     * Seeds the master out of order and returns its identifiers in the ascending order the walk reports.
     *
     * <p>Assumptions: the cursor cases take their probe values from the ORDERED walk rather than from the
     * order they inserted in, and that is what keeps them independent of the seeding helper's direction.
     * A case that indexed into its own insertion list would be asserting against positions it had chosen,
     * so a change to the seeding direction would silently change which row each probe named. Taking the
     * positions from the walk means position two is whatever the engine says position two is.</p>
     *
     * @param rowCount how many rows to commit before walking, of type {@code int}; must be positive and
     *     large enough for the probes the caller intends to make
     * @return the committed identifiers in ascending order, never {@code null}
     */
    private List<String> walkedIdentifiersAfterSeeding(int rowCount) {
        this.seedDescendingIdentifiers(rowCount);
        return this.walkedIdentifiers();
    }

    /**
     * Extracts the transaction identifiers from a list of rows, preserving their order.
     *
     * <p>Assumptions: only identifiers are extracted, never whole rows, so nothing a bounded read
     * returns can reach a failure message as a full record. A row of this table carries a sixteen
     * character card number, and an assertion that compared lists of entities would render it in the
     * diff it emits on failure.</p>
     *
     * @param rows the rows to read identifiers from, of type {@code List<Transaction>}; must not be
     *     {@code null} and may be empty
     * @return the identifiers in the same order the rows arrived in, never {@code null}
     */
    private static List<String> identifiersOf(List<Transaction> rows) {
        return rows.stream().map(Transaction::getTransactionId).toList();
    }

    /**
     * Commits one row so that every later read observes it from outside the writing transaction.
     *
     * <p>Assumptions: the write is COMMITTED rather than left in a persistence-context buffer, and that
     * is what makes the reads in this class mean anything. A row still sitting in a flushable buffer can
     * satisfy a repository read without the engine having ordered, padded or scaled anything, so the
     * ordering and fixed-point assertions here would pass against values this class had supplied
     * itself.</p>
     *
     * @param row the unsaved entity to make durable, of type {@link Transaction}; must not be
     *     {@code null}
     */
    private void persist(Transaction row) {
        this.transactionTemplate.executeWithoutResult(status -> this.ledger.save(row));
    }

    /**
     * Reads one row back by key from outside the transaction that wrote it.
     *
     * <p>Assumptions: the read is a fresh keyed fetch rather than a reference to the instance handed to
     * {@link #persist(Transaction)}, so the values asserted are the engine's and not this class's. The
     * distinction is load-bearing for the blank-padded character columns: the width the engine returns
     * is a property of the column, and an in-memory instance would report whatever string was set on
     * it.</p>
     *
     * @param transactionId the identifier to fetch, of type {@code String}; must not be {@code null} and
     *     must name a row this case already committed
     * @return the stored {@link Transaction}, never {@code null}
     * @throws AssertionError if no row carries that identifier, which fails the case at the point the
     *     row was expected rather than later on a null dereference
     */
    private Transaction reload(String transactionId) {
        return this.ledger.findById(transactionId)
                .orElseThrow(() -> new AssertionError(
                        "no row was stored under the arranged identifier " + transactionId));
    }

    /**
     * Masks a card number to its last four characters for use in a diagnostic.
     *
     * <p>Assumptions: the masking contract is the migration plan's section 0.7.8, which masks a primary
     * account number to its last four digits outside the one administrative endpoint. This class holds
     * to it in assertion descriptions and comparison values alike, because a failure message is
     * precisely where an unmasked number would be read, logged and copied.</p>
     *
     * @param cardNum the card number to mask, of type {@code String}; may be {@code null} or shorter
     *     than the masked prefix, both of which are treated as nothing safe to reveal
     * @return the card number with its leading characters replaced by asterisks, or an all-asterisk
     *     value when there is nothing safe to reveal, never {@code null}
     */
    private static String maskedCard(String cardNum) {
        // Assumptions: a short or absent value returns all asterisks rather than the value itself. The
        //     guard exists because the alternative is the one failure mode masking must not have: a
        //     helper that fell back to returning its input would render an unmasked number precisely
        //     when the data was unexpected, which is when a diagnostic is most likely to be read.
        if (cardNum == null || cardNum.length() <= MASKED_PREFIX_LENGTH) {
            return "*".repeat(MASKED_PREFIX_LENGTH);
        }
        return "*".repeat(MASKED_PREFIX_LENGTH) + cardNum.substring(MASKED_PREFIX_LENGTH);
    }

    /**
     * The minimal application configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the module's job definitions, its queue client
     * and its object-store client stay out of the context and no emulator is needed to start it. Naming
     * the two persistence packages leaves the framework's own auto-configuration to build the pool from
     * the properties the container registered, and the cross-schema reach this table needs comes from the
     * test profile's connection initialisation rather than from a configuration class here.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class PostedMasterPersistenceTestApplication {
    }
}
