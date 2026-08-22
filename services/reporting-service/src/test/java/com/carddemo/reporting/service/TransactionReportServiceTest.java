package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportTotalsResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.mapper.CobolEditMask;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import com.carddemo.reporting.mapper.TransactionReportMapper;
import com.carddemo.reporting.repository.TransactionReportRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Pins how the transaction report DETECTS a broken dimension, how it PAGES, and what it GROUPS on.
 *
 * <p>Purpose: three defects in the earlier shape of this class are each invisible to any test that only
 * checks the report's bytes, and each has a case here. The integrity check compared two aggregate
 * counts, which one missing row and one duplicated row satisfy exactly -- so a run reported success over
 * a report that had silently lost a line and gained another. The listing endpoint materialised the whole
 * range and sliced it by ordinal, which repeats or skips a line under a concurrent posting. And the
 * subtotal path grouped by ACCOUNT while the emitted report groups by CARD, so a subtotal presented
 * beside a listing page was a differently-grouped number.
 *
 * <p>Assumptions: the query surface is mocked. The cases here are about which query is called, with
 * what arguments, and what the service does with the answer -- none of which a database can be made to
 * demonstrate more clearly, and two of which (a cancelled aggregate comparison, a card spanning a page
 * boundary) need a portfolio shape no fixture file expresses.
 *
 * <p>The nested classes below add the one claim no mapper test can make: that a real
 * {@link TransactionReportService#generateReport} run routes EVERY band it emits through
 * {@code TransactionReportMapper} and emits nothing else, in the order the reference's line counter
 * dictates. The sibling {@code com.carddemo.reporting.mapper} package owns the isolated proofs and they
 * are cited rather than repeated: {@code CobolEditMaskTest} proves each mask regime on its own,
 * {@code ReportBandLayoutsTest} proves the seven declared record lengths, the per-band copybook sums and
 * the three dot leaders, and {@code TransactionReportMapperTest} proves each encoder returns a band of
 * the declared length.
 *
 * <p>Alternatives Considered: asserting the band widths and the edit masks here instead, band by band.
 * Rejected, and the boundary matters in both directions. The charter in this package's
 * {@code package-info.java} places the report line being 133 columns with byte-exact masks in the mapper
 * package, because the mapper assembles the line and the services here only decide which rows reach it,
 * and it records that an earlier version of that charter attributed the control to this class while the
 * class did not contain it. Restating those assertions here would recreate exactly the double
 * bookkeeping that correction removed. What is asserted here is a strictly different property that the
 * mapper package cannot reach: an encoder returning 133 bytes says nothing about whether the SERVICE
 * ever hands its sink something an encoder did not produce, and only a run with a capturing sink can
 * settle that.
 *
 * <p>Trade-offs: the real {@code TransactionReportMapper}, {@code ReportBandLayouts} and
 * {@code CobolEditMask} chain is used rather than a mocked mapper, so the unit under test is wider than
 * this class's own subject. That is accepted deliberately: the claim being made is about the bytes that
 * reach the sink, and a mocked mapper would return whatever this test had already decided to expect, so
 * the assertion would carry no information about the emitted stream at all. Only the query surface and
 * the sink are doubled.
 *
 * <p>Assumptions: the emission assertions govern the PADDED record the service hands its sink, which is
 * {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes on every band. The shipped oracle
 * {@code tests/golden/reporting/e2e_full_cycle_report.expected} is NOT fixed width, and the reason is
 * the TEST HARNESS rather than the runtime. The reference writes fixed 133-byte records --
 * {@code FD-REPTFILE-REC PIC X(133)} at L85 of {@code app/cbl/CBTRN03C.cbl} against
 * {@code DCB=(LRECL=133,...)} at L78 of {@code app/jcl/TRANREPT.jcl} -- and it is
 * {@code tests/e2e/test_full_batch_cycle.py} that frames them for storage, its {@code _frame_report}
 * slicing the stream at the record length and right-stripping each slice at L516, with
 * {@code tests/helpers/golden_compare.py} right-stripping each line again at L930 when the artifact is
 * loaded. The measured line-length distribution of the stored file is therefore 14 lines
 * of 0 bytes, 546 of 112, 14 of 115 and 283 of 133, so only the all-hyphen separator survives at the
 * declared length. Any comparison against that artifact therefore applies the harness's own framing to
 * the emitted record first, and an assertion that a stored golden LINE is 133 bytes long could never
 * pass. The padding requirement is not loosened to accommodate the oracle; the comparison is normalised
 * instead.
 *
 * <p>Assumptions: this class consults no clock and proves that the service declares none.
 * {@code app/jcl/TRANREPT.jcl} hard-codes its two bounds as DFSORT symbols at its L43 and L44,
 * {@code PARM-START-DATE,C'2022-01-01'} and {@code PARM-END-DATE,C'2022-07-06'}, and those two dates are
 * the canonical range every fixture-driven case here uses. An injected range is what makes a rerun
 * byte-reproducible against an oracle, so a generator reading the wall clock could never satisfy the
 * comparison it exists to satisfy.
 *
 * <p>Assumptions: the fixture-driven cases order their rows by card fingerprint and then by transaction
 * identifier, which is the total order
 * {@code TransactionReportRepository#streamReportLinesWithin} declares. A stable secondary key is
 * required rather than merely tidy: {@code app/jcl/TRANREPT.jcl} L46 sorts on the card alone and
 * declares no {@code EQUALS}, so the baseline's order among rows sharing a card is not determined, and an
 * expectation resting on it would be an expectation about a sort implementation. Ordering on the
 * identifier as well makes every expectation here reproducible from the fixture bytes.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class TransactionReportServiceTest {

    /** The first business date of every range in this class. */
    private static final LocalDate RANGE_START = LocalDate.of(2022, 7, 1);

    /** The last business date of every range in this class. */
    private static final LocalDate RANGE_END = LocalDate.of(2022, 7, 31);

    /** A fingerprint standing for one card, at the width the reporting view produces. */
    private static final String FINGERPRINT_ONE = "a".repeat(63) + "1";

    /** A fingerprint standing for a second card, so a group change is expressible. */
    private static final String FINGERPRINT_TWO = "b".repeat(63) + "2";

    /**
     * Fixed cursor key material, at the sealer's minimum length.
     *
     * <p>Assumptions: fixed rather than random, so a token asserted here is reproducible from the source
     * alone, and declared here rather than defaulted inside the sealer, because a sealer that defaulted a
     * key is how a development default becomes the committed secret a sealed cursor exists to prevent.</p>
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-reporting-cursor-test-k!".repeat(2).getBytes(StandardCharsets.UTF_8);

    /** How long a cursor this class seals stays redeemable; generous, because nothing here tests expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /**
     * The binding a LEADING position is sealed under, being the one a backward step opens with.
     *
     * <p>Assumptions: two bindings rather than one, mirroring the handler. The published contract seals
     * the direction a position was issued for into it, so a leading key and a trailing key of the same
     * page are sealed under different bindings -- and a test using one binding for both would pass
     * against an implementation that had collapsed them, which is the defect the two exist to prevent.</p>
     */
    private static final String BACKWARD_BINDING = CursorToken.binding(
            "reporting.transaction-report.lines",
            "11111111-2222-3333-4444-555555555555",
            CursorToken.scope("backward", RANGE_START.toString(), RANGE_END.toString()));

    /** The binding a TRAILING position is sealed under, being the one a forward step opens with. */
    private static final String FORWARD_BINDING = CursorToken.binding(
            "reporting.transaction-report.lines",
            "11111111-2222-3333-4444-555555555555",
            CursorToken.scope("forward", RANGE_START.toString(), RANGE_END.toString()));

    private TransactionReportRepository reports;

    private TransactionReportService service;

    private CursorToken cursorToken;

    private TransactionReportRepository.CursorSealer sealer;

    /**
     * Builds the service over a partially mocked query surface.
     *
     * <p>Assumptions: the mock is created with the real-methods answer, so the interface's DEFAULT
     * methods execute and only its abstract queries are stubbed. That choice is what makes the paging
     * cases meaningful: the envelope assembly -- the look-ahead probe, the surplus row's removal, the
     * boundary keys and the previous-page answer -- lives in those default methods, so a mock that
     * returned a ready-made envelope would assert the service repackaged something the test itself had
     * composed. Stubbing only the abstract reads leaves the assembly under test.</p>
     *
     * <p>Assumptions: every stub below is therefore written in the {@code doReturn} form. The
     * {@code when} form evaluates the call it is describing, which under this answer would run the real
     * default method before any stub existed.</p>
     */
    @BeforeEach
    void setUp() {
        reports = mock(TransactionReportRepository.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        service = new TransactionReportService(reports);
        // WHY : Assumptions: a REAL sealer is built over fixed test key material rather than an identity
        //       function. The page envelope refuses a boundary component that does not carry a sealed
        //       token's shape -- deliberately, because a raw keyset cursor would publish the key columns
        //       to a client -- so an identity sealer cannot produce a page at all, and discovering that
        //       here is the guard working rather than an obstacle.
        cursorToken = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        sealer = (key, leading) -> cursorToken.seal(
                leading ? BACKWARD_BINDING : FORWARD_BINDING, key);
    }

    /**
     * Reads one band's amount out of the three bands the subtotal path returns.
     *
     * @param bands the bands as returned
     * @param band the band to read
     * @return that band's amount
     * @throws java.util.NoSuchElementException if the band is absent, which is itself a failure
     */
    private static Money amountOf(List<ReportTotalsResponse> bands, ReportTotalsResponse.Band band) {
        return bands.stream()
                .filter(candidate -> candidate.band() == band)
                .map(ReportTotalsResponse::amount)
                .findFirst()
                .orElseThrow();
    }

    // WHY : Refactoring Rationale: this is the cancelling-aggregate case, and it is written so that it
    //       FAILS against the previous implementation. The stub returns a driving count and a joined
    //       count that are EQUAL, which is precisely the state a comparison of two aggregates accepts,
    //       while the per-transaction probe reports one offending row. Only an implementation that asks
    //       the probe can refuse here.
    /**
     * Asserts that a range holding an unresolvable transaction is refused even when the counts agree.
     */
    @Test
    @DisplayName("an unresolvable transaction is refused even when the aggregate counts agree")
    void anUnresolvableTransactionIsRefusedDespiteAgreeingCounts() {
        doReturn(List.of("0000000000000042")).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn(500L).when(reports).countDrivingRows(any(), any());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.composeTotals(RANGE_START, RANGE_END))
                .withMessageContaining("0000000000000042");

        verify(reports, never()).streamReportLinesWithin(any(), any());
    }

    // WHY : Assumptions: the probe is bounded to ONE row and the bound is asserted. An unbounded probe
    //       over a broken range would read every offending row to report one of them, which turns a
    //       diagnostic into a scan at exactly the moment the range is already known to be unusable.
    /**
     * Asserts that the integrity probe reads no more rows than it reports.
     */
    @Test
    @DisplayName("the integrity probe is bounded to the rows it reports")
    void theIntegrityProbeIsBounded() {
        doReturn(List.of()).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn(0L).when(reports).countDrivingRows(any(), any());
        doReturn(Stream.of()).when(reports).streamReportLinesWithin(any(), any());

        service.composeTotals(RANGE_START, RANGE_END);

        org.mockito.ArgumentCaptor<Limit> bound = org.mockito.ArgumentCaptor.forClass(Limit.class);
        verify(reports).findTransactionsWithUnresolvedDimensions(any(), any(), bound.capture());
        assertThat(bound.getValue().max()).isEqualTo(TransactionReportService.MAX_DIAGNOSTIC_ROWS);
    }

    // WHY : Refactoring Rationale: this asserted that the PAGE path refuses an oversized range, and that
    //       is what the defect was. The bound is a statement about how much of a range can be assembled
    //       in memory on a request thread, and a keyset page assembles the page it was asked for -- so a
    //       range the generating path streams to an object in full could not be read twenty rows at a
    //       time, and the caller was told its end date was invalid. The two directions are now asserted
    //       separately: the page serves, and the totals surface still refuses.
    /**
     * Asserts that a range above the composable maximum still serves a bounded keyset page.
     */
    @Test
    @DisplayName("a range above the composable maximum still serves a bounded page")
    void anOversizedRangeStillServesABoundedPage() {
        doReturn(List.of()).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn((long) TransactionReportService.MAX_REPORT_LINES + 1).when(reports).countDrivingRows(any(), any());
        doReturn(List.of()).when(reports).findReportLinesAfter(anyString(), any(), any(), any(Limit.class));
        doReturn(List.of(line("0000000000000001", FINGERPRINT_ONE, "-10.00"))).when(reports).findReportLines(any(), any(), any(Limit.class));

        PageResponse<TransactionReportLineResponse> page =
                service.readDetailLinePage(RANGE_START, RANGE_END, null, false, sealer);

        assertThat(page.items()).hasSize(1);
        // WHY : Assumptions: the reconciliation is asserted to have RUN on this path, because removing
        //       the cap must not take the parity obligation with it. It stands in for the three lookup
        //       paragraphs of app/cbl/CBTRN03C.cbl, each of which abends on a miss, and it has to run per
        //       page rather than once because the reference has no notion of a first page to privilege.
        verify(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        verify(reports, never()).streamReportLinesWithin(any(), any());
    }

    // WHY : Assumptions: this is the other half of the split and it is asserted on the SAME oversized
    //       range, so the pair proves the cap moved rather than disappeared. The totals surface walks the
    //       whole range on a request thread, which is what the cap bounds.
    /**
     * Asserts that the totals surface still refuses a range above the composable maximum.
     */
    @Test
    @DisplayName("the totals surface still refuses a range above the composable maximum")
    void anOversizedRangeIsStillRefusedByTheTotalsSurface() {
        doReturn(List.of()).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn((long) TransactionReportService.MAX_REPORT_LINES + 1).when(reports).countDrivingRows(any(), any());

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.composeTotals(RANGE_START, RANGE_END))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("endDate"));

        verify(reports, never()).streamReportLinesWithin(any(), any());
    }

    // WHY : Refactoring Rationale: this is the offset-paging case. The assertion is that the KEYSET
    //       method is called and that the whole-range read is not, because the previous implementation
    //       read the range on every page and sliced it by ordinal -- so the twentieth page cost twenty
    //       scans and a row posted between two steps shifted every ordinal after it.
    /**
     * Asserts that a leading page is read by the keyset query and not by assembling the range.
     */
    @Test
    @DisplayName("a leading page is read by keyset, not by assembling the range")
    void aLeadingPageIsReadByKeyset() {
        stubAssemblableRange();
        doReturn(List.of()).when(reports).findReportLinesAfter(anyString(), any(), any(), any(Limit.class));
        doReturn(List.of(line("0000000000000001", FINGERPRINT_ONE, "-10.00"))).when(reports).findReportLines(any(), any(), any(Limit.class));

        PageResponse<TransactionReportLineResponse> page =
                service.readDetailLinePage(RANGE_START, RANGE_END, null, false, sealer);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).transactionId()).isEqualTo("0000000000000001");
        assertThat(page.firstKey())
                .as("the opening page publishes the position a backward step is issued from, and makes no"
                        + " claim about what waits there -- that is the caller's page ordinal to answer")
                .isNotNull();
        verify(reports, never()).streamReportLinesWithin(any(), any());
    }

    /**
     * Asserts that a continuation page positions strictly beyond the anchor the caller held.
     */
    @Test
    @DisplayName("a continuation page positions strictly beyond the caller's anchor")
    void aContinuationPagePositionsBeyondTheAnchor() {
        stubAssemblableRange();
        doReturn(List.of(line("0000000000000009", FINGERPRINT_ONE, "-10.00"))).when(reports).findReportLinesAfter(
                anyString(), any(), any(), any(Limit.class));

        service.readDetailLinePage(RANGE_START, RANGE_END, "0000000000000005", false, sealer);

        verify(reports).findReportLinesAfter(
                org.mockito.ArgumentMatchers.eq("0000000000000005"), any(), any(), any(Limit.class));
        verify(reports, never()).findReportLinesBefore(
                anyString(), any(), any(), any(Limit.class));
    }

    /**
     * Asserts that a backward page reads the preceding window and never the following one.
     */
    @Test
    @DisplayName("a backward page reads the preceding window")
    void aBackwardPageReadsThePrecedingWindow() {
        stubAssemblableRange();
        doReturn(List.of(line("0000000000000004", FINGERPRINT_ONE, "-10.00"))).when(reports).findReportLinesBefore(
                anyString(), any(), any(), any(Limit.class));

        service.readDetailLinePage(RANGE_START, RANGE_END, "0000000000000005", true, sealer);

        verify(reports).findReportLinesBefore(
                org.mockito.ArgumentMatchers.eq("0000000000000005"), any(), any(), any(Limit.class));
        verify(reports, never()).findReportLinesAfter(
                anyString(), any(), any(), any(Limit.class));
    }

    // WHY : Assumptions: a backward step with no anchor is refused HERE as well as at the handler, and
    //       the two refusals are not redundant. The handler's produces the published 400; this one keeps
    //       an in-process caller from receiving the leading page in answer to a request for the page
    //       before a row it never named.
    /**
     * Asserts that a backward page requested without an anchor is refused rather than answered.
     */
    @Test
    @DisplayName("a backward page with no anchor is refused")
    void aBackwardPageWithNoAnchorIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.readDetailLinePage(
                        RANGE_START, RANGE_END, null, true, sealer));

        verify(reports, never()).findReportLinesBefore(
                anyString(), any(), any(), any(Limit.class));
    }

    /**
     * Asserts that a page's boundary tokens are produced by the sealer the caller supplied.
     */
    @Test
    @DisplayName("a page's boundaries are sealed by the caller's sealer")
    void aPageBoundaryIsSealedByTheCallersSealer() {
        stubAssemblableRange();
        doReturn(List.of(line("0000000000000001", FINGERPRINT_ONE, "-10.00"))).when(reports).findReportLines(any(), any(), any(Limit.class));

        PageResponse<TransactionReportLineResponse> page = service.readDetailLinePage(
                RANGE_START, RANGE_END, null, false, sealer);

        assertThat(cursorToken.open(BACKWARD_BINDING, page.firstKey()))
                .as("the leading boundary opens under the binding a backward step uses")
                .isEqualTo("0000000000000001");
        assertThat(cursorToken.open(FORWARD_BINDING, page.lastKey()))
                .as("the trailing boundary opens under the binding a forward step uses")
                .isEqualTo("0000000000000001");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a trailing position may not be replayed backward, which the contract publishes")
                .isThrownBy(() -> cursorToken.open(BACKWARD_BINDING, page.lastKey()));
    }

    // WHY : Refactoring Rationale: this is the grouping case, and it is written so that the two
    //       groupings give DIFFERENT answers. Two lines share one account identifier and carry two
    //       different card fingerprints, so grouping by account emits one group band and grouping by
    //       card emits two. An implementation still grouping by account fails on the count.
    /**
     * Asserts that group subtotals are broken on the card, matching the report the generator emits.
     */
    @Test
    @DisplayName("group subtotals break on the card, not on the account")
    void groupSubtotalsBreakOnTheCard() {
        stubAssemblableRange();
        doReturn(Stream.of(
                line("0000000000000001", FINGERPRINT_ONE, "-10.00"),
                line("0000000000000002", FINGERPRINT_TWO, "-20.00"))).when(reports).streamReportLinesWithin(any(), any());

        List<ReportTotalsResponse> bands = service.composeTotals(RANGE_START, RANGE_END);

        assertThat(amountOf(bands, ReportTotalsResponse.Band.ACCOUNT))
                .as("the closing group band covers the LAST card alone; grouping by account would have"
                        + " carried both lines, because the account never changes")
                .isEqualTo(Money.of("-20.00"));
        assertThat(amountOf(bands, ReportTotalsResponse.Band.GRAND))
                .as("the closing figure sums every line of the range exactly, either way")
                .isEqualTo(Money.of("-30.00"));
    }

    /**
     * Asserts that the subtotal path reads the range itself rather than a list handed to it.
     */
    @Test
    @DisplayName("the subtotal path reads the range rather than a caller's list")
    void theSubtotalPathReadsTheRange() {
        stubAssemblableRange();
        doReturn(Stream.of()).when(reports).streamReportLinesWithin(any(), any());

        service.composeTotals(RANGE_START, RANGE_END);

        verify(reports).streamReportLinesWithin(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    // WHY : Assumptions: the two lines are each at the widest amount the ledger's own picture admits, so
    //       their sum is reachable from conforming rows alone and is not a fabricated figure. The sum
    //       needs a tenth integer position and the band masks provide nine, which is the exact condition
    //       the reference meets and answers by discarding the digits it cannot carry: its three
    //       accumulators are PIC S9(09)V99 at L134 to L136 of app/cbl/CBTRN03C.cbl and it adds into them
    //       with no ON SIZE ERROR clause at L200, L201, L287 and L288.
    // WHY : Refactoring Rationale: this behaviour used to be a raised failure that ended the whole run
    //       and answered the value-composing surface with an internal error, so a range holding one
    //       high-summing page produced no report and no bands at all. What is asserted now is the
    //       narrowing and the completion together, because either alone would pass against a shape that
    //       lost the rest of the report.
    /**
     * Asserts that a band figure past nine integer digits is narrowed and the bands are still composed.
     */
    @Test
    @DisplayName("a band figure past nine integer digits is narrowed rather than refused")
    void anOverWideBandFigureIsNarrowed() {
        stubAssemblableRange();
        doReturn(Stream.of(
                line("0000000000000001", FINGERPRINT_ONE, "999999999.99"),
                line("0000000000000002", FINGERPRINT_ONE, "999999999.99"))).when(reports).streamReportLinesWithin(any(), any());

        List<ReportTotalsResponse> bands = service.composeTotals(RANGE_START, RANGE_END);

        assertThat(amountOf(bands, ReportTotalsResponse.Band.ACCOUNT))
                .as("the card-break figure keeps its low-order nine digits and its cents")
                .isEqualTo(Money.of("999999999.98"));
        assertThat(amountOf(bands, ReportTotalsResponse.Band.GRAND))
                .as("the closing figure is composed from the narrowed page figure, as the reference"
                        + " accumulates the figure it printed")
                .isEqualTo(Money.of("999999999.98"));
    }

    // WHY : Assumptions: the driving count is stubbed ABOVE the composable maximum here, deliberately,
    //       so this case carries both properties at once. The emitting path hands each record to its
    //       sink as it goes and has never been bounded by that maximum, and an over-wide figure inside
    //       such a range is exactly the combination that used to leave a wide range with no object: the
    //       range was too wide for the value-composing surface to answer and one of its figures was too
    //       wide for the emitting surface to print, so neither surface produced anything.
    /**
     * Asserts that a wide range whose figures narrow still writes the report rather than writing nothing.
     */
    @Test
    @DisplayName("a wide range whose figures narrow still writes every record of the report")
    void aRunWhoseFiguresNarrowStillWritesTheReport() {
        doReturn(List.of()).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn((long) TransactionReportService.MAX_REPORT_LINES + 1).when(reports).countDrivingRows(any(), any());
        doReturn(Stream.of(
                line("0000000000000001", FINGERPRINT_ONE, "999999999.99"),
                line("0000000000000002", FINGERPRINT_ONE, "999999999.99"))).when(reports).streamReportLinesWithin(any(), any());
        List<byte[]> written = new ArrayList<>();

        service.generateReport(RANGE_START, RANGE_END, written::add);

        assertThat(written)
                .as("the report is emitted in full, where the raised failure wrote no object at all")
                .isNotEmpty();
        assertThat(written)
                .as("every record the emitting path hands its sink is one declared report record")
                .allSatisfy(record ->
                        assertThat(record).hasSize(ReportBandLayouts.REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that an inverted range is refused, naming the bound the caller can correct.
     */
    @Test
    @DisplayName("an inverted range is refused, naming a bound")
    void anInvertedRangeIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.composeTotals(RANGE_END, RANGE_START))
                .satisfies(refusal -> assertThat(refusal.fields()).isNotEmpty());

        verify(reports, never()).countDrivingRows(any(), any());
    }

    // WHY : Refactoring Rationale: this is the failure-disclosure case. A storage or driver failure's
    //       message is composed by an SDK rather than by this project, so it can carry a request target,
    //       a bucket name or a whole response body -- and an artifact key is derived from a cardholder's
    //       identity. The assertion is on what the abend text does NOT carry, because that text is
    //       returned to a caller and written to an operational record.
    /**
     * Asserts that a sink refusal does not carry its own message into the abend the run raises.
     */
    @Test
    @DisplayName("a sink refusal does not carry its message into the abend text")
    void aSinkRefusalDoesNotCarryItsMessage() {
        stubAssemblableRange();
        doReturn(Stream.of(
                line("0000000000000001", FINGERPRINT_ONE, "-10.00"))).when(reports).streamReportLinesWithin(any(), any());

        String secret = "s3://carddemo-datasets/statements/21820493291-7065.txt";
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.generateReport(RANGE_START, RANGE_END, record -> {
                    throw new IOException("PUT " + secret + " returned 403");
                }))
                .satisfies(abend -> {
                    assertThat(abend.getMessage())
                            .as("no storage message may reach an abend a caller receives")
                            .doesNotContain(secret)
                            .doesNotContain("403");
                    assertThat(abend.getMessage())
                            .as("the reference's own constant names the condition")
                            .contains("ERROR WRITING REPTFILE");
                });
    }

    /**
     * Stubs the two reconciliation reads so a range is assemblable and holds no broken dimension.
     */
    private void stubAssemblableRange() {
        doReturn(List.of()).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn(2L).when(reports).countDrivingRows(any(), any());
    }

    /**
     * Builds one resolved report line.
     *
     * <p>Assumptions: an anonymous implementation of the closed projection rather than a mock, because
     * every accessor is read while a band is assembled and a mock returning {@code null} for an
     * unstubbed accessor would fail the band assembler rather than the case under test.</p>
     *
     * @param transactionId the transaction identifier the line carries
     * @param fingerprint the per-card fingerprint the line groups under
     * @param amount the exact amount the line carries
     * @return the line
     */
    private static TransactionReportRepository.ReportLine line(
            String transactionId, String fingerprint, String amount) {
        return new TransactionReportRepository.ReportLine() {
            @Override
            public String getTransactionId() {
                return transactionId;
            }

            @Override
            public String getCardNum() {
                return "************7065";
            }

            @Override
            public String getCardFingerprint() {
                return fingerprint;
            }

            @Override
            public Long getAccountId() {
                // Assumptions: one account for both lines, deliberately. It is what makes the grouping
                //   case above able to tell a card break from an account break.
                return 21_820_493_291L;
            }

            @Override
            public String getTypeCd() {
                return "01";
            }

            @Override
            public String getTypeDescription() {
                return "Purchase";
            }

            @Override
            public String getCategoryCd() {
                return "0001";
            }

            @Override
            public String getCategoryDescription() {
                return "Groceries";
            }

            @Override
            public String getSource() {
                return "POS";
            }

            @Override
            public Money getAmount() {
                return Money.of(amount);
            }
        };
    }

    // -----------------------------------------------------------------------------------------------
    // Emission harness: the canonical range, a capturing sink, the fixture reader, the band classifier
    // and the derived line-counter model that the paging cases are asserted against.
    // -----------------------------------------------------------------------------------------------

    /**
     * First business date of the canonical range, taken from the baseline job's own DFSORT symbol.
     *
     * <p>Assumptions: {@code app/jcl/TRANREPT.jcl} L43 declares
     * {@code PARM-START-DATE,C'2022-01-01'}, and the fixture carries two rows dated exactly this day so
     * that the lower bound's inclusivity is observable rather than assumed.</p>
     */
    private static final LocalDate CANONICAL_START = LocalDate.of(2022, 1, 1);

    /**
     * Last business date of the canonical range, taken from the baseline job's own DFSORT symbol.
     *
     * <p>Assumptions: {@code app/jcl/TRANREPT.jcl} L44 declares {@code PARM-END-DATE,C'2022-07-06'}, and
     * the fixture carries one row dated exactly this day and one dated the day after, so that the upper
     * bound admits the first and excludes the second.</p>
     */
    private static final LocalDate CANONICAL_END = LocalDate.of(2022, 7, 6);

    /** Classpath directory the four report fixtures are read from. */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /** Registered layout name the 350-byte transaction fixture is written against. */
    private static final String TRAN_LAYOUT = "TRAN";

    /** Registered layout name the 50-byte cross-reference fixture is written against. */
    private static final String XREF_LAYOUT = "XREF";

    /** Registered layout name the 60-byte transaction-type fixture is written against. */
    private static final String TRANTYPE_LAYOUT = "TRANTYPE";

    /** Registered layout name the 60-byte transaction-category fixture is written against. */
    private static final String TRANCAT_LAYOUT = "TRANCAT";

    /**
     * A sink that keeps every record a run offered it, in the order the run offered them.
     *
     * <p>Assumptions: the records are kept as the byte arrays the service handed over and are never
     * trimmed or decoded on the way in, because the length of what was handed over is itself one of the
     * properties under assertion. A sink that stored a trimmed string would erase the padding this class
     * exists to check.</p>
     *
     * <p>Alternatives Considered: a Mockito mock of {@link TransactionReportService.ReportRecordSink}
     * with an argument captor. Rejected because the captor would hold the same array references the
     * service reused if it ever reused one, and a named recording type can assert freshness directly
     * while also reading as the destination the interface documents.</p>
     */
    private static final class RecordingSink implements TransactionReportService.ReportRecordSink {

        /** Every record offered, in offer order. */
        private final List<byte[]> records = new ArrayList<>();

        /**
         * Keeps one offered record.
         *
         * <p>Assumptions: nothing is rejected and nothing is validated here. A sink that refused a
         * malformed record would convert an assertion failure carrying the offending bytes into an
         * abend carrying only a reason string, which is strictly less diagnostic.</p>
         *
         * @param record the record the run offered, a {@code byte[]} that this sink keeps by reference
         */
        @Override
        public void write(byte[] record) {
            this.records.add(record);
        }

        /**
         * Renders every kept record as one classified band name, in offer order.
         *
         * @return the band name of each kept record, in the order the records were offered; never
         *     {@code null}
         */
        private List<String> bandSequence() {
            List<String> sequence = new ArrayList<>(this.records.size());
            for (byte[] record : this.records) {
                sequence.add(bandKindOf(record));
            }
            return sequence;
        }

        /**
         * Renders every kept record as a string of one character per byte, in offer order.
         *
         * @return the kept records decoded one character per byte, padding included; never {@code null}
         */
        private List<String> renderedRecords() {
            List<String> rendered = new ArrayList<>(this.records.size());
            for (byte[] record : this.records) {
                rendered.add(new String(record, StandardCharsets.US_ASCII));
            }
            return rendered;
        }

        /**
         * Joins every kept record into one text, so a negative claim can be made over the whole stream.
         *
         * @return every kept record concatenated in offer order; never {@code null}
         */
        private String wholeStream() {
            return String.join("\n", renderedRecords());
        }
    }

    /**
     * Names the band one emitted record carries, by the literal the reference seeds it with.
     *
     * <p>Assumptions: the classification reads the leading literal each band declares in
     * {@code app/cpy/CVTRA07Y.cpy} rather than the record's position in the stream, so a misordered
     * stream is still classified correctly and the ordering assertion stays independent of the
     * classifier. The separator is tested before the blank band because both are uniform fills and only
     * their fill character distinguishes them.</p>
     *
     * @param record the emitted record to classify, a {@code byte[]} of the declared report length
     * @return one of the band names this class compares sequences of; never {@code null}
     */
    private static String bandKindOf(byte[] record) {
        String rendered = new String(record, StandardCharsets.US_ASCII);
        if (rendered.equals(ReportBandLayouts.SEPARATOR_RULE)) {
            return "SEPARATOR";
        }
        if (rendered.isBlank()) {
            return "BLANK";
        }
        if (rendered.startsWith(ReportBandLayouts.REPORT_SHORT_NAME)) {
            return "NAME_HEADER";
        }
        if (rendered.startsWith(ReportBandLayouts.COLUMN_LABEL_TRANSACTION_ID)) {
            return "COLUMN_HEADER";
        }
        if (rendered.startsWith(ReportBandLayouts.PAGE_TOTAL_LABEL)) {
            return "PAGE_TOTAL";
        }
        if (rendered.startsWith(ReportBandLayouts.ACCOUNT_TOTAL_LABEL)) {
            return "ACCOUNT_TOTAL";
        }
        if (rendered.startsWith(ReportBandLayouts.GRAND_TOTAL_LABEL)) {
            return "GRAND_TOTAL";
        }
        return "DETAIL";
    }

    /**
     * Reads one fixture file and returns its rows as fixed-width byte arrays.
     *
     * <p>Assumptions: the fixture is read from the test classpath rather than from a path relative to
     * the module, so the case behaves identically under a reactor build and under a single-module build.
     * Each line is one record of the declared length and is decoded one byte per character, which is
     * sound for these four fixtures because {@code ReportingFixtureContractTest} already asserts every
     * row of each is exactly its declared length.</p>
     *
     * @param fileName the fixture file name, relative to the fixtures directory on the test classpath
     * @return one byte array per fixture row, in file order; never {@code null}
     * @throws IllegalStateException if the fixture directory or the named file is not on the test
     *     classpath, which is a broken test resource rather than a failure of the subject
     * @throws UncheckedIOException if the file cannot be read
     */
    private static List<byte[]> fixtureRows(String fileName) {
        java.net.URL located =
                TransactionReportServiceTest.class.getClassLoader().getResource(FIXTURE_DIRECTORY);
        if (located == null) {
            throw new IllegalStateException(
                    "fixture directory " + FIXTURE_DIRECTORY + " is not on the test classpath");
        }
        try {
            Path file = Path.of(located.toURI()).resolve(fileName);
            List<byte[]> rows = new ArrayList<>();
            for (String row : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                if (!row.isEmpty()) {
                    rows.add(row.getBytes(StandardCharsets.US_ASCII));
                }
            }
            return rows;
        } catch (java.net.URISyntaxException malformed) {
            throw new IllegalStateException("fixture directory is not addressable as a path", malformed);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("fixture " + fileName + " could not be read", unreadable);
        }
    }

    /**
     * Reads one text field out of a decoded fixture row, with its declared blank padding removed.
     *
     * @param fields the decoded row as {@link FixedWidthCodec} returned it
     * @param fieldName the copybook field name to read
     * @return that field's value with trailing blanks removed; never {@code null}
     */
    private static String textField(Map<String, Object> fields, String fieldName) {
        return String.valueOf(fields.get(fieldName)).stripTrailing();
    }

    /**
     * Derives a per-card grouping key that sorts in the same order as the card number it stands for.
     *
     * <p>Assumptions: the service groups on {@code getCardFingerprint} and the repository orders on the
     * same column, so the fixture-driven cases need a fingerprint whose ordering agrees with the card
     * ordering {@code app/jcl/TRANREPT.jcl} L46 sorts by. Right-padding the card number to a fixed width
     * gives that agreement by construction. A digest would not: a hash reorders the groups, so the
     * expected band sequence would depend on the digest function rather than on the fixture.</p>
     *
     * @param cardNumber the card number the fixture row carries
     * @return a fixed-width grouping key that is order-equivalent to {@code cardNumber}; never
     *     {@code null}
     */
    private static String fingerprintOf(String cardNumber) {
        return cardNumber + "0".repeat(64 - cardNumber.length());
    }


    /**
     * Assembles the resolved report lines the four fixtures produce for one inclusive date range.
     *
     * <p>Assumptions: this stands in for the join and the ordering that
     * {@code TransactionReportRepository#streamReportLinesWithin} performs in SQL -- the cross-reference
     * supplies the account, the type fixture and the category fixture supply the two descriptions, the
     * range restricts on the DATE PART of the 26-character processing timestamp with both bounds
     * admitted, and the result is ordered by fingerprint and then identifier. Building it from the
     * fixture bytes rather than from hand-written rows is what makes the emission cases assertions about
     * the shipped record layouts: every offset comes from
     * {@link CopybookLayout} and none is restated here, which
     * {@code tests/README.md} L540 to L542 requires of any new fixture consumer.</p>
     *
     * <p>Assumptions: the date part is taken through
     * {@link TimestampFormatter#datePrefix} rather than by substring. The reference compares
     * {@code TRAN-PROC-TS (1:10)} at L173 and L174 of {@code app/cbl/CBTRN03C.cbl} using a reference
     * modification, and the shared formatter is the one place that 10-of-26 contract is expressed.</p>
     *
     * @param from the first business date to admit, inclusive; must not be {@code null}
     * @param to the last business date to admit, inclusive; must not be {@code null}
     * @return the resolved lines the fixtures produce for that range, ordered by card fingerprint and
     *     then by transaction identifier; never {@code null}
     * @throws IllegalStateException if a fixture row references a card, type or category the other
     *     fixtures do not resolve, which the caller uses to drive the dimension-refusal cases
     */
    private static List<TransactionReportRepository.ReportLine> fixtureLines(
            LocalDate from, LocalDate to) {

        Map<String, Long> accountByCard = new HashMap<>();
        for (byte[] row : fixtureRows("cardxref.txt")) {
            Map<String, Object> fields =
                    FixedWidthCodec.decodeRecord(row, CopybookLayout.layout(XREF_LAYOUT));
            accountByCard.put(
                    textField(fields, "XREF-CARD-NUM"), (Long) fields.get("XREF-ACCT-ID"));
        }

        Map<String, String> descriptionByType = new HashMap<>();
        for (byte[] row : fixtureRows("trantype.txt")) {
            Map<String, Object> fields =
                    FixedWidthCodec.decodeRecord(row, CopybookLayout.layout(TRANTYPE_LAYOUT));
            descriptionByType.put(
                    textField(fields, "TRAN-TYPE"), textField(fields, "TRAN-TYPE-DESC"));
        }

        Map<String, String> descriptionByCategory = new HashMap<>();
        for (byte[] row : fixtureRows("trancatg.txt")) {
            Map<String, Object> fields =
                    FixedWidthCodec.decodeRecord(row, CopybookLayout.layout(TRANCAT_LAYOUT));
            // WHY : Assumptions: the reference builds the same composite key at L191 to L194 of
            //       app/cbl/CBTRN03C.cbl before performing its category lookup, so a category code is
            //       only meaningful under a type and keying on the code alone would resolve a
            //       description that belongs to a different type.
            descriptionByCategory.put(
                    textField(fields, "TRAN-TYPE-CD") + categoryDigits(fields, "TRAN-CAT-CD"),
                    textField(fields, "TRAN-CAT-TYPE-DESC"));
        }

        List<TransactionReportRepository.ReportLine> resolved = new ArrayList<>();
        for (byte[] row : fixtureRows("tranfile.txt")) {
            Map<String, Object> fields =
                    FixedWidthCodec.decodeRecord(row, CopybookLayout.layout(TRAN_LAYOUT));

            LocalDate processed = LocalDate.parse(
                    TimestampFormatter.datePrefix(textField(fields, "TRAN-PROC-TS")));
            if (processed.isBefore(from) || processed.isAfter(to)) {
                continue;
            }

            String cardNumber = textField(fields, "TRAN-CARD-NUM");
            String typeCode = textField(fields, "TRAN-TYPE-CD");
            String categoryCode = categoryDigits(fields, "TRAN-CAT-CD");

            Long accountId = accountByCard.get(cardNumber);
            String typeDescription = descriptionByType.get(typeCode);
            String categoryDescription = descriptionByCategory.get(typeCode + categoryCode);
            if (accountId == null || typeDescription == null || categoryDescription == null) {
                throw new IllegalStateException(
                        "fixture row " + textField(fields, "TRAN-ID") + " has an unresolved dimension");
            }

            resolved.add(resolvedLine(
                    textField(fields, "TRAN-ID"),
                    cardNumber,
                    accountId,
                    typeCode,
                    typeDescription,
                    categoryCode,
                    categoryDescription,
                    textField(fields, "TRAN-SOURCE"),
                    Money.of((java.math.BigDecimal) fields.get("TRAN-AMT"))));
        }

        resolved.sort(Comparator
                .comparing(TransactionReportRepository.ReportLine::getCardFingerprint)
                .thenComparing(TransactionReportRepository.ReportLine::getTransactionId));
        return resolved;
    }

    /**
     * Reads the category code back as the four digits the report band declares.
     *
     * <p>Assumptions: the codec decodes an unsigned item to an integral value, which drops the leading
     * zeros the fixture bytes carry, while the projection this test stands in for publishes the code as
     * characters with those zeros intact -- the band item is {@code PIC 9(04)} at L24 of
     * {@code app/cpy/CVTRA07Y.cpy}. Re-widening here keeps the stand-in faithful to the projection
     * instead of handing the service a form it never receives in production.</p>
     *
     * @param fields the decoded row as {@link FixedWidthCodec} returned it
     * @param fieldName the copybook field name holding the category code
     * @return the code as four digits, leading zeros included; never {@code null}
     */
    private static String categoryDigits(Map<String, Object> fields, String fieldName) {
        return String.format("%04d", (Long) fields.get(fieldName));
    }

    /**
     * Builds one fully resolved report line, as the reporting query would project it.
     *
     * <p>Assumptions: an anonymous implementation of the closed projection rather than a mock, for the
     * reason the {@link #line} helper above records -- every accessor is read while a band is assembled,
     * so a mock returning {@code null} for an unstubbed accessor would fail the assembler rather than
     * the case under test. The card rendering is masked to its last four digits because that is what the
     * projection publishes; the detail band carries no card at all, which the stream-hygiene case
     * asserts as a negative.</p>
     *
     * @param transactionId the transaction identifier the line carries
     * @param cardNumber the full card number from the fixture, used for the grouping key and masked for
     *     the rendering
     * @param accountId the account the cross-reference resolved for that card
     * @param typeCode the two-character transaction type code
     * @param typeDescription the type description as the type fixture carries it, at full fixture width
     * @param categoryCode the four-digit transaction category code
     * @param categoryDescription the category description as the category fixture carries it, at full
     *     fixture width
     * @param source the transaction source
     * @param amount the exact amount the line carries
     * @return the resolved line; never {@code null}
     */
    private static TransactionReportRepository.ReportLine resolvedLine(
            String transactionId,
            String cardNumber,
            Long accountId,
            String typeCode,
            String typeDescription,
            String categoryCode,
            String categoryDescription,
            String source,
            Money amount) {

        String fingerprint = fingerprintOf(cardNumber);
        String masked = "*".repeat(cardNumber.length() - 4)
                + cardNumber.substring(cardNumber.length() - 4);
        return new TransactionReportRepository.ReportLine() {
            /** {@return the transaction identifier the fixture row carried} */
            @Override
            public String getTransactionId() {
                return transactionId;
            }

            /** {@return the card masked to its last four digits, as the projection publishes it} */
            @Override
            public String getCardNum() {
                return masked;
            }

            /** {@return the grouping key the run breaks on} */
            @Override
            public String getCardFingerprint() {
                return fingerprint;
            }

            /** {@return the account the cross-reference resolved for this card} */
            @Override
            public Long getAccountId() {
                return accountId;
            }

            /** {@return the two-character transaction type code} */
            @Override
            public String getTypeCd() {
                return typeCode;
            }

            /** {@return the type description at full fixture width, for the band to narrow} */
            @Override
            public String getTypeDescription() {
                return typeDescription;
            }

            /** {@return the four-digit category code, leading zeros intact} */
            @Override
            public String getCategoryCd() {
                return categoryCode;
            }

            /** {@return the category description at full fixture width, for the band to narrow} */
            @Override
            public String getCategoryDescription() {
                return categoryDescription;
            }

            /** {@return the transaction source} */
            @Override
            public String getSource() {
                return source;
            }

            /** {@return the exact amount this line carries} */
            @Override
            public Money getAmount() {
                return amount;
            }
        };
    }


    /**
     * What one completed emission run produced.
     *
     * @param sink the sink that captured every record the run emitted, a {@link RecordingSink}
     * @param summary the run's own report of what it emitted, a
     *     {@link TransactionReportService.ReportGenerationSummary}
     */
    private record EmissionRun(
            RecordingSink sink, TransactionReportService.ReportGenerationSummary summary) {
    }

    /**
     * Runs one report over a prepared line list and captures everything it emitted.
     *
     * <p>Assumptions: only the two reconciliation reads and the streaming cursor are stubbed. The whole
     * emission path -- the group break, the page break, the three accumulators and every band encoder --
     * runs for real, because the record stream is the subject.</p>
     *
     * @param lines the resolved lines the cursor should yield, in the order the query would yield them
     * @return the sink and the summary the run produced; never {@code null}
     */
    private EmissionRun emit(List<TransactionReportRepository.ReportLine> lines) {
        stubAssemblableRange();
        doReturn(lines.stream()).when(reports).streamReportLinesWithin(any(), any());

        RecordingSink sink = new RecordingSink();
        TransactionReportService.ReportGenerationSummary summary =
                service.generateReport(CANONICAL_START, CANONICAL_END, sink);
        return new EmissionRun(sink, summary);
    }

    /**
     * Replays the reference's line-counter arithmetic to derive the band sequence a line list must emit.
     *
     * <p>Alternatives Considered: expecting a fixed number of detail lines per page, which the modulus of
     * 20 at L131 and L132 of {@code app/cbl/CBTRN03C.cbl} invites. Rejected outright, and it is the
     * single most likely way to arrive at a wrong expectation here. The counter is advanced by every band
     * written, not by detail lines alone: {@code 1110-WRITE-PAGE-TOTALS} at L293 advances twice at its
     * L299 and L302, {@code 1120-WRITE-ACCOUNT-TOTALS} at L306 advances twice at its L311 and L314,
     * {@code 1120-WRITE-HEADERS} at L324 advances four times at its L327, L331, L335 and L339,
     * {@code 1120-WRITE-DETAIL} at L361 advances once at its L373, and
     * {@code 1110-WRITE-GRAND-TOTALS} at L318 advances not at all. A page break therefore costs six
     * advances before the next detail line and a card break costs two, so the equality test at L282 can
     * be carried straight past a multiple of the modulus and the detail lines per page vary from page to
     * page. Deriving the sequence from the same arithmetic the subject implements is what lets this case
     * assert the boundaries rather than a number someone counted once.</p>
     *
     * <p>Assumptions: each step advances the counter by exactly the number of records it emits, with the
     * grand total the sole exception at zero. That equivalence is read off the five paragraphs above and
     * is what keeps this model free of a second copy of the advance constants.</p>
     *
     * @param lines the resolved lines the run will read, in query order
     * @return the band names the run must emit, in order; never {@code null}
     */
    private static List<String> derivedBandSequence(
            List<TransactionReportRepository.ReportLine> lines) {

        int headingBandCount =
                TransactionReportMapper.encodeHeadingBlock(CANONICAL_START, CANONICAL_END).size();
        List<String> expected = new ArrayList<>();
        long counter = 0;
        boolean firstTime = true;
        String group = null;

        for (TransactionReportRepository.ReportLine line : lines) {
            if (!Objects.equals(group, line.getCardFingerprint())) {
                if (!firstTime) {
                    expected.add("ACCOUNT_TOTAL");
                    expected.add("SEPARATOR");
                    counter += 2;
                }
                group = line.getCardFingerprint();
            }
            if (firstTime) {
                firstTime = false;
                appendHeadingBlock(expected, headingBandCount);
                counter += headingBandCount;
            }
            if (counter % TransactionReportService.LINE_COUNTER_MODULUS == 0) {
                expected.add("PAGE_TOTAL");
                expected.add("SEPARATOR");
                counter += 2;
                appendHeadingBlock(expected, headingBandCount);
                counter += headingBandCount;
            }
            expected.add("DETAIL");
            counter += 1;
        }

        // WHY : Assumptions: the closing sequence emits a card-break band BEFORE the page and grand
        //       bands, which is the documented divergence D-REPORT-CLOSING-TOTAL in
        //       docs/architecture/cobol-to-service-traceability.md. The reference's end-of-file branch at
        //       L197 to L204 of app/cbl/CBTRN03C.cbl performs only its page totals at L202 and its grand
        //       totals at L203, so the final card group's own band is never written and the shipped
        //       oracle ends detail, page total, separator, grand total. This model follows the migrated
        //       behaviour the register describes, not the baseline's.
        if (!firstTime) {
            expected.add("ACCOUNT_TOTAL");
            expected.add("SEPARATOR");
        }
        expected.add("PAGE_TOTAL");
        expected.add("SEPARATOR");
        expected.add("GRAND_TOTAL");
        return expected;
    }

    /**
     * Appends the band names one heading block emits, in the order the block composes them.
     *
     * <p>Assumptions: the count is passed in from
     * {@link TransactionReportMapper#encodeHeadingBlock} rather than written as four here, so this model
     * cannot disagree with the block it is modelling. The order is the reference's own at L325, L329,
     * L333 and L337 of {@code app/cbl/CBTRN03C.cbl}: title band, blank band, column band, separator
     * rule.</p>
     *
     * @param target the sequence being built, appended to in place
     * @param headingBandCount the number of bands the heading block emits, as the mapper reports it
     * @throws IllegalStateException if the mapper reports a block size this model does not describe,
     *     which is a drift between the two that must fail loudly rather than be padded over
     */
    private static void appendHeadingBlock(List<String> target, int headingBandCount) {
        List<String> block = List.of("NAME_HEADER", "BLANK", "COLUMN_HEADER", "SEPARATOR");
        if (headingBandCount != block.size()) {
            throw new IllegalStateException(
                    "the heading block emits " + headingBandCount + " bands and this model describes "
                            + block.size());
        }
        target.addAll(block);
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a count of
     * parent steps, so this class runs identically from the reactor root and from the module directory.
     * That is the same rule the module's integration tests use to reach repository-level files, and a
     * relative path would resolve differently between those two invocations.</p>
     *
     * @return the repository root; never {@code null}
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
     * Right-trims one emitted record the way the harness trims a record before storing it in a golden.
     *
     * <p>Assumptions: the shipped oracle
     * {@code tests/golden/reporting/e2e_full_cycle_report.expected} stores 14 lines of 0 bytes, 546 of
     * 112, 14 of 115 and 283 of 133 -- a measured distribution, not an estimate -- and the trim that
     * produces it is the TEST HARNESS's rather than the runtime's. The reference writes fixed 133-byte
     * records: {@code FD-REPTFILE-REC PIC X(133)} at L85 of {@code app/cbl/CBTRN03C.cbl} against
     * {@code DCB=(LRECL=133,...)} at L78 of {@code app/jcl/TRANREPT.jcl}. It is
     * {@code tests/e2e/test_full_batch_cycle.py} that frames those records before the artifact is
     * stored, slicing the stream at the record length and right-stripping each slice in
     * {@code _frame_report} at L516, and {@code tests/helpers/golden_compare.py} right-strips each line
     * again at L930 when the artifact is loaded. Normalising the EMITTED record down to the stored form
     * is the only way a comparison against the artifact can hold, and it is done here rather than by
     * relaxing the padding requirement: the service still has to emit the full declared width, and the
     * separate emission cases assert exactly that.</p>
     *
     * @param record the emitted record, a {@code byte[]} of the declared report length
     * @return the record rendered with its trailing blanks removed; never {@code null}
     */
    private static String rightTrimmed(byte[] record) {
        return new String(record, StandardCharsets.US_ASCII).stripTrailing();
    }


    /**
     * Reads the 15-character amount column out of one rendered band.
     *
     * <p>Assumptions: the column starts at {@link ReportBandLayouts#AMOUNT_COLUMN_OFFSET} and is
     * {@link CobolEditMask#REPORT_AMOUNT_WIDTH} wide in every money-bearing band, which is the shared
     * span the three total labels and leaders are sized to reach.</p>
     *
     * @param rendered one emitted band, rendered one character per byte
     * @return that band's amount column, exactly as emitted; never {@code null}
     */
    private static String amountColumnOf(String rendered) {
        return rendered.substring(
                ReportBandLayouts.AMOUNT_COLUMN_OFFSET,
                ReportBandLayouts.AMOUNT_COLUMN_OFFSET + CobolEditMask.REPORT_AMOUNT_WIDTH);
    }

    /**
     * Measures the run of dot-leader bytes one total band carries between its label and its amount.
     *
     * @param rendered one emitted total band, rendered one character per byte
     * @param labelWidth the declared width of that band's label item
     * @return the number of consecutive full stops following the label; never negative
     */
    private static int leaderRunOf(String rendered, int labelWidth) {
        int run = 0;
        while (labelWidth + run < rendered.length() && rendered.charAt(labelWidth + run) == '.') {
            run++;
        }
        return run;
    }

    /**
     * Selects every rendered band of one kind from a completed run, in emission order.
     *
     * @param run the completed run whose captured records are selected from
     * @param kind the band name to select, as {@link #bandKindOf} names it
     * @return the rendered records of that kind, in emission order; never {@code null}
     */
    private static List<String> bandsOfKind(EmissionRun run, String kind) {
        List<String> selected = new ArrayList<>();
        for (String rendered : run.sink().renderedRecords()) {
            if (bandKindOf(rendered.getBytes(StandardCharsets.US_ASCII)).equals(kind)) {
                selected.add(rendered);
            }
        }
        return selected;
    }

    /**
     * The record stream a run hands its sink, asserted as a whole rather than band by band.
     *
     * <p>Purpose: these cases make the one claim the mapper test package cannot reach -- that the service
     * routes every band through the encoders and hands the sink nothing else. The declared widths, the
     * mask regimes and the per-band copybook arithmetic are proved in isolation by
     * {@code ReportBandLayoutsTest} and {@code CobolEditMaskTest} and are cited rather than repeated
     * here.</p>
     *
     * <p>WHY: the reference writes every band through the single funnel
     * {@code 1111-WRITE-REPORT-REC} at L343 of {@code app/cbl/CBTRN03C.cbl}, whose record area is
     * declared {@code FD-REPTFILE-REC PIC X(133)} at its L85 and whose data set is declared
     * {@code DCB=(LRECL=133,...)} at L78 of {@code app/jcl/TRANREPT.jcl}.</p>
     */
    @Nested
    @DisplayName("the emitted record stream")
    class EmittedRecordStream {

        /**
         * Asserts that every record a fixture-driven run emits is the full declared report width.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code app/cbl/CBTRN03C.cbl} L85 declares {@code FD-REPTFILE-REC PIC X(133)} and every
         * band moves into that one record area before {@code 1111-WRITE-REPORT-REC} at its L343 writes
         * it, so a short record is not a shape the reference can produce.</p>
         */
        @Test
        @DisplayName("every emitted record is exactly the declared report width")
        void everyEmittedRecordIsTheDeclaredReportWidth() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            assertThat(run.sink().records)
                    .as("a run over the canonical range emits records")
                    .isNotEmpty();
            assertThat(run.sink().records)
                    .allSatisfy(record -> assertThat(record.length)
                            .as("every band reaches the sink at its full declared width, padded")
                            .isEqualTo(ReportBandLayouts.REPORT_RECORD_LENGTH));
        }

        /**
         * Asserts that the three total bands place their amount on one shared column span.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code app/cpy/CVTRA07Y.cpy} sizes each total band's label and leader to reach the same
         * column -- 11 plus 86 at its L51 to L53, 13 plus 84 at its L57 to L59 and 11 plus 86 at its L63
         * to L65 -- so all three amounts land in the same 15 columns even though no two labels are the
         * same width. Asserting the span on the emitted bands catches a leader error and a mask-width
         * error in one place.</p>
         */
        @Test
        @DisplayName("all three total bands carry their amount on one shared column span")
        void allThreeTotalBandsShareOneAmountColumnSpan() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            for (String kind : List.of("PAGE_TOTAL", "ACCOUNT_TOTAL", "GRAND_TOTAL")) {
                List<String> bands = bandsOfKind(run, kind);
                assertThat(bands).as("the run emitted at least one %s band", kind).isNotEmpty();
                assertThat(bands).allSatisfy(band -> {
                    assertThat(amountColumnOf(band))
                            .as("%s carries a 15-column amount at the shared offset", kind)
                            .hasSize(CobolEditMask.REPORT_AMOUNT_WIDTH);
                    assertThat(band.charAt(ReportBandLayouts.AMOUNT_COLUMN_OFFSET - 1))
                            .as("the column immediately before the amount is the last leader byte")
                            .isEqualTo('.');
                });
            }
        }

        /**
         * Asserts that each total band carries its own dot-leader width rather than a shared one.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: the three leaders are declared 86, 84 and 86 bytes at L53, L59 and L65 of
         * {@code app/cpy/CVTRA07Y.cpy}, and the card-break leader is the odd one because its label is two
         * bytes wider. {@code ReportBandLayoutsTest} pins each declared width to its copybook line; this
         * case asserts the widths actually reach the emitted stream and, separately, that the card-break
         * leader is two shorter than the other two -- so an emitter that had collapsed the three onto one
         * width cannot pass even if every constant were changed together.</p>
         */
        @Test
        @DisplayName("each total band carries its own leader width, and the card-break leader is shorter")
        void eachTotalBandCarriesItsOwnLeaderWidth() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            int pageRun = leaderRunOf(
                    bandsOfKind(run, "PAGE_TOTAL").get(0), ReportBandLayouts.PAGE_TOTAL_LABEL_WIDTH);
            int cardRun = leaderRunOf(
                    bandsOfKind(run, "ACCOUNT_TOTAL").get(0),
                    ReportBandLayouts.ACCOUNT_TOTAL_LABEL_WIDTH);
            int grandRun = leaderRunOf(
                    bandsOfKind(run, "GRAND_TOTAL").get(0), ReportBandLayouts.GRAND_TOTAL_LABEL_WIDTH);

            assertThat(pageRun)
                    .as("the page leader reaches the stream at its declared width")
                    .isEqualTo(ReportBandLayouts.PAGE_TOTAL_LEADER_WIDTH);
            assertThat(cardRun)
                    .as("the card-break leader reaches the stream at its declared width")
                    .isEqualTo(ReportBandLayouts.ACCOUNT_TOTAL_LEADER_WIDTH);
            assertThat(grandRun)
                    .as("the grand leader reaches the stream at its declared width")
                    .isEqualTo(ReportBandLayouts.GRAND_TOTAL_LEADER_WIDTH);
            assertThat(cardRun)
                    .as("the card-break leader is two bytes shorter, compensating its two-byte wider"
                            + " label so that all three amounts share one column")
                    .isEqualTo(pageRun - 2)
                    .isEqualTo(grandRun - 2);
        }

        /**
         * Asserts that the detail mask and the total mask remain two different regimes in the stream.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code app/cpy/CVTRA07Y.cpy} L30 declares the detail amount
         * {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}, which leaves the sign position BLANK for a positive value, while
         * its L54, L60 and L66 declare the three totals {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}, which always print a
         * polarity. {@code CobolEditMaskTest} proves each regime in isolation; this case asserts the
         * service does not route a total through the detail encoder or the reverse, which is the one way
         * the two could be unified without either encoder changing.</p>
         */
        @Test
        @DisplayName("the detail mask blanks a positive sign where the total mask prints one")
        void theDetailMaskAndTheTotalMaskRemainDistinct() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            List<String> positiveDetails = new ArrayList<>();
            for (String detail : bandsOfKind(run, "DETAIL")) {
                String amount = amountColumnOf(detail);
                if (!amount.isBlank() && !amount.startsWith("-")) {
                    positiveDetails.add(amount);
                }
            }
            assertThat(positiveDetails)
                    .as("the fixture carries positive detail amounts")
                    .isNotEmpty();
            assertThat(positiveDetails)
                    .allSatisfy(amount -> assertThat(amount)
                            .as("a positive detail amount leaves the leading sign position blank")
                            .startsWith(" "));

            assertThat(bandsOfKind(run, "GRAND_TOTAL"))
                    .allSatisfy(band -> assertThat(amountColumnOf(band).trim())
                            .as("a non-zero total always prints its polarity")
                            .startsWith("+"));
        }

        /**
         * Asserts that a zero amount blanks the whole 15-column item rather than printing a zero.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: every digit position of both masks at L30 and L54 of {@code app/cpy/CVTRA07Y.cpy} is a
         * {@code Z} and none is a {@code 9}, cents included, so suppression reaches the whole item and a
         * zero prints as blanks rather than as {@code 0.00}. The fixture carries transaction
         * {@code 0000000000000024} at exactly zero for this case.</p>
         */
        @Test
        @DisplayName("a zero detail amount blanks all fifteen columns")
        void aZeroDetailAmountBlanksAllFifteenColumns() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            String zeroLine = bandsOfKind(run, "DETAIL").stream()
                    .filter(detail -> detail.startsWith("0000000000000024"))
                    .findFirst()
                    .orElseThrow();

            assertThat(amountColumnOf(zeroLine))
                    .as("a zero amount suppresses every digit, both commas and the decimal point")
                    .isEqualTo(" ".repeat(CobolEditMask.REPORT_AMOUNT_WIDTH))
                    .hasSize(CobolEditMask.REPORT_AMOUNT_WIDTH);
        }

        /**
         * Asserts that the mask ceiling renders in full at both polarities.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code 999,999,999.99} exactly fills the nine integer positions the detail mask at L30
         * of {@code app/cpy/CVTRA07Y.cpy} provides, which is also the declared domain of
         * {@code TRAN-AMT PIC S9(09)V99} in {@code app/cpy/CVTRA05Y.cpy}. The fixture carries
         * transaction {@code 0000000000000022} at the positive ceiling and
         * {@code 0000000000000023} at the negative one, so both signs are exercised at the boundary where
         * suppression stops and truncation would begin.</p>
         */
        @Test
        @DisplayName("the nine-digit ceiling renders in full at both polarities")
        void theMaskCeilingRendersInFullAtBothPolarities() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));
            List<String> details = bandsOfKind(run, "DETAIL");

            String positiveCeiling = details.stream()
                    .filter(detail -> detail.startsWith("0000000000000022"))
                    .findFirst()
                    .orElseThrow();
            String negativeCeiling = details.stream()
                    .filter(detail -> detail.startsWith("0000000000000023"))
                    .findFirst()
                    .orElseThrow();

            assertThat(amountColumnOf(positiveCeiling))
                    .as("the positive ceiling keeps a blank sign position and both group separators")
                    .isEqualTo(" 999,999,999.99");
            assertThat(amountColumnOf(negativeCeiling))
                    .as("the negative ceiling prints a minus in the sign position")
                    .isEqualTo("-999,999,999.99");
        }

        /**
         * Asserts that the category code keeps its leading zeros while amounts suppress theirs.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code TRAN-REPORT-CAT-CD} is declared {@code PIC 9(04)} at L24 of
         * {@code app/cpy/CVTRA07Y.cpy}, which is a zero-filled regime and not the {@code Z} suppression
         * regime the amount items use two lines apart. A code of one therefore prints {@code 0001} on the
         * same record where a zero amount prints blanks, and conflating the two regimes is the error this
         * case exists to catch.</p>
         */
        @Test
        @DisplayName("the category code keeps its leading zeros where the amount suppresses them")
        void theCategoryCodeKeepsItsLeadingZeros() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            int categoryOffset = ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                    .field(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_CD).start();
            int categoryWidth = ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                    .field(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_CD).length();

            List<String> codes = new ArrayList<>();
            for (String detail : bandsOfKind(run, "DETAIL")) {
                codes.add(detail.substring(categoryOffset, categoryOffset + categoryWidth));
            }

            assertThat(codes).as("the run emitted detail lines to read a category code from").isNotEmpty();
            assertThat(codes).allSatisfy(code -> assertThat(code)
                    .as("every category code fills its declared positions with digits")
                    .matches("\\d{" + categoryWidth + "}"));
            assertThat(codes)
                    .as("a low code is zero-filled rather than suppressed, which the fixture's"
                            + " category 1 rows demonstrate")
                    .contains("0001");
        }

        /**
         * Asserts that both single-byte hyphen joiners reach the emitted detail band at their positions.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: the two {@code FILLER PIC X(01) VALUE '-'} items at L21 and L25 of
         * {@code app/cpy/CVTRA07Y.cpy} are data rather than padding: they join a code to its description
         * so that a detail line reads as a code, a hyphen and a description. They sit between the type
         * code and the type description and between the category code and the category description, and
         * an emitter that treated them as blank padding would still produce a 133-column record.</p>
         */
        @Test
        @DisplayName("both hyphen joiners reach the emitted detail band")
        void bothHyphenJoinersReachTheEmittedDetailBand() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));
            List<String> details = bandsOfKind(run, "DETAIL");

            int afterTypeCode = ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                    .field(ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_DESC).start() - 1;
            int afterCategoryCode = ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                    .field(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_DESC).start() - 1;

            assertThat(details).as("the run emitted detail lines").isNotEmpty();
            assertThat(details).allSatisfy(detail -> {
                assertThat(detail.charAt(afterTypeCode))
                        .as("the joiner between the type code and its description")
                        .isEqualTo(ReportBandLayouts.CODE_DESCRIPTION_JOINER.charAt(0));
                assertThat(detail.charAt(afterCategoryCode))
                        .as("the joiner between the category code and its description")
                        .isEqualTo(ReportBandLayouts.CODE_DESCRIPTION_JOINER.charAt(0));
            });
        }

        /**
         * Asserts that the two heading bands carry their spacing-sensitive literals verbatim.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: two literals in {@code app/cpy/CVTRA07Y.cpy} carry significant leading or trailing
         * blanks that a trimming emitter would silently lose. Its L12 declares the range joiner as
         * {@code PIC X(04) VALUE ' to '}, with a space on each side inside the literal, and its L46
         * declares the amount heading as {@code '        Amount'} with eight leading spaces that right-
         * align the heading over the amount column. {@code ReportBandLayoutsTest} pins both literals to
         * their declarations; this case asserts they survive into the emitted heading bands.</p>
         */
        @Test
        @DisplayName("the heading bands carry the range joiner and the amount heading verbatim")
        void theHeadingBandsCarryTheirLiteralsVerbatim() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            assertThat(bandsOfKind(run, "NAME_HEADER"))
                    .as("the title band is emitted")
                    .isNotEmpty()
                    .allSatisfy(band -> assertThat(band)
                            .as("the two dates are joined by the four-byte joiner, spaces included")
                            .contains(CANONICAL_START + ReportBandLayouts.DATE_RANGE_JOINER
                                    + CANONICAL_END));

            assertThat(bandsOfKind(run, "COLUMN_HEADER"))
                    .as("the column band is emitted")
                    .isNotEmpty()
                    .allSatisfy(band -> assertThat(band)
                            .as("the amount heading keeps its eight leading spaces")
                            .contains(ReportBandLayouts.COLUMN_LABEL_AMOUNT));
        }
    }


    /**
     * Builds a synthetic line list of a chosen shape, so a paging boundary can be placed deliberately.
     *
     * <p>Assumptions: card numbers are rendered from an ascending counter, so the fingerprint order this
     * builder produces is the creation order and the expected band sequence is readable from the two
     * arguments alone. Amounts are distinct by construction, which is what lets a per-page figure
     * identify exactly which lines were summed into it -- equal amounts would make a page figure that
     * summed the wrong rows indistinguishable from one that summed the right ones.</p>
     *
     * @param cardCount how many distinct card groups to produce
     * @param rowsPerCard how many rows each card group carries
     * @return the lines in query order, ordered by fingerprint and then identifier; never {@code null}
     */
    private static List<TransactionReportRepository.ReportLine> syntheticLines(
            int cardCount, int rowsPerCard) {

        List<TransactionReportRepository.ReportLine> lines = new ArrayList<>();
        int ordinal = 0;
        for (int card = 1; card <= cardCount; card++) {
            for (int row = 1; row <= rowsPerCard; row++) {
                ordinal++;
                lines.add(resolvedLine(
                        String.format("%016d", ordinal),
                        String.format("%016d", card),
                        (long) card,
                        "01",
                        "Purchase",
                        "0001",
                        "Regular",
                        "POS",
                        Money.ofCents(100L + ordinal)));
            }
        }
        return lines;
    }

    /**
     * Sums the amounts of one half-open slice of a line list.
     *
     * @param lines the lines the slice is taken from
     * @param fromIndex the first index of the slice, inclusive
     * @param toIndex the index one past the slice, exclusive
     * @return the exact sum of that slice; never {@code null}
     */
    private static Money sumOf(
            List<TransactionReportRepository.ReportLine> lines, int fromIndex, int toIndex) {
        Money running = Money.ZERO;
        for (int index = fromIndex; index < toIndex; index++) {
            running = running.plus(lines.get(index).getAmount());
        }
        return running;
    }

    /**
     * The order and repetition of the bands a run emits, asserted against the reference's arithmetic.
     *
     * <p>Purpose: the page boundary is the one property of this report that cannot be read off any single
     * band, because it is produced by a counter that several different paragraphs advance by different
     * amounts. These cases derive the boundaries rather than assume them.</p>
     *
     * <p>WHY: the equality test is {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} at L282 of
     * {@code app/cbl/CBTRN03C.cbl}, over the counter declared at its L129 and L130 and the modulus of 20
     * declared at its L131 and L132.</p>
     */
    @Nested
    @DisplayName("the band sequence and the page boundaries")
    class BandSequenceAndPaging {

        /**
         * Asserts the emitted band sequence is exactly the one the line-counter arithmetic derives.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: this is the whole of {@code 1100-WRITE-TRANSACTION-REPORT} at L274 of
         * {@code app/cbl/CBTRN03C.cbl} read as a sequence -- its first-time heading at L275 to L280, its
         * page-break pair at L283 and L284, its detail at L289 -- together with the card break at its
         * L181 to L188 and the closing sequence its end-of-file branch drives at L202 and L203. Comparing
         * a whole sequence rather than a set of counts is what makes a misordered stream fail.</p>
         */
        @Test
        @DisplayName("the emitted band sequence matches the derived line-counter model exactly")
        void theEmittedBandSequenceMatchesTheDerivedModel() {
            List<TransactionReportRepository.ReportLine> lines =
                    fixtureLines(CANONICAL_START, CANONICAL_END);
            EmissionRun run = emit(lines);

            assertThat(run.sink().bandSequence())
                    .as("every band, in the order the reference's counter dictates")
                    .containsExactlyElementsOf(derivedBandSequence(lines));
        }

        /**
         * Asserts the number of detail lines per page varies, so no fixed rows-per-page holds.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Alternatives Considered: asserting a fixed count of detail lines per page. Rejected, and this
         * case is what makes the rejection checkable. A page break costs two advances for the page band
         * pair at L299 and L302 of {@code app/cbl/CBTRN03C.cbl} plus four for the heading block at its
         * L327, L331, L335 and L339, and a card break costs two more at its L311 and L314, so the counter
         * crosses a multiple of the modulus by different margins on different pages. The shipped oracle
         * shows the same effect at scale, holding pages of unequal length across its 14 page bands.</p>
         */
        @Test
        @DisplayName("detail lines per page vary, so no fixed rows-per-page expectation can hold")
        void detailLinesPerPageVary() {
            List<TransactionReportRepository.ReportLine> lines =
                    fixtureLines(CANONICAL_START, CANONICAL_END);
            EmissionRun run = emit(lines);

            List<Integer> perPage = new ArrayList<>();
            int onThisPage = 0;
            for (String band : run.sink().bandSequence()) {
                if (band.equals("DETAIL")) {
                    onThisPage++;
                } else if (band.equals("PAGE_TOTAL")) {
                    perPage.add(onThisPage);
                    onThisPage = 0;
                }
            }

            assertThat(perPage)
                    .as("the canonical range breaks the page more than once, so there is a shape to vary")
                    .hasSizeGreaterThan(1);
            assertThat(perPage)
                    .as("at least two pages hold different numbers of detail lines, which is the"
                            + " property a fixed rows-per-page emitter cannot reproduce")
                    .doesNotHaveDuplicates();
            assertThat(perPage)
                    .as("no page holds a number of detail lines equal to the counter modulus, which is"
                            + " the number a rows-per-page reading of L282 would produce")
                    .doesNotContain(TransactionReportService.LINE_COUNTER_MODULUS);
        }

        /**
         * Asserts the blank band is emitted exactly once for each heading block and never alone.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code WS-BLANK-LINE} is declared {@code PIC X(133) VALUE SPACES} at L133 of
         * {@code app/cbl/CBTRN03C.cbl} and is written at exactly one site, L329, inside
         * {@code 1120-WRITE-HEADERS} at its L324. It is therefore a component of the heading block rather
         * than a separator an emitter may insert where it likes.</p>
         */
        @Test
        @DisplayName("the blank band is emitted exactly once per heading block")
        void theBlankBandIsEmittedOncePerHeadingBlock() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));
            List<String> sequence = run.sink().bandSequence();

            long headingBlocks =
                    sequence.stream().filter(band -> band.equals("NAME_HEADER")).count();
            long blanks = sequence.stream().filter(band -> band.equals("BLANK")).count();

            assertThat(headingBlocks).as("the run opened at least one page").isPositive();
            assertThat(blanks)
                    .as("one blank band per heading block, from the single write site at L329")
                    .isEqualTo(headingBlocks);
            for (int index = 0; index < sequence.size(); index++) {
                if (sequence.get(index).equals("BLANK")) {
                    assertThat(sequence.get(index - 1))
                            .as("the blank band follows the title band, as L325 then L329 order them")
                            .isEqualTo("NAME_HEADER");
                }
            }
        }

        /**
         * Asserts the separator rule is emitted at each of its three sites and is not de-duplicated.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code TRANSACTION-HEADER-2} is written at three separate sites in
         * {@code app/cbl/CBTRN03C.cbl} -- L300 closing the page-total block, L312 closing the card-break
         * block and L337 closing the heading block -- so a run emits one separator per page band, one per
         * card-break band and one per heading block. Collapsing consecutive separators would shorten the
         * record stream while leaving every individual band correct, which is why the repetition is
         * asserted rather than the presence.</p>
         */
        @Test
        @DisplayName("the separator rule is emitted at all three of its sites, not de-duplicated")
        void theSeparatorRuleIsEmittedAtAllThreeSites() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));
            List<String> sequence = run.sink().bandSequence();

            long headingBlocks = sequence.stream().filter(band -> band.equals("NAME_HEADER")).count();
            long separators = sequence.stream().filter(band -> band.equals("SEPARATOR")).count();

            assertThat(separators)
                    .as("one separator per heading block, per page band and per card-break band")
                    .isEqualTo(headingBlocks
                            + run.summary().pageTotalBands()
                            + run.summary().accountTotalBands());
            assertThat(bandsOfKind(run, "SEPARATOR"))
                    .as("every separator is the full-width all-hyphen rule of L48 of"
                            + " app/cpy/CVTRA07Y.cpy")
                    .allSatisfy(band -> assertThat(band)
                            .isEqualTo(ReportBandLayouts.SEPARATOR_RULE));
        }

        /**
         * Asserts a synthetic portfolio breaks its page where the derived model says it does.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: the fixture-driven case above proves the model over the shipped rows, and this one
         * proves it over a shape chosen so that a card break and a page break interact -- eight cards of
         * three rows each drives seven card breaks through the same counter the page test at L282 of
         * {@code app/cbl/CBTRN03C.cbl} reads, which is the interaction that moves a boundary away from any
         * multiple of the modulus.</p>
         */
        @Test
        @DisplayName("a card break shifts the page boundary, and the model predicts where")
        void aCardBreakShiftsThePageBoundary() {
            List<TransactionReportRepository.ReportLine> lines = syntheticLines(8, 3);
            EmissionRun run = emit(lines);

            assertThat(run.sink().bandSequence())
                    .as("the model accounts for the two-advance card break as well as the page break")
                    .containsExactlyElementsOf(derivedBandSequence(lines));
            assertThat(run.summary().accountTotalBands())
                    .as("one card-break band per card, the last one from the closing sequence")
                    .isEqualTo(8);
        }
    }

    /**
     * The three accumulators, their reset points and the key the subtotal breaks on.
     *
     * <p>Purpose: the report keeps three running figures at once and each is reset at a different point.
     * These cases pin which lines land in which figure, which is what a wrong reset or a wrong grouping
     * key changes.</p>
     *
     * <p>WHY: the accumulators are declared {@code PIC S9(09)V99 VALUE 0} at L134, L135 and L136 of
     * {@code app/cbl/CBTRN03C.cbl}; the detail path adds into two of them at its L287 and L288.</p>
     */
    @Nested
    @DisplayName("the three accumulators and the control break")
    class TotallingAndControlBreak {

        /**
         * Asserts each page band carries only the lines of its own page, proving the reset.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the page figure is reset immediately after it is rolled into the grand figure --
         * {@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL} at L297 of {@code app/cbl/CBTRN03C.cbl} and then
         * {@code MOVE 0 TO WS-PAGE-TOTAL} at its L298. The reset is the discriminating property here, and
         * not any numeric divergence: with exact fixed-point addition a roll-up of per-page figures and a
         * single sum over every row are equal by construction, so no fixture can make them differ. What an
         * implementation that omitted the reset would produce is a CUMULATIVE second page figure and a
         * grand figure that no longer matches the rows -- which both this case and the one below fail
         * on.</p>
         *
         * <p>WHY: the Nth emitted detail band is the Nth line the run read, so the page a line fell on is
         * recoverable from the emitted sequence without parsing any amount back out of an edit mask.</p>
         */
        @Test
        @DisplayName("each page band carries only its own page's lines")
        void eachPageBandCarriesOnlyItsOwnPagesLines() {
            List<TransactionReportRepository.ReportLine> lines = syntheticLines(6, 5);
            EmissionRun run = emit(lines);

            List<String> emittedPageAmounts = new ArrayList<>();
            List<Money> expectedPageAmounts = new ArrayList<>();
            int detailIndex = 0;
            int pageStart = 0;
            for (String rendered : run.sink().renderedRecords()) {
                String kind = bandKindOf(rendered.getBytes(StandardCharsets.US_ASCII));
                if (kind.equals("DETAIL")) {
                    detailIndex++;
                } else if (kind.equals("PAGE_TOTAL")) {
                    emittedPageAmounts.add(amountColumnOf(rendered));
                    expectedPageAmounts.add(sumOf(lines, pageStart, detailIndex));
                    pageStart = detailIndex;
                }
            }

            assertThat(emittedPageAmounts)
                    .as("this portfolio breaks the page, so there is more than one page figure to check")
                    .hasSizeGreaterThan(1);
            for (int page = 0; page < emittedPageAmounts.size(); page++) {
                assertThat(emittedPageAmounts.get(page))
                        .as("page %d carries only the lines that fell on it", page + 1)
                        .isEqualTo(CobolEditMask.formatReportTotalAmount(
                                expectedPageAmounts.get(page)));
            }
        }

        /**
         * Asserts the grand figure is the sum of the emitted page figures.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the grand figure accumulates from the PAGE figure at L297 of
         * {@code app/cbl/CBTRN03C.cbl} rather than from each transaction amount, so the two must agree
         * band for band. Because the page figures partition the run's lines exactly once each, that sum is
         * also the sum of every line, and this case asserts both identities so a maintainer can see they
         * are expected to coincide rather than suspect one of being a coincidence.</p>
         */
        @Test
        @DisplayName("the grand figure is the sum of the page figures, and of every line once")
        void theGrandFigureIsTheRollUpOfThePageFigures() {
            List<TransactionReportRepository.ReportLine> lines = syntheticLines(6, 5);
            EmissionRun run = emit(lines);

            Money everyLineOnce = sumOf(lines, 0, lines.size());

            assertThat(bandsOfKind(run, "GRAND_TOTAL"))
                    .as("a run closes with exactly one grand band")
                    .hasSize(1);
            assertThat(amountColumnOf(bandsOfKind(run, "GRAND_TOTAL").get(0)))
                    .as("the grand band carries the roll-up of the page figures")
                    .isEqualTo(CobolEditMask.formatReportTotalAmount(everyLineOnce));
            assertThat(run.summary().grandTotal())
                    .as("the summary reports the same closing figure the band carries")
                    .isEqualTo(everyLineOnce);
        }

        /**
         * Asserts each card-break band carries only its own card's lines.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the card figure is reset only inside {@code 1120-WRITE-ACCOUNT-TOTALS}, at L310
         * of {@code app/cbl/CBTRN03C.cbl}, and nowhere in the page path -- so a page break in the middle
         * of a card must not disturb it. Two consecutive cards sharing one accumulator is exactly what a
         * reset placed in the page path would produce.</p>
         */
        @Test
        @DisplayName("each card-break band carries only its own card's lines, across a page break")
        void eachCardBreakBandCarriesOnlyItsOwnCardsLines() {
            List<TransactionReportRepository.ReportLine> lines = syntheticLines(4, 9);
            EmissionRun run = emit(lines);

            List<String> emittedCardAmounts = new ArrayList<>();
            List<Money> expectedCardAmounts = new ArrayList<>();
            int detailIndex = 0;
            int cardStart = 0;
            for (String rendered : run.sink().renderedRecords()) {
                String kind = bandKindOf(rendered.getBytes(StandardCharsets.US_ASCII));
                if (kind.equals("DETAIL")) {
                    detailIndex++;
                } else if (kind.equals("ACCOUNT_TOTAL")) {
                    emittedCardAmounts.add(amountColumnOf(rendered));
                    expectedCardAmounts.add(sumOf(lines, cardStart, detailIndex));
                    cardStart = detailIndex;
                }
            }

            assertThat(emittedCardAmounts).as("one band per card group").hasSize(4);
            for (int card = 0; card < emittedCardAmounts.size(); card++) {
                assertThat(emittedCardAmounts.get(card))
                        .as("card group %d carries its own nine lines and no others", card + 1)
                        .isEqualTo(CobolEditMask.formatReportTotalAmount(
                                expectedCardAmounts.get(card)));
            }
        }

        /**
         * Asserts the subtotal breaks on the card even where one account holds two cards.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the break key and the band label name different things, and both are carried as
         * they are. {@code app/cbl/CBTRN03C.cbl} compares {@code WS-CURR-CARD-NUM} -- declared
         * {@code PIC X(16)} at its L137 -- against the transaction's card at its L181, and
         * {@code app/jcl/TRANREPT.jcl} L46 orders the input by that same card, while the band the break
         * emits is labelled {@code 'Account Total'} by L57 and L58 of {@code app/cpy/CVTRA07Y.cpy}. That
         * label-to-key mismatch is an observation about an immutable baseline and is reproduced exactly,
         * label text included; the fixture {@code cardxref.txt} places two of its four cards on one
         * account so that a run grouping by account identifier would emit three bands where this asserts
         * four.</p>
         */
        @Test
        @DisplayName("the subtotal breaks on the card, and keeps the 'Account Total' label verbatim")
        void theSubtotalBreaksOnTheCardNotTheAccount() {
            List<TransactionReportRepository.ReportLine> lines =
                    fixtureLines(CANONICAL_START, CANONICAL_END);

            long distinctCards = lines.stream()
                    .map(TransactionReportRepository.ReportLine::getCardFingerprint)
                    .distinct()
                    .count();
            long distinctAccounts = lines.stream()
                    .map(TransactionReportRepository.ReportLine::getAccountId)
                    .distinct()
                    .count();
            assertThat(distinctCards)
                    .as("the fixture pair places two cards on one account, so the two groupings differ")
                    .isGreaterThan(distinctAccounts);

            EmissionRun run = emit(lines);

            assertThat(run.summary().accountTotalBands())
                    .as("one band per CARD, which grouping by account identifier cannot produce")
                    .isEqualTo(distinctCards);
            assertThat(bandsOfKind(run, "ACCOUNT_TOTAL"))
                    .allSatisfy(band -> assertThat(band)
                            .as("the baseline's own label text, carried character for character")
                            .startsWith(ReportBandLayouts.ACCOUNT_TOTAL_LABEL));
        }

        /**
         * Asserts the account is resolved once per distinct card and not once per transaction.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code 1500-A-LOOKUP-XREF} at L484 of {@code app/cbl/CBTRN03C.cbl} is performed from a
         * single site, L187, which sits INSIDE the card-break block its L181 opens -- while the type and
         * category lookups sit outside that block at its L190 and L195. The account a detail line prints
         * is therefore the one resolved at its card's break, and every line of the card prints that one
         * value. Counting the reads is how the placement is observed: a resolution moved out of the break
         * block would read once per row and still produce an identical report.</p>
         */
        @Test
        @DisplayName("the account is resolved once per distinct card, not once per transaction")
        void theAccountIsResolvedOncePerDistinctCard() {
            List<TransactionReportRepository.ReportLine> plain = syntheticLines(3, 7);
            int[] accountReads = {0};
            List<TransactionReportRepository.ReportLine> counted = new ArrayList<>();
            for (TransactionReportRepository.ReportLine line : plain) {
                counted.add(countingAccountReads(line, accountReads));
            }

            EmissionRun run = emit(counted);

            assertThat(run.summary().detailLines())
                    .as("all twenty-one rows were reported")
                    .isEqualTo(21);
            assertThat(accountReads[0])
                    .as("three reads for three cards, not one read for each of twenty-one rows")
                    .isEqualTo(3);
        }
    }

    /**
     * Wraps one line so that reads of its account can be counted.
     *
     * <p>Assumptions: only the account accessor is instrumented. Every other accessor delegates
     * unchanged, because the band content must stay identical to the uninstrumented run for the count to
     * be the only difference between them.</p>
     *
     * @param delegate the line to wrap
     * @param reads a single-element counter incremented on each account read
     * @return a line that behaves as {@code delegate} and counts its account reads; never {@code null}
     */
    private static TransactionReportRepository.ReportLine countingAccountReads(
            TransactionReportRepository.ReportLine delegate, int[] reads) {

        return new TransactionReportRepository.ReportLine() {
            /** {@return the wrapped line's transaction identifier, unchanged} */
            @Override
            public String getTransactionId() {
                return delegate.getTransactionId();
            }

            /** {@return the wrapped line's card rendering, unchanged} */
            @Override
            public String getCardNum() {
                return delegate.getCardNum();
            }

            /** {@return the wrapped line's grouping key, unchanged} */
            @Override
            public String getCardFingerprint() {
                return delegate.getCardFingerprint();
            }

            /**
             * Counts this read, then answers it.
             *
             * <p>Assumptions: the counter is incremented before the delegation rather than after, so a
             * read that the delegate answered with a refusal is still counted. The placement of the
             * resolution is what is under observation, and a read that happened is a read whether or not
             * it succeeded.</p>
             *
             * @return the wrapped line's account, unchanged
             */
            @Override
            public Long getAccountId() {
                reads[0]++;
                return delegate.getAccountId();
            }

            /** {@return the wrapped line's type code, unchanged} */
            @Override
            public String getTypeCd() {
                return delegate.getTypeCd();
            }

            /** {@return the wrapped line's type description, unchanged} */
            @Override
            public String getTypeDescription() {
                return delegate.getTypeDescription();
            }

            /** {@return the wrapped line's category code, unchanged} */
            @Override
            public String getCategoryCd() {
                return delegate.getCategoryCd();
            }

            /** {@return the wrapped line's category description, unchanged} */
            @Override
            public String getCategoryDescription() {
                return delegate.getCategoryDescription();
            }

            /** {@return the wrapped line's source, unchanged} */
            @Override
            public String getSource() {
                return delegate.getSource();
            }

            /** {@return the wrapped line's amount, unchanged} */
            @Override
            public Money getAmount() {
                return delegate.getAmount();
            }
        };
    }


    /**
     * How the requested date range reaches the cursor, and what an absent or empty range produces.
     *
     * <p>Purpose: the selection predicate itself belongs to the repository, so these cases assert the
     * BOUNDS this service derives from its two parameters and hands over. Whether the SQL admits both ends
     * is proved by {@code TransactionReportRepositoryIT}, which also records that the
     * {@code INCLUDE COND=} at L47 and L48 of {@code app/jcl/TRANREPT.jcl} becomes a query restriction and
     * never an orchestration branch.</p>
     *
     * <p>WHY: the reference compares {@code TRAN-PROC-TS (1:10)} against both bounds at its L173 and L174
     * of {@code app/cbl/CBTRN03C.cbl}, a reference modification that reads the first 10 of the
     * 26-character timestamp, so only the date part takes part in the comparison.</p>
     */
    @Nested
    @DisplayName("the requested date range")
    class RangeSelection {

        /**
         * Asserts the derived bounds admit the whole of both boundary dates and nothing outside them.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: an inclusive end DATE becomes a half-open upper bound at the start of the following day,
         * which is the only form that admits every instant of the end date whatever its time carries. The
         * reference reaches the same outcome by comparing date parts at L173 and L174 of
         * {@code app/cbl/CBTRN03C.cbl}; a bound taken at the start of the end date itself would silently
         * drop every transaction of the last day of the range but midnight.</p>
         */
        @Test
        @DisplayName("both boundary dates are admitted whole, and the day after is excluded")
        void bothBoundaryDatesAreAdmittedWhole() {
            emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            org.mockito.ArgumentCaptor<LocalDateTime> from =
                    org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
            org.mockito.ArgumentCaptor<LocalDateTime> until =
                    org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
            verify(reports).streamReportLinesWithin(from.capture(), until.capture());

            assertThat(from.getValue())
                    .as("the lower bound opens at the first instant of the start date, so the whole of"
                            + " that day is admitted")
                    .isEqualTo(TimestampFormatter.normalize(CANONICAL_START.atStartOfDay()));
            assertThat(until.getValue())
                    .as("the upper bound closes at the first instant AFTER the end date, so the whole of"
                            + " the end date is admitted and the following day is not")
                    .isEqualTo(TimestampFormatter.normalize(
                            CANONICAL_END.plusDays(1).atStartOfDay()));
            assertThat(until.getValue())
                    .as("the upper bound lies strictly after the last instant the end date can carry")
                    .isAfter(CANONICAL_END.atTime(23, 59, 59));
        }

        /**
         * Asserts an absent bound is refused by name rather than producing an empty report.
         *
         * <p>This case takes no parameter and yields no value. The captured exception is
         * {@link ClientInputException}, carried out of the range check before any read is issued; it has
         * no nested cause because an absent argument is detected rather than caught.</p>
         *
         * <p>Refactoring Rationale: this replaces the reference's own outcome instead of restating it.
         * {@code 0550-DATEPARM-READ} at L220 to L243 of {@code app/cbl/CBTRN03C.cbl} reads its parameter
         * record once, and on reaching end of data its L236 moves the end-of-file marker on, so the read
         * loop at its L170 never iterates: the program terminates having produced no report, with no error
         * raised. A scheduled run would publish an empty artifact and report success. Refusing the
         * condition by name is the documented divergence, and it is asserted here rather than the
         * baseline's silence.</p>
         */
        @Test
        @DisplayName("an absent bound is refused by name and no read is issued")
        void anAbsentBoundIsRefusedByName() {
            RecordingSink sink = new RecordingSink();

            assertThatExceptionOfType(ClientInputException.class)
                    .isThrownBy(() -> service.generateReport(null, CANONICAL_END, sink))
                    .satisfies(refusal -> assertThat(refusal.fields()).contains("startDate"));
            assertThatExceptionOfType(ClientInputException.class)
                    .isThrownBy(() -> service.generateReport(CANONICAL_START, null, sink))
                    .satisfies(refusal -> assertThat(refusal.fields()).contains("endDate"));

            assertThat(sink.records).as("a refused range emits no record at all").isEmpty();
            verify(reports, never()).streamReportLinesWithin(any(), any());
        }

        /**
         * Asserts an absent sink is refused before any read is issued.
         *
         * <p>This case takes no parameter and yields no value. The captured exception is
         * {@link NullPointerException}, raised by the entry point's own argument check.</p>
         *
         * <p>WHY: the reference names its destination on the data-definition statement at L76 to L80 of
         * {@code app/jcl/TRANREPT.jcl}, so a run with no destination is a job that does not start. The
         * check is asserted to happen before the cursor opens, because a cursor opened and then abandoned
         * holds a driver-side result set for the life of the transaction.</p>
         */
        @Test
        @DisplayName("an absent sink is refused before the cursor opens")
        void anAbsentSinkIsRefusedBeforeTheCursorOpens() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.generateReport(CANONICAL_START, CANONICAL_END, null));

            verify(reports, never()).streamReportLinesWithin(any(), any());
        }

        /**
         * Asserts a range resolving no transaction still closes the report and raises nothing.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: an absent range and a range that admits no row are different conditions and only
         * the first is refused. The reference's reading of an exhausted input still performs its page
         * totals at L202 and its grand totals at L203 of {@code app/cbl/CBTRN03C.cbl}, and emits no heading
         * at all because the heading is written from inside the detail paragraph at its L279 and that
         * paragraph never runs. The card-break band is also absent, because the first-time guard at its
         * L182 has never been cleared. That outcome is carried across unchanged, and the summary is what
         * tells a caller the report holds no detail line.</p>
         */
        @Test
        @DisplayName("a range resolving no transaction closes the report without a heading")
        void anEmptyRangeClosesTheReportWithoutAHeading() {
            EmissionRun run = emit(List.of());

            assertThat(run.summary().detailLines()).as("no transaction was reported").isZero();
            assertThat(run.summary().grandTotal())
                    .as("the closing figure of an empty run is exactly zero")
                    .isEqualTo(Money.ZERO);
            assertThat(run.sink().bandSequence())
                    .as("the closing page band, its separator and the grand band, and nothing else")
                    .containsExactly("PAGE_TOTAL", "SEPARATOR", "GRAND_TOTAL");
            assertThat(run.summary().accountTotalBands())
                    .as("no card group was ever opened, so none is closed")
                    .isZero();
        }
    }

    /**
     * What stops a run, and what the refusal is allowed to say.
     *
     * <p>Purpose: an unresolvable dimension is a failure of the run and never a skipped line. Each of the
     * three reference lookup paragraphs displays the offending key and then reaches one shared abend
     * paragraph, so a report is never published with a line missing.</p>
     *
     * <p>WHY: {@code 1500-A-LOOKUP-XREF} at L484 to L492 of {@code app/cbl/CBTRN03C.cbl},
     * {@code 1500-B-LOOKUP-TRANTYPE} at its L494 to L502 and {@code 1500-C-LOOKUP-TRANCATG} at its L504
     * to L512 each display their diagnostic, move 23 into the status field, report it through
     * {@code 9910-DISPLAY-IO-STATUS} at its L633 and abend through {@code 9999-ABEND-PROGRAM} at its L626
     * to L630, which calls {@code CEE3ABD} at its L630. The structural shape of the detail those paths
     * record is {@code ABEND-DATA} in {@code app/cpy/CSMSG02Y.cpy} at its L21 to L29.</p>
     *
     * <p>Assumptions: the policy asserted throughout this class is HARD ABEND on a missing dimension, and
     * it is the reference's own rather than a choice made here. All three lookup paragraphs at L484 to
     * L512 of {@code app/cbl/CBTRN03C.cbl} converge on the single abend paragraph at its L626 to L630,
     * which sets condition code 999 at its L629 and terminates the run: not one of the three has a path
     * that skips the offending record and carries on. Skipping would be the tempting alternative, and it
     * is the one behaviour these cases exist to rule out, because a report short one line still balances
     * internally -- every page and card figure is consistent with the lines it did include -- so a
     * silently incomplete artifact would reconcile against itself and be published as correct. The
     * detail carried out of each path is shortened to the widths {@code ABEND-DATA} declares in
     * {@code app/cpy/CSMSG02Y.cpy} at its L21 to L29, which the reason-width case below pins.</p>
     */
    @Nested
    @DisplayName("the failure paths")
    class FailurePaths {

        /**
         * Asserts a card the cross-reference cannot place stops the run.
         *
         * <p>This case takes no parameter and yields no value. The captured exception is
         * {@link IllegalStateException}; it carries no nested cause, because an absent account is detected
         * by an argument check rather than caught from a conversion, and its message carries the
         * reference's own diagnostic text for this condition.</p>
         *
         * <p>WHY: {@code 1500-A-LOOKUP-XREF} at L484 of {@code app/cbl/CBTRN03C.cbl} displays
         * {@code 'INVALID CARD NUMBER : '} at its L487 on the invalid-key path and then abends, so the line
         * is not skipped and no partial report is published.</p>
         */
        @Test
        @DisplayName("a card the cross-reference cannot place stops the run")
        void anUnplaceableCardStopsTheRun() {
            TransactionReportRepository.ReportLine orphan = resolvedLine(
                    "0000000000000031", "9999999999999999", null,
                    "01", "Purchase", "0001", "Regular", "POS", Money.of("1.00"));
            stubAssemblableRange();
            doReturn(Stream.of(orphan)).when(reports).streamReportLinesWithin(any(), any());
            RecordingSink sink = new RecordingSink();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.generateReport(CANONICAL_START, CANONICAL_END, sink))
                    .satisfies(abend -> {
                        assertThat(abend.getMessage())
                                .as("the refusal names the reference's own condition text")
                                .contains("INVALID CARD NUMBER");
                        assertThat(abend.getMessage())
                                .as("and the one abend paragraph's condition code and culprit")
                                .contains("0999")
                                .contains("CBTRN03C");
                    });
        }

        /**
         * Asserts a category code the band cannot carry stops the run.
         *
         * <p>This case takes no parameter and yields no value. The captured exception is
         * {@link IllegalStateException}, and its nested cause is a
         * {@link NumberFormatException} raised inside the conversion's own catch block -- which is the one
         * failure shape the documentation gate cannot see, because it does not inspect a throw raised from
         * inside a catch.</p>
         *
         * <p>WHY: {@code 1500-C-LOOKUP-TRANCATG} at L504 of {@code app/cbl/CBTRN03C.cbl} displays
         * {@code 'INVALID TRAN CATG KEY : '} at its L507 and abends. The band item is declared
         * {@code PIC 9(04)} at L24 of {@code app/cpy/CVTRA07Y.cpy}, so a value that is not a run of digits
         * contradicts a row the same query resolved a description for, and printing anything in its place
         * would put a line on the page for a category the report cannot name.</p>
         */
        @Test
        @DisplayName("a category code the band cannot carry stops the run")
        void anUnprintableCategoryCodeStopsTheRun() {
            TransactionReportRepository.ReportLine malformed = resolvedLine(
                    "0000000000000015", "4859452612877065", 7L,
                    "01", "Purchase", "ZZZZ", "Regular", "POS", Money.of("3.00"));
            stubAssemblableRange();
            doReturn(Stream.of(malformed)).when(reports).streamReportLinesWithin(any(), any());
            RecordingSink sink = new RecordingSink();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.generateReport(CANONICAL_START, CANONICAL_END, sink))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .as("the refusal names the reference's own condition text")
                            .contains("INVALID TRAN CATG KEY"))
                    .withCauseInstanceOf(NumberFormatException.class);
        }

        /**
         * Asserts a transaction whose dimensions do not resolve one-for-one stops the run.
         *
         * <p>This case takes no parameter and yields no value. The captured exception is
         * {@link IllegalStateException}, raised by the integrity probe before the cursor opens; it carries
         * no nested cause because the probe reports a row rather than catching a failure.</p>
         *
         * <p>WHY: this is the shared tail of all three lookup paragraphs, so the refusal composes all
         * three reference texts into one reason -- {@code 'INVALID CARD NUMBER : '} from L487 of
         * {@code app/cbl/CBTRN03C.cbl}, {@code 'INVALID TRANSACTION TYPE : '} from its L497 and
         * {@code 'INVALID TRAN CATG KEY : '} from its L507 -- without asserting which of the three
         * occurred. A cardinality probe establishes that one did and not which, because a multiple on one
         * dimension inflates the counts of the other two.</p>
         *
         * <p>Assumptions: the reason reaching the caller is SHORTENED to the width its record component
         * declares, so the third text does not survive into it. {@code ABEND-REASON} is declared
         * {@code PIC X(50)} at L26 of {@code app/cpy/CSMSG02Y.cpy} -- a 35-line copybook whose
         * {@code ABEND-DATA} group runs from its L21 to its L29 -- and the detail record shortens on the
         * right exactly as an alphanumeric {@code MOVE} into that item would, silently and without
         * refusing. Asserting the two texts that fit and the bound that cuts the third is therefore what
         * the contract actually is; asserting all three would be asserting a reason component wider than
         * the baseline declares.</p>
         */
        @Test
        @DisplayName("an unresolved dimension stops the run, naming what the reason width admits")
        void anUnresolvedDimensionStopsTheRun() {
            doReturn(List.of("0000000000000014")).when(reports)
                    .findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
            RecordingSink sink = new RecordingSink();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.generateReport(CANONICAL_START, CANONICAL_END, sink))
                    .satisfies(abend -> {
                        assertThat(abend.getMessage())
                                .as("the conditions the 50-byte reason component admits, and the"
                                        + " offending transaction the probe named")
                                .contains("INVALID CARD NUMBER")
                                .contains("INVALID TRANSACTION TYPE")
                                .contains("0000000000000014");
                        assertThat(AbendDetail.ABEND_REASON_LENGTH)
                                .as("the reason component is the PIC X(50) of app/cpy/CSMSG02Y.cpy L26,"
                                        + " which is why the third condition text does not fit")
                                .isEqualTo(50);
                        assertThat(abend.getMessage())
                                .as("the third text is shortened away rather than refused, which is how"
                                        + " an alphanumeric MOVE into that item behaves")
                                .doesNotContain("INVALID TRAN CATG KEY");
                    });

            assertThat(sink.records).as("nothing is published from a refused range").isEmpty();
            verify(reports, never()).streamReportLinesWithin(any(), any());
        }

        /**
         * Asserts a destination that cannot accept a record stops the run with the reference's own text.
         *
         * <p>This case takes no parameter and yields no value. The captured exception is
         * {@link IllegalStateException} and its nested cause is the {@link IOException} the destination
         * raised, kept as the cause so that a diagnosis can reach the original failure while the message a
         * caller receives carries none of its text.</p>
         *
         * <p>WHY: {@code 1111-WRITE-REPORT-REC} at L343 of {@code app/cbl/CBTRN03C.cbl} tests its write
         * status and, on anything other than success, displays {@code 'ERROR WRITING REPTFILE'} at its L354
         * before abending at its L356 and L357. The sibling case above asserts what the message must NOT
         * disclose; this one asserts the abend identity it must carry.</p>
         */
        @Test
        @DisplayName("a destination that refuses a record stops the run, carrying the abend identity")
        void aRefusingDestinationStopsTheRun() {
            stubAssemblableRange();
            doReturn(Stream.of(syntheticLines(1, 1).get(0)))
                    .when(reports).streamReportLinesWithin(any(), any());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.generateReport(CANONICAL_START, CANONICAL_END, record -> {
                        throw new IOException("device not ready");
                    }))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .as("the reference's own write-failure text, and the abend identity")
                            .contains("ERROR WRITING REPTFILE")
                            .contains("0999")
                            .contains("CBTRN03C"))
                    .withCauseInstanceOf(IOException.class);
        }

        /**
         * Asserts none of the three diagnostic texts can ever reach the report itself.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: all three are {@code DISPLAY} statements -- at L487, L497 and L507 of
         * {@code app/cbl/CBTRN03C.cbl} -- so they reach the job log and never the report data set the
         * separate funnel at its L343 writes. A diagnostic reaching the report would corrupt a
         * fixed-width artifact that downstream readers parse by column.</p>
         */
        @Test
        @DisplayName("no diagnostic text reaches the report stream")
        void noDiagnosticTextReachesTheReportStream() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            assertThat(run.sink().wholeStream())
                    .as("the three lookup diagnostics belong to the job log, not to the report")
                    .doesNotContain("INVALID CARD NUMBER")
                    .doesNotContain("INVALID TRANSACTION TYPE")
                    .doesNotContain("INVALID TRAN CATG KEY")
                    .doesNotContain("ERROR WRITING REPTFILE")
                    .doesNotContain("ABENDING PROGRAM");
        }
    }

    /**
     * What the emitted report must NOT carry.
     *
     * <p>Purpose: the reference leaves several diagnostic writes in its read loop and its end-of-file
     * branch, all of them to the job log. Each is a phrase that would be visible if the two channels were
     * ever merged, so each is asserted absent from the report itself.</p>
     *
     * <p>WHY: {@code DISPLAY TRAN-RECORD} at L180 of {@code app/cbl/CBTRN03C.cbl} writes the whole 350-byte
     * input record, its L198 and L199 write the amount and the running page figure, and its L232 and L233
     * write the resolved range -- four separate writes to a channel the report does not share.</p>
     */
    @Nested
    @DisplayName("what the report must not carry")
    class StreamHygiene {

        /**
         * Asserts the reference's job-log phrases are absent from the report.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: the four phrases are the literals of L198, L199 and L232 of
         * {@code app/cbl/CBTRN03C.cbl}. They name working-storage items and a progress message, none of
         * which is a column any reader of a 133-column artifact expects.</p>
         */
        @Test
        @DisplayName("no job-log phrase appears in the report")
        void noJobLogPhraseAppearsInTheReport() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            assertThat(run.sink().wholeStream())
                    .as("the leftover progress and working-storage displays are job-log only")
                    .doesNotContain("TRAN-AMT")
                    .doesNotContain("WS-PAGE-TOTAL")
                    .doesNotContain("Reporting from");
        }

        /**
         * Asserts the input record's non-report columns do not leak into the report.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: {@code DISPLAY TRAN-RECORD} at L180 of {@code app/cbl/CBTRN03C.cbl} writes the whole
         * input record, whose merchant columns the detail band declares no item for --
         * {@code app/cpy/CVTRA07Y.cpy} L15 to L31 carries an identifier, an account, a type, a category, a
         * source and an amount and nothing else. The fixture's merchant names and cities are therefore the
         * observable marker of that whole-record write reaching the wrong channel.</p>
         *
         * <p>Assumptions: the markers chosen are merchant names and merchant cities and NOT a phrase from
         * the transaction description, because the fixture's descriptions open with the same words as its
         * transaction-type descriptions -- {@code 'Purchase at ...'} appears in both -- and the type
         * description is a column the band legitimately carries, narrowed to the 15 positions its item
         * declares at L22 of {@code app/cpy/CVTRA07Y.cpy}. A description-derived marker would therefore
         * fail against a correct report. The merchant columns share no text with any dimension
         * description, so their absence is attributable to the band's declared items alone.</p>
         */
        @Test
        @DisplayName("the input record's merchant columns do not reach the report")
        void theInputRecordsExtraColumnsDoNotReachTheReport() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            assertThat(run.sink().wholeStream())
                    .as("the detail band declares no merchant name item and no merchant city item")
                    .doesNotContain("Ironwood Bindery")
                    .doesNotContain("Meadowbrook Supply")
                    .doesNotContain("Ferry Grove Fuel")
                    .doesNotContain("East Rosendale")
                    .doesNotContain("Lake Kaseyview");
        }

        /**
         * Asserts no full card number reaches the report.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: this is a regression guard rather than a masking check, and the distinction
         * matters. The detail band declares no card item at all, so
         * {@code TransactionReportMapper#encodeDetailLine} takes no card argument and there is nothing to
         * mask -- the assertion is that the report stays that way, because the grouping key the run breaks
         * on is derived from the card and a break marker naming its group would be the natural way to
         * reintroduce one. The masking of a card that IS published belongs to
         * {@code ReportingDtoMapperTest}.</p>
         */
        @Test
        @DisplayName("no full card number reaches the report")
        void noFullCardNumberReachesTheReport() {
            List<TransactionReportRepository.ReportLine> lines =
                    fixtureLines(CANONICAL_START, CANONICAL_END);
            EmissionRun run = emit(lines);
            String stream = run.sink().wholeStream();

            assertThat(stream)
                    .as("the four fixture cards, whole")
                    .doesNotContain("0500024453765740")
                    .doesNotContain("4859452612877065")
                    .doesNotContain("9900000000000502")
                    .doesNotContain("9900001020000001");
            for (TransactionReportRepository.ReportLine line : lines) {
                assertThat(stream)
                        .as("no run breaks on a key it also prints")
                        .doesNotContain(line.getCardFingerprint());
            }
        }
    }


    /**
     * The two registered divergences from the baseline's end-of-file branch.
     *
     * <p>Purpose: the reference's end-of-file branch does two things a reader of the source would not
     * expect, and both are visible in the shipped oracle. Neither is reproduced, both are registered, and
     * these cases assert the migrated behaviour the register describes rather than the baseline's.</p>
     *
     * <p>Assumptions: the branch at L197 to L204 of {@code app/cbl/CBTRN03C.cbl} fires exactly once, when
     * the read reaches end of file. Its L200 and L201 add an amount into the page and card-break
     * accumulators from the record area left over from the last successful read -- an amount its L287 and
     * L288 already counted -- and its L202 and L203 perform only the page totals and the grand totals, so
     * {@code 1120-WRITE-ACCOUNT-TOTALS} at its L306 is never reached. This is a TERMINAL FLUSH and not a
     * per-record double add: a reader who sees {@code ADD TRAN-AMT} in two places will otherwise suspect
     * every record of being counted twice, and only the last one is. Both are artifact observations of an
     * immutable baseline that stays exactly as it is.</p>
     *
     * <p>Trade-offs: because the migrated behaviour differs, a record comparison against a captured
     * baseline artifact differs in exactly two places -- one additional band at the very end, and the
     * final transaction's amount once rather than twice in the closing page and grand figures -- and has
     * to be read against the register. Both differences are bounded to the tail and neither appears
     * earlier in the report. The register entries are
     * {@code D-REPORT-CLOSING-TOTAL} and {@code D-REPORT-GRAND-TOTAL} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register.</p>
     */
    @Nested
    @DisplayName("the two registered end-of-file divergences")
    class DocumentedDivergences {

        /**
         * Asserts the final card group is closed by its own band, as the register describes.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: the baseline emits no closing card-break band, which its oracle shows directly -- the
         * final four lines of {@code tests/golden/reporting/e2e_full_cycle_report.expected} are a detail
         * line, a page total, a separator and the grand total, with no card-break band between them. The
         * migrated run closes the last group exactly as it closes every other, which is register entry
         * {@code D-REPORT-CLOSING-TOTAL}.</p>
         */
        @Test
        @DisplayName("the final card group is closed by its own band")
        void theFinalCardGroupIsClosedByItsOwnBand() {
            List<TransactionReportRepository.ReportLine> lines =
                    fixtureLines(CANONICAL_START, CANONICAL_END);
            EmissionRun run = emit(lines);
            List<String> sequence = run.sink().bandSequence();

            assertThat(sequence.subList(sequence.size() - 5, sequence.size()))
                    .as("the closing sequence carries the last group's own band before the page and"
                            + " grand bands, which the baseline's tail does not")
                    .containsExactly(
                            "ACCOUNT_TOTAL", "SEPARATOR", "PAGE_TOTAL", "SEPARATOR", "GRAND_TOTAL");

            long distinctCards = lines.stream()
                    .map(TransactionReportRepository.ReportLine::getCardFingerprint)
                    .distinct()
                    .count();
            assertThat(run.summary().accountTotalBands())
                    .as("every card group is closed, the last one included, so the band count equals the"
                            + " group count rather than one fewer")
                    .isEqualTo(distinctCards);
        }

        /**
         * Asserts the final transaction's amount is counted once rather than twice.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: the baseline's closing page figure carries the final transaction twice, which its oracle
         * shows arithmetically -- the closing page figure of {@code 5,046.19} in
         * {@code tests/golden/reporting/e2e_full_cycle_report.expected} is the {@code 4,442.97} of its ten
         * last-page detail lines plus that last line's own {@code 603.22} again. The migrated run counts
         * each amount once, which is register entry {@code D-REPORT-GRAND-TOTAL}. The doubled figure is
         * asserted absent as well as the correct one present, so the divergence is checkable in both
         * directions rather than only one.</p>
         */
        @Test
        @DisplayName("the final transaction's amount is counted once, not twice")
        void theFinalTransactionsAmountIsCountedOnce() {
            List<TransactionReportRepository.ReportLine> lines = syntheticLines(3, 4);
            EmissionRun run = emit(lines);

            Money everyLineOnce = sumOf(lines, 0, lines.size());
            Money lastAmount = lines.get(lines.size() - 1).getAmount();

            assertThat(run.summary().grandTotal())
                    .as("each amount contributes exactly once to the closing figure")
                    .isEqualTo(everyLineOnce);
            assertThat(run.summary().grandTotal())
                    .as("and specifically not the baseline's terminal-flush figure, which would carry the"
                            + " final amount a second time")
                    .isNotEqualTo(everyLineOnce.plus(lastAmount));
            assertThat(amountColumnOf(bandsOfKind(run, "GRAND_TOTAL").get(0)))
                    .as("the emitted band carries the same once-counted figure the summary reports")
                    .isEqualTo(CobolEditMask.formatReportTotalAmount(everyLineOnce));
        }
    }

    /**
     * That a run is reproducible, and that nothing in it reads a clock.
     *
     * <p>Purpose: the report exists to be compared against a captured artifact, so reproducibility is a
     * correctness property rather than a convenience. A generator that read the wall clock would produce a
     * different report every day from identical input and could never satisfy the comparison it is meant
     * to satisfy.</p>
     *
     * <p>Refactoring Rationale: the range arrives as a parameter, replacing the baseline's two-channel
     * arrangement in which {@code app/jcl/TRANREPT.jcl} fixes the selection range in its own DFSORT
     * symbols at L43 and L44 while the program reads its heading range from a separate parameter data set
     * at its L73 and L74. Nothing in that arrangement makes the two agree, so a baseline run could select
     * one range and head the report with another; one parameter feeding both removes the possibility.</p>
     */
    @Nested
    @DisplayName("reproducibility and the absence of a clock")
    class Determinism {

        /**
         * Asserts the service declares no clock among its collaborators or its parameters.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Alternatives Considered: injecting a {@link Clock} whose accessors throw, so that any
         * consultation would fail the run. Rejected because this service has no clock seam to inject one
         * through -- it declares a single collaborator, the query surface -- and adding a constructor
         * parameter merely to prove it is unused would be adding the very dependency the case exists to
         * deny. Asserting the absence directly is the stronger statement: an injected throwing clock only
         * proves the current code path does not consult it, whereas this proves no path can, because there
         * is nothing to consult. {@code ReportExecutionServiceTest} is the class in this package that owns
         * a clock that is actually used, at the request edge where a date preset is resolved.</p>
         */
        @Test
        @DisplayName("the service declares no clock, on any field, constructor or method")
        void theServiceDeclaresNoClock() {
            for (Field field : TransactionReportService.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s is not a time source", field.getName())
                        .isNotEqualTo(Clock.class);
            }
            for (Constructor<?> constructor
                    : TransactionReportService.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("no constructor takes a time source")
                        .doesNotContain(Clock.class);
            }
            for (java.lang.reflect.Method method
                    : TransactionReportService.class.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("method %s takes no time source", method.getName())
                        .doesNotContain(Clock.class);
            }
        }

        /**
         * Asserts two runs over identical input emit byte-identical streams.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>WHY: this is the property a golden-master comparison rests on. The two runs are driven from
         * one line list through two separate cursors, so anything carried between them -- a field holding
         * an accumulator, a counter surviving a run, a value read from the environment -- would show as a
         * difference in the second stream. The reference's own accumulators at L134 to L136 of
         * {@code app/cbl/CBTRN03C.cbl} are process-wide working storage, which is exactly the arrangement
         * that would fail here.</p>
         */
        @Test
        @DisplayName("two runs over identical input emit byte-identical streams")
        void twoRunsOverIdenticalInputAreByteIdentical() {
            List<TransactionReportRepository.ReportLine> lines =
                    fixtureLines(CANONICAL_START, CANONICAL_END);

            EmissionRun first = emit(lines);
            EmissionRun second = emit(lines);

            assertThat(second.sink().renderedRecords())
                    .as("no accumulator, counter or environment value survives one run into the next")
                    .containsExactlyElementsOf(first.sink().renderedRecords());
            assertThat(second.summary())
                    .as("and the two runs report the same counts and the same closing figure")
                    .isEqualTo(first.summary());
        }

        /**
         * Asserts every emitted record is a freshly allocated array.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the reference moves each band into ONE shared record area,
         * {@code FD-REPTFILE-REC} declared at L85 of {@code app/cbl/CBTRN03C.cbl}, and writes it before
         * the next move overwrites it. A destination that buffered rather than wrote immediately would, on
         * that arrangement, hold many references to one area and flush the last band many times. Handing
         * out a distinct array per record is what makes a buffering destination safe, and it is asserted
         * because a sink is free to buffer and nothing else would reveal the sharing.</p>
         */
        @Test
        @DisplayName("every emitted record is a freshly allocated array")
        void everyEmittedRecordIsFreshlyAllocated() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            List<byte[]> records = run.sink().records;
            for (int outer = 0; outer < records.size(); outer++) {
                for (int inner = outer + 1; inner < records.size(); inner++) {
                    assertThat(records.get(outer))
                            .as("records %d and %d are separate arrays", outer, inner)
                            .isNotSameAs(records.get(inner));
                }
            }
        }
    }

    /**
     * How an emitted stream is compared against the shipped baseline artifact.
     *
     * <p>Purpose: the oracle and the emitted stream are in different forms, and the normalisation between
     * them is stated here once so that no case is written against the wrong one.</p>
     *
     * <p>Assumptions: {@code tests/golden/reporting/e2e_full_cycle_report.expected} stores 14 lines of 0
     * bytes, 546 of 112, 14 of 115 and 283 of 133 -- measured on the shipped file, not estimated --
     * because the harness right-strips each fixed record as it frames the runtime's output for storage,
     * in {@code _frame_report} at L516 of {@code tests/e2e/test_full_batch_cycle.py}. The runtime itself
     * drops nothing: the reference writes fixed 133-byte records. The emitted record is normalised DOWN
     * to the stored form for any comparison; the padding requirement itself is never relaxed, which the
     * emitted-stream cases assert separately.</p>
     *
     * <p>⚠️ Refactoring Rationale: this suite CONSULTS the artifact, where it previously asserted only
     * that an emitted stream's line-length distribution fell into the artifact's four width classes.
     * Those classes are a consequence of each band's content reaching a particular column, so a report
     * with the wrong labels, the wrong band order, the wrong descriptions or a missing page satisfies
     * every one of them -- which is to say the old form of this suite could not see systematic content
     * drift at all, and its name claimed otherwise. The width cases are kept, because they state a
     * property the byte comparison cannot: the comparison normalises the emitted record, so a generator
     * that stopped padding would still match. The two together are what the artifact is worth.</p>
     */
    @Nested
    @DisplayName("comparing an emitted stream against the shipped oracle")
    class GoldenCorroboration {

        /** The shipped baseline artifact, relative to the repository root. */
        private static final String ORACLE = "tests/golden/reporting/e2e_full_cycle_report.expected";

        /** How many lines the shipped artifact holds, measured on the file. */
        private static final int ORACLE_LINES = 857;

        /** How many of the artifact's 256 card groups it closes with a card-break band. */
        private static final int ORACLE_CLOSED_GROUPS = 255;

        /** Leading literal of the title band, {@code REPT-SHORT-NAME} at L5 and L6 of the copybook. */
        private static final String TITLE_LITERAL = "DALYREPT";

        /** Leading literal of the column-heading band, L34 and L35 of the copybook. */
        private static final String HEADINGS_LITERAL = "Transaction ID";

        /** Leading literal of the card-break total band, L57 and L58 of the copybook. */
        private static final String ACCOUNT_TOTAL_LITERAL = "Account Total";

        /** Leading literal of the page total band, L51 and L52 of the copybook. */
        private static final String PAGE_TOTAL_LITERAL = "Page Total";

        /** Leading literal of the grand total band, L63 and L64 of the copybook. */
        private static final String GRAND_TOTAL_LITERAL = "Grand Total";

        /** Zero-based start column of {@code TRAN-REPORT-TRANS-ID}, L16 of the copybook. */
        private static final int DETAIL_ID_OFFSET = 0;

        /** Declared width of {@code TRAN-REPORT-TRANS-ID}. */
        private static final int DETAIL_ID_WIDTH = 16;

        /** Zero-based start column of {@code TRAN-REPORT-ACCOUNT-ID}, L18 of the copybook. */
        private static final int DETAIL_ACCOUNT_OFFSET = 17;

        /** Declared width of {@code TRAN-REPORT-ACCOUNT-ID}. */
        private static final int DETAIL_ACCOUNT_WIDTH = 11;

        /** Zero-based start column of {@code TRAN-REPORT-TYPE-CD}, L20 of the copybook. */
        private static final int DETAIL_TYPE_OFFSET = 29;

        /** Declared width of {@code TRAN-REPORT-TYPE-CD}. */
        private static final int DETAIL_TYPE_WIDTH = 2;

        /** Zero-based start column of {@code TRAN-REPORT-TYPE-DESC}, L22 of the copybook. */
        private static final int DETAIL_TYPE_DESC_OFFSET = 32;

        /** Declared width of {@code TRAN-REPORT-TYPE-DESC}. */
        private static final int DETAIL_TYPE_DESC_WIDTH = 15;

        /** Zero-based start column of {@code TRAN-REPORT-CAT-CD}, L24 of the copybook. */
        private static final int DETAIL_CATEGORY_OFFSET = 48;

        /** Declared width of {@code TRAN-REPORT-CAT-CD}. */
        private static final int DETAIL_CATEGORY_WIDTH = 4;

        /** Zero-based start column of {@code TRAN-REPORT-CAT-DESC}, L26 of the copybook. */
        private static final int DETAIL_CATEGORY_DESC_OFFSET = 53;

        /** Declared width of {@code TRAN-REPORT-CAT-DESC}. */
        private static final int DETAIL_CATEGORY_DESC_WIDTH = 29;

        /** Zero-based start column of {@code TRAN-REPORT-SOURCE}, L28 of the copybook. */
        private static final int DETAIL_SOURCE_OFFSET = 83;

        /** Declared width of {@code TRAN-REPORT-SOURCE}. */
        private static final int DETAIL_SOURCE_WIDTH = 10;

        /**
         * Zero-based start column of the money item every money-bearing band carries.
         *
         * <p>⚠️ Trade-offs: transcribed here rather than read from {@link ReportBandLayouts}, which
         * publishes the same 97. Restating it is what keeps this suite an INDEPENDENT reader of the
         * shipped artifact: reading the artifact through the layout the subject writes through would let
         * this suite and the subject agree on a wrong column, and the whole purpose of comparing against
         * a file produced by another implementation is that the two do not share a definition. The cost
         * is that a legitimate change to the report's geometry has to be made in two places, and that is
         * the intended cost -- the second place is a shipped file this suite may not modify.</p>
         */
        private static final int AMOUNT_OFFSET = 97;

        /** Declared width of the {@code -ZZZ,ZZZ,ZZZ.ZZ} and {@code +ZZZ,ZZZ,ZZZ.ZZ} masks. */
        private static final int AMOUNT_WIDTH = 15;

        /** Zero-based start column of {@code REPT-START-DATE}, L11 of the copybook. */
        private static final int TITLE_START_DATE_OFFSET = 91;

        /** Zero-based start column of {@code REPT-END-DATE}, L13 of the copybook. */
        private static final int TITLE_END_DATE_OFFSET = 105;

        /** Declared width of each of the title band's two date items. */
        private static final int DATE_WIDTH = 10;

        /**
         * Asserts the right-trim normalisation reproduces the oracle's stored width classes.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the four widths are a consequence of each band's CONTENT reaching a different
         * column, not a second width contract. The uniform blank band collapses to nothing, the all-hyphen
         * separator survives whole because its last byte is a hyphen, the title band ends with the last
         * digit of the end date at column 115, and every money-bearing band ends with the last digit of its
         * amount at column 112. A detail line whose amount is suppressed to blanks trims shorter still,
         * which the case below states explicitly so that the distribution is not read as a specification
         * every band must satisfy.</p>
         */
        @Test
        @DisplayName("the right-trim normalisation reproduces the oracle's stored width classes")
        void theRightTrimReproducesTheOraclesWidthClasses() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            assertThat(rightTrimmed(
                    bandsOfKind(run, "SEPARATOR").get(0).getBytes(StandardCharsets.US_ASCII)))
                    .as("the all-hyphen rule survives the trim whole, the oracle's 133-byte class")
                    .hasSize(ReportBandLayouts.REPORT_RECORD_LENGTH);
            assertThat(rightTrimmed(
                    bandsOfKind(run, "BLANK").get(0).getBytes(StandardCharsets.US_ASCII)))
                    .as("the uniform blank band collapses to nothing, the oracle's 0-byte class")
                    .isEmpty();
            assertThat(rightTrimmed(
                    bandsOfKind(run, "NAME_HEADER").get(0).getBytes(StandardCharsets.US_ASCII)))
                    .as("the title band ends at the last digit of the end date, the oracle's 115-byte"
                            + " class")
                    .hasSize(115);
            assertThat(rightTrimmed(
                    bandsOfKind(run, "GRAND_TOTAL").get(0).getBytes(StandardCharsets.US_ASCII)))
                    .as("a money-bearing band ends at the last digit of its amount, the oracle's"
                            + " 112-byte class")
                    .hasSize(ReportBandLayouts.AMOUNT_COLUMN_OFFSET
                            + CobolEditMask.REPORT_AMOUNT_WIDTH);
        }

        /**
         * Asserts a suppressed amount trims shorter, so the oracle's classes are content and not contract.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * <p>Assumptions: the fixture carries transaction {@code 0000000000000024} at exactly zero, whose
         * amount item is blanked entirely by the {@code Z} suppression of L30 of
         * {@code app/cpy/CVTRA07Y.cpy}. Its right-trimmed form therefore ends at the source column and is
         * shorter than 112, while its EMITTED form is still the full declared width. Stating this is what
         * keeps a maintainer from turning the measured distribution into an assertion every band must
         * satisfy, which would fail on this row.</p>
         */
        @Test
        @DisplayName("a suppressed amount trims shorter while the emitted record stays full width")
        void aSuppressedAmountTrimsShorterThanTheOraclesCommonClass() {
            EmissionRun run = emit(fixtureLines(CANONICAL_START, CANONICAL_END));

            byte[] zeroRow = null;
            for (byte[] record : run.sink().records) {
                String rendered = new String(record, StandardCharsets.US_ASCII);
                if (bandKindOf(record).equals("DETAIL") && rendered.startsWith("0000000000000024")) {
                    zeroRow = record;
                }
            }

            assertThat(zeroRow).as("the fixture's zero-amount row was emitted").isNotNull();
            assertThat(zeroRow.length)
                    .as("the emitted record is the full declared width regardless of suppression")
                    .isEqualTo(ReportBandLayouts.REPORT_RECORD_LENGTH);
            assertThat(rightTrimmed(zeroRow).length())
                    .as("but its trimmed form ends before the amount column, so the oracle's width"
                            + " classes describe content rather than a contract")
                    .isLessThan(ReportBandLayouts.AMOUNT_COLUMN_OFFSET);
        }

        // WHY : Assumptions: the WHOLE stream is compared and only three lines are exempted, each by an
        //       arithmetic identity asserted separately below. The two cases above assert width CLASSES,
        //       which a report with the wrong labels, the wrong band order or a missing page would
        //       satisfy exactly -- so on their own they cannot see systematic content drift. This case
        //       is what makes every one of the oracle's 857 lines an assertion.
        /**
         * Asserts the whole emitted stream is the shipped oracle, line for line, bar the two divergences.
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the whole emitted stream is the shipped oracle, line for line")
        void theWholeEmittedStreamIsTheShippedOracle() {
            List<String> oracle = oracleLines();
            EmissionRun run = emit(inputDerivedFrom(oracle));

            assertThat(emittedStream(run)).isEqualTo(expectedStream(oracle));
        }

        // WHY : ⚠️ Trade-offs: the two divergent figures are pinned by an ARITHMETIC identity against the
        //       oracle's own bytes rather than by a widened comparison. A comparison loosened to admit
        //       them -- ignoring the amount column on total bands, say, or comparing only line counts --
        //       would simultaneously stop asserting the 255 account totals, the 13 correct page totals
        //       and the 262 detail amounts that the same mask renders and that the oracle pins exactly.
        //       A registered divergence has to cost only itself.
        /**
         * Asserts each divergent total differs from the oracle's by exactly the last transaction's amount.
         *
         * <p>Assumptions: the identity is register entry {@code D-REPORT-GRAND-TOTAL} stated as
         * arithmetic. The reference's end-of-file branch at L200 and L201 of
         * {@code app/cbl/CBTRN03C.cbl} adds the last record area's amount into the page and grand
         * accumulators a second time, after L287 and L288 had already added it while writing the detail
         * line, so the oracle's final page figure and its grand figure each carry that amount twice. The
         * emitted figures carry it once, so each differs by exactly that amount and by nothing else --
         * which this case asserts on values parsed out of the emitted bytes by this class's own reader,
         * not by re-rendering through the mask under test.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("each divergent total differs from the oracle's by exactly the last amount, twice"
                + " counted")
        void theDivergentTotalsDifferByExactlyTheLastAmount() {
            List<String> oracle = oracleLines();
            EmissionRun run = emit(inputDerivedFrom(oracle));
            List<String> emitted = trimmedRecordsOf(run);

            Money lastDetail = amountOf(lastOfKind(oracle, "DETAIL"));
            assertThat(lastDetail)
                    .as("the oracle's final detail row carries the amount the reference counts twice")
                    .isEqualTo(Money.of("603.22"));

            assertThat(amountOf(lastOfKind(emitted, "PAGE_TOTAL")))
                    .as("the emitted closing page figure counts the last amount once")
                    .isEqualTo(amountOf(lastOfKind(oracle, "PAGE_TOTAL")).minus(lastDetail));
            assertThat(amountOf(lastOfKind(emitted, "GRAND_TOTAL")))
                    .as("and so does the emitted grand figure")
                    .isEqualTo(amountOf(lastOfKind(oracle, "GRAND_TOTAL")).minus(lastDetail));

            // WHY : Assumptions: the emitted grand figure is additionally held to the SUM of the emitted
            //       page figures, which is the property the divergence exists to restore and which the
            //       oracle itself fails. Asserting only the difference from the oracle would be satisfied
            //       by a stream that had moved the same error somewhere else.
            Money pageFigures = Money.ZERO;
            for (String line : emitted) {
                if (kindOf(line).equals("PAGE_TOTAL")) {
                    pageFigures = pageFigures.plus(amountOf(line));
                }
            }
            assertThat(amountOf(lastOfKind(emitted, "GRAND_TOTAL")))
                    .as("the emitted grand figure is exactly the sum of the emitted page figures")
                    .isEqualTo(pageFigures);
        }

        /**
         * Asserts the closing card-break band is the only band the oracle does not carry.
         *
         * <p>Assumptions: this is register entry {@code D-REPORT-CLOSING-TOTAL} stated as a count. The
         * reference writes the card-break band only from the break at L181 of
         * {@code app/cbl/CBTRN03C.cbl}, which fires when the NEXT card arrives and so cannot fire for
         * the last group, and its end-of-file branch at L198 to L203 performs the page and grand
         * paragraphs alone. The emitted stream closes the last group as it closes every other, so it
         * carries one more card-break band -- and, because {@code writeAccountTotals} emits the declared
         * rule after every card-break band without exception, the rule that goes with it. Two lines, both
         * at the end, and the case asserts the count exactly rather than asserting the stream is
         * longer.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the closing card-break band and its rule are the only lines the oracle lacks")
        void theClosingCardBreakBandIsTheOnlyAddition() {
            List<String> oracle = oracleLines();
            EmissionRun run = emit(inputDerivedFrom(oracle));
            List<String> emitted = trimmedRecordsOf(run);

            assertThat(emitted)
                    .as("two lines more than the oracle: the closing card-break band and its rule")
                    .hasSize(oracle.size() + 2);
            assertThat(countOfKind(emitted, "ACCOUNT_TOTAL"))
                    .as("one card-break band more than the oracle's 255, for the 256th group")
                    .isEqualTo(countOfKind(oracle, "ACCOUNT_TOTAL") + 1);
            assertThat(countOfKind(emitted, "SEPARATOR"))
                    .isEqualTo(countOfKind(oracle, "SEPARATOR") + 1);
            for (String kind : List.of("DETAIL", "TITLE", "BLANK", "HEADINGS", "PAGE_TOTAL",
                    "GRAND_TOTAL")) {
                assertThat(countOfKind(emitted, kind))
                        .as("the %s band count is untouched by the divergence", kind)
                        .isEqualTo(countOfKind(oracle, kind));
            }

            // WHY : Assumptions: the added band's own figure is asserted to be the final group's sum,
            //       because a band added at the right position carrying the wrong figure would satisfy
            //       every count above.
            assertThat(amountOf(lastOfKind(emitted, "ACCOUNT_TOTAL")))
                    .as("the added band closes the final group with that group's own sum")
                    .isEqualTo(amountOf(lastOfKind(oracle, "DETAIL")));
        }

        /**
         * Reads the shipped baseline artifact as its stored lines.
         *
         * <p>Assumptions: the artifact is REFERENCE. It is read and never written, and nothing here
         * regenerates it -- an oracle a test may rewrite asserts nothing at all.</p>
         *
         * @return the stored lines, in file order, with the file's trailing newline dropped; never
         *     {@code null}
         * @throws UncheckedIOException if the artifact cannot be read, which is a broken checkout rather
         *     than a failure of the subject
         */
        private List<String> oracleLines() {
            Path file = repositoryRoot().resolve(ORACLE);
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
                assertThat(lines)
                        .as("the shipped artifact holds the 857 lines every figure here is measured on")
                        .hasSize(ORACLE_LINES);
                return lines;
            } catch (IOException unreadable) {
                throw new UncheckedIOException(
                        "the shipped baseline artifact could not be read", unreadable);
            }
        }

        /**
         * Rebuilds the report's input from the oracle's own detail and card-break bands.
         *
         * <p>⚠️ Alternatives Considered: driving this from the module's own fixtures. Rejected: the
         * shipped artifact was produced by a full batch cycle over the repository's end-to-end inputs --
         * posting and interest accrual first -- so no fixture in this module holds the 262 rows it
         * reports, and a comparison between two reports over different inputs could only assert whatever
         * the two had in common. Reconstructing the input from the artifact's own printed fields makes
         * every one of its lines comparable.</p>
         *
         * <p>Assumptions: the GROUPING is read off the artifact's card-break bands rather than guessed. A
         * card-break band closes a group, so the detail rows between two of them are one card's rows, and
         * that partition is asserted below against each band's own figure -- 255 closed groups, every one
         * of whose detail rows sum to the figure the artifact stores for it. The card number itself is
         * not printed anywhere in the report and cannot be recovered, so a synthetic one is derived per
         * group; only its ORDER matters, because the emitter breaks on a change of grouping key and never
         * on the key's content.</p>
         *
         * <p>Assumptions: the printed fields are read at column positions transcribed here from
         * {@code app/cpy/CVTRA07Y.cpy} L16 to L31, and NOT through {@link ReportBandLayouts}. That is
         * deliberate and is the one place in this file where an offset is restated: an oracle read
         * through the layout under test would let a uniform column move round-trip invisibly, because the
         * same wrong offset would be used to read the artifact and to write the comparison.</p>
         *
         * @param oracle the artifact's stored lines; must not be {@code null}
         * @return the resolved lines the emitter must be driven with, in artifact order; never
         *     {@code null}
         */
        private List<TransactionReportRepository.ReportLine> inputDerivedFrom(List<String> oracle) {
            List<TransactionReportRepository.ReportLine> lines = new ArrayList<>();
            List<Money> groupRows = new ArrayList<>();
            int group = 0;
            int closedGroups = 0;

            for (String line : oracle) {
                String kind = kindOf(line);
                if (kind.equals("DETAIL")) {
                    Money amount = amountOf(line);
                    groupRows.add(amount);
                    lines.add(resolvedLine(
                            field(line, DETAIL_ID_OFFSET, DETAIL_ID_WIDTH),
                            syntheticCardNumber(group),
                            Long.parseLong(field(line, DETAIL_ACCOUNT_OFFSET, DETAIL_ACCOUNT_WIDTH)),
                            field(line, DETAIL_TYPE_OFFSET, DETAIL_TYPE_WIDTH),
                            field(line, DETAIL_TYPE_DESC_OFFSET, DETAIL_TYPE_DESC_WIDTH),
                            field(line, DETAIL_CATEGORY_OFFSET, DETAIL_CATEGORY_WIDTH),
                            field(line, DETAIL_CATEGORY_DESC_OFFSET, DETAIL_CATEGORY_DESC_WIDTH),
                            field(line, DETAIL_SOURCE_OFFSET, DETAIL_SOURCE_WIDTH),
                            amount));
                } else if (kind.equals("ACCOUNT_TOTAL")) {
                    Money summed = Money.ZERO;
                    for (Money row : groupRows) {
                        summed = summed.plus(row);
                    }
                    assertThat(summed)
                            .as("the artifact's card-break band %d agrees with the rows it closes, so "
                                    + "the grouping read off it is the grouping the run had", group)
                            .isEqualTo(amountOf(line));
                    groupRows.clear();
                    closedGroups++;
                    group++;
                }
            }

            assertThat(closedGroups)
                    .as("the artifact closes 255 of its 256 groups, the last being the one the "
                            + "reference never closes")
                    .isEqualTo(ORACLE_CLOSED_GROUPS);
            assertThat(groupRows)
                    .as("and the group it leaves open is the final one, which holds rows")
                    .isNotEmpty();
            return lines;
        }

        /**
         * Derives the synthetic card number standing for one group of the artifact.
         *
         * <p>Assumptions: zero-padded to sixteen digits so the grouping keys ascend in artifact order,
         * which is the order the reporting query declares and therefore the order the emitter is entitled
         * to assume. A counter rendered without padding would order group 10 before group 2.</p>
         *
         * @param group the group's zero-based position in the artifact
         * @return the synthetic card number; never {@code null}
         */
        private String syntheticCardNumber(int group) {
            return String.format("%016d", group);
        }

        /**
         * Reads one printed field of a band, with its declared blank padding removed.
         *
         * @param line the stored or emitted line to read from
         * @param offset the field's zero-based start column
         * @param width the field's declared width
         * @return the field with trailing blanks removed; never {@code null}
         */
        private String field(String line, int offset, int width) {
            return line.substring(offset, offset + width).stripTrailing();
        }

        /**
         * Classifies one line of the report by the band that produced it.
         *
         * <p>Assumptions: classified by its leading literal rather than by its length, because four of
         * the seven bands share the 112-character class and two share the 133-character one. The detail
         * band is the one with no leading literal and is recognised by its sixteen leading digits, which
         * is what {@code TRAN-REPORT-TRANS-ID} carries and what no other band can carry.</p>
         *
         * @param line the line to classify
         * @return the band name; never {@code null}
         * @throws IllegalStateException if the line matches no band, which means the artifact or the
         *     emitted stream holds something this model does not describe
         */
        private String kindOf(String line) {
            if (line.isEmpty()) {
                return "BLANK";
            }
            if (line.startsWith(TITLE_LITERAL)) {
                return "TITLE";
            }
            if (line.startsWith(HEADINGS_LITERAL)) {
                return "HEADINGS";
            }
            if (line.chars().allMatch(character -> character == '-')) {
                return "SEPARATOR";
            }
            if (line.startsWith(ACCOUNT_TOTAL_LITERAL)) {
                return "ACCOUNT_TOTAL";
            }
            if (line.startsWith(PAGE_TOTAL_LITERAL)) {
                return "PAGE_TOTAL";
            }
            if (line.startsWith(GRAND_TOTAL_LITERAL)) {
                return "GRAND_TOTAL";
            }
            if (line.length() >= DETAIL_ID_WIDTH
                    && line.substring(0, DETAIL_ID_WIDTH).chars().allMatch(Character::isDigit)) {
                return "DETAIL";
            }
            throw new IllegalStateException("the stream holds a line this model does not describe");
        }

        /**
         * Reads the money field of one band, as a value rather than as rendered characters.
         *
         * <p>Assumptions: parsed by this class rather than re-rendered through
         * {@link CobolEditMask}, so the two bounded identities are asserted on values the subject
         * produced and not on a second application of the mask under test. Both report masks put a fixed
         * sign position first -- {@code -ZZZ,ZZZ,ZZZ.ZZ} at L30 of {@code app/cpy/CVTRA07Y.cpy} and
         * {@code +ZZZ,ZZZ,ZZZ.ZZ} at its L54, L60 and L66 -- so the sign is the field's first character
         * and the magnitude is the rest with its grouping commas removed.</p>
         *
         * @param line the band to read
         * @return the figure the band carries; never {@code null}
         */
        private Money amountOf(String line) {
            String rendered = line.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH);
            String magnitude = rendered.substring(1).replace(",", "").strip();

            // WHY : Assumptions: the two shapes handled here are the Z-suppression regime's own, not
            //       leniency. Every digit position of both masks is a suppression position, so a
            //       magnitude below one prints with NO integer digit at all -- the artifact's line 387
            //       carries a ninety-nine-cent transaction as the eleven blanks and '.99' this reader
            //       has to restore a units zero to -- and an exactly zero value blanks the whole item,
            //       which is what CobolEditMask states at L339 and L411 and what the zero-amount case
            //       above observes. Reading the field with a parser that demanded a leading digit would
            //       fail on the first of those and reading it without the empty case would make this a
            //       partial function of the mask's own output.
            if (magnitude.isEmpty()) {
                return Money.ZERO;
            }
            Money value = Money.of(magnitude.startsWith(".") ? "0" + magnitude : magnitude);
            return rendered.charAt(0) == '-' ? value.negated() : value;
        }

        /**
         * Returns the last line of one band kind in a stream.
         *
         * @param stream the stored or emitted lines
         * @param kind the band name to look for
         * @return that band's last line; never {@code null}
         * @throws IllegalStateException if the stream holds no such band
         */
        private String lastOfKind(List<String> stream, String kind) {
            for (int position = stream.size() - 1; position >= 0; position--) {
                if (kindOf(stream.get(position)).equals(kind)) {
                    return stream.get(position);
                }
            }
            throw new IllegalStateException("the stream holds no " + kind + " band");
        }

        /**
         * Counts the lines of one band kind in a stream.
         *
         * @param stream the stored or emitted lines
         * @param kind the band name to count
         * @return how many lines that band produced
         */
        private long countOfKind(List<String> stream, String kind) {
            return stream.stream().filter(line -> kindOf(line).equals(kind)).count();
        }

        /**
         * Frames a run's emitted records the way the harness frames them before storing an artifact.
         *
         * @param run the completed emission run
         * @return one right-trimmed line per emitted record; never {@code null}
         */
        private List<String> trimmedRecordsOf(EmissionRun run) {
            List<String> framed = new ArrayList<>();
            for (byte[] record : run.sink().records) {
                framed.add(rightTrimmed(record));
            }
            return framed;
        }

        /**
         * Renders a run's emitted records as the single stream a stored artifact holds.
         *
         * @param run the completed emission run
         * @return the framed stream, each line newline-terminated; never {@code null}
         */
        private String emittedStream(EmissionRun run) {
            StringBuilder stream = new StringBuilder();
            for (String line : trimmedRecordsOf(run)) {
                stream.append(line).append('\n');
            }
            return stream.toString();
        }

        /**
         * Builds the stream the emitter must produce: the oracle with exactly the registered divergences.
         *
         * <p>Assumptions: the expected stream is the ORACLE'S OWN BYTES everywhere except at the three
         * places a divergence is registered, so a drift anywhere else has nothing to hide behind. The
         * three places are the two date items of every title band, which carry a range
         * {@link java.time.LocalDate} cannot represent; the two-line closing card-break sequence, whose
         * label and rule come from the artifact's own last such sequence; and the money field of the
         * closing page band and of the grand band.</p>
         *
         * @param oracle the artifact's stored lines; must not be {@code null}
         * @return the stream the emitter must produce, each line newline-terminated; never {@code null}
         */
        private String expectedStream(List<String> oracle) {
            Money lastDetail = amountOf(lastOfKind(oracle, "DETAIL"));
            String closingBand = withAmount(lastOfKind(oracle, "ACCOUNT_TOTAL"), lastDetail);
            String closingRule = lastOfKind(oracle, "SEPARATOR");
            String lastDetailLine = lastOfKind(oracle, "DETAIL");

            StringBuilder stream = new StringBuilder();
            for (String line : oracle) {
                String kind = kindOf(line);
                String amended = switch (kind) {
                    case "TITLE" -> withCanonicalRange(line);
                    case "PAGE_TOTAL" -> line.equals(lastOfKind(oracle, "PAGE_TOTAL"))
                            ? withAmount(line, amountOf(line).minus(lastDetail)) : line;
                    case "GRAND_TOTAL" -> withAmount(line, amountOf(line).minus(lastDetail));
                    default -> line;
                };
                stream.append(amended).append('\n');
                if (line.equals(lastDetailLine)) {
                    stream.append(closingBand).append('\n').append(closingRule).append('\n');
                }
            }
            return stream.toString();
        }

        /**
         * Replaces a title band's two date items with the range this class drives every run over.
         *
         * <p>Assumptions: this is the one divergence in the comparison that is not a behavioural one. The
         * artifact was produced over the end-to-end harness's deliberately wide sentinel range,
         * {@code 0000-00-00} to {@code 9999-99-99}, and neither bound is a date: month and day zero do
         * not exist and nor does month 99, so {@link java.time.LocalDate} cannot carry either and no
         * argument to the emitter could reproduce those ten characters. The substitution is bounded to
         * the two ten-character items at L11 and L13 of {@code app/cpy/CVTRA07Y.cpy}; the rest of the
         * title band, including its two literals and the {@code ' to '} between the dates, is compared
         * as the artifact stores it.</p>
         *
         * @param title one title band from the artifact
         * @return the band with the canonical range substituted; never {@code null}
         */
        private String withCanonicalRange(String title) {
            StringBuilder amended = new StringBuilder(title);
            amended.replace(TITLE_START_DATE_OFFSET, TITLE_START_DATE_OFFSET + DATE_WIDTH,
                    CANONICAL_START.toString());
            amended.replace(TITLE_END_DATE_OFFSET, TITLE_END_DATE_OFFSET + DATE_WIDTH,
                    CANONICAL_END.toString());
            return amended.toString();
        }

        /**
         * Replaces the money field of one total band, leaving its label and leader as the artifact has
         * them.
         *
         * <p>Assumptions: the field is re-rendered through {@link CobolEditMask} rather than assembled
         * here, and the tautology that introduces is contained: it reaches exactly three of the artifact's
         * 857 lines, while the same mask renders the 255 card-break bands, the 13 undisturbed page bands
         * and the 262 detail amounts that the byte comparison pins against the artifact exactly. The
         * VALUES of the three exempted fields are additionally asserted by the two divergence cases
         * above, against figures parsed out of the emitted bytes by this class's own reader.</p>
         *
         * @param band one total band from the artifact
         * @param value the figure the emitter must carry there
         * @return the band with its money field replaced; never {@code null}
         */
        private String withAmount(String band, Money value) {
            return band.substring(0, AMOUNT_OFFSET) + CobolEditMask.formatReportTotalAmount(value);
        }
    }
}
