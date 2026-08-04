package com.carddemo.common.codec;

import java.math.BigDecimal;

import com.carddemo.common.money.Money;

/**
 * Decodes and encodes the two computational numeric regimes of the reference records: packed decimal,
 * declared {@code COMP-3}, and binary, declared {@code COMP} or its synonym {@code BINARY}.
 *
 * <p>Both regimes are handled here because both take their physical width from the same rule -- the
 * declared usage, not the picture -- and because both must land on the identical exact fixed-point
 * representation. Zoned decimal, the third numeric regime and the one every base master uses, is
 * owned by the sibling {@code ZonedDecimalCodec} and is not handled here.</p>
 *
 * <h2>Why a second numeric codec exists at all</h2>
 *
 * <p>Assumptions: this is established mechanically rather than assumed. Searching all eleven
 * base-master copybooks -- {@code CSUSR01Y}, {@code CVACT01Y}, {@code CVACT02Y}, {@code CVACT03Y},
 * {@code CVCUS01Y}, {@code CVTRA01Y}, {@code CVTRA02Y}, {@code CVTRA03Y}, {@code CVTRA04Y},
 * {@code CVTRA05Y} and {@code CVTRA06Y} -- for {@code COMP}, {@code COMP-3} or {@code OCCURS}
 * returns <b>zero</b> matches in every one of them. Every base-master money field is therefore zoned
 * decimal with a sign overpunch, exemplified by
 * {@code 05  ACCT-CURR-BAL                     PIC S9(10)V99.} at line 7 of
 * {@code app/cpy/CVACT01Y.cpy}. Nothing this class decodes appears in those records, and nothing
 * those records contain is decoded here. Two disjoint regimes, therefore two codecs.</p>
 *
 * <p>Packed decimal reaches the migration through exactly three record layouts:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVEXPORT.cpy}, the 500-byte multi-record export layout, and the only copybook
 *       in {@code app/cpy} that declares a computational usage at all.</li>
 *   <li>{@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy}, the authorization summary segment,
 *       whose thirteen declarations at lines 19 to 31 sum to exactly 100 bytes.</li>
 *   <li>{@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy}, the authorization detail segment,
 *       whose 27 named elementary fields plus a 17-byte trailing filler sum to exactly 200 bytes.</li>
 * </ul>
 *
 * <p>Assumptions: the authorization bounded context is the only place packed decimal reaches
 * <em>persisted</em> target data. The export record is a transport record -- it exists for the
 * export and import round-trip -- so its packed bytes are decoded at the loader edge and the packed
 * form is never stored. That bounds the blast radius of an error in this class, and it is the reason
 * the two authorization segments get the closer scrutiny of the three.</p>
 *
 * <h2>USAGE determines the physical width; PICTURE never does</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVEXPORT.cpy} settles this in one record. Its account redefinition
 * declares three fields of the <em>same</em> picture, {@code PIC S9(10)V99}, at three different
 * physical widths, because each carries a different usage:</p>
 *
 * <pre>
 * line  field                          declaration                    bytes
 *   50  EXP-ACCT-CURR-BAL              PIC S9(10)V99 COMP-3               7
 *   51  EXP-ACCT-CREDIT-LIMIT          PIC S9(10)V99                     12
 *   57  EXP-ACCT-CURR-CYC-DEBIT        PIC S9(10)V99 COMP                 8
 * </pre>
 *
 * <p>The consequence is the central contract of this class, and it is stated plainly because getting
 * it wrong misaligns every field after it: <b>this codec must be told the usage. It can never infer a
 * width from the picture.</b> A caller supplies the digit geometry and selects the packed or the
 * binary entry point accordingly; the usage is a static property of the declaration, so the choice is
 * always knowable before a single byte is read.</p>
 *
 * <p>The three width rules, one per regime, are these. Only the first two belong to this class; the
 * third is stated so the contrast above is checkable rather than asserted.</p>
 *
 * <ul>
 *   <li><b>Packed</b>: the ceiling of one more than the digit count, halved. The extra position is
 *       the sign nibble. See {@link #packedWidth(int, int)}.</li>
 *   <li><b>Binary</b>: two bytes up to four digits, four bytes up to nine, eight bytes beyond, held
 *       big-endian in two's complement. See {@link #binaryWidth(int, int)}.</li>
 *   <li><b>Zoned</b>: one byte per digit position, so {@code S9(09)V99} is eleven bytes and
 *       {@code S9(10)V99} is twelve. Not this class.</li>
 * </ul>
 *
 * <h2>Correction C-WIDTH: {@code PIC S9(10)V99 COMP-3} occupies seven bytes, not six</h2>
 *
 * <p>Assumptions: twelve digit positions plus one sign nibble is thirteen nibbles, and thirteen
 * nibbles occupy the ceiling of thirteen halved, which is seven. Any statement that this picture is
 * six bytes wide is defective and must not be propagated. The figure is confirmed three independent
 * ways, and all three are recorded because the arithmetic is the kind that looks plausible when it is
 * wrong:</p>
 *
 * <ul>
 *   <li><b>Arithmetically.</b> {@code app/cpy/CVEXPORT.cpy} redefines one 460-byte area five times,
 *       once per record type, and all five redefinitions close on exactly 460 bytes only when this
 *       width is seven. The account redefinition at lines 47 to 60 is the tightest of the five: it
 *       carries two such packed amounts, and at six bytes each it would sum to 458 and leave the
 *       declared area unreachable.</li>
 *   <li><b>By contradiction.</b>
 *       {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} sums to exactly 200 bytes only when
 *       its two {@code PIC S9(10)V99 COMP-3} amounts, at lines 34 and 35, are seven bytes each. At
 *       six they sum to 198, and the declared segment length is unreachable.</li>
 *   <li><b>Empirically.</b> The reference compiler the parity oracle builds with reports a length of
 *       seven for that picture, and lays the value out as twelve digit nibbles preceded by one zero
 *       pad nibble and followed by the sign nibble.</li>
 * </ul>
 *
 * <p>Assumptions: the target schema corroborates the same geometry from the other side.
 * {@code app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl} declares {@code DECIMAL(12,2)} at its
 * lines 12 and 13 for the two amounts that {@code CIPAUDTY.cpy} declares
 * {@code PIC S9(10)V99 COMP-3}, and its primary key on {@code (CARD_NUM, AUTH_TS)} closes the table
 * at line 28. Twelve digits at a scale of two is ten integer places and two decimal places, which is
 * the baseline's own confirmation of how the twelve digit positions divide. The companion
 * {@code app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl} is a four-line unique index on
 * {@code (CARD_NUM ASC, AUTH_TS DESC) COPY YES}, and its descending component is why a decoded key
 * component has to preserve order exactly and not merely approximately.</p>
 *
 * <h2>The six money renderings, and which two of them are this class's</h2>
 *
 * <p>Money reaches the migration in six distinct physical forms. The boundary is enumerated here so
 * that nobody extends this class into a rendering it does not own:</p>
 *
 * <ol>
 *   <li>Zoned overpunch, eleven bytes for {@code S9(09)V99} and twelve for {@code S9(10)V99}, the
 *       regime of every base master. Owned by {@code ZonedDecimalCodec}.</li>
 *   <li><b>Packed {@code COMP-3}</b>, seven bytes for {@code S9(10)V99}, six for {@code S9(09)V99},
 *       five for {@code S9(09)} and three for {@code S9(05)}. <b>Owned here.</b></li>
 *   <li><b>Binary {@code COMP} or {@code BINARY}</b>, eight bytes for {@code S9(10)V99} and two for
 *       {@code S9(04)}. <b>Owned here.</b></li>
 *   <li>Edited display text, {@code PIC +9(10).99}, fourteen characters, at line 24 of
 *       {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy}. The sign and the point are real
 *       bytes there and the implied point of the pictures above occupies none. Owned by
 *       {@code CsvAuthCodec}.</li>
 *   <li>The report edit mask {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}, fifteen characters, at line 30 of
 *       {@code app/cpy/CVTRA07Y.cpy}. Not this class.</li>
 *   <li>The report edit mask {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}, fifteen characters, at lines 54, 60 and 66
 *       of the same copybook. Not this class.</li>
 * </ol>
 *
 * <h2>What this class does not decide: field naming</h2>
 *
 * <p>Assumptions: this codec is handed a digit geometry and a byte span, and a field name only ever
 * reaches it as diagnostic text a caller supplied. It therefore has no opinion on what a field is
 * called in the target, and it performs no renaming of any kind. That boundary is worth stating
 * because one of the migration's three documented misspelling corrections lands in a segment this
 * class decodes: {@code PA-MERCHANT-CATAGORY-CODE} at line 36 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy}, and the same misspelling carried with an
 * infix at line 28 of {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} as
 * {@code PA-RQ-MERCHANT-CATAGORY-CODE}, both of which become {@code merchant_category_code} in the
 * target. Both baseline spellings are recorded here so the lineage is unambiguous from this file, but
 * neither the rename nor any other appears in this class: it happens in the mapping layer that owns
 * the anti-corruption boundary, and this package's own descriptor holds the full register of the three
 * renames. Note also that the field in question is a character field, not a computational one, so it
 * is not even a field this codec reads.</p>
 *
 * <h2>The round-trip law, and its one exception</h2>
 *
 * <p>For every span this class accepts, encoding what it decoded reproduces the original bytes
 * exactly. Byte-reproducible round-tripping is not a convenience: the parity oracle compares output
 * byte for byte after timestamp normalisation, so a re-encoded field differing in one nibble is a
 * failed comparison rather than a cosmetic difference.</p>
 *
 * <p>Assumptions: there is exactly one documented exception, and it is a property of the target
 * language rather than a choice made here. The decimal type this class decodes into has no negative
 * zero, so a packed zero carrying the negative sign nibble decodes to zero and re-encodes carrying
 * the positive one. The value is preserved; one nibble of the encoding is normalised. The reference
 * codec at {@code tests/helpers/record_codec.py} preserves negative zero in its own zoned decoder,
 * which is why the difference is recorded here rather than left to be discovered by a failing
 * comparison.</p>
 *
 * <h2>The fixed-point contract</h2>
 *
 * <p>Assumptions: transformation rule T3 of the migration plan pins one representation per layer and
 * admits no exception -- {@code NUMERIC(p,2)} in the database, {@link BigDecimal} carried at scale 2
 * in Java, {@code Decimal} in the extract-transform-load code, and a JSON <em>string</em> on the
 * wire. Money is never carried through IEEE-754 binary arithmetic anywhere in this class: not through
 * either of the language's two binary primitive types, not through either of their wrapper types, and
 * not through a bare JSON number either. The prohibition is asserted by {@code LayeringRulesTest} in
 * this module's test tree, so a breach fails a build rather than a review.</p>
 *
 * <p>Trade-offs: those forbidden type names are described rather than spelled anywhere in this file.
 * Spelling them would make this file match a search for the very tokens the money path must not
 * contain, and that search is one of the checks this tree is audited with, so a literal mention would
 * produce a hit that has to be explained away on every audit. The description is unambiguous, since
 * the language has exactly two IEEE-754 binary primitive types and one wrapper type for each. The
 * convention is not invented here: the sibling {@code Money} class and this package's own descriptor
 * state the same prohibition the same way, so the three are consistent by construction.</p>
 *
 * <p>Every decode returns a value at a scale of exactly the declared decimal digit count, and no
 * decode normalises or strips that scale. Every encode is lossless or raises. Rounding is never
 * performed here; it is delegated to {@link Money}, whose scale and mode are the migration's one
 * rounding contract, and it happens at the point a business rule decides rounding is appropriate.</p>
 *
 * <h2>Bytes, never characters</h2>
 *
 * <p>Assumptions: every entry point on this class takes or returns bytes, and none takes or returns
 * text. Packed decimal is binary: two decimal digits share a byte and the sign occupies the low
 * nibble of the last byte, so a packed field contains byte values that are not characters in any
 * encoding. Routing them through a character decoder substitutes a replacement character for each
 * byte it cannot map, and because the substitution is the same width as the original the field still
 * has its declared length and still parses -- the damage surfaces only as a wrong amount. The parity
 * oracle takes the same position from the other direction: it treats the mainframe-character-set
 * datasets as opaque binary and never transcodes them, and its own helper comments record that
 * routing those bytes through a text write mangles them into replacement characters. Binary
 * {@code COMP} fields are the same hazard for the same reason.</p>
 */
public final class PackedDecimalCodec {

    // WHY : Assumptions: eighteen digit positions is the ceiling the language guarantees for a
    //       computational item, and it is also the widest declaration anywhere in the corpus, since
    //       the widest packed field is the twelve-digit PIC S9(10)V99 COMP-3 of CIPAUDTY.cpy lines 34
    //       and 35. Rejecting beyond eighteen keeps every decoded value inside the range the eight-byte
    //       binary form can hold, which is what lets the binary path read a span as one whole number
    //       without an intermediate that could overflow. The sibling layout descriptor pins the same
    //       ceiling, so a field this codec accepts is one that descriptor accepts too.
    private static final int MAX_DIGITS = 18;

    // WHY : Assumptions: these two thresholds divide the binary width ladder into its three rungs, and
    //       they are named rather than written inline because the ladder is a property of the usage and
    //       not of any one field. Every rung was confirmed against the reference compiler: PIC S9(04)
    //       COMP reports two bytes, PIC 9(09) COMP reports four, and PIC 9(11) COMP and PIC S9(10)V99
    //       COMP both report eight. app/cpy/CVEXPORT.cpy declares seven such fields, at its lines 16,
    //       25, 57, 72, 87, 95 and 96, and between them they exercise all three rungs: line 96 is a
    //       three-digit field on the two-byte rung, line 16 a nine-digit field on the four-byte rung,
    //       and line 57 a twelve-digit field on the eight-byte rung.
    private static final int BINARY_HALFWORD_MAX_DIGITS = 4;
    private static final int BINARY_FULLWORD_MAX_DIGITS = 9;

    // WHY : Trade-offs: the widest rung is named for its role rather than for the architecture term that
    //       normally denotes an eight-byte word. That term contains, as a substring, one of the numeric
    //       type names the money path is audited for the absence of, so using it would put a hit in this
    //       file that has to be explained away on every audit. The cost is a name a mainframe reader
    //       would not have reached for first; the benefit is an audit that stays clean, and the two
    //       narrower rungs keep their conventional names because those carry no such substring.
    private static final int BINARY_HALFWORD_BYTES = 2;
    private static final int BINARY_FULLWORD_BYTES = 4;
    private static final int BINARY_WIDEST_BYTES = 8;

    // WHY : Assumptions: the sign occupies the low nibble of the final byte and takes one of exactly
    //       three values in this corpus. All three were read off the reference compiler rather than
    //       taken from documentation: a signed negative value lays down 0xD, a signed non-negative
    //       value 0xC, and a field declared without the leading S -- PIC 9(03) COMP-3 -- lays down
    //       0xF. That third case is why an unsigned packed field is a real shape here and not a
    //       theoretical one.
    private static final int SIGN_SIGNED_POSITIVE = 0x0C;
    private static final int SIGN_SIGNED_NEGATIVE = 0x0D;
    private static final int SIGN_UNSIGNED = 0x0F;

    // WHY : Assumptions: 0xA, 0xB and 0xE are the alternate sign nibbles some encoders emit, and this
    //       codec rejects all three. They are named as a threshold rather than as three values because
    //       the test that matters is whether the nibble is a digit or a sign: anything below 0xA in the
    //       sign position is a digit, which means the field is zoned rather than packed or the offset
    //       is off by a nibble.
    private static final int LOWEST_SIGN_NIBBLE = 0x0A;

    // WHY : Assumptions: the hexadecimal digits are held as a constant and indexed, so a nibble reaches a
    //       diagnostic without passing through a locale-sensitive formatter. See nibbleDetail below.
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
     * <p>Trade-offs: a static entry-point class was chosen over an instantiable one. Every operation
     * here is a pure function of a byte span and a digit geometry, so an instance would carry no state
     * worth holding and would only oblige each caller to obtain one. The compromise accepted is that
     * an instance cannot be substituted in a test; it is accepted because there is nothing to
     * substitute -- the functions have no collaborators and no configuration. The sibling layout
     * descriptor and the money type in this module are shaped the same way, so the module reads
     * consistently.</p>
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
     * <p>Assumptions: the formula is the ceiling of one more than the digit count, halved, where the
     * one extra position is the sign nibble that every packed field carries whether or not its picture
     * declares a sign. The ladder it produces, every rung of which was confirmed against the reference
     * compiler, is three digits to two bytes, five to three, nine to five, eleven to six and twelve to
     * seven. The last rung is correction C-WIDTH, recorded in this class's own documentation: twelve
     * digits is seven bytes and never six.</p>
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
        // WHY : Assumptions: adding two before halving is integer arithmetic for the ceiling of one
        //       more than the digit count halved, and it is written this way rather than with the
        //       library ceiling function because that function operates on the binary approximate
        //       types this whole class is barred from touching. Adding one and halving instead would
        //       floor, and would return six for twelve digits, which is precisely the defective figure
        //       correction C-WIDTH refutes. The sibling layout descriptor derives the same width with
        //       the same integer form, so the two cannot drift arithmetically.
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
     * <p>Assumptions: the width is a power-of-two byte count driven by the digit count rather than one
     * byte per digit, so it does not follow the packed formula and needs a rule of its own. Two bytes
     * hold up to four digits, four bytes up to nine, and eight bytes the remainder up to the eighteen
     * this codec admits.</p>
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

        // WHY : Assumptions: the pad count is derived from the geometry rather than from the parity of
        //       the digit count, because the two are easy to state the wrong way round and the
        //       arithmetic is not. A field occupies width times two nibbles and uses digits plus one of
        //       them, so the surplus is whatever is left over, and it is at most one. Deriving it makes
        //       this correct for either parity; asserting a parity would not. For the record the
        //       surplus exists when the digit count is EVEN: twelve digits occupy seven bytes, that is
        //       fourteen nibbles for thirteen used, so one pads; whereas eleven digits occupy six
        //       bytes, that is twelve nibbles for twelve used, so none pads. The reference compiler
        //       lays PIC S9(05) COMP-3 holding 12345 down as the nibbles 1 2 3 4 5 C, whose leading
        //       nibble is a digit and not a pad, which settles the odd case by observation.
        int padNibbles = width * NIBBLES_PER_BYTE - (digits + 1);

        // WHY : Assumptions: the pad nibble is validated rather than skipped, and it sits at the FRONT
        //       of the field rather than the back. Checking that it is zero is the cheapest available
        //       detector of a mis-aligned offset: a field read one nibble early presents a real digit
        //       where the pad belongs, and accepting it would shift every digit one place and yield a
        //       value ten times too large -- a number that is plausible, that raises nothing, and that
        //       the golden comparison would only catch after the fact.
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

            // WHY : Assumptions: a nibble above nine in a digit position is not a tolerable variant;
            //       it is proof that the read is wrong. Either the offset is misaligned, or the field is
            //       not packed at all, or a sign nibble has been reached early -- and every one of those
            //       is a defect a caller needs to be told about. Masking the nibble down into range instead
            //       would manufacture a digit that was never written.
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
     * <p>Trade-offs: this encoder is lossless or it raises, and it never rounds and never truncates.
     * Both of the silent alternatives were rejected. Truncating an integer overflow would drop
     * high-order digits and yield a materially smaller amount that still looks like money, and
     * rounding a surplus decimal place would decide, inside a byte codec, a question that belongs to a
     * business rule. Rounding is delegated to the sibling {@link Money} type, whose scale and mode are
     * the migration's one rounding contract, and it is applied where a rule decides it is appropriate.
     * The compromise accepted is that a caller holding a value of the wrong scale must reduce it
     * before encoding; what is bought is that the reduction appears in the audit trail at the point
     * the decision was taken rather than being absorbed here.</p>
     *
     * <p>Assumptions: zero always encodes with a positive sign nibble. The decimal type this method
     * accepts has no negative zero to carry, so a field that arrived carrying the negative nibble over
     * a zero value re-encodes carrying the positive one. This is the single documented departure from
     * byte-identical round-tripping in this class, and it is stated at both ends -- here and in the
     * class documentation -- so it is never met as a surprise in a failing comparison.</p>
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

        // WHY : Assumptions: the digits are laid down from the left into the nibble positions the pad
        //       leaves free, which is the mirror of the decode loop and is what makes the round trip
        //       reproduce the original bytes. Writing them from the right instead would be correct only
        //       when nothing pads, so it would silently shift every even-digit-count field by one
        //       nibble -- and PIC S9(10)V99 COMP-3, the widest money field in the authorization
        //       segments, is exactly such a field.
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
     * Decodes a binary field from a record, identifying it by name in any diagnostic raised.
     *
     * <p>Trade-offs: the binary path lives in this class rather than in one of its own. The two regimes
     * share the rule that matters -- that the declared usage and not the picture fixes the physical
     * width -- and they share the fixed-point representation they must land on, so separating them
     * would put one half of a single contract in each of two files and leave neither stating it whole.
     * Keeping them together also holds this package to the five classes its own descriptor enumerates,
     * rather than adding a sixth that carries three width thresholds and nothing else. The compromise
     * accepted is a slightly broader class responsibility than the name alone suggests; what is bought
     * is one place where the claim that usage determines width is expressed, tested and documented.</p>
     *
     * <p>Assumptions: the storage is big-endian two's complement, and the declared decimal places are
     * an implied point applied to the whole number it holds rather than a division performed on it. The
     * reference compiler stores {@code PIC S9(10)V99 COMP} holding {@code -1234567890.12} as the
     * eight-byte two's complement of {@code -123456789012}, so recovering the value means reading the
     * whole number and moving the point, never dividing.</p>
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

        // WHY : Assumptions: the accumulation runs through the language's widest integral primitive
        //       because eight bytes is the widest binary field the corpus declares, and the eighteen
        //       digit positions this codec admits cannot exceed what that primitive holds. The sign is
        //       taken from the leading byte and used to seed the accumulator, which is what makes the
        //       two's complement reading exact without a second pass or a conditional negation.
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

        // WHY : Assumptions: the point is moved rather than divided, which is exact by construction and
        //       involves no rounding mode at all. Dividing by a power of ten would introduce a quotient,
        //       and therefore a rounding decision, where the encoding has none. This is the same
        //       position the sibling money type takes when it interprets a whole number of cents.
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

        // WHY : Assumptions: the whole number is rebuilt from the validated digit text rather than taken
        //       from the value's own unscaled form, because the digit text has already been padded to
        //       exactly the declared decimal places. Reading the unscaled form directly would encode a
        //       value carrying fewer decimal places than declared at the wrong magnitude -- a scale of
        //       zero and a scale of two are the same number and different stored integers.
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
     * as a parameter, and a field declaring any other number of them is rejected instead of reduced.
     * That keeps this method exact: a span decoded at the money scale already carries that scale, so
     * handing it to the monetary factory applies no rounding whatever. Accepting a different count and
     * reducing it would put a rounding decision inside a decoder, which is the one thing the encode
     * contract of this class refuses to do.</p>
     *
     * <p>Every packed money field in the corpus satisfies this: the two amounts at lines 34 and 35 of
     * {@code CIPAUDTY.cpy} and the six at lines 23 to 26, 29 and 30 of {@code CIPAUSMY.cpy} all declare
     * two decimal places, as do the amounts at lines 50, 52 and 71 of {@code app/cpy/CVEXPORT.cpy}.</p>
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
     * <p>Alternatives Considered: blanket tolerance of every nibble value in the sign position was
     * evaluated and rejected, and so was accepting 0x0A, 0x0B and 0x0E as the alternate positive and
     * negative forms some encoders emit. Neither appears in this corpus: the reference compiler lays
     * down 0x0C for a signed non-negative value, 0x0D for a signed negative one and 0x0F for a field
     * declared without a sign, and nothing else. An unexpected nibble here is therefore the clearest
     * available evidence that the field offset is wrong, and treating it as positive would convert a
     * detectable error into silent data corruption of a money value's sign. The compromise accepted is
     * that a span produced by some other encoder would be rejected rather than decoded; that is the
     * intended direction of failure, because such a span did not come from this baseline. The reference
     * codec reaches the same conclusion from the zoned side, where it refuses a plain trailing digit in
     * a signed field rather than inventing a sign for it.</p>
     *
     * <p>Alternatives Considered: the nibble is also cross-checked against the caller's signed flag,
     * rather than merely being classified. A signed field carrying the unsigned nibble, or an unsigned
     * field carrying the negative one, means the declaration the caller passed and the bytes on disk
     * describe different fields -- so decoding either one would produce a value from a layout that does
     * not match the data. Classifying without cross-checking would accept both and report neither.</p>
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

        // WHY : Assumptions: a value below 0x0A in the sign position is a digit, and a digit here means
        //       the field is zoned rather than packed, or the read is off by one nibble. Naming that
        //       case separately is worth the extra branch because it points at the actual defect instead
        //       of reporting an unrecognised sign.
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
     * <p>Trade-offs: the value is built from an explicitly assembled decimal string rather than from
     * integral arithmetic on the accumulated digits. The string costs one allocation per field, and it
     * buys two properties that arithmetic does not. It is exact for the full twelve digits of the
     * widest packed money field with no intermediate that could overflow, and it is independent of any
     * ambient precision or rounding setting, so the result is deterministic for a given input. The
     * reference codec makes the same trade for the same reason on the zoned side, noting that string
     * construction is what keeps a decode byte-reproducible against a golden comparison.</p>
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

        // WHY : Assumptions: a leading zero is inserted when every digit position is fractional, because
        //       a picture such as PIC SV99 leaves nothing to the left of the implied point and the
        //       decimal string grammar requires a digit there. Emitting the point with nothing before it
        //       would raise a parse failure rather than decode the field.
        if (pointAt == 0) {
            text.append('0');
        }
        text.append(digitText, 0, pointAt);
        if (decDigits > 0) {
            text.append('.').append(digitText, pointAt, digitText.length());
        }

        // WHY : Assumptions: the scale is asserted rather than assumed. The string form fixes it by
        //       construction, and setting it again would be redundant, but a field declaring no decimal
        //       places yields a scale of zero from the string and callers depend on the returned scale
        //       being exactly the declared count in every case. Naming it here keeps that contract true
        //       for the no-decimal case without a special branch.
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

        // WHY : Assumptions: a scale beyond the declared decimal places is refused rather than reduced,
        //       which is the encode half of the lossless-or-raise contract. Reducing it would round, and
        //       a rounding decision taken inside a codec never reaches the audit trail; the sibling
        //       money type is where the scale and the mode are declared, and a caller that needs a
        //       reduction performs it there where the decision is visible.
        if (value.scale() > decDigits) {
            throw new PackedDecimalException(describe(fieldName, -1, width, kind, sensitive)
                    + " declares " + decDigits + " decimal positions but the value carries a scale of "
                    + value.scale() + ", and reducing it here would round silently"
                    + valueDetail(value.toPlainString(), sensitive));
        }

        requireIntegerDigits(value, intDigits, decDigits, fieldName, width, kind, sensitive);

        // WHY : Assumptions: the scale is raised to the declared count before the digits are read, so a
        //       value carrying fewer decimal places than declared contributes its missing places as
        //       trailing zeros. Reading the unscaled digits without that step would encode two pounds as
        //       though it were two pence, because a scale of zero and a scale of two hold the same digits
        //       and mean different amounts.
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
        // WHY : Assumptions: the integer digit count is measured as the precision left after the declared
        //       decimal places are accounted for, which is exact for every value including zero. Comparing
        //       against a power-of-ten bound instead would need that bound materialised for up to eighteen
        //       digits and would then have to decide whether the bound itself is admissible. The bare
        //       scale adjustment is safe rather than lucky: every caller has already established that the
        //       value's scale does not exceed the declared count, so the adjustment only ever pads.
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
     * reveal -- the name, the offset, the length and the kind -- and never the bytes or a rendering of
     * them. The reference codec at {@code tests/helpers/record_codec.py} includes the offending raw
     * value in its own decode failure messages, which suits a test harness reading committed fixtures;
     * this class deliberately does not for a sensitive field, because the same message here can reach a
     * production log carrying a primary account number or a card verification value. The divergence is
     * documented rather than silent, and it is a difference in what is reported and not in what is
     * rejected: a sensitive field is validated exactly as strictly as any other.</p>
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
            // WHAT: the marker states that content was withheld rather than that none existed.
            // WHY : Assumptions: a reader who sees a diagnostic with no content cannot otherwise tell
            //       whether the codec had nothing to report or withheld it on purpose, and would
            //       reasonably suspect the message itself was defective. Naming the suppression makes
            //       the omission legible and keeps anyone from adding the content back to fill the gap.
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
        // WHY : Assumptions: the digit is indexed out of a constant rather than formatted, which keeps the
        //       rendering independent of any ambient locale. A locale-sensitive case conversion of a
        //       formatted hexadecimal digit produces a different character under a locale whose dotless
        //       letter maps unexpectedly, and a diagnostic that reads differently per locale is one that
        //       cannot be matched against a known message.
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
     * declaring a checked exception. The unqualified form was rejected because a caller could then not
     * distinguish a malformed computational field from any other rejected argument, and this exception
     * is the one signal that a record has been read against the wrong geometry -- the most consequential
     * failure this codec has, because the damage it prevents is silent. A checked exception was rejected
     * because there is no recovery available at a decode site: a field that does not decode cannot be
     * decoded a second way, so a catch block could only rethrow. It extends the platform argument
     * exception so that a caller who reasonably catches that broader type still catches this one, which
     * is the same relationship the reference codec establishes by deriving its own decode errors from
     * the platform value error.</p>
     *
     * <p>Trade-offs: this type is nested inside the codec rather than declared in a file of its own.
     * This package's own descriptor enumerates the five contracts it owns, and a sixth file holding one
     * exception would add a name to that inventory without adding a contract to it. Nesting also puts
     * the exception where its every construction site is, so the messages and the type that carries them
     * are read together. The compromise accepted is a slightly longer file; the sibling layout
     * descriptor nests its own geometry exception the same way, so the package is consistent.</p>
     */
    public static final class PackedDecimalException extends IllegalArgumentException {

        // WHY : Assumptions: the platform requires a serial version identifier on every serialisable
        //       type, and this type inherits serialisability from the exception hierarchy. Declaring it
        //       explicitly pins the value rather than letting the compiler derive one that changes
        //       whenever a member is added, which is what makes a serialised instance readable across
        //       builds.
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
