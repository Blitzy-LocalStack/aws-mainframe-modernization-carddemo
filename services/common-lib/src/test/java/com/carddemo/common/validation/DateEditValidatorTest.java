package com.carddemo.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.validation.DateEditValidator.DateEditResult;
import com.carddemo.common.validation.DateEditValidator.FeedbackCode;
import com.carddemo.common.validation.DateEditValidator.LanguageEnvironmentResult;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the transcribed date-edit rules, the verbatim baseline message text and the
 * Language-Environment evaluation path.
 *
 * <p>Assumptions: this class encodes message text and boundary rules copied character for character
 * from the reference baseline, so the value of a test here is that it fails when a refactor alters one
 * of them. Every message assertion therefore compares against the published constant AND, where the
 * baseline line is known, states that line in the test documentation so a reader can check the
 * constant itself against the source rather than only against this file.</p>
 *
 * <p>Assumptions: the field label passed to {@code validate} is a caller-supplied screen label and
 * plays no part in any rule, so one fixed label is used throughout. Varying it would test the label
 * plumbing rather than the rules, which the aggregate-message assertions already cover.</p>
 *
 * <p>Trade-offs: the two entry points take their arguments in DIFFERENT orders --
 * {@code validate(fieldLabel, date)} is label first, while
 * {@code evaluateWithLanguageEnvironment(date, mask)} is date first. That asymmetry is a genuine trap
 * for a caller, so it is asserted explicitly rather than merely observed: a test below passes a mask
 * where a date belongs and confirms the result is a refusal rather than an accidental pass.</p>
 */
class DateEditValidatorTest {

    /** The screen label the fixtures validate under, standing in for any caller-supplied label. */
    private static final String LABEL = "Date of Birth";

    /** A business date injected rather than read from the clock, so every run is reproducible. */
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2022, 7, 18);

    /**
     * Verifies the leap-year rule as the baseline expresses it, including the century exception.
     */
    @Nested
    @DisplayName("the leap-year rule")
    class LeapYearRule {

        /**
         * Confirms the divisible-by-400, divisible-by-4 and divisible-by-100 branches each resolve.
         *
         * <p>Assumptions: 1900 and 2100 are the cases that separate a correct implementation from one
         * that only tests divisibility by four, and 1600 and 2000 are the cases that separate a correct
         * implementation from one that excludes every century year. All four are asserted because a
         * rule with three branches cannot be covered by fewer.</p>
         *
         * @param year the year under test
         * @param expected whether the year is a leap year
         */
        @ParameterizedTest
        @CsvSource({
            "1600, true", "1900, false", "2000, true", "2023, false",
            "2024, true", "2100, false", "2400, true", "2200, false",
        })
        @DisplayName("resolves the divisible-by-400, by-100 and by-4 branches")
        void resolvesEveryBranch(int year, boolean expected) {
            assertThat(DateEditValidator.isLeapYear(year)).isEqualTo(expected);
        }

        /**
         * Confirms the divisors the rule is built from are the published constants.
         */
        @Test
        @DisplayName("is built from the published divisors")
        void isBuiltFromPublishedDivisors() {
            assertThat(DateEditValidator.LEAP_CENTURY_DIVISOR).isEqualTo(400);
            assertThat(DateEditValidator.LEAP_ORDINARY_DIVISOR).isEqualTo(4);
        }
    }

    /**
     * Verifies acceptance and the flag state a valid date produces.
     */
    @Nested
    @DisplayName("a valid date")
    class ValidDate {

        /**
         * Confirms a date inside the accepted century domain validates with all three flags valid.
         *
         * @param date the date under test in the declared mask form
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "2022-07-18", "2000-02-29", "2024-02-29", "1900-01-01",
            "2099-12-31", "1999-12-31", "2020-01-31", "2020-04-30",
        })
        @DisplayName("is accepted with year, month and day all marked valid")
        void isAcceptedWithEveryComponentValid(String date) {
            DateEditResult result = DateEditValidator.validate(LABEL, date);

            assertThat(result.isValid()).isTrue();
            assertThat(result.inputError()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.year()).isEqualTo(FieldValidationFlag.VALID);
            assertThat(result.month()).isEqualTo(FieldValidationFlag.VALID);
            assertThat(result.day()).isEqualTo(FieldValidationFlag.VALID);
        }

        /**
         * Confirms the aggregate flag codes of an accepted date are the published valid triple.
         *
         * <p>Assumptions: the aggregate is the {@code CSUTLDWY} flag area the baseline returns, and the
         * valid state is three low-value characters rather than three printable ones. Asserting the
         * published constant rather than a literal keeps this test from becoming the second place the
         * encoding is written down.</p>
         */
        @Test
        @DisplayName("aggregates to the published valid flag codes")
        void aggregatesToPublishedValidCodes() {
            DateEditResult result = DateEditValidator.validate(LABEL, "2022-07-18");

            assertThat(result.aggregateFlagCodes()).isEqualTo(DateEditValidator.AGGREGATE_VALID_CODES);
        }
    }

    /**
     * Verifies each rejection carries the baseline's own message text unchanged.
     */
    @Nested
    @DisplayName("an invalid month or day combination")
    class InvalidCombination {

        /**
         * Confirms the thirty-one-day refusal carries the text of {@code CSUTLDPY} line 221.
         *
         * @param date a date naming day 31 in a month that has thirty
         */
        @ParameterizedTest
        @ValueSource(strings = {"2022-04-31", "2022-06-31", "2022-09-31", "2022-11-31"})
        @DisplayName("day 31 in a thirty-day month carries the verbatim 31-day message")
        void thirtyOneDayRefusalIsVerbatim(String date) {
            DateEditResult result = DateEditValidator.validate(LABEL, date);

            assertThat(result.isValid()).isFalse();
            assertThat(result.message()).contains(DateEditValidator.MSG_NO_31_DAYS);
            assertThat(DateEditValidator.MSG_NO_31_DAYS)
                .isEqualTo(":Cannot have 31 days in this month.");
        }

        /**
         * Confirms the thirty-day refusal carries the text of {@code CSUTLDPY} line 236.
         */
        @Test
        @DisplayName("day 30 in February carries the verbatim 30-day message")
        void thirtyDayRefusalIsVerbatim() {
            DateEditResult result = DateEditValidator.validate(LABEL, "2022-02-30");

            assertThat(result.isValid()).isFalse();
            assertThat(result.message()).contains(DateEditValidator.MSG_NO_30_DAYS);
            assertThat(DateEditValidator.MSG_NO_30_DAYS)
                .isEqualTo(":Cannot have 30 days in this month.");
        }

        /**
         * Confirms the leap-year refusal carries the text of {@code CSUTLDPY} line 266.
         *
         * <p>Assumptions: this message is a single string with no space after the first sentence's full
         * stop, which reads like a typographical slip and is not one -- it is what the baseline
         * declares. Asserting the exact literal is the only way that survives a well-meant tidy-up.</p>
         */
        @Test
        @DisplayName("29 February in a common year carries the verbatim leap-year message")
        void leapYearRefusalIsVerbatim() {
            DateEditResult result = DateEditValidator.validate(LABEL, "2023-02-29");

            assertThat(result.isValid()).isFalse();
            assertThat(result.message()).contains(DateEditValidator.MSG_NOT_LEAP_YEAR);
            assertThat(DateEditValidator.MSG_NOT_LEAP_YEAR)
                .isEqualTo(":Not a leap year.Cannot have 29 days in this month.");
        }

        /**
         * Confirms a month outside one to twelve is refused with the range message.
         *
         * @param date a date naming a month outside the valid range
         */
        @ParameterizedTest
        @ValueSource(strings = {"2022-00-15", "2022-13-15", "2022-99-15"})
        @DisplayName("a month outside 1 to 12 carries the month-range message")
        void monthOutsideRangeIsRefused(String date) {
            DateEditResult result = DateEditValidator.validate(LABEL, date);

            assertThat(result.isValid()).isFalse();
            assertThat(result.message()).contains(DateEditValidator.MSG_MONTH_RANGE);
        }

        /**
         * Confirms a day outside one to thirty-one is refused with the range message.
         *
         * @param date a date naming a day outside the valid range
         */
        @ParameterizedTest
        @ValueSource(strings = {"2022-07-00", "2022-07-32", "2022-07-99"})
        @DisplayName("a day outside 1 to 31 carries the day-range message")
        void dayOutsideRangeIsRefused(String date) {
            DateEditResult result = DateEditValidator.validate(LABEL, date);

            assertThat(result.isValid()).isFalse();
            assertThat(result.message()).contains(DateEditValidator.MSG_DAY_RANGE);
        }
    }

    /**
     * Verifies the century rule the screen edit applies, which is narrower than the calendar.
     */
    @Nested
    @DisplayName("the century rule")
    class CenturyRule {

        /**
         * Confirms only the two centuries the baseline admits are accepted.
         *
         * <p>Assumptions: the rule at {@code CSUTLDPY} lines 68 to 71 admits a year whose leading two
         * digits are 19 or 20 and nothing else. This is narrower than the Gregorian calendar the
         * Language-Environment path uses, and the two are deliberately different surfaces: the screen
         * edit is what a user's keystrokes meet, and it refuses a year no account record can hold.</p>
         *
         * @param date a date whose century falls outside the admitted pair
         */
        @ParameterizedTest
        @ValueSource(strings = {"1899-12-31", "2100-01-01", "1800-06-15", "0001-01-01", "9999-12-31"})
        @DisplayName("refuses a year outside 19xx and 20xx")
        void refusesYearOutsideTheAdmittedCenturies(String date) {
            DateEditResult result = DateEditValidator.validate(LABEL, date);

            assertThat(result.isValid()).isFalse();
        }

        /**
         * Confirms both boundaries of the admitted pair are inside it.
         */
        @Test
        @DisplayName("accepts the bottom of 19xx and the top of 20xx")
        void acceptsBothBoundaries() {
            assertThat(DateEditValidator.validate(LABEL, "1900-01-01").isValid()).isTrue();
            assertThat(DateEditValidator.validate(LABEL, "2099-12-31").isValid()).isTrue();
        }

        /**
         * Confirms the two admitted centuries are the published constants.
         */
        @Test
        @DisplayName("is expressed by the published century constants")
        void isExpressedByPublishedConstants() {
            assertThat(DateEditValidator.LAST_CENTURY).isEqualTo(19);
            assertThat(DateEditValidator.THIS_CENTURY).isEqualTo(20);
        }
    }

    /**
     * Verifies absent, blank and structurally wrong input.
     */
    @Nested
    @DisplayName("absent, blank or malformed input")
    class MalformedInput {

        /**
         * Confirms an absent or blank date is refused rather than treated as acceptable.
         *
         * <p>Assumptions: blankness here means SPACES or empty, matching the baseline's own absent
         * markers, so only space-padded and empty forms belong in this list. A tab is content rather
         * than blank and is covered by the width test below, which is where its louder treatment is
         * documented.</p>
         *
         * @param date the degenerate input under test
         */
        @ParameterizedTest
        @ValueSource(strings = {"", " ", "          ", "----------", "0000-00-00"})
        @DisplayName("is refused rather than accepted")
        void isRefused(String date) {
            assertThat(DateEditValidator.validate(LABEL, date).isValid()).isFalse();
        }

        /**
         * Confirms a null date is rejected by the entry point rather than silently accepted.
         *
         * <p>Assumptions: whichever way the entry point expresses the rejection -- a refusing result or
         * a thrown argument failure -- the outcome a caller must never see is acceptance. This asserts
         * the negative rather than the mechanism, so it stays correct if the mechanism is tightened.</p>
         */
        @Test
        @DisplayName("a null date never yields an accepted result")
        void nullDateIsNeverAccepted() {
            boolean acceptedOrThrew;
            try {
                acceptedOrThrew = DateEditValidator.validate(LABEL, null).isValid();
            } catch (RuntimeException rejected) {
                acceptedOrThrew = false;
            }

            assertThat(acceptedOrThrew).isFalse();
        }

        /**
         * Confirms a date carrying content at any width other than the two accepted ones is refused
         * loudly, by a thrown argument failure naming the offending width.
         *
         * <p>Assumptions: nine characters is the case worth naming, because it is one short of the
         * hyphenated mask and a length-tolerant implementation would parse it by position and silently
         * shift the day. The refusal is a THROWN {@link IllegalArgumentException} rather than a
         * refusing result, and that distinction is deliberate on the production side: a result carries
         * per-field flags for a date whose components could be read, whereas a value of the wrong width
         * has no components to flag. Reporting it as caller input rather than as a field error is what
         * lets the error model answer it as a client failure -- an argument failure maps to HTTP 400
         * with a field-error array, which is exactly what a malformed request deserves.</p>
         *
         * <p>Assumptions: a tab appears in this list rather than among the blank forms above because
         * the baseline's absent markers are SPACES and low values, so a tab is content of width one.
         * That is the faithful reading, and the loud refusal is the safe one.</p>
         *
         * @param date a date whose width is neither of the two the contract accepts
         */
        @ParameterizedTest
        @ValueSource(strings = {"2022-07-1", "2022-7-18", "2022-07-188", "\t", "2022-07-18-"})
        @DisplayName("content at an unaccepted width is refused by a thrown argument failure")
        void contentAtAnUnacceptedWidthIsRefusedLoudly(String date) {
            assertThat(date.length())
                .isNotEqualTo(DateEditValidator.MASKED_DATE_LENGTH)
                .isNotEqualTo(DateEditValidator.PACKED_DATE_LENGTH);

            assertThatThrownBy(() -> DateEditValidator.validate(LABEL, date))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width " + date.length());
        }

        /**
         * Confirms the eight-character unseparated baseline form is accepted alongside the hyphenated
         * one, so the two accepted widths are both pinned.
         *
         * <p>Assumptions: the entry point accepts BOTH declared masks -- the hyphenated form a screen
         * shows and the eight-digit form a record stores -- which is why the width test above has to
         * exclude both. Asserting the unseparated form here is what makes that exclusion readable
         * rather than arbitrary, and it pins a capability a reader would not infer from the mask
         * constant used in every other test.</p>
         */
        @Test
        @DisplayName("the eight-character unseparated baseline form is accepted")
        void theUnseparatedBaselineFormIsAccepted() {
            assertThat(DateEditValidator.validate(LABEL, "20220718").isValid()).isTrue();
            assertThat(DateEditValidator.validate(LABEL, "2022-07-18").isValid()).isTrue();
            assertThat(DateEditValidator.validate(LABEL, "20230229").isValid()).isFalse();
        }

        /**
         * Confirms a same-width value whose components are unreadable is a refusing result, not a throw.
         *
         * <p>Assumptions: this is the other side of the width rule. At an accepted width the components
         * exist and can be flagged individually, so the outcome is a result carrying field errors rather
         * than an argument failure. Pairing the two cases is what shows the boundary between them is the
         * width and nothing else.</p>
         */
        @Test
        @DisplayName("an unreadable value at an accepted width is a refusing result, not a throw")
        void unreadableValueAtAnAcceptedWidthIsARefusingResult() {
            DateEditResult result = DateEditValidator.validate(LABEL, "22-07-18");

            assertThat(result.isValid()).isFalse();
            assertThat(result.fieldErrors()).isNotEmpty();
        }

        /**
         * Confirms non-numeric content in a numeric position is refused.
         *
         * @param date a date with a letter where a digit belongs
         */
        @ParameterizedTest
        @ValueSource(strings = {"20X2-07-18", "2022-0A-18", "2022-07-1B", "abcd-ef-gh"})
        @DisplayName("non-numeric content is refused")
        void nonNumericContentIsRefused(String date) {
            assertThat(DateEditValidator.validate(LABEL, date).isValid()).isFalse();
        }
    }

    /**
     * Verifies the date-of-birth rule, whose business date is injected rather than read from a clock.
     */
    @Nested
    @DisplayName("the date-of-birth rule")
    class DateOfBirthRule {

        /**
         * Confirms a date before the business date is accepted.
         */
        @Test
        @DisplayName("accepts a date before the business date")
        void acceptsAPastDate() {
            DateEditResult yesterday =
                DateEditValidator.validateDateOfBirth(LABEL, "2022-07-17", BUSINESS_DATE);
            DateEditResult longPast =
                DateEditValidator.validateDateOfBirth(LABEL, "1970-01-01", BUSINESS_DATE);

            assertThat(yesterday.isValid()).isTrue();
            assertThat(longPast.isValid()).isTrue();
        }

        /**
         * Confirms the same day as the business date is refused, matching {@code CSUTLDPY} line 350.
         *
         * <p>Assumptions: the baseline requires the current date to be strictly GREATER than the edited
         * date, so equality is a refusal and not an acceptance. This is the boundary most likely to be
         * relaxed by a reader who assumes "not in the future" means "today or earlier", so it is
         * asserted on its own rather than folded into the future-date test.</p>
         */
        @Test
        @DisplayName("refuses the same day as the business date")
        void refusesTheSameDay() {
            DateEditResult sameDay =
                DateEditValidator.validateDateOfBirth(LABEL, "2022-07-18", BUSINESS_DATE);

            assertThat(sameDay.isValid()).isFalse();
            assertThat(sameDay.fieldErrors()).hasSize(3);
        }

        /**
         * Confirms a future date is refused and marks all three components as the baseline does.
         */
        @Test
        @DisplayName("refuses a future date and marks all three components")
        void refusesAFutureDate() {
            DateEditResult tomorrow =
                DateEditValidator.validateDateOfBirth(LABEL, "2022-07-19", BUSINESS_DATE);

            assertThat(tomorrow.isValid()).isFalse();
            assertThat(tomorrow.fieldErrors()).hasSize(3);
            assertThat(tomorrow.message()).contains(DateEditValidator.MSG_FUTURE_DATE);
        }

        /**
         * Confirms the rule reads the injected business date and never the wall clock.
         *
         * <p>Assumptions: the same date is accepted against one business date and refused against
         * another, which is only possible if the comparison uses the argument. This is the observable
         * form of the migration's rule that a business date is a parameter, so a reintroduced clock
         * read fails here rather than in a nightly run months later.</p>
         */
        @Test
        @DisplayName("compares against the injected business date, not the clock")
        void comparesAgainstTheInjectedBusinessDate() {
            String date = "2000-06-15";

            assertThat(DateEditValidator.validateDateOfBirth(LABEL, date, BUSINESS_DATE).isValid())
                .isTrue();
            assertThat(DateEditValidator
                    .validateDateOfBirth(LABEL, date, LocalDate.of(1999, 1, 1)).isValid())
                .isFalse();
        }

        /**
         * Confirms a null business date is rejected rather than defaulted to the clock.
         */
        @Test
        @DisplayName("rejects a null business date")
        void rejectsANullBusinessDate() {
            assertThatThrownBy(
                () -> DateEditValidator.validateDateOfBirth(LABEL, "2000-06-15", null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Verifies the Language-Environment evaluation path, which is a different surface from the edit.
     */
    @Nested
    @DisplayName("the Language-Environment path")
    class LanguageEnvironmentPath {

        /**
         * Confirms a valid date against the declared mask reports the valid severity and verdict.
         */
        @Test
        @DisplayName("reports the valid severity, message number and verdict for a valid date")
        void reportsValidForAValidDate() {
            LanguageEnvironmentResult result = DateEditValidator
                .evaluateWithLanguageEnvironment("2022-07-18", DateEditValidator.DATE_FORMAT_MASK);

            assertThat(result.acceptable()).isTrue();
            assertThat(result.severity()).isEqualTo(DateEditValidator.SEVERITY_VALID);
            assertThat(result.verdict().trim()).isEqualTo("Date is valid");
        }

        /**
         * Confirms the compact baseline mask evaluates its own form.
         *
         * <p>Assumptions: two masks are published because the baseline uses both -- the hyphenated form
         * a screen shows and the eight-digit form a record stores. A date is valid against its own mask
         * and not against the other, which is why the mask is an argument rather than a constant.</p>
         */
        @Test
        @DisplayName("evaluates the compact baseline mask against its own form")
        void evaluatesTheCompactMask() {
            LocalDate any = LocalDate.of(2022, 7, 18);
            String compact = "%04d%02d%02d".formatted(any.getYear(), any.getMonthValue(),
                any.getDayOfMonth());

            assertThat(DateEditValidator
                    .evaluateWithLanguageEnvironment(compact,
                        DateEditValidator.BASELINE_DATE_FORMAT_MASK)
                    .acceptable())
                .isTrue();
        }

        /**
         * Confirms a date below the Gregorian floor is reported as an unsupported range.
         *
         * <p>Assumptions: the floor is a property of the Language-Environment service and not of the
         * screen edit, so it produces a distinct feedback code rather than a field message. Asserting
         * all four reported values together is what distinguishes this refusal from a generic one.</p>
         */
        @Test
        @DisplayName("reports a date below the Gregorian floor as an unsupported range")
        void reportsBelowFloorAsUnsupportedRange() {
            LanguageEnvironmentResult result = DateEditValidator
                .evaluateWithLanguageEnvironment("1582-10-14", DateEditValidator.DATE_FORMAT_MASK);

            assertThat(result.acceptable()).isFalse();
            assertThat(result.unsupportedRange()).isTrue();
            assertThat(result.feedbackCode()).isEqualTo(FeedbackCode.UNSUPP_RANGE);
            assertThat(result.severity()).isEqualTo(DateEditValidator.SEVERITY_ERROR);
            assertThat(result.messageNumber()).isEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);
        }

        /**
         * Confirms the first supported day is accepted, pinning the floor from the inside.
         */
        @Test
        @DisplayName("accepts the first supported day")
        void acceptsTheFirstSupportedDay() {
            assertThat(DateEditValidator.GREGORIAN_FLOOR).isEqualTo(LocalDate.of(1582, 10, 15));
            assertThat(DateEditValidator
                    .evaluateWithLanguageEnvironment("1582-10-15", DateEditValidator.DATE_FORMAT_MASK)
                    .acceptable())
                .isTrue();
        }

        /**
         * Confirms the rendered result is exactly the fixed baseline result width.
         *
         * <p>Assumptions: the baseline returns a fixed-width area, so the rendering is a contract and
         * not a convenience. A render that grew or shrank would misalign a caller reading it by
         * position.</p>
         */
        @Test
        @DisplayName("renders at the fixed baseline result width")
        void rendersAtTheFixedResultWidth() {
            LanguageEnvironmentResult valid = DateEditValidator
                .evaluateWithLanguageEnvironment("2022-07-18", DateEditValidator.DATE_FORMAT_MASK);
            LanguageEnvironmentResult refused = DateEditValidator
                .evaluateWithLanguageEnvironment("1582-10-14", DateEditValidator.DATE_FORMAT_MASK);

            assertThat(valid.render()).hasSize(DateEditValidator.RESULT_LENGTH);
            assertThat(refused.render()).hasSize(DateEditValidator.RESULT_LENGTH);
        }

        /**
         * Confirms an invalid date is refused with a non-valid severity rather than accepted.
         *
         * @param date the date under test against the hyphenated mask
         */
        @ParameterizedTest
        @ValueSource(strings = {"2023-02-29", "2022-13-01", "2022-00-01", "2022-07-32", "20XX-07-18"})
        @DisplayName("refuses an invalid date with a non-valid severity")
        void refusesAnInvalidDate(String date) {
            LanguageEnvironmentResult result = DateEditValidator
                .evaluateWithLanguageEnvironment(date, DateEditValidator.DATE_FORMAT_MASK);

            assertThat(result.acceptable()).isFalse();
            assertThat(result.severity()).isNotEqualTo(DateEditValidator.SEVERITY_VALID);
        }

        /**
         * Pins the argument-order trap between the two entry points.
         *
         * <p>Assumptions: passing the mask where the date belongs is the mistake this asserts against,
         * and the outcome that matters is that it does NOT read as valid. A caller who transposes the
         * two arguments gets a refusal, so the mistake surfaces as a failing validation rather than as
         * a silently accepted value.</p>
         */
        @Test
        @DisplayName("transposing the date and mask arguments yields a refusal, not a pass")
        void transposedArgumentsYieldARefusal() {
            LanguageEnvironmentResult transposed = DateEditValidator
                .evaluateWithLanguageEnvironment(DateEditValidator.DATE_FORMAT_MASK, "2022-07-18");

            assertThat(transposed.acceptable()).isFalse();
        }
    }

    /**
     * Verifies the invariants of the result envelope itself.
     */
    @Nested
    @DisplayName("the result envelope")
    class ResultEnvelope {

        /**
         * Confirms the aggregate message never exceeds the width the baseline area declares.
         *
         * <p>Assumptions: the aggregate is rendered into a fixed-width message area, so a message
         * longer than the declared width would be truncated by whatever renders it. Testing the
         * longest refusal available is the useful case, because a short one proves nothing.</p>
         *
         * @param date a refused date whose message is measured
         */
        @ParameterizedTest
        @ValueSource(strings = {"2023-02-29", "2022-04-31", "2022-02-30", "2022-13-99", "2022-07-19"})
        @DisplayName("keeps the aggregate message inside the declared width")
        void keepsTheAggregateMessageInsideTheDeclaredWidth(String date) {
            DateEditResult result = DateEditValidator.validate(LABEL, date);

            assertThat(result.message().length())
                .isLessThanOrEqualTo(DateEditValidator.AGGREGATE_MESSAGE_LENGTH);
        }

        /**
         * Confirms an accepted and a refused result carry the aggregate codes each state publishes.
         */
        @Test
        @DisplayName("publishes distinct aggregate codes for the accepted and refused states")
        void publishesDistinctAggregateCodes() {
            assertThat(DateEditValidator.validate(LABEL, "2022-07-18").aggregateFlagCodes())
                .isEqualTo(DateEditValidator.AGGREGATE_VALID_CODES);
            assertThat(DateEditValidator.AGGREGATE_VALID_CODES)
                .isNotEqualTo(DateEditValidator.AGGREGATE_INVALID_CODES);
        }

        /**
         * Confirms the result carries back the label the caller supplied, unchanged.
         */
        @Test
        @DisplayName("carries back the caller's field label unchanged")
        void carriesBackTheFieldLabel() {
            assertThat(DateEditValidator.validate(LABEL, "2022-07-18").fieldLabel())
                .isEqualTo(LABEL);
            assertThat(DateEditValidator.validate(LABEL, "2023-02-29").fieldLabel())
                .isEqualTo(LABEL);
        }

        /**
         * Confirms the field-error list is immutable, so a caller cannot alter a reported result.
         */
        @Test
        @DisplayName("exposes an immutable field-error list")
        void exposesAnImmutableFieldErrorList() {
            DateEditResult refused = DateEditValidator.validate(LABEL, "2023-02-29");

            assertThatThrownBy(() -> refused.fieldErrors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * Confirms every field error a refusal reports names one of the three date components.
         *
         * <p>Assumptions: the three component names are published constants because a client renders
         * an error against the input that produced it, so a name outside the published three would
         * leave an error with nothing to attach to.</p>
         */
        @Test
        @DisplayName("names only the three published date components in its field errors")
        void namesOnlyThePublishedComponents() {
            DateEditResult refused = DateEditValidator.validate(LABEL, "2022-13-99");

            assertThat(refused.fieldErrors())
                .allSatisfy(error -> assertThat(error.field()).isIn(
                    DateEditValidator.FIELD_YEAR,
                    DateEditValidator.FIELD_MONTH,
                    DateEditValidator.FIELD_DAY));
        }

        /**
         * Confirms the declared widths the envelope is built from are the published values.
         */
        @Test
        @DisplayName("is built from the published baseline widths")
        void isBuiltFromPublishedWidths() {
            assertThat(DateEditValidator.FIELD_LABEL_LENGTH).isEqualTo(25);
            assertThat(DateEditValidator.AGGREGATE_MESSAGE_LENGTH).isEqualTo(75);
            assertThat(DateEditValidator.PACKED_DATE_LENGTH).isEqualTo(8);
            assertThat(DateEditValidator.MASKED_DATE_LENGTH).isEqualTo(10);
            assertThat(DateEditValidator.RESULT_LENGTH).isEqualTo(80);
            assertThat(DateEditValidator.MASK_SEPARATOR).isEqualTo('-');
        }
    }
}
