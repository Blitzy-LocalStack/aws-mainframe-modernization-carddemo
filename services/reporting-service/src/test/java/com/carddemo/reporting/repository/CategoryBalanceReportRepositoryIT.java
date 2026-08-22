package com.carddemo.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.reporting.domain.TransactionCategoryBalanceView;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the category-balance walk returns real rows in account, then type, then category order.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code CategoryBalanceReportRepository} carries the sort of {@code app/jcl/PRTCATBL.jcl}, whose
 * {@code STEP10R} declares {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} at
 * its line 52. The migrated expression of that sort is a DERIVED method name, so the three ordering
 * components and their sequence exist only inside an identifier: nothing in the type system relates
 * them to the printed report, and a component dropped, reversed or transposed compiles cleanly. This
 * class reads the relation the report reads, over rows an engine actually ordered, and asserts the
 * visited sequence is the declared one.
 *
 * <h2>What this class asserts that no sibling does</h2>
 *
 * <p>The sibling {@code ReportingQueryBootstrapIT} injects this role among the six and so proves its
 * derived name RESOLVES against the metamodel -- that every property path in it exists. Resolution is
 * not order: a name resolving to three real properties in the wrong sequence, or to two of the three,
 * resolves exactly as well. No other class in this module reads this relation at all;
 * {@code TransactionReportRepositoryIT} loads the same fixture into the same base table, but as
 * third-party corroboration of the zoned money regime, and it never issues this query. So this is the
 * only place the sort order of the second report in the reference set is established.
 *
 * <h2>How the relation arrives here</h2>
 *
 * <p>Assumptions: the relations are created by applying the SHIPPED definition files unchanged, in
 * the order an operator applies them, so the query runs against the real
 * {@code reporting.v_transaction_category_balances} rather than against a stand-in table. That view
 * is authored in {@code data-migration/sql/V1__reporting_views.sql} and reads
 * {@code ledger.transaction_category_balances}, which
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} owns. This
 * class defines no relation, conveys no privilege and discards nothing; loading rows is a data
 * statement and sits inside that boundary, which is the same boundary
 * {@code StatementCardXrefRepositoryIT} and {@code TransactionReportRepositoryIT} draw and the same
 * one {@code package-info.java} records for the directory.
 *
 * <p>Alternatives Considered: the plain-table harness script the heading-chunk sibling uses, which
 * starts a container in a fraction of the time this one needs. Rejected for this property. A view
 * interposes its own projection between the query and the rows, so an ordering assertion made over a
 * hand-made table would hold whatever the deployed view did with the three key columns -- and the
 * ordering is the whole subject here. The shipped files also cost nothing in fidelity: they are read,
 * never copied, so they cannot drift from the originals.
 *
 * <h2>Why the fixture is loaded in reverse</h2>
 *
 * <p>Assumptions: the seeded rows are inserted in the exact REVERSE of the order they are expected
 * back in, and that inversion is what gives the assertion force. Loaded in declared order, the rows'
 * physical sequence would equal the expected sequence, so a query that ordered by nothing at all
 * would satisfy the assertion on a small relation the engine reads with a sequential scan. Inverted,
 * every adjacent pair in the expected sequence is a pair the scan produces the other way round --
 * including the pair that differs only in the trailing category component and the pairs that differ
 * only in the middle type component -- so an omitted, reversed or transposed component has to move a
 * row for the walk to still match.
 *
 * <p>Assumptions: the arrangement was MEASURED rather than reasoned about. Against the pinned engine
 * and this corpus, every defect shape returns a sequence differing from the declared one: dropping
 * the category component yields {@code 50/01/0002} before {@code 50/01/0001}, dropping the type
 * component yields {@code 50/03/0001} before {@code 50/01/0002}, dropping the account component
 * interleaves the accounts, reversing any single component moves at least one pair, transposing the
 * account and type components regroups the whole sequence, and ordering by nothing at all returns the
 * exact reverse. So each of those eight defects fails the first case below rather than passing
 * through it.</p>
 *
 * <p>Trade-offs: for the three dropped-component shapes -- account, type and category -- that
 * measurement is an observation and not a guarantee, and saying so is the honest form of the claim.
 * Ties left by a weakened {@code ORDER BY}
 * may be returned in any sequence the engine likes, so no fixture can compel a particular one; the
 * inversion makes the sequence the engine actually produces -- scan order -- the wrong answer, which
 * is the strongest position a fixture can take. The second case below therefore asserts the corpus
 * still carries the tie groups that make each component load-bearing, so the first case cannot pass
 * vacuously after an edit to the corpus.
 *
 * <h2>Parameters, return values, exceptions or errors</h2>
 *
 * <p>This is a test class with no constructor a caller invokes, no value it yields and no exception
 * it raises outside the test engine, so the type accepts no parameter, returns nothing and raises
 * nothing. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids a docstring that omits parameters, return values or exceptions.
 */
@Testcontainers
@SpringBootTest(
        classes = CategoryBalanceReportRepositoryIT.CategoryBalanceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // WHY : Assumptions: this module carries cloud starters as compile dependencies, so a context
    //       that enables auto-configuration at all builds their beans and the region provider
    //       resolves eagerly and raises when it finds none. Nothing here contacts a cloud service,
    //       so the region identifies nothing this class reaches. Alternatives Considered: the
    //       five-property form two siblings use, which leaves both configuration importers enabled
    //       and feeds them fabricated credential strings. The three-property form is used instead,
    //       following the account-lookup sibling: switching the importers off removes the need for
    //       either string, so this class commits no credential-shaped value of any kind.
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.parameterstore.enabled=false",
    "spring.cloud.aws.secretsmanager.enabled=false"
})
class CategoryBalanceReportRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every other integration test in this repository pins, so
     * the whole suite validates against one engine build. It is a digest rather than a tag because a
     * publisher moves a major-line tag to each new minor release, and ordering under a declared
     * collation is a property an engine version can genuinely change.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The two session settings the shipped bootstrap requires before it will run.
     *
     * <p>Assumptions: both are opt-outs the bootstrap reads through {@code current_setting} with its
     * missing-value flag set, and it raises rather than proceeding when neither is on: one
     * acknowledges an unencrypted local connection, the other accepts roles arriving with no
     * credential because their source is a secret store no test has. They are applied as session
     * settings intermixed with the file option, so a setting made before the file is still in force
     * while the file executes.</p>
     */
    private static final List<String> BOOTSTRAP_ACKNOWLEDGEMENTS = List.of(
            "carddemo.bootstrap_allow_insecure",
            "carddemo.bootstrap_allow_missing_credentials");

    /**
     * The shipped definition files, repository-relative, in the order an operator applies them.
     *
     * <p>Assumptions: the list is the one the two sibling deployed-relation classes apply, entry for
     * entry, and it is repeated rather than shared because each class starts its own engine and a
     * shared holder would put one class's arrangement inside another's lifecycle. The bootstrap
     * appears TWICE on purpose: its first pass creates the schemas and the roles, while the
     * cross-schema read privileges it conveys to the projection owner are conditional on the base
     * tables existing, and on that pass none of them does. A view executes with its owner's rights,
     * so a privilege absent at creation surfaces on the first READ rather than at creation.</p>
     *
     * <p>Assumptions: all eleven entries are required even though this class reads one relation.
     * {@code V1__reporting_views.sql} creates all eight projections in one transaction and resolves
     * every base reference at creation time, so a missing account, card or reference table fails the
     * whole file and this class's own view with it.</p>
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

    /** The classpath directory holding the fixture corpus this class loads. */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The category-balance fixture, eight rows of the 50-byte record.
     *
     * <p>Assumptions: this is the committed corpus the fixture contract test already binds by name,
     * record length and row count, so this class inherits that geometry rather than restating it. It
     * is the input of {@code app/jcl/PRTCATBL.jcl}, the report whose sort this class asserts.</p>
     */
    private static final String CATEGORY_BALANCE_FIXTURE = "tcatbal.txt";

    /**
     * The registered descriptor the fixture decodes against.
     *
     * <p>Assumptions: the name is {@code TCATBAL} and never {@code TRANCAT}. Both records carry a
     * group reachable as a transaction-category key, but this one is seventeen bytes over three
     * members from {@code app/cpy/CVTRA01Y.cpy} while the other is six bytes over two from
     * {@code app/cpy/CVTRA04Y.cpy}, so decoding this fixture against that descriptor would read
     * every field from the wrong offset and still succeed.</p>
     */
    private static final String CATEGORY_BALANCE_DESCRIPTOR = "TCATBAL";

    /** The account-identifier field of the fixture record, at one-based positions 1 to 11. */
    private static final String ACCOUNT_FIELD = "TRANCAT-ACCT-ID";

    /** The type-code field of the fixture record, at one-based positions 12 to 13. */
    private static final String TYPE_FIELD = "TRANCAT-TYPE-CD";

    /** The category-code field of the fixture record, at one-based positions 14 to 17. */
    private static final String CATEGORY_FIELD = "TRANCAT-CD";

    /** The balance field of the fixture record, at one-based positions 18 to 28. */
    private static final String BALANCE_FIELD = "TRAN-CAT-BAL";

    /**
     * The number of rows the fixture carries, asserted rather than assumed.
     *
     * <p>Assumptions: the figure is eight and is stated here so that a corpus quietly reduced to one
     * row cannot leave an ordering assertion passing over a sequence with no order in it. The same
     * eight is bound independently by the fixture contract test, so the two disagree loudly rather
     * than drifting.</p>
     */
    private static final int SEEDED_ROW_COUNT = 8;

    /**
     * The sort the reference declares, expressed once over the decoded fixture rows.
     *
     * <p>Assumptions: this comparator is the INDEPENDENT expression of
     * {@code app/jcl/PRTCATBL.jcl} L52 -- account ascending, then type ascending, then category
     * ascending -- and it is what the expected sequence is derived from. Deriving the expectation
     * from a second reading of the same rule, rather than reading it back from the engine with the
     * query's own ordering, is what lets the assertion fail: an engine compared against itself
     * agrees however the query orders.</p>
     *
     * <p>Assumptions: the two code components compare as TEXT and the account identifier as a
     * NUMBER, matching the target columns -- {@code CHAR(2)}, {@code CHAR(4)} and {@code BIGINT} --
     * rather than treating all three alike. The codes are all digits in this corpus, so the two
     * comparisons coincide here; they are still written separately because the category code carries
     * leading zeros that a numeric comparison would discard, and the printed report renders them.</p>
     */
    private static final Comparator<Map<String, Object>> DECLARED_SORT =
            Comparator.<Map<String, Object>>comparingLong(row -> integral(row, ACCOUNT_FIELD))
                    .thenComparing(row -> text(row, TYPE_FIELD))
                    .thenComparing(row -> categoryCode(row, CATEGORY_FIELD));

    /**
     * The container the context connects to, started once for this class.
     *
     * <p>Assumptions: connection coordinates arrive as a bean through {@code @ServiceConnection} and
     * never as text, because the container's port is assigned at run time. A written connection
     * string would either address nothing or address whichever database happened to be listening on
     * the author's machine, and the second failure mode is the worse of the two because it passes.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The repository under test, injected so the walk goes through the declared query.
     */
    @Autowired
    private CategoryBalanceReportRepository categoryBalances;

    /**
     * Applies the shipped definitions and seeds the corpus before the context opens a connection.
     *
     * <p>Assumptions: this runs before the Spring context is built, because the container extension
     * starts the engine before any {@code @BeforeAll} method while the context is built when the
     * first test instance is prepared. So the view and its rows exist before the connection pool
     * opens. Nothing here needs to precede the pool for correctness in any case: the test profile
     * pins schema resolution to {@code reporting} and sets no schema-generation setting, so the
     * provider emits no statement that could fabricate the relation this class must not create.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @BeforeAll
    static void arrangeTheDeployedSchemaAndSeededCorpus() {
        applyDeployedSchema();
        loadCategoryBalancesInReverseDeclaredOrder();
    }

    /**
     * Confirms the walk yields every seeded balance once, in account then type then category order.
     *
     * <p>Assumptions: the stream is consumed inside a try-with-resources block and the case carries
     * a transaction annotation, because both are the method's declared contract rather than a
     * precaution. The query is declared {@code MANDATORY} and returns a cursor, so a caller with no
     * transaction is refused outright and a caller that leaks the stream leaves a server-side cursor
     * open for the rest of the transaction.</p>
     *
     * <p>Assumptions: the visited rows are compared as rendered key tuples rather than as the key
     * records themselves. The embedded key's own rendering deliberately OMITS the account identifier
     * -- it is a projection of cardholder-adjacent identity that
     * {@code docs/architecture/observability.md} rules out of a diagnostic -- so an assertion failure
     * built from the records would report a mismatch in the leading ordering component while
     * displaying neither side of it. The rendering here is fixture data in a test, not a log line, so
     * it may state the identifier and must, for the failure to be readable.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional(readOnly = true)
    @DisplayName("the walk yields every category balance in account, then type, then category order")
    void theWalkYieldsEveryBalanceInTheDeclaredOrder() {
        List<String> expected = expectedDeclaredOrder();

        List<String> visited;
        try (Stream<TransactionCategoryBalanceView> rows = categoryBalances
                .findAllByOrderByKeyAccountIdAscKeyTypeCodeAscKeyCategoryCodeAsc()) {
            visited = rows.map(CategoryBalanceReportRepositoryIT::renderKey).toList();
        }

        assertThat(expected)
                .as("the fixture supplies every seeded row to the expectation")
                .hasSize(SEEDED_ROW_COUNT);
        assertThat(visited)
                .withFailMessage("the category-balance walk must return every balance exactly once"
                        + " in the order app/jcl/PRTCATBL.jcl L52 declares -- account ascending, then"
                        + " type ascending, then category ascending. It returned %s where the"
                        + " reference sort over the same rows is %s, so at least one of the three"
                        + " ordering components is missing, reversed or transposed", visited, expected)
                .containsExactlyElementsOf(expected);
    }

    /**
     * Confirms the seeded corpus keeps all three ordering components load-bearing.
     *
     * <p>Assumptions: this case asserts a property of the CORPUS and not of the query, and it is
     * here because without it the case above could hold while checking almost nothing. A corpus of
     * one row per account would exercise only the leading component, and a corpus loaded in declared
     * order would be reproduced by a query that ordered by nothing at all. Each of the four
     * assertions below removes one of those ways to pass vacuously, and each failure message names
     * what to restore rather than merely reporting a count.</p>
     *
     * <p>Assumptions: every figure is measured from the decoded fixture rather than written out, so
     * this case follows the corpus if the corpus is legitimately changed and fails only when a change
     * costs the first case its force.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seeded balances keep all three ordering components load-bearing")
    void theSeededBalancesKeepAllThreeOrderingComponentsLoadBearing() {
        List<Map<String, Object>> declared = decodedRowsInDeclaredOrder();

        assertThat(declared.stream().map(row -> integral(row, ACCOUNT_FIELD)).distinct().count())
                .withFailMessage("the corpus must span at least two accounts, or the leading"
                        + " ordering component decides nothing; restore a row on a second account")
                .isGreaterThan(1);
        assertThat(adjacentPairsDifferingOnlyIn(declared, CATEGORY_FIELD))
                .withFailMessage("the corpus must carry two adjacent rows sharing an account and a"
                        + " type and differing only in category, or the TRAILING ordering component"
                        + " decides nothing and a query that dropped it would still pass")
                .isPositive();
        assertThat(adjacentPairsDifferingOnlyIn(declared, TYPE_FIELD))
                .withFailMessage("the corpus must carry two adjacent rows sharing an account and"
                        + " differing in type, or the MIDDLE ordering component decides nothing and a"
                        + " query that dropped it would still pass")
                .isPositive();

        List<String> expected = expectedDeclaredOrder();
        assertThat(loadOrder())
                .withFailMessage("the rows must be loaded in the reverse of the order they are"
                        + " expected back in, or the physical sequence the engine scans equals the"
                        + " expected sequence and a query with no ORDER BY at all would satisfy the"
                        + " assertion beside this one; the load order was %s", loadOrder())
                .containsExactlyElementsOf(expected.reversed());
    }

    /**
     * Confirms the walk refuses to open outside a transaction that outlives the call.
     *
     * <p>Assumptions: this case carries no transaction annotation and the reading case above carries
     * one, which is what makes the refusal observable at all. The declared propagation is mandatory,
     * so the framework raises before a statement is prepared.</p>
     *
     * <p>Assumptions: the captured type is
     * {@link org.springframework.transaction.IllegalTransactionStateException}, which the framework
     * raises for a mandatory propagation with no transaction in progress. It is that type rather than
     * a persistence or data-access type because the refusal happens in the transaction interceptor,
     * before the query reaches the provider -- naming a data-access type here would pass on any
     * failure that reached the database and would stop asserting the propagation.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the category-balance walk refuses to open with no enclosing transaction")
    void theWalkRefusesToOpenWithNoEnclosingTransaction() {
        // WHY : Assumptions: the reference brackets its whole report in one sort step --
        //       app/jcl/PRTCATBL.jcl feeds the unloaded file to its sort at L44 and L45 and writes
        //       the report at L53 to L56 -- so the scope this cursor needs is the scope the
        //       reference already used. A walk that opened its own transaction would commit it as it
        //       returned and hand back a closed cursor, and the failure would then surface at the
        //       first row and name neither the query nor the missing transaction.
        assertThatThrownBy(() -> categoryBalances
                .findAllByOrderByKeyAccountIdAscKeyTypeCodeAscKeyCategoryCodeAsc())
                .as("a mandatory propagation refuses to open a cursor into a scope that would close"
                        + " underneath it")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Reads the seeded rows in the order the reference sort declares.
     *
     * <p>Assumptions: the decoded rows are sorted here rather than trusted to arrive sorted. The
     * committed fixture happens to be written in ascending key order, and relying on that would make
     * the expectation a restatement of the file's line order -- so a re-ordered fixture would change
     * what the first case asserts instead of failing the second one.</p>
     *
     * @return the decoded fixture rows ordered by account, then type, then category, never
     *     {@code null}
     */
    private static List<Map<String, Object>> decodedRowsInDeclaredOrder() {
        List<Map<String, Object>> rows =
                new ArrayList<>(decodeAll(CATEGORY_BALANCE_FIXTURE, CATEGORY_BALANCE_DESCRIPTOR));
        rows.sort(DECLARED_SORT);
        return rows;
    }

    /**
     * Renders the key of every seeded row in the order the reference sort declares.
     *
     * @return one rendered key tuple per seeded row, in declared order, never {@code null}
     */
    private static List<String> expectedDeclaredOrder() {
        return decodedRowsInDeclaredOrder().stream()
                .map(CategoryBalanceReportRepositoryIT::renderKey)
                .toList();
    }

    /**
     * Renders the key of every seeded row in the order the rows were inserted.
     *
     * <p>Assumptions: this is derived from the same one expression the loader iterates, so the two
     * cannot disagree about what was inserted first. A second transcription of the load order would
     * let this case report an inversion the loader had stopped applying.</p>
     *
     * @return one rendered key tuple per seeded row, in insertion order, never {@code null}
     */
    private static List<String> loadOrder() {
        return rowsInLoadOrder().stream()
                .map(CategoryBalanceReportRepositoryIT::renderKey)
                .toList();
    }

    /**
     * Reads the seeded rows in the order they are inserted, which is the reverse of declared order.
     *
     * @return the decoded fixture rows in insertion order, never {@code null}
     */
    private static List<Map<String, Object>> rowsInLoadOrder() {
        return decodedRowsInDeclaredOrder().reversed();
    }

    /**
     * Counts adjacent pairs in declared order whose relative order only one component decides.
     *
     * <p>Assumptions: adjacency is what makes a component load-bearing. Two rows differing in a
     * component decide nothing about that component's presence in the ordering unless they are
     * NEIGHBOURS in the declared sequence -- a component only ever settles the order of rows that
     * every earlier component ties on, and those rows are adjacent once sorted.</p>
     *
     * @param declared the decoded rows in declared order
     * @param field the copybook field name of the component under test, either the type code or the
     *     category code
     * @return the number of adjacent pairs that agree on every component preceding {@code field} and
     *     differ on {@code field} itself
     */
    private static long adjacentPairsDifferingOnlyIn(
            List<Map<String, Object>> declared, String field) {
        long pairs = 0;
        for (int index = 1; index < declared.size(); index++) {
            Map<String, Object> earlier = declared.get(index - 1);
            Map<String, Object> later = declared.get(index);
            if (integral(earlier, ACCOUNT_FIELD) != integral(later, ACCOUNT_FIELD)) {
                continue;
            }
            boolean sameType = text(earlier, TYPE_FIELD).equals(text(later, TYPE_FIELD));
            if (CATEGORY_FIELD.equals(field)
                    && sameType
                    && !categoryCode(earlier, CATEGORY_FIELD)
                            .equals(categoryCode(later, CATEGORY_FIELD))) {
                pairs++;
            }
            if (TYPE_FIELD.equals(field) && !sameType) {
                pairs++;
            }
        }
        return pairs;
    }

    /**
     * Renders one seeded fixture row's key as a single comparable tuple.
     *
     * @param row a decoded field map
     * @return the account identifier, the type code and the category code separated by solidi, with
     *     the category code's leading zeros intact, never {@code null}
     */
    private static String renderKey(Map<String, Object> row) {
        return integral(row, ACCOUNT_FIELD)
                + "/" + text(row, TYPE_FIELD)
                + "/" + categoryCode(row, CATEGORY_FIELD);
    }

    /**
     * Renders one materialised view row's key as a single comparable tuple.
     *
     * <p>Assumptions: the two code components are stripped of trailing pad before rendering, because
     * the columns are fixed-width character types and the fixture side of the comparison is stripped
     * for the same reason. Every value in this corpus occupies its full declared width, so the strip
     * removes nothing today; it is applied so that a future fixture cannot make one side padded and
     * the other not.</p>
     *
     * @param view one row the repository returned
     * @return the same rendering {@link #renderKey(Map)} produces for the fixture row behind it,
     *     never {@code null}
     */
    private static String renderKey(TransactionCategoryBalanceView view) {
        TransactionCategoryBalanceView.TransactionCategoryBalanceKey key = view.getKey();
        return key.accountId()
                + "/" + key.typeCode().stripTrailing()
                + "/" + key.categoryCode().stripTrailing();
    }

    /**
     * Applies every shipped definition file, unchanged, in the order an operator applies them.
     *
     * <p>Assumptions: each file is copied into the container and executed by the engine's OWN client
     * rather than sent through the driver, and the reason is syntactic. Two of the files wrap
     * themselves in an explicit transaction and several carry dollar-quoted procedural blocks whose
     * bodies hold semicolons, so any client-side splitting on a statement terminator would cut them
     * in half.</p>
     *
     * <p>Assumptions: the host paths are resolved from the repository root located by probe rather
     * than by a fixed number of parent steps, so this class runs identically from the module
     * directory and from the reactor root.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if a shipped file is absent, naming the path so the defect is
     *     reported against the file that owns it
     */
    private static void applyDeployedSchema() {
        Path root = repositoryRoot();
        int sequence = 0;
        for (String script : DEPLOYED_SCHEMA_SCRIPTS) {
            Path source = root.resolve(script);
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("the shipped definition file " + source
                        + " is absent, so this class cannot assert anything about the relation the"
                        + " category-balance report reads; that file owns it and this class must not"
                        + " create it");
            }
            // WHY : Assumptions: the target name carries a counter because the bootstrap appears
            //       twice in the list and a shared name would let the second copy overwrite the
            //       first while it was still being read.
            String target = "/tmp/deployed-" + sequence + ".sql";
            sequence++;
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(source), target);
            List<String> arguments = new ArrayList<>(List.of("-v", "ON_ERROR_STOP=1"));
            for (String acknowledgement : BOOTSTRAP_ACKNOWLEDGEMENTS) {
                arguments.add("-c");
                arguments.add("set " + acknowledgement + " = 'on'");
            }
            arguments.add("-f");
            arguments.add(target);
            runEngineClient(arguments.toArray(String[]::new));
        }
    }

    /**
     * Runs the engine's own client inside the container and fails loudly on a non-zero result.
     *
     * <p>Assumptions: the client is invoked without a credential, because it runs inside the
     * container as the operating-system user the engine trusts locally. That is what lets this class
     * apply the shipped files with no credential written anywhere in it.</p>
     *
     * @param arguments the client arguments following the connection flags
     * @throws IllegalStateException if the client reports a failure, or if the calling thread is
     *     interrupted while the client is running
     * @throws UncheckedIOException if the client cannot be run at all
     */
    private static void runEngineClient(String... arguments) {
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
     * Loads the fixture into the ledger relation, deliberately inverting the declared order.
     *
     * <p>Assumptions: every value inserted is DECODED from the committed fixture through the shared
     * codec against its registered descriptor, so the load carries the record layout the migration
     * is defined against rather than a second transcription of it. Alternatives Considered: writing
     * eight rows out as literals here, which would be shorter. Rejected because the descriptor
     * registry is the one place the record's offsets are declared, and a literal corpus would stop
     * being the corpus the fixture contract test binds.</p>
     *
     * <p>Assumptions: the insert names the BASE table and the assertions read the VIEW over it,
     * which is deliberate rather than inconsistent -- the view publishes no write path at all, and
     * routing the load through the base table is what keeps this class from needing one.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if any row cannot be loaded
     */
    private static void loadCategoryBalancesInReverseDeclaredOrder() {
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement insert = connection.prepareStatement(
                        "insert into ledger.transaction_category_balances(account_id, type_cd,"
                                + " category_cd, balance) values (?,?,?,?)")) {
            // WHY : Assumptions: the rows are inserted ONE STATEMENT AT A TIME rather than as one
            //       batch, because the inversion only reaches the engine if the arrival order does.
            //       A batch is executed in order too, but nothing in the interface guarantees the
            //       stored order follows it, and the whole force of the arrangement rests on the
            //       physical sequence disagreeing with the expected sequence.
            for (Map<String, Object> row : rowsInLoadOrder()) {
                insert.setLong(1, integral(row, ACCOUNT_FIELD));
                insert.setString(2, text(row, TYPE_FIELD));
                insert.setString(3, categoryCode(row, CATEGORY_FIELD));
                insert.setBigDecimal(4, money(row, BALANCE_FIELD));
                insert.executeUpdate();
            }
        } catch (SQLException cause) {
            throw new IllegalStateException("cannot load the category-balance corpus", cause);
        }
    }

    /**
     * Decodes every row of one fixture through the shared codec against its registered descriptor.
     *
     * <p>Assumptions: the fixture is line-oriented with one record per line, and the newline is a
     * FILE convention that is not part of any record -- the declared length excludes it, so reading
     * the fixture as one continuous byte stream would mis-align every record after the first. The
     * bytes are taken in a single-byte encoding so that one character is one byte and every declared
     * offset holds.</p>
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
        try (InputStream stream = CategoryBalanceReportRepositoryIT.class.getClassLoader()
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
     * Reads one decoded category code back into the zero-padded character form the column carries.
     *
     * <p>Assumptions: the descriptor declares the category code as an unsigned DISPLAY integer, so it
     * decodes to a number and loses the leading zeros the record holds, while the target column is a
     * four-character code in which those zeros are part of the value. Re-padding here is what keeps
     * the code that is written equal to the code the record declares, and it is also what the sort
     * orders on -- {@code app/jcl/PRTCATBL.jcl} L52 sorts the four bytes as they stand.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the four-character zero-padded code, never {@code null}
     */
    private static String categoryCode(Map<String, Object> row, String field) {
        return String.format("%04d", integral(row, field));
    }

    /**
     * Reads one decoded monetary field as an exact decimal.
     *
     * <p>Assumptions: the value stays an exact decimal from the codec to the column and never passes
     * through an approximate numeric type. A binary floating-point hop would round a value the record
     * holds exactly, and the rounding would be invisible until a total disagreed.</p>
     *
     * @param row a decoded field map
     * @param field the copybook field name
     * @return the field's exact value, never {@code null}
     */
    private static BigDecimal money(Map<String, Object> row, String field) {
        return (BigDecimal) row.get(field);
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a count of
     * parent steps, so this class runs identically from the reactor root and from the module
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
     * The narrowest context that can create a repository proxy for this package.
     *
     * <p>Assumptions: the configuration is nested and names the two persistence packages explicitly
     * rather than component-scanning from the module root, for the reason the sibling integration
     * tests record: scanning the root would instantiate the orchestration client and the filter
     * chain, so a context started to read eight rows would additionally need a state-machine
     * identifier and an object-store bucket, and a failure to supply either would read as an ordering
     * defect.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reporting.domain")
    @EnableJpaRepositories("com.carddemo.reporting.repository")
    static class CategoryBalanceTestApplication {
    }
}
