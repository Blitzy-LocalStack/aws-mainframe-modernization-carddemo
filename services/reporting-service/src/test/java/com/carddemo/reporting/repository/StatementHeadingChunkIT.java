package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.reporting.repository.StatementCardXrefRepository.StatementHeadingRow;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the statement heading walk visits every card exactly once, in the declared order.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code StatementCardXrefRepository.findHeadingChunk} pages a relation ordered by the masked
 * card rendering and then by the per-card fingerprint. A keyset predicate is correct only when it
 * reproduces the whole of that order; one that compares a single component silently skips cards and
 * repeats others, and neither outcome raises anything. This class walks a seeded relation in chunks
 * exactly as {@code StatementService.generateStatements} does and asserts the visited sequence is
 * the declared sequence, once each.
 *
 * <p>Refactoring Rationale: this class exists because the defect it pins reached this repository and
 * no gate here could see it. The predicate compared the fingerprint alone while the ordering led on
 * the masked rendering; it parsed, so the sibling {@code ReportingQueryBootstrapIT} passed, and the
 * unit tests of {@code StatementService} stub the repository, so a stand-in answered whatever it was
 * arranged to answer. The property is a relationship between a predicate and an ORDER BY over real
 * rows, which is why nothing short of an engine can assert it.
 *
 * <p>Alternatives Considered: asserting the corrected predicate from the service's unit tests by
 * verifying the arguments passed. Rejected as insufficient rather than wrong -- it is done, and it
 * proves the caller advances both components, but it cannot prove the SQL those components feed
 * selects the right rows. Also considered: extending the sibling parse-only class. Rejected because
 * that class's documented premise is that it creates no relation at all, and creating one there
 * would falsify its own rationale for a second, unrelated purpose.
 *
 * <p>Trade-offs: the relations are created as plain tables -- three by a harness script and the
 * fourth, the per-card identity relation the walk is ordered from, by this class from the harness's
 * own rows -- rather than as the real views, so this class cannot detect a mismatch between an entity
 * mapping and a view definition. That is accepted and the harness script records why at length: the real views read
 * base tables four other services' migrations create, and a copy of four migrations would drift from
 * the originals unnoticed. The narrower claim -- that the predicate reproduces its ordering -- is the
 * one this class makes, and it is a claim no other artifact in the repository makes at all.
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises outside the test engine, so it
 * accepts no parameter, returns nothing and raises nothing. The inapplicability is stated rather
 * than passed over, because user-specified Rule 1 (Explainability) forbids a docstring that omits
 * parameters, return values or exceptions.
 */
@Testcontainers
@SpringBootTest(
        classes = StatementHeadingChunkIT.HeadingChunkTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: these four properties are the same set the sibling parse-only integration
    //       test supplies and for the same reason -- this module's cloud auto-configuration resolves a
    //       region and credentials eagerly and both remote configuration importers read a remote store
    //       during environment preparation, so a context that enables auto-configuration at all fails
    //       before any assertion without them. Nothing here contacts a cloud service, so the two
    //       credential strings are literal, fabricated and identify nothing that exists.
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=not-a-real-key",
    "spring.cloud.aws.credentials.secret-key=not-a-real-secret",
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class StatementHeadingChunkIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and ordering behaviour is something
     * an engine version can genuinely change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative harness that creates and seeds the three relations this class reads.
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-reporting-relations.sql";

    /**
     * The chunk size the walk below uses, two rows.
     *
     * <p>Assumptions: two is deliberate and not a convenience. The production size is two hundred, so
     * a four-row fixture read at that size returns everything in one chunk and the continuation is
     * never exercised at all. Two forces three round trips over four rows, which is the smallest
     * shape in which a chunk boundary falls inside the fixture.</p>
     */
    private static final int CHUNK = 2;

    /**
     * The start sentinel for both continuation components, the empty string.
     *
     * <p>Assumptions: this is the value {@code StatementService} opens its walk with, and it works for
     * both components because it sorts below every non-empty value.</p>
     */
    private static final String FROM_START = "";

    /**
     * A defensive bound on the number of chunks the walk may request.
     *
     * <p>Assumptions: the bound exists because the defect being pinned does not merely misorder rows,
     * it can make the walk NON-TERMINATING -- a repeated row that keeps being re-admitted holds the
     * anchor below itself for ever. Without a bound this class would hang rather than fail, and a hung
     * build reports nothing. Ten is far above the three chunks four rows at size two require.</p>
     */
    private static final int MAX_CHUNKS = 10;

    /**
     * The container the context connects to, started once for this class with the harness applied.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text, because the container's port is assigned at run time. The init script runs once
     * at container start, so the relations exist before the context opens its first connection.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /**
     * Creates and seeds the fourth relation the walk reads, derived from the harness's rows.
     *
     * <p>Purpose: {@code findHeadingChunk} drives from {@code reporting.card_identity} -- the
     * persisted per-card relation whose two ordered columns ARE the walk's ordering tuple -- and the
     * harness script creates the three relations the walk laterals into but not that one.</p>
     *
     * <p>Refactoring Rationale: the query this class exercises used to order a barrier view directly.
     * It cannot any longer: every reporting projection is declared
     * {@code WITH (security_barrier = true)}, which confines what may be pushed below it to LEAKPROOF
     * quals, and a range comparison on text is not leakproof -- so a keyset predicate plus an ordering
     * could reach no index and each chunk materialised and sorted the whole cardholder population. The
     * relation seeded here is what the ordering is served from now, so the walk cannot be exercised at
     * all without it.</p>
     *
     * <p>Assumptions: the relation is created HERE rather than in the harness script, and the reason is
     * a boundary rather than a preference -- this class owns its Java and the shared harness resource
     * is read by more than one artifact. Seeding it FROM the harness's own rows is also what keeps the
     * fixture single-sourced: the fingerprints and masked renderings are the harness's chosen values,
     * so the two relations cannot disagree and the discriminating arrangement the harness documents at
     * length still holds.</p>
     *
     * <p>Assumptions: the whole card number is synthesised from the fingerprint's leading characters,
     * and nothing in this class reads it. Only two properties of it matter: it is unique, which the
     * four chosen fingerprints make it, and it is sixteen characters, which the declared type
     * requires. A plausible card number would be a fiction that reads as data.</p>
     *
     * <p>Assumptions: the two ordered columns carry {@code COLLATE "C"} exactly as the real relation
     * does, so this fixture's order is bytewise for the same reason production's is and does not
     * depend on the container's {@code lc_collate}.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the relation cannot be created or seeded, which is an arrangement
     *     failure and not the property under test
     */
    @BeforeAll
    static void arrangeTheIdentityRelation() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
                Statement arrange = connection.createStatement()) {
            arrange.execute("create table reporting.card_identity ("
                    + " card_fingerprint text collate \"C\" not null,"
                    + " card_num character(16) not null,"
                    + " card_num_masked text collate \"C\" not null,"
                    + " constraint pk_card_identity primary key (card_fingerprint),"
                    + " constraint uq_card_identity_card_num unique (card_num))");
            arrange.execute("create index idx_card_identity_masked_fingerprint"
                    + " on reporting.card_identity (card_num_masked, card_fingerprint)");
            arrange.executeUpdate("insert into reporting.card_identity"
                    + " (card_fingerprint, card_num, card_num_masked)"
                    + " select card_fingerprint, substr(card_fingerprint, 1, 16), card_num"
                    + " from reporting.v_card_xref");
        }
    }

    /**
     * The repository under test, injected so the walk goes through the declared query.
     */
    @Autowired
    private StatementCardXrefRepository cardXrefs;

    /**
     * The entity manager, used only to read the two comparison sequences from the engine.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Confirms the chunked walk visits every card exactly once and in the declared order.
     *
     * <p>Assumptions: the expected sequence is read from the engine by the SAME two-key ordering the
     * query declares, rather than written out here. Writing it out would restate the fixture in a
     * second place, and the two could then disagree; reading it means the assertion compares a walk
     * against an unpaged ordering of the same rows, which is exactly the property a keyset predicate
     * has to have.</p>
     *
     * <p>Assumptions: the walk below reproduces {@code StatementService.generateStatements} rather
     * than approximating it -- it opens with both sentinels, advances BOTH components from the same
     * row, and stops on the first empty chunk. A walk that advanced them independently would be
     * testing a caller that does not exist.</p>
     */
    @Test
    @DisplayName("the chunked heading walk visits every card exactly once, in the declared order")
    void theChunkedWalkVisitsEveryCardExactlyOnceInOrder() {
        List<String> expected = declaredOrder();

        List<String> visited = new ArrayList<>();
        String afterCardNum = FROM_START;
        String afterFingerprint = FROM_START;
        for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
            List<StatementHeadingRow> chunk =
                    cardXrefs.findHeadingChunk(afterCardNum, afterFingerprint, CHUNK);
            if (chunk.isEmpty()) {
                break;
            }
            for (StatementHeadingRow row : chunk) {
                visited.add(row.getCardFingerprint());
                afterCardNum = row.getCardNum();
                afterFingerprint = row.getCardFingerprint();
            }
        }

        assertThat(expected).as("the fixture seeded four cards").hasSize(4);
        assertThat(visited)
                .withFailMessage("the chunked walk must visit every card exactly once and in the"
                        + " order the query declares; it visited %s where the unpaged ordering of the"
                        + " same rows is %s, so the continuation predicate does not reproduce its own"
                        + " ORDER BY", visited, expected)
                .containsExactlyElementsOf(expected);
    }

    /**
     * Confirms the fixture really distinguishes the declared order from fingerprint order.
     *
     * <p>Assumptions: this case asserts a property of the FIXTURE and not of the query, and it is here
     * because without it the case above could hold vacuously. If the four seeded rows happened to sort
     * identically under both orderings, a predicate comparing the fingerprint alone would walk them
     * correctly and the assertion above would pass while checking nothing. Asserting the two orderings
     * DIFFER is what keeps that from happening silently.</p>
     *
     * <p>Assumptions: both sequences are read from the engine rather than computed here, so the
     * comparison is between two orderings the engine actually produces over the seeded rows under the
     * collation the container carries -- which is the same pair of orderings the query and the
     * defective predicate would have resolved.</p>
     */
    @Test
    @DisplayName("the seeded cards order differently by the declared tuple than by fingerprint alone")
    void theSeededCardsDiscriminateTheDeclaredOrderFromFingerprintOrder() {
        List<String> declared = declaredOrder();
        List<String> byFingerprint = fingerprintOrder();

        assertThat(declared).as("the declared ordering returned every seeded card").hasSize(4);
        assertThat(byFingerprint).as("the fingerprint ordering returned every seeded card").hasSize(4);
        assertThat(byFingerprint)
                .withFailMessage("the seeded cards no longer distinguish the declared ordering from"
                        + " fingerprint order, so the walk assertion beside this one is no longer"
                        + " checking the continuation predicate; restore a card whose masked rendering"
                        + " sorts low while its fingerprint sorts high")
                .isNotEqualTo(declared);
    }

    /**
     * Reads the seeded fingerprints in the order the chunk query declares.
     *
     * @return the four seeded fingerprints ordered by masked rendering ascending and then by
     *     fingerprint ascending, never {@code null}
     */
    private List<String> declaredOrder() {
        return nativeFingerprints("select card_fingerprint from reporting.v_card_xref"
                + " order by card_num asc, card_fingerprint asc");
    }

    /**
     * Reads the seeded fingerprints in fingerprint order alone, which is the defective sequence.
     *
     * @return the four seeded fingerprints ordered by fingerprint ascending, never {@code null}
     */
    private List<String> fingerprintOrder() {
        return nativeFingerprints("select card_fingerprint from reporting.v_card_xref"
                + " order by card_fingerprint asc");
    }

    /**
     * Reads one text column as a list, in whatever order the query produced.
     *
     * <p>Assumptions: the rows are mapped through the platform's string conversion rather than cast,
     * because a character column arrives as a type the driver chooses and a cast would couple this
     * helper to that choice for no gain.</p>
     *
     * @param sql a query returning one text column, ordered by the caller
     * @return that column's values in the order the query produced them, never {@code null}
     */
    private List<String> nativeFingerprints(String sql) {
        List<?> rows = entityManager.createNativeQuery(sql).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    /**
     * The narrowest context that can create a repository proxy for this package.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason the sibling integration test
     * records: scanning the root would instantiate the orchestration client and the filter chain, so a
     * context started to read four rows would additionally need a state-machine identifier and an
     * object-store bucket, and a failure to supply either would read as a paging defect.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class HeadingChunkTestApplication {
    }
}
