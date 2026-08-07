package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.common.codec.FixedWidthCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the 350-byte daily-transaction boundary in both directions, pad, sign and forms included.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link DailyTransactionMapper} had no executable consumer, so every value its decode yields and
 * every byte its two encode overloads produce could have changed with the whole suite staying green.
 * That matters for this record more than for most, because it is the record the posting chain reads:
 * its amount decides reason code 102, the first ten characters of its originating stamp decide reason
 * code 103, and its card number is the cross-reference lookup key. This class asserts all thirteen
 * mapped fields of a REAL seed record in both directions, the three regions where the two encode
 * overloads deliberately differ, the projection onto a posted transaction and its determinism, and
 * every documented rejection.</p>
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
 * <p>The record below is the FIRST 350 bytes of {@code app/data/ASCII/dailytran.txt}, read span by span
 * against {@code app/cpy/CVTRA06Y.cpy}. Its amount at bytes 132 to 142 is the ten digits
 * {@code 0000005047} followed by a capital G, and the zoned overpunch G means positive SEVEN, so the
 * amount is 504.77 and not 504.70. That one character is why the fixture is transcribed from the seed
 * rather than invented: an amount composed by hand would almost certainly have ended in a plain digit
 * and would therefore have exercised none of the sign handling a wrong compiler or codec setting
 * corrupts silently.</p>
 *
 * <h2>Assumptions: this record's pad is BLANK, unlike its two siblings</h2>
 *
 * <p>The 20-byte trailing pad of every record of this extract is blanks, whereas the disclosure-group
 * and category-balance extracts pad with the ASCII zero character. A pad-parity case written against
 * this record therefore has to write a non-blank pad into the source image first, because a round trip
 * over the seed record's own blank pad would pass whether the bytes were restored from the image or
 * rebuilt from nothing -- a green assertion proving neither.</p>
 */
@DisplayName("DailyTransactionMapper: 350-byte feed record, both encode overloads, and the projection")
class DailyTransactionMapperTest {

    /** Declared record length, from {@code app/cpy/CVTRA06Y.cpy} and both readers' file descriptions. */
    private static final int RECORD_LENGTH = 350;

    /** Zero-based offset of the amount span, {@code DALYTRAN-AMT PIC S9(09)V99}. */
    private static final int AMOUNT_OFFSET = 132;

    /** Declared width of the amount span: eleven zoned characters for nine integer and two decimals. */
    private static final int AMOUNT_LENGTH = 11;

    /** Zero-based offset of the originating timestamp span. */
    private static final int ORIG_TS_OFFSET = 278;

    /** Zero-based offset of the processing timestamp span. */
    private static final int PROC_TS_OFFSET = 304;

    /** Declared width of each timestamp span. */
    private static final int TIMESTAMP_LENGTH = 26;

    /** Zero-based offset of the trailing pad, from {@code app/cpy/CVTRA06Y.cpy}. */
    private static final int PAD_OFFSET = 330;

    /** Declared width of the trailing pad. */
    private static final int PAD_LENGTH = 20;

    /** The sixteen-character transaction identifier the first seed record carries. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The sixteen-character primary account number the first seed record carries. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** The originating stamp the first seed record carries, in the target 26-character form. */
    private static final String ORIG_TS_TARGET_FORM = "2022-06-10 19:27:53.000000";

    /**
     * The same instant in the baseline producer's own 26-character form.
     *
     * <p>Assumptions: the two forms differ at one-based position 11, where this one carries a third
     * hyphen where the target carries a space, and at positions 14 and 17, where it carries a dot where
     * the target carries a colon. They denote the same instant and are not interchangeable as bytes,
     * which is the whole subject of the restoration case below.</p>
     */
    private static final String ORIG_TS_BASELINE_FORM = "2022-06-10-19.27.53.000000";

    /** The instant both timestamp forms above denote. */
    private static final LocalDateTime ORIG_TS_VALUE = LocalDateTime.parse("2022-06-10T19:27:53");

    /** The first record of {@code app/data/ASCII/dailytran.txt}, transcribed span by span. */
    private static final String REFERENCE_RECORD =
            TRANSACTION_ID                                  // DALYTRAN-ID            X(16)
            + "01"                                          // DALYTRAN-TYPE-CD       X(02)
            + "0001"                                        // DALYTRAN-CAT-CD        9(04)
            + "POS TERM  "                                  // DALYTRAN-SOURCE        X(10)
            + pad("Purchase at Abshire-Lowe", 100)          // DALYTRAN-DESC          X(100)
            + "0000005047G"                                 // DALYTRAN-AMT    S9(09)V99, +504.77
            + "800000000"                                   // DALYTRAN-MERCHANT-ID   9(09)
            + pad("Abshire-Lowe", 50)                       // DALYTRAN-MERCHANT-NAME X(50)
            + pad("North Enoshaven", 50)                    // DALYTRAN-MERCHANT-CITY X(50)
            + pad("72112", 10)                              // DALYTRAN-MERCHANT-ZIP  X(10)
            + CARD_NUMBER                                   // DALYTRAN-CARD-NUM      X(16)
            + ORIG_TS_TARGET_FORM                           // DALYTRAN-ORIG-TS       X(26)
            + " ".repeat(TIMESTAMP_LENGTH)                  // DALYTRAN-PROC-TS       X(26), blank
            + " ".repeat(PAD_LENGTH);                       // FILLER                 X(20)

    /**
     * Right-pads a value with blanks to a declared field width.
     *
     * @param value the content the field carries
     * @param width the declared width of the field
     * @return the value followed by enough blanks to reach {@code width}
     */
    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Returns the reference record as a fresh byte array.
     *
     * @return a newly allocated 350-byte image, so a case that mutates it cannot affect another
     */
    private static byte[] referenceImage() {
        return REFERENCE_RECORD.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Overwrites one span of an image with the supplied characters.
     *
     * @param image the byte array to modify in place, of the declared record length
     * @param offset the zero-based offset of the span to overwrite
     * @param replacement the characters to write, whose length is the span's declared width
     * @return the same array, returned so a caller can modify and pass in one expression
     */
    private static byte[] withSpan(byte[] image, int offset, String replacement) {
        byte[] bytes = replacement.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, image, offset, bytes.length);
        return image;
    }

    /**
     * Reads one span of an image back as characters.
     *
     * @param image the byte array to read, of the declared record length
     * @param offset the zero-based offset of the span
     * @param length the declared width of the span
     * @return the characters the span holds
     */
    private static String spanOf(byte[] image, int offset, int length) {
        return new String(image, offset, length, StandardCharsets.US_ASCII);
    }

    /**
     * Builds a feed entity whose thirteen members are the reference record's, with two substitutions.
     *
     * <p>Assumptions: the entity is immutable and offers no mutator, so a case wanting a different
     * timestamp constructs a whole record rather than setting a field. That is the type's own design --
     * every value is stored exactly as supplied and nothing is defaulted -- so this helper exists to
     * keep each case's substitution visible rather than buried in thirteen repeated arguments.</p>
     *
     * @param origTs the originating stamp the entity should carry, which may be {@code null}
     * @param procTs the processing stamp the entity should carry, which may be {@code null}
     * @return a {@link DailyTransaction} equal to the reference record except in its two stamps
     */
    private static DailyTransaction referenceEntity(LocalDateTime origTs, LocalDateTime procTs) {
        return new DailyTransaction(TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("504.77"), 800000000L,
                "Abshire-Lowe", "North Enoshaven", "72112", CARD_NUMBER, origTs, procTs);
    }

    /**
     * The transcribed fixture is exactly the declared record length.
     *
     * <p>Assumptions: asserted first and on its own, because every other case reads offsets into it. A
     * fixture one character short would shift every span after the mistake, and the resulting failures
     * would point at whichever field happened to follow rather than at the fixture.</p>
     */
    @Test
    @DisplayName("the transcribed fixture is exactly 350 bytes")
    void theFixtureIsTheDeclaredLength() {
        assertThat(REFERENCE_RECORD).hasSize(RECORD_LENGTH).startsWith(TRANSACTION_ID);
        assertThat(spanOf(referenceImage(), AMOUNT_OFFSET, AMOUNT_LENGTH)).isEqualTo("0000005047G");
        assertThat(spanOf(referenceImage(), PAD_OFFSET, PAD_LENGTH)).isBlank();
    }

    /**
     * The decode yields all thirteen mapped fields, amount exact and each stamp read or left absent.
     *
     * <p>Assumptions: the amount's SCALE is asserted as well as its value, because an exact decimal
     * compares by scale as well as by value and every comparison downstream of here is an exact-decimal
     * comparison. A value that arrived scale-stripped would compare unequal to an otherwise identical
     * amount and the difference would be invisible in a rendering of either.</p>
     *
     * <p>Assumptions: the blank processing span decodes to absent rather than to an epoch or a
     * zero-filled instant. The feed leaves it blank on every record because posting is what stamps it,
     * so absent is the correct reading and any substituted instant would make an unposted record
     * indistinguishable from a posted one.</p>
     *
     * <p>Assumptions: the trim rule is asymmetric across adjacent character fields and the asymmetry is
     * asserted rather than smoothed over. The identifier, the type code and the card number keep their
     * full declared width because each is compared at a fixed width against a counterpart that is
     * itself blank-padded; the five descriptive fields are trimmed because nothing compares them.</p>
     */
    @Test
    @DisplayName("decodes all thirteen mapped fields of the first seed record")
    void decodesTheReferenceRecord() {
        DailyTransaction row = DailyTransactionMapper.toEntity(referenceImage());

        assertThat(row.getTransactionId()).isEqualTo(TRANSACTION_ID).hasSize(16);
        assertThat(row.getTypeCd()).isEqualTo("01").hasSize(2);
        assertThat(row.getCategoryCd()).isEqualTo("0001").hasSize(4);
        assertThat(row.getSource()).isEqualTo("POS TERM");
        assertThat(row.getDescription()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(row.getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(row.getAmount().scale()).isEqualTo(2);
        assertThat(row.getMerchantId()).isEqualTo(800000000L);
        assertThat(row.getMerchantName()).isEqualTo("Abshire-Lowe");
        assertThat(row.getMerchantCity()).isEqualTo("North Enoshaven");
        assertThat(row.getMerchantZip()).isEqualTo("72112");
        assertThat(row.getCardNum()).isEqualTo(CARD_NUMBER).hasSize(16);
        assertThat(row.getOrigTs()).isEqualTo(ORIG_TS_VALUE);
        assertThat(row.getProcTs()).as("the feed leaves this span blank; posting stamps it").isNull();
        assertThat(row.getIngestSeq()).as("no ordinal on an instance that did not come from a query")
                .isNull();
    }

    /**
     * The amount's overpunched final character carries its own digit, not merely its sign.
     *
     * <p>Assumptions: asserted as a case of its own rather than folded into the field walk above,
     * because it is the single most consequential character in the record and the easiest to get wrong.
     * A codec that read the sign and discarded the digit would yield 504.70 from these same bytes -- a
     * plausible amount, seven cents adrift -- and every other assertion in this class would still
     * pass.</p>
     */
    @Test
    @DisplayName("the amount overpunch carries its final digit as well as its sign")
    void theAmountOverpunchCarriesItsDigit() {
        assertThat(DailyTransactionMapper.toEntity(referenceImage()).getAmount())
                .isEqualByComparingTo(new BigDecimal("504.77"))
                .isNotEqualByComparingTo(new BigDecimal("504.70"));
    }

    /**
     * A negative amount decodes with both its sign and its digit, and re-encodes to the same span.
     *
     * <p>Assumptions: the negative overpunch is digit-bearing too, so negative 504.77 ends in a capital
     * P, which means negative seven. The negative direction is exercised separately because the sign is
     * what a wrong compiler setting flips, and a flipped sign moves an amount from the debit bucket to
     * the credit bucket while leaving every digit of it correct.</p>
     */
    @Test
    @DisplayName("a negative amount keeps both its sign and its final digit")
    void aNegativeAmountKeepsItsSignAndDigit() {
        byte[] source = withSpan(referenceImage(), AMOUNT_OFFSET, "0000005047P");

        DailyTransaction row = DailyTransactionMapper.toEntity(source);

        assertThat(row.getAmount()).isEqualByComparingTo(new BigDecimal("-504.77"));
        assertThat(DailyTransactionMapper.toRecord(row)).isEqualTo(source);
        assertThat(DailyTransactionMapper.toRecord(row, source)).isEqualTo(source);
    }

    /**
     * The decode hands the entity back beside the image, and the image is a defensive copy.
     *
     * <p>Assumptions: the copy is proved by MUTATING the returned array and reading it again, which is
     * the only way to distinguish a copy from the field itself. It matters because that image is what a
     * caller feeds into the byte-preserving encode and what a reject record carries forward: a caller
     * assembling a reject in place would otherwise silently alter what the next caller reads as the
     * verbatim record.</p>
     */
    @Test
    @DisplayName("the decode returns the entity beside a defensive copy of the source image")
    void theDecodeReturnsADefensiveImageCopy() {
        DailyTransactionMapper.DecodedRecord decoded =
                DailyTransactionMapper.decode(referenceImage());

        assertThat(decoded.entity().getTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(decoded.sourceImage()).isEqualTo(referenceImage());

        byte[] handedOut = decoded.sourceImage();
        Arrays.fill(handedOut, (byte) '#');

        assertThat(decoded.sourceImage())
                .as("mutating a handed-out image does not alter what the decode reports")
                .isEqualTo(referenceImage());
    }

    /**
     * Both encode overloads reproduce the seed record, because the seed record is already canonical.
     *
     * <p>Assumptions: this is asserted for both overloads together to establish the baseline the three
     * cases after it depart from. The seed record's pad is blank, its amount is non-zero and its
     * originating stamp is already in the target form, so all three of the regions the byte-preserving
     * overload exists to restore happen to agree with a fresh rendering here. Every case that follows
     * breaks exactly one of those three agreements, which is what makes the two overloads separable at
     * all.</p>
     */
    @Test
    @DisplayName("both encode overloads reproduce the canonical seed record byte for byte")
    void bothOverloadsReproduceTheCanonicalRecord() {
        byte[] source = referenceImage();
        DailyTransaction row = DailyTransactionMapper.toEntity(source);

        assertThat(DailyTransactionMapper.toRecord(row)).isEqualTo(referenceImage());
        assertThat(DailyTransactionMapper.toRecord(row, source)).isEqualTo(referenceImage());
    }

    /**
     * A non-blank pad survives the byte-preserving encode and is rebuilt as blanks by the plain one.
     *
     * <p>Assumptions: the pad is written explicitly rather than taken from the seed, because this
     * extract's own pad is blank -- so a round trip over it would pass whether the bytes were restored
     * from the image or rebuilt from nothing. Writing a non-blank pad is what makes the assertion
     * discriminating, and it is not a contrived value: the disclosure-group and category-balance
     * extracts really do pad with the ASCII zero character, so a record whose pad carries content is
     * the norm across this family rather than the exception.</p>
     *
     * @param padByte the byte to fill the pad span with for this case, kept inside the seven-bit range
     *     the shared codec's byte-reversibility check accepts
     */
    @ParameterizedTest
    @ValueSource(bytes = {(byte) '0', (byte) '*', (byte) 0x00})
    @DisplayName("a non-blank pad is preserved by one overload and blanked by the other")
    void aNonBlankPadIsPreservedOnlyByTheImageOverload(byte padByte) {
        byte[] source = referenceImage();
        Arrays.fill(source, PAD_OFFSET, RECORD_LENGTH, padByte);
        DailyTransaction row = DailyTransactionMapper.toEntity(source);

        assertThat(DailyTransactionMapper.toRecord(row, source))
                .as("the byte-preserving overload restores the pad the source held")
                .isEqualTo(source);
        assertThat(spanOf(DailyTransactionMapper.toRecord(row), PAD_OFFSET, PAD_LENGTH))
                .as("the plain overload rebuilds the pad as blanks")
                .isBlank();
    }

    /**
     * A signed-zero carrier survives the byte-preserving encode and is normalised by the plain one.
     *
     * <p>Assumptions: a zero amount is the one value whose sign the shared zoned codec cannot
     * reconstruct from the number, because an exact decimal has no negative zero -- so the negative
     * zero overpunch, the closing brace, is information that exists only in the bytes. That is not a
     * hypothetical span: a reversal netting to nothing writes it, and a staged generation compared byte
     * for byte against its input would differ in that single character.</p>
     */
    @Test
    @DisplayName("a negative-zero sign carrier is preserved by one overload and normalised by the other")
    void aSignedZeroCarrierIsPreservedOnlyByTheImageOverload() {
        byte[] source = withSpan(referenceImage(), AMOUNT_OFFSET, "0000000000}");
        DailyTransaction row = DailyTransactionMapper.toEntity(source);

        assertThat(row.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(spanOf(DailyTransactionMapper.toRecord(row, source), AMOUNT_OFFSET, AMOUNT_LENGTH))
                .as("the byte-preserving overload restores the carrier the source held")
                .isEqualTo("0000000000}");
        assertThat(spanOf(DailyTransactionMapper.toRecord(row), AMOUNT_OFFSET, AMOUNT_LENGTH))
                .as("the plain overload emits the canonical positive-zero carrier")
                .isEqualTo("0000000000{");
    }

    /**
     * A stamp in the baseline producer's form is kept as bytes by one overload and rewritten by the
     * other.
     *
     * <p>Assumptions: this is the case that proves the restoration compares VALUES rather than bytes.
     * The two 26-character forms denote the same instant and share not one of positions 11, 14 and 17,
     * so a byte comparison would never find them equal and the restoration would never fire -- every
     * record written in the baseline form would come back rewritten. Comparing the parsed values
     * distinguishes "same instant, different form", which keeps the source bytes, from "different
     * instant", which does not.</p>
     */
    @Test
    @DisplayName("an unchanged stamp in the baseline form keeps its own bytes")
    void anUnchangedBaselineFormStampKeepsItsBytes() {
        byte[] source = withSpan(referenceImage(), ORIG_TS_OFFSET, ORIG_TS_BASELINE_FORM);
        DailyTransaction row = DailyTransactionMapper.toEntity(source);

        assertThat(row.getOrigTs()).as("both producer forms parse to the same instant")
                .isEqualTo(ORIG_TS_VALUE);
        assertThat(DailyTransactionMapper.toRecord(row, source))
                .as("the byte-preserving overload keeps the form the source used")
                .isEqualTo(source);
        assertThat(spanOf(DailyTransactionMapper.toRecord(row), ORIG_TS_OFFSET, TIMESTAMP_LENGTH))
                .as("the plain overload renders the target form")
                .isEqualTo(ORIG_TS_TARGET_FORM);
    }

    /**
     * A CHANGED stamp is rendered from the entity instead of restored from the image.
     *
     * <p>Assumptions: this is the other half of the restoration contract, and it is what keeps the
     * byte-preserving overload honest. Copying each span unconditionally would make that overload
     * ignore the entity's own value, so a caller that had supplied a processing stamp would emit the
     * blank span the feed carried -- a record that matched its source and said something false. The
     * case therefore stamps a value the image does not hold and requires the output to carry it, while
     * the untouched originating span still comes from the image.</p>
     */
    @Test
    @DisplayName("a changed stamp is rendered from the entity, not restored from the image")
    void aChangedStampIsRenderedRatherThanRestored() {
        byte[] source = withSpan(referenceImage(), ORIG_TS_OFFSET, ORIG_TS_BASELINE_FORM);
        DailyTransaction row =
                referenceEntity(ORIG_TS_VALUE, LocalDateTime.parse("2022-07-18T10:15:30.123456"));

        byte[] encoded = DailyTransactionMapper.toRecord(row, source);

        assertThat(spanOf(encoded, PROC_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo("2022-07-18 10:15:30.123456");
        assertThat(spanOf(encoded, ORIG_TS_OFFSET, TIMESTAMP_LENGTH))
                .as("the unchanged originating stamp still comes from the image")
                .isEqualTo(ORIG_TS_BASELINE_FORM);
    }

    /**
     * An absent stamp encodes as 26 blanks, closing the loop with the decode direction.
     *
     * <p>Assumptions: the pair of conversions is the record's representation of an unposted row rather
     * than a tolerance for missing data. Emitting anything else -- a rendered epoch, a zero-filled span
     * -- would make an unposted row read as posted on the next decode.</p>
     */
    @Test
    @DisplayName("an absent stamp encodes as 26 blanks in both overloads")
    void anAbsentStampEncodesAsBlanks() {
        DailyTransaction row = referenceEntity(ORIG_TS_VALUE, null);

        assertThat(spanOf(DailyTransactionMapper.toRecord(row), PROC_TS_OFFSET, TIMESTAMP_LENGTH))
                .isBlank();
        assertThat(spanOf(DailyTransactionMapper.toRecord(row, referenceImage()),
                PROC_TS_OFFSET, TIMESTAMP_LENGTH)).isBlank();
        assertThat(DailyTransactionMapper.toEntity(DailyTransactionMapper.toRecord(row)).getProcTs())
                .as("a blank span decodes back to absent")
                .isNull();
    }

    /**
     * The projection copies twelve fields, stamps the supplied instant, and is deterministic.
     *
     * <p>Assumptions: determinism is proved by calling the projection TWICE with the same arguments and
     * comparing the results, which is the property a golden-master comparison depends on. The baseline
     * reads a clock at this point; the projection takes the instant as a parameter precisely so a rerun
     * reproduces its output, and this case is what would fail if a clock read were reintroduced.</p>
     *
     * <p>Assumptions: the feed record deliberately carries a processing stamp of its own here, so the
     * case can require that it is DROPPED rather than carried. The baseline's field map never reads that
     * span -- it copies the originating stamp and then overwrites the processing stamp with a freshly
     * minted one -- and a projection that carried it would emit whatever an upstream system had left
     * there.</p>
     */
    @Test
    @DisplayName("the projection copies twelve fields, drops the feed's own stamp, and is deterministic")
    void theProjectionCopiesTwelveFieldsAndStampsTheSuppliedInstant() {
        LocalDateTime carriedByTheFeed = LocalDateTime.parse("2021-01-01T00:00:00");
        LocalDateTime postingInstant = LocalDateTime.parse("2022-07-18T10:15:30.123456");
        DailyTransaction row = referenceEntity(ORIG_TS_VALUE, carriedByTheFeed);

        Transaction posted = DailyTransactionMapper.toPostedTransaction(row, postingInstant);
        Transaction again = DailyTransactionMapper.toPostedTransaction(row, postingInstant);

        assertThat(posted.getTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(posted.getTypeCd()).isEqualTo("01");
        assertThat(posted.getCategoryCd()).isEqualTo("0001");
        assertThat(posted.getSource()).isEqualTo("POS TERM");
        assertThat(posted.getDescription()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(posted.getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(posted.getMerchantId()).isEqualTo(800000000L);
        assertThat(posted.getMerchantName()).isEqualTo("Abshire-Lowe");
        assertThat(posted.getMerchantCity()).isEqualTo("North Enoshaven");
        assertThat(posted.getMerchantZip()).isEqualTo("72112");
        assertThat(posted.getCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(posted.getOrigTs()).as("the originating stamp comes from the feed record")
                .isEqualTo(ORIG_TS_VALUE);
        assertThat(posted.getProcTs()).as("the processing stamp is the supplied instant")
                .isEqualTo(postingInstant)
                .isNotEqualTo(carriedByTheFeed);
        assertThat(again.getProcTs()).isEqualTo(posted.getProcTs());
        assertThat(again.getOrigTs()).isEqualTo(posted.getOrigTs());
    }

    /**
     * The projection reduces the supplied instant to the precision the target form carries.
     *
     * <p>Assumptions: the target 26-character form holds six fractional digits, so a nanosecond-precise
     * instant does not survive a round trip through it and a persisted row would disagree with its own
     * rendered image in the last three digits. The reduction is asserted here rather than assumed
     * because it is the caller's instant that is reduced, silently, and a caller comparing what it
     * passed against what came back is the only party positioned to notice.</p>
     */
    @Test
    @DisplayName("the projection reduces the supplied instant to microsecond precision")
    void theProjectionReducesToMicroseconds() {
        Transaction posted = DailyTransactionMapper.toPostedTransaction(
                referenceEntity(ORIG_TS_VALUE, null),
                LocalDateTime.parse("2022-07-18T10:15:30.123456789"));

        assertThat(posted.getProcTs()).isEqualTo(LocalDateTime.parse("2022-07-18T10:15:30.123456"));
    }

    /**
     * The projection refuses an absent instant and an absent feed record rather than defaulting either.
     *
     * <p>Assumptions: refusal rather than a default is the documented contract, because both tempting
     * defaults are wrong. Defaulting the instant to the current one reintroduces the clock read the
     * contract exists to keep out; defaulting it to absent emits a posted row with no processing stamp,
     * which the column declares not null, so the row is refused later by the database with no
     * indication of which caller omitted the argument.</p>
     */
    @Test
    @DisplayName("the projection refuses an absent instant and an absent feed record")
    void theProjectionRefusesAbsentArguments() {
        DailyTransaction row = referenceEntity(ORIG_TS_VALUE, null);

        assertThatThrownBy(() -> DailyTransactionMapper.toPostedTransaction(row, null))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toPostedTransaction(null,
                LocalDateTime.parse("2022-07-18T10:15:30")))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
    }

    /**
     * An image of any length other than the declared one is refused by width, short or long.
     *
     * <p>Assumptions: the refusal is by width at the record level rather than by a bounds failure
     * inside a span read, which is what stops a truncated image from yielding a partial map whose money
     * field has silently shifted. A shifted 350-byte record still reads as 350 bytes, so nothing
     * downstream would notice.</p>
     *
     * @param length the image length to offer for this case
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 132, 330, 349, 351, 700})
    @DisplayName("an image of the wrong length is refused, short or long")
    void aWrongLengthImageIsRefused(int length) {
        byte[] wrong = new byte[length];
        Arrays.fill(wrong, (byte) ' ');

        assertThatThrownBy(() -> DailyTransactionMapper.toEntity(wrong))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.decode(wrong))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * An absent image is refused by the same width check rather than by a null dereference.
     */
    @Test
    @DisplayName("an absent image is refused by the decode")
    void anAbsentImageIsRefused() {
        assertThatThrownBy(() -> DailyTransactionMapper.toEntity(null))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * The byte-preserving encode refuses a wrongly sized source image before copying any span.
     *
     * <p>Assumptions: the width check comes from the sign-preserving codec entry point rather than from
     * a check written in the mapper, which is why a short image is reported as a length failure instead
     * of as an array bounds failure raised from inside the pad copy.</p>
     */
    @Test
    @DisplayName("the byte-preserving encode refuses a wrongly sized source image")
    void theImageOverloadRefusesAWrongWidthImage() {
        DailyTransaction row = referenceEntity(ORIG_TS_VALUE, null);

        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(row, new byte[RECORD_LENGTH - 1]))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(row, new byte[RECORD_LENGTH + 1]))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(row, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An absent feed record is refused identically by both encode overloads.
     *
     * <p>Assumptions: the check is shared rather than repeated per entry point, so both overloads and
     * the projection name the record and the operation. Letting the absence surface from the first
     * getter would raise a failure naming neither, which sends a maintainer to read the mapper rather
     * than their own call site.</p>
     */
    @Test
    @DisplayName("an absent feed record is refused by both encode overloads")
    void anAbsentFeedRecordIsRefused() {
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(null))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(null, referenceImage()))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
    }

    /**
     * A source stamp span in neither producer's form is refused, so a foreign image is caught.
     *
     * <p>Assumptions: the refusal is the one available signal that an image does not belong to the
     * record it was handed with. This record carries no discriminator and no sequence number to compare,
     * so there is nothing else to check -- but a 26-character span that parses under neither producer's
     * form cannot have come from a record of this layout at all.</p>
     */
    @Test
    @DisplayName("a source stamp span in neither producer's form is refused")
    void aMalformedSourceStampIsRefused() {
        byte[] source = withSpan(referenceImage(), ORIG_TS_OFFSET, "NOT-A-TIMESTAMP-AT-ALL-XXX");
        DailyTransaction row = referenceEntity(ORIG_TS_VALUE, null);

        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(row, source))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toEntity(source))
                .as("the same span is refused on the way in")
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
    }

    /**
     * An impossible calendar date in a stamp span is refused rather than rolled forward.
     *
     * <p>Assumptions: a lenient resolver would accept the thirty-first of February and yield the second
     * or third of March -- a date no record could have carried, and one nothing downstream can detect
     * because it is a perfectly well-formed date. A strict resolver refuses it, which is the weaker
     * outcome to read and the stronger one to rely on.</p>
     */
    @Test
    @DisplayName("an impossible calendar date in a stamp span is refused")
    void anImpossibleDateIsRefused() {
        byte[] source = withSpan(referenceImage(), ORIG_TS_OFFSET, "2022-02-31 19:27:53.000000");

        assertThatThrownBy(() -> DailyTransactionMapper.toEntity(source))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
    }

    /**
     * A non-digit inside a numeric-display span is refused by the shared codec.
     *
     * @param span the eleven characters to write over the amount span for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"00000O5047G", "0000005047$", "           "})
    @DisplayName("a non-numeric amount span is refused by the decode")
    void aNonNumericAmountSpanIsRefused(String span) {
        byte[] source = withSpan(referenceImage(), AMOUNT_OFFSET, span);

        assertThatThrownBy(() -> DailyTransactionMapper.toEntity(source))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An entity missing its record key is refused rather than blank-filled.
     *
     * <p>Assumptions: the key is the one field an absent value cannot be tolerated in, because a
     * blank-filled key would write a record that reads as valid and that no diagnostic could ever
     * identify afterwards. Every other character field blank-fills, so the asymmetry is deliberate and
     * is asserted here alongside its counterpart.</p>
     */
    @Test
    @DisplayName("an entity with no record key is refused, while other absent text blank-fills")
    void anEntityWithoutARecordKeyIsRefused() {
        DailyTransaction keyless = new DailyTransaction(null, "01", "0001", "POS TERM", "d",
                new BigDecimal("1.00"), 1L, "n", "c", "z", CARD_NUMBER, ORIG_TS_VALUE, null);
        DailyTransaction sparse = new DailyTransaction(TRANSACTION_ID, "01", "0001", null, null,
                new BigDecimal("1.00"), null, null, null, null, CARD_NUMBER, ORIG_TS_VALUE, null);

        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(keyless))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);

        byte[] encoded = DailyTransactionMapper.toRecord(sparse);
        assertThat(encoded).hasSize(RECORD_LENGTH);
        assertThat(spanOf(encoded, 22, 10)).as("an absent source blank-fills").isBlank();
        assertThat(spanOf(encoded, 143, 9)).as("an absent merchant identifier is zero")
                .isEqualTo("000000000");
    }

    /**
     * A fixed-width code present at some other width is refused rather than padded or truncated.
     *
     * <p>Assumptions: the three fixed-width fields are refused at the wrong width because each is
     * compared at a fixed width downstream. Padding a short value here would produce a record whose key
     * or lookup key silently differs from the one the caller believed it wrote, and truncating a long
     * one would change which account the record belongs to.</p>
     */
    @Test
    @DisplayName("a fixed-width code at the wrong width is refused")
    void aFixedWidthCodeAtTheWrongWidthIsRefused() {
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(
                new DailyTransaction("0000000000", "01", "0001", "s", "d", new BigDecimal("1.00"),
                        1L, "n", "c", "z", CARD_NUMBER, ORIG_TS_VALUE, null)))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(
                new DailyTransaction(TRANSACTION_ID, "001", "0001", "s", "d", new BigDecimal("1.00"),
                        1L, "n", "c", "z", CARD_NUMBER, ORIG_TS_VALUE, null)))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(
                new DailyTransaction(TRANSACTION_ID, "01", "0001", "s", "d", new BigDecimal("1.00"),
                        1L, "n", "c", "z", "48594526", ORIG_TS_VALUE, null)))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
    }

    /**
     * A category code that is not decimal digits, or is wider than its span, is refused.
     *
     * @param categoryCd the value to offer as the category code for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"00A1", " 001", "00001", ""})
    @DisplayName("a category code that is not four decimal digits is refused")
    void aNonNumericCategoryCodeIsRefused(String categoryCd) {
        DailyTransaction row = new DailyTransaction(TRANSACTION_ID, "01", categoryCd, "s", "d",
                new BigDecimal("1.00"), 1L, "n", "c", "z", CARD_NUMBER, ORIG_TS_VALUE, null);

        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(row))
                .isInstanceOf(DailyTransactionMapper.RecordMappingException.class);
    }

    /**
     * A category code shorter than its span is zero-padded on the left, not the right.
     *
     * <p>Assumptions: the padding side is the whole of the difference between category 1 and category
     * 1000, and the field is a numeric-display picture, so left-padding is what the picture means. This
     * is asserted because a right-padded value would still be four digits and would still decode
     * without complaint.</p>
     */
    @Test
    @DisplayName("a short category code is zero-padded on the left")
    void aShortCategoryCodeIsLeftPadded() {
        DailyTransaction row = new DailyTransaction(TRANSACTION_ID, "01", "1", "s", "d",
                new BigDecimal("1.00"), 1L, "n", "c", "z", CARD_NUMBER, ORIG_TS_VALUE, null);

        assertThat(spanOf(DailyTransactionMapper.toRecord(row), 18, 4)).isEqualTo("0001");
    }

    /**
     * An absent amount is refused, a sub-cent amount is reduced to cents, and a too-wide one is refused.
     *
     * <p>Assumptions: the three verdicts are asserted together because they are the three an amount can
     * reach on this path and only together do they show where the boundary is. Absence is refused at
     * CONSTRUCTION rather than at the encode call, because a monetary field has no absent state and the
     * entity reduces its amount through {@code Money.ofPicture}, so an absent one never becomes an
     * instance that could reach this mapper from application code. A third decimal is REDUCED rather than refused, at scale two
     * under HALF_UP, which is the one rounding rule this migration declares for money and which
     * {@code com.carddemo.common.money.Money} applies for every context alike -- refusing it here would
     * make this one mapper stricter than the type that owns the rule, and a caller would then get
     * different answers from the entity and from the encoder for the same value. A tenth integer digit is
     * refused, because reducing THAT would change an amount by an order of magnitude and no rounding rule
     * can express it: the picture declares nine integer positions and the eleven-byte span has nowhere to
     * put a tenth.</p>
     *
     * <p>Assumptions: the width refusal is an {@code ArithmeticException} rather than an
     * {@code IllegalArgumentException}, because it reports a value outside a numeric domain and that is
     * the exception {@code BigDecimal} itself raises for the same class of fault.</p>
     */
    @Test
    @DisplayName("an absent amount is refused, a sub-cent amount rounds, and a too-wide one is refused")
    void anUnrepresentableAmountIsRefused() {
        assertThatThrownBy(() -> new DailyTransaction(TRANSACTION_ID, "01", "0001", "s", "d", null,
                1L, "n", "c", "z", CARD_NUMBER, ORIG_TS_VALUE, null))
                .isInstanceOf(NullPointerException.class);

        byte[] rounded = DailyTransactionMapper.toRecord(
                new DailyTransaction(TRANSACTION_ID, "01", "0001", "s", "d",
                        new BigDecimal("1.005"), 1L, "n", "c", "z", CARD_NUMBER,
                        ORIG_TS_VALUE, null));
        byte[] declared = DailyTransactionMapper.toRecord(
                new DailyTransaction(TRANSACTION_ID, "01", "0001", "s", "d",
                        new BigDecimal("1.01"), 1L, "n", "c", "z", CARD_NUMBER,
                        ORIG_TS_VALUE, null));
        assertThat(rounded).isEqualTo(declared);

        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(
                new DailyTransaction(TRANSACTION_ID, "01", "0001", "s", "d",
                        new BigDecimal("1234567890.12"), 1L, "n", "c", "z", CARD_NUMBER,
                        ORIG_TS_VALUE, null)))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * A descriptive value wider than its declared span is refused rather than truncated.
     *
     * <p>Assumptions: truncation would be the convenient outcome and is the wrong one -- a merchant name
     * silently shortened at the fiftieth character is a record that no length check afterwards can tell
     * from one whose name really was fifty characters.</p>
     */
    @Test
    @DisplayName("a descriptive value wider than its span is refused")
    void anOverWideDescriptiveValueIsRefused() {
        DailyTransaction row = new DailyTransaction(TRANSACTION_ID, "01", "0001", "s",
                "d".repeat(101), new BigDecimal("1.00"), 1L, "n", "c", "z", CARD_NUMBER,
                ORIG_TS_VALUE, null);

        assertThatThrownBy(() -> DailyTransactionMapper.toRecord(row))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The exposed layout carries the geometry the copybook and the sort control both declare.
     *
     * <p>Assumptions: read through the exposed descriptor rather than restated as numbers, so this case
     * says the geometry is what the descriptor reports instead of installing a second authority for it
     * here. The card-number offset is checked because it is the cross-reference lookup key's position
     * and the amount width because eleven versus twelve is what separates this record's money from the
     * account master's.</p>
     */
    @Test
    @DisplayName("the exposed layout declares 350 bytes, an 11-character amount and a 20-byte pad")
    void theLayoutGeometryIsAsDeclared() {
        assertThat(DailyTransactionMapper.layout().reclen()).isEqualTo(RECORD_LENGTH);
        assertThat(DailyTransactionMapper.layout().field("DALYTRAN-AMT").start())
                .isEqualTo(AMOUNT_OFFSET);
        assertThat(DailyTransactionMapper.layout().field("DALYTRAN-AMT").length())
                .isEqualTo(AMOUNT_LENGTH);
        assertThat(DailyTransactionMapper.layout().field("DALYTRAN-CARD-NUM").start()).isEqualTo(262);
        assertThat(DailyTransactionMapper.layout().field("DALYTRAN-CARD-NUM").sensitive())
                .as("the card number is marked sensitive, so no diagnostic may echo it")
                .isTrue();
        assertThat(DailyTransactionMapper.layout().field("DALYTRAN-ORIG-TS").start())
                .isEqualTo(ORIG_TS_OFFSET);
        assertThat(DailyTransactionMapper.layout().field("DALYTRAN-PROC-TS").start())
                .isEqualTo(PROC_TS_OFFSET);
        assertThat(DailyTransactionMapper.layout().field("FILLER").start()).isEqualTo(PAD_OFFSET);
        assertThat(DailyTransactionMapper.layout().field("FILLER").length()).isEqualTo(PAD_LENGTH);
    }
}
