package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BatchRunSummary;
import com.carddemo.batch.mapper.AccountRecordMapper;
import com.carddemo.batch.mapper.DailyTransactionMapper;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.BatchRunRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.BatchStepLedgerWriter;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the read-only, validate-only contract of the pre-posting preflight pass.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this is the tier-2 case set for {@link PreflightDailyTransactionsJob}, the migration of
 * {@code app/cbl/CBTRN01C.cbl} and state 3 of the eleven-state {@code carddemo-daily-batch} chain the
 * migration plan defines in AAP 0.4.1.7 -- delivered at that same position, so the plan's number and
 * the number an operator reads in the console now agree. An earlier revision published the
 * whole-migration verification gate as a twelfth top-level state, which shifted this pass to state 4;
 * the gate now runs INSIDE the staging state, which restores the plan's topology without giving up the
 * verification AAP 0.9.2 and 0.7.7 require. It asserts what the pass DOES NOT do — it writes no row to
 * any table, it produces no rejected record, and it cannot report the soft-warn tier — alongside the
 * three input outcomes it reports, the one behavioural divergence it deliberately carries, and the
 * durable step record that makes a redriven state a no-op.</p>
 *
 * <p>Alternatives Considered: asserting the presence of output, which is how every other job in this
 * package is judged. There is no output to assert. The reference opens six datasets and every one of
 * them {@code OPEN INPUT} — {@code app/cbl/CBTRN01C.cbl:254} for the daily feed, {@code :273} for the
 * customer master, {@code :291} for the cross-reference, {@code :309} for the card master,
 * {@code :327} for the account master and {@code :345} for the transaction master — and the program
 * contains no {@code WRITE}, {@code REWRITE} or {@code DELETE} verb at all. Those six declarations and
 * that absence are the mechanical proof of the read-only contract, and they are far stronger evidence
 * than the program's own comments, so the case set is built around proving an absence rather than
 * matching an artifact. What that costs is that most assertions here are negative; what it buys is
 * coverage of the single worst plausible regression in this job, which is a future edit that
 * helpfully persists the validation outcome.</p>
 *
 * <h2>Why the soft-warn tier is unreachable, and why that is asserted here</h2>
 *
 * <p>Assumptions: {@code app/cbl/CBTRN01C.cbl} contains NO {@code RETURN-CODE} statement anywhere, no
 * reject stream and no counters — there is not one {@code ADD 1 TO} in the program. It therefore
 * either falls through {@code GOBACK} at {@code :197} having reported nothing, or it abends. The
 * migrated pass has the same two outcomes: the clean tier, or a hard failure at or above eight. The
 * parity oracle agrees from the other side, because {@code tests/integration/test_cbtrn01c_prepost.py}
 * asserts a return code of zero in all three of its scenarios, including the two in which a record
 * could not be resolved.</p>
 *
 * <p>Trade-offs: the numeric tier is asserted as the value the application returns and as the process
 * exit status the orchestrator reads, and never as a tolerance configured on a test runner. The graded
 * five-tier rubric that looks applicable is not: section 8 of {@code tests/README.md} defines it for
 * the parity oracle suite, which aggregates the worst code across three layers, whereas
 * {@link BatchReturnCode} models three outcomes only and Maven is binary. Nothing here imports,
 * emulates or aggregates that rubric. What is given up is the ability to report a partially successful
 * build; what is bought is that a real failure cannot be configured to read as an accepted warning.</p>
 *
 * <h2>Divergence D-7 is registered, not absorbed</h2>
 *
 * <p>Refactoring Rationale: the reference performs one cross-reference lookup PAST end of file, and
 * this pass does not reproduce it. Only the record display at {@code app/cbl/CBTRN01C.cbl:168} sits
 * inside the inner guard that {@code :167} opens and {@code :169} closes; the two {@code MOVE}
 * statements at {@code :170-171}, the lookup at {@code :172} and the guarded account read that follows
 * it through {@code :184} all sit outside that guard. So on the iteration whose read reaches end of
 * file, the reference moves a card number out of a record it did not read and looks it up again,
 * producing a spurious final diagnostic. The divergence is registered as
 * {@code D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE} in
 * {@code docs/architecture/cobol-to-service-traceability.md} and {@code app/cbl/CBTRN01C.cbl} is NOT
 * edited: the whole of {@code app/} is reference-only evidence, cited by path and line and never
 * modified. {@code D-7} above is the class-local label the job's own documentation gives this
 * difference, not a register heading -- the register's {@code D-7} is the online header clock, which
 * belongs to a different program, and citing the number here previously sent a reader to it.</p>
 *
 * <h2>What this case set does not assert</h2>
 *
 * <p>Assumptions: the division of labour with the sibling service tier is load-bearing, and asserting
 * one of its contracts a second time here would create two declarations of one rule. Settled there and
 * deliberately absent from this file: the reject-reason precedence and the reject description literals,
 * which {@code com.carddemo.batch.service.PostingValidationServiceTest} owns; the accrual arithmetic,
 * its truncation and its {@code DEFAULT} fallback, which
 * {@code com.carddemo.batch.service.InterestCalculationServiceTest} owns; the retained-generation count
 * and its memoisation, which {@code com.carddemo.batch.service.DatasetGenerationServiceTest} owns; and
 * the two counter lines and the soft-warn emission, which {@code PostTransactionsJobTest} owns because
 * {@code PostTransactionsJob} is the only job that can produce that tier. No transaction boundary is
 * asserted either, because this pass declares none.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Assumptions: every citation here is provenance. Nothing under {@code app/} or {@code tests/} is
 * read at run time and nothing under either is altered. Line numbers refer to the sources as
 * committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of
 * the statement.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is DISABLED for this context,
//       because otherwise it cannot start. application.yml declares an
//       `optional:aws-parameterstore:` location whose resolution builds an SSM client while
//       configuration is still loading, and on a build host with no region that client is built from
//       an unresolved placeholder and the context aborts before any assertion runs. The same property
//       is carried by all three of this module's container-backed cases for the same measured reason.
// WHY : Alternatives Considered: constructing the job directly with test doubles, which is the shape
//       five of the six sibling cases in this package take and which needs no container at all. It
//       was rejected for THIS file specifically: the contract this file owns is that a run leaves
//       every table unchanged, and with repositories replaced by doubles there are no tables, so the
//       headline assertion would pass whatever the pass wrote. Trade-offs: the cost is that this class
//       requires a container engine during Maven's test phase, where the sibling doubles-based cases
//       require none. That is accepted because an assertion of absence that cannot fail is worth
//       nothing, and the module's own test profile records at entry 4 of its register of omissions
//       that a case needing a real pass launches deliberately with the profile selected.
@SpringBootTest(
        classes = PreflightDailyTransactionsJobTest.PreflightPassTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
@SpringBatchTest
@DisplayName("the pre-posting preflight pass")
class PreflightDailyTransactionsJobTest {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: this is the digest every container-backed case in this build already pins, and
     * the version is restated in prose because a digest states nothing a reader recognises. Two cases
     * pinning two engines could disagree about one schema, and the disagreement would surface as
     * whichever of them ran second.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the foreign-schema harness the container runs at start.
     *
     * <p>Assumptions: the harness is REQUIRED rather than convenient here. The test profile sets
     * {@code create-schemas: false} and {@code ddl-auto: validate}, and this pass reads
     * {@code ledger.daily_transactions}, {@code account.card_xref} and {@code account.accounts} —
     * none of which belongs to the schema this module's own migration creates. Without the script the
     * context fails validation before a case runs.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The classpath prefix of this module's committed preflight fixture images.
     *
     * <p>Assumptions: the domain directory is spelled {@code preflight} while the parity oracle spells
     * the same three scenarios {@code prepost} under {@code tests/fixtures/prepost}, and neither name
     * is wrong. Section 4.2 of {@code services/batch-service/src/test/resources/fixtures/README.md}
     * records the correspondence so that nobody corrects one tree to the other: this tree matches the
     * job name a reader finds in the sources and in the state machine, and the oracle tree is
     * reference-only in any case.</p>
     */
    private static final String FIXTURE_ROOT = "fixtures/preflight/";

    /**
     * The account identifier the committed cross-reference resolves the feed record's card to.
     *
     * <p>Assumptions: the value is the oracle scenario's own, documented at
     * {@code tests/fixtures/prepost/happy_path/README.md} as {@code XREF-ACCT-ID = 00000000007}, and
     * it is also the identifier carried by this module's committed
     * {@code fixtures/preflight/unmatched_card/acctdata.txt}. Using the oracle's value rather than an
     * invented one keeps the two trees describing one scenario.</p>
     */
    private static final long RESOLVED_ACCOUNT_ID = 7L;

    /**
     * The customer identifier the committed cross-reference carries alongside the account.
     *
     * <p>Assumptions: documented as {@code XREF-CUST-ID = 000000007} in the same oracle README. This
     * pass never reads the field; it is supplied because the column is declared {@code NOT NULL} by
     * the harness at {@code account.card_xref}.</p>
     */
    private static final long RESOLVED_CUSTOMER_ID = 7L;

    /**
     * An account identifier the account master deliberately does not hold.
     *
     * <p>Assumptions: the oracle's {@code unmatched_account} scenario obtains the same outcome by
     * committing an account master holding {@code 00000000020} instead of the resolved
     * {@code 00000000007}. This value is used the other way round — the cross-reference resolves to an
     * identifier no row carries — because the outcome the pass reports is identical and this form
     * needs no second committed image.</p>
     */
    private static final long ABSENT_ACCOUNT_ID = 20L;

    /**
     * The business date every launch below supplies, in the separated ten-character layout.
     *
     * <p>Assumptions: a literal, never a clock read. Section 11 of {@code tests/README.md} records
     * that business dates are injected as parameters precisely so a rerun reproduces its output, and
     * this pass reads no date at all, so the value is required by the shared validator and consumed by
     * nothing.</p>
     */
    private static final String BUSINESS_DATE = "2022-07-18";

    /**
     * A second business date, used only where a launch must become a distinct job instance.
     *
     * <p>Assumptions: this is the compact ten-character layout {@code app/jcl/INTCALC.jcl:22} supplies
     * as {@code PARM='2022071800'}. Both committed layouts are exactly ten characters and the token is
     * an opaque passthrough, so either is admissible wherever a distinct identifying value is needed.
     * Its rendering is not asserted here; that belongs to {@code CalculateInterestJobTest}.</p>
     */
    private static final String ALTERNATE_BUSINESS_DATE = "2022071800";

    /**
     * The orchestrator execution identifier every launch below records ledger rows under.
     *
     * <p>Assumptions: the identifier is a non-identifying job parameter, so two launches may share it
     * while remaining two job instances. That is exactly the shape a redrive presents, and it is what
     * the ledger's idempotency assertion depends on.</p>
     */
    private static final String RUN_ID = "2022-07-18T02:00:00Z-nightly";

    /** The clean tier of the condition-code rubric, which is the only tier this pass can report. */
    private static final short RETURN_CODE_CLEAN = 0;

    /**
     * Lifts the rendered diagnostic out of the quoted field the pass logs it in.
     *
     * <p>Assumptions: the pass emits each diagnostic as the value of a {@code diagnostic="..."} field on
     * an otherwise structured warning line, and the reference's own literals contain no double quote, so
     * a non-greedy run up to the closing quote captures the whole line and nothing else.</p>
     */
    private static final Pattern DIAGNOSTIC_IN_LOG_LINE = Pattern.compile("diagnostic=\"([^\"]*)\"");

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} rather than the
     * deprecated {@code org.testcontainers.containers}, and the replacement is not generic, so the
     * declaration carries no type argument.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The launcher used to start the pass, contributed by the framework's batch test support. */
    @Autowired
    private JobOperatorTestUtils launcher;

    /** The framework's own metadata helper, used to clear job instances between cases. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The running context, searched for the job bean the way the module's entry point searches it. */
    @Autowired
    private ApplicationContext context;

    /** The durable step ledger table, read to assert the redrive record and its uniqueness. */
    @Autowired
    private BatchRunRepository stepLedgerRows;

    /**
     * The cross-reference the pass resolves a card number through, wrapped so lookups are countable.
     *
     * <p>Assumptions: a spy rather than a mock, so the real query still runs against the container and
     * the pass reaches its genuine outcomes. Counting the calls is the direct way to express divergence
     * D-7, because the lookup count IS the number of records the pass inspected: the reference performs
     * one more than that.</p>
     */
    @MockitoSpyBean
    private CardXrefRepository crossReferences;

    /**
     * The account master the pass reads for a resolved card, wrapped so the read can be proven absent.
     *
     * <p>Assumptions: the reference guards the account read with {@code IF WS-XREF-READ-STATUS = 0} at
     * {@code app/cbl/CBTRN01C.cbl:173}, so an unresolvable card must skip it entirely. A skip is only
     * observable as a call that did not happen, which a spy can show and a table cannot.</p>
     */
    @MockitoSpyBean
    private AccountRepository accounts;

    /**
     * The feed the pass walks, wrapped so the cursor each page was requested from can be read back.
     *
     * <p>Assumptions: a SPY and not a mock, so the walk still reads the rows the container holds and the
     * arrangement stays what the other cases seed with SQL. What the spy adds is the argument the pass
     * passed as its continuation ordinal on each read, which is the only place the keyset cursor is
     * observable from -- the pass reports its totals and not its cursor, so a walk that reset the cursor
     * and a walk that advanced it correctly are indistinguishable from the outside.</p>
     */
    @MockitoSpyBean
    private DailyTransactionRepository feed;

    /**
     * The boundary every seeding statement below is issued inside.
     *
     * <p>Assumptions: a boundary is REQUIRED rather than convenient. This module pins
     * {@code spring.datasource.hikari.auto-commit: false} in both its base document and its test
     * profile, so a statement issued through a bare template outside a transaction is rolled back when
     * the pool reclaims the connection. Measured: every seeded row was invisible to the pass and to the
     * assertions that followed it before these statements were committed, and the failure read as an
     * empty feed rather than as an arrangement fault.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used to seed input rows and to snapshot tables no entity here writes. */
    private JdbcTemplate jdbc;

    /** The captured operational log of the pass, from which the two diagnostics are read. */
    private ListAppender<ILoggingEvent> capturedLog;

    /**
     * The execution the most recent launch produced, published here rather than returned.
     *
     * <p>Assumptions: a field rather than a return value, for the reason recorded on
     * {@link #runPass(String)} — the batch test support's job-scope listener adopts any test-class method
     * returning a job execution as its own context factory and calls it with no arguments.</p>
     */
    private JobExecution execution;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the two migration credentials are registered as well and are not redundant
    //       with the datasource pair. The base document binds them to placeholders carrying no
    //       fallback, and Boot reads those keys only when no connection-details bean supplies them
    //       instead, which is this module's case because the annotation contributing such a bean lives
    //       in an artifact this POM does not declare. Leaving them unregistered aborts the context on
    //       an unresolved placeholder before any migration runs.
    // WHY : Alternatives Considered: a `jdbc:tc:postgresql:...?TC_INITSCRIPT=` URL declared once in the
    //       shared test profile, which would start the container and run the harness from that file
    //       alone. Entry 2 of that profile's register of deliberate omissions already rejected it,
    //       because a dynamically registered container overrides the URL in every case that has one and
    //       the declaration would then silently start a container for any future context-only case that
    //       wanted none. Following the established mechanism keeps one container lifecycle per class.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties every table a case touches, clears job metadata, and starts capturing the pass's log.
     *
     * <p>Assumptions: the tables are emptied rather than each case being wrapped in a rolled-back
     * transaction. The pass runs inside its own step transaction and commits, so an outer rollback
     * would discard the very state a snapshot comparison is taken across; and the ledger's uniqueness
     * constraint is asserted against committed rows.</p>
     *
     * <p>Assumptions: job instances are cleared too, because the business date is an IDENTIFYING job
     * parameter. Without the clear, the second case to launch with the same date would be refused as
     * an already-complete instance rather than reaching its own subject.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates, wrapped here for
     *     the seeding and snapshot statements; must not be {@code null}
     */
    @BeforeEach
    void resetStateAndCaptureLog(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.execution = null;
        this.jobRepositoryTestUtils.removeJobExecutions();
        this.stepLedgerRows.deleteAllInBatch();
        commit("DELETE FROM ledger.daily_transactions");
        commit("DELETE FROM ledger.transactions");
        commit("DELETE FROM ledger.transaction_rejects");
        commit("DELETE FROM ledger.transaction_category_balances");
        commit("DELETE FROM account.card_xref");
        commit("DELETE FROM account.accounts");

        // WHY : Assumptions: the appender is attached to the job's OWN logger rather than to the root,
        //       so an absence assertion cannot be defeated by an unrelated framework warning arriving
        //       on the same run. The two diagnostics this pass emits are the reference's entire failure
        //       output and both are emitted through this one category.
        this.capturedLog = new ListAppender<>();
        this.capturedLog.start();
        preflightLogger().addAppender(this.capturedLog);
    }

    /**
     * Detaches the log appender so it cannot outlive the case that installed it.
     *
     * <p>Assumptions: the logger is a process-wide singleton, so an appender left attached would
     * accumulate every subsequent case's events and turn a later absence assertion into a failure
     * whose cause is in a different method.</p>
     */
    @AfterEach
    void detachLog() {
        preflightLogger().detachAppender(this.capturedLog);
        this.capturedLog.stop();
    }

    /**
     * Confirms the pass registers under the exact token the orchestrated state selects it by.
     *
     * <p>Pins the vocabulary entry {@code preflight-daily-transactions}, which
     * {@code com.carddemo.batch.dto.BatchJobName} declares and which AAP 0.4.1.7 names as state 3 of
     * the nightly chain. A token differing by one character compiles, deploys and then fails inside the
     * state machine with an unresolved-job error, so the agreement is worth deciding at build time.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("register under the token the orchestrated state selects the pass by")
    void theRegisteredNameIsTheOrchestrationToken() {
        // WHY : Assumptions: the bean set is searched the way the module's entry point searches it, at
        //       BatchApplication.java:1176, which iterates the Job beans and compares each bean's own
        //       getName() against the argument. That method's own reasoning records why: the framework
        //       publishes a job operator as a bean but publishes no job registry as one, and the
        //       registry the operator holds is populated from exactly these beans. Reaching past the
        //       bean set to construct the job directly would assert a name no orchestrator can reach.
        List<Job> registered = List.copyOf(this.context.getBeansOfType(Job.class).values());

        assertThat(registered)
                .as("this context carries exactly the pass under test, so the launcher has one subject")
                .hasSize(1);
        assertThat(registered.getFirst().getName())
                .as("the registered name is an orchestration contract, not an internal label")
                .isEqualTo(BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS.token())
                .isEqualTo(PreflightDailyTransactionsJob.JOB_NAME)
                .isEqualTo("preflight-daily-transactions");
        assertThat(this.launcher.getJob().getName())
                .as("the launcher resolves the same job the entry point would resolve")
                .isEqualTo(PreflightDailyTransactionsJob.JOB_NAME);
    }

    /**
     * Confirms a completed pass leaves every table it could have written byte for byte unchanged.
     *
     * <p>This is the contract this file exists for. It pins the six {@code OPEN INPUT} declarations at
     * {@code app/cbl/CBTRN01C.cbl:254}, {@code :273}, {@code :291}, {@code :309}, {@code :327} and
     * {@code :345}, together with the total absence of a {@code WRITE}, {@code REWRITE} or
     * {@code DELETE} verb anywhere in the program.</p>
     *
     * <p>Assumptions: the four tables snapshotted are the ones a pre-posting pass could plausibly be
     * made to write — the posted ledger, the category balances, the reject stream and the account
     * master whose balance posting mutates. The account master is compared as full rows rather than as
     * a count, because the regression worth catching there is a mutation rather than an insertion.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("write no row and mutate no row, because every dataset is opened for input")
    void aCompletedPassLeavesEveryTableUnchanged() throws Exception {
        seedFeedFromFixture("happy_path", 1L);
        seedCrossReference(cardNumberOf("happy_path"), RESOLVED_ACCOUNT_ID);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        List<Map<String, Object>> accountsBefore = snapshotOfAccounts();

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rowCountOf("ledger.transactions"))
                .as("the pass posts nothing; posting is a later state of the chain")
                .isZero();
        assertThat(rowCountOf("ledger.transaction_category_balances"))
                .as("no balance is created or updated, because no balance is touched at all")
                .isZero();
        assertThat(rowCountOf("ledger.transaction_rejects"))
                .as("the reference declares no reject file descriptor for this program")
                .isZero();
        assertThat(snapshotOfAccounts())
                .as("the account master is read for existence only and is never rewritten")
                .isEqualTo(accountsBefore);
        assertThat(rowCountOf("ledger.daily_transactions"))
                .as("the feed itself is input, so the pass neither consumes nor stamps its rows")
                .isOne();
    }

    /**
     * Confirms an unresolvable feed still produces no rejected record.
     *
     * <p>Pins the branch at {@code app/cbl/CBTRN01C.cbl:180-184}, whose entire effect is a
     * {@code DISPLAY}. The contrast with the posting pass is the point and is asserted rather than
     * assumed: {@code CBTRN02C} writes the same condition to its {@code DALYREJS} stream as reject
     * reason 100, and it is that program's reject file — not this one's — that the
     * {@code ledger.transaction_rejects} table receives.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("report an unresolvable record as a diagnostic and never as a rejected row")
    void anUnresolvableFeedProducesNoRejectedRecord() throws Exception {
        seedFeedFromFixture("unmatched_card", 1L);
        seedFeedFromFixture("unmatched_account", 2L);
        seedCrossReference(cardNumberOf("unmatched_account"), ABSENT_ACCOUNT_ID);

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // WHY : Assumptions: both unresolvable conditions are present in one feed, so the assertion
        //       covers the two branches that a reject-writing pass would have rejected on. Seeding only
        //       one of them would leave the other branch able to write a row unobserved.
        assertThat(warningDiagnostics())
                .as("both unresolvable records are reported, once each")
                .hasSize(2);
        assertThat(rowCountOf("ledger.transaction_rejects"))
                .as("this pass has no reject stream; the reject contract belongs to the posting pass")
                .isZero();
    }

    /**
     * Confirms a feed carrying both failure conditions still reports the clean tier.
     *
     * <p>Pins the absence of any {@code RETURN-CODE} statement and of any counter in
     * {@code app/cbl/CBTRN01C.cbl}: the program has nothing with which to report a graded outcome, so
     * an unresolvable record is information and not a warning. The oracle confirms it from the other
     * side, asserting a return code of zero for both unresolvable scenarios in
     * {@code tests/integration/test_cbtrn01c_prepost.py}.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("report the clean tier even when no record in the feed resolves")
    void anEntirelyUnresolvableFeedStillReportsTheCleanTier() throws Exception {
        seedFeedFromFixture("unmatched_card", 1L);
        seedFeedFromFixture("unmatched_account", 2L);
        seedCrossReference(cardNumberOf("unmatched_account"), ABSENT_ACCOUNT_ID);

        runPass(BUSINESS_DATE);

        assertThat(BatchReturnCode.CLEAN.numericValue())
                .as("the clean tier is numerically zero, which is what a state machine reads")
                .isZero();
        // WHY : Trade-offs: the process exit status is asserted through the two inputs
        //       BatchApplication.java:1198-1216 reads rather than by calling that mapping directly,
        //       because exitStatusOf is package-private in com.carddemo.batch and this class belongs to
        //       com.carddemo.batch.job. Moving the class to reach it was rejected: the package charter
        //       fixes this file's package, and a case here that asserted the mapping FUNCTION would be
        //       asserting a different subject from the one it launched. Asserting the inputs is exact,
        //       because that method returns the hard-failure tier for any status other than completed,
        //       the soft-warn tier only for the warning exit code, and the clean tier otherwise.
        assertThat(this.execution.getStatus())
                .as("a status other than completed maps to the hard-failure exit tier")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.execution.getExitStatus().getExitCode())
                .as("the warning exit code is the only route to the soft-warn exit tier")
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(BatchApplication.EXIT_STATUS_CLEAN)
                .as("the two facts above therefore map to this exit status and to no other")
                .isZero();
    }

    /**
     * Confirms the transfer type itself refuses a soft-warn summary from this pass.
     *
     * <p>Pins the guard in {@code com.carddemo.batch.dto.BatchRunSummary}'s compact constructor, which
     * admits the soft-warn tier only for {@code post-transactions}. The reference reaches that tier at
     * {@code app/cbl/CBTRN02C.cbl:229-230}, where a non-zero reject count moves four into the return
     * code, and this program has neither the count nor the statement.</p>
     *
     * <p>Assumptions: the rejected-count biconditional that type also enforces is scoped to the posting
     * job alone, so it is NOT claimed here that a preflight summary carrying rejects would be refused.
     * What refuses it is the tier guard above, and what proves the count is zero is the empty reject
     * table asserted by the case above rather than a constructor.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("refuse a soft-warn summary and report zero rejected and zero written")
    void theWarnTierIsUnreportableAndNothingIsRejectedOrWritten() throws Exception {
        seedFeedFromFixture("happy_path", 1L);
        seedCrossReference(cardNumberOf("happy_path"), RESOLVED_ACCOUNT_ID);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        runPass(BUSINESS_DATE);
        long inspected = lookupCount();

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThatThrownBy(() -> summaryOf(BatchReturnCode.SOFT_WARN, inspected, 0L))
                .as("the warn tier is reportable only by the posting job, whichever counts accompany it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BatchJobName.POST_TRANSACTIONS.token());

        BatchRunSummary reported = summaryOf(BatchReturnCode.CLEAN, inspected, 0L);
        assertThat(reported.recordsRead())
                .as("every record the feed carried was inspected")
                .isEqualTo(1L);
        assertThat(reported.recordsWritten())
                .as("the pass writes nothing, which the unchanged tables above establish")
                .isZero();
        assertThat(reported.recordsRejected())
                .as("the pass rejects nothing, which the empty reject table establishes")
                .isZero();
    }

    /**
     * Confirms the fully matched path reports nothing at all.
     *
     * <p>Drives {@code fixtures/preflight/happy_path/dailytran.txt}, whose card resolves through the
     * cross-reference and whose resolved account exists. The reference reaches its account-missing
     * {@code DISPLAY} at {@code app/cbl/CBTRN01C.cbl:178} only when the account read failed, and its
     * card-unresolvable {@code DISPLAY} at {@code :181-183} only on the alternative branch, so a matched
     * record produces neither.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("report nothing when the card resolves and the account is found")
    void theFullyMatchedPathReportsNothing() throws Exception {
        seedFeedFromFixture("happy_path", 1L);
        seedCrossReference(cardNumberOf("happy_path"), RESOLVED_ACCOUNT_ID);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(warningDiagnostics())
                .as("a matched record reaches neither of the reference's two diagnostic branches")
                .isEmpty();
        verify(this.accounts).findByAccountId(RESOLVED_ACCOUNT_ID);
    }

    /**
     * Confirms an unresolvable card is reported and that the account read is skipped entirely.
     *
     * <p>Drives {@code fixtures/preflight/unmatched_card/dailytran.txt} with no cross-reference row for
     * its card, and additionally seeds the account master from that scenario's committed
     * {@code acctdata.txt} so that the account which WOULD have been reached genuinely exists. The skip
     * is therefore observable as a read that did not happen rather than as a read that found nothing.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("report an unresolvable card and skip the account read the reference guards")
    void anUnresolvableCardSkipsTheAccountRead() throws Exception {
        seedFeedFromFixture("unmatched_card", 1L);
        seedAccount(AccountRecordMapper.toEntity(fixtureImage("unmatched_card", "acctdata.txt")));

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // WHY : Trade-offs: the template is asserted verbatim from app/cbl/CBTRN01C.cbl:181-183 while
        //       both interpolated values are asserted REDACTED -- the card number to its last four
        //       digits and the transaction identifier to its declared width. The reference renders both
        //       in full; reproducing that would put a primary account number and the ledger's own
        //       primary key into a durable log, which the migration plan's data-exposure rule forbids,
        //       so the job redacts them and records that as the one place the verbatim-text rule is
        //       knowingly qualified. Note the middle fragment ends "ID-" with a hyphen and no trailing
        //       space, so the identifier position abuts it; inserting the space a reader expects would
        //       change published text.
        // WHY : ⚠️ Refactoring Rationale: this case asserted the transaction identifier PRESENT and in
        //       full, describing it as identity that names no one. It is the ledger's primary key, so
        //       the expectation encoded the disclosure rather than guarding against it; it now asserts
        //       the redaction, and the case below asserts the raw value appears nowhere in the run's log.
        assertThat(warningDiagnostics())
                .containsExactly("CARD NUMBER " + maskOf(cardNumberOf("unmatched_card"))
                        + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-"
                        + TRANSACTION_ID_REDACTION_IN_LOG);
        // WHY : Assumptions: the skip is pinned as the ABSENCE of the read, which is the reference's own
        //       short-circuit at app/cbl/CBTRN01C.cbl:173 -- the account read at :176 sits inside the
        //       block that line opens and the unresolvable-card branch at :180-184 is its alternative.
        //       Asserting only the message would pass even if the pass read the master first and
        //       discarded the answer, and the account identifier is not available on that branch at all.
        verify(this.accounts, never()).findByAccountId(anyLong());
        assertThat(rowCountOf("account.accounts"))
                .as("the master that was never read is nevertheless present, so the skip is genuine")
                .isOne();
    }

    /**
     * No line this pass logs carries the feed's raw transaction identifier, at any level.
     *
     * <p>⚠️ Purpose: this is the absence assertion that makes the redaction a property of the CLASS
     * rather than of the two statements a sibling case happens to inspect. The identifier is
     * {@code TRAN-ID}, the ledger's primary key, so a line carrying it links log storage to a posted
     * financial record; a case that only checked the diagnostic text would pass while a structured field
     * beside it published the same value, which is exactly the shape the defect had -- it appeared twice
     * per record, once in each.
     *
     * <p>⚠️ Assumptions: the job's logger is driven to {@code DEBUG} for this case and restored
     * afterwards, because the per-record trace is off by default and is the statement most likely to
     * regain the identifier -- it once rendered the feed entity, whose own diagnostic form names it. A
     * level that is off by default is not a control against an operator who raises it, so the assertion
     * has to see that statement.
     *
     * <p>⚠️ Assumptions: the expected value is read from the COMMITTED fixture rather than written down
     * here, so the case cannot drift into asserting the absence of a string the feed never contained --
     * which would pass for the wrong reason. It is asserted non-blank first for the same reason.
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("no logged line carries the feed's raw transaction identifier")
    void noLoggedLineCarriesTheRawTransactionIdentifier() throws Exception {
        seedFeedFromFixture("unmatched_card", 1L);
        seedAccount(AccountRecordMapper.toEntity(fixtureImage("unmatched_card", "acctdata.txt")));

        Logger jobLogger = preflightLogger();
        Level restored = jobLogger.getLevel();
        jobLogger.setLevel(Level.DEBUG);
        try {
            runPass(BUSINESS_DATE);
        } finally {
            jobLogger.setLevel(restored);
        }

        String rawIdentifier = transactionIdOf("unmatched_card").trim();
        assertThat(rawIdentifier)
                .as("the fixture must carry an identifier, or the absence below proves nothing")
                .isNotEmpty();
        assertThat(this.capturedLog.list)
                .as("the pass must have logged something, or the absence below proves nothing")
                .isNotEmpty();
        assertThat(this.capturedLog.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("no line at any level may carry the ledger key the feed record was read under")
                .noneMatch(line -> line.contains(rawIdentifier));
    }

    /**
     * Confirms a resolved card whose account is absent is reported with the identifier redacted.
     *
     * <p>Drives {@code fixtures/preflight/unmatched_account/dailytran.txt} with a cross-reference row
     * that resolves to an account the master does not hold, which is the condition the reference reports
     * at {@code app/cbl/CBTRN01C.cbl:178} as {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'}.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("report a resolved card whose account master row is absent")
    void aResolvedCardWithNoAccountRowIsReported() throws Exception {
        seedFeedFromFixture("unmatched_account", 1L);
        seedCrossReference(cardNumberOf("unmatched_account"), ABSENT_ACCOUNT_ID);

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // WHY : Trade-offs: the two literal fragments are verbatim, including the trailing space of
        //       "ACCOUNT " and the leading space of " NOT FOUND", which the reference carries inside the
        //       literals because it concatenates three pieces with no separator of its own. The
        //       identifier position is asserted as eleven redaction characters rather than as the
        //       account number: the line is emitted at warning level and so reaches durable log storage,
        //       and eleven is the width ACCT-ID is declared at, PIC 9(11) at app/cpy/CVACT01Y.cpy:5, so
        //       a positional reader still finds the field at its declared width.
        assertThat(warningDiagnostics()).containsExactly("ACCOUNT *********** NOT FOUND");
        verify(this.accounts).findByAccountId(ABSENT_ACCOUNT_ID);
    }

    /**
     * Confirms the pass performs exactly one lookup per record and none past end of file.
     *
     * <p>Registers as {@code D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE}, the job's class-local
     * {@code D-7}. Three records are seeded and three lookups must follow.</p>
     *
     * <p>Refactoring Rationale: what is wrong with the reference is guard placement, not logic. The inner
     * test at {@code app/cbl/CBTRN01C.cbl:167} closes with its own {@code END-IF} at {@code :169} and so
     * guards ONLY the record display at {@code :168}; the two {@code MOVE} statements at {@code :170-171}
     * and the cross-reference lookup at {@code :172} sit outside it. On the iteration whose read at
     * {@code :166} reaches end of file and sets {@code END-OF-DAILY-TRANS-FILE} — declared at
     * {@code :146} under a name of its own, not the one the posting pass uses — the program therefore
     * moves a card number out of a record it did not read and performs one further lookup against the
     * stale record buffer, emitting a spurious trailing diagnostic when that stale card was unresolvable.
     * The migrated walk drives its inspection from the batch the query returned, which makes an
     * iteration with no record unrepresentable.</p>
     *
     * <p>Assumptions: the divergence is REGISTERED and not absorbed. It is recorded as
     * {@code D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and {@code app/cbl/CBTRN01C.cbl} is
     * NOT edited — the reference stays byte-identical because it is the behavioural oracle. No byte
     * comparison can observe the difference either, because {@code tests/golden/} holds no
     * {@code preflight} tree for a return count to be compared against.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("perform one lookup per record and none against a record it did not read")
    void exactlyOneLookupPerRecordAndNoneBeyondEndOfFile() throws Exception {
        seedFeedFromFixture("happy_path", 1L);
        seedFeedFromFixture("happy_path", 2L);
        seedFeedFromFixture("happy_path", 3L);
        seedCrossReference(cardNumberOf("happy_path"), RESOLVED_ACCOUNT_ID);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.crossReferences, times(3)).findByCardNum(any());
        assertThat(lookupCount())
                .as("three records inspected, not four; the reference would have inspected four")
                .isEqualTo(3L);
        assertThat(warningDiagnostics())
                .as("no trailing diagnostic follows the last real record")
                .isEmpty();
    }

    /**
     * The walk crosses the commit interval, inspecting every feed row exactly once and in key order.
     *
     * <p>Pins the continuation of the keyset walk at
     * {@code PreflightDailyTransactionsJob.inspectEveryFeedRecord}, which reads
     * {@code BatchConfig.CHUNK_SIZE} rows at a time and advances its cursor to the ordinal of the last
     * row it saw. One row MORE than the interval is seeded, so the pass has to make a second read that
     * returns a non-empty page and a third that returns nothing before it stops.</p>
     *
     * <p>Refactoring Rationale: every other case here seeds at most three rows, so the loop's
     * continuation and its cursor arithmetic were reached by no case at all. Two opposite defects hide
     * below the interval and both are severe: a walk that failed to advance its cursor would re-read
     * page one forever, and one that advanced past the page's last row would silently drop a row per
     * page. Neither is visible in a one-page pass, which is why the boundary is crossed here against the
     * real database rather than argued about.</p>
     *
     * <p>Assumptions: each seeded row carries a DISTINCT card number, and the distinctness is what makes
     * the claim provable. The pass performs one cross-reference lookup per row, so the sequence of card
     * numbers the spy recorded is the sequence of rows the walk visited -- which shows exactly-once and
     * key ORDER together. Seeding one card on every row would make the same total pass for a walk that
     * read the first row a hundred and one times.</p>
     *
     * <p>Assumptions: the cursor positions are asserted as an ORDERED list, not counted. The sequence is
     * the property: the walk must open before the first ordinal, resume from the last ordinal of page one
     * and then from the last ordinal of page two. A count of three reads would accept a walk that asked
     * from zero every time.</p>
     *
     * <p>Assumptions: the read-only contract is re-asserted at this size rather than assumed from the
     * one-row case, because the second page is where a pass that accumulated something per page would
     * first write it.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("cross the commit interval, inspecting every feed row exactly once in key order")
    void theWalkCrossesTheCommitIntervalInspectingEveryRowExactlyOnce() throws Exception {
        int seeded = BatchConfig.CHUNK_SIZE + 1;
        List<String> cardNumbers = seedDistinctFeedRows(seeded);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(cursorsRequested())
                .as("the walk opens before the first ordinal and resumes from each page's last row")
                .containsExactly(0L, (long) BatchConfig.CHUNK_SIZE, (long) seeded);
        assertThat(cardNumbersLookedUp())
                .as("one lookup per seeded row, in ingestion order, none repeated and none missed")
                .containsExactlyElementsOf(cardNumbers);
        assertThat(lookupCount())
                .as("%d rows inspected across two pages", seeded)
                .isEqualTo(seeded);
        assertThat(warningDiagnostics())
                .as("every seeded row resolves, so neither diagnostic branch is reached")
                .isEmpty();
        assertThat(completedEvent())
                .as("the closing event totals every row both pages carried")
                .contains("read=" + seeded, "unresolvedCards=0", "unresolvedAccounts=0");
        assertThat(rowCountOf("ledger.transactions"))
                .as("a second page changes nothing about the pass writing no posted row")
                .isZero();
        assertThat(rowCountOf("ledger.daily_transactions"))
                .as("the feed is input at every size, so no row is consumed or stamped")
                .isEqualTo(seeded);
    }

    /**
     * An initially empty feed inspects nothing, reports nothing, and still records its step.
     *
     * <p>Pins the first read of the walk against an empty table, which is the case
     * {@code app/cbl/CBTRN01C.cbl} reaches when its first {@code READ} sets end-of-file: the program
     * opens its files, reads once, falls straight through its {@code PERFORM UNTIL} and closes. Nothing
     * is displayed because both diagnostic branches sit inside the loop body.</p>
     *
     * <p>Refactoring Rationale: an empty feed was covered only implicitly, by cases that seeded rows and
     * asserted the tables those rows did not change. Implicit is not enough here, because the empty feed
     * is the shape a real nightly run takes whenever the extract is empty, and two of the ways it could
     * fail are silent: a walk that treated an empty first page as an error would fail a legitimate
     * night, and one that skipped its ledger write when it had read nothing would leave a redrive unable
     * to tell that night's step from one that never ran.</p>
     *
     * <p>Assumptions: the ledger row is asserted PRESENT and clean, which is the half of the claim a
     * count of zero lookups cannot make. The pass grading nothing is not the same as the pass having
     * nothing to record: the durable row is what the orchestrator's redrive decision reads, so an empty
     * night must still leave one.</p>
     *
     * <p>Assumptions: exactly ONE read is asserted, from before the first ordinal. A pass that read a
     * second time after an empty page would be issuing a query whose answer cannot differ, which is
     * harmless at this size and is a wasted round trip per page on every night.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("inspect nothing and report nothing on an initially empty feed, and still record it")
    void anInitiallyEmptyFeedInspectsNothingAndStillRecordsItsStep() throws Exception {
        assertThat(rowCountOf("ledger.daily_transactions"))
                .as("the arrangement is the ABSENCE of an arrangement, so the emptiness is asserted"
                        + " rather than assumed from the reset having run")
                .isZero();

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(cursorsRequested())
                .as("one read, from before the first ordinal, and the empty page ends the walk")
                .containsExactly(0L);
        assertThat(lookupCount())
                .as("no row exists to look a card up for")
                .isZero();
        verify(this.accounts, never()).findByAccountId(anyLong());
        assertThat(warningDiagnostics())
                .as("both diagnostic branches sit inside the loop body the pass never entered")
                .isEmpty();
        assertThat(completedEvent())
                .as("the closing event reports three zeros rather than being withheld")
                .contains("read=0", "unresolvedCards=0", "unresolvedAccounts=0");

        Optional<BatchRun> recorded = this.stepLedgerRows.findByRunIdAndStepName(
                RUN_ID, PreflightDailyTransactionsJob.STEP_NAME);
        assertThat(recorded)
                .as("an empty night is still a step a redrive must be able to recognise")
                .isPresent();
        assertThat(recorded.orElseThrow().getStatus())
                .isEqualTo(BatchRun.BatchRunStatus.COMPLETED);
        assertThat(recorded.orElseThrow().getReturnCode())
                .as("nothing read is a clean pass, not a warning")
                .isEqualTo(RETURN_CODE_CLEAN);
        assertThat(rowCountOf("ledger.transactions")).isZero();
        assertThat(rowCountOf("ledger.transaction_rejects")).isZero();
        assertThat(rowCountOf("ledger.transaction_category_balances")).isZero();
    }

    /**
     * Confirms a completed pass writes one durable step row carrying the tier it graded.
     *
     * <p>Refactoring Rationale: this is a strict improvement over the reference and must not be
     * described as preserving its behaviour, because the reference has no checkpoint contract at all to
     * preserve. The only {@code RESTART=} anywhere in the thirty-eight files of {@code app/jcl/} is
     * commented out, at {@code app/jcl/DEFGDGD.jcl:2}, and there is no {@code CHKPT=} in any of them —
     * and this program has no driver among them in the first place. The durable row is what lets an
     * orchestrated redrive distinguish a step that already ran from one that did not.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("write one durable step row keyed on the run and the step name")
    void aCompletedPassWritesOneStepLedgerRow() throws Exception {
        seedFeedFromFixture("happy_path", 1L);
        seedCrossReference(cardNumberOf("happy_path"), RESOLVED_ACCOUNT_ID);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        runPass(BUSINESS_DATE);

        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Optional<BatchRun> recorded =
                this.stepLedgerRows.findByRunIdAndStepName(RUN_ID, PreflightDailyTransactionsJob.STEP_NAME);
        assertThat(recorded)
                .as("the row is addressed by the pair the redrive decision is made on")
                .isPresent();
        assertThat(recorded.orElseThrow().getStepName())
                // WHY : Assumptions: the step name is the job token with the shared suffix appended, so
                //       BatchJobName.forStepName can map a ledger row back to the job that wrote it.
                //       Spelling a literal here would create a second place the suffix is written down.
                .isEqualTo(BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS.token()
                        + BatchJobName.STEP_NAME_SUFFIX);
        assertThat(recorded.orElseThrow().getStatus()).isEqualTo(BatchRun.BatchRunStatus.COMPLETED);
        assertThat(recorded.orElseThrow().getReturnCode())
                .as("the row carries the tier the pass graded, which for this pass is always clean")
                .isEqualTo(RETURN_CODE_CLEAN);
    }

    /**
     * Confirms a second launch under the same run identifier is a no-op rather than a second pass.
     *
     * <p>Assumptions: the two launches differ in their business date and agree in their run identifier,
     * and both halves of that are deliberate. The business date is an IDENTIFYING job parameter, so a
     * second launch needs a different one to become a second job instance at all; the run identifier is
     * non-identifying and is the ledger's own key, so keeping it constant is what presents the second
     * launch as the redrive of one orchestrated execution. Both committed ten-character layouts are
     * admissible for the identifying half because the token is an opaque passthrough.</p>
     *
     * <p>Assumptions: the pass being a no-op is asserted as a lookup count that did not grow, not merely
     * as a row count that stayed at one. A second pass over the same feed would leave one ledger row
     * either way, because the uniqueness constraint admits one row per run and step, so the row count
     * alone cannot distinguish a skipped body from a repeated one.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("skip the pass on a redrive of a step the ledger already recorded as completed")
    void aRedriveOfACompletedStepRunsNothingAndDuplicatesNoRow() throws Exception {
        seedFeedFromFixture("happy_path", 1L);
        seedCrossReference(cardNumberOf("happy_path"), RESOLVED_ACCOUNT_ID);
        seedAccount(resolvableAccount(RESOLVED_ACCOUNT_ID));

        runPass(BUSINESS_DATE);
        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long afterFirst = lookupCount();

        runPass(ALTERNATE_BUSINESS_DATE);
        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(lookupCount())
                .as("the redriven step inspected nothing, because the ledger recorded it complete")
                .isEqualTo(afterFirst);
        assertThat(this.stepLedgerRows.count())
                .as("one row per run and step; a second insert is refused by uq_batch_run_run_step")
                .isOne();
    }

    /**
     * Confirms the business date is required by the shared validator and consumed by nothing here.
     *
     * <p>Assumptions: the parameter is required for ALL SEVEN jobs and not only for the one whose
     * reference step carries a parameter, and the point is asserted because the baseline evidence
     * suggests otherwise. {@code app/jcl/INTCALC.jcl:22} is the ONLY {@code PARM=} on any migrated batch
     * program and it drives the interest pass, yet
     * {@code com.carddemo.batch.config.BatchConfig#carddemoJobParametersValidator} declares the business
     * date and the run identifier as required for every job built with it — this pass included. A case
     * treating the option as optional here would contradict the validator it is driving.</p>
     *
     * <p>Assumptions: what IS true of this pass is that it reads no date. The reference declares no
     * linkage parameter — {@code app/cbl/CBTRN01C.cbl:154} is a bare {@code PROCEDURE DIVISION.} — so
     * the token cannot reach any decision it makes, and the second half of this case asserts exactly
     * that by running the same feed under the other committed layout and comparing the findings. The
     * token's own rendering is not asserted here; the sixteen-character generated identifier it feeds
     * belongs to {@code CalculateInterestJobTest} and to the interest service's own case set.</p>
     *
     * @throws Exception if the framework's own launch path raises, which this case does not provoke
     */
    @Test
    @DisplayName("require the business date, and reach the same findings whichever layout supplies it")
    void theBusinessDateIsRequiredAndTheFindingsDoNotDependOnIt() throws Exception {
        seedFeedFromFixture("unmatched_card", 1L);

        JobParameters withoutDate = new JobParametersBuilder()
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();
        assertThatThrownBy(() -> this.launcher.startJob(withoutDate))
                .as("the shared validator refuses the launch before a job instance is created")
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining(BatchApplication.BUSINESS_DATE_PARAMETER);

        runPass(BUSINESS_DATE);
        BatchStatus underSeparatedStatus = this.execution.getStatus();
        List<String> underSeparatedLayout = warningDiagnostics();
        this.stepLedgerRows.deleteAllInBatch();
        this.capturedLog.list.clear();

        runPass(ALTERNATE_BUSINESS_DATE);

        assertThat(underSeparatedStatus).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(warningDiagnostics())
                .as("the findings are a function of the feed alone, because the pass reads no date")
                .isEqualTo(underSeparatedLayout)
                .hasSize(1);
    }

    /**
     * Starts the pass once with both parameters the shared validator requires.
     *
     * <p>Assumptions: the business date is added as an IDENTIFYING parameter and the run identifier as a
     * non-identifying one, matching what the module's entry point builds. That split is what lets two
     * launches be two job instances while remaining one orchestrated execution to the ledger.</p>
     *
     * @param businessDate the ten-character business-date token to inject, carried through verbatim;
     *     must not be {@code null}
     * @throws Exception if the framework's own launch path raises, which includes the parameter refusal
     *     one case above provokes deliberately
     */
    // WHY : Refactoring Rationale: this method publishes the finished execution through the field above
    //       instead of RETURNING it, and the indirection is required rather than stylistic. The batch
    //       test support registers a job-scope listener that adopts ANY method of the test class whose
    //       return type is a job execution as its context factory, preferring one named
    //       getJobExecution and otherwise taking the first it finds, and then invokes that method with
    //       no arguments. A launcher taking a business date cannot satisfy that call, and the listener
    //       failed every case in this class with "No matching arguments found for method" before the
    //       return type was removed -- a failure raised during test-instance preparation, so it named
    //       the helper rather than any subject under assertion. With no method here returning that
    //       type the listener falls back to its own synthetic execution, which nothing in this class
    //       reads. Trade-offs: the cost is one field of shared state between a launch and the
    //       assertions that follow it; the alternative of wrapping the execution in a nested value type
    //       hides the same collision behind a type that exists only to evade a listener.
    private void runPass(String businessDate) throws Exception {
        this.execution = this.launcher.startJob(new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, businessDate, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters());
    }

    /**
     * Executes one statement inside its own committed transaction.
     *
     * <p>Assumptions: the commit is what makes the written row visible to the pass, which runs inside a
     * transaction of its own, and to the snapshot reads that follow it. The pool this module configures
     * hands out connections with auto-commit off, so a statement issued outside a boundary is discarded
     * rather than applied.</p>
     *
     * @param sql the statement to execute, with positional parameters, supplied as a literal by the
     *     caller; must not be {@code null}
     * @param arguments the positional parameter values, in declaration order
     */
    private void commit(String sql, Object... arguments) {
        this.transactionTemplate.executeWithoutResult(status -> this.jdbc.update(sql, arguments));
    }

    /**
     * Inserts one feed row decoded from a scenario's committed record image.
     *
     * <p>Assumptions: the row is written with plain SQL rather than through a repository, because the
     * feed entity is immutable and its repository interface declares finders only — the pass may read
     * the feed and nothing in this module may write it. The ingestion ordinal is supplied explicitly
     * because the harness declares the column {@code GENERATED BY DEFAULT}, precisely so a fixture may
     * fix the order a keyset walk will see.</p>
     *
     * @param scenario the fixture scenario directory name under {@link #FIXTURE_ROOT}; must be one of
     *     the three the fixture contract admits
     * @param ordinal the ingestion ordinal to store, which fixes this row's position in the walk
     */
    private void seedFeedFromFixture(String scenario, long ordinal) {
        DailyTransaction feedRecord = feedRecordOf(scenario);
        commit("INSERT INTO ledger.daily_transactions (ingest_seq, transaction_id, type_cd,"
                        + " category_cd, source, description, amount, merchant_id, merchant_name,"
                        + " merchant_city, merchant_zip, card_num, orig_ts, proc_ts)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ordinal, feedRecord.getTransactionId(), feedRecord.getTypeCd(),
                feedRecord.getCategoryCd(), feedRecord.getSource(), feedRecord.getDescription(),
                feedRecord.getAmount(), feedRecord.getMerchantId(), feedRecord.getMerchantName(),
                feedRecord.getMerchantCity(), feedRecord.getMerchantZip(), feedRecord.getCardNum(),
                feedRecord.getOrigTs(), feedRecord.getProcTs());
    }

    /**
     * Inserts one cross-reference row mapping a card number onto an account identifier.
     *
     * <p>Assumptions: the row is constructed rather than decoded from a committed image, and nothing is
     * lost by that. The pass reads exactly one field of this record, the account identifier, so no byte
     * geometry is at stake; and this module's fixture tree commits no cross-reference image for the
     * preflight domain, while the oracle's equivalent at {@code tests/fixtures/prepost} is
     * reference-only. The identifiers used are the oracle scenario's own.</p>
     *
     * @param cardNumber the sixteen-character card number to key the row on; must not be {@code null}
     * @param accountId the account identifier the lookup will resolve to, which may or may not exist in
     *     the account master depending on the scenario under assertion
     */
    private void seedCrossReference(String cardNumber, long accountId) {
        commit("INSERT INTO account.card_xref (card_num, customer_id, account_id)"
                        + " VALUES (?, ?, ?)",
                cardNumber, RESOLVED_CUSTOMER_ID, accountId);
    }

    /**
     * Inserts one account master row from an entity.
     *
     * <p>Assumptions: plain SQL is used rather than the repository's own save, because the account entity
     * carries an optimistic-locking version and an assigned identifier, so a save of a fresh instance is
     * a merge rather than an insert. Writing the row directly keeps the arrangement free of that
     * distinction, which no case here is about. The version column is left to the harness default.</p>
     *
     * @param account the account to store, whose twelve mapped properties are written as supplied; must
     *     not be {@code null}
     */
    private void seedAccount(Account account) {
        commit("INSERT INTO account.accounts (account_id, active_status, curr_bal,"
                        + " credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date,"
                        + " curr_cyc_credit, curr_cyc_debit, addr_zip, group_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                account.getAccountId(), account.getActiveStatus(), account.getCurrBal(),
                account.getCreditLimit(), account.getCashCreditLimit(), account.getOpenDate(),
                account.getExpirationDate(), account.getReissueDate(), account.getCurrCycCredit(),
                account.getCurrCycDebit(), account.getAddrZip(), account.getGroupId());
    }

    /**
     * Builds an account master row for an identifier the cross-reference resolves to.
     *
     * <p>Assumptions: the pass reads the EXISTENCE of this row and not one of its fields, so the values
     * carry no assertion and are chosen only to satisfy the harness declarations — an active status the
     * check constraint admits, money at scale 2 as the money contract requires everywhere in this
     * module, and three real calendar dates. Even in a row no arithmetic touches, an amount is a
     * {@code BigDecimal} rather than a floating-point literal, because the layering gate this module
     * runs forbids a primitive floating-point type anywhere in the money path.</p>
     *
     * @param accountId the identifier to key the row on, which the cross-reference will resolve to
     * @return the account, never {@code null}
     */
    private static Account resolvableAccount(long accountId) {
        return new Account(accountId, "Y", new BigDecimal("19.30"), new BigDecimal("206.50"),
                new BigDecimal("26.40"), LocalDate.of(2012, 10, 12), LocalDate.of(2024, 12, 13),
                LocalDate.of(2024, 12, 13), new BigDecimal("0.00"), new BigDecimal("0.00"),
                "98101", "DEFAULT");
    }

    /**
     * Decodes a scenario's committed feed image into an entity.
     *
     * <p>Assumptions: the production decoder is used rather than a reader written here, so the offsets
     * this arrangement depends on are the offsets the pass itself depends on. Section 5.3 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} records the two-source
     * cross-check behind those offsets, and a decoder written here would be a second place they could
     * drift.</p>
     *
     * @param scenario the fixture scenario directory name; must be one of the three admitted domains'
     *     scenarios
     * @return the decoded feed record, never {@code null}
     */
    private static DailyTransaction feedRecordOf(String scenario) {
        return DailyTransactionMapper.toEntity(fixtureImage(scenario, "dailytran.txt"));
    }

    /**
     * Reads the card number the scenario's committed feed image carries.
     *
     * @param scenario the fixture scenario directory name
     * @return the sixteen-character card number as committed, unmasked because it is the lookup key,
     *     never {@code null}
     */
    private static String cardNumberOf(String scenario) {
        return feedRecordOf(scenario).getCardNum();
    }

    /**
     * The redaction the job writes in place of a transaction identifier, at its declared width.
     *
     * <p>Assumptions: the literal is repeated here rather than read from the job, because a test that
     * imported the production constant would agree with it however it changed -- including a change back
     * to the identifier itself. Sixteen characters is {@code TRAN-ID}'s declared width.</p>
     */
    private static final String TRANSACTION_ID_REDACTION_IN_LOG = "****************";

    /**
     * Reads the transaction identifier the scenario's committed feed image carries.
     *
     * @param scenario the fixture scenario directory name
     * @return the sixteen-character identifier as committed, which the card diagnostic ends with, never
     *     {@code null}
     */
    private static String transactionIdOf(String scenario) {
        return feedRecordOf(scenario).getTransactionId();
    }

    /**
     * Reads one committed fixture image as bytes, with its line terminator removed.
     *
     * <p>Assumptions: the stream is read as BYTES and never as text. Section 3.3 of the fixture contract
     * makes the low-order byte of every money field a sign overpunch, so a character decode would be
     * free to alter it; and section 3.9 makes the trailing newline a determinism rule rather than part
     * of the record, so it is stripped here and the record's declared width is then checked by the
     * production decoder itself rather than by a second length constant written down here.</p>
     *
     * @param scenario the fixture scenario directory name under {@link #FIXTURE_ROOT}
     * @param fileName the image's file name within that directory, such as {@code dailytran.txt}
     * @return the record image with any trailing carriage return or line feed removed, never
     *     {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath or cannot be read,
     *     which means the fixture tree and this class disagree about what is committed
     */
    private static byte[] fixtureImage(String scenario, String fileName) {
        String resource = FIXTURE_ROOT + scenario + "/" + fileName;
        try (InputStream stream =
                PreflightDailyTransactionsJobTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the committed fixture " + resource
                        + " is not on the test classpath, so this case has no input to drive");
            }
            byte[] bytes = stream.readAllBytes();
            int length = bytes.length;
            while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
                length--;
            }
            byte[] image = new byte[length];
            System.arraycopy(bytes, 0, image, 0, length);
            return image;
        } catch (IOException unreadable) {
            throw new IllegalStateException("the committed fixture " + resource
                    + " could not be read as bytes", unreadable);
        }
    }

    /**
     * Renders a card number the way a log line is permitted to carry it.
     *
     * <p>Assumptions: four is the number of trailing digits the migration plan's masking rule admits, and
     * the expectation is derived from the committed card number rather than written as a literal so that
     * a fixture change cannot leave this class asserting a digit run no fixture holds.</p>
     *
     * @param cardNumber the full card number from the committed image; must be at least four characters
     * @return the last four characters, never {@code null}
     */
    private static String maskOf(String cardNumber) {
        return cardNumber.substring(cardNumber.length() - 4);
    }

    /**
     * Counts the rows one table holds.
     *
     * <p>Assumptions: the table name is interpolated, and every caller passes a literal declared in this
     * class, so no value from a fixture or a parameter file reaches the statement.</p>
     *
     * @param qualifiedTable the schema-qualified table name to count, supplied as a literal by the
     *     caller; must not be {@code null}
     * @return the row count, never negative
     */
    private long rowCountOf(String qualifiedTable) {
        Long count = this.jdbc.queryForObject("SELECT count(*) FROM " + qualifiedTable, Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Takes a comparable image of every account master row.
     *
     * <p>Assumptions: whole rows are compared rather than a count, because the regression this snapshot
     * exists to catch is a MUTATION rather than an insertion — a pre-posting pass that updated a balance
     * would leave the count unchanged. The ordering is explicit so two snapshots of one table are
     * comparable as lists.</p>
     *
     * @return one map per row, in account-identifier order, never {@code null}
     */
    private List<Map<String, Object>> snapshotOfAccounts() {
        return this.jdbc.queryForList("SELECT * FROM account.accounts ORDER BY account_id");
    }

    /**
     * Extracts the rendered diagnostic line from every warning the pass emitted.
     *
     * <p>Assumptions: the quoted diagnostic is read out of the FORMATTED message rather than out of the
     * argument array, because the rendered line is what an operator greps a container log for and is
     * therefore the artifact under assertion. Only warnings are collected: the two diagnostics are the
     * reference's entire failure output and both are emitted at that level, so an absence assertion
     * cannot be defeated by the pass's own informational banners.</p>
     *
     * @return the diagnostic lines in emission order, never {@code null}
     */
    private List<String> warningDiagnostics() {
        return this.capturedLog.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .map(DIAGNOSTIC_IN_LOG_LINE::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .toList();
    }

    /**
     * Seeds one feed row per requested ordinal, each carrying its own card number and identifier.
     *
     * <p>Assumptions: the rows are written in ONE batched transaction rather than one statement per row,
     * because a hundred and one separately committed statements is a hundred and one round trips against
     * a container on a shared runner and the arrangement is not what any case here is measuring.</p>
     *
     * <p>Assumptions: every row derives from the committed {@code happy_path} image and differs from it
     * in exactly two fields, the identifier and the card number, both derived from the ordinal. Deriving
     * rather than inventing keeps every other field -- the amount, the type and category codes, the two
     * timestamps -- at the values the fixture contract governs, so a row here is a committed row with a
     * distinct key rather than a synthetic record of this method's own design.</p>
     *
     * <p>Assumptions: one cross-reference row is written per card, all naming the SAME account, so the
     * account master needs one row and the resolved path is reached by every feed row. Giving each its
     * own account would test the account master's cardinality, which no case here is about.</p>
     *
     * @param rows the number of feed rows to seed, which become ordinals 1 through {@code rows}
     * @return the card numbers seeded, in ascending ordinal order, never {@code null}
     */
    private List<String> seedDistinctFeedRows(int rows) {
        DailyTransaction template = feedRecordOf("happy_path");
        String cardPrefix = template.getCardNum().substring(0, template.getCardNum().length() - 4);

        List<String> cardNumbers = new ArrayList<>();
        List<Object[]> feedArguments = new ArrayList<>();
        List<Object[]> crossReferenceArguments = new ArrayList<>();
        for (int ordinal = 1; ordinal <= rows; ordinal++) {
            String cardNumber = cardPrefix + String.format("%04d", ordinal);
            cardNumbers.add(cardNumber);
            feedArguments.add(new Object[] {(long) ordinal, String.format("%016d", ordinal),
                template.getTypeCd(), template.getCategoryCd(), template.getSource(),
                template.getDescription(), template.getAmount(), template.getMerchantId(),
                template.getMerchantName(), template.getMerchantCity(), template.getMerchantZip(),
                cardNumber, template.getOrigTs(), template.getProcTs()});
            crossReferenceArguments.add(
                    new Object[] {cardNumber, RESOLVED_CUSTOMER_ID, RESOLVED_ACCOUNT_ID});
        }

        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.batchUpdate("INSERT INTO ledger.daily_transactions (ingest_seq,"
                    + " transaction_id, type_cd, category_cd, source, description, amount,"
                    + " merchant_id, merchant_name, merchant_city, merchant_zip, card_num,"
                    + " orig_ts, proc_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    feedArguments);
            this.jdbc.batchUpdate("INSERT INTO account.card_xref (card_num, customer_id,"
                    + " account_id) VALUES (?, ?, ?)", crossReferenceArguments);
        });
        return cardNumbers;
    }

    /**
     * Reports the continuation ordinal the pass requested each page from, in request order.
     *
     * <p>Assumptions: the argument is read off the spy's recorded invocations rather than inferred from
     * the rows returned, because the cursor is what is under assertion and the rows would agree with a
     * cursor that had been recomputed from scratch. Only the keyset finder is read; the pass makes no
     * other call on this repository, and naming the method keeps that true if one is ever added.</p>
     *
     * @return the continuation ordinals in the order the pass asked for them, never {@code null}
     */
    private List<Long> cursorsRequested() {
        return mockingDetails(this.feed).getInvocations().stream()
                .filter(invocation -> "findByIngestSeqGreaterThanOrderByIngestSeqAsc"
                        .equals(invocation.getMethod().getName()))
                .map(invocation -> (Long) invocation.getArgument(0))
                .toList();
    }

    /**
     * Reports the card numbers the pass looked up, in lookup order.
     *
     * <p>Assumptions: the full number is read and compared rather than a masked form, because this is an
     * arrangement value flowing between two test-owned collections and never a rendering -- the masking
     * rule governs what a LOG line may carry, which the diagnostic assertions cover separately.</p>
     *
     * @return the card numbers in the order the pass resolved them, never {@code null}
     */
    private List<String> cardNumbersLookedUp() {
        return mockingDetails(this.crossReferences).getInvocations().stream()
                .filter(invocation -> "findByCardNum".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArgument(0))
                .toList();
    }

    /**
     * Reports the pass's closing event, whose three totals are the whole of its numeric output.
     *
     * <p>Assumptions: the formatted message is read rather than the pattern and its arguments, so what
     * is asserted is the line an operator reads. Reading the pattern would report the placeholders.</p>
     *
     * @return the formatted closing event, or an empty string when the pass emitted none
     */
    private String completedEvent() {
        return this.capturedLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("event=batch.preflight.completed"))
                .findFirst()
                .orElse("");
    }

    /**
     * Counts the cross-reference lookups the pass performed.
     *
     * <p>Assumptions: the count is read from the spy's recorded invocations rather than expressed as a
     * Mockito verification, so a case can compare two counts taken at two moments — which is what the
     * redrive case needs and what a verification cannot express.</p>
     *
     * @return the number of card-number lookups issued since the spy was last reset, never negative
     */
    private long lookupCount() {
        return mockingDetails(this.crossReferences).getInvocations().stream()
                .filter(invocation -> "findByCardNum".equals(invocation.getMethod().getName()))
                .count();
    }

    /**
     * Builds the summary this pass's outcome would be reported as.
     *
     * @param tier the graded tier to report, which for this pass is only ever the clean one outside the
     *     refusal case below; must not be {@code null}
     * @param read the number of records the pass inspected
     * @param rejected the number of records the pass rejected, which is zero for this pass
     * @return the summary, never {@code null}
     * @throws IllegalArgumentException if the transfer type refuses the combination, which one case
     *     provokes deliberately by offering the soft-warn tier
     */
    private static BatchRunSummary summaryOf(BatchReturnCode tier, long read, long rejected) {
        return new BatchRunSummary(RUN_ID, PreflightDailyTransactionsJob.STEP_NAME,
                BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS, tier, read, 0L, rejected, 0L, Map.of());
    }

    /**
     * Resolves the logger the pass reports its findings through.
     *
     * @return the job's own logger, cast to the binding's type so an appender can be attached, never
     *     {@code null}
     */
    private static Logger preflightLogger() {
        return (Logger) LoggerFactory.getLogger(PreflightDailyTransactionsJob.class);
    }

    /**
     * The minimal Spring Boot configuration this class runs the pass against.
     *
     * <p>Assumptions: no component scan is declared, and the omission is deliberate. This module's own
     * application class scans the whole bounded context and would register all seven job definitions, a
     * queue client and an object-store client — which would leave the launcher with seven candidate jobs
     * instead of one and give six further ways for this class to fail for an unrelated reason. Naming the
     * two persistence packages and importing the four types the pass actually needs leaves the
     * framework's own auto-configuration to build the pool, the migration and the job repository from the
     * properties the container registered.</p>
     *
     * <p>Assumptions: the durable ledger is imported as its two collaborating types rather than mocked,
     * because the redrive assertion is about a committed row and its uniqueness constraint. The failure
     * reporter it also accepts is left absent, which is a supported state: it is injected as an optional
     * and the test profile sets no messaging property at all, so no queue client and no emulator is
     * required to start this context.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    // WHY : Assumptions: DailyFeedWatermarkService is imported EXPLICITLY, like every other
    //       collaborator here, rather than reached by a component scan. This configuration names its
    //       imports one at a time so that the context holds one job definition and not seven, and the
    //       watermark service is a constructor dependency of the pass under test -- so an omission is
    //       a context that fails to start rather than a pass that quietly reads no position.
    // WHY : Assumptions: the REAL service is used, over the real repository the JPA scan above
    //       registers, against the real table V2__batch_feed_watermark.sql creates in the container.
    //       A mock would let this class assert the pass ran without asserting that the window it read
    //       was the window the database holds, which is the only property worth having here.
    @Import({BatchConfig.class, PreflightDailyTransactionsJob.class, BatchStepLedger.class,
            BatchStepLedgerWriter.class, DailyFeedWatermarkService.class})
    static class PreflightPassTestApplication {
    }
}
