package com.carddemo.reference.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.validation.DateEditValidator;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds every record file in this module's fixture tree to the contract its README states.
 *
 * <p>Assumptions: files are read from the **test classpath** rather than by filesystem path, because
 * that is how the eventual service consumers will read them and because a relative filesystem path
 * resolves differently from the reactor root than from the module directory. Maven copies
 * {@code src/test/resources/} into {@code target/test-classes/}, so a record file resolves as
 * {@code fixtures/<domain>/<scenario>/<file>.txt}.</p>
 */
class ReferenceFixtureContractTest {

    /** The classpath prefix every fixture in this tree resolves under. */
    private static final String ROOT = "fixtures/";

    /** The declared record length of the two 60-byte reference records. */
    private static final int REFERENCE_RECLEN = 60;

    /** The declared record length of the disclosure-group record. */
    private static final int DISCGRP_RECLEN = 50;

    /** The declared record length of the batch maintenance record. */
    private static final int BATCH_RECLEN = 53;

    /** The declared record length of the date-conversion request payload. */
    private static final int REQUEST_RECLEN = 1000;

    /**
     * The date the refused-date scenario is named for, February 29 in a year that has no February 29.
     *
     * <p>Assumptions: the refusal is a property of the calendar and not of any caller's tolerance, so
     * this vector reaches the same outcome wherever the rule is invoked. Its scenario README records why
     * a range vector such as {@code 1582-10-14} was not used instead: that one decodes to the
     * unsupported-range outcome, and four baseline call sites across two programs forgive precisely that
     * message number, so the scenario's expected outcome would depend on which caller asked.
     */
    private static final String REFUSED_DATE = "2023-02-29";

    /**
     * The control vector, the same month and day in a year that does have a February 29.
     *
     * <p>Assumptions: it differs from {@link #REFUSED_DATE} at exactly one byte, the final digit of the
     * year, so an assertion that the two reach different severities cannot be satisfied by a rule that
     * merely rejects the shape {@code NNNN-NN-NN} or that answers one fixed outcome for every input.
     */
    private static final String ACCEPTED_NEIGHBOUR_DATE = "2024-02-29";

    /**
     * Reads one fixture from the test classpath.
     *
     * @param relative the path below {@value #ROOT}, for example
     *     {@code reference_list/happy_path/trantype.txt}
     * @return the file's exact bytes
     * @throws IllegalStateException if the resource is absent from the classpath, which is a build or
     *     rename fault rather than an assertion failure
     * @throws IOException if the stream cannot be read
     */
    private static byte[] bytes(String relative) throws IOException {
        try (InputStream in = ReferenceFixtureContractTest.class.getClassLoader()
                .getResourceAsStream(ROOT + relative)) {
            if (in == null) {
                throw new IllegalStateException("fixture absent from the test classpath: "
                        + ROOT + relative);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Splits a fixture into fixed-width records, rejecting any residue.
     *
     * <p>Assumptions: each record is followed by a single line feed, so a file of n records occupies
     * n * (width + 1) bytes. The residue check is what turns a CRLF rewrite or a dropped pad into a
     * failure here instead of a mis-aligned field several assertions later.</p>
     *
     * @param raw the file's exact bytes
     * @param width the declared record width
     * @return one byte array per record, each exactly {@code width} long
     */
    private static List<byte[]> records(byte[] raw, int width) {
        List<byte[]> out = new ArrayList<>();
        int stride = width + 1;
        assertThat(raw.length % stride)
                .as("file length %d is not a whole number of %d-byte records plus one LF each",
                        raw.length, width)
                .isZero();
        for (int off = 0; off + width <= raw.length; off += stride) {
            assertThat(raw[off + width])
                    .as("record at offset %d must be followed by a line feed", off)
                    .isEqualTo((byte) '\n');
            byte[] rec = new byte[width];
            System.arraycopy(raw, off, rec, 0, width);
            out.add(rec);
        }
        return out;
    }

    /**
     * Decodes one record through the production codec and the registered layout.
     *
     * @param record the record bytes
     * @param layout the registry name of the layout to decode against
     * @return the decoded field map, in copybook declaration order
     */
    private static Map<String, Object> decode(byte[] record, String layout) {
        return FixedWidthCodec.decodeRecord(record, CopybookLayout.layout(layout),
                StandardCharsets.US_ASCII);
    }

    /**
     * Supplies every fixture that must be exactly zero bytes.
     *
     * @return one argument set per intentionally empty fixture
     */
    private static Stream<Arguments> emptyFixtures() {
        return Stream.of(
                Arguments.of("reference_list/empty_input/trantype.txt"),
                Arguments.of("reference_list/empty_input/trancatg.txt"),
                Arguments.of("disclosure_group/empty_input/discgrp.txt"),
                Arguments.of("batch_reference_update/empty_input/trtype-update.txt"),
                Arguments.of("date_conversion/empty_input/date-request.txt"));
    }

    /**
     * Supplies every populated fixture with its declared width and expected record count.
     *
     * @return one argument set per populated fixture
     */
    private static Stream<Arguments> populatedFixtures() {
        return Stream.of(
                Arguments.of("reference_list/happy_path/trantype.txt", REFERENCE_RECLEN, 7),

                // WHY : Assumptions: this entry and the delete-restrict one below name the EXACT
                //       scenario paths of two category files that this suite previously read only
                //       through their reference_update/happy_path sibling. A fixture nothing loads by
                //       its own path can be deleted or corrupted with the whole suite staying green,
                //       so enrolling the paths is what makes each file's bytes load-bearing.
                Arguments.of("reference_list/happy_path/trancatg.txt", REFERENCE_RECLEN, 18),
                Arguments.of("reference_update/happy_path/trantype.txt", REFERENCE_RECLEN, 8),
                Arguments.of("reference_update/happy_path/trancatg.txt", REFERENCE_RECLEN, 18),
                Arguments.of("reference_update/delete_restricted_by_category/trantype.txt",
                        REFERENCE_RECLEN, 2),
                Arguments.of("reference_update/delete_restricted_by_category/trancatg.txt",
                        REFERENCE_RECLEN, 2),
                Arguments.of("disclosure_group/happy_path/discgrp.txt", DISCGRP_RECLEN, 1),
                Arguments.of("disclosure_group/default_fallback/discgrp.txt", DISCGRP_RECLEN, 34),
                Arguments.of("batch_reference_update/add_record/trtype-update.txt", BATCH_RECLEN, 2),

                // WHY : Assumptions: these three scenario paths were absent from this list while their
                //       fixtures sat committed, so the bytes of each were established by their scenario
                //       README and by nothing executable -- which each of those documents said outright.
                //       Enrolling them is what turns those declared contracts into asserted ones, and the
                //       sweep in ReferenceFixtureTest now refuses any further omission of the same kind.
                Arguments.of("batch_reference_update/commented_line/trtype-update.txt", BATCH_RECLEN, 2),
                Arguments.of("batch_reference_update/delete_record/trtype-update.txt", BATCH_RECLEN, 2),
                Arguments.of("batch_reference_update/update_record/trtype-update.txt", BATCH_RECLEN, 1),
                Arguments.of("batch_reference_update/invalid_type_abend/trtype-update.txt",
                        BATCH_RECLEN, 2),
                Arguments.of("date_conversion/happy_path/date-request.txt", REQUEST_RECLEN, 1),
                Arguments.of("date_conversion/invalid_date_rejected/date-request.txt",
                        REQUEST_RECLEN, 1),
                Arguments.of("date_conversion/request_payload_ignored/date-request.txt",
                        REQUEST_RECLEN, 1));
    }

    /**
     * Asserts that every intentionally empty fixture is exactly zero bytes.
     *
     * @param relative the classpath-relative fixture path
     * @throws IOException if the fixture cannot be read
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyFixtures")
    @DisplayName("every intentionally empty fixture is exactly zero bytes")
    void everyEmptyFixtureIsZeroBytes(String relative) throws IOException {
        // WHY : Assumptions: the tree README is explicit that zero bytes is the ONLY correct
        //       encoding of an empty fixed-width input, because a file holding a single blank line
        //       is a one-byte file carrying one zero-length record and fails parsing instead of
        //       exercising the empty path. An editor that appends a final newline on save is the
        //       realistic way this regresses, and it is invisible in a diff viewer.
        assertThat(bytes(relative)).isEmpty();
    }

    /**
     * Asserts that every populated fixture holds the record count and width its README states.
     *
     * @param relative the classpath-relative fixture path
     * @param width the declared record width
     * @param expectedRows the record count the scenario README states
     * @throws IOException if the fixture cannot be read
     */
    @ParameterizedTest(name = "{0} -> {2} records of {1} bytes")
    @MethodSource("populatedFixtures")
    @DisplayName("every populated fixture holds the record count and width its README states")
    void everyPopulatedFixtureMatchesItsDeclaredShape(String relative, int width, int expectedRows)
            throws IOException {
        byte[] raw = bytes(relative);

        // WHY : Assumptions: the width is asserted through the arithmetic rather than by trusting
        //       the first record, so a file that lost or gained a single byte anywhere fails here.
        assertThat(raw).hasSize(expectedRows * (width + 1));
        assertThat(records(raw, width)).hasSize(expectedRows);
        assertThat(new String(raw, StandardCharsets.US_ASCII)).doesNotContain("\r");
    }

    /**
     * Asserts that the transaction-type list decodes to the seven seed types in key order.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the transaction-type list decodes to the seven seed types in key order")
    void theTypeListDecodesToSevenSeedTypesInKeyOrder() throws IOException {
        List<byte[]> rows = records(bytes("reference_list/happy_path/trantype.txt"),
                REFERENCE_RECLEN);

        List<String> codes = rows.stream().map(r -> decode(r, "TRANTYPE").get("TRAN-TYPE").toString())
                .toList();
        assertThat(codes).containsExactly("01", "02", "03", "04", "05", "06", "07");

        Map<String, Object> first = decode(rows.get(0), "TRANTYPE");

        // WHY : Assumptions: the description is compared at its FULL declared width including its
        //       trailing blanks, not stripped first. The 50-character field is the contract, and a
        //       comparison against a stripped value would pass against a record whose description
        //       had been shifted left or right inside the field.
        assertThat(first.get("TRAN-TYPE-DESC"))
                .isEqualTo("Purchase" + " ".repeat(42));
        assertThat(decode(rows.get(6), "TRANTYPE").get("TRAN-TYPE-DESC"))
                .isEqualTo("Adjustment" + " ".repeat(40));
    }

    /**
     * Asserts that the add-path fixture carries the authored free type code its README names.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the add-path fixture carries the authored free type code its README names")
    void theAddPathFixtureCarriesTheAuthoredFreeTypeCode() throws IOException {
        List<byte[]> rows = records(bytes("reference_update/happy_path/trantype.txt"),
                REFERENCE_RECLEN);
        assertThat(rows).hasSize(8);

        // WHY : Assumptions: the scenario README identifies row 8 as the ONE authored row in this
        //       file, on the ground that the seed's highest type code is 07 and an add therefore
        //       has no free code to claim. Asserting the authored value here is what stops the
        //       README's identification of it from drifting away from the bytes.
        Map<String, Object> authored = decode(rows.get(7), "TRANTYPE");
        assertThat(authored.get("TRAN-TYPE")).isEqualTo("08");
        assertThat(authored.get("TRAN-TYPE-DESC"))
                .isEqualTo("Fixture Add Path Type" + " ".repeat(29));
    }

    /**
     * Asserts that the restrict fixture pairs a referenced type with an unreferenced one.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the restrict fixture pairs a referenced type with an unreferenced one")
    void theRestrictFixturePairsReferencedAndUnreferencedTypes() throws IOException {
        List<byte[]> types = records(
                bytes("reference_update/delete_restricted_by_category/trantype.txt"),
                REFERENCE_RECLEN);

        // WHY : Refactoring Rationale: the child rows are now read from THIS scenario's own
        //       trancatg.txt rather than from reference_update/happy_path/trancatg.txt. The scenario
        //       ships two category rows of its own, and reading the sibling's eighteen instead left
        //       those two loaded by nothing: they could be emptied or repointed at another type and
        //       the restrict assertion would still pass on the sibling's rows. Reading the local pair
        //       also narrows what the case proves to what the scenario actually carries -- exactly
        //       two children of the referenced type and none of the unreferenced one.
        List<byte[]> categories = records(
                bytes("reference_update/delete_restricted_by_category/trancatg.txt"),
                REFERENCE_RECLEN);

        String referenced = decode(types.get(0), "TRANTYPE").get("TRAN-TYPE").toString();
        String unreferenced = decode(types.get(1), "TRANTYPE").get("TRAN-TYPE").toString();
        assertThat(referenced).isEqualTo("06");
        assertThat(unreferenced).isEqualTo("99");

        List<String> referringTypes = categories.stream()
                .map(r -> decode(r, "TRANCAT").get("TRAN-TYPE-CD").toString()).distinct().toList();

        // WHY : Assumptions: the restrict path is only reachable if a category row actually refers
        //       to the first code, and only refusable-then-permitted if none refers to the second.
        //       Both halves are asserted against the category file rather than assumed from the
        //       scenario's name, because a name cannot go out of date but a reference can.
        assertThat(referringTypes).containsExactly(referenced).doesNotContain(unreferenced);

        // WHY : Assumptions: the referring set is asserted to be EXACTLY the referenced code and
        //       nothing else, not merely to contain it. A scenario whose category file also referred to
        //       a third type would satisfy a containment assertion while making the delete of that
        //       third type refusable too, so the fixture would no longer isolate one restricted delete
        //       from one permitted one -- which is the whole distinction it exists to draw.
        // WHY : Assumptions: the two children are asserted individually as well as collectively,
        //       because the count is what makes the refusal non-vacuous. A single child would still
        //       refuse the delete, but the pair is what shows the refusal is a property of the
        //       reference and not of one row -- and a scenario that lost one child would still pass a
        //       collective assertion while proving strictly less than it claims.
        assertThat(categories).hasSize(2);
        assertThat(decode(categories.get(0), "TRANCAT").get("TRAN-CAT-CD")).hasToString("1");
        assertThat(decode(categories.get(0), "TRANCAT").get("TRAN-CAT-TYPE-DESC"))
                .isEqualTo("Fraud reversal" + " ".repeat(36));
        assertThat(decode(categories.get(1), "TRANCAT").get("TRAN-CAT-CD")).hasToString("2");
        assertThat(decode(categories.get(1), "TRANCAT").get("TRAN-CAT-TYPE-DESC"))
                .isEqualTo("Non-fraud reversal" + " ".repeat(32));

        // WHY : Trade-offs: these two rows are also present in reference_update/happy_path/trancatg.txt,
        //       and the scenario README accepts that second copy so that the directory holds both sides
        //       of the relationship it asserts. Two copies of the same rows are how the two come to
        //       disagree, so the copies are compared here rather than each being read in isolation:
        //       the local pair must be byte-identical to the type-06 subset of the eighteen-row file.
        //       The comparison is on RAW BYTES rather than on decoded fields, because a padding or
        //       balance-field difference between the copies would survive a field-by-field comparison
        //       that only looked at the fields this scenario gives a value to.
        List<byte[]> wholeDomain = records(bytes("reference_update/happy_path/trancatg.txt"),
                REFERENCE_RECLEN);
        List<byte[]> referencedSubset = wholeDomain.stream()
                .filter(r -> referenced.equals(decode(r, "TRANCAT").get("TRAN-TYPE-CD").toString()))
                .toList();
        assertThat(referencedSubset).hasSize(2);
        assertThat(categories.get(0)).isEqualTo(referencedSubset.get(0));
        assertThat(categories.get(1)).isEqualTo(referencedSubset.get(1));
    }

    /**
     * Asserts the list scenario's category file is the whole seeded extract, ordered and referentially
     * closed over its sibling type file.
     *
     * <p>Refactoring Rationale: this fixture was enrolled in no inventory and asserted by nothing when the
     * review found it, and the scenario's README tabled only its type file while claiming the consumer
     * loads every file in the directory. A list scenario whose category half is unread cannot demonstrate
     * that a category list is produced at all, which is the one thing the scenario is named for.</p>
     *
     * <p>Assumptions: four properties are asserted together because they are the ones a list operation
     * depends on. Ascending composite-key order with no duplicate, because the baseline reads the dataset
     * sequentially and a list screen pages in key order; referential closure over the sibling type file,
     * because a category naming a type the scenario does not seed would render with no parent; one
     * decoded row's content, so that a file of the right shape carrying the wrong text cannot pass; and
     * byte-identity with the update scenario's copy, because the two scenarios are deliberately the same
     * eighteen-row extract and a divergence between them would mean one had been edited in isolation.</p>
     *
     * @throws IOException if a fixture cannot be read
     */
    @Test
    @DisplayName("the list scenario's categories are the ordered, closed eighteen-row extract")
    void theListScenarioCategoriesAreOrderedAndReferentiallyClosed() throws IOException {
        List<byte[]> categories = records(bytes("reference_list/happy_path/trancatg.txt"),
                REFERENCE_RECLEN);
        List<byte[]> types = records(bytes("reference_list/happy_path/trantype.txt"),
                REFERENCE_RECLEN);

        assertThat(categories).hasSize(18);

        List<String> keys = categories.stream()
                .map(record -> decode(record, "TRANCAT").get("TRAN-TYPE-CD").toString()
                        + decode(record, "TRANCAT").get("TRAN-CAT-CD").toString())
                .toList();
        assertThat(keys).doesNotHaveDuplicates().isSorted();

        List<String> declaredTypes = types.stream()
                .map(record -> decode(record, "TRANTYPE").get("TRAN-TYPE").toString())
                .toList();
        assertThat(declaredTypes).containsExactly("01", "02", "03", "04", "05", "06", "07");

        List<String> orphans = categories.stream()
                .map(record -> decode(record, "TRANCAT").get("TRAN-TYPE-CD").toString())
                .distinct()
                .filter(parent -> !declaredTypes.contains(parent))
                .toList();
        assertThat(orphans)
                .as("a category naming a type this scenario does not seed would render with no parent")
                .isEmpty();

        assertThat(decode(categories.get(0), "TRANCAT").get("TRAN-CAT-CD")).hasToString("1");
        assertThat(decode(categories.get(0), "TRANCAT").get("TRAN-CAT-TYPE-DESC"))
                .isEqualTo("Regular Sales Draft" + " ".repeat(31));

        // WHY : Assumptions: the two scenarios are asserted BYTE-IDENTICAL rather than merely equal in
        //       row count. They are one extract used by two scenarios, and the eighteen rows carry the
        //       same descriptions and the same all-zero filler; comparing counts alone would let one copy
        //       be edited -- a description reworded, a filler blanked -- while the other stayed, which is
        //       exactly the drift a shared extract is supposed to make impossible.
        assertThat(bytes("reference_list/happy_path/trancatg.txt"))
                .isEqualTo(bytes("reference_update/happy_path/trancatg.txt"));
    }

    /**
     * Asserts that the fallback fixture omits every row of exactly one group classifier.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the fallback fixture omits every row of exactly one group classifier")
    void theFallbackFixtureOmitsExactlyOneClassifier() throws IOException {
        List<byte[]> rows = records(bytes("disclosure_group/default_fallback/discgrp.txt"),
                DISCGRP_RECLEN);
        assertThat(rows).hasSize(34);

        List<String> groups = rows.stream()
                .map(r -> decode(r, "DISGROUP").get("DIS-ACCT-GROUP-ID").toString()).distinct()
                .toList();

        // WHY : Assumptions: the omission IS the mechanism. The seed holds 51 rows -- seventeen
        //       each for three classifiers -- and this file keeps two whole and drops the third
        //       whole, so an account in the dropped group misses its key and falls back. A file
        //       that regained those rows would still decode, still hold valid records and silently
        //       stop exercising the fallback, which is the regression this assertion exists for.
        assertThat(groups).containsExactly("A000000000", "DEFAULT   ");
        assertThat(groups).doesNotContain("ZEROAPR   ");
        assertThat(rows.stream()
                .filter(r -> decode(r, "DISGROUP").get("DIS-ACCT-GROUP-ID").equals("DEFAULT   "))
                .count()).isEqualTo(17L);
    }

    /**
     * Asserts that the fallback rate a missing group resolves to is the nonzero DEFAULT rate.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the fallback rate a missing group resolves to is the nonzero DEFAULT rate")
    void theFallbackRateIsTheNonzeroDefaultRate() throws IOException {
        List<byte[]> rows = records(bytes("disclosure_group/default_fallback/discgrp.txt"),
                DISCGRP_RECLEN);

        // WHY : Assumptions: this is the assertion that makes the scenario worth having. The
        //       omitted ZEROAPR group carries 0.00 on every one of its seed rows, so an account in
        //       it accrues at the DEFAULT rate of 15.00 through the fallback where its own rows
        //       would have given nothing. A fallback that resolved to zero would be
        //       indistinguishable from no accrual at all, and asserting the rate is nonzero is how
        //       that confusion is ruled out.
        Map<String, Object> defaultRow = rows.stream().map(r -> decode(r, "DISGROUP"))
                .filter(d -> d.get("DIS-ACCT-GROUP-ID").equals("DEFAULT   ")
                        && d.get("DIS-TRAN-TYPE-CD").equals("01")
                        && d.get("DIS-TRAN-CAT-CD").toString().equals("1"))
                .findFirst().orElseThrow();
        assertThat(defaultRow.get("DIS-INT-RATE"))
                .isEqualTo(new BigDecimal("15.00"));
        assertThat(decode(rows.get(0), "DISGROUP").get("DIS-INT-RATE"))
                .isEqualTo(new BigDecimal("15.00"));
    }

    /**
     * Asserts that the direct-hit fixture is one record on the key the accrual composes.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the direct-hit fixture is one record on the key the accrual composes")
    void theDirectHitFixtureIsOneRecordOnTheComposedKey() throws IOException {
        Map<String, Object> only = decode(
                records(bytes("disclosure_group/happy_path/discgrp.txt"), DISCGRP_RECLEN).get(0),
                "DISGROUP");

        assertThat(only.get("DIS-ACCT-GROUP-ID")).isEqualTo("A000000000");
        assertThat(only.get("DIS-TRAN-TYPE-CD")).isEqualTo("01");
        assertThat(only.get("DIS-TRAN-CAT-CD")).hasToString("1");

        // WHY : Assumptions: the rate span's trailing character is a positive SIGN OVERPUNCH and
        //       not a brace, so the field decodes to a signed decimal rather than to text. The
        //       decoded type is asserted alongside the value because a codec that read the span as
        //       text would still produce something plausible-looking.
        assertThat(only.get("DIS-INT-RATE")).isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("15.00"));
    }

    /**
     * Asserts that the batch maintenance fixtures carry the action bytes their scenarios need.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the batch maintenance fixtures carry the action bytes their scenarios need")
    void theBatchFixturesCarryTheirActionBytes() throws IOException {
        List<byte[]> valid = records(bytes("batch_reference_update/add_record/trtype-update.txt"),
                BATCH_RECLEN);
        List<byte[]> invalid = records(
                bytes("batch_reference_update/invalid_type_abend/trtype-update.txt"), BATCH_RECLEN);

        // WHY : Assumptions: this record is NOT in the layout registry -- it is the 53-byte
        //       WS-INPUT-REC of COBTUPDT, a one-byte action plus a two-byte code plus a
        //       fifty-byte description -- so its three fields are read by offset here rather than
        //       through the codec. Registering it would imply it is a base master, which it is not.
        assertThat(field(valid.get(0), 0, 1)).isEqualTo("A");
        assertThat(field(valid.get(0), 1, 2)).isEqualTo("08");
        assertThat(field(valid.get(1), 0, 1)).isEqualTo("A");
        assertThat(field(valid.get(1), 1, 2)).isEqualTo("09");

        // WHY : Assumptions: the soft-reject fixture's discriminating byte is a LOWER-CASE 'a'. A
        //       COBOL EVALUATE compares bytes against each literal, so 'a' does not satisfy
        //       WHEN 'A' and falls to WHEN OTHER. Asserting the case explicitly is the point: an
        //       editor or a well-meaning tidy-up that upper-cased it would turn the soft-reject
        //       scenario into a second add scenario, and nothing else in the file would look wrong.
        // WHY : Refactoring Rationale: this scenario and its directory were named for an abend
        //       until this remediation, and the name asserted behaviour the reference program does
        //       not have. 9999-ABEND at app/app-transaction-type-db2/cbl/COBTUPDT.cbl lines 2240 to
        //       2260 displays the message, moves 4 to RETURN-CODE and exits the paragraph; it does
        //       not stop the run, so 1001-READ-NEXT-RECORDS reads and treats the following record.
        //       The second row below is therefore reachable, which is why it is asserted.
        assertThat(field(invalid.get(0), 0, 1)).isEqualTo("a").isNotEqualTo("A");
        assertThat(field(invalid.get(0), 1, 2)).isEqualTo("01");
        assertThat(field(invalid.get(1), 0, 1)).isEqualTo("U");
    }

    /**
     * Asserts that both date-conversion requests fill the declared function and key fields.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("all three date-conversion requests fill the declared function and key fields")
    void allThreeDateRequestsFillTheirDeclaredFields() throws IOException {
        byte[] wellFormed = records(bytes("date_conversion/happy_path/date-request.txt"),
                REQUEST_RECLEN).get(0);
        byte[] ignored = records(
                bytes("date_conversion/request_payload_ignored/date-request.txt"),
                REQUEST_RECLEN).get(0);
        byte[] refusedDate = records(
                bytes("date_conversion/invalid_date_rejected/date-request.txt"),
                REQUEST_RECLEN).get(0);

        assertThat(field(wellFormed, 0, 4)).isEqualTo("DATE");
        assertThat(field(wellFormed, 4, 11)).isEqualTo("00000000001");
        assertThat(field(ignored, 0, 4)).isEqualTo("DTE ");
        assertThat(field(ignored, 4, 11)).isEqualTo("00000000002");
        assertThat(field(refusedDate, 0, 4)).isEqualTo("DTE ");
        assertThat(field(refusedDate, 4, 11)).isEqualTo("00000000002");

        // WHY : Assumptions: the trailing 985 characters are blanks and are asserted as such,
        //       because REQUEST-MESSAGE is PIC X(1000) and a short record would be a different
        //       message. CODATE01 reads neither declared field -- WS-FUNC and WS-KEY occur in that
        //       program only at their declarations -- so a differing payload changes nothing
        //       observable, which is what the first pair exists to assert.
        assertThat(field(wellFormed, 15, REQUEST_RECLEN - 15)).isBlank().hasSize(985);
        assertThat(field(ignored, 15, REQUEST_RECLEN - 15)).isBlank().hasSize(985);
        assertThat(field(refusedDate, 15, REQUEST_RECLEN - 15)).isBlank().hasSize(985);
        assertThat(wellFormed).isNotEqualTo(ignored);

        // WHY : Assumptions: the refused-date envelope is BYTE-IDENTICAL to the ignored-payload one,
        //       and that is asserted rather than treated as an accident. Both scenario READMEs specify
        //       the same three field values -- the function token DTE with one trailing space and the
        //       key 00000000002 -- so equality is the documented outcome, and a reader meeting two
        //       equal files needs to be told which of the two properties distinguishes the scenarios.
        //       It is not the bytes: this record has NO date field at all, so what separates the two
        //       scenarios is the date supplied beside the envelope as a validator parameter, which the
        //       case below asserts. An assertion of inequality here would be false, and silence would
        //       leave the equality looking like a copy-paste fault.
        assertThat(refusedDate).isEqualTo(ignored);
    }

    /**
     * Asserts that the date the refused-date scenario is named for is refused by the production rule.
     *
     * <p>Assumptions: this scenario's outcome is a VALIDATION outcome and not a record-shape outcome,
     * so the assertion is made at the parameter level. Its own README states the boundary in full: the
     * envelope beside this assertion carries no date field, {@code CODATE01} examines no field of the
     * request at all, and the date travels as a ten-character parameter of the date-edit rule rather
     * than inside the 1000 bytes. A case that looked for the refused date inside the record would find
     * nothing to look at.
     *
     * <p>Assumptions: the rule is reached through the production
     * {@code com.carddemo.common.validation.DateEditValidator} and nothing about the outcome is
     * restated here as an expected constant -- the severity and the message number are compared against
     * that type's own named constants and against the feedback outcome it selects, so a change to the
     * rule fails this case rather than leaving it agreeing with a copy of the old answer.
     *
     * <p>Assumptions: {@link #REFUSED_DATE} is a calendar impossibility, so it is refused wherever the
     * rule is invoked, and its constant records why a range vector was not used in its place.
     *
     * @throws IOException if the scenario's envelope cannot be read
     */
    @Test
    @DisplayName("the date the refused-date scenario is named for is refused by the production rule")
    void theRefusedDateScenarioDateIsRefusedByTheProductionRule() throws IOException {
        assertThat(bytes("date_conversion/invalid_date_rejected/date-request.txt"))
                .hasSize(REQUEST_RECLEN + 1);

        DateEditValidator.LanguageEnvironmentResult outcome = DateEditValidator
                .evaluateWithLanguageEnvironment(REFUSED_DATE, DateEditValidator.DATE_FORMAT_MASK);

        assertThat(outcome.feedbackCode())
                .isEqualTo(DateEditValidator.FeedbackCode.BAD_DATE_VALUE);
        assertThat(outcome.severity()).isEqualTo(DateEditValidator.SEVERITY_ERROR);
        assertThat(outcome.messageNumber())
                .isEqualTo(DateEditValidator.FeedbackCode.BAD_DATE_VALUE.messageNumber());
        assertThat(outcome.verdict())
                .isEqualTo(DateEditValidator.FeedbackCode.BAD_DATE_VALUE.verdict());
        assertThat(outcome.date()).isEqualTo(REFUSED_DATE);
        assertThat(outcome.mask()).isEqualTo(DateEditValidator.DATE_FORMAT_MASK);

        // WHY : Assumptions: the outcome is asserted to differ from an accepted one as well as to equal
        //       the refusing one, because a rule that answered one fixed outcome for every input would
        //       satisfy a positive assertion on nothing but the echoed date and mask. The control vector
        //       differs from the refused date at a single byte, so the two also separate a genuine
        //       calendar judgement from a check on the shape NNNN-NN-NN alone.
        assertThat(DateEditValidator
                        .evaluateWithLanguageEnvironment(ACCEPTED_NEIGHBOUR_DATE,
                                DateEditValidator.DATE_FORMAT_MASK)
                        .severity())
                .isEqualTo(DateEditValidator.SEVERITY_VALID)
                .isNotEqualTo(outcome.severity());
    }

    /**
     * Asserts the date-edit scenario's carrier envelope is present and carries the derived bytes.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the date-edit scenario's carrier envelope is present and space-filled")
    void theDateEditScenarioCarrierEnvelopeIsPresentAndSpaceFilled() throws IOException {
        byte[] carrier = records(
                bytes("date_conversion/invalid_date_rejected/date-request.txt"),
                REQUEST_RECLEN).get(0);

        // WHY : Assumptions: this scenario's refusal happens to a DATE passed as a DateEditValidator
        //       parameter, not to any byte of this envelope, so what is assertable here is only that
        //       the carrier matches its README's derivation table. That table gives WS-FUNC as 'DTE'
        //       right-space-padded to four and WS-KEY as 00000000002, and the key deliberately
        //       differs from the happy_path sibling's 00000000001 so a consumer holding two decoded
        //       requests can tell them apart from the key alone.
        assertThat(field(carrier, 0, 4)).isEqualTo("DTE ");
        assertThat(field(carrier, 4, 11)).isEqualTo("00000000002");

        // WHY : Assumptions: the two fill regimes meet one byte apart inside this record, and that
        //       boundary is asserted rather than described because it is the one place a reader can
        //       see both. Byte 14 is the last digit of the '0'-left-padded unsigned key and byte 15
        //       is the first of the space-filled FILLER, exactly as the README's section 4.1.1
        //       states.
        assertThat(field(carrier, 14, 1)).isEqualTo("2");
        assertThat(field(carrier, 15, 1)).isEqualTo(" ");

        // WHY : Trade-offs: the FILLER is asserted to be 985 SPACES rather than merely 985 bytes.
        //       CODATE01.cbl line 112 declares VALUE SPACES, whereas the three seed-derived
        //       reference datasets in the sibling domains '0'-fill their FILLER -- so applying the
        //       seed regime here would write 985 zeros in place of 985 spaces. That is a different
        //       thousand bytes of the SAME length, which no width or record-count check can catch,
        //       and this is the assertion that does.
        assertThat(field(carrier, 15, REQUEST_RECLEN - 15))
                .isEqualTo(" ".repeat(985));

        // WHY : Assumptions: this envelope is byte-identical to request_payload_ignored's, and the
        //       identity is pinned rather than left to coincidence. Both READMEs derive the same
        //       three field values from the same declaration, so a later author who "differentiated"
        //       one of them would silently invalidate the other's section 4.1.1 table. The two
        //       scenarios are distinguished by their SUBJECT -- one pins that CODATE01 reads no
        //       field, the other carries a date-edit parameter alongside the bytes -- not by their
        //       payload. Alternatives Considered: giving this scenario a third distinct key, which
        //       was rejected because its README derives 00000000002 explicitly and the fixture must
        //       match the document rather than the document the fixture.
        assertThat(carrier).isEqualTo(records(
                bytes("date_conversion/request_payload_ignored/date-request.txt"),
                REQUEST_RECLEN).get(0));
    }

    /**
     * Asserts that a nonblank FILLER survives decoding as content in every reference record.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("a nonblank FILLER survives decoding as content in every reference record")
    void nonblankFillerSurvivesDecodingAsContent() throws IOException {
        Map<String, Object> type = decode(
                records(bytes("reference_list/happy_path/trantype.txt"), REFERENCE_RECLEN).get(0),
                "TRANTYPE");
        Map<String, Object> category = decode(
                records(bytes("reference_update/happy_path/trancatg.txt"), REFERENCE_RECLEN).get(0),
                "TRANCAT");

        // WHY : Assumptions: the codec's FILLER rule is content-based, not positional -- a filler
        //       is omitted only when its decoded text is BLANK. These records pad with '0'
        //       characters rather than spaces, so their fillers are content and are retained, and
        //       the decoded map therefore carries every declared field. This is asserted because
        //       the opposite expectation is the intuitive one: a reader who believed FILLER is
        //       always dropped would size these maps one field too small.
        assertThat(type).containsKey("FILLER").hasSize(3);
        assertThat(type.get("FILLER")).isEqualTo("00000000");
        assertThat(category).containsKey("FILLER").hasSize(4);
        assertThat(category.get("FILLER")).isEqualTo("0000");
    }

    /**
     * Asserts that a record fed at the wrong declared width is refused rather than mis-read.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("a record fed at the wrong declared width is refused rather than mis-read")
    void aRecordAtTheWrongWidthIsRefused() throws IOException {
        byte[] fiftyByteRecord = records(bytes("disclosure_group/happy_path/discgrp.txt"),
                DISCGRP_RECLEN).get(0);

        // WHY : Assumptions: this is the failure mode every scenario README lists, and it is
        //       asserted once here rather than in each. Feeding a 50-byte disclosure-group record
        //       to a 60-byte layout must raise rather than decode the first 50 bytes and pad, since
        //       a silent decode would produce plausible wrong values for every field.
        assertThatThrownBy(() -> decode(fiftyByteRecord, "TRANTYPE"))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                .hasMessageContaining("TRANTYPE");
    }

    /**
     * Asserts that an absent fixture name fails loudly instead of silently testing nothing.
     *
     * @throws IOException if the present fixture cannot be read
     */
    @Test
    @DisplayName("an absent fixture name fails loudly instead of silently testing nothing")
    void anAbsentFixtureNameFailsLoudly() throws IOException {
        // WHY : Assumptions: the loader raises on a missing resource rather than returning null,
        //       because a consumer that resolved null and skipped would report success for a
        //       scenario that no longer existed. The probe name has never existed in this tree, and
        //       that is deliberate: a name that was merely REMOVED would still resolve from a stale
        //       target/test-classes, since Maven's resource copy does not delete files that have
        //       disappeared from the source tree unless the module is cleaned first.
        assertThatThrownBy(() -> bytes("date_conversion/no_such_scenario/date-request.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from the test classpath");

        // WHY : Assumptions: both sibling scenarios of the probe resolve, and both are named here rather
        //       than only one, because the probe above proves only that SOME name fails. A pair of names
        //       that do resolve, from the same domain and read through the same loader, is what separates
        //       "the loader raises on an absent name" from "the loader raises on every name". These two
        //       carry byte-identical records under different scenario names, so they also pin the
        //       positive direction for the name RATHER than for the payload, which is the property this
        //       case is about.
        assertThat(bytes("date_conversion/request_payload_ignored/date-request.txt"))
                .hasSize(REQUEST_RECLEN + 1);
        assertThat(bytes("date_conversion/invalid_date_rejected/date-request.txt"))
                .hasSize(REQUEST_RECLEN + 1);
    }

    /**
     * Reads one span of a record as US-ASCII text.
     *
     * @param record the record bytes
     * @param offset the zero-based offset of the span
     * @param length the span's declared width
     * @return the span decoded as text, exactly {@code length} characters
     */
    private static String field(byte[] record, int offset, int length) {
        return new String(record, offset, length, StandardCharsets.US_ASCII);
    }
}
