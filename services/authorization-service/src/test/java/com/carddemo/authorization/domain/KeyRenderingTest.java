package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Pins what {@link PendingAuthDetailKey#toString()} may carry into a log line.
 *
 * <p>Refactoring Rationale: this key rendered its account identifier in full, on the recorded argument
 * that "a key is safe to log in full" because it carries "an account identifier and two clock values and
 * no cardholder data". The premise about cardholder data is true and the conclusion does not follow: the
 * sensitive-data logging contract in {@code docs/architecture/observability.md} names account and
 * customer identifiers in a clause of their own, so what settles the question is what the value IS and
 * not the role it plays in an index. The same correction was applied to the composite keys of the batch
 * and transaction contexts, and this class is what stops this one drifting back.</p>
 *
 * <p>Assumptions: the assertions name the value that must be ABSENT rather than checking the shape of
 * what is present. That direction is deliberate: a rendering can only regress by GAINING a member, and
 * an assertion on presence cannot detect a gain. Two positive assertions accompany them, solely so that
 * a rendering reduced to the empty string could not pass by carrying nothing at all.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.</p>
 */
class KeyRenderingTest {

    /**
     * A synthetic eleven-digit account identifier, at the declared width of {@code PA-ACCT-ID}.
     *
     * <p>Assumptions: the digits are authored rather than taken from a seed row, so they identify no real
     * account, and they are distinctive enough that they cannot occur inside either clock value the
     * rendering does carry -- which is what makes the absence assertion mean what it says. The same value
     * is used by the corresponding cases in the batch, card and transaction contexts, so a reader
     * comparing the four sees one constant rather than four arbitrary ones.</p>
     */
    private static final Long SYNTHETIC_ACCOUNT_ID = 21_820_493_291L;

    /**
     * Confirms the rendering carries the two clock parts and no account identifier.
     *
     * <p>Assumptions: the member NAME is asserted absent alongside the value. A rendering emitting
     * {@code accountId=null} on a partly built key would pass a value-only assertion while still
     * announcing that the component is rendered, and the next populated key would disclose.</p>
     */
    @Test
    void theKeyRendersItsClockPartsAndNoAccountIdentifier() {
        String rendered =
                new PendingAuthDetailKey(SYNTHETIC_ACCOUNT_ID, 20_260_115, 143_000).toString();

        assertThat(rendered).startsWith("PendingAuthDetailKey[");
        assertThat(rendered).contains("authDate=20260115");
        assertThat(rendered).contains("authTime=143000");
        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms a key the provider has not yet populated renders without raising.
     *
     * <p>Refactoring Rationale: this is the path a rendering is most likely to be taken down and least
     * likely to be tested on. The provider constructs an embedded key through its no-argument
     * constructor and populates it afterwards, so a report of a failed flush or a failed conversion can
     * render a key whose parts are all still absent. A rendering that raised there would replace the
     * diagnostic with a second failure that named nothing about the first.</p>
     *
     * <p>Assumptions: the no-argument constructor is reachable from this case only because the test
     * shares the key's package, which is why this class is not placed beside the served-response
     * rendering test in the data-transfer package.</p>
     */
    @Test
    void anUnpopulatedKeyRendersWithoutRaising() {
        PendingAuthDetailKey unpopulated = new PendingAuthDetailKey();

        assertThatCode(unpopulated::toString).doesNotThrowAnyException();
        assertThat(unpopulated.toString()).doesNotContain("accountId");
    }
}
