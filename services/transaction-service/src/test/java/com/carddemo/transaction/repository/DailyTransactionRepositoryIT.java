package com.carddemo.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.transaction.domain.DailyTransaction;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins {@code ledger.daily_transactions}, the pre-posting feed of the LEDGER bounded context, onto a
 * real PostgreSQL engine.
 *
 * <p>The package charter beside this file assigns this class two properties to prove and states them
 * in that order. The first is nullability: the processed timestamp is NULLABLE here while the
 * identically-declared column of {@code ledger.transactions} is {@code NOT NULL}. The second is the
 * key: because the program that reads this feed front to back declares no record key at all, no
 * value the feed carries is unique, so the table's primary key is over a GENERATED ingestion
 * sequence and a chunked scan has to return BOTH rows of a feed that repeats one transaction
 * identifier, each still individually addressable. Everything below serves one of those two, or
 * serves the column contracts they are asserted through.
 *
 * <p>The charter also owns the rulings this class obeys rather than restates -- the closed file set,
 * the keyset-only vocabulary, the injected determinism, the single-sourcing obligation and the
 * report directories. They are cited from here. What this block records instead is the decision
 * taken at each point where a reasonable alternative existed.
 *
 * <h2>What this class deliberately does not re-assert</h2>
 *
 * <p>{@code TransactionRepositoryIT} in this package already pins the two facts every other test
 * here depends on: that Flyway applied the migration, and that an unqualified entity resolves to
 * {@code ledger} rather than to whatever else the connection can see. It also pins the exact
 * CONTRAST this class exists to complete, in its case named for the not-null column, which shows
 * that a posted row carrying no processed timestamp is refused. Neither is repeated below. A second
 * copy of an assertion is a second thing to keep true, and when the two copies drift the one that
 * still passes hides the one that should not.
 *
 * <h2>The nullability asymmetry, and the two independent findings behind it</h2>
 *
 * <p>Assumptions: the processed timestamp is absent on a feed row and present on a posted one, and
 * that difference is a semantic one carried entirely by nullability, because the two copybooks
 * declare the field at the identical {@code PIC X(26)} width at line 17 of each. Two independent
 * findings support it, and either one alone would read as an accident of one extract.
 *
 * <p>The first is mechanical. {@code app/cbl/CBTRN02C.cbl} populates the field in exactly one place:
 * line 438 moves the freshly minted stamp into the POSTED record, and that line sits inside
 * paragraph {@code 2000-POST-TRANSACTION.} which begins at line 424. A feed row has by definition
 * not been through that paragraph yet -- the loop at lines 205 to 219 reaches it only at line 212,
 * and only for a record that line 211 found valid -- so there is no point in the feed's life at
 * which the field could have been set.
 *
 * <p>The second is observational. {@code app/data/ASCII/dailytran.txt} holds 105300 bytes as exactly
 * 300 records of 350 bytes each, and the 26 bytes at one-based positions 305 to 330 are blank on 300
 * of those 300 records, while the ORIGINATING stamp at 279 to 304 is populated on all 300. The
 * blankness is therefore specific to this one field rather than a gap in the extract, which is what
 * makes it evidence of the contract rather than evidence of a bad export.
 *
 * <p>Assumptions: that asymmetry is why the fixture convention keeps two record files rather than
 * one. The sibling {@code src/test/resources/fixtures/README.md} records the decision in its own
 * section on the two files, and the reasoning is cited from there rather than reproduced here. The
 * normative physical statement is this module's {@code db/migration/V1__ledger.sql}, which declares
 * the column {@code NOT NULL} for the posted master at its line 247 and leaves it unconstrained for
 * this feed at its line 463.
 *
 * <p>Assumptions: no assertion or helper below may treat the processed timestamp as present. A
 * builder that defaulted it, or a comparison that dereferenced it, would pass on every row it was
 * given and would quietly stop covering the property this class exists for.
 *
 * <h2>The engine is real, and an in-memory substitute could not carry the properties</h2>
 *
 * <p>Assumptions: H2 is the specific alternative excluded, and it is excluded on capability rather
 * than on preference. Two of the properties asserted below are engine behaviours it does not
 * reproduce: a database-assigned identity handed back on insert and then used as the total ordering
 * of a resumable scan, and a declared-width character column whose blank padding decides whether a
 * sixteen-character key compares equal on the way out. Substituting an embedded engine would leave
 * both assertions passing against something other than the engine that runs in production, which is
 * the failure mode a repository test exists to prevent. The module's own POM records the same
 * exclusion beside the Testcontainers artifacts it declares at test scope, and no embedded driver is
 * on this classpath at all.
 *
 * <p>Assumptions: connection coordinates arrive as a bean and never as text. The container field
 * below carries {@code @ServiceConnection}, which contributes a connection-details bean that the
 * auto-configuration prefers over any datasource property, so no URL, host, port, user name or
 * password appears anywhere in this file. The container's port is assigned at run time, so a written
 * connection string would either address nothing or address whichever database happened to be
 * listening -- and the second failure mode is the worse of the two, because it passes.
 *
 * <h2>How the schema is reached</h2>
 *
 * <p>Trade-offs: {@code @ActiveProfiles("test")} is load-bearing rather than conventional. The
 * profile document holds the only surviving mechanism that reaches the {@code ledger} schema inside
 * a container database that starts empty, because the JDBC URL is GENERATED by the container and so
 * no schema parameter can ride on it. Omitting the annotation does not degrade a run, it fails it on
 * a missing schema before any assertion executes. The compromise accepted is that a mandatory
 * annotation is enforced by that failure rather than by the compiler. The profile file is already
 * authored and is cited rather than duplicated, because a second copy of a profile key is a second
 * place to change it and only one of the two would be read.
 *
 * <p>Assumptions: Flyway needs two artifacts on this classpath and not one. {@code flyway-core} and
 * {@code flyway-database-postgresql} are both managed at 13.0.0 by {@code services/pom.xml}. From
 * Flyway 10 onwards the database-specific support moved out of core into companion artifacts, so
 * core on its own resolves and COMPILES perfectly and then fails as the application context starts,
 * with no PostgreSQL support registered. The failure therefore cannot appear at build time; it
 * appears the moment a container starts, which is why the sibling test asserts the migration ran by
 * reading Flyway's own history rather than by observing that a query happened to work.
 *
 * <h2>The test-context annotation, and why the persistence context is cleared</h2>
 *
 * <p>Alternatives Considered: the JPA test slice annotation, which is the shorter wiring and the one
 * a reader is likelier to expect on a repository test. Rejected on availability rather than on
 * taste: Spring Boot 4.1.0 relocated that slice into a separate per-technology test artifact this
 * module does not declare, and declaring it would be a change to a POM this subtree does not own.
 * The slice would additionally have replaced the DataSource with an embedded database by default,
 * and the paragraph above rules out the only engine that could have filled that role.
 *
 * <p>Alternatives Considered: naming this module's own application class as the test configuration,
 * which is the ordinary shape. Rejected on a measured failure: that class component-scans this
 * bounded context and so registers an explicit pool factory built from datasource PROPERTIES, while
 * {@code @ServiceConnection} contributes a connection-details BEAN instead, so the context fails
 * while creating the pool and every case reports a context load error before any assertion runs. The
 * nested configuration at the foot of this file registers no component scan, so the framework's own
 * auto-configuration builds the pool from that bean.
 *
 * <p>Alternatives Considered: extracting the container, the profile annotation and the row builder
 * into a shared abstract base class for this package's four integration tests, or into an imported
 * test configuration. Rejected, and the charter records the same rejection as the reason its file
 * set is closed: a base class holding a container is shared mutable state, so rows one test inserts
 * become rows another test reads and a failure names the test that ran afterwards rather than the one
 * that caused it. Each test owns its own schema state instead, which is what lets any one of the four
 * be run alone and still mean something. The cost accepted is that the container field and the
 * profile annotation are declared four times.
 *
 * <p>Alternatives Considered: reading a persisted row back through the same persistence context that
 * wrote it. Rejected because it does not test the database: the first-level cache returns the very
 * instance just written, so an assertion phrased against a column, a width or an ordering would pass
 * without a query having reached the engine at all -- and would keep passing if the column were
 * removed. Every case below writes through the helper that flushes and then CLEARS, so each read is
 * a fresh select and each assertion is about the schema rather than about a map.
 *
 * <h2>Contracts consumed, never re-declared</h2>
 *
 * <p>Refactoring Rationale: {@code tests/README.md} lines 540 to 542 impose single-sourcing on the
 * reference suite's own tests -- a layout resolves through the compiler's include path and is never
 * duplicated -- and the analogue holds here exactly. So the money semantic comes from
 * {@code com.carddemo.common.money.Money}, the 26-character timestamp form from
 * {@code com.carddemo.common.time.TimestampFormatter}, and the record layout, the field widths and
 * the byte offsets from the entity, the migration and the fixtures document that already own them.
 * No zoned-decimal codec, no overpunch table and no 350-byte layout is restated in this file. A
 * local copy of any of them would reintroduce precisely the drift an include path forecloses, and the
 * drift would be silent, because both copies would go on compiling and the stale one would go on
 * passing its own assertions.
 *
 * <h2>The scan is keyset-ordered, and the alternative loses rows</h2>
 *
 * <p>Alternatives Considered: offset pagination in either shape the framework offers for it, and the
 * chunk-oriented batch reader built on the same mechanism. Rejected on a defect rather than a
 * preference: positioning by counting rows from the start of an ordered set means an insert landing
 * before the cursor changes how many rows precede it, so a scan positioned that way SKIPS rows it
 * never read and REPEATS rows it already read. A feed is exposed to exactly that, being loaded by one
 * process while a job walks it. A key already returned keeps its place in the ordering no matter what
 * is inserted around it, which is why the cursor here is a key and the row cap is
 * {@code org.springframework.data.domain.Limit}. No offset, page-number or total-count term appears
 * anywhere below.
 *
 * <p>Assumptions: the repository under test declares NO unposted-only finder, and its absence is a
 * ruling rather than an omission. The posting loop cited above consumes the ENTIRE feed and applies
 * no predicate of any kind. Supplying one would change which rows a rerun processes, so no case below
 * tests for such a method and none invents one.
 */
@Testcontainers
@SpringBootTest(
        classes = DailyTransactionRepositoryIT.FeedPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DailyTransactionRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine, which is the major
     * version the deployed cluster runs.
     */
    // WHY : Assumptions: a digest is used because determinism is the property being bought, and a
    //       digest is the only reference that supplies it. The same digest resolves to the same
    //       bytes forever, so a run months from now exercises the engine this file was written
    //       against rather than whatever has since been published.
    // WHY : Alternatives Considered: an exact minor tag such as postgres:17.10-alpine, which reads
    //       better than a digest. Rejected because it is still mutable -- a publisher may rebuild
    //       and republish one minor tag on a new base layer -- so it would narrow the drift without
    //       closing it. A floating tag is excluded outright: it would let the engine change under an
    //       unchanged assertion, which is the one way a green result here could mean nothing.
    // WHY : Assumptions: the digest is deliberately the one the sibling test in this package already
    //       names. Two integration tests pinning two different engines could disagree about the same
    //       schema, and the disagreement would surface as whichever of them ran second.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The container the assertions run against, started once for this class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} and NOT from
     * {@code org.testcontainers.containers}. Testcontainers 2.0.5 ships both and only the legacy one
     * is deprecated, so importing the legacy package would carry a compile-time notice into every
     * future build of this module for no benefit. The replacement is not generic, so the declaration
     * carries no type argument; nothing here relied on the self-type that parameter existed to
     * refine, because the container is configured entirely through {@code @ServiceConnection} rather
     * than by chained builder calls.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The number of rows a chunk of this scan processes, one fewer than every query below asks for.
     *
     * <p>Assumptions: this lives in code beside the queries that consume it rather than in the test
     * profile, because the surplus probe row only makes sense read together with the size it is one
     * larger than. The charter states the same prohibition for the whole package: a tunable size
     * would let a case drift from the contract while still reporting success.
     *
     * <p>Trade-offs: two is small enough that a five-row feed needs three chunks, which is what makes
     * the traversal case cover an exhausted final chunk as well as two full ones. A larger size would
     * have covered the same code in one call and proved less.
     */
    private static final int CHUNK_ROWS = 2;

    /**
     * The number of rows the traversal case loads, chosen so the scan needs three chunks.
     *
     * <p>Assumptions: five rows against a chunk of two yields two full chunks that each report a
     * continuation and a third holding one row that reports none, so the loop's exit is exercised by
     * exhaustion rather than by a row count the case already knew.
     */
    private static final int FEED_ROWS = 5;

    /**
     * The originating timestamp of record 1 of the seed feed, as its 26 bytes read on disk.
     *
     * <p>Assumptions: this is the literal content of one-based positions 279 to 304 of the first
     * record of {@code app/data/ASCII/dailytran.txt}. It is quoted rather than computed so that the
     * form asserted below is the form the reference data actually carries, and it is the ORIGINATING
     * stamp specifically -- the processed stamp at 305 to 330 is blank on every record, which is the
     * property this class exists to pin.
     */
    private static final String SEED_ORIG_TS_TEXT = "2022-06-10 19:27:53.000000";

    /**
     * The same originating timestamp as an instant, for building rows.
     *
     * <p>Assumptions: this is a literal drawn from the reference extract and never a wall-clock read.
     * The charter states the prohibition for the whole package: a case that reads the current time
     * and compares a stored value against it passes for a reason unrelated to the code under test,
     * and fails whenever the two reads straddle a boundary -- a failure that reproduces only at the
     * hour it was introduced. The test profile deliberately supplies no clock, so determinism here
     * comes from the value being fixed at the source.
     */
    private static final LocalDateTime SEED_ORIG_TS = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /**
     * An originating timestamp carrying a non-zero microsecond component.
     *
     * <p>Assumptions: the seed extract's own stamps all end in six zero digits, so no seed value can
     * distinguish a column that keeps microseconds from one that truncates them. This literal exists
     * to make that distinction, and its fractional part is chosen with six significant digits so that
     * a column of lower precision loses a digit the assertion names.
     */
    private static final LocalDateTime MICROSECOND_ORIG_TS =
            LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_000);

    /**
     * The rendered form the microsecond-bearing instant above must round-trip to.
     */
    private static final String MICROSECOND_ORIG_TS_TEXT = "2022-06-10 19:27:53.123456";

    /**
     * The card number of record 2 of the seed feed, which begins with a zero.
     *
     * <p>Assumptions: this is the literal content of one-based positions 263 to 278 of the second
     * record of {@code app/data/ASCII/dailytran.txt}, and its leading zero is the whole reason it is
     * quoted here rather than any of the other 299 values available. {@code app/cpy/CVTRA06Y.cpy}
     * declares the field {@code PIC X(16)} at its line 15, a fixed-width CHARACTER field, so the
     * width and the leading zero are both part of the contract.
     */
    private static final String SEED_CARD_LEADING_ZERO = "0927987108636232";

    /**
     * The card number of record 1 of the seed feed, which begins with a non-zero digit.
     *
     * <p>Assumptions: this is one-based positions 263 to 278 of the first record, and it is present
     * so that rows carrying different cards can be told apart without either value being minted.
     */
    private static final String SEED_CARD = "4859452612877065";

    /**
     * The decoded amount of record 1 of the seed feed, positive.
     *
     * <p>Assumptions: the encoded bytes at one-based 133 to 143 of record 1 are
     * {@code 0000005047G}, and {@code tests/helpers/record_codec.py} carries that exact string as a
     * known-answer vector at its line 81, whose comment names it as the first daily-transaction
     * amount and reads the trailing overpunch as a positive seven. Its line 82 gives the decoded
     * value quoted here. The decode itself belongs to that reference and to the shared kernel's
     * codecs; this file consumes the answer rather than recomputing it.
     */
    private static final BigDecimal SEED_AMOUNT_POSITIVE = new BigDecimal("504.77");

    /**
     * The decoded amount of the negative counterpart vector, same magnitude of digits.
     *
     * <p>Assumptions: {@code tests/helpers/record_codec.py} carries {@code 0000005047J} at its lines
     * 83 and 84 as the negative vector, the trailing overpunch reading as a negative one. Covering a
     * negative value is not optional: the seed feed's 300 amounts split 250 positive to 50 negative,
     * so half of the sign contract would go untested by a positive-only case, and a mis-declared
     * column is exactly the kind that keeps a magnitude and loses a sign.
     */
    private static final BigDecimal SEED_AMOUNT_NEGATIVE = new BigDecimal("-504.71");

    /**
     * An ingestion sequence no row in a cleared table can have been assigned.
     *
     * <p>Assumptions: the migration declares the column as an identity at its line 383, and an
     * identity allocates upward from one, so a value this far above the row count any case below
     * inserts cannot have been handed out. It is used only to drive the keyed read's miss path.
     */
    private static final long ABSENT_INGEST_SEQ = 9_999_999_999L;

    /**
     * A transaction identifier repeated across two rows, to prove neither is lost.
     *
     * <p>Assumptions: this is the identifier of record 1 of the seed feed, at one-based positions 1
     * to 16. The 300 seed records happen to carry 300 DISTINCT identifiers, so a feed repeating one
     * has to be constructed here rather than quoted -- which is exactly why the uniqueness of that
     * extract must not be mistaken for a constraint.
     */
    private static final String SEED_TRAN_ID = "0000000000683580";

    /**
     * A second, distinct transaction identifier from the seed feed.
     *
     * <p>Assumptions: this is record 2's identifier at one-based positions 1 to 16, used wherever a
     * case needs two rows that differ in that column.
     */
    private static final String SEED_TRAN_ID_OTHER = "0000000001774260";

    /**
     * The repository under test, the sole data-access port onto the feed table.
     */
    @Autowired
    private DailyTransactionRepository feed;

    /**
     * The persistence context, used to write rows and then detach them before every read.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * The transaction boundary the write helper opens, since no case below is itself transactional.
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Empties the feed table before each case so none of them can read another's rows.
     *
     * <p>Assumptions: the charter requires each test in this package to own its schema state rather
     * than depend on residue, and inherits the discipline from {@code tests/README.md} section 11,
     * whose heading is at line 475 and whose lines 479 to 480 provision a fresh workspace per test
     * and tear it down afterwards. Only the discipline transfers: the container plus the migration is
     * the equivalent here, so nothing below loads an indexed file or shells out to the reference
     * suite's record-copy analogue described at that section's lines 481 to 484.
     *
     * <p>Trade-offs: the table is cleared BEFORE each case rather than after, so a failed run leaves
     * its rows in place to be inspected. What is given up is that the last case's rows outlive the
     * class; that costs nothing, because the container is discarded with it.
     */
    @BeforeEach
    void clearFeed() {
        this.feed.deleteAll();
    }

    /**
     * Confirms a feed row with no processed timestamp is accepted by the column.
     *
     * <p>This is the one assertion nothing else in this suite makes, and the two-table split exists
     * because of it. It pins the ABSENCE of the move at {@code app/cbl/CBTRN02C.cbl} line 438, which
     * is the only site in that program that populates the processed timestamp and which sits inside
     * paragraph {@code 2000-POST-TRANSACTION.} beginning at its line 424. A row on the feed has not
     * reached that paragraph -- the loop at lines 205 to 219 performs it at line 212, and only for a
     * record line 211 found valid -- so the field is unset by construction, and the column has to
     * accept that.
     *
     * <p>Assumptions: the external data contract this depends on is the reference extract itself. All
     * 300 of the 300 records of {@code app/data/ASCII/dailytran.txt} carry 26 blanks at one-based
     * positions 305 to 330, so a column declared otherwise would reject the seed feed in its
     * entirety. {@code db/migration/V1__ledger.sql} leaves the column unconstrained for this table at
     * its line 463 and declares the same column {@code NOT NULL} for the posted master at its line
     * 247, and the sibling {@code TransactionRepositoryIT} of this package already asserts that
     * not-null half, so the contrast is proved across the pair rather than twice over in one file.
     */
    @Test
    void theFeedAcceptsARowCarryingNoProcessedTimestamp() {
        DailyTransaction unstamped =
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);

        this.persistAndDetach(unstamped);

        // WHY : Assumptions: the identity being populated is the evidence the insert reached the
        //       engine and was accepted, not merely that no exception escaped. The migration assigns
        //       the value at line 383, so a null here would mean nothing was written.
        assertThat(unstamped.getIngestSeq()).isNotNull();
        assertThat(this.feed.count()).isEqualTo(1L);
    }

    /**
     * Confirms the absent processed timestamp survives a write and a fresh read as absent.
     *
     * <p>Acceptance at insert and absence on re-read are two different claims, and only the second
     * one rules out a column default. This case pins the same absence of
     * {@code app/cbl/CBTRN02C.cbl} line 438 within paragraph {@code 2000-POST-TRANSACTION.} at its
     * line 424 as the case above, one step further along.
     *
     * <p>Assumptions: the read is a fresh select rather than a cache hit, because the write helper
     * flushes and then clears the persistence context. Without the clear this assertion would be
     * satisfied by the very instance just written, and it would keep passing even if the engine had
     * substituted a value on the way in -- which is precisely the failure being excluded.
     *
     * <p>Assumptions: the originating stamp is asserted present in the same breath. A column that
     * silently discarded every timestamp would satisfy a null check on its own, so the neighbouring
     * populated field is what distinguishes an absent value from a broken mapping. The extract
     * supports the pairing: the same 300 records that are blank at 305 to 330 are populated at 279 to
     * 304.
     */
    @Test
    void theAbsentProcessedTimestampSurvivesTheRoundTripAsAbsent() {
        DailyTransaction unstamped =
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        this.persistAndDetach(unstamped);

        DailyTransaction reread = this.requireRow(unstamped.getIngestSeq());

        assertThat(reread.getProcTs()).isNull();
        assertThat(reread.getOrigTs()).isEqualTo(SEED_ORIG_TS);
    }

    /**
     * Confirms every row a chunk returns may carry an absent processed timestamp.
     *
     * <p>The single-row cases above leave one gap: a scan that dereferenced the field while ordering,
     * capping or assembling its result would fail on a populated feed of unstamped rows even though
     * one such row inserts and reads back cleanly. This case closes it against the whole opening
     * chunk, which is the shape the posting loop at {@code app/cbl/CBTRN02C.cbl} lines 205 to 219
     * consumes the feed in.
     *
     * <p>Assumptions: an unstamped feed is the NORMAL state of this table rather than an edge case,
     * which is what makes a whole-chunk assertion worth its rows. The reference extract is unstamped
     * on 300 of 300 records, so a scan that tolerated one absent value but not a chunk of them would
     * fail on the very first real load.
     */
    @Test
    void everyRowOfAChunkMayCarryAnAbsentProcessedTimestamp() {
        this.persistAndDetach(this.feedRowsOrderedByArrival(FEED_ROWS));

        List<DailyTransaction> chunk = this.feed.findAllByOrderByIngestSeqAsc(Limit.of(CHUNK_ROWS));

        assertThat(chunk).hasSize(CHUNK_ROWS);
        assertThat(chunk).allSatisfy(row -> assertThat(row.getProcTs()).isNull());
    }

    /**
     * Confirms the opening chunk returns rows in arrival order and stops at the requested cap.
     *
     * <p>This pins the entry into the sequential read the posting program performs: the loop at
     * {@code app/cbl/CBTRN02C.cbl} lines 205 to 219 has established no position on its first pass, so
     * the read returns the first record of the dataset. {@code app/jcl/POSTTRAN.jcl} supplies that
     * dataset as the physical sequential feed at its lines 30 and 31, one of the six data definitions
     * its lines 28 to 42 carry for the step its line 23 declares.
     *
     * <p>Assumptions: arrival order and ingestion-sequence order are the same order, which is what
     * makes the ordered query a faithful stand-in for a positionless file read. The identity is
     * allocated monotonically as rows are inserted, so the ascending scan reproduces the front-to-back
     * order the reference read has rather than imposing a sort the reference never performs.
     *
     * <p>Assumptions: the cap is asserted as well as the order, because a query that ordered correctly
     * and ignored its limit would return the whole feed and still satisfy an order-only assertion,
     * while breaking the bounded-chunk property the whole scan depends on.
     */
    @Test
    void theOpeningChunkReturnsRowsInArrivalOrderAndHonoursTheCap() {
        this.persistAndDetach(this.feedRowsOrderedByArrival(FEED_ROWS));

        List<DailyTransaction> chunk = this.feed.findAllByOrderByIngestSeqAsc(Limit.of(CHUNK_ROWS));

        assertThat(chunk).hasSize(CHUNK_ROWS);
        assertThat(this.sequencesOf(chunk)).isSorted();
        assertThat(this.sequencesOf(chunk))
                .containsExactlyElementsOf(this.sequencesOf(this.allRowsInArrivalOrder())
                        .subList(0, CHUNK_ROWS));
    }

    /**
     * Confirms the continuation query excludes the cursor row itself and resumes immediately after it.
     *
     * <p>This pins the next iteration of that same sequential read, resumed from a stated position so
     * that a chunked scan covers the dataset in the order the loop at
     * {@code app/cbl/CBTRN02C.cbl} lines 205 to 219 covers it.
     *
     * <p>Assumptions: the comparison is STRICT, and both directions of getting it wrong are damaging
     * rather than merely untidy. An inclusive comparison would repeat the cursor row as the first row
     * of the next chunk, and the posting loop would post that transaction twice. The exclusion is
     * asserted directly, by checking the cursor's own sequence is absent from the following chunk,
     * rather than inferred from a row count that a duplicate could satisfy.
     *
     * <p>Assumptions: strictness is only SAFE because the cursor column is unique. The migration keys
     * the table on the generated ingestion sequence at its line 500, so excluding a value excludes
     * exactly one row; over a non-unique column it would have excluded every row sharing the boundary
     * value, and those rows would have been lost silently.
     */
    @Test
    void theContinuationChunkExcludesTheCursorRowAndResumesAfterIt() {
        this.persistAndDetach(this.feedRowsOrderedByArrival(FEED_ROWS));
        List<Long> arrival = this.sequencesOf(this.allRowsInArrivalOrder());
        long cursor = arrival.get(CHUNK_ROWS - 1);

        List<DailyTransaction> next =
                this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(cursor, Limit.of(CHUNK_ROWS));

        assertThat(this.sequencesOf(next)).doesNotContain(cursor);
        assertThat(this.sequencesOf(next))
                .containsExactlyElementsOf(arrival.subList(CHUNK_ROWS, CHUNK_ROWS + CHUNK_ROWS));
    }

    /**
     * Confirms the surplus row reports a continuation without becoming part of the chunk.
     *
     * <p>The reference loop discovers the end of the feed by reading and finding nothing, at
     * {@code app/cbl/CBTRN02C.cbl} line 205 where its end-of-file flag gates the iteration. A bounded
     * query cannot discover it that way, so it asks for one row beyond the chunk and reads the
     * presence of that extra row as the answer. This case pins the discipline: availability comes from
     * the surplus row, and the cursor comes from the last row the caller will actually PROCESS.
     *
     * <p>Assumptions: taking the cursor from the surplus row instead would skip a row on every chunk
     * boundary, and it would do so silently -- the scan would terminate normally having processed
     * fewer rows than the feed holds. The case therefore asserts both halves separately: that the
     * query returned one more row than the chunk size, and that the sequence a caller would carry
     * forward is the chunk's last row rather than the probe's.
     *
     * <p>Assumptions: the surplus row is not itself skipped by the next request. Because the cursor is
     * the chunk's last row, the probe row is the FIRST row the continuation returns, which is what
     * makes the surplus a look-ahead rather than a consumed row.
     */
    @Test
    void theSurplusRowRevealsAContinuationWithoutJoiningTheChunk() {
        this.persistAndDetach(this.feedRowsOrderedByArrival(FEED_ROWS));

        List<DailyTransaction> probed =
                this.feed.findAllByOrderByIngestSeqAsc(Limit.of(CHUNK_ROWS + 1));

        assertThat(probed).hasSize(CHUNK_ROWS + 1);
        boolean hasNext = probed.size() > CHUNK_ROWS;
        assertThat(hasNext).isTrue();

        List<DailyTransaction> processed = probed.subList(0, CHUNK_ROWS);
        long cursor = processed.get(processed.size() - 1).getIngestSeq();
        long probeRowSequence = probed.get(CHUNK_ROWS).getIngestSeq();
        assertThat(cursor).isNotEqualTo(probeRowSequence);

        List<DailyTransaction> next =
                this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(cursor, Limit.of(CHUNK_ROWS));
        assertThat(next.get(0).getIngestSeq()).isEqualTo(probeRowSequence);
    }

    /**
     * Confirms a bounded traversal reaches every row exactly once, with no gap and no repeat.
     *
     * <p>This is the property that makes bounded chunking a faithful replacement for the reference
     * read. The loop at {@code app/cbl/CBTRN02C.cbl} lines 205 to 219 consumes EVERY record of the
     * feed -- line 206 counts each one as it arrives, and no predicate anywhere in the loop filters
     * the set -- so a chunked scan that skipped or repeated a row would change which transactions a
     * run posts.
     *
     * <p>Assumptions: exactly-once is asserted as a whole rather than chunk by chunk, because the two
     * defects it excludes are invisible per chunk. A skip and a repeat each leave every individual
     * chunk well formed, and only the assembled traversal compared against the full table reveals
     * either. The case therefore collects every sequence the traversal yields and asserts it equals
     * the table's own arrival order, element for element.
     *
     * <p>Alternatives Considered: an offset-paged traversal, or the chunk-oriented batch reader built
     * on the same mechanism, which would express this loop in fewer lines. Rejected because counting
     * rows from the start of an ordered set makes the position depend on what precedes it, so an
     * insert landing behind the cursor shifts every later row and the traversal skips and repeats.
     * A feed is loaded by one process while a job walks it, so it is exposed to exactly that. The key
     * cursor asserted here keeps its place in the ordering no matter what is inserted around it.
     *
     * <p>Assumptions: the loop is bounded by the feed running out rather than by a count the case
     * already knew, so an off-by-one in the continuation would hang or truncate rather than pass. The
     * iteration cap is derived from the row count purely as a guard against a non-terminating scan.
     */
    @Test
    void aBoundedTraversalReachesEveryRowExactlyOnce() {
        this.persistAndDetach(this.feedRowsOrderedByArrival(FEED_ROWS));
        List<Long> expected = this.sequencesOf(this.allRowsInArrivalOrder());

        List<Long> visited = new ArrayList<>();
        Long cursor = null;
        int guard = FEED_ROWS + CHUNK_ROWS + 1;
        while (guard-- > 0) {
            List<DailyTransaction> probed = cursor == null
                    ? this.feed.findAllByOrderByIngestSeqAsc(Limit.of(CHUNK_ROWS + 1))
                    : this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                            cursor, Limit.of(CHUNK_ROWS + 1));
            if (probed.isEmpty()) {
                break;
            }
            // WHY : Assumptions: the surplus row is dropped before the rows are consumed and the
            //       cursor is taken from what remains, which is the only arrangement that neither
            //       processes a row twice nor steps over one. Keeping the probe row would process it
            //       here and again at the head of the next chunk.
            List<DailyTransaction> processed =
                    probed.size() > CHUNK_ROWS ? probed.subList(0, CHUNK_ROWS) : probed;
            processed.forEach(row -> visited.add(row.getIngestSeq()));
            if (probed.size() <= CHUNK_ROWS) {
                break;
            }
            cursor = processed.get(processed.size() - 1).getIngestSeq();
        }

        assertThat(visited).containsExactlyElementsOf(expected);
        assertThat(visited).doesNotHaveDuplicates();
    }

    /**
     * Confirms a feed repeating one transaction identifier yields two rows, each addressable.
     *
     * <p>This is the second of the two properties the package charter assigns this class, and it pins
     * the file declaration rather than a paragraph: {@code app/cbl/CBTRN02C.cbl} selects the feed at
     * its lines 29 to 31 as a sequential organisation with sequential access and NO record key at
     * all, and {@code app/jcl/POSTTRAN.jcl} lines 30 and 31 supply it as the physical sequential
     * dataset. A file with no key asserts no uniqueness, so two records carrying one identifier are
     * two legitimate records and the reference loop at lines 205 to 219 processes both, counting each
     * at line 206.
     *
     * <p>Assumptions: the baseline feed asserts no uniqueness over any value it carries; the Java
     * keys the table on a GENERATED ingestion sequence instead, at
     * {@code db/migration/V1__ledger.sql} line 500 over the identity its line 383 declares, and
     * leaves {@code transaction_id} unconstrained at its line 390. The divergence is documented rather
     * than silent, and it runs toward the baseline rather than away from it: keying on the identifier
     * would have made the second occurrence unwritable, which is a behaviour the sequential feed does
     * not have.
     *
     * <p>Assumptions: uniqueness of the identifier in the extract in hand is an EMPIRICAL property and
     * not a constraint. The 300 records of {@code app/data/ASCII/dailytran.txt} do carry 300 distinct
     * identifiers, which is why a repeating feed has to be constructed here rather than quoted -- and
     * why that measurement must not be mistaken for a guarantee about the next extract.
     *
     * <p>Assumptions: individual addressability is asserted through the keyed read on the sequence and
     * through the two rows comparing UNEQUAL to one another. Identity-based equality over the sequence
     * is what keeps both occurrences reachable; had equality compared the identifier, the two rows
     * would have collapsed into one wherever they met a set.
     */
    @Test
    void aRepeatedTransactionIdentifierYieldsTwoIndividuallyAddressableRows() {
        DailyTransaction first =
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        DailyTransaction second = this.feedRow(
                SEED_TRAN_ID, SEED_CARD_LEADING_ZERO, SEED_AMOUNT_NEGATIVE, SEED_ORIG_TS, null);

        this.persistAndDetach(first, second);

        assertThat(first.getIngestSeq()).isNotEqualTo(second.getIngestSeq());

        List<DailyTransaction> scanned =
                this.feed.findAllByOrderByIngestSeqAsc(Limit.of(FEED_ROWS));
        assertThat(scanned).hasSize(2);
        assertThat(scanned).allSatisfy(row -> assertThat(row.getTranId()).isEqualTo(SEED_TRAN_ID));

        DailyTransaction rereadFirst = this.requireRow(first.getIngestSeq());
        DailyTransaction rereadSecond = this.requireRow(second.getIngestSeq());
        assertThat(rereadFirst.getCardNum()).isEqualTo(SEED_CARD);
        assertThat(rereadSecond.getCardNum()).isEqualTo(SEED_CARD_LEADING_ZERO);
        assertThat(rereadFirst).isNotEqualTo(rereadSecond);
    }

    /**
     * Confirms the keyed read finds a row by its generated ingestion sequence.
     *
     * <p>The reference feed offers no keyed read to transcribe -- its file declaration at
     * {@code app/cbl/CBTRN02C.cbl} lines 29 to 31 names no record key -- so this path is the
     * relational affordance that makes a single row addressable at all, and it is what the traversal
     * above relies on to prove a repeated identifier did not collapse.
     *
     * <p>Assumptions: the argument type is {@code Long} because the identifier of this repository is
     * the identity column and not the sixteen-character transaction identifier. That is the
     * distinction the sibling table inverts: its own keyed read takes the character identifier,
     * because there the migration makes it the primary key.
     */
    @Test
    void theKeyedReadFindsARowByItsGeneratedIngestionSequence() {
        DailyTransaction row =
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        this.persistAndDetach(row);

        Optional<DailyTransaction> found = this.feed.findById(row.getIngestSeq());

        assertThat(found).isPresent();
        assertThat(found.get().getTranId()).isEqualTo(SEED_TRAN_ID);
    }

    /**
     * Confirms the keyed read reports an unallocated ingestion sequence as an empty result.
     *
     * <p>Pairing the miss with the hit above is what shows the hit was a lookup rather than a scan
     * returning whatever it found. The reference program has no equivalent to assert against, its feed
     * being keyless at {@code app/cbl/CBTRN02C.cbl} lines 29 to 31, so the contract asserted here is
     * the relational one the migration creates at its line 500.
     *
     * <p>Assumptions: an absent key is an empty result and never an exception, so a caller distinguishes
     * "no such row" from a failure without catching anything. The value used cannot collide with a real
     * row because an identity allocates upward from one and every case here clears the table first.
     */
    @Test
    void theKeyedReadReportsAnUnallocatedSequenceAsAnEmptyResult() {
        this.persistAndDetach(
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null));

        assertThat(this.feed.findById(ABSENT_INGEST_SEQ)).isEmpty();
    }

    /**
     * Confirms the card number round-trips byte-identically, leading zero intact.
     *
     * <p>This pins the field {@code app/cpy/CVTRA06Y.cpy} declares at its line 15 as
     * {@code PIC X(16)}, occupying one-based bytes 263 to 278 of the 350-byte record whose length its
     * line 2 states. The value asserted is the literal content of those bytes in record 2 of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * <p>Assumptions: the column is a fixed-width CHARACTER type and the field a digit string, not a
     * number, and the rejected alternatives each fail in a specific way. A numeric column, or a
     * {@code Long} member, would drop the leading zero and return fifteen digits where the contract
     * has sixteen. A 64-bit binary floating-point type would lose low-order digits outright on a
     * sixteen-digit value. Both failures are silent at the point they occur and surface far away, as a
     * card that cannot be matched.
     *
     * <p>Assumptions: the length is asserted alongside the value because the column is declared-width
     * and the engine blank-pads a short value on the way in. A value that came back padded would
     * compare unequal to the unpadded literal, so a passing equality here is also evidence that the
     * stored width is exactly the declared one.
     */
    @Test
    void theCardNumberRoundTripsWithItsLeadingZeroIntact() {
        DailyTransaction row = this.feedRow(
                SEED_TRAN_ID, SEED_CARD_LEADING_ZERO, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        this.persistAndDetach(row);

        DailyTransaction reread = this.requireRow(row.getIngestSeq());

        assertThat(reread.getCardNum()).isEqualTo(SEED_CARD_LEADING_ZERO);
        assertThat(reread.getCardNum()).startsWith("0");
        assertThat(reread.getCardNum()).hasSize(16);
    }

    /**
     * Confirms the amount column preserves the sign and both decimal places, in both polarities.
     *
     * <p>This pins the field {@code app/cpy/CVTRA06Y.cpy} declares at its line 10 as
     * {@code PIC S9(09)V99} -- nine integer digits, two decimal places and a sign -- at one-based
     * bytes 133 to 143, which {@code db/migration/V1__ledger.sql} maps to an exact decimal column of
     * precision eleven and scale two. It is the value the posting paragraph beginning at
     * {@code app/cbl/CBTRN02C.cbl} line 424 carries into the posted record unchanged.
     *
     * <p>Assumptions: exactness is the property under test and binary floating point is the specific
     * alternative excluded. A 64-bit floating-point column returns plausible values that are wrong in
     * the last place; the error is invisible in any one row and accumulates across a run, which is why
     * no floating-point type appears anywhere in this file, its fixtures or its assertions. The
     * shared kernel's money type is used to re-read the values rather than a local conversion, so the
     * scale and the rendering asserted here are the ones the whole service uses.
     *
     * <p>Assumptions: the negative polarity is not optional. The reference feed's 300 amounts split
     * 250 positive to 50 negative, and a sign carried as an overpunch over the low-order digit is
     * exactly what a mis-declared column keeps the magnitude of and loses the sign of. Both values are
     * the decoded known-answer vectors of {@code tests/helpers/record_codec.py}, at its line 82 for
     * the positive and its line 84 for the negative.
     *
     * <p>Assumptions: the scale is asserted alongside the value because two decimals are part of the
     * contract. A value that came back numerically equal but scaled differently would render with the
     * wrong number of digits everywhere it reached a caller.
     */
    @Test
    void theAmountPreservesSignAndScaleInBothPolarities() {
        DailyTransaction credit =
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        DailyTransaction debit = this.feedRow(
                SEED_TRAN_ID_OTHER, SEED_CARD, SEED_AMOUNT_NEGATIVE, SEED_ORIG_TS, null);
        this.persistAndDetach(credit, debit);

        BigDecimal storedCredit = this.requireRow(credit.getIngestSeq()).getTranAmt();
        BigDecimal storedDebit = this.requireRow(debit.getIngestSeq()).getTranAmt();

        assertThat(storedCredit.scale()).isEqualTo(Money.SCALE);
        assertThat(Money.of(storedCredit).toPlainString()).isEqualTo("504.77");
        assertThat(Money.of(storedCredit).isPositive()).isTrue();

        assertThat(storedDebit.scale()).isEqualTo(Money.SCALE);
        assertThat(Money.of(storedDebit).toPlainString()).isEqualTo("-504.71");
        assertThat(Money.of(storedDebit).isNegative()).isTrue();
    }

    /**
     * Confirms the originating timestamp round-trips at the 26-character microsecond form.
     *
     * <p>This pins the field {@code app/cpy/CVTRA06Y.cpy} declares at its line 16 as
     * {@code PIC X(26)}, at one-based bytes 279 to 304, which the migration maps to a timestamp column
     * of six fractional digits. The rendered form is the shared kernel's, whose width constant states
     * the same 26 characters, so a column of lower precision loses a digit the assertion names. It is
     * the field {@code app/cbl/CBTRN02C.cbl} copies straight across from the feed rather than minting,
     * which is what distinguishes it from the processed stamp its line 438 sets.
     *
     * <p>Assumptions: both a seed value and a microsecond-bearing value are asserted, because the
     * extract cannot cover this alone. All 300 seed stamps end in six zero digits -- record 1's reads
     * exactly as the literal quoted in this class -- so a column that truncated the fraction would
     * round-trip every seed value unchanged and the defect would surface only in production.
     *
     * <p>Assumptions: no ambient clock is read on either path. The charter forbids it for this whole
     * package: a case that compares a stored value against the current time passes for a reason
     * unrelated to the code under test and fails only when two reads straddle a boundary. Both values
     * here are literals, and the rendering and the parse are exercised in both directions so that
     * neither is asserted only against itself.
     */
    @Test
    void theOriginatingTimestampRoundTripsAtMicrosecondPrecision() {
        DailyTransaction seedStamped =
                this.feedRow(SEED_TRAN_ID, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        DailyTransaction microsecondStamped = this.feedRow(
                SEED_TRAN_ID_OTHER, SEED_CARD, SEED_AMOUNT_POSITIVE, MICROSECOND_ORIG_TS, null);
        this.persistAndDetach(seedStamped, microsecondStamped);

        LocalDateTime storedSeed = this.requireRow(seedStamped.getIngestSeq()).getOrigTs();
        assertThat(TimestampFormatter.format(storedSeed)).isEqualTo(SEED_ORIG_TS_TEXT);
        assertThat(storedSeed).isEqualTo(TimestampFormatter.parse(SEED_ORIG_TS_TEXT));

        LocalDateTime storedMicroseconds =
                this.requireRow(microsecondStamped.getIngestSeq()).getOrigTs();
        assertThat(TimestampFormatter.format(storedMicroseconds))
                .isEqualTo(MICROSECOND_ORIG_TS_TEXT);
        assertThat(storedMicroseconds).isEqualTo(MICROSECOND_ORIG_TS);
        assertThat(TimestampFormatter.format(storedMicroseconds))
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
    }

    /**
     * Writes rows in the order given and then detaches them, so the next read is a fresh select.
     *
     * <p>Assumptions: the flush and the clear are not interchangeable, and the order matters in one
     * direction only. Clearing BEFORE the flush would discard the pending inserts unwritten, so the
     * rows a following assertion reasons about would never exist and that assertion would fail for a
     * reason unrelated to the access path it names.
     *
     * <p>Assumptions: the rows are persisted in argument order and the identity is assigned in that
     * order, which is what lets the ordered scans above treat argument order as arrival order. A
     * database-assigned identity is fetched at insert, so each instance carries its own sequence as
     * soon as this method returns even though the instances are detached.
     *
     * <p>Trade-offs: a single transaction wraps the whole batch rather than one per row. What is given
     * up is the ability to observe a partially written feed; what is bought is that a case which needs
     * five rows has five rows or none, so a failure cannot be a half-loaded table.
     *
     * @param rows the transient feed rows to write, of type {@code DailyTransaction...}, in the
     *     arrival order the ordered scans should observe; must contain no {@code null} element
     */
    private void persistAndDetach(DailyTransaction... rows) {
        this.transactionTemplate.executeWithoutResult(status -> {
            for (DailyTransaction row : rows) {
                this.entityManager.persist(row);
            }
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Builds one transient feed row, leaving every field not named by a parameter at a seed value.
     *
     * <p>Assumptions: the five parameters are the fields whose values discriminate between the cases
     * above -- the identifier a repeated feed repeats, the card number carrying the leading zero, the
     * money contract, the originating stamp and the processed stamp whose absence is this class's
     * subject. Everything else is taken verbatim from record 1 of
     * {@code app/data/ASCII/dailytran.txt}, so no value here is minted and none identifies a real
     * person or account.
     *
     * <p>Assumptions: the four fixed-width character fields are written at their declared widths,
     * blank-padded where the value is shorter, because {@code app/cpy/CVTRA06Y.cpy} declares them as
     * fixed-width fields at its lines 6, 7, 8 and 14 and the migration maps them to declared-width
     * columns. A shorter value would be padded by the engine on the way in and would then compare
     * unequal to the unpadded value on the way out, which surfaces as a row that cannot be found
     * rather than as anything about a width.
     *
     * <p>Assumptions: this builder does NOT default the processed timestamp. It is a required
     * parameter precisely so that a case cannot silently acquire a stamped row while claiming to test
     * an unstamped one, which would leave this class proving nothing.
     *
     * @param tranId the sixteen-character transaction identifier, of type {@code String}, which this
     *     table deliberately does not constrain to be unique
     * @param cardNum the sixteen-character card number, of type {@code String}, written at its
     *     declared width
     * @param amount the transaction amount, of type {@code BigDecimal}, at scale two and never a
     *     floating-point value
     * @param origTs the originating timestamp, of type {@code LocalDateTime}, which the feed supplies
     *     and which is populated on all 300 reference records
     * @param procTs the processed timestamp, of type {@code LocalDateTime}, or {@code null} to build
     *     the unstamped feed row the reference extract actually carries
     * @return a transient entity ready to write, never {@code null} and never carrying an assigned
     *     ingestion sequence, since the engine allocates that on insert
     */
    private DailyTransaction feedRow(String tranId, String cardNum, BigDecimal amount,
            LocalDateTime origTs, LocalDateTime procTs) {
        return new DailyTransaction(tranId, "01", "0001", "POS TERM  ",
                "Purchase at Abshire-Lowe", amount, 800000000L,
                "Abshire-Lowe", "North Enoshaven", "72112     ", cardNum,
                origTs, procTs);
    }

    /**
     * Builds a run of distinct unstamped feed rows in the order they should arrive.
     *
     * <p>Assumptions: every row is unstamped, because that is the state the reference extract is in on
     * 300 of its 300 records, so an ordered-scan case running over stamped rows would be walking a
     * feed shape that does not occur.
     *
     * <p>Assumptions: the identifiers are made distinct by index so that a scan assertion can attribute
     * a row to a position, while the repeated-identifier case constructs its own collision explicitly.
     * The generated identifiers are written at the declared sixteen-character width so they compare
     * equal on re-read.
     *
     * @param count the number of rows to build, of type {@code int}, which must be positive
     * @return the rows in arrival order as a {@code DailyTransaction[]}, never {@code null}
     */
    private DailyTransaction[] feedRowsOrderedByArrival(int count) {
        DailyTransaction[] rows = new DailyTransaction[count];
        for (int index = 0; index < count; index++) {
            String tranId = String.format("%016d", index + 1);
            rows[index] =
                    this.feedRow(tranId, SEED_CARD, SEED_AMOUNT_POSITIVE, SEED_ORIG_TS, null);
        }
        return rows;
    }

    /**
     * Reads the whole table back in ingestion-sequence order, as the expected order to compare against.
     *
     * <p>Assumptions: the expectation is read from the ENGINE rather than assembled from the instances
     * that were written, so a traversal assertion compares one query's answer against another's rather
     * than against the test's own bookkeeping. A cap of the row count plus one is passed so that a row
     * beyond the expected set would widen the result and fail the comparison rather than be hidden by
     * the limit.
     *
     * @return every row currently in the table as a {@code List<DailyTransaction>}, ascending by
     *     ingestion sequence, never {@code null}
     */
    private List<DailyTransaction> allRowsInArrivalOrder() {
        return this.feed.findAllByOrderByIngestSeqAsc(Limit.of(FEED_ROWS + 1));
    }

    /**
     * Projects rows onto their ingestion sequences, which is what the ordering assertions compare.
     *
     * <p>Assumptions: the sequences are compared rather than the entities because entity equality here
     * is identity-based on that same sequence, so comparing entities would assert the projection
     * indirectly while reading as though it asserted the rows. Comparing the values directly makes an
     * ordering failure name the positions that differ.
     *
     * @param rows the rows to project, of type {@code List<DailyTransaction>}, each already persisted
     *     and therefore carrying an assigned sequence
     * @return the ingestion sequences in the order the rows were given, as a {@code List<Long>}, never
     *     {@code null}
     */
    private List<Long> sequencesOf(List<DailyTransaction> rows) {
        return rows.stream().map(DailyTransaction::getIngestSeq).toList();
    }

    /**
     * Reads one row back by its ingestion sequence, failing the case if it is absent.
     *
     * <p>Assumptions: an absent row is a failure of the case rather than a condition to branch on,
     * because every caller has just written the row it asks for. Unwrapping here keeps each assertion
     * about the column it names instead of about presence, and a missing row surfaces as this
     * assertion rather than as a later null dereference whose message names the wrong contract.
     *
     * @param ingestSeq the ingestion sequence to read, of type {@code Long}, as assigned by the engine
     *     when the row was inserted
     * @return the row that sequence identifies, of type {@code DailyTransaction}, never {@code null}
     */
    private DailyTransaction requireRow(Long ingestSeq) {
        Optional<DailyTransaction> found = this.feed.findById(ingestSeq);
        assertThat(found).isPresent();
        return found.get();
    }

    /**
     * The minimal Spring Boot configuration this suite runs against.
     *
     * <p>Assumptions: this configuration declares no component scan, and that omission is the whole
     * point of it. The module's own application class scans this bounded context and so registers an
     * explicit pool factory that builds from datasource PROPERTIES; those properties are absent here
     * because the container's coordinates arrive as a connection-details bean, and the context would
     * then fail on a URL that does not begin with the JDBC scheme. Naming the two persistence packages
     * explicitly instead leaves the framework's own auto-configuration to build the pool from that
     * bean.
     *
     * <p>Assumptions: nothing beyond persistence is enabled -- no web layer, no security filter chain,
     * no API documentation -- because the suite runs with no servlet environment and every additional
     * auto-configured concern is one more way for a persistence assertion to fail for an unrelated
     * reason.
     *
     * <p>Trade-offs: declaring it nested rather than as a file of its own keeps the package charter's
     * closed file set true, at the cost of this configuration not being reusable by the sibling
     * integration tests. That cost is accepted deliberately, because the same charter rejects a shared
     * holder for exactly the reason it would apply here: two tests reaching one schema through two
     * differently configured contexts could disagree about what they reached.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.transaction.domain")
    @EnableJpaRepositories("com.carddemo.transaction.repository")
    static class FeedPersistenceTestApplication {
    }
}
