package com.carddemo.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the two user projections to the claim their overridden string forms make.
 *
 * <p>Assumptions: the fixture values below are chosen to be discriminating rather than
 * representative. Each withheld value begins with characters that occur nowhere in the component
 * labels either record prints, so a leading-fragment check against them reports a real leak instead of
 * a coincidental collision with the word {@code cognitoSub} or {@code firstName}. That property is not
 * left to inspection: every negative assertion in this class is preceded by the same check run against
 * a control string built to contain the value, so a detector that had stopped working would fail the
 * control before it could pass the subject.</p>
 */
class UserRenderingExposureTest {

    /** The shortest fragment length at which a match is evidence rather than coincidence. */
    private static final int SHORTEST_MEANINGFUL_FRAGMENT = 3;

    /** The placeholder both records substitute for a value they refuse to render. */
    private static final String PLACEHOLDER = "REDACTED";

    /** The row locator, an eight-character logon identifier as the baseline declares it. */
    private static final String USER_ID = "ADMIN001";

    /** A given name whose every leading fragment is absent from both records' component labels. */
    private static final String FIRST_NAME = "Zbigniew";

    /** A family name chosen on the same ground, and long enough to exercise the declared width. */
    private static final String LAST_NAME = "Wojciechowski";

    /** The administrator role character, which both records print in full. */
    private static final String USER_TYPE = "A";

    /** The identity provider's subject for the described person, which is never rendered. */
    private static final UUID COGNITO_SUB = UUID.fromString("6f9619ff-8b86-d011-b42d-00cf4fc964ff");

    /**
     * Reports whether a rendering discloses a value in full or through a leading fragment.
     *
     * <p>Assumptions: only leading fragments are searched for, not every substring. A three-character
     * window slid across a family name matches unrelated printed text often enough to be useless --
     * {@code Zbigniew} contains {@code gni} and so does the label {@code cognitoSub} -- whereas a
     * truncation or a masked prefix, which is the failure mode this guards against, is by definition
     * leading. The full value is checked as well, so a rendering that printed it whole is caught by the
     * first iteration regardless.</p>
     *
     * @param rendering the string form under examination
     * @param value the value that must not appear in it
     * @return {@code true} if the rendering contains the whole value or any leading fragment of it at
     *     least {@value #SHORTEST_MEANINGFUL_FRAGMENT} characters long
     */
    private static boolean discloses(String rendering, String value) {
        for (int length = SHORTEST_MEANINGFUL_FRAGMENT; length <= value.length(); length++) {
            if (rendering.contains(value.substring(0, length))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asserts that a value is absent from a rendering, having first proved the check can find it.
     *
     * <p>Refactoring Rationale: the control step is what stops this assertion from going vacuous. A
     * negative check whose detector has been broken by a later edit passes silently and reports a
     * guarantee nobody is making, so the detector is exercised against a string that does contain the
     * value before it is trusted about a string that should not.</p>
     *
     * @param rendering the string form under examination
     * @param value the value that must not appear in it
     */
    private static void assertWithheld(String rendering, String value) {
        assertThat(discloses("control[value=" + value + "]", value))
                .as("the leak detector must be able to find %s at all", value)
                .isTrue();
        assertThat(discloses(rendering, value))
                .as("%s must not appear in %s", value, rendering)
                .isFalse();
    }

    /**
     * Builds the single-row response under test from the discriminating fixture values.
     *
     * @return a populated {@link UserResponse} carrying all five components
     */
    private static UserResponse response() {
        return new UserResponse(USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE, COGNITO_SUB);
    }

    /**
     * Builds the page-item projection under test from the discriminating fixture values.
     *
     * @return a populated {@link UserSummary} carrying all four components
     */
    private static UserSummary summary() {
        return new UserSummary(USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE);
    }

    /**
     * Asserts that the single-row response renders exactly the line its contract promises.
     */
    @Test
    @DisplayName("the single-row response renders exactly the line its contract promises")
    void theResponseRendersExactlyTheDocumentedLine() {
        // WHY : Trade-offs: the whole line is pinned rather than only the placeholder count. A count
        //       assertion would pass unchanged if a component were reordered, renamed, or added
        //       printing its value in full, and each of those is a way the exposure comes back. The
        //       cost is that a deliberate change to the rendering has to be made here too, which is
        //       the acknowledgement this test exists to force.
        assertThat(response()).hasToString(
                "UserResponse[userId=ADMIN001, firstName=REDACTED, lastName=REDACTED, "
                        + "userType=A, cognitoSub=REDACTED]");
    }

    /**
     * Asserts that the single-row response discloses no name and no identity-provider subject.
     */
    @Test
    @DisplayName("the single-row response discloses no name and no identity-provider subject")
    void theResponseWithholdsEveryPersonalComponent() {
        String rendering = response().toString();

        assertWithheld(rendering, FIRST_NAME);
        assertWithheld(rendering, LAST_NAME);
        assertWithheld(rendering, COGNITO_SUB.toString());

        // WHY : Assumptions: the subject is also checked without its separators, because a UUID has a
        //       second textual form and a rendering that emitted the undelimited one would carry the
        //       same 32 significant characters while defeating a check written against the delimited
        //       spelling alone.
        assertWithheld(rendering, COGNITO_SUB.toString().replace("-", ""));
    }

    /**
     * Asserts that the single-row response still names every component it withholds the value of.
     */
    @Test
    @DisplayName("the single-row response still names every component it withholds the value of")
    void theResponseNamesEveryComponentItWithholds() {
        String rendering = response().toString();

        // WHY : Assumptions: naming a component while withholding its value is deliberate and is
        //       asserted rather than tolerated. A reader of a diagnostic needs to know the record
        //       carries a name and a subject at all -- otherwise the line reads as a record with three
        //       components -- and the label discloses nothing about the person it belongs to.
        assertThat(rendering).contains("firstName=", "lastName=", "cognitoSub=");
        assertThat(rendering).contains("userId=" + USER_ID, "userType=" + USER_TYPE);
        assertThat(rendering.split(PLACEHOLDER, -1)).hasSize(4);
    }

    /**
     * Asserts that the page item renders exactly the line its contract promises.
     */
    @Test
    @DisplayName("the page item renders exactly the line its contract promises")
    void theSummaryRendersExactlyTheDocumentedLine() {
        assertThat(summary()).hasToString(
                "UserSummary[userId=ADMIN001, firstName=REDACTED, lastName=REDACTED, userType=A]");
    }

    /**
     * Asserts that the page item discloses neither name.
     */
    @Test
    @DisplayName("the page item discloses neither name")
    void theSummaryWithholdsBothNames() {
        String rendering = summary().toString();

        assertWithheld(rendering, FIRST_NAME);
        assertWithheld(rendering, LAST_NAME);
        assertThat(rendering).contains("firstName=", "lastName=");
        assertThat(rendering.split(PLACEHOLDER, -1)).hasSize(3);
    }

    /**
     * Asserts that a stringified page of items discloses no name from any row.
     */
    @Test
    @DisplayName("a stringified page of items discloses no name from any row")
    void aStringifiedPageWithholdsEveryRowsNames() {
        // WHY : Refactoring Rationale: this is the exposure vector the page item actually faces, and it
        //       is asserted separately because a collection builds its own string form out of its
        //       elements' -- one careless log statement over a page therefore multiplies whatever a
        //       single element discloses by the ten rows the reference grid holds. Asserting one
        //       element would leave the multiplied case untested even though it is the likelier one.
        List<UserSummary> page = List.of(
                summary(),
                new UserSummary("USER0002", "Xiuying", "Kowalczyk", "U"),
                new UserSummary("USER0003", "Quintus", "Vasquez", "U"));
        String rendering = page.toString();

        assertWithheld(rendering, FIRST_NAME);
        assertWithheld(rendering, LAST_NAME);
        assertWithheld(rendering, "Xiuying");
        assertWithheld(rendering, "Kowalczyk");
        assertWithheld(rendering, "Quintus");
        assertWithheld(rendering, "Vasquez");
        assertThat(rendering).contains("USER0002", "USER0003");
        assertThat(rendering.split(PLACEHOLDER, -1)).hasSize(7);
    }

    /**
     * Asserts that narrowing the string form leaves equality, hashing and the accessors untouched.
     */
    @Test
    @DisplayName("narrowing the string form leaves equality, hashing and the accessors untouched")
    void narrowingTheStringFormChangesNothingElse() {
        UserResponse first = response();
        UserResponse second = response();

        // WHY : Assumptions: this is the assertion that distinguishes a narrowed rendering from a
        //       narrowed record. The published contract promises the caller every value, and
        //       serialisation reads the component accessors rather than the string form, so an
        //       override that had also suppressed a component would break the response body while
        //       every exposure assertion above still passed.
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.firstName()).isEqualTo(FIRST_NAME);
        assertThat(first.lastName()).isEqualTo(LAST_NAME);
        assertThat(first.cognitoSub()).isEqualTo(COGNITO_SUB);
        assertThat(summary().firstName()).isEqualTo(FIRST_NAME);
        assertThat(summary().lastName()).isEqualTo(LAST_NAME);

        // WHY : Assumptions: two rows differing only in a withheld component render identically, which
        //       is the cost the override's own rationale records rather than a defect. It is asserted
        //       so that a reader who meets two identical log lines knows the behaviour is intended.
        assertThat(new UserResponse(USER_ID, "Different", "Entirely", USER_TYPE, COGNITO_SUB))
                .hasToString(first.toString())
                .isNotEqualTo(first);
    }
}
