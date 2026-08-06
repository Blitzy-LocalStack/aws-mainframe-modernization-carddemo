package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the keyed tokeniser: that a token is stable, opaque, purpose-scoped, key-dependent and of the
 * declared width, and that the key material a caller supplies can neither be too weak nor be reached
 * back out.
 *
 * <p>Assumptions: the property that matters most is that a token discloses nothing about the value it
 * stands in for, so several tests assert the ABSENCE of the protected value and of its fragments rather
 * than asserting the token's format. The format may change; the absence must not.</p>
 *
 * <p>Assumptions: every key here is a fixed literal so a token is reproducible across runs. Determinism
 * is a requirement of the type rather than a convenience of the test -- a correlation token that differed
 * between two computations of the same value could never match a reply to its request.</p>
 */
class OpaqueIdentifierTest {

    /** Key material at the required width, fixed so every token below is reproducible. */
    private static final byte[] KEY =
        "carddemo-opaque-identifier-test-key-material-0001".getBytes(StandardCharsets.UTF_8);

    /** A second, different key of the same width, used to show a token depends on its key. */
    private static final byte[] OTHER_KEY =
        "carddemo-opaque-identifier-test-key-material-0002".getBytes(StandardCharsets.UTF_8);

    /** The purpose the fixtures scope their tokens to. */
    private static final String PURPOSE = "auth-correlation";

    /** A protected value of the shape this system actually tokenises. */
    private static final String PROTECTED_VALUE = "4111111111111111" + "TXN000000000001";

    /**
     * Confirms the same purpose and value produce the same token every time.
     */
    @Test
    @DisplayName("the same purpose and value always produce the same token")
    void tokenIsDeterministic() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThat(tokeniser.token(PURPOSE, PROTECTED_VALUE))
            .isEqualTo(tokeniser.token(PURPOSE, PROTECTED_VALUE));
        assertThat(new OpaqueIdentifier(KEY).token(PURPOSE, PROTECTED_VALUE))
            .isEqualTo(tokeniser.token(PURPOSE, PROTECTED_VALUE));
    }

    /**
     * Confirms every token is exactly the declared width.
     *
     * <p>Assumptions: the width is published because a consumer sizes a database column and a message
     * attribute from it, so a token that varied in length would either overflow a column or waste one.
     * Several different values are tokenised because a fixed width is only demonstrated by inputs of
     * differing lengths producing the same output length.</p>
     *
     * @param value the protected value under test
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "a", "4111111111111111", "4111111111111111TXN000000000001",
        "a much longer value than any this system would ever tokenise in practice",
    })
    @DisplayName("every token is exactly the declared width")
    void tokenIsAlwaysTheDeclaredWidth(String value) {
        assertThat(new OpaqueIdentifier(KEY).token(PURPOSE, value))
            .hasSize(OpaqueIdentifier.TOKEN_LENGTH);
        assertThat(OpaqueIdentifier.TOKEN_LENGTH).isEqualTo(22);
    }

    /**
     * Confirms a token discloses neither the protected value nor either of its components.
     *
     * <p>Assumptions: the value tokenised here is a card number followed by a transaction identifier,
     * which is exactly the composite the authorization correlation is built from. Asserting that neither
     * component appears is stronger than asserting the whole composite does not, because a partial leak
     * of the card number is the one that matters.</p>
     */
    @Test
    @DisplayName("a token discloses neither the protected value nor either of its components")
    void tokenDisclosesNothing() {
        String cardNumber = "4111111111111111";
        String transactionId = "TXN000000000001";

        String token = new OpaqueIdentifier(KEY).token(PURPOSE, cardNumber + transactionId);

        assertThat(token)
            .doesNotContain(cardNumber)
            .doesNotContain(transactionId)
            .doesNotContain(cardNumber.substring(0, 6))
            .doesNotContain(cardNumber.substring(12));
    }

    /**
     * Confirms a token uses only characters safe in a URL, a header and a message attribute.
     *
     * <p>Assumptions: a token travels in a query string, a message attribute and a log line, so a
     * character needing escaping in any of those would be escaped differently by each and stop matching.
     * The URL-safe alphabet without padding is what makes one token usable in all three unchanged.</p>
     */
    @Test
    @DisplayName("a token uses only URL-safe characters and carries no padding")
    void tokenIsUrlSafe() {
        String token = new OpaqueIdentifier(KEY).token(PURPOSE, PROTECTED_VALUE);

        assertThat(token).matches("[A-Za-z0-9_-]{" + OpaqueIdentifier.TOKEN_LENGTH + "}");
        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    /**
     * Confirms two different values tokenise differently.
     */
    @Test
    @DisplayName("two different values tokenise differently")
    void differentValuesTokeniseDifferently() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThat(tokeniser.token(PURPOSE, "4111111111111111TXN000000000001"))
            .isNotEqualTo(tokeniser.token(PURPOSE, "4111111111111111TXN000000000002"));
    }

    /**
     * Confirms the same value under two different purposes tokenises differently.
     *
     * <p>Assumptions: this is what the purpose argument exists for. Without scoping, the same card number
     * tokenised for correlation and tokenised for a cursor would produce one identifier that could be
     * matched across the two systems, defeating the separation each was given a token for.</p>
     */
    @Test
    @DisplayName("the same value under different purposes tokenises differently")
    void differentPurposesTokeniseDifferently() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThat(tokeniser.token("auth-correlation", PROTECTED_VALUE))
            .isNotEqualTo(tokeniser.token("card-list-cursor", PROTECTED_VALUE));
    }

    /**
     * Confirms moving the boundary between purpose and value cannot produce the same token.
     *
     * <p>Assumptions: this is the concatenation ambiguity the length prefix exists to close. Without it
     * the purpose "ab" with value "cdef" and the purpose "a" with value "bcdef" would feed identical
     * bytes to the code and tokenise the same, which would silently merge two scopes. Asserting the pairs
     * differ is the observable form of that prefix.</p>
     *
     * @param firstPurpose the purpose of the first pair
     * @param firstValue the value of the first pair
     * @param secondPurpose the purpose of the second pair, one character shorter
     * @param secondValue the value of the second pair, carrying the moved character
     */
    @ParameterizedTest
    @CsvSource({
        "ab, cdef, a, bcdef",
        "auth, correlation, aut, hcorrelation",
        "xy, z, x, yz",
    })
    @DisplayName("moving the boundary between purpose and value changes the token")
    void movingTheBoundaryChangesTheToken(
            String firstPurpose, String firstValue, String secondPurpose, String secondValue) {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThat(firstPurpose + firstValue).isEqualTo(secondPurpose + secondValue);
        assertThat(tokeniser.token(firstPurpose, firstValue))
            .isNotEqualTo(tokeniser.token(secondPurpose, secondValue));
    }

    /**
     * Confirms the same purpose and value under a different key tokenise differently.
     *
     * <p>Assumptions: key dependence is what makes a token unforgeable by anyone without the key. A
     * tokeniser that ignored its key would produce an identifier anyone could recompute from the
     * protected value, which is a hash rather than a keyed code.</p>
     */
    @Test
    @DisplayName("a different key produces a different token for the same input")
    void differentKeyProducesADifferentToken() {
        assertThat(new OpaqueIdentifier(KEY).token(PURPOSE, PROTECTED_VALUE))
            .isNotEqualTo(new OpaqueIdentifier(OTHER_KEY).token(PURPOSE, PROTECTED_VALUE));
    }

    /**
     * Confirms key material below the declared minimum is refused.
     *
     * <p>Assumptions: refusing at construction is the right place, because a weak key produces tokens
     * that are indistinguishable from strong ones by inspection. The refusal names the supplied length so
     * a deployment that under-provisioned its secret learns what it supplied.</p>
     *
     * @param keyLength a key length below the declared minimum
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 16, 31})
    @DisplayName("key material below the declared minimum is refused")
    void shortKeyIsRefused(int keyLength) {
        byte[] tooShort = new byte[keyLength];

        assertThat(keyLength).isLessThan(OpaqueIdentifier.MIN_KEY_LENGTH);
        assertThatThrownBy(() -> new OpaqueIdentifier(tooShort))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(String.valueOf(keyLength));
    }

    /**
     * Confirms key material exactly at the declared minimum is accepted.
     */
    @Test
    @DisplayName("key material exactly at the declared minimum is accepted")
    void keyAtTheMinimumIsAccepted() {
        assertThat(OpaqueIdentifier.MIN_KEY_LENGTH).isEqualTo(32);
        assertThat(new OpaqueIdentifier(new byte[OpaqueIdentifier.MIN_KEY_LENGTH])
                .token(PURPOSE, PROTECTED_VALUE))
            .hasSize(OpaqueIdentifier.TOKEN_LENGTH);
    }

    /**
     * Confirms a null key is refused.
     */
    @Test
    @DisplayName("a null key is refused")
    void nullKeyIsRefused() {
        assertThatThrownBy(() -> new OpaqueIdentifier(null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * Confirms the key is copied at construction, so clearing the caller's array cannot break tokens.
     *
     * <p>Assumptions: a caller reading key material from a secret store SHOULD clear its own copy, which
     * would silently change every token this instance produced afterwards if the array were retained.
     * Zeroing the source and re-tokenising is the only way to demonstrate the copy happened.</p>
     */
    @Test
    @DisplayName("the key is copied, so clearing the caller's array cannot alter later tokens")
    void keyIsCopiedAtConstruction() {
        byte[] callerKey = KEY.clone();
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(callerKey);
        String before = tokeniser.token(PURPOSE, PROTECTED_VALUE);

        Arrays.fill(callerKey, (byte) 0);

        assertThat(tokeniser.token(PURPOSE, PROTECTED_VALUE)).isEqualTo(before);
    }

    /**
     * Confirms a blank purpose is refused rather than yielding an unscoped token.
     *
     * <p>Assumptions: an unscoped token can be correlated across the separate systems that use it, which
     * is precisely what the purpose argument exists to prevent, so accepting a blank one would silently
     * disable the scoping while appearing to apply it.</p>
     *
     * @param blankPurpose a purpose that carries no scope
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("a blank purpose is refused rather than yielding an unscoped token")
    void blankPurposeIsRefused(String blankPurpose) {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThatThrownBy(() -> tokeniser.token(blankPurpose, PROTECTED_VALUE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms a blank protected value is refused rather than yielding one constant.
     *
     * <p>Assumptions: a token over nothing would be a single constant standing in for every value a
     * caller failed to supply, so two unrelated failures would correlate with each other. Refusing is
     * what keeps a token's presence meaningful.</p>
     *
     * @param blankValue a protected value that carries nothing
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("a blank protected value is refused rather than yielding one constant")
    void blankProtectedValueIsRefused(String blankValue) {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThatThrownBy(() -> tokeniser.token(PURPOSE, blankValue))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms a null purpose and a null value are each refused.
     */
    @Test
    @DisplayName("a null purpose and a null protected value are each refused")
    void nullArgumentsAreRefused() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        assertThatThrownBy(() -> tokeniser.token(null, PROTECTED_VALUE))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> tokeniser.token(PURPOSE, null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * Confirms a single-character difference in the protected value changes the token.
     *
     * <p>Assumptions: this is the property the authorization deduplication key depends on. Two adjacent
     * transaction identifiers must tokenise differently, or exactly-once delivery would discard a
     * genuinely distinct authorization as a duplicate -- which is the failure the truncation defect in
     * the wire codec produced before it was fixed.</p>
     */
    @Test
    @DisplayName("a single-character difference changes the token")
    void aSingleCharacterDifferenceChangesTheToken() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String base = "4111111111111111TXN00000000000";

        assertThat(tokeniser.token(PURPOSE, base + "1"))
            .isNotEqualTo(tokeniser.token(PURPOSE, base + "2"));
        assertThat(tokeniser.token(PURPOSE, base))
            .isNotEqualTo(tokeniser.token(PURPOSE, base + "1"));
    }
}
