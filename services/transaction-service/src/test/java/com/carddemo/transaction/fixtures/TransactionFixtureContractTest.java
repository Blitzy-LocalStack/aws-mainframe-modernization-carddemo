package com.carddemo.transaction.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the nine posting scenarios to the byte values their per-scenario READMEs state.
 *
 * <p>Assumptions: twenty-seven record files across nine scenario directories had no executable
 * consumer. The module's existing tests cover DTOs, the entity mapping, the repository and the
 * published contract, but none of them opens a fixture, so a boundary value could move by one cent or
 * one day and every test would stay green -- which is precisely the change these fixtures exist to
 * make detectable.</p>
 */
class TransactionFixtureContractTest {

    /** The classpath prefix every scenario resolves under. */
    private static final String ROOT = "fixtures/";

    /** The declared record length of the daily-transaction and posted-transaction records. */
    private static final int TRAN_RECLEN = 350;

    /** The declared record length of the transaction-category-balance record. */
    private static final int TCATBAL_RECLEN = 50;

    /** The card number every scenario uses except the missing-card one. */
    private static final String SEED_CARD = "4859452612877065";

    /**
     * Reads one scenario file from the test classpath.
     *
     * @param scenario the scenario directory name
     * @param file the file name within it
     * @return the file's exact bytes
     * @throws IllegalStateException if the resource is absent from the classpath
     * @throws IOException if the stream cannot be read
     */
    private static byte[] bytes(String scenario, String file) throws IOException {
        String path = ROOT + scenario + "/" + file;
        try (InputStream in = TransactionFixtureContractTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture absent from the test classpath: " + path);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Decodes the first record of a scenario file.
     *
     * @param scenario the scenario directory name
     * @param file the file name within it
     * @param layout the registry name of the layout to decode against
     * @param reclen the declared record width
     * @return the decoded field map of record one
     * @throws IOException if the fixture cannot be read
     */
    private static Map<String, Object> first(String scenario, String file, String layout, int reclen)
            throws IOException {
        byte[] raw = bytes(scenario, file);
        assertThat(raw).hasSize(reclen + 1);
        byte[] rec = new byte[reclen];
        System.arraycopy(raw, 0, rec, 0, reclen);
        return FixedWidthCodec.decodeRecord(rec, CopybookLayout.layout(layout),
                StandardCharsets.US_ASCII);
    }

    /**
     * Supplies every scenario directory name in this tree.
     *
     * @return one argument set per scenario directory
     */
    private static Stream<Arguments> scenarios() {
        return Stream.of("happy_path", "boundary_exact_limit", "boundary_expiry_equal", "empty_input",
                "reject_100_card_missing", "reject_101_acct_missing", "reject_102_overlimit",
                "reject_103_expired", "reject_109_rewrite_invalid_key", "zero_balance")
                .map(Arguments::of);
    }

    /**
     * Asserts that every scenario carries its three files at their declared widths.
     *
     * @param scenario the scenario directory name
     * @throws IOException if a fixture cannot be read
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("every scenario carries its three files at their declared widths")
    void everyScenarioCarriesItsThreeFiles(String scenario) throws IOException {
        // WHY : Assumptions: a scenario is a TRIPLE, and the folder convention is that a pair agrees
        //       byte for byte with the template except in the field that moves. Asserting all three
        //       files exist and are whole records -- rather than only the one a scenario is named
        //       for -- is what catches a scenario that lost a file or gained a partial record.
        for (String file : List.of("dailytran.txt", "transact.txt")) {
            byte[] raw = bytes(scenario, file);
            assertThat(raw.length % (TRAN_RECLEN + 1))
                    .as("%s/%s is not whole %d-byte records", scenario, file, TRAN_RECLEN).isZero();
            assertThat(new String(raw, StandardCharsets.US_ASCII)).doesNotContain("\r");
        }
        byte[] balance = bytes(scenario, "tcatbal.txt");
        assertThat(balance.length % (TCATBAL_RECLEN + 1))
                .as("%s/tcatbal.txt is not whole %d-byte records", scenario, TCATBAL_RECLEN)
                .isZero();
    }

    /**
     * Asserts that the over-limit boundary is a one-cent pair straddling the credit limit.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the over-limit boundary is a one-cent pair straddling the credit limit")
    void theOverLimitBoundaryIsAOneCentPair() throws IOException {
        Object atLimit = first("boundary_exact_limit", "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                .get("DALYTRAN-AMT");
        Object overLimit = first("reject_102_overlimit", "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                .get("DALYTRAN-AMT");

        // WHY : Assumptions: the documented rule is that a balance exactly AT the credit limit must
        //       post and one cent over must reject, so the two fixtures must differ by exactly one
        //       cent and the lower one must equal the seed account's limit of 2065.00. Asserting the
        //       difference rather than the two values separately is what makes an edit to either
        //       file fail: moving both by the same amount would keep two independent assertions
        //       green while destroying the boundary.
        assertThat(atLimit).isEqualTo(new BigDecimal("2065.00"));
        assertThat(overLimit).isEqualTo(new BigDecimal("2065.01"));
        assertThat(((BigDecimal) overLimit).subtract((BigDecimal) atLimit))
                .isEqualByComparingTo(new BigDecimal("0.01"));
    }

    /**
     * Asserts that the expiration boundary is a one-day pair straddling the expiry date.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the expiration boundary is a one-day pair straddling the expiry date")
    void theExpirationBoundaryIsAOneDayPair() throws IOException {
        String equalDay = first("boundary_expiry_equal", "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                .get("DALYTRAN-ORIG-TS").toString();
        String pastDay = first("reject_103_expired", "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                .get("DALYTRAN-ORIG-TS").toString();

        // WHY : Assumptions: only the first ten characters take part in the comparison, and the
        //       account's expiry is 2024-12-13, so a transaction dated exactly that must post and
        //       one dated a day later must reject. The time-of-day tail is deliberately identical in
        //       both files, so the single day is provably the only difference and the boundary
        //       cannot be passing for some other reason.
        assertThat(equalDay).startsWith("2024-12-13");
        assertThat(pastDay).startsWith("2024-12-14");
        assertThat(equalDay.substring(10)).isEqualTo(pastDay.substring(10))
                .isEqualTo(" 19:27:53.000000");
    }

    /**
     * Asserts that the missing-card scenario uses a card absent from the seed cross-reference.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the missing-card scenario uses a card absent from the seed cross-reference")
    void theMissingCardScenarioUsesAnAbsentCard() throws IOException {
        String absent = first("reject_100_card_missing", "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                .get("DALYTRAN-CARD-NUM").toString();

        // WHY : Assumptions: the value is all nines rather than a random-looking number, because a
        //       plausible-looking card number could one day collide with a seed row and turn a
        //       reject scenario into a posting scenario silently. Sixteen nines cannot occur in the
        //       seed and reads as deliberate to anyone opening the file.
        assertThat(absent).isEqualTo("9".repeat(16)).isNotEqualTo(SEED_CARD);
    }

    /**
     * Asserts that every other scenario uses the one seed card its READMEs cite.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("every other scenario uses the one seed card its READMEs cite")
    void everyOtherScenarioUsesTheSeedCard() throws IOException {
        for (String scenario : List.of("happy_path", "boundary_exact_limit", "boundary_expiry_equal",
                "reject_101_acct_missing", "reject_102_overlimit", "reject_103_expired",
                "reject_109_rewrite_invalid_key", "zero_balance")) {
            assertThat(first(scenario, "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                    .get("DALYTRAN-CARD-NUM"))
                    .as("%s should use the seed card", scenario)
                    .isEqualTo(SEED_CARD);
        }
    }

    /**
     * Asserts that the two intentionally empty files are exactly zero bytes.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the two intentionally empty files are exactly zero bytes")
    void theTwoEmptyFilesAreZeroBytes() throws IOException {
        // WHY : Assumptions: the two emptinesses mean different things and both matter. An empty
        //       dailytran is an empty INPUT, so the run has nothing to post; an empty tcatbal means
        //       no row exists on the composed key, which is what sends the posting program down its
        //       create branch rather than its update branch. A file that gained a trailing newline
        //       would become a one-byte file holding one zero-length record and would fail parsing
        //       instead of exercising either path.
        assertThat(bytes("empty_input", "dailytran.txt")).isEmpty();
        assertThat(bytes("zero_balance", "tcatbal.txt")).isEmpty();

        // WHY : Assumptions: the OTHER two files in each of those scenarios are populated on
        //       purpose, so the emptiness is provably the single varying property rather than an
        //       incompletely authored directory.
        assertThat(bytes("empty_input", "transact.txt")).hasSize(TRAN_RECLEN + 1);
        assertThat(bytes("empty_input", "tcatbal.txt")).hasSize(TCATBAL_RECLEN + 1);
        assertThat(bytes("zero_balance", "dailytran.txt")).hasSize(TRAN_RECLEN + 1);
    }

    /**
     * Asserts that the category-balance rows carry the composed key and the template balance.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the category-balance rows carry the composed key and the template balance")
    void theCategoryBalanceRowsCarryTheComposedKey() throws IOException {
        for (String scenario : List.of("happy_path", "boundary_exact_limit", "boundary_expiry_equal",
                "empty_input", "reject_100_card_missing", "reject_101_acct_missing",
                "reject_102_overlimit", "reject_103_expired", "reject_109_rewrite_invalid_key")) {
            Map<String, Object> row = first(scenario, "tcatbal.txt", "TCATBAL", TCATBAL_RECLEN);

            // WHY : Assumptions: the key is the account plus the transaction type plus the
            //       category, and every populated scenario composes the same one, so a scenario
            //       whose key drifted would take the create branch instead of the update branch and
            //       would assert the opposite of what its README claims. The balance is held
            //       identical across scenarios so that a consumer asserting balance arithmetic has
            //       one prior value rather than nine.
            assertThat(row.get("TRANCAT-ACCT-ID")).as("%s account", scenario).hasToString("7");
            assertThat(row.get("TRANCAT-TYPE-CD")).as("%s type", scenario).isEqualTo("01");
            assertThat(row.get("TRANCAT-CD")).as("%s category", scenario).hasToString("1");
            assertThat(row.get("TRAN-CAT-BAL")).as("%s balance", scenario)
                    .isEqualTo(new BigDecimal("100.00"));
        }
    }

    /**
     * Asserts that the expiry-boundary balance row is byte-identical to the template's.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the expiry-boundary balance row is byte-identical to the template's")
    void theExpiryBoundaryBalanceRowMatchesTheTemplate() throws IOException {
        // WHY : Refactoring Rationale: this file previously held a balance of 0.00 while its README
        //       stated it was byte-identical to the template's 100.00, and eight of the nine
        //       populated siblings carried 100.00. The one differing byte was at position 24, which
        //       is the digit the README itself notes distinguishes 100.00 from 10.00. The fixture
        //       was corrected rather than the sentence, because the folder convention requires a
        //       scenario to differ from the template only in the field that moves -- here the date --
        //       and the balance is immaterial to the expiration gate, which reads the account record.
        assertThat(bytes("boundary_expiry_equal", "tcatbal.txt"))
                .isEqualTo(bytes("happy_path", "tcatbal.txt"));
    }

    /**
     * Asserts that a daily record is unposted and a posted record carries a processing timestamp.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("a daily record is unposted and a posted record carries a processing timestamp")
    void dailyRecordsAreUnpostedAndPostedRecordsAreNot() throws IOException {
        Map<String, Object> daily = first("happy_path", "dailytran.txt", "DALYTRAN", TRAN_RECLEN);
        Map<String, Object> posted = first("happy_path", "transact.txt", "TRAN", TRAN_RECLEN);

        // WHY : Assumptions: the processing timestamp is what distinguishes an input record from an
        //       output record, and the daily record's span is 26 blanks in the file because nothing
        //       has posted it yet. It decodes to the EMPTY STRING rather than to 26 spaces, because
        //       a 26-byte timestamp is a recognised leaf kind that the codec normalises, whereas an
        //       ordinary text field keeps its padding -- the embossed name in the card fixtures
        //       decodes to its full 50 characters. The two behaviours are asserted here side by side
        //       because the asymmetry is invisible in the copybook, both fields being PIC X(n).
        assertThat(daily.get("DALYTRAN-PROC-TS")).isEqualTo("");
        assertThat(daily).containsKey("DALYTRAN-PROC-TS");
        assertThat(daily.get("DALYTRAN-DESC").toString()).hasSize(100).endsWith(" ");
        assertThat(posted.get("TRAN-PROC-TS")).isEqualTo("2022-07-18 00:00:00.000000");
        assertThat(daily.get("DALYTRAN-ORIG-TS")).isEqualTo(posted.get("TRAN-ORIG-TS"));
        assertThat(daily.get("DALYTRAN-ID")).isEqualTo(posted.get("TRAN-ID"));
    }

    /**
     * Asserts that a negative amount is carried through its sign overpunch, not as text.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("a negative amount is carried through its sign overpunch, not as text")
    void aNegativeAmountIsCarriedThroughItsSignOverpunch() throws IOException {
        Map<String, Object> posted = first("reject_101_acct_missing", "transact.txt", "TRAN",
                TRAN_RECLEN);

        // WHY : Assumptions: this is the one fixture in the tree carrying a NEGATIVE money value,
        //       and it is asserted because the default sign convention silently misreads a
        //       sign-overpunched negative as a positive with a letter in the low-order digit. A
        //       decoder configured wrongly would return 9190 or fail, not -919.00, so this single
        //       assertion covers the whole class of sign-handling regressions for this module.
        assertThat(posted.get("TRAN-AMT")).isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("-919.00"));
        assertThat(((BigDecimal) posted.get("TRAN-AMT")).signum()).isNegative();
        assertThat(((BigDecimal) posted.get("TRAN-AMT")).scale()).isEqualTo(2);
    }
}
