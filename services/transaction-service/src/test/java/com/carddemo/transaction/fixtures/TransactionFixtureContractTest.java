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
     * Asserts that the category-balance rows carry the composed key and their declared balance.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the category-balance rows carry the composed key and their declared balance")
    void theCategoryBalanceRowsCarryTheComposedKey() throws IOException {
        for (String scenario : List.of("happy_path", "boundary_exact_limit", "boundary_expiry_equal",
                "empty_input", "reject_100_card_missing", "reject_101_acct_missing",
                "reject_102_overlimit", "reject_103_expired", "reject_109_rewrite_invalid_key")) {
            Map<String, Object> row = first(scenario, "tcatbal.txt", "TCATBAL", TCATBAL_RECLEN);

            // WHY : Assumptions: the key is the account plus the transaction type plus the
            //       category, and every populated scenario composes the same one, so a scenario
            //       whose key drifted would take the create branch at app/cbl/CBTRN02C.cbl L503
            //       instead of the update branch at L526 and would assert the opposite of what its
            //       README claims. The key is therefore asserted uniformly, and only the balance
            //       varies by scenario below.
            assertThat(row.get("TRANCAT-ACCT-ID")).as("%s account", scenario).hasToString("7");
            assertThat(row.get("TRANCAT-TYPE-CD")).as("%s type", scenario).isEqualTo("01");
            assertThat(row.get("TRANCAT-CD")).as("%s category", scenario).hasToString("1");

            // WHY : Refactoring Rationale: this assertion required 100.00 for all nine populated
            //       scenarios, which contradicted the reference tree it mirrors. Under
            //       tests/fixtures/posting/ the balance is 0.00 for boundary_exact_limit and for
            //       every other populated scenario, and 100.00 only for happy_path; the uniform
            //       expectation here was therefore pinning eight fixtures to the one value the
            //       oracle does NOT give them. boundary_exact_limit has been aligned to its oracle
            //       byte for byte, so its expectation moves to 0.00 while the siblings -- which are
            //       authored elsewhere and still carry the template value -- keep theirs. Expressing
            //       the expectation per scenario rather than as one constant is what lets each
            //       fixture be reconciled with its oracle independently instead of forcing all nine
            //       to move together.
            //       Trade-offs: a per-scenario expectation is marginally less terse than a single
            //       constant, and it no longer guarantees that a consumer asserting balance
            //       arithmetic has one prior value across the tree. That guarantee is given up
            //       deliberately: it was only ever true because the fixtures had been normalised
            //       away from the oracle, and byte-parity with the reference tree is the stronger
            //       property because it is the parity oracle the migration is verified against.
            BigDecimal expectedBalance = "boundary_exact_limit".equals(scenario)
                    ? new BigDecimal("0.00")
                    : new BigDecimal("100.00");
            assertThat(row.get("TRAN-CAT-BAL")).as("%s balance", scenario)
                    .isEqualTo(expectedBalance);
        }
    }

    /**
     * Asserts that the over-limit boundary's category balance is the zero the oracle declares.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the over-limit boundary's category balance is the zero its oracle declares")
    void theOverLimitBoundaryBalanceIsZero() throws IOException {
        // WHY : Assumptions: the zero is the mechanism of this scenario rather than an incidental
        //       value. The trial balance at app/cbl/CBTRN02C.cbl L403-L405 is the cycle credit less
        //       the cycle debit plus the transaction amount, and with the seed account's two cycle
        //       totals at zero it reduces to the amount alone -- which is what lets 2065.00 land
        //       exactly on the 2065.00 credit limit tested at L407. Holding the category balance at
        //       zero as well keeps the posted result provably 0.00 + 2065.00 and leaves the
        //       arithmetic of the scenario with no second contributing term to reason about.
        //       Asserting it in its own test, rather than only inside the loop above, means the
        //       value cannot be quietly folded back into a uniform expectation without a failure.
        Map<String, Object> row = first("boundary_exact_limit", "tcatbal.txt", "TCATBAL",
                TCATBAL_RECLEN);
        assertThat(row.get("TRAN-CAT-BAL")).isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(((BigDecimal) row.get("TRAN-CAT-BAL")).scale()).isEqualTo(2);

        // WHY : Assumptions: the sign is carried by the low-order byte under the zoned-decimal
        //       overpunch table, so a positive zero is the byte `{` and not the digit `0`. Asserting
        //       the raw byte alongside the decoded value is what distinguishes a correctly signed
        //       zero from an unsigned field that merely happens to decode to the same number.
        byte[] raw = bytes("boundary_exact_limit", "tcatbal.txt");
        assertThat(new String(raw, 17, 11, StandardCharsets.US_ASCII)).isEqualTo("0000000000{");
        assertThat(raw[27]).isEqualTo((byte) 0x7B);

        // WHY : Assumptions: the twenty-two trailing FILLER bytes of this record are ASCII zeros
        //       rather than the spaces every other FILLER in the tree uses, which is the seed's own
        //       convention for this layout. They are asserted here because they sit immediately
        //       after the balance: a balance edited by hand that lost or gained one byte would push
        //       into this run and be visible as a FILLER failure rather than as a silent shift.
        assertThat(new String(raw, 28, 22, StandardCharsets.US_ASCII)).isEqualTo("0".repeat(22));
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
        //       Assumptions: that count is a statement about the moment of that change and must not
        //       be read as a claim that every other scenario still matches the template today.
        //       boundary_exact_limit has since been aligned to its own oracle at 0.00, so this
        //       byte-identity holds for the expiry pair specifically and is asserted only for it.
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
        // WHY : Assumptions: these are the two fixtures in the tree carrying a NEGATIVE money
        //       value, and both are asserted because the default sign convention silently misreads
        //       a sign-overpunched negative as a positive with a letter in the low-order digit. A
        //       decoder configured wrongly would return 9190 or fail, not -919.00, so these
        //       assertions cover the whole class of sign-handling regressions for this module.
        //       Refactoring Rationale: this test formerly read one fixture and its comment claimed
        //       that fixture was the only negative one in the tree. reject_100_card_missing then
        //       became the second, because it withholds the transaction its own card lookup
        //       rejects and therefore carries seed row 2 -- the row whose amount is negative. The
        //       loop was widened rather than the sentence rewritten, so the second fixture is
        //       actually verified instead of merely described.
        for (String scenario : List.of("reject_101_acct_missing", "reject_100_card_missing")) {
            Map<String, Object> posted = first(scenario, "transact.txt", "TRAN", TRAN_RECLEN);

            assertThat(posted.get("TRAN-AMT")).as("%s amount", scenario)
                    .isInstanceOf(BigDecimal.class).isEqualTo(new BigDecimal("-919.00"));
            assertThat(((BigDecimal) posted.get("TRAN-AMT")).signum())
                    .as("%s sign", scenario).isNegative();
            assertThat(((BigDecimal) posted.get("TRAN-AMT")).scale())
                    .as("%s scale", scenario).isEqualTo(2);
        }
    }

    /**
     * Asserts that the missing-card scenario withholds the transaction its lookup rejects.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the missing-card scenario withholds the transaction its lookup rejects")
    void theMissingCardScenarioWithholdsTheRejectedTransaction() throws IOException {
        // WHY : Assumptions: the absence of this identifier is half the scenario's expected
        //       outcome. In app/cbl/CBTRN02C.cbl line 211 reaches posting on line 212 only when
        //       the reason is still zero, so a reason-100 reject never reaches the transaction
        //       write, and the posted master must therefore hold no row for the rejected feed
        //       record. Trade-offs: asserting on the identifier rather than on a row count is what
        //       makes the check binary -- transaction_id is the primary key of the table these
        //       bytes load, so a pre-loaded row carrying it would be indistinguishable from one an
        //       insert had added, and an insert that did occur would fail on the key rather than
        //       on the rule under test.
        String rejected = first("reject_100_card_missing", "dailytran.txt", "DALYTRAN", TRAN_RECLEN)
                .get("DALYTRAN-ID").toString();
        String prior = first("reject_100_card_missing", "transact.txt", "TRAN", TRAN_RECLEN)
                .get("TRAN-ID").toString();

        assertThat(rejected).isEqualTo("0000000000683580");
        assertThat(prior).isNotEqualTo(rejected).isEqualTo("0000000001774260");
        assertThat(new String(bytes("reject_100_card_missing", "transact.txt"),
                StandardCharsets.US_ASCII)).doesNotContain(rejected);

        // WHY : Assumptions: the prior row still has to EXIST for "unchanged" to be assertable at
        //       all. A table asserted to be unchanged needs contents, because a query over an
        //       empty table returns nothing whether or not a write was suppressed.
        assertThat(bytes("reject_100_card_missing", "transact.txt")).hasSize(TRAN_RECLEN + 1);
    }
}
