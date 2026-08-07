package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.common.codec.FixedWidthCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the 50-byte disclosure-group boundary in both directions, pad included.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link DisclosureGroupRecordMapper} had no executable consumer, so every byte its two encode
 * overloads produce and every value its decode yields could have changed with the whole suite staying
 * green. This class asserts all four mapped fields of a REAL reference record in both directions, the
 * scale-2 rate the interest job's non-zero gate depends on, the difference between the two encode
 * overloads across the 28-byte pad, and the rejections the class documents.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a caller
 * invokes, no value it yields and no exception it raises outside the test engine, so the type itself
 * accepts no parameter, returns nothing and throws nothing. The inapplicability is stated rather than
 * passed over, because user-specified Rule 1 forbids a docstring that omits parameters, return values
 * or purpose and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own at-clauses.</p>
 *
 * <h2>Assumptions: the reference record is measured, not composed</h2>
 *
 * <p>The record below is the FIRST 50 bytes of {@code app/data/ASCII/discgrp.txt}, read byte by byte:
 * {@code A000000000} at bytes 0 to 9, {@code 01} at 10 to 11, {@code 0001} at 12 to 15, then six zoned
 * rate characters at 16 to 21 -- the digits {@code 00150} followed by an opening-brace overpunch, which
 * is the positive-zero sign character, so the rate is 15.00 -- and then 28 ASCII ZEROS at 22 to 49.
 * That pad is the whole point of this class: the authoritative data pads with the digit zero and not
 * with a space, so an encode that writes blanks does not reproduce it.</p>
 */
@DisplayName("DisclosureGroupRecordMapper: 50-byte rate record, both encode overloads")
class DisclosureGroupRecordMapperTest {

    /** Declared record length, from {@code app/jcl/DISCGRP.jcl} {@code RECORDSIZE(50 50)}. */
    private static final int RECORD_LENGTH = 50;

    /** Zero-based offset of the trailing pad, from {@code app/cpy/CVTRA02Y.cpy} line 10. */
    private static final int PAD_OFFSET = 22;

    /** Declared width of the trailing pad. */
    private static final int PAD_LENGTH = 28;

    /** The ten-character group identifier of the first reference record. */
    private static final String GROUP_ID = "A000000000";

    /** The two-character transaction type code of the first reference record. */
    private static final String TYPE_CODE = "01";

    /** The four-digit transaction category code of the first reference record. */
    private static final String CATEGORY_CODE = "0001";

    /**
     * The first record of {@code app/data/ASCII/discgrp.txt}, pad included exactly as it is stored.
     *
     * <p>Assumptions: the pad is spelt {@code "0".repeat(PAD_LENGTH)} rather than pasted as a literal
     * run, so the fixture cannot silently disagree with the declared width if the descriptor changes.</p>
     */
    private static final String REFERENCE_RECORD =
            GROUP_ID                       // DIS-ACCT-GROUP-ID PIC X(10)
            + TYPE_CODE                    // DIS-TRAN-TYPE-CD  PIC X(02)
            + CATEGORY_CODE                // DIS-TRAN-CAT-CD   PIC 9(04)
            + "00150{"                     // DIS-INT-RATE      PIC S9(04)V99, +15.00
            + "0".repeat(PAD_LENGTH);      // FILLER            PIC X(28), ASCII ZEROS

    /**
     * Returns the reference record as a fresh byte array.
     *
     * @return a newly copied 50-byte image, so a mutating test cannot affect another
     */
    private static byte[] referenceImage() {
        return REFERENCE_RECORD.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The decode yields all four mapped fields, with the rate exact at scale two.
     *
     * <p>Assumptions: the scale is asserted as well as the value, and the two are different claims.
     * {@code app/cbl/CBACT04C.cbl} line 214 gates interest accrual on the rate being non-zero, and an
     * exact decimal compares by scale as well as by value, so a rate that arrived scale-stripped would
     * compare unequal to the scale-2 zero constant and open a gate that should stay shut.</p>
     */
    @Test
    @DisplayName("decodes the four mapped fields of the first reference record")
    void decodesTheReferenceRecord() {
        DisclosureGroup row = DisclosureGroupRecordMapper.toEntity(referenceImage());

        assertThat(row.getId().getAcctGroupId()).isEqualTo(GROUP_ID);
        assertThat(row.getTranTypeCd()).isEqualTo(TYPE_CODE);
        assertThat(row.getTranCatCd()).isEqualTo(CATEGORY_CODE);
        assertThat(row.getInterestRate()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(row.getInterestRate().scale()).isEqualTo(2);
    }

    /**
     * The single-argument encode reproduces every field and writes the pad as BLANKS.
     *
     * <p>Assumptions: this asserts the documented limitation rather than a defect, and asserting it is
     * what stops the limitation being mistaken for a bug or quietly changed. The overload has no source
     * image to read a pad from, so blanks are the only defensible choice; what would be a defect is
     * writing blanks while claiming byte identity, which is what the sibling test below rules out.</p>
     */
    @Test
    @DisplayName("the single-argument encode restores every field and pads with blanks")
    void theSingleArgumentEncodeWritesBlankPadding() {
        byte[] encoded =
                DisclosureGroupRecordMapper.toRecord(
                        DisclosureGroupRecordMapper.toEntity(referenceImage()));

        assertThat(encoded).hasSize(RECORD_LENGTH);
        assertThat(new String(encoded, 0, PAD_OFFSET, StandardCharsets.UTF_8))
                .as("the four mapped fields are reproduced exactly")
                .isEqualTo(REFERENCE_RECORD.substring(0, PAD_OFFSET));
        assertThat(new String(encoded, PAD_OFFSET, PAD_LENGTH, StandardCharsets.UTF_8))
                .as("the pad is blanks, which the authoritative record is not")
                .isEqualTo(" ".repeat(PAD_LENGTH));
        assertThat(encoded)
                .as("so the single-argument overload does NOT reproduce the seed record")
                .isNotEqualTo(referenceImage());
    }

    /**
     * The source-image encode reproduces the reference record byte for byte, pad included.
     *
     * <p>Assumptions: this is the assertion the backup-generation obligation rests on. A staged
     * generation of {@code AWS.M2.CARDDEMO.DISCGRP.BKUP} is expected to be the bytes that were read
     * rather than a rendering of them, and the difference between the two overloads is exactly the 28
     * pad bytes this record actually carries.</p>
     */
    @Test
    @DisplayName("the source-image encode reproduces the seed record byte for byte")
    void theSourceImageEncodeIsByteIdentical() {
        byte[] source = referenceImage();

        byte[] encoded = DisclosureGroupRecordMapper.toRecord(
                DisclosureGroupRecordMapper.toEntity(source), source);

        assertThat(encoded).hasSize(RECORD_LENGTH).isEqualTo(referenceImage());
    }

    /**
     * A pad of any byte value survives the source-image round trip, low values included.
     *
     * <p>Assumptions: three pad bytes are exercised rather than one, because the committed evidence for
     * this record family shows at least two conventions in use -- ASCII zeros in the seed extract and
     * low values wherever a {@code INITIALIZE} left the record area untouched, since that statement does
     * not reach a {@code FILLER} item. A restoration that happened to work for one printable byte and
     * not for a low value would pass a single-case test and fail on real data.</p>
     *
     * @param padByte the byte to fill the pad span with for this case
     */
    @ParameterizedTest
    @ValueSource(bytes = {(byte) '0', (byte) 0x00, (byte) 0x40})
    @DisplayName("a pad of ASCII zeros, low values or any other byte round trips unchanged")
    void anyPadByteSurvivesTheRoundTrip(byte padByte) {
        byte[] source = referenceImage();
        Arrays.fill(source, PAD_OFFSET, RECORD_LENGTH, padByte);

        byte[] encoded = DisclosureGroupRecordMapper.toRecord(
                DisclosureGroupRecordMapper.toEntity(source), source);

        assertThat(encoded).isEqualTo(source);
    }

    /**
     * A negative rate decodes with its sign and re-encodes carrying the same overpunch character.
     *
     * <p>Assumptions: the negative overpunch for a zoned {@code S9(04)V99} field puts the sign on the
     * LAST character and carries that character's DIGIT with it, so it is not a bare sign marker. A rate
     * of -15.00 is therefore spelt as the five digits {@code 00150} followed by a closing brace, the
     * overpunch that means negative zero; the character that looks like a plausible negative marker,
     * a capital O, in fact means negative SIX and would make the value -15.06. The case is exercised
     * because the sign is the one part of a zoned field that a wrong codec setting corrupts silently --
     * the digits still read correctly and only the sign flips, which turns a charge into a credit.</p>
     */
    @Test
    @DisplayName("a negative rate keeps its sign through decode and source-image encode")
    void aNegativeRateKeepsItsSign() {
        byte[] source = referenceImage();
        System.arraycopy("00150}".getBytes(StandardCharsets.UTF_8), 0, source, 16, 6);

        DisclosureGroup row = DisclosureGroupRecordMapper.toEntity(source);
        assertThat(row.getInterestRate()).isEqualByComparingTo(new BigDecimal("-15.00"));

        assertThat(DisclosureGroupRecordMapper.toRecord(row, source)).isEqualTo(source);
    }

    /**
     * A record of any length other than the declared one is refused rather than decoded short.
     *
     * <p>Assumptions: both a short and a long image are exercised, because the two fail differently in
     * a naive implementation -- a short one runs off the end of the array while a long one decodes
     * successfully and silently ignores the surplus. The second is the more dangerous of the two,
     * because it produces a plausible row from a misaligned stream.</p>
     *
     * @param length the image length to offer for this case
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 22, 49, 51, 100})
    @DisplayName("an image of the wrong length is refused, short or long")
    void aWrongLengthImageIsRefused(int length) {
        byte[] wrong = new byte[length];
        Arrays.fill(wrong, (byte) '0');

        assertThatThrownBy(() -> DisclosureGroupRecordMapper.toEntity(wrong))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * The source-image encode refuses an image of the wrong length before copying any span.
     *
     * <p>Assumptions: the refusal is by WIDTH and not by an array-bounds failure, which is why the
     * implementation routes through the sign-preserving codec entry point rather than checking the
     * length itself. A bounds failure raised from inside a pad copy would report an offset rather than
     * naming the record, and would leave a partially written array in hand.</p>
     */
    @Test
    @DisplayName("the source-image encode refuses a wrongly sized image by width")
    void theSourceImageEncodeRefusesAWrongWidthImage() {
        DisclosureGroup row = DisclosureGroupRecordMapper.toEntity(referenceImage());
        byte[] tooShort = new byte[RECORD_LENGTH - 1];

        assertThatThrownBy(() -> DisclosureGroupRecordMapper.toRecord(row, tooShort))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * A null entity is refused by both encode overloads.
     *
     * <p>Assumptions: both overloads are exercised, because the shared field map means a check written
     * in only one of them would leave the other reaching the map with a null. The second call
     * deliberately passes a well-formed image, so the only thing under test is the null entity.</p>
     */
    @Test
    @DisplayName("a null entity is refused by both encode overloads")
    void aNullEntityIsRefused() {
        assertThatThrownBy(() -> DisclosureGroupRecordMapper.toRecord(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DisclosureGroupRecordMapper.toRecord(null, referenceImage()))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * A rate wider than the descriptor's four integer digits is refused rather than truncated.
     *
     * <p>Assumptions: refusal rather than rounding is the documented contract, and the reason is that a
     * rounding decision is a business decision that belongs where an audit trail can show it. A silent
     * truncation here would put a wrong rate into a backup generation with nothing to indicate it.</p>
     */
    @Test
    @DisplayName("a rate too wide for its span is refused, not truncated")
    void anOverWideRateIsRefused() {
        DisclosureGroup wide = new DisclosureGroup(
                new DisclosureGroup.DisclosureGroupId(GROUP_ID, TYPE_CODE, CATEGORY_CODE),
                new BigDecimal("99999.00"));

        assertThatThrownBy(() -> DisclosureGroupRecordMapper.toRecord(wide))
                .isInstanceOf(RuntimeException.class);
    }
}
