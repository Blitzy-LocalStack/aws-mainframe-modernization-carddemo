package com.carddemo.reference.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
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
                Arguments.of("reference_update/happy_path/trantype.txt", REFERENCE_RECLEN, 8),
                Arguments.of("reference_update/happy_path/trancatg.txt", REFERENCE_RECLEN, 18),
                Arguments.of("reference_update/delete_restricted_by_category/trantype.txt",
                        REFERENCE_RECLEN, 2),
                Arguments.of("disclosure_group/happy_path/discgrp.txt", DISCGRP_RECLEN, 1),
                Arguments.of("disclosure_group/default_fallback/discgrp.txt", DISCGRP_RECLEN, 34),
                Arguments.of("batch_reference_update/add_record/trtype-update.txt", BATCH_RECLEN, 2),
                Arguments.of("batch_reference_update/invalid_type_soft_reject/trtype-update.txt",
                        BATCH_RECLEN, 2),
                Arguments.of("date_conversion/happy_path/date-request.txt", REQUEST_RECLEN, 1),
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
        List<byte[]> categories = records(bytes("reference_update/happy_path/trancatg.txt"),
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
        assertThat(referringTypes).contains(referenced).doesNotContain(unreferenced);
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
                bytes("batch_reference_update/invalid_type_soft_reject/trtype-update.txt"), BATCH_RECLEN);

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
    @DisplayName("both date-conversion requests fill the declared function and key fields")
    void bothDateRequestsFillTheirDeclaredFields() throws IOException {
        byte[] wellFormed = records(bytes("date_conversion/happy_path/date-request.txt"),
                REQUEST_RECLEN).get(0);
        byte[] ignored = records(
                bytes("date_conversion/request_payload_ignored/date-request.txt"),
                REQUEST_RECLEN).get(0);

        assertThat(field(wellFormed, 0, 4)).isEqualTo("DATE");
        assertThat(field(wellFormed, 4, 11)).isEqualTo("00000000001");
        assertThat(field(ignored, 0, 4)).isEqualTo("DTE ");
        assertThat(field(ignored, 4, 11)).isEqualTo("00000000002");

        // WHY : Assumptions: the trailing 985 characters are blanks and are asserted as such,
        //       because REQUEST-MESSAGE is PIC X(1000) and a short record would be a different
        //       message. The two payloads differ, and CODATE01 reads neither field -- WS-FUNC and
        //       WS-KEY occur in that program only at their declarations -- so the pair exists to
        //       assert that a differing payload changes nothing observable.
        assertThat(field(wellFormed, 15, REQUEST_RECLEN - 15)).isBlank().hasSize(985);
        assertThat(field(ignored, 15, REQUEST_RECLEN - 15)).isBlank().hasSize(985);
        assertThat(wellFormed).isNotEqualTo(ignored);
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

        // WHY : Refactoring Rationale: date_conversion/invalid_date_rejected was renamed to
        //       request_payload_ignored during this remediation, because CODATE01 examines no field
        //       of the request and so has no invalid-date branch for the old name to exercise. The
        //       rename is pinned by asserting the NEW name resolves rather than by asserting the old
        //       one does not, which is the direction that cannot pass or fail for reasons of build
        //       hygiene.
        assertThat(bytes("date_conversion/request_payload_ignored/date-request.txt"))
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
