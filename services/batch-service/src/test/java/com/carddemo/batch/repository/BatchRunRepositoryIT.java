package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.domain.BatchRun.BatchRunStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Holds {@code batch.batch_run} -- the durable step ledger this module owns -- against the schema its
 * own production migration creates.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this table is the target's replacement for a restart contract the baseline never had.
 * The only {@code RESTART=} anywhere in the reference is COMMENTED OUT, at
 * {@code app/jcl/DEFGDGD.jcl} L2, and no {@code CHKPT=} appears in the tree at all, so a rerun of a
 * mainframe step re-did its work. {@code batch.batch_run} makes a completed step a no-op on a redrive,
 * by carrying one row per {@code (run_id, step_name)} pair with the tier that attempt reached. Because
 * the mechanism is new rather than migrated, nothing in the reference can be compared against it and
 * the only available proof is this one: that the migration creates the table, that the constraints
 * which make a row meaningful actually fire, and that the two finders the ledger reads it through
 * resolve.</p>
 *
 * <p>Refactoring Rationale: every assertion below was previously unreachable. Beside the surrogate
 * primary key, {@code V1__batch.sql} declares five REFUSABLE constraints on this table -- one unique
 * pair and four checks -- and a check constraint that is never violated in a test is indistinguishable
 * from a check constraint that was mistyped: PostgreSQL accepts a predicate that can never be false as
 * readily as one that can, and Hibernate validates none of them. So each of the five is asserted by
 * writing the row it must refuse.</p>
 *
 * <h2>What is asserted and why each one needs a database</h2>
 *
 * <p>Assumptions: the migration is confirmed through Flyway's OWN history table rather than by
 * observing that a query happened to work. A query succeeding proves a table exists; it does not
 * distinguish a table Flyway created from one some other mechanism created, and the distinction is the
 * point -- this module's schema is supposed to arrive from {@code db/migration} and from nowhere
 * else.</p>
 *
 * <p>Assumptions: the OWNER of the {@code batch} schema is asserted, and it is not decoration. Every
 * {@code ALTER DEFAULT PRIVILEGES FOR ROLE} clause in {@code data-migration/sql/V0__schemas_and_roles.sql}
 * is keyed on the role that CREATES an object and is inert otherwise, so a deployment in which Flyway
 * created objects as the connecting user rather than as {@code carddemo_batch_owner} would grant the
 * runtime role nothing and fail at the first query with 42501. That is a configuration property, it is
 * invisible to a passing query, and the container is the only place it can be observed.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is DISABLED for this context,
//       because otherwise it cannot start at all. application.yml declares
//       `optional:aws-parameterstore:/carddemo/${CARDDEMO_ENVIRONMENT:local}/batch/`, and resolving it
//       builds an SSM client while configuration is still loading; on a build host with no AWS_REGION
//       that client is built from the unresolved placeholder and the context aborts with
//       "invalid URI: https://ssm.%24%7BAWS_REGION%7D.amazonaws.com". Measured, not inferred: every
//       case in this class failed that way before the property below was set.
// WHY : Assumptions: the flag stops the LOAD rather than the resolution, and that is precisely why it
//       is sufficient. AbstractAwsConfigDataLocationResolver#isResolvable tests the location PREFIX
//       only -- verified by disassembling the pinned 4.1.0 artifact -- so nothing keeps the location
//       out of the resolved list; but ParameterStoreConfigDataLoader#load consults the same flag and
//       returns an empty contribution before an SsmClient is built, and the client is what fails. The
//       distinction is worth stating because a reader who expects a disabled starter to be absent from
//       the resolver's list will find it there and conclude the flag is inert.
// WHY : Alternatives Considered: (a) narrowing `spring.config.import` to the classpath document alone,
//       tried both as an inlined property and as a command-line argument, and rejected because NEITHER
//       worked -- an import list contributes locations rather than replacing the non-profile document's,
//       so the parameter-store location survived the override and the context aborted unchanged.
//       (b) Supplying a real region so the client builds and letting `optional:` swallow the lookup,
//       rejected because the loader would then issue a genuine GetParametersByPath against the public
//       endpoint -- a network call from a persistence assertion, slow where it is reachable and a
//       timeout where it is not.
// WHY : Trade-offs: the annotation's `properties` attribute carries it rather than `args`, and both
//       forms were measured to work: an inlined test property is added to the environment before
//       config-data processing begins, so the ordering that defeated (a) does not arise for a flag the
//       loader reads. The attribute is preferred because it states a property of the test context
//       rather than simulating a command line this application is never launched with.
@SpringBootTest(
        classes = BatchRunRepositoryIT.StepLedgerPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class BatchRunRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine, the major line the
     * deployed cluster runs.
     *
     * <p>Assumptions: the version is recorded in prose because a digest states nothing a reader
     * recognises, and the two must be changed together.</p>
     */
    // WHY : Alternatives Considered: the readable tags postgres:17-alpine and postgres:17.10-alpine.
    //       Both are mutable -- the publisher moves the first to each new patch release and may
    //       rebuild the second on a new base layer -- and the properties asserted here are engine
    //       behaviours, so either could change under an unchanged assertion and leave a failure
    //       unattributable. A digest resolves to the same bytes forever.
    // WHY : Assumptions: this is the digest every sibling integration test in this build already
    //       pins. Two tests pinning two engines could disagree about one schema, and the
    //       disagreement would surface as whichever of them ran second.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the foreign-schema harness the container runs at start.
     *
     * <p>Assumptions: this class maps only {@code BatchRun}, which lives in the schema Flyway creates,
     * so it needs none of the harness's nine foreign tables. The script is supplied anyway, and
     * deliberately: the datasource pins {@code search_path} to {@code batch, ledger, account,
     * reference, card} on every connection, and this class asserts that the four foreign schemas the
     * other two integration tests depend on are present -- so a broken script reference is reported
     * HERE, by a named assertion, rather than as an undefined-table error inside an unrelated
     * test.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The NOLOGIN role the test profile's Flyway {@code init-sqls} creates and assumes.
     *
     * <p>Assumptions: the name is the deployed one, declared at
     * {@code data-migration/sql/V0__schemas_and_roles.sql} L713 as the authorization of the
     * {@code batch} schema. It is a role name and not a credential: the role is created NOLOGIN and
     * carries no password in either environment.</p>
     */
    private static final String MIGRATION_OWNER_ROLE = "carddemo_batch_owner";

    /** The run identifier every case in this class writes under. */
    private static final String RUN_ID = "2022-07-18T02:00:00Z-nightly";

    /** The step name the posting state of the orchestrated chain carries. */
    private static final String STEP_NAME = "PostTransactions";

    /**
     * The instant a step in this class opens at.
     *
     * <p>Assumptions: a literal, never a clock read, for the reason the package charter records: a
     * value compared against the current time passes for an unrelated reason and fails only when two
     * reads straddle a boundary. The date is the business date the reference's interest step is driven
     * with at {@code app/jcl/INTCALC.jcl} L22, so the two read as one run.</p>
     */
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2022, 7, 18, 2, 0, 0);

    /** The instant a step in this class finishes at, one minute after it opened. */
    private static final LocalDateTime FINISHED_AT = STARTED_AT.plusMinutes(1);

    /** The clean tier of the condition-code rubric. */
    private static final short RETURN_CODE_CLEAN = 0;

    /** The soft-warning tier, which a posting run reaching any reject reports. */
    private static final short RETURN_CODE_WARN = 4;

    /** The hard-failure tier the ledger records when a step body raises. */
    private static final short RETURN_CODE_FAIL = 8;

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} and not from
     * {@code org.testcontainers.containers}. Testcontainers 2.0.5 ships both and only the legacy
     * package is deprecated; the replacement is not generic, so the declaration carries no type
     * argument.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The ledger table under test, injected as the production repository interface. */
    @Autowired
    private BatchRunRepository repository;

    /** The persistence context, used only to flush and clear so every read is a real select. */
    @Autowired
    private EntityManager entityManager;

    /**
     * The transaction boundary every write below is issued inside.
     *
     * <p>Assumptions: a boundary is REQUIRED rather than convenient, because a repository call outside
     * one leaves nothing to flush -- the shared persistence context refuses {@code flush} with
     * {@code TransactionRequiredException} when no transaction is bound to the thread, which is how the
     * need for this collaborator was found. Each write is committed on its own so the read that follows
     * is a genuine select against a committed table rather than a read of an open transaction's own
     * buffer.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used for the catalog and history assertions no entity can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the two Flyway credentials are registered as well and are not redundant with
    //       the datasource pair. application.yml binds spring.flyway.user and spring.flyway.password
    //       to placeholders that carry no fallback, and Boot reads those keys only when no
    //       connection-details bean supplies them instead -- which is exactly this module's case,
    //       because the annotation that would contribute such a bean lives in an artifact this POM
    //       does not declare. Leaving them unregistered aborts the context on an unresolved
    //       placeholder before any migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the ledger table and opens a JDBC handle before each case.
     *
     * <p>Assumptions: the table is emptied rather than each case being wrapped in a rolled-back
     * transaction, and the difference is load-bearing here. Three cases below assert that the DATABASE
     * refuses a row, and a check-constraint violation inside an outer transaction would surface at
     * commit rather than at the statement that caused it, moving the failure away from the write and
     * marking the whole transaction unusable for the assertions that follow.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates, wrapped here for
     *     the catalog reads; must not be {@code null}
     */
    @BeforeEach
    void emptyLedger(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.repository.deleteAllInBatch();
    }

    /**
     * Confirms the production migration applied and that it, and not this test, created the schema.
     *
     * <p>Assumptions: the history row is read for the exact version {@code V1__batch.sql} declares, so
     * an environment that reached this table by some other route fails here. The two object counts
     * beside it are what distinguish "the migration ran" from "the migration ran completely": the
     * Spring Batch job repository is created by the same file, at its L664 through L739, and a job that
     * started against a half-applied schema would fail naming a missing sequence rather than a missing
     * migration.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("Flyway applied V1__batch.sql, creating batch_run and the job repository")
    void flywayAppliedTheProductionBatchMigration() {
        List<String> applied = this.jdbc.queryForList(
                "SELECT version FROM batch.flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);

        assertThat(applied)
                .as("the batch schema must be reached through db/migration and through nothing else")
                .contains("1");

        Integer batchRunColumns = this.jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'batch' AND table_name = 'batch_run'",
                Integer.class);
        assertThat(batchRunColumns)
                .as("batch_run carries the eight columns V1__batch.sql declares, the eighth being the"
                        + " attempt counter a re-opened row increments")
                .isEqualTo(8);

        Integer jobRepositoryTables = this.jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'batch' AND table_name LIKE 'batch\\_job%'",
                Integer.class);
        assertThat(jobRepositoryTables)
                .as("the Spring Batch job repository tables arrive from the same migration")
                .isEqualTo(4);
    }

    /**
     * Confirms Flyway created the {@code batch} schema under the migration owner role.
     *
     * <p>Assumptions: this is the assertion that makes the test profile's {@code init-sqls} mean
     * something. Those statements create the NOLOGIN owner role, grant it CREATE on the container's
     * generated database and assume it, precisely so that the objects Flyway creates belong to that
     * role as they do in a deployed environment. Nothing about a successful migration reveals which
     * role owns its output, so the ownership is read from the catalog.</p>
     *
     * <p>Assumptions: the four FOREIGN schemas are asserted to exist and to be owned by someone else
     * in the same case, because the two halves together state the whole boundary: this module's schema
     * is created by its own migration under its own role, and the four it merely reads arrive from
     * the init script as the container's user. A harness path that had been renamed would fail
     * here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the batch schema is owned by the migration role and the four foreign schemas exist")
    void theBatchSchemaIsOwnedByTheMigrationRole() {
        String batchOwner = this.jdbc.queryForObject(
                "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'batch'",
                String.class);

        assertThat(batchOwner)
                .as("objects a migration creates must belong to the NOLOGIN owner, because every"
                        + " ALTER DEFAULT PRIVILEGES clause in the bootstrap SQL is keyed on the"
                        + " creating role and is inert otherwise")
                .isEqualTo(MIGRATION_OWNER_ROLE);

        List<String> foreignSchemas = this.jdbc.queryForList(
                "SELECT nspname FROM pg_namespace"
                        + " WHERE nspname IN ('ledger', 'account', 'reference', 'card')"
                        + " ORDER BY nspname",
                String.class);
        assertThat(foreignSchemas)
                .as("the init script supplies the four schemas this module reads across")
                .containsExactly("account", "card", "ledger", "reference");

        String ledgerOwner = this.jdbc.queryForObject(
                "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'ledger'",
                String.class);
        assertThat(ledgerOwner)
                .as("a foreign schema is NOT owned by this module's migration role, which is what"
                        + " keeps the init script and the migration distinguishable")
                .isNotEqualTo(MIGRATION_OWNER_ROLE);
    }

    /**
     * Confirms a step row round-trips through the real table and both finders resolve.
     *
     * <p>Assumptions: the row is read back after the context is cleared, so the assertion is about the
     * table rather than about the first-level cache. Reading through the same context would return the
     * instance just written and would keep passing if the column were removed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an open step round-trips and is found by run and step, and by run and status")
    void anOpenStepRoundTripsAndIsFoundByBothFinders() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);

        Optional<BatchRun> byStep = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME);
        assertThat(byStep).isPresent();
        BatchRun stored = byStep.orElseThrow();
        assertThat(stored.getId()).as("the surrogate key is assigned by the database").isNotNull();
        assertThat(stored.getRunId()).isEqualTo(RUN_ID);
        assertThat(stored.getStepName()).isEqualTo(STEP_NAME);
        assertThat(stored.getStatus()).isEqualTo(BatchRunStatus.STARTED);
        assertThat(stored.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(stored.getFinishedAt())
                .as("an open step has published no outcome, so neither column carries one")
                .isNull();
        assertThat(stored.getReturnCode()).isNull();

        assertThat(this.repository.findByRunIdAndStatusOrderByIdAsc(RUN_ID, BatchRunStatus.STARTED))
                .hasSize(1);
        assertThat(this.repository.findByRunIdAndStatusOrderByIdAsc(RUN_ID, BatchRunStatus.COMPLETED))
                .as("a status the run has not reached returns no row rather than every row")
                .isEmpty();
        assertThat(this.repository.findByRunIdAndStepName(RUN_ID, "NoSuchStep"))
                .as("the finder is keyed on the pair, so a wrong step name must miss")
                .isEmpty();
    }

    /**
     * Confirms a completed step stores the warn tier, which is what makes a redrive a no-op.
     *
     * <p>Assumptions: the tier asserted is 4 rather than 0, because 4 is the value the mechanism
     * exists for. {@code app/cbl/CBTRN02C.cbl} L229 to L230 sets a soft-warning return code when any
     * record was rejected, and {@code app/jcl/TRANBKP.jcl} L51 gates the following step with
     * {@code COND=(4,LT)} -- so the chain continues on a 4. A ledger that recorded a completed step
     * without preserving which tier it reached would let a redriven step report clean and change the
     * orchestrator's downstream choice.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a completed step preserves the warn tier the original attempt reached")
    void aCompletedStepPreservesItsTier() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);
        this.transactionTemplate.executeWithoutResult(status -> {
            BatchRun open = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME).orElseThrow();
            open.markCompleted(FINISHED_AT, RETURN_CODE_WARN);
            this.repository.save(open);
        });
        this.entityManager.clear();

        BatchRun stored = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(BatchRunStatus.COMPLETED);
        assertThat(stored.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(stored.getReturnCode()).isEqualTo(RETURN_CODE_WARN);
    }

    /**
     * Confirms a failed step stores the hard-failure tier and its finishing instant.
     *
     * <p>Assumptions: the failed state is asserted separately from the completed one because the
     * lifecycle constraint admits different column combinations for the two, and a mapping that
     * conflated them would satisfy one arm of that constraint while writing the other's status.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed step stores the hard-failure tier and a finishing instant")
    void aFailedStepStoresTheHardFailureTier() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);
        this.transactionTemplate.executeWithoutResult(status -> {
            BatchRun open = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME).orElseThrow();
            open.markFailed(FINISHED_AT, RETURN_CODE_FAIL);
            this.repository.save(open);
        });
        this.entityManager.clear();

        BatchRun stored = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(BatchRunStatus.FAILED);
        assertThat(stored.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(stored.getReturnCode()).isEqualTo(RETURN_CODE_FAIL);
    }

    /**
     * Confirms the unique constraint refuses a second row for one run and step.
     *
     * <p>Assumptions: this constraint IS the idempotency policy, so it is asserted by provoking it
     * rather than by reading the migration. Without it a redriven step would open a second row, the
     * ledger's keyed lookup would return an arbitrary one of the two, and a completed step could be
     * re-executed -- which is the single failure the ledger exists to prevent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a second row for the same run and step is refused by the unique constraint")
    void aDuplicateRunAndStepIsRefused() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);

        assertThatThrownBy(() -> commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_batch_run_run_step");

        this.entityManager.clear();
        assertThat(this.repository.count())
                .as("the refused write left the one committed row and added nothing")
                .isEqualTo(1L);
    }

    /**
     * Confirms the declared predicate of each check constraint, read from the catalog.
     *
     * <p>Refactoring Rationale: the four predicates are asserted from {@code pg_constraint} as well as
     * behaviourally, and the reason is a limitation the behavioural cases cannot escape. Three of the
     * four constraints are UNREACHABLE in isolation, because the lifecycle predicate already requires
     * the status to be one of the three declared values and the return code to be one the tier
     * constraint admits -- so a row that violates the status domain violates the lifecycle predicate
     * too, and PostgreSQL reports whichever it evaluates first rather than the one a case had in mind.
     * Naming a specific constraint in those cases produced a green-looking assertion failure on a
     * correctly rejected row, which is how this was found. Reading the definition text is what pins
     * each predicate individually; the behavioural cases below then prove they are ENFORCED.</p>
     *
     * <p>Assumptions: the definitions are compared by the values they name rather than by their whole
     * text, because PostgreSQL normalises a predicate when it stores it -- adding casts, parentheses
     * and its own spacing -- so an equality against the migration's source would fail on formatting
     * while the predicate was correct.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every constraint V1__batch.sql declares exists with the predicate it declares")
    void theDeclaredConstraintsCarryTheDeclaredPredicates() {
        List<String> names = this.jdbc.queryForList(
                "SELECT conname FROM pg_constraint"
                        + " WHERE conrelid = 'batch.batch_run'::regclass ORDER BY conname",
                String.class);

        assertThat(names).containsExactly(
                "ck_batch_run_attempt",
                "ck_batch_run_finished_after_started",
                "ck_batch_run_lifecycle",
                "ck_batch_run_return_code",
                "ck_batch_run_status",
                "pk_batch_run",
                "uq_batch_run_run_step");

        assertThat(definitionOf("ck_batch_run_status"))
                .as("the status domain is closed at exactly the three values the enumeration declares")
                .contains("'STARTED'", "'COMPLETED'", "'FAILED'");

        assertThat(definitionOf("ck_batch_run_return_code"))
                .as("the tier domain admits the rubric's three tiers and nothing between them: a"
                        + " return code of 2 is a usage error the rubric excludes from aggregation and"
                        + " must never be recorded as a step outcome")
                .contains("IS NULL", "= 0", "= 4", ">= 8");

        assertThat(definitionOf("ck_batch_run_lifecycle"))
                .as("each status arm names the columns it requires, which is what stops a completed"
                        + " step from carrying no tier for a redrive to read")
                .contains("'STARTED'", "'COMPLETED'", "'FAILED'", "finished_at IS NULL",
                        "finished_at IS NOT NULL");

        assertThat(definitionOf("ck_batch_run_finished_after_started"))
                .contains("finished_at IS NULL", "finished_at >= started_at");

        assertThat(definitionOf("uq_batch_run_run_step"))
                .as("the idempotency key is the pair, in that order")
                .contains("UNIQUE (run_id, step_name)");

        assertThat(definitionOf("ck_batch_run_attempt"))
                .as("an attempt count below one describes a row that exists without anything having"
                        + " been attempted, and the row is only created when an attempt begins")
                .contains("attempt >= 1");
    }

    /**
     * Confirms the attempt counter is durable across a re-open and defaults to the first attempt.
     *
     * <p>Purpose: the counter is the only record that a step was tried more than once. Because
     * {@code uq_batch_run_run_step} admits one row per run and step, a redrive cannot insert a second
     * row -- so the recorded row is re-opened in place and the count is what distinguishes a first
     * attempt from a fourth. A counter that lived only in the entity would be lost on every reload.</p>
     *
     * <p>Assumptions: the row is read back through plain SQL after the transaction commits rather than
     * through the persistence context that wrote it, because a context read can be answered from the
     * first-level cache and would pass on a column the database never stored. The column, its default
     * and its constraint are what this case is about.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the attempt counter defaults to one, survives a re-open and refuses a count below one")
    void theAttemptCounterIsDurableAcrossAReopen() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);

        assertThat(this.jdbc.queryForObject(
                "SELECT attempt FROM batch.batch_run WHERE run_id = ? AND step_name = ?",
                Integer.class, RUN_ID, STEP_NAME))
                .as("a row created by an opening attempt records the first attempt")
                .isEqualTo(1);

        this.transactionTemplate.executeWithoutResult(status -> {
            BatchRun recorded = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME)
                    .orElseThrow();
            recorded.markFailed(FINISHED_AT, RETURN_CODE_FAIL);
        });
        this.transactionTemplate.executeWithoutResult(status -> {
            BatchRun recorded = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME)
                    .orElseThrow();
            recorded.reopen(FINISHED_AT.plusMinutes(30));
        });
        this.entityManager.clear();

        Map<String, Object> reopened = this.jdbc.queryForMap(
                "SELECT attempt, status, finished_at, return_code FROM batch.batch_run"
                        + " WHERE run_id = ? AND step_name = ?", RUN_ID, STEP_NAME);
        assertThat(reopened.get("attempt"))
                .as("re-opening the recorded row counts the attempt rather than inserting a second row")
                .isEqualTo(2);
        assertThat(reopened.get("status")).isEqualTo(BatchRun.BatchRunStatus.STARTED.name());
        assertThat(reopened.get("finished_at"))
                .as("the lifecycle constraint requires a started row to carry no finishing instant, so"
                        + " re-opening has to clear the one the failed attempt wrote")
                .isNull();
        assertThat(reopened.get("return_code"))
                .as("and to carry no tier, for the same reason")
                .isNull();

        assertThatThrownBy(() -> commitStatement(
                "UPDATE batch.batch_run SET attempt = 0 WHERE run_id = ? AND step_name = ?",
                RUN_ID, STEP_NAME))
                .as("the database refuses a count below one, so an operator repairing a row by hand"
                        + " cannot record a row that nothing was ever attempted on")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Confirms a status outside the declared three is refused.
     *
     * <p>Assumptions: the row is written through plain SQL rather than through the repository, because
     * the entity maps the status as an enumeration and so cannot express the value being refused. That
     * is the reason the constraint exists at all: the ETL loads and an operator repairs rows without
     * executing application code, so a domain enforced only in Java is a domain enforced only for one
     * of its writers.</p>
     *
     * <p>Trade-offs: the assertion names the TABLE and the fact that a check refused the row, not
     * which check did. An undeclared status also fails the lifecycle predicate -- every arm of it names
     * one of the three valid statuses -- so no row can isolate this one constraint, and asserting a
     * particular name here would fail on a correctly refused row whenever the engine happened to
     * evaluate the other predicate first. The predicate itself is pinned by the catalog case above.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a status outside the declared three is refused by a check constraint")
    void anUndeclaredStatusIsRefused() {
        assertThatThrownBy(() -> commitStatement(
                "INSERT INTO batch.batch_run (run_id, step_name, status, started_at)"
                        + " VALUES (?, ?, 'RUNNING', ?)",
                RUN_ID, STEP_NAME, STARTED_AT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint")
                .hasMessageContaining("batch_run");

        assertThat(this.repository.count()).as("nothing was written").isZero();
    }

    /**
     * Confirms a return code outside the rubric is refused while the abend tier is admitted.
     *
     * <p>Assumptions: the refused value is 2, and the choice is deliberate rather than arbitrary. Two
     * is a real code in the mainframe rubric -- {@code tests/README.md} assigns it to a runner invoked
     * incorrectly -- and the rubric explicitly excludes it from aggregation, so it must never be
     * recorded as a STEP outcome. A domain that admitted it would let a usage error be reported to the
     * orchestrator as though the step had run.</p>
     *
     * <p>Assumptions: 16 is asserted to be ADMITTED in the same case, because a domain that refused
     * every value but zero and four would also satisfy an assertion that named only a refusal. The
     * hard-failure tier is open ABOVE eight rather than fixed at it, and 16 is the rubric's abend
     * tier, so admitting it is the property the predicate's {@code >= 8} arm exists for.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a return code outside the rubric is refused while the abend tier is admitted")
    void anUndeclaredReturnCodeIsRefused() {
        assertThatThrownBy(() -> commitStatement(
                "INSERT INTO batch.batch_run"
                        + " (run_id, step_name, status, started_at, finished_at, return_code)"
                        + " VALUES (?, ?, 'FAILED', ?, ?, 2)",
                RUN_ID, STEP_NAME, STARTED_AT, FINISHED_AT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint")
                .hasMessageContaining("batch_run");

        commitStatement(
                "INSERT INTO batch.batch_run"
                        + " (run_id, step_name, status, started_at, finished_at, return_code)"
                        + " VALUES (?, ?, 'FAILED', ?, ?, 16)",
                RUN_ID, "AbendingStep", STARTED_AT, FINISHED_AT);

        BatchRun abended =
                this.repository.findByRunIdAndStepName(RUN_ID, "AbendingStep").orElseThrow();
        assertThat(abended.getReturnCode())
                .as("the abend tier of the rubric is above the hard-failure floor and is admitted")
                .isEqualTo((short) 16);
    }

    /**
     * Confirms the lifecycle constraint refuses a completed step that published no outcome.
     *
     * <p>Assumptions: this is the constraint whose absence would be least visible. A row marked
     * completed with no return code reads as a finished step to every query, and the ledger's skip
     * decision would then hand a redriven step a null tier rather than the tier the original attempt
     * reached. The refusal is provoked through SQL because the entity's own transition method sets
     * both columns together and so cannot produce the row.</p>
     *
     * <p>Assumptions: the second half refuses an open step that already carries a finishing instant,
     * which is the same constraint read from its other arm. Asserting only one arm would pass against
     * a predicate that had lost the other.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the lifecycle constraint refuses a completed step with no outcome, and a finished"
            + " open step")
    void anIncoherentLifecycleIsRefused() {
        assertThatThrownBy(() -> commitStatement(
                "INSERT INTO batch.batch_run"
                        + " (run_id, step_name, status, started_at, finished_at, return_code)"
                        + " VALUES (?, ?, 'COMPLETED', ?, ?, NULL)",
                RUN_ID, STEP_NAME, STARTED_AT, FINISHED_AT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_batch_run_lifecycle");

        assertThatThrownBy(() -> commitStatement(
                "INSERT INTO batch.batch_run"
                        + " (run_id, step_name, status, started_at, finished_at, return_code)"
                        + " VALUES (?, ?, 'STARTED', ?, ?, NULL)",
                RUN_ID, "StillOpenStep", STARTED_AT, FINISHED_AT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_batch_run_lifecycle");

        assertThat(this.repository.count()).as("neither refused row was written").isZero();
    }

    /**
     * Confirms a step cannot finish before it started.
     *
     * <p>Assumptions: this constraint guards a value the orchestrator reads for elapsed time, and an
     * inverted pair yields a negative duration rather than an error. The refused row is otherwise
     * entirely valid -- a completed status with a clean tier -- so the case isolates the ordering and
     * nothing else.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a step that finishes before it started is refused")
    void anInvertedIntervalIsRefused() {
        assertThatThrownBy(() -> commitStatement(
                "INSERT INTO batch.batch_run"
                        + " (run_id, step_name, status, started_at, finished_at, return_code)"
                        + " VALUES (?, ?, 'COMPLETED', ?, ?, ?)",
                RUN_ID, STEP_NAME, FINISHED_AT, STARTED_AT, RETURN_CODE_CLEAN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_batch_run_finished_after_started");
    }

    /**
     * Executes one statement inside a committed transaction.
     *
     * <p>Refactoring Rationale: a bare {@code JdbcTemplate} write does not survive here, and the reason
     * is a deliberate production setting rather than an oversight in the test.
     * {@code application.yml} sets {@code spring.datasource.hikari.auto-commit: false} so that the
     * driver honours a positive fetch size and the chunked job scans stream instead of exhausting the
     * heap. Outside a transaction the connection is therefore never committed and is rolled back as it
     * returns to the pool, so a statement that plainly succeeded left no row -- which is how this was
     * found, as a later keyed read finding nothing. Every direct statement below goes through this
     * boundary, which is also the boundary the application's own writes use.</p>
     *
     * <p>Assumptions: a refused statement raises from inside the boundary and the exception propagates
     * after the rollback, so the refusal cases read the same way as the accepted one. Plain SQL is used
     * rather than the repository only where the entity cannot express the value under refusal.</p>
     *
     * @param sql the statement to execute, with positional parameters; must not be {@code null}
     * @param arguments the positional parameter values, in declaration order
     * @throws org.springframework.dao.DataIntegrityViolationException if the database refuses the
     *     statement, which several cases above provoke deliberately
     */
    private void commitStatement(String sql, Object... arguments) {
        this.transactionTemplate.executeWithoutResult(status -> this.jdbc.update(sql, arguments));
    }

    /**
     * Returns the stored definition of one constraint on the ledger table.
     *
     * <p>Assumptions: the definition is read through {@code pg_get_constraintdef} rather than
     * assembled from {@code information_schema}, because that function renders the predicate the engine
     * actually holds -- which is the artifact under assertion -- while the standard view exposes it in
     * a form that varies with the column types involved.</p>
     *
     * @param constraintName the exact constraint name {@code V1__batch.sql} declares; must not be
     *     {@code null}
     * @return the rendered definition, never {@code null}
     * @throws org.springframework.dao.EmptyResultDataAccessException if no constraint of that name is
     *     declared on the table, which is itself a meaningful failure
     */
    private String definitionOf(String constraintName) {
        return this.jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                        + " WHERE conrelid = 'batch.batch_run'::regclass AND conname = ?",
                String.class, constraintName);
    }

    /**
     * Writes one open step row, commits it, and detaches every managed instance.
     *
     * <p>Assumptions: the flush precedes the clear inside the boundary, because clearing detaches and
     * a pending change on a detached instance is simply lost. The commit then makes the row visible to
     * the reads that follow, so each of those reads is a real select rather than a lookup in the
     * persistence context that wrote it.</p>
     *
     * @param runId the run identifier to write under; must not be {@code null}
     * @param stepName the step name to write under; must not be {@code null}
     * @param startedAt the instant the step opened at; must not be {@code null}
     * @throws org.springframework.dao.DataIntegrityViolationException if the database refuses the row,
     *     which two cases below provoke deliberately
     */
    private void commitOpenStep(String runId, String stepName, LocalDateTime startedAt) {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.repository.save(new BatchRun(runId, stepName, startedAt));
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, and that omission is the whole point. This
     * module's own application class scans the bounded context and so registers eight job
     * definitions, a queue client, an object-store client and a parameter-store client -- none of
     * which a ledger assertion needs and each of which is a further way for one to fail for an
     * unrelated reason. Naming the two persistence packages leaves the framework's own
     * auto-configuration to build the pool from the properties the container registered.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this
     * block carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class StepLedgerPersistenceTestApplication {
    }
}
