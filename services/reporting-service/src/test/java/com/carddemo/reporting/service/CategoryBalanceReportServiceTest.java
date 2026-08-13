package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.TransactionCategoryBalanceView;
import com.carddemo.reporting.repository.CategoryBalanceReportRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the category-balance report's generation pass: what it writes, in what order, what it totals and
 * what it does with a sink that fails mid-run.
 *
 * <p>Purpose: the reference for this report is a DFSORT step, {@code app/jcl/PRTCATBL.jcl:52-56}, whose
 * whole content is one sort order and one operand list. The line composition is pinned by
 * {@code CategoryBalanceLineLayoutTest}; what remains unproven and is proven here is that the pass
 * emits ONE line per row in the order the repository returns, that it closes the cursor, that it
 * accumulates the total exactly, and that a write failure stops the run rather than producing a short
 * artifact reported as complete.
 *
 * <p>Assumptions: the repository is a mock returning a stream over a fixed list, and the ORDER is
 * therefore the list's rather than a database's. That is deliberate: the ordering contract lives in the
 * repository's method name and is asserted where a real database can observe it; what this class
 * asserts is that the service preserves whatever order it is handed and does not re-sort, which a mock
 * establishes and a database would obscure.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class CategoryBalanceReportServiceTest {

    /** The repository the service reads its ordered balances from. */
    private final CategoryBalanceReportRepository balances =
            mock(CategoryBalanceReportRepository.class);

    /** The service under test. */
    private final CategoryBalanceReportService service = new CategoryBalanceReportService(balances);

    // WHY : Assumptions: the lines are asserted in SEQUENCE and not as a set, because the reference's
    //       output is a sorted file and its order is the only thing the sort step contributes beyond
    //       the operand list. A set comparison would pass over an artifact whose rows were shuffled,
    //       which is precisely the artifact a reader would use the sort order to navigate.
    /**
     * Asserts one line is written per row, in the order the repository returned them.
     */
    @Test
    @DisplayName("one line is written per balance, in the order the repository returned")
    void oneLineIsWrittenPerBalanceInOrder() {
        arrange(
                row(11L, "01", "0001", Money.of("10.00")),
                row(11L, "01", "0002", Money.of("20.00")),
                row(22L, "02", "0003", Money.of("30.00")));
        List<String> written = new ArrayList<>();

        CategoryBalanceReportService.CategoryBalanceReportSummary summary =
                service.generateReport(line -> written.add(decode(line)));

        assertThat(summary.linesWritten()).isEqualTo(3L);
        assertThat(written).containsExactly(
                "00000000011 01 0001 000000010.00        ",
                "00000000011 01 0002 000000020.00        ",
                "00000000022 02 0003 000000030.00        ");
    }

    // WHY : Assumptions: the total is asserted over a mix of signs, because the column is
    //       NUMERIC(11,2) from TRAN-CAT-BAL PIC S9(09)V99 -- a SIGNED picture -- so a credit balance
    //       is representable and a total that summed magnitudes would be wrong in exactly the case an
    //       operator reconciling against the money-parity pass would notice last.
    /**
     * Asserts the total is the exact signed sum at scale two.
     */
    @Test
    @DisplayName("the total is the exact signed sum of every balance printed")
    void theTotalIsTheExactSignedSum() {
        arrange(
                row(11L, "01", "0001", Money.of("100.55")),
                row(11L, "01", "0002", Money.of("-40.05")),
                row(22L, "02", "0003", Money.of("0.01")));

        CategoryBalanceReportService.CategoryBalanceReportSummary summary =
                service.generateReport(line -> { });

        assertThat(summary.total())
                .as("100.55 - 40.05 + 0.01, exact at scale two rather than a floating sum")
                .isEqualTo(Money.of("60.51"));
    }

    // WHY : Assumptions: an empty relation is asserted to produce a VALID empty report rather than a
    //       failure. app/jcl/PRTCATBL.jcl gates nothing on record count -- its sort step has no
    //       INCLUDE and no condition code test -- so a night with no category balances produces an
    //       empty dataset there, and a target that failed the state instead would stop a chain the
    //       reference completes.
    /**
     * Asserts an empty relation yields a zero-line report with a zero total.
     */
    @Test
    @DisplayName("an empty relation yields a zero-line report, not a failure")
    void anEmptyRelationYieldsAnEmptyReport() {
        arrange();
        List<String> written = new ArrayList<>();

        CategoryBalanceReportService.CategoryBalanceReportSummary summary =
                service.generateReport(line -> written.add(decode(line)));

        assertThat(written).isEmpty();
        assertThat(summary.linesWritten()).isZero();
        assertThat(summary.total()).isEqualTo(Money.ZERO);
    }

    // WHY : Assumptions: the failure is asserted to PROPAGATE and to name the row it failed on. A pass
    //       that swallowed it would return a line count lower than the relation holds while reporting
    //       success, and an operator comparing that count against the row count would be the only
    //       control left -- so the artifact would read as complete until someone counted.
    /**
     * Asserts a sink failure stops the run and names the row it failed on.
     */
    @Test
    @DisplayName("a sink failure stops the run and names the account it failed on")
    void aSinkFailureStopsTheRun() {
        arrange(
                row(11L, "01", "0001", Money.of("10.00")),
                row(4242L, "02", "0002", Money.of("20.00")));
        List<String> written = new ArrayList<>();

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> service.generateReport(line -> {
                    if (!written.isEmpty()) {
                        throw new IOException("the artifact rejected the line");
                    }
                    written.add(decode(line));
                }))
                .withMessageContaining("4242")
                .withCauseInstanceOf(IOException.class);

        assertThat(written).hasSize(1);
    }

    // WHY : Assumptions: the cursor is asserted CLOSED, which a streaming read makes a correctness
    //       property rather than hygiene. The repository declares a fetch size of 100, so the read is
    //       a server-side cursor held open for the walk; a pass that left it open would leak one
    //       connection per report and the leak would only surface once the pool was exhausted.
    /**
     * Asserts the streaming read is closed even when the pass fails part-way.
     */
    @Test
    @DisplayName("the streaming read is closed, including when the pass fails part-way")
    void theStreamingReadIsClosed() {
        List<Boolean> closed = new ArrayList<>();
        when(balances.findAllByOrderByKeyAccountIdAscKeyTypeCodeAscKeyCategoryCodeAsc())
                .thenReturn(Stream.of(row(11L, "01", "0001", Money.of("10.00")))
                        .onClose(() -> closed.add(Boolean.TRUE)));

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> service.generateReport(line -> {
                    throw new IOException("the artifact rejected the line");
                }));

        assertThat(closed)
                .as("the try-with-resources closes the cursor on the exception path too")
                .containsExactly(Boolean.TRUE);
    }

    /**
     * Asserts the service refuses to be built without a repository.
     */
    @Test
    @DisplayName("the service refuses construction without a repository")
    void theServiceRefusesConstructionWithoutARepository() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new CategoryBalanceReportService(null));
    }

    /**
     * Asserts a null sink is refused before the cursor is opened.
     */
    @Test
    @DisplayName("a null sink is refused before the read is opened")
    void aNullSinkIsRefused() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.generateReport(null));
    }

    /**
     * Stubs the repository to return the supplied rows in order.
     *
     * @param rows the rows to return, in the order the repository would return them
     */
    private void arrange(TransactionCategoryBalanceView... rows) {
        when(balances.findAllByOrderByKeyAccountIdAscKeyTypeCodeAscKeyCategoryCodeAsc())
                .thenReturn(Stream.of(rows));
    }

    /**
     * Builds one projected balance row without a database.
     *
     * @param accountId the account identifier
     * @param typeCode the transaction type code
     * @param categoryCode the transaction category code
     * @param balance the category balance
     * @return the row
     */
    private static TransactionCategoryBalanceView row(
            Long accountId, String typeCode, String categoryCode, Money balance) {
        return new TransactionCategoryBalanceView(accountId, typeCode, categoryCode, balance);
    }

    /**
     * Decodes a rendered line for readable comparison.
     *
     * @param line the rendered bytes
     * @return the line as text
     */
    private static String decode(byte[] line) {
        return new String(line, StandardCharsets.US_ASCII);
    }
}
