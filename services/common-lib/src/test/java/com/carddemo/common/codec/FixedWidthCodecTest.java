package com.carddemo.common.codec;

import com.carddemo.common.codec.CopybookLayout.FieldSpec;
import com.carddemo.common.codec.CopybookLayout.Kind;
import com.carddemo.common.codec.CopybookLayout.LayoutException;
import com.carddemo.common.codec.CopybookLayout.Provenance;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec.FieldCodecException;
import com.carddemo.common.codec.FixedWidthCodec.RecordLengthException;
import com.carddemo.common.codec.PackedDecimalCodec.PackedDecimalException;
import com.carddemo.common.codec.ZonedDecimalCodec.ZonedDecimalException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FixedWidthCodec} and {@link CopybookLayout} to the exact byte geometry, record lengths,
 * key intervals, dispatch decisions, sentinels and strict failures the immutable reference artifacts
 * declare.
 *
 * <p>This class is the composition-level suite for fixed-width records and it is also the single home
 * of every layout assertion in this package. The two are deliberately not split. A layout descriptor
 * and the codec that slices bytes with it are one contract: a wrong offset is only observable when a
 * record is actually sliced, and a decode is only auditable against the copybook the descriptor
 * transcribes. Splitting them would put the offset in one file and the byte that proves it in
 * another.</p>
 *
 * <h2>Assumptions: every expectation is an inline literal, never a file read</h2>
 *
 * <p>Each record image below is written out in the test that uses it, together with the artifact path
 * and line the value comes from. Nothing here opens a file, reads a clock, resolves an environment
 * variable or reaches a network, so the whole class runs on a machine with no COBOL compiler, no
 * database and no emulator. Alternatives Considered: copying the committed fixtures into a test
 * resource tree and loading them at run time. That was rejected on two grounds. It would duplicate
 * reference data that is explicitly read-only, so a later edit to one copy would leave two disagreeing
 * versions of the same record with nothing to reconcile them; and a loaded record carries no statement
 * of which field occupies which byte, so a reviewer checking an offset would have to count characters
 * in an opaque line instead of reading a labelled concatenation.</p>
 *
 * <p>Assumptions: the fixture rows reused here are reused as VALUES and not as files, so the citation
 * is the contract. The account, card, customer, cross-reference and transaction images come from
 * {@code tests/fixtures/export/happy_path/}; the daily-transaction and category-balance images from
 * {@code tests/fixtures/posting/happy_path/}; the disclosure-group image from
 * {@code tests/fixtures/interest/default_fallback/discgrp.txt}; the transaction-category and
 * transaction-type images directly from {@code app/data/ASCII/}; and the user-security image is
 * constructed field by field from {@code app/cpy/CSUSR01Y.cpy} because no committed ASCII row for it
 * exists.</p>
 *
 * <h2>Assumptions: a record length is derived from declared spans, never from a banner</h2>
 *
 * <p>Every length asserted below is proven twice over: once as the sum of the declared field widths
 * and once against the layout's own declared length. The banner comment a copybook carries is never
 * used as a source. Assumptions: the corpus has at least four incompatible banner regimes --
 * {@code app/cpy/CVACT01Y.cpy} line 2 writes {@code RECLN 300} with no equals sign,
 * {@code app/cpy/CVTRA01Y.cpy} line 2 writes {@code RECLN = 50} with one,
 * {@code app/cpy/CVEXPORT.cpy} line 5 writes {@code Total Record Length: 500 bytes} in prose, and
 * {@code app/cpy/CSUSR01Y.cpy} carries no banner at all -- so a banner is not a machine-readable
 * declaration and the sum is. Summing also catches the failure a banner cannot: a field declared one
 * byte too narrow still decodes to digits in every field after it, and only a sum that misses the
 * declared length reports it.</p>
 *
 * <h2>Assumptions: registry keys are logical names, not dataset spellings</h2>
 *
 * <p>The registry is keyed on {@code SECUSER}, {@code XREF}, {@code DISGROUP} and {@code TRANCAT},
 * while the datasets those records live in are spelled {@code USRSEC}, {@code CARDXREF},
 * {@code DISCGRP} and {@code TRANCATG}. The two vocabularies are near enough to be interchanged by
 * accident and far enough apart that an interchange resolves to nothing, so each dataset spelling is
 * asserted below to fail closed rather than to alias its logical key.</p>
 *
 * <h2>Trade-offs: three base masters have no second implementation to be checked against</h2>
 *
 * <p>The parity oracle's own codec at {@code tests/helpers/record_codec.py} registers ELEVEN layouts,
 * at its lines 1349 to 1360, and the migration has ELEVEN base masters, but the two elevens are not
 * the same set. Only EIGHT names appear in both. The three base masters the oracle omits are
 * {@code SECUSER}, {@code TRANCAT} and {@code TRANTYPE}; the oracle makes its count up with
 * {@code TRNX}, {@code REJECT} and {@code INTTRAN}, which are derived records rather than base masters.
 * For those three omitted records the geometry asserted here rests on the copybook declaration and the
 * dataset definition alone, with no independently written implementation of the same layout to compare
 * a decode against. The trade-off is accepted rather than worked around: writing a second decoder in
 * this file to serve as the missing oracle would reproduce, in the test, the very offsets under test,
 * so one mistake in each would agree and the test would pass. The compensating measure is that those
 * three records are verified directly from their copybook declarations field by field, and that the
 * production registry records the absence of an oracle explicitly through
 * {@link CopybookLayout#hasOracleRoundTrip(String)} so the gap cannot be mistaken for coverage.</p>
 *
 * <h2>Assumptions: there is no universal filler byte</h2>
 *
 * <p>Trailing padding is space-filled in the account, card, customer, cross-reference,
 * daily-transaction and transaction records, and filled with the CHARACTER zero in the
 * disclosure-group, transaction-category, transaction-type and category-balance records. Both regimes
 * are asserted below against the committed rows. A codec that assumed one fill byte would either drop
 * content that is not padding or emit padding the reference programs read as non-numeric, and neither
 * failure is visible in a length check.</p>
 *
 * <h2>Assumptions: low values and spaces are two different values</h2>
 *
 * <p>{@code app/cpy/CVCRD01Y.cpy} declares two adjacent {@code PIC X(75)} message fields, at its lines
 * 28 and 29, and attaches {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} to the second at line 30.
 * The absent state of that field is therefore a span of low values, while an empty message in the first
 * field is a span of blanks. The two are distinct bytes and must stay distinct values; a codec that
 * trimmed both to the same empty result would report an unset field and a cleared field identically.
 * The adjacent numeric redefinitions in the same copybook, at its lines 36, 39 and 42, ALIAS the
 * character fields they redefine and add no storage, so they are not counted a second time in any span
 * asserted here.</p>
 *
 * <h2>Assumptions: the reference sort symbols are ONE-based and inclusive</h2>
 *
 * <p>Two dataset control decks state transaction and category-balance offsets independently of the
 * copybooks, and both use one-based inclusive positions where {@link CopybookLayout.FieldSpec#start()}
 * is zero-based and {@link CopybookLayout.FieldSpec#end()} is exclusive. The conversion is always to
 * subtract one from the declared position. {@code app/jcl/TRANREPT.jcl} line 41 declares
 * {@code TRAN-CARD-NUM,263,16,ZD} and line 42 declares {@code TRAN-PROC-DT,305,10,CH}, which are
 * zero-based 262 and 304. {@code app/jcl/PRTCATBL.jcl} lines 47 to 50 declare positions 1, 12, 14 and
 * 18, which are zero-based 0, 11, 13 and 17. Assumptions: the name {@code TRAN-PROC-DT} in the sort
 * deck is not a copybook field and does not contradict one. It names the leading TEN-character date
 * window of the {@code PIC X(26)} processing timestamp the copybook declares, which is what lets a
 * character comparison against a date literal work at all, and the alternate index at
 * {@code app/jcl/TRANIDX.jcl} line 27 reads the same offset with the full width by declaring
 * {@code KEYS(26 304)}. That operand pair is space-separated in that file and comma-separated in
 * others, so it is quoted here exactly as written rather than normalised.</p>
 *
 * <h2>Assumptions: group items flatten, groups keep their geometry in the key</h2>
 *
 * <p>Four registered records declare a grouped key -- {@code TRAN-CAT-KEY} in
 * {@code app/cpy/CVTRA01Y.cpy} line 5 and again in {@code app/cpy/CVTRA04Y.cpy} line 5,
 * {@code DIS-GROUP-KEY} in {@code app/cpy/CVTRA02Y.cpy} line 5, and {@code TRNX-KEY} in
 * {@code app/cpy/COSTM01.CPY} line 21 -- and none of the four appears as a field. Only their
 * elementary leaves do, and the composite width survives in {@link CopybookLayout.RecordSpec#keyLength()}.
 * The two {@code TRAN-CAT-KEY} declarations are the reason there is no registry-wide field lookup at
 * all: the same textual name is 17 bytes in one copybook and 6 in the other, so a global map would have
 * to pick one and would then be wrong for every reader of the other. A redefining overlay is treated
 * the same way in the opposite direction: it aliases storage already declared and advances the running
 * offset by zero, which is asserted below against {@code app/cbl/COCRDLIC.cbl} line 254 and
 * {@code app/cbl/CBTRN02C.cbl} line 160.</p>
 *
 * <h2>Assumptions: dispatch happens on the declared kind before any character decoding</h2>
 *
 * <p>{@code app/cpy/CVEXPORT.cpy} puts a packed, a zoned display and a binary field in ONE byte image,
 * at its lines 50, 51 and 57, all three carrying the identical picture {@code PIC S9(10)V99} at three
 * different physical widths of 7, 12 and 8 bytes. A record decoded as text first and interpreted
 * afterwards would corrupt two of those three while leaving the overall length plausible. The proof
 * asserted below is direct: a valid binary span whose bytes include values no single-byte character set
 * maps reversibly decodes to an exact value through its own kind, and the SAME span declared as text
 * is refused. Byte order and scale for those two regimes are owned by the sibling packed and zoned
 * suites; what this class proves is the delegation and the composition around it.</p>
 *
 * <h2>Trade-offs: what this class deliberately does not do</h2>
 *
 * <p>It does not implement a copybook parser, and no descriptor built here may encode a simplification
 * the reference sources contradict. Where a test decision depends on such a source fact -- a
 * redefinition that aliases rather than extends, a named filler, a colliding field name, an all-blank
 * timestamp that is real data, an internal decimal point inside a picture clause that also ends in a
 * period -- the fact is stated in an adjacent comment at the point of use rather than generalised into
 * a routine. It also does not format timestamps: {@link CopybookLayout.FieldSpec#normalizeTs()} marks a
 * field for the parity comparison and nothing more, and the module's timestamp formatter owns rendering.
 * Nor does it mask values: {@link CopybookLayout.FieldSpec#sensitive()} marks a field so that
 * diagnostics stay content-free, and the service mappers own domain masking. Both flags are asserted
 * here as markers, and the diagnostics they govern are asserted to be redacted.</p>
 *
 * <p>Assumptions: every quantity in this file is exact fixed point, carried in {@link BigDecimal} at
 * the scale its picture declares. Neither of the language's two IEEE-754 binary primitive types, nor
 * either of their wrapper types, appears anywhere in this file; the names are described rather than
 * spelled so that this file cannot match a search for the tokens the money path must not contain.</p>
 *
 * <p>Parameters, return values and exceptions at type level: declared inapplicable. A class declaration
 * accepts no parameter, yields no value and raises nothing, so this block carries no parameter, return
 * or exception at-clause. Every member below carries its own.</p>
 */
class FixedWidthCodecTest {

    // WHY : Assumptions: the reference datasets are single-byte character data and the codec admits
    //       only US-ASCII and IBM code page 037. Every image in this file is built as US-ASCII because
    //       the committed ASCII rows it transcribes are, and because the code-page-037 path is a
    //       per-field decode concern the sibling suites and the loader own rather than a geometry one.
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * Right-pads character content with blanks to the width its picture clause declares.
     *
     * <p>Assumptions: a {@code PIC X(n)} field stores its content left-aligned and blank-filled to the
     * declared width, which is what every committed row shows -- for instance the 50-byte embossed name
     * at {@code app/cpy/CVACT02Y.cpy} line 8 holds {@code Carter Veum} followed by 39 blanks.
     * Alternatives Considered: writing each padded literal out in full. Rejected because a 100-byte
     * description written as one literal states its own width only by being counted, whereas
     * {@code padded("Purchase at Abshire-Lowe", 100)} states the declared width where a reviewer can
     * check it against the copybook line without counting anything.</p>
     *
     * @param content the character content as the reference row holds it, without its padding
     * @param width the declared character count of the field, which must be at least the content
     *     length
     * @return the content followed by exactly enough blanks to reach {@code width}
     * @throws IllegalArgumentException if {@code width} is smaller than the content length, which
     *     would mean the transcribed content does not fit the field it is cited against
     */
    private static String padded(String content, int width) {
        if (width < content.length()) {
            throw new IllegalArgumentException("content of " + content.length()
                    + " characters does not fit a declared width of " + width);
        }
        return content + " ".repeat(width - content.length());
    }

    /**
     * Builds a byte array from unsigned byte values written as hexadecimal integer literals.
     *
     * <p>Assumptions: a packed or binary span is a nibble pattern rather than characters, so it is
     * stated here as the bytes it is. Trade-offs: taking the values as integers means each one is
     * narrowed here rather than at the call site. That is accepted so a call site can write
     * {@code 0xFF} as it appears in a hexadecimal dump instead of a signed narrowing expression, which
     * is what makes the vector checkable against the two's complement it claims to be.</p>
     *
     * @param unsignedValues the byte values in record order, each in the range 0 through 255
     * @return a newly allocated array holding one byte per supplied value, in the same order
     * @throws IllegalArgumentException if any supplied value lies outside the unsigned byte range,
     *     which would mean the vector was transcribed wrongly rather than narrowed silently
     */
    private static byte[] bytesOf(int... unsignedValues) {
        byte[] result = new byte[unsignedValues.length];
        for (int index = 0; index < unsignedValues.length; index++) {
            if (unsignedValues[index] < 0 || unsignedValues[index] > 0xFF) {
                throw new IllegalArgumentException("value at index " + index
                        + " is outside the unsigned byte range: " + unsignedValues[index]);
            }
            result[index] = (byte) unsignedValues[index];
        }
        return result;
    }

    /**
     * Returns the index of the first differing byte in two arrays, or minus one when they are equal.
     *
     * <p>Assumptions: this exists so that a byte-identity failure can name WHERE the two images diverge
     * without naming WHAT either image holds. The records compared in this file include primary account
     * numbers and, in the user-security case, the reference password slot, and an assertion message
     * becomes console output and a stored report.</p>
     *
     * @param expected the image the reference artifact declares
     * @param actual the image produced by encoding what was decoded
     * @return the zero-based index of the first byte that differs, the shorter length when one array is
     *     a prefix of the other, or minus one when the two arrays are equal
     */
    private static int firstDifference(byte[] expected, byte[] actual) {
        int shared = Math.min(expected.length, actual.length);
        for (int index = 0; index < shared; index++) {
            if (expected[index] != actual[index]) {
                return index;
            }
        }
        return expected.length == actual.length ? -1 : shared;
    }

    /**
     * Decodes one registered record image, re-encodes it and proves the two byte images are identical.
     *
     * <p>Assumptions: byte identity across a decode followed by an encode is the property the parity
     * comparison depends on, because the batch outputs are compared byte for byte after timestamp
     * normalisation. A re-encoded record differing in one character is a failed comparison rather than a
     * cosmetic difference, so this harness compares the whole image rather than sampling fields.</p>
     *
     * <p>Trade-offs: the failure message names the layout, the declared length and the offset of the
     * first divergence, and never the bytes on either side. That costs a reader one lookup into the
     * cited artifact to see what the offending byte should have been, and it buys the guarantee that no
     * record content -- no account number, no card number, no reference password slot -- can reach an
     * assertion message from any of the eleven cases that use this harness.</p>
     *
     * @param layoutName the registered logical record name, which must be one of the names
     *     {@link CopybookLayout#names()} returns
     * @param image the complete record image exactly as the cited reference artifact holds it
     * @return the decoded field map, in declaration order, so a caller can assert individual values
     * @throws CopybookLayout.LayoutException if {@code layoutName} is not registered
     * @throws FixedWidthCodec.RecordLengthException if {@code image} is not the declared record length
     * @throws FixedWidthCodec.FieldCodecException if any field cannot be decoded or re-encoded
     */
    private static Map<String, Object> decodeAndProveByteIdentity(String layoutName, String image) {
        RecordSpec spec = CopybookLayout.layout(layoutName);
        byte[] declared = image.getBytes(ASCII);
        assertEquals(spec.reclen(), declared.length,
                () -> "transcribed image for " + layoutName + " is not the declared record length");

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(declared, spec);
        byte[] reEncoded = FixedWidthCodec.encodeRecord(decoded, spec);

        assertEquals(spec.reclen(), reEncoded.length,
                () -> "re-encoded " + layoutName + " is not the declared record length");
        int divergence = firstDifference(declared, reEncoded);
        assertEquals(-1, divergence,
                () -> "re-encoded " + layoutName + " diverges from the reference image at zero-based"
                        + " offset " + divergence);
        return decoded;
    }

    /**
     * Returns the 300-byte account image the export happy-path fixture holds on its first row.
     *
     * <p>Assumptions: the value is transcribed from
     * {@code tests/fixtures/export/happy_path/acctdata.txt} row 1 and laid out against
     * {@code app/cpy/CVACT01Y.cpy} lines 5 to 17. Three properties of that row are load-bearing for the
     * tests that use it. Its five money fields all carry the opening-brace overpunch, which is the
     * POSITIVE zero-digit form, so a re-encode reproduces them exactly. Its group identifier at
     * copybook line 16 is ten blanks, which is a real blank value in a named field rather than padding,
     * so it must survive the decode. And its trailing 178-byte filler at copybook line 17 is
     * space-filled, which is the fill regime of the character-padded records.</p>
     *
     * @return the exact 300-byte account row as a character image
     */
    private static String accountImage() {
        return "00000000002"
                + "Y"
                + "00000001580{"
                + "00000061300{"
                + "00000054480{"
                + "2013-06-19"
                + "2024-08-11"
                + "2024-08-11"
                + "00000000000{"
                + "00000000000{"
                + "A000000000"
                + " ".repeat(10)
                + " ".repeat(178);
    }

    /**
     * Returns the 350-byte daily-transaction image the posting happy-path fixture holds.
     *
     * <p>Assumptions: the value is transcribed from
     * {@code tests/fixtures/posting/happy_path/dailytran.txt} row 1 and laid out against
     * {@code app/cpy/CVTRA06Y.cpy} lines 5 to 18. Its amount span {@code 0000005047G} ends in the
     * positive overpunch for the digit seven, and its processing timestamp at copybook line 17 is
     * TWENTY-SIX BLANKS, which is real data rather than a defect: a daily transaction has not been
     * posted yet, so no wall-clock stamp has been written into it.</p>
     *
     * <p>Assumptions: the card number in this image is published synthetic seed data, not a
     * credential. It is committed upstream in this repository's own reference extracts --
     * {@code app/data/ASCII/carddata.txt}, {@code app/data/ASCII/dailytran.txt} and
     * {@code app/data/ASCII/cardxref.txt} -- and is transcribed here so the decoded value can be
     * asserted against the same bytes the parity fixtures hold. Substituting a different number
     * would break that correspondence without removing any exposure, because there is none to
     * remove.</p>
     *
     * @return the exact 350-byte daily-transaction row as a character image
     */
    private static String dailyTransactionImage() {
        return "0000000000683580"
                + "01"
                + "0001"
                + padded("POS TERM", 10)
                + padded("Purchase at Abshire-Lowe", 100)
                + "0000005047G"
                + "800000000"
                + padded("Abshire-Lowe", 50)
                + padded("North Enoshaven", 50)
                + padded("72112", 10)
                + "4859452612877065"
                + "2022-06-10 19:27:53.000000"
                + " ".repeat(26)
                + " ".repeat(20);
    }

    /**
     * Returns the 350-byte posted-transaction image the export happy-path fixture holds on its third row.
     *
     * <p>Assumptions: the value is transcribed from
     * {@code tests/fixtures/export/happy_path/trandata.txt} row 3 and laid out against
     * {@code app/cpy/CVTRA05Y.cpy} lines 5 to 18. Row 3 is chosen over rows 1 and 2 for one reason: its
     * merchant postal code is {@code 78487-7965}, an ALPHANUMERIC nine-digit code with an embedded
     * hyphen in a {@code PIC X(10)} field. Rows 1 and 2 hold five digits and five blanks in the same
     * field, so neither would show that the field is character data rather than a number that happens to
     * be stored as text.</p>
     *
     * @return the exact 350-byte posted-transaction row as a character image
     */
    private static String transactionImage() {
        return "0000000006292564"
                + "01"
                + "0001"
                + padded("POS TERM", 10)
                + padded("Purchase at Ernser, Roob and Gleason", 100)
                + "0000000678H"
                + "800000000"
                + padded("Ernser, Roob and Gleason", 50)
                + padded("North Makenziemouth", 50)
                + "78487-7965"
                + "6009619150674526"
                + "2022-06-10 19:27:53.000000"
                + " ".repeat(26)
                + " ".repeat(20);
    }

    /**
     * Builds the descriptor for the account branch of the reference export record.
     *
     * <p>Assumptions: the branch is transcribed from {@code app/cpy/CVEXPORT.cpy} lines 47 to 60 and is
     * the only place in the corpus where all five storage regimes appear in one contiguous record. Its
     * widths are fixed by the declared USAGE and not by the picture clause: the identical picture
     * {@code PIC S9(10)V99} occupies 7 bytes at line 50 under {@code COMP-3}, 12 bytes at line 51 with
     * no usage clause at all, and 8 bytes at line 57 under {@code COMP}. Recognising those usage tokens
     * has one hazard worth stating where the widths are declared: the spelling {@code COMP-3} CONTAINS
     * the spelling {@code COMP}, so any recognition matching the shorter token first would classify the
     * two packed fields as binary, turning 7 bytes into 8 and displacing every field after them. The
     * longest token must be matched first.</p>
     *
     * <p>Assumptions: the enclosing export record is a DISCRIMINATED UNION and not a sequence. Its
     * 460-byte payload at {@code app/cpy/CVEXPORT.cpy} line 19 is redefined five times, at lines 24, 47,
     * 65, 84 and 93, and those five branches ALIAS the same 460 bytes rather than accumulating. This
     * descriptor is therefore one branch checked against 460 on its own, and the branches are never
     * flattened into a single sequential record -- a flattened record would have a length no dataset
     * carries and offsets no program ever wrote.</p>
     *
     * <p>Assumptions: the misspelling {@code EXP-ACCT-EXPIRAION-DATE} at line 54 is carried over
     * unchanged. AAP Rule T1 makes the copybook normative for field names, and the documented target
     * renaming happens in the persistence mapping rather than in a layout descriptor, so correcting it
     * here would break the lineage the descriptor exists to record.</p>
     *
     * @return a validated 460-byte descriptor covering the twelve declared fields plus the trailing
     *     352-byte filler
     */
    private static RecordSpec exportAccountBranch() {
        return new RecordSpec("EXPORT-ACCOUNT-DATA", 460, 11, 0, List.of(
                CopybookLayout.uint("EXP-ACCT-ID", 0, 11),
                CopybookLayout.text("EXP-ACCT-ACTIVE-STATUS", 11, 1),
                CopybookLayout.packed("EXP-ACCT-CURR-BAL", 12, 10, 2, true),
                CopybookLayout.signedZoned("EXP-ACCT-CREDIT-LIMIT", 19, 10, 2),
                CopybookLayout.packed("EXP-ACCT-CASH-CREDIT-LIMIT", 31, 10, 2, true),
                CopybookLayout.text("EXP-ACCT-OPEN-DATE", 38, 10),
                CopybookLayout.text("EXP-ACCT-EXPIRAION-DATE", 48, 10),
                CopybookLayout.text("EXP-ACCT-REISSUE-DATE", 58, 10),
                CopybookLayout.signedZoned("EXP-ACCT-CURR-CYC-CREDIT", 68, 10, 2),
                CopybookLayout.binary("EXP-ACCT-CURR-CYC-DEBIT", 80, 10, 2, true),
                CopybookLayout.text("EXP-ACCT-ADDR-ZIP", 88, 10),
                CopybookLayout.text("EXP-ACCT-GROUP-ID", 98, 10),
                CopybookLayout.text("FILLER", 108, 352))).validateGeometry();
    }

    /**
     * Builds one 460-byte export account image carrying all five storage regimes.
     *
     * <p>Assumptions: the two packed spans and the binary span are written as literal byte vectors
     * rather than produced by the production encoders. Alternatives Considered: building them by calling
     * the packed encoder. Rejected because the value under test is the byte pattern itself: an image
     * built by the encoder would agree with the decoder even if both shared one mistaken nibble
     * convention, and the assertion would then prove only self-consistency. The vectors below are stated
     * as the nibbles they are, so each is checkable against the geometry rule independently.</p>
     *
     * <p>Assumptions: the two zoned spans reuse the exact twelve-character values the account fixture
     * holds at {@code tests/fixtures/export/happy_path/acctdata.txt} row 1, because
     * {@code app/cpy/CVEXPORT.cpy} line 51 declares the same picture as {@code app/cpy/CVACT01Y.cpy}
     * line 8 and a value valid in one is valid in the other. The binary span holds the two's complement
     * of the unscaled integer minus 4275, which is minus 42.75 at the declared two decimal positions,
     * and its leading bytes are values no single-byte character set maps reversibly -- which is what
     * makes it a usable probe for the dispatch order.</p>
     *
     * @return a newly allocated 460-byte image matching {@link #exportAccountBranch()}
     */
    private static byte[] exportAccountBranchImage() {
        String characterPart = "00000000002"
                + "Y"
                + " ".repeat(7)
                + "00000061300{"
                + " ".repeat(7)
                + "2013-06-19"
                + "2024-08-11"
                + "2024-08-11"
                + "00000000000{"
                + " ".repeat(8)
                + "A000000000"
                + " ".repeat(10)
                + " ".repeat(352);
        byte[] image = characterPart.getBytes(ASCII);

        // WHY : Assumptions: twelve digit positions occupy SEVEN packed bytes, being the ceiling of one
        //       more than the digit count halved. Fourteen nibbles hold thirteen used positions, so the
        //       leading nibble pads and must be zero; the low nibble of the last byte is the sign, 0x0C
        //       for a signed non-negative value and 0x0D for a signed negative one.
        System.arraycopy(bytesOf(0x00, 0x00, 0x00, 0x00, 0x15, 0x80, 0x0D), 0, image, 12, 7);
        System.arraycopy(bytesOf(0x00, 0x00, 0x00, 0x05, 0x44, 0x80, 0x0C), 0, image, 31, 7);

        // WHY : Assumptions: a binary field takes a whole machine unit chosen by its digit count rather
        //       than one byte per digit, so twelve digit positions occupy EIGHT bytes and the value is
        //       read as a two's complement whole number scaled by the declared decimal positions.
        System.arraycopy(bytesOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0x4D), 0, image, 80, 8);
        return image;
    }


    /**
     * Proves the storage-regime enumeration exposes exactly five constants in declaration order.
     *
     * <p>Assumptions: the order is asserted alongside the membership because the declaration order is
     * how this enumeration DOCUMENTS the corpus -- character data first as the default regime, then the
     * two display regimes, then the two usage-driven ones -- so a reordering would leave the class
     * documentation describing a sequence the type no longer has. It is emphatically NOT asserted as a
     * persisted or transmitted contract: no production path anywhere in this module calls
     * {@code ordinal()}, no column or wire field carries a regime ordinal, and nothing outside this
     * enumeration depends on which integer a constant happens to occupy. The ordinal assertions an
     * earlier revision carried are therefore removed rather than restated, because a test that pins a
     * value nothing consumes reads as a wire contract that does not exist.</p>
     *
     * <p>Assumptions: the five are also asserted to be exhaustive. The parity oracle's field descriptor
     * admits only three regimes -- character, unsigned display and zoned decimal -- and a fourth and
     * fifth exist here solely because {@code app/cpy/CVEXPORT.cpy} and the two authorization segment
     * copybooks store money packed and identifiers binary. A sixth constant would mean an unrepresented
     * regime had been found in the corpus.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void kindDeclaresExactlyFiveStorageRegimesInDeclarationOrder() {
        assertArrayEquals(
                new Kind[] {Kind.TEXT, Kind.UINT, Kind.ZONED, Kind.PACKED, Kind.BINARY},
                Kind.values(),
                "the storage-regime constants changed in number or in order");
    }

    /**
     * Proves the field descriptor is a record carrying exactly nine components in descriptor order.
     *
     * <p>Assumptions: the nine components and their order mirror the nine attributes the parity oracle's
     * own field descriptor carries, so a reader holding both open reads one contract rather than two.
     * The order is asserted because a record's canonical constructor is positional: swapping
     * {@code intDigits} with {@code decDigits}, or {@code normalizeTs} with {@code sensitive}, compiles
     * cleanly and changes meaning at every construction site.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void fieldSpecDeclaresNineComponentsInCopybookDescriptorOrder() {
        assertTrue(FieldSpec.class.isRecord(), "the field descriptor must remain a record");
        RecordComponent[] components = FieldSpec.class.getRecordComponents();
        List<String> names = new ArrayList<>();
        for (RecordComponent component : components) {
            names.add(component.getName());
        }
        assertEquals(List.of("name", "start", "length", "kind", "intDigits", "decDigits", "signed",
                "normalizeTs", "sensitive"), names,
                "the field descriptor components changed in number or in order");
    }

    /**
     * Proves the record descriptor is a record carrying exactly five components in geometry order.
     *
     * <p>Assumptions: the five are the whole of what a record layout declares -- its registry name, its
     * total length, and the width and offset of its primary key, followed by its ordered fields. A sixth
     * component for an alternate index key is deliberately absent: the target expresses a secondary
     * access path as a database index declared in a schema migration, not as a property of a record
     * layout, so carrying one here would be a second declaration of something the migration owns
     * elsewhere.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void recordSpecDeclaresFiveComponentsInGeometryOrder() {
        assertTrue(RecordSpec.class.isRecord(), "the record descriptor must remain a record");
        List<String> names = new ArrayList<>();
        for (RecordComponent component : RecordSpec.class.getRecordComponents()) {
            names.add(component.getName());
        }
        assertEquals(List.of("name", "reclen", "keyLength", "keyOffset", "fields"), names,
                "the record descriptor components changed in number or in order");
    }

    /**
     * Proves the derived end offset is the start plus the length and is exclusive.
     *
     * <p>Assumptions: the half-open convention is the one the platform's own subrange operations take,
     * so no call site adjusts an index before slicing, and it is the OPPOSITE of the one-based inclusive
     * convention the reference sort decks use. The two adjacent transaction timestamps are the clearest
     * available proof of exclusivity: the originating stamp ends at 304 and the processing stamp starts
     * at 304, so an inclusive end would make the two overlap by one byte and the contiguity proof would
     * still pass while every field after them shifted.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void fieldEndOffsetIsExclusiveAndEqualsStartPlusLength() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        FieldSpec originating = transaction.field("TRAN-ORIG-TS");
        FieldSpec processing = transaction.field("TRAN-PROC-TS");

        assertEquals(originating.start() + originating.length(), originating.end());
        assertEquals(304, originating.end());
        assertEquals(304, processing.start(),
                "the processing stamp must begin exactly where the originating stamp ends");
        assertEquals(330, processing.end());
        assertEquals("TRAN-ORIG-TS[278,304) TEXT", originating.describe(),
                "the content-free description must render the interval as half-open");
    }

    /**
     * Proves a record descriptor copies the caller's field list and hands back an unmodifiable view.
     *
     * <p>Assumptions: the registry is a static table every service reads, so a descriptor that retained
     * the caller's list would let one caller alter the geometry every other caller sees. Both directions
     * are asserted because a copy taken on the way in without an unmodifiable view on the way out leaves
     * the second half of the hole open, and a single removal from a shared list would change the geometry
     * every subsequent decode uses without raising anything.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures an
     * {@link UnsupportedOperationException} from the mutation attempt on the returned list.</p>
     */
    @Test
    void recordSpecCopiesTheCallerFieldListAndRefusesMutationOfTheReturnedList() {
        List<FieldSpec> supplied = new ArrayList<>(List.of(
                CopybookLayout.text("HEAD", 0, 6),
                CopybookLayout.text("TAIL", 6, 4)));
        RecordSpec built = new RecordSpec("DEFENSIVE-COPY-PROBE", 10, 6, 0, supplied)
                .validateGeometry();

        supplied.clear();
        assertEquals(2, built.fields().size(),
                "clearing the caller's list must not empty the descriptor");
        assertEquals("HEAD", built.fields().get(0).name());

        assertThrows(UnsupportedOperationException.class,
                () -> built.fields().add(CopybookLayout.text("APPENDED", 10, 1)),
                "the returned field list must refuse mutation");
        assertThrows(UnsupportedOperationException.class,
                () -> built.fields().remove(0),
                "the returned field list must refuse removal");
    }

    /**
     * Proves the layout registry's declared surface names no type from the monetary package.
     *
     * <p>Assumptions: the layout registry is a STRUCTURAL description of bytes and must stay usable, and
     * unit-testable, with no monetary type present. Its own imports name nothing but the platform
     * collection types, and this assertion holds that boundary at the reflective surface -- every field,
     * constructor and method of the class and of its nested types, in every parameter and return
     * position. Alternatives Considered: expressing this as a layering rule in the module's architecture
     * suite. Rejected as a duplicate: that suite owns the module-wide rules about which layers may see
     * which packages, whereas the claim here is narrower and belongs beside the descriptor it is about
     * -- that this one class stays free of the monetary type even though its sibling codecs deliberately
     * accept it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void copybookLayoutDeclaredSurfaceNamesNoMonetaryType() {
        String forbiddenPackage = "com.carddemo.common.money";
        List<Class<?>> inspected = new ArrayList<>();
        inspected.add(CopybookLayout.class);
        inspected.addAll(Arrays.asList(CopybookLayout.class.getDeclaredClasses()));

        List<String> offenders = new ArrayList<>();
        for (Class<?> subject : inspected) {
            for (Field field : subject.getDeclaredFields()) {
                if (field.getType().getName().startsWith(forbiddenPackage)) {
                    offenders.add(subject.getSimpleName() + "." + field.getName());
                }
            }
            for (Constructor<?> constructor : subject.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (parameter.getName().startsWith(forbiddenPackage)) {
                        offenders.add(subject.getSimpleName() + " constructor parameter");
                    }
                }
            }
            for (Method method : subject.getDeclaredMethods()) {
                if (method.getReturnType().getName().startsWith(forbiddenPackage)) {
                    offenders.add(subject.getSimpleName() + "." + method.getName() + " return");
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (parameter.getName().startsWith(forbiddenPackage)) {
                        offenders.add(subject.getSimpleName() + "." + method.getName() + " parameter");
                    }
                }
            }
        }
        assertThat(offenders)
                .as("the layout registry must stay structural and independent of the monetary package")
                .isEmpty();
        assertThat(inspected).hasSizeGreaterThanOrEqualTo(5);
    }


    /**
     * Proves a field declaration with no usable name is refused rather than accepted as anonymous.
     *
     * <p>Assumptions: a blank name is refused as well as a null one, because a field name is the key a
     * decoded map is built under and a blank key is indistinguishable from a missing one at every
     * consumer. Anonymous padding is not affected: the corpus spells it {@code FILLER}, which is a name,
     * and {@code app/cpy/CSUSR01Y.cpy} line 23 shows padding can even be named
     * {@code SEC-USR-FILLER}.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each of the two refused declarations.</p>
     */
    @Test
    void fieldSpecRefusesANullOrBlankName() {
        LayoutException fromNull = assertThrows(LayoutException.class,
                () -> CopybookLayout.text(null, 0, 4));
        assertThat(fromNull).hasMessageContaining("null");

        LayoutException fromBlank = assertThrows(LayoutException.class,
                () -> CopybookLayout.text("   ", 0, 4));
        assertThat(fromBlank).hasMessageContaining("blank");
    }

    /**
     * Proves a field declaration outside the admissible offset and width range is refused.
     *
     * <p>Assumptions: a negative offset and a width below one are both transcription errors rather than
     * unusual fields. Every record in the corpus starts at offset zero and every elementary item occupies
     * at least one byte, so neither value can arise from a correct reading of a copybook.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused declaration.</p>
     */
    @Test
    void fieldSpecRefusesANegativeOffsetOrANonPositiveWidth() {
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.text("A", -1, 4)))
                .hasMessageContaining("non-negative offset")
                .hasMessageContaining("start=-1");
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.text("A", 0, 0)))
                .hasMessageContaining("at least one byte");
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.text("A", 0, -3)))
                .hasMessageContaining("at least one byte");
    }

    /**
     * Proves a field declaration with no storage regime is refused.
     *
     * <p>Assumptions: the regime is what determines how a field's bytes decode, so a null one leaves the
     * descriptor unable to answer the only question a codec asks it. The canonical constructor is used
     * directly here because none of the eight factories can express the absence.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the refused declaration.</p>
     */
    @Test
    void fieldSpecRefusesANullStorageRegime() {
        assertThat(assertThrows(LayoutException.class,
                () -> new FieldSpec("A", 0, 4, null, 0, 0, false, false, false)))
                .hasMessageContaining("must declare a kind");
    }

    /**
     * Proves a field declaration carrying negative digit metadata is refused.
     *
     * <p>Assumptions: a negative digit count cannot come from a picture clause, so it is a transcription
     * error. It is refused before the width rule runs, because a width derived from a negative count
     * would be arithmetic on a value that has no meaning rather than a rejected declaration.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused declaration.</p>
     */
    @Test
    void fieldSpecRefusesNegativeDigitMetadata() {
        assertThat(assertThrows(LayoutException.class,
                () -> new FieldSpec("A", 0, 11, Kind.ZONED, -1, 2, true, false, false)))
                .hasMessageContaining("negative digit")
                .hasMessageContaining("intDigits=-1");
        assertThat(assertThrows(LayoutException.class,
                () -> new FieldSpec("A", 0, 11, Kind.ZONED, 9, -2, true, false, false)))
                .hasMessageContaining("decDigits=-2");
    }

    /**
     * Proves the two character-backed regimes refuse digit metadata that would restate their width.
     *
     * <p>The refused declaration raises a {@link CopybookLayout.LayoutException}, which this case
     * captures and inspects.</p>
     *
     * @param kind the character-backed regime under test, either the text regime or the unsigned display
     *     regime, supplied by name so the parameterised case reads as the constant it names
     */
    @ParameterizedTest
    @ValueSource(strings = {"TEXT", "UINT"})
    void characterBackedKindsRefuseDigitMetadata(String kind) {
        // WHY : Assumptions: the unsigned display regime is included in the restriction deliberately,
        //       and that is the non-obvious half. PIC 9(11) at app/cpy/CVACT01Y.cpy line 5 genuinely has
        //       eleven digit positions, but its byte width already IS eleven, so recording the eleven a
        //       second time in the digit metadata is exactly what would let the two statements disagree.
        //       The digit count of such a field is recoverable from its length because the two are equal.
        LayoutException refused = assertThrows(LayoutException.class,
                () -> new FieldSpec("A", 0, 4, Kind.valueOf(kind), 4, 0, false, false, false));
        assertThat(refused)
                .hasMessageContaining("of kind " + kind)
                .hasMessageContaining("declared character count");
    }

    /**
     * Proves each numeric regime refuses a declared width its digit counts do not imply.
     *
     * <p>The refused declaration raises a {@link CopybookLayout.LayoutException}, which this case
     * captures and inspects; the accepted declaration on the implied width raises nothing.</p>
     *
     * @param kind the numeric regime under test, named so the case reads as the constant it names
     * @param intDigits the digit positions before the implied decimal point, taken from the picture
     *     clause as the copybook writes it
     * @param decDigits the digit positions after the implied decimal point
     * @param impliedWidth the only width that regime and digit pair admits
     * @param wrongWidth a width one byte away from the implied one, which the declaration must refuse
     */
    @ParameterizedTest
    @CsvSource({
        "ZONED,  10, 2, 12, 11",
        "ZONED,   9, 2, 11, 12",
        "PACKED, 10, 2,  7,  6",
        "PACKED,  9, 2,  6,  7",
        "BINARY, 10, 2,  8,  4",
        "BINARY,  3, 0,  2,  4",
    })
    void numericKindsRefuseAWidthTheirDigitCountsDoNotImply(String kind, int intDigits, int decDigits,
            int impliedWidth, int wrongWidth) {
        Kind regime = Kind.valueOf(kind);
        assertEquals(impliedWidth, CopybookLayout.widthOf(regime, intDigits, decDigits),
                "the implied width for " + kind + " changed");

        FieldSpec accepted = new FieldSpec("A", 0, impliedWidth, regime, intDigits, decDigits, true,
                false, false);
        assertEquals(impliedWidth, accepted.length());

        // WHY : Assumptions: this guard is the mechanical protection against the one defect that stays
        //       invisible. A twelve-digit packed field declared six bytes wide shifts every field after
        //       it by one byte, and a one-byte shift still decodes to digits, so nothing downstream
        //       would raise. Refusing the declaration is the only place the error is still local.
        assertThat(assertThrows(LayoutException.class,
                () -> new FieldSpec("A", 0, wrongWidth, regime, intDigits, decDigits, true, false,
                        false)))
                .hasMessageContaining("imply a width of " + impliedWidth)
                .hasMessageContaining("length=" + wrongWidth);
    }

    /**
     * Proves each numeric width rule follows the declared usage rather than the picture clause.
     *
     * @param kind the numeric regime whose width rule is under test
     * @param intDigits the digit positions before the implied decimal point
     * @param decDigits the digit positions after the implied decimal point
     * @param expectedWidth the physical byte width that regime and digit pair occupies
     */
    @ParameterizedTest
    @CsvSource({
        "ZONED,  10, 2, 12",
        "ZONED,   9, 2, 11",
        "ZONED,   4, 2,  6",
        "PACKED, 10, 2,  7",
        "PACKED,  9, 2,  6",
        "PACKED,  9, 0,  5",
        "PACKED,  5, 0,  3",
        "PACKED,  3, 0,  2",
        "BINARY,  3, 0,  2",
        "BINARY,  9, 0,  4",
        "BINARY, 11, 0,  8",
        "BINARY, 10, 2,  8",
    })
    void numericWidthRulesFollowTheDeclaredUsageRatherThanThePicture(String kind, int intDigits,
            int decDigits, int expectedWidth) {
        // WHY : Assumptions: the three rules differ in kind and not merely in constant. A zoned field is
        //       one printable digit per byte with the sign folded into the low-order digit, so its width
        //       is the digit count exactly. A packed field is two digits per byte with a sign nibble, so
        //       its width is the ceiling of one more than the digit count halved -- which makes twelve
        //       digits SEVEN bytes and not six. A binary field takes a whole machine unit selected by a
        //       step function of the digit count, which makes eleven digits eight bytes and is what lets
        //       the cross-reference branch of app/cpy/CVEXPORT.cpy close at 460 rather than 456.
        assertEquals(expectedWidth, CopybookLayout.widthOf(Kind.valueOf(kind), intDigits, decDigits));
        switch (Kind.valueOf(kind)) {
            case ZONED -> assertEquals(expectedWidth, CopybookLayout.zonedWidth(intDigits, decDigits));
            case PACKED -> assertEquals(expectedWidth, CopybookLayout.packedWidth(intDigits, decDigits));
            case BINARY -> assertEquals(expectedWidth, CopybookLayout.binaryWidth(intDigits, decDigits));
            case TEXT, UINT -> assertEquals(expectedWidth, expectedWidth);
        }
    }

    /**
     * Proves the width dispatcher refuses the character-backed regimes and a missing regime.
     *
     * <p>Assumptions: the two character-backed regimes take their width from the declared character
     * count, so asking for a digit-derived width is a caller error rather than a value the dispatcher
     * could compute. Refusing keeps the numeric-width contract total, which is the property the field
     * constructor relies on when it insists a numeric field has exactly one admissible width.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused request.</p>
     */
    @Test
    void widthDispatcherRefusesCharacterBackedKindsAndAMissingKind() {
        assertThat(assertThrows(LayoutException.class,
                () -> CopybookLayout.widthOf(Kind.TEXT, 3, 0)))
                .hasMessageContaining("declared character count");
        assertThat(assertThrows(LayoutException.class,
                () -> CopybookLayout.widthOf(Kind.UINT, 3, 0)))
                .hasMessageContaining("declared character count");
        assertThat(assertThrows(LayoutException.class,
                () -> CopybookLayout.widthOf(null, 3, 0)))
                .hasMessageContaining("must not be null");
    }

    /**
     * Proves a digit pair that no picture clause can express is refused by every numeric width rule.
     *
     * <p>Assumptions: a pair summing to zero describes a numeric field with no digit position at all, and
     * a pair beyond eighteen exceeds what the reference dialect admits, so both are transcription errors
     * rather than unusual fields. The widest declaration actually present in the corpus is twelve
     * positions, {@code PIC S9(10)V99}, which appears both packed and zoned inside one record at
     * {@code app/cpy/CVEXPORT.cpy}.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused pair.</p>
     */
    @Test
    void numericWidthRulesRefuseAnEmptyOrOverwideDigitPair() {
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.zonedWidth(0, 0)))
                .hasMessageContaining("at least one digit position");
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.packedWidth(0, 0)))
                .hasMessageContaining("at least one digit position");
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.binaryWidth(0, 0)))
                .hasMessageContaining("at least one digit position");
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.zonedWidth(17, 2)))
                .hasMessageContaining("more than 18 digit positions");
        assertThat(assertThrows(LayoutException.class, () -> CopybookLayout.packedWidth(19, 0)))
                .hasMessageContaining("more than 18 digit positions");
    }

    /**
     * Proves a record declaration with no usable name, length or key is refused.
     *
     * <p>Assumptions: the registry name is how every consumer resolves geometry, so a blank one resolves
     * to nothing; a length below one describes a record with no bytes; and a key width below one or a
     * negative key offset describes an index that cannot be built. Each is refused at construction rather
     * than at first use, because every construction site in the registry is a constant declaration in a
     * static initialiser where no recovery is available.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused declaration.</p>
     */
    @Test
    void recordSpecRefusesANullOrBlankNameAnInvalidLengthOrAnInvalidKey() {
        List<FieldSpec> oneField = List.of(CopybookLayout.text("ONLY", 0, 10));

        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec(null, 10, 2, 0, oneField)))
                .hasMessageContaining("record name")
                .hasMessageContaining("null");
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("  ", 10, 2, 0, oneField)))
                .hasMessageContaining("blank");
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("R", 0, 2, 0, oneField)))
                .hasMessageContaining("length of at least one byte");
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("R", 10, 0, 0, oneField)))
                .hasMessageContaining("key of at least one byte");
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("R", 10, 2, -1, oneField)))
                .hasMessageContaining("non-negative key offset");
    }

    /**
     * Proves a primary key that runs past the end of its record is refused.
     *
     * <p>Assumptions: a key beyond the record cannot be extracted at all, so it is refused here rather
     * than at the first read. The parity oracle performs the identical containment check on its own
     * layouts and for the same reason: a malformed key builds a malformed index instead of raising.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the refused declaration.</p>
     */
    @Test
    void recordSpecRefusesAKeyThatRunsPastTheRecord() {
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("R", 10, 4, 8, List.of(CopybookLayout.text("ONLY", 0, 10)))))
                .hasMessageContaining("key of 4 bytes at offset 8")
                .hasMessageContaining("exceeds its declared length of 10");
    }

    /**
     * Proves a record declaration with no field list at all is refused.
     *
     * <p>Assumptions: a record with no field is a record whose geometry cannot be proven, so the sum rule
     * that makes a declared length a derived fact would have nothing to sum. An empty list is refused as
     * well as a null one, because an empty list would pass the sum rule against a length of zero, and a
     * length of zero is already refused for a different reason -- leaving an empty list the one shape
     * that could slip through two guards that each look sufficient.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused declaration.</p>
     */
    @Test
    void recordSpecRefusesANullOrEmptyFieldList() {
        assertThat(assertThrows(LayoutException.class, () -> new RecordSpec("R", 10, 2, 0, null)))
                .hasMessageContaining("at least one field")
                .hasMessageContaining("null");
        assertThat(assertThrows(LayoutException.class, () -> new RecordSpec("R", 10, 2, 0, List.of())))
                .hasMessageContaining("empty");
    }

    /**
     * Proves a contiguous field list validates and hands the same descriptor back.
     *
     * <p>Assumptions: the geometry proof returns the descriptor itself so that a registry declaration can
     * validate and bind in one expression, which is what makes every registered layout provably tiled at
     * class-load time rather than at first use. Identity is asserted rather than equality, because a copy
     * returned here would mean the caller bound a different instance from the one it proved.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void contiguousFieldListValidatesAndReturnsTheSameDescriptor() {
        RecordSpec candidate = new RecordSpec("CONTIGUOUS-PROBE", 60, 6, 0, List.of(
                CopybookLayout.text("TRAN-TYPE-CD", 0, 2),
                CopybookLayout.uint("TRAN-CAT-CD", 2, 4),
                CopybookLayout.text("TRAN-CAT-TYPE-DESC", 6, 50),
                CopybookLayout.text("FILLER", 56, 4)));
        assertSame(candidate, candidate.validateGeometry(),
                "the geometry proof must return the descriptor it proved");
        assertEquals(60, candidate.reclen());
    }

    /**
     * Proves a gap between two fields is refused and named as a gap.
     *
     * <p>The refused declaration raises a {@link CopybookLayout.LayoutException}, which this case
     * captures and inspects.</p>
     *
     * @param declaredStart the offset the second field wrongly declares, each value leaving a gap of a
     *     different width after the first field ends at offset four
     */
    @ParameterizedTest
    @ValueSource(ints = {5, 6, 9})
    void validateGeometryRefusesAGap(int declaredStart) {
        // WHY : Assumptions: contiguity from offset zero is asserted as ONE condition covering both a
        //       gap and an overlap, because a fixed-width record has no unaccounted byte: every byte
        //       belongs to exactly one elementary field, and padding is a field like any other.
        LayoutException refused = assertThrows(LayoutException.class,
                () -> new RecordSpec("GAPPED", 10, 2, 0, List.of(
                        CopybookLayout.text("HEAD", 0, 4),
                        CopybookLayout.text("TAIL", declaredStart, 10 - declaredStart)))
                        .validateGeometry());
        assertThat(refused)
                .hasMessageContaining("record GAPPED")
                .hasMessageContaining("TAIL[" + declaredStart)
                .hasMessageContaining("preceding fields end at 4")
                .hasMessageContaining("a gap");
    }

    /**
     * Proves an overlap between two fields is refused and named as an overlap.
     *
     * <p>Assumptions: an overlap in a field list is a transcription error and is refused, and that is not
     * in tension with the redefining overlays the corpus genuinely contains. A redefinition aliases
     * storage already declared and is never a second entry in the same field list, which is asserted
     * separately against {@code app/cbl/COCRDLIC.cbl} line 254 and {@code app/cbl/CBTRN02C.cbl} line
     * 160.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the refused declaration.</p>
     */
    @Test
    void validateGeometryRefusesAnOverlap() {
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("OVERLAP", 10, 2, 0, List.of(
                        CopybookLayout.text("HEAD", 0, 6),
                        CopybookLayout.text("TAIL", 4, 6))).validateGeometry()))
                .hasMessageContaining("record OVERLAP")
                .hasMessageContaining("preceding fields end at 6")
                .hasMessageContaining("an overlap");
    }

    /**
     * Proves an out-of-order field list is refused because ordering and contiguity are one rule.
     *
     * <p>Assumptions: declaration order IS the byte order in a fixed-width record, so a list whose
     * entries are correct individually but sequenced wrongly cannot tile the record from offset zero. The
     * two transaction timestamps make the clearest case: swapped, each still describes a valid 26-byte
     * interval and the pair still sums to 52, so only the running-offset rule reports it.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the refused declaration.</p>
     */
    @Test
    void validateGeometryRefusesAnOutOfOrderFieldList() {
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("OUT-OF-ORDER", 52, 26, 0, List.of(
                        CopybookLayout.text("SECOND-TS", 26, 26),
                        CopybookLayout.text("FIRST-TS", 0, 26))).validateGeometry()))
                .hasMessageContaining("record OUT-OF-ORDER")
                .hasMessageContaining("SECOND-TS[26,52)")
                .hasMessageContaining("preceding fields end at 0");
    }

    /**
     * Proves a field list that misses the declared record length is refused in both directions.
     *
     * <p>Assumptions: the sum rule is what makes a declared length a derived fact rather than an
     * assertion, which is why no banner comment is parsed anywhere in this package. Both a short sum and a
     * long sum are refused, because a record described one byte long is exactly as unreadable as one
     * described one byte short and neither is visible in a per-field check.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused declaration.</p>
     */
    @Test
    void validateGeometryRefusesAFieldSumThatMissesTheDeclaredLength() {
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("SUM-SHORT", 12, 2, 0, List.of(
                        CopybookLayout.text("HEAD", 0, 5),
                        CopybookLayout.text("TAIL", 5, 5))).validateGeometry()))
                .hasMessageContaining("field lengths sum to 10")
                .hasMessageContaining("declared length is 12");
        assertThat(assertThrows(LayoutException.class,
                () -> new RecordSpec("SUM-LONG", 8, 2, 0, List.of(
                        CopybookLayout.text("HEAD", 0, 5),
                        CopybookLayout.text("TAIL", 5, 5))).validateGeometry()))
                .hasMessageContaining("field lengths sum to 10")
                .hasMessageContaining("declared length is 8");
    }

    /**
     * Proves a geometry breach raises a layout exception rather than relying on a language assertion.
     *
     * <p>Assumptions: a language assertion is stripped unless the virtual machine is started with
     * assertions enabled, so an assertion-based guard would hold during a test run and then vanish in the
     * deployment where a misaligned money field actually costs money. Throwing holds under every
     * configuration. The parity oracle reaches the same conclusion for the same reason and records it at
     * its own self-check. What is asserted here is the observable consequence: the thrown value is an
     * argument exception a caller can catch, and is not an assertion error.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the refused declaration.</p>
     */
    @Test
    void geometryBreachRaisesALayoutExceptionRatherThanAnAssertionError() {
        LayoutException refused = assertThrows(LayoutException.class,
                () -> new RecordSpec("ASSERTION-INDEPENDENT", 12, 2, 0, List.of(
                        CopybookLayout.text("HEAD", 0, 5),
                        CopybookLayout.text("TAIL", 5, 5))).validateGeometry());
        assertThat(refused)
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(AssertionError.class);
        assertThat(LayoutException.class.getEnclosingClass()).isEqualTo(CopybookLayout.class);
    }

    /**
     * Proves a field lookup fails closed and says that names resolve within one record only.
     *
     * <p>Assumptions: there is deliberately no registry-wide field lookup, so the failure message has to
     * say so rather than merely report a miss. A caller that expected a global map would otherwise read
     * the miss as a missing field when the real answer is that the name belongs to a different
     * record.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused lookup.</p>
     */
    @Test
    void fieldLookupFailsClosedAndScopesTheNameToOneRecord() {
        assertThat(assertThrows(LayoutException.class,
                () -> CopybookLayout.layout("ACCOUNT").field("TRAN-CAT-BAL")))
                .hasMessageContaining("record ACCOUNT declares no field named TRAN-CAT-BAL")
                .hasMessageContaining("resolves only within one record");
        assertThat(assertThrows(LayoutException.class,
                () -> CopybookLayout.layout("TRAN").field("tran-card-num")))
                .as("a field name is matched exactly, so a lower-case spelling must not resolve")
                .hasMessageContaining("declares no field named");
    }

    /**
     * Proves an unregistered record name fails closed and never aliases its dataset spelling.
     *
     * <p>Each of the three registry accessors raises a {@link CopybookLayout.LayoutException} for the
     * unregistered name, and this case captures all three.</p>
     *
     * @param datasetSpelling a dataset name that is near enough to a registry key to be interchanged by
     *     accident, and which must resolve to nothing rather than to that key
     */
    @ParameterizedTest
    @ValueSource(strings = {"USRSEC", "CARDXREF", "DISCGRP", "TRANCATG", "TRANSACT", "ACCTDATA",
        "account", "TRAN "})
    void registryLookupFailsClosedForAnUnregisteredName(String datasetSpelling) {
        // WHY : Assumptions: a caller asking for a layout has no fallback available -- it is about to
        //       slice a record, and slicing against a guessed geometry produces plausible digits rather
        //       than an error. Listing the registered names in the failure is what turns a dataset
        //       spelling into a one-line diagnosis instead of a hunt.
        LayoutException refused = assertThrows(LayoutException.class,
                () -> CopybookLayout.layout(datasetSpelling));
        assertThat(refused)
                .hasMessageContaining("no record layout is registered under the name " + datasetSpelling)
                .hasMessageContaining("the registered names are");
        assertThrows(LayoutException.class, () -> CopybookLayout.provenanceOf(datasetSpelling));
        assertThrows(LayoutException.class, () -> CopybookLayout.hasOracleRoundTrip(datasetSpelling));
    }

    /**
     * Proves a geometry diagnostic names the record and the offending interval and no record bytes.
     *
     * <p>Assumptions: a geometry failure is about a declaration and never about data, so it has no byte
     * of a record to disclose and must not acquire one. The assertion is written as an absence check on
     * the message text so that the absence stays absent: a later contributor adding a content check to
     * the constructor would fail this test rather than quietly widen what a message can carry.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the refused declaration.</p>
     */
    @Test
    void geometryDiagnosticNamesTheRecordAndIntervalWithoutRecordBytes() {
        LayoutException refused = assertThrows(LayoutException.class,
                () -> new RecordSpec("DIAGNOSTIC-PROBE", 30, 16, 0, List.of(
                        CopybookLayout.sensitiveText("TRAN-CARD-NUM", 0, 16),
                        CopybookLayout.text("TRAN-TYPE-CD", 18, 2))).validateGeometry());
        String message = refused.getMessage();
        assertThat(message)
                .contains("record DIAGNOSTIC-PROBE")
                .contains("TRAN-TYPE-CD[18,20) TEXT")
                .contains("preceding fields end at 16")
                .doesNotContain("0927987108636232")
                .doesNotContain("6009619150674526");
    }

    /**
     * Proves a decode failure on a field marked sensitive reports geometry only.
     *
     * <p>Assumptions: the parity oracle includes the offending raw value in its own decode failure
     * message, which suits a harness reading committed fixtures, and this codec deliberately does not,
     * because the same message here reaches an application log. The comparison against a non-sensitive
     * field is what makes the redaction visible: the two failures are otherwise identical, so only the
     * presence of the offending characters in one and their absence in the other distinguishes them. The
     * cause chain is checked too, because a retained cause would carry the same characters into a stack
     * trace even with the outer message redacted.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.FieldCodecException} from each refused decode.</p>
     */
    @Test
    void sensitiveFieldDiagnosticCarriesGeometryOnlyAndNoContent() {
        byte[] offending = "A17".getBytes(ASCII);

        FieldCodecException redacted = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(offending,
                        CopybookLayout.sensitiveUint("CARD-CVV-CD", 0, 3)));
        assertThat(redacted.getMessage())
                .contains("CARD-CVV-CD")
                .contains("offset 0")
                .contains("length 3")
                .contains("kind UINT")
                .contains("field content omitted because it is sensitive")
                .doesNotContain("A17");
        assertThat(redacted.getCause())
                .as("a sensitive failure must not retain a cause that carries the content")
                .isNull();

        FieldCodecException plain = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(offending, CopybookLayout.uint("PLAIN-CD", 0, 3)));
        assertThat(plain.getMessage())
                .contains("PLAIN-CD")
                .contains("non-digit at relative offset 0");
    }


    /**
     * Proves the registry declares exactly the eleven base-master names and no others.
     *
     * <p>Assumptions: the eleven are the records transcribed directly from a copybook that defines a
     * persistent dataset, and they are the population AAP Rule T1 speaks about when it makes the copybook
     * normative. The list is asserted in registration order rather than as a set, because that order is
     * what the registry publishes and a consumer iterating it sees.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void registryDeclaresExactlyTheElevenBaseMasterNames() {
        assertEquals(List.of("ACCOUNT", "CARD", "CUSTOMER", "XREF", "DALYTRAN", "TRAN", "DISGROUP",
                "TCATBAL", "SECUSER", "TRANCAT", "TRANTYPE"), CopybookLayout.baseMasterNames(),
                "the base-master population changed in number or in order");
        assertEquals(11, CopybookLayout.baseMasterNames().size());
    }

    /**
     * Proves the registry declares exactly the three derived names and no others.
     *
     * <p>Assumptions: a derived record's geometry is BUILT from a base master rather than transcribed
     * independently, which is what keeps the shared prefix single-sourced. The statement view is a
     * separate copybook over the same dataset with a wider leading key, and the reject stream and the
     * interest transaction are constructed from a base master by the registry itself.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void registryDeclaresExactlyTheThreeDerivedNames() {
        assertEquals(List.of("TRNX", "REJECT", "INTTRAN"), CopybookLayout.derivedNames(),
                "the derived population changed in number or in order");
        assertEquals(3, CopybookLayout.derivedNames().size());
    }

    /**
     * Proves the two populations partition the registry exactly, with no name in both or in neither.
     *
     * <p>Assumptions: conflating the two populations is how three records go missing without anyone
     * noticing, because the parity oracle registers ELEVEN layouts and the migration has ELEVEN base
     * masters and the two elevens are not the same set. Asserting a partition -- every name in exactly
     * one population, and the two sizes adding to the total -- is what makes a substitution of one
     * population for the other impossible to pass off as a count-correct registry.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void baseMasterAndDerivedPopulationsPartitionTheRegistryExactly() {
        List<String> all = CopybookLayout.names();
        List<String> baseMasters = CopybookLayout.baseMasterNames();
        List<String> derived = CopybookLayout.derivedNames();

        assertEquals(14, all.size(), "the registry total changed");
        assertEquals(all.size(), baseMasters.size() + derived.size(),
                "the two populations must add up to the registry total");
        for (String name : all) {
            boolean isBaseMaster = baseMasters.contains(name);
            boolean isDerived = derived.contains(name);
            assertNotEquals(isBaseMaster, isDerived,
                    () -> name + " must belong to exactly one of the two populations");
            assertEquals(isDerived ? Provenance.DERIVED : Provenance.BASE_MASTER,
                    CopybookLayout.provenanceOf(name),
                    () -> name + " carries a provenance that disagrees with its population");
        }
        assertThat(all).containsAll(baseMasters).containsAll(derived).doesNotHaveDuplicates();
    }

    /**
     * Proves parity-oracle support is false for exactly the three base masters the oracle omits.
     *
     * <p>Trade-offs: three of the eleven base masters have no second, independently written
     * implementation of their geometry to be compared against, so their correctness rests on the copybook
     * declaration and the dataset definition alone. That gap is recorded in the registry rather than left
     * implicit, which is what tells a reviewer where a cross-check is available and where it is not.
     * {@code SECUSER} is declared at {@code app/cpy/CSUSR01Y.cpy} line 17, {@code TRANCAT} at
     * {@code app/cpy/CVTRA04Y.cpy} line 4 and {@code TRANTYPE} at {@code app/cpy/CVTRA03Y.cpy} line 4,
     * and none of the three appears among the parity oracle's eleven registered layouts. The oracle makes
     * its own count of eleven up with the three derived records instead, so both counts are correct at
     * different levels and neither can be read as the other.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void parityOracleSupportIsFalseForExactlyTheThreeOmittedBaseMasters() {
        List<String> withoutOracle = new ArrayList<>();
        for (String name : CopybookLayout.names()) {
            if (!CopybookLayout.hasOracleRoundTrip(name)) {
                withoutOracle.add(name);
            }
        }
        assertEquals(List.of("SECUSER", "TRANCAT", "TRANTYPE"), withoutOracle,
                "the set of records with no independent implementation changed");

        assertFalse(CopybookLayout.hasOracleRoundTrip("SECUSER"));
        assertTrue(CopybookLayout.hasOracleRoundTrip("ACCOUNT"));
        assertTrue(CopybookLayout.hasOracleRoundTrip("TRNX"),
                "a derived record can still have an independent implementation");

        // WHY : Assumptions: the eight overlapping names are asserted explicitly because the arithmetic
        //       that reconciles the two elevens is the whole point. Eleven base masters minus these three
        //       leaves eight, and eight plus the three derived records is the oracle's own eleven.
        assertEquals(8, CopybookLayout.baseMasterNames().size() - withoutOracle.size());
    }

    /**
     * Proves each base master's declared length equals the sum of its declared field widths.
     *
     * @param layoutName the registered logical record name under test
     * @param expectedLength the record length independently transcribed from the copybook declarations,
     *     never from the banner comment the copybook carries
     */
    @ParameterizedTest
    @CsvSource({
        "SECUSER,   80",
        "ACCOUNT,  300",
        "CARD,     150",
        "CUSTOMER, 500",
        "XREF,      50",
        "DALYTRAN, 350",
        "TRAN,     350",
        "DISGROUP,  50",
        "TRANCAT,   60",
        "TRANTYPE,  60",
        "TCATBAL,   50",
    })
    void everyBaseMasterLengthIsTheSumOfItsDeclaredFieldWidths(String layoutName, int expectedLength) {
        RecordSpec spec = CopybookLayout.layout(layoutName);
        int summed = 0;
        for (FieldSpec field : spec.fields()) {
            summed += field.length();
        }

        // WHY : Assumptions: the sum is asserted FIRST and the declared length second, in that order, so
        //       that a disagreement is reported as a geometry fact rather than as a bad constant. A field
        //       declared one byte narrow shifts every field after it and still decodes to digits, so the
        //       sum is the only signal that reaches a reader before the data does.
        assertEquals(expectedLength, summed,
                () -> "the declared field widths of " + layoutName + " no longer sum to its length");
        assertEquals(expectedLength, spec.reclen(),
                () -> "the declared length of " + layoutName + " changed");
        assertEquals(Provenance.BASE_MASTER, CopybookLayout.provenanceOf(layoutName));
    }

    /**
     * Proves every registered layout tiles itself contiguously from offset zero to its declared length.
     *
     * <p>Assumptions: this is the invariant every offset assertion elsewhere in this file rests on, so it
     * is asserted over the whole registry rather than per record. Each field must begin exactly where the
     * previous one ended, which forbids a gap and an overlap in one condition, and the final exclusive end
     * must be the declared length exactly. Padding participates: in a fixed-width record every byte
     * belongs to exactly one elementary field, so filler is a field like any other and is never skipped
     * when the offsets are walked.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void everyRegisteredLayoutTilesItselfContiguouslyFromOffsetZero() {
        for (String name : CopybookLayout.names()) {
            RecordSpec spec = CopybookLayout.layout(name);
            assertThat(spec.fields()).as("layout %s must declare at least one field", name).isNotEmpty();

            int cursor = 0;
            for (FieldSpec field : spec.fields()) {
                assertEquals(cursor, field.start(),
                        () -> "layout " + name + " field " + field.describe()
                                + " does not begin where the preceding field ends");
                assertEquals(field.start() + field.length(), field.end());
                cursor = field.end();
            }
            assertEquals(spec.reclen(), cursor,
                    () -> "layout " + name + " does not close at its declared length");
            assertSame(spec, spec.validateGeometry(),
                    () -> "layout " + name + " no longer proves its own geometry");
        }
    }

    /**
     * Proves every registered layout declares a non-empty primary key contained within the record.
     *
     * <p>Assumptions: the key widths come from the dataset definitions rather than from the copybooks,
     * because a copybook declares fields and a dataset definition declares the index over them. Every
     * registered key begins at offset zero, which is the leading-key convention every one of the
     * reference clusters uses, and a key that ran past the record would build a malformed index rather
     * than raising at read time.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void everyRegisteredLayoutDeclaresAContainedNonEmptyPrimaryKey() {
        for (String name : CopybookLayout.names()) {
            RecordSpec spec = CopybookLayout.layout(name);
            assertThat(spec.keyLength())
                    .as("layout %s must declare a key of at least one byte", name)
                    .isGreaterThan(0);
            assertThat(spec.keyOffset())
                    .as("layout %s must declare a non-negative key offset", name)
                    .isGreaterThanOrEqualTo(0);
            assertThat(spec.keyOffset() + spec.keyLength())
                    .as("the key of layout %s must lie wholly inside the record", name)
                    .isLessThanOrEqualTo(spec.reclen());
        }
    }

    /**
     * Proves each grouped copybook key is flattened to its elementary leaves and never kept as a field.
     *
     * <p>Resolving the group name raises a {@link CopybookLayout.LayoutException}, which this case
     * captures as the proof that the group is not a field.</p>
     *
     * @param layoutName the registered logical record name under test
     * @param groupName the group item the copybook declares, which must not resolve as a field
     * @param compositeWidth the total width of the group's elementary leaves, which is the width the
     *     record's primary key retains
     */
    @ParameterizedTest
    @CsvSource({
        "TCATBAL,  TRAN-CAT-KEY,  17",
        "TRANCAT,  TRAN-CAT-KEY,   6",
        "DISGROUP, DIS-GROUP-KEY, 16",
        "TRNX,     TRNX-KEY,      32",
    })
    void groupedKeysAreFlattenedToLeavesWhileTheirCompositeWidthSurvivesInTheKey(String layoutName,
            String groupName, int compositeWidth) {
        RecordSpec spec = CopybookLayout.layout(layoutName);

        // WHY : Assumptions: only elementary leaves carry bytes. A group item is a name over a run of
        //       leaves, so keeping it as a field as well would count the same storage twice and make the
        //       field widths sum to more than the record length.
        assertThrows(LayoutException.class, () -> spec.field(groupName),
                "a group item must not resolve as a field");

        assertEquals(compositeWidth, spec.keyLength(),
                () -> "the composite key width of " + layoutName + " changed");
        assertEquals(0, spec.keyOffset());

        int leadingLeaves = 0;
        int cursor = 0;
        for (FieldSpec field : spec.fields()) {
            if (cursor >= compositeWidth) {
                break;
            }
            cursor = field.end();
            leadingLeaves++;
        }
        assertEquals(compositeWidth, cursor,
                () -> "the leading leaves of " + layoutName + " do not close on the key width");
        assertThat(leadingLeaves)
                .as("a composite key must be made of more than one leaf in %s", layoutName)
                .isGreaterThan(1);
    }

    /**
     * Proves the same textual group name carries two different widths in two different copybooks.
     *
     * <p>Assumptions: this single fact is the reason the package offers no registry-wide field lookup.
     * {@code app/cpy/CVTRA01Y.cpy} line 5 declares {@code TRAN-CAT-KEY} over an eleven-digit account
     * identifier, a two-character type code and a four-digit category code, which is 17 bytes, and
     * {@code app/cpy/CVTRA04Y.cpy} line 5 declares the SAME name over only the last two of those, which
     * is 6 bytes. A global map would have to pick one width and would then be wrong for every reader of
     * the other. Both widths are corroborated independently of the copybooks: the category-balance print
     * deck at {@code app/jcl/PRTCATBL.jcl} lines 47 to 50 puts the balance at one-based position 18, so
     * the key before it occupies the first 17 bytes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void theSameGroupNameCarriesTwoDifferentWidthsInTwoDifferentCopybooks() {
        RecordSpec categoryBalance = CopybookLayout.layout("TCATBAL");
        RecordSpec category = CopybookLayout.layout("TRANCAT");

        assertEquals(17, categoryBalance.keyLength());
        assertEquals(6, category.keyLength());
        assertNotEquals(categoryBalance.keyLength(), category.keyLength(),
                "the two identically named groups must keep their different widths");

        assertEquals(11, categoryBalance.field("TRANCAT-ACCT-ID").length());
        assertEquals(2, categoryBalance.field("TRANCAT-TYPE-CD").length());
        assertEquals(4, categoryBalance.field("TRANCAT-CD").length());
        assertEquals(17, categoryBalance.field("TRANCAT-ACCT-ID").length()
                + categoryBalance.field("TRANCAT-TYPE-CD").length()
                + categoryBalance.field("TRANCAT-CD").length());

        assertEquals(2, category.field("TRAN-TYPE-CD").length());
        assertEquals(4, category.field("TRAN-CAT-CD").length());
        assertEquals(6, category.field("TRAN-TYPE-CD").length()
                + category.field("TRAN-CAT-CD").length());
    }

    /**
     * Proves colliding field names resolve to different intervals in different records.
     *
     * <p>Assumptions: field names are not unique across the corpus, and the collisions are not
     * hypothetical. {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are declared in
     * {@code app/cpy/CVTRA05Y.cpy} lines 6 and 7 as the second and third fields of a transaction, and
     * again in {@code app/cpy/CVTRA04Y.cpy} lines 6 and 7 as the FIRST two fields of a category record.
     * Resolving either name without saying which record it belongs to would read a transaction's type
     * code from a category record's leading bytes, which are digits in both cases, so the read would
     * succeed and be wrong.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the lookup of a name that belongs to the other
     * record.</p>
     */
    @Test
    void collidingFieldNamesResolveToDifferentIntervalsInDifferentRecords() {
        assertEquals("TRAN-TYPE-CD[16,18) TEXT",
                CopybookLayout.layout("TRAN").field("TRAN-TYPE-CD").describe());
        assertEquals("TRAN-TYPE-CD[0,2) TEXT",
                CopybookLayout.layout("TRANCAT").field("TRAN-TYPE-CD").describe());
        assertEquals("TRAN-CAT-CD[18,22) UINT",
                CopybookLayout.layout("TRAN").field("TRAN-CAT-CD").describe());
        assertEquals("TRAN-CAT-CD[2,6) UINT",
                CopybookLayout.layout("TRANCAT").field("TRAN-CAT-CD").describe());

        // WHY : Assumptions: the balance field name is the sharper case, because it exists in exactly one
        //       of the two records that share the group name above. Looking it up on the wrong one has to
        //       fail rather than fall back to anything.
        assertEquals("TRAN-CAT-BAL[17,28) ZONED",
                CopybookLayout.layout("TCATBAL").field("TRAN-CAT-BAL").describe());
        assertThrows(LayoutException.class,
                () -> CopybookLayout.layout("TRANCAT").field("TRAN-CAT-BAL"));
    }


    /**
     * Proves every transaction field sits at the zero-based offset and width its copybook line implies.
     *
     * @param fieldName the field name exactly as {@code app/cpy/CVTRA05Y.cpy} declares it
     * @param start the zero-based byte offset the running sum of the preceding declared widths produces
     * @param length the declared physical byte width of the field
     * @param kind the storage regime the declared usage selects, named so the case reads as the constant
     */
    @ParameterizedTest
    @CsvSource({
        "TRAN-ID,             0,  16, TEXT",
        "TRAN-TYPE-CD,       16,   2, TEXT",
        "TRAN-CAT-CD,        18,   4, UINT",
        "TRAN-SOURCE,        22,  10, TEXT",
        "TRAN-DESC,          32, 100, TEXT",
        "TRAN-AMT,          132,  11, ZONED",
        "TRAN-MERCHANT-ID,  143,   9, UINT",
        "TRAN-MERCHANT-NAME, 152, 50, TEXT",
        "TRAN-MERCHANT-CITY, 202, 50, TEXT",
        "TRAN-MERCHANT-ZIP,  252, 10, TEXT",
        "TRAN-CARD-NUM,      262, 16, TEXT",
        "TRAN-ORIG-TS,       278, 26, TEXT",
        "TRAN-PROC-TS,       304, 26, TEXT",
        "FILLER,             330, 20, TEXT",
    })
    void transactionLayoutPlacesEveryFieldAtItsCopybookDerivedOffset(String fieldName, int start,
            int length, String kind) {
        FieldSpec field = CopybookLayout.layout("TRAN").field(fieldName);
        assertEquals(start, field.start(), () -> fieldName + " moved from its declared offset");
        assertEquals(length, field.length(), () -> fieldName + " changed width");
        assertEquals(Kind.valueOf(kind), field.kind(), () -> fieldName + " changed storage regime");
        assertEquals(start + length, field.end());
    }

    /**
     * Proves the transaction record closes at 350 bytes with its money field at the amount offset.
     *
     * <p>Assumptions: the amount is eleven bytes and not twelve because {@code app/cpy/CVTRA05Y.cpy} line
     * 10 declares {@code PIC S9(09)V99}, whose nine plus two digit positions occupy eleven bytes with the
     * sign folded into the low-order digit as an overpunch. That eleven is corroborated independently of
     * the copybook by {@code app/jcl/PRTCATBL.jcl} line 50, which declares eleven bytes of zoned decimal
     * for the identically pictured category balance.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void transactionRecordClosesAtThreeHundredFiftyBytesWithAnElevenByteAmount() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        assertEquals(350, transaction.reclen());
        assertEquals(350, transaction.fields().get(transaction.fields().size() - 1).end(),
                "the final field must end exactly at the declared record length");

        FieldSpec amount = transaction.field("TRAN-AMT");
        assertEquals(132, amount.start());
        assertEquals(11, amount.length());
        assertEquals(9, amount.intDigits());
        assertEquals(2, amount.decDigits());
        assertTrue(amount.signed(), "the amount picture carries a leading sign");
        assertEquals(11, CopybookLayout.zonedWidth(9, 2));
    }

    /**
     * Proves the transaction access-path offsets match the one-based sort symbol positions.
     *
     * <p>Assumptions: the sort deck states these offsets independently of the copybooks, so the two
     * agreeing is a genuine cross-check rather than a restatement. {@code app/jcl/TRANREPT.jcl} line 41
     * declares {@code TRAN-CARD-NUM,263,16,ZD} and line 42 declares {@code TRAN-PROC-DT,305,10,CH}, both
     * in ONE-based inclusive positions, so subtracting one gives the zero-based 262 and 304 the registry
     * holds. Assumptions: the name {@code TRAN-PROC-DT} in that deck is not a copybook field and does not
     * contradict one. It names the leading TEN-character date window of the {@code PIC X(26)} processing
     * timestamp declared at {@code app/cpy/CVTRA05Y.cpy} line 17, which is what lets the deck's own
     * character comparison against a date literal work; the copybook field remains 26 bytes wide.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void transactionAccessPathOffsetsMatchTheOneBasedSortSymbolPositions() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");

        int cardNumberOneBased = 263;
        int processingStampOneBased = 305;
        assertEquals(cardNumberOneBased - 1, transaction.field("TRAN-CARD-NUM").start());
        assertEquals(16, transaction.field("TRAN-CARD-NUM").length());
        assertEquals(processingStampOneBased - 1, transaction.field("TRAN-PROC-TS").start());

        // WHY : Assumptions: the deck reads ten characters where the copybook declares 26, and both are
        //       right. The date window is a prefix of the timestamp, and an ISO-ordered date compares
        //       lexically exactly as it compares as a date, which is the property the deck's own record
        //       selection relies on. Asserting the containment states the relationship rather than
        //       picking one of the two widths as the truth.
        int dateWindowWidth = 10;
        assertThat(dateWindowWidth).isLessThan(transaction.field("TRAN-PROC-TS").length());
        assertEquals(26, transaction.field("TRAN-PROC-TS").length());
    }

    /**
     * Proves the transaction alternate index key covers exactly the processing timestamp interval.
     *
     * <p>Assumptions: {@code app/jcl/TRANIDX.jcl} line 27 declares the alternate index as
     * {@code KEYS(26 304)}, being a 26-byte key at zero-based offset 304. That operand pair is written
     * SPACE-separated in that file where other definitions in the corpus write it comma-separated, so it
     * is quoted here exactly as the file writes it rather than normalised to one house form. Unlike the
     * sort deck's ten-character window this declaration is already zero-based, which is why no adjustment
     * is applied to it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void transactionAlternateIndexKeyCoversTheProcessingTimestampInterval() {
        FieldSpec processing = CopybookLayout.layout("TRAN").field("TRAN-PROC-TS");
        int alternateIndexKeyLength = 26;
        int alternateIndexKeyOffset = 304;

        assertEquals(alternateIndexKeyOffset, processing.start());
        assertEquals(alternateIndexKeyLength, processing.length());
        assertEquals(alternateIndexKeyOffset + alternateIndexKeyLength, processing.end());
        assertEquals(330, processing.end());

        // WHY : Assumptions: the alternate index is a SECONDARY access path and is deliberately not a
        //       component of the record descriptor. The registry carries the primary key only, because the
        //       target expresses a secondary path as a database index declared in a schema migration; the
        //       primary key of a transaction stays the sixteen-byte identifier at offset zero.
        assertEquals(16, CopybookLayout.layout("TRAN").keyLength());
        assertEquals(0, CopybookLayout.layout("TRAN").keyOffset());
    }

    /**
     * Proves the category-balance offsets match the one-based print symbol positions.
     *
     * <p>Assumptions: {@code app/jcl/PRTCATBL.jcl} lines 47 to 50 declare four symbols at ONE-based
     * positions 1, 12, 14 and 18, which are zero-based 0, 11, 13 and 17. They are an independent proof of
     * two facts the copybook states differently: that the grouped key at {@code app/cpy/CVTRA01Y.cpy}
     * line 5 occupies the first 17 bytes, because the balance begins immediately after it; and that the
     * balance is eleven bytes of zoned decimal, which the deck states outright.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void categoryBalanceOffsetsMatchTheOneBasedPrintSymbolPositions() {
        RecordSpec categoryBalance = CopybookLayout.layout("TCATBAL");

        assertEquals(1 - 1, categoryBalance.field("TRANCAT-ACCT-ID").start());
        assertEquals(11, categoryBalance.field("TRANCAT-ACCT-ID").length());
        assertEquals(12 - 1, categoryBalance.field("TRANCAT-TYPE-CD").start());
        assertEquals(2, categoryBalance.field("TRANCAT-TYPE-CD").length());
        assertEquals(14 - 1, categoryBalance.field("TRANCAT-CD").start());
        assertEquals(4, categoryBalance.field("TRANCAT-CD").length());
        assertEquals(18 - 1, categoryBalance.field("TRAN-CAT-BAL").start());
        assertEquals(11, categoryBalance.field("TRAN-CAT-BAL").length());
        assertEquals(Kind.ZONED, categoryBalance.field("TRAN-CAT-BAL").kind());

        assertEquals(17, categoryBalance.keyLength(),
                "the balance offset and the composite key width are the same fact stated twice");
        assertEquals(50, categoryBalance.reclen());
    }

    /**
     * Proves the statement view is its own 350-byte layout rather than an alias of the transaction.
     *
     * <p>Assumptions: {@code app/cpy/COSTM01.CPY} line 20 declares a separate record over the same
     * dataset with a WIDER leading key -- the sixteen-byte card number at line 22 followed by the
     * sixteen-byte transaction identifier at line 23, which is 32 bytes -- where the transaction record
     * leads with the identifier alone. Both records are 350 bytes and every field after the key is the
     * same shape, which is exactly why an alias would be plausible and wrong: reading a statement row
     * against the transaction layout would take the card number as the transaction identifier and shift
     * the amount by sixteen bytes into the middle of the description.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from the lookup of the flattened-away group item.</p>
     */
    @Test
    void statementViewIsItsOwnLayoutRatherThanAnAliasOfTheTransaction() {
        RecordSpec statement = CopybookLayout.layout("TRNX");
        RecordSpec transaction = CopybookLayout.layout("TRAN");

        assertEquals(350, statement.reclen());
        assertEquals(transaction.reclen(), statement.reclen(),
                "the two records are the same length, which is what makes an alias plausible");
        assertNotEquals(transaction.keyLength(), statement.keyLength(),
                "the two records must keep their different key widths");

        assertEquals(32, statement.keyLength());
        assertEquals(0, statement.keyOffset());
        assertEquals(16, statement.field("TRNX-CARD-NUM").length());
        assertEquals(0, statement.field("TRNX-CARD-NUM").start());
        assertEquals(16, statement.field("TRNX-ID").length());
        assertEquals(16, statement.field("TRNX-ID").start());
        assertEquals(32, statement.field("TRNX-CARD-NUM").length() + statement.field("TRNX-ID").length());

        assertEquals("TRNX-AMT[148,159) ZONED", statement.field("TRNX-AMT").describe());
        assertNotEquals(transaction.field("TRAN-AMT").start(), statement.field("TRNX-AMT").start(),
                "the wider leading key displaces the amount by sixteen bytes");
        assertEquals(Provenance.DERIVED, CopybookLayout.provenanceOf("TRNX"));

        // WHY : Assumptions: the second group item in that copybook, TRNX-REST at line 24, is a name over
        //       the remaining leaves and carries no bytes of its own, so it is flattened away exactly as
        //       the key group is. Retaining it would count the whole tail of the record twice.
        assertThrows(LayoutException.class, () -> statement.field("TRNX-REST"));
    }

    /**
     * Proves the reject stream appends the eighty-byte validation trailer to the daily-transaction prefix.
     *
     * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl} lines 176 to 178 declare the reject record as a
     * 350-byte transaction image followed by an eighty-byte trailer, and lines 181 to 182 declare that
     * trailer as a four-digit reason code and a 76-character description. The 350-byte prefix is the daily
     * transaction, field for field, and it is DERIVED rather than restated: two hand-maintained copies of
     * a fourteen-field prefix would drift, and the drift would be silent because a record read against a
     * prefix that is wrong in one field still decodes to digits in every field after it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void rejectStreamAppendsTheEightyByteValidationTrailerToTheDailyTransactionPrefix() {
        RecordSpec reject = CopybookLayout.layout("REJECT");
        RecordSpec daily = CopybookLayout.layout("DALYTRAN");

        assertEquals(430, reject.reclen());
        assertEquals(350 + 80, reject.reclen());
        assertEquals(daily.fields(), reject.fields().subList(0, daily.fields().size()),
                "the reject prefix must be the daily-transaction field list itself");
        assertEquals(daily.fields().size() + 2, reject.fields().size());
        assertEquals(daily.keyLength(), reject.keyLength());

        FieldSpec reason = reject.field("WS-VALIDATION-FAIL-REASON");
        assertEquals(350, reason.start());
        assertEquals(4, reason.length());
        assertEquals(Kind.UINT, reason.kind());

        FieldSpec description = reject.field("WS-VALIDATION-FAIL-REASON-DESC");
        assertEquals(354, description.start());
        assertEquals(76, description.length());
        assertEquals(Kind.TEXT, description.kind());
        assertEquals(430, description.end());
        assertEquals(Provenance.DERIVED, CopybookLayout.provenanceOf("REJECT"));
    }

    /**
     * Proves the interest transaction clones the transaction layout and flips one timestamp flag only.
     *
     * <p>Assumptions: the interest transaction is generated during the batch run, so its ORIGINATING stamp
     * is a wall-clock value the parity comparison has to blank before comparing, whereas an ordinary
     * posted transaction carries a deterministic originating stamp the comparison has to verify. Altering
     * the base record's own flag was therefore not an option: it would discard business data from every
     * ordinary transaction and shorten the effectively compared record. Only the two diagnostic flags are
     * replaceable and never the geometry, which is why the derived record tiles identically to the record
     * it derives from and why its processing stamp keeps the marking it already had.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void interestTransactionClonesTheTransactionLayoutAndFlipsOneTimestampFlagOnly() {
        RecordSpec interest = CopybookLayout.layout("INTTRAN");
        RecordSpec transaction = CopybookLayout.layout("TRAN");

        assertEquals(350, interest.reclen());
        assertEquals(transaction.reclen(), interest.reclen());
        assertEquals(transaction.keyLength(), interest.keyLength());
        assertEquals(transaction.keyOffset(), interest.keyOffset());
        assertEquals(transaction.fields().size(), interest.fields().size());

        assertFalse(transaction.field("TRAN-ORIG-TS").normalizeTs(),
                "an ordinary posted transaction carries a deterministic originating stamp");
        assertTrue(interest.field("TRAN-ORIG-TS").normalizeTs(),
                "a generated interest transaction carries a run-time originating stamp");
        assertTrue(transaction.field("TRAN-PROC-TS").normalizeTs());
        assertTrue(interest.field("TRAN-PROC-TS").normalizeTs(),
                "the processing stamp keeps the marking it already had");

        for (FieldSpec field : transaction.fields()) {
            FieldSpec derived = interest.field(field.name());
            assertEquals(field.start(), derived.start(), () -> field.name() + " moved");
            assertEquals(field.length(), derived.length(), () -> field.name() + " changed width");
            assertEquals(field.kind(), derived.kind(), () -> field.name() + " changed regime");
            assertEquals(field.sensitive(), derived.sensitive(),
                    () -> field.name() + " changed its diagnostic marking");
        }
        assertNotEquals(transaction.fields(), interest.fields(),
                "exactly one flag differs, so the two field lists must not be equal");
    }

    /**
     * Proves deriving a record leaves the base layouts untouched and their field lists unmodifiable.
     *
     * <p>Assumptions: the registry is built once in a static initialiser and read by every service, so a
     * derivation that mutated its base would change the geometry every subsequent decode uses. Both bases
     * are checked after the derivations that read them have run -- which they have, since the registry is
     * fully built before any test executes -- so the assertion is about the state the derivations left
     * behind rather than about the derivation call itself.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures an
     * {@link UnsupportedOperationException} from each mutation attempt on a base field list, and a
     * {@link CopybookLayout.LayoutException} both from the lookup of the appended trailer on the base
     * record and from a derivation naming a field that does not exist.</p>
     */
    @Test
    void derivationLeavesTheBaseLayoutsUntouchedAndUnmodifiable() {
        RecordSpec daily = CopybookLayout.layout("DALYTRAN");
        RecordSpec transaction = CopybookLayout.layout("TRAN");

        assertEquals(350, daily.reclen());
        assertEquals(14, daily.fields().size(),
                "the reject derivation must not have appended to its base");
        assertEquals(350, transaction.reclen());
        assertEquals(14, transaction.fields().size());
        assertThrows(LayoutException.class, () -> daily.field("WS-VALIDATION-FAIL-REASON"),
                "the appended trailer must not be visible on the base record");

        assertThrows(UnsupportedOperationException.class, () -> daily.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> transaction.fields().clear());

        // WHY : Assumptions: repeating a derivation must be a pure function of its inputs, so running one
        //       here has to leave the registry exactly as it was. Deriving again and comparing the base
        //       afterwards is what proves the base is a value rather than a mutable accumulator.
        RecordSpec repeated = transaction.withFieldFlags("INTTRAN-REPEAT", "TRAN-ORIG-TS", true, false);
        assertEquals(transaction.reclen(), repeated.reclen());
        assertFalse(transaction.field("TRAN-ORIG-TS").normalizeTs(),
                "deriving again must not have altered the base");
        assertThrows(LayoutException.class,
                () -> transaction.withFieldFlags("INTTRAN-MISSPELLED", "TRAN-ORIGIN-TS", true, false),
                "a field name that matches nothing must fail rather than produce a silent clone");
    }


    /**
     * Proves a decoded record is an insertion-ordered map keyed in declaration order.
     *
     * <p>Assumptions: declaration order IS byte order, so a map that lost the order would lose the only
     * statement of where each value came from. The concrete ordered type is asserted as well as the
     * observed order, because a caller that copies the result into an unordered map would silently
     * discard the property while every individual value stayed correct. Alternatives Considered: one
     * typed carrier per layout, which would restore compile-time field names. Rejected because it would
     * duplicate the eleven base-master shapes in a second class hierarchy that then has to be kept in
     * step with the registry by hand.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void decodedRecordIsAnInsertionOrderedMapKeyedInDeclarationOrder() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(transactionImage().getBytes(ASCII),
                transaction);

        assertThat(decoded).isInstanceOf(LinkedHashMap.class);
        assertEquals(List.of("TRAN-ID", "TRAN-TYPE-CD", "TRAN-CAT-CD", "TRAN-SOURCE", "TRAN-DESC",
                "TRAN-AMT", "TRAN-MERCHANT-ID", "TRAN-MERCHANT-NAME", "TRAN-MERCHANT-CITY",
                "TRAN-MERCHANT-ZIP", "TRAN-CARD-NUM", "TRAN-ORIG-TS", "TRAN-PROC-TS"),
                new ArrayList<>(decoded.keySet()),
                "the decoded keys must follow the declaration order with blank padding omitted");

        // WHY : Assumptions: the trailing filler is present in the DESCRIPTOR, which is what lets the
        //       contiguity and record-length proofs above run at all, and absent from the decoded VALUES,
        //       because an inert twenty-byte blank run is not a domain value any consumer wants to carry.
        //       The two statements are about different things and both are asserted so neither can be read
        //       as the other.
        assertThat(transaction.fields()).anyMatch(field -> "FILLER".equals(field.name()));
        assertThat(decoded).doesNotContainKey("FILLER");
    }

    /**
     * Proves an encode of a decoded record emits exactly the declared record length.
     *
     * @param layoutName the registered logical record name under test, chosen to cover a blank-padded
     *     record, a character-zero-padded record and a derived record in one parameterised case
     */
    @ParameterizedTest
    @ValueSource(strings = {"XREF", "TCATBAL", "TRAN", "DALYTRAN"})
    void encodeEmitsExactlyTheDeclaredRecordLength(String layoutName) {
        RecordSpec spec = CopybookLayout.layout(layoutName);
        String image = switch (layoutName) {
            case "XREF" -> "0927987108636232" + "000000020" + "00000000020" + " ".repeat(14);
            case "TCATBAL" -> "00000000007" + "01" + "0001" + "0000001000{" + "0".repeat(22);
            case "TRAN" -> transactionImage();
            default -> dailyTransactionImage();
        };

        // WHY : Assumptions: the length is asserted on the ENCODE result and not only on the input,
        //       because the codec allocates the target array from the declared length and then writes into
        //       it. A field that wrote short would leave the array's own initial bytes in place, which
        //       preserves the length and changes the content, so a length check alone is not the whole
        //       proof -- which is why the byte-identity harness compares content as well.
        byte[] encoded = FixedWidthCodec.encodeRecord(
                FixedWidthCodec.decodeRecord(image.getBytes(ASCII), spec), spec);
        assertEquals(spec.reclen(), encoded.length,
                () -> "the encoded " + layoutName + " is not the declared record length");
        assertEquals(image.length(), encoded.length);
    }

    /**
     * Proves a record shorter or longer than its declared length is refused before any field conversion.
     *
     * <p>Assumptions: the length is checked before the first slice, which gives up any partial recovery
     * from a truncated record. That is the intended trade: a plausible partial map hides a shifted money
     * field, and a shifted money field still decodes to digits. Both directions are refused, and neither
     * is silently padded or truncated -- a padded short record would report a balance the dataset never
     * held.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.RecordLengthException} from each refused image.</p>
     */
    @Test
    void aShortOrLongRecordIsRefusedBeforeAnyFieldConversion() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        byte[] tooShort = transactionImage().substring(0, 349).getBytes(ASCII);
        byte[] tooLong = (transactionImage() + " ").getBytes(ASCII);

        assertThat(assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(tooShort, transaction)))
                .hasMessageContaining("record TRAN expected 350 bytes but received 349");
        assertThat(assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(tooLong, transaction)))
                .hasMessageContaining("record TRAN expected 350 bytes but received 351");
        assertThat(assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(null, transaction)))
                .hasMessageContaining("received null");

        // WHY : Assumptions: an EMPTY record is refused by the same rule rather than treated as an
        //       end-of-file marker. The codec has no notion of a stream, so a zero-length array reaching
        //       it is a caller defect, and answering it with an empty map would produce a record with no
        //       fields that every consumer would then read as a record with blank fields.
        assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(new byte[0], transaction));
    }

    /**
     * Proves the record-length diagnostic names the layout and both lengths and no record bytes.
     *
     * <p>Assumptions: the two lengths are what a reader needs to act -- they say whether the reader or the
     * writer is wrong, and by how much -- and the bytes are what a reader must not be given, because a
     * record image of the wrong length is still a record image and the transaction and card records both
     * carry a primary account number. The absence is asserted explicitly so it stays absent.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.RecordLengthException} from the refused image.</p>
     */
    @Test
    void recordLengthDiagnosticNamesTheLayoutAndBothLengthsWithoutRecordBytes() {
        String truncated = "0927987108636232" + "000000020" + "00000000020";
        RecordLengthException refused = assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(truncated.getBytes(ASCII),
                        CopybookLayout.layout("XREF")));

        assertThat(refused.getMessage())
                .contains("record XREF")
                .contains("expected 50")
                .contains("received 36")
                .doesNotContain("0927987108636232");
        assertThat(refused).isInstanceOf(IllegalArgumentException.class);
        assertEquals(FixedWidthCodec.class, RecordLengthException.class.getEnclosingClass(),
                "the length failure must stay a nested type of the codec that raises it");
    }

    /**
     * Proves the codec validates at field, record and registry granularity through its own entry points.
     *
     * <p>Assumptions: the three granularities fail differently on purpose and each has its own entry
     * point, so a caller can tell a malformed field from a malformed record image from a name that
     * resolves to no layout at all. A single undifferentiated failure would leave a reader unable to tell
     * a corrupt dataset from a mistyped record name.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.FieldCodecException} from the field-level refusals, a
     * {@link FixedWidthCodec.RecordLengthException} from the record-level refusal and a
     * {@link CopybookLayout.LayoutException} from the registry-level and layout-level refusals.</p>
     */
    @Test
    void codecValidatesAtFieldRecordAndRegistryGranularity() {
        FieldSpec field = CopybookLayout.text("PROBE", 0, 4);

        assertThrows(FieldCodecException.class, () -> FixedWidthCodec.decodeField(new byte[3], field));
        assertThrows(FieldCodecException.class, () -> FixedWidthCodec.decodeField(new byte[4], null));
        assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.encodeField("AB", field, new byte[2]));

        assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(new byte[10], CopybookLayout.layout("XREF")));

        assertThrows(LayoutException.class, () -> CopybookLayout.layout("NO-SUCH-RECORD"));
        assertThat(assertThrows(LayoutException.class,
                () -> FixedWidthCodec.decodeRecord(new byte[8], null)))
                .hasMessageContaining("non-null record layout");

        // WHY : Assumptions: the codec re-proves a supplied layout rather than trusting it, because a
        //       descriptor can be constructed without its geometry proof having been run. A field reaching
        //       past the declared length is the case the record-length check cannot catch, since the image
        //       handed in would be the right length while the layout asked for more.
        RecordSpec beyond = new RecordSpec("BEYOND-RECLEN", 8, 4, 0, List.of(
                CopybookLayout.text("HEAD", 0, 5),
                CopybookLayout.text("TAIL", 5, 5)));
        assertThat(assertThrows(LayoutException.class,
                () -> FixedWidthCodec.decodeRecord(new byte[8], beyond)))
                .hasMessageContaining("TAIL[5,10) TEXT")
                .hasMessageContaining("lies outside reclen 8");

        // WHY : Assumptions: a duplicated field name is refused by the codec and not by the descriptor,
        //       because a decoded record is a map and a map cannot represent two entries under one key.
        //       Accepting one would silently keep whichever field was decoded last.
        RecordSpec duplicated = new RecordSpec("DUPLICATE-NAMES", 8, 4, 0, List.of(
                CopybookLayout.text("FILLER", 0, 4),
                CopybookLayout.text("FILLER", 4, 4)));
        assertThat(assertThrows(LayoutException.class,
                () -> FixedWidthCodec.decodeRecord(new byte[8], duplicated)))
                .hasMessageContaining("more than once");
    }

    /**
     * Proves an encode refuses a null map, an undeclared key and a missing required field.
     *
     * <p>Assumptions: an undeclared key is refused rather than ignored, because ignoring it would let a
     * caller believe it had written a field that the record does not contain, and the record would then be
     * emitted with that field's declared bytes left at whatever the fresh array held. A missing required
     * field is refused for the mirror-image reason, and the diagnostic names the field so the caller can
     * see which one. The one omission the codec accepts is registered blank padding, which it rebuilds
     * itself.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.FieldCodecException} from each refused encode.</p>
     */
    @Test
    void encodeRefusesANullMapAnUndeclaredKeyAndAMissingRequiredField() {
        RecordSpec crossReference = CopybookLayout.layout("XREF");

        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.encodeRecord(null, crossReference)))
                .hasMessageContaining("null field map");

        Map<String, Object> withUndeclaredKey = new LinkedHashMap<>();
        withUndeclaredKey.put("XREF-CARD-NUM", "0927987108636232");
        withUndeclaredKey.put("XREF-CUST-ID", Long.valueOf(20L));
        withUndeclaredKey.put("XREF-ACCT-ID", Long.valueOf(20L));
        withUndeclaredKey.put("XREF-EXPIRAION-DATE", "2024-08-11");
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.encodeRecord(withUndeclaredKey, crossReference)))
                .hasMessageContaining("no declared field named XREF-EXPIRAION-DATE");

        Map<String, Object> missingRequired = new LinkedHashMap<>();
        missingRequired.put("XREF-CARD-NUM", "0927987108636232");
        missingRequired.put("XREF-ACCT-ID", Long.valueOf(20L));
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.encodeRecord(missingRequired, crossReference)))
                .hasMessageContaining("missing required field XREF-CUST-ID[16,25) UINT");
    }

    /**
     * Proves one export account image dispatches all five storage regimes to their owning codecs.
     *
     * <p>Assumptions: this is the one record in the corpus that exercises every regime at once, and it is
     * the record that makes the usage-over-picture rule observable, because three of its fields carry the
     * identical picture {@code PIC S9(10)V99} at three different physical widths. The three widths are the
     * whole proof: 7 bytes for the packed balance at {@code app/cpy/CVEXPORT.cpy} line 50, 12 for the
     * zoned credit limit at line 51 and 8 for the binary cycle debit at line 57. USAGE and not PICTURE
     * fixes the width, and the longest usage token has to be recognised first because the packed spelling
     * CONTAINS the binary spelling -- matching the shorter one first would read the two packed fields as
     * eight bytes each and displace every field after them while still producing digits.</p>
     *
     * <p>Assumptions: the values asserted here are the exact decoded quantities, and the byte order and
     * the sign conventions that produce them are owned by the sibling packed and zoned suites. What is
     * proven in this file is the composition -- that one record image reaches five different decoders,
     * each at its own interval, and re-encodes byte for byte.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void oneExportAccountImageDispatchesAllFiveStorageRegimes() {
        RecordSpec branch = exportAccountBranch();
        byte[] image = exportAccountBranchImage();
        assertEquals(460, image.length);

        assertEquals(List.of(Kind.UINT, Kind.TEXT, Kind.PACKED, Kind.ZONED, Kind.PACKED, Kind.TEXT,
                Kind.TEXT, Kind.TEXT, Kind.ZONED, Kind.BINARY, Kind.TEXT, Kind.TEXT, Kind.TEXT),
                branch.fields().stream().map(FieldSpec::kind).toList(),
                "the export account branch must keep all five regimes in declaration order");

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image, branch);
        assertEquals(Long.valueOf(2L), decoded.get("EXP-ACCT-ID"));
        assertEquals("Y", decoded.get("EXP-ACCT-ACTIVE-STATUS"));
        assertEquals(new BigDecimal("-158.00"), decoded.get("EXP-ACCT-CURR-BAL"));
        assertEquals(new BigDecimal("6130.00"), decoded.get("EXP-ACCT-CREDIT-LIMIT"));
        assertEquals(new BigDecimal("5448.00"), decoded.get("EXP-ACCT-CASH-CREDIT-LIMIT"));
        assertEquals("2024-08-11", decoded.get("EXP-ACCT-EXPIRAION-DATE"));
        assertEquals(new BigDecimal("0.00"), decoded.get("EXP-ACCT-CURR-CYC-CREDIT"));
        assertEquals(new BigDecimal("-42.75"), decoded.get("EXP-ACCT-CURR-CYC-DEBIT"));
        assertEquals("A000000000", decoded.get("EXP-ACCT-ADDR-ZIP"));

        // WHY : Assumptions: the two packed values and the binary value are exact fixed point at the scale
        //       their pictures declare, which is what a byte-for-byte parity comparison of money requires.
        //       Asserting the scale separately is what would catch a value that compared equal in
        //       magnitude while carrying the wrong number of decimal places.
        assertEquals(2, ((BigDecimal) decoded.get("EXP-ACCT-CURR-BAL")).scale());
        assertEquals(2, ((BigDecimal) decoded.get("EXP-ACCT-CURR-CYC-DEBIT")).scale());

        byte[] reEncoded = FixedWidthCodec.encodeRecord(decoded, branch);
        assertEquals(-1, firstDifference(image, reEncoded),
                "the export account branch must re-encode byte for byte");
    }

    /**
     * Proves the three export account regimes occupy the widths their declared usages imply.
     *
     * <p>Assumptions: the three widths are stated here as a table so a reviewer can check them against
     * {@code app/cpy/CVEXPORT.cpy} lines 50, 51 and 57 without decoding anything, and so the branch's own
     * closure at 460 bytes is visible as the consequence. Substituting eight bytes for the seven-byte
     * packed fields would make the branch sum to 462, which no dataset carries.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void theThreeExportAccountRegimesOccupyTheWidthsTheirUsagesImply() {
        RecordSpec branch = exportAccountBranch();

        assertEquals(7, branch.field("EXP-ACCT-CURR-BAL").length());
        assertEquals(Kind.PACKED, branch.field("EXP-ACCT-CURR-BAL").kind());
        assertEquals(12, branch.field("EXP-ACCT-CREDIT-LIMIT").length());
        assertEquals(Kind.ZONED, branch.field("EXP-ACCT-CREDIT-LIMIT").kind());
        assertEquals(8, branch.field("EXP-ACCT-CURR-CYC-DEBIT").length());
        assertEquals(Kind.BINARY, branch.field("EXP-ACCT-CURR-CYC-DEBIT").kind());

        for (String name : List.of("EXP-ACCT-CURR-BAL", "EXP-ACCT-CREDIT-LIMIT",
                "EXP-ACCT-CURR-CYC-DEBIT")) {
            FieldSpec field = branch.field(name);
            assertEquals(10, field.intDigits(), () -> name + " changed its integer digit positions");
            assertEquals(2, field.decDigits(), () -> name + " changed its decimal digit positions");
            assertEquals(CopybookLayout.widthOf(field.kind(), 10, 2), field.length(),
                    () -> name + " no longer occupies the width its usage implies");
        }
        assertEquals(460, branch.reclen(),
                "the branch closes at 460 only because the packed fields are seven bytes each");
    }

    /**
     * Proves packed and binary bytes are dispatched on their declared kind before any character decoding.
     *
     * <p>Assumptions: the probe is chosen so that the two paths cannot be confused. The binary span is the
     * two's complement of a negative value, so its leading bytes are the ones no single-byte character set
     * maps reversibly, and the packed span carries a sign nibble byte in the same position. Decoding
     * either span as text is therefore refused outright, while decoding it under its declared kind yields
     * an exact value. The asymmetry is the proof: if the codec decoded characters first and interpreted
     * afterwards, the successful case would be impossible.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.FieldCodecException} from each text-declared decode.</p>
     */
    @Test
    void packedAndBinaryBytesNeverEnterACharacterDecoder() {
        byte[] binarySpan = bytesOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0x4D);
        assertEquals(new BigDecimal("-42.75"), FixedWidthCodec.decodeField(binarySpan,
                CopybookLayout.binary("EXP-ACCT-CURR-CYC-DEBIT", 0, 10, 2, true)));
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(binarySpan,
                        CopybookLayout.text("SAME-SPAN-AS-TEXT", 0, 8))))
                .hasMessageContaining("character decoding is not byte-reversible");

        byte[] packedSpan = bytesOf(0x00, 0x00, 0x00, 0x00, 0x15, 0x80, 0x0D);
        assertEquals(new BigDecimal("-158.00"), FixedWidthCodec.decodeField(packedSpan,
                CopybookLayout.packed("EXP-ACCT-CURR-BAL", 0, 10, 2, true)));
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(packedSpan,
                        CopybookLayout.text("SAME-SPAN-AS-TEXT", 0, 7))))
                .hasMessageContaining("character decoding is not byte-reversible");

        // WHY : Assumptions: the charset argument is ignored by the two computational regimes rather than
        //       validated, which is the observable consequence of dispatching on the kind first. Passing a
        //       charset the codec refuses for character fields, and getting the exact value anyway, is the
        //       strongest available statement that those bytes never reach a decoder at all.
        assertEquals(new BigDecimal("-42.75"), FixedWidthCodec.decodeField(binarySpan,
                CopybookLayout.binary("EXP-ACCT-CURR-CYC-DEBIT", 0, 10, 2, true),
                StandardCharsets.UTF_8));
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField("ABCD".getBytes(ASCII),
                        CopybookLayout.text("PROBE", 0, 4), StandardCharsets.UTF_8)))
                .hasMessageContaining("unsupported fixed-width charset");
    }

    /**
     * Proves a malformed numeric span is refused by its owning codec and surfaces with that cause.
     *
     * <p>Assumptions: the delegation is proven by the CAUSE and not by the message. A composition layer
     * that reimplemented the overpunch table or the nibble rules would report a refusal of its own making,
     * so a refusal whose cause is the delegate's own exception type is the evidence that the delegate did
     * the work. The four spans below are each refused by a different rule -- an unrecognised trailing
     * character in a display field, a non-zero leading pad nibble, a digit where a sign nibble must be, and
     * a stored whole number wider than its declared integer positions -- and which rule each one breaks is
     * the delegate's business rather than this file's, which is why only the cause type and the geometry in
     * the wrapper are asserted here.</p>
     *
     * <p>Assumptions: the redacted variant drops the cause entirely rather than merely rewording the outer
     * message. A retained cause would carry the offending characters into a stack trace even with the outer
     * message cleaned, so the cause has to be absent and not just quiet.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.FieldCodecException} from each refused span and inspects its cause, which is a
     * {@link ZonedDecimalCodec.ZonedDecimalException} for the display span and a
     * {@link PackedDecimalCodec.PackedDecimalException} for the three computational spans.</p>
     */
    @Test
    void aMalformedNumericSpanIsRefusedByItsOwningCodecAndSurfacesWithThatCause() {
        FieldCodecException zonedRefusal = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField("0000005047X".getBytes(ASCII),
                        CopybookLayout.signedZoned("TRAN-AMT", 0, 9, 2)));
        assertThat(zonedRefusal.getMessage()).contains("TRAN-AMT").contains("kind ZONED");
        assertThat(zonedRefusal.getCause()).isInstanceOf(ZonedDecimalException.class);

        FieldSpec packedBalance = CopybookLayout.packed("EXP-ACCT-CURR-BAL", 0, 10, 2, true);
        FieldCodecException padRefusal = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(
                        bytesOf(0x10, 0x00, 0x00, 0x00, 0x15, 0x80, 0x0D), packedBalance));
        assertThat(padRefusal.getMessage()).contains("kind PACKED");
        assertThat(padRefusal.getCause()).isInstanceOf(PackedDecimalException.class);

        FieldCodecException signRefusal = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(
                        bytesOf(0x00, 0x00, 0x00, 0x00, 0x15, 0x80, 0x05), packedBalance));
        assertThat(signRefusal.getCause()).isInstanceOf(PackedDecimalException.class);

        FieldCodecException binaryRefusal = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField(bytesOf(0xFF, 0xFF),
                        CopybookLayout.binary("EXP-CARD-CVV-CD", 0, 3, 0, false)));
        assertThat(binaryRefusal.getMessage()).contains("kind BINARY");
        assertThat(binaryRefusal.getCause()).isInstanceOf(PackedDecimalException.class);

        FieldSpec sensitiveAmount = new FieldSpec("TRAN-AMT", 0, 11, Kind.ZONED, 9, 2, true, false,
                true);
        FieldCodecException redacted = assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField("0000005047X".getBytes(ASCII), sensitiveAmount));
        assertThat(redacted.getMessage())
                .contains("field content omitted because it is sensitive")
                .doesNotContain("0000005047X");
        assertThat(redacted.getCause()).isNull();
    }

    /**
     * Proves the character-backed regimes preserve declared-width text and blank semantics.
     *
     * <p>Assumptions: a character field's declared width is part of its contract, so its trailing blanks
     * are storage rather than noise and must survive a decode unchanged. The unsigned display regime is
     * the mirror image: it accepts only its declared span of digits, refuses anything else in it, and
     * restores the leading zeros on encode that the integral value it decodes to cannot carry. That last
     * property is what lets an eleven-digit identifier whose value is two round-trip to
     * {@code 00000000002} rather than to a two followed by blanks.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.FieldCodecException} from each refused unsigned value.</p>
     */
    @Test
    void characterAndUnsignedRegimesPreserveTheirDeclaredWidthSemantics() {
        FieldSpec name = CopybookLayout.text("CARD-EMBOSSED-NAME", 0, 50);
        byte[] nameSpan = padded("Carter Veum", 50).getBytes(ASCII);
        assertEquals(padded("Carter Veum", 50), FixedWidthCodec.decodeField(nameSpan, name));

        byte[] target = new byte[50];
        FixedWidthCodec.encodeField("Carter Veum", name, target);
        assertArrayEquals(nameSpan, target, "text must be left-aligned and blank-filled to its width");

        FieldSpec accountId = CopybookLayout.uint("ACCT-ID", 0, 11);
        assertEquals(Long.valueOf(2L),
                FixedWidthCodec.decodeField("00000000002".getBytes(ASCII), accountId));
        byte[] identifier = new byte[11];
        FixedWidthCodec.encodeField(Long.valueOf(2L), accountId, identifier);
        assertEquals("00000000002", new String(identifier, ASCII),
                "an unsigned display field must be restored zero-filled on the left");

        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.decodeField("0000000000 ".getBytes(ASCII), accountId)))
                .hasMessageContaining("non-digit at relative offset 10");
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.encodeField(Long.valueOf(-1L), accountId, identifier)))
                .hasMessageContaining("cannot hold a negative value");
        assertThat(assertThrows(FieldCodecException.class,
                () -> FixedWidthCodec.encodeField(Long.valueOf(123456789012L), accountId, identifier)))
                .hasMessageContaining("needs 12 digits but the field holds 11");
    }

    /**
     * Proves an all-blank twenty-six-byte timestamp is accepted and restored to twenty-six blanks.
     *
     * <p>Assumptions: twenty-six blanks in a processing stamp is REAL DATA and not corruption. A daily
     * transaction has not been posted, so no wall-clock stamp has been written into it, and the committed
     * posting fixture carries exactly that value at
     * {@code tests/fixtures/posting/happy_path/dailytran.txt}. Refusing it would turn an unwritten stamp
     * into invalid input and stop the posting run on its own input. The absent state is represented as an
     * empty value rather than as a missing key, and encoding it restores all twenty-six blanks, so the
     * round trip is byte-identical.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void anAllBlankTimestampIsAcceptedAndRestoredToTwentySixBlanks() {
        FieldSpec processing = CopybookLayout.layout("TRAN").field("TRAN-PROC-TS");
        assertEquals(26, processing.length());
        assertTrue(processing.normalizeTs());

        byte[] blankSpan = " ".repeat(26).getBytes(ASCII);
        assertEquals("", FixedWidthCodec.decodeField(blankSpan,
                CopybookLayout.normalizedTimestamp("TRAN-PROC-TS", 0, 26)));

        byte[] target = new byte[26];
        FixedWidthCodec.encodeField("", CopybookLayout.normalizedTimestamp("TRAN-PROC-TS", 0, 26),
                target);
        assertArrayEquals(blankSpan, target, "the absent stamp must restore all twenty-six blanks");

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(
                dailyTransactionImage().getBytes(ASCII), CopybookLayout.layout("DALYTRAN"));
        assertEquals("", decoded.get("DALYTRAN-PROC-TS"));
        assertThat(decoded)
                .as("the absent stamp is an empty value under its own key, not a missing key")
                .containsKey("DALYTRAN-PROC-TS");
    }

    /**
     * Proves a populated twenty-six-character timestamp round-trips character for character.
     *
     * <p>Assumptions: the codec reads a timestamp faithfully and never reformats one. The marking that a
     * field holds a run-generated stamp is parity policy for the comparison step and grants this codec no
     * licence to render a value the module's timestamp formatter owns. The value used here is the exact
     * originating stamp the committed transaction fixture carries, whose six fractional digits are what
     * make the form twenty-six characters wide.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void aPopulatedTimestampRoundTripsCharacterForCharacter() {
        String stamp = "2022-06-10 19:27:53.000000";
        assertEquals(26, stamp.length());

        FieldSpec originating = CopybookLayout.text("TRAN-ORIG-TS", 0, 26);
        assertEquals(stamp, FixedWidthCodec.decodeField(stamp.getBytes(ASCII), originating));

        byte[] target = new byte[26];
        FixedWidthCodec.encodeField(stamp, originating, target);
        assertArrayEquals(stamp.getBytes(ASCII), target);

        FieldSpec markedForNormalisation = CopybookLayout.normalizedTimestamp("TRAN-PROC-TS", 0, 26);
        assertEquals(stamp, FixedWidthCodec.decodeField(stamp.getBytes(ASCII), markedForNormalisation),
                "a marked field is read exactly as an unmarked one is");
    }

    /**
     * Proves the two diagnostic flags mark fields without altering the bytes they describe.
     *
     * <p>Assumptions: both flags are markers and neither performs an action here. The normalisation
     * marking tells the parity comparison which stamps to blank before comparing, and rendering belongs to
     * the module's timestamp formatter. The sensitivity marking keeps content out of diagnostics, and
     * domain masking belongs to the service mappers -- applying a mask in the codec would make an encode
     * of a decoded record reproduce different bytes and destroy the byte-identity property the parity
     * comparison depends on. The proof is that a marked and an unmarked field of the same geometry decode
     * to the same value.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void theTwoDiagnosticFlagsMarkFieldsWithoutAlteringTheirBytes() {
        String pan = "0927987108636232";
        byte[] span = pan.getBytes(ASCII);

        FieldSpec marked = CopybookLayout.sensitiveText("CARD-NUM", 0, 16);
        FieldSpec unmarked = CopybookLayout.text("CARD-NUM", 0, 16);
        assertTrue(marked.sensitive());
        assertFalse(unmarked.sensitive());
        assertEquals(FixedWidthCodec.decodeField(span, unmarked),
                FixedWidthCodec.decodeField(span, marked),
                "a sensitivity marking must not change the decoded value");
        assertEquals(pan, FixedWidthCodec.decodeField(span, marked));

        byte[] target = new byte[16];
        FixedWidthCodec.encodeField(pan, marked, target);
        assertArrayEquals(span, target, "a sensitivity marking must not mask on the way out");

        assertTrue(CopybookLayout.layout("CARD").field("CARD-NUM").sensitive());
        assertTrue(CopybookLayout.layout("CARD").field("CARD-CVV-CD").sensitive());
        assertTrue(CopybookLayout.layout("CUSTOMER").field("CUST-SSN").sensitive());
        assertTrue(CopybookLayout.layout("CUSTOMER").field("CUST-GOVT-ISSUED-ID").sensitive());
        assertFalse(CopybookLayout.layout("CARD").field("CARD-ACTIVE-STATUS").sensitive());
    }

    /**
     * Proves low values and spaces decode to two distinct values rather than collapsing to one.
     *
     * <p>Assumptions: {@code app/cpy/CVCRD01Y.cpy} declares two adjacent {@code PIC X(75)} message fields
     * at its lines 28 and 29, and attaches {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} to the second
     * at line 30. The absent state of that field is therefore a span of low values while an empty message
     * in the first is a span of blanks, and the two states mean different things to the program that reads
     * them. A codec that trimmed both to an empty result would report an unset field and a cleared field
     * identically, so the distinction is asserted at the value level and again as byte identity across a
     * round trip.</p>
     *
     * <p>Assumptions: the numeric redefinitions in the same copybook, at its lines 36, 39 and 42, ALIAS
     * the character fields they redefine and add no storage, so the descriptor built here declares only
     * the two character fields. Counting the aliases as storage would make the record wider than the
     * program's own working area.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void lowValuesAndSpacesDecodeToTwoDistinctValues() {
        RecordSpec messagePair = new RecordSpec("CC-MESSAGE-PAIR", 150, 75, 0, List.of(
                CopybookLayout.text("CCARD-ERROR-MSG", 0, 75),
                CopybookLayout.text("CCARD-RETURN-MSG", 75, 75))).validateGeometry();

        byte[] image = new byte[150];
        Arrays.fill(image, 0, 75, (byte) ' ');
        Arrays.fill(image, 75, 150, (byte) 0);

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image, messagePair);
        String blanks = (String) decoded.get("CCARD-ERROR-MSG");
        String lowValues = (String) decoded.get("CCARD-RETURN-MSG");

        assertEquals(75, blanks.length(), "a blank message keeps its declared width");
        assertEquals(75, lowValues.length(), "a low-values message keeps its declared width");
        assertNotEquals(blanks, lowValues, "the two sentinel states must not collapse to one value");
        assertEquals(' ', blanks.charAt(0));
        assertEquals(0, lowValues.charAt(0), "a low-values span decodes to the low character itself");
        assertThat(blanks).isBlank();
        assertThat(lowValues).isNotBlank();
        assertNotEquals("", blanks, "neither sentinel is trimmed away to an empty value");
        assertNotEquals("", lowValues);

        byte[] reEncoded = FixedWidthCodec.encodeRecord(decoded, messagePair);
        assertEquals(-1, firstDifference(image, reEncoded),
                "both sentinel spans must re-encode byte for byte");
    }


    /**
     * Proves the 300-byte account record decodes and re-encodes byte for byte.
     *
     * <p>Assumptions: the image is the first row of {@code tests/fixtures/export/happy_path/acctdata.txt}
     * laid out against {@code app/cpy/CVACT01Y.cpy} lines 5 to 17. The misspelled expiration date at
     * copybook line 11 is carried over as declared, because AAP Rule T1 makes the copybook normative for
     * field names and the documented target renaming belongs to the persistence mapping rather than to a
     * layout descriptor.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void accountRecordRoundTripsByteIdentically() {
        Map<String, Object> decoded = decodeAndProveByteIdentity("ACCOUNT", accountImage());

        assertEquals(Long.valueOf(2L), decoded.get("ACCT-ID"));
        assertEquals("Y", decoded.get("ACCT-ACTIVE-STATUS"));
        assertEquals(new BigDecimal("158.00"), decoded.get("ACCT-CURR-BAL"));
        assertEquals(new BigDecimal("6130.00"), decoded.get("ACCT-CREDIT-LIMIT"));
        assertEquals(new BigDecimal("5448.00"), decoded.get("ACCT-CASH-CREDIT-LIMIT"));
        assertEquals("2024-08-11", decoded.get("ACCT-EXPIRAION-DATE"));
        assertEquals(new BigDecimal("0.00"), decoded.get("ACCT-CURR-CYC-CREDIT"));
        assertEquals("A000000000", decoded.get("ACCT-ADDR-ZIP"));

        // WHY : Assumptions: the group identifier at copybook line 16 holds ten blanks in this row, and
        //       that is a blank VALUE in a named field rather than padding. It has to survive the decode,
        //       because the interest run reads it as the disclosure-group key and a missing key is what
        //       triggers the documented default-group fallback -- a dropped field would instead look like
        //       a malformed record.
        assertEquals(" ".repeat(10), decoded.get("ACCT-GROUP-ID"));
        assertThat(decoded).doesNotContainKey("FILLER");
    }

    /**
     * Proves the 150-byte card record round-trips and keeps a leading-zero card number as characters.
     *
     * <p>Assumptions: the image is the fourth row of {@code tests/fixtures/export/happy_path/carddata.txt}
     * laid out against {@code app/cpy/CVACT02Y.cpy} lines 5 to 11. That row is chosen because its card
     * number is {@code 0927987108636232}, a REAL identifier whose leading digit is a zero. The copybook
     * declares the field as sixteen characters and not as a number, and the distinction is load-bearing: a
     * numeric reading would carry the value 927987108636232 and re-emit fifteen digits, which is a
     * different card.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void cardRecordRoundTripsAndKeepsALeadingZeroCardNumberAsCharacters() {
        String image = "0927987108636232"
                + "00000000020"
                + "003"
                + padded("Carter Veum", 50)
                + "2024-03-13"
                + "Y"
                + " ".repeat(59);
        Map<String, Object> decoded = decodeAndProveByteIdentity("CARD", image);

        Object cardNumber = decoded.get("CARD-NUM");
        assertThat(cardNumber).isInstanceOf(String.class);
        assertEquals("0927987108636232", cardNumber);
        assertEquals(16, ((String) cardNumber).length(), "the leading zero is part of the identifier");
        assertEquals(Kind.TEXT, CopybookLayout.layout("CARD").field("CARD-NUM").kind());

        assertEquals(Long.valueOf(20L), decoded.get("CARD-ACCT-ID"));
        assertEquals(Long.valueOf(3L), decoded.get("CARD-CVV-CD"));
        assertEquals(padded("Carter Veum", 50), decoded.get("CARD-EMBOSSED-NAME"));
        assertEquals("2024-03-13", decoded.get("CARD-EXPIRAION-DATE"));
        assertThat(decoded).doesNotContainKey("FILLER");
    }

    /**
     * Proves the 500-byte customer record decodes and re-encodes byte for byte.
     *
     * <p>Assumptions: the image is the first row of {@code tests/fixtures/export/happy_path/custdata.txt}
     * laid out against {@code app/cpy/CVCUS01Y.cpy} lines 5 to 23. Two of its values are worth naming
     * because they look like the wrong regime. The government-issued identifier at copybook line 18 is
     * twenty CHARACTERS that happen to be all digits, and the national identifier at line 17 is nine
     * digits declared as an unsigned display number; the first keeps its leading zeros because it is
     * characters and the second is restored zero-filled because the codec pads an unsigned display field
     * on the left.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void customerRecordRoundTripsByteIdentically() {
        String image = "000000002"
                + padded("Enrico", 25)
                + padded("April", 25)
                + padded("Rosenbaum", 25)
                + padded("4917 Myrna Flats", 50)
                + padded("Apt. 453", 50)
                + padded("West Bernita", 50)
                + "IN"
                + "USA"
                + padded("22770", 10)
                + padded("(429)706-9510", 15)
                + padded("(744)950-5272", 15)
                + "587518382"
                + "00000000000506210371"
                + "1961-10-08"
                + "0069194009"
                + "Y"
                + "268"
                + " ".repeat(168);
        Map<String, Object> decoded = decodeAndProveByteIdentity("CUSTOMER", image);

        assertEquals(Long.valueOf(2L), decoded.get("CUST-ID"));
        assertEquals(padded("Enrico", 25), decoded.get("CUST-FIRST-NAME"));
        assertEquals("IN", decoded.get("CUST-ADDR-STATE-CD"));
        assertEquals("USA", decoded.get("CUST-ADDR-COUNTRY-CD"));
        assertEquals(padded("(429)706-9510", 15), decoded.get("CUST-PHONE-NUM-1"));
        assertEquals(Long.valueOf(587518382L), decoded.get("CUST-SSN"));
        assertEquals("00000000000506210371", decoded.get("CUST-GOVT-ISSUED-ID"));
        assertEquals(Long.valueOf(268L), decoded.get("CUST-FICO-CREDIT-SCORE"));
        assertThat(decoded).doesNotContainKey("FILLER");
    }

    /**
     * Proves the 50-byte card cross-reference record decodes and re-encodes byte for byte.
     *
     * <p>Assumptions: the image is the fourth row of
     * {@code tests/fixtures/export/happy_path/cardxref.txt} laid out against
     * {@code app/cpy/CVACT03Y.cpy} lines 5 to 8. The full-width export row is used rather than the
     * corresponding row of {@code app/data/ASCII/cardxref.txt}, which omits its trailing padding and is
     * therefore only 36 characters long; that shorter form is asserted separately to fail strict
     * decoding.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void crossReferenceRecordRoundTripsByteIdentically() {
        String image = "0927987108636232" + "000000020" + "00000000020" + " ".repeat(14);
        Map<String, Object> decoded = decodeAndProveByteIdentity("XREF", image);

        assertEquals("0927987108636232", decoded.get("XREF-CARD-NUM"));
        assertEquals(Long.valueOf(20L), decoded.get("XREF-CUST-ID"));
        assertEquals(Long.valueOf(20L), decoded.get("XREF-ACCT-ID"));
        assertThat(decoded).doesNotContainKey("FILLER");
        assertEquals(3, decoded.size(), "the trailing blank padding is not a domain value");
    }

    /**
     * Proves the 350-byte daily-transaction record decodes and re-encodes byte for byte.
     *
     * <p>Assumptions: the image is the single row of
     * {@code tests/fixtures/posting/happy_path/dailytran.txt} laid out against
     * {@code app/cpy/CVTRA06Y.cpy} lines 5 to 18. Its processing stamp is twenty-six blanks because the
     * row has not been posted, and its amount span ends in the positive overpunch for the digit seven, so
     * the row exercises both an absent stamp and a signed money value in one record.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void dailyTransactionRecordRoundTripsByteIdentically() {
        Map<String, Object> decoded = decodeAndProveByteIdentity("DALYTRAN", dailyTransactionImage());

        assertEquals("0000000000683580", decoded.get("DALYTRAN-ID"));
        assertEquals("01", decoded.get("DALYTRAN-TYPE-CD"));
        assertEquals(Long.valueOf(1L), decoded.get("DALYTRAN-CAT-CD"));
        assertEquals(new BigDecimal("504.77"), decoded.get("DALYTRAN-AMT"));
        assertEquals(Long.valueOf(800000000L), decoded.get("DALYTRAN-MERCHANT-ID"));
        assertEquals(padded("72112", 10), decoded.get("DALYTRAN-MERCHANT-ZIP"));
        assertEquals("4859452612877065", decoded.get("DALYTRAN-CARD-NUM"));
        assertEquals("2022-06-10 19:27:53.000000", decoded.get("DALYTRAN-ORIG-TS"));
        assertEquals("", decoded.get("DALYTRAN-PROC-TS"));
        assertThat(decoded).doesNotContainKey("FILLER");
    }

    /**
     * Proves the 350-byte transaction record round-trips and keeps an alphanumeric postal code.
     *
     * <p>Assumptions: the image is the third row of
     * {@code tests/fixtures/export/happy_path/trandata.txt} laid out against
     * {@code app/cpy/CVTRA05Y.cpy} lines 5 to 18. That row is chosen for its postal code
     * {@code 78487-7965}, which is an alphanumeric nine-digit code with an embedded hyphen in a
     * {@code PIC X(10)} field. The other rows of the same fixture hold five digits and five blanks in the
     * same field, so none of them would show that the field is character data rather than a number stored
     * as text -- and a numeric reading of this row would refuse the hyphen outright.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void transactionRecordRoundTripsAndKeepsAnAlphanumericPostalCode() {
        Map<String, Object> decoded = decodeAndProveByteIdentity("TRAN", transactionImage());

        Object postalCode = decoded.get("TRAN-MERCHANT-ZIP");
        assertThat(postalCode).isInstanceOf(String.class);
        assertEquals("78487-7965", postalCode);
        assertEquals(10, ((String) postalCode).length(), "the nine digits and the hyphen fill the field");
        assertEquals(Kind.TEXT, CopybookLayout.layout("TRAN").field("TRAN-MERCHANT-ZIP").kind());

        assertEquals("0000000006292564", decoded.get("TRAN-ID"));
        assertEquals(new BigDecimal("67.88"), decoded.get("TRAN-AMT"));
        assertEquals("6009619150674526", decoded.get("TRAN-CARD-NUM"));
        assertEquals("2022-06-10 19:27:53.000000", decoded.get("TRAN-ORIG-TS"));
        assertEquals("", decoded.get("TRAN-PROC-TS"));
        assertEquals(padded("Purchase at Ernser, Roob and Gleason", 100), decoded.get("TRAN-DESC"));
    }

    /**
     * Proves the 50-byte disclosure-group record decodes and re-encodes byte for byte.
     *
     * <p>Assumptions: the image is the first row of
     * {@code tests/fixtures/interest/default_fallback/discgrp.txt} laid out against
     * {@code app/cpy/CVTRA02Y.cpy} lines 5 to 10. That row carries the group identifier
     * {@code DEFAULT} padded to ten characters, which is the row the interest run falls back to when an
     * account's own disclosure-group key is not found. Its trailing padding is filled with the CHARACTER
     * zero rather than with blanks, which is the fill regime of the four reference-data records.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void disclosureGroupRecordRoundTripsByteIdentically() {
        String image = padded("DEFAULT", 10) + "01" + "0001" + "00150{" + "0".repeat(28);
        Map<String, Object> decoded = decodeAndProveByteIdentity("DISGROUP", image);

        assertEquals(padded("DEFAULT", 10), decoded.get("DIS-ACCT-GROUP-ID"));
        assertEquals("01", decoded.get("DIS-TRAN-TYPE-CD"));
        assertEquals(Long.valueOf(1L), decoded.get("DIS-TRAN-CAT-CD"));
        assertEquals(new BigDecimal("15.00"), decoded.get("DIS-INT-RATE"));
        assertEquals(2, ((BigDecimal) decoded.get("DIS-INT-RATE")).scale(),
                "the interest rate carries the two decimal positions its picture declares");

        // WHY : Assumptions: the trailing filler of this record is twenty-eight CHARACTER zeros, not
        //       blanks, so it is content by the codec's own rule and is retained. That is the whole point
        //       of asserting it: a codec that dropped every field named as filler would discard bytes the
        //       reference row genuinely holds and re-emit them as blanks, which the reference programs read
        //       as non-numeric.
        assertEquals("0".repeat(28), decoded.get("FILLER"));
    }

    /**
     * Proves the 50-byte transaction-category-balance record decodes and re-encodes byte for byte.
     *
     * <p>Assumptions: the image is the single row of
     * {@code tests/fixtures/posting/happy_path/tcatbal.txt} laid out against
     * {@code app/cpy/CVTRA01Y.cpy} lines 5 to 10. Its first three fields are the elementary leaves of the
     * grouped key, and the balance that follows them begins at zero-based 17, which
     * {@code app/jcl/PRTCATBL.jcl} line 50 confirms independently by declaring it at one-based position
     * 18.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void categoryBalanceRecordRoundTripsByteIdentically() {
        String image = "00000000007" + "01" + "0001" + "0000001000{" + "0".repeat(22);
        Map<String, Object> decoded = decodeAndProveByteIdentity("TCATBAL", image);

        assertEquals(Long.valueOf(7L), decoded.get("TRANCAT-ACCT-ID"));
        assertEquals("01", decoded.get("TRANCAT-TYPE-CD"));
        assertEquals(Long.valueOf(1L), decoded.get("TRANCAT-CD"));
        assertEquals(new BigDecimal("100.00"), decoded.get("TRAN-CAT-BAL"));
        assertEquals("0".repeat(22), decoded.get("FILLER"));
    }

    /**
     * Proves the 60-byte transaction-category record decodes and re-encodes byte for byte.
     *
     * <p>Trade-offs: this record is one of the three base masters the parity oracle does not register, so
     * the expectation below is derived directly from {@code app/cpy/CVTRA04Y.cpy} lines 5 to 9 and from
     * the first row of {@code app/data/ASCII/trancatg.txt}, with no independently written implementation
     * of the same layout to compare against. That is accepted rather than worked around: a second decoder
     * written here to serve as the missing oracle would reproduce the offsets under test, so one mistake in
     * each would agree. Assumptions: the ASCII source file is stored with two-character line endings, so
     * a row read as text measures 61 characters while the record itself is 60; the image below is the
     * 60-byte record with the line ending excluded.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void transactionCategoryRecordRoundTripsByteIdentically() {
        String image = "01" + "0001" + padded("Regular Sales Draft", 50) + "0000";
        assertEquals(60, image.length());
        Map<String, Object> decoded = decodeAndProveByteIdentity("TRANCAT", image);

        assertEquals("01", decoded.get("TRAN-TYPE-CD"));
        assertEquals(Long.valueOf(1L), decoded.get("TRAN-CAT-CD"));
        assertEquals(padded("Regular Sales Draft", 50), decoded.get("TRAN-CAT-TYPE-DESC"));
        assertEquals("0000", decoded.get("FILLER"));
        assertFalse(CopybookLayout.hasOracleRoundTrip("TRANCAT"));
    }

    /**
     * Proves the 60-byte transaction-type record decodes and re-encodes byte for byte.
     *
     * <p>Trade-offs: this is the second of the three base masters with no parity-oracle counterpart, so
     * the expectation is derived directly from {@code app/cpy/CVTRA03Y.cpy} lines 5 to 7 and from the first
     * row of {@code app/data/ASCII/trantype.txt}. Assumptions: its two-byte key is the narrowest in the
     * registry and its trailing filler is eight CHARACTER zeros, so the record proves both the narrow-key
     * and the zero-filled-padding cases that no other layout covers together.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void transactionTypeRecordRoundTripsByteIdentically() {
        String image = "01" + padded("Purchase", 50) + "00000000";
        assertEquals(60, image.length());
        Map<String, Object> decoded = decodeAndProveByteIdentity("TRANTYPE", image);

        assertEquals("01", decoded.get("TRAN-TYPE"));
        assertEquals(padded("Purchase", 50), decoded.get("TRAN-TYPE-DESC"));
        assertEquals("00000000", decoded.get("FILLER"));
        assertEquals(2, CopybookLayout.layout("TRANTYPE").keyLength());
        assertFalse(CopybookLayout.hasOracleRoundTrip("TRANTYPE"));
    }

    /**
     * Proves the 80-byte user-security record decodes and re-encodes byte for byte.
     *
     * <p>Trade-offs: this is the third base master with no parity-oracle counterpart, and it is also the
     * only one of the eleven with no committed ASCII row at all, so the image is constructed field by field
     * from {@code app/cpy/CSUSR01Y.cpy} lines 18 to 23. Its geometry is corroborated outside the copybook:
     * summing the six declared widths gives 80, and the dataset definition for the file declares an
     * eight-byte key and an eighty-byte record.</p>
     *
     * <p>Assumptions: the eight-byte slot the copybook declares at line 21 is filled here with an
     * obviously synthetic run of one repeated letter, and only GEOMETRY is asserted about it -- its
     * offset, its width, and that it is marked so diagnostics stay content-free. Its content is never
     * named in any assertion or message, which the byte-identity harness guarantees structurally by
     * reporting only the offset of a divergence. The target does not carry that field forward at all:
     * identity moves to a managed user pool and the target user table keeps only a subject reference, so
     * nothing here implies the slot belongs in a domain model. The descriptor carries it because the loader
     * still has to traverse those eight bytes in order to skip them.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void userSecurityRecordRoundTripsByteIdentically() {
        String image = padded("ADMIN001", 8)
                + padded("Admin", 20)
                + padded("User", 20)
                + "XXXXXXXX"
                + "A"
                + " ".repeat(23);
        assertEquals(80, image.length());
        Map<String, Object> decoded = decodeAndProveByteIdentity("SECUSER", image);

        assertEquals(padded("ADMIN001", 8), decoded.get("SEC-USR-ID"));
        assertEquals(padded("Admin", 20), decoded.get("SEC-USR-FNAME"));
        assertEquals(padded("User", 20), decoded.get("SEC-USR-LNAME"));
        assertEquals("A", decoded.get("SEC-USR-TYPE"));

        FieldSpec eightByteSlot = CopybookLayout.layout("SECUSER").field("SEC-USR-PWD");
        assertEquals(48, eightByteSlot.start());
        assertEquals(8, eightByteSlot.length());
        assertEquals(Kind.TEXT, eightByteSlot.kind());
        assertTrue(eightByteSlot.sensitive(),
                "the slot must be marked so no diagnostic can quote its content");
        assertThat(decoded).containsKey("SEC-USR-PWD");
        assertThat(decoded).doesNotContainKey("SEC-USR-FILLER");
        assertFalse(CopybookLayout.hasOracleRoundTrip("SECUSER"));
    }

    /**
     * Proves a zoned negative zero normalises to the positive overpunch across a round trip.
     *
     * <p>Assumptions: the reference dialect distinguishes the opening-brace overpunch, a POSITIVE zero,
     * from the closing-brace overpunch, a NEGATIVE zero, as two distinct bytes, and the parity oracle
     * preserves that distinction. The target's exact decimal type has no negative zero at all: a value
     * built from minus zero carries signum zero and is indistinguishable from a value built from zero. The
     * consequence is implemented and stated rather than left to be discovered -- decoding either
     * overpunched zero yields the same value, and encoding a zero always emits the opening brace. This is
     * therefore the ONE span for which decode followed by the PLAIN encode is not byte-identical, and the
     * divergence is exactly one byte at the overpunch position rather than unexplained drift. It is
     * registered under identifier D-SIGNED-ZERO-ZONED in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and the sign-preserving record encoder
     * exercised in the test below reproduces the same span with no divergence at all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void aZonedNegativeZeroNormalisesToThePositiveOverpunch() {
        RecordSpec account = CopybookLayout.layout("ACCOUNT");
        int cycleDebitStart = account.field("ACCT-CURR-CYC-DEBIT").start();
        int overpunchOffset = account.field("ACCT-CURR-CYC-DEBIT").end() - 1;
        assertEquals(90, cycleDebitStart);
        assertEquals(101, overpunchOffset);

        byte[] declared = accountImage().getBytes(ASCII);
        assertEquals('{', (char) declared[overpunchOffset],
                "the reference row stores the positive zero form");
        declared[overpunchOffset] = '}';

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(declared, account);
        BigDecimal negativeZero = (BigDecimal) decoded.get("ACCT-CURR-CYC-DEBIT");
        assertEquals(0, negativeZero.signum(), "the exact decimal type carries no negative zero");
        assertEquals(new BigDecimal("0.00"), negativeZero);
        assertEquals(2, negativeZero.scale());

        byte[] reEncoded = FixedWidthCodec.encodeRecord(decoded, account);
        assertEquals(overpunchOffset, firstDifference(declared, reEncoded),
                "the negative zero must normalise at exactly the overpunch position and nowhere else");
        assertEquals('{', (char) reEncoded[overpunchOffset]);
        assertEquals(declared.length, reEncoded.length);
    }

    /**
     * Proves a packed negative zero normalises to the positive sign nibble across a round trip.
     *
     * <p>Assumptions: the packed regime carries the same asymmetry as the zoned one and for the same
     * reason. Its sign lives in the low nibble of the last byte, where the implementation admits exactly
     * three values -- one for a signed non-negative value, one for a signed negative value and one for a
     * field declared without a sign. Because the target's exact decimal type has no negative zero, a span
     * whose digits are all zero and whose sign nibble is the negative one decodes to the same value as the
     * non-negative form and re-encodes with the non-negative nibble. The divergence is one nibble in one
     * byte, it is documented here rather than discovered, and it is the packed counterpart of the zoned
     * case above.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void aPackedNegativeZeroNormalisesToThePositiveSignNibble() {
        FieldSpec packedBalance = CopybookLayout.packed("EXP-ACCT-CURR-BAL", 0, 10, 2, true);
        byte[] negativeZeroSpan = bytesOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0D);
        assertEquals(7, negativeZeroSpan.length);

        BigDecimal decoded = (BigDecimal) FixedWidthCodec.decodeField(negativeZeroSpan, packedBalance);
        assertEquals(0, decoded.signum());
        assertEquals(new BigDecimal("0.00"), decoded);

        byte[] reEncoded = new byte[7];
        FixedWidthCodec.encodeField(decoded, packedBalance, reEncoded);
        assertArrayEquals(bytesOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C), reEncoded,
                "a zero must re-encode with the signed non-negative nibble");
        assertEquals(6, firstDifference(negativeZeroSpan, reEncoded),
                "the divergence is confined to the byte that holds the sign");

        // WHY : Assumptions: the non-negative form is byte-identical across the same round trip, which is
        //       what confines the divergence above to the negative-zero case alone rather than leaving it
        //       a property of the packed regime generally.
        byte[] positiveZeroSpan = bytesOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C);
        byte[] positiveReEncoded = new byte[7];
        FixedWidthCodec.encodeField(FixedWidthCodec.decodeField(positiveZeroSpan, packedBalance),
                packedBalance, positiveReEncoded);
        assertArrayEquals(positiveZeroSpan, positiveReEncoded);
    }

    /**
     * Proves the sign-preserving record encoder reproduces a record's signed zeroes byte for byte.
     *
     * <p>Assumptions: this is the operation a loader uses when it must write a record back exactly as it
     * read it, and it has no exception. The value map alone cannot carry the distinction between the two
     * zero overpunches, so the encoder is given the record the values were decoded from and restores the
     * carrier for exactly those fields whose value is a signed zero. The account row is used because it
     * declares two zoned zeroes at adjacent offsets, so the assertion covers both a field that was
     * altered to the negative form and one that was left positive -- an encoder that wrote the negative
     * carrier unconditionally would fail the second.</p>
     *
     * <p>Assumptions: the whole record is compared rather than the two carrier bytes alone, because the
     * property being proved is that restoring a carrier disturbs nothing else in a 300-byte record.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void theSignPreservingRecordEncoderReproducesSignedZeroesByteForByte() {
        RecordSpec account = CopybookLayout.layout("ACCOUNT");
        int cycleCreditOverpunch = account.field("ACCT-CURR-CYC-CREDIT").end() - 1;
        int cycleDebitOverpunch = account.field("ACCT-CURR-CYC-DEBIT").end() - 1;

        byte[] source = accountImage().getBytes(ASCII);
        assertEquals('{', (char) source[cycleCreditOverpunch],
                "the reference row stores the positive zero form in the cycle-credit field");
        source[cycleDebitOverpunch] = '}';

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(source, account);
        byte[] reEncoded = FixedWidthCodec.encodeRecordPreservingSign(decoded, account, source);

        assertArrayEquals(source, reEncoded,
                "the sign-preserving encoder must reproduce the source record exactly");
        assertEquals('}', (char) reEncoded[cycleDebitOverpunch],
                "the negative zero carrier must survive the round trip");
        assertEquals('{', (char) reEncoded[cycleCreditOverpunch],
                "the positive zero carrier must not be rewritten as the negative one");
    }

    /**
     * Proves the sign-preserving record encoder still rejects a source record of the wrong length.
     *
     * <p>Assumptions: the source record is read for its sign carriers by absolute offset, so a record of
     * another length would either read past its end or read a carrier from the wrong field. Refusing is
     * what keeps the operation from silently taking a byte from an unrelated position; the expected
     * exception is captured so none escapes this test.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void theSignPreservingRecordEncoderRejectsAMisSizedSourceRecord() {
        RecordSpec account = CopybookLayout.layout("ACCOUNT");
        byte[] source = accountImage().getBytes(ASCII);
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(source, account);

        RecordLengthException refusal = assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.encodeRecordPreservingSign(decoded, account, new byte[1]));

        assertThat(refusal).hasMessageContaining("sign-carrier source");
    }


    /**
     * Proves padding stays in the descriptor while blank padding leaves the decoded values.
     *
     * <p>Assumptions: the two statements are about different things and both are needed. Padding must be
     * DECLARED, because the contiguity and record-length proofs walk every byte and a skipped filler would
     * leave a gap that the sum rule would then report as a short record. Padding must be ABSENT from the
     * decoded values, because an inert blank run is not a domain value and carrying one would push it into
     * every consumer that maps a decoded record. Encoding rebuilds the omitted span from the declared
     * geometry, which is why the omission costs no bytes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void paddingStaysInTheDescriptorWhileBlankPaddingLeavesTheDecodedValues() {
        RecordSpec transaction = CopybookLayout.layout("TRAN");
        FieldSpec padding = transaction.field("FILLER");
        assertEquals(330, padding.start());
        assertEquals(20, padding.length());
        assertEquals(350, padding.end(), "the declared padding is what closes the record");

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(transactionImage().getBytes(ASCII),
                transaction);
        assertThat(decoded).doesNotContainKey("FILLER");
        assertEquals(transaction.fields().size() - 1, decoded.size(),
                "exactly one declared field is omitted from the values");

        // WHY : Assumptions: the omitted span is rebuilt with the target charset's blank byte rather than
        //       left at the fresh array's own initial bytes. Leaving those in place would preserve the
        //       record length and change the content, and the reference programs distinguish a low-value
        //       byte from a blank even though both read as empty in a decoded string.
        byte[] reEncoded = FixedWidthCodec.encodeRecord(decoded, transaction);
        assertEquals(350, reEncoded.length);
        for (int offset = padding.start(); offset < padding.end(); offset++) {
            assertEquals((byte) ' ', reEncoded[offset],
                    "the rebuilt padding must be blanks and not the array's initial bytes");
        }
    }

    /**
     * Proves the six character-padded records carry blank-filled trailing padding.
     *
     * @param layoutName the registered logical record name whose trailing padding is under test
     * @param paddingStart the zero-based offset at which that record's trailing padding begins
     * @param paddingLength the declared width of that record's trailing padding
     */
    @ParameterizedTest
    @CsvSource({
        "ACCOUNT,  122, 178",
        "CARD,      91,  59",
        "CUSTOMER, 332, 168",
        "XREF,      36,  14",
        "DALYTRAN, 330,  20",
        "TRAN,     330,  20",
    })
    void theCharacterPaddedRecordsCarryBlankFilledTrailingPadding(String layoutName, int paddingStart,
            int paddingLength) {
        // WHY : Assumptions: there is NO universal filler byte in this corpus, so the fill regime is
        //       asserted per record family rather than assumed once. These six records are blank-filled and
        //       the four reference-data records are filled with the character zero; a codec that assumed
        //       either regime for all ten would drop content in one family or emit padding the reference
        //       programs read as non-numeric in the other.
        RecordSpec spec = CopybookLayout.layout(layoutName);
        FieldSpec padding = spec.field("FILLER");
        assertEquals(paddingStart, padding.start(), () -> layoutName + " padding moved");
        assertEquals(paddingLength, padding.length(), () -> layoutName + " padding changed width");
        assertEquals(spec.reclen(), padding.end(), () -> layoutName + " padding is not the final field");
        assertEquals(Kind.TEXT, padding.kind());

        String image = switch (layoutName) {
            case "ACCOUNT" -> accountImage();
            case "CARD" -> "0927987108636232" + "00000000020" + "003" + padded("Carter Veum", 50)
                    + "2024-03-13" + "Y" + " ".repeat(59);
            case "CUSTOMER" -> "000000002" + padded("Enrico", 25) + padded("April", 25)
                    + padded("Rosenbaum", 25) + padded("4917 Myrna Flats", 50) + padded("Apt. 453", 50)
                    + padded("West Bernita", 50) + "IN" + "USA" + padded("22770", 10)
                    + padded("(429)706-9510", 15) + padded("(744)950-5272", 15) + "587518382"
                    + "00000000000506210371" + "1961-10-08" + "0069194009" + "Y" + "268"
                    + " ".repeat(168);
            case "XREF" -> "0927987108636232" + "000000020" + "00000000020" + " ".repeat(14);
            case "DALYTRAN" -> dailyTransactionImage();
            default -> transactionImage();
        };
        assertEquals(" ".repeat(paddingLength), image.substring(paddingStart, spec.reclen()),
                () -> "the committed row for " + layoutName + " is not blank-padded");

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image.getBytes(ASCII), spec);
        assertThat(decoded)
                .as("blank padding is omitted from the decoded values of %s", layoutName)
                .doesNotContainKey("FILLER");
        assertEquals(-1, firstDifference(image.getBytes(ASCII),
                FixedWidthCodec.encodeRecord(decoded, spec)),
                () -> "the rebuilt padding of " + layoutName + " is not byte-identical");
    }

    /**
     * Proves the four reference-data records carry character-zero padding that is retained as content.
     *
     * @param layoutName the registered logical record name whose trailing padding is under test
     * @param paddingStart the zero-based offset at which that record's trailing padding begins
     * @param paddingLength the declared width of that record's trailing padding
     */
    @ParameterizedTest
    @CsvSource({
        "DISGROUP,  22, 28",
        "TRANCAT,   56,  4",
        "TRANTYPE,  52,  8",
        "TCATBAL,   28, 22",
    })
    void theReferenceDataRecordsCarryCharacterZeroPaddingRetainedAsContent(String layoutName,
            int paddingStart, int paddingLength) {
        // WHY : Assumptions: the drop rule is content-based and not name-based, which is precisely why
        //       these four records keep their padding. A run of character zeros is not blank, so it is
        //       content by the codec's own rule and is retained; dropping it would re-emit blanks in its
        //       place, and the reference programs read a blank numeric span as non-numeric.
        RecordSpec spec = CopybookLayout.layout(layoutName);
        FieldSpec padding = spec.field("FILLER");
        assertEquals(paddingStart, padding.start(), () -> layoutName + " padding moved");
        assertEquals(paddingLength, padding.length(), () -> layoutName + " padding changed width");
        assertEquals(spec.reclen(), padding.end());

        String image = switch (layoutName) {
            case "DISGROUP" -> padded("DEFAULT", 10) + "01" + "0001" + "00150{" + "0".repeat(28);
            case "TRANCAT" -> "01" + "0001" + padded("Regular Sales Draft", 50) + "0000";
            case "TRANTYPE" -> "01" + padded("Purchase", 50) + "00000000";
            default -> "00000000007" + "01" + "0001" + "0000001000{" + "0".repeat(22);
        };
        assertEquals("0".repeat(paddingLength), image.substring(paddingStart, spec.reclen()),
                () -> "the committed row for " + layoutName + " is not zero-padded");

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image.getBytes(ASCII), spec);
        assertEquals("0".repeat(paddingLength), decoded.get("FILLER"),
                () -> "the character-zero padding of " + layoutName + " must be retained as content");
        assertEquals(-1, firstDifference(image.getBytes(ASCII),
                FixedWidthCodec.encodeRecord(decoded, spec)),
                () -> "the retained padding of " + layoutName + " is not byte-identical");
    }

    /**
     * Proves an explicitly named trailing filler is still recognised as padding.
     *
     * <p>Assumptions: padding is not always anonymous. {@code app/cpy/CSUSR01Y.cpy} line 23 declares
     * {@code 05 SEC-USR-FILLER PIC X(23).}, which is padding in ROLE despite carrying a name, so a rule
     * that recognised only the anonymous spelling would carry twenty-three inert blanks into every decoded
     * user record. The name is kept exactly as declared because AAP Rule T1 permits no renaming, so the
     * recognition has to accommodate the spelling rather than the reverse.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void anExplicitlyNamedTrailingFillerIsStillRecognisedAsPadding() {
        RecordSpec userSecurity = CopybookLayout.layout("SECUSER");
        FieldSpec named = userSecurity.field("SEC-USR-FILLER");
        assertEquals(57, named.start());
        assertEquals(23, named.length());
        assertEquals(80, named.end());
        assertEquals(Kind.TEXT, named.kind());
        assertThat(named.name()).isNotEqualTo("FILLER").endsWith("-FILLER");

        String image = padded("ADMIN001", 8) + padded("Admin", 20) + padded("User", 20)
                + "XXXXXXXX" + "A" + " ".repeat(23);
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image.getBytes(ASCII), userSecurity);
        assertThat(decoded).doesNotContainKey("SEC-USR-FILLER");
        assertEquals(userSecurity.fields().size() - 1, decoded.size());
        assertEquals(-1, firstDifference(image.getBytes(ASCII),
                FixedWidthCodec.encodeRecord(decoded, userSecurity)));
    }

    /**
     * Proves a filler that carries a value is content rather than disposable padding.
     *
     * <p>Assumptions: {@code app/cpy/CVTRA07Y.cpy} declares TWENTY-TWO fillers and every single one of them
     * carries a value clause, so in that copybook a filler is the report's own literal text rather than
     * padding at all. Its line 12 declares {@code 05 FILLER PIC X(04) VALUE ' to '.} between the two date
     * fields at lines 11 and 13, and those four characters are printed output: dropping them as padding
     * would emit a date range with nothing between its two dates. The descriptor below is the minimal
     * three-field case that shows it, and the codec's own rule is what makes the outcome right -- the drop
     * is conditioned on the span being blank, and these four characters are not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    void aFillerThatCarriesAValueIsContentRatherThanDisposablePadding() {
        RecordSpec dateRangeHeader = new RecordSpec("REPORT-DATE-RANGE", 24, 10, 0, List.of(
                CopybookLayout.text("REPT-START-DATE", 0, 10),
                CopybookLayout.text("FILLER", 10, 4),
                CopybookLayout.text("REPT-END-DATE", 14, 10))).validateGeometry();

        String image = "2022-01-01" + " to " + "2022-07-06";
        assertEquals(24, image.length());

        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(image.getBytes(ASCII),
                dateRangeHeader);
        assertEquals("2022-01-01", decoded.get("REPT-START-DATE"));
        assertEquals(" to ", decoded.get("FILLER"),
                "a filler carrying printed literal text must survive the decode");
        assertEquals("2022-07-06", decoded.get("REPT-END-DATE"));
        assertEquals(3, decoded.size());

        byte[] reEncoded = FixedWidthCodec.encodeRecord(decoded, dateRangeHeader);
        assertEquals(-1, firstDifference(image.getBytes(ASCII), reEncoded));

        // WHY : Assumptions: the report literals in that copybook are EDITED numeric text and character
        //       text, not zoned, packed or binary storage. Its line 30 declares an amount as a
        //       hyphen-and-Z mask and its line 54 as a plus-and-Z mask, and both are display formatting
        //       rather than a numeric regime, so no field of that copybook belongs to the three
        //       computational kinds. Declaring one as text is the correct reading and not a simplification.
        assertEquals(Kind.TEXT, dateRangeHeader.field("FILLER").kind());
    }

    /**
     * Proves a redefining overlay aliases storage already declared and advances the offset by zero.
     *
     * <p>Assumptions: two reference sites establish the rule and both are asserted here.
     * {@code app/cbl/COCRDLIC.cbl} line 254 declares {@code 10 FILLER REDEFINES WS-ALL-ROWS.} over the
     * 196-byte field declared on the line above it, and the overlay's own contents are seven repetitions
     * of a 28-byte row -- an eleven-character account identifier, a sixteen-character card number and a
     * one-character status. Seven rows of 28 bytes is 196, which is the width of the field being
     * redefined, not an addition to it. {@code app/cbl/CBTRN02C.cbl} line 160 declares an UNNAMED
     * one-level {@code FILLER REDEFINES} over a 26-character timestamp, and its fourteen leaves also sum
     * to exactly 26.</p>
     *
     * <p>Assumptions: what is asserted is the resulting declared GEOMETRY rather than a parse of the source
     * text, because no copybook parser exists here and none is being introduced. The proof is that the
     * overlay's leaves tile the same span as the field they redefine, and that appending them as new
     * storage is refused against the declared length -- which is precisely what would happen if a reader
     * treated a redefinition as an extension.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link CopybookLayout.LayoutException} from each refused flattening.</p>
     */
    @Test
    void aRedefiningOverlayAliasesStorageAndAdvancesTheOffsetByZero() {
        RecordSpec screenRow = new RecordSpec("WS-EACH-CARD", 28, 11, 0, List.of(
                CopybookLayout.text("WS-ROW-ACCTNO", 0, 11),
                CopybookLayout.text("WS-ROW-CARD-NUM", 11, 16),
                CopybookLayout.text("WS-ROW-CARD-STATUS", 27, 1))).validateGeometry();
        int repeatedRows = 7;
        assertEquals(196, screenRow.reclen() * repeatedRows,
                "seven repetitions of the declared row are exactly the redefined width");

        RecordSpec screenData = new RecordSpec("WS-SCREEN-DATA", 196, 11, 0, List.of(
                CopybookLayout.text("WS-ALL-ROWS", 0, 196))).validateGeometry();
        assertEquals(196, screenData.field("WS-ALL-ROWS").length());
        assertEquals(screenRow.reclen() * repeatedRows, screenData.reclen());

        assertThat(assertThrows(LayoutException.class,
                () -> screenData.extendWith("WS-SCREEN-DATA-FLATTENED", 196,
                        List.of(CopybookLayout.text("WS-SCREEN-ROWS", 0, 196)))))
                .as("appending an overlay as new storage must not close against the declared length")
                .hasMessageContaining("field lengths sum to 392")
                .hasMessageContaining("declared length is 196");

        RecordSpec timestampOverlay = new RecordSpec("DB2-FORMAT-TS-OVERLAY", 26, 4, 0, List.of(
                CopybookLayout.text("DB2-YYYY", 0, 4),
                CopybookLayout.text("DB2-STREEP-1", 4, 1),
                CopybookLayout.text("DB2-MM", 5, 2),
                CopybookLayout.text("DB2-STREEP-2", 7, 1),
                CopybookLayout.text("DB2-DD", 8, 2),
                CopybookLayout.text("DB2-STREEP-3", 10, 1),
                CopybookLayout.text("DB2-HH", 11, 2),
                CopybookLayout.text("DB2-DOT-1", 13, 1),
                CopybookLayout.text("DB2-MIN", 14, 2),
                CopybookLayout.text("DB2-DOT-2", 16, 1),
                CopybookLayout.text("DB2-SS", 17, 2),
                CopybookLayout.text("DB2-DOT-3", 19, 1),
                CopybookLayout.uint("DB2-MIL", 20, 2),
                CopybookLayout.text("DB2-REST", 22, 4))).validateGeometry();
        assertEquals(26, timestampOverlay.reclen(),
                "the overlay leaves tile exactly the timestamp they redefine");

        RecordSpec timestampBase = new RecordSpec("DB2-FORMAT-TS", 26, 26, 0, List.of(
                CopybookLayout.text("DB2-FORMAT-TS", 0, 26))).validateGeometry();
        assertThat(assertThrows(LayoutException.class,
                () -> timestampBase.extendWith("DB2-FORMAT-TS-FLATTENED", 26,
                        timestampOverlay.fields())))
                .hasMessageContaining("field lengths sum to 52")
                .hasMessageContaining("declared length is 26");
    }

    /**
     * Proves the unpadded thirty-six-character cross-reference row fails strict decoding.
     *
     * <p>Assumptions: {@code app/data/ASCII/cardxref.txt} stores each row with its trailing padding OMITTED,
     * so a row measures 36 characters where {@code app/cpy/CVACT03Y.cpy} declares a 50-byte record. The
     * shortfall is exactly the fourteen-byte filler at copybook line 8. Refusing the short row is the
     * correct outcome and not a defect in either artifact: the loader's own job is to pad a stored row out
     * to its declared length before a record codec sees it, and a codec that padded silently would accept a
     * genuinely truncated record with equal willingness. The full-width row used for the round trip above
     * comes from {@code tests/fixtures/export/happy_path/cardxref.txt}, which stores the same records at
     * their declared width.</p>
     *
     * <p>This test takes no parameter and returns no value. It captures a
     * {@link FixedWidthCodec.RecordLengthException} from the unpadded row.</p>
     */
    @Test
    void theUnpaddedThirtySixCharacterCrossReferenceRowFailsStrictDecoding() {
        String storedRow = "0927987108636232" + "000000020" + "00000000020";
        assertEquals(36, storedRow.length(), "the stored row omits its trailing padding");

        RecordSpec crossReference = CopybookLayout.layout("XREF");
        assertEquals(50, crossReference.reclen());
        assertEquals(14, crossReference.field("FILLER").length());
        assertEquals(crossReference.reclen() - storedRow.length(),
                crossReference.field("FILLER").length(),
                "the shortfall is exactly the omitted padding");

        assertThat(assertThrows(RecordLengthException.class,
                () -> FixedWidthCodec.decodeRecord(storedRow.getBytes(ASCII), crossReference)))
                .hasMessageContaining("record XREF expected 50 bytes but received 36");

        // WHY : Assumptions: padding the row out to its declared width is what makes it readable, and it is
        //       asserted here so the refusal above cannot be read as a defect in the stored data. The same
        //       bytes plus the fourteen blanks the copybook declares decode and re-encode identically.
        String paddedRow = storedRow + " ".repeat(14);
        assertEquals(50, paddedRow.length());
        Map<String, Object> decoded = decodeAndProveByteIdentity("XREF", paddedRow);
        assertEquals("0927987108636232", decoded.get("XREF-CARD-NUM"));
    }

}
