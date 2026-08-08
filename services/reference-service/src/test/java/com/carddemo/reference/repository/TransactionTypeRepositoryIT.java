package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
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
}

/**
 * The container fixture every repository integration test in this package shares.
 *
 * <p>Assumptions: this is declared as a second, package-private, top-level type in this file rather than
 * as a file of its own, exactly as {@code package-info.java} in this package rules. A separate file would
 * take the package's closed set from seven compilation units to eight and break the property the sibling
 * test packages of this module rely on when they state their own inventories. A second top-level type in
 * one file is legal Java -- the restriction is one PUBLIC top-level type per file -- and it passes the
 * audit because {@code config/checkstyle/checkstyle.xml} configures neither the one-top-level-type module
 * nor the outer-type-filename module.
 *
 * <p>Assumptions: the container field is {@code static} on this base, so ONE engine is started for the
 * whole package rather than one per test class. Six containers would multiply the slowest part of the
 * build by six and would additionally run Flyway six times over identical migrations.
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
