package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.FieldSpec;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reads every committed segment image and wire variant and asserts what its bytes mean.
 *
 * <p>Assumptions: every expected value below was measured from the committed bytes through the shared
 * codec, not copied from a description of them. Where a value looks arbitrary -- a counter of 3338, a
 * balance of minus twelve million -- it is the decimal reading of a byte pair chosen for what those
 * BYTES are, and the assertion states both.</p>
 *
 * <p>Trade-offs: the segment images are split into records by fixed length rather than by any
 * delimiter, and that is the whole point rather than a convenience. One of these fixtures carries a
 * carriage return and a line feed INSIDE a binary counter, so a reader that split on line endings
 * would produce a different record count from the same bytes. The fixed-length split is asserted
 * against that fixture explicitly.</p>
 */
class PendingAuthSummaryFixtureTest {

    /** Root of the fixture directory on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** Declared length of the pending-authorization summary segment, {@code SEGM ... BYTES=100}. */
    private static final int SEGMENT_LENGTH = 100;

    /** Transmitted length of one comma-separated authorization request payload. */
    private static final int REQUEST_WIRE_LENGTH = 170;

    /**
     * The account identifier every single-record fixture in this directory carries.
     *
     * <p>Assumptions: held as text rather than as a number because the packed codec yields a
     * {@code BigDecimal} for every packed field, identifier or amount, and a decimal compared against a
     * {@code long} fails on TYPE while reporting two values that print identically -- which is a
     * genuinely confusing failure to read.</p>
     */
    private static final String FIRST_ACCOUNT = "10000000001";

    /** The second account identifier, present only in the two-record fixtures. */
    private static final String SECOND_ACCOUNT = "10000000002";

    /**
     * Confirms every segment fixture is a whole number of hundred-byte images with no framing.
     *
     * <p>Assumptions: the absence of framing is asserted, not assumed. These files carry no length
     * prefix, no record-descriptor word and no terminator, so the file size alone determines the record
     * count; a fixture that gained a prefix would still decode field by field and would silently shift
     * every value by the prefix width.</p>
     *
     * @param name the fixture file name
     * @param records the number of hundred-byte images the file holds
     */
    @ParameterizedTest(name = "{0} holds {1} segment image(s)")
    @MethodSource("segmentFixtures")
    @DisplayName("every segment fixture is a whole number of hundred-byte images")
    void everySegmentFixtureIsAWholeNumberOfImages(String name, int records) {
        byte[] content = bytes(name);

        assertThat(content).as("%s: %d images of %d bytes, unframed", name, records, SEGMENT_LENGTH)
                .hasSize(records * SEGMENT_LENGTH);
        List<Map<String, Object>> decoded = segments(name);
        assertThat(decoded).hasSize(records);
        assertThat(decoded).allSatisfy(fields -> assertThat(fields)
                .as("every field of the layout must decode, including the two binary counters")
                .hasSize(summaryLayout().fields().size()));
    }

    /**
     * Confirms the canonical segment decodes to its documented field values and re-encodes exactly.
     *
     * <p>Assumptions: the negative credit balance is the load-bearing value here. Its packed sign
     * nibble is {@code D}, so a decoder that ignored the sign would return a positive hundred and every
     * length and scale assertion would still pass. Asserting the sign is what distinguishes a correct
     * packed reader from a plausible one.</p>
     */
    @Test
    @DisplayName("the canonical segment decodes to its documented values and re-encodes byte for byte")
    void theCanonicalSegmentDecodesToItsDocumentedValues() {
        byte[] image = bytes("pautsum0-canonical.bin");
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(image, summaryLayout());

        assertThat((BigDecimal) fields.get("PA-ACCT-ID")).isEqualByComparingTo(FIRST_ACCOUNT);
        assertThat(fields.get("PA-CUST-ID"))
                .as("the customer id is UNSIGNED DISPLAY, not packed, so it decodes as a whole number")
                .isEqualTo(451L);
        assertThat(fields.get("PA-AUTH-STATUS")).isEqualTo("A");
        assertThat(fields.get("PA-ACCOUNT-STATUS"))
                .as("the five two-byte slots occupy one ten-byte interval, distinguishable end to end")
                .isEqualTo("A1B2C3D4E5");
        assertThat((BigDecimal) fields.get("PA-CREDIT-LIMIT")).isEqualByComparingTo("5000.00");
        assertThat((BigDecimal) fields.get("PA-CASH-LIMIT")).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) fields.get("PA-CREDIT-BALANCE"))
                .as("the packed sign nibble is D, so this balance is NEGATIVE")
                .isEqualByComparingTo("-100.00");
        assertThat((BigDecimal) fields.get("PA-CASH-BALANCE")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) fields.get("PA-APPROVED-AUTH-CNT")).isEqualByComparingTo("42");
        assertThat((BigDecimal) fields.get("PA-DECLINED-AUTH-CNT")).isEqualByComparingTo("7");
        assertThat((BigDecimal) fields.get("PA-APPROVED-AUTH-AMT")).isEqualByComparingTo("4200.00");
        assertThat((BigDecimal) fields.get("PA-DECLINED-AUTH-AMT")).isEqualByComparingTo("700.00");
        assertThat(fields.get("FILLER")).isEqualTo(" ".repeat(34));
        assertThat(FixedWidthCodec.encodeRecord(fields, summaryLayout())).isEqualTo(image);
    }

    /**
     * Confirms a non-blank filler is carried as content rather than dropped as padding.
     *
     * <p>Assumptions: the assertion is on the filler's VALUE and not on the key's presence, and the
     * reason is specific. The codec drops a text filler only when it is blank AND the descriptor it was
     * handed is the very INSTANCE the registry holds -- {@code isPaddingDescriptor} compares by
     * identity, not by record name -- and this class deliberately passes its own instance, so the drop
     * rule cannot fire here at all and a presence assertion would pass whatever the bytes said. What
     * this fixture genuinely pins is that thirty-four bytes of content in the trailing field survive
     * decode and re-encode in place.</p>
     */
    @Test
    @DisplayName("a non-blank filler is content, and survives a re-encode unchanged")
    void nonBlankFillerIsContentAndSurvivesAReEncode() {
        byte[] image = bytes("pautsum0-filler-nonblank.bin");
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(image, summaryLayout());

        assertThat(fields.get("FILLER"))
                .as("thirty-four bytes of trailing content, carried verbatim rather than normalised")
                .isEqualTo("FILLERFILLERFILLERFILLERFILLER1234");
        assertThat((BigDecimal) fields.get("PA-CREDIT-BALANCE")).isEqualByComparingTo("250.00");
        assertThat((BigDecimal) fields.get("PA-APPROVED-AUTH-CNT")).isEqualByComparingTo("17");
        assertThat(FixedWidthCodec.encodeRecord(fields, summaryLayout()))
                .as("the filler bytes must survive the round trip in their exact positions")
                .isEqualTo(image);
    }

    /**
     * Confirms a packed negative zero decodes to zero and that only a sign-preserving encode restores it.
     *
     * <p>Assumptions: this fixture's name ends in {@code decode-only} and this test is what that name
     * means. The digits are all zero and the sign nibble is {@code D}, a value arithmetic cannot
     * distinguish from positive zero; so the ordinary encoder writes sign {@code C} and produces a
     * DIFFERENT hundred bytes that carry the SAME number. Asserting the plain round trip fails, and the
     * sign-preserving one succeeds, pins both halves -- that nothing is lost numerically, and that a
     * byte-exact rewrite needs the sign-preserving path.</p>
     */
    @Test
    @DisplayName("a packed negative zero decodes to zero and needs a sign-preserving encode")
    void negativeZeroDecodesToZeroAndNeedsASignPreservingEncode() {
        byte[] image = bytes("pautsum0-negative-zero-decode-only.bin");
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(image, summaryLayout());

        assertThat(image[43] & 0x0F)
                .as("the low nibble of the last packed byte is the sign, and D is negative")
                .isEqualTo(0x0D);
        BigDecimal balance = (BigDecimal) fields.get("PA-CREDIT-BALANCE");
        assertThat(balance).isEqualByComparingTo("0.00");
        assertThat(balance.signum()).as("negative zero is zero, so its sign is neither plus nor minus")
                .isZero();
        assertThat(FixedWidthCodec.encodeRecord(fields, summaryLayout()))
                .as("the ordinary encoder writes sign C for zero, so these bytes cannot round-trip")
                .isNotEqualTo(image);
        assertThat(FixedWidthCodec.encodeRecordPreservingSign(fields, summaryLayout(), image))
                .as("the sign-preserving encoder reproduces the original bytes exactly")
                .isEqualTo(image);
    }

    /**
     * Confirms line-terminator and high-bit bytes inside fields are data and not delimiters.
     *
     * <p>Assumptions: the counter values are asserted as both bytes and decimals because that is the
     * only way the assertion carries its own reason. 3338 is {@code 0x0D0A}, a carriage return followed
     * by a line feed; 2570 is {@code 0x0A0A}. The second image's counters are {@code 0xFF0A} and
     * {@code 0xFF0D}, which read as NEGATIVE shorts -- so the same fixture proves the binary fields are
     * signed and that a high byte survives beside a terminator byte. A reader that split this file on
     * line feeds would find more pieces than there are records, and that is asserted rather than
     * described.</p>
     */
    @Test
    @DisplayName("line-terminator and high-bit bytes inside fields are data, not record boundaries")
    void lineTerminatorAndHighBitBytesInsideFieldsAreData() {
        byte[] content = bytes("pautsum0-line-terminator-bytes.bin");
        assertThat(content).hasSize(2 * SEGMENT_LENGTH);
        assertThat(contains(content, (byte) 0x0D)).as("a carriage return sits inside a field").isTrue();
        assertThat(contains(content, (byte) 0x0A)).as("a line feed sits inside a field").isTrue();
        assertThat(contains(content, (byte) 0x00)).as("packed zero nibbles produce NUL bytes").isTrue();
        assertThat(contains(content, (byte) 0xFF)).as("a high-bit byte sits beside them").isTrue();
        assertThat(new String(content, StandardCharsets.ISO_8859_1).split("\n", -1).length)
                .as("splitting on line feeds finds more pieces than the two records there are")
                .isGreaterThan(2);

        List<Map<String, Object>> images = segments("pautsum0-line-terminator-bytes.bin");
        assertThat((BigDecimal) images.get(0).get("PA-APPROVED-AUTH-CNT"))
                .as("0x0D0A, a CR LF pair read as the signed short it is")
                .isEqualByComparingTo(BigDecimal.valueOf(0x0D0A));
        assertThat((BigDecimal) images.get(0).get("PA-DECLINED-AUTH-CNT"))
                .as("0x0A0A, two line feeds")
                .isEqualByComparingTo(BigDecimal.valueOf(0x0A0A));
        assertThat((BigDecimal) images.get(1).get("PA-ACCT-ID")).isEqualByComparingTo(SECOND_ACCOUNT);
        assertThat((BigDecimal) images.get(1).get("PA-APPROVED-AUTH-CNT"))
                .as("0xFF0A as a SIGNED short is negative, which an unsigned reader would miss")
                .isNegative()
                .isEqualByComparingTo(BigDecimal.valueOf((short) 0xFF0A));
        assertThat((BigDecimal) images.get(1).get("PA-DECLINED-AUTH-CNT"))
                .isEqualByComparingTo(BigDecimal.valueOf((short) 0xFF0D));
        assertThat((BigDecimal) images.get(1).get("PA-CREDIT-BALANCE"))
                .as("the widest negative balance the field can carry at full scale")
                .isEqualByComparingTo("-12345678.90");
    }

    /**
     * Confirms the purge-parent segment carries the parent key and both counters non-zero.
     *
     * <p>Assumptions: both counters are asserted non-zero because a purge acts on a parent that HAS
     * children, and the counters are the only evidence of children this segment carries -- the children
     * themselves live in the detail segment, which this directory does not hold. A parent with zero
     * counters would exercise the empty case under the name of the populated one.</p>
     */
    @Test
    @DisplayName("the purge-parent segment carries the parent key and both counters non-zero")
    void thePurgeParentSegmentCarriesTheParentKeyAndBothCounters() {
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(bytes("pautsum0-purge-parent.bin"),
                summaryLayout());

        assertThat((BigDecimal) fields.get("PA-ACCT-ID"))
                .as("the summary key is the parent of the detail segments a purge removes")
                .isEqualByComparingTo(FIRST_ACCOUNT);
        assertThat((BigDecimal) fields.get("PA-APPROVED-AUTH-CNT")).isEqualByComparingTo("2");
        assertThat((BigDecimal) fields.get("PA-DECLINED-AUTH-CNT")).isEqualByComparingTo("2");
        assertThat((BigDecimal) fields.get("PA-APPROVED-AUTH-AMT")).isEqualByComparingTo("300.00");
        assertThat((BigDecimal) fields.get("PA-DECLINED-AUTH-AMT")).isEqualByComparingTo("150.00");
    }

    /**
     * Confirms the two unload variants hold identical segments in opposite order.
     *
     * <p>Assumptions: this is the ordering assertion, and it is expressed as a comparison between the
     * two files rather than as an expected order for either. Both unload programs write the same
     * hundred-byte image -- {@code PAUDBUNL.CBL} line 233 writes it to a file, and {@code DBUNLDGS.CBL}
     * carries the same record at line 53 with its file declarations commented out -- so what the pair
     * demonstrates is that file position carries NO meaning: a consumer that assumed the first image
     * was the lowest account would pass against one variant and fail against the other. Asserting the
     * per-account values match across the two files closes the other half, since two files in different
     * orders holding different data would satisfy an order-only assertion.</p>
     */
    @Test
    @DisplayName("the two unload variants hold identical segments in opposite order")
    void theTwoUnloadVariantsHoldIdenticalSegmentsInOppositeOrder() {
        List<Map<String, Object>> sequential = segments("unload-gsam-summary-100.bin");
        List<Map<String, Object>> prefixed = segments("unload-prefixed-summary-100.bin");

        assertThat(sequential).hasSize(2);
        assertThat(prefixed).hasSize(2);
        assertThat(accountIds(sequential)).containsExactly(FIRST_ACCOUNT, SECOND_ACCOUNT);
        assertThat(accountIds(prefixed))
                .as("the same two accounts arrive in the opposite order, so position means nothing")
                .containsExactly(SECOND_ACCOUNT, FIRST_ACCOUNT);
        assertThat(prefixed.get(1))
                .as("the shared account must carry identical values in both variants")
                .isEqualTo(sequential.get(0));
        assertThat(prefixed.get(0)).isEqualTo(sequential.get(1));
    }

    /**
     * Confirms each amount variant is the declared wire length and survives a round trip.
     *
     * <p>Assumptions: the three variants are the boundary values of the money field -- a negative
     * amount, the largest the picture can express, and zero -- and each is asserted through the codec
     * rather than by reading the field as text. The negative case is the one that matters most: it is
     * the only one whose forced sign position carries a minus, so a renderer that took the sign out of
     * an integer position would show up here and nowhere else.</p>
     *
     * @param ordinal the zero-based line number within the fixture
     * @param expectedAmount the amount that line's money field carries
     */
    @ParameterizedTest(name = "line {0} carries {1}")
    @MethodSource("amountVariants")
    @DisplayName("each amount variant is 170 characters and round-trips through the codec")
    void eachAmountVariantIsTheDeclaredWireLengthAndRoundTrips(int ordinal, String expectedAmount) {
        List<String> lines = wireLines("auth-request-amount-variants.csv");
        assertThat(lines).hasSize(3);
        String payload = lines.get(ordinal);

        assertThat(payload.length())
                .as("every payload on this wire is the same length whatever its amount's sign")
                .isEqualTo(REQUEST_WIRE_LENGTH);
        CsvAuthCodec.AuthRequest request = CsvAuthCodec.decodeRequest(payload);
        assertThat(request.transactionAmount().amount()).isEqualByComparingTo(expectedAmount);
        assertThat(CsvAuthCodec.encodeRequest(request))
                .as("a payload this repository's producer would not emit is not a valid fixture")
                .isEqualTo(payload);
    }

    /**
     * Supplies each segment fixture with the number of images it holds.
     *
     * @return one argument pair per binary fixture
     */
    private static Stream<Arguments> segmentFixtures() {
        return Stream.of(
                Arguments.of("pautsum0-canonical.bin", 1),
                Arguments.of("pautsum0-filler-nonblank.bin", 1),
                Arguments.of("pautsum0-negative-zero-decode-only.bin", 1),
                Arguments.of("pautsum0-purge-parent.bin", 1),
                Arguments.of("pautsum0-line-terminator-bytes.bin", 2),
                Arguments.of("unload-gsam-summary-100.bin", 2),
                Arguments.of("unload-prefixed-summary-100.bin", 2));
    }

    /**
     * Supplies the amount each line of the variants fixture carries.
     *
     * @return one argument pair per line: its ordinal and its expected amount
     */
    private static Stream<Arguments> amountVariants() {
        return Stream.of(
                Arguments.of(0, "-250.00"),
                Arguments.of(1, "9999999999.99"),
                Arguments.of(2, "0.00"));
    }

    /**
     * Confirms the locally built descriptor is the registry's geometry and differs only in identity.
     *
     * <p>Assumptions: equality is asserted over the field list rather than over the record length,
     * because two descriptors can tile a hundred bytes in different ways and both validate. Comparing
     * every offset, width, kind, scale and sign flag at once is the whole of what a transcription can
     * get wrong.</p>
     *
     * <p>Refactoring Rationale: the comparison was whole-record equality and is now componentwise over
     * the seven GEOMETRY components, with the diagnostic-disclosure flag deliberately excluded. The
     * registered descriptor now resolves this segment through a fail-closed disclosure allowlist, so
     * seven of its thirteen fields are marked sensitive; this transcription declares none, because it
     * transcribes {@code CIPAUSMY.cpy} and a copybook has no notion of a field whose content may not be
     * logged. Whole-record equality therefore compared a target POLICY against a baseline
     * TRANSCRIPTION and would have to be satisfied by restating the policy here -- which would make this
     * second reading a copy of the first and destroy the independence that is the entire reason it
     * exists. Excluding the one non-geometry flag keeps the two readings independent about the only
     * thing they both describe.</p>
     */
    @Test
    @DisplayName("the local descriptor agrees with the shared registry field for field")
    void theLocalDescriptorAgreesWithTheSharedRegistry() {
        RecordSpec registered = CopybookLayout.layout("PAUTSUM0");
        RecordSpec local = summaryLayout();

        assertThat(local.fields())
                .as("a second transcription of CIPAUSMY must not disagree with the registered one")
                .extracting(FieldSpec::name, FieldSpec::start, FieldSpec::length, FieldSpec::kind,
                        FieldSpec::intDigits, FieldSpec::decDigits, FieldSpec::signed)
                .isEqualTo(registered.fields().stream()
                        .map(field -> org.assertj.core.groups.Tuple.tuple(field.name(), field.start(),
                                field.length(), field.kind(), field.intDigits(), field.decDigits(),
                                field.signed()))
                        .toList());
        assertThat(local.reclen()).isEqualTo(registered.reclen());
        assertThat(local.keyLength()).isEqualTo(registered.keyLength());
        assertThat(local)
                .as("it must nonetheless be a DIFFERENT instance, or the padding rule would fire")
                .isNotSameAs(registered);
    }

    /**
     * Builds the descriptor for the hundred-byte pending-authorization summary segment.
     *
     * <p>Assumptions: every offset and width below comes from the segment mapping in
     * {@code docs/architecture/data-model-and-schema-mapping.md} against {@code CIPAUSMY.cpy} lines 19
     * to 31, and {@code validateGeometry()} proves they tile the hundred bytes with no gap and no
     * overlap. The two counters are declared BINARY and not packed: they are {@code S9(04) COMP}, the
     * only binary fields in either authorization segment, and handing them to the packed codec is the
     * documented mistake this descriptor exists to prevent.</p>
     *
     * <p>Alternatives Considered: resolving the same geometry with
     * {@code CopybookLayout.layout("PAUTSUM0")}, which registers this segment and holds a descriptor
     * identical to the one below. Rejected here, and only here, because the codec's padding rule keys
     * on descriptor identity: handed the registered instance it drops the blank trailing field, which
     * is correct for production consumers and useless for a fixture whose trailing thirty-four bytes are
     * the thing under assertion. A local instance keeps them in the decoded map.</p>
     *
     * <p>Trade-offs: a locally built descriptor is a second transcription and could drift from the
     * registry's. {@code theLocalDescriptorAgreesWithTheSharedRegistry()} closes that by comparing the
     * two field-for-field, so the only thing this method may differ in is its identity.</p>
     *
     * @return a validated hundred-byte descriptor, never {@code null}
     */
    private static RecordSpec summaryLayout() {
        return new RecordSpec("PAUTSUM0", SEGMENT_LENGTH, 6, 0, List.of(
                CopybookLayout.packed("PA-ACCT-ID", 0, 11, 0, true),
                CopybookLayout.uint("PA-CUST-ID", 6, 9),
                CopybookLayout.text("PA-AUTH-STATUS", 15, 1),
                CopybookLayout.text("PA-ACCOUNT-STATUS", 16, 10),
                CopybookLayout.packed("PA-CREDIT-LIMIT", 26, 9, 2, true),
                CopybookLayout.packed("PA-CASH-LIMIT", 32, 9, 2, true),
                CopybookLayout.packed("PA-CREDIT-BALANCE", 38, 9, 2, true),
                CopybookLayout.packed("PA-CASH-BALANCE", 44, 9, 2, true),
                CopybookLayout.binary("PA-APPROVED-AUTH-CNT", 50, 4, 0, true),
                CopybookLayout.binary("PA-DECLINED-AUTH-CNT", 52, 4, 0, true),
                CopybookLayout.packed("PA-APPROVED-AUTH-AMT", 54, 9, 2, true),
                CopybookLayout.packed("PA-DECLINED-AUTH-AMT", 60, 9, 2, true),
                CopybookLayout.text("FILLER", 66, 34))).validateGeometry();
    }

    /**
     * Lists the account identifiers of decoded segments in file order.
     *
     * @param images the decoded segment images
     * @return the identifiers as plain decimal text, in the order the file holds them
     */
    private static List<String> accountIds(List<Map<String, Object>> images) {
        return images.stream()
                .map(image -> ((BigDecimal) image.get("PA-ACCT-ID")).toPlainString())
                .toList();
    }

    /**
     * Decodes one segment fixture into one field map per image.
     *
     * @param name the fixture file name
     * @return the decoded images in file order, never {@code null}
     */
    private static List<Map<String, Object>> segments(String name) {
        byte[] content = bytes(name);
        List<Map<String, Object>> images = new ArrayList<>();
        for (int offset = 0; offset + SEGMENT_LENGTH <= content.length; offset += SEGMENT_LENGTH) {
            images.add(FixedWidthCodec.decodeRecord(
                    Arrays.copyOfRange(content, offset, offset + SEGMENT_LENGTH), summaryLayout()));
        }
        return images;
    }

    /**
     * Reads a comma-separated fixture as one payload per line.
     *
     * <p>Assumptions: the bytes are decoded as ISO-8859-1 because this wire is single-byte by
     * construction and that charset maps each byte to the code point of the same value, so nothing above
     * 0x7F is replaced.</p>
     *
     * @param name the fixture file name
     * @return the non-empty lines in file order, never {@code null}
     */
    private static List<String> wireLines(String name) {
        List<String> lines = new ArrayList<>();
        for (String line : new String(bytes(name), StandardCharsets.ISO_8859_1).split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Reports whether a byte value appears anywhere in a fixture.
     *
     * @param content the fixture bytes
     * @param value the byte to look for
     * @return {@code true} when the value appears at least once
     */
    private static boolean contains(byte[] content, byte value) {
        for (byte candidate : content) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one fixture from the test class path.
     *
     * @param name the fixture file name
     * @return the file's bytes, never {@code null}
     * @throws IllegalStateException if the fixture is absent from the test class path or unreadable,
     *     either of which would mean this assertion was testing nothing
     */
    private static byte[] bytes(String name) {
        try (InputStream fixture =
                PendingAuthSummaryFixtureTest.class.getResourceAsStream(ROOT + name)) {
            if (fixture == null) {
                throw new IllegalStateException(ROOT + name + " is not on the test class path");
            }
            return fixture.readAllBytes();
        } catch (IOException problem) {
            throw new IllegalStateException(ROOT + name + " could not be read", problem);
        }
    }
}
