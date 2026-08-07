package com.carddemo.reporting.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.reporting.dto.ReportTotalsResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.repository.TransactionReportRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces the transaction detail report of {@code app/cbl/CBTRN03C.cbl} as response values.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBTRN03C.cbl} is a 649-line batch program laid out by
 * {@code app/cpy/CVTRA07Y.cpy} and driven by {@code app/jcl/TRANREPT.jcl}. This service composes the
 * report's content -- the detail lines and the three subtotal bands -- out of what the repository
 * returns. It composes values and never bytes: the 133-column fixed-width assembly, the two edit
 * masks and every declared width belong to {@code com.carddemo.reporting.mapper}, and the package
 * charter one level up records why that boundary is not crossed.
 *
 * <h2>Assumptions: the date range is inclusive on both bounds and is converted once, here</h2>
 *
 * <p>{@code app/jcl/TRANREPT.jcl} L47-L48 selects records with both bounds inclusive and on the date
 * part alone, corroborated by {@code app/cbl/CBTRN03C.cbl} L173-L174. The repository takes a
 * half-open instant range instead, for the reasons its own charter gives, and this service performs
 * the conversion: the start becomes the first instant of the first day and the end becomes the first
 * instant of the day <b>after</b> the last. The conversion lives here because this is where the two
 * business dates arrive, and it lives in exactly one place so the two cannot disagree.
 *
 * <h2>Assumptions: the integrity reconciliation is performed and a shortfall is fatal</h2>
 *
 * <p>Register entry <b>R10</b> of the repository charter prescribes the mechanism and the evidence:
 * the baseline treats an unresolvable dimension as fatal on all three of its report lookups --
 * {@code 1500-A-LOOKUP-XREF} at L484-L492, {@code 1500-B-LOOKUP-TRANTYPE} at L494-L502 and
 * {@code 1500-C-LOOKUP-TRANCATG} at L504-L512 each display the offending key and then abend -- so an
 * inner join that silently discarded such a row would produce different bytes, different account and
 * grand totals and different page boundaries. This service compares the driving count against the
 * joined count before it returns anything, and raises naming the first unresolved transaction when
 * they disagree.
 *
 * <h2>Assumptions: the two descriptions are narrowed here and the narrowing is not a truncation
 * defect</h2>
 *
 * <p>The stored type description is fifty characters at {@code app/cpy/CVTRA03Y.cpy} L6 and the
 * stored category description is fifty at {@code app/cpy/CVTRA04Y.cpy} L8, while the report band
 * re-declares them at fifteen and twenty-nine characters at {@code app/cpy/CVTRA07Y.cpy} L22 and
 * L26. The narrowing is therefore the reference's own: a move into a shorter item truncates on the
 * right, which is what the report prints. Narrowing in this service rather than in the response type
 * keeps the response type a carrier of already-narrowed values, which is what its own charter states.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility.
 * The four rationale labels are written in the plural, unparenthesised forms that rule gives.
 */
@Service
public class TransactionReportService {

    /**
     * Declared width of the type description on the report band, in characters.
     *
     * <p>Assumptions: 15 is the width {@code TRAN-REPORT-TYPE-DESC PIC X(15)} declares at
     * {@code app/cpy/CVTRA07Y.cpy} L22, and it is deliberately narrower than the fifty characters the
     * stored field declares. That the two differ is what proves the narrowing is content rather than
     * padding: a fixture whose description is shorter than fifteen characters cannot show that a
     * narrowing happened at all.</p>
     */
    public static final int TYPE_DESCRIPTION_WIDTH = 15;

    /**
     * Declared width of the category description on the report band, in characters.
     *
     * <p>Assumptions: 29 is the width {@code TRAN-REPORT-CAT-DESC PIC X(29)} declares at
     * {@code app/cpy/CVTRA07Y.cpy} L26.</p>
     */
    public static final int CATEGORY_DESCRIPTION_WIDTH = 29;

    /**
     * Declared width of the account identifier on the report band, in characters.
     *
     * <p>Assumptions: 11 is the width {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} declares at
     * {@code app/cpy/CVTRA07Y.cpy} L18, and the identifier is rendered with its leading zeros intact
     * because the band is a character item rather than an edited numeric one.</p>
     */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Number of detail lines the reference prints between page breaks.
     *
     * <p>Assumptions: 20 is declared as {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at
     * {@code app/cbl/CBTRN03C.cbl} L131-L132 and is tested at L282 by
     * {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}. This is print pagination inside a
     * rendered document and is a different concept from the keyset paging of a list response; the
     * api package charter records that the two must never be conflated.</p>
     */
    public static final int PAGE_SIZE = 20;

    /**
     * Maximum number of report lines one request will compose.
     *
     * <p>Trade-offs: the report is bounded rather than unbounded, and the bound is a decision with a
     * cost. An unbounded compose would hold every line of an arbitrary date range in memory at once,
     * which is the failure mode the baseline avoids for free by writing each line to a data set as it
     * goes; a bound means a range wider than this many transactions is refused rather than truncated,
     * because a truncated report whose grand total covered only part of its range would reconcile
     * against nothing. A caller wanting a wider range submits the on-demand execution instead, which
     * writes to an object store exactly as the baseline writes to a data set.</p>
     */
    public static final int MAX_REPORT_LINES = 10_000;

    /**
     * Maximum number of driving rows a reconciliation diagnostic will read.
     *
     * <p>Assumptions: the diagnostic read is bounded independently of the report, because it runs only
     * when the two counts disagree and its whole purpose is to name one offending key. Reading the
     * whole range to name the first row of it would turn a diagnostic into a second full scan.</p>
     */
    public static final int MAX_DIAGNOSTIC_ROWS = 1;

    /**
     * The report-side query surface this service composes from.
     */
    private final TransactionReportRepository reports;

    /**
     * Records the repository this service composes from.
     *
     * <p>Assumptions: the collaborator arrives by constructor injection and is held final, so this
     * service has no settable state and one instance serves many concurrent callers safely. A COBOL
     * program's working storage is process-wide and single-threaded, so a field behaving like one
     * would be shared across unrelated reports.</p>
     *
     * @param reports the report-side query surface; must not be {@code null}
     */
    public TransactionReportService(TransactionReportRepository reports) {
        this.reports = reports;
    }

    /**
     * Composes the detail lines of the report for one inclusive business-date range.
     *
     * <p>Assumptions: the read runs in a read-only transaction so that the two counts and the page
     * read see one consistent snapshot. Without it the reconciliation could compare a driving count
     * taken before a concurrent insert against a joined count taken after it, and report a shortfall
     * that never existed.</p>
     *
     * @param rangeStart the first business date of the range, inclusive; must not be {@code null}
     * @param rangeEnd the last business date of the range, inclusive; must not be {@code null}
     * @return the report's detail lines in card-first order with the transaction identifier behind it,
     *     possibly empty; never {@code null}
     * @throws ClientInputException if the range is inverted, because a range whose end precedes its
     *     start selects nothing and the reference's own inclusive comparison would produce an empty
     *     report a caller would read as an absence of activity
     * @throws IllegalStateException if the integrity reconciliation finds a shortfall, naming the
     *     first unresolved transaction, which is the target's equivalent of the three baseline
     *     lookups displaying the offending key and abending
     */
    @Transactional(readOnly = true)
    public List<TransactionReportLineResponse> composeDetailLines(
            LocalDate rangeStart, LocalDate rangeEnd) {
        requireOrderedRange(rangeStart, rangeEnd);
        LocalDateTime from = rangeStart.atStartOfDay();
        LocalDateTime until = rangeEnd.plusDays(1).atStartOfDay();

        long driving = reports.countDrivingRows(from, until);
        long joined = reports.countJoinedRows(from, until);
        if (driving != joined) {
            reportUnresolvedDimension(from, until, driving, joined);
        }
        if (driving > MAX_REPORT_LINES) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "endDate",
                    "the requested range holds " + driving
                            + " transactions, above the composable maximum of " + MAX_REPORT_LINES);
        }

        List<TransactionReportRepository.ReportLine> lines =
                reports.findReportLines(from, until, Limit.of(MAX_REPORT_LINES));
        List<TransactionReportLineResponse> composed = new ArrayList<>(lines.size());
        for (TransactionReportRepository.ReportLine line : lines) {
            composed.add(new TransactionReportLineResponse(
                    line.getTransactionId(),
                    renderAccountId(line.getAccountId()),
                    line.getTypeCd(),
                    narrow(line.getTypeDescription(), TYPE_DESCRIPTION_WIDTH),
                    line.getCategoryCd(),
                    narrow(line.getCategoryDescription(), CATEGORY_DESCRIPTION_WIDTH),
                    line.getSource(),
                    line.getAmount()));
        }
        return composed;
    }

    /**
     * Composes the three subtotal bands the report closes with.
     *
     * <p>Assumptions: the page total resets every {@value #PAGE_SIZE} detail lines, which is the depth
     * test at {@code app/cbl/CBTRN03C.cbl} L282, so the page total returned here is the total of the
     * <b>last</b> page rather than of the whole report. Returning the whole-report figure under a page
     * label would produce three bands that all carried the same number and a reader who reconciled
     * them would find the report internally consistent and wrong.</p>
     *
     * <p>Assumptions: the account total is the total of the last account group and the grand total is
     * the total of every line, and the last group is closed by an account-total band exactly as every
     * other group is. The baseline does not close its last group -- the break at
     * {@code app/cbl/CBTRN03C.cbl} L181 fires when the next card arrives, so the end-of-file branch at
     * L198-L203 writes the page and grand totals and never the account total -- and that divergence is
     * registered as {@code D-REPORT-CLOSING-TOTAL} in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed silently.</p>
     *
     * @param lines the composed detail lines, in emission order; must not be {@code null}
     * @return the three subtotal bands in the reference's emission order -- page, account, grand --
     *     each carrying the verbatim label its declaration group states; never {@code null}
     */
    public List<ReportTotalsResponse> composeTotals(List<TransactionReportLineResponse> lines) {
        Money page = Money.ZERO;
        Money account = Money.ZERO;
        Money grand = Money.ZERO;
        String currentAccount = null;
        int lineCount = 0;

        for (TransactionReportLineResponse line : lines) {
            // WHY : Assumptions: the account accumulator resets on a CHANGE of account identifier
            //       rather than being summed per identifier into a map. The ordering guarantees every
            //       line of one account is contiguous, which is what makes a control break equivalent
            //       to a grouping and what the reference relies on at its own break; a map would give
            //       the same total for the last group and a different one for a report whose ordering
            //       had been broken, so the break is also a check on the ordering.
            if (!line.accountId().equals(currentAccount)) {
                currentAccount = line.accountId();
                account = Money.ZERO;
            }
            account = account.plus(line.amount());
            grand = grand.plus(line.amount());

            // WHY : Assumptions: the page accumulator resets AFTER the line that fills a page rather
            //       than before the line that opens the next one, because the reference's depth test
            //       at line 282 runs on the line counter after the line has been written. Resetting
            //       first would put the twenty-first line's amount into the page it closes.
            page = page.plus(line.amount());
            lineCount++;
            if (lineCount % PAGE_SIZE == 0) {
                page = Money.ZERO;
            }
        }

        // WHY : Assumptions: the page figure returned is the accumulator as it stands, which is the
        //       partial last page when the line count is not a multiple of the page size and zero when
        //       it is exactly a multiple. Zero is the correct value in that case rather than a missing
        //       one: the reference has already written that page's total and started a new page which
        //       holds nothing.
        return List.of(
                new ReportTotalsResponse(ReportTotalsResponse.Band.PAGE,
                        ReportTotalsResponse.Band.PAGE.reportLabel(), page),
                new ReportTotalsResponse(ReportTotalsResponse.Band.ACCOUNT,
                        ReportTotalsResponse.Band.ACCOUNT.reportLabel(), account),
                new ReportTotalsResponse(ReportTotalsResponse.Band.GRAND,
                        ReportTotalsResponse.Band.GRAND.reportLabel(), grand));
    }

    /**
     * Raises naming the first transaction whose dimensions the join could not resolve.
     *
     * <p>Assumptions: the refusal names the transaction identifier and the two counts, and never the
     * card number. The identifier locates the row exactly and carries no cardholder information of its
     * own; the card number is a primary account number and a diagnostic is the wrong place for one,
     * which is the same division the sibling projection types apply to their own renderings.</p>
     *
     * @param from the first instant of the range
     * @param until the first instant of the day after the range
     * @param driving the number of transactions the date predicate admitted
     * @param joined the number of report lines the three joins yielded
     * @throws IllegalStateException always, which is the point of the method
     */
    private void reportUnresolvedDimension(
            LocalDateTime from, LocalDateTime until, long driving, long joined) {
        List<com.carddemo.reporting.domain.ReportTransactionView> sample =
                reports.findDrivingRows(from, until, Limit.of(MAX_DIAGNOSTIC_ROWS));
        String firstIdentifier = sample.isEmpty() ? "none" : sample.get(0).transactionId();
        throw new IllegalStateException("the report range admits " + driving
                + " transactions but only " + joined
                + " resolve a type, a category and a cross-reference; the first transaction in the"
                + " range is " + firstIdentifier);
    }

    /**
     * Refuses a range whose end precedes its start.
     *
     * @param rangeStart the first business date of the range
     * @param rangeEnd the last business date of the range
     * @throws ClientInputException if the end precedes the start
     */
    private static void requireOrderedRange(LocalDate rangeStart, LocalDate rangeEnd) {
        if (rangeEnd.isBefore(rangeStart)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "endDate",
                    "endDate must not precede startDate");
        }
    }

    /**
     * Renders the account identifier at the declared width of its report band.
     *
     * <p>Assumptions: the identifier is rendered with leading zeros rather than as a bare number,
     * because {@code app/cpy/CVTRA07Y.cpy} L18 declares the band a character item of eleven positions
     * and a numeric rendering would left-align the digits and shift the whole line.</p>
     *
     * @param accountId the account identifier the cross-reference supplied
     * @return the identifier as exactly {@value #ACCOUNT_ID_WIDTH} digits, leading zeros included
     */
    private static String renderAccountId(Long accountId) {
        return String.format("%0" + ACCOUNT_ID_WIDTH + "d", accountId);
    }

    /**
     * Narrows a stored description to the declared width of its report band.
     *
     * <p>Assumptions: the narrowing truncates on the right and pads nothing, which is what a move into
     * a shorter item does in the reference. Padding here would be wrong on both counts: the response
     * type carries values rather than a line image, and the mapper that assembles the line owns every
     * declared width.</p>
     *
     * @param description the stored description, which may be shorter than the band
     * @param width the declared width of the band the description is emitted into
     * @return the leftmost {@code width} characters of the description, or the whole of it when it is
     *     shorter; never {@code null}
     */
    private static String narrow(String description, int width) {
        return description.length() <= width ? description : description.substring(0, width);
    }
}
