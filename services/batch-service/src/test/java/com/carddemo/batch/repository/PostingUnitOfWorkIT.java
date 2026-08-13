package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.mapper.DailyTransactionMapper;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.common.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.context.annotation.Bean;
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
 * Proves the posting unit of work is ONE atomic transaction spanning two schemas.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: {@code app/cbl/CBTRN02C.cbl} writes three records for every accepted daily transaction
 * and commits them together. Its paragraph {@code 2000-POST-TRANSACTION.} opens at L424; L440 to L442
 * write the category balance, the account and the posted transaction; and the program's single
 * commit scope covers all three. {@code PostTransactionsJob.postOneRecord} transcribes exactly that
 * order -- category balance, then account, then ledger -- inside the tasklet step's transaction. In the
 * target those three rows live in TWO schemas, {@code ledger} and {@code account}, reached through the
 * narrowly-scoped cross-schema write grants {@code data-migration/sql/V0__schemas_and_roles.sql}
 * declares. This class is the executable statement that the split does not break the atomicity.</p>
 *
 * <p>Refactoring Rationale: the migration plan records that a saga with compensating reversals was
 * evaluated for this unit of work and REJECTED, because it would make states such as a posted
 * transaction with an unposted balance observable and the golden masters would correctly flag them as a
 * parity failure. That rejection is only sound if the single-transaction alternative genuinely holds
 * across the schema boundary, and nothing asserted it. A cross-schema write that silently committed in
 * two pieces would look identical to one that did not, right up to the first failure between them.</p>
 *
 * <h2>Why the failure is a real one</h2>
 *
 * <p>Alternatives Considered: raising a manufactured exception from the test after the second write, or
 * calling {@code setRollbackOnly}. Both were rejected because they prove the framework rolls back a
 * transaction, which is not in doubt. The rollback here is provoked by a genuine database refusal at
 * the THIRD write -- a description one character wider than {@code description VARCHAR(100)} admits,
 * refused by the engine with SQLSTATE 22001 as {@code value too long} -- so what is asserted is that a real constraint
 * violation at the last write of the unit takes the first two with it, across the schema boundary,
 * exactly as an abend inside the reference's commit scope would have.</p>
 *
 * <p>Assumptions: an over-long description is the refusal chosen, and it is a defect this feed can
 * genuinely produce rather than an artificial one. {@code DALYTRAN-DESC} is {@code PIC X(100)} at L10 of
 * {@code app/cpy/CVTRA06Y.cpy} and the feed is a fixed-width stream, so a record cut one byte adrift
 * carries a wider description than the column admits. Nothing between the feed and the column narrows
 * it -- the entity stores every value exactly as supplied and the mapper carries the description across
 * verbatim -- which is precisely why the engine is the thing that refuses it.</p>
 *
 * <p>Alternatives Considered: a duplicate posted-transaction identifier, which was tried FIRST and does
 * not refuse at all. {@code JpaRepository.save} on an entity whose assigned identifier already exists
 * issues a merge rather than an insert, so the second posting silently UPDATED the first row and the
 * unit committed. That is a real property of this write path and worth knowing, and it is the reason the
 * refusal had to come from a column contract instead.</p>
 *
 * <p>Assumptions: the two surviving writes are read back through fresh selects after the rollback, from
 * a cleared persistence context. Reading through the context that attempted them would return the
 * in-memory instances and would report the rollback as successful whether or not the database had
 * performed one.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is disabled for this context,
//       because application.yml's `optional:aws-parameterstore:` location builds an SSM client while
//       configuration is still loading and a build host with no AWS_REGION aborts the context on the
//       unresolved placeholder. The sibling BatchRunRepositoryIT records the full measurement, why
//       disabling the LOAD is sufficient when the resolver ignores the flag, and the two rejected
//       alternatives; it is cited from here rather than repeated.
@SpringBootTest(
        classes = PostingUnitOfWorkIT.PostingPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class PostingUnitOfWorkIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two tests cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness that supplies the two foreign schemas.
     *
     * <p>Assumptions: this class REQUIRES the script rather than merely benefiting from it. Its writes
     * land in {@code ledger.transactions}, {@code ledger.transaction_category_balances} and
     * {@code account.accounts}, none of which this module's own migration creates or may create, so
     * without the script every case here fails on 42P01 undefined_table.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The account the posting unit writes against.
     *
     * <p>Assumptions: an eleven-digit value, the width {@code ACCT-ID PIC 9(11)} declares at L5 of
     * {@code app/cpy/CVACT01Y.cpy}. It is fabricated and identifies nothing.</p>
     */
    private static final long ACCOUNT_ID = 10000000042L;

    /**
     * The card number the cross-reference resolves to that account.
     *
     * <p>Assumptions: sixteen characters, the width {@code XREF-CARD-NUM PIC X(16)} declares at L5 of
     * {@code app/cpy/CVACT03Y.cpy}. It is fabricated, is not a valid card number under the Luhn check,
     * and identifies nothing.</p>
     */
    private static final String CARD_NUM = "4000000000000042";

    /** The customer the cross-reference names, fabricated at the declared nine-digit width. */
    private static final long CUSTOMER_ID = 900000042L;

    /** The transaction type of every posting in this class. */
    private static final String TYPE_CD = "01";

    /** The transaction category of every posting in this class. */
    private static final String CATEGORY_CD = "0001";

    /** The identifier of the transaction the accepted unit posts. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /**
     * The amount every posting in this class carries.
     *
     * <p>Assumptions: a positive amount with a non-zero cents component, so the account's balance and
     * its cycle-credit total both change observably and a scale that had been lost would show. Money is
     * {@code BigDecimal} at scale two throughout, which is transformation rule T3 and is enforced by
     * {@code common-lib}'s own architecture rules.</p>
     */
    private static final BigDecimal AMOUNT = new BigDecimal("123.45");

    /**
     * The description an accepted posting carries, comfortably inside its declared width.
     *
     * <p>Assumptions: {@code DALYTRAN-DESC} is {@code PIC X(100)} at L10 of
     * {@code app/cpy/CVTRA06Y.cpy} and the owning migration declares the column
     * {@code VARCHAR(100)}, so anything at or below one hundred characters is admitted.</p>
     */
    private static final String DESCRIPTION = "Posting unit of work fixture";

    /**
     * The declared width of the description, from which the refused value is derived.
     *
     * <p>Assumptions: one hundred, the width {@code DALYTRAN-DESC PIC X(100)} declares at L10 of
     * {@code app/cpy/CVTRA06Y.cpy} and the width the owning migration gives
     * {@code ledger.transactions.description}. It is named so the refused value below is derived from
     * the contract rather than from a literal that would not move with it.</p>
     */
    private static final int DESCRIPTION_WIDTH = 100;

    /**
     * A description exactly one character wider than the column admits.
     *
     * <p>Assumptions: the width is COMPUTED from the declared one rather than written as 101, so the
     * value moves with the column if the copybook ever does. One character over is the narrowest
     * violation available, which keeps the refusal attributable to the width rather than to anything
     * else about the value.</p>
     */
    private static final String OVERLONG_DESCRIPTION = "x".repeat(DESCRIPTION_WIDTH + 1);

    /** The account's balance before the posting. */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("100.00");

    /** The account's cycle credit before the posting. */
    private static final BigDecimal OPENING_CYCLE_CREDIT = new BigDecimal("10.00");

    /** The account's credit limit, comfortably above the posted amount so no reject arises. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /**
     * The instant the posted rows are stamped with.
     *
     * <p>Assumptions: a literal, never a clock read. {@code app/cbl/CBTRN02C.cbl} L438 stamps the
     * moment of posting, and the reference suite normalises that field before comparing goldens
     * precisely because it is the one non-deterministic value a posted record carries; a test that read
     * the clock would inherit the non-determinism the normalisation exists to remove.</p>
     */
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2022, 7, 18, 2, 5, 30);

    /** The originating instant the feed record carries, earlier than the processing stamp. */
    private static final LocalDateTime ORIGINATED_AT = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} rather than the
     * deprecated {@code org.testcontainers.containers}, and carries no type argument because the
     * replacement is not generic.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The posted ledger, injected as the production repository interface. */
    @Autowired
    private TransactionRepository ledger;

    /** The account master, injected as the production repository interface. */
    @Autowired
    private AccountRepository accounts;

    /** The cross-reference the posting reads the account from. */
    @Autowired
    private CardXrefRepository crossReferences;

    /** The category balances, injected as the production repository interface. */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalances;

    /**
     * The production accumulation rule, exercised rather than substituted.
     *
     * <p>Assumptions: the real service is used because its create-versus-update branch is part of what
     * the unit of work writes, and a substitute would leave the branch that decides whether a row is
     * inserted or updated unexercised against the real composite primary key.</p>
     */
    @Autowired
    private CategoryBalanceService balances;

    /** The persistence context, used to flush inside a boundary and to detach after it. */
    @Autowired
    private EntityManager entityManager;

    /** The boundary every posting unit below is issued inside, standing in for the tasklet step. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used only to empty the three tables between cases. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the Flyway pair is registered beside the datasource pair because
    //       application.yml binds spring.flyway.user and spring.flyway.password to placeholders with no
    //       fallback, and Boot consults those keys precisely when no connection-details bean supplies
    //       them -- which is this module's case, since the artifact contributing that bean is
    //       deliberately absent from its POM. The package charter records the full reasoning.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the three written tables and seeds the account and its cross-reference.
     *
     * <p>Assumptions: the seed is committed before each case rather than shared across the class, so a
     * case that rolls back a posting still starts from a known balance. The delete order is
     * child-before-parent only in the sense of what each case writes; the harness declares no foreign
     * key between these tables, deliberately, because the owning migrations declare none either.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void resetAndSeed(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM ledger.transactions");
            this.jdbc.update("DELETE FROM ledger.transaction_category_balances");
            this.jdbc.update("DELETE FROM account.accounts");
            this.jdbc.update("DELETE FROM account.card_xref");
        });
        this.transactionTemplate.executeWithoutResult(status -> {
            this.accounts.save(seedAccount());
            // WHY : Assumptions: the cross-reference is written through the persistence context and
            //   NOT through its repository, because that interface deliberately extends the bare
            //   marker rather than the full CRUD one and so declares no write method at all -- this
            //   module only ever READS the cross-reference. Persisting the seed directly is what lets
            //   the read path under test stay read-only.
            this.entityManager.persist(new CardXref(CARD_NUM, CUSTOMER_ID, ACCOUNT_ID));
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Confirms the three writes of one accepted posting all commit, in two schemas.
     *
     * <p>Assumptions: all three rows are asserted from fresh selects, and the account's two changed
     * money columns are asserted separately. The balance and the cycle credit are accumulated by
     * different statements in the reference -- {@code app/cbl/CBTRN02C.cbl} L543 to L546 chooses the
     * cycle column by the SIGN of the amount -- so asserting only the balance would leave the column
     * choice unexercised.</p>
     *
     * <p>Assumptions: the category balance is asserted to have taken the CREATE arm, because this
     * account has no prior row for the type and category pair. That is the branch
     * {@code 2700-A-CREATE} of the reference, and the two arms are separately observable here rather
     * than collapsed into an upsert whose path cannot be told apart.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an accepted posting commits its category balance, account and ledger row together")
    void anAcceptedPostingCommitsAllThreeWrites() {
        CategoryBalanceService.Outcome outcome = postOnce(TRANSACTION_ID);

        assertThat(outcome.arm())
                .as("no prior row exists for this account, type and category, so the create arm runs")
                .isEqualTo(CategoryBalanceService.Arm.CREATED);
        assertThat(outcome.balance()).isEqualByComparingTo(AMOUNT);

        this.entityManager.clear();

        Optional<Transaction> posted = this.ledger.findById(TRANSACTION_ID);
        assertThat(posted).as("the posted transaction is committed in the ledger schema").isPresent();
        assertThat(posted.orElseThrow().getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(posted.orElseThrow().getProcTs())
                .as("the processing stamp is the injected instant, never a clock read")
                .isEqualTo(PROCESSED_AT);

        Account stored = this.accounts.findByAccountId(ACCOUNT_ID).orElseThrow();
        assertThat(stored.getCurrBal())
                .as("the balance is committed in the ACCOUNT schema, in the same unit of work")
                .isEqualByComparingTo(OPENING_BALANCE.add(AMOUNT));
        assertThat(stored.getCurrCycCredit())
                .as("a positive amount accumulates into the cycle CREDIT total, as L543-L546 chooses")
                .isEqualByComparingTo(OPENING_CYCLE_CREDIT.add(AMOUNT));
        assertThat(stored.getCurrCycDebit())
                .as("the cycle debit total is untouched by a positive amount")
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(categoryBalanceOf()).isPresent();
        assertThat(categoryBalanceOf().orElseThrow().getBalance()).isEqualByComparingTo(AMOUNT);
    }

    /**
     * Confirms a refusal at the third write rolls back the first two, across both schemas.
     *
     * <p>Purpose: this is the property the whole harness exists for. The unit writes the category
     * balance and the account first and the posted transaction last, so a refusal at the last write is
     * the case in which a non-atomic implementation would leave the two earlier rows behind -- a posted
     * balance with no posted transaction, which is precisely the observable intermediate state the
     * migration plan rejected a saga in order to avoid.</p>
     *
     * <p>Assumptions: the refusal is provoked by a description one character wider than the column
     * admits, and the class block above records why that trigger rather than a duplicate identifier.
     * The account and the category balance are advanced first inside the same boundary, so their
     * rollback is what the assertions read.</p>
     *
     * <p>Assumptions: the surviving state is compared against the SEEDED values rather than merely
     * checked for the absence of the ledger row. An implementation that committed the account write
     * separately would still leave the ledger row absent, so absence alone would not distinguish the
     * two behaviours.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refusal at the ledger write rolls back the account and the category balance")
    void aRefusalAtTheLastWriteRollsBackTheEarlierTwo() {
        postOnce(TRANSACTION_ID);
        this.entityManager.clear();

        assertThatThrownBy(() -> postRefusedAtLedger("0000000000000043"))
                .as("the description column refuses a value wider than it admits")
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ledger.transactions")
                .rootCause()
                .hasMessageContaining("value too long");

        this.entityManager.clear();

        Account stored = this.accounts.findByAccountId(ACCOUNT_ID).orElseThrow();
        assertThat(stored.getCurrBal())
                .as("the ACCOUNT write of the refused unit was rolled back, so the balance still"
                        + " reflects exactly one accepted posting")
                .isEqualByComparingTo(OPENING_BALANCE.add(AMOUNT));
        assertThat(stored.getCurrCycCredit())
                .isEqualByComparingTo(OPENING_CYCLE_CREDIT.add(AMOUNT));

        assertThat(categoryBalanceOf().orElseThrow().getBalance())
                .as("the LEDGER category balance of the refused unit was rolled back too, so a"
                        + " double-counted amount would fail here")
                .isEqualByComparingTo(AMOUNT);

        assertThat(this.ledger.count())
                .as("the refused unit added no posted transaction")
                .isEqualTo(1L);
    }

    /**
     * Confirms an entirely refused first posting leaves no trace in either schema.
     *
     * <p>Assumptions: this is the same atomicity read from the empty side, and it is asserted because
     * the case above cannot distinguish a rollback from a write that never happened when a prior row
     * already exists. Here nothing precedes the refused unit, so a partially committed implementation
     * would leave an account balance advanced against no transaction at all -- the state a reader would
     * most likely mistake for a legitimate one, because every row present is individually valid.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a first posting refused at the ledger write leaves both schemas untouched")
    void aRefusedFirstPostingLeavesNoTrace() {
        assertThatThrownBy(() -> postRefusedAtLedger(TRANSACTION_ID))
                .isInstanceOf(PersistenceException.class)
                .rootCause()
                .as("the engine refuses the write with SQLSTATE 22001, string data right truncation")
                .hasMessageContaining("value too long");

        this.entityManager.clear();

        Account stored = this.accounts.findByAccountId(ACCOUNT_ID).orElseThrow();
        assertThat(stored.getCurrBal())
                .as("the account is exactly as seeded, so no half of the unit survived")
                .isEqualByComparingTo(OPENING_BALANCE);
        assertThat(stored.getCurrCycCredit()).isEqualByComparingTo(OPENING_CYCLE_CREDIT);
        assertThat(categoryBalanceOf())
                .as("no category balance row was created by the refused unit")
                .isEmpty();
        assertThat(this.ledger.count()).as("no posted transaction was written").isZero();
    }

    /**
     * Confirms the second posting for one key takes the UPDATE arm and accumulates.
     *
     * <p>Assumptions: both arms are asserted because the reference has two distinct paragraphs for
     * them -- {@code 2700-A-CREATE} when the category row is new and {@code 2700-B-UPDATE} when it
     * exists -- and the reference suite requires both to be exercised. Against a real composite primary
     * key the distinction is a keyed lookup that either finds a row or does not, which is exactly what
     * an in-memory substitute cannot reproduce faithfully.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a second posting for one category key updates rather than creating")
    void aSecondPostingForOneKeyTakesTheUpdateArm() {
        postOnce(TRANSACTION_ID);
        this.entityManager.clear();

        CategoryBalanceService.Outcome second = postOnce("0000000000000043");

        assertThat(second.arm())
                .as("the keyed lookup finds the row the first posting created, so the update arm runs")
                .isEqualTo(CategoryBalanceService.Arm.UPDATED);
        assertThat(second.balance())
                .as("the amount accumulates rather than replacing")
                .isEqualByComparingTo(AMOUNT.add(AMOUNT));

        this.entityManager.clear();
        assertThat(categoryBalanceOf().orElseThrow().getBalance())
                .isEqualByComparingTo(AMOUNT.add(AMOUNT));
        assertThat(this.ledger.count()).isEqualTo(2L);
    }

    /**
     * Confirms the cross-reference read that supplies the account key resolves against the real table.
     *
     * <p>Assumptions: the account identifier the posting keys the category balance with comes from the
     * CROSS-REFERENCE and not from the feed record, matching {@code app/cbl/CBTRN02C.cbl} L469 which
     * moves {@code XREF-ACCT-ID} into the key. The feed record carries a card number and no account at
     * all, so a reader who assumes otherwise will look for a field that does not exist -- which is why
     * the read is asserted here rather than taken for granted by the cases above.</p>
     *
     * <p>Assumptions: the fixed-width key is asserted to compare equal on the way out. The column is
     * {@code CHAR(16)}, so a value stored short would be blank-padded and a lookup by the unpadded form
     * would then miss; the assertion is what makes that a failure rather than an empty result.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cross-reference resolves the card to its account through the real table")
    void theCrossReferenceResolvesTheAccount() {
        Optional<CardXref> resolved = this.crossReferences.findByCardNum(CARD_NUM);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(resolved.orElseThrow().getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(this.crossReferences.findByCardNum("4000000000000099"))
                .as("a card the cross-reference does not carry misses, which is reject reason 100")
                .isEmpty();
    }

    // WHY : Assumptions: this case exists because the ordering and the window it asserts are the two
    //       properties a mocked repository cannot establish. BackupTransactionsJobTest stubs this finder
    //       and models its contract, which proves the JOB stages what the finder returns; only a real
    //       engine proves the FINDER returns it -- that the composite ORDER BY resolves in the declared
    //       sequence, and that a strict upper bound on a TIMESTAMP(6) excludes the following midnight.
    /**
     * The daily-subset finder orders by card then identifier and bounds its window half-open.
     *
     * <p>Pins {@code app/jcl/TRANREPT.jcl:37-55}, whose sort declares the single control field
     * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} at L46 and selects on
     * {@code TRAN-PROC-DT,305,10,CH} at L47-L48 -- the first ten characters of the processing
     * timestamp, so a date. The identifier tie-break the query adds is registered as
     * {@code D-DALY-CARD-TIE-BREAK}.</p>
     */
    @Test
    @DisplayName("the daily-subset finder orders by card then identifier over a half-open window")
    void theDailySubsetFinderOrdersAndBoundsAtTheDatabase() {
        LocalDateTime dayStart = PROCESSED_AT.toLocalDate().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1L);

        // WHY : Assumptions: the two out-of-window rows sit ONE MICROSECOND outside each edge, which is
        //       the resolution the column is declared at. A row a whole second or a whole day outside
        //       would be excluded by any comparison, including a wrong one, so it would assert nothing
        //       about the boundary this case exists for.
        this.transactionTemplate.executeWithoutResult(status -> {
            persistPosted("TXN0000000000004", "4000000000000099", dayStart.plusHours(3L));
            persistPosted("TXN0000000000002", CARD_NUM, dayStart);
            persistPosted("TXN0000000000003", "4000000000000099", dayStart.plusHours(1L));
            persistPosted("TXN0000000000001", CARD_NUM, dayEnd.minusNanos(1_000L));
            persistPosted("TXN0000000000000", CARD_NUM, dayStart.minusNanos(1_000L));
            persistPosted("TXN0000000000005", CARD_NUM, dayEnd);
            this.entityManager.flush();
        });

        List<String> ordered = this.transactionTemplate.execute(status ->
                this.ledger.streamProcessedInWindowOrderedByCard(dayStart, dayEnd)
                        .map(row -> row.getCardNum() + "/" + row.getTransactionId())
                        .toList());

        assertThat(ordered)
                .as("grouped by card ascending, then by identifier ascending inside each card")
                .containsExactly(
                        CARD_NUM + "/TXN0000000000001",
                        CARD_NUM + "/TXN0000000000002",
                        "4000000000000099/TXN0000000000003",
                        "4000000000000099/TXN0000000000004");
    }

    /**
     * Persists one posted transaction with an explicit card number and processing instant.
     *
     * <p>Assumptions: the row is built through the production mapper and then its two varying members
     * are set, rather than being assembled field by field. The mapper is what decides every other
     * column, so a row built any other way could satisfy this case while the posted rows the job writes
     * differed.</p>
     *
     * @param transactionId the identifier the posted row carries; must not be {@code null}
     * @param cardNum the sixteen-digit card number, the finder's leading sort key; must not be
     *     {@code null}
     * @param processedAt the processing instant the window is evaluated against; must not be
     *     {@code null}
     */
    private void persistPosted(String transactionId, String cardNum, LocalDateTime processedAt) {
        Transaction posted = DailyTransactionMapper.toPostedTransaction(
                feedRecord(transactionId, DESCRIPTION), processedAt);
        posted.setCardNum(cardNum);
        posted.setProcTs(processedAt);
        this.ledger.save(posted);
    }

    /**
     * Performs one posting unit of work in the order the production job performs it.
     *
     * <p>Assumptions: the three writes are issued through the REAL collaborators and in the real order
     * -- category balance, then account, then ledger -- because the order is what makes the rollback
     * cases meaningful. The flush inside the boundary is what forces the refusal to arise at the third
     * write rather than at commit, so the assertions can attribute it.</p>
     *
     * @param transactionId the identifier the posted row carries; must not be {@code null}
     * @return the accumulation outcome, naming which arm ran and the resulting balance, never
     *     {@code null}
     * @throws PersistenceException if the database refuses any of the three writes; the accepted
     *     description this overload supplies is inside the column's declared width, so no case that
     *     calls it provokes one
     */
    private CategoryBalanceService.Outcome postOnce(String transactionId) {
        return post(transactionId, DESCRIPTION);
    }

    /**
     * Performs one posting unit whose ledger write the description column refuses.
     *
     * <p>Assumptions: only the description differs from the accepted unit, so the rollback the caller
     * asserts is attributable to the third write and to nothing else about the unit.</p>
     *
     * @param transactionId the identifier the posted row would have carried; must not be {@code null}
     * @return never returns normally
     * @throws PersistenceException always, wrapping the engine's refusal of the ledger write as it
     *     flushes
     */
    private CategoryBalanceService.Outcome postRefusedAtLedger(String transactionId) {
        return post(transactionId, OVERLONG_DESCRIPTION);
    }

    /**
     * Performs one posting unit of work in the order the production job performs it.
     *
     * @param transactionId the identifier the posted row carries; must not be {@code null}
     * @param description the description the feed record carries; must not be {@code null}
     * @return the accumulation outcome, naming which arm ran and the resulting balance, never
     *     {@code null}
     * @throws PersistenceException if the database refuses any of the three writes, which two cases
     *     above provoke deliberately
     */
    private CategoryBalanceService.Outcome post(String transactionId, String description) {
        return this.transactionTemplate.execute(status -> {
            CardXref resolved = this.crossReferences.findByCardNum(CARD_NUM).orElseThrow();

            CategoryBalanceService.Outcome outcome = this.balances.accumulate(
                    new TransactionCategoryBalanceId(resolved.getAccountId(), TYPE_CD, CATEGORY_CD),
                    Money.of(AMOUNT));

            Account posting = this.accounts.findByAccountId(resolved.getAccountId()).orElseThrow();
            posting.setCurrBal(Money.of(posting.getCurrBal()).plus(Money.of(AMOUNT)).amount());
            posting.setCurrCycCredit(
                    Money.of(posting.getCurrCycCredit()).plus(Money.of(AMOUNT)).amount());
            this.accounts.save(posting);

            // WHY : Assumptions: the posted row is projected by the PRODUCTION mapper rather than
            //   assembled here, so the stamp, the field carry-over and the FILLER drop are the ones
            //   the job performs. Hand-building it would leave the projection unexercised and would
            //   let this class agree with itself about a shape production does not produce.
            this.ledger.save(DailyTransactionMapper.toPostedTransaction(
                    feedRecord(transactionId, description), PROCESSED_AT));

            this.entityManager.flush();
            return outcome;
        });
    }

    /**
     * Reads the one category balance this class writes, by its composite key.
     *
     * @return the stored row, or empty when no posting has created it; never {@code null}
     */
    private Optional<TransactionCategoryBalance> categoryBalanceOf() {
        return this.categoryBalances.findById(
                new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CD, CATEGORY_CD));
    }

    /**
     * Builds the account row every case starts from.
     *
     * <p>Assumptions: the three dates are literals well inside the reference's own range and the
     * expiration date is comfortably ahead of the processing stamp, so no case here trips the
     * expiration reject at {@code app/cbl/CBTRN02C.cbl} L414. This class asserts atomicity, not
     * validation, and a fixture that happened to be rejectable would make a failure ambiguous.</p>
     *
     * @return a transient account row at the seeded balances, never {@code null}
     */
    private static Account seedAccount() {
        return new Account(
                ACCOUNT_ID,
                "Y",
                OPENING_BALANCE,
                CREDIT_LIMIT,
                new BigDecimal("500.00"),
                LocalDate.of(2014, 11, 20),
                LocalDate.of(2026, 5, 20),
                LocalDate.of(2024, 5, 20),
                OPENING_CYCLE_CREDIT,
                BigDecimal.ZERO,
                "12345     ",
                "DEFAULT   ");
    }

    /**
     * Builds the feed record shape the production mapper posts from.
     *
     * <p>Assumptions: this exists so the class states the feed contract it is posting against even
     * though the cases drive the three writes directly. The width and order of the arguments are the
     * 350-byte {@code DALYTRAN-RECORD} of {@code app/cpy/CVTRA06Y.cpy}, and the processing stamp is
     * ABSENT on a feed row -- the column is nullable for exactly that reason, because posting is what
     * assigns one.</p>
     *
     * @param transactionId the feed record's identifier; must not be {@code null}
     * @param description the description the record carries, which one case deliberately makes wider
     *     than the column admits; must not be {@code null}
     * @return a transient feed row, never {@code null}
     */
    private static DailyTransaction feedRecord(String transactionId, String description) {
        return new DailyTransaction(transactionId, TYPE_CD, CATEGORY_CD, "POS       ",
                description, AMOUNT, 900000001L, "Fixture Merchant",
                "Fixture City", "12345     ", CARD_NUM, ORIGINATED_AT, null);
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the eight job definitions, the queue client and
     * the object-store client this module's own application class would register stay out of the
     * context. The one production service this class does need is declared as a bean instead, which
     * keeps the context to the persistence layer plus the accumulation rule under test.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class PostingPersistenceTestApplication {

        /**
         * Registers the production accumulation rule over the real repository.
         *
         * @param balances the category-balance repository the rule reads and writes through; must not
         *     be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        CategoryBalanceService categoryBalanceService(
                TransactionCategoryBalanceRepository balances) {
            return new CategoryBalanceService(balances);
        }
    }
}
