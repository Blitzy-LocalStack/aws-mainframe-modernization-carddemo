package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.batch.domain.CardXref;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
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
 * Holds the TWO keyed access paths over {@code account.card_xref} apart from one another, against a
 * real engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: one table answers two different questions in the migrated batch chain, and the whole of
 * this class exists to show that the two are not interchangeable. The by-card path takes a card number
 * and yields an account identifier. The by-account path takes an account identifier and yields a card
 * number. They read opposite directions over the same rows, they are reached through different keys,
 * and -- the point this class is built around -- <b>they do not have the same cardinality</b>, so the
 * result shape that is correct for one is wrong for the other.</p>
 *
 * <p>The reference separates the two paths at three independent levels, and each level was read
 * directly rather than inferred. At the dataset level, {@code app/jcl/XREFFILE.jcl:43} keys the base
 * cluster {@code KEYS(16 0)} -- sixteen bytes from byte zero -- while {@code app/jcl/XREFFILE.jcl:74}
 * keys the alternate index {@code KEYS(11,25)} and {@code app/jcl/XREFFILE.jcl:75} declares it
 * {@code NONUNIQUEKEY}. At the program level, {@code app/cbl/CBACT04C.cbl:37} declares the record key
 * and {@code app/cbl/CBACT04C.cbl:38} declares {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}
 * alongside it. At the driver level, {@code app/jcl/INTCALC.jcl:29-30} mounts the base cluster and
 * {@code app/jcl/INTCALC.jcl:31-32} mounts the alternate-index path as a SECOND data definition named
 * {@code XREFFIL1}, whereas {@code app/jcl/POSTTRAN.jcl:32-33} mounts the base cluster alone and the
 * posting job's driver contains no alternate-index data definition at all. Two keys, two data
 * definitions, one table.</p>
 *
 * <p>{@code app/cpy/CVACT03Y.cpy} corroborates both keys arithmetically, which is why they are treated
 * here as established fact rather than as a reading. Its line 5 places {@code XREF-CARD-NUM PIC X(16)}
 * at bytes 1 to 16, so that field begins at offset 0 and is 16 wide, matching
 * {@code KEYS(16 0)} exactly. Its line 6 places {@code XREF-CUST-ID PIC 9(09)} at bytes 17 to 25 and
 * its line 7 places {@code XREF-ACCT-ID PIC 9(11)} at bytes 26 to 36, so that field begins at offset
 * 25 and is 11 wide, matching {@code KEYS(11,25)} exactly. Line 8's {@code FILLER PIC X(14)} closes the
 * record at the 50 bytes line 2 declares. An independent derivation agreeing with a dataset
 * declaration on both keys is the strongest evidence available for a layout, and it is recorded because
 * a wrong offset here does not fail a build -- it returns the wrong account.</p>
 *
 * <h2>Why the two paths are separate cases and never one</h2>
 *
 * <p>Alternatives Considered: one case exercising both finders in sequence, which is shorter and reads
 * as a single statement about one table. Rejected on what a failure would then report. The two paths
 * differ in key, in direction and in permitted multiplicity, so a combined case has to arrange rows
 * that satisfy all three concerns at once, and when it fails it names the table rather than the path.
 * Worse, the multiplicity fixture the by-account path REQUIRES -- several cards under one account --
 * makes the by-card assertion read as though it depended on that multiplicity, which it does not. Every
 * case below therefore touches exactly ONE of the two finders, and no case calls both.</p>
 *
 * <p>Trade-offs: {@code Optional} is the CORRECT return shape for the by-card finder and the WRONG one
 * for a plain by-account finder, and the same type reads as an unremarkable choice in both places. The
 * by-card path is answered by a primary key -- {@code pk_card_xref} on {@code card_num} alone in the
 * harness that creates the table at test time -- so at most one row can match and an optional is safe.
 * The by-account path is answered by a NON-unique index, so many rows can match and an optional is safe
 * only once the query has been bounded to one row and given an order to pick that row by. The
 * production interface spells that difference in the method name it declares,
 * {@code findFirstByAccountIdOrderByCardNumAsc}, and this class is where the difference is demonstrated
 * rather than asserted in prose.</p>
 *
 * <h2>The multi-card fixture is CONSTRUCTED, and no vector available here supplies it</h2>
 *
 * <p>Assumptions: the one-account-to-many-cards case that {@code app/jcl/XREFFILE.jcl:75} explicitly
 * ADMITS occurs in none of the data this class can draw on, so this class builds it. That was measured
 * rather than assumed, across all three bodies of data available to it. The baseline seed
 * {@code app/data/ASCII/cardxref.txt} holds 50 rows, and grouping the account identifier at offset 25
 * for 11 bytes yields 50 distinct values with a count of exactly one for every one of them. The parity
 * vectors under {@code tests/fixtures/} contribute eighteen cross-reference files and not one of them
 * maps an account to two cards -- the largest hold five rows under five accounts, the three interest
 * scenarios hold two rows under two accounts, and every posting and preflight scenario holds a single
 * row. This module's OWN test classpath mirrors sixteen of those files under
 * {@code src/test/resources/fixtures/} and is one-to-one in every one of them. And
 * {@code tests/golden/} carries no cross-reference expectation at all, so <b>nothing pins the
 * determinism case below</b>, whatever data it were arranged from. The citation carried instead is the
 * declaration that admits the case.</p>
 *
 * <p>Refactoring Rationale: this section claimed there was "no golden master and no fixture for a
 * multi-card account anywhere in this repository", and that sweep is no longer true --
 * {@code services/reporting-service/src/test/resources/fixtures/xreffile.txt} holds 88 rows of which
 * 84 are the cards of one account, arranged for the card-ordered statement view that module owns. It
 * is not on this module's test classpath and it is not a by-account vector: a statement fixture pins
 * the rows a card walk emits, not which card a by-account read is required to pick. So the reason for
 * constructing the arrangement here is unchanged, and the claim is NARROWED to what is measured rather
 * than deleted -- a reader still has to know the arrangement is deliberate. What is dropped is the
 * repository-wide sweep, which asserted something about files this class never consults and which the
 * first multi-card fixture added anywhere else falsified.</p>
 *
 * <p>Assumptions: that absence is the entire reason this proof is worth writing, rather than a weakness
 * in it. A naive unordered, unbounded by-account finder passes against every row that ships and against
 * both parity fixtures, because each of them maps one account to one card; it fails the first time an
 * account holds two, which the declared contract permits at any moment. A test built only from shipped
 * data cannot distinguish the correct finder from the broken one, so the distinguishing data is
 * constructed here on purpose.</p>
 *
 * <p>Assumptions: determinism is asserted rather than mere single-valuedness, and the consequence of
 * getting that wrong is observable in the committed goldens. Interest accrual moves the card number
 * this path resolves onto every transaction it generates, and
 * {@code tests/golden/interest/happy_path/transact.expected} carries exactly the two card numbers its
 * cross-reference fixture supplies, at the transaction record's card-number field. A finder that
 * returned an arbitrary matching row would therefore emit a different card number on different runs
 * once an account held more than one card, and the golden comparison would report it as a parity
 * failure with no indication that the cause was an unordered query.</p>
 *
 * <h2>What this class does NOT assert</h2>
 *
 * <p>Assumptions: this table is READ-ONLY in this module, so it takes part in none of the posting unit
 * of work's writes. That is worth stating because a reader may reasonably assume every {@code account}
 * table is a write participant: {@code PostingUnitOfWorkIT} owns the atomicity proof and the
 * optimistic-lock ruling for the tables that ARE written, and no case here contributes to either. The
 * by-card lookup's own result feeds two of those writes without being one --
 * {@code app/cbl/CBTRN02C.cbl:394} moves the resolved account identifier into the account key, and
 * {@code app/cbl/CBTRN02C.cbl:469} moves the same value into the leading component of the
 * category-balance key -- and the proofs for both belong to the classes that own those tables.</p>
 *
 * <p>Assumptions: three rules that touch this lookup are owned elsewhere and are not restated. Reject
 * reason 100 and its literal, which {@code app/cbl/CBTRN02C.cbl:385-386} produces when this lookup
 * misses, belong to {@code PostingValidationServiceTest}; this class proves the lookup and says nothing
 * about the reject a miss produces. The interest job's control-break structure and its business-date
 * parameter belong to {@code CalculateInterestJobTest}. ALL interest arithmetic belongs to
 * {@code InterestCalculationServiceTest}, including the {@code HALF_UP} mode the divide reduces with, and no
 * arithmetic of any kind appears below.</p>
 *
 * <p>Assumptions: the secondary index this class reads through is neither created nor asserted here.
 * {@code idx_card_xref_account_id} is owned by account-service's own migration and is created at test
 * time by the harness script named below; {@code CrossSchemaFeedRepositoryIT} owns the assertion that
 * it exists and is non-unique. This class exercises the QUERY the index serves and authors no schema
 * object of any kind. The reference's explicit index build, {@code IDCAMS BLDINDEX} at
 * {@code app/jcl/XREFFILE.jcl:100-102}, is retired rather than translated, because PostgreSQL maintains
 * an index transactionally as rows change and a translated build step would accomplish nothing.</p>
 *
 * <p>Assumptions: no byte image, record length or fixed-width rendering is asserted below. The 50-byte
 * layout is cited above only to establish the two key offsets; this tier asserts ROWS, and the
 * byte-exact encoding of this record belongs to the mapper tests that produce it.</p>
 *
 * <h2>Diagnostics never carry a full card number</h2>
 *
 * <p>Assumptions: this table's primary key IS a sixteen-digit card number, and the migration plan masks
 * such a value to its last four digits everywhere it is disclosed. That creates a tension this class has
 * to resolve rather than ignore, because the determinism assertion below is ABOUT card-number ordering
 * and so must compare the values in full. It is resolved by comparing in full and reporting masked:
 * every ordering assertion evaluates a full-value comparison and then describes itself with masked
 * text, so a failure reports which suffix was expected against which suffix was found and never prints
 * a whole number. A collection assertion is deliberately avoided wherever card numbers are the subject,
 * because a failing collection assertion renders every element it holds.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// Refactoring Rationale: the Parameter Store config-data location is DISABLED for this context, for
//     the reason the sibling classes in this package record from their own side: application.yml
//     declares an `optional:aws-parameterstore:` location whose resolution builds an SSM client while
//     configuration is still loading, and on a build host with no region that client is built from an
//     unresolved placeholder and the context aborts before a single row is read. The flag stops the
//     LOAD rather than the resolution, which is why it is sufficient.
// Alternatives Considered: narrowing `spring.config.import` to the classpath document alone, which
//     reads as the more targeted fix. Rejected because an import list CONTRIBUTES locations rather
//     than replacing the non-profile document's, so the parameter-store location survives the
//     override and the context aborts unchanged. Supplying a real region instead was also rejected:
//     the loader would then issue a genuine lookup against a public endpoint, which is a network call
//     from a persistence assertion -- slow where it resolves and a timeout where it does not.
@SpringBootTest(
        classes = CardXrefRepositoryIT.CrossReferenceAccessPathTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class CardXrefRepositoryIT {

    /**
     * The engine image, named by manifest digest rather than by tag.
     *
     * <p>Assumptions: the digest is the one both sibling classes in this package pin, and the three
     * must agree. Each class starts its own container against the same {@code account} schema shape, so
     * two classes pinning two engines could disagree about that shape and the disagreement would
     * surface as whichever of them happened to run second.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness script that creates the table under test.
     *
     * <p>Assumptions: {@code account.card_xref} and the non-unique index that serves the by-account
     * path are both created by this script and by nothing else. The {@code account} schema belongs to
     * another service, whose migration is unreachable from this module's test classpath because this
     * module may depend on the shared kernel and on no sibling service, so the shape is supplied here
     * instead. The path has no compiler to check it, which is why it is named once as a constant rather
     * than inline.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The account identifier the constructed multi-card row set is arranged under.
     *
     * <p>Assumptions: eleven digits, matching {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:7}, and deliberately outside the range the shipped seed occupies so
     * that a case here cannot be satisfied by a row it did not arrange.</p>
     */
    private static final long MULTI_CARD_ACCOUNT_ID = 10000000101L;

    /**
     * The account identifier used by the cases that need exactly one card.
     *
     * <p>Assumptions: distinct from the multi-card account above, so the by-card cases arrange a row
     * set whose shape says nothing about multiplicity and cannot accidentally depend on it.</p>
     */
    private static final long SINGLE_CARD_ACCOUNT_ID = 10000000105L;

    /**
     * An account identifier no case arranges a row for.
     *
     * <p>Assumptions: absence has to be arranged as deliberately as presence. This value is never
     * persisted by any case below, so the miss it produces is a property of the query rather than of
     * the order the cases happened to run in.</p>
     */
    private static final long ABSENT_ACCOUNT_ID = 10000000999L;

    /** The customer identifier every arranged row carries, of nine digits per {@code XREF-CUST-ID}. */
    private static final long CUSTOMER_ID = 900000101L;

    /**
     * The lowest card number of the constructed multi-card account, and the expected result.
     *
     * <p>Assumptions: sixteen characters, because the column is declared {@code CHAR(16)} and the
     * leading zeros are part of the value rather than formatting. Comparing these four constants as
     * strings is what makes the ordering assertion below a statement about the query's {@code ORDER BY}
     * rather than about numeric magnitude.</p>
     */
    private static final String CARD_LOWEST = "0000000000000101";

    /** The second-lowest card number of the constructed multi-card account. */
    private static final String CARD_SECOND = "0000000000000102";

    /** The third-lowest card number of the constructed multi-card account. */
    private static final String CARD_THIRD = "0000000000000103";

    /** The highest card number of the constructed multi-card account. */
    private static final String CARD_HIGHEST = "0000000000000104";

    /** The only card number of the single-card account the by-card cases read. */
    private static final String CARD_SINGLE = "0000000000000105";

    /**
     * A card number no case arranges a row for.
     *
     * <p>Assumptions: never persisted below, for the same reason the absent account identifier is
     * never persisted -- a miss must be a property of the query and not of test ordering.</p>
     */
    private static final String ABSENT_CARD_NUM = "0000000000000999";

    /** Digits of a card number a diagnostic may disclose, per the migration plan's masking rule. */
    private static final int DISCLOSED_SUFFIX_WIDTH = 4;

    /**
     * The container every case in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} rather than the legacy
     * container package, and the replacement type is not generic, so the declaration carries no type
     * argument.</p>
     */
    // Alternatives Considered: a shared abstract base class holding this container and the property
    //     registration once for the whole package, which would remove the duplication between this
    //     class and its two siblings. Rejected, and the package charter records the same ruling: a
    //     container held in a base class is shared mutable state, so rows one class arranges are rows
    //     another reads, and a failure then names whichever class ran second rather than the one whose
    //     arrangement was wrong. This class empties the one table it uses and owns its own container,
    //     which is what lets it be run alone and still mean something. Trade-offs: the accepted cost is
    //     a third container start and a third copy of this declaration, paid whenever it changes.
    // Alternatives Considered: an in-memory engine, which would start in milliseconds rather than the
    //     seconds a container costs. Rejected because the property this class exists for is an ENGINE
    //     behaviour: an ordered, bounded read over a non-unique secondary index, and the incorrect
    //     result size an unbounded read produces over the same rows. A substitute implements index
    //     selection and single-result enforcement differently or not at all, so a passing assertion
    //     would say nothing about the engine the nightly chain runs against. No embedded driver is on
    //     this module's classpath, so the substitute is not reachable even by accident.
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The table under test, injected as the production repository interface. */
    @Autowired
    private CardXrefRepository crossReferences;

    /** The persistence context, used to arrange rows and to clear so every read is a real select. */
    @Autowired
    private EntityManager entityManager;

    /**
     * The transaction boundary every arranged write is issued inside.
     *
     * <p>Assumptions: a boundary is REQUIRED rather than tidy, because a flush outside one is refused
     * with a transaction-required failure when no transaction is bound to the thread. Each arrangement
     * commits on its own, so the read that follows is a genuine select against a committed table rather
     * than a read of an open transaction's own buffer.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used to empty the table and to issue the unbounded demonstration read. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry this method adds the container's JDBC URL,
     *     user name and credential to as deferred suppliers; must not be {@code null}
     */
    // Assumptions: no connection literal is written anywhere in this class, and the container's port is
    //     the reason. A container assigns its host port as it starts, so a literal authored beforehand
    //     would either address nothing or -- the worse outcome, because it passes -- address whatever
    //     database happened to be listening on that port.
    // Assumptions: the two migration credentials are registered as well and are not redundant with the
    //     datasource pair. The base configuration document binds them to placeholders carrying no
    //     fallback, and the framework reads those keys only when no connection-details bean supplies
    //     them instead, which is this module's case because the artifact contributing such a bean is
    //     not declared in its POM. Leaving them unregistered aborts the context on an unresolved
    //     placeholder before any migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the cross-reference table and opens a JDBC handle before each case.
     *
     * <p>Assumptions: exactly ONE table is emptied, and the narrowness is deliberate. Every case below
     * arranges its own cross-reference rows and reads nothing else, so emptying anything further would
     * couple this class to tables it does not use -- and emptying the seeded reference rows the harness
     * writes once at container start would leave later cases in other classes unable to find them.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates, used to open the
     *     JDBC handle this class issues its catalog-free statements through; must not be {@code null}
     */
    @BeforeEach
    void emptyCrossReference(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(status ->
                this.jdbc.update("DELETE FROM account.card_xref"));
    }

    /**
     * Confirms the by-card path resolves a present card number to its one row.
     *
     * <p>This pins the base cluster's own key. {@code app/jcl/XREFFILE.jcl:43} declares it
     * {@code KEYS(16 0)}, sixteen bytes from byte zero, which {@code app/cpy/CVACT03Y.cpy:5} places at
     * the head of the record as {@code XREF-CARD-NUM PIC X(16)}. It is the lookup posting performs on
     * every feed record, at {@code app/cbl/CBTRN02C.cbl:382} moving the feed's card number into the key
     * and {@code app/cbl/CBTRN02C.cbl:383} reading with it, and the lookup the pre-posting pass performs
     * at {@code app/cbl/CBTRN01C.cbl:229-230}.</p>
     *
     * <p>Assumptions: an optional is the correct shape HERE and its correctness is a property of the
     * schema rather than of this fixture. The table's only uniqueness constraint is its primary key on
     * the card number alone, so at most one row can match and the single-result contract cannot be
     * violated by any data. The contrast with the by-account cases further down is the reason this case
     * states the ground for the shape instead of merely using it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the by-card read resolves a present card number over the base cluster key")
    void theKeyedReadResolvesThePresentCardNumber() {
        persistCrossReference(CARD_SINGLE, CUSTOMER_ID, SINGLE_CARD_ACCOUNT_ID);

        Optional<CardXref> resolved = this.crossReferences.findByCardNum(CARD_SINGLE);

        assertThat(resolved)
                .as("the card number keyed at XREFFILE.jcl:43 must resolve its own row")
                .isPresent();
        // Assumptions: the comparison is made in full and REPORTED masked, because this table's key is
        //     a sixteen-digit card number and the migration plan discloses only its last four digits.
        //     Asserting on the string directly would satisfy the comparison and then render the whole
        //     value into the build log on failure, which is the disclosure the plan forbids.
        assertThat(CARD_SINGLE.equals(resolved.orElseThrow().getCardNum()))
                .as("the fixed-width key must compare equal on the way out, leading zeros intact,"
                        + " for the card ending %s", maskCardNumber(CARD_SINGLE))
                .isTrue();
    }

    /**
     * Confirms the by-card path reports a miss for an absent card number instead of raising.
     *
     * <p>Both consumers of this path treat a miss as a business outcome and neither treats it as a
     * failure, which is why the repository reports absence neutrally. Posting turns it into a reject
     * reason and continues with the next feed record; the pre-posting pass records a soft status and
     * carries on, at {@code app/cbl/CBTRN01C.cbl:232-233}. Raising here would convert both of those
     * outcomes into a failed step.</p>
     *
     * <p>Assumptions: the reject a miss produces is NOT asserted here.
     * {@code app/cbl/CBTRN02C.cbl:385-386} sets reason 100 and its literal, and that outcome belongs to
     * {@code PostingValidationServiceTest}. This case proves only that the lookup reports the miss the
     * validation rule is built on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the by-card read misses an absent card number rather than raising")
    void theKeyedReadMissesAnAbsentCardNumber() {
        persistCrossReference(CARD_SINGLE, CUSTOMER_ID, SINGLE_CARD_ACCOUNT_ID);

        // Assumptions: one row IS arranged, so the miss is a decision the query made about a key it did
        //     not find rather than the trivial outcome of reading an empty table. An empty-table miss
        //     would pass for a query that was broken in every other respect as well.
        Optional<CardXref> resolved = this.crossReferences.findByCardNum(ABSENT_CARD_NUM);

        assertThat(resolved)
                .as("a card number with no cross-reference row must miss, and the miss must not raise")
                .isEmpty();
    }

    /**
     * Confirms the by-card path yields the account identifier the chain goes on to key by.
     *
     * <p>This is the direction of the by-card path and the reason posting reads it at all. The feed
     * record names only a card, so {@code app/cbl/CBTRN02C.cbl:394} moves the account identifier THIS
     * lookup returned into the account key before reading the account master, and
     * {@code app/cbl/CBTRN02C.cbl:469} moves the same value into the leading component of the
     * category-balance key.</p>
     *
     * <p>Assumptions: the two reads that consume this value are proved elsewhere and are only
     * cross-referenced here. The account master read and the writes it participates in belong to
     * {@code PostingUnitOfWorkIT}; the category balance's composite key belongs to the class that owns
     * that table. This case proves that the value the chain keys by is the value this row carries, which
     * is the one link neither of those classes can establish from its own side.</p>
     *
     * <p>Assumptions: {@code app/jcl/POSTTRAN.jcl:32-33} supplies posting with the base cluster and
     * nothing else -- its driver holds no alternate-index data definition at all -- so posting reaches
     * the account identifier only by resolving a card number first. It cannot ask this table the reverse
     * question, which is why the by-account path is a separate proof rather than a variation of this
     * one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the by-card read yields the account identifier posting then keys the master by")
    void theKeyedReadYieldsTheAccountKeyPostingReadsBy() {
        persistCrossReference(CARD_SINGLE, CUSTOMER_ID, SINGLE_CARD_ACCOUNT_ID);

        CardXref resolved = this.crossReferences.findByCardNum(CARD_SINGLE).orElseThrow();

        assertThat(resolved.getAccountId())
                .as("the account identifier moved into the account key at CBTRN02C.cbl:394 must be the"
                        + " one this row carries")
                .isEqualTo(SINGLE_CARD_ACCOUNT_ID);
        assertThat(resolved.getCustomerId())
                .as("the customer identifier at CVACT03Y.cpy:6 must survive the round trip")
                .isEqualTo(CUSTOMER_ID);
    }

    /**
     * Confirms the by-account path returns the LOWEST card number of an account holding several.
     *
     * <p>This is the highest-value assertion in this class. The alternate index this path reads through
     * is declared over eleven bytes from byte 25 at {@code app/jcl/XREFFILE.jcl:74} and declared
     * {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:75}, with the path object that presents it to
     * a program defined at {@code app/jcl/XREFFILE.jcl:90-92}. Interest accrual is its consumer:
     * {@code app/cbl/CBACT04C.cbl:38} declares the alternate record key and
     * {@code app/cbl/CBACT04C.cbl:394-395} reads explicitly over it. Its driver mounts both paths as
     * separate data definitions, the base cluster at {@code app/jcl/INTCALC.jcl:29-30} and the
     * alternate-index path at {@code app/jcl/INTCALC.jcl:31-32}.</p>
     *
     * <p>Assumptions: the four-row arrangement is CONSTRUCTED and nothing available to this class
     * supplies it. The shipped seed is strictly one card per account -- 50 rows and 50 distinct account
     * identifiers in {@code app/data/ASCII/cardxref.txt} -- every cross-reference vector under
     * {@code tests/fixtures/} is one-to-one, including the sixteen this module mirrors onto its own test
     * classpath, and {@code tests/golden/} holds no cross-reference expectation to pin this case with at
     * all. So the multiplicity that {@code app/jcl/XREFFILE.jcl:75} admits is arranged here rather than
     * loaded. A reader should not go looking for the vector behind this case; the declaration that
     * permits the case IS the citation. The class-level section above records the one multi-card
     * arrangement that does exist elsewhere in the repository, and why it cannot serve here.</p>
     *
     * <p>Alternatives Considered: arranging the rows in card-number order, which is the arrangement a
     * fixture builder reaches for first. Rejected because it cannot distinguish the query's ordering
     * from its insertion sequence -- a finder returning whichever row was written first would pass. The
     * insertion order below is deliberately THIRD, HIGHEST, LOWEST, SECOND, so the expected row is
     * neither the first nor the last written and is not the row a descending order would pick. A finder
     * returning the first-inserted row yields the third card, one returning the last-inserted row yields
     * the second, and one ordering the other way yields the highest; only a genuine ascending order by
     * card number yields the expected value, so the assertion is about the {@code ORDER BY} and nothing
     * else.</p>
     *
     * <p>Trade-offs: this case asserts the RESULT of the by-account query and deliberately does not
     * assert the plan the engine chose to produce it. An execution-plan check was available and is
     * declined because it would be brittle for a reason that has nothing to do with correctness: a
     * planner costs a sequential scan against an index scan from row-count statistics, so on the handful
     * of rows a test arranges it may legitimately decline the index it would certainly use at production
     * volume, and the case would then fail while the query remained right. The accepted cost is that a
     * dropped index would not be reported HERE -- it is reported instead by the catalog case in
     * {@code CrossSchemaFeedRepositoryIT}, which asserts the index exists and is not unique without
     * depending on a planner decision. Result here, existence there, and no assertion resting on
     * statistics in either place.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the by-account read returns the lowest card of a multi-card account")
    void theByAccountReadReturnsTheLowestCardOfAMultiCardAccount() {
        persistCardsForOneAccount(MULTI_CARD_ACCOUNT_ID, CARD_THIRD, CARD_HIGHEST, CARD_LOWEST,
                CARD_SECOND);

        Optional<CardXref> resolved =
                this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(MULTI_CARD_ACCOUNT_ID);

        assertThat(resolved)
                .as("an account holding four cards must still resolve one row over the non-unique"
                        + " index declared at XREFFILE.jcl:75")
                .isPresent();
        // Assumptions: the full sixteen-digit values are compared and only their four-digit suffixes are
        //     reported, which is how this case honours the masking rule while still asserting an
        //     ordering that is a property of the whole value. A direct string comparison would render
        //     both card numbers in full on failure.
        assertThat(CARD_LOWEST.equals(resolved.orElseThrow().getCardNum()))
                .as("the ordered read must return the card ending %s, the lowest of the four arranged,"
                        + " and not the card ending %s that was written first",
                        maskCardNumber(CARD_LOWEST), maskCardNumber(CARD_THIRD))
                .isTrue();
    }

    /**
     * Confirms the by-account path returns the SAME row on every invocation over unchanged rows.
     *
     * <p>Assumptions: repeatability is asserted separately from ordering because they are different
     * failures with the same symptom. An unordered read over a multi-row match may return a stable row
     * for as long as the engine happens to plan it the same way, so a single invocation can agree with
     * an ordered read by coincidence; only a second invocation asked to produce the same answer
     * distinguishes a determined result from a lucky one.</p>
     *
     * <p>Assumptions: the consequence of an unstable pick is observable in a committed golden, which is
     * what makes this case worth its own arrangement.
     * {@code tests/golden/interest/happy_path/transact.expected} carries the card numbers its
     * cross-reference fixture supplies, because accrual writes the card number this path resolves onto
     * each transaction it generates. Were the pick unstable, the generated card number would differ
     * between runs once an account held more than one card, and the golden comparison would report a
     * parity failure whose cause -- an unordered query -- it could not name.</p>
     *
     * <p>Assumptions: no golden pins THIS case, for the reason recorded on the ordering case above: the
     * multiplicity it needs occurs in no shipped data, so the golden can only demonstrate why
     * determinism matters and cannot serve as the vector for it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the by-account read returns the same row on a repeated invocation")
    void theByAccountReadReturnsTheSameRowOnEveryInvocation() {
        persistCardsForOneAccount(MULTI_CARD_ACCOUNT_ID, CARD_THIRD, CARD_HIGHEST, CARD_LOWEST,
                CARD_SECOND);

        String firstRead = this.crossReferences
                .findFirstByAccountIdOrderByCardNumAsc(MULTI_CARD_ACCOUNT_ID)
                .orElseThrow()
                .getCardNum();
        // Assumptions: the context is cleared between the two reads so the second is a genuine select
        //     rather than a return of the first read's managed instance. Without the clear both reads
        //     resolve from the same persistence context and the case would prove only that a cache is
        //     consistent with itself.
        this.entityManager.clear();
        String secondRead = this.crossReferences
                .findFirstByAccountIdOrderByCardNumAsc(MULTI_CARD_ACCOUNT_ID)
                .orElseThrow()
                .getCardNum();

        assertThat(firstRead.equals(secondRead))
                .as("two reads over unchanged rows must agree; the first returned the card ending %s"
                        + " and the second the card ending %s",
                        maskCardNumber(firstRead), maskCardNumber(secondRead))
                .isTrue();
        assertThat(CARD_LOWEST.equals(secondRead))
                .as("the repeated read must still be the lowest card, ending %s, rather than merely"
                        + " agreeing with the first read on some other row",
                        maskCardNumber(CARD_LOWEST))
                .isTrue();
    }

    /**
     * Confirms the by-account path reports a miss for an account holding no card.
     *
     * <p>Assumptions: absence is reported rather than raised on this path for the same reason as on the
     * by-card path -- what a miss MEANS belongs to the caller. Accrual reads this path once per account
     * at its control break and {@code app/cbl/CBACT04C.cbl:396-397} merely reports an unfound account
     * rather than abandoning the run, so a repository that raised here would turn a diagnostic into a
     * failed step.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the by-account read misses an account holding no card rather than raising")
    void theByAccountReadMissesAnAccountHoldingNoCard() {
        // Assumptions: the multi-card rows ARE arranged, under a different account, so the miss is the
        //     query declining to match a populated table rather than the empty-table outcome that would
        //     pass for a query broken in every other respect.
        persistCardsForOneAccount(MULTI_CARD_ACCOUNT_ID, CARD_THIRD, CARD_HIGHEST, CARD_LOWEST,
                CARD_SECOND);

        Optional<CardXref> resolved =
                this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ABSENT_ACCOUNT_ID);

        assertThat(resolved)
                .as("an account with no cross-referenced card must miss, and the miss must not raise")
                .isEmpty();
    }

    /**
     * Confirms the bounded finder succeeds over the same rows on which an unbounded single-result read
     * fails.
     *
     * <p>Assumptions: exercising the correct finder alone would not document why the alternative is
     * wrong. Both shapes compile, both read the same index, and both pass against every row that ships,
     * so a case that only called the right one would leave the choice looking stylistic. This case
     * therefore issues BOTH reads over one arrangement and shows that they disagree.</p>
     *
     * <p>Alternatives Considered: the naive by-account finder,
     * {@code Optional<CardXref> findByAccountId(Long)} -- unordered and unbounded -- is the shape this
     * class exists to rule out. It is not declared on the production interface, so it cannot be called
     * from here, and the demonstration issues the equivalent unbounded single-result read directly
     * instead. Its failure is an incorrect-result-size data-access exception, raised the moment more
     * than one row matches, which the declared {@code NONUNIQUEKEY} at
     * {@code app/jcl/XREFFILE.jcl:75} permits at any time. The bounded, ordered finder cannot raise it
     * however many cards the account holds, and that immunity is the whole reason the production
     * interface spells its name the long way.</p>
     *
     * <p>Alternatives Considered: adding the naive finder to the production interface so it could be
     * called and its failure asserted through the same proxy. Rejected outright -- a test does not widen
     * a production surface to prove that the widening would be wrong, and the added method would then be
     * callable by real code, which is precisely the defect being ruled out.</p>
     *
     * <p>Assumptions: the demonstration read selects the ACCOUNT identifier and never the card number,
     * so no card number can reach the failure message the engine and the framework compose. The exception
     * this case asserts on reports how many rows were found rather than what they held, which keeps the
     * negative assertion free of the disclosure the masking rule forbids.</p>
     *
     * <p>The unbounded read is expected to raise
     * {@link org.springframework.dao.IncorrectResultSizeDataAccessException}, and that expectation is
     * the assertion rather than an escaping failure, so this method declares no thrown type of its
     * own.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the bounded finder succeeds where an unbounded single-result read raises")
    void theUnboundedSingleResultAlternativeFailsWhereTheBoundedFinderSucceeds() {
        persistCardsForOneAccount(MULTI_CARD_ACCOUNT_ID, CARD_THIRD, CARD_HIGHEST, CARD_LOWEST,
                CARD_SECOND);

        assertThat(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(MULTI_CARD_ACCOUNT_ID))
                .as("the bounded, ordered finder must resolve one row from a four-row match")
                .isPresent();

        // Alternatives Considered: the failure is captured and then asserted on, rather than asserted
        //     inside a chained expectation. Capturing first is what lets the description below be
        //     attached to the type assertion itself, so a run in which the unbounded read wrongly
        //     SUCCEEDED reports that specific outcome instead of a bare missing-exception message.
        Throwable unbounded = catchThrowable(() -> this.jdbc.queryForObject(
                "SELECT account_id FROM account.card_xref WHERE account_id = ?",
                Long.class,
                MULTI_CARD_ACCOUNT_ID));

        assertThat(unbounded)
                .as("an unbounded single-result read over the same non-unique key must report an"
                        + " incorrect result size, which is the failure the bounded finder avoids")
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
        assertThat(((IncorrectResultSizeDataAccessException) unbounded).getActualSize())
                .as("the reported size must be the four rows arranged, which is what makes the failure"
                        + " a consequence of the multiplicity the index admits")
                .isEqualTo(4);
    }

    /**
     * Confirms the repository surface declares no mutator, guarding a property the compiler enforces.
     *
     * <p>Assumptions: nothing in this module writes this table, and two independent contracts establish
     * it. In the reference, all three consuming programs read it and none writes or rewrites it --
     * {@code app/cbl/CBTRN02C.cbl:383}, {@code app/cbl/CBTRN01C.cbl:229} and
     * {@code app/cbl/CBACT04C.cbl:394}. In the target the restriction is a privilege rather than a
     * convention: this module's role is granted {@code SELECT} on the {@code account} schema's tables
     * and {@code UPDATE} on the account master BY NAME, so no write privilege on the cross-reference
     * reaches this module at all. Consistently, the mapped type carries no version column, because
     * there is no update path for one to guard.</p>
     *
     * <p>Alternatives Considered: proving the read-only contract by calling a mutator and asserting the
     * database refuses it, which is the shape a privilege test would take. Rejected on two grounds, one
     * of them fatal. It could not be written at all here -- the harness creates no role and issues no
     * grant, so these cases connect as the container's superuser and every table is writable at test
     * time, meaning the call would SUCCEED and the assertion would be inverted. Even where it could be
     * written it would be the weaker statement: the interface extends the narrow repository base rather
     * than a CRUD base, so a mutator is not inherited and a call to one is a COMPILATION error. A method
     * that does not exist cannot be called by any caller, in any environment, whatever the grant.</p>
     *
     * <p>Trade-offs: this case is a regression guard rather than the guarantee itself. The guarantee is
     * the compiler's, and it is already in force; what a reflective check adds is notice if the declared
     * surface later grows a mutator or changes its base type, which would relax the guarantee silently
     * because nothing else in the build would fail. Asserting the declared set exactly, rather than only
     * the absence of known mutator names, is what makes an addition visible whatever it is called.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the repository declares exactly three reads and no mutator")
    void theRepositorySurfaceDeclaresNoMutator() {
        List<String> declared = Arrays.stream(CardXrefRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(declared)
                .as("the declared surface is the whole reachable surface, because the narrow repository"
                        + " base contributes no member of its own")
                .containsExactlyInAnyOrder(
                        "findByCardNum",
                        "findFirstByAccountIdOrderByCardNumAsc",
                        "findAllByOrderByCardNumAsc");

        assertThat(Arrays.stream(CardXrefRepository.class.getMethods()).map(Method::getName).toList())
                .as("no mutator may be reachable, whether declared here or inherited from a base type")
                .doesNotContain("save", "saveAll", "saveAndFlush", "delete", "deleteAll", "deleteById",
                        "deleteAllInBatch", "flush");
    }

    /**
     * Arranges one cross-reference row and commits it.
     *
     * <p>Trade-offs: this helper WRITES a table whose repository exposes no write method, and the two
     * facts are not in conflict. The read-only contract is a property of the repository interface, which
     * declares three reads and nothing else; the arrangement reaches the table through the persistence
     * context directly, which is the only writer available to a case that has to read a row it put
     * there. The alternative -- a fixture loaded outside the test -- was declined because the
     * multiplicity these cases turn on appears in no fixture in this repository, so the row set has to be
     * built where the assertion that needs it can be read beside it.</p>
     *
     * @param cardNum the sixteen-character card number that keys the row, of type {@code String};
     *     must not be {@code null}
     * @param customerId the nine-digit customer identifier the row carries, of type {@code long}
     * @param accountId the eleven-digit account identifier the row carries and that the by-account
     *     path resolves through, of type {@code long}
     */
    private void persistCrossReference(String cardNum, long customerId, long accountId) {
        // Assumptions: each arrangement commits on its own so the read that follows is a real select
        //     against a committed table. A read inside the writing transaction would resolve from that
        //     transaction's own buffer, where an ordering the database would apply need not have been
        //     applied at all -- which would make an ordered read appear correct without the engine
        //     having ordered anything.
        this.transactionTemplate.executeWithoutResult(status -> {
            this.entityManager.persist(new CardXref(cardNum, customerId, accountId));
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Arranges several cards under one account, written in the order given rather than in key order.
     *
     * <p>Assumptions: the caller supplies the insertion order deliberately and this helper preserves it
     * exactly, writing each row in a separate committed transaction. Sorting the values here -- or
     * writing them as one batch the provider is free to reorder -- would destroy the property every
     * by-account case depends on, that the physical write order differs from the card-number order.</p>
     *
     * @param accountId the eleven-digit account identifier every arranged row is written under, of type
     *     {@code long}, so that the rows form the one-to-many match the non-unique index admits
     * @param cardNums the card numbers to arrange, of type {@code String...}, written in exactly the
     *     order given; must not be {@code null} and each element must not be {@code null}
     */
    private void persistCardsForOneAccount(long accountId, String... cardNums) {
        for (String cardNum : cardNums) {
            persistCrossReference(cardNum, CUSTOMER_ID, accountId);
        }
    }

    /**
     * Renders the disclosable suffix of a card number for use in an assertion description.
     *
     * <p>Assumptions: four digits is the width the migration plan permits a primary account number to be
     * disclosed at, and every description in this class that names a card is built through this method
     * for that reason. The plan's masking rule is written for an API response, and it is applied here as
     * well because a failing build log is a retained, searchable, widely readable channel: a case that
     * rendered whole card numbers on failure would publish them exactly where the rule is hardest to
     * notice being broken.</p>
     *
     * @param cardNum the card number to render a suffix of, of type {@code String}; must not be
     *     {@code null} and is never rendered in full by this method
     * @return the last four characters of the supplied value, prefixed by an ellipsis so the result
     *     cannot be mistaken for a complete card number; never {@code null}
     */
    private String maskCardNumber(String cardNum) {
        return "..." + cardNum.substring(cardNum.length() - DISCLOSED_SUFFIX_WIDTH);
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: a configuration local to this class is what keeps the batch job registry, the
     * messaging clients and the object-store client this module's own application class would register
     * out of the context. Those collaborators need a queue, a bucket or a job launcher, none of which a
     * cross-reference read has any use for, and each is one more reason a context could fail to start
     * for a cause unrelated to the table under test.</p>
     *
     * <p>Assumptions: the entity scan and the repository scan name the module's packages rather than
     * this test's, because the types under test are the production mapping and the production interface.
     * A scan narrowed to the cross-reference alone was available and is declined: the module's mapped
     * types are validated against the deployed shape as a set, so scanning the whole package is what
     * makes a mismatch anywhere in it a start-up failure this class reports rather than a defect it
     * happens to miss.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class CrossReferenceAccessPathTestApplication {
    }
}
