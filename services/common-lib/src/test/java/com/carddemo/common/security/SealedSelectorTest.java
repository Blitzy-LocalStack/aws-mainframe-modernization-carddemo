package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the sealing selector: that a selector is deterministic, confidential, authenticated,
 * canonical and purpose-separated, and that every one of those five properties is asserted rather than
 * assumed.
 *
 * <p>Assumptions: the property that matters most is CONFIDENTIALITY, because the reason this type exists
 * is that the encoding-based alternative already in the shared kernel does not provide it. Several tests
 * therefore assert the ABSENCE of the protected value and of every fragment of it from the token,
 * including from the token's own base64 decoding, rather than asserting the token's format. The format
 * may change; the absence must not.</p>
 *
 * <p>Assumptions: every key here is a fixed literal so that a token is reproducible across runs.
 * Determinism is a requirement of the type rather than a convenience of the test -- a selector that
 * differed between two computations of the same value would give one row a different address on every
 * page load.</p>
 */
class SealedSelectorTest {

    /** Key material at the required width, fixed so every selector below is reproducible. */
    private static final byte[] KEY =
            "carddemo-sealed-selector-test-key-material-0001".getBytes(StandardCharsets.UTF_8);

    /** A second, different key of the same width, used to show a selector depends on its key. */
    private static final byte[] OTHER_KEY =
            "carddemo-sealed-selector-test-key-material-0002".getBytes(StandardCharsets.UTF_8);

    /** The purpose the fixtures scope their selectors to. */
    private static final String PURPOSE = "carddemo/card/selector";

    /** A protected value of the shape this system actually addresses rows by. */
    private static final String CARD_NUMBER = "4000123456789010";

    /**
     * Confirms the same purpose and value seal to the same selector every time, which is what makes a
     * selector a usable URL.
     */
    @Test
    @DisplayName("the same purpose and value always seal to the same selector")
    void sealIsDeterministic() {
        SealedSelector sealer = new SealedSelector(KEY);

        assertThat(sealer.seal(PURPOSE, CARD_NUMBER))
                .as("a selector that varied per call would give one row a different address on every"
                        + " page load, so no route would be bookmarkable")
                .isEqualTo(sealer.seal(PURPOSE, CARD_NUMBER));
    }

    /**
     * Confirms a second instance over the same key material seals identically, which is what a
     * horizontally scaled service depends on.
     */
    @Test
    @DisplayName("two instances over one key seal identically")
    void sealIsStableAcrossInstances() {
        assertThat(new SealedSelector(KEY).seal(PURPOSE, CARD_NUMBER))
                .as("two tasks of one scaled service hold two instances over one deployment secret, so"
                        + " a selector minted by either has to open on the other")
                .isEqualTo(new SealedSelector(KEY).seal(PURPOSE, CARD_NUMBER));
    }

    /**
     * Confirms a sealed selector opens back to exactly the value it was sealed from.
     */
    @Test
    @DisplayName("a sealed selector opens back to its value")
    void openRecoversTheSealedValue() {
        SealedSelector sealer = new SealedSelector(KEY);

        assertThat(sealer.open(PURPOSE, sealer.seal(PURPOSE, CARD_NUMBER))).isEqualTo(CARD_NUMBER);
    }

    /**
     * Confirms the token discloses neither the value nor any run of its digits, in the token text and in
     * its own decoded bytes.
     *
     * <p>Assumptions: the decoded bytes are searched as well as the text, because a token that merely
     * encoded the value would pass a text search and fail this one. That is precisely the difference
     * between this type and the encoding-based cursor token, so it is the assertion that most needs to
     * exist.</p>
     */
    @Test
    @DisplayName("a selector discloses neither the value nor any digit run of it")
    void selectorDisclosesNothingAboutTheValue() {
        String selector = new SealedSelector(KEY).seal(PURPOSE, CARD_NUMBER);
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(selector),
                StandardCharsets.ISO_8859_1);

        assertThat(selector).doesNotContain(CARD_NUMBER);
        assertThat(decoded)
                .as("an encoded value would survive a decoding, which is the failure mode this type"
                        + " exists to avoid")
                .doesNotContain(CARD_NUMBER);
        for (int start = 0; start + 4 <= CARD_NUMBER.length(); start++) {
            String run = CARD_NUMBER.substring(start, start + 4);
            assertThat(selector).doesNotContain(run);
            assertThat(decoded).doesNotContain(run);
        }
    }

    /**
     * Confirms a selector minted for one purpose does not open under another, and that two purposes give
     * one value two unrelated selectors.
     */
    @Test
    @DisplayName("a selector is purpose-separated")
    void selectorIsPurposeSeparated() {
        SealedSelector sealer = new SealedSelector(KEY);
        String selector = sealer.seal(PURPOSE, CARD_NUMBER);

        assertThat(sealer.seal("carddemo/account/selector", CARD_NUMBER))
                .as("one value under two purposes must not be joinable by an observer who sees both")
                .isNotEqualTo(selector);
        assertThatThrownBy(() -> sealer.open("carddemo/account/selector", selector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not authenticate");
    }

    /**
     * Confirms a selector minted under one key does not open under another.
     */
    @Test
    @DisplayName("a selector does not open under a different key")
    void selectorIsKeyDependent() {
        String selector = new SealedSelector(KEY).seal(PURPOSE, CARD_NUMBER);
        SealedSelector other = new SealedSelector(OTHER_KEY);

        assertThat(other.seal(PURPOSE, CARD_NUMBER)).isNotEqualTo(selector);
        assertThatThrownBy(() -> other.open(PURPOSE, selector))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms an altered selector is refused rather than opened to some other value.
     */
    @Test
    @DisplayName("an altered selector fails to open")
    void alteredSelectorIsRefused() {
        SealedSelector sealer = new SealedSelector(KEY);
        String selector = sealer.seal(PURPOSE, CARD_NUMBER);
        char[] altered = selector.toCharArray();
        // WHY : Assumptions: the LAST character is altered because it falls inside the authentication
        //       tag, and the first would fall inside the vector. Both must be refused, and the tag is
        //       the half a forger would have to produce, so it is the half asserted here.
        altered[altered.length - 1] = altered[altered.length - 1] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> sealer.open(PURPOSE, new String(altered)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not authenticate");
    }

    /**
     * Confirms a selector carrying a vector other than the one its value derives is refused, so exactly
     * one selector addresses one row.
     *
     * <p>Assumptions: the token is rebuilt with a vector taken from a DIFFERENT value's selector and a
     * ciphertext resealed under that vector, which is what a holder of the key could construct. The tag
     * therefore verifies and only the canonical-vector check can refuse it, so this test is the only one
     * that exercises that check.</p>
     *
     * @throws Exception if the platform cannot perform the cipher this test drives directly or the
     *     reflective lookup of the working-key derivation fails, either of which is a broken toolchain
     *     rather than a failed assertion and is therefore allowed to propagate
     */
    @Test
    @DisplayName("a non-canonical selector for a valid value is refused")
    void nonCanonicalSelectorIsRefused() throws Exception {
        SealedSelector sealer = new SealedSelector(KEY);
        byte[] canonical = java.util.Base64.getUrlDecoder().decode(sealer.seal(PURPOSE, CARD_NUMBER));
        byte[] foreignVector = Arrays.copyOf(
                java.util.Base64.getUrlDecoder().decode(sealer.seal(PURPOSE, "4000123456789028")), 12);

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        java.lang.reflect.Method workingKey =
                SealedSelector.class.getDeclaredMethod("workingKey", String.class);
        workingKey.setAccessible(true);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                (javax.crypto.spec.SecretKeySpec) workingKey.invoke(sealer, PURPOSE),
                new javax.crypto.spec.GCMParameterSpec(128, foreignVector));
        cipher.updateAAD(PURPOSE.getBytes(StandardCharsets.UTF_8));
        byte[] resealed = cipher.doFinal(CARD_NUMBER.getBytes(StandardCharsets.UTF_8));

        byte[] forged = new byte[foreignVector.length + resealed.length];
        System.arraycopy(foreignVector, 0, forged, 0, foreignVector.length);
        System.arraycopy(resealed, 0, forged, foreignVector.length, resealed.length);
        String forgedToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forged);

        assertThat(forgedToken).hasSameSizeAs(
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(canonical));
        assertThatThrownBy(() -> sealer.open(PURPOSE, forgedToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical");
    }

    /**
     * Confirms the published length arithmetic matches the selector a sixteen-character value produces,
     * which is the figure the card contract constrains its selector property to.
     */
    @Test
    @DisplayName("the published length arithmetic matches the sealed length")
    void sealedLengthMatchesTheArithmetic() {
        assertThat(SealedSelector.sealedLengthFor(CARD_NUMBER.length()))
                .as("a card contract writes this figure as a literal in a constraint, so the arithmetic"
                        + " and the literal have to be held together by an assertion")
                .isEqualTo(59)
                .isEqualTo(new SealedSelector(KEY).seal(PURPOSE, CARD_NUMBER).length());
    }

    /**
     * Confirms the shape test admits a selector and refuses a raw card number, which is the confusion it
     * exists to prevent.
     */
    @Test
    @DisplayName("the shape test refuses a raw card number and admits a selector")
    void shapeTestSeparatesASelectorFromARawNumber() {
        assertThat(SealedSelector.hasSealedShape(new SealedSelector(KEY).seal(PURPOSE, CARD_NUMBER)))
                .isTrue();
        assertThat(SealedSelector.hasSealedShape(CARD_NUMBER))
                .as("a run of digits is valid URL-safe base64, so only the length bound separates the"
                        + " two and that bound has to be asserted")
                .isFalse();
        assertThat(SealedSelector.hasSealedShape(null)).isFalse();
        assertThat(SealedSelector.hasSealedShape("")).isFalse();
        assertThat(SealedSelector.hasSealedShape("a".repeat(SealedSelector.MAX_TOKEN_LENGTH + 1)))
                .isFalse();
    }

    /**
     * Confirms a value that is not a selector at all is refused with a message that does not echo it.
     *
     * @param offered a candidate a client might present in place of a selector
     */
    @ParameterizedTest
    @ValueSource(strings = {"4000123456789010", "************9010", "not a selector", "...."})
    @DisplayName("a value that is not a selector is refused without being echoed")
    void aNonSelectorIsRefusedWithoutBeingEchoed(String offered) {
        SealedSelector sealer = new SealedSelector(KEY);

        assertThatThrownBy(() -> sealer.open(PURPOSE, offered))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .as("a rejected selector is attacker-supplied text and must not reach a log"
                                + " through a refusal message")
                        .doesNotContain(offered));
    }

    /**
     * Confirms key material below the required width is refused at construction.
     */
    @Test
    @DisplayName("key material below the required width is refused")
    void weakKeyMaterialIsRefused() {
        byte[] tooShort = new byte[SealedSelector.MIN_KEY_LENGTH - 1];

        assertThatThrownBy(() -> new SealedSelector(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(SealedSelector.MIN_KEY_LENGTH));
    }

    /**
     * Confirms the instance copies its key material, so a caller zeroing its own buffer cannot blank the
     * key an instance is still sealing under.
     */
    @Test
    @DisplayName("the instance copies its key material")
    void keyMaterialIsCopied() {
        byte[] mutable = Arrays.copyOf(KEY, KEY.length);
        SealedSelector sealer = new SealedSelector(mutable);
        String before = sealer.seal(PURPOSE, CARD_NUMBER);
        Arrays.fill(mutable, (byte) 0);

        assertThat(sealer.seal(PURPOSE, CARD_NUMBER))
                .as("a caller reading a secret ought to zero its buffer afterwards, and doing so must"
                        + " not change every selector this instance goes on to mint")
                .isEqualTo(before);
    }

    /**
     * Confirms a blank purpose and a blank value are both refused, since either would collapse the
     * separation the arguments exist to provide.
     */
    @Test
    @DisplayName("a blank purpose and a blank value are refused")
    void blankArgumentsAreRefused() {
        SealedSelector sealer = new SealedSelector(KEY);

        assertThatThrownBy(() -> sealer.seal("  ", CARD_NUMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
        assertThatThrownBy(() -> sealer.seal(PURPOSE, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }

    /**
     * Confirms a value longer than the published bound is refused, so an addressing primitive cannot be
     * turned into a bulk encryption one.
     */
    @Test
    @DisplayName("a value longer than the published bound is refused")
    void anOverLongValueIsRefused() {
        SealedSelector sealer = new SealedSelector(KEY);
        String tooLong = "9".repeat(SealedSelector.MAX_VALUE_LENGTH + 1);

        assertThatThrownBy(() -> sealer.seal(PURPOSE, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(SealedSelector.MAX_VALUE_LENGTH));
    }
}
