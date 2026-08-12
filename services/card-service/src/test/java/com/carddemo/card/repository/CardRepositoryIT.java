package com.carddemo.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.card.domain.Card;
import com.carddemo.card.domain.EncryptedCvv;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies {@link CardRepository} against a real PostgreSQL engine, over the schema that
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql} builds.
 *
 * <p><b>Purpose.</b> This is the only test in the card bounded context that executes real SQL. It
 * establishes three things no mocked test can: that the account-keyed access path which replaces the
 * reference application's alternate index reaches the rows it should and no others; that forward and
 * backward keyset paging agree at every page boundary, with the further-page condition observed both
 * present and absent; and that the Flyway migration applies cleanly and is what created the table.
 * The charter in {@code package-info.java} beside this file owns the rulings this class obeys, and is
 * cited rather than restated.</p>
 *
 * <h2>What this class asserts, and what it deliberately does not</h2>
 *
 * <p>Assumptions: every query method on {@link CardRepository} returns {@code List<Card>}, so the
 * assertions below are over a row sequence and never over a page envelope. The shared envelope
 * {@code com.carddemo.common.web.PageResponse} is assembled one layer up, in
 * {@code com.carddemo.card.service}, because its canonical constructor requires each cursor component
 * to be a token sealed by {@code com.carddemo.common.web.CursorToken} and a repository interface holds
 * no key material to seal one with. The charter records that constraint at {@code :105-117} and
 * forbids introducing a local page or cursor type here at {@code :119-126}. A test in this class that
 * expected an envelope would therefore be asserting a contract that lives elsewhere, and the
 * further-page condition is instead observed exactly as the charter describes at {@code :80-82} -- as
 * the presence or absence of one surplus row.</p>
 *
 * <p>Trade-offs: because the trim, the further-page derivation and the reversal of the backward result
 * all belong to that service layer rather than to the repository, this class asserts their
 * <b>ingredients</b> and not their output: the surplus row's arrival, the descending order the
 * backward query returns, and the boundary keys the caller would publish. What is accepted is that no
 * assertion here exercises the assembly step, which is verified by {@code CardListServiceTest} against
 * a mocked repository. What is bought is that a failure in this class always indicates the generated
 * SQL or the schema, never the orchestration above it, which is what makes the failure attributable.
 * Duplicating the orchestration assertions here would give one behaviour two owners.</p>
 *
 * <h2>Why a real engine rather than an in-memory substitute</h2>
 *
 * <p>Alternatives Considered: an in-memory database such as H2, which would remove the container start
 * from every run. Rejected because four of the properties under test are behaviours of the engine
 * itself rather than of the mapping, so a substitute would either refuse the migration or accept
 * something PostgreSQL refuses, and the test would then be green in exactly the places it is meant to
 * be load-bearing. The four are: {@code BYTEA} storage and retrieval for {@code cvv_encrypted}, whose
 * declaration is at {@code V1__card.sql:218}; the fixed-width {@code CHAR(16)} primary key at
 * {@code :175}, whose blank-padding rules govern comparison and therefore govern the cursor predicate
 * {@code card_num > :afterCardNum} directly; the named check constraint
 * {@code ck_cards_active_status} at {@code :366}, which is what makes one class of fixture physically
 * unpersistable; and the choice of the secondary index {@code idx_cards_account_id} at {@code :399}
 * for an account-filtered read. The accepted cost is one container start per class.</p>
 *
 * <h2>Why the position is a key and never a row count</h2>
 *
 * <p>Alternatives Considered: resuming each page by counting rows from the start of the result set,
 * which is the familiar alternative and is excluded here on behaviour rather than on preference. When
 * a row is inserted or removed between two requests, a query that resumes by counting <b>skips rows it
 * never showed and repeats rows it already showed</b>, because the number of rows preceding the resume
 * point has moved underneath it. Resuming from the key of the last row shown can do neither, since
 * that key is unaffected by an insertion elsewhere in the table. That difference is not left as a
 * claim: {@code ConcurrentInsertBoundary} below inserts a row into the middle of a page already read
 * and then asserts that the following page is unchanged, which is the concrete case counted offsets
 * get wrong.</p>
 *
 * <h2>The cursor is one column, and the reference pair is not it</h2>
 *
 * <p>Assumptions: every assertion here positions on the single sixteen-character {@code card_num}
 * column and nothing is paired with it. That is recorded because the reference program keeps a pair
 * and a reader could reasonably infer a composite key from it: {@code app/cbl/COCRDLIC.cbl:230-232}
 * holds a last-key pair of {@code WS-CA-LAST-CARD-NUM PIC X(16)} with
 * {@code WS-CA-LAST-CARD-ACCT-ID PIC 9(11)}, and {@code :233-235} holds the same two fields again as a
 * first-key pair. One column is sufficient because {@code card_num} is the primary key of
 * {@code card.cards}, declared as constraint {@code pk_cards} at {@code V1__card.sql:355}, and is
 * therefore already unique; the account identifier in that pair serves the list filter and the
 * separate account-keyed path, not the ordering. A test treating the pair as the key would page
 * differently from the reference at every boundary where two card numbers share a prefix, and would
 * still compile and still pass.</p>
 *
 * <h2>Where each expected value comes from</h2>
 *
 * <p>Assumptions: no recorded-output comparison against mainframe behaviour exists for the paths this
 * class covers, so none is claimed. {@code tests/README.md:83-85} records that the online reference
 * programs cannot be run end to end without a CICS runtime, which is absent on the runner, and that
 * only their extractable field-validation logic is unit-tested. Every expected value below is
 * therefore authored from the reference source, the resource definitions and the migration, each cited
 * where it is used. The one batch program over this file does run, but its read path is a sequential
 * open, read and close -- {@code app/cbl/CBACT02C.cbl:31} declares
 * {@code ACCESS MODE IS SEQUENTIAL} under the record key at {@code :32} -- so it corroborates record
 * framing while exercising neither the cursor nor the account-keyed path.</p>
 *
 * <p>Assumptions: several properties asserted here are supplied by the store rather than by either
 * codebase, and they are capability differences rather than anything wanting in the reference.
 * {@code app/csd/CARDDEMO.CSD} defines both card files with {@code READINTEG(UNCOMMITTED)} at
 * {@code :15} and {@code :27}, {@code STRINGS(1)} at {@code :16} and {@code :28},
 * {@code JOURNAL(NO)} at {@code :19} and {@code :31} and {@code RECOVERY(NONE)} at {@code :21} and
 * {@code :33}. A non-recoverable single-string data set has nowhere to keep a log or a second
 * concurrent position, so the reference programs are written against what their store offers, while a
 * container-backed test can assert a committed second read because the relational engine beneath it
 * offers that instead. Both paths remain in service side by side, and neither reading is the correct
 * one for the other's store.</p>
 *
 * <p>Assumptions: every path under {@code app/} cited anywhere in this class is reference material. It
 * is read as the specification, is never modified, and keeps running; the migration adds a path beside
 * it rather than removing one.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. The inapplicability is stated rather than left silent, and
 * every member below carries its own tags.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = CardRepositoryIT.CardPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CardRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the version is recorded in prose because a digest states nothing a reader
     * recognises, and it is the major line the deployed cluster targets.</p>
     */
    // WHY : Alternatives Considered: the tag postgres:17-alpine, or the apparently exact
    //       postgres:17.10-alpine. Both are mutable -- the publisher moves the first to each new
    //       patch release and may rebuild the second on a new base layer -- and the properties under
    //       test here are engine behaviours: whether a secondary index admits a duplicate key, and
    //       the collation a declared-width character column is compared in. Either could change
    //       between two runs of an unchanged repository, so a failure could not be attributed and a
    //       silently altered ordering could keep every assertion green while proving something else.
    // WHY : Trade-offs: a digest is unreadable, so the engine version survives only in the prose
    //       above and has to be updated together with the pin. That is the same trade the
    //       repository's own workflows already accept in pinning each action to a commit identifier
    //       with the readable tag beside it, and it buys an attributable failure for one comment that
    //       moves with the value.
    // WHY : Assumptions: this is a multi-architecture manifest digest rather than a local image
    //       identifier, so it resolves on every architecture the publisher builds for instead of
    //       binding this class to the one machine that recorded it. It is also the digest the sibling
    //       integration tests in this build already pin, so one engine serves the whole suite.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} and not from
     * {@code org.testcontainers.containers}. Testcontainers 2.0.5 ships both, and only the latter is
     * deprecated; the replacement is not generic, so the declaration carries no type argument and
     * nothing here relied on the self-type that argument refined.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The number of rows a page holds in this class, which is one fewer than every paging call asks
     * for.
     *
     * <p>Assumptions: seven is the reference's own page size, fixed at
     * {@code app/cbl/COCRDLIC.cbl:177-178} as {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} and
     * corroborated by the comment at {@code :250} reading {@code 28 CHARS X 7 ROWS = 196}. It reaches
     * the query as an argument rather than as a repository constant, because seven is the row capacity
     * of a twenty-four-row by eighty-column terminal and so is presentation geometry rather than a
     * property of the table.</p>
     */
    private static final int PAGE_ROWS = 7;

    /**
     * The row bound every paging call in this class passes: one more row than a page holds.
     *
     * <p>Assumptions: reading one row beyond the page is the reference's own technique rather than an
     * optimisation this migration added, which is why the surplus row is what the further-page
     * assertions observe. The reference leaves its read loop when its counter reaches the screen limit
     * at {@code app/cbl/COCRDLIC.cbl:1191-1192}, captures the last displayed row's keys at
     * {@code :1194-1195}, and issues one further read at {@code :1197}; if a row comes back it sets
     * {@code WS-CA-NEXT-PAGE-IND}, declared at {@code :242-244} with
     * {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'}, and if the read reaches end of file it clears the same
     * condition at {@code :1216}. Requesting {@code PAGE_ROWS + 1} and observing whether the extra row
     * arrived is that mechanism expressed as a query.</p>
     */
    // WHY : Alternatives Considered: counting the whole matching set alongside each page, which a
    //       total-bearing page abstraction would supply. Rejected because it answers a question the
    //       reference never asks -- so answering it would be behaviour this migration invented -- and
    //       because it costs a second pass over the matching rows, which on an unfiltered card list is
    //       the whole table. One surplus row answers the only question actually asked.
    private static final Limit LOOKAHEAD = Limit.of(PAGE_ROWS + 1);

    /**
     * The classpath directory holding the fixed-width fixtures.
     */
    private static final String FIXTURE_ROOT = "/fixtures/";

    /**
     * The name under which the shared kernel publishes the card record layout.
     */
    private static final String LAYOUT_NAME = "CARD";

    /**
     * The declared length of one card record in bytes, excluding its line terminator.
     *
     * <p>Assumptions: the value is asserted against the shared layout in {@link #splitRecords} rather
     * than trusted, so a layout change cannot leave this figure behind silently.</p>
     */
    private static final int RECORD_BYTES = 150;

    /**
     * The four-record corpus whose account identifiers drive the account-keyed assertions.
     */
    private static final String BY_ACCOUNT_CORPUS = "card-by-account-corpus.txt";

    /**
     * The eighteen-record corpus, strictly ascending by card number, that drives every paging
     * assertion.
     */
    private static final String PAGE_CORPUS = "card-list-page-corpus.txt";

    /**
     * The single-record positive control for the keyed read.
     */
    private static final String VALID_ACTIVE = "card-valid-active.txt";

    /**
     * The single-record inactive-status control.
     */
    private static final String VALID_INACTIVE = "card-valid-inactive.txt";

    /**
     * The two-record corpus whose expiry years fall outside the range the update rules admit.
     */
    private static final String RULE_REJECT_EXPIRY_YEAR =
            "card-rule-reject-expiry-year-out-of-range.txt";

    /**
     * The zero-byte corpus that yields no rows at all.
     */
    private static final String EMPTY_INPUT = "card-empty-input.txt";

    /**
     * The account the by-account corpus gives three cards.
     *
     * <p>Assumptions: the three identifiers used here are authored deliberately outside the seed
     * range {@code 00000000001} to {@code 00000000050}, so an account-filtered assertion cannot
     * accidentally match a row another fixture contributed.</p>
     */
    private static final long ACCOUNT_WITH_THREE_CARDS = 901L;

    /**
     * The account the by-account corpus gives exactly one card.
     */
    private static final long ACCOUNT_WITH_ONE_CARD = 902L;

    /**
     * The account the by-account corpus deliberately omits altogether.
     */
    private static final long ACCOUNT_WITH_NO_CARDS = 903L;

    /**
     * The first seven card numbers of the paging corpus, which is its first page at this page size.
     *
     * <p>Assumptions: the three page constants are transcribed from
     * {@code card-list-page-corpus.txt} in file order, and the fixture is authored strictly ascending
     * by card number so that file order and key order are the same sequence. Writing them out is what
     * lets a paging assertion state both membership and order in one comparison; deriving them by
     * slicing a list the same query returned would compare the query against itself.</p>
     */
    private static final List<String> FIRST_PAGE = List.of(
            "0500024453765740", "0683586198171516", "0923877193247330", "0927987108636232",
            "0982496213629795", "1014086565224350", "1142167692878931");

    /**
     * The next seven card numbers of the paging corpus, which is its second page at this page size.
     */
    private static final List<String> SECOND_PAGE = List.of(
            "1561409106491600", "2745303720002090", "2760836797107565", "2871968252812490",
            "2940139362300449", "2988091353094312", "3260763612337560");

    /**
     * The final four card numbers of the paging corpus, which is a short last page.
     *
     * <p>Assumptions: this page is deliberately shorter than the page size, because a corpus that
     * divided evenly could never distinguish a genuinely exhausted set from one whose surplus row
     * simply was not read. Four rows against a page of seven is what makes the absence of a further
     * page observable.</p>
     */
    private static final List<String> THIRD_PAGE = List.of(
            "3766281984155154", "3940246016141489", "3999169246375885", "4011500891777367");

    /**
     * The whole paging corpus in key order, being the three pages concatenated.
     *
     * <p>Assumptions: this is composed from the three page constants rather than listed a fourth time,
     * so a correction to any page cannot leave the whole-corpus expectation disagreeing with the pages
     * it is meant to be the sum of.</p>
     */
    private static final List<String> WHOLE_CORPUS =
            Stream.of(FIRST_PAGE, SECOND_PAGE, THIRD_PAGE).flatMap(List::stream).toList();

    /**
     * The repository under test.
     */
    @Autowired
    private CardRepository repository;

    /**
     * The pool the container's coordinates were registered into, used only to read catalogue
     * metadata.
     *
     * <p>Assumptions: the migration assertions read {@code pg_catalog} directly rather than through
     * the persistence provider, because the question they ask is which objects the migration created
     * and the provider's own view of the schema is derived from its mappings rather than from the
     * database.</p>
     */
    @Autowired
    private DataSource dataSource;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry, of type
     *     {@link org.springframework.test.context.DynamicPropertyRegistry}, that this method adds the
     *     container's JDBC URL, user name and password to as deferred suppliers
     */
    // WHY : Assumptions: not one connection literal appears in this class or in
    //       services/card-service/src/test/resources/application-test.yml, and that omission is the
    //       mechanism by which no endpoint or credential reaches the repository at all rather than
    //       merely being kept out of sight. The container assigns its host port when it starts, so no
    //       literal authored beforehand could be correct: it would either point at nothing or, on a
    //       developer machine, at whatever real database happened to be listening on the guessed port.
    // WHY : Alternatives Considered: @ServiceConnection on the container field, which is how the
    //       sibling integration tests in transaction-service, auth-service, reference-service and
    //       reporting-service supply the same three values. It is unavailable here, and on a classpath
    //       fact rather than a preference: that annotation comes from
    //       org.springframework.boot:spring-boot-testcontainers, which those four modules declare and
    //       services/card-service/pom.xml does not -- card-service declares
    //       org.testcontainers:testcontainers-postgresql and testcontainers-junit-jupiter only. A
    //       reader arriving from one of those files would otherwise expect a mechanism that cannot
    //       resolve in this module.
    // WHY : Assumptions: the two Flyway credentials are registered as well, and they are not
    //       redundant with the datasource ones. application.yml binds spring.flyway.user and
    //       spring.flyway.password to environment placeholders that carry no fallback, and Boot
    //       reads those keys only when no connection-details bean supplies them instead. The
    //       annotation named above is what would have contributed that bean, so in its absence the
    //       two keys are consulted and an unregistered placeholder aborts the context before any
    //       migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the card table before each test so that no test observes another's rows.
     *
     * <p>Assumptions: the two corpora cannot share a table state, which is why this is a hard
     * requirement rather than tidiness. Every card number in {@link #BY_ACCOUNT_CORPUS} sorts above
     * every card number in {@link #PAGE_CORPUS} -- the lowest of the four begins {@code 4385} while
     * the highest of the eighteen begins {@code 4011} -- so a leftover by-account row would append
     * itself to the final page of a paging assertion and change a row count without changing an
     * order, which is the failure hardest to read correctly.</p>
     */
    // WHY : Alternatives Considered: annotating this class transactional so that each test rolled
    //       back instead. Rejected because a rolled-back test never commits, and two assertions here
    //       depend on committed state: the further-page probe has to read rows through a query rather
    //       than out of the persistence context's first-level cache, and the concurrent-insert
    //       boundary is only meaningful if the inserted row is visible to a subsequent query. A
    //       transaction wrapping the whole test would also hide a constraint violation until commit,
    //       moving the failure away from the statement that caused it.
    // WHY : Trade-offs: deleting in one batch statement leaves the persistence context unaware of the
    //       removal, so no test may hold an entity across this boundary. That is accepted because
    //       every test below loads the rows it needs after this method runs, and the alternative --
    //       loading every row in order to delete it individually -- would issue one statement per row
    //       to reach the same empty table.
    @BeforeEach
    void emptyCardTable() {
        repository.deleteAllInBatch();
    }

    /**
     * Loads one fixed-width fixture into {@code card.cards} and returns the rows as persisted.
     *
     * @param fixtureName the fixture's file name within the classpath fixture directory, such as
     *     {@code card-list-page-corpus.txt}
     * @return the persisted rows in the order the fixture declares them, which is empty for the
     *     zero-byte fixture
     * @throws UncheckedIOException if the named fixture is absent from the classpath or cannot be read
     * @throws IllegalArgumentException if the fixture's length is not a whole number of terminated
     *     records
     */
    // WHY : Assumptions: only fixtures the schema can physically accept are ever passed here. The
    //       register at services/card-service/src/test/resources/fixtures/README.md classifies each
    //       one, and it states at :261 that a Class B record must not be inserted into card.cards --
    //       card-schema-reject-status-out-of-domain.txt carries a status the named constraint
    //       ck_cards_active_status at V1__card.sql:366 refuses, and
    //       card-schema-reject-expiry-month-out-of-range.txt carries months 00 and 13, which are not
    //       dates at all against the true DATE column at :258. Passing either would raise a
    //       constraint violation or a parse error in place of the intended assertion, which is a
    //       failure pointing at the wrong layer. Neither is referenced by this class.
    // WHY : Assumptions: the decoded verification value is deliberately never carried onto the entity,
    //       so every row this method persists leaves cvv_encrypted null -- which the column at
    //       V1__card.sql:218 admits. The fixture's three bytes at positions 28 to 30 are a plaintext
    //       value, and the only legitimate thing to store is an enciphered envelope, which requires
    //       the key-management client that belongs to the service layer. Storing the plaintext to make
    //       the column non-null would put exactly the value this migration exists to protect into the
    //       table. The BYTEA path is proven separately, by MigrationApplied, from bytes that are not a
    //       verification value.
    private List<Card> loadFixture(String fixtureName) {
        List<Card> parsed = new ArrayList<>();
        for (byte[] record : splitRecords(readFixtureBytes(fixtureName))) {
            parsed.add(toCard(FixedWidthCodec.decodeRecord(
                    record, CopybookLayout.layout(LAYOUT_NAME), StandardCharsets.US_ASCII)));
        }
        return repository.saveAll(parsed);
    }

    /**
     * Reads one fixture's bytes from the classpath without interpreting them.
     *
     * @param fixtureName the fixture's file name within the classpath fixture directory
     * @return the fixture's complete contents, which is a zero-length array for the empty fixture
     * @throws UncheckedIOException if the named fixture is absent from the classpath or cannot be read
     */
    // WHY : Assumptions: the bytes are read as bytes and never through a reader. The records are
    //       fixed-width and every offset in the shared layout is a byte offset, so decoding the whole
    //       file as text first and slicing the result would make each offset depend on the platform
    //       character set. The charset is applied per field instead, by the codec, which is the same
    //       discipline tests/helpers/localstack_setup.py applies to the reference data sets.
    private byte[] readFixtureBytes(String fixtureName) {
        try (InputStream in = getClass().getResourceAsStream(FIXTURE_ROOT + fixtureName)) {
            if (in == null) {
                throw new UncheckedIOException(
                        new IOException("fixture not on the classpath: " + FIXTURE_ROOT
                                + fixtureName));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture " + fixtureName, e);
        }
    }

    /**
     * Splits a fixture's bytes into individual records, discarding the line terminators.
     *
     * @param raw the fixture's complete contents as read from the classpath
     * @return one entry per record, each exactly the declared record length, in file order
     * @throws IllegalArgumentException if the total length is not a whole number of terminated records
     *     or if the shared layout no longer declares the record length this class expects
     */
    // WHY : Assumptions: the register at fixtures/README.md:202 states the invariant this method
    //       relies on -- a fixture measures exactly 151 bytes per record, being 150 data bytes and one
    //       line feed, with no carriage return anywhere. Splitting on the terminator and then checking
    //       each slice's width enforces both halves, so a fixture that gained a carriage return or
    //       lost a byte fails here with the arithmetic named rather than deeper in, as a field decoded
    //       one position out of place.
    // WHY : Assumptions: the expected width is cross-checked against CopybookLayout rather than
    //       trusted from the constant, because the layout is the single source of the record geometry
    //       and this class must not become a second declaration of it. tests/README.md:540-542 states
    //       that discipline for the reference copybooks: a layout is resolved through one include path
    //       and never duplicated.
    private static List<byte[]> splitRecords(byte[] raw) {
        int declared = CopybookLayout.layout(LAYOUT_NAME).reclen();
        if (declared != RECORD_BYTES) {
            throw new IllegalArgumentException("the shared CARD layout declares " + declared
                    + " bytes but this class expects " + RECORD_BYTES);
        }
        List<byte[]> records = new ArrayList<>();
        int start = 0;
        while (start < raw.length) {
            int terminator = start;
            while (terminator < raw.length && raw[terminator] != '\n') {
                terminator++;
            }
            int width = terminator - start;
            if (width != RECORD_BYTES) {
                throw new IllegalArgumentException("record " + (records.size() + 1) + " measures "
                        + width + " bytes but every record must measure " + RECORD_BYTES);
            }
            records.add(Arrays.copyOfRange(raw, start, terminator));
            start = terminator + 1;
        }
        return records;
    }

    /**
     * Builds an unsaved entity from one decoded record.
     *
     * @param fields the decoded record, keyed by the field names the shared card layout declares
     * @return an entity carrying the record's card number, account identifier, embossed name,
     *     expiration date and active status, and no verification value
     * @throws java.time.format.DateTimeParseException if the expiration field does not hold an ISO
     *     date
     */
    // WHY : Assumptions: the expiration field is parsed rather than stored as text because the target
    //       column at V1__card.sql:258 is a true DATE. The parse succeeds without a pattern because
    //       the reference already stores the value ISO-ordered in CARD-EXPIRAION-DATE at
    //       app/cpy/CVACT02Y.cpy:9 -- which is also why the reference's own lexical comparisons over
    //       that field remain equivalent to date comparisons. The field name reproduces the
    //       misspelling present in the baseline copybook, because the shared layout transcribes that
    //       copybook faithfully; the target column name corrects it, and the correction is registered
    //       in the migration rather than made silently here.
    // WHY : Assumptions: the embossed name has its padding removed because the fixture field is 50
    //       characters wide by declaration at app/cpy/CVACT02Y.cpy:8 while the target column at
    //       V1__card.sql:233 is variable width. The trailing blanks are padding to the fixed record
    //       length and not part of the name, so carrying them would make a stored name differ from
    //       the same name written into a narrower record. Leading whitespace is left untouched,
    //       because a leading blank would be data rather than padding.
    private static Card toCard(Map<String, Object> fields) {
        return new Card(
                (String) fields.get("CARD-NUM"),
                (Long) fields.get("CARD-ACCT-ID"),
                null,
                ((String) fields.get("CARD-EMBOSSED-NAME")).stripTrailing(),
                LocalDate.parse((String) fields.get("CARD-EXPIRAION-DATE")),
                (String) fields.get("CARD-ACTIVE-STATUS"));
    }

    /**
     * Projects a row sequence onto its card numbers, so that order and membership can be asserted
     * together.
     *
     * @param rows the rows a repository query returned, in the order it returned them
     * @return the card numbers in the same order
     */
    // WHY : Assumptions: comparing the projected keys rather than the entities is what makes an
    //       ordering assertion readable and, more importantly, exact. Card does define equality, but
    //       it defines it on the identifier alone, so an entity-level comparison would report the same
    //       verdict while showing a reader a list of diagnostic strings instead of the two key
    //       sequences that actually differ.
    private static List<String> cardNumbersOf(List<Card> rows) {
        List<String> numbers = new ArrayList<>(rows.size());
        for (Card row : rows) {
            numbers.add(row.getCardNum());
        }
        return numbers;
    }

    /**
     * Runs a catalogue query that yields one text column per row.
     *
     * @param sql the query to execute, which must select exactly one column and must carry no
     *     parameter
     * @return the first column of every row, in the order the query returned them
     * @throws IllegalStateException if the query cannot be executed against the container
     */
    // WHY : Assumptions: these queries are issued over their own short-lived connection rather than
    //       through the persistence provider, because what they ask is which objects exist in the
    //       database and the provider's view of the schema is built from its own mappings. Asking the
    //       provider would therefore risk confirming the mapping against itself, which is precisely
    //       the failure the ddl-auto setting of none exists to make impossible.
    // WHY : Trade-offs: the statement is assembled by the caller as a literal and carries no
    //       parameter, so this helper has no parameter binding at all. That is safe only because every
    //       caller is in this file and every value in those literals is authored here; it is not a
    //       pattern for production code, and the restriction is stated in the contract above so that a
    //       later caller cannot reasonably pass untrusted text through it.
    private List<String> catalogStrings(String sql) {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("catalogue query failed: " + sql, e);
        }
        return values;
    }

    /**
     * Builds a well-formed envelope from bytes that are not, and never were, a verification value.
     *
     * @return an envelope of the shortest shape the framing admits, suitable for proving that the
     *     {@code BYTEA} column stores and returns bytes unchanged
     */
    // WHY : Assumptions: the byte runs below are counters rather than ciphertext, and the value they
    //       frame corresponds to no card in any fixture. The point being established is the column's
    //       fidelity, so the bytes only have to satisfy the framing EncryptedCvv enforces -- a key of
    //       at least one byte, a vector of exactly the declared length and a ciphertext of at least
    //       the declared minimum -- and enciphering a real value would require the key-management
    //       client that belongs to the service layer without making the storage assertion any
    //       stronger.
    // WHY : Trade-offs: the runs are filled by position rather than left zeroed. Zero-filled arrays
    //       would frame just as validly, but a column that silently returned zeros would then satisfy
    //       the assertion, so the value would prove nothing about fidelity. Distinct bytes at distinct
    //       offsets make a truncation or a reordering visible.
    private static EncryptedCvv syntheticEnvelope() {
        byte[] encipheredDataKey = new byte[32];
        byte[] initialisationVector = new byte[EncryptedCvv.INITIALISATION_VECTOR_LENGTH];
        byte[] ciphertext = new byte[EncryptedCvv.MIN_CIPHERTEXT_LENGTH];
        for (int i = 0; i < encipheredDataKey.length; i++) {
            encipheredDataKey[i] = (byte) (i + 1);
        }
        for (int i = 0; i < initialisationVector.length; i++) {
            initialisationVector[i] = (byte) (0x40 + i);
        }
        for (int i = 0; i < ciphertext.length; i++) {
            ciphertext[i] = (byte) (0x80 + i);
        }
        return EncryptedCvv.wrap(encipheredDataKey, initialisationVector, ciphertext);
    }

    /**
     * The account-keyed access path: the target of the reference application's alternate index.
     *
     * <p>Assumptions: this path exists because the reference declares it, and the index beneath it is
     * non-unique by that declaration rather than by caution. The evidence is entirely in the reference
     * and is worth reading in full, because the alternative justification a reader might reach for --
     * that an index makes the read quicker -- would be a preference dressed as a reason and would not
     * establish that the path has to exist at all.</p>
     *
     * <p>Assumptions: {@code app/jcl/CARDFILE.jcl} states the intent in its own words at {@code :78},
     * whose comment reads {@code CREATE ALTERNATE INDEX ON ACCT ID}, and then builds it: the step at
     * {@code :80} runs the catalogue utility, {@code :83} defines the alternate index, {@code :84}
     * relates it to the same base cluster the card master occupies, {@code :85} keys it with
     * {@code KEYS(11 16)}, {@code :86} declares {@code NONUNIQUEKEY}, {@code :87} declares
     * {@code UPGRADE} and {@code :88} fixes the record size at {@code RECORDSIZE(150,150)}. The key
     * operands are a length and a zero-based offset, so eleven bytes at offset sixteen are one-based
     * positions seventeen to twenty-seven of the record, which is exactly {@code CARD-ACCT-ID} at
     * {@code app/cpy/CVACT02Y.cpy:6}. A path over that index is then surfaced to the online region as
     * a separate file: {@code app/csd/CARDDEMO.CSD:13} defines {@code FILE(CARDAIX)} over the
     * alternate-index path data set named at {@code :14} and enables it for browse at {@code :19},
     * alongside the base cluster {@code FILE(CARDDAT)} defined at {@code :25} over the key-sequenced
     * data set at {@code :26}. That declaration is the whole justification for
     * {@code idx_cards_account_id} at {@code V1__card.sql:399}, and {@code NONUNIQUEKEY} is
     * specifically why {@code UNIQUE} is absent from it -- declaring it unique would refuse the second
     * card on an account, a cardinality the reference admits by design. Zero, one and many rows per
     * account are therefore all legitimate, and all three are asserted below.</p>
     *
     * <p>Assumptions: the index rests on that declared access path and not on any browse that
     * executes, which is stated precisely because the reverse is easy to assume. Recorded as
     * observations: the literal naming the index path appears once in each of the three online card
     * programs, at {@code app/cbl/COCRDLIC.cbl:217}, {@code app/cbl/COCRDSLC.cbl:190} and
     * {@code app/cbl/COCRDUPC.cbl:254}, and all eight browse verbs in the list program name the base
     * cluster instead, through the literal {@code 'CARDDAT '} declared at {@code :213-214} -- at
     * {@code :1130} and {@code :1274} for the two positioning verbs, {@code :1147} and {@code :1198}
     * for the two forward reads, {@code :1295} and {@code :1323} for the two backward reads, and
     * inline at {@code :1258} and {@code :1376} for the two that end a browse. And
     * {@code 9150-GETCARD-BYACCT} in {@code app/cbl/COCRDSLC.cbl}, defined at {@code :779} with its
     * exit at {@code :810}, reads the path but is reached by nothing, because
     * {@code 9000-READ-DATA.} at {@code :726} performs only the card-and-account paragraph at
     * {@code :728-729}. These are observations about reference material that is read and never
     * modified; they are not a defect being corrected, and they are not the justification for the
     * index.</p>
     *
     * <p>Refactoring Rationale: the path is reached here through the optional account predicate that
     * the two keyset queries each carry, rather than through a method named for it. A dedicated derived
     * method, {@code findByAccountIdOrderByCardNumAsc}, previously stood on the interface for exactly
     * this purpose and was removed; the reason is recorded at {@code CardRepository.java:395-403} and
     * is what these assertions now rest on. What was wrong with it was not its implementation but its
     * reachability: it had no caller in production or in a test, so the index it was declared to read
     * had no reader through it, while the account-filtered browse that does have a live route resolves
     * through the same index by way of the predicate. Asserting the predicate therefore covers the
     * access path that is actually reached, and a test written against the removed method would have
     * covered one that was not.</p>
     *
     * <p>Assumptions: the fixture authored for this path is not orphaned by that removal. It is also
     * read by {@code CardFixtureContractTest}, which asserts its shape and its four-row cardinality,
     * and it exercises the same index and the same one-account-many-cards property here.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("the account-keyed access path that replaces the alternate index")
    class AccountKeyedAccessPath {

        /**
         * Confirms that an account holding several cards returns all of them, in ascending key order.
         */
        // WHY : Assumptions: three is the cardinality NONUNIQUEKEY at app/jcl/CARDFILE.jcl:86 admits
        //       and a unique index would refuse, so this is the assertion that would fail first if the
        //       UNIQUE keyword were ever added to idx_cards_account_id. The three expected keys are
        //       read from card-by-account-corpus.txt rather than computed, and the fixture register
        //       records that their card numbers are verbatim from the reference seed data while only
        //       the account identifiers are authored.
        @Test
        void returnsEveryCardHeldByAnAccountInAscendingKeyOrder() {
            loadFixture(BY_ACCOUNT_CORPUS);

            List<Card> held = repository.findForwardFromCursor(
                    null, ACCOUNT_WITH_THREE_CARDS, null, LOOKAHEAD);

            assertThat(cardNumbersOf(held)).containsExactly(
                    "4385271476627819", "4534784102713951", "4859452612877065");
        }

        /**
         * Confirms that an account holding a single card returns exactly that one row.
         */
        // WHY : Assumptions: one row is asserted separately from three because a non-unique index
        //       serving a single match is the case a mistaken UNIQUE declaration would still satisfy.
        //       Asserting only this cardinality would therefore leave that mistake undetected, which
        //       is why the three-card case above exists alongside it.
        @Test
        void returnsTheSingleCardHeldByAnAccountWithOne() {
            loadFixture(BY_ACCOUNT_CORPUS);

            List<Card> held = repository.findForwardFromCursor(
                    null, ACCOUNT_WITH_ONE_CARD, null, LOOKAHEAD);

            assertThat(cardNumbersOf(held)).containsExactly("5407099850479866");
        }

        /**
         * Confirms that an account holding no cards returns no rows rather than failing.
         */
        // WHY : Assumptions: the empty case is established by querying an account the fixture
        //       deliberately omits, not by deleting rows first. Deleting would prove only that a table
        //       emptied of matches returns nothing, whereas the condition that actually arises is a
        //       populated table holding no row for the account asked about -- and it has to be an
        //       empty result rather than an error, because NONUNIQUEKEY admits zero as readily as many.
        @Test
        void returnsNoRowsForAnAccountTheFixtureOmits() {
            loadFixture(BY_ACCOUNT_CORPUS);

            List<Card> held = repository.findForwardFromCursor(
                    null, ACCOUNT_WITH_NO_CARDS, null, LOOKAHEAD);

            assertThat(held).isEmpty();
        }

        /**
         * Confirms that an account-filtered read returns no row belonging to another account.
         */
        // WHY : Assumptions: this guards the specific failure a non-unique index makes possible, which
        //       is that a read positioned on a duplicate key walks past the end of its own group and
        //       returns a neighbour's rows. The reference has to handle that response explicitly --
        //       app/cbl/COCRDLIC.cbl pairs the duplicate-key response with the normal one at :1157
        //       with :1158 and again at :1208 with :1209, treating both as success -- so a filtered
        //       read leaking across accounts would be a plausible defect rather than an impossible
        //       one. Asserting the account of every returned row, rather than the count alone, is what
        //       detects it.
        @Test
        void confinesAnAccountFilteredReadToTheAccountAskedFor() {
            loadFixture(BY_ACCOUNT_CORPUS);

            List<Card> held = repository.findForwardFromCursor(
                    null, ACCOUNT_WITH_THREE_CARDS, null, LOOKAHEAD);

            assertThat(held).isNotEmpty();
            assertThat(held).allSatisfy(
                    row -> assertThat(row.getAccountId()).isEqualTo(ACCOUNT_WITH_THREE_CARDS));
        }
    }

    /**
     * The keyed read, which is the target of the reference application's read by record key.
     *
     * <p>Assumptions: the keyed read is {@code findById}, inherited from the Spring Data supertype and
     * deliberately not redeclared on {@link CardRepository}. Its reference counterpart is the read by
     * record key that {@code app/cbl/CBACT02C.cbl} declares at {@code :32} as
     * {@code RECORD KEY IS FD-CARD-NUM}, over the sixteen-byte key that program splits from the
     * hundred-and-fifty-byte record at {@code :39-40}. That the key is the first sixteen bytes and
     * nothing more is confirmed from outside the programs entirely, by the cluster the load job defines
     * in {@code app/jcl/CARDFILE.jcl}.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("the keyed read over the sixteen-character primary key")
    class KeyedRead {

        /**
         * Confirms that a keyed read returns every mapped field of the row it names.
         */
        // WHY : Assumptions: the expected values are the fixture's own bytes, and the fixture register
        //       records that this record is a reference seed record carried across entirely verbatim,
        //       which is what makes it a positive control rather than an authored convenience. Each of
        //       the five mapped fields is asserted rather than the identifier alone, because Card
        //       defines equality on the identifier, so a row returned with every other column empty
        //       would still compare equal to the row expected.
        @Test
        void returnsEveryMappedFieldOfTheRowNamed() {
            loadFixture(VALID_ACTIVE);

            Optional<Card> found = repository.findById("6509230362553816");

            assertThat(found).isPresent();
            Card card = found.orElseThrow();
            assertThat(card.getCardNum()).isEqualTo("6509230362553816");
            assertThat(card.getAccountId()).isEqualTo(30L);
            assertThat(card.getEmbossedName()).isEqualTo("Layla Ullrich");
            assertThat(card.getExpirationDate()).isEqualTo(LocalDate.of(2024, 6, 27));
            assertThat(card.getActiveStatus()).isEqualTo("Y");
        }

        /**
         * Confirms that a keyed read for an absent key yields an empty result rather than an error.
         */
        // WHY : Assumptions: the key below is well formed and simply not present, which is the
        //       condition the reference reports as a record-not-found status rather than as a failure.
        //       Asserting an empty optional is what keeps the caller's not-found handling reachable;
        //       were this to raise instead, the screen path that reports a missing card would be dead
        //       code.
        @Test
        void yieldsNothingForAKeyThatIsNotPresent() {
            loadFixture(VALID_ACTIVE);

            assertThat(repository.findById("9999999999999999")).isEmpty();
        }

        /**
         * Confirms that a stored key keeps its declared width and that a shorter key does not match
         * it.
         */
        // WHY : Assumptions: this is one of the four engine behaviours that make a real database
        //       necessary here rather than a substitute for one. The column at V1__card.sql:175 is
        //       CHAR(16) because app/cpy/CVACT02Y.cpy:5 declares CARD-NUM as PIC X(16), and a
        //       declared-width character column compares its operands as though the shorter were
        //       padded with blanks to the longer's width. A fifteen-character key is therefore
        //       compared as fifteen digits followed by a blank, which cannot equal sixteen digits, so
        //       it must not match. The consequence reaches further than a keyed read: the cursor
        //       predicate card_num > :afterCardNum is an ordering comparison on this same column, so a
        //       store that instead compared a prefix as equal would silently move every page boundary.
        // WHY : Trade-offs: the round-trip width is asserted as well as the non-match, and the two are
        //       not the same claim. Blank padding could in principle make a stored value come back
        //       wider or narrower than it went in, and a value returned with padding stripped would
        //       still satisfy the non-match assertion while breaking any caller that compares a key it
        //       received against one it holds.
        @Test
        void keepsTheDeclaredKeyWidthAndRefusesAShorterKey() {
            loadFixture(VALID_ACTIVE);

            Card stored = repository.findById("6509230362553816").orElseThrow();
            assertThat(stored.getCardNum()).hasSize(16);

            assertThat(repository.findById("650923036255381")).isEmpty();
        }
    }

    /**
     * Forward keyset paging, which replaces the reference application's forward browse.
     *
     * <p>Assumptions: these assertions stand in for {@code 9000-READ-FORWARD.} at
     * {@code app/cbl/COCRDLIC.cbl:1123}, which positions a browse at {@code :1129}, reads at
     * {@code :1146}, probes one row beyond the page at {@code :1197} and ends the browse at
     * {@code :1258}. Each request below asks for {@link #LOOKAHEAD} rows, being one more than a page
     * holds, so that the surplus row stands in for {@code WS-CA-NEXT-PAGE-IND} at {@code :242-244}.</p>
     *
     * <p>Assumptions: resuming strictly after the last row of the previous page is equivalent to what
     * the reference does, and the equivalence is stated because the two mechanisms look different. The
     * reference positions inclusively on a stored key and then reads, and the key it stores is the
     * first <b>undisplayed</b> row, overwritten from the probe read at {@code :1212-1214}. These
     * queries instead resume strictly after the last <b>returned</b> row. Both yield identical
     * boundaries: with seven rows to a page the reference's second page is rows eight to fourteen, and
     * resuming strictly after the key of row seven yields rows eight to fourteen as well. The
     * consequence the assertions honour is that the surplus row's key is never used as the next
     * cursor, since doing so would advance it one row too far and drop a row from the following
     * page.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("forward keyset paging over the eighteen-record corpus")
    class ForwardKeysetPaging {

        /**
         * Confirms that the first page is read with no cursor and reports a further page.
         */
        // WHY : Assumptions: an absent cursor is what expresses the first page, which is the state the
        //       reference is in when it positions from a communication area it has just initialised --
        //       app/cbl/COCRDLIC.cbl:462-463 initialises that area before the forward read at :478.
        //       The eighth row returned is the surplus probe and belongs to the next page, so the page
        //       proper is the first seven and the caller's cursor is the seventh key, never the eighth.
        @Test
        void readsTheFirstPageWithoutACursorAndSeesAFurtherPage() {
            loadFixture(PAGE_CORPUS);

            List<Card> read = repository.findForwardFromCursor(null, null, null, LOOKAHEAD);

            assertThat(read).hasSize(PAGE_ROWS + 1);
            assertThat(cardNumbersOf(read).subList(0, PAGE_ROWS)).isEqualTo(FIRST_PAGE);
            assertThat(cardNumbersOf(read).get(PAGE_ROWS)).isEqualTo(SECOND_PAGE.get(0));
        }

        /**
         * Confirms that resuming after the first page's last key yields the second page and no
         * overlap.
         */
        // WHY : Assumptions: the cursor passed here is the seventh key and not the eighth, which is the
        //       distinction the surplus row makes easy to get wrong. Using the eighth would skip the
        //       row it names, and the resulting page would still be seven rows long and still be in
        //       order, so no count or ordering assertion would notice; only comparing against the
        //       expected keys detects it.
        @Test
        void resumesAfterTheFirstPageWithoutRepeatingOrSkippingARow() {
            loadFixture(PAGE_CORPUS);

            List<Card> read = repository.findForwardFromCursor(
                    FIRST_PAGE.get(PAGE_ROWS - 1), null, null, LOOKAHEAD);

            assertThat(read).hasSize(PAGE_ROWS + 1);
            assertThat(cardNumbersOf(read).subList(0, PAGE_ROWS)).isEqualTo(SECOND_PAGE);
            assertThat(cardNumbersOf(read)).doesNotContainAnyElementsOf(FIRST_PAGE);
        }

        /**
         * Confirms that the last page returns fewer rows than requested, so no further page is
         * reported.
         */
        // WHY : Assumptions: this is the assertion that establishes the further-page condition
        //       reaching FALSE, and a suite lacking it could not distinguish a cursor that always
        //       reports more rows from one that reports them correctly. It corresponds to the
        //       reference's end-of-file arm, which clears the condition at
        //       app/cbl/COCRDLIC.cbl:1216 and reports 'NO MORE RECORDS TO SHOW' at :1219 when the
        //       probe read finds nothing. Four rows returned against eight requested is that same
        //       observation.
        @Test
        void returnsAShortFinalPageWithNoSurplusRow() {
            loadFixture(PAGE_CORPUS);

            List<Card> read = repository.findForwardFromCursor(
                    SECOND_PAGE.get(PAGE_ROWS - 1), null, null, LOOKAHEAD);

            assertThat(read).hasSizeLessThan(PAGE_ROWS + 1);
            assertThat(cardNumbersOf(read)).isEqualTo(THIRD_PAGE);
        }

        /**
         * Confirms that paging to exhaustion visits every row exactly once, in key order.
         */
        // WHY : Assumptions: the three page assertions above each check one boundary, and this one
        //       checks the property those three are meant to add up to -- that the sequence of pages
        //       partitions the table. A defect that both skipped one row and repeated another would
        //       satisfy every count assertion individually while breaking this one, which is why the
        //       whole traversal is compared against the corpus rather than only its pages against each
        //       other.
        @Test
        void visitsEveryRowExactlyOnceWhenPagedToExhaustion() {
            List<Card> loaded = loadFixture(PAGE_CORPUS);

            List<String> visited = new ArrayList<>();
            String cursor = null;
            do {
                List<Card> read = repository.findForwardFromCursor(cursor, null, null, LOOKAHEAD);
                List<String> page = cardNumbersOf(read);
                boolean furtherPageExists = page.size() > PAGE_ROWS;
                List<String> pageProper =
                        furtherPageExists ? page.subList(0, PAGE_ROWS) : page;
                visited.addAll(pageProper);
                cursor = furtherPageExists ? pageProper.get(PAGE_ROWS - 1) : null;
            } while (cursor != null);

            assertThat(visited).hasSize(loaded.size());
            assertThat(visited).doesNotHaveDuplicates();
            assertThat(visited).containsExactlyElementsOf(WHOLE_CORPUS);
        }
    }

    /**
     * Backward keyset paging, which replaces the reference application's backward browse.
     *
     * <p>Assumptions: these assertions stand in for {@code 9100-READ-BACKWARDS.} at
     * {@code app/cbl/COCRDLIC.cbl:1264}, which positions at {@code :1273}, primes at {@code :1294},
     * loops at {@code :1322} and ends at {@code :1376}. The query returns rows in the order it reads
     * them, which is descending, and the caller reverses them before presenting a page; that ordering
     * is not an oversight but the only one that can bound the rows nearest the cursor, since an
     * ascending order under a row bound would select from the wrong end of the table. The reference
     * fills its display array from the bottom upward for the same reason, setting its row counter one
     * past the screen limit at {@code :1284-1286} and decrementing it at {@code :1307} and
     * {@code :1346} as each row arrives.</p>
     *
     * <p>Assumptions: the cursor for a backward step is mandatory rather than nullable, because a
     * backward step is only expressible from a page that was already returned; an absent cursor would
     * describe a request that cannot arise.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("backward keyset paging over the eighteen-record corpus")
    class BackwardKeysetPaging {

        /**
         * Confirms that the backward query returns the rows nearest the cursor, in descending order.
         */
        // WHY : Assumptions: the descending order is asserted as the contract rather than corrected
        //       here, because the reversal belongs to the caller assembling the page. Asserting an
        //       ascending result would encode the opposite expectation and would fail against a
        //       correct repository. The surplus row is the LAST element of a descending result rather
        //       than the first, since the bound applies to the rows nearest the cursor, and mistaking
        //       which end holds it is what would drop a row from the page.
        @Test
        void returnsTheRowsNearestTheCursorInDescendingOrder() {
            loadFixture(PAGE_CORPUS);

            List<Card> read = repository.findBackwardFromCursor(
                    THIRD_PAGE.get(0), null, null, LOOKAHEAD);

            assertThat(read).hasSize(PAGE_ROWS + 1);
            List<String> descending = cardNumbersOf(read);
            assertThat(descending).isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(descending.get(0)).isEqualTo(SECOND_PAGE.get(PAGE_ROWS - 1));
            assertThat(descending.get(PAGE_ROWS)).isEqualTo(FIRST_PAGE.get(PAGE_ROWS - 1));
        }

        /**
         * Confirms that reversing the trimmed backward result reproduces the preceding page exactly.
         */
        // WHY : Assumptions: this is the equivalence the caller depends on, so it is asserted here even
        //       though the reversal itself is the caller's step -- the repository's contract is only
        //       useful if the descending rows it returns are the same set the forward query would have
        //       produced for that page. Trimming before reversing is the order that matters: reversing
        //       first would leave the surplus row at the front of the ascending list and publish it as
        //       the page's first key, which is a silent off-by-one in the cursor rather than a visible
        //       failure.
        @Test
        void reversingTheTrimmedResultReproducesThePrecedingPage() {
            loadFixture(PAGE_CORPUS);

            List<Card> read = repository.findBackwardFromCursor(
                    THIRD_PAGE.get(0), null, null, LOOKAHEAD);

            List<String> trimmed = new ArrayList<>(cardNumbersOf(read).subList(0, PAGE_ROWS));
            Collections.reverse(trimmed);

            assertThat(trimmed).isEqualTo(SECOND_PAGE);
        }

        /**
         * Confirms that stepping back from the second page reaches the first and reports no further
         * page.
         */
        // WHY : Assumptions: this establishes the beginning-of-set boundary, which is the backward
        //       counterpart of the short final page and is proven the same way -- by the surplus row
        //       failing to arrive. Exactly seven rows precede the second page's first key, so a request
        //       for eight returning seven is what shows there is nothing further back. Its ascending
        //       form must be the first page, which is the page a caller reached with no cursor at all.
        @Test
        void stepsBackFromTheSecondPageToTheFirstWithNoSurplusRow() {
            loadFixture(PAGE_CORPUS);

            List<Card> read = repository.findBackwardFromCursor(
                    SECOND_PAGE.get(0), null, null, LOOKAHEAD);

            assertThat(read).hasSizeLessThan(PAGE_ROWS + 1);
            List<String> ascending = new ArrayList<>(cardNumbersOf(read));
            Collections.reverse(ascending);
            assertThat(ascending).isEqualTo(FIRST_PAGE);
        }

        /**
         * Confirms that paging forward to the last page and back again lands on the first page.
         */
        // WHY : Assumptions: the round trip is asserted because the forward and backward predicates are
        //       separate statements over the same column, so they could disagree by one row at a
        //       boundary while each remained self-consistent. Driving the cursor forward and then back
        //       through the same boundaries is what detects an asymmetry -- for instance a
        //       greater-than-or-equal-to on one side and a strict comparison on the other, which would
        //       repeat a boundary row in one direction only.
        @Test
        void returnsToTheFirstPageAfterPagingForwardAndBack() {
            loadFixture(PAGE_CORPUS);

            List<Card> firstPage = repository.findForwardFromCursor(null, null, null, LOOKAHEAD);
            String firstCursor = cardNumbersOf(firstPage).get(PAGE_ROWS - 1);
            List<Card> secondPage =
                    repository.findForwardFromCursor(firstCursor, null, null, LOOKAHEAD);
            String secondCursor = cardNumbersOf(secondPage).get(PAGE_ROWS - 1);
            List<Card> lastPage =
                    repository.findForwardFromCursor(secondCursor, null, null, LOOKAHEAD);

            List<Card> backToSecond = repository.findBackwardFromCursor(
                    cardNumbersOf(lastPage).get(0), null, null, LOOKAHEAD);
            List<String> secondAgain =
                    new ArrayList<>(cardNumbersOf(backToSecond).subList(0, PAGE_ROWS));
            Collections.reverse(secondAgain);

            List<Card> backToFirst = repository.findBackwardFromCursor(
                    secondAgain.get(0), null, null, LOOKAHEAD);
            List<String> firstAgain = new ArrayList<>(cardNumbersOf(backToFirst));
            Collections.reverse(firstAgain);

            assertThat(secondAgain).isEqualTo(SECOND_PAGE);
            assertThat(firstAgain).isEqualTo(FIRST_PAGE);
        }
    }

    /**
     * The boundary a row inserted between two requests creates.
     *
     * <p>Alternatives Considered: this group is the evidence for rejecting offset pagination, carried
     * as an executable case rather than as an assertion in prose. A query that resumed by counting
     * rows from the start of the result would, after the insertion below, return a page beginning with
     * a row the caller had already been shown and ending one row short of where it should, because the
     * number of rows preceding the resume point moved. A cursor naming a key cannot be affected by an
     * insertion elsewhere in the table, and that is what is asserted.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("the boundary when a row is inserted into a page already read")
    class ConcurrentInsertBoundary {

        /**
         * Confirms that a row inserted inside an already-read page changes neither the next page nor
         * its order.
         */
        // WHY : Assumptions: the inserted key is chosen to sort strictly inside the first page rather
        //       than merely before the cursor, because that is the position an offset-based resume gets
        //       wrong. It falls between the third and fourth rows of the first page, so it displaces
        //       every later row by one: counting seven rows from the start would then land on the first
        //       page's own last row and re-present it, while the second page's last row would be pushed
        //       out of reach. Both are asserted against, so the test fails if either happens.
        // WHY : Assumptions: this row is authored rather than taken from a fixture, and deliberately
        //       so. No fixture holds a key in that gap, and adding one would change the page
        //       boundaries every other assertion in this class depends on -- the register at
        //       fixtures/README.md also forbids editing a fixture's bytes, since every field offset
        //       after the edit would shift. Its account identifier is outside both the reference seed
        //       range and the three authored by-account values, so it cannot satisfy an
        //       account-filtered assertion by accident.
        @Test
        void leavesTheFollowingPageUnchangedWhenARowIsInsertedBehindTheCursor() {
            loadFixture(PAGE_CORPUS);
            List<Card> firstPage = repository.findForwardFromCursor(null, null, null, LOOKAHEAD);
            String cursor = cardNumbersOf(firstPage).get(PAGE_ROWS - 1);

            String insertedKey = "0925000000000000";
            repository.save(new Card(insertedKey, 904L, null, "Inserted Behind Cursor",
                    LocalDate.of(2025, 6, 15), "Y"));

            List<Card> secondPage =
                    repository.findForwardFromCursor(cursor, null, null, LOOKAHEAD);

            assertThat(insertedKey).isLessThan(cursor);
            assertThat(repository.findById(insertedKey)).isPresent();
            assertThat(cardNumbersOf(secondPage).subList(0, PAGE_ROWS)).isEqualTo(SECOND_PAGE);
            assertThat(cardNumbersOf(secondPage)).doesNotContainAnyElementsOf(FIRST_PAGE);
            assertThat(cardNumbersOf(secondPage)).doesNotContain(insertedKey);
        }
    }

    /**
     * The empty results a caller must be able to represent.
     *
     * <p>Assumptions: an empty row sequence is what a caller turns into a page carrying no items and
     * no boundary keys, since there is no row whose key could be published as either boundary. The
     * envelope itself is assembled in the service layer, so what is asserted here is the empty
     * sequence the assembly starts from.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("empty results from an empty table and from a filter that matches nothing")
    class EmptyResults {

        /**
         * Confirms that a table with no rows yields an empty sequence rather than an error.
         */
        // WHY : Assumptions: the zero-byte fixture is loaded rather than the load being skipped, so
        //       that the empty state is reached the same way every other state in this class is
        //       reached. It also exercises the loader's own boundary: a file of no bytes must decode to
        //       no records, whereas an off-by-one in the record split would either raise or fabricate
        //       one empty row. The reference reports this state distinctly, recording a no-records
        //       condition at app/cbl/COCRDLIC.cbl:1244 when its first screen returned nothing at all.
        @Test
        void yieldsAnEmptySequenceWhenNoRowsExist() {
            List<Card> loaded = loadFixture(EMPTY_INPUT);

            assertThat(loaded).isEmpty();
            assertThat(repository.findForwardFromCursor(null, null, null, LOOKAHEAD)).isEmpty();
        }

        /**
         * Confirms that a populated table yields an empty sequence for a filter nothing matches.
         */
        // WHY : Assumptions: this is the more demanding of the two empty cases and the one a caller
        //       actually meets, because the table is populated and the query still has to return
        //       nothing. A predicate silently dropped when its argument did not match -- rather than
        //       when it was absent -- would return the whole first page here and pass every other
        //       assertion in this class, since no other test asks for a filter value that matches no
        //       row.
        @Test
        void yieldsAnEmptySequenceWhenTheFilterMatchesNoRow() {
            loadFixture(PAGE_CORPUS);

            assertThat(repository.findForwardFromCursor(
                    null, ACCOUNT_WITH_NO_CARDS, null, LOOKAHEAD)).isEmpty();
            assertThat(repository.findForwardFromCursor(
                    null, null, "9999999999999999", LOOKAHEAD)).isEmpty();
        }
    }

    /**
     * Rows the update rules reject but the schema stores without complaint.
     *
     * <p>Assumptions: the register at
     * {@code services/card-service/src/test/resources/fixtures/README.md} separates the fixtures whose
     * values the schema physically refuses from those it accepts while the business rules reject them,
     * and this group asserts the second kind. The distinction matters because it locates
     * responsibility: an expiry year outside the range the update screen admits is a rule the
     * validator applies, not a constraint the database carries, and the row is a perfectly valid
     * {@code DATE} at {@code V1__card.sql:258}. Asserting that such a row persists is what establishes
     * that the validator is doing work the schema does not do for it -- and any assertion that it is
     * rejected belongs beside that validator, in {@code CardUpdateServiceTest}.</p>
     *
     * <p>Assumptions: the two fixtures the schema does refuse are never loaded anywhere in this class.
     * {@code card-schema-reject-status-out-of-domain.txt} carries a status the named constraint
     * {@code ck_cards_active_status} at {@code V1__card.sql:366} rejects, and
     * {@code card-schema-reject-expiry-month-out-of-range.txt} carries months {@code 00} and
     * {@code 13}, which are not dates at all. Loading either would raise a constraint violation or a
     * parse error in place of the intended assertion, which is a failure pointing at the wrong
     * layer.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("rows the update rules reject but the schema accepts")
    class RuleRejectedRowsStillPersist {

        /**
         * Confirms that expiry years outside the range the rules admit are stored and read back.
         */
        // WHY : Assumptions: the two years are 1949 and 2100, one below and one above the range the
        //       update program admits, and both are ordinary dates to the engine. Asserting the exact
        //       dates rather than only the row count is what shows they were stored faithfully rather
        //       than clamped to a boundary, which is the failure a well-meaning conversion would
        //       introduce and which a count alone would not reveal.
        @Test
        void storesAndReturnsExpiryYearsTheRulesWouldReject() {
            loadFixture(RULE_REJECT_EXPIRY_YEAR);

            List<Card> stored = repository.findForwardFromCursor(null, null, null, LOOKAHEAD);

            assertThat(stored).hasSize(2);
            assertThat(stored).extracting(Card::getExpirationDate)
                    .containsExactly(LocalDate.of(1949, 6, 15), LocalDate.of(2100, 6, 15));
        }
    }

    /**
     * The objects the Flyway migration created, read from the database's own catalogue.
     *
     * <p>Assumptions: the provider is configured to emit no definitions in this module, so every
     * object asserted here exists because {@code V1__card.sql} created it. That configuration is what
     * makes these assertions meaningful rather than circular: were the provider generating the schema
     * from its mappings, the catalogue would agree with the entity by construction and a defect in the
     * migration would still produce a green run, because the only test that runs the migration is this
     * one.</p>
     *
     * <p>Assumptions: PostgreSQL support is not in the migration tool's core artifact. It moved out at
     * version 10, so {@code org.flywaydb:flyway-database-postgresql} is a required companion whose
     * absence has <b>no compile-time signal at all</b> -- the build succeeds and the context then fails
     * at run time, unable to resolve a database implementation for the connection. It is recorded here
     * because a reader tidying the dependency list would otherwise have every reason to take it for a
     * duplicate of the core artifact. This class is the only place in the module where a migration
     * executes, so this group is where that absence would surface.</p>
     *
     * <p>Assumptions: the schema itself is created for the container by the migrator's own
     * schema-creation setting, and not by {@code V1__card.sql}, which creates objects inside the schema
     * and contains no {@code CREATE SCHEMA}. The authority that creates it, its owning role and its
     * grants in a deployed environment is {@code data-migration/sql/V0__schemas_and_roles.sql}, which
     * is not on this module's classpath and therefore cannot be applied from here. A consequence worth
     * stating: this group establishes the table and its constraints, and not the privilege graph
     * around them, which belongs to a run against a provisioned environment.</p>
     *
     * <p>An inner test class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @Nested
    @DisplayName("the schema objects the Flyway migration created")
    class MigrationApplied {

        /**
         * Confirms that the migrated table carries exactly the seven columns the record maps onto.
         */
        // WHY : Assumptions: the columns are asserted in declaration order and as a closed set, so an
        //       eighth column would fail here as loudly as a missing one. Seven is the count the
        //       reference record fixes: app/cpy/CVACT02Y.cpy:4-11 declares a card number, an account
        //       identifier, a verification value, an embossed name, an expiration date and an
        //       active-status flag, and the FILLER at :11 is padding to the fixed record length and is
        //       deliberately dropped rather than carried; the version column that replaces it exists to
        //       carry the optimistic-concurrency check the reference performs with a before-image.
        @Test
        void createdTheTableWithExactlyTheSevenMappedColumns() {
            List<String> columns = catalogStrings(
                    "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'card' AND table_name = 'cards'"
                    + " ORDER BY ordinal_position");

            assertThat(columns).containsExactly("card_num", "account_id", "cvv_encrypted",
                    "embossed_name", "expiration_date", "active_status", "version");
        }

        /**
         * Confirms the three column types that carry contracts the entity alone cannot state.
         */
        // WHY : Assumptions: these three are singled out because each is a type the provider would have
        //       inferred differently, so each is evidence that the migration and not the mapping built
        //       the column. A String maps to a variable-width column by default, whereas card_num is
        //       fixed width because app/cpy/CVACT02Y.cpy:5 declares CARD-NUM as PIC X(16) -- and that
        //       fixed width is what governs the comparison the cursor predicate performs.
        //       expiration_date is a true date rather than the ten-character string the reference
        //       stores at :9. And cvv_encrypted is a byte column rather than the numeric one that
        //       CARD-CVV-CD at :7 would suggest, which is the difference between storing an enciphered
        //       envelope and storing the verification value in the clear.
        @Test
        void createdTheThreeColumnTypesTheMappingWouldNotHaveInferred() {
            List<String> types = catalogStrings(
                    "SELECT column_name || '=' || data_type FROM information_schema.columns"
                    + " WHERE table_schema = 'card' AND table_name = 'cards'"
                    + " AND column_name IN ('card_num', 'expiration_date', 'cvv_encrypted')"
                    + " ORDER BY column_name");

            assertThat(types).containsExactly(
                    "card_num=character", "cvv_encrypted=bytea", "expiration_date=date");
        }

        /**
         * Confirms that the account index exists and is not unique.
         */
        // WHY : Assumptions: the query selects only non-unique indexes, so this assertion fails both if
        //       the index is missing and if it was declared UNIQUE. That second case is the one worth
        //       guarding, because a unique index would satisfy every single-card assertion in this
        //       class and refuse only the multi-card one -- and it would contradict the reference
        //       directly, since app/jcl/CARDFILE.jcl:86 declares the alternate index NONUNIQUEKEY over
        //       the account identifier the comment at :78 names.
        @Test
        void createdTheAccountIndexAsANonUniqueIndex() {
            List<String> indexes = catalogStrings(
                    "SELECT i.relname FROM pg_index ix"
                    + " JOIN pg_class i ON i.oid = ix.indexrelid"
                    + " JOIN pg_class t ON t.oid = ix.indrelid"
                    + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = 'card' AND t.relname = 'cards'"
                    + " AND NOT ix.indisunique ORDER BY i.relname");

            assertThat(indexes).containsExactly("idx_cards_account_id");
        }

        /**
         * Confirms that the named status constraint exists and admits both reference status values.
         */
        // WHY : Assumptions: the constraint is asserted by NAME because the name is what a diagnostic
        //       reports, so a caller mapping a violation onto a message depends on it and an anonymous
        //       constraint would break that mapping while still refusing the same values. Both admitted
        //       values are then stored, because a constraint mistakenly written to admit only the
        //       active status would pass a presence check and refuse half the domain -- and the
        //       inactive status has to be authored, since every one of the fifty reference seed records
        //       carries the active value and not one carries the other.
        @Test
        void createdTheNamedStatusConstraintThatAdmitsBothValues() {
            List<String> constraints = catalogStrings(
                    "SELECT c.conname FROM pg_constraint c"
                    + " JOIN pg_class t ON t.oid = c.conrelid"
                    + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = 'card' AND t.relname = 'cards'"
                    + " AND c.contype = 'c' ORDER BY c.conname");

            assertThat(constraints).contains("ck_cards_active_status");

            loadFixture(VALID_ACTIVE);
            loadFixture(VALID_INACTIVE);
            assertThat(repository.findForwardFromCursor(null, null, null, LOOKAHEAD))
                    .extracting(Card::getActiveStatus)
                    .containsExactlyInAnyOrder("Y", "N");
        }

        /**
         * Confirms that the migrations ran through Flyway, by reading the history they record.
         */
        // WHY : Assumptions: this is the assertion that distinguishes a schema the migration built from
        //       one the provider generated, and it is the reason the group is worth having at all --
        //       every other assertion here would hold equally if the provider had emitted the
        //       definitions itself. The history table is placed inside the owned schema rather than
        //       wherever the connection resolved to, so reading it from `card` also confirms the
        //       default-schema setting took effect.
        // WHY : Refactoring Rationale: this expected exactly one version, on the ground that this module
        //       held one migration. It holds two: V2 closes the card_num character domain that V1
        //       declared a width for without constraining, and it arrived as a new version rather than
        //       as an edit to V1 because V1's checksum is recorded in every already-migrated database.
        //       The set is asserted in INSTALLED ORDER and closed, so a V2 that failed to apply, applied
        //       out of order, or was joined by an unannounced V3 all fail here.
        @Test
        void appliedBothMigrationsThroughFlywayInVersionOrder() {
            List<String> applied = catalogStrings(
                    "SELECT version FROM card.flyway_schema_history"
                    + " WHERE success AND version IS NOT NULL ORDER BY installed_rank");

            assertThat(applied).containsExactly("1", "2");
        }

        /**
         * Confirms the named card-number constraint exists and closes the domain at the column.
         */
        // WHY : Assumptions: the two refused shapes are the ones runtime testing was able to insert
        //       before this constraint existed, and they are refused for two different reasons that a
        //       width check alone would not have caught. Sixteen alphabetic characters fill the declared
        //       width exactly, so nothing about the length is wrong with them; a fifteen-digit value is
        //       padded to the width with a blank by the fixed-width column itself, so it arrives as
        //       sixteen characters too. Both are unrenderable as a masked listing row, and neither is
        //       excluded by CHAR(16).
        // WHY : Trade-offs: the refusal is asserted at the DATABASE and not through the repository,
        //       because closing the domain at the column is the whole point -- the bulk load in
        //       data-migration writes these rows directly and never passes through a Java constraint, so
        //       a rule that lived only in the application would not reach the path that populates the
        //       table. The insert is issued as raw SQL for the same reason.
        @Test
        void createdTheNamedCardNumberConstraintThatClosesTheDomain() {
            List<String> constraints = catalogStrings(
                    "SELECT c.conname FROM pg_constraint c"
                    + " JOIN pg_class t ON t.oid = c.conrelid"
                    + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = 'card' AND t.relname = 'cards'"
                    + " AND c.contype = 'c' ORDER BY c.conname");

            assertThat(constraints).contains("ck_cards_card_num_digits");

            assertThat(insertFailureFor("ABCDEFGHIJKLMNOP"))
                    .as("sixteen alphabetic characters fill the width and are still refused")
                    .contains("ck_cards_card_num_digits");
            assertThat(insertFailureFor("111122223333444 "))
                    .as("a fifteen-digit value padded to the width is refused")
                    .contains("ck_cards_card_num_digits");
            assertThat(catalogStrings(
                    "SELECT card_num FROM card.cards WHERE card_num !~ '^[0-9]{16}$'"))
                    .as("neither refused row reached the table")
                    .isEmpty();
        }

        /**
         * Confirms the constraint admits a conforming key, so the refusals above are not vacuous.
         */
        // WHY : Assumptions: a constraint written to refuse everything would satisfy both refusals above
        //       and break the whole service, so the admitted case is asserted separately rather than
        //       being left to the other groups. The key used carries a leading zero, which is the part of
        //       the domain a numeric column would have discarded and a numeric-cast check would have
        //       admitted for the wrong reason.
        @Test
        void admitsAConformingKeyIncludingItsLeadingZero() {
            repository.save(new Card("0100000000000002", 906L, syntheticEnvelope(),
                    "Domain Control", LocalDate.of(2025, 1, 1), "Y"));

            assertThat(repository.findById("0100000000000002")).isPresent();
        }

        /**
         * Issues a direct insert of one card number and returns the failure it raised.
         *
         * @param cardNumber the sixteen stored characters to attempt, which the constraint is expected to
         *     refuse
         * @return the message the raised failure carried, never {@code null}
         * @throws AssertionError if the insert succeeded, which would mean the domain is still open
         */
        // WHY : Assumptions: the caller asserts on the CONSTRAINT NAME appearing in this message, which
        //       is why the constraint is named in the migration rather than taking a server-generated
        //       name. Asserting on the name rather than on the sentence around it is what keeps the
        //       assertion independent of the server's own wording.
        private String insertFailureFor(String cardNumber) {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO card.cards"
                        + " (card_num, account_id, embossed_name, expiration_date, active_status)"
                        + " VALUES ('" + cardNumber + "', 907, 'Domain Probe', DATE '2025-01-01',"
                        + " 'Y')");
            } catch (SQLException refused) {
                return refused.getMessage();
            }
            throw new AssertionError(
                    "the insert was accepted, so the card_num domain is not closed at the column");
        }

        /**
         * Confirms that the byte column returns exactly the bytes it was given.
         */
        // WHY : Assumptions: the bytes below are framed counters that correspond to no card and were
        //       never a verification value, because what is under test is the column's fidelity rather
        //       than any cipher. This is one of the four engine behaviours that make a substitute
        //       database unusable here: a store that narrowed the column, re-encoded it as text or
        //       padded it would fail this assertion and pass every other one in the class, since no
        //       other assertion reads the column at all.
        // WHY : Trade-offs: the whole envelope is compared rather than its length, so a reordering
        //       inside a run of the correct length is detected as well as a truncation. The comparison
        //       is on the framed bytes and never on any decrypted form, and neither the value nor any
        //       part of it is placed in a message.
        @Test
        void storesAndReturnsTheEncryptedValueColumnUnchanged() {
            EncryptedCvv envelope = syntheticEnvelope();
            repository.save(new Card("0100000000000001", 905L, envelope, "Byte Column Control",
                    LocalDate.of(2025, 1, 1), "Y"));

            Card reread = repository.findById("0100000000000001").orElseThrow();

            assertThat(reread.getCvvEncrypted()).isNotNull();
            assertThat(reread.getCvvEncrypted().envelope()).isEqualTo(envelope.envelope());
        }
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: this configuration declares no component scan, and that omission is its whole
     * purpose. The module's own application class scans the bounded context and so registers the
     * security chain, the key-management client, the card-selector signer and the contract metadata,
     * none of which a repository assertion needs and each of which is a further way for one to fail
     * for an unrelated reason. Naming the two persistence packages explicitly leaves the framework's
     * own auto-configuration to build the pool from the properties the container registered.</p>
     *
     * <p>Trade-offs: it is declared nested rather than as a file of its own, which keeps the package
     * charter's closed file set true -- that charter admits exactly this class and the charter itself
     * -- at the cost of not being reusable by another test. The cost is accepted because the charter
     * rejects a shared holder for the same reason it would apply here: two configurations enabling
     * different subsets of auto-configuration would let two tests disagree about the context they
     * reach one schema through.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.card.domain")
    @EnableJpaRepositories("com.carddemo.card.repository")
    static class CardPersistenceTestApplication {
    }
}
