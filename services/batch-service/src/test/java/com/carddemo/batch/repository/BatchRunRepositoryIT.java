package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.domain.BatchRun.BatchRunStatus;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.service.BatchStepLedgerWriter;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * <p>Purpose: {@code batch.batch_run} makes a completed step a no-op on a redrive, by carrying one row
 * per {@code (run_id, step_name)} pair together with the tier that attempt reached. Every case below
 * asserts one property of that row: that the migration which creates it applies, that the constraints
 * which make it meaningful actually fire, and that the two finders the ledger is read through
 * resolve.</p>
 *
 * <p>Refactoring Rationale: this ledger is an IMPROVEMENT the target adds rather than a port of an
 * existing capability, and the reference is unambiguous about which of the two it is. Exactly one
 * restart directive appears across the thirty-eight members of {@code app/jcl}, at
 * {@code app/jcl/DEFGDGD.jcl} L2, and it is written {@code //*  RESTART=STEP30} -- the leading
 * {@code //*} makes it a comment, so no job ever acted on it -- while no {@code CHKPT=} appears in any
 * of those thirty-eight members at all. What was wrong with the old approach is therefore not that its
 * restart contract was weak but that it had none: a rerun re-did the work of every step that had
 * already succeeded. Because the capability is added rather than migrated, its divergence is registered
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: no golden master is cited by any case in this class, and the absence is a fact about
 * the reference rather than an omission here. The {@code tests/golden} tree holds {@code posting},
 * {@code interest}, {@code provisioning}, {@code reporting} and {@code statement} and no step-ledger
 * directory of any kind, because a ledger the baseline never kept can have left no expected output to
 * compare against. A reader looking for the vector that pins these assertions will not find one, and
 * the citation each case carries instead is the reference line that establishes the ABSENCE of the
 * mechanism.</p>
 *
 * <h2>The line this class does not cross</h2>
 *
 * <p>Assumptions: the stored {@code return_code} values below are written and read as data, and their
 * MEANING is asserted elsewhere. {@code com.carddemo.batch.job.PostTransactionsJobTest} owns the graded
 * return-code tier itself, the two verbatim counter lines the posting step emits, and the inversion of
 * a baseline step gate into an orchestrator run predicate; it also owns the business-date job parameter
 * and the resolution of a job name through the framework's registry. Restating any of that here would
 * create a second place it could be relaxed while this one still read as intact, so a reader wanting
 * the semantics of a 4 is pointed at that class rather than served a copy of it. Tier one, under
 * {@code com.carddemo.batch.service}, owns all validation and arithmetic, and nothing here touches
 * either.</p>
 *
 * <p>Assumptions: no diagnostic in this class may render a whole record or an unmasked primary account
 * number, and the convention is established here even though this table cannot breach it -- the ledger
 * carries a run identifier, a step name, a lifecycle state, two instants, a tier and a counter, and no
 * cardholder data at all. The migration plan's section 0.7.8 masks a primary account number to its last
 * four digits everywhere it is rendered and never returns a card verification value, so an assertion
 * description or a failure message that echoed a full record would leak through the one channel that is
 * exempt from the mapping layer. The sibling classes in this package do hold card numbers, and they
 * copy this convention.</p>
 *
 * <p>Refactoring Rationale: every assertion below was previously unreachable. Beside the surrogate
 * primary key, {@code V1__batch.sql} declares six REFUSABLE constraints on this table -- one unique
 * pair and five checks -- and a check constraint that is never violated in a test is indistinguishable
 * from a check constraint that was mistyped: PostgreSQL accepts a predicate that can never be false as
 * readily as one that can, and Hibernate validates none of them. So each of the six is asserted by
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
     *
     * <p>Assumptions: the ORDER in which the two halves of the schema arrive is load-bearing, and
     * {@code withInitScript} below is what fixes it. Testcontainers runs this script as the container
     * becomes ready, which is strictly before the application context opens the connection Flyway
     * migrates on, so the four foreign schemas already exist when the persistence provider runs its
     * {@code validate} pass over the mappings that reference them. Re-implementing that ordering in
     * Java -- a {@code @BeforeAll} issuing the statements, say -- would place it after the context had
     * already started and failed. The file NAME is therefore part of the contract rather than a
     * description of the file, and neither renaming nor relocating it is a local change.</p>
     *
     * <p>Assumptions: the script deliberately does NOT create {@code batch}, so the one table this
     * class asserts on arrives from {@code db/migration/V1__batch.sql} through Flyway and from nowhere
     * else. That asymmetry is easy to misread as an oversight in the harness: an init script runs as the
     * container's superuser, so a {@code batch} schema created there would be owned by that user, and
     * the test profile's Flyway {@code init-sqls} then assumes a NOLOGIN role which is refused CREATE on
     * a schema it does not own. Letting Flyway create the schema under that role instead -- which is why
     * the profile sets {@code create-schemas} true rather than false -- reproduces the ownership a
     * deployed environment has, and that ownership is itself asserted below.</p>
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
     * The step name of a second state of the same run, used where a case needs one step left open.
     *
     * <p>Assumptions: the name is the interest state's, which FOLLOWS posting in the orchestrated
     * chain, so a run holding this step open while posting has completed is the arrangement a crash
     * part-way through the chain actually leaves behind rather than an arbitrary pair of rows.</p>
     */
    private static final String IN_FLIGHT_STEP_NAME = "CalculateInterest";

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
     * The clock the losing attempt of a collision reads its opening instant from.
     *
     * <p>Assumptions: it is fixed one hour after {@link #STARTED_AT} rather than at it, so a case that
     * asserts the winner's opening instant survived cannot pass because the two instants happened to
     * agree. The zone is the offset the instant is built from, so the value the writer reads back is the
     * literal above plus an hour and not that instant rendered in the runner's own zone.</p>
     */
    private static final Clock LOSER_CLOCK =
            Clock.fixed(STARTED_AT.plusHours(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} and not from
     * {@code org.testcontainers.containers}. Testcontainers 2.0.5 ships both and only the legacy
     * package is deprecated; the replacement is not generic, so the declaration carries no type
     * argument.</p>
     */
    // WHY : Alternatives Considered: an in-memory engine, which would start in milliseconds instead of
    //       the twenty seconds a container costs. Rejected because every property this class asserts IS
    //       an engine behaviour and none of them survives a substitute. The schema ownership read from
    //       pg_namespace, the stored predicate text read from pg_constraint, the SIX constraints that
    //       have to REFUSE a row at the statement that writes it, the search_path the datasource pins
    //       across five schemas and Flyway's own history table are all PostgreSQL facts; an in-memory
    //       engine implements them differently or not at all, so each assertion would either not
    //       compile as written or pass while saying nothing about the engine the nightly chain runs
    //       against. No embedded driver is on this module's classpath, so the substitute is not
    //       reachable even by accident.
    // WHY : Assumptions: the count above is SIX and is named rather than tallied, because it read
    //       "five" while db/migration/V1__batch.sql declares six refusable constraints on
    //       batch.batch_run and this class exercises every one: uq_batch_run_run_step (L447),
    //       ck_batch_run_attempt (L418), ck_batch_run_status (L457), ck_batch_run_return_code
    //       (L480), ck_batch_run_lifecycle (L542) and ck_batch_run_finished_after_started (L576).
    //       pk_batch_run (L410) is deliberately outside the count -- its key is database-generated,
    //       so no statement this class can write violates it. Naming the six rather than counting
    //       them is what stops the number drifting again: a seventh constraint added to the
    //       migration without a case here leaves this list visibly short, whereas a bare numeral
    //       goes stale silently, which is exactly what happened.
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
     * transaction, and the difference is load-bearing here. Six cases below assert that the DATABASE
     * refuses a row, and a constraint violation raised inside an enclosing test transaction would
     * surface at commit rather than at the statement that caused it, moving the failure away from the
     * write and marking the whole transaction unusable for the assertions that follow. Emptying between
     * cases keeps each one independent in the spirit the reference suite states at
     * {@code tests/README.md} section 11, where every test provisions its own workspace and shares no
     * mutable state, which is what lets any single case here be run alone and still mean something.</p>
     *
     * <p>Trade-offs: this per-case truncation is a local convenience of a class whose every write is its
     * own committed transaction, and it does NOT generalise to the rest of the package. Annotating a
     * class in this package {@code @Transactional} so that each case rolled back would be the cheaper
     * habit and is deliberately not adopted, because it silently defeats
     * {@code PostingUnitOfWorkIT}: that class proves the posting unit of work commits or rolls back as
     * one, and it proves it by reading the three tables from OUTSIDE the transaction under test, which
     * an enclosing test transaction makes impossible -- the writes would be invisible to the observer
     * whether the code was correct or not, and the atomicity proof would pass vacuously. The accepted
     * cost of truncating instead is one extra statement per case.</p>
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
     * Confirms the production migrations applied and that they, and not this test, created the schema.
     *
     * <p>Assumptions: the history rows are read for the exact versions {@code db/migration} declares, so
     * an environment that reached this table by some other route fails here. The catalog census beside
     * them is what distinguishes "the migration ran" from "the migration ran completely": the Spring
     * Batch job repository is created by the same file, at its L703 through L778, and a job that started
     * against a half-applied schema fails naming a missing sequence rather than a missing migration.</p>
     *
     * <p>Assumptions: the history holds one VERSIONLESS successful row beside the two versioned ones,
     * and it is asserted separately rather than filtered away silently. Flyway writes it when it has to
     * create the schema before migrating into it -- {@code SchemaHistory} records the marker with the
     * description asserted below and the type {@code SCHEMA} -- so its presence is the direct evidence
     * for the second half of this case's own claim, that the schema was created by the migration and
     * not by this test or by a harness script. The versioned census is therefore taken over
     * {@code version IS NOT NULL} and the marker is asserted on its own, which is what lets both be
     * exact: a census mixing the two would have to admit a null, and admitting a null is how it would
     * also admit a second unexplained versionless row.</p>
     *
     * <p>Refactoring Rationale: the object inventory is an EXACT census of named objects, replacing a
     * count of tables matching {@code batch\_job%}. That prefix count expected four and passed, while
     * the migration creates six framework tables -- {@code BATCH_STEP_EXECUTION} and
     * {@code BATCH_STEP_EXECUTION_CONTEXT} are outside the prefix, and all three sequences are outside
     * the object class -- so a migration that dropped either step table or any sequence would have
     * satisfied the assertion. A subset predicate cannot detect a missing member of the set it filters
     * on, which is exactly the failure mode a migration-completeness case exists to catch.</p>
     *
     * <p>Trade-offs: the census is closed with {@code containsExactlyInAnyOrder} rather than left open
     * with {@code contains}, so a migration that ADDS an object fails here until this list names it.
     * That is deliberate: the cost is one edit per deliberate schema addition, and the benefit is that
     * the batch schema cannot grow an unreviewed table or sequence. {@code flyway_schema_history} is
     * Flyway's own bookkeeping table and {@code daily_feed_watermark} arrives from
     * {@code V2__batch_feed_watermark.sql}; both are named because they are genuinely in this schema,
     * and their shape is pinned by {@code DailyFeedWatermarkRepositoryIT} rather than here.</p>
     *
     * <p>Assumptions: FOUR versions are expected, and the two censuses below respond differently to the
     * last two, which is not an inconsistency.
     * {@code V3__batch_run_contract_restatement.sql} issues {@code COMMENT ON} statements only -- one
     * column comment and seven constraint comments -- so it adds no relation for either census to
     * name, while {@code V4__batch_posting_reject_outbox.sql} creates {@code posting_reject_outbox} and
     * is therefore named in the table census and in neither the column count, which is scoped to
     * {@code batch_run}, nor the sequence census, because its ordinal is an identity column and
     * {@code information_schema.sequences} excludes the sequence behind one. The version list is
     * asserted separately from the relation censuses for exactly this reason: a migration that only
     * documents the schema must still be declared here, because the assertion's purpose is to prove the
     * schema was reached through EVERY migration rather than through the first that happened to create a
     * table.</p>
     *
     * <p>Refactoring Rationale: the expected version list and the table census were extended from three
     * versions and nine tables when {@code V4__batch_posting_reject_outbox.sql} landed. This is the one
     * edit per deliberate schema addition the closed census below states as its own cost, and paying it
     * here rather than opening the census is what keeps an UNREVIEWED table from arriving unnoticed. The
     * shape of the new table is asserted by {@code PostingRejectOutboxIT} and deliberately not
     * restated.</p>
     *
     * <p>Assumptions: the sequence census reads {@code information_schema.sequences}, which excludes a
     * sequence owned by an identity column. So the implicit sequence behind {@code batch_run.id} is
     * absent from the expected set by construction rather than by oversight, and the three named
     * sequences are exactly the ones {@code V1__batch.sql} declares with {@code CREATE SEQUENCE}. The
     * identity column itself is proven by every case here that inserts a row without supplying an
     * identifier and reads one back.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("Flyway applied db/migration completely, creating every named table and sequence")
    void flywayAppliedTheProductionBatchMigration() {
        List<String> applied = this.jdbc.queryForList(
                "SELECT version FROM batch.flyway_schema_history"
                        + " WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);

        assertThat(applied)
                .as("the batch schema must be reached through db/migration and through nothing else,"
                        + " and through every migration it declares rather than only the first")
                .containsExactly("1", "2", "3", "4");

        List<String> schemaCreation = this.jdbc.queryForList(
                "SELECT description FROM batch.flyway_schema_history"
                        + " WHERE success AND version IS NULL ORDER BY installed_rank",
                String.class);
        assertThat(schemaCreation)
                .as("the one versionless history row is Flyway's own schema-creation marker, which is"
                        + " what makes the migration and not this test the creator of the schema")
                .containsExactly("<< Flyway Schema Creation >>");

        Integer batchRunColumns = this.jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'batch' AND table_name = 'batch_run'",
                Integer.class);
        assertThat(batchRunColumns)
                .as("batch_run carries the eight columns V1__batch.sql declares, the eighth being the"
                        + " attempt counter a re-opened row increments")
                .isEqualTo(8);

        List<String> tables = this.jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'batch' AND table_type = 'BASE TABLE'"
                        + " ORDER BY table_name",
                String.class);
        assertThat(tables)
                .as("the batch schema holds exactly the step ledger, the feed watermark, the posting"
                        + " reject outbox, Flyway's history and the six Spring Batch job-repository"
                        + " tables")
                .containsExactlyInAnyOrder(
                        "batch_run",
                        "daily_feed_watermark",
                        "posting_reject_outbox",
                        "flyway_schema_history",
                        "batch_job_instance",
                        "batch_job_execution",
                        "batch_job_execution_params",
                        "batch_step_execution",
                        "batch_step_execution_context",
                        "batch_job_execution_context");

        List<String> sequences = this.jdbc.queryForList(
                "SELECT sequence_name FROM information_schema.sequences"
                        + " WHERE sequence_schema = 'batch' ORDER BY sequence_name",
                String.class);
        assertThat(sequences)
                .as("the three Spring Batch sequences arrive from the same migration; the framework"
                        + " allocates job, job-execution and step-execution identifiers from them and"
                        + " fails at the first launch if any one of them is absent")
                .containsExactlyInAnyOrder(
                        "batch_job_instance_seq",
                        "batch_job_execution_seq",
                        "batch_step_execution_seq");
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
     * <p>Assumptions: the artifact this case pins is
     * {@code data-migration/sql/V0__schemas_and_roles.sql}, whose L713 declares the authorization of the
     * {@code batch} schema, and no reference line is cited because schema ownership has no baseline
     * counterpart at all -- a VSAM cluster is a catalog entry with no owning role, so there is nothing in
     * {@code app/} for this assertion to be faithful to.</p>
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
     * <p>Assumptions: the artifact this case pins is the eight-column declaration in
     * {@code V1__batch.sql} together with the two finders {@code BatchRunRepository} derives from it,
     * and no reference line is cited because the ledger row itself is target-side -- the baseline kept
     * no per-step record, so no COBOL statement and no golden file describes one.</p>
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
     * orchestrator's downstream choice. What that tier MEANS is asserted by
     * {@code com.carddemo.batch.job.PostTransactionsJobTest}; here it is a stored value that has to
     * survive the round trip.</p>
     *
     * <p>Assumptions: the case walks the whole decision a redriven step takes, in the order a job class
     * takes it -- open the row, reach a terminal state, then ask the ledger again -- because the skip
     * decision is the composition of those three and not a property of any one of them. A caller that
     * finds a terminal row returns without repeating the step's writes, and the tier it reads is the
     * one the ORIGINAL attempt published rather than a fresh one.</p>
     *
     * <p>Assumptions: a second step of the same run is left open, which is what makes the status finder
     * answer an operationally real question. After a crash the resumed execution has to tell the steps
     * that finished from the ones that were in flight when the process died, and it has only this table
     * to tell them apart; a run holding one row of each state is the smallest arrangement in which a
     * finder that ignored its status argument would be caught.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a completed step preserves its tier and is distinguished from a step left in flight")
    void aCompletedStepPreservesItsTier() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);
        commitOpenStep(RUN_ID, IN_FLIGHT_STEP_NAME, STARTED_AT.plusMinutes(2));
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

        assertThat(this.repository.findByRunIdAndStatusOrderByIdAsc(RUN_ID, BatchRunStatus.STARTED))
                .as("a resumed execution reads the steps still recorded as in flight, and the step that"
                        + " reached a terminal state is not among them")
                .extracting(BatchRun::getStepName)
                .containsExactly(IN_FLIGHT_STEP_NAME);
        assertThat(this.repository.findByRunIdAndStatusOrderByIdAsc(RUN_ID, BatchRunStatus.COMPLETED))
                .as("and reads the finished steps separately, which is the pair of answers a redrive"
                        + " needs before it decides what is left to do")
                .extracting(BatchRun::getStepName)
                .containsExactly(STEP_NAME);
    }

    /**
     * Confirms a failed step stores the hard-failure tier and its finishing instant.
     *
     * <p>Assumptions: the failed state is asserted separately from the completed one because the
     * lifecycle constraint admits different column combinations for the two, and a mapping that
     * conflated them would satisfy one arm of that constraint while writing the other's status.</p>
     *
     * <p>Assumptions: the tier 8 written here is the FAIL step of the condition-code rubric
     * {@code tests/README.md} section 8 sets out, and the arm of {@code ck_batch_run_lifecycle} in
     * {@code V1__batch.sql} that admits a failed row is what this case pins. The rubric is the reference
     * the value comes from; the row that carries it is target-side, so no golden file is cited.</p>
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
     * re-executed -- which is the single failure the ledger exists to prevent. The reference cannot
     * supply a vector for this: the only restart directive in {@code app/jcl} is the inert comment at
     * {@code app/jcl/DEFGDGD.jcl} L2, so a redrive is a behaviour the baseline never had.</p>
     *
     * <p>Assumptions: the second half of this case admits the row the constraint must NOT refuse, and
     * the pairing is what makes the assertion mean something. A constraint declared on
     * {@code step_name} alone would satisfy the refusal above exactly as the correct one does, and
     * would then be discovered in production, where the nightly chain runs the same step names under a
     * new run identifier every night and the second night's run would be unable to open any step at
     * all. Asserting only the refusal cannot distinguish the two constraints; asserting the permitted
     * row is what pins the key to the PAIR.</p>
     *
     * <p>Assumptions: the single-valuedness of {@code findByRunIdAndStepName} is a property of this
     * constraint and not of the query. The finder declares no result limit and Spring Data applies
     * none, so its {@code Optional} return is a claim that at most one row can match -- and the only
     * thing that makes the claim true is the uniqueness asserted here. Were the constraint dropped, the
     * signature would be unchanged and the failure would appear as an incorrect-result-size exception
     * the first time two rows matched, which is why this case is what licenses every keyed read
     * below.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a second row for the same run and step is refused, while the same step under another"
            + " run is admitted")
    void aDuplicateRunAndStepIsRefused() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);

        assertThatThrownBy(() -> commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_batch_run_run_step");

        this.entityManager.clear();
        assertThat(this.repository.count())
                .as("the refused write left the one committed row and added nothing")
                .isEqualTo(1L);

        String followingRunId = "2022-07-19T02:00:00Z-nightly";
        commitOpenStep(followingRunId, STEP_NAME, STARTED_AT.plusDays(1));

        assertThat(this.repository.count())
                .as("the same step name under a different run is a distinct row, so the constraint is"
                        + " on the pair rather than on the step name")
                .isEqualTo(2L);
        assertThat(this.repository.findByRunIdAndStepName(followingRunId, STEP_NAME))
                .as("each run resolves to its OWN row for that step, which is what keeps one night's"
                        + " ledger from answering the next night's redrive question")
                .isPresent()
                .get()
                .extracting(BatchRun::getStartedAt)
                .isEqualTo(STARTED_AT.plusDays(1));
    }

    /**
     * Confirms the refusal above is recognised as the collision, and another refusal is not.
     *
     * <p>Purpose: {@code BatchStepLedgerWriter.namesStepUniqueness} decides whether a refused ledger
     * write is two executions competing for one step or a defect in the row being written, and the two
     * outcomes are opposite -- the first is reported as a named concurrency condition and the second
     * keeps the handling it has. This case drives that decision with exceptions the DATABASE raised
     * rather than with constructed ones, because the property under assertion is that the constraint
     * name the engine reports is read correctly.</p>
     *
     * <p>Assumptions: both halves are asserted in one case on purpose. A positive-only assertion is
     * satisfied by a classifier that answered {@code true} unconditionally, which would report every
     * integrity failure -- a status outside the domain, a code outside the rubric, an incoherent
     * lifecycle -- as a competing execution and would hide a genuine data defect behind a scheduling
     * diagnosis. Naming the discrimination is the whole of what makes the translation safe.</p>
     *
     * <p>Assumptions: the negative vector is the status-domain refusal, which reaches the classifier
     * through the JDBC translation path rather than the provider's, so it carries no constraint name the
     * classifier can read. That is the conservative answer the caller wants: an unrecognised refusal
     * keeps its own diagnosis rather than being relabelled as a collision it may not be.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the step-uniqueness refusal is recognised and another integrity refusal is not")
    void onlyTheStepUniquenessRefusalIsRecognised() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);

        Throwable collision =
                catchThrowable(() -> commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT.plusHours(1)));

        assertThat(collision).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(BatchStepLedgerWriter.namesStepUniqueness(
                (DataIntegrityViolationException) collision))
                .as("the refusal the engine raised for %s is the collision",
                        BatchStepLedgerWriter.STEP_UNIQUENESS_CONSTRAINT)
                .isTrue();

        Throwable otherRefusal = catchThrowable(() -> commitStatement(
                "INSERT INTO batch.batch_run (run_id, step_name, status, started_at)"
                        + " VALUES (?, ?, 'RUNNING', ?)",
                RUN_ID, IN_FLIGHT_STEP_NAME, STARTED_AT));

        assertThat(otherRefusal).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(BatchStepLedgerWriter.namesStepUniqueness(
                (DataIntegrityViolationException) otherRefusal))
                .as("a refusal about the row being written is not a competing execution")
                .isFalse();
    }

    /**
     * Confirms the loser of a collision is refused by name, writes nothing, and discloses nothing.
     *
     * <p>Purpose: two executions carrying one run identity both read no row and both insert one, so the
     * database settles the race and the second insert is refused. This case asserts what the loser then
     * REPORTS. Before the translation it exited on the entry point's generic job-failed code with a
     * persistence-layer trace quoting the refused statement and the constraint name, which described the
     * database rather than the cause and pointed an operator at the night's records instead of at the
     * duplicate execution.</p>
     *
     * <p>Assumptions: the losing execution's state is reproduced by a repository view that reports no
     * recorded row while the winner's row is committed, which is exactly the snapshot a loser holds --
     * it read before the winner committed and inserts afterwards. The refusal below is therefore raised
     * by the REAL constraint against a real committed row; only the read is arranged.</p>
     *
     * <p>Alternatives Considered: two threads racing on two transactions, with no arranged read at all.
     * Rejected because the loser blocks on the unique index until the winner commits, so the case would
     * depend on the interleaving of two commits to reach the branch it asserts -- and would pass while
     * asserting nothing on the runs where the winner committed first. The race itself is exercised
     * against two processes at run time; what this case has to be right about is the report.</p>
     *
     * <p>Assumptions: the surviving row is asserted to be the WINNER's, by its opening instant and its
     * attempt count, rather than merely to be one row. A loser that had reopened the winner's row would
     * also leave one row, and would have restarted the winner's clock and counted a second attempt
     * against work it never did.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the losing attempt is refused by a named code, writes nothing and discloses nothing")
    void theLosingAttemptOfARaceIsRefusedByName() {
        commitOpenStep(RUN_ID, STEP_NAME, STARTED_AT);
        BatchStepLedgerWriter loser =
                new BatchStepLedgerWriter(repositoryBlindToRecordedRows(), LOSER_CLOCK);

        Throwable refused = catchThrowable(() -> loser.openAttempt(RUN_ID, STEP_NAME));

        assertThat(refused)
                .isInstanceOf(BatchStepLedgerWriter.StepAttemptInProgressException.class)
                .hasNoCause()
                .hasMessageContaining(BatchStepLedgerWriter.OUTCOME_CODE_STEP_IN_PROGRESS)
                .hasMessageContaining(BatchStepLedgerWriter.IN_PROGRESS_REASON)
                .hasMessageContaining(RUN_ID)
                .hasMessageContaining(STEP_NAME);
        // WHY : Assumptions: the absence of the constraint name, of the driver's own duplicate-key
        //       wording and of the refused statement is asserted POSITIVELY. Those three are what the
        //       previous diagnosis consisted of, so a translation that named a code and then attached
        //       the refusal as a cause would satisfy every assertion above while rendering the same
        //       wall of persistence detail wherever the failure is logged.
        assertThat(refused.getMessage())
                .doesNotContain(BatchStepLedgerWriter.STEP_UNIQUENESS_CONSTRAINT)
                .doesNotContain("duplicate key")
                .doesNotContain("insert into");
        assertThat(refused.getStackTrace())
                .as("a diagnosed condition carries no frames, so no persistence trace is rendered")
                .isEmpty();

        this.entityManager.clear();
        assertThat(this.repository.count())
                .as("the refused attempt left the winner's row and added none of its own")
                .isEqualTo(1L);
        BatchRun surviving = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME).orElseThrow();
        assertThat(surviving.getStartedAt())
                .as("the winner's opening instant is intact, so the loser reopened nothing")
                .isEqualTo(STARTED_AT);
        assertThat(surviving.getAttempt())
                .as("the winner's attempt count is intact, so the loser counted nothing")
                .isEqualTo(1);
        assertThat(surviving.getStatus()).isEqualTo(BatchRunStatus.STARTED);
    }

    /**
     * Confirms the attempt whose outcome a competitor recorded first is refused by name, not by state.
     *
     * <p>Purpose: two executions carrying one run identity collide in TWO orders, and the constraint
     * catches only one of them. Insert against insert is refused by the database, which the case above
     * covers. When one execution reads the other's committed row instead, it REOPENS that row -- an
     * update, which the database has nothing to refuse -- runs the step body, and finds the row already
     * closed when it comes to record its own outcome. This case asserts what it reports then.</p>
     *
     * <p>Refactoring Rationale: this case is new, and it is the one a two-process run against the fixed
     * build actually produced. The loser failed on the entry point's generic job-failed code with the
     * row transition's own {@code IllegalStateException} rendered by the batch framework's step logger,
     * frames included, which is the same unreadable shape the insert-side code was introduced to
     * remove -- so a fix that stopped at the constraint would have left the finding's symptom reachable
     * through the more likely interleaving of the two.</p>
     *
     * <p>Assumptions: the winner's close is applied as a direct committed update rather than through a
     * second writer, because what this case has to be right about is the LOSER's report, and driving a
     * real second writer would decide the order by timing and pass while asserting nothing on the runs
     * where the order came out the other way. The row it closes is the row this attempt genuinely opened,
     * so the state the loser meets is the state the race produces.</p>
     *
     * <p>Assumptions: the winner's recorded outcome is asserted to survive intact. A losing attempt that
     * overwrote it would report the collision and still corrupt the ledger, leaving a redrive to read a
     * tier that belongs to neither attempt.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the attempt whose outcome a competitor recorded first is refused by a named code")
    void theAttemptThatLosesTheOutcomeRaceIsRefusedByName() {
        BatchStepLedgerWriter loser = new BatchStepLedgerWriter(this.repository, LOSER_CLOCK);
        Long rowId = loser.openAttempt(RUN_ID, STEP_NAME);
        commitStatement("UPDATE batch.batch_run SET status = 'COMPLETED', finished_at = ?,"
                        + " return_code = 0 WHERE id = ?",
                Timestamp.valueOf(STARTED_AT.plusHours(2)), rowId);
        this.entityManager.clear();

        Throwable refused =
                catchThrowable(() -> loser.closeAttempt(rowId, BatchReturnCode.CLEAN, false));

        assertThat(refused)
                .isInstanceOf(BatchStepLedgerWriter.StepOutcomeAlreadyRecordedException.class)
                .hasNoCause()
                .hasMessageContaining(BatchStepLedgerWriter.OUTCOME_CODE_STEP_OUTCOME_TAKEN)
                .hasMessageContaining(BatchStepLedgerWriter.OUTCOME_TAKEN_REASON)
                .hasMessageContaining(RUN_ID)
                .hasMessageContaining(STEP_NAME);
        // WHY : Assumptions: the empty trace is asserted in its own right, because the framework's step
        //       logger renders whatever escapes a step WITH its frames -- that is where the 58 lines came
        //       from -- so suppressing them on the throwable is the only place the rendering can be
        //       prevented, and a populated trace here would reinstate the wall while every assertion
        //       above still passed.
        assertThat(refused.getStackTrace())
                .as("a diagnosed condition carries no frames, so the framework renders none")
                .isEmpty();

        this.entityManager.clear();
        BatchRun surviving = this.repository.findById(rowId).orElseThrow();
        assertThat(surviving.getStatus())
                .as("the recorded outcome belongs to the attempt that reached it first")
                .isEqualTo(BatchRunStatus.COMPLETED);
        assertThat(surviving.getReturnCode()).isEqualTo(RETURN_CODE_CLEAN);
        assertThat(this.repository.count())
                .as("the refused attempt added no row of its own")
                .isEqualTo(1L);
    }

    /**
     * Confirms an integrity refusal that is not the collision reaches the caller exactly as it arrived.
     *
     * <p>Assumptions: identity is asserted rather than type, because the contract is that the OTHER
     * refusal keeps its own diagnosis. A translation that caught the refusal and raised a fresh
     * exception of the same class would satisfy a type assertion while discarding the message, the cause
     * chain and the constraint the engine actually named -- which is the diagnosis a data defect is
     * repaired from.</p>
     *
     * <p>Assumptions: the refusal handed to the writer is one the DATABASE raised, captured from the
     * status-domain violation above, so the case does not turn on a constructed exception behaving the
     * way a real one is assumed to.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an integrity refusal that is not the collision keeps its own diagnosis")
    void anUnrelatedIntegrityRefusalPropagatesUnchanged() {
        Throwable captured = catchThrowable(() -> commitStatement(
                "INSERT INTO batch.batch_run (run_id, step_name, status, started_at)"
                        + " VALUES (?, ?, 'RUNNING', ?)",
                RUN_ID, STEP_NAME, STARTED_AT));
        assertThat(captured).isInstanceOf(DataIntegrityViolationException.class);
        DataIntegrityViolationException refusal = (DataIntegrityViolationException) captured;
        BatchStepLedgerWriter writer =
                new BatchStepLedgerWriter(repositoryRefusingSaveWith(refusal), LOSER_CLOCK);

        assertThat(catchThrowable(() -> writer.openAttempt(RUN_ID, STEP_NAME)))
                .as("the refusal reaches the caller as the engine raised it, not relabelled")
                .isSameAs(refusal);
    }

    /**
     * Builds a repository view that reports no recorded row while writing through to the real table.
     *
     * <p>Assumptions: the view is a delegating mock rather than a spy, because the injected repository
     * is a framework proxy and a delegating default answer is the supported way to override one method
     * of an instance whose class cannot be subclassed. Every other call -- the insert, the count, the
     * keyed read the assertions use -- reaches the real repository and therefore the real table.</p>
     *
     * @return the view, never {@code null}
     */
    private BatchRunRepository repositoryBlindToRecordedRows() {
        BatchRunRepository blind = mock(BatchRunRepository.class,
                withSettings().defaultAnswer(delegatesTo(this.repository)));
        doReturn(Optional.empty()).when(blind).findByRunIdAndStepName(anyString(), anyString());
        return blind;
    }

    /**
     * Builds a repository view that reports no recorded row and refuses the write with one refusal.
     *
     * <p>Assumptions: the read is arranged as well as the write, so the writer takes its insert branch
     * and the refusal is raised where a real insert would raise it rather than on an update.</p>
     *
     * @param refusal the refusal the write raises, captured from the engine; must not be {@code null}
     * @return the view, never {@code null}
     */
    private BatchRunRepository repositoryRefusingSaveWith(DataIntegrityViolationException refusal) {
        BatchRunRepository refusing = mock(BatchRunRepository.class,
                withSettings().defaultAnswer(delegatesTo(this.repository)));
        doReturn(Optional.empty()).when(refusing).findByRunIdAndStepName(anyString(), anyString());
        doThrow(refusal).when(refusing).save(any(BatchRun.class));
        return refusing;
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
     * Confirms every column and every constraint of the ledger carries a comment in the catalogue.
     *
     * <p>Purpose: {@code V1__batch.sql} states that its comments exist so an operator inspecting the
     * table from a session -- during a failed nightly run, with no access to this repository -- reads
     * the reasoning without the file. That claim was only three-quarters true. It issues a
     * {@code COMMENT ON COLUMN} for seven of its eight columns, leaving {@code attempt} undescribed,
     * and it comments none of its seven constraints -- so the operator holding a constraint name
     * returned by a failed insert had nothing in the catalogue to resolve it against, and the one
     * column a redrive raises first was the one column with no comment.
     * {@code V3__batch_run_contract_restatement.sql} writes the missing commentary, and this case is
     * what holds it there.</p>
     *
     * <p>Refactoring Rationale: the assertion is that NO column and NO constraint is uncommented,
     * rather than that particular comments carry particular words. Asserting text would pin the prose
     * and fail on an improvement to it; asserting completeness fails on the thing that actually goes
     * wrong, which is a column or a rule added later and left undocumented. That is the same defect
     * this case exists because of.</p>
     *
     * <p>Assumptions: the catalogue is read rather than the migration text, because the question is
     * what a session can see. A comment present in the file and absent from the database -- which is
     * exactly what a migration applied under a role without ownership produces, since {@code COMMENT}
     * requires it -- would pass a text assertion and fail this one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every ledger column and constraint carries a comment a session can read")
    void everyColumnAndConstraintCarriesACatalogueComment() {
        List<String> uncommentedColumns = this.jdbc.queryForList(
                "SELECT a.attname FROM pg_attribute a"
                        + " WHERE a.attrelid = 'batch.batch_run'::regclass"
                        + " AND a.attnum > 0 AND NOT a.attisdropped"
                        + " AND col_description(a.attrelid, a.attnum) IS NULL"
                        + " ORDER BY a.attname",
                String.class);

        assertThat(uncommentedColumns)
                .as("every column of the step ledger must be described in the catalogue; an operator"
                        + " reading the table from a session has nothing else, and the column a"
                        + " redrive raises first is the one that was undescribed")
                .isEmpty();

        List<String> uncommentedConstraints = this.jdbc.queryForList(
                "SELECT c.conname FROM pg_constraint c"
                        + " WHERE c.conrelid = 'batch.batch_run'::regclass"
                        + " AND obj_description(c.oid, 'pg_constraint') IS NULL"
                        + " ORDER BY c.conname",
                String.class);

        assertThat(uncommentedConstraints)
                .as("a violated constraint reports its own name and nothing else, so every named rule"
                        + " must be resolvable to its meaning from the catalogue the operator already"
                        + " has open")
                .isEmpty();
    }

    /**
     * Confirms the attempt counter and the re-opened start time are durable across a re-open.
     *
     * <p>Purpose: the counter is the only record that a step was tried more than once. Because
     * {@code uq_batch_run_run_step} admits one row per run and step, a redrive cannot insert a second
     * row -- so the recorded row is re-opened in place and the count is what distinguishes a first
     * attempt from a fourth. A counter that lived only in the entity would be lost on every reload.</p>
     *
     * <p>Purpose: the start time is asserted alongside the counter because the two are written by the
     * same transition and only one of them was being checked. {@code started_at} was mapped
     * {@code updatable = false} while the constructor was its only writer, and {@code reopen} was added
     * afterwards -- so the provider omitted the column from the UPDATE and a redriven row durably kept
     * the FIRST attempt's start while its counter said two. An operator reading a recovered night takes
     * that column as the start of the attempt that is running, so the stale value is worse than a
     * missing one.</p>
     *
     * <p>Assumptions: the row is read back through plain SQL after the transaction commits rather than
     * through the persistence context that wrote it, because a context read can be answered from the
     * first-level cache and would pass on a column the database never stored. The column, its default
     * and its constraint are what this case is about.</p>
     *
     * <p>Assumptions: the artifacts this case pins are the {@code DEFAULT 1} on the counter column and
     * {@code ck_batch_run_attempt} in {@code V1__batch.sql}. No reference line is cited for the counter
     * because the baseline counted nothing: a rerun of a mainframe step left no trace that it was the
     * second attempt, which is the gap {@code app/jcl/DEFGDGD.jcl} L2 hints at in a comment and never
     * closes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a re-open durably counts the attempt, restarts the clock and refuses a count below one")
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
        LocalDateTime secondAttemptStart = FINISHED_AT.plusMinutes(30);
        this.transactionTemplate.executeWithoutResult(status -> {
            BatchRun recorded = this.repository.findByRunIdAndStepName(RUN_ID, STEP_NAME)
                    .orElseThrow();
            recorded.reopen(secondAttemptStart);
        });
        this.entityManager.clear();

        Map<String, Object> reopened = this.jdbc.queryForMap(
                "SELECT attempt, status, started_at, finished_at, return_code FROM batch.batch_run"
                        + " WHERE run_id = ? AND step_name = ?", RUN_ID, STEP_NAME);
        assertThat(reopened.get("attempt"))
                .as("re-opening the recorded row counts the attempt rather than inserting a second row")
                .isEqualTo(2);
        assertThat(reopened.get("status")).isEqualTo(BatchRun.BatchRunStatus.STARTED.name());
        LocalDateTime storedStart = ((Timestamp) reopened.get("started_at")).toLocalDateTime();
        assertThat(storedStart)
                .as("the stored start must be the re-opened attempt's, not the first attempt's, or the"
                        + " column reads as the start of work that finished half an hour earlier")
                .isEqualTo(secondAttemptStart);
        assertThat(storedStart)
                .as("and it must have moved off the value the first attempt wrote")
                .isNotEqualTo(STARTED_AT);
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
     * <p>Assumptions: the artifact this case pins is {@code ck_batch_run_status} in
     * {@code V1__batch.sql}, and the three values it closes the domain at are the constants
     * {@code BatchRun.BatchRunStatus} declares. No reference line is cited because a lifecycle state is
     * something only the target's ledger records.</p>
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
     * <p>Assumptions: the artifact this case pins is {@code ck_batch_run_lifecycle} in
     * {@code V1__batch.sql}, read from two of its three arms. No reference line is cited because the
     * coherence being enforced is between columns the baseline never had.</p>
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
     * <p>Assumptions: the artifact this case pins is {@code ck_batch_run_finished_after_started} in
     * {@code V1__batch.sql}. No reference line is cited because the baseline recorded neither instant,
     * so there is no prior ordering rule for this one to reproduce.</p>
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
     *     which the duplicate-pair case above provokes deliberately
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
