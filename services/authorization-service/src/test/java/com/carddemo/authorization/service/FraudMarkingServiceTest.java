package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.config.SecurityConfig;
import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.AuthFraudRepository;
import com.carddemo.authorization.repository.AuthFraudUpserter;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CursorToken;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the one write this context publishes: the selector-versus-body key comparison that guards it,
 * the composed fraud key, both write paths, and the two rows it leaves behind.
 *
 * <p>Assumptions: the repositories are doubles, the mapper is REAL and the clock is FIXED. A real mapper is
 * needed because the property under test includes redeeming a selector this same mapper sealed, which a
 * double could not exercise; a fixed clock is needed because both report dates are read from it and an
 * assertion on either would otherwise depend on the day the suite runs.</p>
 *
 * <p>Assumptions: every citation is relative to {@code app/app-authorization-ims-db2-mq}, which is
 * reference material this migration reads and never modifies.</p>
 *
 * <h2>What this class owns: divergence D-6</h2>
 *
 * <p>Refactoring Rationale: this class is the owner of documented divergence D-6, and what was wrong with
 * the replaced approach is that it needed a DISTRIBUTED COORDINATOR to keep one logical operation
 * consistent. One fraud marking was split across two programs and two resource managers:
 * {@code cbl/COPAUS1C.cbl} L248 to L252 issues {@code EXEC CICS LINK} to {@code COPAUS2C}, that linked
 * program writes the relational fraud row -- inserting at L141 and L142 or, on the duplicate-key
 * condition it tests at L199 and L203, updating at L222 to L229 -- and control returns to the caller,
 * which replaces the hierarchical segment at L525 to L528. Those two writes were then committed by ONE
 * {@code EXEC CICS SYNCPOINT} at L558, with the rollback at L566 to L568. A single commit point spanning a
 * relational and a hierarchical manager is a genuine two-phase commit, and it is the sharpest evidence
 * that the operation was one unit of work wearing two datastores. Both rows now live in one PostgreSQL
 * schema behind one non-distributed {@code DataSource}, so the coordinator is ELIMINATED rather than
 * emulated: no XA data source, no JTA manager, no saga and no compensating reversal exists anywhere on
 * this path. The register of record is
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Alternatives Considered: a saga, or an equivalent compensating reversal, to keep the two writes
 * consistent without a coordinator. Rejected on a concrete consequence rather than on taste: a saga
 * commits the fraud row first and reverses it afterwards if the segment write fails, which makes a
 * half-marked authorization a state that genuinely exists and can be read between the two steps. Neither
 * the reference system nor the target can produce that state, so a reversal would create precisely the
 * window it exists to close, and every assertion below would then be asserting a weaker property than the
 * baseline already offered.</p>
 *
 * <h2>What this tier does NOT claim</h2>
 *
 * <p>Assumptions: the collaborators here are doubles, so this class asserts the SHAPE of the single
 * boundary -- that one transaction is declared, that the inner write cannot open one of its own, that the
 * two writes are ordered as the reference orders them, and that a failure of either propagates instead of
 * being swallowed. It deliberately does NOT assert what a database RETAINED after a failure, because a
 * double can only record what was asked of it. That claim is owned one tier up by
 * {@code FraudMarkingTransactionRepositoryIT}, which drives the same service against a real engine, and
 * the statement-level narrowing of the replace arm is owned by
 * {@code com.carddemo.authorization.repository.AuthFraudUpserterImpl}, whose {@code ON CONFLICT} clause
 * and system-column discriminator no mock can settle.</p>
 *
 * <p>Assumptions: NO GOLDEN MASTER EXISTS for this path, and none is claimed. The two reference programs
 * are CICS online programs, and the reference suite records that the online {@code CO*} programs cannot be
 * run end to end without a CICS runtime, which the runner does not have. Parity here therefore rests on
 * the copybook and data-definition contracts plus the transcribed control flow, asserted by this class.
 * The reference suite's documented warn-level aggregate return code is its own green state and is
 * unrelated to this module.</p>
 *
 * <p>Assumptions: discovering a defect or an awkward characteristic in the baseline creates an obligation
 * to DOCUMENT it and never a licence to change it. Nothing under {@code app} is edited, fixed or deleted
 * by this class or on its account; the house has already written that precedent down for an unfixable
 * record-key defect in two immutable baseline programs, and every divergence named below follows it.</p>
 *
 * <p>Assumptions: this class is written against user-specified Rule 1 (Explainability), whose gate is
 * conjunctive -- a missing docstring fails it, and a missing decision rationale fails it independently --
 * so every type, test and helper below carries a docstring, and every non-obvious choice carries an
 * adjacent comment under one of the rule's four named categories. Rule 1 is cited here by name and its
 * text is not reproduced. Separately, AAP Rule T8 (user-visible strings verbatim) is what obliges the
 * message assertions below to compare characters rather than meanings.</p>
 */
class FraudMarkingServiceTest {

    /**
     * The authenticated principal the selector this class seals is issued to and redeemed under.
     */
    private static final String SUBJECT = "authorization-operator";

    /** The account the marked row belongs to, as the key holds it. */
    private static final long ACCOUNT_ID = 11L;

    /**
     * The customer the fraud row is filed against, as the parent summary row holds it.
     *
     * <p>Assumptions: the request body carries neither this value nor the account, so the only place a
     * test can plant the customer identifier is the parent-summary double wired in {@code setUp}.</p>
     */
    private static final String CUSTOMER_ID_DIGITS = "000000011";

    /** The Julian date key: day 215 of 2026. */
    private static final int AUTH_DATE = 26215;

    /**
     * The composed time key, positionally 09:16:44 and 902 milliseconds.
     *
     * <p>Assumptions: written with digit grouping that shows the POSITIONAL fields rather than thousands,
     * because the clock half of this key is {@code HHMMSS} written side by side and the low three digits
     * are the milliseconds. Grouping it in threes would read as a count and invite the sexagesimal
     * decomposition this key does not use.</p>
     */
    private static final int AUTH_TIME = 9_16_44_902;

    /** The card number the marked row carries, which is the first half of the fraud key. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The acquirer-supplied original date, six characters year-first. */
    private static final String AUTH_ORIG_DATE = "260803";

    /**
     * The date the substituted repository reports as the DATABASE's current date.
     *
     * <p>Refactoring Rationale: this was an {@code Instant} handed to a fixed clock the service used to be
     * built with. The service now reads its report date from
     * {@link AuthFraudRepository#currentDate()}, because the reference system dates both of its writes from
     * the database server, so a clock is no longer the value to fix and the substitute reports a date
     * directly. The day is unchanged, which is what keeps the two rendering assertions below asserting the
     * same strings they did.</p>
     */
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 8, 6);

    /**
     * The report date in the segment's own month-first eight-character rendering.
     *
     * <p>Assumptions: {@code MM/dd/yy} with solidus separators, which is what
     * {@code EXEC CICS FORMATTIME ... MMDDYY(WS-CUR-DATE) DATESEP} at {@code cbl/COPAUS2C.cbl} L95 to
     * L100 produces and what its L101 moves straight into the segment field. Written out as a literal
     * rather than formatted here, so the expectation is independent of the formatter the service
     * uses.</p>
     */
    private static final String SEGMENT_REPORT_DATE_TEXT = "08/06/26";

    /**
     * The sentence the reference caller composes when it rolls the fraud marking back.
     *
     * <p>Assumptions: carried character for character from {@code cbl/COPAUS1C.cbl} L545, which is the
     * literal inside the {@code STRING} statement spanning L544 to L549 -- one LEADING SPACE, the exact
     * mixed-case wording, and a TRAILING pair of vertical bars before the DL/I status code is appended.
     * The 45 characters here are the literal alone, without that appended code. The comment banner at
     * L562 to L564 reads {@code ROLLBACK THE DB CHANGES} and is NOT this string, so a reader reaching for
     * the rollback text must take L545 and never L563.</p>
     */
    private static final String REFERENCE_ROLLBACK_SENTENCE =
            " System error while FRAUD Tagging, ROLLBACK||";

    /**
     * A merchant name that fills the declared field with significant characters plus trailing blanks.
     *
     * <p>Assumptions: seven characters and fifteen blanks, exactly 22 in total, which is the width
     * {@code PA-MERCHANT-NAME PIC X(22)} declares at {@code cpy/CIPAUDTY.cpy} L40 and the width
     * {@code MERCHANT_NAME VARCHAR(22)} declares at {@code ddl/AUTHFRDS.ddl} L18. The blanks are
     * SIGNIFICANT to this test rather than incidental padding: the reference program moves the CONSTANT
     * declared length at {@code cbl/COPAUS2C.cbl} L130 and never a trimmed one.</p>
     */
    private static final String MERCHANT_NAME_WITH_TRAILING_BLANKS = "ACME CO" + " ".repeat(15);

    /**
     * The packaged contract of record for this context, read from the test class path.
     *
     * <p>Assumptions: the class-path form is used rather than a file-system path because
     * {@code src/main/resources} is on the test class path, which is the convention the sibling contract
     * tests in this module already follow, and it keeps the location independent of the directory a
     * runner happens to start in.</p>
     */
    private static final String CONTRACT_RESOURCE = "/openapi/authorization-api.yaml";

    /**
     * The number of columns the fraud table declares.
     *
     * <p>Assumptions: 26 is counted from {@code ddl/AUTHFRDS.ddl}, whose column definitions occupy L2 to
     * L27 with the primary key on L28, and the reference insert corroborates it from the other side by
     * naming all 26 in the column list at {@code cbl/COPAUS2C.cbl} L143 to L168. Two independent sources
     * agree, so this is a contract rather than a reading.</p>
     */
    private static final int AUTHFRDS_COLUMN_COUNT = 26;

    /**
     * The number of columns the replace arm is permitted to write.
     *
     * <p>Assumptions: 2 is counted from the reference {@code SET} list at {@code cbl/COPAUS2C.cbl} L224
     * and L225, which names the fraud indicator and the report date and nothing else.</p>
     */
    private static final int REPLACE_ARM_COLUMN_COUNT = 2;

    /** The authorization repository double. */
    private PendingAuthDetailRepository details;

    /**
     * The parent-summary repository the service reads the fraud row's customer identifier from.
     */
    private PendingAuthSummaryRepository summaries;

    /** The fraud-row repository double. */
    private AuthFraudRepository fraudRows;

    /** The single-statement fraud writer that replaced the probe-then-write pair. */
    private AuthFraudUpserter fraudUpserts;

    /** The real mapper, which both seals the selector a test presents and redeems it under test. */
    private PendingAuthViewMapper mapper;

    /** The service under test. */
    private FraudMarkingService service;

    /**
     * Builds the doubles, a real sealer over deterministic key material, a fixed clock, and the service.
     */
    @BeforeEach
    void setUp() {
        this.details = mock(PendingAuthDetailRepository.class);
        this.summaries = mock(PendingAuthSummaryRepository.class);
        this.fraudRows = mock(AuthFraudRepository.class);
        this.fraudUpserts = mock(AuthFraudUpserter.class);
        PendingAuthSummary parent =
                new PendingAuthSummary(ACCOUNT_ID, Long.valueOf(CUSTOMER_ID_DIGITS));
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(parent));
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.mapper = new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        // WHY : Assumptions: the report date is stubbed on the fraud repository rather than supplied to a
        //       clock, because that is where the service reads it -- the database's own current date is
        //       what the reference writes, and a substitute that answered a clock instead would leave the
        //       assertion passing while the production path read a different source.
        when(this.fraudRows.currentDate()).thenReturn(REPORT_DATE);
        this.service = new FraudMarkingService(this.details, this.summaries, this.fraudRows,
                this.fraudUpserts,
                this.mapper);
    }

    /**
     * A first mark inserts the fraud row, reports the reference insert sentence, and says it created one.
     *
     * <p>Assumptions: the created flag and the sentence are asserted together, because the contract carries
     * the insert-versus-update distinction on the STATUS CODE and the sentence is display prose. Asserting
     * only the sentence would let a wrong status code pass; asserting only the flag would let the reference
     * wording drift.</p>
     *
     * <p>Refactoring Rationale: the inserted row's two identifier columns are captured and asserted here
     * rather than left to {@code verify(insertFraudRowIfAbsent(any()))}, because they are the only two of
     * the twenty-six that
     * are NOT copied from the segment being marked. The account must be the one the redeemed key names and
     * the customer must be the one the parent summary holds; a row filed against either a caller-stated or
     * a defaulted identifier would still satisfy an {@code any()} verification.</p>
     */
    @Test
    @DisplayName("a first mark inserts the fraud row and reports the reference insert sentence")
    void firstMarkInsertsTheFraudRow() {
        givenExistingRow();
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

        FraudMarkingService.FraudMarkOutcome outcome =
                this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.body().updateStatus())
                .isEqualTo(FraudMarkResponse.UPDATE_STATUS_SUCCESS);
        assertThat(outcome.body().message()).isEqualTo(FraudMarkResponse.MESSAGE_ADD_SUCCESS);
        ArgumentCaptor<AuthFraud> inserted = ArgumentCaptor.forClass(AuthFraud.class);
        verify(this.fraudUpserts).upsert(inserted.capture());
        assertThat(inserted.getValue().getAcctId())
                .as("the account is the one the redeemed selector's key carries")
                .isEqualTo(ACCOUNT_ID);
        assertThat(inserted.getValue().getCustId())
                .as("the customer is the one the parent summary holds")
                .isEqualTo(Long.valueOf(CUSTOMER_ID_DIGITS));
    }

    /**
     * An authorization whose account has no parent summary cannot be filed as a fraud report.
     *
     * <p>Refactoring Rationale: this refusal exists only because the customer identifier moved off the
     * request body and onto the parent summary. The reference program reads the value out of its own
     * communication area, so it has no equivalent failure; here the read can come back empty, and the
     * choice is to refuse rather than to file the row with a null customer. A null would violate the
     * column and surface as a constraint violation from the driver instead of as a statement about the
     * data, and a defaulted zero would file a real fraud report against a customer that does not
     * exist.</p>
     *
     * <p>Assumptions: nothing is saved on this path. The read happens before the insert is composed, so
     * asserting that the repository is never touched is what distinguishes refusing from writing a
     * partially populated row and rolling it back.</p>
     */
    @Test
    @DisplayName("an account with no parent summary cannot file a fraud row")
    void anAccountWithNoParentSummaryCannotFileAFraudRow() {
        givenExistingRow();
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));
        verify(this.fraudUpserts, never()).upsert(any(AuthFraud.class));
    }

    /**
     * A second mark replaces the state on the existing fraud row and reports the update sentence.
     *
     * <p>Assumptions: nothing is saved on this path, because the row was read inside the transaction and is
     * managed, so mutating it is the write. Verifying that no save occurs is what distinguishes replacing a
     * row from inserting a second one, which the primary key would refuse.</p>
     *
     * <p>Assumptions: only the state and the date change. The reference update names exactly those two
     * columns at {@code cbl/COPAUS2C.cbl} L222 to L225, so the twenty-four-column snapshot the row took
     * when it was inserted stays as it was; that is asserted here on the amount, which no path rewrites.</p>
     */
    @Test
    @DisplayName("a second mark reports the update sentence and proposes the requested state")
    void secondMarkReplacesTheExistingRow() {
        givenExistingRow();
        // WHY : Refactoring Rationale: this test used to seed an EXISTING AuthFraud, stub the probe to
        //       return it, and then assert on that object's mutated fields. None of that survives the
        //       change to a single atomic statement: the service no longer reads the row and no longer
        //       mutates a managed entity, so there is no in-memory row for a unit test to inspect. The
        //       stub is now the upserter reporting FALSE, which is what "a row already existed" means at
        //       this boundary.
        // WHY : Assumptions: the two properties this test can still settle are the ones that live in this
        //       service -- that the outcome is reported as a transition rather than a creation, and that
        //       the sentence published for it is the update sentence the reference reports at
        //       cbl/COPAUS2C.cbl L232. It additionally asserts that the row PROPOSED to the statement
        //       carries the requested state, which is the service's remaining share of the write.
        // WHY : Trade-offs: the two column restrictions this test used to assert -- that the state and the
        //       date change and that the twenty-four-column snapshot does not -- are now properties of the
        //       statement's DO UPDATE clause and cannot be observed through a mock. They are asserted
        //       against a real engine by the repository integration test instead, which is the only place
        //       they were ever really settled: a mutation of a detached object proved that this service
        //       CALLED applyState, never that the database left the other columns alone.
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(false);
        ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);

        FraudMarkingService.FraudMarkOutcome outcome =
                this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REMOVED), SUBJECT);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.body().message()).isEqualTo(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
        verify(this.fraudUpserts).upsert(proposed.capture());
        assertThat(proposed.getValue().getAuthFraud()).isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        assertThat(proposed.getValue().getFraudRptDate()).isEqualTo(REPORT_DATE);
    }

    /**
     * The fraud key is composed from the acquirer date and the positionally decoded time key.
     *
     * <p>Refactoring Rationale: this is the assertion that catches a sexagesimal decomposition of the time
     * key. Read positionally, 91644902 is 09:16:44 and 902 milliseconds; read as a count of milliseconds
     * since midnight it is 25 hours and change, which names no instant at all and would either throw or --
     * with a lenient composer -- key the row into the following day. The reference reader splits two
     * characters at a time at {@code cbl/COPAUS2C.cbl} L108 to L110 and three for the milliseconds at
     * L111.</p>
     *
     * <p>Assumptions: the two-digit year 26 resolves to 2026 through the pivot the reference write inherits
     * from Db2's {@code TIMESTAMP_FORMAT}, whose documented window for a two-digit year is 1970 through
     * 2069. A different pivot would key the row into a different century than the reference writes.</p>
     */
    @Test
    @DisplayName("the fraud key composes the acquirer date with the positionally decoded time key")
    void fraudKeyComposesTheAcquirerDateWithThePositionalTimeKey() {
        givenExistingRow();
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

        this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        // WHY : Refactoring Rationale: the composed key used to be captured off the PROBE argument, and
        //       there is no probe any more. It is captured off the row PROPOSED to the atomic statement
        //       instead, which carries the same key -- the statement's conflict target is that key, so a
        //       wrong composition still addresses a wrong row and this assertion still catches it.
        ArgumentCaptor<AuthFraud> keyed = ArgumentCaptor.forClass(AuthFraud.class);
        verify(this.fraudUpserts).upsert(keyed.capture());
        assertThat(keyed.getValue().getId().getCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(keyed.getValue().getId().getAuthTs()).isEqualTo(expectedAuthTs());
    }

    /**
     * The authorization row itself is marked, with the report date in the segment's month-first form.
     *
     * <p>Assumptions: the segment carries its OWN copy of both values, at {@code cpy/CIPAUDTY.cpy} L50 and
     * L53, and the reference system writes both -- the fraud program sets them on its copy at
     * {@code cbl/COPAUS2C.cbl} L101 and L137 and the caller moves that copy back over the segment at
     * {@code cbl/COPAUS1C.cbl} L520. The eight characters are month first because
     * {@code EXEC CICS FORMATTIME ... MMDDYY ... DATESEP} produces them that way, so they are deliberately
     * NOT the ISO ordering the fraud row's own date column holds.</p>
     */
    @Test
    @DisplayName("the authorization row is marked with the segment's month-first report date")
    void theAuthorizationRowIsMarkedWithTheSegmentDate() {
        PendingAuthDetail row = givenExistingRow();
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

        this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REMOVED), SUBJECT);

        assertThat(row.getAuthFraud()).isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        assertThat(row.getFraudReportDate()).isEqualTo("08/06/26");
    }

    /**
     * Both report dates one operation writes are the same day, because one database read produces both.
     *
     * <p>Purpose: this is the assertion that pins divergence {@code D-AUTH-FRAUD-ONE-CLOCK}. The reference
     * system writes this date TWICE from TWO clocks in one operator action -- the segment copy from the
     * transaction monitor's clock at {@code cbl/COPAUS2C.cbl} L91 to L101, and the fraud row's column from
     * the database server's clock at L194 on the insert and L225 on the update -- so the two can name
     * different days. The target reads the DATABASE's date once and derives both values from it, so they
     * cannot.</p>
     *
     * <p>Refactoring Rationale: the single read used to be of an injected application clock, which
     * collapsed the two values correctly and collapsed them onto the wrong SOURCE -- the reference takes
     * this date from the database server in both of its writes, and an application clock is a different
     * process with an independently configured zone. Reading the database once preserves the collapse this
     * test pins and puts it on the source the reference used.</p>
     *
     * <p>Assumptions: the two values are compared as DAYS and not as strings, because the two carry
     * deliberately different renderings of the same day -- the segment's eight characters are month first
     * and the row's column is a date -- so a string comparison would fail on a difference that is not the
     * one under test. The renderings themselves are pinned by the two tests above.</p>
     *
     * <p>Assumptions: the insert path is exercised rather than the update path, because the insert is where
     * the reference reads its two clocks furthest apart -- the segment copy is set on entry to the fraud
     * program and the column is evaluated when the insert statement executes.</p>
     */
    @Test
    @DisplayName("both report dates one operation writes are the same day")
    void bothReportDatesOneOperationWritesAreTheSameDay() {
        PendingAuthDetail row = givenExistingRow();
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

        this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        ArgumentCaptor<AuthFraud> inserted = ArgumentCaptor.forClass(AuthFraud.class);
        verify(this.fraudUpserts).upsert(inserted.capture());
        LocalDate rowDay = inserted.getValue().getFraudRptDate();
        LocalDate segmentDay = LocalDate.parse(row.getFraudReportDate(),
                DateTimeFormatter.ofPattern("MM/dd/yy"));
        assertThat(segmentDay).isEqualTo(rowDay);
        assertThat(rowDay).isEqualTo(REPORT_DATE);
    }

    /**
     * A selector that redeems to a key with no row behind it is not found rather than refused.
     *
     * <p>Assumptions: the two conditions are different and are reported differently. A selector that cannot
     * be redeemed is the caller's to fix; a selector that redeems to a key with no row describes an
     * authorization the expiry sweep has since removed, which nothing the caller sends can correct.</p>
     */
    @Test
    @DisplayName("a selector naming no row is not found")
    void selectorNamingNoRowIsNotFound() {
        when(this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));
        verifyNoInteractions(this.fraudUpserts);
    }

    /**
     * A row whose acquirer-supplied original date will not parse cannot be marked, and says so as a fault.
     *
     * <p>Assumptions: this state IS reachable. The migration records that the original date column is
     * acquirer-supplied and must tolerate a value that is blank or will not parse, which is why it stores
     * characters rather than a date. The caller of this operation supplied none of it, so it is reported as
     * a server-side condition rather than as the caller's mistake -- which is also the reference outcome,
     * whose composed string reaches Db2's conversion function and comes back as a system error the caller
     * rolls back at {@code cbl/COPAUS1C.cbl} L256 to L258.</p>
     */
    @Test
    @DisplayName("a row with an unparseable original date cannot be marked and reports a fault")
    void rowWithUnparseableOriginalDateCannotBeMarked() {
        when(this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.of(rowWithOriginalDate("      ")));

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));
        verify(this.fraudUpserts, never()).upsert(any(AuthFraud.class));
    }

    /**
     * A failure of the SECOND write leaves the first one to be rolled back with it, not committed.
     *
     * <p><strong>This is the divergence D-6 assertion.</strong> The reference system obtains this property
     * from a coordinator driving two resource managers to one syncpoint at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L557 and L558; the target obtains it from
     * both writes sharing one local transaction. The test forces the fraud row to be written and THEN the
     * authorization row's own write to fail, which is the ordering in which a partial commit would be
     * possible if the two writes did not share a boundary.</p>
     *
     * <p>Assumptions: two things together are what make the property hold, so both are asserted. The
     * exception must ESCAPE the method, because propagation is what marks the transaction for rollback --
     * rule T5 maps {@code SYNCPOINT ROLLBACK} onto exactly that, and a caught-and-reported failure would
     * commit the staged first write. And the method must carry a single {@link Transactional} declaration,
     * because propagation only rolls the first write back if that write was inside the same boundary. The
     * annotation is read reflectively rather than assumed: a unit test with repository doubles has no real
     * transaction to observe, so the declaration is the observable fact, and the boundary's runtime effect
     * is asserted against a live engine by this module's {@code *RepositoryIT}.</p>
     *
     * <p>Alternatives Considered: asserting only that the exception propagates. Rejected because
     * propagation alone is satisfied by a method with no transaction at all, which is precisely the state
     * this test exists to exclude -- the first write would then already have been committed on its own.</p>
     *
     * @throws NoSuchMethodException if the operation's signature changes without this assertion following
     *     it, which should fail the build rather than silently stop checking the boundary
     */
    @Test
    @DisplayName("a failure of the second write shares one boundary with the first, so neither survives")
    void aFailureOfTheSecondWriteRollsTheFirstBackWithIt() throws NoSuchMethodException {
        PendingAuthDetail row = spy(rowWithOriginalDate(AUTH_ORIG_DATE));
        when(this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.of(row));
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);
        // WHY : Assumptions: the second write is the authorization row's own state change, so the failure is
        //       injected there rather than on a repository double. Failing a repository would only prove
        //       that a mock throws; failing the entity operation reproduces the shape the reference guards
        //       against, where the relational write has already been issued when the hierarchical one does
        //       not complete.
        doThrow(new IllegalStateException("the authorization row refused the mark"))
                .when(row).applyFraudMark(any(), any());

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));

        verify(this.fraudUpserts).upsert(any(AuthFraud.class));
        assertThat(FraudMarkingService.class
                .getMethod("mark", String.class, FraudMarkRequest.class, String.class)
                .getAnnotation(Transactional.class))
                .as("both writes share the one transaction that divergence D-6 collapses them into")
                .isNotNull();
    }

    /**
     * The authorization row is re-read by key before either write is staged.
     *
     * <p>Assumptions: the ORDER is the assertion, not merely the presence of the read. The reference
     * replace at {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L525 to L528 is unqualified and
     * acts on the position its earlier retrieval established, so the target's fetch by composite key is
     * what stands in for that position. A write staged before the fetch would be operating on a row this
     * transaction had not established, and verifying the two calls without their order would pass either
     * way.</p>
     */
    @Test
    @DisplayName("the row is re-read by composite key before either write is staged")
    void theRowIsReReadByKeyBeforeEitherWriteIsStaged() {
        givenExistingRow();
        when(this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

        this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        InOrder sequence = inOrder(this.details, this.fraudUpserts);
        // WHY : Refactoring Rationale: this sequence was read-the-authorization, probe-the-fraud-row,
        //       write. The middle step no longer exists, and its removal is the point of the change rather
        //       than a casualty of it. What still has to hold -- and is what this test was really for -- is
        //       that the authorization is re-read BEFORE anything is written, because the row the write
        //       projects from is the row that read returned. A write ordered ahead of the read would
        //       project from stale state.
        sequence.verify(this.details).findWithLockById(any(PendingAuthDetailKey.class));
        sequence.verify(this.fraudUpserts).upsert(any(AuthFraud.class));
    }

    /**
     * Divergence D-6: the two writes the reference committed through a coordinator share one boundary.
     *
     * <p>Refactoring Rationale: the replaced approach could not keep one fraud marking consistent without
     * a distributed coordinator, because its two writes went to two resource managers -- the relational
     * fraud row inside the program linked at {@code cbl/COPAUS1C.cbl} L248 to L252, and the hierarchical
     * segment replaced by the caller at L525 to L528 -- and only the transaction monitor could drive both
     * to the one {@code EXEC CICS SYNCPOINT} at L558. Every test here asserts one property of the local
     * transaction that replaced it.</p>
     */
    @Nested
    @DisplayName("divergence D-6: one local transaction replaces the distributed commit")
    class DistributedCommitCollapse {

        /**
         * The fraud row is written before the authorization row, in the reference order.
         *
         * <p>Assumptions: the ORDER is asserted explicitly even though the shared transaction now makes
         * it irrelevant to what survives a failure. The reference caller replaces the segment ONLY after
         * the linked program reported success, testing the out-parameter at {@code cbl/COPAUS1C.cbl} L254
         * and performing the replace at L255, so this sequence is the one a reader comparing the two
         * systems should find. Asserting it pins the sequence against a future reorder that the
         * transaction would otherwise hide.</p>
         */
        @Test
        @DisplayName("the fraud row is written before the authorization row")
        void theFraudRowIsWrittenBeforeTheAuthorizationRow() {
            PendingAuthDetail row = spy(rowWithOriginalDate(AUTH_ORIG_DATE));
            when(FraudMarkingServiceTest.this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row));
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingServiceTest.this.service.mark(selector(),
                    requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            // WHY : Assumptions: the two writes are verified through ONE InOrder over the fraud writer and
            //       the authorization row itself, because the second write is a state change on the entity
            //       rather than a repository call. Two separate verifications would pass whichever way
            //       round the calls actually happened, which is exactly the regression this guards.
            InOrder sequence = inOrder(FraudMarkingServiceTest.this.fraudUpserts, row);
            sequence.verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(any(AuthFraud.class));
            sequence.verify(row).applyFraudMark(PendingAuthDetail.FRAUD_REPORTED,
                    SEGMENT_REPORT_DATE_TEXT);
        }

        /**
         * The fraud write cannot open a transaction of its own, so it can only join the caller's.
         *
         * <p>Assumptions: this is the structural half of D-6 and it is read from the declaration rather
         * than inferred from behaviour, because a double cannot demonstrate propagation. Mandatory
         * propagation means the write REFUSES to run without an inherited transaction, which is what
         * makes "both writes commit together" a property of the code and not of the test's arrangement. A
         * second boundary opened here is precisely how a two-phase commit creeps back in.</p>
         *
         * @throws NoSuchMethodException if the fraud writer's signature changes without this assertion
         *     following it, which should fail the build rather than silently stop checking propagation
         */
        @Test
        @DisplayName("the fraud write demands an inherited transaction rather than opening one")
        void theFraudWriteCannotOpenABoundaryOfItsOwn() throws NoSuchMethodException {
            Transactional onWrite = AuthFraudUpserter.class.getMethod("upsert", AuthFraud.class)
                    .getAnnotation(Transactional.class);

            assertThat(onWrite)
                    .as("the fraud write declares its transaction requirement")
                    .isNotNull();
            assertThat(onWrite.propagation())
                    .as("mandatory propagation is what forbids a second commit scope")
                    .isEqualTo(Propagation.MANDATORY);
            assertThat(FraudMarkingService.class
                    .getMethod("mark", String.class, FraudMarkRequest.class, String.class)
                    .getAnnotation(Transactional.class))
                    .as("the one boundary both writes join is declared on the operation")
                    .isNotNull();
        }

        /**
         * A failure of the first write leaves the authorization row untouched.
         *
         * <p>Assumptions: this is the reference sequential dependence, not merely a consequence of the
         * transaction. {@code cbl/COPAUS1C.cbl} L254 replaces the segment only when the linked program set
         * its success out-parameter, and L256 to L258 take the rollback path instead when it did not, so
         * the hierarchical write was never even attempted after a failed relational write. Asserting the
         * second write is never reached proves the target keeps that dependence rather than relying on the
         * rollback to undo a write it should not have made.</p>
         */
        @Test
        @DisplayName("a failed fraud write means the authorization row is never touched")
        void aFailureOfTheFirstWriteLeavesTheAuthorizationRowUntouched() {
            PendingAuthDetail row = spy(rowWithOriginalDate(AUTH_ORIG_DATE));
            when(FraudMarkingServiceTest.this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row));
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class)))
                    .thenThrow(new IllegalStateException("the fraud row was refused"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FraudMarkingServiceTest.this.service.mark(selector(),
                            requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));

            verify(row, never()).applyFraudMark(any(), any());
        }

        /**
         * No compensating reversal or second commit exists on the failure path.
         *
         * <p>Alternatives Considered: reversing the fraud row in a catch block when the segment write
         * fails, which is what a saga would do and what the reference tree's coordinator made
         * unnecessary. Rejected because a reversal has to COMMIT the first write before it can undo it,
         * so a reader could observe a fraud report the operator never completed. Asserting that the
         * failure reaches the caller with no further interaction is what excludes that design: a
         * compensating write would show up here as an extra call on the fraud writer.</p>
         */
        @Test
        @DisplayName("a failure propagates with no compensating write attempted")
        void noCompensatingReversalIsAttempted() {
            PendingAuthDetail row = spy(rowWithOriginalDate(AUTH_ORIG_DATE));
            when(FraudMarkingServiceTest.this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row));
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);
            doThrow(new IllegalStateException("the authorization row refused the mark"))
                    .when(row).applyFraudMark(any(), any());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FraudMarkingServiceTest.this.service.mark(selector(),
                            requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));

            // WHY : Assumptions: exactly ONE call to the fraud writer is the assertion. A second call
            //       would be either a retry or a reversal, and both are excluded on this path -- the
            //       reference tree declares RESTART(NO) on the transaction and reaches its rollback
            //       through the monitor at cbl/COPAUS1C.cbl L566 to L568, never through a second write.
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(any(AuthFraud.class));
            verifyNoMoreInteractions(FraudMarkingServiceTest.this.fraudUpserts);
        }

        /**
         * The reference rollback sentence is preserved verbatim and is not emitted as a success body.
         *
         * <p>Assumptions: the target does not COMPOSE this sentence, and that is the point of asserting
         * its exact shape here rather than asserting a response that carries it. The reference caller
         * builds it into the screen's own message field on its rollback arm --
         * {@code cbl/COPAUS1C.cbl} L540 rolls back, L542 raises the error flag, the {@code STRING} at L544
         * to L549 composes the text from the literal at L545 plus the DL/I status, and L550 re-sends the
         * screen. The target instead propagates its exception, so the transaction rolls back and the
         * shared problem shape renders the failure; no success body is produced at all. Under AAP Rule T8
         * (user-visible strings verbatim) the literal is nonetheless recorded character for character, so
         * the leading space and the trailing bars survive for whoever renders the failure.</p>
         */
        @Test
        @DisplayName("the reference rollback sentence survives character for character")
        void theRollbackSentenceIsCarriedVerbatimAndNeverReturnedAsSuccess() {
            // WHY : Assumptions: the three properties asserted are the three a paraphrase would break,
            //       which is why they are asserted individually rather than by one equality against a
            //       second copy of the same literal. A single equality against a duplicate of the string
            //       would pass even if BOTH copies drifted from L545 together.
            assertThat(REFERENCE_ROLLBACK_SENTENCE)
                    .as("L545 opens with exactly one space before the word System")
                    .startsWith(" S")
                    .as("L545 closes with two vertical bars, which the status code is appended after")
                    .endsWith("ROLLBACK||")
                    .as("the reference wording keeps FRAUD upper case and Tagging capitalised")
                    .contains("while FRAUD Tagging,")
                    .hasSize(45);

            assertThat(REFERENCE_ROLLBACK_SENTENCE)
                    .as("the rollback text is a failure sentence and is never an outcome message")
                    .isNotEqualTo(FraudMarkResponse.MESSAGE_ADD_SUCCESS)
                    .isNotEqualTo(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
        }
    }

    /**
     * The fraud action is a state-setting operation with two published directions, not a one-way mark.
     *
     * <p>Assumptions: the requested action carries the TARGET STATE rather than a verb. The reference
     * program stores whatever character it is handed, moving it straight into the fraud column at
     * {@code cbl/COPAUS2C.cbl} L137, and its two admitted values are declared beside each other at L80 to
     * L82 -- {@code 'F'} reports a fraud and {@code 'R'} removes one. A test covering only the reporting
     * direction would assert half the contract.</p>
     */
    @Nested
    @DisplayName("the fraud action sets a target state in either direction")
    class FraudActionDirections {

        /**
         * Both published directions are accepted and each reaches both rows.
         *
         * <p>Assumptions: the reference caller derives the direction by INVERTING the state it read --
         * {@code cbl/COPAUS1C.cbl} L236 to L242 sets the removing character when the row is already
         * confirmed and the reporting character otherwise -- whereas the target is handed the direction.
         * That is documented divergence D-AUTH-FRAUD-TARGET-STATE, and the reason is the transport: a
         * request naming a target state is idempotent under a network retry, while a toggle reversed by a
         * retry would withdraw a fraud tag the operator asked for and report success either way. What both
         * systems agree on, and what this test asserts, is that either character is a legitimate outcome
         * and lands identically on the fraud row and on the authorization row.</p>
         *
         * @param action the {@code String} fraud character to request, either the reporting or the removing
         *     one
         */
        @ParameterizedTest(name = "action {0} is stored on both rows")
        @ValueSource(strings = {PendingAuthDetail.FRAUD_REPORTED, PendingAuthDetail.FRAUD_REMOVED})
        @DisplayName("either published direction is stored on both rows")
        void bothPublishedDirectionsAreStoredOnBothRows(String action) {
            PendingAuthDetail row = givenExistingRow();
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingServiceTest.this.service.mark(selector(), requestWith(action), SUBJECT);

            assertThat(row.getAuthFraud())
                    .as("the authorization row ends in the state the request named")
                    .isEqualTo(action);
            ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(proposed.capture());
            assertThat(proposed.getValue().getAuthFraud())
                    .as("the fraud row carries the same state, as the reference move at L137 does")
                    .isEqualTo(action);
        }

        /**
         * An action outside the closed two-character domain is refused and nothing is written.
         *
         * <p>Assumptions: the refusal happens BEFORE either write, so an out-of-domain character can never
         * be stored. The reference program has no equivalent refusal at all -- it moves whatever byte its
         * caller placed in the communication area straight into the column at {@code cbl/COPAUS2C.cbl}
         * L137, and the only thing standing between a stray byte and the table is that its sole caller
         * sets the field from a condition name. The target has a published route and therefore an
         * untrusted caller, so the domain declared at L81 and L82 is enforced rather than assumed.</p>
         *
         * @param action the {@code String} token outside the published domain: a foreign letter, either
         *     admitted letter in the wrong case, both letters together, and a digit
         */
        @ParameterizedTest(name = "action \"{0}\" is refused and writes nothing")
        @ValueSource(strings = {"X", "f", "r", "FR", "0"})
        @DisplayName("an action outside the published domain is refused before any write")
        void anActionOutsideTheClosedDomainIsRefusedAndWritesNothing(String action) {
            PendingAuthDetail row = givenExistingRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FraudMarkingServiceTest.this.service.mark(selector(),
                            requestWith(action), SUBJECT));

            verifyNoInteractions(FraudMarkingServiceTest.this.fraudUpserts);
            assertThat(row.getAuthFraud())
                    .as("the authorization row keeps the state it was read with")
                    .isNull();
        }

        /**
         * The requested action and the reported outcome stay separate domains despite sharing a letter.
         *
         * <p>Assumptions: {@code 'F'} means two unrelated things in two ADJACENT bytes of the reference
         * communication area -- report a fraud in {@code WS-FRD-ACTION} at {@code cbl/COPAUS2C.cbl} L81,
         * and update FAILED in {@code WS-FRD-UPDATE-STATUS} at L85. Folding the two into one field or one
         * type would let a correct-looking character be read as the wrong thing entirely, so the target
         * keeps them on two types: the request carries the action and the response carries the outcome.
         * The response's own domain admits only the success character declared at L84, which is what makes
         * the collision unreachable in the target rather than merely avoided by convention.</p>
         */
        @Test
        @DisplayName("the action letter F and the outcome letter F belong to two separate types")
        void theRequestActionAndTheOutcomeStatusAreSeparateDomains() {
            givenExistingRow();
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingService.FraudMarkOutcome outcome = FraudMarkingServiceTest.this.service
                    .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            assertThat(PendingAuthDetail.FRAUD_REPORTED)
                    .as("the two domains really do share the letter, which is why they must stay apart")
                    .isEqualTo("F");
            assertThat(outcome.body().updateStatus())
                    .as("a successful mark reports the success character from L84, never the action")
                    .isEqualTo(FraudMarkResponse.UPDATE_STATUS_SUCCESS)
                    .isNotEqualTo(PendingAuthDetail.FRAUD_REPORTED);
        }

        /**
         * The screen confirmation is chosen by the state reached, and both wordings are published verbatim.
         *
         * <p>Assumptions: these two sentences are NOT members of this service's response and are not
         * copied into this class. The reference caller selects between them from the RESULTING state --
         * {@code cbl/COPAUS1C.cbl} L534 tests whether the row is now the removed state and takes
         * {@code 'AUTH FRAUD REMOVED...'} at L535, otherwise {@code 'AUTH MARKED FRAUD...'} at L537 -- and
         * in the target they belong to the published contract and the interface message catalogue. This
         * test therefore READS the owning contract and asserts both wordings and their pairing there,
         * which satisfies AAP Rule T8 (user-visible strings verbatim) without creating a third copy that
         * could drift from the two that are actually rendered. Reading a source as the subject of an
         * assertion is the established pattern in this package for exactly this reason.</p>
         *
         * @throws IOException if the packaged contract cannot be read from the test class path, which
         *     should fail the build rather than silently skip the wording check
         */
        @Test
        @DisplayName("both confirmation wordings are published and paired with the resulting state")
        void theScreenConfirmationIsSelectedByTheResultingState() throws IOException {
            String contract = readContract();

            assertThat(contract)
                    .as("the removed wording is carried verbatim, trailing ellipsis included")
                    .contains("'AUTH FRAUD REMOVED...'")
                    .as("the reported wording is carried verbatim, trailing ellipsis included")
                    .contains("'AUTH MARKED FRAUD...'");

            // WHY : Assumptions: the PAIRING is asserted as well as the presence of the two strings,
            //       because the reference selects them from the resulting state and a contract that
            //       published both while describing them the wrong way round would still contain both.
            //       The removed wording has to be the one described against a removed report.
            int removedAt = contract.indexOf("'AUTH FRAUD REMOVED...'");
            int reportedAt = contract.indexOf("'AUTH MARKED FRAUD...'");
            assertThat(removedAt).as("the removed wording is documented").isNotNegative();
            assertThat(reportedAt).as("the reported wording is documented").isNotNegative();
            assertThat(contract.substring(removedAt, reportedAt))
                    .as("the removed wording is the one paired with a report having been removed")
                    .contains("removed");
        }
    }

    /**
     * The insert-versus-update discriminator, and the write footprint each arm is allowed.
     *
     * <p>Alternatives Considered: how to reproduce a branch the reference takes on a Db2 error code when
     * no such code exists in the target. {@code cbl/COPAUS2C.cbl} L199 tests for a clean insert and L203
     * tests for {@code SQLCODE = -803}, the duplicate-key condition, branching to its update paragraph.
     * PostgreSQL has NO equivalent signal: {@code INSERT ... ON CONFLICT ... DO UPDATE} succeeds on both
     * paths and says nothing about which one it took. Three options were weighed. Attempting the insert
     * and catching the constraint violation was rejected because the failing statement puts the
     * transaction into an aborted state unless a savepoint wraps it, which is the very cost
     * {@code ON CONFLICT} exists to avoid, and this transaction has a second write still to make. Reading
     * the row first and branching on its presence was rejected because it is a race: two markings of one
     * authorization both see an absent row, both take the insert arm, and the loser is refused at commit.
     * Collapsing the two outcomes into one message was rejected because both strings are user-visible and
     * AAP Rule T8 (user-visible strings verbatim) forbids losing either. What is used instead is one
     * statement that RETURNS an explicit discriminator, so the branch survives with a single round trip
     * and no race.</p>
     */
    @Nested
    @DisplayName("the insert-versus-update branch and each arm's write footprint")
    class InsertVersusUpdateDiscriminator {

        /**
         * Both outcome wordings survive character for character, with the abbreviation unexpanded.
         *
         * <p>Assumptions: the reference program reports {@code 'ADD SUCCESS'} on the insert arm at
         * {@code cbl/COPAUS2C.cbl} L201 and {@code 'UPDT SUCCESS'} on the duplicate-key update arm at
         * L232. The second is ABBREVIATED in the baseline and must not be tidied into {@code UPDATE
         * SUCCESS}: under AAP Rule T8 (user-visible strings verbatim) the operator sees the characters the
         * reference produced, and a four-letter stem is the whole difference. Asserting the absence of the
         * expanded form is what catches a well-meant correction that an equality against a second copy of
         * the same typo would not.</p>
         */
        @Test
        @DisplayName("ADD SUCCESS and UPDT SUCCESS are verbatim, with UPDT left abbreviated")
        void bothOutcomeWordingsSurviveCharacterForCharacter() {
            assertThat(FraudMarkResponse.MESSAGE_ADD_SUCCESS)
                    .as("the insert wording from L201")
                    .isEqualTo("ADD SUCCESS");
            assertThat(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS)
                    .as("the update wording from L232, abbreviated as the baseline abbreviates it")
                    .isEqualTo("UPDT SUCCESS")
                    .as("the abbreviation is not expanded to the full word")
                    .doesNotContain("UPDATE");
        }

        /**
         * The discriminator the statement returns selects which wording the caller reads.
         *
         * <p>Assumptions: the boolean the fraud write answers with is the whole of the branch, standing in
         * for the reference test of {@code SQLCODE} at {@code cbl/COPAUS2C.cbl} L199 and L203. It is
         * derived in the statement itself from the row's creation marker rather than from a preceding read,
         * which is what makes it race-free. This test drives both answers and asserts the wording follows
         * each, so a discriminator wired backwards fails here rather than surfacing as an operator seeing
         * the wrong sentence.</p>
         *
         * @param created the {@code boolean} answer the fraud write returns, {@code true} for a created row
         * @param expectedMessage the {@code String} wording the caller must then read
         */
        @ParameterizedTest(name = "created={0} reports {1}")
        @CsvSource({"true,ADD SUCCESS", "false,UPDT SUCCESS"})
        @DisplayName("the returned discriminator selects the reference wording for that arm")
        void theDiscriminatorSelectsTheWording(boolean created, String expectedMessage) {
            givenExistingRow();
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class)))
                    .thenReturn(created);

            FraudMarkingService.FraudMarkOutcome outcome = FraudMarkingServiceTest.this.service
                    .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            assertThat(outcome.created())
                    .as("the created flag is carried out to the controller for the status code")
                    .isEqualTo(created);
            assertThat(outcome.body().message())
                    .as("the wording follows the arm the statement reported")
                    .isEqualTo(expectedMessage);
        }

        /**
         * The replace arm proposes the authorization's own values for the other twenty-four columns.
         *
         * <p>Trade-offs: the reference update names exactly TWO columns in its {@code SET} list --
         * {@code AUTH_FRAUD} at {@code cbl/COPAUS2C.cbl} L224 and {@code FRAUD_RPT_DATE} at L225 -- so a
         * row marked twice still describes the authorization as it stood at the FIRST report, and the other
         * twenty-four of the twenty-six columns {@code ddl/AUTHFRDS.ddl} declares at L2 to L27 keep the
         * values they were inserted with. Preserving that footprint costs a hand-written native statement
         * with its own conflict clause instead of a derived-query save, which is more code and cannot be
         * checked by the query deriver; the alternative would have clobbered twenty-four columns on every
         * re-mark and lost the historical snapshot that makes taking it worth anything.</p>
         *
         * <p>Assumptions: what this tier can assert is that the SERVICE proposes no change to any of the
         * twenty-four -- each is projected straight from the authorization being marked, so nothing is
         * defaulted, blanked or recomputed on the way. The narrowing of the statement's own {@code SET}
         * list is a property of the SQL and is asserted against a real engine one tier up, because no
         * double can settle what a conflict clause did.</p>
         */
        @Test
        @DisplayName("the replace arm proposes the authorization's own values for the other 24 columns")
        void theReplaceArmLeavesTheOtherTwentyFourColumnsAsTheAuthorizationHoldsThem() {
            PendingAuthDetail row = givenExistingRow();
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class)))
                    .thenReturn(false);

            FraudMarkingServiceTest.this.service.mark(selector(),
                    requestWith(PendingAuthDetail.FRAUD_REMOVED), SUBJECT);

            ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(proposed.capture());
            AuthFraud fraudRow = proposed.getValue();

            // WHY : Assumptions: the twenty-four are enumerated one by one rather than compared through a
            //       whole-object equality, because the two types are deliberately different shapes -- the
            //       fraud row flattens the composite key into a card number and a composed timestamp, and
            //       renders the processing code as characters. An object comparison could not express
            //       "these twenty-four agree and those two are permitted to differ", which is exactly the
            //       claim the reference SET list makes.
            assertThat(fraudRow.getId().getCardNum()).as("card_num").isEqualTo(row.getCardNum());
            assertThat(fraudRow.getId().getAuthTs()).as("auth_ts").isEqualTo(expectedAuthTs());
            assertThat(fraudRow.getAuthType()).as("auth_type").isEqualTo(row.getAuthType());
            assertThat(fraudRow.getCardExpiryDate()).as("card_expiry_date")
                    .isEqualTo(row.getCardExpiryDate());
            assertThat(fraudRow.getMessageType()).as("message_type").isEqualTo(row.getMessageType());
            assertThat(fraudRow.getMessageSource()).as("message_source")
                    .isEqualTo(row.getMessageSource());
            assertThat(fraudRow.getAuthIdCode()).as("auth_id_code").isEqualTo(row.getAuthIdCode());
            assertThat(fraudRow.getAuthRespCode()).as("auth_resp_code")
                    .isEqualTo(row.getAuthRespCode());
            assertThat(fraudRow.getAuthRespReason()).as("auth_resp_reason")
                    .isEqualTo(row.getAuthRespReason());
            assertThat(fraudRow.getProcessingCode()).as("processing_code")
                    .isEqualTo(row.getProcessingCode());
            assertThat(fraudRow.getTransactionAmt()).as("transaction_amt")
                    .isEqualTo(row.getTransactionAmount());
            assertThat(fraudRow.getApprovedAmt()).as("approved_amt")
                    .isEqualTo(row.getApprovedAmount());
            assertThat(fraudRow.getMerchantCategoryCode()).as("merchant_category_code")
                    .isEqualTo(row.getMerchantCategoryCode());
            assertThat(fraudRow.getAcqrCountryCode()).as("acqr_country_code")
                    .isEqualTo(row.getAcqrCountryCode());
            assertThat(fraudRow.getPosEntryMode()).as("pos_entry_mode")
                    .isEqualTo(row.getPosEntryMode());
            assertThat(fraudRow.getMerchantId()).as("merchant_id").isEqualTo(row.getMerchantId());
            assertThat(fraudRow.getMerchantName()).as("merchant_name").isEqualTo(row.getMerchantName());
            assertThat(fraudRow.getMerchantCity()).as("merchant_city").isEqualTo(row.getMerchantCity());
            assertThat(fraudRow.getMerchantState()).as("merchant_state")
                    .isEqualTo(row.getMerchantState());
            assertThat(fraudRow.getMerchantZip()).as("merchant_zip").isEqualTo(row.getMerchantZip());
            assertThat(fraudRow.getTransactionId()).as("transaction_id")
                    .isEqualTo(row.getTransactionId());
            assertThat(fraudRow.getMatchStatus()).as("match_status").isEqualTo(row.getMatchStatus());
            assertThat(fraudRow.getAcctId()).as("acct_id").isEqualTo(ACCOUNT_ID);
            assertThat(fraudRow.getCustId()).as("cust_id")
                    .isEqualTo(Long.valueOf(CUSTOMER_ID_DIGITS));

            // WHY : Assumptions: the count is asserted so the enumeration above cannot silently fall out of
            //       step with the table. Twenty-six columns are declared at ddl/AUTHFRDS.ddl L2 to L27 and
            //       the reference SET list names two, so twenty-four is the remainder -- and if a column is
            //       ever added to the table, this arithmetic is what fails and sends a reader to the SET
            //       list to decide which side the new column belongs on.
            assertThat(AUTHFRDS_COLUMN_COUNT - REPLACE_ARM_COLUMN_COUNT)
                    .as("the columns the replace arm must leave alone")
                    .isEqualTo(24);
        }
    }

    /**
     * Field-level fidelity: declared widths, exact money, and an unambiguous composed key.
     */
    @Nested
    @DisplayName("field-level fidelity of the row the fraud write proposes")
    class RecordFidelity {

        /**
         * A merchant name keeps its trailing blanks, because the reference length is a constant.
         *
         * <p>Assumptions: the reference program moves the DECLARED length and never a trimmed one --
         * {@code cbl/COPAUS2C.cbl} L130 moves {@code LENGTH OF PA-MERCHANT-NAME}, which is the constant 22
         * from {@code cpy/CIPAUDTY.cpy} L40, unconditionally and before the value itself is moved at L131.
         * The variable-length column at {@code ddl/AUTHFRDS.ddl} L18 therefore receives all 22 characters
         * including the padding, and the target must not helpfully trim: a trimmed value would compare
         * unequal to every row the reference wrote for the same merchant, on the only variable-length
         * column in the table.</p>
         */
        @Test
        @DisplayName("a merchant name reaches the column with its trailing blanks intact")
        void theMerchantNameKeepsItsTrailingBlanks() {
            PendingAuthDetail row = rowWithMerchantName(MERCHANT_NAME_WITH_TRAILING_BLANKS);
            when(FraudMarkingServiceTest.this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row));
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingServiceTest.this.service.mark(selector(),
                    requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(proposed.capture());
            assertThat(proposed.getValue().getMerchantName())
                    .as("all 22 declared positions survive, padding included")
                    .isEqualTo(MERCHANT_NAME_WITH_TRAILING_BLANKS)
                    .hasSize(22)
                    .as("the trailing blanks are not trimmed away")
                    .endsWith(" ");
        }

        /**
         * Both amounts stay exact fixed point at scale two and never pass through binary floating point.
         *
         * <p>Assumptions: the two money columns are declared {@code DECIMAL(12,2)} at
         * {@code ddl/AUTHFRDS.ddl} L12 and L13, and their hierarchical originals are
         * {@code PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 and L35 -- packed decimal, seven
         * bytes each, decoded once at the extract boundary and never stored as packed bytes in a column.
         * AAP Rule T3 requires the value to stay exact at every hop, so the scale and the rounding mode are
         * asserted against the shared kernel's own declarations rather than restated as literals here. The
         * architecture rule that forbids binary floating point is scoped to the shared money package, so it
         * does NOT reach this module by inheritance, which is why the property is asserted here
         * directly.</p>
         */
        @Test
        @DisplayName("both amounts stay exact at scale two, with no binary floating point on the path")
        void theAmountsStayExactFixedPointAtScaleTwo() {
            PendingAuthDetail row = givenExistingRow();
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingServiceTest.this.service.mark(selector(),
                    requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(proposed.capture());
            AuthFraud fraudRow = proposed.getValue();

            assertThat(fraudRow.getTransactionAmt())
                    .as("the amount is carried as an exact decimal, not a binary approximation")
                    .isInstanceOf(BigDecimal.class)
                    .isEqualByComparingTo(row.getTransactionAmount());
            assertThat(fraudRow.getTransactionAmt().scale())
                    .as("scale two, as the shared kernel declares for money")
                    .isEqualTo(Money.SCALE);
            assertThat(fraudRow.getApprovedAmt().scale())
                    .as("the approved amount is held at the same scale")
                    .isEqualTo(Money.SCALE);
            assertThat(Money.GENERAL_ROUNDING)
                    .as("half-up is the rounding the migration fixes for money")
                    .isEqualTo(RoundingMode.HALF_UP);

            // WHY : Assumptions: this service echoes NO money back, which is why there is no
            //       serialise-as-string assertion here to pair with the scale ones. The response type
            //       carries only the outcome character and the outcome sentence, so the transport rule that
            //       money crosses the wire as a JSON string has nothing to bind to on this path; the two
            //       amounts travel inward only. Asserting the absence keeps a future reader from adding an
            //       amount to the body without also giving it the string treatment.
            assertThat(FraudMarkResponse.class.getRecordComponents())
                    .as("no money member exists on the response this operation returns")
                    .noneMatch(component -> BigDecimal.class.equals(component.getType()));
        }

        /**
         * The composed fraud key resolves a century explicitly, so it cannot be ambiguous.
         *
         * <p>Assumptions: the reference predicate parses a TWO-DIGIT YEAR --
         * {@code AUTH_TS = TIMESTAMP_FORMAT (:AUTH-TS, 'YY-MM-DD HH24.MI.SSNNNNNN')} in the update at
         * {@code cbl/COPAUS2C.cbl} L222 to L229, whose mask literal is on L228 -- and a two-digit year
         * leaves the century to whatever pivot the database engine happens to apply. That mask is
         * deliberately NOT reproduced: the target keys on a real timestamp column and never parses a
         * string to find the row, and where a two-digit originating year does still arrive from the
         * acquirer it is widened by a pivot stated explicitly in the mapper. This test drives the two
         * years either side of that pivot and asserts they resolve to two different centuries and two
         * distinct keys, so the ambiguity is closed rather than inherited.</p>
         *
         * @param originalDate the {@code String} six-character acquirer-supplied originating date to store
         * @param expectedYear the {@code int} four-digit calendar year the composed key must resolve it
         *     to
         */
        @ParameterizedTest(name = "originating year in {0} resolves to {1}")
        @CsvSource({"690101,2069", "700101,1970"})
        @DisplayName("a two-digit originating year is widened to an explicit century")
        void theComposedKeyResolvesACenturyExplicitly(String originalDate, int expectedYear) {
            PendingAuthDetail row = rowWithOriginalDate(originalDate);
            when(FraudMarkingServiceTest.this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row));
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingServiceTest.this.service.mark(
                    FraudMarkingServiceTest.this.mapper.toRowView(row, SUBJECT).key(),
                    requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(proposed.capture());
            assertThat(proposed.getValue().getId().getAuthTs().getYear())
                    .as("the century is decided by a stated pivot, not by an engine's default")
                    .isEqualTo(expectedYear);
        }

        /**
         * The fraud row's key is the pair the reference constraint and its browse index are built on.
         *
         * <p>Assumptions: the pair is the card number and the composed timestamp, in that order.
         * {@code ddl/AUTHFRDS.ddl} L28 declares them as the primary key, which is what lets the reference
         * program reach its update arm on the duplicate-key condition at all, and
         * {@code ddl/XAUTHFRD.ddl} declares a unique index over the same two columns with the card number
         * ASCENDING and the timestamp DESCENDING. That direction is not decoration: it is what makes a
         * per-card newest-first browse index-served, and the migration keeps both the column order and the
         * descending sense on the equivalent index in this context's schema. The index itself is a
         * property of the migration rather than of this service, so what is asserted here is that the
         * service composes exactly that pair as the row's identity.</p>
         */
        @Test
        @DisplayName("the fraud row is identified by the card number and the composed timestamp")
        void theFraudRowKeyIsTheCardNumberAndComposedTimestamp() {
            givenExistingRow();
            when(FraudMarkingServiceTest.this.fraudUpserts.upsert(any(AuthFraud.class))).thenReturn(true);

            FraudMarkingServiceTest.this.service.mark(selector(),
                    requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

            ArgumentCaptor<AuthFraud> proposed = ArgumentCaptor.forClass(AuthFraud.class);
            verify(FraudMarkingServiceTest.this.fraudUpserts).upsert(proposed.capture());
            assertThat(proposed.getValue().getId().getCardNum())
                    .as("the leading key column is the card number the authorization carries")
                    .isEqualTo(CARD_NUMBER);
            assertThat(proposed.getValue().getId().getAuthTs())
                    .as("the trailing key column is the composed timestamp the browse orders by")
                    .isEqualTo(expectedAuthTs());
        }
    }

    /**
     * Who may perform the write, and where that decision is taken.
     */
    @Nested
    @DisplayName("the authority decision is taken before this service is reached")
    class AuthorityOwnership {

        /**
         * This service takes no authority decision, so a refusal necessarily precedes both writes.
         *
         * <p>Assumptions: the guard is a ROUTE rule and not a service rule. It is declared once, on
         * {@code config/SecurityConfig}, which matches the fraud route against a decision requiring one of
         * the signed group authorities that the shared kernel's claim converter mints; the decision itself
         * is asserted by that configuration's own test, which drives both admitted groups and an
         * unrecognised one. Because the rule lives in the filter chain, a refused caller never reaches the
         * handler at all -- which is what makes "the refusal precedes any write" structural rather than
         * ordered. This test therefore asserts the property that makes that true here: the operation
         * accepts no authority, role or group argument, so no authority decision CAN be taken at this
         * layer and none is duplicated from the route rule.</p>
         *
         * <p>Assumptions: the subject the operation does accept is not an authority. It is the principal
         * the sealed selector was issued to, used only to redeem that selector, so a caller cannot widen
         * its own permissions by changing it -- the seal would simply fail to open. This is the deliberate
         * replacement for the reference session field a client echoed back, which was ordinary storage the
         * client could state for itself; the group claim behind the route rule is signed and the client
         * cannot assert it. No assertion here reads a client-supplied user type, because the target has
         * none to read.</p>
         *
         * @throws NoSuchMethodException if the operation's signature changes without this assertion
         *     following it, which should fail the build rather than silently stop checking the parameters
         */
        @Test
        @DisplayName("the operation accepts no authority argument, so the route rule decides alone")
        void theServiceTakesNoAuthorityDecision() throws NoSuchMethodException {
            Method operation = FraudMarkingService.class
                    .getMethod("mark", String.class, FraudMarkRequest.class, String.class);

            assertThat(operation.getParameterTypes())
                    .as("only a selector, a body and a subject reach this layer")
                    .containsExactly(String.class, FraudMarkRequest.class, String.class);

            // WHY : Assumptions: the route rule is named here rather than re-implemented, because a second
            //       copy of an authorization rule is a second place it can differ from the one that
            //       actually runs -- and a reader meeting a refusal could no longer tell which of the two
            //       produced it. What is asserted is only that the route this operation sits behind is
            //       guarded by a decision over the signed GROUP authorities, which is the fact this
            //       service depends on.
            assertThat(SecurityConfig.FRAUD_PATH_PATTERN)
                    .as("the guarded route is the fraud sub-resource of an authorization")
                    .endsWith("/fraud");
            assertThat(SecurityConfig.fraudAccess())
                    .as("a decision is installed for that route rather than left to a catch-all")
                    .isNotNull();
            assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                    .as("the admitted authorities are the signed group claims, not a client-stated type")
                    .containsExactlyInAnyOrder(JwtRoleConverter.ADMIN_AUTHORITY,
                            JwtRoleConverter.USER_AUTHORITY);
        }
    }

    /**
     * Reads the packaged contract of record for this context from the test class path.
     *
     * @return the whole contract document as a {@code String}, never {@code null}
     * @throws IOException if the resource is missing from the class path or cannot be read, which should
     *     fail the build rather than let a wording check pass vacuously
     */
    private static String readContract() throws IOException {
        try (InputStream source = FraudMarkingServiceTest.class
                .getResourceAsStream(CONTRACT_RESOURCE)) {
            if (source == null) {
                throw new IOException("the packaged contract is absent from the test class path: "
                        + CONTRACT_RESOURCE);
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Builds the canonical authorization row carrying a stated merchant name.
     *
     * <p>Assumptions: every other member is the canonical row's, so a merchant-name assertion cannot be
     * satisfied or broken by an unrelated field changing.</p>
     *
     * @param merchantName the {@code String} value to store in the 22-position merchant-name field
     * @return the {@link PendingAuthDetail} authorization row carrying that merchant name, never
     *     {@code null}
     */
    private static PendingAuthDetail rowWithMerchantName(String merchantName) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                AUTH_ORIG_DATE, "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", merchantName,
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Arranges the re-read by key to return the canonical authorization row.
     *
     * @return the {@link PendingAuthDetail} row the service will mark, retained so its post-state can be
     *     asserted; never {@code null}
     */
    private PendingAuthDetail givenExistingRow() {
        PendingAuthDetail row = rowWithOriginalDate(AUTH_ORIG_DATE);
        when(this.details.findWithLockById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.of(row));
        return row;
    }

    /**
     * Builds the request body naming the canonical row with one stated action.
     *
     * @param action the {@code String} fraud state to set
     * @return the {@link FraudMarkRequest} body naming that action, never {@code null}
     */
    private static FraudMarkRequest requestWith(String action) {
        return new FraudMarkRequest(action);
    }

    /**
     * The timestamp the fraud key must carry for the canonical row.
     *
     * <p>Assumptions: written as an explicit literal rather than computed from the same expression the
     * service uses, so the assertion is independent of the implementation it checks.</p>
     *
     * @return the {@link LocalDateTime} 2026-08-03 at 09:16:44 and 902 milliseconds, never {@code null}
     */
    private static LocalDateTime expectedAuthTs() {
        return LocalDateTime.of(2026, 8, 3, 9, 16, 44, 902_000_000);
    }

    /**
     * Seals the selector naming the canonical row.
     *
     * <p>Assumptions: minted through the REAL mapper by rendering the row and taking its selector, so the
     * part order and separator are the mapper's rather than restated here.</p>
     *
     * @return the sealed selector as a {@code String} the service can redeem, never {@code null}
     */
    private String selector() {
        return this.mapper.toRowView(rowWithOriginalDate(AUTH_ORIG_DATE), SUBJECT).key();
    }

    /**
     * Builds the canonical authorization row with a stated original date.
     *
     * @param originalDate the {@code String} six characters to store as the acquirer-supplied original
     *     date
     * @return the {@link PendingAuthDetail} fully populated authorization row, never {@code null}
     */
    private static PendingAuthDetail rowWithOriginalDate(String originalDate) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                originalDate, "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }
}
