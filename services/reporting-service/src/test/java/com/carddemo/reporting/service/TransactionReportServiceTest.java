package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportTotalsResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.repository.TransactionReportRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    /**
     * Asserts that a range wider than the composable maximum is refused, naming the bound it exceeded.
     */
    @Test
    @DisplayName("a range above the composable maximum is refused, naming the end bound")
    void anOversizedRangeIsRefused() {
        doReturn(List.of()).when(reports).findTransactionsWithUnresolvedDimensions(any(), any(), any(Limit.class));
        doReturn((long) TransactionReportService.MAX_REPORT_LINES + 1).when(reports).countDrivingRows(any(), any());

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.readDetailLinePage(
                        RANGE_START, RANGE_END, null, false, sealer))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("endDate"));
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
        assertThat(page.hasPrevious())
                .as("the opening page advertises no previous page")
                .isFalse();
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
}
