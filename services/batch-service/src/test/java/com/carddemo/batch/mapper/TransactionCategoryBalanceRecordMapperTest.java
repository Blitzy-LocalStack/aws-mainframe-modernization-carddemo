package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.common.codec.FixedWidthCodec;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the 50-byte category-balance boundary in both directions, pad and sign included.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionCategoryBalanceRecordMapper} had no executable consumer, so the bytes its encode
 * overloads produce and the values its decode yields could have changed with the whole suite staying
 * green. That matters more for this record than for most: its image is compared BYTE FOR BYTE against
 * {@code tests/golden/posting/*}{@code /tcatbal.expected}, so one blank too many in the 22-byte pad or
 * one digit of scale in the balance is a failed comparison rather than an approximate match. This class
 * asserts all four mapped fields of a REAL reference record in both directions, the difference between
 * the two encode overloads across that pad, the key image geometry, and every documented rejection.</p>
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
 * <p>The record below is the FIRST 50 bytes of {@code app/data/ASCII/tcatbal.txt}, read byte by byte:
 * eleven account digits at bytes 0 to 10, the two-character type code at 11 to 12, the four-digit
 * category code at 13 to 16, eleven zoned balance characters at 17 to 27 -- ten digits followed by an
 * opening-brace overpunch, which is the positive-zero sign, so the balance is 0.00 -- and then 22 ASCII
 * ZEROS at 28 to 49. The pad is the point: the authoritative data pads with the digit zero, so an encode
 * that writes blanks does not reproduce it.</p>
 */
@DisplayName("TransactionCategoryBalanceRecordMapper: 50-byte balance record, both encode overloads")
class TransactionCategoryBalanceRecordMapperTest {

    /** Declared record length, from {@code app/jcl/TCATBALF.jcl} {@code RECORDSIZE(50 50)}. */
    private static final int RECORD_LENGTH = 50;

    /** Zero-based offset of the balance span. */
    private static final int BALANCE_OFFSET = 17;

    /** Declared width of the balance span, eleven zoned characters for {@code S9(09)V99}. */
    private static final int BALANCE_LENGTH = 11;

    /** Zero-based offset of the trailing pad, from {@code app/cpy/CVTRA01Y.cpy} line 10. */
    private static final int PAD_OFFSET = 28;

    /** Declared width of the trailing pad. */
    private static final int PAD_LENGTH = 22;

    /** The account identifier the first reference record carries. */
    private static final long ACCOUNT_ID = 10L;

    /** The two-character transaction type code of the first reference record. */
    private static final String TYPE_CODE = "10";

    /** The four-digit transaction category code of the first reference record. */
    private static final String CATEGORY_CODE = "0001";

    /** The first record of {@code app/data/ASCII/tcatbal.txt}, pad included exactly as stored. */
    private static final String REFERENCE_RECORD =
            "00000000010"                  // TRANCAT-ACCT-ID PIC 9(11)
            + TYPE_CODE                    // TRANCAT-TYPE-CD PIC X(02)
            + CATEGORY_CODE                // TRANCAT-CD      PIC 9(04)
            + "0000000000{"                // TRAN-CAT-BAL    PIC S9(09)V99, +0.00
            + "0".repeat(PAD_LENGTH);      // FILLER          PIC X(22), ASCII ZEROS

    /**
     * Returns the reference record as a fresh byte array.
     *
     * @return a newly copied 50-byte image, so a mutating test cannot affect another
     */
    private static byte[] referenceImage() {
        return REFERENCE_RECORD.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the reference record with its balance span replaced.
     *
     * @param zonedBalance the eleven zoned characters to write at the balance offset
     * @return a fresh 50-byte image carrying that balance and the reference record's other fields
     */
    private static byte[] imageWithBalance(String zonedBalance) {
        byte[] image = referenceImage();
        System.arraycopy(zonedBalance.getBytes(StandardCharsets.UTF_8), 0, image,
                BALANCE_OFFSET, BALANCE_LENGTH);
        return image;
    }

    /**
     * The decode yields all four mapped fields, with the balance exact at scale two.
     *
     * <p>Assumptions: the category code is asserted as the four-character string {@code "0001"} and not
     * as the number 1, because the leading zeros are part of the key. A code stored as an integer would
     * make {@code 0001} and {@code 1} the same key, which the owning migration's use of a fixed-character
     * column exists to prevent.</p>
     */
    @Test
    @DisplayName("decodes the four mapped fields of the first reference record")
    void decodesTheReferenceRecord() {
        TransactionCategoryBalance row =
                TransactionCategoryBalanceRecordMapper.toEntity(referenceImage());

        assertThat(row.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(row.getTypeCd()).isEqualTo(TYPE_CODE);
        assertThat(row.getCategoryCd()).isEqualTo(CATEGORY_CODE);
        assertThat(row.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.getBalance().scale()).isEqualTo(2);
    }

    /**
     * The single-argument encode reproduces every field and writes the pad as BLANKS.
     *
     * <p>Assumptions: this asserts the documented limitation rather than a defect, and asserting it is
     * what stops the limitation being mistaken for a bug or quietly changed. The overload has no source
     * image to read a pad from, so blanks are the only defensible choice for a row this module
     * ORIGINATED; the defect would be writing blanks while claiming byte identity for a row that was
     * read, which the sibling test below rules out.</p>
     */
    @Test
    @DisplayName("the single-argument encode restores every field and pads with blanks")
    void theSingleArgumentEncodeWritesBlankPadding() {
        byte[] encoded = TransactionCategoryBalanceRecordMapper.toRecord(
                TransactionCategoryBalanceRecordMapper.toEntity(referenceImage()));

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
     * <p>Assumptions: this is the assertion the parity obligation rests on. The committed expectations
     * for this record carry non-blank pads -- ASCII zeros where the update arm rewrote a seed row, and
     * low values where the create arm wrote whatever the record area held, because {@code INITIALIZE}
     * does not reach a {@code FILLER} item -- so byte identity is only reachable through this
     * overload.</p>
     */
    @Test
    @DisplayName("the source-image encode reproduces the seed record byte for byte")
    void theSourceImageEncodeIsByteIdentical() {
        byte[] source = referenceImage();

        byte[] encoded = TransactionCategoryBalanceRecordMapper.toRecord(
                TransactionCategoryBalanceRecordMapper.toEntity(source), source);

        assertThat(encoded).hasSize(RECORD_LENGTH).isEqualTo(referenceImage());
    }

    /**
     * A pad of any byte value survives the source-image round trip, low values included.
     *
     * <p>Assumptions: the low-value case is not hypothetical for this record. The zero-balance posting
     * scenario's committed expectation carries 22 low-value bytes in this span, so a restoration that
     * worked only for printable bytes would pass a single-case test and fail against that golden.</p>
     *
     * @param padByte the byte to fill the pad span with for this case
     */
    @ParameterizedTest
    @ValueSource(bytes = {(byte) '0', (byte) 0x00, (byte) ' ', (byte) 0x7f})
    @DisplayName("a pad of ASCII zeros, low values, blanks or any other byte round trips unchanged")
    void anyPadByteSurvivesTheRoundTrip(byte padByte) {
        byte[] source = referenceImage();
        Arrays.fill(source, PAD_OFFSET, RECORD_LENGTH, padByte);

        byte[] encoded = TransactionCategoryBalanceRecordMapper.toRecord(
                TransactionCategoryBalanceRecordMapper.toEntity(source), source);

        assertThat(encoded).isEqualTo(source);
    }

    /**
     * A negatively-signed zero balance keeps its own overpunch through the source-image encode.
     *
     * <p>Assumptions: this is the second region the source-image overload exists for, and it is
     * separable from the pad. An exact decimal has no signed zero, so the balance decodes to plain zero
     * either way; only the source image can say which of the two zero overpunch characters the record
     * carried. The single-argument overload is asserted to normalise it, so the two behaviours are
     * pinned side by side rather than one being assumed from the other.</p>
     */
    @Test
    @DisplayName("a negatively-signed zero keeps its overpunch only through the source-image encode")
    void aNegativeZeroKeepsItsCarrierOnlyWithTheSourceImage() {
        byte[] source = imageWithBalance("0000000000}");

        TransactionCategoryBalance row =
                TransactionCategoryBalanceRecordMapper.toEntity(source);
        assertThat(row.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(TransactionCategoryBalanceRecordMapper.toRecord(row, source))
                .as("the source-image overload restores the negative-zero carrier")
                .isEqualTo(source);
        assertThat(TransactionCategoryBalanceRecordMapper.toRecord(row)[BALANCE_OFFSET
                + BALANCE_LENGTH - 1])
                .as("the plain overload normalises it to the positive-zero carrier")
                .isEqualTo((byte) '{');
    }

    /**
     * A negative non-zero balance decodes with its sign and re-encodes to the same bytes.
     *
     * <p>Assumptions: the overpunch carries the final DIGIT as well as the sign, so a balance of -1.05
     * is spelt as nine leading digits, then a {@code 0}, then the character meaning negative five. The
     * digit-bearing nature of the overpunch is why this case is separate from the negative-zero one
     * above: a codec that handled the sign but dropped the digit would pass that test and fail this.</p>
     */
    @Test
    @DisplayName("a negative non-zero balance keeps both its sign and its final digit")
    void aNegativeBalanceKeepsItsSignAndFinalDigit() {
        byte[] source = imageWithBalance("0000000010N");

        TransactionCategoryBalance row =
                TransactionCategoryBalanceRecordMapper.toEntity(source);

        assertThat(row.getBalance()).isEqualByComparingTo(new BigDecimal("-1.05"));
        assertThat(row.getBalance().scale()).isEqualTo(2);
        assertThat(TransactionCategoryBalanceRecordMapper.toRecord(row))
                .as("the plain overload reproduces the signed span and blanks only the pad")
                .isEqualTo(imageWithBalanceAndBlankPad("0000000010N"));
        assertThat(TransactionCategoryBalanceRecordMapper.toRecord(row, source))
                .as("the source-image overload reproduces the whole record")
                .isEqualTo(source);
    }

    /**
     * Returns an image carrying the supplied balance and a blank pad, as the plain encode produces.
     *
     * @param zonedBalance the eleven zoned characters to place at the balance offset
     * @return a fresh 50-byte image with that balance, the reference key fields and a blank pad
     */
    private static byte[] imageWithBalanceAndBlankPad(String zonedBalance) {
        byte[] image = imageWithBalance(zonedBalance);
        Arrays.fill(image, PAD_OFFSET, RECORD_LENGTH, (byte) ' ');
        return image;
    }

    /**
     * The key image is the first seventeen bytes of the record, in the key's physical order.
     *
     * <p>Assumptions: the key is asserted to be a PREFIX of the whole record rather than merely to have
     * the right length, and the two are different claims. The implementation is able to write the three
     * key fields into an array of only the key's length precisely because the key offset is zero, so
     * each field's record offset is already its offset within the key; a key that began anywhere else
     * would need different code, and this assertion is what pins the property that makes it correct.</p>
     */
    @Test
    @DisplayName("the key image is the record's first seventeen bytes, in physical order")
    void theKeyImageIsTheRecordPrefix() {
        TransactionCategoryBalance row =
                TransactionCategoryBalanceRecordMapper.toEntity(referenceImage());

        byte[] key = TransactionCategoryBalanceRecordMapper.toKeyImage(row.getId());

        assertThat(TransactionCategoryBalanceRecordMapper.keyOffset()).isZero();
        assertThat(key).hasSize(TransactionCategoryBalanceRecordMapper.keyLength());
        assertThat(new String(key, StandardCharsets.UTF_8))
                .isEqualTo(REFERENCE_RECORD.substring(0,
                        TransactionCategoryBalanceRecordMapper.keyLength()));
    }

    /**
     * A record of any length other than the declared one is refused rather than decoded short.
     *
     * <p>Assumptions: a long image is exercised as well as short ones, because it is the more dangerous
     * of the two -- it decodes successfully and silently ignores the surplus, producing a plausible row
     * from a misaligned stream, whereas a short one fails visibly.</p>
     *
     * @param length the image length to offer for this case
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 17, 28, 49, 51, 100})
    @DisplayName("an image of the wrong length is refused, short or long")
    void aWrongLengthImageIsRefused(int length) {
        byte[] wrong = new byte[length];
        Arrays.fill(wrong, (byte) '0');

        assertThatThrownBy(() -> TransactionCategoryBalanceRecordMapper.toEntity(wrong))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * The source-image encode refuses an image of the wrong length before copying any span.
     *
     * <p>Assumptions: the refusal is by WIDTH rather than by an array-bounds failure, which is why the
     * implementation routes through the sign-preserving codec entry point. A bounds failure raised from
     * inside the pad copy would report an offset instead of naming the record.</p>
     */
    @Test
    @DisplayName("the source-image encode refuses a wrongly sized image by width")
    void theSourceImageEncodeRefusesAWrongWidthImage() {
        TransactionCategoryBalance row =
                TransactionCategoryBalanceRecordMapper.toEntity(referenceImage());

        assertThatThrownBy(() -> TransactionCategoryBalanceRecordMapper.toRecord(row,
                new byte[RECORD_LENGTH - 1]))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * A null entity is refused by both encode overloads.
     *
     * <p>Assumptions: both are exercised, because the shared field map means a check written in only one
     * of them would leave the other reaching the map with a null. The second call passes a well-formed
     * image deliberately, so the only thing under test is the null entity.</p>
     */
    @Test
    @DisplayName("a null entity is refused by both encode overloads")
    void aNullEntityIsRefused() {
        assertThatThrownBy(() -> TransactionCategoryBalanceRecordMapper.toRecord(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                TransactionCategoryBalanceRecordMapper.toRecord(null, referenceImage()))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * A balance wider than the descriptor's nine integer digits is refused rather than truncated.
     *
     * <p>Assumptions: refusal rather than truncation is the contract, because a silent truncation here
     * would put a wrong balance into a compared record -- and it would do so by an order of magnitude,
     * which no rounding rule can express.</p>
     *
     * <p>Refactoring Rationale: the refusal is asserted at CONSTRUCTION rather than at the encode call,
     * because that is where the invariant now lives. The entity reduces its balance through
     * {@code Money.ofPicture} against the nine integer digits {@code TRAN-CAT-BAL} declares, so an
     * over-wide value never becomes an instance and can never reach this mapper from application code.
     * Asserting it on the encoder would have required an instance that cannot be built, and asserting
     * the earlier guard is the stronger claim: a value refused at construction is refused for every
     * consumer of the entity and not only for this one.</p>
     *
     * <p>Assumptions: the encoder keeps its own span check regardless, because the persistence provider
     * materialises a stored row by field assignment and bypasses the constructor entirely. That path is
     * unreachable from a test, so the check it guards is asserted by the geometry cases above rather
     * than here.</p>
     */
    @Test
    @DisplayName("a balance too wide for its span is refused, not truncated")
    void anOverWideBalanceIsRefused() {
        assertThatThrownBy(() -> new TransactionCategoryBalance(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(
                        ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE),
                new BigDecimal("9999999999.00")))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * A category code that is not four digits is refused, so a malformed key never reaches an image.
     *
     * <p>Refactoring Rationale: the refusal is asserted at KEY CONSTRUCTION rather than on the encode
     * call, because that is where the invariant now lives. {@code TransactionCategoryBalanceId} reduces
     * its category component through an exact-width digits guard, so a malformed code never becomes a
     * key and can never reach this mapper from application code. Asserting it on the encoder would have
     * required an instance that application code cannot build, and asserting the earlier guard is the
     * stronger claim: a value refused when the key is built is refused for every consumer of the key and
     * not only for this one encode path.</p>
     *
     * <p>Assumptions: the code is part of the key, so a value of the wrong width would shift the balance
     * span and every byte after it, producing a 50-byte record that reads as a different row entirely.
     * That is why the guard refuses rather than pads: a value short on the LEFT is a different key, not
     * a shorter spelling of the same one.</p>
     *
     * @param categoryCode the malformed category code to offer for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"1", "001", "00001", "  01", "abcd", ""})
    @DisplayName("a category code that is not four digits is refused when the key is built")
    void aMalformedCategoryCodeIsRefused(String categoryCode) {
        assertThatThrownBy(() -> new TransactionCategoryBalance.TransactionCategoryBalanceId(
                ACCOUNT_ID, TYPE_CODE, categoryCode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The encoder refuses a malformed category code on the one path that bypasses the key's guard.
     *
     * <p>Assumptions: the encoder keeps its own exact-width digits check even though the key now guards
     * the same property, because the persistence provider materialises a stored row by assigning the
     * mapped components reflectively and never calls the fully specified constructor. That path is the
     * only way a malformed code can reach the encoder, and it is not reachable by constructing a value,
     * so this case reproduces it the same way the provider does -- by assigning the component onto an
     * already valid key.</p>
     *
     * <p>Alternatives Considered: dropping the encode-path claim entirely once the key began guarding
     * the width, on the grounding that the check had become unreachable. It is rejected because the
     * check is not unreachable, only unreachable from a constructor: a row hydrated from a column that
     * some future migration widened, or a row written by any producer outside this module, arrives
     * through exactly the assignment this case performs. A defence that nothing exercises is a defence
     * that can be deleted by a later reader who reasons the same way, so the claim is kept and made
     * executable rather than left as prose.</p>
     *
     * @param categoryCode the malformed category code to plant on an otherwise valid key
     * @throws ReflectiveOperationException if the mapped component cannot be assigned, which would mean
     *     the member this case simulates the provider assigning has been renamed or removed
     */
    @ParameterizedTest
    @ValueSource(strings = {"1", "001", "00001", "  01", "abcd", ""})
    @DisplayName("a hydrated key with a malformed category code is refused on the encode path")
    void aHydratedMalformedCategoryCodeIsRefusedOnEncode(String categoryCode)
            throws ReflectiveOperationException {
        TransactionCategoryBalance row = new TransactionCategoryBalance(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(
                        ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE),
                BigDecimal.ZERO.setScale(2));
        plantCategoryCode(row.getId(), categoryCode);

        assertThatThrownBy(() -> TransactionCategoryBalanceRecordMapper.toRecord(row))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Assigns a category component onto an existing key the way the persistence provider does.
     *
     * <p>Assumptions: the assignment is reflective because that is precisely how the provider populates
     * an embeddable it has hydrated, so a value planted this way reaches the encoder by the same route a
     * stored row does. Nothing in production code assigns this component after construction.</p>
     *
     * @param id the key to overwrite, which must already be a fully constructed value
     * @param categoryCode the component value to plant, which may be malformed
     * @throws ReflectiveOperationException if the mapped member cannot be found or assigned
     */
    private static void plantCategoryCode(
            TransactionCategoryBalance.TransactionCategoryBalanceId id, String categoryCode)
            throws ReflectiveOperationException {
        Field member = TransactionCategoryBalance.TransactionCategoryBalanceId.class
                .getDeclaredField("categoryCd");
        member.setAccessible(true);
        member.set(id, categoryCode);
    }
}
