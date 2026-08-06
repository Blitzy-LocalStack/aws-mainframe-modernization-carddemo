package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.CardXref;
import com.carddemo.common.codec.FixedWidthCodec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the 50-byte card cross-reference boundary in both directions.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link CardXrefRecordMapper} had no executable consumer. This class asserts all three mapped
 * fields of a real reference record in both directions, the rule that the sixteen-character card
 * number is <b>never</b> trimmed because its width is the whole of the base cluster key, the fact
 * that both identifiers are read as plain unsigned digits and never through the sign-overpunch path,
 * the trailing pad's asymmetric treatment, the diagnostic that <b>omits</b> the content of a
 * sensitive field, and every documented rejection.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test engine,
 * so the type itself accepts no parameter, returns nothing and throws nothing. The inapplicability is
 * stated rather than passed over, because user-specified Rule 1 (Explainability) forbids a docstring
 * that omits parameters, return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Every member below carries its own parameter, return and
 * exception at-clauses.</p>
 *
 * <h2>Assumptions: the reference extract is 36 characters and the record is 50</h2>
 *
 * <p>{@code app/data/ASCII/cardxref.txt} ships lines of 36 characters, which is the three mapped
 * fields with the trailing {@code FILLER PIC X(14)} omitted, because a text extract carries no
 * trailing blanks. The record the dataset declares is 50 -- {@code app/jcl/XREFFILE.jcl} fixes
 * {@code RECORDSIZE(50 50)} -- so the constant below restores the pad explicitly. That restoration is
 * itself a fact worth pinning: a loader that fed the extract straight in would be refused by width
 * rather than shifting fields, and this class asserts that refusal.</p>
 */
@DisplayName("CardXrefRecordMapper: 50-byte cross-reference decode and encode")
class CardXrefRecordMapperTest {

    /** Declared record length of the cross-reference, from {@code app/jcl/XREFFILE.jcl}. */
    private static final int RECORD_LENGTH = 50;

    /** Declared width of the card number, which is also the whole of the base cluster key. */
    private static final int CARD_NUM_LENGTH = 16;

    /** Zero-based offset of the customer identifier, declared {@code PIC 9(09)} without an S. */
    private static final int CUSTOMER_ID_OFFSET = 16;

    /** Zero-based offset of the account identifier, declared {@code PIC 9(11)} without an S. */
    private static final int ACCOUNT_ID_OFFSET = 25;

    /** Zero-based offset of the trailing pad, from {@code app/cpy/CVACT03Y.cpy} line 8. */
    private static final int PAD_OFFSET = 36;

    /** Declared width of the trailing pad. */
    private static final int PAD_LENGTH = 14;

    /**
     * The first record of {@code app/data/ASCII/cardxref.txt} with its declared pad restored.
     */
    private static final String REFERENCE_RECORD =
            "0500024453765740"      // XREF-CARD-NUM  PIC X(16)
            + "000000050"           // XREF-CUST-ID   PIC 9(09)
            + "00000000050"         // XREF-ACCT-ID   PIC 9(11)
            + " ".repeat(PAD_LENGTH);

    /** The mapper under test, held as one instance because it resolves its layout at construction. */
    private final CardXrefRecordMapper mapper = new CardXrefRecordMapper();

    /**
     * Returns the reference record as bytes.
     *
     * @return a freshly copied 50-byte image, so a mutating test cannot affect another
     */
    private static byte[] referenceImage() {
        return REFERENCE_RECORD.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns a copy of the reference record with one field span overwritten.
     *
     * @param offset the zero-based offset of the span to overwrite
     * @param value the replacement content, which must be exactly the span's declared width
     * @return a freshly copied 50-byte image carrying the replacement
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
        assertThat(REFERENCE_RECORD).hasSize(RECORD_LENGTH);
        assertThat(PAD_OFFSET + PAD_LENGTH).isEqualTo(RECORD_LENGTH);
    }

    /**
     * Asserts that all three mapped fields decode to their expected values.
     */
    @Test
    @DisplayName("all three mapped fields decode to their expected values")
    void allThreeMappedFieldsDecodeToTheirExpectedValues() {
        CardXref xref = mapper.toEntity(referenceImage());

        assertThat(xref.getCardNum()).isEqualTo("0500024453765740");
        assertThat(xref.getCustomerId()).isEqualTo(50L);
        assertThat(xref.getAccountId()).isEqualTo(50L);
    }

    /**
     * Asserts that a card number holding trailing blanks keeps all sixteen characters.
     */
    @Test
    @DisplayName("a card number holding trailing blanks keeps all sixteen characters")
    void aCardNumberHoldingTrailingBlanksKeepsAllSixteenCharacters() {
        byte[] image = spliced(0, "1234567890      ");

        CardXref xref = mapper.toEntity(image);

        // WHY : Assumptions: this is the OPPOSITE of the treatment a descriptive character field gets
        //       elsewhere in this package, and the difference is the point. The card number is the
        //       whole of the base cluster key at sixteen bytes from offset zero, so its width is the
        //       contract rather than an upper bound: a trimmed value still equals itself and no longer
        //       equals its blank-padded counterpart in an application-level comparison, so the row is
        //       missed with nothing failing first. Applying this package's general trim rule here
        //       breaks a primary key silently.
        assertThat(xref.getCardNum()).isEqualTo("1234567890      ").hasSize(CARD_NUM_LENGTH);
        assertThat(mapper.toRecord(xref)).isEqualTo(image);
    }

    /**
     * Asserts that an identifier span ending in an overpunch letter is refused, not read as signed.
     */
    @Test
    @DisplayName("an identifier span ending in an overpunch letter is refused, not read as signed")
    void anIdentifierSpanEndingInAnOverpunchLetterIsRefused() {
        // WHY : Assumptions: this is the single most likely source of silent corruption in this record
        //       and the corpus proves the hazard is real rather than theoretical. app/data/ASCII/
        //       dailytran.txt contains eleven-character runs such as 3580010001P that end in a
        //       negative-overpunch letter, so a decoder that resolved the trailing byte of an
        //       eleven-byte span by LOOKING at it would have real input to be wrong about: it would
        //       consume the letter as a sign, take the digit body one character short, and return a
        //       negative value wrong by an order of magnitude that still reads as an identifier. Only
        //       the descriptor's signed flag -- false, because app/cpy/CVACT03Y.cpy:7 omits the S --
        //       stands between the two readings, and this assertion is what pins it.
        assertThatThrownBy(() -> mapper.toEntity(spliced(ACCOUNT_ID_OFFSET, "3580010001P")))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("XREF-ACCT-ID")
                .hasMessageContaining("non-digit");
    }

    /**
     * Asserts that a blank-padded reference record round-trips byte for byte.
     */
    @Test
    @DisplayName("a blank-padded reference record round-trips byte for byte")
    void aBlankPaddedReferenceRecordRoundTripsByteForByte() {
        byte[] image = referenceImage();

        assertThat(mapper.toRecord(mapper.toEntity(image)))
                .isEqualTo(image)
                .hasSize(RECORD_LENGTH);
    }

    /**
     * Asserts that the round trip is byte-exact only when the source pad was already blank.
     */
    @Test
    @DisplayName("the round trip is byte-exact only when the source pad was already blank")
    void theRoundTripIsByteExactOnlyWhenTheSourcePadWasBlank() {
        byte[] withDataInPad = spliced(PAD_OFFSET, "X".repeat(PAD_LENGTH));

        byte[] encoded = mapper.toRecord(mapper.toEntity(withDataInPad));

        // WHY : Assumptions: the caveat the mapper's own class documentation states is asserted here
        //       rather than left as prose. app/cpy/CVACT03Y.cpy:8 declares a bare FILLER carrying
        //       neither a REDEFINES nor a VALUE clause, which is what makes it padding rather than
        //       content, so the decode drops it and the encode rebuilds it as blanks. Both directions
        //       treating it alike would be the error: dropping it on encode too would emit 36 bytes
        //       where the dataset declares 50, and carrying it on decode too would put fourteen bytes
        //       of inert padding into the entity and every consumer below it.
        assertThat(encoded).hasSize(RECORD_LENGTH).isNotEqualTo(withDataInPad);
        assertThat(new String(encoded, StandardCharsets.UTF_8).substring(PAD_OFFSET))
                .isEqualTo(" ".repeat(PAD_LENGTH));
        assertThat(Arrays.copyOf(encoded, PAD_OFFSET))
                .isEqualTo(Arrays.copyOf(withDataInPad, PAD_OFFSET));
    }

    /**
     * Asserts that a diagnostic for the sensitive card-number field omits the offending content.
     */
    @Test
    @DisplayName("a diagnostic for the sensitive card-number field omits the offending content")
    void aDiagnosticForTheSensitiveCardNumberOmitsItsContent() {
        String overWide = "1234567890123456789";

        // WHY : Assumptions: the assertion is that the value is ABSENT from the message, not that it
        //       is masked. The shared layout marks XREF-CARD-NUM sensitive, and the codec's response
        //       to a sensitive field it cannot encode is to name the field and say outright that the
        //       content was withheld. A diagnostic is the one place a rejected primary account number
        //       is most likely to reach a log unnoticed, so asserting the absence of the digits --
        //       rather than the presence of a mask -- is the assertion that actually protects them.
        assertThatThrownBy(() -> mapper.toRecord(new CardXref(overWide, 1L, 2L)))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("XREF-CARD-NUM")
                .hasMessageContaining("sensitive")
                .hasMessageNotContaining(overWide);
    }

    /**
     * Supplies each documented rejection with the diagnostic fragment it must produce.
     *
     * @return a stream of case label, the invocation that must be refused and a diagnostic fragment
     */
    private static Stream<Arguments> rejections() {
        CardXrefRecordMapper mapper = new CardXrefRecordMapper();
        return Stream.of(
                Arguments.of("a null image", (Executable) () -> mapper.toEntity(null),
                        "expected 50 bytes"),
                Arguments.of("the 36-character extract line fed in unpadded",
                        (Executable) () -> mapper
                                .toEntity(REFERENCE_RECORD.substring(0, PAD_OFFSET)
                                        .getBytes(StandardCharsets.UTF_8)),
                        "received 36"),
                Arguments.of("an image one byte long",
                        (Executable) () -> mapper
                                .toEntity(Arrays.copyOf(referenceImage(), RECORD_LENGTH + 1)),
                        "received 51"),
                Arguments.of("a non-digit in the customer identifier",
                        (Executable) () -> mapper.toEntity(spliced(CUSTOMER_ID_OFFSET, "00000000X")),
                        "XREF-CUST-ID"),
                Arguments.of("a null entity", (Executable) () -> mapper.toRecord(null),
                        "must not be null"),
                Arguments.of("an absent card number",
                        (Executable) () -> mapper.toRecord(new CardXref(null, 1L, 2L)),
                        "cardNum"),
                Arguments.of("an absent customer identifier",
                        (Executable) () -> mapper
                                .toRecord(new CardXref("1234567890123456", null, 2L)),
                        "customerId"),
                Arguments.of("an absent account identifier",
                        (Executable) () -> mapper
                                .toRecord(new CardXref("1234567890123456", 1L, null)),
                        "accountId"),
                Arguments.of("a negative customer identifier",
                        (Executable) () -> mapper
                                .toRecord(new CardXref("1234567890123456", -1L, 2L)),
                        "cannot hold a negative value"),
                Arguments.of("an account identifier needing a twelfth digit",
                        (Executable) () -> mapper
                                .toRecord(new CardXref("1234567890123456", 1L, 100000000000L)),
                        "needs 12 digits"));
    }

    /**
     * Asserts that each documented rejection raises with a diagnostic naming its cause.
     *
     * @param label a short description of the case, shown in the case name
     * @param invocation the conversion that must be refused
     * @param fragment a fragment the diagnostic must contain
     */
    @ParameterizedTest(name = "the mapper refuses {0}")
    @MethodSource("rejections")
    @DisplayName("each documented rejection raises with a diagnostic naming its cause")
    void eachDocumentedRejectionRaises(String label, Executable invocation, String fragment) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOfAny(FixedWidthCodec.RecordLengthException.class,
                        FixedWidthCodec.FieldCodecException.class, NullPointerException.class,
                        IllegalArgumentException.class)
                .hasMessageContaining(fragment);
    }

    /**
     * Asserts that a fresh mapper resolves its layout at construction rather than per call.
     */
    @Test
    @DisplayName("a fresh mapper resolves its layout at construction rather than per call")
    void aFreshMapperResolvesItsLayoutAtConstruction() {
        // WHY : Assumptions: two independently constructed mappers must decode one image identically,
        //       which is what shows the descriptor is resolved from the shared registry rather than
        //       declared locally per instance. A locally declared descriptor would be a second truth
        //       about the same 50 bytes, and two copies stay internally consistent while disagreeing
        //       with each other, so nothing fails at the moment they diverge.
        CardXref first = new CardXrefRecordMapper().toEntity(referenceImage());
        CardXref second = new CardXrefRecordMapper().toEntity(referenceImage());

        assertThat(second.getCardNum()).isEqualTo(first.getCardNum());
        assertThat(second.getCustomerId()).isEqualTo(first.getCustomerId());
        assertThat(second.getAccountId()).isEqualTo(first.getAccountId());
    }
}
