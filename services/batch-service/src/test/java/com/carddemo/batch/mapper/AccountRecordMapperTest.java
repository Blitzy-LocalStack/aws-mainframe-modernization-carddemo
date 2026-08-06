package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.Account;
import com.carddemo.common.codec.FixedWidthCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the 300-byte account master boundary in both directions.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link AccountRecordMapper} had no executable consumer, so its twelve-field object-to-field map
 * rested on review alone. This class asserts every field of a real reference record in both
 * directions, the <b>asymmetric</b> trim rule that keeps the disclosure-group join key padded while
 * trimming the descriptive postal code, the absent-date form, the sign overpunch on a negative
 * balance, the trailing pad's different treatment on decode and on encode, and every documented
 * rejection.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test engine,
 * so the type itself accepts no parameter, returns nothing and throws nothing. The inapplicability is
 * stated rather than passed over, because user-specified Rule 1 (Explainability) forbids a docstring
 * that omits parameters, return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Every member below carries its own parameter, return and
 * exception at-clauses.</p>
 *
 * <h2>Assumptions: the record image is assembled from named field pieces</h2>
 *
 * <p>{@link #REFERENCE_RECORD} is built by concatenating twelve named pieces plus the pad, so a
 * reader can check each piece against the picture clause that declares it in
 * {@code app/cpy/CVACT01Y.cpy} without counting characters. The assembled value is byte-identical to
 * the first record of {@code app/data/ASCII/acctdata.txt}, which was captured and compared before
 * this constant was written down. Alternatives Considered: one 300-character literal. Rejected
 * because nobody can review it -- a single misplaced character shifts every field after it and the
 * literal still measures 300.</p>
 */
@DisplayName("AccountRecordMapper: 300-byte account master decode and encode")
class AccountRecordMapperTest {

    /** Declared record length of the account master, from {@code app/cpy/CVACT01Y.cpy}. */
    private static final int RECORD_LENGTH = 300;

    /** Zero-based offset of the current balance, whose sign overpunch several tests exercise. */
    private static final int BALANCE_OFFSET = 12;

    /** Zero-based offset of the open date, the first of the three ten-character date fields. */
    private static final int OPEN_DATE_OFFSET = 48;

    /** Zero-based offset of the expiration date. */
    private static final int EXPIRATION_DATE_OFFSET = 58;

    /** Zero-based offset of the reissue date. */
    private static final int REISSUE_DATE_OFFSET = 68;

    /** Zero-based offset of the descriptive postal code, which the decode trims. */
    private static final int ZIP_OFFSET = 102;

    /** Zero-based offset of the disclosure-group join key, whose padding the decode keeps. */
    private static final int GROUP_ID_OFFSET = 112;

    /** Zero-based offset of the trailing pad, dropped on decode and rebuilt as blanks on encode. */
    private static final int PAD_OFFSET = 122;

    /** Declared width of the trailing pad, from {@code app/cpy/CVACT01Y.cpy} line 17. */
    private static final int PAD_LENGTH = 178;

    /**
     * The first record of {@code app/data/ASCII/acctdata.txt}, assembled from named field pieces.
     *
     * <p>Assumptions: the postal-code field of this reference record holds {@code A000000000} and
     * its disclosure-group field holds ten blanks, which looks transposed and is what the shipped
     * extract actually contains. It is transcribed as it stands rather than corrected, because a
     * fixture that quietly improved the reference would stop being a reading of it.</p>
     */
    private static final String REFERENCE_RECORD =
            "00000000001"        // ACCT-ID              PIC 9(11)
            + "Y"                // ACCT-ACTIVE-STATUS   PIC X(01)
            + "00000001940{"     // ACCT-CURR-BAL        PIC S9(10)V99, +194.00
            + "00000020200{"     // ACCT-CREDIT-LIMIT    PIC S9(10)V99, +2020.00
            + "00000010200{"     // ACCT-CASH-CREDIT-LIM PIC S9(10)V99, +1020.00
            + "2014-11-20"       // ACCT-OPEN-DATE       PIC X(10)
            + "2025-05-20"       // ACCT-EXPIRAION-DATE  PIC X(10), baseline misspelling
            + "2025-05-20"       // ACCT-REISSUE-DATE    PIC X(10)
            + "00000000000{"     // ACCT-CURR-CYC-CREDIT PIC S9(10)V99, +0.00
            + "00000000000{"     // ACCT-CURR-CYC-DEBIT  PIC S9(10)V99, +0.00
            + "A000000000"       // ACCT-ADDR-ZIP        PIC X(10)
            + " ".repeat(10)     // ACCT-GROUP-ID        PIC X(10)
            + " ".repeat(PAD_LENGTH);

    /**
     * Returns the reference record as bytes.
     *
     * @return a freshly copied 300-byte image, so a mutating test cannot affect another
     */
    private static byte[] referenceImage() {
        return REFERENCE_RECORD.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns a copy of the reference record with one field span overwritten.
     *
     * <p>Assumptions: splicing an existing record is preferred to assembling a whole new one for a
     * single-field variation, because everything the variation is not about then stays demonstrably
     * unchanged.</p>
     *
     * @param offset the zero-based offset of the span to overwrite
     * @param value the replacement content, which must be exactly the span's declared width
     * @return a freshly copied 300-byte image carrying the replacement
     */
    private static byte[] spliced(int offset, String value) {
        byte[] image = referenceImage();
        byte[] replacement = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(replacement, 0, image, offset, replacement.length);
        return image;
    }

    /**
     * Asserts that the assembled reference constant is exactly the declared record length.
     */
    @Test
    @DisplayName("the assembled reference constant is exactly the declared record length")
    void theAssembledReferenceConstantIsExactlyTheDeclaredLength() {
        // WHY : Assumptions: this is asserted before any field assertion, because every offset below
        //       is meaningless if the assembly is the wrong length. Twelve named fields spanning 122
        //       bytes plus a 178-byte pad reconcile to 300, and stating both halves is what lets a
        //       failure say which half is wrong.
        assertThat(REFERENCE_RECORD).hasSize(RECORD_LENGTH);
        assertThat(PAD_OFFSET + PAD_LENGTH).isEqualTo(RECORD_LENGTH);
    }

    /**
     * Asserts that every one of the twelve mapped fields decodes to its expected value.
     */
    @Test
    @DisplayName("every one of the twelve mapped fields decodes to its expected value")
    void everyMappedFieldDecodesToItsExpectedValue() {
        Account account = AccountRecordMapper.toEntity(referenceImage());

        assertThat(account.getAccountId()).isEqualTo(1L);
        assertThat(account.getActiveStatus()).isEqualTo("Y");
        assertThat(account.getCurrBal()).isEqualByComparingTo("194.00");
        assertThat(account.getCreditLimit()).isEqualByComparingTo("2020.00");
        assertThat(account.getCashCreditLimit()).isEqualByComparingTo("1020.00");
        assertThat(account.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        assertThat(account.getExpirationDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo("0.00");
        assertThat(account.getAddrZip()).isEqualTo("A000000000");
        assertThat(account.getGroupId()).isEqualTo("          ");
    }

    /**
     * Asserts that every decoded money value carries exactly two decimal places.
     */
    @Test
    @DisplayName("every decoded money value carries exactly two decimal places")
    void everyDecodedMoneyValueCarriesScaleTwo() {
        Account account = AccountRecordMapper.toEntity(referenceImage());

        // WHY : Assumptions: the SCALE is asserted and not only the value, because a zero-valued
        //       amount compares equal at any scale. AAP transformation rule T3 requires money to stay
        //       exact fixed point at scale two end to end, and a value that arrived at scale zero
        //       would satisfy every numeric comparison in this file while emitting a differently
        //       shaped record on the way back out.
        assertThat(Stream.of(account.getCurrBal(), account.getCreditLimit(),
                        account.getCashCreditLimit(), account.getCurrCycCredit(),
                        account.getCurrCycDebit()).map(BigDecimal::scale).distinct().toList())
                .containsExactly(2);
    }

    /**
     * Asserts that the join key keeps its padding while the descriptive postal code is trimmed.
     */
    @Test
    @DisplayName("the join key keeps its padding while the descriptive postal code is trimmed")
    void theJoinKeyKeepsItsPaddingWhileThePostalCodeIsTrimmed() {
        byte[] image = spliced(ZIP_OFFSET, "43215     ");
        System.arraycopy("DEFAULT   ".getBytes(StandardCharsets.UTF_8), 0, image, GROUP_ID_OFFSET,
                10);

        Account account = AccountRecordMapper.toEntity(image);

        // WHY : Assumptions: the two adjacent PIC X(10) fields are asserted TOGETHER, because the
        //       whole point is that they are treated differently. ACCT-GROUP-ID keeps all ten
        //       characters because app/cbl/CBACT04C.cbl:437 moves the literal 'DEFAULT' into a ten-
        //       character field, so the stored fallback key is DEFAULT followed by three blanks and
        //       the disclosure-group rate lookup matches only if that padding survives. Trimming it
        //       would silently lose the fallback rate for every account in the default group -- and
        //       asserting each field in its own test would let a change unify the rule without
        //       either test noticing which of the two it broke.
        assertThat(account.getGroupId()).isEqualTo("DEFAULT   ").hasSize(10);
        assertThat(account.getAddrZip()).isEqualTo("43215");
        assertThat(AccountRecordMapper.toRecord(account)).isEqualTo(image);
    }

    /**
     * Asserts that a blank date field decodes as absent rather than raising.
     */
    @Test
    @DisplayName("a blank date field decodes as absent rather than raising")
    void aBlankDateFieldDecodesAsAbsent() {
        byte[] image = spliced(OPEN_DATE_OFFSET, " ".repeat(10));
        System.arraycopy(" ".repeat(20).getBytes(StandardCharsets.UTF_8), 0, image,
                EXPIRATION_DATE_OFFSET, 20);

        Account account = AccountRecordMapper.toEntity(image);

        assertThat(account.getOpenDate()).isNull();
        assertThat(account.getExpirationDate()).isNull();
        assertThat(account.getReissueDate()).isNull();
        assertThat(AccountRecordMapper.toRecord(account)).isEqualTo(image);
    }

    /**
     * Asserts that a negative balance is decoded through its sign overpunch and re-encoded to it.
     */
    @Test
    @DisplayName("a negative balance is decoded through its sign overpunch and re-encoded to it")
    void aNegativeBalanceRoundTripsThroughItsSignOverpunch() {
        byte[] image = spliced(BALANCE_OFFSET, "00000001940}");

        Account account = AccountRecordMapper.toEntity(image);

        // WHY : Assumptions: the negative case is asserted separately from the positive one because
        //       the two differ in ONE byte, the low-order sign carrier, and nothing about the digit
        //       body distinguishes them. The reference test suite records that compiling with the
        //       default ASCII sign convention silently corrupts negative balances, so a decoder that
        //       read the carrier as a digit would return a positive value of the same magnitude and
        //       every magnitude assertion elsewhere in this file would still pass.
        assertThat(account.getCurrBal()).isEqualByComparingTo("-194.00");
        assertThat(AccountRecordMapper.toRecord(account)).isEqualTo(image);
    }

    /**
     * Asserts that a blank-padded reference record round-trips byte for byte.
     */
    @Test
    @DisplayName("a blank-padded reference record round-trips byte for byte")
    void aBlankPaddedReferenceRecordRoundTripsByteForByte() {
        byte[] image = referenceImage();

        assertThat(AccountRecordMapper.toRecord(AccountRecordMapper.toEntity(image)))
                .isEqualTo(image)
                .hasSize(RECORD_LENGTH);
    }

    /**
     * Asserts that the trailing pad is dropped on decode and rebuilt as blanks on encode.
     */
    @Test
    @DisplayName("the trailing pad is dropped on decode and rebuilt as blanks on encode")
    void theTrailingPadIsDroppedOnDecodeAndRebuiltAsBlanksOnEncode() {
        byte[] withDataInPad = spliced(PAD_OFFSET, "X".repeat(PAD_LENGTH));

        byte[] encoded = AccountRecordMapper.toRecord(AccountRecordMapper.toEntity(withDataInPad));

        // WHY : Assumptions: the round trip is deliberately NOT byte-identical here, and asserting
        //       the difference is what documents the asymmetry as intended rather than as a defect.
        //       The decode drops the pad because it is padding to the declared record length and not
        //       data, so the entity carries no property that could hold it; the encode rebuilds it
        //       with the charset's blank byte rather than leaving a fresh array's zero bytes, because
        //       a COBOL reader distinguishes low values from spaces even though both look empty once
        //       read into a string.
        assertThat(encoded).hasSize(RECORD_LENGTH);
        assertThat(encoded).isNotEqualTo(withDataInPad);
        assertThat(new String(encoded, StandardCharsets.UTF_8).substring(PAD_OFFSET))
                .isEqualTo(" ".repeat(PAD_LENGTH));
        assertThat(Arrays.copyOf(encoded, PAD_OFFSET))
                .isEqualTo(Arrays.copyOf(withDataInPad, PAD_OFFSET));
    }

    /**
     * Asserts that an entity with absent character values encodes and decodes without raising.
     */
    @Test
    @DisplayName("an entity with absent character values encodes and decodes without raising")
    void anEntityWithAbsentCharacterValuesEncodesToBlanks() {
        Account sparse = new Account(1L, null, new BigDecimal("1.00"), new BigDecimal("1.00"),
                new BigDecimal("1.00"), null, null, null, new BigDecimal("1.00"),
                new BigDecimal("1.00"), null, null);

        byte[] encoded = AccountRecordMapper.toRecord(sparse);

        // WHY : Assumptions: an absent CHARACTER value is encoded as blanks while an absent MONEY
        //       value is refused, and the two are asserted in adjacent tests for that contrast. A
        //       character field has a blank form the baseline itself writes; a signed zoned span has
        //       no absent form at all, so defaulting one to zero would invent a balance.
        assertThat(encoded).hasSize(RECORD_LENGTH);
        assertThat(AccountRecordMapper.toEntity(encoded).getActiveStatus()).isEqualTo(" ");
        assertThat(AccountRecordMapper.toEntity(encoded).getAddrZip()).isEmpty();
        assertThat(AccountRecordMapper.toEntity(encoded).getGroupId()).isEqualTo(" ".repeat(10));
        assertThat(AccountRecordMapper.toEntity(encoded).getOpenDate()).isNull();
    }

    /**
     * Asserts that an absent money value is refused rather than defaulted to zero.
     */
    @Test
    @DisplayName("an absent money value is refused rather than defaulted to zero")
    void anAbsentMoneyValueIsRefused() {
        Account noBalance = new Account(1L, "Y", null, new BigDecimal("1.00"),
                new BigDecimal("1.00"), null, null, null, new BigDecimal("1.00"),
                new BigDecimal("1.00"), "", "");

        assertThatThrownBy(() -> AccountRecordMapper.toRecord(noBalance))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("ACCT-CURR-BAL");
    }

    /**
     * Supplies each documented decode rejection with the diagnostic it must produce.
     *
     * @return a stream of case label, the spliced image and the exception type expected
     */
    private static Stream<Arguments> decodeRejections() {
        return Stream.of(
                Arguments.of("a null image", (Executable) () -> AccountRecordMapper.toEntity(null),
                        FixedWidthCodec.RecordLengthException.class, "expected 300 bytes"),
                Arguments.of("an image one byte short",
                        (Executable) () -> AccountRecordMapper
                                .toEntity(Arrays.copyOf(referenceImage(), RECORD_LENGTH - 1)),
                        FixedWidthCodec.RecordLengthException.class, "received 299"),
                Arguments.of("an image one byte long",
                        (Executable) () -> AccountRecordMapper
                                .toEntity(Arrays.copyOf(referenceImage(), RECORD_LENGTH + 1)),
                        FixedWidthCodec.RecordLengthException.class, "received 301"),
                Arguments.of("a non-calendar date",
                        (Executable) () -> AccountRecordMapper
                                .toEntity(spliced(OPEN_DATE_OFFSET, "2014-13-20")),
                        IllegalArgumentException.class, "ACCT-OPEN-DATE"),
                Arguments.of("a non-digit in a money span",
                        (Executable) () -> AccountRecordMapper
                                .toEntity(spliced(BALANCE_OFFSET, "0000000194X0")),
                        FixedWidthCodec.FieldCodecException.class, "ACCT-CURR-BAL"));
    }

    /**
     * Asserts that each documented decode rejection raises the diagnostic it claims.
     *
     * @param label a short description of the case, shown in the case name
     * @param invocation the decode that must be refused
     * @param expected the exception type the mapper documents for this case
     * @param fragment a fragment the diagnostic must contain, so the failure names the cause
     */
    @ParameterizedTest(name = "the decode refuses {0}")
    @MethodSource("decodeRejections")
    @DisplayName("each documented decode rejection raises the diagnostic it claims")
    void eachDocumentedDecodeRejectionRaises(String label, Executable invocation,
            Class<? extends Throwable> expected, String fragment) {
        assertThatThrownBy(invocation::execute).isInstanceOf(expected)
                .hasMessageContaining(fragment);
    }

    /**
     * Supplies each documented encode rejection with the diagnostic it must produce.
     *
     * @return a stream of case label, the encode that must be refused and a diagnostic fragment
     */
    private static Stream<Arguments> encodeRejections() {
        return Stream.of(
                Arguments.of("a null entity",
                        (Executable) () -> AccountRecordMapper.toRecord(null),
                        "account must not be null"),
                Arguments.of("an amount needing an eleventh integer digit",
                        (Executable) () -> AccountRecordMapper.toRecord(
                                new Account(1L, "Y", new BigDecimal("99999999999.99"),
                                        new BigDecimal("1.00"), new BigDecimal("1.00"), null, null,
                                        null, new BigDecimal("1.00"), new BigDecimal("1.00"), "",
                                        "")),
                        "13 digit positions"),
                Arguments.of("an amount carrying a third decimal place",
                        (Executable) () -> AccountRecordMapper.toRecord(
                                new Account(1L, "Y", new BigDecimal("1.234"),
                                        new BigDecimal("1.00"), new BigDecimal("1.00"), null, null,
                                        null, new BigDecimal("1.00"), new BigDecimal("1.00"), "",
                                        "")),
                        "more than 2 decimal places"));
    }

    /**
     * Asserts that each documented encode rejection raises rather than truncating or rounding.
     *
     * @param label a short description of the case, shown in the case name
     * @param invocation the encode that must be refused
     * @param fragment a fragment the diagnostic must contain
     */
    @ParameterizedTest(name = "the encode refuses {0}")
    @MethodSource("encodeRejections")
    @DisplayName("each documented encode rejection raises rather than truncating or rounding")
    void eachDocumentedEncodeRejectionRaises(String label, Executable invocation, String fragment) {
        // WHY : Assumptions: an over-wide or over-precise amount must RAISE and not be silently
        //       narrowed. A rounding decision is a business decision that belongs at the service
        //       layer where an audit trail can show it, and a truncated amount emitted from an
        //       encoder is the plausible-number-that-is-wrong the money contract exists to prevent.
        assertThatThrownBy(invocation::execute)
                .isInstanceOfAny(FixedWidthCodec.FieldCodecException.class,
                        NullPointerException.class)
                .hasMessageContaining(fragment);
    }

    /**
     * Asserts that a four-digit year at the top of the range encodes into the ten-character span.
     */
    @Test
    @DisplayName("a four-digit year at the top of the range encodes into the ten-character span")
    void aFourDigitYearAtTheTopOfTheRangeEncodes() {
        Account account = new Account(1L, "Y", new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"), LocalDate.of(9999, 12, 31), null, null,
                new BigDecimal("0.00"), new BigDecimal("0.00"), "", "");

        String encoded = new String(AccountRecordMapper.toRecord(account), StandardCharsets.UTF_8);

        assertThat(encoded.substring(OPEN_DATE_OFFSET, OPEN_DATE_OFFSET + 10))
                .isEqualTo("9999-12-31");
        assertThat(encoded.substring(EXPIRATION_DATE_OFFSET, REISSUE_DATE_OFFSET))
                .isEqualTo(" ".repeat(10));
    }
}
