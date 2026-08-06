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
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes {@code pautdtl1-time-leading-nines.bin} and asserts the contract its bytes carry.
 *
 * <p>Purpose: this class is the executable documentation of one 200-byte authorization detail
 * segment whose eight key bytes are {@code 75 89 9C 99 99 98 99 9C}. Those bytes look like
 * corruption and are not: they are the arithmetically correct encoding of an authorization taken
 * one second after midnight. Without a consumer stating that, a maintainer reading a hex dump would
 * "repair" the fixture into ASCII digits and destroy the property it exists to prove. The fixture
 * carries no comment of its own -- a comment byte would push the record past the declared 200 and
 * corrupt the trailing filler -- so the explanation lives here, in the Javadoc of the code that
 * reads it, where {@code config/checkstyle/checkstyle.xml} polices its presence and completeness on
 * every build rather than leaving it to review.
 *
 * <h2>Assumptions: the layout</h2>
 *
 * <p>The record is the IMS child segment {@code PAUTDTL1}, transcribed from
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54 and registered as
 * {@code PAUTDTL} in {@code CopybookLayout}. Its 27 named elementary fields plus a seventeen-byte
 * trailing filler sum to exactly 200, which two independent sources corroborate:
 * {@code ims/DBPAUTP0.dbd} declares {@code SEGM NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200} at
 * its line 36, and {@code ims/PADFLDBD.DBD} declares {@code RECORD=(200),RECFM=F} at its line 27.
 * Every offset asserted below is read from the registry rather than restated here, so a wrong
 * offset in either the registry or the fixture fails in this class instead of passing quietly.
 *
 * <p>The segment carries <b>no account identifier</b>: IMS supplies it hierarchically from the
 * parent. This record belongs to {@code PA-ACCT-ID} 10000000001, the account
 * {@code pautsum0-canonical.bin} carries, which is what satisfies the foreign key
 * {@code fk_pending_auth_detail_summary} that {@code V1__authorization.sql} declares on
 * {@code account_id}.
 *
 * <h2>Assumptions: the nines complement, and why its bytes are deliberately non-ASCII</h2>
 *
 * <p>{@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} builds the key from the server clock
 * and then inverts it. Its line 868 takes a five-digit Julian date, its lines 871 and 872 build a
 * nine-digit time by multiplying the six-digit clock time by 1000 and adding the milliseconds, and
 * its lines 874 and 875 store {@code 99999 - date} and {@code 999999999 - time}. The inverse is
 * applied on the way out, at {@code cbl/CBPAUP0C.cbl} line 280 for the date and
 * {@code cbl/COPAUS2C.cbl} line 107 for the time, after which line 108 onwards slices the nine
 * digits into hours, minutes, seconds and milliseconds.
 *
 * <p>Assumptions: the inversion exists to make byte order equal reverse chronological order.
 * {@code ims/DBPAUTP0.dbd} line 37 declares the eight-byte sequence field
 * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} -- <b>character</b>, not packed -- so
 * IMS sorts sibling detail segments by comparing those eight bytes directly, and the root's
 * {@code POINTER=(TWINBWD)} at line 29 walks that order backwards. A larger complement therefore
 * sorts later and means an earlier instant, which is how the newest authorization is reached first
 * without a descending index.
 *
 * <p>Because the sort is byte-wise over the raw span, the <b>sign nibble participates in it</b>.
 * Both key fields here consequently use the signed positive nibble {@code 0xC}, uniformly and
 * deliberately. An unsigned {@code 0xF} would decode to the same number while sorting after every
 * {@code 0xC} sibling, so a mixed convention across this directory would reorder twins while every
 * asserted value stayed correct -- a failure with no symptom, which is why the nibble is asserted
 * below rather than assumed.
 *
 * <p>Assumptions: a near-midnight authorization is operationally ordinary, not contrived. The
 * encoding is a subtraction, so a <em>small</em> business time yields a <em>large</em> complement,
 * and the nightly batch window is exactly where small times occur. This record's business time of
 * 1000 -- 00:00:01.000 -- complements to 999998999, whose nibbles are almost all nines. The extreme
 * is worth naming for completeness: business time 0 complements to 999999999, laying down
 * {@code 99 99 99 99 9C}, every digit nibble at nine.
 *
 * <p>Trade-offs: that arithmetic is what makes all eight key bytes fall <b>outside</b> the ASCII
 * digit range {@code 0x30}-{@code 0x39}, and that is the property this fixture was written to pin.
 * A reader that treats the key as fixed-width text produces mojibake or a decode error, so the
 * mistake announces itself. The complementary hazard is the opposite shape: a pipeline that reads
 * the packed span correctly but forgets to invert it stores 75899 as a date, which is a well-formed
 * integer and fails nowhere. The range check in
 * {@link #theComplementDecodesToTheValuesTheIntegerColumnsCarry()} is what catches that second
 * shape, and both are asserted here so the pair is documented together.
 *
 * <h2>Assumptions: the fixture holds the complement, the columns hold the decoded value</h2>
 *
 * <p>{@code V1__authorization.sql} declares {@code auth_date} and {@code auth_time} as
 * {@code INTEGER NOT NULL} holding <b>decoded</b> values, and makes
 * {@code (account_id, auth_date, auth_time)} the primary key. The bytes therefore decode in two
 * steps and both are asserted separately: the packed span yields the stored complement, and the
 * subtraction yields the 24100 and 1000 that reach the columns. Asserting only the second step
 * would pass for a fixture that stored the business values directly and so exercised no complement
 * at all.
 *
 * <h2>Assumptions: two packed geometries with opposite pad rules</h2>
 *
 * <p>{@code PIC S9(10)V99 COMP-3} is twelve digits in seven bytes, which is fourteen nibbles for
 * thirteen used, so it carries <b>one leading pad nibble that must be zero</b> and
 * {@code PackedDecimalCodec} raises if it is not. The sibling summary segment's
 * {@code PIC S9(09)V99 COMP-3} is eleven digits in six bytes, twelve nibbles for twelve used, so it
 * carries <b>no pad at all</b> and a zero high nibble there is a real digit whose loss would go
 * unnoticed. Two files in one directory, opposite rules, which is why
 * {@link #theMoneySpansCarryTheMandatoryZeroPadNibble()} asserts the pad rather than only the
 * amount.
 *
 * <h2>Assumptions: the POS entry mode is text, not a binary halfword</h2>
 *
 * <p>Three sources describe this two-byte field and they do not agree.
 * {@code cpy/CIPAUDTY.cpy} line 38 declares {@code PIC 9(02)}, which is two display characters;
 * {@code dcl/AUTHFRDS.dcl} line 71 declares the Db2 host variable {@code PIC S9(4) USAGE COMP},
 * which is a binary halfword; and {@code ddl/AUTHFRDS.ddl} line 16, together with the target
 * migration, declares {@code SMALLINT}. The segment is authoritative for the segment, so the bytes
 * are the ASCII pair {@code 30 35}; {@code cbl/COPAUS2C.cbl} line 128 is where the conversion to
 * the binary host variable happens. Writing a halfword into the fixture would corrupt those two
 * bytes while leaving the record's length untouched.
 */
class PendingAuthDetailComplementKeyFixtureTest {

    /** The classpath directory every fixture in this module lives under. */
    private static final String FIXTURE = "fixtures/pautdtl1-time-leading-nines.bin";

    /** The registry name of the layout the fixture is written against. */
    private static final String LAYOUT = "PAUTDTL";

    /**
     * The account the parent summary segment carries, which this detail segment belongs to.
     *
     * <p>Assumptions: the value is supplied by the test rather than read from the record, because
     * {@code cpy/CIPAUDTY.cpy} declares no account field at all -- IMS inherits the parent key
     * hierarchically, and the relational target restores it as a column.</p>
     */
    private static final long PARENT_ACCOUNT_ID = 10_000_000_001L;

    /** The five-digit Julian date the complement at offset zero decodes to. */
    private static final int BUSINESS_JULIAN_DATE = 24_100;

    /** The nine-digit {@code HHMMSSmmm} time the complement at offset three decodes to. */
    private static final int BUSINESS_TIME_MS = 1_000;

    /** The stored complement of {@link #BUSINESS_JULIAN_DATE}, as the packed span holds it. */
    private static final int STORED_DATE_COMPLEMENT = 75_899;

    /** The stored complement of {@link #BUSINESS_TIME_MS}, as the packed span holds it. */
    private static final int STORED_TIME_COMPLEMENT = 999_998_999;

    /** The lowest ASCII digit code point, the floor of the range the key bytes must avoid. */
    private static final int ASCII_ZERO = 0x30;

    /** The highest ASCII digit code point, the ceiling of the range the key bytes must avoid. */
    private static final int ASCII_NINE = 0x39;

    /** The number of bytes the two complement components occupy together. */
    private static final int KEY_WIDTH = 8;

    /**
     * The condition every key byte must satisfy: it is not the code point of an ASCII digit.
     *
     * <p>Trade-offs: the check is a predicate rather than a range assertion because AssertJ's
     * integer assertion offers {@code isBetween} but no negated form, so the alternative was to
     * assert a boolean and lose the offending value from the failure message. A predicate keeps both
     * the value and the reason in the report, which matters here: the message is what tells a
     * maintainer that a byte inside the digit range means the key was rewritten as text.</p>
     */
    private static final java.util.function.Predicate<Integer> NOT_AN_ASCII_DIGIT =
            candidate -> candidate < ASCII_ZERO || candidate > ASCII_NINE;

    /** The description AssertJ prints for {@link #NOT_AN_ASCII_DIGIT} when it fails. */
    private static final String ASCII_DIGIT_RANGE = "outside the ASCII digit range 0x30-0x39";

    /**
     * Reads the fixture as raw bytes.
     *
     * <p>Assumptions: the resource is reached through the class loader rather than a filesystem
     * path, which is how the packaged test jar reaches it and what keeps the assertion independent
     * of the working directory a runner happens to choose.</p>
     *
     * @return the fixture's bytes, exactly as stored
     * @throws AssertionError if the fixture is not on the test classpath
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] image() {
        try (InputStream stream = PendingAuthDetailComplementKeyFixtureTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new AssertionError("fixture " + FIXTURE + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + FIXTURE + " could not be read", failure);
        }
    }

    /**
     * Decodes the fixture through the registered layout.
     *
     * <p>Assumptions: the descriptor is the registry instance rather than a locally built copy,
     * because {@code FixedWidthCodec} tests descriptor identity when it decides whether a blank
     * filler is droppable padding. Passing a copy would retain the seventeen filler bytes and make
     * {@link #theTrailingFillerReachesNoFieldYetTheRecordStillCloses()} assert the opposite of the
     * contract.</p>
     *
     * @return the decoded fields in copybook declaration order
     */
    private static Map<String, Object> decoded() {
        return FixedWidthCodec.decodeRecord(image(), CopybookLayout.layout(LAYOUT));
    }

    /**
     * Returns one decoded field as text with its trailing blanks removed.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's characters with trailing blanks stripped
     */
    private static String textField(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name)).stripTrailing();
    }

    /**
     * Returns one decoded field as an exact decimal.
     *
     * <p>Assumptions: the value is routed through its own string form rather than cast, because a
     * packed span decodes to a {@code BigDecimal} while an unsigned display span decodes to a whole
     * number, and both must be comparable here without the assertion caring which.</p>
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's value as an exact decimal
     */
    private static BigDecimal amountField(Map<String, Object> fields, String name) {
        return new BigDecimal(String.valueOf(fields.get(name)));
    }

    /**
     * Confirms the fixture is exactly one record of the length the segment declares.
     *
     * <p>Assumptions: 200 is asserted with no tolerance for a terminator byte. The reference
     * suite's text fixtures end in a newline so that a line count equals a record count, and that
     * convention cannot be carried here: this record's last field is the seventeen-byte filler, so
     * an appended byte would land past it and make the record 201 -- one over the declared length,
     * which is the corruption the newline convention was written to prevent in the first place.</p>
     */
    @Test
    @DisplayName("the fixture is exactly one 200-byte segment with no terminator byte")
    void theFixtureIsExactlyTheDeclaredSegmentLength() {
        byte[] record = image();
        RecordSpec spec = CopybookLayout.layout(LAYOUT);

        assertThat(spec.reclen()).isEqualTo(200);
        assertThat(record).hasSize(200);
        assertThat(record.length % spec.reclen()).isZero();
        assertThat(record[record.length - 1]).as("final byte is blank filler, not a terminator")
                .isEqualTo((byte) ' ');
    }

    /**
     * Confirms no byte of the eight-byte key falls inside the ASCII digit range.
     *
     * <p>Assumptions: this single assertion is the fixture's reason to exist, and it is stated
     * mechanically so the claim cannot decay into a comment nobody checks. A reader that treats the
     * key as fixed-width text fails visibly on these bytes, which is the whole point: the failure
     * announces itself instead of producing a plausible wrong date.</p>
     */
    @Test
    @DisplayName("every one of the eight key bytes lies outside the ASCII digit range")
    void everyKeyByteIsOutsideTheAsciiDigitRange() {
        byte[] record = image();

        for (int offset = 0; offset < KEY_WIDTH; offset++) {
            int value = record[offset] & 0xFF;
            assertThat(value).as("key byte at offset %s", offset)
                    .matches(NOT_AN_ASCII_DIGIT, ASCII_DIGIT_RANGE);
        }
        assertThat(Arrays.copyOfRange(record, 0, KEY_WIDTH))
                .containsExactly(0x75, 0x89, 0x9C, 0x99, 0x99, 0x98, 0x99, 0x9C);
    }

    /**
     * Confirms the key spans hold the nines complement and not the business values.
     *
     * <p>Assumptions: the packed decode is asserted before the subtraction, because a fixture that
     * stored 24100 and 1000 directly would satisfy every decoded-value assertion while exercising
     * no complement at all. The sign nibble of each span is asserted for the reason the class
     * Javadoc gives: the key is sorted as characters, so an unsigned nibble would reorder siblings
     * while leaving every decoded number correct.</p>
     */
    @Test
    @DisplayName("the key spans hold the stored complement under a signed positive nibble")
    void theKeySpansCarryTheNinesComplementNotTheBusinessValue() {
        byte[] record = image();

        assertThat(PackedDecimalCodec.decodePacked(record, 0, 5, 0, true))
                .isEqualByComparingTo(BigDecimal.valueOf(STORED_DATE_COMPLEMENT));
        assertThat(PackedDecimalCodec.decodePacked(record, 3, 9, 0, true))
                .isEqualByComparingTo(BigDecimal.valueOf(STORED_TIME_COMPLEMENT));

        assertThat(record[2] & 0x0F).as("date sign nibble is signed positive").isEqualTo(0x0C);
        assertThat(record[7] & 0x0F).as("time sign nibble is signed positive").isEqualTo(0x0C);
    }

    /**
     * Confirms the complement inverts to the values the integer columns carry.
     *
     * <p>Assumptions: the two subtractions are the ones the reference programs perform, so they are
     * written out here rather than hidden behind a helper -- {@code 99999 - date} at
     * {@code cbl/CBPAUP0C.cbl} line 280 and {@code 999999999 - time} at
     * {@code cbl/COPAUS2C.cbl} line 107.</p>
     *
     * <p>Trade-offs: the day-of-year range check is asserted for both the decoded value and the raw
     * complement, and the second half is what gives the check its value. A pipeline that read the
     * packed span correctly but skipped the inversion would store 75899, whose day-of-year is 899
     * and therefore outside 1 to 366 -- so the same range check that passes here is what would
     * catch that silent leak. Asserting only the decoded 100 would document the happy path and
     * prove nothing about the detector.</p>
     */
    @Test
    @DisplayName("the complement inverts to the auth_date and auth_time the columns hold")
    void theComplementDecodesToTheValuesTheIntegerColumnsCarry() {
        int storedDate = PackedDecimalCodec.decodePacked(image(), 0, 5, 0, true).intValueExact();
        int storedTime = PackedDecimalCodec.decodePacked(image(), 3, 9, 0, true).intValueExact();

        assertThat(99_999 - storedDate).isEqualTo(BUSINESS_JULIAN_DATE);
        assertThat(999_999_999 - storedTime).isEqualTo(BUSINESS_TIME_MS);

        int dayOfYear = BUSINESS_JULIAN_DATE % 1_000;
        assertThat(BUSINESS_JULIAN_DATE / 1_000).as("two-digit year").isEqualTo(24);
        assertThat(dayOfYear).as("day of year of the decoded Julian date")
                .isEqualTo(100)
                .isBetween(1, 366);
        assertThat(STORED_DATE_COMPLEMENT % 1_000)
                .as("an uninverted complement fails the same range check")
                .isGreaterThan(366);

        // WHY : Assumptions: the nine digits are sliced exactly as cbl/COPAUS2C.cbl lines 108 to 111
        //       slice them -- (1:2) hours, (3:2) minutes, (5:2) seconds, (7:3) milliseconds -- so the
        //       assertion documents that this record is one second past midnight rather than merely
        //       that its time is a small number. The zero-padded nine-character form is what the
        //       reference receiver holds, and reproducing it here keeps the slice boundaries visible.
        String clock = String.format("%09d", BUSINESS_TIME_MS);
        assertThat(clock.substring(0, 2)).as("hours").isEqualTo("00");
        assertThat(clock.substring(2, 4)).as("minutes").isEqualTo("00");
        assertThat(clock.substring(4, 6)).as("seconds").isEqualTo("01");
        assertThat(clock.substring(6)).as("milliseconds").isEqualTo("000");
    }

    /**
     * Confirms the record mixes a binary key with text fields and that both read correctly at once.
     *
     * <p>Assumptions: this is the single most important thing to understand about the record, so it
     * is asserted in one pass rather than split across two tests. Eighty-eight bytes after a key
     * whose every byte is non-ASCII sits the POS entry mode, two plain ASCII digits, and a reader
     * that resolved the key's non-ASCII bytes by decoding the whole record as one representation
     * would break one region or the other. The pair proves the regions coexist by design.</p>
     */
    @Test
    @DisplayName("a non-ASCII binary key and an ASCII display field both read in one pass")
    void theRecordMixesTextAndBinaryRegionsInOnePass() {
        byte[] record = image();
        Map<String, Object> fields = decoded();

        assertThat(record[0] & 0xFF).as("first key byte")
                .matches(NOT_AN_ASCII_DIGIT, ASCII_DIGIT_RANGE);
        assertThat(Arrays.copyOfRange(record, 95, 97))
                .as("POS entry mode is the ASCII pair 30 35, never a binary halfword")
                .containsExactly(ASCII_ZERO, 0x35);

        assertThat(amountField(fields, "PA-POS-ENTRY-MODE")).isEqualByComparingTo("5");
        assertThat(amountField(fields, "PA-PROCESSING-CODE")).isEqualByComparingTo("0");
    }

    /**
     * Confirms both money spans carry the mandatory zero pad nibble and the documented amount.
     *
     * <p>Assumptions: the pad is asserted as well as the amount because the two failures look
     * nothing alike. A wrong amount is visible; a field read one nibble early presents a real digit
     * where the pad belongs, shifts every digit one place and yields a value ten times too large --
     * a plausible number that raises nothing. The corruption asserted at the end demonstrates that
     * the codec's guard is live rather than merely documented.</p>
     */
    @Test
    @DisplayName("both seven-byte money spans begin with a zero pad nibble and hold 75.50")
    void theMoneySpansCarryTheMandatoryZeroPadNibble() {
        byte[] record = image();
        Map<String, Object> fields = decoded();

        assertThat(PackedDecimalCodec.packedWidth(10, 2))
                .as("twelve digits plus a sign occupy seven bytes, not six")
                .isEqualTo(7);
        assertThat(Arrays.copyOfRange(record, 74, 81))
                .containsExactly(0x00, 0x00, 0x00, 0x00, 0x07, 0x55, 0x0C);
        assertThat(Arrays.copyOfRange(record, 81, 88))
                .containsExactly(0x00, 0x00, 0x00, 0x00, 0x07, 0x55, 0x0C);

        assertThat((record[74] >> 4) & 0x0F).as("leading pad nibble of the transaction amount")
                .isZero();
        assertThat((record[81] >> 4) & 0x0F).as("leading pad nibble of the approved amount")
                .isZero();

        assertThat(amountField(fields, "PA-TRANSACTION-AMT")).isEqualByComparingTo("75.50");
        assertThat(amountField(fields, "PA-APPROVED-AMT")).isEqualByComparingTo("75.50");

        // WHY : Assumptions: the amount is asserted at a scale of two, and both money fields hold the
        //       same value on purpose. cbl/COPAUA0C.cbl line 693 moves the requested amount straight
        //       into the approved amount on the approval path, so an approved authorization for the
        //       full amount is the shape the reference produces; a declined one carries zero, which
        //       the sibling purge fixture covers instead.
        assertThat(amountField(fields, "PA-APPROVED-AMT").scale()).isEqualTo(2);

        byte[] corrupted = image();
        corrupted[74] = (byte) 0x10;
        assertThatThrownBy(() -> PackedDecimalCodec.decodePacked(corrupted, 74, 10, 2, true))
                .as("a non-zero pad nibble is refused rather than shifted into a digit")
                .isInstanceOf(PackedDecimalCodec.PackedDecimalException.class);
    }

    /**
     * Confirms every text and display field decodes to the value the fixture was written to carry.
     *
     * <p>Assumptions: the merchant category field is read by its misspelt copybook name
     * {@code PA-MERCHANT-CATAGORY-CODE}, because that is the name the registry declares and the name
     * {@code cpy/CIPAUDTY.cpy} line 36 defines. Three spellings coexist in this system -- the
     * copybook's and {@code ddl/AUTHFRDS.ddl} line 14 both misspell it, and only the target column
     * is corrected to {@code merchant_category_code} -- so reading it by the corrected name here
     * would fail, and that is the intended behaviour: the correction belongs to the mapping layer,
     * not to the codec.</p>
     *
     * <p>Assumptions: the originating date and time are asserted as stored, and no assertion claims
     * they agree with the complement key. {@code cbl/COPAUA0C.cbl} lines 877 and 878 move both
     * straight from the client's request payload, whereas the key at offset zero is derived from the
     * server clock at lines 868 to 875. The two may legitimately disagree in production; their
     * agreement in this fixture is a deliberate choice that makes the record easy to read, not a
     * contract.</p>
     */
    @Test
    @DisplayName("every text and display field decodes to its documented value")
    void everyDocumentedFieldValueDecodes() {
        Map<String, Object> fields = decoded();

        assertThat(textField(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("240409");
        assertThat(textField(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("000001");
        assertThat(textField(fields, "PA-CARD-NUM")).isEqualTo("4000123456789010");
        assertThat(textField(fields, "PA-AUTH-TYPE")).isEqualTo("PURC");
        assertThat(textField(fields, "PA-CARD-EXPIRY-DATE")).isEqualTo("1227");
        assertThat(textField(fields, "PA-MESSAGE-TYPE")).isEqualTo("0100");
        assertThat(textField(fields, "PA-MESSAGE-SOURCE")).isEqualTo("POS");
        assertThat(textField(fields, "PA-AUTH-ID-CODE")).isEqualTo("A00020");
        assertThat(textField(fields, "PA-AUTH-RESP-CODE")).isEqualTo("00");
        assertThat(textField(fields, "PA-AUTH-RESP-REASON")).isEqualTo("0000");
        assertThat(textField(fields, "PA-MERCHANT-CATAGORY-CODE")).isEqualTo("5411");
        assertThat(textField(fields, "PA-ACQR-COUNTRY-CODE")).isEqualTo("840");
        assertThat(textField(fields, "PA-MERCHANT-ID")).isEqualTo("MERCH0000000001");
        assertThat(textField(fields, "PA-MERCHANT-NAME")).isEqualTo("ACME HARDWARE");
        assertThat(textField(fields, "PA-MERCHANT-CITY")).isEqualTo("SEATTLE");
        assertThat(textField(fields, "PA-MERCHANT-STATE")).isEqualTo("WA");
        assertThat(textField(fields, "PA-MERCHANT-ZIP")).isEqualTo("98101");
        assertThat(textField(fields, "PA-TRANSACTION-ID")).isEqualTo("TXN000000000020");
    }

    /**
     * Confirms the decoded row satisfies every domain the target table declares.
     *
     * <p>Assumptions: the response code {@code '00'} is the condition name
     * {@code 88 PA-AUTH-APPROVED} at {@code cpy/CIPAUDTY.cpy} line 31, and the match status
     * {@code 'P'} is {@code 88 PA-MATCH-PENDING} at line 46, so both are values the reference could
     * actually produce rather than values chosen to satisfy a constraint.</p>
     *
     * <p>Trade-offs: the fraud flag is a <b>blank</b>, and its blankness is the point. The copybook
     * declares condition names for only {@code 'F'} at line 51 and {@code 'R'} at line 52 and none
     * for a blank, yet the reference writes a blank on the ordinary path and
     * {@code cbl/COPAUS1C.cbl} lines 344 to 349 render a bare hyphen when neither name is set. That
     * absence is the positive evidence behind the blank-tolerant check
     * {@code ck_pending_auth_detail_auth_fraud}, which admits {@code 'F'}, {@code 'R'}, null and a
     * single blank -- so this fixture is the vector that proves the blank is accepted rather than
     * rejected as an unknown code.</p>
     */
    @Test
    @DisplayName("the decoded row satisfies the match-status, fraud and key domains")
    void theDecodedRowSatisfiesTheDeclaredColumnDomains() {
        Map<String, Object> fields = decoded();

        assertThat(textField(fields, "PA-MATCH-STATUS")).isEqualTo("P").isIn("P", "D", "E", "M");
        assertThat(textField(fields, "PA-AUTH-FRAUD"))
                .as("a blank fraud flag is admitted, not rejected as an unknown code")
                .isEmpty();
        assertThat(String.valueOf(fields.get("PA-AUTH-FRAUD"))).isEqualTo(" ");

        // WHY : Assumptions: a blank report date maps to SQL NULL rather than to a zero date, because
        //       fraud_rpt_date is nullable and a zero date would be a value the reference never wrote.
        //       The column is CHAR(8) and the reference writes MM/DD/YY there -- cbl/COPAUS2C.cbl
        //       lines 95 to 101 -- which is a different representation from the ten-character Db2 host
        //       variable at dcl/AUTHFRDS.dcl line 84; the two stay distinct and neither is eight
        //       zeroes.
        assertThat(textField(fields, "PA-FRAUD-RPT-DATE")).isEmpty();

        assertThat(PARENT_ACCOUNT_ID).isEqualTo(10_000_000_001L);
        assertThat(new int[] {BUSINESS_JULIAN_DATE, BUSINESS_TIME_MS})
                .as("the two key components of the composite primary key, decoded")
                .containsExactly(24_100, 1_000);
    }

    /**
     * Confirms the seventeen filler bytes reach no field while the record still closes at 200.
     *
     * <p>Assumptions: the filler is dropped because it is blank and because the descriptor handed to
     * the codec is the registry instance. Twenty-seven of the layout's twenty-eight fields therefore
     * survive the decode, and the encode restores the dropped bytes, which is what keeps the
     * physical width intact without carrying an inert value through every consumer.</p>
     */
    @Test
    @DisplayName("the blank trailing filler reaches no field yet the record still closes at 200")
    void theTrailingFillerReachesNoFieldYetTheRecordStillCloses() {
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        Map<String, Object> fields = decoded();

        assertThat(spec.fields()).hasSize(28);
        assertThat(spec.field("FILLER").length()).isEqualTo(17);
        assertThat(fields).doesNotContainKey("FILLER").hasSize(27);
        assertThat(FixedWidthCodec.encodeRecordPreservingSign(fields, spec, image()))
                .hasSize(200);
    }

    /**
     * Confirms the record decodes and re-encodes byte-identically.
     *
     * <p>Assumptions: byte identity is the correct expectation for this fixture specifically,
     * because its filler is blank, both its packed signs are the positive nibble and it holds no
     * negative zero. A negatively-signed packed zero would re-encode to the positive nibble under
     * the plain encoder -- the documented signed-zero normalisation -- so the sign-preserving
     * encoder is used here too, keeping this assertion identical in form to the one the shared
     * fixture consumer applies to every binary fixture in the directory.</p>
     */
    @Test
    @DisplayName("the record round trips byte-identically through decode and encode")
    void theRecordRoundTripsByteIdentically() {
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        byte[] record = image();

        Map<String, Object> fields = FixedWidthCodec.decodeRecord(record, spec);
        assertThat(fields).isNotEmpty();
        assertThat(FixedWidthCodec.encodeRecordPreservingSign(fields, spec, record))
                .isEqualTo(record);
    }

    /**
     * Confirms a record one byte short or one byte long is refused rather than decoded.
     *
     * <p>Assumptions: both directions are asserted. A short record is the shape a truncated read
     * produces and a long one is the shape an appended terminator produces, and this record's
     * trailing field is a filler, so an appended byte would otherwise be absorbed silently into
     * padding that the decode drops anyway -- the one case where a length error could pass
     * unnoticed.</p>
     */
    @Test
    @DisplayName("a 199-byte or 201-byte image is refused, not decoded short or long")
    void aRecordOneByteShortOrOneByteLongIsRefused() {
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        byte[] shortRecord = Arrays.copyOf(image(), 199);
        byte[] longRecord = Arrays.copyOf(image(), 201);

        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(shortRecord, spec))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(longRecord, spec))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * Confirms the record holds no byte a line-oriented reader would treat as a terminator.
     *
     * <p>Assumptions: the two terminators are not equally possible here, and stating which is which
     * is what keeps the read discipline from resting on luck. A line feed is structurally
     * impossible in this record type: a packed byte's low nibble is either a digit or a sign, and
     * {@code 0x0A} requires a low nibble of {@code A}, which is neither -- so a line feed could only
     * arrive from a binary field, and this segment declares none. A carriage return is possible in
     * principle, because a negative packed value whose final digit is zero lays down {@code 0x0D};
     * every packed value here is positive, so none is present. The assertion covers both, and the
     * reader must still treat the file as a fixed-length byte stream rather than depend on that.</p>
     */
    @Test
    @DisplayName("no line-feed or carriage-return byte appears anywhere in the record")
    void theRecordHoldsNoLineTerminatorByte() {
        byte[] record = image();

        assertThat(record).doesNotContain((byte) 0x0A).doesNotContain((byte) 0x0D);
    }
}
