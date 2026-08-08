package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.mapper.ExportRecordMapper.ExportRecord;
import com.carddemo.batch.mapper.ExportRecordMapper.ExportRecordException;
import com.carddemo.batch.mapper.ExportRecordMapper.OpaqueSensitiveValue;
import com.carddemo.batch.mapper.ExportRecordMapper.Prefix;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.common.codec.CopybookLayout;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the 500-byte export boundary, and above all its handling of the card verification value.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link ExportRecordMapper} had no executable consumer, which for this class meant two specific
 * gaps rather than one general one. The source-image encode overload accepted ANY 500-byte array as the
 * record's source and copied the verification span straight out of it, so a caller that paired one
 * record with another's image produced an export carrying the wrong card's secret; and the card-view
 * factory required an opaque carrier that no reachable code could construct, so a card record could be
 * decoded but never assembled. This class asserts the corrected behaviour of both: that a mismatched
 * image is refused, that the verification value comes from the record's own carrier, and that the
 * carrier can be built and denied by name.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a caller
 * invokes, no value it yields and no exception it raises outside the test engine, so the type itself
 * accepts no parameter, returns nothing and throws nothing. The inapplicability is stated rather than
 * passed over, because user-specified Rule 1 forbids a docstring that omits parameters, return values
 * or purpose and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own at-clauses.</p>
 *
 * <h2>Assumptions: the images are assembled from the descriptor, not pasted</h2>
 *
 * <p>Every image below is built by {@link #cardImage(long, String, int)} from the layout's own declared
 * offsets rather than written out as a 500-character literal. A literal would have to be recounted by
 * hand against the descriptor on every geometry change, and the one thing these tests must not do is
 * agree with a stale idea of where the verification span sits.</p>
 */
@DisplayName("ExportRecordMapper: 500-byte export record, image provenance and the opaque carrier")
class ExportRecordMapperTest {

    /** Declared length of the whole export record, from {@code app/cpy/CVEXPORT.cpy:5}. */
    private static final int RECORD_LENGTH = 500;

    /** Zero-based offset at which the 460-byte payload begins. */
    private static final int PAYLOAD_OFFSET = 40;

    /** A target-form timestamp, twenty-six characters, as {@code TimestampFormatter} emits. */
    private static final String TIMESTAMP = "2022-07-18 10:15:30.123456";

    /** The four-character branch identifier the fixtures carry. */
    private static final String BRANCH_ID = "0001";

    /** The five-character region code the fixtures carry. */
    private static final String REGION_CODE = "US001";

    /** The sixteen-character primary account number the card fixtures carry. */
    private static final String CARD_NUMBER = "4000123456789010";

    /**
     * Builds one card-view export image with the supplied sequence number and verification value.
     *
     * <p>Assumptions: the image is written through the mapper's own encode path rather than assembled
     * byte by byte here, so the fixture cannot disagree with the descriptor about any offset. The
     * verification value is supplied through the public carrier factory, which is also what makes that
     * factory's existence a precondition of this whole class rather than an incidental convenience.</p>
     *
     * @param sequenceNumber the retrieval key to place in the prefix
     * @param cardNumber the primary account number to place in the card view
     * @param verificationValue the verification value to carry, as an unsigned halfword quantity
     * @return a fresh 500-byte image, never {@code null}
     */
    private static byte[] cardImage(long sequenceNumber, String cardNumber, int verificationValue) {
        Prefix prefix =
                new Prefix(RecordType.CARD, TIMESTAMP, sequenceNumber, BRANCH_ID, REGION_CODE);
        return ExportRecordMapper.toRecord(
                ExportRecord.ofCard(prefix, cardFields(cardNumber),
                        OpaqueSensitiveValue.of(halfword(verificationValue))));
    }

    /**
     * Renders a small unsigned quantity as the two-byte halfword the verification span declares.
     *
     * <p>Assumptions: big-endian, most significant byte first, which is the order the shared kernel's
     * binary codec uses and therefore the order the span is read back in. Writing the two bytes here
     * rather than encoding through a numeric field is deliberate: the whole point of the carrier is that
     * no numeric variable in the production path ever holds a verification value, so the test supplies
     * bytes exactly as a caller would.</p>
     *
     * @param value the quantity to render, which must fit two bytes
     * @return a two-byte big-endian array
     */
    private static byte[] halfword(int value) {
        return new byte[] {(byte) (value >>> 8), (byte) value};
    }

    /**
     * Builds the card-view projection the factory takes, with the pad and the verification value absent.
     *
     * @param cardNumber the primary account number to place in the projection
     * @return an ordered map of the card view's named fields, excluding the pad and the secret
     */
    private static Map<String, Object> cardFields(String cardNumber) {
        return Map.of(
                "EXP-CARD-NUM", cardNumber,
                "EXP-CARD-ACCT-ID", java.math.BigDecimal.valueOf(10000000011L),
                "EXP-CARD-EMBOSSED-NAME", "ACME HARDWARE",
                "EXP-CARD-EXPIRAION-DATE", "2027-12-31",
                "EXP-CARD-ACTIVE-STATUS", "Y");
    }

    /**
     * Returns the two bytes of the verification span of an image.
     *
     * @param image the 500-byte export record to read
     * @return a fresh two-byte array holding the span's current content
     */
    private static byte[] verificationSpanOf(byte[] image) {
        CopybookLayout.FieldSpec field =
                ExportRecordMapper.viewLayout(RecordType.CARD).field("EXP-CARD-CVV-CD");
        int start = PAYLOAD_OFFSET + field.start();
        return Arrays.copyOfRange(image, start, start + field.length());
    }

    /**
     * The opaque carrier can be built and denied by name, so the card factory is reachable.
     *
     * <p>Assumptions: this is asserted first because every other card case depends on it. Before the
     * carrier had a public factory and a named absent state, {@link ExportRecord#ofCard} could not be
     * called at all from outside the mapper -- it requires a carrier and neither kind could be obtained
     * -- so a card record was decodable but not assemblable and the export step had no way to build the
     * record it must write.</p>
     */
    @Test
    @DisplayName("the opaque carrier has a public factory and a named absent state")
    void theOpaqueCarrierIsConstructible() {
        OpaqueSensitiveValue present = OpaqueSensitiveValue.of(halfword(123));
        OpaqueSensitiveValue absent = OpaqueSensitiveValue.absent();

        assertThat(present.isPresent()).isTrue();
        assertThat(present.copyBytes()).isEqualTo(halfword(123));
        assertThat(absent.isPresent()).isFalse();
        assertThat(absent.copyBytes()).isNull();
    }

    /**
     * The carrier copies on the way in and on the way out, so no caller shares its state.
     *
     * <p>Assumptions: both directions are asserted, because they defend against different mistakes.
     * Copying IN stops a caller that reuses or zeroes its own buffer from emptying the carrier; copying
     * OUT stops a caller that zeroes the value after encrypting from emptying the carrier a re-encode
     * still needs. A single-direction copy would pass one of these two assertions and fail the other.</p>
     */
    @Test
    @DisplayName("the carrier copies defensively in both directions")
    void theCarrierCopiesDefensively() {
        byte[] supplied = halfword(456);
        OpaqueSensitiveValue carrier = OpaqueSensitiveValue.of(supplied);

        Arrays.fill(supplied, (byte) 0);
        assertThat(carrier.copyBytes())
                .as("zeroing the caller's array does not empty the carrier")
                .isEqualTo(halfword(456));

        byte[] handedOut = carrier.copyBytes();
        Arrays.fill(handedOut, (byte) 0);
        assertThat(carrier.copyBytes())
                .as("zeroing a handed-out copy does not empty the carrier")
                .isEqualTo(halfword(456));
    }

    /**
     * The carrier never renders its content, whether present or absent.
     *
     * <p>Assumptions: the two renderings are asserted to be IDENTICAL rather than merely both redacted.
     * A marker that read differently when a value was present would confirm to anyone reading a log that
     * a given card has a verification value on file, which is information the record is not meant to
     * disclose either.</p>
     */
    @Test
    @DisplayName("the carrier renders one constant marker whether present or absent")
    void theCarrierNeverRendersItsContent() {
        String present = OpaqueSensitiveValue.of(halfword(789)).toString();
        String absent = OpaqueSensitiveValue.absent().toString();

        assertThat(present).isEqualTo(absent).doesNotContain("789");
    }

    /**
     * A carrier of any width other than the declared span is refused, content never echoed.
     *
     * <p>Assumptions: the width is checked at the factory rather than at the encode, so a malformed
     * carrier is refused where it is created instead of failing later inside a span copy. A shorter one
     * would otherwise leave part of the target span holding whatever the encode had already written.</p>
     *
     * @param width the carrier width to offer for this case
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4, 16})
    @DisplayName("a carrier of the wrong width is refused without echoing its bytes")
    void aWrongWidthCarrierIsRefused(int width) {
        byte[] wrong = new byte[width];
        Arrays.fill(wrong, (byte) 0x41);

        assertThatThrownBy(() -> OpaqueSensitiveValue.of(wrong))
                .isInstanceOf(ExportRecordException.class)
                .hasMessageContaining("EXP-CARD-CVV-CD")
                .hasMessageNotContaining("AAAA");
    }

    /**
     * A null carrier is refused, and the refusal points at the absent state instead.
     *
     * <p>Assumptions: {@code null} is refused rather than silently treated as absence, because the two
     * mean different things to a reader of the call site: one is a deliberate statement that this record
     * has no verification value, the other is an unset variable. The message names the alternative so a
     * caller is not left guessing how to express absence.</p>
     */
    @Test
    @DisplayName("a null carrier is refused and the message names absent() instead")
    void aNullCarrierIsRefused() {
        assertThatThrownBy(() -> OpaqueSensitiveValue.of(null))
                .isInstanceOf(ExportRecordException.class)
                .hasMessageContaining("absent()");
    }

    /**
     * A decoded card record round trips byte for byte through the source-image encode.
     *
     * <p>Assumptions: the verification span is asserted separately from the whole-image equality, even
     * though whole-image equality already covers it. The separate assertion is what makes a failure
     * legible: an image comparison over 500 bytes reports a first differing index, and knowing whether
     * that index is inside the two-byte secret span is the difference between a pad problem and a
     * disclosure problem.</p>
     */
    @Test
    @DisplayName("a decoded card record round trips byte for byte, verification span included")
    void aCardRecordRoundTripsByteForByte() {
        byte[] source = cardImage(1L, CARD_NUMBER, 321);

        ExportRecord decoded = ExportRecordMapper.decode(source);

        assertThat(decoded.prefix().recordType()).isEqualTo(RecordType.CARD);
        assertThat(decoded.cardVerificationValue().isPresent()).isTrue();
        assertThat(ExportRecordMapper.toRecord(decoded, source)).isEqualTo(source);
        assertThat(verificationSpanOf(ExportRecordMapper.toRecord(decoded, source)))
                .isEqualTo(halfword(321));
    }

    /**
     * The encode takes the verification value from the RECORD and never from the supplied image.
     *
     * <p>Refactoring Rationale: this is the assertion the corrected behaviour exists for. Two card
     * images are built that differ in the verification value and agree on everything the belonging check
     * inspects -- the same view, the same sequence number -- so the mismatched pairing is accepted and
     * the only question left is which of the two secrets the output carries. Under the earlier
     * implementation the span was copied out of the image, so the answer was the WRONG card's value; the
     * output must now carry the value the decoded record itself holds. The test is written this way
     * deliberately: an image that the belonging check rejected would prove nothing about where the value
     * came from, because nothing would be encoded at all.</p>
     */
    @Test
    @DisplayName("the verification value comes from the decoded record, not from the source image")
    void theVerificationValueComesFromTheRecord() {
        byte[] ownImage = cardImage(7L, CARD_NUMBER, 111);
        byte[] otherCardImage = cardImage(7L, "4000999999999999", 222);

        ExportRecord decoded = ExportRecordMapper.decode(ownImage);

        byte[] encoded = ExportRecordMapper.toRecord(decoded, otherCardImage);

        assertThat(verificationSpanOf(encoded))
                .as("the record's own value, not the foreign image's")
                .isEqualTo(halfword(111));
        assertThat(verificationSpanOf(encoded))
                .as("and specifically not the other card's value")
                .isNotEqualTo(halfword(222));
    }

    /**
     * An absent carrier leaves the encoded verification span as the plain encode wrote it.
     *
     * <p>Assumptions: the span is asserted to be the encoded zero the card field map supplies, and NOT
     * the foreign image's bytes. That is the correct outcome rather than a silent omission: a record
     * assembled without a verification value has none to write, and taking the image's bytes for it --
     * which the earlier implementation did -- would have invented a secret the projection never carried.</p>
     */
    @Test
    @DisplayName("an absent carrier writes the encoded zero and never the image's bytes")
    void anAbsentCarrierWritesNoSecret() {
        byte[] foreignImage = cardImage(9L, CARD_NUMBER, 999);
        Prefix prefix = new Prefix(RecordType.CARD, TIMESTAMP, 9L, BRANCH_ID, REGION_CODE);
        ExportRecord withoutSecret = ExportRecord.ofCard(prefix, cardFields(CARD_NUMBER),
                OpaqueSensitiveValue.absent());

        byte[] encoded = ExportRecordMapper.toRecord(withoutSecret, foreignImage);

        assertThat(verificationSpanOf(encoded)).isEqualTo(halfword(0));
        assertThat(verificationSpanOf(encoded)).isNotEqualTo(halfword(999));
    }

    /**
     * A source image whose sequence number differs from the record's is refused.
     *
     * <p>Assumptions: the sequence number is the record's retrieval key, so two images agreeing on it are
     * the same record of the same generation and two disagreeing on it are not. The realistic failure
     * this catches is a loop that pairs record N with image N minus one, which no length check can see
     * and which would silently assemble one export record out of two different sources.</p>
     */
    @Test
    @DisplayName("a source image with a different sequence number is refused")
    void aForeignSequenceNumberIsRefused() {
        ExportRecord decoded = ExportRecordMapper.decode(cardImage(10L, CARD_NUMBER, 111));
        byte[] wrongGeneration = cardImage(11L, CARD_NUMBER, 111);

        assertThatThrownBy(() -> ExportRecordMapper.toRecord(decoded, wrongGeneration))
                .isInstanceOf(ExportRecordException.class)
                .hasMessageContaining("does not")
                .hasMessageContaining("belong to this record");
    }

    /**
     * A source image carrying a different view is refused before any span is read.
     *
     * <p>Assumptions: the discriminator decides how the whole 460-byte payload is interpreted, so an
     * image carrying a different one is not merely a different record but a differently SHAPED one --
     * every span offset restored from it would be read at the wrong place. That is why this is checked
     * before the payload is touched rather than being left to fail as a field-level surprise.</p>
     */
    @Test
    @DisplayName("a source image carrying a different view is refused")
    void aForeignViewIsRefused() {
        ExportRecord decoded = ExportRecordMapper.decode(cardImage(12L, CARD_NUMBER, 111));
        byte[] otherView = cardImage(12L, CARD_NUMBER, 111);
        otherView[0] = (byte) 'A';

        assertThatThrownBy(() -> ExportRecordMapper.toRecord(decoded, otherView))
                .isInstanceOf(ExportRecordException.class)
                .hasMessageContaining("view");
    }

    /**
     * A source image of the wrong length is refused, short or long.
     *
     * @param length the image length to offer for this case
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 40, 499, 501, 1000})
    @DisplayName("a source image of the wrong length is refused")
    void aWrongLengthSourceImageIsRefused(int length) {
        ExportRecord decoded = ExportRecordMapper.decode(cardImage(13L, CARD_NUMBER, 111));

        assertThatThrownBy(() -> ExportRecordMapper.toRecord(decoded, new byte[length]))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * A discriminator that is none of the five the baseline writes is refused, with no default view.
     *
     * <p>Assumptions: the refusal names the CODE POINT rather than rendering the character, because the
     * field is declared as one unconstrained byte and rendering it raw would put an unreadable or
     * control character into a log line. There is deliberately no default interpretation of the payload:
     * the baseline's own import reads the same five in a case statement whose fall-through branch is an
     * error path.</p>
     *
     * @param discriminator the byte to place at offset zero for this case
     */
    @ParameterizedTest
    @ValueSource(bytes = {(byte) 'Z', (byte) ' ', (byte) 0x00, (byte) 'c', (byte) 'd'})
    @DisplayName("an unrecognised discriminator is refused with no default view")
    void anUnrecognisedDiscriminatorIsRefused(byte discriminator) {
        byte[] image = cardImage(14L, CARD_NUMBER, 111);
        image[0] = discriminator;

        assertThatThrownBy(() -> ExportRecordMapper.decode(image))
                .isInstanceOf(ExportRecordException.class)
                .hasMessageContaining("code point");
    }

    /**
     * The prefix decodes without interpreting the payload, so a view can be chosen before it is read.
     *
     * <p>Assumptions: the record type and the prefix are readable independently of the payload, and this
     * is what makes the belonging check possible at all -- the check has to compare the discriminator
     * before the payload may safely be decoded, because the discriminator is what selects the view the
     * payload is decoded against.</p>
     */
    @Test
    @DisplayName("the discriminator and prefix decode without reading the payload")
    void thePrefixDecodesIndependentlyOfThePayload() {
        byte[] image = cardImage(15L, CARD_NUMBER, 111);

        assertThat(ExportRecordMapper.recordTypeOf(image)).isEqualTo(RecordType.CARD);

        Prefix prefix = ExportRecordMapper.decodePrefix(image);
        assertThat(prefix.recordType()).isEqualTo(RecordType.CARD);
        assertThat(prefix.sequenceNumber()).isEqualTo(15L);
        assertThat(prefix.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(prefix.branchId()).isEqualTo(BRANCH_ID);
        assertThat(prefix.regionCode()).isEqualTo(REGION_CODE);
    }

    /**
     * The record geometry is the declared 500 bytes with the payload at offset 40.
     *
     * <p>Assumptions: asserted through the exposed descriptor rather than against literals held here, so
     * this test states that the geometry is what the descriptor says rather than restating the numbers a
     * second time where they could drift.</p>
     */
    @Test
    @DisplayName("the record is 500 bytes with a 460-byte payload at offset 40")
    void theRecordGeometryIsAsDeclared() {
        CopybookLayout.RecordSpec record = ExportRecordMapper.recordLayout();

        assertThat(record.reclen()).isEqualTo(RECORD_LENGTH);
        assertThat(record.field("EXPORT-RECORD-DATA").start()).isEqualTo(PAYLOAD_OFFSET);
        assertThat(record.field("EXPORT-RECORD-DATA").length())
                .isEqualTo(RECORD_LENGTH - PAYLOAD_OFFSET);
        assertThat(ExportRecordMapper.viewLayout(RecordType.CARD).reclen())
                .isEqualTo(RECORD_LENGTH - PAYLOAD_OFFSET);
    }

    /**
     * A null view is refused rather than resolved to a default, there being five and no default.
     */
    @Test
    @DisplayName("a null view is refused, there being five views and no default")
    void aNullViewIsRefused() {
        assertThatThrownBy(() -> ExportRecordMapper.viewLayout(null))
                .isInstanceOf(ExportRecordException.class)
                .hasMessageContaining("five payload views");
    }

    /**
     * The card number is not trimmed, its full width being the key the export is retrieved by.
     *
     * <p>Assumptions: the sixteen characters are asserted on the decoded projection rather than on the
     * image, because a trim would be invisible in the image and visible only in the value. The primary
     * account number's width is the whole of the card cluster's key, so a trimmed value would fail to
     * match the record it names.</p>
     */
    @Test
    @DisplayName("the card number keeps all sixteen characters through a decode")
    void theCardNumberIsNeverTrimmed() {
        ExportRecord decoded = ExportRecordMapper.decode(cardImage(16L, CARD_NUMBER, 111));

        assertThat(decoded.cardFields()).containsEntry("EXP-CARD-NUM", CARD_NUMBER);
        assertThat((String) decoded.cardFields().get("EXP-CARD-NUM")).hasSize(16);
    }

    /**
     * The decoded card projection excludes the verification value and the trailing pad.
     *
     * <p>Assumptions: the exclusion is the documented compromise -- a decoded export record is
     * deliberately NOT a complete representation of its input -- so it is asserted rather than left to be
     * discovered. The value reaches a caller only through the opaque carrier, and the pad has no target
     * member at all.</p>
     */
    @Test
    @DisplayName("the card projection excludes the verification value and the pad")
    void theProjectionExcludesTheSecretAndThePad() {
        ExportRecord decoded = ExportRecordMapper.decode(cardImage(17L, CARD_NUMBER, 111));

        assertThat(decoded.cardFields())
                .doesNotContainKey("EXP-CARD-CVV-CD")
                .doesNotContainKey("FILLER");
    }

    /**
     * The pad is restored from the source image, whatever bytes it holds.
     *
     * <p>Assumptions: a non-blank pad is written into the source image explicitly rather than relying on
     * whatever the encode produced, because the property under test is that the RESTORATION happens. An
     * image whose pad the encode had already blanked would round trip successfully with no restoration at
     * all, so the test would assert nothing.</p>
     *
     * @param padByte the byte to fill the card view's pad span with for this case
     */
    @ParameterizedTest
    @ValueSource(bytes = {(byte) '0', (byte) 0x00, (byte) 0x5a})
    @DisplayName("the trailing pad is restored from the source image whatever it holds")
    void thePadIsRestoredFromTheSourceImage(byte padByte) {
        byte[] source = cardImage(18L, CARD_NUMBER, 111);
        CopybookLayout.FieldSpec pad =
                ExportRecordMapper.viewLayout(RecordType.CARD).field("FILLER");
        int start = PAYLOAD_OFFSET + pad.start();
        Arrays.fill(source, start, start + pad.length(), padByte);

        byte[] encoded = ExportRecordMapper.toRecord(ExportRecordMapper.decode(source), source);

        assertThat(encoded).isEqualTo(source);
    }

    /**
     * A null record is refused by both encode overloads.
     */
    @Test
    @DisplayName("a null record is refused by both encode overloads")
    void aNullRecordIsRefused() {
        assertThatThrownBy(() -> ExportRecordMapper.toRecord(null))
                .isInstanceOf(ExportRecordException.class);
        assertThatThrownBy(() ->
                ExportRecordMapper.toRecord(null, cardImage(19L, CARD_NUMBER, 111)))
                .isInstanceOf(ExportRecordException.class);
    }

    /**
     * An image shorter than the record is refused by the decode entry points too.
     *
     * <p>Assumptions: all three read entry points are exercised, because each could have been written
     * with its own length handling. The discriminator reader in particular touches only byte zero, so a
     * naive implementation would answer successfully for a one-byte array and leave the caller to fail
     * later against a payload that is not there.</p>
     */
    @Test
    @DisplayName("every read entry point refuses an image that is not 500 bytes")
    void everyReadEntryPointRefusesAShortImage() {
        byte[] tooShort = new byte[RECORD_LENGTH - 1];
        Arrays.fill(tooShort, (byte) ' ');
        tooShort[0] = (byte) 'D';

        assertThatThrownBy(() -> ExportRecordMapper.recordTypeOf(tooShort))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ExportRecordMapper.decodePrefix(tooShort))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ExportRecordMapper.decode(tooShort))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Each of the five baseline discriminator characters resolves to its own view.
     *
     * <p>Assumptions: these bytes are the CONTRACT and not an internal encoding, because a written
     * export file already holds them -- changing one would strand every record produced before the
     * change. They are established by both halves of the baseline pair rather than inferred:
     * {@code app/cbl/CBEXPORT.cbl} writes them at lines 274, 343, 407, 462 and 527, and
     * {@code app/cbl/CBIMPORT.cbl:272-286} reads the same five in a case statement whose fall-through
     * branch is an error path rather than a default interpretation.</p>
     *
     * <p>Assumptions: the mapping is asserted through {@code recordTypeOf} on a real image rather than
     * through an accessor on the enum, so what is under test is the character that actually reaches the
     * file. Only byte zero is varied, which is legitimate here because the discriminator reader is
     * documented to interpret no payload byte -- and the sibling case above is what establishes that.</p>
     *
     * @param discriminator the single character to place at offset zero
     * @param expected the name of the view that character must resolve to
     */
    @ParameterizedTest
    @CsvSource({"C,CUSTOMER", "A,ACCOUNT", "X,CARD_XREF", "T,TRANSACTION", "D,CARD"})
    @DisplayName("each of C, A, X, T and D resolves to the view the baseline writes it for")
    void eachBaselineDiscriminatorResolvesToItsView(char discriminator, RecordType expected) {
        byte[] image = cardImage(20L, CARD_NUMBER, 111);
        image[0] = (byte) discriminator;

        assertThat(ExportRecordMapper.recordTypeOf(image)).isEqualTo(expected);
    }
}
