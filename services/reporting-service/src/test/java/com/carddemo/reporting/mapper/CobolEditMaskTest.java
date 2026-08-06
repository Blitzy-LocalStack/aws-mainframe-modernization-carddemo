package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins every byte-exact numeric edit contract exposed by the reporting mapper boundary.
 *
 * <p>The AAP inventory contains eight rows: seven formatting methods on {@link CobolEditMask}
 * plus the variable-width API string owned by {@link Money}. The class-level regime numbering
 * excludes that API row, so AAP rows six through eight map to formatter regimes five through
 * seven. Keeping that distinction explicit prevents a nonexistent formatter method from being
 * invented for the API representation.</p>
 *
 * <p>Each fixture is created from exact decimal text. Assertions cover polarity, suppression,
 * width, locale independence, and concurrent use without assigning rounding responsibility to
 * this formatter.</p>
 */
class CobolEditMaskTest {

    private Locale originalDefaultLocale;

    /**
     * Installs a hostile default locale before each test so locale-dependent punctuation cannot pass.
     */
    @BeforeEach
    void installHostileDefaultLocale() {
        originalDefaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
    }

    /**
     * Restores and verifies the process default locale so this global fixture cannot leak to sibling tests.
     */
    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale);
        assertThat(Locale.getDefault()).isEqualTo(originalDefaultLocale);
    }

    /**
     * Pins the signed-negative report-detail picture as a separate polarity and width case.
     *
     * <p>The positive leading byte is blank while the negative leading byte is a minus. This case
     * remains separate from totals because merging their expectations would hide that one-byte
     * distinction.</p>
     */
    @Test
    void reportDetailRegimeUsesBlankOrMinusLeadingSign() {
        // WHY: app/cpy/CVTRA07Y.cpy L30 declares the signed-negative detail picture.
        // WHY : Assumptions: services/common-lib/src/main/java/com/carddemo/common/money/Money.java
        //       L390 keeps this test inside the exact money contract; a binary fraction primitive
        //       could reach a neighboring cent before the mapper sees it.
        Money positive = Money.of("1234.56");
        Money negative = Money.of("-1234.56");
        Money zero = Money.of("0.00");

        String positiveResult = CobolEditMask.formatReportDetailAmount(positive);
        String negativeResult = CobolEditMask.formatReportDetailAmount(negative);
        String zeroResult = CobolEditMask.formatReportDetailAmount(zero);

        // WHY : Alternatives Considered: one parameterized sweep was rejected because
        //       app/cpy/CVTRA07Y.cpy L30/L54/L60/L66 and app/cpy/CVTRA05Y.cpy L10 define distinct
        //       sign, suppression, and ceiling semantics that need distinct failure names.
        assertThat(positiveResult).isEqualTo("       1,234.56").hasSize(15);
        assertThat(negativeResult).isEqualTo("-      1,234.56").hasSize(15);
        assertThat(zeroResult).isEqualTo(" ".repeat(15)).hasSize(15);
    }

    /**
     * Pins the fixed-leading-plus totals picture as a separate polarity and width case.
     *
     * <p>Unifying this method with the detail picture would replace the positive plus byte with a
     * blank in the page, account, and grand-total bands. Zero is still wholly blank because the
     * all-suppressed item is decided before polarity.</p>
     */
    @Test
    void reportTotalRegimeAlwaysPrintsNonzeroPolarity() {
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L54, L60, and L66 declare the three totals
        //       pictures.
        String positive = CobolEditMask.formatReportTotalAmount(Money.of("1234.56"));
        String negative = CobolEditMask.formatReportTotalAmount(Money.of("-1234.56"));
        String zero = CobolEditMask.formatReportTotalAmount(Money.of("0.00"));

        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L54/L60/L66 require totals to stay separate
        //       from detail; sharing its positive-sign expectation would place a blank where a
        //       plus belongs in all three bands, changing each byte stream.
        assertThat(positive).isEqualTo("+      1,234.56").hasSize(15);
        assertThat(negative).isEqualTo("-      1,234.56").hasSize(15);
        assertThat(zero).isEqualTo(" ".repeat(15)).hasSize(15);
    }

    /**
     * Pins zero suppression across integer digits and grouping separators as its own mask case.
     *
     * <p>A separator to the left of the first significant digit becomes blank with the suppressed
     * digits around it. The decimal point remains visible only when at least one fractional digit
     * is significant.</p>
     */
    @Test
    void reportDetailSuppressionBlanksLeadingDigitsAndCommas() {
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L30 places two commas inside the Z-edited integer
        //       region.
        String fractional = CobolEditMask.formatReportDetailAmount(Money.of("0.05"));
        String underThousand = CobolEditMask.formatReportDetailAmount(Money.of("999.99"));
        String grouped = CobolEditMask.formatReportDetailAmount(Money.of("1234.56"));

        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L30 makes a separator left of the first
        //       significant digit part of the suppressed region; retaining it for 999.99 adds
        //       punctuation to blank bytes, while 1,234.56 has one significant group and comma.
        assertThat(fractional).isEqualTo("            .05").doesNotContain(",");
        assertThat(underThousand).isEqualTo("         999.99").doesNotContain(",");
        assertThat(grouped).isEqualTo("       1,234.56").containsOnlyOnce(",");
    }

    /**
     * Pins the all-suppressed report zero as fifteen blanks in its own mask case.
     *
     * <p>A default {@code DecimalFormat} family renders visible zero digits, so it cannot serve as
     * this oracle. The detail picture uses {@code Z} in every integer and fractional position,
     * making the blank item distinct from {@code 0.00}, {@code .00}, {@code +0.00}, and
     * {@code 0}.</p>
     */
    @Test
    void reportZeroSuppressesTheEntireFifteenCharacterItem() {
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L30 uses Z in every integer and fractional digit
        //       position.
        String zero = CobolEditMask.formatReportDetailAmount(Money.of("0.00"));

        // WHY : Alternatives Considered: visible forms 0.00, .00, +0.00, and 0 were rejected
        //       because app/cpy/CVTRA07Y.cpy L30 uses Z in the cents too, suppressing the complete
        //       fifteen-character item instead of exposing numeric or punctuation bytes.
        assertThat(zero).isEqualTo(" ".repeat(15)).hasSize(15);
        assertThat(zero).isNotEqualTo("0.00");
        assertThat(zero).isNotEqualTo(".00");
        assertThat(zero).isNotEqualTo("+0.00");
        assertThat(zero).isNotEqualTo("0");
    }

    /**
     * Pins the exact S9(09)V99 ceiling in both polarities as its own mask case.
     *
     * <p>The vector fills all nine integer positions and both fraction positions without entering
     * the formatter's overflow path. A smaller value would leave suppression behavior in the
     * expected string and would not prove the declared ceiling itself.</p>
     */
    @Test
    void reportDetailAcceptsTheNineIntegerDigitCeiling() {
        // WHY : Assumptions: app/cpy/CVTRA05Y.cpy L10 and app/cbl/CBTRN03C.cbl L134-L136 set the
        //       ceiling.
        String positive = CobolEditMask.formatReportDetailAmount(Money.of("999999999.99"));
        String negative = CobolEditMask.formatReportDetailAmount(Money.of("-999999999.99"));

        // WHY : Assumptions: app/cpy/CVTRA05Y.cpy L10 and app/cbl/CBTRN03C.cbl L134-L136 make
        //       999999999.99 fill all nine integer positions and both cents; a smaller fixture
        //       leaves the ceiling unproved and a larger one belongs to the rejection path.
        assertThat(positive).isEqualTo(" 999,999,999.99").hasSize(15);
        assertThat(negative).isEqualTo("-999,999,999.99").hasSize(15);
    }

    /**
     * Pins the unsigned 9(nn) regime with leading zeroes preserved at the declared digit count.
     *
     * <p>This regime is most easily confused with report suppression, where leading zeroes become
     * blanks. A four-digit receiving picture must instead retain all four numeric positions for
     * zero and for a short value.</p>
     */
    @Test
    void unsignedDigitRegimePreservesEveryDeclaredPosition() {
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L24 declares TRAN-REPORT-CAT-CD as PIC 9(04).
        String zero = CobolEditMask.formatUnsignedDigits(0L, 4);
        String seven = CobolEditMask.formatUnsignedDigits(7L, 4);
        String full = CobolEditMask.formatUnsignedDigits(1234L, 4);

        assertThat(zero).isEqualTo("0000").hasSize(4);
        assertThat(seven).isEqualTo("0007").hasSize(4);
        assertThat(full).isEqualTo("1234").hasSize(4);
    }

    /**
     * Pins the DFSORT T-edit regime as twelve unsigned, zero-preserving characters.
     *
     * <p>The OUTREC fields at {@code app/jcl/PRTCATBL.jcl} L53-L56 total 41 columns, while
     * its DCB at L61 declares 40. That source discrepancy does not alter the twelve-character
     * edit mask at L56. The job invokes SORT at L43 and no COBOL program owns this form, so a
     * report or statement sign must never leak into it.</p>
     */
    @Test
    void sortEditedRegimePreservesZeroesAndEmitsNoSign() {
        // WHY : Assumptions: app/jcl/PRTCATBL.jcl L56 declares EDIT=(TTTTTTTTT.TT).
        String zero = CobolEditMask.formatSortEditedBalance(Money.of("0.00"));
        String positive = CobolEditMask.formatSortEditedBalance(Money.of("1234.56"));
        String negative = CobolEditMask.formatSortEditedBalance(Money.of("-1234.56"));

        // WHY : Trade-offs: app/cpy/CVTRA07Y.cpy L30/L54, app/jcl/PRTCATBL.jcl L56,
        //       app/cbl/CORPT00C.cbl L77, and app/cbl/CBSTM03A.CBL L113/L137/L142 stay independent
        //       because substitution can erase .00, widen 12 to 14, move a sign, or add commas.
        assertThat(zero).isEqualTo("000000000.00").hasSize(12);
        assertThat(positive).isEqualTo("000001234.56").hasSize(12);
        assertThat(negative).isEqualTo("000001234.56").hasSize(12);
        assertThat(negative).doesNotContain("+", "-");
    }

    /**
     * Pins the API money row as scale-two plain text with no field-width padding.
     *
     * <p>This row is not a method on {@link CobolEditMask}; it is the exact API representation on
     * {@link Money}. Treating it as a mask would invent a fixed width and would conflate JSON
     * boundary text with report or statement columns.</p>
     */
    @Test
    void apiMoneyStringRegimeKeepsScaleWithoutPadding() {
        // WHY : Assumptions: services/common-lib/src/main/java/com/carddemo/common/money/Money.java
        //       L900 defines this row.
        String zero = Money.of("0.00").toPlainString();
        String nonzero = Money.of("1234.56").toPlainString();

        // WHY : Assumptions: services/reporting-service/src/main/java/com/carddemo/reporting/mapper/
        //       CobolEditMask.java L124-L141 accepts exact two-place values, so a higher-scale
        //       fixture duplicates common-lib's tests and lets the two suites drift.
        assertThat(zero).isEqualTo("0.00").hasSize(4);
        assertThat(nonzero).isEqualTo("1234.56").hasSize(7);
        assertThat(zero.length()).isNotEqualTo(nonzero.length());
    }

    /**
     * Pins the signed zero-filled regime at twelve characters with eight integer digits.
     *
     * <p>This is the row most likely to be confused with the unrelated fourteen-character
     * authorization wire form. The CORPT picture has eight integer positions, always prints a
     * leading sign, and preserves every zero.</p>
     */
    @Test
    void signedZeroFilledRegimeUsesEightIntegerDigits() {
        // WHY : Assumptions: app/cbl/CORPT00C.cbl L77 declares PIC +99999999.99.
        String zero = CobolEditMask.formatSignedZeroFilledAmount(Money.of("0.00"));
        String positive = CobolEditMask.formatSignedZeroFilledAmount(Money.of("12345678.90"));
        String negative = CobolEditMask.formatSignedZeroFilledAmount(Money.of("-12345678.90"));

        // WHY : Assumptions: app/cbl/CORPT00C.cbl L77 contains eight integer positions and
        //       therefore 12 characters; reading the unrelated authorization form's ten positions
        //       creates 14 characters and shifts the remaining card image by two bytes.
        assertThat(zero).isEqualTo("+00000000.00").hasSize(12);
        assertThat(positive).isEqualTo("+12345678.90").hasSize(12);
        assertThat(negative).isEqualTo("-12345678.90").hasSize(12);
        assertThat(positive.length()).isNotEqualTo(14);
    }

    /**
     * Pins the statement balance regime with zero padding and a trailing sign position.
     *
     * <p>Unlike the neighboring statement amount, this picture preserves all nine integer zeroes.
     * Both statement forms forbid grouping commas and place a minus only in the final character,
     * while a non-negative value leaves that final character blank.</p>
     */
    @Test
    void statementBalanceRegimeUsesTrailingSignWithoutGrouping() {
        // WHY : Assumptions: app/cbl/CBSTM03A.CBL L113 declares ST-CURR-BAL as PIC 9(9).99-.
        String zero = CobolEditMask.formatStatementBalance(Money.of("0.00"));
        String positive = CobolEditMask.formatStatementBalance(Money.of("1234.56"));
        String negative = CobolEditMask.formatStatementBalance(Money.of("-1234.56"));

        assertThat(zero).isEqualTo("000000000.00 ").hasSize(13);
        assertThat(positive).isEqualTo("000001234.56 ").hasSize(13).doesNotContain(",");
        assertThat(negative).isEqualTo("000001234.56-").hasSize(13).doesNotContain(",");
        assertThat(positive).endsWith(" ");
        assertThat(negative).endsWith("-");
    }

    /**
     * Pins the statement amount regime with integer suppression and visible cents.
     *
     * <p>This zero is not interchangeable with report zero: only the nine integer positions are
     * {@code Z}, while both fractional positions are {@code 9}. The result therefore retains
     * {@code .00} and a trailing blank sign instead of suppressing all thirteen characters.</p>
     */
    @Test
    void statementAmountRegimeSuppressesOnlyIntegerZeroes() {
        // WHY : Assumptions: app/cbl/CBSTM03A.CBL L137 and L142 declare the two PIC Z(9).99-
        //       fields.
        String zero = CobolEditMask.formatStatementAmount(Money.of("0.00"));
        String positive = CobolEditMask.formatStatementAmount(Money.of("1234.56"));
        String negative = CobolEditMask.formatStatementAmount(Money.of("-1234.56"));
        String reportZero = CobolEditMask.formatReportDetailAmount(Money.of("0.00"));

        assertThat(zero).isEqualTo("         .00 ").hasSize(13).doesNotContain(",");
        assertThat(positive).isEqualTo("     1234.56 ").hasSize(13).doesNotContain(",");
        assertThat(negative).isEqualTo("     1234.56-").hasSize(13).doesNotContain(",");
        assertThat(zero).isNotEqualTo(reportZero);
        assertThat(positive).endsWith(" ");
        assertThat(negative).endsWith("-");
    }

    /**
     * Proves grouped report punctuation is independent of a hostile process default locale.
     *
     * <p>Germany reverses the punctuation roles used by the report picture. Exercising a grouped
     * value under that default is the regression guard that fails if a locale-sensitive formatter
     * is introduced without root-locale symbols.</p>
     */
    @Test
    void groupedReportOutputIgnoresHostileDefaultLocale() {
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L30 declares comma grouping and a period decimal
        //       point.
        String result = CobolEditMask.formatReportDetailAmount(Money.of("1234567.89"));

        // WHY : Alternatives Considered: trusting the build locale was rejected because
        //       app/cpy/CVTRA07Y.cpy L30 declares comma grouping and a period decimal mark, while
        //       Locale.GERMANY swaps those byte classes without changing the field length.
        assertThat(Locale.getDefault()).isEqualTo(Locale.GERMANY);
        assertThat(result).isEqualTo("   1,234,567.89").hasSize(15);
    }

    /**
     * Proves concurrent calls return the same bytes as a single call.
     *
     * <p>The production class rejects a shared mutable {@code DecimalFormat} at its lines 110-122.
     * This test exercises one formatting method from several worker threads so a future stateful
     * replacement cannot satisfy the sequential examples while corrupting concurrent bands.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting for workers
     * @throws ExecutionException if a worker fails while formatting its amount
     */
    @Test
    void concurrentFormattingIsByteIdentical()
            throws InterruptedException, ExecutionException {
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy L54 declares the totals byte pattern exercised
        //       concurrently.
        Money amount = Money.of("999999999.99");
        String expected = CobolEditMask.formatReportTotalAmount(amount);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            tasks.add(() -> CobolEditMask.formatReportTotalAmount(amount));
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = executor.invokeAll(tasks);

            // WHY : Trade-offs: services/reporting-service/src/main/java/com/carddemo/reporting/
            //       mapper/CobolEditMask.java L110-L122 rejects locking and thread-local alternatives;
            //       128 calls over eight workers force 16 reuses each, exposing shared mutation.
            assertThat(results).hasSize(128);
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo(expected);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
