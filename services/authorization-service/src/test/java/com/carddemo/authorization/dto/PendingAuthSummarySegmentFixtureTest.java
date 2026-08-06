package com.carddemo.authorization.dto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.codec.PackedDecimalCodec.SignedPacked;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes the seven committed pending-authorization summary segment fixtures.
 *
 * <p>Each fixture is a byte image of {@code PENDING-AUTHORIZATION-SUMMARY}, the IMS segment declared in
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} at lines 19 to 31. That segment is the one
 * place in this context where money reaches persisted data as packed decimal, so its byte image is a
 * contract that has to be pinned against real bytes rather than against a description.</p>
 *
 * <p><b>The segment layout, 0-based, derived field by field from the copybook.</b> Offsets are 0-based
 * throughout this class and are never mixed with a 1-based convention.</p>
 *
 * <table>
 *   <caption>PENDING-AUTHORIZATION-SUMMARY, 100 bytes</caption>
 *   <tr><th>Offset</th><th>Field</th><th>Picture</th><th>Bytes</th><th>Representation</th></tr>
 *   <tr><td>0</td><td>{@code PA-ACCT-ID}</td><td>{@code S9(11) COMP-3}</td><td>6</td>
 *       <td>packed, eleven digits plus a sign nibble</td></tr>
 *   <tr><td>6</td><td>{@code PA-CUST-ID}</td><td>{@code 9(09)}</td><td>9</td>
 *       <td>unsigned display digits</td></tr>
 *   <tr><td>15</td><td>{@code PA-AUTH-STATUS}</td><td>{@code X(01)}</td><td>1</td><td>character</td></tr>
 *   <tr><td>16</td><td>{@code PA-ACCOUNT-STATUS}</td><td>{@code X(02) OCCURS 5}</td><td>10</td>
 *       <td>five two-character slots</td></tr>
 *   <tr><td>26</td><td>{@code PA-CREDIT-LIMIT}</td><td>{@code S9(09)V99 COMP-3}</td><td>6</td>
 *       <td>packed money</td></tr>
 *   <tr><td>32</td><td>{@code PA-CASH-LIMIT}</td><td>{@code S9(09)V99 COMP-3}</td><td>6</td>
 *       <td>packed money</td></tr>
 *   <tr><td>38</td><td>{@code PA-CREDIT-BALANCE}</td><td>{@code S9(09)V99 COMP-3}</td><td>6</td>
 *       <td>packed money</td></tr>
 *   <tr><td>44</td><td>{@code PA-CASH-BALANCE}</td><td>{@code S9(09)V99 COMP-3}</td><td>6</td>
 *       <td>packed money</td></tr>
 *   <tr><td>50</td><td>{@code PA-APPROVED-AUTH-CNT}</td><td>{@code S9(04) COMP}</td><td>2</td>
 *       <td>binary halfword, most significant byte first</td></tr>
 *   <tr><td>52</td><td>{@code PA-DECLINED-AUTH-CNT}</td><td>{@code S9(04) COMP}</td><td>2</td>
 *       <td>binary halfword, most significant byte first</td></tr>
 *   <tr><td>54</td><td>{@code PA-APPROVED-AUTH-AMT}</td><td>{@code S9(09)V99 COMP-3}</td><td>6</td>
 *       <td>packed money</td></tr>
 *   <tr><td>60</td><td>{@code PA-DECLINED-AUTH-AMT}</td><td>{@code S9(09)V99 COMP-3}</td><td>6</td>
 *       <td>packed money</td></tr>
 *   <tr><td>66</td><td>{@code FILLER}</td><td>{@code X(34)}</td><td>34</td>
 *       <td>padding to the segment length</td></tr>
 * </table>
 *
 * <p>Byte-count check: 6 + 9 + 1 + 10 + 6 + 6 + 6 + 6 + 2 + 2 + 6 + 6 + 34 = 100, which is the size of
 * every single-segment fixture on disk. {@link #everyFixtureIsAWholeNumberOfSegments()} re-measures
 * that rather than trusting it.</p>
 *
 * <p>Assumptions: three DIFFERENT numeric representations sit inside one hundred bytes, and confusing
 * any two of them decodes plausible wrong numbers rather than failing. The account identifier and the
 * six money fields are packed decimal, the two counters are binary halfwords, and the customer
 * identifier is plain display digits. That is why each family below is decoded through the production
 * method built for it -- {@link PackedDecimalCodec#decodePacked} for the packed fields,
 * {@link PackedDecimalCodec#decodeBinary} for the counters, and a plain text read for the display
 * digits -- and never through one generic reader.</p>
 *
 * <p><b>The seven fixtures, and what each one exists to prove.</b> Five are single-segment images and
 * two are two-segment images; no fixture duplicates another's purpose.</p>
 *
 * <table>
 *   <caption>Fixture inventory</caption>
 *   <tr><th>File</th><th>Bytes</th><th>Segments</th><th>What only this file covers</th></tr>
 *   <tr><td>{@code pautsum0-canonical.bin}</td><td>100</td><td>1</td>
 *       <td>the reference image every other fixture is a variation of, carrying a NEGATIVE credit
 *           balance so the sign nibble is exercised by the baseline case</td></tr>
 *   <tr><td>{@code pautsum0-filler-nonblank.bin}</td><td>100</td><td>1</td>
 *       <td>a non-blank {@code FILLER}, proving a decoder does not depend on the padding being
 *           spaces</td></tr>
 *   <tr><td>{@code pautsum0-negative-zero-decode-only.bin}</td><td>100</td><td>1</td>
 *       <td>packed NEGATIVE ZERO -- all digit nibbles zero with a negative sign nibble</td></tr>
 *   <tr><td>{@code pautsum0-line-terminator-bytes.bin}</td><td>200</td><td>2</td>
 *       <td>{@code 0x0D} and {@code 0x0A} bytes inside the BINARY COUNTER halfwords of both segments,
 *           proving they are data and not record terminators; its second segment additionally carries a
 *           full-precision negative balance and two negative counters</td></tr>
 *   <tr><td>{@code pautsum0-purge-parent.bin}</td><td>100</td><td>1</td>
 *       <td>the parent summary of the purge flow, carrying a positive balance and non-zero counters in
 *           both slots</td></tr>
 *   <tr><td>{@code unload-gsam-summary-100.bin}</td><td>200</td><td>2</td>
 *       <td>two segments in ASCENDING account order</td></tr>
 *   <tr><td>{@code unload-prefixed-summary-100.bin}</td><td>200</td><td>2</td>
 *       <td>the SAME two accounts in DESCENDING order -- the paired opposite of the file above</td></tr>
 * </table>
 *
 * <p>Assumptions: the last two files are a matched pair whose only difference is segment order, and
 * that is the property under test. An unload stream is a sequence of fixed-length segments with no
 * length prefix and no terminator, so nothing in the bytes themselves declares an order; a reader that
 * silently sorted, or that assumed ascending, would pass against one file and fail against the other.
 * Committing both is what makes order an assertion instead of an accident.</p>
 *
 * <p>Alternatives Considered: asserting the two-segment files only by their length. Rejected because
 * length alone cannot distinguish the pair -- both are 200 bytes -- so the assertion that matters is
 * which account appears first, which is what {@link #theTwoUnloadFixturesCarryOppositeSegmentOrder()}
 * checks.</p>
 *
 * <p><b>Provenance and sensitive-data attestation.</b> Every byte of all seven files is fabricated for
 * these assertions. The two account identifiers, the customer identifiers and the five account-status
 * slots represent no real account and no real person; the status slots carry the deliberately
 * non-lifelike values {@code A1} through {@code E5} and {@code AB} through {@code F5} precisely so that
 * a slot-ordering error is visible in a failure message. No primary account number and no card
 * verification value appears in any of the seven files, because the summary segment declares
 * neither.</p>
 *
 * <p>Assumptions: the fixtures are read through the test classpath rather than by filesystem path.
 * Maven copies {@code src/test/resources/} into {@code target/test-classes/}, so each file resolves as
 * {@code fixtures/<name>} and these assertions hold identically in a reactor build and a single-module
 * build.</p>
 */
class PendingAuthSummarySegmentFixtureTest {

    /** The declared length of one {@code PENDING-AUTHORIZATION-SUMMARY} segment, in bytes. */
    private static final int SEGMENT_LENGTH = 100;

    /** The 0-based offset of {@code PA-ACCT-ID}, a packed field of eleven digits and a sign. */
    private static final int ACCT_ID_OFFSET = 0;

    /** The 0-based offset of {@code PA-CUST-ID}, nine unsigned display digits. */
    private static final int CUST_ID_OFFSET = 6;

    /** The declared width of {@code PA-CUST-ID}, in display characters. */
    private static final int CUST_ID_WIDTH = 9;

    /** The 0-based offset of {@code PA-AUTH-STATUS}, one character. */
    private static final int AUTH_STATUS_OFFSET = 15;

    /** The 0-based offset of the first of the five {@code PA-ACCOUNT-STATUS} slots. */
    private static final int ACCOUNT_STATUS_OFFSET = 16;

    /** The number of {@code PA-ACCOUNT-STATUS} slots the copybook declares with {@code OCCURS 5}. */
    private static final int ACCOUNT_STATUS_SLOTS = 5;

    /** The declared width of one {@code PA-ACCOUNT-STATUS} slot, in characters. */
    private static final int ACCOUNT_STATUS_WIDTH = 2;

    /** The 0-based offset of {@code PA-CREDIT-LIMIT}, packed money. */
    private static final int CREDIT_LIMIT_OFFSET = 26;

    /** The 0-based offset of {@code PA-CREDIT-BALANCE}, packed money. */
    private static final int CREDIT_BALANCE_OFFSET = 38;

    /** The 0-based offset of {@code PA-APPROVED-AUTH-CNT}, a signed binary halfword. */
    private static final int APPROVED_CNT_OFFSET = 50;

    /** The 0-based offset of {@code PA-DECLINED-AUTH-CNT}, a signed binary halfword. */
    private static final int DECLINED_CNT_OFFSET = 52;

    /** The 0-based offset of {@code FILLER}, the segment's trailing padding. */
    private static final int FILLER_OFFSET = 66;

    /** The declared width of {@code FILLER}, in bytes. */
    private static final int FILLER_WIDTH = 34;

    /** The integer digit count every packed money field in this segment declares. */
    private static final int MONEY_INT_DIGITS = 9;

    /** The decimal digit count every packed money field in this segment declares. */
    private static final int MONEY_DEC_DIGITS = 2;

    /** The digit count {@code PA-ACCT-ID} declares. */
    private static final int ACCT_ID_DIGITS = 11;

    /** The digit count each authorization counter declares. */
    private static final int COUNTER_DIGITS = 4;

    /** The sign nibble a negative packed field carries, as this codec emits and accepts it. */
    private static final int NEGATIVE_SIGN_NIBBLE = 0x0D;

    /** Every fixture this class consumes, in the order the class Javadoc tabulates them. */
    private static final List<String> ALL_FIXTURES =
            List.of(
                    "fixtures/pautsum0-canonical.bin",
                    "fixtures/pautsum0-filler-nonblank.bin",
                    "fixtures/pautsum0-negative-zero-decode-only.bin",
                    "fixtures/pautsum0-line-terminator-bytes.bin",
                    "fixtures/pautsum0-purge-parent.bin",
                    "fixtures/unload-gsam-summary-100.bin",
                    "fixtures/unload-prefixed-summary-100.bin");

    /**
     * Reads a committed binary fixture from the test classpath.
     *
     * @param resource the classpath-relative resource name, never {@code null}
     * @return the file's bytes exactly as committed, never {@code null}
     * @throws IllegalStateException if the resource is absent from the test classpath
     * @throws UncheckedIOException if the resource cannot be read
     */
    private static byte[] readFixture(String resource) {
        try (InputStream stream =
                PendingAuthSummarySegmentFixtureTest.class
                        .getClassLoader()
                        .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + resource + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException cause) {
            throw new UncheckedIOException("fixture " + resource + " could not be read", cause);
        }
    }

    /**
     * Extracts one segment from a possibly multi-segment fixture image.
     *
     * @param image the whole fixture image, never {@code null}
     * @param ordinal the 0-based segment position within the image
     * @return a copy of the requested segment's hundred bytes, never {@code null}
     */
    private static byte[] segment(byte[] image, int ordinal) {
        int start = ordinal * SEGMENT_LENGTH;
        return Arrays.copyOfRange(image, start, start + SEGMENT_LENGTH);
    }

    /**
     * Reads a fixed-width display field out of a segment as text.
     *
     * @param seg the segment bytes, never {@code null}
     * @param offset the 0-based offset of the field
     * @param width the declared width of the field, in characters
     * @return the field's characters, never {@code null}
     */
    private static String text(byte[] seg, int offset, int width) {
        return new String(seg, offset, width, StandardCharsets.US_ASCII);
    }

    /**
     * Reads one of the segment's packed money fields.
     *
     * @param seg the segment bytes, never {@code null}
     * @param offset the 0-based offset of the packed field
     * @return the decoded amount at a scale of exactly two, never {@code null}
     */
    private static BigDecimal money(byte[] seg, int offset) {
        return PackedDecimalCodec.decodePacked(seg, offset, MONEY_INT_DIGITS, MONEY_DEC_DIGITS, true);
    }

    /**
     * Asserts that every fixture is a whole number of hundred-byte segments.
     *
     * <p>Assumptions: this is the first assertion because every other one indexes into the image by
     * segment. A file whose length were not a multiple of the segment length would make every offset
     * below meaningless from the first byte of the overrun, and it would fail with an array error rather
     * than with a message naming the cause.</p>
     */
    @Test
    @DisplayName("every fixture image is a whole number of hundred-byte segments")
    void everyFixtureIsAWholeNumberOfSegments() {
        for (String resource : ALL_FIXTURES) {
            byte[] image = readFixture(resource);
            assertTrue(image.length > 0, resource + " is not empty");
            assertEquals(
                    0,
                    image.length % SEGMENT_LENGTH,
                    resource + " is a whole number of " + SEGMENT_LENGTH + "-byte segments");
        }
    }

    /**
     * Asserts the canonical segment decodes field by field to its documented values.
     *
     * <p>Assumptions: the canonical image is asserted exhaustively -- every one of the thirteen fields
     * -- and the six variant images are then asserted only on what makes each of them different. Without
     * one exhaustive baseline, an offset error common to the whole layout would be invisible, because
     * every variant would agree with every other variant about the wrong offsets.</p>
     *
     * <p>Assumptions: the canonical credit balance is NEGATIVE at {@code -100.00}. That is a property of
     * the fixture and not an accident: a baseline image whose every amount was positive would leave the
     * sign nibble of a packed money field untested by the very case a reader treats as normal.</p>
     */
    @Test
    @DisplayName("the canonical segment decodes to its documented value in every field")
    void theCanonicalSegmentDecodesFieldByField() {
        byte[] seg = segment(readFixture("fixtures/pautsum0-canonical.bin"), 0);

        assertEquals(
                new BigDecimal("10000000001"),
                PackedDecimalCodec.decodePacked(seg, ACCT_ID_OFFSET, ACCT_ID_DIGITS, 0, true),
                "PA-ACCT-ID decodes from six packed bytes");
        assertEquals("000000451", text(seg, CUST_ID_OFFSET, CUST_ID_WIDTH), "PA-CUST-ID is display digits");
        assertEquals("A", text(seg, AUTH_STATUS_OFFSET, 1), "PA-AUTH-STATUS is one character");

        List<String> expectedSlots = List.of("A1", "B2", "C3", "D4", "E5");
        for (int slot = 0; slot < ACCOUNT_STATUS_SLOTS; slot++) {
            assertEquals(
                    expectedSlots.get(slot),
                    text(seg, ACCOUNT_STATUS_OFFSET + slot * ACCOUNT_STATUS_WIDTH, ACCOUNT_STATUS_WIDTH),
                    "PA-ACCOUNT-STATUS slot " + (slot + 1) + " decodes at its own offset");
        }

        assertEquals(new BigDecimal("5000.00"), money(seg, CREDIT_LIMIT_OFFSET), "PA-CREDIT-LIMIT");
        assertEquals(new BigDecimal("1000.00"), money(seg, 32), "PA-CASH-LIMIT");
        assertEquals(new BigDecimal("-100.00"), money(seg, CREDIT_BALANCE_OFFSET), "PA-CREDIT-BALANCE");
        assertEquals(new BigDecimal("0.00"), money(seg, 44), "PA-CASH-BALANCE");

        assertEquals(
                new BigDecimal("42"),
                PackedDecimalCodec.decodeBinary(seg, APPROVED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "PA-APPROVED-AUTH-CNT decodes from a binary halfword, not from packed bytes");
        assertEquals(
                new BigDecimal("7"),
                PackedDecimalCodec.decodeBinary(seg, DECLINED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "PA-DECLINED-AUTH-CNT decodes from a binary halfword");

        assertEquals(new BigDecimal("4200.00"), money(seg, 54), "PA-APPROVED-AUTH-AMT");
        assertEquals(new BigDecimal("700.00"), money(seg, 60), "PA-DECLINED-AUTH-AMT");

        assertEquals(
                " ".repeat(FILLER_WIDTH),
                text(seg, FILLER_OFFSET, FILLER_WIDTH),
                "the canonical FILLER is blank, which is what makes the non-blank sibling a variation");
    }

    /**
     * Asserts a non-blank {@code FILLER} changes no decoded field.
     *
     * <p>Assumptions: the padding is not data, and a decoder must not read it. This fixture is the only
     * way to show that: it carries the literal text {@code FILLERFILLERFILLERFILLERFILLER1234} in the
     * trailing thirty-four bytes, so a reader that folded the padding into any preceding field would
     * decode a different value for that field. The four trailing digits are deliberate -- padding made
     * only of letters could not reveal a decoder that had absorbed it into a numeric field.</p>
     */
    @Test
    @DisplayName("a non-blank FILLER changes no decoded field")
    void aNonBlankFillerChangesNoDecodedField() {
        byte[] seg = segment(readFixture("fixtures/pautsum0-filler-nonblank.bin"), 0);

        assertEquals(
                "FILLERFILLERFILLERFILLERFILLER1234",
                text(seg, FILLER_OFFSET, FILLER_WIDTH),
                "the padding really is non-blank in this fixture");
        assertEquals(
                new BigDecimal("10000000001"),
                PackedDecimalCodec.decodePacked(seg, ACCT_ID_OFFSET, ACCT_ID_DIGITS, 0, true),
                "PA-ACCT-ID is unaffected by the padding");
        assertEquals(new BigDecimal("250.00"), money(seg, CREDIT_BALANCE_OFFSET), "PA-CREDIT-BALANCE");
        assertEquals(
                new BigDecimal("1700.00"),
                money(seg, 54),
                "PA-APPROVED-AUTH-AMT is unaffected by the padding");
        assertEquals(
                new BigDecimal("200.00"),
                money(seg, 60),
                "PA-DECLINED-AUTH-AMT, the field immediately before the padding, is unaffected");
        assertEquals(
                new BigDecimal("17"),
                PackedDecimalCodec.decodeBinary(seg, APPROVED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "PA-APPROVED-AUTH-CNT is unaffected by the padding");
    }

    /**
     * Asserts packed negative zero decodes as zero while its sign nibble survives inspection.
     *
     * <p>Assumptions: negative zero is a real value in packed decimal and has no distinct numeric
     * counterpart -- an exact decimal cannot be signed zero -- so the two halves of this assertion are
     * necessarily made through two different methods.
     * {@link PackedDecimalCodec#decodePacked} yields {@code 0.00}, and
     * {@link PackedDecimalCodec#decodePackedPreservingSign} additionally reports the nibble the field
     * carried, which is the only way the negative sign is observable at all.</p>
     *
     * <p>Assumptions: the fixture is named decode-only because a re-encode does not round-trip. Encoding
     * an exact zero emits the positive sign nibble, since the value carries no sign to preserve, so
     * asserting a byte-identical round trip here would assert something the representation cannot
     * deliver. That is a property of packed decimal, not a codec defect, and it is why this fixture is
     * read and never re-emitted.</p>
     */
    @Test
    @DisplayName("packed negative zero decodes as zero and its sign nibble stays observable")
    void packedNegativeZeroDecodesAsZeroWithItsSignObservable() {
        byte[] seg = segment(readFixture("fixtures/pautsum0-negative-zero-decode-only.bin"), 0);

        assertEquals(
                new BigDecimal("0.00"),
                money(seg, CREDIT_BALANCE_OFFSET),
                "negative zero decodes to zero at scale two");

        SignedPacked preserved =
                PackedDecimalCodec.decodePackedPreservingSign(
                        seg, CREDIT_BALANCE_OFFSET, MONEY_INT_DIGITS, MONEY_DEC_DIGITS, true);
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(preserved.value()),
                "the preserved-sign read agrees on the value");
        assertEquals(
                NEGATIVE_SIGN_NIBBLE,
                preserved.signNibble(),
                "the negative sign nibble is reported, which a plain decode cannot express");
    }

    /**
     * Asserts that {@code 0x0D} and {@code 0x0A} bytes inside a segment are data, not terminators.
     *
     * <p>Assumptions: this is the fixture that would catch the single most damaging mistake available on
     * this path -- reading an unload stream with a line-oriented reader. The terminator bytes sit inside
     * the two BINARY COUNTER halfwords of both segments, which is the realistic place for them: a
     * halfword may legitimately hold any of the 65536 bit patterns, and four of the ones it can hold are
     * a CR or an LF in either byte position. A reader that split on a line terminator would produce a
     * short first record and then misalign every field of every segment after it. A fixed-length reader
     * is unaffected, and both segments decode.</p>
     *
     * <p>Alternatives Considered: placing the terminator bytes in the trailing {@code FILLER}, which
     * would have been easier to author. Rejected because padding is never decoded, so a line-splitting
     * reader would truncate only bytes nothing reads and the fixture would pass while proving nothing.
     * Alternatives Considered: placing them in a packed field. Rejected because a packed field cannot
     * hold them -- {@code 0x0A} and {@code 0x0D} are not valid digit-pair or digit-plus-sign nibbles, so
     * the codec would reject the field and the assertion would be about packed validation rather than
     * about record framing.</p>
     *
     * <p>Assumptions: the second segment's counters decode NEGATIVE, at {@code -246} and {@code -243},
     * because their high bytes are {@code 0xFF} and their low bytes are the LF and CR values. That makes
     * the segment do double duty: it is the only place in this fixture set where a signed binary halfword
     * is exercised on the negative side, alongside a full-precision negative packed balance of
     * {@code -12345678.90} that fills every one of the nine declared integer digits.</p>
     */
    @Test
    @DisplayName("CR and LF bytes inside a segment are data rather than record terminators")
    void lineTerminatorBytesInsideASegmentAreData() {
        byte[] image = readFixture("fixtures/pautsum0-line-terminator-bytes.bin");
        assertEquals(2 * SEGMENT_LENGTH, image.length, "the fixture holds two segments");

        byte[] first = segment(image, 0);
        byte[] counters = Arrays.copyOfRange(first, APPROVED_CNT_OFFSET, DECLINED_CNT_OFFSET + 2);
        assertTrue(
                indexOf(counters, (byte) 0x0D) >= 0,
                "the first segment really does carry a CR byte inside its counter halfwords");
        assertTrue(
                indexOf(counters, (byte) 0x0A) >= 0,
                "the first segment really does carry an LF byte inside its counter halfwords");

        assertEquals(
                new BigDecimal("10000000001"),
                PackedDecimalCodec.decodePacked(first, ACCT_ID_OFFSET, ACCT_ID_DIGITS, 0, true),
                "the first segment's account identifier decodes despite the embedded terminators");
        assertEquals(
                new BigDecimal("3338"),
                PackedDecimalCodec.decodeBinary(first, APPROVED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "the halfword holding CR then LF decodes as the number those bytes spell");
        assertEquals(
                new BigDecimal("2570"),
                PackedDecimalCodec.decodeBinary(first, DECLINED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "the halfword holding LF then LF decodes as the number those bytes spell");

        byte[] second = segment(image, 1);
        assertEquals(
                "000000452",
                text(second, CUST_ID_OFFSET, CUST_ID_WIDTH),
                "the SECOND segment starts at offset 100 exactly, so nothing was consumed as a terminator");
        assertEquals(
                new BigDecimal("-12345678.90"),
                money(second, CREDIT_BALANCE_OFFSET),
                "the second segment fills all nine integer digits with a negative balance");
        assertEquals(
                new BigDecimal("-246"),
                PackedDecimalCodec.decodeBinary(second, APPROVED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "a counter whose low byte is LF and whose high byte is 0xFF decodes negative");
        assertEquals(
                new BigDecimal("-243"),
                PackedDecimalCodec.decodeBinary(second, DECLINED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "a counter whose low byte is CR and whose high byte is 0xFF decodes negative");
    }

    /**
     * Asserts the purge-flow parent segment decodes to its documented positive state.
     *
     * <p>Assumptions: the purge flow acts on a summary that has authorizations recorded against it, so
     * this fixture carries a non-zero count in BOTH counters and a positive credit balance. A parent
     * with zero counters would be indistinguishable from a summary that had never been used, which is
     * the state the purge flow does not act on.</p>
     */
    @Test
    @DisplayName("the purge-flow parent segment decodes to its documented positive state")
    void thePurgeParentSegmentDecodesToItsPositiveState() {
        byte[] seg = segment(readFixture("fixtures/pautsum0-purge-parent.bin"), 0);

        assertEquals(
                new BigDecimal("450.00"),
                money(seg, CREDIT_BALANCE_OFFSET),
                "the purge parent carries a POSITIVE credit balance");
        assertEquals(
                new BigDecimal("2"),
                PackedDecimalCodec.decodeBinary(seg, APPROVED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "PA-APPROVED-AUTH-CNT is non-zero");
        assertEquals(
                new BigDecimal("2"),
                PackedDecimalCodec.decodeBinary(seg, DECLINED_CNT_OFFSET, COUNTER_DIGITS, 0, true),
                "PA-DECLINED-AUTH-CNT is non-zero, so both counters are covered");
        assertEquals(new BigDecimal("300.00"), money(seg, 54), "PA-APPROVED-AUTH-AMT");
        assertEquals(new BigDecimal("150.00"), money(seg, 60), "PA-DECLINED-AUTH-AMT");
    }

    /**
     * Asserts the two unload fixtures hold the same accounts in opposite order.
     *
     * <p>Assumptions: the pair's whole content is order, so both halves are asserted -- that each file
     * holds the same two customer identifiers, and that it holds them the other way round. Asserting
     * only the order of one file would pass against a reader that had sorted the stream; asserting only
     * the membership would pass against a reader that had reversed it.</p>
     *
     * <p>Assumptions: the two segments are distinguished by {@code PA-CUST-ID} rather than by
     * {@code PA-ACCT-ID}. Both files carry the same packed account identifier, so the display customer
     * identifier is the only field that separates the two segments, and a failure message reporting a
     * nine-character display value is readable where a packed byte span is not.</p>
     */
    @Test
    @DisplayName("the two unload fixtures carry the same accounts in opposite segment order")
    void theTwoUnloadFixturesCarryOppositeSegmentOrder() {
        byte[] ascending = readFixture("fixtures/unload-gsam-summary-100.bin");
        byte[] descending = readFixture("fixtures/unload-prefixed-summary-100.bin");

        assertEquals(2 * SEGMENT_LENGTH, ascending.length, "the ascending fixture holds two segments");
        assertEquals(2 * SEGMENT_LENGTH, descending.length, "the descending fixture holds two segments");

        String ascendingFirst = text(segment(ascending, 0), CUST_ID_OFFSET, CUST_ID_WIDTH);
        String ascendingSecond = text(segment(ascending, 1), CUST_ID_OFFSET, CUST_ID_WIDTH);
        String descendingFirst = text(segment(descending, 0), CUST_ID_OFFSET, CUST_ID_WIDTH);
        String descendingSecond = text(segment(descending, 1), CUST_ID_OFFSET, CUST_ID_WIDTH);

        assertEquals("000000451", ascendingFirst, "the ascending fixture leads with the lower identifier");
        assertEquals("000000452", ascendingSecond, "and follows with the higher one");
        assertEquals("000000452", descendingFirst, "the descending fixture leads with the higher identifier");
        assertEquals("000000451", descendingSecond, "and follows with the lower one");

        assertNotEquals(
                ascendingFirst,
                descendingFirst,
                "the pair differs in which segment comes first, which is the property under test");
        assertArrayEquals(
                new String[] {ascendingFirst, ascendingSecond},
                new String[] {descendingSecond, descendingFirst},
                "both files hold the same two segments, only transposed");
    }

    /**
     * Reports the first position of a byte value within a span.
     *
     * @param span the bytes to search, never {@code null}
     * @param wanted the byte value to look for
     * @return the 0-based position of the first occurrence, or {@code -1} when the value is absent
     */
    private static int indexOf(byte[] span, byte wanted) {
        for (int index = 0; index < span.length; index++) {
            if (span[index] == wanted) {
                return index;
            }
        }
        return -1;
    }
}
