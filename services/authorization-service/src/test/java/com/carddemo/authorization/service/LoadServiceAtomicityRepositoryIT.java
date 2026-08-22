package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.PackedDecimalCodec;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that one extract load is ONE unit of work on a real engine: a refusal leaves no visible row.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: {@link LoadService} states that a load is atomic -- that a re-run after a failure starts
 * from a database that is either wholly loaded or wholly absent. That is a claim about what a SEPARATE
 * READER can see after a refusal, so only a real transaction manager over a real engine can answer it.
 * This class is where it is answered.
 *
 * <p>⚠️ Refactoring Rationale: this class is an addition, and it exists because the claim was false while
 * being documented as true. The loader opened one transaction per five hundred records, so a refusal in a
 * later chunk left every earlier chunk committed and a refusal in the child file left the whole summary
 * file committed. Its unit test could not detect either: a mocked repository has no transaction to
 * discard, so it keeps whatever it recorded whether the boundary enclosed it or not, and the only thing
 * that test can observe is how many boundaries were opened. The two cases below observe the rows
 * THEMSELVES, through a connection that shares nothing with the load, which is the only observation that
 * distinguishes a rolled-back write from a committed one.
 *
 * <h2>What is real here, and what is not</h2>
 *
 * <p>Assumptions: the repositories, the transaction manager, the schema, the migration and the engine are
 * real, and the extracts are the committed unload fixtures the sibling unit test reads. Nothing is stubbed,
 * because there is nothing to stub -- this loader takes two streams and two repositories and calls no
 * other context.
 *
 * <p>Assumptions: every assertion is made through plain JDBC on a connection of its own. A repository read
 * would share the persistence context the writes went through and could answer from the identity map,
 * reporting a row the engine never retained -- which is the exact failure this class exists to detect.
 *
 * <h2>Trade-offs</h2>
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's Failsafe configuration
 * includes exactly that suffix, so any other name would leave the class unrun. The suffix names the TIER
 * it belongs to, not the subject it exercises.
 *
 * <p>Trade-offs: one case writes five hundred rows and then discards them, which is more work than any
 * other case in this module. It is accepted because the chunk boundary is five hundred records and a
 * smaller extract cannot reach a second chunk at all -- an extract inside one chunk passes against both
 * the atomic loader and the per-chunk one it replaced, and would therefore assert nothing.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
@SpringBootTest(
        classes = LoadServiceAtomicityRepositoryIT.LoadTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LoadServiceAtomicityRepositoryIT {

    /** The engine image, named by manifest digest so the version cannot drift: PostgreSQL 17.10-alpine. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The engine every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** Where the committed extract fixtures sit on the test class path. */
    private static final String FIXTURES = "/fixtures/";

    /** The committed summary extract: two well-formed hundred-byte root images. */
    private static final String SUMMARY_FIXTURE = "unload-prefixed-summary-100.bin";

    /** The committed detail extract: four well-formed prefixed authorization records. */
    private static final String DETAIL_FIXTURE = "unload-prefixed-detail-206.bin";

    /** A committed segment whose fraud marker is outside its declared domain, used to force a refusal. */
    private static final String SEGMENT_FRAUD_OUT_OF_DOMAIN = "pautdtl1-auth-fraud-invalid.bin";

    /** How many bytes one summary image occupies. */
    private static final int SUMMARY_STRIDE = 100;

    /** How many bytes one prefixed authorization record occupies. */
    private static final int DETAIL_STRIDE = 206;

    /** How many bytes the packed parent key ahead of each authorization segment occupies. */
    private static final int PREFIX_WIDTH = 6;

    /** How many digits that packed key carries, {@code PIC S9(11) COMP-3}. */
    private static final int PREFIX_DIGITS = 11;

    /** How many roots the summary fixture holds. */
    private static final int ROOT_COUNT = 2;

    /** How many children the detail fixture holds. */
    private static final int CHILD_COUNT = 4;

    /**
     * How many records the loader reads before it goes back to the stream for more.
     *
     * <p>⚠️ Assumptions: this repeats a figure the loader holds privately, for the reason its sibling unit
     * test records: a case that must span more than one chunk has to build an extract larger than the
     * chunk, and no accessor publishes it. Trade-offs: were the loader's chunk to grow, the case below
     * would stop spanning two chunks and would fail on its row count rather than quietly degrading into a
     * single-chunk case that proves nothing.
     */
    private static final int CHUNK_RECORDS = 500;

    /**
     * The first account the synthetic multi-chunk extract names.
     *
     * <p>Assumptions: the range is clear of the two fixture accounts, so the multi-chunk case and the
     * fixture cases cannot collide on a key, and it stays inside eleven digits so every generated key packs
     * into the segment's six bytes.
     */
    private static final long SYNTHETIC_ACCOUNT_BASE = 20_000_000_000L;

    /**
     * The record ceiling the loader under test is built with.
     *
     * <p>Assumptions: it is stated generously rather than left at the production default so the multi-chunk
     * case is refused by the fault it arranges and never by the ceiling, which would be a passing
     * assertion about the wrong refusal.
     */
    private static final int GENEROUS_MAX_RECORDS = 10_000;

    /** The real summary repository the loader writes through, and which the cleanup empties. */
    @Autowired
    private PendingAuthSummaryRepository summaries;

    /** The real authorization repository the loader writes through, and which the cleanup empties. */
    @Autowired
    private PendingAuthDetailRepository details;

    /** The manager the loader's own transaction is opened through. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The pool the JDBC observations take their own connections from. */
    @Autowired
    private DataSource dataSource;

    /** The template the cleanup writes run in, which must commit before a case begins. */
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
     * Empties both tables, so no case inherits another's rows.
     *
     * <p>Assumptions: authorizations before summaries, the authorization carrying the foreign key; and in
     * its own transaction, which has committed before a case begins, so a case that observes no row can
     * tell an absent write from an unseen one.
     */
    @BeforeEach
    void emptyBothTables() {
        this.transactions.executeWithoutResult(status -> {
            this.details.deleteAllInBatch();
            this.summaries.deleteAllInBatch();
        });
    }

    /**
     * A refusal after the first chunk has written leaves the engine holding none of its rows.
     *
     * <p>⚠️ Purpose: this is the case the per-chunk boundary failed. The extract is five hundred
     * well-formed records -- exactly one chunk -- followed by a truncated record, so the loader writes
     * five hundred rows, goes back to the stream, and refuses the extract as malformed. A loader that
     * committed per chunk left those five hundred rows visible to every reader, and the caller received an
     * exception naming a trailing-byte fault with no indication that a partial load had been left behind.
     *
     * <p>Assumptions: the refusal is arranged as a TRAILING PARTIAL RECORD rather than as a store failure,
     * because it is the one fault that is guaranteed to be discovered after a whole chunk has been written
     * and before the next one is: the reader cannot know the extract ends badly until it has read past the
     * last whole record. Alternatives Considered: making a repository fail on its second call, as the unit
     * test does. Rejected here because a repository that fails is a stub, and this class exists precisely
     * to remove the stub from the observation.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refusal in the second chunk leaves the first chunk's five hundred rows invisible")
    void aRefusalAfterTheFirstChunkLeavesNoVisibleRow() {
        byte[] wholeChunk = manySummaries(CHUNK_RECORDS);
        byte[] extract = Arrays.copyOf(wholeChunk, wholeChunk.length + SUMMARY_STRIDE / 2);
        LoadService subject = loader();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> subject.loadSummaries(new ByteArrayInputStream(extract)))
                .withMessageContaining("whole number of");

        assertThat(rowCount("pending_auth_summary"))
                .as("the five hundred rows of the first chunk share the refusal's transaction, so a"
                        + " separate reader must see none of them")
                .isZero();
    }

    /**
     * A refusal in the child file leaves the summary file of the same load invisible.
     *
     * <p>⚠️ Purpose: this is the cross-file half, and it is the one an operator meets first. A corrupt
     * detail extract is an ordinary event -- a truncated transfer, a mis-handed generation -- and the
     * loader used to answer it by committing the whole summary file and then raising. The database was
     * then holding root segments with no authorizations, which is indistinguishable by inspection from a
     * legitimately childless account, so the corrective re-run could not be told from a first run.
     *
     * <p>Assumptions: the corruption is written into the SECOND authorization record and is a committed
     * out-of-domain segment behind the fixture's own well-formed packed prefix, so the refusal is
     * attributable to the segment rule rather than to a hand-computed offset that a layout change would
     * silently relocate.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a child-file refusal leaves no summary row of the same load visible")
    void aChildRefusalLeavesNoVisibleSummary() {
        LoadService subject = loader();

        assertThatExceptionOfType(LoadService.MalformedSegmentException.class)
                .isThrownBy(() -> subject.load(
                        open(SUMMARY_FIXTURE), new ByteArrayInputStream(corruptedDetailExtract())));

        assertThat(rowCount("pending_auth_summary"))
                .as("the summary pass wrote inside the transaction the child refusal discards, so a"
                        + " separate reader must see no root segment")
                .isZero();
        assertThat(rowCount("pending_auth_detail"))
                .as("and no authorization either")
                .isZero();
    }

    /**
     * A refused load is recovered by re-running it, and re-running a good load changes nothing.
     *
     * <p>Purpose: atomicity is only half of the recovery property. The other half is that the operator's
     * action after a refusal is to fix the extract and run the same command again, and that running it
     * twice is harmless -- which is what the runbook instructs and what the reference program's own
     * duplicate tolerance was for. This case rehearses exactly that sequence on a real engine: a refused
     * load, then a clean one, then the clean one again.
     *
     * <p>⚠️ Assumptions: the third step's counts are asserted to be UNCHANGED rather than the load being
     * asserted to insert nothing. Refactoring Rationale: while the loader committed per chunk, an
     * interrupted load left rows behind and the re-run's insert count depended on where the interruption
     * fell -- so the observable an operator can rely on is the state of the tables, not the counters of any
     * one run. That remains the right observable now that the interruption leaves nothing, because it is
     * the one a runbook can tell an operator to check.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refused load is recovered by re-running it, and a second clean run changes nothing")
    void aRefusedLoadIsRecoveredByRerunningIt() {
        LoadService subject = loader();
        assertThatExceptionOfType(LoadService.MalformedSegmentException.class)
                .isThrownBy(() -> subject.load(
                        open(SUMMARY_FIXTURE), new ByteArrayInputStream(corruptedDetailExtract())));

        LoadService.LoadOutcome recovered = subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));
        LoadService.LoadOutcome repeated = subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

        assertThat(recovered.inserted())
                .as("the re-run starts from an empty table, so it inserts the whole extract")
                .isEqualTo(ROOT_COUNT + CHILD_COUNT);
        assertThat(repeated.inserted())
                .as("and a further run recognises every record as already stored")
                .isZero();
        assertThat(repeated.alreadyPresent()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
        assertThat(rowCount("pending_auth_summary"))
                .as("the engine holds one root per fixture record however many times the load ran")
                .isEqualTo(ROOT_COUNT);
        assertThat(rowCount("pending_auth_detail"))
                .as("and one authorization per fixture record")
                .isEqualTo(CHILD_COUNT);
    }

    /**
     * Builds the loader under test over the real repositories and a transaction of its own.
     *
     * <p>Assumptions: the template is constructed here rather than injected, so the boundary these cases
     * observe is the one the loader opens and not one a test fixture arranged around it.
     *
     * @return a loader at a generous record ceiling, writing the two real repositories; never {@code null}
     */
    private LoadService loader() {
        return new LoadService(this.summaries, this.details,
                new TransactionTemplate(this.transactionManager), GENEROUS_MAX_RECORDS);
    }

    /**
     * Builds a summary extract of a stated size, every record naming a different account.
     *
     * <p>Assumptions: the accounts are made DISTINCT rather than the fixture being repeated, so every
     * record of the chunk is a real insert. Repeating one record would leave the chunk with one insert and
     * four hundred and ninety-nine counted duplicates, and a case asserting that a discarded chunk had
     * written would then be asserting almost nothing.
     *
     * <p>Assumptions: the account is overwritten IN PLACE at offset zero, which is where
     * {@code cpy/CIPAUSMY.cpy} L19 puts {@code PA-ACCT-ID PIC S9(11) COMP-3}. Every other field keeps the
     * committed fixture's bytes, so each record stays a valid segment image the schema accepts.
     *
     * @param records how many records the extract should hold; must be positive
     * @return an extract of exactly that many well-formed summary images; never {@code null}
     */
    private static byte[] manySummaries(int records) {
        byte[] template = Arrays.copyOfRange(bytes(SUMMARY_FIXTURE), 0, SUMMARY_STRIDE);
        byte[] extract = new byte[records * SUMMARY_STRIDE];
        for (int index = 0; index < records; index++) {
            int at = index * SUMMARY_STRIDE;
            System.arraycopy(template, 0, extract, at, SUMMARY_STRIDE);
            byte[] account = PackedDecimalCodec.encodePacked(
                    BigDecimal.valueOf(SYNTHETIC_ACCOUNT_BASE + index), PREFIX_DIGITS, 0, true);
            System.arraycopy(account, 0, extract, at, account.length);
        }
        return extract;
    }

    /**
     * Builds a two-record detail extract whose second record carries an out-of-domain segment.
     *
     * @return an extract the loader accepts the first record of and refuses the second; never {@code null}
     */
    private static byte[] corruptedDetailExtract() {
        byte[] extract = Arrays.copyOf(bytes(DETAIL_FIXTURE), DETAIL_STRIDE * 2);
        byte[] outOfDomainSegment = bytes(SEGMENT_FRAUD_OUT_OF_DOMAIN);
        System.arraycopy(outOfDomainSegment, 0, extract, DETAIL_STRIDE + PREFIX_WIDTH,
                outOfDomainSegment.length);
        return extract;
    }

    /**
     * Opens one committed fixture as a stream.
     *
     * @param name the fixture's file name within the class-path fixture directory; must not be
     *     {@code null}
     * @return a stream over that fixture's bytes; never {@code null}
     */
    private static InputStream open(String name) {
        return new ByteArrayInputStream(bytes(name));
    }

    /**
     * Reads one committed fixture in full.
     *
     * @param name the fixture's file name within the class-path fixture directory; must not be
     *     {@code null}
     * @return the fixture's whole contents as raw bytes; never {@code null}
     * @throws AssertionError if the fixture is absent from the test class path, which fails rather than
     *     skips: a case that silently ran against no input would report a passing verdict on nothing
     */
    private static byte[] bytes(String name) {
        try (InputStream source =
                LoadServiceAtomicityRepositoryIT.class.getResourceAsStream(FIXTURES + name)) {
            if (source == null) {
                throw new AssertionError("fixture " + name + " is not on the test class path");
            }
            return source.readAllBytes();
        } catch (IOException unreadable) {
            throw new AssertionError("fixture " + name + " could not be read", unreadable);
        }
    }

    /**
     * Counts the rows of one table on a connection of its own.
     *
     * @param table the unqualified table name; must not be {@code null}
     * @return how many rows a reader outside the load's transaction can see
     * @throws IllegalStateException if the count could not be run
     */
    private int rowCount(String table) {
        String query = "SELECT count(*) FROM " + table;
        // WHY : Assumptions: the connection is taken from the pool and closed immediately, so the read
        //       runs in its own auto-committed transaction. A read that shared the load's connection
        //       would see that transaction's uncommitted writes and would report rows a rollback was
        //       about to remove, which is the one answer this class must not be able to give.
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
     * The narrowest context that can host the loader under test against a real engine.
     *
     * <p>Assumptions: the loader is NOT component-scanned even though it is a {@code @Service}. Scanning
     * its package would also start the outbox publisher, which wants a queue client, and the remote
     * account client, which wants a network address; and scanning with default filters would pick up the
     * nested configuration classes of the other integration tests in this package -- the test tree shares
     * the production package names -- each declaring its own repository enablement, which fails the
     * context on a duplicate bean definition. Each case builds the loader it needs instead.
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.authorization.domain")
    @EnableJpaRepositories("com.carddemo.authorization.repository")
    static class LoadTestApplication {

        /**
         * The template the per-case cleanup writes run in.
         *
         * @param manager the real transaction manager the context created
         * @return a template at default propagation, which the cleanup commits through
         */
        @Bean
        TransactionTemplate cleanupTransactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
