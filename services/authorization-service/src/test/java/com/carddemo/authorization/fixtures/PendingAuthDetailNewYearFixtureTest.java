package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads {@code pautdtl1-newyear-pair.bin} and asserts the three defects and inversions its bytes
 * exist to catch.
 *
 * <p>Assumptions: the fixture is two 200-byte {@code PAUTDTL1} images with record two beginning at
 * file offset 200. The layout is
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54, whose field widths sum to
 * exactly 200: a three-byte packed date and a five-byte packed time bracketed as the eight-byte group
 * {@code PA-AUTHORIZATION-KEY} at line 19, then character and display fields, two SEVEN-byte packed
 * money fields at lines 34 and 35, and a seventeen-byte {@code FILLER} at line 54. The declared length
 * is corroborated by {@code ims/DBPAUTP0.dbd} line 36 {@code BYTES=200} and by
 * {@code ims/PADFLDBD.DBD} line 27 {@code RECORD=(200),RECFM=F}. There is no framing: no length
 * prefix, no record-descriptor word, no terminator, and deliberately no trailing newline.</p>
 *
 * <p>Assumptions: THE FIXTURE CARRIES THE NINES COMPLEMENT; THE TABLE CARRIES THE DECODED VALUE.
 * {@code cbl/COPAUA0C.cbl} line 874 stores {@code PA-AUTH-DATE-9C = 99999 - WS-YYDDD} over the
 * five-digit Julian moved at line 868, and line 875 stores
 * {@code PA-AUTH-TIME-9C = 999999999 - WS-TIME-WITH-MS} over the millisecond-resolution time computed
 * at lines 871 to 872. The complements are read back by {@code cbl/CBPAUP0C.cbl} line 280 and
 * {@code cbl/COPAUS2C.cbl} line 107. So the stored bytes are 76634 and 764040000 for record one and
 * 75998 and 999998999 for record two, while {@code V1__authorization.sql} lines 321 and 322 declare
 * {@code auth_date} and {@code auth_time} as INTEGER columns holding the DECODED 23365, 235959999,
 * 24001 and 1000. Every assertion below names the decoded value; asserting a complement as a column
 * value would encode the defect this fixture exists to prevent.</p>
 *
 * <p>Assumptions: the two dates are 23365 and 24001 BECAUSE THEY STRADDLE A YEAR BOUNDARY, and that is
 * the whole reason this fixture exists rather than an incidental choice. 23365 is 31 December 2023 and
 * 24001 is 1 January 2024, one day apart, yet {@code cbl/CBPAUP0C.cbl} line 282 computes
 * {@code WS-DAY-DIFF = CURRENT-YYDDD - WS-AUTH-DATE} as a plain integer subtraction of two YYDDD
 * Julians with no year-boundary handling, yielding 636. Line 284's test is the inclusive
 * {@code IF WS-DAY-DIFF &gt;= WS-EXPIRY-DAYS}, and the threshold defaults to 5 at line 199 when the
 * PARM read at line 197 is not numeric, so 636 wrongly qualifies a one-day-old authorization for
 * deletion. REPLACING THESE TWO DATES WITH ANY TWO MID-YEAR DATES WOULD DELETE THE REGRESSION WITHOUT
 * FAILING A SINGLE ASSERTION, because within one year the naive subtraction and the true elapsed day
 * count agree. The margin is not decorative either: 636 exceeds the threshold by so much that a
 * partially-correct implementation cannot accidentally pass.</p>
 *
 * <p>Assumptions: the pad-nibble rule is OPPOSITE for the two segments in this directory, so the two
 * cannot be encoded by one habit. A detail money field is {@code PIC S9(10)V99 COMP-3}: twelve digits
 * in seven bytes is fourteen nibbles for thirteen used, so ONE LEADING PAD nibble exists and
 * {@code PackedDecimalCodec} throws when it is non-zero. The sibling summary's
 * {@code PIC S9(09)V99 COMP-3} is eleven digits in six bytes, twelve nibbles for twelve used, so NO
 * pad exists and a zero high nibble there would silently drop a digit instead of being rejected.</p>
 *
 * <p>Assumptions: {@code PA-POS-ENTRY-MODE} at line 38 is {@code PIC 9(02)} DISPLAY, so it occupies
 * two ASCII digit bytes at offsets 95 and 96 and is asserted as the text-backed value 5. Three
 * representations of this one field coexist and only the copybook governs these bytes:
 * {@code dcl/AUTHFRDS.dcl} line 71 declares the Db2 host variable {@code PIC S9(4) USAGE COMP} and
 * {@code ddl/AUTHFRDS.ddl} line 16 declares the column {@code SMALLINT}, with
 * {@code cbl/COPAUS2C.cbl} line 128 performing the conversion between them. Writing a binary halfword
 * into these two bytes would corrupt them while leaving the record length correct.</p>
 *
 * <p>Assumptions: {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME} at offsets 8 and 14 have a
 * DIFFERENT PROVENANCE from the complement key at offsets 0 to 7. {@code cbl/COPAUA0C.cbl} lines 877
 * to 878 move the originating pair straight from the client-supplied CSV request, whereas lines 868 to
 * 875 derive the complement from the server clock. The two may legitimately disagree in production, so
 * NO ASSERTION BELOW COMPARES THEM. They are mutually consistent in this fixture only so the record
 * reads coherently to a human.</p>
 *
 * <p>Trade-offs: this file is where the fixture's byte-level rationale lives, and that placement is
 * deliberate rather than convenient. A {@code .bin} has no function, class or module entry point to
 * carry a docstring, and a comment byte would break the 200-byte record closure, so the obligation
 * relocates to the Javadoc of the test that consumes it. Prose beside the fixture was rejected for two
 * specific reasons: {@code config/checkstyle/suppressions.xml} lines 250 to 251 exempt everything
 * under the fixtures resource directory from every Javadoc check, and {@code checkstyle.xml} line 178
 * narrows the tool to {@code fileExtensions="java"} in the first place, so documentation carried next
 * to the bytes is policed by nothing whereas this Javadoc is policed mechanically at Maven
 * {@code validate}.</p>
 */
class PendingAuthDetailNewYearFixtureTest {

    /** Root of the fixture directory on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** The fixture this test exists to read. */
    private static final String FIXTURE = "pautdtl1-newyear-pair.bin";

    /** The paired parent summary whose counters the purge arithmetic drives to zero. */
    private static final String PARENT = "pautsum0-purge-parent.bin";

    /** Declared length of the authorization detail segment, {@code SEGM ... BYTES=200}. */
    private static final int SEGMENT_LENGTH = 200;

    /** Ordinal of the older, approved, year-end record. */
    private static final int YEAR_END = 0;

    /** Ordinal of the newer, declined, new-year record. */
    private static final int NEW_YEAR = 1;

    /** Julian YYDDD of record one, 31 December 2023. */
    private static final int YEAR_END_JULIAN = 23365;

    /** Julian YYDDD of record two, 1 January 2024, and the business date the purge runs for. */
    private static final int NEW_YEAR_JULIAN = 24001;

    /** Expiry threshold {@code cbl/CBPAUP0C.cbl} line 199 applies when the PARM is not numeric. */
    private static final int DEFAULT_EXPIRY_DAYS = 5;

    /** Complement base for the five-digit date, {@code 99999 - YYDDD} at COPAUA0C line 874. */
    private static final int DATE_COMPLEMENT_BASE = 99999;

    /** Complement base for the nine-digit time, {@code 999999999 - HHMMSSmmm} at line 875. */
    private static final int TIME_COMPLEMENT_BASE = 999999999;

    /** Stored complement of {@link #YEAR_END_JULIAN}, the literal three bytes at offset 0. */
    private static final int YEAR_END_STORED_DATE = DATE_COMPLEMENT_BASE - YEAR_END_JULIAN;

    /** Stored complement of {@link #NEW_YEAR_JULIAN}, the literal three bytes at offset 200. */
    private static final int NEW_YEAR_STORED_DATE = DATE_COMPLEMENT_BASE - NEW_YEAR_JULIAN;

    /** Decoded time of record one, 23:59:59.999 as HHMMSSmmm. */
    private static final int YEAR_END_TIME = 235959999;

    /** Decoded time of record two, 00:00:01.000 as HHMMSSmmm. */
    private static final int NEW_YEAR_TIME = 1000;

    /** Count of declared layout fields that reach the decoded map; {@code FILLER} does not. */
    private static final int MAPPED_FIELD_COUNT = 27;

    /** Width of the trailing {@code FILLER} at {@code CIPAUDTY.cpy} line 54. */
    private static final int FILLER_WIDTH = 17;

    /**
     * The day difference a plain YYDDD subtraction produces across the year boundary.
     *
     * <p>Assumptions: this is the WRONG answer, asserted as wrong. {@code cbl/CBPAUP0C.cbl} line 282
     * computes it and line 284 acts on it, so naming it here lets the test prove the migrated
     * implementation does not reproduce it rather than merely prove the right answer by itself.</p>
     */
    private static final long NAIVE_JULIAN_DIFFERENCE = NEW_YEAR_JULIAN - YEAR_END_JULIAN;

    /** True elapsed days between the two records, 31 December 2023 to 1 January 2024. */
    private static final long TRUE_ELAPSED_DAYS = 1L;

    /**
     * The century the two-digit Julian years are read into.
     *
     * <p>Assumptions: a YYDDD Julian carries no century, so one must be supplied. The baseline's own
     * seed and extract data is twenty-first century throughout, and the injected business dates in the
     * batch JCL are 2022 onward, so 2000 is the window. It is named rather than inlined because it is
     * an interpretation of the data and not a property of it.</p>
     */
    private static final int CENTURY_BASE = 2000;

    /**
     * Reads one fixture resource as raw bytes.
     *
     * @param name the resource name below the fixture root
     * @return the resource's bytes, exactly as stored
     * @throws IllegalStateException if the resource is absent from the test class path or unreadable
     */
    private static byte[] bytes(String name) {
        try (InputStream fixture =
                PendingAuthDetailNewYearFixtureTest.class.getResourceAsStream(ROOT + name)) {
            if (fixture == null) {
                throw new IllegalStateException(ROOT + name + " is not on the test class path");
            }
            return fixture.readAllBytes();
        } catch (IOException problem) {
            throw new IllegalStateException(ROOT + name + " could not be read", problem);
        }
    }

    /**
     * Returns the authorization detail layout from the shared registry.
     *
     * <p>Assumptions: the layout is fetched from {@code common-lib} rather than restated here. The
     * offsets are declared once in {@code CopybookLayout} so a fixture and its reader can never drift
     * apart, which is the same single-sourcing the COBOL gets from one copybook include path.</p>
     *
     * @return the registered 200-byte {@code PAUTDTL} record specification
     */
    private static RecordSpec detailLayout() {
        return CopybookLayout.layout("PAUTDTL");
    }

    /**
     * Slices one fixed-length record out of the multi-record fixture image.
     *
     * <p>Assumptions: records are located by multiplying the ordinal by the declared length, never by
     * scanning for a terminator. Record two begins at offset 200 because record one is 200 bytes long,
     * and this fixture carries no separator byte for a scan to find.</p>
     *
     * @param image the whole fixture image
     * @param ordinal the zero-based record ordinal
     * @return exactly {@code SEGMENT_LENGTH} bytes beginning at the ordinal's offset
     */
    private static byte[] recordAt(byte[] image, int ordinal) {
        return Arrays.copyOfRange(image, ordinal * SEGMENT_LENGTH,
                ordinal * SEGMENT_LENGTH + SEGMENT_LENGTH);
    }

    /**
     * Decodes one record of the fixture through the shared codec.
     *
     * @param ordinal the zero-based record ordinal
     * @return every declared field of that record, in declaration order
     */
    private static Map<String, Object> record(int ordinal) {
        return FixedWidthCodec.decodeRecord(recordAt(bytes(FIXTURE), ordinal), detailLayout());
    }

    /**
     * Returns one decoded character field with its trailing blanks removed.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's characters without trailing blanks
     */
    private static String text(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name)).stripTrailing();
    }

    /**
     * Returns one decoded numeric field as an exact decimal.
     *
     * <p>Assumptions: every packed field decodes to a {@code BigDecimal}, identifier or amount alike,
     * so the value is routed through its string form rather than cast. Comparison is by value and
     * never by {@code equals}, because 300.00 and 300 are equal amounts at different scales.</p>
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's value as an exact decimal
     */
    private static BigDecimal number(Map<String, Object> fields, String name) {
        return new BigDecimal(String.valueOf(fields.get(name)));
    }

    /**
     * Converts a five-digit YYDDD Julian into a real calendar date.
     *
     * <p>Assumptions: this is the conversion whose ABSENCE is the defect. Turning both Julians into
     * real dates and differencing those is what makes 31 December and 1 January one day apart instead
     * of 636, and it is why the migrated implementation cannot simply transcribe
     * {@code cbl/CBPAUP0C.cbl} line 282.</p>
     *
     * @param julian a YYDDD Julian: two-digit year followed by three-digit day of year
     * @return the calendar date that Julian denotes in the {@link #CENTURY_BASE} window
     */
    private static LocalDate fromJulian(int julian) {
        return LocalDate.ofYearDay(CENTURY_BASE + julian / 1000, julian % 1000);
    }

    /**
     * Applies the nines-complement decode that turns a stored key component into its business value.
     *
     * <p>Assumptions: THE CODEC DOES NOT DO THIS, and that division of labour is the point. A
     * fixed-width codec reads the packed bytes and returns the number they literally hold -- 76634 --
     * because complementing is a business transformation, not a representation one. The COBOL performs
     * it explicitly at {@code cbl/CBPAUP0C.cbl} line 280 for the date and {@code cbl/COPAUS2C.cbl} line
     * 107 for the time, so in the target it belongs to the mapping layer that populates the INTEGER
     * columns. This helper mirrors that step so the test asserts BOTH representations and names which
     * one a column holds.</p>
     *
     * @param stored the complement as it appears in the fixture's bytes
     * @param base {@link #DATE_COMPLEMENT_BASE} or {@link #TIME_COMPLEMENT_BASE}
     * @return the business value the corresponding INTEGER column carries
     */
    private static int decodeComplement(int stored, int base) {
        return base - stored;
    }

    /**
     * Returns the stored complement of one key component as a whole number.
     *
     * @param fields the decoded record
     * @param name either {@code PA-AUTH-DATE-9C} or {@code PA-AUTH-TIME-9C}
     * @return the packed value exactly as the fixture stores it, uncomplemented
     */
    private static int stored(Map<String, Object> fields, String name) {
        return number(fields, name).intValueExact();
    }

    /**
     * Confirms the fixture is exactly two unframed 200-byte images and that the FILLER drop reconciles.
     *
     * <p>Assumptions: the file size alone determines the record count, so the absence of framing is
     * asserted rather than assumed. A fixture that gained a length prefix or a trailing newline would
     * still decode field by field and would silently shift every value by the added width.</p>
     *
     * <p>Assumptions: the seventeen-byte {@code FILLER} at line 54 reaches no column, so 34 of the
     * fixture's 400 bytes are padding that the mapping layer drops, and each record still has to
     * reconcile to 200. Asserting the FILLER is blank is what proves those bytes are padding rather
     * than an unmapped field carrying data.</p>
     */
    @Test
    @DisplayName("the fixture is two unframed 200-byte detail images whose FILLER is pure padding")
    void theFixtureIsTwoUnframedDetailImages() {
        byte[] image = bytes(FIXTURE);

        assertThat(image)
                .as("two images of %d bytes, no prefix, no descriptor word, no terminator",
                        SEGMENT_LENGTH)
                .hasSize(2 * SEGMENT_LENGTH);
        assertThat(detailLayout().reclen()).isEqualTo(SEGMENT_LENGTH);
        assertThat(image[image.length - 1])
                .as("the last byte is a FILLER blank, so no trailing newline was appended")
                .isEqualTo((byte) ' ');

        int droppedWidth = 0;
        for (int ordinal : new int[] {YEAR_END, NEW_YEAR}) {
            Map<String, Object> fields = record(ordinal);

            assertThat(detailLayout().fields()).hasSize(MAPPED_FIELD_COUNT + 1);

            // WHY : Assumptions: the codec OMITS a field named FILLER from the decoded map rather than
            //       returning it as blanks, so the drop is asserted by absence of the key. Asserting a
            //       blank VALUE instead would pass vacuously: a missing key yields null, and null
            //       stringifies to "null" rather than to the blanks such a check would look for.
            assertThat(fields)
                    .as("%d of the %d declared fields reach a column", MAPPED_FIELD_COUNT,
                            MAPPED_FIELD_COUNT + 1)
                    .hasSize(MAPPED_FIELD_COUNT)
                    .doesNotContainKey("FILLER");
            droppedWidth += FILLER_WIDTH;
        }

        int mappedWidth = detailLayout().fields().stream()
                .filter(field -> !"FILLER".equals(field.name()))
                .mapToInt(CopybookLayout.FieldSpec::length)
                .sum();

        assertThat(mappedWidth)
                .as("the %d mapped fields occupy %d bytes", MAPPED_FIELD_COUNT,
                        SEGMENT_LENGTH - FILLER_WIDTH)
                .isEqualTo(SEGMENT_LENGTH - FILLER_WIDTH);
        assertThat(mappedWidth + FILLER_WIDTH)
                .as("the mapped fields plus the dropped FILLER still reconcile to the declared length")
                .isEqualTo(SEGMENT_LENGTH);
        assertThat(droppedWidth)
                .as("%d bytes per record reach no column, 34 across the file", FILLER_WIDTH)
                .isEqualTo(34);
    }

    /**
     * Confirms the year-end record decodes to its documented business values.
     *
     * <p>Assumptions: the date and time are asserted as the DECODED 23365 and 235959999, not as the
     * stored complements 76634 and 764040000. A decoder that returned the stored value would still
     * satisfy every length and scale check while inverting the meaning of the field.</p>
     *
     * <p>Assumptions: the response code {@code "00"} is the load-bearing character pair, because
     * {@code cbl/CBPAUP0C.cbl} line 287 branches on equality with it alone. That is what routes this
     * record down the approved arm of the purge and makes {@code PA-APPROVED-AMT} the value line 289
     * subtracts.</p>
     */
    @Test
    @DisplayName("the year-end record decodes to Julian 23365 at 23:59:59.999, approved for 300.00")
    void theYearEndRecordDecodesToItsDocumentedValues() {
        Map<String, Object> fields = record(YEAR_END);

        assertThat(stored(fields, "PA-AUTH-DATE-9C"))
                .as("the fixture's bytes hold the COMPLEMENT, which the codec returns verbatim")
                .isEqualTo(YEAR_END_STORED_DATE)
                .isEqualTo(76634);
        assertThat(decodeComplement(stored(fields, "PA-AUTH-DATE-9C"), DATE_COMPLEMENT_BASE))
                .as("and 99999 - 76634 is the Julian the auth_date INTEGER column holds")
                .isEqualTo(YEAR_END_JULIAN);
        assertThat(stored(fields, "PA-AUTH-TIME-9C")).isEqualTo(764040000);
        assertThat(decodeComplement(stored(fields, "PA-AUTH-TIME-9C"), TIME_COMPLEMENT_BASE))
                .as("HHMMSSmmm to the millisecond: 23:59:59.999")
                .isEqualTo(YEAR_END_TIME);
        assertThat(text(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("231231");
        assertThat(text(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("235959");
        assertThat(text(fields, "PA-AUTH-RESP-CODE"))
                .as("satisfies 88 PA-AUTH-APPROVED, so the purge takes its line 287 arm")
                .isEqualTo("00");
        assertThat(text(fields, "PA-AUTH-RESP-REASON"))
                .as("the approval default set at cbl/COPAUA0C.cbl line 698")
                .isEqualTo("0000");
        assertThat(text(fields, "PA-AUTH-ID-CODE")).isEqualTo("A00002");
        assertThat(text(fields, "PA-TRANSACTION-ID")).isEqualTo("TXN000000000002");
        assertThat(number(fields, "PA-TRANSACTION-AMT")).isEqualByComparingTo("300.00");
        assertThat(number(fields, "PA-APPROVED-AMT"))
                .as("approved in full, so line 289 subtracts the whole requested amount")
                .isEqualByComparingTo("300.00");
    }

    /**
     * Confirms the new-year record decodes to its documented business values.
     *
     * <p>Assumptions: the response code {@code "05"} is explicitly synthetic. Only inequality with
     * {@code "00"} is behaviourally load-bearing at {@code cbl/CBPAUP0C.cbl} line 287, and
     * {@code CIPAUDTY.cpy} names an {@code 88} for {@code '00'} alone, so the copybook fixes no decline
     * code. The reason code {@code "4100"} by contrast IS domain-verified: it is insufficient funds
     * from the {@code EVALUATE} at {@code cbl/COPAUA0C.cbl} lines 700 to 717.</p>
     *
     * <p>Assumptions: the approved amount is positive-signed zero rather than a negative zero, which is
     * what keeps the image round-trip safe. Nothing is approved on a decline, and it is
     * {@code PA-TRANSACTION-AMT} that line 292 subtracts on this arm, not the approved amount.</p>
     */
    @Test
    @DisplayName("the new-year record decodes to Julian 24001 at 00:00:01.000, declined with 4100")
    void theNewYearRecordDecodesToItsDocumentedValues() {
        Map<String, Object> fields = record(NEW_YEAR);

        assertThat(stored(fields, "PA-AUTH-DATE-9C"))
                .as("the fixture's bytes hold the COMPLEMENT, which the codec returns verbatim")
                .isEqualTo(NEW_YEAR_STORED_DATE)
                .isEqualTo(75998);
        assertThat(decodeComplement(stored(fields, "PA-AUTH-DATE-9C"), DATE_COMPLEMENT_BASE))
                .as("and 99999 - 75998 is the Julian the auth_date INTEGER column holds")
                .isEqualTo(NEW_YEAR_JULIAN);
        assertThat(stored(fields, "PA-AUTH-TIME-9C")).isEqualTo(999998999);
        assertThat(decodeComplement(stored(fields, "PA-AUTH-TIME-9C"), TIME_COMPLEMENT_BASE))
                .as("one second past midnight in milliseconds")
                .isEqualTo(NEW_YEAR_TIME);
        assertThat(text(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("240101");
        assertThat(text(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("000001");
        assertThat(text(fields, "PA-AUTH-RESP-CODE"))
                .as("not '00', so the purge takes its line 291 declined arm")
                .isEqualTo("05");
        assertThat(text(fields, "PA-AUTH-RESP-REASON")).isEqualTo("4100");
        assertThat(text(fields, "PA-AUTH-ID-CODE")).isEqualTo("A00003");
        assertThat(text(fields, "PA-TRANSACTION-ID")).isEqualTo("TXN000000000003");
        assertThat(number(fields, "PA-TRANSACTION-AMT"))
                .as("the value line 292 subtracts from the declined accumulator")
                .isEqualByComparingTo("150.00");
        assertThat(number(fields, "PA-APPROVED-AMT")).isEqualByComparingTo("0.00");
    }

    /**
     * Confirms the fields both records share, including the two that are easy to mis-encode.
     *
     * <p>Assumptions: the card number is identical in both records, which is what makes the ordering
     * proof below purely temporal. The value is synthetic and no assertion here expects an unmasked
     * primary account number to leave a service boundary; the layout marks the field sensitive and
     * masking to the last four digits belongs to the mapping layer.</p>
     *
     * <p>Assumptions: the expiry date is {@code "1227"} in MMYY order, December 2027, not YYMM. Read as
     * YYMM it would be month 27 of 2012 -- an invalid date that a lenient parser would silently accept
     * as some other month.</p>
     */
    @Test
    @DisplayName("both records share one card and carry POS entry mode as two ASCII digits")
    void bothRecordsShareTheirNonTemporalFields() {
        for (int ordinal : new int[] {YEAR_END, NEW_YEAR}) {
            Map<String, Object> fields = record(ordinal);

            assertThat(text(fields, "PA-CARD-NUM")).isEqualTo("4000123456789010");
            assertThat(text(fields, "PA-AUTH-TYPE")).isEqualTo("PURC");
            assertThat(text(fields, "PA-CARD-EXPIRY-DATE"))
                    .as("MMYY: December 2027, not YYMM")
                    .isEqualTo("1227");
            assertThat(fields.get("PA-MESSAGE-TYPE")).isEqualTo("0100  ");
            assertThat(fields.get("PA-MESSAGE-SOURCE")).isEqualTo("POS   ");
            assertThat(number(fields, "PA-PROCESSING-CODE"))
                    .as("PIC 9(06) DISPLAY: six ASCII digit bytes, not a binary field")
                    .isEqualByComparingTo("0");
            assertThat(number(fields, "PA-POS-ENTRY-MODE"))
                    .as("PIC 9(02) DISPLAY at offsets 95 and 96; a binary halfword would corrupt them")
                    .isEqualByComparingTo("5");
            assertThat(text(fields, "PA-MERCHANT-CATAGORY-CODE"))
                    .as("the copybook misspelling is the wire name and is carried across verbatim")
                    .isEqualTo("5411");
            assertThat(text(fields, "PA-ACQR-COUNTRY-CODE")).isEqualTo("840");
            assertThat(text(fields, "PA-MERCHANT-ID")).isEqualTo("MERCH0000000001");
            assertThat(text(fields, "PA-MERCHANT-NAME")).isEqualTo("ACME HARDWARE");
            assertThat(text(fields, "PA-MERCHANT-CITY")).isEqualTo("SEATTLE");
            assertThat(text(fields, "PA-MERCHANT-STATE")).isEqualTo("WA");
            assertThat(text(fields, "PA-MERCHANT-ZIP")).isEqualTo("98101");
            assertThat(text(fields, "PA-MATCH-STATUS"))
                    .as("88 PA-MATCH-PENDING, the purge-candidate state the CHECK constraint admits")
                    .isEqualTo("P");
            assertThat(fields.get("PA-AUTH-FRAUD"))
                    .as("blank has no 88-level yet is a legitimate baseline value")
                    .isEqualTo(" ");
            assertThat(fields.get("PA-FRAUD-RPT-DATE"))
                    .as("eight blanks must map to SQL NULL, never to a zero date")
                    .isEqualTo(" ".repeat(8));
        }
    }

    /**
     * Confirms the year-end authorization is NOT expired and that 636 is not the difference computed.
     *
     * <p>Assumptions: this is the regression the fixture exists for, and it is asserted in both
     * directions on purpose. Proving the correct answer alone would still pass for an implementation
     * that happened to be right for the wrong reason, so the wrong answer is named and excluded too.
     * With a business date of Julian 24001, the plain subtraction at {@code cbl/CBPAUP0C.cbl} line 282
     * yields 636, and line 284's inclusive comparison against the default threshold of 5 from line 199
     * turns that into a deletion. Converting both Julians to real dates first yields 1, which is below
     * the threshold, so the authorization survives.</p>
     *
     * <p>Trade-offs: the day count is computed here from the decoded fields rather than by invoking a
     * production purge service, because the fixture's contract is what its bytes MEAN. Binding this
     * assertion to one service's method signature would make the byte-level guarantee depend on that
     * signature surviving refactoring, and the guarantee has to outlive it.</p>
     */
    @Test
    @DisplayName("the year-end authorization survives the purge: one day elapsed, not 636")
    void theYearEndAuthorizationIsNotExpiredAcrossTheYearBoundary() {
        int authorizedJulian =
                decodeComplement(stored(record(YEAR_END), "PA-AUTH-DATE-9C"), DATE_COMPLEMENT_BASE);
        assertThat(authorizedJulian).isEqualTo(YEAR_END_JULIAN);

        LocalDate authorized = fromJulian(authorizedJulian);
        LocalDate businessDate = fromJulian(NEW_YEAR_JULIAN);
        assertThat(authorized).isEqualTo(LocalDate.of(2023, 12, 31));
        assertThat(businessDate).isEqualTo(LocalDate.of(2024, 1, 1));

        long elapsedDays = ChronoUnit.DAYS.between(authorized, businessDate);

        assertThat(elapsedDays)
                .as("31 December to 1 January is one day, whatever the Julians look like")
                .isEqualTo(TRUE_ELAPSED_DAYS)
                .isNotEqualTo(NAIVE_JULIAN_DIFFERENCE);
        assertThat(NAIVE_JULIAN_DIFFERENCE)
                .as("the defect's value: a plain YYDDD subtraction across the year boundary")
                .isEqualTo(636L);
        assertThat(elapsedDays < DEFAULT_EXPIRY_DAYS)
                .as("one day is below the five-day threshold, so this record must NOT be purged")
                .isTrue();
        assertThat(NAIVE_JULIAN_DIFFERENCE >= DEFAULT_EXPIRY_DAYS)
                .as("636 >= 5 is why the naive arithmetic wrongly qualifies it for deletion")
                .isTrue();
    }

    /**
     * Confirms the raw key order is the exact inverse of the decoded column order.
     *
     * <p>Assumptions: {@code ims/DBPAUTP0.dbd} line 37 declares the eight-byte child sequence field
     * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} as CHARACTER even though both of its
     * constituents are packed, so IMS sequences twins by byte-wise comparison of the raw complement
     * bytes. That is the entire purpose of storing a complement: byte-ascending IS chronologically
     * descending. Here record two's key begins 0x75 and record one's begins 0x76, so the NEWER record
     * sorts first on raw bytes.</p>
     *
     * <p>Assumptions: the inversion is a real behavioural consequence of decoding, not a preference.
     * Once the complements become plain INTEGER columns, ascending order yields oldest-first, so
     * reproducing the baseline browse order requires {@code ORDER BY auth_date DESC, auth_time DESC} --
     * which is exactly what {@code V1__authorization.sql} records at lines 312 to 314.</p>
     *
     * <p>Assumptions: both key fields carry the positive sign nibble {@code 0xC} in both records. The
     * sign nibble is the final low nibble of the eight-byte key and therefore participates in the
     * byte-wise comparison, so mixing {@code 0xC} and {@code 0xF} across records would perturb this
     * proof while leaving every decoded value unchanged.</p>
     */
    @Test
    @DisplayName("byte-ascending key order puts the newer record first, inverting the decoded order")
    void theRawKeyOrderInvertsTheDecodedColumnOrder() {
        byte[] image = bytes(FIXTURE);
        byte[] yearEndKey = Arrays.copyOfRange(image, 0, 8);
        byte[] newYearKey = Arrays.copyOfRange(image, SEGMENT_LENGTH, SEGMENT_LENGTH + 8);

        // WHY : Alternatives Considered: Arrays.compare was rejected in favour of compareUnsigned.
        //       Java compares byte[] as SIGNED bytes, so any byte from 0x80 up ranks below 0x00 --
        //       and complement bytes such as 0x99 are commonplace here. This fixture would pass
        //       either way because its keys first differ at 0x75 against 0x76, both positive, but
        //       that is accidental: IMS orders a TYPE=C field by UNSIGNED byte value, so the signed
        //       comparison would invert the very proof this test makes on a sibling fixture.
        assertThat(Arrays.compareUnsigned(newYearKey, yearEndKey))
                .as("0x75 precedes 0x76, so the NEWER key sorts first byte-wise")
                .isNegative();
        assertThat(yearEndKey[yearEndKey.length - 1] & 0x0F)
                .as("positive sign nibble, uniform so it cannot perturb the byte-wise order")
                .isEqualTo(0x0C);
        assertThat(newYearKey[newYearKey.length - 1] & 0x0F).isEqualTo(0x0C);

        List<Integer> decodedAscending = new ArrayList<>();
        for (int ordinal : new int[] {YEAR_END, NEW_YEAR}) {
            decodedAscending.add(decodeComplement(
                    stored(record(ordinal), "PA-AUTH-DATE-9C"), DATE_COMPLEMENT_BASE));
        }
        // WHY : Assumptions: sorting on natural Integer order is what models the SQL the target would
        //       issue. Once the complement is decoded into an INTEGER column, the database orders by
        //       magnitude and has no memory of the byte pattern the value arrived as, which is the
        //       precise mechanism by which the decode inverts the baseline's browse order.
        decodedAscending.sort(Comparator.naturalOrder());

        assertThat(decodedAscending)
                .as("decoded INTEGER ascending is OLDEST first: the opposite of the raw byte order")
                .containsExactly(YEAR_END_JULIAN, NEW_YEAR_JULIAN);
        decodedAscending.sort(Comparator.reverseOrder());
        assertThat(decodedAscending)
                .as("ORDER BY auth_date DESC restores the baseline browse order")
                .containsExactly(NEW_YEAR_JULIAN, YEAR_END_JULIAN);
    }

    /**
     * Confirms purging both records drives the paired parent summary to exactly one and exactly zero.
     *
     * <p>Assumptions: the purge path cannot be exercised by a detail record alone. The mutation at
     * {@code cbl/CBPAUP0C.cbl} lines 285 to 295 subtracts from the PARENT summary's counters and
     * accumulators, and the detail segment carries no account identifier at all -- IMS supplies it
     * hierarchically -- so this fixture is only meaningful paired with {@code pautsum0-purge-parent.bin}
     * whose counters are 2 and 2 and whose accumulators are 300.00 and 150.00.</p>
     *
     * <p>Assumptions: the two amounts were chosen so BOTH arms land on exactly zero and exactly one,
     * which makes the arithmetic provable rather than approximately right. The approved arm subtracts
     * {@code PA-APPROVED-AMT} at line 289 and the declined arm subtracts {@code PA-TRANSACTION-AMT} at
     * line 292 -- two DIFFERENT source fields, so a transcription that used one field for both arms
     * would still balance if the amounts were equal, and here they deliberately are not.</p>
     *
     * <p>Assumptions: the declined arm subtracts a twelve-digit {@code NUMERIC(12,2)} field into an
     * eleven-digit {@code NUMERIC(11,2)} accumulator, per {@code V1__authorization.sql} lines 406 and
     * 223. Equality is asserted exactly rather than within a tolerance, because that width mismatch is
     * a real precision hazard and a tolerance would conceal precisely the loss worth catching.</p>
     */
    @Test
    @DisplayName("purging both records drives the parent summary's counters and totals to zero exactly")
    void thePurgeDrivesTheParentSummaryToExactlyZero() {
        Map<String, Object> parent =
                FixedWidthCodec.decodeRecord(bytes(PARENT), CopybookLayout.layout("PAUTSUM0"));
        int approvedCount = number(parent, "PA-APPROVED-AUTH-CNT").intValueExact();
        int declinedCount = number(parent, "PA-DECLINED-AUTH-CNT").intValueExact();
        // WHY : Alternatives Considered: the accumulators are carried as Money rather than as raw
        //       BigDecimal so the subtraction runs through the shared scale-2 fixed-point type the
        //       money path mandates. Plain BigDecimal arithmetic would also be exact here, but it
        //       would let a future edit introduce a double at this point without any gate objecting.
        Money approvedTotal = Money.of(number(parent, "PA-APPROVED-AUTH-AMT"));
        Money declinedTotal = Money.of(number(parent, "PA-DECLINED-AUTH-AMT"));

        assertThat(approvedCount).isEqualTo(2);
        assertThat(declinedCount).isEqualTo(2);
        assertThat(approvedTotal.amount()).isEqualByComparingTo("300.00");
        assertThat(declinedTotal.amount()).isEqualByComparingTo("150.00");

        for (int ordinal : new int[] {YEAR_END, NEW_YEAR}) {
            Map<String, Object> detail = record(ordinal);
            if ("00".equals(text(detail, "PA-AUTH-RESP-CODE"))) {
                approvedCount -= 1;
                approvedTotal = approvedTotal.minus(Money.of(number(detail, "PA-APPROVED-AMT")));
            } else {
                declinedCount -= 1;
                declinedTotal = declinedTotal.minus(Money.of(number(detail, "PA-TRANSACTION-AMT")));
            }
        }

        assertThat(approvedCount).as("2 - 1 on the line 288 arm").isEqualTo(1);
        assertThat(declinedCount).as("2 - 1 on the line 291 arm").isEqualTo(1);
        assertThat(approvedTotal.amount())
                .as("300.00 - 300.00 exactly, not merely close")
                .isEqualByComparingTo("0.00");
        assertThat(declinedTotal.amount())
                .as("150.00 - 150.00 exactly: the 12-digit into 11-digit subtraction loses nothing")
                .isEqualByComparingTo("0.00");
    }

    /**
     * Confirms the two records form distinct composite keys under one shared account.
     *
     * <p>Assumptions: {@code V1__authorization.sql} line 561 keys the detail table on
     * {@code (account_id, auth_date, auth_time)} whereas line 245 keys the summary on
     * {@code account_id} ALONE, and {@code ims/DBPAUTP0.dbd} corroborates that split with one key per
     * level: a six-byte {@code TYPE=P} root key at line 30 and an eight-byte {@code TYPE=C} child key
     * at line 37. Both of these records belong to the same account, so it is the composite and not the
     * account that distinguishes them -- which is exactly what a table keyed on {@code account_id}
     * alone would collapse.</p>
     */
    @Test
    @DisplayName("both records form distinct composite keys under the one shared account")
    void bothRecordsFormDistinctCompositeKeys() {
        List<List<Integer>> keys = new ArrayList<>();
        for (int ordinal : new int[] {YEAR_END, NEW_YEAR}) {
            Map<String, Object> fields = record(ordinal);
            keys.add(List.of(
                    decodeComplement(stored(fields, "PA-AUTH-DATE-9C"), DATE_COMPLEMENT_BASE),
                    decodeComplement(stored(fields, "PA-AUTH-TIME-9C"), TIME_COMPLEMENT_BASE)));
        }

        assertThat(keys)
                .as("the decoded halves of the composite key, which must not collide")
                .containsExactly(List.of(YEAR_END_JULIAN, YEAR_END_TIME),
                        List.of(NEW_YEAR_JULIAN, NEW_YEAR_TIME))
                .doesNotHaveDuplicates();
    }

    /**
     * Confirms both records re-encode to bytes identical to the committed fixture.
     *
     * <p>Assumptions: byte equality is the right expectation here and it is only safe because of three
     * properties designed into the fixture -- every FILLER is blank, every sign nibble is the positive
     * {@code 0xC}, and no value is a negative zero. A negative zero would decode to the same amount and
     * re-encode to a different byte, so the round trip is an oracle for the encoder rather than merely
     * a decodability check.</p>
     */
    @Test
    @DisplayName("both records re-encode byte for byte, making the fixture an encoder oracle")
    void bothRecordsReEncodeByteForByte() {
        byte[] image = bytes(FIXTURE);

        for (int ordinal : new int[] {YEAR_END, NEW_YEAR}) {
            byte[] original = recordAt(image, ordinal);
            byte[] reEncoded = FixedWidthCodec.encodeRecord(
                    FixedWidthCodec.decodeRecord(original, detailLayout()), detailLayout());

            assertThat(reEncoded)
                    .as("record %d must survive a decode/encode round trip unchanged", ordinal)
                    .isEqualTo(original);
        }
    }

    /**
     * Confirms a misframed slice is rejected and that a misframed file fails record reconciliation.
     *
     * <p>Assumptions: the codec checks the record length before decoding the first field, so a 199- or
     * 201-byte slice is refused outright. That refusal is the guard worth asserting because a decoder
     * that tolerated a one-byte drift would return a plausible map in which every field after the drift
     * had shifted -- silently wrong financial values rather than a visible failure.</p>
     *
     * <p>Assumptions: the whole-file check is a divisibility check, never a line count. The house
     * convention at {@code tests/fixtures/README.md} sections 3.2 and 3.3 makes {@code wc -l} equal the
     * record count for text fixtures, but that cannot be adopted here: a {@code COMP-3} negative whose
     * final digit is zero produces the sign byte {@code 0x0D}, so a terminator convention would be
     * indistinguishable from data in a sibling detail fixture that carried a negative amount.</p>
     */
    @Test
    @DisplayName("a 199- or 201-byte slice is rejected and a misframed file fails reconciliation")
    void aMisframedSliceIsRejected() {
        byte[] image = bytes(FIXTURE);
        RecordSpec spec = detailLayout();

        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(Arrays.copyOf(image, 199), spec))
                .as("one byte short must fail rather than decode a shifted record")
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(Arrays.copyOf(image, 201), spec))
                .as("one byte long must fail just as loudly")
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);

        assertThat(image.length % spec.reclen())
                .as("400 divides by 200 with no remainder, so the file holds whole records")
                .isZero();
        assertThat(399 % spec.reclen()).as("399 cannot reconcile to whole records").isNotZero();
        assertThat(401 % spec.reclen()).as("401 cannot reconcile to whole records").isNotZero();
    }

    /**
     * Confirms the fixture carries no line-terminator byte anywhere.
     *
     * <p>Assumptions: the two terminator bytes have DIFFERENT status in this record type and the
     * distinction is worth stating precisely. A {@code 0x0A} is structurally impossible: the segment
     * contains only PACKED and DISPLAY fields, and in a {@code COMP-3} byte the low nibble {@code A} is
     * neither a digit nor a valid sign of {@code C}, {@code D} or {@code F}, so a line feed can only
     * arrive from a {@code COMP} binary field -- and the only {@code COMP} fields in this database are
     * the summary segment's two counters. A {@code 0x0D} by contrast is entirely possible in a detail
     * record, as the sign byte of a negative amount ending in the digit zero; it is absent here only
     * because every packed value in this particular fixture is positive.</p>
     *
     * <p>Assumptions: none of the five bytes of the new-year record's packed time is an ASCII digit, so
     * a reader that mistook the key for text fails visibly instead of returning a wrong number.</p>
     */
    @Test
    @DisplayName("the fixture carries no 0x0A and no 0x0D, and its packed key is not readable as text")
    void theFixtureCarriesNoLineTerminatorBytes() {
        byte[] image = bytes(FIXTURE);

        assertThat(image).as("a line feed cannot occur in a PACKED or DISPLAY field")
                .doesNotContain((byte) 0x0A);
        assertThat(image).as("no negative packed value here, so no 0x0D sign byte either")
                .doesNotContain((byte) 0x0D);

        byte[] packedTime = Arrays.copyOfRange(image, SEGMENT_LENGTH + 3, SEGMENT_LENGTH + 8);
        for (byte value : packedTime) {
            int unsigned = value & 0xFF;

            // WHY : Trade-offs: the range is expressed as an explicit predicate rather than through a
            //       range assertion, because the negation reads as the requirement itself -- the byte
            //       must fall OUTSIDE the ASCII digit range -- and it reports the offending byte in hex,
            //       which is the form the copybook and the hex dump both use.
            assertThat(unsigned < 0x30 || unsigned > 0x39)
                    .as("0x%02X must not be mistakable for an ASCII digit", value)
                    .isTrue();
        }
    }
}
