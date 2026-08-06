package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.PackedDecimalCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts what the three images in {@code pautdtl1-order-same-day-times.bin} mean, in what order
 * their raw keys sort, and why the third of them is not a duplicate of the first.
 *
 * <p>This class is the documentation carrier for that fixture. A fixed-length binary segment image
 * cannot hold a comment or a docstring of its own: every one of its 200 bytes is a declared field
 * interval, so a single explanatory byte would push the record over its length and shift every field
 * after it. The explanation therefore lives here, in Javadoc, which the documentation gate configured
 * at {@code config/checkstyle/checkstyle.xml} inspects mechanically at Maven {@code validate} with
 * {@code includeTestSourceDirectory} enabled. Prose kept beside the fixture instead would be
 * inspected by nothing: that file sets {@code fileExtensions="java"} at its line 185, which is also
 * the honest reason the {@code [\\/]src[\\/]test[\\/]resources[\\/]fixtures[\\/]} entry in
 * {@code config/checkstyle/suppressions.xml} is defensive rather than load-bearing -- it excludes a
 * tree the extension filter already excludes.</p>
 *
 * <h2>Assumptions: the 200-byte layout</h2>
 *
 * <p>Every offset this class reads comes from {@code CopybookLayout.layout("PAUTDTL")}, which
 * transcribes {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54 once. The
 * layout is never restated here. Its 27 named fields plus a 17-byte trailing filler sum to exactly
 * 200, and three independent sources agree: {@code ims/DBPAUTP0.dbd} line 36 declares
 * {@code SEGM NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200}; line 37 declares the retrieval key
 * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C}, which is the group at
 * {@code CIPAUDTY.cpy} line 19 -- three bytes of packed date plus five of packed time; and
 * {@code ims/PADFLDBD.DBD} line 27 declares {@code RECORD=(200),RECFM=F} for the sequential form.</p>
 *
 * <p>Assumptions: the segment carries no account identifier of its own, and that absence is
 * load-bearing rather than an omission. IMS supplies the parent key hierarchically, so a relational
 * loader has to contribute {@code account_id} from the parent it was read under. All three images
 * here belong to account 10000000001, the account every single-record fixture in this directory
 * carries.</p>
 *
 * <h2>Assumptions: the two key fields hold a NINES COMPLEMENT, the columns hold the decoded value</h2>
 *
 * <p>This is the fixture's sharpest trap, so the arithmetic is stated in full.
 * {@code cbl/COPAUA0C.cbl} builds the time at millisecond resolution with
 * {@code COMPUTE WS-TIME-WITH-MS = (WS-CUR-TIME-N6 * 1000) + WS-CUR-TIME-MS} at its lines 871 to 872,
 * then stores both key components complemented: {@code COMPUTE PA-AUTH-DATE-9C = 99999 - WS-YYDDD} at
 * line 874 over the five-digit Julian form taken at line 868, and
 * {@code COMPUTE PA-AUTH-TIME-9C = 999999999 - WS-TIME-WITH-MS} at line 875.
 * {@code cbl/COPAUS2C.cbl} line 107 reverses it with {@code 999999999 - PA-AUTH-TIME-9C} and then
 * slices the result {@code (1:2)} for hours, {@code (3:2)} for minutes, {@code (5:2)} for seconds and
 * {@code (7:3)} for MILLISECONDS. That last slice is the direct evidence that second resolution is
 * insufficient, and it is why this fixture exists.</p>
 *
 * <pre>
 * label  business value   meaning              stored complement  bytes
 * date   24095            2024 day 095         99999-24095=75904  75 90 4C
 * tB     163045500        16:30:45.500         836954499          83 69 54 49 9C
 * tA      91500000        09:15:00.000         908499999          90 84 99 99 9C
 * tC     163045750        16:30:45.750         836954249          83 69 54 24 9C
 * </pre>
 *
 * <p>Trade-offs: every assertion below names the DECODED value -- 24095 with 163045500, 91500000 and
 * 163045750 -- and never the stored complement, because {@code V1__authorization.sql} declares
 * {@code auth_date} and {@code auth_time} as {@code INTEGER} columns holding decoded values. Asserting
 * 75904 or 836954499 against those columns would pass against a loader that forgot to complement and
 * would then order every browse backwards.</p>
 *
 * <p>Alternatives Considered: storing the already-decoded 24095 and 091500 in these two fields, which
 * is what the {@code pautdtl-} fixtures committed alongside this one do -- they carry
 * {@code 24 09 5C} and {@code 00 00 91 50 0C} and vary the last time digit to keep their primary keys
 * apart. That shape was rejected here for two specific reasons. It contradicts the encode sites cited
 * above, and provably so: a stored 24095 reverses to Julian 75904, a 904th day that no year has, so
 * those bytes cannot be a complement of anything. And it cannot express either property this fixture
 * exists to prove, because with decoded values byte-ascending order becomes chronologically ASCENDING
 * -- erasing the descending-browse behaviour the complement is there to produce -- while a
 * six-digit HHMMSS zero-extended to nine digits has no millisecond field to disambiguate. The two
 * conventions therefore coexist in one directory on purpose and must not be averaged: read this
 * fixture as complemented and those as decoded.</p>
 *
 * <h2>Assumptions: tB and tC deliberately share an HHMMSS, and are not duplicates</h2>
 *
 * <p>Records one and three both carry the text {@code "163045"} in {@code PA-AUTH-ORIG-TIME} and both
 * decode to the second 16:30:45. They differ only in the millisecond triplet, 500 against 750, which
 * exists solely inside the packed {@code PA-AUTH-TIME-9C} and has no representation at all in the
 * six-character text field. Two consequences follow, and both are asserted below. A loader that
 * derived {@code auth_time} from the text field would lose the millisecond entirely; a loader that
 * truncated the packed value to second resolution would map both records onto the same
 * {@code (account_id, auth_date, auth_time)} and violate {@code pk_pending_auth_detail}, so the second
 * insert would fail. Anyone tidying this fixture by deleting the apparent duplicate deletes that proof
 * and no test fails afterwards, which is precisely why it is written down here.</p>
 *
 * <h2>Assumptions: the file order is deliberately NOT chronological</h2>
 *
 * <p>The images are stored tB, tA, tC -- 16:30:45.500, then 09:15:00.000, then 16:30:45.750. That is
 * not an accident of authoring. A {@code PAUTDTL1} image is self-describing: its own eight-byte key
 * fully identifies it, so its relationship to its parent and its place in time survive any
 * permutation of the file. Shuffling therefore proves that a consumer SORTS rather than trusting
 * position, and {@link #decodedSetIsIndependentOfReadOrder()} checks every permutation.</p>
 *
 * <p>Trade-offs: the opposite contract also lives in this directory and must not be confused with
 * this one. A bare-child sequential unload stream, the shape named
 * {@code unload-gsam-detail-200.bin}, carries no key prefix, so its rows are related only by write
 * order and reordering it destroys information. The summary-level pair already committed here shows
 * the same distinction concretely: {@code unload-gsam-summary-100.bin} and
 * {@code unload-prefixed-summary-100.bin} hold the same two records in opposite orders precisely so
 * that unload ordering is asserted rather than assumed. Two fixture families in one directory with
 * opposite ordering contracts is exactly the kind of thing that has to be recorded rather than
 * inferred.</p>
 *
 * <h2>Assumptions: the sign nibble must be 0xC on every key field, uniformly</h2>
 *
 * <p>{@code ims/DBPAUTP0.dbd} line 37 declares the eight-byte key {@code TYPE=C}, character, even
 * though both of its components are packed, and line 29 completes the picture with
 * {@code POINTER=(TWINBWD)}. IMS therefore orders sibling twins by comparing the raw complement bytes,
 * which is the whole reason a complement is stored: byte-ascending equals chronologically DESCENDING,
 * so the newest authorization is reached first. The sign nibble occupies the low nibble of the last
 * byte of each component and so participates in that comparison. Mixing 0xC and 0xF across records
 * would leave every decoded value identical while perturbing the ordering -- a failure that shows up
 * as a wrong sequence and nothing else -- so {@link #signNibbleIsUniformlyPositiveOnBothKeyFields()}
 * pins it.</p>
 *
 * <h2>Assumptions: the pad nibble is present in money and absent in the key, in the same file</h2>
 *
 * <p>{@code PackedDecimalCodec} derives the pad from the geometry as
 * {@code width * 2 - (digits + 1)} and REJECTS a non-zero pad. The two amounts here are
 * {@code PIC S9(10)V99 COMP-3}: twelve digits in seven bytes is fourteen nibbles for thirteen used,
 * so one leading nibble pads and must be zero. Both key components come out pad-free -- five digits in
 * three bytes and nine digits in five bytes each use every nibble -- and so does the sibling summary
 * segment's {@code PIC S9(09)V99 COMP-3}, eleven digits in six bytes, where a leading zero nibble is a
 * DIGIT and treating it as padding would silently drop a place. Opposite rules, same directory, which
 * is why {@link #padGeometryDiffersBetweenTheKeyAndTheMoneyFields()} asserts both directions.</p>
 *
 * <h2>Assumptions: PA-POS-ENTRY-MODE is two ASCII digits here, not a binary halfword</h2>
 *
 * <p>One value wears three representations and only the first belongs in these bytes.
 * {@code CIPAUDTY.cpy} line 38 declares {@code PIC 9(02)}, two DISPLAY characters;
 * {@code dcl/AUTHFRDS.dcl} line 71 declares the Db2 host variable {@code PIC S9(4) USAGE COMP}, a
 * binary halfword; and {@code ddl/AUTHFRDS.ddl} line 16, with the target migration, declares
 * {@code SMALLINT}. {@code cbl/COPAUS2C.cbl} line 128 performs the conversion between the first and
 * the second. Writing the halfword into the segment would corrupt bytes 95 and 96 of every record
 * while still decoding to a plausible small number, so
 * {@link #displayNumericFieldsAreAsciiDigitsNotBinary()} reads those bytes as characters.</p>
 *
 * <h2>Assumptions: provenance is contract-derived, and there is no golden master</h2>
 *
 * <p>These bytes were built from the copybook, the two database descriptions, the Db2 definition and
 * the declaration, not recorded from a run. {@code tests/README.md} section 1.1 lines 83 to 85 states
 * that the online {@code CO*} CICS programs cannot run end to end without a CICS runtime, which the
 * runner does not have, and the baseline supplies no authorization request producer. So no golden
 * master exists for this path and the guarded golden-update switch has nothing here to regenerate.
 * Every identifier is synthetic; the primary account number is a documentation-range value and is the
 * same in all three records so that ordering is driven purely by time.</p>
 */
class PendingAuthDetailOrderingFixtureTest {

    /** Root of the fixture directory on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** The fixture this class documents and consumes. */
    private static final String FIXTURE = "pautdtl1-order-same-day-times.bin";

    /** Declared length of the detail segment, {@code SEGM ... BYTES=200}. */
    private static final int RECLEN = 200;

    /** Number of images the fixture holds. */
    private static final int IMAGES = 3;

    /** Declared length of the {@code TYPE=C} retrieval key, {@code START=1,BYTES=8}. */
    private static final int KEY_LENGTH = 8;

    /** Width of the trailing {@code FILLER X(17)} that reaches no target column. */
    private static final int FILLER_WIDTH = 17;

    /** Julian YYDDD every image shares, 2024 day 095, so only the time varies. */
    private static final int JULIAN = 24095;

    /** Complement base for {@code PA-AUTH-DATE-9C}, {@code PIC S9(05)}. */
    private static final int DATE_COMPLEMENT_BASE = 99999;

    /** Complement base for {@code PA-AUTH-TIME-9C}, {@code PIC S9(09)}. */
    private static final int TIME_COMPLEMENT_BASE = 999999999;

    /** Divisor that reduces an HHMMSSmmm value to the HHMMSS a second-resolution loader would keep. */
    private static final int MILLIS_PER_SECOND = 1000;

    /** The packed sign nibble every signed field in this fixture must carry. */
    private static final int POSITIVE_SIGN = 0x0C;

    /** Integer digit count of {@code PA-AUTH-DATE-9C}. */
    private static final int DATE_DIGITS = 5;

    /** Integer digit count of {@code PA-AUTH-TIME-9C}. */
    private static final int TIME_DIGITS = 9;

    /** Integer digit count of the two {@code PIC S9(10)V99} amounts. */
    private static final int MONEY_INT_DIGITS = 10;

    /** Decimal digit count of the two {@code PIC S9(10)V99} amounts. */
    private static final int MONEY_DEC_DIGITS = 2;

    /** Integer digit count of the sibling summary segment's {@code PIC S9(09)V99} money. */
    private static final int SUMMARY_MONEY_INT_DIGITS = 9;

    /** The primary account number all three images share, a synthetic documentation-range value. */
    private static final String CARD = "4000123456789010";

    /**
     * Supplies the three images in FILE order with the business values each one decodes to.
     *
     * <p>Assumptions: the ordinals are the deliberately shuffled file positions, so the sequence of
     * decoded times below reads 16:30:45.500, 09:15:00.000, 16:30:45.750 rather than ascending. A
     * reader that expected chronological order would fail here first, which is the intended place to
     * discover the contract.</p>
     *
     * @return one argument set per image: ordinal, label, decoded HHMMSSmmm, amount, original-time
     *     text, authorization identifier code and transaction identifier
     */
    private static List<Arguments> images() {
        return List.of(
                Arguments.of(0, "tB", 163045500, "25.00", "163045", "A00010", "TXN000000000010"),
                Arguments.of(1, "tA", 91500000, "99.99", "091500", "A00011", "TXN000000000011"),
                Arguments.of(2, "tC", 163045750, "1000.00", "163045", "A00012", "TXN000000000012"));
    }

    /**
     * Reads a fixture from the test class path in full.
     *
     * @param name the file name inside the fixture directory
     * @return every byte of the file, unmodified
     * @throws AssertionError if the fixture is absent from the class path
     * @throws UncheckedIOException if the stream cannot be read
     */
    private static byte[] bytes(String name) {
        try (InputStream stream = PendingAuthDetailOrderingFixtureTest.class
                .getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test class path");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Extracts one image by ordinal using fixed-length arithmetic only.
     *
     * <p>Assumptions: the split is positional because the file carries no framing whatsoever -- no
     * length prefix, no record-descriptor word, no terminator. A reader that split on a line ending
     * would be relying on a byte this record type is not guaranteed to lack.</p>
     *
     * @param image the whole fixture
     * @param ordinal the zero-based image position
     * @return exactly {@link #RECLEN} bytes
     */
    private static byte[] recordAt(byte[] image, int ordinal) {
        return Arrays.copyOfRange(image, ordinal * RECLEN, (ordinal + 1) * RECLEN);
    }

    /**
     * Returns the registered detail layout, the single source of these offsets.
     *
     * @return the {@code PAUTDTL} record specification held by {@code CopybookLayout}
     */
    private static RecordSpec layout() {
        return CopybookLayout.layout("PAUTDTL");
    }

    /**
     * Decodes one image through the shared codec.
     *
     * @param image the whole fixture
     * @param ordinal the zero-based image position
     * @return the declaration-named field values, with blank registered filler omitted
     */
    private static Map<String, Object> decode(byte[] image, int ordinal) {
        return FixedWidthCodec.decodeRecord(recordAt(image, ordinal), layout());
    }

    /**
     * Returns the byte offset a named field occupies, taken from the registered layout.
     *
     * <p>Alternatives Considered: offsets are read from {@code CopybookLayout} rather
     * than written as literals here, so this class cannot drift from the copybook independently of the
     * codec that reads the same bytes in production. Literal offsets are the specific failure this
     * avoids: a field moved in the layout would leave a stale literal decoding the neighbouring
     * interval, which yields a plausible wrong number rather than an error.</p>
     *
     * @param fieldName the exact copybook field name
     * @return the zero-based offset of that field within one image
     */
    private static int offsetOf(String fieldName) {
        return layout().field(fieldName).start();
    }

    /**
     * Reverses the stored nines complement of {@code PA-AUTH-DATE-9C}.
     *
     * @param record one 200-byte image
     * @return the Julian YYDDD the record represents
     */
    private static int decodedAuthDate(byte[] record) {
        BigDecimal stored = PackedDecimalCodec.decodePacked(record, offsetOf("PA-AUTH-DATE-9C"),
                DATE_DIGITS, 0, true);
        return DATE_COMPLEMENT_BASE - stored.intValueExact();
    }

    /**
     * Reverses the stored nines complement of {@code PA-AUTH-TIME-9C}.
     *
     * @param record one 200-byte image
     * @return the business time as HHMMSSmmm, nine digits at millisecond resolution
     */
    private static int decodedAuthTime(byte[] record) {
        BigDecimal stored = PackedDecimalCodec.decodePacked(record, offsetOf("PA-AUTH-TIME-9C"),
                TIME_DIGITS, 0, true);
        return TIME_COMPLEMENT_BASE - stored.intValueExact();
    }

    /**
     * Extracts the eight-byte {@code TYPE=C} retrieval key IMS compares byte-wise.
     *
     * @param record one 200-byte image
     * @return the leading {@link #KEY_LENGTH} bytes, complement and sign nibbles included
     */
    private static byte[] retrievalKey(byte[] record) {
        return Arrays.copyOfRange(record, 0, KEY_LENGTH);
    }

    /**
     * Reads one nibble out of a packed field so a sign or a pad can be asserted directly.
     *
     * @param record one 200-byte image
     * @param byteOffset the offset of the byte holding the nibble
     * @param high whether the wanted nibble is the high one
     * @return the nibble value, 0 through 15
     */
    private static int nibble(byte[] record, int byteOffset, boolean high) {
        int value = record[byteOffset] & 0xFF;
        return high ? value >>> 4 : value & 0x0F;
    }

    /**
     * Confirms the fixture is three unframed 200-byte images and holds neither terminator byte.
     *
     * <p>Assumptions: the length is asserted with no newline allowance at all. The convention at
     * {@code tests/fixtures/README.md} section 3.3 lines 176 to 184 ends a fixture with one trailing
     * newline so that {@code wc -l} equals the record count, and section 3.2 lines 158 to 174 reasons
     * that a stray carriage return would be absorbed into the trailing field or the filler and push the
     * record one byte past its length. Both apply in shape and neither can be adopted: a terminator
     * byte here would make the file 601 bytes and no longer divisible by 200. Section 3.2 lines 170 to
     * 174 permits a different convention only when the scenario documents it in its own README, and
     * that carrier is unavailable for a binary segment, which is the second reason this class exists.
     * Section 3.4 lines 186 to 211 is inapplicable outright: it specifies zoned-decimal sign overpunch
     * against the ASCII seeds, and no field in this record is zoned.</p>
     *
     * <p>Trade-offs: both terminator bytes are asserted absent for different reasons rather than one
     * blanket reason. A {@code COMP-3} byte can never be 0x0A, because the low nibble A is neither a
     * digit nor a valid sign, so line feed can only enter this record type through a binary field and
     * this record type declares none. 0x0D is reachable: it is the sign byte of a negatively-signed
     * packed value whose final digit is zero. Every packed value here is positive, so it is absent in
     * fact, but the read discipline is positional and does not depend on that.</p>
     */
    @Test
    @DisplayName("the fixture is three unframed 200-byte images with no terminator byte")
    void fixtureIsThreeUnframedImagesWithNoTerminatorByte() {
        byte[] image = bytes(FIXTURE);

        assertThat(image).as("three images of %d bytes, unframed", RECLEN)
                .hasSize(IMAGES * RECLEN);
        assertThat(image.length % RECLEN).as("a length not divisible by %d cannot reconcile", RECLEN)
                .isZero();
        assertThat(image).as("line feed cannot occur in a packed or display field").doesNotContain(
                (byte) 0x0A);
        assertThat(image).as("carriage return is reachable only from a negative packed zero")
                .doesNotContain((byte) 0x0D);
        assertThat(layout().reclen()).isEqualTo(RECLEN);
        assertThat(layout().keyLength()).isEqualTo(KEY_LENGTH);
        assertThat(layout().keyOffset()).isZero();
    }

    /**
     * Confirms each image decodes to its documented business values, complement reversed.
     *
     * <p>Assumptions: the shared date is asserted per record rather than once, because holding the
     * date constant across all three is what isolates {@code auth_time} as the only variable driving
     * the ordering proof. A record whose date drifted would still sort, and would still look correct in
     * isolation, while quietly destroying that isolation.</p>
     *
     * @param ordinal the zero-based file position
     * @param label the shorthand used throughout this class
     * @param businessTime the decoded HHMMSSmmm the image represents
     * @param amount the money literal both amount fields carry
     * @param originalTime the client-supplied HHMMSS text
     * @param authIdCode the authorization identifier code
     * @param transactionId the transaction identifier
     */
    @ParameterizedTest(name = "image {0} ({1}) decodes to auth_time {2}")
    @MethodSource("images")
    @DisplayName("every image decodes to its documented business values")
    void everyImageDecodesToItsDocumentedValues(int ordinal, String label, int businessTime,
            String amount, String originalTime, String authIdCode, String transactionId) {
        byte[] image = bytes(FIXTURE);
        byte[] record = recordAt(image, ordinal);
        Map<String, Object> fields = decode(image, ordinal);

        assertThat(record).as("%s closes on the declared segment length", label).hasSize(RECLEN);
        assertThat(decodedAuthDate(record)).as("%s: Julian YYDDD, complement reversed", label)
                .isEqualTo(JULIAN);
        assertThat(decodedAuthTime(record)).as("%s: HHMMSSmmm, complement reversed", label)
                .isEqualTo(businessTime);

        assertThat((BigDecimal) fields.get("PA-TRANSACTION-AMT")).isEqualByComparingTo(amount);
        assertThat((BigDecimal) fields.get("PA-APPROVED-AMT")).isEqualByComparingTo(amount);
        assertThat(fields.get("PA-AUTH-ORIG-DATE")).isEqualTo("240404");
        assertThat(fields.get("PA-AUTH-ORIG-TIME")).isEqualTo(originalTime);
        assertThat(fields.get("PA-AUTH-ID-CODE")).isEqualTo(authIdCode);
        assertThat(fields.get("PA-TRANSACTION-ID")).isEqualTo(transactionId);
        assertThat(fields.get("PA-CARD-NUM")).isEqualTo(CARD);
        assertThat(fields.get("PA-AUTH-TYPE")).isEqualTo("PURC");
        assertThat(fields.get("PA-CARD-EXPIRY-DATE")).as("MMYY, December 2027").isEqualTo("1227");
        assertThat(fields.get("PA-MESSAGE-TYPE")).isEqualTo("0100  ");
        assertThat(fields.get("PA-MESSAGE-SOURCE")).isEqualTo("POS   ");
        assertThat(fields.get("PA-MERCHANT-CATAGORY-CODE")).isEqualTo("5411");
        assertThat(fields.get("PA-ACQR-COUNTRY-CODE")).isEqualTo("840");
        assertThat(fields.get("PA-MERCHANT-ID")).isEqualTo("MERCH0000000001");
        assertThat(fields.get("PA-MERCHANT-NAME")).isEqualTo("ACME HARDWARE         ");
        assertThat(fields.get("PA-MERCHANT-CITY")).isEqualTo("SEATTLE      ");
        assertThat(fields.get("PA-MERCHANT-STATE")).isEqualTo("WA");
        assertThat(fields.get("PA-MERCHANT-ZIP")).isEqualTo("98101    ");
    }

    /**
     * Confirms the response fields, the status domain values and the two blank fields the target
     * schema must accept.
     *
     * <p>Assumptions: the blank authorization-fraud byte is the positive evidence behind a target check
     * constraint that would otherwise look permissive without reason. {@code CIPAUDTY.cpy} declares
     * only {@code 'F'} at line 51 and {@code 'R'} at line 52 and provides NO condition name for blank,
     * yet {@code cbl/COPAUA0C.cbl} line 908 moves {@code SPACE} into the field on every insert and
     * {@code cbl/COPAUS1C.cbl} lines 344 to 349 render a bare hyphen when neither condition holds. The
     * absence of an 88-level is therefore a gap in the copybook, not a prohibition, which is why
     * {@code ck_pending_auth_detail_auth_fraud} admits a blank alongside NULL.</p>
     *
     * <p>Trade-offs: the blank report date is asserted as eight spaces and NOT as a date. The
     * committed migration declares {@code pending_auth_detail.fraud_rpt_date} as a nullable
     * {@code CHAR(8)}, keeping the segment's own MM/DD/YY shape from {@code cbl/COPAUS2C.cbl} lines 95
     * to 101, and reserves a real {@code DATE} for the separate fraud table. The two representations
     * differ in the target exactly as they differ in the baseline, where
     * {@code dcl/AUTHFRDS.dcl} line 84 is ten characters wide against the segment's eight.</p>
     *
     * @param ordinal the zero-based file position
     * @param label the shorthand used throughout this class
     * @param businessTime the decoded HHMMSSmmm, unused by this assertion but supplied by the source
     * @param amount the money literal, unused by this assertion but supplied by the source
     * @param originalTime the HHMMSS text, unused by this assertion but supplied by the source
     * @param authIdCode the authorization identifier code, unused here but supplied by the source
     * @param transactionId the transaction identifier, unused here but supplied by the source
     */
    @ParameterizedTest(name = "image {0} ({1}) carries the approved, pending, unmarked state")
    @MethodSource("images")
    @DisplayName("every image is approved, pending, and carries both blank fraud fields")
    void everyImageIsApprovedPendingAndUnmarked(int ordinal, String label, int businessTime,
            String amount, String originalTime, String authIdCode, String transactionId) {
        Map<String, Object> fields = decode(bytes(FIXTURE), ordinal);

        assertThat(fields.get("PA-AUTH-RESP-CODE")).as("%s: 88 PA-AUTH-APPROVED at line 31", label)
                .isEqualTo("00");
        assertThat(fields.get("PA-AUTH-RESP-REASON")).as("the approval default").isEqualTo("0000");
        assertThat(fields.get("PA-MATCH-STATUS")).as("88 PA-MATCH-PENDING at line 46").isEqualTo("P");
        assertThat(List.of("P", "D", "E", "M"))
                .as("must satisfy ck_pending_auth_detail_match_status")
                .contains((String) fields.get("PA-MATCH-STATUS"));
        assertThat(fields.get("PA-AUTH-FRAUD")).as("no 88-level exists for blank, yet blank is written")
                .isEqualTo(" ");
        assertThat(fields.get("PA-FRAUD-RPT-DATE")).as("eight spaces, a nullable CHAR(8) in the target")
                .isEqualTo(" ".repeat(8));
    }

    /**
     * Confirms the file order is the deliberate tB, tA, tC and not chronological.
     *
     * <p>Assumptions: this is asserted explicitly so that a future edit which "fixes" the order fails
     * here with a message naming the reason, rather than silently removing the property that
     * {@link #decodedSetIsIndependentOfReadOrder()} is designed to prove.</p>
     */
    @Test
    @DisplayName("the file order is deliberately not chronological")
    void fileOrderIsDeliberatelyNotChronological() {
        byte[] image = bytes(FIXTURE);

        List<Integer> asStored = new ArrayList<>();
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            asStored.add(decodedAuthTime(recordAt(image, ordinal)));
        }

        assertThat(asStored).as("stored order: 16:30:45.500, then 09:15:00.000, then 16:30:45.750")
                .containsExactly(163045500, 91500000, 163045750);

        List<Integer> chronological = new ArrayList<>(asStored);
        chronological.sort(Comparator.naturalOrder());
        assertThat(asStored).as("shuffling is the point; sorted storage would prove nothing")
                .isNotEqualTo(chronological);
        assertThat(chronological).as("the same three values in chronological order")
                .containsExactly(91500000, 163045500, 163045750);
    }

    /**
     * Confirms byte-ascending order over the raw keys is chronologically descending.
     *
     * <p>Assumptions: the comparison is unsigned byte by byte because the key is declared
     * {@code TYPE=C} at {@code ims/DBPAUTP0.dbd} line 37. Java's {@code byte} is signed, so a naive
     * comparison would place 0x83 and 0x90 below 0x75 and invert the result; the unsigned comparator
     * below is what makes this assertion reproduce the IMS collation rather than the JVM's.</p>
     */
    @Test
    @DisplayName("raw keys ascending are chronologically descending, latest first")
    void rawKeysAscendingAreChronologicallyDescending() {
        byte[] image = bytes(FIXTURE);

        List<byte[]> keys = new ArrayList<>();
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            keys.add(retrievalKey(recordAt(image, ordinal)));
        }
        keys.sort(Comparator.comparing(key -> {
            StringBuilder unsigned = new StringBuilder();
            for (byte value : key) {
                unsigned.append(String.format("%02X", value));
            }
            return unsigned.toString();
        }));

        List<Integer> chronology = new ArrayList<>();
        for (byte[] key : keys) {
            byte[] padded = Arrays.copyOf(key, RECLEN);
            chronology.add(decodedAuthTime(padded));
        }

        assertThat(chronology).as("tC, tB, tA: the newest authorization is reached first")
                .containsExactly(163045750, 163045500, 91500000);

        List<Integer> ascendingByColumn = new ArrayList<>(List.of(163045500, 91500000, 163045750));
        ascendingByColumn.sort(Comparator.naturalOrder());
        assertThat(ascendingByColumn).as("ordering the decoded INTEGER columns ascending is the reverse")
                .containsExactly(91500000, 163045500, 163045750);
        assertThat(ascendingByColumn).as("descending decoded order reproduces baseline browse order")
                .isEqualTo(chronology.reversed());
    }

    /**
     * Confirms millisecond resolution is what keeps the composite primary key unique.
     *
     * <p>Alternatives Considered: the collision is demonstrated arithmetically rather
     * than by attempting a second insert, because the property being proved is a property of the
     * FIXTURE -- that two of its images are distinguishable only below the second -- and that holds
     * whether or not a database is
     * reachable. The consequence in the schema is fixed independently:
     * {@code pk_pending_auth_detail} is declared on {@code (account_id, auth_date, auth_time)}, and all
     * three images share one account and one date.</p>
     */
    @Test
    @DisplayName("millisecond resolution is required for composite primary-key uniqueness")
    void millisecondResolutionIsRequiredForPrimaryKeyUniqueness() {
        byte[] image = bytes(FIXTURE);

        int first = decodedAuthTime(recordAt(image, 0));
        int third = decodedAuthTime(recordAt(image, 2));

        assertThat(first / MILLIS_PER_SECOND).as("both reduce to the second 16:30:45")
                .isEqualTo(third / MILLIS_PER_SECOND)
                .isEqualTo(163045);
        assertThat(first % MILLIS_PER_SECOND).isEqualTo(500);
        assertThat(third % MILLIS_PER_SECOND).isEqualTo(750);
        assertThat(first).as("at full resolution the two are distinct, so both rows insert")
                .isNotEqualTo(third);

        assertThat(decode(image, 0).get("PA-AUTH-ORIG-TIME"))
                .as("the text field cannot carry the millisecond, so a loader reading it loses 500/750")
                .isEqualTo(decode(image, 2).get("PA-AUTH-ORIG-TIME"));

        Set<List<Object>> truncatedKeys = new LinkedHashSet<>();
        Set<List<Object>> fullKeys = new LinkedHashSet<>();
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] record = recordAt(image, ordinal);
            truncatedKeys.add(List.of(JULIAN, decodedAuthTime(record) / MILLIS_PER_SECOND));
            fullKeys.add(List.of(JULIAN, decodedAuthTime(record)));
        }
        assertThat(truncatedKeys).as("second truncation collapses three rows onto two keys").hasSize(2);
        assertThat(fullKeys).as("full resolution keeps all three distinct").hasSize(IMAGES);
    }

    /**
     * Confirms both key fields carry sign nibble 0xC in every image.
     *
     * <p>Assumptions: the nibble is read out of the raw byte rather than inferred from the decoded
     * sign, because a decoded positive value is identical whether the nibble is 0xC or 0xF while the
     * two bytes are not, and it is the BYTE that IMS compares. This assertion is therefore about
     * collation, not about arithmetic.</p>
     */
    @Test
    @DisplayName("both key fields carry a uniform positive sign nibble")
    void signNibbleIsUniformlyPositiveOnBothKeyFields() {
        byte[] image = bytes(FIXTURE);
        int dateEnd = offsetOf("PA-AUTH-DATE-9C") + PackedDecimalCodec.packedWidth(DATE_DIGITS, 0) - 1;
        int timeEnd = offsetOf("PA-AUTH-TIME-9C") + PackedDecimalCodec.packedWidth(TIME_DIGITS, 0) - 1;

        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] record = recordAt(image, ordinal);
            assertThat(nibble(record, dateEnd, false)).as("image %d date sign nibble", ordinal)
                    .isEqualTo(POSITIVE_SIGN);
            assertThat(nibble(record, timeEnd, false)).as("image %d time sign nibble", ordinal)
                    .isEqualTo(POSITIVE_SIGN);
        }
    }

    /**
     * Confirms the money fields pad and the key fields do not, in this one record.
     *
     * <p>Assumptions: the widths are obtained from {@code PackedDecimalCodec.packedWidth} rather than
     * written as literals, so the asymmetry is demonstrated to follow from the shared rule instead of
     * being asserted alongside it. The summary segment's eleven-digit money is included because it is
     * the counter-example that makes the rule non-obvious: there a leading zero nibble is a digit, and
     * a reader that skipped it as padding would drop a decimal place silently.</p>
     */
    @Test
    @DisplayName("money pads one leading nibble while both key fields pad none")
    void padGeometryDiffersBetweenTheKeyAndTheMoneyFields() {
        int moneyWidth = PackedDecimalCodec.packedWidth(MONEY_INT_DIGITS, MONEY_DEC_DIGITS);
        int dateWidth = PackedDecimalCodec.packedWidth(DATE_DIGITS, 0);
        int timeWidth = PackedDecimalCodec.packedWidth(TIME_DIGITS, 0);
        int summaryWidth = PackedDecimalCodec.packedWidth(SUMMARY_MONEY_INT_DIGITS, MONEY_DEC_DIGITS);

        assertThat(moneyWidth).as("twelve digits plus a sign is thirteen nibbles, so seven bytes")
                .isEqualTo(7);
        assertThat(moneyWidth * 2 - (MONEY_INT_DIGITS + MONEY_DEC_DIGITS + 1))
                .as("one leading nibble pads, and PackedDecimalCodec rejects it if non-zero")
                .isEqualTo(1);
        assertThat(dateWidth * 2 - (DATE_DIGITS + 1)).as("five digits plus a sign fills three bytes")
                .isZero();
        assertThat(timeWidth * 2 - (TIME_DIGITS + 1)).as("nine digits plus a sign fills five bytes")
                .isZero();
        assertThat(summaryWidth * 2 - (SUMMARY_MONEY_INT_DIGITS + MONEY_DEC_DIGITS + 1))
                .as("the sibling summary money pads none, so its leading zero nibble is a digit")
                .isZero();

        byte[] image = bytes(FIXTURE);
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] record = recordAt(image, ordinal);
            assertThat(nibble(record, offsetOf("PA-TRANSACTION-AMT"), true))
                    .as("image %d transaction amount pad nibble", ordinal).isZero();
            assertThat(nibble(record, offsetOf("PA-APPROVED-AMT"), true))
                    .as("image %d approved amount pad nibble", ordinal).isZero();
        }
    }

    /**
     * Confirms the two DISPLAY numeric fields are ASCII characters rather than binary.
     *
     * <p>Assumptions: the raw bytes are compared against their character form first and the decoded
     * number second. Checking only the decoded number would not distinguish the two encodings for
     * small values, which is exactly the range these two fields occupy.</p>
     */
    @Test
    @DisplayName("the DISPLAY numeric fields are ASCII digits, not a binary halfword")
    void displayNumericFieldsAreAsciiDigitsNotBinary() {
        byte[] image = bytes(FIXTURE);

        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] record = recordAt(image, ordinal);
            int posOffset = offsetOf("PA-POS-ENTRY-MODE");
            int codeOffset = offsetOf("PA-PROCESSING-CODE");

            assertThat(Arrays.copyOfRange(record, posOffset, posOffset + 2))
                    .as("image %d: two ASCII digits, never a COMP halfword", ordinal)
                    .isEqualTo("05".getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(record, codeOffset, codeOffset + 6))
                    .as("image %d: six ASCII digits", ordinal)
                    .isEqualTo("000000".getBytes(StandardCharsets.US_ASCII));

            Map<String, Object> fields = decode(image, ordinal);
            assertThat(fields.get("PA-POS-ENTRY-MODE")).as("decodes to the target SMALLINT value")
                    .isEqualTo(5L);
            assertThat(fields.get("PA-PROCESSING-CODE")).isEqualTo(0L);
        }
    }

    /**
     * Confirms the decoded set is identical under every permutation of the three images.
     *
     * <p>Assumptions: all six permutations are exercised rather than one shuffle, because a consumer
     * that happened to sort correctly for one arrangement and not another would pass a single-shuffle
     * check. This is the property that makes a keyed detail image safe to reorder, and it is the exact
     * property a bare unload stream does NOT have.</p>
     */
    @Test
    @DisplayName("the decoded set is independent of read order")
    void decodedSetIsIndependentOfReadOrder() {
        byte[] image = bytes(FIXTURE);
        Set<List<Object>> canonical = new LinkedHashSet<>();
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] record = recordAt(image, ordinal);
            canonical.add(List.of(decodedAuthDate(record), decodedAuthTime(record),
                    decode(image, ordinal).get("PA-TRANSACTION-ID")));
        }
        assertThat(canonical).hasSize(IMAGES);

        int[][] permutations = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0},
        };
        for (int[] permutation : permutations) {
            Set<List<Object>> permuted = new LinkedHashSet<>();
            for (int ordinal : permutation) {
                byte[] record = recordAt(image, ordinal);
                permuted.add(List.of(decodedAuthDate(record), decodedAuthTime(record),
                        decode(image, ordinal).get("PA-TRANSACTION-ID")));
            }
            assertThat(permuted).as("permutation %s yields the same set", Arrays.toString(permutation))
                    .isEqualTo(canonical);
        }
    }

    /**
     * Confirms the blank trailing filler is dropped from the decoded map yet the record still closes.
     *
     * <p>Assumptions: the drop happens because the layout comes from the registry.
     * {@code FixedWidthCodec} omits a blank registered filler only when the supplied specification is
     * identical to the one {@code CopybookLayout} holds, so a locally restated layout would keep the
     * filler and this assertion would fail -- which is the mechanism that makes restating a layout
     * detectable rather than merely discouraged.</p>
     */
    @Test
    @DisplayName("the blank trailing filler reaches no column yet each record closes on 200")
    void blankFillerIsDroppedYetEachRecordReconciles() {
        byte[] image = bytes(FIXTURE);
        RecordSpec spec = layout();

        assertThat(spec.field("FILLER").length()).isEqualTo(FILLER_WIDTH);
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            Map<String, Object> fields = decode(image, ordinal);
            assertThat(fields).as("image %d omits only the blank filler", ordinal)
                    .hasSize(spec.fields().size() - 1)
                    .doesNotContainKey("FILLER");
            assertThat(recordAt(image, ordinal)).hasSize(RECLEN);
        }
        assertThat(FILLER_WIDTH * IMAGES).as("bytes that reach no target column").isEqualTo(51);
    }

    /**
     * Confirms every image re-encodes byte-identically, shuffled file order included.
     *
     * <p>Alternatives Considered: the plain encoder suffices here and the sign-preserving
     * variant is deliberately not used. A byte-exact round trip would need that path only where a
     * negatively
     * signed zero exists, because that is the one value an encoder normalises to a positive nibble.
     * Every packed value in this fixture is strictly positive, every sign nibble is already 0xC, and
     * every filler is blank, so the ordinary encoder must reproduce the bytes exactly -- and asserting
     * that with the ordinary encoder is a stronger statement than asserting it with the variant that
     * copies the original nibbles back.</p>
     */
    @Test
    @DisplayName("every image re-encodes byte-identically, shuffled order included")
    void everyImageRoundTripsByteIdentically() {
        byte[] image = bytes(FIXTURE);
        RecordSpec spec = layout();

        byte[] rebuilt = new byte[image.length];
        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] original = recordAt(image, ordinal);
            byte[] encoded = FixedWidthCodec.encodeRecord(decode(image, ordinal), spec);

            assertThat(encoded).as("image %d round-trips byte for byte", ordinal)
                    .isEqualTo(original);
            System.arraycopy(encoded, 0, rebuilt, ordinal * RECLEN, RECLEN);
        }
        assertThat(rebuilt).as("the whole file, in its deliberately shuffled order").isEqualTo(image);
    }

    /**
     * Confirms a slice one byte short or one byte long is rejected rather than partially decoded.
     *
     * <p>Alternatives Considered: an off-by-one slice is the realistic failure for a
     * positional reader, and the codec checks the length before the first field rather than
     * recovering what it can. Partial
     * recovery is the alternative that was rejected upstream, because a plausible partial map hides a
     * shifted monetary field, which is the one error that must never decode quietly.</p>
     */
    @Test
    @DisplayName("a 199- or 201-byte slice is rejected outright")
    void wrongLengthSlicesAreRejected() {
        byte[] record = recordAt(bytes(FIXTURE), 0);
        RecordSpec spec = layout();

        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(Arrays.copyOf(record, RECLEN - 1), spec))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                .hasMessageContaining("PAUTDTL");
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(Arrays.copyOf(record, RECLEN + 1), spec))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                .hasMessageContaining("PAUTDTL");
        assertThat(bytes(FIXTURE).length % RECLEN)
                .as("a file length not divisible by the segment length cannot reconcile").isZero();
    }

    /**
     * Confirms the three images satisfy the target uniqueness constraints and column ranges.
     *
     * <p>Assumptions: the card-and-transaction uniqueness is asserted separately from the primary key
     * because they can fail independently. All three images share one card, so had they also shared a
     * transaction identifier the fixture would be uninsertable through
     * {@code uq_pending_auth_detail_card_transaction} even though its three primary keys are distinct.
     * The range check exists because the decoded times are stored in {@code INTEGER} columns and
     * 163045750 leaves comfortable headroom below the signed 32-bit ceiling, whereas the stored
     * complement 908499999 also fits -- so a loader that omitted the complement would not be caught by
     * a range error and has to be caught by value.</p>
     */
    @Test
    @DisplayName("the images satisfy the target uniqueness constraints and column ranges")
    void imagesSatisfyTargetUniquenessAndRanges() {
        byte[] image = bytes(FIXTURE);
        Set<String> transactionIds = new LinkedHashSet<>();
        Set<List<Object>> primaryKeys = new LinkedHashSet<>();

        for (int ordinal = 0; ordinal < IMAGES; ordinal++) {
            byte[] record = recordAt(image, ordinal);
            Map<String, Object> fields = decode(image, ordinal);

            transactionIds.add((String) fields.get("PA-TRANSACTION-ID"));
            primaryKeys.add(List.of(decodedAuthDate(record), decodedAuthTime(record)));

            assertThat(decodedAuthTime(record)).isPositive().isLessThan(Integer.MAX_VALUE);
            assertThat(decodedAuthDate(record)).isPositive().isLessThan(DATE_COMPLEMENT_BASE);
            assertThat(((BigDecimal) fields.get("PA-TRANSACTION-AMT")).precision())
                    .as("must fit NUMERIC(12,2)").isLessThanOrEqualTo(12);
            assertThat(((BigDecimal) fields.get("PA-TRANSACTION-AMT")).scale())
                    .as("money is exact fixed point at scale two").isEqualTo(MONEY_DEC_DIGITS);
        }

        assertThat(transactionIds).as("one card, three transaction identifiers").hasSize(IMAGES);
        assertThat(primaryKeys).as("three distinct (auth_date, auth_time) pairs under one account")
                .hasSize(IMAGES);
    }
}

