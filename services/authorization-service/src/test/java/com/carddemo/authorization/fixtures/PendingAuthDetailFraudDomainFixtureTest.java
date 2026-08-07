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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes {@code pautdtl1-auth-fraud-invalid.bin} beside {@code pautdtl1-auth-fraud-domain.bin} and
 * fixes the exact boundary of the fraud-flag domain.
 *
 * <p>Purpose: this class is the executable documentation of two fixtures that only mean something
 * together. The three-record file images the states the target column ACCEPTS; the one-record file
 * images a state it must REFUSE. Read alone, either file is ambiguous -- the refusing one especially,
 * because a 200-byte image cannot say of itself that its byte at offset 174 is wrong on purpose. This
 * class says it, and states the boundary as assertions so the claim cannot decay into prose nobody
 * checks.
 *
 * <p><b>Assumptions: {@code pautdtl1-auth-fraud-invalid.bin} IS INTENTIONALLY INVALID AND MUST NOT BE
 * "CORRECTED".</b> Its byte at offset 174 is {@code 'Y'} (0x59), which no condition name in
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} declares and which
 * {@code ck_pending_auth_detail_auth_fraud} in {@code V1__authorization.sql} refuses. Two silent
 * failures are available to a maintainer who does not know that. Changing the byte to {@code 'F'}
 * turns a negative test into a duplicate of a positive one already covered by the sibling; widening
 * the check to admit {@code 'Y'} removes the only bound on a column whose domain was already widened
 * once. Either leaves every test in this module green, which is why the intent is recorded here where
 * {@code config/checkstyle/checkstyle.xml} polices its presence on every build rather than in a
 * comment the record has no room for -- a comment byte would push the record past the declared 200
 * and corrupt the trailing filler.
 *
 * <p>Assumptions: the layout is {@code CIPAUDTY.cpy} lines 19 to 54, registered as {@code PAUTDTL},
 * and the two bytes this class cares about are ADJACENT: {@code PA-MATCH-STATUS PIC X(01)} at line 45
 * occupies offset 173 and {@code PA-AUTH-FRAUD PIC X(01)} at line 50 occupies offset 174. Both columns
 * carry their own check in the target schema, so the refused record sets the match status to a
 * deliberately VALID {@code 'P'} -- condition name {@code 88 PA-MATCH-PENDING} at line 46 -- and every
 * other field to a value the reference could actually have produced. That is what makes the refusal
 * ATTRIBUTABLE: a test that only expected "some exception" would pass identically for a fixture that
 * had accidentally broken the neighbouring byte, so the engine-tier consumer
 * {@code PendingAuthFraudDomainRepositoryIT} asserts the constraint by NAME.
 *
 * <p>Assumptions: the accepted domain is EXACTLY FOUR STATES and the evidence for each is separate.
 * {@code CIPAUDTY.cpy} declares two condition names and no more -- {@code 88 PA-FRAUD-CONFIRMED
 * VALUE 'F'} at line 51 and {@code 88 PA-FRAUD-REMOVED VALUE 'R'} at line 52. There is no condition
 * name for a blank, confirmed by absence, yet the reference writes a blank on the ordinary path and
 * {@code cbl/COPAUS1C.cbl} lines 344 to 349 handle that state explicitly, rendering a bare hyphen at
 * line 349 when neither name is set. A nullable column adds SQL null for an extract row that carries
 * no flag at all. So the check admits {@code 'F'}, {@code 'R'}, a single blank and null -- and NOTHING
 * FURTHER, because the widening was driven by that one piece of evidence and reaches exactly as far as
 * the evidence does. {@code 'Y'} sits outside it.
 *
 * <p>Assumptions: {@code 'Y'} was chosen deliberately over three rejected alternatives, and the
 * reasons are properties of how each would have failed rather than preferences. It is printable,
 * upper case, single byte and semantically suggestive -- the value a well-meaning integrator would
 * supply for a field whose name reads like a boolean -- so it is the realistic out-of-domain value
 * rather than an arbitrary one. A non-printable byte was rejected because encoding validation could
 * refuse it before the check ran; a lower-case {@code 'f'} was rejected because case normalisation
 * could refuse it before the check ran; a tab or a low value was rejected because trimming could turn
 * it into the blank the check ADMITS. Each rejected candidate would have produced a passing test for
 * the wrong reason, which is the failure mode this fixture exists to avoid.
 *
 * <p>Assumptions: the fraud report date is POPULATED, at {@code "04/30/24"}, rather than blanked.
 * {@code cbl/COPAUS1C.cbl} line 347 renders that date whenever the flag is set, so a record carrying
 * a non-blank flag beside a blank date would be internally inconsistent and would give a reviewer a
 * second anomaly to weigh against the first. Populating it leaves the {@code 'Y'} as the only
 * anomaly. The format is the one {@code cbl/COPAUS2C.cbl} lines 95 to 101 produce, {@code MMDDYY}
 * with a date separator. Two representations must stay distinct: this IMS field is {@code PIC X(08)}
 * carrying {@code MM/DD/YY}, whereas the Db2 column is a real {@code DATE} at
 * {@code ddl/AUTHFRDS.ddl} line 25 whose generated host variable is {@code PIC X(10)} at
 * {@code dcl/AUTHFRDS.dcl} line 84.
 *
 * <p>Assumptions: the refused record's Julian is 24121 while the three admitted records all carry
 * 24120, and the difference is load-bearing. The target primary key is
 * {@code (account_id, auth_date, auth_time)} and the refused record shares its parent account and its
 * time of day with the first admitted record, so an identical Julian would make a combined load fail
 * on the key before the fraud check was ever evaluated -- a green test proving nothing about the
 * column under test. Julian 24121 is 30 April 2024, one day after the siblings, and its transaction
 * identifier and authorization code are equally distinct so the unique key
 * {@code (card_num, transaction_id)} cannot fire either.
 *
 * <p>Assumptions: both key components carry the positive sign nibble {@code 0xC}, uniformly with
 * every sibling. {@code ims/DBPAUTP0.dbd} line 37 declares the eight-byte key {@code TYPE=C}, so the
 * reference compares child twins byte-wise over the raw complement bytes and the sign nibble
 * participates in that comparison. Mixing {@code 0xC} and {@code 0xF} across sibling fixtures would
 * perturb their order while leaving every decoded value unchanged, which is a difference no
 * value-level assertion could see.
 *
 * <p>Assumptions: the two money spans begin with a ZERO PAD NIBBLE and the requirement is not
 * symmetric across this directory. {@code PIC S9(10)V99 COMP-3} is twelve digits in seven bytes,
 * which is fourteen nibbles -- one leading pad, twelve digits, one sign -- and
 * {@code PackedDecimalCodec} REFUSES a non-zero pad. The summary segment's {@code PIC S9(09)V99} is
 * eleven digits in six bytes with no pad at all, where a zero high nibble would silently drop a
 * digit instead. Getting the pad right matters here in particular: a wrong pad would make this record
 * fail at DECODE, and a fixture that cannot be decoded never reaches the check it exists to trip, so
 * the refusal would be attributed to the wrong layer.
 *
 * <p>Assumptions: {@code PA-POS-ENTRY-MODE} at line 38 is {@code PIC 9(02)} DISPLAY, so offsets 95
 * and 96 hold the two ASCII digits {@code "05"} and not a binary halfword. The trap is that the same
 * datum is {@code SMALLINT} in {@code ddl/AUTHFRDS.ddl} line 16 and {@code PIC S9(4) USAGE COMP} in
 * {@code dcl/AUTHFRDS.dcl} line 71, and {@code cbl/COPAUS2C.cbl} line 128 is where the reference
 * converts between them. Writing the halfword into the segment would corrupt those two bytes while
 * leaving the record exactly 200 long.
 *
 * <p>Trade-offs: this class asserts the domain from the MIGRATION TEXT and from the fixture bytes,
 * and asserts nothing against a live engine. The engine-tier claim -- that an insert is refused and
 * that the refusal names the fraud check rather than its neighbour -- needs a real database and lives
 * in {@code PendingAuthFraudDomainRepositoryIT}, which the module's failsafe include runs. Splitting
 * them keeps the byte-level contract in the fast tier, where it runs without a container runtime.
 */
class PendingAuthDetailFraudDomainFixtureTest {

    /** The fixture whose fraud byte is deliberately outside the accepted domain. */
    private static final String REFUSED_FIXTURE = "fixtures/pautdtl1-auth-fraud-invalid.bin";

    /** The sibling fixture holding one record per admitted non-null fraud state. */
    private static final String ADMITTED_FIXTURE = "fixtures/pautdtl1-auth-fraud-domain.bin";

    /** The registry name of the 200-byte authorization-detail segment both fixtures are written to. */
    private static final String LAYOUT = "PAUTDTL";

    /** The migration whose check constraint fixes the accepted domain. */
    private static final String MIGRATION = "db/migration/V1__authorization.sql";

    /** The declared segment length, from {@code SEGM NAME=PAUTDTL1 ... BYTES=200}. */
    private static final int SEGMENT_LENGTH = 200;

    /** The number of records the admitted-state sibling holds, one per accepted non-null state. */
    private static final int ADMITTED_RECORD_COUNT = 3;

    /** Width of the packed authorization key, being {@code BYTES=8} in {@code ims/DBPAUTP0.dbd}. */
    private static final int KEY_WIDTH = 8;

    /** Offset of {@code PA-MATCH-STATUS}, the constrained byte immediately before the fraud flag. */
    private static final int MATCH_STATUS_OFFSET = 173;

    /** Offset of {@code PA-AUTH-FRAUD}, the one byte this pair of fixtures exists to bound. */
    private static final int FRAUD_OFFSET = 174;

    /** Offset of the trailing {@code FILLER PIC X(17)} that closes the record. */
    private static final int FILLER_OFFSET = 183;

    /** Width of that trailing filler, in bytes. */
    private static final int FILLER_WIDTH = 17;

    /** The code point of the refused flag {@code 'Y'}, asserted as a byte so no charset intervenes. */
    private static final byte REFUSED_FRAUD_BYTE = 0x59;

    /** The code point of the valid neighbouring match status {@code 'P'}. */
    private static final byte PENDING_MATCH_BYTE = 0x50;

    /** The refused flag as text, for the assertions that read decoded fields rather than bytes. */
    private static final String REFUSED_FRAUD_MARK = "Y";

    /** {@code 88 PA-FRAUD-CONFIRMED VALUE 'F'} at {@code cpy/CIPAUDTY.cpy} line 51. */
    private static final String FRAUD_REPORTED = "F";

    /** {@code 88 PA-FRAUD-REMOVED VALUE 'R'} at {@code cpy/CIPAUDTY.cpy} line 52. */
    private static final String FRAUD_REMOVED = "R";

    /** The never-marked state: a blank, which the copybook declares no condition name for. */
    private static final String FRAUD_UNMARKED = " ";

    /** The four match statuses {@code cpy/CIPAUDTY.cpy} lines 46 to 49 declare. */
    private static final List<String> MATCH_STATUS_DOMAIN = List.of("P", "D", "E", "M");

    /** Complement base for the five-digit date, {@code 99999 - YYDDD} at COPAUA0C line 874. */
    private static final int DATE_COMPLEMENT_BASE = 99999;

    /** Complement base for the nine-digit time, {@code 999999999 - HHMMSSmmm} at line 875. */
    private static final int TIME_COMPLEMENT_BASE = 999999999;

    /** The decoded Julian this record carries, the nines complement having been undone. */
    private static final int AUTH_DATE_JULIAN = 24121;

    /** The decoded millisecond-resolution time this record carries, 14:00:00.000. */
    private static final int AUTH_TIME_WITH_MS = 140_000_000;

    /** The complement the fixture's three date bytes actually hold, which the codec returns as is. */
    private static final int STORED_DATE_COMPLEMENT = DATE_COMPLEMENT_BASE - AUTH_DATE_JULIAN;

    /** The complement the fixture's five time bytes actually hold. */
    private static final int STORED_TIME_COMPLEMENT = TIME_COMPLEMENT_BASE - AUTH_TIME_WITH_MS;

    /** The day-of-year component of the decoded Julian, which must fall inside 1 to 366. */
    private static final int AUTH_DAY_OF_YEAR = 121;

    /** The calendar day the Julian names, 30 April 2024, asserted so the leap year is proven. */
    private static final LocalDate AUTH_CALENDAR_DAY = LocalDate.of(2024, 4, 30);

    /** Both money spans of the refused record, exact at scale two. */
    private static final BigDecimal AUTHORIZED_AMOUNT = new BigDecimal("50.00");

    /** The decoded point-of-sale entry mode, two display digits rather than a binary halfword. */
    private static final int POS_ENTRY_MODE = 5;

    /** The fraud report date the refused record carries, in the reference's {@code MM/DD/YY} form. */
    private static final String FRAUD_REPORT_DATE = "04/30/24";

    /** The exact predicate {@code V1__authorization.sql} declares for the fraud column. */
    private static final String FRAUD_CHECK_PREDICATE =
            "CHECK (auth_fraud IN ('F', 'R') OR auth_fraud IS NULL OR auth_fraud = ' ')";

    /** The name the fraud check carries, which the engine-tier consumer asserts a refusal reports. */
    private static final String FRAUD_CONSTRAINT_NAME = "ck_pending_auth_detail_auth_fraud";

    /**
     * Reads one fixture resource as raw bytes.
     *
     * <p>Assumptions: the resource is reached through the class loader rather than a filesystem path,
     * which is how the packaged test artifact reaches it and what keeps every assertion independent of
     * whatever working directory a runner chooses.</p>
     *
     * @param name the resource name below the class-path root
     * @return the resource's bytes, exactly as stored
     * @throws AssertionError if the resource is absent from the test class path, which would mean this
     *     class was asserting nothing at all
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] bytes(String name) {
        try (InputStream stream = PendingAuthDetailFraudDomainFixtureTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Slices one fixed-length record out of a multi-record image.
     *
     * @param image the whole fixture image
     * @param ordinal the zero-based record ordinal
     * @return exactly {@link #SEGMENT_LENGTH} bytes beginning at that ordinal's offset
     */
    private static byte[] record(byte[] image, int ordinal) {
        int start = ordinal * SEGMENT_LENGTH;
        return Arrays.copyOfRange(image, start, start + SEGMENT_LENGTH);
    }

    /**
     * Decodes one record through the registered layout.
     *
     * <p>Assumptions: the descriptor is the registry instance rather than a locally built copy,
     * because {@code FixedWidthCodec} tests descriptor IDENTITY when it decides whether a blank filler
     * is droppable padding. A copy would retain the seventeen filler bytes and invert what
     * {@link #theTrailingFillerReachesNoFieldYetTheRecordCloses()} asserts.</p>
     *
     * @param image exactly one record of the registered length
     * @return the decoded fields in copybook declaration order
     */
    private static Map<String, Object> decode(byte[] image) {
        return FixedWidthCodec.decodeRecord(image, CopybookLayout.layout(LAYOUT));
    }

    /**
     * Returns the refused record, which is the whole of its fixture.
     *
     * @return the 200 bytes of {@code pautdtl1-auth-fraud-invalid.bin}
     */
    private static byte[] refusedRecord() {
        return bytes(REFUSED_FIXTURE);
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
     * packed span decodes to a decimal while an unsigned display span decodes to a whole number, and
     * both must be comparable here without the assertion caring which arrived.</p>
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's value as an exact decimal
     */
    private static BigDecimal numberField(Map<String, Object> fields, String name) {
        return new BigDecimal(String.valueOf(fields.get(name)));
    }

    /**
     * Returns one decoded key component exactly as the codec produced it, complement and all.
     *
     * <p>Assumptions: the codec is a codec and not a mapper, so it returns the packed span's own
     * value. Undoing the nines complement is the mapping layer's work, which is why this helper and
     * {@link #decodeComplement(int, int)} are separate: conflating them is how a complement ends up
     * asserted as a column value.</p>
     *
     * @param fields the decoded record
     * @param name the copybook field name of a packed key component
     * @return the stored complement as a whole number
     */
    private static int stored(Map<String, Object> fields, String name) {
        return numberField(fields, name).intValueExact();
    }

    /**
     * Undoes the nines complement, reproducing what the target integer column holds.
     *
     * @param storedValue the complement the fixture's packed bytes carry
     * @param base {@link #DATE_COMPLEMENT_BASE} or {@link #TIME_COMPLEMENT_BASE}
     * @return the business value the reference computed the complement from
     */
    private static int decodeComplement(int storedValue, int base) {
        return base - storedValue;
    }

    /**
     * Reads the owning migration as text.
     *
     * @return the whole of {@code V1__authorization.sql}
     */
    private static String migration() {
        return new String(bytes(MIGRATION), StandardCharsets.UTF_8);
    }

    /**
     * Confirms both fixtures divide into whole records of the length the segment declares.
     *
     * <p>Assumptions: 200 is asserted with no tolerance for a terminator byte, and the reason is
     * specific to this record shape. The reference suite's text fixtures end in a newline so that a
     * line count equals a record count, and {@code tests/fixtures/README.md} section 3.2 reasons that
     * a stray carriage return would be absorbed into the trailing field or the filler and push the
     * record one byte over its declared length. This record ENDS in that filler, so the named hazard
     * applies and the convention's remedy cannot be adopted; the final byte is therefore asserted to
     * be the filler's blank rather than any terminator.</p>
     */
    @Test
    @DisplayName("both fraud fixtures divide into whole 200-byte segments with no terminator byte")
    void bothFixturesAreWholeRecordsOfTheDeclaredSegmentLength() {
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        byte[] refused = refusedRecord();
        byte[] admitted = bytes(ADMITTED_FIXTURE);

        assertThat(spec.reclen()).isEqualTo(SEGMENT_LENGTH);
        assertThat(spec.keyLength()).isEqualTo(KEY_WIDTH);
        assertThat(refused).hasSize(SEGMENT_LENGTH);
        assertThat(admitted).hasSize(ADMITTED_RECORD_COUNT * SEGMENT_LENGTH);
        assertThat(admitted.length % spec.reclen()).isZero();
        assertThat(refused[refused.length - 1])
                .as("the final byte is the filler's blank, not a line terminator")
                .isEqualTo((byte) ' ');
    }

    /**
     * Confirms the refused record decodes without raising, because it is byte-valid throughout.
     *
     * <p>Assumptions: separating BYTE validity from DOMAIN validity is the whole point of this case.
     * The record must reach the database intact so that the only thing left to refuse it is the check
     * constraint; a record that failed in the codec would be refused a layer too early and the
     * refusal would be attributed to the wrong cause. The two money spans are therefore decoded
     * through {@code PackedDecimalCodec} directly as well as through the record codec, because that
     * class REFUSES a non-zero leading pad nibble and the pad is the one part of this geometry that a
     * hand-written fixture gets wrong silently.</p>
     */
    @Test
    @DisplayName("the refused record is byte-valid: it decodes cleanly, pad nibbles included")
    void theRefusedRecordDecodesCleanlyBecauseOnlyItsDomainIsWrong() {
        byte[] refused = refusedRecord();

        assertThat(PackedDecimalCodec.packedWidth(10, 2))
                .as("twelve digits plus a sign occupy seven bytes, never six")
                .isEqualTo(7);
        assertThat(PackedDecimalCodec.decodePacked(refused, 74, 10, 2, true))
                .isEqualByComparingTo(AUTHORIZED_AMOUNT);
        assertThat(PackedDecimalCodec.decodePacked(refused, 81, 10, 2, true))
                .isEqualByComparingTo(AUTHORIZED_AMOUNT);
        assertThat(decode(refused))
                .as("the whole record decodes; nothing about it is malformed")
                .isNotEmpty();
    }

    /**
     * Confirms the decoded record carries the values this fixture is documented to carry.
     *
     * <p>Assumptions: THE FIXTURE CARRIES THE NINES COMPLEMENT AND THE COLUMN CARRIES THE DECODED
     * VALUE, so every expectation below is the decoded number. The stored bytes are 75878 and
     * 859999999 from {@code cbl/COPAUA0C.cbl} lines 874 and 875; the columns
     * {@code V1__authorization.sql} declares are integers holding 24121 and 140000000, and asserting a
     * complement as a column value would encode the very defect these fixtures exist to prevent.</p>
     *
     * <p>Assumptions: the day-of-year is asserted inside 1 to 366 because that is the bound
     * {@code ck_pending_auth_detail_auth_date_domain} places on the same value. Proving the date is
     * inside its own domain is what removes it as a competing cause of the refusal this pair of
     * fixtures is about.</p>
     */
    @Test
    @DisplayName("the refused record decodes to Julian 24121, 14:00:00.000 and two 50.00 amounts")
    void theDecodedRecordCarriesTheValuesThisFixtureDocuments() {
        Map<String, Object> fields = decode(refusedRecord());

        assertThat(stored(fields, "PA-AUTH-DATE-9C"))
                .as("the fixture's bytes hold the COMPLEMENT, which the codec returns verbatim")
                .isEqualTo(STORED_DATE_COMPLEMENT);
        assertThat(decodeComplement(stored(fields, "PA-AUTH-DATE-9C"), DATE_COMPLEMENT_BASE))
                .as("and 99999 - 75878 is the Julian the auth_date INTEGER column holds")
                .isEqualTo(AUTH_DATE_JULIAN);
        assertThat(stored(fields, "PA-AUTH-TIME-9C")).isEqualTo(STORED_TIME_COMPLEMENT);
        assertThat(decodeComplement(stored(fields, "PA-AUTH-TIME-9C"), TIME_COMPLEMENT_BASE))
                .as("and 999999999 - 859999999 is the time the auth_time INTEGER column holds")
                .isEqualTo(AUTH_TIME_WITH_MS);
        assertThat(AUTH_DATE_JULIAN % 1000).isEqualTo(AUTH_DAY_OF_YEAR).isBetween(1, 366);
        assertThat(LocalDate.ofYearDay(2024, AUTH_DAY_OF_YEAR))
                .as("day 121 of a leap year is 30 April, one day after the admitted siblings")
                .isEqualTo(AUTH_CALENDAR_DAY);
        assertThat(numberField(fields, "PA-TRANSACTION-AMT")).isEqualByComparingTo(AUTHORIZED_AMOUNT);
        assertThat(numberField(fields, "PA-APPROVED-AMT")).isEqualByComparingTo(AUTHORIZED_AMOUNT);
        assertThat(numberField(fields, "PA-POS-ENTRY-MODE").intValueExact())
                .isEqualTo(POS_ENTRY_MODE);
        assertThat(textField(fields, "PA-MATCH-STATUS")).isEqualTo("P").isIn(MATCH_STATUS_DOMAIN);
        assertThat(textField(fields, "PA-AUTH-FRAUD")).isEqualTo(REFUSED_FRAUD_MARK);
        assertThat(textField(fields, "PA-FRAUD-RPT-DATE")).isEqualTo(FRAUD_REPORT_DATE);
        assertThat(textField(fields, "PA-AUTH-RESP-CODE"))
                .as("the approved condition name at cpy/CIPAUDTY.cpy line 31")
                .isEqualTo("00");
        assertThat(textField(fields, "PA-AUTH-ORIG-DATE"))
                .as("the client-supplied original date, consistent with the server-derived Julian")
                .isEqualTo("240430");
    }

    /**
     * Confirms the out-of-domain byte is the fraud flag and that its constrained neighbour is valid.
     *
     * <p>Assumptions: the two constrained bytes are ADJACENT at offsets 173 and 174, which is why this
     * case reads bytes rather than decoded text. A fixture that had broken the match status by one
     * byte would still be refused by the database, and a test that only expected a refusal would stay
     * green while proving nothing about the fraud column. Pinning both bytes is what makes the
     * refusal attributable before an engine is ever involved.</p>
     *
     * <p>Assumptions: the refused byte is asserted to be printable upper-case ASCII, because that is
     * the property the choice of {@code 'Y'} rests on. A control byte would risk refusal by encoding
     * validation and a lower-case letter would risk refusal by case normalisation, either of which
     * would make an engine-tier pass mean something other than what it claims.</p>
     */
    @Test
    @DisplayName("offset 174 holds 'Y' while its neighbour at 173 holds a valid 'P'")
    void theRefusedByteIsTheFraudFlagAndItsNeighbourIsDeliberatelyValid() {
        byte[] refused = refusedRecord();

        assertThat(refused[MATCH_STATUS_OFFSET]).isEqualTo(PENDING_MATCH_BYTE);
        assertThat(String.valueOf((char) refused[MATCH_STATUS_OFFSET])).isIn(MATCH_STATUS_DOMAIN);
        assertThat(refused[FRAUD_OFFSET]).isEqualTo(REFUSED_FRAUD_BYTE);
        assertThat(REFUSED_FRAUD_MARK)
                .as("the refused value is none of the three non-null accepted states")
                .isNotIn(FRAUD_REPORTED, FRAUD_REMOVED, FRAUD_UNMARKED);
        assertThat(refused[FRAUD_OFFSET])
                .as("printable upper-case ASCII, so no earlier layer can claim the refusal")
                .isBetween((byte) 'A', (byte) 'Z');
    }

    /**
     * Confirms every span this record shares with an admitted sibling is byte-identical.
     *
     * <p>Assumptions: attributability is a property of the whole record and not only of offset 174. If
     * this record differed from an admitted one in a money pad, a match status, a response code or a
     * merchant field, the database would refuse it for that reason instead and the engine-tier
     * assertion would pass while testing something else. The spans below are therefore compared
     * against the first record of {@code pautdtl1-auth-fraud-domain.bin}, which the schema
     * ACCEPTS -- so the only differences left are the intended ones: the second byte of the date
     * complement, the original date, the authorization code, the transaction identifier, the fraud
     * flag and the fraud report date.</p>
     */
    @Test
    @DisplayName("every span shared with an accepted sibling record is byte-identical")
    void everySpanSharedWithAnAdmittedRecordIsByteIdentical() {
        byte[] refused = refusedRecord();
        byte[] admitted = record(bytes(ADMITTED_FIXTURE), 0);
        int[][] sharedSpans = {
            {3, 5}, {14, 6}, {20, 16}, {36, 4}, {40, 4}, {44, 6}, {50, 6}, {62, 2}, {64, 4},
            {68, 6}, {74, 7}, {81, 7}, {88, 4}, {92, 3}, {95, 2}, {97, 15}, {112, 22}, {134, 13},
            {147, 2}, {149, 9}, {MATCH_STATUS_OFFSET, 1}, {FILLER_OFFSET, FILLER_WIDTH},
        };

        for (int[] span : sharedSpans) {
            int start = span[0];
            int length = span[1];
            assertThat(Arrays.copyOfRange(refused, start, start + length))
                    .as("shared span at offset %d for %d bytes", start, length)
                    .isEqualTo(Arrays.copyOfRange(admitted, start, start + length));
        }
    }

    /**
     * Confirms the refused record's key and identifiers collide with no admitted record.
     *
     * <p>Assumptions: the target primary key is {@code (account_id, auth_date, auth_time)} and the
     * unique key is {@code (card_num, transaction_id)}. This record deliberately SHARES its parent
     * account and its card with the admitted siblings, because a fixture that changed those too would
     * no longer be the same authorization stream; the distinctness therefore has to come from the
     * Julian and the transaction identifier, and both are asserted here. Without that, a combined load
     * would fail on a key before the fraud check was evaluated, and the engine-tier case would report
     * a refusal that had nothing to do with this fixture's reason to exist.</p>
     */
    @Test
    @DisplayName("the refused record's key and transaction identifier collide with no sibling")
    void theRefusedKeyAndIdentifiersAreDistinctFromEveryAdmittedRecord() {
        byte[] refused = refusedRecord();
        byte[] admittedImage = bytes(ADMITTED_FIXTURE);
        byte[] refusedKey = Arrays.copyOfRange(refused, 0, KEY_WIDTH);
        List<String> admittedTransactionIds = new ArrayList<>();

        for (int ordinal = 0; ordinal < ADMITTED_RECORD_COUNT; ordinal++) {
            byte[] sibling = record(admittedImage, ordinal);
            assertThat(Arrays.copyOfRange(sibling, 0, KEY_WIDTH))
                    .as("admitted record %d must not share the refused record's key", ordinal)
                    .isNotEqualTo(refusedKey);
            assertThat(Arrays.copyOfRange(sibling, 20, 36))
                    .as("the card is deliberately shared, so the key alone differentiates")
                    .isEqualTo(Arrays.copyOfRange(refused, 20, 36));
            admittedTransactionIds.add(textField(decode(sibling), "PA-TRANSACTION-ID"));
        }

        assertThat(textField(decode(refused), "PA-TRANSACTION-ID"))
                .isEqualTo("TXN000000000043")
                .isNotIn(admittedTransactionIds);
        assertThat(admittedTransactionIds).doesNotHaveDuplicates().hasSize(ADMITTED_RECORD_COUNT);
    }

    /**
     * Confirms the two fixtures together enumerate the accepted domain and the value outside it.
     *
     * <p>Assumptions: stating both halves in ONE case is what documents the domain as exactly four
     * states rather than as a loosely typed column. The sibling images the three states a byte can
     * hold, in the order the evidence for them appears -- {@code 'F'} and {@code 'R'} from the
     * condition names at {@code cpy/CIPAUDTY.cpy} lines 51 and 52, then the blank whose only evidence
     * is the copybook's silence combined with the render path at {@code cbl/COPAUS1C.cbl} lines 344 to
     * 349. The fourth accepted state is SQL null, which no byte image can carry because a fixed-width
     * record has no absent field; the engine-tier consumer supplies it directly. This fixture supplies
     * the fifth value, the one that must be refused.</p>
     */
    @Test
    @DisplayName("the sibling images 'F', 'R' and blank; this fixture supplies the refused 'Y'")
    void theTwoFixturesTogetherEnumerateTheDomainAndTheValueOutsideIt() {
        byte[] admittedImage = bytes(ADMITTED_FIXTURE);
        List<String> imagedStates = new ArrayList<>();

        for (int ordinal = 0; ordinal < ADMITTED_RECORD_COUNT; ordinal++) {
            byte[] sibling = record(admittedImage, ordinal);
            imagedStates.add(String.valueOf(decode(sibling).get("PA-AUTH-FRAUD")));
            assertThat(sibling[MATCH_STATUS_OFFSET])
                    .as("every admitted record also carries the valid pending match status")
                    .isEqualTo(PENDING_MATCH_BYTE);
        }

        assertThat(imagedStates)
                .as("the three states a byte can hold, in the order their evidence appears")
                .containsExactly(FRAUD_REPORTED, FRAUD_REMOVED, FRAUD_UNMARKED);
        assertThat(String.valueOf(decode(refusedRecord()).get("PA-AUTH-FRAUD")))
                .isEqualTo(REFUSED_FRAUD_MARK)
                .isNotIn(imagedStates);
    }

    /**
     * Confirms the migration still declares the four-state domain this fixture bounds.
     *
     * <p>Assumptions: the predicate is asserted as EXACT TEXT rather than by searching for the column
     * name, because the failure this guards against is a widening. A check amended to admit {@code 'Y'}
     * would still mention {@code auth_fraud}, still carry the same constraint name and still refuse
     * some values, so a looser assertion would keep passing while the bound this fixture exists to
     * hold had been removed. Asserting the whole predicate makes any amendment visible here, next to
     * the fixture that documents why it should not be made.</p>
     *
     * <p>Assumptions: the constraint NAME is asserted alongside the predicate because the engine-tier
     * consumer identifies the refusal by that name. The two assertions belong together: if the name
     * were renamed and only the engine-tier case updated, the reason the name matters would stop being
     * recorded anywhere.</p>
     */
    @Test
    @DisplayName("the migration declares the four accepted states and nothing further")
    void theMigrationStillDeclaresTheFourStateDomain() {
        String migration = migration();

        assertThat(migration)
                .as("the predicate that admits 'F', 'R', NULL and a single blank -- and no more")
                .contains(FRAUD_CHECK_PREDICATE);
        assertThat(migration).contains("CONSTRAINT " + FRAUD_CONSTRAINT_NAME);
        assertThat(migration)
                .as("the neighbouring column keeps its own separate check, so refusals differ")
                .contains("CHECK (match_status IN ('P', 'D', 'E', 'M'))");
    }

    /**
     * Confirms the record re-encodes to the identical bytes, refused flag included.
     *
     * <p>Assumptions: the codec layer must not repair or normalise a domain-invalid value. Domain
     * enforcement belongs to the database, and this assertion is what proves the codec stays out of
     * it: a codec that silently mapped an unknown flag onto a blank would make this fixture load
     * successfully and would delete the refusal it exists to provoke, while every value-level
     * assertion in this class still passed.</p>
     */
    @Test
    @DisplayName("the record round-trips byte-identically, the refused 'Y' included")
    void theRecordRoundTripsByteIdenticallyIncludingTheRefusedByte() {
        byte[] refused = refusedRecord();

        byte[] reEncoded = FixedWidthCodec.encodeRecord(decode(refused),
                CopybookLayout.layout(LAYOUT));

        assertThat(reEncoded).isEqualTo(refused);
        assertThat(reEncoded[FRAUD_OFFSET])
                .as("the refused flag survives a decode and re-encode unchanged")
                .isEqualTo(REFUSED_FRAUD_BYTE);
    }

    /**
     * Confirms a record one byte short or one byte long is refused rather than read.
     *
     * <p>Assumptions: the reader of this fixture is a FIXED-LENGTH BYTE STREAM reader, never a
     * line-oriented one, and this case is what makes that a checked property. A reader that tolerated
     * 201 bytes would tolerate an appended terminator, which
     * {@code tests/fixtures/README.md} section 3.2 identifies as the way a stray byte lands inside the
     * trailing filler; a reader that tolerated 199 would silently shift the fraud byte out of the span
     * this whole class is about.</p>
     */
    @Test
    @DisplayName("a 199- or 201-byte input is refused rather than decoded")
    void aRecordOneByteShortOrOneByteLongIsRefused() {
        byte[] refused = refusedRecord();
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        byte[] tooShort = Arrays.copyOfRange(refused, 0, SEGMENT_LENGTH - 1);
        byte[] tooLong = Arrays.copyOfRange(refused, 0, SEGMENT_LENGTH + 1);

        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(tooShort, spec))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(tooLong, spec))
                .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
    }

    /**
     * Confirms the seventeen filler bytes reach no field yet the record still closes at 200.
     *
     * <p>Assumptions: the filler is padding to the fixed segment length and carries no data, so the
     * codec drops it from the decoded map -- which is exactly the reconciliation a reader of a hex
     * dump needs stated. Seventeen bytes are present in the image, no column receives them, and the
     * declared field widths plus those seventeen sum to the declared 200. Asserting the sum rather
     * than only the absence is what would catch a layout in which the filler had been narrowed and
     * some other field widened to compensate.</p>
     */
    @Test
    @DisplayName("the seventeen filler bytes reach no field yet the record reconciles to 200")
    void theTrailingFillerReachesNoFieldYetTheRecordCloses() {
        byte[] refused = refusedRecord();
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        Map<String, Object> fields = decode(refused);
        int declaredWidth = spec.fields().stream().mapToInt(field -> field.length()).sum();

        assertThat(fields).doesNotContainKey("FILLER");
        assertThat(Arrays.copyOfRange(refused, FILLER_OFFSET, SEGMENT_LENGTH))
                .isEqualTo("                 ".getBytes(StandardCharsets.US_ASCII));
        assertThat(declaredWidth).isEqualTo(SEGMENT_LENGTH);
        assertThat(fields).hasSize(spec.fields().size() - 1);
    }

    /**
     * Confirms neither fixture holds a byte a line-oriented reader could split on.
     *
     * <p>Assumptions: the two terminator bytes carry different risks in this record type and both are
     * checked. A line feed cannot arise from a packed field at all, because the low nibble of a packed
     * byte is either a digit or a sign nibble and 0x0A is neither; it could only arrive from a binary
     * {@code COMP} field, and this segment declares none. A carriage return CAN arise, as 0x0D is the
     * byte a negative packed value whose final digit is zero produces. Every packed value here is
     * positive so neither byte is present, but the absence is asserted rather than assumed so that a
     * future fixture edit which introduced one is caught here instead of in a reader.</p>
     */
    @Test
    @DisplayName("neither fraud fixture holds a line feed or a carriage return")
    void neitherFixtureHoldsALineTerminatorByte() {
        for (String name : List.of(REFUSED_FIXTURE, ADMITTED_FIXTURE)) {
            byte[] image = bytes(name);
            assertThat(image).as("%s must hold no line feed", name).doesNotContain((byte) 0x0A);
            assertThat(image).as("%s must hold no carriage return", name)
                    .doesNotContain((byte) 0x0D);
        }
    }
}
