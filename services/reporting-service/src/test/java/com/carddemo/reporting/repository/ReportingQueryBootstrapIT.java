package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Proves that every query the five repository roles declare parses against the real metamodel.
 *
 * <h2>Purpose</h2>
 *
 * <p>Four of the five roles declare their queries by method name and the fifth writes them out, and
 * both forms fail the same way: a property the metamodel does not carry, an entity join on a path that
 * does not exist, or a projection accessor with no matching alias is a defect the compiler cannot see.
 * All of them surface at one moment -- when the repository proxy is created -- because that is when
 * each declared query is handed to the entity manager and parsed. This class provokes that moment and
 * asserts that all five proxies came into being.
 *
 * <p>Assumptions: the assertion is deliberately that the beans exist and not that a query returned
 * rows. No relation is created in the container at all, so a query cannot be executed here -- and it
 * does not need to be, because parsing happens against the metamodel rather than against the
 * catalogue. Alternatives Considered: creating the seven views in the container and asserting on rows,
 * which would additionally prove the mapping matches the relations. Rejected because those views read
 * base tables that four <em>other</em> services' migrations create, so reproducing them here would
 * mean reproducing four migrations this module does not own, and the copy would drift from the
 * originals silently. The narrower assertion is the one this module can make truthfully.
 *
 * <p>Trade-offs: this class starts a database engine to validate query strings, which is a heavy
 * mechanism for a syntactic property. It is accepted because the alternative -- bootstrapping an
 * entity manager factory with no connection -- requires pinning a dialect and suppressing metadata
 * access in a test, which are exactly the settings that would let a query pass here and fail against
 * the engine the service runs on. The engine is real for the same reason the sibling
 * transaction-service integration tests give for theirs.
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises outside the test engine, so the type
 * accepts no parameter, returns nothing and raises nothing. The inapplicability is stated rather than
 * passed over, because user-specified Rule 1 (Explainability) forbids a docstring that omits
 * parameters, return values or exceptions.
 */
@Testcontainers
@SpringBootTest(
        classes = ReportingQueryBootstrapIT.ReportingQueryTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: a region is REQUIRED and is supplied here rather than in the shared test
    //       profile. The cloud auto-configuration this module carries resolves a region eagerly and
    //       raises when it finds none, so a context that enables auto-configuration at all needs one
    //       even though this class contacts no cloud service. It is supplied per test rather than in
    //       the profile because the profile's own register of deliberate omissions rules that a
    //       cloud-service location belongs to the test that needs it, and a region is the least
    //       location-like of those values but is still one.
    "spring.cloud.aws.region.static=us-east-1",
    // WHY : Assumptions: the two credential values are literal and fabricated, and they are supplied
    //       for the same eager-resolution reason. They are not a secret of any kind: no call is made,
    //       so nothing is signed with them, and the strings identify nothing that exists.
    "spring.cloud.aws.credentials.access-key=not-a-real-key",
    "spring.cloud.aws.credentials.secret-key=not-a-real-secret",
    // WHY : Assumptions: both remote configuration importers are disabled outright rather than
    //       pointed at an emulator. An importer left enabled would try to read a parameter store and a
    //       secret store during environment preparation, which happens before any assertion and would
    //       fail this class on a network timeout rather than on a query defect.
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class ReportingQueryBootstrapIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one the sibling transaction-service integration tests already
     * pin, so this module and that one validate against one engine build rather than two. It is a
     * digest rather than a tag because a publisher moves the major-line tag to each new minor release,
     * so a tag would let the engine change between two runs of an unchanged repository -- and the
     * property under test here, whether a query parses, is one an engine version can genuinely
     * change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container the context connects to, started once for this class.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text. The container's port is assigned at run time, so a written connection string
     * would either address nothing or address whichever database happens to be listening on the
     * author's machine -- and the second failure mode is the worse of the two, because it passes.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The report-side query surface, injected so that its declared queries are parsed.
     */
    @Autowired
    private TransactionReportRepository transactionReports;

    /**
     * The card-ordered transaction traversal, injected so that its derived queries are parsed.
     */
    @Autowired
    private StatementTransactionRepository statementTransactions;

    /**
     * The sequential cross-reference traversal, injected so that its derived queries are parsed.
     */
    @Autowired
    private StatementCardXrefRepository cardXrefs;

    /**
     * The keyed customer lookup, injected so that its derived query is parsed.
     */
    @Autowired
    private StatementCustomerRepository customers;

    /**
     * The keyed account lookup, injected so that its derived query is parsed.
     */
    @Autowired
    private StatementAccountRepository accounts;

    /**
     * Asserts that all five repository proxies were created, which means every declared query parsed.
     *
     * <p>Assumptions: this single assertion is not thin, and the reason is worth stating because it
     * looks thin. The work is done by the context refresh that precedes it: Spring Data creates one
     * proxy per interface and, in doing so, resolves every derived method name against the metamodel
     * and hands every written query to the entity manager to parse. A property that does not exist, a
     * join path that does not resolve or a projection accessor with no matching alias fails the
     * refresh, so this method never runs. Asserting the five references are present is what makes that
     * refresh a test result rather than a silent precondition.</p>
     */
    @Test
    @DisplayName("every declared query of all five repository roles parses against the metamodel")
    void everyDeclaredQueryParses() {
        assertThat(transactionReports).as("the report-side query surface").isNotNull();
        assertThat(statementTransactions).as("the card-ordered transaction traversal").isNotNull();
        assertThat(cardXrefs).as("the sequential cross-reference traversal").isNotNull();
        assertThat(customers).as("the keyed customer lookup").isNotNull();
        assertThat(accounts).as("the keyed account lookup").isNotNull();
    }

    /**
     * The narrowest context that can create a repository proxy for this package.
     *
     * <p>Assumptions: the configuration is nested rather than declared as a file of its own, which
     * keeps this directory to the two files it needs. It enables auto-configuration and names the two
     * packages explicitly rather than component-scanning from the module root, and that narrowness is
     * load-bearing: scanning the module root would instantiate the orchestration client bean and the
     * filter chain, so a context started to parse a query would additionally need a state machine
     * identifier and an object-store bucket, and a failure to supply either would read as a query
     * defect.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class ReportingQueryTestApplication {
    }
}
