package com.carddemo.authorization.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.mapper.AuthFraudMapper;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes {@code pautdtl1-date-formats.bin} and asserts the three date renderings one authorization
 * carries.
 *
 * <p>Purpose: one authorization detail segment states its own date THREE times in three different
 * encodings, and this class is the executable statement of what each one means and how they relate.
 * The nines-complemented ordinal key at offsets 0 and 3 is machine order; the six characters of
 * {@code PA-AUTH-ORIG-DATE} at offset 8 are the acquirer's year-month-day; and the eight characters of
 * {@code PA-FRAUD-RPT-DATE} at offset 175 are a month-first, solidus-separated form with a two-digit
 * year. Three encodings of one fact is three chances to disagree, and nothing in this module asserted
 * that they agree until this class existed.
 *
 * <p>Refactoring Rationale: this fixture shipped with no test-source reference at all. It was
 * therefore free to change, or to vanish, with every test in the module staying green -- and it is
 * the only fixture in the directory whose whole point is the relationship BETWEEN date fields rather
 * than the value of one of them. The gap is closed by enrolling it in the closed inventory of
 * {@code AuthorizationFixtureContractTest} and by asserting its semantics here.
 *
 * <h2>Assumptions: the layout</h2>
 *
 * <p>The record is the IMS child segment {@code PAUTDTL1}, transcribed from
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54 and registered as
 * {@code PAUTDTL} in {@code CopybookLayout}. Every offset used below is taken from that registry
 * rather than restated, so a wrong offset in either the registry or the fixture fails here instead of
 * passing quietly. The fixture is 400 bytes, which is exactly two 200-byte segments; the count is
 * asserted rather than assumed.
 *
 * <h2>Assumptions: record one, where all three renderings agree</h2>
 *
 * <p>Record one is 15 July 2024 at 09:15:30.000. Its stored key bytes hold the complements
 * {@code 99999 - 24197} and {@code 999999999 - 91530000}, which
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 874 and 875 declare; its
 * originating characters are {@code 240715}; and its fraud report date is {@code 07/15/24}. Day 197 of
 * 2024 IS 15 July -- 2024 is a leap year, so the day-of-year arithmetic differs from a common year and
 * the fixture exercises the leap case deliberately -- and all three renderings therefore name one
 * calendar day. That is the property this record exists to hold.
 *
 * <h2>Assumptions: record two, the two-digit-year boundary, where they part company</h2>
 *
 * <p>Record two is the boundary case, and it does NOT resolve to one calendar day. Its stored
 * complements decode to the ordinal {@code 99365} and the time {@code 235959000}; its originating
 * characters are {@code 991231}; and its fraud report date is {@code 12/31/99}. All three agree on 31
 * December and on day 365 of a common year, and they disagree on the century, because the migration
 * supplies TWO different windows for the two digits and does so on purpose:
 *
 * <ul>
 *   <li>the two CHARACTER fields widen on a pivot of seventy --
 *       {@code PendingAuthDetailMapper.CENTURY_PIVOT} -- so {@code 99} reads as 1999. Both the
 *       originating date and the fraud report date go through that one helper, which is why they
 *       cannot disagree with each other;</li>
 *   <li>the ORDINAL key widens on a fixed century of two thousand --
 *       {@code PurgeJob}'s {@code ORDINAL_DATE_CENTURY} -- so {@code 99365} reads as 2099.</li>
 * </ul>
 *
 * <p>Assumptions: the divergence is asserted as delivered rather than reported as a defect, and the
 * reason is recorded in the constant that causes it: the pivot widens a PAST acquirer-supplied date
 * over a hundred-year window, whereas the fixed century widens a date this system itself wrote from
 * its own clock, and one constant serving both would have to be wrong for one of them. No production
 * record can expose the difference, because the writer at {@code COPAUA0C.cbl} line 868 builds the
 * ordinal from the server clock and the seed and extract data is twenty-first century throughout; only
 * a synthetic year-99 record such as this one reaches it, and it can only reach the system through the
 * extract-load path. Trade-offs: a maintainer who unifies the two windows must change the constant AND
 * this assertion together, and must decide which of the two documented rationales to abandon. Leaving
 * the boundary unasserted -- which is how it stood -- meant either window could be changed silently.
 *
 * <p>Assumptions: the purge's conversion is reproduced here as arithmetic rather than invoked, because
 * it is private to that service and the behaviour of the purge itself is exercised against repository
 * doubles in {@code PurgeJobTest}. What this class asserts is the CONTENT of the fixture and the
 * relationship between its three renderings; the constant is named locally so a reader sees the two
 * windows side by side in one file.
 *
 * <p>Assumptions: the explanation lives in this Javadoc rather than beside the bytes. The fixture is a
 * fixed-length binary record, so a comment byte would push it past the declared 200 and corrupt the
 * trailing filler, and {@code config/checkstyle/suppressions.xml} exempts the fixtures resource
 * directory from every Javadoc check while {@code checkstyle.xml} narrows the tool to
 * {@code fileExtensions="java"} -- so prose next to the bytes is policed by nothing whereas this
 * Javadoc is policed mechanically at Maven {@code validate}.
 */
class PendingAuthDetailDateFormatFixtureTest {

    /** Root of the fixture directory on the test class path. */
    private static final String ROOT = "/fixtures/";

    /** The fixture this test exists to read. */
    private static final String FIXTURE = "pautdtl1-date-formats.bin";

    /** Declared length of the authorization detail segment, {@code SEGM ... BYTES=200}. */
    private static final int SEGMENT_LENGTH = 200;

    /** Number of segments the fixture image holds. */
    private static final int RECORD_COUNT = 2;

    /** Ordinal of the leap-year record whose three date renderings agree. */
    private static final int AGREEING = 0;

    /** Ordinal of the year-99 record where the character and ordinal windows part company. */
    private static final int BOUNDARY = 1;

    /**
     * The account the two records belong to.
     *
     * <p>Assumptions: the segment carries no account identifier -- IMS supplies it hierarchically from
     * the parent -- so the mapper takes it as an argument. This is the account
     * {@code pautsum0-canonical.bin} carries, which is what satisfies
     * {@code fk_pending_auth_detail_summary}.</p>
     */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /** Complement base for the five-digit date, {@code 99999 - YYDDD} at COPAUA0C line 874. */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /** Complement base for the nine-digit time, {@code 999999999 - HHMMSSmmm} at line 875. */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /** Decoded ordinal date of record one, 15 July 2024 as YYDDD. */
    private static final int AGREEING_ORDINAL = 24_197;

    /** Decoded time of record one, 09:15:30.000 as HHMMSSmmm. */
    private static final int AGREEING_TIME = 91_530_000;

    /** Decoded ordinal date of record two, day 365 of year 99 as YYDDD. */
    private static final int BOUNDARY_ORDINAL = 99_365;

    /** Decoded time of record two, 23:59:59.000 as HHMMSSmmm. */
    private static final int BOUNDARY_TIME = 235_959_000;

    /**
     * The divisor separating the two-digit year from the three-digit day of year in an ordinal date.
     */
    private static final int ORDINAL_YEAR_DIVISOR = 1_000;

    /**
     * The century {@code PurgeJob} reads an ordinal date's two-digit year into.
     *
     * <p>Assumptions: two thousand, named here so a reader sees it beside the character fields' pivot
     * of seventy rather than having to open two files to discover the two windows differ. It is
     * reproduced rather than imported because the production constant is private to that service.</p>
     */
    private static final int ORDINAL_DATE_CENTURY = 2_000;

    /**
     * Reads one fixture resource as raw bytes.
     *
     * @param name the resource name below the fixture root
     * @return the resource's bytes, exactly as stored
     * @throws IllegalStateException if the resource is absent from the test class path or unreadable
     */
    private static byte[] bytes(String name) {
        try (InputStream fixture =
                PendingAuthDetailDateFormatFixtureTest.class.getResourceAsStream(ROOT + name)) {
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
     * @return the registered 200-byte {@code PAUTDTL} record specification
     */
    private static RecordSpec detailLayout() {
        return CopybookLayout.layout("PAUTDTL");
    }

    /**
     * Slices one fixed-length record out of the multi-record fixture image.
     *
     * <p>Assumptions: records are located by multiplying the ordinal by the declared length, never by
     * scanning for a terminator. The fixture carries no separator byte for a scan to find.</p>
     *
     * @param ordinal the zero-based record ordinal
     * @return exactly {@link #SEGMENT_LENGTH} bytes beginning at the ordinal's offset
     */
    private static byte[] recordAt(int ordinal) {
        byte[] image = bytes(FIXTURE);
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
        return FixedWidthCodec.decodeRecord(recordAt(ordinal), detailLayout());
    }

    /**
     * Returns one decoded field as text with its trailing blanks removed.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's characters with trailing blanks stripped
     */
    private static String text(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name)).stripTrailing();
    }

    /**
     * Converts a five-digit ordinal date into the calendar date the purge reads it as.
     *
     * <p>Assumptions: this reproduces {@code PurgeJob}'s private conversion exactly -- a fixed century
     * plus the day of year -- so that the window it applies can be compared here against the pivot the
     * character fields use.</p>
     *
     * @param ordinalDate a two-digit year followed by a three-digit day of year, as one integer
     * @return the calendar date that ordinal denotes under the purge's fixed century
     */
    private static LocalDate ordinalAsPurgeReadsIt(int ordinalDate) {
        return LocalDate.ofYearDay(ORDINAL_DATE_CENTURY + ordinalDate / ORDINAL_YEAR_DIVISOR,
                ordinalDate % ORDINAL_YEAR_DIVISOR);
    }

    /**
     * Confirms the fixture is exactly two whole segments of the registered length.
     */
    @Test
    @DisplayName("the date-format fixture is two whole 200-byte authorization detail segments")
    void theFixtureIsTwoWholeSegments() {
        byte[] image = bytes(FIXTURE);

        assertThat(detailLayout().reclen())
                .as("the registry declares the segment length the fixture is written against")
                .isEqualTo(SEGMENT_LENGTH);
        assertThat(image).hasSize(RECORD_COUNT * SEGMENT_LENGTH);
    }

    /**
     * Confirms the stored key bytes of both records are the nines complements of their decoded values.
     *
     * <p>Assumptions: the complement identity is asserted on the STORED value read out of the record,
     * so a fixture regenerated without the inversion fails here. The inversion is what makes ascending
     * byte order equal reverse chronological order, which {@code ims/DBPAUTP0.dbd} line 37 relies on by
     * declaring the eight key bytes as a single character sequence field.</p>
     */
    @Test
    @DisplayName("both records store the nines complement of their ordinal date and time")
    void bothRecordsStoreTheNinesComplementOfTheirKey() {
        Map<String, Object> agreeing = record(AGREEING);
        Map<String, Object> boundary = record(BOUNDARY);

        assertThat(Integer.parseInt(String.valueOf(agreeing.get("PA-AUTH-DATE-9C"))))
                .isEqualTo(DATE_COMPLEMENT_BASE - AGREEING_ORDINAL);
        assertThat(Integer.parseInt(String.valueOf(agreeing.get("PA-AUTH-TIME-9C"))))
                .isEqualTo(TIME_COMPLEMENT_BASE - AGREEING_TIME);
        assertThat(Integer.parseInt(String.valueOf(boundary.get("PA-AUTH-DATE-9C"))))
                .isEqualTo(DATE_COMPLEMENT_BASE - BOUNDARY_ORDINAL);
        assertThat(Integer.parseInt(String.valueOf(boundary.get("PA-AUTH-TIME-9C"))))
                .isEqualTo(TIME_COMPLEMENT_BASE - BOUNDARY_TIME);

        // WHY : Assumptions: the mapper is asserted to apply the same inversion the arithmetic above
        //       states, rather than the test trusting one and not the other. The entity's key holds the
        //       DECODED value, which is the whole reason the relational columns are queryable in
        //       chronological order without a complement in the predicate.
        assertThat(PendingAuthDetailMapper.toKey(recordAt(AGREEING), ACCOUNT_ID).getAuthDate())
                .isEqualTo(AGREEING_ORDINAL);
        assertThat(PendingAuthDetailMapper.toKey(recordAt(BOUNDARY), ACCOUNT_ID).getAuthTime())
                .isEqualTo(BOUNDARY_TIME);
    }

    /**
     * Confirms record one's ordinal key, originating characters and fraud report date name one day.
     *
     * <p>Assumptions: the three renderings are resolved through the THREE production paths that read
     * them -- the purge's ordinal conversion, the detail mapper's timestamp composition and the fraud
     * mapper's report-date parse -- and then compared, rather than each being compared against a
     * literal. Comparing against literals would pass on an implementation that read all three
     * consistently wrongly, which is the failure a three-way agreement is meant to exclude.</p>
     */
    @Test
    @DisplayName("record one's ordinal key, originating date and fraud report date name one day")
    void recordOneRenderingsAllNameTheSameCalendarDay() {
        byte[] segment = recordAt(AGREEING);
        Map<String, Object> fields = record(AGREEING);
        PendingAuthDetail detail = PendingAuthDetailMapper.toEntity(segment, ACCOUNT_ID);

        assertThat(text(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("240715");
        assertThat(text(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("091530");
        assertThat(text(fields, "PA-FRAUD-RPT-DATE")).isEqualTo("07/15/24");

        LocalDate fromOrdinal = ordinalAsPurgeReadsIt(detail.getId().getAuthDate().intValue());
        LocalDateTime fromCharacters = PendingAuthDetailMapper.authTimestamp(detail);
        LocalDate fromFraudReport =
                AuthFraudMapper.segmentFraudReportDate(detail.getFraudReportDate());

        assertThat(fromOrdinal)
                .as("day 197 of a leap year is 15 July, so the ordinal and the characters agree")
                .isEqualTo(LocalDate.of(2024, 7, 15))
                .isEqualTo(fromCharacters.toLocalDate())
                .isEqualTo(fromFraudReport);
        assertThat(fromCharacters)
                .as("the key's time composes with the originating date into the stored instant")
                .isEqualTo(LocalDateTime.of(2024, 7, 15, 9, 15, 30));

        // WHY : Assumptions: the fraud report date is round-tripped through the ENCODE direction as
        //       well, because the two directions of that eight-character format live in one class
        //       precisely so they cannot diverge. A parse-only assertion would pass on an encoder that
        //       emitted a form its own parse would refuse.
        assertThat(AuthFraudMapper.segmentFraudReportDateText(fromFraudReport))
                .isEqualTo("07/15/24");
    }

    /**
     * Confirms record two's two character fields widen to 1999 while its ordinal key widens to 2099.
     *
     * <p>Assumptions: this asserts a DELIBERATE divergence between two widening windows, not a
     * coincidence. The two character fields resolve through one shared pivot of seventy and therefore
     * cannot disagree with each other; the ordinal resolves through a fixed century of two thousand.
     * Both name 31 December of a common year, and both years -- 1999 and 2099 -- are common years, so
     * day 365 is 31 December in each. The class Javadoc records why the two windows are separate and
     * what a maintainer unifying them would have to decide.</p>
     */
    @Test
    @DisplayName("record two's characters widen to 1999 and its ordinal key widens to 2099")
    void recordTwoIsTheTwoDigitYearBoundaryWhereTheWindowsPart() {
        byte[] segment = recordAt(BOUNDARY);
        Map<String, Object> fields = record(BOUNDARY);
        PendingAuthDetail detail = PendingAuthDetailMapper.toEntity(segment, ACCOUNT_ID);

        assertThat(text(fields, "PA-AUTH-ORIG-DATE")).isEqualTo("991231");
        assertThat(text(fields, "PA-AUTH-ORIG-TIME")).isEqualTo("235959");
        assertThat(text(fields, "PA-FRAUD-RPT-DATE")).isEqualTo("12/31/99");

        LocalDateTime fromCharacters = PendingAuthDetailMapper.authTimestamp(detail);
        LocalDate fromFraudReport =
                AuthFraudMapper.segmentFraudReportDate(detail.getFraudReportDate());
        LocalDate fromOrdinal = ordinalAsPurgeReadsIt(detail.getId().getAuthDate().intValue());

        assertThat(fromCharacters)
                .as("the pivot of seventy widens a stored 99 into 1999")
                .isEqualTo(LocalDateTime.of(1999, 12, 31, 23, 59, 59));
        assertThat(fromFraudReport)
                .as("the fraud report date goes through the same pivot, so it cannot disagree with the"
                        + " originating date")
                .isEqualTo(LocalDate.of(1999, 12, 31))
                .isEqualTo(fromCharacters.toLocalDate());
        assertThat(fromOrdinal)
                .as("the ordinal key's fixed century of two thousand reads the same two digits as 2099")
                .isEqualTo(LocalDate.of(2099, 12, 31));

        assertThat(fromOrdinal.getYear())
                .as("the two windows are a century apart on this record, which is the boundary it exists"
                        + " to hold; unifying them changes one of two documented rationales")
                .isNotEqualTo(fromCharacters.getYear());
        assertThat(fromOrdinal.getMonthValue()).isEqualTo(fromCharacters.getMonthValue());
        assertThat(fromOrdinal.getDayOfMonth()).isEqualTo(fromCharacters.getDayOfMonth());
        assertThat(fromOrdinal.getDayOfYear())
                .as("day 365 is 31 December in a common year, and both 1999 and 2099 are common years")
                .isEqualTo(BOUNDARY_ORDINAL % ORDINAL_YEAR_DIVISOR)
                .isEqualTo(fromCharacters.getDayOfYear());

        // WHY : Assumptions: the boundary record is asserted to be ACCEPTED by the domain, because the
        //       century ambiguity is an interpretation question and not a validity one. Its ordinal
        //       99365 and its time 235959000 both sit inside the key's declared domain, so refusing it
        //       would reject data the reference application would have stored.
        assertThat(detail.getId().getAuthDate()).isEqualTo(BOUNDARY_ORDINAL);
        assertThat(detail.getId().getAuthTime()).isEqualTo(BOUNDARY_TIME);
    }
}
