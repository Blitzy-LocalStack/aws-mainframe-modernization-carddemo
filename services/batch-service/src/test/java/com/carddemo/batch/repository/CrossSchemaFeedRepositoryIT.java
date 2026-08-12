package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.batch.domain.TransactionReject;
import com.carddemo.batch.dto.RejectReason;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.data.domain.Limit;
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
 * Holds the harness post-state and the six cross-schema repositories against a real engine.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this module maps ten entities across five schemas and owns only one of the five. The
 * nine mappings that address the other four -- ledger, account, reference and card -- are the migration
 * plan's claim that a batch job can read a sibling context's table under a scoped grant without a
 * service call, and until this class existed nothing executed them. What it asserts is therefore the
 * JOIN between two artifacts: the nine foreign tables the init script mirrors, and the repository
 * interfaces and entity mappings that address them.</p>
 *
 * <p>Refactoring Rationale: a mirrored schema drifts silently. The init script reproduces column names,
 * widths, nullability and constraints from four other modules' migrations, and nothing compiles the
 * two against each other -- so a column renamed in an owning migration leaves the mirror correct-looking
 * and this module's entity mapping pointing at a name production no longer has. Executing every mapping
 * against the mirror is what converts that drift from a silent divergence into a failing test, and the
 * assertions below are deliberately phrased on the columns most likely to drift: the fixed-width keys,
 * the money scales and the reject stream's three-column decomposition of a 430-byte record.</p>
 *
 * <h2>What each group covers</h2>
 *
 * <p>Assumptions: the reject stream is asserted at its exact widths because the 430-byte contract is
 * the one output of this module that a downstream consumer parses positionally. Its composition is
 * corroborated from four independent directions in the reference -- the file description at
 * {@code app/cbl/CBTRN02C.cbl} L83 to L84, the working-storage declarations at its L177, L181 and L182,
 * the {@code LRECL=430} of {@code app/jcl/POSTTRAN.jcl} L36, and the committed goldens -- so the widths
 * are a contract rather than a choice.</p>
 *
 * <p>Assumptions: the seeded DEFAULT disclosure rows are asserted because their absence is invisible
 * until it is expensive. When a direct group lookup misses, {@code app/cbl/CBACT04C.cbl} L436 tests for
 * VSAM status 23, L437 substitutes the DEFAULT group and L438 retries -- and that retry has no
 * invalid-key clause, so a missing DEFAULT row reaches {@code 9999-ABEND-PROGRAM} and abends. The
 * observable symptom is a crash attributed to the interest job while the interest job behaves exactly as
 * specified.</p>
 *
 * <p>Assumptions: the two export-only masters are asserted for their ORDERING as well as for their
 * resolution, which the four older groups do not all need. Their walks feed a dataset whose record
 * sequence numbers are assigned in write order, so a walk that returned the right set in the wrong
 * order would renumber the export between two runs over identical data -- a difference no assertion
 * about the set could see. The card master additionally proves that a schema absent from the
 * connection's search path resolves through the entity's own qualification.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is disabled for this context,
//       because application.yml's `optional:aws-parameterstore:` location builds an SSM client while
//       configuration is still loading and a build host with no AWS_REGION aborts the context on the
//       unresolved placeholder. The sibling BatchRunRepositoryIT records the full measurement, why
//       disabling the LOAD is sufficient when the resolver ignores the flag, and the two rejected
//       alternatives; it is cited from here rather than repeated.
@SpringBootTest(
        classes = CrossSchemaFeedRepositoryIT.CrossSchemaPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class CrossSchemaFeedRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite. The version is recorded in prose because a digest states nothing a
     * reader recognises, and the two must be changed together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness this class is the primary consumer of.
     *
     * <p>Assumptions: every table this class reads or writes comes from that script, so a renamed or
     * moved file is reported by the very first case below rather than as an undefined-table error
     * somewhere later.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /** The account the seeded cross-references resolve to. */
    private static final long ACCOUNT_ID = 10000000077L;

    /** The customer the seeded cross-references name. */
    private static final long CUSTOMER_ID = 900000077L;

    /**
     * The lower of the two card numbers seeded against one account.
     *
     * <p>Assumptions: both values are sixteen characters, the width {@code XREF-CARD-NUM PIC X(16)}
     * declares at L5 of {@code app/cpy/CVACT03Y.cpy}, and both begin with a zero. The leading zero is
     * the reason these particular values were chosen: the column is fixed-width CHARACTER, so a numeric
     * mapping would have discarded it and the lookup would then miss.</p>
     */
    private static final String CARD_NUM_LOW = "0000000000000077";

    /** The higher of the two card numbers seeded against one account, sorting after the lower. */
    private static final String CARD_NUM_HIGH = "0000000000000078";

    /** The number of DEFAULT disclosure rows the harness seeds, one per type and category pair. */
    private static final int DEFAULT_GROUP_ROW_COUNT = 17;

    /**
     * The space-padded DEFAULT group identifier, at the declared width.
     *
     * <p>Assumptions: the padding is written out rather than trimmed, because the column is
     * {@code CHAR(10)} and the harness records why: a trimmed value compared against a padded one turns
     * every interest lookup into a miss, and the baseline resolves a miss by falling back rather than by
     * reporting an error -- so the defect would be silent.</p>
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT   ";

    /**
     * The number of feed rows the keyset scan case loads.
     *
     * <p>Assumptions: five rows against a chunk of two yields two full chunks that each report a
     * continuation and a third holding one row, so the loop's exit is reached by exhaustion rather than
     * by a count the case already knew.</p>
     */
    private static final int FEED_ROWS = 5;

    /** The rows one chunk of the keyset scan carries, deliberately smaller than the feed. */
    private static final int CHUNK_ROWS = 2;

    /**
     * The originating instant every seeded feed row carries.
     *
     * <p>Assumptions: a literal drawn from record 1 of {@code app/data/ASCII/dailytran.txt}, never a
     * clock read. The reference suite injects business dates rather than reading them for the same
     * reason: a value compared against the current time passes for an unrelated reason.</p>
     */
    private static final LocalDateTime ORIGINATED_AT = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} rather than the
     * deprecated {@code org.testcontainers.containers}, and carries no type argument because the
     * replacement is not generic.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The pre-posting feed, injected as the production repository interface. */
    @Autowired
    private DailyTransactionRepository feed;

    /** The reject stream, injected as the production repository interface. */
    @Autowired
    private TransactionRejectRepository rejects;

    /** The cross-reference, injected as the production repository interface. */
    @Autowired
    private CardXrefRepository crossReferences;

    /** The disclosure-group rates, injected as the production repository interface. */
    @Autowired
    private DisclosureGroupRepository disclosureGroups;

    /** The customer master, injected as the production repository interface. */
    @Autowired
    private CustomerRepository customers;

    /** The card master, injected as the production repository interface. */
    @Autowired
    private CardRepository cards;

    /** The persistence context, used to persist seeds and to detach between write and read. */
    @Autowired
    private EntityManager entityManager;

    /** The boundary every write below is issued inside, because the pool does not auto-commit. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, for the catalog assertions no entity can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the Flyway pair is registered beside the datasource pair because
    //       application.yml binds spring.flyway.user and spring.flyway.password to placeholders with no
    //       fallback, and Boot consults those keys precisely when no connection-details bean supplies
    //       them -- which is this module's case. The package charter records the full reasoning.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the three written tables before each case.
     *
     * <p>Assumptions: {@code reference.disclosure_groups} is NOT emptied, because the harness seeds it
     * once at container start and a case that deleted those rows would leave every later case unable to
     * find the fallback. The three tables that ARE emptied are the ones cases here write.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void emptyWrittenTables(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM ledger.daily_transactions");
            this.jdbc.update("DELETE FROM ledger.transaction_rejects");
            this.jdbc.update("DELETE FROM account.card_xref");
            // WHY : Assumptions: the two export-only masters are emptied here as well, and they are
            //       seeded through plain SQL rather than through the persistence context because
            //       both projections are immutable and map neither their enciphered columns nor
            //       their version column -- so there is no entity path that could supply
            //       account.customers.ssn_encrypted, which the owning schema declares NOT NULL.
            //       That is the mapping working as intended rather than a gap in it.
            this.jdbc.update("DELETE FROM account.customers");
            this.jdbc.update("DELETE FROM card.cards");
        });
    }

    /**
     * Confirms the harness established every schema and table this module maps.
     *
     * <p>Assumptions: this is asserted FIRST and by name, so a renamed or moved init script is reported
     * as the missing post-state it is rather than as an unrelated-looking query failure inside a later
     * case. The classpath-relative path has no compiler to check it.</p>
     *
     * <p>Assumptions: the nine foreign tables are asserted as a closed set rather than by presence
     * alone. A harness that had grown a tenth table would be mirroring structure no test can reach,
     * which is the drift surface the script's own rationale sets out to keep closed.</p>
     *
     * <p>Refactoring Rationale: the set grew from seven tables in three schemas to nine in four, and
     * the two additions are {@code account.customers} and {@code card.cards}. They are here because the
     * export job now reads all five masters {@code app/cbl/CBEXPORT.cbl} reads -- it read three, and its
     * customer and card phases logged a heading, reported zero and emitted nothing. Hibernate validates
     * every mapped table at context start, so without these two the whole module fails to start rather
     * than one query failing, which is exactly what this case exists to report by name.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the init script established the four foreign schemas and their nine tables")
    void theHarnessEstablishedItsPostState() {
        List<String> tables = this.jdbc.queryForList(
                "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                        + " WHERE table_schema IN ('ledger', 'account', 'reference', 'card')"
                        + " ORDER BY table_schema, table_name",
                String.class);

        // WHY : ⚠️ Refactoring Rationale: this expectation named account.customers and card.cards THREE
        //       TIMES each and is corrected to one occurrence apiece. The query selects from
        //       information_schema.tables, which reports a table once, so a repeated element made the
        //       assertion unsatisfiable by any database -- it demanded thirteen rows from a nine-row
        //       result and reported the two additions as MISSING, which reads as a harness that never
        //       created them. Assumptions: the ordering asserted is the query's own
        //       ORDER BY table_schema, table_name and not a hand-kept sequence, which is why the two
        //       additions sit third and fourth rather than at the end.
        assertThat(tables).containsExactly(
                "account.accounts",
                "account.card_xref",
                "account.customers",
                "card.cards",
                "ledger.daily_transactions",
                "ledger.transaction_category_balances",
                "ledger.transaction_rejects",
                "ledger.transactions",
                "reference.disclosure_groups");
    }

    /**
     * Confirms the customer master is readable through its projection, in customer-identifier order.
     *
     * <p>Purpose: this is the first half of the seam the export depends on, and until it existed the
     * customer phase of {@code ExportJob} emitted nothing at all. Two properties are asserted together
     * because each is worthless without the other: that the walk RESOLVES against a table in a schema
     * this module reads under a grant, and that it arrives in the key order the reference's sequential
     * read returns.</p>
     *
     * <p>Assumptions: the rows are seeded through plain SQL and are seeded OUT OF ORDER, so the
     * ascending result is produced by the query's own ordering clause rather than by insertion order.
     * A relational read guarantees no order unless one is requested, and an unordered walk would pass an
     * insertion-ordered assertion on most engines while renumbering the export's records between runs.
     * </p>
     *
     * <p>Assumptions: the enciphered columns are supplied here because the owning schema declares
     * {@code ssn_encrypted} NOT NULL, and they are supplied as ciphertext-shaped bytes rather than as
     * readable text. What matters for this case is that a row carrying them reads back through a
     * projection that maps neither, which is exactly the state the registered divergence
     * {@code D-EXPORT-PROTECTED-FIELDS-ELIDED} describes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the customer master reads back through its projection in identifier order")
    void theCustomerMasterWalksInIdentifierOrder() {
        this.transactionTemplate.executeWithoutResult(status -> {
            seedCustomer(200L, "SECOND");
            seedCustomer(100L, "FIRST");
        });

        List<Long> identifiers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        this.transactionTemplate.executeWithoutResult(status ->
                this.customers.findAllByOrderByCustomerIdAsc().forEach(row -> {
                    identifiers.add(row.getCustomerId());
                    names.add(row.getFirstName());
                }));

        assertThat(identifiers)
                .as("ascending customer identifier, the record-key order of CBEXPORT.cbl:38")
                .containsExactly(100L, 200L);
        assertThat(names)
                .as("each row carries its own mapped columns, not another row's")
                .containsExactly("FIRST", "SECOND");
    }

    /**
     * Confirms the card master is readable through its projection, in card-number order.
     *
     * <p>Purpose: this is the second half of that seam, and it additionally proves the ONE schema this
     * module reaches by name alone. The connection's search path is
     * {@code batch,ledger,account,reference} and does not include {@code card}, so a walk that resolved
     * here proves the entity's explicit schema qualification is doing its job -- a mapping that relied
     * on the search path would fail with an undefined table instead.</p>
     *
     * <p>Assumptions: the seeded card numbers differ in their LAST digit and are inserted in descending
     * order, so an unordered or a reversed walk is distinguishable from the ascending one the reference
     * returns. Assumptions: the ordering is a BYTE ordering over a character key rather than a numeric
     * one, which is why a leading-zero card number is seeded as the lower of the two: a numeric
     * ordering would place it differently and the record-key order the reference walks is character.
     * </p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the card master reads back through its projection in card-number order")
    void theCardMasterWalksInCardNumberOrder() {
        this.transactionTemplate.executeWithoutResult(status -> {
            seedCard("4111111111111111", 555L);
            seedCard("0111111111111111", 555L);
        });

        List<String> cardNumbers = new ArrayList<>();
        this.transactionTemplate.executeWithoutResult(status ->
                this.cards.findAllByOrderByCardNumAsc()
                        .forEach(row -> cardNumbers.add(row.getCardNum())));

        assertThat(cardNumbers)
                .as("ascending card number as CHARACTERS, the record-key order of CBEXPORT.cbl:62")
                .containsExactly("0111111111111111", "4111111111111111");
    }

    /**
     * Seeds one customer row, including the two columns the batch projection does not map.
     *
     * @param customerId the identifier the row is keyed on
     * @param firstName the first name, used to prove each row carries its own columns
     */
    private void seedCustomer(long customerId, String firstName) {
        this.jdbc.update("INSERT INTO account.customers (customer_id, first_name, middle_name,"
                        + " last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd,"
                        + " addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn_encrypted,"
                        + " govt_issued_id_encrypted, dob, eft_account_id, pri_card_holder_ind,"
                        + " fico_credit_score) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?)",
                customerId, firstName, "MIDDLE", "LAST", "ADDR ONE", "ADDR TWO", "ADDR THREE",
                "TX", "USA", "78727", "5125551212", "5125551213",
                new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8},
                java.sql.Date.valueOf("1980-05-17"), "EFT0000001", "Y", (short) 750);
    }

    /**
     * Seeds one card row, including the enciphered column the batch projection does not map.
     *
     * @param cardNum the sixteen-character card number the row is keyed on
     * @param accountId the account the card belongs to
     */
    private void seedCard(String cardNum, long accountId) {
        this.jdbc.update("INSERT INTO card.cards (card_num, account_id, cvv_encrypted,"
                        + " embossed_name, expiration_date, active_status)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                cardNum, accountId, new byte[] {9, 10}, "CARD HOLDER NAME",
                java.sql.Date.valueOf("2030-01-01"), "Y");
    }

    /**
     * Confirms the secondary index that stands in for the alternate index exists.
     *
     * <p>Assumptions: the index is asserted by NAME and by the column it covers. Its name is the owning
     * migration's, so a mirror that invented a different one would stop being diffable against the
     * authority; and its purpose is the access path, so the covered column is the half that matters.
     * {@code app/csd/CARDDEMO.CSD} L63 defines {@code CXACAIX} and its own description at L64 reads
     * ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY -- the base cluster cannot serve that path, because
     * {@code app/cbl/CBACT03C.cbl} L32 declares the record key as the card number.</p>
     *
     * <p>Assumptions: the index is deliberately NON-unique, and the by-account read below proves it, so
     * this case asserts existence and the paired case asserts the multiplicity the index admits.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cross-reference carries the by-account index that replaces CXACAIX")
    void theByAccountIndexExists() {
        String definition = this.jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes"
                        + " WHERE schemaname = 'account' AND indexname = 'idx_card_xref_account_id'",
                String.class);

        assertThat(definition)
                .as("the alternate-index path must exist as a real secondary index")
                .contains("account.card_xref")
                .contains("(account_id)");
        assertThat(definition)
                .as("the path admits several cards per account, so the index is not unique")
                .doesNotContain("UNIQUE");
    }

    /**
     * Confirms both cross-reference reads resolve, and that the account path admits several cards.
     *
     * <p>Assumptions: two rows are seeded against ONE account, which is what makes the by-account read
     * meaningful. A single row would satisfy the assertion without showing that the path is
     * non-unique, and the reference's own alternate index is non-unique precisely because an account
     * legitimately holds more than one card.</p>
     *
     * <p>Assumptions: the by-account read returns the LOWEST card number, matching the ordering its
     * method name declares. Asserting merely that it returned something would pass for a query that
     * ordered the other way, and the reference reads its alternate index forward.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cross-reference resolves by card and by account, lowest card first")
    void bothCrossReferenceReadsResolve() {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.entityManager.persist(new CardXref(CARD_NUM_HIGH, CUSTOMER_ID, ACCOUNT_ID));
            this.entityManager.persist(new CardXref(CARD_NUM_LOW, CUSTOMER_ID, ACCOUNT_ID));
            this.entityManager.flush();
            this.entityManager.clear();
        });

        Optional<CardXref> byCard = this.crossReferences.findByCardNum(CARD_NUM_LOW);
        assertThat(byCard).isPresent();
        assertThat(byCard.orElseThrow().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(byCard.orElseThrow().getCardNum())
                .as("the fixed-width key compares equal on the way out, leading zero intact")
                .isEqualTo(CARD_NUM_LOW);

        assertThat(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                .isPresent()
                .get()
                .extracting(CardXref::getCardNum)
                .as("the by-account read is ordered, so the lower of the two cards is returned")
                .isEqualTo(CARD_NUM_LOW);

        assertThat(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID + 1))
                .as("an account with no cross-referenced card misses rather than failing")
                .isEmpty();
    }

    /**
     * Confirms the feed's keyset scan walks the whole feed in ingestion order across chunks.
     *
     * <p>Alternatives Considered: offset pagination, rejected on a defect rather than a preference.
     * Positioning by counting rows from the start of an ordered set means an insert landing before the
     * cursor changes how many rows precede it, so a scan positioned that way skips rows it never read
     * and repeats rows it already read. A feed is exposed to exactly that, being loaded by one process
     * while a job walks it. The production job walks this feed by key for that reason, and this case
     * exercises the same repository method it uses.</p>
     *
     * <p>Assumptions: the identity assigned by the DATABASE is the ordering, and an in-memory
     * substitute could not carry the assertion. The rows are written in a known order and their
     * assigned sequences are read back, so a scan that ordered by anything else -- a transaction
     * identifier the feed may repeat, for instance -- would produce a different traversal.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the feed scan walks every row in ingestion order across three chunks")
    void theFeedScanWalksEveryRowInOrder() {
        seedFeed();

        List<Long> visited = new ArrayList<>();
        long cursor = 0L;
        int chunks = 0;
        while (true) {
            List<DailyTransaction> chunk = this.feed
                    .findByIngestSeqGreaterThanOrderByIngestSeqAsc(cursor, Limit.of(CHUNK_ROWS));
            if (chunk.isEmpty()) {
                break;
            }
            chunks++;
            for (DailyTransaction row : chunk) {
                visited.add(row.getIngestSeq());
                cursor = row.getIngestSeq();
            }
        }

        assertThat(visited).hasSize(FEED_ROWS);
        assertThat(visited).isSorted();
        assertThat(chunks)
                .as("five rows at a chunk of two need three reads, the last one short")
                .isEqualTo(3);

        List<DailyTransaction> firstChunk = this.feed
                .findByIngestSeqGreaterThanOrderByIngestSeqAsc(0L, Limit.of(CHUNK_ROWS));
        assertThat(firstChunk).hasSize(CHUNK_ROWS);
        assertThat(firstChunk.getFirst().getProcTs())
                .as("a feed row carries NO processing stamp: posting is what assigns one, which is why"
                        + " the column is nullable")
                .isNull();
        assertThat(firstChunk.getFirst().getOrigTs())
                .as("the originating stamp is present on a feed row and keeps its microseconds")
                .isEqualTo(ORIGINATED_AT);
    }

    /**
     * Confirms the reject stream stores the 430-byte contract as its three columns.
     *
     * <p>Assumptions: the raw record is asserted at exactly 350 characters INCLUDING its trailing
     * padding, because the reference copies the daily-transaction image verbatim -- padding and all --
     * at {@code app/cbl/CBTRN02C.cbl} L447. It is a byte image and not a parsed record, so a column
     * that trimmed it would break a positional consumer without failing anything else.</p>
     *
     * <p>Assumptions: the reason code is asserted as a NUMBER and the wire form is asserted separately
     * through the enumeration's own rendering. This is the single most confusable column in the schema:
     * the four-digit zero-padded {@code 0102} that appears in the fixtures and goldens is what
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} produces when the trailer is written as bytes, so
     * modelling the column as four characters is the natural mistake. The owner declares a small
     * integer, and the padding belongs to the byte image rather than to the column.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reject row stores the 350-byte image, the numeric reason and its exact description")
    void aRejectRowStoresTheFourHundredAndThirtyByteContract() {
        RejectReason reason = RejectReason.OVER_CREDIT_LIMIT;
        String image = rawImage();

        this.transactionTemplate.executeWithoutResult(status -> {
            this.rejects.save(new TransactionReject(
                    image, (short) reason.code(), reason.description()));
            this.entityManager.flush();
            this.entityManager.clear();
        });

        List<TransactionReject> stored = this.jdbc.query(
                "SELECT reject_seq, raw_record, reason_code, reason_desc"
                        + " FROM ledger.transaction_rejects ORDER BY reject_seq",
                (row, index) -> new TransactionReject(
                        row.getString("raw_record"),
                        row.getShort("reason_code"),
                        row.getString("reason_desc")));

        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getRawRecord())
                .as("the image is stored at its full declared width, padding included")
                .hasSize(350)
                .isEqualTo(image);
        assertThat(stored.getFirst().getReasonCode())
                .as("the reason is a NUMBER in the column and four zero-padded digits only on the wire")
                .isEqualTo((short) 102);
        assertThat(stored.getFirst().getReasonDesc())
                .as("the description is carried across character for character from the reference")
                .isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(reason.trailerField())
                .as("the 80-byte trailer renders the same reason as four digits and a 76-character"
                        + " description, which is the wire form the column is not")
                .hasSize(RejectReason.TRAILER_WIDTH)
                .startsWith("0102");

        Long generated = this.jdbc.queryForObject(
                "SELECT reject_seq FROM ledger.transaction_rejects", Long.class);
        assertThat(generated)
                .as("the key is a surrogate identity because the reference writes rejects to a"
                        + " sequential stream with no key at all, so two rejects may be identical")
                .isNotNull();
    }

    /**
     * Confirms the reject stream's check constraint refuses a reason outside the declared width.
     *
     * <p>Assumptions: the constraint admits the whole four-digit range rather than an enumeration of
     * the four documented reasons, and that latitude is the owner's rather than an oversight -- a fifth
     * value, 109, is set at {@code app/cbl/CBTRN02C.cbl} L556 inside the account-rewrite invalid-key
     * path. The refused value is therefore five digits, which no {@code PIC 9(04)} field can hold.</p>
     *
     * <p>Assumptions: the row is written through plain SQL because the entity's own constructor rejects
     * the value before the database sees it. Both guards are wanted: the entity refuses a value an
     * application path could supply, and the constraint refuses one the ETL or an operator could.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reason code outside the four-digit range is refused by the check constraint")
    void anOutOfRangeRejectReasonIsRefused() {
        assertThatThrownBy(() -> this.transactionTemplate.executeWithoutResult(status ->
                this.jdbc.update(
                        "INSERT INTO ledger.transaction_rejects (raw_record, reason_code, reason_desc)"
                                + " VALUES (?, 10000, ?)",
                        rawImage(), "OVERLIMIT TRANSACTION")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transaction_rejects_reason_code");

        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM ledger.transaction_rejects", Integer.class))
                .as("the refused row was not written")
                .isZero();
    }

    /**
     * Confirms the DEFAULT disclosure rows the interest fallback depends on are reachable.
     *
     * <p>Assumptions: the row count is asserted as well as one rate, because the fallback needs a row
     * for EVERY type and category pair an account can carry -- a partial seed would fall back
     * successfully for some pairs and abend for others, which is the hardest version of this failure to
     * diagnose.</p>
     *
     * <p>Assumptions: two rates are asserted rather than one, and the pairs are chosen for opposite
     * reasons. The {@code ('07','0001')} pair is the single field on which the two shipped encodings of
     * this dataset disagree -- record 34 of {@code app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS} reads
     * <code>'00150{'</code> while row 34 of {@code app/data/ASCII/discgrp.txt} reads
     * <code>'00000{'</code>, which under {@code DIS-INT-RATE PIC S9(04)V99} decode to 15.00 and 0.00 --
     * so it is the one row where a wrongly transcribed harness would let a fallback account accrue no
     * interest at all with no error raised either way. The {@code ('02','0001')} pair is asserted
     * because all three sources agree it is zero, which is what keeps a legitimately zero rate
     * distinguishable from a row the seed omitted.</p>
     *
     * <p>Assumptions: the authoritative reading of the discriminating pair is 15.00, the EBCDIC extract
     * deciding wherever a dataset ships in both encodings. What settles it is the baseline's own load
     * job rather than a preference: {@code app/jcl/DISCGRP.jcl} L56-L61 defines the VSAM cluster and
     * REPROs into it from {@code DSN=AWS.M2.CARDDEMO.DISCGRP.PS}, and no job in {@code app/jcl} loads
     * the {@code .txt} form at all. That settlement is registered as {@code D-SEED-ENCODING-AUTHORITY}
     * in {@code docs/architecture/cobol-to-service-traceability.md} and is held mechanically by
     * {@code DisclosureGroupSeedParityTest}, which compares the extract, this module's harness and the
     * owning migration {@code V2__seed_reference.sql} and fails when any pair of the three disagrees.</p>
     *
     * <p>Refactoring Rationale: this case previously expected 0.00 here and argued that the harness had
     * followed the baseline where a sibling migration had not. That reasoning was right that the
     * immutable data decides and wrong about which of its two encodings is the extract the reference
     * system reads, and it is superseded rather than merely contradicted -- the harness, the owning
     * migration and the ETL reader now agree on 15.00, so an expectation of zero here would be the only
     * declaration in the tree still dissenting, and a container-backed case cannot see that: it can only
     * report what the harness seeded. Asserting the settled value keeps the drift detection where it can
     * actually see all three declarations as text.</p>
     *
     * <p>Assumptions: a direct-hit group is asserted to MISS, which is what makes the fallback
     * observable at all. The harness deliberately seeds only the DEFAULT rows, so a lookup for a real
     * account group finds nothing and the fallback path is the one taken; seeding the direct-hit rows
     * here would make the fallback scenario unable to miss.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seeded DEFAULT disclosure rows are complete and a direct-hit group misses")
    void theDefaultDisclosureRowsAreReachable() {
        Integer seeded = this.jdbc.queryForObject(
                "SELECT count(*) FROM reference.disclosure_groups WHERE acct_group_id = ?",
                Integer.class, DEFAULT_GROUP_ID);
        assertThat(seeded)
                .as("one DEFAULT row per type and category pair, or the fallback abends on the pairs"
                        + " it lacks")
                .isEqualTo(DEFAULT_GROUP_ROW_COUNT);

        Optional<DisclosureGroup> fallback = this.disclosureGroups.findByIdIs(
                new DisclosureGroupId(DEFAULT_GROUP_ID, "01", "0001"));
        assertThat(fallback).isPresent();
        assertThat(fallback.orElseThrow().getInterestRate())
                .as("the rate keeps its scale of two, decoded from the baseline's zoned bytes")
                .isEqualByComparingTo(new BigDecimal("15.00"));

        Optional<DisclosureGroup> discriminating = this.disclosureGroups.findByIdIs(
                new DisclosureGroupId(DEFAULT_GROUP_ID, "07", "0001"));
        assertThat(discriminating).isPresent();
        assertThat(discriminating.orElseThrow().getInterestRate())
                .as("the one pair the two shipped encodings disagree on, at the EBCDIC extract's"
                        + " reading, which is the extract DISCGRP.jcl REPROs from")
                .isEqualByComparingTo(new BigDecimal("15.00"));

        Optional<DisclosureGroup> zeroRate = this.disclosureGroups.findByIdIs(
                new DisclosureGroupId(DEFAULT_GROUP_ID, "02", "0001"));
        assertThat(zeroRate).isPresent();
        assertThat(zeroRate.orElseThrow().getInterestRate())
                .as("a zero rate is a seeded row rather than a miss, so the fallback finds it and"
                        + " accrues nothing instead of abending")
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThat(this.disclosureGroups.findByIdIs(
                new DisclosureGroupId("A000000000", "01", "0001")))
                .as("a direct-hit group is deliberately unseeded, so the fallback path is the one"
                        + " taken and remains observable")
                .isEmpty();
    }

    /**
     * Loads the feed rows the scan walks, letting the database assign each ingestion sequence.
     *
     * <p>Assumptions: the rows are inserted through SQL rather than through the persistence context,
     * and that is faithful rather than a shortcut. This module NEVER writes this feed -- the ETL loads
     * it and the posting job only reads it -- so the entity places its identifier on the ingestion
     * sequence with no generation strategy, which means a {@code persist} raises
     * {@code IdentifierGenerationException} demanding a value the application has no setter for. That
     * is how this was found. Inserting without the column lets the table's own
     * {@code GENERATED BY DEFAULT AS IDENTITY} assign it, which is what the ETL's bulk load does.</p>
     *
     * <p>Assumptions: the processing stamp is omitted from the column list, so it lands NULL -- a feed
     * row has not been posted and posting is what assigns one. Every other value is a fixture constant,
     * so no value here identifies a real person, account or card.</p>
     */
    private void seedFeed() {
        this.transactionTemplate.executeWithoutResult(status -> {
            for (int ordinal = 1; ordinal <= FEED_ROWS; ordinal++) {
                this.jdbc.update(
                        "INSERT INTO ledger.daily_transactions"
                                + " (transaction_id, type_cd, category_cd, source, description,"
                                + " amount, merchant_id, merchant_name, merchant_city, merchant_zip,"
                                + " card_num, orig_ts)"
                                + " VALUES (?, '01', '0001', 'POS       ', ?, 10.00, 900000001,"
                                + " 'Fixture Merchant', 'Fixture City', '12345     ', ?, ?)",
                        String.format("%016d", ordinal),
                        "Cross-schema feed fixture row " + ordinal,
                        CARD_NUM_LOW,
                        ORIGINATED_AT);
            }
        });
    }

    /**
     * Builds a 350-character stand-in for a daily-transaction byte image.
     *
     * <p>Assumptions: the width is the copybook's and the CONTENT is deliberately meaningless. This
     * case asserts that the column stores 350 characters verbatim, not that a particular record decodes
     * -- the decoding contract belongs to the mapper's own tests, and giving this fixture a decodable
     * shape would invite a reader to assert the wrong thing here.</p>
     *
     * @return exactly 350 characters, never {@code null}
     */
    private static String rawImage() {
        return "R".repeat(350);
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the eight job definitions, the queue client and
     * the object-store client this module's own application class would register stay out of the
     * context. Naming the two persistence packages leaves the framework's own auto-configuration to
     * build the pool from the properties the container registered.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class CrossSchemaPersistenceTestApplication {
    }
}
