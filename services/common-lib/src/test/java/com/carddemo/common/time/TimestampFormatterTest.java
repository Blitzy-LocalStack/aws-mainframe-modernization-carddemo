package com.carddemo.common.time;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TimestampFormatter} against the exact twenty-six character timestamp contract, its
 * deterministic conversion behaviour and the three distinct failure families it signals.
 *
 * <p>Every expectation below is pinned to an immutable reference artifact rather than to a value
 * chosen for the test, and each citation is written beside the expectation it fixes so that a reader
 * can re-derive the expectation without leaving this file.</p>
 *
 * <p>Assumptions: one shared utility is tested here, once, rather than a timestamp test per consuming
 * service. The paragraph this class replaces exists twice in the reference baseline and is byte for
 * byte identical in both copies, {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBTRN02C.cbl}
 * lines 692 to 705 in the posting program and at {@code app/cbl/CBACT04C.cbl} lines 613 to 626 in the
 * interest program, the same fourteen lines at a constant offset of seventy nine. Because the two
 * baseline copies are identical, a posting-service test and an interest-service test of the same
 * rendering would assert the same bytes twice, and the two copies would then be free to drift apart
 * exactly as the COBOL pair can. Alternatives Considered: duplicating these expectations into a test
 * class per consuming service was evaluated and rejected on that ground: it would reproduce in the
 * target the very duplication the shared kernel exists to remove, and it would leave no single place
 * that fails when the contract changes.</p>
 *
 * <p>Assumptions: the production formatter resolves the pattern {@code uuuu-MM-dd HH:mm:ss.SSSSSS}
 * with {@link ResolverStyle#STRICT}, so the class under test refuses a well-punctuated value of the
 * correct width whose date or time the calendar does not have, instead of adjusting it into a
 * neighbouring one. That refusal is asserted here as part of the contract rather than assumed, because
 * it is the behaviour a later change to the pattern or the resolver would silently remove.</p>
 *
 * <p>Assumptions: the contract has two halves and both are exercised here. Rendering and reading turn
 * the value into and out of its twenty-six character text, and REDUCING turns it into the microsecond
 * resolution that text can carry, which is what a caller persists. The reducing half is not observable
 * through the rendered string -- the fraction field discards a seventh digit whether or not the value
 * was reduced first -- so it is asserted directly against
 * {@link TimestampFormatter#normalize(LocalDateTime)} and
 * {@link TimestampFormatter#normalizeNow(Clock)}, and the two published year bounds that make the
 * width a contract at all are asserted against the entry points they constrain. Expectations phrased
 * only against rendered text cannot see either property, which is why neither is left to them.</p>
 *
 * <p>Trade-offs: the expectations are split across many small methods rather than gathered into a few
 * broad ones. The width, the date-to-time separator, the time punctuation, the count of fractional
 * digits, the calendar validity and the three exception families all fail independently of one
 * another, so one broad method would report that something differed without saying which. The cost is
 * a longer file; the gain is that a failure names the property that broke.</p>
 *
 * <p>Assumptions: no clock is ever read from the ambient environment here. Where a current value is
 * needed the test supplies {@link Clock#fixed(Instant, java.time.ZoneId)}, which is what makes the
 * rendered result assertable at all.</p>
 */
class TimestampFormatterTest {

    // WHY : Assumptions: this is an observed value and not a value invented for the test. The first
    //       record of tests/fixtures/posting/happy_path/dailytran.txt carries exactly these bytes at
    //       one-based columns 279 to 304, which is the twenty-six character processing-timestamp field
    //       of a 350-byte daily-transaction record. Using the fixture's own bytes means a change that
    //       breaks the contract also breaks against real reference data rather than only against this
    //       suite's opinion of it.
    private static final String FIXTURE_TIMESTAMP = "2022-06-10 19:27:53.000000";

    // WHY : Assumptions: the nanosecond argument is an explicit zero rather than omitted, because the
    //       fractional second is contractual. The fixture's six fractional positions are all zero, so
    //       a value built without stating the zero would still render '.000000' and would leave a
    //       reader unable to tell whether the six zeroes came from the data or from a default.
    private static final LocalDateTime FIXTURE_LOCAL_DATE_TIME =
            LocalDateTime.of(2022, 6, 10, 19, 27, 53, 0);

    // WHY : Assumptions: ten is stated twice in the reference baseline and is not derivable from
    //       TIMESTAMP_LENGTH, so it is declared here beside its citations rather than computed. It is
    //       the EXPORT-DATE field of app/cpy/CVEXPORT.cpy line 13, and the field length of the sort
    //       symbol TRAN-PROC-DT,305,10,CH at app/jcl/TRANREPT.jcl line 42.
    private static final int DATE_PREFIX_LENGTH = 10;

    // WHY : Assumptions: both forms are declared because two entry points return the prefix in two
    //       types, and asserting the typed result against a re-sliced substring of the input would
    //       make the expectation depend on the same slicing the method under test performs.
    private static final String FIXTURE_DATE_PREFIX = "2022-06-10";

    private static final LocalDate FIXTURE_LOCAL_DATE = LocalDate.of(2022, 6, 10);

    // WHY : Assumptions: the width is taken from the production constant rather than written as a
    //       literal run of spaces, so this vector cannot fall out of step with the contract it is
    //       meant to probe. The evidence that blank is a real state and not a hypothetical one is
    //       tests/fixtures/export/happy_path/trandata.txt, whose five 350-byte records each carry
    //       twenty-six spaces at one-based columns 305 to 330. Note what that fixture does and does
    //       not prove: it fixes the WIDTH and the placement of a blank field, and it says nothing
    //       whatever about punctuation, because there is no punctuation in it to observe.
    private static final String BLANK_TIMESTAMP = " ".repeat(TimestampFormatter.TIMESTAMP_LENGTH);

    // WHY : Assumptions: app/jcl/INTCALC.jcl line 22 runs
    //       //STEP15 EXEC PGM=CBACT04C,PARM='2022071800', so this is a real production parameter and
    //       not a malformed timestamp someone might type. It is retained here precisely because it is
    //       ten characters, the same count as the ISO date prefix, which is what makes it the most
    //       plausible wrong argument a caller could hand these methods.
    private static final String COMPACT_BUSINESS_DATE = "2022071800";

    // WHY : Assumptions: app/cbl/CBTRN02C.cbl line 149 carries the comment
    //       '* T I M E S T A M P   D B 2  X(26)     EEEE-MM-DD-UU.MM.SS.HH0000'. That mask is an
    //       accurate description of the Db2 form the baseline emits, written in the baseline's own
    //       display-mask notation. It is held here for one purpose only, the negative regression
    //       below: mask notation and java.time pattern notation are different languages, and this
    //       constant is what proves that transliterating one into the other does not compile into a
    //       working formatter.
    private static final String NATIVE_DISPLAY_MASK = "EEEE-MM-DD-UU.MM.SS.HH0000";

    // WHY : Alternatives Considered: this is the pattern the prose contract reads like, and it is the
    //       one a maintainer is most likely to reach for, so the reason it is not the production
    //       pattern is demonstrated rather than described. It is built here in the test and never
    //       obtained from the class under test, because reaching into the private production constant
    //       would assert the implementation instead of the behaviour.
    private static final DateTimeFormatter YEAR_OF_ERA_STRICT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    // WHY : Assumptions: a second independently built formatter is declared rather than reusing the
    //       first with a different letter, so that the two can be applied to the SAME input in the
    //       same test and the difference attributed to the one letter that differs between them.
    private static final DateTimeFormatter PROLEPTIC_YEAR_STRICT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Confirms the published contract width is twenty-six characters.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing constant as a test
     * failure quoting both the expected and the actual width.</p>
     *
     * <p>Assumptions: the width is asserted directly, as a value, because six independent reference
     * artifacts agree on it and every other expectation in this file is measured against it. It is
     * {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy} line 17, at {@code app/cbl/CBTRN02C.cbl} line
     * 159 and at {@code app/cpy/CVEXPORT.cpy} line 11. A change to this constant is a change to the
     * wire format of every record that carries the field, so it is pinned here rather than read from
     * the class and compared with itself.</p>
     */
    @Test
    void timestampLengthConstantIsTwentySix() {
        assertEquals(26, TimestampFormatter.TIMESTAMP_LENGTH,
                "the timestamp contract width is fixed at twenty-six characters by the reference "
                        + "record layouts and cannot be changed without changing those layouts");
    }

    /**
     * Confirms that formatting the fixture instant yields the canonical twenty-six character form,
     * character for character.
     *
     * <p>Takes no parameters and returns no value. JUnit reports any difference as a test failure
     * showing the expected and actual strings, so a single wrong separator is visible in the report.</p>
     *
     * <p>Trade-offs: this expectation is a whole-string equality rather than a set of substring
     * probes. A whole-string comparison is the only form that fixes all six properties at once -- the
     * single space where ISO 8601 would place a {@code T}, the two colons inside the time, the sole
     * period before the fraction, the zero padding of every component, all six fractional digits, and
     * the total width -- and a substring probe would leave whichever property it did not name free to
     * change unnoticed. The cost is that the failure message names the whole value rather than the one
     * property that broke, which is why the properties that fail for independent reasons each also
     * have a method of their own below.</p>
     *
     * <p>Alternatives Considered: emitting the reference baseline's native Db2 punctuation,
     * {@code YYYY-MM-DD-HH.MM.SS.cc0000}, was evaluated and not adopted. The baseline's mask comment
     * at {@code app/cbl/CBTRN02C.cbl} line 149 accurately documents that native form, and its
     * timestamp paragraph builds it by moving three hyphens into {@code DB2-STREEP-1} through
     * {@code DB2-STREEP-3} and three dots into {@code DB2-DOT-1} through {@code DB2-DOT-3}. Two
     * findings decided against carrying it forward. First, {@code app/cpy/CVEXPORT.cpy} line 14
     * models the date-to-time separator as its own separately addressable {@code PIC X(1)} field,
     * {@code EXPORT-DATE-TIME-SEP}, between the ten-byte date at line 13 and the fifteen-byte time at
     * line 15; a layout that gives the separator its own name is a layout in which the separator is a
     * rendering choice while the {@code 10 + 1 + 15} geometry is the contract. Second, the target
     * consumers of this value are a browser, a JSON API and a {@code TIMESTAMP(6)} column, and all
     * three read the space-and-colon form natively, so carrying the Db2 punctuation would add a
     * translation at every boundary instead of at none. The baseline emits its native form; this class
     * emits the documented canonical target form; both are twenty-six characters at the same field
     * position, and the divergence is deliberate and documented.</p>
     */
    @Test
    void formatRendersTheCanonicalTwentySixCharacterForm() {
        String rendered = TimestampFormatter.format(FIXTURE_LOCAL_DATE_TIME);

        assertEquals(FIXTURE_TIMESTAMP, rendered,
                "the rendered value must match the observed fixture bytes at columns 279 to 304 of "
                        + "the first record of tests/fixtures/posting/happy_path/dailytran.txt");

        // WHY : Assumptions: the width is asserted separately even though the equality above already
        //       implies it. The two expectations answer different questions -- whether the value is
        //       the right value, and whether it fits the PIC X(26) field it is assigned to -- and a
        //       width breach is the failure that silently truncates a record rather than rejecting
        //       it, so it is worth naming in its own message.
        assertEquals(TimestampFormatter.TIMESTAMP_LENGTH, rendered.length(),
                "a rendered contract value must occupy the whole fixed-width field and no more");
    }

    /**
     * Confirms that a value carrying nonzero microseconds keeps all six of its fractional digits.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a truncated, rounded or zero-filled
     * fraction as a test failure showing the two strings.</p>
     *
     * <p>Trade-offs: the target contract exposes six fractional positions on every value, whereas the
     * reference baseline can only ever populate two of them. The baseline clock field
     * {@code COB-MIL PIC X(02)} holds hundredths of a second, and the timestamp paragraph moves it
     * into {@code DB2-MIL} and then moves the literal {@code '0000'} into {@code DB2-REST}, so the
     * last four fractional positions of a baseline value are structural zeroes rather than measured
     * digits. The compromise accepted here is that the target does not restrict itself to the
     * baseline's resolution: a {@link LocalDateTime} carrying real microseconds renders them, and a
     * value migrated from the baseline simply arrives with four trailing zeroes of its own accord. The
     * alternative, quantising every value to hundredths so that the target could produce nothing the
     * baseline could not, was rejected because it would discard precision the target column
     * {@code TIMESTAMP(6)} is declared to hold.</p>
     */
    @Test
    void formatPreservesNonZeroMicroseconds() {
        // WHY : Assumptions: 123_456_000 nanoseconds is exactly 123456 microseconds, a whole number of
        //       microseconds chosen so that this expectation isolates the WIDTH and ORDER of the
        //       fraction field. A value carrying sub-microsecond nanoseconds would additionally
        //       exercise the truncation boundary, which is a separate concern and would make a failure
        //       here ambiguous between the two. That concern is not left uncovered by the split: it is
        //       asserted directly against the reducing entry point, in
        //       normalizeTruncatesTheSeventhFractionalDigitRatherThanRounding below, on a vector this
        //       method deliberately does not carry. Assumptions: the division of labour is what makes
        //       each failure attributable, and it is stated here so that a whole-microsecond vector in
        //       this method is read as a scope decision rather than as the only fraction ever tried.
        LocalDateTime withMicroseconds = LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_000);

        assertEquals("2022-06-10 19:27:53.123456",
                TimestampFormatter.format(withMicroseconds),
                "six fractional digits must carry the supplied microseconds rather than being "
                        + "padded with zeroes");

        // WHY : Assumptions: the zero-microsecond case is retained alongside the nonzero one because
        //       the two fail for opposite reasons. A formatter that dropped the fraction entirely
        //       would still satisfy neither, but a formatter that emitted a variable-width fraction
        //       would satisfy the nonzero case and fail only here, on the value the reference fixture
        //       actually contains.
        assertEquals(FIXTURE_TIMESTAMP, TimestampFormatter.format(FIXTURE_LOCAL_DATE_TIME),
                "a zero fraction must still occupy all six positions as '.000000'");
    }

    /**
     * Confirms that a seventh fractional digit is discarded by truncation and never by rounding, at
     * both carry boundaries, and that reducing an already-reduced value changes nothing.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a rounded result, a moved second, a
     * moved day or a non-idempotent reduction as a test failure showing the two values.</p>
     *
     * <p>Assumptions: this is the only method in this file whose vectors carry a fraction FINER than
     * the contract resolution, and that is what makes it the only one able to observe the reduction at
     * all. The rendering fraction field discards the remainder of its own accord, so a value carrying
     * {@code 123_456_789} nanoseconds renders {@code .123456} whether or not it was reduced first --
     * measured on JDK 21.0.11 -- which means no expectation phrased against a rendered string can
     * distinguish a reducing implementation from a non-reducing one. Asserting the reduced VALUE is
     * what draws that distinction, and it is the reason this method exercises
     * {@code normalize} directly rather than reaching it through {@code format}.</p>
     *
     * <p>Trade-offs: the two carry vectors cost two extra assertions and they are kept because
     * rounding and truncation differ only on them. Rounding {@code 19:27:53.999999500} advances the
     * second to 54, and rounding {@code 23:59:59.999999500} advances the day to the eleventh -- the
     * second case was measured to produce {@code 2022-06-11T00:00}, so it moves the ten-character
     * date prefix that every reference consumer of this field reads,
     * {@code app/cbl/CBTRN02C.cbl} line 414 comparing {@code DALYTRAN-ORIG-TS (1:10)} and
     * {@code app/jcl/TRANREPT.jcl} line 42 sorting on {@code TRAN-PROC-DT,305,10,CH}. A test that
     * omitted them would pass against the rounding the database driver performs, which is the one
     * behaviour this contract exists to exclude.</p>
     *
     * <p>Alternatives Considered: asserting only that the reduced nanosecond field is a whole
     * multiple of one thousand was evaluated and rejected. It holds for a rounded value too, so it
     * would state the resolution without stating the DIRECTION, and the direction is the whole
     * decision. The reduced values are therefore pinned exactly, and the whole-multiple property is
     * asserted beside them as the weaker consequence rather than in place of them.</p>
     */
    @Test
    void normalizeTruncatesTheSeventhFractionalDigitRatherThanRounding() {
        // WHY : Assumptions: 123_456_789 is the vector the reduction is specified against, and its
        //       expected result 123_456_000 is stated as a literal rather than computed from the
        //       input. Deriving the expectation by the same division the implementation performs
        //       would make the two agree by construction and assert nothing.
        LocalDateTime subMicrosecond = LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_789);

        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_000),
                TimestampFormatter.normalize(subMicrosecond),
                "a fraction finer than one microsecond must be discarded, not rounded up");

        assertEquals(123_456_000, TimestampFormatter.normalize(subMicrosecond).getNano(),
                "the reduced nanosecond field must be a whole number of microseconds");

        // WHY : Assumptions: the second boundary is asserted as a whole value rather than as a
        //       component read, because rounding here would change the second AND leave every other
        //       component untouched, and a component-level expectation on the fraction alone would
        //       not see that.
        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 999_999_000),
                TimestampFormatter.normalize(
                        LocalDateTime.of(2022, 6, 10, 19, 27, 53, 999_999_500)),
                "at the second boundary the discarded half-microsecond must not advance the second");

        assertEquals(LocalDateTime.of(2022, 6, 10, 23, 59, 59, 999_999_000),
                TimestampFormatter.normalize(
                        LocalDateTime.of(2022, 6, 10, 23, 59, 59, 999_999_500)),
                "at the day boundary the discarded half-microsecond must not advance the date prefix");

        // WHY : Assumptions: idempotence is asserted because the class documents the reduction as
        //       something a caller may apply at more than one layer, and a caller cannot rely on that
        //       unless a second application is a no-op. Applying it twice in one expression is the
        //       only form that states it.
        LocalDateTime reducedOnce = TimestampFormatter.normalize(subMicrosecond);

        assertEquals(reducedOnce, TimestampFormatter.normalize(reducedOnce),
                "reducing an already-reduced value must change nothing");

        // WHY : Trade-offs: these last two expectations duplicate no assertion above and they are the
        //       reason the reduction is public at all. The class promises that a caller which persists
        //       the reduced value is persisting exactly what the rendered string says, and the promise
        //       is only kept while ONE truncation feeds both. Rendering the reduced value and reading
        //       the rendered value back are the two halves of that promise, and asserting them here
        //       means a future change deriving the two from separate truncations fails a test rather
        //       than drifting apart at the seventh digit in production.
        assertEquals(TimestampFormatter.format(subMicrosecond),
                TimestampFormatter.format(reducedOnce),
                "rendering the reduced value must produce the same string as rendering the raw one");

        assertEquals(reducedOnce,
                TimestampFormatter.parse(TimestampFormatter.format(subMicrosecond)),
                "the rendered value must read back as exactly the value a caller should persist");
    }

    /**
     * Confirms that reducing the reading of a caller-supplied fixed clock truncates it and agrees with
     * reducing that reading by hand.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing value as a test failure
     * showing both.</p>
     *
     * <p>Assumptions: the clock is pinned and carries a deliberate sub-microsecond component, because
     * a clock whose reading were already a whole microsecond would make this expectation hold under
     * an implementation that reduced nothing. The instant below carries {@code 123456789}
     * nanoseconds, which was measured on JDK 21.0.11 to be preserved in full by
     * {@link Clock#fixed(Instant, java.time.ZoneId)} rather than quantised by the clock itself, so the
     * reduction under test is the only thing that can remove it.</p>
     *
     * <p>Trade-offs: the same value is also asserted against a hand-reduced reading of the same clock.
     * That second expectation is weaker on its own, since both sides would move together if the
     * reduction changed, and it is kept because it is what pins the ONE-READ obligation: an
     * implementation reading the clock a second time for the reduced value would satisfy neither
     * expectation on a moving clock, and pinning the clock is what makes the defect assertable at
     * all.</p>
     */
    @Test
    void normalizeNowReducesTheReadingOfAFixedClock() {
        // WHY : Assumptions: the zone is UTC explicitly, for the reason stated on the rendering
        //       counterpart of this method -- an absolute instant yields a different local value under
        //       a different zone, so a clock built with the platform default would assert a different
        //       expectation on a differently configured runner.
        Clock pinned = Clock.fixed(
                Instant.parse("2022-06-10T19:27:53.123456789Z"), ZoneOffset.UTC);

        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53, 123_456_000),
                TimestampFormatter.normalizeNow(pinned),
                "a clock reading carrying a seventh fractional digit must be reduced, not rounded");

        assertEquals(TimestampFormatter.normalize(LocalDateTime.now(pinned)),
                TimestampFormatter.normalizeNow(pinned),
                "reading the clock and reducing must agree with the combined entry point");

        assertEquals(0, TimestampFormatter.normalizeNow(pinned).getNano() % 1_000,
                "the reduced reading must carry no fraction below one microsecond");
    }

    /**
     * Confirms that reading a caller-supplied fixed clock produces one predictable rendering.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing rendering as a test
     * failure showing both strings.</p>
     *
     * <p>Assumptions: the clock is pinned rather than ambient, and that is what makes this expectation
     * possible to state at all. Two time sources exist in the reference baseline and they stay separate
     * in the target. A business date is INJECTED from outside the program, at
     * {@code app/jcl/INTCALC.jcl} line 22 which runs
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}, and injecting it is what lets a rerun
     * reproduce its output byte for byte. The processing stamp of that same run is a different thing:
     * it is read from a clock, and the entry point under test here is the one that reads it. The
     * injected business date is compact and unpunctuated, so it is neither produced nor consumed by
     * this class; see the method that asserts its rejection.</p>
     *
     * <p>Alternatives Considered: asserting only that the result is twenty-six characters, which is
     * the most a test of an ambient clock could assert, was rejected. It would pass against a
     * formatter that emitted the wrong field order, the wrong separators or the wrong zone, because
     * all of those are also twenty-six characters. Supplying the clock converts a shape assertion
     * into a value assertion.</p>
     */
    @Test
    void formatNowRendersDeterministicallyFromAFixedClock() {
        // WHY : Assumptions: the zone is stated explicitly as UTC rather than taken from the host,
        //       because the instant below is an absolute point in time and the local value derived
        //       from it depends entirely on the zone used to interpret it. A clock built with the
        //       platform default zone would render differently on a differently configured runner,
        //       which is the exact non-determinism this method exists to exclude.
        Clock pinned = Clock.fixed(Instant.parse("2022-06-10T19:27:53Z"), ZoneOffset.UTC);

        assertEquals(FIXTURE_TIMESTAMP, TimestampFormatter.formatNow(pinned),
                "a clock pinned to the fixture instant, read in UTC, must render the fixture value");
    }

    /**
     * Confirms that reading the canonical form yields the local date and time it names.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing result as a test failure
     * showing the expected and actual values.</p>
     *
     * <p>Assumptions: the expectation is built from its seven components rather than from a second
     * parse of the same text, so the two sides of the comparison share no code. The nanosecond
     * component is left at its default of zero here, which is the value the fixture's six zero
     * fractional digits denote.</p>
     */
    @Test
    void parseReadsTheCanonicalFormAsTheMatchingLocalDateTime() {
        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53),
                TimestampFormatter.parse(FIXTURE_TIMESTAMP),
                "every component of the twenty-six character form must survive being read back");
    }

    /**
     * Confirms that rendering a value read from the contract form reproduces the original text
     * exactly.
     *
     * <p>Takes no parameters and returns no value. JUnit reports any difference between the original
     * and the re-rendered text as a test failure showing both.</p>
     *
     * <p>Assumptions: the value carries no zone, and that is why the type crossing this boundary is
     * {@link LocalDateTime} and why a round trip through it can be exact. The reference clock structure
     * holds eight components and only seven of them ever reach the formatted result: the eighth,
     * {@code COB-REST PIC X(05)}, carries the offset from Greenwich that the clock intrinsic returns,
     * and it is declared at {@code app/cbl/CBTRN02C.cbl} line 157 and then never moved into the
     * twenty-six character value, neither in the paragraph at lines 692 to 705 nor in the
     * byte-equivalent paragraph at {@code app/cbl/CBACT04C.cbl} lines 613 to 626. No offset is
     * recorded anywhere in the source, so none is invented here.</p>
     *
     * <p>Alternatives Considered: an offset-bearing or zone-bearing round trip, through
     * {@code java.time.OffsetDateTime} or {@code java.time.ZonedDateTime}, was evaluated against that
     * finding and rejected. Either would require this test to choose an offset the reference data never
     * recorded, and the round trip would then prove only that the chosen offset survived its own
     * invention.</p>
     *
     * <p>Trade-offs: a round trip is asserted in addition to the separate format and parse
     * expectations above, and it is not redundant with them. Those two fix each direction against an
     * external value; this one fixes the two directions against each other, which is the property that
     * fails when both are wrong in the same way.</p>
     */
    @Test
    void formatAndParseRoundTripIsExact() {
        assertEquals(FIXTURE_TIMESTAMP,
                TimestampFormatter.format(TimestampFormatter.parse(FIXTURE_TIMESTAMP)),
                "reading and re-rendering a contract value must be a no-op on the text");
    }

    /**
     * Confirms that the reference baseline's native display mask is not a usable Java pattern.
     *
     * <p>Takes no parameters and returns no value. The expected failure is an
     * {@link IllegalArgumentException}, raised by {@link DateTimeFormatter#ofPattern(String, Locale)}
     * when it reaches the uppercase {@code U} of the mask, which is not a pattern letter the platform
     * defines. The exception is raised inside the assertion lambda and is captured there, so this
     * method itself completes normally; JUnit reports a missing or differently typed exception as a
     * test failure.</p>
     *
     * <p>Assumptions: a reference display mask and a {@code java.time} pattern are different notations
     * that happen to share an alphabet, so the mask cannot be transliterated into a pattern even
     * though it reads as though it could. This expectation exists to make that concrete at the point a
     * maintainer would otherwise try it. Note carefully what is being asserted and what is not: the
     * mask comment at {@code app/cbl/CBTRN02C.cbl} line 149 is an accurate description of the form the
     * baseline emits, written in the baseline's own notation, and nothing here contradicts it. What
     * fails is the act of feeding that notation to a different parser.</p>
     */
    @Test
    void nativeDisplayMaskIsNotAValidJavaPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> DateTimeFormatter.ofPattern(NATIVE_DISPLAY_MASK, Locale.ROOT),
                "the reference display mask must be rejected as a Java pattern, so that it is never "
                        + "mistaken for one");
    }

    /**
     * Confirms that the four-letter Java pattern field the mask opens with denotes a day-of-week name
     * rather than a four-digit year.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a rendered value that looks like a
     * year as a test failure.</p>
     *
     * <p>Assumptions: in the reference mask notation the leading {@code EEEE} stands for the four
     * digits of the year, which is why the mask reads as a plausible pattern. In {@code java.time} the
     * same four letters are the day-of-week field, so a formatter built from the mask -- were the rest
     * of it valid -- would emit a weekday where a year was intended. That substitution is silent: it
     * produces text of a similar length in the same position, so it would not be caught by a width
     * check.</p>
     *
     * <p>Alternatives Considered: pinning the exact rendered glyphs, which are {@code Fri} for this
     * date under {@link Locale#ROOT}, was evaluated and rejected. Those glyphs come from the
     * platform's bundled locale data, which is versioned independently of this code, so pinning them
     * would couple a statement about pattern SEMANTICS to a data table that may legitimately change.
     * The two expectations below state the semantic claim instead and hold under any locale data: the
     * field does not produce the year, and it does not produce digits at all.</p>
     */
    @Test
    void fourLetterDayOfWeekIsNotAFourDigitYear() {
        String dayOfWeekField = DateTimeFormatter.ofPattern("EEEE", Locale.ROOT)
                .format(FIXTURE_LOCAL_DATE_TIME);

        assertNotEquals("2022", dayOfWeekField,
                "the four-letter Java field must not render the year, or a mask transliterated "
                        + "into a pattern would appear to work");

        assertFalse(dayOfWeekField.matches(".*[0-9].*"),
                "a day-of-week name carries no digits, which is what distinguishes it from the "
                        + "four-digit year the reference mask intends at that position");
    }

    /**
     * Confirms that the production parser accepts the fixture value, that the year-of-era pattern
     * under strict resolution refuses it, and that the proleptic-year pattern under strict resolution
     * accepts it.
     *
     * <p>Takes no parameters and returns no value. The expected failure in the middle expectation is a
     * {@link DateTimeParseException}, raised because a strictly resolved year-of-era field yields no
     * year unless an era field accompanies it, and the fixture form carries no era. That exception is
     * captured inside the assertion lambda, so this method itself completes normally; JUnit reports a
     * missing or differently typed exception, or a rejected valid value, as a test failure.</p>
     *
     * <p>Alternatives Considered: the pattern letter and the resolver style are a single joint
     * decision, and this method exists to record which of the three available combinations production
     * uses and what each of the other two costs. Writing the year as {@code yyyy} reads closer to the
     * prose contract, so it is the combination a maintainer is most likely to reach for; combined with
     * strict resolution it does not merely behave differently, it rejects every value the contract
     * admits, as the middle expectation shows. Keeping {@code yyyy} and dropping strict resolution
     * removes that failure but reintroduces the one strictness exists to prevent, because the
     * platform's default resolution adjusts an impossible date into a neighbouring valid one instead
     * of refusing it, so a caller submitting a day a month does not have would receive a different day
     * with nothing reporting a problem. Production therefore resolves {@code uuuu} strictly, which is
     * the only one of the three that both accepts every valid value and refuses every invalid one. The
     * cost accepted is that the pattern string is no longer a character-for-character echo of the
     * prose contract, which is why this method is written to make the reason legible from the test
     * rather than only from the production comment.</p>
     */
    @Test
    void yearOfEraUnderStrictResolutionRejectsWhatTheProductionParserAccepts() {
        // WHY : Assumptions: the production entry point is exercised first, so that a failure ordering
        //       makes the diagnosis obvious. If production itself cannot read the fixture then the two
        //       comparison expectations below are moot, and reporting them first would send a reader to
        //       the wrong place.
        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53),
                TimestampFormatter.parse(FIXTURE_TIMESTAMP),
                "the production parser must accept the observed reference fixture value");

        assertThrows(DateTimeParseException.class,
                () -> LocalDateTime.parse(FIXTURE_TIMESTAMP, YEAR_OF_ERA_STRICT),
                "a strictly resolved year-of-era field cannot yield a year without an era, so this "
                        + "combination would reject every value the contract admits");

        // WHY : Assumptions: the accepted alternative is asserted with the SAME input as the rejected
        //       one, and the two formatters differ by exactly one pattern letter. That is what
        //       attributes the difference to the letter rather than to the resolver, the locale or the
        //       input, each of which is held constant across the pair.
        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53),
                LocalDateTime.parse(FIXTURE_TIMESTAMP, PROLEPTIC_YEAR_STRICT),
                "the proleptic-year field resolves strictly without an era, which is why production "
                        + "uses it");
    }

    /**
     * Confirms that the ten-character ISO date prefix is sliced out of a contract value unchanged.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing prefix or a differing
     * prefix length as a test failure showing both values.</p>
     *
     * <p>Assumptions: the prefix is a live external dependency and not an internal convenience, so it
     * is asserted rather than left implied by the whole-value expectations. Two reference consumers
     * read the leading ten bytes of a twenty-six character value instead of reading the value:
     * {@code app/cbl/CBTRN02C.cbl} line 414 evaluates
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, comparing a date by slicing a
     * timestamp, and the spelling of that field is the baseline's own and is quoted as it stands;
     * {@code app/jcl/TRANREPT.jcl} line 42 declares the sort symbol {@code TRAN-PROC-DT,305,10,CH},
     * ten bytes in character mode, which lines 47 and 48 then range-compare against two character
     * literals. That second consumer is a production date-range filter executed as a LEXICOGRAPHIC
     * comparison, and it is correct only because this prefix is fixed width, zero padded and ordered
     * most significant component first, so comparing two prefixes as text answers the same question as
     * comparing them as dates.</p>
     */
    @Test
    void datePrefixReturnsTheTenCharacterIsoDate() {
        String prefix = TimestampFormatter.datePrefix(FIXTURE_TIMESTAMP);

        assertEquals(FIXTURE_DATE_PREFIX, prefix,
                "the prefix must be the leading ten characters of the contract value verbatim");

        assertEquals(DATE_PREFIX_LENGTH, prefix.length(),
                "the prefix width is the ten bytes both reference consumers read, so it must not "
                        + "vary with the content of the value");
    }

    /**
     * Confirms that the ten-character prefix is also readable as a typed date.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing date as a test failure
     * showing both values.</p>
     *
     * <p>Assumptions: the expectation is constructed from its three components, so it does not depend
     * on the same slicing the method under test performs. This is the typed counterpart of the
     * character slice the reference consumers take, and it is asserted separately because a caller that
     * needs to compare dates arithmetically rather than lexicographically uses this entry point
     * instead of the string one.</p>
     */
    @Test
    void toLocalDateReadsTheTenCharacterPrefixAsADate() {
        assertEquals(FIXTURE_LOCAL_DATE, TimestampFormatter.toLocalDate(FIXTURE_TIMESTAMP),
                "the typed reader must yield the same date the ten-character prefix denotes");
    }

    /**
     * Confirms that the prefix readers look only at the first ten characters and are indifferent to
     * what follows them.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing result, or an exception
     * escaping where none is expected, as a test failure.</p>
     *
     * <p>Assumptions: the scope of the prefix readers is deliberately narrower than the scope of the
     * full parser, and asserting that narrowness positively is what stops it being tightened by
     * accident. The value used here is twenty-six characters carrying the ISO {@code T} in place of the
     * required space, so it is a value the full parser refuses; its first ten characters are
     * nonetheless a valid ISO date, and both prefix readers therefore succeed on it. A maintainer who
     * assumed these readers validated the whole value would read that as a defect and would add a full
     * parse, which would break the very consumers the prefix exists for: the reference filter at
     * {@code app/jcl/TRANREPT.jcl} line 42 reads ten bytes and never looks at the remaining sixteen.
     * The companion expectation that the full parser does refuse this same value is asserted separately
     * below, so the pair records both halves of the boundary.</p>
     */
    @Test
    void datePrefixIsIndifferentToContentPastTheTenthCharacter() {
        String isoSeparatorVariant = "2022-06-10T19:27:53.000000";

        assertEquals(FIXTURE_DATE_PREFIX,
                TimestampFormatter.datePrefix(isoSeparatorVariant),
                "a defect beyond the tenth character must not affect the sliced prefix");

        assertEquals(FIXTURE_LOCAL_DATE,
                TimestampFormatter.toLocalDate(isoSeparatorVariant),
                "the typed reader interprets the prefix only, so it too is unaffected by a defect "
                        + "outside it");
    }

    /**
     * Confirms that a value of the correct width carrying the wrong punctuation is refused as a content
     * failure.
     *
     * <p>Takes no parameters and returns no value. Each expected failure is a
     * {@link DateTimeParseException}, raised because the value is exactly the contract width but does
     * not match the contract pattern. The exceptions are raised inside the assertion lambdas and
     * captured there, so this method itself completes normally; JUnit reports a missing or differently
     * typed exception as a test failure.</p>
     *
     * <p>Assumptions: every value below is exactly twenty-six characters, so the width check cannot
     * reject any of them and only the pattern can. That is why they are grouped: they are the cases in
     * which a length-only guard would let a wrong value through. The three chosen are the three that
     * actually occur -- the ISO {@code T} separator, which is what a caller producing ISO 8601 text
     * emits; the reference baseline's own native Db2 form with hyphens and dots inside the time, which
     * is what a value carried across unconverted looks like; and a comma before the fraction, which is
     * what a locale-sensitive formatter emits where the contract requires a period.</p>
     */
    @Test
    void wrongPunctuationAtTheContractWidthIsAContentFailure() {
        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2022-06-10T19:27:53.000000"),
                "the contract places a space where ISO 8601 places a T, so the ISO form must be "
                        + "refused rather than silently accepted");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2022-06-10-19.27.53.000000"),
                "the reference baseline's native Db2 punctuation is not the canonical target form, "
                        + "so a value carried across unconverted must be refused");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2022-06-10 19:27:53,000000"),
                "the fractional separator is a period in every locale under this contract, so a "
                        + "comma must be refused");
    }

    /**
     * Confirms that a well-punctuated value naming a date or time the calendar does not have is refused
     * rather than adjusted into a neighbouring one.
     *
     * <p>Takes no parameters and returns no value. Each expected failure is a
     * {@link DateTimeParseException}, raised by strict resolution when a syntactically well-formed
     * value denotes an impossible calendar point. The exceptions are raised inside the assertion
     * lambdas and captured there, so this method itself completes normally; JUnit reports a value that
     * was accepted instead of refused as a test failure.</p>
     *
     * <p>Assumptions: these are the cases a width check and a punctuation check both pass, and they are
     * the reason the production resolver is strict. Under the platform's default resolution each of
     * them would be accepted and quietly moved: the twenty-ninth of February in a common year becomes
     * the twenty-eighth, a thirty-first day in a thirty-day month becomes the thirtieth, and an hour of
     * twenty-four becomes midnight of the FOLLOWING day. That last one is the most damaging, because it
     * changes the ten-character date prefix, and the prefix is the part every reference consumer reads
     * -- so an impossible hour would shift a record into a different day of the report range filtered
     * at {@code app/jcl/TRANREPT.jcl} lines 47 and 48. A month beyond twelve is included because it is
     * refused by default resolution too, and asserting it records the boundary rather than leaving a
     * reader to assume where it lies.</p>
     */
    @Test
    void impossibleCalendarValuesAreRefusedRatherThanAdjusted() {
        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2023-02-29 00:00:00.000000"),
                "2023 is not a leap year, so its twenty-ninth of February must be refused and not "
                        + "clamped to the twenty-eighth");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2022-04-31 12:00:00.000000"),
                "April has thirty days, so a thirty-first must be refused and not clamped to the "
                        + "thirtieth");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2022-06-10 24:00:00.000000"),
                "an hour of twenty-four must be refused, because rolling it to the next day would "
                        + "change the ten-character prefix every reference consumer reads");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse("2022-13-10 19:27:53.000000"),
                "a thirteenth month must be refused");

        // WHY : Assumptions: the typed prefix reader is asserted on the impossible-date case as well,
        //       because it resolves the prefix through a SECOND parser rather than through the one the
        //       expectations above exercise. A defect inside the first ten characters is the only kind
        //       both readers can see, which is why this value carries its defect there rather than in
        //       the time portion.
        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.toLocalDate("2023-02-29 00:00:00.000000"),
                "the typed prefix reader must refuse an impossible date just as the full parser does");
    }

    /**
     * Confirms that a genuine leap day carrying the maximal fraction is accepted and round trips
     * exactly.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a rejected valid value, or a differing
     * re-rendered value, as a test failure.</p>
     *
     * <p>Assumptions: an acceptance case is asserted in the same file as the refusal cases above
     * because a refusal suite on its own cannot distinguish a correctly strict parser from one that
     * refuses everything. This value is chosen to be maximally awkward while remaining entirely valid:
     * 2024 is a leap year so the twenty-ninth of February exists, the time is the last second of the
     * day, and the fraction occupies all six positions at their highest value.</p>
     *
     * <p>Trade-offs: the fraction here is {@code 999999} microseconds exactly, so this method fixes
     * that all six positions survive a round trip at their maximum and does NOT discriminate rounding
     * from truncation -- a whole microsecond has nothing below it to dispose of either way. The vector
     * that draws that distinction carries a seventh digit and is asserted in
     * normalizeTruncatesTheSeventhFractionalDigitRatherThanRounding above, on this same day boundary.
     * The two are kept apart so that a failure here means the round trip broke and a failure there
     * means the reduction did.</p>
     */
    @Test
    void theValidLeapDayAndMaximalFractionRoundTrip() {
        String validLeapDay = "2024-02-29 23:59:59.999999";

        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 999_999_000),
                TimestampFormatter.parse(validLeapDay),
                "a leap day in a genuine leap year, at the last microsecond of the day, is a valid "
                        + "contract value and must be accepted");

        assertEquals(validLeapDay,
                TimestampFormatter.format(TimestampFormatter.parse(validLeapDay)),
                "the maximal fraction must survive the round trip without carrying into the next "
                        + "second");
    }

    /**
     * Confirms that zero padding holds the rendered width at twenty-six for single-digit components,
     * for midnight and for the last instant of a year.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a differing rendering as a test
     * failure showing both strings.</p>
     *
     * <p>Assumptions: the width is only a contract while every component is padded to its declared
     * span, so the padding is exercised on the values where an unpadded formatter would shorten the
     * result. A single-digit month, day, hour, minute and second together would cost five characters
     * unpadded, taking a twenty-six character value to twenty-one; and because a shorter value assigned
     * to a {@code PIC X(26)} field is padded or truncated by the receiving layout rather than rejected,
     * such a value would corrupt a record silently instead of failing it. Midnight and the final second
     * of a year are included as the two boundaries at which components reach their extreme values in
     * opposite directions.</p>
     */
    @Test
    void zeroPaddingHoldsTheRenderedWidthAtTwentySix() {
        assertEquals("2022-01-02 03:04:05.000000",
                TimestampFormatter.format(LocalDateTime.of(2022, 1, 2, 3, 4, 5, 0)),
                "a single-digit month, day, hour, minute and second must each be zero padded");

        assertEquals("2022-06-10 00:00:00.000000",
                TimestampFormatter.format(LocalDateTime.of(2022, 6, 10, 0, 0, 0, 0)),
                "midnight must render as all-zero time components rather than being omitted");

        assertEquals("2022-12-31 23:59:59.999999",
                TimestampFormatter.format(
                        LocalDateTime.of(2022, 12, 31, 23, 59, 59, 999_999_000)),
                "the last microsecond of a year must render at the contract width");
    }

    /**
     * Confirms that the two published year bounds are the years the contract width can carry, that
     * both are accepted, and that the year immediately outside each is refused on both sides of the
     * boundary.
     *
     * <p>Takes no parameters and returns no value. The refusals expected of the rendering entry point
     * are {@link IllegalArgumentException}, raised by its year guard before anything is rendered, and
     * the refusals expected of the reading entry points are {@link DateTimeParseException}, because
     * there the offending year arrived inside the caller's data rather than as its argument. Every
     * expected failure is raised inside an assertion lambda and captured there, so this method itself
     * completes normally; JUnit reports a missing, differently typed or wrongly valued result as a test
     * failure.</p>
     *
     * <p>Assumptions: the year range is a consequence of the twenty-six character width and not a
     * policy ceiling, so the two constants are asserted as values and the four boundary years are
     * exercised through the entry points rather than against the constants alone. The measurements
     * that fix them were taken on JDK 21.0.11: year 999 renders {@code 0999-12-31 23:59:59.999999},
     * which is exactly twenty-six characters, and year 10000 renders
     * {@code +10000-01-01 00:00:00.000000}, which is twenty-eight and gains a sign.</p>
     *
     * <p>Trade-offs: the year case cannot be folded into the wrong-width case, and that is why it is
     * asserted separately at the cost of a method of its own. Below the range the rendering is still
     * twenty-six characters, so a width check sees nothing wrong with it; above the range a value
     * assigned to a {@code PIC X(26)} field is truncated by the receiving layout rather than rejected,
     * which turns a refusable value into a silently corrupted record. Only a year check separates the
     * two, and only these vectors demonstrate that it exists.</p>
     *
     * <p>Alternatives Considered: asserting the year rule on the rendering entry point alone was
     * evaluated and rejected. The class would then be able to READ a year it refuses to WRITE, and a
     * record carrying {@code 0999} would pass through the reader into a database column that the
     * writer could never have produced -- an inconsistency between two code paths of one contract,
     * which is the hardest kind of defect to attribute to either. The slice-only entry point is
     * deliberately excluded from the rule and is asserted here as such, because it returns ten
     * characters without interpreting them and its own contract is documented as indifferent to
     * content.</p>
     */
    @Test
    void supportedYearBoundsAreTheYearsTheContractWidthCanCarry() {
        assertEquals(1000, TimestampFormatter.MIN_SUPPORTED_YEAR,
                "the earliest year the four-character year field can carry without padding "
                        + "ambiguity is one thousand");

        assertEquals(9999, TimestampFormatter.MAX_SUPPORTED_YEAR,
                "the latest year the four-character year field can carry is nine thousand nine "
                        + "hundred and ninety nine");

        // WHY : Assumptions: both bounds are asserted as ACCEPTED before either neighbour is asserted
        //       as refused. A refusal suite on its own cannot tell a correctly bounded implementation
        //       from one that refuses everything near the edge, and an off-by-one that excluded the
        //       bound itself would be invisible without these two.
        assertEquals("1000-01-01 00:00:00.000000",
                TimestampFormatter.format(LocalDateTime.of(1000, 1, 1, 0, 0, 0, 0)),
                "the earliest supported year must render, and must render zero padded");

        assertEquals(TimestampFormatter.TIMESTAMP_LENGTH,
                TimestampFormatter.format(LocalDateTime.of(1000, 1, 1, 0, 0, 0, 0)).length(),
                "the accepted lower bound must occupy exactly the contract width");

        assertEquals("9999-12-31 23:59:59.999999",
                TimestampFormatter.format(
                        LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000)),
                "the latest supported year must render at its own last microsecond");

        assertEquals(TimestampFormatter.TIMESTAMP_LENGTH,
                TimestampFormatter.format(
                        LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000)).length(),
                "the accepted upper bound must occupy exactly the contract width");

        // WHY : Assumptions: year 999 is the vector that proves the check is on the YEAR and not on
        //       the rendered length, because its rendering was measured to be exactly twenty-six
        //       characters. Were the guard removed, this value would pass every width expectation in
        //       this file and escape as a contract value the reader also has to accept.
        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.format(
                        LocalDateTime.of(999, 12, 31, 23, 59, 59, 999_999_000)),
                "one year below the lower bound must be refused even though it renders at the "
                        + "contract width");

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.format(LocalDateTime.of(10000, 1, 1, 0, 0, 0, 0)),
                "one year above the upper bound must be refused, because it widens the rendering "
                        + "beyond the field it is assigned to");

        // WHY : Assumptions: this value is exactly twenty-six characters and parses cleanly under the
        //       production pattern -- measured to yield year 999 -- so the width guard and the pattern
        //       both pass it and only the contract's own range check can refuse it. It is asserted
        //       through the read side as a DIFFERENT exception family from the write side above,
        //       because that is the distinction the class draws between a defect in a caller's data
        //       and a defect in its arguments.
        String yearBelowLowerBound = "0999-06-10 19:27:53.000000";

        assertEquals(TimestampFormatter.TIMESTAMP_LENGTH, yearBelowLowerBound.length(),
                "the read-side vector must be the contract width, or it would test the width guard "
                        + "instead of the year guard");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse(yearBelowLowerBound),
                "the parser must refuse a year the renderer cannot write, so the class cannot read a "
                        + "value it is unable to produce");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.toLocalDate(yearBelowLowerBound),
                "the typed prefix reader interprets the year and must apply the same range");

        // WHY : Trade-offs: the slice is asserted to SUCCEED on the same value, which looks like an
        //       inconsistency and is a documented one. That entry point returns ten characters and
        //       interprets none of them, so applying the year range there would make a layout
        //       operation fail on content; the range belongs on the two entry points that turn text
        //       into a date. Asserting the asymmetry is what stops a later reader from closing it as
        //       an apparent oversight.
        assertEquals("0999-06-10", TimestampFormatter.datePrefix(yearBelowLowerBound),
                "the width-only slice must remain indifferent to a year the interpreting readers "
                        + "refuse");
    }

    /**
     * Confirms that a value of the wrong width is refused as a structural failure, through a different
     * exception type from a content failure.
     *
     * <p>Takes no parameters and returns no value. Each expected failure is an
     * {@link IllegalArgumentException}, raised by the width guard before any parsing is attempted, and
     * it is deliberately a different type from the {@link DateTimeParseException} that a
     * correctly sized but malformed value produces. The exceptions are raised inside the assertion
     * lambdas and captured there, so this method itself completes normally; JUnit reports a missing or
     * differently typed exception as a test failure.</p>
     *
     * <p>Assumptions: the two exception families are asserted as distinct because they diagnose
     * different defects, and collapsing them would discard the more useful half of the diagnosis. At
     * this width the usual cause of a wrong length is a field read at the wrong offset inside a
     * 350-byte record, which is a fault in the caller's record layout; a right-length value that will
     * not parse is a fault in the caller's data. The two vectors are the fixture value one character
     * short and one character long, which are the two smallest possible layout errors and therefore the
     * two most likely to survive review.</p>
     *
     * <p>Trade-offs: all three string entry points are asserted rather than only the parser. They share
     * one width guard, so asserting one would usually suffice, and the compromise accepted here is a
     * little repetition in exchange for the guard's placement on each entry point being a stated
     * expectation rather than an inference from the implementation.</p>
     */
    @Test
    void wrongWidthIsAStructuralFailureDistinctFromAContentFailure() {
        String oneCharacterShort = FIXTURE_TIMESTAMP.substring(0, 25);
        String oneCharacterLong = FIXTURE_TIMESTAMP + "0";

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.parse(oneCharacterShort),
                "a value one character short is a layout error and must be refused before parsing");

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.parse(oneCharacterLong),
                "a value one character long is a layout error and must be refused before parsing");

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.datePrefix(oneCharacterShort),
                "the string prefix reader validates the whole width, not merely the ten characters "
                        + "it returns");

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.toLocalDate(oneCharacterLong),
                "the typed prefix reader validates the whole width as well");
    }

    /**
     * Confirms that the injected compact batch business date is refused by every timestamp entry point
     * that reads text.
     *
     * <p>Takes no parameters and returns no value. Each expected failure is an
     * {@link IllegalArgumentException}, raised by the width guard because the value is ten characters
     * where the contract requires exactly twenty-six. The exceptions are raised inside the assertion
     * lambdas and captured there, so this method itself completes normally; JUnit reports an accepted
     * value as a test failure.</p>
     *
     * <p>Assumptions: this value is a real production parameter and not a malformed timestamp. It is
     * the {@code PARM} of {@code app/jcl/INTCALC.jcl} line 22, which runs
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}. Its ten characters are a compact,
     * unpunctuated year, month, day and hour, so although it shares its length with the ISO date prefix
     * the two are different formats carrying different fields: the prefix is punctuated and stops at
     * the day, while this value is unpunctuated and includes an hour. Matching lengths are therefore
     * pure coincidence, and this expectation exists so that the coincidence is never mistaken for
     * compatibility -- an entry point that accepted it would read {@code 2022071800} as a date and
     * would have to invent a punctuation scheme to do so.</p>
     *
     * <p>Alternatives Considered: adding an overload that accepted the compact form was evaluated and
     * rejected. The injected business date and the processing timestamp are deliberately separate time
     * sources in the reference baseline, and a class that read both would let a caller pass one where
     * the other was meant, which is precisely the substitution these expectations exclude.</p>
     */
    @Test
    void compactBusinessDateParameterIsNotTimestampInput() {
        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.parse(COMPACT_BUSINESS_DATE),
                "the injected compact business date is not a contract timestamp and must be refused");

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.datePrefix(COMPACT_BUSINESS_DATE),
                "sharing the ISO prefix's length does not make the compact form sliceable as one");

        assertThrows(IllegalArgumentException.class,
                () -> TimestampFormatter.toLocalDate(COMPACT_BUSINESS_DATE),
                "the typed reader must refuse the compact form rather than interpreting it as a date");
    }

    /**
     * Confirms that twenty-six blank characters are not a readable timestamp, yet are still sliced at
     * full width by the string prefix reader.
     *
     * <p>Takes no parameters and returns no value. The two expected failures are
     * {@link DateTimeParseException}, raised because blank text of the correct width satisfies the width
     * guard and then fails to match the pattern. Those exceptions are raised inside the assertion
     * lambdas and captured there, so this method itself completes normally; JUnit reports a missing or
     * differently typed exception, or a differing slice, as a test failure.</p>
     *
     * <p>Assumptions: blank is an observed state in the reference data rather than a hypothetical one.
     * Each of the five 350-byte records of
     * {@code tests/fixtures/export/happy_path/trandata.txt} carries twenty-six spaces at one-based
     * columns 305 to 330, which is how a fixed-width record says a field is not yet stamped. Note the
     * limits of what that fixture establishes: it fixes the WIDTH and the placement of a blank field,
     * and it establishes nothing at all about punctuation, since it contains no punctuation to observe.
     * The two halves of this expectation are therefore different in kind -- reading a blank value is a
     * content failure, while slicing one is a layout operation that must preserve exactly what it was
     * given.</p>
     *
     * <p>Alternatives Considered: treating blank text as equivalent to an absent value, by trimming it
     * and returning a null or empty result, was evaluated and rejected. The two mean different things
     * and are reported through different exception families for that reason: blank is a DATUM that a
     * fixed-width record legitimately contains, whereas an absent reference is a defect in a Java
     * caller. Collapsing them would make a caller unable to tell an unstamped record from its own bug,
     * and it would silently turn the width-preserving slice below into a variable-width one.</p>
     */
    @Test
    void blankFixedWidthTextIsNotATimestampYetKeepsItsWidth() {
        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.parse(BLANK_TIMESTAMP),
                "blank text is the correct width, so only the pattern can reject it, and it must");

        assertThrows(DateTimeParseException.class,
                () -> TimestampFormatter.toLocalDate(BLANK_TIMESTAMP),
                "a blank prefix is not an ISO date and must not resolve to one");

        // WHY : Assumptions: the expected slice is built from the prefix length constant rather than
        //       written as a literal run of ten spaces, so that a reader can see it is ten and a
        //       reviewer does not have to count spaces in a string literal to check it.
        assertEquals(" ".repeat(DATE_PREFIX_LENGTH),
                TimestampFormatter.datePrefix(BLANK_TIMESTAMP),
                "the string prefix reader is a fixed-width slice, so a blank field must yield blanks "
                        + "of the prefix width rather than being trimmed away");
    }

    /**
     * Confirms that an absent argument is refused at every entry point.
     *
     * <p>Takes no parameters and returns no value. Each expected failure is a
     * {@link NullPointerException}, raised by the mandatory-argument guard on the entry point named in
     * the accompanying message. The exceptions are raised inside the assertion lambdas and captured
     * there, so this method itself completes normally; JUnit reports a missing or differently typed
     * exception as a test failure.</p>
     *
     * <p>Assumptions: an absent reference is a defect in the calling code and not a value the contract
     * has a rendering for, which is why it is reported through a different family from both the blank
     * datum above and the wrong-width layout error. The clock entry point is included deliberately: a
     * null clock most often means a caller expected an ambient default, and this class provides none by
     * design, so the refusal is what points them at that absence instead of at a formatting fault.</p>
     *
     * <p>Trade-offs: all six public entry points are covered by this one method rather than by six.
     * They fail for one reason and through one family, so six methods would report the same defect six
     * ways, and the compromise accepted is that a failure names the method in its message instead of in
     * the report heading. What the coverage buys is that adding a seventh entry point without a
     * mandatory-argument guard fails here, which is exactly how the two reducing entry points came to
     * be listed: neither could reach an assertion in this file before it named them.</p>
     */
    @Test
    void absentArgumentsAreRejectedAsCallerDefects() {
        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.format(null),
                "there is no rendering of an absent timestamp");

        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.formatNow(null),
                "there is deliberately no ambient clock to fall back to");

        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.normalize(null),
                "there is no reduced form of an absent timestamp");

        // WHY : Assumptions: the reducing clock reader is asserted separately from the rendering one
        //       even though both take a clock, because each validates its own argument before
        //       delegating. Were the guard removed from this one, the null would still be caught one
        //       call depth below and the exception would name the delegate's parameter rather than the
        //       entry point the caller wrote, which is the diagnosis this expectation protects.
        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.normalizeNow(null),
                "the reducing clock reader has deliberately no ambient clock to fall back to either");

        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.parse(null),
                "an absent value is a caller defect and not an unreadable datum");

        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.datePrefix(null),
                "an absent value cannot be sliced");

        assertThrows(NullPointerException.class,
                () -> TimestampFormatter.toLocalDate(null),
                "an absent value cannot be interpreted as a date");
    }

    /**
     * Confirms that the shared formatter is a non-extensible, non-instantiable static utility.
     *
     * <p>Takes no parameters and returns no value. JUnit reports a class that is not final, that
     * declares more than one constructor, or whose constructor is not private, as a test failure; an
     * unexpected reflection failure is reported through the surrounding {@code assertDoesNotThrow}
     * rather than as a checked exception escaping this method.</p>
     *
     * <p>Assumptions: the shape is part of the contract and not an implementation detail. This class
     * holds no state and is the single source of one wire format, so an instance would communicate a
     * lifecycle that does not exist and a subclass could reopen a decision the whole shared kernel
     * depends on being closed. Both halves are asserted because they fail independently: removing
     * {@code final} permits a subclass, while widening the constructor permits an instance.</p>
     *
     * <p>Trade-offs: the constructor is located and its modifiers are read, but it is never made
     * accessible and never invoked. Invoking it would be the only way to assert what its body does,
     * and it would also be the only thing in this file that defeated the very encapsulation being
     * asserted; reading the modifier is sufficient, because the modifier is what the language enforces.
     * The reflective lookup is wrapped in an assertion that no exception is thrown so that this method
     * needs no {@code throws} clause for a checked reflection exception that is unrelated to the
     * property under test.</p>
     */
    @Test
    void theUtilityIsFinalAndItsConstructorIsPrivate() {
        assertTrue(Modifier.isFinal(TimestampFormatter.class.getModifiers()),
                "the shared formatter must be final so that no subclass can reopen its rendering");

        assertEquals(1, TimestampFormatter.class.getDeclaredConstructors().length,
                "exactly one constructor must exist, so that no second entry point can be added "
                        + "without this expectation noticing");

        Constructor<TimestampFormatter> declared = assertDoesNotThrow(
                () -> TimestampFormatter.class.getDeclaredConstructor(),
                "the utility must declare the zero-argument constructor this expectation inspects");

        assertTrue(Modifier.isPrivate(declared.getModifiers()),
                "the constructor must be private, which is where the language enforces that this "
                        + "stateless utility is never instantiated");
    }
}
