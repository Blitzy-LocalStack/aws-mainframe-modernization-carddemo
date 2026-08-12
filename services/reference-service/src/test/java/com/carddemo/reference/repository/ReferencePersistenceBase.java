// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/ReferencePersistenceBase.java
// -----------------------------------------------------------------------------
// Purpose:
//      The one container fixture every repository integration test in this
//      package runs against: a single PostgreSQL engine started once for the
//      JVM, wired into a minimal application context that scans this module's
//      entities and repositories and nothing else.
//
// WHY (non-obvious design decisions):
//  (1) Refactoring Rationale: this file exists because the type it holds used to
//      live as a second top-level type inside TransactionTypeRepositoryIT.java.
//      Every one of the eight sibling classes that extends it was compiled with
//      a warning -- "auxiliary class ... should not be accessed from outside its
//      own source file" -- and those eight were the only warnings this module
//      emitted at all. The file-count property the old arrangement protected is
//      restated in package-info.java as nine compilation units instead of
//      eight; the warnings are gone.
//  (2) Assumptions: the four rationale labels used below are the PLURAL forms
//      that docs/CODE_DOCUMENTATION_STANDARD.md rules for -- Alternatives
//      Considered:, Refactoring Rationale:, Assumptions: and Trade-offs: -- so
//      that they can be found by grep before they are read by a person.
//  (3) Trade-offs: this file declares no test case. A reader scanning the
//      package for assertions finds one file that reports none, which is the
//      ordinary shape of a shared fixture and is why the package charter names
//      it explicitly.
// =============================================================================
package com.carddemo.reference.repository;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The container fixture every repository integration test in this package shares.
 *
 * <p>⚠️ Refactoring Rationale: this type used to be declared as a second, package-private, top-level type
 * inside {@code TransactionTypeRepositoryIT.java}, and the package charter argued for that arrangement on
 * the grounds that it kept the package's closed set at eight compilation units rather than nine. It is now
 * its own file, because the arrangement had a cost the argument did not account for: an auxiliary top-level
 * type accessed from another source file is a documented compiler diagnostic, and every one of the eight
 * sibling classes that extends this base emitted one. Those eight were the ONLY warnings the whole module
 * produced. A self-imposed file count is worth less than a build that reports nothing, and the property the
 * closed set was protecting -- that a reader can enumerate what a package holds -- is served just as well by
 * a charter naming nine files as by one naming eight.
 *
 * <p>Assumptions: the type stays PACKAGE-PRIVATE and keeps its name, both deliberately. Package-private is
 * what the eight subclasses resolve it by, with no import between them and it, so widening it to public
 * would advertise a fixture outside the one package entitled to use it. The name is unchanged because those
 * same eight resolve it by simple name, so a rename is eight edits and no gain.
 *
 * <p>Assumptions: the container field is {@code static} on this base, so ONE engine is started for the
 * whole package rather than one per test class. Nine containers would multiply the slowest part of the
 * build by nine and would additionally run Flyway nine times over identical migrations.
 *
 * <p>Trade-offs: this file holds no test of its own, so a reader scanning the package for cases finds one
 * file that reports none. That is accepted as the ordinary cost of a shared fixture, and it is why the
 * package charter names this file for what it is rather than leaving it to be inferred from its emptiness.
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
