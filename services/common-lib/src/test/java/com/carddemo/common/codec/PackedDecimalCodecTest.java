package com.carddemo.common.codec;

import com.carddemo.common.codec.CopybookLayout.Kind;
import com.carddemo.common.codec.CopybookLayout.LayoutException;
import com.carddemo.common.codec.PackedDecimalCodec.PackedDecimalException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link PackedDecimalCodec} to the exact nibbles and bytes the three reference copybooks that
 * declare a computational usage require, for both the packed regime and the binary regime it owns.
 *
 * <p>Assumptions: three immutable artifacts are the authority for every expectation below, and each is
 * cited again at the test that depends on it. {@code app/cpy/CVEXPORT.cpy} is the 500-byte export
 * layout, and the only copybook under {@code app/cpy} that declares a computational usage at all.
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} is the authorization summary segment, whose
 * thirteen declarations at lines 19 to 31 close at exactly 100 bytes.
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} is the authorization detail segment, whose
 * 27 named elementary fields plus a 17-byte trailing filler close at exactly 200 bytes. No test here
 * reads those files: every vector and every length is a literal transcribed from them, so this class
 * runs with no COBOL compiler, no database and no emulator present.</p>
 *
 * <h2>Assumptions: USAGE fixes the physical width, and PICTURE never does</h2>
 *
 * <p>This is the single claim the whole class exists to make auditable, because a width taken from the
 * picture instead of the usage misaligns every field after it and still decodes to digits, so nothing
 * raises. {@code app/cpy/CVEXPORT.cpy} settles it inside one record: its account redefinition declares
 * three fields of the <em>same</em> picture, {@code PIC S9(10)V99}, at three different physical
 * widths.</p>
 *
 * <pre>
 * line  field                          declaration                    bytes
 *   50  EXP-ACCT-CURR-BAL              PIC S9(10)V99 COMP-3               7
 *   51  EXP-ACCT-CREDIT-LIMIT          PIC S9(10)V99                     12
 *   57  EXP-ACCT-CURR-CYC-DEBIT        PIC S9(10)V99 COMP                 8
 * </pre>
 *
 * <p>Assumptions: recognising a usage token has one hazard, and it is preserved here as a documented
 * declaration fact rather than as a parser. The spelling {@code COMP-3} CONTAINS the spelling
 * {@code COMP}, so any recognition that matches the shorter token first classifies all four packed
 * fields of that copybook as binary. At the twelve-digit geometry above that is a seven-versus-eight
 * byte disagreement, and because a record is sliced by running offsets the error does not stay local:
 * it displaces every field that follows, in every branch that contains one. The longest usage token
 * must therefore be matched first. This class does not implement a source parser to demonstrate that;
 * it asserts the production classification instead, through
 * {@link CopybookLayout#widthOf(Kind, int, int)} at the identical digit geometry, which is the same
 * decision expressed where the code actually makes it.</p>
 *
 * <p>Assumptions: a textual match count is not a declaration count, and the two are recorded
 * separately so that no test codifies the wrong one. Searching {@code app/cpy/CVEXPORT.cpy} for
 * {@code COMP-3} returns FIVE lines, of which FOUR are declarations -- lines 41, 50, 52 and 71 -- while
 * line 6 is prose, the banner {@code Storage Optimization: COMP/COMP-3 fields for numeric data}.
 * Searching the same file for a pure {@code COMP} declaration returns SEVEN, at lines 16, 25, 57, 72,
 * 87, 95 and 96. A count of the bare token {@code COMP} returns twelve, which is those seven plus the
 * five {@code COMP-3} lines, and that twelve is precisely the mis-tokenisation described above. No
 * assertion below uses any of these counts as a semantic quantity; they are recorded because a reader
 * checking the geometry against the file will otherwise derive the wrong one.</p>
 *
 * <h2>Assumptions: the export record is a discriminated union, not a sequence</h2>
 *
 * <p>{@code app/cpy/CVEXPORT.cpy} declares a 40-byte header and one {@code PIC X(460)} payload, then
 * redefines that payload FIVE times, at lines 24, 47, 65, 84 and 93. Those five branches ALIAS the same
 * 460 bytes; they do not accumulate. The record is therefore 500 bytes and not 40 plus five payloads,
 * and each branch is checked below against 460 individually. Flattening the union into one sequential
 * record would produce a record length no dataset has and offsets no program wrote.</p>
 *
 * <h2>Assumptions: the sign-nibble policy asserted here is the implementation's own, not a general one</h2>
 *
 * <p>The policy was read out of {@code PackedDecimalCodec} rather than generalised from another COBOL
 * runtime, because runtimes differ on exactly this point. The implementation admits three sign nibbles
 * and no others: {@code 0xC} for a signed non-negative value, {@code 0xD} for a signed negative one and
 * {@code 0xF} for a field declared without a leading {@code S}. It rejects {@code 0xA}, {@code 0xB} and
 * {@code 0xE} -- the alternate forms some encoders emit -- and it rejects them for both a signed and an
 * unsigned declaration. It rejects a value below {@code 0xA} in the sign position with a separate
 * diagnostic, because a digit there means the field is zoned rather than packed, or the read is off by
 * one nibble. It also cross-checks the nibble against the declared sign: a signed field carrying
 * {@code 0xF}, or an unsigned field carrying {@code 0xC} or {@code 0xD}, is refused rather than
 * decoded. Every one of those cases has a test below, and the tests would fail if the policy were
 * widened.</p>
 *
 * <h2>Assumptions: the unused nibble is a property of an EVEN digit count</h2>
 *
 * <p>The specification for this class describes the pad as the odd-digit case, and that wording is
 * reconciled here against the real nibble geometry rather than reproduced. A packed field occupies
 * {@code width * 2} nibbles and uses {@code digits + 1} of them, the extra one being the sign, so the
 * surplus is {@code width * 2 - (digits + 1)} and is at most one. Twelve digits occupy seven bytes,
 * which is fourteen nibbles for thirteen used, so ONE nibble pads. Eleven digits occupy six bytes,
 * which is twelve nibbles for twelve used, so NONE pads. The surplus therefore appears when the digit
 * count is EVEN, and a five-digit field's leading nibble is a real digit and not a pad. The invariant
 * that is actually asserted is neither parity-specific nor invented: any unused leading nibble must be
 * zero, and a non-zero one is refused.</p>
 *
 * <h2>Trade-offs: what this class deliberately does not cover</h2>
 *
 * <p>The four money-typed entry points of the codec are exercised here only through their scale guard,
 * and the module's monetary type is never named in this file. That type has its own suites in the
 * sibling money package, and this file's declared dependency set does not include it, so importing it
 * would assert an edge this test does not need: the guard refuses a field whose declared decimal
 * positions are not the money contract's scale, and refusing is observable without the type appearing
 * in a signature here. Zoned decimal is likewise out of scope except as the twelve-byte leg of the
 * three-usage proof, where {@link ZonedDecimalCodec} is called rather than reimplemented.</p>
 *
 * <p>Trade-offs: every vector below is written as an uppercase hexadecimal string and every geometry as
 * the two digit counts the picture clause states, rather than being assembled by a helper that mirrors
 * the production arithmetic. A helper would shorten the file and would also reproduce, in the test, the
 * very nibble packing under test, so one mistake in the production loop and the same mistake in the
 * helper would agree and the test would pass. The cost accepted is that a reader must count nibbles, so
 * each vector is accompanied by a statement of what occupies each position.</p>
 *
 * <p>Assumptions: money and every other quantity here is exact fixed point at every hop, carried in
 * {@link BigDecimal} at the scale its picture declares. Neither of the language's two IEEE-754 binary
 * primitive types, nor either of their wrapper types, appears anywhere in this file. The prohibition is
 * the migration's own, it is asserted for production code by the architecture suite in this module, and
 * the type names are described rather than spelled here for the reason the codec's own charter gives:
 * spelling them would make this file match a search for the very tokens the money path must not
 * contain.</p>
 *
 * <p>Parameters, return values and exceptions at type level: declared inapplicable. A class declaration
 * accepts no parameter, yields no value and raises nothing, so this block carries no parameter, return
 * or exception at-clause. Every member below carries its own.</p>
 */
class PackedDecimalCodecTest {

    /** Uppercase hexadecimal digits, indexed rather than formatted so no locale can alter a vector. */
    private static final String HEX_DIGITS = "0123456789ABCDEF";

    /**
     * The field name the sensitive-diagnostic tests submit, taken from a real packed money field.
     *
     * <p>Assumptions: {@code PA-TRANSACTION-AMT} is declared {@code PIC S9(10)V99 COMP-3} at line 34 of
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy}, so it is one of the two widest packed
     * fields in the corpus and it is genuinely an amount a diagnostic must not echo. Using a real name
     * rather than an invented label keeps the assertion legible to a reader holding the copybook.</p>
     */
    private static final String DETAIL_AMOUNT_FIELD = "PA-TRANSACTION-AMT";

    /**
     * The real binary amount name used when a binary diagnostic needs a field identity.
     *
     * <p>Assumptions: {@code EXP-ACCT-CURR-CYC-DEBIT} is declared
     * {@code PIC S9(10)V99 COMP} at line 57 of {@code app/cpy/CVEXPORT.cpy}. Using that declaration
     * keeps a diagnostic's kind, name and width mutually consistent.</p>
     */
    private static final String BINARY_AMOUNT_FIELD = "EXP-ACCT-CURR-CYC-DEBIT";

    /**
     * Parses an uppercase hexadecimal vector into the record bytes it denotes.
     *
     * <p>Assumptions: the parse is strict on case and on length so that a vector reads identically as an
     * input and as an expectation. {@link #hex(byte[])} renders uppercase, so accepting lowercase here
     * would allow a vector to be written one way and asserted another, and the two would no longer be
     * comparable by eye. Two hexadecimal characters are one byte, so an odd-length vector is a typing
     * error in the test rather than a decodable input.</p>
     *
     * @param hexText the vector as an even-length run of the characters {@code 0} to {@code 9} and
     *     {@code A} to {@code F}, with no separator and no prefix; must not be {@code null}
     * @return a newly allocated array holding one byte per character pair, in the order written
     * @throws IllegalArgumentException if {@code hexText} has an odd length or holds a character that is
     *     not an uppercase hexadecimal digit, either of which is a defect in the test vector itself
     * @throws NullPointerException if {@code hexText} is {@code null}, because absence is not a
     *     hexadecimal test vector
     */
    private static byte[] fromHex(String hexText) {
        if (hexText.length() % 2 != 0) {
            throw new IllegalArgumentException(
                    "a packed or binary vector must have an even number of hexadecimal characters,"
                            + " because two characters are one byte, but got " + hexText.length());
        }
        byte[] parsed = new byte[hexText.length() / 2];
        for (int index = 0; index < parsed.length; index++) {
            int high = HEX_DIGITS.indexOf(hexText.charAt(index * 2));
            int low = HEX_DIGITS.indexOf(hexText.charAt(index * 2 + 1));
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("a vector must use uppercase hexadecimal digits only,"
                        + " but character pair " + index + " of " + hexText + " is not one");
            }
            parsed[index] = (byte) ((high << 4) | low);
        }
        return parsed;
    }

    /**
     * Renders record bytes as the uppercase hexadecimal vector that denotes them.
     *
     * <p>Trade-offs: byte spans are compared as rendered strings rather than as arrays. An array
     * comparison reports the first differing index, which for a packed field is a byte holding two
     * nibbles and tells a reader neither which nibble moved nor whether the sign changed; the rendered
     * form puts the whole field beside the whole expectation so the difference is read directly against
     * the copybook. The cost accepted is one allocation per assertion, which no test here is sensitive
     * to.</p>
     *
     * <p>Assumptions: the digits are indexed out of a constant rather than formatted, which keeps the
     * rendering independent of any ambient locale. This mirrors what the codec itself does when it
     * names a nibble in a diagnostic, so a vector in a failure message and a nibble in an exception
     * message are written the same way.</p>
     *
     * @param value the bytes to render; every element contributes exactly two characters and the
     *     array must not be {@code null}
     * @return the uppercase hexadecimal rendering, twice as long as {@code value}
     * @throws NullPointerException if {@code value} is {@code null}, because absence has no byte
     *     rendering
     */
    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte element : value) {
            int unsigned = element & 0xFF;
            text.append(HEX_DIGITS.charAt(unsigned >> 4)).append(HEX_DIGITS.charAt(unsigned & 0x0F));
        }
        return text.toString();
    }

    /**
     * The packed width ladder follows the reference compiler at every copybook digit count in scope.
     *
     * <p>Assumptions: these five rungs are transcribed from the declarations that exercise packed
     * storage in the three reference copybooks. Keeping the integer and decimal counts separate makes
     * the twelve-digit money case visible rather than reducing every row to an anonymous total.</p>
     *
     * @param intDigits the integer positions declared before the implied point
     * @param decDigits the decimal positions declared after the implied point
     * @param expectedWidth the physical byte width emitted by the reference compiler
     */
    @ParameterizedTest
    @CsvSource({
        "3, 0, 2",
        "5, 0, 3",
        "9, 0, 5",
        "11, 0, 6",
        "10, 2, 7"
    })
    void packedWidthMatchesReferenceLadder(int intDigits, int decDigits, int expectedWidth) {
        assertEquals(expectedWidth, PackedDecimalCodec.packedWidth(intDigits, decDigits));
    }

    /**
     * {@code PIC S9(10)V99 COMP-3} occupies seven bytes and can never regress to six.
     *
     * <p>Alternatives Considered: relying on the parameterized ladder alone. This named guard is
     * retained because six bytes was the historical defect: a generic row would report a numerical
     * mismatch, while this method reports the exact copybook declaration whose wrong width shifts all
     * following fields.</p>
     */
    @Test
    void s9TenV99Comp3IsSevenBytesNeverSix() {
        assertEquals(7, PackedDecimalCodec.packedWidth(10, 2));
        assertEquals(7, CopybookLayout.widthOf(Kind.PACKED, 10, 2));
        assertThat(PackedDecimalCodec.packedWidth(10, 2)).isNotEqualTo(6);
    }

    /**
     * Packed width remains correct at the smallest geometry and at both sides of its widest boundary.
     *
     * <p>Assumptions: one digit is the smallest valid numeric picture and eighteen is the largest this
     * codec admits. Seventeen and eighteen occupy different packed widths, so testing both prevents an
     * endpoint change from being hidden by the more familiar copybook rungs.</p>
     *
     * @param intDigits the total digit count, expressed as integer positions for this boundary proof
     * @param expectedWidth the physical byte width implied by that count
     */
    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "2, 2",
        "17, 9",
        "18, 10"
    })
    void packedWidthHandlesEndpointGeometries(int intDigits, int expectedWidth) {
        assertEquals(expectedWidth, PackedDecimalCodec.packedWidth(intDigits, 0));
    }

    /**
     * A zero unused leading nibble is accepted for the twelve-digit packed money geometry.
     *
     * <p>Assumptions: the first zero in {@code 0123456789012C} is padding, the next twelve nibbles are
     * the digits of {@code 1234567890.12}, and {@code C} is the signed positive nibble. This directly
     * exercises the even-digit geometry that leaves one nibble unused.</p>
     */
    @Test
    void zeroUnusedLeadingNibbleIsAccepted() {
        BigDecimal decoded =
                PackedDecimalCodec.decodePacked(fromHex("0123456789012C"), 0, 10, 2, true);

        assertEquals(new BigDecimal("1234567890.12"), decoded);
    }

    /**
     * A non-zero unused leading nibble is rejected instead of being ignored.
     *
     * <p>Assumptions: changing only the first nibble of the accepted twelve-digit vector makes the
     * failure about padding alone. The expected exception is captured and inspected, so this test
     * raises no exception to its caller.</p>
     */
    @Test
    void nonzeroUnusedLeadingNibbleIsRejected() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(
                        fromHex("1123456789012C"), 0, 10, 2, true, "EXP-ACCT-CURR-BAL", false));

        assertThat(thrown.getMessage())
                .contains("must begin with a zero pad nibble")
                .contains("12 digit positions")
                .contains("found 0x1");
    }

    /**
     * An odd digit count starts immediately with a real digit and has no leading pad.
     *
     * <p>Assumptions: {@code PIC S9(05) COMP-3} holding {@code 12345} is the six-nibble sequence
     * {@code 1 2 3 4 5 C}. Treating its leading {@code 1} as padding would either reject a valid field
     * or silently decode a four-digit value.</p>
     */
    @Test
    void oddDigitCountBeginsWithARealDigit() {
        assertEquals(new BigDecimal("12345"),
                PackedDecimalCodec.decodePacked(fromHex("12345C"), 0, 5, 0, true));
    }

    /**
     * The three admitted sign nibbles decode under the signedness each one represents.
     *
     * <p>Assumptions: {@code C} is signed non-negative, {@code D} is signed negative and {@code F} is
     * unsigned. These are the only forms emitted by the reference compiler, so acceptance is limited
     * to exactly these rows.</p>
     *
     * @param vector the complete packed field, including its final sign nibble
     * @param expectedValue the exact decimal value represented by the vector
     * @param signed whether the corresponding picture clause carries a leading {@code S}
     */
    @ParameterizedTest
    @CsvSource({
        "12345C, 12345, true",
        "12345D, -12345, true",
        "750F, 750, false"
    })
    void admittedSignNibblesDecode(String vector, String expectedValue, boolean signed) {
        BigDecimal decoded = PackedDecimalCodec.decodePacked(
                fromHex(vector), 0, vector.length() - 1, 0, signed);

        assertEquals(new BigDecimal(expectedValue), decoded);
    }

    /**
     * Every decimal digit in the sign position is rejected as a likely usage or alignment error.
     *
     * <p>Assumptions: the first three nibbles remain the valid digits {@code 123}; only the terminal
     * nibble varies. The expected exception is captured and inspected, so this test raises no
     * exception to its caller.</p>
     *
     * @param signNibble the decimal nibble placed where a sign must occur
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void decimalNibbleInSignPositionIsRejected(int signNibble) {
        String vector = "123" + HEX_DIGITS.charAt(signNibble);

        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(fromHex(vector), 0, 3, 0, true));

        assertThat(thrown.getMessage())
                .contains("holds a digit where its sign nibble must be")
                .contains("found 0x" + signNibble);
    }

    /**
     * Alternate sign nibbles {@code A}, {@code B} and {@code E} are rejected for either declaration.
     *
     * <p>Alternatives Considered: accepting common alternate positive and negative forms. The
     * production policy deliberately rejects them because none occurs in the reference corpus and an
     * unexpected terminal nibble is valuable evidence of a wrong field boundary. The expected
     * exception is captured and inspected, so this test raises no exception to its caller.</p>
     *
     * <p>Assumptions: this assertion pins the inspected implementation policy rather than claiming a
     * universal packed-decimal rule. A runtime with a broader sign repertoire is not evidence that
     * these vectors should be accepted by this migration codec.</p>
     *
     * @param signNibble the alternate nibble placed in the terminal position
     * @param signed whether the attempted declaration carries a leading {@code S}
     */
    @ParameterizedTest
    @CsvSource({
        "A, true",
        "B, true",
        "E, true",
        "A, false",
        "B, false",
        "E, false"
    })
    void alternateSignNibblesAreRejected(String signNibble, boolean signed) {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(
                        fromHex("123" + signNibble), 0, 3, 0, signed));

        assertThat(thrown.getMessage())
                .contains("alternate sign nibble")
                .contains("found 0x" + signNibble);
    }

    /**
     * A valid sign nibble is still rejected when it contradicts the picture's declared signedness.
     *
     * <p>Assumptions: classification and declaration agreement are separate checks. Each vector uses a
     * sign nibble the codec recognises, so the expected failure proves that recognition alone is not
     * enough. The expected exception is captured and inspected, so this test raises no exception to
     * its caller.</p>
     *
     * @param vector the complete three-digit packed vector
     * @param signed whether the attempted declaration carries a leading {@code S}
     * @param expectedClause the declaration mismatch phrase expected in the diagnostic
     */
    @ParameterizedTest
    @CsvSource({
        "123F, true, declared with a sign",
        "123C, false, declared without a sign",
        "123D, false, declared without a sign"
    })
    void signNibbleMustAgreeWithDeclaredSignedness(
            String vector, boolean signed, String expectedClause) {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(fromHex(vector), 0, 3, 0, signed));

        assertThat(thrown.getMessage()).contains(expectedClause);
    }

    /**
     * Nibbles {@code A} through {@code F} are rejected in every digit position.
     *
     * <p>Assumptions: the invalid nibble is fixed at digit position two while the final {@code C}
     * remains a valid signed-positive nibble. Keeping the sign valid isolates digit validation from
     * sign validation. The expected exception is captured and inspected, so this test raises no
     * exception to its caller.</p>
     *
     * @param digitNibble the non-decimal nibble placed in the second digit position
     */
    @ParameterizedTest
    @ValueSource(strings = {"A", "B", "C", "D", "E", "F"})
    void nondecimalNibbleInDigitPositionIsRejected(String digitNibble) {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(
                        fromHex("1" + digitNibble + "3C"), 0, 3, 0, true));

        assertThat(thrown.getMessage())
                .contains("non-digit nibble at digit position 2 of 3")
                .contains("found 0x" + digitNibble);
    }

    /**
     * Packed decoding preserves the declared scale, including trailing decimal zero positions.
     *
     * <p>Assumptions: numerical equality alone cannot prove this contract because values with
     * different scales can compare as the same quantity. Both the value and its scale are therefore
     * asserted, with {@code S9(10)V99} supplying the migration's important scale-two case.</p>
     */
    @Test
    void packedDecodePreservesExactDeclaredScale() {
        BigDecimal amount =
                PackedDecimalCodec.decodePacked(fromHex("0123456789012C"), 0, 10, 2, true);
        BigDecimal count = PackedDecimalCodec.decodePacked(fromHex("12345C"), 0, 5, 0, true);

        assertEquals(new BigDecimal("1234567890.12"), amount);
        assertEquals(2, amount.scale());
        assertEquals(new BigDecimal("12345"), count);
        assertEquals(0, count.scale());
    }

    /**
     * Accepted packed vectors reproduce byte for byte after an exact decode and encode.
     *
     * <p>Trade-offs: the expected bytes are literals rather than values produced by another packing
     * helper. Repeating production arithmetic in a test would let the same nibble-placement error
     * agree at both ends. The corpus spans both signs, zero, the minimum width, an unsigned field, the
     * widest copybook amount and the largest eighteen-digit geometry.</p>
     *
     * @param expectedValue the exact value and scale represented by the vector
     * @param intDigits the declared integer digit positions
     * @param decDigits the declared decimal digit positions
     * @param signed whether the corresponding picture carries a leading {@code S}
     * @param vector the reference packed bytes rendered in uppercase hexadecimal
     */
    @ParameterizedTest
    @CsvSource({
        "1234567890.12, 10, 2, true, 0123456789012C",
        "-1234567890.12, 10, 2, true, 0123456789012D",
        "0.00, 10, 2, true, 0000000000000C",
        "5, 1, 0, true, 5C",
        "750, 3, 0, false, 750F",
        "9999999999.99, 10, 2, true, 0999999999999C",
        "999999999999999999, 18, 0, true, 0999999999999999999C"
    })
    void packedGoldenVectorsRoundTripByteForByte(
            String expectedValue, int intDigits, int decDigits, boolean signed, String vector) {
        BigDecimal decoded =
                PackedDecimalCodec.decodePacked(fromHex(vector), 0, intDigits, decDigits, signed);

        assertEquals(new BigDecimal(expectedValue), decoded);
        assertEquals(vector,
                hex(PackedDecimalCodec.encodePacked(decoded, intDigits, decDigits, signed)));
    }

    /**
     * Packed negative zero decodes as zero and re-encodes with the canonical positive sign nibble.
     *
     * <p>Assumptions: the exact decimal type carries no negative-zero state, so a value ALONE cannot
     * preserve the terminal {@code D}. This is the one documented exception to byte-identical round
     * trips on the plain pair of operations; every magnitude nibble remains unchanged and only
     * {@code D} becomes {@code C}. It is registered under identifier D-SIGNED-ZERO-PACKED in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and the sign-preserving pair
     * exercised below has no exception at all.</p>
     */
    @Test
    void packedNegativeZeroCanonicalizesToPositiveZero() {
        String negativeZero = "0000000000000D";
        BigDecimal decoded =
                PackedDecimalCodec.decodePacked(fromHex(negativeZero), 0, 10, 2, true);
        String reencoded = hex(PackedDecimalCodec.encodePacked(decoded, 10, 2, true));

        assertEquals(new BigDecimal("0.00"), decoded);
        assertEquals("0000000000000C", reencoded);
        assertThat(reencoded).isNotEqualTo(negativeZero);
    }

    /**
     * The sign-preserving pair reproduces a packed negative zero byte for byte.
     *
     * <p>Assumptions: the sign nibble is returned beside the value, so the {@code D} survives a decode
     * that the value alone cannot carry it through, and the encoder writes back the nibble it was given.
     * Both zero nibbles are asserted, because an implementation that always wrote {@code D} for a zero
     * would satisfy the negative case on its own.</p>
     */
    @Test
    void signPreservingPackedPairReproducesBothZeroSignNibbles() {
        String negativeZero = "0000000000000D";
        String positiveZero = "0000000000000C";

        PackedDecimalCodec.SignedPacked negative = PackedDecimalCodec.decodePackedPreservingSign(
                fromHex(negativeZero), 0, 10, 2, true);
        PackedDecimalCodec.SignedPacked positive = PackedDecimalCodec.decodePackedPreservingSign(
                fromHex(positiveZero), 0, 10, 2, true);

        assertEquals(new BigDecimal("0.00"), negative.value());
        assertEquals(0x0D, negative.signNibble());
        assertEquals(new BigDecimal("0.00"), positive.value());
        assertEquals(0x0C, positive.signNibble());

        assertEquals(negativeZero,
                hex(PackedDecimalCodec.encodePackedPreservingSign(negative, 10, 2, true)));
        assertEquals(positiveZero,
                hex(PackedDecimalCodec.encodePackedPreservingSign(positive, 10, 2, true)));
    }

    /**
     * The sign-preserving pair reproduces a non-zero packed value and its unsigned counterpart.
     *
     * <p>Assumptions: two vectors are used rather than one because the three nibbles this codec emits
     * split into two cases that a single vector cannot cover: a signed field, whose nibble agrees with
     * the value's own sign, and a field declared without the leading {@code S}, whose {@code F} agrees
     * with no sign at all and would be lost by a boolean sign flag.</p>
     */
    @Test
    void signPreservingPackedPairReproducesSignedAndUnsignedFields() {
        String signedNegative = "0000000158000D";
        String unsigned = "000F";

        PackedDecimalCodec.SignedPacked negative = PackedDecimalCodec.decodePackedPreservingSign(
                fromHex(signedNegative), 0, 10, 2, true);
        PackedDecimalCodec.SignedPacked plain = PackedDecimalCodec.decodePackedPreservingSign(
                fromHex(unsigned), 0, 3, 0, false);

        assertEquals(new BigDecimal("-1580.00"), negative.value());
        assertEquals(0x0D, negative.signNibble());
        assertEquals(new BigDecimal("0"), plain.value());
        assertEquals(0x0F, plain.signNibble());

        assertEquals(signedNegative,
                hex(PackedDecimalCodec.encodePackedPreservingSign(negative, 10, 2, true)));
        assertEquals(unsigned,
                hex(PackedDecimalCodec.encodePackedPreservingSign(plain, 3, 0, false)));
    }

    /**
     * A pair whose nibble contradicts a non-zero value, or is not one this codec emits, is refused.
     *
     * <p>Assumptions: both refusals are asserted together because they defend the same property from
     * two directions -- that every pair this record admits is one the encoder can lay down and the
     * decoder can read back to the same value. The alternate nibble {@code 0x0B} is used for the second
     * case because it is a real sign nibble that this codec deliberately does not accept, so it proves
     * the check tests membership rather than merely a numeric range.</p>
     */
    @Test
    void signPreservingPackedPairRefusesAnInadmissibleNibble() {
        PackedDecimalException contradiction = assertThrows(PackedDecimalException.class,
                () -> new PackedDecimalCodec.SignedPacked(new BigDecimal("1.00"), 0x0D));
        PackedDecimalException notEmitted = assertThrows(PackedDecimalException.class,
                () -> new PackedDecimalCodec.SignedPacked(BigDecimal.ZERO, 0x0B));

        assertThat(contradiction).hasMessageContaining("contradicts the value's own sign");
        assertThat(notEmitted).hasMessageContaining("0xC, 0xD or 0xF");
    }

    /**
     * A packed value needing more integer positions than the picture declares is rejected.
     *
     * <p>Assumptions: {@code 10000000000.00} needs eleven integer positions while
     * {@code S9(10)V99} declares ten. The expected exception is captured and inspected, so this test
     * raises no exception to its caller.</p>
     */
    @Test
    void packedEncodeRejectsIntegerOverflow() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodePacked(
                        new BigDecimal("10000000000.00"), 10, 2, true));

        assertThat(thrown.getMessage())
                .contains("declares 10 integer positions")
                .contains("value needs 11")
                .contains("value 10000000000.00");
    }

    /**
     * A packed value carrying surplus decimal precision is rejected instead of being reduced.
     *
     * <p>Trade-offs: the test supplies a non-zero third decimal place so accepting it would require a
     * rounding decision. That decision belongs to a business rule and not to a byte codec. The
     * expected exception is captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void packedEncodeRejectsFractionalPrecisionLoss() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodePacked(new BigDecimal("1.234"), 10, 2, true));

        assertThat(thrown.getMessage())
                .contains("declares 2 decimal positions")
                .contains("scale of 3")
                .contains("round silently")
                .contains("value 1.234");
    }

    /**
     * Packed encoding rejects an absent value and a negative value for an unsigned declaration.
     *
     * <p>Assumptions: both failures are declaration failures before any nibble can be written. The
     * expected exceptions are captured and inspected, so this test raises no exception to its
     * caller.</p>
     */
    @Test
    void packedEncodeRejectsAbsentAndUnsignedNegativeValues() {
        PackedDecimalException absent = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodePacked(null, 3, 0, true));
        PackedDecimalException unsignedNegative = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodePacked(new BigDecimal("-1"), 3, 0, false));

        assertThat(absent.getMessage()).contains("cannot be encoded from a null value");
        assertThat(unsignedNegative.getMessage())
                .contains("declared without a sign")
                .contains("value -1");
    }

    /**
     * Packed decoding rejects an absent record, a negative offset and a span shorter than its width.
     *
     * <p>Assumptions: these are the three addressability failures the public decode contract exposes.
     * A longer containing record is valid because the offset selects one field, so the length case
     * deliberately supplies one byte fewer than the seven-byte field requires. Every expected
     * exception is captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void packedDecodeRejectsInvalidRecordSpans() {
        PackedDecimalException absent = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(null, 0, 10, 2, true));
        PackedDecimalException negativeOffset = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(
                        fromHex("0123456789012C"), -1, 10, 2, true));
        PackedDecimalException shortRecord = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(
                        fromHex("012345678901"), 0, 10, 2, true));

        assertThat(absent.getMessage()).contains("record byte array is null");
        assertThat(negativeOffset.getMessage()).contains("negative offset");
        assertThat(shortRecord.getMessage())
                .contains("extends past the end of a record of 6 bytes")
                .contains("length 7");
    }

    /**
     * Packed and binary width entry points reject every invalid digit geometry consistently.
     *
     * <p>Assumptions: negative counts, a zero-position field and a field beyond eighteen positions are
     * metadata defects rather than data defects. Both storage regimes call the same geometry guard,
     * so each row asserts both public paths and inspects the captured exceptions.</p>
     *
     * @param intDigits the attempted integer digit count
     * @param decDigits the attempted decimal digit count
     * @param expectedClause the diagnostic phrase identifying the violated geometry rule
     */
    @ParameterizedTest
    @CsvSource({
        "-1, 2, must not be negative",
        "10, -1, must not be negative",
        "0, 0, at least one digit position",
        "18, 1, more than 18 digit positions"
    })
    void computationalWidthsRejectInvalidMetadata(
            int intDigits, int decDigits, String expectedClause) {
        PackedDecimalException packed = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.packedWidth(intDigits, decDigits));
        PackedDecimalException binary = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.binaryWidth(intDigits, decDigits));

        assertThat(packed.getMessage()).contains(expectedClause);
        assertThat(binary.getMessage()).contains(expectedClause);
    }

    /**
     * Geometry is validated before a packed decoder attempts to read an absent record.
     *
     * <p>Assumptions: deterministic validation order matters to callers diagnosing a broken layout.
     * A zero-digit declaration and a null record violate two contracts; reporting the geometry first
     * points to the layout defect that would make every record fail. The expected exception is
     * captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void packedGeometryFailurePrecedesAbsentRecordFailure() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(null, 0, 0, 0, true));

        assertThat(thrown.getMessage())
                .contains("at least one digit position")
                .doesNotContain("record byte array is null");
    }

    /**
     * The codec exposes its domain failure as a nested subtype of the platform argument exception.
     *
     * <p>Assumptions: callers may catch the precise nested type for malformed computational fields or
     * the broader argument type for a whole validation boundary. This inheritance test pins both
     * choices without constructing an exception through its package-private constructor.</p>
     */
    @Test
    void packedDecimalExceptionIsAnArgumentException() {
        assertThat(PackedDecimalException.class).isAssignableTo(IllegalArgumentException.class);
    }

    /**
     * Binary width follows the two-, four- and eight-byte storage tiers at every boundary.
     *
     * <p>Assumptions: four total digits are the last halfword geometry, nine are the last fullword
     * geometry and every valid count above nine takes eight bytes. The rows immediately before and
     * after both transitions make the step function explicit.</p>
     *
     * @param intDigits the integer positions declared before the implied point
     * @param decDigits the decimal positions declared after the implied point
     * @param expectedWidth the physical byte width selected by the storage tier
     */
    @ParameterizedTest
    @CsvSource({
        "1, 0, 2",
        "4, 0, 2",
        "5, 0, 4",
        "9, 0, 4",
        "10, 0, 8",
        "10, 2, 8",
        "18, 0, 8"
    })
    void binaryWidthMatchesStorageTiers(int intDigits, int decDigits, int expectedWidth) {
        assertEquals(expectedWidth, PackedDecimalCodec.binaryWidth(intDigits, decDigits));
    }

    /**
     * The two binary declarations called out by the copybooks select two and eight bytes respectively.
     *
     * <p>Assumptions: {@code PIC S9(04) COMP} is used for both authorization counters in
     * {@code CIPAUSMY.cpy}, while {@code PIC S9(10)V99 COMP} is
     * {@code EXP-ACCT-CURR-CYC-DEBIT} at line 57 of {@code CVEXPORT.cpy}. Naming both guards the
     * smallest and largest binary forms that carry business values in this corpus.</p>
     */
    @Test
    void copybookBinaryDeclarationsSelectTheirMachineWidths() {
        assertEquals(2, PackedDecimalCodec.binaryWidth(4, 0));
        assertEquals(8, PackedDecimalCodec.binaryWidth(10, 2));
        assertEquals(2, CopybookLayout.widthOf(Kind.BINARY, 4, 0));
        assertEquals(8, CopybookLayout.widthOf(Kind.BINARY, 10, 2));
    }

    /**
     * Binary golden vectors decode and re-encode in big-endian two's-complement order.
     *
     * <p>Trade-offs: every byte string is a literal independently computed from the stored whole
     * number, not output captured from the implementation. The rows include both signs at every width
     * and the one-cent values that prove the implied point moves without loss.</p>
     *
     * @param expectedValue the exact value and scale represented by the vector
     * @param intDigits the declared integer digit positions
     * @param decDigits the declared decimal digit positions
     * @param vector the reference binary bytes rendered in uppercase hexadecimal
     */
    @ParameterizedTest
    @CsvSource({
        "1234, 4, 0, 04D2",
        "-1234, 4, 0, FB2E",
        "123456789, 9, 0, 075BCD15",
        "-123456789, 9, 0, F8A432EB",
        "1234567890.12, 10, 2, 0000001CBE991A14",
        "-1234567890.12, 10, 2, FFFFFFE34166E5EC",
        "0.01, 10, 2, 0000000000000001",
        "-0.01, 10, 2, FFFFFFFFFFFFFFFF"
    })
    void binaryGoldenVectorsRoundTripByteForByte(
            String expectedValue, int intDigits, int decDigits, String vector) {
        BigDecimal decoded =
                PackedDecimalCodec.decodeBinary(fromHex(vector), 0, intDigits, decDigits, true);

        assertEquals(new BigDecimal(expectedValue), decoded);
        assertEquals(decDigits, decoded.scale());
        assertEquals(vector,
                hex(PackedDecimalCodec.encodeBinary(decoded, intDigits, decDigits, true)));
    }

    /**
     * The maximum positive and negative decimal magnitudes fit at all three binary widths.
     *
     * <p>Assumptions: the limits here come from the picture, not from the wider machine container.
     * For example a halfword can hold {@code 32767}, but {@code S9(04)} admits only four decimal
     * positions, so {@code 9999} is the positive fitting boundary the migration may encode.</p>
     *
     * @param expectedValue the boundary value represented by the vector
     * @param intDigits the declared integer digit positions
     * @param decDigits the declared decimal digit positions
     * @param vector the big-endian two's-complement reference bytes
     */
    @ParameterizedTest
    @CsvSource({
        "9999, 4, 0, 270F",
        "-9999, 4, 0, D8F1",
        "999999999, 9, 0, 3B9AC9FF",
        "-999999999, 9, 0, C4653601",
        "9999999999.99, 10, 2, 000000E8D4A50FFF",
        "-9999999999.99, 10, 2, FFFFFF172B5AF001"
    })
    void binaryPictureBoundariesFitExactly(
            String expectedValue, int intDigits, int decDigits, String vector) {
        BigDecimal value = new BigDecimal(expectedValue);

        assertEquals(vector, hex(PackedDecimalCodec.encodeBinary(value, intDigits, decDigits, true)));
        assertEquals(value,
                PackedDecimalCodec.decodeBinary(fromHex(vector), 0, intDigits, decDigits, true));
    }

    /**
     * Binary encoding and decoding reject values that exceed the declared integer positions.
     *
     * <p>Assumptions: {@code 10000} exceeds {@code S9(04)} on encode, while the valid halfword bytes
     * {@code 8000} represent {@code -32768} and therefore exceed the same picture on decode. The
     * latter proves that being representable by the machine unit is not enough. Both expected
     * exceptions are captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void binaryPathsRejectPictureOverflow() {
        PackedDecimalException encodeFailure = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodeBinary(new BigDecimal("10000"), 4, 0, true));
        PackedDecimalException decodeFailure = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodeBinary(fromHex("8000"), 0, 4, 0, true));

        assertThat(encodeFailure.getMessage())
                .contains("declares 4 integer positions")
                .contains("value needs 5");
        assertThat(decodeFailure.getMessage())
                .contains("declares 4 integer positions")
                .contains("value needs 5")
                .contains("value -32768");
    }

    /**
     * Unsigned binary declarations reject negative values on both decode and encode.
     *
     * <p>Assumptions: the unsigned-negative decode branch is reachable only for the eight-byte tier,
     * because the accumulator is wide enough to hold every shorter unsigned pattern without wrapping.
     * Eight {@code F} bytes produce the stored value {@code -1}; the matching encode case supplies
     * that value directly. Both expected exceptions are captured and inspected, so this test raises no
     * exception to its caller.</p>
     */
    @Test
    void unsignedBinaryFieldRejectsNegativeStorageAndValue() {
        PackedDecimalException decodeFailure = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodeBinary(
                        fromHex("FFFFFFFFFFFFFFFF"), 0, 18, 0, false));
        PackedDecimalException encodeFailure = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodeBinary(new BigDecimal("-1"), 18, 0, false));

        assertThat(decodeFailure.getMessage())
                .contains("declared without a sign")
                .contains("negative stored value")
                .contains("value -1");
        assertThat(encodeFailure.getMessage())
                .contains("declared without a sign")
                .contains("negative value")
                .contains("value -1");
    }

    /**
     * Binary decoding requires the entire selected machine unit to lie within the record.
     *
     * <p>Assumptions: the seven supplied bytes are one short of the eight selected by
     * {@code S9(10)V99 COMP}. The expected exception is captured and inspected, so this test raises no
     * exception to its caller.</p>
     */
    @Test
    void binaryDecodeRejectsShortRecordSpan() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodeBinary(
                        fromHex("0000001CBE991A"), 0, 10, 2, true));

        assertThat(thrown.getMessage())
                .contains("length 8")
                .contains("record of 7 bytes")
                .contains("layout and the record disagree");
    }

    /**
     * One {@code S9(10)V99} value survives the packed, display and binary usages at distinct widths.
     *
     * <p>Assumptions: lines 50, 51 and 57 of {@code app/cpy/CVEXPORT.cpy} declare the same picture as
     * {@code COMP-3}, display and {@code COMP}. The negative value exercises each regime's sign form:
     * terminal {@code D} for packed, terminal {@code K} as the overpunched display digit two, and an
     * eight-byte two's-complement binary whole number.</p>
     */
    @Test
    void identicalPictureUsesThreeDistinctPhysicalRepresentations() {
        BigDecimal value = new BigDecimal("-1234567890.12");
        byte[] packed = PackedDecimalCodec.encodePacked(value, 10, 2, true);
        String zoned = ZonedDecimalCodec.encode(value, 10, 2, true);
        byte[] binary = PackedDecimalCodec.encodeBinary(value, 10, 2, true);

        assertEquals(7, packed.length);
        assertEquals("0123456789012D", hex(packed));
        assertEquals(12, zoned.length());
        assertEquals("12345678901K", zoned);
        assertEquals(8, binary.length);
        assertEquals("FFFFFFE34166E5EC", hex(binary));
        assertEquals(value, PackedDecimalCodec.decodePacked(packed, 0, 10, 2, true));
        assertEquals(value, ZonedDecimalCodec.decode(zoned, 10, 2, true));
        assertEquals(value, PackedDecimalCodec.decodeBinary(binary, 0, 10, 2, true));
    }

    /**
     * The usage kind, not the picture, selects seven, twelve or eight physical bytes.
     *
     * <p>Assumptions: the longest usage token must be recognised first because {@code COMP-3}
     * contains {@code COMP}. The five textual {@code COMP-3} matches in {@code CVEXPORT.cpy} include
     * one prose banner and only four declarations, while seven pure {@code COMP} declarations make a
     * naive bare-token count of twelve. These assertions avoid both hazards by passing the already
     * classified kind to the production width selector.</p>
     */
    @Test
    void usageKindAloneSelectsThePhysicalWidth() {
        assertEquals(7, CopybookLayout.widthOf(Kind.PACKED, 10, 2));
        assertEquals(12, CopybookLayout.widthOf(Kind.ZONED, 10, 2));
        assertEquals(8, CopybookLayout.widthOf(Kind.BINARY, 10, 2));
        assertEquals(12, ZonedDecimalCodec.widthOf(10, 2));
    }

    /**
     * Character and unsigned-display kinds refuse a digit-derived width.
     *
     * <p>Assumptions: {@code TEXT} and {@code UINT} take their physical width directly from their
     * character count, so supplying picture digits would create two competing width declarations.
     * Both expected exceptions are captured and inspected, so this test raises no exception to its
     * caller.</p>
     */
    @Test
    void characterKindsRefuseDigitDerivedWidth() {
        LayoutException text = assertThrows(LayoutException.class,
                () -> CopybookLayout.widthOf(Kind.TEXT, 10, 2));
        LayoutException unsigned = assertThrows(LayoutException.class,
                () -> CopybookLayout.widthOf(Kind.UINT, 3, 0));

        assertThat(text.getMessage())
                .contains("width of a TEXT field is its declared character count")
                .contains("only ZONED, PACKED and BINARY widths are derived");
        assertThat(unsigned.getMessage())
                .contains("width of a UINT field is its declared character count")
                .contains("only ZONED, PACKED and BINARY widths are derived");
    }

    /**
     * Each {@code CVEXPORT.cpy} payload redefinition closes at 460 bytes independently.
     *
     * <p>Assumptions: the five branches beginning at lines 24, 47, 65, 84 and 93 alias one
     * {@code PIC X(460)} payload; they are alternatives and never sequential fields. The 40-byte
     * header is added to exactly one payload to obtain the documented 500-byte record.</p>
     *
     * <p>Refactoring Rationale: every computational term below is now a call into the unit under test
     * rather than the byte count it yields. In its earlier form this test summed literals only, so no
     * production symbol took part in it and it could not fail for any reason connected to the codec --
     * a width helper that began returning six bytes for {@code S9(10)V99 COMP-3} would leave these sums
     * untouched and this test green. Substituting the calls makes each sum a derivation FROM the code
     * under test, so the copybook total is what verifies the helper rather than merely accompanying
     * it. The character and display terms stay literal deliberately: their width is their declared
     * character count, and this class asserts elsewhere that the width helper refuses to derive
     * one.</p>
     */
    @Test
    void exportRedefinitionsCloseIndividuallyWithoutAccumulating() {
        // WHY : Assumptions: each named width states the picture it comes from, so a reader can check a
        //       term against CVEXPORT.cpy without counting bytes. EXP-CUST-FICO-CREDIT-SCORE at line 41
        //       is PIC 9(03) COMP-3; EXP-ACCT-CURR-BAL at line 50 and EXP-ACCT-CASH-CREDIT-LIMIT at
        //       line 52 are PIC S9(10)V99 COMP-3; EXP-TRAN-AMT at line 71 is PIC S9(09)V99 COMP-3.
        int ficoPacked = PackedDecimalCodec.packedWidth(3, 0);
        int accountMoneyPacked = PackedDecimalCodec.packedWidth(10, 2);
        int transactionMoneyPacked = PackedDecimalCodec.packedWidth(9, 2);

        // WHY : Assumptions: the binary terms are EXPORT-SEQUENCE-NUM at line 16 and
        //       EXP-CUST-ID at line 25 and EXP-TRAN-MERCHANT-ID at line 72, all PIC 9(09) COMP;
        //       EXP-XREF-ACCT-ID at line 87 and EXP-CARD-ACCT-ID at line 95, both PIC 9(11) COMP;
        //       EXP-CARD-CVV-CD at line 96, PIC 9(03) COMP; and EXP-ACCT-CURR-CYC-DEBIT at line 57,
        //       PIC S9(10)V99 COMP. Their widths come from the halfword, fullword and doubleword tiers
        //       rather than from a digit count, which is why they are separate calls and not one.
        int nineDigitBinary = PackedDecimalCodec.binaryWidth(9, 0);
        int elevenDigitBinary = PackedDecimalCodec.binaryWidth(11, 0);
        int cvvBinary = PackedDecimalCodec.binaryWidth(3, 0);
        int accountMoneyBinary = PackedDecimalCodec.binaryWidth(10, 2);

        // WHY : Assumptions: the zoned terms are EXP-ACCT-CREDIT-LIMIT at line 51 and
        //       EXP-ACCT-CURR-CYC-CREDIT at line 56, both PIC S9(10)V99 with no COMP clause, so they
        //       occupy display bytes. Taking them from the zoned helper rather than writing twelve is
        //       what makes the account branch a comparison of THREE regimes -- packed, binary and
        //       zoned -- against one copybook total, which is the property that catches a width helper
        //       returning another regime's answer.
        int accountMoneyZoned = ZonedDecimalCodec.widthOf(10, 2);

        int customer = nineDigitBinary + 25 + 25 + 25 + 150 + 2 + 3 + 10 + 30 + 9 + 20 + 10 + 10 + 1
                + ficoPacked + 134;
        int account = 11 + 1 + accountMoneyPacked + accountMoneyZoned + accountMoneyPacked + 10 + 10
                + 10 + accountMoneyZoned + accountMoneyBinary + 10 + 10 + 352;
        int transaction = 16 + 2 + 4 + 10 + 100 + transactionMoneyPacked + nineDigitBinary + 50 + 50
                + 10 + 16 + 26 + 26 + 140;
        int cardCrossReference = 16 + 9 + elevenDigitBinary + 427;
        int card = 16 + elevenDigitBinary + cvvBinary + 50 + 10 + 1 + 373;
        int header = 1 + 26 + nineDigitBinary + 4 + 5;

        assertEquals(460, customer);
        assertEquals(460, account);
        assertEquals(460, transaction);
        assertEquals(460, cardCrossReference);
        assertEquals(460, card);
        assertEquals(40, header);
        assertEquals(500, header + account);
        assertThat(header + customer + account + transaction + cardCrossReference + card)
                .isNotEqualTo(500);
    }

    /**
     * Treating both account packed amounts as six bytes leaves the payload two bytes short.
     *
     * <p>Alternatives Considered: checking only that the correct branch totals 460. This
     * proof-by-contradiction is retained because it identifies the exact consequence of the historical
     * six-byte claim: two packed declarations each lose one byte, yielding 458 rather than a merely
     * unspecified mismatch.</p>
     *
     * <p>Refactoring Rationale: the wrong width is now DERIVED from the unit under test rather than
     * written as the literal six, and the derivation is what makes the contradiction attributable. Six
     * bytes is exactly what {@code packedWidth} returns for an ELEVEN-digit picture, so the historical
     * error was reading {@code S9(10)V99} as though it declared one integer digit fewer -- a misreading
     * of the picture, not of the packing rule. Expressing it as the neighbouring digit count says which
     * mistake produces 458; the literal six said only that some other number had been used. In its
     * earlier form this test summed literals alone and no production symbol took part, so it could not
     * fail for any reason connected to the codec.</p>
     */
    @Test
    void sixBytePackedClaimMakesAccountBranchOnly458Bytes() {
        int correctPackedWidth = PackedDecimalCodec.packedWidth(10, 2);
        int widthOfTheNeighbouringPicture = PackedDecimalCodec.packedWidth(9, 2);
        int accountMoneyZoned = ZonedDecimalCodec.widthOf(10, 2);
        int accountMoneyBinary = PackedDecimalCodec.binaryWidth(10, 2);

        // WHY : Assumptions: the two widths must differ by exactly one byte, which is the whole content
        //       of the historical error. Packed width is the digit count halved and incremented, so
        //       eleven digits and twelve digits land in adjacent bytes and one dropped integer position
        //       costs exactly one byte. Asserting the difference rather than the two values keeps the
        //       claim about the relationship the contradiction below depends on.
        assertEquals(7, correctPackedWidth);
        assertEquals(6, widthOfTheNeighbouringPicture);
        assertEquals(1, correctPackedWidth - widthOfTheNeighbouringPicture);

        int branchWithWrongPackedWidths = 11 + 1 + widthOfTheNeighbouringPicture + accountMoneyZoned
                + widthOfTheNeighbouringPicture + 10 + 10 + 10 + accountMoneyZoned
                + accountMoneyBinary + 10 + 10 + 352;

        assertEquals(458, branchWithWrongPackedWidths);
        assertThat(branchWithWrongPackedWidths).isNotEqualTo(460);
    }

    /**
     * The pending-authorization summary declarations close at exactly 100 bytes.
     *
     * <p>Assumptions: the terms follow {@code CIPAUSMY.cpy} lines 19 to 31 in order: a six-byte
     * packed account identifier, nine display digits, one status, five two-byte statuses, four
     * six-byte amounts, two halfword counters, two further six-byte amounts and a 34-byte filler.</p>
     */
    @Test
    void pendingAuthorizationSummaryClosesAt100Bytes() {
        int summaryLength = 6 + 9 + 1 + 10 + 24 + 4 + 12 + 34;

        assertEquals(100, summaryLength);
        assertEquals(6, PackedDecimalCodec.packedWidth(11, 0));
        assertEquals(6, PackedDecimalCodec.packedWidth(9, 2));
        assertEquals(2, PackedDecimalCodec.binaryWidth(4, 0));
    }

    /**
     * Both detail money fields carry seven-byte packed geometry and remain contiguous.
     *
     * <p>Assumptions: after the 74 bytes preceding line 34 of {@code CIPAUDTY.cpy},
     * {@code PA-TRANSACTION-AMT} occupies offsets 74 through 80 and
     * {@code PA-APPROVED-AMT} occupies offsets 81 through 87. The field descriptors derive rather
     * than accept their lengths, so this also proves the metadata exposed to callers.</p>
     */
    @Test
    void pendingAuthorizationDetailMoneyFieldsAreSevenBytesEach() {
        var transactionAmount =
                CopybookLayout.packed("PA-TRANSACTION-AMT", 74, 10, 2, true);
        var approvedAmount =
                CopybookLayout.packed("PA-APPROVED-AMT", transactionAmount.end(), 10, 2, true);

        assertEquals(7, transactionAmount.length());
        assertEquals(81, transactionAmount.end());
        assertEquals(7, approvedAmount.length());
        assertEquals(88, approvedAmount.end());
        assertEquals(Kind.PACKED, transactionAmount.kind());
        assertEquals(2, transactionAmount.decDigits());
        assertThat(transactionAmount.signed()).isTrue();
        assertThat(transactionAmount.sensitive()).isFalse();
    }

    /**
     * The pending-authorization detail declarations close at exactly 200 bytes.
     *
     * <p>Assumptions: every term follows {@code CIPAUDTY.cpy} in declaration order, including the
     * eight-byte composite key and the 17-byte trailing filler. The merchant category at line 36 is a
     * four-character field and is counted as such; this test does not attempt to decode it as a
     * computational value.</p>
     *
     * <p>Refactoring Rationale: the three computational terms are now calls into the unit under test,
     * matching the pattern the summary-segment test beside this one already used. In its earlier form
     * every term was a literal, so the 200-byte total could not disagree with the codec about anything
     * -- it agreed with itself. The composite key is expressed as its two declared components rather
     * than as the eight bytes they occupy, because the segment declares them separately at lines 20 and
     * 21 and their individual widths are what a caller slicing the key depends on.</p>
     */
    @Test
    void pendingAuthorizationDetailClosesAt200Bytes() {
        // WHY : Assumptions: PA-AUTH-DATE-9C at line 20 is PIC S9(05) COMP-3 and PA-AUTH-TIME-9C at
        //       line 21 is PIC S9(09) COMP-3, so the group item at line 19 is the sum of the two and
        //       never a declared width of its own. PA-TRANSACTION-AMT at line 34 and PA-APPROVED-AMT at
        //       line 35 are both PIC S9(10)V99 COMP-3.
        int authDateKeyComponent = PackedDecimalCodec.packedWidth(5, 0);
        int authTimeKeyComponent = PackedDecimalCodec.packedWidth(9, 0);
        int detailMoney = PackedDecimalCodec.packedWidth(10, 2);
        int compositeKey = authDateKeyComponent + authTimeKeyComponent;

        assertEquals(3, authDateKeyComponent);
        assertEquals(5, authTimeKeyComponent);
        assertEquals(8, compositeKey);

        int detailLength = compositeKey + 6 + 6 + 16 + 4 + 4 + 6 + 6 + 6 + 2 + 4 + 6
                + detailMoney + detailMoney + 4 + 3 + 2 + 15 + 22 + 13 + 2 + 9 + 15 + 1 + 1 + 8 + 17;

        assertEquals(200, detailLength);
    }

    /**
     * Substituting six bytes for both detail money fields produces 198 rather than 200 bytes.
     *
     * <p>Alternatives Considered: inferring the seven-byte correction only from the successful
     * 200-byte sum. This separate contradiction makes the two missing bytes attributable: one from
     * each {@code S9(10)V99 COMP-3} declaration at lines 34 and 35.</p>
     *
     * <p>Refactoring Rationale: as in the account-branch contradiction, the wrong width is derived
     * from the unit under test as the width of the neighbouring nine-integer-digit picture rather than
     * written as the literal six. That is what identifies the error as a misread picture rather than a
     * misapplied packing rule, and it is what gives this test a dependency on the code it is about; the
     * earlier all-literal form had none.</p>
     */
    @Test
    void sixByteDetailMoneyClaimProduces198Bytes() {
        int compositeKey =
                PackedDecimalCodec.packedWidth(5, 0) + PackedDecimalCodec.packedWidth(9, 0);
        int widthOfTheNeighbouringPicture = PackedDecimalCodec.packedWidth(9, 2);

        assertEquals(1,
                PackedDecimalCodec.packedWidth(10, 2) - widthOfTheNeighbouringPicture,
                "the misread picture must cost exactly one byte per declaration");

        int detailLengthWithWrongMoneyWidths = compositeKey + 6 + 6 + 16 + 4 + 4 + 6 + 6 + 6 + 2 + 4
                + 6 + widthOfTheNeighbouringPicture + widthOfTheNeighbouringPicture + 4 + 3 + 2 + 15
                + 22 + 13 + 2 + 9 + 15 + 1 + 1 + 8 + 17;

        assertEquals(198, detailLengthWithWrongMoneyWidths);
        assertThat(detailLengthWithWrongMoneyWidths).isNotEqualTo(200);
    }

    /**
     * The eight-byte authorization key decodes as its five- and nine-digit packed components.
     *
     * <p>Assumptions: {@code PA-AUTH-DATE-9C} is the first three bytes and contains {@code 24197};
     * {@code PA-AUTH-TIME-9C} is the following five and contains {@code 101530123}. Decoding from one
     * combined array proves both the component widths and the second component's offset.</p>
     */
    @Test
    void compositeAuthorizationKeyDecodesBothPackedComponents() {
        byte[] key = fromHex("24197C101530123C");

        BigDecimal date = PackedDecimalCodec.decodePacked(key, 0, 5, 0, true);
        BigDecimal time = PackedDecimalCodec.decodePacked(key, 3, 9, 0, true);

        assertEquals(8, key.length);
        assertEquals(new BigDecimal("24197"), date);
        assertEquals(new BigDecimal("101530123"), time);
    }

    /**
     * A non-sensitive unnamed field diagnostic identifies geometry and the offending nibble.
     *
     * <p>Assumptions: the geometry-only overload deliberately has no field name, but it still reports
     * the offset, length, kind and invalid nibble needed to locate a malformed span. The expected
     * exception is captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void unnamedFieldDiagnosticIdentifiesGeometryAndNibble() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(fromHex("1A3C"), 0, 3, 0, true));

        assertThat(thrown.getMessage())
                .startsWith("packed field at an unnamed position at offset 0 of length 2")
                .contains("non-digit nibble at digit position 2 of 3")
                .contains("found 0xA");
    }

    /**
     * A sensitive packed decode diagnostic names the field but withholds bytes and hexadecimal detail.
     *
     * <p>Trade-offs: the malformed vector begins with a non-zero pad, which would ordinarily report
     * {@code found 0x9}. Marking the field sensitive preserves the actionable geometry while removing
     * that nibble and the complete vector. The expected exception is captured and inspected, so this
     * test raises no exception to its caller.</p>
     */
    @Test
    void sensitivePackedDecodeDiagnosticWithholdsContent() {
        String rawVector = "9123456789012C";
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(
                        fromHex(rawVector), 0, 10, 2, true, DETAIL_AMOUNT_FIELD, true));

        assertThat(thrown.getMessage())
                .startsWith("packed field " + DETAIL_AMOUNT_FIELD + " at offset 0 of length 7")
                .contains("(content withheld: field is marked sensitive)")
                .contains("must begin with a zero pad nibble")
                .doesNotContain("found", "0x", rawVector);
    }

    /**
     * A sensitive packed encode diagnostic withholds the rejected amount and has no record offset.
     *
     * <p>Assumptions: encode operates on a field value rather than on a containing record, so no
     * offset exists to report. The field name, kind and seven-byte geometry remain sufficient to find
     * the declaration. The expected exception is captured and inspected, so this test raises no
     * exception to its caller.</p>
     */
    @Test
    void sensitivePackedEncodeDiagnosticWithholdsValueAndOffset() {
        String rejectedValue = "10000000000.00";
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodePacked(
                        new BigDecimal(rejectedValue), 10, 2, true, DETAIL_AMOUNT_FIELD, true));

        assertThat(thrown.getMessage())
                .startsWith("packed field " + DETAIL_AMOUNT_FIELD + " of length 7")
                .contains("(content withheld: field is marked sensitive)")
                .contains("declares 10 integer positions")
                .doesNotContain("offset", "; value", rejectedValue);
    }

    /**
     * A sensitive binary decode diagnostic also withholds the negative stored value.
     *
     * <p>Assumptions: eight {@code F} bytes would disclose no business meaning by themselves, but the
     * decoded whole number is still field content and follows the same suppression rule. The expected
     * exception is captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void sensitiveBinaryDecodeDiagnosticWithholdsStoredValue() {
        String rawVector = "FFFFFFFFFFFFFFFF";
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodeBinary(
                        fromHex("00000000" + rawVector), 4, 18, 0, false,
                        BINARY_AMOUNT_FIELD, true));

        assertThat(thrown.getMessage())
                .startsWith("binary field " + BINARY_AMOUNT_FIELD + " at offset 4 of length 8")
                .contains("(content withheld: field is marked sensitive)")
                .contains("negative stored value")
                .doesNotContain("; value", "-1", rawVector, "0x");
    }

    /**
     * Digit validation precedes sign validation when both nibbles are malformed.
     *
     * <p>Assumptions: {@code 1A3E} has an invalid {@code A} at digit position two and an alternate
     * {@code E} sign. Reporting the leftmost digit defect first makes validation deterministic and
     * points at the earliest evidence of misalignment. The expected exception is captured and
     * inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void malformedDigitIsReportedBeforeMalformedSign() {
        PackedDecimalException thrown = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePacked(fromHex("1A3E"), 0, 3, 0, true));

        assertThat(thrown.getMessage())
                .contains("non-digit nibble at digit position 2 of 3")
                .contains("found 0xA")
                .doesNotContain("alternate sign nibble", "found 0xE");
    }

    /**
     * All four money-typed entry points reject a field whose declared scale is not two.
     *
     * <p>Trade-offs: the module monetary type is intentionally neither imported nor named as a local
     * variable here, because it is outside this file's dependency whitelist and has its own tests.
     * Passing {@code null} is safe for this proof because the scale guard runs before source or amount
     * validation. Each expected exception is captured and inspected, so this test raises no exception
     * to its caller.</p>
     */
    @Test
    void moneyEntryPointsRejectNonContractScaleBeforeContent() {
        PackedDecimalException packedDecode = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodePackedMoney(
                        null, 0, 10, 3, true, DETAIL_AMOUNT_FIELD, true));
        PackedDecimalException packedEncode = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodePackedMoney(
                        null, 10, 3, true, DETAIL_AMOUNT_FIELD, true));
        PackedDecimalException binaryDecode = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.decodeBinaryMoney(
                        null, 0, 10, 3, true, BINARY_AMOUNT_FIELD, true));
        PackedDecimalException binaryEncode = assertThrows(PackedDecimalException.class,
                () -> PackedDecimalCodec.encodeBinaryMoney(
                        null, 10, 3, true, BINARY_AMOUNT_FIELD, true));

        assertThat(packedDecode.getMessage())
                .contains("packed money field " + DETAIL_AMOUNT_FIELD)
                .contains("exactly 2 decimal positions")
                .contains("declares 3");
        assertEquals(packedDecode.getMessage(), packedEncode.getMessage());
        assertThat(binaryDecode.getMessage())
                .contains("binary money field " + BINARY_AMOUNT_FIELD)
                .contains("exactly 2 decimal positions")
                .contains("declares 3");
        assertEquals(binaryDecode.getMessage(), binaryEncode.getMessage());
    }

    /**
     * The layout width selector rejects a missing kind before attempting any geometry arithmetic.
     *
     * <p>Assumptions: a null kind is a metadata error with no safe default; choosing packed, zoned or
     * binary would produce three different widths for the same digits. The expected exception is
     * captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void layoutWidthRejectsMissingKind() {
        LayoutException thrown = assertThrows(LayoutException.class,
                () -> CopybookLayout.widthOf(null, 10, 2));

        assertThat(thrown.getMessage()).contains("kind must not be null");
    }

    /**
     * A layout descriptor refuses a six-byte declaration for a twelve-digit packed field.
     *
     * <p>Assumptions: the canonical descriptor receives both length and digit geometry, so accepting
     * disagreement would let a bad hand-written layout bypass the safe factory. The expected
     * exception is captured and inspected, so this test raises no exception to its caller.</p>
     */
    @Test
    void layoutDescriptorRejectsSixByteTwelveDigitPackedField() {
        LayoutException thrown = assertThrows(LayoutException.class,
                () -> new CopybookLayout.FieldSpec(
                        DETAIL_AMOUNT_FIELD, 74, 6, Kind.PACKED, 10, 2, true, false, true));

        assertThat(thrown.getMessage())
                .contains("imply a width of 7 bytes")
                .contains("length=6");
    }
}
