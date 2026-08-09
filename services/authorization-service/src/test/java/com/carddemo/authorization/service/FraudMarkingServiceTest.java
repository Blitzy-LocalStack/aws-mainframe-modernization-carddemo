package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.AuthFraudRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.web.CursorToken;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    /** The authorization repository double. */
    private PendingAuthDetailRepository details;

    /**
     * The parent-summary repository the service reads the fraud row's customer identifier from.
     */
    private PendingAuthSummaryRepository summaries;

    /** The fraud-row repository double. */
    private AuthFraudRepository fraudRows;

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
     * rather than left to {@code verify(save(any()))}, because they are the only two of the twenty-six that
     * are NOT copied from the segment being marked. The account must be the one the redeemed key names and
     * the customer must be the one the parent summary holds; a row filed against either a caller-stated or
     * a defaulted identifier would still satisfy an {@code any()} verification.</p>
     */
    @Test
    @DisplayName("a first mark inserts the fraud row and reports the reference insert sentence")
    void firstMarkInsertsTheFraudRow() {
        givenExistingRow();
        when(this.fraudRows.findById(any(AuthFraudKey.class))).thenReturn(Optional.empty());

        FraudMarkingService.FraudMarkOutcome outcome =
                this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.body().updateStatus())
                .isEqualTo(FraudMarkResponse.UPDATE_STATUS_SUCCESS);
        assertThat(outcome.body().message()).isEqualTo(FraudMarkResponse.MESSAGE_ADD_SUCCESS);
        ArgumentCaptor<AuthFraud> inserted = ArgumentCaptor.forClass(AuthFraud.class);
        verify(this.fraudRows).save(inserted.capture());
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
        when(this.fraudRows.findById(any(AuthFraudKey.class))).thenReturn(Optional.empty());
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));
        verify(this.fraudRows, never()).save(any(AuthFraud.class));
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
    @DisplayName("a second mark replaces the state on the existing row and reports the update sentence")
    void secondMarkReplacesTheExistingRow() {
        PendingAuthDetail row = givenExistingRow();
        AuthFraud existing = AuthFraud.from(row, expectedAuthTs(), ACCOUNT_ID, 11L,
                PendingAuthDetail.FRAUD_REPORTED, LocalDate.of(2026, 8, 4));
        when(this.fraudRows.findById(any(AuthFraudKey.class))).thenReturn(Optional.of(existing));

        FraudMarkingService.FraudMarkOutcome outcome =
                this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REMOVED), SUBJECT);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.body().message()).isEqualTo(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
        assertThat(existing.getAuthFraud()).isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        assertThat(existing.getFraudRptDate()).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(existing.getTransactionAmt())
                .as("the snapshot the row took when it was inserted is not rewritten")
                .isEqualByComparingTo(new BigDecimal("250.00"));
        verify(this.fraudRows, never()).save(any(AuthFraud.class));
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
        when(this.fraudRows.findById(any(AuthFraudKey.class))).thenReturn(Optional.empty());

        this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        ArgumentCaptor<AuthFraudKey> probed = ArgumentCaptor.forClass(AuthFraudKey.class);
        verify(this.fraudRows).findById(probed.capture());
        assertThat(probed.getValue().getCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(probed.getValue().getAuthTs()).isEqualTo(expectedAuthTs());
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
        when(this.fraudRows.findById(any(AuthFraudKey.class))).thenReturn(Optional.empty());

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
        when(this.fraudRows.findById(any(AuthFraudKey.class))).thenReturn(Optional.empty());

        this.service.mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT);

        ArgumentCaptor<AuthFraud> inserted = ArgumentCaptor.forClass(AuthFraud.class);
        verify(this.fraudRows).save(inserted.capture());
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
        when(this.details.findById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));
        verifyNoInteractions(this.fraudRows);
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
        when(this.details.findById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.of(rowWithOriginalDate("      ")));

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> this.service
                .mark(selector(), requestWith(PendingAuthDetail.FRAUD_REPORTED), SUBJECT));
        verify(this.fraudRows, never()).save(any(AuthFraud.class));
    }

    /**
     * Arranges the re-read by key to return the canonical authorization row.
     *
     * @return the row the service will mark, retained so its post-state can be asserted
     */
    private PendingAuthDetail givenExistingRow() {
        PendingAuthDetail row = rowWithOriginalDate(AUTH_ORIG_DATE);
        when(this.details.findById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.of(row));
        return row;
    }

    /**
     * Builds the request body naming the canonical row with one stated action.
     *
     * @param action the fraud state to set
     * @return a request agreeing with the canonical selector
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
     * @return 2026-08-03 at 09:16:44 and 902 milliseconds
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
     * @return a sealed selector the service can redeem
     */
    private String selector() {
        return this.mapper.toRowView(rowWithOriginalDate(AUTH_ORIG_DATE), SUBJECT).key();
    }

    /**
     * Builds the canonical authorization row with a stated original date.
     *
     * @param originalDate the six characters to store as the acquirer-supplied original date
     * @return a fully populated authorization row
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
