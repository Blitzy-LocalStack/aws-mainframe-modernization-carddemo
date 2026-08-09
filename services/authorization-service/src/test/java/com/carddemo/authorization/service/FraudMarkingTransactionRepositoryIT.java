package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.AuthFraudRepository;
import com.carddemo.authorization.repository.AuthFraudUpserter;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.web.CursorToken;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that the fraud marking's TWO writes commit together and roll back together, on a real engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: {@code FraudMarkingService.mark} writes twice -- one row into {@code auth_fraud} through
 * a native upsert, and the authorization's own two fraud members through the persistence context -- and
 * its correctness claim is that the pair is atomic. This class is where that claim is answered, because
 * it is the only tier that can answer it: the writes reach two tables through two different mechanisms,
 * and only a real transaction manager over a real engine can show that a failure after the first leaves
 * no trace of it.
 *
 * <p>Refactoring Rationale: this class is an addition, and it replaces a claim the unit test could not
 * support. That test asserted the presence of {@code @Transactional} and the ORDER of two mock
 * interactions, which establishes that the annotation is written and that the Java called two
 * collaborators. Neither says anything about what the DATABASE does: an annotation on a method invoked
 * from inside the same bean is not applied at all, a native statement issued through an
 * {@code EntityManager} that was never enlisted commits on its own, and a mock has no rollback to
 * observe. The distance between "the annotation is present" and "the two writes are one unit of work"
 * is exactly the distance this class covers.
 *
 * <h2>How the two halves of the claim are separated</h2>
 *
 * <p>Assumptions: the claim has two halves and each needs its own case, because no single case reaches
 * both. {@link #bothWritesCommitTogether()} calls the service with NO ambient transaction, so the
 * boundary that commits is the service's own; {@link #neitherWriteSurvivesARollback()} calls it inside
 * a transaction the case then rolls back, which is what shows both writes are ENLISTED in the caller's
 * unit of work rather than committing on their own. The second is not a weaker statement of the first:
 * a native statement issued through an unenlisted connection would commit regardless of what the caller
 * decides afterwards, and that is the specific way a two-write method silently stops being atomic.
 *
 * <p>Alternatives Considered: provoking a failure INSIDE the service after its first write, by
 * presenting a fraud action outside the two-character domain. It does not reach: the projection the
 * upsert is handed is built first and {@code AuthFraudMapper.requireFraudAction} refuses the action
 * there, so nothing has been written when the refusal is raised and a case built on it would assert a
 * rollback of nothing. That ordering is itself worth knowing -- the domain guard sits ahead of the first
 * write and not between the two -- and it is why the enlistment case takes the shape it does.
 *
 * <p>Trade-offs: because the rollback is driven from outside, this class does not exhibit a failure
 * arising inside the service between its two writes. Accepted, because no such failure is reachable: the
 * projection is validated before the first write and the second is a field assignment on an already
 * loaded entity. What the pair does establish is the property that matters -- both writes reach one
 * transaction, and that transaction is the service's own when the caller supplies none.
 *
 * <p>Assumptions: every assertion about what survives is made through PLAIN JDBC on a connection of its
 * own, and never through a repository. A repository read shares the persistence context that performed
 * the writes, so an instance still held there could answer from the identity map and report a value the
 * engine never stored -- which is the exact failure this class exists to detect.
 *
 * <h2>Trade-offs</h2>
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's Failsafe configuration
 * includes exactly that suffix, so any other name would leave it unrun. The suffix describes the TIER --
 * a case that needs a real database -- rather than the subject, which is a service's transaction
 * boundary. The sibling {@code fixtures.PendingAuthFraudDomainRepositoryIT} accepts the same trade for
 * the same reason.
 *
 * <p>Trade-offs: the schema is built by running the module's own Flyway migration against the container
 * rather than by a hand-written definition, so a constraint that differs between the migration and this
 * class cannot exist. The cost is that a migration failure surfaces here as a start-up error rather than
 * as an assertion.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
@SpringBootTest(
        classes = FraudMarkingTransactionRepositoryIT.FraudMarkingTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FraudMarkingTransactionRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     */
    // WHY : Assumptions: this is the same digest the sibling integration tests of this module name. Two
    //       classes pinning two engines could disagree about one constraint or one transaction
    //       behaviour, and the disagreement would surface as whichever ran second; a digest rather than
    //       a tag is what makes the reference immutable, since a publisher may rebuild a patch tag.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The engine every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The principal the selector is sealed for and redeemed under. */
    private static final String SUBJECT = "authorization-operator";

    /** The account the fabricated authorization hangs under. */
    private static final long ACCOUNT_ID = 11L;

    /** The customer that account belongs to, which the fraud row carries. */
    private static final long CUSTOMER_ID = 11L;

    /** The card of the fabricated authorization, whole because it is the fraud row's own key. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The decoded authorization date, being the first half of the composite key. */
    private static final int AUTH_DATE = 26_215;

    /** The decoded authorization time, being the second half of the composite key. */
    private static final int AUTH_TIME = 9_16_44_902;

    /** A fraud action outside the published domain, used to provoke the refusal after the first write. */
    private static final String OUT_OF_DOMAIN_ACTION = "X";

    /** The authorization repository, used to seed and never to assert. */
    @Autowired
    private PendingAuthDetailRepository details;

    /** The summary repository, used to seed the parent the fraud row reads its customer from. */
    @Autowired
    private PendingAuthSummaryRepository summaries;

    /** The service under test, injected so that the transactional proxy is the one exercised. */
    @Autowired
    private FraudMarkingService service;

    /** The mapper that seals the selector each case presents, which is the production one. */
    @Autowired
    private PendingAuthViewMapper mapper;

    /** The pool the JDBC assertions take their own connections from. */
    @Autowired
    private DataSource dataSource;

    /** The transaction manager's template, used to seed rows outside the service's own boundary. */
    @Autowired
    private TransactionTemplate transactions;

    /**
     * Points the context's datasource and migration at the container this class started.
     *
     * @param registry the registry the framework supplies for late-bound properties
     */
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties both tables and seeds one summary and one authorization beneath it.
     *
     * <p>Assumptions: the seed is written in its OWN transaction, which has committed before any case
     * begins. A seed left inside the case's own transaction would be invisible to the JDBC assertions,
     * and a case that then found nothing could not tell an absent write from an unseen one.
     */
    @BeforeEach
    void seedOneAuthorization() {
        this.transactions.executeWithoutResult(status -> {
            this.details.deleteAllInBatch();
            this.summaries.deleteAllInBatch();
        });
        emptyFraudTable();
        this.transactions.executeWithoutResult(status -> {
            this.summaries.save(new PendingAuthSummary(ACCOUNT_ID, Long.valueOf(CUSTOMER_ID)));
            this.details.save(authorization());
        });
    }

    /**
     * Both writes are visible after a successful mark, each in its own table.
     *
     * <p>Assumptions: the two readings together are the property. The fraud row alone would pass against
     * an implementation that never touched the authorization, and the authorization alone would pass
     * against one whose upsert silently affected no row -- the statement reports which arm ran rather
     * than raising, so a caller that ignored the report would not notice.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a successful mark commits the fraud row and the authorization's state together")
    void bothWritesCommitTogether() {
        FraudMarkingService.FraudMarkOutcome outcome = this.service.mark(selector(),
                new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        assertThat(outcome.created())
                .as("the key had no fraud row, so the insert arm ran")
                .isTrue();
        assertThat(fraudRowCount())
                .as("exactly one fraud row exists for the marked authorization")
                .isEqualTo(1);
        assertThat(storedFraudAction())
                .as("the fraud row carries the reported state")
                .isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
        assertThat(storedAuthorizationFraudState())
                .as("the authorization's own fraud position is written in the same unit of work")
                .isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
        assertThat(storedAuthorizationReportDate())
                .as("the report date accompanies the position, both moving together")
                .isNotNull();
    }

    /**
     * Neither write survives a rollback of the transaction the marking joined.
     *
     * <p>Purpose: this is the atomicity claim. The marking writes twice through two different
     * mechanisms -- a native statement and a dirty-checked entity -- and a native statement is exactly
     * the kind of write that can escape a transaction without anybody noticing, because it commits on
     * whatever connection it was given. If it did, a rolled-back marking would leave a fraud report
     * standing against an authorization that is not marked: a disagreement between two tables that no
     * later read can resolve, and one that reads as a genuine fraud report to anybody auditing the fraud
     * table.
     *
     * <p>Assumptions: the marking is called inside an ambient transaction, which it joins -- its own
     * propagation is the default -- and the case then rolls that transaction back. Both writes must
     * therefore be gone. Asserting only the fraud row would leave a rollback that undid the wrong write
     * passing, so both tables are read.
     *
     * <p>Assumptions: the rollback is requested by marking the transaction rollback-only rather than by
     * throwing, because a thrown exception would also have to be caught and asserted, and this case is
     * about what the ENGINE retains rather than about how a failure is reported.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("neither write survives a rollback of the transaction the marking joined")
    void neitherWriteSurvivesARollback() {
        String selector = selector();

        this.transactions.executeWithoutResult(status -> {
            FraudMarkingService.FraudMarkOutcome outcome = this.service.mark(selector,
                    new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);
            assertThat(outcome.created())
                    .as("the marking really did write, so the rollback has something to undo")
                    .isTrue();
            status.setRollbackOnly();
        });

        assertThat(fraudRowCount())
                .as("the native statement must be enlisted, so its row cannot survive the rollback")
                .isZero();
        assertThat(storedAuthorizationFraudState())
                .as("the entity write must be enlisted too, leaving the authorization unmarked")
                .isNull();
        assertThat(storedAuthorizationReportDate()).isNull();
    }

    /**
     * A fraud action outside the published domain is refused before anything is written.
     *
     * <p>Purpose: this pins the ORDERING the rollback case above depends on, and it is a real guard in
     * its own right. Bean validation on the request record is applied by the controller, so a caller
     * reaching this service directly -- which is what any future in-process caller would be -- is not
     * validated by it, and the projection's own check is then the only thing between an out-of-domain
     * character and a stored fraud indicator the column cannot hold.
     *
     * <p>Assumptions: the refusal is asserted to leave the fraud table EMPTY, which is what establishes
     * that the guard sits ahead of the first write rather than between the two.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an out-of-domain fraud action is refused before either write")
    void anOutOfDomainActionIsRefusedBeforeAnyWrite() {
        String selector = selector();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> this.service.mark(selector,
                        new FraudMarkRequest(OUT_OF_DOMAIN_ACTION), SUBJECT))
                .withMessageContaining(OUT_OF_DOMAIN_ACTION);

        assertThat(fraudRowCount())
                .as("the refusal precedes the first write, so no fraud row is created")
                .isZero();
        assertThat(storedAuthorizationFraudState()).isNull();
    }

    /**
     * A second mark transitions both tables again and still leaves exactly one fraud row.
     *
     * <p>Assumptions: the second action is the OPPOSITE of the first, so the two tables can be asserted
     * to have moved rather than merely to hold something. A repeat of the same action would leave an
     * implementation that ignored the second mark entirely indistinguishable from one that applied it.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a second mark transitions both tables and adds no second fraud row")
    void aSecondMarkTransitionsBothTables() {
        String selector = selector();
        this.service.mark(selector, new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        FraudMarkingService.FraudMarkOutcome second = this.service.mark(selector,
                new FraudMarkRequest(PendingAuthDetail.FRAUD_REMOVED), SUBJECT);

        assertThat(second.created())
                .as("a row already existed for the key, so the conflict arm ran")
                .isFalse();
        assertThat(fraudRowCount())
                .as("the conflict arm updates in place rather than adding a row")
                .isEqualTo(1);
        assertThat(storedFraudAction()).isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        assertThat(storedAuthorizationFraudState())
                .as("the authorization follows the fraud row into the released state")
                .isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
    }

    /**
     * Seals a selector for the seeded authorization, using the production mapper.
     *
     * <p>Assumptions: the selector is minted by the SAME mapper the service redeems it with, because the
     * property under test is the write and not the sealing. A hand-built token would be refused, and a
     * second mapper with its own key material would make every case fail for a reason unrelated to what
     * it asserts.
     *
     * @return an opaque selector the service will redeem for the seeded key
     */
    private String selector() {
        return this.mapper.toRowView(authorization(), SUBJECT).key();
    }

    /**
     * Builds the one authorization every case in this class marks.
     *
     * @return a fully populated authorization whose fraud position is unset
     */
    private static PendingAuthDetail authorization() {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260803", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Empties the fraud table on its own connection, so no case inherits another's row.
     *
     * @throws IllegalStateException if the statement cannot be run
     */
    private void emptyFraudTable() {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM auth_fraud")) {
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("the fraud table could not be emptied", failure);
        }
    }

    /**
     * Counts the fraud rows the engine holds.
     *
     * @return how many rows {@code auth_fraud} contains
     * @throws IllegalStateException if the count cannot be read
     */
    private int fraudRowCount() {
        return readInt("SELECT count(*) FROM auth_fraud");
    }

    /**
     * Reads the fraud action of the single stored fraud row.
     *
     * @return the stored action, trimmed of the padding its fixed-width column carries
     * @throws IllegalStateException if no row is stored or the column cannot be read
     */
    private String storedFraudAction() {
        return readString("SELECT auth_fraud FROM auth_fraud")
                .orElseThrow(() -> new IllegalStateException("no fraud row is stored"));
    }

    /**
     * Reads the fraud position of the seeded authorization straight from its own table.
     *
     * @return the stored position, or {@code null} when the column holds SQL null
     */
    private String storedAuthorizationFraudState() {
        return readString("SELECT auth_fraud FROM pending_auth_detail").orElse(null);
    }

    /**
     * Reads the fraud report date of the seeded authorization straight from its own table.
     *
     * @return the stored report date, or {@code null} when the column holds SQL null
     */
    private String storedAuthorizationReportDate() {
        return readString("SELECT fraud_rpt_date FROM pending_auth_detail").orElse(null);
    }

    /**
     * Runs one single-value character query on a connection of its own.
     *
     * @param query the query to run, naming its table unqualified
     * @return the first column of the first row, trimmed, or empty when it is SQL null
     * @throws IllegalStateException if the query returns no row or cannot be run
     */
    private Optional<String> readString(String query) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("no row answered " + query);
            }
            String value = rows.getString(1);
            return value == null ? Optional.empty() : Optional.of(value.trim());
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * Runs one single-value integer query on a connection of its own.
     *
     * @param query the query to run, naming its table unqualified
     * @return the first column of the first row
     * @throws IllegalStateException if the query returns no row or cannot be run
     */
    private int readInt(String query) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("no row answered " + query);
            }
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * The narrowest context that can host the service under test against a real engine.
     *
     * <p>Assumptions: the writer fragment is component-scanned and the SERVICE layer is not, and the
     * asymmetry is deliberate. The native fraud upsert is implemented by a package-private component
     * that this class cannot construct from outside its package, so it has to be scanned; the service
     * package, by contrast, holds a queue listener, an outbox publisher and a remote client, none of
     * which this class asserts anything about and each of which would demand a queue, a network address
     * or an identity provider to start. Declaring the two beans this class needs keeps the context to
     * what it tests.
     *
     * <p>Refactoring Rationale: the scan names ONE component by pattern and turns the default filters
     * OFF, where it first scanned the whole repository package. A package-wide scan with the default
     * filters also finds the nested configuration classes of the OTHER integration tests in that
     * package -- test classes share the package name of the code they exercise -- and each of those
     * declares its own repository enablement, so the context failed to start on a duplicate repository
     * bean definition before a single case ran. Naming the one component is what makes the scan
     * insensitive to what else happens to sit in that package.
     *
     * <p>Assumptions: the service is declared through a bean method rather than scanned, and it is still
     * a TRANSACTIONAL PROXY -- the framework's transaction infrastructure is auto-configured for every
     * bean in the context, however it was declared, so the boundary being exercised is the production
     * one. That is the whole point of injecting the bean rather than constructing it in a case.
     *
     * <p>Alternatives Considered: excluding the resource-server and messaging auto-configuration is
     * copied from the sibling repository test rather than reasoned afresh, and its reasons hold here
     * unchanged: the resource-server set-up resolves this module's identity-provider address while
     * deciding which beans to define, and the module's base profile binds that address to an environment
     * placeholder with no fallback, so the context would fail before any bean existed; and a messaging
     * client is a bean that can only fail in a test that addresses no queue.
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.authorization.domain")
    @EnableJpaRepositories("com.carddemo.authorization.repository")
    @ComponentScan(
            basePackageClasses = AuthFraudUpserter.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com\\.carddemo\\.authorization\\.repository\\.AuthFraudUpserterImpl"))
    static class FraudMarkingTestApplication {

        /**
         * The view mapper, keyed with fixed material so a selector sealed here reopens here.
         *
         * <p>Assumptions: the key material is a constant of this test rather than the deployment's, and
         * that is safe precisely because it is not a secret in this context: the selector never leaves
         * the case that sealed it. Reading the deployed key instead would make this class depend on a
         * secret store to assert a transaction boundary.
         *
         * @return the mapper both the cases and the service under test use
         */
        @Bean
        PendingAuthViewMapper pendingAuthViewMapper() {
            byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
            Arrays.fill(keyMaterial, (byte) 0x3C);
            return new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        }

        /**
         * The service under test, wired from the real repositories and the real writer fragment.
         *
         * @param details the authorization repository
         * @param summaries the summary repository, read for the fraud row's customer identifier
         * @param fraudRows the fraud repository, read for the engine's own current date
         * @param fraudUpserts the native single-statement fraud writer
         * @param mapper the selector-sealing mapper
         * @return the service, which the framework wraps in its transactional proxy
         */
        @Bean
        FraudMarkingService fraudMarkingService(PendingAuthDetailRepository details,
                PendingAuthSummaryRepository summaries, AuthFraudRepository fraudRows,
                AuthFraudUpserter fraudUpserts, PendingAuthViewMapper mapper) {
            return new FraudMarkingService(details, summaries, fraudRows, fraudUpserts, mapper);
        }

        /**
         * A template for the seeding writes, which must commit outside the service's own boundary.
         *
         * @param manager the context's transaction manager
         * @return a template that begins and commits one transaction per call
         */
        @Bean
        TransactionTemplate seedingTransactions(
                org.springframework.transaction.PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
