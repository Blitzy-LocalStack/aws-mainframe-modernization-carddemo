package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.domain.TransactionReject;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Holds the reject stream's 430-byte composition against a real engine, as a whole-group move of the
 * daily-transaction record exactly as it was read.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: {@code ledger.transaction_rejects} is the relational form of the one dataset the posting
 * job writes that a downstream consumer parses POSITIONALLY, and this class is where the three columns
 * that decompose it are executed against PostgreSQL rather than read for correctness. The subtlety it
 * exists for is contained in three words of the reference program: the stored image is the daily record
 * <b>as it was read</b>. {@code app/cbl/CBTRN02C.cbl:447} is {@code MOVE DALYTRAN-RECORD TO
 * REJECT-TRAN-DATA}, a whole-group move of all 350 bytes in one statement, and {@code :448} then moves
 * the trailer over the remaining eighty. Nothing between those two statements recomposes a field,
 * reformats a value or stamps a timestamp.</p>
 *
 * <p>Assumptions: the 430-byte width is corroborated three independent ways, which is what lets it be
 * asserted as a contract rather than as one reading of one file. The file description at
 * {@code app/cbl/CBTRN02C.cbl:82-84} composes the record as {@code FD-REJECT-RECORD PIC X(350)} followed
 * by {@code FD-VALIDATION-TRAILER PIC X(80)}. The working storage at {@code :176-182} declares the same
 * two areas and then decomposes the trailer into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} plus a
 * 76-character description, so four plus seventy-six agrees with eighty. The dataset allocation at
 * {@code app/jcl/POSTTRAN.jcl:36} states the total outright as
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}. A reject stream written at the wrong width is a file that
 * still opens and still reads, which is why one source would not be enough.</p>
 *
 * <h2>The consequence this class proves, and the failure it is shaped to catch</h2>
 *
 * <p>Assumptions: because {@code app/cpy/CVTRA06Y.cpy} declares its own tail as
 * {@code DALYTRAN-PROC-TS PIC X(26)} followed by {@code FILLER PIC X(20)}, a group move of that record
 * carries the DAILY record's processing-timestamp span and the DAILY record's filler span into the
 * stored image. It does not carry the posted transaction's {@code TRAN-PROC-TS}, and it does not carry a
 * freshly minted stamp: the only site in the program that mints one is {@code :437-438}, inside
 * {@code 2000-POST-TRANSACTION}, which {@code :211-216} reaches on the OPPOSITE arm of the decision
 * from the reject writer. A record that was rejected was never posted, so its processing-timestamp span
 * is blank.</p>
 *
 * <p>Assumptions: the committed expectation files agree, and they were measured rather than assumed
 * before these assertions were written. Each of the four records in
 * {@code tests/golden/posting/reject_100_card_missing/dalyrejs.expected} and its three siblings is
 * exactly 430 characters; bytes 305 to 330 are twenty-six spaces; bytes 331 to 350 are twenty spaces;
 * bytes 351 to 354 carry the reason zero-padded to four digits; and bytes 355 to 430 carry the
 * description right-padded to seventy-six. Two further measurements are worth stating because they are
 * what make the span assertions below discriminating rather than decorative: the ORIGINATING-timestamp
 * span at bytes 279 to 304 IS populated in every one of the four, and the first 350 characters of each
 * golden record are byte-identical to the daily-transaction fixture record that provoked it. The
 * populated span beside the blank one is the observable signature of a group move.</p>
 *
 * <p>Trade-offs: this tier asserts ROWS and not BYTES, and the line is drawn deliberately rather than
 * for convenience. What is asserted here is that the column stores the daily record's own image with the
 * span content that image had; the rendering of a row back into 430 fixed-width characters belongs to
 * {@code com.carddemo.batch.mapper.TransactionRejectRecordMapper}, and the comparison of a whole emitted
 * stream against those goldens belongs to the job tier's {@code PostTransactionsJobTest}. The accepted
 * cost is that no case here reads or writes a byte array, so a defect in the fixed-width encoder is
 * invisible from this file; the compensating benefit is that these cases cannot pass or fail for a
 * reason belonging to the encoder, which is what keeps a failure here diagnosable as a persistence
 * defect.</p>
 *
 * <h2>The line this class does not cross</h2>
 *
 * <p>Assumptions: the reject write is on the REJECTION path, so it is NOT one of the three writes in the
 * posting unit of work, and a reader may otherwise assume the row participates in that transaction.
 * {@code app/cbl/CBTRN02C.cbl:211-216} performs {@code 2000-POST-TRANSACTION} when the validation reason
 * is zero and otherwise increments the reject tally and performs {@code 2500-WRITE-REJECT-REC}. The two
 * arms are alternatives: a record is posted or rejected, never both. {@code PostingUnitOfWorkIT} is
 * therefore the sole owner of the three-write atomicity proof, and no case here asserts anything about
 * it.</p>
 *
 * <p>Alternatives Considered: restating the reject rules the rule tier already owns, so that a reader of
 * this file would not have to open another. Rejected, because a rule asserted in two files is a rule
 * that can be relaxed in one while the other still reads as intact.
 * {@code com.carddemo.batch.service.PostingValidationServiceTest} owns and is not restated here:
 * reject-reason precedence, including that 100 short-circuits the account lookup and that 103 overwrites
 * 102 when both conditions hold; the verbatim literals as a validation OUTCOME; the composition of the
 * eighty-character trailer at the service layer; that 109 can never reach this stream; both inclusive
 * boundaries; and the projected balance being taken from the cycle accumulators.
 * {@code com.carddemo.batch.job.PostTransactionsJobTest} owns and is not restated here: the warn-tier
 * return code, the two verbatim counter lines, the inversion of the baseline step gate into an
 * orchestrator predicate, the declared transaction boundary, and job-level parity against the nine
 * {@code tests/golden/posting} trees including the reject stream itself. No case here asserts a return
 * code at all, this being a repository rather than a process. Nothing here asserts anything about
 * generation datasets either: {@code app/jcl/DALYREJS.jcl:24-26} defines the five-generation scratch
 * group, and generation resolution belongs to {@code DatasetGenerationService} and its own test.</p>
 *
 * <h2>How the citations above are to be read</h2>
 *
 * <p>Assumptions: every {@code app/} and {@code tests/} path cited in this file is reference material,
 * read for provenance and never modified. The reference programs stay byte-identical and keep running,
 * which is exactly what lets them serve as the oracle this module is measured against, and the oracle
 * suite's own aggregate warn-level return code is its documented green state rather than a regression.
 * Line numbers refer to the sources as committed. Two proofs below have NO baseline citation and say so
 * at their own site, because they are target-side properties the reference cannot supply a vector for:
 * the surrogate identity key and the admission of duplicate rejects.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// Refactoring Rationale: the Parameter Store config-data location is disabled for this context, because
//     application.yml's `optional:aws-parameterstore:` location builds an SSM client while configuration
//     is still loading, and a build host with no AWS_REGION then aborts the context on the unresolved
//     placeholder rather than on anything this class asserts. BatchRunRepositoryIT records the full
//     measurement, why disabling the LOAD suffices when the resolver ignores the flag, and the two
//     rejected alternatives; it is cited from here rather than repeated, so the two cannot drift.
@SpringBootTest(
        classes = TransactionRejectRepositoryIT.RejectStreamPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class TransactionRejectRepositoryIT {

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
     * The classpath-relative path of the harness that establishes {@code ledger.transaction_rejects}.
     *
     * <p>Assumptions: the table this class writes comes from that script and NOT from Flyway. The
     * ledger schema belongs to transaction-service, whose migration is unreachable from this module's
     * test classpath, so the harness mirrors it; the script is deliberately not a migration, carrying no
     * version prefix and sitting outside {@code db/migration}, because Flyway applying it would place
     * another context's table under this module's migration history.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The container this class owns, seeded with the harness before Flyway opens a connection.
     *
     * <p>Alternatives Considered: a container held in a shared abstract base class, so that the
     * declaration and its property registration were written once for the whole package. Rejected by
     * the package charter's second ruling and repeated here because the hazard is concrete for this
     * class: a shared container makes rows one class inserts rows another reads, and every case below
     * counts the rows in one table, so a sibling's reject row would be indistinguishable from this
     * class's own and the failure would name whichever class ran second.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The width of the daily-transaction image the reject row carries, from {@code CVTRA06Y}. */
    private static final int IMAGE_WIDTH = 350;

    /** The declared width of the reason description, from {@code CBTRN02C} L182. */
    private static final int REASON_DESC_WIDTH = 76;

    /** Zero-based start of the card-number span, {@code DALYTRAN-CARD-NUM} at 1-based 263. */
    private static final int CARD_NUM_START = 262;

    /** Zero-based end of the card-number span, the sixteen characters ending at 1-based 278. */
    private static final int CARD_NUM_END = 278;

    /** Zero-based start of the originating-timestamp span, {@code DALYTRAN-ORIG-TS} at 1-based 279. */
    private static final int ORIG_TS_START = 278;

    /** Zero-based end of the originating-timestamp span, ending at 1-based 304. */
    private static final int ORIG_TS_END = 304;

    /** Zero-based start of the processing-timestamp span, {@code DALYTRAN-PROC-TS} at 1-based 305. */
    private static final int PROC_TS_START = 304;

    /** Zero-based end of the processing-timestamp span, ending at 1-based 330. */
    private static final int PROC_TS_END = 330;

    /** The declared width of both timestamp spans, {@code PIC X(26)}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The declared width of the trailing filler span, {@code FILLER PIC X(20)} at 1-based 331. */
    private static final int FILLER_WIDTH = 20;

    /** How many characters of the card number a diagnostic may reveal, per the masking contract. */
    private static final int UNMASKED_CARD_DIGITS = 4;

    /**
     * The originating timestamp every fixture image below carries in its {@code ORIG-TS} span.
     *
     * <p>Assumptions: this is the value the committed reject expectations carry at bytes 279 to 304,
     * and it is a literal rather than a clock read, so a case cannot pass for a reason unrelated to
     * what it asserts. It is twenty-six characters, matching the span's declared width exactly.</p>
     */
    private static final String ORIGINATED_AT = "2022-06-10 19:27:53.000000";

    /**
     * A stamp shaped exactly like a processing timestamp, used only to prove an assertion has teeth.
     *
     * <p>Assumptions: this is the twenty-six-character form {@code TimestampFormatter} emits and the
     * form {@code app/cbl/CBTRN02C.cbl:437-438} moves into a POSTED transaction. It appears in this
     * file solely as the value a correct implementation must NOT place in a reject image.</p>
     */
    private static final String PROCESSED_AT = "2022-07-18 04:15:22.123456";

    /** The card number every fixture image carries, a constructed value naming no real instrument. */
    private static final String FIXTURE_CARD_NUM = "4000000000009999";

    /** The reject stream, injected as the production repository interface and not as a wider base. */
    @Autowired
    private TransactionRejectRepository rejects;

    /** The persistence context, used to detach between a write and the read that verifies it. */
    @Autowired
    private EntityManager entityManager;

    /** The boundary every write below is issued inside, because the pool does not auto-commit. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, this interface declaring no finder that could read a row back. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // Assumptions: the Flyway pair is registered beside the datasource pair because application.yml
    //     binds spring.flyway.user and spring.flyway.password to placeholders with no fallback, and Boot
    //     consults those keys precisely when no connection-details bean supplies them, which is this
    //     module's case. The package charter records the full reasoning.
    // Assumptions: every value here is supplied by the container as it starts and none is written as a
    //     literal. A URL authored before the container assigned its host port would either address
    //     nothing or -- the worse outcome, because it passes -- address whatever database happened to be
    //     listening, so no endpoint or credential appears anywhere in this file.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the reject stream before each case and opens a JDBC handle over the pool.
     *
     * <p>Assumptions: emptying is necessary rather than tidy, because two cases below count rows and
     * one asserts that two rows carry DISTINCT ordinals. A residual row from a previous case would make
     * a count assertion pass or fail on history rather than on what the case wrote.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void emptyTheRejectStream(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        // Assumptions: the delete is issued through JDBC and not through the repository, which offers no
        //     removal member at all for the reasons the append-only case below proves. A test's own
        //     arrangement reaching past the interface is not a contradiction of that contract: the
        //     CONTRACT is what application code may do, and this statement is harness teardown.
        this.transactionTemplate.executeWithoutResult(
                status -> this.jdbc.update("DELETE FROM ledger.transaction_rejects"));
    }

    /**
     * Confirms the two declared widths hold: the image at exactly 350 and the description intact.
     *
     * <p>Assumptions: the widths are the reference program's, corroborated three ways -- the file
     * description at {@code app/cbl/CBTRN02C.cbl:82-84}, the working storage at {@code :176-182}, and the
     * {@code LRECL=430} at {@code app/jcl/POSTTRAN.jcl:36}. The image is asserted at an EXACT width
     * rather than a maximum because a fixed-width field's padding is content: the reference moves a
     * fixed 350-byte area into another fixed 350-byte area, so a legitimate value is never short.</p>
     *
     * <p>Assumptions: a blank-tailed image is the discriminating fixture here, and an all-filled one
     * would not be. The faithful image ends in forty-six blanks -- the twenty-six of the
     * processing-timestamp span and the twenty of the filler -- so this case is what shows that
     * {@code CHAR(350)} returns those blanks rather than trimming them. That behaviour was measured
     * against this engine before the assertion was written: the column blank-pads on storage, the driver
     * returns the padded value, and SQL {@code length()} over the same value answers the TRIMMED figure
     * while {@code octet_length} answers 350. This case therefore measures the returned string in Java.</p>
     *
     * <p>Assumptions: the description is asserted for exact equality rather than for width, because it
     * is a {@code VARCHAR(76)} and the reference's four texts are 25, 24, 21 and 42 characters. Padding
     * it out to seventy-six is the emitter's step and belongs to the mapper, not to this column.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the image stores at exactly 350 characters with its trailing blanks and the description intact")
    void theDeclaredWidthsHold() {
        String image = rejectedRecordImage("0000000000000001");

        long ordinal = appendReject(
                image,
                TransactionReject.REASON_CODE_OVERLIMIT,
                TransactionReject.REASON_DESC_OVERLIMIT);

        List<StoredReject> stored = storedRejects();
        assertThat(stored).hasSize(1);
        StoredReject row = stored.getFirst();

        // Assumptions: the width is asserted on an int rather than on the string, and the whole-image
        //     comparison below is asserted on a boolean, both for the same reason: AssertJ prints the
        //     ACTUAL value when hasSize or isEqualTo fails on a String, which would put all 350
        //     characters -- including the card number at 1-based 263 to 278 -- into the build log. An int
        //     comparison prints two numbers and a boolean comparison prints true and false.
        assertThat(row.rawRecord().length())
                .as("the image is stored at its full declared width, the trailing blanks included,"
                        + " because CHAR(350) pads rather than trims (card %s)", maskedCardNumber(image))
                .isEqualTo(IMAGE_WIDTH);
        assertThat(row.rawRecord().equals(image))
                .as("the stored image is character-for-character the image that was appended; the"
                        + " payload is withheld from this description because it spans a card number")
                .isTrue();
        assertThat(row.reasonDesc())
                .as("the description is carried across character for character from"
                        + " app/cbl/CBTRN02C.cbl:411")
                .isEqualTo("OVERLIMIT TRANSACTION")
                .hasSizeLessThanOrEqualTo(REASON_DESC_WIDTH);
        assertThat(ordinal)
                .as("the database assigned an ordinal to the appended row")
                .isPositive();
    }

    /**
     * Confirms the stored image keeps the DAILY record's own timestamp and filler spans, as read.
     *
     * <p>Assumptions: this is the highest-value assertion in this file and it is asserted in BOTH
     * directions. {@code app/cbl/CBTRN02C.cbl:447} is {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA},
     * one whole-group move of all 350 bytes, so the stored image retains the spans
     * {@code app/cpy/CVTRA06Y.cpy} gives that record -- {@code DALYTRAN-PROC-TS PIC X(26)} at 1-based
     * 305 to 330 and {@code FILLER PIC X(20)} at 331 to 350. The positive direction is that the
     * originating span at 279 to 304 is POPULATED; the negative direction is that the processing span is
     * blank and holds no timestamp at all.</p>
     *
     * <p>Assumptions: the negative direction is what makes this case meaningful, and its absence is the
     * specific defect being caught. An implementation that recomposed the record field by field and
     * stamped a processing timestamp into it -- the natural thing to write, since the POSTED record does
     * receive one at {@code app/cbl/CBTRN02C.cbl:437-438} -- would still store 350 characters and would
     * still pass a case that only measured the width. It is caught here because a stamp is not blank.
     * The two spans are asserted TOGETHER for the same reason: a populated originating span beside a
     * blank processing span is the observable signature of a group move, whereas a blank processing span
     * on its own would also be produced by an implementation that stored nothing but spaces.</p>
     *
     * <p>Assumptions: the measurement behind this is the committed oracle rather than this file's
     * reasoning. In each of {@code tests/golden/posting/reject_100_card_missing/dalyrejs.expected},
     * {@code reject_101_acct_missing}, {@code reject_102_overlimit} and {@code reject_103_expired}, bytes
     * 305 to 330 are twenty-six spaces, bytes 331 to 350 are twenty spaces, and bytes 279 to 304 carry a
     * populated originating timestamp; and the first 350 characters of each record are byte-identical to
     * the daily-transaction fixture that provoked it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the stored image keeps the daily record's blank processing span and its populated originating span")
    void theStoredImageIsTheDailyRecordAsRead() {
        String image = rejectedRecordImage("0000000000000002");

        appendReject(
                image,
                TransactionReject.REASON_CODE_AFTER_EXPIRATION,
                TransactionReject.REASON_DESC_AFTER_EXPIRATION);

        String storedImage = storedRejects().getFirst().rawRecord();
        String storedProcessingSpan = storedImage.substring(PROC_TS_START, PROC_TS_END);
        String storedOriginatingSpan = storedImage.substring(ORIG_TS_START, ORIG_TS_END);

        // Assumptions: these two spans are safe to name in a description where the whole image is not.
        //     Neither overlaps the card-number span at 1-based 263 to 278, so a failure prints a
        //     timestamp shaped value and nothing that identifies an instrument.
        assertThat(storedProcessingSpan)
                .as("bytes 305-330 are the DAILY record's own DALYTRAN-PROC-TS, blank because"
                        + " app/cbl/CBTRN02C.cbl:211-216 rejected this record instead of posting it")
                .isEqualTo(" ".repeat(TIMESTAMP_WIDTH))
                .isBlank();
        assertThat(storedProcessingSpan.trim())
                .as("no processing timestamp was stamped into the payload; a recomposing implementation"
                        + " would fill this span and still store 350 characters")
                .isEmpty();
        assertThat(storedOriginatingSpan)
                .as("bytes 279-304 are the DAILY record's own DALYTRAN-ORIG-TS, populated because the"
                        + " feed supplied it, which is what distinguishes a group move from blanks")
                .isEqualTo(ORIGINATED_AT);
        assertThat(storedImage.substring(PROC_TS_END))
                .as("bytes 331-350 are the DAILY record's own trailing FILLER span")
                .isEqualTo(" ".repeat(FILLER_WIDTH));
    }

    /**
     * Confirms the blank-span assertion can actually fail, by storing an image that violates it.
     *
     * <p>Assumptions: an assertion nobody has seen fail is an assertion of unknown strength, and this
     * case is what fixes the strength of the one above. It appends an image identical in every span
     * except that the processing span carries a stamp of exactly the twenty-six-character form
     * {@code app/cbl/CBTRN02C.cbl:437-438} mints for a POSTED transaction, and it asserts that the same
     * predicate the case above applies now reports a difference. Without this, a predicate that silently
     * matched anything would leave the group-move proof reading as intact.</p>
     *
     * <p>Trade-offs: this stores a row the migrated job must never produce, which is the cost of proving
     * the discriminator works. It is accepted because the row is written directly through the interface
     * inside this case's own transaction and is removed before the next case runs, so no other case can
     * observe it; the alternative -- trusting the predicate because it looks discriminating -- is what
     * lets a length-only assertion masquerade as a content assertion.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stamped processing span is distinguishable, so the blank-span assertion has teeth")
    void aStampedProcessingSpanIsCaught() {
        String faithful = rejectedRecordImage("0000000000000003");
        String recomposed = dailyRecordImage("0000000000000003", PROCESSED_AT);

        // Assumptions: the two images differ ONLY in the processing span, which is what makes the
        //     comparison below attribute the difference to that span rather than to fixture drift. Both
        //     come from one builder and differ in one argument.
        assertThat(recomposed.length())
                .as("the recomposed image is the same declared width, which is precisely why a"
                        + " width-only assertion could not tell the two apart")
                .isEqualTo(faithful.length());

        appendReject(
                recomposed,
                TransactionReject.REASON_CODE_OVERLIMIT,
                TransactionReject.REASON_DESC_OVERLIMIT);

        String storedProcessingSpan =
                storedRejects().getFirst().rawRecord().substring(PROC_TS_START, PROC_TS_END);
        assertThat(storedProcessingSpan)
                .as("the engine stores whatever the processing span held, so the span assertion above"
                        + " reports content and is not satisfied by any 350-character value")
                .isEqualTo(PROCESSED_AT)
                .isNotBlank();
    }

    /**
     * Confirms each of the four persisted reason codes stores as a number beside its verbatim text.
     *
     * <p>Assumptions: the four codes and their texts are carried character for character from the
     * reference program, which assigns each pair in two adjacent statements -- 100 at
     * {@code app/cbl/CBTRN02C.cbl:385} with its text at {@code :386}, 101 at {@code :397} with
     * {@code :398}, 102 at {@code :410} with {@code :411}, and 103 at {@code :417} with {@code :418}. All
     * four were verified character for character against the committed expectations
     * {@code tests/golden/posting/reject_100_card_missing/dalyrejs.expected} and its three siblings, whose
     * bytes 355 to 430 carry these texts right-padded to seventy-six.</p>
     *
     * <p>Assumptions: the persisted domain is exactly these four, and a fifth value exists in the
     * reference program that cannot reach this stream. 109 is assigned only at
     * {@code app/cbl/CBTRN02C.cbl:556}, inside the account rewrite's invalid-key clause, on a paragraph
     * reached only from the arm of {@code :211-216} taken when validation has already SUCCEEDED -- while
     * this stream is written from the opposite arm. The domain is stated here as a documented fact and
     * NOT asserted as a constraint, because neither the harness nor the entity narrows the column to four
     * values: both admit the whole four-digit picture, so an assertion that only 100 to 103 are storable
     * would contradict the schema. Reachability is the rule tier's proof, not this tier's.</p>
     *
     * <p>Assumptions: the stored form is an integer and the wire form is four zero-padded characters, and
     * these are two representations of one value rather than a disagreement. {@code reason_code} is
     * declared {@code SMALLINT} by both the harness and the owning migration, so the column holds 102;
     * {@code WS-VALIDATION-FAIL-REASON} is {@code PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:181}, so
     * bytes 351 to 354 of a record read {@code 0102}. This case asserts the integer, and the zero-padded
     * rendering belongs to the mapper. The fixture guide beside this tree records the same distinction as
     * one of the three schema facts it states rather than derives, and names the reason a case gets this
     * wrong: comparing a {@code SMALLINT} against the string form fails in a way that looks like a wrong
     * reason code rather than a wrong representation.</p>
     *
     * @param reasonCode the numeric reason under test, one of the four the validation paragraphs assign
     *     and persist, supplied as the {@code SMALLINT} the column declares
     * @param reasonDesc the description the reference program moves alongside that code, carried
     *     character for character and asserted for exact equality
     */
    @ParameterizedTest(name = "reason {0} stores beside \"{1}\"")
    @DisplayName("each of the four persisted reason codes stores as a number with its verbatim text")
    @CsvSource({
        "100, INVALID CARD NUMBER FOUND",
        "101, ACCOUNT RECORD NOT FOUND",
        "102, OVERLIMIT TRANSACTION",
        "103, TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
    })
    void eachPersistedReasonCodeStoresAsANumberWithItsText(short reasonCode, String reasonDesc) {
        String image = rejectedRecordImage(String.format(Locale.ROOT, "%016d", reasonCode));

        appendReject(image, reasonCode, reasonDesc);

        StoredReject row = storedRejects().getFirst();
        assertThat(row.reasonCode())
                .as("the reason is a NUMBER in the column, the four zero-padded digits being the wire"
                        + " form the mapper renders and not the stored form")
                .isEqualTo(reasonCode);
        assertThat(row.reasonDesc())
                .as("the description is carried character for character from the reference program and"
                        + " from the committed reject expectations")
                .isEqualTo(reasonDesc);
        assertThat(row.rawRecord().length())
                .as("the image accompanying every reason is stored at its full declared width")
                .isEqualTo(IMAGE_WIDTH);
    }

    /**
     * Confirms two identical rejected records store as two distinct rows rather than colliding.
     *
     * <p>Assumptions: this proof is TARGET-SIDE and has no baseline citation of its own, because the
     * reference dataset asserts uniqueness over nothing at all -- there is no constraint in the reference
     * to be faithful to, only the absence of one. What the reference does supply is the reason the
     * absence matters: {@code app/cbl/CBTRN02C.cbl:46-49} selects the stream as
     * {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} and no record key, and
     * {@code app/jcl/DALYREJS.jcl:24-26} retains five generations of it, so the same record rejected on
     * two runs is legitimately two entries.</p>
     *
     * <p>Alternatives Considered: a unique constraint over the record image, or over the image together
     * with its reason, so that a reject carried a natural key instead of a surrogate one. Rejected
     * because a rerun legitimately re-rejects the same input: collapsing the two occurrences into one row
     * would lose a reject the reference program emitted and would undercount the tally that
     * {@code app/cbl/CBTRN02C.cbl:229-230} turns into the run's return code. This case is asserted rather
     * than left to the schema because it is the observable consequence of having chosen a surrogate key,
     * and a constraint added later would break it loudly here instead of quietly in a nightly run.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the same rejected record appended twice stores as two rows with distinct ordinals")
    void duplicateRejectsArePermitted() {
        String image = rejectedRecordImage("0000000000000004");

        long first = appendReject(
                image,
                TransactionReject.REASON_CODE_INVALID_CARD_NUMBER,
                TransactionReject.REASON_DESC_INVALID_CARD_NUMBER);
        long second = appendReject(
                image,
                TransactionReject.REASON_CODE_INVALID_CARD_NUMBER,
                TransactionReject.REASON_DESC_INVALID_CARD_NUMBER);

        List<StoredReject> stored = storedRejects();
        assertThat(stored)
                .as("two appends of one identical record produce two rows and no constraint violation")
                .hasSize(2);
        assertThat(second)
                .as("the ordinals differ, which is what makes two legitimately identical rejects"
                        + " distinguishable where the reference stream left them indistinguishable")
                .isNotEqualTo(first);
        assertThat(List.of(stored.getFirst().rejectSeq(), stored.get(1).rejectSeq()))
                .as("both assigned ordinals are the ones the appends returned, read back in append order")
                .containsExactly(first, second);
        assertThat(stored.getFirst().rawRecord().equals(stored.get(1).rawRecord()))
                .as("both rows carry the identical image, so nothing normalised the payload to make the"
                        + " two rows differ; the payload is withheld from this description")
                .isTrue();
    }

    /**
     * Confirms the appended row carries a database-assigned surrogate ordinal in append order.
     *
     * <p>Refactoring Rationale: the reference dataset is a keyless sequential output file, and what was
     * wrong with carrying that shape forward is that it cannot BE a relational table.
     * {@code app/cbl/CBTRN02C.cbl:46-49} declares it {@code ORGANIZATION IS SEQUENTIAL} with
     * {@code ACCESS MODE IS SEQUENTIAL} and no {@code RECORD KEY} at all, and
     * {@code app/jcl/POSTTRAN.jcl:36} gives it {@code RECFM=F} -- a flat stream appended to and never
     * keyed into. There is therefore no natural key to carry across, and the only column that could have
     * served as one is the 350-character image, which is not unique and is far wider than any key this
     * table needs. Identity is introduced rather than invented from the payload: the alternative is not
     * "no surrogate" but "no identity", and a mapped type requires one.</p>
     *
     * <p>Assumptions: the ordinal is an append POSITION, so ordering by it reproduces the order the
     * reference wrote entries in. That order is observable in the reference:
     * {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:446} is performed inside the same
     * front-to-back loop that reads the feed at {@code :202-219}, so the reject stream's sequence is the
     * feed's sequence restricted to the rejected records.</p>
     *
     * <p>Trade-offs: an ordinal is all the identity this table carries, and it deliberately carries no
     * run discriminator -- no run identifier, no timestamp and no generation. The consequence is worth
     * stating plainly because a reader will otherwise assume a query exists: this table CANNOT be asked
     * how many rejects a given run produced. The tally that {@code app/cbl/CBTRN02C.cbl:229-230} turns
     * into the graded return code is the job's own step-execution counter, the direct equivalent of the
     * working-storage counter incremented at {@code :214} in the same breath as the row is written at
     * {@code :215}, and that counter is scoped to one execution by construction. A table-wide count would
     * aggregate every reject ever loaded into the schema and would answer a different question than the
     * caller asked. The accepted cost is that the count lives with the job rather than with the data.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the database assigns the surrogate ordinal, the reference stream having no key to carry across")
    void theSurrogateOrdinalIsAssignedInAppendOrder() {
        long earlier = appendReject(
                rejectedRecordImage("0000000000000005"),
                TransactionReject.REASON_CODE_ACCOUNT_NOT_FOUND,
                TransactionReject.REASON_DESC_ACCOUNT_NOT_FOUND);
        long later = appendReject(
                rejectedRecordImage("0000000000000006"),
                TransactionReject.REASON_CODE_OVERLIMIT,
                TransactionReject.REASON_DESC_OVERLIMIT);

        assertThat(later)
                .as("the second append received a higher ordinal, so ordering by it reproduces the order"
                        + " app/cbl/CBTRN02C.cbl:202-219 wrote the entries in")
                .isGreaterThan(earlier);
        assertThat(storedRejects())
                .as("both rows are readable in that append order")
                .extracting(StoredReject::rejectSeq)
                .containsExactly(earlier, later);
    }

    /**
     * Confirms the reachable surface of the reject interface is one append and nothing else.
     *
     * <p>Assumptions: the reference program has no read, rewrite or removal path over this dataset at
     * all, so there is nothing to migrate. {@code 2500-WRITE-REJECT-REC} at
     * {@code app/cbl/CBTRN02C.cbl:446} moves the record at {@code :447}, moves the trailer at
     * {@code :448} and issues exactly one {@code WRITE} at {@code :451}; the file is opened for output
     * only, and no rewrite or delete verb appears against it anywhere in the program. An inherited
     * removal member would therefore offer a capability with no parity reference -- there is no
     * expectation file saying what removing a reject should do, because the reference cannot do it.</p>
     *
     * <p>Alternatives Considered: proving this at RUN time by calling a removal member and asserting that
     * the database or a privilege refused it. Rejected on two independent grounds. The call would not
     * compile, since the member does not exist on the interface, so there is nothing to write; and even
     * if the surface were widened, the harness creates no role and issues no grant, so these tests
     * connect as the container's superuser and a delete would SUCCEED here while failing in a deployed
     * environment. A method that does not exist cannot be called from anywhere, which is a stronger
     * guarantee than a call that happens to be rejected, and it holds at every call site rather than only
     * at the ones a test exercises.</p>
     *
     * <p>Assumptions: this class's own arrangement deletes rows between cases, and that is not a
     * contradiction of the append-only contract. The contract governs what application code may reach
     * through the interface; the teardown reaches around it through JDBC precisely because the interface
     * offers no removal member to abuse.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the interface exposes one append and no update, delete or count member")
    void theReachableSurfaceIsAppendOnly() {
        List<String> reachable = Arrays.stream(TransactionRejectRepository.class.getMethods())
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();

        assertThat(reachable)
                .as("the whole reachable surface is the single append, the interface extending the"
                        + " marker Repository base rather than a CRUD base that would inherit more")
                .containsExactly("save");
        assertThat(reachable)
                .as("no removal or update member is reachable, the reference program having no rewrite"
                        + " or delete path over this dataset to migrate")
                .noneMatch(name -> name.startsWith("delete")
                        || name.startsWith("remove")
                        || name.startsWith("update"));
        assertThat(reachable)
                .as("no counting member is offered either, because TransactionReject carries no run"
                        + " discriminator and a table-wide count would answer a different question")
                .noneMatch(name -> name.startsWith("count"));
    }

    /**
     * Builds one 350-character daily-transaction image whose processing-timestamp span is blank.
     *
     * <p>This is the shape a rejected record has when the reject writer copies it: every field the feed
     * supplied is present, the ORIGINATING timestamp among them, and the PROCESSING timestamp is blank
     * because posting is what fills it and this record was not posted.</p>
     *
     * @param transactionId the value placed in the leading {@code DALYTRAN-ID} span, used to tell two
     *     otherwise identical fixture images apart; padded or truncated to the span's sixteen characters
     * @param processingStamp the value placed in the {@code DALYTRAN-PROC-TS} span, which a faithful
     *     image leaves blank; a non-blank value is supplied only by the case that proves the span
     *     assertion can actually fail
     * @return exactly {@value #IMAGE_WIDTH} characters laid out in the field order
     *     {@code app/cpy/CVTRA06Y.cpy} declares; never {@code null}
     */
    // Assumptions: this composes SPANS at the copybook's declared widths and is emphatically NOT a
    //     fixed-width codec. It performs no zoned-decimal sign overpunch, no packed-decimal packing and
    //     no numeric editing, because this tier asserts rows rather than bytes and
    //     TransactionRejectRecordMapper owns the encoding. Re-deriving the encoding here would let a case
    //     pass or fail for a reason belonging to the mapper.
    // Assumptions: the fourteen widths below sum to 350 and are read off app/cpy/CVTRA06Y.cpy in
    //     declaration order -- identifier 16, type 2, category 4, source 10, description 100, amount 11,
    //     merchant identifier 9, merchant name 50, merchant city 50, merchant postal code 10, card
    //     number 16, originating stamp 26, processing stamp 26, filler 20. The two spans this class
    //     asserts on therefore land where the copybook puts them, at 1-based 305 and 331, and the
    //     card-number span lands at 263.
    // Assumptions: every value is a constructed fixture naming no real person, account or instrument,
    //     and no value is read from a clock. A stamp compared against the current time would pass for a
    //     reason unrelated to the code under test and fail only when two reads straddled a boundary.
    private static String dailyRecordImage(String transactionId, String processingStamp) {
        StringBuilder image = new StringBuilder(IMAGE_WIDTH);
        image.append(pad(transactionId, 16));
        image.append(pad("01", 2));
        image.append(pad("0001", 4));
        image.append(pad("POS", 10));
        image.append(pad("Fixture rejected transaction", 100));
        image.append(pad("00000010000", 11));
        image.append(pad("900000001", 9));
        image.append(pad("Fixture Merchant", 50));
        image.append(pad("Fixture City", 50));
        image.append(pad("12345", 10));
        image.append(pad(FIXTURE_CARD_NUM, 16));
        image.append(pad(ORIGINATED_AT, TIMESTAMP_WIDTH));
        image.append(pad(processingStamp, TIMESTAMP_WIDTH));
        image.append(pad("", FILLER_WIDTH));
        return image.toString();
    }

    /**
     * Builds the faithful form of a rejected record image, the processing-timestamp span left blank.
     *
     * @param transactionId the value placed in the leading {@code DALYTRAN-ID} span, so that two images
     *     built for one case can be told apart without reading any other span
     * @return exactly {@value #IMAGE_WIDTH} characters whose processing-timestamp span is blank, which
     *     is what a group move of an unposted record produces; never {@code null}
     */
    // Assumptions: a blank processing span is the faithful state and not an omission. The only site that
    //     mints a stamp is app/cbl/CBTRN02C.cbl:437-438, inside 2000-POST-TRANSACTION, which :211-216
    //     reaches on the arm OPPOSITE the reject writer -- so a record in this stream was never posted
    //     and its own span was never filled. The four committed reject expectations agree: bytes 305 to
    //     330 are twenty-six spaces in every one of them.
    private static String rejectedRecordImage(String transactionId) {
        return dailyRecordImage(transactionId, "");
    }

    /**
     * Right-pads a value with blanks to a fixed span width, or truncates a value that overruns it.
     *
     * @param value the span's content, which may be shorter than the span; must not be {@code null}
     * @param width the span's declared width in characters, taken from {@code app/cpy/CVTRA06Y.cpy}
     * @return exactly {@code width} characters; never {@code null}
     */
    // Assumptions: padding is on the RIGHT because every span this method fills is declared PIC X(n),
    //     which is alphanumeric and left-justified. The one span whose picture is numeric-display and so
    //     pads on the left is the reason code, and that lives in the trailer rather than in the image
    //     this method builds -- it is a SMALLINT column here and is rendered zero-padded only on the
    //     wire, by the mapper.
    // Trade-offs: an overrunning value is truncated rather than refused. A refusal would be the stricter
    //     choice, and it is declined because the caller is this file's own fixture builder rather than
    //     application code: truncation keeps the total at exactly 350 whatever a future fixture supplies,
    //     which is the property the entity's constructor checks and the property a case needs, whereas a
    //     refusal would replace a readable width failure with an exception from a helper.
    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Renders the card number inside a record image as a masked fragment safe to put in a diagnostic.
     *
     * @param image the 350-character record image to take the card-number span from; must not be
     *     {@code null} and must be at least as long as that span's end
     * @return the last {@value #UNMASKED_CARD_DIGITS} characters of the card-number span behind a mask,
     *     carrying no other character of the image; never {@code null}
     */
    // Assumptions: the masking contract is the migration plan's, at its section 0.7.8, which masks a
    //     primary account number to its last four digits everywhere except one administrative endpoint
    //     this module does not have. It is applied here because the image spans a card number at 1-based
    //     263 to 278 per app/cpy/CVTRA06Y.cpy, and an assertion description reaches a build log.
    // Trade-offs: this returns four characters where the whole span would be more informative to a
    //     reader diagnosing a failure. The alternative was to let the failure carry the span, and it is
    //     rejected on where that text ends up: a build log is retained and widely readable, and the
    //     module's own test profile already pins the provider's error category to ERROR for exactly this
    //     reason -- so a diagnostic that leaked what that setting suppresses would defeat it. The span
    //     itself remains readable in the row and in the emitted dataset generation, which are the places
    //     an operator is meant to read it from.
    private static String maskedCardNumber(String image) {
        String cardNumber = image.substring(CARD_NUM_START, CARD_NUM_END);
        return "****" + cardNumber.substring(cardNumber.length() - UNMASKED_CARD_DIGITS);
    }

    /**
     * Reads every stored reject row back, ordered by the ordinal the database assigned.
     *
     * @return the stored rows in append order, each carrying its ordinal, its blank-padded image, its
     *     numeric reason and that reason's description; never {@code null}, possibly empty
     */
    // Assumptions: the read is issued through JDBC because TransactionRejectRepository declares NO
    //     finder to read one back. That absence is deliberate and is proved by its own case below, so a
    //     read-back here has to go around the interface rather than through it; the alternative would be
    //     to add a finder to production code for a test's benefit, which would widen a surface the
    //     reference program has no counterpart for.
    // Assumptions: the image column is read with getString and its width is trusted to include the
    //     blank padding, which was measured against this engine rather than assumed -- a CHAR(350) value
    //     is returned padded, while SQL length() over the same value answers the TRIMMED figure and only
    //     octet_length answers 350. Every width assertion below is therefore made in Java on the
    //     returned string, and none uses SQL length().
    private List<StoredReject> storedRejects() {
        return this.jdbc.query(
                "SELECT reject_seq, raw_record, reason_code, reason_desc"
                        + " FROM ledger.transaction_rejects ORDER BY reject_seq",
                (row, index) -> new StoredReject(
                        row.getLong("reject_seq"),
                        row.getString("raw_record"),
                        row.getShort("reason_code"),
                        row.getString("reason_desc")));
    }

    /**
     * Appends one reject row through the production interface, inside a committed transaction.
     *
     * @param image the 350-character record image to store, normally the faithful form a group move
     *     produces; must not be {@code null}
     * @param reasonCode the numeric reason the record was rejected for, stored as the column's
     *     {@code SMALLINT}
     * @param reasonDesc the reason's description, carried character for character from the reference
     *     program; must not be {@code null}
     * @return the ordinal the database assigned to the appended row, read from the instance the
     *     interface returned rather than from the argument
     */
    // Assumptions: the ordinal is read from the RETURNED instance and never from the one passed in,
    //     because the interface's own contract states that the argument's ordinal must be absent and
    //     that a caller needing the assigned value must read it from what came back.
    // Assumptions: the write is wrapped in an explicit boundary because the test profile turns
    //     connection auto-commit off, so an unwrapped save would neither commit nor be visible to the
    //     JDBC read-back that verifies it. The flush and clear that follow detach the instance, so the
    //     read below reaches the database rather than the persistence context's first-level cache --
    //     without which a case could pass against an object it had just constructed in memory.
    private long appendReject(String image, short reasonCode, String reasonDesc) {
        return this.transactionTemplate.execute(status -> {
            TransactionReject appended =
                    this.rejects.save(new TransactionReject(image, reasonCode, reasonDesc));
            this.entityManager.flush();
            this.entityManager.clear();
            return appended.getRejectSeq();
        });
    }

    /**
     * One stored reject row as the database returns it, held apart from the mapped entity.
     *
     * @param rejectSeq the ordinal the database assigned, being the surrogate identity of the row
     * @param rawRecord the stored image as the driver returned it, blank padding included
     * @param reasonCode the stored reason, a {@code SMALLINT} in the column and never four characters
     * @param reasonDesc the stored description, as the reference program wrote it
     */
    // Alternatives Considered: reading rows back as TransactionReject instances, which is what a sibling
    //     in this package does. Declined for this class because the entity is mapped @Immutable and
    //     compares on its ordinal alone, so a case proving that two duplicate rows are DISTINCT would be
    //     asserting through the very equality it is trying to characterise. A plain record carries the
    //     four column values with no equality opinion of its own, which keeps that case's assertion
    //     about the database rather than about the mapping.
    private record StoredReject(long rejectSeq, String rawRecord, short reasonCode, String reasonDesc) {

        /**
         * Renders the ordinal and the reason for diagnosis, deliberately omitting the record image.
         *
         * @return a String carrying the ordinal, the reason code, the reason description and the card
         *     number masked to its last {@value #UNMASKED_CARD_DIGITS} digits, and never the image
         *     itself; never {@code null}
         */
        // Assumptions: a record's generated rendering names every component, so WITHOUT this override
        //     the image is rendered in full wherever a collection of this type is rendered -- and
        //     AssertJ renders the actual collection when a size or content assertion over it fails.
        //     That is not a hypothetical: the omission was found by breaking a list-size assertion
        //     deliberately and reading the report, which printed the whole 350-character image and with
        //     it the card number spanning 1-based 263 to 278 per app/cpy/CVTRA06Y.cpy. The masking
        //     contract is the migration plan's, at its section 0.7.8, which masks a primary account
        //     number to its last four digits.
        // Alternatives Considered: leaving the generated rendering in place and instead avoiding every
        //     assertion that could render this type -- asserting sizes on int values and content on
        //     extracted components only. Rejected because it makes the guarantee depend on every
        //     author remembering it: a later case adding one containsExactly or hasSize over these rows
        //     would reintroduce the leak silently, and nothing would report it. Overriding the
        //     rendering makes the type safe to render from anywhere, which is the same choice
        //     TransactionReject makes on the production side and for the same reason.
        // Trade-offs: a failure over these rows no longer shows the bytes that differ, so a content
        //     mismatch has to be diagnosed from the span assertions each case makes separately. That is
        //     accepted because a build log is retained and widely readable, while the image remains
        //     available in the row and in the emitted dataset generation, which are where an operator
        //     is meant to read it.
        @Override
        public String toString() {
            return "StoredReject[rejectSeq=" + rejectSeq
                    + ", card=" + maskedCardNumber(rawRecord)
                    + ", reasonCode=" + reasonCode
                    + ", reasonDesc=" + reasonDesc
                    + ']';
        }
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the job definitions, the queue client and the
     * object-store client this module's own application class would register stay out of the context.
     * Naming the two persistence packages leaves the framework's auto-configuration to build the pool
     * from the properties the container registered.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class RejectStreamPersistenceTestApplication {
    }
}
