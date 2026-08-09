package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.carddemo.reference.domain.TransactionType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the transaction-type queries against a real PostgreSQL engine.
 *
 * <p>This is the normative browse of the reference context, so the properties asserted here are the
 * ones every other walk in the package is a variation of: the seed migration's row count, the ascending
 * and descending orders under a declared-width character column's collation, the strictness of both
 * bounds, and the filter arms each tolerating an absent argument.
 *
 * <p>Assumptions: these are engine behaviours and not repository arithmetic. Whether {@code CHAR(2)}
 * orders the way the baseline's {@code ORDER BY TR_TYPE} orders, and whether a {@code like} with an
 * escape clause behaves as the pattern helper assumes, cannot be established by a test double -- a
 * double would answer whatever it was told and every assertion would pass while proving nothing.
 */
class TransactionTypeRepositoryIT extends ReferencePersistenceBase {

    /** The number of types the seed migration loads, which is also the published window. */
    private static final int SEEDED_TYPES = 7;

    /** The repository under test. */
    @Autowired
    private TransactionTypeRepository types;

    /** Supplies the transaction the insert statement requires, one per write. */
    // WHY : Refactoring Rationale: the insert cases below run through this template rather than calling
    //       the repository directly, and the reason was measured rather than anticipated. A derived or
    //       declared modifying statement carries no transaction of its own -- only the inherited save,
    //       delete and flush members are annotated by the framework's base implementation -- so calling
    //       insertType outside a transaction raised InvalidDataAccessApiUsageException reporting "No
    //       active transaction for update or delete query" and never reached the constraint at all. The
    //       template supplies the boundary that TransactionTypeService.create supplies in production,
    //       so what is exercised here is the statement AS THE SERVICE ISSUES IT.
    // WHY : Alternatives Considered: annotating this class @Transactional and letting the framework roll
    //       each case back. Rejected for the same reason the auth context's identity suite records: a
    //       UNIQUE constraint is evaluated when its statement executes, and one ambient transaction
    //       spanning a whole case makes the point at which a refusal arrives depend on when the context
    //       happens to flush. Committing each write on its own makes each refusal attributable to the
    //       statement that caused it.
    // WHY : Alternatives Considered: declaring insertType @Transactional on the repository interface so
    //       no caller has to supply a boundary. Rejected because it would let the insert commit on its
    //       own when a service method that already owns a transaction calls it -- exactly the case the
    //       create path is, where the row and the identity-provider compensation must share one unit of
    //       work -- and because the sibling auth repository's insert is declared the same way, so the
    //       two contexts would then disagree about who owns the boundary.
    @Autowired
    private TransactionTemplate commit;

    /**
     * Confirms the seed migration loads exactly the seven types the window is sized for.
     *
     * <p>Assumptions: this is asserted first because several cases below depend on it. The
     * {@code repository/package-info.java} charter records the same number and warns that a test
     * expecting a further page on seeded data alone has to insert an eighth row first.
     */
    @Test
    @DisplayName("the seed migration loads exactly seven transaction types")
    void theSeedLoadsSevenTypes() {
        List<TransactionType> all = this.types.findAllByOrderByTypeCdAsc(Limit.of(100));
        assertThat(all).hasSize(SEEDED_TYPES);
        assertThat(all).extracting(TransactionType::getTypeCd)
                .as("the engine's ordering of a declared-width character column, not a sorted list")
                .containsExactly("01", "02", "03", "04", "05", "06", "07");
    }

    /**
     * Confirms the forward walk is bounded and excludes the position it is given.
     */
    @Test
    @DisplayName("the forward walk excludes its position and honours the bound")
    void theForwardWalkIsStrictAndBounded() {
        List<TransactionType> page = this.types.findByTypeCdGreaterThanOrderByTypeCdAsc("02",
                Limit.of(3));
        assertThat(page).extracting(TransactionType::getTypeCd).containsExactly("03", "04", "05");
    }

    /**
     * Confirms the backward walk reads descending, excludes its position and honours the bound.
     */
    @Test
    @DisplayName("the backward walk reads descending, excludes its position and honours the bound")
    void theBackwardWalkIsStrictDescendingAndBounded() {
        List<TransactionType> page = this.types.findByTypeCdLessThanOrderByTypeCdDesc("05",
                Limit.of(3));
        assertThat(page).extracting(TransactionType::getTypeCd).containsExactly("04", "03", "02");
    }

    /**
     * Confirms the keyed finder resolves a seeded code and reports an unseeded one as absent.
     */
    @Test
    @DisplayName("the keyed finder resolves a seeded code and reports an unknown one absent")
    void theKeyedFinderResolvesASeededCode() {
        assertThat(this.types.findByTypeCd("01")).isPresent();
        assertThat(this.types.findByTypeCd("99")).isEmpty();
    }

    /**
     * Confirms the filtered forward walk narrows to one code and still excludes its position.
     *
     * <p>Assumptions: the strictness of this bound is asserted against a real engine because it was
     * changed. The query declared an inclusive comparison, matching the baseline cursor literally, while
     * the sealed position this service publishes carries the last code the caller received -- so an
     * inclusive comparison would have returned that row a second time.
     */
    @Test
    @DisplayName("the filtered forward walk narrows by code and excludes its position")
    void theFilteredForwardWalkNarrowsAndIsStrict() {
        assertThat(this.types.findFilteredPageAfter("04", null, null, Limit.of(8)))
                .extracting(TransactionType::getTypeCd).containsExactly("04");
        assertThat(this.types.findFilteredPageAfter("04", null, "04", Limit.of(8)))
                .as("the position is excluded, so narrowing to it and starting from it yields nothing")
                .isEmpty();
    }

    /**
     * Confirms an absent filter argument admits every row rather than matching none.
     *
     * <p>Assumptions: this is the arm the baseline expresses with a guard flag, because a one-byte field
     * cannot represent its own absence. The migrated form relies on the engine evaluating
     * {@code :arg is null or ...}, which is exactly what a double could not establish.
     */
    @Test
    @DisplayName("an absent filter argument admits every row")
    void anAbsentFilterAdmitsEveryRow() {
        assertThat(this.types.findFilteredPageAfter(null, null, null, Limit.of(100)))
                .hasSize(SEEDED_TYPES);
    }

    /**
     * Confirms the description filter matches by containment with the caller's metacharacters escaped.
     */
    @Test
    @DisplayName("the description filter matches by containment and escapes the caller's wildcards")
    void theDescriptionFilterMatchesByContainment() {
        Optional<TransactionType> first = this.types.findByTypeCd("01");
        assertThat(first).isPresent();
        String fragment = first.get().getDescription().trim();

        String pattern = TransactionTypeRepository.descriptionFilterPattern(fragment);
        assertThat(this.types.findFilteredPageAfter(null, pattern, null, Limit.of(100)))
                .isNotEmpty();

        String literalWildcard = TransactionTypeRepository.descriptionFilterPattern("%");
        assertThat(this.types.findFilteredPageAfter(null, literalWildcard, null, Limit.of(100)))
                .as("an escaped percent must match a literal percent, not every row")
                .isEmpty();
    }

    /**
     * Confirms the count answers the filter question without any reference to a position.
     */
    @Test
    @DisplayName("the count answers whether the filter matches anything anywhere")
    void theCountIgnoresAnyPosition() {
        assertThat(this.types.countFilterMatches(null, null)).isEqualTo(SEEDED_TYPES);
        assertThat(this.types.countFilterMatches("01", null)).isEqualTo(1L);
        assertThat(this.types.countFilterMatches("99", null))
                .as("a filter matching nothing is a field refusal upstream, not an empty page")
                .isZero();
    }

    /**
     * Confirms the unique constraint refuses a duplicate code at the statement rather than at commit.
     *
     * <p>Purpose: this is the timing the service's duplicate classification depends on. The service
     * catches {@code DataIntegrityViolationException} around its insert so it can read the SQLSTATE and
     * answer the referential conflict the contract publishes. That catch can only run if the INSERT is
     * issued inside it -- a plain {@code save} registers the row with the persistence context and the
     * statement goes out when the context is flushed, which for a transactional method is at commit,
     * after the catch has returned.</p>
     *
     * <p>Assumptions: {@code insertType} is what the service now calls, so that is what is exercised
     * here. A double could not establish this: whether the violation arrives from this call or from a
     * later commit is the engine's and the persistence provider's behaviour, and a mock would raise
     * whenever it was told to. The sibling case below records WHY a save is not what is called, which is
     * a measured property of this entity rather than a preference.</p>
     *
     * <p>Assumptions: a SEEDED code is reused rather than one inserted by this case, so the row the
     * constraint collides with is committed and outside this test's own unit of work.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a duplicate code is refused by the flush and not deferred to commit")
    void aDuplicateCodeIsRefusedByTheFlush() {
        Optional<TransactionType> seeded =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(1)).stream().findFirst();
        assertThat(seeded).as("the seed migration must have loaded a row to collide with").isPresent();

        assertThatThrownBy(() -> this.commit.executeWithoutResult(status ->
                this.types.insertType(seeded.get().getTypeCd(), "Duplicate of a seeded code")))
                .as("the constraint must refuse the insert the service issues")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Confirms that a save CANNOT insert this entity, which is why the explicit insert exists.
     *
     * <p>Purpose: this is the measurement that produced {@code insertType}, kept as a case so the
     * reasoning cannot be undone by someone "simplifying" the service back to a save. The entity carries
     * an assigned {@code String} identifier and a primitive {@code long} version, so the framework's
     * newness test can find neither a null version nor a null identifier and routes the save through
     * {@code merge} -- which loads the row that identifier names and writes an UPDATE against it.</p>
     *
     * <p>Assumptions: what is asserted is that the save does NOT raise the integrity violation the
     * service classifies. Whether it raises an optimistic-locking failure or silently updates depends on
     * whether the stored version equals the zero on the new instance, and both outcomes are wrong in the
     * same way -- neither reaches the duplicate classification. Asserting the absence of the integrity
     * violation states exactly the property that matters and does not depend on which of the two the
     * seeded row's version happens to produce.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a save cannot insert this entity, so the duplicate never reaches the integrity branch")
    void aSaveCannotInsertThisEntity() {
        Optional<TransactionType> seeded =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(1)).stream().findFirst();
        assertThat(seeded).isPresent();

        TransactionType duplicate =
                new TransactionType(seeded.get().getTypeCd(), "Merged rather than inserted");

        assertThat(catchThrowableOfType(DataIntegrityViolationException.class,
                () -> this.types.saveAndFlush(duplicate)))
                .as("a save reaches merge, so the duplicate does not arrive as an integrity violation")
                .isNull();
    }

    /**
     * Confirms the state the service classifies on is the state the engine actually reports.
     *
     * <p>Purpose: the service reads the SQLSTATE out of the violation's cause chain and branches on
     * {@code 23505} to answer the duplicate as a referential conflict. The literal it compares against is
     * only correct if this engine reports that state for this constraint, and nothing in the codebase
     * establishes that except a real violation.</p>
     *
     * <p>Assumptions: the state is read by walking the cause chain for a {@link java.sql.SQLException},
     * which is how the service reads it, rather than by matching a provider-specific exception subclass.
     * Asserting it the same way the production code reads it is what makes this case evidence for that
     * code and not for a different mechanism.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the engine reports SQLSTATE 23505 for the duplicate the service classifies")
    void theEngineReportsTheClassifiedSqlState() {
        Optional<TransactionType> seeded =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(1)).stream().findFirst();
        assertThat(seeded).isPresent();

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> this.commit.executeWithoutResult(status ->
                        this.types.insertType(seeded.get().getTypeCd(), "Second insert")));

        assertThat(refusal).isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("the state the service branches on when it answers a duplicate create")
                .isEqualTo("23505");
    }

    /**
     * Reads the SQLSTATE out of a violation the way the service under test reads it.
     *
     * @param failure the violation the provider raised; must not be {@code null}
     * @return the first non-blank SQLSTATE found by walking the cause chain, or {@code null} when the
     *     chain reports none
     */
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.sql.SQLException reported
                    && reported.getSQLState() != null
                    && !reported.getSQLState().isBlank()) {
                return reported.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}

/**
 * The container fixture every repository integration test in this package shares.
 *
 * <p>Assumptions: this is declared as a second, package-private, top-level type in this file rather than
 * as a file of its own, exactly as {@code package-info.java} in this package rules. A separate file would
 * take the package's closed set from eight compilation units to nine and break the property the sibling
 * test packages of this module rely on when they state their own inventories. A second top-level type in
 * one file is legal Java -- the restriction is one PUBLIC top-level type per file -- and it passes the
 * audit because {@code config/checkstyle/checkstyle.xml} configures neither the one-top-level-type module
 * nor the outer-type-filename module.
 *
 * <p>Assumptions: the container field is {@code static} on this base, so ONE engine is started for the
 * whole package rather than one per test class. Seven containers would multiply the slowest part of the
 * build by seven and would additionally run Flyway seven times over identical migrations.
 *
 * <p>Trade-offs: the base type is findable only by opening the class that hosts it, which is the cost
 * accepted for the closed-set property above, and it is why the hosting file is named in the package
 * charter rather than left to a search.
 */
@SpringBootTest(
        classes = ReferencePersistenceBase.ReferencePersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class ReferencePersistenceBase {

    // WHY : Assumptions: the image is named by DIGEST rather than by the tag postgres:17-alpine, for the
    //       reason the ledger context's integration tests record at length: that tag pins the MAJOR line
    //       only, and the properties under test here are engine behaviours -- the collation a
    //       declared-width character column is ordered in, and how a like clause with an escape
    //       character treats a caller's own metacharacter. A moving tag would let either change between
    //       two runs of unchanged repositories, so a failure could not be attributed and a silently
    //       altered ordering could keep every assertion green while proving something different.
    // WHY : Assumptions: this is the SAME digest the ledger context pins, so the two suites cannot
    //       disagree about engine behaviour, and the image is already present wherever either has run.
    // WHY : Trade-offs: a digest is unreadable, so the engine version it denotes survives only in this
    //       comment and has to be updated with the value. That is the trade this repository's workflows
    //       already accept when they pin an action to a commit identifier with the tag written beside it.
    /** PostgreSQL 17 on Alpine, pinned by manifest digest rather than by a moving tag. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    // WHY : Refactoring Rationale: the engine is started HERE, in a static initialiser, and the class
    //       carries neither @Testcontainers nor @Container. Those two were tried first and produced a
    //       failure worth recording, because it does not look like a lifecycle problem: the JUnit
    //       extension owns a @Container field for the duration of ONE test class, so the engine was
    //       stopped when the first class in this package finished while the framework's cached
    //       application context -- which is shared across all six classes, because they declare the same
    //       configuration and profile -- went on pointing at it. Every later class then failed on
    //       connection acquisition after a ten-second pool timeout, reporting an empty pool rather than a
    //       stopped container, so the reported cause named the pool and not the lifecycle.
    // WHY : Assumptions: nothing stops the engine explicitly and nothing needs to. Testcontainers'
    //       companion reaper container removes it when the test JVM exits, which is the mechanism the
    //       annotation-driven form relies on for its own cleanup too; the difference here is only WHEN
    //       the shutdown is triggered, not whether it happens.
    // WHY : Alternatives Considered: keeping @Container and letting each class start its own engine.
    //       Rejected because it multiplies the slowest part of this module's build by six and runs the
    //       same two migrations six times, and because the framework would still cache one context
    //       across the six classes -- so the later classes would be pointed at the FIRST engine while
    //       five more sat idle.
    // WHY : Assumptions: the connection details reach the context DECLARATIVELY, through
    //       @ServiceConnection, and this is the mechanism this module's own
    //       src/test/resources/application-test.yml already depends on. That file deliberately omits
    //       spring.flyway.user and spring.flyway.password and records why: the container's
    //       @ServiceConnection is adapted into a Flyway connection-details bean, so the container's own
    //       generated credential migrates the schema and no stand-in credential has to be invented.
    // WHY : Alternatives Considered: @DynamicPropertySource, which this package's charter ruled for on
    //       the grounds that no dependency should be added to obtain a shorter annotation. Tried, and
    //       measured to fail: publishing the three spring.datasource keys leaves spring.flyway.user bound
    //       to an unset placeholder, and every context load failed authenticating as the literal
    //       placeholder text. Publishing Flyway's two keys as well would restate keys the same charter
    //       forbids a class here from restating, so the ruling could not be met in either direction. The
    //       ruling is withdrawn in that charter rather than worked around here.
    /** The one engine every test class in this package runs against, started once for the JVM. */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    /**
     * The minimal application this package's tests are bootstrapped from.
     *
     * <p>Assumptions: entities and repositories are scanned explicitly rather than the service's own
     * entry point being used, because that entry point pulls in the security chain, the messaging
     * listener and the interface document, none of which a persistence test exercises and each of which
     * would then need configuration a persistence test has no reason to supply.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reference.domain")
    @EnableJpaRepositories("com.carddemo.reference.repository")
    static class ReferencePersistenceTestApplication {
    }
}
