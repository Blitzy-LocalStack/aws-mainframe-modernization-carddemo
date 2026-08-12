package com.carddemo.reporting.service;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.observability.LogSafeText;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportTotalsResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.mapper.CobolEditMask;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import com.carddemo.reporting.mapper.TransactionReportMapper;
import com.carddemo.reporting.repository.TransactionReportRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emits the daily transaction report of {@code app/cbl/CBTRN03C.cbl}, and composes its values.
 *
 * <h2>What this class is</h2>
 *
 * <p>{@code app/cbl/CBTRN03C.cbl} is a 649-line batch writer, laid out by
 * {@code app/cpy/CVTRA07Y.cpy} (73 lines) and driven in the reference by
 * {@code app/jcl/TRANREPT.jcl} (84 lines). Each significant paragraph of that program becomes one
 * named method here, so that
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite paragraph-to-method pairs
 * instead of gesturing at a whole class. Everything under {@code app/} is read as reference material
 * and stays byte-identical; this migration adds a path and removes none.
 *
 * <p>Two surfaces are published, and they are different in kind. {@link #generateReport} emits the
 * report as records through a caller-supplied sink, which is what a batch run wants.
 * {@link #composeDetailLines} and {@link #composeTotals} return response values, which is what the
 * two operations published at {@code src/main/resources/openapi/reporting-api.yaml} want. Both drive
 * the same accumulation engine, for the reason recorded against {@link #composeTotals}.
 *
 * <h2>This class orchestrates, and formats nothing</h2>
 *
 * <p>Assumptions: every band geometry, every declared width, all padding and both edit masks live in
 * {@code com.carddemo.reporting.mapper}, which the charter of the enclosing package establishes.
 * {@code app/cpy/CVTRA07Y.cpy} declares the record 133 characters wide on its L48, the reference
 * declares the same 133 on L85 and L133 of {@code app/cbl/CBTRN03C.cbl}, and
 * {@code app/jcl/TRANREPT.jcl} declares LRECL=133 on its L78. That number is the record's declared
 * length and never a sum of field widths; deriving it by addition invites a one-character error that
 * only a byte comparison would catch. This class quotes no width at all: where it needs one it reads
 * it from {@link ReportBandLayouts#TRANSACTION_DETAIL_REPORT}, so a width can be wrong in the mapper
 * or right everywhere and can never be wrong in only one of the two.
 *
 * <p>Assumptions: no query is written here and no persistence context is touched. Every read arrives
 * through {@link TransactionReportRepository}, whose login role can read the reporting views and
 * write nothing, and this module declares no schema object and no migration directory of any kind.
 *
 * <h2>The report's own pagination is derived, never assumed</h2>
 *
 * <p>Assumptions: the page break is the equality test at L282 of {@code app/cbl/CBTRN03C.cbl},
 * {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}, and the counter it reads is advanced by
 * heading and subtotal bands as well as by detail lines. The reference advances it at nine places:
 * L299 and L302 in the page-total paragraph, L311 and L314 in the card-break paragraph, L327, L331,
 * L335 and L339 in the headings paragraph, and L373 in the detail paragraph. The grand-total
 * paragraph at L318 to L322 is the one write paragraph that advances it at no place at all. The
 * number of detail lines that lands on a report page is therefore a consequence of those advances and
 * is not a constant: the shipped oracle
 * {@code tests/golden/reporting/e2e_full_cycle_report.expected} runs 12, 18, 18, 18, 18, 18, 40, 12,
 * 32, 12, 18, 18, 18 and 10 detail lines across its 14 pages, and never 20. Because L282 tests for
 * equality rather than for a threshold, a two-band or four-band advance can step straight over a
 * multiple of the modulus, which is how pages of 40 and 32 arise.
 *
 * <p>Assumptions: this is print pagination inside one rendered document. It is a different concept
 * from the keyset paging that browse endpoints use, and the two are never mixed here: no keyset
 * envelope, no cursor and no row-window vocabulary appears in this class.
 *
 * <h2>Totalling rolls up in three levels</h2>
 *
 * <p>Assumptions: the reference adds each amount into the page and card-break accumulators together
 * at L287 and L288, carries the page figure into its band at L294, adds the page figure into the
 * grand accumulator at L297 and only then resets the page accumulator at L298. The grand total
 * therefore accumulates from page totals and not from raw amounts. That is arithmetic rather than
 * arrangement: the 14 page totals of the shipped oracle sum to 78,557.92, which is the grand total it
 * reports to the cent. A single flat summation over the detail lines would agree by coincidence and
 * would stop agreeing the moment a page boundary moved.
 *
 * <h2>The break is on card number while the band reads Account Total</h2>
 *
 * <p>Assumptions: L181 of {@code app/cbl/CBTRN03C.cbl} compares {@code WS-CURR-CARD-NUM}, declared
 * {@code PIC X(16)} at its L137, against {@code TRAN-CARD-NUM}, and {@code app/jcl/TRANREPT.jcl}
 * sorts on that one key at its L46. The band the break writes is labelled {@code 'Account Total'} by
 * {@code app/cpy/CVTRA07Y.cpy} L58. Both are true at once. Grouping on the account identifier instead
 * would emit one band where the reference emits several, for every account that holds more than one
 * card, so this class breaks on the card rendering and keeps the label verbatim.
 *
 * <h2>Two registered divergences at end of file</h2>
 *
 * <p>Trade-offs: the end-of-file branch at L197 to L204 of {@code app/cbl/CBTRN03C.cbl} adds an
 * amount into the page and card-break accumulators a second time at L200 and L201, reading the record
 * area left by the last successful read whose amount L287 and L288 already counted, and it performs
 * the page totals at L202 and the grand totals at L203 without ever performing the card-break
 * paragraph. The shipped oracle shows both consequences: its closing page total of 5,046.19 is the
 * 4,442.97 of its 10 last-page detail lines plus its last line's own 603.22, and its tail runs detail,
 * page total, separator, grand total with no card-break band after the final detail line. This class
 * reproduces neither. It counts each amount once and it closes the final card group exactly as it
 * closes every other, which is what the two entries <b>D-REPORT-GRAND-TOTAL</b> and
 * <b>D-REPORT-CLOSING-TOTAL</b> of {@code docs/architecture/cobol-to-service-traceability.md} record,
 * and what {@code TransactionReportMapper} already states for the encoding half. The compromise
 * accepted is that a record comparison against a captured reference artifact differs in exactly those
 * two places and has to be read against that register rather than treated as a defect. Neither
 * divergence is an edit to anything under {@code app/}.
 *
 * <h2>Method names cannot follow the reference numbering</h2>
 *
 * <p>Alternatives Considered: naming each method after the paragraph number it transcribes. That is
 * unavailable, because the reference reuses two prefixes: {@code 1110-} names both the page totals at
 * L293 and the grand totals at L318, and {@code 1120-} names the card-break totals at L306, the
 * headings at L324 and the detail line at L361. Java requires distinct names, so each method below is
 * named for the band it emits. The numbering collision is the reason, and it is an observation about
 * an immutable artifact rather than a criticism of it.
 *
 * <p>Assumptions: the six file-open paragraphs at L376 to L466 and the six file-close paragraphs at
 * L514 to L605 have no counterpart here at all, and none should be looked for. Connection lifecycle
 * belongs to the injected repository and to the transaction the caller opens.
 *
 * <h2>The business date is always supplied</h2>
 *
 * <p>Assumptions: no method here reads a clock, and none accepts one. The reference already works
 * this way for the emitting half: {@code app/jcl/TRANREPT.jcl} carries the selection range in on its
 * L47 and L48 and {@code app/cbl/CBTRN03C.cbl} takes the heading range from an external parameter
 * record it reads at its L168. Resolving a preset into concrete dates is the request edge's work, at
 * L215 and L241 of {@code app/cbl/CORPT00C.cbl}, and {@code ReportExecutionService} owns it.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries Javadoc stating its purpose, each parameter with its name, type and
 * description, its return value and every exception it can raise, because user-specified Rule 1
 * (Explainability) attaches that obligation to every function and class at its L15 and enumerates the
 * four elements at its L18, L19, L20 and L21 without qualifying by visibility. The private methods
 * carry most of the behaviour in this class, so they are documented on exactly the same terms as the
 * public ones, and {@code config/checkstyle/checkstyle.xml} audits both at private scope. Where two
 * sources appear to disagree the order of precedence is Rule 1 first, then that ruleset, then
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and prose last.
 *
 * <p>Assumptions: a clean run of that ruleset is necessary and not sufficient. It cannot see whether
 * a rationale is specific, and its checks on a throw declaration cannot reach a throw raised from
 * inside a catch block, so those are written out by hand below. Rule 1's validation gate at its L43 is
 * conjunctive: a docstring alone does not satisfy it and an inline rationale alone does not either.
 *
 * <h2>What the tests of this class have to hold</h2>
 *
 * <p>Assumptions: the test tree belongs to another author, and the contract it has to hold is stated
 * here so that it is stated once. The detail-lines-per-page sequence follows the counter advances
 * rather than the modulus, including on a page closed by a card break; the roll-up reproduces
 * transaction into page into grand and transaction into card group, with the grand figure taken from
 * page figures; the break fires on the card rendering and the band emitted reads
 * {@code 'Account Total'}; the two registered end-of-file divergences are asserted rather than merely
 * described; an absent range surfaces a refusal instead of returning quietly with nothing emitted; a
 * missing dimension refuses the run and names the transaction; this class is provably free of any
 * clock; and the module's database role is provably unable to write.
 *
 * @see TransactionReportMapper
 * @see TransactionReportRepository
 */
@Service
public class TransactionReportService {

    /**
     * Modulus the line counter is tested against to decide where a report page ends.
     *
     * <p>Assumptions: 20 is declared as {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at L131 and
     * L132 of {@code app/cbl/CBTRN03C.cbl} and is read only by the equality test at its L282. It is
     * named for the arithmetic it takes part in and deliberately not for a quantity of rows, because
     * it is not one: heading and subtotal bands advance the same counter, so the detail lines that
     * land on a page range from 10 to 40 across the 14 pages of
     * {@code tests/golden/reporting/e2e_full_cycle_report.expected}. A name suggesting rows per page
     * is the single most likely way to arrive at an emitter that writes 20 of them and produces a
     * different record stream.</p>
     */
    public static final int LINE_COUNTER_MODULUS = 20;

    /**
     * Greatest number of report lines the value-composing surface will assemble in one call.
     *
     * <p>Trade-offs: {@link #composeDetailLines} holds every line it returns in memory at once, so it
     * is bounded and a range holding more transactions than this is refused rather than shortened. A
     * shortened report whose grand figure covered only part of its range would reconcile against
     * nothing, which is worse than a refusal the caller can act on. The bound belongs to that surface
     * alone: {@link #generateReport} hands each record to a sink as it goes, exactly as the reference
     * writes each record to a data set as it goes at L345 of {@code app/cbl/CBTRN03C.cbl}, so it holds
     * no report in memory and needs no bound.</p>
     */
    public static final int MAX_REPORT_LINES = 10_000;

    /**
     * Greatest number of driving rows the integrity diagnostic will read.
     *
     * <p>Assumptions: the diagnostic runs only when the two reconciliation counts disagree, and its
     * whole purpose is to name one offending transaction, which is what the three reference lookup
     * paragraphs do when they display the offending key before abending. Reading the range again in
     * full to name its first row would turn a diagnostic into a second complete scan of it.</p>
     */
    public static final int MAX_DIAGNOSTIC_ROWS = 1;

    /**
     * Bands the headings step emits, and therefore the advance it makes to the line counter.
     *
     * <p>Assumptions: four is the number of advances {@code 1120-WRITE-HEADERS} makes, at L327, L331,
     * L335 and L339 of {@code app/cbl/CBTRN03C.cbl}, for the title band of its L325, the blank band of
     * its L329, the column band of its L333 and the separator rule of its L337. It is a property of
     * that paragraph rather than of any band's content, which is why the value-composing surface can
     * advance by it without building a single band.</p>
     *
     * <p>Trade-offs: this restates a count that {@link TransactionReportMapper#encodeHeadingBlock} also
     * determines, and the duplication is accepted only because it is checked. The emitting path compares
     * the two before it writes anything and refuses outright if they differ, so the pair can be wrong
     * loudly and cannot drift quietly -- and a silent drift here would move every page boundary in the
     * report while leaving each page internally consistent.</p>
     */
    private static final int HEADING_BAND_COUNT = 4;

    /**
     * Diagnostic channel for this class.
     *
     * <p>Assumptions: the reference leaves four diagnostic {@code DISPLAY} statements on the report
     * path -- the whole input record at L180 of {@code app/cbl/CBTRN03C.cbl}, the amount and the page
     * accumulator at its L198 and L199, and the heading range at its L232 and L233 -- and every one of
     * them goes to the job log rather than into the report data set. Routing them to a logger keeps
     * that separation exact, because a diagnostic written into the record stream would shift every
     * page boundary that follows it as well as corrupting the record it landed in.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportService.class);

    /**
     * Name this class records as the component held responsible when it raises the abend equivalent.
     *
     * <p>Assumptions: the culprit component of {@code ABEND-DATA} is declared
     * {@code ABEND-CULPRIT PIC X(8)} in {@code app/cpy/CSMSG02Y.cpy}, and eight characters is exactly
     * the width of the reference program's own name, so the name is carried without shortening.</p>
     */
    private static final String THIS_PROGRAM = "CBTRN03C";

    /**
     * Condition code this class records when it raises the abend equivalent.
     *
     * <p>Assumptions: {@code 9999-ABEND-PROGRAM} at L626 of {@code app/cbl/CBTRN03C.cbl} moves 999
     * into its abend code at L629 before calling the language-environment abend service at L630, and
     * {@link AbendDetail#ABEND_CODE_LENGTH} is four characters. The value is therefore carried as 999
     * rendered into four positions rather than as a number, because the receiving component is
     * character data and a numeric rendering would place the digits differently.</p>
     */
    private static final String ABEND_CODE = "0999";

    /**
     * Reason text this class records when the report record stream cannot be written.
     *
     * <p>Assumptions: this is the verbatim text {@code 1111-WRITE-REPORT-REC} displays at L354 of
     * {@code app/cbl/CBTRN03C.cbl} when the write status is not {@code '00'}, and transformation rule
     * T8 requires a user-visible string to cross unchanged. It sits in the reason component rather
     * than the message component so that the message component stays free for the underlying
     * cause.</p>
     */
    private static final String WRITE_FAILURE_REASON = "ERROR WRITING REPTFILE";

    /**
     * Verbatim text the card cross-reference lookup displays when a card resolves to no account.
     *
     * <p>Assumptions: {@code 1500-A-LOOKUP-XREF} displays this at L487 of
     * {@code app/cbl/CBTRN03C.cbl} on its invalid-key path, and it carries a space before and after
     * its colon. Both spaces are part of the literal and transformation rule T8 carries it across
     * character for character.</p>
     */
    private static final String INVALID_CARD_MESSAGE = "INVALID CARD NUMBER : ";

    /**
     * Verbatim text the transaction-type lookup displays when a type code resolves to no row.
     *
     * <p>Assumptions: {@code 1500-B-LOOKUP-TRANTYPE} displays this at L497 of
     * {@code app/cbl/CBTRN03C.cbl}, with the same colon spacing as the two other lookup texts.</p>
     */
    private static final String INVALID_TYPE_MESSAGE = "INVALID TRANSACTION TYPE : ";

    /**
     * Verbatim text the transaction-category lookup displays when a category key resolves to no row.
     *
     * <p>Assumptions: {@code 1500-C-LOOKUP-TRANCATG} displays this at L507 of
     * {@code app/cbl/CBTRN03C.cbl}, again with a space on both sides of the colon.</p>
     */
    private static final String INVALID_CATEGORY_MESSAGE = "INVALID TRAN CATG KEY : ";

    /**
     * The read-only query surface every read in this class arrives through.
     */
    private final TransactionReportRepository reports;

    /**
     * Records the repository this service reads through.
     *
     * <p>Assumptions: the collaborator arrives by constructor injection and is held unmodifiable, so
     * this class has no settable state and one instance serves concurrent callers safely. The
     * reference's working storage at L128 to L137 of {@code app/cbl/CBTRN03C.cbl} is process-wide and
     * single-threaded, so a field behaving like it would be shared across unrelated report runs; every
     * value that varies per run is a parameter or a local instead.</p>
     *
     * @param reports the report-side query surface, a {@link TransactionReportRepository}; must not be
     *     {@code null}
     * @throws NullPointerException if {@code reports} is {@code null}, refused here rather than at the
     *     first read so that a misconfigured context fails while it is being built
     */
    public TransactionReportService(TransactionReportRepository reports) {
        this.reports = Objects.requireNonNull(reports, "reports must not be null");
    }

    /**
     * Destination one emitted report record is handed to.
     *
     * <p>Assumptions: this is the target equivalent of the report data set the reference writes to at
     * L345 of {@code app/cbl/CBTRN03C.cbl}, declared {@code FD-REPTFILE-REC PIC X(133)} at its L85.
     * Each call receives one complete record of that declared length; the sink appends record
     * separators, buffers and closes on its own terms, because a run writing to an object store and a
     * run writing to a stream differ in all three and none of those differences is the report's
     * business.</p>
     *
     * <p>Alternatives Considered: taking the destination as a constructor collaborator instead. It is
     * a method parameter because the destination varies per run while the repository does not -- the
     * reference names its own destination per run too, on the data-definition statement at L76 to L80
     * of {@code app/jcl/TRANREPT.jcl} rather than anywhere inside the program -- so a constructor
     * collaborator would either pin every run of one instance to one destination or reintroduce
     * settable state on a class that deliberately has none.</p>
     */
    public interface ReportRecordSink {

        /**
         * Accepts one complete report record.
         *
         * <p>Assumptions: the refusal is declared as a checked condition rather than left unchecked,
         * because every plausible destination for this report -- an object store, a stream, a data set
         * -- reports a refusal that way, and an implementation forced to wrap its own refusal would
         * choose its own wrapper. Declaring it here means one shape of refusal reaches the caller,
         * which is what lets the write funnel convert it in exactly one place, the way the reference
         * tests its write status in exactly one place at L346 to L358 of
         * {@code app/cbl/CBTRN03C.cbl}.</p>
         *
         * @param record the record to append, a {@code byte[]} of exactly
         *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes; never {@code null}
         * @throws IOException if the record cannot be appended, which the caller treats as the
         *     reference treats a write status other than {@code '00'} at L346 to L358 of
         *     {@code app/cbl/CBTRN03C.cbl}
         */
        void write(byte[] record) throws IOException;
    }

    /**
     * What one completed report run emitted, and the figure it closed with.
     *
     * <p>Assumptions: a summary is returned rather than nothing, so that a caller can tell a run that
     * emitted detail lines from one that emitted only the two closing bands. The reference offers no
     * such distinction -- it displays an end-of-execution line at L215 of
     * {@code app/cbl/CBTRN03C.cbl} and returns the same way in both cases -- and an orchestrator
     * deciding whether a report is worth publishing needs it.</p>
     *
     * @param recordsWritten a {@code long} count of the records that reached the sink, counting
     *     heading, detail, subtotal and separator bands alike, which is the number of records the
     *     emitted report holds
     * @param detailLines a {@code long} count of the detail lines emitted, being the number of
     *     transactions the range resolved
     * @param pageTotalBands a {@code long} count of the page-total bands emitted, which is one more
     *     than the number of page breaks because the run closes with one
     * @param accountTotalBands a {@code long} count of the card-break bands emitted, each carrying the
     *     label {@code 'Account Total'} declared at L58 of {@code app/cpy/CVTRA07Y.cpy}
     * @param grandTotal the grand figure the run closed with, a {@link Money} accumulated from the
     *     page figures as L297 of {@code app/cbl/CBTRN03C.cbl} accumulates it
     */
    public record ReportGenerationSummary(
            long recordsWritten,
            long detailLines,
            long pageTotalBands,
            long accountTotalBands,
            Money grandTotal) {

        /**
         * Refuses a summary whose closing figure is absent.
         *
         * <p>Assumptions: the figure is exact decimal and a zero figure is a legitimate value that the
         * total mask renders as blanks, so absence cannot be signalled by zero and has to be refused
         * outright. The four counts need no check because a primitive count cannot be absent.</p>
         *
         * @param recordsWritten a {@code long} count of the records that reached the sink; any
         *     non-negative value
         * @param detailLines a {@code long} count of the detail lines emitted; any non-negative value
         * @param pageTotalBands a {@code long} count of the page-total bands emitted; any non-negative
         *     value
         * @param accountTotalBands a {@code long} count of the card-break bands emitted; any
         *     non-negative value
         * @param grandTotal the closing grand figure, a {@link Money}; must not be {@code null}
         * @throws NullPointerException if {@code grandTotal} is {@code null}
         */
        public ReportGenerationSummary {
            Objects.requireNonNull(grandTotal, "grandTotal must not be null");
        }
    }

    /**
     * The working storage of one report run, alive only for the duration of that run.
     *
     * <p>Assumptions: these seven mutable values transcribe {@code WS-REPORT-VARS} at L128 to L137 of
     * {@code app/cbl/CBTRN03C.cbl}, whose three accumulators are each declared
     * {@code PIC S9(09)V99} at its L134, L135 and L136 -- nine integer positions and two decimal
     * positions, which is exactly what the total mask at L54 of {@code app/cpy/CVTRA07Y.cpy} can
     * render and therefore not something to widen.</p>
     *
     * <p>Alternatives Considered: holding these as fields of the enclosing service, which is the
     * arrangement the reference has because its working storage is process-wide and its one program is
     * single-threaded. Rejected outright: one instance of this service answers concurrent callers, so
     * a field would let two report runs add into each other's accumulators and mis-place each other's
     * page breaks, with no error anywhere and a wrong figure in both reports. Passing one holder down
     * the call chain costs a parameter on each private method and buys isolation by construction.</p>
     *
     * <p>Alternatives Considered: threading the values through as return values instead of mutating a
     * holder. Rejected because five accumulators and a counter cannot be returned from a step that also
     * emits records without inventing a second carrier type, and the reference's own paragraphs are
     * mutations of shared storage, so a holder keeps the transcription readable against them.</p>
     */
    private static final class ReportAccumulators {

        // Assumptions: this is WS-FIRST-TIME PIC X VALUE 'Y' at L128, whose only two uses are the
        //       heading-suppression test at L275 and the card-break guard at L182. It is a
        //       within-one-run flag over a sequential read and not a re-entry discriminator: the
        //       pseudo-conversational discriminator the migration removes is CDEMO-PGM-CONTEXT in
        //       app/cpy/COCOM01Y.cpy, which spans terminal turns, whereas this one never outlives the
        //       call that created it and is therefore not session state under any reading.
        private boolean firstTime = true;

        // Assumptions: this is WS-LINE-COUNTER PIC 9(09) COMP-3 VALUE 0 at L129 and L130. It counts
        //       every emitted band and not only detail lines, which is the whole mechanism behind the
        //       page break at L282, and it is held as a 64-bit count rather than at the nine digits the
        //       picture declares because a streamed run has no bound on its length and the reference's
        //       own bound is an artifact of a declared display width.
        private long lineCounter;

        // Assumptions: WS-PAGE-TOTAL at L134. It is reset at L298, immediately after L297 has carried
        //       it into the grand accumulator, so its value is only ever the page just closed.
        private Money pageTotal = Money.ZERO;

        // Assumptions: WS-ACCOUNT-TOTAL at L135, reset at L310 once its band has been emitted.
        private Money accountTotal = Money.ZERO;

        // Assumptions: WS-GRAND-TOTAL at L136. It is added to at L297 alone, from the page figure and
        //       never from a raw amount, which is why a flat summation is not an equivalent.
        private Money grandTotal = Money.ZERO;

        // Assumptions: WS-CURR-CARD-NUM PIC X(16) VALUE SPACES at L137, the key the break at L181
        //       compares. It starts absent rather than blank so that the first row always differs from
        //       it, which is the effect the reference gets from a blank comparand.
        private String currentGroup;

        // Assumptions: the account identifier the card cross-reference resolved for currentGroup. The
        //       reference resolves it inside the break block, at L186 and L187, so it is resolved once
        //       per card and then reused by every detail line of that card at L364.
        private Long currentAccountId;

        // Assumptions: the last page figure emitted and the last card-break figure emitted are kept
        //       because the value-composing surface reports the bands the report closes with, and by
        //       then the two accumulators above hold zero: L298 resets the page figure once L297 has
        //       carried it into the grand figure, and L310 resets the card-break figure once its band
        //       has been emitted.
        private Money lastPageTotal = Money.ZERO;

        private Money lastAccountTotal = Money.ZERO;

        // Assumptions: these four counts exist only to fill the run summary. They are held beside the
        //       accumulators rather than derived afterwards because the emitting sequence is the only
        //       place that knows how many bands each step produced.
        private long recordsWritten;

        private long detailLines;

        private long pageTotalBands;

        private long accountTotalBands;
    }

    /**
     * Emits the whole daily transaction report for one inclusive business-date range.
     *
     * <p>This is the main paragraph of {@code app/cbl/CBTRN03C.cbl}: the sequential read loop at its
     * L170 to L206, the card-number break at its L181 to L188 and the closing sequence its
     * end-of-file branch performs at its L197 to L204. Every band it produces is encoded by
     * {@link TransactionReportMapper} and handed to {@code sink} through the one write method this
     * class has, so a caller supplies a destination and nothing else.</p>
     *
     * <p>Refactoring Rationale: the reference resolves its heading range from an external parameter
     * record while {@code app/jcl/TRANREPT.jcl} resolves its selection range from two literals of its
     * own, at L43 and L44, against the parameter data set its L73 and L74 name and the program consumes
     * at L277 and L278. Nothing in that arrangement makes the two agree, so a run can select one range
     * of records and head the report with another. The online submitter avoids it by writing both
     * channels from one value, at L429 to L432 of {@code app/cbl/CORPT00C.cbl}; the batch path does
     * not. This method takes one range and passes it to both the row selection and the heading band,
     * which replaces the two-channel arrangement rather than restating it, and is a materially stronger
     * property than reproducible reruns on their own.</p>
     *
     * <p>Assumptions: the row selection predicate belongs to the repository, and no filter is applied
     * again here. {@code app/jcl/TRANREPT.jcl} L47 and L48 select on the date part with both bounds
     * admitted, and the in-line comparison the reference carries at its L173 to L178 selects nothing at
     * all because its {@code CONTINUE} branch and its {@code NEXT SENTENCE} branch both fall through to
     * L179. Neither the inert construct nor a duplicate of the predicate is reproduced: a second filter
     * in this method would be a second place for the range's inclusivity to be got wrong. That
     * {@code INCLUDE COND=} is a record-selection predicate and not a step gate, which is why it becomes
     * a query restriction and never an orchestration branch even though it shares a keyword with the
     * step gates elsewhere in the same job.</p>
     *
     * <p>Assumptions: the run is read-only and single-snapshot. The repository's streaming query
     * requires an existing transaction, so this method opens one; without it the two reconciliation
     * counts and the stream could see three different states of the same relation and report a
     * shortfall that never existed.</p>
     *
     * @param rangeStart the first business date to report on, inclusive, a {@link LocalDate}; must not
     *     be {@code null}
     * @param rangeEnd the last business date to report on, inclusive, a {@link LocalDate}; must not be
     *     {@code null} and must not precede {@code rangeStart}
     * @param sink the destination each emitted record is handed to, a {@link ReportRecordSink}; must
     *     not be {@code null}
     * @return a {@link ReportGenerationSummary} carrying the record and band counts of the run and the
     *     grand figure it closed with; never {@code null}
     * @throws ClientInputException if either bound is absent or the range is inverted, which replaces
     *     the reference's silent empty run
     * @throws IllegalStateException if a transaction resolves fewer or more than one of each dimension,
     *     naming the first driving transaction of the range, or if the sink cannot accept a record, in
     *     which case the message carries the abend detail this class records
     * @throws org.springframework.dao.DataAccessException if the reporting views cannot be read
     */
    @Transactional(readOnly = true)
    public ReportGenerationSummary generateReport(
            LocalDate rangeStart, LocalDate rangeEnd, ReportRecordSink sink) {

        requirePresentRange(rangeStart, rangeEnd);
        requireOrderedRange(rangeStart, rangeEnd);
        Objects.requireNonNull(sink, "sink must not be null");

        reconcileDimensionIntegrity(rangeStart, rangeEnd);

        ReportAccumulators acc = new ReportAccumulators();

        // Assumptions: the cursor is closed on every path because the repository's charter makes
        //       closing the caller's obligation, and an unclosed cursor holds a driver-side result set
        //       open for the life of the transaction this method opened. The reference has the same
        //       discipline and states it the same number of times: its six close paragraphs at L514 to
        //       L605 of app/cbl/CBTRN03C.cbl run on its one exit path.
        try (Stream<TransactionReportRepository.ReportLine> lines =
                reports.streamReportLines(rangeStart, rangeEnd)) {

            lines.forEach(line -> {
                // Assumptions: the reference displays the whole input record at L180 before doing
                //       anything with it, to the job log and never to the report. The equivalent is a
                //       trace record whose content is sanitised, because a value read from a relation
                //       reaching a log line verbatim is how a control character forges a log entry.
                // WHY : Assumptions: the transaction identifier is reported and the card is NOT. The
                //       identifier is named in docs/architecture/observability.md among the identity a
                //       diagnostic may keep, and it already identifies the row uniquely -- it is the
                //       primary key of the record this line reads -- so the card rendering added no
                //       diagnostic power it did not already have.
                //       Refactoring Rationale: this line also rendered the card through
                //       LogSafeText.sanitize. That was the wrong function for the value twice over: the
                //       sanitiser exists to neutralise control characters and says nothing about
                //       disclosure, and the one sanctioned abbreviation of a card number is
                //       com.carddemo.common.security.CardNumberMasker, applied "only where a rendering
                //       has no other way to say which row it describes". This rendering had another way,
                //       so neither function was the right answer and the card is omitted instead.
                //       Trade-offs: a reader following a control break can no longer see the group's key
                //       in the trace line. The break itself is still observable -- the surviving
                //       identifiers change at the boundary -- and the report body carries the masked
                //       card where the reference prints it, so nothing an operator needs is lost from
                //       the artifact that is meant to carry it.
                if (LOG.isTraceEnabled()) {
                    // WHY : Refactoring Rationale: the card fragment is REMOVED from this record. It
                    //       carried the masked rendering, which is four digits of a primary account
                    //       number, beside the transaction identifier -- and the two together link a
                    //       cardholder to a transaction in a stream the sensitive-data logging contract
                    //       in docs/architecture/observability.md covers by name. The fragment was also
                    //       unnecessary: the identifier already locates the row, and the card the row
                    //       belongs to is recoverable from it deliberately rather than published to
                    //       every reader of a trace stream. The fingerprint is not substituted either --
                    //       it is a stable per-card correlator, so emitting it would reintroduce the
                    //       same linkability by another route.
                    LOG.trace("read transaction {}",
                            LogSafeText.sanitize(line.getTransactionId()));
                }

                if (breakOnGroupChange(acc, line.getCardFingerprint(), sink)) {
                    // Assumptions: the resolution sits here, conditional on the break, because the
                    //       reference's cross-reference lookup sits inside its own break block at L187.
                    //       One resolution serves every detail line of the card, which is what L364
                    //       prints from.
                    acc.currentAccountId = requireResolvedAccount(
                            line.getAccountId(), line.getCardFingerprint());
                }

                writeTransactionReport(acc, sink, rangeStart, rangeEnd, line.getAmount(), line);
            });
        }

        closeReport(acc, sink);

        return new ReportGenerationSummary(acc.recordsWritten, acc.detailLines,
                acc.pageTotalBands, acc.accountTotalBands, acc.grandTotal);
    }

    /**
     * Closes one card group when the next group arrives, and resolves the new group's account.
     *
     * <p>This is the break block at L181 to L188 of {@code app/cbl/CBTRN03C.cbl} together with the
     * cross-reference lookup {@code 1500-A-LOOKUP-XREF} its L187 performs.</p>
     *
     * <p>Assumptions: the comparison is on the card rendering, which is what L181 compares against
     * {@code WS-CURR-CARD-NUM} at its declared {@code PIC X(16)} on L137, and what
     * {@code app/jcl/TRANREPT.jcl} orders the input by on its L46. The band the break emits is
     * labelled {@code 'Account Total'} by L58 of {@code app/cpy/CVTRA07Y.cpy}, so the key and the label
     * name different things and both are carried as they are. Grouping on the account identifier
     * instead would emit a single band for an account holding several cards where the reference emits
     * one per card, which changes the record stream, the card-break figures and every page boundary
     * after the first such account.</p>
     *
     * <p>Assumptions: the guard on the emission is the reference's own test at L182, so no band is
     * emitted before the first group has any lines in it. The first row always takes this branch,
     * because the reference starts its comparand blank and this one starts it absent, and on that first
     * row the guard is still unset so nothing is closed.</p>
     *
     * <p>Assumptions: this method reports whether the group changed rather than resolving the new
     * group's account itself, so that the resolution stays at the caller's own read step. That mirrors
     * the reference exactly: its cross-reference lookup sits inside this block at L187, while its type
     * and category lookups sit outside it at L190 and L195. The account is therefore resolved once per
     * card and the other two dimensions once per transaction, and both cardinalities are the
     * reference's. Moving the account resolution out would repeat work for every transaction of a card;
     * moving the other two in would resolve one type and one category for a whole card and print them
     * on transactions that carry neither.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param groupKey the grouping key of the row just read, a {@code String} -- the card rendering on
     *     the emitting path and the account identifier on the value-composing path; must not be
     *     {@code null}
     * @param sink the destination a closing band is handed to, a {@link ReportRecordSink}, or
     *     {@code null} to advance the counter without emitting anything
     * @return {@code true} when the key differs from the group in progress and a new group has just
     *     been opened, {@code false} when the row continues the group in progress
     * @throws IllegalStateException if the sink cannot accept the closing band
     * @throws ArithmeticException if the closing figure needs more than the nine integer positions its
     *     mask provides
     */
    private boolean breakOnGroupChange(
            ReportAccumulators acc, String groupKey, ReportRecordSink sink) {

        if (groupKey.equals(acc.currentGroup)) {
            return false;
        }

        if (!acc.firstTime) {
            writeAccountTotals(acc, sink);
        }

        acc.currentGroup = groupKey;
        return true;
    }

    /**
     * Resolves the account a card group prints under, refusing a card the cross-reference cannot place.
     *
     * <p>This is {@code 1500-A-LOOKUP-XREF} at L484 to L492 of {@code app/cbl/CBTRN03C.cbl}, whose
     * invalid-key path displays the offending card at its L487, moves 23 into its status field, reports
     * that status and abends.</p>
     *
     * <p>Assumptions: the query joins the cross-reference rather than reading it per card, so a row
     * whose card resolves to nothing is not returned at all and this refusal is unreachable through the
     * reporting query. It is written regardless because it is the one place an absent account would
     * otherwise travel into the detail band, where it would surface as a rendering failure naming an
     * edit mask rather than naming the card, and because the whole point of the policy recorded against
     * {@link #reconcileDimensionIntegrity} is that an unresolvable dimension stops the run.</p>
     *
     * <p>Assumptions: the value quoted in the refusal is the narrowed rendering the reporting relation
     * exposes -- twelve asterisks and four digits -- and not a primary account number, so the refusal
     * names the card the way the reference's own display does without carrying what that display
     * carried. It is passed through the control-character filter first, because a value read from a
     * relation reaching a diagnostic verbatim is how a forged log entry gets written.</p>
     *
     * @param accountId the account the cross-reference resolved, a {@link Long}; may be {@code null},
     *     which is the condition this method exists to refuse
     * @param cardGroup the card rendering the account was resolved for, a {@code String} used only to
     *     name the refusal; must not be {@code null}
     * @return the resolved account identifier as a primitive count of eleven digits at most, ready for
     *     the band item {@code app/cpy/CVTRA07Y.cpy} L18 declares
     * @throws IllegalStateException if {@code accountId} is {@code null}, carrying the reference's own
     *     invalid-card text and the narrowed card rendering
     */
    private long requireResolvedAccount(Long accountId, String cardGroup) {
        if (accountId == null) {
            throw new IllegalStateException(renderedAbend(
                    abendDetail(INVALID_CARD_MESSAGE, LogSafeText.sanitize(cardGroup))));
        }
        return accountId;
    }

    /**
     * Emits the closing sequence the report ends with, once the input is exhausted.
     *
     * <p>This is the end-of-file branch at L197 to L204 of {@code app/cbl/CBTRN03C.cbl}, which
     * performs the page totals at its L202 and the grand totals at its L203.</p>
     *
     * <p>Trade-offs: two behaviours of that branch are not reproduced, and both are registered rather
     * than silent. Its L200 and L201 add an amount into the page and card-break accumulators a second
     * time, reading the record area left over from the last successful read whose amount L287 and L288
     * already counted, so the closing page figure and the grand figure each carry the final
     * transaction twice; the shipped oracle
     * {@code tests/golden/reporting/e2e_full_cycle_report.expected} shows its closing page figure of
     * 5,046.19 as the 4,442.97 of its 10 last-page detail lines plus that last line's own 603.22. And
     * the branch never performs the card-break paragraph, so the final group's own band is never
     * emitted; the same oracle ends detail, page total, separator, grand total with no card-break band
     * after its final detail line. This method counts each amount once and closes the final group
     * exactly as {@link #breakOnGroupChange} closes every other. The two divergences are
     * <b>D-REPORT-GRAND-TOTAL</b> and <b>D-REPORT-CLOSING-TOTAL</b> in
     * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register, and the
     * compromise accepted is that a record comparison against a captured reference artifact differs in
     * exactly two places -- one additional band at the end, and the final transaction's amount once
     * rather than twice in the closing page figure and the grand figure -- and has to be read against
     * that register. Every difference is bounded to the tail and none appears earlier in the report.</p>
     *
     * <p>Assumptions: the closing band is guarded by the reference's own first-time test at L182, so a
     * range that resolved no transaction closes no group. That leaves such a run emitting a page-total
     * band and a grand-total band whose figures are both zero and no heading at all, because the
     * heading is emitted from inside the detail paragraph at L279 and that paragraph never ran. That is
     * the reference's own outcome for an exhausted input and it is carried across unchanged; the run
     * summary is what tells a caller the report holds no detail line.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param sink the destination each closing band is handed to, a {@link ReportRecordSink}, or
     *     {@code null} to advance the counter without emitting anything
     * @throws IllegalStateException if the sink cannot accept one of the closing bands
     */
    private void closeReport(ReportAccumulators acc, ReportRecordSink sink) {
        if (!acc.firstTime) {
            writeAccountTotals(acc, sink);
        }
        writePageTotals(acc, sink);
        writeGrandTotals(acc, sink);
    }

    /**
     * Accumulates one transaction and emits its detail line, opening a report page where one is due.
     *
     * <p>This is {@code 1100-WRITE-TRANSACTION-REPORT} at L274 to L290 of
     * {@code app/cbl/CBTRN03C.cbl}, and the order of its four steps is the order that paragraph
     * states.</p>
     *
     * <p>Assumptions: the first heading is emitted from inside this step and not before the read loop,
     * because the reference emits it from inside its own detail paragraph, at L279 within the first-time
     * block that spans its L275 to L280. Hoisting it above the loop would emit the four heading bands
     * before the counter had been advanced by anything, which is the same four advances in a different
     * place only for an input that holds at least one row; for an exhausted input it would emit a
     * heading the reference does not emit at all.</p>
     *
     * <p>Assumptions: the range reaches the heading band as the two arguments of this step, which is
     * what the reference's L277 and L278 do by moving the parameter record's two dates into the band's
     * two date items. It is the same range the row selection used, by the single-parameter property
     * recorded against {@link #generateReport}.</p>
     *
     * <p>Assumptions: the page break emits the page totals and then the headings, in that order, which
     * is the order of L283 and L284, and it is tested before the amount is accumulated at L287 and L288
     * rather than after. Accumulating first would put this transaction's amount into the page it is
     * about to close instead of into the page it actually prints on.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param sink the destination each band is handed to, a {@link ReportRecordSink}, or {@code null} to
     *     advance the counter and the accumulators without emitting anything
     * @param rangeStart the first business date of the range, a {@link LocalDate} carried into the
     *     heading band; must not be {@code null}
     * @param rangeEnd the last business date of the range, a {@link LocalDate} carried into the heading
     *     band; must not be {@code null}
     * @param amount the transaction's amount, a {@link Money} added into the page and card-break
     *     accumulators; must not be {@code null}
     * @param line the resolved row whose detail band is emitted, a
     *     {@link TransactionReportRepository.ReportLine}, or {@code null} when {@code sink} is
     *     {@code null} and no band is built
     * @throws IllegalStateException if the sink cannot accept one of the bands this step emits
     */
    private void writeTransactionReport(
            ReportAccumulators acc,
            ReportRecordSink sink,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Money amount,
            TransactionReportRepository.ReportLine line) {

        if (acc.firstTime) {
            acc.firstTime = false;
            writeHeaders(acc, sink, rangeStart, rangeEnd);
        }

        // Assumptions: the test is an equality against the modulus and not a comparison against a row
        //       count, so a two-band or four-band advance can carry the counter straight past a multiple
        //       of it and leave a page running on. That is exactly how the shipped oracle comes to hold
        //       pages of 40 and 32 detail lines, and reading the test as "every twentieth detail line"
        //       is what produces an emitter whose every page holds 20.
        if (acc.lineCounter % LINE_COUNTER_MODULUS == 0) {
            writePageTotals(acc, sink);
            writeHeaders(acc, sink, rangeStart, rangeEnd);
        }

        acc.pageTotal = acc.pageTotal.plus(amount);
        acc.accountTotal = acc.accountTotal.plus(amount);

        writeDetail(acc, sink, line);
    }

    /**
     * Emits the four bands that open a report page and advances the counter once for each.
     *
     * <p>This is {@code 1120-WRITE-HEADERS} at L324 to L341 of {@code app/cbl/CBTRN03C.cbl}: the title
     * band at its L325, a blank band at its L329, the column band at its L333 and the separator rule at
     * its L337, each followed by an advance at its L327, L331, L335 and L339.</p>
     *
     * <p>Assumptions: the four bands come from {@link TransactionReportMapper#encodeHeadingBlock}, which
     * composes them in that same order, and the counter is advanced once per band actually returned
     * rather than by a separate constant of four. Advancing by a constant would let the two disagree if
     * the block's composition ever changed, and the advance is what the page break reads.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param sink the destination each band is handed to, a {@link ReportRecordSink}, or {@code null} to
     *     advance the counter without emitting anything
     * @param rangeStart the first business date of the range, a {@link LocalDate} rendered into the
     *     title band; must not be {@code null}
     * @param rangeEnd the last business date of the range, a {@link LocalDate} rendered into the title
     *     band; must not be {@code null}
     * @throws IllegalStateException if the sink cannot accept one of the four bands
     */
    private void writeHeaders(
            ReportAccumulators acc, ReportRecordSink sink,
            LocalDate rangeStart, LocalDate rangeEnd) {

        if (sink == null) {
            // Assumptions: the value-composing surface still advances by the same four, because the
            //       advance is what moves a page boundary, and it builds no band at all -- which is why
            //       it needs no range and may be called without one.
            acc.lineCounter += HEADING_BAND_COUNT;
            return;
        }

        List<byte[]> bands = TransactionReportMapper.encodeHeadingBlock(rangeStart, rangeEnd);
        if (bands.size() != HEADING_BAND_COUNT) {
            throw new IllegalStateException("the heading block composed " + bands.size()
                    + " bands where the reference paragraph advances the line counter "
                    + HEADING_BAND_COUNT + " times");
        }

        for (byte[] band : bands) {
            writeReportRecord(band, sink, acc);
        }
        acc.lineCounter += HEADING_BAND_COUNT;
    }

    /**
     * Emits one transaction detail band and advances the counter once.
     *
     * <p>This is {@code 1120-WRITE-DETAIL} at L361 to L374 of {@code app/cbl/CBTRN03C.cbl}, whose eight
     * moves at L363 to L370 are the eight values handed to
     * {@link TransactionReportMapper#encodeDetailLine} here, in that order.</p>
     *
     * <p>Assumptions: the account identifier comes from the cross-reference and not from the
     * transaction, which is what L364 states by moving {@code XREF-ACCT-ID}, and it was resolved once
     * for this card group by {@link #breakOnGroupChange}. The two descriptions come from the type and
     * category rows and the reference narrows each on the move, at L366 and L368, into band items whose
     * declared widths L22 and L26 of {@code app/cpy/CVTRA07Y.cpy} set; that narrowing is the mapper's,
     * so the values are handed over at their stored width and not shortened here.</p>
     *
     * <p>Assumptions: the reference qualifies two of its sources explicitly at L365 and L367, writing
     * {@code TRAN-TYPE-CD OF TRAN-RECORD} and {@code TRAN-CAT-CD OF TRAN-RECORD}, because both names
     * are also declared by {@code app/cpy/CVTRA04Y.cpy} and a bare name would not say which record it
     * meant. The same discipline is kept here: each of the eight values is read from a named accessor of
     * the projection rather than from a local whose origin a reader would have to trace, so the record
     * each value comes from is visible at the call.</p>
     *
     * <p>Assumptions: the reference opens this paragraph with {@code INITIALIZE} at L362, which blanks
     * the band's named items and leaves its {@code FILLER} items alone, so every separator character
     * the band declares -- the two single hyphens at L21 and L25 of {@code app/cpy/CVTRA07Y.cpy} among
     * them -- survives from one line to the next. A band rebuilt from empty in the target would lose
     * those characters, which is why the mapper seeds each band from a template that carries them and
     * why nothing here starts from a blank record.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param sink the destination the band is handed to, a {@link ReportRecordSink}, or {@code null} to
     *     advance the counter without building or emitting a band
     * @param line the resolved row to render, a {@link TransactionReportRepository.ReportLine}; must not
     *     be {@code null} unless {@code sink} is {@code null}
     * @throws IllegalStateException if the sink cannot accept the band, or if the category code the
     *     projection carries is not the four-digit value {@code app/cpy/CVTRA05Y.cpy} L7 declares
     */
    private void writeDetail(
            ReportAccumulators acc, ReportRecordSink sink,
            TransactionReportRepository.ReportLine line) {

        if (sink != null) {
            writeReportRecord(TransactionReportMapper.encodeDetailLine(
                    line.getTransactionId(),
                    acc.currentAccountId,
                    line.getTypeCd(),
                    line.getTypeDescription(),
                    categoryCodeOf(line.getCategoryCd()),
                    line.getCategoryDescription(),
                    line.getSource(),
                    line.getAmount()), sink, acc);
        }

        acc.lineCounter++;
        acc.detailLines++;
    }

    /**
     * Emits the page-total band and its separator, carrying the page figure into the grand figure.
     *
     * <p>This is {@code 1110-WRITE-PAGE-TOTALS} at L293 to L304 of {@code app/cbl/CBTRN03C.cbl}, and
     * the six statements below are its six statements in its order.</p>
     *
     * <p>Assumptions: the grand figure is accumulated here, from the page figure, at the point L297
     * accumulates it -- after the band has been emitted at L296 and before the page accumulator is
     * reset at L298. That ordering is what makes the grand figure the sum of the emitted page figures
     * rather than a second summation over the detail lines, and the shipped oracle proves the identity:
     * its 14 page figures sum to 78,557.92, which is the grand figure it reports. A flat summation would
     * agree only while every page boundary stayed where it is.</p>
     *
     * <p>Assumptions: the separator band this step emits is the same declared rule the card-break step
     * emits at L312 and the headings step emits at L337, and the repetition across those three places is
     * kept. It is not shared or elided: the rule appears in the record stream once per emitting step,
     * and removing a repetition would remove a record and shift every page boundary after it.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param sink the destination each band is handed to, a {@link ReportRecordSink}, or {@code null} to
     *     accumulate and advance without emitting anything
     * @throws IllegalStateException if the sink cannot accept either band
     * @throws ArithmeticException if the page figure needs more than the nine integer positions the
     *     total mask at L54 of {@code app/cpy/CVTRA07Y.cpy} provides
     */
    private void writePageTotals(ReportAccumulators acc, ReportRecordSink sink) {
        Money closing = acc.pageTotal;

        if (sink != null) {
            writeReportRecord(TransactionReportMapper.encodePageTotal(closing), sink, acc);
        }

        acc.grandTotal = acc.grandTotal.plus(closing);
        acc.pageTotal = Money.ZERO;
        acc.lineCounter++;

        if (sink != null) {
            writeReportRecord(TransactionReportMapper.encodeSeparatorRule(), sink, acc);
        }

        acc.lineCounter++;

        // Assumptions: the figure just emitted is kept because the value-composing surface reports the
        //       bands the report closes with, and by the time it asks the accumulator above has been
        //       reset to zero by the statement transcribing L298.
        acc.lastPageTotal = closing;
        acc.pageTotalBands++;
    }

    /**
     * Emits the card-break band and its separator, closing one card group.
     *
     * <p>This is {@code 1120-WRITE-ACCOUNT-TOTALS} at L306 to L316 of {@code app/cbl/CBTRN03C.cbl},
     * whose five statements are the five below in its order.</p>
     *
     * <p>Assumptions: the band's label is the verbatim {@code 'Account Total'} declared at L58 of
     * {@code app/cpy/CVTRA07Y.cpy} inside a 13-character item it fills exactly, which is why it carries
     * no space before the leader dots that follow it, where the page band's own 10-character label
     * inside an 11-character item carries one. Transformation rule T8 carries all three labels across
     * character for character, so the difference in spacing is preserved and not normalised.</p>
     *
     * <p>Assumptions: unlike the page figure, the card-break figure is not carried into any wider
     * figure. L310 resets it once its band has been emitted and nothing else reads it, so the card-break
     * bands of a report are a partition of its detail amounts and are not a term in its grand figure.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; mutated in place
     * @param sink the destination each band is handed to, a {@link ReportRecordSink}, or {@code null} to
     *     accumulate and advance without emitting anything
     * @throws IllegalStateException if the sink cannot accept either band
     * @throws ArithmeticException if the card-break figure needs more than the nine integer positions
     *     the total mask at L60 of {@code app/cpy/CVTRA07Y.cpy} provides
     */
    private void writeAccountTotals(ReportAccumulators acc, ReportRecordSink sink) {
        Money closing = acc.accountTotal;

        if (sink != null) {
            writeReportRecord(TransactionReportMapper.encodeAccountTotal(closing), sink, acc);
        }

        acc.accountTotal = Money.ZERO;
        acc.lineCounter++;

        if (sink != null) {
            writeReportRecord(TransactionReportMapper.encodeSeparatorRule(), sink, acc);
        }

        acc.lineCounter++;

        acc.lastAccountTotal = closing;
        acc.accountTotalBands++;
    }

    /**
     * Emits the single grand-total band the report ends with.
     *
     * <p>This is {@code 1110-WRITE-GRAND-TOTALS} at L318 to L322 of {@code app/cbl/CBTRN03C.cbl}.</p>
     *
     * <p>Assumptions: this is the one write paragraph of the reference that advances the line counter at
     * no point. Its single write at L321 is followed by nothing, where each of the other four write
     * paragraphs advances the counter at one of the nine sites L299, L302, L311, L314, L327, L331, L335,
     * L339 and L373. The absence is a consequence of position rather than an omission: this band is the
     * last record of the report, so no later test could read an advance it made. The counter is left
     * alone here for that reason, and a step that advanced it would put this class's page arithmetic one
     * out of step with the reference's for any caller reading the counter afterwards.</p>
     *
     * @param acc the accumulators of this run, a {@link ReportAccumulators}; read for its grand figure
     *     and, when a band is emitted, mutated only in its record count
     * @param sink the destination the band is handed to, a {@link ReportRecordSink}, or {@code null} to
     *     emit nothing
     * @throws IllegalStateException if the sink cannot accept the band
     * @throws ArithmeticException if the grand figure needs more than the nine integer positions the
     *     total mask at L66 of {@code app/cpy/CVTRA07Y.cpy} provides
     */
    private void writeGrandTotals(ReportAccumulators acc, ReportRecordSink sink) {
        if (sink != null) {
            writeReportRecord(TransactionReportMapper.encodeGrandTotal(acc.grandTotal), sink, acc);
        }
    }

    /**
     * Hands one encoded band to the sink, and abends if the sink refuses it.
     *
     * <p>This is {@code 1111-WRITE-REPORT-REC} at L343 to L359 of {@code app/cbl/CBTRN03C.cbl}, the one
     * place that program writes a record. Every band this class emits passes through here for that
     * reason: the reference's write status test at its L346, its diagnostic at its L354 and its abend at
     * its L357 are stated once there and are stated once here, so a new band cannot acquire its own
     * failure handling.</p>
     *
     * <p>Assumptions: a refusal is terminal and never skipped. The reference moves 12 into its result
     * field on any status other than {@code '00'} and abends, so a partially written report is not a
     * state this program produces; continuing past a refused record would emit a report missing a record
     * in the middle, whose page boundaries and subtotal bands would all still look internally
     * consistent.</p>
     *
     * @param record the band to append, a {@code byte[]} of the declared record length; must not be
     *     {@code null}
     * @param sink the destination to append it to, a {@link ReportRecordSink}; must not be {@code null}
     * @param acc the accumulators of this run, a {@link ReportAccumulators}, whose record count is
     *     advanced on success
     * @throws IllegalStateException if the sink refuses the record, carrying the condition code, the
     *     culprit and the reference's own diagnostic text, with the refusal as its cause. It is
     *     documented here by hand because the ruleset's throw analysis cannot see a throw raised from
     *     inside a catch block
     */
    private void writeReportRecord(
            byte[] record, ReportRecordSink sink, ReportAccumulators acc) {
        try {
            sink.write(record);
        } catch (IOException refusal) {
            // Assumptions: the checked refusal is converted rather than declared onward. The reference
            //       terminates the run through its abend service at L630 rather than returning a status
            //       a caller could ignore, and an unchecked failure carrying the abend detail is the
            //       target equivalent that the shared handler already renders. Declaring the checked
            //       type on every step above would put the same handling decision at each of them.
            // WHY : Refactoring Rationale: the refusal is logged as a DIGEST and its message no longer
            //       reaches the abend text. Both channels were unbounded by construction: the message
            //       of an object-store or file failure is composed by a driver or an SDK, not by this
            //       project, so it can carry a request URI -- and an artifact key is derived from a
            //       cardholder's identity -- a bucket name, a set of request headers or a whole
            //       response body. Passing the throwable to the logger additionally renders every
            //       cause's message under the default appender chain, which
            //       docs/architecture/observability.md records as the exact gap ThrowableDigest exists
            //       to close. The digest carries the chain of type names and the frame each link was
            //       raised at, which is what identifies a failure mode, and drops every message.
            // WHY : Assumptions: the abend reason becomes a CONSTANT rather than a narrowed rendering of
            //       the failure. The reference's own text is a constant -- 'ERROR WRITING REPTFILE' at
            //       L389 of app/cbl/CBTRN03C.cbl -- so a constant is the reference's behaviour as well
            //       as the safe choice, and the digest in the log is where a maintainer looks for which
            //       failure it was.
            LOG.error("event=report.record.refused reason={} recordOrdinal={} failure={}",
                    WRITE_FAILURE_REASON, acc.recordsWritten + 1, ThrowableDigest.of(refusal));
            throw new IllegalStateException(
                    renderedAbend(abendDetail(WRITE_FAILURE_REASON, WRITE_FAILURE_REASON)), refusal);
        }

        acc.recordsWritten++;
    }

    /**
     * Reads one bounded page of the report's detail lines, positioned by an opened cursor key.
     *
     * <p>Refactoring Rationale: this replaces {@code composeDetailLines(LocalDate, LocalDate)}, which
     * assembled the whole range into a list -- up to {@value #MAX_REPORT_LINES} rows -- and left the
     * caller to slice it. Two defects came out of that. The caller sliced ORDINALLY, carrying an integer
     * offset in its cursor token, so under a concurrent posting a page could repeat a line it had already
     * shown or skip one it had not; the migration plan states keyset positioning as a rule for exactly
     * that reason and offset positioning is not an available option. And the whole range was read for
     * every page, so the twentieth page cost twenty full scans of the range and the response time of the
     * first page was a function of the range's width rather than of the page's.</p>
     *
     * <p>Assumptions: the page is read by the query surface's own keyset methods, which order by the
     * per-card fingerprint and then the transaction identifier and continue strictly beyond an anchor row.
     * The look-ahead row those methods request is what makes the further-page answer a fact about the
     * relation rather than an estimate -- the same device the reference uses at
     * {@code app/cbl/COCRDLIC.cbl} L1197.</p>
     *
     * <p>Assumptions: the dimension reconciliation runs before the page is read, on every page rather
     * than on the first. It is two bounded aggregate queries and it is what stands in for the three
     * reference lookup paragraphs that abend on a miss; running it once would let a load that broke a
     * dimension between two pages go unreported, and the reference has no notion of a first page to
     * privilege.</p>
     *
     * <p>Assumptions: the range bound is still enforced, because a caller may page through a range and
     * the bound is a statement about how much of a range this class is willing to serve at all rather
     * than about one page.</p>
     *
     * @param rangeStart the first business date to cover, inclusive, a {@link LocalDate}; must not be
     *     {@code null}
     * @param rangeEnd the last business date to cover, inclusive, a {@link LocalDate}; must not be
     *     {@code null}
     * @param openedCursorKey the opened cursor key naming the boundary row to continue from, or
     *     {@code null} to read the leading page
     * @param backward {@code true} to read the page preceding the key, {@code false} to read the page
     *     following it; {@code true} with a {@code null} key is refused, because a backward step is taken
     *     from a row the caller holds and names nothing without it
     * @param sealer seals each boundary key this page reports into a client-facing token; must not be
     *     {@code null}
     * @return one bounded page of detail lines with its two sealed boundaries; never {@code null}
     * @throws NullPointerException if either bound or {@code sealer} is {@code null}
     * @throws ClientInputException if either bound is absent, if the range is inverted, or if the range
     *     holds more transactions than this class is willing to serve
     * @throws IllegalArgumentException if a backward page is requested with no cursor key
     * @throws IllegalStateException if a transaction in the range does not resolve to exactly one of each
     *     dimension, which is the target's equivalent of the reference abending on an unresolved lookup
     * @throws org.springframework.dao.DataAccessException if the reporting views cannot be read
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionReportLineResponse> readDetailLinePage(
            LocalDate rangeStart,
            LocalDate rangeEnd,
            String openedCursorKey,
            boolean backward,
            TransactionReportRepository.CursorSealer sealer) {

        requirePresentRange(rangeStart, rangeEnd);
        requireOrderedRange(rangeStart, rangeEnd);
        Objects.requireNonNull(sealer, "sealer must not be null");
        if (backward && openedCursorKey == null) {
            throw new IllegalArgumentException(
                    "a backward page is taken from the first row of the window the caller holds, so it"
                            + " cannot be requested without a cursor");
        }
        requireAssemblableRange(rangeStart, rangeEnd);

        PageResponse<TransactionReportRepository.ReportLine> page = backward
                ? reports.readPreviousReportLines(
                        rangeStart, rangeEnd, openedCursorKey, LINE_COUNTER_MODULUS, sealer)
                : reports.readNextReportLines(
                        rangeStart, rangeEnd, openedCursorKey, LINE_COUNTER_MODULUS, sealer);

        List<TransactionReportLineResponse> rendered = new ArrayList<>(page.items().size());
        for (TransactionReportRepository.ReportLine row : page.items()) {
            rendered.add(renderLine(row));
        }
        if (rendered.isEmpty()) {
            // WHY : Assumptions: the two positions are handed over CROSSED, and the crossing is
            //       load-bearing rather than a slip. ofFilteredEmpty names its parameters after the
            //       DIRECTION each one continues -- forward first -- while the envelope's components are
            //       named after the boundary each one is, so the leading boundary of the page read here
            //       is the position a further backward step continues from. The only page that reaches
            //       this arm is the repository's backward-exhausted one, whose single position is sealed
            //       for backward replay, and the crossing is what publishes it as this page's LEADING
            //       boundary where a client will replay it backward. Passing the two straight through
            //       would publish a backward-bound token as the trailing boundary, which the cursor's
            //       own direction binding then refuses on the forward request a client would make of it.
            return PageResponse.ofFilteredEmpty(page.firstKey(), page.lastKey());
        }
        return PageResponse.ofRows(rendered, page.firstKey(), page.lastKey(), page.hasNext());
    }

    /**
     * Renders one resolved report line as the response value the listing operation publishes.
     *
     * <p>Assumptions: the two description fields are narrowed to the widths their bands declare, because
     * the response reports what the report prints and a band truncates on the right. The card is not
     * rendered at all: the eight components are the eight the detail band prints, and a card number is
     * none of them.</p>
     *
     * @param row one resolved report line from the query surface; must not be {@code null}
     * @return the response value for that line, never {@code null}
     */
    private static TransactionReportLineResponse renderLine(
            TransactionReportRepository.ReportLine row) {
        return new TransactionReportLineResponse(
                row.getTransactionId(),
                renderAccountId(row.getAccountId()),
                row.getTypeCd(),
                narrowToBandWidth(row.getTypeDescription(),
                        ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_DESC),
                row.getCategoryCd(),
                narrowToBandWidth(row.getCategoryDescription(),
                        ReportBandLayouts.FIELD_TRAN_REPORT_CAT_DESC),
                row.getSource(),
                row.getAmount());
    }

    /**
     * Refuses a range holding more transactions than this class is willing to serve.
     *
     * <p>Assumptions: the reconciliation runs as part of this guard rather than beside it, because the
     * count it returns is the count the bound is tested against and reading it twice could straddle a
     * concurrent load. The reconciliation is also the migrated form of the three lookup paragraphs of
     * {@code app/cbl/CBTRN03C.cbl}, each of which abends on a miss, so a range that fails it must not be
     * served at all.</p>
     *
     * @param rangeStart the first business date, inclusive; must not be {@code null}
     * @param rangeEnd the last business date, inclusive; must not be {@code null}
     * @throws ClientInputException if the range holds more than {@value #MAX_REPORT_LINES} transactions
     * @throws IllegalStateException if a transaction in the range does not resolve to exactly one of each
     *     dimension
     */
    private void requireAssemblableRange(LocalDate rangeStart, LocalDate rangeEnd) {
        long driving = reconcileDimensionIntegrity(rangeStart, rangeEnd);
        if (driving > MAX_REPORT_LINES) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "endDate",
                    "the requested range holds " + driving
                            + " transactions, above the composable maximum of " + MAX_REPORT_LINES);
        }
    }

    /**
     * Composes the three subtotal bands the report closes with, as response values.
     *
     * <p>Assumptions: the bands are produced by running the very same accumulation engine
     * {@link #generateReport} runs, with no destination attached, so the page figure is the figure of
     * the last page the counter mechanics actually closed and not the residue of a count of detail lines.
     * A second, simpler engine here was the alternative and it is what an earlier revision of this class
     * had: it divided a running count of detail lines by the modulus, which puts a page boundary every
     * twentieth detail line. The shipped oracle
     * {@code tests/golden/reporting/e2e_full_cycle_report.expected} disproves that outright -- its 14
     * pages carry 12, 18, 18, 18, 18, 18, 40, 12, 32, 12, 18, 18, 18 and 10 detail lines -- so the two
     * engines would have reported different page figures for the same range while each looked internally
     * consistent. One engine cannot disagree with itself.</p>
     *
     * <p>Refactoring Rationale: the grouping key on this path is now the CARD, matching
     * {@link #generateReport}, where it was the account identifier. The prose that defended the account
     * key was candid about the divergence -- it said the two "differ for an account holding several"
     * cards, where "this surface reports one closing group figure and the emitted report closes one group
     * per card" -- and then accepted it on the ground that the response type it consumed carried no card
     * component. That is a reason the divergence existed, not a reason to keep it. A caller reading the
     * totals endpoint beside the listing endpoint is reading two views of one report run, and a group
     * subtotal that groups differently from the report is a wrong number rather than a differently-scoped
     * one. The fix removes the constraint instead of accepting it: this method reads the range itself and
     * groups on the per-card fingerprint the query surface projects, so both surfaces run the same engine
     * over the same grouping and the response type stays card-free.</p>
     *
     * <p>Assumptions: the fingerprint is the grouping key rather than a card rendering, for the reason
     * recorded on {@link #generateReport} -- the masked rendering is four digits behind a constant filler,
     * so grouping on it merges two cardholders whose cards share a tail. The reference groups on the whole
     * card number at L181 of {@code app/cbl/CBTRN03C.cbl} against the {@code PIC X(16)} key its L137
     * declares, and the fingerprint is the only column of the reporting relation that is a function of the
     * whole of it.</p>
     *
     * <p>Assumptions: the range is read again here rather than being derived from a list of lines a
     * caller already holds. That is one extra pass over the range, which is the cost, and it buys two
     * things a caller-supplied list could not: the totals cover the WHOLE range rather than whichever
     * page the caller happens to hold, and they are grouped by a key the response type does not
     * publish.</p>
     *
     * <p>Assumptions: the three figures reported are the last page figure emitted, the last group figure
     * emitted and the grand figure, in the emission order of L202, L203 and the card break -- not the
     * live accumulators, which the closing sequence has already reset at L298 and L310. An empty list
     * closes no group and reports three zero figures, and a zero figure is a legitimate value that the
     * total mask renders as blanks rather than as zero-bearing text.</p>
     *
     * @param rangeStart the first business date to cover, inclusive, a {@link LocalDate}; must not be
     *     {@code null}
     * @param rangeEnd the last business date to cover, inclusive, a {@link LocalDate}; must not be
     *     {@code null}
     * @return the three bands in the reference's emission order -- page, then card break, then grand --
     *     each carrying the verbatim label its declaration group states; never {@code null}
     * @throws NullPointerException if either bound is {@code null}
     * @throws ClientInputException if the range is inverted or selects more rows than this class is
     *     willing to assemble
     * @throws IllegalStateException if a transaction in the range does not resolve to exactly one of each
     *     dimension, which is the target's equivalent of the reference abending on an unresolved lookup
     * @throws ArithmeticException if one of the three figures needs more than the nine integer positions
     *     the total masks at L54, L60 and L66 of {@code app/cpy/CVTRA07Y.cpy} provide
     */
    @Transactional(readOnly = true)
    public List<ReportTotalsResponse> composeTotals(LocalDate rangeStart, LocalDate rangeEnd) {
        requirePresentRange(rangeStart, rangeEnd);
        requireOrderedRange(rangeStart, rangeEnd);
        requireAssemblableRange(rangeStart, rangeEnd);

        ReportAccumulators acc = new ReportAccumulators();
        try (Stream<TransactionReportRepository.ReportLine> lines =
                reports.streamReportLines(rangeStart, rangeEnd)) {
            lines.forEach(line -> {
                breakOnGroupChange(acc, line.getCardFingerprint(), null);
                writeTransactionReport(acc, null, null, null, line.getAmount(), null);
            });
        }
        closeReport(acc, null);

        return List.of(
                new ReportTotalsResponse(ReportTotalsResponse.Band.PAGE,
                        ReportTotalsResponse.Band.PAGE.reportLabel(), acc.lastPageTotal),
                new ReportTotalsResponse(ReportTotalsResponse.Band.ACCOUNT,
                        ReportTotalsResponse.Band.ACCOUNT.reportLabel(), acc.lastAccountTotal),
                new ReportTotalsResponse(ReportTotalsResponse.Band.GRAND,
                        ReportTotalsResponse.Band.GRAND.reportLabel(), acc.grandTotal));
    }

    /**
     * Establishes that every transaction in the range resolves to exactly one of each dimension.
     *
     * <p>This stands in for the three lookup paragraphs of {@code app/cbl/CBTRN03C.cbl}, each of which
     * treats a miss as fatal: {@code 1500-A-LOOKUP-XREF} at L484 to L492 displays
     * {@code 'INVALID CARD NUMBER : '} at its L487, {@code 1500-B-LOOKUP-TRANTYPE} at L494 to L502
     * displays {@code 'INVALID TRANSACTION TYPE : '} at its L497, and
     * {@code 1500-C-LOOKUP-TRANCATG} at L504 to L512 displays {@code 'INVALID TRAN CATG KEY : '} at its
     * L507. All three then report the status and abend.</p>
     *
     * <p>Assumptions: the policy chosen is inner joins together with this reconciliation, and not outer
     * joins with a per-row refusal. The reporting queries join the three dimensions inwardly, so a
     * transaction that resolves none of one of them is not returned -- which is the failure mode this
     * method exists to catch, because an inner join on its own would silently drop the row and change
     * three things at once: the records the report holds, the card-break and grand figures, and every
     * page boundary after the dropped row. Each of those three would still look internally consistent,
     * so none of them would be noticed. Outer joins with a refusal on an absent dimension was the
     * alternative and it names the offending row directly; it was not chosen because it makes every
     * report row carry three nullable dimensions in order to detect a state the reference treats as
     * fatal, and the count comparison detects the same state without changing the shape of the query the
     * report actually reads.</p>
     *
     * <p>Assumptions: the comparison is for equality and not for a shortfall, because divergence is
     * possible in both directions. A shortfall means a dimension resolved to nothing. A surplus means a
     * join matched more than one row, which the narrowed card rendering the reporting relation exposes
     * makes possible for two cards sharing their last four digits. Either way the report's figures would
     * not be the reference's, so either way the run stops.</p>
     *
     * @param rangeStart the first business date of the range, inclusive, a {@link LocalDate}; must not be
     *     {@code null}
     * @param rangeEnd the last business date of the range, inclusive, a {@link LocalDate}; must not be
     *     {@code null}
     * @return the number of transactions the date predicate admits, which a caller uses to bound its own
     *     read
     * @throws IllegalStateException if the two counts disagree, naming both counts and the first driving
     *     transaction of the range
     * @throws org.springframework.dao.DataAccessException if the reporting views cannot be read
     */
    private long reconcileDimensionIntegrity(LocalDate rangeStart, LocalDate rangeEnd) {
        LocalDateTime from = firstInstantOf(rangeStart);
        LocalDateTime until = firstInstantAfter(rangeEnd);

        // WHY : Refactoring Rationale: the test is a per-transaction cardinality probe and no longer a
        //       comparison of two aggregate counts. The comparison could be satisfied by a broken range
        //       -- one transaction resolving to no cross-reference row subtracts one from the joined
        //       count while a second matching two adds one, and the two cancel exactly -- so the run
        //       reported success over a report missing one line and carrying a duplicate. The probe
        //       tests each transaction on its own, so nothing cancels, and it returns the offending
        //       identifier rather than leaving the refusal to name a sample row that may not be the one
        //       at fault.
        List<String> unresolved = reports.findTransactionsWithUnresolvedDimensions(
                from, until, Limit.of(MAX_DIAGNOSTIC_ROWS));
        if (!unresolved.isEmpty()) {
            reportUnresolvedDimension(unresolved.get(0));
        }
        return reports.countDrivingRows(from, until);
    }

    /**
     * Refuses the run and names the transaction whose dimensions the joins could not resolve.
     *
     * <p>This is the shared tail of the three lookup paragraphs: {@code 9910-DISPLAY-IO-STATUS} at L633
     * of {@code app/cbl/CBTRN03C.cbl} followed by {@code 9999-ABEND-PROGRAM} at its L626, which moves
     * 999 into its abend code at L629 and calls the abend service at L630.</p>
     *
     * <p>Refactoring Rationale: the identifier this names is now the OFFENDING transaction rather than
     * the first transaction of the range. The previous version read one driving row to have something to
     * quote, and that row was the range's first -- which is the offender only by coincidence. A
     * maintainer following it would inspect a transaction that resolves perfectly well. The cardinality
     * probe returns the identifier of a row that actually failed, so the diagnostic points at the defect.
     * </p>
     *
     * <p>Assumptions: the refusal names the transaction identifier and no card number of any form. The
     * identifier locates the row exactly and carries nothing about a cardholder; the reference's own
     * displays quote the offending key because a job log was the only channel it had, and here the
     * identifier is both sufficient and narrower. The three reference texts are carried into the reason
     * component so that the refusal names the same three conditions the reference names, without
     * asserting which of the three occurred -- a cardinality probe establishes that one did and not
     * which, because a multiple on one dimension inflates the counts of the other two.</p>
     *
     * @param offendingIdentifier the transaction identifier the probe returned; must not be {@code null}
     * @throws IllegalStateException always, which is the purpose of this method
     */
    private void reportUnresolvedDimension(String offendingIdentifier) {
        String identifier = LogSafeText.sanitize(offendingIdentifier);
        String reason = INVALID_CARD_MESSAGE + " / " + INVALID_TYPE_MESSAGE
                + " / " + INVALID_CATEGORY_MESSAGE;
        LOG.error("report range holds a transaction whose dimensions do not resolve to exactly one row"
                + " each; transaction is {}", identifier);

        throw new IllegalStateException(renderedAbend(abendDetail(reason,
                "transaction " + identifier + " does not resolve to exactly one card cross-reference"
                        + " row, one transaction type and one transaction category")));
    }

    /**
     * Refuses a run whose reporting range was never resolved.
     *
     * <p>Refactoring Rationale: this replaces the reference's own outcome for a missing range rather
     * than restating it. {@code 0550-DATEPARM-READ} at L220 to L243 of {@code app/cbl/CBTRN03C.cbl}
     * performs a single read of the parameter record, once, at its L168. When that read reaches the end
     * of the data set the evaluation at its L225 sets result 16 and its L236 moves the end-of-file
     * marker on, so the read loop at its L170 never iterates even once: the six close paragraphs run, the
     * program displays its end-of-execution line at L215 and it terminates having produced no report at
     * all, with no error raised and no diagnostic beyond the absence itself. A scheduled run would
     * publish an empty artifact and report success. This method makes the same condition a refusal the
     * caller has to handle, which is the one behaviour in this class that replaces an existing one
     * rather than differing from it.</p>
     *
     * <p>Assumptions: an absent range and a range that resolves no transaction are different conditions
     * and only the first is refused here. The reference's own reading of an exhausted input still emits
     * its closing bands, which {@link #closeReport} carries across unchanged, and the run summary is
     * what distinguishes such a report from one holding detail lines.</p>
     *
     * @param rangeStart the first business date of the range, a {@link LocalDate}; may be {@code null},
     *     which is the condition this method refuses
     * @param rangeEnd the last business date of the range, a {@link LocalDate}; may be {@code null},
     *     which is the condition this method refuses
     * @throws ClientInputException if either bound is absent, naming the bound so that a caller can see
     *     which of the two it omitted
     */
    private static void requirePresentRange(LocalDate rangeStart, LocalDate rangeEnd) {
        if (rangeStart == null) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "startDate",
                    "startDate is required and no report is produced without it");
        }
        if (rangeEnd == null) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "endDate",
                    "endDate is required and no report is produced without it");
        }
    }

    /**
     * Refuses a range whose end precedes its start.
     *
     * <p>Assumptions: both bounds are admitted by the reference's selection predicate at L47 and L48 of
     * {@code app/jcl/TRANREPT.jcl}, so an inverted range admits nothing and would produce a report a
     * reader would take for an absence of activity rather than for a malformed request. Refusing it names
     * the mistake at the point a caller can still act on it.</p>
     *
     * @param rangeStart the first business date of the range, a {@link LocalDate}; must not be
     *     {@code null}
     * @param rangeEnd the last business date of the range, a {@link LocalDate}; must not be {@code null}
     * @throws ClientInputException if {@code rangeEnd} precedes {@code rangeStart}
     */
    private static void requireOrderedRange(LocalDate rangeStart, LocalDate rangeEnd) {
        if (rangeEnd.isBefore(rangeStart)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "endDate",
                    "endDate must not precede startDate");
        }
    }

    /**
     * Reduces one inclusive business date to the first instant the range admits for it.
     *
     * <p>Assumptions: the bound is passed through the shared timestamp normalisation so that a bound and
     * a stored value are compared at the one resolution the 26-character timestamp contract carries. That
     * is also exactly what the repository does when it converts a business date for its own streaming
     * query, so the two counts this class takes and the rows the stream returns are drawn from one
     * population rather than from two that happen to coincide.</p>
     *
     * @param date the inclusive business date to reduce, a {@link LocalDate}; must not be {@code null}
     * @return the first instant of that date at the contract's resolution; never {@code null}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    private static LocalDateTime firstInstantOf(LocalDate date) {
        return TimestampFormatter.normalize(date.atStartOfDay());
    }

    /**
     * Reduces one inclusive business date to the first instant the range excludes after it.
     *
     * <p>Assumptions: the upper bound is exclusive of the day after, which is how an inclusive date
     * comparison becomes an instant comparison without discarding the last day. The reference compares
     * the leading ten characters of a timestamp at its L174, so a transaction timed at any hour of the
     * last day is inside the range; an exclusive bound placed at the start of the last day instead would
     * silently drop every transaction of it.</p>
     *
     * @param date the inclusive last business date of the range, a {@link LocalDate}; must not be
     *     {@code null}
     * @return the first instant of the following day at the contract's resolution; never {@code null}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    private static LocalDateTime firstInstantAfter(LocalDate date) {
        return TimestampFormatter.normalize(date.plusDays(1).atStartOfDay());
    }

    /**
     * Renders an account identifier at the digit count its report band item declares.
     *
     * <p>Assumptions: the rendering keeps its leading zeros, because L18 of
     * {@code app/cpy/CVTRA07Y.cpy} declares the band item as character data of eleven positions and an
     * unpadded number would left-align its digits and shift the rest of the line. The rendering itself is
     * the mapper's, through {@link CobolEditMask#formatUnsignedDigits}, so this class states neither the
     * padding rule nor the width: the width is read from the band descriptor and the padding is applied
     * where every other declared-width rendering in this module is applied.</p>
     *
     * @param accountId the account identifier the cross-reference supplied, a {@link Long}; must not be
     *     {@code null}
     * @return the identifier as exactly the digits the band item declares, leading zeros included; never
     *     {@code null}
     * @throws NullPointerException if {@code accountId} is {@code null}
     * @throws IllegalArgumentException if the identifier is negative, which the unsigned band item has no
     *     position to carry, or needs more digits than the item declares
     */
    private static String renderAccountId(Long accountId) {
        return CobolEditMask.formatUnsignedDigits(
                accountId, bandWidth(ReportBandLayouts.FIELD_TRAN_REPORT_ACCOUNT_ID));
    }

    /**
     * Narrows a stored description to the width of the report band item it prints into.
     *
     * <p>Assumptions: the narrowing discards characters on the right and pads nothing, which is what an
     * alphanumeric move into a shorter item does at L366 and L368 of {@code app/cbl/CBTRN03C.cbl}. The
     * stored descriptions are fifty characters wide -- {@code app/cpy/CVTRA03Y.cpy} L6 for the type and
     * {@code app/cpy/CVTRA04Y.cpy} L8 for the category -- while their band items are narrower, so the
     * narrowing is the reference's own and not a loss introduced here. Padding would be wrong on this
     * path because a response value carries a value and not a line image; the padding belongs to the
     * mapper that assembles the record.</p>
     *
     * @param description the stored description, a {@code String} that may already be shorter than the
     *     band item; must not be {@code null}
     * @param bandFieldName the name of the band item it prints into, one of the field-name constants
     *     {@link ReportBandLayouts} publishes; must name a field of the detail band
     * @return the leading characters of the description up to the item's declared width, or the whole of
     *     it when it is already shorter; never {@code null}
     * @throws NullPointerException if {@code description} is {@code null}
     * @throws com.carddemo.common.codec.CopybookLayout.LayoutException if the detail band declares no
     *     item of that name
     */
    private static String narrowToBandWidth(String description, String bandFieldName) {
        int width = bandWidth(bandFieldName);
        return description.length() <= width ? description : description.substring(0, width);
    }

    /**
     * Reads the declared width of one detail-band item from the band descriptor.
     *
     * <p>Assumptions: every width this class needs is read from
     * {@link ReportBandLayouts#TRANSACTION_DETAIL_REPORT} rather than restated as a number here, so the
     * declared widths of {@code app/cpy/CVTRA07Y.cpy} have exactly one home in this module. A literal
     * repeated in a service and in a mapper can disagree in one of the two places without anything
     * failing, whereas a single descriptor can only be wrong in both at once and is therefore checkable
     * by one test.</p>
     *
     * @param bandFieldName the name of the band item to measure, one of the field-name constants
     *     {@link ReportBandLayouts} publishes; must name a field of the detail band
     * @return the count of characters that item declares
     * @throws com.carddemo.common.codec.CopybookLayout.LayoutException if the detail band declares no
     *     item of that name, in which case the message names both the band and the requested item
     */
    private static int bandWidth(String bandFieldName) {
        return ReportBandLayouts.TRANSACTION_DETAIL_REPORT.field(bandFieldName).length();
    }

    /**
     * Reads the transaction category code as the integral value its band item is declared to carry.
     *
     * <p>Assumptions: the projection carries the code as characters with its leading zeros intact,
     * because that is the form the response type publishes, while the band item is declared
     * {@code PIC 9(04)} at L24 of {@code app/cpy/CVTRA07Y.cpy} and the mapper therefore takes an
     * integral value and zero-fills it itself. The conversion happens here, once, at the boundary between
     * the two forms rather than at each of them.</p>
     *
     * @param categoryCode the category code as the projection carries it, a {@code String} of digits;
     *     must not be {@code null}
     * @return the same code as an integral value ready for the band item
     * @throws IllegalStateException if the value is not a run of digits, which reports a defect in the
     *     reporting relation rather than a fault of any caller, and which is documented here by hand
     *     because the ruleset's throw analysis cannot see a throw raised from inside a catch block
     */
    private static int categoryCodeOf(String categoryCode) {
        try {
            return Integer.parseInt(categoryCode);
        } catch (NumberFormatException malformed) {
            // Assumptions: the refusal is raised rather than a substitute code printed, because the code
            //       is the join key that already resolved the 50-character description declared at L8 of
            //       app/cpy/CVTRA04Y.cpy; a value that cannot be read as the PIC 9(04) of L24 of
            //       app/cpy/CVTRA07Y.cpy therefore contradicts a row the same query returned, and
            //       printing anything in its place would put a report line on the page for a category
            //       the report cannot name.
            throw new IllegalStateException(renderedAbend(abendDetail(INVALID_CATEGORY_MESSAGE,
                    "category code " + LogSafeText.sanitize(categoryCode)
                            + " is not the run of digits the report band declares")), malformed);
        }
    }

    /**
     * Builds the structured abend detail this class records when it stops a run.
     *
     * <p>Assumptions: the four components are the group item {@code ABEND-DATA} declares in
     * {@code app/cpy/CSMSG02Y.cpy}, and the record they are carried in shortens each to its declared
     * width on construction, so a reason or a message longer than its component is carried as much of it
     * as fits rather than refused. The condition code and the culprit are constant for this class,
     * because the reference has exactly one abend paragraph, at L626 of {@code app/cbl/CBTRN03C.cbl},
     * reached from every failure path it has.</p>
     *
     * @param reason the reason the condition arose, a {@code String}, ordinarily one of the reference's
     *     own verbatim diagnostic texts; may be {@code null}, which the record carries as empty
     * @param message the text intended for whoever reads the failure, a {@code String}; may be
     *     {@code null}, which the record carries as empty
     * @return the detail carrying this class's condition code and culprit alongside the two texts; never
     *     {@code null}
     */
    private static AbendDetail abendDetail(String reason, String message) {
        return new AbendDetail(ABEND_CODE, THIS_PROGRAM, reason, message);
    }

    /**
     * Renders one abend detail into the sentence a refusal carries.
     *
     * <p>Assumptions: the sentence names the condition code, the culprit and then the reason and the
     * message, so that a reader of a failure sees the same three things the reference's job log showed --
     * its abend announcement at L627 of {@code app/cbl/CBTRN03C.cbl}, its condition code from L629 and
     * the diagnostic the failing paragraph displayed before it. Rendering it here rather than at each
     * throw site keeps one shape for every refusal this class raises.</p>
     *
     * @param detail the structured detail to render, an {@link AbendDetail}; must not be {@code null}
     * @return a single-line sentence carrying the condition code, the culprit and whichever of the reason
     *     and the message are present; never {@code null}
     * @throws NullPointerException if {@code detail} is {@code null}
     */
    private static String renderedAbend(AbendDetail detail) {
        // Assumptions: the two texts are joined by concatenation rather than by any accumulating text
        //       helper. This class emits no assembled text at all -- every declared-width composition
        //       belongs to the mapper -- so keeping even a diagnostic sentence to plain concatenation
        //       leaves no text-assembly machinery here for a later reader to mistake for band building.
        String reason = detail.abendReason();
        String message = detail.abendMsg();
        String tail = "";
        if (!reason.isEmpty()) {
            tail = " -- " + reason;
        }
        if (!message.isEmpty()) {
            tail = tail.isEmpty() ? " -- " + message : tail + ": " + message;
        }
        return "abend " + detail.abendCode() + " raised by " + detail.abendCulprit() + tail;
    }
}
