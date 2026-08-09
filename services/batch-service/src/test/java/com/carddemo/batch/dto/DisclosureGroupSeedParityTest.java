package com.carddemo.batch.dto;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the three declarations of the seventeen {@code DEFAULT} disclosure-group interest rates in
 * agreement: the immutable baseline extract, this module's test harness, and the migration that owns
 * the table.
 *
 * <p><b>Purpose.</b> The rate keyed by the {@code DEFAULT} account group is the one rate
 * {@code app/cbl/CBACT04C.cbl} lines 415 to 441 reach when a direct group lookup misses, so it is
 * read on the path taken by every account whose own disclosure group is absent. It is declared in
 * three places that no compiler compares: {@code app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS} carries
 * the baseline bytes, {@code db/testharness/test-harness-schemas-and-foreign-tables.sql} seeds the
 * rows this module's container-backed tests read, and
 * {@code services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql} seeds the
 * rows the deployed system reads. This class compares all three and fails the build when any pair
 * disagrees.</p>
 *
 * <p>Assumptions: the baseline is one of the three sides rather than a footnote beside a
 * harness-against-migration comparison, and that is the whole design of this class. The two SQL
 * scripts are both derived artifacts, so a comparison between them alone passes whenever they are
 * equally wrong, which is exactly the failure that produced this class: the harness seeded 0.00 for
 * the {@code ('07','0001')} pair while the owning migration seeded 15.00, and nothing compared them.
 * Adding the extract makes the immutable data the arbiter, so the two derived sides cannot drift
 * together away from it and still pass. That is the same reasoning the package charter beside this
 * file applies to the job-argument tokens, whose two declarations it compares rather than restating
 * either as a literal array.</p>
 *
 * <p>Assumptions: the {@code EBCDIC} extract is authoritative where a dataset ships in both
 * encodings, which for this dataset is not a stylistic preference but the difference between 15.00
 * and no interest at all. The two encodings of the disclosure-group dataset disagree on exactly one
 * field: record 34 of the extract reads <code>'00150{'</code> for the {@code ('07','0001')} pair while row
 * 34 of {@code app/data/ASCII/discgrp.txt} reads <code>'00000{'</code>. The extract decides because
 * {@code app/jcl/DISCGRP.jcl} lines 56 to 61 load the VSAM cluster by {@code REPRO} from
 * {@code DSN=AWS.M2.CARDDEMO.DISCGRP.PS}, the extract itself, and no job anywhere in
 * {@code app/jcl} loads the {@code .txt} form at all. That settlement is registered as
 * {@code D-SEED-ENCODING-AUTHORITY} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and this class is the mechanical half
 * of it.</p>
 *
 * <p>Refactoring Rationale: this class exists because the harness once decoded the {@code ASCII}
 * rendering instead, seeded 0.00 for the discriminating row, and recorded the difference from the
 * owning migration as a divergence it was not placed to repair. That reasoning was right that the
 * immutable baseline decides and wrong about which extract is the baseline, and nothing in the build
 * was able to say so, because the only two declarations any test compared were the harness against
 * itself. Correcting the value without adding this comparison would have left the same gap behind for
 * the next transcription.</p>
 *
 * <p>Alternatives Considered: asserting the seventeen rates as a literal table inside this file and
 * comparing each of the three sources against it. Rejected, because a literal table is a fourth
 * declaration of the same data and the first thing a reader would update when a test failed, which
 * turns a detected divergence into a silenced one. Every expectation here is therefore read from an
 * artifact, and the only literals are the single discriminating row and the row count, both of which
 * are asserted against the extract rather than trusted.</p>
 *
 * <p>Alternatives Considered: asserting the agreement through a database round trip, by loading the
 * harness into a container and querying {@code reference.disclosure_groups}. Rejected for this
 * property, because the harness is the artifact under test: a query can only report what the harness
 * seeded and cannot see what the owning migration would have seeded instead. The comparison has to
 * happen where the two declarations are text. It is also the reason this class needs no container
 * and runs in the unit phase, where a divergence is cheapest to see.</p>
 *
 * <p>Assumptions: two of the three sources are read from the repository tree rather than from the
 * test classpath, which extends the obligation the package charter states without weakening it. The
 * charter requires a test here to assert against the other side of a cross-module contract wherever
 * that side is reachable, and names the classpath because the job-argument tokens happen to be
 * reachable that way. The owning migration belongs to {@code reference-service} and the extract lives
 * under {@code app/}, so neither is on this module's classpath and both are reached by path from the
 * repository root instead. The precedent is
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/ServiceCatalogInventoryTest.java},
 * which resolves the root the same way to hold a document against the reactor it describes.</p>
 *
 * <p>Assumptions: the extract under {@code app/} is read as evidence and is never modified,
 * re-encoded or regenerated by anything here, and the same holds for the parity oracle suite under
 * {@code tests/}. The citation is the whole extent of the relationship.</p>
 */
class DisclosureGroupSeedParityTest {

    /**
     * The classpath location of this module's test harness, which seeds the rows its container-backed
     * tests read.
     */
    private static final String HARNESS_RESOURCE =
            "/db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The repository-relative path of the migration that owns {@code reference.disclosure_groups}.
     */
    private static final String OWNING_MIGRATION =
            "services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql";

    /**
     * The repository-relative path of the immutable baseline extract the running system loads.
     *
     * <p>Assumptions: this is the {@code EBCDIC} extract and not the {@code ASCII} rendering of the
     * same dataset. The two disagree on one rate, and this is the one {@code app/jcl/DISCGRP.jcl}
     * lines 56 to 61 load.</p>
     */
    private static final String BASELINE_EXTRACT =
            "app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS";

    /**
     * The name the 50-byte disclosure-group geometry is registered under in the shared kernel.
     */
    private static final String LAYOUT_NAME = "DISGROUP";

    /**
     * The character encoding of the baseline extract's bytes.
     *
     * <p>Assumptions: the extract is a code-page 037 dataset, which is the encoding
     * {@code com.carddemo.common.codec.FixedWidthCodec} accepts beside {@code US-ASCII} and the one
     * the migration's own loaders decode it with. Reading it as {@code US-ASCII} would not fail: it
     * would yield fifty characters of mojibake per record and a rate field that no decoder accepts,
     * which is a confusing failure rather than an informative one.</p>
     */
    private static final Charset EXTRACT_CHARSET = Charset.forName("IBM037");

    /**
     * The copybook name of the ten-character account group identifier at offset zero.
     */
    private static final String FIELD_ACCOUNT_GROUP_ID = "DIS-ACCT-GROUP-ID";

    /**
     * The copybook name of the two-character transaction type code.
     */
    private static final String FIELD_TRANSACTION_TYPE_CODE = "DIS-TRAN-TYPE-CD";

    /**
     * The copybook name of the four-digit transaction category code.
     */
    private static final String FIELD_TRANSACTION_CATEGORY_CODE = "DIS-TRAN-CAT-CD";

    /**
     * The copybook name of the signed annual percentage rate that follows the key.
     */
    private static final String FIELD_INTEREST_RATE = "DIS-INT-RATE";

    /**
     * The number of {@code DEFAULT} rows the baseline extract carries.
     *
     * <p>Assumptions: the count is asserted against the extract rather than trusted, so that a
     * comparison of two maps cannot pass because both are empty. Seventeen is the number of
     * {@code (type, category)} pairs the {@code DEFAULT} group prices, and it is the same seventeen
     * the {@code A000000000} group prices.</p>
     */
    private static final int DEFAULT_ROW_COUNT = 17;

    /**
     * The transaction type code of the one row the three sources once disagreed on.
     */
    private static final String DISCRIMINATING_TYPE_CODE = "07";

    /**
     * The transaction category code of the one row the three sources once disagreed on.
     */
    private static final String DISCRIMINATING_CATEGORY_CODE = "0001";

    /**
     * The rate the baseline extract carries for the discriminating row.
     *
     * <p>Assumptions: {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9 is
     * six zoned-decimal display characters with the sign overpunched onto the last of them, so the
     * extract's <code>'00150{'</code> is the digits {@code 001500} with a positive sign, which is 15.00.
     * The rejected reading is the {@code ASCII} rendering's <code>'00000{'</code>, which is 0.00.</p>
     */
    private static final BigDecimal DISCRIMINATING_RATE = new BigDecimal("15.00");

    /**
     * The declared scale of the rate, being the two decimal digit positions of its picture clause.
     */
    private static final int RATE_SCALE = 2;

    /**
     * Matches one seeded {@code DEFAULT} disclosure-group row in either SQL script.
     *
     * <p>Assumptions: the group identifier is matched at its declared ten-character width with its
     * trailing blanks written out, because that is the form both scripts spell and a pattern that
     * tolerated a trimmed identifier would also match a row seeded against a different, shorter group
     * whose name merely started with the same letters.</p>
     */
    private static final Pattern SEED_ROW = Pattern.compile(
            "\\(\\s*'DEFAULT {3}'\\s*,\\s*'(\\d{2})'\\s*,\\s*'(\\d{4})'\\s*,\\s*(-?\\d+\\.\\d+)\\s*\\)");

    /**
     * Matches an SQL line comment and everything after it on the same line.
     */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /**
     * Confirms the baseline extract carries exactly seventeen {@code DEFAULT} rows, so that no
     * comparison below can pass by comparing two empty maps.
     */
    @Test
    @DisplayName("the baseline extract carries exactly seventeen DEFAULT disclosure-group rows")
    void theBaselineExtractCarriesSeventeenDefaultRows() {
        Map<String, BigDecimal> baseline = baselineRates();

        assertThat(baseline).hasSize(DEFAULT_ROW_COUNT);
        assertThat(baseline).containsKey(discriminatingKey());
    }

    /**
     * Confirms this module's test harness seeds the same seventeen rates the baseline extract carries.
     *
     * <p>Assumptions: the assertion is map equality over all seventeen keys rather than a check of
     * the one row that once differed. A single-row check would pass the moment that row was corrected
     * and would say nothing about the other sixteen, and the rates are not uniform -- the extract
     * carries 0.00, 15.00 and 25.00 across the group -- so any of them could be transcribed wrongly
     * in the same way.</p>
     */
    @Test
    @DisplayName("the test harness seeds every DEFAULT rate the baseline extract carries")
    void theTestHarnessSeedsEveryBaselineDefaultRate() {
        Map<String, BigDecimal> baseline = baselineRates();
        Map<String, BigDecimal> harness = seededRates(readClasspathResource(HARNESS_RESOURCE));

        assertThat(harness)
                .as("harness seed at %s must agree with %s on every DEFAULT rate",
                        HARNESS_RESOURCE, BASELINE_EXTRACT)
                .isEqualTo(baseline);
    }

    /**
     * Confirms the migration that owns the table seeds the same seventeen rates the baseline extract
     * carries.
     */
    @Test
    @DisplayName("the owning migration seeds every DEFAULT rate the baseline extract carries")
    void theOwningMigrationSeedsEveryBaselineDefaultRate() {
        Map<String, BigDecimal> baseline = baselineRates();
        Map<String, BigDecimal> owning = seededRates(readRepositoryFile(OWNING_MIGRATION));

        assertThat(owning)
                .as("owning migration at %s must agree with %s on every DEFAULT rate",
                        OWNING_MIGRATION, BASELINE_EXTRACT)
                .isEqualTo(baseline);
    }

    /**
     * Confirms the one row the three sources once disagreed on reads 15.00 on every route into the
     * table.
     *
     * <p>Assumptions: this row is asserted by name in addition to the map equalities above, because
     * the two values it has held are not a rounding difference. It is the rate the fallback branch at
     * {@code app/cbl/CBACT04C.cbl} lines 415 to 441 resolves, so 0.00 and 15.00 are the difference
     * between accruing nothing and accruing fifteen percent on identical input, and a reader of a
     * failure here needs the row named rather than inferred from a map diff.</p>
     */
    @Test
    @DisplayName("the DEFAULT fallback rate for type 07 category 0001 is 15.00 on all three routes")
    void theDiscriminatingFallbackRateIsFifteenOnAllThreeRoutes() {
        String key = discriminatingKey();

        assertThat(baselineRates())
                .as("baseline extract %s", BASELINE_EXTRACT)
                .containsEntry(key, DISCRIMINATING_RATE);
        assertThat(seededRates(readClasspathResource(HARNESS_RESOURCE)))
                .as("test harness %s", HARNESS_RESOURCE)
                .containsEntry(key, DISCRIMINATING_RATE);
        assertThat(seededRates(readRepositoryFile(OWNING_MIGRATION)))
                .as("owning migration %s", OWNING_MIGRATION)
                .containsEntry(key, DISCRIMINATING_RATE);
    }

    /**
     * Decodes the baseline extract and returns its {@code DEFAULT} rates keyed by type and category.
     *
     * @return a map from the rendered {@code type/category} key to the rate at the declared scale;
     *     never {@code null}
     * @throws UncheckedIOException if the extract cannot be read
     * @throws AssertionError if the extract's length is not a whole number of records
     */
    private static Map<String, BigDecimal> baselineRates() {
        CopybookLayout.RecordSpec layout = CopybookLayout.layout(LAYOUT_NAME);
        byte[] extract = readRepositoryBytes(BASELINE_EXTRACT);

        // WHY : Assumptions: the record count is derived by dividing the file length by the declared
        //       record length, which is the only handle a fixed-length extract offers -- it carries
        //       no record separator and no header. The remainder is asserted to be zero because a
        //       non-zero one means the file and the descriptor disagree about the geometry, and
        //       reading on regardless would silently shift every field of every record after the
        //       first short one.
        assertThat(extract.length % layout.reclen())
                .as("%s must be a whole number of %d-byte records", BASELINE_EXTRACT, layout.reclen())
                .isZero();

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (int offset = 0; offset < extract.length; offset += layout.reclen()) {
            byte[] record = new byte[layout.reclen()];
            System.arraycopy(extract, offset, record, 0, layout.reclen());

            // WHY : Assumptions: the record is decoded one field at a time under the extract's own
            //       encoding rather than transcoded whole and then decoded. The shared codec switches
            //       on each field's storage kind before it reaches a character decoder for exactly
            //       this reason, and although this particular layout happens to hold no packed span,
            //       a whole-record transcode is the habit that corrupts the layouts that do -- so it
            //       is not established here for a reader to copy.
            Map<String, Object> decoded =
                    FixedWidthCodec.decodeRecord(record, layout, EXTRACT_CHARSET);

            // WHY : Assumptions: the group identifier is reached through the key type's
            //       blank-padding entry point rather than compared as text. The descriptor models the
            //       span as text and the decode strips its trailing blanks, so the value arrives
            //       shorter than the ten characters the key requires exactly; the entry point pads it
            //       back to the declared width, and the key type then answers whether it is the
            //       DEFAULT group. Comparing the stripped text against a padded literal here would
            //       need a second, private notion of what DEFAULT looks like.
            DisclosureGroupKey key = DisclosureGroupKey.ofBlankPaddedAccountGroupId(
                    String.valueOf(decoded.get(FIELD_ACCOUNT_GROUP_ID)),
                    String.valueOf(decoded.get(FIELD_TRANSACTION_TYPE_CODE)),
                    Math.toIntExact((Long) decoded.get(FIELD_TRANSACTION_CATEGORY_CODE)));
            if (!key.isDefaultAccountGroup()) {
                continue;
            }

            rates.put(renderKey(key.transactionTypeCode(), key.transactionCategoryCodeField()),
                    normalise((BigDecimal) decoded.get(FIELD_INTEREST_RATE)));
        }
        return rates;
    }

    /**
     * Extracts the {@code DEFAULT} disclosure-group rates one SQL script seeds.
     *
     * @param sql the script's full text; must not be {@code null}
     * @return a map from the rendered {@code type/category} key to the rate at the declared scale;
     *     never {@code null}
     */
    private static Map<String, BigDecimal> seededRates(String sql) {
        // WHY : Assumptions: line comments are removed before the rows are matched, because both
        //       scripts carry commentary beside the rows they seed and one of them quotes the
        //       extract's rate bytes in that commentary. A commented-out row must not read as a
        //       seeded one either: a row a reviewer disabled is precisely a row the deployed system
        //       does not have, so counting it would make this comparison agree while the two
        //       databases differed.
        Matcher rows = SEED_ROW.matcher(LINE_COMMENT.matcher(sql).replaceAll(""));

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        while (rows.find()) {
            rates.put(renderKey(rows.group(1), rows.group(2)), normalise(new BigDecimal(rows.group(3))));
        }
        return rates;
    }

    /**
     * Renders the map key one row is held under.
     *
     * @param transactionTypeCode the two-character type code
     * @param transactionCategoryCode the four-digit category code
     * @return the two codes joined by a solidus; never {@code null}
     */
    private static String renderKey(String transactionTypeCode, String transactionCategoryCode) {
        return transactionTypeCode + "/" + transactionCategoryCode;
    }

    /**
     * Renders the map key of the one row the three sources once disagreed on.
     *
     * @return the discriminating row's key; never {@code null}
     */
    private static String discriminatingKey() {
        return renderKey(DISCRIMINATING_TYPE_CODE, DISCRIMINATING_CATEGORY_CODE);
    }

    /**
     * Restates one rate at the scale its picture clause declares.
     *
     * @param rate the rate as decoded or as parsed from a script; must not be {@code null}
     * @return the same value carrying exactly the declared scale; never {@code null}
     * @throws ArithmeticException if the value cannot be held at the declared scale without rounding
     */
    private static BigDecimal normalise(BigDecimal rate) {
        // WHY : Assumptions: the scale is restated rather than left as read, because the comparisons
        //       above are map equality and an exact decimal's equality is scale-sensitive: 15.00 and
        //       15.0 are the same rate and are not equal. Rounding is deliberately not requested, so
        //       a script that seeded a third decimal digit -- a value the six-digit zoned field
        //       cannot hold at all -- is reported rather than quietly truncated into agreement.
        return rate.setScale(RATE_SCALE);
    }

    /**
     * Reads one resource from this module's test classpath.
     *
     * @param resource the absolute classpath location; must not be {@code null}
     * @return the resource's full text; never {@code null}
     * @throws UncheckedIOException if the resource cannot be read
     * @throws AssertionError if the classpath holds no such resource
     */
    private static String readClasspathResource(String resource) {
        try (InputStream stream = DisclosureGroupSeedParityTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("test classpath must hold %s", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read classpath resource " + resource, unreadable);
        }
    }

    /**
     * Reads one repository file as text.
     *
     * @param relative the path relative to the repository root; must not be {@code null}
     * @return the file's full text; never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String readRepositoryFile(String relative) {
        Path file = repositoryRoot().resolve(relative);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Reads one repository file as bytes.
     *
     * @param relative the path relative to the repository root; must not be {@code null}
     * @return the file's full contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read
     */
    private static byte[] readRepositoryBytes(String relative) {
        Path file = repositoryRoot().resolve(relative);
        try {
            return Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * Locates the repository root from the directory the test runs in.
     *
     * @return the nearest ancestor of the working directory that holds the baseline extract; never
     *     {@code null}
     * @throws AssertionError if no ancestor holds it
     */
    private static Path repositoryRoot() {
        // WHY : Assumptions: the root is found by walking up from the working directory looking for
        //       one of the files this class reads, rather than taken from a build property or a
        //       relative literal. Maven runs a module's tests with the working directory set to that
        //       module, so a relative literal would have to encode the depth from the module to the
        //       root and would break the moment the class moved; searching for a file that must exist
        //       for the test to mean anything at all makes the failure self-describing.
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(BASELINE_EXTRACT))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " contains "
                + BASELINE_EXTRACT + ", so the seeded rates cannot be held against the baseline");
    }
}
