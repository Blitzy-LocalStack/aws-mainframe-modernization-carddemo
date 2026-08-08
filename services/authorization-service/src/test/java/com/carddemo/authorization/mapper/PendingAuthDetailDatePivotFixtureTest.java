package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consumes {@code pautdtl1-date-formats.bin} and asserts both century-pivot branches.
 *
 * <p>Purpose: the reference application stores two-digit years and never widens them, so the migration
 * supplies a pivot -- {@link PendingAuthDetailMapper#CENTURY_PIVOT} at seventy -- and every stored year
 * takes one of two branches through it. This fixture is the only committed record pair that reaches BOTH:
 * its first record carries year {@code 24} and takes the twenty-first-century branch, its second carries
 * year {@code 99} and takes the twentieth. Until this class existed, no test reached the second branch at
 * all, and the mapper's own Javadoc asserted in prose that no committed fixture did.
 *
 * <h2>Refactoring Rationale: why this file needed a consumer, and what its absence had already cost</h2>
 *
 * <p>The fixture was committed with no executable reader, so all 400 of its bytes were free to change
 * while the module stayed green. That alone is the standing reason every fixture here has a consumer. The
 * specific cost in this case is sharper: {@link PendingAuthDetailMapper#calendarYearOf(int)} documented its
 * pivot with the sentence "Every two-digit year in the committed fixtures is 23 or 24 and therefore takes
 * the twenty-first-century branch", and this file falsified it the moment it landed. A reader consulting
 * that sentence would have concluded the twentieth-century branch was unreachable in practice and could
 * have removed it. The sentence is now corrected to describe both branches, and this class is what keeps
 * the corrected version true: adding a fixture year below seventy fails here rather than making a
 * paragraph quietly wrong.
 *
 * <h2>Assumptions: why this fixture consumer lives here rather than in the fixtures package</h2>
 *
 * <p>Most fixture consumers in this module are filed under {@code com.carddemo.authorization.fixtures},
 * because a fixture family typically spans several production packages and belongs under none of them.
 * This one does not: every rule it asserts is declared by {@link PendingAuthDetailMapper}, and every one
 * of those declarations is PACKAGE-PRIVATE -- the pivot constant, the widening function and the three
 * render rules are all {@code static} without {@code public}. Filing this class in the fixtures package
 * would therefore have required widening five members of a production type for a test's benefit, which
 * the subtree's own convention exists to avoid; the sibling wire-fixture consumers in this package were
 * filed the same way for the same reason. The 500-byte buffer and the transmitted reply frame stay in the
 * fixtures package because their subject is the shared codec, whose surface is public.
 *
 * <h2>Assumptions: the layout and the two records</h2>
 *
 * <p>Each record is the IMS child segment {@code PAUTDTL1} from
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54, registered as
 * {@code PAUTDTL}, 200 bytes per record, so a 400-byte file is exactly two records. Every offset used
 * below is read from the registry rather than restated, so a wrong offset in either the registry or the
 * fixture fails here instead of passing quietly.
 *
 * <p>The two records differ in the three date-bearing fields and nowhere that would confound the
 * comparison. Record one carries {@code PA-AUTH-ORIG-DATE} {@code 240715}, {@code PA-AUTH-ORIG-TIME}
 * {@code 091530} and {@code PA-CARD-EXPIRY-DATE} {@code 0826}; record two carries {@code 991231},
 * {@code 235959} and {@code 1299}. The second record's values are the extreme of every field it touches
 * -- the last day of the last month, one second before midnight, and a year on the far side of the pivot
 * -- which is what makes one record enough to reach the branch and the boundary together.
 *
 * <h2>Assumptions: the two render rules disagree about field order, deliberately</h2>
 *
 * <p>{@code renderOriginatingDate} RE-ORDERS its six characters to a month-first form, because
 * {@code cbl/COPAUS1C.cbl} moves the stored pairs into a display field in that order; a stored
 * {@code 991231} therefore displays as {@code 12/31/99}. {@code renderOriginatingTime} does NOT re-order,
 * because the stored order is already hours, minutes, seconds. Both are asserted here on the same record,
 * on purpose: a reader who generalised either rule to the other would corrupt the other, and the two
 * records make the asymmetry visible rather than stated.
 *
 * <p>Trade-offs: the rendered forms are asserted as literals rather than derived from the stored
 * characters. Deriving them would make this class re-implement the render rule and agree with its own
 * copy, which is exactly the failure a fixture-backed test exists to avoid.
 */
class PendingAuthDetailDatePivotFixtureTest {

    /** The classpath location of the fixture this class reads. */
    private static final String FIXTURE = "fixtures/pautdtl1-date-formats.bin";

    /** The registry name of the layout the fixture is written against. */
    private static final String LAYOUT = "PAUTDTL";

    /** Number of records the file holds, being its byte length divided by the declared length. */
    private static final int RECORD_COUNT = 2;

    /** The record whose two-digit year is on the twenty-first-century side of the pivot. */
    private static final int TWENTY_FIRST_CENTURY_RECORD = 0;

    /** The record whose two-digit year is on the twentieth-century side of the pivot. */
    private static final int TWENTIETH_CENTURY_RECORD = 1;

    /**
     * Reads the whole fixture as raw bytes.
     *
     * @return the fixture's bytes, exactly as stored
     * @throws AssertionError if the resource is not on the test classpath
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] image() {
        try (InputStream stream = PendingAuthDetailDatePivotFixtureTest.class.getClassLoader()
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
     * Decodes one record of the fixture through the registered layout.
     *
     * @param ordinal the zero-based record ordinal
     * @return the decoded field map, keyed by copybook field name
     * @throws AssertionError if the resource is not on the test classpath
     */
    private static Map<String, Object> record(int ordinal) {
        RecordSpec spec = CopybookLayout.layout(LAYOUT);
        byte[] whole = image();
        byte[] slice = new byte[spec.reclen()];
        System.arraycopy(whole, ordinal * spec.reclen(), slice, 0, spec.reclen());
        return FixedWidthCodec.decodeRecord(slice, spec);
    }

    /**
     * Returns one decoded text field with its trailing blanks removed.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's characters with trailing blanks stripped
     */
    private static String textField(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name)).stripTrailing();
    }

    /**
     * Confirms the file is exactly two whole records of the declared segment length.
     *
     * <p>Assumptions: the length is asserted as the product of the registered record length and the
     * record count rather than against the literal 400, so a change to the registered geometry fails
     * here instead of leaving a file that no longer divides.</p>
     */
    @Test
    @DisplayName("the fixture is two whole records of the declared segment length")
    void theFixtureIsTwoWholeRecords() {
        int reclen = CopybookLayout.layout(LAYOUT).reclen();

        assertThat(image()).hasSize(RECORD_COUNT * reclen);
        assertThat(image().length % reclen)
                .as("a file that did not divide would mean a field had changed width")
                .isZero();
    }

    /**
     * Confirms record one's stored year takes the twenty-first-century branch.
     *
     * <p>Assumptions: the branch is asserted through the mapper's own widening function rather than by
     * comparing the stored characters, because the branch is the mapper's decision. Comparing
     * characters would prove only what the fixture holds.</p>
     */
    @Test
    @DisplayName("a stored year below the pivot widens into the twenty-first century")
    void aYearBelowThePivotWidensIntoTheTwentyFirstCentury() {
        Map<String, Object> fields = record(TWENTY_FIRST_CENTURY_RECORD);
        String storedDate = textField(fields, "PA-AUTH-ORIG-DATE");

        assertThat(storedDate).isEqualTo("240715");

        int storedYear = Integer.parseInt(storedDate.substring(0, 2));
        assertThat(storedYear)
                .as("this record is on the twenty-first-century side of the pivot")
                .isLessThan(PendingAuthDetailMapper.CENTURY_PIVOT);
        assertThat(PendingAuthDetailMapper.calendarYearOf(storedYear)).isEqualTo(2024);

        assertThat(PendingAuthDetailMapper.renderOriginatingDate(storedDate))
                .as("the stored pairs are re-ordered to a month-first display form")
                .isEqualTo("07/15/24");
        assertThat(PendingAuthDetailMapper.renderOriginatingTime(
                textField(fields, "PA-AUTH-ORIG-TIME")))
                .as("the time keeps its stored order and only gains separators")
                .isEqualTo("09:15:30");
        assertThat(PendingAuthDetailMapper.renderCardExpiry(
                textField(fields, "PA-CARD-EXPIRY-DATE")))
                .isEqualTo("08/26");
    }

    /**
     * Confirms record two's stored year takes the twentieth-century branch.
     *
     * <p>Assumptions: this is the assertion that did not exist. The widening is asserted to be 1999 and
     * NOT 2099, because those are the two answers the pivot chooses between and only one of them is a
     * date this application could have recorded. A mapper whose pivot had been raised past ninety-nine,
     * or removed, would return 2099 here and every other test in the module would stay green.</p>
     *
     * <p>Assumptions: the pivot constant is read from the mapper rather than written as seventy, so this
     * case states which SIDE of the pivot the record is on rather than restating the pivot's value. A
     * literal here would let the test and the mapper disagree about the boundary while both passed.</p>
     */
    @Test
    @DisplayName("a stored year at or above the pivot widens into the twentieth century")
    void aYearAtOrAboveThePivotWidensIntoTheTwentiethCentury() {
        Map<String, Object> fields = record(TWENTIETH_CENTURY_RECORD);
        String storedDate = textField(fields, "PA-AUTH-ORIG-DATE");

        assertThat(storedDate).isEqualTo("991231");

        int storedYear = Integer.parseInt(storedDate.substring(0, 2));
        assertThat(storedYear)
                .as("this record is on the twentieth-century side of the pivot")
                .isGreaterThanOrEqualTo(PendingAuthDetailMapper.CENTURY_PIVOT);
        assertThat(PendingAuthDetailMapper.calendarYearOf(storedYear))
                .as("the pivot resolves 99 to 1999 and must not resolve it to 2099")
                .isEqualTo(1999)
                .isNotEqualTo(2099);

        assertThat(PendingAuthDetailMapper.renderOriginatingDate(storedDate))
                .as("re-ordering is independent of the century the year resolves to")
                .isEqualTo("12/31/99");
        assertThat(PendingAuthDetailMapper.renderOriginatingTime(
                textField(fields, "PA-AUTH-ORIG-TIME")))
                .isEqualTo("23:59:59");
        assertThat(PendingAuthDetailMapper.renderCardExpiry(
                textField(fields, "PA-CARD-EXPIRY-DATE")))
                .isEqualTo("12/99");
    }

    /**
     * Confirms the two records land on opposite sides of the pivot, and that the pivot is a boundary.
     *
     * <p>Assumptions: the pair is asserted as a PAIR rather than only record by record, because what the
     * fixture exists to establish is that both branches are reachable from committed data. Two cases
     * each passing alone would not say that the two chose differently.</p>
     *
     * <p>Assumptions: the boundary itself is exercised at the pivot and one below it. Those two inputs
     * are supplied in-test rather than added to the fixture, because a pivot boundary is a property of
     * the widening rule and not of any authorization this application recorded -- committing a record
     * dated in 1970 to assert it would put a date in the corpus that the reference system could not have
     * produced.</p>
     */
    @Test
    @DisplayName("the two records take opposite branches and the pivot is the boundary between them")
    void theTwoRecordsTakeOppositeBranchesAcrossThePivot() {
        int firstYear = Integer.parseInt(
                textField(record(TWENTY_FIRST_CENTURY_RECORD), "PA-AUTH-ORIG-DATE").substring(0, 2));
        int secondYear = Integer.parseInt(
                textField(record(TWENTIETH_CENTURY_RECORD), "PA-AUTH-ORIG-DATE").substring(0, 2));

        assertThat(PendingAuthDetailMapper.calendarYearOf(firstYear))
                .as("the two committed years must resolve to different centuries")
                .isNotEqualTo(PendingAuthDetailMapper.calendarYearOf(secondYear));
        assertThat(PendingAuthDetailMapper.calendarYearOf(firstYear) / 100).isEqualTo(20);
        assertThat(PendingAuthDetailMapper.calendarYearOf(secondYear) / 100).isEqualTo(19);

        int pivot = PendingAuthDetailMapper.CENTURY_PIVOT;
        assertThat(PendingAuthDetailMapper.calendarYearOf(pivot))
                .as("the pivot itself is the first year on the twentieth-century side")
                .isEqualTo(1900 + pivot);
        assertThat(PendingAuthDetailMapper.calendarYearOf(pivot - 1))
                .as("one below the pivot is the last year on the twenty-first-century side")
                .isEqualTo(2000 + pivot - 1);
    }

    /**
     * Confirms the composed authorization timestamp carries the stored two-digit year unwidened.
     *
     * <p>Assumptions: the composed 23-character form keeps the year at TWO digits, which is why the
     * pivot does not appear in it. That is worth an assertion rather than an omission: the pivot governs
     * the widening used when a calendar date is constructed, while this composition is a re-spacing of
     * stored characters for a conversion mask of exactly this width. A composition that widened the year
     * would produce a 25-character string the mask rejects as a data exception naming neither the field
     * nor the record.</p>
     */
    @Test
    @DisplayName("the composed timestamp re-spaces the stored characters and widens no year")
    void theComposedTimestampWidensNoYear() {
        String storedDate = textField(record(TWENTIETH_CENTURY_RECORD), "PA-AUTH-ORIG-DATE");

        String composed = PendingAuthDetailMapper.composeAuthTimestampText(storedDate, 1_000);

        assertThat(composed).hasSize(PendingAuthDetailMapper.AUTH_TIMESTAMP_LENGTH);
        assertThat(composed).startsWith("99-12-31 ");
        assertThat(composed)
                .as("the composition keeps the stored order and does not re-order like the display rule")
                .doesNotStartWith("12-31-99");
        assertThat(composed).endsWith(PendingAuthDetailMapper.MILLISECOND_MICROSECOND_PAD);
    }
}
