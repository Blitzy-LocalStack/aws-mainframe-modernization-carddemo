package com.carddemo.authorization.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link PendingAuthSummaryResponse#toString()} may carry into a log line.
 *
 * <p>This response is the widest type in the module -- sixty-seven components -- and a record's
 * generated rendering emits every one of them. Populated, that single line carries a composed customer
 * name, two address lines, a telephone number, a credit limit, a cash limit and four further monetary
 * values, plus five transaction identifiers. The screen those components feed renders to one operator
 * already authorised for that one account; a log is retained, aggregated and readable by every holder
 * of log access, so the same values emitted once per served request accumulate into a searchable copy
 * of the customer file, which no screen produces.
 *
 * <p>Assumptions: the assertions name values that must be <em>absent</em>, because a rendering can only
 * regress by gaining a component and an assertion on presence cannot detect a gain. Deleting the
 * override restores the generated rendering silently -- nothing in the build fails and the disclosure
 * appears only in a log no test reads -- so these negative assertions are the only mechanism that turns
 * that deletion into a build failure.
 *
 * <p>Alternatives Considered: asserting the rendered length instead, on the theory that a
 * sixty-seven-component line is simply long. Rejected, because a response with most components absent
 * renders short even from the generated form, so a length bound would pass on exactly the input that
 * matters least and say nothing about the populated case.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class SummaryRenderingTest {

    /** The number of components the rendering must withhold, sixty-seven less the three it names. */
    private static final String WITHHELD_CLAIM = "withheld=64 components";

    /**
     * Seals the opaque row selectors the response refuses to accept in raw form.
     *
     * <p>Assumptions: the response validates each selector as a sealed cursor token in its compact
     * constructor, so a populated fixture cannot be built from an arbitrary string. The key material is
     * a fixed fill rather than a random value because nothing here opens the token again -- only its
     * shape is required -- and a fixed value keeps the fixture reproducible.
     */
    private static CursorToken sealer;

    /**
     * Builds the selector sealer once for the class.
     */
    @BeforeAll
    static void buildSealer() {
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x5A);
        sealer = new CursorToken(keyMaterial, Duration.ofMinutes(5));
    }

    /**
     * Confirms a fully populated summary renders only the screen identity and the populated row count.
     *
     * <p>Assumptions: two of the five row slots are populated, so the count in the rendering is a value
     * the assertion can distinguish from both the empty and the full case. A test that populated all
     * five could not tell a genuine count from a hard-coded five.
     */
    @Test
    void aPopulatedSummaryRendersOnlyTheScreenIdentityAndThePopulatedRowCount() {
        PendingAuthSummaryResponse response = populatedResponse();

        String rendered = response.toString();

        assertThat(rendered).contains("transactionName=CP00");
        assertThat(rendered).contains("programName=COPAUS0C");
        assertThat(rendered).contains("populatedRows=2");
        assertThat(rendered).contains(WITHHELD_CLAIM);

        assertThat(rendered).doesNotContain("Abshire-Lowe");
        assertThat(rendered).doesNotContain("1 Demonstration Way");
        assertThat(rendered).doesNotContain("North Enoshaven");
        assertThat(rendered).doesNotContain("5550101234");
        assertThat(rendered).doesNotContain("2065.00");
        assertThat(rendered).doesNotContain("264.00");
        assertThat(rendered).doesNotContain("193.00");
        assertThat(rendered).doesNotContain("504.77");
        assertThat(rendered).doesNotContain("0000000000683580");
        assertThat(rendered).doesNotContain("0000000000683581");
    }

    /**
     * Confirms the rendered row count reflects the slots that actually carry a transaction identifier.
     *
     * <p>Assumptions: a blank identifier is the baseline's own representation of an unpopulated screen
     * row, so a rendering that counted a blank slot as populated would misreport the screen rather than
     * merely differ in style. Both the fully absent and the blank-filled cases are asserted, because a
     * count implemented with a null check alone would pass the first and fail the second.
     */
    @Test
    void theRowCountIgnoresAbsentAndBlankRowIdentifiers() {
        assertThat(responseWithRowIdentifiers(null, null, null, null, null).toString())
                .contains("populatedRows=0");
        assertThat(responseWithRowIdentifiers("   ", "                ", null, null, null).toString())
                .contains("populatedRows=0");
        assertThat(responseWithRowIdentifiers("0000000000683580", "0000000000683581",
                        "0000000000683582", "0000000000683583", "0000000000683584").toString())
                .contains("populatedRows=5");
    }

    /**
     * Builds a summary carrying a value in every component this rendering must withhold.
     *
     * <p>Assumptions: the personal and monetary components are populated deliberately, because a test
     * built from an empty response would assert the absence of values that were never there and would
     * pass against the generated rendering it exists to reject.
     *
     * @return the response, never {@code null}
     */
    private PendingAuthSummaryResponse populatedResponse() {
        return new PendingAuthSummaryResponse(
                "CP00", "Pending Authorization Summary", "07/18/22", "COPAUS0C",
                "CardDemo", "00:00:00", "00000000007", "Abshire-Lowe", "000000007",
                "1 Demonstration Way", "Y", "North Enoshaven", "5550101234",
                2, 1,
                Money.of("2065.00"), Money.of("264.00"), Money.of("504.77"),
                Money.of("193.00"), Money.of("0.00"), Money.of("12.34"),
                " ", "0000000000683580", "07/18/22", "00:00:00", "0100", "A", "P",
                Money.of("504.77"),
                " ", "0000000000683581", "07/18/22", "00:00:01", "0100", "D", "D",
                Money.of("0.00"),
                " ", null, null, null, null, null, null, null,
                " ", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, " ",
                "Authorization summary displayed",
                sealer.seal("pending-auth-summary:00000000007", "00000000007:26217:104530123"),
                sealer.seal("pending-auth-summary:00000000007", "00000000007:26217:104530124"),
                null, null, null);
    }

    /**
     * Builds a summary whose only populated components are the five row transaction identifiers.
     *
     * <p>Assumptions: the sixty-two remaining components are left absent, which the response admits, so
     * the case isolates the counting rule from everything else the rendering does.
     *
     * @param row1 the row 1 transaction identifier, which may be {@code null} or blank
     * @param row2 the row 2 transaction identifier, which may be {@code null} or blank
     * @param row3 the row 3 transaction identifier, which may be {@code null} or blank
     * @param row4 the row 4 transaction identifier, which may be {@code null} or blank
     * @param row5 the row 5 transaction identifier, which may be {@code null} or blank
     * @return the response, never {@code null}
     */
    private PendingAuthSummaryResponse responseWithRowIdentifiers(String row1, String row2,
            String row3, String row4, String row5) {
        return new PendingAuthSummaryResponse(
                // components 1 through 21: the header, the two counts and the six account amounts
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null,
                // components 22 through 29: row 1, whose transaction identifier is component 23
                null, row1, null, null, null, null, null, null,
                // components 30 through 37: row 2, whose transaction identifier is component 31
                null, row2, null, null, null, null, null, null,
                // components 38 through 45: row 3, whose transaction identifier is component 39
                null, row3, null, null, null, null, null, null,
                // components 46 through 53: row 4, whose transaction identifier is component 47
                null, row4, null, null, null, null, null, null,
                // components 54 through 61: row 5 leads with its identifier and ends with selection
                row5, null, null, null, null, null, null, null,
                // component 62: the message line
                null,
                // components 63 through 67: the five opaque row selectors
                null, null, null, null, null);
    }
}
