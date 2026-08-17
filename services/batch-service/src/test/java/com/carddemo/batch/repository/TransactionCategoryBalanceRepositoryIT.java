package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.common.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds the two access disciplines {@link TransactionCategoryBalanceRepository} declares against a real
 * PostgreSQL engine: the ordered walk interest accrual drives, and the keyed read posting branches on.
 *
 * <h2>Purpose</h2>
 *
 * <p>One cluster in the reference is reached two structurally different ways, and this class proves both
 * against an engine rather than against a substitute. Posting selects it
 * {@code ACCESS MODE IS RANDOM} and keys in one row at a time; accrual selects the SAME cluster
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} at
 * {@code app/cbl/CBACT04C.cbl:29-30} and walks it end to end. Neither property is arithmetic, so
 * neither is provable where the store is stubbed: an ORDER BY can be modelled by a mock but only an
 * engine EVALUATES one, and an absent row is a state of a table rather than a value a stub returns.</p>
 *
 * <h2>The two obligations this class owns</h2>
 *
 * <ul>
 *   <li><b>The ascending walk in reference key order.</b> The key is the first seventeen bytes of the
 *       record, and that is arithmetic rather than inference: {@code app/jcl/TCATBALF.jcl:40} defines
 *       the cluster {@code KEYS(17 0)} while {@code app/cpy/CVTRA01Y.cpy:6-8} declares the leading
 *       fields as an eleven-digit account identifier, a two-character type code and a four-digit
 *       category code, and 11 plus 2 plus 4 is exactly 17 in that sequence. Two independent files, one
 *       answer, and it fixes the order as account, then type, then category.</li>
 *   <li><b>The keyed read and the two additive arms.</b> {@code app/cbl/CBTRN02C.cbl:467} opens the
 *       paragraph, {@code :481} accepts a not-found status alongside success, {@code :495} branches on
 *       the result, and the arms at {@code :503-524} and {@code :526-542} both ADD the posted amount --
 *       differing only in an initialisation and in write against rewrite.</li>
 * </ul>
 *
 * <h2>What this class does NOT assert, and who does</h2>
 *
 * <p>{@code PostingUnitOfWorkIT} beside this file is the SOLE owner of the three-write atomicity proof
 * -- commit-all-or-none and rollback-all-or-none across {@code ledger} and {@code account} in one
 * transaction, in the baseline order {@code app/cbl/CBTRN02C.cbl:440-442} establishes, with no saga, no
 * two-phase commit and no compensating reversal. The category-balance write is the FIRST of those three,
 * so this class is a natural place to reach for a second atomicity test; it deliberately carries none.</p>
 *
 * <p>Trade-offs: citing that owner rather than re-proving atomicity here accepts that a reader meets the
 * category-balance table in two files. The alternative -- a rollback case here as well -- would put one
 * property under two owners, which is how a proof drifts: one side is updated, the other still passes,
 * and nothing can report that the two now describe different behaviour.</p>
 *
 * <p>Trade-offs: <b>the balance-accumulation obligation is split BY TABLE, and this class owns one half
 * of it.</b> This class owns the CATEGORY-BALANCE half -- the unconditional
 * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} that both arms perform at {@code app/cbl/CBTRN02C.cbl:508}
 * and {@code :527}. {@code PostingUnitOfWorkIT} owns the ACCOUNT CYCLE-BUCKET half -- the inclusive
 * {@code IF DALYTRAN-AMT >= 0} at {@code app/cbl/CBTRN02C.cbl:548} that files a zero amount under
 * credit, and the else arm that adds an already-negative amount as is rather than negating it. Those are
 * columns of {@code account.accounts} and not of this table, so no case here touches them. The split is
 * recorded rather than left implicit so a reviewer can see both halves are covered and neither is
 * orphaned.</p>
 *
 * <p>Tier one owns and is not restated here: the load-bearing {@code INITIALIZE} at
 * {@code app/cbl/CBTRN02C.cbl:504} and the two arms as service-level partitions, both in
 * {@code CategoryBalanceServiceTest}; reject-reason precedence, the verbatim reject literals and the two
 * inclusive validation boundaries, in {@code PostingValidationServiceTest}; and ALL interest arithmetic,
 * in {@code InterestCalculationServiceTest}. This table's balance is the multiplicand of that formula and
 * never its result, so no case here asserts rounding on the interest path -- the reference divide
 * truncates, no {@code ROUNDED} phrase appearing anywhere under {@code app/cbl}, and an assertion here
 * that assumed otherwise would contradict the baseline while appearing to defend it.</p>
 *
 * <p>Tier two owns and is not restated here: the graded return-code tier and the counter lines, the
 * inversion of a baseline step gate into an orchestrator predicate, and job-level golden parity, in
 * {@code PostTransactionsJobTest}; the job-level control-break structure and the omitted final-account
 * flush, in {@code CalculateInterestJobTest}. That omission is visible in
 * {@code tests/golden/interest/happy_path/acctdat.expected}, where the first account moves while the
 * last one does not, and it is a documented baseline divergence rather than a walk-order fault.</p>
 *
 * <p>No case here asserts a byte image or the fifty-byte fixed-width record.
 * {@code TransactionCategoryBalanceRecordMapper} owns the record and its trailing {@code FILLER}, whose
 * pad byte differs between a seeded row and a freshly created one; <b>this tier asserts rows, and where
 * it needs a value it asserts the field.</b></p>
 *
 * <h2>Boundaries</h2>
 *
 * <p>Assumptions: these cases write ROWS into {@code ledger.transaction_category_balances} under the
 * narrowly scoped cross-schema grant and create no STRUCTURE anywhere. That table's data definition
 * belongs to {@code transaction-service}'s {@code V1__ledger.sql}, and its shape at test time is
 * supplied by {@code src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql}. A
 * definition authored here would be a second definition of a table another context already defines,
 * diverging silently the moment either side changed, so no case declares or asserts on an index either.</p>
 *
 * <p>Assumptions: the harness DDL is the normative physical contract at test time and every mapping was
 * verified against it before anything below was asserted, because the test profile runs schema
 * validation and a disagreement is a hard start-up failure for every class in this package at once
 * rather than one failing assertion. The three key columns and the balance column were checked
 * explicitly on all three sides -- harness, owning migration and the nested identifier of
 * {@link TransactionCategoryBalance} -- and they agree: {@code account_id} BIGINT against a
 * {@code Long}, {@code type_cd} CHAR(2) and {@code category_cd} CHAR(4) against fixed-width
 * {@code String}s, and {@code balance} NUMERIC(11,2) against a {@code BigDecimal}. No residual
 * disagreement was found, so nothing needed recording as a coordination finding.</p>
 *
 * <p>Every path under {@code app/} named above is reference material, read as the specification and
 * never modified. The oracle suite under {@code tests/} is read the same way and is never re-pinned.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = TransactionCategoryBalanceRepositoryIT.CategoryBalancePersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class TransactionCategoryBalanceRepositoryIT {

    /**
     * The engine image, pinned by digest rather than by tag as every class in this package pins it.
     */
    // Assumptions: a digest names one immutable image while a tag is a moving reference, and a walk
    //     ordering assertion is exactly the kind that a collation change between two minor releases
    //     could turn from passing to failing with no commit in between.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The harness script that supplies the four schemas this module does not own.
     */
    // Assumptions: this runs as an init script rather than as a Flyway migration, so it is applied
    //     before Flyway opens a connection and its tables never enter this module's migration history.
    //     The ledger table read below is created there and by nothing on this module's classpath.
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /** The table these cases empty and refill, qualified because the search path is not relied on. */
    private static final String CATEGORY_BALANCE_TABLE = "ledger.transaction_category_balances";

    /**
     * The account identifier the posting parity fixtures carry, as a magnitude rather than as digits.
     */
    // Assumptions: tests/fixtures/posting/happy_path/tcatbal.txt renders this key as the zero-padded
    //     characters 00000000007, which is the eleven-byte PIC 9(11) field at app/cpy/CVTRA01Y.cpy:6.
    //     The column is BIGINT, so the padding is a property of the fixed-width record and not of the
    //     stored value, and it is deliberately not reproduced in this literal.
    private static final long ACCOUNT_ID = 7L;

    /** The transaction type code both posting parity fixtures carry. */
    private static final String TYPE_CD = "01";

    /** The transaction category code both posting parity fixtures carry. */
    private static final String CATEGORY_CD = "0001";

    /**
     * The balance the update-arm fixture starts from, decoded from its zoned-decimal image.
     */
    // Assumptions: the eleven-byte field reads 0000001000{ in
    //     tests/fixtures/posting/happy_path/tcatbal.txt. The sign is overpunched onto the LAST digit
    //     position, where { encodes a positive zero, so the digits are 000000100 followed by 0 and 0
    //     and the value is 100.00. Decoding it here rather than storing the image keeps this tier
    //     asserting rows rather than bytes.
    private static final String OPENING_BALANCE = "100.00";

    /**
     * The amount the posting fixtures present, decoded from the daily-transaction record.
     */
    // Assumptions: the amount field of tests/fixtures/posting/happy_path/dailytran.txt reads
    //     0000005047G, where G overpunches a positive seven onto the final digit, giving 504.77. The
    //     zero_balance scenario presents the same amount, which is what makes one constant serve both
    //     arms and makes the two expected balances differ only by the opening balance.
    private static final String POSTED_AMOUNT = "504.77";

    /**
     * The balance the update arm must reach, taken from the committed golden master.
     */
    // Assumptions: tests/golden/posting/happy_path/tcatbal.expected reads 0000006047G, which decodes
    //     to 604.77 by the same overpunch rule. It is asserted as the golden figure rather than
    //     computed silently, so a change to either input constant fails here instead of quietly
    //     agreeing with itself.
    private static final String ACCUMULATED_BALANCE = "604.77";

    /**
     * A second posted amount, used to show that accumulation is additive rather than assignment.
     */
    private static final String SECOND_POSTED_AMOUNT = "25.13";

    /**
     * A negative amount, constructed because the reference ships no negative posting vector.
     */
    // Trade-offs: this figure is INVENTED and has no golden master, and saying so is the point of the
    //     comment. All eight posting scenarios carry a positive sign byte, so nothing that ships
    //     exercises a negative accumulation -- yet fifty of the three hundred rows of
    //     app/data/ASCII/dailytran.txt are negative and the accumulation at app/cbl/CBTRN02C.cbl:508
    //     and :527 is an unconditional ADD, so a negative running balance is genuine reference
    //     behaviour. The compromise accepted is a case whose expectation rests on the declared
    //     signed PICTURE rather than on a captured output; the alternative was to leave the sign path
    //     unproven and discover a lost sign from a parity diff instead.
    private static final String NEGATIVE_AMOUNT = "-750.25";

    /**
     * The account identifiers the walk fixture spans, deliberately of differing digit counts.
     */
    // Alternatives Considered: three identifiers of equal width, which is how the reference renders
    //     them in its fixed-width records and the obvious choice. Rejected because it cannot
    //     distinguish the two orderings it needs to: zero-padded equal-width digits sort identically
    //     whether the column is an integer or text, so a mapping that had silently become character
    //     typed would pass. These three do not -- by magnitude they run 7, 42, 99999999999, while as
    //     characters they would run "42", "7", "99999999999" -- so the assertion fails unless the
    //     column really is the BIGINT the harness declares. The largest value fills the eleven digits
    //     app/cpy/CVTRA01Y.cpy:6 declares and does not exceed them.
    private static final long[] WALK_ACCOUNT_IDS = {7L, 42L, 99999999999L};

    /** The type codes the walk fixture spans, so the second key level is genuinely exercised. */
    private static final String[] WALK_TYPE_CODES = {"01", "02", "10"};

    /**
     * The category codes the walk fixture spans, sized so the whole grid clears the fetch window.
     */
    // Alternatives Considered: varying only the account identifier, which is all the control break at
    //     app/cbl/CBACT04C.cbl:194 examines. Rejected because such a fixture passes under a query
    //     ordered by the account alone and therefore proves nothing about the second and third key
    //     levels, which are observable: app/cbl/CBACT04C.cbl:193 emits one line per row scanned and
    //     that stream is compared byte for byte by the golden-master oracle. Twelve codes across three
    //     types and three accounts also puts the grid above the fetch window, so one fixture serves
    //     both requirements instead of two.
    private static final String[] WALK_CATEGORY_CODES = {
        "0001", "0002", "0003", "0004", "0005", "0006",
        "0007", "0008", "0009", "0010", "0011", "0012",
    };

    /**
     * The number of rows the walk fixture holds, which the grid above fixes rather than a bare literal.
     */
    // Assumptions: the walk declares a fetch-size query hint of one hundred and the test profile sets
    //     the same figure as its session default, so this product being above one hundred is what
    //     guarantees the ordering assertion spans more than one round trip to the engine. Deriving it
    //     from the three arrays rather than restating it means widening any key level cannot leave the
    //     figure behind, and the value is asserted against that threshold in the walk case itself.
    private static final int WALK_ROW_COUNT =
            WALK_ACCOUNT_IDS.length * WALK_TYPE_CODES.length * WALK_CATEGORY_CODES.length;

    /**
     * The number of rows the walk buffers per round trip, mirrored from the interface under test.
     */
    // Assumptions: the walk fixes this window with a query hint on the method itself rather than
    //     inheriting a session default, so the figure cannot be changed by a profile and is safe to
    //     mirror as a constant. It is mirrored rather than read because a test that derived the
    //     threshold from the same annotation it is checking against would agree with any value,
    //     including one small enough to make the multi-round-trip claim false.
    private static final int WALK_FETCH_WINDOW = 100;

    /** The number of trailing account-identifier digits a diagnostic may name. */
    // Assumptions: AAP 0.7.8 masks a primary account number to its last four digits and never returns
    //     a card verification value, and this tier's convention is that failure output never renders a
    //     whole record. This table carries no card number, but it does carry an account identifier
    //     beside a balance, so the convention is honoured here rather than treated as inapplicable.
    //     Naming the contract is what keeps a later reader from widening the label as a debugging
    //     convenience.
    private static final int UNMASKED_DIGITS = 4;

    /**
     * The engine every case in this class reads and writes, started once for this class alone.
     */
    // Alternatives Considered: a shared abstract base class holding this container for the whole
    //     package. Rejected by the package charter and for a concrete reason: a container in a base
    //     class is shared mutable state, so rows one class inserts are rows another reads and the
    //     failure names whichever class ran second. Every case below empties this table for itself,
    //     which is only sound while the container belongs to this class.
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The interface under test, injected as the production repository rather than reconstructed. */
    @Autowired
    private TransactionCategoryBalanceRepository repository;

    /** The persistence context, flushed and cleared so a read-back is a real select. */
    @Autowired
    private EntityManager entityManager;

    /**
     * The boundary a walk is consumed inside, since the walk refuses a caller holding no transaction.
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used only to empty this table between cases. */
    private JdbcTemplate jdbc;

    /**
     * Points the datasource and Flyway at the container the runtime assigned a port to.
     *
     * @param registry the registry Spring hands this method to accept deferred property values; each
     *     value is registered as a supplier so it is read after the container has started
     */
    // Assumptions: no connection literal may appear in this class. A container assigns its host port
    //     as it starts, so a literal authored beforehand would either address nothing or -- the worse
    //     outcome, because it would pass -- address whatever database happened to be listening.
    // Assumptions: the Flyway pair is registered in addition to the datasource pair because the base
    //     profile binds those two keys to placeholders with no fallback, so omitting them fails
    //     context start-up on an unresolved placeholder rather than on anything to do with a category
    //     balance.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the category-balance table so each case observes only the rows it arranged.
     *
     * @param dataSource the pooled datasource the context built against the container, injected on the
     *     method rather than the field because it is needed only to construct the JDBC handle
     */
    // Assumptions: a delete is used rather than a truncate because this table is emptied on its own
    //     while sibling classes in this package empty several tables together, and a truncate would
    //     take a stronger lock than a per-case reset needs.
    @BeforeEach
    void emptyTheCategoryBalanceTable(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(
                status -> this.jdbc.update("DELETE FROM " + CATEGORY_BALANCE_TABLE));
    }

    /**
     * Confirms the walk delivers every row ordered by account, then type code, then category code.
     *
     * <p>Pins the key order that {@code app/jcl/TCATBALF.jcl:40} and {@code app/cpy/CVTRA01Y.cpy:6-8}
     * fix between them, delivered under the {@code ACCESS MODE IS SEQUENTIAL} that
     * {@code app/cbl/CBACT04C.cbl:29-30} declares.</p>
     */
    // Assumptions: this ordering is load-bearing rather than cosmetic, and the consequence of losing it
    //     is wrong money rather than untidy output. The control break at app/cbl/CBACT04C.cbl:194 is a
    //     SINGLE-KEY compare -- it tests the current row's account against the previous row's and
    //     nothing else, carrying no set of accounts already seen and no sort of its own. It is
    //     therefore correct only while every row of an account arrives consecutively. Under any other
    //     order one account breaks more than once, and each spurious break flushes a partial interest
    //     total at :196 and resets the accumulator at :200, so the run completes and reports success
    //     while having posted the wrong interest. A relational query has no default order, so the
    //     accrual job depends on this repository's ordering as an external guarantee.
    // Trade-offs: the rows are arranged in the exact REVERSE of the expected sequence and the fixture
    //     is larger than one fetch window, which makes this the slowest case in the class. Both are
    //     paid deliberately. Inserting in expected order would let a query that had lost its ORDER BY
    //     pass on nothing more than heap order, and a fixture inside one window would assert ordering
    //     only within a single buffer. Reversing every level also means a query ordered by the account
    //     alone still fails, because the within-account rows would then come back reversed.
    @Test
    @DisplayName("the walk returns every row in ascending account, type and category order")
    void theWalkDeliversEveryRowInAscendingCompositeKeyOrder() {
        assertThat(WALK_ROW_COUNT)
                .as("walk fixture rows, which must exceed the walk's own fetch window of %d so the"
                        + " ordering assertion spans more than one round trip", WALK_FETCH_WINDOW)
                .isGreaterThan(WALK_FETCH_WINDOW);

        List<String> arranged = this.arrangeWalkGridInReverseOrder();
        List<String> expected = expectedWalkOrder();

        assertThat(expected)
                .as("expected key labels, which must be free of duplicates or the comparison below"
                        + " could be satisfied by two rows whose masked labels collided")
                .doesNotHaveDuplicates();
        assertThat(arranged)
                .as("the arranged insertion sequence must differ from the expected sequence, or the"
                        + " comparison below could be satisfied by insertion order alone")
                .isNotEqualTo(expected);

        assertThat(this.walkedLabels())
                .as("key labels in the order the walk delivered them under the planner's own choice,"
                        + " each naming only the trailing %d digits of its account identifier",
                        UNMASKED_DIGITS)
                .containsExactlyElementsOf(expected);

        // Alternatives Considered: stopping at the assertion above, which is the whole of the property
        //     and reads as sufficient. Rejected on a MEASUREMENT rather than on caution: the ordering
        //     above was re-run against a query narrowed to the account identifier alone, and against a
        //     query carrying no ordering clause at all, and it PASSED both times. The reason is that
        //     this table's primary key covers all four columns, so the cheapest plan for the narrowed
        //     query is an index-only scan of that key -- which yields the full composite order as a
        //     side effect. Every candidate ordering is therefore observationally identical while that
        //     plan is chosen, and the assertion above cannot separate a three-level contract from a
        //     one-level one.
        // Assumptions: the second assertion closes that gap by removing the plan, not by changing the
        //     query. With index paths off the engine must sort a sequential scan, and a sort keyed on
        //     fewer columns than the contract names leaves the remaining levels in heap order -- which
        //     this fixture has deliberately arranged as the exact reverse of the expected sequence.
        //     The settings are transaction scoped, so they revert with the read and no later case runs
        //     under a modified planner. This matters beyond the test: the accrual control break at
        //     app/cbl/CBACT04C.cbl:194 depends on the order under WHATEVER plan the engine picks, and
        //     a sort plan is what it will pick once this table outgrows its index-only scan.
        assertThat(this.walkedLabelsUnderAForcedSort())
                .as("key labels in walk order with index paths disabled, so the ordering clause itself"
                        + " has to carry all three key levels rather than inheriting them from a scan"
                        + " of the primary key")
                .containsExactlyElementsOf(expected);
    }

    /**
     * Confirms the walk orders the account identifier by magnitude rather than as rendered digits.
     *
     * <p>Pins the account identifier declared {@code PIC 9(11)} at {@code app/cpy/CVTRA01Y.cpy:6} as
     * the leading eleven bytes of the {@code KEYS(17 0)} key at {@code app/jcl/TCATBALF.jcl:40},
     * mapped to the BIGINT column the harness declares.</p>
     */
    // Alternatives Considered: asserting the order over equal-width zero-padded identifiers, which is
    //     how the reference renders them in its fifty-byte records. Rejected because the two candidate
    //     orderings AGREE on padded values of equal width and so such a fixture cannot tell them
    //     apart: it would pass just as well against a column that had silently become character typed.
    //     The three identifiers here disagree -- by magnitude 7 precedes 42 precedes 99999999999,
    //     whereas as characters "42" would precede "7" -- so this case fails unless the ordering is
    //     genuinely numeric. The two code components are the opposite case and are left alone: they
    //     are fixed-width zero-padded character codes under a bytewise collation, where a bytewise
    //     order IS the intended order and coincides with the numeric one only while the padding holds.
    @Test
    @DisplayName("the walk orders the account identifier numerically, not as rendered digits")
    void theWalkOrdersTheAccountIdentifierByMagnitudeRatherThanLexically() {
        for (long accountId : WALK_ACCOUNT_IDS) {
            this.insertRow(keyOf(accountId, TYPE_CD, CATEGORY_CD), OPENING_BALANCE);
        }

        List<Long> delivered = this.walkedRows().stream()
                .map(TransactionCategoryBalance::getAccountId)
                .toList();

        assertThat(delivered)
                .as("account identifiers in walk order; by magnitude these run ascending, whereas a"
                        + " character ordering of the same values would place 42 before 7")
                .containsExactly(7L, 42L, 99999999999L)
                .isSorted();
    }

    /**
     * Confirms a walk over an empty table yields no row rather than failing.
     *
     * <p>Pins the end-of-file exit of the read loop at {@code app/cbl/CBACT04C.cbl:188-190}, which
     * completes over an input holding no record.</p>
     */
    // Assumptions: an empty category-balance table is a reachable state rather than a degenerate one.
    //     It is what a freshly provisioned environment holds before the first posting run, and accrual
    //     may legitimately be scheduled against it, so the walk has to terminate cleanly rather than
    //     treat an absent first row as a fault.
    @Test
    @DisplayName("a walk over an empty table delivers no row")
    void theWalkOfAnEmptyTableDeliversNoRow() {
        assertThat(this.rowCount())
                .as("rows present before the walk, which the per-case reset leaves at zero")
                .isZero();

        assertThat(this.walkedRows())
                .as("rows delivered by a walk over an empty table")
                .isEmpty();
    }

    /**
     * Confirms the walk refuses a caller that holds no transaction instead of returning a dead cursor.
     *
     * <p>Pins the declared propagation of the walk on
     * {@link TransactionCategoryBalanceRepository#findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc()},
     * whose contract states the caller must consume the stream inside a transaction it already holds.
     * This is a contract of the migrated interface and no reference line states it: the reference holds
     * an open file rather than a transaction, so the citation here is the interface rather than
     * {@code app/}.</p>
     */
    // Assumptions: the refusal is the point, not an inconvenience to be worked around. A cursor is
    //     valid only for the transaction that opened it, so the alternative default propagation would
    //     start a transaction, commit it as the method RETURNED, and hand back a stream already
    //     closed -- surfacing at the first element inside the caller's loop and naming neither the
    //     method nor the missing transaction. Proving the refusal here is what makes the
    //     try-with-resources-inside-a-transaction idiom every other case uses a requirement rather
    //     than a house style.
    @Test
    @DisplayName("the walk refuses a caller holding no transaction")
    void theWalkRefusesACallThatHoldsNoTransaction() {
        assertThatThrownBy(this.repository::findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc)
                .as("calling the walk with no transaction in progress")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Confirms the keyed read resolves a stored row through the whole three-part identifier.
     *
     * <p>Pins the keyed read at {@code app/cbl/CBTRN02C.cbl:474}, whose key is assembled from the three
     * components moved in at {@code :469-471}, over the update-arm fixture
     * {@code tests/fixtures/posting/happy_path/tcatbal.txt}.</p>
     */
    // Assumptions: the account component is the one the CALLER supplies from the cross-reference and
    //     never one derived from the transaction being posted. app/cbl/CBTRN02C.cbl:469 moves
    //     XREF-ACCT-ID into the key, and the daily-transaction record has no account identifier to
    //     offer in any case. This tier cannot prove where the caller obtained it -- CardXrefRepositoryIT
    //     owns the lookup that resolves a card to its account -- so what is proved here is the
    //     narrower and still necessary claim: the repository keys on exactly the three components
    //     handed to it and invents none of them.
    @Test
    @DisplayName("the keyed read resolves a row by its whole three-part key")
    void theKeyedReadResolvesARowByItsWholeThreePartKey() {
        TransactionCategoryBalanceId key = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);
        this.insertRow(key, OPENING_BALANCE);

        Optional<TransactionCategoryBalance> found = this.read(key);

        assertThat(found)
                .as("keyed read of the row arranged under %s", maskedLabel(key))
                .isPresent();
        assertThat(found.orElseThrow().getAccountId())
                .as("account component of the key the row came back under")
                .isEqualTo(ACCOUNT_ID);
        assertThat(found.orElseThrow().getTypeCd())
                .as("type component of the key the row came back under")
                .isEqualTo(TYPE_CD);
        assertThat(found.orElseThrow().getCategoryCd())
                .as("category component of the key the row came back under")
                .isEqualTo(CATEGORY_CD);
    }

    /**
     * Confirms a key no row carries yields an empty result rather than raising.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:481}, where the file status for record-not-found is accepted
     * alongside the success status as clean, so only some other status reaches the abend path.</p>
     */
    // Assumptions: an absent row is a NORMAL outcome of this read and not an error, and the consequence
    //     of treating it as one is severe rather than cosmetic. The reference sets its create flag from
    //     the not-found handler at app/cbl/CBTRN02C.cbl:478 and then accepts that status as clean at
    //     :481, precisely because the first transaction ever posted to a category finds no row -- the
    //     ordinary case rather than an exception. A repository that raised here would abend the posting
    //     job on the first transaction for every newly used category.
    @Test
    @DisplayName("the keyed read yields an empty result for a key no row carries, without raising")
    void theKeyedReadYieldsAnEmptyOptionalForAKeyNoRowCarries() {
        TransactionCategoryBalanceId absent = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);

        assertThat(this.read(absent))
                .as("keyed read of %s against a table holding no such row", maskedLabel(absent))
                .isEmpty();

        this.insertRow(absent, OPENING_BALANCE);

        assertThat(this.read(absent))
                .as("the same keyed read once the row exists, which is what makes the empty result"
                        + " above a statement about the table rather than about the method")
                .isPresent();
    }

    /**
     * Confirms the keyed read separates keys that differ only in one of the two code components.
     *
     * <p>Pins the three-part key of {@code app/cpy/CVTRA01Y.cpy:6-8}, whose seventeen bytes
     * {@code app/jcl/TCATBALF.jcl:40} declares as the whole key, so a difference in any component is a
     * different row.</p>
     */
    // Alternatives Considered: proving the key only through its account component, which is the
    //     component a reader assumes carries the identity. Rejected because the two code components are
    //     adjacent short strings, so a mapping that transposed or ignored one would still resolve the
    //     account and would fail only as a lookup that quietly matched the wrong row -- returning a
    //     balance belonging to another category, which accumulates silently rather than raising.
    @Test
    @DisplayName("the keyed read separates keys differing only in a type or category code")
    void theKeyedReadSeparatesKeysThatDifferOnlyInACode() {
        TransactionCategoryBalanceId stored = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);
        this.insertRow(stored, OPENING_BALANCE);

        assertThat(this.read(keyOf(ACCOUNT_ID, "02", CATEGORY_CD)))
                .as("same account and category as %s but a different type code", maskedLabel(stored))
                .isEmpty();
        assertThat(this.read(keyOf(ACCOUNT_ID, TYPE_CD, "0002")))
                .as("same account and type as %s but a different category code", maskedLabel(stored))
                .isEmpty();
        assertThat(this.read(stored))
                .as("the stored key itself, unaffected by the two near misses above")
                .isPresent();
    }

    /**
     * Confirms the create arm stores exactly the posted amount, starting from zero.
     *
     * <p>Pins the create arm at {@code app/cbl/CBTRN02C.cbl:503-524} against the committed vector
     * {@code tests/fixtures/posting/zero_balance/tcatbal.txt}, which holds no row at all, and its
     * golden master {@code tests/golden/posting/zero_balance/tcatbal.expected}, which holds one row at
     * the posted amount.</p>
     */
    // Assumptions: the created row starting at zero rather than at some earlier value is a property the
    //     reference has to work for, which is why asserting it is worthwhile. The INITIALIZE at
    //     app/cbl/CBTRN02C.cbl:504 is load-bearing: the failed READ leaves the previous iteration's
    //     bytes in the group item, so without it the ADD at :508 would accumulate onto a stale balance
    //     carried over from another key. CategoryBalanceServiceTest owns that reasoning at the service
    //     layer; what is asserted here is only the observable database outcome, which is that the
    //     stored balance equals the posted amount and not a penny more.
    @Test
    @DisplayName("the create arm stores exactly the posted amount")
    void theCreateArmStoresExactlyThePostedAmount() {
        TransactionCategoryBalanceId key = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);

        assertThat(this.read(key))
                .as("the arm-selecting read, empty here, which is what sends the caller down the"
                        + " create arm at app/cbl/CBTRN02C.cbl:495")
                .isEmpty();

        this.createArm(key, Money.of(POSTED_AMOUNT));

        this.assertBalanceIs(key, POSTED_AMOUNT);
    }

    /**
     * Confirms the update arm adds the posted amount to the balance already stored.
     *
     * <p>Pins the update arm at {@code app/cbl/CBTRN02C.cbl:526-542} against the committed pair
     * {@code tests/fixtures/posting/happy_path/tcatbal.txt} and
     * {@code tests/golden/posting/happy_path/tcatbal.expected}, whose zoned images decode to an opening
     * balance of 100.00, a posted amount of 504.77 and a resulting balance of 604.77.</p>
     */
    // Alternatives Considered: exercising both arms through one conflict-resolving upsert, which is the
    //     shorter route to the same stored value. Rejected because it would make which arm ran
    //     unobservable, and the branch at app/cbl/CBTRN02C.cbl:495 is behaviour the golden masters
    //     assert rather than an implementation detail -- the two scenarios above are separate committed
    //     vectors precisely because the reference distinguishes them. Asserting the arms in separate
    //     cases keeps a failure able to say which one broke.
    @Test
    @DisplayName("the update arm adds the posted amount to the stored balance")
    void theUpdateArmAddsThePostedAmountToTheStoredBalance() {
        TransactionCategoryBalanceId key = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);
        this.insertRow(key, OPENING_BALANCE);

        TransactionCategoryBalance existing = this.read(key)
                .orElseThrow(() -> new AssertionError(
                        "the arranged row was not readable under " + maskedLabel(key)));

        this.updateArm(existing, Money.of(POSTED_AMOUNT));

        this.assertBalanceIs(key, ACCUMULATED_BALANCE);
        assertThat(this.rowCount())
                .as("rows present afterwards; the update arm rewrites the row it read rather than"
                        + " adding a second one under the same key")
                .isEqualTo(1);
    }

    /**
     * Confirms two amounts posted against one key accumulate rather than overwriting each other.
     *
     * <p>Pins the accumulation at {@code app/cbl/CBTRN02C.cbl:527}, which is an unconditional add to
     * the value just read rather than a move over it.</p>
     */
    // Alternatives Considered: one application of one amount, which the update-arm case above already
    //     performs. Rejected because a single application cannot distinguish addition from assignment:
    //     an implementation that simply stored the incoming amount would produce the same balance
    //     whenever the opening balance happened to be zero, and would agree with a one-shot assertion
    //     wherever the expected figure was computed the same wrong way. Two applications separate them
    //     -- addition reaches the opening balance plus both amounts, assignment reaches only the last.
    @Test
    @DisplayName("two amounts posted against one key accumulate rather than overwriting")
    void twoPostingsAgainstOneKeyAccumulateRatherThanOverwrite() {
        TransactionCategoryBalanceId key = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);
        this.insertRow(key, OPENING_BALANCE);

        this.updateArm(this.requireRow(key), Money.of(POSTED_AMOUNT));
        this.updateArm(this.requireRow(key), Money.of(SECOND_POSTED_AMOUNT));

        BigDecimal bothApplied = Money.of(OPENING_BALANCE)
                .plus(Money.of(POSTED_AMOUNT))
                .plus(Money.of(SECOND_POSTED_AMOUNT))
                .amount();

        assertThat(this.balanceOf(key))
                .as("balance after two postings against %s; addition reaches the opening balance plus"
                        + " both amounts, whereas assignment would reach only the second amount",
                        maskedLabel(key))
                .isEqualByComparingTo(bothApplied)
                .isNotEqualByComparingTo(Money.of(SECOND_POSTED_AMOUNT).amount());
    }

    /**
     * Confirms a negative running balance persists and reads back carrying its sign.
     *
     * <p>Pins the signed picture {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:9}
     * and the unconditional add at {@code app/cbl/CBTRN02C.cbl:527}. <b>No golden vector exists for
     * this case and none is implied</b>: every one of the eight posting scenarios carries a positive
     * amount, so the expectation below rests on the declared picture rather than on captured output.</p>
     */
    // Trade-offs: this case is CONSTRUCTED, and that is a compromise accepted rather than an oversight.
    //     Fifty of the three hundred rows of app/data/ASCII/dailytran.txt carry a negative amount and
    //     the accumulation is an unconditional ADD in both arms, so a negative running balance is
    //     genuine reference behaviour -- yet no shipped posting fixture produces one, so an
    //     implementation that dropped the sign would pass against everything that ships. What is given
    //     up is a captured expectation; what is bought is that the signed column is proved to round
    //     trip before a parity diff has to discover it. The reference build makes the same point from
    //     the other side: it requires the EBCDIC sign convention because the default silently corrupts
    //     negative balances, per tests/README.md section 5.2.
    @Test
    @DisplayName("a negative running balance persists and reads back with its sign")
    void aNegativeAccumulationPersistsWithItsSign() {
        TransactionCategoryBalanceId key = keyOf(ACCOUNT_ID, TYPE_CD, CATEGORY_CD);
        this.insertRow(key, OPENING_BALANCE);

        this.updateArm(this.requireRow(key), Money.of(NEGATIVE_AMOUNT));

        BigDecimal expected = Money.of(OPENING_BALANCE).plus(Money.of(NEGATIVE_AMOUNT)).amount();

        assertThat(expected)
                .as("the constructed expectation, which must itself be negative or this case would"
                        + " prove nothing about the sign")
                .isNegative();
        this.assertBalanceIs(key, expected.toPlainString());
    }

    /**
     * Builds one composite identifier from its three declared components.
     *
     * @param accountId the account identifier, held as a magnitude because the column is BIGINT; it is
     *     the eleven-digit field at {@code app/cpy/CVTRA01Y.cpy:6} and carries no rendered padding here
     * @param typeCd the two-character transaction type code at {@code app/cpy/CVTRA01Y.cpy:7}; must be
     *     supplied at its declared width because the column is fixed width
     * @param categoryCd the four-character transaction category code at
     *     {@code app/cpy/CVTRA01Y.cpy:8}; must be supplied zero padded to its declared width
     * @return the identifier the two repository members both key on, never {@code null}
     */
    // Assumptions: the entity's own nested identifier type is used rather than three loose arguments
    //     threaded through the cases. The reference keys this cluster on a GROUP ITEM -- RECORD KEY IS
    //     FD-TRAN-CAT-KEY at app/cbl/CBACT04C.cbl:31 -- so one whole-key value matches the reference
    //     shape and cannot drift from the entity's identity, whereas three separate arguments could be
    //     transposed at a call site and still compile, the two code arguments being adjacent short
    //     strings.
    private static TransactionCategoryBalanceId keyOf(
            long accountId, String typeCd, String categoryCd) {
        return new TransactionCategoryBalanceId(accountId, typeCd, categoryCd);
    }

    /**
     * Renders one identifier for a failure message with its account identifier masked.
     *
     * @param key the identifier to render; must not be {@code null}
     * @return a label naming only the trailing digits of the account identifier beside the two codes,
     *     never {@code null} and never the row's balance
     */
    // Assumptions: no diagnostic in this class renders a whole row. AAP 0.7.8 masks a primary account
    //     number to its last four digits and never returns a card verification value; this table
    //     carries neither, but it does carry an account identifier beside a balance, so the same
    //     convention is applied to the identifier and the balance is left out of labels entirely. The
    //     masked form is still enough to tell the arranged keys of these cases apart, which is all a
    //     failure message needs.
    private static String maskedLabel(TransactionCategoryBalanceId key) {
        String digits = Long.toString(key.getAccountId());
        String tail = digits.length() <= UNMASKED_DIGITS
                ? digits
                : digits.substring(digits.length() - UNMASKED_DIGITS);
        return "account ..." + tail + "/" + key.getTypeCd() + "/" + key.getCategoryCd();
    }

    /**
     * Stores one row at a stated balance, standing in for a seeded fixture row.
     *
     * @param key the identifier to store the row under; must not be {@code null}
     * @param balance the opening balance as a plain decimal string, converted through the shared money
     *     type so the stored scale is the one the column declares
     */
    // Assumptions: arranging a row through the repository rather than through insert text keeps the
    //     arrangement subject to the same mapping the assertions read back through, so a case cannot
    //     accidentally prove that hand-written SQL agrees with itself. The scale the column declares is
    //     reached through the shared money type rather than by calling a scale setter here, which keeps
    //     the single arithmetic boundary rules T3 and T4 require out of this file.
    private void insertRow(TransactionCategoryBalanceId key, String balance) {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.repository.save(new TransactionCategoryBalance(key, Money.of(balance).amount()));
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Performs the create arm: a row that did not exist is written carrying only the posted amount.
     *
     * @param key the identifier no row currently occupies; must not be {@code null}
     * @param amount the posted amount, which becomes the whole of the new balance
     */
    // Assumptions: the new row is built from the single-argument entity constructor, which opens the
    //     balance at zero, and the amount is then ADDED to it. That mirrors app/cbl/CBTRN02C.cbl:504
    //     followed by :508 rather than short-cutting to a constructor that takes the amount directly:
    //     the two forms reach the same stored value, but only this one makes the create arm visibly the
    //     same unconditional addition as the update arm, which is the property this class owns.
    private void createArm(TransactionCategoryBalanceId key, Money amount) {
        this.transactionTemplate.executeWithoutResult(status -> {
            TransactionCategoryBalance created = new TransactionCategoryBalance(key);
            created.setBalance(Money.of(created.getBalance()).plus(amount).amount());
            this.repository.save(created);
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Performs the update arm: the posted amount is added to the balance already read.
     *
     * @param existing the row the arm-selecting read returned, whose stored balance is the addend's
     *     counterpart; must not be {@code null}
     * @param amount the posted amount to add to that stored balance
     */
    // Assumptions: this reads the balance off the row the caller already read and adds to it, which is
    //     what makes the operation a read-modify-write rather than an overwrite. app/cbl/CBTRN02C.cbl
    //     :527 adds to the record the READ at :474 populated, so an implementation that formed the new
    //     balance from the amount alone would be a different program even where the arithmetic happened
    //     to agree.
    private void updateArm(TransactionCategoryBalance existing, Money amount) {
        this.transactionTemplate.executeWithoutResult(status -> {
            existing.setBalance(Money.of(existing.getBalance()).plus(amount).amount());
            this.repository.save(existing);
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Reads one row by its whole key through the interface under test.
     *
     * @param key the identifier to read; must not be {@code null}
     * @return the matching row, or an empty optional when the table holds no row under that key --
     *     which is a normal outcome and not an error
     */
    private Optional<TransactionCategoryBalance> read(TransactionCategoryBalanceId key) {
        return this.transactionTemplate.execute(status -> this.repository.findByIdIs(key));
    }

    /**
     * Reads one row that a case has already arranged and is entitled to assume present.
     *
     * @param key the identifier the row was arranged under; must not be {@code null}
     * @return the matching row, never {@code null}
     * @throws AssertionError if no row is stored under that key, which means the arrangement rather
     *     than the behaviour under test has failed
     */
    // Assumptions: an arrangement failure is raised as an assertion error naming the masked key rather
    //     than surfacing later as a null. The two cases that use this read the same row twice, so a
    //     silent absence would otherwise fail on the second read and point at the accumulation instead
    //     of at the arrangement.
    private TransactionCategoryBalance requireRow(TransactionCategoryBalanceId key) {
        return this.read(key).orElseThrow(() -> new AssertionError(
                "expected an arranged row under " + maskedLabel(key)));
    }

    /**
     * Reads the stored balance for one key.
     *
     * @param key the identifier to read the balance of; must not be {@code null}
     * @return the stored balance at the scale the column declares, never {@code null}
     * @throws AssertionError if no row is stored under that key
     */
    private BigDecimal balanceOf(TransactionCategoryBalanceId key) {
        return this.requireRow(key).getBalance();
    }

    /**
     * Asserts the stored balance equals a stated figure, by value and at the declared scale.
     *
     * @param key the identifier whose stored balance is being asserted; must not be {@code null}
     * @param expected the expected balance as a plain decimal string, so the figure reads in the
     *     message exactly as it was written in the case
     * @throws AssertionError if no row is stored under that key
     */
    // Assumptions: the value comparison is by compareTo and never by equals, because equals on this
    //     decimal type is scale sensitive -- 604.7 does not equal 604.70 and 100.0 does not equal
    //     100.00 -- so an equals comparison would report a correct balance as wrong purely on how many
    //     trailing zeros the expectation happened to be written with.
    // Assumptions: the scale is asserted SEPARATELY and deliberately, since the comparison above is
    //     blind to it. The column is declared NUMERIC with two decimal places, from the
    //     PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:9, so a value that round tripped at another scale
    //     would still satisfy the comparison while no longer matching the contract the column states.
    private void assertBalanceIs(TransactionCategoryBalanceId key, String expected) {
        BigDecimal stored = this.balanceOf(key);
        assertThat(stored)
                .as("stored balance under %s", maskedLabel(key))
                .isEqualByComparingTo(new BigDecimal(expected));
        assertThat(stored.scale())
                .as("scale of the stored balance under %s, which the column declares",
                        maskedLabel(key))
                .isEqualTo(Money.SCALE);
    }

    /**
     * Arranges the whole walk grid in the exact reverse of the order the walk must deliver it in.
     *
     * @return the masked labels of the rows in the order they were inserted, which is descending on
     *     every key level, never {@code null}
     */
    // Trade-offs: every level is reversed rather than only the account level. Reversing the account
    //     alone would leave the within-account rows inserted ascending, so a query that had lost its
    //     second and third ordering levels could still return them ascending by nothing more than the
    //     order the engine happened to read them in, and the case would pass while proving only the
    //     first level. The cost is that the arranged sequence is unreadable as a list; it is returned
    //     so the case can assert it genuinely differs from the expected one.
    private List<String> arrangeWalkGridInReverseOrder() {
        List<String> arranged = new ArrayList<>(WALK_ROW_COUNT);
        for (int account = WALK_ACCOUNT_IDS.length - 1; account >= 0; account--) {
            for (int type = WALK_TYPE_CODES.length - 1; type >= 0; type--) {
                for (int category = WALK_CATEGORY_CODES.length - 1; category >= 0; category--) {
                    TransactionCategoryBalanceId key = keyOf(WALK_ACCOUNT_IDS[account],
                            WALK_TYPE_CODES[type], WALK_CATEGORY_CODES[category]);
                    this.insertRow(key, OPENING_BALANCE);
                    arranged.add(maskedLabel(key));
                }
            }
        }
        return arranged;
    }

    /**
     * Builds the sequence the walk is required to deliver, ascending on all three key levels.
     *
     * @return the masked labels of the whole walk grid in ascending account, type and category order,
     *     never {@code null}
     */
    // Assumptions: the expected sequence is generated from the same three arrays in ascending nesting
    //     rather than written out as a literal list. A hand-written list of over a hundred keys is a
    //     second place the key order is stated, and the failure mode of two statements of one order is
    //     that the list is corrected to match a broken query rather than the query to match the list.
    // Alternatives Considered: comparing the identifier objects instead of these labels, which is the
    //     stronger comparison in principle because the identifier's equality covers all three
    //     components without masking. Rejected on the failure message rather than on the assertion:
    //     that type's own rendering deliberately omits the account identifier, so a mismatch would
    //     print over a hundred entries that differ only in components it does not show. The labels
    //     carry both codes in full and the account identifier masked, and the case asserts they hold
    //     no duplicates, so nothing is given up in discriminating power.
    private static List<String> expectedWalkOrder() {
        List<String> expected = new ArrayList<>(WALK_ROW_COUNT);
        long[] ascendingAccounts = WALK_ACCOUNT_IDS.clone();
        Arrays.sort(ascendingAccounts);
        String[] ascendingTypes = WALK_TYPE_CODES.clone();
        Arrays.sort(ascendingTypes);
        String[] ascendingCategories = WALK_CATEGORY_CODES.clone();
        Arrays.sort(ascendingCategories);
        for (long accountId : ascendingAccounts) {
            for (String typeCd : ascendingTypes) {
                for (String categoryCd : ascendingCategories) {
                    expected.add(maskedLabel(keyOf(accountId, typeCd, categoryCd)));
                }
            }
        }
        return expected;
    }

    /**
     * Walks the whole table through the interface under test and materialises the rows.
     *
     * @return every row in the order the walk delivered it, never {@code null}
     */
    // Assumptions: the stream is consumed inside the transaction the template opens and closed by
    //     try-with-resources, which the walk's own contract requires and which one case here proves is
    //     enforced rather than advisory. Materialising inside that boundary is what lets the assertions
    //     run after it has closed; the fixture is bounded by this class, so holding it is safe here in
    //     a way it would not be for the production walk over a whole table.
    private List<TransactionCategoryBalance> walkedRows() {
        return this.transactionTemplate.execute(status -> {
            try (Stream<TransactionCategoryBalance> rows =
                    this.repository.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc()) {
                return rows.toList();
            }
        });
    }

    /**
     * Walks the whole table and reduces each row to its masked key label.
     *
     * @return every row's masked label in the order the walk delivered it, never {@code null}
     */
    private List<String> walkedLabels() {
        return this.walkedRows().stream()
                .map(TransactionCategoryBalance::getId)
                .map(TransactionCategoryBalanceRepositoryIT::maskedLabel)
                .toList();
    }

    /**
     * Walks the whole table with index access paths disabled, so the engine must sort to order.
     *
     * @return every row's masked label in the order the walk delivered it once no index could supply
     *     that order, never {@code null}
     */
    // Assumptions: the three settings are applied on the SAME connection the walk then reads through,
    //     which holds because a template built over the transaction's datasource is handed the
    //     transaction-bound connection rather than a fresh one. Were that not so the settings would
    //     land on a different session and this helper would silently degrade into a duplicate of the
    //     plain walk -- passing, and proving nothing beyond what the plain walk already proves.
    // Trade-offs: the transaction-scoped form is used rather than a session-wide one so the settings
    //     revert when this read commits. The cost is that they have to be reapplied on every call,
    //     which is accepted because the alternative leaves a modified planner behind for whichever
    //     case runs next and turns one deliberate narrowing into an ambient property of the class.
    private List<String> walkedLabelsUnderAForcedSort() {
        List<TransactionCategoryBalance> rows = this.transactionTemplate.execute(status -> {
            this.jdbc.execute("SET LOCAL enable_indexscan = off");
            this.jdbc.execute("SET LOCAL enable_indexonlyscan = off");
            this.jdbc.execute("SET LOCAL enable_bitmapscan = off");
            try (Stream<TransactionCategoryBalance> walked =
                    this.repository.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc()) {
                return walked.toList();
            }
        });
        return rows.stream()
                .map(TransactionCategoryBalance::getId)
                .map(TransactionCategoryBalanceRepositoryIT::maskedLabel)
                .toList();
    }

    /**
     * Counts the rows currently stored, independently of the mapping under test.
     *
     * @return the number of rows in the category-balance table, never negative
     */
    // Alternatives Considered: counting through the repository's inherited count method. Rejected
    //     because two cases use this figure to check what the mapping DID -- that the update arm
    //     rewrote a row instead of adding one, and that a reset really emptied the table -- and a count
    //     taken through the same mapping could agree with a mapping that was writing the wrong rows.
    private int rowCount() {
        Integer counted = this.jdbc.queryForObject(
                "SELECT count(*) FROM " + CATEGORY_BALANCE_TABLE, Integer.class);
        return counted == null ? 0 : counted;
    }

    /**
     * Boots the narrowest context these cases need: the mappings, the interfaces and a datasource.
     */
    // Assumptions: the entity and repository packages are named explicitly rather than discovered from
    //     a production application class, so this context carries no message listener and no scheduled
    //     work. The test profile leaves the messaging properties unset and the module's queue
    //     configuration is gated on one of them, so nothing here needs a transport to be stood up.
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class CategoryBalancePersistenceTestApplication {
    }
}
