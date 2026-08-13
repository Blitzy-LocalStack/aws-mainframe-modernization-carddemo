package com.carddemo.reporting.service;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.TransactionCategoryBalanceView;
import com.carddemo.reporting.mapper.CategoryBalanceLineLayout;
import com.carddemo.reporting.repository.CategoryBalanceReportRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces the category-balance report the reference's sort-only job prints.
 *
 * <p>Purpose: this is the migrated form of {@code app/jcl/PRTCATBL.jcl}, whose target the migration
 * plan's section 0.4.1.7 assigns to the {@code GenerateReports} state alongside
 * {@code app/jcl/TRANREPT.jcl}. The reference job is two steps: a {@code REPROC} unload of the
 * transaction-category-balance master at lines 29-39, and a sort at lines 43-63 that orders the
 * unloaded file and reformats each record. This service performs the second step; the first is
 * performed by {@code batch-service}'s backup state, which is the chain's single unload point.</p>
 *
 * <p>Assumptions: the report reads the RELATION and not the unloaded flat file, even though the
 * reference's sort reads the file. The unload and the sort are separate jobs in the reference only
 * because each JCL job is written to stand alone -- the same reason the transaction master is unloaded
 * twice per reference night -- so the flat file is an artifact of that structure rather than a
 * contract between the two steps. Reading the relation removes an ordering dependency between two
 * states that would otherwise have to agree on a generation number, and produces the same rows in the
 * same order because the ordering is expressed over the same three key columns.</p>
 *
 * <p>Assumptions: there is NO date range and no selection. {@code app/jcl/PRTCATBL.jcl:44-45} feeds
 * the sort the whole file with no {@code INCLUDE} condition, unlike {@code app/jcl/TRANREPT.jcl:47-48}
 * which does carry one. The report is therefore a full print of current balances rather than a
 * period report, and it takes no business date at all -- which is why this service's method has no
 * date parameter while the transaction report's has two.</p>
 *
 * <p>Assumptions: no header band, no page total and no grand total are emitted. The reference sort
 * declares only {@code SORT FIELDS} and {@code OUTREC}, with no {@code OUTFIL HEADER} and no
 * {@code SECTIONS} or {@code TRAILER}, so every byte of the output is detail lines. The transaction
 * report's banded structure comes from {@code app/cbl/CBTRN03C.cbl}, a program this job does not
 * run.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
@Service
public class CategoryBalanceReportService {

    /** The ordered read of the balances this report prints. */
    private final CategoryBalanceReportRepository balances;

    /**
     * Builds the service over the ordered read it prints from.
     *
     * @param balances the repository supplying balances in account, type then category order; must
     *     not be {@code null}
     * @throws NullPointerException if {@code balances} is {@code null}
     */
    public CategoryBalanceReportService(CategoryBalanceReportRepository balances) {
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
    }

    /**
     * Writes every category balance to the sink as a fixed-length report line.
     *
     * <p>Assumptions: the transaction boundary is declared HERE rather than at the caller, and it is
     * read-only. The repository's walk is {@code MANDATORY}, so it refuses a caller with no
     * transaction; declaring the boundary at the only method that opens the cursor keeps the
     * obligation next to the walk it governs rather than spread over every task that runs a
     * report.</p>
     *
     * <p>Trade-offs: the running total is accumulated and returned even though the reference prints
     * no total line. It is returned rather than printed: nothing writes it to the artifact, so the
     * output stays byte-identical to what the reference's {@code OUTREC} produces, and an operator
     * still gets a figure to compare against the money-parity verification pass. Discarding it was
     * the alternative and would have left the log with a line count and no value to reconcile.</p>
     *
     * @param sink the destination each rendered line is written to; must not be {@code null} and is
     *     not closed by this method, because the caller owns the artifact it wraps
     * @return the summary of what was written, never {@code null}
     * @throws NullPointerException if {@code sink} is {@code null}
     * @throws UncheckedIOException if the sink cannot accept a line, wrapping the underlying failure
     *     so the walk's lambda-free loop can propagate it without changing this method's signature
     * @throws org.springframework.transaction.IllegalTransactionStateException never in production,
     *     because the boundary is declared on this method; it remains reachable for a caller that
     *     invokes the bean's method directly on the target instance rather than through the proxy
     */
    @Transactional(readOnly = true)
    public CategoryBalanceReportSummary generateReport(ReportLineSink sink) {
        Objects.requireNonNull(sink, "sink must not be null");

        long lines = 0L;
        Money total = Money.ZERO;
        try (Stream<TransactionCategoryBalanceView> rows = this.balances
                .findAllByOrderByKeyAccountIdAscKeyTypeCodeAscKeyCategoryCodeAsc()) {
            // WHY : Trade-offs: the stream is adapted to an Iterable so the body is a plain loop.
            //       forEach with a lambda cannot propagate the checked IOException the sink raises,
            //       so it would force a wrap inside the lambda and a second unwrap outside it -- two
            //       conversions where this loop needs one.
            for (TransactionCategoryBalanceView row : (Iterable<TransactionCategoryBalanceView>)
                    rows::iterator) {
                try {
                    sink.write(CategoryBalanceLineLayout.render(row));
                } catch (IOException unwritable) {
                    throw new UncheckedIOException(
                            "could not write the category-balance report line for account "
                                    + row.getKey().accountId(), unwritable);
                }
                lines++;
                total = total.plus(row.getBalance());
            }
        }
        return new CategoryBalanceReportSummary(lines, total);
    }

    /**
     * The destination a rendered report line is written to.
     *
     * <p>Purpose: keep this service independent of where its output goes, so it can be exercised
     * against an in-memory collector with no object store and no container. The nightly path binds it
     * to the same artifact writer the transaction report uses.</p>
     *
     * <p>Alternatives Considered: reusing {@code TransactionReportService.ReportRecordSink}, which has
     * the identical single-method shape. Rejected because it would make this service depend on the
     * transaction report's type for no reason other than the shape matching, and a later change to
     * that report's sink -- a flush signal, a band marker -- would then reach a report that has no
     * bands.</p>
     */
    public interface ReportLineSink {

        /**
         * Accepts one rendered line.
         *
         * @param line the bytes to write, exactly one line of the declared record length; must not be
         *     {@code null}
         * @throws IOException if the line cannot be written
         */
        void write(byte[] line) throws IOException;
    }

    /**
     * What one run of the category-balance report wrote.
     *
     * @param linesWritten the number of detail lines emitted, one per balance; zero when the relation
     *     holds no row, which is a valid report rather than a failure
     * @param total the exact sum of every balance printed, never {@code null}; it is NOT printed by
     *     the report and exists so an operator can reconcile the artifact against the money-parity
     *     verification pass
     */
    public record CategoryBalanceReportSummary(long linesWritten, Money total) {

        /**
         * Refuses a summary that names no total.
         *
         * @param linesWritten as the record component of the same name
         * @param total as the record component of the same name
         * @throws NullPointerException if {@code total} is {@code null}
         * @throws IllegalArgumentException if {@code linesWritten} is negative
         */
        public CategoryBalanceReportSummary {
            Objects.requireNonNull(total, "total must not be null");
            if (linesWritten < 0L) {
                throw new IllegalArgumentException(
                        "linesWritten must not be negative, was " + linesWritten);
            }
        }

        /**
         * Renders this summary without the total it carries.
         *
         * <p>Purpose: a record's generated rendering prints every component, and this one holds an exact
         * money total. {@code docs/architecture/observability.md} L1095 to L1104 names a monetary amount
         * among the values a diagnostic OMITS rather than abbreviates, and it draws no distinction
         * between a single balance and a sum of balances -- a total over a known population discloses
         * the population's money as surely as a row does.</p>
         *
         * <p>Assumptions: the line count is KEPT. It is a bounded count of lines written to an artifact,
         * of the same character as the version counter L1109 to L1112 sanctions, and it is the member an
         * operator reconciling the artifact against the relation it was built from actually reads. The
         * total remains available to that operator through the money-parity verification pass, which is
         * where this record's own component documentation already points them.</p>
         *
         * <p>Trade-offs: a log line written from this summary cannot be used to reconcile the money
         * itself, which is the whole reason the total is computed. That is accepted rather than worked
         * around, because the alternative -- rendering the total at a rounded or truncated scale -- is
         * the abbreviation the rule names, and it would additionally publish a money figure at a scale
         * this migration forbids money to be carried at anywhere else.</p>
         *
         * @return {@code String} rendering on a single line, naming the line count and no money value,
         *     never {@code null}
         */
        @Override
        public String toString() {
            return "CategoryBalanceReportSummary[linesWritten=" + linesWritten + ']';
        }
    }
}
