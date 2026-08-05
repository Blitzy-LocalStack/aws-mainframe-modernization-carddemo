package com.carddemo.common.codec;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.common.money.Money;

/**
 * Converts complete COBOL record images to ordered field maps and back again.
 *
 * <p><b>Purpose.</b> This class is the composition boundary for the copybook
 * codec package. It consumes a {@link CopybookLayout.RecordSpec}, validates the
 * record geometry, slices each field by its zero-based byte interval, and
 * dispatches the field to the codec selected by
 * {@link CopybookLayout.FieldSpec#kind()}. The inverse operation writes every
 * field into a byte array whose length is exactly the record's declared
 * {@link CopybookLayout.RecordSpec#reclen()}.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> The public decode
 * operations accept record bytes plus a record or field descriptor and return
 * ordered maps or typed field values. The public encode operations accept field
 * values plus descriptors and return or update exact-width byte arrays. Every
 * method documents its parameters, return value where one exists, and every
 * exception it can raise. This type itself accepts no construction parameters,
 * returns no value, and is not instantiated.</p>
 *
 * <h2>Eleven base-master records and their omitted padding</h2>
 *
 * <p>The lengths below are sums of the elementary PICTURE widths, never values
 * parsed from a banner. The final column names the field omitted from a decoded
 * map when it contains only blanks. The omission is reversed during encoding
 * by writing charset-correct blank bytes through the declared end of the
 * record.</p>
 *
 * <pre>
 * dataset   copybook and declaration                         bytes  omitted padding
 * USRSEC    CSUSR01Y.cpy lines 17-23 SEC-USER-DATA              80  SEC-USR-FILLER X(23)
 * ACCTDATA  CVACT01Y.cpy lines 4-17 ACCOUNT-RECORD              300  FILLER X(178)
 * CARDDATA  CVACT02Y.cpy lines 4-11 CARD-RECORD                 150  FILLER X(59)
 * CUSTDATA  CVCUS01Y.cpy lines 4-23 CUSTOMER-RECORD             500  FILLER X(168)
 * CARDXREF  CVACT03Y.cpy lines 4-8 CARD-XREF-RECORD              50  FILLER X(14)
 * DALYTRAN  CVTRA06Y.cpy lines 4-18 DALYTRAN-RECORD             350  FILLER X(20)
 * TRANSACT  CVTRA05Y.cpy lines 4-18 TRAN-RECORD                 350  FILLER X(20)
 * DISCGRP   CVTRA02Y.cpy lines 4-10 DIS-GROUP-RECORD             50  FILLER X(28)
 * TRANCATG  CVTRA04Y.cpy lines 4-9 TRAN-CAT-RECORD               60  FILLER X(04)
 * TRANTYPE  CVTRA03Y.cpy lines 4-7 TRAN-TYPE-RECORD              60  FILLER X(08)
 * TCATBALF  CVTRA01Y.cpy lines 4-10 TRAN-CAT-BAL-RECORD          50  FILLER X(22)
 * </pre>
 *
 * <p>Alternatives Considered: parsing a record-length banner was rejected
 * because the source has four incompatible regimes. {@code CVACT01Y.cpy} line
 * 2 uses {@code RECLN 300}, {@code CVTRA01Y.cpy} line 2 uses
 * {@code RECLN = 50}, {@code CVEXPORT.cpy} line 5 uses the prose form
 * {@code Total Record Length: 500 bytes}, and {@code CSUSR01Y.cpy} has no
 * banner at all. Summing the PICTURE widths closes all eleven rows above and
 * leaves the banners as independent cross-checks rather than inputs.</p>
 *
 * <h2>Offset cross-check</h2>
 *
 * <p>Summing {@code CVTRA05Y.cpy} lines 5-14 places
 * {@code TRAN-CARD-NUM} at zero-based offset 262, and adding its 16 bytes plus
 * the 26-byte originating timestamp places {@code TRAN-PROC-TS} at 304.
 * Independently, {@code app/jcl/TRANREPT.jcl} lines 41-42 declare one-based
 * positions 263 and 305, and {@code app/jcl/TRANIDX.jcl} line 27 declares
 * {@code KEYS(26 304)}. {@code app/jcl/PRTCATBL.jcl} lines 47-50 provide a
 * separate check: one-based position 18 and width 11 for
 * {@code TRAN-CAT-BAL} agree with {@code CVTRA01Y.cpy} line 9. The conversion
 * rule is always {@code zeroBased = oneBased - 1}; reversing that subtraction
 * shifts every subsequent field.</p>
 *
 * <h2>Character sets and storage kinds</h2>
 *
 * <p>The overloads without a charset use US-ASCII, matching the seed datasets
 * under {@code app/data/ASCII}. IBM code page 037 records are supplied through
 * the charset overloads and decoded one field at a time. The binary staging
 * comments at {@code tests/helpers/localstack_setup.py} lines 729, 741 and 1048
 * warn that routing those bytes through a text write creates replacement
 * characters. A whole record is therefore never decoded as text.</p>
 *
 * <p>Trade-offs: storage kind is consulted before any charset is used.
 * {@code PACKED} and {@code BINARY} spans go directly to
 * {@link PackedDecimalCodec}; only {@code TEXT}, {@code UINT} and
 * {@code ZONED} spans cross a character boundary. This branch order costs a
 * per-field dispatch, but {@code CVEXPORT.cpy} proves it is necessary: line 50
 * is a seven-byte packed amount, line 51 is a twelve-byte zoned amount, and
 * line 57 is an eight-byte binary amount inside one 500-byte image.</p>
 *
 * <p>Text is returned at its declared width so no call site guesses whether a
 * field maps to a variable-width description or a fixed-width key. A typed
 * downstream mapper may use {@link String#stripTrailing()} when its schema says
 * the field is variable-width. Ten-character ISO dates remain unchanged, so
 * their lexical order remains their date order. Twenty-six-character
 * timestamps also remain unchanged; an all-blank timestamp is represented as
 * the empty string and pads back to 26 blanks on encode. The
 * {@link CopybookLayout.FieldSpec#normalizeTs()} flag is metadata for parity
 * comparison and never causes this codec to reformat or normalise content.</p>
 *
 * <h2>Map, FILLER, and derivation policy</h2>
 *
 * <p>Trade-offs: an insertion-ordered {@link Map} is used instead of one typed
 * carrier per record. The map sacrifices compile-time field typing, but one
 * codec can then serve all eleven base masters plus derived records without
 * eleven parallel result classes. Typed anti-corruption views belong in the
 * downstream mappers, while this boundary remains faithful to copybook names
 * and declaration order.</p>
 *
 * <p>Trade-offs: a final blank padding field is omitted on decode and restored
 * on encode. Carrying it through every map would expose up to 178 meaningless
 * blank characters to each consumer; omitting it without restoring it would
 * produce a short physical record. An explicitly supplied or nonblank filler
 * remains content, which preserves the {@code VALUE} case proven by
 * {@code CVTRA07Y.cpy}: all 22 of its FILLER declarations carry VALUE clauses.
 * A {@code FILLER REDEFINES} item is an overlay alias and is absent from the
 * non-overlapping {@link CopybookLayout.FieldSpec} list, so it advances no
 * offset. The named {@code SEC-USR-FILLER} at {@code CSUSR01Y.cpy} line 23 is
 * still padding because it is final and blank.</p>
 *
 * <p>Assumptions: the registry distinguishes eleven base masters from three
 * derived records. The reference registry in
 * {@code tests/helpers/record_codec.py} lines 1349-1361 also has eleven
 * entries, but only eight are base masters; {@code TRNX}, {@code REJECT} and
 * {@code INTTRAN} are derived, while {@code SECUSER}, {@code TRANCAT} and
 * {@code TRANTYPE} are the three base masters without a Python round-trip
 * entry. Both counts are correct at different abstraction levels.</p>
 *
 * <p>Alternatives Considered: the 350-byte prefixes are derived rather than
 * copied. {@code REJECT} appends its four-byte reason and 76-byte description
 * to {@code DALYTRAN}; {@code INTTRAN} changes only the originating timestamp
 * flag on {@code TRAN}. Copying either field list would violate the
 * single-source rule at {@code tests/README.md} lines 540-542 and allow two
 * geometries for the same prefix to drift independently. Key length is checked
 * beside record length because the reference self-check at
 * {@code tests/helpers/record_codec.py} lines 1684-1730 validates both.</p>
 *
 * <h2>Sensitive data boundary</h2>
 *
 * <p>Assumptions: the descriptor's sensitive flag marks a field; it does not
 * mask or transform it. PAN masking, verification-value suppression and
 * identifier encryption belong to {@code services/*}{@code /mapper/*Mapper.java}.
 * Masking here would make byte-exact round-tripping impossible. The one
 * security behavior owned here is diagnostic hygiene: a failure for a
 * sensitive field reports only its name, offset, length and kind, never its
 * bytes or value. The reference implementation may include raw content in an
 * error; this Java path deliberately omits it for marked fields, and the
 * divergence is documented at the exception boundary.</p>
 *
 * <p>Assumptions: layout descriptors are Java constants, not runtime resources.
 * This library has no record-layout resource tree for the codec to discover;
 * its framework registration and shared defaults are unrelated resources.
 * Loading layouts by classpath name would make an absent or shadowed resource
 * look like an empty registry, while the Java registry is linked and checked
 * when this class is initialized.</p>
 */
public final class FixedWidthCodec {

    private static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;
    private static final String IBM037_CANONICAL_NAME = "IBM037";
    private static final int EXPECTED_LAYOUT_COUNT = 14;
    private static final int EXPECTED_BASE_MASTER_COUNT = 11;
    private static final int EXPECTED_DERIVED_COUNT = 3;
    private static final int TIMESTAMP_LENGTH = 26;

    static {
        // WHY : Assumptions: Java declarations are the only layout source and this library has no
        //       record-layout resource tree to fall back to. Running the registry proof at class load
        //       makes an omitted or mistyped layout fail before any service can decode a record,
        //       whereas resource discovery could silently return an empty set after packaging.
        validateRegistry();
    }

    /**
     * Prevents construction of this stateless codec holder.
     */
    private FixedWidthCodec() {
    }

    /**
     * Decodes one US-ASCII record into declaration-ordered field values.
     *
     * @param record the complete physical record image; never {@code null}
     * @param spec the record layout that defines every field interval; never {@code null}
     * @return an insertion-ordered map containing every non-padding field
     * @throws CopybookLayout.LayoutException if the supplied layout is invalid
     * @throws RecordLengthException if {@code record} is not exactly {@code spec.reclen()} bytes
     * @throws FieldCodecException if any field cannot be decoded under its declared kind
     */
    public static Map<String, Object> decodeRecord(byte[] record,
            CopybookLayout.RecordSpec spec) {
        return decodeRecord(record, spec, DEFAULT_CHARSET);
    }

    /**
     * Decodes one record into declaration-ordered field values using a field charset.
     *
     * <p>The charset applies only to character-backed fields. Packed and binary fields
     * remain raw byte spans even when their surrounding record uses IBM code page 037.</p>
     *
     * @param record the complete physical record image; never {@code null}
     * @param spec the record layout that defines every field interval; never {@code null}
     * @param charset US-ASCII or IBM code page 037 for character-backed fields; may be
     *     {@code null} only when the layout contains no character-backed field
     * @return an insertion-ordered map containing every non-padding field
     * @throws CopybookLayout.LayoutException if the supplied layout is invalid
     * @throws RecordLengthException if {@code record} is not exactly {@code spec.reclen()} bytes
     * @throws FieldCodecException if a charset is unsupported or any field cannot be decoded
     */
    public static Map<String, Object> decodeRecord(byte[] record,
            CopybookLayout.RecordSpec spec, Charset charset) {
        validateRecordSpec(spec);

        // WHY : Trade-offs: length is checked before the first slice. The single comparison gives up
        //       partial recovery from a truncated record, but tests/helpers/record_codec.py lines
        //       513-594 rejects that recovery because a plausible partial map can hide a shifted
        //       financial field. A boundary failure names expected and actual widths instead.
        requireRecordLength(record, spec);

        // WHY : Trade-offs: LinkedHashMap preserves the copybook declaration order while serving all
        //       layouts through one API. One typed carrier per layout would restore compile-time field
        //       names but would duplicate the eleven base-master shapes in a second class hierarchy.
        Map<String, Object> decoded = new LinkedHashMap<>();
        List<CopybookLayout.FieldSpec> declaredFields = spec.fields();
        for (int index = 0; index < declaredFields.size(); index++) {
            CopybookLayout.FieldSpec field = declaredFields.get(index);
            Object value = decodeField(record, field, charset);

            // WHY : Trade-offs: blank registered padding is omitted so consumers do not carry inert
            //       FILLER values, while nonblank or explicitly valued filler remains content. The
            //       omitted bytes are restored during encode, preserving the physical record width.
            if (!isDroppablePadding(spec, field, index, value)) {
                // WHY : Assumptions: sensitive marks diagnostic handling only. The codec must retain
                //       the actual value for byte-exact round trips; masking, suppression and
                //       encryption are mapper decisions, and applying them here would make encode of
                //       the decoded map reproduce different bytes.
                decoded.put(field.name(), value);
            }
        }
        return decoded;
    }

    /**
     * Encodes declaration-named values into one exact-width US-ASCII record.
     *
     * @param fields the values keyed by exact copybook field name; never {@code null}
     * @param spec the record layout that defines every target interval; never {@code null}
     * @return a newly allocated record of exactly {@code spec.reclen()} bytes
     * @throws CopybookLayout.LayoutException if the supplied layout is invalid
     * @throws FieldCodecException if a key is unknown, a required value is absent, or a field cannot
     *     be encoded
     */
    public static byte[] encodeRecord(Map<String, Object> fields,
            CopybookLayout.RecordSpec spec) {
        return encodeRecord(fields, spec, DEFAULT_CHARSET);
    }

    /**
     * Encodes declaration-named values into one exact-width record using a field charset.
     *
     * <p>The charset applies only to character-backed fields and to blank padding. Packed
     * and binary values are encoded directly by {@link PackedDecimalCodec}.</p>
     *
     * @param fields the values keyed by exact copybook field name; never {@code null}
     * @param spec the record layout that defines every target interval; never {@code null}
     * @param charset US-ASCII or IBM code page 037 for character-backed fields; may be
     *     {@code null} only when no character-backed field or blank padding is encoded
     * @return a newly allocated record of exactly {@code spec.reclen()} bytes
     * @throws CopybookLayout.LayoutException if the supplied layout is invalid
     * @throws FieldCodecException if the map is null, a key is unknown, a required value is absent,
     *     a charset is unsupported, or a field cannot be encoded
     */
    public static byte[] encodeRecord(Map<String, Object> fields,
            CopybookLayout.RecordSpec spec, Charset charset) {
        validateRecordSpec(spec);
        validateSuppliedFields(fields, spec);

        byte[] target = new byte[spec.reclen()];
        List<CopybookLayout.FieldSpec> declaredFields = spec.fields();
        for (int index = 0; index < declaredFields.size(); index++) {
            CopybookLayout.FieldSpec field = declaredFields.get(index);
            if (!fields.containsKey(field.name())) {
                if (isPaddingDescriptor(spec, field, index)) {
                    // WHY : Trade-offs: dropped trailing padding is rebuilt with the target charset's
                    //       blank byte. Leaving the new array's zero bytes in place would preserve
                    //       length but not content, and a COBOL reader distinguishes LOW-VALUES from
                    //       spaces even though both look empty in a Java string.
                    fillWithBlanks(target, field, charset);
                    continue;
                }
                throw new FieldCodecException("record " + spec.name()
                        + " is missing required field " + field.describe());
            }
            encodeField(fields.get(field.name()), field, target, charset);
        }
        return target;
    }

    /**
     * Decodes one US-ASCII field from its declared interval in a record image.
     *
     * @param record the byte array containing the field interval; never {@code null}
     * @param field the field descriptor selecting the interval and storage kind; never {@code null}
     * @return a {@link String}, {@link Long}, or {@link BigDecimal} according to the field kind
     * @throws FieldCodecException if the field is absent, outside the record, or malformed
     */
    public static Object decodeField(byte[] record, CopybookLayout.FieldSpec field) {
        return decodeField(record, field, DEFAULT_CHARSET);
    }

    /**
     * Decodes one field from its declared interval using a character-field charset.
     *
     * @param record the byte array containing the field interval; never {@code null}
     * @param field the field descriptor selecting the interval and storage kind; never {@code null}
     * @param charset US-ASCII or IBM code page 037 for character-backed kinds; ignored by packed and
     *     binary kinds
     * @return a {@link String}, {@link Long}, or {@link BigDecimal} according to the field kind
     * @throws FieldCodecException if the field is absent, outside the record, uses an unsupported
     *     charset, or contains bytes invalid for its declared kind
     */
    public static Object decodeField(byte[] record, CopybookLayout.FieldSpec field,
            Charset charset) {
        requireFieldSpan(record, field, "decode");

        try {
            // WHY : Trade-offs: kind is switched on before charset validation so PACKED and BINARY
            //       bytes can never enter a character decoder. CVEXPORT.cpy lines 50, 51 and 57 put
            //       packed, zoned and binary representations in one image, so record-wide decoding
            //       would corrupt two of those three branches while preserving the overall length.
            return switch (field.kind()) {
                case TEXT -> decodeText(record, field, charset);
                case UINT -> decodeUnsigned(record, field, charset);
                case ZONED -> decodeZoned(record, field, charset);
                case PACKED -> decodePacked(record, field);
                case BINARY -> decodeBinary(record, field);
            };
        } catch (FieldCodecException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw fieldFailure("decode", field, exception);
        }
    }

    /**
     * Encodes one value into its declared interval of a US-ASCII target record.
     *
     * @param value the value to encode according to {@code field.kind()}
     * @param field the field descriptor selecting the interval and storage kind; never {@code null}
     * @param target the byte array updated in place; never {@code null}
     * @throws FieldCodecException if the field is outside the target or the value is not encodable
     */
    public static void encodeField(Object value, CopybookLayout.FieldSpec field, byte[] target) {
        encodeField(value, field, target, DEFAULT_CHARSET);
    }

    /**
     * Encodes one value into its declared target interval using a character-field charset.
     *
     * @param value the value to encode according to {@code field.kind()}
     * @param field the field descriptor selecting the interval and storage kind; never {@code null}
     * @param target the byte array updated in place; never {@code null}
     * @param charset US-ASCII or IBM code page 037 for character-backed kinds; ignored by packed and
     *     binary kinds
     * @throws FieldCodecException if the field is outside the target, the charset is unsupported, or
     *     the value cannot be represented under the declared storage contract
     */
    public static void encodeField(Object value, CopybookLayout.FieldSpec field, byte[] target,
            Charset charset) {
        requireFieldSpan(target, field, "encode");

        try {
            switch (field.kind()) {
                case TEXT -> writeCharacters(textValue(value, field), field, target, charset);
                case UINT -> writeCharacters(unsignedText(value, field), field, target, charset);
                case ZONED -> writeCharacters(encodeZoned(value, field), field, target, charset);
                case PACKED -> copyEncoded(
                        PackedDecimalCodec.encodePacked(decimalValue(value, field),
                                field.intDigits(), field.decDigits(), field.signed(),
                                field.name(), field.sensitive()),
                        field, target);
                case BINARY -> copyEncoded(
                        PackedDecimalCodec.encodeBinary(decimalValue(value, field),
                                field.intDigits(), field.decDigits(), field.signed(),
                                field.name(), field.sensitive()),
                        field, target);
            }
        } catch (FieldCodecException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw fieldFailure("encode", field, exception);
        }
    }

    /**
     * Decodes one character field without discarding declared-width padding.
     *
     * @param record the record containing the field
     * @param field the character field descriptor
     * @param charset the character set applied to this field only
     * @return the exact decoded text, or an empty string for an all-blank 26-byte timestamp
     * @throws FieldCodecException if the charset is unsupported or the bytes do not round-trip
     */
    private static String decodeText(byte[] record, CopybookLayout.FieldSpec field,
            Charset charset) {
        String text = decodeCharacters(record, field, charset);

        // WHY : Assumptions: PIC X(10) dates are already ISO ordered, so preserving their bytes keeps
        //       lexical and date order equivalent, as TRANREPT.jcl line 42 relies on. PIC X(26)
        //       timestamps are likewise read faithfully; normalizeTs marks parity policy and does not
        //       authorise this codec to reformat a value owned by TimestampFormatter.
        if (isTimestampField(field) && isBlank(text)) {
            // WHY : Assumptions: 26 blanks are a legitimate absent timestamp. The committed reject
            //       records described at tests/helpers/record_codec.py lines 1292-1297 carry that
            //       exact value, so rejecting it would turn an unwritten processing stamp into corrupt
            //       input. The empty result pads back to the same 26 blanks during encoding.
            return "";
        }
        return text;
    }

    /**
     * Decodes one unsigned display field as a non-negative integral value.
     *
     * @param record the record containing the field
     * @param field the unsigned display field descriptor
     * @param charset the character set applied to this field only
     * @return the decoded value as a {@link Long}
     * @throws FieldCodecException if any character is not a decimal digit or the value is too large
     */
    private static Long decodeUnsigned(byte[] record, CopybookLayout.FieldSpec field,
            Charset charset) {
        String digits = decodeCharacters(record, field, charset);
        for (int index = 0; index < digits.length(); index++) {
            char candidate = digits.charAt(index);
            if (candidate < '0' || candidate > '9') {
                throw fieldFailure("decode", field,
                        new IllegalArgumentException("unsigned display contains a non-digit"
                                + " at relative offset " + index));
            }
        }
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException exception) {
            throw fieldFailure("decode", field,
                    new IllegalArgumentException("unsigned display exceeds the integral range",
                            exception));
        }
    }

    /**
     * Decodes one signed display field through the zoned-decimal codec.
     *
     * @param record the record containing the field
     * @param field the zoned-decimal field descriptor
     * @param charset the character set applied to this field only
     * @return the exact decoded value at {@code field.decDigits()} scale
     * @throws ZonedDecimalCodec.ZonedDecimalException if the field violates the overpunch contract
     * @throws FieldCodecException if the charset is unsupported or the bytes do not round-trip
     */
    private static BigDecimal decodeZoned(byte[] record, CopybookLayout.FieldSpec field,
            Charset charset) {
        String characters = decodeCharacters(record, field, charset);

        // WHY : Alternatives Considered: inlining the overpunch lookup was rejected because
        //       ZonedDecimalCodec owns the two canonical sign strings. A second copy here could drift
        //       and decode the same final byte differently in field and record operations.
        return ZonedDecimalCodec.decode(characters, field.intDigits(), field.decDigits(),
                field.signed(), fieldContext(field));
    }

    /**
     * Decodes one packed-decimal field without applying a character set.
     *
     * @param record the record containing the field
     * @param field the packed-decimal field descriptor
     * @return the exact decoded value at {@code field.decDigits()} scale
     * @throws PackedDecimalCodec.PackedDecimalException if the field violates packed geometry
     */
    private static BigDecimal decodePacked(byte[] record, CopybookLayout.FieldSpec field) {
        // WHY : Alternatives Considered: packed nibble handling remains in PackedDecimalCodec instead
        //       of being copied here. That class already validates pad, digit and sign nibbles, and a
        //       second implementation would let record and single-field decoding disagree silently.
        return PackedDecimalCodec.decodePacked(record, field.start(), field.intDigits(),
                field.decDigits(), field.signed(), field.name(), field.sensitive());
    }

    /**
     * Decodes one binary numeric field without applying a character set.
     *
     * @param record the record containing the field
     * @param field the binary field descriptor
     * @return the exact decoded value at {@code field.decDigits()} scale
     * @throws PackedDecimalCodec.PackedDecimalException if the field violates binary geometry
     */
    private static BigDecimal decodeBinary(byte[] record, CopybookLayout.FieldSpec field) {
        return PackedDecimalCodec.decodeBinary(record, field.start(), field.intDigits(),
                field.decDigits(), field.signed(), field.name(), field.sensitive());
    }

    /**
     * Encodes one zoned-decimal value through the dedicated overpunch codec.
     *
     * @param value the value accepted by {@link #decimalValue(Object, CopybookLayout.FieldSpec)}
     * @param field the zoned-decimal field descriptor
     * @return exactly {@code field.length()} display characters
     * @throws FieldCodecException if the value type is unsupported
     * @throws ZonedDecimalCodec.ZonedDecimalException if the value does not fit the field
     */
    private static String encodeZoned(Object value, CopybookLayout.FieldSpec field) {
        return ZonedDecimalCodec.encode(decimalValue(value, field), field.intDigits(),
                field.decDigits(), field.signed(), fieldContext(field));
    }

    /**
     * Converts a supported exact numeric input to the decimal type used by numeric codecs.
     *
     * @param value a {@link BigDecimal}, {@link Money}, integral wrapper, or decimal character text
     * @param field the field receiving the value, used for sensitive-safe diagnostics
     * @return the exact decimal value without rounding
     * @throws FieldCodecException if the value is null, approximate, or not valid decimal text
     */
    private static BigDecimal decimalValue(Object value, CopybookLayout.FieldSpec field) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Money money) {
            return money.amount();
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof CharSequence characters) {
            try {
                return new BigDecimal(characters.toString());
            } catch (NumberFormatException exception) {
                throw fieldFailure("encode", field,
                        new IllegalArgumentException("numeric text is not a plain decimal",
                                exception));
            }
        }
        throw fieldFailure("encode", field,
                new IllegalArgumentException("numeric value must be exact decimal, money,"
                        + " integral, or plain decimal text"));
    }

    /**
     * Converts a text-field input to characters without implicit object formatting.
     *
     * @param value the character sequence to encode
     * @param field the receiving text field, used for diagnostics
     * @return the supplied character content
     * @throws FieldCodecException if the value is null or is not a character sequence
     */
    private static String textValue(Object value, CopybookLayout.FieldSpec field) {
        if (value instanceof CharSequence characters) {
            return characters.toString();
        }
        throw fieldFailure("encode", field,
                new IllegalArgumentException("text value must be a character sequence"));
    }

    /**
     * Converts an unsigned display input to validated decimal characters.
     *
     * @param value an integral wrapper or decimal digit character sequence
     * @param field the receiving unsigned field, used for diagnostics
     * @return unpadded decimal digits
     * @throws FieldCodecException if the value is negative, empty, non-integral, or too wide
     */
    private static String unsignedText(Object value, CopybookLayout.FieldSpec field) {
        String digits;
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long integral = ((Number) value).longValue();
            if (integral < 0) {
                throw fieldFailure("encode", field,
                        new IllegalArgumentException("unsigned display cannot hold a negative value"));
            }
            digits = Long.toString(integral);
        } else if (value instanceof CharSequence characters) {
            digits = characters.toString();
        } else {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("unsigned display value must be integral"
                            + " or decimal digit text"));
        }

        if (digits.isEmpty()) {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("unsigned display value must not be empty"));
        }
        for (int index = 0; index < digits.length(); index++) {
            char candidate = digits.charAt(index);
            if (candidate < '0' || candidate > '9') {
                throw fieldFailure("encode", field,
                        new IllegalArgumentException("unsigned display contains a non-digit"
                                + " at relative offset " + index));
            }
        }
        if (digits.length() > field.length()) {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("unsigned display needs " + digits.length()
                            + " digits but the field holds " + field.length()));
        }
        return "0".repeat(field.length() - digits.length()) + digits;
    }

    /**
     * Decodes one character-backed span and proves the conversion is byte-reversible.
     *
     * @param record the record containing the field
     * @param field the field interval to decode
     * @param charset the supported single-byte character set for this field
     * @return the decoded characters
     * @throws FieldCodecException if the charset is unsupported or a byte is not reversible
     */
    private static String decodeCharacters(byte[] record, CopybookLayout.FieldSpec field,
            Charset charset) {
        Charset supported = requireCharset(charset, field);

        // WHY : Assumptions: IBM037 input is decoded in binary mode one field at a time. The staging
        //       warnings at tests/helpers/localstack_setup.py lines 729, 741 and 1048 identify a
        //       whole-record text write as the path that replaces bytes while leaving widths plausible.
        String decoded = new String(record, field.start(), field.length(), supported);
        byte[] roundTrip = decoded.getBytes(supported);
        if (roundTrip.length != field.length()) {
            throw fieldFailure("decode", field,
                    new IllegalArgumentException("character decoding changed the field byte count"));
        }
        for (int index = 0; index < roundTrip.length; index++) {
            if (roundTrip[index] != record[field.start() + index]) {
                throw fieldFailure("decode", field,
                        new IllegalArgumentException("character decoding is not byte-reversible"
                                + " at relative offset " + index));
            }
        }
        return decoded;
    }

    /**
     * Writes character content into a field and fills the unused suffix with blanks.
     *
     * @param text the characters to encode
     * @param field the target field interval
     * @param target the target record updated in place
     * @param charset the supported single-byte character set for this field
     * @throws FieldCodecException if the text is unrepresentable, too wide, or uses an unsupported
     *     charset
     */
    private static void writeCharacters(String text, CopybookLayout.FieldSpec field,
            byte[] target, Charset charset) {
        Charset supported = requireCharset(charset, field);
        byte[] encoded = text.getBytes(supported);
        if (!new String(encoded, supported).equals(text)) {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("character value is not representable"
                            + " in charset " + supported.name()));
        }
        if (encoded.length > field.length()) {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("character value needs " + encoded.length
                            + " bytes but the field holds " + field.length()));
        }

        byte blank = blankByte(supported, field);
        for (int index = 0; index < field.length(); index++) {
            target[field.start() + index] = index < encoded.length ? encoded[index] : blank;
        }
    }

    /**
     * Copies one packed or binary encoder result into its record interval.
     *
     * @param encoded the exact field bytes returned by a numeric codec
     * @param field the target field interval
     * @param target the target record updated in place
     * @throws FieldCodecException if the delegated codec returned the wrong width
     */
    private static void copyEncoded(byte[] encoded, CopybookLayout.FieldSpec field,
            byte[] target) {
        if (encoded.length != field.length()) {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("numeric codec returned " + encoded.length
                            + " bytes for a field of length " + field.length()));
        }
        System.arraycopy(encoded, 0, target, field.start(), encoded.length);
    }

    /**
     * Fills one omitted padding interval with the charset's blank byte.
     *
     * @param target the target record updated in place
     * @param field the padding field interval
     * @param charset the character set defining the physical blank byte
     * @throws FieldCodecException if the charset is unsupported or its blank is not one byte
     */
    private static void fillWithBlanks(byte[] target, CopybookLayout.FieldSpec field,
            Charset charset) {
        Charset supported = requireCharset(charset, field);
        byte blank = blankByte(supported, field);
        for (int index = field.start(); index < field.end(); index++) {
            target[index] = blank;
        }
    }

    /**
     * Returns the physical blank byte for a supported character set.
     *
     * @param charset the already validated character set
     * @param field the field receiving blanks, used for diagnostics
     * @return the one-byte encoding of an ordinary space
     * @throws FieldCodecException if the character set does not encode a blank as one byte
     */
    private static byte blankByte(Charset charset, CopybookLayout.FieldSpec field) {
        byte[] encoded = " ".getBytes(charset);
        if (encoded.length != 1) {
            throw fieldFailure("encode", field,
                    new IllegalArgumentException("charset " + charset.name()
                            + " does not encode a blank as one byte"));
        }
        return encoded[0];
    }

    /**
     * Accepts only the two single-byte character sets admitted by the dataset contract.
     *
     * @param charset the caller-supplied character set
     * @param field the character-backed field requesting it
     * @return the validated charset
     * @throws FieldCodecException if {@code charset} is null or is neither US-ASCII nor IBM037
     */
    private static Charset requireCharset(Charset charset, CopybookLayout.FieldSpec field) {
        if (charset == null) {
            throw fieldFailure("process", field,
                    new IllegalArgumentException("character-backed field requires a charset"));
        }
        if (charset.equals(StandardCharsets.US_ASCII)
                || charset.name().equalsIgnoreCase(IBM037_CANONICAL_NAME)) {
            return charset;
        }
        throw fieldFailure("process", field,
                new IllegalArgumentException("unsupported fixed-width charset " + charset.name()
                        + "; expected US-ASCII or IBM037"));
    }

    /**
     * Builds the sensitive-aware context expected by the zoned codec.
     *
     * @param field the field whose geometry is carried into diagnostics
     * @return a zoned-codec context containing name, offset, length, kind and sensitivity
     */
    private static ZonedDecimalCodec.FieldContext fieldContext(
            CopybookLayout.FieldSpec field) {
        return new ZonedDecimalCodec.FieldContext(field.name(), field.start(), field.length(),
                field.kind().name(), field.sensitive());
    }

    /**
     * Determines whether a decoded registered field is blank droppable padding.
     *
     * @param spec the record containing the field
     * @param field the candidate padding field
     * @param index the field's declaration index
     * @param value the decoded field value
     * @return {@code true} only for a terminal filler descriptor whose text is blank
     */
    private static boolean isDroppablePadding(CopybookLayout.RecordSpec spec,
            CopybookLayout.FieldSpec field, int index, Object value) {
        return isPaddingDescriptor(spec, field, index)
                && value instanceof String text
                && isBlank(text);
    }

    /**
     * Determines whether a field descriptor is registered copybook padding.
     *
     * @param spec the record containing the field
     * @param field the candidate padding field
     * @param index the field's declaration index
     * @return {@code true} when the exact registered field is a text filler
     */
    private static boolean isPaddingDescriptor(CopybookLayout.RecordSpec spec,
            CopybookLayout.FieldSpec field, int index) {
        // WHY : Assumptions: two source exceptions prevent name-only FILLER handling. A FILLER
        //       REDEFINES item is an overlay alias and therefore has no FieldSpec and adds zero bytes;
        //       a FILLER carrying VALUE is content and is retained whenever it is nonblank or supplied
        //       explicitly. CVTRA07Y.cpy proves the latter with 22 VALUE clauses on all 22 fillers.
        return index >= 0
                && index < spec.fields().size()
                && spec.fields().get(index) == field
                && field.kind() == CopybookLayout.Kind.TEXT
                && isFillerName(field.name())
                && CopybookLayout.names().contains(spec.name())
                && CopybookLayout.layout(spec.name()) == spec;
    }

    /**
     * Recognises both anonymous and explicitly named padding fields.
     *
     * @param name the exact copybook field name
     * @return {@code true} for {@code FILLER} and names ending in {@code -FILLER}
     */
    private static boolean isFillerName(String name) {
        return "FILLER".equals(name) || name.endsWith("-FILLER");
    }

    /**
     * Recognises the 26-byte timestamp leaf fields in the registered layouts.
     *
     * @param field the field descriptor to inspect
     * @return {@code true} when the field is text, 26 bytes wide and ends in {@code -TS}
     */
    private static boolean isTimestampField(CopybookLayout.FieldSpec field) {
        return field.kind() == CopybookLayout.Kind.TEXT
                && field.length() == TIMESTAMP_LENGTH
                && field.name().endsWith("-TS");
    }

    /**
     * Tests whether character content consists only of ordinary spaces.
     *
     * @param text the text to inspect
     * @return {@code true} for an empty string or text containing spaces only
     */
    private static boolean isBlank(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates that an encode map names only fields declared by its record.
     *
     * @param fields the supplied field map
     * @param spec the record layout defining the accepted names
     * @throws FieldCodecException if the map is null or contains a null or unknown key
     */
    private static void validateSuppliedFields(Map<String, Object> fields,
            CopybookLayout.RecordSpec spec) {
        if (fields == null) {
            throw new FieldCodecException("record " + spec.name()
                    + " cannot be encoded from a null field map");
        }
        for (String suppliedName : fields.keySet()) {
            if (suppliedName == null) {
                throw new FieldCodecException("record " + spec.name()
                        + " cannot be encoded from a field map containing a null key");
            }
            boolean declared = false;
            for (CopybookLayout.FieldSpec field : spec.fields()) {
                if (field.name().equals(suppliedName)) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                throw new FieldCodecException("record " + spec.name()
                        + " has no declared field named " + suppliedName);
            }
        }
    }

    /**
     * Requires a physical record to match its declared length before any field read.
     *
     * @param record the complete physical record image
     * @param spec the layout declaring the required length
     * @throws RecordLengthException if the record is null, short or long
     */
    private static void requireRecordLength(byte[] record,
            CopybookLayout.RecordSpec spec) {
        if (record == null || record.length != spec.reclen()) {
            String actual = record == null ? "null" : Integer.toString(record.length);
            throw new RecordLengthException("record " + spec.name() + " expected "
                    + spec.reclen() + " bytes but received " + actual);
        }
    }

    /**
     * Requires one field interval to fit wholly inside a byte array.
     *
     * @param bytes the source or target byte array
     * @param field the field interval to validate
     * @param operation the operation name included in diagnostics
     * @throws FieldCodecException if either argument is null or the interval is outside the array
     */
    private static void requireFieldSpan(byte[] bytes, CopybookLayout.FieldSpec field,
            String operation) {
        if (field == null) {
            throw new FieldCodecException(operation + " requires a non-null field descriptor");
        }
        if (bytes == null) {
            throw fieldFailure(operation, field,
                    new IllegalArgumentException("record byte array is null"));
        }
        long exclusiveEnd = (long) field.start() + field.length();
        if (field.start() < 0 || field.length() < 1 || exclusiveEnd > bytes.length) {
            throw fieldFailure(operation, field,
                    new IllegalArgumentException("field interval exceeds byte array length "
                            + bytes.length));
        }
    }

    /**
     * Proves field, record, and key geometry for one supplied layout.
     *
     * @param spec the layout to validate
     * @throws CopybookLayout.LayoutException if the layout is null, gapped, overlapping,
     *     non-closing, duplicated by field name, or contains an out-of-record field or key
     */
    private static void validateRecordSpec(CopybookLayout.RecordSpec spec) {
        if (spec == null) {
            throw new CopybookLayout.LayoutException("a fixed-width operation requires"
                    + " a non-null record layout");
        }

        // WHY : Alternatives Considered: explicit exceptions are used rather than Java assertion
        //       statements. Assertions disappear unless the virtual machine starts with -ea, so a
        //       layout defect could pass in production even though it failed during a specially
        //       configured test. These checks remain active in every runtime.
        // WHY : Alternatives Considered: reclen is derived by adding each declared field width rather
        //       than reading a copybook banner. CVACT01Y, CVTRA01Y, CVEXPORT and CSUSR01Y demonstrate
        //       four incompatible banner regimes, including no banner, while the sum closes every
        //       registered layout under one rule.
        long cursor = 0;
        List<CopybookLayout.FieldSpec> fields = spec.fields();
        for (int index = 0; index < fields.size(); index++) {
            CopybookLayout.FieldSpec field = fields.get(index);
            long exclusiveEnd = (long) field.start() + field.length();
            if (field.start() < 0 || field.length() < 1 || exclusiveEnd > spec.reclen()) {
                throw new CopybookLayout.LayoutException("record " + spec.name() + " field "
                        + field.describe() + " lies outside reclen " + spec.reclen());
            }
            if (field.start() != cursor) {
                throw new CopybookLayout.LayoutException("record " + spec.name() + " field "
                        + field.describe() + " starts at " + field.start()
                        + " while preceding fields end at " + cursor + ", creating "
                        + (field.start() > cursor ? "a gap" : "an overlap"));
            }
            for (int prior = 0; prior < index; prior++) {
                if (fields.get(prior).name().equals(field.name())) {
                    throw new CopybookLayout.LayoutException("record " + spec.name()
                            + " declares field name " + field.name() + " more than once;"
                            + " a field map cannot represent duplicate keys");
                }
            }
            cursor = exclusiveEnd;
        }
        if (cursor != spec.reclen()) {
            throw new CopybookLayout.LayoutException("record " + spec.name()
                    + " field lengths sum to " + cursor + " but reclen is " + spec.reclen());
        }
        long keyEnd = (long) spec.keyOffset() + spec.keyLength();
        if (spec.keyOffset() < 0 || spec.keyLength() < 1 || keyEnd > spec.reclen()) {
            throw new CopybookLayout.LayoutException("record " + spec.name() + " key interval ["
                    + spec.keyOffset() + "," + keyEnd + ") exceeds reclen " + spec.reclen());
        }
    }

    /**
     * Verifies the complete registry against its independently expected constants.
     *
     * @throws CopybookLayout.LayoutException if a registry population, provenance, geometry,
     *     record length, key length, or oracle-support flag differs from the expected table
     */
    private static void validateRegistry() {
        List<String> names = CopybookLayout.names();
        List<String> baseMasters = CopybookLayout.baseMasterNames();
        List<String> derived = CopybookLayout.derivedNames();
        if (names.size() != EXPECTED_LAYOUT_COUNT
                || baseMasters.size() != EXPECTED_BASE_MASTER_COUNT
                || derived.size() != EXPECTED_DERIVED_COUNT) {
            throw new CopybookLayout.LayoutException("layout registry expected "
                    + EXPECTED_LAYOUT_COUNT + " total entries split into "
                    + EXPECTED_BASE_MASTER_COUNT + " base masters and "
                    + EXPECTED_DERIVED_COUNT + " derived records, but found "
                    + names.size() + ", " + baseMasters.size() + " and " + derived.size());
        }

        // WHY : Assumptions: the registry's eleven entries and the eleven base masters are different
        //       populations. Only eight overlap; SECUSER, TRANCAT and TRANTYPE lack oracle round trips,
        //       while TRNX, REJECT and INTTRAN are derived. Checking provenance and oracle support per
        //       name prevents a count-correct registry that silently substitutes one population.
        for (String name : names) {
            CopybookLayout.RecordSpec spec = CopybookLayout.layout(name);
            validateRecordSpec(spec);

            boolean expectedDerived = isExpectedDerived(name);
            CopybookLayout.Provenance expectedProvenance = expectedDerived
                    ? CopybookLayout.Provenance.DERIVED
                    : CopybookLayout.Provenance.BASE_MASTER;
            if (CopybookLayout.provenanceOf(name) != expectedProvenance
                    || baseMasters.contains(name) == expectedDerived
                    || derived.contains(name) != expectedDerived) {
                throw new CopybookLayout.LayoutException("layout " + name
                        + " is registered under the wrong provenance population");
            }
            if (CopybookLayout.hasOracleRoundTrip(name) != expectedOracleRoundTrip(name)) {
                throw new CopybookLayout.LayoutException("layout " + name
                        + " carries the wrong parity-oracle support flag");
            }

            // WHY : Assumptions: keyLength is checked beside reclen because the reference
            //       _validate_layouts routine at tests/helpers/record_codec.py lines 1684-1730
            //       asserts the pair. A record can decode at the right width while still building an
            //       index over the wrong number of key bytes, so record length alone is insufficient.
            int expectedReclen = expectedReclen(name);
            int expectedKeyLength = expectedKeyLength(name);
            if (spec.reclen() != expectedReclen || spec.keyLength() != expectedKeyLength) {
                throw new CopybookLayout.LayoutException("layout " + name
                        + " expected reclen/keyLength " + expectedReclen + "/"
                        + expectedKeyLength + " but registered " + spec.reclen() + "/"
                        + spec.keyLength());
            }
        }
    }

    /**
     * Identifies the three layouts derived from base-master geometry.
     *
     * @param name the registered layout name
     * @return {@code true} for TRNX, REJECT and INTTRAN
     */
    private static boolean isExpectedDerived(String name) {
        // WHY : Alternatives Considered: REJECT and INTTRAN are classified as derivations rather than
        //       second literal copies of their 350-byte prefixes. CopybookLayout.extendWith and
        //       withFieldFlags keep the shared geometry single-sourced, matching tests/README.md
        //       lines 540-542 and preventing a prefix correction from reaching only one copy.
        return "TRNX".equals(name) || "REJECT".equals(name) || "INTTRAN".equals(name);
    }

    /**
     * Returns whether the Python parity registry contains the named layout.
     *
     * @param name the registered layout name
     * @return {@code false} only for SECUSER, TRANCAT and TRANTYPE
     */
    private static boolean expectedOracleRoundTrip(String name) {
        return !"SECUSER".equals(name) && !"TRANCAT".equals(name)
                && !"TRANTYPE".equals(name);
    }

    /**
     * Returns the independently summed record length expected for a registry name.
     *
     * @param name the registered layout name
     * @return the expected record length in bytes
     * @throws CopybookLayout.LayoutException if the name is outside the closed registry
     */
    private static int expectedReclen(String name) {
        return switch (name) {
            case "SECUSER" -> 80;
            case "ACCOUNT" -> 300;
            case "CARD" -> 150;
            case "CUSTOMER" -> 500;
            case "XREF" -> 50;
            case "DALYTRAN", "TRAN", "TRNX", "INTTRAN" -> 350;
            case "DISGROUP", "TCATBAL" -> 50;
            case "TRANCAT", "TRANTYPE" -> 60;
            case "REJECT" -> 430;
            default -> throw new CopybookLayout.LayoutException(
                    "unexpected layout name in record-length self-check: " + name);
        };
    }

    /**
     * Returns the independently expected primary-key length for a registry name.
     *
     * @param name the registered layout name
     * @return the expected primary-key length in bytes
     * @throws CopybookLayout.LayoutException if the name is outside the closed registry
     */
    private static int expectedKeyLength(String name) {
        return switch (name) {
            case "SECUSER" -> 8;
            case "ACCOUNT" -> 11;
            case "CARD", "XREF", "DALYTRAN", "TRAN", "DISGROUP", "REJECT", "INTTRAN" -> 16;
            case "CUSTOMER" -> 9;
            case "TRANCAT" -> 6;
            case "TRANTYPE" -> 2;
            case "TCATBAL" -> 17;
            case "TRNX" -> 32;
            default -> throw new CopybookLayout.LayoutException(
                    "unexpected layout name in key-length self-check: " + name);
        };
    }

    /**
     * Builds a field failure whose diagnostic respects the sensitive-data boundary.
     *
     * @param operation the attempted field operation
     * @param field the field whose geometry identifies the failure
     * @param cause the underlying validation or codec failure
     * @return a field exception safe to expose to callers
     */
    private static FieldCodecException fieldFailure(String operation,
            CopybookLayout.FieldSpec field, IllegalArgumentException cause) {
        String message = operation + " failed for field " + field.name()
                + " at offset " + field.start() + " with length " + field.length()
                + " and kind " + field.kind();

        // WHY : Assumptions: the reference implementation may echo an offending value, but marked
        //       Java fields deliberately do not. CVCUS01Y.cpy lines 17-18 and CVACT02Y.cpy lines
        //       5-8 include identity, PAN and verification data; retaining the raw cause for those
        //       fields would leak it through a stack trace even if the outer message were redacted.
        if (field.sensitive()) {
            return new FieldCodecException(message + "; field content omitted because it is sensitive");
        }
        String detail = cause.getMessage();
        if (detail == null || detail.isBlank()) {
            return new FieldCodecException(message, cause);
        }
        return new FieldCodecException(message + ": " + detail, cause);
    }

    // WHY : Trade-offs: the two exceptions are nested rather than top-level files. Catch sites use a
    //       qualified name, but the codec package remains at its closed inventory of five production
    //       classes plus package-info.java instead of gaining a seventh compilation unit.

    /**
     * Reports that a physical record image differs from its declared exact length.
     *
     * <p>This unchecked type distinguishes boundary corruption from a malformed
     * individual field while remaining catchable as an ordinary argument error.</p>
     */
    public static final class RecordLengthException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates a record-length failure with expected and actual geometry.
         *
         * @param message the diagnostic naming the record, expected length and actual length
         */
        private RecordLengthException(String message) {
            super(message);
        }
    }

    /**
     * Reports that one field cannot be decoded or encoded under its descriptor.
     *
     * <p>Messages for sensitive fields contain geometry only. Non-sensitive
     * failures may retain their underlying codec cause for diagnosis.</p>
     */
    public static final class FieldCodecException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates a field failure without retaining an underlying cause.
         *
         * @param message the sensitive-safe diagnostic text
         */
        private FieldCodecException(String message) {
            super(message);
        }

        /**
         * Creates a non-sensitive field failure retaining its underlying cause.
         *
         * @param message the diagnostic text
         * @param cause the underlying codec or validation failure
         */
        private FieldCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
