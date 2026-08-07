package com.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the detail shape's diagnostic rendering and its serialised body to their two different contracts.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review found that this record's generated string form printed the
 * cardholder's embossed name and the full eleven-digit account identifier, so an incidental
 * stringification -- a log line, an assertion message, the detail of an exception the framework raises --
 * carried a personal name joined to an account locator. A log store is the one destination neither the
 * mapper's masking nor the shared advice's path masking reaches, which is what made a rendering nobody
 * wrote the widest exposure in the shape. An earlier revision of the record declined the override on the
 * ground that its package charter assigns suppression to the mapper alone; that reasoning is corrected on
 * the record itself, and this class is the executable half of the correction.</p>
 *
 * <p>Assumptions: the two contracts are asserted SEPARATELY and against each other, because narrowing one
 * of them must not narrow the other. A redaction that also removed a value from the serialised body would
 * be a breaking change to the published contract, and a test that only asserted the string form would not
 * notice. Every case below therefore states which of the two it is about.</p>
 *
 * <p>Assumptions: the values used are synthetic. The masked number is the rendering the published contract
 * gives as its example, and the account identifier and name identify nobody.</p>
 */
class CardDetailRenderingTest {

    /** The masked rendering the mapper admits into this shape, twelve masks and four digits. */
    private static final String MASKED_NUMBER = "************0011";

    /** A synthetic eleven-digit account identifier of the declared form. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A synthetic cardholder name of the declared character set. */
    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** A conforming expiry date. */
    private static final String EXPIRATION_DATE = "2027-06-30";

    /**
     * A shape-valid sealed card selector.
     *
     * <p>Assumptions: shape-valid and NOT redeemable -- every byte of it is filler. Nothing in this
     * class redeems it; what the record's compact constructor requires is the published SHAPE, so a
     * value of that shape is what a rendering test needs, and minting a real one would add a sealer and
     * its key material to the fixture for no assertion.</p>
     *
     * <p>Refactoring Rationale: the value is the fixed-width sealed shape rather than the cursor's
     * three-part versioned form, which an earlier revision used. The two are sealed by different
     * primitives on purpose: a row selector addresses a row that does not move and appears in a
     * bookmarkable route, so it never expires, while a paging cursor names a position and is
     * deliberately short-lived. Using the cursor's form here made this fixture unconstructible once the
     * record's guard was narrowed to the selector's own shape.</p>
     */
    private static final String SEALED_KEY =
            "fake-selector-example-not-a-real-sealed-value-0000000000000";

    /** The shape under test, built once because it is immutable. */
    private static final CardDetail DETAIL = new CardDetail(SEALED_KEY, MASKED_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
            EXPIRATION_DATE, "Y", 3);

    /**
     * Confirms the diagnostic rendering carries none of the three withheld values.
     *
     * <p>Assumptions: each withheld value is asserted absent individually rather than through one combined
     * assertion, so a failure names which one leaked. The account identifier is also asserted absent in its
     * unpadded form, because a rendering that trimmed leading zeros would defeat a search for the padded
     * one while disclosing the same account.</p>
     */
    @Test
    @DisplayName("the rendering carries neither the name, the account identifier nor the expiry")
    void theRenderingCarriesNoWithheldValue() {
        String rendered = DETAIL.toString();

        assertThat(rendered).doesNotContain(EMBOSSED_NAME);
        assertThat(rendered).doesNotContain("JOHN");
        assertThat(rendered).doesNotContain(ACCOUNT_ID);
        assertThat(rendered).doesNotContain("11\"");
        assertThat(rendered).doesNotContain(EXPIRATION_DATE);
    }

    /**
     * Confirms the rendering keeps the shape and the component order a record's generated form has.
     *
     * <p>Assumptions: the shape is asserted because a reader who knows what a record prints must not be led
     * to think some other type produced the line. All seven components are named in contract order, two
     * carrying a placeholder, one a masked rendering and four their value.</p>
     *
     * <p>Refactoring Rationale: the expected line names the SELECTOR and a MASKED account identifier,
     * where an earlier revision of this case expected neither -- it omitted the selector entirely and
     * expected the account fully redacted. Both changes follow from considering the two card shapes
     * together, which {@link CardSummary} records in full: the selector discloses nothing without the
     * deployment key and is the only value left that correlates the line with a request, so omitting it
     * cost the rendering its usefulness for nothing; and the account identifier is an identifier rather
     * than a secret, so masking it leaves enough to correlate two lines about one account while
     * disclosing no complete locator -- and it keeps one policy across both shapes instead of full
     * redaction on this one beside full disclosure on its sibling.</p>
     */
    @Test
    @DisplayName("the rendering names all seven components in contract order")
    void theRenderingNamesAllSixComponentsInOrder() {
        assertThat(DETAIL.toString()).isEqualTo("CardDetail[key=" + SEALED_KEY
                + ", displayCardNumber=" + MASKED_NUMBER
                + ", accountId=*******0011"
                + ", embossedName=REDACTED"
                + ", expirationDate=REDACTED"
                + ", activeStatus=Y"
                + ", version=3]");
    }

    /**
     * Confirms the three retained components are present, so the rendering can still correlate a line.
     *
     * <p>Assumptions: this is the half of the property a redaction breaks by overshooting. The masked
     * number is retained precisely because the mapper admits no other form into this shape, so retaining it
     * discloses four digits that the published response already carries; a rendering that withheld it too
     * would identify no row and correlate nothing.</p>
     */
    @Test
    @DisplayName("the rendering retains the masked number, the status and the token")
    void theRenderingRetainsTheCorrelatingComponents() {
        String rendered = DETAIL.toString();

        assertThat(rendered).contains("displayCardNumber=" + MASKED_NUMBER)
                .contains("activeStatus=Y")
                .contains("version=3");
    }

    /**
     * Confirms the serialised body still carries every published property with its value.
     *
     * <p>Assumptions: this is the assertion that keeps the redaction from becoming a contract change. The
     * framework writes a response from the accessors and never from the string form, so all six values must
     * appear in the body -- including the three the rendering withholds. A body missing one of them would
     * break every caller of the read operation.</p>
     *
     * @throws Exception if the body cannot be written, which would mean the shape is not serialisable
     */
    @Test
    @DisplayName("the serialised body is unchanged and carries all six values")
    void theSerialisedBodyIsUnchanged() throws Exception {
        String body = new ObjectMapper().writeValueAsString(DETAIL);

        assertThat(body).contains("\"displayCardNumber\":\"" + MASKED_NUMBER + "\"")
                .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
                .contains("\"embossedName\":\"" + EMBOSSED_NAME + "\"")
                .contains("\"expirationDate\":\"" + EXPIRATION_DATE + "\"")
                .contains("\"activeStatus\":\"Y\"")
                .contains("\"version\":3");
    }

    /**
     * Confirms a value the rendering withholds is still returned by its accessor.
     *
     * <p>Assumptions: asserted alongside the serialised body because the two could fail independently -- a
     * shape could serialise from a field while an accessor was overridden, or the reverse. The mapper's
     * decision about what a response discloses is unaffected by this change, and that is only true if both
     * paths still carry the value.</p>
     */
    @Test
    @DisplayName("every accessor still returns the value the mapper supplied")
    void everyAccessorStillReturnsItsValue() {
        assertThat(DETAIL.displayCardNumber()).isEqualTo(MASKED_NUMBER);
        assertThat(DETAIL.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(DETAIL.embossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(DETAIL.expirationDate()).isEqualTo(EXPIRATION_DATE);
        assertThat(DETAIL.activeStatus()).isEqualTo("Y");
        assertThat(DETAIL.version()).isEqualTo(3);
    }

    /**
     * Confirms narrowing the rendering left equality and hashing untouched.
     *
     * <p>Assumptions: two shapes differing only in a withheld component print identically and must still
     * compare unequal, because a record's identity is its components and not its printed form. Without this
     * case a redaction that had accidentally been implemented by clearing the components would pass every
     * assertion above except the accessor one.</p>
     */
    @Test
    @DisplayName("redacting the rendering does not merge two distinct shapes")
    void redactingTheRenderingDoesNotMergeDistinctShapes() {
        // WHY : Refactoring Rationale: the two shapes differ in the EMBOSSED NAME alone, and an earlier
        //       revision of this case also gave them different account identifiers. That stopped
        //       demonstrating the property once the account was masked rather than redacted: a masked
        //       rendering preserves the last four digits, so two different accounts print differently
        //       and the two lines were no longer identical. The property under test is that a component
        //       the rendering SUBSTITUTES for cannot make two distinct shapes indistinguishable to
        //       equality, so the shapes must differ only in such a component -- which the name is and
        //       the account, now masked, is not.
        CardDetail other = new CardDetail(SEALED_KEY, MASKED_NUMBER, ACCOUNT_ID, "JANE R PUBLIC",
                EXPIRATION_DATE, "Y", 3);

        assertThat(DETAIL.toString()).isEqualTo(other.toString());
        assertThat(DETAIL).isNotEqualTo(other);
        assertThat(DETAIL.hashCode()).isNotEqualTo(other.hashCode());
    }

    /**
     * Confirms a hostile value placed in a withheld component cannot reach the rendering either.
     *
     * <p>Assumptions: the redaction is by substitution rather than by filtering, so no value of a withheld
     * component can appear -- which is what makes the property hold for values nobody anticipated. The
     * cases submitted are values a validation refusal would plausibly be about.</p>
     *
     * <p>Assumptions: every submitted value is DISTINCTIVE, and a single-letter value is deliberately not
     * among them. An absence assertion is only meaningful for a value that would not otherwise appear in the
     * line, and the placeholder itself spells four distinct letters -- so submitting one of them would fail
     * this case while the redaction was working correctly. The blank case is admitted and its absence
     * assertion skipped for the same reason, a blank occurring naturally between the rendered components.</p>
     *
     * @param hostile the value to place in the embossed name
     */
    @ParameterizedTest(name = "withheld value [{0}]")
    @ValueSource(strings = {"SECRET NAME", "ZZZ QQQ", "                                                  "})
    @DisplayName("no value of a withheld component reaches the rendering")
    void noValueOfAWithheldComponentReachesTheRendering(String hostile) {
        String rendered = new CardDetail(SEALED_KEY, MASKED_NUMBER, ACCOUNT_ID, hostile, EXPIRATION_DATE, "Y", 0)
                .toString();

        assertThat(rendered).contains("embossedName=REDACTED");
        if (!hostile.isBlank()) {
            assertThat(rendered).doesNotContain(hostile);
        }
    }

    /**
     * Confirms the sibling summary shape needs no override, and records why in an executable form.
     *
     * <p>Assumptions: the difference between the two shapes is the LINKAGE rather than the value. The
     * summary carries a masked number, an account identifier and a status and no personal component at all,
     * so its identifier is a pure row locator; the detail joins that identifier to a cardholder's name, and
     * it is the join that makes a line disclosive. Asserting the summary's rendering here rather than
     * leaving the distinction to prose means a future personal component added to the summary would fail
     * this case rather than pass unnoticed.</p>
     */
    @Test
    @DisplayName("the summary shape carries no personal component, so it withholds nothing outright")
    void theSummaryShapeCarriesNoPersonalComponent() {
        List<String> summaryComponents = java.util.Arrays.stream(CardSummary.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(summaryComponents)
                .as("a personal component added here would need the same redaction the detail shape has")
                .containsExactly("key", "displayCardNumber", "accountId", "activeStatus");
        // WHY : Refactoring Rationale: the summary's rendering is asserted to carry a MASKED account
        //       identifier, where an earlier revision of this case asserted it carried the identifier in
        //       full on the ground that a row with no personal component needs no redaction. The ground
        //       holds for the JOIN and not for the value: nothing here is joined to a cardholder's name,
        //       which is why this shape withholds nothing outright, but an eleven-digit account number is
        //       still a complete locator and this rendering reaches the same logs the detail's does. The
        //       reasoning is recorded in full on CardSummary.toString, which also records why one policy
        //       across both card shapes was preferred to two.
        assertThat(new CardSummary(SEALED_KEY, MASKED_NUMBER, ACCOUNT_ID, "Y").toString())
                .contains(MASKED_NUMBER)
                .contains("*******0011")
                .doesNotContain(ACCOUNT_ID);
    }
}
