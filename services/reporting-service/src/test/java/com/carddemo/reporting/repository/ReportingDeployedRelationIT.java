package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.repository.StatementCardXrefRepository.StatementHeadingRow;
import jakarta.persistence.EntityManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the card fixtures reach the deployed reporting relations, masked, joined and unwritable.
 *
 * <h2>Purpose</h2>
 *
 * <p>The two card fixtures in this module -- {@code fixtures/carddata.txt} and
 * {@code fixtures/cardxref.txt} -- were committed with a codec-only consumer. That consumer decodes
 * every row, re-encodes it and asserts byte identity, which settles the record geometry and settles
 * nothing else: it cannot show that the rows load into the relations the reporting context reads,
 * that the cross-reference projection narrows a primary account number before publishing it, that the
 * statement heading resolves a card to its customer and its account across three separate
 * projections, or that the login role those projections are read under is unable to write. This class
 * asserts all four against a real engine carrying the DDL this repository ships.</p>
 *
 * <h2>Why the deployed DDL is applied rather than reproduced</h2>
 *
 * <p>Assumptions: every relation this class reads is created by executing the shipped files
 * themselves, read from the working tree and sent to the engine unchanged -- the bootstrap that
 * creates the schemas and the roles, the four owning services' migrations that create the base
 * tables, and the data-migration file that creates the reporting projections over them. Nothing here
 * restates a column, a grant or a masking expression.</p>
 *
 * <p>Alternatives Considered: a hand-written harness script standing in for the projections, which is
 * what the sibling {@code StatementHeadingChunkIT} uses and what this class deliberately does not.
 * That choice is right for the sibling and wrong here, and the difference is the property being
 * asserted. The sibling asserts that a keyset predicate reproduces its own {@code ORDER BY}, which is
 * a property of the query and holds over any relation of the right shape. This class asserts that the
 * projection MASKS, that it JOINS and that the role CANNOT WRITE -- three properties that live
 * entirely in the view definitions and the grants, so a stand-in relation would assert them of the
 * stand-in and prove nothing about what ships. Reproducing the definitions here instead would put a
 * second copy of the masking expression in the repository, and the copy would be the thing under
 * test.</p>
 *
 * <p>Trade-offs: the cost is a longer arrangement -- nine script executions rather than one, and one
 * of the nine executed twice. That is accepted because the second execution is not incidental: the
 * bootstrap grants the projection owner its cross-schema read privileges only for base tables that
 * already exist, and on a first pass none of them does. Its own notices say so, and the projections
 * are definer-rights, so without the second pass the very first read fails with a privilege error
 * even for a superuser. Applying the files in the order an operator applies them is therefore part of
 * what this class establishes.</p>
 *
 * <h2>Why the context connects as the reporting login role</h2>
 *
 * <p>Assumptions: the datasource below authenticates as {@code carddemo_reporting}, the SELECT-only
 * login role the deployed service uses, and not as the container's owner. Reading the projections as
 * an owner would exercise the same SQL and none of the privilege boundary, and the boundary is half of
 * what this class is for. The role is created by the bootstrap without a credential, because the
 * bootstrap's credential source is a secret store no test has; the arrangement below therefore sets
 * one, and the value is a fabricated literal that authenticates to a container discarded when this
 * class finishes.</p>
 *
 * <p>Assumptions: the pool configuration is the deployed one, inherited from
 * {@code src/main/resources/application.yml} by being left alone -- the read-only pool posture,
 * disabled auto-commit, and the connection initialisation statement that pins the search path to
 * {@code reporting}. Overriding any of them here would test a posture that is not deployed.</p>
 *
 * <p>Trade-offs: because the pool is read-only, a write attempted through it is refused by the pool
 * before the engine consults a grant, so a refusal observed there proves the flag and not the
 * privilege. The two controls are therefore asserted separately: the privilege on a direct read-write
 * connection opened as the same role, and the pool posture by reading the session's own transaction
 * mode back through the pool.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises outside the test engine, so it accepts
 * no parameter, returns nothing and raises nothing. The inapplicability is stated rather than passed
 * over, because user-specified Rule 1 (Explainability) forbids a docstring that omits parameters,
 * return values or exceptions.</p>
 */
@SpringBootTest(
        classes = ReportingDeployedRelationIT.DeployedRelationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: these five properties are the set both sibling integration tests supply and
    //       for the same reason -- this module's cloud auto-configuration resolves a region and
    //       credentials eagerly and both remote configuration importers read a remote store while the
    //       environment is being prepared, so a context that enables auto-configuration at all fails
    //       before any assertion without them. Nothing here contacts a cloud service, so the two
    //       credential strings are literal, fabricated and identify nothing that exists.
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=not-a-real-key",
    "spring.cloud.aws.credentials.secret-key=not-a-real-secret",
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class ReportingDeployedRelationIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and both a masking cast and a
     * privilege message are things an engine version can change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The SELECT-only login role the deployed reporting context authenticates as.
     */
    private static final String REPORTING_ROLE = "carddemo_reporting";

    /**
     * The fabricated credential given to {@link #REPORTING_ROLE} inside the throwaway container.
     *
     * <p>Assumptions: this is not a secret and is not treated as one. The bootstrap deliberately
     * leaves every service role without a credential when no secret store is reachable, so a role
     * that must accept a connection has to be given one here; the value authenticates to a container
     * created for this class and destroyed with it, reaches no network the container does not own, and
     * is spelled so that a scan reporting it reports something self-evidently not live.</p>
     *
     * <p>Alternatives Considered: generating a random value per run. Rejected as strictly worse for a
     * reader -- it would make the credential look like something worth protecting, and it would remove
     * the one property that matters here, which is that anybody can see at a glance that it is not.</p>
     */
    private static final String REPORTING_ROLE_PASSWORD = "not-a-real-reporting-credential";

    /**
     * The two settings the bootstrap requires before it will run on a local engine.
     *
     * <p>Assumptions: both are the bootstrap's OWN documented escape hatches and neither is a
     * workaround invented here. It refuses to apply credentials over an unencrypted transport, because
     * an {@code ALTER ROLE ... PASSWORD} accepts no bind parameter and would cross the wire as
     * statement text; and it refuses to commit while a service role has no credential, because no
     * service could then authenticate. A container with no server certificate and no secret store
     * trips both, so both are acknowledged explicitly rather than by weakening the file.</p>
     */
    private static final List<String> BOOTSTRAP_ACKNOWLEDGEMENTS = List.of(
            "carddemo.bootstrap_allow_insecure",
            "carddemo.bootstrap_allow_missing_credentials");

    /**
     * The shipped DDL files, repository-relative, in the order an operator applies them.
     *
     * <p>Assumptions: the bootstrap appears TWICE and the repetition is required rather than
     * defensive. Its first pass creates the schemas and the roles; its cross-schema read grants to the
     * projection owner are conditional on the base tables existing, and on that pass none of them
     * does -- the file emits a notice per skipped grant saying to re-run it afterwards. The
     * projections are definer-rights, so a missing grant surfaces as a privilege error on the first
     * read rather than at creation, which is exactly the failure the second pass removes.</p>
     */
    private static final List<String> DEPLOYED_SCHEMA_SCRIPTS = List.of(
            "data-migration/sql/V0__schemas_and_roles.sql",
            "services/account-service/src/main/resources/db/migration/V1__account.sql",
            "services/account-service/src/main/resources/db/migration/"
                    + "V2__account_inquiry_reply_ledger.sql",
            "services/card-service/src/main/resources/db/migration/V1__card.sql",
            "services/card-service/src/main/resources/db/migration/V2__card_num_digit_domain.sql",
            "services/reference-service/src/main/resources/db/migration/V1__reference.sql",
            "services/transaction-service/src/main/resources/db/migration/V1__ledger.sql",
            "services/transaction-service/src/main/resources/db/migration/"
                    + "V2__ledger_transaction_id_allocator.sql",
            "services/transaction-service/src/main/resources/db/migration/"
                    + "V3__ledger_bytewise_collation.sql",
            "data-migration/sql/V0__schemas_and_roles.sql",
            "data-migration/sql/V1__reporting_views.sql");

    /**
     * The classpath directory holding the fixture corpus this class loads.
     */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The card whose master row exists while no cross-reference row publishes it.
     *
     * <p>Assumptions: this is the fixture's deliberate asymmetry, and it is what lets a reader tell a
     * projection that reads the cross-reference from one that reads the card master. A corpus in which
     * every master row had a cross-reference row could not distinguish the two.</p>
     */
    private static final String UNPUBLISHED_CARD = "9900001010000001";

    /**
     * One of the two fixture cards whose last four digits are identical.
     *
     * <p>Assumptions: this card and {@link #UNPUBLISHED_CARD} end in the same four digits, so they
     * share one masked rendering while differing as cards. That collision is the reason the projection
     * publishes a keyed per-card fingerprint beside the mask and the reason a whole-number lookup
     * exists at all, and it is asserted here rather than assumed.</p>
     */
    private static final String COLLIDING_PUBLISHED_CARD = "9900001020000001";

    /**
     * The account carrying two of the five fixture cards.
     */
    private static final long ACCOUNT_WITH_TWO_CARDS = 50L;

    /**
     * The account whose only card is the one the cross-reference does not publish.
     */
    private static final long ACCOUNT_WITH_UNPUBLISHED_CARD = 101L;

    /**
     * The account whose card is inactive in the card master.
     */
    private static final long ACCOUNT_WITH_INACTIVE_CARD = 102L;

    /**
     * A card number no fixture carries, used to prove a lookup answers with nothing.
     */
    private static final String ABSENT_CARD = "9999999999999999";

    /**
     * The masked rendering every published card must carry: twelve asterisks then four digits.
     */
    private static final String MASKED_RENDERING = "\\*{12}[0-9]{4}";

    /**
     * The number of leading asterisks the projection substitutes for the withheld digits.
     */
    private static final int MASKED_PREFIX_LENGTH = 12;

    /**
     * The number of trailing digits the projection publishes.
     */
    private static final int PUBLISHED_TAIL_LENGTH = 4;

    /**
     * A bound wide enough to return the whole fixture in one request.
     *
     * <p>Assumptions: the corpus holds four published cards, so any bound above four returns all of
     * them and no case below depends on a chunk boundary. Chunk-boundary behaviour is the sibling
     * class's subject and is deliberately not restated here.</p>
     */
    private static final int WHOLE_CORPUS_BOUND = 25;

    /**
     * The placeholder ciphertext written into the two enciphered columns.
     *
     * <p>Assumptions: both columns are declared as byte arrays and one of them is not nullable, so a
     * load has to supply something; no reporting projection selects either column, so what it supplies
     * cannot affect any assertion here. A fabricated constant is used rather than a real
     * enciphering, because reaching for the owning service's cipher would couple this class to a
     * component it does not exercise and would make a cipher change look like a reporting defect.</p>
     */
    private static final byte[] PLACEHOLDER_CIPHERTEXT =
            "not-real-ciphertext".getBytes(StandardCharsets.US_ASCII);

    /**
     * The engine this class reads, started explicitly so the arrangement below precedes any context.
     *
     * <p>Assumptions: the container is started from a static initialiser rather than by the
     * Testcontainers JUnit extension, and the difference matters. The deployed pool runs a connection
     * initialisation statement pinning the search path to a schema, and it authenticates as a role
     * that the bootstrap creates; both must therefore exist before the first pooled connection opens.
     * A static initialiser runs when this class is loaded, which is strictly before any Spring context
     * is built for it, so the ordering is a language guarantee rather than an extension-ordering
     * assumption.</p>
     *
     * <p>Trade-offs: the class consequently manages the lifecycle itself and does not carry the
     * extension's container annotations. Testcontainers' own reaper still removes the container when
     * the build's JVM exits, so nothing is leaked; what is given up is the extension's per-class
     * start and stop logging, which is not something any assertion here reads.</p>
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
        acknowledgeBootstrapPreconditions();
        applyDeployedSchema();
        grantTheReportingRoleALogin();
        loadTheFixtureCorpus();
    }

    /**
     * The cross-reference projection, injected so every read goes through the declared query.
     */
    @Autowired
    private StatementCardXrefRepository cardXrefs;

    /**
     * The account projection, injected to prove the heading join reaches a second relation.
     */
    @Autowired
    private StatementAccountRepository accounts;

    /**
     * The customer projection, injected to prove the heading join reaches a third relation.
     */
    @Autowired
    private StatementCustomerRepository customers;

    /**
     * The entity manager, used only to read session settings back through the deployed pool.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Points the deployed datasource at the container, authenticating as the reporting login role.
     *
     * <p>Assumptions: only the three connection coordinates are supplied. Every other datasource
     * property -- the read-only pool, the disabled auto-commit, the acquisition and lifetime
     * ceilings and the connection initialisation statement that pins the search path -- is inherited
     * from the module's own configuration by not being mentioned, which is what makes the posture
     * under test the deployed posture.</p>
     *
     * <p>Alternatives Considered: the service-connection annotation the two sibling classes use.
     * Rejected here because it derives the username and password from the container's own owner
     * account, and reading the projections as an owner would exercise none of the privilege boundary
     * this class exists to assert.</p>
     *
     * @param registry the registry the test framework supplies for late-bound properties; must not be
     *     {@code null}
     */
    @DynamicPropertySource
    static void reportingDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> REPORTING_ROLE);
        registry.add("spring.datasource.password", () -> REPORTING_ROLE_PASSWORD);
    }

    /**
     * Confirms the context reads as the SELECT-only role, in the reporting schema, read-only.
     *
     * <p>Assumptions: all three facts are read back from the session rather than inferred from
     * configuration, because each of them is a property of the connection the pool actually handed
     * out. A configuration key can be present and unapplied -- an initialisation statement that
     * silently failed would leave the search path at its default, and every unqualified read below
     * would then resolve somewhere else.</p>
     */
    @Test
    @DisplayName("the context reads as the SELECT-only role, in the reporting schema, read-only")
    void theContextReadsAsTheSelectOnlyRoleInTheReportingSchemaReadOnly() {
        assertThat(sessionSetting("select current_user"))
                .as("the deployed pool must authenticate as the SELECT-only login role")
                .isEqualTo(REPORTING_ROLE);
        assertThat(sessionSetting("select current_setting('search_path')"))
                .as("the deployed connection initialisation statement must have been applied")
                .isEqualTo("reporting");
        assertThat(sessionSetting("select current_setting('transaction_read_only')"))
                .as("the deployed pool declares itself read-only, which the session must reflect")
                .isEqualTo("on");
    }

    /**
     * Confirms the cross-reference projection publishes every card narrowed to its last four digits.
     *
     * <p>Assumptions: the assertion is two-sided. Every published rendering must MATCH the narrowed
     * form, and no published value of any column may CONTAIN a whole fixture card number -- the second
     * half is what would catch a projection that added an unmasked column beside the masked one, which
     * the first half alone cannot see.</p>
     */
    @Test
    @DisplayName("the cross-reference projection publishes every card narrowed to its last four digits")
    void theCrossReferenceProjectionPublishesEveryCardNarrowed() {
        List<StatementHeadingRow> published = wholeCorpus();

        assertThat(published)
                .as("the cross-reference fixture publishes four of the five committed cards")
                .hasSize(4);
        for (StatementHeadingRow row : published) {
            assertThat(row.getCardNum())
                    .withFailMessage("the projection must publish %s narrowed to twelve asterisks and"
                            + " four digits; publishing anything wider hands the reporting role a"
                            + " primary account number the view exists to withhold", row.getCardNum())
                    .matches(MASKED_RENDERING);
        }

        List<String> publishedTails = published.stream()
                .map(row -> row.getCardNum().substring(MASKED_PREFIX_LENGTH))
                .toList();
        List<String> committedTails = committedCardNumbers().stream()
                .filter(card -> !card.equals(UNPUBLISHED_CARD))
                .map(card -> card.substring(card.length() - PUBLISHED_TAIL_LENGTH))
                .toList();
        assertThat(publishedTails)
                .as("every published tail must be the tail of a committed cross-reference card")
                .containsExactlyInAnyOrderElementsOf(committedTails);

        for (String committed : committedCardNumbers()) {
            for (StatementHeadingRow row : published) {
                assertThat(row.getCardNum() + '|' + row.getCardFingerprint())
                        .withFailMessage("no value the projection publishes may contain the whole card"
                                + " number %s; it appeared in a published row, so the mask is not the"
                                + " only rendering leaving this relation", committed)
                        .doesNotContain(committed);
            }
        }
    }

    /**
     * Confirms the heading read joins each published card to its customer and its account.
     *
     * <p>Assumptions: the expected values are read from the OTHER two projections through their own
     * repositories rather than written out here, so the assertion compares a joined row against the
     * unjoined rows it should have been assembled from. Writing the expected names and balances out
     * would restate the fixture in a second place and the two could then disagree.</p>
     *
     * <p>Assumptions: the join is asserted for every published row and not for a representative one.
     * A join predicate that is right for three rows and wrong for the fourth is exactly the shape a
     * single-row assertion misses, and the corpus deliberately contains a row whose account carries a
     * second card and a row whose card is inactive.</p>
     */
    @Test
    @DisplayName("the heading read joins each published card to its customer and its account")
    void theHeadingReadJoinsEachPublishedCardToItsCustomerAndAccount() {
        List<StatementHeadingRow> published = wholeCorpus();
        assertThat(published).as("the corpus publishes four cards").hasSize(4);

        for (StatementHeadingRow row : published) {
            Optional<CustomerView> customer = customers.findById(row.getCustomerId());
            Optional<AccountView> account = accounts.findById(row.getAccountId());

            assertThat(customer)
                    .withFailMessage("the heading row for %s names customer %d, so the customer"
                            + " projection must hold that customer; it does not, so the join is"
                            + " producing an identifier the projections do not agree on",
                            row.getCardNum(), row.getCustomerId())
                    .isPresent();
            assertThat(account)
                    .withFailMessage("the heading row for %s names account %d, so the account"
                            + " projection must hold that account; it does not, so the join is"
                            + " producing an identifier the projections do not agree on",
                            row.getCardNum(), row.getAccountId())
                    .isPresent();

            assertThat(row.getFirstName().trim())
                    .as("the joined first name must be the customer projection's first name")
                    .isEqualTo(customer.orElseThrow().getFirstName().trim());
            assertThat(row.getLastName().trim())
                    .as("the joined last name must be the customer projection's last name")
                    .isEqualTo(customer.orElseThrow().getLastName().trim());
            assertThat(row.getPostalCode().trim())
                    .as("the joined postal code must be the customer projection's postal code")
                    .isEqualTo(customer.orElseThrow().getPostalCode().trim());
            assertThat(row.getFicoCreditScore())
                    .as("the joined credit score must be the customer projection's credit score")
                    .isEqualTo(customer.orElseThrow().getFicoCreditScore());
            assertThat(row.getCurrentBalance().amount())
                    .as("the joined balance must be the account projection's balance")
                    .isEqualByComparingTo(account.orElseThrow().getCurrentBalance().amount());
        }
    }

    /**
     * Confirms a whole-number lookup separates two cards that share one masked rendering.
     *
     * <p>Assumptions: this is the property the mask cannot have and the fixture is arranged to expose.
     * Two committed cards end in the same four digits, so they publish one identical rendering; one of
     * them is in the cross-reference and the other is not. A lookup on the rendering would answer for
     * the wrong card, so the projection publishes a keyed fingerprint and resolution takes the whole
     * number -- and the two together are what this case measures.</p>
     */
    @Test
    @DisplayName("a whole-number lookup separates two cards sharing one masked rendering")
    void aWholeNumberLookupSeparatesTwoCardsSharingOneMaskedRendering() {
        assertThat(UNPUBLISHED_CARD.substring(UNPUBLISHED_CARD.length() - PUBLISHED_TAIL_LENGTH))
                .as("the fixture must keep two cards sharing a tail, or this case is vacuous")
                .isEqualTo(COLLIDING_PUBLISHED_CARD
                        .substring(COLLIDING_PUBLISHED_CARD.length() - PUBLISHED_TAIL_LENGTH));

        Optional<CardXrefView> colliding = cardXrefs.resolveByWholeCardNumber(COLLIDING_PUBLISHED_CARD);
        Optional<CardXrefView> unpublished = cardXrefs.resolveByWholeCardNumber(UNPUBLISHED_CARD);
        Optional<CardXrefView> absent = cardXrefs.resolveByWholeCardNumber(ABSENT_CARD);

        assertThat(colliding)
                .as("the cross-referenced member of the colliding pair must resolve")
                .isPresent();
        assertThat(colliding.orElseThrow().getAccountId())
                .as("it must resolve to its own account and not to its tail-sharing sibling's")
                .isEqualTo(ACCOUNT_WITH_INACTIVE_CARD);
        assertThat(unpublished)
                .withFailMessage("the card the cross-reference does not publish must resolve to"
                        + " nothing; it resolved, which means the lookup matched on the four digits it"
                        + " shares with %s rather than on the whole number", COLLIDING_PUBLISHED_CARD)
                .isEmpty();
        assertThat(absent).as("a card no fixture carries must resolve to nothing").isEmpty();

        Optional<CardXrefView> byFingerprint =
                cardXrefs.findById(colliding.orElseThrow().getCardFingerprint());
        assertThat(byFingerprint)
                .as("the fingerprint the lookup returned must address the same row")
                .isPresent();
        assertThat(byFingerprint.orElseThrow().getCardNum())
                .as("addressing by fingerprint must return the same masked rendering")
                .isEqualTo(colliding.orElseThrow().getCardNum());
    }

    /**
     * Confirms the card master holds a card the cross-reference does not publish.
     *
     * <p>Assumptions: the master row count is read on a connection that can see the card schema, and
     * the published count is read through the reporting role, because the reporting role holds no
     * privilege on that schema at all. Comparing the two is the point: five rows load, four are
     * publishable, and the projection publishes exactly the four.</p>
     */
    @Test
    @DisplayName("the card master holds a card the cross-reference does not publish")
    void theCardMasterHoldsACardTheCrossReferenceDoesNotPublish() {
        assertThat(ownerCount("select count(*) from card.cards"))
                .as("all five committed card rows must load into the card master")
                .isEqualTo(5L);
        assertThat(ownerCount("select count(*) from account.card_xref"))
                .as("all four committed cross-reference rows must load")
                .isEqualTo(4L);
        assertThat(ownerCount("select count(*) from card.cards where card_num = '"
                + UNPUBLISHED_CARD + "'"))
                .as("the unpublished card must nonetheless exist in the master")
                .isEqualTo(1L);

        assertThat(cardXrefs.findCardsOfAccount(ACCOUNT_WITH_UNPUBLISHED_CARD,
                Limit.of(WHOLE_CORPUS_BOUND)))
                .withFailMessage("account %d holds a card in the master and no cross-reference row,"
                        + " so the projection must publish none of its cards",
                        ACCOUNT_WITH_UNPUBLISHED_CARD)
                .isEmpty();
    }

    /**
     * Confirms an account with two cards publishes both, and an inactive card is still published.
     *
     * <p>Assumptions: both halves are asserted together because they are the same mistake seen twice.
     * A projection keyed on the account rather than the card would collapse the two-card account to
     * one row, and a projection that filtered on the master's active flag would drop the inactive
     * card. Neither the cross-reference nor any reporting relation carries that flag, so the second
     * half also records that a statement is produced for a closed card -- which is the behaviour the
     * batch oracle has.</p>
     */
    @Test
    @DisplayName("an account with two cards publishes both, and an inactive card is still published")
    void anAccountWithTwoCardsPublishesBothAndAnInactiveCardIsStillPublished() {
        List<CardXrefView> both = cardXrefs.findCardsOfAccount(ACCOUNT_WITH_TWO_CARDS,
                Limit.of(WHOLE_CORPUS_BOUND));
        assertThat(both)
                .withFailMessage("account %d holds two cards in the cross-reference, so the"
                        + " projection must publish two rows for it; publishing one would mean the"
                        + " relation is keyed on the account rather than on the card",
                        ACCOUNT_WITH_TWO_CARDS)
                .hasSize(2);
        assertThat(both.stream().map(CardXrefView::getCardFingerprint).distinct().count())
                .as("the two rows of one account must carry two distinct fingerprints")
                .isEqualTo(2L);
        assertThat(both.stream().map(CardXrefView::getAccountId).distinct().toList())
                .as("both rows must name the account they were selected by")
                .containsExactly(ACCOUNT_WITH_TWO_CARDS);

        assertThat(ownerCount("select count(*) from card.cards where active_status = 'N'"))
                .as("the corpus must keep exactly one inactive card, or the next assertion is vacuous")
                .isEqualTo(1L);
        assertThat(cardXrefs.findCardsOfAccount(ACCOUNT_WITH_INACTIVE_CARD,
                Limit.of(WHOLE_CORPUS_BOUND)))
                .withFailMessage("the inactive card must still be published: no reporting relation"
                        + " carries the master's active flag, so a statement is produced for a closed"
                        + " card exactly as the batch oracle produces one")
                .hasSize(1);
    }

    /**
     * Confirms the reporting role cannot write through any relation it can read.
     *
     * <p>Assumptions: the attempts run on a DIRECT connection opened as the reporting role rather than
     * through the pool, and that is what makes the case about the grant. The deployed pool is
     * read-only, so a write attempted through it is refused before the engine consults a privilege at
     * all -- a refusal that would hold even if every grant were wrong.</p>
     *
     * <p>Assumptions: four attempts are made because the privilege is carried by four different
     * mechanisms, and any one of them failing silently would leave a hole the other three cannot see:
     * a projection is not automatically updatable, the schema holding the base tables grants the role
     * no usage, the reporting schema grants it no creation, and the table keying the fingerprint is
     * withheld from it outright.</p>
     */
    @Test
    @DisplayName("the reporting role cannot write through any relation it can read")
    void theReportingRoleCannotWriteThroughAnyRelationItCanRead() {
        assertThatThrownBy(() -> executeAsReportingRole(
                "insert into reporting.v_card_xref(card_num, card_fingerprint, customer_id,"
                        + " account_id) values ('****', 'f', 1, 1)"))
                .as("a projection assembled from two relations is not automatically updatable")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("v_card_xref");

        assertThatThrownBy(() -> executeAsReportingRole(
                "insert into account.card_xref(card_num, customer_id, account_id)"
                        + " values ('1111111111111111', 1, 1)"))
                .as("the role holds no usage on the schema the base relations live in")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");

        assertThatThrownBy(() -> executeAsReportingRole(
                "update account.card_xref set customer_id = 0"))
                .as("the same absence of usage refuses an update")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");

        assertThatThrownBy(() -> executeAsReportingRole(
                "create table reporting.reporting_owns_nothing (probe integer)"))
                .as("the role holds no creation privilege in the schema it reads")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    /**
     * Confirms the reporting role cannot read the secret that keys the per-card fingerprint.
     *
     * <p>Assumptions: this is a separate case from the write refusals because it is a separate
     * property. The fingerprint is a keyed digest of the whole card number, so a role that could read
     * both the fingerprint and the key could invert the mask by exhausting sixteen digits. Withholding
     * the key is therefore part of the masking control and not merely tidiness, and the bootstrap
     * revokes it explicitly because a default privilege on the schema cannot tell a table from a
     * view.</p>
     */
    @Test
    @DisplayName("the reporting role cannot read the secret that keys the per-card fingerprint")
    void theReportingRoleCannotReadTheSecretThatKeysTheFingerprint() {
        assertThatThrownBy(() -> executeAsReportingRole(
                "select key_value from reporting.card_grouping_key"))
                .withFailMessage("the grouping key must be unreadable by the reporting role: it is"
                        + " mixed into every published fingerprint, so a role holding both could"
                        + " recover the card numbers the mask withholds")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");

        assertThat(cardXrefs.resolveByWholeCardNumber(COLLIDING_PUBLISHED_CARD))
                .as("the fingerprint remains readable, which is what makes withholding the key the"
                        + " whole of the control")
                .isPresent();
    }

    /**
     * Reads the whole published corpus in one request, in the projection's declared order.
     *
     * @return every row the cross-reference projection publishes, never {@code null}
     */
    private List<StatementHeadingRow> wholeCorpus() {
        return cardXrefs.findHeadingChunk("", "", WHOLE_CORPUS_BOUND);
    }

    /**
     * Reads one single-value setting back through the deployed pool.
     *
     * @param sql a query returning exactly one row of one column
     * @return that value rendered as text, never {@code null}
     */
    private String sessionSetting(String sql) {
        return String.valueOf(entityManager.createNativeQuery(sql).getSingleResult());
    }

    /**
     * Counts rows on a connection that can see every schema, which the reporting role cannot.
     *
     * <p>Assumptions: this helper opens its own connection as the container's owner rather than
     * reusing the injected entity manager, because the reporting role holds no privilege on the card
     * or account schemas at all -- the very property asserted above. A count of what loaded therefore
     * cannot be taken through the pool.</p>
     *
     * @param sql a query returning one row of one integral column
     * @return the counted value
     * @throws IllegalStateException if the query cannot be executed
     */
    private static long ownerCount(String sql) {
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("no row returned by " + sql);
            }
            return rows.getLong(1);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot execute " + sql, cause);
        }
    }

    /**
     * Executes one statement on a read-write connection authenticated as the reporting role.
     *
     * <p>Assumptions: the connection is deliberately NOT read-only, so a refusal observed by a caller
     * is a refusal by the engine's privilege system and not by a client-side flag. That distinction is
     * the whole reason this helper exists beside the injected pool.</p>
     *
     * @param sql the statement to attempt
     * @throws SQLException if the engine refuses the statement, which is what every caller asserts
     */
    private static void executeAsReportingRole(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), REPORTING_ROLE, REPORTING_ROLE_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Opens a connection as the container's owner account.
     *
     * @return an open connection the caller must close, never {@code null}
     * @throws SQLException if the connection cannot be opened
     */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * Records both bootstrap acknowledgements on the database so later sessions inherit them.
     *
     * <p>Assumptions: they are set on the DATABASE rather than on a session, because each script below
     * is executed by a separate client process and a session setting would not survive between them.
     * A database-level setting is inherited by every connection that follows, which is exactly the
     * scope required.</p>
     *
     * @throws IllegalStateException if either setting cannot be recorded
     */
    private static void acknowledgeBootstrapPreconditions() {
        for (String setting : BOOTSTRAP_ACKNOWLEDGEMENTS) {
            runPsql("-c", "ALTER DATABASE " + POSTGRES.getDatabaseName()
                    + " SET " + setting + " = 'on'");
        }
    }

    /**
     * Applies every shipped DDL file, unchanged, in the order an operator applies them.
     *
     * <p>Assumptions: each file is copied into the container and executed by the engine's own client
     * rather than sent through the driver, and the reason is syntactic. Two of the files wrap
     * themselves in an explicit transaction and several contain dollar-quoted procedural blocks whose
     * bodies hold semicolons, so any client-side splitting on a statement terminator would cut them in
     * half. Handing the whole file to the client that the shipping documentation tells an operator to
     * use removes the question entirely.</p>
     *
     * @throws IllegalStateException if any file cannot be copied or reports a failure
     */
    private static void applyDeployedSchema() {
        Path root = repositoryRoot();
        int sequence = 0;
        for (String script : DEPLOYED_SCHEMA_SCRIPTS) {
            Path source = root.resolve(script);
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("the shipped DDL file " + source
                        + " is absent, so this test cannot assert anything about the deployed schema");
            }
            String target = "/tmp/deployed-" + sequence + ".sql";
            sequence++;
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(source), target);
            runPsql("-v", "ON_ERROR_STOP=1", "-f", target);
        }
    }

    /**
     * Gives the reporting login role a credential so the deployed pool can authenticate as it.
     *
     * <p>Assumptions: the bootstrap creates the role able to log in and leaves it without a
     * credential, because its credential source is a secret store; this is the one thing the
     * arrangement adds to what the shipped files establish, and it adds no privilege of any kind.</p>
     *
     * @throws IllegalStateException if the credential cannot be recorded
     */
    private static void grantTheReportingRoleALogin() {
        runPsql("-c", "ALTER ROLE " + REPORTING_ROLE + " WITH LOGIN PASSWORD '"
                + REPORTING_ROLE_PASSWORD + "'");
    }

    /**
     * Runs the engine's own client inside the container and fails loudly on a non-zero result.
     *
     * @param arguments the client arguments following the connection flags
     * @throws IllegalStateException if the client reports a failure, or if the calling thread is
     *     interrupted while the client is running
     * @throws UncheckedIOException if the client cannot be run at all
     */
    private static void runPsql(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-q"));
        command.addAll(List.of(arguments));
        try {
            org.testcontainers.containers.Container.ExecResult result =
                    POSTGRES.execInContainer(command.toArray(String[]::new));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("the engine client refused " + command + ": "
                        + result.getStderr() + result.getStdout());
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot run " + command, cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, cause);
        }
    }

    /**
     * Loads the four fixtures this class reads into the relations their owning services declare.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against the registered copybook descriptor, so the load carries the record layout the
     * migration is defined against rather than a second transcription of it. Hard-coding the values
     * here would let the fixture and the load drift, and the fixture is the artifact under test.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded
     */
    private static void loadTheFixtureCorpus() {
        try (Connection connection = ownerConnection()) {
            loadAccounts(connection);
            loadCustomers(connection);
            loadCards(connection);
            loadCardCrossReferences(connection);
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the reporting fixture corpus", cause);
        }
    }

    /**
     * Loads {@code acctfile.txt} into the account master.
     *
     * @param connection an open connection able to write the account schema; must not be {@code null}
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadAccounts(Connection connection) throws SQLException {
        String sql = "insert into account.accounts(account_id, active_status, curr_bal,"
                + " credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date,"
                + " curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("acctfile.txt", "ACCOUNT")) {
                insert.setLong(1, integral(row, "ACCT-ID"));
                insert.setString(2, text(row, "ACCT-ACTIVE-STATUS"));
                insert.setBigDecimal(3, money(row, "ACCT-CURR-BAL"));
                insert.setBigDecimal(4, money(row, "ACCT-CREDIT-LIMIT"));
                insert.setBigDecimal(5, money(row, "ACCT-CASH-CREDIT-LIMIT"));
                insert.setObject(6, date(row, "ACCT-OPEN-DATE"));
                insert.setObject(7, date(row, "ACCT-EXPIRAION-DATE"));
                insert.setObject(8, date(row, "ACCT-REISSUE-DATE"));
                insert.setBigDecimal(9, money(row, "ACCT-CURR-CYC-CREDIT"));
                insert.setBigDecimal(10, money(row, "ACCT-CURR-CYC-DEBIT"));
                insert.setString(11, text(row, "ACCT-ADDR-ZIP"));
                insert.setString(12, text(row, "ACCT-GROUP-ID"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code custfile.txt} into the customer master.
     *
     * <p>Assumptions: the national identifier and the government-issued identifier are replaced by
     * {@link #PLACEHOLDER_CIPHERTEXT} rather than carried across, because the target columns hold
     * ciphertext and this class exercises no cipher. No reporting projection selects either column, so
     * the substitution cannot affect an assertion.</p>
     *
     * @param connection an open connection able to write the account schema; must not be {@code null}
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCustomers(Connection connection) throws SQLException {
        String sql = "insert into account.customers(customer_id, first_name, middle_name, last_name,"
                + " addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip,"
                + " phone_num_1, phone_num_2, ssn_encrypted, govt_issued_id_encrypted, dob,"
                + " eft_account_id, pri_card_holder_ind, fico_credit_score, version)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("custfile.txt", "CUSTOMER")) {
                insert.setLong(1, integral(row, "CUST-ID"));
                insert.setString(2, text(row, "CUST-FIRST-NAME"));
                insert.setString(3, text(row, "CUST-MIDDLE-NAME"));
                insert.setString(4, text(row, "CUST-LAST-NAME"));
                insert.setString(5, text(row, "CUST-ADDR-LINE-1"));
                insert.setString(6, text(row, "CUST-ADDR-LINE-2"));
                insert.setString(7, text(row, "CUST-ADDR-LINE-3"));
                insert.setString(8, text(row, "CUST-ADDR-STATE-CD"));
                insert.setString(9, text(row, "CUST-ADDR-COUNTRY-CD"));
                insert.setString(10, text(row, "CUST-ADDR-ZIP"));
                insert.setString(11, text(row, "CUST-PHONE-NUM-1"));
                insert.setString(12, text(row, "CUST-PHONE-NUM-2"));
                insert.setBytes(13, PLACEHOLDER_CIPHERTEXT);
                insert.setBytes(14, PLACEHOLDER_CIPHERTEXT);
                insert.setObject(15, date(row, "CUST-DOB-YYYY-MM-DD"));
                insert.setString(16, text(row, "CUST-EFT-ACCOUNT-ID"));
                insert.setString(17, text(row, "CUST-PRI-CARD-HOLDER-IND"));
                insert.setShort(18, (short) integral(row, "CUST-FICO-CREDIT-SCORE"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code carddata.txt} into the card master.
     *
     * <p>Assumptions: the verification value is replaced by {@link #PLACEHOLDER_CIPHERTEXT} for the
     * reason recorded on the customer load, and additionally because the committed fixture carries a
     * placeholder rather than a value: the target column holds ciphertext and no reporting relation
     * projects it at all.</p>
     *
     * @param connection an open connection able to write the card schema; must not be {@code null}
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCards(Connection connection) throws SQLException {
        String sql = "insert into card.cards(card_num, account_id, cvv_encrypted, embossed_name,"
                + " expiration_date, active_status, version) values (?,?,?,?,?,?,0)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("carddata.txt", "CARD")) {
                insert.setString(1, text(row, "CARD-NUM"));
                insert.setLong(2, integral(row, "CARD-ACCT-ID"));
                insert.setBytes(3, PLACEHOLDER_CIPHERTEXT);
                insert.setString(4, text(row, "CARD-EMBOSSED-NAME"));
                insert.setObject(5, date(row, "CARD-EXPIRAION-DATE"));
                insert.setString(6, text(row, "CARD-ACTIVE-STATUS"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Loads {@code cardxref.txt} into the card cross-reference.
     *
     * @param connection an open connection able to write the account schema; must not be {@code null}
     * @throws SQLException if any row cannot be inserted
     */
    private static void loadCardCrossReferences(Connection connection) throws SQLException {
        String sql = "insert into account.card_xref(card_num, customer_id, account_id)"
                + " values (?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : decodeAll("cardxref.txt", "XREF")) {
                insert.setString(1, text(row, "XREF-CARD-NUM"));
                insert.setLong(2, integral(row, "XREF-CUST-ID"));
                insert.setLong(3, integral(row, "XREF-ACCT-ID"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Reads the card numbers the cross-reference fixture commits, in file order.
     *
     * @return the four committed card numbers, trimmed, never {@code null}
     */
    private static List<String> committedCardNumbers() {
        return decodeAll("cardxref.txt", "XREF").stream()
                .map(row -> text(row, "XREF-CARD-NUM"))
                .toList();
    }

    /**
     * Decodes every row of one fixture through the shared codec against its registered descriptor.
     *
     * @param fileName the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @param descriptorName the registered copybook descriptor name
     * @return one decoded field map per row, in file order, never {@code null}
     * @throws UncheckedIOException if the fixture cannot be read
     * @throws IllegalStateException if the fixture is absent from the classpath
     */
    private static List<Map<String, Object>> decodeAll(String fileName, String descriptorName) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        String resource = FIXTURE_DIRECTORY + '/' + fileName;
        try (InputStream stream = ReportingDeployedRelationIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the fixture " + resource + " is absent");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.ISO_8859_1))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(line -> FixedWidthCodec.decodeRecord(
                                line.getBytes(StandardCharsets.ISO_8859_1), spec))
                        .toList();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read the fixture " + resource, cause);
        }
    }

    /**
     * Reads one decoded character field, trimmed of the pad that carries it to its declared width.
     *
     * <p>Assumptions: the trailing blanks are trimmed because the copybook pads a character field to a
     * fixed width while the target column is variable-width for exactly the fields that hold text a
     * person reads. Loading the pad would make every comparison depend on it.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value with trailing blanks removed, never {@code null}
     */
    private static String text(Map<String, Object> row, String field) {
        return String.valueOf(row.get(field)).stripTrailing();
    }

    /**
     * Reads one decoded unsigned integral field.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's value
     */
    private static long integral(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    /**
     * Reads one decoded fixed-point money field.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's exact value, never {@code null}
     */
    private static BigDecimal money(Map<String, Object> row, String field) {
        return (BigDecimal) row.get(field);
    }

    /**
     * Reads one decoded character field holding an already-ordered calendar date.
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return that date, never {@code null}
     */
    private static LocalDate date(Map<String, Object> row, String field) {
        return LocalDate.parse(text(row, field));
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a fixed
     * number of parent steps, so this class runs identically from the reactor root and from the module
     * directory. A relative path would resolve differently between those two invocations.</p>
     *
     * @return the repository root, never {@code null}
     * @throws IllegalStateException if no ancestor carries the reactor descriptor
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("services/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory carries services/pom.xml");
    }

    /**
     * The narrowest context that can create the three repository proxies this class reads.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason both sibling integration
     * tests record: scanning the root would instantiate the orchestration client and the filter chain,
     * so a context started to read a handful of rows would additionally need a state-machine
     * identifier and an object-store bucket, and a failure to supply either would read as a masking or
     * a privilege defect.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class DeployedRelationTestApplication {
    }
}
