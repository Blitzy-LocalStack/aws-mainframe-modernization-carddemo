package com.carddemo.batch.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the whole fixture corpus to the byte values its scenario READMEs state.
 *
 * <p>Assumptions: 51 record files across sixteen scenario directories had almost no executable
 * consumer. The pre-posting job test opens four of them and the export job test one; the
 * interest job test reads the reference tree under {@code tests/} rather than this one, and the nine
 * posting scenarios are cited in prose and asserted against values declared inside unit tests. So a
 * one-cent boundary, a one-day boundary, a deliberately absent cross-reference row or a deliberately
 * zero-byte file could all change here with every test staying green -- which is precisely the change
 * these fixtures exist to make detectable.</p>
 */
class BatchFixtureContractTest {

    /** The classpath prefix every scenario resolves under. */
    private static final String ROOT = "fixtures/";

    /** The same tree as a source path, used for the census the classpath cannot enumerate. */
    private static final Path TREE = Path.of("src", "test", "resources", "fixtures");

    /** The card number every scenario uses on its transaction record. */
    private static final String SEED_CARD = "4859452612877065";

    /** The credit limit of account {@code 00000000007}, which both over-limit halves are stated against. */
    private static final BigDecimal SEED_CREDIT_LIMIT = new BigDecimal("2065.00");

    /** The expiration date of account {@code 00000000007}, which both expiry halves are stated against. */
    private static final String SEED_EXPIRY = "2024-12-13";

    /** The disclosure rate every interest scenario in this tree uses. */
    private static final BigDecimal SEED_RATE = new BigDecimal("15.00");

    /** The sixteen scenario paths the master contract's section 4.1 table names. */
    private static final List<String> SCENARIOS = List.of(
            "export/happy_path",
            "interest/default_fallback", "interest/happy_path", "interest/zero_balance",
            "posting/boundary_exact_limit", "posting/boundary_expiry_equal", "posting/empty_input",
            "posting/happy_path", "posting/reject_100_card_missing",
            "posting/reject_101_acct_missing", "posting/reject_102_overlimit",
            "posting/reject_103_expired", "posting/zero_balance",
            "preflight/happy_path", "preflight/unmatched_account", "preflight/unmatched_card");

    /** The file-name to layout-registry-name mapping every record file in this tree resolves through. */
    private static final Map<String, String> LAYOUT_OF_FILE = Map.of(
            "acctdata.txt", "ACCOUNT",
            "carddata.txt", "CARD",
            "cardxref.txt", "XREF",
            "custdata.txt", "CUSTOMER",
            "dailytran.txt", "DALYTRAN",
            "discgrp.txt", "DISGROUP",
            "tcatbal.txt", "TCATBAL",
            "trandata.txt", "TRAN");

    /**
     * The two files that are deliberately zero bytes, each for a different reason.
     *
     * <p>Assumptions: the two emptinesses mean opposite things and both are load-bearing. An empty
     * transaction feed means the run has nothing to do; an empty category balance means the run has
     * something to do and no row to do it to, which is what drives the create half of the branch.</p>
     */
    private static final List<String> DELIBERATELY_EMPTY = List.of(
            "posting/empty_input/dailytran.txt", "posting/zero_balance/tcatbal.txt");

    /**
     * Supplies every scenario path in this tree.
     *
     * @return one argument set per scenario directory, never {@code null}
     */
    private static Stream<Arguments> scenarios() {
        return SCENARIOS.stream().map(Arguments::of);
    }

    /**
     * Reads one fixture from the test classpath.
     *
     * @param scenario the domain-qualified scenario path, of type {@code String}, such as
     *     {@code posting/happy_path}; must not be {@code null}
     * @param file the file name within that directory, of type {@code String}; must not be
     *     {@code null}
     * @return the file's exact bytes, never {@code null}
     * @throws IllegalStateException if the resource is absent from the test classpath, which means
     *     the committed tree and this class disagree about what exists
     * @throws IOException if the stream cannot be read
     */
    private static byte[] bytes(String scenario, String file) throws IOException {
        String path = ROOT + scenario + "/" + file;
        try (InputStream in = BatchFixtureContractTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture absent from the test classpath: " + path);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Lists the record files one scenario directory holds, excluding its contract document.
     *
     * @param scenario the domain-qualified scenario path, of type {@code String}; must not be
     *     {@code null}
     * @return the file names in ascending order, never {@code null}
     * @throws IOException if the directory cannot be listed
     */
    private static List<String> recordFiles(String scenario) throws IOException {
        try (Stream<Path> entries = Files.list(TREE.resolve(scenario))) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> !"README.md".equals(name))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Decodes record {@code index} of a fixture under the layout its file name maps to.
     *
     * @param scenario the domain-qualified scenario path, of type {@code String}; must not be
     *     {@code null}
     * @param file the file name within that directory, of type {@code String}; must not be
     *     {@code null}
     * @param index the zero-based record ordinal to decode, of type {@code int}
     * @return the decoded field map of that record, never {@code null}
     * @throws IOException if the fixture cannot be read
     */
    private static Map<String, Object> record(String scenario, String file, int index)
            throws IOException {

        CopybookLayout.RecordSpec spec = CopybookLayout.layout(LAYOUT_OF_FILE.get(file));
        byte[] raw = bytes(scenario, file);
        byte[] one = new byte[spec.reclen()];
        System.arraycopy(raw, index * (spec.reclen() + 1), one, 0, spec.reclen());
        return FixedWidthCodec.decodeRecord(one, spec, StandardCharsets.US_ASCII);
    }

    /**
     * Reads one field of one record as the exact ASCII bytes the file holds.
     *
     * <p>Assumptions: identifiers and dates are compared through this helper rather than through the
     * decoded value, because a numeric picture decodes to a number and a number has lost its leading
     * zeros and its declared width. {@code XREF-ACCT-ID} is {@code 9(11)}, so the committed
     * {@code 00000000007} and the decoded {@code 7} are the same value and only one of them can be
     * compared against a fixture's bytes.</p>
     *
     * @param scenario the domain-qualified scenario path, of type {@code String}; must not be
     *     {@code null}
     * @param file the file name within that directory, of type {@code String}; must not be
     *     {@code null}
     * @param index the zero-based record ordinal, of type {@code int}
     * @param field the copybook field name, of type {@code String}, as the layout registry declares
     *     it; must not be {@code null}
     * @return the field's exact characters, unpadded and untrimmed, never {@code null}
     * @throws IOException if the fixture cannot be read
     * @throws IllegalArgumentException if the layout declares no field of that name
     */
    private static String raw(String scenario, String file, int index, String field)
            throws IOException {

        CopybookLayout.RecordSpec spec = CopybookLayout.layout(LAYOUT_OF_FILE.get(file));
        CopybookLayout.FieldSpec target = spec.fields().stream()
                .filter(candidate -> candidate.name().equals(field))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        spec.name() + " declares no field " + field));
        byte[] all = bytes(scenario, file);
        int offset = index * (spec.reclen() + 1) + target.start();
        return new String(all, offset, target.length(), StandardCharsets.US_ASCII);
    }

    /**
     * Counts the records one fixture holds.
     *
     * @param scenario the domain-qualified scenario path, of type {@code String}; must not be
     *     {@code null}
     * @param file the file name within that directory, of type {@code String}; must not be
     *     {@code null}
     * @return the record count, zero for a deliberately empty file
     * @throws IOException if the fixture cannot be read
     */
    private static int recordCount(String scenario, String file) throws IOException {
        int reclen = CopybookLayout.layout(LAYOUT_OF_FILE.get(file)).reclen();
        return bytes(scenario, file).length / (reclen + 1);
    }

    /**
     * Asserts that the scenario census is exactly the sixteen the master contract names.
     *
     * @throws IOException if the tree cannot be walked
     */
    @Test
    @DisplayName("the tree holds exactly the sixteen scenarios the contract's section 4.1 names")
    void theTreeHoldsExactlyTheSixteenNamedScenarios() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(TREE, 2)) {
            for (Path candidate : walk.filter(Files::isDirectory).sorted().toList()) {
                if (TREE.relativize(candidate).getNameCount() == 2) {
                    found.add(TREE.relativize(candidate).toString().replace('\\', '/'));
                }
            }
        }

        // WHY : Assumptions: the set is asserted CLOSED in both directions rather than as a minimum.
        //       A scenario added without a contract document would satisfy a minimum-count check and
        //       silently escape the section 10 mandate, and a scenario deleted would leave every
        //       README that cites it pointing at nothing. Both directions have to fail.
        assertThat(found).containsExactlyElementsOf(
                SCENARIOS.stream().sorted(Comparator.naturalOrder()).toList());
    }

    /**
     * Asserts that every scenario directory carries the contract document section 10 mandates.
     *
     * @param scenario the domain-qualified scenario path
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("every scenario carries the README its section 10 mandate requires")
    void everyScenarioCarriesItsContractDocument(String scenario) {
        // WHY : Assumptions: section 10 of the master contract states the obligation as *must*, and
        //       derives it from user-specified Rule 1 rather than from any row of the migration plan.
        //       An obligation stated in prose and checked by nobody is the state that let twelve of
        //       these sixteen directories go undocumented, so it is asserted here rather than trusted.
        assertThat(TREE.resolve(scenario).resolve("README.md"))
                .as("%s must carry a README.md per master section 10", scenario)
                .isRegularFile();
    }

    /**
     * Asserts that every record file is whole records at its declared width, with no CR.
     *
     * @param scenario the domain-qualified scenario path
     * @throws IOException if a fixture cannot be read
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("every record file is whole records at its declared width and carries no CR")
    void everyRecordFileIsWholeRecordsAtItsDeclaredWidth(String scenario) throws IOException {
        List<String> files = recordFiles(scenario);
        assertThat(files).as("%s holds no record file at all", scenario).isNotEmpty();

        for (String file : files) {
            assertThat(LAYOUT_OF_FILE)
                    .as("%s/%s has no layout in this class's mapping, so its geometry is unchecked",
                            scenario, file)
                    .containsKey(file);
            int reclen = CopybookLayout.layout(LAYOUT_OF_FILE.get(file)).reclen();
            byte[] raw = bytes(scenario, file);

            // WHY : Assumptions: the modulus is taken over reclen PLUS ONE, because master section 3.9
            //       makes exactly one LF terminate every record. Checking the modulus over reclen alone
            //       would accept a file whose terminators had been stripped and reject a correct one.
            assertThat(raw.length % (reclen + 1))
                    .as("%s/%s is not whole %d-byte records plus one terminator each",
                            scenario, file, reclen)
                    .isZero();
            assertThat(new String(raw, StandardCharsets.US_ASCII))
                    .as("%s/%s must be LF-only per master section 3.8", scenario, file)
                    .doesNotContain("\r");
            if (raw.length > 0) {
                assertThat(raw[raw.length - 1])
                        .as("%s/%s must end in exactly one newline per master section 3.9",
                                scenario, file)
                        .isEqualTo((byte) '\n');
                assertThat(raw[raw.length - 2])
                        .as("%s/%s carries a blank final line, which parses as a malformed record",
                                scenario, file)
                        .isNotEqualTo((byte) '\n');
            }
        }
    }

    /**
     * Asserts that every record of every file decodes under its declared layout.
     *
     * @param scenario the domain-qualified scenario path
     * @throws IOException if a fixture cannot be read
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("every record decodes under its declared layout, sign overpunch included")
    void everyRecordDecodesUnderItsDeclaredLayout(String scenario) throws IOException {
        for (String file : recordFiles(scenario)) {
            int count = recordCount(scenario, file);
            for (int index = 0; index < count; index++) {
                // WHY : Assumptions: the decode is the assertion. The shared codec rejects a money
                //       field whose low-order byte is not a sign overpunch, so decoding every record
                //       is what proves master section 3.3 holds across the whole corpus without this
                //       class restating the overpunch alphabet and drifting from the codec's own.
                assertThat(record(scenario, file, index))
                        .as("%s/%s record %d does not decode under %s",
                                scenario, file, index, LAYOUT_OF_FILE.get(file))
                        .isNotEmpty();
            }
        }
    }

    /**
     * Asserts that the two deliberately empty files are exactly zero bytes.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the two deliberately empty files are exactly zero bytes")
    void theTwoDeliberatelyEmptyFilesAreZeroBytes() throws IOException {
        for (String path : DELIBERATELY_EMPTY) {
            int slash = path.lastIndexOf('/');
            // WHY : Assumptions: zero bytes rather than one newline, per master section 3.11. An
            //       editor that appends a terminating newline on save would turn each of these into a
            //       file holding one malformed zero-length record, which the loaders reject -- and the
            //       failure would name the loader rather than the save that caused it.
            assertThat(bytes(path.substring(0, slash), path.substring(slash + 1)))
                    .as("%s is deliberately empty and must be exactly zero bytes", path)
                    .isEmpty();
        }
    }

    /**
     * Asserts that the over-limit boundary is a one-cent pair straddling this tree's credit limit.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the over-limit boundary is a one-cent pair straddling the credit limit")
    void theOverLimitBoundaryIsAOneCentPair() throws IOException {
        BigDecimal limit = (BigDecimal) record("posting/reject_102_overlimit", "acctdata.txt", 0)
                .get("ACCT-CREDIT-LIMIT");
        BigDecimal atLimit = (BigDecimal) record("posting/boundary_exact_limit", "dailytran.txt", 0)
                .get("DALYTRAN-AMT");
        BigDecimal overLimit = (BigDecimal) record("posting/reject_102_overlimit", "dailytran.txt", 0)
                .get("DALYTRAN-AMT");

        // WHY : Assumptions: the limit is read from the fixture rather than written out here, so the
        //       pair is anchored to the account it is actually measured against. Asserting the
        //       DIFFERENCE as well as the two values is what makes an edit fail: moving both amounts
        //       by the same figure would keep two independent value assertions green while destroying
        //       the boundary master section 7.1.3 exists to pin.
        assertThat(limit).isEqualByComparingTo(SEED_CREDIT_LIMIT);
        assertThat(atLimit).isEqualByComparingTo(limit);
        assertThat(overLimit.subtract(atLimit)).isEqualByComparingTo(new BigDecimal("0.01"));
    }

    /**
     * Asserts that the over-limit scenario stays clear of the expiration overwrite.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the over-limit scenario's date precedes its expiry, so 103 cannot overwrite 102")
    void theOverLimitScenarioStaysClearOfTheExpirationOverwrite() throws IOException {
        String originated = raw("posting/reject_102_overlimit", "dailytran.txt", 0,
                "DALYTRAN-ORIG-TS").substring(0, 10);
        String expires = raw("posting/reject_102_overlimit", "acctdata.txt", 0,
                "ACCT-EXPIRAION-DATE");

        // WHY : Assumptions: master section 7.1.4 records that the over-limit and expiry tests are two
        //       sequential unguarded IF blocks, so a fixture that is both over limit AND past expiry
        //       silently becomes a 103 scenario. Only ten characters take part, per the reference
        //       modifier at :414, so the comparison is made on ten and not on the whole timestamp.
        assertThat(expires).isEqualTo(SEED_EXPIRY);
        assertThat(originated).isLessThanOrEqualTo(expires);
    }

    /**
     * Asserts that both expiry-boundary scenarios pin the same expiration date and account.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("both expiry-boundary scenarios pin the same expiration date and account")
    void bothExpiryBoundaryScenariosPinTheSameDate() throws IOException {
        for (String scenario : List.of("posting/boundary_expiry_equal", "posting/reject_103_expired")) {
            // WHY : Assumptions: neither directory holds the transaction that dates onto or past the
            //       boundary -- each scenario README section 4.1 names that absence -- so what these
            //       two files can pin is the ACCOUNT side: the expiry the comparison is made against,
            //       and the cross-reference that resolves the seed card to that account. If either
            //       moved, several READMEs in this domain would become wrong at once.
            assertThat(raw(scenario, "acctdata.txt", 0, "ACCT-EXPIRAION-DATE"))
                    .as("%s pins the expiry the boundary pair is stated against", scenario)
                    .isEqualTo(SEED_EXPIRY);
            assertThat(raw(scenario, "cardxref.txt", 0, "XREF-CARD-NUM")).isEqualTo(SEED_CARD);
            assertThat(raw(scenario, "cardxref.txt", 0, "XREF-ACCT-ID"))
                    .isEqualTo(raw(scenario, "acctdata.txt", 0, "ACCT-ID"));
        }
    }

    /**
     * Asserts that the missing-card scenario's cross-reference cannot resolve its own transaction.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the missing-card scenario's cross-reference holds a different card")
    void theMissingCardScenarioHoldsADifferentCard() throws IOException {
        // WHY : Assumptions: the discrimination is asserted RELATIONALLY -- the card on the reject
        //       scenario's transaction is absent from that scenario's own cross-reference, while the
        //       control scenario's is present in its own -- rather than against a literal card number.
        //       This tree builds reason 100 from the cross-reference row it withholds, where the
        //       sibling transaction-service tree builds it from an all-nines card, so a literal
        //       carried between the two trees would be wrong in one of them.
        assertThat(raw("posting/reject_100_card_missing", "cardxref.txt", 0, "XREF-CARD-NUM"))
                .isNotEqualTo(raw("posting/reject_100_card_missing", "dailytran.txt", 0,
                        "DALYTRAN-CARD-NUM"));
        assertThat(raw("posting/happy_path", "cardxref.txt", 0, "XREF-CARD-NUM"))
                .isEqualTo(raw("posting/happy_path", "dailytran.txt", 0, "DALYTRAN-CARD-NUM"))
                .isEqualTo(SEED_CARD);
    }

    /**
     * Asserts that the missing-account scenario names an account its own master does not hold.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the missing-account scenario's cross-reference names an absent account")
    void theMissingAccountScenarioNamesAnAbsentAccount() throws IOException {
        String resolved = raw("posting/reject_101_acct_missing", "cardxref.txt", 0, "XREF-ACCT-ID");
        String onFile = raw("posting/reject_101_acct_missing", "acctdata.txt", 0, "ACCT-ID");

        // WHY : Assumptions: the card must still resolve, or the scenario would raise 100 and never
        //       reach the account lookup at all -- master section 7.1.3's short circuit. So both halves
        //       are asserted: the card is present in the cross-reference, and the account that
        //       cross-reference names is absent from the master.
        assertThat(raw("posting/reject_101_acct_missing", "cardxref.txt", 0, "XREF-CARD-NUM"))
                .isEqualTo(SEED_CARD);
        assertThat(resolved).isNotEqualTo(onFile);
    }

    /**
     * Asserts that the posting zero-balance scenario opens at zero into an empty balance file.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the posting zero-balance scenario opens at zero with an unchanged credit limit")
    void thePostingZeroBalanceScenarioOpensAtZero() throws IOException {
        Map<String, Object> account = record("posting/zero_balance", "acctdata.txt", 0);

        // WHY : Assumptions: the credit limit is asserted alongside the zero balance because the
        //       scenario's expected outcome is that the transaction POSTS, which depends on the limit
        //       and not on the balance -- master section 7.1.3 builds the over-limit operand from the
        //       two cycle fields and the amount. A fixture that zeroed the limit as well would turn a
        //       posting scenario into an over-limit one with no other assertion noticing.
        assertThat((BigDecimal) account.get("ACCT-CURR-BAL")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) account.get("ACCT-CREDIT-LIMIT"))
                .isEqualByComparingTo(SEED_CREDIT_LIMIT);
        assertThat(recordCount("posting/zero_balance", "tcatbal.txt")).isZero();
    }

    /**
     * Asserts that the interest zero-balance scenario pairs a zero balance with a non-zero rate.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the interest zero-balance scenario pairs zero balances with a non-zero rate")
    void theInterestZeroBalanceScenarioPairsZeroWithANonZeroRate() throws IOException {
        // WHY : Assumptions: BOTH halves are asserted because either one alone destroys the scenario
        //       in a different direction. Moving the rate to zero converts it into the zero-rate
        //       outcome of master section 7.2.2, which writes no transaction at all; moving the
        //       balances off zero converts it into a duplicate of the direct-hit sibling. Neither
        //       failure would be visible in any other assertion.
        assertThat((BigDecimal) record("interest/zero_balance", "discgrp.txt", 0).get("DIS-INT-RATE"))
                .isEqualByComparingTo(SEED_RATE);
        assertThat(recordCount("interest/zero_balance", "tcatbal.txt")).isEqualTo(2);
        for (int index = 0; index < 2; index++) {
            assertThat((BigDecimal) record("interest/zero_balance", "tcatbal.txt", index)
                    .get("TRAN-CAT-BAL"))
                    .as("interest/zero_balance row %d must open at zero", index)
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat((BigDecimal) record("interest/happy_path", "tcatbal.txt", index)
                    .get("TRAN-CAT-BAL"))
                    .as("interest/happy_path row %d is the contrast and must be non-zero", index)
                    .isEqualByComparingTo(new BigDecimal("1000.00"));
        }
    }

    /**
     * Asserts that the interest fallback scenario withholds the group id the direct hit supplies.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the fallback scenario blanks the group id the direct-hit scenario supplies")
    void theFallbackScenarioBlanksTheGroupId() throws IOException {
        // WHY : Assumptions: the two interest scenarios differ in ONE field and this is it. The
        //       direct-hit scenario's account carries a group id that its own disclosure file holds,
        //       and the fallback scenario's is blank so the composed key misses and the DEFAULT group
        //       is re-read. Every other operand is deliberately held equal across the pair, so this
        //       single field is the only thing that can distinguish the two branches.
        assertThat(raw("interest/happy_path", "acctdata.txt", 0, "ACCT-GROUP-ID"))
                .isEqualTo(raw("interest/happy_path", "discgrp.txt", 0, "DIS-ACCT-GROUP-ID"));
        assertThat(raw("interest/default_fallback", "acctdata.txt", 0, "ACCT-GROUP-ID")).isBlank();
        assertThat(recordCount("interest/default_fallback", "discgrp.txt")).isEqualTo(17);
        for (int index = 0; index < 17; index++) {
            assertThat(raw("interest/default_fallback", "discgrp.txt", index, "DIS-ACCT-GROUP-ID"))
                    .as("the fallback file must hold DEFAULT rows only, row %d does not", index)
                    .startsWith("DEFAULT");
        }
    }

    /**
     * Asserts that the unmatched-card scenario's cross-reference names another card.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the unmatched-card preflight scenario's cross-reference names another card")
    void theUnmatchedCardScenarioNamesAnotherCard() throws IOException {
        assertThat(raw("preflight/unmatched_card", "cardxref.txt", 0, "XREF-CARD-NUM"))
                .isNotEqualTo(raw("preflight/unmatched_card", "dailytran.txt", 0,
                        "DALYTRAN-CARD-NUM"));

        // WHY : Assumptions: the account file is asserted present and matching, because this scenario
        //       is the one where an account row is deliberately PRESENT and seeded by the consuming
        //       test -- so the absence of the account read is attributable to the short circuit rather
        //       than to there being nothing to read. Its own README section 4.1 draws that contrast
        //       against posting/reject_100_card_missing, where the row is present and genuinely unread.
        assertThat(raw("preflight/unmatched_card", "acctdata.txt", 0, "ACCT-EXPIRAION-DATE"))
                .isEqualTo(SEED_EXPIRY);
    }

    /**
     * Asserts that the two byte-identical preflight scenarios really are identical.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the two preflight scenarios documented as byte-identical really are")
    void theTwoIdenticalPreflightScenariosReallyAreIdentical() throws IOException {
        // WHY : Assumptions: this asserts a documented SAMENESS rather than a difference, which is
        //       unusual and deliberate. Both scenario READMEs state that the two directories are
        //       byte-identical and that the distinction lives in the cross-reference row the consuming
        //       test seeds -- account 7, which exists, against account 20, which is not created. If a
        //       future author "fixed" the duplication by editing one directory, those two READMEs
        //       would become wrong and the consuming test would keep passing.
        for (String file : List.of("cardxref.txt", "dailytran.txt")) {
            assertThat(bytes("preflight/happy_path", file))
                    .as("preflight/happy_path/%s and preflight/unmatched_account/%s are documented"
                            + " as byte-identical", file, file)
                    .isEqualTo(bytes("preflight/unmatched_account", file));
        }
    }

    /**
     * Asserts that the export scenario carries all five masters at one row each.
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the export scenario carries all five masters at five rows each")
    void theExportScenarioCarriesAllFiveMasters() throws IOException {
        // WHY : Assumptions: the export domain is the only one whose scenario is a COMPLETE corpus,
        //       because the export record composes five masters into one 500-byte artefact and a
        //       missing master would make one of its five record types unproducible. The row count is
        //       asserted equal across all five rather than merely non-zero, since the round trip pairs
        //       them off and an unequal count would silently shorten the artefact.
        for (String file : List.of("acctdata.txt", "carddata.txt", "cardxref.txt", "custdata.txt",
                "trandata.txt")) {
            assertThat(recordCount("export/happy_path", file))
                    .as("export/happy_path/%s must carry the same five rows as its siblings", file)
                    .isEqualTo(5);
        }
    }
}
