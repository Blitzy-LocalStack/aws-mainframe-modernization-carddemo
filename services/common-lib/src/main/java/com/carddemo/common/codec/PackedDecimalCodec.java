package com.carddemo.common.codec;

import java.math.BigDecimal;

import com.carddemo.common.money.Money;

/**
 * Decodes and encodes the two computational numeric regimes of the reference records: packed decimal,
 * declared {@code COMP-3}, and binary, declared {@code COMP} or its synonym {@code BINARY}.
 *
 * <p>Both regimes are handled here because both take their physical width from the declared usage
 * rather than from the picture, and because both must land on the identical exact fixed-point
 * representation. Zoned decimal, the third regime and the one every base master uses, is owned by the
 * sibling {@code ZonedDecimalCodec}. The field-by-field width and column derivations summarised below
 * are held in full in {@code docs/architecture/data-model-and-schema-mapping.md}.</p>
 *
 * <h2>Why a second numeric codec exists at all</h2>
 *
 * <p>Assumptions: the two regimes are disjoint, established mechanically rather than assumed --
 * searching all eleven base-master copybooks for {@code COMP}, {@code COMP-3} or {@code OCCURS}
 * returns zero matches in every one of them, so every base-master money field is zoned decimal with a
 * sign overpunch, exemplified by {@code 05 ACCT-CURR-BAL PIC S9(10)V99.} at line 7 of
 * {@code app/cpy/CVACT01Y.cpy}. Packed decimal reaches the migration through exactly three layouts:
 * {@code app/cpy/CVEXPORT.cpy}, the 500-byte export layout and the only copybook in {@code app/cpy}
 * declaring a computational usage at all; and the two authorization segments
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} and {@code CIPAUDTY.cpy}, of 100 and 200
 * bytes.</p>
 *
 * <p>Assumptions: the authorization bounded context is the only place packed decimal reaches
 * <em>persisted</em> target data. The export record is a transport record, so its packed bytes are
 * decoded at the loader edge and the packed form is never stored -- which bounds the blast radius of an
 * error here and is why the two authorization segments get the closer scrutiny of the three.</p>
 *
 * <h2>USAGE determines the physical width; PICTURE never does</h2>
 *
 * <p>{@code app/cpy/CVEXPORT.cpy} settles this in one record, declaring three fields of the
 * <em>same</em> picture at three different physical widths because each carries a different usage:</p>
 *
 * <pre>
 * line  field                          declaration                    bytes
 *   50  EXP-ACCT-CURR-BAL              PIC S9(10)V99 COMP-3               7
 *   51  EXP-ACCT-CREDIT-LIMIT          PIC S9(10)V99                     12
 *   57  EXP-ACCT-CURR-CYC-DEBIT        PIC S9(10)V99 COMP                 8
 * </pre>
 *
 * <p>Assumptions: the consequence is the central contract of this class, stated plainly because
 * getting it wrong misaligns every field after it -- <b>this codec must be told the usage and can never
 * infer a width from the picture.</b> A caller supplies the digit geometry and selects the packed or
 * the binary entry point; the usage is a static property of the declaration, so the choice is always
 * knowable before a byte is read. Packed width is the ceiling of one more than the digit count halved,
 * the extra position being the sign nibble ({@link #packedWidth(int, int)}); binary width is two bytes
 * up to four digits, four up to nine and eight beyond, big-endian two's complement
 * ({@link #binaryWidth(int, int)}); zoned width is one byte per digit position and is not this
 * class's.</p>
 *
 * <p>Assumptions: {@code PIC S9(10)V99 COMP-3} occupies <b>seven</b> bytes, not six -- twelve digit
 * positions plus one sign nibble is thirteen nibbles, whose ceiling halved is seven -- and any
 * statement of six is defective and must not be propagated. The figure is confirmed three independent
 * ways because this arithmetic looks plausible when it is wrong: the five 460-byte redefinitions of
 * {@code CVEXPORT.cpy} all close exactly only at seven; {@code CIPAUDTY.cpy} reaches its declared 200
 * bytes only when its two such amounts at lines 34 and 35 are seven each; and the reference compiler
 * reports seven, laying the value out as twelve digit nibbles preceded by one zero pad nibble and
 * followed by the sign nibble. {@code app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl} corroborates
 * from the other side with {@code DECIMAL(12,2)} at lines 12 and 13, and the companion
 * {@code XAUTHFRD.ddl} indexes {@code (CARD_NUM ASC, AUTH_TS DESC)} -- whose descending component is
 * why a decoded key component has to preserve order exactly rather than approximately.</p>
 *
 * <h2>The renderings this class owns, and those it does not</h2>
 *
 * <p>Assumptions: money reaches the migration in six distinct physical forms and this class owns two
 * of them, so the boundary is stated to stop the class being extended into a rendering it does not
 * own. <b>Owned here:</b> packed {@code COMP-3}, and binary {@code COMP} or {@code BINARY}. <b>Not
 * owned:</b> zoned overpunch, which is {@code ZonedDecimalCodec}'s; the edited display text
 * {@code PIC +9(10).99} at line 24 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy}, where the sign and the point are real
 * bytes and the implied point of the pictures above occupies none, which is
 * {@code CsvAuthCodec}'s; and the two report edit masks of {@code app/cpy/CVTRA07Y.cpy}, which belong
 * to the reporting context.</p>
 *
 * <p>Assumptions: this codec is handed a digit geometry and a byte span, and a field name only ever
 * reaches it as diagnostic text a caller supplied, so it performs no renaming of any kind. The
 * boundary is worth stating because one of the three names the target deliberately spells differently
 * lands in a segment this class decodes, {@code PA-MERCHANT-CATAGORY-CODE} at line 36 of
 * {@code CIPAUDTY.cpy}; the rename happens in the mapping layer that owns the anti-corruption
 * boundary, and the field is in any case a character field and not one this codec reads.</p>
 *
 * <h2>The round-trip law, and its one exception</h2>
 *
 * <p>For every span this class accepts, encoding what it decoded reproduces the original bytes
 * exactly. Byte-reproducible round-tripping is not a convenience: the parity oracle compares output
 * byte for byte after timestamp normalisation, so a re-encoded field differing in one nibble is a
 * failed comparison rather than a cosmetic difference.</p>
 *
 * <p>Assumptions: there is exactly one documented exception and it is a property of the target
 * language rather than a choice made here. The decimal type this class decodes into has no negative
 * zero, so a packed zero carrying the negative sign nibble decodes to zero and re-encodes carrying the
 * positive one -- the value is preserved and one nibble of the encoding is normalised. The reference
 * codec at {@code tests/helpers/record_codec.py} preserves negative zero in its own zoned decoder,
 * which is why the difference is recorded here rather than left to a failing comparison.</p>
 *
 * <h2>The fixed-point contract</h2>
 *
 * <p>Assumptions: transformation rule T3 pins one representation per layer and admits no exception --
 * {@code NUMERIC(p,2)} in the database, {@link BigDecimal} carried at scale 2 in Java, {@code Decimal}
 * in the extract-transform-load code, and a JSON <em>string</em> on the wire. Money is never carried
 * through IEEE-754 binary arithmetic anywhere in this class: not through either of the language's two
 * binary primitive types, not through either of their wrapper types, and not as a bare JSON number.
 * {@code LayeringRulesTest} asserts the prohibition, so a breach fails a build rather than a
 * review.</p>
 *
 * <p>Trade-offs: those forbidden type names are described rather than spelled anywhere in this file,
 * because spelling them would make it match the audit search for the very tokens the money path must
 * not contain and produce a hit to explain away on every audit. The description is unambiguous, the
 * language having exactly two IEEE-754 binary primitive types and one wrapper each.</p>
 *
 * <p>Every decode returns a value at a scale of exactly the declared decimal digit count and no decode
 * normalises or strips that scale; every encode is lossless or raises. Rounding is never performed
 * here -- it is delegated to {@link Money}, whose scale and mode are the migration's one rounding
 * contract, at the point a business rule decides rounding is appropriate.</p>
 *
 * <h2>Bytes, never characters</h2>
 *
 * <p>Assumptions: every entry point takes or returns bytes and none takes or returns text, because
 * packed decimal is binary -- two decimal digits share a byte and the sign occupies the low nibble of
 * the last byte, so a packed field contains byte values that are not characters in any encoding. The
 * invariant is therefore a prohibition rather than a prediction: packed bytes, sign bytes and padding
 * low values are never routed through a whole-record text decode. What such a decode does to them is a
 * property of the charset rather than one behaviour that can be relied on -- a single-byte charset maps
 * all 256 values to some character and mistranslates silently with no replacement at all, while a
 * multi-byte charset substitutes replacement characters whose count need not equal the bytes consumed,
 * which moves every following field. Both are corruption and only one leaves the offsets intact. The
 * parity oracle takes the same position from the other direction, treating the
 * mainframe-character-set datasets as opaque binary and never transcoding them. Binary {@code COMP}
 * fields are the same hazard for the same reason.</p>
 */
public final class PackedDecimalCodec {

    // Assumptions: eighteen is the ceiling the language guarantees for a computational item and is
    //     also the widest declaration in the corpus. Rejecting beyond it keeps every decoded value
    //     inside the range the eight-byte binary form holds, which is what lets the binary path read
    //     a span as one whole number without an intermediate that could overflow.
    private static final int MAX_DIGITS = 18;

    // Assumptions: the ladder is a property of the usage rather than of any one field, so its two
    //     thresholds are named rather than written inline. Every rung was confirmed against the
    //     reference compiler, and the seven binary fields of app/cpy/CVEXPORT.cpy (lines 16, 25, 57,
    //     72, 87, 95 and 96) exercise all three.
    private static final int BINARY_HALFWORD_MAX_DIGITS = 4;
    private static final int BINARY_FULLWORD_MAX_DIGITS = 9;

    // Trade-offs: the widest rung is named for its role, not for the architecture term for an
    //     eight-byte word, because that term contains as a substring one of the numeric type names
    //     the money path is audited for the absence of. The cost is a name a mainframe reader would
    //     not reach for first; the two narrower rungs keep conventional names, carrying no such
    //     substring.
    private static final int BINARY_HALFWORD_BYTES = 2;
    private static final int BINARY_FULLWORD_BYTES = 4;
    private static final int BINARY_WIDEST_BYTES = 8;

    // Assumptions: all three values were read off the reference compiler rather than taken from
    //     documentation -- signed negative lays down 0xD, signed non-negative 0xC, and a field
    //     declared without the leading S lays down 0xF. That third case is why an unsigned packed
    //     field is a real shape here and not a theoretical one.
    private static final int SIGN_SIGNED_POSITIVE = 0x0C;
    private static final int SIGN_SIGNED_NEGATIVE = 0x0D;
    private static final int SIGN_UNSIGNED = 0x0F;

    // Assumptions: the alternate sign nibbles 0xA, 0xB and 0xE are all rejected, and the threshold
    //     is named rather than the three values because the test that matters is digit or sign --
    //     anything below 0xA in the sign position means the field is zoned rather than packed, or the
    //     offset is off by a nibble.
    private static final int LOWEST_SIGN_NIBBLE = 0x0A;

    // Assumptions: the hexadecimal digits are held as a constant and indexed, so a nibble reaches a
    //     diagnostic without passing through a locale-sensitive formatter. See nibbleDetail below.
    private static final String HEX_DIGITS = "0123456789ABCDEF";

    private static final int NIBBLE_MASK = 0x0F;
    private static final int HIGH_NIBBLE_SHIFT = 4;
    private static final int NIBBLES_PER_BYTE = 2;
    private static final int MAX_DIGIT_NIBBLE = 0x09;
    private static final int BYTE_MASK = 0xFF;
    private static final int BITS_PER_BYTE = 8;

    /**
     * Prevents instantiation of this codec.
     *
     * <p>Trade-offs: a static entry-point class was chosen over an instantiable one, because every
     * operation here is a pure function of a byte span and a digit geometry. The cost is that an
     * instance cannot be substituted in a test, accepted because there is nothing to substitute --
     * the functions have no collaborators and no configuration.</p>
     *
     * @throws AssertionError always, because this constructor exists only to deny instantiation and
     *     raising is what makes a reflective call fail rather than succeed silently
     */
    private PackedDecimalCodec() {
        throw new AssertionError("PackedDecimalCodec is a static codec and must not be instantiated");
    }

    /**
     * Returns the number of bytes a packed-decimal field of the given digit geometry occupies.
     *
     * <p>Assumptions: the extra position the formula adds is the sign nibble, which every packed field
     * carries whether or not its picture declares a sign. Every rung of the resulting ladder was
     * confirmed against the reference compiler; its last rung is correction C-WIDTH, so twelve digits
     * is seven bytes and never six.</p>
     *
     * @param intDigits the number of integer digit positions, the {@code a} of {@code S9(a)V9(d)};
     *     must not be negative
     * @param decDigits the number of decimal digit positions, the {@code d} of {@code S9(a)V9(d)};
     *     must not be negative, and the implied decimal point sits this many places from the right
     * @return the physical byte width of the field, always at least one
     * @throws PackedDecimalException if either count is negative, if they sum to zero, or if they sum
     *     to more than the eighteen digit positions this codec admits
     */
    public static int packedWidth(int intDigits, int decDigits) {
        return packedWidthOf(requireDigits(intDigits, decDigits, "packed"));
    }

    /**
     * Returns the byte width of a packed field from an already-validated digit count.
     *
     * <p>Trade-offs: the formula lives here alone and the three entry points that need it all call
     * through, rather than each computing it inline. Inlining would have saved a call at the cost of
     * writing correction C-WIDTH's arithmetic in three places, where two of them could later be edited
     * and one forgotten -- and a width that disagreed between the decoder and the encoder would break
     * round-tripping in a way that looks like a data problem rather than a code one.</p>
     *
     * @param digits the field's total digit count, already validated as being between one and the
     *     eighteen positions this codec admits
     * @return the physical byte width of the field, always at least one
     */
    private static int packedWidthOf(int digits) {
        // Assumptions: adding two before halving is integer arithmetic for the ceiling, written this
        //     way rather than with the library ceiling function because that operates on the binary
        //     approximate types this class is barred from touching. Adding one instead would floor,
        //     returning six for twelve digits -- the defective figure correction C-WIDTH refutes.
        return (digits + NIBBLES_PER_BYTE) / NIBBLES_PER_BYTE;
    }

    /**
     * Returns the number of bytes a binary field of the given digit geometry occupies.
     *
     * <p>Assumptions: {@code COMP} and {@code BINARY} are one usage under two spellings, and both
     * occur in the reference corpus, so a codec that recognised only one of them would silently
     * mis-size every field declared with the other. This method is the single width rule for both
     * spellings, which is why its name says what the storage is rather than repeating either
     * spelling.</p>
     *
     * @param intDigits the number of integer digit positions; must not be negative
     * @param decDigits the number of decimal digit positions; must not be negative
     * @return the physical byte width of the field, which is two, four or eight
     * @throws PackedDecimalException if either count is negative, if they sum to zero, or if they sum
     *     to more than the eighteen digit positions this codec admits
     */
    public static int binaryWidth(int intDigits, int decDigits) {
        int digits = requireDigits(intDigits, decDigits, "binary");
        if (digits <= BINARY_HALFWORD_MAX_DIGITS) {
            return BINARY_HALFWORD_BYTES;
        }
        if (digits <= BINARY_FULLWORD_MAX_DIGITS) {
            return BINARY_FULLWORD_BYTES;
        }
        return BINARY_WIDEST_BYTES;
    }

    /**
     * Decodes a packed-decimal field from a record, identifying it by name in any diagnostic raised.
     *
     * <p>The returned value carries a scale of exactly {@code decDigits}, never a normalised or
     * stripped one. Assumptions: byte-reproducible round-tripping depends on that. Stripping trailing
     * zeros would make two values compare equal that encode to different bytes, and the parity oracle
     * compares encoded output byte for byte, so a stripped scale turns a passing comparison into a
     * failing one for a value that is numerically right.</p>
     *
     * @param source the record bytes to read from; must not be {@code null} and must be long enough to
     *     contain the whole field at {@code offset}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, in which case the sign
     *     nibble must be the signed positive or the signed negative one; {@code false} for an unsigned
     *     picture, whose sign nibble must be the unsigned one
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}, in which case the diagnostic identifies the field by offset, length and kind
     * @param sensitive {@code true} when the field holds data that must never reach a log, which
     *     suppresses the offending nibble from any diagnostic raised
     * @return the decoded value at a scale of exactly {@code decDigits}, negative when the sign nibble
     *     is the signed negative one
     * @throws PackedDecimalException if the digit geometry is inadmissible, if {@code source} is
     *     {@code null}, if the field does not lie wholly within {@code source}, if the leading pad
     *     nibble is non-zero, if a digit position holds a nibble above nine, if the sign position holds
     *     a digit, or if the sign nibble contradicts {@code signed}
     */
    public static BigDecimal decodePacked(byte[] source, int offset, int intDigits, int decDigits,
            boolean signed, String fieldName, boolean sensitive) {
        int digits = requireDigits(intDigits, decDigits, "packed");
        int width = packedWidthOf(digits);
        requireSpan(source, offset, width, fieldName, "packed", sensitive);

        // Assumptions: the pad count is derived from the geometry rather than asserted from the
        //     parity of the digit count, because the two are easy to state the wrong way round and
        //     the arithmetic is not -- a field occupies width times two nibbles and uses digits plus
        //     one, so the surplus is what is left over and is at most one. Deriving it is correct for
        //     either parity; the surplus in fact exists when the digit count is even.
        int padNibbles = width * NIBBLES_PER_BYTE - (digits + 1);

        // Assumptions: the pad nibble sits at the FRONT of the field and is validated rather than
        //     skipped, because checking it is zero is the cheapest detector of a mis-aligned offset:
        //     a field read one nibble early presents a real digit where the pad belongs, yielding a
        //     plausible value ten times too large that raises nothing.
        if (padNibbles > 0) {
            int pad = nibbleAt(source, offset, 0);
            if (pad != 0) {
                throw new PackedDecimalException(describe(fieldName, offset, width, "packed", sensitive)
                        + " must begin with a zero pad nibble, because its " + digits
                        + " digit positions leave one nibble unused at the front of the field"
                        + nibbleDetail(pad, sensitive));
            }
        }

        StringBuilder digitText = new StringBuilder(digits);
        for (int nibbleIndex = padNibbles; nibbleIndex < padNibbles + digits; nibbleIndex++) {
            int nibble = nibbleAt(source, offset, nibbleIndex);

            // Assumptions: a nibble above nine in a digit position proves the read is wrong -- a
            //     misaligned offset, a field that is not packed, or a sign nibble reached early --
            //     so it is refused rather than masked down, which would manufacture a digit that was
            //     never written.
            if (nibble > MAX_DIGIT_NIBBLE) {
                throw new PackedDecimalException(describe(fieldName, offset, width, "packed", sensitive)
                        + " holds a non-digit nibble at digit position "
                        + (nibbleIndex - padNibbles + 1) + " of " + digits
                        + ", so the field is either misaligned or not packed decimal"
                        + nibbleDetail(nibble, sensitive));
            }
            digitText.append((char) ('0' + nibble));
        }

        boolean negative = decodeSignNibble(source, offset, width, signed, fieldName, sensitive);
        return assemble(digitText, negative, decDigits);
    }

    /**
     * Decodes a packed-decimal field from a record.
     *
     * <p>This is the form a caller uses when it has no field name to supply; a diagnostic then
     * identifies the field by its offset, length and kind. It is equivalent to the named form with a
     * {@code null} name and the field treated as non-sensitive.</p>
     *
     * @param source the record bytes to read from; must not be {@code null}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @return the decoded value at a scale of exactly {@code decDigits}
     * @throws PackedDecimalException if the geometry is inadmissible, the span does not fit, the pad
     *     nibble is non-zero, a digit nibble is above nine, or the sign nibble is absent or contradicts
     *     {@code signed}
     */
    public static BigDecimal decodePacked(byte[] source, int offset, int intDigits, int decDigits,
            boolean signed) {
        return decodePacked(source, offset, intDigits, decDigits, signed, null, false);
    }

    /**
     * Encodes a value as a packed-decimal field, identifying it by name in any diagnostic raised.
     *
     * <p>Assumptions: zero always encodes with a positive sign nibble, because the decimal type this
     * method accepts has no negative zero to carry. That is the single documented departure from
     * byte-identical round-tripping <em>on this plain pair of operations</em>, registered as
     * D-SIGNED-ZERO-PACKED in {@code docs/architecture/cobol-to-service-traceability.md}. A caller that
     * must reproduce the bytes exactly uses {@link #decodePackedPreservingSign} with
     * {@link #encodePackedPreservingSign}, which carry the nibble beside the value.</p>
     *
     * @param value the value to encode; must not be {@code null}, must carry a scale no greater than
     *     {@code decDigits}, and must have no more integer digits than {@code intDigits}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, which selects the signed
     *     sign nibbles; {@code false} for an unsigned picture, which selects the unsigned nibble and
     *     admits no negative value
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log, which
     *     suppresses the offending value from any diagnostic raised
     * @return a newly allocated array holding exactly the field's declared width in bytes
     * @throws PackedDecimalException if the digit geometry is inadmissible, if {@code value} is
     *     {@code null}, if its scale exceeds {@code decDigits}, if it needs more than {@code intDigits}
     *     integer positions, or if it is negative while {@code signed} is {@code false}
     */
    public static byte[] encodePacked(BigDecimal value, int intDigits, int decDigits, boolean signed,
            String fieldName, boolean sensitive) {
        int digits = requireDigits(intDigits, decDigits, "packed");
        int width = packedWidthOf(digits);
        String digitText = requireEncodableDigits(value, intDigits, decDigits, signed, fieldName,
                width, "packed", sensitive);

        int padNibbles = width * NIBBLES_PER_BYTE - (digits + 1);
        byte[] target = new byte[width];

        // Assumptions: the digits are laid down from the left into the positions the pad leaves
        //     free, mirroring the decode loop, which is what makes the round trip reproduce the
        //     original bytes. Writing from the right would be correct only when nothing pads, so it
        //     would shift every even-digit-count field -- PIC S9(10)V99 COMP-3 among them.
        for (int digitIndex = 0; digitIndex < digits; digitIndex++) {
            int nibble = digitText.charAt(digitIndex) - '0';
            setNibble(target, padNibbles + digitIndex, nibble);
        }

        int signNibble = signNibbleFor(value, signed);
        setNibble(target, width * NIBBLES_PER_BYTE - 1, signNibble);
        return target;
    }

    /**
     * Encodes a value as a packed-decimal field.
     *
     * <p>This is the form a caller uses when it has no field name to supply; a diagnostic then
     * identifies the field by its length and kind. It is equivalent to the named form with a
     * {@code null} name and the field treated as non-sensitive.</p>
     *
     * @param value the value to encode; must not be {@code null}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @return a newly allocated array holding exactly the field's declared width in bytes
     * @throws PackedDecimalException if the geometry is inadmissible, the value overflows
     *     {@code intDigits}, its scale exceeds {@code decDigits}, or it is negative in an unsigned field
     */
    public static byte[] encodePacked(BigDecimal value, int intDigits, int decDigits, boolean signed) {
        return encodePacked(value, intDigits, decDigits, signed, null, false);
    }

    /**
     * One decoded packed field together with the literal sign nibble its final byte carried.
     *
     * <p><b>Purpose.</b> This pair exists for the case the plain value cannot express. The decimal type
     * has no negative zero, so a field ending {@code 0x0D} over an all-zero magnitude and one ending
     * {@code 0x0C} decode to the same value and the nibble that distinguished them is gone; and the
     * alternate positive nibbles this codec accepts are likewise indistinguishable once decoded.
     * Carrying the nibble beside the value keeps both, which is what lets
     * {@link #encodePackedPreservingSign} reproduce the original bytes exactly.</p>
     *
     * <p>Alternatives Considered: carrying a boolean sign rather than the nibble, which would cover the
     * negative-zero case alone. Rejected because this codec admits three sign nibbles and not two --
     * {@code 0x0C}, {@code 0x0D} and {@code 0x0F} for a field declared without the leading {@code S} --
     * so a boolean would leave a caller unable to say which non-negative nibble a field carried.</p>
     *
     * @param value the decoded value, carried at the field's declared scale, never {@code null}
     * @param signNibble the low nibble of the field's final byte exactly as it was read, one of
     *     {@code 0x0C}, {@code 0x0D} or {@code 0x0F}; {@code 0x0F} for an unsigned field
     */
    public record SignedPacked(BigDecimal value, int signNibble) {

        /**
         * Rejects a pair whose nibble is not one this codec emits, or which contradicts the value.
         *
         * <p>Assumptions: the admissible nibbles are exactly the three {@code signNibbleFor} produces,
         * because a pair this record accepts must be one {@link #encodePackedPreservingSign} can lay
         * down -- so both ends of the round trip admit the same set rather than one being wider.</p>
         *
         * <p>Successful construction yields this record instance and no separate return value.</p>
         *
         * @param value the decoded value; must not be {@code null}
         * @param signNibble the sign nibble the field carried
         * @throws PackedDecimalException if the value is {@code null}, if the nibble is not
         *     {@code 0x0C}, {@code 0x0D} or {@code 0x0F}, or if the value is non-zero and its own sign
         *     disagrees with the nibble's sign class
         */
        public SignedPacked {
            if (value == null) {
                throw new PackedDecimalException("packed signed-field value is absent");
            }
            if (signNibble != SIGN_SIGNED_POSITIVE && signNibble != SIGN_SIGNED_NEGATIVE
                    && signNibble != SIGN_UNSIGNED) {
                throw new PackedDecimalException("packed sign nibble 0x"
                        + HEX_DIGITS.charAt(signNibble & NIBBLE_MASK) + " is not one of the three this"
                        + " codec emits, 0xC, 0xD or 0xF");
            }
            if (value.signum() != 0 && (value.signum() < 0) != (signNibble == SIGN_SIGNED_NEGATIVE)) {
                throw new PackedDecimalException("packed sign nibble contradicts the value's own"
                        + " sign, which may happen only at zero");
            }
        }
    
        /**
         * Renders the SIGN and the scale, never the decoded value.
         *
         * <p>Purpose. The decoded value of a packed field is whatever the field held, and in this migration
         * a packed field is almost always money -- the export record and both authorization segments are
         * where {@code COMP-3} appears -- so a rendering that printed it would put an account's balance or
         * an approved amount into a codec log line. {@code docs/architecture/observability.md} L1093 to
         * L1112 withholds monetary values, and a codec cannot know which field it was handed, so it must
         * treat every value as one.</p>
         *
         * <p>Assumptions: the sign nibble and the scale are kept, and they are precisely the diagnostic
         * content this type exists to carry. The documented failure mode of this format is a sign
         * convention read the wrong way -- the reference test harness records that the default convention
         * silently corrupts negative balances -- and that fault is visible in the nibble and the sign of
         * the value without the magnitude ever being printed.</p>
         *
         * <p>Alternatives Considered: rendering the value's precision as well as its scale. Rejected
         * because precision is the digit COUNT of the magnitude, which narrows a balance to a decade and
         * is an abbreviation of a withheld value rather than metadata about it.</p>
         *
         * @return a rendering naming the sign nibble in hexadecimal, whether the value is negative and its
         *     scale, with the magnitude omitted; never {@code null}
         */
        @Override
        public String toString() {
            return "SignedPacked[signNibble=0x" + Integer.toHexString(this.signNibble).toUpperCase(
                    java.util.Locale.ROOT)
                    + ", negative=" + (this.value.signum() < 0)
                    + ", scale=" + this.value.scale() + ']';
        }
}

    /**
     * Decodes a packed field, keeping the literal sign nibble its final byte carried.
     *
     * <p>Behaviour is identical to {@link #decodePacked(byte[], int, int, int, boolean)} for the value;
     * the difference is that the sign nibble is returned alongside it, so a signed zero and an alternate
     * sign nibble both survive the decode. Pass the result to {@link #encodePackedPreservingSign} to
     * reproduce the original bytes exactly.</p>
     *
     * @param source the record bytes to read from; must not be {@code null}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @return the decoded value paired with the sign nibble the field carried, never {@code null}
     * @throws PackedDecimalException if the geometry is inadmissible, the span does not fit, the pad
     *     nibble is non-zero, a digit nibble is above nine, or the sign nibble is absent or contradicts
     *     {@code signed}
     */
    public static SignedPacked decodePackedPreservingSign(byte[] source, int offset, int intDigits,
            int decDigits, boolean signed) {
        BigDecimal value = decodePacked(source, offset, intDigits, decDigits, signed);

        // Assumptions: the nibble is read AFTER the full decode above rather than during it, so
        //     every rejection the plain decoder performs still happens first and this method can
        //     never report the nibble of a field the codec would refuse.
        int width = packedWidth(intDigits, decDigits);
        int nibble = nibbleAt(source, offset, width * NIBBLES_PER_BYTE - 1);
        return new SignedPacked(value, nibble);
    }

    /**
     * Encodes a decoded pair back into the exact bytes it came from, signed zero included.
     *
     * <p>This is the byte-exact inverse of {@link #decodePackedPreservingSign}: for every field that
     * method accepts, encoding what it returned reproduces the original bytes with no exception at all.
     * The plain {@link #encodePacked(BigDecimal, int, int, boolean)} canonicalises the sign nibble
     * because its argument cannot carry one; this form can, so it does not canonicalise.</p>
     *
     * @param decoded the value and its sign nibble, as returned by
     *     {@link #decodePackedPreservingSign}; must not be {@code null}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @return a newly allocated array holding exactly the field's declared width in bytes, identical to
     *     the bytes the pair was decoded from
     * @throws PackedDecimalException if the pair is absent, if the geometry is inadmissible, if the
     *     value overflows {@code intDigits}, if its scale exceeds {@code decDigits}, or if it is
     *     negative in an unsigned field
     */
    public static byte[] encodePackedPreservingSign(SignedPacked decoded, int intDigits, int decDigits,
            boolean signed) {
        if (decoded == null) {
            throw new PackedDecimalException("packed signed-field pair is absent");
        }
        byte[] canonical = encodePacked(decoded.value(), intDigits, decDigits, signed);

        // Assumptions: only the sign nibble is rewritten, never a digit nibble and never the pad,
        //     because that is the only position at which the canonical encoder and the source field
        //     can differ. Copying the source bytes wholesale instead would let a caller smuggle
        //     digits past every geometry check the encoder just performed.
        setNibble(canonical, canonical.length * NIBBLES_PER_BYTE - 1, decoded.signNibble());
        return canonical;
    }

    /**
     * Decodes a binary field from a record, identifying it by name in any diagnostic raised.
     *
     * <p>Trade-offs: the binary path lives in this class rather than in one of its own, because the two
     * regimes share the rule that matters -- usage and not picture fixes the physical width -- and the
     * fixed-point representation they land on, so separating them would put half of one contract in
     * each of two files. The cost is a broader class responsibility than the name suggests.</p>
     *
     * @param source the record bytes to read from; must not be {@code null} and must be long enough to
     *     contain the whole field at {@code offset}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}; {@code false} for an
     *     unsigned picture, which admits no negative stored value
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log, which
     *     suppresses the offending value from any diagnostic raised
     * @return the decoded value at a scale of exactly {@code decDigits}
     * @throws PackedDecimalException if the digit geometry is inadmissible, if {@code source} is
     *     {@code null}, if the field does not lie wholly within {@code source}, if an unsigned field
     *     holds a negative value, or if the stored whole number needs more than {@code intDigits}
     *     integer positions
     */
    public static BigDecimal decodeBinary(byte[] source, int offset, int intDigits, int decDigits,
            boolean signed, String fieldName, boolean sensitive) {
        int width = binaryWidth(intDigits, decDigits);
        requireSpan(source, offset, width, fieldName, "binary", sensitive);

        long accumulator = signed && (source[offset] & 0x80) != 0 ? -1L : 0L;
        for (int byteIndex = 0; byteIndex < width; byteIndex++) {
            accumulator = (accumulator << BITS_PER_BYTE) | (source[offset + byteIndex] & BYTE_MASK);
        }

        if (!signed && accumulator < 0) {
            throw new PackedDecimalException(describe(fieldName, offset, width, "binary", sensitive)
                    + " is declared without a sign but holds a negative stored value, so the field is"
                    + " either misaligned or declared with the wrong sign"
                    + valueDetail(Long.toString(accumulator), sensitive));
        }

        // Assumptions: the point is moved rather than divided, which is exact by construction and
        //     involves no rounding mode at all. Dividing by a power of ten would introduce a quotient,
        //     and therefore a rounding decision, where the encoding has none. This is the same
        //     position the sibling money type takes when it interprets a whole number of cents.
        BigDecimal scaled = BigDecimal.valueOf(accumulator).movePointLeft(decDigits);
        requireIntegerDigits(scaled, intDigits, decDigits, fieldName, width, "binary", sensitive);
        return scaled.setScale(decDigits);
    }

    /**
     * Decodes a binary field from a record.
     *
     * <p>This is the form a caller uses when it has no field name to supply; a diagnostic then
     * identifies the field by its offset, length and kind.</p>
     *
     * @param source the record bytes to read from; must not be {@code null}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @return the decoded value at a scale of exactly {@code decDigits}
     * @throws PackedDecimalException if the geometry is inadmissible, the span does not fit, an unsigned
     *     field holds a negative value, or the stored value overflows {@code intDigits}
     */
    public static BigDecimal decodeBinary(byte[] source, int offset, int intDigits, int decDigits,
            boolean signed) {
        return decodeBinary(source, offset, intDigits, decDigits, signed, null, false);
    }

    /**
     * Encodes a value as a binary field, identifying it by name in any diagnostic raised.
     *
     * <p>Trade-offs: as with the packed encoder, this one is lossless or it raises. The reasoning is
     * the same and is not repeated: rounding belongs to {@link Money} at the point a business rule
     * calls for it, and a codec that quietly rounded would keep that decision out of the audit
     * trail.</p>
     *
     * @param value the value to encode; must not be {@code null}, must carry a scale no greater than
     *     {@code decDigits}, and must have no more integer digits than {@code intDigits}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}; {@code false} for an
     *     unsigned picture, which admits no negative value
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log, which
     *     suppresses the offending value from any diagnostic raised
     * @return a newly allocated array holding exactly the field's declared width in bytes
     * @throws PackedDecimalException if the digit geometry is inadmissible, if {@code value} is
     *     {@code null}, if its scale exceeds {@code decDigits}, if it needs more than {@code intDigits}
     *     integer positions, or if it is negative while {@code signed} is {@code false}
     */
    public static byte[] encodeBinary(BigDecimal value, int intDigits, int decDigits, boolean signed,
            String fieldName, boolean sensitive) {
        int width = binaryWidth(intDigits, decDigits);
        String digitText = requireEncodableDigits(value, intDigits, decDigits, signed, fieldName,
                width, "binary", sensitive);

        // Assumptions: the whole number is rebuilt from the validated digit text rather than taken
        //     from the value's own unscaled form, because the digit text has already been padded to
        //     exactly the declared decimal places. Reading the unscaled form directly would encode a
        //     value carrying fewer decimal places than declared at the wrong magnitude -- a scale of
        //     zero and a scale of two are the same number and different stored integers.
        long magnitude = Long.parseLong(digitText);
        long stored = value.signum() < 0 ? -magnitude : magnitude;

        byte[] target = new byte[width];
        for (int byteIndex = width - 1; byteIndex >= 0; byteIndex--) {
            target[byteIndex] = (byte) (stored & BYTE_MASK);
            stored >>= BITS_PER_BYTE;
        }
        return target;
    }

    /**
     * Encodes a value as a binary field.
     *
     * <p>This is the form a caller uses when it has no field name to supply; a diagnostic then
     * identifies the field by its length and kind.</p>
     *
     * @param value the value to encode; must not be {@code null}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @return a newly allocated array holding exactly the field's declared width in bytes
     * @throws PackedDecimalException if the geometry is inadmissible, the value overflows
     *     {@code intDigits}, its scale exceeds {@code decDigits}, or it is negative in an unsigned field
     */
    public static byte[] encodeBinary(BigDecimal value, int intDigits, int decDigits, boolean signed) {
        return encodeBinary(value, intDigits, decDigits, signed, null, false);
    }

    /**
     * Decodes a packed-decimal money field from a record into the module's monetary type.
     *
     * <p>Assumptions: the decimal places are fixed at the money contract's own scale rather than taken
     * as a parameter, and any other count is rejected instead of reduced -- reducing it would put a
     * rounding decision inside a decoder, the one thing this class's encode contract refuses. Every
     * packed money field in the corpus declares two places, in {@code CIPAUDTY.cpy},
     * {@code CIPAUSMY.cpy} and {@code app/cpy/CVEXPORT.cpy} alike.</p>
     *
     * @param source the record bytes to read from; must not be {@code null}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field, ten for
     *     {@code PIC S9(10)V99 COMP-3} and nine for {@code PIC S9(09)V99 COMP-3}
     * @param decDigits the number of decimal digit positions declared for the field, which must equal
     *     the money contract's scale
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return the decoded amount, exact and at the money contract's scale
     * @throws PackedDecimalException if {@code decDigits} is not the money contract's scale, or if the
     *     span fails any decode validation this class applies
     */
    public static Money decodePackedMoney(byte[] source, int offset, int intDigits, int decDigits,
            boolean signed, String fieldName, boolean sensitive) {
        requireMoneyScale(decDigits, fieldName, "packed");
        return Money.of(decodePacked(source, offset, intDigits, decDigits, signed, fieldName, sensitive));
    }

    /**
     * Encodes a monetary amount as a packed-decimal field.
     *
     * <p>Assumptions: an amount arrives already at the money contract's scale, because that type holds
     * no other, so this encoder never has a surplus decimal place to consider and the lossless-or-raise
     * contract bites only on integer overflow.</p>
     *
     * @param amount the amount to encode; must not be {@code null}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field, which must equal
     *     the money contract's scale
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return a newly allocated array holding exactly the field's declared width in bytes
     * @throws PackedDecimalException if {@code amount} is {@code null}, if {@code decDigits} is not the
     *     money contract's scale, or if the amount needs more than {@code intDigits} integer positions
     */
    public static byte[] encodePackedMoney(Money amount, int intDigits, int decDigits, boolean signed,
            String fieldName, boolean sensitive) {
        requireMoneyScale(decDigits, fieldName, "packed");
        return encodePacked(requireAmount(amount, fieldName, "packed"), intDigits, decDigits, signed,
                fieldName, sensitive);
    }

    /**
     * Decodes a binary money field from a record into the module's monetary type.
     *
     * <p>Assumptions: binary money is a real shape in the corpus and not a theoretical one.
     * {@code EXP-ACCT-CURR-CYC-DEBIT} at line 57 of {@code app/cpy/CVEXPORT.cpy} declares
     * {@code PIC S9(10)V99 COMP}, which is the same picture as the packed amount seven lines above it
     * and occupies eight bytes rather than seven. A caller that reached for the packed entry point
     * there would read one byte short and misalign the rest of the record.</p>
     *
     * @param source the record bytes to read from; must not be {@code null}
     * @param offset the zero-based byte offset of the field within {@code source}; must not be negative
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field, which must equal
     *     the money contract's scale
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return the decoded amount, exact and at the money contract's scale
     * @throws PackedDecimalException if {@code decDigits} is not the money contract's scale, or if the
     *     span fails any decode validation this class applies
     */
    public static Money decodeBinaryMoney(byte[] source, int offset, int intDigits, int decDigits,
            boolean signed, String fieldName, boolean sensitive) {
        requireMoneyScale(decDigits, fieldName, "binary");
        return Money.of(decodeBinary(source, offset, intDigits, decDigits, signed, fieldName, sensitive));
    }

    /**
     * Encodes a monetary amount as a binary field.
     *
     * @param amount the amount to encode; must not be {@code null}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field, which must equal
     *     the money contract's scale
     * @param signed {@code true} when the picture carries a leading {@code S}, {@code false} otherwise
     * @param fieldName the declared field name, used only to identify the field in a diagnostic; may be
     *     {@code null}
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return a newly allocated array holding exactly the field's declared width in bytes
     * @throws PackedDecimalException if {@code amount} is {@code null}, if {@code decDigits} is not the
     *     money contract's scale, or if the amount needs more than {@code intDigits} integer positions
     */
    public static byte[] encodeBinaryMoney(Money amount, int intDigits, int decDigits, boolean signed,
            String fieldName, boolean sensitive) {
        requireMoneyScale(decDigits, fieldName, "binary");
        return encodeBinary(requireAmount(amount, fieldName, "binary"), intDigits, decDigits, signed,
                fieldName, sensitive);
    }

    /**
     * Validates a digit geometry and returns its total digit count.
     *
     * @param intDigits the number of integer digit positions; must not be negative
     * @param decDigits the number of decimal digit positions; must not be negative
     * @param kind the usage name to quote in a diagnostic, either {@code packed} or {@code binary}
     * @return the sum of the two counts, always between one and the eighteen positions admitted
     * @throws PackedDecimalException if either count is negative, if they sum to zero, or if they sum to
     *     more than eighteen
     */
    private static int requireDigits(int intDigits, int decDigits, String kind) {
        if (intDigits < 0 || decDigits < 0) {
            throw new PackedDecimalException("digit positions of a " + kind + " field must not be"
                    + " negative, but intDigits=" + intDigits + " and decDigits=" + decDigits);
        }
        int digits = intDigits + decDigits;
        if (digits == 0) {
            throw new PackedDecimalException("a " + kind + " field must declare at least one digit"
                    + " position, but intDigits and decDigits are both zero");
        }
        if (digits > MAX_DIGITS) {
            throw new PackedDecimalException("a " + kind + " field must not declare more than "
                    + MAX_DIGITS + " digit positions, but intDigits=" + intDigits + " and decDigits="
                    + decDigits + " sum to " + digits);
        }
        return digits;
    }

    /**
     * Validates that a field of the given width lies wholly within a record.
     *
     * <p>Assumptions: a span that does not fit is rejected rather than read short or padded. The
     * reference codec takes the same position on the zoned side, where a wrong-length field raises
     * because callers slice by fixed offsets, so a length that disagrees means the layout or the input
     * record is corrupt. Reading short here would decode from bytes that belong to the next field.</p>
     *
     * @param source the record bytes the field is to be read from; must not be {@code null}
     * @param offset the zero-based byte offset of the field; must not be negative
     * @param width the field's declared byte width
     * @param fieldName the declared field name for the diagnostic; may be {@code null}
     * @param kind the usage name to quote in a diagnostic
     * @param sensitive {@code true} to suppress content from the diagnostic
     * @throws PackedDecimalException if {@code source} is {@code null}, if {@code offset} is negative,
     *     or if the field extends past the end of {@code source}
     */
    private static void requireSpan(byte[] source, int offset, int width, String fieldName, String kind,
            boolean sensitive) {
        if (source == null) {
            throw new PackedDecimalException(describe(fieldName, offset, width, kind, sensitive)
                    + " cannot be decoded because the record byte array is null");
        }
        if (offset < 0) {
            throw new PackedDecimalException(describe(fieldName, offset, width, kind, sensitive)
                    + " has a negative offset, so the field position is not addressable");
        }
        if (offset + width > source.length) {
            throw new PackedDecimalException(describe(fieldName, offset, width, kind, sensitive)
                    + " extends past the end of a record of " + source.length
                    + " bytes, so the layout and the record disagree");
        }
    }

    /**
     * Returns one nibble of a field, counting from the high nibble of its first byte.
     *
     * <p>Assumptions: the extraction is arithmetic on bytes and never passes through text. Two decimal
     * digits share a byte in the packed form, so the addressable unit is a nibble rather than a
     * character, and the byte is masked to eight bits first because the language widens a byte with sign
     * extension. Omitting that mask would turn every byte above 0x7F into a negative value and corrupt
     * both nibbles of it.</p>
     *
     * @param source the record bytes to read from
     * @param offset the zero-based byte offset of the field within {@code source}
     * @param nibbleIndex the zero-based nibble position within the field, where zero is the high nibble
     *     of the field's first byte
     * @return the nibble value, between zero and fifteen
     */
    private static int nibbleAt(byte[] source, int offset, int nibbleIndex) {
        int value = source[offset + nibbleIndex / NIBBLES_PER_BYTE] & BYTE_MASK;
        return nibbleIndex % NIBBLES_PER_BYTE == 0 ? value >> HIGH_NIBBLE_SHIFT : value & NIBBLE_MASK;
    }

    /**
     * Writes one nibble of a field, counting from the high nibble of its first byte.
     *
     * @param target the field bytes to write into, whose length is the field's declared width
     * @param nibbleIndex the zero-based nibble position within the field
     * @param nibble the nibble value to store, between zero and fifteen
     */
    private static void setNibble(byte[] target, int nibbleIndex, int nibble) {
        int byteIndex = nibbleIndex / NIBBLES_PER_BYTE;
        int existing = target[byteIndex] & BYTE_MASK;
        int merged = nibbleIndex % NIBBLES_PER_BYTE == 0
                ? (nibble << HIGH_NIBBLE_SHIFT) | (existing & NIBBLE_MASK)
                : (existing & (NIBBLE_MASK << HIGH_NIBBLE_SHIFT)) | nibble;
        target[byteIndex] = (byte) merged;
    }

    /**
     * Reads and validates the sign nibble in the low nibble of a packed field's final byte.
     *
     * <p>Alternatives Considered: blanket tolerance of every nibble value in the sign position, and
     * accepting 0x0A, 0x0B and 0x0E as the alternate forms some encoders emit. Both rejected because
     * neither appears in this corpus, so an unexpected nibble is the clearest available evidence that
     * the field offset is wrong and treating it as positive would convert a detectable error into
     * silent corruption of a money value's sign. The cost is that a span from another encoder is
     * rejected rather than decoded, which is the intended direction of failure.</p>
     *
     * @param source the record bytes to read from
     * @param offset the zero-based byte offset of the field within {@code source}
     * @param width the field's declared byte width
     * @param signed {@code true} when the picture carries a leading {@code S}
     * @param fieldName the declared field name for the diagnostic; may be {@code null}
     * @param sensitive {@code true} to suppress the offending nibble from the diagnostic
     * @return {@code true} when the sign nibble is the signed negative one, {@code false} otherwise
     * @throws PackedDecimalException if the sign position holds a digit, if it holds an alternate sign
     *     nibble this codec does not admit, or if the nibble contradicts {@code signed}
     */
    private static boolean decodeSignNibble(byte[] source, int offset, int width, boolean signed,
            String fieldName, boolean sensitive) {
        int sign = source[offset + width - 1] & NIBBLE_MASK;

        // Assumptions: a value below 0x0A in the sign position is a digit, and a digit here means
        //     the field is zoned rather than packed, or the read is off by one nibble. Naming that
        //     case separately is worth the extra branch because it points at the actual defect instead
        //     of reporting an unrecognised sign.
        if (sign < LOWEST_SIGN_NIBBLE) {
            throw new PackedDecimalException(describe(fieldName, offset, width, "packed", sensitive)
                    + " holds a digit where its sign nibble must be, so the field is either zoned rather"
                    + " than packed or misaligned by one nibble" + nibbleDetail(sign, sensitive));
        }
        if (sign != SIGN_SIGNED_POSITIVE && sign != SIGN_SIGNED_NEGATIVE && sign != SIGN_UNSIGNED) {
            throw new PackedDecimalException(describe(fieldName, offset, width, "packed", sensitive)
                    + " holds an alternate sign nibble that this codec does not admit; the reference"
                    + " corpus uses only the signed positive, signed negative and unsigned forms"
                    + nibbleDetail(sign, sensitive));
        }
        if (signed && sign == SIGN_UNSIGNED) {
            throw new PackedDecimalException(describe(fieldName, offset, width, "packed", sensitive)
                    + " is declared with a sign but holds the unsigned sign nibble, so the declaration"
                    + " and the data describe different fields");
        }
        if (!signed && sign != SIGN_UNSIGNED) {
            throw new PackedDecimalException(describe(fieldName, offset, width, "packed", sensitive)
                    + " is declared without a sign but holds a signed sign nibble, so the declaration"
                    + " and the data describe different fields");
        }
        return sign == SIGN_SIGNED_NEGATIVE;
    }

    /**
     * Returns the sign nibble a value takes when encoded into a packed field.
     *
     * <p>Assumptions: the negative nibble is selected from the value's own sign, and the decimal type
     * this codec encodes has no negative zero to report, so a zero value always takes a positive
     * nibble. That is the whole of the documented departure from byte-identical round-tripping: a span
     * that arrived carrying the negative nibble over zero digits re-encodes carrying the positive
     * one.</p>
     *
     * @param value the value being encoded
     * @param signed {@code true} when the picture carries a leading {@code S}
     * @return the signed negative nibble for a negative value, the signed positive nibble for any other
     *     value in a signed field, and the unsigned nibble in an unsigned field
     */
    private static int signNibbleFor(BigDecimal value, boolean signed) {
        if (!signed) {
            return SIGN_UNSIGNED;
        }
        return value.signum() < 0 ? SIGN_SIGNED_NEGATIVE : SIGN_SIGNED_POSITIVE;
    }

    /**
     * Builds the decoded value from its digit text, its sign and its declared decimal places.
     *
     * <p>Trade-offs: the value is built from an assembled decimal string rather than from integral
     * arithmetic on the accumulated digits. One allocation per field buys two properties arithmetic
     * does not have: exactness for the full twelve digits of the widest packed money field with no
     * intermediate that could overflow, and independence from any ambient precision or rounding
     * setting, so the result is deterministic for a given input.</p>
     *
     * @param digitText the decoded digits in order, most significant first, of length equal to the
     *     field's total digit count
     * @param negative {@code true} when the sign nibble was the signed negative one
     * @param decDigits the number of decimal digit positions, which becomes the returned scale
     * @return the assembled value at a scale of exactly {@code decDigits}
     */
    private static BigDecimal assemble(StringBuilder digitText, boolean negative, int decDigits) {
        StringBuilder text = new StringBuilder(digitText.length() + 2);
        if (negative) {
            text.append('-');
        }
        int pointAt = digitText.length() - decDigits;

        // Assumptions: a leading zero is inserted when every digit position is fractional, because
        //     a picture such as PIC SV99 leaves nothing to the left of the implied point and the
        //     decimal string grammar requires a digit there. Emitting the point with nothing before it
        //     would raise a parse failure rather than decode the field.
        if (pointAt == 0) {
            text.append('0');
        }
        text.append(digitText, 0, pointAt);
        if (decDigits > 0) {
            text.append('.').append(digitText, pointAt, digitText.length());
        }

        // Assumptions: the scale is asserted rather than assumed. The string form fixes it by
        //     construction, and setting it again would be redundant, but a field declaring no decimal
        //     places yields a scale of zero from the string and callers depend on the returned scale
        //     being exactly the declared count in every case. Naming it here keeps that contract true
        //     for the no-decimal case without a special branch.
        return new BigDecimal(text.toString()).setScale(decDigits);
    }

    /**
     * Validates a value against a field's declared geometry and returns its digits as text.
     *
     * <p>Assumptions: the returned text is exactly the field's total digit count, left-padded with
     * zeros, with the implied decimal point removed. Both encoders consume that one form, which is what
     * lets them differ only in how they lay the digits down and keeps a single place responsible for
     * deciding whether a value fits at all.</p>
     *
     * @param value the value to encode; must not be {@code null}
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param signed {@code true} when the picture carries a leading {@code S}
     * @param fieldName the declared field name for the diagnostic; may be {@code null}
     * @param width the field's declared byte width, quoted in a diagnostic
     * @param kind the usage name to quote in a diagnostic
     * @param sensitive {@code true} to suppress the offending value from the diagnostic
     * @return the value's digits, most significant first, padded to the field's total digit count
     * @throws PackedDecimalException if {@code value} is {@code null}, if its scale exceeds
     *     {@code decDigits}, if it is negative while {@code signed} is {@code false}, or if it needs
     *     more than {@code intDigits} integer positions
     */
    private static String requireEncodableDigits(BigDecimal value, int intDigits, int decDigits,
            boolean signed, String fieldName, int width, String kind, boolean sensitive) {
        if (value == null) {
            throw new PackedDecimalException(describe(fieldName, -1, width, kind, sensitive)
                    + " cannot be encoded from a null value, because a numeric field has no"
                    + " representation for an absent amount");
        }
        if (!signed && value.signum() < 0) {
            throw new PackedDecimalException(describe(fieldName, -1, width, kind, sensitive)
                    + " is declared without a sign and cannot carry a negative value"
                    + valueDetail(value.toPlainString(), sensitive));
        }

        // Assumptions: a scale beyond the declared decimal places is refused rather than reduced,
        //     which is the encode half of the lossless-or-raise contract. Reducing it would round, and
        //     a rounding decision taken inside a codec never reaches the audit trail; the sibling
        //     money type is where the scale and the mode are declared, and a caller that needs a
        //     reduction performs it there where the decision is visible.
        if (value.scale() > decDigits) {
            throw new PackedDecimalException(describe(fieldName, -1, width, kind, sensitive)
                    + " declares " + decDigits + " decimal positions but the value carries a scale of "
                    + value.scale() + ", and reducing it here would round silently"
                    + valueDetail(value.toPlainString(), sensitive));
        }

        requireIntegerDigits(value, intDigits, decDigits, fieldName, width, kind, sensitive);

        // Assumptions: the scale is raised to the declared count before the digits are read, so a
        //     value carrying fewer decimal places than declared contributes its missing places as
        //     trailing zeros. Reading the unscaled digits without that step would encode two pounds as
        //     though it were two pence, because a scale of zero and a scale of two hold the same digits
        //     and mean different amounts.
        String digits = value.abs().setScale(decDigits).unscaledValue().toString();
        int totalDigits = intDigits + decDigits;
        if (digits.length() >= totalDigits) {
            return digits;
        }
        StringBuilder padded = new StringBuilder(totalDigits);
        for (int padIndex = digits.length(); padIndex < totalDigits; padIndex++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    /**
     * Validates that a value fits the integer digit positions a field declares.
     *
     * @param value the value to check
     * @param intDigits the number of integer digit positions declared for the field
     * @param decDigits the number of decimal digit positions declared for the field
     * @param fieldName the declared field name for the diagnostic; may be {@code null}
     * @param width the field's declared byte width, quoted in a diagnostic
     * @param kind the usage name to quote in a diagnostic
     * @param sensitive {@code true} to suppress the offending value from the diagnostic
     * @throws PackedDecimalException if the value needs more than {@code intDigits} integer positions
     */
    private static void requireIntegerDigits(BigDecimal value, int intDigits, int decDigits,
            String fieldName, int width, String kind, boolean sensitive) {
        // Assumptions: the integer digit count is measured as the precision left after the declared
        //     decimal places are accounted for, which is exact for every value including zero. Comparing
        //     against a power-of-ten bound instead would need that bound materialised for up to eighteen
        //     digits and would then have to decide whether the bound itself is admissible. The bare
        //     scale adjustment is safe rather than lucky: every caller has already established that the
        //     value's scale does not exceed the declared count, so the adjustment only ever pads.
        BigDecimal scaled = value.setScale(decDigits);
        int usedIntegerDigits = scaled.precision() - scaled.scale();
        if (usedIntegerDigits > intDigits) {
            throw new PackedDecimalException(describe(fieldName, -1, width, kind, sensitive)
                    + " declares " + intDigits + " integer positions but the value needs "
                    + usedIntegerDigits + ", and truncating it here would drop high-order digits and"
                    + " yield a materially smaller amount" + valueDetail(scaled.toPlainString(),
                    sensitive));
        }
    }

    /**
     * Validates that a money field declares exactly the decimal places the money contract fixes.
     *
     * @param decDigits the number of decimal digit positions declared for the field
     * @param fieldName the declared field name for the diagnostic; may be {@code null}
     * @param kind the usage name to quote in a diagnostic
     * @throws PackedDecimalException if {@code decDigits} is not the money contract's scale
     */
    private static void requireMoneyScale(int decDigits, String fieldName, String kind) {
        if (decDigits != Money.SCALE) {
            throw new PackedDecimalException("the " + kind + " money field "
                    + (fieldName == null ? "at an unnamed position" : fieldName) + " must declare exactly "
                    + Money.SCALE + " decimal positions to be read as an amount, but declares "
                    + decDigits + "; decode it as a plain decimal instead of reducing it here");
        }
    }

    /**
     * Validates a monetary argument and returns its exact decimal value.
     *
     * @param amount the amount to unwrap; must not be {@code null}
     * @param fieldName the declared field name for the diagnostic; may be {@code null}
     * @param kind the usage name to quote in a diagnostic
     * @return the amount's exact decimal value at the money contract's scale
     * @throws PackedDecimalException if {@code amount} is {@code null}
     */
    private static BigDecimal requireAmount(Money amount, String fieldName, String kind) {
        if (amount == null) {
            throw new PackedDecimalException("the " + kind + " money field "
                    + (fieldName == null ? "at an unnamed position" : fieldName)
                    + " cannot be encoded from a null amount");
        }
        return amount.amount();
    }

    /**
     * Builds the leading clause of a diagnostic, identifying a field without disclosing its content.
     *
     * <p>Assumptions: for a field marked sensitive this clause is the whole of what a diagnostic may
     * reveal -- name, offset, length and kind -- and never the bytes. The reference codec at
     * {@code tests/helpers/record_codec.py} quotes the offending raw value, which suits a harness
     * reading committed fixtures; the same message here can reach a production log carrying a primary
     * account number, so it is withheld. The difference is in what is reported, not in what is
     * rejected.</p>
     *
     * @param fieldName the declared field name, or {@code null} when the caller supplied none
     * @param offset the zero-based byte offset of the field, or a negative value when the operation is
     *     an encode and no record position applies
     * @param width the field's declared byte width
     * @param kind the usage name, either {@code packed} or {@code binary}
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return the identifying clause, which never contains field content
     */
    private static String describe(String fieldName, int offset, int width, String kind,
            boolean sensitive) {
        StringBuilder text = new StringBuilder(64);
        text.append(kind).append(" field ");
        text.append(fieldName == null ? "at an unnamed position" : fieldName);
        if (offset >= 0) {
            text.append(" at offset ").append(offset);
        }
        text.append(" of length ").append(width);
        if (sensitive) {
            // Assumptions: a reader who sees a diagnostic with no content cannot otherwise tell
            //     whether the codec had nothing to report or withheld it on purpose, and would
            //     reasonably suspect the message itself was defective. Naming the suppression makes
            //     the omission legible and keeps anyone from adding the content back to fill the gap.
            text.append(" (content withheld: field is marked sensitive)");
        }
        return text.toString();
    }

    /**
     * Renders an offending nibble for a diagnostic, or nothing at all for a sensitive field.
     *
     * @param nibble the nibble value that failed validation
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return a clause naming the nibble in hexadecimal, or an empty string when {@code sensitive}
     */
    private static String nibbleDetail(int nibble, boolean sensitive) {
        // Assumptions: the digit is indexed out of a constant rather than formatted, which keeps the
        //     rendering independent of any ambient locale. A locale-sensitive case conversion of a
        //     formatted hexadecimal digit produces a different character under a locale whose dotless
        //     letter maps unexpectedly, and a diagnostic that reads differently per locale is one that
        //     cannot be matched against a known message.
        return sensitive ? "" : "; found 0x" + HEX_DIGITS.charAt(nibble & NIBBLE_MASK);
    }

    /**
     * Renders an offending value for a diagnostic, or nothing at all for a sensitive field.
     *
     * @param plainText the value that failed validation, already rendered without exponent notation
     * @param sensitive {@code true} when the field holds data that must never reach a log
     * @return a clause naming the value, or an empty string when {@code sensitive}
     */
    private static String valueDetail(String plainText, boolean sensitive) {
        return sensitive ? "" : "; value " + plainText;
    }

    /**
     * Reports a packed-decimal or binary field that violates the encoding contract of this codec.
     *
     * <p>Alternatives Considered: reusing the platform's own argument exception unqualified, or
     * declaring a checked one. The unqualified form was rejected because a caller could not then
     * distinguish a malformed computational field from any other rejected argument, and this is the one
     * signal that a record was read against the wrong geometry -- the failure whose damage is silent. A
     * checked exception was rejected because a field that does not decode cannot be decoded a second
     * way, so a catch block could only rethrow. It extends the platform argument exception so a caller
     * catching that broader type still catches this one.</p>
     */
    public static final class PackedDecimalException extends IllegalArgumentException {

        // Assumptions: the platform requires a serial version identifier on every serialisable
        //     type, and this type inherits serialisability from the exception hierarchy. Declaring it
        //     explicitly pins the value rather than letting the compiler derive one that changes
        //     whenever a member is added, which is what makes a serialised instance readable across
        //     builds.
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception describing one violated encoding constraint.
         *
         * @param message the diagnostic text, which names the field, the constraint it breached and the
         *     consequence of accepting it; it must never contain the content of a field marked sensitive
         */
        PackedDecimalException(String message) {
            super(message);
        }
    }
}
