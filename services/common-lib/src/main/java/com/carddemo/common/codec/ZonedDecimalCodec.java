package com.carddemo.common.codec;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.carddemo.common.money.Money;

/**
 * Decodes and encodes zoned-decimal display fields, the sign-overpunch money regime of every
 * base-master record in the reference baseline.
 *
 * <h2>Purpose</h2>
 *
 * <p>A COBOL {@code USAGE DISPLAY} numeric field stores one digit per byte and stores no sign byte
 * and no decimal point. The sign is folded into the low-order digit as an <em>overpunch</em>, and the
 * decimal point is implied by the {@code V} of the picture clause, which occupies no byte at all.
 * This class is the only place in the migrated Java that knows that encoding. It turns a fixed-width
 * span of characters into an exact {@link BigDecimal} carried at the field's declared scale, and it
 * turns such a value back into the identical span. The field-by-field widths and column mappings
 * summarised below are held in full in {@code docs/architecture/data-model-and-schema-mapping.md}.</p>
 *
 * <p>Assumptions: getting this wrong is the highest-risk error in the migration because it is silent.
 * A misread overpunch does not raise, does not fail to parse and does not look wrong; it yields a
 * plausible amount wrong in the cents or wrong by a factor of ten, surfacing much later as a balance
 * that does not reconcile. Every decision here is therefore biased toward failing loudly over
 * guessing.</p>
 *
 * <h2>The overpunch mapping</h2>
 *
 * <p>The trailing byte carries the sign and the low-order digit together. Ten characters encode a
 * positive digit and ten encode a negative one:</p>
 *
 * <pre>
 * positive   {  A  B  C  D  E  F  G  H  I      '{' is +0, 'A' is +1, ... 'I' is +9
 * negative   }  J  K  L  M  N  O  P  Q  R      '}' is -0, 'J' is -1, ... 'R' is -9
 * </pre>
 *
 * <p>Assumptions: the most likely defect is to read that trailing byte as a bare sign marker instead
 * of as a signed digit, so three vectors stand as the regression evidence against it. The middle one
 * is the point: an eleven-character span ending {@code 'G'} is {@code 504.77} and never
 * {@code 50.47}, because {@code 'G'} contributes the digit seven as well as the sign, and misreading
 * it shifts the amount by a factor of ten while still producing a reasonable-looking number.</p>
 *
 * <pre>
 * span            picture      bytes   decodes to   where the span occurs
 * 00000020650{    S9(10)V99       12      2065.00   ACCT-CREDIT-LIMIT, zero-based [24:36] of
 *                                                   tests/fixtures/posting/happy_path/acctdata.txt
 * 0000005047G     S9(09)V99       11       504.77   DALYTRAN-AMT, zero-based [132:143] of a record
 *                                                   in app/data/ASCII/dailytran.txt
 * 0000009190}     S9(09)V99       11      -919.00   the negative form of the same picture
 * </pre>
 *
 * <h2>The sign convention, and the naming trap inside it</h2>
 *
 * <p>Assumptions: this class implements the EBCDIC sign-overpunch convention explicitly and offers no
 * second mode, because {@code tests/README.md} lines 273 and 274 state the constraint the regime rests
 * on -- "{@code -fsign=EBCDIC} is REQUIRED - the default {@code -fsign=ASCII} misreads the
 * zoned-decimal sign overpunch and silently corrupts negative balances" -- and a configurable mode
 * would reintroduce exactly the choice that sentence warns can be made wrongly. The two character-set
 * names in it trap the unwary: {@code tests/helpers/record_codec.py} line 135 calls this mapping "the
 * canonical IBM ASCII trailing-sign mapping" while the setting selecting it is named EBCDIC, and both
 * are correct about different things -- the characters are ASCII-printable, the <em>convention</em>
 * assigning them digit-and-sign meanings is the EBCDIC one. Choosing the setting whose name matches
 * the characters is precisely what corrupts negative balances. (The quotation's em-dash is rendered as
 * an ASCII hyphen-minus because this compilation unit is ASCII-only; no word is altered.)</p>
 *
 * <h2>Signed and unsigned display fields are different contracts</h2>
 *
 * <p>A signed {@code PIC S9(n)V9(d)} field carries an overpunch on its final byte; an unsigned
 * {@code PIC 9(n)} field carries plain digits throughout and has no sign carrier at all. The baseline
 * puts the two side by side, {@code 05 ACCT-ID PIC 9(11).} at line 5 of
 * {@code app/cpy/CVACT01Y.cpy} being unsigned and immediately followed by a one-character status
 * field whose value in the fixture above is the letter {@code 'Y'} -- a byte that is not an overpunch
 * character and must never be read as one.</p>
 *
 * <p>Assumptions: every entry point is therefore <em>told</em> which contract applies rather than
 * working it out, taking the flag from the field descriptor {@code CopybookLayout} declares, so the
 * regime is decided by the copybook and never by the data. A signed field's digit body stops one
 * character short of its width and that final character is resolved through the overpunch tables; an
 * unsigned field's every character is a plain digit and no sign is extracted at any point.</p>
 *
 * <p>Assumptions: inferring the regime from the trailing byte silently corrupts real data in this
 * corpus, which is why it is refused rather than merely discouraged. Five spans of
 * {@code app/data/ASCII/dailytran.txt} -- {@code 3580010001P} and its siblings, and
 * {@code 4260030001O} -- are eleven characters ending in a letter from the negative table, so each
 * looks exactly like a signed {@code S9(09)V99} amount and none is one. Within the 350-byte record of
 * {@code app/cpy/CVTRA06Y.cpy} the first sits at zero-based offset 12 and crosses four field
 * boundaries, ending on the {@code 'P'} of the literal {@code POS TERM}, and the last ends on the
 * {@code 'O'} of {@code OPERATOR}; because {@code 'P'} is the negative seven and {@code 'O'} the
 * negative six, a trailing-byte sniffer reads a source-terminal label as a nine-figure negative
 * amount.</p>
 *
 * <h2>Decode per field, never per record</h2>
 *
 * <p>Assumptions: a span reaches this class already sliced to one field, because a whole record
 * contains bytes that are not text -- overpunch characters, packed nibbles and low values inside
 * padding. The rule is a prohibition rather than a prediction, because what a text decoder does
 * depends on the charset: a single-byte charset maps all 256 values to some character and so
 * mistranslates in silence, while a multi-byte charset substitutes replacement characters whose count
 * need not equal the bytes consumed, shifting every subsequent offset. The parity oracle takes the
 * same position, treating its mainframe-character-set datasets as opaque binary.</p>
 *
 * <p>Trade-offs: the consequence for this class's surface is that it accepts a {@link CharSequence}
 * span and offers no byte-array entry point. A byte-array overload would need a charset, and the only
 * one obtainable from the platform's standard set is a single-byte Latin mapping that is not the
 * mainframe encoding and would quietly succeed on data it had already mistranslated; worse, it invites
 * the whole-record decode above, because a record is the natural unit in which bytes arrive.
 * Transcoding stays upstream where the loader owns it and performs it one field at a time.</p>
 *
 * <h2>The round-trip law, and its single documented exception</h2>
 *
 * <p>For every span this class accepts, encoding what it decoded reproduces the original span byte
 * for byte. That law is not a convenience: the parity oracle compares batch output byte for byte
 * after timestamp normalisation, so a re-encoded record differing in one character is a failed
 * comparison rather than a cosmetic difference.</p>
 *
 * <p>Assumptions: there is exactly one exception and it is a consequence of the target type rather
 * than a defect in either side. COBOL holds the opening-brace overpunch (a positive zero) and the
 * closing-brace overpunch (a negative zero) as two distinct bytes, and
 * {@code tests/helpers/record_codec.py} preserves the distinction, its line 395 reading the sign from
 * that byte. {@link BigDecimal} has no negative zero, so decoding either overpunched zero yields the
 * same value and encoding a zero always emits the opening brace. A closing-brace zero therefore
 * normalises across a round trip, and that is the one span for which the law does not hold <em>on the
 * plain pair of operations</em>; the divergence is registered as D-SIGNED-ZERO-ZONED in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The sign-preserving entry points below
 * carry the byte beside the value and have no departure at all.</p>
 *
 * <p>Every money field in all eleven base-master records is in this regime, and the widths follow
 * mechanically from the picture clause because the implied decimal point occupies no byte:</p>
 *
 * <pre>
 * declaration                      declared at                      bytes   SQL column
 * ACCT-CURR-BAL   PIC S9(10)V99    app/cpy/CVACT01Y.cpy line  7        12   NUMERIC(12,2)
 * TRAN-AMT        PIC S9(09)V99    app/cpy/CVTRA05Y.cpy line 10        11   NUMERIC(11,2)
 * TRAN-CAT-BAL    PIC S9(09)V99    app/cpy/CVTRA01Y.cpy line  9        11   NUMERIC(11,2)
 * DIS-INT-RATE    PIC S9(04)V99    app/cpy/CVTRA02Y.cpy line  9         6   NUMERIC(6,2)
 * ACCT-ID         PIC 9(11)        app/cpy/CVACT01Y.cpy line  5        11   BIGINT
 * CUST-SSN        PIC 9(09)        app/cpy/CVCUS01Y.cpy line 17         9   encrypted, masked
 * </pre>
 *
 * <p>Assumptions: the widths are corroborated by artifacts sharing no code with the copybooks, so a
 * transcription slip in either would be caught -- the sort symbols {@code TRAN-CAT-BAL,18,11,ZD} at
 * {@code app/jcl/PRTCATBL.jcl} line 50, whose {@code ZD} type code names the zoned regime outright,
 * and {@code TRAN-CARD-NUM,263,16,ZD} at {@code app/jcl/TRANREPT.jcl} line 41, whose one-based
 * position is the zero-based offset that summing {@code app/cpy/CVTRA05Y.cpy} produces. Sort symbols
 * are one-based and space-separated index operands are not, so the conversion is always one
 * subtracted from the one-based position.</p>
 *
 * <p>Assumptions: the field names above keep the baseline's spelling, {@code ACCT-EXPIRAION-DATE} and
 * {@code CARD-EXPIRAION-DATE} included. Those two and the merchant-category field of the
 * authorization payloads are the only three baseline names the target spells differently, and the
 * rename happens downstream in the entity and transport types rather than here; per AAP Rule T1 the
 * copybook is normative and no other field is renamed, which is what keeps that list auditable.</p>
 *
 * <h2>Why a second numeric codec exists</h2>
 *
 * <p>Assumptions: searching all eleven base-master copybooks for {@code COMP}, {@code COMP-3} and
 * {@code OCCURS} returns zero matches, so the base masters are entirely in this regime and this class
 * suffices for all of them. Packed decimal is a genuinely different byte layout -- two digits per
 * byte with a sign nibble -- reaching the migration through only three layouts, the export record at
 * {@code app/cpy/CVEXPORT.cpy} whose line 50 takes seven bytes where the zoned form of the same
 * picture takes twelve, and the two authorization segments; those belong to
 * {@code PackedDecimalCodec}. Two regimes, two codecs, and never one class branching between them:
 * the regime is a property of the declared usage and so knowable before the program runs, which makes
 * a wrong choice a compilation-time mistake rather than a run-time branch that silently picks the
 * other decoder.</p>
 *
 * <h2>What this class does not own</h2>
 *
 * <p>Assumptions: money reaches the codec package in seven renderings and this class owns exactly one,
 * so the boundary is stated to stop the class being extended into territory that belongs elsewhere.
 * Rendering 3 is a pair because the authorization wire uses two forms -- the reply mask the reference
 * program emits and the narrower request token its receiver holds -- a split argued in full on
 * {@code CsvAuthCodec}.</p>
 *
 * <pre>
 * #  rendering                    width      owner
 * 1  zoned overpunch              11 or 12 bytes   THIS CLASS
 * 2  packed COMP-3                7, 5 or 3 bytes  PackedDecimalCodec
 * 3a reply mask PIC -zzzzzzzzz9.99 14 characters   CsvAuthCodec
 * 3b request token, receiver-width 13 characters   CsvAuthCodec
 * 4  DFSORT mask EDIT(TTTTTTTTT.TT)  12 characters not this package
 * 5  report mask -ZZZ,ZZZ,ZZZ.ZZ  15 characters    not this class
 * 6  report mask +ZZZ,ZZZ,ZZZ.ZZ  15 characters    not this class
 * </pre>
 *
 * <p>Renderings five and six are declared at line 30 and at lines 54, 60 and 66 of
 * {@code app/cpy/CVTRA07Y.cpy}. What separates them from rendering one is that their sign, group
 * separators and decimal point are all real bytes on the wire, whereas here the sign occupies no byte
 * of its own and the decimal point occupies none at all.</p>
 *
 * <h2>Money never leaves fixed point</h2>
 *
 * <p>Values cross this boundary as {@link BigDecimal} or as {@link Money}, never as an IEEE-754
 * binary value. The prohibition is absolute in the money path and is asserted by the architecture
 * test rather than by review, so a violation fails a build. Trade-offs: those forbidden type names
 * are described rather than spelled, matching the sibling charter, because spelling them would make
 * this file match the audit search for the very tokens the money path must not contain and produce a
 * hit to explain away on every audit. The description is unambiguous, the language having exactly two
 * IEEE-754 binary primitive types and one wrapper each.</p>
 *
 * <p>Assumptions: rounding is deliberately absent from this class -- encoding is lossless or it
 * raises, so a value that will not fit its field is reported rather than quietly reshaped. Where a
 * business rule genuinely decides an amount should be rounded, {@link Money} performs it at scale
 * {@link Money#SCALE} under {@link Money#GENERAL_ROUNDING}, where the decision belongs and where an
 * audit can see it.</p>
 */
public final class ZonedDecimalCodec {

    // Alternatives Considered: the sign-and-digit pair could be resolved with a map literal
    //     keyed on the character, or with a cascade of twenty comparisons. Two index strings are
    //     used instead because the POSITION in the string IS the digit value, which makes encoding
    //     a direct lookup by index and decoding a direct search for an index, from the same two
    //     constants. That symmetry is self-checking: a typo in either table shows up immediately as
    //     an asymmetry between the two directions, whereas two maps would have to be kept in step
    //     by hand and would lose the positional invariant entirely, and a comparison cascade would
    //     express no relationship between a character and its digit at all. The reference
    //     implementation reaches the same conclusion for the same reason and records it at
    //     tests/helpers/record_codec.py lines 130 to 135; its tables are declared at lines 137 and
    //     138 and are reproduced here character for character so that a value decoded here and a
    //     value decoded by the parity oracle cannot disagree.
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    // Assumptions: '0' is the pad character because a display field's unused high-order
    //     positions hold ASCII zeros in every record of the corpus, which is visible directly in
    //     the fixture span quoted in this class's charter: ACCT-CREDIT-LIMIT is stored as
    //     00000020650{ rather than as a space-padded or sign-prefixed 2065. Padding with spaces
    //     would produce a field the baseline programs read as non-numeric.
    private static final char PAD_DIGIT = '0';

    /**
     * Identifies the field a diagnostic message is about, and states whether its content may be
     * quoted in that message.
     *
     * <p>Alternatives Considered: typing {@code kind} as the layout package's storage-regime
     * enumeration rather than as text. Rejected for the same reason as above: it would pull the
     * registry back into this class's public surface for a value that only ever appears inside a
     * message. A caller holding the enumeration passes its name, and a caller that has only a label
     * passes the label.</p>
     *
     * @param name the field name exactly as its copybook declares it, misspellings included; may be
     *     null when the caller has no name to give
     * @param offset the ZERO-based byte offset of the field from the start of its record, carried for
     *     the message alone
     * @param length the declared byte width of the field, carried for the message alone
     * @param kind a label for the field's storage regime, such as the zoned or the unsigned-display
     *     regime; may be null when the caller has none
     * @param sensitive whether the field's content must be kept out of every diagnostic message,
     *     which is true for cardholder identity and payment data such as the national identifier
     *     declared at line 17 of {@code app/cpy/CVCUS01Y.cpy}
     */
    public record FieldContext(String name, int offset, int length, String kind, boolean sensitive) {
    }

    /**
     * Reports a span or a value that violates the fixed-width sign-overpunch contract.
     *
     * <p>Assumptions: every input defect this codec detects is reported as this one type -- a
     * wrong-length span, a non-digit in the digit body, an unrecognised trailing byte, a plain digit
     * where an overpunch is required, a negative value bound for an unsigned field, an over-wide
     * value and an over-scaled one -- so a caller parsing a record needs one catch rather than seven.
     * It extends the unchecked argument-exception type rather than introducing a checked one, because
     * a malformed fixed-width span is a defect in the data or in the supplied geometry and not a
     * condition a correct caller recovers from field by field.</p>
     */
    public static final class ZonedDecimalException extends IllegalArgumentException {

        // Assumptions: the platform expects a serial version identifier on a serialisable type,
        //     and this one inherits serialisability from the exception hierarchy. Pinning the value
        //     explicitly stops the compiler deriving one that changes whenever a member is added,
        //     which is what keeps a serialised instance readable across builds.
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception describing one violated sign-overpunch or geometry constraint.
         *
         * @param message the diagnostic text, which names the constraint and the geometry that
         *     breached it, and which carries field content only when the field was not declared
         *     sensitive
         */
        ZonedDecimalException(String message) {
            super(message);
        }
    }

    /**
     * Prevents instantiation of this stateless codec.
     *
     * <p>Trade-offs: a final holder of static methods was chosen over an injectable instance, because
     * the operations here are pure functions of a span and three integers with no configuration,
     * state or collaborator to substitute, so a seam would add indirection without buying a test not
     * already writable against the static form. Statelessness also makes the class safe to call from
     * any thread, which matters because batch steps decode records in parallel. The cost is that a
     * caller cannot replace the implementation -- the intended outcome for a contract whose purpose
     * is that exactly one implementation exists.</p>
     */
    private ZonedDecimalCodec() {
        // Alternatives Considered: raising from this body was evaluated and rejected as noise,
        //     matching the sibling layout descriptor in this package. The private modifier already
        //     makes the only call site that could reach this constructor impossible to write, and
        //     the class is final so no subclass can reopen the decision, so an exception here would
        //     guard a state the compiler forbids outright.
    }

    /**
     * Returns the byte width a zoned field of the given digit counts occupies.
     *
     * <p>The width is simply the sum of the two digit counts, because the implied decimal point that
     * {@code V} denotes occupies no byte and a signed field carries its sign inside its low-order
     * digit rather than in a byte of its own. That is why {@code PIC S9(09)V99} is eleven bytes and
     * {@code PIC S9(10)V99} is twelve, and it is the arithmetic every caller of this class depends on
     * when it slices a record.</p>
     *
     * @param intDigits the number of digit positions before the implied decimal point, which is the
     *     first repeat count of the picture clause; must not be negative
     * @param decDigits the number of digit positions after the implied decimal point, which is the
     *     repeat count following {@code V}; must not be negative
     * @return the field width in bytes, which is {@code intDigits + decDigits} and is at least one
     * @throws ZonedDecimalException if either count is negative, or if the two sum to zero, because a
     *     field of no digits has nothing to decode and, when signed, nowhere to put its sign
     */
    public static int widthOf(int intDigits, int decDigits) {
        if (intDigits < 0 || decDigits < 0) {
            throw new ZonedDecimalException("zoned field digit counts must not be negative, but"
                    + " intDigits=" + intDigits + " and decDigits=" + decDigits);
        }

        int width = intDigits + decDigits;
        if (width < 1) {
            throw new ZonedDecimalException("zoned field must declare at least one digit position,"
                    + " but intDigits=" + intDigits + " and decDigits=" + decDigits + " sum to zero");
        }
        return width;
    }

    /**
     * Decodes one fixed-width zoned-decimal span into an exact value at the field's declared scale.
     *
     * <p>This is the geometry-only form, for a caller that has no field identity to report. Its
     * diagnostics name the geometry and never the span, which is a deliberate divergence from the
     * reference implementation: that one quotes the offending characters at
     * tests/helpers/record_codec.py lines 262, 268, 274 and 290, and it runs inside a test harness
     * over fixture data, whereas this one runs in a service over cardholder records and its messages
     * reach a log aggregator.</p>
     *
     * <p>Assumptions: this form withholds content BECAUSE it has no field identity -- sensitivity is a
     * property of the field, so a caller naming no field has said nothing about whether the span may
     * be read, and silence is read as refusal rather than as permission. The national identifier at
     * line 17 of {@code app/cpy/CVCUS01Y.cpy} is an unsigned display field and so does reach this
     * class. To have a span quoted, pass
     * {@link #decode(CharSequence, int, int, boolean, FieldContext)} a descriptor declaring the field
     * not sensitive, which is an explicit statement rather than an omission.</p>
     *
     * @param raw the exact field characters, already sliced from the record so that this call decodes
     *     one field and never a whole record; its length must equal {@code intDigits + decDigits},
     *     since the field carries neither a separate sign byte nor a decimal point
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}, so that the final
     *     character is a trailing-sign overpunch rather than a plain digit; this must come from the
     *     field's declared geometry and never from inspecting the characters
     * @return the decoded value, carried at scale exactly {@code decDigits} so that re-encoding
     *     reproduces the original characters
     * @throws ZonedDecimalException if the span is absent, if its length is not the declared width, if
     *     any character of the digit body is not an ASCII digit, if the final character of a signed
     *     field is neither a recognised overpunch nor is rejected as a plain digit, or if either digit
     *     count is invalid
     */
    public static BigDecimal decode(CharSequence raw, int intDigits, int decDigits, boolean signed) {
        return decode(raw, intDigits, decDigits, signed, null);
    }

    /**
     * Decodes one fixed-width zoned-decimal span, naming the field in any diagnostic it raises.
     *
     * <p>Behaviour is identical to {@link #decode(CharSequence, int, int, boolean)} in every respect
     * except the text of a failure message. When {@code field} declares the field sensitive, the
     * message names only that field's name, offset, length and kind, and no span content appears in
     * it at all.</p>
     *
     * <p>Assumptions: the reference implementation quotes the raw value in every message and this
     * method deliberately does not for a sensitive field, a divergence recorded in the migration's
     * traceability matrix. A diagnostic travels further than the record does -- into a log
     * aggregator, an alert and a support ticket -- so a national identifier echoed into one has left
     * the boundary protecting it, and the field's identity and geometry are enough to locate the
     * defect.</p>
     *
     * @param raw the exact field characters, already sliced from the record; its length must equal
     *     {@code intDigits + decDigits}
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}, taken from the field's
     *     declared geometry and never inferred from the characters
     * @param field the field to name in a diagnostic, and whether its content may be quoted there;
     *     null selects the geometry-only diagnostics of the four-argument form
     * @return the decoded value, carried at scale exactly {@code decDigits}
     * @throws ZonedDecimalException if the span is absent, if its length is not the declared width, if
     *     any character of the digit body is not an ASCII digit, if the final character of a signed
     *     field is not a recognised trailing-sign overpunch, or if either digit count is invalid
     */
    public static BigDecimal decode(CharSequence raw, int intDigits, int decDigits, boolean signed,
            FieldContext field) {
        int width = widthOf(intDigits, decDigits);
        if (raw == null) {
            throw failure("zoned field span is absent", field, null);
        }
        if (raw.length() != width) {
            throw failure("zoned field expected " + width + " characters (intDigits=" + intDigits
                    + ", decDigits=" + decDigits + ") but the span holds " + raw.length(), field, raw);
        }

        // Assumptions: a signed field's sign is carried BY its low-order digit rather than
        //     beside it, so the body stops one character short and that last character contributes
        //     both a sign and a digit. Treating the final character as a sign marker alone is the
        //     defect this class exists to prevent: the span 0000005047G is 504.77 and not 50.47,
        //     because 'G' is the positive seven, and the wrong reading is out by a factor of ten
        //     while still looking like a plausible amount.
        int bodyLength = signed ? width - 1 : width;
        for (int index = 0; index < bodyLength; index++) {
            if (!isAsciiDigit(raw.charAt(index))) {
                throw failure((signed ? "signed" : "unsigned")
                        + " zoned field has a non-digit character at zero-based index " + index
                        + " of its digit body", field, raw);
            }
        }

        boolean negative = false;
        int lowOrderDigit = 0;
        if (signed) {
            char last = raw.charAt(width - 1);
            int positiveIndex = POSITIVE_OVERPUNCH.indexOf(last);
            int negativeIndex = NEGATIVE_OVERPUNCH.indexOf(last);
            if (positiveIndex >= 0) {
                lowOrderDigit = positiveIndex;
            } else if (negativeIndex >= 0) {
                negative = true;
                lowOrderDigit = negativeIndex;
            } else if (isAsciiDigit(last)) {
                // Alternatives Considered: tolerating a plain trailing digit as an
                //     un-overpunched positive value. Rejected, because in this corpus a signed
                //     field is uniformly overpunched -- the fixture span quoted in this class's
                //     charter shows three consecutive signed amounts all ending in '{' -- so a bare
                //     digit means the producer wrote the field under the other sign convention,
                //     which is exactly the -fsign=ASCII failure tests/README.md lines 273 and 274
                //     warn about. The sign of such a field is genuinely unknown, and assuming
                //     positive would turn a detectable configuration error into a silent sign flip
                //     on any negative balance. Raising surfaces it at the record that carries it.
                throw failure("signed zoned field ends in a plain digit rather than a trailing-sign"
                        + " overpunch character, so its sign is unknown and is not assumed", field,
                        raw);
            } else {
                throw failure("signed zoned field must end in a trailing-sign overpunch character,"
                        + " one of " + POSITIVE_OVERPUNCH + " for positive or " + NEGATIVE_OVERPUNCH
                        + " for negative", field, raw);
            }
        }

        return assemble(raw, width, decDigits, signed, lowOrderDigit, negative);
    }

    /**
     * One decoded zoned span together with the trailing-sign overpunch its bytes actually carried.
     *
     * <p><b>Purpose.</b> This pair exists for the one case the plain value cannot express: a signed
     * zero. {@link BigDecimal} has no negative zero, so a closing-brace zero and an opening-brace zero
     * decode to the same value and the byte that told them apart is gone. Carrying the sign beside the
     * value keeps it, which is what lets {@link #encodePreservingSign} reproduce the original span byte
     * for byte and lets the round-trip law of this class hold without exception on this path.</p>
     *
     * <p>Trade-offs: a caller needing byte-exact re-encoding must choose this pair and its encoder
     * rather than the plain {@link #decode(CharSequence, int, int, boolean)} and
     * {@link #encode(BigDecimal, int, int, boolean)}, deliberately: the plain pair stays simplest for
     * the majority of callers, who read a value and never re-emit the span, while a loader that must
     * reproduce a dataset byte for byte states that requirement by choosing this one. The plain pair's
     * divergence on a negative zero is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * @param value the decoded value, carried at the field's declared scale, never {@code null}
     * @param negativeSign whether the span's trailing character was a NEGATIVE overpunch; for a
     *     non-zero value this always agrees with the value's own sign, and for a zero it is the only
     *     surviving record of which of the two zero overpunches the span carried
     */
    public record SignedZoned(BigDecimal value, boolean negativeSign) {

        /**
         * Rejects a pair whose stated sign contradicts the value's own sign.
         *
         * <p>Assumptions: the two components may disagree only at zero, because that is the only
         * magnitude for which the value carries no sign of its own. A pair claiming a negative
         * overpunch over a positive magnitude is not a representable span, so it is refused at
         * construction rather than producing a span that would decode back to something else.</p>
         *
         * <p>Successful construction yields this record instance and no separate return value.</p>
         *
         * @param value the decoded value; must not be {@code null}
         * @param negativeSign whether the span carried a negative overpunch
         * @throws ZonedDecimalException if the value is {@code null}, or if it is non-zero and its own
         *     sign disagrees with {@code negativeSign}
         */
        public SignedZoned {
            if (value == null) {
                throw failure("zoned signed-span value is absent", null, null);
            }
            if (value.signum() != 0 && (value.signum() < 0) != negativeSign) {
                throw failure("zoned signed-span sign " + (negativeSign ? "negative" : "positive")
                        + " contradicts the value's own sign, which may happen only at zero", null,
                        value.toPlainString());
            }
        }
    }

    /**
     * Decodes one fixed-width zoned-decimal span, keeping the overpunch its final byte carried.
     *
     * <p>Behaviour is identical to {@link #decode(CharSequence, int, int, boolean)} for the value; the
     * difference is that the sign the span carried is returned alongside it, so a signed zero survives
     * the decode. Pass the result to {@link #encodePreservingSign} to reproduce the original span byte
     * for byte, including a closing-brace zero.</p>
     *
     * @param raw the exact field characters, already sliced from the record; its length must equal
     *     {@code intDigits + decDigits}
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}, taken from the field's
     *     declared geometry and never inferred from the characters
     * @return the decoded value paired with the overpunch class of the span's final character; an
     *     unsigned field always reports a non-negative sign, never {@code null}
     * @throws ZonedDecimalException if the span is absent, if its length is not the declared width, if
     *     any character of the digit body is not an ASCII digit, if the final character of a signed
     *     field is not a recognised trailing-sign overpunch, or if either digit count is invalid
     */
    public static SignedZoned decodePreservingSign(CharSequence raw, int intDigits, int decDigits,
            boolean signed) {
        BigDecimal value = decode(raw, intDigits, decDigits, signed);

        // Assumptions: the sign is read from the span AFTER the full decode above rather than
        //     during it, so every rejection the plain decoder performs still happens first and this
        //     method cannot classify the sign of a span the codec would refuse. The cost is one
        //     extra character inspection per field, which is immaterial beside the decode itself.
        boolean negative = signed
                && NEGATIVE_OVERPUNCH.indexOf(raw.charAt(widthOf(intDigits, decDigits) - 1)) >= 0;
        return new SignedZoned(value, negative);
    }

    /**
     * Encodes a decoded pair back into the exact span it came from, signed zero included.
     *
     * <p>This is the byte-exact inverse of {@link #decodePreservingSign}: for every span that method
     * accepts, encoding what it returned reproduces the original characters with no exception at all.
     * The plain {@link #encode(BigDecimal, int, int, boolean)} normalises a negative zero to the
     * positive-zero overpunch because its argument cannot carry the distinction; this form can, so it
     * does not normalise.</p>
     *
     * <p>Assumptions: the sign component governs only the zero case. For a non-zero value the
     * canonical constructor has already established that the two components agree, so the overpunch
     * this method writes is the one the magnitude implies either way.</p>
     *
     * @param decoded the value and its overpunch class, as returned by {@link #decodePreservingSign};
     *     must not be {@code null}
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}, so that the final
     *     character is written as a trailing-sign overpunch
     * @return exactly {@code intDigits + decDigits} characters, identical to the span the pair was
     *     decoded from
     * @throws ZonedDecimalException if the pair is absent, if the value carries more decimal places
     *     than {@code decDigits} can hold, if its magnitude needs more digit positions than the field
     *     provides, if it is negative and the field is unsigned, or if either digit count is invalid
     */
    public static String encodePreservingSign(SignedZoned decoded, int intDigits, int decDigits,
            boolean signed) {
        if (decoded == null) {
            throw failure("zoned signed-span pair is absent", null, null);
        }
        String canonical = encode(decoded.value(), intDigits, decDigits, signed);
        if (!signed || !decoded.negativeSign() || decoded.value().signum() != 0) {
            return canonical;
        }

        // Assumptions: only the final character is rewritten, and only for a negative zero,
        //     because that is the only position and the only value at which the canonical encoder
        //     and the source span can differ. Rewriting more than the overpunch -- returning the
        //     source span wholesale, for instance -- would let a caller smuggle bytes past every
        //     geometry check the encoder just performed.
        int width = widthOf(intDigits, decDigits);
        int lowOrderDigit = canonical.charAt(width - 1) == POSITIVE_OVERPUNCH.charAt(0)
                ? 0
                : POSITIVE_OVERPUNCH.indexOf(canonical.charAt(width - 1));
        return canonical.substring(0, width - 1) + NEGATIVE_OVERPUNCH.charAt(lowOrderDigit);
    }

    /**
     * Decodes one fixed-width zoned-decimal span into a monetary amount.
     *
     * <p>This is the money convenience over {@link #decode(CharSequence, int, int, boolean)}. It
     * takes no fractional digit count because every money field in the eleven base-master records is
     * declared with two, from {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of
     * {@code app/cpy/CVACT01Y.cpy} to {@code TRAN-CAT-BAL PIC S9(09)V99} at line 9 of
     * {@code app/cpy/CVTRA01Y.cpy}. Fixing the count at {@link Money#SCALE} rather than accepting it
     * is what makes the conversion provably exact: the decoded value already carries that scale, so
     * wrapping it cannot round.</p>
     *
     * <p>Alternatives Considered: accepting a fractional digit count here as the general form does.
     * Rejected because {@link Money} is defined at exactly one scale, so any other count would have to
     * be reduced to it and a reduction is a rounding decision this class does not make. A field with
     * another fractional width -- the disclosure-group rate at line 9 of
     * {@code app/cpy/CVTRA02Y.cpy}, a rate rather than an amount -- goes through the general form and
     * stays a plain decimal.</p>
     *
     * @param raw the exact field characters, already sliced from the record; its length must equal
     *     {@code intDigits + }{@link Money#SCALE}
     * @param intDigits the number of digit positions before the implied decimal point, which is ten
     *     for the twelve-byte form and nine for the eleven-byte form
     * @param signed whether the picture clause carries a leading {@code S}, taken from the field's
     *     declared geometry
     * @return the decoded amount, at exactly {@link Money#SCALE} decimal places
     * @throws ZonedDecimalException if the span is absent, if its length is not the declared width, if
     *     any character of the digit body is not an ASCII digit, or if the final character of a signed
     *     field is not a recognised trailing-sign overpunch
     * @throws ArithmeticException if the decoded magnitude exceeds the domain {@link Money} accepts,
     *     which a field wider than the twelve-byte form can express and that type deliberately
     *     refuses rather than silently carrying an amount no baseline record could hold
     */
    public static Money decodeMoney(CharSequence raw, int intDigits, boolean signed) {
        return Money.of(decode(raw, intDigits, Money.SCALE, signed));
    }

    /**
     * Encodes an exact value into one fixed-width zoned-decimal span.
     *
     * <p>This is the precise inverse of {@link #decode(CharSequence, int, int, boolean)}: for every
     * span that method accepts, encoding what it returned reproduces the original characters. The one
     * documented exception is the negative zero described in this class's charter, which normalises to
     * the positive-zero overpunch because the target type has no negative zero to preserve.</p>
     *
     * <p>Encoding is lossless or it raises. It never truncates a value that is too wide for its field
     * and it never rounds one that carries more decimal places than the field holds.</p>
     *
     * @param value the amount or quantity to encode, interpreted at its own scale, so that a value of
     *     {@code 2065} and a value of {@code 2065.00} both mean two thousand and sixty-five
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}, so that the final
     *     character is written as a trailing-sign overpunch
     * @return exactly {@code intDigits + decDigits} characters: the magnitude zero-padded on the left,
     *     with no decimal point, and with the final character overpunched when the field is signed
     * @throws ZonedDecimalException if the value is absent, if it carries more decimal places than
     *     {@code decDigits} can hold, if its magnitude needs more digit positions than the field
     *     provides, if it is negative and the field is unsigned, or if either digit count is invalid
     */
    public static String encode(BigDecimal value, int intDigits, int decDigits, boolean signed) {
        return encode(value, intDigits, decDigits, signed, null);
    }

    /**
     * Encodes an exact value into one fixed-width zoned-decimal span, naming the field on failure.
     *
     * <p>Behaviour is identical to {@link #encode(BigDecimal, int, int, boolean)} except that a
     * diagnostic names the field, and quotes the value only when that field is present and declares
     * itself not sensitive. Passing null, which is what the four-argument form does, withholds the
     * value: sensitivity is a property of the field, so a caller naming no field has stated nothing
     * about whether the value may be read.</p>
     *
     * @param value the amount or quantity to encode, interpreted at its own scale
     * @param intDigits the number of digit positions before the implied decimal point
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the picture clause carries a leading {@code S}
     * @param field the field to name in a diagnostic, and whether its value may be quoted there; null
     *     selects the diagnostics of the four-argument form
     * @return exactly {@code intDigits + decDigits} characters, zero-padded on the left and
     *     overpunched on the final character when the field is signed
     * @throws ZonedDecimalException if the value is absent, if it carries more decimal places than
     *     {@code decDigits} can hold, if its magnitude needs more digit positions than the field
     *     provides, if it is negative and the field is unsigned, or if either digit count is invalid
     */
    public static String encode(BigDecimal value, int intDigits, int decDigits, boolean signed,
            FieldContext field) {
        int width = widthOf(intDigits, decDigits);
        if (value == null) {
            throw failure("zoned field value is absent", field, null);
        }

        // Trade-offs: silent truncation and silent rounding were both available and both
        //     rejected. This call pads a value that carries fewer decimal places and raises on one
        //     that carries more non-zero places, so 2065 becomes 2065.00 while 2065.001 is refused
        //     rather than becoming 2065.00. The compromise accepted is that a caller holding a
        //     value of greater precision must reduce it deliberately, and that is the point: this
        //     codec's job is faithful representation, and a codec that quietly dropped a tenth of a
        //     cent would hide a decision the audit trail needs to show. Where a business rule
        //     genuinely decides an amount should be rounded, Money performs it at its own scale
        //     under its own documented mode, at the point where the decision belongs. The reference
        //     implementation takes the identical position at tests/helpers/record_codec.py lines 376
        //     to 393, quantising and then verifying the quantised value is unchanged.
        BigDecimal canonical;
        try {
            canonical = value.setScale(decDigits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exceededPrecision) {
            throw failure("value carries more than " + decDigits
                    + " decimal places, which this field cannot hold without dropping precision",
                    field, value.toPlainString());
        }

        // Assumptions: the sign is read with a signum test rather than by asking whether the
        //     value is negative-signed, and the distinction is the negative-zero carve-out. The
        //     target type has no negative zero, so a value built from the text for negative zero
        //     reports signum zero; the test therefore classifies every zero as non-negative and the
        //     encoder emits the canonical positive-zero overpunch for all of them. That is the one
        //     and only span this class does not round-trip byte for byte, and it is stated here as
        //     well as in the charter because this line is where it happens.
        boolean negative = canonical.signum() < 0;
        if (!signed && negative) {
            // Assumptions: this is the reachable half of the unsigned-sign contract. On the
            //     decode side an unsigned field cannot yield a negative value at all, because its
            //     characters are plain digits and carry no sign, so no check is needed there and
            //     none is written; on this side a caller can hand a negative value to an unsigned
            //     field, so the rejection lives here. The asymmetry is recorded so that a later
            //     reader does not read the absent decode-side check as an omission.
            throw failure("unsigned zoned field cannot carry a negative value, because an unsigned"
                    + " display field has no sign carrier", field, canonical.toPlainString());
        }

        // Assumptions: shifting the point right by the fractional width leaves a value of scale
        //     zero, whose plain string is therefore pure digits with no point and no exponent. The
        //     shift is an exact rescaling rather than a division, so no rounding mode is involved
        //     and no quotient is formed. Taking the magnitude first keeps the sign out of the string
        //     entirely, which matters because the sign does not occupy a character of its own.
        String digits = canonical.abs().movePointRight(decDigits).toPlainString();
        if (digits.length() > width) {
            throw failure("value needs " + digits.length() + " digit positions but the field provides "
                    + width + " (intDigits=" + intDigits + ", decDigits=" + decDigits + ")", field,
                    canonical.toPlainString());
        }

        String padded = leftPad(digits, width);
        if (!signed) {
            return padded;
        }

        // Assumptions: the overpunch replaces the final character rather than being appended,
        //     because the sign shares that character with the low-order digit. Appending would
        //     produce a span one character too long and would shift every following field of the
        //     record, which is the silent misalignment this package's charter describes.
        int lowOrderDigit = padded.charAt(width - 1) - PAD_DIGIT;
        String table = negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return padded.substring(0, width - 1) + table.charAt(lowOrderDigit);
    }

    /**
     * Encodes a monetary amount into one fixed-width zoned-decimal span.
     *
     * <p>This is the money convenience over {@link #encode(BigDecimal, int, int, boolean)}, and the
     * counterpart of {@link #decodeMoney(CharSequence, int, boolean)}. It takes no fractional digit
     * count for the same reason: an amount of this type is always at {@link Money#SCALE}, which is the
     * fractional width every money field in the base-master records declares.</p>
     *
     * @param value the amount to encode; must not be null
     * @param intDigits the number of digit positions before the implied decimal point, which is ten
     *     for the twelve-byte form and nine for the eleven-byte form
     * @param signed whether the picture clause carries a leading {@code S}
     * @return exactly {@code intDigits + }{@link Money#SCALE} characters, zero-padded on the left and
     *     overpunched on the final character when the field is signed
     * @throws ZonedDecimalException if the amount is absent, if its magnitude needs more digit
     *     positions than the field provides, or if it is negative and the field is unsigned
     */
    public static String encodeMoney(Money value, int intDigits, boolean signed) {
        if (value == null) {
            throw failure("zoned money value is absent", null, null);
        }
        return encode(value.amount(), intDigits, Money.SCALE, signed);
    }

    /**
     * Assembles the validated characters of a span into an exact value at the declared scale.
     *
     * @param raw the span whose digit body has already been validated
     * @param width the declared field width, equal to the sum of the two digit counts
     * @param decDigits the number of digit positions after the implied decimal point
     * @param signed whether the final character of the span is an overpunch rather than a plain digit
     * @param lowOrderDigit the digit the overpunched character contributes, ignored when the field is
     *     unsigned
     * @param negative whether the overpunched character selected the negative table
     * @return the assembled value, at scale exactly {@code decDigits}
     */
    private static BigDecimal assemble(CharSequence raw, int width, int decDigits, boolean signed,
            int lowOrderDigit, boolean negative) {
        // Assumptions: the replacement is what separates the sign from the digit, and it has to
        //     happen before the value is assembled because the overpunched character is not a digit
        //     the numeric parser would accept. An unsigned field needs no replacement at all, since
        //     every one of its characters is already a plain digit.
        String digits = signed
                ? raw.subSequence(0, width - 1).toString() + (char) (PAD_DIGIT + lowOrderDigit)
                : raw.toString();

        int pointIndex = width - decDigits;
        String integerDigits = digits.substring(0, pointIndex);
        if (integerDigits.isEmpty()) {
            // Assumptions: a picture may declare no integer positions at all, as PIC SV99 does,
            //     in which case every character of the span is fractional and the integer part is
            //     empty. The numeric parser rejects a string that opens with a decimal point, so a
            //     zero is supplied. The reference implementation substitutes the same zero for the
            //     same reason at tests/helpers/record_codec.py lines 300 and 301.
            integerDigits = "0";
        }

        // Trade-offs: assembling a string costs one allocation and is marginally slower than
        //     accumulating the digits into an integer and applying the scale afterwards. The
        //     exchange is worth it twice over. The string form is context-independent and exact --
        //     there is no accumulator to overflow on a wide field, and no ambient precision or
        //     rounding setting that could alter the result -- and it is the form that makes the
        //     returned scale exactly the declared fractional width, because the parser takes the
        //     scale from the characters it was given. The reference implementation builds its value
        //     from an explicit string for the same reason, at tests/helpers/record_codec.py lines
        //     294 to 303.
        StringBuilder text = new StringBuilder(width + 2);
        if (negative) {
            text.append('-');
        }
        text.append(integerDigits);
        if (decDigits > 0) {
            text.append('.').append(digits, pointIndex, width);
        }

        // Assumptions: the value is returned exactly as parsed, with no normalisation of its
        //     scale, and byte-reproducible round-tripping depends on that. Stripping trailing zeros
        //     would make the amounts 2065.00 and 2065 compare equal while encoding to different
        //     characters, so the parity oracle's byte-for-byte comparison would fail on a record
        //     that is numerically identical to its golden master.
        return new BigDecimal(text.toString());
    }

    /**
     * Pads a magnitude on the left with zeros until it fills the declared field width.
     *
     * @param digits the magnitude as a bare digit string, no wider than {@code width}
     * @param width the declared field width to fill
     * @return the magnitude occupying exactly {@code width} characters
     */
    private static String leftPad(String digits, int width) {
        int missing = width - digits.length();
        if (missing == 0) {
            return digits;
        }
        // Assumptions: the padding goes on the left because a display field is right-aligned
        //     within its declared width, which the corpus shows directly: ACCT-CREDIT-LIMIT holds
        //     00000020650{ rather than 20650{ followed by spaces. Padding on the right, or with
        //     spaces on either side, produces a field the baseline programs read as non-numeric.
        return String.valueOf(PAD_DIGIT).repeat(missing) + digits;
    }

    /**
     * Reports whether a character is one of the ten ASCII digits.
     *
     * @param candidate the character to classify
     * @return true when the character is in the inclusive range zero to nine
     */
    private static boolean isAsciiDigit(char candidate) {
        // Alternatives Considered: the platform's own digit test was evaluated and rejected. It
        //     answers true for every decimal digit in every script the character set defines, so it
        //     accepts characters a fixed-width record produced on the reference platform can never
        //     contain -- and the arithmetic that follows a digit test here subtracts the ASCII zero,
        //     which for one of those characters yields a value in the hundreds or thousands rather
        //     than nought to nine. An explicit range test accepts exactly the ten characters the
        //     contract permits, so a foreign digit is reported as the malformed input it is instead
        //     of decoding to a nonsensical amount.
        return candidate >= PAD_DIGIT && candidate <= '9';
    }

    /**
     * Builds the exception for one violated contract, quoting content only when that is permitted.
     *
     * <p>Assumptions: content reaches this method through the {@code content} parameter alone, and
     * never inside {@code problem}. Every caller observes that split, so {@code problem} carries only
     * geometry and the codec's own constants while {@code content} carries the span or the value. That
     * is what makes the sensitive-field suppression complete rather than partial: there is exactly one
     * channel to gate, and gating it here means no caller can leak content by forgetting to.</p>
     *
     * @param problem the constraint that was breached, stated in terms of geometry and of this
     *     class's own constants, and never containing field content
     * @param field the field to name, or null when the caller supplied no field identity, in which
     *     case nothing is known about the span's sensitivity and content is withheld
     * @param content the span or value that breached the constraint, or null when there is none to
     *     quote; it is omitted unless {@code field} is present and declares the field not sensitive
     * @return the exception to raise, which the caller throws so that the raise site stays visible
     */
    private static ZonedDecimalException failure(String problem, FieldContext field,
            CharSequence content) {
        StringBuilder message = new StringBuilder(problem);
        if (field != null) {
            message.append(" [field ").append(describe(field)).append(']');
        }

        // Assumptions: an ABSENT field context withholds the content, because absent means nothing
        //     is known about the span and not that the caller declared nothing sensitive. The four
        //     convenience overloads above all pass null, so reading absence as permission would quote
        //     whatever every default call was decoding -- including a national identifier or a primary
        //     account number sliced out of a reference record. Content is therefore quoted only where
        //     a context is present AND declares the field not sensitive, which is an explicit
        //     statement by the caller that the span may be read.
        if (content != null && field != null && !field.sensitive()) {
            message.append(": '").append(content).append('\'');
        }
        return new ZonedDecimalException(message.toString());
    }

    /**
     * Describes a field by the four attributes a diagnostic may always carry.
     *
     * @param field the field to describe; its name and kind may each be null
     * @return the field's name, zero-based offset, length and kind, in a form safe to log for a
     *     sensitive field because it contains no content
     */
    private static String describe(FieldContext field) {
        // Assumptions: a null name or kind is rendered as a placeholder rather than raising,
        //     because this method runs while a diagnostic about some other defect is being built.
        //     Raising here would replace a precise message about the real problem with an unrelated
        //     failure at exactly the moment the real message was needed.
        return (field.name() == null ? "(unnamed)" : field.name())
                + " at zero-based offset " + field.offset()
                + ", length " + field.length()
                + ", kind " + (field.kind() == null ? "(unstated)" : field.kind());
    }
}
