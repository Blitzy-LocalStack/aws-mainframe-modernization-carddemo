package com.carddemo.card.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.common.security.SealedSelector;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts what an expiry edit does to the stored day, including the one case it cannot round-trip.
 *
 * <h2>What this test fixes in place</h2>
 *
 * <p>The reference lets a user edit an expiration month and year and never a day: the update map declares
 * the two parts and no day field, and the record written at {@code app/cbl/COCRDUPC.cbl} lines 1467 to 1474
 * composes a day into the stored value without consulting a calendar. The target's column is a true date, so
 * the impossible combination the reference can hold -- the thirty-first of February, say -- has no
 * representation, and the mapper brings the day back to the target month's last day.
 *
 * <p>Assumptions: that clamp was implemented and reasoned about in place but was asserted by no test and was
 * contradicted by the traceability document, which said the stored day is retained and registered no
 * divergence. It is registered now as {@code D-CARD-EXPIRY-DAY-CLAMP}, and these cases are what hold it
 * true: the ordinary case where the day survives untouched, the clamped case in a short month, the leap-year
 * boundary in both directions, and the year-only edit.
 */
class CardExpiryParityTest {

    /** Key material for the sealer the mapper requires; immaterial to every assertion here. */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-expiry-parity-test!".repeat(2).getBytes(StandardCharsets.UTF_8);

    /** The card number the cases edit. */
    private static final String CARD_NUMBER = "4111111111110011";

    /**
     * Asserts that a day that exists in the submitted month survives the edit untouched.
     *
     * <p>Assumptions: this is the ordinary case and the one the traceability document described, so it is
     * asserted first -- the clamp must not be reachable when the day is valid.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a day that exists in the submitted month is carried through unchanged")
    void aValidDaySurvivesUnchanged() {

        assertThat(edited(LocalDate.of(2027, 1, 15), "03", "2029"))
                .isEqualTo(LocalDate.of(2029, 3, 15));
    }

    /**
     * Asserts that a stored day absent from the submitted month is brought back to that month's last day.
     *
     * <p>Assumptions: this is the divergence, stated as a concrete pair. A stored thirty-first edited into
     * February cannot be stored as submitted, is not refused, and becomes the twenty-eighth in a common
     * year.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a stored day absent from the submitted month is brought back to that month's last day")
    void anImpossibleDayIsClamped() {

        assertThat(edited(LocalDate.of(2027, 1, 31), "02", "2027"))
                .as("D-CARD-EXPIRY-DAY-CLAMP: the target column is a true date, so the combination the"
                        + " reference can hold has no representation and the day is brought back")
                .isEqualTo(LocalDate.of(2027, 2, 28));
    }

    /**
     * Asserts that the clamp respects a leap year in both directions.
     *
     * <p>Assumptions: the leap case is asserted because it is where a naive implementation goes wrong. The
     * mapper bounds the day against the SETTLED target month rather than adjusting the year and the month
     * one at a time -- adjusting in one order brings a leap day back twice and in the other order once, so
     * the two orders disagree. These two cases would not both hold under either field-at-a-time order.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the clamp respects a leap year, admitting the twenty-ninth and refusing the thirtieth")
    void theClampRespectsALeapYear() {

        assertThat(edited(LocalDate.of(2027, 1, 29), "02", "2028"))
                .as("2028 is a leap year, so a stored 29th is valid in February and must not be clamped")
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(edited(LocalDate.of(2028, 2, 29), "02", "2027"))
                .as("moving a leap day into a common year must clamp to the 28th")
                .isEqualTo(LocalDate.of(2027, 2, 28));
    }

    /**
     * Asserts that an edit that changes only the year keeps the stored month and day.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("an edit that changes only the year keeps the stored month and day")
    void aYearOnlyEditKeepsMonthAndDay() {

        assertThat(edited(LocalDate.of(2027, 7, 4), "07", "2031"))
                .isEqualTo(LocalDate.of(2031, 7, 4));
    }

    /**
     * Applies an expiry edit to a stored card and reports the date the row ends up holding.
     *
     * <p>Assumptions: the other two editable attributes are held constant across every case, so the only
     * variable is the expiry pair and the stored day it composes against.
     *
     * @param stored the expiration date the row currently holds
     * @param month the submitted month, as two characters
     * @param year the submitted year, as four characters
     * @return the expiration date the row holds after the edit, never {@code null}
     */
    private static LocalDate edited(LocalDate stored, String month, String year) {

        Card card = new Card(CARD_NUMBER, 11L, null, "TEST CARDHOLDER", stored, "Y");
        CardUpdateRequest request =
                new CardUpdateRequest("TEST CARDHOLDER", "Y", month, year, 0);

        return new CardMapper(new SealedSelector(SELECTOR_KEY))
                .applyUpdate(request, card)
                .getExpirationDate();
    }
}
